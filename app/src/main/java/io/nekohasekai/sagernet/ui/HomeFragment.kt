package io.nekohasekai.sagernet.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.onDefaultDispatcher
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.FormatFileSizeCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class HomeFragment : ToolbarFragment(R.layout.layout_home) {

    private lateinit var connectButton: LinearLayout
    private lateinit var connectIcon: TextView
    private lateinit var connectLabel: TextView
    private lateinit var connectSub: TextView

    private lateinit var pulseRing1: View
    private lateinit var pulseRing2: View

    private lateinit var statusChip: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusText: TextView

    private lateinit var serverCard: LinearLayout
    private lateinit var serverFlag: TextView
    private lateinit var serverName: TextView
    private lateinit var serverSubtitle: TextView

    private lateinit var pingButton: LinearLayout
    private lateinit var pingValue: TextView

    private lateinit var downloadSpeed: TextView
    private lateinit var uploadSpeed: TextView

    private lateinit var btnServers: LinearLayout
    private lateinit var btnRoutes: LinearLayout
    private lateinit var btnSettings: LinearLayout

    private var pulseAnim1: ObjectAnimator? = null
    private var pulseAnim2: ObjectAnimator? = null
    private var pingJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        connectButton = view.findViewById(R.id.connectButton)
        connectIcon = view.findViewById(R.id.connectIcon)
        connectLabel = view.findViewById(R.id.connectLabel)
        connectSub = view.findViewById(R.id.connectSub)

        pulseRing1 = view.findViewById(R.id.pulseRing1)
        pulseRing2 = view.findViewById(R.id.pulseRing2)

        statusChip = view.findViewById(R.id.statusChip)
        statusDot = view.findViewById(R.id.statusDot)
        statusText = view.findViewById(R.id.statusText)

        serverCard = view.findViewById(R.id.serverCard)
        serverFlag = view.findViewById(R.id.serverFlag)
        serverName = view.findViewById(R.id.serverName)
        serverSubtitle = view.findViewById(R.id.serverSubtitle)

        pingButton = view.findViewById(R.id.pingButton)
        pingValue = view.findViewById(R.id.pingValue)

        downloadSpeed = view.findViewById(R.id.downloadSpeed)
        uploadSpeed = view.findViewById(R.id.uploadSpeed)

        btnServers = view.findViewById(R.id.btnServers)
        btnRoutes = view.findViewById(R.id.btnRoutes)
        btnSettings = view.findViewById(R.id.btnSettings)

        // Setup Pulse Animations
        setupPulseAnimations()

        // Connect button click -> Toggle VPN
        connectButton.setOnClickListener {
            (activity as? MainActivity)?.toggleService()
        }

        // Server Card click -> open Servers list
        serverCard.setOnClickListener {
            (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_configuration)
        }

        // Ping click -> Test ping
        pingButton.setOnClickListener {
            testPing()
        }

        // Quick action buttons
        btnServers.setOnClickListener {
            (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_configuration)
        }
        btnRoutes.setOnClickListener {
            (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_route)
        }
        btnSettings.setOnClickListener {
            (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_settings)
        }

        // Sync active state
        val mainActivity = activity as? MainActivity
        updateState(mainActivity?.state ?: BaseService.State.Idle)
        updateActiveProfile()
    }

    private fun setupPulseAnimations() {
        val scaleX1 = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.9f, 1.15f, 0.9f)
        val scaleY1 = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.9f, 1.15f, 0.9f)
        val alpha1 = PropertyValuesHolder.ofFloat(View.ALPHA, 0.2f, 0.6f, 0.2f)
        pulseAnim1 = ObjectAnimator.ofPropertyValuesHolder(pulseRing1, scaleX1, scaleY1, alpha1).apply {
            duration = 2600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
        }

        val scaleX2 = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.85f, 1.25f, 0.85f)
        val scaleY2 = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.85f, 1.25f, 0.85f)
        val alpha2 = PropertyValuesHolder.ofFloat(View.ALPHA, 0.4f, 0.1f, 0.4f)
        pulseAnim2 = ObjectAnimator.ofPropertyValuesHolder(pulseRing2, scaleX2, scaleY2, alpha2).apply {
            duration = 3200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
        }
    }

    fun updateState(state: BaseService.State) {
        val context = context ?: return

        when (state) {
            BaseService.State.Connected -> {
                connectButton.setBackgroundResource(R.drawable.bg_penik_connect_btn)
                connectIcon.text = "⚡"
                connectIcon.setTextColor(ContextCompat.getColor(context, R.color.penik_acc))
                connectLabel.text = "ПОДКЛЮЧЕНО"
                connectSub.text = "VLESS REALITY"

                statusChip.setBackgroundResource(R.drawable.bg_penik_status_chip)
                statusText.text = "АКТИВЕН"
                statusText.setTextColor(ContextCompat.getColor(context, R.color.penik_acc))
                statusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.penik_acc))

                if (pulseAnim1?.isRunning != true) pulseAnim1?.start()
                if (pulseAnim2?.isRunning != true) pulseAnim2?.start()
            }
            BaseService.State.Connecting -> {
                connectButton.setBackgroundResource(R.drawable.bg_penik_connect_btn)
                connectIcon.text = "⌛"
                connectIcon.setTextColor(ContextCompat.getColor(context, R.color.penik_acc2))
                connectLabel.text = "ПОДКЛЮЧЕНИЕ..."
                connectSub.text = "УСТАНОВКА СВЯЗИ"

                statusChip.setBackgroundResource(R.drawable.bg_penik_status_chip)
                statusText.text = "СВЯЗЬ"
                statusText.setTextColor(ContextCompat.getColor(context, R.color.penik_acc2))
                statusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.penik_acc2))

                if (pulseAnim1?.isRunning != true) pulseAnim1?.start()
            }
            BaseService.State.Stopping -> {
                connectLabel.text = "ОТКЛЮЧЕНИЕ..."
                connectSub.text = "ЗАВЕРШЕНИЕ СЕССИИ"
                statusText.text = "СТОП"
            }
            else -> { // Idle / Stopped
                connectButton.setBackgroundResource(R.drawable.bg_penik_connect_btn_off)
                connectIcon.text = "✕"
                connectIcon.setTextColor(ContextCompat.getColor(context, R.color.penik_red))
                connectLabel.text = "ОТКЛЮЧЕНО"
                connectSub.text = "НАЖМИТЕ ДЛЯ СВЯЗИ"

                statusChip.setBackgroundResource(R.drawable.bg_penik_status_chip_off)
                statusText.text = "ОТКЛЮЧЕНО"
                statusText.setTextColor(ContextCompat.getColor(context, R.color.penik_red))
                statusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.penik_red))

                pulseAnim1?.cancel()
                pulseAnim2?.cancel()
                pulseRing1.alpha = 0.2f
                pulseRing2.alpha = 0.2f

                downloadSpeed.text = "0.0 B/s"
                uploadSpeed.text = "0.0 B/s"
            }
        }
        updateActiveProfile()
    }

    fun updateActiveProfile() {
        lifecycleScope.launch {
            val selectedId = DataStore.selectedProxy
            val profile = onDefaultDispatcher {
                if (selectedId > 0) SagerDatabase.proxyDao.getById(selectedId) else null
            }

            if (profile != null) {
                serverName.text = profile.displayName()
                serverSubtitle.text = "10 Gbps · BBR · RAM-only"
                connectSub.text = profile.displayType() ?: "VLESS REALITY"
                serverFlag.text = if (profile.displayName().contains("DE") || profile.displayName().contains("Герм")) "🇩🇪"
                    else if (profile.displayName().contains("NL") || profile.displayName().contains("Нидер")) "🇳🇱"
                    else if (profile.displayName().contains("US") || profile.displayName().contains("США")) "🇺🇸"
                    else if (profile.displayName().contains("RU") || profile.displayName().contains("Росс")) "🇷🇺"
                    else "🌐"
            } else {
                serverName.text = "Выбрать сервер"
                serverSubtitle.text = "Нажмите для списка локаций"
                serverFlag.text = "🌐"
            }
        }
    }

    fun updateTraffic(txRate: Long, rxRate: Long) {
        val context = context ?: return
        val dl = FormatFileSizeCompat.formatFileSize(context, rxRate, DataStore.useIECUnit)
        val ul = FormatFileSizeCompat.formatFileSize(context, txRate, DataStore.useIECUnit)
        downloadSpeed.text = "$dl/s"
        uploadSpeed.text = "$ul/s"
    }

    private fun testPing() {
        val mainActivity = activity as? MainActivity ?: return
        if (mainActivity.state != BaseService.State.Connected) {
            pingValue.text = "—"
            return
        }

        pingJob?.cancel()
        pingValue.text = "..."
        pingJob = lifecycleScope.launch {
            try {
                val elapsed = onDefaultDispatcher {
                    mainActivity.urlTest()
                }
                pingValue.text = if (elapsed > 0) "$elapsed ms" else "err"
            } catch (_: Exception) {
                pingValue.text = "err"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateActiveProfile()
        val mainActivity = activity as? MainActivity
        if (mainActivity != null) {
            updateState(mainActivity.state)
        }
    }

    override fun onDestroyView() {
        pulseAnim1?.cancel()
        pulseAnim2?.cancel()
        pingJob?.cancel()
        super.onDestroyView()
    }
}
