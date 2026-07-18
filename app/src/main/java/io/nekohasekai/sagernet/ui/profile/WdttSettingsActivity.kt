package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.wdtt.WdttBean

class WdttSettingsActivity : ProfileSettingsActivity<WdttBean>() {

    override fun createEntity() = WdttBean()

    override fun WdttBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverWdttWorkers = workers
        DataStore.serverWdttHashes = vkHashes
    }

    override fun WdttBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        workers = (DataStore.serverWdttWorkers ?: 27).coerceIn(12, 108)
        vkHashes = DataStore.serverWdttHashes ?: ""
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.wdtt_preferences)

        val workersPref = findPreference<SeekBarPreference>(Key.WDTT_WORKERS)!!
        workersPref.apply {
            summaryProvider = Preference.SummaryProvider<SeekBarPreference> { pref ->
                val hashesText = DataStore.serverWdttHashes ?: ""
                val uniqueHashes = hashesText.split(Regex("[,\\s\\n]+"))
                    .filter { it.isNotBlank() && it.length >= 16 }
                    .distinct()
                val filledHashCount = uniqueHashes.size
                val maxWorkers = filledHashCount.coerceAtLeast(1) * 27
                val currentVal = pref.value
                "Всего потоков: $currentVal. Для $filledHashCount хэш(ей) лимит: $maxWorkers потоков (27 на каждый хэш)."
            }
        }

        val updateWorkersLimit = {
            val hashesText = DataStore.serverWdttHashes ?: ""
            val uniqueHashes = hashesText.split(Regex("[,\\s\\n]+"))
                .filter { it.isNotBlank() && it.length >= 16 }
                .distinct()
            val filledHashCount = uniqueHashes.size
            val maxWorkers = (filledHashCount.coerceAtLeast(1) * 27).coerceIn(12, 108)

            workersPref.max = maxWorkers
            if (workersPref.value > maxWorkers) {
                workersPref.value = maxWorkers
            }
        }

        updateWorkersLimit()

        findPreference<EditTextPreference>(Key.VK_HASHES)!!.apply {
            setOnPreferenceChangeListener { _, _ ->
                listView.post {
                    updateWorkersLimit()
                    workersPref.summaryProvider = workersPref.summaryProvider
                }
                true
            }
        }
    }
}
