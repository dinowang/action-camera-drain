package net.dinowang.actioncameradrain.domain.usb

import android.content.Context
import android.content.Intent
import android.os.storage.StorageManager
import android.os.storage.StorageVolume

/**
 * Helpers around [StorageManager] for picking the right intent to launch the
 * SAF picker for a removable USB-mounted volume, so the user doesn't have to
 * navigate the picker themselves.
 *
 * Reference:
 *   https://developer.android.com/reference/android/os/storage/StorageVolume#createOpenDocumentTreeIntent()
 */
class RemovableVolumeProvider(context: Context) {

    private val storage = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager

    /** All currently-mounted volumes that look like a removable USB-mounted card. */
    fun removableVolumes(): List<StorageVolume> =
        storage.storageVolumes.filter { it.isRemovable && !it.isPrimary }

    /**
     * Returns an intent that opens the SAF picker pre-seeded at a removable
     * volume's root, if exactly one removable volume is currently mounted.
     * Otherwise returns null and the caller should fall back to a plain
     * [androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree] launch.
     */
    fun pickerIntentForRemovable(): Intent? {
        val candidates = removableVolumes()
        if (candidates.size != 1) return null
        return candidates.single().createOpenDocumentTreeIntent()
    }
}
