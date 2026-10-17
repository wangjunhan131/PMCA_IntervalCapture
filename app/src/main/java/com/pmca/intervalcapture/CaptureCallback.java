package com.pmca.intervalcapture;

/**
 * CaptureCallback —— 相机捕获事件回调（由 CaptureService 向上层通知）
 * <p>
 * 之所以要这层回调，是因为 PMCA SDK 的回调来自固件侧的线程（不是 UI 线程），
 * 上层（Activity）收到后必须切回自己的定时器线程再刷新界面。
 */
public interface CaptureCallback {

    /**
     * 相机已打开、可以开始拍照
     */
    void onCameraOpened();

    /**
     * 快门已动作（照片已开始写入 SD 卡）
     */
    void onShotTaken();

    /**
     * 照片已经完整写入 SD 卡
     *
     * @param fileName 相机给出的文件名，例如 "DSC01234.JPG"
     */
    void onImageStored(String fileName);

    /**
     * 本次拍摄失败
     *
     * @param reason 中文原因
     */
    void onCaptureFailed(String reason);

    /**
     * 相机内部错误（来自 CameraEx.setErrorCallback）
     *
     * @param errorCode 相机固件错误码
     */
    void onCameraError(int errorCode);

    /**
     * 相机被关闭/释放
     */
    void onCameraClosed();
}
