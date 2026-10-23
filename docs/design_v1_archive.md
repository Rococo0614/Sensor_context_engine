# [归档] 端侧低功耗多模态情境感知引擎 — 详细设计文档 v1

> 本文档于 2026-10-23 归档。原“四维共享骨干 + 个性化建议”方向已被质量感知多传感器融合主线替代。当前设计以 `docs/design.md` 和 `docs/fusion_algorithm_spec.md` 为准。

| 项 | 内容 |
|---|---|
| 项目代号 | `sensor_context_engine` |
| 目标设备 | vivo V2547A（SM8850 / 骁龙 8 Elite Gen5，`canoe` 平台，Android 16 / SDK 36，已 root） |
| 训练设备 | RTX 5090 |
| 周期 | 4 个月（8 个双周 Sprint） |
| 文档状态 | 设计定稿 v1（范围已冻结，四维 taxonomy 不再扩张） |
| 定位 | 懂原理的工程师 + AI 编程落地。每模块标注【原理要点】（必须自己弄懂）与【AI 落地】（交给 AI 编程） |

## 目录

1. [行业洞察与项目论点](#1-行业洞察与项目论点)
2. [情境输出定义（四维多标签 Taxonomy）](#2-情境输出定义四维多标签-taxonomy)
3. [系统架构（分层门控）](#3-系统架构分层门控)
4. [传感器接入设计](#4-传感器接入设计)
5. [数据管线](#5-数据管线)
6. [算法设计](#6-算法设计)
7. [端侧部署设计（QNN 为主 + TFLite 对照）](#7-端侧部署设计qnn-为主--tflite-对照)
8. [功耗评测方法论](#8-功耗评测方法论)
9. [里程碑（8 个双周 Sprint）](#9-里程碑8-个双周-sprint)
10. [风险与 Limitation](#10-风险与-limitation)
11. [仓库结构规划](#11-仓库结构规划)
12. [附录：原理要点总清单](#12-附录原理要点总清单)
13. [附录：设备能力实测记录](#13-附录设备能力实测记录)

---

## 1. 行业洞察与项目论点

### 1.1 行业现状

旗舰手机上常驻着 20+ 个情境检测器。本机实测 55 个传感器，其中 vivo 自定义低功耗检测器包括 `raiseup` / `putdown` / `vivomotion` / `elevator_detect` / `car_navi_detect` / `airplane_identify` / `vibration_classification` / `activity_wk` / `lowlight_detect` / `drop_depth` 等。厂商竞争力 = **更丰富的情境感知 × 更低的常驻功耗**。

### 1.2 两个真痛点

1. **常驻感知的功耗墙**：多传感器高采样率常开 + NN 持续推理，naive 实现一天吃掉两位数电量百分比，产品上不可接受。
2. **能力碎片化**：每个情境一个孤岛模型（抬手一个、车载一个、电梯一个），无共享表征；每新增一个能力就多养一个常驻模型，功耗与维护成本线性上涨。

### 1.3 项目论点

> 构建一个**分层门控、多模态融合**的情境感知引擎：一个 <1mW 级微型门控常驻低功耗通路，仅在情境可能变化时逐级唤醒 NPU 上的多标签融合模型；**单一共享骨干输出四维情境状态**，用真机实测证明"接近满血精度、平均功耗只有 naive 常开方案的一小部分"。

### 1.4 与岗位需求的映射

| JD 条目 | 项目落点 |
|---|---|
| 低功耗多模态感知算法设计与实现 | 分层门控 + IMU/光/磁/接近/计步融合（§3、§6） |
| 端侧部署、实时性、低功耗、功耗精细化控制 | QNN CPU/DSP/NPU 三后端部署 + 实测功耗矩阵（§7、§8） |
| AI + 低功耗平台前沿预研 | 量化/蒸馏/占空比/SensorDirectChannel 实验 |
| 时频域信号处理 | 特征工程 + Kalman 姿态融合（§6，够用即可，不深挖） |
| ML/DL + 主流框架 | PyTorch 训练全链路（§6） |
| Android 系统架构 / Sensor Framework / HAL | batching、FIFO、wakeup sensor、SensorDirectChannel 深度使用（§4） |
| C/C++ | 端侧推理热路径 JNI/C++（§7.1，AI 生成 + 自己读懂） |
| 跨团队协作 | 设计文档、评测报告、Demo（本文档即载体） |

---

## 2. 情境输出定义（四维多标签 Taxonomy）

四维**同时输出、彼此独立**（multi-label / multi-head），窗口级预测与事件级预测混合。标签定义、判定边界与标注协议在 S1 冻结为 `docs/taxonomy.md`。

### 2.1 维度一：活动（Activity，窗口级）

| 标签 | 判定边界 | 主要信号 |
|---|---|---|
| `still` | 静止（坐/站/躺合并，由姿态维细分） | accel 方差极低 |
| `walking` | 周期步态 1.4–2.5 Hz | accel FFT 主频 + 计步器 |
| `running` | 步态 2.5–4 Hz，幅值显著更大 | 同上 + 幅值 |
| `cycling` | 高频小幅振动 + 持续位移感 | accel/gyro 频谱形态 |
| `in_vehicle` | 低频发动机振动 + 持续平滑加速度 | 对标 `vibration_classification` / `car_navi_detect` |
| `elevator` | 垂直加速度脉冲 + 前后静止段组合 | 对标 `elevator_detect` |
| `stairs`（stretch） | 步态 + 俯仰角周期变化；无气压计为弱信号 | Kalman pitch + 步态 |

- 窗口：2–4 s 滑窗，50% 重叠；输出各标签概率。
- 公开集标签映射：UCI-HAR(6 类) / PAMAP2(12+ 类) / WISDM / RealWorld(8 类含骑行) / ExtraSensory → 统一映射表；映射不了的类丢弃或合并（如 `upstairs`/`downstairs` → `stairs`，仅在自采数据保留）。

### 2.2 维度二：携带姿态（Carry Posture，窗口级）

| 标签 | 判定依据 |
|---|---|
| `desk_faceup` | 接近 = 远 + 光照稳定 + gravity 指向屏幕法线 |
| `desk_facedown` | 接近 = 近（贴桌面）+ 光 ≈ 0 + gravity 反向 |
| `in_hand` | 光中等 + 微动与身体运动强耦合 + 姿态高频小幅变化 |
| `in_pocket` | 光 ≈ 0 + 接近 = 近 + 运动与步态周期**强**耦合 |
| `in_bag` | 光 ≈ 0 + 运动与步态**弱**耦合（独立摆动/缓冲） |

- 核心原理：口袋 vs 包 = **步态耦合度**（互相关 / 相干性）；桌面正反 = 接近 + 光 + 重力方向三证据融合。

### 2.3 维度三：环境（Environment，窗口级，慢变）

| 子维 | 标签 | 依据 |
|---|---|---|
| 室内外 | `indoor` / `outdoor` / `transition` | lux 量级（室外 >10k，室内 10²–10³）+ CCT + 光变化动态 |
| 明暗 | `bright` / `dim` / `dark` | lux 阈值 + 对标 `lowlight_detect` |
| 光色 | `warm` / `neutral` / `cool` | tcs3425 CCT（<3500 K / 3500–5500 K / >5500 K） |

- 变化缓慢，推理频率可低至 0.1 Hz——这本身就是占空比设计的一部分。

### 2.4 维度四：交互事件（Interaction Events，事件级）

| 事件 | 时长 | 对标 |
|---|---|---|
| `pickup`（拿起） | ~0.5–1 s 瞬态 | vivo `raiseup` |
| `putdown`（放下） | ~0.5–1 s | vivo `putdown` |
| `shake`（摇晃） | 0.5–2 s | 自定 |
| `flip`（翻转） | ~0.5 s | gravity 方向突变 |

- 事件级输出：滑动短窗二分类 + 非极大值抑制（NMS）去抖。
- **与 vivo 检测器并行录制**：其输出作为弱标签与 baseline（§5.4、§8.5）。

---

## 3. 系统架构（分层门控）

### 3.1 分层结构

```
                 ┌────────────────────────────────────────────┐
                 │ Stage 2  记录/上报 (rare)                    │
                 │  情境跳变或用户交互时: 落库 / UI / 日志          │
                 └──────────────▲─────────────────────────────┘
                                │ context_changed
                 ┌──────────────┴─────────────────────────────┐
                 │ Stage 1  融合情境模型 (on-demand, NPU/HTP)    │
                 │  输入: IMU 50Hz × 2-4s窗 + mag + lux/CCT +   │
                 │        proximity + step                     │
                 │  模型: 共享骨干 + 4头 (活动/姿态/环境/事件)      │
                 │  Kalman 姿态融合作为附加特征通道                │
                 └──────────────▲─────────────────────────────┘
                                │ wake (gate_score > θ_wake)
                 ┌──────────────┴─────────────────────────────┐
                 │ Stage 0  常驻门控 (always-on, 低占空比)        │
                 │  输入: accel 25Hz batched (5s latency) +     │
                 │        proximity/light (on-change, wakeup) + │
                 │        step_counter + significant_motion /   │
                 │        tilt (硬件检测器)                      │
                 │  模型: <10K 参数 (统计特征 + 微型MLP/GBDT)      │
                 │  输出: "情境可能变化" 概率 → 唤醒 Stage 1        │
                 │  + 固定心跳: 每 N 秒强制唤醒一次兜底             │
                 └────────────────────────────────────────────┘
```

### 3.2 门控设计（B 方向落点：重原理、轻体量）

- **唤醒源三类**：
  1. 硬件 wakeup 传感器事件（`significant_motion` / `tilt_detector` / `proximity` / `lit_up`），AP 可保持睡眠，零 CPU 成本；
  2. 门控微模型对 batched accel 统计特征的打分；
  3. 定时心跳（保证环境维慢变不漏检）。
- **占空比调度**：Stage 1 每次唤醒只推理 1–2 个窗口（~4 s），确认情境稳定后立即回睡；情境跳变则进入 10–30 s 的"观察期"高频推理，稳定后退出。
- **滞回（hysteresis）**：唤醒阈值 `θ_wake` 与回睡阈值 `θ_sleep` 分离，防止边界抖动振荡。

【原理要点】占空比与平均功耗的关系（`P_avg ≈ duty × P_active + P_idle`）；wakeup 与 non-wakeup 传感器的区别（AP 能否休眠）；batching/FIFO 为什么省电（减少 AP 唤醒次数，而非减少采样本身）。
【AI 落地】状态机代码、SensorManager 注册、阈值扫描脚本。

### 3.3 与 vivo 黑盒检测器的关系

- **不依赖**其内部实现（拿不到），但**并行录制**其事件流：
  - 作为弱标签来源（§5.4）；
  - 作为精度/功耗对标 baseline——"自研融合模型 vs 厂商孤岛检测器"是评测报告的亮点章节（§8.5）。

---

## 4. 传感器接入设计

### 4.1 传感器清单与采样策略（本机实测参数）

| 传感器 | 硬件 | 能力 | 采集策略 |
|---|---|---|---|
| accelerometer | lsm6dsvx (ST) | ≤480 Hz, FIFO 10000, non-wakeup | Stage0: 25 Hz + `maxReportLatency` 5 s（batching）；Stage1: 50 Hz |
| gyroscope | vsen_gyro | ≤480 Hz | Stage1: 50 Hz（Stage0 不用，省电） |
| magnetometer | qmc6309h | ≤100 Hz, FIFO 600 | Stage1: 20 Hz；用 uncalibrated 版自行去偏 |
| ambient light | tcs3743 | on-change，含 wakeup 变体 `lit_up` | on-change 注册，事件驱动 |
| light CCT | tcs3425 | on-change | on-change |
| proximity | tcs3743 prox | on-change, **wakeup** | on-change（门控唤醒源） |
| step_counter / step_detector | QTI / vivo | on-change / special-trigger | 被动读取 |
| significant_motion / tilt_detector | QTI | one-shot / special-trigger, **wakeup** | 门控唤醒源 |
| gravity / rotation_vector | 融合虚拟传感器 | ≤200 Hz | Stage1 可选（对照自研 Kalman） |
| vivo 检测器组 | raiseup / putdown / vivomotion / elevator / car_navi / vibration_cls / activity_wk … | 多为 wakeup | **仅录制事件流**，做弱标签与对标 |

### 4.2 低功耗通路（逐级实验，本身即评测内容）

1. **Batching / FIFO**：`registerListener(rate, maxReportLatencyUs)`，让 sensor hub 攒批，AP 每 5 s 醒一次。
2. **Wakeup sensors 作门控**：AP 睡眠期间由 sensor hub 检测事件唤醒。
3. **SensorDirectChannel**：本机确认支持（实测 `0 open direct connections`）。共享内存直通、绕过 Binder——P4 做 A/B 实验测其相对普通 batching 的功耗/延迟收益。
4. **占空比**：Stage 门控（§3.2）。

【原理要点】Android 传感器数据路径（sensor → hub/SLPI → HAL → framework → app）；batching 省的是唤醒次数而非采样本身；Direct Channel 省的是 Binder/IPC 开销。
【AI 落地】采集 App 全部代码、DirectChannel 的 `SharedMemory`/`MemoryFile` 样板。

### 4.3 多率时间对齐

- **统一时钟**：`elapsedRealtimeNanos`。
- **重采样网格**：25 Hz（IMU 原生 ≥25 Hz 直接对齐；mag 20 Hz 最近邻；on-change 传感器零阶保持 forward-fill）。
- **窗口切片**：按网格时间戳切 2–4 s 窗，每窗携带通道张量
  `[accel(3), gyro(3), mag(3), pitch/roll(Kalman), lux, cct, prox, step_rate]`。
- 规格文档 `docs/alignment_spec.md`（S1 交付）为 Kotlin 端侧与 Python 训练侧**共同遵守的单一事实来源**，避免两侧实现漂移。

【原理要点】重采样与混叠；零阶保持对事件型传感器为何够用。
【AI 落地】对齐/切窗代码（Python 训练侧 + Kotlin 端侧各一份，共享同一规格）。

---

## 5. 数据管线

### 5.1 统一 Schema

```
record = {
  session_id, device, wall_ts, elapsed_ns,
  accel[3], gyro[3], mag_uncal[3],
  lux, cct, prox(0/1), step_cum,
  vivo_events: {sensor_type, ts, values},   // 并行录制
  label: {activity, posture, env_indoor, env_light, env_cct, event} | null
}
```

存储：端侧二进制日志（protobuf 或 flatbuffer）→ `adb pull` → Python 转 parquet。

### 5.2 公开数据集（5090 训练主力）

| 数据集 | 用途 | 备注 |
|---|---|---|
| UCI-HAR | 活动基线 | 6 类，30 人 |
| PAMAP2 | 活动扩展 | 12+ 类，含骑行/类电梯活动 |
| WISDM | 泛化验证 | 手持 + 口袋两位置——正好服务姿态维 |
| RealWorld (HETRAS) | 骑行/真实场景 | 8 类，多设备位置 |
| ExtraSensory | 弱标签情境 | 含 in-car / on-bus 等情境标签 |

- 每个集写一个 converter → 统一 schema；仅保留能映射的标签。
- **公开集缺姿态/环境/光三维** → 这三维主要靠自采，这是自采的核心动机。

### 5.3 自采集协议

- **采集 App**（S1 交付）：可配传感器集合/采样率/batching 参数，前台服务保活，支持息屏采集。
- **脚本化采集**：
  - 活动：每种 3–5 min × 多场景（走/跑/骑行/乘车/电梯/静止），手机分别置于各姿态；
  - 姿态：每姿态 2–3 min × 活动交叉（口袋-走路、桌面-静止…）；
  - 环境：室内外 × 白天/夜晚 × 暖/冷光源，各 2 min；
  - 事件：pickup / putdown / shake / flip 各 50+ 次，随机间隔。
- **标注**：采集时按键/语音打时间戳标记（半自动），事后按脚本对齐；事件类由录制脚本自动生成时间区间标签。
- **规模目标**：4 个月累计 ≥10 h 有效数据（本人 + 拉 2–3 位同事/朋友各采 1–2 次）。

### 5.4 弱监督

- vivo 检测器事件（`raiseup` / `putdown` / `elevator_detect` / `car_navi_detect` / `vibration_classification` / `activity_wk`）→ 对应维度弱标签（有噪声，只用于预训练/正则）。
- 计步器 → `walking` 的弱证据。
- **测试集必须全人工确认，弱标签只进训练集。**

【原理要点】弱监督的噪声假设；为什么测试集必须干净。
【AI 落地】converter、采集 App、标注对齐脚本全部 AI 生成。

---

## 6. 算法设计

### 6.1 特征与表征

- **Stage 0 门控特征**（必须极廉价，纯统计）：窗内 accel 幅值 mean/std/MAD、过零率、与重力方向夹角变化、步频估计、prox/light 状态，约 20 维。
- **Stage 1 学习特征**：原始多通道时序直接进网络（端到端），不手工 FFT。
- **手工时频特征作为对照基线**：FFT 频带能量、谱熵、主频、自相关周期——用于评测报告展示"传统时频方法 vs 深度学习"（对应 JD"精通时频域信号处理"要求）。

### 6.2 姿态融合（Kalman，多源融合的落点）

- 互补滤波（起步，便宜）→ EKF（accel + gyro + mag，标准 9 轴融合）估计 pitch/roll/yaw。
- 磁力计室内干扰大 → mag 只贡献 yaw，且带干扰检测（场强偏离阈值时降权）。
- 输出 pitch/roll 及其稳定性统计量，作为 Stage 1 的附加特征通道；同时与系统 `rotation_vector` 对照验证。

【原理要点】互补滤波的频域直觉（accel 低频可信、gyro 高频可信）；EKF 的预测/更新推导；磁干扰检测。这是"多源融合"面试必问，必须自己推一遍。
【AI 落地】滤波器实现代码、误差评测脚本。

### 6.3 情境模型

- **骨干**：1D-CNN（~100K 参数，基线）→ TCN（扩大感受野）；输入 `[C × T]` 多通道窗。
- **四头输出**：`activity(7)` / `posture(5)` / `env(2×2×3)` / `events(4 × sigmoid)`。
- **融合方式对比实验**（报告素材）：
  - early fusion：所有通道拼接进骨干；
  - late fusion：IMU 骨干 + 环境特征 MLP，两头拼接；
  - 结论写进评测报告。
- **门控微模型**：统计特征 + 微型 MLP（<10K 参数）或 GBDT；标签 = "未来 5 s 内四维任一标签是否变化"（从全量标注数据反推）。
- **蒸馏**：FP32 大模型（teacher，可用小 Transformer）→ INT8 小模型（student）；蒸馏损失 = 软标签 KL + 硬标签 CE。

### 6.4 训练与评测协议

- **数据划分**：按 session 划分 train/val/test（防泄漏）；公开集按 subject 划分。
- **指标**：每维 macro-F1 + 混淆矩阵；事件维加 precision/recall@IoU；总指标 = 四维加权 macro-F1。
- **鲁棒性切片**（报告单列）：跨人（留一人法）、跨姿态（训练无口袋、测口袋）。

【原理要点】多任务学习的头间平衡（loss 加权）；session 级划分的必要性；蒸馏原理。
【AI 落地】训练管线、超参搜索、评测报告生成。

---

## 7. 端侧部署设计（QNN 为主 + TFLite 对照）

### 7.1 部署路径

```
PyTorch (5090)
  ├─► ONNX ─► qnn-onnx-converter ─► QNN model ─► context binary
  │             ├─ CPU backend
  │             ├─ HTP/DSP backend (libQnnHtpV81Skel；SM8850 = V81 ✓)
  │             └─ GPU backend (对照)
  └─► TFLite (INT8) ─► XNNPACK CPU / NNAPI delegate   [对照组]
```

- **直接复用 `live_photo_agent` 的 QNN 实战经验**：本机 HTP V81 skel 库已验证可用（3B VLM 都跑通了，<1M 参数的情境模型毫无压力）；`genie-app`/QAIRT 工具链、`ADSP_LIBRARY_PATH`、skel 软链等坑已全部踩过。
- 推理封装：Kotlin App 内 JNI 调 QNN C API（热路径 C++，AI 生成 + 自己读懂）。

### 7.2 量化与压缩

| 步骤 | 方案 | 验收 |
|---|---|---|
| PTQ INT8 | 校准集 = 训练数据子集（各情境均衡采样） | 四维 macro-F1 损失 <2 pt |
| QAT | 仅当 PTQ 掉点超标时启用 | 同上 |
| 蒸馏 | teacher FP32 → student INT8（参数量 ↓5–10×） | student ≥ teacher − 1 pt |

### 7.3 基准测试矩阵（头号差异化成果）

| 后端 | 延迟/推理 | 内存 | 功耗（§8 方法） |
|---|---|---|---|
| QNN CPU | ✓ | ✓ | ✓ |
| QNN HTP/DSP | ✓ | ✓ | ✓ |
| QNN GPU | ✓ | ✓ | ✓ |
| TFLite XNNPACK | ✓ | ✓ | ✓ |
| TFLite NNAPI | ✓ | ✓ | ✓ |

【原理要点】量化对精度的影响机理（激活分布 / 校准集选择）；HTP 为什么比 CPU 省电（VLIW/HVX 向量、专用供电域）；context binary 加载与图分片。
【AI 落地】转换脚本、JNI 封装、基准测试 harness。

---

## 8. 功耗评测方法论

### 8.1 三种互补方法

1. **受控放电 A/B（主方法，可信度最高）**
   - 固定条件：同电量区间（80% → 60%）、屏幕关、飞行模式（或恒定 WiFi）、同室温；
   - 场景脚本自动执行（adb 驱动）：如"每 30 s 一个 4 s 推理窗"的 naive 常开 vs 分层门控；
   - 记录 `dumpsys batterystats` 放电步进 + 时间戳 → 每小时掉电 %；
   - 每配置 ≥2 h × 2 次重复，报告均值 ± 方差。
2. **batterystats / Battery Historian 归因**：app 级 wakelock / CPU / sensor 归因，交叉验证 A/B。
3. **占空比日志 + 解析模型**：端侧记录各 Stage 激活时长/推理次数/后端，结合单组件实测功耗（纯传感器 batching 功耗、单次 HTP 推理功耗）建解析式 `P_avg` 模型，与整机 A/B 互相校验。

### 8.2 核心对比实验（评测报告主表）

| 配置 | 精度（四维 F1） | 平均功耗 | 延迟 |
|---|---|---|---|
| naive 常开（Stage1 连续, CPU） | 上限 | 基准 100% | — |
| naive 常开（HTP） | 同上 | ? | — |
| **分层门控（HTP）** | 预期 −1~2 pt | **预期 10–30%** | 唤醒后 <100 ms |
| 仅硬件检测器（无学习） | 低 | ≈0 | — |
| vivo 黑盒检测器（对标） | 单维 | 不可测，但可对比行为 | — |

### 8.3 附加实验

- batching 参数扫描：`maxReportLatency ∈ {0, 1s, 5s, 10s}` 的功耗曲线；
- SensorDirectChannel vs 普通 batching；
- 采样率扫描：25 / 50 / 100 Hz 对精度与功耗的边际收益；
- ODPM 电源轨：`dumpsys power` 若可导出 rail 数据则做子系统级归因，做不了则标注不可用——不影响主结论。

### 8.4 头号可展示成果

1. **功耗/精度权衡曲线**：naive 常开 vs 分层门控，同精度下平均功耗降 X%（真机实测）。
2. **CPU vs DSP vs NPU 部署对比**：同模型的延迟 + 功耗矩阵。
3. **量化/蒸馏效果**：FP32 → INT8 → 蒸馏小模型的精度损失 vs 功耗/延迟收益。
4. **对标 vivo 黑盒检测器**：自研融合模型 vs 厂商检测器在自采集上的准确率。

【原理要点】放电测量的混淆因素（屏幕/射频/温度）与控制变量法；wakelock 与 AP 睡眠；功耗解析模型。
【AI 落地】adb 自动化脚本、batterystats 解析、报告图表生成。

---

## 9. 里程碑（8 个双周 Sprint）

| Sprint | 周 | 阶段 | 关键交付 | 验收标准 |
|---|---|---|---|---|
| S1 | 1-2 | **P0 数据地基** | 采集 App v0（batching + 全传感器 + vivo 事件流）；`taxonomy.md` 冻结；`alignment_spec.md`；公开集 converter ×5；统一 schema | 手机录 30 min 数据可完整回放对齐 |
| S2 | 3-4 | **P1 信号 + 基线** | 时频特征库；对齐/切窗管线；UCI-HAR / PAMAP2 上 1D-CNN 基线 | 公开集活动分类 ≥ 文献水平（~93%+） |
| S3 | 5-6 | **P1 自采战役①** | 采集脚本执行（活动 + 姿态）；≥4 h 自采数据；标注完成 | 活动/姿态两维可训练 |
| S4 | 7-8 | **P2 多源融合** | Kalman/EKF 姿态融合；光/磁/接近接入；early vs late fusion 实验；四维模型 v1 | 四维 macro-F1 首版报告；融合优于单 IMU |
| S5 | 9-10 | **P3 端侧部署** | ONNX → QNN 三后端 + TFLite 对照；PTQ INT8；蒸馏 student；JNI 推理封装 | 端侧推理跑通，延迟 <100 ms/窗，INT8 掉点 <2 pt |
| S6 | 11-12 | **P4 低功耗架构** | Stage0 门控模型 + 状态机；占空比调度；DRC/batching 实验；受控放电 A/B 全套 | **功耗/精度权衡主表产出**（头号成果） |
| S7 | 13-14 | **P5 系统集成** | 端到端 App：实时情境显示 + 日志 + 电量感知调度；鲁棒性切片评测；自采战役②补数据 | 陌生人拿手机演示 10 min 无崩溃，情境实时正确 |
| S8 | 15-16 | **P6 评测 + 成文** | 全量评测报告；vs vivo 检测器对标章节；Demo 视频；技术长文（行业洞察版） | 作品集级完整交付 |

**关键依赖链**：采集 App(S1) → 一切数据工作；QNN 跑通(S5) → 功耗实验(S6)。S5 若 QNN 工具链对 SM8850 有坑，TFLite 路线先行兜底，QNN 并行攻关（已有 V81 实战经验，风险低）。

---

## 10. 风险与 Limitation

| 风险 | 影响 | 缓解 |
|---|---|---|
| **无气压计**（本机实测无 pressure/barometer） | 楼层/爬楼/高程维缺失 | 明确标注 limitation；`stairs` 用 IMU 步态做 stretch 标签 |
| 真 HAL/内核不可达（量产机） | JD"HAL 层开发"只能沾边 | Framework 深度能力（batching/DRC/wakeup/hub 行为反推）+ 文档展示数据路径理解 |
| 单人为主的数据 | 泛化受限 | 公开多用户集训练 + 留一人法评测 + 拉 2–3 人补采 |
| 功耗为估算非实验室级 | 数值绝对精度有限 | 受控 A/B + 重复实验 + 报告方差；相对结论（分层 vs 常开）依然可信 |
| 磁力计室内干扰 | yaw 不可靠 | 干扰检测降权；姿态维主要靠 gravity + prox + 光 |
| QNN 对 SM8850 的工具链版本匹配 | S5 延期 | 已有 V81 skel 实战经验；TFLite 兜底路线并行 |
| 4 个月周期紧 | 范围膨胀 | 四维 taxonomy 已冻结不再加维；`stairs` / ODPM 均为 stretch |

---

## 11. 仓库结构规划

```
sensor_context_engine/
├── docs/
│   ├── design.md              # 本文档
│   ├── taxonomy.md            # 标签定义与标注协议（S1 冻结件）
│   └── alignment_spec.md      # 时间对齐/切窗规格（Kotlin 与 Python 共同遵守，S1）
├── android/                   # 采集 App + 推理 App（Kotlin，单 module 起步）
│   └── app/src/main/java/.../
│       ├── collect/           # 传感器采集、batching、DRC、vivo 事件流录制
│       ├── inference/         # QNN/TFLite JNI 封装、分层状态机
│       └── ui/                # 实时情境显示、日志
├── training/                  # PyTorch（5090）
│   ├── data/                  # 公开集 converter、自采解析、schema
│   ├── features/              # 时频特征、Kalman
│   ├── models/                # 骨干/四头/门控/蒸馏
│   └── scripts/               # 训练、评测、超参
├── deployment/
│   ├── qnn/                   # ONNX → QNN 转换、context binary 构建
│   ├── tflite/                # 转换与 INT8
│   └── bench/                 # 端侧延迟/内存基准
├── eval/
│   ├── power/                 # 受控放电脚本、batterystats 解析、占空比日志分析
│   └── accuracy/              # 四维评测、鲁棒性切片、vs vivo 对标
└── data/                      # gitignore；仅存 schema 文档与下载脚本
```

---

## 12. 附录：原理要点总清单

自己必须弄懂、面试会被问的部分：

1. Android 传感器数据路径与 batching / FIFO / wakeup 机制（§4.2）
2. 占空比-平均功耗模型（§3.2）
3. 互补滤波 / EKF 九轴姿态融合推导（§6.2）
4. 时频特征：FFT 频带、谱熵、自相关周期（§6.1）
5. INT8 量化机理与校准集选择；蒸馏损失设计（§7.2）
6. HTP/DSP 为什么比 CPU 省电（§7.3）
7. 受控放电实验的混淆因素与控制变量（§8.1）
8. 多任务学习头间平衡、session 级数据划分（§6.4）

其余（采集 App、converter、训练管线、JNI 样板、评测脚本、图表）全部【AI 落地】。

---

## 13. 附录：设备能力实测记录

采集时间：2026-10-20，`adb` 只读查询。

### 13.1 平台

```
ro.board.platform = canoe
ro.product.model  = V2547A
ro.product.device = PD2547
ro.soc.model      = SM8850   (骁龙 8 Elite Gen5)
ro.build.version.release = 16
ro.build.version.sdk     = 36
```

### 13.2 传感器（`dumpsys sensorservice`，共 55 个）

关键项：

```
lsm6dsvx Accelerometer        ST     type=1   minRate=1Hz  maxRate=479.85Hz  FIFO(10000,1000)  non-wakeUp
vsen_gyro Gyroscope           vivo   type=4   maxRate=479.85Hz              FIFO(10000,0)     non-wakeUp
qmc6309h  Magnetic Field      vivo   type=2   maxRate=100Hz  FIFO(10000,600) non-wakeUp
qmc6309h  Mag Uncalibrated    vivo   type=14  maxRate=100Hz  FIFO(10000,600)
tcs3743   Ambient Light       ams    type=5   on-change                     non-wakeUp
tcs3743   Proximity           ams    type=8   on-change                     wakeUp
tcs3425   light_cct           ams    type=66557 on-change
gravity / linear_acceleration QTI    type=9/10 maxRate=200Hz
rotation_vector / game_rv / geomag_rv        maxRate=100–200Hz
sns_smd    significant_motion QTI    type=17  one-shot        wakeUp
ccd_tilt   tilt_detector      QTI    type=22  special-trigger wakeUp
step_detect / pedometer       QTI/vivo type=18/19  需 ACTIVITY_RECOGNITION 权限
stationary_detect_wakeup / motion_detect     QTI  one-shot
```

vivo 自定义低功耗检测器（对标 + 弱标签来源）：

```
raiseup(66538, wakeUp)  putdown(66539)  vivomotion(66540, wakeUp)
angle_judge(66542)  lit_up / ambient_light_scene(66544, wakeUp)
smartprox(66554, wakeUp)  drop_depth(66561, wakeUp)
car_navi_detect(66579)  elevator_detect(66584)  airplane_identify(66585, wakeUp)
game_gesture(66590)  Camera_Noise(66594, wakeUp)
vibration_classification(66598, wakeUp)  activity_nwk/activity_wk(66601)
lowlight_detect(66631, wakeUp)  motionsickness(66628)  static_check(66618)
```

### 13.3 能力确认

```
气压计 / barometer : 无（grep pressure|barometer|bmp|altitude 为空）
SensorDirectChannel: 支持（dumpsys 显示 "0 open direct connections"）
WiFi               : 开启，"Wifi scanning is always available"，wifi_scan_throttle_enabled=true
BLE                : bluetooth_manager 可用
batterystats       : 可用（Discharge step durations / Estimated screen on time 可读）
ODPM 电源轨         : dumpsys power 未见 rail 导出，待 S6 进一步验证
```

### 13.4 可复用的既有工程经验（来自 `live_photo_agent`）

- QNN / Genie 端侧推理工具链已在本机跑通：`libQnnHtpV81Skel.so`（HTP V81，匹配 SM8850）、`ADSP_LIBRARY_PATH` 配置、`genie-app` CLI dialog 协议、3B 级模型 context binary 加载。
- 端侧模型部署目录约定、`chown`/权限坑、`deploy.sh` 式部署脚本模式。
- 真机 adb 驱动的自动化验证与审计日志落库模式（可迁移为占空比/功耗日志基建）。
