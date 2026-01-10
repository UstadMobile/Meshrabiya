# Deprecated Storage Functionality - Execution Plan
**Date**: November 14, 2025  
**Status**: 🟡 Awaiting Approval  
**Estimated Effort**: 2-4 hours  
**Risk Level**: LOW (unused code)

---

## EXECUTION CHECKLIST

### Pre-Execution Verification ✅
- [x] Analysis complete (DEPRECATED_STORAGE_ANALYSIS.md)
- [ ] User approval received
- [ ] Backup current state (git commit)
- [ ] Build verification baseline established

### Phase 1: Comment Out Deprecated Functions
- [ ] 1.1 DistributedStorageAgent.kt (entire file)
- [ ] 1.2 ServiceLayerCoordinator.kt (partial - storage sections)
- [ ] 1.3 MeshrabiyaInterop.kt (partial - 2 conversion functions)
- [ ] 1.4 MeshNetworkInterface.kt (partial - 6 storage methods)
- [ ] 1.5 VirtualNode_MeshNetworkInterface.kt (partial - 6 storage stubs)
- [ ] 1.6 ResourceRequirements.kt (StorageOperation enum)
- [ ] 1.7 ServiceLayerTestInterface.kt (partial - storage test)

### Phase 2: Rename Deprecated Files
- [ ] 2.1 Rename DistributedStorageAgent.kt → DistributedStorageAgent.DEPRECATED.md
- [ ] 2.2 Convert to markdown documentation format

### Phase 3: Verification & Testing
- [ ] 3.1 Build verification (Meshrabiya module)
- [ ] 3.2 Test verification (unit tests)
- [ ] 3.3 Usage verification (grep for remaining references)
- [ ] 3.4 Update KNOWLEDGE-11142025.md

---

## DETAILED EXECUTION STEPS

### Step 1.1: DistributedStorageAgent.kt - Full Deprecation

**File**: `lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/DistributedStorageAgent.kt`  
**Lines**: 1-934 (entire file)  
**Action**: Add deprecation header, comment out all code

**Implementation**:
```kotlin
package com.ustadmobile.meshrabiya.service.compute

/**
 * ⚠️ DEPRECATED: DistributedStorageAgent - November 14, 2025 ⚠️
 * 
 * This file contains prototype storage code that was never completed and is superseded
 * by the canonical storage service.
 * 
 * ## Deprecation Details
 * - **Deprecated Date**: November 14, 2025
 * - **Reason**: Duplicate implementation, never integrated with VirtualNode
 * - **Replacement**: `com.ustadmobile.meshrabiya.storage.DistributedStorageManager`
 * - **Last Known Usage**: ServiceLayerCoordinator (test code only)
 * - **Target Removal**: Q1 2026
 * 
 * ## Why This Code Was Deprecated
 * 1. Wrong Architecture: Storage functionality should not be in /service/compute/ package
 * 2. Duplicate Implementation: DistributedStorageManager already provides all needed functionality
 * 3. Never Completed: All MeshNetworkInterface storage methods throw NotImplementedError
 * 4. Not Integrated: VirtualNode uses DistributedStorageManager, not DistributedStorageAgent
 * 5. Test/Prototype Code: Only used in ServiceLayerCoordinator (test infrastructure)
 * 
 * ## Migration Path
 * Replace all usage with:
 * ```kotlin
 * val storageManager = virtualNode.getDistributedStorageManager()
 * // Use DistributedStorageManager methods:
 * // - storeFile(), retrieveFile(), deleteFile()
 * // - Full PGP encryption, recipient management, replication
 * ```
 * 
 * ## Impact Assessment
 * - Build Impact: NONE (unused in production)
 * - Runtime Impact: NONE (no production code paths)
 * - Test Impact: MINIMAL (only ServiceLayerCoordinator affected)
 * 
 * ---
 * 
 * ORIGINAL IMPLEMENTATION BELOW (Commented Out)
 */

/*

[PASTE ENTIRE FILE CONTENTS HERE WITH // COMMENTING]

package com.ustadmobile.meshrabiya.service.compute

import kotlinx.coroutines.*
// [etc. - all 934 lines commented]

*/
```

