package com.screenshare;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 创建布局
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);

        // IP显示文本
        TextView ipText = new TextView(this);
        ipText.setText("本机IP: " + getLocalIpAddress() + "\n端口: 9998");
        ipText.setTextSize(18);
        layout.addView(ipText);

        // 开始按钮
        Button startBtn = new Button(this);
        startBtn.setText("开始共享");
        startBtn.setOnClickListener(v -> {
            Toast.makeText(this, "功能开发中...", Toast.LENGTH_SHORT).show();
        });
        layout.addView(startBtn);

        // 停止按钮
        Button stopBtn = new Button(this);
        stopBtn.setText("停止共享");
        stopBtn.setOnClickListener(v -> {
            Toast.makeText(this, "功能开发中...", Toast.LENGTH_SHORT).show();
        });
        layout.addView(stopBtn);

        // 隐藏按钮
        Button hideBtn = new Button(this);
        hideBtn.setText("隐藏到后台");
        hideBtn.setOnClickListener(v -> {
            moveTaskToBack(true);
            Toast.makeText(this, "已隐藏到后台", Toast.LENGTH_SHORT).show();
        });
        layout.addView(hideBtn);

        setContentView(layout);
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
