# 02 · 打包与 PMCA-RE 刷入 A6000 完整教程

本文分三部分：

1. **打包**：把源码变成可安装的 `PMCA_IntervalCapture.apk`（含签名校验）；
2. **刷入**：用 PMCA-RE 通过 USB 把 APK 装进 A6000 相机；
3. **验证与排障**：装完怎么确认成功、出问题怎么退回。

> 强烈建议先读 [03-风险提示.md](03-风险提示.md)。刷写行为有风险，请自行承担后果。

---

## 第一部分 · 打包

### 1.1 一键打包（推荐）

```powershell
cd D:\sony\PMCA_IntervalCapture
$env:JAVA_TOOL_OPTIONS=''      # 避免 "Picked up JAVA_TOOL_OPTIONS" 噪声
powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1
```

产物：`build\outputs\PMCA_IntervalCapture.apk`

### 1.2 脚本到底做了什么（8 步）

| 步骤 | 命令要点 | 作用 |
| --- | --- | --- |
| ① 生成 R.java | `aapt package -f -m -J gen -M AndroidManifest.xml -S res -I android.jar` | 把 `res/` 里的字符串/样式/图标编译成资源 ID |
| ② 打包资源 | `aapt package -f -F base.apk -M AndroidManifest.xml -S res -I android.jar` | 生成只含 `AndroidManifest.xml` 与 `resources.arsc` 的基础 APK |
| ③ 编译 Java | `javac -encoding UTF-8 -source 8 -target 8 -bootclasspath android.jar -cp sony-pmca-stubs.jar ...` | 编译全部 `.java`；Sony 私有 API 只参与编译 |
| ④ 转 DEX | `d8 --min-api 10 --release --lib android.jar --lib sony-pmca-stubs.jar --output dex <all .class>` | 转成 API 10 兼容的 `classes.dex` |
| ⑤ 合并 | 用 .NET `ZipFile` 把 `classes.dex` 写进 `base.apk` | APK 里同时有资源和代码 |
| ⑥ 对齐 | `zipalign -f -p 4 in.apk out.apk` | 4 字节对齐，减少相机端 mmap 失败 |
| ⑦ 签名 | `apksigner sign --v1-signing-enabled true --v2-signing-enabled false --min-sdk-version 10 ...` | **必须启用 v1（JAR）签名**：Android 2.3.7 不认 v2/v3 |
| ⑧ 校验 | `apksigner verify` + `aapt dump badging` | 打印包名、版本、标签、SDK 版本 |

### 1.3 手工分步命令（脚本坏了时的兜底）

```powershell
$env:JAVA_TOOL_OPTIONS=''
$SDK = 'D:\sony\build-support\android-tools\android-15'
$LIB = 'D:\sony\build-support\libs'
$OUT = 'D:\sony\PMCA_IntervalCapture\build\manual'
New-Item -ItemType Directory -Force -Path "$OUT\gen","$OUT\classes","$OUT\dex" | Out-Null

# ① 生成 R.java
& "$SDK\aapt.exe" package -f -m -J "$OUT\gen" `
  -M app\src\main\AndroidManifest.xml -S app\src\main\res -I "$LIB\android-4.1.1.4.jar"

# ② 资源包
& "$SDK\aapt.exe" package -f -F "$OUT\base.apk" `
  -M app\src\main\AndroidManifest.xml -S app\src\main\res -I "$LIB\android-4.1.1.4.jar"

# ③ 编译（把所有 .java 路径列成文件传给 javac，避免命令行过长）
Get-ChildItem -Recurse -Filter *.java app\src\main\java, "$OUT\gen" | ForEach-Object { $_.FullName } |
  Set-Content -Encoding ASCII "$OUT\sources.txt"
& javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$LIB\android-4.1.1.4.jar" `
  -cp "$LIB\sony-pmca-stubs.jar" -d "$OUT\classes" "@$OUT\sources.txt"

# ④ 转 DEX
Get-ChildItem -Recurse -Filter *.class "$OUT\classes" | ForEach-Object { $_.FullName } |
  Set-Content -Encoding ASCII "$OUT\classes.txt"
