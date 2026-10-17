# 运行纯逻辑层单元测试（不需要真机、不需要 Android SDK）
#
# 只编译 CaptureSettings.java / IntervalScheduler.java 两个“零依赖”类，
# 以及 tools/tests/LogicTest.java，然后在电脑 JVM 上直接跑。
#
# 用法（在仓库根目录执行）：
#   powershell -ExecutionPolicy Bypass -File tools\run-logic-tests.ps1
$ErrorActionPreference = 'Stop'
$root = Resolve-Path "$PSScriptRoot\.."
$javaSrc = Join-Path $root 'app\src\main\java'
$testSrc = Join-Path $PSScriptRoot 'tests'
$out = Join-Path $root 'build\test-classes'

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path $out | Out-Null

$files = @(
    (Join-Path $javaSrc 'com\pmca\intervalcapture\CaptureSettings.java'),
    (Join-Path $javaSrc 'com\pmca\intervalcapture\IntervalScheduler.java'),
    (Join-Path $testSrc 'LogicTest.java')
)

Write-Host "== 编译纯逻辑类与测试 ==" -ForegroundColor Cyan
& javac -encoding UTF-8 -d $out $files
if ($LASTEXITCODE -ne 0) { throw "编译失败" }

Write-Host "== 运行测试 ==" -ForegroundColor Cyan
& java -cp $out test.LogicTest
exit $LASTEXITCODE
