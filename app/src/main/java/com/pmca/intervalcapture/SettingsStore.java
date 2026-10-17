package com.pmca.intervalcapture;

/**
 * SettingsStore —— 参数与统计数据的持久化
 * <p>
 * 用途：
 *   1. 记住用户上次设置的间隔、张数、格式等，下次进入应用不用重设；
 *   2. 断电/异常退出后能恢复“上次拍到哪里”（仅用于显示，不影响 SD 卡上的原图）。
 * <p>
 * 实现选择：使用 Android 的 SharedPreferences（平台基础能力，不是相机接口）。
 * 之所以同时把参数写一份到 SD 卡（PMCACAP/SETTINGS.TXT）：
 *   相机固件有时会因为刷入/升级/恢复出厂而清空应用私有数据，
 *   而 SD 卡上的文本文件可以手工查看，方便排查“参数怎么变回默认值了”。
 */
public final class SettingsStore {

    private static final String PREF_NAME = "interval_capture_pref";
    private static final String KEY_SETTINGS = "settings_line";
    private static final String KEY_TOTAL_TAKEN = "total_taken";

    private final android.content.Context context;

    public SettingsStore(android.content.Context context) {
        this.context = context;
    }

    /** 读取上次保存的参数；没有记录时返回默认参数 */
    public CaptureSettings load() {
        try {
            android.content.SharedPreferences sp =
                    context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE);
            String line = sp.getString(KEY_SETTINGS, null);
            if (line == null) {
                // 私有数据被清空时，尝试从 SD 卡恢复
                line = readSdSettings();
            }
            return CaptureSettings.fromPersistLine(line);
        } catch (Throwable t) {
            AppLog.error("读取参数失败，使用默认值", t);
            return new CaptureSettings();
        }
    }

    /** 保存参数 */
    public void save(CaptureSettings settings) {
        try {
            android.content.SharedPreferences sp =
                    context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE);
            String line = settings.toPersistLine();
            sp.edit().putString(KEY_SETTINGS, line).commit();
            writeSdSettings(line);
        } catch (Throwable t) {
            AppLog.error("保存参数失败", t);
        }
    }

    /** 累计拍摄张数（跨多次任务累加，仅供用户参考） */
    public int getTotalTaken() {
        try {
            return context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
                    .getInt(KEY_TOTAL_TAKEN, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 累加拍摄张数 */
    public void addTotalTaken(int count) {
        try {
            android.content.SharedPreferences sp =
                    context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE);
            int total = sp.getInt(KEY_TOTAL_TAKEN, 0) + count;
            sp.edit().putInt(KEY_TOTAL_TAKEN, total).commit();
        } catch (Throwable t) {
            AppLog.error("累计张数写入失败", t);
        }
    }

    /* ------------------------- SD 卡冗余备份 ------------------------- */

    private java.io.File sdSettingsFile() {
        try {
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStorageDirectory().getAbsolutePath(),
                    AppLog.SD_DIR_NAME);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            return new java.io.File(dir, "SETTINGS.TXT");
        } catch (Throwable t) {
            return null;
        }
    }

    private String readSdSettings() {
        java.io.File f = sdSettingsFile();
        if (f == null || !f.exists()) {
            return null;
        }
        java.io.BufferedReader br = null;
        try {
            br = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(f), "UTF-8"));
            return br.readLine();
        } catch (Throwable t) {
            return null;
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void writeSdSettings(String line) {
        java.io.File f = sdSettingsFile();
        if (f == null) {
            return;
        }
        java.io.FileOutputStream fos = null;
        try {
            fos = new java.io.FileOutputStream(f, false);
            fos.write((line + "\r\n").getBytes("UTF-8"));
        } catch (Throwable t) {
            AppLog.warn("SD 卡参数备份写入失败：" + t.getMessage());
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