**Verification**:
- File still exists (for historical reference)
- All code commented out
- Deprecation notice explains replacement

---

### Step 1.2: ServiceLayerCoordinator.kt - Partial Deprecation

**File**: `lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/ServiceLayerCoordinator.kt`  
**Lines**: Multiple sections (keep compute functionality)  
**Action**: Comment out storage-related sections only

**Section 1: Storage Agent Property (Line 55)**
```kotlin
/*
 * DEPRECATED: Storage agent property - November 14, 2025
 * Reason: Uses deprecated DistributedStorageAgent
 * Replacement: Get DistributedStorageManager via virtualNode.getDistributedStorageManager()
 */
// private val storageAgent by lazy { DistributedStorageAgent(meshrabiyaMeshAdapter) }
```

**Section 2: Active Storage Operations Map (Line 93)**
```kotlin
/*
 * DEPRECATED: Storage operations tracking - November 14, 2025
 */
// private val activeStorageOps = ConcurrentHashMap<String, StorageOperationStatus>()
```

**Section 3: StorageOperationStatus Data Class (Lines 115-121)**
```kotlin
/*
 * DEPRECATED: StorageOperationStatus data class - November 14, 2025
 */
// data class StorageOperationStatus(
//     val operationId: String,
//     val type: String, // "STORE", "RETRIEVE", "DELETE"
//     val fileId: String,
//     val status: String,
//     val progress: Float,
//     val startTime: Long
// )
```

**Section 4: ServiceStatistics - Remove Storage Fields**
```kotlin
data class ServiceStatistics(
    var computeTasksCompleted: Long = 0L,
    var computeTasksFailed: Long = 0L,
    var computeTasksCanceled: Long = 0L,
    // DEPRECATED: Storage statistics - November 14, 2025
    // var storageRequestsHandled: Long = 0L,
    // var storageErrorsEncountered: Long = 0L,
    var totalBytesProcessed: Long = 0L,
    var totalComputeTimeMs: Long = 0L,
    var meshContributionScore: Float = 0.0f,
    var serviceUptimeMs: Long = 0L
)
```

**Section 5: Storage Request Handler (Lines ~240-280)**
```kotlin
/*
 * DEPRECATED: handleStorageRequest() - November 14, 2025
 * Replacement: Use DistributedStorageManager.storeFile() directly
 */
// suspend fun handleStorageRequest(fileData: ByteArray, fileName: String, replicationFactor: Int = 3): String? {
//     [entire method body]
// }
```

**Section 6: Storage Retrieval Handler (Lines ~310-340)**
```kotlin
/*
 * DEPRECATED: handleRetrievalRequest() - November 14, 2025
 * Replacement: Use DistributedStorageManager.retrieveFile() directly
 */
// suspend fun handleRetrievalRequest(fileId: String): ByteArray? {
//     [entire method body]
// }
```

**Section 7: Storage Operations Counter (Lines ~404-406)**
```kotlin
/*
 * DEPRECATED: getActiveStorageOperationsCount() - November 14, 2025
 */
// fun getActiveStorageOperationsCount(): Int {
//     return activeStorageOps.size
// }
```

**Section 8: Test Storage Initialization (Lines ~773-794)**
```kotlin
// In initializeTestState():
/*
 * DEPRECATED: Storage test initialization - November 14, 2025
 */
// activeStorageOps["storage_op_1"] = StorageOperationStatus(...)
// activeStorageOps["storage_op_2"] = StorageOperationStatus(...)
// activeStorageOps["storage_op_3"] = StorageOperationStatus(...)
```

**Section 9: Storage Status Reporting (Lines ~397)**
```kotlin
// In getServiceStatus():
fun getServiceStatus(): Map<String, Any> {
    return mapOf(
        "isActive" to isActive(),
        "activeTasks" to activeComputeTasks.size,
        // DEPRECATED: "storageOperations" to activeStorageOps.values.toList(),
        "computeStats" to serviceStats,
        "uptimeMs" to (System.currentTimeMillis() - serviceStats.serviceUptimeMs)
    )
}
```