& "$SDK\d8.bat" --min-api 10 --release `
  --lib "$LIB\android-4.1.1.4.jar" --lib "$LIB\sony-pmca-stubs.jar" `
  --output "$OUT\dex" "@$OUT\classes.txt" | Out-Null

# ⑤ 合并 classes.dex
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$OUT\base.apk", 'Update')
[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$OUT\dex\classes.dex", 'classes.dex') | Out-Null
$zip.Dispose()

# ⑥ 对齐 ⑦ 签名
& "$SDK\zipalign.exe" -f -p 4 "$OUT\base.apk" "$OUT\aligned.apk"
& "$SDK\apksigner.bat" sign --ks build-support\pmca-debug.keystore `
  --ks-pass pass:pmca123456 --key-pass pass:pmca123456 --ks-key-alias pmca `
  --v1-signing-enabled true --v2-signing-enabled false --min-sdk-version 10 `
  --out "$OUT\PMCA_IntervalCapture.apk" "$OUT\aligned.apk"

# ⑧ 校验
& "$SDK\apksigner.bat" verify --print-certs "$OUT\PMCA_IntervalCapture.apk"
& "$SDK\aapt.exe" dump badging "$OUT\PMCA_IntervalCapture.apk" |
  Select-String "package:|sdkVersion|application-label|application-icon"
```

### 1.4 打包产物必须满足的 5 个条件

| 检查项 | 期望值 | 不满足的后果 |
| --- | --- | --- |
| `minSdkVersion` | `10` | >10 时相机拒绝安装或运行崩溃 |
| `versionCode` | 存在且为整数（当前 `1`） | PMCA-RE 解析失败、安装报错 |
| 签名方案 | v1 = true（JAR signing），且 v2/v3/v4 全部为 false | Android 2.3.7 只认 JAR 签名；v2+ 签名块对老系统无意义，纯 v1 最稳 |
| 私有类是否误打包 | `dexdump -f` 的 `Class descriptor` 里**没有** `com/sony/...` | 与固件同名类冲突 → 启动即崩 |
| 语言级别 | class 文件 major version ≤ 52（Java 8） | d8 或相机 Dalvik 无法解析 |

自查命令（用 `dexdump` 看**类定义**，而不是在 dex 里搜字符串）：

```powershell
# 直接查看 dex 里的全部类定义，确认只有本应用自己的类
& "D:\sony\build-support\android-tools\android-15\dexdump.exe" -f build\work\dex\classes.dex |
  Select-String "Class descriptor" |
  ForEach-Object { $_.Line.Trim() } |
  Sort-Object -Unique

# 期望：所有行都是 Lcom/pmca/intervalcapture/...;
# 如果出现任何 Lcom/sony/...; 的 Class descriptor —— 私有桩被误打包，必须检查 compileOnly
```

> **不要用“在 dex 里搜 `com/sony/scalar` 字符串”来判断。** 本项目 dex 中**必然**存在
> `Lcom/sony/scalar/hardware/CameraEx;` 这类**类型引用**（调用处的方法签名），这是正常的；
> 判断标准是 `Class descriptor`（类定义）里不能有 sony 类。
> 实测本项目：37 个 class_defs 全部为 `com/pmca/intervalcapture/*`，sony 类零定义。

### 1.5 换签名 / 换包名

- **换签名**：重新 `keytool -genkeypair` 生成 keystore，改 `tools\build-apk.ps1` 里的 `$KS/$KS_PASS/$KS_ALIAS`。
- **换包名**：改 `AndroidManifest.xml` 的 `package="..."` 与 `app/build.gradle` 的 `applicationId`，
  两处必须一致；Java 源码包路径可以不改（`R` 类由 aapt 按 manifest 包名生成）。
- **升版本**：改 `AndroidManifest.xml` 的 `android:versionCode`（每次发布 +1）与 `versionName`。

---

## 第二部分 · 用 PMCA-RE 刷入 A6000

### 2.1 需要准备什么

| 项目 | 要求 |
| --- | --- |
| 相机 | ILCE-6000（A6000），支持 PlayMemories Camera Apps |
| 电量 | **≥ 80%**，刷入全程不要关机、不要拔线 |
| USB 线 | 能传数据的 micro-USB 线（只能充电的线不行，这是最常见的失败原因） |
| 电脑 | Windows 10/11（PMCA-RE 使用系统自带的 MTP/大容量存储驱动，通常无需装驱动） |
| 工具 | [Sony-PMCA-RE releases](https://github.com/ma1co/Sony-PMCA-RE/releases) 里的 `pmca-gui.exe`（图形界面）或 `pmca-console`（命令行） |
| 安装包 | 本文第一部分产出的 `PMCA_IntervalCapture.apk` |

### 2.2 相机端设置（关键，90% 的失败都出在这里）

1. 相机**开机**；
2. 按 `MENU` → 找到 **设置（Setup）** → **USB 连接**（USB Connection）；
3. 选择 **MTP**。
   > ⚠️ 不要选“海量存储器 / Mass Storage”。安装应用必须用 **MTP** 模式。
4. 用 micro-USB 线把相机连到电脑；
5. 相机屏幕若提示“已连接 USB”，说明握手正常；Windows 资源管理器里能看到相机设备。

### 2.3 方式一：图形界面 pmca-gui（最简单）

1. 双击 `pmca-gui.exe`（免安装，解压即用）；
2. 程序顶部应显示相机型号（如 `ILCE-6000`）与固件版本 —— 看不到就回到 2.2 检查 MTP 与线材；
3. 切到 **Install app**（安装应用）标签页；
4. 选择 **Install app from file**（从文件安装），点选 `build\outputs\PMCA_IntervalCapture.apk`；
5. 点 **Install selected app**；
6. 期间相机屏幕会切换到“应用安装模式”，可能出现进度提示，**不要碰相机、不要拔线**；
7. 等程序显示 `Successfully` / `Task completed successfully`（约 1 分钟）；
8. 拔线 → **关机再开机**（让相机重新扫描应用列表）。

### 2.4 方式二：命令行 pmca-console（便于脚本化）

```powershell
# 0) 把 pmca-console.exe 所在目录加入 PATH，或直接写全路径
cd D:\sony\PMCA_IntervalCapture

# 1) 先确认相机能被识别（会打印型号/固件/序列号）
pmca-console info

# 2) 安装 APK
#    install 命令会自动把相机切到 "app install mode"，无需手动操作相机
pmca-console install -f build\outputs\PMCA_IntervalCapture.apk

# 3) 需要保存安装回执（排障用）
pmca-console install -f build\outputs\PMCA_IntervalCapture.apk -o install-result.json
```

成功输出形如：

```
Analyzing apk
Package: com.pmca.intervalcapture
Version: 1.0.0
Starting task
Starting communication
...
Task completed successfully
```

`pmca-console install` 的常用参数：

| 参数 | 含义 |
| --- | --- |
| `-f <apk>` | 从本地 APK 文件安装（本项目用这个） |
| `-a <package>` | 从索尼应用列表按包名安装（需要联网） |
| `-i` | 交互式从应用列表挑选 |
| `-o <file>` | 把安装结果写成 JSON |
| `-d native\|libusb\|qemu` | 指定 USB 驱动后端（Windows 默认 `native` 即可） |

### 2.5 方式三：先转 SPK 再安装（备用 / 离线分发）

```powershell
# APK → SPK（索尼的应用安装包格式）
pmca-console apk2spk build\outputs\PMCA_IntervalCapture.apk build\outputs\PMCA_IntervalCapture.spk

# 也可以反解验证
pmca-console spk2apk build\outputs\PMCA_IntervalCapture.spk build\outputs\roundtrip.apk
```

SPK 的优点是可以作为单个文件离线分发/备份；缺点是仍要经 PMCA-RE 安装，不改变整体流程。
**注意：SPK 不是相机固件，不能用相机固件升级流程刷写。**

### 2.6 安装后在相机上启动

1. 相机开机；
2. `MENU` → **应用程序（Application）** → **应用程序列表（Application List）**；
3. 找到 **“间隔拍照”**（`application-label`，图标为蓝框相机），按确认键启动；
4. 首次启动会申请相机资源，屏幕短暂黑屏属正常；
5. 看到 640×480 的参数页即成功。

### 2.7 安装完成后的自检（建议立刻做一次）

用最短配置跑一遍，确认端到端可用：

| 项目 | 建议值 | 检查点 |
| --- | --- | --- |
| 间隔 | 3.0 秒 | 倒计时是否按 0.1 秒递减 |
| 张数 | 3 张 | 拍满 3 张后是否弹“全部照片已保存至 SD 卡” |
| 格式 | 仅 JPG | 相机 SD 卡是否出现 3 张新 JPG |
| 曝光锁定 | 开 | 3 张画面亮度是否一致、不闪烁 |
| 预览 | 开 | 屏幕是否显示实时画面 |
| 完成弹窗 | 默认“否” | 直接按确认 → 不能生成任何视频文件 |

然后检查 SD 卡：

```
/DCIM/100MSDCF/DSC0xxxx.JPG     ← 相机原生照片（应用不参与写入）
/PMCACAP/LOG.TXT                ← 运行日志（便于排障）
/PMCACAP/SETTINGS.TXT           ← 参数备份
/PMCACAP/TIMELAPSE_JOB.TXT      ← 只有用户选“是”生成视频才会出现
```

### 2.8 卸载 / 回滚 / 重装

1. **相机内卸载**：`MENU` → **应用程序** → **应用程序列表 / 应用程序管理**，选中“间隔拍照”，
   按提示删除（不同固件菜单名略有差异，A6000 上通常在应用程序列表内长按或按选项键）。
2. **覆盖安装**：签名一致时可直接重装新版本（versionCode 变大即可）；签名不一致会报
   `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，此时必须先卸载。
3. **保留数据**：卸载会清除应用在相机内的私有数据（`/data/data/com.pmca.intervalcapture`），
   但 **SD 卡上的照片与 `PMCACAP/` 目录不受影响**。
4. **彻底救砖**：见 [03-风险提示.md](03-风险提示.md) 的“应急处理”一节。

---

## 第三部分 · 故障排查对照表

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `pmca-console info` 提示 `No devices found` | 相机 USB 模式不是 MTP / 线只能充电 / 相机没开机 / 驱动异常 | 改成 MTP；换原装数据线；重插；Windows 设备管理器确认无黄色感叹号 |
| 识别到相机但 install 卡在 `Switching to app install mode` | 相机侧弹窗未确认；USB 被其他程序占用（PlayMemories Home、图片导入软件） | 关掉其他相机软件；在相机屏幕上确认；重插 USB |
| `Task completed successfully` 但相机里找不到应用 | 没关机重启；相机应用列表缓存未刷新 | 关机再开机；`MENU → 应用程序 → 应用程序列表` 再找 |
| 应用一闪就退回相机界面 | 私有 API 签名不匹配 / 桩被误打包 / 调用了固件不存在的接口 | 检查 dex 是否含 `com/sony/scalar` 类定义；确认 `compileOnly`；查看 `PMCACAP/LOG.TXT` |
| 屏幕界面错位、只画了一半 | 相机屏幕不是 640×480，或强制了竖屏 | 确认 `screenOrientation="landscape"`；界面坐标按 640×480 写死 |
| 按键没反应 | 扫描码映射与固件不一致 | 日志里记录了原始 scanCode/keyCode，按日志补映射（`CameraKeyRouter`） |
| 拍摄过程中应用退出 | 相机过热保护 / 内存不足 / 长时间预览 | 关闭预览、调大间隔、缩短单次任务时长 |
| 弹出“存储卡已满/写入失败” | SD 卡满或卡坏 | 清卡、换卡（建议 U3 卡），应用会自动停止拍摄 |
| 弹出“相机温度过高” | 长时间连拍 + 预览 | 停止任务，冷却后再拍；关闭预览、增大间隔 |
| 拍了 N 张但只存了 M 张（M<N） | 间隔小于单张耗时，快门排队/丢弃 | 见 03 文档“间隔无法 100% 严格”一节；增大间隔或改 JPG |
| 安装报 `INSTALL_FAILED_ALREADY_EXISTS` | 同名包已安装 | 先卸载再装，或直接安装更高 versionCode |

排障首选材料：**SD 卡 `/PMCACAP/LOG.TXT`**（应用自己写的运行日志）
和电脑侧 PMCA-RE 控制台输出。
