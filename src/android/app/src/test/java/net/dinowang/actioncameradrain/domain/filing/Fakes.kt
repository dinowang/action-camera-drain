package net.dinowang.actioncameradrain.domain.filing

import java.io.ByteArrayInputStream
import java.io.InputStream

class FakeMediaFile(
    override val pathSegments: List<String>,
    private val bytes: ByteArray = ByteArray(0),
    override val lastModifiedMillis: Long = 0L,
) : MediaFile {
    override val name: String get() = pathSegments.last()
    override val sizeBytes: Long get() = bytes.size.toLong()
    override fun openInputStream(): InputStream = ByteArrayInputStream(bytes)
}

class FakeMediaSource(
    override val rootLabel: String,
    private val files: List<MediaFile>,
) : MediaSource {
    override fun files(): Sequence<MediaFile> = files.asSequence()
}
