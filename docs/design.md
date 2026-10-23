# 端侧低功耗质量感知多传感器融合引擎 — 设计文档 v2

| 项 | 内容 |
|---|---|
| 项目代号 | `sensor_context_engine` |
| 目标设备 | vivo V2547A / SM8850 / Android 16 / API 36 / 已 root |
| 核心方向 | 多率、质量感知、缺失模态鲁棒的传感器融合 |
| 主任务 | Activity + Carry Posture 联合估计 |
| 下游验证 | 基于物理情境的 Top-3 App 推荐 |
| 首版隐私边界 | 不使用麦克风、相机、GPS；原始数据仅在明确采集时落盘 |
| 文档状态 | v2 方向冻结；F0 数据契约已实现（2026-10-23） |

> v1 设计保存在 `docs/design_v1_archive.md`。v2 不再以“覆盖尽可能多的四维标签”为目标，而以**证明融合算法相对单模态、直接拼接和固定权重方案的增量价值**为中心。

## 1. 项目重新定义

### 1.1 核心问题

手机上的传感器具有不同采样率、噪声、时效和可用性：

- accelerometer 能观察运动强度，但难区分手持、口袋和包；
- gyro 能观察旋转，但常开增加功耗；
- magnetometer 能辅助方向，却易受室内磁干扰；
- light/proximity 对遮挡和携带状态有帮助，但单独存在歧义；
- step/vendor detector 功耗低，但标签稀疏、黑盒且可能无权限；
- 某些模态会缺失、过期、被遮挡、饱和或产生长时间采样空洞。

项目研究问题是：

> 能否用一个小型端侧模型，根据每个模态当下的质量和新鲜度，自适应融合多率传感器，在单个传感器缺失或受干扰时，仍稳定输出活动与携带姿态？

### 1.2 可证伪假设

| 编号 | 假设 | 验证方式 |
|---|---|---|
| H1 | 多模态融合优于最佳单模态 | fused macro-F1 对比 accel-only / IMU-only / env-only |
| H2 | 质量门控优于直接拼接 | clean、missing、stale、mag-interference 四组切片 |
| H3 | 时间状态约束减少抖动 | false transitions/hour 与状态确认延迟 |
| H4 | 物理情境对下一 App 预测有增量 | usage-only baseline 对比 usage + fused context |
| H5 | 分层门控可降低平均功耗 | continuous baseline 对比 Stage 0/1 duty cycle |

若 H1/H2 不成立，停止复杂融合路线；若 H4 不成立，保留融合引擎但停止 App 推荐方向。

### 1.3 什么算项目中的“融合”

本项目覆盖三个层次：

1. **信号级融合**：accel + gyro + mag 的姿态估计与磁干扰降权；
2. **表征级融合**：IMU、环境、事件分支分别编码后，由质量门控联合估计 activity/carry；
3. **时间级融合**：根据前态、转移约束和持续时间抑制不合理跳变。

仅采集多个传感器、显示多个数值，或不做消融地把所有通道拼起来，不足以构成本项目的融合贡献。

## 2. 范围冻结

### 2.1 主输出

#### Activity（主头）

| 标签 | 首版状态 | 主要证据 |
|---|---|---|
| `still` | core | accel 方差、姿态稳定性、step 静默 |
| `walking` | core | 周期步态、step、IMU 频谱/自相关 |
| `running` | core | 更高步频和幅值、step rate |
| `in_vehicle` | core | 无步态、持续低频振动、vendor car/vibration 事件 |

`cycling / elevator / stairs` 从 v1 core 移出，作为数据充分后的 stretch。

#### Carry Posture（主头）

| 标签 | 首版状态 | 主要证据 |
|---|---|---|
| `desk_faceup` | core | still + 重力方向 + prox far |
| `desk_facedown` | core | still + 重力反向 + prox near + dark |
| `in_hand` | core | 非平放、微运动、prox/light 组合 |
| `in_pocket` | core | dark + prox near + 与步态强耦合 |
| `in_bag` | stretch/core-data-dependent | dark + 与人体步态弱耦合、独立摆动 |

#### Auxiliary Heads

- `light_level = bright / normal / dim / dark / unknown`；
- `orientation_stability`；
- 每个模态的 branch logits，仅用于训练约束和消融。

交互事件不再作为主模型输出；pickup/putdown/vendor events 首版作为时间转移证据和弱标签。

### 2.2 明确非目标

