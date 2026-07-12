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
    val profile: ProxyEntity,
) : AbstractInstance {

    lateinit var config: V2rayBuildResult
    lateinit var v2rayPoint: V2RayInstance
    private lateinit var wsForwarder: WebView
    private lateinit var shForwarder: WebView
    private var wdttProcess: Process? = null

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
            val wgBean = initWdtt(profile.wdttBean!!)
            profile.putBean(wgBean)
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

        wdttProcess?.destroy()
        wdttProcess = null

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
        val peer = "${bean.serverAddress}:${bean.serverPort}"
        val deviceId = android.provider.Settings.Secure.getString(
            SagerNet.application.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown"

        val cmd = mutableListOf(
            exe.absolutePath,
            "-device-id", deviceId,
            "-peer", peer,
            "-n", bean.workers.toString(),
            "-listen", "127.0.0.1:$listenPort",
            "-vk-anon-path", "vkcalls",
            "-vk-auth", "anonymous",
        )
        if (bean.vkHashes.isNotBlank()) cmd.addAll(listOf("-vk", bean.vkHashes))
        if (bean.password.isNotBlank()) cmd.addAll(listOf("-password", bean.password))

        val proc = ProcessBuilder(cmd).start()
        wdttProcess = proc

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

        return parseWireGuardConfig(wgConfig).firstOrNull()
            ?: error("wdtt: could not parse WireGuard config")
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
                        // Read any remaining stderr before returning
                        stderrReader.readLines().forEach { stderrLog.append(it).append("\n") }
                        return@withContext configBuilder.toString().trim()
                    }
                    collecting && l.contains("║") -> configBuilder.appendLine(l.replace("║", "").trim())
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
