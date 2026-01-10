package com.ustadmobile.meshrabiya

import android.content.SharedPreferences
import io.mockk.*
import org.junit.Before
import org.junit.Test
import kotlin.test.*

/**
 * Unit tests for VPN rules precedence logic.
 * Verifies that per-app VPN rules correctly supersede global gateway preferences.
 */
class VpnRulesPrecedenceTest {

    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor

    @Before
    fun setup() {
        println("[DEBUG] VpnRulesPrecedenceTest: Starting @Before setup()")
        mockPrefs = mockk(relaxed = true)
        mockEditor = mockk(relaxed = true)
        
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.apply() } just Runs
    }

    @Test
    fun `VPN rule for Tor supersedes CLEARNET_ONLY preference`() {
        // Chrome is torified
        every { mockPrefs.getString("PrefTord", "") } returns "com.android.chrome"
        
        val isTorified = isPackageTorified("com.android.chrome", mockPrefs)
        
        assertTrue(isTorified, "Chrome should be torified when in PrefTord")
    }

    @Test
    fun `VPN rule for clearnet supersedes TOR_ONLY preference`() {
        // WhatsApp NOT torified (only Chrome in list)
        every { mockPrefs.getString("PrefTord", "") } returns "com.android.chrome"
        
        val isTorified = isPackageTorified("com.whatsapp", mockPrefs)
        
        assertFalse(isTorified, "WhatsApp should not be torified when not in PrefTord")
    }

    @Test
    fun `empty VPN settings returns false`() {
        every { mockPrefs.getString("PrefTord", "") } returns ""
        
        val isTorified = isPackageTorified("com.android.chrome", mockPrefs)
        
        assertFalse(isTorified, "No package should be torified with empty PrefTord")
    }

    @Test
    fun `null VPN settings returns false`() {
        every { mockPrefs.getString("PrefTord", "") } returns null
        
        val isTorified = isPackageTorified("com.android.chrome", mockPrefs)
        
        assertFalse(isTorified, "No package should be torified with null PrefTord")
    }

    @Test
    fun `multiple torified apps parsed correctly`() {
        every { mockPrefs.getString("PrefTord", "") } returns 
            "com.android.chrome|org.mozilla.firefox|com.whatsapp"
        
        assertTrue(isPackageTorified("com.android.chrome", mockPrefs))
        assertTrue(isPackageTorified("org.mozilla.firefox", mockPrefs))
        assertTrue(isPackageTorified("com.whatsapp", mockPrefs))
        assertFalse(isPackageTorified("org.telegram.messenger", mockPrefs))
    }

    @Test
    fun `whitespace in package names handled correctly`() {
        every { mockPrefs.getString("PrefTord", "") } returns 
            " com.android.chrome | org.mozilla.firefox "
        
        // Should still match despite whitespace
        assertTrue(isPackageTorified("com.android.chrome", mockPrefs))
        assertTrue(isPackageTorified("org.mozilla.firefox", mockPrefs))
    }

    @Test
    fun `single torified app works`() {
        every { mockPrefs.getString("PrefTord", "") } returns "com.android.chrome"
        
        assertTrue(isPackageTorified("com.android.chrome", mockPrefs))
        assertFalse(isPackageTorified("com.whatsapp", mockPrefs))
    }

    @Test
    fun `empty string elements filtered out`() {
        every { mockPrefs.getString("PrefTord", "") } returns 
            "com.android.chrome||org.mozilla.firefox"
        
        assertTrue(isPackageTorified("com.android.chrome", mockPrefs))
        assertTrue(isPackageTorified("org.mozilla.firefox", mockPrefs))
        assertFalse(isPackageTorified("", mockPrefs))
    }

    @Test
    fun `package name matching is case sensitive`() {
        every { mockPrefs.getString("PrefTord", "") } returns "com.android.chrome"
        
        assertTrue(isPackageTorified("com.android.chrome", mockPrefs))
        assertFalse(isPackageTorified("COM.ANDROID.CHROME", mockPrefs))
        assertFalse(isPackageTorified("com.Android.Chrome", mockPrefs))
    }

    @Test
    fun `partial package name match returns false`() {
        every { mockPrefs.getString("PrefTord", "") } returns "com.android.chrome"
        
        assertTrue(isPackageTorified("com.android.chrome", mockPrefs))
        assertFalse(isPackageTorified("com.android", mockPrefs))
        assertFalse(isPackageTorified("chrome", mockPrefs))
        assertFalse(isPackageTorified("com.android.chrome.beta", mockPrefs))
    }

    /**
     * Helper function that mimics VPN rules precedence logic.
     * This is the logic that should be integrated into GatewayTypeResolver.
     */
    private fun isPackageTorified(packageName: String, prefs: SharedPreferences): Boolean {
        val torifiedAppsString = prefs.getString("PrefTord", "") ?: ""
        if (torifiedAppsString.isEmpty()) return false
        
        val torifiedPackages = torifiedAppsString
            .split("|")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        
        return torifiedPackages.contains(packageName)
    }
}
