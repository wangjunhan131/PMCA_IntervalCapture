package com.pmca.intervalcapture;

/**
 * CaptureSettings —— 间隔拍摄参数模型（纯逻辑类，不依赖 Android / 索尼 SDK）
 * <p>
 * 设计要点：
 * 1. 相机硬件限制全部内聚在本类中，任何 UI 层只调用 {@link #adjust} 与格式化方法，
 * 保证“最低 0.1 秒、步进 0.1 秒、上限 3600 秒、张数 1~9999”等规则只有一处实现；
 * 2. 间隔时间用“十分之一秒”的整数（intervalDeciseconds）保存，避免浮点在相机 JVM
 * 上反复加减产生的 0.1 误差；
 * 3. 本类不接触任何索尼 API，便于在普通电脑上做单元测试。
 * <p>
 * 对应 PMCA SDK 的说明：索尼 PMCA 并没有提供“间隔拍摄”的现成接口，间隔拍摄必须由应用
 * 自己在每一张之间计时，然后反复调用 CameraEx → Camera.takePicture 静态拍摄接口。
 */
public final class CaptureSettings {

    /* ============================ 硬性范围常量 ============================ */

    /**
     * 拍摄间隔下限：0.1 秒（v1.1.0 起按用户需求放开到 0.1 秒）。
     * 注意：A6500 机身单张“快门+读出+写卡”至少占用约 0.6~0.9 秒，
     * 低于 1.0 秒的间隔在硬件上无法严格维持——{@link #getHardwareWarning()}
     * 会在间隔小于 1 秒时给出黄色警告，但不再强制拦截（由用户自行决定）。
     */
    public static final int INTERVAL_MIN_DS = 1;

    /** 拍摄间隔强制下限：0.1 秒 = 1 个最小单位（v1.1.0 起允许 0.1 秒设置） */
    public static final int INTERVAL_HARD_MIN_DS = 1;

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

    /* ============================ 曝光控制常量（v1.1.0） ============================ */

    /** 曝光模式：跟随机内设置（应用不干预光圈/快门/ISO） */
    public static final int EXP_MODE_CAMERA = 0;
    /** 曝光模式：应用接管（开机时按下面 3 个字段下发到相机） */
    public static final int EXP_MODE_APP = 1;

    /** ISO 自动（对应机内 AUTO 档） */
    public static final int ISO_AUTO = 0;

    /** 光圈/快门单次调整的最大档数（防呆：一次最多 ±10 档） */
    public static final int EXPOSURE_STEPS_MAX = 10;

    /**
     * 常见 ISO 档位表（用于 UI 循环切换）。
     * 说明：真实可用档位以相机 getSupportedISOSensitivities() 为准；A6500 扩展 ISO
     * （如 50/64/80）不在本表内，但应用不拦截——直接输入的值由固件自行取舍。
     * UI 循环顺序：AUTO → 100 → 200 → ... → 25600 → 回到 AUTO。
     */
    public static final int[] ISO_OPTIONS = new int[]{
            ISO_AUTO, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600
    };

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

    /* ============================ 曝光控制字段（v1.1.0 新增） ============================
     * 语义：这是“应用侧的曝光意图”，真正下发到相机硬件是在 CaptureService 里通过
     * 索尼 PMCA SDK 的 ParametersModifier / CameraEx 步进接口完成的（见 ExposureController.apply）。
     * 与机内模式拨盘的关系：
     *   - 机内拨盘在 M 档：光圈/快门可调，ISO 独立可调；
     *   - 机内拨盘在 A/S 档：只能调其中两个；
     *   - 机内拨盘在 P/自动档：调光圈快门无效（固件拒绝），应用照常发命令、不报错。
     * 因此这里不做“模式判断”，把硬件不支持的情况交给固件自然忽略（注释里说明）。
     */

