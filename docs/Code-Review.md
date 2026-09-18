# CoroutineFabric Code Review

> Review Target: CoroutineCoordinator v1.1  
> Review Baseline:
>
> - `Kotlin-Coroutine-API-Design-v1.1.md`
> - `Kotlin-Coroutine-API-Requirements-v1.1.md`
> - `CoroutineCoordinator-Test-Cases-v1.1.md`

## 1. Review 结论

当前 Review 文件：

```text
CoordinatorKey.kt
CoordinatorLaunch.kt
CoroutineCoordinator.kt
```

整体结论：

```text
CoordinatorKey.kt
→ ⚠️ 核心设计正确，但存在 v1.0 KDoc 命名残留

CoordinatorLaunch.kt
→ ✅ 符合 v1.1

CoroutineCoordinator.kt
→ ✅ 核心状态机符合 v1.1
→ 🧹 建议删除不再需要的 test-only inspection API
```

当前实现不需要因为 v1.1 重新设计或重写 Coordinator 状态机。

以下核心语义已经与 v1.1 对齐：

```text
Once
├── launchOnce duplicate → drop
├── joinOnce duplicate → join active execution
└── joiner cancellation isolation

Queued
├── strict FIFO
├── no overlap
├── launchQueued → no wait
└── joinQueued → wait own execution

Coalesced
├── running execution is not replaced
├── latest pending wins
├── immutable snapshot
└── commitment point

Lifecycle
└── bound to owning CoroutineScope

Coordination Domain
└── defined by CoroutineCoordinator instance
```

---

# 2. CoordinatorKey.kt

## 状态

```text
⚠️ Minor Fix Required
```

Key 类型设计本身符合 v1.1：

```kotlin
CoordinatorKey.Once
CoordinatorKey.Queued
CoordinatorKey.Coalesced<T>
```

Strategy 在 key 创建时绑定，因此可以利用 Kotlin 类型系统阻止错误组合。

例如：

```kotlin
launchOnce(queuedKey) {
    // compile error
}

joinQueued(onceKey) {
    // compile error
}
```

符合：

```text
能通过类型系统阻止的错误
→ 不留给 runtime
```

---

## 2.1 KDoc 存在 v1.0 API 名称残留

当前文档仍然使用：

```text
launchOnce / once
launchQueued / queued
```

v1.1 已冻结为：

```text
launchOnce / joinOnce
launchQueued / joinQueued
```

因此需要修改 KDoc。

### Before

```kotlin
- [once] creates a key usable only with [launchOnce] / [once]
- [queued] creates a key usable only with [launchQueued] / [queued]
```

### After

```kotlin
- [once] creates a key usable only with [launchOnce] / [joinOnce]
- [queued] creates a key usable only with [launchQueued] / [joinQueued]
- [coalesced] creates a key usable only with [launchCoalesced]
```

### Priority

```text
P1
```

属于文档/API 一致性问题，不影响当前 runtime semantics。

---

# 3. CoordinatorLaunch.kt

## 状态

```text
✅ PASS
```

当前 public API 已经与 v1.1 对齐：

```kotlin
launchOnce(...)
joinOnce(...)

launchQueued(...)
joinQueued(...)

launchCoalesced(...)
```

符合统一 invocation model：

```text
launchXXX
→ caller does not wait

joinXXX
→ caller waits for execution
```

---

# 4. joinOnce Review

当前实现通过：

```kotlin
submitOnce(key, block).completion.await()
```

实现 suspend waiting。

当 key idle：

```text
joinOnce A
↓
A becomes running
↓
A executes
↓
caller awaits A completion
```

当已经存在 A：

```text
A running

B joinOnce
```

`submitOnce()` 返回已有 running submission。

因此：

```text
B block does not execute
B waits A
```

实际 execution：

```text
A
```

而不是：

```text
A → B
```

符合 v1.1 Once contract。

---

# 5. joinOnce Cancellation Isolation

这是 Once strategy 的核心 P0 invariant。

场景：

```text
A running

B joins A
C joins A
```

B/C 只是：

```text
await shared completion
```

取消 B：

```text
cancel B
```

只取消 B 自身的 suspension。

不会调用：

```text
A.cancel()
```

因此必须保持：

```text
B cancelled

A still running
C still waiting
```

A 完成：

```text
C resumes normally
```

当前实现符合：

```text
joiner cancellation
≠
shared execution cancellation
```

### Result

```text
✅ PASS
```

---

# 6. launchQueued Review

`launchQueued()` 使用 Queued state 提交 execution。

如果当前没有 running：

```text
submission
→ running
```

如果已有 running：

