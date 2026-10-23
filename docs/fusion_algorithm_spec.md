# 质量感知多率传感器融合算法规格 v1

> 本文是训练侧、Android 推理侧和评测侧的算法单一事实来源。未经消融结果支持，不扩充标签或模型复杂度。

## 1. 目标

给定最近 4 s 的异步多传感器数据，联合估计：

```text
Activity      = still / walking / running / in_vehicle
Carry Posture = desk_faceup / desk_facedown / in_hand / in_pocket / in_bag
```

并输出：

- 每个类别概率；
- overall confidence 与 reject 标记；
- 每个模态的 gate weight；
- 输入质量摘要；
- 模型版本与有效期。

模型重点不是扩大标签，而是回答：

1. 哪个模态在当前窗口可信？
2. 某个模态缺失/过期/受干扰时如何降权？
3. 多模态是否提供最佳单模态无法得到的增量？

## 2. 记号

```text
X_imu   ∈ R^(C×T)      # 25 Hz grid，T=100
X_env   ∈ R^E          # 窗口级慢变量和统计
X_event ∈ R^V          # 事件计数、bit、time-since-event
Q_m     ∈ R^K          # 模态质量向量
M_m     ∈ {0,1}        # 模态可用 mask
z_a                    # activity
z_c                    # carry posture
```

目标后验：

```text
P(z_a, z_c | X_imu, X_env, X_event, Q, history)
```

首版使用共享 fused representation + 两个分类头，不把 Activity×Carry 展开成 20 个联合类别，以避免数据稀疏。

## 3. 输入定义

### 3.1 统一窗口

- grid：25 Hz；
- window：4 s / 100 点；
- stride：2 s / 50 点；
- 左闭右开；
- 首尾采集交互各裁掉 3 s；
- 所有通道同时提供 valid mask；
- 详细插值规则遵循 `docs/alignment_spec.md`。

### 3.2 Motion branch

#### 连续通道

```text
accel x/y/z
gyro x/y/z
mag_uncal x/y/z
gravity or fused pitch/roll
```

首版训练必须支持以下通道配置：

| 配置 | 用途 |
|---|---|
| accel only | 最低成本 baseline |
| accel + gyro | IMU baseline |
| accel + gyro + mag | 磁信息增量与干扰实验 |
| accel + gyro + orientation | 使用 EKF/系统 gravity 的 hybrid 输入 |

#### Motion quality

```text
sample_ratio
median_period_ms
max_gap_ms
timestamp_disorder_count
saturation_ratio
accel_norm_outlier_ratio
orientation_flatness
orientation_stability
gyro_available
mag_available
mag_field_mean_uT
mag_field_deviation_score
```

磁干扰分数基于窗口场强相对设备校准范围的偏离和短时突变；具体阈值从采集分布确定，不在规格中硬编码为真理。

### 3.3 Environment branch

窗口级输入：

```text
log1p(lux)
cct / 10000
proximity distance
proximity near bit
lux delta / stability
```

质量输入：

```text
lux_available / cct_available / prox_available
lux_age_ms / cct_age_ms / prox_age_ms
lux_change_count
prox_change_count
sensor_is_wakeup
```

on-change 值采用 forward-fill，但 freshness 必须显式输入。过期数据不允许与新数据同权。

### 3.4 Event branch

```text
step_count_delta
step_rate
time_since_step_ms
significant_motion bit + age
tilt bit + age
vendor_raiseup bit + age
vendor_putdown bit + age
vendor_car bit + age
vendor_activity logits/events + age
permission / registration availability bits
```

vendor detector 仅作为可选输入和弱标签，不允许成为测试集真值。

## 4. 信号级融合模块

### 4.1 姿态基线

实现两条对照：

1. 系统 `gravity / rotation_vector`；
2. 自研 complementary filter，随后可选 EKF。

直觉：

- gyro 高频变化可信，长期积分漂移；
- accel 低频重力可信，运动时污染；
- mag 仅辅助 yaw，并按干扰分数降权。

首版主任务主要使用 pitch/roll/flatness，不以室内 yaw 精度作为项目成败指标。

### 4.2 步态耦合特征

用于 `in_pocket` vs `in_bag`：

- accel magnitude autocorrelation peak；
- dominant cadence；
- axis-to-magnitude coherence；
- step event 与 IMU 峰值时间差；
- 周期间幅值稳定性。

