package com.pmca.intervalcapture.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import com.pmca.intervalcapture.CaptureSettings;
import com.pmca.intervalcapture.IntervalScheduler;

/**
 * RunScreen —— 运行状态界面（拍摄中 / 暂停 / 已结束 / 异常）
 * <p>
 * 640x480 布局：
 * <pre>
 *  y  0~40   标题栏：状态文字（待机/拍摄中/已暂停/已结束/异常停止） + 录制红点
 *  y 40~190  “已拍张数”大号数字 + 剩余张数 + 进度条
 *  y190~300  “距下一张 x.x 秒”大号倒计时 + 最近一张耗时
 *  y300~352  参数摘要（间隔 / 张数 / 格式 / 锁定 / 预览）
 *  y352~400  健康状态（机身温度等级 / 存储卡剩余 / 异常说明）
 *  y400~440  提示信息（最近一次警告或错误）
 *  y440~480  按键提示
 * </pre>
 * 画面右侧预留一块区域给相机预览（由 Activity 的 SurfaceView 覆盖），
 * 保证“预览开”时能实时看画面、“预览关”时这块区域就是纯黑。
 */
public final class RunScreen {

    /** 预览区域（逻辑坐标），SurfaceView 会按这个矩形对齐 */
    public static final Rect PREVIEW_RECT = new Rect(344, 40, 640, 340);

    private final Paint titlePaint = UiTheme.newPaint(UiTheme.COLOR_TEXT, UiTheme.TEXT_TITLE, true);
    private final Paint bigPaint = UiTheme.newPaint(UiTheme.COLOR_TEXT, UiTheme.TEXT_BIG, true);
    private final Paint hugePaint = UiTheme.newPaint(UiTheme.COLOR_ACCENT, UiTheme.TEXT_HUGE, true);
    private final Paint labelPaint = UiTheme.newPaint(UiTheme.COLOR_TEXT_DIM, UiTheme.TEXT_LABEL, false);
    private final Paint textPaint = UiTheme.newPaint(UiTheme.COLOR_TEXT, UiTheme.TEXT_VALUE, false);
    private final Paint hintPaint = UiTheme.newPaint(UiTheme.COLOR_TEXT_DIM, UiTheme.TEXT_HINT, false);
    private final Paint warnPaint = UiTheme.newPaint(UiTheme.COLOR_WARN, UiTheme.TEXT_HINT, true);
    private final Paint errPaint = UiTheme.newPaint(UiTheme.COLOR_ERROR, UiTheme.TEXT_HINT, true);
    private final Paint okPaint = UiTheme.newPaint(UiTheme.COLOR_OK, UiTheme.TEXT_HINT, true);
    private final Paint panelPaint = UiTheme.newPaint(UiTheme.COLOR_PANEL, 1f, false);
    private final Paint barBgPaint = UiTheme.newPaint(UiTheme.COLOR_PANEL_FOCUS, 1f, false);
    private final Paint barFgPaint = UiTheme.newPaint(UiTheme.COLOR_OK, 1f, false);
    private final Paint recPaint = UiTheme.newPaint(UiTheme.COLOR_REC, 1f, false);
    private final Paint barPaint = UiTheme.newPaint(UiTheme.COLOR_TITLE_BAR, 1f, false);

    /** 状态栏颜色随状态变化 */
    private int statusColor(int state) {
        switch (state) {
            case IntervalScheduler.STATE_RUNNING:
                return UiTheme.COLOR_REC;
            case IntervalScheduler.STATE_PAUSED:
                return UiTheme.COLOR_WARN;
            case IntervalScheduler.STATE_ERROR:
                return UiTheme.COLOR_ERROR;
            case IntervalScheduler.STATE_FINISHED:
                return UiTheme.COLOR_OK;
            default:
                return UiTheme.COLOR_ACCENT;
        }
    }

