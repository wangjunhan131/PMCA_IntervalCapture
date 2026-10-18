package com.pmca.intervalcapture;

/**
 * IntervalScheduler —— 间隔拍摄的“节拍状态机”（纯逻辑类，不依赖 Android / 索尼 SDK）
 * <p>
 * 职责：
 *   1. 根据参数与“上一张完成时刻”计算下一次拍摄应该发生在什么时候；
 *   2. 统计已拍张数、失败张数，判断是否达到设定张数（固定张数模式自动停止）；
 *   3. 支持暂停/继续：暂停期间不累计节拍，继续后对齐到“继续时刻 + 间隔”，
 *      且暂停不会重置已拍张数；
 *   4. 处理“拍摄超时”：当一张的曝光时间接近甚至超过设定间隔时，不再等待下一拍而是
 *      立刻连拍（并记录“周期溢出”次数），在界面上提示用户间隔无法保证。
 * <p>
 * 与 PMCA SDK 的关系：索尼没有提供间隔拍摄接口，本类负责“什么时候调用一次
 * CameraEx 的静态拍摄”，真正的调用发生在本类之后由 CaptureService 完成。
 * 相机端计时全部使用 {@link System#nanoTime()}，避免使用 System.currentTimeMillis()
 * 时用户改时间导致节拍跳跃。
 */
public final class IntervalScheduler {

    /** 运行状态 */
    public static final int STATE_IDLE = 0;      // 待机
    public static final int STATE_RUNNING = 1;   // 拍摄中
    public static final int STATE_PAUSED = 2;    // 已暂停
    public static final int STATE_FINISHED = 3;  // 已结束
    public static final int STATE_ERROR = 4;     // 异常停止

    /**
     * 单张拍摄的保护超时（毫秒）。
     * 超过该时间仍未收到相机的“写卡完成”回调，判定为相机异常，主动取消本次拍摄。
     * 之所以取 30 秒：A6500 挂 30 秒长曝光 + RAW 写卡也能在这个时间内完成；
     * 万一机身卡死，应用能及时报错而不是无限等待。
     */
    public static final long CAPTURE_TIMEOUT_MS = 30000L;

    private final CaptureSettings settings;

    private int state = STATE_IDLE;

    /** 已成功写入 SD 卡的张数（以相机 StoreImageComplete 回调为准） */
    private int takenCount = 0;
    /** 拍摄失败张数（快门取消、写卡失败等） */
    private int failedCount = 0;
    /** 因为有张未及时完成，导致无法按设定间隔拍摄的“溢出”次数 */
    private int overrunCount = 0;

    /** 下一张的计划拍摄时刻（nanoTime） */
    private long nextShotAtNanos = 0L;
    /** 当前这一张的开始时刻（nanoTime），用于计算拍摄耗时 */
    private long shotStartNanos = 0L;
    /** 当前这一张是否正在相机中处理（等待回调） */
    private boolean shotInFlight = false;

    /** 最近一张的拍摄+写卡耗时（毫秒），用于界面提示 */
    private long lastShotDurationMs = 0L;

    /** 完成回调（用户界面注册，用于刷新） */
    public interface Listener {
        /** 状态发生变化（开始/暂停/继续/结束/出错） */
        void onStateChanged(int newState);

        /** 成功拍摄并保存一张 */
        void onShotSaved(int takenCount, String fileName);

        /** 拍摄失败 */
        void onShotFailed(String reason);

        /** 达到设定张数，正常结束 */
        void onFinished(int takenCount);
    }

    private Listener listener;

