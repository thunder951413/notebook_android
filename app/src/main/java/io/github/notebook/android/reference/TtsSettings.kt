package io.github.notebook.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.notebook.android.sync.SyncRepository
import io.github.notebook.android.tts.*
import java.util.Locale
import kotlin.math.roundToInt

private const val SAMPLE_TEXT = "你好，这是一段朗读试听。熄屏之后，也可以继续为你读文章。"

/** Connection, voice and speed for reading notes aloud through a 通义千问 speech service. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TtsSettingsScreen(repo: SyncRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val saved = remember { repo.ttsSettings() }
    var api by remember { mutableStateOf(saved.api) }
    var endpoint by remember { mutableStateOf(saved.endpoint) }
    var apiKey by remember { mutableStateOf(saved.apiKey) }
    var model by remember { mutableStateOf(saved.model) }
    var voice by remember { mutableStateOf(saved.voice) }
    var language by remember { mutableStateOf(saved.language) }
    var instruction by remember { mutableStateOf(saved.instruction) }
    var speed by remember { mutableFloatStateOf(saved.speed) }
    var priceText by remember { mutableStateOf(trimNumber(saved.unitPrice)) }
    var balanceText by remember { mutableStateOf(if (saved.balance > 0f) trimNumber(saved.balance) else "") }
    var usedChars by remember { mutableLongStateOf(repo.ttsUsedChars()) }
    var showKey by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val player by TtsPlayer.state.collectAsState()

    fun current() = TtsSettings(
        api, endpoint, apiKey, model, voice, language, instruction, speed,
        unitPrice = priceText.toFloatOrNull()?.takeIf { it >= 0f } ?: TtsCost.DEFAULT_UNIT_PRICE,
        balance = balanceText.toFloatOrNull()?.takeIf { it > 0f } ?: 0f,
    )
    fun persist(): Boolean = runCatching { repo.saveTtsSettings(current()) }
        .onFailure { message = it.localizedMessage }.isSuccess

    // Each finished request adds to the count, so refresh when playback changes.
    LaunchedEffect(player.phase) { usedChars = repo.ttsUsedChars() }
    BackHandler {
        persist()
        onBack()
    }
    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundTool(Icons.Default.ArrowBack, "返回", { persist(); onBack() }, filled = false)
            Text("语音朗读", fontSize = 28.sp)
        }
        Text(
            "在阅读页长按正文，选“朗读”即可从这里读到文末；熄屏后会继续朗读，通知栏可暂停和切换段落。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        SectionLabel("接口")
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TtsApi.entries.forEach { option ->
                        FilterChip(
                            selected = api == option,
                            onClick = {
                                if (api != option) {
                                    api = option
                                    endpoint = option.defaultEndpoint
                                    model = option.defaultModel
                                    voice = option.defaultVoice
                                }
                            },
                            label = { Text(option.label) },
                        )
                    }
                }
                OutlinedTextField(
                    endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("接口地址") },
                    supportingText = { Text(api.endpointHint) },
                )
                OutlinedTextField(
                    apiKey, { apiKey = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("API Key") },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton({ showKey = !showKey }) {
                            Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showKey) "隐藏" else "显示")
                        }
                    },
                    supportingText = { Text("只保存在本机的加密存储中，不会同步。") },
                )
                OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("模型") })
            }
        }

        SectionLabel("音色")
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val known = TtsVoices.of(api).firstOrNull { it.id == voice }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(known?.name ?: voice.ifBlank { "未选择" }, fontSize = 18.sp)
                        Text(
                            known?.note ?: "自定义音色 ID",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton({ picking = true }) { Text("选择音色") }
                }
                OutlinedTextField(
                    voice, { voice = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("音色 ID") },
                    supportingText = { Text("可直接填写自己的复刻或设计音色 ID。") },
                )
                if (api == TtsApi.Qwen3Flash) {
                    Text("语种", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TTS_LANGUAGES.forEach { (code, label) ->
                            FilterChip(language == code, { language = code }, { Text(label) })
                        }
                    }
                    Text(
                        "文章只有一种语言时，选对应语种读得更准；中英混合用“自动”。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    OutlinedTextField(
                        instruction, { instruction = it }, Modifier.fillMaxWidth(), label = { Text("语气或方言（可选）") },
                        supportingText = { Text("例如“用温柔缓慢的语气”“用四川话朗读”。") },
                    )
                }
            }
        }

        SectionLabel("播放")
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                Text("语速 ${String.format(Locale.ROOT, "%.1f", speed)}×")
                Slider(speed, { speed = (it * 10).roundToInt() / 10f }, valueRange = 0.5f..2f, steps = 14)
            }
        }

        SectionLabel("用量与余额（估算）")
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val now = current()
                val spent = TtsCost.spent(usedChars, now.unitPrice)
                val remaining = TtsCost.remaining(now.balance, usedChars, now.unitPrice)
                if (remaining != null) {
                    Text("剩余 ${TtsCost.yuan(remaining)}", fontSize = 28.sp, color = if (remaining <= 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Text(
                        "约可再朗读 ${String.format(Locale.ROOT, "%.1f", TtsCost.charactersLeft(remaining, now.unitPrice) / 10_000.0)} 万字",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "已合成 ${usedChars} 字 · 约花费 ${TtsCost.yuan(spent)}",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        balanceText, { balanceText = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.weight(1f), singleLine = true,
                        label = { Text("起算余额（元）") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    )
                    OutlinedTextField(
                        priceText, { priceText = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.weight(1f), singleLine = true,
                        label = { Text("元 / 万字") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    )
                }
                OutlinedButton({
                    if (persist()) {
                        runCatching { repo.resetTtsUsage() }.onSuccess { usedChars = 0; message = "已按当前余额重新开始计算" }.onFailure { message = it.localizedMessage }
                    }
                }) { Text("按此余额重新计算") }
                Text(
                    "阿里云没有用 API Key 查询余额的接口，这里按本机合成的字数和单价估算：先填你账户当前的余额，之后每次合成自动扣减。单价默认 0.8 元/万字，以百炼控制台的价格为准；用其他设备或网页产生的消耗不会计入。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val canTest = current().configured
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                {
                    if (player.phase != TtsPhase.Idle) TtsPlayer.stop()
                    else {
                        TtsPlayer.dismissError()
                        TtsPlayer.start(context, "朗读试听", listOf(SAMPLE_TEXT), current())
                    }
                },
                Modifier.weight(1f),
                enabled = canTest || player.phase != TtsPhase.Idle,
            ) {
                Icon(if (player.phase == TtsPhase.Idle) Icons.Default.PlayArrow else Icons.Default.Stop, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (player.phase == TtsPhase.Idle) "试听" else "停止")
            }
            Button({ if (persist()) message = "朗读设置已保存" }, Modifier.weight(1f)) { Text("保存") }
        }
        player.error?.let {
            Text("试听失败：$it", color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
        }
        message?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
        }
        Text(
            "合成按字数计费，由所填 Key 的账号承担。如果熄屏后朗读被系统中断，请在系统的电池设置里允许 Notebook 后台运行。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(32.dp))
    }

    if (picking) {
        ModalBottomSheet(onDismissRequest = { picking = false }) {
            Text("选择音色", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
            LazyVoiceList(TtsVoices.of(api), voice) {
                voice = it.id
                picking = false
            }
        }
    }
}

@Composable
private fun LazyVoiceList(voices: List<TtsVoice>, selected: String, onPick: (TtsVoice) -> Unit) {
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
        items(voices.size, key = { voices[it].id }) { index ->
            val item = voices[index]
            ListItem(
                headlineContent = { Text(item.name) },
                supportingContent = { Text("${item.note}  ·  ${item.id}", fontSize = 12.sp) },
                trailingContent = { if (item.id == selected) Icon(Icons.Default.Check, "已选中", tint = MaterialTheme.colorScheme.tertiary) },
                modifier = Modifier.clickable { onPick(item) },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun trimNumber(value: Float): String = value.toBigDecimal().stripTrailingZeros().toPlainString()

@Composable
private fun SectionLabel(text: String) {
    Text(text, Modifier.padding(start = 20.dp, top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
}
