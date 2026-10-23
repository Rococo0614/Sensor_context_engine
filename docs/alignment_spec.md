# 多传感器时间对齐、质量与切窗规格 v2

本文是 Android 采集侧与 Python 训练侧共同遵守的时间语义规格。任何模型输入变更必须先修改本文和一致性测试。

## 1. 时钟

- 传感器序列唯一时钟：`SensorEvent.timestamp`（与 `elapsedRealtimeNanos` 同一时间基准）；
- 禁止使用 `System.currentTimeMillis` 对齐传感器；
- wall time 仅记录 session 的现实时间，便于查找数据；
- 同一 sensor 内时间戳必须单调非降；乱序事件在转换时排序并计入质量报告；
- 不跨 session 对齐或切窗。

## 2. 标准网格

- 默认频率：25 Hz；
- 周期：`40_000_000 ns`；
- 网格起点：session 内第一个 accelerometer 时间戳向上对齐到 40 ms；
- 网格终点：最后一个 accelerometer 时间戳向下对齐；
- 无 accelerometer 的 session 判定为不可训练，但仍保留事件记录。

## 3. 通道规则

| 通道 | 对齐方法 | 最大有效间隔 |
|---|---|---|
| accel x/y/z | 相邻样本线性插值 | 两侧间隔 ≤200 ms |
| gyro x/y/z | 相邻样本线性插值 | 两侧间隔 ≤200 ms |
| mag x/y/z | 最近邻 | 距网格点 ≤100 ms |
| gravity x/y/z | 线性插值 | 两侧间隔 ≤200 ms |
| lux / CCT / proximity | 零阶保持（forward-fill） | 最多保留 10 min，同时输出 age/freshness |
| step counter | 零阶保持 | session 内永久有效 |
| vendor event / step detector | 不扩散到连续通道，单独保留事件表 | — |

缺失值输出为空/NaN，同时生成对应 `*_valid` mask；禁止无标记地填零。forward-fill 只说明最近观测值存在，不表示仍然新鲜。

### 3.1 Freshness

每个慢变通道必须同时输出：

```text
value
valid              # session 中是否曾出现有效观测
age_ms             # grid timestamp - source event timestamp
freshness_score    # 单调递减到 [0,1]
```

首版默认参考尺度：

| 通道 | fresh | stale warning | invalid retention |
|---|---:|---:|---:|
| light/CCT | ≤5 s | >30 s | >10 min |
| proximity | ≤5 s | >30 s | >10 min |
| event bit | 由事件定义 | 输出 time-since-event | 不静默扩散成持续真值 |

阈值作为质量特征和切片标准，不直接等同于业务标签。

### 3.2 Window quality

每个连续 sensor/window 生成：

```text
sample_count
expected_sample_count
sample_ratio
median_period_ms
p95_period_ms
max_gap_ms
timestamp_disorder_count
saturation_ratio
```

mag 额外生成：

```text
field_mean_uT
field_std_uT
field_deviation_score
spike_ratio
```

质量向量是模型输入，也是 robustness 分桶依据。

## 4. 传感器选择

同类传感器存在多个实现时：

1. 首选 uncalibrated magnetic field；
2. 首选 non-wakeup accel/gyro 连续流；
3. 以文件头的 `sensor_id + string_type + name` 固定本 session 选择；
4. 不允许在一个 session 中途静默切换物理传感器。

## 5. Canonical channel order

训练、导出和 Android 必须共享固定顺序：

```text
continuous:
  accel_x, accel_y, accel_z,
  gyro_x, gyro_y, gyro_z,
  mag_x, mag_y, mag_z,
  gravity_x, gravity_y, gravity_z

environment:
  log_lux, cct_norm, proximity_distance, proximity_near,
  lux_age, cct_age, proximity_age,
  valid masks

events:
  step_delta, step_rate, time_since_step,
  significant_motion + age,
  tilt + age,
  selected vendor bits + age,
  availability masks
```

模型版本必须携带 channel schema hash；顺序变化必须使旧模型拒绝加载。

## 6. 切窗

- 默认窗口：4 s，即 100 个 25 Hz 网格点；
- 默认步长：2 s，即 50% overlap；
- 快速价值验证默认裁掉每个 session 首尾各 3 s，排除点击开始/结束造成的运动；
- 窗口左闭右开 `[start, end)`；
- 有效 accel 比例低于 90% 的窗口丢弃；
- 其他模态缺失不直接丢窗，由 mask 告知模型；
- session 固定标签应用于该 session 的全部窗口；事件 marker 只标记其时间邻域。

事件分支以窗口结尾为基准，编码窗口内计数和 `time_since_event`；禁止把瞬时 event 在整个 session 内 forward-fill 为 1。

## 7. 数据划分

- 最小隔离单位是完整 session，禁止把同一 session 的重叠窗拆到 train/test 两侧；
- 有多人数据时优先按 participant 划分；
- 快速价值验证使用 leave-one-session-out；某类别必须至少有 2 个独立 session；
- 弱标签数据不得进入人工确认测试集。

Activity 与 Carry 必须按交叉组合检查 split 分布；若某个组合只出现在一个 split，评测无效。

## 8. 故障注入语义

故障注入只能修改 replay/model input，不能改写原始 `.scelog`：

- `drop modality`：values=NaN、valid=0、quality.available=0；
- `stale`：保留最后值、增加 age、降低 freshness；
- `sample loss`：删除 replay 中的部分事件，再重新对齐并计算 quality；
- `gap`：删除连续时间段，不允许用跨 gap 插值掩盖；
- `mag interference`：对值施加偏置/尖峰，同时更新 interference quality；
- `wrong quality`：数据退化但 quality 保持正常，用于检测对 metadata 的脆弱依赖。

每个 corruption 必须记录 seed、参数和源 session，不生成无法追溯的新“原始文件”。

## 9. 一致性验收

1. Android 写出的真机 `.scelog` 必须能被 `training/sce_data/binary_log.py` 解析；
2. 文件头传感器数量应与采集启动时枚举一致；
3. event timestamp 落在 session start/end 范围内；
4. 25 Hz 网格相邻差严格为 `40_000_000 ns`；
5. 4 s 窗固定包含 100 行；
6. fixture、Python 单元测试和真机 round-trip 均通过后才能修改格式或对齐逻辑。
7. 同一窗口的 channel order、mask、freshness、quality 在 Python 与 Android 中逐项一致；
8. 生成 model input checksum 和 schema hash；
9. 跨端 logits 在后端约定 tolerance 内一致。
