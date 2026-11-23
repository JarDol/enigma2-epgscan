package pl.jardol.e2stream

import android.app.Application
import android.app.Dialog
import android.content.Context
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerActionMenusTest {
    private lateinit var controller: ActivityController<PlayerActivity>
    private lateinit var activity: PlayerActivity
    private lateinit var prefs: Prefs
    private val buffers = mutableListOf<TimeshiftBuffer>()
    private val recorders = mutableListOf<TimeshiftRecorder>()
    private val main = Channel(1, "Main", "1:0:1:ABC:456:789:0:0:0:0:")
    private val other = Channel(2, "Other", "1:0:1:DEF:456:789:0:0:0:0:")
    private val bgType = PlayerActivity::class.java.declaredClasses.single { it.simpleName == "BgChan" }

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("e2stream", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context).apply {
            playbackAttempt = "unfinished / VLC"
            channelSync = false
            logFtp = false
            appLanguage = "pl"
        }
        SyncManager.init(context)
        controller = Robolectric.buildActivity(PlayerActivity::class.java).setup()
        activity = controller.get()
        TvDialog.dismissAll()
        prefs.bufPlay = true
        prefs.tsInternalOk = true
        prefs.pipEnabled = true
        prefs.actionDialogSec = 5
        prefs.confirmDialogSec = 0
        set("bufLive", true)
        set("tsBuf", buffer())
        set("playList", listOf(main, other))
        set("playIndex", 0)
        set("bouquets", listOf(Bouquet("Test", "bouquet")))
        get<EpgUi>("epg").eitOnly = true
    }

    @After fun tearDown() {
        TvDialog.dismissAll()
        backgrounds().clear()
        set("localRec", null)
        set("tsBuf", null)
        set("bufLive", false)
        controller.pause().stop().destroy()
        recorders.forEach { it.stop() }
        buffers.forEach { it.close(); it.dir.deleteRecursively() }
    }

    private fun set(name: String, value: Any?) =
        PlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)

    @Suppress("UNCHECKED_CAST")
    private fun <T> get(name: String): T =
        PlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T

    private fun call(name: String, vararg args: Any?) =
        PlayerActivity::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(activity, *args)

    private fun buffer() = TimeshiftBuffer(File(RuntimeEnvironment.getApplication().cacheDir,
        "action-menu-${System.nanoTime()}"), 5).also { buffers += it }

    private fun backgrounds(): MutableList<Any> = get("bgChans")

    private fun background(ch: Channel = other, pip: Int = -1): Any {
        val recorder = TimeshiftRecorder("http://example.test/live", null, { null }, onFailure = {})
            .also { recorders += it }
        return bgType.declaredConstructors.single().apply { isAccessible = true }.newInstance(
            "http://example.test/live", ch.name, buffer(), TsAnalyzer(1880) {}, recorder,
            ProgramJournal(), ch, 0, listOf(main, other),
        ).also {
            bgType.getDeclaredField("pip").apply { isAccessible = true }.setInt(it, pip)
            backgrounds() += it
        }
    }

    private fun elapsed(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(seconds, TimeUnit.SECONDS)

    private fun drain() {
        get<ExecutorService>("io").submit {}.get(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun findList(view: View): ListView? {
        if (view is ListView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findList(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun list() = findList(TvDialog.top()!!.window!!.decorView)!!
    private fun labels(): List<String> = list().let { lv ->
        (0 until lv.adapter.count).map { (lv.adapter.getItem(it) as TvDialog.Item).title }
    }
    private fun pick(index: Int) = list().let { it.performItemClick(it.adapter.getView(index, null, it), index, it.adapter.getItemId(index)) }

    private fun timeout(dialog: Dialog = TvDialog.top()!!) {
        dialog.onWindowFocusChanged(true)
        elapsed(5)
        assertFalse(dialog.isShowing)
        elapsed(2)
        assertNull(TvDialog.top())
    }

    @Suppress("UNCHECKED_CAST")
    private fun pipButtons(ch: Channel): List<TvDialog.Btn> =
        call("channelPipButtons", ch, "bouquet", listOf(main, other)) as List<TvDialog.Btn>

    @Test fun currentChannelAndHistoryDescriptionsHaveManagementWithoutEpgAndKeepSelection() {
        for (history in listOf(false, true)) {
            set("panelHist", history)
            set("panelList", listOf(other, main))
            get<View>("panel").visibility = View.VISIBLE
            val channelList = get<ListView>("list")
            channelList.setSelection(1)
            channelList.onItemLongClickListener!!.onItemLongClick(channelList, null, 1, 1)
            drain()
            val button = TvDialog.top()!!.window!!.decorView.findViewWithTag<Button>("chip_1")
            assertNotNull(button)
            assertEquals(activity.getString(R.string.epg_pip), button.text.toString())
            button.performClick()
            assertNotNull(list())
            assertEquals(1, channelList.selectedItemPosition)
            timeout()
            assertEquals(1, channelList.selectedItemPosition)
            assertTrue(backgrounds().isEmpty())
        }
        val normalized = Channel(1, main.name, main.ref.lowercase().trimEnd(':'))
        assertEquals(activity.getString(R.string.epg_pip), pipButtons(normalized).single().label)
    }

    @Test fun pipAddAndCloseRecheckStateWhenExecuted() {
        val add = pipButtons(other).single()
        assertEquals(activity.getString(R.string.pip_add_channel), add.label)
        set("playIndex", 1)
        add.onClick()
        assertTrue(backgrounds().isEmpty())
        assertEquals(activity.getString(R.string.pip_is_main), get<TextView>("message").text.toString())
        set("playIndex", 0)
        val bg = background(pip = 0)
        val close = pipButtons(other).single()
        assertEquals(activity.getString(R.string.pip_close_channel), close.label)
        bgType.getDeclaredField("pip").apply { isAccessible = true }.setInt(bg, -1)
        close.onClick()
        assertEquals(activity.getString(R.string.pip_no_longer_open), get<TextView>("message").text.toString())
        assertEquals(1, backgrounds().size)
    }

    @Test fun fullPipRejectsAdditionWithoutRemovingBuffers() {
        repeat(3) { index -> background(Channel(index + 3, "Pip $index", "1:0:1:${index + 10}:0:0:0:0:0:0:"), index) }
        pipButtons(other).single().onClick()
        assertEquals(3, backgrounds().size)
        assertEquals(activity.getString(R.string.pip_full), get<TextView>("message").text.toString())
    }

    @Test fun pipTimeoutResetsOnInputAndLeavesWindowAndBufferRunning() {
        val bg = background(pip = 0)
        call("showPipMenu", 0)
        val dialog = TvDialog.top()!!
        dialog.onWindowFocusChanged(true)
        elapsed(4)
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
        elapsed(4)
        assertTrue(dialog.isShowing)
        elapsed(1)
        assertFalse(dialog.isShowing)
        elapsed(2)
        assertNull(TvDialog.top())
        assertSame(bg, backgrounds().single())
        assertEquals(0, bgType.getDeclaredField("pip").apply { isAccessible = true }.getInt(bg))
        assertTrue(get<Boolean>("bufLive"))
    }

    @Test fun disabledTimeoutKeepsPipAndBufferMenusOpen() {
        prefs.actionDialogSec = 0
        background()
        call("showPipMenu", 0)
        var dialog = TvDialog.top()!!
        dialog.onWindowFocusChanged(true)
        elapsed(60)
        assertTrue(dialog.isShowing)
        dialog.dismiss()
        call("showBufferedMenu", 0)
        dialog = TvDialog.top()!!
        dialog.onWindowFocusChanged(true)
        elapsed(60)
        assertTrue(dialog.isShowing)
    }

    @Test fun bufferedChannelHasTwoActionsAndSameRecordingChoicesAsEpg() {
        val bg = background()
        val event = EpgEvent(1, System.currentTimeMillis() / 1000 - 60, 3600, "Program", "", "", other.ref, other.name)
        call("bufferedActions", bg, other, event, 1)
        assertEquals(listOf(activity.getString(R.string.player_bg_close), activity.getString(R.string.player_bgrec_menu)), labels())
        pick(1)
        val choices = labels()
        assertEquals(3, choices.size)
        assertEquals(activity.getString(R.string.player_bgrec_event, event.title), choices.first())
        timeout()
        assertSame(bg, backgrounds().single())
        call("bufRecMenu", bg, other, event, false, {})
        assertEquals(choices, labels())
        TvDialog.top()!!.onBackPressed()
        assertNull(TvDialog.top())
    }

    @Test fun bufferRecordingWithoutEpgHasTwoChoicesAndBackReturnsButTimeoutDoesNot() {
        val bg = background()
        call("bufferedActions", bg, other, null, 1)
        pick(1)
        drain()
        assertEquals(2, labels().size)
        assertEquals(activity.getString(R.string.player_bgrec_until), labels().last())
        TvDialog.top()!!.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, labels().size)
        timeout()
        assertSame(bg, backgrounds().single())
        assertTrue(get<Boolean>("bufLive"))
    }

    @Test fun receiverRecordingWindowsTimeoutWithoutOpeningTimersOrConfirming() {
        val timer = DecoderTimer(other.ref, other.name, "Program", "", 0, Long.MAX_VALUE, 1, false, false)
        call("showDecoderTimer", timer)
        timeout()
        call("confirmDeleteDecoderTimer", timer)
        timeout()
    }

    @Test fun recordingAndProtectedBufferTimeoutNeverStopsOrApprovesAnything() {
        val bg = background()
        val bgBuffer = bgType.getDeclaredField("buf").apply { isAccessible = true }.get(bg) as TimeshiftBuffer
        val recType = PlayerActivity::class.java.declaredClasses.single { it.simpleName == "LocalRec" }
        fun recording(buffer: TimeshiftBuffer, channel: Channel): Any {
            val pin = buffer.startPin(File(buffer.dir, "recording"), 0)!!
            return recType.declaredConstructors.single().apply { isAccessible = true }.newInstance(
                pin, REC_EVENT, JSONObject().put("title", "Program"), Long.MAX_VALUE, channel,
                0L, 0L, buffer, ProgramJournal(),
            )
        }
        val bgRecording = recording(bgBuffer, other)
        val recordingField = bgType.getDeclaredField("lrec").apply { isAccessible = true }
        recordingField.set(bg, bgRecording)
        call("bgCloseAsk", bg, {})
        timeout()
        assertSame(bg, backgrounds().single())
        assertSame(bgRecording, recordingField.get(bg))
        assertNotNull(bgBuffer.pin)
        call("bufRecMenu", bg, other, null, false, {})
        assertEquals(listOf(activity.getString(R.string.player_bgrec_stop)), labels())
        timeout()
        assertSame(bgRecording, recordingField.get(bg))
        val mainRecording = recording(get("tsBuf"), main)
        set("localRec", mainRecording)
        call("showRecordMenu")
        timeout()
        call("showStopRecs", 0)
        timeout()
        assertSame(mainRecording, get<Any>("localRec"))
        assertSame(bgRecording, recordingField.get(bg))
    }

    @Test fun subtitleMenuHidesMimeAndPidWhilePreservingRealTrackSelection() {
        val formats = (737..738).map {
            Format.Builder().setId("15037/$it").setLanguage("pl")
                .setSampleMimeType("application/x-media3-cues").build()
        }
        val tracks = Tracks(listOf(Tracks.Group(
            TrackGroup(*formats.toTypedArray()), false,
            intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED), booleanArrayOf(false, false),
        )))
        var params = TrackSelectionParameters.Builder(activity).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, args ->
            when (method.name) {
                "getCurrentTracks" -> tracks
                "getTrackSelectionParameters" -> params
                "setTrackSelectionParameters" -> { params = args!![0] as TrackSelectionParameters; null }
                else -> null
            }
        } as ExoPlayer
        set("player", player)
        call("showSubtitleDialog")
        assertEquals(listOf(activity.getString(R.string.subtitle_off), "Polski (1)", "Polski (2)"), labels())
        pick(1)
        assertFalse(C.TRACK_TYPE_TEXT in params.disabledTrackTypes)
        assertEquals(listOf(0), params.overrides.values.single().trackIndices)
        timeout()
        assertFalse(C.TRACK_TYPE_TEXT in params.disabledTrackTypes)
    }

    @Test fun audioMenuLabelsUseLocalizedDescriptionsAndKeepCodecDetails() {
        assertEquals("Polski  •  Audiodeskrypcja  •  AC3 6ch",
            call("audioLabel", "pl", "ac3", 6, "Audio description for the visually impaired - [Polish]", false))
        assertEquals("Polski  •  Stacja X  •  MPEG 2ch",
            call("audioLabel", "pl", "mpga", 2, "Stacja X", false))
    }
}