- 不让通用 LLM 常驻读取 25–50 Hz 原始数据；
- 不在首版加入麦克风、相机和 GPS；
- 不在数据不足时扩展 taxonomy；
- 不用单一总体准确率掩盖类别、session 和用户差异；
- 不把启发式规则的结果当作最终标注；
- 不因目标设备已 root 而设计不可迁移的产品依赖；
- 不在融合价值尚未成立前投入完整 App 推荐产品化。

## 3. 系统架构

```text
Android Sensor Framework / Sensor Hub
        │
        ├── accel 25/50 Hz
        ├── gyro 50 Hz
        ├── mag 20 Hz
        ├── light/CCT/proximity on-change
        └── step + vendor hardware events
        │
        ▼
Timestamp Alignment + Calibration + Quality Estimation
        │
        ├── Motion branch: accel/gyro/gravity/mag
        ├── Environment branch: light/CCT/proximity
        └── Event branch: step/vendor events/time-since-event
        │
        ▼
Quality-Aware Gated Fusion
        │
        ├── Activity head
        ├── Carry head
        └── Auxiliary heads
        │
        ▼
Temporal State Filter + Calibration
        │
        ▼
PhysicalContextSnapshot
        │
        ├── 实时调试/功耗评测
        └── Contextual App Recommendation（下游）
```

### 3.1 低功耗分层

#### Stage 0：常驻变化门控

- accel 25 Hz + 5 s batching；
- proximity/硬件 wakeup event；
- 低成本统计量和心跳；
- 只判断“是否值得运行融合模型”，不输出最终 posture。

#### Stage 1：按需融合

- 临时启用 gyro/mag 或读取最近 FIFO；
- 运行 4 s 窗口的质量感知融合模型；
- 情境不稳定时进入短观察期；
- 稳定后回睡。

#### Stage 2：状态与下游

- 时间滤波、置信度校准、ContextSnapshot；
- 只有稳定变化或明确请求才更新下游推荐；
- LLM 若接入，只消费结构化 ContextSnapshot。

## 4. 数据模态与质量描述

| 分支 | 输入 | 质量向量示例 |
|---|---|---|
| Motion | accel、gyro、gravity、mag | sample ratio、max gap、saturation、mag field deviation |
| Environment | lux、CCT、proximity | freshness、change magnitude、遮挡一致性、missing mask |
| Event | step counter/detector、significant motion、vivo detector | time since event、event availability、permission state |

每个值必须同时携带：

```text
value + timestamp + valid mask + freshness + source + quality
```

`UNKNOWN` 和缺失不能用零值代替。模型必须在训练中见过缺失模态。

详细输入、网络和损失见 `docs/fusion_algorithm_spec.md`；对齐规则见 `docs/alignment_spec.md`。

## 5. 数据采集原则

### 5.1 因子交叉，禁止标签捷径

必须让相同活动出现在多个携带姿态中，也让同一姿态跨多个活动：

```text
still   × in_hand / in_pocket / desk_faceup / desk_facedown
walking × in_hand / in_pocket / in_bag
running × in_hand / in_pocket
vehicle × in_hand / in_pocket / in_bag（仅乘客、安全前提）
```

还需采集反事实组合：

- dark + desk_facedown 与 dark + in_pocket；
- still + in_hand 与 still + desk；
- walking + in_hand 与 walking + in_pocket；
- mag 正常与人为磁干扰；
- light/proximity 缺失或过期回放。

### 5.2 数据划分

- 按完整 session 划分；
- 多人数据按 participant 划分；
- 同一连续录制不可切成独立 train/test；
- 重叠窗口不得跨 split；
- 测试集标签必须人工确认。

### 5.3 当前采集基建

已完成：

- `SCELOG v1`；
- 56 个传感器枚举；
- 14 个实际注册流；
- 50 Hz accel/gyro、20 Hz mag 的真机连续 round-trip；
- 25 Hz 对齐、质量检测、窗口导出；
- vivo 后台冻结问题定位及“允许后台耗电”设置入口。

原 Phase B 分类协议已归档；新协议以 `docs/rapid_validation_protocol.md` 为准。

## 6. 融合算法主线

必须实现并公平比较：

1. accel-only TCN；
2. IMU-only TCN；
3. 手工特征 + 传统分类器；
4. early fusion；
5. fixed-weight late fusion；
6. **quality-aware gated fusion（主模型）**；
7. 主模型 + temporal filter。

主模型不能只在 clean test 上比较；必须执行：

- modality dropout；
- stale light/proximity；
- gyro/mag missing；
- magnetic interference；
- sampling gap；
- sensor rate drift。