该组特征作为传统 baseline 和 Environment/Event branch 辅助输入；深度模型仍接收原始窗口。

## 5. 必做 Baselines

### B0：多数类与规则

- majority per head；
- desk/pocket 的手工规则；
- 用于发现标签泄漏和数据异常。

### B1：手工特征 + 传统模型

特征：mean/std/MAD、频带能量、谱熵、主频、自相关、方向统计、lux/prox/step。

候选模型：logistic regression、random forest 或 GBDT。最终至少保留一个可部署、可解释 baseline。

### B2：Accel-only TCN

证明单一运动模态上限。

### B3：IMU-only TCN

accel + gyro，mag 单独做增量实验。

### B4：Early Fusion

将慢变量重复到时间网格后与 IMU concat。必须保留 freshness/mask，否则该 baseline 不公平且会静默填零。

### B5：Fixed Late Fusion

各分支独立输出 logits，以验证集固定权重融合：

```text
logits = w_imu*l_imu + w_env*l_env + w_event*l_event
```

权重不依赖当前窗口质量。

## 6. 主模型：Quality-Aware Gated Fusion

### 6.1 模态编码器

#### IMU encoder

首版建议轻量 TCN：

```text
Conv1d stem
→ depthwise-separable residual blocks
   dilation = 1, 2, 4, 8
→ masked mean + masked std pooling
→ h_imu (64d)
```

#### Environment encoder

```text
normalized env values + freshness + masks
→ MLP 32 → 16
→ h_env (16d)
```

#### Event encoder

```text
event bits + counts + log1p(time-since-event) + availability
→ MLP 32 → 16
→ h_event (16d)
```

首版总参数目标 `<250K`。参数预算优先给 IMU encoder，不为追求模型规模增加层数。

### 6.2 质量门控

每个分支独立产生门值：

```text
g_m = sigmoid(MLP(concat(h_m, Q_m, M_m)))
```

融合：

```text
h_fused = MLP(concat(
    g_imu   * P_imu(h_imu),
    g_env   * P_env(h_env),
    g_event * P_event(h_event),
    Q_global
))
```

使用独立 sigmoid 而非 softmax，允许多个互补模态同时高权重，也允许所有低质量模态同时低权重并触发 reject。

### 6.3 输出头

```text
activity_logits = HeadActivity(h_fused)
carry_logits    = HeadCarry(h_fused)
light_logits    = HeadLight(h_env)       # auxiliary
branch_logits   = Head_m(h_m)            # training/debug only
```

### 6.4 防止 gate collapse

- branch auxiliary loss；
- modality dropout；
- 记录每类/每质量切片 gate 分布；
- 如果一个 gate 长期 >0.95、其他长期 <0.05，必须检查标签捷径；
- 不强行要求平均等权，真实可靠性差异应保留。

## 7. 训练目标

```text
L = 1.0 * CE(activity)
  + 1.0 * CE(carry)
  + 0.2 * CE(light_aux)
  + 0.2 * Σ available_branch_CE
```

第一版固定权重，确认梯度和标签质量后再考虑 uncertainty weighting / GradNorm。

类别不均衡处理顺序：

1. session-level balanced sampler；
2. class weight；
3. 只有仍严重偏斜时才考虑 focal loss。

禁止先用 focal loss 掩盖采集矩阵失衡。

## 8. 缺失与退化训练

### 8.1 Modality dropout

训练阶段随机执行：

```text
P(drop env)   = 0.15
P(drop event) = 0.15
P(drop gyro)  = 0.10
P(drop mag)   = 0.20
```

不得同时 drop accel 与所有其他分支；具体概率通过验证集扫描，但测试报告必须记录。

### 8.2 Corruption augmentation

- sampling gap：0.2 / 0.5 / 1 / 2 s；
- random sample loss：10% / 30% / 50%；
- stale env：5 / 30 / 120 s；
- mag bias 和 field spike；
- gyro bias；
- light noise 与 prox stuck；
- event stream unavailable。

模型输入中的 quality 必须同步反映 corruption；另设“错误质量估计”切片，防止模型只在完美 quality metadata 下工作。

## 9. 时间状态融合

第一阶段比较：

1. raw window argmax；
2. EMA + dual threshold hysteresis；
3. learned transition matrix / HMM。

