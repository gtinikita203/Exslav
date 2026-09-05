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

package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.SubscriptionType
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

@Suppress("EXPERIMENTAL_API_USAGE")
abstract class GroupUpdater {

    abstract suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    )

    data class Progress(
        var max: Int
    ) {
        var progress by AtomicInteger()
    }

    companion object {

        val updating = Collections.synchronizedSet<Long>(mutableSetOf())
        val progress = Collections.synchronizedMap<Long, Progress>(mutableMapOf())

        fun startUpdate(proxyGroup: ProxyGroup, byUser: Boolean) {
            runOnDefaultDispatcher {
                executeUpdate(proxyGroup, byUser)
            }
        }

        suspend fun executeUpdate(proxyGroup: ProxyGroup, byUser: Boolean): Boolean {
            return coroutineScope {
                if (!updating.add(proxyGroup.id)) cancel()
                GroupManager.postReload(proxyGroup.id)

                val subscription = proxyGroup.subscription!!
                val connected = SagerNet.started && DataStore.startedProfile > 0
                val userInterface = GroupManager.userInterface

                if (subscription.updateWhenConnectedOnly && !connected) {
                    if (!byUser || userInterface == null) {
                        finishUpdate(proxyGroup)
                        cancel()
                    } else {
                        if (!userInterface.confirm(app.getString(R.string.update_subscription_warning))) {
                            finishUpdate(proxyGroup)
                            cancel()
                        }
                    }
                }

                try {
                    when (subscription.type) {
                        SubscriptionType.RAW -> RawUpdater
                        SubscriptionType.SIP008 -> SIP008Updater
                        SubscriptionType.AGE -> AgeUpdater
                        else -> error("unsupported")
                    }.doUpdate(proxyGroup, subscription, userInterface, byUser)
                    true
                } catch (e: Throwable) {
                    Logs.w(e)
                    if (byUser && userInterface != null) {
                        userInterface.onUpdateFailure(proxyGroup, e.readableMessage)
                    }
                    finishUpdate(proxyGroup)
                    false
                }
            }
        }


        suspend fun finishUpdate(proxyGroup: ProxyGroup) {
            updating.remove(proxyGroup.id)
            progress.remove(proxyGroup.id)
            GroupManager.postUpdate(proxyGroup)
        }

        fun extractSubscriptionTitle(getHeader: (String) -> String): String? {
            val headersToTry = listOf(
                "Profile-Title",
                "profile-title",
                "Subscription-Title",
                "subscription-title",
                "X-Profile-Title",
                "x-profile-title"
            )
            for (headerName in headersToTry) {
                val value = getHeader(headerName)
                if (value.isNotBlank()) {
                    val trimmed = value.trim()
                    val decoded = if (trimmed.startsWith("base64:", ignoreCase = true)) {
                        try {
                            String(android.util.Base64.decode(trimmed.substring(7).trim(), android.util.Base64.DEFAULT), Charsets.UTF_8).trim()
                        } catch (_: Throwable) {
                            trimmed.substring(7).trim()
                        }
                    } else {
                        try {
                            java.net.URLDecoder.decode(trimmed, "UTF-8").trim()
                        } catch (_: Throwable) {
                            trimmed
                        }
                    }
                    if (decoded.isNotBlank()) return decoded
                }
            }

            val disposition = getHeader("Content-Disposition").takeIf { it.isNotBlank() }
                ?: getHeader("content-disposition").takeIf { it.isNotBlank() }
            if (!disposition.isNullOrBlank()) {
                val filenameStarRegex = Regex("""filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)""", RegexOption.IGNORE_CASE)
                val filenameNormalRegex = Regex("""filename\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                val filenameBareRegex = Regex("""filename\s*=\s*([^; ]+)""", RegexOption.IGNORE_CASE)

                val rawFilename = filenameStarRegex.find(disposition)?.groupValues?.get(1)
                    ?: filenameNormalRegex.find(disposition)?.groupValues?.get(1)
                    ?: filenameBareRegex.find(disposition)?.groupValues?.get(1)

                if (!rawFilename.isNullOrBlank()) {
                    val decoded = try {
                        java.net.URLDecoder.decode(rawFilename.trim(), "UTF-8")
                    } catch (_: Throwable) {
                        rawFilename.trim()
                    }
                    val cleaned = decoded.replace(Regex("""\.(txt|yaml|yml|json|conf|sub)$""", RegexOption.IGNORE_CASE), "").trim()
                    if (cleaned.isNotBlank()) return cleaned
                }
            }

            return null
        }

        fun extractSubscriptionUpdateInterval(getHeader: (String) -> String): Int? {
            val headersToTry = listOf(
                "Profile-Update-Interval",
                "profile-update-interval",
                "Subscription-Update-Interval",
                "subscription-update-interval"
            )
            for (headerName in headersToTry) {
                val value = getHeader(headerName)
                if (value.isNotBlank()) {
                    val num = value.trim().toIntOrNull()
                    if (num != null && num > 0) {
                        return if (num <= 168) num * 60 else num
                    }
                }
            }
            return null
        }
    }

}