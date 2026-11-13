package pl.jardol.e2stream

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdaterTest {
    @Test
    fun filtersReleaseChannelAndSortsVersions() {
        val releases = listOf(
            release("e2stream-0.18.29", asset("e2stream-0.18.29.apk", "29")),
            release("e2stream-v0.18.31-beta.1", asset("e2stream-0.18.31-beta.1.apk", "beta"), prerelease = true),
            release(
                "e2stream-0.18.32-beta.1-vlc.3.6.5",
                asset("e2stream-0.18.32-beta.1-vlc.3.6.5.apk", "vlc-3.6.5"),
                prerelease = true,
            ),
            release(
                "e2stream-0.18.32-beta.2-vlc.3.7.0",
                asset("e2stream-0.18.32-beta.2-vlc.3.7.0.apk", "vlc-3.7.0"),
                prerelease = true,
            ),
            release(
                "e2stream-0.18.32-beta.3-vlc.3.7.7",
                asset("e2stream-0.18.32-beta.3-vlc.3.7.7.apk", "vlc-3.7.7"),
                prerelease = true,
            ),
            release("e2stream-0.18.30", asset("e2stream-0.18.30.apk", "30")),
            release("e2stream-0.18.32", asset("e2stream-0.18.32.apk", "draft"), draft = true),
            release("plugin-1.0", asset("plugin-1.0.ipk", "plugin")),
        )

        assertEquals(
            listOf("0.18.30", "0.18.29"),
            Updater.selectReleases(releases, "stable").map { it.version },
        )
        assertEquals(
            listOf(
                "0.18.32-beta.3-vlc.3.7.7",
                "0.18.32-beta.2-vlc.3.7.0",
                "0.18.32-beta.1-vlc.3.6.5",
                "0.18.31-beta.1",
                "0.18.30",
                "0.18.29",
            ),
            Updater.selectReleases(releases, "beta").map { it.version },
        )
    }

    @Test
    fun ignoresReleasesWithoutMatchingApk() {
        val release = release("e2stream-0.18.30", asset("other.apk", "other"))

        assertEquals(emptyList<Updater.Info>(), Updater.selectReleases(listOf(release), "stable"))
    }

    private fun release(
        tag: String,
        vararg assets: Updater.Asset,
        prerelease: Boolean = false,
        draft: Boolean = false,
    ) = Updater.Release(tag, prerelease, draft, assets.toList())

    private fun asset(name: String, suffix: String) =
        Updater.Asset(name, "https://example.test/$suffix.apk")
}
