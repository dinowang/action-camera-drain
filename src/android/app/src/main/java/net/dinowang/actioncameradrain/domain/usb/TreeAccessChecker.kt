package net.dinowang.actioncameradrain.domain.usb

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

class TreeAccessChecker(context: Context) {

    private val appContext = context.applicationContext

    fun isAccessible(uri: Uri, requireNonEmpty: Boolean = false): Boolean {
        val root = runCatching { DocumentFile.fromTreeUri(appContext, uri) }.getOrNull()
            ?: return false
        if (!runCatching { root.exists() && root.canRead() }.getOrDefault(false)) return false
        if (!requireNonEmpty) return true
        return runCatching { root.listFiles().isNotEmpty() }.getOrDefault(false)
    }
}
