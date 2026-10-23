# [归档] 端侧情境感知与个性化工程技术方案 v1

> 本文档记录已完成的 Phase A/B 工程闭环，于 2026-10-23 归档。当前 Android 方向见 `docs/android_technical_plan.md`。

| 项 | 内容 |
|---|---|
| 工程 | `sensor_context_engine/android` |
| 目标设备 | vivo V2547A / Android 16（API 36）/ arm64-v8a |
| 语言 | Kotlin（业务与传感器管线）+ C++（后续 QNN 热路径） |
| 首版形态 | 单应用、单进程、前台服务、纯端侧闭环 |
| 文档版本 | v1.0 |

## 0. 实施状态

截至 2026-10-22，**Phase A 已完成并在 V2547A 真机验证**：

- API 36 debug APK 构建、安装和启动通过；
- health foreground service 在授权后正常运行；
- 实际注册到 `lsm6dsvx Accelerometer Non-wakeup`、`tcs3743 Ambient Light Sensor Non-wakeup` 和 `tcs3743 Proximity Sensor Wakeup`；
- 25 Hz accelerometer + 5 s batching 注册成功；
- 真实传感器窗口、Stage 0、启发式情境输出和 UI 更新通过；
- 模拟暗光手持情境可产生建议通知；
- 通知“接受”操作可更新并持久化策略统计，JSONL trace 可完整回放；
- 8 个 Android/JVM 单元测试、4 个 Python 单元测试、Android Lint 和 `assembleDebug` 全部通过。

真机发现：tcs3743 的 ambient light 虽声明为 on-change，但微小照度波动会产生约 5 Hz 回调。实现已禁止每个 light callback 强制唤醒 Stage 1，改为约 4.5 s 节流并只在明暗分桶改变时放行。是否在息屏 Stage 0 中长期注册该 non-wakeup light，仍需在 Phase D 通过功耗 A/B 决定。

Phase B 工程能力也已于 2026-10-22 完成首轮 round-trip：

- `SCELOG v1` 流式二进制写入、干净结束帧与异常文件恢复；
- 独立采集前台服务、固定 session 标签、事件打点、文件列表；
- 枚举本机 56 个传感器，首轮实际激活 14 个流；
- ADB 导出、Python 解析、25 Hz 对齐、4 s 切窗和快速价值报告；
- 采集 session 默认 45 s 自动干净停止，评测自动裁掉首尾各 3 s；
- 真机连续样本验证：66.8 s 内写入 11,999 个 sensor event + 1 个 marker，accel/gyro 约 50 Hz、mag 约 20 Hz，最大 accel 间隔 26.4 ms，干净结束；
- 发现 vivo 默认“智能控制后台耗电”会冻结 FGS：失败 session 出现 49–246 s 空洞。改为“允许后台耗电”后息屏/后台连续性恢复；评测脚本会自动拒绝此类坏 session。

当前仍需用户按 `docs/rapid_validation_protocol.md` 完成多姿态、多活动采集，才能形成第一份可评分的 go/no-go 结论。

## 1. 目标与边界

### 1.1 工程目标

在目标手机上交付一条可运行、可观测、可替换模型的端侧链路：

```text
SensorManager
  -> 多传感器缓存与时间窗
  -> Stage 0 变化门控
  -> Stage 1 情境推理
  -> 状态平滑与置信度
  -> 个性化策略
  -> 建议/动作
  -> 用户反馈
  -> 本地偏好更新
```

首版必须证明两件事：

1. 传感器数据能够在息屏场景下持续进入统一情境接口；
2. 同一情境会因用户历史反馈产生不同决策，形成完整个性化闭环。

### 1.2 首版非目标

- 不在首版在线训练或微调情境模型；
- 不在首版静默修改系统关键设置；
- 不将 root 权限作为产品依赖；
- 不保存原始 IMU 长时数据；
- 不以启发式分类准确率作为最终算法结论。

启发式推理仅用于打通工程，后续由 TFLite/QNN 实现替换，接口保持不变。

## 2. 核心设计原则

1. **采集和模型解耦**：模型不直接访问 `SensorManager`，只消费标准 `SensorWindow`。
2. **情境和体验解耦**：模型只输出 `ContextSnapshot`，不直接操作 UI 或系统能力。
3. **规则先行、学习后置**：先用可解释策略验证体验，再学习用户阈值或排序。
4. **默认建议、授权自动化**：冷启动阶段只建议；自动执行必须由用户明确开启。
5. **端侧隐私优先**：原始传感器默认只驻留内存，偏好与决策日志保存在应用私有目录。
6. **全链路可审计**：每次推理、策略命中和反馈均能通过 `decision_trace` 还原。

## 3. 运行时架构

