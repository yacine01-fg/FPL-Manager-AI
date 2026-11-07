package com.fplmanagerai.data

import com.fplmanagerai.data.cache.FileBasedDataCache
import com.fplmanagerai.data.cache.InMemoryDataCache
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalDataCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testInMemoryDataCacheSaveAndRead() {
        val cache = InMemoryDataCache()
        assertNull(cache.read("key1"))

        cache.save("key1", "sample_content")
        assertEquals("sample_content", cache.read("key1"))
        assertTrue(cache.isFresh("key1", 5000L))
        assertFalse(cache.isFresh("key1", -1L))

        cache.clear("key1")
        assertNull(cache.read("key1"))
    }

    @Test
    fun testFileBasedDataCache() {
        val dir = tempFolder.newFolder("fpl_cache_test")
        val cache = FileBasedDataCache(dir)

        assertNull(cache.read("test_key"))

        cache.save("test_key", "{\"status\":\"ok\"}")
        assertEquals("{\"status\":\"ok\"}", cache.read("test_key"))
        assertTrue(cache.isFresh("test_key", 10000L))

        // Create new instance pointing to same directory to test persistence
        val secondInstance = FileBasedDataCache(dir)
        assertEquals("{\"status\":\"ok\"}", secondInstance.read("test_key"))

        secondInstance.clear("test_key")
        assertNull(secondInstance.read("test_key"))
    }
}
