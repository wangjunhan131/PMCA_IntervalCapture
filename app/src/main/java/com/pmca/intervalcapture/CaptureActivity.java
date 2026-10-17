package com.pmca.intervalcapture;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.pmca.intervalcapture.ui.DialogScreen;
import com.pmca.intervalcapture.ui.RunScreen;
import com.pmca.intervalcapture.ui.SettingsScreen;
import com.pmca.intervalcapture.ui.UiTheme;

/**
 * CaptureActivity —— 应用唯一的 Activity，负责界面绘制、按键分发与任务编排。
 * <p>
 * 为什么只有一个 Activity：
 *   相机固件通过广播 com.android.server.DAConnectionManagerService.AppInfoReceive 记录
 *   “当前前台应用”，多个 Activity 来回切换会让固件反复重建相机界面；
 *   单 Activity + 自绘 Canvas 是最稳定、最不容易让相机丢预览的做法。
 * <p>
 * 界面用 View 树实现（不是网页、也不是手机触屏 UI，而是相机固件支持的 Android View）：
 * <pre>
 *   FrameLayout
 *     ├── SurfaceView（预览层，z 序在下方；关闭预览时隐藏/缩小到 1x1）
 *     └── 自绘 CanvasView（所有文字/面板都画在这里，覆盖在预览之上但只画在左侧信息区）
 * </pre>
 * 三个界面（参数页 / 运行页 / 弹窗）由本类切换，不创建新的 Activity。
 * <p>
 * 线程模型：
 *   - 界面 100ms 定时刷新（Handler.postDelayed），负责倒计时显示、判断是否到点拍摄；
 *   - 索尼 SDK 的回调来自固件线程，回调里只做状态更新，真正访问相机（takePicture）
 *     一律通过 post 到 UI 线程执行，避免固件线程里重入相机接口。
 */
public class CaptureActivity extends Activity {

    /* ============================ 界面模式 ============================ */
    private static final int MODE_SETTINGS = 0;
    private static final int MODE_RUNNING = 1;
    private static final int MODE_FINISH_DIALOG = 2;
    private static final int MODE_ERROR_DIALOG = 3;
    private static final int MODE_CONFIRM_DIALOG = 4;

    /** 界面刷新间隔（毫秒）：100ms 即倒计时显示精度 0.1 秒 */
    private static final long TICK_MS = 100L;

    /* ============================ 状态字段 ============================ */
    private int mode = MODE_SETTINGS;
    private CaptureSettings settings;
    private SettingsStore store;
    private IntervalScheduler scheduler;
    private CaptureService captureService;
    private HealthMonitor healthMonitor;
    private SettingsScreen settingsScreen;
    private RunScreen runScreen;
    private DialogScreen dialogScreen;

    /** 画布视图 */
    private CanvasView canvasView;
    /** 预览视图 */
    private SurfaceView previewView;
    /** 预览是否可见 */
    private boolean previewVisible = false;

    /** 界面刷新 Handler（附着于 UI 主线程） */
    private final Handler uiHandler = new Handler();
    /** 保持 CPU 唤醒的锁：长时间间隔拍摄时防止休眠导致节拍漂移 */
    private PowerManager.WakeLock wakeLock;

    /** 相机是否打开成功 */
    private boolean cameraOk = false;
    /** 相机打开失败原因（显示在参数页） */
    private String cameraStatusLine = null;

    /** 最近一次提示信息 */
    private String message = null;
    private boolean messageIsError = false;

    /** 完成弹窗中“是否生成视频”的当前选项（默认 false = 否） */
    private boolean chooseVideo = false;
    /** 错误弹窗内容 */
    private String errorTitle = "";
    private String errorReason = "";
    private String errorExtra = null;
    /** 确认弹窗内容 */
    private String confirmTitle = "";
    private String confirmMessage = "";
    private String confirmOption0 = "确定";
    private String confirmOption1 = "取消";
    private int confirmEmphasis = 1;
    private int confirmPendingAction = 0;

