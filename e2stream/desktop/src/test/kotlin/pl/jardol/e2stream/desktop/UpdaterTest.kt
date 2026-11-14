package pl.jardol.e2stream.desktop

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdaterTest {
    private fun release(tag: String, prerelease: Boolean, asset: String? = "E2Stream.exe") = JSONObject()
        .put("tag_name", tag)
        .put("prerelease", prerelease)
        .put("html_url", "https://github.com/x/$tag")
        .put("assets", JSONArray().apply {
            if (asset != null) put(JSONObject().put("name", asset).put("browser_download_url", "https://dl/$tag/$asset").put("size", 10))
        })

    private val releases = JSONArray()
        .put(release("e2stream-0.18.44", false, "e2stream-0.18.44.apk"))
        .put(release("e2stream-windows-0.2.1-test", true))
        .put(release("e2stream-windows-0.2.0", false))
        .put(release("e2stream-windows-0.1.8-test", true))
        .put(release("epgscan-2.19.1", false, "epgscan.zip"))
        .put(release("e2stream-windows-0.1.9", false, asset = null))

    @Test
    fun stableChannelSkipsTestAndAndroidReleases() {
        val stable = Updater.parse(releases, "stable")
        assertEquals(listOf("0.2.0"), stable.map { it.version })
    }

    @Test
    fun testChannelIncludesTestReleasesNewestFirst() {
        val test = Updater.parse(releases, "test")
        assertEquals(listOf("0.2.1", "0.2.0", "0.1.8"), test.map { it.version })
        assertTrue(test.first().test)
    }

    @Test
    fun versionsCompareNumerically() {
        assertTrue(Updater.compare("0.10.0", "0.9.9") > 0)
        assertEquals(0, Updater.compare("0.2.0", "0.2.0"))
        assertTrue(Updater.compare("0.2.0", "0.2.1") < 0)
    }
}
