package zy.hotspot.app.io

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Handler
import android.os.ParcelFileDescriptor
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * 本地实现 librootkotlinx 2.0 的 io/net 桥接 API（1.2.1 缺失部分），
 * 行为对齐 VPNHotspot 原工程。仅用于 vpnhotspot 模块。
 */

class ALocalSocket internal constructor(val socket: LocalSocket) {
    suspend fun openReadChannel(): ByteReadChannel {
        val channel = ByteChannel(autoFlush = true)
        startReadThread(socket.fileDescriptor, channel)
        return channel
    }

    suspend fun openWriteChannel(): ByteWriteChannel {
        val channel = ByteChannel(autoFlush = false)
        startWriteThread(socket.fileDescriptor, channel)
        return channel
    }

    fun close() {
        try {
            socket.close()
        } catch (_: IOException) { }
    }
}

class ALocalServerSocket(serverSocket: LocalServerSocket, @Suppress("unused") handler: Handler) : AutoCloseable {
    private val delegate = serverSocket

    suspend fun accept(): ALocalSocket = withContext(Dispatchers.IO) {
        ALocalSocket(delegate.accept())
    }

    override fun close() {
        try {
            delegate.close()
        } catch (_: IOException) { }
    }
}

class FileDescriptorByteReadChannel(
    private val fd: FileDescriptor,
    private val delegate: ByteChannel,
) : ByteReadChannel by delegate {
    fun drain() {
        // 后台读线程持续消费，close 前无需主动排空
    }
}

fun ParcelFileDescriptor.openReadChannel(@Suppress("unused") handler: Handler): FileDescriptorByteReadChannel {
    val channel = ByteChannel(autoFlush = true)
    startReadThread(fileDescriptor, channel)
    return FileDescriptorByteReadChannel(fileDescriptor, channel)
}

suspend fun ByteReadChannel.readLineTo(line: StringBuilder): Int {
    val text = readChunkLine() ?: return -1
    line.append(text)
    return text.length
}

private fun startReadThread(fd: FileDescriptor, target: ByteChannel) {
    Thread {
        val input = FileInputStream(fd)
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) {
                    target.cancel(null)
                    break
                }
                if (n == 0) continue
                runBlocking { target.writeFully(buf, 0, n) }
            }
        } catch (e: IOException) {
            target.cancel(e)
        } finally {
            try {
                input.close()
            } catch (_: IOException) { }
        }
    }.also { it.isDaemon = true }.start()
}

private fun startWriteThread(fd: FileDescriptor, source: ByteChannel) {
    Thread {
        val output = FileOutputStream(fd)
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = runBlocking { source.readAvailable(buf) }
                if (n < 0) break
                if (n > 0) {
                    output.write(buf, 0, n)
                    output.flush()
                }
            }
        } catch (e: IOException) {
            source.cancel(e)
        } finally {
            try {
                output.close()
            } catch (_: IOException) { }
        }
    }.also { it.isDaemon = true }.start()
}
