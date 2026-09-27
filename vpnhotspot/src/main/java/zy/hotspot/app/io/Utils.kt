package zy.hotspot.app.io

import android.system.ErrnoException
import android.system.OsConstants
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.availableForRead
import io.ktor.utils.io.readAvailable
import java.io.IOException
import java.util.Locale

// Stream closed caused in NullOutputStream
val IOException.isEBADF get() = (cause as? ErrnoException)?.errno == OsConstants.EBADF ||
        message?.lowercase(Locale.ENGLISH) == "stream closed"

suspend fun ByteReadChannel.readChunkLine(): String? {
    val buffer = StringBuilder()
    while (true) {
        if (!awaitContent(1)) return if (buffer.isEmpty()) null else buffer.toString()
        val one = ByteArray(1)
        if (readAvailable(one, 0, 1) <= 0) return if (buffer.isEmpty()) null else buffer.toString()
        val b = one[0].toInt()
        if (b == '\n'.code) return buffer.toString()
        if (b != '\r'.code) buffer.append(b.toChar())
    }
}

suspend fun ByteReadChannel.forEachLineSafely(block: (String) -> Boolean) {
    try {
        while (true) {
            val line = readChunkLine() ?: break
            if (!block(line)) break
        }
    } catch (e: ErrnoException) {
        if (e.errno != OsConstants.EBADF) throw e
    } catch (e: IOException) {
        if (!e.isEBADF) throw e
    }
}

/**
 * Drain currently available bytes from this channel and consume complete lines.
 *
 * @return true if reading stopped with the channel still open, false if EOF was reached.
 */
suspend fun ByteReadChannel.drainLines(
        line: StringBuilder = StringBuilder(),
        buffer: ByteArray = ByteArray(4096),
        flushPartial: Boolean = false,
        block: (String) -> Unit,
): Boolean {
    fun flushLine(emitEmpty: Boolean) {
        if (line.lastOrNull() == '\r') line.setLength(line.length - 1)
        if (emitEmpty || line.isNotEmpty()) block(line.toString())
        line.clear()
    }
    while (availableForRead > 0) {
        val count = readAvailable(buffer)
        if (count < 0) {
            closedCause?.let { throw it }
            flushLine(false)
            return false
        }
        val text = buffer.decodeToString(0, count)
        var start = 0
        while (true) {
            val end = text.indexOf('\n', start)
            if (end < 0) {
                line.append(text, start, text.length)
                break
            }
            line.append(text, start, end)
            flushLine(true)
            start = end + 1
        }
    }
    if (isClosedForRead) {
        closedCause?.let { throw it }
        flushLine(false)
        return false
    }
    if (flushPartial) flushLine(false)
    return true
}
