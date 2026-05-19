package net.dinowang.actioncameradrain.data.config

import android.content.Context
import androidx.annotation.RawRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import net.dinowang.actioncameradrain.R

/**
 * Loads the default upload config from res/raw. The real config file
 * (upload_config.json) is git-ignored; if it's missing we fall back to the
 * checked-in sample so the build is never broken.
 */
class ConfigRepository(private val context: Context) {

    private val _current = MutableStateFlow<UploadConfig?>(null)
    val current: Flow<UploadConfig?> = _current.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        val json = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }
        val raw = readRawOrNull(R.raw::class.java, "upload_config")
            ?: readRaw(R.raw.upload_config_sample)
        _current.value = json.decodeFromString(UploadConfig.serializer(), raw)
    }

    private fun readRaw(@RawRes resId: Int): String =
        context.resources.openRawResource(resId).bufferedReader().use { it.readText() }

    private fun readRawOrNull(rawClass: Class<*>, name: String): String? {
        val field = runCatching { rawClass.getField(name) }.getOrNull() ?: return null
        val id = field.getInt(null)
        return runCatching { readRaw(id) }.getOrNull()
    }
}
