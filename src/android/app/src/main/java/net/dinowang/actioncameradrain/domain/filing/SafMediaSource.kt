package net.dinowang.actioncameradrain.domain.filing

import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream

/**
 * Media source backed by a SAF [DocumentFile] tree.
 *
 * Lazily walks the tree on demand. Each [SafMediaFile] reads bytes through
 * the given [ContentResolver].
 */
class SafMediaSource(
    private val resolver: ContentResolver,
    private val root: DocumentFile,
    override val rootLabel: String = root.name ?: "card",
) : MediaSource {

    override fun files(): Sequence<MediaFile> = sequence {
        walk(root, emptyList())
    }.constrainOnce()

    private suspend fun SequenceScope<MediaFile>.walk(node: DocumentFile, parents: List<String>) {
        if (node.isFile) {
            yield(SafMediaFile(resolver, node, parents + (node.name ?: "")))
            return
        }
        val children = node.listFiles().sortedBy { it.name ?: "" }
        for (child in children) {
            val name = child.name ?: continue
            val segs = parents + name
            if (child.isDirectory) {
                walk(child, segs)
            } else {
                yield(SafMediaFile(resolver, child, segs))
            }
        }
    }
}

private class SafMediaFile(
    private val resolver: ContentResolver,
    private val doc: DocumentFile,
    override val pathSegments: List<String>,
) : MediaFile {
    override val name: String get() = pathSegments.last()
    override val sizeBytes: Long get() = doc.length()
    override val lastModifiedMillis: Long get() = doc.lastModified()
    override fun openInputStream(): InputStream {
        val uri: Uri = doc.uri
        return resolver.openInputStream(uri)
            ?: throw java.io.IOException("Cannot open input stream for $uri")
    }
}