    /**
     * 曝光控制的模式：跟随机内（EXP_MODE_CAMERA）或由应用接管（EXP_MODE_APP）。
     * 应用接管时才会真正调用 CameraEx 的步进/设置接口；跟随机内则完全不干预，
     * 与 v1.0.x 行为一致（所有参数由机内菜单/拨盘决定）。
     */
    private int exposureMode = EXP_MODE_CAMERA;

    /**
     * 光圈调整请求（相对档数）：0 表示不动；正数 = 收小光圈档数；负数 = 开大光圈档数。
     * 为什么是“相对档数”而不是绝对值：索尼固件只提供 incrementAperture/decrementAperture
     * 步进接口（档位表随镜头不同而不同，应用拿不到完整表），因此 UI 上“调大光圈值”
     * = 多次调用 decrementAperture（F 值变小=光圈开大）。
     */
    private int apertureSteps = 0;

    /** 快门速度调整请求（相对档数）：0 不动；正数 = 更慢（长曝）；负数 = 更快 */
    private int shutterSteps = 0;

    /**
     * ISO 设置请求：ISO_AUTO（0）= 自动；其它值 = 具体感光度（如 100/400/1600）。
     * 实际是否生效取决于 ParametersModifier.setISOSensitivity + 机内拨盘档位；
     * 若机内处于自动 ISO，应用手动值会在 ExposureController.apply 中被 setISOSensitivity 覆盖。
     */
    private int isoValue = ISO_AUTO;

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

    /* ============================ 曝光控制读取接口（v1.1.0） ============================ */

    public int getExposureMode() {
        return exposureMode;
    }

    public int getApertureSteps() {
        return apertureSteps;
    }

    public int getShutterSteps() {
        return shutterSteps;
    }

