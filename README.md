# 📱 手机屏幕共享APP

## 功能特点

- ✅ **实时屏幕采集** - 基于Android MediaProjection
- ✅ **后台隐藏运行** - 前台Service，通知栏显示状态
- ✅ **开机自动启动** - 开机后自动启动服务
- ✅ **TCP实时传输** - 局域网内低延迟传输
- ✅ **电脑端查看** - 配套Python客户端

## 编译方法

### 1. 环境要求
- Android Studio Hedgehog | 2023.1.1 或更高版本
- Android SDK 34
- JDK 8+

### 2. 编译步骤
1. 打开Android Studio
2. 选择 "Open an existing project"
3. 选择本项目文件夹
4. 等待Gradle同步完成
5. 点击菜单 Build → Build Bundle(s) / APK(s) → Build APK(s)
6. APK生成位置：`app/build/outputs/apk/debug/app-debug.apk`

## 使用方法

### 手机端
1. 安装并打开APP
2. 点击「开始共享」
3. 在弹出的权限对话框中点击「立即开始」
4. （首次启动）会请求屏幕录制权限，允许即可
5. 点击「隐藏到后台」可以把APP退到后台运行

### 电脑端
使用配套的Python客户端：
```python
# 连接手机端，IP和端口在手机APP上显示
# 端口默认: 9998
```

## 注意事项

### 1. 权限说明
- **屏幕录制权限** - 必须允许，用于采集屏幕内容
- **网络权限** - 用于传输屏幕数据
- **开机自启权限** - 需要在手机设置中手动允许

### 2. 兼容性
- Android 5.0 (API 21) 及以上版本
- 建议 Android 8.0+ 以获得最佳稳定性

### 3. 性能优化
- 传输质量可在代码中调整 (JPEG quality 0-100)
- 建议使用5G WiFi以获得最佳体验

## 项目结构

```
屏幕共享手机端/
├── app/
│   ├── src/main/
│   │   ├── java/com/screenshare/
│   │   │   ├── MainActivity.java          # 主界面
│   │   │   ├── ScreenShareService.java    # 屏幕共享服务
│   │   │   └── BootReceiver.java          # 开机自启接收器
│   │   ├── res/layout/
│   │   │   └── activity_main.xml          # 界面布局
│   │   └── AndroidManifest.xml             # 权限和组件配置
│   └── build.gradle
├── build.gradle
└── settings.gradle
```

## 技术说明

- 使用 MediaProjection API 进行屏幕采集
- 使用 ImageReader 获取实时帧
- JPEG 压缩后通过 TCP Socket 传输
- Foreground Service 保证后台运行不被系统杀死
- BOOT_COMPLETED 广播实现开机自启

## 常见问题

**Q: APP重启后需要重新授权？**
A: 这是Android系统的安全机制，每次启动都需要用户主动授权。

**Q: 开机自启不生效？**
A: 需要在手机「设置 → 应用管理 → 自启动管理」中手动允许本APP自启。

**Q: 传输有延迟？**
A: 可以降低JPEG压缩质量，或者降低分辨率。

**Q: 通知栏一直显示？**
A: 这是前台Service的要求，Android 8.0+必须显示通知，可以在设置中隐藏通知。
