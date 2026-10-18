package com.pmca.intervalcapture;

import android.hardware.Camera;
import android.hardware.Camera.Parameters;

import com.sony.scalar.hardware.CameraEx;

/**
 * ExposureController —— 曝光控制（光圈 / 快门 / ISO）硬件层（v1.1.0 新增）
 * <p>
 * ===================== 用到的索尼 PMCA SDK 接口清单 =====================
 * 1. {@code CameraEx.createParametersModifier(android.hardware.Camera.Parameters)}
 *    创建参数修改器。★ 必须在相机打开之后调用（传入 getNormalCamera().getParameters()），
 *    相机未打开时调用会直接失败——OpenMemories 的 TimeLapse 源码里也印证了这一点
 *    （它把 modifier 用法写在了 SettingsActivity 中但整体注释掉，就是因为设置页里
 *    相机尚未 open，modifier 不可用）。
 *
 * 2. {@code CameraEx.incrementAperture() / decrementAperture()}
 *    光圈步进。★ 索尼固件没有“直接设置 F 值”的接口，只能按镜头档位逐档步进；
 *    增 = F 值变大（光圈收小），减 = F 值变大反方向（光圈开大）。
 *    档位表随镜头不同而不同，应用不需要（也无法）知道具体 F 值。
 *
 * 3. {@code CameraEx.incrementShutterSpeed() / decrementShutterSpeed()}
 *    快门速度步进。同样只有相对档位操作；增 = 更慢（长曝方向）或更快
 *    取决于固件定义，这里以“decrement = 更快 / increment = 更慢”约定并在 UI 标明。
 *
 * 4. {@code ParametersModifier.setISOSensitivity(int)} + {@code getSupportedISOSensitivities()}
 *    ISO 直接设置。setISOSensitivity 的参数是感光度数值本身（如 100/400/1600），
 *    是否生效取决于机内拨盘（M/A/S 档且非自动 ISO 时生效）；
 *    先用 getSupportedISOSensitivities() 判断请求值是否在支持列表里，
 *    不在时取列表中最接近的一档。
 *
 * 5. {@code ParametersModifier.setAutoExposureLock(String)}
 *    AE 锁定：AE_LOCK_ON（"locked"）。曝光锁定开关打开时在第一张前调用一次，
 *    让所有后续张复用第一张的测光结果——这是“防止延时画面明暗闪烁”的固件级保障，
 *    比应用层“不再对焦”更可靠（v1.0.x 只用了后者）。
 *
 * ===================== 兼容性策略 =====================
 * 不同机型/固件的 PMCA 运行时对上述方法支持不一（例如老机型可能没有
 * createParametersModifier）。因此本类【每一个】索尼接口调用都包在
 * try/catch(Throwable) 里：NoSuchMethodError / RuntimeException 一律吞掉并
 * 记录日志、上报 UI 提示，绝不让它炸掉拍摄主流程。曝光控制是“尽力而为”：
 * 失败时照片仍然按机内设置正常拍摄保存。
 */
public final class ExposureController {

    /** 应用结果：成功 */
    public static final int RESULT_OK = 0;
    /** 应用结果：相机未打开 */
    public static final int RESULT_NO_CAMERA = 1;
    /** 应用结果：部分或全部命令被固件拒绝/不支持（不影响拍摄） */
    public static final int RESULT_PARTIAL = 2;

    /** 最近一次应用的说明文本（显示在运行页/参数页），null 表示没有需要说明的 */
    private String lastNotice;
    /** 是否至少成功执行了一条命令 */
    private boolean anyApplied = false;

    public String getLastNotice() {
        return lastNotice;
    }

    public boolean isAnyApplied() {
        return anyApplied;
    }

