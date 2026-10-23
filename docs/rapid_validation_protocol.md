# 融合算法快速验证与采集协议 v2

## 1. 目标

本协议不是为了用少量窗口宣称高准确率，而是快速检查：

1. Activity 与 Carry 标签能否稳定采集；
2. 数据是否存在活动/姿态捷径；
3. 环境和事件模态是否提供 IMU 之外的互补信息；
4. 数据质量是否足够进入单模态和融合 baseline；
5. 哪些标签应停止或延后。

核心原则：**同一活动跨多个携带姿态、同一姿态跨多个活动、同一明暗条件跨多个标签。**

## 2. 设备准备

### 2.1 vivo 后台策略

采集前在 App 中点击“打开 vivo 后台耗电设置”，设为“允许后台耗电”。默认“智能控制”已实测会在约 5 s 后冻结 App，并制造 49–246 s 数据空洞。

ADB 入口：

```bash
adb shell am start -a com.iqoo.powersaving.battery.high.power.jump
```

### 2.2 统一条件

- participant 使用匿名 ID；
- 默认 45 s 自动停止；
- 点击开始后 3 s 内完成息屏/放置，评测自动裁掉首尾各 3 s；
- pilot 默认屏幕关闭，避免屏幕状态成为隐藏变量；
- 普通室内环境，除非 session 明确标记 dark/bright；
- 每次停止后重新开始，禁止把一段连续记录切成多个 session；
- 手机壳、口袋、包类型和异常写入 note；
- 不边走边操作采集 UI，运行/乘车以安全为先。

## 3. P0 反事实 Pilot

每格 2 个独立 session，共 12 个 session，约 9 分钟录制：

| Activity | Carry | Light | 重复 | 目的 |
|---|---|---|---:|---|
| still | in_hand | normal | 2 | 与 walking-in_hand 解耦 |
| still | in_pocket | normal | 2 | 与 walking-in_pocket 解耦 |
| walking | in_hand | normal | 2 | 活动证据 |
| walking | in_pocket | normal | 2 | 活动×携带联合 |
| still | desk_faceup | normal | 2 | 重力/光/接近融合 |
| still | desk_facedown | normal | 2 | 与 pocket 的 dark+near 歧义 |

操作定义：

- `still + in_hand`：坐或站，正常阅读握姿，允许自然微动；
- `still + in_pocket`：坐姿，手机完整放入口袋；
- `walking`：连续自然步行，不中途停留；
- `desk_faceup/down`：稳定平面，期间不触碰；
- 每个重复之间取出并重新放置手机，形成真正独立 session。

P0 只覆盖 `still/walking` 和四种核心 carry，用于发现 pipeline 与标签问题，不作为正式模型结果。

## 4. P1 模态互补 Pilot

P0 数据质量通过后补采：

| Activity | Carry | Condition | 重复 | 验证问题 |
|---|---|---|---:|---|
| still | in_hand | dark room | 2 | dark 是否被误学成 pocket |
| still | in_hand | bright/outdoor shade | 2 | light 鲁棒性 |
| walking | in_bag | same route | 3 | pocket vs bag 步态耦合 |
| walking | in_pocket | same route | 3 | pocket vs bag 对照 |
| running | in_hand | safe area | 2 | walking/running |
| running | in_pocket | safe area | 2 | activity/carry 解耦 |

磁干扰数据单独采集，不与正常标签混在同一 session：

- `still + in_hand` 正常磁环境 2 次；
- 相同姿态在可控磁源附近 2 次；
- note 标记 `mag_interference`，仅用于 robustness test。

## 5. P2 完整首轮

在 P0/P1 通过后，再采 `in_vehicle`：

| Activity | Carry | 重复 |
|---|---|---:|
| in_vehicle | in_hand | ≥3 |
| in_vehicle | in_pocket | ≥3 |
| in_vehicle | in_bag | ≥3 |

要求：

- 仅作为乘客采集；
- 至少覆盖平稳道路和启停道路；
- note 记录车辆类型和道路情况，但不记录精确位置；
- `cycling/elevator/stairs` 暂不采集。

正式训练前每个 core 组合目标 ≥3 个 session，至少跨 2 天；正式泛化结论需要增加 participant。

## 6. 质量验收

自动 FAIL 条件：

- 无 clean end；
- accel median rate <20 Hz；
- max accel gap >1 s；
- aligned accel valid ratio <90%；
- 有效时长 <30 s；
- 标签为 unknown；
- session 内发生未标记的活动/姿态切换。

建议警告但不自动删除：

- gyro/mag 缺失；
- light/proximity 从未更新；
- sensor rate 明显漂移；
- gate 输入质量异常；
- 事件流因权限不可用。

坏 session 保留原文件和失败原因，但不进入训练/测试。

## 7. 导出与初检

```bash
python3 training/scripts/pull_sessions.py --output data/fusion_pilot
python3 training/scripts/convert_sessions.py \
  data/fusion_pilot --output data/fusion_pilot_aligned
python3 training/scripts/validate_value.py \
  data/fusion_pilot --output reports/fusion_pilot_quality.md
```

进入 F1 后，单模态和融合训练使用独立脚本，不用窗口级随机 split。

## 8. Pilot 判断

### 数据 GO

- P0 12 个 session 中 ≥10 个质量 PASS；
- 相同组合的重复特征距离明显小于关键反事实组合；
- `still/in_hand` 与 `still/in_pocket` 不是只靠 activity 区分；
- `walking/in_hand` 与 `walking/in_pocket` 中 environment 分支存在互补证据；
- desk_facedown 与 pocket 虽然都可能 dark+near，但 motion/orientation 可分。

### 数据 CONDITIONAL GO

- Activity 可分但 carry 某一类别不稳定：缩小 carry 或补采；
- bag 不稳定：移回 stretch，不影响四类 carry 主线；
- vendor event 不可用：保留 optional mask，不阻塞主线。

### 数据 STOP / 修协议

- 重复 session 内部差异大于类别差异；
- 标签无法由实际操作稳定复现；
- 数据质量受系统后台策略持续破坏；
- 关键类别只能由采集地点、屏幕状态或人为流程捷径区分。

## 9. 正式算法 GO

Pilot 通过只代表“可以训练”，不代表融合成立。融合算法最终仍需满足：

- 比最佳单模态联合 macro-F1 +3 pt，或 carry +5 pt；
- corrupted set 上比 early fusion +5 pt；
- gate weight 随已知质量退化合理变化；
- temporal filter 将 false transitions/hour 降低 ≥30%。

详细定义以 `docs/fusion_algorithm_spec.md` 为准。
