package com.github.catvod.crawler;

import com.github.tvbox.osc.util.LOG;

public class SpiderDebug {
    public static void log(Throwable th) {
        try {
            LOG.d(th.getMessage(), th);
        } catch (Throwable th1) {

        }
    }

    public static void log(String msg) {
        try {
            LOG.d(msg);
        } catch (Throwable th1) {

        }
    }

    public static String ec(int i) {
        return "";
    }
}
