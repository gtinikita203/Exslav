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
                android.util.Log.i("WDTT-Bridge", "sendOnce attempt #$attempt: connect to $name")
                Logs.i("[$TAG] sendOnce attempt #$attempt: connect to $name")
                client = LocalSocket()
                client.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
                android.util.Log.i("WDTT-Bridge", "sendOnce attempt #$attempt: connect OK, sending fd...")
                Logs.i("[$TAG] sendOnce attempt #$attempt: connect OK, sending fd...")
                client.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                client.outputStream.write(1)
                client.outputStream.flush()
                android.util.Log.i("WDTT-Bridge", "TUN fd sent to go_client via $name (attempt #$attempt)")
                Logs.i("[$TAG] TUN fd sent to go_client via $name (attempt #$attempt)")
                return@withContext
            } catch (e: Exception) {
                lastError = e
                android.util.Log.w("WDTT-Bridge", "sendOnce attempt #$attempt FAILED: ${e.javaClass.simpleName}: ${e.message}")
                Logs.w("[$TAG] sendOnce attempt #$attempt FAILED: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                runCatching { client?.close() }
            }
            delay(CONNECT_RETRY_DELAY_MS)
        }
        android.util.Log.e("WDTT-Bridge", "sendOnce: failed to connect to go_client after $MAX_ATTEMPTS attempts")
        Logs.e("[$TAG] sendOnce: failed to connect to go_client after $MAX_ATTEMPTS attempts")
        throw IllegalStateException("TunFdBridge.sendOnce: failed to connect to go_client", lastError)
    }
}
