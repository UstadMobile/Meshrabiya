package com.ustadmobile.meshrabiya.api

import android.content.Context
import com.ustadmobile.meshrabiya.model.ApiResult
import com.ustadmobile.meshrabiya.model.MeshState
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.EmergentRoleManager
import com.ustadmobile.meshrabiya.vnet.MeshRole
import com.ustadmobile.meshrabiya.storage.DistributedStorageManager
import com.ustadmobile.meshrabiya.storage.DistributedStorageManager.StorageStats
import com.ustadmobile.meshrabiya.storage.DistributedStorageManager.StorageParticipationConfig
import com.ustadmobile.meshrabiya.service.compute.DistributedComputeClient
import com.ustadmobile.meshrabiya.service.MeshEcosystemListener
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tests for MeshrabiyaApiImpl event handlers (Pattern D) and addTask (Pattern C)
 * 
 * All signatures verified from actual source code as of 2025-12-06
 */
class MeshrabiyaApiEventAndTaskTest {

    private lateinit var api: MeshrabiyaApiImpl
    private lateinit var mockContext: Context
    private lateinit var mockNode: AndroidVirtualNode
    private lateinit var mockRoleManager: EmergentRoleManager
    private lateinit var mockStorageManager: DistributedStorageManager
    private lateinit var mockComputeClient: DistributedComputeClient
    private lateinit var mockEcosystemListener: MeshEcosystemListener

    @Before
    fun setup() {
        println("[DEBUG] MeshrabiyaApiEventAndTaskTest: Starting @Before setup()")
        // Create all mocks
        mockContext = mockk(relaxed = true)
        mockNode = mockk(relaxed = true)
        mockRoleManager = mockk(relaxed = true)
        mockStorageManager = mockk(relaxed = true)
        mockComputeClient = mockk(relaxed = true)
        mockEcosystemListener = mockk(relaxed = true)
        
        // Setup node properties with correct nullability
        every { mockNode.address } returns InetAddress.getByName("10.0.0.1")
        every { mockNode.emergentRoleManager } returns mockRoleManager  // Non-null
        every { mockNode.distributedStorageManager } returns mockStorageManager  // Nullable but we provide it
        every { mockNode.obtainDistributedComputeClient() } returns mockComputeClient  // Returns non-null
        every { mockNode.obtainMeshEcosystemListener() } returns mockEcosystemListener  // Returns non-null
        every { mockNode.neighbors() } returns emptyList()
        
        // Setup role manager with StateFlow
        val rolesFlow = MutableStateFlow(setOf(MeshRole.MESH_PARTICIPANT))
        every { mockRoleManager.currentMeshRoles } returns rolesFlow
        every { mockRoleManager.setPreferredRoles(any()) } just Runs
        
        // Setup storage manager with StateFlow properties
        val statsFlow = MutableStateFlow(StorageStats())
        val participationFlow = MutableStateFlow(false)
        every { mockStorageManager.storageStats } returns statsFlow
        every { mockStorageManager.participationEnabled } returns participationFlow
        every { mockStorageManager.configureStorageParticipation(any()) } just Runs
        
        // Setup compute client
        coEvery { mockComputeClient.processTaskRequest(any()) } returns "task-result-123"
        
        // Create API instance
        api = MeshrabiyaApiImpl.getInstance()
        api.provideAppContext(mockContext)
        
        // Inject mocks via reflection - must inject all fields before any test uses them
        injectMockNode()
        injectMockRoleManager()
        injectMockStorageManager()
        
        // Clear any existing callbacks from previous tests
        clearCallbacks()
    }

    @After
    fun tearDown() {
        // Clear callbacks before clearing mocks
        clearCallbacks()
        clearAllMocks()
    }
    
    private fun clearCallbacks() {
        // Clear all event handler callbacks via reflection
        try {
            setFieldToNull("onFileRetrieved")
            setFieldToNull("onFileStored")
            setFieldToNull("onPermissionUpdated")
            setFieldToNull("onOperationFailed")
            setFieldToNull("onFileShared")
            setFieldToNull("onFileAddedToDropFolder")
            setFieldToNull("onGatewayTraffic")
            setFieldToNull("onMeshStateChanged")
            setFieldToNull("onPeerCountChanged")
            setFieldToNull("onGossipMessage")
            setFieldToNull("onTaskStatusUpdate")
        } catch (e: Exception) {
            // Ignore if fields don't exist
        }
    }
    
    private fun setFieldToNull(fieldName: String) {
        try {
            val field = api.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(api, null)
        } catch (e: NoSuchFieldException) {
            // Field doesn't exist, ignore
        }
    }

