package io.nekohasekai.sagernet.bg.proto

/**
 * Параметры raw-туннеля WDTT, которыми `initWdtt` делится с `VpnService.startVpn()`.
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

    fun set(ip: String, mtu: Int, dns: String, sockName: String) {
        this.ip = ip
        this.mtu = mtu
        this.dns = dns
        this.sockName = sockName
        active = true
    }

    fun clear() {
        active = false
        sockName = ""
    }
}