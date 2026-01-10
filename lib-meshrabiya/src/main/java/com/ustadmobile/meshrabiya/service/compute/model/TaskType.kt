package com.ustadmobile.meshrabiya.service.compute.model

import kotlinx.serialization.Serializable

/**
 * TASK TYPES (Execution Engines)
 * 
 * Defines which runtime is required to execute the task.
 * Each type requires different executor implementation and sandbox configuration.
 * 
 * Ref: TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN.md Section 1.3
 */
@Serializable
enum class TaskType {
    /**
     * Python scripts (requires Chaquopy runtime)
     * - Executor: PythonExecutor
     * - Sandbox: Python-specific syscall whitelist
     * - Bundle format: .py files or Python wheel
     */
    PYTHON,

    /**
     * Compiled Java bytecode (requires JVM)
     * - Executor: JVMExecutor
     * - Sandbox: JVM SecurityManager
     * - Bundle format: .class files or JAR
     */
    JAVA,

    /**
     * JVM languages (Kotlin, Scala, Groovy)
     * - Executor: JVMExecutor (same as JAVA)
     * - Sandbox: JVM SecurityManager
     * - Bundle format: .class files or JAR
     */
    JVM,

    /**
     * JavaScript code (requires Node.js or J2V8)
     * - Executor: JSExecutor
     * - Sandbox: JS-specific syscall whitelist
     * - Bundle format: .js files or npm package
     */
    JAVASCRIPT,

    /**
     * Native ML models (TFLite, ML Kit)
     * - Executor: MLNativeExecutor
     * - Sandbox: Native code restrictions
     * - Bundle format: .tflite, .tflite.json, ML Kit model files
     */
    ML_NATIVE,

    /**
     * Multi-step workflow orchestration
     * - Executor: WorkflowExecutor
     * - Sandbox: Workflow-specific (orchestrates other tasks)
     * - Bundle format: JSON/YAML workflow definition
     */
    WORKFLOW;

    /**
     * Get required runtime for this task type
     */
    fun getRequiredRuntime(): RuntimeType {
        return when (this) {
            PYTHON -> RuntimeType.PYTHON
            JAVA, JVM -> RuntimeType.JVM
            JAVASCRIPT -> RuntimeType.NODEJS
            ML_NATIVE -> RuntimeType.NATIVE
            WORKFLOW -> RuntimeType.JVM // Workflow orchestrator is Kotlin
        }
    }
}

/**
 * RUNTIME TYPES
 * 
 * Available runtimes on compute nodes
 */
@Serializable
enum class RuntimeType {
    /** Always available (app is JVM-based) */
    JVM,
    
    /** NDK-compiled native code */
    NATIVE,
    
    /** Chaquopy (user-installed) */
    PYTHON,
    
    /** J2V8 (user-installed) */
    NODEJS,
    
    /** Cross-compiled to native (future) */
    RUST,
    
    /** Cross-compiled to native (future) */
    GO,
    
    /** WebAssembly runtime (future) */
    WASM
}
