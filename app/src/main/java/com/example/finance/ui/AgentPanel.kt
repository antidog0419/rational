// ui/AgentPanel.kt
// 「识屏助手」：sult_liban 移植的第二感知通道入口（MediaProjection 录屏 + MLKit OCR + 技能决策 + 比价）。
// 自包含：权限引导(悬浮窗/通知/录屏)、AgentGraph 配置、离线"手动分析"演示、最近决策预览。
// 说明：录屏授权为系统安全弹窗，必须用户手动点「立即开始」；本面板只负责拉起。

package com.example.finance.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.finance.agent.AgentGraph
import com.example.finance.agent.AnalysisBus
import com.example.finance.capture.FloatingCaptureService
import com.example.finance.config.ConfigRepository
import com.example.finance.data.UserSettings
import com.example.finance.scene.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { UserSettings(context) }

    // 状态订阅
    val analysis by AnalysisBus.state.collectAsState()
    val decisions by AgentGraph.repository.decisions.collectAsState(initial = emptyList())

    // 运行中?（通过前台服务判定：读 AnalysisBus 太粗，用服务自状态不可靠，改为由按钮态提示）
    var captureRunning by remember { mutableStateOf(false) }

    // 配置（读取一次；DeepSeek 自动同步自「我的 → DeepSeek 云设置」）
    var llmOcrEnabled by remember { mutableStateOf(true) }
    var llmSyncNote by remember { mutableStateOf("正在读取…") }
    LaunchedEffect(Unit) {
        val cfg = AgentGraph.configRepository.configuration.first()
        llmOcrEnabled = cfg.llmOcrEnabled
        llmSyncNote = llmSyncLabel(cfg)
    }

    // 手动分析输入
    var manualName by remember { mutableStateOf("索尼 WH-1000XM5 无线降噪耳机") }
    var manualPriceYuan by remember { mutableStateOf("2999") }
    var manualBusy by remember { mutableStateOf(false) }
    var manualResult by remember { mutableStateOf<String?>(null) }
    var lastManual by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var manualRecordState by remember { mutableStateOf<String?>(null) }
    var recordedIds by remember { mutableStateOf(setOf<String>()) }

    // 权限 launcher
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            AnalysisBus.update(com.example.finance.scene.AnalysisState.Error("屏幕捕获授权被拒绝，可再次点击开启"))
            return@rememberLauncherForActivityResult
        }
        val intent = Intent(context, FloatingCaptureService::class.java).apply {
            action = FloatingCaptureService.ACTION_START
            putExtra(FloatingCaptureService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(FloatingCaptureService.EXTRA_RESULT_DATA, data)
        }
        context.startForegroundService(intent)
        captureRunning = true
    }
    fun requestProjection() {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> requestProjection() }

    fun launchCaptureFlow() {
        scope.launch(Dispatchers.IO) { AgentGraph.syncAll(context) }
        when {
            !Settings.canDrawOverlays(context) -> {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                )
            }
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED -> notifLauncher.launch(
                android.Manifest.permission.POST_NOTIFICATIONS
            )
            else -> requestProjection()
        }
    }

    fun stopCapture() {
        context.startService(
            Intent(context, FloatingCaptureService::class.java).apply { action = FloatingCaptureService.ACTION_STOP }
        )
        captureRunning = false
    }

    fun syncDeepSeek() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                AgentGraph.syncLlmFromFinance(context)
                val cfg = AgentGraph.configRepository.configuration.first()
                llmSyncNote = llmSyncLabel(cfg)
            }.onFailure {
                llmSyncNote = "同步失败：${it.message}"
            }
        }
    }

    fun setLlmOcr(value: Boolean) {
        llmOcrEnabled = value
        scope.launch(Dispatchers.IO) {
            runCatching {
                val cfg = AgentGraph.configRepository.configuration.first()
                AgentGraph.configRepository.save(
                    cfg.llmEndpoint, cfg.llmApiKey, cfg.llmModel,
                    cfg.searchBaseUrl, cfg.searchApiKey, cfg.searchEngine,
                    cfg.readerEnabled, value,
                )
            }
        }
    }

    fun runManual() {
        if (manualBusy) return
        manualBusy = true
        manualResult = null
        manualRecordState = null
        scope.launch(Dispatchers.IO) {
            runCatching { AgentGraph.syncAll(context) } // 预算口径 + DeepSeek 保持最新
            val cents = parseYuanToCents(manualPriceYuan.trim())
            if (cents == null) {
                manualResult = "价格格式无效"
                manualBusy = false
                return@launch
            }
            val name = manualName.trim().ifBlank { "手动输入商品" }
            val category = when {
                listOf("手机", "电脑", "耳机", "相机", "电视", "平板").any { name.contains(it) } -> "electronics"
                listOf("冰箱", "空调", "洗衣机").any { name.contains(it) } -> "appliance"
                listOf("衣", "鞋", "包", "美妆", "戒指", "项链").any { name.contains(it) } -> "fashion"
                else -> "other"
            }
            val scene = SceneContext(
                sceneType = "manual",
                product = Product(name, category),
                price = PriceInfo(cents),
                confidence = 1.0, productConfidence = 1.0, priceConfidence = 1.0,
            )
            runCatching {
                val (_, decision) = AgentGraph.orchestrator.analyzeScene(scene) { AnalysisBus.update(it) }
                manualResult = buildString {
                    append(decision.display.title)
                    append("\n风险：${decision.riskLevel.name} · 建议：${decision.recommendation.name}")
                    if (decision.delayHours != null) append("\n冷静 ${decision.delayHours} 小时再决定")
                    decision.display.keyPoints.forEach { append("\n• $it") }
                    decision.price?.let {
                        if (it.availability.name == "UNAVAILABLE") append("\n价格：无可靠比价证据")
                        else {
                            val low = it.referenceLowCents?.let { v -> "%.2f".format(v / 100.0) } ?: "-"
                            val high = it.referenceHighCents?.let { v -> "%.2f".format(v / 100.0) } ?: "-"
                            append("\n价格参考：¥$low–¥$high（${it.evidenceSource.name}）")
                        }
                    }
                }
            }.onFailure {
                manualResult = "分析失败：${it.message}"
            }
            if (!manualResult.orEmpty().startsWith("分析失败")) {
                lastManual = name to cents
            }
            manualBusy = false
        }
    }

    /** 把"手动分析"的该笔确认记入本地账单（来源=识屏，与手动补记同库同权） */
    fun recordManualPurchase() {
        val m = lastManual ?: return
        scope.launch(Dispatchers.IO) {
            val r = com.example.finance.data.BillWriter.add(
                context, m.first, m.second / 100.0,
                com.example.finance.data.BillSources.SCREEN, "识屏决策·手动分析确认",
            )
            manualRecordState = when (r) {
                null -> "无法记入：名称或金额无效"
                -1L -> "该笔已存在（识屏·同商家同金额·同一分钟），未重复记入"
                else -> "✓ 已记入本地账单（来源：识屏）"
            }
        }
    }

    /** 最近决策列表里主动"记一笔" */
    fun recordDecision(decisionId: String, name: String, cents: Long) {
        if (decisionId in recordedIds) return
        scope.launch(Dispatchers.IO) {
            val r = com.example.finance.data.BillWriter.add(
                context, name, cents / 100.0,
                com.example.finance.data.BillSources.SCREEN, "识屏决策购买确认",
            )
            if (r != null && r != -1L) recordedIds = recordedIds + decisionId
        }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🧭 识屏助手 · AI 购物决策", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    color = if (captureRunning) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape
                ) { Text(if (captureRunning) "运行中" else "未运行",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall) }
            }
            Text("第二条感知通道：录屏截取当前页面 → 本机 OCR → 技能决策（预算/储蓄目标/冲动/比价），与无障碍记账互不依赖。截屏仅本机识别，不上传。",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // 状态行
            Text(stateText(analysis), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { if (captureRunning) stopCapture() else launchCaptureFlow() }, modifier = Modifier.weight(1f)) {
                    Text(if (captureRunning) "停止识屏" else "开始识屏（需授权录屏）")
                }
            }
            Text("首次使用会依次请求：悬浮窗 → 通知 → 系统「开始录制」弹窗（必须手动点「立即开始」）。授权后会出现蓝色悬浮球「理伴一下」，在任意购物页点它即可分析。",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)

            HorizontalDivider()

            // 配置（统一 DeepSeek）
            Text("配置 · 统一 DeepSeek", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("LLM 场景提取 / 价格估价", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Switch(checked = llmOcrEnabled, onCheckedChange = ::setLlmOcr)
            }
            Text(llmSyncNote, style = MaterialTheme.typography.labelSmall,
                color = if (llmSyncNote.startsWith("DeepSeek 已同步"))
                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = ::syncDeepSeek, modifier = Modifier.fillMaxWidth()) {
                Text("立即同步「我的 → DeepSeek 云设置」")
            }
            Text("识别与估价统一使用你的 DeepSeek Key（启动/手动分析时自动同步，不再需要智谱）；估价为模型知识参考，非实时成交价。",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)

            HorizontalDivider()

            // 手动分析（无需任何授权的离线演示）
            Text("手动分析演示（离线可用）", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = manualName, onValueChange = { manualName = it },
                label = { Text("商品名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = manualPriceYuan, onValueChange = { manualPriceYuan = it },
                    label = { Text("价格（元）") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = ::runManual, enabled = !manualBusy, modifier = Modifier.weight(1f)) {
                    Text(if (manualBusy) "分析中…" else "分析")
                }
            }
            manualResult?.let { r ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Text(r, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            lastManual?.let {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = ::recordManualPurchase, enabled = manualRecordState == null,
                        modifier = Modifier.weight(1f)) {
                        Text(if (manualRecordState == null) "＋ 记一笔到本地账单" else "已处理")
                    }
                }
                manualRecordState?.let { s ->
                    Text(s, style = MaterialTheme.typography.labelSmall,
                        color = if (s.startsWith("✓")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error)
                }
            }

            // 最近决策
            if (decisions.isNotEmpty()) {
                HorizontalDivider()
                Text("最近决策（本地 agent.db）", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                decisions.take(3).forEach { d ->
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(d.productName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f), maxLines = 1)
                                Text(riskTag(d.recommendation), style = MaterialTheme.typography.labelSmall,
                                    color = recommendationColor(d.recommendation))
                            }
                            Text("¥${d.priceCents / 100.0} · ${fmtDecisionTime(d.createdAt)} · ${d.recommendation}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    enabled = d.decisionId !in recordedIds,
                                    onClick = { recordDecision(d.decisionId, d.productName, d.priceCents) }
                                ) { Text(if (d.decisionId in recordedIds) "✓ 已记账" else "＋ 记一笔") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun llmSyncLabel(cfg: com.example.finance.config.ApiConfiguration): String =
    if (cfg.llmConfigured) "DeepSeek 已同步：模型 ${cfg.llmModel}（${cfg.llmApiKey.take(6)}***）"
    else "尚未同步 DeepSeek：请先在「我的 → DeepSeek 云设置」填入 API Key 并保存"

private fun stateText(s: com.example.finance.scene.AnalysisState): String = when (s) {
    is com.example.finance.scene.AnalysisState.Idle -> "空闲：开始识屏后，在购物页点悬浮球即可分析"
    is com.example.finance.scene.AnalysisState.Capturing -> "正在截屏…"
    is com.example.finance.scene.AnalysisState.Recognizing -> "正在本地 OCR 识别…"
    is com.example.finance.scene.AnalysisState.ExtractingProduct -> "正在智能提取商品与价格…"
    is com.example.finance.scene.AnalysisState.PreliminaryResult -> "初步结论已出，正在比价…"
    is com.example.finance.scene.AnalysisState.EnrichingPrice -> "正在查询参考价格…"
    is com.example.finance.scene.AnalysisState.NeedsCorrection -> "需要修正：${s.message}"
    is com.example.finance.scene.AnalysisState.Result -> "已完成：${s.decision.display.title}"
    is com.example.finance.scene.AnalysisState.Error -> "提示：${s.message}"
}

private fun riskTag(recommendation: String): String = when (recommendation) {
    "BUY" -> "可考虑购买"
    "DELAY" -> "建议再等等"
    else -> "建议放弃"
}

@Composable
private fun recommendationColor(recommendation: String): Color = when (recommendation) {
    "BUY" -> MaterialTheme.colorScheme.primary
    "DELAY" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

private fun fmtDecisionTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
