# Kotlin 自定义协程 API 设计文档

> CoroutineFabric Coordination v1.1

## 1. 核心模型

公共抽象为 `CoroutineCoordinator`。它不是 `CoroutineScope`，不创建自己的 `Job`，完全依赖传入 scope 的生命周期，不提供独立 `cancel()` / `close()`。

Coordinator 实例定义 coordination domain；Registry、运行时状态、队列、pending、running 均为私有实现细节。`CoordinatorKey` 使用对象身份语义，同一 key 在不同 Coordinator 中互不影响；key 创建时绑定 strategy，strategy 混用必须编译期失败。

## 2. Strategy

```text
Once       = active 时后续 execution 不重要，重复提交丢弃
Queued     = 每个 accepted submission 都重要，严格 FIFO
Coalesced  = active 期间只保留 latest pending
```

`Conflate` 不作为独立 strategy。

## 3. Public API

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

suspend fun CoroutineCoordinator.joinOnce(
    key: CoordinatorKey.Once,
    block: suspend CoroutineScope.() -> Unit,
)

fun CoroutineCoordinator.launchQueued(
    key: CoordinatorKey.Queued,
    block: suspend CoroutineScope.() -> Unit,
): Unit

suspend fun CoroutineCoordinator.joinQueued(
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

## 4. Invocation semantics

`launchXXX` 提交后不等待。`joinXXX` 提交或复用 execution，并等待对应 execution 完成。

### Once

`launchOnce` 在 active 时丢弃 duplicate。`joinOnce` 在 active 时不执行自己的 block，而是 join 当前 active execution。多个 joiner 共享 execution；取消一个 joiner 只取消自身等待，不取消 shared execution，也不影响其他 joiner。

### Queued

`launchQueued` 接受 submission 后立即返回。`joinQueued` 将自己的 block 放入 FIFO，等待自己的 turn、执行并等待自身完成后返回；不等待后续 queued work。同一 key 下 execution 不重叠。

### Coalesced

`launchCoalesced` 不提供 `joinCoalesced`。active execution 不被新 submission 取消；pending 只保留 latest。每次 submission 立即形成 immutable snapshot，即使随后被覆盖也必须执行 snapshot。

```text
A running; B/C/D submitted  => A → D
```

pending 被提升为 running 的瞬间是 commitment point，之后不可替换：

```text
A running; B/C/D pending; D committed; E arrives => A → D → E
```

只要 owning scope 保持 active，latest pending eventually executes。

## 5. Lifecycle, failure, and boundary

所有 execution 运行在传入 scope。scope cancellation 遵循 structured concurrency，取消 running、queued、pending；已取消 scope 不接受新 submission，`launch*` 静默返回，`joinOnce` / `joinQueued` 以 `CancellationException` 结束。ordinary failure 按 owning scope 的 Job / SupervisorJob 规则传播，状态必须清理。

多个线程并发提交不属于当前 public thread-safety contract；实现仍必须保持内部状态一致，不规定 `synchronized`、CAS 或 `ConcurrentHashMap` 等实现方式。
