package com.screenshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ScreenShareService extends Service {
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "ScreenShareServiceChannel";
    // 换成不会被系统占用的端口，避免8080被拦截
    private static final int SERVER_PORT = 23456;
    private static final int FPS = 60;

    private MediaProjection mediaProjection;
    private MediaProjectionManager mediaProjectionManager;
    private VirtualDisplay virtualDisplay;
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private DataOutputStream outputStream;
    private boolean isStreaming = false;
    private String clientIP = null;
    private Handler mainHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(getMainLooper());
        createNotificationChannel();
        
        // 申请忽略电池优化
        requestIgnoreBatteryOptimization();
        
        // 强制启动前台服务，保证服务不被杀
        startForeground(NOTIFICATION_ID, getNotification());
        Toast.makeText(this, "服务已启动，端口：23456", Toast.LENGTH_LONG).show();

        // 保活代码
        KeepAliveActivity.register(this);

        mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        // 启动服务后立刻启动Socket监听
        startServer();
    }

    private void requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "屏幕共享服务",
                NotificationManager.IMPORTANCE_HIGH // 改成高优先级，避免被系统杀掉
            );
            channel.setShowBadge(true);
            channel.setSound(null, null);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification getNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, notificationIntent, 
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? android.app.PendingIntent.FLAG_IMMUTABLE : 0
        );

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("屏幕共享服务运行中")
                .setContentText("端口：23456 | 点击打开APP")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MAX) // 最高优先级
                .build();
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, getNotification());
    }

    private void startServer() {
        new Thread(() -> {
            try {
                // 绑定所有网络接口，避免只绑定本地回环
                serverSocket = new ServerSocket(SERVER_PORT, 50);
                appendLog("✅ 服务器启动成功！端口：" + SERVER_PORT + "，可以连接了");
                mainHandler.post(() -> Toast.makeText(this, "服务器启动成功，端口23456", Toast.LENGTH_LONG).show());
                
                while (!serverSocket.isClosed()) {
                    try {
                        clientSocket = serverSocket.accept();
                        clientIP = clientSocket.getInetAddress().getHostAddress();
                        appendLog("✅ 客户端连接成功：" + clientIP);
                        outputStream = new DataOutputStream(clientSocket.getOutputStream());
                        
                        if (!isStreaming) {
                            mainHandler.post(() -> startScreenStream());
                        }
                    } catch (IOException e) {
                        if (!serverSocket.isClosed()) {
                            appendLog("❌ 接受连接失败：" + e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                appendLog("❌ 服务器启动失败：" + e.getMessage());
                mainHandler.post(() -> Toast.makeText(this, "服务器启动失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
                e.printStackTrace();
            }
        }).start();
    }

    private void startScreenStream() {
        if (isStreaming || mediaProjectionManager == null) return;
        Intent captureIntent = mediaProjectionManager.createScreenCaptureIntent();
        captureIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(captureIntent);
    }

    public void handleCaptureResult(int resultCode, Intent data) {
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
        if (mediaProjection == null) return;

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int density = metrics.densityDpi;
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;

        // 这里保留你原来的ScreenEncoder逻辑，没有的话先注释也不影响连接测试
        // Surface encoderSurface = yourEncoder.getSurface();
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenShare",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            null, // 测试连接的时候先传null，不影响端口监听
            null,
            null
        );
        isStreaming = true;
        updateNotification();
        appendLog("✅ 屏幕共享已启动");
    }

    private void stopScreenStream() {
        isStreaming = false;
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        try {
            if (outputStream != null) outputStream.close();
            if (clientSocket != null) clientSocket.close();
        } catch (IOException e) {}
        outputStream = null;
        clientSocket = null;
        clientIP = null;
        updateNotification();
        appendLog("屏幕共享已停止");
    }

    private void appendLog(String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String logMsg = "[" + time + "] " + message + "\n";
        System.out.println(logMsg); // 同时打印到控制台，方便调试
        // 如果你有MainActivity的日志显示就放开下面的
        // mainHandler.post(() -> MainActivity.addLog(logMsg));
    }

    @Override
    public void onDestroy() {
        stopScreenStream();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