**Keep**: All compute-related functionality (computeService, task handling, etc.)

---

### Step 1.3: MeshrabiyaInterop.kt - Partial Deprecation

**File**: `lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/MeshrabiyaInterop.kt`  
**Lines**: Lines 71-118 (2 functions)  
**Action**: Comment out DistributedStorageAgent conversion functions

**Function 1: toAppFileMetadata() (Lines 71-91)**
```kotlin
/**
 * DEPRECATED: DistributedStorageAgent conversion - November 14, 2025
 * 
 * This conversion was for deprecated DistributedStorageAgent.
 * Use DistributedStorageManager.FileMetadata directly instead.
 */
// fun UMFileTransportDTO.toAppFileMetadata(): DistributedStorageAgent.FileMetadata {
//     val replicationFactor = when (this.replicationLevel) {
//         UMReplicationLevel.MINIMAL -> 1
//         UMReplicationLevel.STANDARD -> 3
//         UMReplicationLevel.HIGH -> 5
//         UMReplicationLevel.CRITICAL -> 7
//     }
//
//     return DistributedStorageAgent.FileMetadata(
//         fileId = this.fileId,
//         originalName = this.path.substringAfterLast('/'),
//         sizeBytes = 0L, // size not provided in transport DTO
//         checksumMD5 = this.checksum,
//         storedTimestamp = this.createdAt,
//         accessCount = 0L,
//         lastAccessTimestamp = this.lastAccessed,
//         replicationFactor = replicationFactor,
//         tags = this.meshReferences.toSet()
//     )
// }
```

**Function 2: toFileTransportDTO() (Lines 96-118)**
```kotlin
/**
 * DEPRECATED: DistributedStorageAgent conversion - November 14, 2025
 * 
 * This conversion was for deprecated DistributedStorageAgent.StorageRequest.
 * Use DistributedStorageManager methods directly instead.
 */
// fun DistributedStorageAgent.StorageRequest.toFileTransportDTO(): UMFileTransportDTO {
//     val replLevel = when (this.replicationFactor) {
//         1 -> UMReplicationLevel.MINIMAL
//         3 -> UMReplicationLevel.STANDARD
//         5 -> UMReplicationLevel.HIGH
//         7 -> UMReplicationLevel.CRITICAL
//         else -> UMReplicationLevel.STANDARD
//     }
//
//     val priority = when (this.priority) {
//         DistributedStorageAgent.StoragePriority.LOW -> UMSyncPriority.LOW
//         DistributedStorageAgent.StoragePriority.NORMAL -> UMSyncPriority.NORMAL
//         DistributedStorageAgent.StoragePriority.HIGH -> UMSyncPriority.HIGH
//         DistributedStorageAgent.StoragePriority.CRITICAL -> UMSyncPriority.CRITICAL
//     }
//
//     return UMFileTransportDTO(
//         path = this.fileName,
//         fileId = this.fileId,
//         replicationLevel = replLevel,
//         priority = priority,
//         createdAt = System.currentTimeMillis(),
//         lastAccessed = 0L,
//         meshReferences = this.tags.toList(),
//         checksum = ""
//     )
// }
```

**Keep**: All other conversion functions (toAppResourceRequirements, toMeshrabiya, etc.)

---

### Step 1.4: MeshNetworkInterface.kt - Partial Deprecation

**File**: `lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/MeshNetworkInterface.kt`  
**Lines**: 12-16 (5 storage methods)  
**Action**: Comment out storage operations, keep compute operations

