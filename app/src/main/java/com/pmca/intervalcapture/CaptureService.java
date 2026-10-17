package com.pmca.intervalcapture;

import android.hardware.Camera;
import android.view.SurfaceHolder;

import com.sony.scalar.hardware.CameraEx;

/**
 * CaptureService —— 索尼 PMCA SDK 静态照片捕获封装（本应用最核心的硬件交互层）
 * <p>
 * ===================== 用到的索尼 PMCA SDK 接口清单 =====================
 * 1. {@code CameraEx.open(int cameraId, CameraEx.OpenOptions options)}
 *    打开相机。第一个参数 0 表示主（唯一）成像器；options 传 null 表示使用固件当前设置。
 *    返回值是 CameraEx 实例，本应用所有相机控制都通过它完成。
 *
 * 2. {@code CameraEx.getNormalCamera()} → {@code android.hardware.Camera}
 *    取得“普通静态拍摄”通道。这是拍静态照片必须用的通道。
 *
 * 3. {@code Camera.setPreviewDisplay(SurfaceHolder)} / {@code Camera.startPreview()} /
 *    {@code Camera.stopPreview()}
 *    分别用于绑定预览显示面、开启预览、关闭预览。关闭预览可以显著降低机身发热
 *    （界面上的“预览开关”就是控制这里）。
 *
 * 4. {@code CameraEx.burstableTakePicture()}
 *    PMCA 平台上触发静态照片的推荐接口（必须在 open() 之后、且相机空闲时调用）：
 *      - 应用不接收 JPEG 数据，照片由相机固件按机内设置
 *        （JPG / RAW / JPG+RAW、画质、色彩空间）直接编码并写入 SD 卡；
 *      - 触发后固件回调 ShutterListener.onShutter(...)，应用在那时调用
 *        cancelTakePicture() 结束连拍状态并恢复预览；
 *      - 应用只负责“扣扳机”，不参与编码，因此绝不会额外生成视频。
 *    ★ 本应用【绝不】调用 MediaRecorder、也不调用任何视频录制接口。
 *
 * 5. {@code CameraEx.setShutterListener(CameraEx.ShutterListener)}
 *    快门动作回调：onShutter(int status, CameraEx camera)，
 *    status == STATUS_OK 表示快门已正常动作，STATUS_CANCELED 被取消，STATUS_ERROR 出错。
 *
 * 6. {@code CameraEx.setStoreImageCompleteListener(CameraEx.StoreImageCompleteListener)}
 *    写卡完成回调：onDone(int status, CameraEx.StoreImageInfo info, CameraEx camera)，
 *    info.FileName / info.DirectoryName 就是照片在 SD 卡上的文件名与目录名。
 *    这是判断“这一张真的存好了”的唯一可靠依据，间隔拍摄的计数必须以此为准。
 *
 * 7. {@code CameraEx.setErrorCallback(CameraEx.ErrorCallback)}
 *    固件级错误回调（镜头异常、机械错误、写卡失败等）。
 *
 * 8. {@code CameraEx.cancelTakePicture()} —— 用户在写卡过程中按停止键时取消本次拍摄。
 * 9. {@code CameraEx.release()} —— 释放相机（退出应用时必须调用，否则固件侧资源不释放）。
 * 10. {@code CameraEx.setAutoPictureReviewControl(CameraEx.AutoPictureReviewControl)}
 *     传入 null 表示关闭“拍后回看”，避免每张之后屏幕回放照片打断连续拍摄。
 *
 * ===================== 快门/对焦/曝光的锁定策略 =====================
 * 参数“曝光锁定开关”（对应 CameraEx 的 AE/AF/WB 锁定语义）开启时：
 *   1. 首张：正常触发一次自动对焦（固件在 AF 的同时完成 AE 测光与 AWB 白平衡）；
 *   2. 之后每一张：不再调用 autoFocus()，也不再修改任何机内参数，
 *      镜头焦点停在 AF 结果上，AE/AWB 结果被固件沿用，画面亮度保持一致；
 *   3. 因为没有重新测光，连续拍摄的亮度不会出现“忽明忽暗”的闪烁——
 *      这正是间隔拍摄（延时素材）最看重的性质。
 * 关闭时：每张拍摄前都重新 autoFocus()，即每张重新测光对焦（适合光线缓慢变化、
 * 且不希望画面固定在一个曝光值的场景）。
 * 说明：PMCA SDK 未提供“显式 AEL 锁定”的公开方法，本应用通过“不重新测量”的方式
 * 自然保持锁定；真正改变 AE 状态的只有固件自身（例如镜头/光线突变）。
 */
public final class CaptureService {

    /** 相机通道编号：主成像器 */
    private static final int CAMERA_ID = 0;

    private final CaptureCallback callback;

