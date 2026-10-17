package com.pmca.intervalcapture;

/**
 * AppLog —— 轻量日志工具
 * <p>
 * 为什么要写日志到 SD 卡：
 *   1. 相机屏幕只有 640x480，出错时能显示的信息有限；
 *   2. 相机没有 USB 调试口可直接看 logcat（需要 PMCA-RE 的 tweak 开启 adb/telnet）,
 *      把关键事件写到 /mnt/sdcard/PMCACAP/LOG.TXT 后，把卡插到电脑上就能看到；
 *   3. 长时间无人值守拍摄结束后，用户可以据此核对每一张的触发时间与耗时。
 * <p>
 * 涉及的非索尼 API：java.io / android.util.Log，均为平台基础类，不涉及任何安卓专属
 * 相机控制函数（相机控制一律走 com.sony.scalar.*）。
 */
public final class AppLog {

    private static final String TAG = "PMCACAP";

    /** SD 卡上的日志目录（相机 SD 卡的挂载点由 Environment 提供） */
    public static final String SD_DIR_NAME = "PMCACAP";

    private static java.io.File logFile;
    private static boolean sdWritable = true;
    /** 日志文件超过该大小时截断重写，避免长时间拍摄把卡写满 */
    private static final long MAX_LOG_BYTES = 512 * 1024L;

    private AppLog() {
    }

    /** 初始化：尝试创建 SD 卡日志目录；失败不影响主流程（只打 logcat） */
    public static void init(String sdRootPath) {
        try {
            java.io.File dir = new java.io.File(sdRootPath, SD_DIR_NAME);
            if (!dir.exists()) {
                sdWritable = dir.mkdirs();
            }
            logFile = new java.io.File(dir, "LOG.TXT");
        } catch (Throwable t) {
            sdWritable = false;
            logFile = null;
        }
    }

    public static String getLogFilePath() {
        return logFile == null ? "(未启用)" : logFile.getAbsolutePath();
    }

    public static void info(String msg) {
        log("I", msg);
    }

    public static void warn(String msg) {
        log("W", msg);
    }

    public static void error(String msg) {
        log("E", msg);
    }

    /** 记录异常堆栈（只记录类名+消息，避免长栈写满卡） */
    public static void error(String msg, Throwable t) {
        if (t == null) {
            log("E", msg);
            return;
        }
        log("E", msg + " | " + t.getClass().getName() + ": " + t.getMessage());
    }

    private static void log(String level, String msg) {
        String line = "[" + level + "] " + System.currentTimeMillis() + " " + msg;
        // 1) 输出到 logcat，方便通过 PMCA-RE 开启 adb 后排查
        try {
            android.util.Log.println(level.equals("E") ? android.util.Log.ERROR
                    : level.equals("W") ? android.util.Log.WARN : android.util.Log.INFO, TAG, msg);
        } catch (Throwable ignored) {
        }
        // 2) 追加写入 SD 卡
        if (!sdWritable || logFile == null) {
            return;
        }
        if (logFile.length() > MAX_LOG_BYTES) {
            // 超限：清空重来，保证不会把卡写满导致拍摄中断
            logFile.delete();
        }
        java.io.FileOutputStream fos = null;
        try {
            fos = new java.io.FileOutputStream(logFile, true);
            fos.write((line + "\r\n").getBytes("UTF-8"));
        } catch (Throwable ignored) {
            // 写日志失败时静默处理，绝不影响拍摄主流程
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
