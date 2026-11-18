package pl.jardol.e2stream

import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SettingsStorageTest {
    private lateinit var controller: ActivityController<SettingsActivity>
    private lateinit var activity: SettingsActivity
    private lateinit var prefs: Prefs
    private lateinit var list: ListView

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("e2stream", Context.MODE_PRIVATE).edit().clear().commit()
        PinLock.lock()
        controller = Robolectric.buildActivity(SettingsActivity::class.java).create()
        activity = controller.get()
        prefs = Prefs(activity)
        list = activity.findViewById(R.id.settingsList)
    }

    @After
    fun tearDown() {
        ShadowDialog.getLatestDialog()?.dismiss()
        controller.destroy()
        PinLock.lock()
    }

    private fun openStorage() {
        SettingsActivity::class.java.getDeclaredMethod("openSection", String::class.java)
            .apply { isAccessible = true }.invoke(activity, "Pamięć masowa")
    }

    private fun rows(): List<View> = (0 until list.count).map { list.adapter.getView(it, null, list) }
    private fun labels(): List<String> = rows().mapNotNull { it.findViewById<TextView>(R.id.label)?.text?.toString() }
    private fun has(id: Int) = activity.getString(id) in labels()

    private fun chooseMedium(index: (ListView) -> Int) {
        val position = rows().indexOfFirst {
            it.findViewById<TextView>(R.id.label)?.text?.toString() == activity.getString(R.string.storage_medium)
        }
        list.performItemClick(list.adapter.getView(position, null, list), position, position.toLong())
        val choices = findList(ShadowDialog.getLatestDialog().window!!.decorView)!!
        val selected = index(choices)
        choices.performItemClick(choices.adapter.getView(selected, null, choices), selected, selected.toLong())
    }

    private fun findList(view: View): ListView? {
        if (view is ListView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findList(view.getChildAt(i))?.let { return it }
        return null
    }

    @Test
    fun noneHidesDependentRowsAndReselectingMediumRestoresSavedSettings() {
        prefs.tsInternalOk = true
        prefs.bufPlay = true
        prefs.bgCount = 2
        prefs.bgBufMin = 3
        prefs.bgFullMsg = false
        prefs.bgStatusMsg = false
        prefs.pipEnabled = true
        prefs.pipFrameMs = 3_000
        prefs.pipPos = 3
        prefs.tsMin = 5
        prefs.tsDelay = 30
        prefs.tsStepSec = 4
        openStorage()
        val originalLabels = labels()
        assertTrue(has(R.string.setting_bg_full_msg))
        assertTrue(has(R.string.setting_pip_position))
        assertTrue(has(R.string.setting_seek_lr_short))

        chooseMedium { it.count - 1 }
        val features = listOf(R.string.setting_buffer_play, R.string.setting_pip_enabled, R.string.setting_timeshift)
        val visible = rows()
        for (id in features) {
            val row = visible.single { it.findViewById<TextView>(R.id.label)?.text?.toString() == activity.getString(id) }
            assertTrue(row is ViewGroup)
            fun texts(view: View): List<String> =
                if (view is TextView) listOf(view.text.toString())
                else if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
                else emptyList()
            assertTrue(texts(row).contains(activity.getString(R.string.storage_requires_medium)))
        }
        for (id in listOf(R.string.setting_bg_buffer, R.string.setting_bg_buffer_time,
            R.string.setting_bg_full_msg, R.string.setting_bg_status_msg, R.string.setting_pip_mode,
            R.string.setting_pip_position, R.string.setting_automatic_timeshift,
            R.string.setting_seek_lr_short, R.string.setting_rec_max_hours)) assertFalse(has(id))
        assertEquals(5, prefs.tsMin)
        assertTrue(prefs.bufPlay)
        assertEquals(2, prefs.bgCount)
        assertTrue(prefs.pipEnabled)
        assertFalse(prefs.tsInternalOk)

        val internalIndex = Storage.options(activity).filter { it.path != activity.filesDir.absolutePath }
            .indexOfFirst { it.path == TimeshiftBuffer.internalBase(activity).absolutePath }
        assertTrue(internalIndex >= 0)
        chooseMedium { internalIndex }
        ShadowDialog.getLatestDialog().window!!.decorView.findViewWithTag<Button>("chip_0").performClick()
        assertEquals(originalLabels, labels())
        assertTrue(prefs.tsInternalOk)
        assertEquals(3, prefs.bgBufMin)
        assertFalse(prefs.bgFullMsg)
        assertFalse(prefs.bgStatusMsg)
        assertEquals(3_000, prefs.pipFrameMs)
        assertEquals(3, prefs.pipPos)
        assertEquals(30, prefs.tsDelay)
        assertEquals(4, prefs.tsStepSec)
        assertEquals(5, prefs.tsMin)
    }

    @Test
    fun noneShowsRequirementsEvenWithFeaturesDisabledAndRetainsOtherVisibilityRules() {
        prefs.bufPlay = false
        prefs.pipEnabled = false
        prefs.tsMin = 0
        openStorage()
        assertTrue(has(R.string.setting_buffer_play))
        assertTrue(has(R.string.setting_pip_enabled))
        assertTrue(has(R.string.setting_timeshift))

        prefs.tsInternalOk = true
        openStorage()
        assertFalse(has(R.string.setting_pip_enabled))
        assertFalse(has(R.string.setting_bg_buffer))
        assertFalse(has(R.string.setting_automatic_timeshift))

        prefs.bufPlay = true
        prefs.bgCount = 0
        openStorage()
        assertTrue(has(R.string.setting_bg_buffer))
        assertTrue(has(R.string.setting_pip_enabled))
        assertFalse(has(R.string.setting_pip_mode))
        assertFalse(has(R.string.setting_bg_full_msg))
        assertFalse(has(R.string.setting_automatic_timeshift))
    }

    @Test
    fun disconnectedSelectedMediumKeepsExistingInactiveOptions() {
        prefs.tsDir = activity.cacheDir.resolve("unavailable-medium").apply { writeText("not a directory") }.absolutePath
        prefs.tsInternalOk = false
        prefs.bufPlay = true
        prefs.bgCount = 2
        prefs.pipEnabled = true
        prefs.tsMin = 5
        openStorage()
        assertTrue(has(R.string.setting_bg_buffer))
        assertTrue(has(R.string.setting_bg_status_msg))
        assertTrue(has(R.string.setting_pip_mode))
        assertTrue(has(R.string.setting_seek_lr_short))
    }

    @Test
    fun writeBatchOptionRequiresSelectedMediumAndBufferPlayback() {
        prefs.tsInternalOk = true
        prefs.bufPlay = true
        prefs.tsWriteBatchBytes = 512 * 1024
        openStorage()
        assertTrue(has(R.string.setting_buffer_write_batch))

        prefs.bufPlay = false
        openStorage()
        assertFalse(has(R.string.setting_buffer_write_batch))
        assertEquals(512 * 1024, prefs.tsWriteBatchBytes)

        prefs.bufPlay = true
        openStorage()
        chooseMedium { it.count - 1 }
        assertFalse(has(R.string.setting_buffer_write_batch))
        assertEquals(512 * 1024, prefs.tsWriteBatchBytes)
    }
}
