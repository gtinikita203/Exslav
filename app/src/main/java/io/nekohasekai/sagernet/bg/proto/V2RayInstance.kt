/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg.proto

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import io.nekohasekai.sagernet.RootCAProvider
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.AbstractInstance
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.V2rayBuildResult
import io.nekohasekai.sagernet.fmt.buildV2RayConfig
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.naive.buildNaiveConfig
import io.nekohasekai.sagernet.fmt.shadowquic.ShadowQUICBean
import io.nekohasekai.sagernet.fmt.shadowquic.buildShadowQUICConfig
import io.nekohasekai.sagernet.fmt.wdtt.WdttBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.parseWireGuardConfig
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import kotlinx.coroutines.*
import libexclavecore.V2RayInstance
import java.io.File
import java.net.DatagramSocket

abstract class V2RayInstance(
    var profile: ProxyEntity,
) : AbstractInstance {

    lateinit var config: V2rayBuildResult
    lateinit var v2rayPoint: V2RayInstance
    private lateinit var wsForwarder: WebView
    private lateinit var shForwarder: WebView
    private var wdttProcess: Process? = null

    /** Режим теста подключения WDTT: go_client запускается с -ping-only. */
    @Volatile
    var wdttPingOnly: Boolean = false

    /** Результат ping-теста WDTT (мс), если был запущен с -ping-only. */
    @Volatile
    var wdttPingResult: Int? = null

    val pluginPath = hashMapOf<String, PluginManager.InitResult>()
    val pluginConfigs = hashMapOf<Int, Pair<Int, String>>()
    val externalInstances = hashMapOf<Int, AbstractInstance>()
    open lateinit var processes: GuardedProcessPool
    private var cacheFiles = ArrayList<File>()
    fun isInitialized(): Boolean {
        return ::config.isInitialized
    }

    protected fun initPlugin(name: String): PluginManager.InitResult {
        return pluginPath.getOrPut(name) { PluginManager.init(name)!! }
    }

    protected open fun buildConfig() {
        config = buildV2RayConfig(profile)
    }

    protected open fun loadConfig() {
        v2rayPoint.loadConfig(config.config)
    }

    open suspend fun init() {
        v2rayPoint = V2RayInstance()
        if (profile.requireBean() is WdttBean) {
            Log.i("WDTT", "Detected WDTT bean, converting to WireGuard")
            val wgBean = initWdtt(profile.wdttBean!!)
            Log.i("WDTT", "Converted to WG bean, updating profile")
            profile = profile.copy().putBean(wgBean)
            Log.i("WDTT", "Profile updated with WG bean")
        }
        buildConfig()
        for ((_, chain) in config.index) {
            chain.entries.forEachIndexed { _, (triple, profile) ->
                val port = triple.first
                val username = triple.second
                val password = triple.third
                when (val bean = profile.requireBean()) {
                    is NaiveBean -> {
                        initPlugin("naive-plugin")
                        pluginConfigs[port] = profile.type to bean.buildNaiveConfig(port, username, password)
                    }
                    is ShadowQUICBean -> {
                        initPlugin("shadowquic-plugin")
                        pluginConfigs[port] = profile.type to bean.buildShadowQUICConfig(
                            port, username, password,
                            {
                                File(app.noBackupFilesDir, "shadowquic_" + SystemClock.elapsedRealtime() + ".pem").apply {
                                    parentFile?.mkdirs()
                                    cacheFiles.add(this)
                                }
                            }
                        )
                    }
                }
            }
        }
        loadConfig()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun launch() {
        val context = SagerNet.application

        if (WdttRawTunState.active) {
            // Raw-режим WDTT: v2ray-ядро НЕ запускаем — весь трафик идёт через
            // go_client напрямую (TUN fd передан в VpnService.startVpn()).
            // Фейковый WG-bean нужен только для профиля; реальный WG-handshake
            // к 127.0.0.1 никому не нужен и вызывает перезапуск туннеля.
            Log.i("WDTT", "Raw mode active, skipping v2ray core launch (go_client handles all traffic)")
            return
        }

        for ((_, chain) in config.index) {
            chain.entries.forEachIndexed { _, (triple, profile) ->
                val port = triple.first
                val bean = profile.requireBean()
                val (_, config) = pluginConfigs[port] ?: (0 to "")
                val env = mutableMapOf<String, String>()
                if (DataStore.providerRootCA != RootCAProvider.SYSTEM) {
                    env["SSL_CERT_FILE"] = when (DataStore.providerRootCA) {
                        RootCAProvider.MOZILLA -> {
                            File(app.filesDir, "mozilla_included.pem").canonicalPath
                        }
                        RootCAProvider.SYSTEM_AND_USER -> {
                            File(app.filesDir, "android_included.pem").canonicalPath
                        }
                        RootCAProvider.CUSTOM -> {
                            File(app.externalAssets, "root_store.certs").canonicalPath
                        }
                        else -> error("impossible")
                    }
                }
                when {
                    externalInstances.containsKey(port) -> {
                        externalInstances[port]!!.launch()
                    }
                    bean is NaiveBean -> {
                        val configFile = File(
                            context.noBackupFilesDir,
                            "naive_" + SystemClock.elapsedRealtime() + ".json"
                        )
                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)
                        if (bean.certificate.isNotEmpty()) {
                            val caFile = File(
                                context.noBackupFilesDir,
                                "naive_" + SystemClock.elapsedRealtime() + ".ca"
                            )
                            caFile.parentFile?.mkdirs()
                            caFile.writeText(bean.certificate)
                            cacheFiles.add(caFile)
                            env["SSL_CERT_FILE"] = caFile.absolutePath
                        }
                        val commands = mutableListOf(
                            initPlugin("naive-plugin").path, configFile.absolutePath
                        )
                        processes.start(commands, env)
                    }
                    bean is ShadowQUICBean -> {
                        val configFile = File(
                            context.noBackupFilesDir,
                            "shadowquic_" + SystemClock.elapsedRealtime() + ".yaml"
                        )
                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)
                        if (DataStore.providerRootCA == RootCAProvider.SYSTEM) {
                            // https://github.com/rustls/rustls-native-certs/issues/3
                            env["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
                        }
                        val commands = mutableListOf(
                            initPlugin("shadowquic-plugin").path,
                            "-c",
                            configFile.absolutePath,
                        )
                        processes.start(commands, env)
                    }
                }
            }
        }
        v2rayPoint.start()
        if (config.requireWs) {
            val url = "http://" + joinHostPort(LOCALHOST, config.wsPort) + "/"
            runOnMainDispatcher {
                wsForwarder = WebView(context)
                wsForwarder.settings.javaScriptEnabled = true
                wsForwarder.webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        Logs.d("WebView load r: $error")
                        runOnMainDispatcher {
                            wsForwarder.loadUrl("about:blank")
                            delay(1000L)
                            wsForwarder.loadUrl(url)
                        }
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        Logs.d("WebView loaded: ${view.title}")
                    }
                }
                wsForwarder.loadUrl(url)
            }
        }
        if (config.requireSh) {
            val url = "http://" + joinHostPort(LOCALHOST, config.shPort) + "/"
            runOnMainDispatcher {
                shForwarder = WebView(context)
                shForwarder.settings.javaScriptEnabled = true
                shForwarder.webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        Logs.d("WebView load r: $error")
                        runOnMainDispatcher {
                            shForwarder.loadUrl("about:blank")
                            delay(1000L)
                            shForwarder.loadUrl(url)
                        }
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        Logs.d("WebView loaded: ${view.title}")
                    }
                }
                shForwarder.loadUrl(url)
            }
        }
    }

    private var isClosed = false

    @Suppress("EXPERIMENTAL_API_USAGE")
    override fun close() {
        if (isClosed) return

        if (wdttProcess != null) {
            Log.i("WDTT", "Destroying wdtt process")
            wdttProcess?.destroy()
            wdttProcess = null
            Log.i("WDTT", "wdtt process destroyed")
        }

        if (WdttRawTunState.active) {
            Log.i("WDTT", "Clearing raw TUN state on close")
            WdttRawTunState.clear()
        }

        for (instance in externalInstances.values) {
            runCatching {
                instance.close()
            }
        }

        cacheFiles.removeAll { it.delete(); true }

        if (::wsForwarder.isInitialized) {
            runBlocking {
                onMainDispatcher {
                    wsForwarder.loadUrl("about:blank")
                    wsForwarder.destroy()
                }
            }
        }

        if (::shForwarder.isInitialized) {
            runBlocking {
                onMainDispatcher {
                    shForwarder.loadUrl("about:blank")
                    shForwarder.destroy()
                }
            }
        }

        if (::processes.isInitialized) processes.close(GlobalScope + Dispatchers.IO)

        if (::v2rayPoint.isInitialized) {
            v2rayPoint.close()
        }

        isClosed = true
    }

    private suspend fun initWdtt(bean: WdttBean): WireGuardBean {
        val context = SagerNet.application
        val exe = File(context.applicationInfo.nativeLibraryDir, "libclient.so")
        Log.i("WDTT", "Checking ${exe.absolutePath} exists=${exe.exists()} canExecute=${exe.canExecute()}")
        if (!exe.exists()) {
            Log.e("WDTT", "libclient.so not found at ${exe.absolutePath}")
            error("wdtt: libclient.so not found at ${exe.absolutePath}")
        }
        if (!exe.canExecute()) {
            Log.e("WDTT", "libclient.so not executable at ${exe.absolutePath}")
            error("wdtt: libclient.so not executable, chmod required")
        }

        val listenPort = DatagramSocket(0).use { it.localPort }
        val autoRaw = (bean.mode == "vpn" || bean.mode == "auto") && bean.serverAddress.contains("2.26")
        val isRaw = bean.mode == "rawtun" || autoRaw
        if (autoRaw) {
            Log.i("WDTT", "Auto-switch WG->raw for server ${bean.serverAddress}")
        }
        // Raw-режим требует порт сервера -listen-raw (по умолчанию serverPort+3:
        // 56000 -> 56003), а НЕ DTLS-порт serverPort — иначе сервер не понимает
        // GETCONF_RAW и никогда не отвечает (см. server.go -listen-raw).
        val peerPort = if (isRaw) {
            (bean.rawPort ?: 0).takeIf { it > 0 } ?: (bean.serverPort + 3)
        } else {
            bean.serverPort
        }
        val peer = "${bean.serverAddress}:$peerPort"

        val cmd = mutableListOf(
            exe.absolutePath,
            "-peer", peer,
            "-n", bean.workers.toString(),
            "-listen", "127.0.0.1:$listenPort",
            "-vk-anon-path", "vkcalls",
            "-vk-auth", "anonymous",
            "-captcha-mode", "auto",
        )

        var rawSockName = ""
        if (isRaw) {
            rawSockName = io.nekohasekai.sagernet.fmt.wdtt.TunFdBridge.newSocketName()
            cmd.addAll(listOf("-mode", "rawtun", "-tun-fd-sock", io.nekohasekai.sagernet.fmt.wdtt.TunFdBridge.goSockPath(rawSockName)))
        }

        if (bean.vkHashes.isNotBlank()) cmd.addAll(listOf("-vk", bean.vkHashes))
        if (bean.password.isNotBlank()) cmd.addAll(listOf("-password", bean.password))

        if (isRaw && wdttPingOnly) {
            // Тест подключения: go_client с -ping-only сам замеряет RTT до
            // raw-сервера (RunPingRaw: TURN + WRAP + GETCONF_RAW) и печатает
            // в stdout "PING_RESULT|<rtt>". TUN fd в этом режиме не нужен.
            cmd.add("-ping-only")
            val proc = ProcessBuilder(cmd).start()
            wdttProcess = proc
            Log.i("WDTT", "Starting subprocess [RAW PING]: ${cmd.joinToString(" ")}")
            try {
                val rtt = withTimeout(60_000L) {
                    proc.inputStream.bufferedReader().useLines { lines ->
                        var result: Int? = null
                        for (line in lines) {
                            Log.i("WDTT-GoPing", line)
                            val m = Regex("PING_RESULT\\|(\\d+)").find(line)
                            if (m != null) {
                                result = m.groupValues[1].toIntOrNull()
                                break
                            }
                        }
                        result ?: error("wdtt: ping did not complete")
                    }
                }
                wdttPingResult = rtt
                Log.i("WDTT", "Raw ping result: $rtt ms")
                proc.destroy()
                wdttProcess = null
                return WireGuardBean().apply {
                    this.serverAddress = "127.0.0.1"
                    this.serverPort = listenPort
                    this.localAddress = "10.70.0.2/32"
                    this.mtu = 1350
                    this.name = bean.name?.ifBlank { "WDTT" } ?: "WDTT"
                    this.privateKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
                    this.peerPublicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
                }.applyDefaultValues()
            } catch (e: Exception) {
                proc.destroy()
                wdttProcess = null
                Log.e("WDTT", "Failed to ping raw server", e)
                error("wdtt: ping failed: ${e.message}")
            }
        }

        val proc = ProcessBuilder(cmd).start()
        wdttProcess = proc

        if (isRaw) {
            val rawConfig = try {
                Log.i("WDTT", "Starting subprocess [RAW]: ${cmd.joinToString(" ")}")
                withTimeout(120_000L) {
                    readWdttRawConfig(proc)
                }
            } catch (e: Exception) {
                proc.destroy()
                wdttProcess = null
                Log.e("WDTT", "Failed to get RAW config", e)
                error("wdtt: failed to get RAW config: ${e.message}")
            }

Log.i("WDTT", "Got RAW config:\n$rawConfig")
            val fields = rawConfig.lines().associate { l ->
                val parts = l.split("=", limit = 2).map { it.trim() }
                (parts.getOrElse(0) { "" }) to (parts.getOrElse(1) { "" })
            }
            val ip = fields["IP"].orEmpty()
            val mtu = fields["MTU"]?.toIntOrNull() ?: 1350
            val dns = fields["DNS"].orEmpty().ifBlank { "1.1.1.1,1.0.0.1" }

            // Параметры raw-туннеля передаются VpnService.startVpn(), который сам
            // поднимет TUN с server-адресом/MTU/DNS и сразу передаст fd в go_client
            // (сейчас — через TunFdBridge.sendOnce, а не через фоновую гонку на conn).
            // TUN fd передаётся ТОЛЬКО один раз, когда VpnService.build() его создал.
            // Здесь больше НЕ ждём VpnService.instance.conn — это была гонка:
            // v2ray-core (fake WG) закрывал conn раньше, чем мы успевали забрать fd.
            WdttRawTunState.set(ip.ifBlank { "10.70.0.2" }, mtu, dns, rawSockName)
            Log.i("WDTT", "Registered raw TUN state: ip=${WdttRawTunState.ip} mtu=${WdttRawTunState.mtu} dns=${WdttRawTunState.dns} sock=$rawSockName")

            // Возвращаем фейковый WG bean для удовлетворения контракта Exslav
            return WireGuardBean().apply {
                this.serverAddress = "127.0.0.1"
                this.serverPort = listenPort
                this.localAddress = if (ip.isNotBlank()) ip else "10.70.0.2/32"
                this.mtu = mtu
                this.name = bean.name?.ifBlank { "WDTT" } ?: "WDTT"
                this.privateKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
                this.peerPublicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
            }.applyDefaultValues()
        }

        val wgConfig = try {
            Log.i("WDTT", "Starting subprocess: ${cmd.joinToString(" ")}")
            Log.i("WDTT", "Waiting for WG config from $peer with workers=${bean.workers}...")
            withTimeout(120_000L) {
                readWdttWgConfig(proc)
            }
        } catch (e: Exception) {
            proc.destroy()
            wdttProcess = null
            Log.e("WDTT", "Failed to get WG config", e)
            error("wdtt: failed to get WireGuard config: ${e.message}")
        }

        Log.i("WDTT", "Got WG config, parsing:\n$wgConfig")
        val parsed = parseWireGuardConfig(wgConfig)
        Log.i("WDTT", "Parsed ${parsed.size} WG beans")
        return parsed.firstOrNull()?.applyDefaultValues()
            ?: error("wdtt: could not parse WireGuard config")
    }

    private suspend fun readWdttRawConfig(proc: Process): String = withContext(Dispatchers.IO) {
        val stdoutReader = proc.inputStream.bufferedReader()
        val stderrReader = proc.errorStream.bufferedReader()
        val configBuilder = StringBuilder()
        val stderrLog = StringBuilder()
        var collecting = false

        val readyCompletable = kotlinx.coroutines.CompletableDeferred<Unit>()
        val rawBoxCompleted = kotlinx.coroutines.CompletableDeferred<Unit>()

        // Читаем stderr (куда Go пишет логи log.Printf)
        val stderrJob = GlobalScope.launch(Dispatchers.IO) {
            try {
                var line: String?
                while (stderrReader.readLine().also { line = it } != null) {
                    val l = line!!
                    Log.i("WDTT-GoStderr", l)
                    stderrLog.append(l).append("\n")

                    if (l.contains("[СТАТИСТИКА]")) {
                        WdttRawTunState.updateFromStats(l)
                    }

                    if (l.contains("[READY] Туннель готов к работе") || l.contains("Успешный старт!")) {
                        Log.i("WDTT", "Detected READY signal in stderr!")
                        readyCompletable.complete(Unit)
                    }
                }
            } catch (_: Exception) {}
        }

        // Читаем stdout (куда печатается RAW Конфиг)
        val stdoutJob = GlobalScope.launch(Dispatchers.IO) {
            try {
                var line: String?
                while (stdoutReader.readLine().also { line = it } != null) {
                    val l = line!!
                    Log.i("WDTT-GoStdout", l)

                    if (l.contains("╔") && l.contains("RAW Конфиг")) {
                        collecting = true
                        configBuilder.clear()
                    } else if (collecting && l.contains("╚")) {
                        Log.i("WDTT", "Found RAW config end marker in stdout")
                        readyCompletable.complete(Unit)
                        rawBoxCompleted.complete(Unit)
                    } else if (collecting && l.contains("║")) {
                        val cleaned = l.replace("║", "").trim()
                        configBuilder.appendLine(cleaned)
                    }
                }
            } catch (_: Exception) {}
        }

        // Ждем именно рамку с RAW-конфигом из stdout (rawBoxCompleted),
        // а не первый [READY] из stderr, который печатается раньше окончания парсинга.
        // Если рамка так и не пришла, но туннель READY — используем фолбэк ниже.
        try {
            withTimeout(60_000L) {
                rawBoxCompleted.await()
            }
        } catch (e: TimeoutCancellationException) {
            Log.w("WDTT", "RAW config box not received within 60s (only READY seen), using fallback")
        } catch (e: Exception) {
            Log.e("WDTT", "Timeout waiting for RAW config / READY signal", e)
            error("wdtt: process failed to start tunnel\nstderr: $stderrLog")
        }

        val resultStr = configBuilder.toString().trim()
        if (resultStr.isNotEmpty()) {
            return@withContext resultStr
        }
        return@withContext "IP = 10.70.0.2\nMTU = 1350"
    }

    private suspend fun readWdttWgConfig(proc: Process): String = withContext(Dispatchers.IO) {
        val stdoutReader = proc.inputStream.bufferedReader()
        val stderrReader = proc.errorStream.bufferedReader()
        val configBuilder = StringBuilder()
        val stderrLog = StringBuilder()
        var collecting = false
        var line: String?

        try {
            while (stdoutReader.readLine().also { line = it } != null) {
                val l = line!!
                Log.d("WDTT", "stdout: $l")
                when {
                    l.contains("╔") && l.contains("WireGuard") -> {
                        collecting = true
                        configBuilder.clear()
                    }
                    collecting && l.contains("╚") -> {
                        Log.i("WDTT", "Found WG config end marker")
                        val result = configBuilder.toString().trim()
                        Log.i("WDTT", "Returning config length=${result.length}")
                        return@withContext result
                    }
                    collecting && l.contains("║") -> {
                        val cleaned = l.replace("║", "").trim()
                        configBuilder.appendLine(cleaned)
                        Log.d("WDTT", "Added config line: $cleaned")
                    }
                }
            }
            // Read stderr after stdout is closed
            stderrReader.readLines().forEach { stderrLog.append(it).append("\n") }
        } catch (e: Exception) {
            Log.e("WDTT", "Exception reading subprocess output", e)
            stderrReader.readLines().forEach { stderrLog.append(it).append("\n") }
            error("wdtt: failed reading stdout: ${e.message}\nstderr: $stderrLog")
        }
        error("wdtt: process exited without WireGuard config\nstderr: $stderrLog")
    }

}
