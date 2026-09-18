# CoroutineFabric

基于 [Kotlin-Coroutine-API-Requirements-v1.md](./docs/Kotlin-Coroutine-API-Requirements-v1.md) 与 [Kotlin-Coroutine-API-Design-v1.md](./docs/Kotlin-Coroutine-API-Design-v1.md) 实现的 Android Library：
以 `CoroutineCoordinator` 为协调域、`CoordinatorKey` 为 Lock/Mutex 式身份对象，为同一个 key 提供三种明确的协调策略——Once、Queued、Coalesced。

- `:library`（`com.coroutinefabric`）：API 实现模块（minSdk 21，compileSdk 34）
- `:app`（`com.coroutinefabric.demo`）：可安装的 Demo，直观演示各 API 的行为
- 依赖：`kotlinx-coroutines-core` 1.8.1（`api` 暴露）
- 测试：JUnit 4 + `kotlinx-coroutines-test`（`runTest` 虚拟时间 + 真实线程高竞争）；
  测试范围与验收条件见 [CoroutineCoordinator-Test-Cases-v1.md](./docs/CoroutineCoordinator-Test-Cases-v1.md)

## 运行 Demo

```bash
./gradlew :app:installDebug   # 或 :app:assembleDebug 后手动安装 app/build/outputs/apk/debug/app-debug.apk
```

Demo 页面分五块，均带时间戳日志：

- **launchOnce**：点击运行 2s 初始化任务；运行中再点被静默忽略（不等待、无句柄）。
- **once（suspend）**：运行一次刷新（1.5s）并挂起等待其完成；“连续调用 4 次”可观察只有首个 block 真正执行，其余调用挂起等待当前 execution 完成后返回。
- **launchQueued / queued（suspend）**：一次提交 A/B/C，严格 FIFO 串行（各 0.8s）；“等待方式”下每个 `queued()` 在自己的执行完成后返回（而不是等整队排空）。
- **launchCoalesced — 温度控制**：用「-1℃ / +1℃」调整目标温度（设备应用一个设定值需 1.5s）；设备应用期间快速改设定值会合并，最终只有首个与最新设定值被应用；「快速 +5℃」一键连发 5 个设定值。
- **launchCoalesced — 承诺点**：“运行承诺点演示”自动提交 20..24——20℃（2s）应用期间 21/22/23 合并为 23；20 完成后 23 被提升为 running（承诺点）；24 在 23 应用期间到达。最终只应用 20 → 23 → 24：21/22 永远不应用，且已承诺的 23 不会被 24 替换。

## 快速开始

```kotlin
// coordinator：协调域（绑定一个 CoroutineScope；不是 scope、不持有 Job、无 cancel/close）
val coordinator = CoroutineCoordinator(lifecycleScope)

// key：Lock/Mutex 式对象身份；创建时绑定策略，策略混用编译期禁止
val initKey   = CoordinatorKey.once()          // Once
val uploadKey = CoordinatorKey.queued()        // Queued
val searchKey = CoordinatorKey.coalesced<SearchSnapshot>()  // Coalesced<T>

// 1) launchOnce：重复点击保护、一次性初始化（非 suspend，Unit）
coordinator.launchOnce(initKey) {
    repository.refresh()
}

// 2) once：suspend 版本——首个调用执行 block，并发调用复用当前 execution 并等待其完成
lifecycleScope.launch {
    coordinator.once(refreshKey) { repository.refresh() }  // 返回时 execution 已完成
}

// 3) launchQueued：上传、写入等不可丢失操作，FIFO 串行（非 suspend，Unit）
coordinator.launchQueued(uploadKey) { upload(batchA) }
coordinator.launchQueued(uploadKey) { upload(batchB) } // 等 A 完成后执行

//    queued：suspend 版本——等待“自己的” execution 完成后返回
lifecycleScope.launch {
    coordinator.queued(uploadKey) { upload(batchC) }
}

// 4) launchCoalesced：序列化状态更新（BLE/硬件指令、慢速持久化、远程状态同步等），
//    running 不取消、中间 pending 可丢、latest 必执行（非 suspend，Unit）
//    注意：不与 Flow.debounce 重复（debounce 基于时间，Coalesced 基于 execution 状态），
//    搜索框等原始输入流应优先使用 debounce / distinctUntilChanged 等 Flow 算子
coordinator.launchCoalesced(searchKey, SearchSnapshot(query, filters.toList())) { snapshot ->
    repository.search(snapshot)  // snapshot 是提交时冻结的不可变副本
}
```

