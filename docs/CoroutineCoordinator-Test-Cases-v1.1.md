# CoroutineCoordinator 测试用例

> CoroutineFabric Coordination v1.1

测试只验证公开可观察行为，不读取 `running`、`pending`、`Submission` 等内部状态，也不把具体同步实现当作契约。

## 1. Contract Tests

### Once

- `UT-O01` idle 时 `launchOnce` 执行一次。
- `UT-O02` active 时 duplicate `launchOnce` 被 drop。
- `UT-O03` execution 完成后同 key 可再次执行。
- `UT-O04` idle 时 `joinOnce` 执行 block 并等待完成。
- `UT-O05` duplicate `joinOnce` 不执行自己的 block，只 join active execution。
- `UT-O06` 多个 joiner join 同一 execution。
- `UT-O07` 取消一个 joiner 不取消 shared execution，其他 joiner 正常完成。
- `UT-O08` active execution exception 传播到 joiner，并清理 key state。

### Queued

- `UT-Q01` 单个 submission 执行。
- `UT-Q02` 多个 submission 严格 FIFO。
- `UT-Q03` 同 key execution never overlap，`maxActive == 1`。
- `UT-Q04` `launchQueued` 不等待。
- `UT-Q05` `joinQueued` 等待自己的 execution，不等待后续 execution。
- `UT-Q06` scope cancellation 不启动 pending queue。

### Coalesced

- `UT-C01` 单个 submission 执行。
- `UT-C02` A running 后 B/C/D 依次提交，结果为 `A → D`，B/C 不执行。
- `UT-C03` 新 submission 不取消 running A。
- `UT-C04` snapshot 在 submission 时冻结，原值之后修改不影响 execution。
- `UT-C05` commitment point：D 被提升为 running 后 E 到达，结果必须为 `A → D → E`。
- `UT-C06` scope cancellation 丢弃 latest pending。

### Domain / Lifecycle / Type

- `UT-D01` 同 Coordinator + 不同 key 独立。
- `UT-D02` 不同 Coordinator + 同 key 独立。
- `UT-L01` Coordinator 不产生 detached/orphan coroutine。
- `UT-T01` Once key、Queued key、`Coalesced<Int>` 的错误 API 组合编译失败。

## 2. Transition Tests

使用 deterministic scheduler / gate，精确覆盖：

- finish ↔ submit；
- cancel ↔ finish；
- pending → committed；
- `joinOnce` joiner cancellation 与 shared completion 的交错；
- scope cancellation 位于 commitment point 前后。

所有断言仍通过最终执行顺序、完成信号、取消结果等公开行为完成，不读取内部状态。

## 3. Concurrency Robustness Tests

使用真实线程或 `Dispatchers.Default` 验证实现状态一致性，不扩展 public contract：

- Once：并发提交不产生 overlap、crash 或 deadlock。
- Queued：每个 accepted submission exactly once、无 duplicate、无 overlap、无 lost state。
- Coalesced：running 不被并发 submission 取消；pending winner 至多一个；无 overlap、crash 或 deadlock。

并发 Queued 测试不得把 submitter 创建顺序当作 accepted 顺序；严格 FIFO 由 deterministic contract test 负责。并发 Coalesced 测试不得断言数值最大或创建最晚者必胜，只验证 winner 属于已接受 submissions 且恰有一个 winner execution。

## 4. 三层职责与通过标准

```text
Layer 1 — Contract Tests
冻结公开语义：Once / Queued / Coalesced / cancellation / lifecycle / domain

Layer 2 — Transition Tests
保护状态转换边界：finish-submit / cancel-finish / pending-committed

Layer 3 — Concurrency Robustness Tests
保护实现健壮性：no lost state / duplicate / overlap / deadlock / crash
```

Layer 3 通过不等于对外承诺任意跨线程并发提交顺序；内部同步机制仍是实现细节。

核心 invariant：同 key active execution ≤ 1；Once duplicate launch drop、duplicate join join active；Queued accepted submissions FIFO 且每个执行一次；Coalesced pending latest wins、committed execution 不可替换；Coordinator lifecycle 等于 owning scope lifecycle。
