package com.ustadmobile.meshrabiya.service
import androidx.core.content.ContextCompat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl

/**
 * V3: BroadcastReceiver for monitoring Tor daemon status from Orbot.
 * 
 * Listens for Orbot's status broadcast intents and updates MeshrabiyaApiImpl
 * with current Tor running state. This status is used to enforce SERVER-side
 * Tor gateway availability (cannot announce Tor gateway if Tor is not running).
 * 
 * **Orbot Status Intent:**
 * - Action: `org.torproject.android.intent.action.STATUS`
 * - Extra: `"org.torproject.android.intent.extra.STATUS"` (String)
 * - Values: "ON", "OFF", "STARTING", "STOPPING"
 * 
 * **V3 Mapping (Conservative):**
 * - "ON" → Tor is running (true)
 * - All other values → Tor is not ready (false)
 * 
 * **Usage:**
 * ```kotlin
 * val monitor = TorStatusMonitor()
 * monitor.register(context)  // Start monitoring
 * 
 * // Later...
 * val isActive = meshrabiyaApi.isTorActive()  // Query current status
 * 
 * // When done
 * monitor.unregister(context)  // Stop monitoring
 * ```
 * 
 * **Integration:**
 * - Registered in MeshrabiyaApiImpl.initMesh() or AndroidVirtualNode initialization
 * - Updates MeshrabiyaApiImpl.isTorRunning volatile field
 * - Thread-safe via volatile writes
 * 
 * **Privacy Note:**
 * This receiver does NOT send any data to Orbot. It only passively listens
 * for status broadcasts that Orbot already emits system-wide.
 */
class TorStatusMonitor : BroadcastReceiver() {

    companion object {
        private const val TAG = "TorStatusMonitor"

        /**
         * Orbot's status broadcast intent action.
         * Sent when Tor daemon status changes.
         */
        const val ACTION_TOR_STATUS = "org.torproject.android.intent.action.STATUS"

        /**
         * Intent extra key for Tor status string.
         * Values: "ON", "OFF", "STARTING", "STOPPING"
         */
        const val EXTRA_TOR_STATUS = "org.torproject.android.intent.extra.STATUS"

        /**
         * Tor status value indicating daemon is fully running.
         */
        const val STATUS_ON = "ON"

        /**
         * Tor status value indicating daemon is stopped.
         */
        const val STATUS_OFF = "OFF"

        /**
         * Tor status value indicating daemon is starting (not ready yet).
         */
        const val STATUS_STARTING = "STARTING"

        /**
         * Tor status value indicating daemon is stopping.
         */
        const val STATUS_STOPPING = "STOPPING"
    }

    /**
     * Flag to track if receiver is currently registered.
     * Prevents double-registration errors.
     */
    @Volatile
    private var isRegistered = false

    /**
     * Register this receiver to start monitoring Tor status.
     * 
     * **Call Context:** Should be called from MeshrabiyaApiImpl.initMesh()
     * or AndroidVirtualNode initialization.
     * 
     * @param context Android context (application context recommended)
     * @throws IllegalStateException if already registered
     */
    fun register(context: Context) {
        if (isRegistered) {
            Log.w(TAG, "TorStatusMonitor already registered, ignoring duplicate register()")
            return
        }

        try {
            val filter = IntentFilter(ACTION_TOR_STATUS)
            ContextCompat.registerReceiver(
                context,
                this,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isRegistered = true
            Log.i(TAG, "TorStatusMonitor registered for Orbot status broadcasts")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register TorStatusMonitor", e)
            throw e
        }
    }

    /**
     * Unregister this receiver to stop monitoring Tor status.
     * 
     * **Call Context:** Should be called from MeshrabiyaApiImpl cleanup
     * or AndroidVirtualNode shutdown.
     * 
     * @param context Android context (same as used in register)
     */
    fun unregister(context: Context) {
        if (!isRegistered) {
            Log.w(TAG, "TorStatusMonitor not registered, ignoring unregister()")
            return
        }

        try {
            context.unregisterReceiver(this)
            isRegistered = false
            Log.i(TAG, "TorStatusMonitor unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister TorStatusMonitor", e)
        }
    }

    /**
     * Handle Orbot status broadcast intent.
     * 
     * **Status Mapping (Conservative):**
     * - "ON" → true (Tor fully running, can announce Tor gateway)
     * - "STARTING" → false (not ready yet)
     * - "STOPPING" → false (shutting down)
     * - "OFF" → false (not running)
     * - null/unknown → false (assume not ready)
     * 
     * **Implementation:**
     * 1. Extract status string from intent extra
     * 2. Map to boolean (only "ON" = true)
     * 3. Update MeshrabiyaApiImpl.isTorRunning
     * 4. Log status change
     * 
     * @param context Android context
     * @param intent Broadcast intent from Orbot
     */
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ACTION_TOR_STATUS) {
            Log.w(TAG, "Received non-status intent: ${intent?.action}")
            return
        }

        val status = intent.getStringExtra(EXTRA_TOR_STATUS)
        val isTorActive = (status == STATUS_ON)

        Log.i(TAG, "Tor status update: status='$status' → isTorActive=$isTorActive")

        // Update MeshrabiyaApiImpl status
        try {
            val api = MeshrabiyaApiImpl.getInstance()
            api.updateTorStatus(isTorActive)
            
            Log.d(TAG, "Updated MeshrabiyaApi.isTorActive = $isTorActive")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update MeshrabiyaApi Tor status", e)
        }
    }

    /**
     * Query current Tor status synchronously (if receiver is active).
     * 
     * **Note:** This only returns the last known status from broadcasts.
     * For guaranteed current status, use MeshrabiyaApi.isTorActive() which
     * maintains the latest state.
     * 
     * @return true if last known status was "ON", false otherwise
     */
    fun getCurrentStatus(): Boolean {
        return MeshrabiyaApiImpl.getInstance().isTorActive()
    }

    /**
     * Send a request to Orbot to broadcast its current status.
     * 
     * **Use Case:** Call this after registering receiver to get immediate
     * status without waiting for next status change.
     * 
     * **Implementation:** Sends ACTION_TOR_STATUS intent to Orbot,
     * which should respond with a status broadcast.
     * 
     * @param context Android context
     */
    fun requestStatusUpdate(context: Context) {
        try {
            val intent = Intent(ACTION_TOR_STATUS)
            intent.setPackage("org.torproject.android")  // Target Orbot package
            context.sendBroadcast(intent)
            Log.d(TAG, "Requested Tor status update from Orbot")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Tor status update", e)
        }
    }
}
