# Kotlin 自定义协程 API 需求文档

## 1. 文档目的

本文定义 `CoroutineCoordinator`、`CoordinatorKey` 和 Once、Queued、Coalesced 三种协调策略的行为契约。

## 2. API 范围

```text
CoordinatorKey.once()
CoordinatorKey.queued()
CoordinatorKey.coalesced<T>()

launchOnce(...)       // 非 suspend，Unit
once(...)             // suspend
launchQueued(...)     // 非 suspend，Unit
queued(...)           // suspend
launchCoalesced(...)  // 非 suspend，Unit
```

`Conflate` 不再是独立策略或 key 工厂；原 `conflate()` 语义并入 `once()`。`Coalesced` 暂不提供 suspend 版本，`launchLatest` 不提供。

## 3. 核心模型与边界

`CoroutineCoordinator` 实例定义 coordination domain。它不是 `CoroutineScope`，不创建自己的 Job，完全依赖传入 scope 生命周期，不提供独立 `cancel`/`close`。

Registry、运行时状态和队列只能是 coordinator 的私有实现细节。不得再以 Job/root Job → Registry 定义 domain。

`CoordinatorKey` 使用 Lock/Mutex 式对象身份，运行时状态不放在 key 中。同一个 key 在不同 coordinator 中互不影响。key 创建时绑定策略，策略混用必须编译期禁止。

## 4. 三种策略契约

### Once

`launchOnce` 非 suspend、返回 `Unit`。同 key 已有 active execution 时，后续 submission 直接忽略，不等待；占用持续到对应 coroutine 正常完成、异常或取消。

`once` 为 suspend 版本。第一个调用执行 block；并发的后续调用不执行自己的 block，而是等待当前 execution 完成后返回。

```text
A running
B/C once 调用 → 等待 A
真正执行：A
```

### Queued

`launchQueued` 不等待，`queued` 等待自己的 execution。两者都要求同 key 下所有 submission 严格 FIFO、全部执行、不重叠。

### Coalesced

`launchCoalesced` 非 suspend、返回 `Unit`。输入在 submission 时冻结为 immutable snapshot（每次调用都执行 snapshot，即使该提交随后被覆盖）。当前 execution 运行期间只保留一个 latest pending；后来的 submission 覆盖前面的 pending。

```text
A running，B/C/D 依次提交
最终执行：A → D
```

承诺点（commitment point）：pending 被提升为 running 的瞬间即成为已承诺的 execution，不可再被后续 submission 覆盖：

```text
A running，B/C/D 依次提交；A 完成后 D 提升为 running，此时 E 到达
最终执行：A → D → E（而不是 A → E）
```

当前 execution 不取消；只要 scope 保持 active，latest pending 必在 running 结束后执行。由于被覆盖 submission 的等待边界难以定义，暂不提供 `suspend coalesced(...)`。

## 5. Coalesced 与 debounce

两者不重复：`Flow.debounce` 依据时间窗口等待输入稳定；Coalesced 依据 execution 状态，在当前操作完成后执行 latest pending。需要时可以先 `debounce`，再提交给 `launchCoalesced`。

## 6. 生命周期、取消与失败

- 所有 coroutine 必须运行在传入 scope 上。
- coordinator 不创建独立 Job，不拥有独立生命周期。
- scope 取消：所有 running/queued/pending 全部取消、不再启动，不留下脱离 scope 的工作；挂起在 `once`/`queued` 上的调用者收到 `CancellationException`。
- scope 已取消时提交：`launch*` 静默返回（不登记任何工作，带值 API 也不执行 snapshot 函数）；`once`/`queued` 抛 `CancellationException`。
- 失败传播：block 的非取消异常按 structured concurrency 传播到传入 scope（普通 `Job` scope 级联取消；`SupervisorJob` 边界内只影响该 execution）。无论成功或失败，key 状态都会清理并允许后续提交。
- block 抛出的 `CancellationException` 是取消而不是失败：不触发 scope 的异常处理器。
- 当前不保证多个线程并发调用同一个 coordinator 的线程安全；调用方跨线程并发提交时应自行同步。
- 不规定 CAS、`ConcurrentHashMap` 或其他具体并发实现。

## 7. 验收标准

- 三个 key 工厂的策略绑定能够编译期阻止策略混用。
- 同 key 在不同 coordinator 中独立执行。
- Once：launch 直接忽略重复请求；suspend 调用等待当前 execution。
- Queued：所有请求按 FIFO 执行且不重叠，suspend 调用等待自己的 execution。
- Coalesced：A running 时 B/C/D 最终只执行 A/D，A 不被取消，D 使用提交时 snapshot；
  承诺点：D 提升为 running 后 E 到达 → 最终执行 A/D/E。
- scope 取消后不留下脱离 scope 的工作；挂起的 `once`/`queued` 调用者收到 `CancellationException`。
- 失败按 structured concurrency 传播，且 key 失败后可重新提交；`CancellationException` 不触发 scope 异常处理器。
- 不把未确认的 Coalesced completion boundary 写成既定设计。
