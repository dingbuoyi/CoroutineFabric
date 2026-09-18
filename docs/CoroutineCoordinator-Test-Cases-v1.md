# CoroutineCoordinator 测试用例文档

## 1. 文档目的

本文定义 `CoroutineCoordinator` 的单元测试范围、测试方法和验收条件。

测试以已经冻结的：

- `Kotlin-Coroutine-API-Design.md`
- `Kotlin-Coroutine-API-Requirements.md`

为行为契约。

测试目标不是验证 `CoroutineCoordinator` 的内部实现方式，而是验证公开可观察行为是否满足设计要求。

主要验证：

```text
Once
Queued
Coalesced
Coordination Domain
Lifecycle / Cancellation
Exception Propagation
```

---

# 2. 测试原则

## 2.1 测试公开行为，不测试内部状态

测试不应该依赖：

```text
states
running
queue
latest
Submission
synchronized
```

等内部实现。

例如 Coalesced 不应该断言：

```text
state.latest == D
```

而应该断言：

```text
submitted:
A B C D

executed:
A D
```

这样即使未来内部实现发生变化，只要公开行为不变，测试仍然有效。

---

## 2.2 不依赖真实时间

避免使用：

```kotlin
delay(100)
```

来猜测 coroutine 是否已经执行。

优先使用：

```kotlin
CompletableDeferred
runCurrent()
advanceUntilIdle()
```

精确控制执行时机。

典型 gate：

```kotlin
val started = CompletableDeferred<Unit>()
val release = CompletableDeferred<Unit>()

coordinator.launchOnce(key) {
    started.complete(Unit)
    release.await()
}
```

测试可以精确形成：

```text
A starts
↓
A suspended
↓
submit B/C/D
↓
release A
↓
observe result
```

---

## 2.3 测试工具

建议使用：

```text
kotlinx-coroutines-test
```

主要 API：

```kotlin
runTest
StandardTestDispatcher
runCurrent()
advanceUntilIdle()
```

异步控制主要使用：

```kotlin
CompletableDeferred
```

---

# 3. Once — launchOnce

## UT-O01 First submission executes

### Given

Key 当前 idle。

### When

```kotlin
launchOnce(key) {
    A
}
```

### Then

```text
executed:
A
```

A 只执行一次。

---

## UT-O02 Duplicate submission is dropped

### Given

```text
A running
```

### When

同一个 key 提交：

```text
B
```

### Then

B 不执行。

```text
submitted:
A B

executed:
A
```

---

## UT-O03 Multiple duplicate submissions are dropped

### Given

```text
A running
```

### When

连续提交：

```text
B
C
D
```

### Then

```text
executed:
A
```

B/C/D 都不能执行。

---

## UT-O04 New submission executes after previous completion

### Given

A 已经完成。

### When

再次使用相同 key 提交 B。

### Then

```text
A → B
```

验证 Once 的含义是：

```text
at most one active execution
```

而不是：

```text
one execution during entire key lifetime
```

---

## UT-O05 launchOnce does not wait for execution

### Given

A 开始后通过 gate 保持 suspended。

### When

调用：

```kotlin
launchOnce(key) {
    A
}
```

### Then

`launchOnce()` 调用立即返回。

A 此时仍然可以保持 active。

---

# 4. Once — suspend once()

## UT-O06 First caller executes block

### Given

Key idle。

### When

```kotlin
once(key) {
    A
}
```

### Then

A 执行。

调用者等待 A 完成后返回。

---

## UT-O07 Duplicate caller joins active execution

### Given

```text
A running
```

### When

B 调用：

```kotlin
once(key) {
    B
}
```

### Then

B 自己的 block 不执行。

B 等待 A。

A 完成后：

```text
B caller resumes
```

实际执行：

```text
A
```

而不是：

```text
A → B
```

---

## UT-O08 Multiple callers join same execution

### Given

```text
A running
```

### When

B/C/D 同时：

```text
once() → join A
```

### Then

