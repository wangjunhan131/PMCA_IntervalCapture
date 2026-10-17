package com.pmca.intervalcapture.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * DialogScreen —— 通用弹窗（完成提示、错误提示、二选一确认框）
 * <p>
 * 相机没有触屏，所以弹窗的选项全部通过“上下键切换 + 确认键决定”或
 * “左/右键直接选择 + 确认键决定”来完成。
 * <p>
 * 本应用用到的三种弹窗：
 *   1. 拍摄完成提示：显示总张数，并提供【是否生成延时视频】选项，
 *      ★ 默认选中“否”★，用户必须主动选择“是”才会进入（默认关闭，符合需求）；
 *   2. 错误提示（SD 卡满 / 写卡失败 / 过热）：只有一个“确定”，按确认键回到参数页；
 *   3. 通用确认框（例如“退出应用？”）。
 */
public final class DialogScreen {

    /** 弹窗框体（640x480 屏幕中央） */
    private static final Rect BOX = new Rect(60, 90, 580, 400);

    private final Paint boxPaint = UiTheme.newPaint(UiTheme.COLOR_PANEL, 1f, false);
    private final Paint borderPaint = UiTheme.newPaint(UiTheme.COLOR_ACCENT, 1f, false);
    private final Paint titlePaint = UiTheme.newPaint(UiTheme.COLOR_ACCENT, 24f, true);
    private final Paint bodyPaint = UiTheme.newPaint(UiTheme.COLOR_TEXT, 17f, false);
    private final Paint optionPaint = UiTheme.newPaint(UiTheme.COLOR_TEXT, 19f, false);
    private final Paint optionFocusPaint = UiTheme.newPaint(UiTheme.COLOR_ACCENT, 19f, true);
    private final Paint hintPaint = UiTheme.newPaint(UiTheme.COLOR_TEXT_DIM, 14f, false);
    private final Paint errBoxPaint = UiTheme.newPaint(UiTheme.COLOR_PANEL, 1f, false);
    private final Paint errBorderPaint = UiTheme.newPaint(UiTheme.COLOR_ERROR, 2f, false);
    private final Paint errTitlePaint = UiTheme.newPaint(UiTheme.COLOR_ERROR, 24f, true);
    private final Paint focusBgPaint = UiTheme.newPaint(UiTheme.COLOR_PANEL_FOCUS, 1f, false);
    private final Paint okPaint = UiTheme.newPaint(UiTheme.COLOR_OK, 19f, true);

    /**
     * 绘制“拍摄完成 + 是否生成延时视频”弹窗。
     *
     * @param canvas      画布
     * @param takenCount  本次成功保存的照片张数
     * @param failedCount 失败张数
     * @param chooseVideo true 表示当前焦点在“是”，false 表示在“否”（默认 false）
     */
    public void drawFinishDialog(Canvas canvas, int takenCount, int failedCount, boolean chooseVideo) {
        // 半透明遮罩：在相机 640x480 屏幕上用 4 条边条遮挡，避免依赖 alpha 混合支持
        canvas.drawColor(0xFF000000);
        canvas.drawRect(BOX, boxPaint);
        UiTheme.drawRectStroke(canvas, borderPaint, BOX, 2f);

        canvas.drawText("拍摄任务已完成", BOX.left + 24, BOX.top + 40, titlePaint);

        // 主体说明
        String line1 = "全部照片已经保存至 SD 卡。";
        String line2 = "成功 " + takenCount + " 张" + (failedCount > 0 ? "，失败 " + failedCount + " 张" : "");
        String line3 = "照片文件位于相机存储卡的 DCIM 目录（按机内设置生成 JPG / ARW）。";
        canvas.drawText(line1, BOX.left + 24, BOX.top + 86, bodyPaint);
        canvas.drawText(line2, BOX.left + 24, BOX.top + 114, okPaint);
        canvas.drawText(line3, BOX.left + 24, BOX.top + 146, bodyPaint);

        // 选项标题
        canvas.drawText("是否生成延时视频？（默认：否）", BOX.left + 24, BOX.top + 190, bodyPaint);

        // 两个选项按钮
        Rect noRect = new Rect(BOX.left + 40, BOX.top + 210, BOX.left + 200, BOX.top + 258);
        Rect yesRect = new Rect(BOX.left + 260, BOX.top + 210, BOX.left + 420, BOX.top + 258);
        // “否”是默认项，始终有绿色边框提示这是推荐选项
        canvas.drawRect(noRect, chooseVideo ? boxPaint : focusBgPaint);
        UiTheme.drawRectStroke(canvas, okPaint, noRect, 2f);
        canvas.drawRect(yesRect, chooseVideo ? focusBgPaint : boxPaint);
        UiTheme.drawRectStroke(canvas, borderPaint, yesRect, 1f);
        UiTheme.drawTextCentered(canvas, chooseVideo ? optionPaint : optionFocusPaint, "否（不生成）", noRect);
        UiTheme.drawTextCentered(canvas, chooseVideo ? optionFocusPaint : optionPaint, "是（生成视频）", yesRect);

        // 说明：默认关闭视频合成
        canvas.drawText("默认不生成视频；选择“是”后才会调用视频合成功能（可选附加项）。",
                BOX.left + 24, BOX.top + 286, hintPaint);

        // 按键提示
        canvas.drawText("左/右 切换选项    确认 确定    菜单 直接选择“否”并退出",
                BOX.left + 24, BOX.bottom - 18, hintPaint);
    }

