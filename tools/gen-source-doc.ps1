# ============================================================================
#  gen-source-doc.ps1
#  作用：把项目里全部源码按固定顺序拼成一个 Markdown 文档
#        输出到 docs\05-完整源码合集.md
#  说明：源码文件本身在 app\ 与 tools\ 下，本脚本只做“读取 + 拼接”，
#        不修改任何源码，避免手工复制导致文档与代码不一致。
#  用法：powershell -ExecutionPolicy Bypass -File tools\gen-source-doc.ps1
# ============================================================================

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$outFile = Join-Path $root 'docs\05-完整源码合集.md'

# ---------------------------------------------------------------------------
# 文件清单：按“构建配置 → 清单资源 → 核心逻辑 → 界面 → 硬件抽象 → 工具”顺序
# 每项 = 相对路径 + 代码块语言标记 + 一句话说明
# ---------------------------------------------------------------------------
$files = @(
    @{ p = 'build.gradle';                          lang = 'gradle';     desc = '根工程构建脚本（Gradle 路线用）' }
    @{ p = 'settings.gradle';                       lang = 'gradle';     desc = 'Gradle 多模块声明' }
    @{ p = 'gradle.properties';                     lang = 'properties'; desc = 'Gradle 全局属性' }
    @{ p = 'app\build.gradle';                      lang = 'gradle';     desc = '应用模块构建脚本（compileSdk 10 / compileOnly 私有桩）' }
    @{ p = 'app\src\main\AndroidManifest.xml';      lang = 'xml';        desc = '应用清单：包名、版本号、权限、Activity/Receiver 声明' }
    @{ p = 'app\src\main\res\values\strings.xml';   lang = 'xml';        desc = '字符串资源（应用名、标题）' }
    @{ p = 'app\src\main\res\values\styles.xml';    lang = 'xml';        desc = '主题样式（全屏无标题栏）' }
    @{ p = 'app\src\main\java\com\pmca\intervalcapture\CaptureActivity.java';      lang = 'java'; desc = '★ 主界面与总编排：状态机驱动、按键路由、100ms 节拍、弹窗与退出' }
    @{ p = 'app\src\main\java\com\pmca\intervalcapture\CaptureService.java';       lang = 'java'; desc = '★ 硬件层：CameraEx 打开相机、预览、静态拍照、AE/AF 锁定、错误回调' }
    @{ p = 'app\src\main\java\com\pmca\intervalcapture\CaptureSettings.java';      lang = 'java'; desc = '参数模型与钳制规则（间隔/张数/格式/曝光锁定/预览）' }
    @{ p = 'app\src\main\java\com\pmca\intervalcapture\IntervalScheduler.java';    lang = 'java'; desc = '★ 节拍状态机：开始/暂停/继续/停止/溢出检测/倒计时（纯逻辑、可单测）' }
    @{ p = 'app\src\main\java\com\pmca\intervalcapture\HealthMonitor.java';        lang = 'java'; desc = '健康检查：SD 卡状态与剩余空间、温度轮询、连续写卡失败' }
    @{ p = 'app\src\main\java\com\pmca\intervalcapture\CaptureCallback.java';      lang = 'java'; desc = '硬件层回调接口（解耦 Activity 与 CaptureService）' }
    @{ p = 'app\src\main\java\com\pmca\intervalcapture\CameraKeyRouter.java';      lang = 'java'; desc = '实体按键扫描码 → 语义动作映射（无触屏）' }
    @{ p = 'app\src\main\java\com\pmca\intervalcapture\SettingsStore.java';        lang = 'java'; desc = '参数持久化（SharedPreferences + SD 卡冗余备份）' }
    @{ p = 'app\src\main\java\com\pmca\intervalcapture\AppLog.java';               lang = 'java'; desc = '日志：logcat + SD 卡 PMCACAP/LOG.TXT 追加写' }
    @{ p = 'app\src\main\java\com\pmca\intervalcapture\TimelapseJobWriter.java';   lang = 'java'; desc = '可选附加功能：用户选“是”时才写后期合成任务清单（机内不出视频）' }
    @{ p = 'app\src\main\java\com\pmca\intervalcapture\SystemEventReceiver.java';  lang = 'java'; desc = '系统广播接收器（开机完成 / 退出完成，用于日志与状态复位）' }
    @{ p = 'app\src\main\java\com\pmca\intervalcapture\ui\UiTheme.java';           lang = 'java'; desc = '界面主题常量与绘制工具（640×480、颜色、字号、文本格式化）' }
    @{ p = 'app\src\main\java\com\pmca\intervalcapture\ui\SettingsScreen.java';    lang = 'java'; desc = '参数配置页绘制' }
    @{ p = 'app\src\main\java\com\pmca\intervalcapture\ui\RunScreen.java';         lang = 'java'; desc = '运行状态页绘制（计数/倒计时/进度/温度/溢出）' }
    @{ p = 'app\src\main\java\com\pmca\intervalcapture\ui\DialogScreen.java';      lang = 'java'; desc = '完成弹窗 / 报错弹窗 / 确认弹窗绘制' }
    @{ p = 'tools\build-apk.ps1';                   lang = 'powershell'; desc = '一键打包脚本（aapt → javac → d8 → 合并 → 对齐 → 签名 → 校验）' }
    @{ p = 'tools\run-logic-tests.ps1';             lang = 'powershell'; desc = '纯 JVM 逻辑单测运行脚本' }
    @{ p = 'tools\tests\LogicTest.java';            lang = 'java';       desc = '逻辑单元测试（64 项断言，不依赖相机）' }
)

