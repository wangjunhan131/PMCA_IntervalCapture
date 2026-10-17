package com.pmca.intervalcapture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * SystemEventReceiver —— 接收相机固件的应用生命周期广播
 * <p>
 * 相机固件在下列时机发送广播，应用需要响应，否则会出现“退出后黑屏”
 * 或“再次进入应用时相机被占用”等问题：
 * <pre>
 *   com.android.server.DAConnectionManagerService.BootCompleted
 *       → 固件侧已完成应用侧启动，可以安全操作相机；
 *   com.android.server.DAConnectionManagerService.ExitCompleted
 *       → 固件侧完成了退出流程，本应用需要释放相机资源。
 * </pre>
 * 说明：这是相机固件与 PMCA 应用之间的私有约定（不是安卓官方 API），
 * 名字里的“DAConnectionManager”就是索尼固件里的应用生命周期管理服务。
 * 这里只做日志记录与兜底处理，真正的相机打开/关闭仍由 CaptureActivity 的
 * 生命周期方法负责，避免两处同时操作相机造成冲突。
 */
public class SystemEventReceiver extends BroadcastReceiver {

    /** 固件：应用启动完成 */
    public static final String ACTION_BOOT_COMPLETED =
            "com.android.server.DAConnectionManagerService.BootCompleted";
    /** 固件：应用退出完成 */
    public static final String ACTION_EXIT_COMPLETED =
            "com.android.server.DAConnectionManagerService.ExitCompleted";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        if (ACTION_BOOT_COMPLETED.equals(action)) {
            // 固件已就绪：记录日志，便于排查相机无法打开的问题
            AppLog.info("收到固件广播：BootCompleted（应用启动完成）");
        } else if (ACTION_EXIT_COMPLETED.equals(action)) {
            // 固件已完成退出：记录日志，正常情况下 Activity 已经释放相机
            AppLog.info("收到固件广播：ExitCompleted（应用退出完成）");
        }
    }
}