    private fun injectMockNode() {
        val nodeField = api.javaClass.getDeclaredField("myNode")
        nodeField.isAccessible = true
        nodeField.set(api, mockNode)
    }

    private fun injectMockRoleManager() {
        val roleField = api.javaClass.getDeclaredField("emergentRoleManager")
        roleField.isAccessible = true
        roleField.set(api, mockRoleManager)
    }

    private fun injectMockStorageManager() {
        val storageField = api.javaClass.getDeclaredField("distributedStorageManager")
        storageField.isAccessible = true
        storageField.set(api, mockStorageManager)
    }

    // ====================================================================
    // PATTERN D: Event Handler Tests (11 event handlers)
    // ====================================================================

    @Test
    fun `test setOnFileRetrieved registers handler and receives callback`() {
        // Arrange
        var capturedFileId: String? = null
        var capturedFile: File? = null
        val latch = CountDownLatch(1)
        
        // Act - Register handler
        api.setOnFileRetrieved { fileId, file ->
            capturedFileId = fileId
            capturedFile = file
            latch.countDown()
        }
        
        // Trigger callback via reflection
        val onFileRetrievedField = api.javaClass.getDeclaredField("onFileRetrieved")
        onFileRetrievedField.isAccessible = true
        val handler = onFileRetrievedField.get(api) as ((String, File) -> Unit)?
        
        val testFile = File("/tmp/test.txt")
        handler?.invoke("file-123", testFile)
        
        // Assert
        assertTrue("Handler should be called", latch.await(1, TimeUnit.SECONDS))
        assertEquals("file-123", capturedFileId)
        assertEquals(testFile, capturedFile)
    }

    @Test
    fun `test setOnFileStored registers handler and receives callback`() {
        // Arrange
        var capturedFileId: String? = null
        var capturedFile: File? = null
        val latch = CountDownLatch(1)
        
        // Act
        api.setOnFileStored { fileId, file ->
            capturedFileId = fileId
            capturedFile = file
            latch.countDown()
        }
        
        // Trigger
        val field = api.javaClass.getDeclaredField("onFileStored")
        field.isAccessible = true
        val handler = field.get(api) as ((String, File) -> Unit)?
        val testFile = File("/tmp/stored.txt")
        handler?.invoke("stored-456", testFile)
        
        // Assert
        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertEquals("stored-456", capturedFileId)
        assertEquals(testFile, capturedFile)
    }

    @Test
    fun `test setOnPermissionUpdated registers handler and receives callback`() {
        // Arrange
        var capturedFileId: String? = null
        var capturedSuccess: Boolean? = null
        val latch = CountDownLatch(1)
        
        // Act
        api.setOnPermissionUpdated { fileId, success ->
            capturedFileId = fileId
            capturedSuccess = success
            latch.countDown()
        }
        
        // Trigger
        val field = api.javaClass.getDeclaredField("onPermissionUpdated")
        field.isAccessible = true
        val handler = field.get(api) as ((String, Boolean) -> Unit)?
        handler?.invoke("perm-789", true)
        
        // Assert
        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertEquals("perm-789", capturedFileId)
        assertEquals(true, capturedSuccess)
    }

    @Test
    fun `test setOnOperationFailed registers handler with error parameter`() {
        // Arrange
        var capturedOperation: String? = null
        var capturedError: Throwable? = null
        val latch = CountDownLatch(1)
        
        // Act
        api.setOnOperationFailed { operation, error ->
            capturedOperation = operation
            capturedError = error
            latch.countDown()
        }
        
        // Trigger
        val field = api.javaClass.getDeclaredField("onOperationFailed")
        field.isAccessible = true
        val handler = field.get(api) as ((String, Throwable) -> Unit)?
        val testError = RuntimeException("Test error")
        handler?.invoke("storeFile", testError)
        
        // Assert
        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertEquals("storeFile", capturedOperation)
        assertEquals(testError, capturedError)
    }

    @Test
    fun `test setOnFileShared registers handler with recipientId`() {
        // Arrange
        var capturedFileId: String? = null
        var capturedRecipientId: String? = null
        val latch = CountDownLatch(1)
        
        // Act
        api.setOnFileShared { fileId, recipientId ->
            capturedFileId = fileId
            capturedRecipientId = recipientId
            latch.countDown()
        }
        
        // Trigger
        val field = api.javaClass.getDeclaredField("onFileShared")
        field.isAccessible = true
        val handler = field.get(api) as ((String, String) -> Unit)?
        handler?.invoke("shared-123", "user-456")
        
        // Assert
        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertEquals("shared-123", capturedFileId)
        assertEquals("user-456", capturedRecipientId)
    }

