# Kotlin 自定义协程 API 设计文档

## 1. 核心模型

公共抽象为 `CoroutineCoordinator`。它不是 `CoroutineScope`，不创建自己的 `Job`，完全依赖传入 `CoroutineScope` 的生命周期，不提供独立 `cancel`/`close`。

`CoroutineCoordinator` 实例本身定义 coordination domain。Registry、运行时状态、队列和 entry 都是 coordinator 的私有实现细节；不得使用 Job/root Job → Registry 的关系定义 domain。

`CoordinatorKey` 采用 Lock/Mutex 式对象身份，运行时状态不放在 key 中。同一个 key 在不同 coordinator 中互不影响。key 在创建时绑定策略，策略混用必须编译期禁止。

## 2. 三种协调策略

今天的设计收敛为三种本质不同的策略：

```text
Once       = 当前已有 execution 时，丢弃所有后续 execution
Queued     = 当前已有 execution 时，保留所有后续 execution
Coalesced  = 当前已有 execution 时，只保留 latest pending execution
```

`Conflate` 不再作为独立策略；它的“只有第一个 block 执行，后续调用等待当前 execution”语义并入 `Once` 的 suspend 调用形式 `once()`。

## 3. 公开 API

key 采用 sealed 子类绑定策略，参数类型即策略，策略混用是编译期错误：

```kotlin
sealed class CoordinatorKey private constructor() {
    class Once private constructor() : CoordinatorKey()
    class Queued private constructor() : CoordinatorKey()
    class Coalesced<T> private constructor() : CoordinatorKey()

    companion object {
        fun once(): Once
        fun queued(): Queued
        fun <T> coalesced(): Coalesced<T>
    }
}

class CoroutineCoordinator(private val scope: CoroutineScope)

fun CoroutineCoordinator.launchOnce(
    key: CoordinatorKey.Once,
    block: suspend CoroutineScope.() -> Unit,
): Unit

suspend fun CoroutineCoordinator.once(
    key: CoordinatorKey.Once,
    block: suspend CoroutineScope.() -> Unit,
)

fun CoroutineCoordinator.launchQueued(
    key: CoordinatorKey.Queued,
    block: suspend CoroutineScope.() -> Unit,
): Unit

suspend fun CoroutineCoordinator.queued(
    key: CoordinatorKey.Queued,
    block: suspend CoroutineScope.() -> Unit,
)

fun <T> CoroutineCoordinator.launchCoalesced(
    key: CoordinatorKey.Coalesced<T>,
    value: T,
    snapshot: (T) -> T = { it },
    block: suspend CoroutineScope.(T) -> Unit,
): Unit
```

`Coalesced` 暂不提供 suspend 版本：submission 可能被后续 submission 覆盖，调用者究竟等待哪一个 completion boundary 尚未形成自然且稳定的契约。`launchLatest` 不提供。

## 4. 策略语义

### Once

`launchOnce` 是非 suspend、返回 `Unit`；同 key 已有 active execution 时后续请求直接忽略、不等待。占用从 submission 被接受开始，直到对应 coroutine 正常完成、异常或取消。

`once` 是 suspend 版本：同 key 并发调用时只有第一个 block 真正执行，后续调用复用当前 execution 并挂起等待它完成；后续 block 不执行。两者是同一 Once 策略的不同调用方式。

### Queued

`launchQueued` 是 FIFO + 不等待；`queued` 是 FIFO + 等待自己的 execution。两者都保证同 key 下全部执行、严格 FIFO 且不重叠。

### Coalesced

`launchCoalesced` 是非 suspend、返回 `Unit`，使用 immutable snapshot。当前 execution 运行期间的新 submission 成为 latest pending，后来的覆盖前面的 pending；当前 execution 完成后执行 latest pending：

```text
A running
B pending → C pending → D pending
最终执行：A → D
```

承诺点（commitment point）：pending 被提升为 running 的瞬间即成为已承诺的 execution，即使其 worker 协程尚未开始执行，也不会再被更新的 submission 覆盖：

```text
A running；B/C/D 依次提交；A 完成后 D 提升为 running，此时 E 到达
最终执行：A → D → E（而不是 A → E）
```

只要 scope 保持 active，latest pending 必被执行。它与 `Flow.debounce` 不重复：`debounce` 基于时间窗口，Coalesced 基于 execution 是否正在运行。两者可以串联使用。

## 5. 快照、生命周期、取消与线程安全

带值提交必须在 submission 时形成 immutable snapshot（每次调用都执行 snapshot，即使该提交随后被覆盖）。所有 coroutine 运行在传入 scope 上；coordinator 不创建独立 Job，也没有独立生命周期。

scope 取消时所有 running/queued/pending 全部取消、不再启动，挂起的 `once`/`queued` 调用者收到 `CancellationException`；scope 已取消时提交，`launch*` 静默返回（不执行 snapshot 函数），`once`/`queued` 抛 `CancellationException`。block 的非取消异常按 structured concurrency 传播到传入 scope（普通 `Job` 级联取消，`SupervisorJob` 边界隔离），key 状态无论成败都清理并允许重新提交；block 抛出的 `CancellationException` 是取消而不是失败，不触发 scope 异常处理器。

当前不保证多个线程并发调用同一个 coordinator 的线程安全。调用方若跨线程并发提交，应自行同步。本文不规定 CAS、`ConcurrentHashMap` 等具体实现方案。

## 6. 验收重点

- key 工厂只有 `once()`、`queued()`、`coalesced<T>()`，策略混用在编译期被拒绝。
- 同一 key 在不同 coordinator 中独立运行。
- Once 的两个调用方式分别满足“不等待”和“等待当前 execution”。
- Queued 全部执行、严格 FIFO、不重叠。
- Coalesced 只保留 latest pending，且不取消当前 execution；承诺点：已提升为 running 的 submission 不可再被覆盖（A → D → E，而不是 A → E）。
- `Coalesced` suspend completion boundary 不作为当前 API 契约。