    private static final int CONFIRM_EXIT_APP = 1;
    private static final int CONFIRM_CLEAR_STATS = 2;

    /* ============================ 生命周期 ============================ */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // 相机屏幕可能带自动息屏，拍摄期间需要常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // 屏幕颜色深度用高质量模式，避免文字发虚（部分固件默认是低色深）
        try {
            getWindow().setFormat(android.graphics.PixelFormat.RGB_565);
        } catch (Throwable ignored) {
        }

        // ① 日志与参数初始化
        AppLog.init(android.os.Environment.getExternalStorageDirectory().getAbsolutePath());
        AppLog.info("===== PMCA_IntervalCapture 启动 =====");
        store = new SettingsStore(this);
        settings = store.load();

        // ② 界面组件
        settingsScreen = new SettingsScreen();
        runScreen = new RunScreen();
        dialogScreen = new DialogScreen();
        healthMonitor = new HealthMonitor();

        buildViewTree();

        // ③ 向相机固件申报“当前前台应用”
        notifyAppInfo();

        // ④ 申请 CPU 唤醒锁（只在拍摄任务期间持有）
        acquireWakeLock();

        // ⑤ 相机在进入本界面时打开，退出时释放
        openCameraAsync();

        // ⑥ 启动界面刷新循环
        uiHandler.postDelayed(tickRunnable, TICK_MS);
    }

    /**
     * 构建 View 树：
     *   FrameLayout
     *     ├─ SurfaceView（预览）
     *     └─ CanvasView（覆盖层，透明背景，只在信息区绘制）
     */
    private void buildViewTree() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        previewView = new SurfaceView(this);
        previewView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        previewView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                // 预览显示面就绪：如果相机已打开则绑定显示面
                if (captureService != null && captureService.isOpened() && settings.isPreviewEnabled()) {
                    captureService.startPreview(holder);
                    previewVisible = true;
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                previewVisible = false;
                if (captureService != null) {
                    captureService.stopPreview();
                }
            }
        });
        FrameLayout.LayoutParams pvLp = new FrameLayout.LayoutParams(
                RunScreen.PREVIEW_RECT.width(), RunScreen.PREVIEW_RECT.height());
        pvLp.leftMargin = RunScreen.PREVIEW_RECT.left;
        pvLp.topMargin = RunScreen.PREVIEW_RECT.top;
        previewView.setVisibility(View.GONE);     // 参数页不需要预览
        root.addView(previewView, pvLp);

        canvasView = new CanvasView(this);
        root.addView(canvasView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.FILL_PARENT));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppLog.info("onResume");
        notifyAppInfo();
        uiHandler.removeCallbacks(tickRunnable);
        uiHandler.postDelayed(tickRunnable, TICK_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        AppLog.info("onPause");
        // 被相机固件切走（例如打开播放界面）时，必须先停止拍摄并释放相机，
        // 否则固件会认为相机被占用，出现“无法拍照”。
        stopTaskInternal("应用被切换到后台，拍摄已停止", true);
        uiHandler.removeCallbacks(tickRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AppLog.info("onDestroy");
        uiHandler.removeCallbacksAndMessages(null);
        closeCamera();
        healthMonitor.close();
        releaseWakeLock();
    }

    /* ============================ 相机打开 / 关闭 ============================ */

    /**
     * 异步打开相机：相机打开可能需要几百毫秒，不能阻塞 UI 线程，
     * 否则界面会白屏、按键无响应。
     */
    private void openCameraAsync() {
        cameraStatusLine = "正在打开相机…";
        invalidate();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = doOpenCamera();
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        cameraOk = ok;
                        cameraStatusLine = ok ? null : "相机初始化失败：请确认机内拍摄模式为静态照片(P/A/S/M)，然后返回重试";
                        if (!ok) {
                            AppLog.error("相机打开失败");
                        }
                        invalidate();
                    }
                });
            }
        }, "open-camera").start();
    }

    /** 在后台线程里真正调用 CameraEx.open（见 CaptureService） */
    private boolean doOpenCamera() {
        if (captureService == null) {
            captureService = new CaptureService(captureCallback);
        }
        SurfaceHolder holder = null;
        if (settings.isPreviewEnabled() && previewView != null && previewView.getHolder() != null) {
            holder = previewView.getHolder();
        }
        return captureService.open(holder, settings.isLockAeAf());
    }

    private void closeCamera() {
        if (captureService != null) {
            captureService.close();
            captureService = null;
        }
        cameraOk = false;
    }

    /* ============================ 采集回调（固件线程） ============================ */

    private final CaptureCallback captureCallback = new CaptureCallback() {
        @Override
        public void onCameraOpened() {
        }

        @Override
        public void onShotTaken() {
            // 快门动作：仅用于日志，不在固件线程里刷新界面
            AppLog.info("快门已动作");
        }

        @Override
        public void onImageStored(final String fileName) {
            // 写卡完成：切回 UI 线程更新调度器计数
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    healthMonitor.onStoreSuccess();
                    if (scheduler != null) {
                        scheduler.onShotSaved(fileName);
                    }
                    message = "已保存：" + fileName;
                    messageIsError = false;
                    invalidate();
                }
            });
        }

        @Override
        public void onCaptureFailed(final String reason) {
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleCaptureFailure(reason);
                }
            });
        }

        @Override
        public void onCameraError(final int errorCode) {
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    stopWithError("相机错误（代码 " + errorCode + "）",
                            "相机固件报告错误，拍摄已停止。请检查镜头是否安装到位、存储卡是否正常。",
                            "已保存的照片不受影响");
                }
            });
        }

        @Override
        public void onCameraClosed() {
        }
    };

    /* ============================ 主循环：刷新 + 节拍 ============================ */

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            doTick();
            invalidate();
            uiHandler.postDelayed(this, TICK_MS);
        }
    };

    /**
     * 每 100ms 执行一次：
     *   1. 运行模式下判断是否到点，到点则调用相机拍照；
     *   2. 检查单张拍摄是否超时；
     *   3. 检查是否因为过热/SD 卡问题需要中止。
     */
    private void doTick() {
        if (mode != MODE_RUNNING || scheduler == null) {
            return;
        }
        int state = scheduler.getState();
        if (state != IntervalScheduler.STATE_RUNNING) {
            return;
        }
        // 单张拍摄超时保护
        if (scheduler.isCaptureTimeout()) {
            AppLog.error("单张拍摄超时");
            if (captureService != null) {
                captureService.cancelCapture();
            }
            stopWithError("拍摄超时", "相机在 30 秒内没有完成写入，可能是存储卡写入过慢或机身异常。",
                    "已拍照片均已保存");
            return;
        }
        // 是否到点
        if (scheduler.shouldTriggerNow()) {
            triggerShot();
        }
    }

    /**
     * 触发一张拍摄：
     *   1. 先做健康巡检（SD 卡 / 空间 / 温度），有问题立即停止；
     *   2. 调用 CaptureService.takePicture（内部走 CameraEx → Camera.takePicture）。
     */
    private void triggerShot() {
        if (captureService == null || !captureService.isOpened()) {
            stopWithError("相机未就绪", "相机连接已断开，拍摄已停止。", "已拍照片均已保存");
            return;
        }
        // 健康巡检
        boolean firstShot = scheduler.getTakenCount() == 0 && scheduler.getFailedCount() == 0;
        if (!healthMonitor.checkBeforeShot(firstShot)) {
            stopWithError("拍摄已停止", healthMonitor.getFatalReason(), "已拍照片均已保存");
            return;
        }
        String warn = healthMonitor.getWarningText();
        if (warn != null) {
            message = warn;
            messageIsError = false;
        }
        // 标记开始：必须在调用 takePicture 之前标记，保证回调不会漏统计
        scheduler.onShotStarted();
        boolean submitted = captureService.takePicture();
        if (!submitted) {
            // 提交失败（例如上一张还没写完卡）：本张按失败处理，等下一次节拍
            scheduler.onShotFailed("拍摄请求未被接受");
        }
    }

    /* ============================ 任务控制 ============================ */

    /** 从参数页开始一轮拍摄任务 */
    private void startTask() {
        if (!cameraOk || captureService == null || !captureService.isOpened()) {
            // 相机没打开成功：重新尝试一次
            AppLog.warn("相机未就绪，重新打开");
            openCameraAsync();
            message = "相机未就绪，正在重新初始化…";
            messageIsError = true;
            invalidate();
            return;
        }
        // 保存参数，防止断电后丢失
        store.save(settings);

        scheduler = new IntervalScheduler(settings.copy());
        scheduler.setListener(schedulerListener);

        // 打开温度监控
        healthMonitor.open();

        // 按参数决定是否显示预览
        applyPreviewVisibility(settings.isPreviewEnabled());

        mode = MODE_RUNNING;
        message = "开始拍摄（" + settings.formatInterval() + " × "
                + (settings.isInfiniteCount() ? "无限" : settings.getEffectiveShotCount() + "张") + "）";
        messageIsError = false;
        scheduler.start();
        acquireWakeLock();

        // 相机硬件限制提示：间隔太短时立刻提醒
        String hw = settings.getHardwareWarning();
        if (hw != null) {
            message = hw + "（将尽力拍摄）";
        }
        invalidate();
    }

    /** 停止任务并回到参数页 */
    private void stopTaskInternal(String reason, boolean backToSettings) {
        if (scheduler != null && mode == MODE_RUNNING) {
            AppLog.info("停止拍摄：" + reason);
            if (captureService != null) {
                captureService.cancelCapture();
            }
            scheduler.stop(reason);
        }
        scheduler = null;
        healthMonitor.close();
        applyPreviewVisibility(false);
        mode = MODE_SETTINGS;
        if (backToSettings) {
            message = reason;
            messageIsError = false;
        }
        invalidate();
    }

    /** 出现不可恢复错误：停止拍摄、弹中文错误框 */
    private void stopWithError(String title, String reason, String extra) {
        AppLog.error("拍摄异常停止：" + title + " - " + reason);
        if (scheduler != null) {
            scheduler.fail(reason);
        }
        int taken = scheduler == null ? 0 : scheduler.getTakenCount();
        if (captureService != null) {
            captureService.cancelCapture();
        }
        scheduler = null;
        healthMonitor.close();
        applyPreviewVisibility(false);
        errorTitle = title;
        errorReason = reason;
        errorExtra = (taken > 0 ? "本次已成功保存 " + taken + " 张照片。" : "") + (extra == null ? "" : extra);
        mode = MODE_ERROR_DIALOG;
        invalidate();
    }

    /** 单张拍摄失败（不代表整轮任务失败）：累计失败次数过多才停止 */
    private void handleCaptureFailure(String reason) {
        AppLog.warn("单张拍摄失败：" + reason);
        boolean tooMany = healthMonitor.onStoreFailure();
        if (scheduler != null) {
            scheduler.onShotFailed(reason);
        }
        message = reason;
        messageIsError = true;
        if (tooMany) {
            stopWithError("相机异常", reason, null);
        }
        invalidate();
    }

    /* ============================ 调度器监听 ============================ */

    private final IntervalScheduler.Listener schedulerListener = new IntervalScheduler.Listener() {
        @Override
        public void onStateChanged(final int newState) {
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    AppLog.info("状态 -> " + IntervalScheduler.stateName(newState));
                }
            });
        }

        @Override
        public void onShotSaved(final int takenCount, final String fileName) {
            // onImageStored 已经做计数处理，这里不重复计数
        }

        @Override
        public void onShotFailed(final String reason) {
        }

        @Override
        public void onFinished(final int takenCount) {
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    showFinishDialog(takenCount);
                }
            });
        }
    };

    /** 拍摄完成：弹出“是否生成延时视频”提示，默认“否” */
    private void showFinishDialog(int takenCount) {
        int failed = scheduler == null ? 0 : scheduler.getFailedCount();
        store.addTotalTaken(takenCount);
        AppLog.info("任务完成：成功 " + takenCount + " 张，失败 " + failed + " 张");
        scheduler = null;
        healthMonitor.close();
        applyPreviewVisibility(false);
        chooseVideo = false;          // ★ 默认选中“否”，不生成视频
        finishTakenCount = takenCount;
        finishFailedCount = failed;
        mode = MODE_FINISH_DIALOG;
        invalidate();
    }

    private int finishTakenCount = 0;
    private int finishFailedCount = 0;

    /* ============================ 按键处理 ============================ */

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int action = CameraKeyRouter.routeKeyDown(event);
        if (action == CameraKeyRouter.ACTION_NONE) {
            return super.onKeyDown(keyCode, event);
        }
        handleAction(action);
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        int action = CameraKeyRouter.routeKeyUp(event);
        if (action == CameraKeyRouter.ACTION_NONE) {
            return super.onKeyUp(keyCode, event);
        }
        return true;
    }

    /** 按键动作分发（按当前界面模式） */
    private void handleAction(int action) {
        switch (mode) {
            case MODE_SETTINGS:
                handleSettingsAction(action);
                break;
            case MODE_RUNNING:
                handleRunningAction(action);
                break;
            case MODE_FINISH_DIALOG:
                handleFinishDialogAction(action);
                break;
            case MODE_ERROR_DIALOG:
                handleErrorDialogAction(action);
                break;
            case MODE_CONFIRM_DIALOG:
                handleConfirmDialogAction(action);
                break;
            default:
                break;
        }
        invalidate();
    }

    /* -------- 参数页按键 -------- */
    private void handleSettingsAction(int action) {
        switch (action) {
            case CameraKeyRouter.ACTION_UP:
                settingsScreen.moveFocus(-1);
                break;
            case CameraKeyRouter.ACTION_DOWN:
                settingsScreen.moveFocus(1);
                break;
            case CameraKeyRouter.ACTION_LEFT:
                settings.adjust(settingsScreen.getFocusIndex(), -1);
                store.save(settings);
                break;
            case CameraKeyRouter.ACTION_RIGHT:
                settings.adjust(settingsScreen.getFocusIndex(), 1);
                store.save(settings);
                break;
            case CameraKeyRouter.ACTION_FN:
                settingsScreen.moveFocus(1);
                break;
            case CameraKeyRouter.ACTION_ENTER:
            case CameraKeyRouter.ACTION_SHUTTER:
                startTask();
                break;
            case CameraKeyRouter.ACTION_PLAY:
                showConfirmDialog("拍摄统计",
                        "成功保存过的累计张数：" + store.getTotalTaken() + " 张\n"
                                + "日志文件：SD 卡 PMCACAP/LOG.TXT\n"
                                + "参数备份：SD 卡 PMCACAP/SETTINGS.TXT",
                        "知道了", "取消", 0, 0);
                break;
            case CameraKeyRouter.ACTION_BACK:
                showConfirmDialog("退出应用？", "按“确认”返回相机原生拍摄界面。",
                        "取消", "退出", 0, CONFIRM_EXIT_APP);
                break;
            default:
                break;
        }
    }

    /* -------- 运行页按键 -------- */
    private void handleRunningAction(int action) {
        if (scheduler == null) {
            mode = MODE_SETTINGS;
            return;
        }
        int state = scheduler.getState();
        switch (action) {
            case CameraKeyRouter.ACTION_ENTER:
            case CameraKeyRouter.ACTION_SHUTTER:
                // 确认键 / 快门键：开始 ⇄ 暂停
                if (state == IntervalScheduler.STATE_RUNNING) {
                    scheduler.pause();
                    if (captureService != null) {
                        captureService.cancelCapture();
                    }
                    message = "已暂停（按确认继续，计数不重置）";
                } else if (state == IntervalScheduler.STATE_PAUSED) {
                    scheduler.resume();
                    message = "继续拍摄";
                }
                messageIsError = false;
                break;
            case CameraKeyRouter.ACTION_DOWN:
                // 下方向键：停止拍摄，回到参数设置页
                stopTaskInternal("已手动停止拍摄", true);
                break;
            case CameraKeyRouter.ACTION_UP:
                // 上方向键：查看/切换预览（运行中切换预览有热量与稳定性取舍）
                togglePreviewDuringRun();
                break;
            case CameraKeyRouter.ACTION_LEFT:
            case CameraKeyRouter.ACTION_RIGHT:
                // 运行中不允许改参数：提示用户先停止，避免节拍混乱
                message = "运行中不可修改参数，请按“下”停止后再调整";
                messageIsError = false;
                break;
            case CameraKeyRouter.ACTION_BACK:
                stopTaskInternal("已停止并退出拍摄", true);
                break;
            default:
                break;
        }
    }

    /** 运行中切换预览开关（只影响显示，不影响拍摄） */
    private void togglePreviewDuringRun() {
        boolean enable = !settings.isPreviewEnabled();
        settings.setPreviewEnabled(enable);
        applyPreviewVisibility(enable);
        message = enable ? "预览已开启（注意发热）" : "预览已关闭（降低发热）";
        messageIsError = false;
    }

    /* -------- 完成弹窗按键 -------- */
    private void handleFinishDialogAction(int action) {
        switch (action) {
            case CameraKeyRouter.ACTION_LEFT:
            case CameraKeyRouter.ACTION_RIGHT:
            case CameraKeyRouter.ACTION_UP:
            case CameraKeyRouter.ACTION_DOWN:
                chooseVideo = !chooseVideo;
                break;
            case CameraKeyRouter.ACTION_ENTER:
            case CameraKeyRouter.ACTION_SHUTTER:
                if (chooseVideo) {
                    // ★ 用户明确选择“是”后才进入视频合成流程（可选附加功能）
                    startOptionalTimelapseVideo();
                }
                backToSettingsAfterDialog();
                break;
            case CameraKeyRouter.ACTION_BACK:
                // 菜单键 = 直接选“否”
                chooseVideo = false;
                backToSettingsAfterDialog();
                break;
            default:
                break;
        }
    }

    /* -------- 错误弹窗按键 -------- */
    private void handleErrorDialogAction(int action) {
        if (action == CameraKeyRouter.ACTION_ENTER
                || action == CameraKeyRouter.ACTION_BACK
                || action == CameraKeyRouter.ACTION_SHUTTER) {
            mode = MODE_SETTINGS;
            message = errorReason;
            messageIsError = true;
        }
    }

    /* -------- 确认弹窗按键 -------- */
    private void handleConfirmDialogAction(int action) {
        switch (action) {
            case CameraKeyRouter.ACTION_LEFT:
            case CameraKeyRouter.ACTION_RIGHT:
            case CameraKeyRouter.ACTION_UP:
            case CameraKeyRouter.ACTION_DOWN:
                confirmEmphasis = confirmEmphasis == 0 ? 1 : 0;
                break;
            case CameraKeyRouter.ACTION_ENTER:
            case CameraKeyRouter.ACTION_SHUTTER:
                mode = MODE_SETTINGS;
                if (confirmPendingAction == CONFIRM_EXIT_APP && confirmEmphasis == 1) {
                    exitToCameraUi();
                }
                confirmPendingAction = 0;
                break;
            case CameraKeyRouter.ACTION_BACK:
                mode = MODE_SETTINGS;
                confirmPendingAction = 0;
                break;
            default:
                break;
        }
    }

    private void showConfirmDialog(String title, String msg, String opt0, String opt1,
                                   int emphasis, int pendingAction) {
        confirmTitle = title;
        confirmMessage = msg;
        confirmOption0 = opt0;
        confirmOption1 = opt1;
        confirmEmphasis = emphasis;
        confirmPendingAction = pendingAction;
        mode = MODE_CONFIRM_DIALOG;
        invalidate();
    }

    private void backToSettingsAfterDialog() {
        mode = MODE_SETTINGS;
        message = "已完成本次拍摄，可继续设置新的参数";
        messageIsError = false;
        invalidate();
    }

    /* ============================ 可选：延时视频合成 ============================ */

    /**
     * 【可选附加功能】把本次拍摄的照片合成为延时视频。
     * <p>
     * 设计原则（严格遵守需求）：
     *   - 照片保存永远是第一优先级，本方法只会在用户主动选择“是”之后被调用；
     *   - 默认路径完全不进入本方法；
     *   - 合成失败绝不影响、也不会删除已保存的原图。
     * <p>
     * 关于实现：
     *   PMCA SDK 没有提供“把多张照片编码成一个视频文件”的公开接口。
     *   camera firmware 自带的视频录制接口（MediaRecorder 等）只能录制实时画面，
     *   无法把 SD 卡上已有的照片编码成视频。因此本方法只做两件事：
     *     1) 在 SD 卡上生成一份“视频合成任务清单” TIMELAPSE_JOB.TXT，
     *        记录照片目录、帧率建议等信息，供电脑端工具离线合成；
     *     2) 在界面上给出明确中文提示。
     *   这样既满足“可选附加功能”的需求，又保证机内不会悄悄生成视频，
     *   也不会因为编码占用机身算力而导致过热或拍摄中断。
     */
    private void startOptionalTimelapseVideo() {
        AppLog.info("用户选择生成延时视频（可选附加功能）");
        boolean ok = TimelapseJobWriter.writeJob(finishTakenCount, settings);
        String msg;
        if (ok) {
            msg = "已生成视频合成任务清单（SD 卡 PMCACAP/TIMELAPSE_JOB.TXT），可用电脑端工具合成";
        } else {
            msg = "视频合成清单写入失败，但照片已全部保存，不受影响";
        }
        message = msg;
        messageIsError = false;
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
    }

    /* ============================ 预览显隐 ============================ */

    /**
     * 控制预览视图可见性：
     *   - 打开：显示 SurfaceView 并启动预览；
     *   - 关闭：停止预览并把 SurfaceView 设为 GONE（不再占用显示带宽与传感器预览）。
     */
    private void applyPreviewVisibility(boolean visible) {
        if (previewView == null) {
            return;
        }
        if (visible) {
            previewView.setVisibility(View.VISIBLE);
            if (captureService != null && captureService.isOpened()) {
                captureService.startPreview(previewView.getHolder());
                previewVisible = true;
            }
        } else {
            if (captureService != null) {
                captureService.stopPreview();
            }
            previewVisible = false;
            previewView.setVisibility(View.GONE);
        }
        invalidate();
    }

    /* ============================ 绘制 ============================ */

    /** 触发一次重绘 */
    private void invalidate() {
        if (canvasView != null) {
            canvasView.postInvalidate();
        }
    }

    /**
     * 自绘 Canvas 视图：把三个界面的绘制委派给对应 Screen 类。
     * 背景透明，只有左侧信息区有内容，右侧留给相机预览。
     */
    private final class CanvasView extends View {

        CanvasView(android.content.Context context) {
            super(context);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // 画布固定 640x480；若固件分辨率不同，做等比缩放
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            if (w != UiTheme.SCREEN_W || h != UiTheme.SCREEN_H) {
                float sx = w / (float) UiTheme.SCREEN_W;
                float sy = h / (float) UiTheme.SCREEN_H;
                canvas.save();
                canvas.scale(sx, sy);
            }
            try {
                drawCurrentScreen(canvas);
            } catch (Throwable t) {
                // 绘制异常绝不能让应用崩溃（相机上崩溃会导致固件黑屏）
                AppLog.error("界面绘制异常", t);
                canvas.drawColor(Color.BLACK);
                Paint p = UiTheme.newPaint(Color.WHITE, 18f, false);
                canvas.drawText("界面绘制异常：" + t.getClass().getSimpleName(), 20, 40, p);
            }
            if (w != UiTheme.SCREEN_W || h != UiTheme.SCREEN_H) {
                canvas.restore();
            }
        }

        private void drawCurrentScreen(Canvas canvas) {
            switch (mode) {
                case MODE_SETTINGS:
                    settingsScreen.draw(canvas, settings, cameraOk, cameraStatusLine);
                    break;
                case MODE_RUNNING:
                    drawRunScreen(canvas);
                    break;
                case MODE_FINISH_DIALOG:
                    dialogScreen.drawFinishDialog(canvas, finishTakenCount, finishFailedCount, chooseVideo);
                    break;
                case MODE_ERROR_DIALOG:
                    dialogScreen.drawErrorDialog(canvas, errorTitle, errorReason, errorExtra);
                    break;
                case MODE_CONFIRM_DIALOG:
                    dialogScreen.drawConfirmDialog(canvas, confirmTitle, confirmMessage,
                            confirmOption0, confirmOption1, confirmEmphasis);
                    break;
                default:
                    canvas.drawColor(UiTheme.COLOR_BG);
                    break;
            }
        }

        private void drawRunScreen(Canvas canvas) {
            if (scheduler == null) {
                canvas.drawColor(UiTheme.COLOR_BG);
                return;
            }
            // 存储卡剩余空间文本
            String freeText = readFreeText();
            runScreen.draw(canvas, scheduler, scheduler.getSettings(),
                    healthMonitor.getBoxTempText(), freeText,
                    message, messageIsError, previewVisible);
        }
    }

    /** 读取 SD 卡剩余空间并格式化（只为界面显示，读取失败时显示“未知”） */
    private String readFreeText() {
        try {
            android.os.StatFs stat = new android.os.StatFs(
                    android.os.Environment.getExternalStorageDirectory().getAbsolutePath());
            // 与 HealthMonitor 相同：只用 API 10 就存在的 StatFs 方法
            long blockSize = stat.getBlockSize();
            long blocks = stat.getAvailableBlocks();
            long bytes = blockSize * blocks;
            if (bytes <= 0) {
                return "未知";
            }
            long gb = bytes / (1024L * 1024L * 1024L);
            if (gb > 0) {
                return "剩余 " + gb + " GB";
            }
            return "剩余 " + (bytes / (1024L * 1024L)) + " MB";
        } catch (Throwable t) {
            return "未知";
        }
    }

    /* ============================ 与相机固件的协作 ============================ */

    /**
     * 向相机固件申报“当前前台应用”。
     * 广播：com.android.server.DAConnectionManagerService.AppInfoReceive
     * 固件据此判断要不要在自己那一侧启动/停止原生拍摄界面。
     */
    private void notifyAppInfo() {
        try {
            Intent intent = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
            intent.putExtra("package_name", getPackageName());
            intent.putExtra("class_name", getComponentName().getClassName());
            sendBroadcast(intent);
        } catch (Throwable t) {
            AppLog.warn("上报应用信息失败：" + t.getMessage());
        }
    }

    /**
     * 退出应用，返回相机原生拍摄界面。
     * 与索尼官方应用列表中的其他 PMCA 应用一致的做法：
     *   finish() + 启动 ACTION_MAIN/CATEGORY_HOME 的 Intent，
     *   固件会接管并回到原生拍照 UI。
     */
    private void exitToCameraUi() {
        AppLog.info("退出应用，返回相机原生界面");
        stopTaskInternal("已退出", false);
        closeCamera();
        try {
            finish();
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            AppLog.error("返回相机界面失败", t);
        }
    }

    /* ============================ 电源管理 ============================ */

    /**
     * 获取 CPU 唤醒锁：长间隔（例如 60 秒一张）拍摄时，相机可能因为“省电”把应用挂起，
     * 导致节拍漂移。这里在任务期间持有 PARTIAL_WAKE_LOCK，保证计时与写卡不被中断。
     */
    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PMCACAP:TIMER");
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire();
            }
        } catch (Throwable t) {
            AppLog.warn("获取唤醒锁失败：" + t.getMessage());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {
        }
    }
}