约束示例：

- desk → pocket 通常经过 pickup/in_hand；
- faceup ↔ facedown 需要方向变化；
- vehicle → running 的瞬时跳变低概率；
- 状态可拒识为 unknown，不能为保持连续而强制错误标签。

评价：

```text
false transitions / hour
transition detection delay
segment-level F1
window-level macro-F1
```

## 10. 置信度与拒识

- validation set temperature scaling；
- 报告 ECE、Brier score、reliability diagram；
- overall confidence 综合 head probability、gate coverage 和 quality；
- 当关键输入质量低且 posterior 不集中时输出 `UNKNOWN`；
- App 推荐只消费非 rejected、仍在 validUntil 内的状态。

## 11. 数据与 Split

### 11.1 最小交叉矩阵

主混淆对必须有反事实：

| Activity | in_hand | in_pocket | in_bag | desk_up/down |
|---|---:|---:|---:|---:|
| still | 必须 | 必须 | 可选 | 必须 |
| walking | 必须 | 必须 | 必须 | 不适用 |
| running | 必须 | 必须 | 可选 | 不适用 |
| in_vehicle | 必须 | 必须 | 必须 | 不适用 |

环境明暗在 `in_hand / in_pocket / desk_facedown` 中都必须出现，避免模型学习 `dark=pocket`。

### 11.2 Split

- pilot：leave-one-session-out，只用于 pipeline sanity；
- 正式：participant-grouped train/val/test；
- 单人阶段：按日期和 session group，最后一天只做 test；
- 公开 HAR 数据只预训练 motion encoder，不用于 carry test 指标。

## 12. 评测矩阵

### 12.1 Clean ablation

| Run | Motion | Env | Event | Gate | Temporal |
|---|---|---|---|---|---|
| B2 | accel | — | — | — | — |
| B3 | IMU | — | — | — | — |
| B4 | raw concat | repeated | concat | — | — |
| B5 | encoder | encoder | encoder | fixed | — |
| F1 | encoder | encoder | encoder | quality | — |
| F2 | encoder | encoder | encoder | quality | ✓ |

### 12.2 Robustness

每个 run 至少在以下切片报告：

```text
clean
no gyro
no mag
no env
stale env
mag interference
30% IMU loss
1 s gap
cross-day
cross-person（有数据后）
```

### 12.3 指标

- activity/carry macro-F1；
- per-class precision/recall；
- joint exact match（两个头都正确）；
- ECE/Brier；
- false transitions/hour；
- p50/p95 latency；
- model memory/size；
- energy per inference 与 duty cycle。

窗口数量只能作为样本描述；统计显著性和 bootstrap 以 session 为单位。

## 13. 验收与停止条件

### GO

- fused joint macro-F1 比最佳单模态 +3 pt，或 carry +5 pt；
- corrupted set 上 quality gate 比 early fusion +5 pt；
- temporal filter 减少 ≥30% false transitions；
- 至少两个 carry 混淆对显示明确互补证据；
- gate 权重与人为质量退化方向一致。

### CONDITIONAL GO

- clean 精度无明显增益，但缺失/干扰鲁棒性显著提升；
- 缩小模态或标签后继续，不扩模型。

### STOP / PIVOT

- activity/carry 可被 accel-only 同等解决；
- gate 不随质量变化或只学习标签泄漏；
- 新增模态收益低于功耗/权限成本；
- session-level 泛化接近随机。

## 14. 端侧一致性

必须维护 golden fixtures：

```text
SCELOG raw session
→ Python aligned tensor + quality
→ expected model input checksum
→ FP32 expected logits
→ INT8 TFLite/QNN logits tolerance
```

Android 与 Python 对：

- timestamp grid；
- interpolation；
- normalization；
- masks/freshness；
- quality feature；
- channel order；
- label index；

逐项一致，否则禁止比较端侧和离线精度。

## 15. 与下游 App 推荐的接口

融合模型只输出：

```text
PhysicalContextSnapshot
```

不接收 package name，不对 App 排序，防止核心算法和下游耦合。

推荐侧只读取：

```text
activity/carry probabilities
confidence/reject
stable duration
time since context change
quality summary
```

这样可以独立回答两个问题：

1. 融合算法本身是否成立；
2. 物理情境是否对 App 推荐产生增量。
