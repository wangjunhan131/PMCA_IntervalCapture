# PMCA_IntervalCapture —— 索尼相机机内间隔拍照工具

> 一个运行在 **索尼 ILCE-6000（A6000）相机内部** 的 PlayMemories Camera Apps（PMCA）应用。  
> 直接在相机本体上定时拍摄静态原图到 SD 卡，**只出照片，默认不合成延时视频**。  
> 不需要电脑、不需要手机、不需要连接任何外设。

---

## 1. 这个项目是什么 / 不是什么

| 是                                                      | 不是                           |
| ------------------------------------------------------ | ---------------------------- |
| 索尼 PMCA 平台的原生 Android 应用（API Level 10 / Android 2.3.7） | ❌ 手机 Android APK（不能装到手机）     |
| 界面用相机 **实体按键** 操作（上下左右 + 确认）                           | ❌ 触屏/网页/HTML/JS 应用           |
| 通过 PMCA-RE 刷入相机内部，脱机运行                                 | ❌ 电脑端 Windows 软件 / Python 脚本 |
| 每次触发调用索尼 `CameraEx` 静态拍照接口，原图直接落 SD 卡                  | ❌ 录制视频、自动合成延时视频              |

**核心原则：照片优先。** 视频合成只是拍摄结束后弹窗里的一个可选附加项，默认选项是“否”，  
选“否”时应用只保留照片，绝不对原图做任何改动，也不会调用任何视频编码接口。

---

## 2. 功能一览

### 2.1 参数配置页

| 参数   | 范围 / 选项                      | 说明                                    |
| ---- | ---------------------------- | ------------------------------------- |
| 拍摄间隔 | 1.0 ~ 3600 秒，最小步进 0.1 秒      | 内部以 0.1 秒为整数单位存储，1.0 秒是 A6000 的稳定下限   |
| 拍摄张数 | 固定模式 1 ~ 9999 张 / 无限模式（手动停止） | 两种模式可切换                               |
| 图像格式 | 仅 JPG / 仅 RAW（ARW）/ JPG+RAW  | 单选，实际写卡格式由相机“影像质量”设置决定                |
| 曝光锁定 | 开 / 关                        | 开启后首张锁定 AE/AF（白平衡随相机设置），后续复用，防止画面明暗闪烁 |
| 实时预览 | 开 / 关                        | 关闭可显著降低发热、提升长序列稳定性                    |
| 计数模式 | 固定张数 / 无限                    | 无限模式剩余张数显示“∞”                         |

### 2.2 运行状态页

- 已拍张数（大号数字）、剩余张数、进度条
- 距离下一次拍摄的倒计时（精确到 0.1 秒）
- 上一张实际耗时、节拍溢出次数、失败次数
- 机身温度状态、SD 卡剩余空间
- 状态文字：待机 / 拍摄中 / 已暂停 / 已结束 / 出错

### 2.3 按键映射（相机没有触屏）

**参数页**

| 按键      | 作用                             |
| ------- | ------------------------------ |
| 上 / 下   | 在 6 个参数项之间移动焦点                 |
| 左 / 右   | 修改当前焦点参数的数值（间隔 +0.1s、张数按档位递增等） |
| 确认 / 快门 | 开始拍摄                           |
| MENU    | 退出应用，返回相机原生拍摄界面                |
| PLAY    | 查看累计拍摄统计                       |

> 需求原文写的是“上下键修改各项参数”。由于本应用有 6 个参数项，必须**先选中再修改**，  
> 所以实现为「上下选参数、左右改数值」，这是相机菜单的通用操作习惯，也避免误触把间隔一次改飞。  
> 运行页的「下方向键 = 停止拍摄并回到参数页」严格按需求实现。

**运行页**

| 按键      | 作用               |
| ------- | ---------------- |
| 确认 / 快门 | 开始 ⇄ 暂停（暂停不重置计数） |
| 下       | 停止拍摄，回到参数设置页     |
| 上       | 运行中切换预览开关（省电降温）  |
| 左 / 右   | 提示“运行中不可修改参数”    |
| MENU    | 停止并退出            |

**完成弹窗**：左右/上下切换「是 / 否」，确认键确定，默认高亮 **否**；MENU 等同选「否」。

### 2.4 逻辑规则

1. 每次触发 → 调用 `CameraEx.takePicture()`，由相机固件按机内设置把 JPG/ARW 直接写入 SD 卡，  
   应用**不参与图像编码**，也不保存图片副本。
2. 拍摄过程中可随时暂停/继续，**计数不重置**；继续后重新开始计时，不补拍暂停期间缺的张数。
3. 拍摄结束弹出“全部照片已经保存至 SD 卡”，附带【是否生成延时视频】选项，**默认否**。  
   选“是”时只写一份 `PMCACAP/TIMELAPSE_JOB.TXT` 任务清单（含帧率建议与 ffmpeg 命令），  
   供用户在电脑上自行合成；**机内不生成视频、不改动任何原图**。
