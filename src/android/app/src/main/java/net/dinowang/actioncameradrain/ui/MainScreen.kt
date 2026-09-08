package net.dinowang.actioncameradrain.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.dinowang.actioncameradrain.data.config.UploadConfig
import net.dinowang.actioncameradrain.domain.filing.DeviceSummary
import net.dinowang.actioncameradrain.domain.upload.NetworkPolicy
import net.dinowang.actioncameradrain.domain.upload.StartMode
import net.dinowang.actioncameradrain.domain.upload.TransferState
import net.dinowang.actioncameradrain.domain.upload.UploadProgress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val config by vm.config.collectAsState()
    val cards by vm.cards.collectAsState()
    val container by vm.container.collectAsState()
    val networkPolicy by vm.networkPolicy.collectAsState()
    val context = LocalContext.current
    var pendingDeviceId by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingTreeUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingStartMode by rememberSaveable { mutableStateOf<String?>(null) }
    var notificationPermissionError by rememberSaveable { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val uri = pendingTreeUri?.let(Uri::parse)
        val mode = pendingStartMode?.let { runCatching { StartMode.valueOf(it) }.getOrNull() }
        pendingTreeUri = null
        pendingStartMode = null
        if (granted && uri != null && mode != null) {
            vm.startUpload(pendingDeviceId, uri, mode)
        } else if (!granted) {
            notificationPermissionError = true
        }
    }

    fun startWithNotificationPermission(deviceId: Int?, uri: Uri, mode: StartMode) {
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            vm.startUpload(deviceId, uri, mode)
        } else {
            pendingDeviceId = deviceId
            pendingTreeUri = uri.toString()
            pendingStartMode = mode.name
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
            RemoteSection(
                config = config,
                containerState = container,
                onInput = vm::onContainerInputChanged,
                onSelect = vm::selectContainer,
                onRefresh = vm::refreshRemoteContainers,
                onCreate = vm::createContainer,
                networkPolicy = networkPolicy,
                onNetworkPolicyChange = vm::setNetworkPolicy,
            )
            if (notificationPermissionError) {
                Text(
                    "Notification permission is required for background uploads.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            CardsSection(
                cards = cards,
                onPickTree = { deviceId, uri -> vm.attachTreeUri(deviceId, uri) },
                onStart = ::startWithNotificationPermission,
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
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Destination: ", style = MaterialTheme.typography.bodyMedium)
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
private fun RemoteSection(
    config: UploadConfig?,
    containerState: ContainerPickerState,
    onInput: (String) -> Unit,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onCreate: (String) -> Unit,
    networkPolicy: NetworkPolicy,
    onNetworkPolicyChange: (NetworkPolicy) -> Unit,
) {
    val enabled = config is UploadConfig.AzureBlob
    Text("Remote", style = MaterialTheme.typography.titleMedium)
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ConfigSection(config)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Container", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRefresh, enabled = enabled && !containerState.loading) {
                    if (containerState.loading) {
                        CircularProgressIndicator(modifier = Modifier.height(16.dp))
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "refresh containers")
                    }
                }
            }

            var expanded by remember { mutableStateOf(false) }
            val showMenu = expanded && containerState.suggestions.any { it != containerState.current.trim() }
            ExposedDropdownMenuBox(
                expanded = showMenu,
                onExpandedChange = { expanded = it },
            ) {
                TextField(
                    value = containerState.current,
                    onValueChange = { onInput(it) },
                    singleLine = true,
                    placeholder = { Text("existing or new") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(showMenu) },
                    enabled = enabled,
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    containerState.suggestions.forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name, fontFamily = FontFamily.Monospace) },
                            onClick = {
                                onSelect(name)
                                expanded = false
                            },
                        )
                    }
                }
            }

            val typed = containerState.current.trim()
            val typedIsNew = typed.isNotEmpty() && typed !in containerState.remote
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSelect(typed) },
                    enabled = enabled && typed.isNotEmpty() && typed in containerState.remote,
                ) { Text("Use") }
                OutlinedButton(
                    onClick = { onCreate(typed) },
                    enabled = enabled && typedIsNew && !containerState.loading,
                ) { Text("Create") }
            }

            Text("Network", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = networkPolicy == NetworkPolicy.UNMETERED,
                    onClick = { onNetworkPolicyChange(NetworkPolicy.UNMETERED) },
                    label = { Text("Wi-Fi only") },
                )
                FilterChip(
                    selected = networkPolicy == NetworkPolicy.ANY,
                    onClick = { onNetworkPolicyChange(NetworkPolicy.ANY) },
                    label = { Text("Any network") },
                )
            }
            Text(
                "Metered networks are capped at 10 Mbps so other apps retain bandwidth.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            containerState.refreshError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            containerState.notice?.let {
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
    Text("Memory Card", style = MaterialTheme.typography.titleMedium)
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
            Text("No memory card detected.")
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
        Text("Pick source…")
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
                    "Grant access to this card's root (one-time).",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = ::launchPicker) {
                    Text("Grant")
                }
                return@Column
            }
            if (card.planning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp))
                    Spacer(Modifier.padding(4.dp))
                    Text("Scanning…")
                }
                return@Column
            }
            card.planError?.let {
                Text("Scan failed: $it", color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { onReplan(card.device?.deviceId, card.treeUri) }) {
                    Text("Retry")
                }
                if (card.transfer == null) return@Column
            }
            val plan = card.plan
            if (plan != null) {
                Text("Plan", style = MaterialTheme.typography.titleSmall)
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
            } else if (card.transfer == null) {
                return@Column
            } else {
                Text("Restoring upload plan…", style = MaterialTheme.typography.bodySmall)
            }
            ProgressRow(card.progress, card.transfer?.state)
            card.transfer?.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (card.transfer.state == TransferState.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            val running = card.transfer?.isActive == true
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) {
                    OutlinedButton(onClick = { onPause(card.device?.deviceId, card.treeUri) }) {
                        Text("Pause")
                    }
                    OutlinedButton(
                        onClick = { onCancel(card.device?.deviceId, card.treeUri) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("Cancel") }
                } else {
                    Button(
                        onClick = { onStart(card.device?.deviceId, card.treeUri, StartMode.RESUME) },
                        enabled = plan != null,
                    ) { Text(if (card.progress == null) "Start" else "Resume") }
                    OutlinedButton(
                        onClick = { onStart(card.device?.deviceId, card.treeUri, StartMode.RESTART) },
                        enabled = plan != null,
                    ) { Text("Restart") }
                }
            }
            if (!running && card.transfer?.state in setOf(
                    TransferState.PAUSED,
                    TransferState.FAILED,
                )
            ) {
                OutlinedButton(
                    onClick = { onCancel(card.device?.deviceId, card.treeUri) },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Cancel and clean remote")
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
private fun ProgressRow(progress: UploadProgress?, transferState: TransferState?) {
    if (progress == null) return
    val ratio = if (progress.totalBytes > 0) progress.doneBytes.toFloat() / progress.totalBytes else 0f
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
        val failed = progress.failedFiles
        val done = progress.doneFiles
        Text(
            "${done}/${progress.totalFiles} files · ${formatSize(progress.doneBytes)} / ${formatSize(progress.totalBytes)} " +
                "· ${formatRate(progress.bytesPerSecond)} · workers=${progress.currentParallelism}" +
                if (failed > 0) " · failed=$failed" else "",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("state: ${transferState ?: progress.state}", style = MaterialTheme.typography.bodySmall)
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