```kotlin
package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.storage.DistributedStorageManager.FileTransportDTO
import com.ustadmobile.meshrabiya.vnet.MeshChunk
import com.ustadmobile.meshrabiya.mmcp.StorageCapabilities
import com.ustadmobile.meshrabiya.service.compute.executor.TaskExecutionRequest
import com.ustadmobile.meshrabiya.service.compute.executor.TaskExecutionResponse
// DEPRECATED: import com.ustadmobile.meshrabiya.service.compute.model.StorageOperation

interface MeshNetworkInterface {
    /*
     * DEPRECATED: Storage operations - November 14, 2025
     * 
     * These storage operations were never implemented (all implementations throw NotImplementedError).
     * They were part of the deprecated DistributedStorageAgent prototype.
     * 
     * Replacement: Use DistributedStorageManager directly via VirtualNode.getDistributedStorageManager()
     * 
     * All methods below throw NotImplementedError in VirtualNode_MeshNetworkInterface implementation.
     */
    // Storage operations
    // suspend fun sendStorageRequest(targetNodeId: String, fileInfo: FileTransportDTO, operation: StorageOperation)
    // suspend fun queryFileAvailability(path: String): List<String>
    // suspend fun requestFileFromNode(nodeId: String, path: String): ByteArray?
    // suspend fun getAvailableStorageNodes(): List<String>
    // suspend fun broadcastStorageAdvertisement(capabilities: StorageCapabilities)
    // suspend fun nodeHasSpace(nodeId: String, requiredSpace: Long): Boolean
    // suspend fun sendChunkToNode(nodeId: String, chunk: MeshChunk, chunkBytes: ByteArray)
    // suspend fun requestChunkFromNode(nodeId: String, chunkId: String): ByteArray?
    
    // Compute operations (KEEP - Phase 2 implementation)
    suspend fun executeRemoteTask(nodeId: String, request: TaskExecutionRequest): TaskExecutionResponse
}
```

---

### Step 1.5: VirtualNode_MeshNetworkInterface.kt - Partial Deprecation

**File**: `lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode_MeshNetworkInterface.kt`  
**Lines**: 27-68 (6 storage stubs + 2 chunk methods)  
**Action**: Comment out NotImplementedError stubs

```kotlin
/*
 * DEPRECATED STORAGE STUBS - November 14, 2025
 * 
 * These storage method stubs were never implemented and always throw NotImplementedError.
 * They were part of the deprecated DistributedStorageAgent prototype.
 * 
 * Replacement: Use DistributedStorageManager via virtualNode.getDistributedStorageManager()
 */

// override suspend fun sendStorageRequest(
//     targetNodeId: String,
//     fileInfo: FileTransportDTO,
//     operation: StorageOperation
// ) {
//     // Will delegate to virtualNode.getDistributedStorageManager().sendStorageRequest(...)
//     throw NotImplementedError("sendStorageRequest not yet implemented in VirtualNode_MeshNetworkInterface")
// }

// override suspend fun queryFileAvailability(path: String): List<String> {
//     // Will delegate to virtualNode.getDistributedStorageManager().queryFileAvailability(path)
//     throw NotImplementedError("queryFileAvailability not yet implemented in VirtualNode_MeshNetworkInterface")
// }

// override suspend fun requestFileFromNode(nodeId: String, path: String): ByteArray? {
//     // Will delegate to virtualNode.getDistributedStorageManager().requestFileFromNode(nodeId, path)
//     throw NotImplementedError("requestFileFromNode not yet implemented in VirtualNode_MeshNetworkInterface")
// }

// override suspend fun getAvailableStorageNodes(): List<String> {
//     // Will delegate to virtualNode.getDistributedStorageManager().getAvailableStorageNodes()
//     throw NotImplementedError("getAvailableStorageNodes not yet implemented in VirtualNode_MeshNetworkInterface")
// }

// override suspend fun broadcastStorageAdvertisement(capabilities: StorageCapabilities) {
//     // Will delegate to virtualNode.getDistributedStorageManager().broadcastStorageAdvertisement(capabilities)
//     throw NotImplementedError("broadcastStorageAdvertisement not yet implemented in VirtualNode_MeshNetworkInterface")
// }

// override suspend fun nodeHasSpace(nodeId: String, requiredSpace: Long): Boolean {
//     // Will delegate to virtualNode.getDistributedStorageManager().nodeHasSpace(nodeId, requiredSpace)
//     throw NotImplementedError("nodeHasSpace not yet implemented in VirtualNode_MeshNetworkInterface")
// }

// override suspend fun sendChunkToNode(nodeId: String, chunk: MeshChunk, chunkBytes: ByteArray) {
//     // Will delegate to virtualNode.getDistributedStorageManager().sendChunkToNode(...)
//     throw NotImplementedError("sendChunkToNode not yet implemented in VirtualNode_MeshNetworkInterface")
// }

// override suspend fun requestChunkFromNode(nodeId: String, chunkId: String): ByteArray? {
//     // Will delegate to virtualNode.getDistributedStorageManager().requestChunkFromNode(...)
//     throw NotImplementedError("requestChunkFromNode not yet implemented in VirtualNode_MeshNetworkInterface")
// }

// KEEP: executeRemoteTask() - Phase 2 implementation (lines 75-80)
override suspend fun executeRemoteTask(
    nodeId: String,
    request: TaskExecutionRequest
): TaskExecutionResponse {
    // ... keep implementation
}
```

