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
            startFrameChecker(); // 加一个帧率检查器
            
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
        
        int width = 720;
        int height = 1280;
        int density = metrics.densityDpi;
        
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        
        imageReader.setOnImageAvailableListener(reader -> {
            frameCount++; // 每回调一次计数+1
            
            if (!isRunning || outputStream == null) return;
            
            Image image = null;
            try {
                image = reader.acquireNextImage();
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
        
        handler.postDelayed(() -> {
            try {
                virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ScreenShare",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC, // 换个标志试试
                    imageReader.getSurface(),
                    null, handler
                );
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 1000);
    }

    private void startFrameChecker() {
        // 每秒检查一次有没有采集到帧
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;
                
                android.util.Log.d("ScreenShare", "采集到的帧数：" + frameCount);
                
                // 如果没有采集到帧，发一个测试红色图片
                if (frameCount == 0 && outputStream != null) {
                    try {
                        Bitmap testBitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888);
                        testBitmap.eraseColor(Color.RED);
                        
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        testBitmap.compress(Bitmap.CompressFormat.JPEG, 40, baos);
                        byte[] data = baos.toByteArray();
                        
                        byte[] sizeBuffer = ByteBuffer.allocate(4).putInt(data.length).array();
                        outputStream.write(sizeBuffer);
                        outputStream.write(data);
                        outputStream.flush();
                        
                        testBitmap.recycle();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                
                handler.postDelayed(this, 1000);
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
            
            Bitmap bitmap = Bitmap.createBitmap(
                rowStride / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            );
            bitmap.copyPixelsFromBuffer(buffer);
            
            Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height);
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            cropped.compress(Bitmap.CompressFormat.JPEG, 40, baos);
            byte[] data = baos.toByteArray();
            
            byte[] sizeBuffer = ByteBuffer.allocate(4).putInt(data.length).array();
            outputStream.write(sizeBuffer);
            outputStream.write(data);
            outputStream.flush();
            
            bitmap.recycle();
            cropped.recycle();
            
        } catch (Exception e) {
            e.printStackTrace();
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
