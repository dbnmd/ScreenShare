package com.screenshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
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
    
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private Thread heartbeatThread;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(getMainLooper());
        createNotificationChannel();
        
        // 申请忽略电池优化（代码里动态申请）
        requestIgnoreBatteryOptimization();
        
        // 启动前台服务，用媒体类型（比specialUse优先级高）
        startForeground(NOTIFICATION_ID, getNotification());
        
        // 超级锁
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, 
                "ScreenShare::WakeLock"
            );
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
            
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF, 
                "ScreenShare::WifiLock"
            );
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Exception e) {
            showToast("后台锁获取失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startNetworkServer();
        startTestImageSender();
        startHeartbeat(); // 启动心跳，防止网络断
        
        showToast("服务已启动！端口：9998");
        return START_STICKY; // 改成START_STICKY，被杀后自动重启
    }

    private void requestIgnoreBatteryOptimization() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + getPackageName())
                );
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startHeartbeat() {
        heartbeatThread = new Thread(() -> {
            while (isRunning) {
                try {
                    if (outputStream != null && clientSocket != null && clientSocket.isConnected()) {
                        // 发送1字节心跳，防止华为断TCP
                        outputStream.write(0xFF);
                        outputStream.flush();
                    }
                    Thread.sleep(1000); // 每秒跳一次
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        heartbeatThread.start();
    }

    private void startNetworkServer() {
        new Thread(() -> {
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    try { serverSocket.close(); } catch (Exception e) {}
                }
                
                serverSocket = new ServerSocket(PORT);
                serverSocket.setReuseAddress(true);
                isRunning = true;
                
                while (isRunning) {
                    try {
                        clientSocket = serverSocket.accept();
                        clientSocket.setTcpNoDelay(true);
                        clientSocket.setKeepAlive(true);
                        outputStream = clientSocket.getOutputStream();
                        frameCount = 0;
                        
                        showToast("客户端已连接！");
                        updateNotification();
                        
                    } catch (Exception e) {
                        e.printStackTrace();
                        try { Thread.sleep(1000); } catch (InterruptedException ie) {}
                    }
                }
            } catch (Exception e) {
                showToast("服务启动失败: " + e.getMessage());
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
                            testBitmap.eraseColor(Color.RED);
                            
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
                            closeConnectionQuietly();
                        } finally {
                            if (testBitmap != null) {
                                testBitmap.recycle();
                            }
                        }
                    }
                    
                    Thread.sleep(100);
                    
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
        
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        closeConnectionQuietly();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
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
                    NotificationManager.IMPORTANCE_MAX // 最高优先级！
            );
            channel.setImportance(NotificationManager.IMPORTANCE_MAX);
            channel.enableVibration(false);
            channel.enableLights(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification getNotification() {
        String status = clientSocket != null && clientSocket.isConnected() 
            ? "已发送: " + frameCount + "帧" 
            : "等待连接";
            
        // 点击通知回到APP
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("屏幕共享 - 正在运行")
                    .setContentText(status)
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .setPriority(Notification.PRIORITY_MAX)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .build();
        }
        return new Notification.Builder(this)
                .setContentTitle("屏幕共享 - 正在运行")
                .setContentText(status)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MAX)
                .build();
    }
}
