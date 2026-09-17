package io.nekohasekai.sagernet.group

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.RouteMode
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import java.nio.charset.StandardCharsets

object HappRoutingHelper {

    data class ImportResult(
        val success: Boolean,
        val name: String = "",
        val rulesCount: Int = 0,
        val message: String? = null
    )

    fun isRoutingLink(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.startsWith("happ://routing/onadd/", ignoreCase = true) ||
                trimmed.startsWith("penik://routing/onadd/", ignoreCase = true) ||
                (trimmed.contains("/routing/onadd/", ignoreCase = true))
    }

    fun extractBase64(text: String): String {
        val trimmed = text.trim()
        val prefixIdx = trimmed.indexOf("/onadd/", ignoreCase = true)
        return if (prefixIdx != -1) {
            trimmed.substring(prefixIdx + "/onadd/".length).trim()
        } else if (trimmed.startsWith("happ://", ignoreCase = true) || trimmed.startsWith("penik://", ignoreCase = true)) {
            trimmed.substringAfter("://").substringAfter("/").trim()
        } else {
            trimmed
        }
    }

    suspend fun parseAndApply(routingRaw: String, enableRouting: Boolean = true): ImportResult {
        return try {
            val b64 = extractBase64(routingRaw)
            val jsonBytes = try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (_: Throwable) {
                Base64.decode(b64, Base64.URL_SAFE)
            }
            val jsonStr = String(jsonBytes, StandardCharsets.UTF_8).trim()
            val json = Gson().fromJson(jsonStr, JsonObject::class.java)
                ?: return ImportResult(false, message = "Invalid JSON structure")

            val profileName = json.get("Name")?.asString?.takeIf { it.isNotBlank() } ?: "PenikVPN"

            // 1. DNS settings
            val remoteDns = json.get("RemoteDNSDomain")?.asString?.takeIf { it.isNotBlank() }
                ?: json.get("RemoteDns")?.asString?.takeIf { it.isNotBlank() }
            if (!remoteDns.isNullOrBlank()) {
                DataStore.remoteDns = remoteDns
            }

            val domesticDns = json.get("DomesticDNSDomain")?.asString?.takeIf { it.isNotBlank() }
                ?: json.get("DomesticDns")?.asString?.takeIf { it.isNotBlank() }
            if (!domesticDns.isNullOrBlank()) {
                DataStore.directDns = domesticDns
                DataStore.useLocalDnsAsDirectDns = false
            }

            // 2. GeoIP / Geosite URLs
            val geoipUrl = json.get("Geoipurl")?.asString?.takeIf { it.isNotBlank() }
            if (!geoipUrl.isNullOrBlank()) {
                DataStore.rulesGeoipUrl = geoipUrl
            }

            val geositeUrl = json.get("Geositeurl")?.asString?.takeIf { it.isNotBlank() }
            if (!geositeUrl.isNullOrBlank()) {
                DataStore.rulesGeositeUrl = geositeUrl
            }

            // 3. DNS Hosts
            val dnsHostsObj = json.getAsJsonObject("DnsHosts")
            if (dnsHostsObj != null && dnsHostsObj.entrySet().isNotEmpty()) {
                val existingHosts = DataStore.dnsHosts.lines().filter { it.isNotBlank() }.toMutableList()
                for ((host, ipEl) in dnsHostsObj.entrySet()) {
                    val ip = ipEl.asString
                    if (existingHosts.none { it.startsWith("$host ") }) {
                        existingHosts.add("$host $ip")
                    }
                }
                DataStore.dnsHosts = existingHosts.joinToString("\n")
            }

            // 4. Parse Rules
            fun extractList(key: String): List<String> {
                val array = json.getAsJsonArray(key) ?: return emptyList()
                return array.mapNotNull { it.asString?.trim()?.takeIf { s -> s.isNotEmpty() } }
            }

            val directSites = extractList("DirectSites")
            val directIps = extractList("DirectIp")
            val proxySites = extractList("ProxySites")
            val proxyIps = extractList("ProxyIp")
            val blockSites = extractList("BlockSites")
            val blockIps = extractList("BlockIp")

            val routeOrder = json.get("RouteOrder")?.asString?.lowercase() ?: "block-proxy-direct"
            val orderTokens = routeOrder.split("-").map { it.trim() }

            val newRules = mutableListOf<RuleEntity>()

            for (token in orderTokens) {
                when (token) {
                    "block" -> {
                        if (blockSites.isNotEmpty() || blockIps.isNotEmpty()) {
                            newRules.add(
                                RuleEntity(
                                    name = "$profileName - Блокировка (Block)",
                                    enabled = true,
                                    outbound = -2L,
                                    domains = blockSites.joinToString("\n"),
                                    ip = blockIps.joinToString("\n")
                                )
                            )
                        }
                    }
                    "proxy" -> {
                        if (proxySites.isNotEmpty() || proxyIps.isNotEmpty()) {
                            newRules.add(
                                RuleEntity(
                                    name = "$profileName - Прокси (Proxy)",
                                    enabled = true,
                                    outbound = 0L,
                                    domains = proxySites.joinToString("\n"),
                                    ip = proxyIps.joinToString("\n")
                                )
                            )
                        }
                    }
                    "direct" -> {
                        if (directSites.isNotEmpty() || directIps.isNotEmpty()) {
                            newRules.add(
                                RuleEntity(
                                    name = "$profileName - Прямо (Direct / RU)",
                                    enabled = true,
                                    outbound = -1L,
                                    domains = directSites.joinToString("\n"),
                                    ip = directIps.joinToString("\n")
                                )
                            )
                        }
                    }
                }
            }

            // 5. Apply rules to Database
            if (newRules.isNotEmpty()) {
                val allExisting = SagerDatabase.rulesDao.allRules()
                val oldForProfile = allExisting.filter { it.name.startsWith("$profileName - ") }
                if (oldForProfile.isNotEmpty()) {
                    SagerDatabase.rulesDao.deleteRules(oldForProfile)
                }

                val currentMaxOrder = SagerDatabase.rulesDao.nextOrder() ?: 1L
                newRules.forEachIndexed { index, rule ->
                    rule.userOrder = currentMaxOrder + index
                }
                SagerDatabase.rulesDao.insert(newRules)
            }

            // 6. Enable Route Mode
            if (enableRouting) {
                DataStore.routeMode = RouteMode.RULE
            }

            ProfileManager.postReload()

            ImportResult(true, name = profileName, rulesCount = newRules.size)
        } catch (e: Exception) {
            ImportResult(false, message = e.localizedMessage ?: e.toString())
        }
    }
}