### 3.1 Android 组件

| 组件 | 职责 |
|---|---|
| `MainActivity` | 权限申请、服务控制、实时状态和反馈统计 |
| `SensorCollectorService` | 前台服务；注册传感器、维护窗口、驱动门控和推理 |
| `SensorWindowBuffer` | 按 `elapsedRealtimeNanos` 保存有限时长样本，不落原始数据 |
| `ChangeGate` | 使用运动强度、光照分桶、接近状态和心跳判断是否运行 Stage 1 |
| `ContextInferenceEngine` | 稳定模型接口；首版为启发式实现，后续替换为 QNN/TFLite |
| `ContextStabilizer` | 连续确认、滞回和有效期管理，降低状态抖动 |
| `PersonalizationEngine` | 根据情境、策略统计、冷却期生成体验决策 |
| `PolicyStatsStore` | 保存展示、接受、拒绝、撤销和最后展示时间 |
| `NotificationController` | 常驻状态通知及个性化建议通知 |
| `FeedbackReceiver` | 接收通知按钮反馈并更新用户偏好 |
| `TraceLogger` | 输出脱敏 JSONL 决策链路日志 |

### 3.2 线程模型

- 主线程：Activity 与系统组件生命周期；
- `HandlerThread("sensor-pipeline")`：传感器回调、窗口更新、Stage 0 和首版推理；
- 首版推理必须在 10 ms 量级，不阻塞传感器线程；
- 接入 QNN 后新增单线程推理执行器，窗口以不可变快照跨线程传递；
- 文件日志采用短追加写，后续数据采集模式再引入独立 I/O 队列。

## 4. 传感器策略

### 4.1 首版接入

| 传感器 | 参数 | 用途 |
|---|---|---|
| Accelerometer | 25 Hz，5 s batching | 活动强度、平放方向、Stage 0 门控 |
| Ambient light | on-change | 明暗情境 |
| Proximity | on-change / wakeup（设备支持时） | 口袋、遮挡证据及门控 |

后续按需加入 gyro 50 Hz、mag 20 Hz、CCT、step counter 和 vivo 自定义事件。Stage 0 不常开 gyro。

### 4.2 时间与窗口

- 唯一时序时钟：传感器事件 `timestamp` / `elapsedRealtimeNanos`；
- 推理窗口：最近 4 s；
- 最少样本：25 个 accelerometer 样本；
- Stage 0 最短决策间隔：2 s；
- 正常批处理间隔：约 5 s；
- 兜底心跳：60 s；
- 原始样本只保留最近 10 s。

### 4.3 降级

- accelerometer 缺失：服务报告不可运行，不产生伪情境；
- light/proximity 缺失：对应字段为 unknown，策略不得把 unknown 当作否定值；
- batching 注册失败：退回无 batching 注册并记录能力差异；
- 自定义 vivo sensor 只能动态发现，不以固定 type ID 作为唯一依据。

## 5. 数据契约

### 5.1 `SensorWindow`

```text
windowStartElapsedNs
windowEndElapsedNs
acceleration: List<(timestamp, x, y, z)>
latestLux: value + timestamp | null
latestProximity: value + maxRange + timestamp | null
```

### 5.2 `ContextSnapshot`

```text
timestamp / validUntil
activity: label + confidence
carryPosture: label + confidence
environment.light: label + confidence + lux
interactionEvents: event list
source: HEURISTIC | TFLITE | QNN | SIMULATED
```

所有未知值必须显式使用 `UNKNOWN`，禁止用零概率或默认标签掩盖缺失数据。

### 5.3 `ExperienceDecision`

```text
decisionId
policyId
actionType
title / explanation
confidence
createdAt
sourceContext
```

每条建议都必须能解释：哪个策略、哪些情境证据、为什么此时触发。

## 6. 首个个性化场景

### 6.1 暗光手持阅读辅助

首版命中条件：

- activity = `STILL`；
- carry posture = `IN_HAND`；
- light level = `DIM` 或 `DARK`；
- 三项置信度达到策略阈值；
- 不在冷却期；
- 用户未因连续拒绝被抑制。

首版动作是通知建议，不静默修改全局亮度。后续动作分级：

1. `SUGGEST_ONLY`：只展示建议；
2. `ASK_EVERY_TIME`：用户点击后执行；
3. `AUTO_APPLY`：用户明确授权且策略接受率达标后自动执行。

### 6.2 个性化更新

每个策略维护：`shown / accepted / dismissed / reverted / lastShownAt`。

- 样本少于 5 次时始终处于探索/建议阶段；
- 无接受且连续累计 3 次拒绝时暂停建议；
- 自动化不能只由接受率开启，必须额外取得显式授权；
- 用户撤销自动动作计为强负反馈；
- 调试注入绕过冷却期，但仍在日志中标记 `SIMULATED`。

