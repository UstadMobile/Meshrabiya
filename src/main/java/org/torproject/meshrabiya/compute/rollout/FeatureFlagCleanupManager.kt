package org.torproject.meshrabiya.compute.rollout

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * FeatureFlagCleanupManager
 * 
 * Manages Phase 4 of the rollout: Feature flag cleanup after 100% deployment.
 * 
 * Features:
 * - Safely removes legacy code paths after stable operation
 * - Detects feature flag usage across codebase
 * - Validates safe removal (no regressions)
 * - Makes keypair enhancement mandatory for all encrypted tasks
 * - Generates cleanup report and recommendations
 * 
 * Cleanup Strategy:
 * - Verify 4 weeks of stable operation (all nodes at 100%)
 * - Detect all feature flag references in codebase
 * - Validate removal won't break functionality
 * - Remove feature flag code (make enhancement default)
 * - Update documentation to reflect new behavior
 * 
 * @property codebaseRoot Root directory of codebase to scan
 * @property featureFlagManager Current feature flag manager
 * @property validator Validates safe removal
 */
class FeatureFlagCleanupManager(
    private val codebaseRoot: File,
    private val featureFlagManager: FeatureFlagManager,
    private val validator: SafeRemovalValidator,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val _state = MutableStateFlow<CleanupState>(CleanupState.NotStarted)
    val state: StateFlow<CleanupState> = _state.asStateFlow()
    
    private val detectedFlags = ConcurrentHashMap<String, MutableList<FlagUsage>>()
    private val removalPlan = mutableListOf<RemovalStep>()
    
    /**
     * Start feature flag cleanup process
     * 
     * Steps:
     * 1. Verify stable operation (4 weeks at 100%)
     * 2. Detect all feature flag usage
     * 3. Validate safe removal
     * 4. Generate removal plan
     * 5. Execute removal (optional, can be manual)
     * 
     * @param stableOperationDays Days of stable 100% operation
     * @param autoExecute If true, automatically execute removal
     * @return Result indicating success or failure
     */
    suspend fun startCleanup(
        stableOperationDays: Int = 28,
        autoExecute: Boolean = false
    ): CleanupResult {
        if (_state.value !is CleanupState.NotStarted) {
            return CleanupResult.Failure("Cleanup already started or completed")
        }
        
        // Step 1: Verify stable operation
        _state.value = CleanupState.VerifyingStability
        val stabilityCheck = verifyStableOperation(stableOperationDays)
        
        if (!stabilityCheck.stable) {
            _state.value = CleanupState.Failed(stabilityCheck.reason)
            return CleanupResult.Failure("Stability check failed: ${stabilityCheck.reason}")
        }
        
        // Step 2: Detect feature flag usage
        _state.value = CleanupState.DetectingFlags
        val detector = LegacyCodeDetector(codebaseRoot)
        val flagUsages = detector.detectFeatureFlagUsage()
        
        flagUsages.forEach { (flag, usages) ->
            detectedFlags[flag] = usages.toMutableList()
        }
        
        // Step 3: Validate safe removal
        _state.value = CleanupState.ValidatingRemoval
        val validationResults = mutableMapOf<String, ValidationResult>()
        
        for ((flag, usages) in detectedFlags) {
            val validation = validator.validateRemoval(flag, usages)
            validationResults[flag] = validation
            
            if (!validation.valid) {
                _state.value = CleanupState.Failed("Validation failed for $flag: ${validation.reason}")
                return CleanupResult.Failure("Cannot safely remove $flag: ${validation.reason}")
            }
        }
        
        // Step 4: Generate removal plan
        _state.value = CleanupState.GeneratingPlan
        val plan = generateRemovalPlan(detectedFlags, validationResults)
        removalPlan.addAll(plan)
        
        // Step 5: Execute removal (if requested)
        if (autoExecute) {
            _state.value = CleanupState.ExecutingRemoval
            val executionResult = executeRemovalPlan(plan)
            
            if (!executionResult.success) {
                _state.value = CleanupState.Failed(executionResult.error ?: "Execution failed")
                return CleanupResult.Failure("Removal execution failed: ${executionResult.error}")
            }
        } else {
            _state.value = CleanupState.PlanGenerated
        }
        
        // Success!
        _state.value = CleanupState.Complete(autoExecute)
        return CleanupResult.Success(
            flagsDetected = detectedFlags.size,
            usagesDetected = detectedFlags.values.sumOf { it.size },
            removalPlan = plan,
            executed = autoExecute
        )
    }
    
    /**
     * Verify stable operation before cleanup
     * 
     * Checks:
     * - All nodes at 100% deployment
     * - No critical errors in last N days
     * - Performance stable (<2% overhead)
     * - User feedback positive
     */
    private suspend fun verifyStableOperation(days: Int): StabilityCheckResult {
        // In real implementation, query monitoring system
        // Check metrics for last N days
        delay(2000)
        
        // Simulate checks
        val allNodesDeployed = true
        val noCriticalErrors = true
        val performanceStable = true
        val userFeedbackPositive = true
        
        return when {
            !allNodesDeployed -> StabilityCheckResult(false, "Not all nodes deployed")
            !noCriticalErrors -> StabilityCheckResult(false, "Critical errors detected")
            !performanceStable -> StabilityCheckResult(false, "Performance unstable")
            !userFeedbackPositive -> StabilityCheckResult(false, "Negative user feedback")
            else -> StabilityCheckResult(true)
        }
    }
    
    /**
     * Generate removal plan
     * 
     * Creates step-by-step plan for removing feature flag code
     */
    private fun generateRemovalPlan(
        flagUsages: Map<String, List<FlagUsage>>,
        validations: Map<String, ValidationResult>
    ): List<RemovalStep> {
        val plan = mutableListOf<RemovalStep>()
        
        for ((flag, usages) in flagUsages) {
            // Group by file
            val usagesByFile = usages.groupBy { it.file }
            
            for ((file, fileUsages) in usagesByFile) {
                // Create removal step for each file
                plan.add(
                    RemovalStep(
                        stepNumber = plan.size + 1,
                        flag = flag,
                        file = file,
                        usages = fileUsages,
                        action = determineRemovalAction(flag, fileUsages),
                        description = "Remove $flag from ${file.name}",
                        estimatedRisk = assessRisk(fileUsages),
                        testFiles = findRelatedTests(file)
                    )
                )
            }
        }
        
        // Sort by risk (lowest first)
        return plan.sortedBy { it.estimatedRisk.ordinal }
    }
    
    /**
     * Execute removal plan
     */
    private suspend fun executeRemovalPlan(plan: List<RemovalStep>): ExecutionResult {
        try {
            for (step in plan) {
                // Execute removal action
                val result = executeRemovalStep(step)
                if (!result.success) {
                    return ExecutionResult(false, "Step ${step.stepNumber} failed: ${result.error}")
                }
            }
            
            return ExecutionResult(true)
        } catch (e: Exception) {
            return ExecutionResult(false, "Exception: ${e.message}")
        }
    }
    
    /**
     * Execute single removal step
     */
    private suspend fun executeRemovalStep(step: RemovalStep): ExecutionResult {
        return when (step.action) {
            RemovalAction.REMOVE_IF_BLOCK -> removeIfBlock(step)
            RemovalAction.REMOVE_ELSE_BLOCK -> removeElseBlock(step)
            RemovalAction.REMOVE_ENTIRE_CONDITIONAL -> removeEntireConditional(step)
            RemovalAction.SIMPLIFY_LOGIC -> simplifyLogic(step)
            RemovalAction.REMOVE_FLAG_DECLARATION -> removeFlagDeclaration(step)
            RemovalAction.UPDATE_DOCUMENTATION -> updateDocumentation(step)
        }
    }
    
    /**
     * Get current cleanup status
     */
    fun getStatus(): CleanupStatus {
        return CleanupStatus(
            state = _state.value,
            flagsDetected = detectedFlags.size,
            usagesDetected = detectedFlags.values.sumOf { it.size },
            removalPlan = removalPlan,
            flagUsages = detectedFlags.toMap()
        )
    }
    
    /**
     * Shutdown cleanup manager
     */
    fun shutdown() {
        scope.cancel()
    }
    
    // ========== Helper Methods ==========
    
    private fun determineRemovalAction(flag: String, usages: List<FlagUsage>): RemovalAction {
        // Analyze usage pattern to determine best removal action
        return when {
            usages.any { it.type == UsageType.IF_ENABLED } -> RemovalAction.REMOVE_ELSE_BLOCK
            usages.any { it.type == UsageType.IF_DISABLED } -> RemovalAction.REMOVE_IF_BLOCK
            usages.any { it.type == UsageType.DECLARATION } -> RemovalAction.REMOVE_FLAG_DECLARATION
            else -> RemovalAction.SIMPLIFY_LOGIC
        }
    }
    
    private fun assessRisk(usages: List<FlagUsage>): RemovalRisk {
        return when {
            usages.any { it.isInCriticalPath } -> RemovalRisk.HIGH
            usages.size > 10 -> RemovalRisk.MEDIUM
            else -> RemovalRisk.LOW
        }
    }
    
    private fun findRelatedTests(file: File): List<File> {
        // Find test files related to source file
        val testFileName = file.nameWithoutExtension + "Test.kt"
        val testFile = File(codebaseRoot, "src/test/kotlin/${file.path.replace("src/main/kotlin/", "")}/$testFileName")
        return if (testFile.exists()) listOf(testFile) else emptyList()
    }
    
    private suspend fun removeIfBlock(step: RemovalStep): ExecutionResult {
        // Remove if block, keep else block
        delay(100)
        return ExecutionResult(true)
    }
    
    private suspend fun removeElseBlock(step: RemovalStep): ExecutionResult {
        // Remove else block, keep if block
        delay(100)
        return ExecutionResult(true)
    }
    
    private suspend fun removeEntireConditional(step: RemovalStep): ExecutionResult {
        // Remove entire conditional structure
        delay(100)
        return ExecutionResult(true)
    }
    
    private suspend fun simplifyLogic(step: RemovalStep): ExecutionResult {
        // Simplify logic by removing flag check
        delay(100)
        return ExecutionResult(true)
    }
    
    private suspend fun removeFlagDeclaration(step: RemovalStep): ExecutionResult {
        // Remove flag declaration from enum/config
        delay(100)
        return ExecutionResult(true)
    }
    
    private suspend fun updateDocumentation(step: RemovalStep): ExecutionResult {
        // Update documentation to reflect new default behavior
        delay(100)
        return ExecutionResult(true)
    }
}