## 7. 下游：情境化 App 推荐

### 7.1 定位

App 推荐是融合算法的外部价值验证，不是核心模型训练目标。

```text
PhysicalContextSnapshot
        + time/day
        + previous app / recent usage
        -> next-app ranker
        -> Top-3 home widget
```

### 7.2 增量实验

| 组 | 输入 |
|---|---|
| A | 全局最常用 App |
| B | 时间 + 星期 |
| C | B + previous app + recency |
| D | C + fused activity/carry context |

只有 D 相对 C 有稳定增益，才证明物理情境对 App 推荐有价值。

首版 UI 使用非打扰式桌面组件；不使用频繁推荐通知，不在锁屏展示敏感 App。

### 7.3 LLM 边界

LLM 不负责实时排序，仅可用于：

- 将“走路时不要推荐短视频”转成结构化偏好；
- 根据确定性统计生成解释；
- 周期性总结习惯。

## 8. 验收标准

### 8.1 融合价值

- clean test：主模型联合 macro-F1 比最佳单模态至少高 3 个百分点，或 carry F1 至少高 5 个百分点；
- corrupted test：质量门控比 early fusion 至少高 5 个百分点；
- 单一非关键模态缺失时，联合 macro-F1 下降不超过 8 个百分点；
- temporal filter 将 false transitions/hour 降低至少 30%，且中位确认延迟不超过 2 个窗口；
- ECE ≤ 0.08，低置信度样本可以拒识；
- 所有结果按 session/participant 给出置信区间，不只报告窗口平均。

### 8.2 端侧部署

- 模型参数首版目标 <250K；
- INT8 相对 FP32 掉点 <2 pt；
- Stage 1 单窗 p95 延迟 <50 ms（CPU/HTP 分别报告）；
- 无传感器数据落入网络；
- 缺失模态不会崩溃或静默填零。

### 8.3 App 推荐

- usage + context 的 Top-3 相对 usage-only 基线绝对提升 ≥8 pt 或相对提升 ≥15%；
- 至少两个高频情境出现跨天稳定增益；
- 只在高置信度情境更新推荐；
- 用户可排除 App、查看原因和删除全部历史。

## 9. 执行阶段

| 阶段 | 目标 | 关键交付 |
|---|---|---|
| F0 | 文档与数据契约重构 | 本文、fusion spec、平衡采集协议 |
| F1 | 单模态基线 | accel-only、IMU-only、传统特征；数据质量报告 |
| F2 | 融合 baseline | early/late fusion、统一训练评测管线 |
| F3 | 主模型 | 质量门控、modality dropout、辅助头 |
| F4 | 时间融合 | 状态转移、校准、误跳变评测 |
| F5 | 端侧部署 | TFLite/QNN、INT8、CPU/HTP 对比 |
| F6 | 下游验证 | UsageStats、next-app baseline、Top-3 widget |
| F7 | 低功耗与成文 | Stage 0/1、功耗矩阵、消融与技术报告 |

F0 文档、跨端 schema、质量估计和融合样本导出已完成；下一门槛是按 v2 协议采集 P0 pilot。Phase A/B 已完成的 Android 和采集代码继续复用，不推倒重来。

## 10. 风险与停止条件

| 风险 | 处理 |
|---|---|
| 单人/单设备过拟合 | session/participant split，公开集只服务 activity encoder |
| 姿态与活动标签混淆 | 因子交叉采集与反事实切片 |
| 环境分支成为捷径 | dark/normal 在多个姿态中平衡 |
| vendor detector 权限不稳定 | 只作为可选事件，不作为必需输入 |
| 磁力计污染 | 场强质量特征、干扰注入和门控降权 |
| 门控塌缩到单一分支 | branch auxiliary loss、modality dropout、gate 监控 |
| 融合无增益 | H1/H2 不成立则停止主模型复杂化 |
| App 推荐无增益 | H4 不成立则不开发推荐 UI |
| HTP 唤醒成本高于 CPU | 真机 A/B，不预设硬件后端结论 |

## 11. 最终交付叙事

项目最终不以“支持多少标签”作为成果，而以四张表/图作为核心证据：

1. 单模态 → early/late → quality-gated 的融合增益；
2. clean → missing/stale/interference 的鲁棒性曲线；
3. 误跳变、延迟、功耗三者的权衡；
4. App 推荐 usage-only → usage + physical context 的增量。

这四项均可在目标 vivo 真机上复现，才算项目成立。
