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
    
    private HandlerThread handlerThread;
    private Handler handler;
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private OutputStream outputStream;
    private boolean isRunning = false;
    private boolean frameSent = false; // 只发1帧

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
        startNetworkServer();
        startTestImageSender();
        
        Toast.makeText(this, "只发1帧测试！端口：9998", Toast.LENGTH_SHORT).show();
        return START_STICKY;
    }

    private void startNetworkServer() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                isRunning = true;
                frameSent = false; // 每次新连接重置标志
                
                while (isRunning) {
                    try {
                        clientSocket = serverSocket.accept();
                        outputStream = clientSocket.getOutputStream();
                        frameSent = false; // 新连接，重新发
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void startTestImageSender() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;
                
                // 还没发过帧，就发1帧
                if (!frameSent && outputStream != null) {
                    try {
                        Bitmap testBitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888);
                        testBitmap.eraseColor(Color.RED);
                        
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        testBitmap.compress(Bitmap.CompressFormat.JPEG, 50, baos);
                        byte[] data = baos.toByteArray();
                        
                        byte[] sizeBuffer = ByteBuffer.allocate(4).putInt(data.length).array();
                        outputStream.write(sizeBuffer);
                        outputStream.write(data);
                        outputStream.flush();
                        
                        testBitmap.recycle();
                        frameSent = true; // 标记已发送，不再发了
                        
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                
                // 继续循环，但不再发数据
                handler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
        try {
            if (outputStream != null) outputStream.close();
            if (clientSocket != null) clientSocket.close();
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        
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
        String status = frameSent ? "已发送1帧" : "等待连接";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("只发1帧测试")
                    .setContentText(status)
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .build();
        }
        return new Notification.Builder(this)
                .setContentTitle("只发1帧测试")
                .setContentText(status)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build();
    }
}
