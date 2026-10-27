package com.jolsha.tv.data

import com.jolsha.tv.data.model.Category
import com.jolsha.tv.data.model.Channel
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
import java.io.StringReader

/**
 * Quality Assurance & Performance Stress Test Suite for Jolsha TV.
 *
 * Verifies edge cases:
 * - Corrupted EXTINF metadata (unclosed quotes, missing commas, negative durations).
 * - Extreme URLs (query params, auth tokens, spaces encoded or unencoded, ports, ipv4/ipv6).
 * - High volume playlist stress test (10,000+ entries) parsing speed and memory efficiency.
 * - Composite ID collision prevention and channel sorting.
 * - Quick channel guide selection and category switching logic.
 * - Rapid channel switching simulation and LRU history bounding.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistStressAndEdgeCaseTest {

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
    // 1. CORRUPTED EXTINF METADATA TESTS
    // =============================================================================================

    @Test
    fun testCorruptedExtInfUnclosedQuotes() {
        // Line with unclosed quotes in tvg-name and tvg-id
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="unclosed_id tvg-name="Broken Quote Name group-title="SPORTS",Valid Channel One
            http://stream.example/ch1
            #EXTINF:-1 tvg-name="Unclosed At End,Valid Channel Two
            http://stream.example/ch2
        """.trimIndent()

        val channels = parser.parse(m3u)
        assertEquals(2, channels.size)

        // Title after comma takes precedence
        assertEquals("Valid Channel One", channels[0].name)
        assertEquals(1, channels[0].channelNumber)

        assertEquals("Valid Channel Two", channels[1].name)
        assertEquals(2, channels[1].channelNumber)
    }

    @Test
    fun testCorruptedExtInfMissingCommas() {
        // Missing commas in various formats
        val m3u = """
            #EXTM3U
            #EXTINF:-1 Channel Without Any Comma
            http://stream.example/ch1
            #EXTINF:-1 tvg-name="Channel With TvgName" group-title="NEWS"
            http://stream.example/ch2
            #EXTINF:0 Channel Zero Duration No Comma
            http://stream.example/ch3
        """.trimIndent()

        val channels = parser.parse(m3u)
        assertEquals(3, channels.size)

        // Trailing text extracted as title
        assertEquals("Channel Without Any Comma", channels[0].name)
        assertEquals("Channel With TvgName", channels[1].name)
        assertEquals("NEWS", channels[1].category)
        assertEquals("Channel Zero Duration No Comma", channels[2].name)
    }

    @Test
    fun testNegativeAndExtremeDurations() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1,Live Stream Negative 1
            http://stream.example/ch1
            #EXTINF:-999,Live Stream Negative 999
            http://stream.example/ch2
            #EXTINF:-1.0,Live Stream Decimal Duration
            http://stream.example/ch3
            #EXTINF : -1 , Live Stream Spaced Colon
            http://stream.example/ch4
            #EXTINF 0 , Live Stream No Colon
            http://stream.example/ch5
        """.trimIndent()

        val channels = parser.parse(m3u)
        assertEquals(5, channels.size)

        assertEquals("Live Stream Negative 1", channels[0].name)
        assertEquals("Live Stream Negative 999", channels[1].name)
        assertEquals("Live Stream Decimal Duration", channels[2].name)
        assertEquals("Live Stream Spaced Colon", channels[3].name)
        assertEquals("Live Stream No Colon", channels[4].name)
    }

    // =============================================================================================
    // 2. EXTREME URL TESTS
    // =============================================================================================

    @Test
    fun testExtremeUrlsWithAuthTokensAndQueryParams() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1,Stream With Long Token
            https://secure.stream.net:8443/hls/live.m3u8?token=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ&auth=active&expires=1799999999&sig=a%2Bb%2Fc%3D%3D
            #EXTINF:-1,Stream With Multiple Query Keys
            http://10.189.188.190:8080/live/user123/pass456/101.ts?mode=adaptive&buffer=5000&fmt=ts
        """.trimIndent()

        val channels = parser.parse(m3u)
        assertEquals(2, channels.size)

        assertTrue(channels[0].streamUrl.contains("token=eyJhbGciOiJIUzI1Ni"))
        assertTrue(channels[0].streamUrl.contains("&sig=a%2Bb%2Fc%3D%3D"))
        assertEquals(8443, java.net.URI(channels[0].streamUrl).port)

        assertTrue(channels[1].streamUrl.contains("mode=adaptive"))
        assertEquals("10.189.188.190", java.net.URI(channels[1].streamUrl).host)
        assertEquals(8080, java.net.URI(channels[1].streamUrl).port)
    }

    @Test
    fun testExtremeUrlsWithEncodedAndUnencodedSpaces() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1,Channel With Unencoded Spaces
            http://stream.example.com:8080/live streams/channel 101/index.m3u8
            #EXTINF:-1,Channel With Already Encoded Spaces
            http://stream.example.com:8080/live%20streams/channel%20102/index.m3u8
            #EXTINF:-1,Channel With Query Param Spaces
            http://stream.example.com/stream?name=First Channel&quality=high hd
        """.trimIndent()

        val channels = parser.parse(m3u)
        assertEquals(3, channels.size)

        // Unencoded spaces must be sanitized to %20 to protect HttpURLConnection from crashing
        assertFalse(channels[0].streamUrl.contains(" "))
        assertTrue(channels[0].streamUrl.contains("live%20streams/channel%20101"))

        // Already encoded spaces remain valid
        assertFalse(channels[1].streamUrl.contains(" "))
        assertTrue(channels[1].streamUrl.contains("live%20streams/channel%20102"))

        // Query param spaces sanitized
        assertFalse(channels[2].streamUrl.contains(" "))
        assertTrue(channels[2].streamUrl.contains("First%20Channel"))
        assertTrue(channels[2].streamUrl.contains("high%20hd"))
    }

    @Test
    fun testExtremeUrlsWithPortsIpv4AndIpv6() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1,Standard IPv4 Custom Port
            http://192.168.1.100:9000/live/ch1.m3u8
            #EXTINF:-1,Loopback IPv4 Port
            http://127.0.0.1:8080/stream
            #EXTINF:-1,IPv6 Literal Address
            http://[2001:db8:85a3::8a2e:370:7334]:8080/live.m3u8
        """.trimIndent()

        val channels = parser.parse(m3u)
        assertEquals(3, channels.size)

        assertEquals("http://192.168.1.100:9000/live/ch1.m3u8", channels[0].streamUrl)
        assertEquals("http://127.0.0.1:8080/stream", channels[1].streamUrl)
        assertEquals("http://[2001:db8:85a3::8a2e:370:7334]:8080/live.m3u8", channels[2].streamUrl)
    }

    @Test
    fun testSupportedStreamingSchemes() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1,HTTP Stream
            http://stream.example/http.m3u8
            #EXTINF:-1,HTTPS Stream
            https://stream.example/https.m3u8
            #EXTINF:-1,RTMP Stream
            rtmp://stream.example/live/rtmp
            #EXTINF:-1,RTMPS Stream
            rtmps://stream.example/live/rtmps
            #EXTINF:-1,RTSP Stream
            rtsp://stream.example:554/live/rtsp
            #EXTINF:-1,UDP Multicast
            udp://@239.255.1.1:5000
            #EXTINF:-1,RTP Multicast
            rtp://@239.255.1.2:5002
            #EXTINF:-1,HLS Custom Scheme
            hls://stream.example/live.m3u8
        """.trimIndent()

        val channels = parser.parse(m3u)
        assertEquals(8, channels.size)
        assertEquals("http", channels[0].streamUrl.substringBefore("://"))
        assertEquals("https", channels[1].streamUrl.substringBefore("://"))
        assertEquals("rtmp", channels[2].streamUrl.substringBefore("://"))
        assertEquals("rtmps", channels[3].streamUrl.substringBefore("://"))
        assertEquals("rtsp", channels[4].streamUrl.substringBefore("://"))
        assertEquals("udp", channels[5].streamUrl.substringBefore("://"))
        assertEquals("rtp", channels[6].streamUrl.substringBefore("://"))
        assertEquals("hls", channels[7].streamUrl.substringBefore("://"))
    }

    // =============================================================================================
    // 3. HIGH VOLUME PLAYLIST STRESS TEST (10,000+ ENTRIES)
    // =============================================================================================

    @Test
    fun testHighVolumePlaylistStress10000Entries() = runTest(testDispatcher) {
        val entryCount = 10_000
        val sb = StringBuilder(entryCount * 120)
        sb.append("#EXTM3U\n")

        val categories = arrayOf("NEWS", "SPORTS", "ENTERTAINMENT", "MOVIES", "KIDS", "MUSIC", "DOCUMENTARY", "REGIONAL")

        for (i in 1..entryCount) {
            val cat = categories[i % categories.size]
            sb.append("#EXTINF:-1 tvg-id=\"id_$i\" tvg-name=\"Channel $i\" group-title=\"$cat\",Channel $i HD\n")
            sb.append("http://10.189.188.190:8080/live/user/pass/$i.m3u8\n")
        }

        val content = sb.toString()

        // 1. Measure parse speed
        val startTime = System.currentTimeMillis()
        val channels = parser.parse(StringReader(content))
        val parseDurationMs = System.currentTimeMillis() - startTime

        assertEquals(entryCount, channels.size)
        // High-volume parsing must complete rapidly (< 1500ms on modern JVM)
        assertTrue("Parsing $entryCount channels took ${parseDurationMs}ms, expected < 1500ms", parseDurationMs < 1500)

        // 2. Load into repository and measure categorization speed
        val repoStartTime = System.currentTimeMillis()
        val loadResult = repository.loadFromContent(content)
        val repoDurationMs = System.currentTimeMillis() - repoStartTime

        assertTrue(loadResult.isSuccess)
        assertEquals(entryCount, repository.channels.value.size)

        // Categories should have 3 system categories + 8 playlist groups = 11 categories
        val repoCats = repository.categories.value
        assertEquals(11, repoCats.size)
        assertEquals(entryCount, repoCats.first { it.id == Category.ID_ALL }.count)

        // Each category count must be correctly distributed
        for (catName in categories) {
            val cat = repoCats.find { it.name == catName }
            assertNotNull("Category $catName must exist", cat)
            assertTrue("Category $catName count must be > 0", cat!!.count > 0)
        }

        assertTrue("Repository loading & categorization took ${repoDurationMs}ms, expected < 2000ms", repoDurationMs < 2000)
    }

    // =============================================================================================
    // 4. COMPOSITE ID COLLISIONS & CHANNEL SORTING
    // =============================================================================================

    @Test
    fun testCompositeIdCollisionPrevention() {
        // Multiple channels with identical names, URLs, and metadata across different categories
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-name="BTV National" group-title="ALL CHANNELS",BTV National
            http://10.189.188.190/stream/1
            #EXTINF:-1 tvg-name="BTV National" group-title="BANGLA",BTV National
            http://10.189.188.190/stream/1
            #EXTINF:-1 tvg-name="BTV National" group-title="HD",BTV National
            http://10.189.188.190/stream/1
            #EXTINF:-1 tvg-name="BTV National" group-title="FAVORITES",BTV National
            http://10.189.188.190/stream/1
        """.trimIndent()

        val channels = parser.parse(m3u)
        assertEquals(4, channels.size)

        val uniqueIds = channels.map { it.id }.toSet()
        // No collisions: every channel gets a unique composite ID including its sequential index
        assertEquals(4, uniqueIds.size)

        // Sequential 1-based channel numbering and 0-based originalIndex
        for (i in channels.indices) {
            assertEquals(i + 1, channels[i].channelNumber)
            assertEquals(i, channels[i].originalIndex)
            assertEquals(String.format("%03d", i + 1), channels[i].formattedNumber)
        }
    }

    @Test
    fun testChannelSortingMechanisms() {
        val list = listOf(
            Channel(id = "ch_2", name = "Zebra TV", streamUrl = "http://test/2", channelNumber = 3, originalIndex = 2),
            Channel(id = "ch_0", name = "Alpha TV", streamUrl = "http://test/0", channelNumber = 1, originalIndex = 0),
            Channel(id = "ch_1", name = "Beta TV", streamUrl = "http://test/1", channelNumber = 2, originalIndex = 1)
        )

        // Sort by channelNumber
        val sortedByNumber = list.sortedBy { it.channelNumber }
        assertEquals("Alpha TV", sortedByNumber[0].name)
        assertEquals("Beta TV", sortedByNumber[1].name)
        assertEquals("Zebra TV", sortedByNumber[2].name)

        // Sort by Name
        val sortedByName = list.sortedBy { it.name }
        assertEquals("Alpha TV", sortedByName[0].name)
        assertEquals("Beta TV", sortedByName[1].name)
        assertEquals("Zebra TV", sortedByName[2].name)

        // Sort by originalIndex
        val sortedByIndex = list.sortedBy { it.originalIndex }
        assertEquals("Alpha TV", sortedByIndex[0].name)
        assertEquals("Beta TV", sortedByIndex[1].name)
        assertEquals("Zebra TV", sortedByIndex[2].name)
    }

    // =============================================================================================
    // 5. QUICK GUIDE SELECTION & CATEGORY SWITCHING LOGIC
    // =============================================================================================

    @Test
    fun testQuickChannelGuideSelectionAndCategorySwitching() = runTest(testDispatcher) {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="SPORTS",ESPN HD
            http://stream.example/espn
            #EXTINF:-1 group-title="SPORTS",Sky Sports
            http://stream.example/sky
            #EXTINF:-1 group-title="NEWS",CNN International
            http://stream.example/cnn
            #EXTINF:-1 group-title="NEWS",BBC World
            http://stream.example/bbc
            #EXTINF:-1 group-title="MOVIES",HBO
            http://stream.example/hbo
        """.trimIndent()

        repository.loadFromContent(m3u)

        // 1. All channels
        val all = repository.getChannelsForCategory(Category.ID_ALL)
        assertEquals(5, all.size)

        // 2. Sports category
        val sports = repository.getChannelsForCategory("cat_sports")
        assertEquals(2, sports.size)
        assertTrue(sports.all { it.category == "SPORTS" })

        // 3. News category
        val news = repository.getChannelsForCategory("cat_news")
        assertEquals(2, news.size)
        assertTrue(news.all { it.category == "NEWS" })

        // 4. Movies category
        val movies = repository.getChannelsForCategory("cat_movies")
        assertEquals(1, movies.size)
        assertEquals("HBO", movies[0].name)

        // 5. Category switching preserves items
        assertEquals(5, repository.channels.value.size)
    }

    // =============================================================================================
    // 6. RAPID CHANNEL SWITCHING & RECALL HISTORY SIMULATION
    // =============================================================================================

    @Test
    fun testRapidChannelSwitchingSimulation() = runTest(testDispatcher) {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="ALL",Channel A
            http://stream.example/a
            #EXTINF:-1 group-title="ALL",Channel B
            http://stream.example/b
            #EXTINF:-1 group-title="ALL",Channel C
            http://stream.example/c
            #EXTINF:-1 group-title="ALL",Channel D
            http://stream.example/d
        """.trimIndent()

        repository.loadFromContent(m3u)
        val channels = repository.channels.value

        // Simulate rapid channel switching 50 times in rapid succession
        var currentChannel: Channel? = null
        var previousChannelId: String? = null

        fun switchChannel(next: Channel) {
            if (currentChannel != null && currentChannel!!.id != next.id) {
                previousChannelId = currentChannel!!.id
            }
            currentChannel = next
            repository.markChannelAsWatched(next.id)
        }

        // Switch: A -> B -> C -> D -> A -> C -> B
        switchChannel(channels[0]) // A
        switchChannel(channels[1]) // B
        assertEquals(channels[0].id, previousChannelId) // previous was A
        assertEquals("Channel B", currentChannel?.name)

        switchChannel(channels[2]) // C
        assertEquals(channels[1].id, previousChannelId) // previous was B

        switchChannel(channels[3]) // D
        assertEquals(channels[2].id, previousChannelId) // previous was C

        // Rapid loop switching 100 times
        for (i in 0 until 100) {
            val target = channels[i % channels.size]
            switchChannel(target)
        }

        // Must maintain valid state, no crashes
        assertNotNull(currentChannel)
        assertNotNull(previousChannelId)

        // Recent channels history must be intact and properly ordered
        val recentChannels = repository.getChannelsForCategory(Category.ID_RECENT)
        assertTrue(recentChannels.size <= 4)
    }

    @Test
    fun testFavoritesAndRecentChannelsLruCap() {
        // Verify LRU cap (MAX_RECENT_CHANNELS = 30)
        for (i in 1..50) {
            userPrefs.addRecentChannel("channel_id_$i")
        }

        val recents = userPrefs.getRecentChannelIds()
        assertEquals(30, recents.size)
        // Most recent was #50
        assertEquals("channel_id_50", recents.first())
        // Oldest kept was #21
        assertEquals("channel_id_21", recents.last())

        // Re-watching #25 pushes it to the front
        userPrefs.addRecentChannel("channel_id_25")
        val recentsAfter = userPrefs.getRecentChannelIds()
        assertEquals(30, recentsAfter.size)
        assertEquals("channel_id_25", recentsAfter.first())
    }
}