    public IntervalScheduler(CaptureSettings settings) {
        this.settings = settings;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public CaptureSettings getSettings() {
        return settings;
    }

    /* ============================ 基本状态查询 ============================ */

    public int getState() {
        return state;
    }

    public int getTakenCount() {
        return takenCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public int getOverrunCount() {
        return overrunCount;
    }

    public long getLastShotDurationMs() {
        return lastShotDurationMs;
    }

    public boolean isShotInFlight() {
        return shotInFlight;
    }

    /** 剩余张数（无限模式返回 -1） */
    public int getRemainingCount() {
        if (settings.isInfiniteCount()) {
            return -1;
        }
        int remain = settings.getEffectiveShotCount() - takenCount;
        return remain < 0 ? 0 : remain;
    }

    /** 进度百分比（0~100），无限模式按“已完成张数对 100 取模”显示不了进度，返回 -1 */
    public int getProgressPercent() {
        if (settings.isInfiniteCount()) {
            return -1;
        }
        int total = settings.getEffectiveShotCount();
        if (total <= 0) {
            return 0;
        }
        return (int) (takenCount * 100L / total);
    }

    /* ============================ 生命周期操作 ============================ */

    /**
     * 开始拍摄（待机 → 拍摄中）。第一张立即拍摄，之后按间隔计时。
     *
     * @return 是否真的切换了状态
     */
    public boolean start() {
        if (state != STATE_IDLE && state != STATE_FINISHED && state != STATE_ERROR) {
            return false;
        }
        takenCount = 0;
        failedCount = 0;
        overrunCount = 0;
        lastShotDurationMs = 0L;
        shotInFlight = false;
        nextShotAtNanos = System.nanoTime();   // 立即拍第一张
        setState(STATE_RUNNING);
        return true;
    }

    /**
     * 暂停（拍摄中 → 已暂停）。
     * 暂停不会重置计数；如果暂停时有一张还在相机里处理，等它回调后再真正进入暂停态，
     * 这里只负责切换状态，剩余的收尾交给 onShotDone 处理。
     */
    public boolean pause() {
        if (state != STATE_RUNNING) {
            return false;
        }
        setState(STATE_PAUSED);
        return true;
    }

    /**
     * 继续（已暂停 → 拍摄中）。继续后从当前时刻重新开始计时，不补拍暂停期间缺的张数。
     */
    public boolean resume() {
        if (state != STATE_PAUSED) {
            return false;
        }
        // 注意单位：nanoTime() 是纳秒，getIntervalMillis() 是毫秒，必须乘 1e6 换算，
        // 否则“继续”之后会把下一张的触发时刻设成一个极小的纳秒值，导致立即连拍。
        nextShotAtNanos = System.nanoTime() + settings.getIntervalMillis() * 1000000L;
        setState(STATE_RUNNING);
        return true;
    }

    /**
     * 停止拍摄并结束任务（用户按“下”键或发生错误时调用）。
     *
     * @param reason 结束原因，用于界面显示
     */
    public void stop(String reason) {
        if (state == STATE_FINISHED || state == STATE_ERROR || state == STATE_IDLE) {
            return;
        }
        setState(STATE_FINISHED);
        if (listener != null) {
            listener.onFinished(takenCount);
        }
    }

    /** 发生不可恢复错误（SD 卡满 / 写卡失败 / 过热）时由监控模块调用 */
    public void fail(String reason) {
        if (state == STATE_ERROR) {
            return;
        }
        setState(STATE_ERROR);
        if (listener != null) {
            listener.onShotFailed(reason);
        }
    }

    /* ============================ 节拍推进 ============================ */

    /**
     * 判断当前是否应该触发一次拍摄。
     * 由界面主循环（约每 100ms 调用一次）调用。
     *
     * @return true 表示可以调用相机拍照接口
     */
    public boolean shouldTriggerNow() {
        if (state != STATE_RUNNING || shotInFlight) {
            return false;
        }
        long now = System.nanoTime();
        return now >= nextShotAtNanos;
    }

    /**
     * 标记“已经调用相机拍照接口”，开始计时本张的拍摄时长。
     */
    public void onShotStarted() {
        shotStartNanos = System.nanoTime();
        shotInFlight = true;
    }

    /**
     * 相机报告本张已经成功写入 SD 卡（StoreImageComplete 回调）。
     *
     * @param fileName 相机给出的文件名，例如 "DSC01234.JPG"
     */
    public void onShotSaved(String fileName) {
        if (!shotInFlight) {
            return;
        }
        shotInFlight = false;
        takenCount++;
        lastShotDurationMs = (System.nanoTime() - shotStartNanos) / 1000000L;
        scheduleNext();
        if (listener != null) {
            listener.onShotSaved(takenCount, fileName);
        }
        if (!settings.isInfiniteCount() && takenCount >= settings.getEffectiveShotCount()) {
            setState(STATE_FINISHED);
            if (listener != null) {
                listener.onFinished(takenCount);
            }
        }
    }

    /**
     * 相机报告本张失败（快门被取消 / 写卡失败 / 曝光异常）。
     * 失败张数不计入已拍数量，但同样进入下一拍节拍，避免一直卡在同一张。
     */
    public void onShotFailed(String reason) {
        if (!shotInFlight) {
            return;
        }
        shotInFlight = false;
        failedCount++;
        lastShotDurationMs = (System.nanoTime() - shotStartNanos) / 1000000L;
        scheduleNext();
        if (listener != null) {
            listener.onShotFailed(reason);
        }
    }

    /**
     * 计算下一张的计划时刻（v1.1.1 节拍语义修正）：
     *
     * 旧行为：下一张 = 本张完成时刻 + 间隔。当单张耗时（对焦 + 曝光 + 写卡，
     * 真机上常达数秒）不可忽略时，实际节奏变成“单张耗时 + 间隔”，用户设置的
     * 1 秒间隔在真机上表现为十几秒，与直觉严重不符——这是用户反馈“实际间隔
     * 高达 10 多秒”的根因之一。
     *
     * 新行为：以下一张应当触发的绝对节拍为锚（节拍器语义）：
     *   - 正常：nextShotAtNanos += intervalMs（从上一张计划时刻累加间隔），
     *     即无论上一张拍了多久，两次“触发动作”之间的目标间隔严格等于设定值；
     *   - 若该时刻已过（单张耗时超过间隔，例如长曝光），立即触发并累加溢出
     *     计数，界面提示“间隔无法严格维持”。
     * 首张触发时刻由 start() 设定，后续依次累加，漂移不累积到下一张。
     */
    private void scheduleNext() {
        long intervalMs = settings.getIntervalMillis();
        long now = System.nanoTime();
        // 以“上一次计划触发时刻 + 间隔”为下一拍；计划时刻缺失时退回当前时刻
        long base = (nextShotAtNanos > 0) ? nextShotAtNanos : now;
        long planned = base + intervalMs * 1000000L;
        if (planned <= now) {
            // 已错过节拍：单张耗时超过间隔（溢出）。立刻触发，并记录溢出次数。
            overrunCount++;
            nextShotAtNanos = now;
        } else {
            nextShotAtNanos = planned;
        }
    }

    /**
     * 距下一次拍摄的倒计时（毫秒），用于界面显示“距下一张 x.x 秒”。
     * 暂停或未运行时返回 -1。
     */
    public long getCountdownMillis() {
        if (state != STATE_RUNNING) {
            return -1;
        }
        long now = System.nanoTime();
        long diff = nextShotAtNanos - now;
        if (diff < 0) {
            return 0;
        }
        return diff / 1000000L;
    }

    /**
     * 检查单张拍摄是否超时（相机回调迟迟不来）。
     *
     * @return true 表示已超时，调用方应提示错误并停止任务
     */
    public boolean isCaptureTimeout() {
        return shotInFlight && (System.nanoTime() - shotStartNanos) / 1000000L > CAPTURE_TIMEOUT_MS;
    }

    /**
     * 预计剩余时长（秒），用于开始前提示用户。无限模式返回 -1。
     */
    public long getEstimatedTotalSeconds() {
        if (settings.isInfiniteCount()) {
            return -1;
        }
        long perShot = Math.max(settings.getIntervalMillis(), 1000L);
        return settings.getEffectiveShotCount() * perShot / 1000L;
    }

    private void setState(int newState) {
        if (state == newState) {
            return;
        }
        state = newState;
        if (listener != null) {
            listener.onStateChanged(newState);
        }
    }

    /** 状态中文名称，供界面直接显示 */
    public static String stateName(int state) {
        switch (state) {
            case STATE_IDLE:
                return "待机";
            case STATE_RUNNING:
                return "拍摄中";
            case STATE_PAUSED:
                return "已暂停";
            case STATE_FINISHED:
                return "已结束";
            case STATE_ERROR:
                return "异常停止";
            default:
                return "未知";
        }
    }
}