    /**
     * 按参数把曝光意图下发到相机。
     * 只在【曝光模式 = 应用接管】时被调用；跟随机内模式时本方法不会被触发。
     *
     * @param cameraEx 已打开的 CameraEx 实例
     * @param settings 本次任务参数
     * @return RESULT_OK / RESULT_NO_CAMERA / RESULT_PARTIAL
     */
    public int apply(CameraEx cameraEx, CaptureSettings settings) {
        lastNotice = null;
        anyApplied = false;
        if (cameraEx == null || settings == null) {
            return RESULT_NO_CAMERA;
        }
        if (settings.getExposureMode() != CaptureSettings.EXP_MODE_APP) {
            return RESULT_OK;   // 跟随机内：什么都不做
        }

        StringBuilder notes = new StringBuilder();
        int failures = 0;

        // ---------- ① 光圈步进 ----------
        // 约定：apertureSteps > 0 = 收小光圈（F 值变大）= decrementAperture
        //       apertureSteps < 0 = 开大光圈（F 值变小）= incrementAperture
        int apSteps = settings.getApertureSteps();
        if (apSteps != 0) {
            boolean ok = true;
            for (int i = 0; i < Math.abs(apSteps) && ok; i++) {
                ok = stepAperture(cameraEx, apSteps > 0);
            }
            if (ok) {
                anyApplied = true;
            } else {
                failures++;
                notes.append("光圈调整未生效（请检查机内是否为 A/M 档）；");
            }
        }

        // ---------- ② 快门步进 ----------
        // 约定：shutterSteps > 0 = 更慢（长曝方向）= incrementShutterSpeed
        //       shutterSteps < 0 = 更快 = decrementShutterSpeed
        int shSteps = settings.getShutterSteps();
        if (shSteps != 0) {
            boolean ok = true;
            for (int i = 0; i < Math.abs(shSteps) && ok; i++) {
                ok = stepShutter(cameraEx, shSteps > 0);
            }
            if (ok) {
                anyApplied = true;
            } else {
                failures++;
                notes.append("快门调整未生效（请检查机内是否为 S/M 档）；");
            }
        }

        // ---------- ③ ISO 设置 ----------
        int iso = settings.getIsoValue();
        if (iso != CaptureSettings.ISO_AUTO) {
            boolean ok = setIso(cameraEx, iso);
            if (ok) {
                anyApplied = true;
            } else {
                failures++;
                notes.append("ISO 设置未生效；");
            }
        }

        // ---------- ④ AE 锁定（与“曝光锁定开关”联动） ----------
        // 锁定开启时在第一张前下发 AE_LOCK_ON，之后所有张复用首张测光。
        // 关闭时不发（固件每张自动测光）。
        if (settings.isLockAeAf()) {
            boolean ok = setAeLock(cameraEx, true);
            if (ok) {
                anyApplied = true;
            } else {
                // AE 锁失败不算致命：应用层仍有“首张后不再对焦”的兜底策略
                notes.append("AE 锁定命令未生效（改用首张后不再对焦策略）；");
            }
        }

        if (failures > 0) {
            lastNotice = notes.toString();
            AppLog.warn("曝光控制部分失败：" + lastNotice);
            return RESULT_PARTIAL;
        }
        AppLog.info("曝光控制已下发：光圈=" + apSteps + " 快门=" + shSteps + " ISO=" + iso);
        return RESULT_OK;
    }

    /* ============================ 单项命令（全部吞异常） ============================ */

    /**
     * 光圈步进一档。
     * @param narrower true = 收小光圈（F 值变大）→ decrementAperture
     */
    private boolean stepAperture(CameraEx cameraEx, boolean narrower) {
        try {
            if (narrower) {
                cameraEx.decrementAperture();
            } else {
                cameraEx.incrementAperture();
            }
            return true;
        } catch (Throwable t) {
            AppLog.warn("光圈步进失败：" + t.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 快门步进一档。
     * @param slower true = 更慢（长曝方向）→ incrementShutterSpeed
     */
    private boolean stepShutter(CameraEx cameraEx, boolean slower) {
        try {
            if (slower) {
                cameraEx.incrementShutterSpeed();
            } else {
                cameraEx.decrementShutterSpeed();
            }
            return true;
        } catch (Throwable t) {
            AppLog.warn("快门步进失败：" + t.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 设置 ISO。
     * 先取固件支持列表；请求值不在列表里时选最接近的一档（对数距离比较）。
     * 全程吞异常：固件拒绝（例如机内自动 ISO）不影响拍摄。
     */
    private boolean setIso(CameraEx cameraEx, int iso) {
        try {
            Parameters params = cameraEx.getNormalCamera().getParameters();
            CameraEx.ParametersModifier modifier = cameraEx.createParametersModifier(params);
            int target = iso;
            try {
                java.util.List supported = modifier.getSupportedISOSensitivities();
                if (supported != null && !supported.isEmpty() && !supported.contains(Integer.valueOf(iso))) {
                    target = nearestIso(supported, iso);
                    AppLog.info("请求 ISO " + iso + " 不在支持列表，改用最接近的 " + target);
                }
            } catch (Throwable ignored) {
                // 取不到支持列表就直接发原值，让固件自行取舍
            }
            modifier.setISOSensitivity(target);
            return true;
        } catch (Throwable t) {
            AppLog.warn("ISO 设置失败：" + t.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 在支持列表里找数值上最接近请求值的 ISO。
     * 列表元素可能是 Integer，也可能被固件包成其它类型，统一 toString 后 parse。
     */
    private static int nearestIso(java.util.List supported, int iso) {
        int best = iso;
        long bestDiff = Long.MAX_VALUE;
        for (Object o : supported) {
            try {
                int v = Integer.parseInt(String.valueOf(o).trim());
                long diff = Math.abs((long) v - iso);
                // 取对数距离更合理（ISO 是等比序列），但线性差在相邻档之间结果一致，
                // 这里用线性差 + 偏向较小值（先到先得）已经足够
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = v;
                }
            } catch (NumberFormatException ignored) {
                // 跳过无法解析的条目（例如 "AUTO"）
            }
        }
        return best;
    }

    /**
     * AE 测光锁定/解锁。
     * @param lock true = 锁定（AE_LOCK_ON）
     */
    private boolean setAeLock(CameraEx cameraEx, boolean lock) {
        try {
            Parameters params = cameraEx.getNormalCamera().getParameters();
            CameraEx.ParametersModifier modifier = cameraEx.createParametersModifier(params);
            modifier.setAutoExposureLock(lock ? CameraEx.ParametersModifier.AE_LOCK_ON
                    : CameraEx.ParametersModifier.AE_LOCK_OFF);
            return true;
        } catch (Throwable t) {
            AppLog.warn("AE 锁定失败：" + t.getClass().getSimpleName());
            return false;
        }
    }
}
