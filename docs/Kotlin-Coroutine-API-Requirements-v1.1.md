# Kotlin 自定义协程 API 需求文档

> CoroutineFabric Coordination v1.1

## 1. 范围

本文冻结 `CoroutineCoordinator`、`CoordinatorKey` 以及 Once、Queued、Coalesced 三种 strategy 的公开行为。API 必须保留：`launchOnce`、`joinOnce`、`launchQueued`、`joinQueued`、`launchCoalesced`；不提供 `joinCoalesced`。

## 2. Contract

- Coordinator instance 定义 coordination domain；不同 Coordinator 即使使用同一 key 也互不协调。
- 同一 Coordinator 中，不同 key 独立；key 使用对象身份。
- key 类型绑定 strategy，错误 strategy 组合在编译期失败。
- Coordinator 不创建独立 Job，不拥有独立 lifecycle，不提供 `cancel()` / `close()`。
- `launchOnce` duplicate 必须 drop；active execution 完成后 key 可再次接受 submission。
- `joinOnce` duplicate 必须 join active execution；自己的 block 不执行。joiner cancellation 不得取消 shared execution 或其他 joiner。
- `launchQueued` 不等待；`joinQueued` 等待自己的 execution。所有 accepted submissions 严格 FIFO、全部执行、同 key 不重叠。
- `launchCoalesced` 在 active 期间只保留 latest pending，不取消 running execution；snapshot 在 submission 时执行。
- Coalesced commitment point 之后 execution 不可被替换；测试必须能观察 `A → D → E`，不能退化为 `A → E`。
- latest pending 的 eventual execution 以 owning scope 保持 active 为前提。
- scope 取消时 running、queued、pending 不再启动；`launch*` 静默返回且不执行 snapshot；`joinOnce` / `joinQueued` 取消结束。
- failure 遵循 Kotlin structured concurrency；成功、失败、取消后 runtime state 都必须清理。

## 3. 非目标

不定义 `joinCoalesced` 的 completion boundary；不将 `Flow.debounce` 等时间窗口语义并入 Coordinator；不承诺调用方可依赖具体内部同步实现或任意跨线程提交顺序。

## 4. 验收标准

Contract、Transition、Concurrency Robustness 三层测试全部通过；尤其必须覆盖 Once joiner cancellation isolation、Queued FIFO / no overlap、Coalesced latest 与 commitment point，以及 Coordinator domain isolation。
