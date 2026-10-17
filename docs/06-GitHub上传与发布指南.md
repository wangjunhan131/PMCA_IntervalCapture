# 06 · 上传到 GitHub 与发布 Release 全流程

> 本文档假设工程已经能在本地构建成功（`build\outputs\PMCA_IntervalCapture.apk`）。
> 全部命令均在 **PowerShell** 中执行，工作目录固定为
> `D:\sony\PMCA_IntervalCapture`（克隆到你自己的机器后换成你的路径即可）。
>
> 仓库已随工程提供三个“上传前必备”文件：`.gitignore`、`.gitattributes`、`LICENSE`。
> 它们决定了**哪些文件进仓库、换行符怎么处理、以什么协议开源**，请勿删除。

---

## 1. 前置：安装并配置 Git

### 1.1 安装

Windows 推荐两种方式（任选其一）：

```powershell
# 方式 A：winget 一键安装（本机采用此方式，装完路径为 C:\Program Files\Git\cmd\git.exe）
winget install --id Git.Git -e --source winget --accept-package-agreements --accept-source-agreements

# 方式 B：官网下载安装包 https://git-scm.com/download/win
```

装完后**必须新开一个 PowerShell 窗口**（PATH 才生效），验证：

```powershell
git --version
# 期望输出类似：git version 2.51.0.windows.1
```

如果提示“无法将 git 项识别为 cmdlet”，可直接用绝对路径调用：
`& 'C:\Program Files\Git\cmd\git.exe' --version`。

### 1.2 配置身份与编码（首次使用 Git 必做）

```powershell
# 提交者身份：GitHub 上会显示这个名字和邮箱
git config --global user.name  "你的昵称"
git config --global user.email "你的邮箱@example.com"

# 中文文件名不要被转义成 \346\226\207 这种八进制
git config --global core.quotepath false

# 让 Git 在 push 时使用 Windows 自带的证书存储（公司网络/代理环境更稳）
git config --global http.sslBackend schannel

# 全局 .gitignore（可选）
git config --global core.excludesfile "$env:USERPROFILE\.gitignore_global"
```

验证：

```powershell
git config --global --list
```

---

## 2. 本地初始化仓库并提交

```powershell
cd D:\sony\PMCA_IntervalCapture

# 初始化，主分支名用 main（GitHub 默认）
git init -b main

# 把工程加入暂存区（.gitignore 会自动过滤大文件）
git add .

# ★ 关键一步：确认暂存了什么。下面这条命令应该【没有任何输出】
git status --short | Select-String 'android-4\.1\.1\.4\.jar|\.keystore|\.jks|^A  build/|^A  \.work/'

# 看文件清单和数量（预期约 40 个文件）
git ls-files
(git ls-files | Measure-Object).Count

# 看暂存体积（预期 1 MB 左右，不含 android.jar，不应出现 12 MB）
git count-objects -vH
```

预期结果（本工程实测）：

| 检查项 | 期望值 |
| --- | --- |
| 文件数 | 约 40 个（源码 + 文档 + 脚本 + 桩 jar + 图标 + 清单） |
| `android-4.1.1.4.jar` | **不出现**（12 MB，Android SDK 组件，不入库） |
| `build\`、`.work\` | **不出现**（构建产物） |
| `*.keystore` | **不出现**（私钥） |
| `sony-pmca-stubs.jar` | 出现（127 KB 编译期桩，用于编译，不打包进 APK） |
| `gradle\wrapper\gradle-wrapper.jar` | 出现（59 KB，标准 Gradle Wrapper） |

首次提交：

```powershell
git commit -m "feat: PMCA 机内间隔拍照应用 v1.0.0（A6000 实体按键 / 只出照片）"
```

> 提交信息建议遵守 Conventional Commits：`feat:` 新功能、`fix:` 修 bug、`docs:` 文档、`chore:` 杂项。
> 后续小改动示例：`git commit -m "docs: 补充 MTP 连接方式的截图说明"`。

---

## 3. 在 GitHub 上创建空仓库

1. 打开 https://github.com/new
2. 填写：

| 字段 | 建议值 |
| --- | --- |
| Repository name | `PMCA_IntervalCapture`（与工程名一致） |
| Description | `索尼 A6000 机内间隔拍照工具 · PMCA 原生应用 · 只出照片不合成视频` |
| 可见性 | **Public**（想私藏就选 Private，流程完全一样） |
| Add a README file | ❌ **不要勾**（本地已有 README.md） |
| Add .gitignore | ❌ **不要选**（本地已提供） |
| Choose a license | ❌ **不要选**（本地已提供 MIT LICENSE） |

3. 点 **Create repository**，跳转后的页面会给出推送地址，形如
   `https://github.com/<你的用户名>/PMCA_IntervalCapture.git`

