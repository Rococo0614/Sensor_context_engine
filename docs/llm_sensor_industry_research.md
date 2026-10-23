# LLM × 手机/可穿戴传感器行业与技术调研

> 调研日期：2026-10-23  
> 范围：公开论文、技术报告、厂商官方分享与专利；重点关注 IMU、位置、环境、健康与设备状态如何进入 LLM/Agent。  
> 说明：产品发布稿只能证明厂商公开宣称的能力；论文不等于量产；专利不等于实现，也不构成法律意见。

## 0. 项目决策记录（2026-10-23）

在本调研基础上，项目已完成一次方向收敛：

- 核心研究从“LLM 理解传感器/四维大而全情境”改为 **Activity + Carry 的质量感知多传感器融合**；
- 首版使用 IMU、mag、light/CCT、proximity、step 和硬件事件，不使用麦克风/GPS；
- 下游选择“情境化 Top-3 App 推荐”，只用于验证物理上下文增量；
- LLM 不进入底层融合和实时排序热路径，仅作为后续偏好解析、解释和习惯总结工具；
- 当前规范以 `docs/design.md` 和 `docs/fusion_algorithm_spec.md` 为准。

## 1. 执行摘要

### 1.1 最重要的结论

行业已经形成两条不同路线：

1. **感知基础模型路线（研究为主）**  
   将 IMU、PPG、温度等时序通过 sensor encoder、projector 或视觉化表示对齐到语言空间，使模型能分类、描述和回答传感器问题。代表包括 Google SensorLM、SensorLLM、LLaSA、Sensor2Text、PH-LLM。

2. **语义上下文路线（产品主流）**  
   由低功耗传感器通路和专用模型先产出 `walking / driving / dark / at_home / sleep_score` 等状态，再进入个人知识图谱、记忆系统或结构化 prompt，最后由 LLM 负责解释、规划、推荐和执行。

公开资料中，**没有证据表明主流手机产品让通用 LLM 以 25–100 Hz 常驻读取原始 IMU**。公开量产架构更接近：

```text
Always-on sensor hub / 专用感知模型
  -> 语义状态、事件、置信度
  -> 个人知识图谱 / 长期记忆
  -> LLM / Agent 推理与工具调用
  -> 建议或动作
  -> 用户反馈更新偏好
```

### 1.2 LLM 的增量价值不在基础分类

对 `walking / running / desk_faceup` 这类短窗识别，TCN/CNN/树模型通常更便宜、更稳定。LLM 的真正增量在：

- 将多个模态和长时间历史组合成高层情境；
- 利用世界知识解释“为什么”和“接下来怎么办”；
- 根据用户偏好选择不同建议、交互模态或工具；
- 把自然语言反馈转为结构化策略；
- 对聚合数据执行开放式查询、代码分析和总结；
- 在新任务、少样本标签和开放词汇场景下提高泛化。

### 1.3 对本项目的直接判断

“再做一个活动识别器”价值有限；更有价值的定位是：

> **为端侧 Agent 提供低功耗、带置信度、可追溯的 Physical Context Layer，并验证这些物理上下文是否能减少打扰、改善决策或形成隐私友好的个人记忆。**

最值得先验证的不是“LLM 能否看懂一串加速度数字”，而是：

1. 物理上下文加入后，Agent 决策是否优于无上下文和纯规则；
2. 哪些信息真正产生增量，哪些只是重复系统已有能力；
3. 改善是否足以覆盖传感器常驻与 LLM 推理的功耗、隐私和误触成本。

---

## 2. 证据分级

| 等级 | 来源 | 可以证明什么 | 不能证明什么 |
|---|---|---|---|
| A | 同行评审论文、正式技术报告 | 方法、数据、实验设置和局限 | 已量产、真实用户价值 |
| B | 厂商官方产品/技术分享 | 产品方向、公开架构、可见功能 | 内部具体模型、真实使用率和完整指标 |
| C | 专利 | 战略布局和可能实现方式 | 已实现、有效、必然覆盖具体产品 |
| D | 媒体与二手解读 | 辅助发现线索 | 不宜单独作为技术结论 |

