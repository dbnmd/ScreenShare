package com.screenshare;

import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
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
        setContentView(R.layout.activity_main);

        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        TextView ipText = findViewById(R.id.ip_text);
        ipText.setText("本机IP: " + getLocalIpAddress() + "\n端口: 9998");

        Button startBtn = findViewById(R.id.start_btn);
        Button stopBtn = findViewById(R.id.stop_btn);
        Button hideBtn = findViewById(R.id.hide_btn);

        startBtn.setOnClickListener(v -> startScreenCapture());
        stopBtn.setOnClickListener(v -> stopService());
        hideBtn.setOnClickListener(v -> moveTaskToBack(true));
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
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
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