    /** 索尼 CameraEx 实例（打开成功后非空） */
    private CameraEx cameraEx;
    /** 普通静态拍摄通道 */
    private Camera normalCamera;
    /** 是否已经打开了相机 */
    private boolean opened = false;
    /** 预览是否正在运行 */
    private boolean previewRunning = false;
    /** 当前是否有一张正在拍摄中（防止重入） */
    private boolean captureInFlight = false;
    /** 当前这一张是否已经收到“快门动作”回调 */
    private boolean shutterFired = false;

    /** 第一张拍摄后置为 true，用于 AE/AF 锁定策略 */
    private boolean firstShotDone = false;
    /** 本次任务是否需要锁定 AE/AF */
    private boolean lockAeAf = true;
    /** 最近一次失败的详细原因（用于在相机屏幕上显示，便于排查真机问题） */
    private String lastError = null;
    /** 本次触发拍摄前预览是否在运行：快门回调后需要恢复预览 */
    private boolean restorePreviewAfterShot = false;

    public CaptureService(CaptureCallback callback) {
        this.callback = callback;
    }

    /* ============================ 打开 / 关闭 ============================ */

    /**
     * 打开相机并（可选）启动预览。
     *
     * @param previewHolder   预览显示面；传 null 表示关闭预览
     * @param lockAeAfOnFirst 是否在第一张之后锁定 AE/AF/WB
     * @return 打开成功返回 true
     */
    public boolean open(SurfaceHolder previewHolder, boolean lockAeAfOnFirst) {
        close();
        this.lockAeAf = lockAeAfOnFirst;
        this.firstShotDone = false;
        try {
            // ① 调用 Sony 的【静态工厂方法】CameraEx.open(cameraId, options) 打开相机。
            //    ★ 注意：在索尼固件里 open 是 public static 方法，
            //      必须写成 CameraEx.open(...)，不能写 new CameraEx().open(...)；
            //      CameraEx 自身没有公开构造器（固件里整个类没有 <init>），
            //      写成 new CameraEx() 会在真机上抛 NoSuchMethodError。
            //    第一个参数 CAMERA_ID=0 表示主（唯一）成像器；
            //    OpenOptions 传 null 表示完全沿用相机固件当前的拍摄设置
            //    （测光模式、AF 模式、画质、JPG/RAW 格式等都由机内菜单决定）。
            //    返回值是一个 CameraEx 实例，之后所有索尼接口都通过它调用。
            cameraEx = CameraEx.open(CAMERA_ID, null);
            if (cameraEx == null) {
                lastError = "CameraEx.open 返回 null（可能机内正处在非静态照片模式）";
                AppLog.error(lastError);
                return false;
            }
            // ② 取普通静态拍摄通道
            normalCamera = cameraEx.getNormalCamera();
            if (normalCamera == null) {
                lastError = "getNormalCamera 返回 null";
                AppLog.error(lastError);
                close();
                return false;
            }
            // ③ 注册快门回调：得知每张的快门动作
            cameraEx.setShutterListener(new CameraEx.ShutterListener() {
                @Override
                public void onShutter(int status, CameraEx camera) {
                    handleShutterStatus(status);
                }
            });
            // ④ 注册写卡完成回调：这是“照片真的存进 SD 卡”的唯一依据
            cameraEx.setStoreImageCompleteListener(new CameraEx.StoreImageCompleteListener() {
                @Override
                public void onDone(int status, CameraEx.StoreImageInfo info, CameraEx camera) {
                    handleStoreCompleted(status, info);
                }
            });
            // ⑤ 注册固件级错误回调
            cameraEx.setErrorCallback(new CameraEx.ErrorCallback() {
                @Override
                public void onError(int errorCode, CameraEx camera) {
                    AppLog.error("相机固件错误码：" + errorCode);
                    if (callback != null) {
                        callback.onCameraError(errorCode);
                    }
                }
            });
            // ⑥ 关闭拍后回看：连续拍摄时回放会遮挡画面并拖慢节拍
            try {
                cameraEx.setAutoPictureReviewControl(null);
            } catch (Throwable t) {
                AppLog.warn("关闭拍后回看失败（不影响拍摄）");
            }
            opened = true;
            // ⑦ 按需开启预览
            if (previewHolder != null) {
                startPreview(previewHolder);
            }
            AppLog.info("相机已打开，预览=" + (previewHolder != null));
            if (callback != null) {
                callback.onCameraOpened();
            }
            return true;
        } catch (Throwable t) {
            // ★ 真机排查关键：把异常类型与消息原样保留下来，
            //   界面会把它显示在相机屏幕上（例如 java.lang.NoSuchMethodError: ...）。
            //   历史上 A6500 初始化失败就是靠这一行定位到“open 被误写成实例方法”的。
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            AppLog.error("打开相机失败", t);
            close();
            return false;
        }
    }

