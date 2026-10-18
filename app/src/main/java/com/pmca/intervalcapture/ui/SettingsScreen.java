package com.pmca.intervalcapture.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import com.pmca.intervalcapture.CaptureSettings;
import com.pmca.intervalcapture.IntervalScheduler;

/**
 * SettingsScreen —— 参数配置页（相机 640x480 屏幕，纯实体按键操作）
 * <p>
 * 页面结构（640x480）：
 * <pre>
 *  y  0~44   标题栏：应用名 + “参数设置”
 *  y 44~400  参数列表（6 项，每项 56 高）：
 *              拍摄间隔 / 固定张数 / 图像格式 / 曝光锁定 / 预览 / 任务模式
 *              焦点行高亮，右侧显示当前值
 *  y400~440  硬件限制警告行（间隔过短/RAW/预览时黄字提示）
 *  y440~480  按键提示行
 * </pre>
 * 按键（由 Activity 统一分发）：
 *   上/下    切换当前参数行
 *   左/右    减小/增大当前参数（开关类为切换）
 *   确认     开始拍摄
 *   菜单     退出应用，返回相机原生界面
 */
public final class SettingsScreen {

    /** 参数行高度 */
    private static final int ROW_H = 56;
    /** 列表起始 y */
    private static final int ROW_TOP = 48;
    /** 左侧标签宽度 */
    private static final int LABEL_W = 230;

    private final Paint titlePaint = UiTheme.newPaint(UiTheme.COLOR_TEXT, UiTheme.TEXT_TITLE, true);
    private final Paint labelPaint = UiTheme.newPaint(UiTheme.COLOR_TEXT, UiTheme.TEXT_LABEL, false);
    private final Paint labelFocusPaint = UiTheme.newPaint(UiTheme.COLOR_ACCENT, UiTheme.TEXT_LABEL, true);
    private final Paint valuePaint = UiTheme.newPaint(UiTheme.COLOR_TEXT, UiTheme.TEXT_VALUE, true);
    private final Paint hintPaint = UiTheme.newPaint(UiTheme.COLOR_TEXT_DIM, UiTheme.TEXT_HINT, false);
    private final Paint warnPaint = UiTheme.newPaint(UiTheme.COLOR_WARN, UiTheme.TEXT_HINT, true);
    private final Paint panelPaint = UiTheme.newPaint(UiTheme.COLOR_PANEL, 1f, false);
    private final Paint focusPaint = UiTheme.newPaint(UiTheme.COLOR_PANEL_FOCUS, 1f, false);
    private final Paint barPaint = UiTheme.newPaint(UiTheme.COLOR_TITLE_BAR, 1f, false);
    private final Paint linePaint = UiTheme.newPaint(UiTheme.COLOR_PANEL_FOCUS, 1f, false);

    /** 当前焦点行 */
    private int focusIndex = 0;

    public int getFocusIndex() {
        return focusIndex;
    }

    /** 焦点上移 / 下移（循环） */
    public void moveFocus(int delta) {
        int n = CaptureSettings.FIELD_COUNT;
        focusIndex = ((focusIndex + delta) % n + n) % n;
    }

    /** 参数行名称 */
    public static String fieldLabel(int index) {
        switch (index) {
            case CaptureSettings.FIELD_INTERVAL:
                return "拍摄间隔";
            case CaptureSettings.FIELD_COUNT_MODE:
                return "拍摄张数模式";
            case CaptureSettings.FIELD_SHOT_COUNT:
                return "拍摄总张数";
            case CaptureSettings.FIELD_FORMAT:
                return "图像存储格式";
            case CaptureSettings.FIELD_LOCK:
                return "曝光锁定";
            case CaptureSettings.FIELD_PREVIEW:
                return "实时预览";
            case CaptureSettings.FIELD_EXPOSURE_MODE:
                return "曝光模式";
            case CaptureSettings.FIELD_APERTURE:
                return "光圈调整";
            case CaptureSettings.FIELD_SHUTTER:
                return "快门调整";
            case CaptureSettings.FIELD_ISO:
                return "感光度 ISO";
            default:
                return "";
        }
    }

    /** 参数行当前值文本 */
    public static String fieldValue(CaptureSettings s, int index) {
        switch (index) {
            case CaptureSettings.FIELD_INTERVAL:
                return s.formatInterval();
            case CaptureSettings.FIELD_COUNT_MODE:
                return s.isInfiniteCount() ? "无限连续" : "固定张数";
            case CaptureSettings.FIELD_SHOT_COUNT:
                return s.isInfiniteCount() ? "（无限模式不适用）" : s.formatShotCount();
            case CaptureSettings.FIELD_FORMAT:
                return s.formatImageFormat();
            case CaptureSettings.FIELD_LOCK:
                return s.isLockAeAf() ? "开" : "关";
            case CaptureSettings.FIELD_PREVIEW:
                return s.formatPreview();
            case CaptureSettings.FIELD_EXPOSURE_MODE:
                return s.formatExposureMode();
            case CaptureSettings.FIELD_APERTURE:
                return s.formatAperture();
            case CaptureSettings.FIELD_SHUTTER:
                return s.formatShutter();
            case CaptureSettings.FIELD_ISO:
                return s.formatIso();
            default:
                return "";
        }
    }

