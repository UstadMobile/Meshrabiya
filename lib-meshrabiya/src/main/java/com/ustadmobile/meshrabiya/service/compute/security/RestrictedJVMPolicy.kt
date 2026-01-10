package com.ustadmobile.meshrabiya.service.compute.security

import java.io.File
import java.security.AllPermission
import java.security.CodeSource
import java.security.Permission
import java.security.PermissionCollection
import java.security.Permissions
import java.security.Policy
import java.io.FilePermission
import java.lang.RuntimePermission
import java.net.SocketPermission
import java.util.PropertyPermission

/**
 * Thread-local security policy for isolated JVM task execution.
 *
 * Enforces filesystem restrictions on a per-task basis using ThreadLocal workspace context.
 * Each executor thread has its own workspace directory, preventing cross-task filesystem access.
 *
 * **Granted Permissions:**
 * - FilePermission: Read/write within thread's assigned workspace directory only
 * - RuntimePermission("accessDeclaredMembers"): Required for reflection APIs
 * - RuntimePermission("createClassLoader"): Required for URLClassLoader
 *
 * **Denied Permissions:**
 * - SocketPermission: All network access blocked
 * - RuntimePermission("loadLibrary.*"): Native code execution blocked
 * - RuntimePermission("setSecurityManager"): Cannot bypass security
 * - PropertyPermission: System property writes blocked
 * - FilePermission: Any paths outside workspace directory blocked
 *
 * **Thread Safety:**
 * Uses ThreadLocal<File> to isolate workspace context per execution thread.
 * Safe for concurrent execution across multiple IO dispatcher threads.
 *
 * **Usage:**
 * ```kotlin
 * val originalPolicy = Policy.getPolicy()
 * val originalManager = System.getSecurityManager()
 * try {
 *     RestrictedJVMPolicy.setWorkspaceForCurrentThread(workspaceDir)
 *     Policy.setPolicy(RestrictedJVMPolicy)
 *     System.setSecurityManager(SecurityManager())
 *     // Execute untrusted code
 * } finally {
 *     System.setSecurityManager(originalManager)
 *     Policy.setPolicy(originalPolicy)
 *     RestrictedJVMPolicy.clearWorkspaceForCurrentThread()
 * }
 * ```
 *
 * @see JVMExecutor.execute
 */
object RestrictedJVMPolicy : Policy() {
    
    /**
     * Thread-local workspace directory for current task execution.
     * Null when no task is executing on this thread.
     */
    private val workspaceDirectory = ThreadLocal<File?>()
    
    /**
     * Sets the workspace directory for the current thread.
     * Must be called before installing SecurityManager.
     *
     * @param workspace Absolute path to task's workspace directory
     */
    fun setWorkspaceForCurrentThread(workspace: File) {
        workspaceDirectory.set(workspace)
    }
    
    /**
     * Clears the workspace directory for the current thread.
     * Must be called after removing SecurityManager.
     */
    fun clearWorkspaceForCurrentThread() {
        workspaceDirectory.remove()
    }
    
    /**
     * Returns the workspace directory for the current thread.
     * Null if no task is executing.
     */
    private fun getCurrentWorkspace(): File? = workspaceDirectory.get()
    
    /**
     * Determines permissions for code executing in the current thread.
     *
     * **CRITICAL:** All permission checks route through this method when SecurityManager is active.
     * This method is called by SecurityManager.checkPermission() for every protected operation.
     *
     * @param codesource Source of the code (typically null for dynamically loaded classes)
     * @return PermissionCollection with granted permissions for this thread's workspace
     */
    override fun getPermissions(codesource: CodeSource?): PermissionCollection {
        val workspace = getCurrentWorkspace()
        
        // If no workspace set (not in task context), deny everything except basic reflection
        if (workspace == null) {
            return Permissions().apply {
                add(RuntimePermission("accessDeclaredMembers"))
            }
        }
        
        return Permissions().apply {
            // GRANT: Filesystem access within workspace directory
            // Pattern: workspace/- matches all files/subdirectories recursively
            add(FilePermission("${workspace.absolutePath}${File.separator}-", "read,write,delete"))
            
            // GRANT: Reflection APIs (required for Class.forName, getDeclaredMethods, etc.)
            add(RuntimePermission("accessDeclaredMembers"))
            
            // GRANT: ClassLoader creation (required for URLClassLoader in executor)
            add(RuntimePermission("createClassLoader"))
            
            // DENY: Network access (SocketPermission not added = denied by default)
            // DENY: Native library loading (RuntimePermission("loadLibrary.*") not added)
            // DENY: SecurityManager bypass (RuntimePermission("setSecurityManager") not added)
            // DENY: System property writes (PropertyPermission not added)
            // DENY: File access outside workspace (no other FilePermission added)
        }
    }
    
    /**
     * Determines permissions for a specific Protection Domain.
     * Routes to getPermissions(CodeSource) for consistent behavior.
     */
    override fun getPermissions(domain: java.security.ProtectionDomain?): PermissionCollection {
        return getPermissions(domain?.codeSource)
    }
    
    /**
     * Fast-path permission check.
     * Always returns false to force full permission evaluation via getPermissions().
     *
     * This ensures every permission check goes through getPermissions() where
     * thread-local workspace context is checked.
     */
    override fun implies(domain: java.security.ProtectionDomain?, permission: Permission?): Boolean {
        // Force all checks through getPermissions() to ensure thread-local context
        return false
    }
}