    /**
     * 释放相机。退出运行界面时务必调用，否则相机固件可能一直被占用，
     * 表现为返回原生界面后无法拍照。
     */
    public void close() {
        if (previewRunning && normalCamera != null) {
            try {
                normalCamera.stopPreview();
            } catch (Throwable t) {
                AppLog.warn("stopPreview 异常：" + t.getMessage());
            }
            previewRunning = false;
        }
        if (cameraEx != null) {
            try {
                cameraEx.release();     // 释放固件侧相机资源
            } catch (Throwable t) {
                AppLog.warn("release 异常：" + t.getMessage());
            }
        }
        cameraEx = null;
        normalCamera = null;
        opened = false;
        captureInFlight = false;
        shutterFired = false;
        if (callback != null) {
            callback.onCameraClosed();
        }
    }

    public boolean isOpened() {
        return opened;
    }

    /**
     * 最近一次打开/拍摄失败的详细原因（异常类名 + message）。
     * 界面会把它显示在相机屏幕上，方便没有电脑时也能看到真实错误。
     */
    public String getLastError() {
        return lastError;
    }

    public boolean isCaptureInFlight() {
        return captureInFlight;
    }

    /* ============================ 预览控制 ============================ */

    /** 开启预览：绑定显示面并启动预览流 */
    public boolean startPreview(SurfaceHolder holder) {
        if (normalCamera == null || holder == null) {
            return false;
        }
        try {
            normalCamera.setPreviewDisplay(holder);
            normalCamera.startPreview();
            previewRunning = true;
            return true;
        } catch (Throwable t) {
            AppLog.warn("开启预览失败：" + t.getMessage());
            return false;
        }
    }

    /** 关闭预览（降低发热）；关闭后仍可正常拍照 */
    public void stopPreview() {
        if (normalCamera == null || !previewRunning) {
            return;
        }
        try {
            normalCamera.stopPreview();
        } catch (Throwable t) {
            AppLog.warn("关闭预览异常：" + t.getMessage());
        }
        previewRunning = false;
    }

    public boolean isPreviewRunning() {
        return previewRunning;
    }

    /** 设置显示面（例如从关闭预览切换为打开预览时） */
    public boolean setPreviewDisplay(SurfaceHolder holder) {
        if (normalCamera == null || holder == null) {
            return false;
        }
        try {
            normalCamera.setPreviewDisplay(holder);
            return true;
        } catch (Throwable t) {
            AppLog.warn("绑定预览显示面失败：" + t.getMessage());
            return false;
        }
    }

    /* ============================ 拍照 ============================ */

    /**
     * 触发一张静态照片。
     * <p>
     * 关键实现说明：
     *   - {@code takePicture(shutter, null, null)} 的三个回调全部传 null 时，
     *     PMCA 固件会把照片按机内设置直接写入 SD 卡，应用不参与编码；
     *   - 这里额外传入一个 ShutterCallback，仅用于“知道快门何时动作”，
     *     不会触发任何图片数据回调，也就不会消耗内存；
     *   - 对焦策略：仅在“未锁定 AE/AF”或“第一张”时执行自动对焦；
     *     锁定开启时后续张不再重新对焦，保证焦点与曝光一致。
     *
     * @return true 表示已成功提交拍摄请求
     */
    public boolean takePicture() {
        if (normalCamera == null || !opened) {
            return false;
        }
        if (captureInFlight) {
            // 上一张还没写完卡，拒绝重复触发（间隔太短时会走到这里）
            return false;
        }
        try {
            if (!previewRunning) {
                // 某些固件在“不预览”状态下拍摄前需要一次空预览启动，否则返回错误；
                // 这里用异常捕获兜底，失败也不影响后续 takePicture 的尝试。
                try {
                    normalCamera.startPreview();
                    normalCamera.stopPreview();
                } catch (Throwable ignored) {
                }
            }
            // 对焦策略：未锁定 AE/AF，或者这是第一张时，才重新对焦
            boolean needFocus = (!lockAeAf) || (!firstShotDone);
            if (needFocus) {
                try {
                    // Camera.autoFocus：触发一次单次自动对焦。
                    // 索尼固件在这里会同时完成 AE 与 AWB 的“抓取”，结果在后续张沿用。
                    normalCamera.autoFocus(null);
                } catch (Throwable t) {
                    AppLog.warn("自动对焦触发失败：" + t.getMessage());
                }
                // 给固件一点时间完成测光/对焦（半按快门的等效等待）
                try {
                    Thread.sleep(lockAeAf ? 600 : 250);
                } catch (InterruptedException ignored) {
                }
            }
            captureInFlight = true;
            shutterFired = false;
            // 记录触发前的预览状态：快门回调里要据此恢复预览
            restorePreviewAfterShot = previewRunning;
            // ★ 真正触发静态照片：cameraEx.burstableTakePicture()。
            //   这是 Sony PMCA 平台上最可靠的“单张静态照片”触发方式
            //   （官方示例与 OpenMemories 系应用都用它）：
            //     - 调用后由固件按机内设置（JPG / RAW / JPG+RAW）直接编码并写入 SD 卡；
            //     - 应用不接收任何 JPEG 数据，也【绝不】调用 MediaRecorder 或视频接口；
            //     - 快门动作完成后固件回调 ShutterListener.onShutter(...)，
            //       我们在那个回调里 cancelTakePicture() 结束本次连拍状态并恢复预览。
            cameraEx.burstableTakePicture();
            AppLog.info("触发拍摄：第 " + (firstShotDone ? "后续" : "首") + " 张，对焦=" + needFocus);
            return true;
        } catch (Throwable t) {
            captureInFlight = false;
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            AppLog.error("触发拍摄失败", t);
            return false;
        }
    }

