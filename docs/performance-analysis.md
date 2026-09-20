# OpenSPG 性能分析报告

> 生成日期：2026-09-20
> 范围：OpenSPG 代码、依赖的基础组件性能分析及改进建议
> 方法：对顶层 pom、reasoner / server / cloudext / builder 各模块进行了代码级核查，仅收录可验证的热点。

---

## 一、依赖与基础组件层问题

### D1. Java 8 + 老版本组件拖慢整体基数 🔴

顶层 `pom.xml` 锁定 `java.version=1.8`，连带不少核心组件停靠在老版本：

| 组件 | 版本 | 问题 |
|------|------|------|
| Java | 1.8 | 无 G1 以外现代 GC 特性的更好调优、无 `records`/switch 模式 |
| fastjson | 1.2.69 | 老版、非 fastjson2，官方已停止维护（有反序列化安全风险） |
| Scala | 2.11.12 | 极老，reasoner `lube-*` 依赖 |
| ANTLR | 4.8 | KGDSL 解析基础，新版有解析性能改进 |
| Dozer | 5.4.0 | 反射拷贝，官方已 EOL |
| Logback | 1.2.11 | 存在已知 CVE，建议升级 1.2.13+ |
| Neo4j 驱动 | 4.4.7 / TuGraph 1.4.1 | 版本较旧 |

**建议**：至少把 `fastjson → fastjson2`（或统一到 Jackson，本项目 Jackson 2.13.4 也可升级）、`Dozer → MapStruct`、升级 Logback 与 Java 到 11/17。这些都无需改动业务逻辑，是低风险高收益项。

### D2. Redis 使用 Java 原生对象序列化 🔴

`cloudext/impl/cache/redis` 下 `ObjectRedisCodec` 用 `ObjectOutputStream/ObjectInputStream`：

- 体积膨胀约 2~10 倍（含类描述/继承结构头），网络与内存开销大；
- 序列化慢（反射遍历对象图）；
- 反序列化不安全；
- 无法跨语言/版本互通。

**建议**：缓存值改走二进制紧凑序列化（Kryo/FST）或 JSON（Jackson），预计可显著降低缓存响应延迟与带宽。纯性能收益在缓存访问频繁的场景非常可观。

### D3. Elasticsearch 写入/删除是 N+1 单文档 HTTP 调用 🔴

客户端 `ElasticSearchRecordClient` 只暴露单文档 `_doc/{docId}` upsert/delete，**没有 `_bulk` 批量写接口**；而写路径 `ElasticSearchRecordUtils` 在 `for` 循环里对每条 `IdxRecord` 单独发一次 HTTP 请求。读侧已用了 `_mget`（批量），写侧却是逐条往返。

在 builder 写子图的高吞吐场景下，这是明确的吞吐短板。

**建议**：新增 `_bulk` 批量 upsert/delete 接口，按批（如 1k 条）聚合一次发送。

### D4. 本地计算引擎用子进程运行 🔴

`LocalComputingEngineClient` 通过 `ProcessBuilder` 每次启动独立进程注入环境并跑任务。进程启动/退出开销大，扩展性差。它已做了"复用未完成任务"的规避。

**建议**：改为进程池/常驻 worker，或复用 server 侧线程池执行，避免重复 fork。

---

## 二、reasoner 执行链路

### R1. `LocalRDG.patternScan` 每个 kgGraph 一个 Future+匿名 Callable 🔴

`runner/local-runner/.../rdg/LocalRDG.java`：

- 对 `kgGraphList` 每个元素 new 一个匿名 `Callable` 并 `threadPoolExecutor.submit`，随后逐个 `future.get()`。若 kgGraph 数量巨大，会一次性创建海量 Future 对象并全部排队，任务切分粒度太细。
- 每个 callable 还 `new HashMap<>()` 传参。
- 每次 patternScan 无条件 `JSON.toJSONString` 两个规则集打 INFO 日志（一次/批，尚可，但若字段大亦有开销）。

**建议**：按并行度做分区/分批提交（而非逐元素），限制并发 pending future 数；热路径避免逐元素匿名对象分配；规则集 JSON 仅 debug 时打印。

