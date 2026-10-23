# Android 质量感知多传感器融合工程方案 v2

| 项 | 内容 |
|---|---|
| 目标设备 | vivo V2547A / Android 16 / arm64-v8a |
| 主算法 | Activity + Carry 的质量感知门控融合 |
| 下游 | 情境化 Top-3 App 推荐组件 |
| 隐私边界 | 无麦克风、相机、GPS；融合默认不落原始数据 |
| 状态 | v2 已对齐；F0 主机侧完成，等待新版 APK 真机重装与 P0 pilot |

## 1. 已完成且继续复用

- Android 工程、前台服务、权限和通知基建；
- accel/light/proximity 实时链路与 Stage 0 原型；
- `SCELOG v1` 全传感器采集服务；
- session 标签、marker、45 s 自动停止；
- ADB 导出、Python parser、25 Hz alignment；
- 真机 50 Hz accel/gyro、20 Hz mag 连续性验证；
- JSONL trace、单元测试与 lint；
- vivo 后台冻结诊断与设置入口。

暗光阅读建议保留为 legacy smoke test，不再是产品主线。

## 2. 新运行时架构

```text
SensorCollectorService
        ↓
MultiRateWindowAssembler
        ├── ImuWindow
        ├── EnvironmentSnapshot
        ├── EventHistory
        └── ModalityQuality
        ↓
FusionInferenceEngine
        ├── Heuristic baseline
        ├── TFLite backend
        └── QNN backend
        ↓
TemporalContextFilter
        ↓
PhysicalContextRepository
        ├── Debug dashboard
        ├── Trace logger
        └── AppRecommendationEngine（F6）
```

## 3. Android 模块调整

```text
com.sensorcontext.engine/
├── collection/              # 已有明确采集模式
├── sensor/
│   ├── MultiRateWindowAssembler
│   ├── SensorCapabilityRegistry
│   └── quality/
│       ├── SamplingQualityEstimator
│       ├── MagneticInterferenceDetector
│       └── FreshnessTracker
├── fusion/
│   ├── FusionInput
│   ├── FusionOutput
│   ├── FusionInferenceEngine
│   ├── HeuristicFusionBaseline
│   └── backend/             # TFLite / QNN
├── context/
│   ├── PhysicalContextSnapshot
│   └── TemporalContextFilter
├── recommendation/          # F6 后实现
│   ├── UsageEventCollector
│   ├── HabitModel
│   └── AppRecommendationWidget
└── storage/
```

现有 `inference/` 不立即删除；先通过适配器迁移，避免破坏已通过的真机闭环。

## 4. 新数据契约

### 4.1 `ModalityQuality`

```kotlin
data class ModalityQuality(
    val available: Boolean,
    val sampleRatio: Float,
    val maxGapMillis: Float,
    val freshnessMillis: Long,
    val saturationRatio: Float,
    val interferenceScore: Float,
)
```

### 4.2 `FusionInput`

```text
windowStart / windowEnd
imu[C,T] + imuQuality
environment[lux,cct,proximity] + environmentQuality
events[stepRate,timeSinceMotion,vendorBits] + eventQuality
valid masks
```

### 4.3 `PhysicalContextSnapshot`

```text
activity probabilities
carry probabilities
auxiliary light state
per-modality gate weights
overall confidence
isRejected
source/modelVersion
timestamp/validUntil
quality summary
```

对外输出 gate weight 和 quality，是为了调试、解释和证明模型确实在融合，而不是黑盒拼接。

## 5. 采样策略

### Stage 0

| 传感器 | 策略 |
|---|---|
| accel | 25 Hz，5 s batching |
| proximity | on-change / wakeup |
| step/significant motion | hardware event |
| light | 首轮 A/B：持续注册 vs Stage 1 临时注册 |

### Stage 1

| 传感器 | 策略 |
|---|---|
| accel/gyro | 50 Hz，模型侧对齐到 25 Hz |
| mag uncalibrated | 20 Hz + 场强干扰分数 |
| gravity | 系统输出或自研融合对照 |
| light/CCT/proximity | on-change，携带 freshness |
| step/vendor | 事件历史与距上次事件时间 |

Stage 1 窗口默认 4 s，步长 2 s；Android 和 Python 共同遵循 `docs/alignment_spec.md`。

## 6. 后端接口

```kotlin
interface FusionInferenceEngine {
    fun infer(input: FusionInput): FusionOutput
}
```

实施顺序：

1. `HeuristicFusionBaseline`；
2. Python 导出的 TFLite INT8；
3. ONNX → QNN CPU/HTP；
4. 同一 golden window 跨 Python/TFLite/QNN 一致性测试。

小模型不预设 HTP 更省电；必须测量图加载、唤醒和单窗能耗。

## 7. 质量与故障注入

Android 调试模式和 Python replay 必须支持：

- drop gyro；
- drop environment branch；
- light/proximity timestamp 人为变旧；
- mag 加固定偏置或标为 interference；
- accel 采样随机丢失；
- 插入 0.2–2 s gap；
- rate 从 50 Hz 漂移到 25/40 Hz。

每次推理 trace：

```text
input quality
branch availability
gate weights
raw head probabilities
temporal filtered result
latency/backend/model version
```

## 8. App 推荐阶段边界

F1–F5 不申请 Usage Access。只有融合算法达到验收标准后，F6 才增加：

- `PACKAGE_USAGE_STATS` 特殊授权引导；
- `UsageStatsManager.queryEvents`；
- 仅保存 package、timestamp、前态和 ContextSnapshot；
- `<queries>` 只声明 launcher intent，不申请 `QUERY_ALL_PACKAGES`；
- 默认排除本 App、launcher、系统 UI；用户可排除敏感 App；
- 首版用桌面 widget，不用推荐通知。

LLM 不进入推荐热路径。

## 9. 实施顺序

### F0：当前任务

- [x] 冻结 fusion spec；
- [x] 更新 collection taxonomy 和标签组合校验；
- [x] 更新 Python alignment age、schema、quality 和 dataset exporter；
- [x] 建立 Android `FusionInput` / quality contract；
- [x] 建立单模态与融合实验目录；
- [ ] 新版 APK 真机重装（当前 ADB 设备断开）；
- [ ] 按 v2 协议完成 P0 反事实 pilot。

F0 主机验收：Android/JVM 12/12、Python 10/10、`lintDebug`、`assembleDebug` 全部通过；旧真机数据已成功导出 schema `52a7299a08ae8313` 的融合窗口。

### F1：单模态基线

- accel-only；
- IMU-only；
- environment-only carry baseline；
- handcrafted feature baseline；
- session-level evaluation。

### F2–F4：融合算法

- early/late；
- quality gate + modality dropout；
- temporal filtering + calibration；
- clean/corrupted/missing ablation。

### F5：部署

- TFLite/QNN；
- INT8；
- CPU/HTP latency/power。

### F6：价值 Demo

- UsageStats collector；
- next-app baselines；
- physical context incremental test；
- Top-3 widget。

## 10. 当前开工门槛

以下条件满足后才能进入模型代码：

- `docs/fusion_algorithm_spec.md` 完成；
- 新采集矩阵完成至少一个 participant 的 pilot；
- 每个主标签至少两个独立 session；
- 数据质量 PASS，且活动/姿态存在交叉；
- 单模态 baseline 的 split 和指标冻结。

这能避免在存在标签捷径的数据上过早开发复杂网络。
