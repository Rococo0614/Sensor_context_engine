# [归档] Phase B 快速价值验证协议 v1

> 本协议未充分服务质量感知融合与反事实消融，于 2026-10-23 归档。当前采集协议见 `docs/rapid_validation_protocol.md`。

目标不是在小样本上宣称模型精度，而是用最少采集判断：**手机现有传感器能否稳定区分关键活动和携带状态，是否值得继续投入真实模型与低功耗部署。**

## 1. 最小采集矩阵

### 1.1 极速 go/no-go（建议先做）

先只录下面 8 个 session，每个 45 s，总有效录制约 6 分钟：

| activity | posture | 重复 |
|---|---|---:|
| still | desk_faceup | 2 |
| still | in_hand | 2 |
| walking | in_hand | 2 |
| walking | in_pocket | 2 |

这组数据已足够对 activity 和核心 posture 做首次 leave-one-session-out。若达到 GO，再补完整矩阵。

### 1.2 完整首轮

每个 session 45–60 s；同一类别至少录制 2 个独立 session，中间停止并重新开始。

| 组 | activity | posture | 建议条件 | 重复 |
|---|---|---|---|---|
| A1 | still | desk_faceup | 普通室内光 | 2 |
| A2 | still | desk_facedown | 普通室内光 | 2 |
| A3 | still | in_hand | 正常手持阅读姿势 | 2 |
| A4 | still | in_hand | 暗光 | 2 |
| B1 | walking | in_hand | 正常步行 | 2 |
| B2 | walking | in_pocket | 同一路线 | 2 |
| B3 | walking | in_bag | 同一路线 | 2 |
| C1 | running | in_hand 或 in_pocket | 安全场地，慢跑 | 2（可选） |

最小版本不做 C1 也可以先分析，共 14 个 session，约 12–15 分钟。

## 2. 操作要求

1. vivo 真机先点击采集页“打开 vivo 后台耗电设置”，把本 App 改为“允许后台耗电”；默认“智能控制”会在约 5 s 后冻结 App 并造成长时间丢样；
2. 打开采集页面，participant 使用匿名 ID，如 `p01`；
3. 每个 session 只选择一个活动和一个携带状态，不在中途切换；
4. 默认 45 s 自动停止；点击开始后等待 3 秒再执行动作，评测脚本会自动裁掉首尾各 3 秒；
5. pickup/putdown/shake/flip 使用页面按钮打点，每种至少 20 次才分析事件价值；
6. 记录异常，如手机滑出口袋、接电话、暂停行走；
7. 原始文件导出后不要人工修改。

也可以通过 ADB 直接打开 vivo 设置页：

```bash
adb shell am start -a com.iqoo.powersaving.battery.high.power.jump
```

报告会自动将 `max accel gap > 1 s`、accelerometer 有效率 `<90%` 等 session 标为 `FAIL`，并排除在分类验证之外。

## 3. 一键导出与分析

```bash
python3 training/scripts/pull_sessions.py --output data/pilot
python3 training/scripts/convert_sessions.py data/pilot --output data/pilot_aligned
python3 training/scripts/validate_value.py data/pilot --output reports/rapid_validation.md
```

## 4. 价值判断标准

小样本结果只做 go/no-go：

- **GO**：按 session 留一验证时，activity 或 posture 至少一个维度的 macro-F1 比多数类基线高 0.20，并且混淆具有可解释性；
- **CONDITIONAL GO**：总体提升不足，但某些高价值类别（如桌面正反、手持/口袋）明显可分，缩小 taxonomy 后继续；
- **NO-GO / 改方案**：重复 session 内部特征不稳定、同类距离大于异类，或关键姿态在现有传感器下接近随机；
- 功耗不在 Phase B 下最终结论，但必须记录采集稳定性、丢样比例和文件增长速度。

## 5. 防止错误结论

- 不按随机窗口切分训练/测试；重叠窗会造成严重泄漏；
- 不把调试注入情境当作训练数据；
- 不用同一次连续录制切成两个“独立”session；
- 每类只有一个 session 时只输出描述统计，不输出分类分数；
- 本阶段结果不能替代多用户、跨设备和正式测试集。