---

## 4. 关联远程仓库并推送

### 4.1 方式 A：HTTPS + 浏览器登录（推荐新手）

```powershell
git remote add origin https://github.com/<你的用户名>/PMCA_IntervalCapture.git
git remote -v                      # 确认 origin 地址
git branch -M main                 # 确保当前分支叫 main
git push -u origin main
```

首次 `push` 会弹出 **Git Credential Manager** 窗口（Git for Windows 自带）：
选 `Sign in with your browser` → 浏览器里点授权 → 回到终端自动继续。
凭据会被 Windows 凭据管理器记住，之后不用再输。

> 如果没弹窗、直接 403/认证失败：说明需要 **Personal Access Token（PAT）**。
> GitHub 网页 → 右上头像 → **Settings → Developer settings → Personal access tokens
> → Tokens (classic)** → `Generate new token` → 勾 `repo` 权限 → 生成后复制（只显示一次），
> 在提示 `Password:` 时粘贴这个 token（**不是**账号密码）。

### 4.2 方式 B：SSH（长期使用更省事）

```powershell
# 1) 生成密钥（一路回车即可，路径默认 ~/.ssh/id_ed25519）
ssh-keygen -t ed25519 -C "你的邮箱@example.com"

# 2) 复制公钥内容
Get-Content $env:USERPROFILE\.ssh\id_ed25519.pub | Set-Clipboard

# 3) 网页操作：GitHub → Settings → SSH and GPG keys → New SSH key → 粘贴 → Add SSH key

# 4) 测试连通（首次会问 yes/no，输入 yes）
ssh -T git@github.com          # 期望看到：Hi <用户名>! You've successfully authenticated...

# 5) 用 SSH 地址推送
git remote set-url origin git@github.com:<你的用户名>/PMCA_IntervalCapture.git
git push -u origin main
```

推送成功后，刷新 GitHub 仓库页面即可看到 README 渲染效果。

---

## 5. 打版本号 + 发布 Release（把 APK 提供给刷机用户）

代码仓库**不该**提交 APK（见 `.gitignore`），正确的分发方式是 GitHub Releases：

```powershell
cd D:\sony\PMCA_IntervalCapture

# 1) 创建附注标签（版本号与 AndroidManifest 的 versionName 保持一致）
git tag -a v1.0.0 -m "PMCA_IntervalCapture v1.0.0：首个可用版本，仅拍照、不合成视频"

# 2) 推送标签
git push origin --tags
```

然后网页操作：

1. 仓库页右侧 → **Releases** → **Draft a new release**
2. **Choose a tag** → 选 `v1.0.0`
3. Release title：`v1.0.0 · 首个可用版本`
4. 描述可参考下面模板（Markdown）：

```markdown
## 功能
- 机内定时间隔拍照（1.0~3600 秒，步进 0.1 秒）
- 固定张数 1~9999 / 无限连续（手动停止）
- 只输出照片（JPG / RAW / JPG+RAW 由机内菜单决定），**默认不生成延时视频**
- 可暂停/继续，计数不重置；SD 卡满、写卡失败、过热自动停止并中文报错

## 安装
1. 相机：`MENU → 设置 → USB连接 → MTP`
2. 电脑：`pmca-console install -f PMCA_IntervalCapture.apk`
3. 装完**关机再开机**，`MENU → 应用程序 → 应用程序列表` 里打开

## 注意
- 需要自签名证书，仅供个人学习使用
- 刷入第三方应用有变砖风险，详见 docs/03-风险提示.md
- 别在重要拍摄前首次试机
```

