# 一键构建 PMCA_IntervalCapture 的 APK（Windows PowerShell 版）
#
# 流程：aapt 打包资源+清单 → javac 编译 → d8 生成 classes.dex → 组装 zip
#       → zipalign 对齐 → apksigner 签名
#
# 用法（在仓库根目录执行）：
#   powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1
#
# 前置要求见 docs/01-编译环境搭建.md：JDK 8+、Android build-tools、android.jar
param(
    [string]$BuildTools = "$PSScriptRoot\..\..\build-support\android-tools\android-15",
    [string]$AndroidJar = "$PSScriptRoot\..\..\build-support\libs\android-4.1.1.4.jar",
    [string]$SonyStubJar = "$PSScriptRoot\..\..\build-support\libs\sony-pmca-stubs.jar",
    [string]$KeyStore = "$PSScriptRoot\..\build-support\pmca-debug.keystore",
    [string]$OutDir = "$PSScriptRoot\..\build\outputs"
)

$ErrorActionPreference = 'Stop'
$root = Resolve-Path "$PSScriptRoot\.."
$srcDir = Join-Path $root 'app\src\main'
$work = Join-Path $root 'build\work'
$resDir = Join-Path $srcDir 'res'
$manifest = Join-Path $srcDir 'AndroidManifest.xml'

Write-Host "== 1/8 清理工作目录 ==" -ForegroundColor Cyan
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force -Path $work, (Join-Path $work 'gen'), (Join-Path $work 'classes'), (Join-Path $work 'dex'), $OutDir | Out-Null

Write-Host "== 2/8 生成 R.java 并打包资源 ==" -ForegroundColor Cyan
# aapt package：把 res/ 编译成 resources.arsc 并生成 R.java（平台资源 id 使用 android.jar 中的定义）
& "$BuildTools\aapt.exe" package -f -m -J (Join-Path $work 'gen') -M $manifest -S $resDir -I $AndroidJar --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt package 失败" }

# 生成未签名的 APK 骨架（含清单、资源、resources.arsc）
& "$BuildTools\aapt.exe" package -f -M $manifest -S $resDir -I $AndroidJar -F (Join-Path $work 'base.apk')
if ($LASTEXITCODE -ne 0) { throw "aapt 生成 base.apk 失败" }

Write-Host "== 3/8 编译 Java 源码 ==" -ForegroundColor Cyan
$javaFiles = Get-ChildItem -Recurse -Path @((Join-Path $srcDir 'java'), (Join-Path $work 'gen')) -Filter *.java |
    ForEach-Object { $_.FullName }
$cp = "$AndroidJar;$SonyStubJar"
& javac -nowarn -encoding UTF-8 -source 8 -target 8 -cp $cp -d (Join-Path $work 'classes') $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac 编译失败" }

Write-Host "== 4/8 使用 d8 生成 classes.dex ==" -ForegroundColor Cyan
$classFiles = Get-ChildItem -Recurse -Path (Join-Path $work 'classes') -Filter *.class | ForEach-Object { $_.FullName }
# --min-api 10：生成 Android 2.3（相机运行时）可用的 DEX；
# --lib：把 android.jar 声明为“平台类库”，d8 不会把它打进 dex
& cmd /c "`"$BuildTools\d8.bat`" --min-api 10 --release --lib `"$AndroidJar`" --lib `"$SonyStubJar`" --output `"$(Join-Path $work 'dex')`" $($classFiles -join ' ')"
if ($LASTEXITCODE -ne 0) { throw "d8 生成 DEX 失败" }

Write-Host "== 5/8 组装 APK ==" -ForegroundColor Cyan
$unaligned = Join-Path $work 'app-unaligned.apk'
Copy-Item (Join-Path $work 'base.apk') $unaligned -Force
# 把 classes.dex 加进 APK 根目录（Android 运行时从这里加载）
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unaligned, 'Update')
$dexEntry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
$dexStream = $dexEntry.Open()
$dexBytes = [System.IO.File]::ReadAllBytes((Join-Path $work 'dex\classes.dex'))
$dexStream.Write($dexBytes, 0, $dexBytes.Length)
$dexStream.Close()
$zip.Dispose()

Write-Host "== 6/8 zipalign 对齐 ==" -ForegroundColor Cyan
$aligned = Join-Path $work 'app-aligned.apk'
& "$BuildTools\zipalign.exe" -f -p 4 $unaligned $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败" }

Write-Host "== 7/8 签名 ==" -ForegroundColor Cyan
if (-not (Test-Path (Split-Path $KeyStore))) { New-Item -ItemType Directory -Force -Path (Split-Path $KeyStore) | Out-Null }
if (-not (Test-Path $KeyStore)) {
    Write-Host "   未找到密钥库，自动生成自签名密钥库（仅用于 PMCA-RE 刷入测试）"
        & keytool -genkeypair -v -keystore $KeyStore -storepass pmca123456 -keypass pmca123456 -alias pmca -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=PMCA IntervalCapture, OU=DIY, O=PMCA, L=Beijing, ST=Beijing, C=CN" 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "生成密钥库失败" }
}
$final = Join-Path $OutDir 'PMCA_IntervalCapture.apk'
# 必须使用 V1（JAR）签名：相机运行时是 Android 2.3，只认 V1 签名。
# 这里显式关闭 v2/v3/v3.1/v4，保证产物只带一个纯 JAR 签名块——最贴近相机固件的校验方式，
# 也便于用“解包看 META-INF/*.SF/*.RSA”这种最朴素的方式自查。
& cmd /c "`"$BuildTools\apksigner.bat`" sign --ks `"$KeyStore`" --ks-pass pass:pmca123456 --key-pass pass:pmca123456 --ks-key-alias pmca --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false --v4-signing-enabled false --min-sdk-version 10 --out `"$final`" `"$aligned`""
if ($LASTEXITCODE -ne 0) { throw "apksigner 签名失败" }

Write-Host "== 8/8 校验 ==" -ForegroundColor Cyan
& cmd /c "`"$BuildTools\apksigner.bat`" verify --verbose `"$final`""
& cmd /c "`"$BuildTools\aapt.exe`" dump badging `"$final`"" | Select-Object -First 6

Write-Host ""
Write-Host "构建完成：$final" -ForegroundColor Green
Write-Host "APK 大小：$([math]::Round((Get-Item $final).Length / 1KB, 1)) KB"
