package com.pmca.intervalcapture.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * UiTheme —— 界面绘制常量与公共绘制方法
 * <p>
 * 相机屏幕固定 640x480、无触屏，因此：
 *   - 坐标全部使用像素绝对值，不做 dpi 换算；
 *   - 所有绘制都走 Canvas + Paint，不使用任何可点击 View；
 *   - 字体统一使用等宽/大字号，保证在相机小屏上可读。
 */
public final class UiTheme {

    /** 逻辑画布宽（与相机屏幕一致） */
    public static final int SCREEN_W = 640;
    /** 逻辑画布高 */
    public static final int SCREEN_H = 480;

    /* ---------------------------- 颜色 ---------------------------- */
    public static final int COLOR_BG = Color.rgb(18, 18, 18);
    public static final int COLOR_PANEL = Color.rgb(32, 32, 34);
    public static final int COLOR_PANEL_FOCUS = Color.rgb(52, 52, 56);
    public static final int COLOR_TITLE_BAR = Color.rgb(0, 96, 128);
    public static final int COLOR_TEXT = Color.rgb(235, 235, 235);
    public static final int COLOR_TEXT_DIM = Color.rgb(150, 150, 150);
    public static final int COLOR_ACCENT = Color.rgb(64, 200, 255);
    public static final int COLOR_WARN = Color.rgb(255, 196, 0);
    public static final int COLOR_ERROR = Color.rgb(255, 72, 72);
    public static final int COLOR_OK = Color.rgb(80, 220, 120);
    public static final int COLOR_REC = Color.rgb(255, 40, 40);

    /* ---------------------------- 字号 ---------------------------- */
    public static final float TEXT_TITLE = 22f;
    public static final float TEXT_LABEL = 18f;
    public static final float TEXT_VALUE = 19f;
    public static final float TEXT_HINT = 14f;
    public static final float TEXT_BIG = 46f;
    public static final float TEXT_HUGE = 72f;

    private UiTheme() {
    }

    /** 创建一个默认 Paint（抗锯齿、无衬线字体） */
    public static Paint newPaint(int color, float size, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setTextSize(size);
        p.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD
                : android.graphics.Typeface.DEFAULT);
        return p;
    }

    /** 画一条水平分隔线 */
    public static void drawHLine(Canvas c, Paint p, int x1, int x2, int y) {
        c.drawLine(x1, y, x2, y, p);
    }

    /** 画矩形边框 */
    public static void drawRectStroke(Canvas c, Paint p, Rect r, float width) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        c.drawRect(r, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** 在矩形内居中绘制文本（水平+垂直居中） */
    public static void drawTextCentered(Canvas c, Paint p, String text, Rect r) {
        float x = r.exactCenterX();
        float y = r.exactCenterY() - (p.descent() + p.ascent()) / 2f;
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(text, x, y, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    /** 左对齐绘制文本，返回文本宽度 */
    public static float drawTextLeft(Canvas c, Paint p, String text, float x, float y) {
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText(text, x, y, p);
        return p.measureText(text);
    }

    /** 右对齐绘制文本 */
    public static void drawTextRight(Canvas c, Paint p, String text, float right, float y) {
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(text, right, y, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    /** 文本宽度 */
    public static float measure(Paint p, String text) {
        return p.measureText(text);
    }

    /** 用等宽数字格式输出“x.x”，避免相机 JVM 上 String.format 的本地化差异 */
    public static String oneDecimal(long tenths) {
        if (tenths < 0) {
            return "0.0";
        }
        long whole = tenths / 10;
        long deci = tenths % 10;
        return whole + "." + deci;
    }

    /** 秒 → “x.x” 文本（用于倒计时） */
    public static String secondsText(long millis) {
        if (millis < 0) {
            return "--.-";
        }
        return oneDecimal(millis / 100L);
    }

    /**
     * 把秒数格式化成“1小时23分45秒”形式，用于预计时长显示。
     */
    public static String durationText(long totalSeconds) {
        if (totalSeconds < 0) {
            return "无限";
        }
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) {
            sb.append(h).append("小时");
        }
        if (m > 0) {
            sb.append(m).append("分");
        }
        sb.append(s).append("秒");
        return sb.toString();
    }
}