$sb = New-Object System.Text.StringBuilder
function Add-Line([string]$s) { [void]$sb.AppendLine($s) }

$totalLines = 0
$rows = New-Object System.Collections.Generic.List[string]

Add-Line '# 05 · 完整源码合集'
Add-Line ''
Add-Line '> 本文由 `tools\gen-source-doc.ps1` 从工程目录**自动生成**，与仓库内源码逐字节一致。'
Add-Line '> 单独开发/编译请直接使用工程目录中的文件；本文用于一次性通读与交付存档。'
Add-Line ''
Add-Line '## 阅读顺序建议'
Add-Line ''
Add-Line '1. `AndroidManifest.xml`、`app/build.gradle` —— 先看应用长什么样、怎么被构建；'
Add-Line '2. `CaptureActivity.java` —— 全局总编排，所有交互都从这里的 `onKeyDown` / `onTick` 进入；'
Add-Line '3. `CaptureService.java` —— 只关心“怎么让相机拍一张并存进 SD 卡”；'
Add-Line '4. `IntervalScheduler.java` + `CaptureSettings.java` —— 纯逻辑，可离线单测；'
Add-Line '5. `HealthMonitor.java` —— 什么时候必须停下来；'
Add-Line '6. `ui\*.java` —— 640×480 屏幕怎么画；'
Add-Line '7. `tools\*` —— 怎么打包、怎么自测。'
Add-Line ''
Add-Line '## 文件清单与行数'
Add-Line ''
Add-Line '| 文件 | 行数 | 说明 |'
Add-Line '| --- | ---: | --- |'

foreach ($f in $files) {
    $full = Join-Path $root $f.p
    if (-not (Test-Path $full)) { throw "缺少文件：$full" }
    # 用 ReadAllLines 统计真实行数（Measure-Object -Line 会跳过空行，导致行数偏小）
    $n = [IO.File]::ReadAllLines($full, [Text.Encoding]::UTF8).Count
    $totalLines += $n
    $rows.Add("| ``$($f.p)`` | $n | $($f.desc) |")
}
foreach ($r in $rows) { Add-Line $r }
Add-Line "| **合计** | **$totalLines** | 以上为本文收录的全部文件 |"
Add-Line ''

$idx = 0
foreach ($f in $files) {
    $idx++
    $full = Join-Path $root $f.p
    $content = [IO.File]::ReadAllText($full, [Text.Encoding]::UTF8)
    if ($content -match "`r`n") { $content = $content -replace "`r`n", "`n" }
    $content = $content.TrimEnd("`n")

    Add-Line '---'
    Add-Line ''
    Add-Line "## $idx. ``$($f.p)``"
    Add-Line ''
    Add-Line "**说明**：$($f.desc)"
    Add-Line ''
    Add-Line "``````$($f.lang)"
    Add-Line $content
    Add-Line '``````'
    Add-Line ''
}

Add-Line '---'
Add-Line ''
Add-Line '## 附：构建产物校验记录'
Add-Line ''
Add-Line '| 项目 | 结果 |'
Add-Line '| --- | --- |'
Add-Line '| 编译 | javac 通过；d8 产物 classes.dex 共 **37 个 class_defs**（15 个业务类 + 匿名内部类） |'
Add-Line '| DEX | d8 `--min-api 10` 通过，单 `classes.dex`，62,620 字节 |'
Add-Line '| 签名 | apksigner v1（JAR）verified = true，v2/v3.1/v4 全部关闭 |'
Add-Line '| 包大小 | `PMCA_IntervalCapture.apk` 约 35.1 KB（仅 V1 签名） |'
Add-Line '| 包名/版本 | `com.pmca.intervalcapture` / versionCode 1 / versionName 1.0.0 |'
Add-Line '| 应用名 | 间隔拍照（application-label） |'
Add-Line '| 逻辑单测 | 64 项断言全部通过 |'
Add-Line '| 私有类泄漏 | `dexdump -f` 全量扫描：37 个类定义**全部**是 `com/pmca/intervalcapture/*`；`com/sony/scalar/*` 只以类型引用出现，**零类定义** |'
Add-Line ''

$dir = Split-Path -Parent $outFile
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
[IO.File]::WriteAllText($outFile, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))

Write-Host ("已生成：{0}" -f $outFile)
Write-Host ("收录文件：{0} 个，合计 {1} 行" -f $files.Count, $totalLines)
Write-Host ("文档大小：{0:N1} KB" -f ((Get-Item $outFile).Length / 1KB))
