package net.dinowang.actioncameradrain.data.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class UploadAuth {
    @Serializable
    @SerialName("sas")
    data class Sas(val sasToken: String) : UploadAuth()

    @Serializable
    @SerialName("connectionString")
    data class ConnectionString(val connectionString: String) : UploadAuth()
}

@Serializable
sealed class UploadConfig {
    abstract val id: String
    abstract val label: String

    @Serializable
    @SerialName("AzureBlob")
    data class AzureBlob(
        override val id: String,
        override val label: String,
        val accountUrl: String,
        val container: String,
        val auth: UploadAuth,
    ) : UploadConfig()
}

/** Mask a secret for UI display. Keeps the first/last few chars, ellipses the middle. */
fun maskSecret(value: String, head: Int = 4, tail: Int = 4): String {
    if (value.length <= head + tail + 1) return "•".repeat(value.length.coerceAtLeast(8))
    return value.substring(0, head) + "…" + "•".repeat(8) + "…" + value.substring(value.length - tail)
}

fun UploadConfig.AzureBlob.authSummary(): String = when (val a = auth) {
    is UploadAuth.Sas -> "SAS " + maskSecret(a.sasToken)
    is UploadAuth.ConnectionString -> "ConnectionString " + maskSecret(a.connectionString)
}
