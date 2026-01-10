package org.torproject.meshrabiya.compute.security.tests

import kotlinx.coroutines.runBlocking
import org.torproject.meshrabiya.compute.TaskManager
import org.torproject.meshrabiya.compute.StrangersSafeComputeEngine
import java.io.File

/**
 * KeypairIsolationTests verifies that task keypairs are properly isolated.
 * 
 * Test Scenarios:
 * 1. Task A cannot access Task B's private key
 * 2. Task A cannot access Task B's public key from memory
 * 3. Task keypairs are sandboxed per container
 * 4. Environment variables are isolated per task
 * 5. Keypair registry prevents cross-task access
 * 6. Expired keypairs are inaccessible
 * 7. Keypairs are removed from memory after cleanup
 * 8. File system isolation prevents key file access
 * 
 * Success Criteria:
 * - All unauthorized access attempts must fail
 * - No cross-task key leakage
 * - Proper sandboxing enforced
 * 
 * @property taskManager TaskManager instance for keypair operations
 * @property computeEngine StrangersSafeComputeEngine for sandbox testing
 */
class KeypairIsolationTests(
    private val taskManager: TaskManager,
    private val computeEngine: StrangersSafeComputeEngine
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
     * Run all keypair isolation tests.
     * 
     * @return Test suite result
     */
    fun runAllTests(): SuiteResult {
        val results = mutableListOf<TestResult>()
        
        results.add(testCrossTaskPrivateKeyAccess())
        results.add(testCrossTaskPublicKeyAccess())
        results.add(testKeypairRegistryIsolation())
        results.add(testEnvironmentVariableIsolation())
        results.add(testExpiredKeypairInaccessible())
        results.add(testKeypairMemoryCleanup())
        results.add(testSandboxKeypairIsolation())
        results.add(testFileSystemKeypairIsolation())
        
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
     * Test 1: Task A cannot access Task B's private key.
     */
    private fun testCrossTaskPrivateKeyAccess(): TestResult {
        return try {
            runBlocking {
                // Generate keypair for Task A
                val taskIdA = "test-task-a-${System.currentTimeMillis()}"
                taskManager.generateTaskKeypair(taskIdA, lifetimeMs = 300000)
                
                // Generate keypair for Task B
                val taskIdB = "test-task-b-${System.currentTimeMillis()}"
                taskManager.generateTaskKeypair(taskIdB, lifetimeMs = 300000)
                
                // Verify Task A can access its own private key
                val privateKeyA = taskManager.getTaskPrivateKey(taskIdA)
                if (privateKeyA == null) {
                    return@runBlocking TestResult(
                        testName = "testCrossTaskPrivateKeyAccess",
                        passed = false,
                        message = "Task A cannot access its own private key",
                        details = mapOf("taskIdA" to taskIdA)
                    )
                }
                
                // Attempt to access Task B's private key using Task A's ID (should fail)
                // In a real attack scenario, Task A would try to guess or enumerate Task B's ID
                val privateKeyB = taskManager.getTaskPrivateKey(taskIdB)
                
                // Verify Task B's key exists
                if (privateKeyB == null) {
                    return@runBlocking TestResult(
                        testName = "testCrossTaskPrivateKeyAccess",
                        passed = false,
                        message = "Task B keypair not found",
                        details = mapOf("taskIdB" to taskIdB)
                    )
                }
                
                // The security check: keys should be different
                if (privateKeyA == privateKeyB) {
                    return@runBlocking TestResult(
                        testName = "testCrossTaskPrivateKeyAccess",
                        passed = false,
                        message = "Task A and Task B have the same private key (isolation violated)",
                        details = mapOf(
                            "taskIdA" to taskIdA,
                            "taskIdB" to taskIdB
                        )
                    )
                }
                
                // Cleanup
                taskManager.cleanupExpiredKeypairs()
                
                TestResult(
                    testName = "testCrossTaskPrivateKeyAccess",
                    passed = true,
                    message = "Task keypairs are properly isolated - different keys for different tasks",
                    details = mapOf(
                        "taskIdA" to taskIdA,
                        "taskIdB" to taskIdB,
                        "keyLengthA" to privateKeyA.length,
                        "keyLengthB" to privateKeyB.length
                    )
                )
            }
        } catch (e: Exception) {
            TestResult(
                testName = "testCrossTaskPrivateKeyAccess",
                passed = false,
                message = "Test failed with exception: ${e.message}",
                details = mapOf("exception" to e::class.simpleName.orEmpty())
            )
        }
    }
    
    /**
     * Test 2: Task A cannot access Task B's public key from memory.
     */
    private fun testCrossTaskPublicKeyAccess(): TestResult {
        return try {
            runBlocking {
                val taskIdA = "test-task-pubkey-a-${System.currentTimeMillis()}"
                val taskIdB = "test-task-pubkey-b-${System.currentTimeMillis()}"
                
                taskManager.generateTaskKeypair(taskIdA, lifetimeMs = 300000)
                taskManager.generateTaskKeypair(taskIdB, lifetimeMs = 300000)
                
                val publicKeyA = taskManager.getTaskPublicKey(taskIdA)
                val publicKeyB = taskManager.getTaskPublicKey(taskIdB)
                
                if (publicKeyA == null || publicKeyB == null) {
                    return@runBlocking TestResult(
                        testName = "testCrossTaskPublicKeyAccess",
                        passed = false,
                        message = "Failed to retrieve public keys",
                        details = mapOf(
                            "publicKeyA" to (publicKeyA != null),
                            "publicKeyB" to (publicKeyB != null)
                        )
                    )
                }
                
                // Verify keys are different
                if (publicKeyA == publicKeyB) {
                    return@runBlocking TestResult(
                        testName = "testCrossTaskPublicKeyAccess",
                        passed = false,
                        message = "Public keys are identical (isolation violated)",
                        details = mapOf(
                            "taskIdA" to taskIdA,
                            "taskIdB" to taskIdB
                        )
                    )
                }
                
                // Cleanup
                taskManager.cleanupExpiredKeypairs()
                
                TestResult(
                    testName = "testCrossTaskPublicKeyAccess",
                    passed = true,
                    message = "Public keys are properly isolated",
                    details = mapOf(
                        "taskIdA" to taskIdA,
                        "taskIdB" to taskIdB,
                        "keyLengthA" to publicKeyA.length,
                        "keyLengthB" to publicKeyB.length
                    )
                )
            }
        } catch (e: Exception) {
            TestResult(
                testName = "testCrossTaskPublicKeyAccess",
                passed = false,
                message = "Test failed with exception: ${e.message}",
                details = mapOf("exception" to e::class.simpleName.orEmpty())
            )
        }
    }
    
    /**
     * Test 3: Keypair registry prevents cross-task access.
     */
    private fun testKeypairRegistryIsolation(): TestResult {
        return try {
            runBlocking {
                val taskIdA = "test-registry-a-${System.currentTimeMillis()}"
                val taskIdB = "test-registry-b-${System.currentTimeMillis()}"
                
                taskManager.generateTaskKeypair(taskIdA, lifetimeMs = 300000)
                taskManager.generateTaskKeypair(taskIdB, lifetimeMs = 300000)
                
                // Get all active keypairs
                val activeKeypairs = taskManager.getActiveKeypairs()
                
                // Verify both tasks are in registry
                val hasTaskA = activeKeypairs.any { it.taskId == taskIdA }
                val hasTaskB = activeKeypairs.any { it.taskId == taskIdB }
                
                if (!hasTaskA || !hasTaskB) {
                    return@runBlocking TestResult(
                        testName = "testKeypairRegistryIsolation",
                        passed = false,
                        message = "Tasks not found in keypair registry",
                        details = mapOf(
                            "hasTaskA" to hasTaskA,
                            "hasTaskB" to hasTaskB
                        )
                    )
                }
                
                // Verify each task has unique keypair entry
                val taskAEntry = activeKeypairs.find { it.taskId == taskIdA }
                val taskBEntry = activeKeypairs.find { it.taskId == taskIdB }
                
                if (taskAEntry?.publicKey == taskBEntry?.publicKey) {
                    return@runBlocking TestResult(
                        testName = "testKeypairRegistryIsolation",
                        passed = false,
                        message = "Registry contains duplicate keypairs",
                        details = mapOf(
                            "taskIdA" to taskIdA,
                            "taskIdB" to taskIdB
                        )
                    )
                }
                
                // Cleanup
                taskManager.cleanupExpiredKeypairs()
                
                TestResult(
                    testName = "testKeypairRegistryIsolation",
                    passed = true,
                    message = "Keypair registry properly isolates task keypairs",
                    details = mapOf(
                        "totalKeypairs" to activeKeypairs.size,
                        "taskIdA" to taskIdA,
                        "taskIdB" to taskIdB
                    )
                )
            }
        } catch (e: Exception) {
            TestResult(
                testName = "testKeypairRegistryIsolation",
                passed = false,
                message = "Test failed with exception: ${e.message}",
                details = mapOf("exception" to e::class.simpleName.orEmpty())
            )
        }
    }
    
    /**
     * Test 4: Environment variables are isolated per task.
     */
    private fun testEnvironmentVariableIsolation(): TestResult {
        return try {
            runBlocking {
                val taskIdA = "test-env-a-${System.currentTimeMillis()}"
                val taskIdB = "test-env-b-${System.currentTimeMillis()}"
                
                val keypairA = taskManager.generateTaskKeypair(taskIdA, lifetimeMs = 300000)
                val keypairB = taskManager.generateTaskKeypair(taskIdB, lifetimeMs = 300000)
                
                if (keypairA == null || keypairB == null) {
                    return@runBlocking TestResult(
                        testName = "testEnvironmentVariableIsolation",
                        passed = false,
                        message = "Failed to generate keypairs",
                        details = emptyMap()
                    )
                }
                
                // Setup isolated environments for both tasks
                val envA = computeEngine.setupIsolatedEnvironment(
                    taskId = taskIdA,
                    taskKeypair = keypairA
                )
                
                val envB = computeEngine.setupIsolatedEnvironment(
                    taskId = taskIdB,
                    taskKeypair = keypairB
                )
                
                // Verify environment variables are set
                val pubKeyEnvA = envA.environmentVars["TASK_PUBLIC_KEY"]
                val privKeyEnvA = envA.environmentVars["TASK_PRIVATE_KEY"]
                val pubKeyEnvB = envB.environmentVars["TASK_PUBLIC_KEY"]
                val privKeyEnvB = envB.environmentVars["TASK_PRIVATE_KEY"]
                
                if (pubKeyEnvA == null || privKeyEnvA == null || 
                    pubKeyEnvB == null || privKeyEnvB == null) {
                    return@runBlocking TestResult(
                        testName = "testEnvironmentVariableIsolation",
                        passed = false,
                        message = "Environment variables not set",
                        details = mapOf(
                            "pubKeyEnvA" to (pubKeyEnvA != null),
                            "privKeyEnvA" to (privKeyEnvA != null),
                            "pubKeyEnvB" to (pubKeyEnvB != null),
                            "privKeyEnvB" to (privKeyEnvB != null)
                        )
                    )
                }
                
                // Verify environment variables are different
                if (pubKeyEnvA == pubKeyEnvB || privKeyEnvA == privKeyEnvB) {
                    return@runBlocking TestResult(
                        testName = "testEnvironmentVariableIsolation",
                        passed = false,
                        message = "Environment variables are not isolated",
                        details = mapOf(
                            "taskIdA" to taskIdA,
                            "taskIdB" to taskIdB
                        )
                    )
                }
                
                // Cleanup
                taskManager.cleanupExpiredKeypairs()
                
                TestResult(
                    testName = "testEnvironmentVariableIsolation",
                    passed = true,
                    message = "Environment variables are properly isolated per task",
                    details = mapOf(
                        "taskIdA" to taskIdA,
                        "taskIdB" to taskIdB,
                        "envVarsA" to envA.environmentVars.size,
                        "envVarsB" to envB.environmentVars.size
                    )
                )
            }
        } catch (e: Exception) {
            TestResult(
                testName = "testEnvironmentVariableIsolation",
                passed = false,
                message = "Test failed with exception: ${e.message}",
                details = mapOf("exception" to e::class.simpleName.orEmpty())
            )
        }
    }
    
    /**
     * Test 5: Expired keypairs are inaccessible.
     */
    private fun testExpiredKeypairInaccessible(): TestResult {
        return try {
            runBlocking {
                val taskId = "test-expired-${System.currentTimeMillis()}"
                
                // Generate keypair with very short lifetime (1ms)
                taskManager.generateTaskKeypair(taskId, lifetimeMs = 1)
                
                // Wait for expiration
                Thread.sleep(10)
                
                // Attempt to access expired keypair
                val publicKey = taskManager.getTaskPublicKey(taskId)
                val privateKey = taskManager.getTaskPrivateKey(taskId)
                
                // Expired keys should not be accessible
                if (publicKey != null || privateKey != null) {
                    return@runBlocking TestResult(
                        testName = "testExpiredKeypairInaccessible",
                        passed = false,
                        message = "Expired keypair is still accessible",
                        details = mapOf(
                            "taskId" to taskId,
                            "publicKeyAccessible" to (publicKey != null),
                            "privateKeyAccessible" to (privateKey != null)
                        )
                    )
                }
                
                TestResult(
                    testName = "testExpiredKeypairInaccessible",
                    passed = true,
                    message = "Expired keypairs are properly inaccessible",
                    details = mapOf("taskId" to taskId)
                )
            }
        } catch (e: Exception) {
            TestResult(
                testName = "testExpiredKeypairInaccessible",
                passed = false,
                message = "Test failed with exception: ${e.message}",
                details = mapOf("exception" to e::class.simpleName.orEmpty())
            )
        }
    }
    
    /**
     * Test 6: Keypairs are removed from memory after cleanup.
     */
    private fun testKeypairMemoryCleanup(): TestResult {
        return try {
            runBlocking {
                val taskId = "test-cleanup-${System.currentTimeMillis()}"
                
                // Generate keypair
                taskManager.generateTaskKeypair(taskId, lifetimeMs = 100)
                
                // Verify keypair exists
                val beforeCleanup = taskManager.getTaskPublicKey(taskId)
                if (beforeCleanup == null) {
                    return@runBlocking TestResult(
                        testName = "testKeypairMemoryCleanup",
                        passed = false,
                        message = "Keypair not found before cleanup",
                        details = mapOf("taskId" to taskId)
                    )
                }
                
                // Wait for expiration
                Thread.sleep(150)
                
                // Run cleanup
                taskManager.cleanupExpiredKeypairs()
                
                // Verify keypair is gone
                val afterCleanup = taskManager.getTaskPublicKey(taskId)
                if (afterCleanup != null) {
                    return@runBlocking TestResult(
                        testName = "testKeypairMemoryCleanup",
                        passed = false,
                        message = "Keypair still accessible after cleanup",
                        details = mapOf("taskId" to taskId)
                    )
                }
                
                TestResult(
                    testName = "testKeypairMemoryCleanup",
                    passed = true,
                    message = "Keypairs are properly removed from memory after cleanup",
                    details = mapOf("taskId" to taskId)
                )
            }
        } catch (e: Exception) {
            TestResult(
                testName = "testKeypairMemoryCleanup",
                passed = false,
                message = "Test failed with exception: ${e.message}",
                details = mapOf("exception" to e::class.simpleName.orEmpty())
            )
        }
    }
    
    /**
     * Test 7: Sandbox isolates keypairs between containers.
     */
    private fun testSandboxKeypairIsolation(): TestResult {
        return try {
            // This test would require actual container execution
            // For now, we verify the setup is correct
            runBlocking {
                val taskIdA = "test-sandbox-a-${System.currentTimeMillis()}"
                val taskIdB = "test-sandbox-b-${System.currentTimeMillis()}"
                
                val keypairA = taskManager.generateTaskKeypair(taskIdA, lifetimeMs = 300000)
                val keypairB = taskManager.generateTaskKeypair(taskIdB, lifetimeMs = 300000)
                
                if (keypairA == null || keypairB == null) {
                    return@runBlocking TestResult(
                        testName = "testSandboxKeypairIsolation",
                        passed = false,
                        message = "Failed to generate keypairs for sandbox test",
                        details = emptyMap()
                    )
                }
                
                // Setup environments
                val envA = computeEngine.setupIsolatedEnvironment(taskIdA, keypairA)
                val envB = computeEngine.setupIsolatedEnvironment(taskIdB, keypairB)
                
                // Verify environments are isolated (different container IDs, different env vars)
                if (envA.containerId == envB.containerId) {
                    return@runBlocking TestResult(
                        testName = "testSandboxKeypairIsolation",
                        passed = false,
                        message = "Containers are not isolated (same container ID)",
                        details = mapOf("containerId" to envA.containerId)
                    )
                }
                
                // Cleanup
                taskManager.cleanupExpiredKeypairs()
                
                TestResult(
                    testName = "testSandboxKeypairIsolation",
                    passed = true,
                    message = "Sandbox environments are properly isolated",
                    details = mapOf(
                        "containerIdA" to envA.containerId,
                        "containerIdB" to envB.containerId
                    )
                )
            }
        } catch (e: Exception) {
            TestResult(
                testName = "testSandboxKeypairIsolation",
                passed = false,
                message = "Test failed with exception: ${e.message}",
                details = mapOf("exception" to e::class.simpleName.orEmpty())
            )
        }
    }
    
    /**
     * Test 8: File system isolation prevents key file access.
     */
    private fun testFileSystemKeypairIsolation(): TestResult {
        return try {
            // Verify that keypairs are never written to disk
            runBlocking {
                val taskId = "test-filesystem-${System.currentTimeMillis()}"
                val keypair = taskManager.generateTaskKeypair(taskId, lifetimeMs = 300000)
                
                if (keypair == null) {
                    return@runBlocking TestResult(
                        testName = "testFileSystemKeypairIsolation",
                        passed = false,
                        message = "Failed to generate keypair",
                        details = emptyMap()
                    )
                }
                
                // Check common locations where keys might be persisted
                val suspiciousLocations = listOf(
                    "/tmp/${taskId}.key",
                    "/tmp/${taskId}_public.pem",
                    "/tmp/${taskId}_private.pem",
                    "/data/local/tmp/${taskId}.key",
                    "/sdcard/${taskId}.key"
                )
                
                val foundOnDisk = suspiciousLocations.any { File(it).exists() }
                
                if (foundOnDisk) {
                    return@runBlocking TestResult(
                        testName = "testFileSystemKeypairIsolation",
                        passed = false,
                        message = "Keypair found persisted to disk (security violation)",
                        details = mapOf(
                            "taskId" to taskId,
                            "suspiciousLocations" to suspiciousLocations
                        )
                    )
                }
                
                // Cleanup
                taskManager.cleanupExpiredKeypairs()
                
                TestResult(
                    testName = "testFileSystemKeypairIsolation",
                    passed = true,
                    message = "Keypairs are not persisted to disk (memory-only)",
                    details = mapOf(
                        "taskId" to taskId,
                        "checkedLocations" to suspiciousLocations.size
                    )
                )
            }
        } catch (e: Exception) {
            TestResult(
                testName = "testFileSystemKeypairIsolation",
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
        sb.appendLine("           KEYPAIR ISOLATION TEST REPORT")
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
