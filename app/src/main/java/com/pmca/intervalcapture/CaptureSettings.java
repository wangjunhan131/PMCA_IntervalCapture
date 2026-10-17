package com.pmca.intervalcapture;

/**
 * CaptureSettings —— 间隔拍摄参数模型（纯逻辑类，不依赖 Android / 索尼 SDK）
 * <p>
 * 设计要点：
 * 1. 相机硬件限制全部内聚在本类中，任何 UI 层只调用 {@link #adjust} 与格式化方法，
 * 保证“最低 1 秒、步进 0.1 秒、上限 3600 秒、张数 1~9999”等规则只有一处实现；
 * 2. 间隔时间用“十分之一秒”的整数（intervalDeciseconds）保存，避免浮点在相机 JVM
 * 上反复加减产生的 0.1 误差；
 * 3. 本类不接触任何索尼 API，便于在普通电脑上做单元测试。
 * <p>
 * 对应 PMCA SDK 的说明：索尼 PMCA 并没有提供“间隔拍摄”的现成接口，间隔拍摄必须由应用
 * 自己在每一张之间计时，然后反复调用 CameraEx → Camera.takePicture 静态拍摄接口。
 */
public final class CaptureSettings {

    /* ============================ 硬性范围常量 ============================ */

    /** 拍摄间隔下限：0.1 秒为最小步进（A6500 理论最短稳定间隔为 1 秒，低于 1 秒会给出警告） */
    public static final int INTERVAL_MIN_DS = 10;

    /**
     * 拍摄间隔强制下限：1.0 秒 = 10 个 0.1 秒。
     * A6500 机身在连续静态拍摄时，单张的“快门+读出+写卡”至少占用约 0.6~0.9 秒，
     * 因此低于 1.0 秒的间隔在实际使用中不可能达成，此处作为“最低允许设置值”拦截。
     */
    public static final int INTERVAL_HARD_MIN_DS = 10;

    /** 拍摄间隔上限：3600.0 秒（1 小时） */
    public static final int INTERVAL_MAX_DS = 36000;

    /** 固定张数模式下的张数上限 */
    public static final int SHOT_COUNT_MAX = 9999;

    /** 固定张数模式下的张数下限（至少拍 1 张） */
    public static final int SHOT_COUNT_MIN = 1;

    /**
     * 无限模式在内部使用的“虚拟总数”。
     * 用一个大于任何实际可拍数量的数值代替，避免到处写 if (infinite) 分支；
     * 界面显示时把它翻译成“∞”。
     */
    public static final int SHOT_COUNT_INFINITE = 1000000;

    /** 图像格式：仅 JPG。相机固件按机内“影像质量/文件格式”设置执行，本应用不干预。 */
    public static final int FORMAT_JPG_ONLY = 0;
    /** 图像格式：仅 RAW（.ARW）。 */
    public static final int FORMAT_RAW_ONLY = 1;
    /** 图像格式：JPG + RAW 同时保存。 */
    public static final int FORMAT_JPG_PLUS_RAW = 2;

    /** 格式选项的中文名称，供界面直接使用 */
    public static final String[] FORMAT_NAMES = new String[]{"仅 JPG", "仅 RAW(ARW)", "JPG+RAW"};

    /* ============================ 参数字段 ============================ */

    /** 拍摄间隔，单位：0.1 秒 */
    private int intervalDeciseconds = 20;      // 默认 2.0 秒

    /** 是否无限连续拍摄；false 表示“固定张数”模式 */
    private boolean infiniteCount = false;

    /** 固定张数模式下的总张数；无限模式下该值无意义 */
    private int shotCount = 100;               // 默认 100 张

    /** 图像格式，取 FORMAT_xxx 之一 */
    private int imageFormat = FORMAT_JPG_ONLY; // 默认仅 JPG

    /** 是否锁定曝光/对焦/白平衡：开启后第一张锁定，后续复用，防止延时画面明暗闪烁 */
    private boolean lockAeAf = true;           // 默认开启（间隔拍摄的核心需求）

    /** 是否在拍摄过程中显示实时预览；关闭可降低机身发热、提升连续拍摄稳定性 */
    private boolean previewEnabled = true;     // 默认开启

    /* ============================ 读取接口 ============================ */

    public int getIntervalDeciseconds() {
        return intervalDeciseconds;
    }

    /** 返回间隔秒数（浮点），仅用于显示与告警判断，内部计时请使用整数版本 */
    public float getIntervalSeconds() {
        return intervalDeciseconds / 10f;
    }

