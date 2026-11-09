# Android 最小能力验证

本目录下的 Android 工程是阶段 1／2 的本机验证工具，不是正式版 StarLive。Web 星图原型仍保留在 `dist/`；正式产品界面及完整提醒规则尚未迁移。此文只描述实现和操作方法；实际真机结果以本轮验收记录为准。

当前 Probe 为 `0.2-probe`（versionCode 2），fixture 继续沿用 `0.1-fixture`。仅更新 Probe 即可保留既有系统授权与测试基线；权限和 debug 签名配置不变。

## 工程与权限

| 模块 | 包名／入口 | 用途 |
| --- | --- | --- |
| `probe` | `org.starlive.probe/.MainActivity` | 授权入口、受控使用统计、通知和用户主动启动的后台能力验证 |
| `usagefixture` | `org.starlive.usagefixture/.FixtureActivity` | 独立且不申请任何权限的测试计时页，生成已知的真实前台使用事件 |

验证工程使用 Java 平台界面，避免为系统能力实验引入界面框架依赖。正式产品是否采用 Kotlin／Jetpack Compose，仍在最小真机能力验证后确定。固定编译配置为 AGP 8.9.2、Gradle 8.11.1、JDK 17、compile/target SDK 35、Build Tools 35.0.0、min SDK 26；完整环境准备见 `BUILD.md`。