B/C/D 的 block 均不执行。

```text
A running

B waiting
C waiting
D waiting
```

A 完成后：

```text
B resumes
C resumes
D resumes
```

---

# 5. Once — Cancellation Isolation

## UT-O09 Cancelling one joiner does not cancel shared execution

**优先级：P0**

### Given

```text
A running

B once() → joins A
C once() → joins A
```

### When

取消 B。

### Then

必须满足：

```text
B cancelled

A still running
C still waiting
```

随后完成 A。

必须：

```text
A completes
C resumes normally
```

核心 invariant：

```text
joiner cancellation
≠
shared execution cancellation
```

---

## UT-O10 Cancelling one joiner does not affect other joiners

### Given

```text
A running
B waiting A
C waiting A
```

### When

```text
cancel B
```

### Then

C 仍然正常等待 A。

B 的 cancellation 不得传播给 C。

---

# 6. Once — Exception

## UT-O11 Execution exception reaches joiners

### Given

```text
A running
B once() → joins A
```

### When

A：

```kotlin
throw TestException()
```

### Then

B 自己的 block 不执行。

B 的：

```kotlin
once(...)
```

以对应异常结束。

异常同时仍然遵循 owning CoroutineScope 正常的 Job exception propagation 规则。

---

# 7. Queued — FIFO

## UT-Q01 Single submission executes

```text
submitted:
A

executed:
A
```

---

## UT-Q02 Two submissions execute FIFO

```text
submitted:
A B

executed:
A → B
```

---

## UT-Q03 Multiple submissions execute strict FIFO

```text
submitted:
A B C D

executed:
A → B → C → D
```

不能出现：

```text
A → C → B → D
```

等乱序。

---

## UT-Q04 Executions never overlap

记录当前 active execution 数：

```kotlin
var activeExecutions = 0
var maxActiveExecutions = 0
```

每次 execution：

```text
activeExecutions++
record max
execute
activeExecutions--
```

最终：

```text
maxActiveExecutions == 1
```

同一个 Queued key 下任何时候最多只有一个 active execution。

---

# 8. Queued — launchQueued

## UT-Q05 launchQueued does not wait

### Given

A 开始执行并保持 suspended。

### When

```kotlin
launchQueued(key) {
    A
}
```

### Then

调用立即返回。

A 可以继续保持 active。

---

## UT-Q06 Submissions accepted while execution active

### Given

```text
A running
```

### When

提交：

```text
B
C
D
```

### Then

A 完成后：

```text
A → B → C → D
```

---

# 9. Queued — suspend queued()

## UT-Q07 queued waits for own execution

### Given

```text
A running
```

### When

B：

```kotlin
queued(key) {
    B
}
```

### Then

A 运行期间：

```text
B caller suspended
```

A 完成：

```text
B starts
```

B caller 仍然等待。

B 完成：

```text
B caller resumes
```

---

## UT-Q08 Multiple queued callers wait independently

### Given

```text
A running
```

### When

```text
B queued()
C queued()
D queued()
```

### Then

执行顺序：

```text
A → B → C → D
```

调用者恢复顺序跟随自己的 execution completion：

```text
B completes → B caller resumes
C completes → C caller resumes
D completes → D caller resumes
```

B caller 不需要等待 C/D。

---

# 10. Queued — Cancellation

## UT-Q09 Scope cancellation aborts pending queue

### Given

```text
A running

B pending
C pending
D pending
```

### When

owning CoroutineScope 被取消。

### Then

A 遵循正常 coroutine cancellation。

B/C/D 不得执行。

```text
executed:
A only
```

Coordinator 不允许产生脱离 owning scope 的工作。

---

# 11. Queued — Exception

## UT-Q10 Failure under normal Job

使用：

```kotlin
CoroutineScope(Job() + dispatcher)
```

### Given

```text
A
B
C
```

### When

A 抛出异常。

### Then

验证：

```text
A failure
↓
parent Job cancellation
↓
remaining Coordinator work follows scope cancellation
```

