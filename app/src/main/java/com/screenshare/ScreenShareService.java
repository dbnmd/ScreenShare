package com.screenshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
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
import java.nio.ByteBuffer;

public class ScreenShareService extends Service {

    private static final String CHANNEL_ID = "ScreenShareChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final int PORT = 9998;
    
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
                        clientSocket = serverSocket.accept();
                        outputStream = clientSocket.getOutputStream();
                    } catch (IOException e) {
                        e.printStackTrace();
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
        
        // 降低分辨率，确保能正常工作
        int width = 720;  // 固定720p宽度
        int height = 1280; // 固定720p高度
        int density = metrics.densityDpi;
        
        // 使用ImageFormat.JPEG格式不行，必须用RGBA_8888
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        
        // 确保回调在handler线程执行
        imageReader.setOnImageAvailableListener(reader -> {
            if (!isRunning || outputStream == null) return;
            
            // 控制帧率：最多5帧/秒
            long now = System.currentTimeMillis();
            if (now - lastFrameTime < 200) return;
            lastFrameTime = now;
            
            Image image = null;
            try {
                image = reader.acquireNextImage(); // 用acquireNextImage而不是acquireLatestImage
                if (image != null) {
                    processImage(image);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }, handler);
        
        // 延迟一点创建VirtualDisplay，确保ImageReader准备好
        handler.postDelayed(() -> {
            try {
                virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ScreenShare",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                    imageReader.getSurface(),
                    null, handler
                );
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 500);
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
            
            // 裁剪到正确尺寸
            Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height);
            
            // 压缩为JPEG
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            cropped.compress(Bitmap.CompressFormat.JPEG, 40, baos);
            byte[] data = baos.toByteArray();
            
            // 发送数据：4字节长度 + 数据
            byte[] sizeBuffer = ByteBuffer.allocate(4).putInt(data.length).array();
            outputStream.write(sizeBuffer);
            outputStream.write(data);
            outputStream.flush();
            
            // 回收Bitmap
            bitmap.recycle();
            cropped.recycle();
            
        } catch (Exception e) {
            e.printStackTrace();
            // 出错不崩溃，只是重置连接
            resetConnection();
        }
    }

    private void resetConnection() {
        try {
            if (outputStream != null) {
                outputStream.close();
                outputStream = null;
            }
            if (clientSocket != null) {
                clientSocket.close();
                clientSocket = null;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        
        if (virtualDisplay != null) {
            virtualDisplay.release();
        }
        if (imageReader != null) {
            imageReader.close();
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
        }
        if (handlerThread != null) {
            handlerThread.quitSafely();
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
