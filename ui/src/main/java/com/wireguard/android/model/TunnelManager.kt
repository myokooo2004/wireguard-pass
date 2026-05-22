/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.model

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.databinding.BaseObservable
import androidx.databinding.Bindable
import com.wireguard.android.Application.Companion.get
import com.wireguard.android.Application.Companion.getBackend
import com.wireguard.android.Application.Companion.getTunnelManager
import com.wireguard.android.BR
import com.wireguard.android.R
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.configStore.ConfigStore
import com.wireguard.android.databinding.ObservableSortedKeyedArrayList
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.util.UserKnobs
import com.wireguard.android.util.applicationScope
import com.wireguard.config.Config
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Maintains and mediates changes to the set of available WireGuard tunnels,
 */
class TunnelManager(private val configStore: ConfigStore) : BaseObservable() {
    private val tunnels = CompletableDeferred<ObservableSortedKeyedArrayList<String, ObservableTunnel>>()
    private val context: Context = get()
    private val tunnelMap: ObservableSortedKeyedArrayList<String, ObservableTunnel> = ObservableSortedKeyedArrayList(TunnelComparator)
    private var haveLoaded = false

    private fun addToList(name: String, config: Config?, state: Tunnel.State): ObservableTunnel {
        val tunnel = ObservableTunnel(this, name, config, state)
        tunnelMap.add(tunnel)
        return tunnel
    }

    suspend fun getTunnels(): ObservableSortedKeyedArrayList<String, ObservableTunnel> = tunnels.await()

