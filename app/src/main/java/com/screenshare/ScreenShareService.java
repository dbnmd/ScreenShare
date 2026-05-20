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
    private static final long FRAME_INTERVAL = 2000; // 改成2秒1帧，更慢更稳
    
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
        
        // 主动GC一下，减少内存压力
        System.gc();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startNetworkServer();
        startTestImageSender();
        
        Toast.makeText(this, "修复版已启动！端口：9998", Toast.LENGTH_SHORT).show();
        return START_STICKY;
    }

    private void startNetworkServer() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                isRunning = true;
                
                while (isRunning) {
                    try {
                        // 每次新连接前，彻底关闭旧连接
                        closeConnectionQuietly();
                        
                        clientSocket = serverSocket.accept();
                        outputStream = clientSocket.getOutputStream();
                        frameCount = 0; // 新连接重置计数
                        
                    } catch (Throwable t) { // 捕获所有Throwable，包括Error
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
                
                if (outputStream != null && clientSocket != null && clientSocket.isConnected()) {
                    Bitmap testBitmap = null;
                    try {
                        // 生成测试图片
                        testBitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888);
                        testBitmap.eraseColor(Color.RED);
                        
                        // 压缩
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        testBitmap.compress(Bitmap.CompressFormat.JPEG, 30, baos);
                        byte[] data = baos.toByteArray();
                        
                        // 发送
                        byte[] sizeBuffer = ByteBuffer.allocate(4).putInt(data.length).array();
                        outputStream.write(sizeBuffer);
                        outputStream.write(data);
                        outputStream.flush();
                        
                        frameCount++;
                        
                    } catch (Throwable t) { // 捕获所有Throwable，包括OOM Error
                        t.printStackTrace();
                        closeConnectionQuietly();
                    } finally {
                        // 绝对确保Bitmap回收
                        if (testBitmap != null) {
                            testBitmap.recycle();
                        }
                        // 主动GC
                        System.gc();
                    }
                }
                
                handler.postDelayed(this, FRAME_INTERVAL);
            }
        }, 1000);
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
            // 忽略关闭时的错误
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
        
        // 最后再GC一次
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
        String status = "已发送: " + frameCount + "帧";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("修复版测试")
                    .setContentText(status)
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .build();
        }
        return new Notification.Builder(this)
                .setContentTitle("修复版测试")
                .setContentText(status)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build();
    }
}
