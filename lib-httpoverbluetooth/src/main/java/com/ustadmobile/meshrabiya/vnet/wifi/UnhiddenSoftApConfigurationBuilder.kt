package com.ustadmobile.meshrabiya.vnet.wifi

import android.annotation.SuppressLint
import android.net.MacAddress
import android.net.wifi.SoftApConfiguration
import androidx.annotation.RequiresApi

/**
 * Reflection workaround to access hidden SoftApConfiguration.Builder so it can be used to set
 * LocalOnlyHotspot on Android 13+
 *
 * LocalOnlyHotspotConfig is generated here by Android:
 * https://cs.android.com/android/platform/superproject/+/refs/heads/master:packages/modules/Wifi/service/java/com/android/server/wifi/WifiApConfigStore.java;drc=7bb4243a97d53af6cbd4de21bcc61556a758898b;l=423
 */
@RequiresApi(30)
class UnhiddenSoftApConfigurationBuilder {

    @SuppressLint("PrivateApi")
    private val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")

    private val builderInstance = builderClass.newInstance()

    fun setBand(band: Int) : UnhiddenSoftApConfigurationBuilder {
        builderClass.getMethod("setBand", Int::class.javaPrimitiveType).invoke(
            builderInstance, band
        )
        return this
    }

    fun setAutoshutdownEnabled(enabled: Boolean) : UnhiddenSoftApConfigurationBuilder  {
        builderClass.getMethod("setAutoShutdownEnabled", Boolean::class.javaPrimitiveType).invoke(
            builderInstance, enabled
        )
        return this
    }

    fun setPassphrase(passphrase: String, securityType: Int) : UnhiddenSoftApConfigurationBuilder  {
        builderClass.getMethod(
            "setPassphrase", String::class.java, Int::class.javaPrimitiveType
        ).invoke(
            builderInstance, passphrase, securityType
        )

        return this
    }

    fun setBssid(macAddress: MacAddress) : UnhiddenSoftApConfigurationBuilder {
        builderClass.getMethod("setBssid", MacAddress::class.java).invoke(
            builderInstance, macAddress
        )
        return this
    }

    fun setMacRandomizationSetting(randomizationSetting: Int): UnhiddenSoftApConfigurationBuilder  {
        builderClass.getMethod("setMacRandomizationSetting", Int::class.java).invoke(
            builderInstance, randomizationSetting
        )
        return this
    }

    fun setSsid(ssid: String) : UnhiddenSoftApConfigurationBuilder {
        builderClass.getMethod("setSsid", String::class.java).invoke(
            builderInstance, ssid
        )
        return this
    }

    fun build(): SoftApConfiguration {
        return builderClass
            .getMethod("build")
            .invoke(builderInstance) as SoftApConfiguration
    }

    companion object {

        //Band constants as per
        // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r54:packages/modules/Wifi/framework/java/android/net/wifi/SoftApConfiguration.java;l=78
        const val BAND_2GHZ = 1.shl(0)

        const val BAND_5GHZ = 1.shl(1)

        const val BAND_6GHZ = 1.shl(2)

        const val BAND_60GHZ = 1.shl(3)

        const val BAND_ANY = BAND_2GHZ.or(BAND_5GHZ).or(BAND_6GHZ).or(BAND_60GHZ)

        /*
         * Randomization settings as per
         * https://cs.android.com/android/platform/superproject/+/android-13.0.0_r54:packages/modules/Wifi/framework/java/android/net/wifi/SoftApConfiguration.java;l=344
         */
        const val RANDOMIZATION_NONE = 0

        const val RANDOMIZATION_PERSISTENT = 1

        const val RANDOMIZATION_NON_PERSISTENT = 2

        /*
         * Security types as per
         * https://cs.android.com/android/platform/superproject/+/android-13.0.0_r54:packages/modules/Wifi/framework/java/android/net/wifi/SoftApConfiguration.java;l=406
         */
        /**
         * THe definition of security type OPEN.
         */
        const val SECURITY_TYPE_OPEN = 0

        /**
         * The definition of security type WPA2-PSK.
         */
        const val SECURITY_TYPE_WPA2_PSK = 1

        /**
         * The definition of security type WPA3-SAE Transition mode.
         */
        const val SECURITY_TYPE_WPA3_SAE_TRANSITION = 2

        /**
         * The definition of security type WPA3-SAE.
         */
        const val SECURITY_TYPE_WPA3_SAE = 3

        /**
         * The definition of security type WPA3-OWE Transition.
         */
        const val SECURITY_TYPE_WPA3_OWE_TRANSITION = 4

        /**
         * The definition of security type WPA3-OWE.
         */
        const val SECURITY_TYPE_WPA3_OWE = 5

    }

}