package com.ustadmobile.meshrabiya

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

/**
 * Robolectric-based test for MeshrabiyaConstants with dependency injection for SharedPreferences.
 * Ensures robust, isolated, and realistic SharedPreferences behavior in tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MeshrabiyaConstantsTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("mesh_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit() // Ensure clean state
        MeshrabiyaConstants.init(context)
        MeshrabiyaConstants.setUserId("")
        MeshrabiyaConstants.setNickname("")
    }

    @Test
    fun testUserIdPersistence() {
        val testUserId = "test-user-id-123"
        MeshrabiyaConstants.setUserId(testUserId)
        val retrievedUserId = MeshrabiyaConstants.getUserId()
        assertEquals(testUserId, retrievedUserId)
    }

    @Test
    fun testNicknamePersistence() {
        val testNickname = "TestUser"
        MeshrabiyaConstants.setNickname(testNickname)
        val retrievedNickname = MeshrabiyaConstants.getNickname()
        assertEquals(testNickname, retrievedNickname)
    }

    @Test
    fun testPersistenceAfterSimulatedRestart() {
        val testUserId = "persisted-user-id"
        val testNickname = "PersistedUser"
        MeshrabiyaConstants.setUserId(testUserId)
        MeshrabiyaConstants.setNickname(testNickname)
        // Simulate app restart by re-initializing MeshrabiyaConstants
        MeshrabiyaConstants.init(context)
        val userIdAfterRestart = MeshrabiyaConstants.getUserId()
        val nicknameAfterRestart = MeshrabiyaConstants.getNickname()
        assertEquals(testUserId, userIdAfterRestart)
        assertEquals(testNickname, nicknameAfterRestart)
    }
}
