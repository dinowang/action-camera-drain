package net.dinowang.actioncameradrain.ui

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.dinowang.actioncameradrain.background.UploadJobScheduler
import net.dinowang.actioncameradrain.data.config.ConfigRepository
import net.dinowang.actioncameradrain.data.config.UploadConfig
import net.dinowang.actioncameradrain.data.storage.AzureBlobClient
import net.dinowang.actioncameradrain.data.storage.ContainerSelectionRepository
import net.dinowang.actioncameradrain.data.storage.GrantedTreeStore
import net.dinowang.actioncameradrain.data.storage.TransferStore
import net.dinowang.actioncameradrain.domain.filing.IngestPlan
import net.dinowang.actioncameradrain.domain.filing.IngestPlanner
import net.dinowang.actioncameradrain.domain.filing.SafMediaSource
import net.dinowang.actioncameradrain.domain.upload.NetworkPolicy
import net.dinowang.actioncameradrain.domain.upload.StartMode
import net.dinowang.actioncameradrain.domain.upload.TransferRecord
import net.dinowang.actioncameradrain.domain.upload.TransferState
import net.dinowang.actioncameradrain.domain.upload.UploadProgress
import net.dinowang.actioncameradrain.domain.usb.RemovableVolumeProvider
import net.dinowang.actioncameradrain.domain.usb.TreeAccessChecker
import net.dinowang.actioncameradrain.domain.usb.UsbCardDevice
import net.dinowang.actioncameradrain.domain.usb.UsbCardWatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class CardUiState(
    val device: UsbCardDevice?,
    val treeUri: Uri?,
    val rootLabel: String,
    val plan: IngestPlan? = null,
    val planning: Boolean = false,
    val planError: String? = null,
    val progress: UploadProgress? = null,
    val transfer: TransferRecord? = null,
) {
    val id: String get() = treeUri?.toString() ?: device?.deviceId?.toString() ?: "unknown"
}