    /** 间隔毫秒数，供计时器使用 */
    public long getIntervalMillis() {
        return intervalDeciseconds * 100L;
    }

    public boolean isInfiniteCount() {
        return infiniteCount;
    }

    /** 有效总张数：无限模式返回 SHOT_COUNT_INFINITE */
    public int getEffectiveShotCount() {
        return infiniteCount ? SHOT_COUNT_INFINITE : shotCount;
    }

    /** 用户设置的张数（无限模式下仍返回上一次设定的数字，便于切回固定模式） */
    public int getShotCount() {
        return shotCount;
    }

    public int getImageFormat() {
        return imageFormat;
    }

    public boolean isLockAeAf() {
        return lockAeAf;
    }

    public boolean isPreviewEnabled() {
        return previewEnabled;
    }

    /* ============================ 修改接口（带钳制） ============================ */

    /**
     * 上下键调整参数。
     *
     * @param fieldIndex 参数字段序号，见 {@link #FIELD_INTERVAL} 等常量
     * @param direction  正数表示“+”，负数表示“-”
     * @return 实际发生变化时返回 true
     */
    public boolean adjust(int fieldIndex, int direction) {
        switch (fieldIndex) {
            case FIELD_INTERVAL:
                return setIntervalDeciseconds(intervalDeciseconds + direction * intervalStep(direction));
            case FIELD_SHOT_COUNT:
                if (infiniteCount) {
                    // 无限模式下上下键在“无限 / 固定张数”之间切换
                    infiniteCount = false;
                    return true;
                }
                return setShotCount(shotCount + direction * countStep());
            case FIELD_FORMAT:
                return setImageFormat(imageFormat + (direction > 0 ? 1 : -1));
            case FIELD_LOCK:
                lockAeAf = !lockAeAf;
                return true;
            case FIELD_PREVIEW:
                previewEnabled = !previewEnabled;
                return true;
            case FIELD_COUNT_MODE:
                infiniteCount = !infiniteCount;
                return true;
            default:
                return false;
        }
    }

    /** 间隔调整步长：低于 10 秒时每次 0.1 秒，10 秒以上每次 1 秒，方便快速拉到小时级 */
    private int intervalStep(int direction) {
        if (intervalDeciseconds < 100) {
            return 1;           // 0.1 秒
        }
        if (intervalDeciseconds < 600) {
            return 10;          // 1 秒
        }
        return direction > 0 ? 100 : 100; // 10 秒以上每次 10 秒
    }

    /** 张数调整步长：<100 每按一次 ±1，<1000 ±10，其余 ±100 */
    private int countStep() {
        if (shotCount < 100) {
            return 1;
        }
        if (shotCount < 1000) {
            return 10;
        }
        return 100;
    }

    /**
     * 设置间隔（单位 0.1 秒），自动钳制到 [1.0 秒, 3600 秒]。
     * <p>
     * 注意：本类内部表示本身就使用“0.1 秒”为最小单位的整数（10 = 1.0 秒，11 = 1.1 秒），
     * 因此这里只做上下限钳制，不能再做任何“按整秒对齐”的取整，否则 1.1 秒会被错误地变成 1.0 秒。
     */
    public boolean setIntervalDeciseconds(int value) {
        int clamped = clamp(value, INTERVAL_HARD_MIN_DS, INTERVAL_MAX_DS);
        if (clamped == intervalDeciseconds) {
            return false;
        }
        intervalDeciseconds = clamped;
        return true;
    }

    /** 设置固定张数，自动钳制到 [1, 9999] */
    public boolean setShotCount(int value) {
        int clamped = clamp(value, SHOT_COUNT_MIN, SHOT_COUNT_MAX);
        if (clamped == shotCount) {
            return false;
        }
        shotCount = clamped;
        return true;
    }

    /** 设置图像格式（循环切换） */
    public boolean setImageFormat(int value) {
        int count = FORMAT_NAMES.length;
        int v = ((value % count) + count) % count;   // 循环
        if (v == imageFormat) {
            return false;
        }
        imageFormat = v;
        return true;
    }

    public void setLockAeAf(boolean value) {
        lockAeAf = value;
    }

    public void setPreviewEnabled(boolean value) {
        previewEnabled = value;
    }

    public void setInfiniteCount(boolean value) {
        infiniteCount = value;
    }

    /* ============================ 显示格式化 ============================ */