### R2. `PatternMatcher` 边匹配按 (edgeType, direction) 逐批查图 🔴

`runner/runner-common/.../pattern/PatternMatcher.java` 用 `TreeMap<edgeType, TreeMap<direction, ArrayList>>` 维护，逐个 edgeType 调 `graphState.getEdges(...)`，再做过滤（`removeIf` + predicate）。

好消息：JSON 序列化都在 `debugEnable` 开关内，**生产默认关闭，不是问题**。

**关注点**：若一个匹配点涉及多 edgeType，会变成多次图查询。建议做子图级批量拉取一次命中多 edge/type，减少图存储往返（尤其在 RocksDB graph state 场景下，逐 type 查询会多次遍历邻接表）。

---

## 三、server / cloudext / builder

### S1. 服务端 schema 无本地缓存 🔴

`server/core/schema/service/.../SPGTypeServiceImpl.queryProjectSchema` 每次调用都重查 `queryAllBasicType() + queryCustomizedType()`（后者再查 `queryAllStandardType() + queryByProject`），**无任何服务端缓存**。schema 是重读远多于写改的元数据，却被高频（每条查询/构建/编目请求）重复全量查询 DAO。

反向对比：http-client 侧反而有 `SchemaCache`，说明作者认可缓存思路，但服务端核心层缺失。

**建议**：在 server schema service 引入带失效刷新的本地缓存（Caffeine 或版本号失效：schema 变更时失效对应 project）。这是对"编目+构建+推理"整体链路所有请求的全局性收益，优先级最高。

### S2. Dozer 反射映射在 DAO/Convertor 链路广泛使用 🟡

`common/util/.../DozerBeanMapperUtil` 是单例 mapper（这一点还好，未每次 new），但底层仍是反射逐字段拷贝。`server/infra/dao` 下 8 个 scheduler/common convertor 均依赖它。

**建议**：热路径换 MapStruct 编译期映射；Dozer 本身已 EOL。Dozer 单次拷贝速度比手写/MapStruct 慢一个量级，虽非最高危，但转换点多、改动小。

### S3. fastjson 1.2.69 全项目 309 处/100 文件 🟡

多模块大量依赖 `JSON.toJSONString/parseObject`。老版 fastjson（1.2.69 为安全修复分支）可用但非最佳。建议整体切换 fastjson2 或 Jackson（本项目已引入 Jackson），统一序列化通道，兼顾安全与性能。

### S4. builder 数据通道依赖 ES/图库写入，放大了 D3 🔴

builder 写子图经 `Neo4jSinkWriter` 与 ES 落库。结合 D3 的单文档 ES 写入 + D2 的 Redis，构建链路是 I/O 往返密集点。**图写入已具备分批（Neo4jStoreClient upsert 单条/批量分流）**，但 ES 侧缺失 `_bulk`，必须在 builder 高吞吐入口优先补齐。

---

## 优先改进清单（按影响 × 成本）

| 优先级 | 改进点 | 影响模块 | 证据 | 收益 |
|--------|--------|----------|------|------|
| 🔴 P0 | 服务端 schema 加缓存并失效 | server 全链路 | S1 | 全局请求延迟↓，QPS↑ |
| 🔴 P0 | ES 写/删改 `_bulk` 批量 | builder/cloudext | D3/S4 | 写吞吐大幅↑ |
| 🔴 P0 | Redis 换紧凑序列化 | cloudext cache | D2 | 缓存 RTT↓、带宽↓ |
| 🔴 P1 | reasoner 分批并行，勿逐元素 submit | reasoner | R1 | 大 KG 推理时长稳定 |
| 🟡 P1 | PatternMatcher 子图级批量拉边 | reasoner | R2 | 减少图往返 |
| 🟡 P2 | fastjson→fastjson2/Jackson；Dozer→MapStruct | 全局 | S3/S2 | 序列化/拷贝加速 |
| 🟡 P2 | 升级旧依赖（Logback/Java/ANTLR） | 全局 | D1 | 安全+基础性能 |
| 🟡 P2 | 计算引擎改进程池 | cloudext | D4 | 降低 fork 开销 |
```