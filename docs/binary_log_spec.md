# SCELOG v1 二进制采集格式

`SCELOG` 是 Phase B 使用的无依赖、可流式写入、可从异常中恢复的端侧采集格式。首版不采用 protobuf，是为了快速验证数据价值并避免采集 App 引入额外 runtime；转换成统一表结构后，后续可无损迁移至 protobuf/FlatBuffers。

## 1. 基本约定

- 文件扩展名：`.scelog`；
- 字节序：big-endian；
- 字符串：`uint16 byte_length + UTF-8 bytes`；
- 时间戳：Android sensor event 的 `elapsedRealtimeNanos`；
- wall time 只用于 session 定位，不用于传感器对齐；
- 每个 record 使用长度前缀，文件异常结束时可保留此前完整 record；
- v1 单条 record 最大 1 MiB，单个字符串最大 65535 bytes。

## 2. 文件头

```text
bytes[8]  magic = "SCELOG01"
uint16    version = 1
uint16    flags = 0
uint32    header_payload_length
bytes[]   header_payload
```

`header_payload`：

```text
int64  session_start_elapsed_ns
int64  session_start_wall_ms
string session_id
string participant_id
string activity_label
string posture_label
string environment_label
string light_label
string note
string manufacturer
string model
string device
string build_fingerprint
string app_version
uint16 sensor_count
SensorDescriptor sensors[sensor_count]
```

当前融合实验允许的主标签由 `docs/fusion_algorithm_spec.md` 冻结。`SCELOG v1` 本身仍使用字符串编码，因此项目方向变更不需要破坏二进制格式；dataset builder 会拒绝 `unknown` 和规格外标签。

`SensorDescriptor`：

```text
uint16 sensor_id                 # session 内稳定，从 0 开始
int32  android_sensor_type
int32  reporting_mode
int32  min_delay_us
int32  max_delay_us
int32  fifo_max_event_count
float32 resolution
float32 max_range
uint8 flags                      # bit0 = wakeup sensor
string name
string vendor
string string_type
```

文件头枚举设备的全部传感器；事件记录只引用 `sensor_id`，避免在每条事件重复写名称。

## 3. Record 帧

每帧结构：

```text
uint32 payload_length
uint8  record_type
bytes  type_specific_payload
```

### 3.1 SensorEvent（type = 1）

```text
int64   elapsed_ns
uint16  sensor_id
int8    accuracy
uint8   value_count
float32 values[value_count]
```

### 3.2 Marker（type = 2）

```text
int64  elapsed_ns
string marker                    # pickup / putdown / shake / flip / custom
string note
```

### 3.3 CleanEnd（type = 3）

```text
int64 elapsed_ns
int64 wall_ms
int64 data_record_count          # 不包含 CleanEnd 自身
```

解析到 EOF 但没有 `CleanEnd` 时，session 标记为 `truncated=true`；此前完整帧仍可使用。

## 4. 文件命名与目录

端侧私有目录：

```text
files/recordings/<UTC timestamp>_<session-id>.scelog
```

文件名只允许 `[A-Za-z0-9._-]`，用户输入不会直接形成路径。通过 debug 包的 `run-as` + ADB 脚本导出，正式产品再替换为受控 `FileProvider`。

## 5. Schema 演进

- 修改字段顺序或编码必须提升 `version`；
- 新增 header 尾字段可以利用 `header_payload_length` 跳过，但仍应提升 minor 文档版本；
- 未知 record type 必须依靠 `payload_length` 跳过，不能使整个 session 解析失败；
- 原始 `.scelog` 只作为采集事实源，训练侧统一转换为 aligned CSV/Parquet。
