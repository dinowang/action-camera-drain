package net.dinowang.actioncameradrain.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.dinowang.actioncameradrain.data.config.UploadConfig
import net.dinowang.actioncameradrain.domain.filing.DeviceSummary
import net.dinowang.actioncameradrain.domain.upload.FileUploadStatus
import net.dinowang.actioncameradrain.domain.upload.StartMode
import net.dinowang.actioncameradrain.domain.upload.UploadProgress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val config by vm.config.collectAsState()
    val cards by vm.cards.collectAsState()
    val container by vm.container.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text("Action Camera Drain") })
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ConfigSection(config)
            ContainerSection(
                state = container,
                enabled = config is UploadConfig.AzureBlob,
                onInput = vm::onContainerInputChanged,
                onSelect = vm::selectContainer,
                onRefresh = vm::refreshRemoteContainers,
                onCreate = vm::createContainer,
            )
            HorizontalDivider()
            CardsSection(
                cards = cards,
                onPickTree = { deviceId, uri -> vm.attachTreeUri(deviceId, uri) },
                onStart = { deviceId, uri, mode -> vm.startUpload(deviceId, uri, mode) },
                onReplan = { deviceId, uri -> vm.planCard(deviceId, uri) },
                onPause = { deviceId, uri -> vm.pauseUpload(deviceId, uri) },
                onCancel = { deviceId, uri -> vm.cancelUpload(deviceId, uri) },
                buildPickerIntent = vm::buildPickerIntent,
            )
        }
    }
}

@Composable
private fun ConfigSection(config: UploadConfig?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("目的地：", style = MaterialTheme.typography.bodyMedium)
        val display = when (config) {
            null -> "—"
            is UploadConfig.AzureBlob -> config.shortLabel()
        }
        Text(
            display,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

private fun UploadConfig.AzureBlob.shortLabel(): String {
    val host = accountUrl.removePrefix("https://").removePrefix("http://")
    val acc = host.substringBefore('.').ifBlank { label }
    return acc
}

private fun CardUiState.headerTitle(): String {
    // Prefer the detected camera identity from the scan (e.g. "Insta360 OneRS").
    plan?.buckets?.values?.firstOrNull { it?.brand != null }?.let { dev ->
        val brand = dev.brand!!.trim()
        val model = dev.model?.trim().orEmpty()
        val display = when {
            model.isEmpty() -> brand
            model.equals(brand, true) -> brand
            model.startsWith("$brand ", true) -> model        // model already contains brand
            model.startsWith(brand, true) -> model            // e.g. brand="Insta360", model="Insta360OneRS"
            else -> "$brand $model"
        }
        return display
    }
    // Otherwise fall back to USB device name; the volume serial (e.g. 0000-0000) is noise.
    device?.displayName?.let { if (it.isNotBlank()) return it }
    return rootLabel
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContainerSection(
    state: ContainerPickerState,
    enabled: Boolean,
    onInput: (String) -> Unit,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onCreate: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Container", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRefresh, enabled = enabled && !state.loading) {
                    if (state.loading) {
                        CircularProgressIndicator(modifier = Modifier.height(16.dp))
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "refresh containers")
                    }
                }
            }

            var expanded by remember { mutableStateOf(false) }
            val showMenu = expanded && state.suggestions.any { it != state.current.trim() }
            ExposedDropdownMenuBox(
                expanded = showMenu,
                onExpandedChange = { expanded = it },
            ) {
                TextField(
                    value = state.current,
                    onValueChange = { onInput(it) },
                    singleLine = true,
                    placeholder = { Text("既有或新建") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(showMenu) },
                    enabled = enabled,
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    state.suggestions.forEach { name ->
                        DropdownMenuItem(
                            text = {
                                val tag = when {
                                    name in state.remote -> "remote"
                                    name in state.history -> "recent"
                                    else -> ""
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(name, fontFamily = FontFamily.Monospace)
                                    if (tag.isNotEmpty()) {
                                        Text(tag, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            },
                            onClick = {
                                onSelect(name)
                                expanded = false
                            },
                        )
                    }
                }
            }

            val typed = state.current.trim()
            val typedIsNew = typed.isNotEmpty() && typed !in state.remote
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSelect(typed) },
                    enabled = enabled && typed.isNotEmpty() && typed in state.remote,
                ) { Text("使用") }
                OutlinedButton(
                    onClick = { onCreate(typed) },
                    enabled = enabled && typedIsNew && !state.loading,
                ) { Text("新建") }
            }

            state.refreshError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            state.notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CardsSection(
    cards: List<CardUiState>,
    onPickTree: (Int?, Uri) -> Unit,
    onStart: (Int?, Uri, StartMode) -> Unit,
    onReplan: (Int?, Uri) -> Unit,
    onPause: (Int?, Uri) -> Unit,
    onCancel: (Int?, Uri) -> Unit,
    buildPickerIntent: () -> android.content.Intent?,
) {
    Text("記憶卡", style = MaterialTheme.typography.titleMedium)
    if (cards.isEmpty()) {
        EmptyCardsHint(onPickTree)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cards.forEach { card ->
            CardItem(card, onPickTree, onStart, onReplan, onPause, onCancel, buildPickerIntent)
        }
        AddAnyTreeButton(onPickTree)
    }
}

@Composable
private fun EmptyCardsHint(onPickTree: (Int?, Uri) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("尚未偵測到記憶卡。")
            AddAnyTreeButton(onPickTree)
        }
    }
}

