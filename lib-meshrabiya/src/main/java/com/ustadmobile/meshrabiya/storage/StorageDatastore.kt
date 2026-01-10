package com.ustadmobile.meshrabiya.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import com.ustadmobile.meshrabiya.vnet.MeshChunk
import com.ustadmobile.meshrabiya.vnet.MeshFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ustadmobile.meshrabiya.storage.FileReference


class MeshDataStoreDb(context: Context) : SQLiteOpenHelper(context, "mesh_datastore.db", null, 3) {
    
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE mesh_files (
                fileId TEXT PRIMARY KEY,
                fileName TEXT,
                path TEXT,
                sizeBytes INTEGER,
                owner TEXT,
                recipients TEXT,
                createdAt INTEGER,
                relativePath TEXT
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE mesh_chunks (
                chunkId TEXT PRIMARY KEY,
                fileId TEXT,
                chunkIndex INTEGER,
                totalChunks INTEGER,
                chunkSize INTEGER,
                fileName TEXT,
                relativePath TEXT,
                hash TEXT,
                storedAt INTEGER,
                sessionKeys BLOB,
                serverPath TEXT
            )
        """.trimIndent())
    }
                // recipients TEXT,
                // owner TEXT,

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            db.execSQL("DROP TABLE IF EXISTS mesh_files")
            db.execSQL("DROP TABLE IF EXISTS mesh_chunks")
            onCreate(db)
        }
    }
}

class StorageDataStore private constructor(context: Context) {
    private val gson = Gson()
    private val dbHelper = MeshDataStoreDb(context)

    companion object {
        @Volatile private var instance: StorageDataStore? = null
        fun getInstance(context: Context): StorageDataStore =
            instance ?: synchronized(this) {
                instance ?: StorageDataStore(context.applicationContext).also { instance = it }
            }
    }

    // --- Mesh File Operations ---
    fun addMeshFile(file: MeshFile) {
        val values = ContentValues().apply {
            put("fileId", file.fileId)
            put("fileName", file.fileName)
            put("path", file.path)
            put("sizeBytes", file.sizeBytes)
            put("owner", serializeRecipient(file.owner))
            put("recipients", serializeRecipients(file.recipients))
            put("createdAt", file.createdAt)
            put("relativePath", file.relativePath)
        }
        dbHelper.writableDatabase.insertWithOnConflict("mesh_files", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getMeshFile(fileId: String): MeshFile? {
        dbHelper.readableDatabase.rawQuery(
            "SELECT * FROM mesh_files WHERE fileId = ?", arrayOf(fileId)
        ).use { cursor ->
            return if (cursor.moveToFirst()) {
                MeshFile(
                    fileId = cursor.getString(cursor.getColumnIndexOrThrow("fileId")),
                    fileName = cursor.getString(cursor.getColumnIndexOrThrow("fileName")),
                    path = cursor.getString(cursor.getColumnIndexOrThrow("path")),
                    sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow("sizeBytes")),
                    owner = deserializeRecipient(cursor.getString(cursor.getColumnIndexOrThrow("owner"))),
                    recipients = deserializeRecipients(cursor.getString(cursor.getColumnIndexOrThrow("recipients"))),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("createdAt")),
                    relativePath = cursor.getString(cursor.getColumnIndexOrThrow("relativePath"))
                )
            } else null
        }
    }

    fun deleteMeshFile(fileId: String) {
        dbHelper.writableDatabase.delete("mesh_files", "fileId = ?", arrayOf(fileId))
    }

    fun getAllMeshFiles(): List<MeshFile> {
        val files = mutableListOf<MeshFile>()
        dbHelper.readableDatabase.rawQuery("SELECT * FROM mesh_files", null).use { cursor ->
            while (cursor.moveToNext()) {
                files.add(
                    MeshFile(
                        fileId = cursor.getString(cursor.getColumnIndexOrThrow("fileId")),
                        path = cursor.getString(cursor.getColumnIndexOrThrow("path")),
                        sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow("sizeBytes")),
                        owner = deserializeRecipient(cursor.getString(cursor.getColumnIndexOrThrow("owner"))),
                        recipients = deserializeRecipients(cursor.getString(cursor.getColumnIndexOrThrow("recipients"))),
                        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("createdAt")),
                        relativePath = cursor.getString(cursor.getColumnIndexOrThrow("relativePath")),
                        fileName = cursor.getString(cursor.getColumnIndexOrThrow("fileName"))
                    )
                )
            }
        }
        return files
    }

    fun getAllMeshFileNames(): List<String> {
        val names = mutableListOf<String>()
        dbHelper.readableDatabase.rawQuery("SELECT fileName FROM mesh_files", null).use { cursor ->
            while (cursor.moveToNext()) {
                names.add(cursor.getString(cursor.getColumnIndexOrThrow("fileName")))
            }
        }
        return names
    }

    // --- Mesh Chunk Operations ---
    fun addMeshChunk(chunk: MeshChunk) {
        val values = ContentValues().apply {
            put("chunkId", chunk.chunkId)
            put("fileId", chunk.fileId)
            put("chunkIndex", chunk.chunkIndex)
            put("totalChunks", chunk.totalChunks)
            put("chunkSize", chunk.chunkSize)
            put("fileName", chunk.fileName)
            put("relativePath", chunk.relativePath)
            put("hash", chunk.hash)
            put("storedAt", chunk.storedAt)
            // put("recipients", serializeRecipients(chunk.recipients))
            // put("owner", serializeRecipient(chunk.owner))
            put("sessionKeys", serializeSessionKeys(chunk.sessionKeys))
            put("serverPath", chunk.serverPath) // <-- NEW FIELD
        }
        dbHelper.writableDatabase.insertWithOnConflict("mesh_chunks", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getMeshChunk(chunkId: String): MeshChunk? {
        dbHelper.readableDatabase.rawQuery(
            "SELECT * FROM mesh_chunks WHERE chunkId = ?", arrayOf(chunkId)
        ).use { cursor ->
            return if (cursor.moveToFirst()) {
                MeshChunk(
                    chunkId = cursor.getString(cursor.getColumnIndexOrThrow("chunkId")),
                    fileId = cursor.getString(cursor.getColumnIndexOrThrow("fileId")),
                    chunkIndex = cursor.getInt(cursor.getColumnIndexOrThrow("chunkIndex")),
                    totalChunks = cursor.getInt(cursor.getColumnIndexOrThrow("totalChunks")),
                    chunkSize = cursor.getLong(cursor.getColumnIndexOrThrow("chunkSize")),
                    fileName = cursor.getString(cursor.getColumnIndexOrThrow("fileName")),
                    relativePath = cursor.getString(cursor.getColumnIndexOrThrow("relativePath")),
                    hash = cursor.getString(cursor.getColumnIndexOrThrow("hash")),
                    storedAt = cursor.getLong(cursor.getColumnIndexOrThrow("storedAt")),
                    sessionKeys = deserializeSessionKeys(cursor.getBlob(cursor.getColumnIndexOrThrow("sessionKeys"))),
                    replicaCount = cursor.getInt(cursor.getColumnIndexOrThrow("replicaCount")),
                    // fileReference = deserializeFileReference(cursor.getString(cursor.getColumnIndexOrThrow("fileReference"))) ?: error("fileReference must not be null for chunkId=${cursor.getString(cursor.getColumnIndexOrThrow("chunkId"))}"),
                    serverPath = cursor.getString(cursor.getColumnIndexOrThrow("serverPath")) // <-- NEW FIELD
                )
            } else null
        }
    }

    fun getChunksForFile(fileId: String): List<MeshChunk> {
        val chunks = mutableListOf<MeshChunk>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT * FROM mesh_chunks WHERE fileId = ? ORDER BY chunkIndex ASC", arrayOf(fileId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                chunks.add(
                    MeshChunk(
                        chunkId = cursor.getString(cursor.getColumnIndexOrThrow("chunkId")),
                        fileId = cursor.getString(cursor.getColumnIndexOrThrow("fileId")),
                        chunkIndex = cursor.getInt(cursor.getColumnIndexOrThrow("chunkIndex")),
                        totalChunks = cursor.getInt(cursor.getColumnIndexOrThrow("totalChunks")),
                        chunkSize = cursor.getLong(cursor.getColumnIndexOrThrow("chunkSize")),
                        fileName = cursor.getString(cursor.getColumnIndexOrThrow("fileName")),
                        relativePath = cursor.getString(cursor.getColumnIndexOrThrow("relativePath")),
                        hash = cursor.getString(cursor.getColumnIndexOrThrow("hash")),
                        storedAt = cursor.getLong(cursor.getColumnIndexOrThrow("storedAt")),
                        sessionKeys = deserializeSessionKeys(cursor.getBlob(cursor.getColumnIndexOrThrow("sessionKeys"))),
                        replicaCount = cursor.getInt(cursor.getColumnIndexOrThrow("replicaCount")),
                        // fileReference = deserializeFileReference(cursor.getString(cursor.getColumnIndexOrThrow("fileReference"))) ?: error("fileReference must not be null for chunkId=${cursor.getString(cursor.getColumnIndexOrThrow("chunkId"))}"),
                        serverPath = cursor.getString(cursor.getColumnIndexOrThrow("serverPath")) // <-- NEW FIELD
                    )
                )
            }
        }
        return chunks
    }

    fun getAllMeshChunks(): List<MeshChunk> {
        val chunks = mutableListOf<MeshChunk>()
        dbHelper.readableDatabase.rawQuery("SELECT * FROM mesh_chunks", null).use { cursor ->
            while (cursor.moveToNext()) {
                chunks.add(
                    MeshChunk(
                        chunkId = cursor.getString(cursor.getColumnIndexOrThrow("chunkId")),
                        fileId = cursor.getString(cursor.getColumnIndexOrThrow("fileId")),
                        chunkIndex = cursor.getInt(cursor.getColumnIndexOrThrow("chunkIndex")),
                        totalChunks = cursor.getInt(cursor.getColumnIndexOrThrow("totalChunks")),
                        chunkSize = cursor.getLong(cursor.getColumnIndexOrThrow("chunkSize")),
                        fileName = cursor.getString(cursor.getColumnIndexOrThrow("fileName")),
                        relativePath = cursor.getString(cursor.getColumnIndexOrThrow("relativePath")),
                        hash = cursor.getString(cursor.getColumnIndexOrThrow("hash")),
                        storedAt = cursor.getLong(cursor.getColumnIndexOrThrow("storedAt")),
                        sessionKeys = deserializeSessionKeys(cursor.getBlob(cursor.getColumnIndexOrThrow("sessionKeys"))),
                        replicaCount = cursor.getInt(cursor.getColumnIndexOrThrow("replicaCount")),
                        // fileReference = deserializeFileReference(cursor.getString(cursor.getColumnIndexOrThrow("fileReference"))) ?: error("fileReference must not be null for chunkId=${cursor.getString(cursor.getColumnIndexOrThrow("chunkId"))}"),
                        serverPath = cursor.getString(cursor.getColumnIndexOrThrow("serverPath")) // <-- NEW FIELD
                    )
                )
            }
        }
        return chunks
    }

    fun getAllMeshFileIds(): List<String> {
        val ids = mutableListOf<String>()
        dbHelper.readableDatabase.rawQuery("SELECT DISTINCT fileId FROM mesh_chunks", null).use { cursor ->
            while (cursor.moveToNext()) {
                ids.add(cursor.getString(cursor.getColumnIndexOrThrow("fileId")))
            }
        }
        return ids
    }

    // --- Update chunk metadata/session keys ---
    fun updateMeshChunk(chunk: MeshChunk) {
        val values = ContentValues().apply {
            put("fileId", chunk.fileId)
            put("chunkIndex", chunk.chunkIndex)
            put("totalChunks", chunk.totalChunks)
            put("chunkSize", chunk.chunkSize)
            put("fileName", chunk.fileName)
            put("relativePath", chunk.relativePath)
            put("hash", chunk.hash)
            put("storedAt", chunk.storedAt)
            put("sessionKeys", serializeSessionKeys(chunk.sessionKeys))
            put("serverPath", chunk.serverPath)
        }
        dbHelper.writableDatabase.update("mesh_chunks", values, "chunkId = ?", arrayOf(chunk.chunkId))
    }

    // --- Directory/Index API ---
    fun getDirectoryIndex(): Map<MeshFile, List<MeshChunk>> {
        val files = getAllMeshFiles()
        val index = mutableMapOf<MeshFile, List<MeshChunk>>()
        for (file in files) {
            val chunks = getChunksForFile(file.fileId)
            index[file] = chunks
        }
        return index
    }

    fun getStorageSummary(): StorageSummary {
        val files = getAllMeshFiles()
        val chunks = getAllMeshChunks()
        val totalSize = files.sumOf { it.sizeBytes }
        return StorageSummary(
            fileCount = files.size,
            chunkCount = chunks.size,
            totalSize = totalSize
        )
    }

    data class StorageSummary(
        val fileCount: Int,
        val chunkCount: Int,
        val totalSize: Long
    )

    // --- Helper methods for recipients/owner and sessionKeys serialization ---

    private fun serializeRecipients(recipients: List<RecipientEntry>): String {
        return gson.toJson(recipients)
    }

    private fun deserializeRecipients(data: String): List<RecipientEntry> {
        if (data.isBlank()) return emptyList()
        val type = object : TypeToken<List<RecipientEntry>>() {}.type
        return gson.fromJson(data, type)
    }

    private fun serializeRecipient(owner: RecipientEntry): String {
        return gson.toJson(owner)
    }

    private fun deserializeRecipient(data: String): RecipientEntry {
        if (data.isBlank()) throw IllegalArgumentException("Recipient data must not be blank")
        return gson.fromJson(data, RecipientEntry::class.java)
    }

    private fun serializeFileReference(fileReference: FileReference?): String {
        return if (fileReference == null) "" else gson.toJson(fileReference)
    }

    private fun deserializeFileReference(data: String): FileReference? {
        if (data.isBlank()) return null
        return gson.fromJson(data, FileReference::class.java)
    }

    private fun serializeSessionKeys(sessionKeys: Map<String, ByteArray>): ByteArray {
        // Simple serialization: keyId:length:data ... (not for cryptographic use)
        if (sessionKeys.isEmpty()) return ByteArray(0)
        val out = mutableListOf<Byte>()
        for ((keyId, data) in sessionKeys) {
            val keyIdBytes = keyId.toByteArray(Charsets.UTF_8)
            val lengthBytes = data.size.toString().toByteArray(Charsets.UTF_8)
            out.add(keyIdBytes.size.toByte())
            out.addAll(keyIdBytes.toList())
            out.add(lengthBytes.size.toByte())
            out.addAll(lengthBytes.toList())
            out.addAll(data.toList())
        }
        return out.toByteArray()
    }

    private fun deserializeSessionKeys(blob: ByteArray?): Map<String, ByteArray> {
        if (blob == null || blob.isEmpty()) return emptyMap()
        val map = mutableMapOf<String, ByteArray>()
        var idx = 0
        while (idx < blob.size) {
            val keyIdLen = blob[idx].toInt()
            idx++
            val keyId = String(blob, idx, keyIdLen, Charsets.UTF_8)
            idx += keyIdLen
            val lenLen = blob[idx].toInt()
            idx++
            val dataLen = String(blob, idx, lenLen, Charsets.UTF_8).toIntOrNull() ?: 0
            idx += lenLen
            val data = blob.copyOfRange(idx, idx + dataLen)
            idx += dataLen
            if (keyId != null) map[keyId] = data
        }
        return map
    }
    private fun parseRecipientKeyIds(str: String?): List<Long> {
        if (str.isNullOrEmpty()) return emptyList()
        return str.split(",").mapNotNull { it.toLongOrNull() }
    }

    // private fun serializeFileReference(fileReference: FileReference?): String {
    //     return if (fileReference == null) "" else gson.toJson(fileReference)
    // }

    // private fun deserializeFileReference(data: String): FileReference? {
    //     if (data.isBlank()) return null
    //     return gson.fromJson(data, FileReference::class.java)
    // }

    // private fun serializeSessionKeys(sessionKeys: Map<Long, ByteArray>): ByteArray {
    //     // Simple serialization: keyId:length:data ... (not for cryptographic use)
    //     if (sessionKeys.isEmpty()) return ByteArray(0)
    //     val out = mutableListOf<Byte>()
    //     for ((keyId, data) in sessionKeys) {
    //         val keyIdBytes = keyId.toString().toByteArray(Charsets.UTF_8)
    //         val lengthBytes = data.size.toString().toByteArray(Charsets.UTF_8)
    //         out.add(keyIdBytes.size.toByte())
    //         out.addAll(keyIdBytes.toList())
    //         out.add(lengthBytes.size.toByte())
    //         out.addAll(lengthBytes.toList())
    //         out.addAll(data.toList())
    //     }
    //     return out.toByteArray()
    // }

    // private fun deserializeSessionKeys(blob: ByteArray?): Map<Long, ByteArray> {
    //     if (blob == null || blob.isEmpty()) return emptyMap()
    //     val map = mutableMapOf<Long, ByteArray>()
    //     var idx = 0
    //     while (idx < blob.size) {
    //         val keyIdLen = blob[idx].toInt()
    //         idx++
    //         val keyId = String(blob, idx, keyIdLen, Charsets.UTF_8).toLongOrNull()
    //         idx += keyIdLen
    //         val lenLen = blob[idx].toInt()
    //         idx++
    //         val dataLen = String(blob, idx, lenLen, Charsets.UTF_8).toIntOrNull() ?: 0
    //         idx += lenLen
    //         val data = blob.copyOfRange(idx, idx + dataLen)
    //         idx += dataLen
    //         if (keyId != null) map[keyId] = data
    //     }
    //     return map
    // }
}