package net.dinowang.actioncameradrain.domain.filing

import java.io.InputStream

/**
 * Abstraction over a card-like media tree. Implementations may be backed by a
 * SAF DocumentFile tree on Android, or a plain filesystem in unit tests.
 */
interface MediaSource {
    val rootLabel: String
    fun files(): Sequence<MediaFile>
}

interface MediaFile {
    /** Path segments under the source root. Last segment equals [name]. */
    val pathSegments: List<String>
    val name: String
    val sizeBytes: Long
    val lastModifiedMillis: Long
    fun openInputStream(): InputStream
}

fun MediaFile.relativePath(): String = pathSegments.joinToString("/")
