# WAL 恢复沙盒（WAL Recovery Sandbox）

一个零外部存储依赖的本地教学沙盒：手工创建键值事务，观察 `BEGIN / PUT / DELETE /
COMMIT / CHECKPOINT` 帧如何追加到 write-ahead log，在指定记录或指定字节之后模拟掉电，
重启时只扫描真实落盘前缀，并逐步展示哪些事务重做、哪些丢弃、依据是什么。

不使用任何现成数据库充当 WAL；WAL、快照、恢复器全部为本仓库实现。

## 构建与运行

```bash
./gradlew classes
./gradlew test
./gradlew run --args='--host 127.0.0.1 --port 5226'
```

打开 <http://127.0.0.1:5226>，页面标题为“WAL 恢复沙盒”。
可选参数：`--data <目录>` 指定实验持久化目录（默认 `sandbox-data/`）。

## 帧格式

大端字节，固定 25 字节头 + 载荷，CRC32 覆盖除自身 4 字节外的整帧：

| 偏移 | 长度 | 字段 |
| --- | --- | --- |
| 0 | 4 | payload length |
| 4 | 1 | type（1 BEGIN, 2 PUT, 3 DELETE, 4 COMMIT, 5 CHECKPOINT, 6 SEGHDR） |
| 5 | 8 | txnId |
| 13 | 8 | seq（同事务从 1 开始连续编号） |
| 21 | 4 | CRC32 |
| 25 | n | payload |

- `PUT`：`u32 keyLen, key, u32 valLen, value`
- `DELETE`：`u32 keyLen, key`
- `CHECKPOINT`：`u64 generation, u64 startSegmentId`
- `SEGHDR`（每段第一帧）：`u64 segmentId, u64 prevSegmentId`

## 崩溃语义

- 扫描逐帧进行，终止原因严格区分：干净 EOF、截断头、截断载荷、坏校验、非法长度、
  未知类型。任一异常帧之后的字节一律不再解释。
- 只有完整且 CRC 通过的 `COMMIT` 才让事务生效；同一事务记录必须从 `seq=1` 起连续，
  缺号/重号/提交后继续写都会让整笔事务丢弃；扫描结束仍未提交的事务同样丢弃。
- 恢复是只读过程，重复运行结果完全一致，不会重复施加任何操作。

## Checkpoint 与段链

- 快照严格按“写临时文件 → `force` 同步 → 原子替换 → 目录 fsync”分阶段；恢复只认
  `snapshot.dat`，任何阶段掉电都只可能得到完整旧快照或完整新快照，`*.tmp` 永不采用。
- 快照内嵌 `generation` 与 `startSegmentId`：恢复先加载快照代次，再沿段头中的
  `(segmentId, prevSegmentId)` 逐段验证连续链，缺段或前驱不符立即停止。文件名仅用于
  可读性，恢复从不按文件名字符串猜测。

## 故障开关与持久化

- 短写、尾部垃圾、位翻转、checkpoint 中断四类故障挂在**选定的一次写入**上；故障只在
  “模拟掉电”重建时生效，点“恢复供电”即清空，不污染后续实验。
- 所有实验、事件与 `data/` 下原始字节持久化；导出 ZIP 后在空实例导入并恢复，会得到
  逐字相同的恢复轨迹（测试 `exportImportReplaysIdenticalTrace` 断言每一步标题/依据/
  K/V 快照一致）。

## 测试覆盖

`./gradlew test` 共 17 个用例，覆盖：空日志、截断头、截断载荷、坏 CRC（位翻转）、
未知类型、未提交事务、序号缺号、重复恢复幂等、checkpoint 三个中断阶段、孤立临时快照、
日志段缺口、尾部垃圾、字节级中断、故障隔离与导出/导入轨迹一致。

## 代码布局

- `walsandbox.frame`：帧、CRC、扫描器与终止分类
- `walsandbox.log`：段管理、快照三阶段协议、目录 fsync
- `walsandbox.recover`：快照代次 + 连续段链 + 事务恢复推理
- `walsandbox.sandbox`：实验模型、确定性落盘前缀重建（故障/崩溃注入）、仓库持久化
- `walsandbox.web`：JDK 内置 HttpServer、REST API 与单页界面
