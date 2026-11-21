<!--suppress HtmlDeprecatedAttribute, HtmlDeprecatedTag -->
<div align="center">

# MiuiSettingMod

### 基于 Miuix 的 HyperOS 界面增强模块

作者：桜咲 ｜ 基于 [Ianzb/MiuixGuiTemplate](https://github.com/Ianzb/MiuixGuiTemplate) 二次开发

[接口文档](docs/API.md) | [原生 Hook 指南](docs/NATIVE_HOOK.md) | [二次开发指南](docs/CUSTOMIZE.md) | [模块开发工作流](docs/WORKFLOW.md) | [更新日志](changelog.md)

![Platform](https://img.shields.io/badge/Platform-Android-green)
![LSPosed](https://img.shields.io/badge/LSPosed-libxposed%20102-blue)

</div>

**MiuiSettingMod** 是面向 HyperOS 的 LSPosed 模块，提供一组可开关的系统界面增强功能；同时基于 [libxposed API 102](https://libxposed.github.io/api/index-all.html) 与 [Miuix](https://github.com/compose-miuix-ui/miuix) Compose 组件库，保留完整的 Hook 封装、配置系统与安全模式，方便继续扩展自己的功能。

<br>

# 功能

功能页按分区组织，开关默认全部关闭；改动后需要重启对应进程（或系统）才会生效。

## 显示与界面

- **强制开启色彩风格高级模式** — 在「设置 → 显示 → 色彩风格」中恒显示高级（专业）模式入口（`com.android.settings`）

## 系统界面

- **隐藏手势线** — 常驻隐藏，或仅在桌面隐藏、回到其它应用时恢复（`com.android.systemui`）
  - 隐藏方案可选：**新方案**（`NavigationHandle#onDraw`，默认）/ **旧方案**（`MiuiDecorationBottomView#onDraw`，用于存在状态栏跟随问题的版本）
  - 隐藏后把手势导航栏声明给系统的 `navigationBars` inset 高度归零，**内容直接延伸到屏幕底部**；只改导航栏 inset，不动 `systemGestures` / `mandatorySystemGestures`，底部返回手势热区保持不变

## 息屏显示

- **关闭 AOD 通知光效** — 来通知时不播放波纹 / 粒子动画，通知图标与时钟照常显示（AOD 插件运行在 `com.android.systemui` 进程，需额外探测其插件 ClassLoader）

## 框架能力

- **Hook 封装** — `hookBefore` / `hookAfter` / `hookReplace` / `intercept` / `findAndHook*` / `hookAll*` / `hookClassInitializer` / `invokeOriginal`，可在回调中改写方法参数与返回值，统一句柄管理
- **原生 Hook 封装** — `NativeHookHelper` / `BaseNativeHook` / `BaseLoad.initNativeHook`，与 Java Hook 对称的一键接入（声明、加载、开关、安全兜底），面向 Rust 应用，详见[原生 Hook 指南](docs/NATIVE_HOOK.md)
- **版本 / 设备筛选** — 统一的 `HookVersionGate` 与 `deviceScope`：版本支持 `>` `<`、多重规则（AND/OR），可按 Android / HyperOS / MIUI / 应用版本；设备支持手机 / 平板 / 折叠屏区分，可在设置中手动覆盖当前设备类型
- **安全模式** — 目标应用反复崩溃时自动跳过该应用的 Hook；设置页可逐应用开关并查看崩溃计数
- **主动申请作用域** — 启用选项时自动为未授权目标申请作用域
- **DexKit 缓存** — 带 JSON 持久化、版本失效校验与文件锁的 DexKit 缓存，支持 Root 清空
- **统一配置系统** — 自定义键名、默认值、持久化、跨进程镜像、JSON 导出导入
- **组件与 Hook 绑定** — 开关 / 箭头 / 下拉 / 滑块 / 复选框 / 单选 / 文本 / 包名列表，标题接入全局搜索
- **设备独占控制** — 配置项声明 `deviceScope` 后，非白名单设备上禁用灰显不隐藏，切换「当前设备类型」实时生效；hook 侧同步跳过
- **宽屏与外观** — 底部导航可选 HyperOS 玻璃 / iOS 液态玻璃，宽屏可切换顶部胶囊或左侧面板侧栏；背景模糊与渐变模糊统一由 miuix 提供

<br>

# 作用域

在 LSPosed 中为模块勾选以下作用域后重启对应应用：

| 包名 | 用途 |
|---|---|
| `com.android.settings` | 色彩风格高级模式 |
| `com.android.systemui` | 手势线隐藏、AOD 通知光效 |
| `system` | 系统进程级功能（按需勾选） |

<br>

# 系统要求

- 已安装 **LSPosed**（支持 libxposed API 102）
- Android 15+（minSdk 35）
- 清空 DexKit 缓存需要 **Root 权限**

<br>

# 构建

```bash
# 克隆项目
git clone https://github.com/767939287/MiuiSettingMod.git
cd MiuiSettingMod

# 设置 JDK 25（CI 使用 temurin 25）和 Android SDK
# app 模块编译目标为 JVM 21，以使用 miuix-nav 的内联路由 API
# 编辑 local.properties 指向你的 SDK 路径
# 需要 Android SDK Platform 37.2（compileSdk = 37 minor 2，miuix AAR 以此编译）

# 构建 Debug APK
./gradlew assembleDebug

# APK 输出位置（仅 arm64-v8a）
# app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

## miuix 依赖

OS4 玻璃组件（`miuix-glass`、`miuix-material`、`miuix-glass-icons`）随 miuix 发布到 Maven Central，本项目直接使用 **0.9.5-beta03**。

```toml
# gradle/libs.versions.toml
miuix = "0.9.5-beta03"
```

`settings.gradle.kts` 只保留 `google()` 与 `mavenCentral()`（已移除 `mavenLocal()`），因此任意机器 / CI 上 `./gradlew assembleDebug` 都能直接解析依赖。

> 二次开发若需要跟随 miuix 主干尚未发版的提交，可以自行在本地 `publishToMavenLocal` 并把 `miuix` 改成对应快照版本号、临时加回 `mavenLocal()`；本项目默认不这样做。

<br>

# 发布与 CI

仓库内置 GitHub Actions 工作流：

- **`.github/workflows/ci.yml`** — 推送到 `master`（改动 `app/**`、`hook/**`、Gradle 文件）或提交 PR 时构建 Debug APK，上传为 Actions Artifacts（保留 7 天）。
- **`.github/workflows/release.yml`** — 推送 `v*.*.*` 标签或手动触发时构建**签名** Release APK，发布到 GitHub Release（Release Notes 取自 `changelog.md` 对应版本段落）并上传 Artifact（保留 30 天）；配置 Telegram 后可自动推送。

## Release 签名配置（二次开发须自行配置）

Release 构建通过 `signingConfigs.release` 读取环境变量（见 `app/build.gradle.kts`），keystore 固定为根目录 `release.keystore`（已加入 `.gitignore`，切勿提交）：

| 环境变量 / Secret | 说明 |
|---|---|
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 密钥别名（未设置时默认 `release`） |
| `KEY_PASSWORD` | 密钥密码 |

**1. 生成本地 keystore**（alias 换成你自己的）：

```bash
keytool -genkeypair -v -keystore release.keystore \
  -alias release -keyalg RSA -keysize 2048 -validity 10000
```

**2. 本地构建签名包**（PowerShell）：

```powershell
$env:KEYSTORE_PASSWORD="你的keystore密码"
$env:KEY_ALIAS="release"
$env:KEY_PASSWORD="你的密钥密码"
./gradlew assembleRelease
# 输出：app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

**3. 配置 GitHub Secrets**（仓库 Settings → Secrets and variables → Actions）：

| Secret | 内容 |
|---|---|
| `KEYSTORE_BASE64` | `release.keystore` 的 Base64：`[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore"))` |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |
| `CHANNEL_ID` / `BOT_TOKEN` | （可选）Telegram 推送；不需要时删除 `release.yml` 的「上传到Telegram」步骤 |
| `MESSAGE_THREAD_ID` | （可选）多话题群（Forum）指定话题的 `message_thread_id`；不填则发到默认 / General 话题 |
| `TEST_MESSAGE_THREAD_ID` | （可选）测试版推送的话题 `message_thread_id`（同一群的另一个话题），与 `CHANNEL_ID` / `BOT_TOKEN` 配合使用；不填则跳过 CI 的「上传到Telegram」步骤 |

**4. 发布**：手动运行 `Release Build` 工作流并填写版本号（会自动 `versionCode` +1 并提交），或先改好 `versionName` 再推送 `vX.Y.Z` 形式的标签。

> 只开发 / 只构建 Debug 时无需上述配置，`assembleDebug` 不受影响；未放置 `release.keystore` 或未设置环境变量时请勿运行 `assembleRelease`。

<br>

# 二次开发

新增一个功能的最小闭环：

1. 新目标进程时，先在 `:hook` 模块 `META-INF/xposed/scope.list` 声明包名。
2. 新建 `BaseLoad`，在 `onPackageLoaded` 中按配置键注册 hook，并在 `HookEntryRegistry` 登记。
3. 新建 `BaseHook`，在 `init()` 中用 `HookHelper` 挂载目标方法；需要改写参数/返回值时用 `HookParam.setArg` / `setResultValue`。
4. 在 `featureSpecs()` 声明 `OptionSpec`（`key` 与 `BaseHook.key` 一致），UI 自动渲染并上报生效状态。
5. 需要 DexKit 时覆写 `useDexKit()` 与 `initDexKit()`（缓存自动管理）。

> **需要修改的完整清单（图标、链接、模块元数据、Hook、配置项等）见 [二次开发指南](docs/CUSTOMIZE.md)。**

> **实现指定应用的 Hook、或参考其他模块复刻功能时的完整流程（含真机 `adb` 扫描授权、隐私边界与开源合规）见 [模块开发工作流](docs/WORKFLOW.md)。**

详细接口说明见 [接口文档](docs/API.md)。

<br>

# 第三方库

- [miuix](https://github.com/compose-miuix-ui/miuix) — HyperOS 风格 Compose UI 组件库（含 iOS 液态玻璃底栏示例，`component/liquid`）
- [libxposed API](https://github.com/libxposed/api) — 现代 Xposed 模块 API（102）
- [DexKit](https://github.com/LuckyPray/DexKit) — Dex 解析与缓存
- [AndroidX Compose](https://developer.android.com/jetpack/compose) — 声明式 UI 框架

<br>

# 参考与致谢

开发过程中参考了若干开源项目与其他模块项目，谨向相关作者与贡献者致谢。具体致谢清单统一维护在应用内「关于 → 第三方许可证与致谢」页面，文档不再逐一展开；参考或复刻第三方项目时的合规流程见 [模块开发工作流](docs/WORKFLOW.md)。

<br>

# 使用本模板的项目

基于本模板的衍生项目**无需**在 `README.md` 或应用内「关于」页标注来源。应用内 Logo 下方的 `Based on MiuiSettingMod <版本号>` 为**可选项**：保留时建议将版本号更新为所依据的脚手架版本，便于同步脚手架的修复与改进；不保留亦不影响合规。

```text
Based on MiuiSettingMod <版本号>
```

当前使用本模板的项目：

| 项目 |
|---|
| [HyperNavBar](https://github.com/HyperNavBar/HyperNavBar) |
| [HyperRefine](https://github.com/Ianzb/HyperRefine) |

开源协议义务已汇总为一张核对清单，见[二次开发指南 · 开源协议与致谢](docs/CUSTOMIZE.md#11-开源协议与致谢必读)。

<br>

# 许可证

本项目以 [GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0）开源。

Copyright (C) 2026 桜咲 —— 基于 Ianzb/MiuixGuiTemplate 二次开发，原项目同样以 AGPL-3.0 授权。

本仓库同时包含 Apache-2.0 许可的第三方代码（自 [miuix](https://github.com/compose-miuix-ui/miuix) 等引入的文件保留其原始版权与许可声明）。AGPL-3.0 与 GPL-3.0 兼容（AGPL-3.0 §13）；基于本模板的衍生作品须以 AGPL-3.0 授权公开，并保留第三方代码的原始版权与许可声明。
