package com.coroutinefabric.demo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.coroutinefabric.CoordinatorKey
import com.coroutinefabric.CoroutineCoordinator
import com.coroutinefabric.launchCoalesced
import com.coroutinefabric.launchOnce
import com.coroutinefabric.launchQueued
import com.coroutinefabric.joinOnce
import com.coroutinefabric.joinQueued
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val coordinator = CoroutineCoordinator(lifecycleScope)
    private val initKey = CoordinatorKey.once()
    private val refreshKey = CoordinatorKey.once()
    private val uploadKey = CoordinatorKey.queued()
    private val tempKey = CoordinatorKey.coalesced<Int>()
    private val commitKey = CoordinatorKey.coalesced<Int>()
    private var targetTemp = 20

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var tempLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        logView = findViewById(R.id.logView)
        logScroll = findViewById(R.id.logScroll)
        tempLabel = findViewById(R.id.tempLabel)
        findViewById<MaterialButton>(R.id.btnOnce).setOnClickListener { submitOnce() }
        findViewById<MaterialButton>(R.id.btnOnceWait).setOnClickListener { submitOnceAndWait() }
        findViewById<MaterialButton>(R.id.btnOnceRapid).setOnClickListener { triggerRapidRefresh() }
        findViewById<MaterialButton>(R.id.btnQueued).setOnClickListener { submitQueued() }
        findViewById<MaterialButton>(R.id.btnQueuedWait).setOnClickListener { submitQueuedAndWait() }
        findViewById<MaterialButton>(R.id.btnTempMinus).setOnClickListener { changeTemp(-1) }
        findViewById<MaterialButton>(R.id.btnTempPlus).setOnClickListener { changeTemp(+1) }
        findViewById<MaterialButton>(R.id.btnTempRapid).setOnClickListener { rapidTempUp() }
        findViewById<MaterialButton>(R.id.btnCommit).setOnClickListener { submitCommitmentDemo() }
        findViewById<MaterialButton>(R.id.btnCopyLog).setOnClickListener { copyLog() }
        findViewById<MaterialButton>(R.id.btnClear).setOnClickListener { logView.text = "" }
    }

    private fun copyLog() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("CoroutineFabric log", logView.text))
        Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun log(msg: String) {
        val stamp = (System.currentTimeMillis() % 1_000_000).toString().padStart(6, '0')
        logView.append("$stamp  $msg\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun submitOnce() {
        coordinator.launchOnce(initKey) {
            log("launchOnce: 初始化开始（2s）")
            delay(2000)
            log("launchOnce: 初始化完成")
        }
        log("launchOnce: 已提交（同 key 已有活动时静默忽略，不等待）")
    }

    private fun submitOnceAndWait() {
        lifecycleScope.launch {
            log("joinOnce: 进入 joinOnce()（1.5s，等待执行完成）")
            coordinator.joinOnce(refreshKey) {
                log("joinOnce: 执行开始（1.5s）")
                delay(1500)
                log("joinOnce: 执行完成")
            }
            log("joinOnce: joinOnce() 返回（当前 execution 已完成）")
        }
    }

    private fun triggerRapidRefresh() {
        repeat(4) { i ->
            lifecycleScope.launch {
                delay(i * 300L)
                log("joinOnce[$i]: 进入 joinOnce()")
                coordinator.joinOnce(refreshKey) {
                    log("joinOnce[$i]: block 真正执行（1.5s）")
                    delay(1500)
                    log("joinOnce[$i]: block 执行完成")
                }
                log("joinOnce[$i]: joinOnce() 返回（等待当前 execution 完成）")
            }
        }
        log("joinOnce: 连续调用 4 次（间隔 300ms）—— 只有首个 block 执行，其余 join 当前 execution")
    }

    private fun submitQueued() {
        listOf("A", "B", "C").forEach { name ->
            coordinator.launchQueued(uploadKey) {
                log("queued: 任务 $name 开始（0.8s）")
                delay(800)
                log("queued: 任务 $name 完成")
            }
        }
        log("launchQueued: 已提交 A/B/C（按 FIFO 串行执行，不等待）")
    }

    private fun submitQueuedAndWait() {
        listOf("A", "B", "C").forEach { name ->
            lifecycleScope.launch {
                coordinator.joinQueued(uploadKey) {
                    log("joinQueued: 任务 $name 开始（0.8s）")
                    delay(800)
                    log("joinQueued: 任务 $name 完成")
                }
                log("joinQueued: 任务 $name 的 joinQueued() 返回（等到自己的 execution 完成）")
            }
        }
        log("joinQueued: 已提交 A/B/C（等待方式：各自的 joinQueued() 在自己的执行完成后返回）")
    }

    // Temperature control: the "device" applies a setpoint slowly (1.5s). While it is
    // applying, rapid new setpoints coalesce into the latest one — only the first and the
    // latest setpoint are ever applied.
    private fun changeTemp(delta: Int) {
        targetTemp += delta
        setTempValue(targetTemp)
    }

    private fun rapidTempUp() {
        repeat(5) { i ->
            lifecycleScope.launch {
                delay(i * 200L)
                targetTemp += 1
                setTempValue(targetTemp)
            }
        }
        log("temp: 快速 +5℃（间隔 200ms，共提交 5 个设定值）")
    }

    private fun setTempValue(value: Int) {
        tempLabel.text = "目标温度：${value}℃"
        log("temp: 设定值改为 ${value}℃（设备正在应用其他值时，中间设定值会被合并）")
        coordinator.launchCoalesced(tempKey, value) { v ->
            log("temp: 设备开始应用 ${v}℃（1.5s）")
            delay(1500)
            log("temp: 应用 ${v}℃ 完成")
        }
    }

    // Commitment point (temperature use case): 20℃ applying (2s); 21/22/23 arrive while it
    // runs and coalesce into 23; 20 finishes -> 23 is promoted to running (committed);
    // 24 arrives while 23 applies. Result: 20 -> 23 -> 24. 21/22 are never applied, and
    // 23 — already promoted — is not replaced by 24.
    private fun submitCommitmentDemo() {
        val plan = listOf(0L to 20, 600L to 21, 1000L to 22, 1400L to 23, 2400L to 24)
        for ((delayMs, value) in plan) {
            lifecycleScope.launch {
                delay(delayMs)
                log("commit: 设定值改为 ${value}℃")
                coordinator.launchCoalesced(commitKey, value) { v ->
                    val holdMs = if (v == 20) 2000L else 800L
                    log("commit: 设备开始应用 ${v}℃（${holdMs}ms）")
                    delay(holdMs)
                    log("commit: 应用 ${v}℃ 完成")
                }
            }
        }
        log("commit: 20℃ 应用（2s）中 21/22/23 陆续到达合并为 23；20 完成 → 23 被提升（承诺点，不可再被替换）；24 在 23 应用期间到达")
        log("commit: 预期结果 20 → 23 → 24（21/22 永远不应用；23 不会被 24 替换）")
    }
}