Coordinator 不改变普通 Job 的 structured concurrency 规则。

---

## UT-Q11 Failure under SupervisorJob

使用：

```kotlin
CoroutineScope(SupervisorJob() + dispatcher)
```

### Given

```text
A
B
C
```

### When

A 抛出异常。

### Then

验证实际行为符合 `SupervisorJob` 的生命周期与异常传播规则。

Coordinator 自身不得额外引入：

```text
global cancellation
```

或另一套异常传播模型。

---

# 12. Coalesced — Basic

## UT-C01 Single submission executes

```text
submitted:
A

executed:
A
```

---

## UT-C02 Pending executes after running

### Given

```text
A running
```

### When

提交 B。

### Then

```text
A → B
```

---

## UT-C03 Only latest pending executes

### Given

```text
A running
```

### When

依次提交：

```text
B
C
D
```

### Then

最终：

```text
A → D
```

必须验证：

```text
B never executes
C never executes
```

---

## UT-C04 New submissions do not cancel running execution

### Given

```text
A running
```

### When

提交：

```text
B
C
D
```

### Then

A 不得因为这些新的 Coalesced submissions 被取消。

A 正常完成后：

```text
D executes
```

最终：

```text
A → D
```

注意：

这里保证的是：

```text
new Coalesced submission
≠
cancel running execution
```

owning scope cancellation 仍然可以取消 A。

---

# 13. Coalesced — Snapshot

## UT-C05 Submission uses immutable snapshot

### Given

提交一个可变业务对象。

### When

调用：

```kotlin
launchCoalesced(
    key = key,
    value = source,
    snapshot = { ... },
) {
    ...
}
```

submission 完成后修改原始 `source`。

### Then

真正 execution 必须看到：

```text
submission-time snapshot
```

不能看到 submission 之后发生的原对象修改。

---

# 14. Coalesced — Commitment Point

## UT-C06 Promoted pending submission cannot be replaced

**核心状态机测试。**

### Given

```text
A running

B submitted
C submitted
D submitted
```

此时逻辑状态：

```text
running = A
latest = D
```

### When

A 完成。

D 被提升：

```text
D:
pending → running
```

这就是 commitment point。

此后立即提交 E。

### Then

必须：

```text
A → D → E
```

不得：

```text
A → E
```

核心 invariant：

```text
latest
    = replaceable

running
    = committed
```

一旦 pending submission 被提升为 running，后续 submission 不允许再覆盖它。

---

# 15. Coalesced — Cancellation

## UT-C07 Scope cancellation drops latest pending

### Given

```text
A running
D latest pending
```

### When

owning CoroutineScope 被取消。

### Then

A 遵循正常 coroutine cancellation。

D 不执行。

验证：

> latest pending 的 eventual execution guarantee 只在 owning scope 保持 active 的前提下成立。

---

# 16. Coordination Domain

## UT-D01 Same Coordinator + Same Key coordinates

### Given

```text
Coordinator A
Key X
```

### When

多个 submission 使用 X。

### Then

必须按照 X 对应 strategy 协调。

---

## UT-D02 Same Coordinator + Different Keys are independent

### Given

```text
Coordinator A

Key X
Key Y
```

### When

X 有 active execution。

同时向 Y 提交 execution。

### Then

Y 不应该因为 X 而被协调、排队或丢弃。

核心 invariant：

```text
different key
=
different coordination state
```

---

## UT-D03 Different Coordinators + Same Key are independent

### Given

```text
Coordinator A
Coordinator B

same Key X
```

### When

Coordinator A 的 X 正在执行。

同时：

```text
Coordinator B + X
```

提交 execution。

### Then

两个 execution 可以独立运行。

验证：

```text
Coordinator instance identity
=
coordination domain
```

而不是：

```text
CoordinatorKey
=
global coordination domain
```

---

# 17. Lifecycle

## UT-L01 Coordinator does not outlive owning scope

### Given

