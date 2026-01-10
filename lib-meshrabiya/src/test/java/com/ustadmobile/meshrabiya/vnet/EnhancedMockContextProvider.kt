package com.ustadmobile.meshrabiya.vnet

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.os.PowerManager
import android.telephony.TelephonyManager
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class EnhancedMockContextProvider {
    companion object {
        /**
         * Creates a mock context that properly supports both string and class-based getSystemService
         */
        fun createFullMockContext(): Context {
            val mockContext = mock<Context>()
            val services = createAllSystemServices()
            // Mock string-based method
            whenever(mockContext.getSystemService(any<String>())).thenAnswer { invocation ->
                val serviceName = invocation.arguments[0] as String
                services[serviceName]
            }
            // Mock class-based method
            whenever(mockContext.getSystemService(any<Class<*>>())).thenAnswer { invocation ->
                val serviceClass = invocation.arguments[0] as Class<*>
                getServiceByClass(services, serviceClass)
            }
            return mockContext
        }
        private fun createAllSystemServices(): Map<String, Any> {
            return mapOf(
                Context.CONNECTIVITY_SERVICE to createMockConnectivityManager(),
                Context.ALARM_SERVICE to createMockAlarmManager(),
                Context.NOTIFICATION_SERVICE to createMockNotificationManager(),
                Context.TELEPHONY_SERVICE to createMockTelephonyManager(),
                Context.LOCATION_SERVICE to createMockLocationManager(),
                Context.POWER_SERVICE to createMockPowerManager(),
                Context.ACTIVITY_SERVICE to createMockActivityManager(),
                Context.WIFI_SERVICE to createMockWifiManager()
            )
        }
        private fun getServiceByClass(services: Map<String, Any>, serviceClass: Class<*>): Any? {
            return when (serviceClass) {
                ConnectivityManager::class.java -> services[Context.CONNECTIVITY_SERVICE]
                AlarmManager::class.java -> services[Context.ALARM_SERVICE]
                NotificationManager::class.java -> services[Context.NOTIFICATION_SERVICE]
                TelephonyManager::class.java -> services[Context.TELEPHONY_SERVICE]
                LocationManager::class.java -> services[Context.LOCATION_SERVICE]
                PowerManager::class.java -> services[Context.POWER_SERVICE]
                ActivityManager::class.java -> services[Context.ACTIVITY_SERVICE]
                // Add WifiManager for class-based getSystemService
                android.net.wifi.WifiManager::class.java -> services[Context.WIFI_SERVICE]
                else -> null
            }
        }
        private fun createMockWifiManager(): android.net.wifi.WifiManager {
            val mockWifiManager = mock<android.net.wifi.WifiManager>()
            val mockWifiLock = mock<android.net.wifi.WifiManager.WifiLock>()
            // Stub createWifiLock to return the mock WifiLock for any argument
            org.mockito.kotlin.whenever(mockWifiManager.createWifiLock(org.mockito.kotlin.any())).thenReturn(mockWifiLock)
            org.mockito.kotlin.whenever(mockWifiManager.createWifiLock(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(mockWifiLock)
            // Stub acquire to do nothing
            org.mockito.kotlin.doNothing().`when`(mockWifiLock).acquire()
            // Optionally stub release to do nothing
            org.mockito.kotlin.doNothing().`when`(mockWifiLock).release()
            return mockWifiManager
        }
        private fun createMockConnectivityManager(): ConnectivityManager {
            val mock = mock<ConnectivityManager>()
            val networkInfo = mock<android.net.NetworkInfo>()
            whenever(networkInfo.isConnected).thenReturn(true)
            whenever(mock.activeNetworkInfo).thenReturn(networkInfo)
            return mock
        }
        private fun createMockAlarmManager(): AlarmManager = mock()
        private fun createMockNotificationManager(): NotificationManager = mock()
        private fun createMockTelephonyManager(): TelephonyManager = mock()
        private fun createMockLocationManager(): LocationManager = mock()
        private fun createMockPowerManager(): PowerManager = mock()
        private fun createMockActivityManager(): ActivityManager = mock()
    }
}
