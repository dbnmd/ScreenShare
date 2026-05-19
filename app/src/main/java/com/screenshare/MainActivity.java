package com.screenshare;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        TextView text = new TextView(this);
        text.setText("ScreenShare APP 运行成功！");
        text.setTextSize(24);
        text.setPadding(32, 32, 32, 32);
        
        setContentView(text);
    }
}
