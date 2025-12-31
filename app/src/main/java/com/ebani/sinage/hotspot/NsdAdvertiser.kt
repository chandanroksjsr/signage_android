package com.ebani.sinage.hotspot

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

class NsdAdvertiser(private val context: Context) {
    private val nsd by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
    private var reg: NsdManager.RegistrationListener? = null

    fun register(type: String = "_http._tcp.", name: String, port: Int) {
        unregister()
        val info = NsdServiceInfo().apply {
            serviceType = type       // "_http._tcp."
            serviceName = name       // "Sinage-Setup"
            this.port = port         // picked port
        }
        reg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { /* ok */ }
            override fun onRegistrationFailed(i: NsdServiceInfo, error: Int) { /* log if needed */ }
            override fun onServiceUnregistered(i: NsdServiceInfo) { /* ok */ }
            override fun onUnregistrationFailed(i: NsdServiceInfo, error: Int) { /* log if needed */ }
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg)
    }

    fun unregister() {
        reg?.let { runCatching { nsd.unregisterService(it) } }
        reg = null
    }
}
