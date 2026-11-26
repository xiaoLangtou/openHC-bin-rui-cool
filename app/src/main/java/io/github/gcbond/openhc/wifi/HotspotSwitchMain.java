package io.github.gcbond.openhc.wifi;

import android.os.Bundle;
import android.os.IBinder;
import android.os.ResultReceiver;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 车机系统热点开关的命令行入口。由 OpenHC 经车机本机的网络 ADB、以 shell 身份运行：
 * {@code CLASSPATH=<OpenHC 安装包> app_process /system/bin io.github.gcbond.openhc.wifi.HotspotSwitchMain start|stop|check}。
 * 普通应用开不了系统热点；shell 身份有共享网络要查的权限。
 *
 * <p>开热点走系统的「WiFi 共享网络」（tethering 类型 0），用的是车机设置里保存的热点名称、密码和频段，不改热点设置。
 * 接口：安卓 8～10 是 {@code IConnectivityManager.startTethering(int, ResultReceiver, boolean, String)} 和
 * {@code stopTethering(int, String)}；安卓 11 起共享网络挪到了单独的模块，这里不支持，报 UNSUPPORTED。
 * 按名字和参数形状找方法，不依赖接口编号。
 *
 * <p>start：热点已经开着（或正在开）就不动它，报 ALREADY_ON；否则请求打开，等到系统报热点已开、共享网卡也出来了才报 DONE
 * （网卡地址那时已经配好，调用方马上就能认出热点网卡）。stop：请求关掉（没开着时系统什么都不做）。check 只找接口、不改动。
 * 不读、不打印热点名称和密码。
 *
 * <p>输出一行：{@code OPENHC_HOTSPOT_SWITCH|结果|说明}。结果：DONE、ALREADY_ON、READY（check：接口都在）、
 * UNSUPPORTED（这台系统没有这些接口）、DENIED（系统拒绝了调用方）、FAILED（其他错误、没开成）。
 * 10 秒内没完成就自行退出，不留有权限的进程。
 */
public final class HotspotSwitchMain {
    public static final String HEADER = "OPENHC_HOTSPOT_SWITCH";
    public static final String START = "start";
    public static final String STOP = "stop";
    public static final String CHECK = "check";

    private static final long DEADLINE_MS = 10_000L;
    private static final long RESULT_WAIT_MS = 3_000L;
    private static final long ENABLE_WAIT_MS = 6_000L;
    private static final long POLL_MS = 200L;
    private static final int SHELL_UID = 2000;
    private static final int ROOT_UID = 0;
    /** 系统签名进程（OpenHC 用平台密钥签名、sharedUserId=android.uid.system 时）：允许进程内直调。 */
    private static final int SYSTEM_UID = 1000;
    private static final String SHELL_PACKAGE = "com.android.shell";
    private static final int TETHERING_WIFI = 0;
    /** 系统热点状态：10 正在关、11 关着、12 正在开、13 开着、14 出错。 */
    private static final int AP_ENABLING = 12;
    private static final int AP_ENABLED = 13;
    private static final int AP_FAILED = 14;
    private static final int NO_ANSWER = Integer.MIN_VALUE;

    private HotspotSwitchMain() {
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
        }, "openhc-hotspot-switch-deadline");
        deadline.setDaemon(true);
        deadline.start();

        System.out.println(runCommand(args.length == 1 ? args[0] : ""));
        System.exit(0);
    }

    /**
     * 执行一条命令并返回输出行（{@code HEADER|结果|说明}）。进程内静态入口：系统签名进程（uid 1000）不走
     * app_process + 网络 ADB 也能直接调用；不装超时看门狗、不调 System.exit，随便在哪调用都安全。
     */
    public static String runCommand(String arg) {
        String result;
        String detail = "-";
        try {
            int uid = android.os.Process.myUid();
            if (uid != SHELL_UID && uid != ROOT_UID && uid != SYSTEM_UID) {
                result = "DENIED";
            } else if (!(START.equals(arg) || STOP.equals(arg) || CHECK.equals(arg))) {
                result = "FAILED";
            } else {
                Object wifi = service("wifi", "android.net.wifi.IWifiManager$Stub");
                Object connectivity = service("connectivity", "android.net.IConnectivityManager$Stub");
                Method state = wifi == null ? null : find(wifi, "getWifiApEnabledState");
                Method tethered = connectivity == null ? null : find(connectivity, "getTetheredIfaces");
                Method start = connectivity == null ? null
                    : find(connectivity, "startTethering", int.class, ResultReceiver.class, boolean.class, String.class);
                Method stop = connectivity == null ? null : find(connectivity, "stopTethering", int.class, String.class);
                if (state == null || tethered == null || start == null || stop == null) {
                    result = "UNSUPPORTED";
                } else if (CHECK.equals(arg)) {
                    result = "READY";
                } else if (STOP.equals(arg)) {
                    stop.invoke(connectivity, TETHERING_WIFI, SHELL_PACKAGE);
                    result = "DONE";
                } else {
                    String[] outcome = start(wifi, state, connectivity, tethered, start);
                    result = outcome[0];
                    detail = outcome[1];
                }
            }
        } catch (InvocationTargetException e) {
            result = e.getTargetException() instanceof SecurityException ? "DENIED" : "FAILED";
        } catch (Throwable t) {
            result = "FAILED";
        }
        return HEADER + "|" + result + "|" + detail;
    }

    /** 返回 {结果, 说明}。 */
    private static String[] start(Object wifi, Method state, Object connectivity, Method tethered, Method start) throws Exception {
        int before = (Integer) state.invoke(wifi);
        if (before == AP_ENABLED || before == AP_ENABLING) {
            return new String[]{"ALREADY_ON", "state=" + before};
        }
        final AtomicInteger code = new AtomicInteger(NO_ANSWER);
        final CountDownLatch answered = new CountDownLatch(1);
        ResultReceiver receiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                code.set(resultCode);
                answered.countDown();
            }
        };
        start.invoke(connectivity, TETHERING_WIFI, receiver, false, SHELL_PACKAGE);
        answered.await(RESULT_WAIT_MS, TimeUnit.MILLISECONDS);
        // 0 是成功；没回结果时照样看热点状态。
        if (code.get() != NO_ANSWER && code.get() != 0) return new String[]{"FAILED", "code=" + code.get()};
        long until = System.currentTimeMillis() + ENABLE_WAIT_MS;
        int now = before;
        while (System.currentTimeMillis() < until) {
            now = (Integer) state.invoke(wifi);
            if (now == AP_FAILED) return new String[]{"FAILED", "state=" + now};
            String[] ifaces = (String[]) tethered.invoke(connectivity);
            if (now == AP_ENABLED && ifaces != null && ifaces.length > 0) return new String[]{"DONE", "state=" + now};
            Thread.sleep(POLL_MS);
        }
        return new String[]{"FAILED", "timeout state=" + now};
    }

    private static Object service(String name, String stub) throws Exception {
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name);
        if (!(binder instanceof IBinder)) return null;
        return Class.forName(stub).getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    private static Method find(Object target, String name, Class<?>... types) {
        for (Method m : target.getClass().getMethods()) {
            if (name.equals(m.getName()) && java.util.Arrays.equals(m.getParameterTypes(), types)) return m;
        }
        return null;
    }
}
