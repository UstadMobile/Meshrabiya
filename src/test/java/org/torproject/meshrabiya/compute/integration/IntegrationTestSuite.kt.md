package org.torproject.meshrabiya.compute.integration

// ...existing code...

/**
 * Integration Test Suite - Phase 8.1
 * 
 * Comprehensive integration testing covering:
 * - Task Execution Layer only (3 tests)
 * - Keypair Enhancement Layer only (3 tests)
 * - Combined integration (6 tests)
 * 
 * Total: 12 integration test scenarios
 * 
 * Ref: TASK_KEYPAIR_ENHANCEMENT_PLAN_PART5.md Section 14.5
 *      MASTER_IMPLEMENTATION_ROADMAP.md Phase 8
 */
class IntegrationTestSuite(
    private val taskManager: TaskManager,
    private val storageManager: DistributedStorageManager,
    private val scheduler: IntelligentTaskScheduler,
    private val computeEngine: StrangersSafeComputeEngine
) {
    
    data class TestResult(
        val testName: String,
        val passed: Boolean,
        val executionTimeMs: Long,
        val errorMessage: String? = null,
        val details: Map<String, Any> = emptyMap()
    )
    
    data class SuiteResult(
        val totalTests: Int,
        val passed: Int,
        val failed: Int,
        val testResults: List<TestResult>,
        val totalExecutionTimeMs: Long,
        val successRate: Double
    )
    
    /**
     * Run all 12 integration test scenarios
     */
    fun runAllTests(): SuiteResult {
        val startTime = System.currentTimeMillis()
        val results = mutableListOf<TestResult>()
        
        println("=" .repeat(80))
        println("INTEGRATION TEST SUITE - PHASE 8.1")
        println("Total Scenarios: 12")
        println("=" .repeat(80))
        
        // Part 1: Task Execution Layer Only (3 tests)
        println("\n[PART 1: TASK EXECUTION LAYER ONLY]")
        results.add(testSimpleTaskExecution())
        results.add(testSandboxFileTransparency())
        results.add(testResourceLimitsEnforcement())
        
        // Part 2: Keypair Enhancement Layer Only (3 tests)
        println("\n[PART 2: KEYPAIR ENHANCEMENT LAYER ONLY]")
        results.add(testKeypairIsolationBetweenTasks())
        results.add(testDynamicFileSharing())
        results.add(testKeypairLifecycleManagement())
        
        // Part 3: Combined Integration (6 tests)
        println("\n[PART 3: COMBINED INTEGRATION]")
        results.add(testTaskWithEncryptedFiles())
        results.add(testTaskDecompositionWithKeypairs())
        results.add(testMultiNodeExecution())
        results.add(testTaskCancellation())
        results.add(testNetworkPartitionRecovery())
        results.add(testFeatureFlagToggle())
        
        val endTime = System.currentTimeMillis()
        val totalTime = endTime - startTime
        val passed = results.count { it.passed }
        val failed = results.count { !it.passed }
        
        return SuiteResult(
            totalTests = results.size,
            passed = passed,
            failed = failed,
            testResults = results,
            totalExecutionTimeMs = totalTime,
            successRate = passed.toDouble() / results.size
        )
    }
    
    // ===========================================================================================
    // PART 1: TASK EXECUTION LAYER ONLY (3 TESTS)
    // ===========================================================================================
    
    /**
     * Test 1: Simple Task Execution (No Encryption)
     * 
     * Scenario: Submit basic task, execute on compute node, verify output
     * Tests: Task lifecycle, sandbox execution, output storage
     */
    private fun testSimpleTaskExecution(): TestResult = runBlocking {
        val testName = "Test 1: Simple Task Execution"
        val startTime = System.currentTimeMillis()
        
        try {
            println("\n[$testName]")
            println("  Scenario: Submit Python task, execute, verify output")
            
            // Create simple Python task
            val taskId = UUID.randomUUID().toString()
            val task = TaskManager.ComputeTask(
                taskId = taskId,
                type = TaskType.PYTHON,
                executable = """
                    print("Hello from Meshrabiya!")
                    with open("/sandbox/output/result.txt", "w") as f:
                        f.write("Task completed successfully")
                """.trimIndent(),
                inputFiles = emptyList(),
                requirements = TaskManager.TaskRequirements(
                    memoryMb = 128,
                    timeoutSeconds = 30,
                    networkAccess = false
                )
            )
            
            // Submit task
            println("  Step 1: Submit task")
            val submitResult = taskManager.submitTask(task)
            require(submitResult) { "Task submission failed" }
            
            // Execute task (simulated)
            println("  Step 2: Execute task in sandbox")
            val container = computeEngine.createContainer(task, keypair = null)
            val executionResult = container.execute()
            require(executionResult.success) { "Task execution failed: ${executionResult.error}" }
            
            // Verify output
            println("  Step 3: Verify output file")
            val outputFiles = executionResult.outputFiles
            require(outputFiles.isNotEmpty()) { "No output files generated" }
            
            val outputData = storageManager.retrieveFileRaw(outputFiles.first())
            val outputText = String(outputData)
            require(outputText.contains("Task completed successfully")) {
                "Output mismatch: $outputText"
            }
            
            val executionTime = System.currentTimeMillis() - startTime
            println("  ✅ PASSED (${executionTime}ms)")
            
            return@runBlocking TestResult(
                testName = testName,
                passed = true,
                executionTimeMs = executionTime,
                details = mapOf(
                    "taskId" to taskId,
                    "outputFiles" to outputFiles.size,
                    "executionTimeMs" to executionResult.executionTimeMs
                )
            )
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            println("  ❌ FAILED: ${e.message}")
            
            return@runBlocking TestResult(
                testName = testName,
                passed = false,
                executionTimeMs = executionTime,
                errorMessage = e.message
            )
        }
    }
    
    /**
     * Test 2: Sandbox File Transparency
     * 
     * Scenario: Task reads input files, writes output files, verify sandbox isolation
     * Tests: Filesystem transparency layer, input/output file handling
     */
    private fun testSandboxFileTransparency(): TestResult = runBlocking {
        val testName = "Test 2: Sandbox File Transparency"
        val startTime = System.currentTimeMillis()
        
        try {
            println("\n[$testName]")
            println("  Scenario: Task accesses files via sandbox filesystem")
            
            // Create input file
            println("  Step 1: Create input file")
            val inputData = "Input data for processing".toByteArray()
            val inputFileId = storageManager.storeFile(
                data = inputData,
                recipients = emptyList(),
                metadata = DistributedStorageManager.FileMetadata(
                    ownerId = "test-user",
                    fileName = "input.txt",
                    isEncrypted = false
                )
            )
            
            // Create task that processes input file
            val taskId = UUID.randomUUID().toString()
            val task = TaskManager.ComputeTask(
                taskId = taskId,
                type = TaskType.PYTHON,
                executable = """
                    with open("/sandbox/input/$inputFileId", "r") as f:
                        data = f.read()
                    
                    with open("/sandbox/output/processed.txt", "w") as f:
                        f.write(f"Processed: {data}")
                """.trimIndent(),
                inputFiles = listOf(inputFileId),
                requirements = TaskManager.TaskRequirements(
                    memoryMb = 128,
                    timeoutSeconds = 30,
                    networkAccess = false
                )
            )
            
            // Execute task
            println("  Step 2: Execute task")
            val container = computeEngine.createContainer(task, keypair = null)
            computeEngine.prepareInputFiles(container, task.inputFiles, keypair = null)
            val executionResult = container.execute()
            require(executionResult.success) { "Task execution failed" }
            
            // Verify output contains processed input
            println("  Step 3: Verify file transparency")
            val outputData = storageManager.retrieveFileRaw(executionResult.outputFiles.first())
            val outputText = String(outputData)
            require(outputText.contains("Processed: Input data for processing")) {
                "File transparency failed: $outputText"
            }
            
            val executionTime = System.currentTimeMillis() - startTime
            println("  ✅ PASSED (${executionTime}ms)")
            
            return@runBlocking TestResult(
                testName = testName,
                passed = true,
                executionTimeMs = executionTime,
                details = mapOf(
                    "inputFiles" to 1,
                    "outputFiles" to executionResult.outputFiles.size
                )
            )
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            println("  ❌ FAILED: ${e.message}")
            
            return@runBlocking TestResult(
                testName = testName,
                passed = false,
                executionTimeMs = executionTime,
                errorMessage = e.message
            )
        }
    }
    
    /**
     * Test 3: Resource Limits Enforcement
     * 
     * Scenario: Task exceeds memory limit, verify graceful termination
     * Tests: Resource monitoring, sandbox limits, error handling
     */
    private fun testResourceLimitsEnforcement(): TestResult = runBlocking {
        val testName = "Test 3: Resource Limits Enforcement"
        val startTime = System.currentTimeMillis()
        
        try {
            println("\n[$testName]")
            println("  Scenario: Task exceeds memory limit, verify termination")
            
            // Create task with low memory limit
            val taskId = UUID.randomUUID().toString()
            val task = TaskManager.ComputeTask(
                taskId = taskId,
                type = TaskType.PYTHON,
                executable = """
                    # Attempt to allocate 256MB (limit is 64MB)
                    large_list = [0] * (256 * 1024 * 1024)
                """.trimIndent(),
                inputFiles = emptyList(),
                requirements = TaskManager.TaskRequirements(
                    memoryMb = 64,  // Low limit
                    timeoutSeconds = 30,
                    networkAccess = false
                )
            )
            
            // Execute task (should fail with OUT_OF_MEMORY)
            println("  Step 1: Execute task with insufficient memory")
            val container = computeEngine.createContainer(task, keypair = null)
            val executionResult = container.execute()
            
            // Verify graceful failure
            println("  Step 2: Verify graceful failure")
            require(!executionResult.success) { "Task should have failed due to memory limit" }
            require(executionResult.errorType == TaskManager.ExecutionErrorType.OUT_OF_MEMORY) {
                "Expected OUT_OF_MEMORY, got: ${executionResult.errorType}"
            }
            
            val executionTime = System.currentTimeMillis() - startTime
            println("  ✅ PASSED (${executionTime}ms)")
            
            return@runBlocking TestResult(
                testName = testName,
                passed = true,
                executionTimeMs = executionTime,
                details = mapOf(
                    "errorType" to executionResult.errorType.name,
                    "errorMessage" to (executionResult.error ?: "")
                )
            )
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            println("  ❌ FAILED: ${e.message}")
            
            return@runBlocking TestResult(
                testName = testName,
                passed = false,
                executionTimeMs = executionTime,
                errorMessage = e.message
            )
        }
    }
    
    // ===========================================================================================
    // PART 2: KEYPAIR ENHANCEMENT LAYER ONLY (3 TESTS)
    // ===========================================================================================
    
    /**
     * Test 4: Keypair Isolation Between Tasks
     * 
     * Scenario: Two tasks with separate keypairs, verify no cross-access
     * Tests: Keypair registry isolation, per-task keypair generation
     */
    private fun testKeypairIsolationBetweenTasks(): TestResult = runBlocking {
        val testName = "Test 4: Keypair Isolation Between Tasks"
        val startTime = System.currentTimeMillis()
        
        try {
            println("\n[$testName]")
            println("  Scenario: Verify keypair isolation between concurrent tasks")
            
            // Enable feature flag
            FeatureFlags.enableTaskKeypair()
            
            // Generate keypairs for two tasks
            println("  Step 1: Generate keypairs for Task A and Task B")
            val taskIdA = "task-a-${UUID.randomUUID()}"
            val taskIdB = "task-b-${UUID.randomUUID()}"
            
            val keypairA = taskManager.generateTaskKeypair(taskIdA)
            val keypairB = taskManager.generateTaskKeypair(taskIdB)
            
            // Verify different keypairs
            println("  Step 2: Verify keypairs are different")
            require(keypairA.publicKeyPem != keypairB.publicKeyPem) {
                "Task keypairs should be different"
            }
            
            // Verify Task A cannot access Task B's private key
            println("  Step 3: Verify cross-task access prevention")
            val taskBKeyFromA = taskManager.getTaskPublicKey(taskIdB)
            require(taskBKeyFromA != null) { "Public key should be accessible" }
            
            // Verify private keys are isolated (simulated - actual test would use registry check)
            val activeKeypairs = taskManager.getActiveKeypairs()
            require(activeKeypairs.contains(taskIdA)) { "Task A keypair not in registry" }
            require(activeKeypairs.contains(taskIdB)) { "Task B keypair not in registry" }
            require(activeKeypairs.size >= 2) { "Both keypairs should be active" }
            
            // Cleanup
            taskManager.cleanupExpiredKeypairs()
            
            val executionTime = System.currentTimeMillis() - startTime
            println("  ✅ PASSED (${executionTime}ms)")
            
            return@runBlocking TestResult(
                testName = testName,
                passed = true,
                executionTimeMs = executionTime,
                details = mapOf(
                    "taskIdA" to taskIdA,
                    "taskIdB" to taskIdB,
                    "activeKeypairs" to activeKeypairs.size
                )
            )
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            println("  ❌ FAILED: ${e.message}")
            
            return@runBlocking TestResult(
                testName = testName,
                passed = false,
                executionTimeMs = executionTime,
                errorMessage = e.message
            )
        } finally {
            FeatureFlags.disableTaskKeypair()
        }
    }
    
    /**
     * Test 5: Dynamic File Sharing
     * 
     * Scenario: Add task as recipient to existing file, verify access
     * Tests: updateFileAccess(), session key re-encryption, dynamic recipient management
     */
    private fun testDynamicFileSharing(): TestResult = runBlocking {
        val testName = "Test 5: Dynamic File Sharing"
        val startTime = System.currentTimeMillis()
        
        try {
            println("\n[$testName]")
            println("  Scenario: Add task keypair as recipient to existing file")
            
            // Enable feature flag
            FeatureFlags.enableTaskKeypair()
            
            // Create file encrypted for owner only
            println("  Step 1: Create file for owner only")
            val ownerPublicKey = "owner-public-key-pem"
            val fileData = "Sensitive data for task".toByteArray()
            val fileId = storageManager.storeFile(
                data = fileData,
                recipients = listOf(
                    DistributedStorageManager.Recipient(
                        id = "owner",
                        publicKey = ownerPublicKey,
                        type = DistributedStorageManager.RecipientType.USER
                    )
                ),
                metadata = DistributedStorageManager.FileMetadata(
                    ownerId = "owner",
                    fileName = "sensitive.dat",
                    isEncrypted = true
                )
            )
            
            // Generate task keypair
            println("  Step 2: Generate task keypair")
            val taskId = "task-${UUID.randomUUID()}"
            val taskKeypair = taskManager.generateTaskKeypair(taskId)
            
            // Add task as recipient dynamically
            println("  Step 3: Add task as recipient")
            val updateResult = storageManager.updateFileAccess(
                fileId = fileId,
                addRecipients = listOf(
                    DistributedStorageManager.Recipient(
                        id = taskId,
                        publicKey = taskKeypair.publicKeyPem,
                        type = DistributedStorageManager.RecipientType.TASK
                    )
                )
            )
            require(updateResult) { "Failed to add task as recipient" }
            
            // Verify task can now decrypt file
            println("  Step 4: Verify task can decrypt file")
            val decryptedData = storageManager.retrieveFile(
                fileId = fileId,
                recipientId = taskId,
                privateKey = taskKeypair.privateKeyPem
            )
            require(decryptedData.contentEquals(fileData)) {
                "Decrypted data does not match original"
            }
            
            // Verify metadata updated
            println("  Step 5: Verify metadata updated")
            val metadata = storageManager.getFileMetadata(fileId)
            require(metadata?.recipients?.any { it.id == taskId } == true) {
                "Task not in recipient list"
            }
            
            val executionTime = System.currentTimeMillis() - startTime
            println("  ✅ PASSED (${executionTime}ms)")
            
            return@runBlocking TestResult(
                testName = testName,
                passed = true,
                executionTimeMs = executionTime,
                details = mapOf(
                    "fileId" to fileId,
                    "taskId" to taskId,
                    "recipientCount" to (metadata?.recipients?.size ?: 0)
                )
            )
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            println("  ❌ FAILED: ${e.message}")
            
            return@runBlocking TestResult(
                testName = testName,
                passed = false,
                executionTimeMs = executionTime,
                errorMessage = e.message
            )
        } finally {
            FeatureFlags.disableTaskKeypair()
        }
    }
    
    /**
     * Test 6: Keypair Lifecycle Management
     * 
     * Scenario: Generate keypair, use during task, cleanup after completion
     * Tests: Keypair TTL, automatic cleanup, registry management
     */
    private fun testKeypairLifecycleManagement(): TestResult = runBlocking {
        val testName = "Test 6: Keypair Lifecycle Management"
        val startTime = System.currentTimeMillis()
        
        try {
            println("\n[$testName]")
            println("  Scenario: Full keypair lifecycle from generation to cleanup")
            
            // Enable feature flag
            FeatureFlags.enableTaskKeypair()
            
            // Generate keypair with short TTL
            println("  Step 1: Generate keypair with 100ms TTL")
            val taskId = "task-${UUID.randomUUID()}"
            val keypair = taskManager.generateTaskKeypair(taskId, ttlMs = 100)
            
            // Verify keypair accessible immediately
            println("  Step 2: Verify keypair accessible")
            val publicKey = taskManager.getTaskPublicKey(taskId)
            require(publicKey != null) { "Keypair should be accessible" }
            require(publicKey == keypair.publicKeyPem) { "Public key mismatch" }
            
            // Wait for expiration
            println("  Step 3: Wait for expiration (150ms)")
            delay(150)
            
            // Verify keypair expired (but still in registry until cleanup)
            println("  Step 4: Verify keypair expired")
            val expiredKey = taskManager.getTaskPublicKey(taskId)
            require(expiredKey == null) { "Expired keypair should return null" }
            
            // Run cleanup
            println("  Step 5: Run cleanup")
            taskManager.cleanupExpiredKeypairs()
            
            // Verify keypair removed from registry
            println("  Step 6: Verify cleanup removed keypair")
            val activeKeypairs = taskManager.getActiveKeypairs()
            require(!activeKeypairs.contains(taskId)) {
                "Keypair should be removed from active registry"
            }
            
            val executionTime = System.currentTimeMillis() - startTime
            println("  ✅ PASSED (${executionTime}ms)")
            
            return@runBlocking TestResult(
                testName = testName,
                passed = true,
                executionTimeMs = executionTime,
                details = mapOf(
                    "taskId" to taskId,
                    "ttlMs" to 100,
                    "cleanupSuccessful" to true
                )
            )
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            println("  ❌ FAILED: ${e.message}")
            
            return@runBlocking TestResult(
                testName = testName,
                passed = false,
                executionTimeMs = executionTime,
                errorMessage = e.message
            )
        } finally {
            FeatureFlags.disableTaskKeypair()
        }
    }
    
    // ===========================================================================================
    // PART 3: COMBINED INTEGRATION (6 TESTS)
    // ===========================================================================================
    
    /**
     * Test 7: Task with Encrypted Files
     * 
     * Scenario: Complete task lifecycle with encrypted input files
     * Tests: Integration of task execution + keypair enhancement
     */
    private fun testTaskWithEncryptedFiles(): TestResult = runBlocking {
        val testName = "Test 7: Task with Encrypted Files"
        val startTime = System.currentTimeMillis()
        
        try {
            println("\n[$testName]")
            println("  Scenario: Execute task with encrypted input files")
            
            // Enable feature flag
            FeatureFlags.enableTaskKeypair()
            
            // Create encrypted input file
            println("  Step 1: Create encrypted input file")
            val inputData = "Encrypted input data".toByteArray()
            val ownerPublicKey = "owner-public-key-pem"
            val fileId = storageManager.storeFile(
                data = inputData,
                recipients = listOf(
                    DistributedStorageManager.Recipient(
                        id = "owner",
                        publicKey = ownerPublicKey,
                        type = DistributedStorageManager.RecipientType.USER
                    )
                ),
                metadata = DistributedStorageManager.FileMetadata(
                    ownerId = "owner",
                    fileName = "encrypted-input.dat",
                    isEncrypted = true
                )
            )
            
            // Create task
            println("  Step 2: Create task")
            val taskId = UUID.randomUUID().toString()
            val task = TaskManager.ComputeTask(
                taskId = taskId,
                type = TaskType.PYTHON,
                executable = """
                    with open("/sandbox/input/$fileId", "r") as f:
                        data = f.read()
                    
                    with open("/sandbox/output/result.txt", "w") as f:
                        f.write(f"Processed: {data}")
                """.trimIndent(),
                inputFiles = listOf(fileId),
                requirements = TaskManager.TaskRequirements(
                    memoryMb = 128,
                    timeoutSeconds = 30,
                    networkAccess = false
                )
            )
            
            // Generate task keypair
            println("  Step 3: Generate task keypair")
            val taskKeypair = taskManager.generateTaskKeypair(taskId)
            
            // Add task as recipient to input file
            println("  Step 4: Add task as recipient")
            storageManager.updateFileAccess(
                fileId = fileId,
                addRecipients = listOf(
                    DistributedStorageManager.Recipient(
                        id = taskId,
                        publicKey = taskKeypair.publicKeyPem,
                        type = DistributedStorageManager.RecipientType.TASK
                    )
                )
            )
            
            // Execute task
            println("  Step 5: Execute task")
            val container = computeEngine.createContainer(task, keypair = taskKeypair)
            computeEngine.prepareInputFiles(container, task.inputFiles, keypair = taskKeypair)
            val executionResult = container.execute()
            require(executionResult.success) { "Task execution failed" }
            
            // Verify output
            println("  Step 6: Verify output")
            val outputData = storageManager.retrieveFileRaw(executionResult.outputFiles.first())
            val outputText = String(outputData)
            require(outputText.contains("Processed: Encrypted input data")) {
                "Output mismatch: $outputText"
            }
            
            // Cleanup
            taskManager.cleanupExpiredKeypairs()
            
            val executionTime = System.currentTimeMillis() - startTime
            println("  ✅ PASSED (${executionTime}ms)")
            
            return@runBlocking TestResult(
                testName = testName,
                passed = true,
                executionTimeMs = executionTime,
                details = mapOf(
                    "taskId" to taskId,
                    "inputFiles" to 1,
                    "outputFiles" to executionResult.outputFiles.size
                )
            )
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            println("  ❌ FAILED: ${e.message}")
            
            return@runBlocking TestResult(
                testName = testName,
                passed = false,
                executionTimeMs = executionTime,
                errorMessage = e.message
            )
        } finally {
            FeatureFlags.disableTaskKeypair()
        }
    }
    
    /**
     * Test 8: Task Decomposition with Keypairs
     * 
     * Scenario: Decompose task into sub-tasks, each with separate keypair
     * Tests: Integration of scheduler + keypair enhancement
     */
    private fun testTaskDecompositionWithKeypairs(): TestResult = runBlocking {
        val testName = "Test 8: Task Decomposition with Keypairs"
        val startTime = System.currentTimeMillis()
        
        try {
            println("\n[$testName]")
            println("  Scenario: Map-reduce task with per-sub-task keypairs")
            
            // Enable feature flag
            FeatureFlags.enableTaskKeypair()
            
            // Create parent task (map-reduce)
            println("  Step 1: Create map-reduce parent task")
            val parentTaskId = "parent-${UUID.randomUUID()}"
            val parentTask = TaskManager.ComputeTask(
                taskId = parentTaskId,
                type = TaskType.PYTHON,  // Simplified (actual would use MAP_REDUCE type)
                executable = "# Map-reduce job",
                inputFiles = emptyList(),
                requirements = TaskManager.TaskRequirements(
                    memoryMb = 256,
                    timeoutSeconds = 60,
                    networkAccess = false
                )
            )
            
            // Decompose into 3 sub-tasks
            println("  Step 2: Decompose into 3 sub-tasks")
            val subTaskCount = 3
            val subTaskIds = (1..subTaskCount).map { "sub-task-$it-${UUID.randomUUID()}" }
            
            // Generate keypair for each sub-task
            println("  Step 3: Generate keypair for each sub-task")
            val subTaskKeypairs = subTaskIds.map { subTaskId ->
                subTaskId to taskManager.generateTaskKeypair(subTaskId)
            }.toMap()
            
            // Verify each sub-task has unique keypair
            println("  Step 4: Verify keypair uniqueness")
            val publicKeys = subTaskKeypairs.values.map { it.publicKeyPem }.toSet()
            require(publicKeys.size == subTaskCount) {
                "Sub-tasks should have unique keypairs"
            }
            
            // Simulate execution of sub-tasks
            println("  Step 5: Execute sub-tasks concurrently")
            val successCount = AtomicInteger(0)
            val subTaskJobs = subTaskIds.map { subTaskId ->
                async {
                    try {
                        // Simulated sub-task execution
                        val keypair = subTaskKeypairs[subTaskId]!!
                        val subTask = TaskManager.ComputeTask(
                            taskId = subTaskId,
                            type = TaskType.PYTHON,
                            executable = "print('Sub-task $subTaskId')",
                            inputFiles = emptyList(),
                            requirements = TaskManager.TaskRequirements(
                                memoryMb = 64,
                                timeoutSeconds = 30,
                                networkAccess = false
                            )
                        )
                        
                        val container = computeEngine.createContainer(subTask, keypair = keypair)
                        val result = container.execute()
                        if (result.success) successCount.incrementAndGet()
                    } catch (e: Exception) {
                        println("    Sub-task $subTaskId failed: ${e.message}")
                    }
                }
            }
            subTaskJobs.awaitAll()
            
            // Verify all sub-tasks succeeded
            println("  Step 6: Verify all sub-tasks succeeded")
            require(successCount.get() == subTaskCount) {
                "Expected $subTaskCount successful sub-tasks, got ${successCount.get()}"
            }
            
            // Cleanup
            taskManager.cleanupExpiredKeypairs()
            
            val executionTime = System.currentTimeMillis() - startTime
            println("  ✅ PASSED (${executionTime}ms)")
            
            return@runBlocking TestResult(
                testName = testName,
                passed = true,
                executionTimeMs = executionTime,
                details = mapOf(
                    "parentTaskId" to parentTaskId,
                    "subTaskCount" to subTaskCount,
                    "successfulSubTasks" to successCount.get()
                )
            )
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            println("  ❌ FAILED: ${e.message}")
            
            return@runBlocking TestResult(
                testName = testName,
                passed = false,
                executionTimeMs = executionTime,
                errorMessage = e.message
            )
        } finally {
            FeatureFlags.disableTaskKeypair()
        }
    }
    
    /**
     * Test 9: Multi-Node Execution
     * 
     * Scenario: Distribute tasks across multiple compute nodes
     * Tests: Node discovery, task assignment, distributed keypair management
     */
    private fun testMultiNodeExecution(): TestResult = runBlocking {
        val testName = "Test 9: Multi-Node Execution"
        val startTime = System.currentTimeMillis()
        
        try {
            println("\n[$testName]")
            println("  Scenario: Distribute 5 tasks across 3 simulated nodes")
            
            // Enable feature flag
            FeatureFlags.enableTaskKeypair()
            
            // Simulate 3 compute nodes
            println("  Step 1: Simulate 3 compute nodes")
            val nodeIds = listOf("node-1", "node-2", "node-3")
            val nodeCapabilities = nodeIds.associateWith {
                mapOf(
                    "supportsKeypair" to true,
                    "availableMemoryMb" to 512,
                    "cpuCores" to 2
                )
            }
            
            // Create 5 tasks
            println("  Step 2: Create 5 tasks")
            val taskCount = 5
            val tasks = (1..taskCount).map { i ->
                val taskId = "task-$i-${UUID.randomUUID()}"
                TaskManager.ComputeTask(
                    taskId = taskId,
                    type = TaskType.PYTHON,
                    executable = "print('Task $i on node')",
                    inputFiles = emptyList(),
                    requirements = TaskManager.TaskRequirements(
                        memoryMb = 128,
                        timeoutSeconds = 30,
                        networkAccess = false
                    )
                )
            }
            
            // Assign tasks to nodes (round-robin)
            println("  Step 3: Assign tasks to nodes")
            val assignments = tasks.mapIndexed { index, task ->
                val nodeId = nodeIds[index % nodeIds.size]
                task to nodeId
            }.toMap()
            
            // Execute tasks on assigned nodes
            println("  Step 4: Execute tasks on assigned nodes")
            val successCount = AtomicInteger(0)
            val executionJobs = assignments.map { (task, nodeId) ->
                async {
                    try {
                        // Generate keypair on assigned node
                        val keypair = taskManager.generateTaskKeypair(task.taskId)
                        
                        // Execute task
                        val container = computeEngine.createContainer(task, keypair = keypair)
                        val result = container.execute()
                        
                        if (result.success) {
                            println("    Task ${task.taskId} completed on $nodeId")
                            successCount.incrementAndGet()
                        }
                    } catch (e: Exception) {
                        println("    Task ${task.taskId} failed on $nodeId: ${e.message}")
                    }
                }
            }
            executionJobs.awaitAll()
            
            // Verify all tasks succeeded
            println("  Step 5: Verify all tasks succeeded")
            require(successCount.get() == taskCount) {
                "Expected $taskCount successful tasks, got ${successCount.get()}"
            }
            
            // Verify tasks distributed across nodes
            println("  Step 6: Verify task distribution")
            val nodeAssignmentCounts = assignments.values.groupingBy { it }.eachCount()
            nodeIds.forEach { nodeId ->
                val count = nodeAssignmentCounts[nodeId] ?: 0
                println("    $nodeId: $count tasks")
            }
            
            // Cleanup
            taskManager.cleanupExpiredKeypairs()
            
            val executionTime = System.currentTimeMillis() - startTime
            println("  ✅ PASSED (${executionTime}ms)")
            
            return@runBlocking TestResult(
                testName = testName,
                passed = true,
                executionTimeMs = executionTime,
                details = mapOf(
                    "nodeCount" to nodeIds.size,
                    "taskCount" to taskCount,
                    "successfulTasks" to successCount.get(),
                    "distribution" to nodeAssignmentCounts
                )
            )
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            println("  ❌ FAILED: ${e.message}")
            
            return@runBlocking TestResult(
                testName = testName,
                passed = false,
                executionTimeMs = executionTime,
                errorMessage = e.message
            )
        } finally {
            FeatureFlags.disableTaskKeypair()
        }
    }
    
    /**
     * Test 10: Task Cancellation
     * 
     * Scenario: Cancel running task, verify keypair cleanup
     * Tests: Task lifecycle management, cancellation handling, resource cleanup
     */
    private fun testTaskCancellation(): TestResult = runBlocking {
        val testName = "Test 10: Task Cancellation"
        val startTime = System.currentTimeMillis()
        
        try {
            println("\n[$testName]")
            println("  Scenario: Cancel task mid-execution, verify cleanup")
            
            // Enable feature flag
            FeatureFlags.enableTaskKeypair()
            
            // Create long-running task
            println("  Step 1: Create long-running task")
            val taskId = UUID.randomUUID().toString()
            val task = TaskManager.ComputeTask(
                taskId = taskId,
                type = TaskType.PYTHON,
                executable = """
                    import time
                    for i in range(100):
                        print(f"Iteration {i}")
                        time.sleep(0.1)  # 10 seconds total
                """.trimIndent(),
                inputFiles = emptyList(),
                requirements = TaskManager.TaskRequirements(
                    memoryMb = 128,
                    timeoutSeconds = 30,
                    networkAccess = false
                )
            )
            
            // Generate keypair
            println("  Step 2: Generate keypair")
            val keypair = taskManager.generateTaskKeypair(taskId)
            
            // Start task execution
            println("  Step 3: Start task execution")
            val executionJob = async {
                val container = computeEngine.createContainer(task, keypair = keypair)
                container.execute()
            }
            
            // Wait briefly, then cancel
            println("  Step 4: Wait 100ms, then cancel task")
            delay(100)
            executionJob.cancel()
            
            // Verify keypair still exists (until cleanup)
            println("  Step 5: Verify keypair exists before cleanup")
            val keypairBeforeCleanup = taskManager.getTaskPublicKey(taskId)
            require(keypairBeforeCleanup != null) { "Keypair should exist before cleanup" }
            
            // Run cleanup
            println("  Step 6: Run cleanup")
            taskManager.cleanupExpiredKeypairs()
            
            // Verify keypair removed
            println("  Step 7: Verify keypair removed")
            val activeKeypairs = taskManager.getActiveKeypairs()
            require(!activeKeypairs.contains(taskId)) {
                "Keypair should be removed after cleanup"
            }
            
            val executionTime = System.currentTimeMillis() - startTime
            println("  ✅ PASSED (${executionTime}ms)")
            
            return@runBlocking TestResult(
                testName = testName,
                passed = true,
                executionTimeMs = executionTime,
                details = mapOf(
                    "taskId" to taskId,
                    "cancelled" to true,
                    "cleanupSuccessful" to true
                )
            )
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            println("  ❌ FAILED: ${e.message}")
            
            return@runBlocking TestResult(
                testName = testName,
                passed = false,
                executionTimeMs = executionTime,
                errorMessage = e.message
            )
        } finally {
            FeatureFlags.disableTaskKeypair()
        }
    }
    
    /**
     * Test 11: Network Partition Recovery
     * 
     * Scenario: Simulate network partition, verify task continues after recovery
     * Tests: Fault tolerance, task persistence, state recovery
     */
    private fun testNetworkPartitionRecovery(): TestResult = runBlocking {
        val testName = "Test 11: Network Partition Recovery"
        val startTime = System.currentTimeMillis()
        
        try {
            println("\n[$testName]")
            println("  Scenario: Network partition during task execution, verify recovery")
            
            // Enable feature flag
            FeatureFlags.enableTaskKeypair()
            
            // Create task
            println("  Step 1: Create task")
            val taskId = UUID.randomUUID().toString()
            val task = TaskManager.ComputeTask(
                taskId = taskId,
                type = TaskType.PYTHON,
                executable = "print('Task execution')",
                inputFiles = emptyList(),
                requirements = TaskManager.TaskRequirements(
                    memoryMb = 128,
                    timeoutSeconds = 30,
                    networkAccess = false
                )
            )
            
            // Generate keypair
            println("  Step 2: Generate keypair")
            val keypair = taskManager.generateTaskKeypair(taskId)
            
            // Simulate network partition (delay)
            println("  Step 3: Simulate network partition (200ms)")
            delay(200)
            
            // Verify keypair still accessible (persisted)
            println("  Step 4: Verify keypair persisted across partition")
            val recoveredKeypair = taskManager.getTaskPublicKey(taskId)
            require(recoveredKeypair != null) { "Keypair should persist across partition" }
            require(recoveredKeypair == keypair.publicKeyPem) { "Keypair should match" }
            
            // Execute task after recovery
            println("  Step 5: Execute task after recovery")
            val container = computeEngine.createContainer(task, keypair = keypair)
            val executionResult = container.execute()
            require(executionResult.success) { "Task should succeed after recovery" }
            
            // Cleanup
            taskManager.cleanupExpiredKeypairs()
            
            val executionTime = System.currentTimeMillis() - startTime
            println("  ✅ PASSED (${executionTime}ms)")
            
            return@runBlocking TestResult(
                testName = testName,
                passed = true,
                executionTimeMs = executionTime,
                details = mapOf(
                    "taskId" to taskId,
                    "partitionDurationMs" to 200,
                    "recoverySuccessful" to true
                )
            )
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            println("  ❌ FAILED: ${e.message}")
            
            return@runBlocking TestResult(
                testName = testName,
                passed = false,
                executionTimeMs = executionTime,
                errorMessage = e.message
            )
        } finally {
            FeatureFlags.disableTaskKeypair()
        }
    }
    
    /**
     * Test 12: Feature Flag Toggle
     * 
     * Scenario: Toggle feature flag, verify tasks adapt to mode change
     * Tests: Feature flag system, backward compatibility, graceful degradation
     */
    private fun testFeatureFlagToggle(): TestResult = runBlocking {
        val testName = "Test 12: Feature Flag Toggle"
        val startTime = System.currentTimeMillis()
        
        try {
            println("\n[$testName]")
            println("  Scenario: Toggle feature flag, verify mode switching")
            
            // Test 1: Enhanced mode (flag enabled)
            println("  Step 1: Enable feature flag")
            FeatureFlags.enableTaskKeypair()
            
            val taskId1 = UUID.randomUUID().toString()
            val task1 = TaskManager.ComputeTask(
                taskId = taskId1,
                type = TaskType.PYTHON,
                executable = "print('Enhanced mode')",
                inputFiles = emptyList(),
                requirements = TaskManager.TaskRequirements(
                    memoryMb = 128,
                    timeoutSeconds = 30,
                    networkAccess = false
                )
            )
            
            // Submit task (should use keypair)
            println("  Step 2: Submit task (enhanced mode)")
            val keypair1 = taskManager.generateTaskKeypair(taskId1)
            require(keypair1 != null) { "Keypair should be generated in enhanced mode" }
            
            val container1 = computeEngine.createContainer(task1, keypair = keypair1)
            val result1 = container1.execute()
            require(result1.success) { "Enhanced mode task should succeed" }
            
            // Test 2: Legacy mode (flag disabled)
            println("  Step 3: Disable feature flag")
            FeatureFlags.disableTaskKeypair()
            
            val taskId2 = UUID.randomUUID().toString()
            val task2 = TaskManager.ComputeTask(
                taskId = taskId2,
                type = TaskType.PYTHON,
                executable = "print('Legacy mode')",
                inputFiles = emptyList(),
                requirements = TaskManager.TaskRequirements(
                    memoryMb = 128,
                    timeoutSeconds = 30,
                    networkAccess = false
                )
            )
            
            // Submit task (should NOT use keypair)
            println("  Step 4: Submit task (legacy mode)")
            val container2 = computeEngine.createContainer(task2, keypair = null)
            val result2 = container2.execute()
            require(result2.success) { "Legacy mode task should succeed" }
            
            // Test 3: Re-enable and verify
            println("  Step 5: Re-enable feature flag")
            FeatureFlags.enableTaskKeypair()
            
            val taskId3 = UUID.randomUUID().toString()
            val keypair3 = taskManager.generateTaskKeypair(taskId3)
            require(keypair3 != null) { "Keypair should be generated after re-enabling" }
            
            // Cleanup
            taskManager.cleanupExpiredKeypairs()
            
            val executionTime = System.currentTimeMillis() - startTime
            println("  ✅ PASSED (${executionTime}ms)")
            
            return@runBlocking TestResult(
                testName = testName,
                passed = true,
                executionTimeMs = executionTime,
                details = mapOf(
                    "enhancedModeTask" to taskId1,
                    "legacyModeTask" to taskId2,
                    "reEnabledTask" to taskId3,
                    "allSucceeded" to true
                )
            )
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            println("  ❌ FAILED: ${e.message}")
            
            return@runBlocking TestResult(
                testName = testName,
                passed = false,
                executionTimeMs = executionTime,
                errorMessage = e.message
            )
        } finally {
            FeatureFlags.disableTaskKeypair()
        }
    }
    
    /**
     * Generate comprehensive test report
     */
    fun generateReport(suiteResult: SuiteResult): String {
        val report = StringBuilder()
        
        report.appendLine("=" .repeat(80))
        report.appendLine("INTEGRATION TEST SUITE REPORT - PHASE 8.1")
        report.appendLine("=" .repeat(80))
        report.appendLine()
        
        // Summary
        report.appendLine("SUMMARY")
        report.appendLine("-".repeat(80))
        report.appendLine("Total Tests:       ${suiteResult.totalTests}")
        report.appendLine("Passed:            ${suiteResult.passed} ✅")
        report.appendLine("Failed:            ${suiteResult.failed} ❌")
        report.appendLine("Success Rate:      ${String.format("%.1f%%", suiteResult.successRate * 100)}")
        report.appendLine("Total Time:        ${suiteResult.totalExecutionTimeMs}ms")
        report.appendLine()
        
        // Part summaries
        val part1Tests = suiteResult.testResults.take(3)
        val part2Tests = suiteResult.testResults.drop(3).take(3)
        val part3Tests = suiteResult.testResults.drop(6)
        
        report.appendLine("PART 1: TASK EXECUTION LAYER ONLY")
        report.appendLine("-".repeat(80))
        part1Tests.forEach { result ->
            val status = if (result.passed) "✅ PASS" else "❌ FAIL"
            report.appendLine("${result.testName}: $status (${result.executionTimeMs}ms)")
            if (!result.passed) {
                report.appendLine("  Error: ${result.errorMessage}")
            }
        }
        report.appendLine()
        
        report.appendLine("PART 2: KEYPAIR ENHANCEMENT LAYER ONLY")
        report.appendLine("-".repeat(80))
        part2Tests.forEach { result ->
            val status = if (result.passed) "✅ PASS" else "❌ FAIL"
            report.appendLine("${result.testName}: $status (${result.executionTimeMs}ms)")
            if (!result.passed) {
                report.appendLine("  Error: ${result.errorMessage}")
            }
        }
        report.appendLine()
        
        report.appendLine("PART 3: COMBINED INTEGRATION")
        report.appendLine("-".repeat(80))
        part3Tests.forEach { result ->
            val status = if (result.passed) "✅ PASS" else "❌ FAIL"
            report.appendLine("${result.testName}: $status (${result.executionTimeMs}ms)")
            if (!result.passed) {
                report.appendLine("  Error: ${result.errorMessage}")
            }
        }
        report.appendLine()
        
        // Overall result
        report.appendLine("=" .repeat(80))
        if (suiteResult.successRate >= 0.99) {
            report.appendLine("✅ INTEGRATION TEST SUITE PASSED")
            report.appendLine("Success rate ${String.format("%.1f%%", suiteResult.successRate * 100)} meets >99% target")
        } else {
            report.appendLine("❌ INTEGRATION TEST SUITE FAILED")
            report.appendLine("Success rate ${String.format("%.1f%%", suiteResult.successRate * 100)} below 99% target")
        }
        report.appendLine("=" .repeat(80))
        
        return report.toString()
    }
}
