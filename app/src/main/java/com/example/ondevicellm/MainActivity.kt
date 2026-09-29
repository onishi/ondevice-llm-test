package com.example.ondevicellm

import android.content.ClipData
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            ) {
                ChatScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showModelDialog by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showPromptDialog by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importModel)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("On-Device LLM Chat", style = MaterialTheme.typography.titleMedium)
                        Text(
                            statusText(state.modelState),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "メニュー")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("モデルを選択") },
                            onClick = { menuOpen = false; vm.refreshModels(); showModelDialog = true },
                        )
                        DropdownMenuItem(
                            text = { Text("ファイルから取り込む") },
                            onClick = { menuOpen = false; picker.launch(arrayOf("*/*")) },
                        )
                        DropdownMenuItem(
                            text = { Text("システムプロンプト") },
                            onClick = { menuOpen = false; showPromptDialog = true },
                        )
                        DropdownMenuItem(
                            text = { Text("会話をリセット") },
                            enabled = state.modelState is ModelState.Ready,
                            onClick = { menuOpen = false; vm.resetConversation() },
                        )
                        HorizontalDivider()
                        BackendType.entries.forEach { b ->
                            DropdownMenuItem(
                                text = { Text((if (state.preferredBackend == b) "✓ " else "   ") + "バックエンド: $b") },
                                onClick = { menuOpen = false; vm.setPreferredBackend(b) },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            when (val ms = state.modelState) {
                is ModelState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is ModelState.Copying -> LinearProgressIndicator(progress = { ms.progress }, modifier = Modifier.fillMaxWidth())
                else -> {}
            }

            Box(Modifier.weight(1f)) {
                if (state.messages.isEmpty()) {
                    EmptyState(
                        state = state,
                        pushDir = vm.externalModelDir?.absolutePath ?: "",
                        onSelect = { vm.refreshModels(); showModelDialog = true },
                        onLoad = vm::loadModel,
                        onImport = { picker.launch(arrayOf("*/*")) },
                    )
                } else {
                    MessageList(state.messages)
                }
            }

            InputBar(
                enabled = state.modelState is ModelState.Ready,
                generating = state.generating,
                onSend = vm::send,
                onStop = vm::stopGeneration,
            )
        }
    }

    if (showPromptDialog) {
        SystemPromptDialog(
            initial = state.systemPrompt,
            onDismiss = { showPromptDialog = false },
            onSave = { showPromptDialog = false; vm.setSystemPrompt(it) },
        )
    }

    if (showModelDialog) {
        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            title = { Text("モデルを選択") },
            text = {
                if (state.availableModels.isEmpty()) {
                    Text("*.litertlm が見つかりません。\n\nadb push するか、メニューの「ファイルから取り込む」を使ってください。")
                } else {
                    Column {
                        state.availableModels.forEach { f ->
                            TextButton(
                                onClick = { showModelDialog = false; vm.loadModel(f) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "${f.name}  (${"%.2f".format(f.length() / 1e9)} GB)",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelDialog = false }) { Text("閉じる") }
            },
        )
    }
}

@Composable
private fun SystemPromptDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("システムプロンプト") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 10,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "保存すると会話がリセットされます",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { text = DEFAULT_SYSTEM_PROMPT }) { Text("デフォルトに戻す") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

private fun statusText(ms: ModelState): String = when (ms) {
    ModelState.NotLoaded -> "モデル未読み込み"
    is ModelState.Copying -> "取り込み中 ${(ms.progress * 100).toInt()}%: ${ms.name}"
    is ModelState.Loading -> "読み込み中 (${ms.backend}): ${ms.name}"
    is ModelState.Ready -> "${ms.name} · ${ms.backend} · load ${"%.1f".format(ms.loadMillis / 1000.0)}s"
    is ModelState.Error -> ms.message
}

@Composable
private fun EmptyState(
    state: ChatUiState,
    pushDir: String,
    onSelect: () -> Unit,
    onLoad: (File) -> Unit,
    onImport: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val ms = state.modelState) {
            is ModelState.Ready -> Text("「${ms.name}」と話してみましょう", style = MaterialTheme.typography.titleMedium)
            is ModelState.Loading, is ModelState.Copying -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(statusText(ms))
            }
            else -> {
                if (ms is ModelState.Error) {
                    Text(ms.message, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                }
                if (state.availableModels.isNotEmpty()) {
                    Text("使うモデルを選んでください", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    state.availableModels.forEach { f ->
                        Button(onClick = { onLoad(f) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${f.name}  (${"%.2f".format(f.length() / 1e9)} GB)")
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    TextButton(onClick = onImport) { Text("ファイルから取り込む") }
                    return@Column
                }
                Text("LiteRT-LM 形式 (.litertlm) のモデルを用意してください", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Text("adb で次の場所に push:", style = MaterialTheme.typography.bodyMedium)
                SelectionContainer {
                    Text(pushDir, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onSelect) { Text("モデルを選択") }
                    Button(onClick = onImport) { Text("ファイルから取り込む") }
                }
            }
        }
    }
}

@Composable
private fun MessageList(messages: List<ChatMessage>) {
    val listState = rememberLazyListState()
    val last = messages.lastOrNull()
    LaunchedEffect(messages.size, last?.text?.length) {
        listState.scrollToItem(messages.lastIndex.coerceAtLeast(0), Int.MAX_VALUE)
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(messages, key = { it.id }) { MessageBubble(it) }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == Role.USER
    val colors = MaterialTheme.colorScheme
    val (bg, fg) = when (msg.role) {
        Role.USER -> colors.primaryContainer to colors.onPrimaryContainer
        Role.MODEL -> colors.surfaceVariant to colors.onSurfaceVariant
        Role.ERROR -> colors.errorContainer to colors.onErrorContainer
    }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Box(
            Modifier
                .then(if (isUser) Modifier.widthIn(max = 320.dp) else Modifier.fillMaxWidth())
                .background(bg, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (msg.streaming && msg.text.isEmpty()) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = fg)
            } else {
                SelectionContainer {
                    if (msg.role == Role.MODEL) {
                        MarkdownContent(displayText(msg), msg.streaming, fg)
                    } else {
                        Text(msg.text, color = fg)
                    }
                }
            }
        }
        if (msg.role == Role.MODEL && !msg.streaming) {
            val clipboard = LocalClipboard.current
            val scope = rememberCoroutineScope()
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 表示と同じ後処理 (<think> や化けた文字の除去) を済ませた Markdown をコピーする
                IconButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("response", displayText(msg))))
                        }
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "回答を Markdown でコピー",
                        tint = colors.outline,
                        modifier = Modifier.size(16.dp),
                    )
                }
                msg.stats?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = colors.outline)
                }
            }
        }
    }
}

private val thinkBlock = Regex("<think>[\\s\\S]*?</think>\\s*")

/**
 * 思考モデルの <think>…</think>、不正な UTF-8 のトークンが化けた U+FFFD、
 * 文中で浮いている無関係な文字体系の断片 (removeStrayScripts) は表示しない
 */
private fun displayText(msg: ChatMessage): String {
    if (msg.role != Role.MODEL) return msg.text
    val text = removeStrayScripts(msg.text.replace(thinkBlock, "").replace("\uFFFD", ""))
    val open = text.indexOf("<think>")
    return if (open < 0) text else text.substring(0, open) + "(考え中…)"
}

@Composable
private fun InputBar(enabled: Boolean, generating: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            enabled = enabled,
            placeholder = { Text(if (enabled) "メッセージを入力" else "モデルを読み込んでください") },
            modifier = Modifier.weight(1f),
            maxLines = 5,
        )
        Spacer(Modifier.size(8.dp))
        if (generating) {
            FilledIconButton(onClick = onStop) {
                Icon(Icons.Default.Stop, contentDescription = "停止")
            }
        } else {
            FilledIconButton(
                onClick = { onSend(text); text = "" },
                enabled = enabled && text.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "送信")
            }
        }
    }
}