    /**
     * 绘制运行界面。
     *
     * @param canvas      画布
     * @param scheduler   调度器（读取计数、倒计时、状态）
     * @param settings    本次任务使用的参数快照
     * @param tempText    机身温度描述
     * @param freeText    存储卡剩余空间描述
     * @param message     最近一次提示（成功/警告/错误），可为 null
     * @param messageIsError 提示是否表示错误
     * @param showPreview 是否显示预览区域（关闭时该区域画成纯黑并写“预览已关闭”）
     */
    public void draw(Canvas canvas, IntervalScheduler scheduler, CaptureSettings settings,
                     String tempText, String freeText, String message, boolean messageIsError,
                     boolean showPreview) {
        canvas.drawColor(UiTheme.COLOR_BG);
        int state = scheduler.getState();

        // ---------- 标题栏 ----------
        Rect titleBar = new Rect(0, 0, UiTheme.SCREEN_W, 40);
        canvas.drawRect(titleBar, barPaint);
        // 拍摄中时在标题左侧画一个红色圆点（模拟录制指示）
        int titleX = 16;
        if (state == IntervalScheduler.STATE_RUNNING) {
            canvas.drawCircle(26, 20, 8, recPaint);
            titleX = 44;
        }
        canvas.drawText("间隔拍摄  |  " + IntervalScheduler.stateName(state), titleX, 28, titlePaint);
        hintPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("间隔 " + settings.formatInterval(), UiTheme.SCREEN_W - 16, 27, hintPaint);
        hintPaint.setTextAlign(Paint.Align.LEFT);

        // ---------- 左侧信息区（宽 344）----------
        int leftW = PREVIEW_RECT.left;

        // 已拍张数（大号）——v1.1.1：字号 72→60，避免数字下沿压到 y=186 的剩余张数行
        canvas.drawText("已拍张数", 16, 74, labelPaint);
        int taken = scheduler.getTakenCount();
        // 无限模式张数可能很大，字号自适应
        hugePaint.setTextSize(taken >= 10000 ? 46f : 60f);
        UiTheme.drawTextRight(canvas, hugePaint, String.valueOf(taken), leftW - 16, 138);

        // 剩余张数 / 总张数
        int remain = scheduler.getRemainingCount();
        String totalText = settings.isInfiniteCount()
                ? "总张数：无限"
                : "总张数：" + settings.getEffectiveShotCount();
        canvas.drawText("剩余张数：" + (remain < 0 ? "无限" : String.valueOf(remain)), 16, 186, textPaint);
        canvas.drawText(totalText, 16, 208, textPaint);

        // 进度条（固定张数模式）
        int barLeft = 16, barRight = leftW - 16, barY = 218;
        canvas.drawRect(new Rect(barLeft, barY, barRight, barY + 10), barBgPaint);
        int percent = scheduler.getProgressPercent();
        if (percent < 0) {
            // 无限模式：用“已拍张数对 100 的余数”做一个循环进度，纯装饰
            int cyc = taken % 100;
            int w = (barRight - barLeft) * cyc / 100;
            canvas.drawRect(new Rect(barLeft, barY, barLeft + w, barY + 10), barFgPaint);
        } else {
            int w = (barRight - barLeft) * percent / 100;
            canvas.drawRect(new Rect(barLeft, barY, barLeft + w, barY + 10), barFgPaint);
            UiTheme.drawTextRight(canvas, hintPaint, percent + "%", barRight, barY + 28);
        }

        // ---------- 倒计时（v1.1.1：字号 72→56，避免字面下沿压住 y=352 的"上张耗时"行造成重叠） ----------
        canvas.drawText("距下一张", 16, 264, labelPaint);
        long countdown = scheduler.getCountdownMillis();
        String cdText;
        if (state == IntervalScheduler.STATE_PAUSED) {
            cdText = "--.-";
        } else if (state == IntervalScheduler.STATE_FINISHED || state == IntervalScheduler.STATE_ERROR) {
            cdText = "--.-";
        } else if (scheduler.isShotInFlight()) {
            cdText = "拍摄中";
            hugePaint.setTextSize(42f);
        } else {
            cdText = UiTheme.secondsText(countdown) + " 秒";
            hugePaint.setTextSize(56f);
        }
        UiTheme.drawTextRight(canvas, hugePaint, cdText, leftW - 16, 318);
        hugePaint.setTextSize(72f);

        // 最近一张耗时 / 失败张数 / 节拍溢出次数
        long lastMs = scheduler.getLastShotDurationMs();
        canvas.drawText("上张耗时 " + UiTheme.secondsText(lastMs) + " 秒", 16, 352, hintPaint);
        String fails = "失败 " + scheduler.getFailedCount() + " 张";
        if (scheduler.getOverrunCount() > 0) {
            fails += "   节拍溢出 " + scheduler.getOverrunCount() + " 次";
        }
        canvas.drawText(fails, 16, 372, scheduler.getOverrunCount() > 0 ? warnPaint : hintPaint);

        // ---------- 健康状态 ----------
        canvas.drawText("机身温度：" + tempText, 16, 398, hintPaint);
        canvas.drawText("存储卡：" + freeText, 16, 418, hintPaint);

        // ---------- 提示区 ----------
        if (message != null) {
            Paint mp = messageIsError ? errPaint : warnPaint;
            canvas.drawText(truncate(mp, message, UiTheme.SCREEN_W - 32), 16, 442, mp);
        }

        // ---------- 预览区域 ----------
        Rect pr = PREVIEW_RECT;
        canvas.drawRect(pr, panelPaint);
        if (!showPreview) {
            Paint p = UiTheme.newPaint(UiTheme.COLOR_TEXT_DIM, UiTheme.TEXT_HINT, false);
            UiTheme.drawTextCentered(canvas, p, "预览已关闭（降低发热）", pr);
        }

        // ---------- 按键提示 ----------
        canvas.drawText("确认 暂停/继续    下 停止   菜单 退出", 16, UiTheme.SCREEN_H - 8, hintPaint);
    }

    /** 文本过长时截断加省略号，避免超出屏幕（用 ASCII 点，固件字体缺"…"字形） */
    private String truncate(Paint p, String text, int maxWidth) {
        if (UiTheme.measure(p, text) <= maxWidth) {
            return text;
        }
        String s = text;
        while (s.length() > 1 && UiTheme.measure(p, s + "..") > maxWidth) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "..";
    }
}
