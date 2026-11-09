# StarLive · 七日星图

北斗七星，一周七天。先为一周留好期待，再慢慢点亮每一天。

StarLive 是一个个人周计划 Android 应用项目，用北斗七星呈现周一到周日的计划和完成进度。仓库包含原生 alpha、保留的 Web 交互原型，以及独立 Android 能力验证工具。

## 原生应用

`android/app` 使用 Kotlin／Jetpack Compose，包名为 `io.github.githuiy.starlive`，当前开发版本为 `0.1.0-alpha02`，包含以下功能。

- 星图与日计划、本周／下周草稿编辑、生活灵感和独立使用情况页面。
- 个人空计划默认、主动进入的隔离示例、完成状态合并和历史保留。
- 本地原子保存、提醒设置，以及校验／预览后覆盖的手动备份与恢复。
- 用户授权并开启后，读取所选应用的真实使用事件，按系统时区累计，通过可见前台服务提醒，支持 30 分钟稍后和重启后按已开启意图尝试恢复。

使用情况页面标记数据质量和监测状态。当前产物使用 debug 签名。测试通过项、vivo 后台和耗电的实测范围见 [阶段 5 记录](docs/STAGE5_RESULTS.md)。

准备好工作区工具后，在仓库根目录运行：

```powershell
./scripts/build-app.ps1
```

入口默认运行 `plan-core` 测试、应用本机单测、debug 构建及 Android Lint。产物为 `android/app/build/outputs/apk/debug/app-debug.apk`。构建方法和测试边界见 [构建说明](docs/BUILD.md)；规则来源和工程选择见 [阶段 3 决策](docs/STAGE3_DECISIONS.md)。[阶段 4 验收记录](docs/STAGE4_RESULTS.md)记载本轮 43 项通过测试、开发 APK、文件指纹及尚需真机验收的边界。

## Web 原型功能

- **一周星图**：切换星期，查看当天计划；完成小事，点亮对应的星。
- **周计划**：安排本周和下周，编辑草稿后确认，避免草稿直接覆盖首页。
- **生活灵感**：从家务、运动、学习、阅读、社交、探索、休息等活动中挑选，也可以添加自己的计划。
- **屏幕留白**：应用选择、提醒阈值和提醒弹窗的交互演示。
- 响应式布局、键盘操作和减少动态效果支持。

## Web 原型本地运行

Web 原型使用原生 HTML、CSS 和 JavaScript，没有第三方运行时依赖，也不需要安装 npm 包或构建。此说明仅针对 `dist/`，原生 Android 应用另有构建依赖。

在项目根目录运行：

```sh
python3 -m http.server 8000 --directory dist
```

然后打开 http://localhost:8000 。使用支持 SVG、`dialog` 和 `localStorage` 的现代浏览器。

## 文件结构

```text
dist/
  index.html   页面结构
  style.css    样式和响应式布局
  app.js       周计划、星图与演示交互
android/
  app/          原生 StarLive alpha
  plan-core/    可独立测试的计划与备份领域核心
  probe/        Android 系统能力验证包
  usagefixture/ 独立测试计时页
scripts/        工作区构建与诊断入口
docs/           需求、决策、构建和验收记录
```

`dist/` 中的文件为完整 Web 原型源代码，保留用于交互参考，可单独本地运行。

## Web 原型数据与边界

- 示例周计划和应用使用时长均为演示数据，不代表真实个人日程或设备记录。
- 自己的计划和设置只保存在当前浏览器的 `localStorage`，不会同步到其他设备。清除浏览器网站数据会删除这些本地记录。
- Web 原型没有账号系统、后端、云同步、分析脚本或外部 API 调用。
- “屏幕留白”只演示交互，不读取真实屏幕使用记录，不申请系统权限，也不提供后台通知或应用限制。

原生 alpha 的个人计划保存在应用本地文件中，与浏览器 `localStorage`、示例体验和两个验证包隔离。首版无账号、云同步或联网服务；新增原生运行依赖不改变这一产品范围。

## Web 原型检查

如果已安装 Node.js，可以检查 JavaScript 语法：

```sh
node --check dist/app.js
```

## 独立 Android 能力验证

`android/probe` 和 `android/usagefixture` 是 Java 能力验证应用及无权限测试计时页，用于检查目标手机的真实使用统计、通知及后台延迟。它们与正式应用包名独立；验证包的授权、数据或电池设置不代表正式应用已具备相同能力。

开发工具保存在仓库所在工作区的 `tools/`。能力验证包仍使用 `scripts/build-probe.ps1` 构建，详见 [验证应用说明](docs/ANDROID_PROBE.md)、[首轮真机结果](docs/STAGE2_RESULTS.md) 和 [后台策略对照记录](docs/STAGE2_THREAD_RETEST.md)。只在测试窗口首尾采集服务诊断，避免持续操作改变被测条件。短时实验不能代替正式应用的锁屏、重启、长期时效或耗电验收。

## 许可证

[MIT License](LICENSE)。第三方运行依赖、测试依赖及构建工具说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