## 公开 API

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

fun CoroutineCoordinator.launchOnce(key: CoordinatorKey.Once, block: suspend CoroutineScope.() -> Unit): Unit
suspend fun CoroutineCoordinator.once(key: CoordinatorKey.Once, block: suspend CoroutineScope.() -> Unit)
fun CoroutineCoordinator.launchQueued(key: CoordinatorKey.Queued, block: suspend CoroutineScope.() -> Unit): Unit
suspend fun CoroutineCoordinator.queued(key: CoordinatorKey.Queued, block: suspend CoroutineScope.() -> Unit)
fun <T> CoroutineCoordinator.launchCoalesced(
    key: CoordinatorKey.Coalesced<T>,
    value: T,
    snapshot: (T) -> T = { it },
    block: suspend CoroutineScope.(T) -> Unit,
): Unit
```

| API | 语义 | 返回值 |
|---|---|---|
| `launchOnce` | 同 key 已有 active execution 时直接忽略、不等待；占用持续到对应协程正常完成/异常/取消 | `Unit`（无句柄） |
| `once` | 同策略的 suspend 形式：首个调用执行 block；并发调用不执行自己的 block，挂起等待当前 execution 完成后返回 | 挂起直至 execution 完成（正常返回 / 重抛失败 / scope 取消时抛 `CancellationException`） |
| `launchQueued` | 只要 owning scope 保持 active：全部执行、严格 FIFO、不重叠；scope 取消后不保证剩余队列继续执行；不等待 | `Unit` |
| `queued` | 同策略的 suspend 形式：等待**自己的** execution 完成（不等整队排空） | 挂起直至自己的 execution 完成（结果语义同上） |
| `launchCoalesced` | running 不取消；pending 只保留 latest，后来的覆盖前面的（被覆盖者立即 terminalize）；只要 owning scope 保持 active，latest pending 必在 running 结束后执行 | `Unit` |

不提供 `launchLatest`；`Coalesced` 暂不提供 suspend 版本（被覆盖 submission 的完成边界无稳定契约）。
`Conflate` 不再是独立策略：原“触发合并”语义由 `once` 的 suspend 形式覆盖。

## 关键语义约定（实现已固定，均有测试覆盖）

1. **coordinator 实例即协调域**：registry、运行时状态、队列、pending 全部是 coordinator 的私有实现细节；
   不以任何 Job/root Job 定义域。coordinator 不是 `CoroutineScope`：不创建自己的 Job、无独立生命周期、
   不提供 `cancel`/`close`。
2. **key 是 Lock/Mutex 式对象身份**：key 不携带运行时状态；同一个 key 实例在不同 coordinator 中互不影响；
   一个 key 实例可作为长生命周期 `val` 安全共享。key 在创建时由工厂绑定策略，
   `launchOnce`/`once` 只收 `CoordinatorKey.Once`、`launchQueued`/`queued` 只收 `Queued`、
   `launchCoalesced` 只收 `Coalesced<T>`——策略混用是编译错误。
3. **所有协程运行在传入的 scope 上**：继承该 scope 的 dispatcher / context 元素 / 异常处理。
4. **scope 取消**：所有 running/queued/pending 全部取消、不再启动，key 状态清理干净，不留下脱离 scope 的工作；
   挂起在 `once`/`queued` 上的调用者收到 `CancellationException`。scope 已取消时：
   `launch*` 静默返回（不登记任何工作，带值 API 也不执行 snapshot 函数）；`once`/`queued` 抛 `CancellationException`。
5. **失败传播**：block 的非取消异常沿 structured concurrency 传播到传入 scope（普通 scope 会级联取消；
   `SupervisorJob` 边界内只影响该 execution）。无论成功还是失败，key 状态都会清理并允许后续提交；
   supervisor 边界下已入队的后续任务继续执行。
6. **suspend 调用者观察 execution 结果**：正常完成 → 返回；execution 失败 → 重抛该失败
   （类型与消息一致；kotlinx.coroutines 1.8.x 的 `await()` 会做栈迹恢复，抛出的实例不保证与被抛异常同一对象）；
   execution 被取消 → 抛 `CancellationException`。
7. **`CancellationException` 保持取消语义**：block 抛 `CancellationException` 时表现为取消，不触发 scope 的异常处理器。
8. **跨线程线程安全不属于公共契约**：内部 `synchronized` 只是实现细节，调用方不得依赖
   （设计文档不规定 CAS/`ConcurrentHashMap` 等具体方案，也不承诺调用方可任意跨线程并发提交）。
9. **输入快照**：`launchCoalesced` 在**提交点**同步执行 `snapshot(value)` 冻结输入，每次调用都执行
   （即使该提交随后被更新的提交覆盖）；执行体只接收快照，读不到提交后的外部变化。
   不可变类型用默认 `{ it }` 即可；可变对象必须在 `snapshot` 中复制为不可变形态。
10. **Coalesced 承诺点（commitment point）**：pending submission 被提升为 running 的瞬间即成为
    已承诺的 execution——即使其 worker 协程尚未开始执行，也不会再被更新的 submission 覆盖
    （A running、latest=D，A 完成后 D 提升为 running，此时 E 到达 → 最终执行 A → D → E）。
11. **terminal state invariant**：每个被创建的 submission 最终都进入明确的 terminal state
    （完成门必被 complete），不留下“已不可访问却永远 incomplete”的 submission：
    Coalesced 中被覆盖的 pending 在覆盖发生时立即以取消语义 terminalize（不执行、无等待者可观察）；
    scope 死亡时全部剩余 pending 以 `CancellationException` 唤醒。

## 内部设计

- `CoroutineCoordinator` 持有一把锁 + `HashMap<CoordinatorKey, KeyState>`；`KeyState` 按策略分形
  （Once / Queued{queue: ArrayDeque} / Coalesced{latest}），key 空闲即从 map 移除。
- 每个 submission 是一个内部 `Submission`（block + `CompletableDeferred` 完成门）。只有成为 running 的
  submission 才 `scope.launch` 一个 worker 协程执行；pending submission 不占协程，由前一个 execution
  完成时提升（promote）。
- **锁边界**：`synchronized` 临界区只做内部状态转换（登记 / 入队 / 覆盖 latest / 提升为 running）；
  所有可能产生外部可观察行为的操作——`scope.launch`、`complete`/`completeExceptionally`、用户 block——
  一律在锁外执行。提升（pending → running）在锁内原子完成，是 Coalesced 的承诺点：
  一旦提升，锁外新到达的 submission 只会成为新的 latest，不会覆盖已承诺者。
- **单一 completion 路径**：worker 体只执行 block；`job.invokeOnCompletion` 回调是唯一的完成路径——
  恰好触发一次，携带 execution 结果（正常为 null / 失败为原异常 / 取消为 `CancellationException`，
  包括“body 从未开始执行就被取消”的情形），据此 complete 完成门并驱动 `finish`。
  block 的异常仍按 structured concurrency 原样传播到 owning scope 的异常处理器。
- Coalesced 覆盖发生时：锁内摘出被覆盖的 pending，锁外以取消语义 terminalize 其完成门
  （terminal state invariant，见语义约定 11）。
- `finish` 是**单一幂等的清理/转移路径**：锁内先 `claim`（只有真正占用 running 槽的 submission 才放行，
  重复调用 no-op），再按策略取下一个（FIFO 队头 / latest）并提升为 running；若 scope 已死则锁内摘出全部
  剩余 submission、锁外以 `CancellationException` 唤醒其挂起等待者（不启动注定 born-dead 的 worker）。
  提升出的 submission 在锁外启动。
- suspend 形式的实现要点：`once` 的后续调用直接 `await` 当前 running submission 的完成门（“join 当前 execution”）；
  `queued` 的调用者 `await` 自己 submission 的完成门。

### 与设计文档的对应关系

- key 参数类型为策略绑定的 sealed 子类型（`Once`/`Queued`/`Coalesced<T>`），策略混用是编译期错误；
  工厂形态 `CoordinatorKey.once()/queued()/coalesced<T>()`。
- `once`/`queued` 的 suspend 契约落实为“挂起直至所参与的 execution 完成，并观察其结果”
  （首个调用者等待自己的 block 完成；后续调用者等待当前 execution 完成）。
- 文档不保证线程安全、不规定并发实现；实现内部仍使用 `synchronized` 短临界区（临界区内只做纯内存
  状态转换，`scope.launch`、完成门 complete、用户 block 一律在锁外执行），真实线程压力测试作为实现的
  回归保护保留，但不构成公开契约。

## 运行测试

```bash
./gradlew :library:testDebugUnitTest
```

测试范围、方法与验收条件以 `docs/CoroutineCoordinator-Test-Cases-v1.md` 为契约
（UT-O01~O11 / UT-Q01~Q11 / UT-C01~C07 / UT-D01~D03 / UT-L01~L02），每个用例带 `// UT-xxx` 追溯注释。
全部使用虚拟时间（`CompletableDeferred` 门 + `runCurrent`/`advanceUntilIdle`，不依赖真实时间），
只断言公开可观察行为（submitted vs executed），不依赖内部状态。