---

### Step 1.6: ResourceRequirements.kt - Deprecate StorageOperation Enum

**File**: `lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/model/ResourceRequirements.kt`  
**Lines**: 44-50  
**Action**: Comment out enum with clear deprecation notice

```kotlin
/**
 * ⚠️ DEPRECATED: StorageOperation enum - November 14, 2025 ⚠️
 * 
 * This enum was part of the deprecated DistributedStorageAgent functionality and is no longer used.
 * 
 * ## Important Note
 * There is a DIFFERENT StorageOperation enum in SandboxStorageProxy.kt which is STILL ACTIVE:
 * - `com.ustadmobile.meshrabiya.service.security.SandboxStorageProxy.StorageOperation`
 * - Values: READ, WRITE (used for sandbox security in Phase 2 implementation)
 * - Do NOT confuse with this deprecated enum
 * 
 * ## Deprecation Details
 * - **Deprecated Date**: November 14, 2025
 * - **Reason**: Part of deprecated DistributedStorageAgent prototype
 * - **Replacement**: Use DistributedStorageManager methods directly (no enum needed)
 * - **Target Removal**: Q1 2026
 * 
 * ## Original Values (deprecated)
 * - STORE: Store file in distributed storage
 * - RETRIEVE: Retrieve file from storage
 * - DELETE: Delete file from storage
 * - REPLICATE: Replicate file to other nodes
 * - VERIFY: Verify file integrity
 */
// enum class StorageOperation {
//     STORE,
//     RETRIEVE,
//     DELETE,
//     REPLICATE,
//     VERIFY
// }
```

---

### Step 1.7: ServiceLayerTestInterface.kt - Partial Deprecation

**File**: `lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/ServiceLayerTestInterface.kt`  
**Action**: Comment out storage test method and remove from test suite

**Test Method (Lines 163-180)**
```kotlin
/*
 * DEPRECATED TEST: testBasicStorageOperation() - November 14, 2025
 * 
 * This test validates deprecated ServiceLayerCoordinator.handleStorageRequest().
 * Test removed as storage functionality is deprecated.
 */
// private suspend fun testBasicStorageOperation(): TestResult {
//     return try {
//         logDebug("Starting basic storage operation test...")
//         
//         val testData = "Test file data".toByteArray()
//         val fileId = coordinator.handleStorageRequest(testData, "test.txt", replicationFactor = 2)
//         
//         if (fileId == null) {
//             TestResult("basicStorageOp", false, "Storage request returned null")
//         } else {
//             logDebug("Storage request succeeded, fileId: $fileId")
//             TestResult("basicStorageOp", true, "Storage operation completed")
//         }
//     } catch (e: Exception) {
//         logError("Basic storage operation test failed", e)
//         TestResult("basicStorageOp", false, "Exception: ${e.message}")
//     }
// }
```

**Remove from Test Suite (Line 40)**
```kotlin
suspend fun runAllTests(): List<TestResult> {
    logDebug("ServiceLayerTestInterface: Running all tests...")
    val results = mutableListOf<TestResult>()
    
    results.add(testBasicComputeTask())
    // DEPRECATED: results.add(testBasicStorageOperation())  // Removed November 14, 2025
    results.add(testTaskCancellation())
    // ... other tests
}
```

