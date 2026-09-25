package io.nekohasekai.sagernet;

import android.content.Context;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * ZyBox RootHelper — 以 root 身份（app_process）反射系统服务实现完整 tethering。
 * 用法: CLASSPATH=<apk> app_process /system/bin io.nekohasekai.sagernet.RootHelper <action> ...
 * actions:
 *   tether <wifi|usb|bluetooth|ethernet> <on|off>   系统级共享开关（带结果验证）
 *   info                                            转发/NAT 诊断
 * 结果约定: 打印 RH_RESULT=0(成功)/1(失败)/2(超时或异常)，并 System.exit 对应码
 */
public class RootHelper {

    static Context ctx;

    public static void main(String[] args) throws Exception {
        System.out.println("RH_START pid=" + android.os.Process.myPid() + " args=" + java.util.Arrays.toString(args));
        if (args.length < 1) { System.out.println("NO_ARGS"); System.exit(2); return; }
        ctx = rootContext(args.length >= 4 ? args[3] : "moe.nb4a");
        switch (args[0]) {
            case "tether":
                if (args.length < 3) { System.out.println("NO_ARGS"); System.exit(2); return; }
                tether(args[1], args[2], args.length >= 4 ? args[3] : null);
                break;
            case "info":
                info();
                break;
            default:
                System.out.println("UNKNOWN " + args[0]);
                System.exit(2);
        }
    }

