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
import android.view.Surface;

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
    private static final int SERVER_PORT = 8080;
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
        
        // 启动前台服务
        startForeground(NOTIFICATION_ID, getNotification());

        // ✅ 已内置保活代码，不用你加
        KeepAliveActivity.register(this);

        mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
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
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setShowBadge(false);
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
                .setContentTitle("屏幕共享")
                .setContentText(isStreaming ? "正在共享中 | 连接: " + (clientIP != null ? clientIP : "无") : "等待连接")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, getNotification());
    }

    private void startServer() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(SERVER_PORT);
                appendLog("服务器启动，端口: " + SERVER_PORT);
                
                while (!serverSocket.isClosed()) {
                    try {
                        clientSocket = serverSocket.accept();
                        clientIP = clientSocket.getInetAddress().getHostAddress();
                        appendLog("客户端连接: " + clientIP);
                        outputStream = new DataOutputStream(clientSocket.getOutputStream());
                        
                        if (!isStreaming) {
                            mainHandler.post(() -> startScreenStream());
                        }
                    } catch (IOException e) {
                        if (!serverSocket.isClosed()) {
                            appendLog("接受连接失败: " + e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                appendLog("服务器启动失败: " + e.getMessage());
            }
        }).start();
    }

    private void startScreenStream() {
        if (isStreaming || mediaProjectionManager == null) return;
        Intent captureIntent = mediaProjectionManager.createScreenCaptureIntent();
        captureIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(captureIntent);
    }

    // 你原来的ScreenEncoder逻辑可以直接加在这里，不会冲突
    public void handleCaptureResult(int resultCode, Intent data) {
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
        if (mediaProjection == null) return;

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int density = metrics.densityDpi;
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;

        // 这里替换成你原来的ScreenEncoder逻辑就行
        Surface encoderSurface = null; // 替换成你编码器的Surface
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenShare",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            encoderSurface,
            null,
            null
        );
        isStreaming = true;
        updateNotification();
        appendLog("屏幕共享已启动");
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
        // 如果你原来的MainActivity有addLog方法就放开下面的，没有就注释掉
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