5. **Attach binaries** → 上传 `build\outputs\PMCA_IntervalCapture.apk`（约 35 KB）
6. 点 **Publish release**

> 校验和（可选，方便别人核对文件）：
> ```powershell
> Get-FileHash build\outputs\PMCA_IntervalCapture.apk -Algorithm SHA256 | Format-List
> ```
> 把 SHA256 值贴到 Release 描述里。

---

## 6. 仓库设置（5 分钟美化，让人看得懂）

| 位置 | 建议填写内容 |
| --- | --- |
| About → Description | `索尼 A6000 机内间隔拍照工具 · PMCA 原生应用 · 只出照片不合成视频` |
| About → Topics | `sony` `pmca` `a6000` `ilce-6000` `intervalometer` `timelapse` `playmemories` `camera-app` `java` `reverse-engineering` |
| About → License | 自动识别为 `MIT license`（有 LICENSE 文件即可） |
| Settings → General → Default branch | `main` |
| Settings → Features → Issues / Discussions | 建议开启 Issues（收集刷机问题），Discussions 可选 |
| Settings → General → Danger Zone | 保持公开，除非你想私有 |

---

## 7. 常见问题排查

| 现象 | 原因 / 解决 |
| --- | --- |
| `git: command not found` | 装完没重开终端；重开 PowerShell 或用绝对路径 `C:\Program Files\Git\cmd\git.exe` |
| `fatal: remote origin already exists` | `git remote set-url origin <新地址>` 覆盖即可 |
| `Authentication failed` / `403` | 用 PAT 当密码，**不是** GitHub 登录密码；或改用 SSH |
| `error: src refspec main does not match any` | 还没 commit：`git add .` + `git commit -m "init"` |
| `Filename too long` | `git config core.longpaths true`（本项目路径不长，一般用不到） |
| 中文文件名显示成 `\350\204\232...` | `git config --global core.quotepath false` |
| 误把 `build/` 提交了 | `git rm -r --cached build` → `git commit -m "chore: 移除构建产物"` |
| 误把大文件提交进历史 | 用 `git filter-repo --path <文件> --invert-paths` 清洗（会重写历史，慎用） |
| `.ps1` 推送后变成 LF、脚本报语法错 | `.gitattributes` 已强制 `*.ps1 text eol=crlf`；本地若已被改坏，`git checkout -- <文件>` 恢复 |
| push 卡住/超时 | 关掉代理或配置：`git config --global http.proxy http://127.0.0.1:端口`（按需） |

---

## 8. 国内网络备选：Gitee

GitHub 访问不稳定时，可先推到 Gitee，流程完全一样，只换地址：

```powershell
git remote add gitee https://gitee.com/<你的用户名>/PMCA_IntervalCapture.git
git push -u gitee main
# 之后可同时推两处：
git push origin main
git push gitee main
```

---

## 9. 安全红线（务必遵守）

1. **绝不上传**：`*.keystore` / `*.jks`（私钥）、相机固件 dump、你我都不该传的索尼专有文件。
2. **绝不提交**：`app\libs\android-4.1.1.4.jar`（Android SDK 组件，仓库不重分发，见 LICENSE 第 3 条）。
3. **APK 只走 Releases**，不进 git 历史——否则仓库会越滚越大。
4. 若发现密钥已误传：立刻到仓库 Settings → 删除/重建密钥，并用 `git filter-repo` 清理历史；
   仅删除当前文件**不会**从历史提交里消失。
5. 本项目仅适合个人学习/创作用途，请勿用于商业破解或改装销售（见 LICENSE 补充声明）。
