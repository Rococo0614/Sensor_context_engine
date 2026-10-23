# Sensor Context Engine

端侧低功耗、质量感知、多率传感器融合工程。核心任务是 Activity + Carry Posture 联合估计，下游以情境化 App 推荐验证物理上下文价值。

- 总体设计：[`docs/design.md`](docs/design.md)
- 融合算法规格：[`docs/fusion_algorithm_spec.md`](docs/fusion_algorithm_spec.md)
- Android 技术方案：[`docs/android_technical_plan.md`](docs/android_technical_plan.md)
- LLM × 传感器行业调研：[`docs/llm_sensor_industry_research.md`](docs/llm_sensor_industry_research.md)

## 当前实现

`android/` 已提供可运行的采集与 legacy 闭环：

```text
accelerometer / light / proximity
  -> 低功耗变化门控
  -> 可替换情境推理接口
  -> 暗光手持阅读策略（legacy smoke test）
  -> 建议通知
  -> 用户接受/拒绝反馈
  -> 本地个性化统计
```

当前情境推理是工程联调用启发式基线，不代表最终模型精度。新主线将增加模态质量、缺失 mask、质量门控融合和时间状态约束；在融合算法达到验收标准后，再开发 App 推荐组件。

## 构建

```bash
cd android
cp local.properties.example local.properties
# 按本机路径修改 sdk.dir
./gradlew testDebugUnitTest assembleDebug
```

安装到已连接设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Legacy 真机闭环

1. 打开 **Sensor Context Engine**，授予“身体活动”和通知权限；
2. 点击“启动感知”，等待页面出现实时情境；
3. 点击“调试：注入暗光手持静止情境”；
4. 在建议通知中选择“接受”或“暂不需要”；
5. 返回 App 查看持久化的偏好统计。

决策审计日志位于应用私有目录：

```bash
adb shell run-as com.sensorcontext.engine.debug \
  cat files/traces/decision_trace.jsonl
```

## 融合数据采集与快速验证

从首页进入“Phase B 数据采集”，为每个 session 选择固定 activity/posture/environment/light 标签。必须按 [`docs/rapid_validation_protocol.md`](docs/rapid_validation_protocol.md) 交叉采集，禁止把 activity 和 posture 绑定。vivo 设备在息屏采集前必须通过页面入口将本 App 设为“允许后台耗电”。

导出、转换和生成 go/no-go 报告：

```bash
python3 training/scripts/pull_sessions.py --output data/pilot
python3 training/scripts/convert_sessions.py data/pilot --output data/pilot_aligned
python3 training/scripts/validate_value.py \
  data/pilot --output reports/rapid_validation.md
```

二进制格式与对齐规则分别见 [`docs/binary_log_spec.md`](docs/binary_log_spec.md) 和 [`docs/alignment_spec.md`](docs/alignment_spec.md)。旧方向和已完成工程记录保存在 `docs/*_v1_archive.md`。
