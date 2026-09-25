package io.nekohasekai.sagernet;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * ZyBox RootHelper — 以 root 身份（app_process）反射系统服务实现完整 tethering。
 * 用法: CLASSPATH=<apk> app_process /system/bin io.nekohasekai.sagernet.RootHelper <action> ...
 * actions:
 *   tether <wifi|usb|bluetooth|ethernet> <on|off>   系统级共享开关（同系统设置）
 *   info                                            转发/NAT 诊断
 */
public class RootHelper {

    static Context ctx;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.out.println("NO_ARGS"); return; }
        ctx = systemContext();
        switch (args[0]) {
            case "tether":
                if (args.length < 3) { System.out.println("NO_ARGS"); return; }
                tether(args[1], args[2]);
                break;
            case "info":
                info();
                break;
            default:
                System.out.println("UNKNOWN " + args[0]);
        }
    }

    static Context systemContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        return (Context) at.getMethod("getSystemContext").invoke(thread);
    }

    static void tether(String type, String onOff) throws Exception {
        int t;
        switch (type) {
            case "wifi": t = 0; break;          // TETHERING_WIFI
            case "usb": t = 1; break;           // TETHERING_USB
            case "bluetooth": t = 2; break;     // TETHERING_BLUETOOTH
            case "ethernet": t = 5; break;      // TETHERING_ETHERNET
            default: System.out.println("UNKNOWN_TYPE"); return;
        }
        boolean on = onOff.equals("on");
        Object tm = ctx.getSystemService("tethering");
        if (tm == null) { System.out.println("NO_TETHERING_SERVICE"); return; }
        Class<?> cbCls = Class.forName("android.net.TetheringManager$StartTetheringCallback");
        Executor exe = Executors.newSingleThreadExecutor();
        Object cb = Proxy.newProxyInstance(cbCls.getClassLoader(), new Class[]{cbCls},
            (p, m, a) -> {
                if (m.getName().equals("onTetheringStarted")) System.out.println("STARTED");
                if (m.getName().equals("onTetheringFailed")) System.out.println("FAILED");
                return null;
            });
        if (on) {
            Class<?> bCls = Class.forName("android.net.TetheringManager$TetheringRequest$Builder");
            Object builder = bCls.getConstructor(int.class).newInstance(t);
            try {
                bCls.getMethod("setExemptFromEntitlementCheck", boolean.class).invoke(builder, true);
            } catch (Throwable ignored) { }
            Object req = bCls.getMethod("build").invoke(builder);
            tm.getClass().getMethod("startTethering", req.getClass(), Executor.class, cbCls)
                .invoke(tm, req, exe, cb);
            Thread.sleep(1800);
            System.out.println("DONE");
        } else {
            tm.getClass().getMethod("stopTethering", int.class, Executor.class, cbCls)
                .invoke(tm, t, exe, cb);
            Thread.sleep(800);
            System.out.println("DONE");
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
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line;
        while ((line = br.readLine()) != null) System.out.println(line);
        BufferedReader er = new BufferedReader(new InputStreamReader(p.getErrorStream()));
        while ((line = er.readLine()) != null) System.out.println("[err] " + line);
        p.waitFor();
    }
}
