# 工作区构建说明

Android 工程包含原生 StarLive alpha、可独立测试的计划与监测核心，以及阶段 2 Java 能力验证包。原生界面使用 Kotlin／Jetpack Compose。Web 原型保留在 `dist/`。

阶段 5 在星图、周计划、生活灵感与本地备份基础上，加入真实使用统计、前台服务提醒及开机恢复。当前构建使用 debug 签名。构建结果与真机检查分别记录。[STAGE5_RESULTS.md](STAGE5_RESULTS.md)

2026-11-09 阶段 4 的实际结果与交付指纹见 [STAGE4_RESULTS.md](STAGE4_RESULTS.md)：43 项测试通过，debug 构建通过，Lint 最终 0 错误；其中也列出本机测试适配和仍需真机验收的边界。

## 工具布局

项目在工作区的 `StarLive/`，开发工具均放在同一工作区的 `tools/`，不修改系统环境变量：

```text
workspace/
  StarLive/android/
    app/
    plan-core/
    probe/
    usagefixture/
  StarLive/scripts/
  tools/jdk-17.0.21+9/
  tools/gradle-8.11.1/
  tools/android-sdk/
  tools/platform-tools/
  tools/gradle-user-home/
  tools/android-user-home/
  tools/robolectric-m2/
```

当前工程配置：Temurin JDK 17.0.21+9、Gradle 8.11.1、Android Gradle Plugin 8.10.1、SDK Platform 35、Build Tools 35.0.0。原生应用新增 Kotlin／Compose 插件 2.2.0、Compose BOM 2025.06.01；计划核心使用 Gson 2.11.0。本机测试依赖包括 JUnit 4.13.2 和 Robolectric 4.15.1。

Android 模块 compileSdk/targetSdk 为 35、minSdk 为 26，这是工程选择；目前目标真机为 Android 15／API 35，不表示其他系统版本已验收。上述版本列表描述仓库配置，不提前宣称新增依赖的完整测试已通过。正式 Release 尤其启用 R8 缩减前，须结合实际工具版本核对 [Android 官方 Kotlin／D8／R8 兼容要求](https://developer.android.com/build/kotlin-support)。

兼容依据：[Android 官方 AGP 8.10 说明](https://developer.android.com/build/releases/agp-8-10-0-release-notes)。升级 AGP 未改变工作区 Gradle、JDK、SDK 或 Build Tools 版本。阶段 2 的既有构建／真机记录使用 AGP 8.9.2；该历史事实保持不变，当前源码重新构建任何 Android 模块时统一采用 8.10.1。SDK/JDK/Gradle 下载包已按官方 SHA-256 校验；工具下载、缓存和 debug 签名密钥不纳入仓库。

## 原生 StarLive alpha 构建

在仓库目录运行：

```powershell
./scripts/build-app.ps1
```

默认任务：

```text
:plan-core:test
:app:testDebugUnitTest
:app:assembleDebug
:app:lintDebug
```

`plan-core` 为纯 Java 领域测试；`app:testDebugUnitTest` 为本机 JVM／Robolectric 检查，不连接手机。两类测试都不能替代真实触摸、系统权限、OriginOS 后台行为或耗电验收；模拟器也不能替代目标真机。测试代码存在或单独编译产出，不等于所有任务及界面检查已通过。

应用信息与产物：

| 项目 | 值 |
| --- | --- |
| 包名 | `io.github.githuiy.starlive` |
| 版本名／版本号 | `0.1.0-alpha02`／`2` |
| APK | `android/app/build/outputs/apk/debug/app-debug.apk` |
| 本机测试报告 | `android/plan-core/build/reports/tests/test/`、`android/app/build/reports/tests/testDebugUnitTest/` |
| Lint 报告 | `android/app/build/reports/lint-results-debug.html` |

首版个人数据经 AtomicFile 保存在本地，手动备份与两个验证包、Web 浏览器数据隔离。真实监测需在应用内主动开启，并授予使用情况访问与通知权限。修改阈值只更新规则，监测服务在有效使用时判断提醒。

## Java 能力验证包构建

在仓库目录运行：

```powershell
./scripts/build-probe.ps1
```

入口默认构建 `probe` 和 `usagefixture` 两个 debug APK 并运行对应 Android Lint，与正式应用入口分开。

产物路径：

- `android/probe/build/outputs/apk/debug/probe-debug.apk`
- `android/usagefixture/build/outputs/apk/debug/usagefixture-debug.apk`

验证包仍用于真实系统能力实验，不包含完整正式产品规则；用途与测试值见 [ANDROID_PROBE.md](ANDROID_PROBE.md)。

## 当前代理、离线缓存与签名

两个入口共用 `build-probe.ps1` 中的工作区工具链设置；均可用 `-Tasks` 指定任务，使用 `-Offline` 向 Gradle 传递 `--offline`。当前进程优先沿用 HTTPS_PROXY／HTTP_PROXY；没有环境代理时读取 Windows 已启用的 HTTP(S) 系统代理。入口不修改系统代理，也不主动重连或循环重试；首次下载／连接失败须报告。

首次构建可能需要下载新增依赖。Robolectric 的运行时 Android 测试包解析独立于 Gradle 依赖解析，缓存配置在工作区 `tools/robolectric-m2/`，并继承当前进程的 HTTP(S) 代理参数。完整离线单测还需要预先准备这些运行时测试包；不能将 Gradle 的 `--offline` 理解为对所有测试下载器强制断网。

`local.properties` 只记录本机 SDK 路径并已忽略。工具、缓存、测试下载包、模块 `build/`、APK 和本地签名密钥不入库；此阶段使用自动生成的 debug 签名。正式版须另行管理长期 Release 签名、SHA-256、签名证书指纹和覆盖升级验证。

## 真机连接

```powershell
./scripts/adb.ps1 devices -l
```

此入口仅转发指定 ADB 命令。应统一使用同一版本 Platform-Tools，避免 ADB 自身因服务版本不匹配而重启。本轮统一为 37.0.1，并保持已经建立的服务；若需要首次启动，则沿用这台 vivo 已验证可用的 USB 兼容后端。连接失败后不要调用 reconnect、kill/start 循环或反复枚举。始终用实际设备标识指定目标设备。

阶段 3／4 的本地开发和上述本机测试无需连接手机。安装应用、真实屏幕检查及阶段 5 系统能力测试属于单独的真机动作。使用情况访问由用户在系统设置中主动授予，通知权限单独申请；禁止通过 shell appops 或其他权限绕过正常流程。验证包的权限和后台设置不会自动赋予正式包。

能力验证结果与未覆盖项见 [STAGE2_RESULTS.md](STAGE2_RESULTS.md) 和 [STAGE2_THREAD_RETEST.md](STAGE2_THREAD_RETEST.md)。首版产品规则与工程决定见 [STAGE3_DECISIONS.md](STAGE3_DECISIONS.md)。验收记录不包含私人应用使用明细或设备序列号；临时后台例外不等于长期后台策略已决定。