4. 错误捕获：SD 卡满、存储卡读写失败、相机过热 → 立即停止拍摄并弹出中文报错。

---

## 3. 目录结构

```
PMCA_IntervalCapture/
├── build.gradle                     # 根构建脚本（Gradle 6.7.1）
├── settings.gradle
├── gradle.properties
├── gradlew / gradlew.bat            # 需由 `gradle wrapper` 生成（见文档 01）
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
├── app/
│   ├── build.gradle                 # app 模块：compileSdk 10 / minSdk 10 / Java 1.7
│   ├── libs/
│   │   ├── sony-pmca-stubs.jar      # 索尼私有 API 编译期桩（compileOnly，不进 APK）
│   │   └── android-4.1.1.4.jar      # Android 编译期类库存根
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/values/strings.xml
│       ├── res/values/styles.xml
│       ├── res/drawable/ic_launcher.png
│       └── java/com/pmca/intervalcapture/
│           ├── CaptureActivity.java      # 唯一的 Activity：状态机 + 界面 + 按键编排
│           ├── CaptureService.java       # 硬件层：CameraEx 打开/预览/拍照/释放
│           ├── CaptureCallback.java      # 硬件层回调接口
│           ├── CaptureSettings.java      # 参数模型（钳制、步进、序列化、硬件告警）
│           ├── IntervalScheduler.java    # 节拍状态机（开始/暂停/继续/停止/超时/溢出）
│           ├── HealthMonitor.java        # SD 卡、空间、温度、连续写卡失败监控
│           ├── CameraKeyRouter.java      # 相机扫描码 → 逻辑按键
│           ├── SettingsStore.java        # 参数持久化（SharedPreferences + SD 卡备份）
│           ├── AppLog.java               # 日志（logcat + SD 卡 PMCACAP/LOG.TXT）
│           ├── TimelapseJobWriter.java   # 可选：写延时视频任务清单（不生成视频）
│           ├── SystemEventReceiver.java  # 开机/退出广播记录
│           └── ui/
│               ├── UiTheme.java          # 640×480 配色、字号、绘制工具
│               ├── SettingsScreen.java   # 参数页绘制
│               ├── RunScreen.java        # 运行页绘制
│               └── DialogScreen.java     # 完成/错误/确认弹窗绘制
├── tools/
│   ├── build-apk.ps1                # 无 Gradle 的一键打包脚本（aapt→javac→d8→签名）
│   ├── run-logic-tests.ps1          # 纯逻辑单元测试运行脚本
│   ├── gen-source-doc.ps1           # 把全部源码自动拼成 docs/05-完整源码合集.md
│   └── tests/LogicTest.java         # 64 项断言（参数/节拍/暂停/溢出/无限模式）
├── build-support/                   # 本机自测工具链（Android build-tools、桩 jar、keystore）
└── docs/
    ├── 01-编译环境搭建.md
    ├── 02-打包与PMCA-RE刷入教程.md
    ├── 03-风险提示.md
    ├── 04-需求实现对照表.md
    └── 05-完整源码合集.md
```

---

## 4. 快速开始

### 4.1 已有 APK，直接刷机

```powershell
# 产物
build\outputs\PMCA_IntervalCapture.apk

# 用 PMCA-RE 刷入（相机用 USB 连电脑，开机即可）
pmca-console install -f build\outputs\PMCA_IntervalCapture.apk
```

详细步骤见 [docs/02-打包与PMCA-RE刷入教程.md](docs/02-打包与PMCA-RE刷入教程.md)。

### 4.2 从源码重新打包（无需 Gradle）

```powershell
powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1
```

脚本 8 步：`aapt package` 生成 R.java → `aapt` 打包资源 → `javac` 编译 → `d8` 转 DEX →  
合并进 APK → `zipalign` 对齐 → `apksigner` v1 签名 → 校验并打印 badging。

### 4.3 跑单元测试

```powershell
powershell -ExecutionPolicy Bypass -File tools\run-logic-tests.ps1
```

纯 JVM 运行，不需要相机、不需要 Android SDK，覆盖 64 项断言。

### 4.4 用 Gradle 构建（需官方 PMCA SDK）

环境与步骤见 [docs/01-编译环境搭建.md](docs/01-编译环境搭建.md)。

---

## 5. 关键平台约束（写进代码注释里的那些）