    /** 间隔显示：整数秒显示“2 秒”，带小数显示“2.5 秒” */
    public String formatInterval() {
        int sec = intervalDeciseconds / 10;
        int deci = intervalDeciseconds % 10;
        if (deci == 0) {
            return sec + " 秒";
        }
        return sec + "." + deci + " 秒";
    }

    /** 张数显示：无限模式显示“无限（手动停止）” */
    public String formatShotCount() {
        if (infiniteCount) {
            return "无限（手动停止）";
        }
        return shotCount + " 张";
    }

    public String formatImageFormat() {
        return FORMAT_NAMES[imageFormat];
    }

    public String formatLock() {
        return lockAeAf ? "开（首张后锁定 AE/AF/WB）" : "关（每张重新测光对焦）";
    }

    public String formatPreview() {
        return previewEnabled ? "开" : "关（省电降温）";
    }

    /* ============================ 硬件告警 ============================ */

    /**
     * 判断当前设置是否可能受相机硬件限制而无法严格维持间隔。
     * 触发条件（满足其一即提示）：
     *   - 间隔 < 1.0 秒（A6500 理论上做不到）；
     *   - 间隔 < 1.5 秒且图像格式包含 RAW（RAW 单张写卡时间更长）；
     *   - 间隔 < 1.5 秒且同时开启预览（预览刷新与写卡争抢总线）。
     *
     * @return 需要提示时返回中文提示文本，否则返回 null
     */
    public String getHardwareWarning() {
        float sec = getIntervalSeconds();
        if (sec < 1.0f) {
            return "间隔小于 1 秒，A6500 无法稳定维持";
        }
        if (sec < 1.5f && imageFormat != FORMAT_JPG_ONLY) {
            return "RAW 写卡较慢，1.5 秒以内间隔可能漂移";
        }
        if (sec < 1.5f && previewEnabled) {
            return "预览会占用总线，1.5 秒以内间隔可能漂移";
        }
        return null;
    }

    /* ============================ 参数字段序号 ============================ */

    public static final int FIELD_INTERVAL = 0;
    public static final int FIELD_SHOT_COUNT = 1;
    public static final int FIELD_FORMAT = 2;
    public static final int FIELD_LOCK = 3;
    public static final int FIELD_PREVIEW = 4;
    public static final int FIELD_COUNT_MODE = 5;
    /** 参数总项数，界面按此循环焦点 */
    public static final int FIELD_COUNT = 6;

    /* ============================ 工具方法 ============================ */

    /**
     * 数值钳制。Android 2.3 的 Math 没有 clamp，这里自己实现，避免依赖高版本 API。
     */
    public static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    /** 深拷贝，防止运行中修改参数影响正在进行的任务 */
    public CaptureSettings copy() {
        CaptureSettings s = new CaptureSettings();
        s.intervalDeciseconds = intervalDeciseconds;
        s.infiniteCount = infiniteCount;
        s.shotCount = shotCount;
        s.imageFormat = imageFormat;
        s.lockAeAf = lockAeAf;
        s.previewEnabled = previewEnabled;
        return s;
    }

    /** 序列化为一行文本，用于写入 SD 卡（断电后仍可查看本次参数） */
    public String toPersistLine() {
        return "interval=" + intervalDeciseconds
                + ";infinite=" + infiniteCount
                + ";count=" + shotCount
                + ";format=" + imageFormat
                + ";lock=" + lockAeAf
                + ";preview=" + previewEnabled;
    }

    /** 从 toPersistLine() 的结果恢复，容错解析（任何一项坏了就使用默认值） */
    public static CaptureSettings fromPersistLine(String line) {
        CaptureSettings s = new CaptureSettings();
        if (line == null) {
            return s;
        }
        String[] parts = line.split(";");
        for (String p : parts) {
            int eq = p.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = p.substring(0, eq).trim();
            String v = p.substring(eq + 1).trim();
            try {
                if (k.equals("interval")) {
                    s.setIntervalDeciseconds(Integer.parseInt(v));
                } else if (k.equals("infinite")) {
                    s.infiniteCount = "true".equals(v);
                } else if (k.equals("count")) {
                    s.setShotCount(Integer.parseInt(v));
                } else if (k.equals("format")) {
                    s.setImageFormat(Integer.parseInt(v));
                } else if (k.equals("lock")) {
                    s.lockAeAf = "true".equals(v);
                } else if (k.equals("preview")) {
                    s.previewEnabled = "true".equals(v);
                }
            } catch (NumberFormatException ignored) {
                // 单项解析失败时忽略，保留默认值即可
            }
        }
        return s;
    }
}
