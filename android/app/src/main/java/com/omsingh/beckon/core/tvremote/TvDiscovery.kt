package com.omsingh.beckon.core.tvremote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A TV found on the network. */
data class FoundTv(val name: String, val host: String)

/** Looks for Android TVs and Google TVs on the Wi-Fi network, the way the Google TV app does. */
class TvDiscovery(context: Context) {
    private val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val _found = MutableStateFlow<List<FoundTv>>(emptyList())
    val found: StateFlow<List<FoundTv>> = _found.asStateFlow()

    private var listener: NsdManager.DiscoveryListener? = null
    private val waiting = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    @Synchronized
    fun start() {
        if (listener != null || manager == null) return
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = stop()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(this@TvDiscovery) { waiting.addLast(info) }
                resolveNext()
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                _found.update { list -> list.filterNot { it.name == info.serviceName } }
            }
        }
        listener = discovery
        try {
            manager.discoverServices(TvProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (_: Exception) {
            listener = null
        }
    }

    @Synchronized
    fun stop() {
        val active = listener ?: return
        listener = null
        waiting.clear()
        resolving = false
        try { manager?.stopServiceDiscovery(active) } catch (_: Exception) {}
    }

    // Names are looked up one at a time: a second lookup started too soon is refused.
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val next: NsdServiceInfo
        synchronized(this) {
            if (resolving) return
            next = waiting.removeFirstOrNull() ?: return
            resolving = true
        }
        try {
            manager?.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = done()
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress
                    if (host != null) {
                        _found.update { list -> list.filterNot { it.host == host } + FoundTv(info.serviceName, host) }
                    }
                    done()
                }
            })
        } catch (_: Exception) {
            done()
        }
    }

    private fun done() {
        synchronized(this) { resolving = false }
        resolveNext()
    }
}
