package com.screenshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
import java.net.SocketException;
import java.nio.ByteBuffer;

public class ScreenShareService extends Service {

    private static final String CHANNEL_ID = "ScreenShareChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final int PORT = 9998;
    private static final long FRAME_INTERVAL = 500; // 2帧/秒
    
    private HandlerThread handlerThread;
    private Handler handler;
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private OutputStream outputStream;
    private boolean isRunning = false;
    private int frameNumber = 0;

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
        startImageGenerator();
        
        Toast.makeText(this, "测试模式已启动！端口：9998", Toast.LENGTH_SHORT).show();
        return START_STICKY;
    }

    private void startNetworkServer() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                isRunning = true;
                
                while (isRunning) {
                    try {
                        if (clientSocket != null) {
                            try { clientSocket.close(); } catch (Exception e) {}
                        }
                        
                        clientSocket = serverSocket.accept();
                        
                        try {
                            clientSocket.setTcpNoDelay(true);
                            clientSocket.setKeepAlive(true);
                            clientSocket.setSendBufferSize(1024 * 1024);
                        } catch (SocketException e) {
                            e.printStackTrace();
                        }
                        
                        outputStream = clientSocket.getOutputStream();
                        
                    } catch (IOException e) {
                        e.printStackTrace();
                        try { Thread.sleep(1000); } catch (InterruptedException ie) {}
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void startImageGenerator() {
        // 完全不用屏幕采集，纯代码生成测试图片
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;
                
                if (outputStream != null && clientSocket != null && clientSocket.isConnected()) {
                    try {
                        // 生成一张测试图片：带帧号的彩色图片
                        Bitmap testBitmap = generateTestImage(480, 800);
                        
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        testBitmap.compress(Bitmap.CompressFormat.JPEG, 30, baos);
                        byte[] data = baos.toByteArray();
                        
                        // 发送
                        byte[] sizeBuffer = ByteBuffer.allocate(4).putInt(data.length).array();
                        outputStream.write(sizeBuffer);
                        outputStream.write(data);
                        outputStream.flush();
                        
                        testBitmap.recycle();
                        frameNumber++;
                        
                    } catch (Exception e) {
                        e.printStackTrace();
                        resetConnection();
                    }
                }
                
                handler.postDelayed(this, FRAME_INTERVAL);
            }
        }, 1000);
    }

    private Bitmap generateTestImage(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        
        // 背景色随帧号变化，方便看到动态效果
        int hue = (frameNumber * 10) % 360;
        int bgColor = Color.HSVToColor(255, new float[]{hue, 0.8f, 0.8f});
        canvas.drawColor(bgColor);
        
        // 画一个白色的帧号
        Paint paint = new Paint();
        paint.setColor(Color.WHITE);
        paint.setTextSize(100);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("帧: " + frameNumber, width/2, height/2, paint);
        
        // 画一些测试线条
        paint.setColor(Color.BLACK);
        paint.setStrokeWidth(5);
        canvas.drawLine(0, 0, width, height, paint);
        canvas.drawLine(width, 0, 0, height, paint);
        
        return bitmap;
    }

    private void resetConnection() {
        try {
            if (outputStream != null) outputStream.close();
            if (clientSocket != null) clientSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
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
        
        resetConnection();
        try {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("测试模式")
                    .setContentText("端口：9998")
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .build();
        }
        return new Notification.Builder(this)
                .setContentTitle("测试模式")
                .setContentText("端口：9998")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build();
    }
}
