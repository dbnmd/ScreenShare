package com.screenshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.RemoteViews;

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
    private ScreenEncoder screenEncoder;
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private DataOutputStream outputStream;
    private boolean isStreaming = false;
    private String clientIP = null;
    private Handler mainHandler;

    private WindowManager windowManager;
    private View floatButton;
    private boolean buttonAdded = false;

    private RemoteViews mViews;
    private boolean showNetSpeed = true;
    private long lastTotalRxBytes = 0;
    private long lastTimeStamp = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(getMainLooper());
        createNotificationChannel();
        
        // 申请忽略电池优化
        requestIgnoreBatteryOptimization();
        
        // 启动前台服务
        startForeground(NOTIFICATION_ID, getNotification());

        // ✅ 新增1像素保活注册
        KeepAliveActivity.register(this);

        mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        VnstatUtils.initVNstat(this);
        mViews = new RemoteViews(getPackageName(), R.layout.notification_layout);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        showFloatingButton();
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

    private void showFloatingButton() {
        if (buttonAdded) return;

        int LAYOUT_FLAG;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LAYOUT_FLAG = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            LAYOUT_FLAG = WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            LAYOUT_FLAG,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 100;
        params.y = 200;

        floatButton = LayoutInflater.from(this).inflate(R.layout.float_button, null);
        View btn = floatButton.findViewById(R.id.float_btn);
        
        btn.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean isClick = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isClick = true;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int deltaX = (int) (event.getRawX() - initialTouchX);
                        int deltaY = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(deltaX) > 5 || Math.abs(deltaY) > 5) {
                            isClick = false;
                            params.x = initialX + deltaX;
                            params.y = initialY + deltaY;
                            windowManager.updateViewLayout(floatButton, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (isClick) {
                            if (isStreaming) stopScreenStream();
                            else startScreenStream();
                        }
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(floatButton, params);
        buttonAdded = true;
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
                .setSmallIcon(R.drawable.ic_launcher_foreground)
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

    public void handleCaptureResult(int resultCode, Intent data) {
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
        if (mediaProjection == null) return;

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int density = metrics.densityDpi;
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;

        screenEncoder = new ScreenEncoder(width, height, FPS, new ScreenEncoder.Callback() {
            @Override
            public void onEncodedFrame(byte[] frameData) {
                if (outputStream != null && isStreaming) {
                    try {
                        outputStream.writeInt(frameData.length);
                        outputStream.write(frameData);
                        outputStream.flush();
                    } catch (IOException e) {
                        appendLog("发送失败: " + e.getMessage());
                        stopScreenStream();
                    }
                }
            }
        });

        try {
            screenEncoder.start();
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenShare",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                screenEncoder.getSurface(),
                null,
                null
            );
            isStreaming = true;
            updateNotification();
            appendLog("屏幕共享已启动");
        } catch (Exception e) {
            appendLog("启动编码器失败: " + e.getMessage());
        }
    }

    private void stopScreenStream() {
        isStreaming = false;
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (screenEncoder != null) {
            screenEncoder.stop();
            screenEncoder = null;
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
        mainHandler.post(() -> MainActivity.addLog(logMsg));
    }

    @Override
    public void onDestroy() {
        stopScreenStream();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {}
        if (buttonAdded && floatButton != null) {
            windowManager.removeView(floatButton);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
