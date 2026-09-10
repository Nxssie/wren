package download

import java.io.File
import java.io.OutputStream

/**
 * Where a finished download is persisted. [open] hands out the stream the bytes are
 * written into; the stream must be closed before [Output.commit] is called. Non-File
 * destinations (MediaStore on Android) rely on [Output.abort] for cleanup on failure.
 */
fun interface Destination {
    fun open(fileName: String): Output
}

interface Output {
    val stream: OutputStream

    /** Human-readable location of the final file (full path on desktop, file name on Android). */
    val path: String

    /** Called after a successfully completed copy to make the file visible. */
    fun commit()

    /** Discards whatever was written so far. */
    fun abort()
}

/** Plain-directory destination: writes to a `.part` sibling and renames on commit. */
class FileDestination(private val dir: File) : Destination {
    override fun open(fileName: String): Output {
        dir.mkdirs()
        if (!dir.isDirectory) error("cannot write to ${dir.path}")
        val target = uniqueFile(dir, fileName)
        val partial = File(target.parentFile, "${target.name}.part")
        val stream = partial.outputStream()
        return object : Output {
            override val stream: OutputStream = stream
            override val path: String = target.path
            override fun commit() {
                if (!partial.renameTo(target)) error("cannot finalize ${target.name}")
            }
            override fun abort() {
                runCatching { stream.close() }
                partial.delete()
            }
        }
    }
}

private fun uniqueFile(dir: File, name: String): File {
    val candidate = File(dir, name)
    if (!candidate.exists()) return candidate
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var index = 1
    while (true) {
        val next = File(dir, "$stem ($index)$ext")
        if (!next.exists()) return next
        index++
    }
}
