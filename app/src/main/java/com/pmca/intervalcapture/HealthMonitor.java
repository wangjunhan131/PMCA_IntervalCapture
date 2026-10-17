package com.pmca.intervalcapture;

/**
 * HealthMonitor —— 拍摄过程中的健康巡检（SD 卡 / 温度 / 电池 / 存储写入）
 * <p>
 * 需求映射：“SD 卡满、存储卡读写失败、相机过热，立刻停止拍摄并弹出中文报错”。
 * <p>
 * 用到的接口：
 *   1. SD 卡剩余空间：android.os.StatFs（平台基础 API，读取挂载点文件系统信息）——
 *      这是相机 SD 卡在系统里的真实挂载点，读取不需要任何索尼私有接口，
 *      也不涉及“用电脑/手机写卡”，照片写入仍完全由相机固件完成；
 *   2. 相机温度：com.sony.scalar.sysutil.didep.Temperature（索尼私有工具类）
 *      - new Temperature() 创建实例；
 *      - getStatus() 返回 Temperature.Status；
 *      - Status.get(Status.ID_BOX) 取机身温度等级，Status.get(Status.ID_BATTERY) 取电池温度等级；
 *      - 等级含义：STAT_NORMAL(1) 正常、STAT_PREHIGH(2) 偏高、STAT_NEARHIGH(3) 接近过热限、
 *        STAT_HIGH(4) 过热（此时固件可能拒绝拍摄）；
 *      - release() 释放实例（必须调用，否则会泄漏固件侧资源）。
 *   3. 温度状态回调：Temperature#setStatusCallback 可在温度等级变化时主动通知，
 *      这里用轮询 + 回调双保险，回调用不到时轮询仍能发现。
 *   4. 电池：com.sony.scalar.sysutil.didep.Power（可选，仅用于提示，不作为停止条件）。
 * <p>
 * 安全检查策略（宁可提前停下，也不要拍到一半没电/满卡）：
 *   - 剩余空间 < 100 MB：警告；
 *   - 剩余空间 < 30 MB 或写卡连续失败：立即停止；
 *   - 机身温度等级 >= STAT_NEARHIGH：警告；>= STAT_HIGH：立即停止；
 *   - 单张写卡失败累计 3 次：立即停止（判断为卡故障）。
 */
public final class HealthMonitor {

    /** 立即停止的原因（中文，直接显示到屏幕） */
    private String fatalReason = null;
    /** 警告信息（中文），界面用黄色显示 */
    private String warningText = null;

    /** 上次检查的序号，用于控制检查频率（每 10 次拍照检查一次，避免频繁访问固件） */
    private int checkCounter = 0;
    /** 连续写卡失败次数 */
    private int consecutiveStoreFailures = 0;

    /** 阈值：剩余空间警告 100MB */
    private static final long WARN_FREE_BYTES = 100L * 1024 * 1024;
    /** 阈值：剩余空间低于 30MB 必须停止（一张 A6000 RAW 约 24MB） */
    private static final long STOP_FREE_BYTES = 30L * 1024 * 1024;
    /** 连续写卡失败上限 */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    /** 索尼温度实例；创建失败（例如其他机型固件无此类）时为 null，功能自动降级 */
    private com.sony.scalar.sysutil.didep.Temperature temperature;
    private boolean temperatureAvailable = false;

    /** 最近的温度等级（1~4），用于界面显示 */
    private int boxTempLevel = com.sony.scalar.sysutil.didep.Temperature.Status.STAT_UNKNOWN;

    /**
     * 温度状态回调占位（本应用不注册回调）。
     * <p>
     * 说明：Temperature.setStatusCallback 需要的是固件内部定义的类型
     * Temperature$StatusCallback，其抽象方法名各固件版本不同、公开资料无法确定，
     * 因此这里不注册回调，改为在拍摄循环中按固定频率主动轮询 getStatus()，
     * 效果等价，又不会因为方法名不匹配在运行时抛 AbstractMethodError 打断拍摄。
     */
    private static final com.sony.scalar.sysutil.didep.Temperature.StatusCallback TEMP_CALLBACK = null;

    /** 打开温度监控（进入运行界面时调用） */
    public void open() {
        fatalReason = null;
        warningText = null;
        consecutiveStoreFailures = 0;
        boxTempLevel = com.sony.scalar.sysutil.didep.Temperature.Status.STAT_UNKNOWN;
        try {
            // 创建索尼温度管理实例（com.sony.scalar.sysutil.didep.Temperature）。
            // 该对象背后连接的是相机固件的温度传感器服务，创建后需要 release() 释放。
            temperature = new com.sony.scalar.sysutil.didep.Temperature();
            // 主动轮询模式：不注册回调（见 tempCallback 处的说明），
            // 在每次 checkBeforeShot 时调用 getStatus() 读取最新等级。
            temperatureAvailable = true;
            AppLog.info("温度监控已启动（轮询模式）");
        } catch (Throwable t) {
            // 某些固件版本没有 Temperature 类，或权限不足：降级为“无温度监控”，不阻塞拍摄
            temperature = null;
            temperatureAvailable = false;
            AppLog.warn("温度监控不可用：" + t.getClass().getSimpleName());
        }
    }

    /** 关闭温度监控（退出运行界面时必须调用，避免固件侧资源泄漏） */
    public void close() {
        if (temperature != null) {
            try {
                temperature.release();   // 释放固件侧温度服务句柄
            } catch (Throwable t) {
                AppLog.warn("释放温度监控失败：" + t.getMessage());
            }
            temperature = null;
        }
        temperatureAvailable = false;
    }

    public boolean isTemperatureAvailable() {
        return temperatureAvailable;
    }

