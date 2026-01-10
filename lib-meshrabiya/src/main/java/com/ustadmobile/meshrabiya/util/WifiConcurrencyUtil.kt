package com.ustadmobile.meshrabiya.util

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

import android.net.ConnectivityManager
import android.net.NetworkCapabilities


/**
 * Utility to detect if WiFi AP/Station concurrency is available on the device.
 * This checks for Android 11+ and uses WifiManager's isStaApConcurrencySupported() if available.
 */
object WifiConcurrencyUtil {

    /**
     * Returns true if the device supports WiFi AP/Station concurrency or can likely achieve dual connectivity.
     * Uses the most definitive method available for the device's Android version.
     */
    fun isDualConnectivityAvailable(context: Context): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> isApStaConcurrencySupported(context)
            else -> hasWifiDirectGoDualConnectivity2(context)
        }
    }

    /**
     * Returns true if the device supports WiFi AP/Station concurrency.
     */
    fun isApStaConcurrencySupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Android 11 (API 30) is required for official support
            return false
        }
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return false

        // Official API available from Android 11+
        return try {
            // isStaApConcurrencySupported() is available from API 30
            val method = WifiManager::class.java.getMethod("isStaApConcurrencySupported")
            method.invoke(wifiManager) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun hasWifiDirectGoDualConnectivity(context: Context): Boolean {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Check if Wi-Fi Direct is enabled and potentially in GO mode
        // Note: There's no direct API to check if the device *is* the GO,
        // but we can check if P2P is enabled, which is a prerequisite.
        // More advanced checks might involve listening to P2P state changes.
        if (!wifiManager.isP2pSupported) {
            return false // Device does not support Wi-Fi Direct
        }

        // Check for an active Wi-Fi AP connection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            val isWifiConnected = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

            // If Wi-Fi is connected and P2P is supported, it suggests potential for dual connectivity.
            // This is an *inference*, not a definitive confirmation of simultaneous active data paths.
            return isWifiConnected
        } else {
            // For older Android versions (API < 23), rely on deprecated methods
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI)
            @Suppress("DEPRECATION")
            val isWifiConnected = networkInfo?.isConnected == true

            return isWifiConnected
        }
    }

     /**
     * Returns true if the device is likely able to connect to an AP and host a hotspot simultaneously.
     * This is still an inference, but tries to be more definitive using available APIs.
     */
    fun hasWifiDirectGoDualConnectivity2(context: Context): Boolean {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Check Wi-Fi Direct (P2P) support
        if (!wifiManager.isP2pSupported) {
            return false
        }

        // Check if device is currently hosting a hotspot (AP)
        val isHotspotActive = try {
            val method = WifiManager::class.java.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            method.invoke(wifiManager) as? Boolean ?: false
        } catch (_: Exception) {
            false
        }

        // Check for active Wi-Fi connection (STA)
        val isWifiConnected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI)
            @Suppress("DEPRECATION")
            networkInfo?.isConnected == true
        }

        // If both hotspot and Wi-Fi connection are active, and P2P is supported, it's highly likely dual connectivity is available
        if (isHotspotActive && isWifiConnected) {
            return true
        }

        // Fallback: If only Wi-Fi is connected and P2P is supported, infer potential for dual connectivity
        return isWifiConnected
    }
}