测试覆盖（66 个用例，按策略分文件）：

- `CoroutineCoordinatorOnceTest`（14）：UT-O01~O11——首次执行、重复提交丢弃（含多并发重复）、
  完成后重新提交（at most one **active** execution）、`launchOnce` 不等待、`once` 首个调用者执行并等待、
  重复调用者 join 当前 execution（自己的 block 不执行）、多 joiner、**取消一个 joiner 不取消共享
  execution（UT-O09，P0）且不影响其他 joiner（UT-O10）**、execution 异常传播给所有 joiner 与 scope handler；
  另含已取消 scope 下 `once` 抛 CE / `launchOnce` 不登记、普通 Job scope 下失败级联取消
- `CoroutineCoordinatorQueuedTest`（19）：UT-Q01~Q11——单/双/多提交严格 FIFO（乱序即失败）、
  永不重叠（`activeExecutions` 计数器断言 ≤1）、running 期间持续接受提交、`launchQueued` 不等待、
  `queued` 只等自己的 execution（不等整队）、**多个 `queued` 调用者各自随自己的 execution 独立恢复
  （UT-Q08）**、scope 取消中止剩余队列并 CE 唤醒等待者、**Job vs SupervisorJob 失败矩阵
  （UT-Q10/Q11：普通 Job 级联取消中止剩余队列 / supervisor 不引入额外取消且队列续跑）**；
  另含取消非失败（队列续跑、handler 不触发，Job/Supervisor 两例）、等待者观察自己 execution 的失败
  且后续继续、失败保留原始类型/消息且 key 可重新提交、已取消 scope 行为、排空后重启