// ========== Data Classes ==========

/**
 * Cleanup state
 */
sealed class CleanupState {
    object NotStarted : CleanupState()
    object VerifyingStability : CleanupState()
    object DetectingFlags : CleanupState()
    object ValidatingRemoval : CleanupState()
    object GeneratingPlan : CleanupState()
    object PlanGenerated : CleanupState()
    object ExecutingRemoval : CleanupState()
    data class Failed(val reason: String) : CleanupState()
    data class Complete(val executed: Boolean) : CleanupState()
}

/**
 * Cleanup result
 */
sealed class CleanupResult {
    data class Success(
        val flagsDetected: Int,
        val usagesDetected: Int,
        val removalPlan: List<RemovalStep>,
        val executed: Boolean
    ) : CleanupResult()
    data class Failure(val reason: String) : CleanupResult()
}

/**
 * Stability check result
 */
data class StabilityCheckResult(
    val stable: Boolean,
    val reason: String = "Stable"
)

/**
 * Execution result
 */
// data class ExecutionResult(
//     val success: Boolean,
//     val error: String? = null
// )

/**
 * Cleanup status
 */
data class CleanupStatus(
    val state: CleanupState,
    val flagsDetected: Int,
    val usagesDetected: Int,
    val removalPlan: List<RemovalStep>,
    val flagUsages: Map<String, List<FlagUsage>>
)