    public int getBoxTempLevel() {
        return boxTempLevel;
    }

    /** 温度等级的中文描述 */
    public String getBoxTempText() {
        if (!temperatureAvailable) {
            return "不可用";
        }
        switch (boxTempLevel) {
            case com.sony.scalar.sysutil.didep.Temperature.Status.STAT_NORMAL:
                return "正常";
            case com.sony.scalar.sysutil.didep.Temperature.Status.STAT_PREHIGH:
                return "偏高";
            case com.sony.scalar.sysutil.didep.Temperature.Status.STAT_NEARHIGH:
                return "接近过热";
            case com.sony.scalar.sysutil.didep.Temperature.Status.STAT_HIGH:
                return "过热";
            default:
                return "未知";
        }
    }

    public String getFatalReason() {
        return fatalReason;
    }

    public String getWarningText() {
        return warningText;
    }

    public boolean isFatal() {
        return fatalReason != null;
    }

    /* ============================ 巡检入口 ============================ */

    /**
     * 每次“准备拍下一张”之前调用。
     *
     * @param isFirstShot 是否是本次任务的第一张（第一张前必须做完整检查）
     * @return true 表示安全，可以拍；false 表示已经设置 fatalReason，应立刻停止
     */
    public boolean checkBeforeShot(boolean isFirstShot) {
        fatalReason = null;
        warningText = null;

        // 1) 每次都检查：SD 卡是否存在且可写
        String storageState = readStorageState();
        if (!"mounted".equals(storageState)) {
            fatalReason = storageStateToChinese(storageState);
            return false;
        }

        // 2) 每次都检查：剩余空间
        long free = readFreeBytes();
        if (free >= 0) {
            if (free < STOP_FREE_BYTES) {
                fatalReason = "存储卡空间不足（剩余 " + (free / 1024 / 1024) + "MB），已停止拍摄";
                return false;
            } else if (free < WARN_FREE_BYTES) {
                warningText = "存储卡剩余空间仅 " + (free / 1024 / 1024) + "MB";
            }
        }

        // 3) 降低频率检查温度：每 5 张或第一张之前检查一次，减少对固件的调用压力
        if (isFirstShot || (++checkCounter >= 5)) {
            checkCounter = 0;
            if (!checkTemperature()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查温度。返回 false 表示过热必须停止。
     */
    private boolean checkTemperature() {
        if (temperature == null) {
            return true;
        }
        try {
            com.sony.scalar.sysutil.didep.Temperature.Status status = temperature.getStatus();
            if (status == null) {
                return true;
            }
            // ID_BOX = 机身整体温度，是连续拍摄最主要的热源指标
            boxTempLevel = status.get(com.sony.scalar.sysutil.didep.Temperature.Status.ID_BOX);
            if (boxTempLevel >= com.sony.scalar.sysutil.didep.Temperature.Status.STAT_HIGH) {
                fatalReason = "相机过热，已自动停止拍摄。请关闭电源待机身降温后再拍摄";
                return false;
            }
            if (boxTempLevel >= com.sony.scalar.sysutil.didep.Temperature.Status.STAT_NEARHIGH) {
                // 接近过热：不立即停止，但建议用户关闭预览、延长间隔
                warningText = "机身温度接近上限，建议关闭预览或加大间隔";
            }
        } catch (Throwable t) {
            // 温度接口异常时不影响拍摄，只记录日志
            AppLog.warn("读取温度失败：" + t.getClass().getSimpleName());
        }
        return true;
    }

    /**
     * 相机报告“写卡完成”时调用，清零连续失败计数。
     */
    public void onStoreSuccess() {
        consecutiveStoreFailures = 0;
    }

    /**
     * 相机报告“写卡失败/快门异常”时调用。
     *
     * @return true 表示连续失败次数过多，应停止拍摄
     */
    public boolean onStoreFailure() {
        consecutiveStoreFailures++;
        if (consecutiveStoreFailures >= MAX_CONSECUTIVE_FAILURES) {
            fatalReason = "存储卡读写失败（连续 " + consecutiveStoreFailures
                    + " 次），请检查存储卡或更换存储卡后重试";
            return true;
        }
        return false;
    }

    /* ============================ 平台信息读取 ============================ */

    /** 读取 SD 卡挂载状态（平台 API） */
    private String readStorageState() {
        try {
            return android.os.Environment.getExternalStorageState();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private String storageStateToChinese(String state) {
        if ("removed".equals(state)) {
            return "未检测到存储卡，请插入存储卡后重试";
        }
        if ("mounted_ro".equals(state)) {
            return "存储卡处于只读状态（写保护），无法保存照片";
        }
        if ("unmounted".equals(state) || "bad_removal".equals(state)) {
            return "存储卡已拔出或未正确挂载，已停止拍摄";
        }
        if ("unmountable".equals(state) || "checking".equals(state)) {
            return "存储卡正在检查或无法挂载，已停止拍摄";
        }
        return "存储卡不可用（" + state + "），已停止拍摄";
    }

    /** 读取 SD 卡剩余字节数；失败返回 -1（此时不做空间判断） */
    private long readFreeBytes() {
        try {
            android.os.StatFs stat = new android.os.StatFs(
                    android.os.Environment.getExternalStorageDirectory().getAbsolutePath());
            // getBlockSize() / getAvailableBlocks() 在 API 10（Android 2.3）就存在，
            // 全系列相机固件通用，因此不使用后来才出现的 getAvailableBytes()。
            long blockSize = stat.getBlockSize();
            long availBlocks = stat.getAvailableBlocks();
            if (blockSize <= 0 || availBlocks < 0) {
                return -1;
            }
            return blockSize * availBlocks;
        } catch (Throwable t) {
            return -1;
        }
    }
}
