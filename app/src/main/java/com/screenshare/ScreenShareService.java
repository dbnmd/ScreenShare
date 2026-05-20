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
import android.os.IBinder;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

public class ScreenShareService extends Service {

    private static final String CHANNEL_ID = "ScreenShareChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final int PORT = 9998;
    
    private Handler mainHandler;
    private ServerSocket serverSocket;
    private volatile Socket clientSocket;
    private volatile OutputStream outputStream;
    private volatile boolean isRunning = false;
    private volatile int frameCount = 0;
    private volatile int reconnectCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(getMainLooper());
        createNotificationChannel();
        
        // 启动前台服务，最高优先级
        startForeground(NOTIFICATION_ID, getNotification());
        
        showToast("Service已创建");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_REDELIVER_INTENT：Service被杀死后自动重启
        startNetworkServer();
        startTestImageSender();
        
        showToast("服务已启动！端口：9998");
        return START_REDELIVER_INTENT; // 关键：被杀死后自动重启！
    }

    private void startNetworkServer() {
        new Thread(() -> {
            try {
                // 如果ServerSocket已经存在，先关闭
                if (serverSocket != null && !serverSocket.isClosed()) {
                    try { serverSocket.close(); } catch (Exception e) {}
                }
                
                serverSocket = new ServerSocket(PORT);
                isRunning = true;
                showToast("ServerSocket启动成功！等待连接...");
                
                while (isRunning) {
                    try {
                        // 等待新连接
                        clientSocket = serverSocket.accept();
                        outputStream = clientSocket.getOutputStream();
                        frameCount = 0;
                        reconnectCount++;
                        
                        showToast("客户端已连接！重连次数: " + reconnectCount);
                        updateNotification();
                        
                    } catch (Exception e) {
                        showToast("连接出错: " + e.getMessage());
                        e.printStackTrace();
                        try { Thread.sleep(1000); } catch (InterruptedException ie) {}
                    }
                }
            } catch (Exception e) {
                showToast("ServerSocket启动失败: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }

    private void startTestImageSender() {
        new Thread(() -> {
            while (isRunning) {
                try {
                    if (outputStream != null && clientSocket != null && clientSocket.isConnected()) {
                        Bitmap testBitmap = null;
                        try {
                            testBitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888);
                            float hue = (frameCount * 5) % 360;
                            testBitmap.eraseColor(Color.HSVToColor(255, new float[]{hue, 0.8f, 0.8f}));
                            
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            testBitmap.compress(Bitmap.CompressFormat.JPEG, 30, baos);
                            byte[] data = baos.toByteArray();
                            
                            byte[] sizeBuffer = ByteBuffer.allocate(4).putInt(data.length).array();
                            outputStream.write(sizeBuffer);
                            outputStream.write(data);
                            outputStream.flush();
                            
                            frameCount++;
                            updateNotification();
                            
                        } catch (Exception e) {
                            // Broken pipe，断开连接，等待重连
                            showToast("发送出错，等待重连...");
                            closeConnectionQuietly();
                        } finally {
                            if (testBitmap != null) {
                                testBitmap.recycle();
                            }
                        }
                    }
                    
                    Thread.sleep(100); // 10fps，更流畅
                    
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void closeConnectionQuietly() {
        try {
            if (outputStream != null) outputStream.close();
            if (clientSocket != null) clientSocket.close();
        } catch (Exception e) {
            // 忽略
        } finally {
            outputStream = null;
            clientSocket = null;
        }
    }

    private void showToast(final String message) {
        mainHandler.post(() -> {
            Toast.makeText(ScreenShareService.this, message, Toast.LENGTH_SHORT).show();
        });
    }

    private void updateNotification() {
        mainHandler.post(() -> {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.notify(NOTIFICATION_ID, getNotification());
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        closeConnectionQuietly();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        showToast("服务已停止");
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
                    NotificationManager.IMPORTANCE_HIGH // 改成HIGH优先级
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification getNotification() {
        String status = clientSocket != null && clientSocket.isConnected() 
            ? "已发送: " + frameCount + "帧 | 重连: " + reconnectCount
            : "等待连接";
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("屏幕共享")
                    .setContentText(status)
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .setOngoing(true) // 常驻通知，无法被划掉
                    .build();
        }
        return new Notification.Builder(this)
                .setContentTitle("屏幕共享")
                .setContentText(status)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
    }
}
