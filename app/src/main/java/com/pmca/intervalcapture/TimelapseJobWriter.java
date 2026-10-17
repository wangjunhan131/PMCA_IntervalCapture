package com.pmca.intervalcapture;

/**
 * TimelapseJobWriter —— 可选附加功能：延时视频“合成任务清单”写入器
 * <p>
 * ⚠ 设计原则（严格遵守需求，也是本应用的安全边界）：
 *   1. 照片保存永远是第一优先级。本类只在用户于完成弹窗中**主动选择“是”**时被调用；
 *      默认路径（选择“否”或直接按菜单键）完全不会触达这里；
 *   2. 索尼 PMCA SDK 没有提供“把 SD 卡上已有多张照片编码成一个视频文件”的公开接口，
 *      固件自带的录制接口只能录实时画面。因此机内不会、也无法直接生成延时视频；
 *   3. 本类的做法是：在 SD 卡上生成一份纯文本任务清单 TIMELAPSE_JOB.TXT，
 *      记录本次拍摄的照片目录、张数、建议帧率与间隔，方便之后在电脑上用
 *      ffmpeg 等工具离线合成。全程不转码、不复制、不删除任何原始照片；
 *   4. 任何异常都只返回 false，绝不抛出——视频是附加功能，不能影响照片与拍摄流程。
 * <p>
 * 用到的接口：java.io 文件写入（普通文件 IO），照片本身由相机固件写入，
 * 本类只写一个 KB 级的文本文件，不会对存储卡造成压力。
 */
public final class TimelapseJobWriter {

    private TimelapseJobWriter() {
    }

    /**
     * 写入延时视频合成任务清单。
     *
     * @param takenCount 本次成功保存的照片张数
     * @param settings   本次使用的拍摄参数（用于记录间隔与格式）
     * @return true 表示写入成功
     */
    public static boolean writeJob(int takenCount, CaptureSettings settings) {
        if (takenCount <= 0) {
            // 一张都没拍成，没有可用的素材，直接返回
            return false;
        }
        java.io.FileOutputStream fos = null;
        try {
            // 清单写到 SD 卡 PMCACAP 目录，与日志、参数备份放在一起，方便用户定位
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStorageDirectory().getAbsolutePath(),
                    AppLog.SD_DIR_NAME);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            java.io.File jobFile = new java.io.File(dir, "TIMELAPSE_JOB.TXT");

            // 建议帧率：以“拍摄间隔”换算成 24fps 时间轴需要跳帧还是补帧，
            // 这里只给出一个常见的 24fps 建议值，真正的帧率由用户后期决定。
            int fps = 24;
            float intervalSec = settings.getIntervalSeconds();
            // 每一秒成片所覆盖的真实时长 = fps * 间隔；写在清单里方便后期计算
            int realSecondsPerVideoSecond = (int) (fps * intervalSec);
            // 成片时长（秒）
            float videoSeconds = takenCount / (float) fps;

            StringBuilder sb = new StringBuilder();
            sb.append("PMCA_IntervalCapture 延时视频合成任务清单\r\n");
            sb.append("==========================================\r\n");
            sb.append("本次成功保存照片数：").append(takenCount).append(" 张\r\n");
            sb.append("拍摄间隔：").append(settings.formatInterval()).append("\r\n");
            sb.append("图像格式：").append(settings.formatImageFormat()).append("\r\n");
            sb.append("建议帧率：").append(fps).append(" fps\r\n");
            sb.append("建议成片时长：").append((int) videoSeconds).append(" 秒\r\n");
            sb.append("1 秒成片对应现实时间：").append(realSecondsPerVideoSecond).append(" 秒\r\n");
            sb.append("\r\n");
            sb.append("照片位置：相机存储卡 DCIM 目录下（本应用不移动、不改名照片）\r\n");
            sb.append("\r\n");
            sb.append("电脑端合成示例（把照片按顺序编号后执行，需要 ffmpeg）：\r\n");
            sb.append("  ffmpeg -framerate ").append(fps).append(" -pattern_type glob -i \"*.JPG\" ")
                    .append("-c:v libx264 -pix_fmt yuv420p timelapse.mp4\r\n");
            sb.append("\r\n");
            sb.append("注意：本清单只是一份说明文档，机内不会自动生成任何视频文件。\r\n");
            sb.append("      原始照片请勿删除，它们是唯一的一手素材。\r\n");

            fos = new java.io.FileOutputStream(jobFile, false);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.flush();
            AppLog.info("已生成延时视频合成清单：" + jobFile.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            AppLog.warn("生成延时视频合成清单失败：" + t.getClass().getSimpleName());
            return false;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
