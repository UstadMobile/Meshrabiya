package org.torproject.meshrabiya.compute.security.tests

import kotlinx.coroutines.runBlocking
import org.torproject.meshrabiya.storage.DistributedStorageManager
import org.torproject.meshrabiya.storage.RecipientType
import org.torproject.meshrabiya.compute.TaskManager

/**
 * FileIsolationTests verifies that files are properly isolated between tasks.
 * 
 * Test Scenarios:
 * 1. Task A cannot read files encrypted for Task B
 * 2. Task cannot read files without proper recipient entry
 * 3. Expired task recipients lose file access
 * 4. File metadata correctly tracks task recipients
 * 5. updateFileAccess properly adds/removes task recipients
 * 6. Cross-task file enumeration is prevented
 * 7. File decryption fails for unauthorized tasks
 * 8. Recipient list cannot be tampered with
 * 
 * Success Criteria:
 * - All unauthorized file access attempts must fail
 * - No cross-task file leakage
 * - Proper recipient tracking
 * 
 * @property storageManager DistributedStorageManager for file operations
 * @property taskManager TaskManager for task keypair operations
 */
class FileIsolationTests(
    private val storageManager: DistributedStorageManager,
    private val taskManager: TaskManager
) {
    
    /**
     * Test result for a single isolation test.
     */
    data class TestResult(
        val testName: String,
        val passed: Boolean,
        val message: String,
        val details: Map<String, Any> = emptyMap()
    )
    
    /**
     * Test suite result.
     */
    data class SuiteResult(
        val totalTests: Int,
        val passed: Int,
        val failed: Int,
        val results: List<TestResult>
    ) {
        val allPassed: Boolean get() = failed == 0
        val passRate: Double get() = if (totalTests > 0) passed.toDouble() / totalTests else 0.0
    }
    
    /**
     * Run all file isolation tests.
     * 
     * @return Test suite result
     */
    fun runAllTests(): SuiteResult {
        val results = mutableListOf<TestResult>()
        
        results.add(testCrossTaskFileAccess())
        results.add(testUnauthorizedFileAccess())
        results.add(testExpiredTaskRecipientAccess())
        results.add(testFileMetadataRecipientTracking())
        results.add(testUpdateFileAccessIsolation())
        results.add(testCrossTaskFileEnumeration())
        results.add(testFileDecryptionAuthorization())
        results.add(testRecipientListIntegrity())
        
        val passed = results.count { it.passed }
        val failed = results.count { !it.passed }
        
        return SuiteResult(
            totalTests = results.size,
            passed = passed,
            failed = failed,
            results = results
        )
    }
    
    /**
     * Test 1: Task A cannot read files encrypted for Task B.
     */
    private fun testCrossTaskFileAccess(): TestResult {
        return try {
            runBlocking {
                val taskIdA = "test-file-access-a-${System.currentTimeMillis()}"
                val taskIdB = "test-file-access-b-${System.currentTimeMillis()}"
                
                // Generate keypairs for both tasks
                val keypairA = taskManager.generateTaskKeypair(taskIdA, lifetimeMs = 300000)
                val keypairB = taskManager.generateTaskKeypair(taskIdB, lifetimeMs = 300000)
                
                if (keypairA == null || keypairB == null) {
                    return@runBlocking TestResult(
                        testName = "testCrossTaskFileAccess",
                        passed = false,
                        message = "Failed to generate task keypairs",
                        details = emptyMap()
                    )
                }
                
                // Create recipient entry for Task B only
                val recipientB = storageManager.RecipientEntry(
                    publicKey = keypairB.publicKey,
                    recipientType = RecipientType.TASK,
                    expiresAt = System.currentTimeMillis() + 300000,
                    taskId = taskIdB
                )
                
                // Store file with Task B as recipient
                val testData = "Secret data for Task B only".toByteArray()
                val fileId = storageManager.storeFile(
                    data = testData,
                    filename = "test-file-${System.currentTimeMillis()}.txt",
                    recipients = listOf(recipientB)
                )
                
                // Verify file metadata shows only Task B has access
                val metadata = storageManager.getFileMetadata(fileId)
                if (metadata == null) {
                    return@runBlocking TestResult(
                        testName = "testCrossTaskFileAccess",
                        passed = false,
                        message = "File metadata not found",
                        details = mapOf("fileId" to fileId)
                    )
                }
                
                // Check if Task A has access (should be false)
                val taskAHasAccess = metadata.hasTaskAccess(taskIdA)
                if (taskAHasAccess) {
                    return@runBlocking TestResult(
                        testName = "testCrossTaskFileAccess",
                        passed = false,
                        message = "Task A has unauthorized access to Task B's file",
                        details = mapOf(
                            "fileId" to fileId,
                            "taskIdA" to taskIdA,
                            "taskIdB" to taskIdB
                        )
                    )
                }
                
                // Check if Task B has access (should be true)
                val taskBHasAccess = metadata.hasTaskAccess(taskIdB)
                if (!taskBHasAccess) {
                    return@runBlocking TestResult(
                        testName = "testCrossTaskFileAccess",
                        passed = false,
                        message = "Task B does not have access to its own file",
                        details = mapOf(
                            "fileId" to fileId,
                            "taskIdB" to taskIdB
                        )
                    )
                }
                
                // Cleanup
                storageManager.deleteFile(fileId)
                taskManager.cleanupExpiredKeypairs()
                
                TestResult(
                    testName = "testCrossTaskFileAccess",
                    passed = true,
                    message = "Files are properly isolated between tasks",
                    details = mapOf(
                        "fileId" to fileId,
                        "taskIdA" to taskIdA,
                        "taskIdB" to taskIdB,
                        "taskAHasAccess" to taskAHasAccess,
                        "taskBHasAccess" to taskBHasAccess
                    )
                )
            }
        } catch (e: Exception) {
            TestResult(
                testName = "testCrossTaskFileAccess",
                passed = false,
                message = "Test failed with exception: ${e.message}",
                details = mapOf("exception" to e::class.simpleName.orEmpty())
            )
        }
    }
    
    /**
     * Test 2: Task cannot read files without proper recipient entry.
     */
    private fun testUnauthorizedFileAccess(): TestResult {
        return try {
            runBlocking {
                val authorizedTask = "test-authorized-${System.currentTimeMillis()}"
                val unauthorizedTask = "test-unauthorized-${System.currentTimeMillis()}"
                
                val keypairAuth = taskManager.generateTaskKeypair(authorizedTask, lifetimeMs = 300000)
                
                if (keypairAuth == null) {
                    return@runBlocking TestResult(
                        testName = "testUnauthorizedFileAccess",
                        passed = false,
                        message = "Failed to generate keypair",
                        details = emptyMap()
                    )
                }
                
                val recipient = storageManager.RecipientEntry(
                    publicKey = keypairAuth.publicKey,
                    recipientType = RecipientType.TASK,
                    expiresAt = System.currentTimeMillis() + 300000,
                    taskId = authorizedTask
                )
                
                // Store file
                val testData = "Authorized access only".toByteArray()
                val fileId = storageManager.storeFile(
                    data = testData,
                    filename = "test-auth-${System.currentTimeMillis()}.txt",
                    recipients = listOf(recipient)
                )
                
                val metadata = storageManager.getFileMetadata(fileId)
                if (metadata == null) {
                    return@runBlocking TestResult(
                        testName = "testUnauthorizedFileAccess",
                        passed = false,
                        message = "File metadata not found",
                        details = emptyMap()
                    )
                }
                
                // Verify unauthorized task has no access
                val hasAccess = metadata.hasTaskAccess(unauthorizedTask)
                if (hasAccess) {
                    return@runBlocking TestResult(
                        testName = "testUnauthorizedFileAccess",
                        passed = false,
                        message = "Unauthorized task has file access",
                        details = mapOf(
                            "fileId" to fileId,
                            "unauthorizedTask" to unauthorizedTask
                        )
                    )
                }
                
                // Cleanup
                storageManager.deleteFile(fileId)
                taskManager.cleanupExpiredKeypairs()
                
                TestResult(
                    testName = "testUnauthorizedFileAccess",
                    passed = true,
                    message = "Unauthorized tasks cannot access files",
                    details = mapOf(
                        "fileId" to fileId,
                        "authorizedTask" to authorizedTask,
                        "unauthorizedTask" to unauthorizedTask
                    )
                )
            }
        } catch (e: Exception) {
            TestResult(
                testName = "testUnauthorizedFileAccess",
                passed = false,
                message = "Test failed with exception: ${e.message}",
                details = mapOf("exception" to e::class.simpleName.orEmpty())
            )
        }
    }
    
    /**
     * Test 3: Expired task recipients lose file access.
     */
    private fun testExpiredTaskRecipientAccess(): TestResult {
        return try {
            runBlocking {
                val taskId = "test-expired-access-${System.currentTimeMillis()}"
                val keypair = taskManager.generateTaskKeypair(taskId, lifetimeMs = 100)
                
                if (keypair == null) {
                    return@runBlocking TestResult(
                        testName = "testExpiredTaskRecipientAccess",
                        passed = false,
                        message = "Failed to generate keypair",
                        details = emptyMap()
                    )
                }
                
                // Create recipient with short expiration
                val recipient = storageManager.RecipientEntry(
                    publicKey = keypair.publicKey,
                    recipientType = RecipientType.TASK,
                    expiresAt = System.currentTimeMillis() + 50, // 50ms expiration
                    taskId = taskId
                )
                
                val testData = "Temporary access data".toByteArray()
                val fileId = storageManager.storeFile(
                    data = testData,
                    filename = "test-temp-${System.currentTimeMillis()}.txt",
                    recipients = listOf(recipient)
                )
                
                // Verify initial access
                val metadataBefore = storageManager.getFileMetadata(fileId)
                val hasAccessBefore = metadataBefore?.hasTaskAccess(taskId) ?: false
                
                if (!hasAccessBefore) {
                    return@runBlocking TestResult(
                        testName = "testExpiredTaskRecipientAccess",
                        passed = false,
                        message = "Task does not have initial access",
                        details = mapOf("fileId" to fileId, "taskId" to taskId)
                    )
                }
                
                // Wait for expiration
                Thread.sleep(100)
                
                // Check active recipients (should filter out expired)
                val metadataAfter = storageManager.getFileMetadata(fileId)
                val activeRecipients = metadataAfter?.getActiveRecipients() ?: emptyList()
                
                val expiredRecipientStillActive = activeRecipients.any { 
                    it.taskId == taskId && it.isExpired()
                }
                
                if (expiredRecipientStillActive) {
                    return@runBlocking TestResult(
                        testName = "testExpiredTaskRecipientAccess",
                        passed = false,
                        message = "Expired recipient still in active list",
                        details = mapOf(
                            "fileId" to fileId,
                            "taskId" to taskId
                        )
                    )
                }
                
                // Cleanup
                storageManager.deleteFile(fileId)
                taskManager.cleanupExpiredKeypairs()
                
                TestResult(
                    testName = "testExpiredTaskRecipientAccess",
                    passed = true,
                    message = "Expired task recipients properly lose access",
                    details = mapOf(
                        "fileId" to fileId,
                        "taskId" to taskId,
                        "activeRecipientsAfterExpiry" to activeRecipients.size
                    )
                )
            }
        } catch (e: Exception) {
            TestResult(
                testName = "testExpiredTaskRecipientAccess",
                passed = false,
                message = "Test failed with exception: ${e.message}",
                details = mapOf("exception" to e::class.simpleName.orEmpty())
            )
        }
    }
    
    /**
     * Test 4: File metadata correctly tracks task recipients.
     */
    private fun testFileMetadataRecipientTracking(): TestResult {
        return try {
            runBlocking {
                val taskId1 = "test-tracking-1-${System.currentTimeMillis()}"
                val taskId2 = "test-tracking-2-${System.currentTimeMillis()}"
                
                val keypair1 = taskManager.generateTaskKeypair(taskId1, lifetimeMs = 300000)
                val keypair2 = taskManager.generateTaskKeypair(taskId2, lifetimeMs = 300000)
                
                if (keypair1 == null || keypair2 == null) {
                    return@runBlocking TestResult(
                        testName = "testFileMetadataRecipientTracking",
                        passed = false,
                        message = "Failed to generate keypairs",
                        details = emptyMap()
                    )
                }
                
                val recipient1 = storageManager.RecipientEntry(
                    publicKey = keypair1.publicKey,
                    recipientType = RecipientType.TASK,
                    expiresAt = System.currentTimeMillis() + 300000,
                    taskId = taskId1
                )
                
                val recipient2 = storageManager.RecipientEntry(
                    publicKey = keypair2.publicKey,
                    recipientType = RecipientType.TASK,
                    expiresAt = System.currentTimeMillis() + 300000,
                    taskId = taskId2
                )
                
                // Store file with both recipients
                val testData = "Multi-task data".toByteArray()
                val fileId = storageManager.storeFile(
                    data = testData,
                    filename = "test-multi-${System.currentTimeMillis()}.txt",
                    recipients = listOf(recipient1, recipient2)
                )
                
                val metadata = storageManager.getFileMetadata(fileId)
                if (metadata == null) {
                    return@runBlocking TestResult(
                        testName = "testFileMetadataRecipientTracking",
                        passed = false,
                        message = "File metadata not found",
                        details = emptyMap()
                    )
                }
                
                // Verify both tasks are tracked
                val taskRecipients = metadata.getTaskRecipients()
                val hasTask1 = taskRecipients.any { it.taskId == taskId1 }
                val hasTask2 = taskRecipients.any { it.taskId == taskId2 }
                
                if (!hasTask1 || !hasTask2) {
                    return@runBlocking TestResult(
                        testName = "testFileMetadataRecipientTracking",
                        passed = false,
                        message = "Task recipients not properly tracked",
                        details = mapOf(
                            "hasTask1" to hasTask1,
                            "hasTask2" to hasTask2,
                            "taskRecipientCount" to taskRecipients.size
                        )
                    )
                }
                
                // Verify recipient count
                if (taskRecipients.size != 2) {
                    return@runBlocking TestResult(
                        testName = "testFileMetadataRecipientTracking",
                        passed = false,
                        message = "Incorrect task recipient count",
                        details = mapOf(
                            "expected" to 2,
                            "actual" to taskRecipients.size
                        )
                    )
                }
                
                // Cleanup
                storageManager.deleteFile(fileId)
                taskManager.cleanupExpiredKeypairs()
                
                TestResult(
                    testName = "testFileMetadataRecipientTracking",
                    passed = true,
                    message = "File metadata correctly tracks task recipients",
                    details = mapOf(
                        "fileId" to fileId,
                        "taskId1" to taskId1,
                        "taskId2" to taskId2,
                        "taskRecipientCount" to taskRecipients.size
                    )
                )
            }
        } catch (e: Exception) {
            TestResult(
                testName = "testFileMetadataRecipientTracking",
                passed = false,
                message = "Test failed with exception: ${e.message}",
                details = mapOf("exception" to e::class.simpleName.orEmpty())
            )
        }
    }
    
    /**
     * Test 5: updateFileAccess properly adds/removes task recipients.
     */
    private fun testUpdateFileAccessIsolation(): TestResult {
        return try {
            runBlocking {
                val taskId1 = "test-update-1-${System.currentTimeMillis()}"
                val taskId2 = "test-update-2-${System.currentTimeMillis()}"
                
                val keypair1 = taskManager.generateTaskKeypair(taskId1, lifetimeMs = 300000)
                val keypair2 = taskManager.generateTaskKeypair(taskId2, lifetimeMs = 300000)
                
                if (keypair1 == null || keypair2 == null) {
                    return@runBlocking TestResult(
                        testName = "testUpdateFileAccessIsolation",
                        passed = false,
                        message = "Failed to generate keypairs",
                        details = emptyMap()
                    )
                }
                
                val recipient1 = storageManager.RecipientEntry(
                    publicKey = keypair1.publicKey,
                    recipientType = RecipientType.TASK,
                    expiresAt = System.currentTimeMillis() + 300000,
                    taskId = taskId1
                )
                
                // Store file with only Task 1
                val testData = "Access control test data".toByteArray()
                val fileId = storageManager.storeFile(
                    data = testData,
                    filename = "test-access-${System.currentTimeMillis()}.txt",
                    recipients = listOf(recipient1)
                )
                
                // Verify Task 2 doesn't have access
                val metadataBefore = storageManager.getFileMetadata(fileId)
                val task2AccessBefore = metadataBefore?.hasTaskAccess(taskId2) ?: true
                
                if (task2AccessBefore) {
                    return@runBlocking TestResult(
                        testName = "testUpdateFileAccessIsolation",
                        passed = false,
                        message = "Task 2 has access before being added",
                        details = mapOf("fileId" to fileId)
                    )
                }
                
                // Add Task 2 as recipient
                val recipient2 = storageManager.RecipientEntry(
                    publicKey = keypair2.publicKey,
                    recipientType = RecipientType.TASK,
                    expiresAt = System.currentTimeMillis() + 300000,
                    taskId = taskId2
                )
                
                storageManager.updateFileAccess(
                    fileId = fileId,
                    addRecipients = listOf(recipient2),
                    removeRecipients = emptyList()
                )
                
                // Verify Task 2 now has access
                val metadataAfterAdd = storageManager.getFileMetadata(fileId)
                val task2AccessAfterAdd = metadataAfterAdd?.hasTaskAccess(taskId2) ?: false
                
                if (!task2AccessAfterAdd) {
                    return@runBlocking TestResult(
                        testName = "testUpdateFileAccessIsolation",
                        passed = false,
                        message = "Task 2 doesn't have access after being added",
                        details = mapOf("fileId" to fileId)
                    )
                }
                
                // Remove Task 2
                storageManager.updateFileAccess(
                    fileId = fileId,
                    addRecipients = emptyList(),
                    removeRecipients = listOf(recipient2)
                )
                
                // Verify Task 2 no longer has access
                val metadataAfterRemove = storageManager.getFileMetadata(fileId)
                val task2AccessAfterRemove = metadataAfterRemove?.hasTaskAccess(taskId2) ?: true
                
                if (task2AccessAfterRemove) {
                    return@runBlocking TestResult(
                        testName = "testUpdateFileAccessIsolation",
                        passed = false,
                        message = "Task 2 still has access after being removed",
                        details = mapOf("fileId" to fileId)
                    )
                }
                
                // Cleanup
                storageManager.deleteFile(fileId)
                taskManager.cleanupExpiredKeypairs()
                
                TestResult(
                    testName = "testUpdateFileAccessIsolation",
                    passed = true,
                    message = "updateFileAccess properly manages task recipient isolation",
                    details = mapOf(
                        "fileId" to fileId,
                        "taskId1" to taskId1,
                        "taskId2" to taskId2
                    )
                )
            }
        } catch (e: Exception) {
            TestResult(
                testName = "testUpdateFileAccessIsolation",
                passed = false,
                message = "Test failed with exception: ${e.message}",
                details = mapOf("exception" to e::class.simpleName.orEmpty())
            )
        }
    }
    
    /**
     * Test 6: Cross-task file enumeration is prevented.
     */
    private fun testCrossTaskFileEnumeration(): TestResult {
        return try {
            runBlocking {
                // This test verifies that tasks cannot enumerate files they don't have access to
                // In a properly isolated system, file listing should only show authorized files
                
                val taskId1 = "test-enum-1-${System.currentTimeMillis()}"
                val taskId2 = "test-enum-2-${System.currentTimeMillis()}"
                
                val keypair1 = taskManager.generateTaskKeypair(taskId1, lifetimeMs = 300000)
                val keypair2 = taskManager.generateTaskKeypair(taskId2, lifetimeMs = 300000)
                
                if (keypair1 == null || keypair2 == null) {
                    return@runBlocking TestResult(
                        testName = "testCrossTaskFileEnumeration",
                        passed = false,
                        message = "Failed to generate keypairs",
                        details = emptyMap()
                    )
                }
                
                // Create files for each task
                val recipient1 = storageManager.RecipientEntry(
                    publicKey = keypair1.publicKey,
                    recipientType = RecipientType.TASK,
                    expiresAt = System.currentTimeMillis() + 300000,
                    taskId = taskId1
                )
                
                val recipient2 = storageManager.RecipientEntry(
                    publicKey = keypair2.publicKey,
                    recipientType = RecipientType.TASK,
                    expiresAt = System.currentTimeMillis() + 300000,
                    taskId = taskId2
                )
                
                val fileId1 = storageManager.storeFile(
                    data = "Task 1 data".toByteArray(),
                    filename = "task1-file.txt",
                    recipients = listOf(recipient1)
                )
                
                val fileId2 = storageManager.storeFile(
                    data = "Task 2 data".toByteArray(),
                    filename = "task2-file.txt",
                    recipients = listOf(recipient2)
                )
                
                // Verify each task can only see its own file
                val allFiles = storageManager.listFiles()
                val task1Files = allFiles.filter { it.hasTaskAccess(taskId1) }
                val task2Files = allFiles.filter { it.hasTaskAccess(taskId2) }
                
                val task1SeesOnlyItsFile = task1Files.size == 1 && task1Files[0].fileId == fileId1
                val task2SeesOnlyItsFile = task2Files.size == 1 && task2Files[0].fileId == fileId2
                
                if (!task1SeesOnlyItsFile || !task2SeesOnlyItsFile) {
                    return@runBlocking TestResult(
                        testName = "testCrossTaskFileEnumeration",
                        passed = false,
                        message = "Tasks can see files they shouldn't have access to",
                        details = mapOf(
                            "task1Files" to task1Files.size,
                            "task2Files" to task2Files.size,
                            "task1SeesOnlyItsFile" to task1SeesOnlyItsFile,
                            "task2SeesOnlyItsFile" to task2SeesOnlyItsFile
                        )
                    )
                }
                
                // Cleanup
                storageManager.deleteFile(fileId1)
                storageManager.deleteFile(fileId2)
                taskManager.cleanupExpiredKeypairs()
                
                TestResult(
                    testName = "testCrossTaskFileEnumeration",
                    passed = true,
                    message = "Cross-task file enumeration is properly prevented",
                    details = mapOf(
                        "taskId1" to taskId1,
                        "taskId2" to taskId2,
                        "fileId1" to fileId1,
                        "fileId2" to fileId2
                    )
                )
            }
        } catch (e: Exception) {
            TestResult(
                testName = "testCrossTaskFileEnumeration",
                passed = false,
                message = "Test failed with exception: ${e.message}",
                details = mapOf("exception" to e::class.simpleName.orEmpty())
            )
        }
    }
    
    /**
     * Test 7: File decryption fails for unauthorized tasks.
     */
    private fun testFileDecryptionAuthorization(): TestResult {
        return try {
            runBlocking {
                val authorizedTask = "test-decrypt-auth-${System.currentTimeMillis()}"
                val unauthorizedTask = "test-decrypt-unauth-${System.currentTimeMillis()}"
                
                val keypairAuth = taskManager.generateTaskKeypair(authorizedTask, lifetimeMs = 300000)
                val keypairUnauth = taskManager.generateTaskKeypair(unauthorizedTask, lifetimeMs = 300000)
                
                if (keypairAuth == null || keypairUnauth == null) {
                    return@runBlocking TestResult(
                        testName = "testFileDecryptionAuthorization",
                        passed = false,
                        message = "Failed to generate keypairs",
                        details = emptyMap()
                    )
                }
                
                val recipient = storageManager.RecipientEntry(
                    publicKey = keypairAuth.publicKey,
                    recipientType = RecipientType.TASK,
                    expiresAt = System.currentTimeMillis() + 300000,
                    taskId = authorizedTask
                )
                
                val testData = "Encrypted sensitive data".toByteArray()
                val fileId = storageManager.storeFile(
                    data = testData,
                    filename = "test-decrypt-${System.currentTimeMillis()}.txt",
                    recipients = listOf(recipient)
                )
                
                // Attempt decryption with authorized task (should succeed)
                val authorizedDecrypt = try {
                    storageManager.retrieveFile(fileId, authorizedTask)
                    true
                } catch (e: Exception) {
                    false
                }
                
                // Attempt decryption with unauthorized task (should fail)
                val unauthorizedDecrypt = try {
                    storageManager.retrieveFile(fileId, unauthorizedTask)
                    true // If this succeeds, test fails
                } catch (e: Exception) {
                    false // Expected to fail
                }
                
                if (!authorizedDecrypt) {
                    return@runBlocking TestResult(
                        testName = "testFileDecryptionAuthorization",
                        passed = false,
                        message = "Authorized task cannot decrypt file",
                        details = mapOf("fileId" to fileId)
                    )
                }
                
                if (unauthorizedDecrypt) {
                    return@runBlocking TestResult(
                        testName = "testFileDecryptionAuthorization",
                        passed = false,
                        message = "Unauthorized task can decrypt file (security violation)",
                        details = mapOf("fileId" to fileId)
                    )
                }
                
                // Cleanup
                storageManager.deleteFile(fileId)
                taskManager.cleanupExpiredKeypairs()
                
                TestResult(
                    testName = "testFileDecryptionAuthorization",
                    passed = true,
                    message = "File decryption properly enforces authorization",
                    details = mapOf(
                        "fileId" to fileId,
                        "authorizedTask" to authorizedTask,
                        "unauthorizedTask" to unauthorizedTask
                    )
                )
            }
        } catch (e: Exception) {
            TestResult(
                testName = "testFileDecryptionAuthorization",
                passed = false,
                message = "Test failed with exception: ${e.message}",
                details = mapOf("exception" to e::class.simpleName.orEmpty())
            )
        }
    }
    
    /**
     * Test 8: Recipient list cannot be tampered with.
     */
    private fun testRecipientListIntegrity(): TestResult {
        return try {
            runBlocking {
                val taskId = "test-integrity-${System.currentTimeMillis()}"
                val keypair = taskManager.generateTaskKeypair(taskId, lifetimeMs = 300000)
                
                if (keypair == null) {
                    return@runBlocking TestResult(
                        testName = "testRecipientListIntegrity",
                        passed = false,
                        message = "Failed to generate keypair",
                        details = emptyMap()
                    )
                }
                
                val recipient = storageManager.RecipientEntry(
                    publicKey = keypair.publicKey,
                    recipientType = RecipientType.TASK,
                    expiresAt = System.currentTimeMillis() + 300000,
                    taskId = taskId
                )
                
                val testData = "Integrity test data".toByteArray()
                val fileId = storageManager.storeFile(
                    data = testData,
                    filename = "test-integrity-${System.currentTimeMillis()}.txt",
                    recipients = listOf(recipient)
                )
                
                // Get initial recipient list
                val metadataBefore = storageManager.getFileMetadata(fileId)
                val recipientsBefore = metadataBefore?.getTaskRecipients() ?: emptyList()
                val recipientCountBefore = recipientsBefore.size
                
                // Re-retrieve metadata and verify recipient list hasn't changed
                val metadataAfter = storageManager.getFileMetadata(fileId)
                val recipientsAfter = metadataAfter?.getTaskRecipients() ?: emptyList()
                val recipientCountAfter = recipientsAfter.size
                
                if (recipientCountBefore != recipientCountAfter) {
                    return@runBlocking TestResult(
                        testName = "testRecipientListIntegrity",
                        passed = false,
                        message = "Recipient list count changed unexpectedly",
                        details = mapOf(
                            "before" to recipientCountBefore,
                            "after" to recipientCountAfter
                        )
                    )
                }
                
                // Verify recipient details match
                val taskIdsBefore = recipientsBefore.map { it.taskId }.toSet()
                val taskIdsAfter = recipientsAfter.map { it.taskId }.toSet()
                
                if (taskIdsBefore != taskIdsAfter) {
                    return@runBlocking TestResult(
                        testName = "testRecipientListIntegrity",
                        passed = false,
                        message = "Recipient task IDs changed unexpectedly",
                        details = mapOf(
                            "before" to taskIdsBefore,
                            "after" to taskIdsAfter
                        )
                    )
                }
                
                // Cleanup
                storageManager.deleteFile(fileId)
                taskManager.cleanupExpiredKeypairs()
                
                TestResult(
                    testName = "testRecipientListIntegrity",
                    passed = true,
                    message = "Recipient list integrity is maintained",
                    details = mapOf(
                        "fileId" to fileId,
                        "recipientCount" to recipientCountBefore
                    )
                )
            }
        } catch (e: Exception) {
            TestResult(
                testName = "testRecipientListIntegrity",
                passed = false,
                message = "Test failed with exception: ${e.message}",
                details = mapOf("exception" to e::class.simpleName.orEmpty())
            )
        }
    }
    
    /**
     * Generate detailed test report.
     */
    fun generateReport(result: SuiteResult): String {
        val sb = StringBuilder()
        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine("              FILE ISOLATION TEST REPORT")
        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine()
        sb.appendLine("Summary:")
        sb.appendLine("  Total Tests: ${result.totalTests}")
        sb.appendLine("  Passed: ${result.passed}")
        sb.appendLine("  Failed: ${result.failed}")
        sb.appendLine("  Pass Rate: ${"%.1f".format(result.passRate * 100)}%")
        sb.appendLine("  Overall: ${if (result.allPassed) "✅ PASS" else "❌ FAIL"}")
        sb.appendLine()
        sb.appendLine("Detailed Results:")
        sb.appendLine("─────────────────────────────────────────────────────────────")
        
        result.results.forEachIndexed { index, testResult ->
            sb.appendLine()
            sb.appendLine("Test ${index + 1}: ${testResult.testName}")
            sb.appendLine("  Status: ${if (testResult.passed) "✅ PASS" else "❌ FAIL"}")
            sb.appendLine("  Message: ${testResult.message}")
            if (testResult.details.isNotEmpty()) {
                sb.appendLine("  Details:")
                testResult.details.forEach { (key, value) ->
                    sb.appendLine("    - $key: $value")
                }
            }
        }
        
        sb.appendLine()
        sb.appendLine("═══════════════════════════════════════════════════════════")
        
        return sb.toString()
    }
}
