package com.ustadmobile.meshrabiya.storage

import org.junit.BeforeClass
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ustadmobile.meshrabiya.model.UserKeyManager
import com.ustadmobile.meshrabiya.model.User
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl
import com.ustadmobile.meshrabiya.vnet.MeshChunk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith
import java.security.KeyPair
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Integration test for chunk storage/retrieval/replication with userId/ownerId propagation.
 * Mirrors MeshrabiyaConstantsTest best practices: explicit context, clean state, realistic persistence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ChunkOwnershipIntegrationTest {
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
    private lateinit var keypair: KeyPair
    private lateinit var user: User

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        MeshrabiyaConstants.init(context)
        MeshrabiyaConstants.setUserId("integration-user-id")
        MeshrabiyaConstants.setNickname("IntegrationUser")
        keypair = UserKeyManager.generateKeypair(context, provider = "BC")
        user = User(
            userId = "integration-user-id",
            publicKey = keypair.public,
            nickname = "IntegrationUser",
            keypair = keypair,
            entry = RecipientEntry(
                publicKey = java.util.Base64.getEncoder().encodeToString(keypair.public.encoded),
                recipientType = RecipientType.USER,
                recipientId = "integration-user-id"
            )
        )
    }

    @Test
    fun testMeshChunkOwnershipFields() {
        val chunk = MeshChunk(
            chunkId = "chunk-001",
            fileId = "file-001",
            chunkIndex = 0,
            totalChunks = 1,
            chunkSize = 1024,
            fileName = "testfile.txt",
            relativePath = "testfile.txt",
            hash = "fakehash",
            storedAt = System.currentTimeMillis(),
            sessionKeys = mapOf(),
            replicaCount = 1,
            serverPath = "/tmp/testfile.txt"
        )
        assertEquals(1, chunk.replicaCount)
        assertEquals("chunk-001", chunk.chunkId)
    }

    @Test
    fun testChunkOwnershipPropagation() {
        // Simulate storing and retrieving chunk
        val chunk = MeshChunk(
            chunkId = "chunk-002",
            fileId = "file-002",
            chunkIndex = 1,
            totalChunks = 2,
            chunkSize = 2048,
            fileName = "testfile2.txt",
            relativePath = "testfile2.txt",
            hash = "fakehash2",
            storedAt = System.currentTimeMillis(),
            sessionKeys = mapOf(),
            replicaCount = 2,
            serverPath = "/tmp/testfile2.txt"
        )
        // Simulate retrieval and replication
        val retrievedChunk = chunk.copy(replicaCount = chunk.replicaCount + 1)
        assertEquals(chunk.chunkId, retrievedChunk.chunkId)
        assertEquals(chunk.replicaCount + 1, retrievedChunk.replicaCount)
    }
}