本报告优先引用 A/B/C，关键判断尽量由至少两类证据交叉支持。

---

## 3. 学术与技术报告：传感器如何进入 LLM

### 3.1 路线 A：特征/状态文本化，再让 LLM 推理

这是成本最低、目前最容易落地的路线。

#### Penetrative AI（HotMobile / ACL Findings 2024）

- 同时研究两种输入：传感器派生的文字描述，以及数字化原始序列；
- 手机实验使用 accelerometer、GPS、Wi-Fi 等信息推断活动和环境；
- 强调利用 LLM 的常识和世界知识，而不只是模式分类；
- 结论是“可以工作，但依赖 prompt、专家知识和任务约束”，不能把通用 LLM 当确定性传感器算法。

**启示**：最现实的输入不是逐点数值，而是 `step_rate=...`、`motion_variance=...`、`near 5 APs` 等可解释特征。

来源：[论文](https://aclanthology.org/2024.findings-acl.437/)、[arXiv](https://arxiv.org/abs/2310.09605)

#### Health-LLM（CHIL 2024）

- 将 resting heart rate、sleep minutes 等聚合指标和人口学、健康知识、时间上下文组合成 prompt；
- 在 4 个公开数据集、10 个健康预测任务上评估；
- 小模型 HealthAlpaca 在 8/10 任务达到最好结果；上下文增强最高带来 23.8% 改善。

**启示**：LLM 的优势来自“传感器摘要 + 用户上下文 + 领域知识”的组合，而不是单独看波形。

来源：[PMLR](https://proceedings.mlr.press/v248/kim24b.html)

#### LLMSense（SenSys-ML 2024）

- 面向长时间行为轨迹和环境传感器记录，而非数秒分类；
- 支持原始 trace 或低层感知结果；
- 用“先总结、再推理”和“选择性历史”控制上下文长度；
- 在痴呆行为诊断、占用状态推理等任务报告约 80%+ 准确率；
- 建议小模型在边缘总结，大模型在云侧进行高层推理。

**启示**：长时间上下文必须先压缩；把全部窗口塞进 LLM 并不可扩展。

来源：[arXiv](https://arxiv.org/abs/2403.19857)、[IEEE](https://doi.org/10.1109/SenSys-ML62579.2024.00007)

#### DailyLLM（2025 预印本）

- 按位置、运动、环境、生理四维聚合手机/手表传感器；
- IMU 先计算时域、频域、方向与周期特征，光照/温度/噪声先转语义等级；
- 特征写入结构化 prompt，1.5B LLM 生成活动日志与长期总结；
- 声称 1.5B 模型在日志生成上超过 70B 基线并可部署 Raspberry Pi；
- 部分综合数据由多个数据集按规则合成，因此不能直接视作真实手机量产指标。

**启示**：它的工程架构与本项目最接近；真正关键的是特征、语义化、窗口和 prompt，而非模型参数量。

来源：[arXiv](https://arxiv.org/abs/2507.13737)

### 3.2 路线 B：Sensor Encoder → Projector → LLM Token Space

这条路线研究新颖性更强，但需要大量 sensor-language 配对数据。

#### LLaSA（UbiComp 2025）

- LIMU-BERT 编码原始 accelerometer/gyro，MLP projector 映射到 LLaMA/Vicuna token 空间；
- 发布 35,960 个 IMU-caption 和 199,701 个 sensor-QA 样本；
- 7B/13B 模型支持开放式问答和解释；
- 通用 GPT/Vicuna 直接处理数值时，HAR F1 大约只有 0.04–0.25；领域对齐后的 LLaSA 达约 0.65–0.84；
- 论文明确承认 7B/13B FP16 不适合手机实时部署。

**启示**：通用 LLM 并不会天然理解 IMU；真正有效的是 sensor encoder、跨模态对齐和领域监督。

来源：[论文/DOI](https://doi.org/10.1145/3714394.3756187)、[arXiv](https://arxiv.org/abs/2406.14498)

#### SensorLLM（EMNLP 2025）

- 第一阶段使用自动生成的趋势描述进行 Sensor-Language Alignment；
- 使用特殊 token 区分通道，学习数值变化、通道特征和可变时长；
- 第二阶段再进行任务调优；
- 目标是 HAR，而不是端侧主动服务。

**启示**：可以利用我们 Phase B 的特征脚本自动生成“趋势文本”作为弱监督，但仍需要显著训练投入。

来源：[ACL Anthology](https://aclanthology.org/2025.emnlp-main.19/)

#### Sensor2Text（IMWUT 2024）

- 多个 wearable sensor encoder 与语言联合编码；
- 通过视觉语言模型进行 teacher-student 跨模态迁移；
- 再接入 LLM 获得活动问答和对话能力。

**启示**：若目标是“问我的传感器发生了什么”，应训练独立 sensor-language encoder，而不是让聊天模型承担全部感知。

来源：[ACM](https://doi.org/10.1145/3699747)、[arXiv](https://arxiv.org/abs/2410.20034)

### 3.3 路线 C：大规模 Sensor-Language Foundation Model

#### Google SensorLM（NeurIPS 2025）

- 59.7M 小时、103,643 人、127 个国家的 Fitbit/Pixel Watch 去标识数据；
- 模态包括 PPG、accelerometer、皮电、皮温、高度等；
- 用层次 caption pipeline 自动产生统计、结构和语义描述；
- 结合 CLIP 式对比学习和 caption 生成训练；
- 支持零/少样本活动识别、检索、caption 和未见任务泛化；
- sensor encoder 从约 3M 到 1.27B 参数；主要输入为分钟级聚合特征和长时间窗口，不是手机上 50 Hz 常驻 LLM。

**启示**：Sensor-Language 预训练确实有效，但 Google 的优势来自数千万小时配对数据。我们的项目不能正面复制，应利用小数据、设备专属情境和端侧系统闭环做差异化。

来源：[Google Research](https://research.google/blog/sensorlm-learning-the-language-of-wearable-sensors/)、[NeurIPS](https://proceedings.neurips.cc/paper_files/paper/2025/hash/42cd98f0e7520d4a63c34891ac1c972f-Abstract-Conference.html)

### 3.4 路线 D：把曲线画成图，让 VLM 看

#### By My Eyes（EMNLP 2024）

- 将 sensor trace 自动绘图后输入多模态 LLM；
- 9 个任务、4 类传感模态上，比数值文本 prompt 平均高约 10% 准确率；
- token 成本降低约 15.8×。

**启示**：适合云端分析和快速基线，不适合常驻端侧；但可用于数据探索、教师标注和 Demo。

来源：[ACL Anthology](https://aclanthology.org/2024.emnlp-main.133/)

### 3.5 路线 E：LLM 不读原始数据，而是调用分析工具

#### Google PHIA（Nature Communications 2026）

- LLM Agent 通过 Python sandbox 分析 wearable 表格，并通过搜索补充领域知识；
- 采用多步 ReAct，而非要求 LLM 心算所有统计；
- 超过 4,000 个问题、650 小时人工评测；客观数值问题约 84% 准确，开放问题约 83% 获正面评价。

**启示**：复杂数值分析应交给确定性代码，LLM 负责拆题、选工具、解释结果。这比把一串数字直接喂给 LLM 更可靠。

来源：[Nature Communications](https://www.nature.com/articles/s41467-025-67922-y)、[arXiv](https://arxiv.org/abs/2406.06464)

### 3.6 端侧演示的现实成本

UbiComp 2024 的 `Enabling On-Device LLMs Personalization with Smartphone Sensing` 在 Pixel 8 Pro 上运行 Llama-3-8B，主要输入是 screen text 与问卷，而非高频 IMU。论文报告一次 5 分钟案例约消耗 3% 电量，并观察到 hallucination 和概念反转。

**启示**：即使不是原始 IMU，持续运行 8B LLM 也不可接受；LLM 必须事件触发、低频调用。

来源：[论文](https://arxiv.org/abs/2407.04418)、[ACM DOI](https://doi.org/10.1145/3675094.3677545)

---

## 4. 手机厂商和芯片厂商的实际路线

### 4.1 Qualcomm：Always-on Sensing Hub → Personal Knowledge Graph → Agent

这与目标手机最直接相关。Snapdragon 8 Elite Gen 5 官方 Product Brief 写明：

- Sensing Hub 和 Hexagon NPU 共同支持 agentic AI；
- Sensing Hub 有双 Micro NPU 处理 audio、voice、sensors；
- 用于创建 personal knowledge graph；
- Qualcomm Personal Scribe 与个人图谱支撑基于 routine、preference、conversation 的推荐和代理行动。

上一代公开白皮书还将 Sensing Hub 定位为小模型和传感器 24/7 contextualization 的 always-on 处理器，独立电源域可在主 SoC 休眠时运行。

**判断**：芯片厂定义的产品路径不是“LLM 常驻读传感器”，而是 Micro NPU 常驻感知、生成个人上下文，再由更大的 NPU/LLM 按需推理。

来源：[Snapdragon 8 Elite Gen 5 Product Brief](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/Snapdragon-8-Elite-Gen-5-product-brief.pdf)

### 4.2 vivo：感知、记忆、Harness、工具执行已经成型，但公开物理情境接口仍不清楚

vivo 公开路线分两阶段：

- 2024 BlueOS 2：底层是 BlueLM + VCAP；上层服务引擎包含数据服务、意图感知和决策；意图框架根据行为与使用习惯感知场景，再规划执行；
- 2026 OriginOS 7 / BlueOS 4：BlueLM-Nano 负责端侧实时感知和个人记忆，公开列举的输入主要是屏幕、图像、视频、文本、文件；BlueLM Harness 贯通感知、记忆、规划、执行，并暴露 6000+ 原子技能。

与此同时，OriginOS 的物理情境功能仍大量由专用模块完成，例如电梯/车载检测、场景能效、环境光、自适应亮度、运动和健康算法。

**关键空白**：公开材料没有说明 accelerometer/gyro/light 的低功耗语义状态如何标准化进入 BlueLM-Nano/Harness。这正是本项目可以验证的桥接层。

来源：[vivo 2024 VDC](https://www.vivo.com.cn/brand/news/detail?id=1270&type=0)、[vivo 2026 VDC](https://www.vivo.com.cn/brand/news/detail?id=1389&type=0)、[OriginOS 7](https://www.vivo.com.cn/originos)

### 4.3 OPPO：PersonaX 把“泛在感知”直接接到个人记忆

OPPO 2025 AI 峰会公开了三层：

1. On-Device Compute；
2. PersonaX 记忆共生引擎；
3. Agent Matrix 工具与跨设备执行。

PersonaX 明确宣称整合本地、一方、第三方内容数据、设备状态、网络状态，以及行为、环境、情绪三类传感器数据，用于构建长期记忆和“懂你 AI”。

**判断**：OPPO 是手机厂中对“感知 → 记忆 → Agent”描述最明确的公开案例，但没有公布原始信号编码、功耗和准确率，因此更像产品架构而非可复现技术报告。

来源：[OPPO AI 峰会](https://www.oppo.com/cn/newsroom/press/540)

### 4.4 Samsung：Personal Data Engine + Personal Knowledge Graph

- Galaxy S25 的 Personal Data Engine 在端侧学习习惯和偏好；
- Samsung 收购 Oxford Semantic Technologies，将动态跨应用信息组织成 personal knowledge graph；
- Now Brief 使用 location、time、usage patterns、calendar、weather，以及 Galaxy Watch/Ring 的健康摘要进行主动建议；
- Samsung 明确说 PDE 当前主要分析 Samsung 原生应用；没有公开说通用 LLM 直接消费手机 IMU。

**判断**：Samsung 将 sensor/health 数据先变成健康指标与知识图谱节点，再供 AI 功能使用。

来源：[Galaxy S25](https://news.samsung.com/global/samsung-galaxy-s25-series-sets-the-standard-of-ai-phone-as-a-true-ai-companion)、[Oxford Semantic 收购](https://news.samsung.com/global/samsung-electronics-announces-acquisition-of-oxford-semantic-technologies-uk-based-knowledge-graph-startup)

### 4.5 Apple：产品使用 personal/app/screen context，传感器 LLM 仍主要在研究

- Apple Intelligence 使用约 3B 端侧模型，强调 personal context、on-screen awareness、App Intents 和跨应用动作；
- 公开产品资料的 personal context 主要是邮件、消息、日历、照片、屏幕和应用内容；
- Apple 2025 研究展示了 LLM 对 modality-specific audio/motion 模型输出做 late fusion，可零/一样本分类活动；这不是量产声明；
- Apple 的路线也体现“专用模型先理解模态，LLM 后融合”。

来源：[Apple Foundation Models](https://machinelearning.apple.com/research/apple-intelligence-foundation-language-models)、[Apple Intelligence](https://www.apple.com/newsroom/2024/06/introducing-apple-intelligence-for-iphone-ipad-and-mac/)、[Late Sensor Fusion](https://machinelearning.apple.com/research/multimodal-sensor-fusion)

### 4.6 Google：产品 Personal Intelligence 与 SensorLM 研究仍是两条线

- Gemini Personal Intelligence 公开产品主要连接 Gmail、Photos、Calendar、Maps、Search 等数字上下文；
- SensorLM、PH-LLM、PHIA 则证明 wearable sensor-language、健康推理和 agentic analysis 可行；
- 尚无公开证据表明 Pixel/Gemini 手机助手已把高频手机 IMU 直接接入 Gemini。

**判断**：Google 已有最强 sensor-language 研究储备，但商业产品仍优先数字内容、健康摘要和受控数据源。

来源：[SensorLM](https://research.google/blog/sensorlm-learning-the-language-of-wearable-sensors/)、[Personal Intelligence](https://blog.google/innovation-and-ai/products/gemini-app/personal-intelligence)、[PH-LLM](https://www.nature.com/articles/s41591-025-03888-0)

### 4.7 小米、荣耀、华为：公开产品更强调主动服务与系统上下文

- Xiaomi HyperMind 公开描述环境、视觉、听觉和行为感知，用于跨设备主动智能；
- HONOR YOYO/Magic Portal 主要基于屏幕、相机、多模态意图和系统服务；
- HarmonyOS 小艺与情境感知服务强调多设备、位置、日程、应用和系统能力；
- 公开材料中同样缺少“原始手机 IMU → LLM”的可复现链路。

这进一步说明：**系统级厂商竞争焦点是 context graph、memory、tool ecosystem 和主动服务，不是单独追求 IMU 分类榜单。**

---

## 5. 产业参考架构

电信终端产业协会《手机智能体技术研究报告 R/TAF 015—2026》由中国信通院牵头，华为、OPPO、荣耀、vivo、蚂蚁参编。报告给出的四层架构是：

1. 智能交互层；
2. 智能连接层：GPS、麦克风、摄像头、accelerometer、时间、网络、日历；
3. 智能中枢层：通常由大模型驱动，完成意图理解、规划、决策和记忆；
4. 智能执行层：API、App Intents、GUI 操作。

这份产业共识明确把**传感器连接层**与**LLM 中枢层**分开，支持本报告的核心判断。

来源：[《手机智能体技术研究报告》PDF](https://www.taf.org.cn/upload/notice/2026-0615-145839-2020167396.pdf)

---

## 6. 专利信号

| 公开号 | 申请人 | 关键内容 | 对本项目的意义 |
|---|---|---|---|
| [WO2024129101A1](https://patents.google.com/patent/WO2024129101A1/en) | Google | ambient light、accelerometer、orientation 等可直接输入 LLM，或先变成 `dark / freefall / driving` 文本；阈值和持续时间控制触发 | 几乎完整覆盖“sensor state → text context → chatbot”；该 PCT 页面显示 ceased，但仍是明确技术信号 |
| [US20250131020A1](https://patents.google.com/patent/US20250131020A1/en) | Qualcomm | sensing hub 汇总位置、运动、视觉、生理、交互反馈；物理情境选择 user profile/model，增强 prompt，并依据隐式反馈更新 | 与目标芯片和“用户反馈闭环”最接近；说明价值重点在 profile/prompt adaptation |
| [US20240403564A1 / US12417356B2](https://patents.google.com/patent/US20240403564A1/en) | Google | 用户特征和 local context 生成 soft prompt/user embedding；LLM 参数可冻结；反馈更新 embedding | 支持“不要给每人微调整个 LLM，而是学习小型用户表征” |
| [US20250053430A1](https://patents.google.com/patent/US20250053430A1/en) | Meta | 传感器上下文 + 用户高层目标生成 prompt，LLM 产生 contextualized action recommendation | 说明 sensor value 最终要落到高层目标和行动，而不是只输出标签 |
| [US12406664B2](https://patents.google.com/patent/US12406664B2/en) | Apple | 按需选择 on-screen/device context、抽取 metadata、消除助手请求歧义 | 强调 context 应按请求选择，避免每次把全部个人数据放入模型 |

专利共同趋势：

- 先筛选、分类或摘要传感器状态；
- 按情境选择 profile、prompt 或模型；
- LLM 负责语言和高层决策；
- 反馈持续更新个人模型；
- 极少主张“把全部原始流永久塞进 LLM”。

---

## 7. 横向比较

| 路线 | 典型代表 | 优点 | 主要问题 | 适合本项目 |
|---|---|---|---|---|
| 数值直接文本化 | Penetrative AI | 零训练、快验证 | token 多、数值推理不稳定 | 仅做 baseline |
| 特征/标签结构化 prompt | DailyLLM、Health-LLM | 可解释、低成本、易端侧 | 特征设计决定上限 | **很适合 MVP** |
| Sensor encoder + projector | LLaSA、PH-LLM | 真正跨模态、开放问答 | 需要大量配对数据和训练 | 中期研究线 |
| Sensor-language 预训练 | SensorLM | 零/少样本和开放词汇 | 数据与算力门槛极高 | 不宜正面复制 |
| 曲线图 → VLM | By My Eyes | 快速、token 成本低 | 端侧常驻不经济 | 教师/分析工具 |
| 专用模型 late fusion | Apple research | 各模态稳定、可复用 | 需要融合协议 | **很适合产品** |
| LLM Agent + 数值工具 | PHIA | 数值可靠、开放查询 | 推理延迟高 | 适合低频总结 |
| Context graph + Agent | Qualcomm/Samsung/OPPO/vivo | 产品闭环、长期个性化 | 数据治理和错误触发难 | **最有产业价值** |

---

## 8. 对当前项目的机会判断

### 8.1 不建议作为主线的方向

#### “让 3B LLM 直接连续读 50 Hz IMU”

原因：

- 通用 LLM 对原始数字能力弱，已有论文反复证明需要对齐；
- token、延迟和功耗不适合 always-on；
- 我们没有 10 万级 sensor-caption/QA，更没有 Google 级数据；
- 基础活动分类没有必要支付 LLM 成本；
- 输出不确定性不适合安全动作。

#### “只做更高 F1 的活动分类器”

Android、Qualcomm、vivo 已有大量活动和情境 detector。除非我们能证明显著更低功耗、开放 taxonomy 或跨情境共享，否则难形成用户价值。

### 8.2 值得投入的三个方向

#### 方向 1：Physical Context Router（推荐主线）

传感器不替 LLM 回答，而是决定：

- 当前是否适合打扰；
- 应该显示、播报、振动还是延后；
- 应选哪个用户 profile、prompt 和工具；
- 是否允许自动执行，还是必须确认；
- 需要多少上下文与多大的模型。

示例：同一句“提醒我回复”，静止手持时显示卡片，走路口袋时延后，驾车时只播报高优先级摘要。

**可量化价值**：错误打扰率、建议接受率、延迟处理率、LLM 调用次数和额外功耗。

#### 方向 2：Privacy-Preserving Physical Memory

将原始流在端侧压缩成事件：

```text
08:12  从桌面拿起
08:15  步行，手机在口袋
08:23  进入车辆
08:55  到达后重新手持
```

LLM 只检索事件和摘要，回答：

- “我刚才大概什么时候出门？”
- “今天通勤时长是否异常？”
- “为什么我没看到那条提醒？”
- “我的手机通常在什么情境下被放进包里？”

相较相机 life-logging，它更隐私、低功耗，但信息密度也更低。

#### 方向 3：Sensor-Grounded Explanation / QA（研究副线）

用小型 TCN 输出标签和 32–64 维 embedding，再投影成少量 sensor tokens 接入端侧 3B LLM，比较：

1. 只给标签；
2. 给统计特征文本；
3. 给 sensor tokens。

任务不是再分类，而是回答“为什么判断为口袋步行”“近期发生了什么变化”“置信度为什么低”。如果 sensor tokens 对解释和未见组合没有增益，就及时停止该研究线。

---

## 9. 推荐的新项目论点

原论点偏向“一个共享模型输出四维标签”。结合调研，更有价值的论点可以升级为：

> 在 Snapdragon Sensing Hub 和端侧 NPU 上构建一个低功耗 Physical Context Layer，将手机碎片化传感器转换为带置信度、时效和来源的语义事件；再通过个人上下文路由和端侧 LLM，使 Agent 在正确的时间、以正确的交互方式调用正确的能力，并以真实用户反馈证明它比无上下文 LLM 和固定规则更少打扰、更符合个人习惯。

对应系统：

```text
Sensor Hub / Stage 0
    -> Context Encoder / Stage 1
    -> Context Event + confidence + freshness + provenance
    -> Personal Context Graph
    -> Context Router
         ├─ deterministic rule
         ├─ small policy model
         └─ on-demand LLM advisor
    -> allow-listed action
    -> feedback and memory update
```

### LLM 输入应是“最小充分上下文”

```json
{
  "physical_context": {
    "activity": {"value": "walking", "p": 0.91},
    "carry": {"value": "in_pocket", "p": 0.88},
    "environment": {"light": "dark", "p": 0.95},
    "stable_for_s": 42,
    "changed": ["desk_faceup -> in_hand -> in_pocket"]
  },
  "user_preference": {
    "while_walking": "defer_non_urgent"
  },
  "available_actions": [
    "do_nothing",
    "defer_notification",
    "speak_short_summary"
  ]
}
```

而不是数千个 accelerometer 浮点数。

---

## 10. 下一轮价值验证建议

### 实验 A：LLM 到底有没有增量

在相同 ContextSnapshot 上比较：

1. 无上下文 LLM；
2. 固定规则；
3. 结构化上下文 + LLM；
4. 结构化上下文 + 用户历史 + LLM。

使用 20–30 个冲突场景和 3 类虚拟用户偏好，人工盲评：动作正确性、理由、是否打扰、是否违反约束。

### 实验 B：哪种传感器表示最有效

比较：

- 标签 + confidence；
- 标签 + 统计特征；
- 传感器趋势 caption；
- sensor embedding/pseudo tokens（后续）。

若标签已经达到相同决策质量，就没有必要开发复杂 sensor-language adapter。

### 实验 C：真实闭环价值

选择一个动作边界清晰的场景，例如“移动状态下通知呈现方式”，连续使用 3–7 天，记录：

- 展示次数；
- 接受/拒绝/撤销；
- 错误抑制；
- 用户手动纠正；
- 日均功耗；
- LLM 唤醒次数。

**继续条件**：上下文方案相对规则 baseline 显著降低错误打扰或提高接受率；否则项目保留为低功耗 context engine，不强行绑定 LLM。

---

## 11. 最终判断

1. **传感器 + LLM 是真实方向，不是伪需求**：Google、Apple、Qualcomm、Samsung、OPPO、vivo 的论文、芯片和产品路线均在向它靠拢。
2. **但“LLM 直接读原始手机传感器”尚不是量产主流**：研究上可行，端侧数据、稳定性和功耗仍不成熟。
3. **产业价值集中在个人上下文、记忆和主动服务**：传感器只是证据源，LLM 是高层推理器，不应替代低功耗感知模型。
4. **本项目的差异化机会是真机可验证的桥接层**：把 vivo/Qualcomm 已有但碎片化、黑盒化的 sensor context 转成开放、可解释、可审计的 Agent Context API。
5. **最稳妥的主线是 Context Router + Personal Memory**，Sensor-to-LLM adapter 作为有明确停止条件的研究副线。
