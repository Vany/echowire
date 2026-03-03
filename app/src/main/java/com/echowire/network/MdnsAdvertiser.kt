package com.echowire.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

/**
 * mDNS service advertiser for WebSocket server discovery.
 *
 * Advertises a service on the local network using mDNS/DNS-SD protocol.
 * Manages multicast lock acquisition/release for WiFi compatibility.
 *
 * Thread-safe: All mDNS operations run on NsdManager's internal thread.
 *
 * @param context Android context for system services
 * @param serviceType mDNS service type (e.g., "_echowire._tcp.")
 * @param serviceName Human-readable service name
 * @param onError Error callback for registration failures
 */
class MdnsAdvertiser(
    private val context: Context,
    private val serviceType: String,
    private val serviceName: String,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "MdnsAdvertiser"
    }

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile
    private var isRegistered: Boolean = false

    /**
     * Register mDNS service with specified port.
     *
     * CRITICAL: Acquires multicast lock before registration.
     * Lock is released on error or during unregister().
     *
     * @param port WebSocket server port to advertise
     */
    fun register(port: Int) {
        if (isRegistered) {
            Log.w(TAG, "Service already registered")
            return
        }

        try {
            // Check WiFi status
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wifiManager.isWifiEnabled) {
                Log.w(TAG, "WiFi is not enabled, mDNS registration may fail")
            }

            // Acquire multicast lock (required for mDNS on WiFi)
            val lock = wifiManager.createMulticastLock("MdnsAdvertiser").apply {
                setReferenceCounted(true)
                acquire()
            }
            multicastLock = lock
            Log.d(TAG, "Multicast lock acquired")

            try {
                nsdManager = context.getSystemService(NsdManager::class.java)

                val serviceInfo = NsdServiceInfo().apply {
                    this.serviceName = this@MdnsAdvertiser.serviceName
                    this.serviceType = this@MdnsAdvertiser.serviceType
                    this.port = port
                }

                Log.d(TAG, "Registering mDNS: name=$serviceName, type=$serviceType, port=$port")

                registrationListener = object : NsdManager.RegistrationListener {
                    override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        val errorMsg = when (errorCode) {
                            NsdManager.FAILURE_ALREADY_ACTIVE -> "Service already registered"
                            NsdManager.FAILURE_INTERNAL_ERROR -> "Internal error"
                            NsdManager.FAILURE_MAX_LIMIT -> "Max registrations reached"
                            else -> "Unknown error code: $errorCode"
                        }
                        Log.e(TAG, "mDNS registration failed: $errorMsg")
                        onError("mDNS registration failed: $errorMsg")
                        isRegistered = false
                    }

                    override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e(TAG, "mDNS unregistration failed: $errorCode")
                        isRegistered = false
                    }

                    override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                        Log.i(TAG, "mDNS service registered: ${serviceInfo.serviceName}")
                        isRegistered = true
                    }

                    override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                        Log.i(TAG, "mDNS service unregistered: ${serviceInfo.serviceName}")
                        isRegistered = false
                    }
                }

                nsdManager?.registerService(
                    serviceInfo,
                    NsdManager.PROTOCOL_DNS_SD,
                    registrationListener
                )

            } catch (e: Exception) {
                // CRITICAL: Release multicast lock on registration failure
                Log.e(TAG, "Failed to register mDNS service", e)
                onError("mDNS registration error: ${e.message}")

                // Clean up multicast lock
                try {
                    if (lock.isHeld) {
                        lock.release()
                        Log.d(TAG, "Multicast lock released after failure")
                    }
                } catch (releaseError: Exception) {
                    Log.e(TAG, "Failed to release multicast lock after error", releaseError)
                }
                multicastLock = null
                throw e
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup mDNS", e)
            isRegistered = false
        }
    }

    /**
     * Unregister mDNS service and release resources.
     * Safe to call multiple times.
     */
    fun unregister() {
        if (!isRegistered) {
            Log.d(TAG, "Service not registered, nothing to unregister")
            return
        }

        try {
            registrationListener?.let { listener ->
                nsdManager?.unregisterService(listener)
            }
            registrationListener = null
            nsdManager = null

            // Release multicast lock
            multicastLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                    Log.d(TAG, "Multicast lock released")
                }
            }
            multicastLock = null

            isRegistered = false
            Log.i(TAG, "mDNS service unregistered")

        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering mDNS service", e)
            isRegistered = false
        }
    }

    /**
     * Check if service is currently registered.
     */
    fun isRegistered(): Boolean = isRegistered
}
