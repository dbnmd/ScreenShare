import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_SCREEN_CAPTURE = 1000;
    private static final int REQUEST_ALL_PERMISSIONS = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 最外层try-catch，任何异常都不会闪退
        try {
            // 纯代码写布局，不用XML，不会有资源找不到问题
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(64, 64, 64, 64);
            Button startBtn = new Button(this);
            startBtn.setText("启动屏幕共享");
            startBtn.setTextSize(18);
            layout.addView(startBtn);
            setContentView(layout);

            // 1. 先申请所有需要的权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQUEST_ALL_PERMISSIONS);
                }
            }

            // 2. 按钮点击逻辑
            final MediaProjectionManager mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startBtn.setOnClickListener(v -> {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        Intent captureIntent = mediaProjectionManager.createScreenCaptureIntent();
                        startActivityForResult(captureIntent, REQUEST_SCREEN_CAPTURE);
                    } else {
                        Toast.makeText(this, "安卓版本过低，需要安卓5.0以上", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "启动失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    e.printStackTrace();
                }
            });

        } catch (Exception e) {
            Toast.makeText(this, "启动失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_ALL_PERMISSIONS) {
            StringBuilder msg = new StringBuilder();
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    msg.append(permissions[i]).append(" 未授权\n");
                }
            }
            if (msg.length() > 0) {
                Toast.makeText(this, "请允许所有权限才能正常使用：\n" + msg, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        try {
            if (requestCode == REQUEST_SCREEN_CAPTURE && resultCode == RESULT_OK) {
                // 启动服务
                Intent serviceIntent = new Intent(this, ScreenShareService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                Toast.makeText(this, "服务已启动，通知栏可看到", Toast.LENGTH_SHORT).show();
                finish(); // 启动后关闭页面
            } else {
                Toast.makeText(this, "你拒绝了录屏权限，无法启动服务", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "启动服务失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }
}
