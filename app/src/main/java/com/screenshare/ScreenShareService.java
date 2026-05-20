package com.screenshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;

public class ScreenShareService extends Service {

    private static final String CHANNEL_ID = "ScreenShareChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final int PORT = 9998;
    private static final long FRAME_INTERVAL = 500; // 2帧/秒，超慢但超稳
    
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread handlerThread;
    private Handler handler;
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private OutputStream outputStream;
    private boolean isRunning = false;
    private long lastFrameTime = 0;
    private int frameCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, getNotification());
        
        handlerThread = new HandlerThread("ScreenCapture");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("resultCode") && intent.hasExtra("data")) {
            int resultCode = intent.getIntExtra("resultCode", -1);
            Intent data = intent.getParcelableExtra("data");
            
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            mediaProjection = manager.getMediaProjection(resultCode, data);
            
            startNetworkServer();
            startScreenCapture();
            
            Toast.makeText(this, "屏幕共享已启动！端口：9998", Toast.LENGTH_SHORT).show();
        }
        return START_STICKY;
    }

    private void startNetworkServer() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                isRunning = true;
                
                while (isRunning) {
                    try {
                        // 关闭旧连接
                        if (clientSocket != null) {
                            try { clientSocket.close(); } catch (Exception e) {}
                        }
                        
                        clientSocket = serverSocket.accept();
                        
                        // Socket优化选项，提升稳定性
                        try {
                            clientSocket.setTcpNoDelay(true);      // 关闭Nagle算法
                            clientSocket.setKeepAlive(true);       // 开启保活
                            clientSocket.setSoTimeout(0);          // 读超时无限大
                            clientSocket.setSendBufferSize(1024 * 1024); // 增大发送缓冲区
                        } catch (SocketException e) {
                            e.printStackTrace();
                        }
                        
                        outputStream = clientSocket.getOutputStream();
                        
                    } catch (IOException e) {
                        e.printStackTrace();
                        // 出错等待1秒再继续
                        try { Thread.sleep(1000); } catch (InterruptedException ie) {}
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void startScreenCapture() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        
        // 超低分辨率：480p，极致减少数据量
        int width = 480;
        int height = 800;
        int density = metrics.densityDpi;
        
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        
        imageReader.setOnImageAvailableListener(reader -> {
            frameCount++;
            
            // 没有客户端连接就不处理
            if (!isRunning || outputStream == null || clientSocket == null || !clientSocket.isConnected()) {
                return;
            }
            
            // 帧率控制：2帧/秒
            long now = System.currentTimeMillis();
            if (now - lastFrameTime < FRAME_INTERVAL) return;
            lastFrameTime = now;
            
            Image image = null;
            try {
                image = reader.acquireNextImage();
                if (image != null) {
                    processImage(image);
                }
            } catch (Exception e) {
                // 捕获所有异常，绝对不崩溃
                e.printStackTrace();
            } finally {
                if (image != null) {
                    try { image.close(); } catch (Exception e) {}
                }
            }
        }, handler);
        
        handler.postDelayed(() -> {
            try {
                virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ScreenShare",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                    imageReader.getSurface(),
                    null, handler
                );
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 1000);
    }

    private void processImage(Image image) {
        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int width = image.getWidth();
            int height = image.getHeight();
            
            // 创建Bitmap
            Bitmap bitmap = Bitmap.createBitmap(
                rowStride / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            );
            bitmap.copyPixelsFromBuffer(buffer);
            
            // 裁剪
            Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height);
            
            // 超低质量压缩：20%质量，极致减小体积
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            cropped.compress(Bitmap.CompressFormat.JPEG, 20, baos);
            byte[] data = baos.toByteArray();
            
            // 发送前再检查一遍连接状态
            if (outputStream != null && clientSocket != null && clientSocket.isConnected()) {
                // 先发送4字节长度（大端序）
                byte[] sizeBuffer = ByteBuffer.allocate(4).putInt(data.length).array();
                outputStream.write(sizeBuffer);
                outputStream.write(data);
                outputStream.flush();
            }
            
            // 立即回收，避免OOM
            bitmap.recycle();
            cropped.recycle();
            
        } catch (Exception e) {
            // 发送出错只断开连接，不崩溃
            e.printStackTrace();
            resetConnection();
        }
    }

    private void resetConnection() {
        try {
            if (outputStream != null) {
                outputStream.close();
            }
            if (clientSocket != null) {
                clientSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            outputStream = null;
            clientSocket = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception e) {}
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Exception e) {}
        }
        if (mediaProjection != null) {
            try { mediaProjection.stop(); } catch (Exception e) {}
        }
        if (handlerThread != null) {
            try { handlerThread.quitSafely(); } catch (Exception e) {}
        }
        
        resetConnection();
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        Toast.makeText(this, "屏幕共享已停止", Toast.LENGTH_SHORT).show();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "屏幕共享服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification getNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("屏幕共享中")
                    .setContentText("端口：9998")
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .build();
        }
        return new Notification.Builder(this)
                .setContentTitle("屏幕共享中")
                .setContentText("端口：9998")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build();
    }
}