    public int getIsoValue() {
        return isoValue;
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
            case FIELD_EXPOSURE_MODE:
                // 曝光模式切换：机内 ↔ 应用接管
                exposureMode = (exposureMode == EXP_MODE_APP) ? EXP_MODE_CAMERA : EXP_MODE_APP;
                return true;
            case FIELD_APERTURE:
                // 跟随机内模式下不允许调整（UI 显示"（跟随机内）"）
                if (exposureMode != EXP_MODE_APP) {
                    return false;
                }
                // 光圈：正方向 = 收小（F 值变大），负方向 = 开大（F 值变小）
                apertureSteps = clamp(apertureSteps + direction, -EXPOSURE_STEPS_MAX, EXPOSURE_STEPS_MAX);
                return true;
            case FIELD_SHUTTER:
                if (exposureMode != EXP_MODE_APP) {
                    return false;
                }
                // 快门：正方向 = 更慢（长曝），负方向 = 更快
                shutterSteps = clamp(shutterSteps + direction, -EXPOSURE_STEPS_MAX, EXPOSURE_STEPS_MAX);
                return true;
            case FIELD_ISO:
                if (exposureMode != EXP_MODE_APP) {
                    return false;
                }
                isoValue = nextIso(isoValue, direction);
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

    /* ============================ 曝光控制修改接口（v1.1.0） ============================ */

    public void setExposureMode(int value) {
        exposureMode = (value == EXP_MODE_APP) ? EXP_MODE_APP : EXP_MODE_CAMERA;
    }

    public void setApertureSteps(int value) {
        apertureSteps = clamp(value, -EXPOSURE_STEPS_MAX, EXPOSURE_STEPS_MAX);
    }

    public void setShutterSteps(int value) {
        shutterSteps = clamp(value, -EXPOSURE_STEPS_MAX, EXPOSURE_STEPS_MAX);
    }

    public void setIsoValue(int value) {
        isoValue = value;
    }

    /**
     * 循环切换到下一个 ISO 档位。
     * 方向为正时向高感光度走；到达 25600 后回到 AUTO；方向为负反向。
     * 如果当前值不在表内（例如固件扩展档 50），先找表中最接近的位置再走。
     */
    private static int nextIso(int current, int direction) {
        int n = ISO_OPTIONS.length;
        // 找当前值在表中的位置；找不到时，取第一个 ≥ current 的位置（无则 0）
        int idx = 0;
        boolean found = false;
        for (int i = 0; i < n; i++) {
            if (ISO_OPTIONS[i] == current) {
                idx = i;
                found = true;
                break;
            }
        }
        if (!found) {
            idx = 0;
            for (int i = 1; i < n; i++) {
                if (ISO_OPTIONS[i] < current) {
                    idx = i;    // 记录最后一个小于 current 的档位
                }
            }
        }
        idx = ((idx + (direction > 0 ? 1 : -1)) % n + n) % n;
        return ISO_OPTIONS[idx];
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

    /* ============================ 曝光控制格式化（v1.1.0） ============================ */

    /** 曝光模式显示 */
    public String formatExposureMode() {
        return exposureMode == EXP_MODE_APP ? "应用接管" : "跟随机内";
    }

    /** 光圈调整显示：“0 = 不变，+3 = 收小 3 档，-2 = 开大 2 档” */
    public String formatAperture() {
        if (exposureMode != EXP_MODE_APP) {
            return "（跟随机内）";
        }
        if (apertureSteps == 0) {
            return "不变";
        }
        return apertureSteps > 0
                ? "收小 " + apertureSteps + " 档（F 值变大）"
                : "开大 " + (-apertureSteps) + " 档（F 值变小）";
    }

    /** 快门调整显示 */
    public String formatShutter() {
        if (exposureMode != EXP_MODE_APP) {
            return "（跟随机内）";
        }
        if (shutterSteps == 0) {
            return "不变";
        }
        return shutterSteps > 0
                ? "更慢 " + shutterSteps + " 档（长曝）"
                : "更快 " + (-shutterSteps) + " 档";
    }

    /** ISO 显示 */
    public String formatIso() {
        if (exposureMode != EXP_MODE_APP) {
            return "（跟随机内）";
        }
        return isoValue == ISO_AUTO ? "自动（AUTO）" : ("ISO " + isoValue);
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
            return "间隔小于 1 秒，超出现有硬件稳定拍摄能力，可能漂移";
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
    /* ---- v1.1.0 新增曝光控制字段（追加在尾部，保证已有持久化行兼容） ---- */
    public static final int FIELD_EXPOSURE_MODE = 6;
    public static final int FIELD_APERTURE = 7;
    public static final int FIELD_SHUTTER = 8;
    public static final int FIELD_ISO = 9;
    /** 参数总项数，界面按此循环焦点 */
    public static final int FIELD_COUNT = 10;

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
        s.exposureMode = exposureMode;
        s.apertureSteps = apertureSteps;
        s.shutterSteps = shutterSteps;
        s.isoValue = isoValue;
        return s;
    }

    /** 序列化为一行文本，用于写入 SD 卡（断电后仍可查看本次参数） */
    public String toPersistLine() {
        return "interval=" + intervalDeciseconds
                + ";infinite=" + infiniteCount
                + ";count=" + shotCount
                + ";format=" + imageFormat
                + ";lock=" + lockAeAf
                + ";preview=" + previewEnabled
                + ";expmode=" + exposureMode
                + ";aperture=" + apertureSteps
                + ";shutter=" + shutterSteps
                + ";iso=" + isoValue;
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
                } else if (k.equals("expmode")) {
                    s.setExposureMode(Integer.parseInt(v));
                } else if (k.equals("aperture")) {
                    s.setApertureSteps(Integer.parseInt(v));
                } else if (k.equals("shutter")) {
                    s.setShutterSteps(Integer.parseInt(v));
                } else if (k.equals("iso")) {
                    s.setIsoValue(Integer.parseInt(v));
                }
            } catch (NumberFormatException ignored) {
                // 单项解析失败时忽略，保留默认值即可
            }
        }
        return s;
    }
}
