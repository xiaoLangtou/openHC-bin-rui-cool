package io.github.gcbond.openhc.wifi;

import android.os.Bundle;
import android.os.IBinder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 车机 WiFi「自动连网」开关的命令行入口。由 OpenHC 经车机本机的网络 ADB、以 shell 身份运行：
 * {@code CLASSPATH=<OpenHC 安装包> app_process /system/bin io.github.gcbond.openhc.wifi.ScanSwitchMain pause|restore|check}。
 * 普通应用调不了这个开关，shell 身份有权限（安卓 7.1～10 查 CONNECTIVITY_INTERNAL，11 起查 NETWORK_SETTINGS）。
 *
 * <p>关掉自动连网后，系统不再为找网络而定时扫描 WiFi；直连投屏时车机 WiFi 开着却没连任何网络，系统会不停地扫，
 * 单射频每扫一次就要离开直连的信道，画面和声音跟着顿一下。接口：安卓 7.1～10 是
 * {@code enableWifiConnectivityManager(boolean)}，安卓 11 起是 {@code allowAutojoinGlobal(boolean, ...)}
 * （14 起多两个参数）。按名字和参数形状找方法，不依赖接口编号（有的车机系统改过编号）。
 *
 * <p>check 只找接口、不改动，打开设置时用来确认整条路走得通。输出一行：{@code OPENHC_SCAN_SWITCH|结果|方法}。
 * 结果：DONE（调用成功）、READY（check：接口在）、UNSUPPORTED（这台系统没有这个接口）、DENIED（系统拒绝了调用方）、
 * FAILED（其他错误）。10 秒内没完成就自行退出，不留有权限的进程。
 */
public final class ScanSwitchMain {
    public static final String HEADER = "OPENHC_SCAN_SWITCH";
    public static final String PAUSE = "pause";
    public static final String RESTORE = "restore";
    public static final String CHECK = "check";

    private static final long DEADLINE_MS = 10_000L;
    private static final int SHELL_UID = 2000;
    private static final int ROOT_UID = 0;
    /** 系统签名进程（OpenHC 用平台密钥签名、sharedUserId=android.uid.system 时）：允许进程内直调。 */
    private static final int SYSTEM_UID = 1000;
    private static final String SHELL_PACKAGE = "com.android.shell";

    private ScanSwitchMain() {
    }

    public static void main(String[] args) {
        Thread deadline = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(DEADLINE_MS);
                } catch (InterruptedException ignored) {
                    return;
                }
                System.exit(2);
            }
        }, "openhc-scan-switch-deadline");
        deadline.setDaemon(true);
        deadline.start();

        System.out.println(runCommand(args.length == 1 ? args[0] : ""));
        System.exit(0);
    }

    /**
     * 执行一条命令并返回输出行（{@code HEADER|结果|方法}）。进程内静态入口：系统签名进程（uid 1000）不走
     * app_process + 网络 ADB 也能直接调用；不装超时看门狗、不调 System.exit，随便在哪调用都安全。
     */
    public static String runCommand(String arg) {
        String result;
        String method = "-";
        try {
            int uid = android.os.Process.myUid();
            if (uid != SHELL_UID && uid != ROOT_UID && uid != SYSTEM_UID) {
                result = "DENIED";
            } else if (!(PAUSE.equals(arg) || RESTORE.equals(arg) || CHECK.equals(arg))) {
                result = "FAILED";
            } else {
                Object wifi = wifiService();
                Method setter = wifi == null ? null : findSetter(wifi);
                if (setter == null) {
                    result = "UNSUPPORTED";
                } else {
                    method = setter.getName();
                    result = CHECK.equals(arg) ? "READY" : invoke(wifi, setter, RESTORE.equals(arg));
                }
            }
        } catch (Throwable t) {
            result = "FAILED";
        }
        return HEADER + "|" + result + "|" + method;
    }

    private static Object wifiService() throws Exception {
        Object binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String.class).invoke(null, "wifi");
        if (!(binder instanceof IBinder)) return null;
        return Class.forName("android.net.wifi.IWifiManager$Stub")
            .getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    /** 安卓 11 起的 allowAutojoinGlobal（第一个参数是 boolean，其余是 String 或 Bundle）优先，否则找老接口。 */
    private static Method findSetter(Object wifi) {
        Method old = null;
        for (Method m : wifi.getClass().getMethods()) {
            Class<?>[] types = m.getParameterTypes();
            if ("allowAutojoinGlobal".equals(m.getName()) && types.length >= 1 && types[0] == boolean.class) {
                boolean shaped = true;
                for (int i = 1; i < types.length; i++) {
                    if (types[i] != String.class && types[i] != Bundle.class) shaped = false;
                }
                if (shaped) return m;
            }
            if ("enableWifiConnectivityManager".equals(m.getName()) && types.length == 1 && types[0] == boolean.class) {
                old = m;
            }
        }
        return old;
    }

    private static String invoke(Object wifi, Method setter, boolean enabled) {
        Class<?>[] types = setter.getParameterTypes();
        Object[] values = new Object[types.length];
        values[0] = enabled;
        for (int i = 1; i < types.length; i++) {
            values[i] = types[i] == String.class ? SHELL_PACKAGE : new Bundle();
        }
        try {
            setter.invoke(wifi, values);
            return "DONE";
        } catch (InvocationTargetException e) {
            return e.getTargetException() instanceof SecurityException ? "DENIED" : "FAILED";
        } catch (Exception e) {
            return "FAILED";
        }
    }
}