    suspend fun create(name: String, config: Config?): ObservableTunnel = withContext(Dispatchers.Main.immediate) {
        [span_0](start_span)// IP Address နာမည် (ဥပမာ- 162.159.192.13) ဖြစ်နေပါက မူရင်းစစ်ဆေးချက်ကို ကျော်လွန်ခွင့်ပြုရန် စစ်ဆေးပါသည်[span_0](end_span)
        val isIpAddressName = name.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))

        [span_1](start_span)if (!isIpAddressName && Tunnel.isNameInvalid(name))[span_1](end_span)
            [span_2](start_span)throw IllegalArgumentException(context.getString(R.string.tunnel_error_invalid_name))[span_2](end_span)
        [span_3](start_span)if (tunnelMap.containsKey(name))[span_3](end_span)
            [span_4](start_span)throw IllegalArgumentException(context.getString(R.string.tunnel_error_already_exists, name))[span_4](end_span)
        [span_5](start_span)addToList(name, withContext(Dispatchers.IO) { configStore.create(name, config!!) }, Tunnel.State.DOWN)[span_5](end_span)
    }

    suspend fun delete(tunnel: ObservableTunnel) = withContext(Dispatchers.Main.immediate) {
        [span_6](start_span)val originalState = tunnel.state[span_6](end_span)
        [span_7](start_span)val wasLastUsed = tunnel == lastUsedTunnel[span_7](end_span)
        // Make sure nothing touches the tunnel.
        [span_8](start_span)if (wasLastUsed)[span_8](end_span)
            [span_9](start_span)lastUsedTunnel = null[span_9](end_span)
        [span_10](start_span)tunnelMap.remove(tunnel)[span_10](end_span)
        try {
            [span_11](start_span)if (originalState == Tunnel.State.UP)[span_11](end_span)
                [span_12](start_span)withContext(Dispatchers.IO) { getBackend().setState(tunnel, Tunnel.State.DOWN, null) }[span_12](end_span)
            try {
                [span_13](start_span)withContext(Dispatchers.IO) { configStore.delete(tunnel.name) }[span_13](end_span)
            } catch (e: Throwable) {
                [span_14](start_span)if (originalState == Tunnel.State.UP)[span_14](end_span)
                    [span_15](start_span)withContext(Dispatchers.IO) { getBackend().setState(tunnel, Tunnel.State.UP, tunnel.config) }[span_15](end_span)
                [span_16](start_span)throw e[span_16](end_span)
            }
        [span_17](start_span)} catch (e: Throwable) {[span_17](end_span)
            // Failure, put the tunnel back.
            [span_18](start_span)tunnelMap.add(tunnel)[span_18](end_span)
            [span_19](start_span)if (wasLastUsed)[span_19](end_span)
                [span_20](start_span)lastUsedTunnel = tunnel[span_20](end_span)
            [span_21](start_span)throw e[span_21](end_span)
        }
    }

    @get:Bindable
    var lastUsedTunnel: ObservableTunnel? [span_22](start_span)= null[span_22](end_span)
        private set(value) {
            [span_23](start_span)if (value == field) return[span_23](end_span)
            [span_24](start_span)field = value[span_24](end_span)
            [span_25](start_span)notifyPropertyChanged(BR.lastUsedTunnel)[span_25](end_span)
            [span_26](start_span)applicationScope.launch { UserKnobs.setLastUsedTunnel(value?.name) }[span_26](end_span)
        }

    suspend fun getTunnelConfig(tunnel: ObservableTunnel): Config = withContext(Dispatchers.Main.immediate) {
        [span_27](start_span)tunnel.onConfigChanged(withContext(Dispatchers.IO) { configStore.load(tunnel.name) })[span_27](end_span)!!
    }

    fun onCreate() {
        applicationScope.launch {
            try {
                [span_28](start_span)onTunnelsLoaded(withContext(Dispatchers.IO) { configStore.enumerate() }, withContext(Dispatchers.IO) { getBackend().runningTunnelNames })[span_28](end_span)
            } catch (e: Throwable) {
                [span_29](start_span)Log.e(TAG, Log.getStackTraceString(e))[span_29](end_span)
            }
        [span_30](start_span)}
    }

    private fun onTunnelsLoaded(present: Iterable<String>, running: Collection<String>) {
        for (name in present)[span_30](end_span)
            [span_31](start_span)addToList(name, null, if (running.contains(name)) Tunnel.State.UP else Tunnel.State.DOWN)[span_31](end_span)
        applicationScope.launch {
            [span_32](start_span)val lastUsedName = UserKnobs.lastUsedTunnel.first()[span_32](end_span)
            [span_33](start_span)if (lastUsedName != null)[span_33](end_span)
                [span_34](start_span)lastUsedTunnel = tunnelMap[lastUsedName][span_34](end_span)
            [span_35](start_span)haveLoaded = true[span_35](end_span)
            [span_36](start_span)restoreState(true)[span_36](end_span)
            [span_37](start_span)tunnels.complete(tunnelMap)[span_37](end_span)
        }
    }

    private fun refreshTunnelStates() {
        applicationScope.launch {
            try {
                [span_38](start_span)val running = withContext(Dispatchers.IO) { getBackend().runningTunnelNames }[span_38](end_span)
                [span_39](start_span)for (tunnel in tunnelMap)[span_39](end_span)
                    [span_40](start_span)tunnel.onStateChanged(if (running.contains(tunnel.name)) Tunnel.State.UP else Tunnel.State.DOWN)[span_40](end_span)
            } catch (e: Throwable) {
                [span_41](start_span)Log.e(TAG, Log.getStackTraceString(e))[span_41](end_span)
            }
        [span_42](start_span)}
    }

    suspend fun restoreState(force: Boolean) {
        if (!haveLoaded ||[span_42](end_span)
            (!force && !UserKnobs.restoreOnBoot.first()[span_43](start_span)))[span_43](end_span)
            [span_44](start_span)return[span_44](end_span)
        [span_45](start_span)val previouslyRunning = UserKnobs.runningTunnels.first()[span_45](end_span)
        [span_46](start_span)if (previouslyRunning.isEmpty()) return[span_46](end_span)
        withContext(Dispatchers.IO) {
            try {
                [span_47](start_span)tunnelMap.filter { previouslyRunning.contains(it.name) }.map { async(Dispatchers.IO + SupervisorJob()) { setTunnelState(it, Tunnel.State.UP) } }[span_47](end_span)
                    [span_48](start_span).awaitAll()[span_48](end_span)
            } catch (e: Throwable) {
                [span_49](start_span)Log.e(TAG, Log.getStackTraceString(e))[span_49](end_span)
            }
        }
    }

    suspend fun saveState() {
        [span_50](start_span)UserKnobs.setRunningTunnels(tunnelMap.filter { it.state == Tunnel.State.UP }.map { it.name }.toSet())[span_50](end_span)
    }

    [span_51](start_span)suspend fun setTunnelConfig(tunnel: ObservableTunnel, config: Config): Config = withContext(Dispatchers.Main.immediate) {[span_51](end_span)
        [span_52](start_span)tunnel.onConfigChanged(withContext(Dispatchers.IO) {[span_52](end_span)
            [span_53](start_span)getBackend().setState(tunnel, tunnel.state, config)[span_53](end_span)
            [span_54](start_span)configStore.save(tunnel.name, config)[span_54](end_span)
        [span_55](start_span)})[span_55](end_span)!!
    }

    [span_56](start_span)suspend fun setTunnelName(tunnel: ObservableTunnel, name: String): String = withContext(Dispatchers.Main.immediate) {[span_56](end_span)
        [span_57](start_span)// นာမည်ပြန်ပြင်သည့်နေရာတွင်လည်း IP Address ပုံစံဖြစ်ပါက ခွင့်ပြုရန် စစ်ဆေးပါသည်[span_57](end_span)
        val isIpAddressName = name.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))

        [span_58](start_span)if (!isIpAddressName && Tunnel.isNameInvalid(name))[span_58](end_span)
            [span_59](start_span)throw IllegalArgumentException(context.getString(R.string.tunnel_error_invalid_name))[span_59](end_span)
        [span_60](start_span)if (tunnelMap.containsKey(name)) {[span_60](end_span)
            [span_61](start_span)throw IllegalArgumentException(context.getString(R.string.tunnel_error_already_exists, name))[span_61](end_span)
        }
        [span_62](start_span)val originalState = tunnel.state[span_62](end_span)
        [span_63](start_span)val wasLastUsed = tunnel == lastUsedTunnel[span_63](end_span)
        // Make sure nothing touches the tunnel.
        [span_64](start_span)if (wasLastUsed)[span_64](end_span)
            [span_65](start_span)lastUsedTunnel = null[span_65](end_span)
        [span_66](start_span)tunnelMap.remove(tunnel)[span_66](end_span)
        var throwable: Throwable? [span_67](start_span)= null[span_67](end_span)
        var newName: String? [span_68](start_span)= null[span_68](end_span)
        try {
            [span_69](start_span)if (originalState == Tunnel.State.UP)[span_69](end_span)
                [span_70](start_span)withContext(Dispatchers.IO) { getBackend().setState(tunnel, Tunnel.State.DOWN, null) }[span_70](end_span)
            [span_71](start_span)withContext(Dispatchers.IO) { configStore.rename(tunnel.name, name) }[span_71](end_span)
            [span_72](start_span)newName = tunnel.onNameChanged(name)[span_72](end_span)
            [span_73](start_span)if (originalState == Tunnel.State.UP)[span_73](end_span)
                [span_74](start_span)withContext(Dispatchers.IO) { getBackend().setState(tunnel, Tunnel.State.UP, tunnel.config) }[span_74](end_span)
        [span_75](start_span)} catch (e: Throwable) {[span_75](end_span)
            [span_76](start_span)throwable = e[span_76](end_span)
            // On failure, we don't know what state the tunnel might be in. Fix that.
            [span_77](start_span)getTunnelState(tunnel)[span_77](end_span)
        }
        // Add the tunnel back to the manager, under whatever name it thinks it has.
        [span_78](start_span)tunnelMap.add(tunnel)[span_78](end_span)
        [span_79](start_span)if (wasLastUsed)[span_79](end_span)
            [span_80](start_span)lastUsedTunnel = tunnel[span_80](end_span)
        [span_81](start_span)if (throwable != null)[span_81](end_span)
            [span_82](start_span)throw throwable[span_82](end_span)
        [span_83](start_span)newName[span_83](end_span)!!
    }

    [span_84](start_span)suspend fun setTunnelState(tunnel: ObservableTunnel, state: Tunnel.State): Tunnel.State = withContext(Dispatchers.Main.immediate) {[span_84](end_span)
        [span_85](start_span)var newState = tunnel.state[span_85](end_span)
        var throwable: Throwable? [span_86](start_span)= null[span_86](end_span)
        try {
            [span_87](start_span)newState = withContext(Dispatchers.IO) { getBackend().setState(tunnel, state, tunnel.getConfigAsync()) }[span_87](end_span)
            [span_88](start_span)if (newState == Tunnel.State.UP)[span_88](end_span)
                [span_89](start_span)lastUsedTunnel = tunnel[span_89](end_span)
        [span_90](start_span)} catch (e: Throwable) {[span_90](end_span)
            [span_91](start_span)throwable = e[span_91](end_span)
        }
        [span_92](start_span)tunnel.onStateChanged(newState)[span_92](end_span)
        [span_93](start_span)saveState()[span_93](end_span)
        [span_94](start_span)if (throwable != null)[span_94](end_span)
            [span_95](start_span)throw throwable[span_95](end_span)
        [span_96](start_span)newState[span_96](end_span)
    }

    class IntentReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            applicationScope.launch {
                [span_97](start_span)val manager = getTunnelManager()[span_97](end_span)
                [span_98](start_span)if (intent == null) return@launch[span_98](end_span)
                [span_99](start_span)val action = intent.action ?: return@launch[span_99](end_span)
                [span_100](start_span)if ("com.wireguard.android.action.REFRESH_TUNNEL_STATES" == action) {[span_100](end_span)
                    [span_101](start_span)manager.refreshTunnelStates()[span_101](end_span)
                    return@launch
                }
                [span_102](start_span)if (!UserKnobs.allowRemoteControlIntents.first())[span_102](end_span)
                    [span_103](start_span)return@launch[span_103](end_span)
                [span_104](start_span)val state = when (action) {[span_104](end_span)
                    [span_105](start_span)"com.wireguard.android.action.SET_TUNNEL_UP" -> Tunnel.State.UP[span_105](end_span)
                    [span_106](start_span)"com.wireguard.android.action.SET_TUNNEL_DOWN" -> Tunnel.State.DOWN[span_106](end_span)
                    [span_107](start_span)else -> return@launch[span_107](end_span)
                }
                [span_108](start_span)val tunnelName = intent.getStringExtra("tunnel") ?: return@launch[span_108](end_span)
                [span_109](start_span)val tunnels = manager.getTunnels()[span_109](end_span)
                [span_110](start_span)val tunnel = tunnels[tunnelName] ?: return@launch[span_110](end_span)
                try {
                    [span_111](start_span)manager.setTunnelState(tunnel, state)[span_111](end_span)
                [span_112](start_span)} catch (e: Throwable) {[span_112](end_span)
                    [span_113](start_span)Toast.makeText(context, ErrorMessages[e], Toast.LENGTH_LONG).show()[span_113](end_span)
                }
            }
        }
    }

    [span_114](start_span)suspend fun getTunnelState(tunnel: ObservableTunnel): Tunnel.State = withContext(Dispatchers.Main.immediate) {[span_114](end_span)
        [span_115](start_span)tunnel.onStateChanged(withContext(Dispatchers.IO) { getBackend().getState(tunnel) })[span_115](end_span)
    }

    [span_116](start_span)suspend fun getTunnelStatistics(tunnel: ObservableTunnel): Statistics = withContext(Dispatchers.Main.immediate) {[span_116](end_span)
        [span_117](start_span)tunnel.onStatisticsChanged(withContext(Dispatchers.IO) { getBackend().getStatistics(tunnel) })[span_117](end_span)!!
    }

    companion object {
        private const val TAG = "WireGuard/TunnelManager"
    }
}