```text
submission
→ queue
```

caller 不等待 execution completion。

符合：

```text
launchQueued
=
enqueue + fire-and-forget
```

### Result

```text
✅ PASS
```

---

# 7. joinQueued Review

`joinQueued()`：

```kotlin
submitQueued(key, block).completion.await()
```

等待的是本次 submission 自己的 completion。

例如：

```text
A running

B joinQueued
C queued
```

执行过程：

```text
A
↓
B
↓
C
```

B caller：

```text
wait while A runs
↓
B starts
↓
wait B
↓
B completes
↓
B caller resumes
```

B 不需要等待 C。

这与 `joinOnce()` 的语义差异正确：

```text
joinOnce
→ active exists
→ reuse active execution
→ own block may not execute

joinQueued
→ accepted into queue
→ wait own execution
→ own block executes when its turn arrives
```

### Result

```text
✅ PASS
```

---

# 8. Queued FIFO

Queued state 使用 FIFO queue。

逻辑为：

```text
running == null
→ submission becomes running

running != null
→ addLast(submission)
```

获取下一个：

```text
removeFirst
→ running
```

因此对于确定的 accepted order：

```text
A B C D
```

execution order：

```text
A → B → C → D
```

同时只有：

```text
active execution <= 1
```

### Result

```text
✅ PASS
```

注意：

```text
FIFO
=
actual accepted submission order
```

不等于：

```text
concurrent coroutine creation order
```

并发测试不得使用 submitter 创建顺序推断 FIFO 顺序。

---

# 9. Coalesced Review

Coalesced 当前模型：

```text
running
+
latest pending
```

A running 时：

```text
B → latest

C
→ replaces B

D
→ replaces C
```

最终：

```text
A → D
```

新的 submission 不会因为 Coalesced replacement semantics 取消 A。

### Result

```text
✅ PASS
```

---

# 10. Coalesced Commitment Point

核心实现逻辑：

```kotlin
latest?.also {
    latest = null
    running = it
}
```

该 transition 在内部 synchronization boundary 中完成。

场景：

```text
A running

B submitted
C submitted
D submitted
```

A 完成：

```text
LOCK

D:
latest → running

UNLOCK
```

此时 D 已经 committed。

随后 E：

```text
running = D
latest = E
```

最终：

```text
A → D → E
```

不得：

```text
A → E
```

当前实现符合：

```text
pending
→ replaceable

running
→ committed
→ cannot be replaced
```

### Result

```text
✅ PASS
```

注意：

```text
committed
≠
block body guaranteed to start
```

owning scope 仍然可以在 commitment 后取消 execution。

---

# 11. Coalesced Snapshot

当前执行顺序：

```text
check scope active
↓
snapshot(value)
↓
submit snapshot
```

因此 execution 使用：

```text
submission-time snapshot
```

而不是 execution-time value。

这满足 immutable snapshot contract。

scope 已取消时：

```text
return
```

发生在 snapshot 前，因此 cancelled scope submission 不执行 snapshot。

### Result

```text
✅ PASS
```

`isActive()` 只属于 fast-path。

它不是 atomic acceptance guarantee：

```text
check active
↓
scope may immediately cancel
```

属于正常 coroutine lifecycle race，不违反 v1.1 contract。

---

# 12. Replaced Coalesced Submission

被新的 pending submission 替换后，旧 submission completion 会被 terminalize，而不是永久保持 incomplete。

这可以避免内部 Submission 永远处于未完成状态。

当前 public API 没有：

```kotlin
joinCoalesced(...)
```

因此调用方不会直接等待 replaced submission。

当前实现可以保留。

### Result

```text
✅ PASS
```

---

# 13. Lifecycle Review

Coordinator 没有创建：

```text
independent Job
SupervisorJob
independent CoroutineScope
```

execution 通过 owning：

```kotlin
scope.launch {
    ...
}
```

启动。

因此：

```text
Coordinator lifetime
=
owning CoroutineScope lifetime
```

Coordinator 也没有：

```text
cancel()
close()
```

符合 v1.1 lifecycle contract。

### Result

```text
✅ PASS
```

---

# 14. Scope Cancellation

owning scope cancellation 后：

```text
running
queued
pending
```

均遵循 owning scope cancellation。

remaining state 会被清理，pending waiter completion 会被终止。

completion side effect 位于 synchronization boundary 外。

正确结构：

```text
LOCK

state transition
determine next
detach aborted work

UNLOCK

complete waiters
start next if allowed
```

避免在 lock 内执行外部 coroutine completion side effects。

### Result

```text
✅ PASS
```

---

# 15. Failure Propagation

execution 直接运行于：