---

## PHASE 2: RENAME FILES

### Step 2.1: Rename DistributedStorageAgent.kt

**Command**:
```bash
cd /Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute
mv DistributedStorageAgent.kt DistributedStorageAgent.DEPRECATED.md
```

**Timing**: After Phase 1 complete and build verified

---

### Step 2.2: Convert to Markdown Documentation

**Update File Format**:
```markdown
# Deprecated: DistributedStorageAgent

**Deprecated Date**: November 14, 2025  
**Package**: `com.ustadmobile.meshrabiya.service.compute`  
**Status**: ❌ Fully Deprecated  
**Target Removal**: Q1 2026

---

## Deprecation Summary

This file contained prototype storage code that was never completed or integrated with the production system.

### Reason for Deprecation
1. **Wrong Architecture**: Storage functionality should not be in `/service/compute/` package
2. **Duplicate Implementation**: `DistributedStorageManager` already provides all needed functionality
3. **Never Completed**: All `MeshNetworkInterface` storage methods throw `NotImplementedError`
4. **Not Integrated**: `VirtualNode` uses `DistributedStorageManager`, not `DistributedStorageAgent`
5. **Test/Prototype Only**: Only used in `ServiceLayerCoordinator` (test infrastructure)

### Replacement
Use: `com.ustadmobile.meshrabiya.storage.DistributedStorageManager`

```kotlin
// Old (deprecated):
val storageAgent = DistributedStorageAgent(meshNetwork)
storageAgent.handleStorageRequest(request)

// New (canonical):
val storageManager = virtualNode.getDistributedStorageManager()
storageManager.storeFile(file, accessScope, owner, recipients)
```

### Impact Assessment
- **Build Impact**: NONE (unused in production)
- **Runtime Impact**: NONE (no production code paths)
- **Test Impact**: MINIMAL (only `ServiceLayerCoordinator` affected)

### Dependencies Deprecated Alongside
- `MeshNetworkInterface` storage operations (6 methods)
- `VirtualNode_MeshNetworkInterface` storage stubs (6 stubs)
- `ServiceLayerCoordinator` storage handling (~150 lines)
- `MeshrabiyaInterop` storage conversions (2 functions)
- `StorageOperation` enum in `ResourceRequirements.kt`

---

## Original Implementation (Archived)

```kotlin
package com.ustadmobile.meshrabiya.service.compute

// [ORIGINAL FILE CONTENTS]
// Lines 1-934 preserved for historical reference

import kotlinx.coroutines.*
// ... [rest of commented code]
```

---

**End of Deprecated File Documentation**
```

---

## PHASE 3: VERIFICATION

### Step 3.1: Build Verification

**Command**:
```bash
cd /Users/dreadstar/workspace/orbot-android
: > build_output.log && \
export JAVA_HOME=$(/usr/libexec/java_home -v 21) && \
./gradlew :Meshrabiya:lib-meshrabiya:compileDebugKotlin --console=plain 2>&1 | tee build_output.log

# Check for errors
grep "error:" build_output.log | wc -l
# Expected: 0 errors
```

**Success Criteria**:
- Build completes without errors
- No new compilation errors introduced
- All Phase 1-10 files still compile

---

### Step 3.2: Test Verification

**Command**:
```bash
: > test_output.log && \
export JAVA_HOME=$(/usr/libexec/java_home -v 21) && \
./gradlew :Meshrabiya:lib-meshrabiya:testDebugUnitTest --console=plain 2>&1 | tee test_output.log

# Check results
grep "BUILD SUCCESSFUL" test_output.log
# Expected: BUILD SUCCESSFUL
```

**Success Criteria**:
- All tests pass
- No test failures due to deprecated code removal
- ServiceLayerCoordinator tests pass (compute functionality intact)

---

### Step 3.3: Usage Verification