- `CoroutineCoordinatorCoalescedTest`（15）：UT-C01~C07——单提交立即执行、pending 在 running 后执行、
  只执行 latest（B/C 从未执行）且 **running 不被新提交取消**、永不重叠、**提交时不可变 snapshot**
  （可变输入提交后修改不可见、被覆盖时快照仍按提交点冻结、默认透传、每次提交即时执行 snapshot）、
  **commitment point：提升后的 pending 不可再被覆盖（UT-C06，A → D → E 而非 A → E）**、
  scope 取消丢弃 latest pending（UT-C07）；另含失败/取消下 pending 接力（supervisor）、
  普通 Job scope 下失败中止 latest pending、已取消 scope 不登记且不执行 snapshot
- `CoroutineCoordinatorDomainTest`（11）：UT-D01~D03——同 coordinator 同 key 协调、
  同 coordinator 异 key 独立（key 是对象身份：两个 `once()` 实例互不影响；once/queued 两例）、
  **异 coordinator 同 key 独立**（同 scope 两例 + 异 scope 一例：coordinator 实例身份 = 协调域）；
  UT-L01/L02——scope 取消后所有 key 状态清零、coordinator 无自有 Job（scope 是唯一生命周期杠杆）、
  worker 运行在 coordinator scope 的 context 上；另含策略混用编译期拒绝（子类型不兼容，第一阶段依赖
  Kotlin 编译器验证）
- `CoroutineCoordinatorRaceConditionTest`（7）：文档用例目录之外的额外回归——虚拟时间确定性竞态
  （完成边界提交不丢失、32 并发提交不丢失不重复不重叠）+ 真实线程高竞争
  （`launchOnce` 风暴恰好一个、FIFO 无丢失无并行、完成/提交竞态无丢失、`once` 风暴永不重叠），
  每个压力用例重复 10–20 轮。真实线程压力测试定位为实现健壮性回归保护：跨线程提交安全不属于
  公开 API 契约（严格 FIFO 与 latest-wins 由确定性契约测试验证）
