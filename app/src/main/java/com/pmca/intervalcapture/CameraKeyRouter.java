package com.pmca.intervalcapture;

import android.view.KeyEvent;

import com.sony.scalar.sysutil.ScalarInput;

/**
 * CameraKeyRouter —— 相机实体按键路由
 * <p>
 * A6500 机身虽有触摸屏，但本应用**不做任何触摸交互**，全部操作都靠机身实体按键；
 * 这样在不同 PMCA 机型（A6000 系无触屏 / A6500 有触屏）上行为完全一致，也避免误触。
 * 相机运行时把按键事件以 {@link KeyEvent} 形式
 * 投递到 Activity，但普通的 keyCode 在相机上不可靠（同一物理键在不同机型上 keyCode 可能
 * 不同），真正稳定的是“扫描码” {@link KeyEvent#getScanCode()}。
 * 扫描码的取值定义在索尼私有类 {@code com.sony.scalar.sysutil.ScalarInput} 中，
 * 例如：
 * <pre>
 *   ScalarInput.ISV_KEY_UP      = 103   上方向键
 *   ScalarInput.ISV_KEY_DOWN    = 108   下方向键
 *   ScalarInput.ISV_KEY_LEFT    = 105   左方向键
 *   ScalarInput.ISV_KEY_RIGHT   = 106   右方向键
 *   ScalarInput.ISV_KEY_ENTER   = 232   中央确认键
 *   ScalarInput.ISV_KEY_MENU    = 514   MENU 键（在应用内通常等价于“返回相机”）
 *   ScalarInput.ISV_KEY_SK1     = 229   左上软键
 *   ScalarInput.ISV_KEY_SK2     = 513   右下软键（部分机型为“删除/垃圾桶”键）
 *   ScalarInput.ISV_KEY_FN      = 520   FN 键
 *   ScalarInput.ISV_KEY_AEL     = 532   AEL 曝光锁定键
 *   ScalarInput.ISV_KEY_S1_1    = 516   快门半按
 *   ScalarInput.ISV_KEY_S2      = 518   快门全按
 * </pre>
 * 本类把这些扫描码翻译成“应用动作”，这样界面层只处理语义动作，与具体按键彻底解耦。
 */
public final class CameraKeyRouter {

    /* ============================ 应用动作定义 ============================ */

    /** 无动作 */
    public static final int ACTION_NONE = 0;
    /** 上：参数页 = 上一个参数；运行页 = 无（或加长间隔） */
    public static final int ACTION_UP = 1;
    /** 下：参数页 = 下一个参数；运行页 = 停止拍摄并返回参数页 */
    public static final int ACTION_DOWN = 2;
    /** 左：参数减 */
    public static final int ACTION_LEFT = 3;
    /** 右：参数加 */
    public static final int ACTION_RIGHT = 4;
    /** 确认：参数页 = 开始拍摄 / 运行页 = 开始-暂停切换 / 弹窗 = 确认选项 */
    public static final int ACTION_ENTER = 5;
    /** 菜单/返回：参数页 = 退出应用；运行页 = 停止并退出；弹窗 = 选“否” */
    public static final int ACTION_BACK = 6;
    /** FN 键：参数页 = 循环切换焦点（与上下键同效，作为备用） */
    public static final int ACTION_FN = 7;
    /** 快门键：参数页 = 直接开始；运行页 = 暂停/继续 */
    public static final int ACTION_SHUTTER = 8;
    /** 播放键：参数页 = 显示本次统计信息 */
    public static final int ACTION_PLAY = 9;

    /** 动作文本（界面底部按键提示直接使用） */
    public static String actionName(int action) {
        switch (action) {
            case ACTION_UP:
                return "上";
            case ACTION_DOWN:
                return "下";
            case ACTION_LEFT:
                return "左";
            case ACTION_RIGHT:
                return "右";
            case ACTION_ENTER:
                return "确认";
            case ACTION_BACK:
                return "菜单";
            case ACTION_FN:
                return "FN";
            case ACTION_SHUTTER:
                return "快门";
            case ACTION_PLAY:
                return "播放";
            default:
                return "?";
        }
    }

    private CameraKeyRouter() {
    }

    /* ============================ 按键 → 动作 ============================ */

    /**
     * 把一次“按下”的 KeyEvent 翻译成应用动作。
     *
     * @param event 按键事件
     * @return 应用动作常量
     */
    public static int routeKeyDown(KeyEvent event) {
        if (event == null) {
            return ACTION_NONE;
        }
        int scanCode = event.getScanCode();
        // 部分老固件扫描码为 0，此时退化为标准 keyCode 判断
        if (scanCode == 0) {
            return routeKeyCode(event.getKeyCode());
        }
        switch (scanCode) {
            case ScalarInput.ISV_KEY_UP:
            case ScalarInput.ISV_KEY_IR_UP:
                return ACTION_UP;
            case ScalarInput.ISV_KEY_DOWN:
            case ScalarInput.ISV_KEY_IR_DOWN:
                return ACTION_DOWN;
            case ScalarInput.ISV_KEY_LEFT:
            case ScalarInput.ISV_KEY_IR_LEFT:
                return ACTION_LEFT;
            case ScalarInput.ISV_KEY_RIGHT:
            case ScalarInput.ISV_KEY_IR_RIGHT:
                return ACTION_RIGHT;
            case ScalarInput.ISV_KEY_ENTER:
            case ScalarInput.ISV_KEY_SK1:
                return ACTION_ENTER;
            case ScalarInput.ISV_KEY_MENU:
            case ScalarInput.ISV_KEY_SK2:
            case ScalarInput.ISV_KEY_DELETE:
                return ACTION_BACK;
            case ScalarInput.ISV_KEY_FN:
                return ACTION_FN;
            case ScalarInput.ISV_KEY_S2:
                return ACTION_SHUTTER;
            case ScalarInput.ISV_KEY_S1_1:
            case ScalarInput.ISV_KEY_S1_2:
                // 半按快门不做动作：本应用自己控制对焦
                return ACTION_NONE;
            case ScalarInput.ISV_KEY_PLAY:
                return ACTION_PLAY;
            default:
                return ACTION_NONE;
        }
    }

    /**
     * 把“抬起”的 KeyEvent 翻译成应用动作。
     * 应用采用“按下即触发”的交互方式（相机 UI 习惯），抬起事件一律吞掉，
     * 避免同一个键产生两次动作。
     */
    public static int routeKeyUp(KeyEvent event) {
        return ACTION_NONE;
    }

    /** 扫描码缺失时的兜底：使用标准 Android keyCode */
    private static int routeKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return ACTION_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return ACTION_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return ACTION_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return ACTION_RIGHT;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                return ACTION_ENTER;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
                return ACTION_BACK;
            case KeyEvent.KEYCODE_CAMERA:
                return ACTION_SHUTTER;
            default:
                return ACTION_NONE;
        }
    }

    /**
     * 该扫描码是否属于“本应用要消费”的按键。
     * 返回 true 时 Activity 会返回 true 阻止事件继续传给系统（否则固件可能同时执行
     * “返回原生界面”之类的默认行为，导致两个动作叠加）。
     */
    public static boolean isConsumed(KeyEvent event) {
        return routeKeyDown(event) != ACTION_NONE;
    }
}