data class ContainerPickerState(
    val current: String = "",
    val remote: List<String> = emptyList(),
    val history: List<String> = emptyList(),
    val loading: Boolean = false,
    val refreshError: String? = null,
    val notice: String? = null,
) {
    val suggestions: List<String>
        get() = remote
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val configRepo = ConfigRepository(application)
    private val usbWatcher = UsbCardWatcher(application)
    private val containerRepo = ContainerSelectionRepository(application)
    private val grantedTrees = GrantedTreeStore(application)
    private val transferStore = TransferStore(application)
    private val uploadScheduler = UploadJobScheduler(application)
    private val volumeProvider = RemovableVolumeProvider(application)
    private val treeAccess = TreeAccessChecker(application)
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    val config: StateFlow<UploadConfig?> = configRepo.current
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val attachedUsb: StateFlow<List<UsbCardDevice>> = usbWatcher.attached

    private val _cards = MutableStateFlow<List<CardUiState>>(emptyList())
    val cards: StateFlow<List<CardUiState>> = _cards.asStateFlow()

    private val _container = MutableStateFlow(ContainerPickerState())
    val container: StateFlow<ContainerPickerState> = _container.asStateFlow()

    val networkPolicy: StateFlow<NetworkPolicy> = transferStore.networkPolicy
        .stateIn(viewModelScope, SharingStarted.Eagerly, NetworkPolicy.UNMETERED)

    private var transferSnapshot: List<TransferRecord> = emptyList()
    private val restoredPlanRequests = mutableSetOf<String>()

    init {
        viewModelScope.launch { uploadScheduler.reconcileOrphanedTransfers() }
        viewModelScope.launch {
            configRepo.load()
            // Seed picker once we know the active config.
            val cfg = config.first { it != null } as? UploadConfig.AzureBlob ?: return@launch
            val last = containerRepo.getLast(cfg.id)
            val history = containerRepo.history(cfg.id)
            _container.value = _container.value.copy(
                current = last ?: "",
                history = history,
            )
            refreshRemoteContainers()
        }
        usbWatcher.bind()
        viewModelScope.launch {
            usbWatcher.attached.collect { reconcileFromUsb(it) }
        }
        viewModelScope.launch {
            transferStore.records.collectLatest { records ->
                transferSnapshot = records
                val decorated = _cards.value.map(::attachLatestTransfer)
                val knownUris = decorated.mapNotNull { it.treeUri?.toString() }.toSet()
                val restored = records
                    .filter {
                        it.state !in setOf(
                            TransferState.COMPLETED,
                            TransferState.CANCELLED,
                        ) && it.treeUri !in knownUris
                    }
                    .distinctBy { it.treeUri }
                    .map { transfer ->
                        CardUiState(
                            device = null,
                            treeUri = Uri.parse(transfer.treeUri),
                            rootLabel = transfer.rootLabel,
                            progress = transfer.toUploadProgress(),
                            transfer = transfer,
                        )
                    }
                _cards.value = decorated + restored
                for (card in restored) {
                    val uri = card.treeUri ?: continue
                    if (restoredPlanRequests.add(uri.toString())) {
                        planCard(card.device?.deviceId, uri)
                    }
                }
            }
        }
        viewModelScope.launch { pollCardAccessibility() }
    }

    /**
     * The USB receiver only fires when the entire reader is unplugged. If the
     * user pulls the memory card out of the reader (reader stays attached),
     * the StorageVolume gets unmounted silently. Poll DocumentFile access to
     * notice and drop the card.
     */
    private suspend fun pollCardAccessibility() {
        while (true) {
            kotlinx.coroutines.delay(2_000)
            val snapshot = _cards.value
            val keep = snapshot.filter { card ->
                val tree = card.treeUri ?: return@filter true
                val transferIsActionable = card.transfer?.state !in setOf(
                    null,
                    TransferState.COMPLETED,
                    TransferState.CANCELLED,
                )
                transferIsActionable || treeAccess.isAccessible(tree)
            }
            if (keep.size != snapshot.size) {
                _cards.value = keep
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        usbWatcher.unbind()
    }

    // ---- Container picker ----------------------------------------------------

    fun onContainerInputChanged(text: String) {
        _container.value = _container.value.copy(current = text.trim(), notice = null)
        _cards.value = _cards.value.map(::attachLatestTransfer)
    }

    /** User explicitly selected an existing container (from dropdown). */
    fun selectContainer(name: String) {
        val cfg = config.value as? UploadConfig.AzureBlob ?: return
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        _container.value = _container.value.copy(current = trimmed, notice = null)
        _cards.value = _cards.value.map(::attachLatestTransfer)
        viewModelScope.launch {
            containerRepo.setLast(cfg.id, trimmed)
            _container.value = _container.value.copy(history = containerRepo.history(cfg.id))
        }
        // Selecting a different container invalidates the per-card plan/progress.
        invalidatePlans()
    }

    /** User typed a fresh name and hit the "create" button. PUT it on Azure. */
    fun createContainer(name: String) {
        val cfg = config.value as? UploadConfig.AzureBlob ?: return
        val trimmed = name.trim()
        if (!isLikelyValidContainerName(trimmed)) {
            _container.value = _container.value.copy(
                notice = "Name must be 3-63 chars, lowercase alphanumeric or '-', not starting/ending with '-'",
            )
            return
        }
        viewModelScope.launch {
            _container.value = _container.value.copy(loading = true, notice = null)
            val result = withContext(Dispatchers.IO) {
                runCatching { AzureBlobClient(cfg, http).createContainer(trimmed) }
            }
            result.fold(
                onSuccess = {
                    containerRepo.setLast(cfg.id, trimmed)
                    _container.value = _container.value.copy(
                        current = trimmed,
                        history = containerRepo.history(cfg.id),
                        loading = false,
                        notice = "Created: $trimmed",
                    )
                    refreshRemoteContainers()
                    invalidatePlans()
                },
                onFailure = { e ->
                    _container.value = _container.value.copy(
                        loading = false,
                        notice = "Create failed: ${e.message ?: e.javaClass.simpleName}",
                    )
                },
            )
        }
    }

    fun refreshRemoteContainers() {
        val cfg = config.value as? UploadConfig.AzureBlob ?: return
        viewModelScope.launch {
            _container.value = _container.value.copy(loading = true, refreshError = null)
            val result = withContext(Dispatchers.IO) {
                runCatching { AzureBlobClient(cfg, http).listContainers() }
            }
            result.fold(
                onSuccess = { list ->
                    _container.value = _container.value.copy(
                        remote = list,
                        loading = false,
                        refreshError = null,
                    )
                },
                onFailure = { e ->
                    _container.value = _container.value.copy(
                        remote = emptyList(),
                        loading = false,
                        refreshError = "List failed (SAS may need srt=s & sp=l): ${e.message ?: e.javaClass.simpleName}",
                    )
                },
            )
        }
    }

    /** Drop existing plans so they get rebuilt for the new container (blob paths are container-relative). */
    private fun invalidatePlans() {
        _cards.value = _cards.value.map { c ->
            c.copy(plan = null, progress = c.transfer?.toUploadProgress())
        }
        // Auto-replan for any card with a tree already attached.
        for (c in _cards.value) {
            val tree = c.treeUri ?: continue
            planCard(c.device?.deviceId, tree)
        }
    }

    // ---- USB / card lifecycle ------------------------------------------------

    private fun reconcileFromUsb(devices: List<UsbCardDevice>) {
        val current = _cards.value
        val survived = current.mapNotNull { card ->
            val stillAttached = devices.any { it.deviceId == card.device?.deviceId }
            if (!stillAttached && card.device != null) {
                null
            } else card
        }
        val existingDeviceIds = survived.mapNotNull { it.device?.deviceId }.toSet()
        val additions = devices
            .filter { it.deviceId !in existingDeviceIds }
            .map { CardUiState(device = it, treeUri = null, rootLabel = it.displayName) }
        _cards.value = (survived + additions).map(::attachLatestTransfer)

        // For each new card try to auto-attach a previously-granted tree, or at
        // least pre-seed the picker to the matching StorageVolume.
        for (dev in additions.mapNotNull { it.device }) {
            viewModelScope.launch { resolveCardSource(dev) }
        }
    }

    private suspend fun resolveCardSource(dev: UsbCardDevice) {
        val ctx = getApplication<Application>()
        val key = usbKey(dev)
        val saved = grantedTrees.get(key)
        if (saved != null && isPermissionStillPersisted(ctx, saved) &&
            treeAccess.isAccessible(saved, requireNonEmpty = true)
        ) {
            attachTreeUri(dev.deviceId, saved)
        }
        // Don't pre-compute the picker intent here — vold may not have mounted
        // the volume yet at USB-attach time. The UI calls [buildPickerIntent]
        // at click time instead.
    }

    /** Fresh look-up of a SAF picker intent pre-seeded to a removable volume. */
    fun buildPickerIntent(): android.content.Intent? =
        runCatching { volumeProvider.pickerIntentForRemovable() }.getOrNull()

    private fun isPermissionStillPersisted(
        ctx: android.content.Context,
        uri: Uri,
    ): Boolean = ctx.contentResolver.persistedUriPermissions.any { it.uri == uri }

    private fun updateCardByDevice(deviceId: Int, transform: (CardUiState) -> CardUiState) {
        _cards.value = _cards.value.map { c ->
            if (c.device?.deviceId == deviceId) transform(c) else c
        }
    }

    fun attachTreeUri(deviceId: Int?, treeUri: Uri) {
        val ctx = getApplication<Application>()
        val flags = (android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching { ctx.contentResolver.takePersistableUriPermission(treeUri, flags) }

        val docRoot = DocumentFile.fromTreeUri(ctx, treeUri) ?: return
        val rootLabel = docRoot.name ?: "card"

        _cards.value = _cards.value.map { c ->
            if ((deviceId != null && c.device?.deviceId == deviceId) ||
                (deviceId == null && c.device == null && c.treeUri == null)
            ) {
                c.copy(treeUri = treeUri, rootLabel = rootLabel)
            } else c
        }.let { list ->
            if (deviceId == null && list.none { it.treeUri == treeUri }) {
                list + CardUiState(device = null, treeUri = treeUri, rootLabel = rootLabel)
            } else list
        }.map(::attachLatestTransfer)
            .groupBy { it.treeUri?.toString() ?: "device:${it.device?.deviceId}" }
            .map { (_, matches) -> matches.firstOrNull { it.device != null } ?: matches.first() }

        // Remember the grant so subsequent re-attaches are silent.
        if (deviceId != null) {
            val dev = _cards.value.firstOrNull { it.device?.deviceId == deviceId }?.device
            if (dev != null) {
                viewModelScope.launch { grantedTrees.put(usbKey(dev), treeUri) }
            }
        }

        planCard(deviceId, treeUri)
    }

    fun planCard(deviceId: Int?, treeUri: Uri) {
        viewModelScope.launch {
            updateCard(deviceId, treeUri) { it.copy(planning = true, planError = null) }
            val ctx = getApplication<Application>()
            config.first { it is UploadConfig.AzureBlob }
            val plan = withContext(Dispatchers.IO) {
                runCatching {
                    val root = DocumentFile.fromTreeUri(ctx, treeUri)
                        ?: error("Cannot open tree URI.")
                    val src = SafMediaSource(ctx.contentResolver, root)
                    IngestPlanner().plan(src)
                }
            }
            plan.fold(
                onSuccess = { p ->
                    updateCard(deviceId, treeUri) {
                        it.copy(plan = p, planning = false, planError = null)
                    }
                },
                onFailure = { e ->
                    updateCard(deviceId, treeUri) {
                        it.copy(planning = false, planError = e.message ?: "Plan failed")
                    }
                },
            )
        }
    }

    fun startUpload(deviceId: Int?, treeUri: Uri, mode: StartMode) {
        val cfg = config.value as? UploadConfig.AzureBlob ?: return
        val containerName = _container.value.current.trim().ifBlank { cfg.container }
        val card = _cards.value.firstOrNull { it.matches(deviceId, treeUri) } ?: return
        val plan = card.plan ?: return
        viewModelScope.launch {
            containerRepo.setLast(cfg.id, containerName)
            uploadScheduler.schedule(
                configId = cfg.id,
                treeUri = treeUri.toString(),
                container = containerName,
                rootLabel = card.rootLabel,
                deviceId = deviceId,
                mode = mode,
                networkPolicy = networkPolicy.value,
                totalFiles = plan.fileCount,
                totalBytes = plan.totalBytes,
            ).onFailure { error ->
                updateCardSilent(deviceId, treeUri) {
                    it.copy(planError = error.message ?: "Unable to schedule upload.")
                }
            }
        }
    }

    fun pauseUpload(deviceId: Int?, treeUri: Uri) {
        val card = _cards.value.firstOrNull { it.matches(deviceId, treeUri) } ?: return
        val transferId = card.transfer?.id ?: return
        viewModelScope.launch { uploadScheduler.pause(transferId) }
    }

    fun cancelUpload(deviceId: Int?, treeUri: Uri) {
        val card = _cards.value.firstOrNull { it.matches(deviceId, treeUri) } ?: return
        val transfer = card.transfer ?: return
        viewModelScope.launch { uploadScheduler.cancel(transfer.id) }
    }

    fun setNetworkPolicy(policy: NetworkPolicy) {
        viewModelScope.launch { transferStore.setNetworkPolicy(policy) }
    }

    private fun updateCard(deviceId: Int?, treeUri: Uri, transform: (CardUiState) -> CardUiState) {
        _cards.value = _cards.value.map { c ->
            if (c.matches(deviceId, treeUri)) transform(c) else c
        }
    }

    private fun updateCardSilent(deviceId: Int?, treeUri: Uri, transform: (CardUiState) -> CardUiState) {
        updateCard(deviceId, treeUri, transform)
    }

    private fun CardUiState.matches(deviceId: Int?, treeUri: Uri): Boolean =
        this.treeUri == treeUri || (deviceId != null && this.device?.deviceId == deviceId)

    private fun attachLatestTransfer(card: CardUiState): CardUiState {
        val uri = card.treeUri?.toString() ?: return card.copy(transfer = null, progress = null)
        val selectedContainer = _container.value.current.trim()
        val transfer = transferSnapshot
            .asSequence()
            .filter { it.treeUri == uri }
            .sortedWith(
                compareByDescending<TransferRecord> { it.isActive }
                    .thenByDescending { it.container == selectedContainer }
                    .thenByDescending { it.updatedAtMillis },
            )
            .firstOrNull()
        return card.copy(transfer = transfer, progress = transfer?.toUploadProgress())
    }

    fun forgetGrant(deviceId: Int?) {
        val dev = _cards.value.firstOrNull { it.device?.deviceId == deviceId }?.device ?: return
        viewModelScope.launch { grantedTrees.clear(usbKey(dev)) }
        _cards.value = _cards.value.map { c ->
            if (c.device?.deviceId == deviceId) c.copy(treeUri = null, plan = null) else c
        }
    }

    companion object {
        // https://learn.microsoft.com/rest/api/storageservices/naming-and-referencing-containers--blobs--and-metadata
        private val CONTAINER_NAME_REGEX = Regex("^[a-z0-9](?:[a-z0-9]|-(?=[a-z0-9])){1,61}[a-z0-9]$")
        fun isLikelyValidContainerName(name: String): Boolean = CONTAINER_NAME_REGEX.matches(name)

        private fun usbKey(dev: UsbCardDevice): String =
            "%04x:%04x:%s".format(dev.vendorId, dev.productId, dev.serial ?: "-")
    }
}
