package com.ustadmobile.meshrabiya.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import com.ustadmobile.meshrabiya.storage.DropFolderItem
import java.lang.IllegalStateException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.min
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.storage.RecipientEntry
import java.util.concurrent.ConcurrentHashMap
import com.ustadmobile.meshrabiya.storage.DistributedStorageManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
/**
 * Section 7: Drop Folder Service
 * 
 * Monitors a designated drop folder for new files and automatically uploads them to the mesh network.
 * 
 * Features:
 * - FileObserver monitoring for CREATE, MODIFY, CLOSE_WRITE, DELETE, MOVED_TO, MOVED_FROM events
 * - Auto-upload on CLOSE_WRITE (file write completed)
 * - Shared subfolder exception (files in drop/shared/ not re-uploaded)
 * - Auto-generated FileMetadata from file properties
 * - Duplicate prevention
 * - Error handling with retry logic
 * - Foreground service for Android O+
 * 
 * Drop Folder Structure:
 * ```
 * MeshrabiyaFiles/
 *   drop/              <- Monitored folder
 *     file1.txt        <- AUTO-UPLOAD
 *     file2.jpg        <- AUTO-UPLOAD
 *     shared/          <- Exception folder
 *       from_node1.txt <- NO AUTO-UPLOAD (downloaded from other nodes)
 *       from_node2.pdf <- NO AUTO-UPLOAD
 * ```
 */
class MeshDropFolderService : Service() {
    
    private lateinit var dropFolder: File
    private var fileObserver: FileObserver? = null
    private val processedFiles = mutableSetOf<String>()
    private val uploadQueue = ConcurrentLinkedQueue<File>()
    private val uploadExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val allItems = ConcurrentHashMap<String, DropFolderItem>()
    private val distributedStorageManager = DistributedStorageManager.getInstance()

    
    companion object {
        private const val TAG = "MeshDropFolderService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "mesh_drop_folder"
        private const val MAX_FILE_SIZE = 100 * 1024 * 1024L  // 100 MB
        private const val RETRY_DELAY_NETWORK_ERROR = 30_000L  // 30 seconds
        private const val RETRY_DELAY_SERVICE_ERROR = 5_000L   // 5 seconds
        private const val UPLOAD_RATE_LIMIT_MS = 1000L         // 1 second between uploads
        
        // FileObserver event mask
        private const val ALL_EVENTS = FileObserver.CREATE or 
                                      FileObserver.MODIFY or 
                                      FileObserver.CLOSE_WRITE or 
                                      FileObserver.DELETE or 
                                      FileObserver.MOVED_TO or 
                                      FileObserver.MOVED_FROM
    }

    // override fun onBind(intent: Intent?): IBinder? {
    //     return null
    // }

    override fun onCreate() {
        super.onCreate()
        
        // Start as foreground service for Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)
        }
        
        // Initialize drop folder
        dropFolder = File(getExternalFilesDir(null), "MeshrabiyaFiles/drop")
        if (!dropFolder.exists()) {
            dropFolder.mkdirs()
            Log.d(TAG, "Created drop folder: ${dropFolder.absolutePath}")
        }

        initializeAllItems()
        // Create FileObserver
        initializeFileObserver()
        
