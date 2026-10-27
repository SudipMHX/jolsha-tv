package com.jolsha.tv.data

import android.content.SharedPreferences
import com.jolsha.tv.data.model.Category
import com.jolsha.tv.data.repository.PlaylistRepository
import com.jolsha.tv.data.repository.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DuplicateChannelHandlingTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var fakePrefs: SharedPreferences
    private lateinit var userPrefs: UserPreferencesRepository
    private lateinit var repository: PlaylistRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakePrefs = FakeTestSharedPreferences()
        userPrefs = UserPreferencesRepository(fakePrefs)
        repository = PlaylistRepository(userPrefs, testDispatcher, testScope)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testDuplicateChannelsAndCategoriesHandledGracefully() = runTest(testDispatcher) {
        // Simulates real IPTV playlist where channels are repeated across ALL CHANNELS and categories
        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="ALL CHANNELS",BTV National
            http://10.189.188.190:8080/156
            #EXTINF:-1 group-title="BANGLA",BTV National
            http://10.189.188.190:8080/156
            #EXTINF:-1 group-title="ALL CHANNELS",BTV News
            http://10.189.188.190:8080/157
            #EXTINF:-1 group-title="BANGLA",BTV News
            http://10.189.188.190:8080/157
            #EXTINF:-1 group-title="NEWS",BTV News
            http://10.189.188.190:8080/157
        """.trimIndent()

        val result = repository.loadFromContent(m3u)
        assertTrue(result.isSuccess)

        val categories = repository.categories.value
        // "ALL CHANNELS" group is not duplicated as a category tab because System "All Channels" already exists
        assertNull(categories.find { it.id == "cat_all_channels" })

        // "All Channels" system category count should be unique channels: 2 (BTV National & BTV News)
        val allCat = categories.find { it.id == Category.ID_ALL }
        assertNotNull(allCat)
        assertEquals(2, allCat!!.count)

        // getChannelsForCategory(ID_ALL) returns exactly 2 channels without duplicates
        val distinctChannels = repository.getChannelsForCategory(Category.ID_ALL)
        assertEquals(2, distinctChannels.size)

        // Bangla group has 2 channels
        val banglaCat = categories.find { it.name == "BANGLA" }
        assertNotNull(banglaCat)
        assertEquals(2, banglaCat!!.count)

        // News group has 1 channel
        val newsCat = categories.find { it.name == "NEWS" }
        assertNotNull(newsCat)
        assertEquals(1, newsCat!!.count)
    }
}

private class FakeTestSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any>()

    override fun getAll(): MutableMap<String, *> = HashMap(values)
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    }
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor(values)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private class Editor(private val target: MutableMap<String, Any>) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clearPending = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) pending[key] = values?.toSet()
            return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) pending[key] = null
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            clearPending = true
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clearPending) target.clear()
            for ((k, v) in pending) {
                if (v == null) target.remove(k) else target[k] = v
            }
        }
    }
}