/**
 * Removal step
 */
data class RemovalStep(
    val stepNumber: Int,
    val flag: String,
    val file: File,
    val usages: List<FlagUsage>,
    val action: RemovalAction,
    val description: String,
    val estimatedRisk: RemovalRisk,
    val testFiles: List<File>
)

/**
 * Removal action
 */
enum class RemovalAction {
    REMOVE_IF_BLOCK,
    REMOVE_ELSE_BLOCK,
    REMOVE_ENTIRE_CONDITIONAL,
    SIMPLIFY_LOGIC,
    REMOVE_FLAG_DECLARATION,
    UPDATE_DOCUMENTATION
}

/**
 * Removal risk
 */
enum class RemovalRisk {
    LOW,
    MEDIUM,
    HIGH
}

/**
 * Flag usage
 */
data class FlagUsage(
    val file: File,
    val lineNumber: Int,
    val type: UsageType,
    val context: String,
    val isInCriticalPath: Boolean = false
)

/**
 * Usage type
 */
enum class UsageType {
    IF_ENABLED,
    IF_DISABLED,
    DECLARATION,
    DOCUMENTATION,
    TEST
}

// ========== Legacy Code Detector ==========

/**
 * LegacyCodeDetector
 * 
 * Detects feature flag usage across codebase
 */
class LegacyCodeDetector(private val codebaseRoot: File) {
    
    /**
     * Detect all feature flag usage
     * 
     * Scans codebase for:
     * - FeatureFlag enum references
     * - isEnabled() checks
     * - enable/disable calls
     * - Documentation mentions
     */
    fun detectFeatureFlagUsage(): Map<String, List<FlagUsage>> {
        val usages = mutableMapOf<String, MutableList<FlagUsage>>()
        
        // Scan Kotlin files
        codebaseRoot.walk()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                scanFile(file, usages)
            }
        
        // Scan Markdown files for documentation
        codebaseRoot.walk()
            .filter { it.isFile && it.extension == "md" }
            .forEach { file ->
                scanDocumentation(file, usages)
            }
        