Probe 声明 `PACKAGE_USAGE_STATS`、`POST_NOTIFICATIONS`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`，并声明 `specialUse` 的具体测试用途。没有 INTERNET、无障碍、设备管理员、root、全量应用查询、精确闹钟、保持唤醒或开机自启权限。只查询明确的 fixture 包是否安装。系统使用事件仅在内存中处理；只展示并保存受控 fixture 的汇总数值，不保存其他应用名或原始使用事件。

## 构建与安装

在 `android/` 使用已配置的工作区工具环境运行：

```powershell
.\gradlew.bat :probe:assembleDebug :usagefixture:assembleDebug
```

产物分别在：

- `probe/build/outputs/apk/debug/probe-debug.apk`
- `usagefixture/build/outputs/apk/debug/usagefixture-debug.apk`

仅使用 debug 签名。安装操作由本轮真机验证执行者完成；首次侧载安装或系统安全提示按手机正常流程确认。不得用 `-g`、`pm grant`、`appops set` 等手段代替正常授权。

## 正常使用顺序

1. 打开“StarLive 能力验证”，确认显著的“非正式版”标识。
2. 点击“1 使用情况授权”，由用户在系统页面主动允许；回到应用后重新检查。拒绝或撤销时显示不可用，不显示成零。
3. 点击“2 允许通知”，使用系统通知授权流程；与使用情况权限分开。
4. 点击“发送测试通知”，检查通知是否出现、点击是否返回 Probe。计数只说明已提交给系统，实际送达必须看手机确认。
5. 点击“3 启动测试监测”。只有使用情况及通知可用时启动；常驻通知提供“停止测试”。
6. 点击“4 打开测试计时页”，在单窗口中停留已知时间，随后回 Probe 点击“刷新真实统计”。比较 fixture 自身的本次前台秒数与 UsageEvents 汇总的增加量。
7. 在 fixture 停留到测试阈值，检查后台提醒；再次超过阈值不应自动重复轰炸。
8. 从提醒或 Probe 中点击“测试稍后 30 秒”，在到期时让 fixture 保持前台，检查再次提醒；到期前离开 fixture 时应消费该请求而不再补发。停止监测取消待发稍后。
9. 完成后点击“停止测试监测”，确认常驻通知移除、诊断中的服务状态停止。不会随重启自动启动。

## 测试值与正式规则严格分开

| 项目 | 本验证包 | 正式产品已确认需求 |
| --- | --- | --- |
| 作用范围 | 仅受控 fixture | 用户选定的应用 |
| 阈值 | fixture 今日累计 30 秒，每日一次 | 连续 60 分钟、单应用每日 60 分钟、所选合计每日 180 分钟 |
| 稍后 | 30 秒，且到期仍在 fixture 前台才发 | 30 分钟，到期仍使用所选应用才发 |
| 连续会话／两分钟宽限 | 本验证包不实现、不得宣称已验收 | 切换所选应用接续；离开／锁屏不计时，120 秒内返回接续 |

v0.2 的查询、策略计算、稍后操作、停止清理和诊断文件 I/O 均使用同一个 `starlive-probe-worker` HandlerThread 串行队列；Activity 的 `onResume` 只提交异步读取，主线程仅渲染权限快照和界面。查询范围及事件算法保留 v0.1，便于比较线程迁移的影响。下一次轮询仍从本次工作完成后约 10 秒安排，不持有唤醒锁，不请求忽略省电，不保证后台准时。锁屏、省电、重启、进程终止及 vivo 后台策略的影响必须实测。USB 供电中的结果不能作为日常耗电验收。

停止请求先使当前运行代次及待启动代次失效，再在串行队列清理稍后状态；查询返回后必须重新核验代次，旧任务不能再提交通知。最终提交通路有互斥门控：停止可能等待已经开始的系统提交通路完成，无法撤回系统已接受的通知。门控内不做 Usage 查询或诊断文件 I/O。通知广播仅排队，不使用 `goAsync` 长时间等待共享队列。

## 安全诊断与自动化入口

按钮的内容描述为：

- `probe.open.usage`
- `probe.request.notifications`
- `probe.start.monitor`
- `probe.stop.monitor`
- `probe.open.fixture`
- `probe.refresh`
- `probe.test.notification`
- `probe.test.snooze`

独立 fixture 可通过其导出 Activity 启动；Probe 不接受后台广播自动开始监测，须从界面显式启动。以下命令只读取我们自己新建的 debug 应用私有诊断，不读取其他应用数据：

```text
adb -s <当前已验证的设备标识> shell run-as org.starlive.probe cat files/probe-state.json
```

该 JSON 含权限及通知渠道状态、服务生命周期、最后心跳与 elapsed 时间、轮询计数、fixture 汇总时长／前台状态／事件计数、统计快照时间及年龄、测试策略的触发与去重计数、系统调用成功的通知提交计数、稍后到期时间和短错误说明。v0.2 同时记录 schema、版本、运行标识、写入来源及 worker 名称。缺失数据使用 JSON null。没有其他应用包名、聊天内容、用户使用目的或原始事件。`notification_count` 是策略生成次数；`posted_notification_count` 是提交系统的次数，两者都不代替手机侧通知送达验证。前台标志描述上次统计快照，不宣称实时状态；界面显示快照年龄。

整个 JSON 都是 `generated_at` 时刻的快照。系统杀进程后文件不会自行变化；不能只凭其中 `service_running=true` 或写入时的 age=0 判断服务仍然存活。后台测试须比较两次采样的 `loop_count`／`last_heartbeat_at` 是否推进，结合当前时间及系统中我们自己服务的实际状态判断。

## v0.2 服务独占诊断

后台回归应读取新的 `files/service-state.json`。只有监测服务相关代码能写它，Activity 刷新通用 JSON 不会刷新这个文件，也不会伪造服务心跳。

```text
adb -s <当前已验证的设备标识> shell run-as org.starlive.probe cat files/service-state.json
```

关键字段：

| 字段 | 含义 |
| --- | --- |
| `schema_version=2`、`probe_version`、`run_id` | 绑定版本和一次实际启动的监测运行；新启动产生新 UUID |
| `source=service_only`、`worker_thread`、`process_id` | 写入来源与线程／进程身份 |
| `phase`、`phase_at` | 最新阶段，如 `tick_enter`、`query_start`、`query_end`、`notify_ready`、`tick_end`、`waiting_for_next_tick`、`stopped` |
| `generated_at`、各阶段时间对象 | 每个对象都有 `wall`、`elapsed`、`uptime` 毫秒值 |
| `tick_enter_count`／`tick_end_count` | 本次运行中已经进入／完整结束的轮询计数 |
| `expected_next_uptime`／`expected_next_elapsed` | 下一次计划回调的两个单调时钟时间 |
| `recent_ticks` | 最近最多 32 条；进入即加入，包含当前未完成条目，不只保存完成条目 |
| `uptime_jitter_ms`／`elapsed_jitter_ms`／`extra_sleep_gap_ms` | 回调相对计划时间的两种延迟，以及 elapsed 比 uptime 多出的间隔；帮助区分深睡与工作线程调度迟到 |
| `query_start`／`query_end`／`query_duration_ms` | 查询前、查询后的阶段及实际调用耗时；写入 marker 的开销不混入查询耗时 |
| `power` | 亮屏、系统省电和 idle 状态；每轮进入 marker 落盘后再查询，另有 `power_check_start/end` |
| `previous_write_duration_elapsed_ms` | 上一次服务诊断写入实际耗时；每次写入无法把自己的结束时间写入自身，所以下个 marker 暴露它 |
| `current_write_started_elapsed`／`previous_write_finished_elapsed` | 当前及上次诊断 I/O 的边界 |
| `usage_available`、`fixture_today_millis`、`fixture_foreground`、`fixture_complete` | 受控 fixture 的最近统计及完整性，另有 `last_usage_observed_at` |
| `notification_count`／`posted_notification_count`／`snooze_submission_count` | 应用私有状态中的累计决策／系统提交／稍后提交计数；`run_` 前缀的同名字段表示本次运行的增量 |
| `last_snooze` | 本次最近的稍后请求；每次排程产生新的 `request_id`，并记录 `scheduled_at`、`planned_due_wall/elapsed`、`decision_at`、`submission_at`、延迟和 outcome |

`submission_at` 是系统调用返回后的时刻，表示提交成功与否，不是用户看到横幅的时刻。每个 tick 另存 `notify_call_started` 和调用耗时。整个文件仍是快照；需要检查时间推进、运行标识、进入／结束计数和环形记录覆盖，不能仅凭一个新文件判断后台可靠。

建议首轮对照保留相同手机授权和后台条件，在 fixture 亮屏前台停留约 180 秒，中间不执行 ADB、不打开 Probe；首尾各取一次服务专属快照并由用户报告通知出现时机。非深睡下相邻 tick 间隔及稍后提交延迟的观察线由本轮测试记录给出，不作为长期实时承诺。

前台时长按系统时区当天零点切分，采用 fixture 的 resumed／paused 事件配对，屏幕关闭或设备关机时保守截断；缺失配对显示不完整，不补造时间。只面向单窗口受控实验，不保证与系统数字健康一致，也不宣称已支持分屏或所有异常事件。

## 本机逻辑检查

`logic-tests/ProbePolicyChecks.java` 为无第三方依赖的纯 Java 检查程序，覆盖 30 秒边界、每日去重、持久状态恢复、稍后到期条件、停止取消和多个原因合并。编译运行方法见该文件注释。实际 Android 构建、系统授权、真实通知、后台时效及耗电仍需分别验证。

v0.2 另有 `logic-tests/ProbeRuntimeChecks.java`，验证单调时钟差异、边界／溢出，以及真正跨线程排队任务在停止后不能进入最终提交通路的顺序。两套检查各 39 项；它们验证纯 Java 逻辑，不替代 Android／OriginOS 调度和通知送达测试。
