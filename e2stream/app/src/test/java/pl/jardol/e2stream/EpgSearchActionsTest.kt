package pl.jardol.e2stream

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ListView
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class EpgSearchActionsTest {
    private val io = Executors.newSingleThreadExecutor()
    private val server = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
    private lateinit var serverThread: Thread
    private val controller = Robolectric.buildActivity(Activity::class.java).setup()
    private val activity = controller.get()
    private val ref = "1:0:1:123:456:789:0:0:0:0:"
    private val begin = System.currentTimeMillis() / 1000 + 3600
    @Volatile private var success = true
    private val messages = mutableListOf<Pair<String, Boolean>>()
    private lateinit var epg: EpgUi

    @Before fun setUp() {
        activity.getSharedPreferences("zaptimers", Context.MODE_PRIVATE).edit().clear().commit()
        serverThread = Thread {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val request = reader.readLine()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val body = if (request.contains("/api/epgsearch")) {
                        """{"events":[{"id":42,"begin_timestamp":$begin,"duration_sec":3600,"title":"Test programu","sref":"$ref","sname":"Kanał"}]}"""
                    } else {
                        """{"result":$success,"message":"${if (success) "Dodano timer" else "Błąd"}"}"""
                    }.toByteArray(Charsets.UTF_8)
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(body)
                    output.flush()
                }
            } catch (e: SocketException) {
                if (!server.isClosed) throw e
            }
        }
        serverThread.start()
        val prefs = Prefs(activity).apply { host = "127.0.0.1"; httpPort = server.localPort }
        val host = object : EpgUi.Host {
            override fun current(): Channel? = null
            override fun bouquetChannels(): List<Channel> = emptyList()
            override fun bouquetList(): List<Bouquet> = emptyList()
            override fun currentBouquet(): Bouquet? = null
            override fun zapTo(ref: String, onResult: ((Boolean, String) -> Unit)?) = Unit
            override fun message(t: String) { messages += t to (TvDialog.top() != null) }
        }
        epg = EpgUi(activity, OpenWebif(prefs), io, EpgStore(activity), host)
    }

    @After fun tearDown() {
        TvDialog.dismissAll()
        io.shutdownNow()
        server.close()
        serverThread.join(5000)
        controller.destroy()
    }

    private fun drain() {
        io.submit {}.get(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun searchAndOpenEvent() {
        EpgUi::class.java.getDeclaredMethod("search", String::class.java)
            .apply { isAccessible = true }.invoke(epg, "Test")
        drain()
        val list = findList(TvDialog.top()!!.window!!.decorView)!!
        list.performItemClick(list.adapter.getView(0, null, list), 0, list.adapter.getItemId(0))
    }

    private fun findList(view: View): ListView? {
        if (view is ListView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findList(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun clickButton(text: String) {
        fun find(view: View): Button? {
            if (view is Button && view.text.toString() == text) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                find(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        val button = find(TvDialog.top()!!.window!!.decorView)
        assertNotNull("Missing button $text", button)
        button!!.performClick()
    }

    private fun record() {
        clickButton(activity.getString(R.string.epg_record_receiver))
        clickButton(activity.getString(R.string.epg_record))
        drain()
    }

    @Test fun successfulRecordingClosesSearchBeforeShowingResult() {
        searchAndOpenEvent()
        record()
        assertNull(TvDialog.top())
        assertTrue(messages.last().first.contains(activity.getString(R.string.epg_recording_set)))
        assertFalse(messages.last().second)
    }

    @Test fun failedRecordingKeepsSearchAndShowsError() {
        success = false
        searchAndOpenEvent()
        record()
        assertNotNull(TvDialog.top())
        assertTrue(messages.last().first.contains(activity.getString(R.string.epg_recording_failed)))
        assertTrue(messages.last().second)
    }

    @Test fun recordingFromOtherEpgViewsDoesNotCloseTheirList() {
        val parent = TvDialog.list(activity, "EPG", listOf(TvDialog.Item("Program"))) {}
        val event = EpgEvent(42, begin, 3600, "Test programu", "", "", ref, "Kanał")
        epg.showEvent(event, null)
        record()
        assertTrue(parent.isShowing)
        assertTrue(messages.last().second)
    }

    @Test fun currentEventOffersExtraActionsWithoutEpg() {
        epg.eitOnly = true
        var clicked = false
        val channel = Channel(1, "Kanał", "1:0:1:FFE:456:789:0:0:0:0:")
        epg.showCurrentEvent(channel) { event ->
            assertNull(event)
            listOf(TvDialog.Btn(activity.getString(R.string.pip_add_channel)) { clicked = true })
        }
        drain()
        clickButton(activity.getString(R.string.pip_add_channel))
        assertTrue(clicked)
    }

    @Test fun receiverRecordingConfirmationUsesEpgTimeoutAndDoesNotRunAction() {
        Prefs(activity).apply { actionDialogSec = 5; confirmDialogSec = 0 }
        var returned = false
        val event = EpgEvent(42, begin, 3600, "Test programu", "", "", ref, "Kanał")
        val scheduled = ZapTimers.Timer(ref, "Kanał", event.title, begin, begin + 3600, 42)
        epg.showEvent(event, null, scheduledTimer = scheduled, onReturn = { returned = true })
        clickButton(activity.getString(R.string.epg_record_receiver))
        val dialog = TvDialog.top()!!
        dialog.onWindowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
        assertFalse(dialog.isShowing)
        assertNull(TvDialog.top())
        assertFalse(returned)
        assertTrue(messages.isEmpty())
    }

    @Test fun currentEventKeepsRecordingAndExtraActions() {
        epg.eitOnly = true
        val channel = Channel(1, "Kanał", "1:0:1:FFD:456:789:0:0:0:0:")
        val start = System.currentTimeMillis() / 1000 - 60
        EpgStore(activity).write(channel.ref, listOf(EpgEvent(1, start, 3600, "Program", "", "", channel.ref, channel.name)))
        var clicked = false
        epg.showCurrentEvent(channel) { event ->
            assertNotNull(event)
            listOf(TvDialog.Btn(activity.getString(R.string.pip_add_channel)) { clicked = true })
        }
        drain()
        val dialog = TvDialog.top()!!
        val recording = dialog.window!!.decorView.findViewWithTag<View>("chip_1")
        val pip = dialog.window!!.decorView.findViewWithTag<View>("chip2_0")
        assertNotNull(recording)
        assertNotNull(pip)
        assertSame(recording.parent, pip.parent)
        clickButton(activity.getString(R.string.pip_add_channel))
        assertTrue(clicked)
    }

    @Test fun removingScheduledSwitchFromSearchClosesResults() {
        ZapTimers.add(activity, ZapTimers.Timer(ref, "Kanał", "Test programu", begin, begin + 3600, 42))
        searchAndOpenEvent()
        clickButton(activity.getString(R.string.epg_remove_switch))
        clickButton(activity.getString(R.string.epg_remove))
        assertNull(TvDialog.top())
        assertFalse(ZapTimers.has(activity, ref, begin))
        assertTrue(messages.last().first.contains(activity.getString(R.string.epg_switch_removed)))
        assertFalse(messages.last().second)
    }
}
