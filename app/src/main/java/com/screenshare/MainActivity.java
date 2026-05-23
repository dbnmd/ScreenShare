import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_SCREEN_CAPTURE = 1000;
    private MediaProjectionManager mediaProjectionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 代码创建布局，不用xml文件，不会有R包错误
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(64, 64, 64, 64);
        Button startBtn = new Button(this);
        startBtn.setText("启动屏幕共享");
        startBtn.setTextSize(18);
        layout.addView(startBtn);
        setContentView(layout);

        // 权限自动跳转
        PermissionJumpUtil.jumpToPermissionSettings(this);

        // 初始化录屏管理器
        mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        // 按钮点击事件
        startBtn.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Intent captureIntent = mediaProjectionManager.createScreenCaptureIntent();
                startActivityForResult(captureIntent, REQUEST_SCREEN_CAPTURE);
            } else {
                Toast.makeText(this, "安卓版本过低，不支持录屏", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SCREEN_CAPTURE && resultCode == RESULT_OK) {
            // 启动屏幕共享服务
            Intent serviceIntent = new Intent(this, ScreenShareService.class);
            serviceIntent.putExtra("code", resultCode);
            serviceIntent.putExtra("data", data);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Toast.makeText(this, "屏幕共享已启动，端口23456", Toast.LENGTH_SHORT).show();
            finish(); // 启动服务后自动关闭页面，进后台
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopService(new Intent(this, ScreenShareService.class));
    }
}