    static Context systemContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        return (Context) at.getMethod("getSystemContext").invoke(thread);
    }

    // 对齐 librootkotlinx RootProcessBootstrap.createRootPackageContext：
    // 用真实 app 包上下文（opPackageName=moe.nb4a）而非 systemContext("android")，
    // 保证 stopTethering/connector 的 opPackageName 与调用 uid 语义正确（uid 0 + 包名一致）
    static Context rootContext(String pkg) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            Context sys = (Context) at.getMethod("getSystemContext").invoke(thread);
            int userId = android.os.Process.myUid() / 100000;
            Class<?> uh = Class.forName("android.os.UserHandle");
            Object userHandle = uh.getMethod("of", int.class).invoke(null, userId);
            return (Context) sys.getClass().getMethod(
                "createPackageContextAsUser", String.class, int.class, uh)
                .invoke(sys, pkg, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY, userHandle);
        } catch (Throwable e) {
            try { return systemContext(); } catch (Throwable ignored) { return null; }
        }
    }

    static void fail(String msg) {
        System.out.println(msg);
        System.out.println("RH_RESULT=2");
        System.exit(2);
    }

    static void tether(String type, String onOff, String opPackageName) throws Exception {
        int t;
        switch (type) {
            case "wifi": t = 0; break;          // TETHERING_WIFI
            case "usb": t = 1; break;           // TETHERING_USB
            case "bluetooth": t = 2; break;     // TETHERING_BLUETOOTH
            case "ethernet": t = 5; break;      // TETHERING_ETHERNET
            default: fail("UNKNOWN_TYPE"); return;
        }
        boolean on = onOff.equals("on");
        Object tm = ctx.getSystemService("tethering");
        if (tm == null) { fail("NO_TETHERING_SERVICE"); return; }
        Class<?> cbCls = Class.forName("android.net.TetheringManager$StartTetheringCallback");
        Executor exe = Executors.newSingleThreadExecutor();
        if (on) {
            // ===== 开启：startTethering + 回调验证（对齐 TetheringManagerCompat.startTethering）=====
            final CountDownLatch latch = new CountDownLatch(1);
            final int[] result = {-1};
            Object cb = Proxy.newProxyInstance(cbCls.getClassLoader(), new Class[]{cbCls},
                (p, m, a) -> {
                    if (m.getName().equals("onTetheringStarted")) { result[0] = 0; System.out.println("STARTED"); latch.countDown(); }
                    if (m.getName().equals("onTetheringFailed")) { result[0] = 1; System.out.println("FAILED"); latch.countDown(); }
                    return null;
                });
            Class<?> bCls = Class.forName("android.net.TetheringManager$TetheringRequest$Builder");
            Object builder = bCls.getConstructor(int.class).newInstance(t);
            try { bCls.getMethod("setExemptFromEntitlementCheck", boolean.class).invoke(builder, true); } catch (Throwable ignored) { }
            try { bCls.getMethod("setShouldShowEntitlementUi", boolean.class).invoke(builder, true); } catch (Throwable ignored) { }
            Object req = bCls.getMethod("build").invoke(builder);
            try {
                tm.getClass().getMethod("startTethering", req.getClass(), Executor.class, cbCls)
                    .invoke(tm, req, exe, cb);
            } catch (Throwable e) {
                System.out.println("EXC " + e);
                fail("START_EXC");
                return;
            }
            if (!latch.await(2, TimeUnit.SECONDS)) {
                // 回调未达（ColorOS 上 binder 回调不可靠）→ 查实际状态兜底
                if (tetherActive(t)) {
                    System.out.println("STATE_OK");
                    System.out.println("RH_RESULT=0");
                    System.exit(0);
                }
                System.out.println("TIMEOUT");
                System.out.println("RH_RESULT=2");
                System.exit(2);
            }
            System.out.println("RH_RESULT=" + result[0]);
            System.exit(result[0] == 0 ? 0 : 1);
        } else {
            // ===== 关闭：ITetheringConnector.stopTethering + IIntResultListener 验证（对齐 TetheringManagerCompat.stopTethering）=====
            stopViaConnector(tm, t, opPackageName != null ? opPackageName : ctx.getPackageName());
        }
    }

    // 关闭：getConnector → ITetheringConnector.stopTethering(type, opPackageName[, attributionTag], resultListener)
    // onResult(0)=成功（同 VPNHotspot 源码，不依赖不存在的 TetheringManager.stopTethering 签名）
    static void stopViaConnector(Object tm, int t, String pkg) throws Exception {
        try {
            Class<?> itcCls = Class.forName("android.net.ITetheringConnector");
            // 找 TetheringManager 内部 IConnectorConsumer（getConnector 的参数）
            Class<?> consumerCls = null;
            for (Class<?> c : tm.getClass().getDeclaredClasses()) {
                if (c.getSimpleName().contains("ConnectorConsumer")) { consumerCls = c; break; }
            }
            if (consumerCls == null) { fail("NO_CONNECTOR_CONSUMER"); return; }
            final Object[] connectorRef = new Object[1];
            final CountDownLatch connLatch = new CountDownLatch(1);
            Object consumer = Proxy.newProxyInstance(consumerCls.getClassLoader(), new Class[]{consumerCls},
                (p, m, a) -> {
                    if (m.getName().equals("onConnectorAvailable")) { connectorRef[0] = a[0]; connLatch.countDown(); }
                    return null;
                });
            Method getConn = null;
            for (Method m : tm.getClass().getDeclaredMethods()) {
                if (m.getName().equals("getConnector")) { getConn = m; break; }
            }
            if (getConn == null) { fail("NO_GET_CONNECTOR"); return; }
            getConn.setAccessible(true);
            getConn.invoke(tm, consumer);
            if (!connLatch.await(2, TimeUnit.SECONDS)) { fail("GET_CONNECTOR_TIMEOUT"); return; }
            Object connector = connectorRef[0];
            if (connector == null) { fail("CONNECTOR_NULL"); return; }
            // IIntResultListener
            Class<?> irlCls = Class.forName("android.net.IIntResultListener");
            final int[] res = {-1};
            Object listener = Proxy.newProxyInstance(irlCls.getClassLoader(), new Class[]{irlCls},
                (p, m, a) -> {
                    if (m.getName().equals("onResult")) res[0] = (Integer) a[0];
                    return null;
                });
            Method stop = null;
            try {
                stop = itcCls.getMethod("stopTethering", int.class, String.class, String.class, irlCls);
            } catch (NoSuchMethodException ignored) { }
            if (stop == null) stop = itcCls.getMethod("stopTethering", int.class, String.class, irlCls);
            if (stop.getParameterCount() == 4) {
                String attr = null;
                try { attr = ctx.getAttributionTag(); } catch (Throwable ignored) { }
                stop.invoke(connector, t, pkg, attr, listener);
            } else {
                stop.invoke(connector, t, pkg, listener);
            }
            Thread.sleep(1500);
            if (res[0] == 0 || !tetherActive(t)) {
                System.out.println("CONNECTOR_RESULT=" + res[0]);
                System.out.println("RH_RESULT=0");
                System.exit(0);
            }
            System.out.println("CONNECTOR_RESULT=" + res[0]);
            System.out.println("RH_RESULT=1");
            System.exit(1);
        } catch (Throwable e) {
            fail("CONNECTOR_EXC " + e);
        }
    }

    // 是否有活动的 tether 接口（热点/usb/蓝牙/以太网任一已建立）—— 回调不可靠时的状态兜底
    static boolean tetherActive(int t) {
        try {
            Object cm = ctx.getSystemService("connectivity");
            Method m = cm.getClass().getMethod("getTetheredIfaces");
            String[] ifaces = (String[]) m.invoke(cm);
            return ifaces != null && ifaces.length > 0;
        } catch (Throwable e) {
            return false;
        }
    }

    static void info() throws Exception {
        run("ip -o link show");
        run("ip rule show");
        run("iptables -t nat -L POSTROUTING -n --line-numbers");
        run("iptables -L FORWARD -n --line-numbers");
        run("ndc nat status");
        run("settings get global tether_offload_disabled");
    }

    static void run(String cmd) throws Exception {
        System.out.println("### " + cmd);
        Process p = new ProcessBuilder("/system/bin/sh", "-c", cmd).start();
        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
        String line;
        while ((line = br.readLine()) != null) System.out.println(line);
        java.io.BufferedReader er = new java.io.BufferedReader(new java.io.InputStreamReader(p.getErrorStream()));
        while ((line = er.readLine()) != null) System.out.println("[err] " + line);
        p.waitFor();
    }
}
