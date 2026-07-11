package io.nekohasekai.sagernet.fmt.wdtt

import android.net.Uri
import io.nekohasekai.sagernet.fmt.AbstractBean
import org.json.JSONArray
import org.json.JSONObject

fun parseWdttConfig(text: String): List<AbstractBean> {
    val trimmed = text.trim()
    if (trimmed.startsWith("qwdtt://") || trimmed.startsWith("qwdtt:config")) {
        parseQwdttUri(trimmed)?.let { return listOf(it) }
    }
    if (trimmed.startsWith("{")) {
        try {
            val root = JSONObject(trimmed)
            val array = root.optJSONArray("profiles") ?: root.optJSONArray("servers")
            if (array != null) {
                val list = parseWdttArray(array)
                if (list.isNotEmpty()) return list
            }
        } catch (_: Exception) {}
    }
    if (trimmed.startsWith("[")) {
        try {
            val list = parseWdttArray(JSONArray(trimmed))
            if (list.isNotEmpty()) return list
        } catch (_: Exception) {}
    }
    return emptyList()
}

private fun parseWdttArray(array: JSONArray): List<AbstractBean> {
    val list = mutableListOf<AbstractBean>()
    for (i in 0 until array.length()) {
        parseWdttObject(array.optJSONObject(i) ?: continue)?.let { list.add(it) }
    }
    return list
}

fun parseWdttObject(obj: JSONObject): WdttBean? {
    val peer = obj.optString("peer", "").trim()
    if (peer.isBlank()) return null
    val (host, port) = splitPeer(peer)
    return WdttBean().apply {
        name = obj.optString("name", "WDTT")
        serverAddress = host
        serverPort = port
        vkHashes = obj.optString("hashes", obj.optString("vkHashes", ""))
        password = obj.optString("password", obj.optString("pass", ""))
        workers = obj.optInt("workers", obj.optInt("workersPerHash", 24)).coerceIn(12, 108)
    }
}

fun parseQwdttUri(raw: String): WdttBean? {
    return try {
        val fullUri = raw.replace("qwdtt:config", "qwdtt://config")

        // Format: wdtt://IP:DTLS_PORT:WG_PORT:LOCAL_PORT:PASSWORD:HASH1,HASH2,...
        if (fullUri.startsWith("wdtt://")) {
            val afterScheme = fullUri.substringAfter("://")
            val colonIdx = afterScheme.indexOf(":")
            if (colonIdx <= 0) return null

            val host = afterScheme.substring(0, colonIdx)
            val rest = afterScheme.substring(colonIdx + 1)
            val parts = rest.split(":", limit = 5)

            if (parts.size >= 4) {
                val dtlsPort = parts[0].toIntOrNull() ?: return null
                val password = parts[3]
                val hashes = if (parts.size > 4) parts[4] else ""

                return WdttBean().apply {
                    name = "WDTT $host"
                    serverAddress = host
                    serverPort = dtlsPort
                    vkHashes = hashes
                    password = password
                    workers = 24
                }
            }
        }

        // Fallback: query params
        val uri = Uri.parse(fullUri)
        val peer = uri.getQueryParameter("peer") ?: return null
        val (addr, port) = splitPeer(peer)
        WdttBean().apply {
            name = uri.getQueryParameter("name") ?: "WDTT"
            serverAddress = addr
            serverPort = port
            vkHashes = uri.getQueryParameter("hashes") ?: ""
            password = uri.getQueryParameter("pass") ?: uri.getQueryParameter("password") ?: ""
            workers = (uri.getQueryParameter("workers")?.toIntOrNull() ?: 24).coerceIn(12, 108)
        }
    } catch (_: Exception) {
        null
    }
}

private fun splitPeer(peer: String): Pair<String, Int> {
    val lastColon = peer.lastIndexOf(':')
    if (lastColon <= 0) return peer to 51820
    val port = peer.substring(lastColon + 1).toIntOrNull() ?: return peer to 51820
    return peer.substring(0, lastColon) to port
}
