package test;

import com.pmca.intervalcapture.CaptureSettings;
import com.pmca.intervalcapture.IntervalScheduler;

/**
 * LogicTest —— 纯逻辑层自测（不依赖任何 Android / 索尼 SDK，可在电脑 JVM 直接运行）
 * <p>
 * 覆盖：
 *   1. 间隔参数的上下限钳制、0.1 秒步进；
 *   2. 张数范围钳制与无限模式；
 *   3. 格式循环切换；
 *   4. 参数序列化/反序列化往返；
 *   5. 调度器：开始立即拍第一张、拍完按间隔排下一张；
 *   6. 暂停/继续不重置已拍张数；
 *   7. 固定张数拍满自动结束；
 *   8. 单张耗时超过间隔时判定“节拍溢出”，且不会无限等待；
 *   9. 完成回调只会触发一次；停止后不再触发拍摄。
 * <p>
 * 运行方式见 tools/run-logic-tests.ps1（javac + java 直接跑）。
 */
public final class LogicTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        testIntervalClamp();
        testIntervalStep();
        testShotCountClamp();
        testFormatCycle();
        testPersistRoundTrip();
        testSchedulerFirstShotImmediately();
        testSchedulerIntervalTiming();
        testPauseResumeKeepsCount();
        testFixedCountFinishes();
        testOverrunDetected();
        testInfiniteModeNoFinish();
        testHardwareWarning();
        testExposureSettings();

        System.out.println();
        System.out.println("==== 测试结果：通过 " + passed + " 项，失败 " + failed + " 项 ====");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /* ============================ 断言工具 ============================ */

    private static void check(String name, boolean cond, String detail) {
        if (cond) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name + "  -> " + detail);
        }
    }

    private static void eq(String name, long expect, long actual) {
        check(name, expect == actual, "期望 " + expect + "，实际 " + actual);
    }

    private static void eq(String name, String expect, String actual) {
        check(name, expect == null ? actual == null : expect.equals(actual),
                "期望 " + expect + "，实际 " + actual);
    }

    /* ============================ 参数模型测试 ============================ */

    private static void testIntervalClamp() {
        CaptureSettings s = new CaptureSettings();
        s.setIntervalDeciseconds(0);          // 低于下限 0.1 秒
        eq("间隔下限钳制为 0.1 秒", 1, s.getIntervalDeciseconds());
        s.setIntervalDeciseconds(999999);     // 超过上限 3600 秒
        eq("间隔上限钳制为 3600 秒", 36000, s.getIntervalDeciseconds());
        s.setIntervalDeciseconds(25);
        eq("间隔 2.5 秒可正常设置", 25, s.getIntervalDeciseconds());
        eq("间隔 2.5 秒格式化为文本", "2.5 秒", s.formatInterval());
        s.setIntervalDeciseconds(20);
        eq("整秒格式化不带小数点", "2 秒", s.formatInterval());
        s.setIntervalDeciseconds(5);
        eq("间隔 0.5 秒格式化为文本", "0.5 秒", s.formatInterval());
    }

    private static void testIntervalStep() {
        CaptureSettings s = new CaptureSettings();
        s.setIntervalDeciseconds(1);         // 0.1 秒（v1.1.0 起允许）
        s.adjust(CaptureSettings.FIELD_INTERVAL, 1);
        eq("0.1 秒向上一步为 0.2 秒", 2, s.getIntervalDeciseconds());
        s.adjust(CaptureSettings.FIELD_INTERVAL, -1);
        eq("0.2 秒向下一步回到 0.1 秒", 1, s.getIntervalDeciseconds());
        s.adjust(CaptureSettings.FIELD_INTERVAL, -1);
        eq("0.1 秒向下一步不会被压到下限以下", 1, s.getIntervalDeciseconds());
        s.setIntervalDeciseconds(10);        // 1.0 秒
        s.adjust(CaptureSettings.FIELD_INTERVAL, 1);
        eq("1.0 秒向上一步为 1.1 秒", 11, s.getIntervalDeciseconds());
    }

    private static void testShotCountClamp() {
        CaptureSettings s = new CaptureSettings();
        s.setShotCount(0);
        eq("张数下限钳制为 1", 1, s.getShotCount());
        s.setShotCount(99999);
        eq("张数上限钳制为 9999", 9999, s.getShotCount());
        s.setInfiniteCount(true);
        eq("无限模式有效总数为内部大值", CaptureSettings.SHOT_COUNT_INFINITE, s.getEffectiveShotCount());
        eq("无限模式显示文本", "无限（手动停止）", s.formatShotCount());
    }

    private static void testFormatCycle() {
        CaptureSettings s = new CaptureSettings();
        eq("默认格式为仅 JPG", CaptureSettings.FORMAT_JPG_ONLY, s.getImageFormat());
        s.adjust(CaptureSettings.FIELD_FORMAT, 1);
        eq("切换到仅 RAW", CaptureSettings.FORMAT_RAW_ONLY, s.getImageFormat());
        s.adjust(CaptureSettings.FIELD_FORMAT, 1);
        eq("切换到 JPG+RAW", CaptureSettings.FORMAT_JPG_PLUS_RAW, s.getImageFormat());
        s.adjust(CaptureSettings.FIELD_FORMAT, 1);
        eq("再切换回到仅 JPG（循环）", CaptureSettings.FORMAT_JPG_ONLY, s.getImageFormat());
        s.adjust(CaptureSettings.FIELD_FORMAT, -1);
        eq("反向切换回到 JPG+RAW（循环）", CaptureSettings.FORMAT_JPG_PLUS_RAW, s.getImageFormat());
        eq("格式显示文本", "JPG+RAW", s.formatImageFormat());
    }

    private static void testPersistRoundTrip() {
        CaptureSettings a = new CaptureSettings();
        a.setIntervalDeciseconds(123);   // 12.3 秒
        a.setShotCount(250);
        a.setImageFormat(CaptureSettings.FORMAT_JPG_PLUS_RAW);
        a.setLockAeAf(false);
        a.setPreviewEnabled(false);
        a.setInfiniteCount(true);
        String line = a.toPersistLine();
        CaptureSettings b = CaptureSettings.fromPersistLine(line);
        eq("序列化往返：间隔", a.getIntervalDeciseconds(), b.getIntervalDeciseconds());
        eq("序列化往返：张数", a.getShotCount(), b.getShotCount());
        eq("序列化往返：格式", a.getImageFormat(), b.getImageFormat());
        check("序列化往返：曝光锁定", a.isLockAeAf() == b.isLockAeAf(), "不一致");
        check("序列化往返：预览", a.isPreviewEnabled() == b.isPreviewEnabled(), "不一致");
        check("序列化往返：无限模式", a.isInfiniteCount() == b.isInfiniteCount(), "不一致");
        // 容错：坏数据不能抛异常
        CaptureSettings c = CaptureSettings.fromPersistLine("interval=abc;count=xx;format=99");
        check("损坏数据可容错解析", c != null, "解析返回 null");
    }

    /* ============================ 调度器测试 ============================ */

    /** 记录回调事件的测试监听器 */
    private static final class Recorder implements IntervalScheduler.Listener {
        int saved = 0;
        int failed = 0;
        int finished = 0;
        int lastState = -1;
        String lastFile = null;

        @Override
        public void onStateChanged(int newState) {
            lastState = newState;
        }

        @Override
        public void onShotSaved(int takenCount, String fileName) {
            saved++;
            lastFile = fileName;
        }

        @Override
        public void onShotFailed(String reason) {
            failed++;
        }

        @Override
        public void onFinished(int takenCount) {
            finished++;
        }
    }

    private static void testSchedulerFirstShotImmediately() {
        CaptureSettings s = new CaptureSettings();
        s.setIntervalDeciseconds(10);
        IntervalScheduler sch = new IntervalScheduler(s);
        Recorder rec = new Recorder();
        sch.setListener(rec);
        eq("初始状态为待机", IntervalScheduler.STATE_IDLE, sch.getState());
        check("开始前不应触发拍摄", !sch.shouldTriggerNow(), "却触发了");
        sch.start();
        eq("开始后状态为拍摄中", IntervalScheduler.STATE_RUNNING, sch.getState());
        check("开始后应立即允许拍第一张", sch.shouldTriggerNow(), "没有触发");
    }

    private static void testSchedulerIntervalTiming() throws Exception {
        CaptureSettings s = new CaptureSettings();
        s.setIntervalDeciseconds(10);      // 1.0 秒
        s.setShotCount(3);
        IntervalScheduler sch = new IntervalScheduler(s);
        Recorder rec = new Recorder();
        sch.setListener(rec);
        sch.start();

        // 第一张
        check("第 1 张到点", sch.shouldTriggerNow(), "未到点");
        sch.onShotStarted();
        check("拍摄中不应重复触发（写卡未完成）", !sch.shouldTriggerNow(), "重复触发");
        sch.onShotSaved("DSC00001.JPG");
        eq("已拍张数 1", 1, sch.getTakenCount());
        eq("文件名回调正确", "DSC00001.JPG", rec.lastFile);

        // 刚存完，倒计时应接近 1 秒（v1.1.1 节拍器语义：下一拍=上一拍计划时刻+间隔，
        // 首张处理耗时会吃掉一部分倒计时，所以下限放宽到 0）
        long cd = sch.getCountdownMillis();
        check("第 1 张存完后倒计时不超过 1 秒", cd > 0 && cd <= 1000, "实际 " + cd + " ms");
        check("刚存完时不应立即触发", !sch.shouldTriggerNow(), "立即触发了");

        // 等待间隔过去
        Thread.sleep(1100);
        check("等待 1.1 秒后应到点", sch.shouldTriggerNow(), "未到点");
        sch.onShotStarted();
        sch.onShotSaved("DSC00002.JPG");
        eq("已拍张数 2", 2, sch.getTakenCount());
        eq("剩余张数 1", 1, sch.getRemainingCount());
    }

    private static void testPauseResumeKeepsCount() throws Exception {
        CaptureSettings s = new CaptureSettings();
        s.setIntervalDeciseconds(10);
        s.setShotCount(10);
        IntervalScheduler sch = new IntervalScheduler(s);
        Recorder rec = new Recorder();
        sch.setListener(rec);
        sch.start();
        sch.onShotStarted();
        sch.onShotSaved("DSC00001.JPG");
        eq("暂停前已拍 1 张", 1, sch.getTakenCount());

        check("暂停成功", sch.pause(), "暂停失败");
        eq("暂停后状态为已暂停", IntervalScheduler.STATE_PAUSED, sch.getState());
        check("暂停期间不应触发拍摄", !sch.shouldTriggerNow(), "暂停期间触发了");
        eq("暂停期间倒计时返回 -1", -1, sch.getCountdownMillis());

        check("继续成功", sch.resume(), "继续失败");
        eq("继续后状态为拍摄中", IntervalScheduler.STATE_RUNNING, sch.getState());
        eq("继续后不重置已拍张数", 1, sch.getTakenCount());
        check("继续后不立即触发（重新计时）", !sch.shouldTriggerNow(), "立即触发了");

        Thread.sleep(1050);
        check("继续后经过一个间隔应到点", sch.shouldTriggerNow(), "未到点");
        sch.onShotStarted();
        sch.onShotSaved("DSC00002.JPG");
        eq("继续后累计 2 张", 2, sch.getTakenCount());
    }

    private static void testFixedCountFinishes() {
        CaptureSettings s = new CaptureSettings();
        s.setIntervalDeciseconds(10);
        s.setShotCount(2);
        IntervalScheduler sch = new IntervalScheduler(s);
        Recorder rec = new Recorder();
        sch.setListener(rec);
        sch.start();

        sch.onShotStarted();
        sch.onShotSaved("DSC00001.JPG");
        eq("拍满前仍在拍摄中", IntervalScheduler.STATE_RUNNING, sch.getState());

        sch.onShotStarted();
        sch.onShotSaved("DSC00002.JPG");
        eq("拍满 2 张后状态为已结束", IntervalScheduler.STATE_FINISHED, sch.getState());
        eq("完成回调触发 1 次", 1, rec.finished);
        eq("已拍张数 2", 2, sch.getTakenCount());
        eq("剩余张数 0", 0, sch.getRemainingCount());
        check("结束后不再触发拍摄", !sch.shouldTriggerNow(), "结束后仍触发");
    }

    private static void testOverrunDetected() throws Exception {
        CaptureSettings s = new CaptureSettings();
        s.setIntervalDeciseconds(10);   // 1.0 秒
        s.setShotCount(3);
        IntervalScheduler sch = new IntervalScheduler(s);
        sch.setListener(new Recorder());
        sch.start();
        sch.onShotStarted();
        // 模拟单张耗时 1.2 秒 > 间隔 1.0 秒
        Thread.sleep(1200);
        sch.onShotSaved("DSC00001.JPG");
        eq("单张耗时超过间隔时记录节拍溢出", 1, sch.getOverrunCount());
        check("溢出后应立即允许拍下一张", sch.shouldTriggerNow(), "未立即触发");
    }

    private static void testInfiniteModeNoFinish() {
        CaptureSettings s = new CaptureSettings();
        s.setIntervalDeciseconds(10);
        s.setInfiniteCount(true);
        IntervalScheduler sch = new IntervalScheduler(s);
        Recorder rec = new Recorder();
        sch.setListener(rec);
        sch.start();
        for (int i = 1; i <= 50; i++) {
            sch.onShotStarted();
            sch.onShotSaved("DSC" + i + ".JPG");
        }
        eq("无限模式拍 50 张仍在拍摄中", IntervalScheduler.STATE_RUNNING, sch.getState());
        eq("无限模式没有结束回调", 0, rec.finished);
        eq("无限模式剩余张数返回 -1", -1, sch.getRemainingCount());
        eq("无限模式进度返回 -1", -1, sch.getProgressPercent());
    }

    private static void testHardwareWarning() {
        CaptureSettings s = new CaptureSettings();
        s.setIntervalDeciseconds(10);
        s.setPreviewEnabled(true);
        check("1.0 秒 + 预览应给出硬件告警", s.getHardwareWarning() != null, "没有告警");
        s.setIntervalDeciseconds(50);    // 5 秒
        check("5 秒间隔不应有硬件告警", s.getHardwareWarning() == null, "出现多余告警");
        s.setIntervalDeciseconds(12);    // 1.2 秒
        s.setPreviewEnabled(false);
        s.setImageFormat(CaptureSettings.FORMAT_JPG_PLUS_RAW);
        check("1.2 秒 + RAW 应给出硬件告警", s.getHardwareWarning() != null, "没有告警");
    }

    /* ==================== v1.1.0：曝光控制参数测试 ==================== */

    private static void testExposureSettings() {
        CaptureSettings s = new CaptureSettings();
        // 默认值：跟随机内 + 各项不变 + ISO 自动
        eq("默认曝光模式为跟随机内", CaptureSettings.EXP_MODE_CAMERA, s.getExposureMode());
        eq("默认光圈步进为 0", 0, s.getApertureSteps());
        eq("默认快门步进为 0", 0, s.getShutterSteps());
        eq("默认 ISO 为自动", CaptureSettings.ISO_AUTO, s.getIsoValue());
        eq("默认参数项数为 10", 10, CaptureSettings.FIELD_COUNT);

        // 曝光模式切换（开关型：左右键都切换）
        s.adjust(CaptureSettings.FIELD_EXPOSURE_MODE, 1);
        eq("曝光模式切换为应用接管", CaptureSettings.EXP_MODE_APP, s.getExposureMode());
        s.adjust(CaptureSettings.FIELD_EXPOSURE_MODE, -1);
        eq("曝光模式切回跟随机内", CaptureSettings.EXP_MODE_CAMERA, s.getExposureMode());

        // 跟随机内模式下，光圈/快门/ISO 三项不允许调整
        s.adjust(CaptureSettings.FIELD_APERTURE, 1);
        eq("跟随机内时光圈不可调", 0, s.getApertureSteps());

        // 应用接管模式下调整光圈（+1 = 收小一档）
        s.adjust(CaptureSettings.FIELD_EXPOSURE_MODE, 1);
        s.adjust(CaptureSettings.FIELD_APERTURE, 1);
        eq("光圈 +1 档", 1, s.getApertureSteps());
        s.adjust(CaptureSettings.FIELD_APERTURE, 1);
        eq("光圈 +2 档", 2, s.getApertureSteps());
        s.adjust(CaptureSettings.FIELD_APERTURE, -1);
        eq("光圈回到 +1 档", 1, s.getApertureSteps());

        // 步进上下限钳制（±10）
        for (int i = 0; i < 20; i++) {
            s.adjust(CaptureSettings.FIELD_APERTURE, 1);
        }
        eq("光圈步进钳制在 +10", 10, s.getApertureSteps());
        for (int i = 0; i < 30; i++) {
            s.adjust(CaptureSettings.FIELD_APERTURE, -1);
        }
        eq("光圈步进钳制在 -10", -10, s.getApertureSteps());

        // 快门步进
        s.adjust(CaptureSettings.FIELD_SHUTTER, 1);
        eq("快门 +1 档（更慢）", 1, s.getShutterSteps());
        s.adjust(CaptureSettings.FIELD_SHUTTER, -3);
        eq("快门 -2 档（更快）", -2, s.getShutterSteps());

        // ISO 循环：AUTO(0) → 100 → 200 → ... → 25600 → 回到 AUTO
        s.adjust(CaptureSettings.FIELD_ISO, 1);
        eq("ISO 从 AUTO 到 100", 100, s.getIsoValue());
        s.adjust(CaptureSettings.FIELD_ISO, -1);
        eq("ISO 从 100 回到 AUTO", CaptureSettings.ISO_AUTO, s.getIsoValue());
        s.adjust(CaptureSettings.FIELD_ISO, -1);
        eq("ISO 从 AUTO 向下循环到 25600", 25600, s.getIsoValue());
        s.adjust(CaptureSettings.FIELD_ISO, 1);
        eq("ISO 从 25600 回到 AUTO", CaptureSettings.ISO_AUTO, s.getIsoValue());

        // 格式化文本
        s.setExposureMode(CaptureSettings.EXP_MODE_APP);
        s.setApertureSteps(2);
        s.setShutterSteps(-1);
        s.setIsoValue(400);
        check("应用接管+光圈+2 显示包含[收小]", s.formatAperture().contains("收小"), s.formatAperture());
        check("应用接管+快门-1 显示包含[更快]", s.formatShutter().contains("更快"), s.formatShutter());
        eq("ISO 400 显示文本", "ISO 400", s.formatIso());
        s.setExposureMode(CaptureSettings.EXP_MODE_CAMERA);
        eq("跟随机内时光圈显示跟随机内", "（跟随机内）", s.formatAperture());

        // 序列化往返
        s.setExposureMode(CaptureSettings.EXP_MODE_APP);
        s.setApertureSteps(-3);
        s.setShutterSteps(4);
        s.setIsoValue(1600);
        CaptureSettings r = CaptureSettings.fromPersistLine(s.toPersistLine());
        eq("序列化往返 曝光模式", CaptureSettings.EXP_MODE_APP, r.getExposureMode());
        eq("序列化往返 光圈", -3, r.getApertureSteps());
        eq("序列化往返 快门", 4, r.getShutterSteps());
        eq("序列化往返 ISO", 1600, r.getIsoValue());

        // 旧版本持久化行（没有曝光四键）兼容
        CaptureSettings old = CaptureSettings.fromPersistLine(
                "interval=25;infinite=false;count=100;format=1;lock=true;preview=false");
        eq("旧版本行解析 曝光模式默认跟随机内", CaptureSettings.EXP_MODE_CAMERA, old.getExposureMode());
        eq("旧版本行解析 光圈默认 0", 0, old.getApertureSteps());
        eq("旧版本行解析 间隔仍正确", 25, old.getIntervalDeciseconds());
    }
}