**Search for Remaining References**:
```bash
cd /Users/dreadstar/workspace/orbot-android/Meshrabiya

# Check for DistributedStorageAgent usage
grep -r "DistributedStorageAgent" lib-meshrabiya/src/main/java/ --include="*.kt" | grep -v "DEPRECATED" | grep -v "^//"
# Expected: No results (only deprecated comments)

# Check for sendStorageRequest usage
grep -r "sendStorageRequest" lib-meshrabiya/src/main/java/ --include="*.kt" | grep -v "DEPRECATED" | grep -v "^//"
# Expected: No results

# Check for StorageOperation.STORE usage (deprecated enum)
grep -r "StorageOperation\.STORE\|StorageOperation\.REPLICATE\|StorageOperation\.VERIFY" lib-meshrabiya/src/main/java/ --include="*.kt" | grep -v "DEPRECATED" | grep -v "^//"
# Expected: No results (only SandboxStorageProxy.StorageOperation.READ/WRITE remains)

# Verify SandboxStorageProxy.StorageOperation still active
grep -r "SandboxStorageProxy\.StorageOperation" lib-meshrabiya/src/main/java/ --include="*.kt"
# Expected: TaskManager.kt (Phase 2 implementation) - SHOULD EXIST
```

**Success Criteria**:
- No active references to deprecated storage code
- SandboxStorageProxy.StorageOperation still used in TaskManager
- Only commented-out code and deprecation notices remain

---

### Step 3.4: Update Knowledge Documentation

**File**: `/Users/dreadstar/workspace/orbot-android/KNOWLEDGE-11142025.md`

**Add Section 21**:
```markdown
## 21. DEPRECATED STORAGE FUNCTIONALITY CLEANUP

### 21.1 Analysis Completed

**Date**: November 14, 2025  
**Analysis Document**: `Meshrabiya/DEPRECATED_STORAGE_ANALYSIS.md`

**Findings**:
- Identified 1,200 lines of deprecated storage code across 7 files
- All deprecated code unused in production (prototype/test code only)
- Canonical storage service: `DistributedStorageManager` (755 lines, fully functional)
- Deprecated storage service: `DistributedStorageAgent` (934 lines, never completed)

**Root Cause**:
- Prototype `DistributedStorageAgent` was created in `/service/compute/` package
- Never integrated with VirtualNode (which uses canonical DistributedStorageManager)
- All MeshNetworkInterface storage methods throw NotImplementedError
- Only used by ServiceLayerCoordinator (test code)

### 21.2 Deprecation Executed

**Date**: [EXECUTION DATE]  
**Execution Plan**: `Meshrabiya/DEPRECATION_EXECUTION_PLAN.md`

**Phase 1: Comment Out Deprecated Functions**
- ✅ DistributedStorageAgent.kt - Entire file commented (934 lines)
- ✅ ServiceLayerCoordinator.kt - Storage sections commented (~150 lines)
- ✅ MeshrabiyaInterop.kt - 2 storage conversion functions commented (~45 lines)
- ✅ MeshNetworkInterface.kt - 6 storage methods commented
- ✅ VirtualNode_MeshNetworkInterface.kt - 6 storage stubs commented
- ✅ ResourceRequirements.kt - StorageOperation enum commented
- ✅ ServiceLayerTestInterface.kt - Storage test method commented

**Phase 2: Rename Deprecated Files**
- ✅ DistributedStorageAgent.kt → DistributedStorageAgent.DEPRECATED.md

**Phase 3: Verification**
- ✅ Build verification: 0 errors
- ✅ Test verification: All tests pass
- ✅ Usage verification: No active references to deprecated code
- ✅ SandboxStorageProxy.StorageOperation still active (TaskManager Phase 2 code)

### 21.3 Impact Assessment

**Build Impact**: ✅ NONE
- All deprecated code was unused in production
- Build completes successfully after deprecation

**Runtime Impact**: ✅ NONE
- VirtualNode already uses DistributedStorageManager
- Phase 1-10 implementation unaffected

**Test Impact**: ✅ MINIMAL
- Only ServiceLayerTestInterface.testBasicStorageOperation() removed
- All other tests pass

**Code Reduction**:
- ~1,200 lines deprecated/commented
- 1 file renamed to .DEPRECATED.md
- Technical debt reduced

### 21.4 Remaining Active Storage Code

**Canonical Storage Service** (Keep):
- `storage/DistributedStorageManager.kt` (755 lines) - Production storage service
- Used by: VirtualNode, MeshrabiyaApiImpl, TaskManager (Phase 2)
- Integrations: MeshNetworkInterface, MeshGossipService, PGP encryption

**Sandbox Security** (Keep):
- `service/security/SandboxStorageProxy.kt` - StorageOperation enum (READ, WRITE)
- Used by: TaskManager (Phase 2) for sandbox security
- Different from deprecated StorageOperation (STORE, RETRIEVE, DELETE, REPLICATE, VERIFY)

### 21.5 Migration Guidance

**Before (Deprecated)**:
```kotlin
val storageAgent = DistributedStorageAgent(meshNetwork)
val result = storageAgent.handleStorageRequest(request)
```

**After (Canonical)**:
```kotlin
val storageManager = virtualNode.getDistributedStorageManager()
val result = storageManager.storeFile(
    file = file,
    accessScope = AccessScope.SHARED,
    owner = taskRequesterPublicKey,
    recipients = listOf(
        RecipientEntry(publicKey, RecipientType.USER)
    )
)
```

### 21.6 Target Removal Date

**Grace Period**: 2 months (November 2025 - January 2026)  
**Final Removal**: Q1 2026  
**Action**: Complete deletion of deprecated code after grace period

---
```

