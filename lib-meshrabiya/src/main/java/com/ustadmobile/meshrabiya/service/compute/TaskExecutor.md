package com.ustadmobile.meshrabiya.service.compute

/**
 * Interface for task execution runtimes.
 * Implementations handle execution for specific runtime types (JVM, JS, ML).
 * 
 * Executors are responsible for:
 * - Validating code bundles
 * - Executing tasks in isolated containers
 * - Writing output files to sandbox outputs/ directory
 * - Returning execution results
 */
interface TaskExecutor {
    /**
     * Execute a task in an isolated container.
     *
     * @param task Complete task object with execution context and sandbox info
     * @param inputFiles Map of filename -> file contents for task inputs
     * @param containerId Unique identifier for the execution container
     * @return ExecutionResult with success status, output data, and error info
     */
    suspend fun execute(
        task: Task,
        inputFiles: Map<String, ByteArray>,
        containerId: String
    ): ExecutionResult

    /**
     * Validate that the code bundle is well-formed for this runtime.
     *
     * @param codeBundle Binary code to validate
     * @return true if valid, false otherwise
     */
    fun validateCodeBundle(codeBundle: ByteArray): Boolean

    /**
     * Get the runtime type this executor supports.
     *
     * @return RuntimeType enum value
     */
    fun getSupportedRuntime(): RuntimeType
}
