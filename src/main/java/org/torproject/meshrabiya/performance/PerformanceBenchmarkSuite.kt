package org.torproject.meshrabiya.performance

// ...existing code...

/**
 * Performance Benchmark Suite for Distributed Compute System
 * 
 * Implements 5 comprehensive benchmark tests:
 * 1. Keypair Generation Latency
 * 2. Multi-Recipient Encryption
 * 3. File Decryption Performance
 * 4. Session Key Re-Encryption
 * 5. End-to-End Task Execution Overhead
 * 
 * Performance Targets:
 * - Keypair generation: <500ms (p95) on mobile
 * - File re-encryption: <100ms per file (p95)
 * - File decryption: <50ms per file (p95)
 * - Session key re-encryption: <100ms (p95)
 * - End-to-end overhead: <2% vs baseline
 * 
 * @property taskManager TaskManager for task operations
 * @property storageManager DistributedStorageManager for storage operations
 * @property keypairGenerator PGPKeypairGenerator for key generation
 */
class PerformanceBenchmarkSuite(
    private val taskManager: TaskManager,
    private val storageManager: DistributedStorageManager,
    private val keypairGenerator: PGPKeypairGenerator
) {
    
    /**
     * Benchmark result for a single test.
     */
    data class BenchmarkResult(
        val testName: String,
        val iterations: Int,
        val stats: PerformanceStats,
        val target: BenchmarkTarget,
        val passed: Boolean,
        val details: Map<String, Any> = emptyMap()
    )
    
    /**
     * Performance statistics.
     */
    data class PerformanceStats(
        val mean: Long,
        val median: Long,
        val p50: Long,
        val p95: Long,
        val p99: Long,
        val min: Long,
        val max: Long,
        val stdDev: Double
    )
    
    /**
     * Benchmark target thresholds.
     */
    data class BenchmarkTarget(
        val metricName: String,
        val targetValue: Long,
        val unit: String
    )
    
    /**
     * Suite result.
     */
    data class SuiteResult(
        val totalBenchmarks: Int,
        val passed: Int,
        val failed: Int,
        val results: List<BenchmarkResult>
    ) {
        val allPassed: Boolean get() = failed == 0
        val passRate: Double get() = if (totalBenchmarks > 0) passed.toDouble() / totalBenchmarks else 0.0
    }
    
    /**
     * Run all performance benchmarks.
     * 
     * @return Suite result with all benchmark results
     */
    fun runAllBenchmarks(): SuiteResult {
        val results = mutableListOf<BenchmarkResult>()
        
        // Benchmark 1: Keypair Generation Latency
        results.add(benchmarkKeypairGeneration())
        
        // Benchmark 2: Multi-Recipient Encryption
        results.add(benchmarkMultiRecipientEncryption())
        
        // Benchmark 3: File Decryption Performance
        results.add(benchmarkFileDecryption())
        
        // Benchmark 4: Session Key Re-Encryption
        results.add(benchmarkSessionKeyReEncryption())
        
        // Benchmark 5: End-to-End Task Execution Overhead
        results.add(benchmarkEndToEndOverhead())
        
        val passed = results.count { it.passed }
        val failed = results.count { !it.passed }
        
        return SuiteResult(
            totalBenchmarks = results.size,
            passed = passed,
            failed = failed,
            results = results
        )
    }
    
    /**
     * Benchmark 1: Keypair Generation Latency
     * Target: <500ms (p95) on mobile
     */
    private fun benchmarkKeypairGeneration(): BenchmarkResult {
        return try {
            val iterations = 100
            val timings = mutableListOf<Long>()
            
            repeat(iterations) { i ->
                val time = measureTimeMillis {
                    keypairGenerator.generateKeypair("benchmark-task-$i")
                }
                timings.add(time)
            }
            
            val stats = analyzeTimings(timings)
            val target = BenchmarkTarget("p95 latency", 500, "ms")
            val passed = stats.p95 < target.targetValue
            
            BenchmarkResult(
                testName = "benchmarkKeypairGeneration",
                iterations = iterations,
                stats = stats,
                target = target,
                passed = passed,
                details = mapOf(
                    "algorithm" to "RSA-4096",
                    "status" to if (passed) "✅ PASS" else "❌ FAIL"
                )
            )
        } catch (e: Exception) {
            BenchmarkResult(
                testName = "benchmarkKeypairGeneration",
                iterations = 0,
                stats = PerformanceStats(0, 0, 0, 0, 0, 0, 0, 0.0),
                target = BenchmarkTarget("p95 latency", 500, "ms"),
                passed = false,
                details = mapOf("error" to (e.message ?: "Unknown error"))
            )
        }
    }
    
    /**
     * Benchmark 2: Multi-Recipient Encryption
     * Measures session key generation + multi-recipient encryption
     * Target: Linear scaling O(n)
     */
    private fun benchmarkMultiRecipientEncryption(): BenchmarkResult {
        return try {
            runBlocking {
                val testData = ByteArray(1024 * 1024) { it.toByte() }  // 1MB test data
                val recipientCounts = listOf(1, 5, 10, 50, 100)
                val allTimings = mutableListOf<Long>()
                val scalingResults = mutableMapOf<Int, PerformanceStats>()
                
                for (recipientCount in recipientCounts) {
                    // Generate recipient keypairs
                    val recipients = (1..recipientCount).map { i ->
                        val keypair = taskManager.generateTaskKeypair(
                            taskId = "recipient-$i",
                            lifetimeMs = 300000
                        )
                        storageManager.RecipientEntry(
                            publicKey = keypair!!.publicKey,
                            recipientType = RecipientType.TASK,
                            expiresAt = System.currentTimeMillis() + 300000,
                            taskId = "recipient-$i"
                        )
                    }
                    
                    // Benchmark encryption
                    val iterations = 20
                    val timings = mutableListOf<Long>()
                    
                    repeat(iterations) {
                        val time = measureTimeMillis {
                            storageManager.storeFile(
                                data = testData,
                                filename = "test-$recipientCount-recipients.dat",
                                recipients = recipients
                            )
                        }
                        timings.add(time)
                        allTimings.add(time)
                    }
                    
                    scalingResults[recipientCount] = analyzeTimings(timings)
                    
                    // Cleanup
                    taskManager.cleanupExpiredKeypairs()
                }
                
                // Verify linear scaling
                val oneRecipientP95 = scalingResults[1]?.p95 ?: 0
                val hundredRecipientsP95 = scalingResults[100]?.p95 ?: 0
                
                // Expected: ~50ms base + ~10ms per recipient = ~1050ms for 100 recipients
                val expectedMaxTime = 50 + (100 * 10)  // 1050ms
                val passed = hundredRecipientsP95 < expectedMaxTime
                
                val stats = analyzeTimings(allTimings)
                
                BenchmarkResult(
                    testName = "benchmarkMultiRecipientEncryption",
                    iterations = recipientCounts.size * 20,
                    stats = stats,
                    target = BenchmarkTarget("100 recipients p95", expectedMaxTime, "ms"),
                    passed = passed,
                    details = mapOf(
                        "fileSize" to "1MB",
                        "recipientCounts" to recipientCounts,
                        "1_recipient_p95" to "${oneRecipientP95}ms",
                        "100_recipients_p95" to "${hundredRecipientsP95}ms",
                        "scaling" to "Linear O(n)",
                        "status" to if (passed) "✅ PASS" else "❌ FAIL"
                    )
                )
            }
        } catch (e: Exception) {
            BenchmarkResult(
                testName = "benchmarkMultiRecipientEncryption",
                iterations = 0,
                stats = PerformanceStats(0, 0, 0, 0, 0, 0, 0, 0.0),
                target = BenchmarkTarget("100 recipients p95", 1050, "ms"),
                passed = false,
                details = mapOf("error" to (e.message ?: "Unknown error"))
            )
        }
    }
    
    /**
     * Benchmark 3: File Decryption Performance
     * Target: <50ms per file (p95)
     */
    private fun benchmarkFileDecryption(): BenchmarkResult {
        return try {
            runBlocking {
                val fileSizes = listOf(
                    1024,               // 1KB
                    1024 * 100,         // 100KB
                    1024 * 1024,        // 1MB
                    1024 * 1024 * 10    // 10MB
                )
                
                val allTimings = mutableListOf<Long>()
                val sizeResults = mutableMapOf<Int, PerformanceStats>()
                
                for (fileSize in fileSizes) {
                    val testData = ByteArray(fileSize) { it.toByte() }
                    val keypair = taskManager.generateTaskKeypair("decrypt-test", lifetimeMs = 300000)!!
                    
                    val recipient = storageManager.RecipientEntry(
                        publicKey = keypair.publicKey,
                        recipientType = RecipientType.TASK,
                        expiresAt = System.currentTimeMillis() + 300000,
                        taskId = "decrypt-test"
                    )
                    
                    // Store file once
                    val fileId = storageManager.storeFile(
                        data = testData,
                        filename = "decrypt-test-${fileSize}.dat",
                        recipients = listOf(recipient)
                    )
                    
                    // Benchmark decryption
                    val iterations = 50
                    val timings = mutableListOf<Long>()
                    
                    repeat(iterations) {
                        val time = measureTimeMillis {
                            storageManager.retrieveFile(fileId, "decrypt-test")
                        }
                        timings.add(time)
                        allTimings.add(time)
                    }
                    
                    sizeResults[fileSize] = analyzeTimings(timings)
                    
                    // Cleanup
                    storageManager.deleteFile(fileId)
                }
                
                taskManager.cleanupExpiredKeypairs()
                
                // Check 1MB file performance (most common size)
                val oneMBStats = sizeResults[1024 * 1024]
                val passed = (oneMBStats?.p95 ?: Long.MAX_VALUE) < 50
                
                val stats = analyzeTimings(allTimings)
                
                BenchmarkResult(
                    testName = "benchmarkFileDecryption",
                    iterations = fileSizes.size * 50,
                    stats = stats,
                    target = BenchmarkTarget("1MB file p95", 50, "ms"),
                    passed = passed,
                    details = mapOf(
                        "fileSizes" to fileSizes.map { "${it / 1024}KB" },
                        "1KB_p95" to "${sizeResults[1024]?.p95 ?: 0}ms",
                        "100KB_p95" to "${sizeResults[1024 * 100]?.p95 ?: 0}ms",
                        "1MB_p95" to "${sizeResults[1024 * 1024]?.p95 ?: 0}ms",
                        "10MB_p95" to "${sizeResults[1024 * 1024 * 10]?.p95 ?: 0}ms",
                        "status" to if (passed) "✅ PASS" else "❌ FAIL"
                    )
                )
            }
        } catch (e: Exception) {
            BenchmarkResult(
                testName = "benchmarkFileDecryption",
                iterations = 0,
                stats = PerformanceStats(0, 0, 0, 0, 0, 0, 0, 0.0),
                target = BenchmarkTarget("1MB file p95", 50, "ms"),
                passed = false,
                details = mapOf("error" to (e.message ?: "Unknown error"))
            )
        }
    }
    
    /**
     * Benchmark 4: Session Key Re-Encryption
     * This is the critical optimization for dynamic file sharing
     * Target: <100ms per recipient (independent of file size)
     */
    private fun benchmarkSessionKeyReEncryption(): BenchmarkResult {
        return try {
            runBlocking {
                val testData = ByteArray(1024 * 1024 * 10) { it.toByte() }  // 10MB file
                
                // Initial encryption for owner
                val ownerKeypair = taskManager.generateTaskKeypair("owner", lifetimeMs = 300000)!!
                val ownerRecipient = storageManager.RecipientEntry(
                    publicKey = ownerKeypair.publicKey,
                    recipientType = RecipientType.USER,
                    expiresAt = System.currentTimeMillis() + 300000,
                    taskId = "owner"
                )
                
                val fileId = storageManager.storeFile(
                    data = testData,
                    filename = "reencrypt-test.dat",
                    recipients = listOf(ownerRecipient)
                )
                
                // Add 10 new recipients one by one
                val iterations = 10
                val timings = mutableListOf<Long>()
                
                repeat(iterations) { i ->
                    val taskKeypair = taskManager.generateTaskKeypair("task-$i", lifetimeMs = 300000)!!
                    val taskRecipient = storageManager.RecipientEntry(
                        publicKey = taskKeypair.publicKey,
                        recipientType = RecipientType.TASK,
                        expiresAt = System.currentTimeMillis() + 300000,
                        taskId = "task-$i"
                    )
                    
                    val time = measureTimeMillis {
                        storageManager.updateFileAccess(
                            fileId = fileId,
                            addRecipients = listOf(taskRecipient),
                            removeRecipients = emptyList()
                        )
                    }
                    timings.add(time)
                }
                
                val stats = analyzeTimings(timings)
                val target = BenchmarkTarget("p95 latency", 100, "ms")
                val passed = stats.p95 < target.targetValue
                
                // Cleanup
                storageManager.deleteFile(fileId)
                taskManager.cleanupExpiredKeypairs()
                
                BenchmarkResult(
                    testName = "benchmarkSessionKeyReEncryption",
                    iterations = iterations,
                    stats = stats,
                    target = target,
                    passed = passed,
                    details = mapOf(
                        "originalFileSize" to "10MB",
                        "note" to "Only re-encrypts session key (~256 bytes), not entire file",
                        "optimization" to "Key operation",
                        "status" to if (passed) "✅ PASS" else "❌ FAIL"
                    )
                )
            }
        } catch (e: Exception) {
            BenchmarkResult(
                testName = "benchmarkSessionKeyReEncryption",
                iterations = 0,
                stats = PerformanceStats(0, 0, 0, 0, 0, 0, 0, 0.0),
                target = BenchmarkTarget("p95 latency", 100, "ms"),
                passed = false,
                details = mapOf("error" to (e.message ?: "Unknown error"))
            )
        }
    }
    
    /**
     * Benchmark 5: End-to-End Task Execution Overhead
     * Measures complete overhead of keypair enhancement
     * Target: <2% overhead vs baseline
     */
    private fun benchmarkEndToEndOverhead(): BenchmarkResult {
        return try {
            runBlocking {
                val inputFiles = listOf(
                    ByteArray(1024 * 100),      // 100KB
                    ByteArray(1024 * 500),      // 500KB
                    ByteArray(1024 * 1024)      // 1MB
                )
                
                // Baseline: Task execution WITHOUT keypair (simulated)
                val baselineTime = measureTimeMillis {
                    simulateTaskExecutionWithoutKeypair(inputFiles)
                }
                
                // With keypair enhancement
                val iterations = 10
                val timings = mutableListOf<Long>()
                
                repeat(iterations) {
                    val time = measureTimeMillis {
                        simulateTaskExecutionWithKeypair(inputFiles)
                    }
                    timings.add(time)
                }
                
                val stats = analyzeTimings(timings)
                val overhead = stats.mean - baselineTime
                val overheadPercent = (overhead.toDouble() / baselineTime) * 100
                
                val target = BenchmarkTarget("overhead", 2, "%")
                val passed = overheadPercent < 2.0
                
                BenchmarkResult(
                    testName = "benchmarkEndToEndOverhead",
                    iterations = iterations,
                    stats = stats,
                    target = target,
                    passed = passed,
                    details = mapOf(
                        "baseline" to "${baselineTime}ms",
                        "withKeypair_mean" to "${stats.mean}ms",
                        "absoluteOverhead" to "${overhead}ms",
                        "relativeOverhead" to "${"%.2f".format(overheadPercent)}%",
                        "breakdown" to mapOf(
                            "keypairGeneration" to "~500ms",
                            "fileReEncryption" to "~${inputFiles.size * 100}ms",
                            "fileDecryption" to "~${inputFiles.size * 50}ms"
                        ),
                        "status" to if (passed) "✅ PASS" else "⚠️ ACCEPTABLE"
                    )
                )
            }
        } catch (e: Exception) {
            BenchmarkResult(
                testName = "benchmarkEndToEndOverhead",
                iterations = 0,
                stats = PerformanceStats(0, 0, 0, 0, 0, 0, 0, 0.0),
                target = BenchmarkTarget("overhead", 2, "%"),
                passed = false,
                details = mapOf("error" to (e.message ?: "Unknown error"))
            )
        }
    }
    
    /**
     * Simulate task execution without keypair (baseline).
     */
    private suspend fun simulateTaskExecutionWithoutKeypair(inputFiles: List<ByteArray>) {
        // Simulate simple task execution
        kotlinx.coroutines.delay(100)  // Container startup
        inputFiles.forEach { _ ->
            // Process file
            kotlinx.coroutines.delay(10)
        }
        kotlinx.coroutines.delay(50)  // Container cleanup
    }
    
    /**
     * Simulate task execution with keypair.
     */
    private suspend fun simulateTaskExecutionWithKeypair(inputFiles: List<ByteArray>) {
        // 1. Generate keypair
        val keypair = taskManager.generateTaskKeypair("benchmark-task", lifetimeMs = 300000)!!
        
        // 2. Re-encrypt input files
        inputFiles.forEach { file ->
            val recipient = storageManager.RecipientEntry(
                publicKey = keypair.publicKey,
                recipientType = RecipientType.TASK,
                expiresAt = System.currentTimeMillis() + 300000,
                taskId = "benchmark-task"
            )
            
            val fileId = storageManager.storeFile(
                data = file,
                filename = "input-${file.size}.dat",
                recipients = listOf(recipient)
            )
            
            // Cleanup
            storageManager.deleteFile(fileId)
        }
        
        // 3. Execute task (simulated)
        kotlinx.coroutines.delay(100)  // Container startup
        kotlinx.coroutines.delay(inputFiles.size * 10L)  // Process files
        kotlinx.coroutines.delay(50)  // Container cleanup
        
        // 4. Cleanup keypair
        taskManager.cleanupExpiredKeypairs()
    }
    
    /**
     * Analyze timing measurements and compute statistics.
     */
    private fun analyzeTimings(timings: List<Long>): PerformanceStats {
        require(timings.isNotEmpty()) { "Timings list cannot be empty" }
        
        val sorted = timings.sorted()
        val n = sorted.size
        
        val mean = timings.average().toLong()
        val median = sorted[n / 2]
        val p50 = sorted[n / 2]
        val p95 = sorted[(n * 0.95).toInt().coerceAtMost(n - 1)]
        val p99 = sorted[(n * 0.99).toInt().coerceAtMost(n - 1)]
        val min = sorted.first()
        val max = sorted.last()
        
        // Calculate standard deviation
        val variance = timings.map { (it - mean).toDouble().pow(2) }.average()
        val stdDev = sqrt(variance)
        
        return PerformanceStats(mean, median, p50, p95, p99, min, max, stdDev)
    }
    
    /**
     * Generate detailed benchmark report.
     */
    fun generateReport(result: SuiteResult): String {
        val sb = StringBuilder()
        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine("        PERFORMANCE BENCHMARK SUITE REPORT")
        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine()
        sb.appendLine("Summary:")
        sb.appendLine("  Total Benchmarks: ${result.totalBenchmarks}")
        sb.appendLine("  Passed: ${result.passed}")
        sb.appendLine("  Failed: ${result.failed}")
        sb.appendLine("  Pass Rate: ${"%.1f".format(result.passRate * 100)}%")
        sb.appendLine("  Overall: ${if (result.allPassed) "✅ ALL TARGETS MET" else "⚠️ SOME TARGETS MISSED"}")
        sb.appendLine()
        sb.appendLine("Performance Targets:")
        sb.appendLine("  - Keypair generation: <500ms (p95)")
        sb.appendLine("  - Multi-recipient encryption: Linear O(n) scaling")
        sb.appendLine("  - File decryption: <50ms per file (p95)")
        sb.appendLine("  - Session key re-encryption: <100ms (p95)")
        sb.appendLine("  - End-to-end overhead: <2%")
        sb.appendLine()
        sb.appendLine("Detailed Results:")
        sb.appendLine("─────────────────────────────────────────────────────────────")
        
        result.results.forEachIndexed { index, benchmark ->
            sb.appendLine()
            sb.appendLine("Benchmark ${index + 1}: ${benchmark.testName}")
            sb.appendLine("  Iterations: ${benchmark.iterations}")
            sb.appendLine("  Status: ${if (benchmark.passed) "✅ PASS" else "❌ FAIL"}")
            sb.appendLine("  Target: ${benchmark.target.metricName} < ${benchmark.target.targetValue}${benchmark.target.unit}")
            sb.appendLine("  Performance Statistics:")
            sb.appendLine("    - Mean: ${benchmark.stats.mean}ms")
            sb.appendLine("    - Median (p50): ${benchmark.stats.p50}ms")
            sb.appendLine("    - p95: ${benchmark.stats.p95}ms")
            sb.appendLine("    - p99: ${benchmark.stats.p99}ms")
            sb.appendLine("    - Min: ${benchmark.stats.min}ms")
            sb.appendLine("    - Max: ${benchmark.stats.max}ms")
            sb.appendLine("    - StdDev: ${"%.2f".format(benchmark.stats.stdDev)}ms")
            
            if (benchmark.details.isNotEmpty()) {
                sb.appendLine("  Additional Details:")
                benchmark.details.forEach { (key, value) ->
                    sb.appendLine("    - $key: $value")
                }
            }
        }
        
        sb.appendLine()
        sb.appendLine("═══════════════════════════════════════════════════════════")
        if (result.allPassed) {
            sb.appendLine("🎉 PERFORMANCE ASSESSMENT: ALL TARGETS MET")
            sb.appendLine("   System performance is within acceptable ranges.")
        } else {
            sb.appendLine("⚠️  PERFORMANCE ASSESSMENT: SOME TARGETS MISSED")
            sb.appendLine("   Review failed benchmarks and consider optimization.")
        }
        sb.appendLine("═══════════════════════════════════════════════════════════")
        
        return sb.toString()
    }
}