    /**
     * 绘制错误弹窗（单按钮）。
     *
     * @param title   标题，例如“存储卡已满”
     * @param reason  中文原因，自动换行
     * @param extra   追加提示（可为 null），例如“已拍照片均已保存”
     */
    public void drawErrorDialog(Canvas canvas, String title, String reason, String extra) {
        canvas.drawColor(0xFF000000);
        canvas.drawRect(BOX, errBoxPaint);
        UiTheme.drawRectStroke(canvas, errBorderPaint, BOX, 2f);

        canvas.drawText(title, BOX.left + 24, BOX.top + 40, errTitlePaint);

        // 原因自动换行绘制（每行最多 30 个全角字符宽）
        int y = BOX.top + 84;
        java.util.List<String> lines = wrap(reason, 30);
        for (String l : lines) {
            canvas.drawText(l, BOX.left + 24, y, bodyPaint);
            y += 26;
        }
        if (extra != null) {
            canvas.drawText(extra, BOX.left + 24, y + 16, hintPaint);
        }

        // 确定按钮
        Rect okRect = new Rect(BOX.centerX() - 80, BOX.bottom - 66, BOX.centerX() + 80, BOX.bottom - 22);
        canvas.drawRect(okRect, focusBgPaint);
        UiTheme.drawRectStroke(canvas, borderPaint, okRect, 1f);
        UiTheme.drawTextCentered(canvas, optionFocusPaint, "确定", okRect);

        canvas.drawText("确认 / 菜单 返回参数设置页", BOX.left + 24, BOX.bottom - 96, hintPaint);
    }

    /**
     * 通用二选一确认框。
     *
     * @param emphasizeOption 0 表示高亮第一个选项，1 表示高亮第二个
     */
    public void drawConfirmDialog(Canvas canvas, String title, String message,
                                  String option0, String option1, int emphasizeOption) {
        canvas.drawColor(0xFF000000);
        canvas.drawRect(BOX, boxPaint);
        UiTheme.drawRectStroke(canvas, borderPaint, BOX, 2f);
        canvas.drawText(title, BOX.left + 24, BOX.top + 40, titlePaint);

        int y = BOX.top + 86;
        for (String l : wrap(message, 30)) {
            canvas.drawText(l, BOX.left + 24, y, bodyPaint);
            y += 26;
        }

        Rect r0 = new Rect(BOX.left + 40, BOX.top + 210, BOX.left + 220, BOX.top + 258);
        Rect r1 = new Rect(BOX.left + 260, BOX.top + 210, BOX.left + 440, BOX.top + 258);
        canvas.drawRect(r0, emphasizeOption == 0 ? focusBgPaint : boxPaint);
        UiTheme.drawRectStroke(canvas, borderPaint, r0, 1f);
        canvas.drawRect(r1, emphasizeOption == 1 ? focusBgPaint : boxPaint);
        UiTheme.drawRectStroke(canvas, borderPaint, r1, 1f);
        UiTheme.drawTextCentered(canvas, emphasizeOption == 0 ? optionFocusPaint : optionPaint, option0, r0);
        UiTheme.drawTextCentered(canvas, emphasizeOption == 1 ? optionFocusPaint : optionPaint, option1, r1);

        canvas.drawText("左/右 切换    确认 决定    菜单 取消", BOX.left + 24, BOX.bottom - 18, hintPaint);
    }

    /** 简单的中文按宽度折行（按字符数估算，中文字符按 1 计） */
    private java.util.List<String> wrap(String text, int maxCharsPerLine) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (text == null) {
            return out;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + maxCharsPerLine);
            out.add(text.substring(start, end));
            start = end;
            if (out.size() >= 3) {
                // 最多显示 3 行，剩余截断
                if (start < text.length()) {
                    out.set(out.size() - 1, out.get(out.size() - 1).substring(0, Math.max(0, out.get(out.size() - 1).length() - 1)) + "…");
                }
                break;
            }
        }
        return out;
    }
}
