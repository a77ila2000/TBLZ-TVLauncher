package com.tblz.tvlauncher.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object NetworkValidator {
    private const val TAG = "NetworkValidator"

    /**
     * NET_CAPABILITY_INTERNET on its own is not enough here. Waking this box tears Wi-Fi
     * down and re-associates, and for the first seconds of that the pre-sleep network is
     * still reported as active with the INTERNET capability even though DHCP has not run
     * yet -- so a fetch fired on the strength of it talks to nothing and fails. Requiring
     * an actual IP address closes that window.
     *
     * NET_CAPABILITY_VALIDATED is deliberately not used: some TV ROMs never complete
     * captive-portal validation (logcat shows "could not create any url from ,"), so
     * waiting on it would never return.
     */
    fun isNetworkValidated(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false

        val linkProperties = cm.getLinkProperties(activeNetwork) ?: return false
        return linkProperties.linkAddresses.any { !it.address.isLinkLocalAddress && !it.address.isAnyLocalAddress }
    }

    suspend fun waitForValidatedNetwork(context: Context, timeoutMs: Long = 15000): Boolean = withContext(Dispatchers.IO) {
        if (isNetworkValidated(context)) {
            Log.d(TAG, "Network is already validated")
            return@withContext true
        }

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@withContext false

        var isConnected = false
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network became available")
                isConnected = true
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    Log.d(TAG, "Network internet capability confirmed")
                    isConnected = true
                }
            }
        }

        try {
            cm.registerNetworkCallback(networkRequest, callback)
            withTimeoutOrNull(timeoutMs) {
                while (!isConnected && !isNetworkValidated(context)) {
                    delay(300)
                }
                isConnected = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network validation error: ${e.message}")
        } finally {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (_: Exception) {}
        }

        val result = isConnected || isNetworkValidated(context)
        Log.d(TAG, "Network wait result: $result")
        result
    }
}
