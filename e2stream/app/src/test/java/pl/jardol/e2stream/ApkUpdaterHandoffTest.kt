package pl.jardol.e2stream

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ApkUpdaterHandoffTest {
    @Test
    fun requestUsesExactReleaseTagChannelAndExplicitDowngradeDecision() {
        val info = Updater.Info(
            version = "0.18.39-beta.1",
            url = "https://example.test/e2stream.apk",
            name = "e2stream-0.18.39-beta.1.apk",
            prerelease = true,
            tag = "e2stream-v0.18.39-beta.1",
        )

        val request = ApkUpdaterHandoff.requestIntent(info, "beta", allowDowngrade = true)

        assertEquals(ApkUpdaterHandoff.ACTION_REQUEST_INSTALL, request.action)
        assertEquals("pl.jardol.apkupdater", request.`package`)
        assertEquals("JarDol/enigma2-epgscan", request.getStringExtra(ApkUpdaterHandoff.EXTRA_REPOSITORY))
        assertEquals("pl.jardol.e2stream", request.getStringExtra(ApkUpdaterHandoff.EXTRA_PACKAGE))
        assertEquals("e2stream-v0.18.39-beta.1", request.getStringExtra(ApkUpdaterHandoff.EXTRA_VERSION))
        assertEquals("beta", request.getStringExtra(ApkUpdaterHandoff.EXTRA_CHANNEL))
        assertTrue(request.getBooleanExtra(ApkUpdaterHandoff.EXTRA_ALLOW_DOWNGRADE, false))
        assertEquals("pl.jardol.apkupdater.extra.MESSAGE", ApkUpdaterHandoff.EXTRA_MESSAGE)
    }

    @Test
    fun onlyThePinnedSingleUpdaterSignerIsTrusted() {
        val expected = ApkUpdaterHandoff.EXPECTED_SIGNER_SHA256
        assertTrue(ApkUpdaterHandoff.hasTrustedSigner(listOf(expected)))
        assertTrue(ApkUpdaterHandoff.hasTrustedSigner(listOf(expected.uppercase())))
        assertFalse(ApkUpdaterHandoff.hasTrustedSigner(listOf("00".repeat(32))))
        assertFalse(ApkUpdaterHandoff.hasTrustedSigner(listOf(expected, expected)))
        assertFalse(ApkUpdaterHandoff.hasTrustedSigner(emptyList()))
    }

    @Test
    fun missingUpdaterIsTreatedAsUnavailable() {
        val context = RuntimeEnvironment.getApplication()
        val info = Updater.Info("0.18.39", "", "e2stream.apk", false, "e2stream-0.18.39")
        val intent = ApkUpdaterHandoff.requestIntent(info, "stable", allowDowngrade = false)

        assertFalse(ApkUpdaterHandoff.isAvailable(context as Context, intent))
    }
}
