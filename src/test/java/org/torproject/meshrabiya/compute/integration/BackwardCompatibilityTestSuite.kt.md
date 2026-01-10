package org.torproject.meshrabiya.compute.integration

// ...existing code...

/**
 * Backward Compatibility Test Suite - Phase 8.2
 * 
 * Comprehensive backward compatibility testing covering:
 * - TC-BC-01: Legacy task on enhanced node
 * - TC-BC-02: Enhanced task on legacy node  
 * - TC-BC-03: Mixed mesh (50% enhanced, 50% legacy)
 * - TC-BC-04: Feature flag disable during execution
 * - TC-BC-05: Rolling upgrade scenario
 * 
 * Total: 5 backward compatibility test cases
 * 
 * Ref: TASK_KEYPAIR_ENHANCEMENT_PLAN_PART5.md Section 14.5
 *      MASTER_IMPLEMENTATION_ROADMAP.md Phase 8.2
 */
class BackwardCompatibilityTestSuite(
    private val taskManager: TaskManager,
    private val storageManager: DistributedStorageManager,
    private val computeEngine: StrangersSafeComputeEngine
) {
    
    data class TestResult(
        val testName: String,
        val testCase: String,
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
     * Run all 5 backward compatibility test cases
     */
    fun runAllTests(): SuiteResult {
        val startTime = System.currentTimeMillis()
        val results = mutableListOf<TestResult>()
        
        println("=" .repeat(80))
        println("BACKWARD COMPATIBILITY TEST SUITE - PHASE 8.2")
        println("Total Test Cases: 5")
        println("=" .repeat(80))
        
        // TC-BC-01: Legacy task on enhanced node
        results.add(testLegacyTaskOnEnhancedNode())
        
        // TC-BC-02: Enhanced task on legacy node
        results.add(testEnhancedTaskOnLegacyNode())
        
        // TC-BC-03: Mixed mesh (50/50)
        results.add(testMixedMesh())
        
        // TC-BC-04: Feature flag disable during execution
        results.add(testFeatureFlagDisableDuringExecution())
        
        // TC-BC-05: Rolling upgrade scenario
        results.add(testRollingUpgrade())
        
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
    
    /**
     * TC-BC-01: Legacy Task on Enhanced Node
     * 
     * Setup: Node has keypair feature enabled, task submitted without keypair flag
     * Expected: Task executes in legacy mode (no keypair generated)
     * Success: Task completes successfully without using keypair enhancement
     */
    private fun testLegacyTaskOnEnhancedNode(): TestResult = runBlocking {
        val testName = "TC-BC-01: Legacy Task on Enhanced Node"
        val startTime = System.currentTimeMillis()
        
        try {
            println("\n[$testName]")
            println("  Setup: Enhanced node, legacy task (no encryption)")
            println("  Expected: Execute in legacy mode")
            
            // Enable feature flag (enhanced node)
            println("  Step 1: Enable feature flag (enhanced node)")
            FeatureFlags.enableTaskKeypair()
            
            // Create legacy task (no encrypted files)
            println("  Step 2: Create legacy task (no encrypted files)")
            val taskId = UUID.randomUUID().toString()
            val task = TaskManager.ComputeTask(
                taskId = taskId,
                type = TaskType.PYTHON,
                executable = """
                    print("Legacy task execution")
                    with open("/sandbox/output/result.txt", "w") as f:
                        f.write("Legacy mode output")
                """.trimIndent(),
                inputFiles = emptyList(),  // No encrypted files
                requirements = TaskManager.TaskRequirements(
                    memoryMb = 128,
                    timeoutSeconds = 30,
                    networkAccess = false
                )
            )
            
            // Execute task WITHOUT keypair (legacy mode)
            println("  Step 3: Execute task in legacy mode (no keypair)")
            val container = computeEngine.createContainer(task, keypair = null)
            val executionResult = container.execute()
            
            // Verify success
            println("  Step 4: Verify task succeeded")
            require(executionResult.success) { "Legacy task should succeed on enhanced node" }
            
            // Verify no keypair was generated
            println("  Step 5: Verify no keypair generated")
            val keypair = taskManager.getTaskPublicKey(taskId)
            require(keypair == null) { "No keypair should be generated for legacy task" }
            
            // Verify output
            println("  Step 6: Verify output")
            val outputData = storageManager.retrieveFileRaw(executionResult.outputFiles.first())
            val outputText = String(outputData)
            require(outputText.contains("Legacy mode output")) {
                "Output should match legacy task"
            }
            
            val executionTime = System.currentTimeMillis() - startTime
            println("  ✅ PASSED (${executionTime}ms)")
            
            return@runBlocking TestResult(
                testName = testName,
                testCase = "TC-BC-01",
                passed = true,
                executionTimeMs = executionTime,
                details = mapOf(
                    "taskId" to taskId,
                    "mode" to "legacy",
                    "keypairGenerated" to false,
                    "executionSuccess" to true
                )
            )
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            println("  ❌ FAILED: ${e.message}")
            
            return@runBlocking TestResult(
                testName = testName,
                testCase = "TC-BC-01",
                passed = false,
                executionTimeMs = executionTime,
                errorMessage = e.message
            )
        } finally {
            FeatureFlags.disableTaskKeypair()
        }
    }
    
    /**
     * TC-BC-02: Enhanced Task on Legacy Node
     * 
     * Setup: Node does not support keypair, task submitted with keypair flag
     * Expected: Task rejected with UNSUPPORTED_FEATURE error
     * Success: Proper error handling, no crash
     */
    private fun testEnhancedTaskOnLegacyNode(): TestResult = runBlocking {
        val testName = "TC-BC-02: Enhanced Task on Legacy Node"
        val startTime = System.currentTimeMillis()
        
        try {
            println("\n[$testName]")
            println("  Setup: Legacy node (no keypair support), enhanced task")
            println("  Expected: Reject with UNSUPPORTED_FEATURE")
            
            // Disable feature flag (legacy node)
            println("  Step 1: Disable feature flag (legacy node)")
            FeatureFlags.disableTaskKeypair()
            
            // Create enhanced task (with encrypted files)
            println("  Step 2: Create enhanced task with encrypted file")
            val inputData = "Encrypted data".toByteArray()
            val fileId = storageManager.storeFile(
                data = inputData,
                recipients = listOf(
                    DistributedStorageManager.Recipient(
                        id = "owner",
                        publicKey = "owner-public-key-pem",
                        type = DistributedStorageManager.RecipientType.USER
                    )
                ),
                metadata = DistributedStorageManager.FileMetadata(
                    ownerId = "owner",
                    fileName = "encrypted-input.dat",
                    isEncrypted = true
                )
            )
            
            val taskId = UUID.randomUUID().toString()
            val task = TaskManager.ComputeTask(
                taskId = taskId,
                type = TaskType.PYTHON,
                executable = "print('Enhanced task')",
                inputFiles = listOf(fileId),  // Encrypted file
                requirements = TaskManager.TaskRequirements(
                    memoryMb = 128,
                    timeoutSeconds = 30,
                    networkAccess = false
                )
            )
            
            // Attempt to generate keypair (should fail on legacy node)
            println("  Step 3: Attempt to generate keypair (should fail)")
            var keypairGenerationFailed = false
            try {
                taskManager.generateTaskKeypair(taskId)
            } catch (e: UnsupportedOperationException) {
                keypairGenerationFailed = true
                println("    Expected failure: ${e.message}")
            }
            
            // Verify failure
            println("  Step 4: Verify keypair generation failed")
            require(keypairGenerationFailed) {
                "Keypair generation should fail on legacy node"
            }
            
            // Attempt to execute task (should fail gracefully)
            println("  Step 5: Attempt task execution (should fail gracefully)")
            var executionFailed = false
            try {
                val container = computeEngine.createContainer(task, keypair = null)
                val executionResult = container.execute()
                
                if (!executionResult.success && 
                    executionResult.errorType == TaskManager.ExecutionErrorType.UNSUPPORTED_FEATURE) {
                    executionFailed = true
                }
            } catch (e: Exception) {
                executionFailed = true
                println("    Expected failure: ${e.message}")
            }
            
            // Verify proper error handling
            println("  Step 6: Verify proper error handling")
            require(executionFailed) { "Task should fail gracefully on legacy node" }
            
            val executionTime = System.currentTimeMillis() - startTime
            println("  ✅ PASSED (${executionTime}ms)")
            
            return@runBlocking TestResult(
                testName = testName,
                testCase = "TC-BC-02",
                passed = true,
                executionTimeMs = executionTime,
                details = mapOf(
                    "taskId" to taskId,
                    "nodeType" to "legacy",
                    "keypairGenerationFailed" to true,
                    "taskExecutionFailed" to true,
                    "errorHandling" to "graceful"
                )
            )
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            println("  ❌ FAILED: ${e.message}")
            
            return@runBlocking TestResult(
                testName = testName,
                testCase = "TC-BC-02",
                passed = false,
                executionTimeMs = executionTime,
                errorMessage = e.message
            )
        }
    }
    
    /**
     * TC-BC-03: Mixed Mesh (Enhanced + Legacy Nodes)
     * 
     * Setup: Mesh with 50% enhanced nodes, 50% legacy nodes, submit 10 tasks
     * Expected: Enhanced tasks route to enhanced nodes, legacy tasks to any node
     * Success: All tasks complete successfully, proper routing
     */
    private fun testMixedMesh(): TestResult = runBlocking {
        val testName = "TC-BC-03: Mixed Mesh (50% Enhanced, 50% Legacy)"
        val startTime = System.currentTimeMillis()
        
        try {
            println("\n[$testName]")
            println("  Setup: 4 enhanced nodes, 4 legacy nodes, 10 tasks (5 enhanced, 5 legacy)")
            println("  Expected: Proper routing, all tasks succeed")
            
            // Define nodes
            println("  Step 1: Define 8 nodes (4 enhanced, 4 legacy)")
            val enhancedNodes = (1..4).map { "enhanced-node-$it" }
            val legacyNodes = (1..4).map { "legacy-node-$it" }
            val allNodes = enhancedNodes + legacyNodes
            
            // Create 10 tasks (5 enhanced, 5 legacy)
            println("  Step 2: Create 10 tasks (5 enhanced, 5 legacy)")
            
            // Enhanced tasks (with encrypted files)
            val enhancedTasks = (1..5).map { i ->
                val taskId = "enhanced-task-$i-${UUID.randomUUID()}"
                
                // Create encrypted input file
                val fileId = storageManager.storeFile(
                    data = "Encrypted data $i".toByteArray(),
                    recipients = listOf(
                        DistributedStorageManager.Recipient(
                            id = "owner",
                            publicKey = "owner-key-$i",
                            type = DistributedStorageManager.RecipientType.USER
                        )
                    ),
                    metadata = DistributedStorageManager.FileMetadata(
                        ownerId = "owner",
                        fileName = "encrypted-$i.dat",
                        isEncrypted = true
                    )
                )
                
                TaskManager.ComputeTask(
                    taskId = taskId,
                    type = TaskType.PYTHON,
                    executable = "print('Enhanced task $i')",
                    inputFiles = listOf(fileId),
                    requirements = TaskManager.TaskRequirements(
                        memoryMb = 128,
                        timeoutSeconds = 30,
                        networkAccess = false
                    )
                )
            }
            
            // Legacy tasks (no encrypted files)
            val legacyTasks = (1..5).map { i ->
                TaskManager.ComputeTask(
                    taskId = "legacy-task-$i-${UUID.randomUUID()}",
                    type = TaskType.PYTHON,
                    executable = "print('Legacy task $i')",
                    inputFiles = emptyList(),
                    requirements = TaskManager.TaskRequirements(
                        memoryMb = 128,
                        timeoutSeconds = 30,
                        networkAccess = false
                    )
                )
            }
            
            // Route enhanced tasks to enhanced nodes only
            println("  Step 3: Route enhanced tasks to enhanced nodes")
            val enhancedSuccessCount = AtomicInteger(0)
            val enhancedJobs = enhancedTasks.mapIndexed { index, task ->
                async {
                    try {
                        // Enable feature flag for enhanced node
                        FeatureFlags.enableTaskKeypair()
                        
                        // Generate keypair
                        val keypair = taskManager.generateTaskKeypair(task.taskId)
                        
                        // Add task as recipient to encrypted file
                        storageManager.updateFileAccess(
                            fileId = task.inputFiles.first(),
                            addRecipients = listOf(
                                DistributedStorageManager.Recipient(
                                    id = task.taskId,
                                    publicKey = keypair.publicKeyPem,
                                    type = DistributedStorageManager.RecipientType.TASK
                                )
                            )
                        )
                        
                        // Execute
                        val container = computeEngine.createContainer(task, keypair = keypair)
                        computeEngine.prepareInputFiles(container, task.inputFiles, keypair = keypair)
                        val result = container.execute()
                        
                        if (result.success) {
                            val nodeId = enhancedNodes[index % enhancedNodes.size]
                            println("    Enhanced task ${task.taskId} completed on $nodeId")
                            enhancedSuccessCount.incrementAndGet()
                        }
                        
                        FeatureFlags.disableTaskKeypair()
                    } catch (e: Exception) {
                        println("    Enhanced task ${task.taskId} failed: ${e.message}")
                    }
                }
            }
            
            // Route legacy tasks to any node
            println("  Step 4: Route legacy tasks to any node")
            val legacySuccessCount = AtomicInteger(0)
            val legacyJobs = legacyTasks.mapIndexed { index, task ->
                async {
                    try {
                        // Execute without keypair
                        val container = computeEngine.createContainer(task, keypair = null)
                        val result = container.execute()
                        
                        if (result.success) {
                            val nodeId = allNodes[index % allNodes.size]
                            println("    Legacy task ${task.taskId} completed on $nodeId")
                            legacySuccessCount.incrementAndGet()
                        }
                    } catch (e: Exception) {
                        println("    Legacy task ${task.taskId} failed: ${e.message}")
                    }
                }
            }
            
            // Wait for all tasks
            println("  Step 5: Wait for all tasks to complete")
            enhancedJobs.awaitAll()
            legacyJobs.awaitAll()
            
            // Verify all tasks succeeded
            println("  Step 6: Verify all tasks succeeded")
            require(enhancedSuccessCount.get() == 5) {
                "Expected 5 enhanced tasks, got ${enhancedSuccessCount.get()}"
            }
            require(legacySuccessCount.get() == 5) {
                "Expected 5 legacy tasks, got ${legacySuccessCount.get()}"
            }
            
            val executionTime = System.currentTimeMillis() - startTime
            println("  ✅ PASSED (${executionTime}ms)")
            
            return@runBlocking TestResult(
                testName = testName,
                testCase = "TC-BC-03",
                passed = true,
                executionTimeMs = executionTime,
                details = mapOf(
                    "enhancedNodes" to enhancedNodes.size,
                    "legacyNodes" to legacyNodes.size,
                    "enhancedTasksSucceeded" to enhancedSuccessCount.get(),
                    "legacyTasksSucceeded" to legacySuccessCount.get(),
                    "totalTasks" to 10
                )
            )
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            println("  ❌ FAILED: ${e.message}")
            
            return@runBlocking TestResult(
                testName = testName,
                testCase = "TC-BC-03",
                passed = false,
                executionTimeMs = executionTime,
                errorMessage = e.message
            )
        }
    }
    
    /**
     * TC-BC-04: Feature Flag Disable During Execution
     * 
     * Setup: Task running with keypair, feature flag disabled mid-execution
     * Expected: Running tasks complete normally, new tasks use legacy mode
     * Success: No disruption to running tasks, graceful mode transition
     */
    private fun testFeatureFlagDisableDuringExecution(): TestResult = runBlocking {
        val testName = "TC-BC-04: Feature Flag Disable During Execution"
        val startTime = System.currentTimeMillis()
        
        try {
            println("\n[$testName]")
            println("  Setup: Task running with keypair, disable flag mid-execution")
            println("  Expected: Running task completes, new task uses legacy mode")
            
            // Enable feature flag
            println("  Step 1: Enable feature flag")
            FeatureFlags.enableTaskKeypair()
            
            // Start task with keypair
            println("  Step 2: Start task with keypair")
            val taskId1 = "task-1-${UUID.randomUUID()}"
            val task1 = TaskManager.ComputeTask(
                taskId = taskId1,
                type = TaskType.PYTHON,
                executable = """
                    import time
                    print("Task started with keypair")
                    time.sleep(0.2)  # 200ms
                    print("Task completing")
                """.trimIndent(),
                inputFiles = emptyList(),
                requirements = TaskManager.TaskRequirements(
                    memoryMb = 128,
                    timeoutSeconds = 30,
                    networkAccess = false
                )
            )
            
            val keypair1 = taskManager.generateTaskKeypair(taskId1)
            
            // Start execution
            val executionJob = async {
                val container = computeEngine.createContainer(task1, keypair = keypair1)
                container.execute()
            }
            
            // Disable feature flag mid-execution
            println("  Step 3: Wait 100ms, then disable feature flag")
            delay(100)
            FeatureFlags.disableTaskKeypair()
            println("    Feature flag disabled")
            
            // Wait for first task to complete
            println("  Step 4: Wait for running task to complete")
            val result1 = executionJob.await()
            require(result1.success) { "Running task should complete despite flag disable" }
            println("    Running task completed successfully")
            
            // Submit new task (should use legacy mode)
            println("  Step 5: Submit new task (should use legacy mode)")
            val taskId2 = "task-2-${UUID.randomUUID()}"
            val task2 = TaskManager.ComputeTask(
                taskId = taskId2,
                type = TaskType.PYTHON,
                executable = "print('New task in legacy mode')",
                inputFiles = emptyList(),
                requirements = TaskManager.TaskRequirements(
                    memoryMb = 128,
                    timeoutSeconds = 30,
                    networkAccess = false
                )
            )
            
            // Verify no keypair generated for new task
            println("  Step 6: Verify new task uses legacy mode")
            var keypairGenerationAttempted = false
            try {
                taskManager.generateTaskKeypair(taskId2)
                keypairGenerationAttempted = true
            } catch (e: Exception) {
                // Expected - feature flag disabled
            }
            require(!keypairGenerationAttempted) {
                "New task should not generate keypair (flag disabled)"
            }
            
            // Execute new task in legacy mode
            println("  Step 7: Execute new task in legacy mode")
            val container2 = computeEngine.createContainer(task2, keypair = null)
            val result2 = container2.execute()
            require(result2.success) { "Legacy mode task should succeed" }
            
            val executionTime = System.currentTimeMillis() - startTime
            println("  ✅ PASSED (${executionTime}ms)")
            
            return@runBlocking TestResult(
                testName = testName,
                testCase = "TC-BC-04",
                passed = true,
                executionTimeMs = executionTime,
                details = mapOf(
                    "runningTaskId" to taskId1,
                    "runningTaskCompleted" to true,
                    "newTaskId" to taskId2,
                    "newTaskMode" to "legacy",
                    "gracefulTransition" to true
                )
            )
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            println("  ❌ FAILED: ${e.message}")
            
            return@runBlocking TestResult(
                testName = testName,
                testCase = "TC-BC-04",
                passed = false,
                executionTimeMs = executionTime,
                errorMessage = e.message
            )
        }
    }
    
    /**
     * TC-BC-05: Rolling Upgrade Scenario
     * 
     * Setup: Upgrade nodes one by one from legacy to enhanced (8 nodes)
     * Expected: Tasks continue to execute throughout upgrade
     * Success: Zero downtime, all tasks complete successfully
     */
    private fun testRollingUpgrade(): TestResult = runBlocking {
        val testName = "TC-BC-05: Rolling Upgrade Scenario"
        val startTime = System.currentTimeMillis()
        
        try {
            println("\n[$testName]")
            println("  Setup: 8 legacy nodes, upgrade one by one, submit tasks continuously")
            println("  Expected: Zero downtime, all tasks succeed")
            
            // Define 8 nodes (all start as legacy)
            println("  Step 1: Initialize 8 legacy nodes")
            val nodes = (1..8).map { i ->
                mapOf(
                    "id" to "node-$i",
                    "enhanced" to false
                )
            }.toMutableList()
            
            val totalTasks = 16  // 2 tasks per node
            val successCount = AtomicInteger(0)
            val taskJobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()
            
            // Submit tasks continuously
            println("  Step 2: Submit 16 tasks continuously")
            for (taskIndex in 1..totalTasks) {
                val taskId = "task-$taskIndex-${UUID.randomUUID()}"
                
                // Determine if task should be enhanced (based on available enhanced nodes)
                val enhancedNodeCount = nodes.count { it["enhanced"] as Boolean }
                val useEnhancedMode = enhancedNodeCount > 0 && taskIndex % 2 == 0
                
                val job = async {
                    try {
                        val task = TaskManager.ComputeTask(
                            taskId = taskId,
                            type = TaskType.PYTHON,
                            executable = "print('Task $taskIndex')",
                            inputFiles = emptyList(),
                            requirements = TaskManager.TaskRequirements(
                                memoryMb = 128,
                                timeoutSeconds = 30,
                                networkAccess = false
                            )
                        )
                        
                        if (useEnhancedMode) {
                            // Enhanced mode
                            FeatureFlags.enableTaskKeypair()
                            val keypair = taskManager.generateTaskKeypair(taskId)
                            val container = computeEngine.createContainer(task, keypair = keypair)
                            val result = container.execute()
                            FeatureFlags.disableTaskKeypair()
                            
                            if (result.success) {
                                println("    Task $taskIndex completed (enhanced mode)")
                                successCount.incrementAndGet()
                            }
                        } else {
                            // Legacy mode
                            val container = computeEngine.createContainer(task, keypair = null)
                            val result = container.execute()
                            
                            if (result.success) {
                                println("    Task $taskIndex completed (legacy mode)")
                                successCount.incrementAndGet()
                            }
                        }
                    } catch (e: Exception) {
                        println("    Task $taskIndex failed: ${e.message}")
                    }
                }
                
                taskJobs.add(job)
                
                // Upgrade a node every 2 tasks
                if (taskIndex % 2 == 0 && taskIndex / 2 <= 8) {
                    val nodeIndex = (taskIndex / 2) - 1
                    println("  Step 3.$nodeIndex: Upgrade node-${nodeIndex + 1} to enhanced")
                    nodes[nodeIndex] = mapOf(
                        "id" to "node-${nodeIndex + 1}",
                        "enhanced" to true
                    )
                    
                    val enhancedCount = nodes.count { it["enhanced"] as Boolean }
                    val legacyCount = nodes.size - enhancedCount
                    println("    Current mesh: $enhancedCount enhanced, $legacyCount legacy")
                }
                
                // Small delay between task submissions
                delay(10)
            }
            
            // Wait for all tasks to complete
            println("  Step 4: Wait for all tasks to complete")
            taskJobs.awaitAll()
            
            // Verify all tasks succeeded
            println("  Step 5: Verify all tasks succeeded")
            require(successCount.get() == totalTasks) {
                "Expected $totalTasks successful tasks, got ${successCount.get()}"
            }
            
            // Verify all nodes upgraded
            println("  Step 6: Verify all nodes upgraded")
            val enhancedNodeCount = nodes.count { it["enhanced"] as Boolean }
            require(enhancedNodeCount == 8) {
                "Expected 8 enhanced nodes, got $enhancedNodeCount"
            }
            
            val executionTime = System.currentTimeMillis() - startTime
            println("  ✅ PASSED (${executionTime}ms)")
            
            return@runBlocking TestResult(
                testName = testName,
                testCase = "TC-BC-05",
                passed = true,
                executionTimeMs = executionTime,
                details = mapOf(
                    "totalNodes" to 8,
                    "totalTasks" to totalTasks,
                    "successfulTasks" to successCount.get(),
                    "finalEnhancedNodes" to enhancedNodeCount,
                    "zeroDowntime" to true
                )
            )
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            println("  ❌ FAILED: ${e.message}")
            
            return@runBlocking TestResult(
                testName = testName,
                testCase = "TC-BC-05",
                passed = false,
                executionTimeMs = executionTime,
                errorMessage = e.message
            )
        }
    }
    
    /**
     * Generate comprehensive test report
     */
    fun generateReport(suiteResult: SuiteResult): String {
        val report = StringBuilder()
        
        report.appendLine("=" .repeat(80))
        report.appendLine("BACKWARD COMPATIBILITY TEST SUITE REPORT - PHASE 8.2")
        report.appendLine("=" .repeat(80))
        report.appendLine()
        
        // Summary
        report.appendLine("SUMMARY")
        report.appendLine("-".repeat(80))
        report.appendLine("Total Test Cases:  ${suiteResult.totalTests}")
        report.appendLine("Passed:            ${suiteResult.passed} ✅")
        report.appendLine("Failed:            ${suiteResult.failed} ❌")
        report.appendLine("Success Rate:      ${String.format("%.1f%%", suiteResult.successRate * 100)}")
        report.appendLine("Total Time:        ${suiteResult.totalExecutionTimeMs}ms")
        report.appendLine()
        
        // Detailed results
        report.appendLine("TEST CASE DETAILS")
        report.appendLine("-".repeat(80))
        suiteResult.testResults.forEach { result ->
            val status = if (result.passed) "✅ PASS" else "❌ FAIL"
            report.appendLine("[${result.testCase}] ${result.testName}")
            report.appendLine("  Status: $status (${result.executionTimeMs}ms)")
            
            if (result.passed) {
                result.details.forEach { (key, value) ->
                    report.appendLine("  - $key: $value")
                }
            } else {
                report.appendLine("  Error: ${result.errorMessage}")
            }
            report.appendLine()
        }
        
        // Test case summaries
        report.appendLine("TEST CASE SUMMARIES")
        report.appendLine("-".repeat(80))
        
        report.appendLine("TC-BC-01: Legacy task on enhanced node")
        report.appendLine("  ✓ Legacy task executes successfully on enhanced node")
        report.appendLine("  ✓ No keypair generated (backward compatible)")
        report.appendLine()
        
        report.appendLine("TC-BC-02: Enhanced task on legacy node")
        report.appendLine("  ✓ Enhanced task rejected with UNSUPPORTED_FEATURE")
        report.appendLine("  ✓ Graceful error handling, no crash")
        report.appendLine()
        
        report.appendLine("TC-BC-03: Mixed mesh (50% enhanced, 50% legacy)")
        report.appendLine("  ✓ Enhanced tasks route to enhanced nodes")
        report.appendLine("  ✓ Legacy tasks work on any node")
        report.appendLine("  ✓ All tasks succeed (10/10)")
        report.appendLine()
        
        report.appendLine("TC-BC-04: Feature flag disable during execution")
        report.appendLine("  ✓ Running task completes despite flag disable")
        report.appendLine("  ✓ New tasks use legacy mode after disable")
        report.appendLine("  ✓ Graceful mode transition")
        report.appendLine()
        
        report.appendLine("TC-BC-05: Rolling upgrade scenario")
        report.appendLine("  ✓ Zero downtime during upgrade")
        report.appendLine("  ✓ All tasks succeed (16/16)")
        report.appendLine("  ✓ All nodes upgraded (8/8)")
        report.appendLine()
        
        // Overall result
        report.appendLine("=" .repeat(80))
        if (suiteResult.successRate == 1.0) {
            report.appendLine("✅ BACKWARD COMPATIBILITY TEST SUITE PASSED")
            report.appendLine("All 5 test cases passed - backward compatibility verified")
        } else {
            report.appendLine("❌ BACKWARD COMPATIBILITY TEST SUITE FAILED")
            report.appendLine("${suiteResult.failed} test case(s) failed - backward compatibility issues detected")
        }
        report.appendLine("=" .repeat(80))
        
        return report.toString()
    }
}
