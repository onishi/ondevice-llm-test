package com.example.ondevicellm

import android.content.ClipData
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SmartToy
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
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

    // カメラアプリに撮影を頼み、FileProvider 経由で cache/photos/ に書いてもらう
    val context = LocalContext.current
    val photoUri = remember {
        val file = File(context.cacheDir, "photos/capture.jpg").apply { parentFile?.mkdirs() }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) vm.attachImage(photoUri)
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::attachImage)
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
                            text = { Text("新しい会話") },
                            leadingIcon = { Icon(Icons.Default.AddComment, contentDescription = null) },
                            enabled = state.modelState is ModelState.Ready,
                            onClick = { menuOpen = false; vm.resetConversation() },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("モデルを選択") },
                            leadingIcon = { Icon(Icons.Default.SmartToy, contentDescription = null) },
                            onClick = { menuOpen = false; vm.refreshModels(); showModelDialog = true },
                        )
                        DropdownMenuItem(
                            text = { Text("ファイルから取り込む") },
                            leadingIcon = { Icon(Icons.Default.FileOpen, contentDescription = null) },
                            onClick = { menuOpen = false; picker.launch(arrayOf("*/*")) },
                        )
                        DropdownMenuItem(
                            text = { Text("システムプロンプト") },
                            leadingIcon = { Icon(Icons.Default.EditNote, contentDescription = null) },
                            onClick = { menuOpen = false; showPromptDialog = true },
                        )
                        HorizontalDivider()
                        BackendType.entries.forEach { b ->
                            DropdownMenuItem(
                                text = { Text("バックエンド: $b") },
                                leadingIcon = {
                                    Icon(
                                        if (b == BackendType.GPU) Icons.Default.Bolt else Icons.Default.Memory,
                                        contentDescription = null,
                                    )
                                },
                                trailingIcon = {
                                    if (state.preferredBackend == b) Icon(Icons.Default.Check, contentDescription = "選択中")
                                },
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
                        onLoad = vm::loadModel,
                        onLoadGeminiNano = vm::loadGeminiNano,
                        onImport = { picker.launch(arrayOf("*/*")) },
                    )
                } else {
                    MessageList(state.messages)
                }
            }

            InputBar(
                enabled = state.modelState is ModelState.Ready,
                imagesSupported = (state.modelState as? ModelState.Ready)?.supportsImages == true,
                pendingImage = state.pendingImage?.preview,
                generating = state.generating,
                onSend = vm::send,
                onStop = vm::stopGeneration,
                onTakePhoto = { takePicture.launch(photoUri) },
                onPickPhoto = {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onClearImage = vm::clearImage,
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
                Column {
                    TextButton(
                        onClick = { showModelDialog = false; vm.loadGeminiNano() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Gemini Nano  (端末内蔵 · AICore)", modifier = Modifier.fillMaxWidth())
                    }
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
                    if (state.availableModels.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "*.litertlm は見つかりません。adb push するか、メニューの「ファイルから取り込む」を使ってください。",
                            style = MaterialTheme.typography.bodySmall,
                        )
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
    is ModelState.Loading -> "読み込み中 (${ms.detail}): ${ms.name}"
    is ModelState.Ready -> "${ms.name} · ${ms.backend} · load ${"%.1f".format(ms.loadMillis / 1000.0)}s"
    is ModelState.Error -> ms.message
}

@Composable
private fun EmptyState(
    state: ChatUiState,
    pushDir: String,
    onLoad: (File) -> Unit,
    onLoadGeminiNano: () -> Unit,
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
                Text("使うモデルを選んでください", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onLoadGeminiNano, modifier = Modifier.fillMaxWidth()) {
                    Text("Gemini Nano  (端末内蔵)")
                }
                Spacer(Modifier.height(8.dp))
                state.availableModels.forEach { f ->
                    Button(onClick = { onLoad(f) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${f.name}  (${"%.2f".format(f.length() / 1e9)} GB)")
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (state.availableModels.isEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("LiteRT-LM 形式 (.litertlm) のモデルは adb で次の場所に push:", style = MaterialTheme.typography.bodyMedium)
                    SelectionContainer {
                        Text(pushDir, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = onImport) { Text("ファイルから取り込む") }
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
            if (msg.image != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Image(
                        msg.image.asImageBitmap(),
                        contentDescription = "添付した画像",
                        modifier = Modifier
                            .widthIn(max = 220.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(msg.text, color = fg)
                }
            } else if (msg.streaming && msg.text.isEmpty()) {
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
private fun InputBar(
    enabled: Boolean,
    imagesSupported: Boolean,
    pendingImage: Bitmap?,
    generating: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    onClearImage: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var attachMenuOpen by remember { mutableStateOf(false) }
    if (pendingImage != null) {
        Box(Modifier.padding(start = 12.dp, top = 8.dp)) {
            Image(
                pendingImage.asImageBitmap(),
                contentDescription = "添付する画像",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            FilledIconButton(
                onClick = onClearImage,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 8.dp, y = (-8).dp)
                    .size(24.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "画像を外す", modifier = Modifier.size(14.dp))
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (imagesSupported) {
            Box {
                IconButton(onClick = { attachMenuOpen = true }, enabled = enabled && !generating) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = "画像を添付")
                }
                DropdownMenu(expanded = attachMenuOpen, onDismissRequest = { attachMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("カメラで撮る") },
                        leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                        onClick = { attachMenuOpen = false; onTakePhoto() },
                    )
                    DropdownMenuItem(
                        text = { Text("写真を選ぶ") },
                        leadingIcon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                        onClick = { attachMenuOpen = false; onPickPhoto() },
                    )
                }
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            enabled = enabled,
            placeholder = {
                Text(
                    when {
                        !enabled -> "モデルを読み込んでください"
                        pendingImage != null -> "画像について質問 (空欄でも送れます)"
                        else -> "メッセージを入力"
                    }
                )
            },
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
                enabled = enabled && (text.isNotBlank() || pendingImage != null),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "送信")
            }
        }
    }
}