@Composable
private fun AddAnyTreeButton(onPickTree: (Int?, Uri) -> Unit) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) onPickTree(null, uri) }
    OutlinedButton(onClick = { launcher.launch(null) }) {
        Text("手動選擇來源…")
    }
}

@Composable
private fun CardItem(
    card: CardUiState,
    onPickTree: (Int?, Uri) -> Unit,
    onStart: (Int?, Uri, StartMode) -> Unit,
    onReplan: (Int?, Uri) -> Unit,
    onPause: (Int?, Uri) -> Unit,
    onCancel: (Int?, Uri) -> Unit,
    buildPickerIntent: () -> android.content.Intent?,
) {
    val fallbackLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) onPickTree(card.device?.deviceId, uri) }
    val seededLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        if (uri != null) onPickTree(card.device?.deviceId, uri)
    }

    fun launchPicker() {
        val intent = buildPickerIntent()
        if (intent != null) seededLauncher.launch(intent)
        else fallbackLauncher.launch(null)
    }

    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(card.headerTitle(), style = MaterialTheme.typography.titleSmall)
            card.device?.let {
                Text(
                    "USB %04x:%04x · serial=%s".format(
                        it.vendorId, it.productId, it.serial ?: "—",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (card.treeUri == null) {
                Text(
                    "請選擇此卡根目錄（只需一次）。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = ::launchPicker) {
                    Text("授權")
                }
                return@Column
            }
            if (card.planning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp))
                    Spacer(Modifier.padding(4.dp))
                    Text("掃描中…")
                }
                return@Column
            }
            card.planError?.let {
                Text("掃描失敗：$it", color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { onReplan(card.device?.deviceId, card.treeUri) }) {
                    Text("重試掃描")
                }
                return@Column
            }
            val plan = card.plan ?: return@Column
            Text("計畫", style = MaterialTheme.typography.titleSmall)
            Text(
                "${plan.fileCount} files · ${formatSize(plan.totalBytes)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            for ((key, summary) in plan.summaryByDevice()) {
                DeviceSummaryRow(key, summary)
            }
            if (plan.conflicts.isNotEmpty()) {
                Text("⚠ ${plan.conflicts.size} blob-name conflict(s)",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (plan.oversized.isNotEmpty()) {
                Text("⚠ ${plan.oversized.size} oversized blob name(s)",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            ProgressRow(card.progress, card.fileStatus.values.toList())
            val running = card.progress?.state == UploadProgress.State.RUNNING
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) {
                    OutlinedButton(onClick = { onPause(card.device?.deviceId, card.treeUri) }) {
                        Text("暫停")
                    }
                    OutlinedButton(
                        onClick = { onCancel(card.device?.deviceId, card.treeUri) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("取消") }
                } else {
                    Button(
                        onClick = { onStart(card.device?.deviceId, card.treeUri, StartMode.RESUME) },
                    ) { Text(if (card.progress == null) "開始上傳" else "繼續上傳") }
                    OutlinedButton(
                        onClick = { onStart(card.device?.deviceId, card.treeUri, StartMode.RESTART) },
                    ) { Text("從頭開始") }
                }
            }
        }
    }
}

@Composable
private fun DeviceSummaryRow(key: String, s: DeviceSummary) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "• $key",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${s.fileCount} · ${formatSize(s.totalBytes)}",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun ProgressRow(progress: UploadProgress?, fileStates: List<FileUploadStatus>) {
    if (progress == null) return
    val ratio = if (progress.totalBytes > 0) progress.doneBytes.toFloat() / progress.totalBytes else 0f
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
        val failed = fileStates.count { it == FileUploadStatus.FAILED }
        val done = fileStates.count { it == FileUploadStatus.DONE }
        Text(
            "${done}/${progress.totalFiles} files · ${formatSize(progress.doneBytes)} / ${formatSize(progress.totalBytes)} " +
                "· ${formatRate(progress.bytesPerSecond)} · workers=${progress.currentParallelism}" +
                if (failed > 0) " · failed=$failed" else "",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("state: ${progress.state}", style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatSize(b: Long): String {
    if (b < 1024) return "$b B"
    var d = b.toDouble()
    for (u in listOf("KB", "MB", "GB", "TB")) {
        d /= 1024
        if (d < 1024) return "%.1f %s".format(d, u)
    }
    return "%.1f PB".format(d)
}

private fun formatRate(bps: Double): String {
    if (bps <= 0) return "—"
    return formatSize(bps.toLong()) + "/s"
}