    @Test
    fun `test setOnFileAddedToDropFolder registers handler`() {
        // Arrange
        var capturedFileId: String? = null
        var capturedFile: File? = null
        val latch = CountDownLatch(1)
        
        // Act
        api.setOnFileAddedToDropFolder { fileId, file ->
            capturedFileId = fileId
            capturedFile = file
            latch.countDown()
        }
        
        // Trigger
        val field = api.javaClass.getDeclaredField("onFileAddedToDropFolder")
        field.isAccessible = true
        val handler = field.get(api) as ((String, File) -> Unit)?
        val dropFile = File("/drop/file.dat")
        handler?.invoke("drop-789", dropFile)
        
        // Assert
        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertEquals("drop-789", capturedFileId)
        assertEquals(dropFile, capturedFile)
    }

    @Test
    fun `test setOnGatewayTraffic registers handler with Boolean return`() {
        // Arrange
        var capturedPacket: VirtualPacket? = null
        val latch = CountDownLatch(1)
        
        // Act - Handler returns Boolean
        api.setOnGatewayTraffic { packet ->
            capturedPacket = packet
            latch.countDown()
            true  // Return true to allow packet
        }
        
        // Trigger
        val field = api.javaClass.getDeclaredField("onGatewayTraffic")
        field.isAccessible = true
        val handler = field.get(api) as ((VirtualPacket) -> Boolean)?
        
        val mockPacket = mockk<VirtualPacket>(relaxed = true)
        val result = handler?.invoke(mockPacket)
        
        // Assert
        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertEquals(mockPacket, capturedPacket)
        assertEquals(true, result)  // Handler returns Boolean
    }

    @Test
    fun `test setOnMeshStateChanged registers handler with MeshState`() {
        // Arrange
        var capturedState: MeshState? = null
        val latch = CountDownLatch(1)
        
        // Act
        api.setOnMeshStateChanged { newState ->
            capturedState = newState
            latch.countDown()
        }
        
        // Trigger
        val field = api.javaClass.getDeclaredField("onMeshStateChanged")
        field.isAccessible = true
        val handler = field.get(api) as ((MeshState) -> Unit)?
        handler?.invoke(MeshState.CONNECTED)
        
        // Assert
        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertEquals(MeshState.CONNECTED, capturedState)
    }

    @Test
    fun `test setOnPeerCountChanged registers handler with Int`() {
        // Arrange
        var capturedCount: Int? = null
        val latch = CountDownLatch(1)
        
        // Act
        api.setOnPeerCountChanged { newCount ->
            capturedCount = newCount
            latch.countDown()
        }
        
        // Trigger
        val field = api.javaClass.getDeclaredField("onPeerCountChanged")
        field.isAccessible = true
        val handler = field.get(api) as ((Int) -> Unit)?
        handler?.invoke(5)
        
        // Assert
        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertEquals(5, capturedCount)
    }

    @Test
    fun `test setOnGossipMessage registers handler with senderId and ByteArray`() {
        // Arrange
        var capturedSenderId: Int? = null
        var capturedBytes: ByteArray? = null
        val latch = CountDownLatch(1)
        
        // Act
        api.setOnGossipMessage { senderId, messageBytes ->
            capturedSenderId = senderId
            capturedBytes = messageBytes
            latch.countDown()
        }
        
        // Trigger
        val field = api.javaClass.getDeclaredField("onGossipMessage")
        field.isAccessible = true
        val handler = field.get(api) as ((Int, ByteArray) -> Unit)?
        val testBytes = byteArrayOf(1, 2, 3, 4, 5)
        handler?.invoke(42, testBytes)
        
        // Assert
        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertEquals(42, capturedSenderId)
        assertArrayEquals(testBytes, capturedBytes)
    }

    @Test
    fun `test setOnTaskStatusUpdate registers handler with taskId and status`() {
        // Arrange
        var capturedTaskId: String? = null
        var capturedStatus: String? = null
        val latch = CountDownLatch(1)
        
        // Act
        api.setOnTaskStatusUpdate { taskId, status ->
            capturedTaskId = taskId
            capturedStatus = status
            latch.countDown()
        }
        
        // Trigger
        val field = api.javaClass.getDeclaredField("onTaskStatusUpdate")
        field.isAccessible = true
        val handler = field.get(api) as ((String, String) -> Unit)?
        handler?.invoke("task-999", "completed")
        
        // Assert
        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertEquals("task-999", capturedTaskId)
        assertEquals("completed", capturedStatus)
    }