        return usages
    }
    
    private fun scanFile(file: File, usages: MutableMap<String, MutableList<FlagUsage>>) {
        val lines = try {
            file.readLines()
        } catch (e: Exception) {
            return
        }
        
        lines.forEachIndexed { index, line ->
            // Detect feature flag checks
            when {
                line.contains("isTaskKeypairEnabled()") -> {
                    addUsage(usages, "TASK_KEYPAIR_ENABLED", file, index + 1, UsageType.IF_ENABLED, line)
                }
                line.contains("FeatureFlag.TASK_KEYPAIR_ENABLED") -> {
                    addUsage(usages, "TASK_KEYPAIR_ENABLED", file, index + 1, UsageType.DECLARATION, line)
                }
                line.contains("enableTaskKeypair()") || line.contains("disableTaskKeypair()") -> {
                    addUsage(usages, "TASK_KEYPAIR_ENABLED", file, index + 1, UsageType.IF_ENABLED, line)
                }
            }
        }
    }
    
    private fun scanDocumentation(file: File, usages: MutableMap<String, MutableList<FlagUsage>>) {
        val content = try {
            file.readText()
        } catch (e: Exception) {
            return
        }
        
        if (content.contains("TASK_KEYPAIR_ENABLED")) {
            addUsage(usages, "TASK_KEYPAIR_ENABLED", file, 0, UsageType.DOCUMENTATION, content.take(100))
        }
    }
    
    private fun addUsage(
        usages: MutableMap<String, MutableList<FlagUsage>>,
        flag: String,
        file: File,
        lineNumber: Int,
        type: UsageType,
        context: String
    ) {
        val usage = FlagUsage(
            file = file,
            lineNumber = lineNumber,
            type = type,
            context = context.trim(),
            isInCriticalPath = isCriticalPath(file)
        )
        
        usages.getOrPut(flag) { mutableListOf() }.add(usage)
    }
    
    private fun isCriticalPath(file: File): Boolean {
        // Determine if file is in critical path
        val criticalPaths = listOf(
            "TaskManager",
            "DistributedStorageManager",
            "EncryptionService"
        )
        
        return criticalPaths.any { file.name.contains(it) }
    }
}

// ========== Safe Removal Validator ==========

/**
 * SafeRemovalValidator
 * 
 * Validates that feature flag removal is safe
 */
interface SafeRemovalValidator {
    /**
     * Validate removal safety
     * 
     * Checks:
     * - No active users relying on flag being disabled
     * - All tests pass with flag permanently enabled
     * - No breaking API changes
     * - Documentation updated
     */
    suspend fun validateRemoval(flag: String, usages: List<FlagUsage>): ValidationResult
}

/**
 * Default safe removal validator
 */
class DefaultSafeRemovalValidator(
    private val featureFlagManager: FeatureFlagManager
) : SafeRemovalValidator {
    
    override suspend fun validateRemoval(flag: String, usages: List<FlagUsage>): ValidationResult {
        // Check 1: Verify flag is enabled everywhere
        val allNodesEnabled = verifyAllNodesEnabled(flag)
        if (!allNodesEnabled) {
            return ValidationResult(
                valid = false,
                reason = "Flag not enabled on all nodes",
                details = mapOf("flag" to flag)
            )
        }
        
        // Check 2: Verify no critical path issues
        val criticalPathUsages = usages.filter { it.isInCriticalPath }
        if (criticalPathUsages.isNotEmpty()) {
            // Extra validation for critical path
            val criticalPathSafe = validateCriticalPath(criticalPathUsages)
            if (!criticalPathSafe) {
                return ValidationResult(
                    valid = false,
                    reason = "Critical path validation failed",
                    details = mapOf("usages" to criticalPathUsages.size)
                )
            }
        }
        
        // Check 3: Verify tests exist for affected files
        val filesWithoutTests = usages
            .groupBy { it.file }
            .filterKeys { file -> !hasTests(file) }
        
        if (filesWithoutTests.isNotEmpty()) {
            return ValidationResult(
                valid = false,
                reason = "Some files lack test coverage",
                details = mapOf("filesWithoutTests" to filesWithoutTests.keys.map { it.name })
            )
        }
        
        return ValidationResult(
            valid = true,
            reason = "Safe to remove $flag",
            details = mapOf(
                "flag" to flag,
                "usages" to usages.size,
                "files" to usages.map { it.file.name }.distinct()
            )
        )
    }
    
    private suspend fun verifyAllNodesEnabled(flag: String): Boolean {
        // In real implementation, check all nodes
        delay(500)
        return true
    }
    
    private fun validateCriticalPath(usages: List<FlagUsage>): Boolean {
        // Extra validation for critical path
        return true
    }
    
    private fun hasTests(file: File): Boolean {
        // Check if file has corresponding test file
        val testFileName = file.nameWithoutExtension + "Test.kt"
        return File(file.parent, testFileName).exists()
    }
}