    /** 参数行下方的补充说明（小字） */
    public static String fieldHint(CaptureSettings s, int index) {
        switch (index) {
            case CaptureSettings.FIELD_INTERVAL:
                return "范围 0.1~3600 秒，步进 0.1 秒（低于 1 秒可能无法严格维持）";
            case CaptureSettings.FIELD_COUNT_MODE:
                return "固定张数：拍完自动停止；无限：手动按停止";
            case CaptureSettings.FIELD_SHOT_COUNT:
                return "1~9999 张";
            case CaptureSettings.FIELD_FORMAT:
                return "按机内设置写入 SD 卡原图（本应用不做转码）";
            case CaptureSettings.FIELD_LOCK:
                return "开：首张锁定 AE/AF/WB，防止画面明暗闪烁";
            case CaptureSettings.FIELD_PREVIEW:
                return "关：降低机身发热，提升长间隔稳定性";
            case CaptureSettings.FIELD_EXPOSURE_MODE:
                return "跟随机内=不改曝光；应用接管=下面三项生效";
            case CaptureSettings.FIELD_APERTURE:
                return "步进档位，需镜头支持、机内 A/M 档";
            case CaptureSettings.FIELD_SHUTTER:
                return "步进档位，需机内 S/M 档；长曝会占用间隔";
            case CaptureSettings.FIELD_ISO:
                return "仅 M 档可设；自动时由机内控制";
            default:
                return "";
        }
    }

    /**
     * 绘制整个参数页。
     *
     * @param canvas   画布
     * @param settings 当前参数
     * @param cameraOk 相机是否可用（不可用时在状态行提示）
     * @param statusLine 额外的状态说明（例如相机打开失败原因），可为 null
     */
    public void draw(Canvas canvas, CaptureSettings settings, boolean cameraOk, String statusLine) {
        // 背景
        canvas.drawColor(UiTheme.COLOR_BG);

        // ---------- 标题栏 ----------
        Rect titleBar = new Rect(0, 0, UiTheme.SCREEN_W, 44);
        canvas.drawRect(titleBar, barPaint);
        canvas.drawText("间隔拍照  |  参数设置", 16, 31, titlePaint);
        valuePaint.setTextSize(UiTheme.TEXT_HINT);
        valuePaint.setColor(UiTheme.COLOR_TEXT_DIM);
        UiTheme.drawTextRight(canvas, valuePaint, "PMCA_IntervalCapture v1.1.0", UiTheme.SCREEN_W - 14, 29);
        valuePaint.setTextSize(UiTheme.TEXT_VALUE);
        valuePaint.setColor(UiTheme.COLOR_TEXT);

        // ---------- 参数列表 ----------
        for (int i = 0; i < CaptureSettings.FIELD_COUNT; i++) {
            int top = ROW_TOP + i * ROW_H;
            Rect row = new Rect(8, top, UiTheme.SCREEN_W - 8, top + ROW_H - 4);
            boolean focused = (i == focusIndex);
            // 行背景：焦点行用亮色面板并加左侧高亮条
            canvas.drawRect(row, focused ? focusPaint : panelPaint);
            if (focused) {
                canvas.drawRect(new Rect(row.left, row.top, row.left + 6, row.bottom),
                        UiTheme.newPaint(UiTheme.COLOR_ACCENT, 1f, false));
            }
            // 标签（左侧）
            Paint lp = focused ? labelFocusPaint : labelPaint;
            canvas.drawText(fieldLabel(i), row.left + 18, top + 26, lp);
            // 值（左侧标签右边，右对齐到行尾）
            UiTheme.drawTextRight(canvas, valuePaint, fieldValue(settings, i), row.right - 18, top + 26);
            // 小字说明
            hintPaint.setColor(focused ? UiTheme.COLOR_TEXT_DIM : UiTheme.COLOR_TEXT_DIM);
            canvas.drawText(fieldHint(settings, i), row.left + 18, top + 45, hintPaint);
        }

        // ---------- 警告区 ----------
        int warnY = ROW_TOP + CaptureSettings.FIELD_COUNT * ROW_H + 8;   // 约 y=392
        UiTheme.drawHLine(canvas, linePaint, 8, UiTheme.SCREEN_W - 8, warnY - 6);
        String warning = settings.getHardwareWarning();
        if (warning != null) {
            // 用 [!] 而不是 ⚠：固件字体表缺这个 Unicode 符号，会显示成空心方框
            canvas.drawText("[!] " + warning, 16, warnY + 14, warnPaint);
        } else {
            warnPaint.setColor(UiTheme.COLOR_OK);
            canvas.drawText("[OK] 当前设置符合机型的稳定拍摄能力", 16, warnY + 14, warnPaint);
            warnPaint.setColor(UiTheme.COLOR_WARN);
        }
        if (statusLine != null) {
            warnPaint.setColor(cameraOk ? UiTheme.COLOR_TEXT_DIM : UiTheme.COLOR_ERROR);
            // 最长 2 行：失败原因可能包含异常类名，单行放不下
            UiTheme.drawTextWrapped(canvas, warnPaint, statusLine, 16, warnY + 34,
                    UiTheme.SCREEN_W - 32, 2, 17f);
            warnPaint.setColor(UiTheme.COLOR_WARN);
        }

        // ---------- 按键提示 ----------
        int hintY = UiTheme.SCREEN_H - 16;
        String hint = "上/下 选择   左/右 调整   确认 开始拍摄   菜单 退出";
        canvas.drawText(hint, 16, hintY, hintPaint);
        // 预计时长靠右显示
        IntervalScheduler dummy = null;   // 预计时长直接用参数计算，不依赖调度器
        long totalSec;
        if (settings.isInfiniteCount()) {
            totalSec = -1;
        } else {
            totalSec = settings.getEffectiveShotCount() * Math.max(settings.getIntervalMillis(), 1000L) / 1000L;
        }
        UiTheme.drawTextRight(canvas, hintPaint, "预计时长 " + UiTheme.durationText(totalSec),
                UiTheme.SCREEN_W - 16, hintY);
    }
}
