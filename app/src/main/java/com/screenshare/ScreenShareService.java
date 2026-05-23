import android.app.Service;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.net.wifi.WifiManager;
import androidx.core.app.NotificationCompat;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.widget.Toast;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

public class ScreenShareService extends Service {
    public static final int SERVER_PORT = 23456;
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private OutputStream outputStream;
    private MediaProjection mediaProjection;
    private MediaCodec encoder;
    private boolean isStreaming = false;

    // 保活相关
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private static final int NOTIFICATION_ID = 10086;
    private static final String CHANNEL_ID = "screen_share_channel";

    @Override
    public void onCreate() {
        super.onCreate();
        // 1. 保活锁初始化
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreenShare::WakeLock");
        wakeLock.acquire(10*60*1000L);
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ScreenShare::WifiLock");
        wifiLock.acquire();

        // 2. 前台服务通知
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "屏幕共享服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("屏幕共享后台运行保活");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("屏幕共享正在运行")
                .setContentText("端口23456，等待客户端连接")
                .setSmallIcon(R.drawable.ic_launcher) // 换成你自己的图标id
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(NOTIFICATION_ID, notification);

        // 3. 启动服务端线程
        new Thread(this::startServer).start();
    }

    private void startServer() {
        try {
            serverSocket = new ServerSocket(SERVER_PORT, 50);
            showToast("✅ 服务器启动成功，端口23456");
            while (!serverSocket.isClosed()) {
                try {
                    clientSocket = serverSocket.accept();
                    clientSocket.setSoTimeout(15000);
                    clientSocket.setTcpNoDelay(true);
                    clientSocket.setSendBufferSize(1024 * 1024);
                    outputStream = clientSocket.getOutputStream();

                    // ✅ 连接成功立刻发4字节测试数据，避免客户端卡死
                    outputStream.write(new byte[]{0x00, 0x00, 0x00, 0x00});
                    outputStream.flush();

                    showToast("✅ 客户端连接成功");
                    if (!isStreaming) {
                        // 初始化录屏编码
                        mediaProjection = ((MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE))
                                .getMediaProjection(getSharedPreferences("config", MODE_PRIVATE).getInt("code", -1),
                                        (Intent) getSharedPreferences("config", MODE_PRIVATE).getParcelable("data"));
                        initEncoder();
                        isStreaming = true;
                        new Thread(this::encodeLoop).start();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            showToast("❌ 服务器启动失败：" + e.getMessage());
            e.printStackTrace();
        }
    }

    private void initEncoder() throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 720, 1280);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 2000000); // 2Mbps码率
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30); // 30帧
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaFormat.COLOR_FormatSurface);
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mediaProjection.createVirtualDisplay("ScreenShare",
                720, 1280, getResources().getDisplayMetrics().densityDpi,
                0, encoder.createInputSurface(), null, null);
        encoder.start();
    }

    private void encodeLoop() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        try {
            while (isStreaming && clientSocket != null && !clientSocket.isClosed()) {
                int outputBufferId = encoder.dequeueOutputBuffer(bufferInfo, 10000);
                if (outputBufferId >= 0) {
                    ByteBuffer outputBuffer = encoder.getOutputBuffer(outputBufferId);
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        byte[] data = new byte[bufferInfo.size];
                        outputBuffer.get(data);
                        // 先发4字节长度，再发数据，和客户端协议对应
                        outputStream.write(ByteBuffer.allocate(4).putInt(bufferInfo.size).array());
                        outputStream.write(data);
                        outputStream.flush();
                    }
                    encoder.releaseOutputBuffer(outputBufferId, false);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            try {
                clientSocket.close();
            } catch (Exception ex) {}
        }
    }

    private void showToast(String msg) {
        android.os.Handler mainHandler = new android.os.Handler(getMainLooper());
        mainHandler.post(() -> Toast.makeText(ScreenShareService.this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 保存录屏权限数据
        if (intent != null && intent.hasExtra("code") && intent.hasExtra("data")) {
            getSharedPreferences("config", MODE_PRIVATE).edit()
                    .putInt("code", intent.getIntExtra("code", -1))
                    .putParcelable("data", intent.getParcelableExtra("data"))
                    .apply();
        }
        return START_STICKY; // 服务被杀自动重启
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isStreaming = false;
        try {
            if (encoder != null) encoder.stop();
            if (mediaProjection != null) mediaProjection.stop();
            if (clientSocket != null) clientSocket.close();
            if (serverSocket != null) serverSocket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        // 释放保活锁
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        stopForeground(true);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
