package net.dinowang.actioncameradrain.data.storage

import net.dinowang.actioncameradrain.data.config.UploadAuth
import net.dinowang.actioncameradrain.data.config.UploadConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.Base64
import java.util.regex.Pattern

/**
 * Minimal Azure Blob REST client. Implements just what UploadEngine + UI need:
 *   - listContainers()                     (GET {account}/?comp=list)
 *   - createContainer(name)                (PUT {account}/{name}?restype=container)
 *   - putBlock(container, blobName, ...)
 *   - putBlockList(container, blobName, ...)
 *   - deleteBlob(container, blobName)
 *
 * Container is *not* part of the client identity — it is supplied per call so
 * the UI can let the user pick / create a container at runtime without
 * reconstructing this client.
 *
 * REST API reference:
 *   https://learn.microsoft.com/rest/api/storageservices/list-containers2
 *   https://learn.microsoft.com/rest/api/storageservices/create-container
 *   https://learn.microsoft.com/rest/api/storageservices/put-block
 *   https://learn.microsoft.com/rest/api/storageservices/put-block-list
 *   https://learn.microsoft.com/rest/api/storageservices/delete-blob
 */
class AzureBlobClient(
    private val config: UploadConfig.AzureBlob,
    private val http: OkHttpClient,
) {
    private val accountUrl: HttpUrl = config.accountUrl.trimEnd('/').toHttpUrl()

    private val sasQuery: String? = when (val a = config.auth) {
        is UploadAuth.Sas -> a.sasToken.trimStart('?')
        is UploadAuth.ConnectionString -> extractSasFromConnectionString(a.connectionString)
    }

    init {
        require(!sasQuery.isNullOrBlank()) {
            "AzureBlobClient currently requires a SAS token (either directly or embedded in the connection string)."
        }
    }

    /** Default container baked into config; UI may override. */
    val defaultContainer: String get() = config.container

    fun blobUrl(container: String, blobName: String): HttpUrl {
        val builder = accountUrl.newBuilder().addPathSegment(container)
        for (seg in blobName.split('/')) builder.addPathSegment(seg)
        return appendSas(builder).build()
    }

    private fun appendSas(builder: HttpUrl.Builder): HttpUrl.Builder {
        val query = sasQuery!!
        query.split('&').forEach { kv ->
            if (kv.isEmpty()) return@forEach
            val eq = kv.indexOf('=')
            if (eq < 0) builder.addEncodedQueryParameter(kv, null)
            else builder.addEncodedQueryParameter(kv.substring(0, eq), kv.substring(eq + 1))
        }
        return builder
    }

    /**
     * List containers visible to the SAS. Requires an account-level SAS with
     * service+list permissions (`srt=s` & `sp=l`); on insufficient perms
     * the call throws [AzureBlobException].
     */
    fun listContainers(): List<String> {
        val urlB = accountUrl.newBuilder()
            .addEncodedQueryParameter("comp", "list")
        appendSas(urlB)
        val req = Request.Builder().url(urlB.build()).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = runCatching { resp.body?.string()?.take(512) }.getOrNull()
                throw AzureBlobException("ListContainers failed: ${resp.code} ${resp.message} ${body.orEmpty()}")
            }
            val xml = resp.body?.string().orEmpty()
            return CONTAINER_NAME_REGEX.findAll(xml).map { it.groupValues[1] }.toList()
        }
    }

    /** Create a container. 201 Created on success; tolerates 409 (already exists). */
    fun createContainer(name: String) {
        val urlB = accountUrl.newBuilder().addPathSegment(name)
            .addEncodedQueryParameter("restype", "container")
        appendSas(urlB)
        val req = Request.Builder().url(urlB.build())
            .put(ByteArray(0).toRequestBody()).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 409) {
                val body = runCatching { resp.body?.string()?.take(512) }.getOrNull()
                throw AzureBlobException("CreateContainer($name) failed: ${resp.code} ${resp.message} ${body.orEmpty()}")
            }
        }
    }

    fun putBlock(container: String, blobName: String, blockId: String, bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        val urlB = accountUrl.newBuilder().addPathSegment(container)
        for (seg in blobName.split('/')) urlB.addPathSegment(seg)
        urlB.addEncodedQueryParameter("comp", "block")
        urlB.addEncodedQueryParameter("blockid", urlEncode(blockId))
        appendSas(urlB)
        val body: RequestBody = bytes.toRequestBody(
            "application/octet-stream".toMediaTypeOrNull(), offset, length,
        )
        val req = Request.Builder().url(urlB.build()).put(body).build()
        http.newCall(req).execute().use { ensureSuccess(it, "PutBlock $container/$blobName ($blockId)") }
    }

    fun putBlockList(
        container: String,
        blobName: String,
        blockIds: List<String>,
        contentType: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ) {
        val urlB = accountUrl.newBuilder().addPathSegment(container)
        for (seg in blobName.split('/')) urlB.addPathSegment(seg)
        urlB.addEncodedQueryParameter("comp", "blocklist")
        appendSas(urlB)
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<BlockList>")
            for (id in blockIds) append("<Latest>").append(xmlEscape(id)).append("</Latest>")
            append("</BlockList>")
        }
        val body = xml.toRequestBody("application/xml; charset=utf-8".toMediaTypeOrNull())
        val req = Request.Builder().url(urlB.build())
            .apply {
                if (!contentType.isNullOrBlank()) header("x-ms-blob-content-type", contentType)
                for ((k, v) in metadata) {
                    // Azure metadata header names must be valid C# identifiers
                    // (letters, digits, underscore) and are returned lowercased.
                    header("x-ms-meta-$k", v)
                }
            }
            .put(body).build()
        http.newCall(req).execute().use { ensureSuccess(it, "PutBlockList $container/$blobName") }
    }

    fun deleteBlob(container: String, blobName: String) {
        val url = blobUrl(container, blobName)
        val req = Request.Builder().url(url).delete().build()
        http.newCall(req).execute().use {
            if (!it.isSuccessful && it.code != 404) {
                throw AzureBlobException("DELETE $container/$blobName failed: ${it.code} ${it.message}")
            }
        }
    }

    private fun ensureSuccess(resp: Response, op: String) {
        if (!resp.isSuccessful) {
            val body = runCatching { resp.body?.string()?.take(512) }.getOrNull()
            throw AzureBlobException("$op failed: ${resp.code} ${resp.message} ${body.orEmpty()}")
        }
    }

    companion object {
        // <Container><Name>foo</Name>... we grab the first <Name> inside each <Container> block.
        private val CONTAINER_NAME_REGEX = Regex("<Container>\\s*<Name>([^<]+)</Name>", RegexOption.DOT_MATCHES_ALL)

        fun encodeBlockId(raw: String): String =
            Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))

        private fun extractSasFromConnectionString(cs: String): String? {
            val m = Pattern.compile("SharedAccessSignature=([^;]+)").matcher(cs)
            return if (m.find()) m.group(1) else null
        }

        private fun xmlEscape(s: String): String = s
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
    }
}

class AzureBlobException(message: String) : RuntimeException(message)
