package net.dinowang.actioncameradrain.data.storage

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * Persists previously-granted SAF tree URIs keyed by a stable identifier of the
 * USB card device (vendor:product:serial). Subsequent attaches of the same card
 * can skip the picker entirely as long as
 * [android.content.ContentResolver.getPersistedUriPermissions] still confirms
 * the grant.
 */
class GrantedTreeStore(context: Context) {

    private val ctx = context.applicationContext
    private val Context.dataStore by preferencesDataStore(DATASTORE_NAME)

    suspend fun get(usbKey: String): Uri? {
        val raw = ctx.dataStore.data.first()[keyFor(usbKey)] ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    suspend fun put(usbKey: String, uri: Uri) {
        ctx.dataStore.edit { it[keyFor(usbKey)] = uri.toString() }
    }

    suspend fun clear(usbKey: String) {
        ctx.dataStore.edit { it.remove(keyFor(usbKey)) }
    }

    private fun keyFor(usbKey: String) = stringPreferencesKey("tree:$usbKey")

    companion object {
        private const val DATASTORE_NAME = "granted_trees"
    }
}
