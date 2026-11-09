# 阶段 1 结果：原型基线、已确认规则与本机构建准备

记录日期为 2026-11-08（Asia/Shanghai）。

阶段 1 已完成原型基线核验、需求归档及 Android 最小能力验证工程的本机准备。**完整 StarLive Android 产品尚未实现；本记录不代表阶段 2 真机能力已通过，也不代表已交付正式签名 APK。**

## 原型基线

- 基线提交：`98ca38321402982c993816a3ebe9e9adb3146951`；MIT LICENSE 及原版权声明保留。
- 原有 `dist/index.html`、`dist/style.css`、`dist/app.js` 未修改，README 和 LICENSE 也未改动。
- `node --check dist/app.js` 通过。
- 本地 HTTP 的 HTML、CSS、JavaScript 三项资源均返回 200。
- 使用已有 Microsoft Edge、独立临时浏览器配置完成 **24／24 项检查**，涵盖起始星图、2500 ms 后进入今天、手动总览停留、星光完成／取消反馈、空计划基础亮度、草稿隔离、完成状态重载保存、灵感去重、本周／下周独立确认、四个独立页面、键盘和减少动态效果。
- 360、420、1280 CSS 像素宽度下未观察到页面横向溢出；查看了总览、聚焦和周计划编辑截图。没有未捕获浏览器异常，也没有页面发起的外部 HTTP(S) 资源请求。

详细范围、证据和局限见 [BASELINE.md](BASELINE.md)。这些检查不是目标真机触摸、字体放大或 Android 后台测试。

## 需求与本机策略检查

[REQUIREMENTS.md](REQUIREMENTS.md) 已记录本会话确认的正式规则，包括：

- 连续有效使用 60 分钟、每应用每日 60 分钟、所选应用合计每日 180 分钟。
- 切换监测应用接续；离开／锁屏 ≤120 秒保持会话关联，间隔不计时，超过 120 秒重开。
- 连续每会话一次、累计每日一次；同时触发合并；主动稍后 30 分钟，到期仍使用才提醒；关闭监测取消待发提醒。
- 本周／下周按天安排，草稿确认后生效；未完成保留原日灰色；跟随系统时区，午夜累计重新计数而连续会话接续；手动本地备份。

`android/logic-tests/ProbePolicyChecks.java` 的 **39 项本机纯 Java 断言通过**，覆盖验证包策略的阈值边界、每日去重、重启恢复、权限和监测开关、稍后到期与消费、停止取消、替换稍后请求、触发原因合并、跨日及防溢出状态处理。

注意：这些断言针对最小验证包的 **30 秒累计阈值和 30 秒稍后测试值**，不等于正式产品的三类阈值、连续会话／120 秒宽限或 30 分钟稍后已经全部实现和验收。

## Android 工程与构建入口

工作区已准备以下固定工具组合：

| 项目 | 版本／位置 |
| --- | --- |
| JDK | Temurin 17.0.21+9，`workspace/tools/jdk-17.0.21+9/` |
| Gradle | 8.11.1，`workspace/tools/gradle-8.11.1/` |
| Android Gradle Plugin | 8.9.2 |
| Android SDK | Platform 35、Build Tools 35.0.0，`workspace/tools/android-sdk/` |
| SDK 命令行工具 | 官方包编号 15859902，下载校验通过 |
| Platform-Tools | 37.0.1，`workspace/tools/platform-tools/` |
| 本地缓存 | `workspace/tools/gradle-user-home/`、`workspace/tools/android-user-home/` |

工具包按官方校验值检查。工程已生成 Gradle 8.11.1 Wrapper，`gradle-wrapper.properties` 固定发行版本并记录 `distributionSha256Sum`；Wrapper JAR 属于构建入口文件，不是应用 APK 或签名私钥。

验证工程含两个模块：

- `probe`：权限入口、受控统计、通知与用户主动启动的后台实验。
- `usagefixture`：不申请权限的受控前台计时页，供对比真实使用事件。