---

## ROLLBACK PLAN

In case of unexpected issues:

### Rollback Step 1: Revert Git Commit
```bash
git log --oneline -5
# Find commit before deprecation
git revert <commit-hash>
```

### Rollback Step 2: Manual Uncommenting
If partial rollback needed:
1. Uncomment specific sections in affected files
2. Restore imports
3. Run build verification
4. Run test verification

### Rollback Triggers
- Build failures (compile errors)
- Test failures (>5% test regression)
- Production code paths affected
- Phase 1-10 implementation broken

---

## POST-EXECUTION CHECKLIST

### Immediate (Day 1):
- [ ] All Phase 1-3 steps complete
- [ ] Build verification passed
- [ ] Test verification passed
- [ ] Usage verification passed
- [ ] KNOWLEDGE-11142025.md updated
- [ ] Git commit with deprecation changes

### Short-term (Week 1):
- [ ] Monitor for any issues reported
- [ ] Update external documentation (if any)
- [ ] Notify team of deprecation

### Long-term (Q1 2026):
- [ ] Complete removal of deprecated code
- [ ] Delete commented-out code
- [ ] Archive .DEPRECATED.md files to docs/deprecated/
- [ ] Update architecture documentation

---

## ESTIMATED TIMELINE

| Phase | Estimated Time | Dependencies |
|-------|---------------|--------------|
| **Phase 1**: Comment Out Functions | 1.5-2 hours | User approval |
| **Phase 2**: Rename Files | 15 minutes | Phase 1 complete |
| **Phase 3**: Verification | 30-45 minutes | Phases 1-2 complete |
| **Documentation**: Update KNOWLEDGE | 30 minutes | Phase 3 complete |
| **Total** | **2.5-4 hours** | - |

---

## SUCCESS CRITERIA

✅ **Phase 1 Success**:
- All deprecated functions commented out
- Deprecation notices in place
- No compilation errors

✅ **Phase 2 Success**:
- DistributedStorageAgent.kt renamed to .DEPRECATED.md
- File converted to markdown documentation

✅ **Phase 3 Success**:
- Build passes (0 errors)
- All tests pass
- No active references to deprecated code
- KNOWLEDGE doc updated

✅ **Overall Success**:
- ~1,200 lines of deprecated code cleaned up
- Technical debt reduced
- No production impact
- Clear migration path documented

---

**Plan Status**: 🟡 Ready for Execution (Awaiting Approval)  
**Risk Level**: 🟢 LOW  
**Estimated Completion**: 2-4 hours after approval

