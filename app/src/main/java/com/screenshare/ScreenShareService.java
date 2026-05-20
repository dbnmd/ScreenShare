package com.screenshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
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
    private static final long FRAME_INTERVAL = 1000; // 1秒1帧
    
    private HandlerThread handlerThread;
    private Handler handler;
    private ServerSocket serverSocket;
    
    // 加volatile！确保跨线程可见！
    private volatile Socket clientSocket;
    private volatile OutputStream outputStream;
    
    private volatile boolean isRunning = false;
    private volatile int frameCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, getNotification());
        
        handlerThread = new HandlerThread("ScreenCapture");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
        
        System.gc();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startNetworkServer();
        startTestImageSender();
        
        Toast.makeText(this, "volatile修复版已启动！端口：9998", Toast.LENGTH_SHORT).show();
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
                        closeConnectionQuietly();
                        
                        // 等待新连接
                        clientSocket = serverSocket.accept();
                        outputStream = clientSocket.getOutputStream();
                        frameCount = 0;
                        
                        // 连接成功，更新通知
                        updateNotification();
                        
                    } catch (Throwable t) {
                        t.printStackTrace();
                        try { Thread.sleep(1000); } catch (InterruptedException ie) {}
                    }
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }).start();
    }

    private void startTestImageSender() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;
                
                // 现在有volatile了，能看到最新值！
                if (outputStream != null && clientSocket != null && clientSocket.isConnected()) {
                    Bitmap testBitmap = null;
                    try {
                        // 生成测试图片：颜色随帧号变化
                        testBitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888);
                        float hue = (frameCount * 5) % 360;
                        testBitmap.eraseColor(Color.HSVToColor(255, new float[]{hue, 0.8f, 0.8f}));
                        
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        testBitmap.compress(Bitmap.CompressFormat.JPEG, 30, baos);
                        byte[] data = baos.toByteArray();
                        
                        // 发送
                        byte[] sizeBuffer = ByteBuffer.allocate(4).putInt(data.length).array();
                        outputStream.write(sizeBuffer);
                        outputStream.write(data);
                        outputStream.flush();
                        
                        frameCount++;
                        updateNotification(); // 每发一帧更新通知
                        
                    } catch (Throwable t) {
                        t.printStackTrace();
                        closeConnectionQuietly();
                    } finally {
                        if (testBitmap != null) {
                            testBitmap.recycle();
                        }
                        System.gc();
                    }
                }
                
                handler.postDelayed(this, FRAME_INTERVAL);
            }
        }, 500);
    }

    private void updateNotification() {
        // 在主线程更新通知
        new Handler(getMainLooper()).post(() -> {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.notify(NOTIFICATION_ID, getNotification());
        });
    }

    private void closeConnectionQuietly() {
        try {
            if (outputStream != null) {
                outputStream.close();
            }
            if (clientSocket != null) {
                clientSocket.close();
            }
        } catch (Throwable t) {
            // 忽略
        } finally {
            outputStream = null;
            clientSocket = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
        
        closeConnectionQuietly();
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        
        System.gc();
        
        Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show();
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
        String status = clientSocket != null && clientSocket.isConnected() 
            ? "已连接 | 已发送: " + frameCount + "帧" 
            : "等待连接";
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("volatile修复版")
                    .setContentText(status)
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .build();
        }
        return new Notification.Builder(this)
                .setContentTitle("volatile修复版")
                .setContentText(status)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build();
    }
}
