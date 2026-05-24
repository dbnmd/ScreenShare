import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.widget.Toast;
import java.net.ServerSocket;

public class ScreenShareService extends Service {
    private ServerSocket serverSocket;
    private static final int NOTIFICATION_ID = 10086;
    private static final String CHANNEL_ID = "screen_share_channel";

    @Override
    public void onCreate() {
        super.onCreate();
        // 最外层套try-catch，任何异常都不会闪退
        try {
            // 1. 创建通知渠道（安卓8.0+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "屏幕共享服务",
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("屏幕共享后台运行");
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.createNotificationChannel(channel);
                }
            }

            // 2. 构建通知，用兼容写法
            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(this);
            }
            builder.setContentTitle("屏幕共享正在运行")
                    .setContentText("端口：23456")
                    .setSmallIcon(android.R.drawable.ic_menu_gallery)
                    .setOngoing(true);

            // 3. 启动前台服务
            startForeground(NOTIFICATION_ID, builder.build());

            // 4. 启动Socket服务，单独线程
            new Thread(() -> {
                try {
                    serverSocket = new ServerSocket(23456);
                    // 循环接受连接，不处理数据，先确保不闪退
                    while (!serverSocket.isClosed()) {
                        serverSocket.accept();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();

            Toast.makeText(this, "服务启动成功！端口23456", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Toast.makeText(this, "服务启动失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // 服务被杀自动重启
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        stopForeground(true);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