两个模块采用 `compileSdk/targetSdk 35`、`minSdk 26`。**目前只将 Android 15／API 35 作为目标验证环境**；配置了 minSdk 26 不表示 API 26–34 或其他系统已经实测。

`scripts/build-probe.ps1` 提供工作区工具、缓存、当前代理及任务入口；`scripts/adb.ps1` 转发单次 ADB 命令，保持本轮统一工具版本及已验证的兼容后端。没有增加网络重连或手机连接的循环。具体使用方式见 [BUILD.md](BUILD.md)，真机操作及测试值边界见 [ANDROID_PROBE.md](ANDROID_PROBE.md)。

## 构建结果及已处理问题

首次成功的完整构建输出为 `BUILD SUCCESSFUL`，共 86 个任务，完成：

- `:probe:assembleDebug`
- `:usagefixture:assembleDebug`
- `:probe:lintDebug`
- `:usagefixture:lintDebug`

这说明两个 debug APK 可编译打包、两模块 Android Lint 可完成；它不是正式签名发行版，也不能代替手机安装、授权、真实通知或后台可靠性验收。

诊断代码最后修改后，已再执行离线增量构建和两模块 Lint，结果为 `BUILD SUCCESSFUL`，耗时 15 秒：86 个任务中 18 个执行、68 个为最新状态。此轮复核用于确保最终验证包经过后续源码变更后的重新打包，不能只依据更早的一次成功记录判断产物已包含最新修改。

| 问题 | 处理及结论 |
| --- | --- |
| 初次依赖下载未自动沿用 Windows WinINET 当前系统代理，Gradle 直连超时 | 已报告；将当前启用的 HTTP(S) 代理显式传给本次 Gradle 进程后，依赖下载成功。没有更换代理或修改系统代理。 |
| Windows 批处理参数解析 | 已移除 `nonProxyHosts` 参数中的管道符，避免被 `cmd` 当作管道；修复后的脚本完成构建。 |
| SDK XML 版本 3／4 兼容警告 | 构建期间出现工具可识别 XML 版本与 SDK 元数据版本的兼容警告；本次未阻断编译、打包或 Lint。仍需记录工具兼容性，不能将警告表述为没有出现。 |
| 执行环境对普通子进程／本机检查有限制 | 原型浏览器检查首次未就绪并已报告，随后在授权执行环境完成本机检查；未将其解释为手机故障。 |

## 工作区与文件边界

- 项目源码、构建脚本和说明位于工作区项目目录；开发工具、下载缓存、Android 用户目录及本地调试文件均保留在工作区范围内。
- 原型检查报告和截图位于工作区的 `.starlive-baseline-check/`，在项目仓库之外。手机侧截图和本地诊断不进入公开源码。
- `local.properties`、Gradle 项目缓存、各模块 `build/`、APK／AAB、debug／正式签名密钥及本地日志已配置忽略；工具安装包和工具缓存不属于项目跟踪文件。
- 本阶段记录不包含设备序列号、用户私人应用使用明细、账号凭据或签名私钥。
- 没有因本机准备而修改系统环境变量、公开发布项目或推送远端。

## 仍需完成

1. 在已授权范围内执行阶段 2，分别记录安装、系统授权、受控真实统计、通知与后台行为；出现问题尤其手机问题立即报告。
2. 在真机能力结果基础上确定正式技术路线，迁移完整星图、周计划、三类提醒、会话边界和手动备份。
3. 完成跨日／跨周／时区与长时间运行、锁屏／省电／进程终止／重启及日常耗电验证，并量化提醒延迟和耗电门槛。
4. 最后构建并验证长期签名的 Release APK，提供版本、SHA-256、签名指纹、覆盖升级和可复现构建记录。

阶段 2 结果另记于 `STAGE2_RESULTS.md`；本文件不提前宣称该阶段通过。
