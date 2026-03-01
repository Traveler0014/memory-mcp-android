package me.travon.memory

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

class NSDHelper(
    context: Context, 
    private val port: Int,
    private val serviceName: String = "MemoryMCP"
) {

    private val nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val serviceType = "_mcp._tcp" 

    fun registerService() {
        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = this@NSDHelper.serviceName
            this.serviceType = this@NSDHelper.serviceType
            this.port = this@NSDHelper.port
            // Add metadata for MCP clients
            this.setAttribute("path", "/mcp")
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredInfo: NsdServiceInfo) {
                Log.d("NSDHelper", "Service registered: ${registeredInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("NSDHelper", "Registration failed: $errorCode")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.d("NSDHelper", "Service unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("NSDHelper", "Unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                registrationListener
            )
        } catch (e: Exception) {
            Log.e("NSDHelper", "Failed to register NSD service", e)
        }
    }

    fun unregisterService() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                Log.e("NSDHelper", "Error unregistering NSD service", e)
            }
        }
    }
}
