package com.ustadmobile.meshrabiya.service.compute

import kotlinx.coroutines.*

/**
 * Test interface for validating distributed service layer functionality
 * Provides methods to test compute tasks, storage operations, and service coordination
 */
class ServiceLayerTestInterface(
    private val coordinator: ServiceLayerCoordinator
) {
    
    data class TestResult(
        val testName: String,
        val success: Boolean,
        val duration: Long,
        val details: String,
        val error: String? = null
    )
    
    data class TestSuite(
        val suiteName: String,
        val results: List<TestResult>,
        val totalDuration: Long,
        val successRate: Float
    )
    
    suspend fun runBasicServiceTests(): TestSuite {
        val results = mutableListOf<TestResult>()
        val startTime = System.currentTimeMillis()
        
        // Test 1: Service activation
        results.add(testServiceActivation())
        
        // Test 2: Basic compute task
        if (coordinator.isServiceActive()) {
            results.add(testBasicComputeTask())
            
            // DEPRECATED: Test 3: Storage operation - November 14, 2025
            // This test used deprecated DistributedStorageAgent
            // Use DistributedStorageManager tests instead
            // results.add(testBasicStorageOperation())
            
            // Test 4: Service capabilities
            results.add(testServiceCapabilities())
            
            // Test 5: Statistics retrieval
            results.add(testStatisticsRetrieval())
        }
        
        val totalDuration = System.currentTimeMillis() - startTime
        val successCount = results.count { it.success }
        val successRate = if (results.isNotEmpty()) (successCount.toFloat() / results.size) * 100f else 0f
        
        return TestSuite(
            suiteName = "Basic Service Layer Tests",
            results = results,
            totalDuration = totalDuration,
            successRate = successRate
        )
    }
    
    private suspend fun testServiceActivation(): TestResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            val wasActive = coordinator.isServiceActive()
            
            if (!wasActive) {
                val activated = coordinator.startServices()
                if (activated && coordinator.isServiceActive()) {
                    TestResult(
                        testName = "Service Activation",
                        success = true,
                        duration = System.currentTimeMillis() - startTime,
                        details = "Service successfully activated"
                    )
                } else {
                    TestResult(
                        testName = "Service Activation",
                        success = false,
                        duration = System.currentTimeMillis() - startTime,
                        details = "Service activation failed",
                        error = "Failed to activate service layer"
                    )
                }
            } else {
                TestResult(
                    testName = "Service Activation",
                    success = true,
                    duration = System.currentTimeMillis() - startTime,
                    details = "Service was already active"
                )
            }
            
        } catch (e: Exception) {
            TestResult(
                testName = "Service Activation",
                success = false,
                duration = System.currentTimeMillis() - startTime,
                details = "Exception during activation",
                error = e.message
            )
        }
    }
    
    private suspend fun testBasicComputeTask(): TestResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            // Create a simple Python compute task
            val task = IntelligentDistributedComputeService.ComputeTask.PythonTask(
                taskId = "test_task_${System.currentTimeMillis()}",
                scriptCode = "result = 2 + 2",
                inputData = emptyMap(),
                libraries = emptySet(),
                estimatedExecutionMs = 1000L,
                resourceRequirements = ResourceRequirements(
                    minRAMMB = 64,
                    preferredRAMMB = 128,
                    cpuIntensity = CPUIntensity.LIGHT,
                    requiresGPU = false
                ),
                outputSchema = IntelligentDistributedComputeService.OutputSchema(
                    IntelligentDistributedComputeService.OutputFormat.JSON,
                    1024,
                    mapOf("result" to "string")
                )
            )
            
            val taskId = coordinator.submitComputeTask(task)
            
            // Wait a bit and check status
            delay(1000)
            val status = coordinator.getComputeTaskStatus(taskId)
            
            if (status != null) {
                TestResult(
                    testName = "Basic Compute Task",
                    success = true,
                    duration = System.currentTimeMillis() - startTime,
                    details = "Task submitted successfully. Status: ${status.status}, Progress: ${status.progress}"
                )
            } else {
                TestResult(
                    testName = "Basic Compute Task",
                    success = false,
                    duration = System.currentTimeMillis() - startTime,
                    details = "Failed to retrieve task status",
                    error = "Task status not found"
                )
            }
            
        } catch (e: Exception) {
            TestResult(
                testName = "Basic Compute Task",
                success = false,
                duration = System.currentTimeMillis() - startTime,
                details = "Exception during compute task test",
                error = e.message
            )
        }
    }
    
    // DEPRECATED: testBasicStorageOperation - November 14, 2025
    // This test used deprecated DistributedStorageAgent (storeFile/retrieveFile methods)
    // Use DistributedStorageManager tests instead
    /*
    private suspend fun testBasicStorageOperation(): TestResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            // Test storing a small file
            val testData = "Hello, Distributed Storage!".toByteArray()
            val fileId = coordinator.storeFile(
                fileName = "test_file.txt",
                data = testData,
                tags = setOf("test", "validation")
            )
            
            // Wait a bit for storage operation
            delay(500)
            
            // Try to retrieve the file
            val retrievedData = coordinator.retrieveFile(fileId)
            
            if (retrievedData != null && retrievedData.contentEquals(testData)) {
                TestResult(
                    testName = "Basic Storage Operation",
                    success = true,
                    duration = System.currentTimeMillis() - startTime,
                    details = "File stored and retrieved successfully. Size: ${testData.size} bytes"
                )
            } else {
                TestResult(
                    testName = "Basic Storage Operation",
                    success = false,
                    duration = System.currentTimeMillis() - startTime,
                    details = "File retrieval failed or data mismatch",
                    error = "Retrieved data does not match stored data"
                )
            }
            
        } catch (e: Exception) {
            TestResult(
                testName = "Basic Storage Operation",
                success = false,
                duration = System.currentTimeMillis() - startTime,
                details = "Exception during storage operation test",
                error = e.message
            )
        }
    }
    */
    
    private suspend fun testServiceCapabilities(): TestResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            val capabilities = coordinator.getServiceCapabilities()
            
            val expectedFeatures = listOf(
                "computeEnabled",
                "storageEnabled", 
                "supportedComputeTypes",
                "maxComputeThreads",
                "maxStorageGB"
            )
            
            val details = buildString {
                append("Compute: ${capabilities.computeEnabled}, ")
                append("Storage: ${capabilities.storageEnabled}, ")
                append("Threads: ${capabilities.maxComputeThreads}, ")
                append("Storage: ${capabilities.maxStorageGB}GB, ")
                append("Types: ${capabilities.supportedComputeTypes.size}")
            }
            
            TestResult(
                testName = "Service Capabilities",
                success = true,
                duration = System.currentTimeMillis() - startTime,
                details = details
            )
            
        } catch (e: Exception) {
            TestResult(
                testName = "Service Capabilities",
                success = false,
                duration = System.currentTimeMillis() - startTime,
                details = "Exception during capabilities test",
                error = e.message
            )
        }
    }
    
    private suspend fun testStatisticsRetrieval(): TestResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            val stats = coordinator.getServiceStatistics()
            val activeOps = coordinator.getActiveOperations()
            
            val details = buildString {
                append("Tasks: ${stats.computeTasksCompleted}/${stats.computeTasksFailed}, ")
                append("Storage: ${stats.storageRequestsHandled}/${stats.storageErrorsEncountered}, ")
                append("Bytes: ${stats.totalBytesProcessed}, ")
                append("Active Ops: ${activeOps.size}")
            }
            
            TestResult(
                testName = "Statistics Retrieval",
                success = true,
                duration = System.currentTimeMillis() - startTime,
                details = details
            )
            
        } catch (e: Exception) {
            TestResult(
                testName = "Statistics Retrieval",
                success = false,
                duration = System.currentTimeMillis() - startTime,
                details = "Exception during statistics test",
                error = e.message
            )
        }
    }
    
    suspend fun runPerformanceTests(): TestSuite {
        val results = mutableListOf<TestResult>()
        val startTime = System.currentTimeMillis()
        
        if (!coordinator.isServiceActive()) {
            coordinator.startServices()
            delay(2000) // Allow service to start
        }
        
        // Performance test 1: Multiple concurrent compute tasks
        results.add(testConcurrentComputeTasks())
        
        // Performance test 2: Large file storage
        results.add(testLargeFileStorage())
        
        // Performance test 3: Rapid task submission
        results.add(testRapidTaskSubmission())
        
        val totalDuration = System.currentTimeMillis() - startTime
        val successCount = results.count { it.success }
        val successRate = if (results.isNotEmpty()) (successCount.toFloat() / results.size) * 100f else 0f
        
        return TestSuite(
            suiteName = "Performance Tests",
            results = results,
            totalDuration = totalDuration,
            successRate = successRate
        )
    }
    
    private suspend fun testConcurrentComputeTasks(): TestResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            val taskCount = 5
            val tasks = coroutineScope {
                (1..taskCount).map { i ->
                    async {
                        val task = IntelligentDistributedComputeService.ComputeTask.PythonTask(
                            taskId = "concurrent_task_$i",
                            scriptCode = "import time; time.sleep(0.1); result = $i * 2",
                            inputData = emptyMap(),
                            libraries = emptySet(),
                            estimatedExecutionMs = 500L,
                            resourceRequirements = ResourceRequirements(
                                minRAMMB = 32,
                                preferredRAMMB = 64,
                                cpuIntensity = CPUIntensity.LIGHT,
                                requiresGPU = false
                            ),
                            outputSchema = IntelligentDistributedComputeService.OutputSchema(
                                IntelligentDistributedComputeService.OutputFormat.JSON,
                                1024,
                                mapOf("result" to "string")
                            )
                        )
                        coordinator.submitComputeTask(task)
                    }
                }
            }
            
            val taskIds = tasks.awaitAll()
            delay(2000) // Allow tasks to process
            
            val completedCount = taskIds.count { taskId ->
                val status = coordinator.getComputeTaskStatus(taskId)
                status?.status in listOf("COMPLETED", "FAILED")
            }
            
            TestResult(
                testName = "Concurrent Compute Tasks",
                success = completedCount >= taskCount / 2, // At least half should complete
                duration = System.currentTimeMillis() - startTime,
                details = "$completedCount/$taskCount tasks processed"
            )
            
        } catch (e: Exception) {
            TestResult(
                testName = "Concurrent Compute Tasks",
                success = false,
                duration = System.currentTimeMillis() - startTime,
                details = "Exception during concurrent tasks test",
                error = e.message
            )
        }
    }
    
    private suspend fun testLargeFileStorage(): TestResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            // Create a 1MB test file
            val largeData = ByteArray(1024 * 1024) { (it % 256).toByte() }
            
            val fileId = coordinator.storeFile(
                fileName = "large_test_file.bin",
                data = largeData,
                tags = setOf("performance", "large")
            )
            
            delay(3000) // Allow time for storage
            
            val retrievedData = coordinator.retrieveFile(fileId)
            val success = retrievedData != null && retrievedData.size == largeData.size
            
            TestResult(
                testName = "Large File Storage",
                success = success,
                duration = System.currentTimeMillis() - startTime,
                details = "1MB file ${if (success) "successfully" else "failed to be"} stored and retrieved"
            )
            
        } catch (e: Exception) {
            TestResult(
                testName = "Large File Storage",
                success = false,
                duration = System.currentTimeMillis() - startTime,
                details = "Exception during large file test",
                error = e.message
            )
        }
    }
    
    private suspend fun testRapidTaskSubmission(): TestResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            val taskCount = 20
            val submissionStartTime = System.currentTimeMillis()
            
            val taskIds = (1..taskCount).map { i ->
                val task = IntelligentDistributedComputeService.ComputeTask.PythonTask(
                    taskId = "rapid_task_$i",
                    scriptCode = "result = $i",
                    inputData = emptyMap(),
                    libraries = emptySet(),
                    estimatedExecutionMs = 100L,
                    resourceRequirements = ResourceRequirements(
                        minRAMMB = 16,
                        preferredRAMMB = 32,
                        cpuIntensity = CPUIntensity.LIGHT,
                        requiresGPU = false
                    ),
                    outputSchema = IntelligentDistributedComputeService.OutputSchema(
                        IntelligentDistributedComputeService.OutputFormat.JSON,
                        1024,
                        mapOf("result" to "string")
                    )
                )
                coordinator.submitComputeTask(task)
            }
            
            val submissionDuration = System.currentTimeMillis() - submissionStartTime
            val tasksPerSecond = (taskCount * 1000f) / submissionDuration
            
            TestResult(
                testName = "Rapid Task Submission",
                success = submissionDuration < 5000, // Should complete in under 5 seconds
                duration = System.currentTimeMillis() - startTime,
                details = "$taskCount tasks submitted in ${submissionDuration}ms (${String.format("%.1f", tasksPerSecond)} tasks/sec)"
            )
            
        } catch (e: Exception) {
            TestResult(
                testName = "Rapid Task Submission",
                success = false,
                duration = System.currentTimeMillis() - startTime,
                details = "Exception during rapid submission test",
                error = e.message
            )
        }
    }
}
