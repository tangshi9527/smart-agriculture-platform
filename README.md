# 智慧农业云管端一体化平台

> 个人练习项目。一台开发机上写完并跑起来的完整链路：MQTT 设备接入 → 时序存储 → 降采样 → 告警判定 → WebSocket 大屏推送。
> **没有生产部署，没有真实硬件接入。** InfluxDB / RabbitMQ / MySQL 只有代码和单元测试，没起过真实服务 —— 详见「[八、已知限制](#八已知限制)」。
>
> 文中标 `[待填：…]` 的地方是还没定的命令 / 路径 / 地址，清单见文末。

---

## 一、项目用途

给农业大棚做设备接入和数据看板：传感器经 MQTT 上报温湿度、光照、土壤数据，平台解析后更新设备影子、写时序库、按规则判告警，再推给大屏。

**用途定位**：技术验证 + 作品集，不是可交付生产的系统。

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.2.5 / Java 21 |
| 设备接入 | Netty 4.1，自研 MQTT 3.1.1 编解码（不接现成 broker） |
| 时序存储 | InfluxDB 2.x，Flux Task 做 1 分钟均值降采样 |
| 关系库 | H2（默认）/ MySQL（只有 profile 和驱动） |
| 缓存 | Redis 存设备影子，无 Redis 时降级进程内 Map |
| 消息 | 进程内总线 / RabbitMQ，由 `mq.enabled` 切换 |
| 安全 | Spring Security + JWT；设备侧「设备 ID + 密钥」 |
| 容错 | resilience4j 熔断、令牌桶限流、幂等回执、可恢复降级 |
| 前端 | Vue 3 + Vite + Element Plus + ECharts |

数据流一行：`模拟器 → MQTT(1883) → 清洗 + 影子 → 事件总线 → {存储 → 攒批 → InfluxDB | 告警 → H2 | 推送 → WebSocket} → 大屏`

---

## 二、安装步骤

**前置依赖**

| 依赖 | 版本要求 | 是否必需 |
|---|---|---|
| JDK | 21（`pom.xml` 里 `java.version=21`） | 必需 |
| Node.js | 18+（Vite 5 的要求） | 只做前端时必需 |
| Python | 3.x，仅用标准库 | 跑模拟器时必需 |
| Maven | 不需要，仓库自带 wrapper（`mvnw` / `mvnw.cmd`） | — |
| Docker | 任意较新版本 | 可选，只用于一键拉起依赖 |

**步骤**

1. 获取代码

```bash
git clone [待填：仓库地址]
cd smart-agriculture-platform
```

2. 装后端依赖并编译（首次会下载依赖，比较慢）

```bash
./mvnw -q -DskipTests package      # Windows: mvnw.cmd -q -DskipTests package
```

3. 装前端依赖

```bash
cd frontend && npm install && cd ..
```

4. 环境变量（可选，不配也能跑）

```bash
cp .env.example .env
```

`application.yml` 里所有凭据都是 `${环境变量:演示默认值}` 形式，不配就走演示值。`.env` 已被 `.gitignore` 忽略。

5. 验证安装是否到位

```bash
./mvnw test                        # 28 个测试类 / 105 个用例，应全绿
```

---

## 三、运行方式

### 方式 A：本地零依赖（默认，最省事）

不需要装任何外部服务，H2 存关系数据、进程内总线传消息、InfluxDB 不可用时走内存降级。

```bash
# 后端：HTTP 8080，MQTT 1883
./mvnw spring-boot:run             # Windows: mvnw.cmd spring-boot:run

# 或走脚本（带 JVM 参数，见下方说明）
scripts/start-backend.sh           # Windows: scripts\start-backend.bat
```

```bash
# 前端（另开一个终端）
scripts/start-frontend.sh          # Windows: scripts\start-frontend.bat
# 等价于：cd frontend && npm install && npm run dev
```

### 方式 B：Docker Compose（一次拉起全部依赖）

```bash
cp .env.example .env               # compose 会读它
docker compose up -d
```

compose 里的服务：`influxdb`、`redis`、`rabbitmq`、`mysql`、`app`、`frontend`。
`app` 依赖前四个的 healthcheck 通过后才启动。

### 方式 C：跑已打包的 jar

```bash
# 必须带 JVM 参数，原因见「七、常见问题」第 7 条
java -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Xms512m -Xmx4g \
     -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./heapdump.hprof \
     -jar target/smart-agriculture-platform.jar
```

`scripts/run-backend.bat` 就是这条命令，不想手敲可以直接跑它。

### 端口与地址

| 用途 | 默认端口 | 说明 |
|---|---|---|
| 后端 HTTP | 8080 | `SERVER_PORT` 可改 |
| MQTT | 1883 | `MQTT_PORT` 可改 |
| 前端开发服务器 | 5173 | `frontend/vite.config.js` 里写死 |
| 前端（compose / nginx） | 80 | 反代到 `app:8080` |
| InfluxDB | 8086 | compose 内 |
| 数据库文件 | `./data/agri.mv.db` | `DB_PATH` 可改 |

部署后的访问地址：`[待填：线上地址]`
服务器部署目录：`[待填：部署路径]`

---

## 四、使用示例

下面 `$BASE` 指后端地址，本地默认 `http://localhost:8080`，线上填 `[待填：线上地址]`。

**1. 登录拿 JWT**

启动时 `DataInitializer` 会建一个默认账户 `admin / admin123`，并预置 `device-00001` ~ `device-00010` 十个示例设备密钥。

```bash
curl -X POST "$BASE/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

除 `/auth/**`、`/ws/**`、`/actuator/**`、`/simulator/**`、`/h2-console/**` 外，其余接口都要带 `Authorization: Bearer <token>`。

**2. 查健康状态**

降级原因、队列积压、脏数据计数都在这里，排查先看它。

```bash
curl "$BASE/api/system/health" -H "Authorization: Bearer $TOKEN"
```

**3. 起设备模拟器**

```bash
# 独立跑（默认 200 设备 / 200 连接）
python simulator/device_sim.py

# 或走脚本
scripts/run-sim.bat

# 调规模（1000 连接属于压测档，注意机器温度）
python simulator/device_sim.py --connections 1000 --connect-pace-ms 5

# 不连网，只跑物理并打印统计（改参数后先跑这个，两秒出结果）
python simulator/device_sim.py --dry-run --sim-hours 24
```

模拟器不是「每帧随机数」，而是走一条物理链路：

```
室外气象 → 温室气候真值 → 执行器仲裁 → 传感器非理想化 → 故障状态机 → MQTT
          能量/水汽/CO2    手动优先/超时回落   滞后·辐射误差·漂移   卡值·漂移·尖峰
```

- **4 棚 × 50 测点**：同棚共享一套真值，只差传感器个体标定；棚间有朝向与密封性差异
- **真闭环**：云端 `FAN_ON` 会把通风真拉到 1.0，温度曲线当场拐头；手动保持 10 分钟后交还自动
- **告警可达**：参数按既有三条告警规则反推标定，能自然穿过阈值并自行恢复
- **故障有生命周期**：卡值/漂移/尖峰/遮挡/结露/丢帧/离线各自带着持续时长，不是每帧掷骰子
- **可复现**：每设备独立随机种子，同一命令两次运行逐字节一致

`--dry-run` 的实测对照（24 仿真小时，默认 summer 场景）：

| 指标 | v1（独立随机数） | v2（物理仿真） |
|---|---|---|
| 相邻两拍温差 P50 | 4.72℃ | **0.080℃** |
| 温度 lag-1 自相关 | 0.0002 | **0.9995** |
| 温度-湿度相关系数 | 0.0002 | **−0.9956** |
| 同一大棚 12 台同刻极差 | 15.4℃ | **0.69~0.77℃** |
| 0–6 点光照 P50 | 49,815 lux | **≈110 lux** |
| `soilMoisture<25` 告警 | 0 / 43200（规则永不触发） | **4.46%，去抖后 10 次/天** |
| `temperature>35` 告警 | 0 / 43200 | **5.60%，去抖后 18 次/天** |

常用参数：

| 参数 | 作用 |
|---|---|
| `--scenario summer\|mild\|winter` | 气象预设。mild 下高温告警为 0，winter 下靠加热维持，可用来对照 |
| `--zones N` | 大棚数量（设备均分），默认 4 |
| `--sim-speed 60` | 60 倍时间加速，24 分钟看完一天（精度略降，演示用） |
| `--init-soil 0.23` | 初始土壤含水率，用来演示「低土壤告警 → 自动灌溉恢复」 |
| `--extra-metrics` | 额外上报 `par/soilTemp/leafTemp/vpd/dewPoint`；默认关，保持既有点数口径不变 |
| `--base-temp` `--temp-amplitude` | 后端界面下发，现在映射到**室外气温**而非传感器输出 |

注意 `--fault-rate` 的语义变了：v1 是「高温故障概率」，v2 是**故障强度倍率基准**（0.04 = 正常，0 = 不注入故障，0.2 ≈ 5 倍故障率）。后端默认传 0.04，因此默认行为不变。

**3.1 模拟器自身的回归**

```bash
# 闭环/仲裁/同棚相关性/故障生命周期/可复现/时间连续性，10 项断言
python simulator/verify_behaviors.py

# 极简 MQTT broker，验证 CONNECT→SUBSCRIBE→PUBLISH→下行指令回执整条链路
python simulator/mock_broker.py --port 18830 --duration 40 &
python simulator/device_sim.py --port 18830 --devices 20 --connections 20 --duration 30
```

也可以让后端拉起，注意路径是 `/simulator`，**不是** `/api/simulator`：

```bash
curl -X POST "$BASE/simulator/start" -H "Content-Type: application/json" -d '{}'
curl "$BASE/simulator/status"
curl -X POST "$BASE/simulator/stop"
```

**4. 查遥测数据**

```bash
curl "$BASE/api/telemetry/device-00001?range=-1h" -H "Authorization: Bearer $TOKEN"
```

**5. 打开大屏**

浏览器访问 `[待填：前端访问地址]`（本地开发是 `http://localhost:5173`）。
实时数据走 WebSocket `/ws/dashboard`，前端通过 `VITE_API_BASE` 指定后端地址。

**主要接口一览**（路径以代码为准）

| 接口 | 方法 | 说明 |
|---|---|---|
| `/auth/login` `/auth/register` | POST | 登录 / 注册 |
| `/api/system/health` | GET | 健康状态与降级信息 |
| `/api/telemetry/{deviceId}?range=-1h` | GET | 遥测查询，可选原始库 / 降采样库 |
| `/api/alerts` | GET/POST | 告警规则查询与保存 |
| `/api/devices` | GET | 设备与密钥管理 |
| `/api/dashboard` | GET | 大屏汇总数据 |
| `/simulator/start` `/stop` `/status` | POST/GET | 模拟器编排 |
| `/ws/dashboard` | WS | 遥测 / 告警 / 连接状态推送 |
| `/h2-console` | — | H2 控制台（仅开发） |
| `/actuator/health` `/actuator/prometheus` | GET | 指标暴露 |

---

## 五、关键配置说明

**总原则**：`application.yml` 里全部是 `${环境变量:演示默认值}`。不配任何变量也能启动；要改就复制 `.env.example` 为 `.env`，**不要直接改 yml**。演示默认值只能用于本地。

**必看的几项**

| 变量 | 默认 | 改错会怎样 |
|---|---|---|
| `SERVER_PORT` | 8080 | 和前端 `VITE_API_BASE` 对不上就连不通 |
| `DB_PATH` | `./data/agri` | 相对**启动目录**，不是项目根；换目录启动会建一个新的空库 |
| `INFLUX_URL` / `INFLUX_TOKEN` / `INFLUX_ORG` / `INFLUX_BUCKET` | 见 `.env.example` | org / bucket 与 compose 初始化值不一致时，**连得上但写不进** |
| `INFLUX_TASK_HEALTH_MS` | 300000（5 分钟） | 降采样 Task 是 InfluxDB 自己的调度对象，停摆时应用完全无感，这个探测是唯一信号 |
| `INFLUX_TASK_LAG_MS` | 600000（10 分钟） | Task 每 1 分钟跑一次，这里留了 10 倍余量；调小会误判停摆 |
| `INFLUX_RESCUE_RETENTION_DAYS` | 365 | 判停摆后临时拉长的 raw 保留期，**补算成功才调回**，防止修复期间原始数据被过期删掉 |
| `INFLUX_WRITE_MAX_BATCHES` | 8 | 单 tick 连发批数上限，**它乘 batch-size 除 flush 间隔就是吞吐天花板**（默认 20,000 点/秒）。扩容时先调这里，别急着换库 |
| `INFLUX_MEMORY_MAX_POINTS` | 300000 | 降级内存缓冲的全局点数上限（约 20MB）。**≤0 表示不限**，只在调试时这么配——否则 Influx 长时间不可用会把堆吃干 |
| `MQ_ENABLED` | false | true 时才连 RabbitMQ；`false` 走进程内总线，业务代码不用改 |
| `JWT_SECRET` | 演示串 | HS256 要求 ≥32 字节，生产必须换。生成：`openssl rand -base64 48` |
| `MQTT_SIM_USERNAME` / `MQTT_SIM_SECRET` | 见 `.env.example` | 模拟器专用服务账号，脚本和后端共用，两边不一致会 CONNACK 失败 |
| `LLM_ENABLED` / `LLM_API_KEY` | false / 空 | 任一不满足就不发任何外部请求，自动降级本地模板，大屏标注 `LOCAL` |
| `API_LEGACY_COMPAT` | true | true 时 `/api/**` 与 `/api/v1/**` 同时可用，下线旧版改 false |
| `CAPACITY_CPU_THRESHOLD` / `CAPACITY_HEAP_THRESHOLD` | 0.7 / 0.8 | 资源紧张时只给「降采样率」节流建议，**不会自动切内存存储** |

**两个 retention 要一起看**

`INFLUX_RAW_RETENTION_DAYS`（默认 30）和 `INFLUX_DOWNSAMPLE_RETENTION_DAYS`（默认 365）只在**建库时**生效，改已有 bucket 得走 update API。
调小之前先想清楚：降采样 task 停摆 + raw 到期 = 数据永久丢且**没有任何报错**。

**前端**

后端地址由 `VITE_API_BASE` 指定，默认 `http://localhost:8080`（在 `frontend/src/api.js` 里）。
`vite.config.js` 没配 proxy，所以开发时是跨域直连；compose 部署走 nginx 反代，不需要这个变量。

---

## 六、关键设计取舍

> 这一章只回答「为什么这么做」，操作步骤在上面几章。
> 每条都写清**被否决的方案和理由**——只列结论的方案清单没有信息量，被砍掉的那些才有。

### 6.1 写入用攒批，不是一条一个 HTTP

`WriteApiBlocking` 是同步阻塞的：一条遥测一次 HTTP 往返。200 设备、每 2 秒一报是 100 次/秒，
storage 链路的线程全部时间花在等网络上——**加线程没用，瓶颈在往返次数不在线程数**。

现在 `write()` 只把 `Telemetry` 丢进有界队列就返回，后台单线程每 200ms 或攒够 500 条刷一次。
**实测：100 设备 / 200ms 间隔跑一轮，9210 个点只产生 113 次写请求（平均 81 点/次，最大 136）。**

**延迟与吞吐**（`scripts/bench-write.sh`，八组；服务端用假服务注入固定处理耗时，**不是真实 InfluxDB 的实测延迟**）：

| 场景 | 逐条 | 攒批 |
|---|---|---|
| 100 点/秒 · P50 / P99 | 12~15 / 30~39 ms | 117.7 / 213~214 ms |
| 200 点/秒 · 达成率 | **55%** | 100% |
| 100 点/秒、服务端 20ms · 达成率 | **47%** | 100% |
| 饱和吞吐上限 | **109.6 点/秒** | **17,866 点/秒** |

两次跑，攒批侧几乎不动；逐条侧 P50 12.0 / 15.1 有波动，因为它全靠抢锁排队，排队长度对时序敏感。
饱和吞吐 17,866 两次完全相同——那是被常量精确决定的上限，不是测量出来的近似值。

逐条路径的上限不是「6 线程 × 单次容量」，而是 **109.6 点/秒**——因为 `writeNow()` 里
`synchronized (writeLock)` 把整段 HTTP 往返都锁住了，6 个 storage 线程实际是全局串行。
当前负载正好 100 点/秒，**余量 1.1 倍**，服务端再慢一点就顶到墙。攒批上限则是撞在
`MAX_BATCHES_PER_TICK=8`（8 批 × 500 条 ÷ 200ms = 20,000 的理论值），不是服务端的极限。

**否决的方案**：换 `WriteApi`（非阻塞、内部缓冲）——它自己也定时 flush，但缓冲无界，
且写失败后数据去向不由我们控制；而这个项目需要「写失败 → 转内存 → 恢复后补写」这条闭环
掌握在自己手里。另一个被否决的是加 storage 线程数：IO 密集时加线程只是把更多请求压到
同一个 InfluxDB 上。

### 6.2 写失败要分类，不能一律降级

上一版是 `catch (Exception e) → influxAvailable = false`，把两种完全不同的失败当成一种：

| 失败类型 | 上一版 | 现在 |
|---|---|---|
| 网络超时 / 5xx | 立即降级 | **连续 3 批**才降级，前两批数据转内存等补写 |
| HTTP 400（字段类型冲突、行协议语法错） | 立即降级，而且**永久**降级 | 丢弃该批 + 计数，**不降级** |

400 那条才是真事故：重试永远是 400，于是系统永久留在内存模式，正常数据也开始往内存堆，
最终又是 OOM。**修一个故障顺手引入另一个故障，就是这么来的。**

现在的判断依据：4xx 里除 408（超时）和 429（限流）外，重试结果都一样，只能丢。
被丢弃的点数在 `/api/system/health` 的 `influx.write.rejectedByServer`，
和「队列满丢弃」「非法字段剔除」「内存淘汰」分开计数——**四个数指向四种完全不同的排查方向**，
合成一个 `dropped` 等于没数。

### 6.3 在线集合是 ZSET，不是 SET

「谁超时了」用 SET 只能 `SMEMBERS` 拿全量再逐台 `HGET lastHeartbeat`：1000 台设备
就是每秒 1 + 1000 次 Redis 往返。本机 loopback 撑得住，真实网络 RTT 1ms 时这个扫描
根本追不上心跳——**等于用自己的往返延迟去对抗心跳间隔**。

改成 ZSET（score = 心跳毫秒时间戳）之后，判定压成一次
`ZRANGEBYSCORE agri:online 0 (now-10s)`，正常情况返回空集、零额外开销。

顺带修掉一个更危险的隐患：上一版 `Long.parseLong(hb)` 和 Redis 操作共用同一个 try，
心跳字段被写坏就抛 `NumberFormatException`，被 `catch (Exception)` 当成「Redis 挂了」——
**一台设备的脏字段把全部设备的状态切进内存影子**。

**升级注意**：旧版本这个 key 是 SET 类型，直接升级会拿到 `WRONGTYPE`。代码里特判了：
删键重建（在线名单是可再生数据，下次心跳会写回来），不降级。

### 6.4 队列有界 + 满了直接丢，不用 CallerRunsPolicy

提交方是 Netty 的 IO 线程。`CallerRunsPolicy` 会让它自己跑存储写入，
等于一个 EventLoop 上几百条连接一起卡住。**丢点只影响一条数据，阻塞影响一片连接。**

### 6.5 用进程内总线，不是上来就 RabbitMQ

`mq.enabled` 默认 false。抽 `TelemetryMessageBus` 接口是为了 clone 下来零外部依赖就能跑，
生产要真解耦时改一行配置切到 `RabbitMqTelemetryBus`。两条路都保留，但
**只有本地总线跑过端到端**，RabbitMQ 只有 `rabbitmq-mock` 内存 broker 的集成测试。

### 6.6 三条链路各自一个队列，不共用

三个消费者的丢点容忍度完全不同：storage 是尽力持久化，alert 丢一条就是漏一次告警，
push 丢一条下一条会补。共用队列时，storage 被 InfluxDB 拖慢会把 alert 和 push 一起挤掉——
用户看到的是「曲线在、告警没了、大屏卡了」，**三个症状一个根因**。而且告警延迟从 50ms
悄悄变成 500ms，根本没人发现。

### 6.7 降采样必须自己探活，因为 Task 停摆没有任何信号

Flux Task 写的是 `range(start: -task.every)`，只算最近一分钟，所以**任务停了那一段就永久
缺失，不会自己回补**。而 Task 是 InfluxDB 内部的调度对象，停摆时应用侧收不到任何通知；
30 天后 raw retention 把没被降采样的数据删掉——数据永久丢失，且没有任何报错。

所以每 5 分钟比一次两个桶的最新点时间。判停摆后先把 raw retention 从 30 天临时拉到 365 天
（**不是为了修复，是为了防止修复期间原始数据被过期删掉**），再按 1 小时分片跑 Flux 补算，
补算成功才调回配置值。**空洞能补，蒸发补不回来。**

### 6.8 手写 MQTT 变长长度字段，不用 LengthFieldBasedFrameDecoder

那个解码器能用，但 MQTT 的剩余长度是变长编码（1~4 字节，每字节 7 位有效），
配参数绕而且不容易自证。自己写反而直白：读变长长度 → 算总长 → 可读字节不够就 return
（半包留在累积缓冲，下次接着解）。

### 6.9 限流在外、幂等在内

先挡超频再判重复。反过来的话，有人恶意刷接口会先把幂等 key 写满 Redis。

### 6.10 降级必须能恢复，否则是单向门

Redis 抖一次就永久用内存影子，多实例各算各的、重启全丢。所以降级后每 30s 探一次
（指数退避到 300s），恢复时**先把内存状态回写再切回**，否则降级期间的读数在切回瞬间丢失。

Influx 同理，但多一道门槛：资源守卫说 CPU 还紧张就不恢复。因为 ping 通只说明服务活着，
不代表本机扛得住一次批量写——直接切回的结果是「切回 → 写入打满 CPU → 判失败 → 又降级」，
每 30 秒一次尖峰，比一直用内存更折腾。

### 6.11 内存降级要全局总量上限，淘汰按设备整台

原来 `memRaw` 只设了每设备上限（3000 点），总量等于「每台上限 × 设备数」。而设备数是外部输入
（设备增多、或伪造 deviceId 灌入），所以**总量其实无界**：1000 台约 200~300MB，3000 台接近 1GB。
堆配的是 `-Xmx4g`，靠堆大撑住了——那是运气不是设计，而且这个项目已经因为「无界队列」吃过一次 OOM
（线程池队列后来才改成 `ArrayBlockingQueue`），不该在同一个地方栽第二次。

所以加了 `influx.memory.max-total-points`（默认 300,000 点，约 20MB）。

**淘汰策略选「按设备整台淘汰」而不是「逐点淘汰」**：逐点淘汰会让每台设备的曲线都缺中间一段，
查询端既判断不出哪段可信、也没法画连续曲线；整台淘汰至少保证活下来的设备序列是完整的，
丢掉的设备是「整段没有」——后者对查询端是个明确状态，前者是隐藏的坏数据。

挑哪台淘汰用「最久没有新数据」而不是「点数最多」：前者通常意味着设备已经掉线或不活跃，
数据本来就快过期了；后者会优先干掉数据密度高的主力设备。

配套把 `memoryPointCount()` 从「遍历所有设备求和」改成读 O(1) 计数。顺带修掉一个真实缺陷：
补写原先是「边清容器边发」，最后不满一批的那部分要等循环结束才发，**那段一旦失败，
数据在内存里已经删掉了，补写救不回来**。现在改成用 `sent` 指针记录已确认写出的点数，
`finally` 里按 `sent` 精确回退各容器头部，失败的部分原地保留等下一轮重试。

### 6.12 攒批的吞吐天花板是个常量，所以它得能配

`MAX_BATCHES_PER_TICK` 决定单个 tick 最多连发几批，而吞吐上限就是
`maxBatchesPerTick × batchSize × 1000 / flushInterval`。默认 8、500 条、200ms 时是 20,000 点/秒，
基准实测 17,866 点/秒已经贴住它——**扩容时真正的瓶颈是这三个常量，不是 InfluxDB**。

原来它是 `static final`，想调只能改代码重编译。现在做成 `influx.write.max-batches-per-tick`，
`/api/system/health` 的 `influx.write.throughputCeilingPerSec` 会直接报出当前配置对应的天花板，
不用翻代码去算。

配成 0 或负数会被收敛到 1：直接拿它当循环上界的话，`drainAndWrite()` 一次都不会进，
攒批线程就**静默停摆**了——这种「配置错导致功能消失且没有报错」的形态最难查，所以在构造函数里挡掉。

## 七、常见问题

**1. 没装 InfluxDB / Redis，能启动吗？**
能。InfluxDB 连不上会降级到进程内内存缓冲（恢复后补写并记录空洞区间），Redis 连不上影子降级到进程内 Map。降级原因在 `/api/system/health` 里能看到。

**2. 为什么不用 RabbitMQ 也能跑？**
`mq.enabled` 默认 false，走 `LocalTelemetryBus`（进程内线程池）。抽这一层是为了 clone 下来零外部依赖，生产要真解耦时改一行配置切到 `RabbitMqTelemetryBus`。

**3. 数据库文件在哪？重启会不会丢？**
默认 `./data/agri.mv.db`（`DB_PATH` 是前缀，相对启动目录）。文件型 H2 会持久化，`ddl-auto: update` 自动建表。删掉这个文件等于清库。

**4. 改了 retention 为什么不生效？**
只在**建库时**生效。已有 bucket 要用 InfluxDB 的 bucket update API 改，或删库重建。

**5. 怎么知道降采样任务是不是停了？**
靠 `INFLUX_TASK_HEALTH_MS` 定时探活：比对原始库和降采样库各自最新点的时间差，超过 `INFLUX_TASK_LAG_MS` 判停摆。判停后先放宽 raw 保留期止血，再按小时分片补算，补算成功才复原保留期。
之所以要自己探，是因为 Task 是 InfluxDB 内部的调度对象，它停了应用侧完全收不到任何通知。

**6. 前端页面空白 / 请求全部失败？**
先看 `VITE_API_BASE` 和后端端口对不对。`vite.config.js` 没有 proxy，前端是直连后端的，跨域和后端地址错误都会表现成「请求全挂」。再看后端 `/api/system/health` 是否正常。

**7. 跑一会儿内存吃满、CPU 风扇狂转甚至过热关机？**
别用裸 `java -jar`。`scripts/` 下的启动脚本带了 `-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Xms512m -Xmx4g`，这是踩过这个坑之后加的：早期堆只有 1G 且时序缓冲无界，OOM 后 Full GC 反复触发把核心全占了。手动启动也要带这组参数。

**8. 模拟器把机器跑爆？**
默认是 200 设备 / 200 连接（稳定档）。`--connections 1000` 属于压测档，要显式加，并且关注机器温度——`scripts/run-sim.bat` 的注释里也写了这一点。

**9. 打包时提示找不到符号 / Lombok 注解不生效？**
`pom.xml` 里 Lombok 配了 `annotationProcessorPaths`，用 `mvnw` 走标准流程没问题；如果用 IDE 编译，需要开启 annotation processing。

---

## 八、已知限制

| 项 | 现状 |
|---|---|
| MQTT QoS 2 / 离线消息 / 会话持久化 | 只实现到 QoS 0/1 的报文层；QoS 2 只解析了标志位，没做 PUBREC 那套握手；遗嘱消息（Will）未做 |
| TLS | MQTT 明文 1883，没配证书 |
| 设备密钥存储 | 比较用了恒定时间实现，但库里仍是明文 |
| 单实例 | 长连接路由表在进程内存里，多节点无法跨节点下发指令 |
| 告警消除通知 | 记录能自动置 RESOLVED，但没有「告警已消除」的推送 |
| 测试覆盖率 | 未接 JaCoCo，真实覆盖率未知 |
| 前端 | 没配 Vite proxy，开发态是跨域直连 |
| 落库攒批的延迟 | 实测 P50 117.7ms / P99 213~214ms（100 点/秒、服务端单次写 8ms）。告警判定不经过 InfluxDB，所以不影响告警时效；但如果要拿 InfluxDB 当准实时数据源，这个延迟是硬伤 |
| 攒批参数没做容量推算 | `batchSize=500` / `queue=20000` / `flushInterval=200ms` 全是经验值。要算得先量真实 InfluxDB 单次写入的 P99——本机起不了实例，这一步做不了 |
| 攒批只在假服务上验证过 | 81 点/次、9210 点 113 次请求，以及延迟与吞吐的所有数字，都是在 `scripts/mock-influx-batch-test.py` 上量的。**服务端处理耗时是脚本注入的假设值，不是真实 InfluxDB 的实测延迟**，绝对数值不能外推到生产 |
| 攒批吞吐天花板靠常量决定 | 上限 = `maxBatchesPerTick × batchSize ÷ flushInterval`，实测 17,866 点/秒已贴着 20,000 的理论值。该常量现已可配，但**三个参数仍是经验值，没做容量推算** |
| 内存降级上限是估算值 | `max-total-points=300000` 约合 20MB，是按「单个 `Rec` 60~70 字节」估的，**没实测 JVM 里的真实占用**；也没在真实降级场景下跑过打满 30 万点的情况 |
| 崩溃时攒批队列会丢 | 队列只在内存里，进程被 kill 时未刷出的点直接丢失。`shutdown()` 只能保证优雅停机不丢，不是崩溃兜底 |
| Redis 在线集合的升级 | 旧版本 `agri:online` 是 SET，升级后第一次写入会报 `WRONGTYPE`，代码会删键重建。重建瞬间在线数归零，下一次心跳恢复 |

**验过的 / 没验过的**

| 项 | 结论 |
|---|---|
| MQTT 接入规模 | 本机 loopback 模拟，1000 连接 / 500 设备同时在线 —— **功能验证规模，不是性能结论** |
| WebSocket 吞吐 | 单客户端约 573 条/秒（自写 WS 客户端计数） |
| Redis 设备影子 | 本机真实 Redis 实例，读写正常 |
| 告警热更新 | 新增规则立即生效，无需重启 |
| 单元测试 | 28 个测试类 / 105 个用例，`./mvnw test` 全绿 |
| **写入路径基准** | `scripts/bench-write.sh` 八组，逐条 vs 攒批的延迟、达成率、饱和吞吐。跑的是真实 `InfluxService.write()`，两条路径由 `batchWriter` 是否为 null 自然分叉 |
| **InfluxDB 真实读写与降采样** | 全程走内存降级路径。写入、Flux Task 创建、retention、补算只在单元测试和 mock 上验证过，**Flux 脚本没在真实实例上执行过** |
| **RabbitMQ** | 只有 `rabbitmq-mock` 内存版集成测试，没连过真 broker |
| **MySQL** | 配了 profile 和驱动，没实际跑过，全程用 H2 |
| **真实硬件** | 没有。所有「设备」都是 `simulator/device_sim.py` 在 loopback 上模拟 |
| **写入攒批** | 用 `scripts/mock-influx-batch-test.py`（最小 InfluxDB v2 假服务）验证过批次合并、4xx 不降级、5xx 连续 3 批降级、恢复后补写 8200 点；**没在真实 InfluxDB 上量过吞吐** |
| **压测与长时间稳定性** | 没有压测报告，也没有 24 小时以上长跑数据 |
| **生产部署** | 没有 |

1000 连接这个数字只证明 Netty 能同时持有 1000 个 TCP 连接并正确解析报文，没在连接数上出现明显瓶颈；
它**不证明**真实网络下的接入能力 —— 全在 loopback，没有 RTT、丢包重传、NAT 超时、弱网重连。

---

## 附：待补清单

按出现顺序列出。补完后本节整段删掉，再全局搜 `待填` 应无结果：

1. 二、安装步骤 — `git clone [待填：仓库地址]`
2. 三、运行方式 — 部署后的访问地址 `[待填：线上地址]`
3. 三、运行方式 — 服务器部署目录 `[待填：部署路径]`
4. 四、使用示例 — `$BASE` 的线上取值 `[待填：线上地址]`
5. 四、使用示例 — 大屏访问地址 `[待填：前端访问地址]`

---

## 许可证

[MIT](LICENSE) © 2026 tangshi9527

可自由使用、修改、分发（含商业用途），保留版权声明即可。软件按「现状」提供，不附带任何形式的担保。
