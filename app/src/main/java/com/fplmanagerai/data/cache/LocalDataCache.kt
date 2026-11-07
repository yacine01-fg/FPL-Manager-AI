package com.fplmanagerai.data.cache

import java.io.File
import java.util.concurrent.ConcurrentHashMap

interface DataCacheStore {
    fun save(key: String, content: String)
    fun read(key: String): String?
    fun getTimestamp(key: String): Long
    fun isFresh(key: String, maxAgeMillis: Long): Boolean
    fun clear(key: String)
    fun clearAll()
}

class InMemoryDataCache : DataCacheStore {
    private val dataMap = ConcurrentHashMap<String, String>()
    private val timeMap = ConcurrentHashMap<String, Long>()

    override fun save(key: String, content: String) {
        dataMap[key] = content
        timeMap[key] = System.currentTimeMillis()
    }

    override fun read(key: String): String? = dataMap[key]

    override fun getTimestamp(key: String): Long = timeMap[key] ?: 0L

    override fun isFresh(key: String, maxAgeMillis: Long): Boolean {
        val ts = getTimestamp(key)
        if (ts == 0L) return false
        return (System.currentTimeMillis() - ts) < maxAgeMillis
    }

    override fun clear(key: String) {
        dataMap.remove(key)
        timeMap.remove(key)
    }

    override fun clearAll() {
        dataMap.clear()
        timeMap.clear()
    }
}

class FileBasedDataCache(private val cacheDir: File? = null) : DataCacheStore {
    private val fallback = InMemoryDataCache()

    companion object {
        const val KEY_BOOTSTRAP = "bootstrap_static"
        const val KEY_FIXTURES = "fixtures_list"

        const val BOOTSTRAP_TTL_MILLIS = 30 * 60 * 1000L // 30 minutes
        const val FIXTURES_TTL_MILLIS = 2 * 60 * 60 * 1000L // 2 hours
    }

    private fun getFile(key: String): File? {
        val dir = cacheDir ?: return null
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "fpl_${key}.json")
    }

    private fun getMetaFile(key: String): File? {
        val dir = cacheDir ?: return null
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "fpl_${key}.meta")
    }

    override fun save(key: String, content: String) {
        fallback.save(key, content)
        try {
            val file = getFile(key) ?: return
            val meta = getMetaFile(key) ?: return
            file.writeText(content, Charsets.UTF_8)
            meta.writeText(System.currentTimeMillis().toString(), Charsets.UTF_8)
        } catch (_: Exception) {
            // Memory fallback remains active
        }
    }

    override fun read(key: String): String? {
        // Check memory first
        fallback.read(key)?.let { return it }

        try {
            val file = getFile(key)
            if (file != null && file.exists() && file.length() > 0) {
                val content = file.readText(Charsets.UTF_8)
                fallback.save(key, content)
                return content
            }
        } catch (_: Exception) {
            // Ignore file read error and return null
        }
        return null
    }

    override fun getTimestamp(key: String): Long {
        val memoryTs = fallback.getTimestamp(key)
        if (memoryTs > 0L) return memoryTs

        try {
            val meta = getMetaFile(key)
            if (meta != null && meta.exists()) {
                val tsStr = meta.readText(Charsets.UTF_8).trim()
                return tsStr.toLongOrNull() ?: 0L
            }
        } catch (_: Exception) {
            return 0L
        }
        return 0L
    }

    override fun isFresh(key: String, maxAgeMillis: Long): Boolean {
        val ts = getTimestamp(key)
        if (ts == 0L) return false
        return (System.currentTimeMillis() - ts) < maxAgeMillis
    }

    override fun clear(key: String) {
        fallback.clear(key)
        try {
            getFile(key)?.delete()
            getMetaFile(key)?.delete()
        } catch (_: Exception) {}
    }

    override fun clearAll() {
        fallback.clearAll()
        try {
            cacheDir?.listFiles()?.forEach { if (it.name.startsWith("fpl_")) it.delete() }
        } catch (_: Exception) {}
    }
}

object LocalDataCache {
    var defaultCache: DataCacheStore = InMemoryDataCache()
        private set

    fun initialize(cacheDirectory: File) {
        defaultCache = FileBasedDataCache(cacheDirectory)
    }

    fun useInMemory() {
        defaultCache = InMemoryDataCache()
    }
}