```kotlin
scope.launch
```

因此普通 exception 继续遵循 owning scope 的 structured concurrency semantics。

普通：

```kotlin
Job()
```

下：

```text
child failure
→ may cancel parent
→ remaining Coordinator work follows cancellation
```

`SupervisorJob()` 下：

```text
Coordinator does not introduce additional global cancellation
```

`CancellationException` 继续作为 cancellation 处理。

Coordinator 没有建立独立 exception model。

### Result

```text
✅ PASS
```

---

# 16. Lock Boundary

当前核心原则：

```text
LOCK

claim running
update queue/latest
determine next
remove idle state

UNLOCK

complete deferred
launch coroutine
```

特别是：

```text
scope.launch
```

没有放在：

```text
synchronized(lock)
```

内部。

这可以避免：

```text
launch side effect while holding coordinator lock
```

当前 lock boundary 符合 v1.1。

### Result

```text
✅ PASS
```

---

# 17. Thread-Safety Contract

当前 KDoc 表达：

```text
cross-thread concurrent submission behavior
is not part of public API contract
```

同时内部 synchronization 仍用于保证实现自身状态一致性。

这正确区分：

```text
public thread-safety contract not guaranteed

≠

implementation state correctness not required
```

调用方也不得依赖 Coordinator 当前具体使用的：

```text
synchronized
```

实现。

### Result

```text
✅ PASS
```

---

# 18. hasActiveWork()

当前生产实现存在：

```kotlin
internal fun hasActiveWork(
    key: CoordinatorKey,
): Boolean
```

其定位为：

```text
Test / inspection hook
```

这不是 runtime semantics bug。

但是 v1.1 Test Design 已经明确：

```text
test public observable behavior
do not test internal state
```

因此测试应该优先通过：

```text
executed values
CompletableDeferred gates
caller suspended/resumed
Job completion
```

验证行为。

而不是：

```text
inspect Coordinator internal state
```

### Recommendation

检查现有 tests 是否仍使用：

```kotlin
hasActiveWork()
```

如果没有使用：

```text
→ delete it
```

如果仍然使用：

```text
→ 优先重构测试
→ 使用 observable behavior
→ 再删除 inspection hook
```

### Priority

```text
P2 / Cleanup
```

不是 v1.1 release blocker。

---

# 19. Required Changes

## P1 — Fix CoordinatorKey KDoc

修改：

```text
launchOnce / once
```

为：

```text
launchOnce / joinOnce
```

修改：

```text
launchQueued / queued
```

为：

```text
launchQueued / joinQueued
```

并确保 Coalesced 文档指向：

```text
launchCoalesced
```

---

# 20. Recommended Cleanup

## P2 — Remove hasActiveWork()

如果测试不再依赖：

```kotlin
hasActiveWork()
```

建议从生产代码删除。

理由：

```text
production Coordinator
should not expose internal state
only for tests
```

测试应保护 public observable contract。

---

# 21. No State-Machine Rewrite Required

本次 v1.1 Review 没有发现需要重新设计以下核心状态机的问题：

```text
Once state machine
Queued FIFO
Coalesced latest replacement
Coalesced commitment point
scope lifecycle
completion propagation
lock boundary
```

因此：

```text
❌ 不需要重写 CoroutineCoordinator

✅ 修正文档残留

✅ 清理不必要的 test hook

✅ 继续运行 v1.1 Test Cases
```

---

# 22. Final Review Status

```text
CoroutineFabric
CoroutineCoordinator v1.1

Core API
✅ PASS

Once
✅ PASS

joinOnce
✅ PASS

Joiner Cancellation Isolation
✅ PASS

Queued
✅ PASS

joinQueued
✅ PASS

Coalesced
✅ PASS

Commitment Point
✅ PASS

Snapshot
✅ PASS

Lifecycle
✅ PASS

Failure Propagation
✅ PASS

Lock Boundary
✅ PASS

Thread-Safety Contract
✅ PASS

CoordinatorKey KDoc
⚠️ P1 Minor Fix

hasActiveWork()
🧹 P2 Cleanup Recommended
```

## Final Conclusion

当前实现已经满足 CoroutineFabric Coordination v1.1 的核心设计。

合入 v1.1 前建议完成：

```text
1. 修复 CoordinatorKey.kt 中旧 once()/queued() API KDoc
2. 检查 hasActiveWork() 是否仍被测试使用
3. 若无依赖，删除 hasActiveWork()
4. 运行完整 v1.1 Test Cases
```

完成以上项目后，可以将当前实现视为：

```text
CoroutineCoordinator v1.1
→ Ready for final test verification
```