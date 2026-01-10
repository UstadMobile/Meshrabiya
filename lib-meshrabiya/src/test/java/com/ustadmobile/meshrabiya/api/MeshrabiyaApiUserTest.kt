package com.ustadmobile.meshrabiya.api

import java.security.KeyPairGenerator
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ustadmobile.meshrabiya.model.UserKeyManager
import com.ustadmobile.meshrabiya.model.User
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl
import org.junit.BeforeClass
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.security.KeyPair

/**
 * API tests for MeshrabiyaApi user endpoints.
 * Mirrors MeshrabiyaConstantsTest best practices: explicit context, clean state, realistic persistence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MeshrabiyaApiUserTest {
        @Test
        fun testKeyPairGeneratorDiagnostics() {
            println("\n--- KeyPairGenerator Diagnostics ---")
            // Print all aliases for 'RSA' in all providers
            Security.getProviders().forEach { provider ->
                val rsaAlgos = provider.services.filter { it.type == "KeyPairGenerator" && it.algorithm.contains("RSA", ignoreCase = true) }
                if (rsaAlgos.isNotEmpty()) {
                    println("Provider ${provider.name} supports KeyPairGenerator algorithms: ${rsaAlgos.map { it.algorithm }}")
                }
            }
            // Try KeyPairGenerator.getInstance("RSA", "BC")
            try {
                val kpgBC = KeyPairGenerator.getInstance("RSA", "BC")
                println("KeyPairGenerator.getInstance('RSA', 'BC') succeeded: $kpgBC")
            } catch (e: Exception) {
                println("KeyPairGenerator.getInstance('RSA', 'BC') failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            // Try KeyPairGenerator.getInstance("RSA")
            try {
                val kpgDefault = KeyPairGenerator.getInstance("RSA")
                println("KeyPairGenerator.getInstance('RSA') succeeded: $kpgDefault (provider: ${kpgDefault.provider})")
            } catch (e: Exception) {
                println("KeyPairGenerator.getInstance('RSA') failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            println("--- End Diagnostics ---\n")
        }
    companion object {
        @JvmStatic
        @BeforeClass
        fun setupProvider() {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
            // Debug: Print all registered providers
            println("Registered Security Providers:")
            Security.getProviders().forEach { provider ->
                println("Provider: ${provider.name} - ${provider.info}")
            }
            // Debug: Print all KeyPairGenerator algorithms for each provider
            println("Supported KeyPairGenerator algorithms:")
            Security.getProviders().forEach { provider ->
                val serviceNames = provider.services.filter { it.type == "KeyPairGenerator" }.map { it.algorithm }
                println("Provider ${provider.name}: ${serviceNames}")
            }
        }
    }

    private lateinit var context: Context
    private lateinit var api: MeshrabiyaApiImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        MeshrabiyaConstants.init(context)
        MeshrabiyaConstants.setUserId("")
        MeshrabiyaConstants.setNickname("")
        api = MeshrabiyaApiImpl()
        println("[DEBUG] setUp: context=$context, MeshrabiyaConstants.userId='${MeshrabiyaConstants.getUserId()}', nickname='${MeshrabiyaConstants.getNickname()}'")
        println("[DEBUG] setUp: api.keyProvider='BC' (forced for JVM)")
        api.setKeyProviderForTest("BC")
    }


    @Test
    fun testSetNicknamePersists() {
        println("[DEBUG] testSetNicknamePersists: Setting nickname to 'NewNick'")
        api.setUserNickname("NewNick")
        val nickname = MeshrabiyaConstants.getNickname()
        println("[DEBUG] testSetNicknamePersists: MeshrabiyaConstants.getNickname() = '$nickname'")
        assertEquals("NewNick", nickname)
    }

}
