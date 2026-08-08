package io.nekohasekai.sagernet.fmt.wdtt

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Передаёт TUN fd в go_client (libclient.so) через unix-сокет (SCM_RIGHTS).
 */
object TunFdBridge {
    private const val TAG = "TunFdBridge"
    private const val CONNECT_RETRY_DELAY_MS = 200L
    private const val MAX_ATTEMPTS = 25

    fun newSocketName(): String = "exslav_wdtt_tun_${android.os.Process.myPid()}_${System.nanoTime()}"

    fun goSockPath(name: String): String = "@$name"

    suspend fun sendOnce(name: String, pfd: ParcelFileDescriptor) = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            var client: LocalSocket? = null
            try {
                Logs.i("[$TAG] sendOnce attempt #$attempt: connect to $name")
                client = LocalSocket()
                client.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
                Logs.i("[$TAG] sendOnce attempt #$attempt: connect OK, sending fd...")
                client.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                client.outputStream.write(1)
                client.outputStream.flush()
                Logs.i("[$TAG] TUN fd sent to go_client via $name (attempt #$attempt)")
                return@withContext
            } catch (e: Exception) {
                lastError = e
                Logs.w("[$TAG] sendOnce attempt #$attempt FAILED: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                runCatching { client?.close() }
            }
            delay(CONNECT_RETRY_DELAY_MS)
        }
        Logs.e("[$TAG] sendOnce: failed to connect to go_client after $MAX_ATTEMPTS attempts")
        throw IllegalStateException("TunFdBridge.sendOnce: failed to connect to go_client", lastError)
    }
}