        Log.i(TAG, "MeshDropFolderService started, monitoring: ${dropFolder.absolutePath}")
    }

    private fun initializeAllItems() {
        allItems.clear()
        scanDropFolder(dropFolder, "")
    }

    private fun scanDropFolder(folder: File, parentRelativePath: String) {
        folder.listFiles()?.forEach { file ->
            val relativePath = if (parentRelativePath.isEmpty()) file.name else "$parentRelativePath/${file.name}"
            val parentItem = allItems[parentRelativePath]
            val item = DropFolderItem(
                itemRelativePath = relativePath,
                isFolder = file.isDirectory,
                parent = parentItem,
                children = emptyList()
            )
            allItems[relativePath] = item
            parentItem?.let {
                val updatedChildren = it.children + item
                allItems[parentRelativePath] = it.copy(children = updatedChildren)
            }
            if (file.isDirectory) {
                scanDropFolder(file, relativePath)
            }
        }
    }
    
    private fun initializeFileObserver() {
        fileObserver = object : FileObserver(dropFolder.absolutePath, ALL_EVENTS) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                
                when (event and ALL_EVENTS) {
                    FileObserver.CLOSE_WRITE -> handleFileCompleted(path)
                    FileObserver.CREATE -> handleFileCreated(path)
                    FileObserver.MODIFY -> handleFileModified(path)
                    FileObserver.DELETE -> handleFileDeleted(path)
                    FileObserver.MOVED_TO -> handleFileMovedIn(path)
                    FileObserver.MOVED_FROM -> handleFileMovedOut(path)
                }
            }
        }
        
        fileObserver?.startWatching()
        Log.d(TAG, "FileObserver started")
    }
    
    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mesh Drop Folder Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors drop folder for automatic mesh file uploads"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mesh Drop Folder Active")
            .setContentText("Monitoring for new files to upload")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    // ========== Event Handlers ==========
    
    private fun handleFileCreated(path: String) {
        Log.d(TAG, "File created in drop folder: $path")
        val file = File(dropFolder, path)
        if (!isInSharedSubfolder(file)) {
            val item = DropFolderItem(
                itemRelativePath = path,
                isFolder = file.isDirectory,
                parent = findParentDropFolderItemByPath(path)
                // parent = ... // find parent DropFolderItem
            )
            allItems[path] = item
            item.parent?.let {
                val updatedChildren = it.children + item
                allItems[it.itemRelativePath] = it.copy(children = updatedChildren)
            }
            MeshrabiyaApiImpl.getInstance().notifyDropFolderUpdate(listOf(item))
        }
        // Don't upload yet - wait for CLOSE_WRITE
    }
    
    private fun handleFileModified(path: String) {
        Log.d(TAG, "File modified in drop folder: $path")
        // will have to trigger mesh delete of old file  and Remove from processed set to allow re-upload after modification complete 
        val file = File(dropFolder, path)
        processedFiles.remove(file.absolutePath)
    }
    
    private fun handleFileCompleted(path: String) {
        val file = File(dropFolder, path)
        
        // Validate file
        if (!file.exists() || !file.isFile || file.length() == 0L) {
            Log.d(TAG, "Skipping invalid file: $path")
            return
        }
        
        // Skip if already processed
        if (processedFiles.contains(file.absolutePath)) {
            Log.d(TAG, "File already processed: $path")
            return
        }
        
        // Skip "shared" subfolder (User Clarification 5)
        if (isInSharedSubfolder(file)) {
            Log.d(TAG, "Skipping file in shared subfolder: $path")
            return
        }
        
        // Check file size limit
        // if (file.length() > MAX_FILE_SIZE) {
        //     Log.w(TAG, "File too large for auto-upload: $path (${file.length()} bytes)")
        //     showNotification("File too large", "Please upload ${file.name} manually")
        //     return
        // }
        
        Log.i(TAG, "File ready for upload: $path (${file.length()} bytes)")
        
        // Add to upload queue
        uploadQueue.offer(file)
        
        // Process queue on background thread
        uploadExecutor.execute {
            processUploadQueue()
        }
    }

    fun findParentDropFolderItem(
        item: DropFolderItem,
    ): DropFolderItem? {
        val parentPath = File(item.itemRelativePath).parent ?: return null
        return allItems[parentPath]
    }

    fun findParentDropFolderItemByPath(itemPath: String): DropFolderItem? {
        val itemFile = File(itemPath)
        val dropFolderPath = dropFolder.absolutePath
        val absItemPath = itemFile.absolutePath
        if (!absItemPath.startsWith(dropFolderPath)) {
            return null
        }
        val parentPath = itemFile.parent ?: return null
        // Compute relative path for lookup in allItems
        val relativeParentPath = if (parentPath.startsWith(dropFolderPath)) {
            parentPath.removePrefix(dropFolderPath).trimStart(File.separatorChar)
        } else {
            parentPath
        }
        return allItems[relativeParentPath]
    }
    
    private fun handleFileDeleted(path: String) {
        // val file = File(dropFolder, path)
        // processedFiles.remove(file.absolutePath)
        // Log.d(TAG, "File deleted from drop folder: $path")
        // val item = DropFolderItem(
        //     item = file,
        //     isFolder = file.isDirectory,
        //     // parent = ... // find parent DropFolderItem
        // )
        // MeshrabiyaApiImpl.getInstance().notifyDropFolderUpdate(listOf(item))
        val parentRelativePath = File(path).parent ?: ""
        val parentItem = allItems[parentRelativePath]
        val item = allItems.remove(path)
        parentItem?.let {
            val updatedChildren = it.children.filter { child -> child.itemRelativePath != path }
            allItems[parentRelativePath] = it.copy(children = updatedChildren)
        }
        item?.let { MeshrabiyaApiImpl.getInstance().notifyDropFolderUpdate(listOf(it)) }
    }
    
    private fun handleFileMovedIn(path: String) {
        Log.d(TAG, "File moved into drop folder: $path")
        val file = File(dropFolder, path)
        if (!isInSharedSubfolder(file)) {
            val item = DropFolderItem(
                itemRelativePath = path,
                isFolder = file.isDirectory,
                parent = findParentDropFolderItemByPath(path)
            )
            MeshrabiyaApiImpl.getInstance().notifyDropFolderUpdate(listOf(item))
        }
        // Will be uploaded on next CLOSE_WRITE
    }
    
    private fun handleFileMovedOut(path: String) {
        val file = File(dropFolder, path)
        processedFiles.remove(file.absolutePath)
        Log.d(TAG, "File moved out of drop folder: $path")
        val item = DropFolderItem(
            itemRelativePath = path,
            isFolder = file.isDirectory,
            parent = findParentDropFolderItemByPath(path)
        )
        MeshrabiyaApiImpl.getInstance().notifyDropFolderUpdate(listOf(item))
    }
    
    // ========== Upload Logic ==========
    
    private fun processUploadQueue() {
        while (uploadQueue.isNotEmpty()) {
            val file = uploadQueue.poll() ?: break
            
            // Validate and upload
            if (file.exists() && !isInSharedSubfolder(file) && !processedFiles.contains(file.absolutePath)) {
                uploadToMesh(file)
            }
            
            // Rate limiting: max 1 upload per second
            Thread.sleep(UPLOAD_RATE_LIMIT_MS)
        }
    }

    fun getInheritedRecipientsForFile(file: File, recursive: Boolean): List<RecipientEntry> {
        val recipients = mutableSetOf<RecipientEntry>()
        var currentFolder = file.parentFile
        val dropFolderPath = MeshrabiyaConstants.getDropFolderPath()
        while (currentFolder != null) {
            recipients.addAll(getRecipientsForFolder(currentFolder))
            if (!recursive || currentFolder.absolutePath == dropFolderPath) {
                break
            }
            currentFolder = currentFolder.parentFile
        }
        return recipients.toList()
    }
    
    fun getRecipientsForFolder(folder: File): List<RecipientEntry> {
        // Compute the relative path from the drop folder root
        val dropFolderPath = dropFolder.absolutePath
        val folderPath = folder.absolutePath
        val relativePath = if (folderPath.startsWith(dropFolderPath)) {
            folderPath.removePrefix(dropFolderPath).trimStart(File.separatorChar)
        } else {
            folderPath
        }
        val item = allItems[relativePath]
        return item?.trigger?.recipients ?: emptyList()
    }
    
    private fun uploadToMesh(file: File) {
        val api = MeshrabiyaApiImpl.getInstance()
        
        Log.i(TAG, "Uploading file to mesh: ${file.name}")
        
        // Upload via MeshrabiyaApi (metadata auto-generated internally) (file: File, recipients:List<RecipientEntry>,callback: (Result<String>) -> Unit)
        // distributedStorageManager.storeFile(file, getInheritedRecipientsForFile(file.path, true)) 

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val fileBytes = file.readBytes()
                val senderId = api.getNodeId()
                
                val fileRef = distributedStorageManager.storeFile(
                    path = file.absolutePath,
                    data = fileBytes,
                    recipients = getInheritedRecipientsForFile(file, true), 
                )
                if (fileRef != null) {
                    // callback(Result.success(fileRef.id))
                    api.getOnFileStored()?.invoke(fileRef.fileId, file, Result.success(fileRef.fileId))
                } else {
                    // callback(Result.failure(Exception("Failed to store file")))
                     api.getOnOperationFailed()?.invoke("storeFile",  Exception("Failed to store file"))
                }
            } catch (e: Exception) {
                // callback(Result.failure(e))
                api.getOnOperationFailed()?.invoke("storeFile", Exception("Failed to store file"))
            }
        }
        // { result ->
        //     result.fold(
        //         onSuccess = { fileId ->
        //             Log.i(TAG, "Drop folder file uploaded successfully: ${file.name} -> $fileId")
        //             processedFiles.add(file.absolutePath)
                    
                    
        //         },
        //         onFailure = { error ->
        //             Log.e(TAG, "Drop folder upload failed: ${file.name}", error)
                    
        //             // Remove from processed set to allow retry
        //             processedFiles.remove(file.absolutePath)
                    
        //             // Schedule retry based on error type
        //             when (error) {
        //                 is IOException -> {
        //                     Log.d(TAG, "Network error, scheduling retry in ${RETRY_DELAY_NETWORK_ERROR}ms")
        //                     scheduleRetry(file, RETRY_DELAY_NETWORK_ERROR)
        //                 }
        //                 is IllegalStateException -> {
        //                     Log.d(TAG, "Service not ready, scheduling retry in ${RETRY_DELAY_SERVICE_ERROR}ms")
        //                     scheduleRetry(file, RETRY_DELAY_SERVICE_ERROR)
        //                 }
        //                 else -> {
        //                     Log.e(TAG, "Permanent upload failure for ${file.name}, no retry")
        //                 }
        //             }
        //         }
        //     )
        // }
    }
    
    private fun scheduleRetry(file: File, delayMs: Long) {
        handler.postDelayed({
            if (file.exists()) {
                Log.d(TAG, "Retrying upload: ${file.name}")
                uploadQueue.offer(file)
                uploadExecutor.execute {
                    processUploadQueue()
                }
            }
        }, delayMs)
    }
    
    fun isInSharedSubfolder(file: File): Boolean {
        var parent = file.parentFile
        
        // Walk up directory tree
        while (parent != null && parent != dropFolder) {
            if (parent.name == "shared") {
                return true  // Skip this file
            }
            parent = parent.parentFile
        }
        
        return false  // Not in shared subfolder
    }
    
    private fun shouldDeleteAfterUpload(): Boolean {
        // Check user preference for auto-delete
        val prefs = getSharedPreferences("meshrabiya", Context.MODE_PRIVATE)
        return prefs.getBoolean("delete_after_upload", false)
    }
    
    private fun showNotification(title: String, message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }
    
    // ========== Service Lifecycle ==========
    
    override fun onBind(intent: Intent?): IBinder? {
        return null  // Not a bound service
    }
    
    override fun onDestroy() {
        fileObserver?.stopWatching()
        uploadExecutor.shutdown()
        handler.removeCallbacksAndMessages(null)
        Log.i(TAG, "MeshDropFolderService stopped")
        super.onDestroy()
    }
}
