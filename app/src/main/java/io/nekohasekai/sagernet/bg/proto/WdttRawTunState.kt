package io.nekohasekai.sagernet.bg.proto

/**
 * Параметры и состояние raw-туннеля WDTT, которыми `initWdtt` делится с
 * `VpnService.startVpn()` и которые заполняются из stderr go_client.
 * В raw-режиме TUN поднимается в VpnService НЕ со стандартным 172.19.0.1, а с
 * адресом/MTU/DNS, полученными от сервера (RAWCONF), и его fd отдаётся напрямую
 * go_client — промежуточный Tun2ray/v2ray при этом НЕ создаётся.
 */
object WdttRawTunState {
    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    var ip: String = "10.70.0.2"

    @Volatile
    var mtu: Int = 1350

    @Volatile
    var dns: String = "1.1.1.1,1.0.0.1"

    @Volatile
    var sockName: String = ""

    /** Cumulative traffic reported by go_client "[СТАТИСТИКА]" lines. */
    @Volatile
    var txBytes: Long = 0L

    @Volatile
    var rxBytes: Long = 0L

    /** Active workers reported by go_client. */
    @Volatile
    var workers: Int = 0

    fun set(ip: String, mtu: Int, dns: String, sockName: String) {
        this.ip = ip
        this.mtu = mtu
        this.dns = dns
        this.sockName = sockName
        txBytes = 0L
        rxBytes = 0L
        workers = 0
        active = true
    }

    fun clear() {
        active = false
        sockName = ""
        txBytes = 0L
        rxBytes = 0L
        workers = 0
    }

    /**
     * Парсит строку статистики go_client вида:
     * "[СТАТИСТИКА] Активных: N | Up: X.XX МБ | Down: Y.YY МБ"
     * и обновляет счётчики.
     */
    fun updateFromStats(line: String) {
        val activeMatch = Regex("Активных:\\s*(\\d+)").find(line)
        if (activeMatch != null) {
            workers = activeMatch.groupValues[1].toIntOrNull() ?: 0
        }
        val upMatch = Regex("Up:\\s*([\\d.]+)\\s*МБ").find(line)
        if (upMatch != null) {
            txBytes = (upMatch.groupValues[1].toDoubleOrNull() ?: 0.0).let { (it * 1024 * 1024).toLong() }
        }
        val downMatch = Regex("Down:\\s*([\\d.]+)\\s*МБ").find(line)
        if (downMatch != null) {
            rxBytes = (downMatch.groupValues[1].toDoubleOrNull() ?: 0.0).let { (it * 1024 * 1024).toLong() }
        }
        // Фолбэк на старый формат go_client: "Трафик: X.XX МБ" (без разделения Up/Down).
        val trafficMatch = Regex("Трафик:\\s*([\\d.]+)\\s*МБ").find(line)
        if (trafficMatch != null && upMatch == null && downMatch == null) {
            rxBytes = (trafficMatch.groupValues[1].toDoubleOrNull() ?: 0.0).let { (it * 1024 * 1024).toLong() }
        }
    }
}