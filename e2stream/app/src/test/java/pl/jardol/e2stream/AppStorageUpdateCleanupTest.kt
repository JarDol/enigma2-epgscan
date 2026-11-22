package pl.jardol.e2stream

import android.app.Application
import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AppStorageUpdateCleanupTest {
    @Test
    fun successfulPackageReplacementRemovesCachedApksImmediately() {
        val context = RuntimeEnvironment.getApplication()
        val suffix = System.nanoTime()
        val apk = File(context.cacheDir, "e2stream-test-$suffix.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val partial = File(context.cacheDir, "e2stream-test-$suffix.apk.part").apply { writeBytes(byteArrayOf(4, 5)) }
        val unrelated = File(context.cacheDir, "unrelated-$suffix.apk").apply { writeBytes(byteArrayOf(6)) }

        try {
            ZapBootReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

            assertFalse(apk.exists())
            assertFalse(partial.exists())
            assertTrue(unrelated.exists())
        } finally {
            apk.delete()
            partial.delete()
            unrelated.delete()
        }
    }
}