Coordinator 使用：

```text
Scope A
```

### When

Scope A 被取消。

### Then

所有 Coordinator execution 都必须遵循 Scope A 的 cancellation。

不得产生：

```text
detached Job
independent Job
orphan coroutine
```

---

## UT-L02 Coordinator does not create independent lifecycle

验证 Coordinator 没有：

```text
cancel()
close()
independent Job
```

所有 execution 生命周期都来自传入的 CoroutineScope。

其中部分属于 API/代码结构验证，不一定需要 runtime test。

---

# 18. Type Safety

以下主要属于 compile-time contract：

```text
Once key
    不能调用 Queued API

Queued key
    不能调用 Once API

Coalesced<Int>
    不能提交 String
```

第一阶段可以依赖 Kotlin 编译器。

如果未来希望自动验证，可以引入 compile-testing。

目前不是核心单元测试的 blocker。

---

# 19. 第一阶段必须实现的测试

优先实现以下测试：

```text
Once
1. UT-O02 Duplicate submission is dropped
2. UT-O07 Duplicate once joins active execution
3. UT-O09 Joiner cancellation isolation

Queued
4. UT-Q03 Strict FIFO
5. UT-Q04 No overlap
6. UT-Q07 queued waits for own execution

Coalesced
7. UT-C03 A + B/C/D → A/D
8. UT-C04 Running execution is not cancelled
9. UT-C06 Commitment Point → A/D/E

Domain
10. UT-D03 Different Coordinators + Same Key are independent
```

这 10 个测试构成 Coordinator 第一层核心状态机测试。

---

# 20. 第二阶段测试

第一阶段全部稳定后，再增加：

```text
Once
├── multiple joiners
├── exception propagation
└── scope cancellation

Queued
├── multiple suspend callers
├── scope cancellation
├── Job exception
└── SupervisorJob exception

Coalesced
├── immutable snapshot
└── scope cancellation

Domain
└── different keys

Lifecycle
└── owning scope cancellation
```

---

# 21. 测试文件结构

建议：

```text
src/test/kotlin/.../

CoroutineCoordinatorOnceTest.kt
CoroutineCoordinatorQueuedTest.kt
CoroutineCoordinatorCoalescedTest.kt
CoroutineCoordinatorDomainTest.kt
```

后续测试数量增加后可以继续拆：

```text
CoroutineCoordinatorCancellationTest.kt
CoroutineCoordinatorExceptionTest.kt
```

不要把全部测试放进一个巨大的：

```text
CoroutineCoordinatorTest.kt
```

---

# 22. 核心测试 Invariants

测试最终应该保护以下 invariant。

## Once

```text
同 key active execution <= 1

launch duplicate
→ drop

suspend duplicate
→ join active execution

cancel joiner
→ does not cancel execution
```

---

## Queued

```text
同 key active execution <= 1

accepted submissions
→ FIFO

queued caller
→ waits own execution
```

---

## Coalesced

```text
同 key active execution <= 1

running
→ cannot be replaced

pending
→ latest wins

pending → running
→ committed
```

---

## Coordination Domain

```text
same coordinator + same key
→ coordinates

same coordinator + different key
→ independent

different coordinator + same key
→ independent
```

---

## Lifecycle

```text
Coordinator lifetime
=
owning CoroutineScope lifetime

no detached work
```

---

# 23. 测试通过标准

当上述核心 invariant 全部具有自动化测试保护后，可以认为 `CoroutineCoordinator` 的核心行为已经进入稳定阶段。

后续开发流程应该变为：

```text
Requirements / Design
        ↓
Unit Tests
        ↓
Implementation
        ↓
All Tests Pass
```

对于已经冻结的行为契约：

```text
修改 implementation
        ↓
现有 tests 必须继续通过
```

如果未来确实需要修改行为：

```text
先修改 Requirements / Design
        ↓
再修改 Test Cases
        ↓
最后修改 Implementation
```

避免通过修改测试去迁就错误实现。