| 约束                            | 在本项目中的体现                                                                                              |
| ----------------------------- | ----------------------------------------------------------------------------------------------------- |
| 相机运行时 = Android 2.3.7（API 10） | `minSdkVersion 10`；只用 API 10 存在的 `android.*` 接口；`StatFs` 只用 `getBlockSize()` / `getAvailableBlocks()` |
| 索尼私有 API 只在固件里                | 所有 `com.sony.scalar.*` 用 `compileOnly` 引入，**绝不打包**，否则启动即 `ClassCastException`/崩溃                      |
| A6000 最短稳定间隔 1 秒              | 间隔下限硬钳制 1.0 秒；<1.5 秒且开预览或用 RAW 时界面弹硬件告警                                                               |
| 长曝光会占用拍摄周期                    | 调度器记录每张实际耗时，`lastShotDuration >= interval` 时判定“节拍溢出”：下一次立即拍，并累加溢出计数在运行页显示                             |
| 无触屏，640×480 横屏                | 全部界面用 `Canvas` 自绘，无任何 `Button`/`EditText`/触摸监听；预览区固定 296×300                                          |
| 只拍静态图                         | 全工程只调用 `CameraEx.takePicture()`，没有任何 `MediaRecorder` / 视频 API                                         |
| 相机必须保持唤醒                      | 拍摄期间持有 `PARTIAL_WAKE_LOCK`，结束与退出时释放                                                                   |

---

## 6. 安全边界与风险（务必先读）

> **刷入第三方 PMCA 应用存在相机固件损坏、变砖的风险。**  
> 本项目只通过 PMCA-RE 的 **app installer** 通道安装应用，不修改相机固件本体；  
> 但 PMCA-RE 本身是逆向工程工具，任何刷写行为都请自行承担风险。  
> 完整风险清单见 [docs/03-风险提示.md](docs/03-风险提示.md)，至少请先确认：
>
> 1. 电池电量 > 80%，刷入过程不要断电、不要拔 USB；
> 2. 先备份 SD 卡里的照片；
> 3. 长时间高频连拍会加速机械快门损耗、导致相机过热停机；
> 4. 曝光时间会占用拍摄周期，**设置的间隔无法 100% 严格生效**，尤其在 1~2 秒间隔 + RAW 场景；
> 5. 出问题可用 PMCA-RE 卸载应用（`pmca-console install` 反向操作或 Tweak 工具），严重时需救援固件。

---

## 7. 免责声明

本项目为学习与个人创作用途的开源工程，与索尼公司无关，未获得索尼官方授权或背书。  
“PlayMemories Camera Apps”“ILCE-6000”“索尼”是其各自权利人的商标。  
刷机、改机属于超出厂商保修范围的操作，请自行评估并承担全部风险与后果。

---

## 8. 开源协议与仓库说明

- 代码以 **MIT License** 发布（见 [LICENSE](LICENSE)）；另附“刷机风险自担、不含索尼专有代码”的补充声明。
- `app\libs\sony-pmca-stubs.jar` 是**编译期签名桩**，签名信息来自  
  [OpenMemories-Framework](https://github.com/ma1co/OpenMemories-Framework)（MIT），按其许可证保留署名；  
  运行时真正的实现由相机固件提供，桩**不会**被打进 APK（可 `dexdump -f` 验证无私类定义）。
- **仓库刻意不包含以下大文件/敏感文件**（见 [.gitignore](.gitignore)），克隆后按  
  [docs/01-编译环境搭建.md](docs/01-编译环境搭建.md) 自备即可：

| 未入库内容                                   | 原因                     | 克隆后怎么办                                             |
| --------------------------------------- | ---------------------- | -------------------------------------------------- |
| `app\libs\android-4.1.1.4.jar`（约 12 MB） | Android SDK 存根，仓库不重新分发 | 从自己的 Android SDK 取 `android.jar` 改名放入              |
| `build-support\android-tools\`          | 二进制工具链，体积大             | 复制自己 build-tools 的 `aapt/zipalign/apksigner/d8`    |
| `build-support\*.keystore`、`*.jks`      | 私钥不应入库                 | `tools\build-apk.ps1` 检测不到会自动 `keytool` 生成         |
| `build\`、`.work\`、`*.apk`、`*.dex`       | 构建产物                   | 本地 `tools\build-apk.ps1` 重新生成；发布版放 GitHub Releases |

- 想发布可刷机版本：把打好的 `PMCA_IntervalCapture.apk` 附到 GitHub **Releases**（不要提交进 git 历史）。
- 完整源码合集（单文件通读版）：[docs/05-完整源码合集.md](docs/05-完整源码合集.md)。
- 上传到 GitHub 的完整流程（含本机已就绪的 SSH 密钥、一键推送脚本）：[docs/06-GitHub上传与发布指南.md](docs/06-GitHub上传与发布指南.md)。

一键推送到自己的 GitHub 账号：

```powershell
# 先把公钥加到 https://github.com/settings/ssh/new （只需一次）
powershell -ExecutionPolicy Bypass -File tools\push-to-github.ps1 -User 你的GitHub用户名
```

> 脚本会自动配置 `origin`、校验 SSH、推送 `main` 和 `v1.0.0` 标签。
> 加 `-Check` 只做检查不推送；加 `-Token ghp_xxx` 可改用 HTTPS + PAT。