    /**
     * 取消当前正在进行的拍摄（用户按停止键、或写卡超时时调用）。
     */
    public void cancelCapture() {
        if (cameraEx == null || !captureInFlight) {
            return;
        }
        try {
            cameraEx.cancelTakePicture();      // 取消本次拍摄，已写入的部分由固件回滚
            AppLog.info("已取消当前拍摄");
        } catch (Throwable t) {
            AppLog.warn("cancelTakePicture 异常：" + t.getMessage());
        }
        captureInFlight = false;
    }

    /* ============================ 回调处理 ============================ */

    /**
     * 处理快门回调（PMCA SDK 的 ShutterListener，运行在固件线程）。
     * <p>
     * burstableTakePicture() 触发的是“连拍式”拍摄状态，快门动作后必须调用
     * cancelTakePicture() 把它停掉，否则相机会一直停在连拍状态、无法拍下一张；
     * 停掉之后再恢复预览，画面才会回到实时取景。
     */
    private void handleShutterStatus(int status) {
        shutterFired = true;
        // ① 结束本次“连拍式”触发状态，让相机回到可再次触发的空闲状态
        if (cameraEx != null) {
            try {
                cameraEx.cancelTakePicture();
            } catch (Throwable t) {
                AppLog.warn("cancelTakePicture 异常：" + t.getMessage());
            }
        }
        // ② 恢复实时预览（如果触发前预览是开着的）
        if (restorePreviewAfterShot && normalCamera != null) {
            try {
                normalCamera.startPreview();
                previewRunning = true;
            } catch (Throwable t) {
                AppLog.warn("恢复预览失败：" + t.getMessage());
            }
            restorePreviewAfterShot = false;
        }
        if (status == CameraEx.ShutterListener.STATUS_OK) {
            // 快门正常动作：照片开始写卡，等待 StoreImageComplete 回调
            AppLog.info("快门动作 OK");
            if (callback != null) {
                callback.onShotTaken();
            }
        } else if (status == CameraEx.ShutterListener.STATUS_CANCELED) {
            AppLog.warn("快门被取消");
            captureInFlight = false;
            if (callback != null) {
                callback.onCaptureFailed("本次拍摄被取消");
            }
        } else if (status == CameraEx.ShutterListener.STATUS_ERROR) {
            AppLog.error("快门错误");
            captureInFlight = false;
            if (callback != null) {
                callback.onCaptureFailed("相机快门错误，请检查机身");
            }
        }
    }

    /**
     * 处理写卡完成回调（PMCA SDK 的 StoreImageCompleteListener，运行在固件线程）。
     */
    private void handleStoreCompleted(int status, CameraEx.StoreImageInfo info) {
        captureInFlight = false;
        if (info == null) {
            if (callback != null) {
                callback.onCaptureFailed("拍照失败：未获得文件信息（可能是存储卡已满或故障）");
            }
            return;
        }
        String fileName = info.FileName;
        String dirName = info.DirectoryName;
        if (fileName == null) {
            fileName = "(未知文件名)";
        }
        AppLog.info("照片已保存：" + dirName + "/" + fileName + "，状态=" + status);
        firstShotDone = true;
        if (callback != null) {
            // 注意：status 的具体含义随固件版本不同（0 通常表示成功），
            // 只要拿到了 FileName 就认为写卡完成；失败场景由 ShutterListener / ErrorCallback 报出。
            callback.onImageStored(fileName);
        }
    }

    /* ============================ 状态查询 ============================ */

    /** 本张是否已经收到快门动作回调 */
    public boolean isShutterFired() {
        return shutterFired;
    }

    /** 是否已经拍过第一张（用于界面上提示“已锁定 AE/AF”） */
    public boolean isFirstShotDone() {
        return firstShotDone;
    }
}
