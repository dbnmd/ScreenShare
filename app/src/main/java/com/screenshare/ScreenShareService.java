import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.widget.Toast;
import java.net.ServerSocket;
import java.net.Socket;

public class ScreenShareService extends Service {
    private ServerSocket serverSocket;
    private static final int NOTIFICATION_ID = 10086;
    private static final String CHANNEL_ID = "screen_share_channel";

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            // 1. 启动前台服务通知，加版本判断
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "屏幕共享服务",
                        NotificationManager.IMPORTANCE_LOW
                );
                NotificationManager manager = getSystemService(NotificationManager.class);
                manager.createNotificationChannel(channel);
            }

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(this);
            }
            builder.setContentTitle("屏幕共享正在运行")
                    .setContentText("端口23456")
                    .setSmallIcon(android.R.drawable.ic_menu_gallery);
            startForeground(NOTIFICATION_ID, builder.build());

            // 2. 启动服务端Socket，加try-catch避免闪退
            new Thread(() -> {
                try {
                    serverSocket = new ServerSocket(23456);
                    while (!serverSocket.isClosed()) {
                        Socket client = serverSocket.accept();
                        // 这里先只接受连接，不处理数据，确保不闪退
                        client.close();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();

            Toast.makeText(this, "服务启动成功，端口23456", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Toast.makeText(this, "服务启动失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
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
