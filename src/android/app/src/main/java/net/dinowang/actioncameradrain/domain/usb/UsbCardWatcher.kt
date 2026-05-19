package net.dinowang.actioncameradrain.domain.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Represents a USB device the user might want to drain — typically a USB card
 * reader or an external SSD with a memory card mounted on it.
 *
 * The Android USB stack doesn't expose mounted volume paths directly; the
 * standard flow on Android 11+ is to ask the user to pick the volume via SAF
 * (`ACTION_OPEN_DOCUMENT_TREE`). [UsbCardWatcher] simply tells us *that* a
 * device is attached; pairing with a tree URI is the UI's responsibility.
 */
data class UsbCardDevice(
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val productName: String?,
    val manufacturerName: String?,
    val serial: String?,
) {
    val displayName: String
        get() = listOfNotNull(manufacturerName, productName)
            .joinToString(" ")
            .ifBlank { "USB device ${"%04x".format(vendorId)}:${"%04x".format(productId)}" }
}

/**
 * Watches for USB attach / detach events and exposes the currently-attached set
 * as a [StateFlow]. Register / unregister with [bind] tied to the host's lifecycle.
 */
class UsbCardWatcher(private val context: Context) {

    private val _attached = MutableStateFlow<List<UsbCardDevice>>(emptyList())
    val attached: StateFlow<List<UsbCardDevice>> = _attached.asStateFlow()

    private val manager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val device: UsbDevice? = intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                ?: refreshList().let { null }
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (device != null) addDevice(device)
                    else refreshList()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device != null) removeDevice(device.deviceId)
                    else refreshList()
                }
            }
        }
    }

    fun bind() {
        refreshList()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    fun unbind() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun refreshList() {
        _attached.value = manager.deviceList.values.map(::toCardDevice)
    }

    private fun addDevice(device: UsbDevice) {
        _attached.value = (_attached.value + toCardDevice(device)).distinctBy { it.deviceId }
    }

    private fun removeDevice(deviceId: Int) {
        _attached.value = _attached.value.filter { it.deviceId != deviceId }
    }

    private fun toCardDevice(d: UsbDevice): UsbCardDevice = UsbCardDevice(
        deviceId = d.deviceId,
        vendorId = d.vendorId,
        productId = d.productId,
        productName = runCatching { d.productName }.getOrNull(),
        manufacturerName = runCatching { d.manufacturerName }.getOrNull(),
        serial = runCatching { d.serialNumber }.getOrNull(),
    )

    companion object {
        private const val TAG = "UsbCardWatcher"
    }
}
