package com.screenshare;

import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE = 1000;
    private MediaProjectionManager projectionManager;
    private boolean isServiceRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 用代码创建界面，不依赖 XML 布局
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);

        // IP 地址显示
        TextView ipText = new TextView(this);
        ipText.setText("本机IP: " + getLocalIpAddress() + "\n端口: 9998");
        ipText.setTextSize(18);
        layout.addView(ipText);

        // 开始按钮
        Button startBtn = new Button(this);
        startBtn.setText("开始共享");
        startBtn.setOnClickListener(v -> startScreenCapture());
        layout.addView(startBtn);

        // 停止按钮
        Button stopBtn = new Button(this);
        stopBtn.setText("停止服务");
        stopBtn.setOnClickListener(v -> stopService());
        layout.addView(stopBtn);

        // 隐藏按钮
        Button hideBtn = new Button(this);
        hideBtn.setText("隐藏到后台");
        hideBtn.setOnClickListener(v -> moveTaskToBack(true));
        layout.addView(hideBtn);

        setContentView(layout);

        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
    }

    private void startScreenCapture() {
        Intent captureIntent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(captureIntent, REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK) {
            Intent serviceIntent = new Intent(this, ScreenShareService.class);
            serviceIntent.putExtra("resultCode", resultCode);
            serviceIntent.putExtra("data", data);
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            
            isServiceRunning = true;
            Toast.makeText(this, "屏幕共享服务已启动", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopService() {
        Intent serviceIntent = new Intent(this, ScreenShareService.class);
        stopService(serviceIntent);
        isServiceRunning = false;
        Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show();
    }

    private String getLocalIpAddress() {
        try {
            List interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        boolean isIPv4 = sAddr.indexOf(':') < 0;
                        if (isIPv4) {
                            return sAddr;
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "获取失败";
    }
}
