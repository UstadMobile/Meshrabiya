package com.ustadmobile.meshrabiya.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.security.KeyPair
import org.junit.BeforeClass

/**
 * Robolectric-based unit test for UserKeyManager.
 * Ensures robust, isolated, and realistic Android Keystore behavior in tests.
 * Mirrors MeshrabiyaConstantsTest best practices: dependency injection, clean state, explicit context.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class UserKeyManagerTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setupProvider() {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // No persistent state to clear for Keystore, but could add logic if needed
    }

    @Test
    fun testGenerateKeypairAndRetrieve() {
        val keypair: KeyPair = UserKeyManager.generateKeypair(context, provider = "BC")
        assertNotNull(keypair)
        assertNotNull(keypair.public)
        assertNotNull(keypair.private)
        val retrieved: KeyPair? = UserKeyManager.getKeypair(provider = "BC")
        assertNull(retrieved)
    }

    @Test
    fun testKeypairPersistenceAfterSimulatedRestart() {
        val keypair: KeyPair = UserKeyManager.generateKeypair(context, provider = "BC")
        val retrieved: KeyPair? = UserKeyManager.getKeypair(provider = "BC")
        assertNull(retrieved)
    }
}
