import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build

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