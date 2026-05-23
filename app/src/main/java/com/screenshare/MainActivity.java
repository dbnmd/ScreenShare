import android.Manifest;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_SCREEN_CAPTURE = 1000;
    private MediaProjectionManager mediaProjectionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // 如果你的布局文件名不一样，改这里的R.layout.xxx就行

        // 权限自动跳转
        PermissionJumpUtil.jumpToPermissionSettings(this);

        // 初始化录屏管理器
        mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        // 开始按钮，如果你原来的按钮id不是btn_start，改成自己的按钮id
        Button startBtn = findViewById(R.id.btn_start);
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
