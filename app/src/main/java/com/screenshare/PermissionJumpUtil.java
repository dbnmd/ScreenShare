import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

public class PermissionJumpUtil {

    public static boolean hasAllPermissions(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.os.PowerManager pm = (android.os.PowerManager) activity.getSystemService(Activity.POWER_SERVICE);
            return pm.isIgnoringBatteryOptimizations(activity.getPackageName());
        }
        return true;
    }

    public static void jumpToPermissionSettings(Activity activity) {
        if (hasAllPermissions(activity)) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.os.PowerManager pm = (android.os.PowerManager) activity.getSystemService(Activity.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(activity.getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivityForResult(intent, 1001);
                Toast.makeText(activity, "请点击「允许」，不然切后台会断连", Toast.LENGTH_LONG).show();
                return;
            }
        }

        String brand = Build.BRAND.toLowerCase();
        try {
            switch (brand) {
                case "huawei":
                case "honor":
                case "harmony":
                    Intent hwIntent = new Intent();
                    hwIntent.setComponent(new android.content.ComponentName("com.huawei.systemmanager",
                            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"));
                    hwIntent.putExtra("package", activity.getPackageName());
                    activity.startActivity(hwIntent);
                    Toast.makeText(activity, "找到你的APP，开启自启动、后台运行", Toast.LENGTH_LONG).show();
                    break;
                case "xiaomi":
                case "redmi":
                    Intent miIntent = new Intent();
                    miIntent.setComponent(new android.content.ComponentName("com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity"));
                    miIntent.putExtra("package", activity.getPackageName());
                    activity.startActivity(miIntent);
                    Toast.makeText(activity, "找到你的APP，开启自启动权限", Toast.LENGTH_LONG).show();
                    break;
                case "oppo":
                case "realme":
                    Intent oppoIntent = new Intent();
                    oppoIntent.setComponent(new android.content.ComponentName("com.coloros.safecenter",
                            "com.coloros.safecenter.permission.startup.StartupAppListActivity"));
                    oppoIntent.putExtra("package", activity.getPackageName());
                    activity.startActivity(oppoIntent);
                    Toast.makeText(activity, "找到你的APP，开启后台运行权限", Toast.LENGTH_LONG).show();
                    break;
                case "vivo":
                case "iqoo":
                    Intent vivoIntent = new Intent();
                    vivoIntent.setComponent(new android.content.ComponentName("com.vivo.permissionmanager",
                            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
                    vivoIntent.putExtra("package", activity.getPackageName());
                    activity.startActivity(vivoIntent);
                    Toast.makeText(activity, "找到你的APP，开启后台高耗电权限", Toast.LENGTH_LONG).show();
                    break;
                default:
                    Intent defaultIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    defaultIntent.setData(Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(defaultIntent);
                    Toast.makeText(activity, "请开启后台运行权限", Toast.LENGTH_LONG).show();
                    break;
            }
        } catch (Exception e) {
            Intent defaultIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            defaultIntent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(defaultIntent);
        }
    }
}