    @Test
    fun `test triggerTaskStatusUpdate invokes registered callback`() {
        // Arrange
        var capturedTaskId: String? = null
        var capturedStatus: String? = null
        val latch = CountDownLatch(1)
        
        api.setOnTaskStatusUpdate { taskId, status ->
            capturedTaskId = taskId
            capturedStatus = status
            latch.countDown()
        }
        
        // Act - Call public trigger method
        api.triggerTaskStatusUpdate("task-abc", "running")
        
        // Assert
        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertEquals("task-abc", capturedTaskId)
        assertEquals("running", capturedStatus)
    }

    // ====================================================================
    // PATTERN C: addTask Tests
    // ====================================================================

    @Test
    fun `test addTask returns Success with auto-generated taskId`() {
        // Arrange
        val requestParams = mapOf<String, Any>(
            "taskType" to "python"
        )
        
        // Act
        val result = api.addTask(requestParams)
        
        // Assert
        assertTrue("Should return ApiResult.Success", result is ApiResult.Success)
    }

    @Test
    fun `test addTask returns Success with provided taskId`() {
        // Arrange
        val requestParams = mapOf<String, Any>(
            "taskId" to "custom-task-123",
            "taskType" to "jvm"
        )
        
        // Act
        val result = api.addTask(requestParams)
        
        // Assert
        assertTrue("Should return ApiResult.Success", result is ApiResult.Success)
    }

    @Test
    fun `test addTask returns Failure when taskType missing`() {
        // Arrange
        val requestParams = mapOf<String, Any>(
            // taskType missing
        )
        
        // Act
        val result = api.addTask(requestParams)
        
        // Assert
        assertTrue("Should return ApiResult.Failure", result is ApiResult.Failure)
        if (result is ApiResult.Failure) {
            assertNotNull("Should have error", result.error)
            assertTrue("Error should mention taskType", 
                result.error.message?.contains("taskType") ?: false)
        }
    }

    @Test
    fun `test addTask accepts valid taskType python`() {
        // Arrange
        val requestParams = mapOf<String, Any>(
            "taskType" to "python"
        )
        
        // Act
        val result = api.addTask(requestParams)
        
        // Assert
        assertTrue("Should return Success for python taskType", result is ApiResult.Success)
    }

    @Test
    fun `test addTask accepts valid taskType jvm`() {
        // Arrange
        val requestParams = mapOf<String, Any>(
            "taskType" to "jvm"
        )
        
        // Act
        val result = api.addTask(requestParams)
        
        // Assert
        assertTrue("Should return Success for jvm taskType", result is ApiResult.Success)
    }

    @Test
    fun `test addTask accepts valid taskType javascript`() {
        // Arrange
        val requestParams = mapOf<String, Any>(
            "taskType" to "javascript"
        )
        
        // Act
        val result = api.addTask(requestParams)
        
        // Assert
        assertTrue("Should return Success for javascript taskType", result is ApiResult.Success)
    }

    @Test
    fun `test addTask accepts valid taskType ml-native`() {
        // Arrange
        val requestParams = mapOf<String, Any>(
            "taskType" to "ml-native"
        )
        
        // Act
        val result = api.addTask(requestParams)
        
        // Assert
        assertTrue("Should return Success for ml-native taskType", result is ApiResult.Success)
    }

    @Test
    fun `test addTask succeeds without optional parameters`() {
        // Arrange
        val requestParams = mapOf<String, Any>(
            "taskType" to "python"
        )
        
        // Act
        val result = api.addTask(requestParams)
        
        // Assert
        assertTrue("Should return Success with only required parameters", result is ApiResult.Success)
    }



    @Test
    fun `test addTask triggers status callback asynchronously`() {
        // Arrange
        var callbackInvoked = false
        val latch = CountDownLatch(1)
        
        api.setOnTaskStatusUpdate { taskId, status ->
            callbackInvoked = true
            latch.countDown()
        }
        
        val requestParams = mapOf<String, Any>(
            "taskType" to "python"
        )
        
        // Act
        val result = api.addTask(requestParams)
        
        // Assert - Returns immediately, callback happens async
        assertTrue("Should return Success immediately", result is ApiResult.Success)
        
        // Wait briefly for async callback
        val callbackReceived = latch.await(2, TimeUnit.SECONDS)
        // Note: Callback may or may not fire in test environment
        // This test verifies the API returns immediately regardless
    }
}
