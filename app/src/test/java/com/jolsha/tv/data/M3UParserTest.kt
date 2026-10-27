package com.jolsha.tv.data

import android.content.SharedPreferences
import com.jolsha.tv.data.model.AspectRatio
import com.jolsha.tv.data.model.Category
import com.jolsha.tv.data.parser.M3UParser
import com.jolsha.tv.data.repository.PlaylistRepository
import com.jolsha.tv.data.repository.UserPreferencesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class M3UParserTest {

    private val parser = M3UParser()
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var userPrefs: UserPreferencesRepository
    private lateinit var repository: PlaylistRepository

    @Before
    fun setup() {
        fakePrefs = FakeSharedPreferences()
        userPrefs = UserPreferencesRepository(fakePrefs)
        repository = PlaylistRepository(
            preferencesRepository = userPrefs,
            dispatcher = testDispatcher
        )
    }

    // =============================================================================================
    // 1. STANDARD VALID M3U SAMPLE (Real entries from playlist.m3u)
    // =============================================================================================

    @Test
    fun testParseValidM3URealSample() {
        val sampleM3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="" tvg-name="BTV NATIONAL HD" group-title="ALL CHANNELS" tvg-logo="",BTV NATIONAL HD
            http://10.189.188.190:8080/7654321/1234567/156
            #EXTINF:-1 tvg-id="ATNBANG" tvg-name="ATN BANGLA HD" group-title="BANGLA" tvg-logo="http://10.189.188.190:8080/images/dfbba50e9cbf9269bb1ac404eae51e8f.png",ATN BANGLA HD
            http://10.189.188.190:8080/7654321/1234567/159
            #EXTINF:-1 tvg-id="NTVTR" tvg-name="NTV" group-title="BANGLA" tvg-logo="http://10.189.188.190:8080/images/ce8c32474d2fba4aa3da6031f2c03e07.png",NTV
            http://10.189.188.190:8080/7654321/1234567/162
        """.trimIndent()

        val channels = parser.parse(sampleM3u)

        assertEquals(3, channels.size)

        // First channel: BTV NATIONAL HD
        val ch1 = channels[0]
        assertEquals("BTV NATIONAL HD", ch1.name)
        assertEquals("http://10.189.188.190:8080/7654321/1234567/156", ch1.streamUrl)
        assertEquals("ALL CHANNELS", ch1.category)
        assertEquals("", ch1.tvgId)
        assertEquals("BTV NATIONAL HD", ch1.tvgName)
        assertEquals("", ch1.logoUrl)
        assertEquals(1, ch1.channelNumber)
        assertEquals(0, ch1.originalIndex)
        assertEquals("001", ch1.formattedNumber)
        assertTrue(ch1.id.startsWith("ch_0_"))

        // Second channel: ATN BANGLA HD
        val ch2 = channels[1]
        assertEquals("ATN BANGLA HD", ch2.name)
        assertEquals("ATNBANG", ch2.tvgId)
        assertEquals("BANGLA", ch2.category)
        assertEquals("http://10.189.188.190:8080/images/dfbba50e9cbf9269bb1ac404eae51e8f.png", ch2.logoUrl)
        assertEquals(2, ch2.channelNumber)
        assertEquals(1, ch2.originalIndex)
        assertEquals("002", ch2.formattedNumber)
        assertTrue(ch2.id.startsWith("ch_1_"))

        // Third channel: NTV
        val ch3 = channels[2]
        assertEquals("NTV", ch3.name)
        assertEquals("NTVTR", ch3.tvgId)
        assertEquals(3, ch3.channelNumber)
        assertEquals(2, ch3.originalIndex)
    }

    // =============================================================================================
    // 2. MISSING TVG ATTRIBUTES
    // =============================================================================================

    @Test
    fun testMissingTvgAttributes() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1,Ekushey TV
            http://10.189.188.190:8080/stream/161
            #EXTINF:0,Channel 24
            http://10.189.188.190:8080/stream/162
            #EXTINF:-1 group-title="NEWS",Independent TV
            http://10.189.188.190:8080/stream/163
        """.trimIndent()

        val channels = parser.parse(m3u)
        assertEquals(3, channels.size)

        // Missing all tvg-* and group-title -> defaults to "OTHER"
        val ch1 = channels[0]
        assertEquals("Ekushey TV", ch1.name)
        assertEquals("OTHER", ch1.category)
        assertEquals("", ch1.tvgId)
        assertEquals("", ch1.tvgName)
        assertEquals("", ch1.logoUrl)
        assertEquals(1, ch1.channelNumber)

        // 0 duration, missing attributes
        val ch2 = channels[1]
        assertEquals("Channel 24", ch2.name)
        assertEquals("OTHER", ch2.category)
        assertEquals(2, ch2.channelNumber)

        // Only group-title present
        val ch3 = channels[2]
        assertEquals("Independent TV", ch3.name)
        assertEquals("NEWS", ch3.category)
        assertEquals("", ch3.tvgId)
        assertEquals(3, ch3.channelNumber)
    }

    // =============================================================================================
    // 3. UNQUOTED ATTRIBUTES AND IRREGULAR WHITESPACE
    // =============================================================================================

    @Test
    fun testUnquotedAttributesAndIrregularWhitespace() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id=CH1 tvg-name=GTV group-title=BANGLA tvg-logo=http://example.com/logo.png,GTV HD
            http://example.com/stream1
            #EXTINF:-1   tvg-id  =  "CH2"   group-title   =   'SPORTS'   tvg-name  =  "T Sports"  ,   T Sports HD  
            http://example.com/stream2
            #EXTINF:-1 group-title="ENTERTAINMENT" tvg-name="Zee Bangla"
            http://example.com/stream3
        """.trimIndent()

        val channels = parser.parse(m3u)
        assertEquals(3, channels.size)

        // Unquoted attributes
        val ch1 = channels[0]
        assertEquals("CH1", ch1.tvgId)
        assertEquals("GTV", ch1.tvgName)
        assertEquals("BANGLA", ch1.category)
        assertEquals("http://example.com/logo.png", ch1.logoUrl)
        assertEquals("GTV HD", ch1.name)

        // Irregular whitespace and single quotes
        val ch2 = channels[1]
        assertEquals("CH2", ch2.tvgId)
        assertEquals("SPORTS", ch2.category)
        assertEquals("T Sports", ch2.tvgName)
        assertEquals("T Sports HD", ch2.name)

        // No comma in EXTINF line, fallback to tvg-name
        val ch3 = channels[2]
        assertEquals("ENTERTAINMENT", ch3.category)
        assertEquals("Zee Bangla", ch3.tvgName)
        assertEquals("Zee Bangla", ch3.name)
    }

    // =============================================================================================
    // 4. IDENTICAL URLS IN MULTIPLE CATEGORIES (No deduplication)
    // =============================================================================================

    @Test
    fun testIdenticalUrlsInMultipleCategories() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-name="BTV NATIONAL HD" group-title="ALL CHANNELS",BTV NATIONAL HD
            http://10.189.188.190:8080/7654321/1234567/156
            #EXTINF:-1 tvg-name="BTV NATIONAL HD" group-title="BANGLA",BTV NATIONAL HD
            http://10.189.188.190:8080/7654321/1234567/156
            #EXTINF:-1 tvg-name="BTV NEWS HD" group-title="ALL CHANNELS",BTV NEWS HD
            http://10.189.188.190:8080/7654321/1234567/157
            #EXTINF:-1 tvg-name="BTV NEWS HD" group-title="BANGLA",BTV NEWS HD
            http://10.189.188.190:8080/7654321/1234567/157
            #EXTINF:-1 tvg-name="BTV NEWS HD" group-title="NEWS",BTV NEWS HD
            http://10.189.188.190:8080/7654321/1234567/157
        """.trimIndent()

        val channels = parser.parse(m3u)

        // Must preserve all 5 entries without deduplication
        assertEquals(5, channels.size)

        // Verify unique composite IDs
        val ids = channels.map { it.id }.toSet()
        assertEquals(5, ids.size)

        // Verify identical stream URLs are preserved
        assertEquals(channels[0].streamUrl, channels[1].streamUrl)
        assertEquals("ALL CHANNELS", channels[0].category)
        assertEquals("BANGLA", channels[1].category)

        assertEquals(channels[2].streamUrl, channels[3].streamUrl)
        assertEquals(channels[3].streamUrl, channels[4].streamUrl)
        assertEquals("ALL CHANNELS", channels[2].category)
        assertEquals("BANGLA", channels[3].category)
        assertEquals("NEWS", channels[4].category)

        // Channel numbers sequence 1..5
        for (i in channels.indices) {
            assertEquals(i + 1, channels[i].channelNumber)
            assertEquals(i, channels[i].originalIndex)
        }
    }

    // =============================================================================================
    // 5. UNICODE CHARACTERS (Bengali, Hindi, Arabic)
    // =============================================================================================

    @Test
    fun testUnicodeChannelNames() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-name="সময় টিভি" group-title="বাংলা",সময় টিভি এইচডি
            http://stream.example/somoy
            #EXTINF:-1 tvg-name="आज तक" group-title="समाचार",आज तक लाइव
            http://stream.example/aajtak
            #EXTINF:-1 tvg-name="الجزيرة" group-title="أخبار",الجزيرة الإخبارية
            http://stream.example/aljazeera
            #EXTINF:-1 tvg-name="দীপ্ত টিভি" group-title="বাংলা",দীপ্ত টিভি
            http://stream.example/deeptotv
        """.trimIndent()

        val channels = parser.parse(m3u)
        assertEquals(4, channels.size)

        assertEquals("সময় টিভি এইচডি", channels[0].name)
        assertEquals("সময় টিভি", channels[0].tvgName)
        assertEquals("বাংলা", channels[0].category)

        assertEquals("आज तक लाइव", channels[1].name)
        assertEquals("आज तक", channels[1].tvgName)
        assertEquals("समाचार", channels[1].category)

        assertEquals("الجزيرة الإخبارية", channels[2].name)
        assertEquals("الجزيرة", channels[2].tvgName)
        assertEquals("أخبار", channels[2].category)

        assertEquals("দীপ্ত টিভি", channels[3].name)
    }

    // =============================================================================================
    // 6. MALFORMED ENTRIES, BLANK LINES, INVALID URLS
    // =============================================================================================

    @Test
    fun testMalformedEntriesBlankLinesAndInvalidUrls() {
        val m3u = """
            #EXTM3U

            # Some comment line

               
            #EXTINF:-1 tvg-name="Broken Channel 1"
            not_a_valid_url
            
            #EXTINF:-1 tvg-name="Valid Channel 1" group-title="TEST",Valid 1
            http://stream.example/valid1

            #EXTINF:-1 tvg-name="Unfinished Entry"
            #EXTINF:-1 tvg-name="Valid Channel 2" group-title="TEST",Valid 2
            https://stream.example/valid2

            # Channel without EXTINF tag
            http://stream.example/raw_stream_3

            #EXTINF:-1,Invalid Scheme Channel
            ftp://stream.example/invalid_ftp
        """.trimIndent()

        val channels = parser.parse(m3u)

        // Broken Channel 1: ignored (invalid URL)
        // Valid Channel 1: parsed (index 0)
        // Unfinished Entry: replaced by Valid Channel 2
        // Valid Channel 2: parsed (index 1)
        // Channel without EXTINF: parsed (index 2)
        // Invalid Scheme Channel: ignored (ftp is not a streaming scheme)
        assertEquals(3, channels.size)

        val ch1 = channels[0]
        assertEquals("Valid 1", ch1.name)
        assertEquals("http://stream.example/valid1", ch1.streamUrl)
        assertEquals(1, ch1.channelNumber)
        assertEquals(0, ch1.originalIndex)

        val ch2 = channels[1]
        assertEquals("Valid 2", ch2.name)
        assertEquals("https://stream.example/valid2", ch2.streamUrl)
        assertEquals(2, ch2.channelNumber)
        assertEquals(1, ch2.originalIndex)

        val ch3 = channels[2]
        assertEquals("Channel 3", ch3.name)
        assertEquals("http://stream.example/raw_stream_3", ch3.streamUrl)
        assertEquals("OTHER", ch3.category)
        assertEquals(3, ch3.channelNumber)
        assertEquals(2, ch3.originalIndex)
    }

    // =============================================================================================
    // 7. CATEGORY EXTRACTION AND GROUPING IN PLAYLIST REPOSITORY
    // =============================================================================================

    @Test
    fun testCategoryExtractionAndGroupingInRepository() = runTest(testDispatcher) {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="ALL CHANNELS",BTV National
            http://stream.example/1
            #EXTINF:-1 group-title="BANGLA",BTV National
            http://stream.example/1
            #EXTINF:-1 group-title="BANGLA",Channel I
            http://stream.example/2
            #EXTINF:-1 group-title="NEWS",Somoy TV
            http://stream.example/3
            #EXTINF:-1 group-title="SPOTRS",T Sports
            http://stream.example/4
        """.trimIndent()

        val result = repository.loadFromContent(m3u)
        assertTrue(result.isSuccess)

        val categories = repository.categories.value

        // System categories: All Channels, Favorites, Recently Watched
        assertEquals(Category.ID_ALL, categories[0].id)
        assertEquals("All Channels", categories[0].name)
        assertEquals(5, categories[0].count)
        assertTrue(categories[0].isSystemCategory)

        assertEquals(Category.ID_FAVORITES, categories[1].id)
        assertEquals("Favorites", categories[1].name)
        assertEquals(0, categories[1].count)

        assertEquals(Category.ID_RECENT, categories[2].id)
        assertEquals("Recently Watched", categories[2].name)
        assertEquals(0, categories[2].count)

        // Playlist groups: ALL CHANNELS (1), BANGLA (2), NEWS (1), SPOTRS (1)
        val banglaCat = categories.find { it.name == "BANGLA" }
        assertNotNull(banglaCat)
        assertEquals(2, banglaCat!!.count)

        val newsCat = categories.find { it.name == "NEWS" }
        assertNotNull(newsCat)
        assertEquals(1, newsCat!!.count)

        val sportsCat = categories.find { it.name == "SPOTRS" }
        assertNotNull(sportsCat)
        assertEquals(1, sportsCat!!.count)

        // Category channel filtering
        val banglaChannels = repository.getChannelsForCategory(banglaCat)
        assertEquals(2, banglaChannels.size)
        assertTrue(banglaChannels.all { it.category == "BANGLA" })

        val allChannels = repository.getChannelsForCategory(Category.ID_ALL)
        assertEquals(5, allChannels.size)
    }

    // =============================================================================================
    // 8. SEARCH FILTERING IN PLAYLIST REPOSITORY
    // =============================================================================================

    @Test
    fun testSearchFilteringInRepository() = runTest(testDispatcher) {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-name="BTV NATIONAL HD" group-title="BANGLA",BTV National
            http://stream.example/1
            #EXTINF:-1 tvg-name="ATN BANGLA HD" group-title="BANGLA",ATN Bangla
            http://stream.example/2
            #EXTINF:-1 tvg-name="SOMOY TV HD" group-title="NEWS",Somoy News
            http://stream.example/3
            #EXTINF:-1 tvg-name="সময় টিভি" group-title="বাংলা",সময় সংবাদ
            http://stream.example/4
        """.trimIndent()

        repository.loadFromContent(m3u)

        // Empty query returns all channels
        assertEquals(4, repository.searchChannels("").size)
        assertEquals(4, repository.searchChannels("   ").size)

        // Substring match on channel name (case-insensitive)
        val btvResults = repository.searchChannels("btv")
        assertEquals(1, btvResults.size)
        assertEquals("BTV National", btvResults[0].name)

        // Substring match on category
        val newsResults = repository.searchChannels("news")
        assertEquals(1, newsResults.size)
        assertEquals("Somoy News", newsResults[0].name)

        // Substring match on tvg-name
        val atnResults = repository.searchChannels("atn")
        assertEquals(1, atnResults.size)
        assertEquals("ATN Bangla", atnResults[0].name)

        // Search by channel number
        val ch3Results = repository.searchChannels("3")
        assertTrue(ch3Results.any { it.channelNumber == 3 })

        // Unicode search
        val unicodeResults = repository.searchChannels("সংবাদ")
        assertEquals(1, unicodeResults.size)
        assertEquals("সময় সংবাদ", unicodeResults[0].name)

        // Non-matching query
        val emptyResults = repository.searchChannels("xyz_non_existent")
        assertEquals(0, emptyResults.size)
    }

    // =============================================================================================
    // 9. FAVORITES AND RECENT CHANNELS (LRU, MAX 30)
    // =============================================================================================

    @Test
    fun testFavoritesAndRecentChannels() = runTest(testDispatcher) {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="BANGLA",Channel One
            http://stream.example/1
            #EXTINF:-1 group-title="BANGLA",Channel Two
            http://stream.example/2
            #EXTINF:-1 group-title="NEWS",Channel Three
            http://stream.example/3
        """.trimIndent()

        repository.loadFromContent(m3u)
        val chList = repository.channels.value
        val ch1Id = chList[0].id
        val ch2Id = chList[1].id
        val ch3Id = chList[2].id

        // Initially no favorites
        assertEquals(0, repository.getChannelsForCategory(Category.ID_FAVORITES).size)

        // Toggle ch1 to favorite
        val isFav = repository.toggleFavorite(ch1Id)
        assertTrue(isFav)
        assertTrue(userPrefs.isFavorite(ch1Id))

        // Check favorites in repository
        val favChannels = repository.getChannelsForCategory(Category.ID_FAVORITES)
        assertEquals(1, favChannels.size)
        assertEquals("Channel One", favChannels[0].name)

        val favCategory = repository.categories.value.find { it.id == Category.ID_FAVORITES }
        assertEquals(1, favCategory?.count)

        // Add recent channels (LRU order)
        repository.markChannelAsWatched(ch1Id)
        repository.markChannelAsWatched(ch2Id)
        repository.markChannelAsWatched(ch3Id)
        // Re-watch ch1 -> should jump to the front
        repository.markChannelAsWatched(ch1Id)

        val recentChannels = repository.getChannelsForCategory(Category.ID_RECENT)
        assertEquals(3, recentChannels.size)
        assertEquals("Channel One", recentChannels[0].name) // Most recently watched
        assertEquals("Channel Three", recentChannels[1].name)
        assertEquals("Channel Two", recentChannels[2].name)

        // Test LRU max 30 limit
        for (i in 0 until 50) {
            userPrefs.addRecentChannel("channel_$i")
        }
        assertEquals(30, userPrefs.getRecentChannelIds().size)
        assertEquals("channel_49", userPrefs.getRecentChannelIds().first())
    }

    // =============================================================================================
    // 10. USER PREFERENCES (Aspect Ratio and Source Persistence)
    // =============================================================================================

    @Test
    fun testUserPreferences() {
        assertEquals(AspectRatio.FIT, userPrefs.getDefaultAspectRatio())

        userPrefs.setDefaultAspectRatio(AspectRatio.SIXTEEN_BY_NINE)
        assertEquals(AspectRatio.SIXTEEN_BY_NINE, userPrefs.getDefaultAspectRatio())

        userPrefs.setDefaultAspectRatio(AspectRatio.ZOOM)
        assertEquals(AspectRatio.ZOOM, userPrefs.getDefaultAspectRatio())

        // Source persistence
        userPrefs.setLastLoadedSource("content://media/external/file/101")
        assertEquals("content://media/external/file/101", userPrefs.getLastLoadedSource())

        userPrefs.setLastLoadedSource(null)
        assertEquals(null, userPrefs.getLastLoadedSource())
    }
}

/**
 * Pure Kotlin in-memory SharedPreferences implementation for JUnit tests.
 */
class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any>()

    override fun getAll(): MutableMap<String, *> = HashMap(values)

    override fun getString(key: String?, defValue: String?): String? =
        (values[key] as? String) ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int =
        (values[key] as? Int) ?: defValue

    override fun getLong(key: String?, defValue: Long): Long =
        (values[key] as? Long) ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        (values[key] as? Float) ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        (values[key] as? Boolean) ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor(values)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    class FakeEditor(private val target: MutableMap<String, Any>) : SharedPreferences.Editor {
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