首版只学习决策偏好，不在线更新情境神经网络，从而避免灾难性遗忘和错误标签自强化。

## 7. 权限、后台与安全

### 7.1 权限

- `FOREGROUND_SERVICE`；
- `FOREGROUND_SERVICE_HEALTH`；
- `ACTIVITY_RECOGNITION`（运行时申请，同时满足 health FGS 前置条件）；
- `POST_NOTIFICATIONS`（Android 13+）；
- 首版不申请位置、麦克风、相机和联网权限。

### 7.2 后台策略

- 用户从 Activity 明确启动前台服务；
- 不使用永久 wakelock；
- 优先依赖 sensor FIFO/batching 和 wakeup sensor；
- 开发机可通过 ADB 加入电池白名单，但代码不得依赖 root；
- 自动开机启动留到稳定性阶段，并单独验证 Android 16 的 FGS 限制。

### 7.3 数据与隐私

- 正常运行不落原始传感器；
- 日志只保存派生标签、置信度、策略理由和反馈；
- 日志轮转上限 5 MiB；
- 所有文件位于 app-private storage；
- App 提供偏好重置，后续增加日志导出与一键删除。

## 8. 模型接入方案

### 8.1 稳定接口

```kotlin
interface ContextInferenceEngine {
    fun infer(window: SensorWindow): ContextSnapshot
}
```

依次实现：

1. `HeuristicContextInferenceEngine`：仅用于工程联调；
2. `TfliteContextInferenceEngine`：先验证张量、量化和端侧精度；
3. `QnnContextInferenceEngine`：JNI/C++ 加载 context binary，运行 HTP 后端；
4. 通过 build flavor 或运行时配置选择后端，输出契约不变。

### 8.2 QNN 边界

- Kotlin 负责传感器和窗口；
- JNI 只接收连续 `FloatBuffer` 和元数据；
- C++ 负责 QNN lifecycle、buffer binding 和推理；
- 小模型必须实测 CPU 与 HTP 的唤醒开销，不能预设 HTP 一定更省电；
- context binary、QNN runtime 版本和 HTP skel 必须写入构建/部署清单。

## 9. 可观测性与验收

### 9.1 决策日志

JSONL 至少包含：

- 服务启停与传感器能力；
- Gate 是否放行及原因；
- 情境结果、置信度和来源；
- 策略命中或被抑制原因；
- 用户反馈和更新后的统计。

### 9.2 首个垂直闭环 Definition of Done

- Debug APK 可在 V2547A 安装启动；
- 用户授权后前台服务正常运行；
- 实时页面能显示传感器推导的情境；
- 可通过调试入口稳定注入“暗光手持静止”情境；
- 注入后出现带接受/拒绝按钮的建议通知；
- 反馈能够持久化，重启 App 后统计仍存在；
- 连续拒绝 3 次后策略被抑制；
- `decision_trace.jsonl` 能完整还原上述过程；
- 单元测试覆盖首个策略、冷却/抑制和 Stage 0 基本行为；
- `assembleDebug` 与 `testDebugUnitTest` 通过。

## 10. 分阶段执行计划

### Phase A：工程地基与闭环（本轮）

- Android 工程、权限和前台服务；
- accelerometer/light/proximity 采集与内存窗口；
- Stage 0 门控、启发式推理、状态平滑；
- 暗光阅读策略、建议通知、反馈持久化；
- 调试页面、模拟情境、JSONL trace；
- JVM 单元测试、构建和真机 smoke test。

### Phase B：数据采集模式

- 全传感器能力枚举；
- protobuf/flatbuffer 二进制采集；
- 人工打标、session 管理、ADB 导出；
- 对齐规格与 Python 侧一致性测试。

### Phase C：真实模型

- TFLite INT8 首次接入；
- 四头输出映射、置信度校准；
- QNN/HTP JNI 后端及 CPU/HTP A/B；
- 模型版本、回滚和兼容性机制。

### Phase D：低功耗与更多体验

- 硬件 wakeup sensor、vivo 事件流和 Stage 0 模型；
- 观察期/回睡状态机；
- 通知降扰、运动模式等新策略；
- 受控放电、误触发率和用户接受率联合评测。

## 11. 计划中的工程结构

```text
android/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/sensorcontext/engine/
│   │   ├── context/
│   │   ├── inference/
│   │   ├── notification/
│   │   ├── personalization/
│   │   ├── sensor/
│   │   └── storage/
│   └── res/
└── app/src/test/
```

技术方案以接口边界为冻结项；启发式阈值、模型后端和体验策略均允许通过实验迭代。
