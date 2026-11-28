package pl.jardol.e2stream

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.admin.DevicePolicyManager
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ustawienia jako lista obsługiwana pilotem: ▲▼ wybór, OK zmiana.
 * Zmiany zapisują się od razu; odtwarzacz przeładowuje się po wyjściu z ustawień.
 */
class SettingsActivity : Activity() {

    companion object {
        const val EXTRA_FOCUS_TIMESHIFT = "focus_timeshift"
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private lateinit var p: Prefs
    private lateinit var listView: ListView
    private val ui = Handler(Looper.getMainLooper())
    private var asking = false
    private var changed = false
    private var pendingWakeEnable = false
    private var wakePermissionFlow: String? = null
    private var fullScreenPermissionAsked = false
    private var overlayPermissionAsked = false
    private var sleepTestDialog: Dialog? = null
    private var sleepTestDeadline = 0L
    private enum class SleepTestMode { ADMIN, ACCESSIBILITY, PHILIPS, ANDROID_REMOTE }
    private class PhilipsRecordingException : Exception()
    private var sleepTestMode = SleepTestMode.ADMIN
    private var philipsSleepTest: PhilipsSleepTest? = null
    private var philipsBusy = false
    private var philipsPairingServer: PhilipsPairingServer? = null
    private var philipsPairingDialog: Dialog? = null
    private var androidRemoteTest: AndroidTvRemoteTest? = null
    private var androidRemoteServer: PhilipsPairingServer? = null
    private var androidRemoteDialog: Dialog? = null
    private var androidRemoteGeneration = 0
    private var shareSession: LocalShareSession? = null
    private var sleepPermissionPending = false
    private val adapter = Adapter()
    private lateinit var items: List<Item>
    private lateinit var store: EpgStore
    private var status = ""
    @Volatile private var discoveryThread: Thread? = null

    // ------------------------------------------------------------ model

    private sealed class Item(val label: String) {
        class Header(label: String) : Item(label)
        class Toggle(label: String, val get: () -> Boolean, val set: (Boolean) -> Unit) : Item(label)
        class Text(
            label: String, val get: () -> String, val set: (String) -> Unit,
            val input: Int = InputType.TYPE_CLASS_TEXT, val secret: Boolean = false, val hint: String = "",
        ) : Item(label)
        class Action(label: String, val value: () -> String = { "" }, val run: () -> Unit) : Item(label)
    }

    private fun num(label: String, get: () -> Int, set: (Int) -> Unit, range: IntRange) =
        Item.Text(label, { get().toString() }, { v -> v.trim().toIntOrNull()?.let { set(it.coerceIn(range)) } },
            InputType.TYPE_CLASS_NUMBER, hint = "${range.first}–${range.last}")

    /** Sekcje ustawień (pierwszy ekran) — po wejściu lista pozycji danej sekcji. */
    private var section: String? = null

    private val sections = listOf(
        Triple("Dekoder Enigma2", R.string.section_decoder, R.string.section_decoder_desc),
        Triple("Odtwarzanie i obraz", R.string.section_playback, R.string.section_playback_desc),
        Triple("Pamięć masowa", R.string.section_storage, R.string.section_storage_desc),
        Triple("Wygląd", R.string.section_appearance, R.string.section_appearance_desc),
        Triple("EPG i picony kanałów", R.string.section_epg, R.string.section_epg_desc),
        Triple("Ogólne", R.string.section_general, R.string.section_general_desc),
        Triple("Informacje i aktualizacje", R.string.section_updates, R.string.section_updates_desc),
        Triple("Logi i zrzuty", R.string.section_logs, R.string.section_logs_desc),
    )

    private fun buildItems(): List<Item> {
        val sec = section ?: return sections.map { (name, title, desc) ->
            Item.Action(getString(title), { getString(desc) }) { openSection(name) }
        }
        val title = sections.first { it.first == sec }.second
        return listOf<Item>(Item.Header(getString(title))) + when (sec) {
            "Pamięć masowa" -> storageItems()
            "Dekoder Enigma2" -> listOf(
                Item.Header(getString(R.string.setting_receiver)),
        Item.Text(getString(R.string.setting_receiver_ip_address), { p.host }, { p.host = it.trim(); checkPluginQuiet(); offerBackupNow() }, hint = "np. 192.168.1.20"),
        Item.Action(getString(R.string.setting_search_for_receivers_on_the_network)) { discover() },
        Item.Text(getString(R.string.receiver_search_subnet), { p.discoverySubnet }, { value ->
            val subnet = value.trim()
            if (subnet.isNotEmpty() && Discovery.subnetHosts(subnet) == null) {
                toast(getString(R.string.receiver_search_invalid_subnet))
            } else p.discoverySubnet = subnet
        }, hint = getString(R.string.receiver_search_subnet_hint)),
        num(getString(R.string.setting_openwebif_port), { p.httpPort }, { p.httpPort = it }, 1..65535),
        num(getString(R.string.setting_streaming_port), { p.streamPort }, { p.streamPort = it }, 1..65535),
        Item.Toggle(getString(R.string.setting_block_changes_when_the_receiver_is_unreachable), { p.blockOnDisconnect }, { p.blockOnDisconnect = it }),
        Item.Text(getString(R.string.setting_openwebif_username), { p.user }, { p.user = it.trim() }, hint = getString(R.string.settings_hint_no_login)),
        Item.Text(getString(R.string.setting_openwebif_password), { p.pass }, { p.pass = it }, secret = true),
                Item.Header(getString(R.string.setting_epg_scan_plugin_on_the_receiver)),
        Item.Action(getString(R.string.setting_plugin_status), { if (p.pluginOk) getString(R.string.settings_plugin_installed, p.pluginVersion) else getString(R.string.settings_plugin_missing) }) { checkPlugin() },
        num(getString(R.string.setting_plugin_port), { p.pluginPort }, { p.pluginPort = it }, 1024..65535),
        Item.Text(getString(R.string.setting_plugin_token), { p.pluginToken }, { p.pluginToken = it.trim() }, secret = true, hint = getString(R.string.settings_hint_no_token)),
                Item.Header(getString(R.string.setting_ftp_only_needed_without_the_epg_scan_plugin_on_the_rece)),
        Item.Text(getString(R.string.setting_ftp_username), { p.ftpUser }, { p.ftpUser = it.trim().ifEmpty { "root" } }),
        Item.Text(getString(R.string.setting_ftp_password), { p.ftpPass }, { p.ftpPass = it }, secret = true),
        Item.Text(getString(R.string.setting_ftp_directory_on_the_receiver), { p.ftpDir }, { p.ftpDir = it.trim().ifEmpty { Prefs.DEFAULT_FTP_DIR } }),
                Item.Header(getString(R.string.setting_receiver_synchronisation)),
        Item.Toggle(getString(R.string.setting_synchronise_settings_and_channel_data), { p.channelSync }, { p.channelSync = it }),
        Item.Text(getString(R.string.settings_device_name, p.deviceTag), { p.deviceName }, { p.deviceName = it.trim().removeSuffix("[${p.deviceTag}]").trim().ifEmpty { android.os.Build.MODEL } },
            hint = "np. Salon – Philips"),
        Item.Action(getString(R.string.setting_synchronise_now), { syncStatus() }) { syncNow() },
        Item.Action(getString(R.string.setting_import_settings_from_another_device)) { chooseProfile() },
            )
            "Odtwarzanie i obraz" -> listOf(
        Item.Action(getString(R.string.setting_playback_engine), { if (p.engine == "vlc") "VLC" else "ExoPlayer" }) { chooseEngine() },
    ) + (if (p.engine == "vlc") listOf(
        Item.Action(getString(R.string.setting_vlc_deinterlacing), { deintName(p.vlcDeint) }) { chooseDeint() },
        Item.Action(getString(R.string.setting_vlc_hardware_acceleration), { hwName(p.vlcHwMode) }) { chooseHw() },
        Item.Action(getString(R.string.setting_vlc_video_format_chroma), { p.vlcChroma }) { chooseChroma() },
    ) else listOf(
        Item.Toggle(getString(R.string.setting_video_tunnelling), { p.tunneling }, { p.tunneling = it }),
        Item.Toggle(getString(R.string.setting_restart_on_stuttering_dropped_frames), { p.dropRestart }, { p.dropRestart = it }),
        Item.Toggle(getString(R.string.setting_fast_channel_start), { p.fastStart }, { p.fastStart = it }),
        num(getString(R.string.setting_playback_buffer_ms), { p.rebufferMs }, { p.rebufferMs = it }, 1000..15_000),
        Item.Toggle(getString(R.string.setting_build_buffer_by_slowing_playback_slightly_5_without_tun), { p.cushionSlow }, { p.cushionSlow = it }),
    )) + listOf(
        Item.Toggle(getString(R.string.setting_keep_frame_on_zap), { p.keepFrame }, { p.keepFrame = it }),
        Item.Toggle(getString(R.string.setting_restart_frozen_video_watchdog), { p.watchdog }, { p.watchdog = it }),
        num(getString(R.string.setting_freeze_after_seconds_without_new_frames), { p.stallSec }, { p.stallSec = it }, 1..30),
        num(getString(R.string.setting_startup_buffer_ms_fast_picture), { p.bufferMs }, { p.bufferMs = it }, 250..10_000),
        Item.Action(getString(R.string.setting_screen_refresh_rate), { afrName(p.afrMode) }) { chooseAfr() },
                Item.Header(getString(R.string.setting_audio)),
        Item.Text(getString(R.string.setting_preferred_language), { p.audioLang }, { p.audioLang = it }, hint = "Kody lub nazwy, np. pl/pol/Polish, en/eng/English"),
        Item.Toggle(getString(R.string.setting_prefer_ac3_e_ac3), { p.preferAc3 }, { p.preferAc3 = it }),
        // przesunięcie dźwięku — wspólne dla VLC i ExoPlayera (z tunelowaniem i bez)
        Item.Action(getString(R.string.setting_av_shift), { avShiftLabel(this, p.avShiftMs) }) {
            val o = AvShift.values
            TvDialog.single(this, getString(R.string.setting_av_shift), o.map { avShiftLabel(this, it) }, o.indexOf(p.avShiftMs).coerceAtLeast(0)) { w ->
                p.avShiftMs = o[w]; AvShift.ms = o[w]; markChanged("przesunięcie dźwięku = ${o[w]} ms"); items = buildItems(); refresh()
            }
        },
            )
            "Wygląd" -> listOf(
                Item.Header(getString(R.string.setting_channel_list)),
                pctItem(getString(R.string.setting_channel_list_font), { p.uiListFont }, { p.uiListFont = it }),
                pctItem(getString(R.string.setting_channel_list_picons), { p.uiListIcon }, { p.uiListIcon = it }),
                Item.Header(getString(R.string.setting_epg_events_channel_epg_multi_epg_search)),
                pctItem(getString(R.string.setting_epg_window_font), { p.uiEpgFont }, { p.uiEpgFont = it }),
                Item.Header(getString(R.string.setting_channel_banner)),
                pctItem(getString(R.string.setting_channel_banner_font), { p.uiBannerFont }, { p.uiBannerFont = it }),
                pctItem(getString(R.string.setting_channel_banner_picon), { p.uiBannerIcon }, { p.uiBannerIcon = it }),
            )
            "EPG i picony kanałów" -> listOf(
        Item.Action(getString(R.string.setting_epg_fetch), {
            getString(when (p.epgFetch) { EpgUpdatePolicy.STARTUP -> R.string.settings_every_start; EpgUpdatePolicy.DAILY -> R.string.settings_daily; else -> R.string.update_mode_off })
        }) {
            val modes = listOf("off", EpgUpdatePolicy.DAILY, EpgUpdatePolicy.STARTUP)
            TvDialog.single(this, getString(R.string.setting_epg_fetch),
                listOf(getString(R.string.update_mode_off), getString(R.string.settings_daily), getString(R.string.settings_every_start)),
                modes.indexOf(p.epgFetch).coerceAtLeast(0)) { w ->
                p.epgFetch = modes[w]
                markChanged("pobieranie EPG = ${modes[w]}")
                items = buildItems(); refresh()
            }
        },
    ) + (if (p.epgFetch == EpgUpdatePolicy.DAILY) listOf<Item>(
        Item.Text(getString(R.string.setting_epg_download_time), { p.epgHour },
            { v -> if (Regex("^\\d{1,2}:\\d{2}$").matches(v.trim())) p.epgHour = v.trim() else toast(getString(R.string.message_format_hh_mm)) },
            InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME, hint = "HH:MM"),
    ) else emptyList()) + listOf<Item>(
        num(getString(R.string.setting_days_of_epg_to_download), { p.epgDays }, { p.epgDays = it }, 1..14),
        Item.Toggle(getString(R.string.setting_eit_from_stream), { p.eitStream }, { v ->
            if (!v) { p.eitStream = false; items = buildItems(); refresh() } else enableStreamEit()
        }),
    ) + (if (p.eitStream) listOf<Item>(
        Item.Toggle(getString(R.string.setting_eit_replace_epg), { p.eitReplace }, { p.eitReplace = it }),
    ) + (if (BuildConfig.BETA) listOf<Item>(
        Item.Toggle(getString(R.string.setting_eit_only), { p.eitOnly }, { v ->
            p.eitOnly = v
            if (v) {
                val n = EpgStore(this).clear()
                AppLog.i("EPG: tryb testowy EIT — usunięto zapisane EPG ($n kanałów); pobieranie z dekodera wyłączone")
                toast(getString(R.string.message_eit_only_on, n))
            }
        }),
    ) else emptyList()) else emptyList()) + listOf(
        Item.Toggle(getString(R.string.setting_channel_picons_from_receiver), { p.picons }, { p.picons = it }),
        Item.Toggle(getString(R.string.setting_replace_missing_picons_with_e2stream_logo), { p.missingPiconLogo }, { p.missingPiconLogo = it }),
        Item.Text(getString(R.string.setting_picon_directory_on_receiver), { p.piconDir }, { p.piconDir = it.trim().trimEnd('/') },
            hint = getString(R.string.settings_hint_auto_directory)),
        Item.Action(getString(R.string.setting_download_epg_and_picons_now), { status.ifBlank { epgStatus() } }) { sync(false) },
        Item.Action(getString(R.string.setting_delete_and_download_icons_again)) { sync(true) },
            )
            "Ogólne" -> listOf(
        Item.Action(getString(R.string.app_language), {
            getString(when (p.appLanguage) {
                AppLanguage.POLISH -> R.string.language_polish
                AppLanguage.ENGLISH -> R.string.language_english
                else -> R.string.language_system
            })
        }) {
            val languages = listOf(AppLanguage.SYSTEM, AppLanguage.POLISH, AppLanguage.ENGLISH)
            TvDialog.single(this, getString(R.string.app_language),
                listOf(getString(R.string.language_system), getString(R.string.language_polish), getString(R.string.language_english)),
                languages.indexOf(p.appLanguage).coerceAtLeast(0)) { selected ->
                if (p.appLanguage != languages[selected]) {
                    p.appLanguage = languages[selected]
                    markChanged("język aplikacji = ${p.appLanguage}")
                    recreate()
                }
            }
        },
        Item.Action(getString(R.string.setting_visible_bouquets), { if (p.hiddenBouquets.isEmpty()) getString(R.string.settings_all) else getString(R.string.settings_hidden_count, p.hiddenBouquets.size) }) { chooseBouquets() },
        Item.Action(getString(R.string.setting_menu_items_and_order)) { showQuickMenuSettings() },
        Item.Action(getString(R.string.setting_remote_control_key_mapping)) { showKeyMap() },
        Item.Action(getString(R.string.setting_map_ok_and_arrows_without_banner)) { showNavigationMap(false) },
        Item.Action(getString(R.string.setting_map_ok_and_arrows_with_channel_banner)) { showNavigationMap(true) },
        Item.Action(getString(R.string.setting_sleep_timer), { sleepLabel(p.sleepHours) }) {
            val o = listOf(0) + Prefs.SLEEP_HOURS
            TvDialog.single(this, getString(R.string.setting_sleep_timer), o.map { sleepLabel(it) }, o.indexOf(p.sleepHours).coerceAtLeast(0)) { w ->
                p.sleepHours = o[w]; markChanged("wyłączenie po bezczynności = ${o[w]} h"); items = buildItems(); refresh()
            }
        },
        Item.Action(getString(R.string.sleep_device_title), {
            getString(if (!p.sleepDeviceOnIdle) R.string.sleep_device_off else if (sleepAdminActive()) R.string.sleep_device_on else R.string.sleep_test_status_permission)
        }) { showSleepDeviceSettings() },
        *(if (BuildConfig.BETA) arrayOf<Item>(Item.Action(getString(R.string.sleep_test_title), {
            getString(if (sleepAdminActive()) R.string.sleep_test_status_ready else R.string.sleep_test_status_permission)
        }) { showSleepTest() }, Item.Action(getString(R.string.sleep_accessibility_title), {
            getString(if (SleepAccessibilityTestService.isConnected()) R.string.sleep_test_status_ready else R.string.sleep_accessibility_not_connected)
        }) { showSleepAccessibilityTest() }, Item.Action(getString(R.string.sleep_philips_title), {
            getString(if (philipsSleepTest != null) R.string.sleep_test_status_ready else R.string.sleep_philips_unpaired)
        }) { showPhilipsSleepTest() }, Item.Action(getString(R.string.sleep_remote_title), {
            getString(if (androidRemoteTest != null) R.string.sleep_test_status_ready else R.string.sleep_philips_unpaired)
        }) { showAndroidRemoteTest() }) else emptyArray()),
        Item.Toggle(getString(R.string.setting_resume_last_channel_on_startup), { p.resumeLast }, { p.resumeLast = it }),
        Item.Action(getString(R.string.setting_switch_to_channel_on_startup), {
            if (p.resumeLast) getString(R.string.settings_start_disabled) else p.startChannelName.ifBlank { getString(R.string.settings_first_channel) }
        }) { if (p.resumeLast) toast(getString(R.string.message_first_disable_resume_last_channel_on_startup)) else chooseStartChannel() },
        Item.Toggle(getString(R.string.setting_wake_tv_for_scheduled_switches_experimental), { p.wakeForZap }, { setWakeForZap(it) }),
        Item.Action(getString(R.string.setting_wake_schedule_monitor), { wakeMonitorLabel(p.wakeZapMonitorSeconds) }) {
            val seconds = Prefs.WAKE_ZAP_MONITOR_SECONDS
            TvDialog.single(this, getString(R.string.setting_wake_schedule_monitor),
                seconds.map { wakeMonitorLabel(it) }, seconds.indexOf(p.wakeZapMonitorSeconds).coerceAtLeast(0)) { selected ->
                p.wakeZapMonitorSeconds = seconds[selected]
                ZapWakeAlarms.sync(this)
                markChanged("monit harmonogramu = ${seconds[selected]} s")
                items = buildItems()
                refresh()
            }
        },
        Item.Action(getString(R.string.setting_wake_popup_position), { wakeOverlayPositionLabel(p.wakeOverlayPosition) }) {
            val positions = Prefs.WAKE_OVERLAY_POSITIONS
            TvDialog.single(this, getString(R.string.setting_wake_popup_position),
                positions.map { wakeOverlayPositionLabel(it) }, positions.indexOf(p.wakeOverlayPosition).coerceAtLeast(0)) { selected ->
                p.wakeOverlayPosition = positions[selected]
                markChanged("położenie popupu harmonogramu = ${positions[selected]}")
                items = buildItems()
                refresh()
            }
        },
        Item.Action(getString(R.string.setting_wake_overlay_permission), {
            getString(if (ZapWakeAlarms.canDrawOverlays(this)) R.string.wake_allowed else R.string.wake_no_permission)
        }) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !ZapWakeAlarms.canDrawOverlays(this)) {
                try {
                    wakePermissionFlow = "overlay-manual"
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    wakePermissionFlow = null
                    toast(getString(R.string.message_overlay_permission_not_granted))
                }
            }
        },
        *(if (BuildConfig.WAKE_DIAGNOSTICS) arrayOf<Item>(Item.Action(getString(R.string.setting_wake_up_diagnostics), { getString(if (p.wakeForZap) R.string.settings_status_alarm else R.string.settings_enable_wake_first) }) {
            TvDialog.confirm(this, getString(R.string.message_wake_up_diagnostics), ZapWakeAlarms.diagnostics(this), listOf(
                TvDialog.Btn(getString(R.string.message_60_s_test)) { toast(ZapWakeAlarms.scheduleTest(this)) },
                TvDialog.Btn(getString(R.string.message_send_log)) { Tools.uploadLog(this, p, true) { toast(it) } },
                TvDialog.Btn(getString(R.string.message_close)),
            ))
        }) else emptyArray()),
        Item.Action(getString(R.string.setting_remove_completed_switches_after), {
            if (p.zapHistoryDays == 0) getString(R.string.settings_never_clean) else getString(R.string.settings_after_days, p.zapHistoryDays)
        }) {
            val days = listOf(7, 14, 28, 0)
            TvDialog.single(this, getString(R.string.message_remove_completed_switches_after), listOf(getString(R.string.settings_after_days, 7), getString(R.string.settings_after_days, 14), getString(R.string.settings_after_days, 28), getString(R.string.settings_never_clean)),
                days.indexOf(p.zapHistoryDays).coerceAtLeast(0)) { selected ->
                p.zapHistoryDays = days[selected]
                markChanged("harmonogram: historia ${if (days[selected] == 0) "bez limitu" else "${days[selected]} dni"}")
            }
        },
        Item.Toggle(getString(R.string.setting_reverse_when_changing_channels), { p.invertUpDown }, { p.invertUpDown = it }),
        num(getString(R.string.setting_channel_banner_duration_s), { p.bannerSec }, { p.bannerSec = it }, 1..30),
        Item.Toggle(getString(R.string.setting_message_bar), { p.messageBar }, { p.messageBar = it; items = buildItems() }),
        *(if (p.messageBar) arrayOf(num(getString(R.string.setting_message_bar_duration), { p.messageBarSec }, { p.messageBarSec = it }, 1..10)) else emptyArray()),
        num(getString(R.string.setting_timeshift_banner_duration_s), { p.tsBannerSec }, { p.tsBannerSec = it }, 1..30),
        num(getString(R.string.setting_channel_list_duration_s_0_no_timeout), { p.channelListSec }, { p.channelListSec = it }, 0..30),
        num(getString(R.string.setting_action_dialog_duration_s_0_no_timeout), { p.actionDialogSec }, { p.actionDialogSec = it }, 0..30),
        num(getString(R.string.setting_confirmation_dialog_duration_s_0_no_timeout), { p.confirmDialogSec }, { p.confirmDialogSec = it }, 0..30),
        num(getString(R.string.setting_channel_epg_duration_s_0_no_timeout), { p.epgChannelSec }, { p.epgChannelSec = it }, 0..30),
        num(getString(R.string.setting_multi_epg_duration_s_0_no_timeout), { p.epgMultiSec }, { p.epgMultiSec = it }, 0..30),
        num(getString(R.string.setting_information_dialog_duration_s_0_no_timeout), { p.infoDialogSec }, { p.infoDialogSec = it }, 0..30),
        Item.Toggle(getString(R.string.setting_show_information_dialogs), { !p.hideInfoDialogs }, { p.hideInfoDialogs = !it }),
        Item.Text(getString(R.string.setting_settings_pin), { p.pin }, { p.pin = it.trim() },
            InputType.TYPE_CLASS_NUMBER, secret = true, hint = "maks. 6 cyfr; puste = bez blokady"),
        Item.Action(getString(R.string.setting_ask_for_pin_again_after), {
            if (p.pin.isEmpty()) getString(R.string.settings_pin_inactive) else PinLock.label(this, p.pinMin) + getString(R.string.settings_pin_suffix)
        }) { if (p.pin.isEmpty()) toast(getString(R.string.message_set_a_pin_first)) else choosePinTime() },
            )

            "Informacje i aktualizacje" -> listOf(
                Item.Header(getString(R.string.setting_e2stream_application)),
        Item.Action(getString(R.string.setting_update_channel), { getString(if (p.updateChannel == "beta") R.string.settings_beta else R.string.settings_stable) }) {
            TvDialog.single(this, getString(R.string.message_update_channel), listOf(getString(R.string.update_channel_stable), "Beta (prerelease)"),
                if (p.updateChannel == "beta") 1 else 0) { w ->
                p.updateChannel = if (w == 1) "beta" else "stable"
                markChanged("kanał aktualizacji = ${p.updateChannel}")
            }
        },
        Item.Action(getString(R.string.setting_check_for_updates), { getString(R.string.settings_version, Tools.appVersion(this)) }) { Tools.checkUpdate(this, true) { toast(it) } },
        Item.Action(getString(R.string.setting_update_mode), { updateModeLabel(p.updateMode) }) {
            val o = Prefs.UPDATE_MODES
            TvDialog.single(this, getString(R.string.setting_update_mode), o.map { updateModeLabel(it) }, o.indexOf(p.updateMode).coerceAtLeast(0)) { w ->
                p.updateMode = o[w]; markChanged("aktualizacja automatyczna = ${o[w]}"); items = buildItems(); refresh()
            }
        },
                Item.Action(getString(R.string.setting_application_information_readme)) { showReadme("E2Stream", "e2stream/README.md") },
                Item.Header(getString(R.string.setting_epg_scan_plugin)),
                Item.Action(getString(R.string.setting_plugin_on_receiver), { if (p.pluginOk) getString(R.string.settings_version, p.pluginVersion) else getString(R.string.settings_plugin_absent_details) }) { checkPlugin() },
                Item.Action(getString(R.string.setting_plugin_information_readme)) { showReadme("EPG Scan", "epgscan/EPGScan/README.md") },
            )
            else -> listOfNotNull(
        Item.Toggle(getString(R.string.setting_on_screen_statistics), { p.showStats }, { p.showStats = it }),
        if (BuildConfig.BETA) Item.Action(getString(R.string.share_settings_action)) { shareDiagnostics() } else null,
                if (p.pluginOk) null else Item.Action(getString(R.string.setting_without_epg_scan_sending_to_the_receiver_requires_ftp),
                    { getString(R.string.settings_set_decoder) }) { openSection("Dekoder Enigma2") },
        Item.Action(getString(R.string.setting_send_log_to_receiver), { "${p.ftpDir}/${Tools.logName(p)}" }) { Tools.uploadLog(this, p, true) { toast(it) } },
        Item.Action(getString(R.string.setting_send_ts_dumps_to_receiver)) { Tools.uploadDumps(this, p) { toast(it) } },
        Item.Toggle(getString(R.string.setting_clean_stream_damaged_packets_invalid_timestamps), { p.cleanTs }, { p.cleanTs = it }),
        Item.Toggle(getString(R.string.setting_save_ts_dump_on_freeze), { p.saveTs }, { p.saveTs = it }),
        num(getString(R.string.setting_ts_dump_size_mb), { p.tsRingMb }, { p.tsRingMb = it }, 2..64),
        Item.Toggle(getString(R.string.setting_send_log_to_receiver_every_2_minutes), { p.logFtp }, { p.logFtp = it }),
        Item.Toggle(getString(R.string.setting_send_ts_dumps_to_receiver_2), { p.tsFtp }, { p.tsFtp = it }),
            )
        }
    }

    /** README z GitHuba (kopia aktualnej wersji opisu) w przewijanym oknie. */
    private fun showReadme(title: String, path: String) {
        toast(getString(R.string.message_loading_information))
        Thread {
            val text = try {
                val c = java.net.URL("https://raw.githubusercontent.com/JarDol/enigma2-epgscan/main/$path").openConnection()
                    as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 15000
                c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (e: Exception) { getString(R.string.service_description_error, e.message ?: e.toString()) }
            // prosty tekst z Markdown: bez znaczników nagłówków, pogrubień i kodu
            val plain = text.lines().joinToString("\n") { l ->
                l.replace(Regex("^#{1,6}\\s*"), "").replace("**", "").replace("`", "")
                    .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                TvDialog.text(this, getString(R.string.service_description, title), plain)
            }
        }.start()
    }

    private fun pctItem(label: String, get: () -> Int, set: (Int) -> Unit) =
        Item.Action(label, { "${get()}%" }) {
            val opts = intArrayOf(50, 60, 70, 80, 90, 100, 110, 120, 140)
            TvDialog.single(this, label, opts.map { "$it%" }, opts.indexOf(get())) { w ->
                set(opts[w]); markChanged("$label = ${opts[w]}%")
            }
        }

    private fun openSection(name: String) {
        section = name
        if (name == "Dekoder Enigma2" || name == "Informacje i aktualizacje" || name == "Logi i zrzuty") checkPluginQuiet()
        items = buildItems()
        refresh()
        listView.setSelection(items.indexOfFirst { it !is Item.Header }.coerceAtLeast(0))
    }

    private fun quickMenuEntriesInOrder(): MutableList<QuickMenu.Entry> {
        return QuickMenu.allInOrder(p.quickMenuOrder).toMutableList()
    }

    private fun showQuickMenuSettings(selected: Int = 0) {
        val entries = quickMenuEntriesInOrder()
        val visible = p.quickMenuVisible.toMutableSet()
        val rows = entries.mapIndexed { index, entry ->
            TvDialog.Item(getString(entry.labelRes), "${index + 1}.  ${getString(if (entry.id in visible) R.string.settings_visible else R.string.settings_hidden)}")
        }
        TvDialog.list(this, getString(R.string.message_menu_items_and_order), rows, selected.coerceIn(rows.indices),
            hint = getString(R.string.settings_options_hint), widthFrac = 0.6f) { index ->
            val entry = entries[index]
            val options = buildList {
                add(if (entry.id in visible) R.string.settings_hide_item else R.string.settings_show_item)
                if (index > 0) add(R.string.settings_move_up)
                if (index < entries.lastIndex) add(R.string.settings_move_down)
            }
            TvDialog.list(this, getString(entry.labelRes), options.map { TvDialog.Item(getString(it)) },
                hint = getString(R.string.epg_hint_actions), widthFrac = 0.5f) { choice ->
                when (options[choice]) {
                    R.string.settings_hide_item -> visible.remove(entry.id)
                    R.string.settings_show_item -> visible.add(entry.id)
                    R.string.settings_move_up -> entries.add(index - 1, entries.removeAt(index))
                    R.string.settings_move_down -> entries.add(index + 1, entries.removeAt(index))
                }
                p.quickMenuVisible = visible
                QuickMenu.saveOrder(p, entries)
                markChanged("menu: ${getString(entry.labelRes)} ${getString(options[choice]).lowercase()}")
                showQuickMenuSettings(entries.indexOfFirst { it.id == entry.id })
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val s = section
        if (s != null) {
            section = null
            items = buildItems()
            refresh()
            listView.setSelection(sections.indexOfFirst { it.first == s }.coerceAtLeast(0))
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun afrName(m: Int) = getString(when (m) { 1 -> R.string.settings_refresh_50; 2 -> R.string.settings_refresh_auto; else -> R.string.settings_refresh_unchanged })

    private fun epgStatus(): String {
        val at = p.lastEpgFetch
        val last = if (at > 0) SimpleDateFormat("dd.MM HH:mm", Locale.forLanguageTag(AppLanguage.selected(this))).format(Date(at)) else getString(R.string.settings_never)
        return getString(R.string.settings_epg_status, last, store.count())
    }

    // ------------------------------------------------------------ cykl życia

    private val relock = object : Runnable {
        override fun run() {
            if (!isFinishing && !asking && !PinLock.isUnlocked(p)) {
                asking = true
                PinLock.ask(this@SettingsActivity, p, cancelable = false,
                    onCancel = { asking = false; finish() }) { asking = false }
            }
            ui.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sleepPermissionPending = savedInstanceState?.getBoolean("sleepPermissionPending") ?: false
        setContentView(R.layout.activity_settings)
        p = Prefs(this)
        p.migrate()
        AppLog.init(this, Storage.dir(this, p))
        store = EpgStore(this)
        items = buildItems()
        findViewById<TextView>(R.id.version).text = getString(R.string.settings_screen_title, Tools.appVersion(this))
        listView = findViewById(R.id.settingsList)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ -> onPick(items[pos]) }
        listView.setSelection(0)
        listView.requestFocus()
        if (intent.getBooleanExtra(EXTRA_FOCUS_TIMESHIFT, false)) {
            openSection("Pamięć masowa")
            val index = items.indexOfFirst { it is Item.Action && it.label == getString(R.string.setting_timeshift) }
            check(index >= 0) { "Brak ustawienia timeshiftu" }
            listView.setSelection(index)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("sleepPermissionPending", sleepPermissionPending)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (sleepPermissionPending) {
            sleepPermissionPending = false
            p.sleepDeviceOnIdle = sleepAdminActive()
            if (p.sleepDeviceOnIdle) markChanged("uśpienie po bezczynności = włączone")
            else toast(getString(R.string.sleep_test_permission_missing))
        }
        if (::items.isInitialized) refresh()
        ui.removeCallbacks(relock)
        ui.postDelayed(relock, 2000)
        when (wakePermissionFlow) {
            "exact" -> {
                wakePermissionFlow = null
                if (ZapWakeAlarms.canScheduleExact(this)) continueWakeEnable()
                else { pendingWakeEnable = false; toast(getString(R.string.message_permission_for_exact_alarms_was_not_granted)) }
            }
            "fullscreen" -> {
                wakePermissionFlow = null
                fullScreenPermissionAsked = true
                continueWakeEnable()
            }
            "overlay" -> {
                wakePermissionFlow = null
                overlayPermissionAsked = true
                continueWakeEnable()
            }
            "overlay-manual" -> {
                wakePermissionFlow = null
                items = buildItems()
                refresh()
            }
        }
    }

    private fun setWakeForZap(enabled: Boolean) {
        if (!enabled) {
            pendingWakeEnable = false
            p.wakeForZap = false
            ZapWakeAlarms.sync(this)
            toast(getString(R.string.message_scheduled_wake_up_disabled))
            return
        }
        pendingWakeEnable = true
        fullScreenPermissionAsked = false
        overlayPermissionAsked = false
        continueWakeEnable()
    }

    private fun continueWakeEnable() {
        if (!pendingWakeEnable) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !ZapWakeAlarms.canScheduleExact(this)) {
            try {
                wakePermissionFlow = "exact"
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                wakePermissionFlow = null
                pendingWakeEnable = false
                toast(getString(R.string.message_this_device_does_not_support_exact_alarms))
            }
            return
        }
        if (Build.VERSION.SDK_INT >= 34 && !ZapWakeAlarms.canUseFullScreen(this) && !fullScreenPermissionAsked) {
            try {
                wakePermissionFlow = "fullscreen"
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:$packageName")))
                return
            } catch (_: Exception) {
                wakePermissionFlow = null
                fullScreenPermissionAsked = true
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 741)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !ZapWakeAlarms.canDrawOverlays(this) && !overlayPermissionAsked) {
            try {
                wakePermissionFlow = "overlay"
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
                return
            } catch (_: Exception) {
                wakePermissionFlow = null
                overlayPermissionAsked = true
            }
        }
        pendingWakeEnable = false
        p.wakeForZap = true
        ZapWakeAlarms.sync(this)
        markChanged("Budź TV dla harmonogramu = włączone")
        toast(getString(R.string.message_scheduled_wake_up_enabled))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !ZapWakeAlarms.canDrawOverlays(this)) {
            toast(getString(R.string.message_overlay_permission_not_granted))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 741) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) continueWakeEnable()
        else { pendingWakeEnable = false; toast(getString(R.string.message_without_notifications_reminders_may_not_appear_on_scree)) }
    }

    override fun onPause() {
        super.onPause()
        cancelSleepTest()
        ui.removeCallbacks(relock)
        if (changed) { p.bump(); changed = false }
        SyncManager.syncAsync() // zmiany od razu na dekoder
    }

    override fun onDestroy() {
        stopPhilipsPairing()
        stopAndroidRemotePairing()
        shareSession?.stop()
        discoveryThread?.interrupt()
        super.onDestroy()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun shareDiagnostics() {
        shareSession?.stop()
        val backup = org.json.JSONObject()
        for ((key, value) in SettingsBackup.values(p)) {
            backup.put(key, if (value is Set<*>) org.json.JSONArray(value) else value)
        }
        val data = org.json.JSONObject().put("device", org.json.JSONObject().put("backup", backup))
            .toString(2).toByteArray(Charsets.UTF_8)
        val log = try { AppLog.snapshot(markClean = false) } catch (e: java.io.IOException) {
            AppLog.i("local share: cannot read log (${e.javaClass.simpleName})")
            toast(getString(R.string.share_failed))
            return
        }
        val entries = listOf(
            LocalShareServer.Entry("e2stream.log", log.size.toLong()) { log.inputStream() },
            LocalShareServer.Entry("e2stream-settings.json", data.size.toLong()) { data.inputStream() },
        )
        shareSession = LocalShareSession.start(this, entries, R.string.share_warning)
        if (shareSession == null) toast(getString(R.string.share_failed))
    }

    private fun wakeMonitorLabel(seconds: Int) = when (seconds) {
        0 -> getString(R.string.settings_wake_monitor_off)
        60 -> getString(R.string.settings_wake_monitor_minute, 1)
        120 -> getString(R.string.settings_wake_monitor_minute, 2)
        300 -> getString(R.string.settings_wake_monitor_minute, 5)
        else -> getString(R.string.settings_wake_monitor_seconds, seconds)
    }

    private fun wakeOverlayPositionLabel(position: Int) = getString(when (position) {
        Prefs.WAKE_OVERLAY_TOP_LEFT -> R.string.settings_wake_overlay_top_left
        Prefs.WAKE_OVERLAY_BOTTOM_LEFT -> R.string.settings_wake_overlay_bottom_left
        Prefs.WAKE_OVERLAY_TOP_RIGHT -> R.string.settings_wake_overlay_top_right
        Prefs.WAKE_OVERLAY_CENTER -> R.string.settings_wake_overlay_center
        else -> R.string.settings_wake_overlay_bottom_right
    })

    private fun refresh() = adapter.notifyDataSetChanged()

    private fun sleepAdmin() = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private fun sleepAdminComponent() = ComponentName(this, SleepTestAdminReceiver::class.java)
    private fun sleepAdminActive() = sleepAdmin().isAdminActive(sleepAdminComponent())

    private fun showSleepDeviceSettings() {
        val actions = mutableListOf<TvDialog.Btn>()
        if (p.sleepDeviceOnIdle) {
            actions += TvDialog.Btn(getString(R.string.sleep_device_disable)) {
                p.sleepDeviceOnIdle = false
                markChanged("uśpienie po bezczynności = wyłączone")
                refresh()
            }
        }
        if (!p.sleepDeviceOnIdle || !sleepAdminActive()) {
            actions += TvDialog.Btn(getString(R.string.sleep_device_enable)) {
                if (!packageManager.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN)) {
                    toast(getString(R.string.sleep_test_unsupported))
                } else if (sleepAdminActive()) {
                    p.sleepDeviceOnIdle = true
                    markChanged("uśpienie po bezczynności = włączone")
                    refresh()
                } else {
                    try {
                        sleepPermissionPending = true
                        startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, sleepAdminComponent())
                            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.sleep_device_permission_reason)))
                    } catch (e: ActivityNotFoundException) {
                        sleepPermissionPending = false
                        toast(getString(R.string.sleep_test_permission_failed, e.message ?: e.toString()))
                    } catch (e: SecurityException) {
                        sleepPermissionPending = false
                        toast(getString(R.string.sleep_test_permission_failed, e.message ?: e.toString()))
                    }
                }
            }
        }
        if (sleepAdminActive()) actions += TvDialog.Btn(getString(R.string.sleep_test_revoke)) {
            sleepAdmin().removeActiveAdmin(sleepAdminComponent())
            p.sleepDeviceOnIdle = false
            markChanged("uśpienie po bezczynności = wyłączone")
            refresh()
            toast(getString(R.string.sleep_test_revoked))
        }
        TvDialog.confirm(this, getString(R.string.sleep_device_title), getString(R.string.sleep_device_explain), actions)
    }

    private fun showSleepTest() {
        if (AppState.recording) { toast(getString(R.string.sleep_test_recording)); return }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN)) {
            toast(getString(R.string.sleep_test_unsupported)); return
        }
        if (!sleepAdminActive()) {
            TvDialog.confirm(this, getString(R.string.sleep_test_title), getString(R.string.sleep_test_explain),
                listOf(TvDialog.Btn(getString(R.string.sleep_test_permission)) {
                    try {
                        startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, sleepAdminComponent())
                            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.sleep_test_permission_reason)))
                    } catch (e: ActivityNotFoundException) {
                        toast(getString(R.string.sleep_test_permission_failed, e.message ?: e.toString()))
                    } catch (e: SecurityException) {
                        toast(getString(R.string.sleep_test_permission_failed, e.message ?: e.toString()))
                    }
                }))
            return
        }
        val actions = mutableListOf<TvDialog.Btn>()
        if (BuildConfig.BETA) actions += TvDialog.Btn(getString(R.string.sleep_test_start)) { startSleepTest() }
        actions += TvDialog.Btn(getString(R.string.sleep_test_revoke)) {
                    sleepAdmin().removeActiveAdmin(sleepAdminComponent())
                    p.sleepDeviceOnIdle = false
                    markChanged("uśpienie po bezczynności = wyłączone")
                    items = buildItems()
                    refresh()
                    toast(getString(R.string.sleep_test_revoked))
                }
        TvDialog.confirm(this, getString(R.string.sleep_test_title), getString(R.string.sleep_test_ready), actions)
    }

    private fun showSleepAccessibilityTest() {
        if (AppState.recording || LocalRecordings.activeDirs.isNotEmpty()) { toast(getString(R.string.sleep_test_recording)); return }
        val actions = mutableListOf(TvDialog.Btn(getString(R.string.sleep_accessibility_settings)) {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                toast(getString(R.string.sleep_test_permission_failed, e.message ?: e.toString()))
            } catch (e: SecurityException) {
                toast(getString(R.string.sleep_test_permission_failed, e.message ?: e.toString()))
            }
        })
        if (SleepAccessibilityTestService.isConnected()) {
            actions += TvDialog.Btn(getString(R.string.sleep_test_start)) { startSleepAccessibilityTest() }
        }
        TvDialog.confirm(this, getString(R.string.sleep_accessibility_title), getString(R.string.sleep_accessibility_explain), actions)
    }

    private fun showPhilipsSleepTest() {
        if (AppState.recording || LocalRecordings.activeDirs.isNotEmpty()) { toast(getString(R.string.sleep_test_recording)); return }
        if (philipsBusy) { toast(getString(R.string.sleep_philips_busy)); return }
        val actions = mutableListOf(TvDialog.Btn(getString(R.string.sleep_philips_pair)) { startPhilipsPairing() })
        if (philipsSleepTest != null) actions += TvDialog.Btn(getString(R.string.sleep_test_start)) {
            if (AppState.recording || LocalRecordings.activeDirs.isNotEmpty()) toast(getString(R.string.sleep_test_recording))
            else startSleepCountdown(SleepTestMode.PHILIPS)
        }
        TvDialog.confirm(this, getString(R.string.sleep_philips_title), getString(R.string.sleep_philips_explain), actions)
    }

    private fun startPhilipsPairing() {
        stopPhilipsPairing()
        philipsSleepTest = null
        val address = try { PhilipsSleepTest.deviceAddress() } catch (e: java.net.SocketException) {
            AppLog.i("Philips pairing: cannot determine local address: ${e.javaClass.simpleName}")
            ""
        }
        if (address.isEmpty()) { toast(getString(R.string.sleep_philips_no_network)); return }
        val client = PhilipsSleepTest("127.0.0.1")
        val texts = PhilipsPairingServer.Texts(
            getString(R.string.sleep_philips_title), getString(R.string.sleep_philips_show_pin),
            getString(R.string.sleep_philips_pin_hint), getString(R.string.sleep_philips_submit),
            getString(R.string.sleep_philips_retry), getString(R.string.sleep_philips_failed),
            getString(R.string.sleep_philips_paired))
        lateinit var server: PhilipsPairingServer
        try {
            server = PhilipsPairingServer(address, client, texts, onPaired = {
                ui.post {
                    if (philipsPairingServer === server && !isFinishing && !isDestroyed) {
                        philipsPairingServer = null
                        philipsPairingDialog?.dismiss()
                        philipsPairingDialog = null
                        philipsSleepTest = client
                        refresh()
                        toast(getString(R.string.sleep_philips_paired))
                        showPhilipsSleepTest()
                    }
                }
            }, onFinished = { success ->
                if (!success) ui.post {
                    if (philipsPairingServer === server && !isFinishing && !isDestroyed) {
                        stopPhilipsPairing()
                        toast(getString(R.string.sleep_philips_expired))
                    }
                }
            })
        } catch (e: java.io.IOException) {
            AppLog.i("Philips pairing server failed: ${e.javaClass.simpleName}")
            toast(getString(R.string.sleep_philips_no_network))
            return
        }
        philipsPairingServer = server
        philipsPairingDialog = LinkDialog.show(this, getString(R.string.sleep_philips_title),
            getString(R.string.sleep_philips_browser_instructions, server.url),
            server.url) { stopPhilipsPairing() }
        server.start()
    }

    private fun stopPhilipsPairing() {
        philipsPairingServer?.stop()
        philipsPairingServer = null
        philipsPairingDialog?.dismiss()
        philipsPairingDialog = null
    }

    private fun showAndroidRemoteTest() {
        if (AppState.recording || LocalRecordings.activeDirs.isNotEmpty()) { toast(getString(R.string.sleep_test_recording)); return }
        if (philipsBusy) { toast(getString(R.string.sleep_philips_busy)); return }
        val actions = mutableListOf(TvDialog.Btn(getString(R.string.sleep_philips_pair)) { startAndroidRemotePairing() })
        if (androidRemoteTest != null) actions += TvDialog.Btn(getString(R.string.sleep_test_start)) {
            startSleepCountdown(SleepTestMode.ANDROID_REMOTE)
        }
        TvDialog.confirm(this, getString(R.string.sleep_remote_title), getString(R.string.sleep_remote_explain), actions)
    }

    private fun startAndroidRemotePairing() {
        stopAndroidRemotePairing()
        androidRemoteTest = null
        val address = try { PhilipsSleepTest.deviceAddress() } catch (e: java.net.SocketException) {
            AppLog.i("Android TV Remote: cannot determine local address: ${e.javaClass.simpleName}")
            ""
        }
        if (address.isEmpty()) { toast(getString(R.string.sleep_philips_no_network)); return }
        val generation = androidRemoteGeneration
        philipsBusy = true
        Thread {
            val client = try { AndroidTvRemoteTest() } catch (e: Exception) {
                AppLog.i("Android TV Remote certificate error: ${e.javaClass.simpleName}")
                ui.post {
                    if (generation == androidRemoteGeneration && !isFinishing && !isDestroyed) {
                        philipsBusy = false
                        toast(getString(R.string.sleep_remote_failed))
                    }
                }
                return@Thread
            }
            ui.post {
                if (generation != androidRemoteGeneration || isFinishing || isDestroyed) {
                    client.close()
                    return@post
                }
                philipsBusy = false
                val texts = PhilipsPairingServer.Texts(
                    getString(R.string.sleep_remote_title), getString(R.string.sleep_philips_show_pin),
                    getString(R.string.sleep_remote_pin_hint), getString(R.string.sleep_philips_submit),
                    getString(R.string.sleep_philips_retry), getString(R.string.sleep_remote_failed),
                    getString(R.string.sleep_philips_paired))
                lateinit var server: PhilipsPairingServer
                try {
                    server = PhilipsPairingServer(address, client, texts, onPaired = {
                        ui.post {
                            if (androidRemoteServer === server && !isFinishing && !isDestroyed) {
                                androidRemoteServer = null
                                androidRemoteDialog?.dismiss()
                                androidRemoteDialog = null
                                androidRemoteTest = client
                                refresh()
                                toast(getString(R.string.sleep_philips_paired))
                                showAndroidRemoteTest()
                            }
                        }
                    }, onFinished = { success ->
                        if (!success) ui.post {
                            if (androidRemoteServer === server && !isFinishing && !isDestroyed) {
                                stopAndroidRemotePairing()
                                toast(getString(R.string.sleep_philips_expired))
                            }
                        }
                    })
                } catch (e: java.io.IOException) {
                    client.close()
                    AppLog.i("Android TV Remote pairing server failed: ${e.javaClass.simpleName}")
                    toast(getString(R.string.sleep_remote_failed))
                    return@post
                }
                androidRemoteServer = server
                androidRemoteDialog = LinkDialog.show(this, getString(R.string.sleep_remote_title),
                    getString(R.string.sleep_philips_browser_instructions, server.url), server.url) { stopAndroidRemotePairing() }
                server.start()
            }
        }.start()
    }

    private fun stopAndroidRemotePairing() {
        androidRemoteGeneration++
        androidRemoteServer?.stop()
        androidRemoteServer = null
        androidRemoteDialog?.dismiss()
        androidRemoteDialog = null
    }

    private fun runPhilipsOperation(operation: () -> Unit, errorRes: Int = R.string.sleep_philips_failed, onSuccess: () -> Unit) {
        if (philipsBusy) { toast(getString(R.string.sleep_philips_busy)); return }
        philipsBusy = true
        Thread {
            var failure: Exception? = null
            try {
                operation()
            } catch (e: java.io.IOException) { failure = e
            } catch (e: java.security.GeneralSecurityException) { failure = e
            } catch (e: org.json.JSONException) { failure = e
            } catch (e: IllegalArgumentException) { failure = e
            } catch (e: IllegalStateException) { failure = e
            } catch (e: PhilipsRecordingException) { failure = e
            } catch (e: SecurityException) { failure = e }
            ui.post {
                philipsBusy = false
                if (!isFinishing && !isDestroyed) {
                    if (failure == null) onSuccess()
                    else {
                        AppLog.i("Network sleep test failed: ${failure.javaClass.simpleName}")
                        toast(getString(if (failure is PhilipsRecordingException) R.string.sleep_test_recording else errorRes))
                    }
                }
            }
        }.start()
    }

    private val sleepTestTick = object : Runnable {
        override fun run() {
            val dlg = sleepTestDialog?.takeIf { it.isShowing } ?: return
            val left = sleepTestDeadline - SystemClock.elapsedRealtime()
            if (left > 0) {
                TvDialog.setMessage(dlg, getString(R.string.sleep_test_countdown, ((left + 999) / 1000).toInt()))
                ui.postDelayed(this, minOf(left, 1000))
                return
            }
            sleepTestDialog = null
            dlg.dismiss()
            when {
                AppState.recording || LocalRecordings.activeDirs.isNotEmpty() -> toast(getString(R.string.sleep_test_recording))
                sleepTestMode == SleepTestMode.PHILIPS -> {
                    val client = philipsSleepTest
                    if (client == null) toast(getString(R.string.sleep_philips_unpaired))
                    else runPhilipsOperation({
                        if (AppState.recording || LocalRecordings.activeDirs.isNotEmpty())
                            throw PhilipsRecordingException()
                        client.standby()
                    }) { AppLog.i("Philips sleep test: standby accepted") }
                }
                sleepTestMode == SleepTestMode.ANDROID_REMOTE -> {
                    val client = androidRemoteTest
                    if (client == null) toast(getString(R.string.sleep_philips_unpaired))
                    else runPhilipsOperation({
                        val sent = client.standby {
                            if (AppState.recording || LocalRecordings.activeDirs.isNotEmpty())
                                throw PhilipsRecordingException()
                        }
                        AppLog.i(if (sent) "Android TV Remote sleep test: POWER sent" else "Android TV Remote sleep test: device already off")
                    }, R.string.sleep_remote_failed) {}
                }
                sleepTestMode == SleepTestMode.ACCESSIBILITY -> {
                    try {
                        when (SleepAccessibilityTestService.lockScreen()) {
                            null -> toast(getString(R.string.sleep_accessibility_not_connected))
                            true -> AppLog.i("test uśpienia: system przyjął akcję dostępności LOCK_SCREEN")
                            false -> {
                                AppLog.i("test uśpienia: system odrzucił akcję dostępności LOCK_SCREEN")
                                toast(getString(R.string.sleep_accessibility_failed))
                            }
                        }
                    } catch (e: SecurityException) {
                        AppLog.i("test uśpienia przez dostępność: błąd $e")
                        toast(getString(R.string.sleep_test_error, e.message ?: e.toString()))
                    }
                }
                !sleepAdminActive() -> toast(getString(R.string.sleep_test_permission_missing))
                else -> try {
                    AppLog.i("test uśpienia: wywołuję DevicePolicyManager.lockNow()")
                    sleepAdmin().lockNow()
                } catch (e: SecurityException) {
                    AppLog.i("test uśpienia: błąd $e")
                    toast(getString(R.string.sleep_test_error, e.message ?: e.toString()))
                }
            }
        }
    }

    private fun startSleepTest() {
        if (AppState.recording || LocalRecordings.activeDirs.isNotEmpty()) { toast(getString(R.string.sleep_test_recording)); return }
        if (!sleepAdminActive()) { toast(getString(R.string.sleep_test_permission_missing)); return }
        startSleepCountdown(SleepTestMode.ADMIN)
    }

    private fun startSleepAccessibilityTest() {
        if (AppState.recording || LocalRecordings.activeDirs.isNotEmpty()) { toast(getString(R.string.sleep_test_recording)); return }
        if (!SleepAccessibilityTestService.isConnected()) { toast(getString(R.string.sleep_accessibility_not_connected)); return }
        startSleepCountdown(SleepTestMode.ACCESSIBILITY)
    }

    private fun startSleepCountdown(mode: SleepTestMode) {
        cancelSleepTest()
        sleepTestMode = mode
        sleepTestDeadline = SystemClock.elapsedRealtime() + 10_000
        val title = when (mode) {
            SleepTestMode.ADMIN -> R.string.sleep_test_title
            SleepTestMode.ACCESSIBILITY -> R.string.sleep_accessibility_title
            SleepTestMode.PHILIPS -> R.string.sleep_philips_title
            SleepTestMode.ANDROID_REMOTE -> R.string.sleep_remote_title
        }
        sleepTestDialog = TvDialog.confirm(this, getString(title),
            getString(R.string.sleep_test_countdown, 10),
            listOf(TvDialog.Btn(getString(R.string.dialog_cancel)) { cancelSleepTest() }),
            onBack = { cancelSleepTest() }, timeoutSec = 0)
        ui.post(sleepTestTick)
    }

    private fun cancelSleepTest() {
        ui.removeCallbacks(sleepTestTick)
        sleepTestDialog?.dismiss()
        sleepTestDialog = null
    }

    private fun updateModeLabel(mode: String) = getString(when (mode) {
        "notify" -> R.string.update_mode_notify
        "on" -> R.string.update_mode_on
        else -> R.string.update_mode_off
    })

    private fun sleepLabel(h: Int) = if (h <= 0) getString(R.string.sleep_timer_off) else getString(R.string.sleep_timer_hours, h)

    private fun markChanged(what: String) {
        changed = true
        AppLog.i("ustawienia: $what")
        refresh()
    }

    // ------------------------------------------------------------ edycja

    private fun onPick(it: Item) {
        when (it) {
            is Item.Header -> {}
            is Item.Toggle -> { it.set(!it.get()); markChanged("${it.label} = ${it.get()}") }
            is Item.Action -> it.run()
            is Item.Text -> editText(it)
        }
    }

    private fun editText(it: Item.Text) {
        val et = EditText(this)
        et.inputType = if (it.secret) {
            if ((it.input and InputType.TYPE_CLASS_NUMBER) != 0) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else it.input
        et.setText(it.get())
        et.setSelection(et.text.length)
        et.hint = it.hint
        et.textSize = 22f
        et.imeOptions = EditorInfo.IME_ACTION_DONE
        et.setSingleLine(true)
        TvDialog.input(this, it.label, it.hint, et) {
            it.set(et.text.toString())
            markChanged(it.label + if (it.secret) " (zmieniono)" else " = ${it.get()}")
        }
    }

    // ------------------------------------------------------------ pamięć masowa

    /**
     * Jedno miejsce na nośnik (dysk USB / pamięć urządzenia za zgodą / brak) i wszystko, co z niego korzysta:
     * timeshift, nagrania lokalne, logi. Przy „Brak” zostają informacje o wymaganym nośniku;
     * odłączony wybrany nośnik zachowuje nieaktywne opcje. Wartości ustawień zostają.
     */
    private fun storageItems(): List<Item> {
        val selected = p.tsDir.isNotBlank() || p.tsInternalOk
        return listOf(
        Item.Header(getString(R.string.storage_header_medium)),
        Item.Action(getString(R.string.storage_medium), { tsPlace() }) { chooseTsDir() },
        Item.Header(getString(R.string.storage_header_buffer_play)),
        gated(getString(R.string.setting_buffer_play), {
            if (!p.bufPlay) getString(R.string.settings_disabled)
            else if (p.tsMin > 0) getString(R.string.settings_enabled) + " — " + getString(R.string.buffer_play_shared, p.tsMin)
            else getString(R.string.settings_enabled) + " — " + getString(R.string.buffer_play_short, TimeshiftBuffer.BUF_PLAY_MIN)
        }) {
            p.bufPlay = !p.bufPlay
            markChanged("odtwarzanie przez bufor: ${if (p.bufPlay) "włączone" else "wyłączone"}")
            items = buildItems(); refresh()
        },
    ) + (if (!selected || !p.bufPlay) emptyList<Item>() else listOf(
            gated(getString(R.string.setting_buffer_write_batch), { writeBatchName(p.tsWriteBatchBytes) }) {
                val sizes = TimeshiftBuffer.WRITE_BATCH_OPTIONS.toList()
                TvDialog.single(this, getString(R.string.setting_buffer_write_batch), sizes.map { writeBatchName(it) },
                    sizes.indexOf(p.tsWriteBatchBytes).coerceAtLeast(0)) { w ->
                    p.tsWriteBatchBytes = sizes[w]
                    markChanged("partia zapisu bufora = ${writeBatchName(sizes[w])}")
                    items = buildItems(); refresh()
                }
            },
    )) + (if (!selected || !p.bufPlay) emptyList<Item>() else listOf(
        gated(getString(R.string.setting_bg_buffer), {
            if (!p.bufPlay) getString(R.string.setting_bg_buffer_needs) else bgCountName(p.bgCount)
        }) {
            if (!p.bufPlay) { toast(getString(R.string.setting_bg_buffer_needs)); return@gated }
            val o = listOf(0, 1, 2, 3)
            TvDialog.single(this, getString(R.string.setting_bg_buffer), o.map { bgCountName(it) }, p.bgCount.coerceIn(0, 3)) { w ->
                p.bgCount = o[w]; markChanged("bufor poprzednich kanałów: ${bgCountName(o[w])}"); items = buildItems(); refresh()
            }
        },
    )) + (if (!selected || !p.bufPlay || p.bgCount <= 0) emptyList<Item>() else listOf(
        // czas bufora — tylko gdy bufor poprzednich kanałów włączony
        gated(getString(R.string.setting_bg_buffer_time), { bgName(p.bgBufMin) }) { chooseBgBuffer() },
        Item.Toggle(getString(R.string.setting_bg_full_msg), { p.bgFullMsg }, { p.bgFullMsg = it }),
        Item.Toggle(getString(R.string.setting_bg_replace_msg), { p.bgReplaceMsg }, { p.bgReplaceMsg = it }),
        Item.Toggle(getString(R.string.setting_bg_status_msg), { p.bgStatusMsg }, { p.bgStatusMsg = it }),
        Item.Toggle(getString(R.string.setting_bg_protect_all), { p.bgProtectAll }, {
            p.bgProtectAll = it; items = buildItems(); refresh()
        }),
    ) + (if (p.bgProtectAll) emptyList() else listOf(
        Item.Toggle(getString(R.string.setting_bg_protect_event), { p.bgProtectEvent }, { p.bgProtectEvent = it }),
    ))
    ) + (if (!Features.PIP || (selected && !p.bufPlay)) emptyList<Item>() else listOf(
        gated(getString(R.string.setting_pip_enabled), { getString(if (p.pipEnabled) R.string.settings_enabled else R.string.settings_disabled) }) {
            p.pipEnabled = !p.pipEnabled; markChanged("PiP = ${p.pipEnabled}"); items = buildItems(); refresh()
        },
    )) + (if (!selected || !Features.PIP || !p.bufPlay || !p.pipEnabled) emptyList<Item>() else listOf(
        gated(getString(R.string.setting_pip_mode), { pipName(p.pipFrameMs) }) {
            val o = listOf(1000, 2000, 3000, 5000)
            TvDialog.single(this, getString(R.string.setting_pip_mode), o.map { pipName(it) }, o.indexOf(p.pipFrameMs).coerceAtLeast(0)) { w ->
                p.pipFrameMs = o[w]; markChanged("PiP: ${pipName(o[w])}"); items = buildItems(); refresh()
            }
        },
        gated(getString(R.string.setting_pip_position), { pipPositions().getOrElse(p.pipPos) { "" } }) {
            val o = pipPositions()
            TvDialog.single(this, getString(R.string.setting_pip_position), o, p.pipPos.coerceIn(0, o.size - 1)) { w ->
                p.pipPos = w; markChanged("PiP: położenie okien = ${o[w]}"); items = buildItems(); refresh()
            }
        },
    )) + listOf(
        Item.Header(getString(R.string.setting_timeshift_pause_and_rewind_live_tv)),
        gated(getString(R.string.setting_timeshift), { tsValue() }) { chooseTs() },
    ) + (if (!selected || p.tsMin <= 0) emptyList<Item>() else listOf(
        // przy odtwarzaniu przez bufor zapis rusza od razu (odtwarzacz czyta z bufora) — opóźnienie nieaktywne, ustawienie zostaje
        gated(getString(R.string.setting_automatic_timeshift), {
            if (p.bufPlay) getString(R.string.ts_delay_buffer_play) else tsDelayName(p.tsDelay)
        }) { if (p.bufPlay) toast(getString(R.string.ts_delay_buffer_play)) else chooseTsDelay() },
        gated(getString(R.string.setting_seek_lr_short), { "${p.tsStepSec} s" }) {
            val o = listOf(1, 2, 3, 4, 5)
            TvDialog.single(this, getString(R.string.message_seek), o.map { "$it s" }, o.indexOf(p.tsStepSec).coerceAtLeast(0)) { w -> p.tsStepSec = o[w]; markChanged("przewijanie ◀ ▶ krótkie = ${o[w]} s"); items = buildItems(); refresh() }
        },
        gated(getString(R.string.setting_seek_lr_long), { "${p.tsStepSecLong} s" }) {
            val o = listOf(10, 15, 20, 25, 30)
            TvDialog.single(this, getString(R.string.message_seek), o.map { "$it s" }, o.indexOf(p.tsStepSecLong).coerceAtLeast(0)) { w -> p.tsStepSecLong = o[w]; markChanged("przewijanie ◀ ▶ długie = ${o[w]} s"); items = buildItems(); refresh() }
        },
        gated(getString(R.string.setting_seek_ud_short), { Prefs.stepMinLabel(p.tsStepUdSec) }) {
            val o = listOf(30, 60, 90, 120)
            TvDialog.single(this, getString(R.string.message_seek_2), o.map { Prefs.stepMinLabel(it) }, o.indexOf(p.tsStepUdSec).coerceAtLeast(0)) { w -> p.tsStepUdSec = o[w]; markChanged("przewijanie ▲ ▼ krótkie = ${o[w]} s"); items = buildItems(); refresh() }
        },
        gated(getString(R.string.setting_seek_ud_long), { "${p.tsStepUdLongMin} min" }) {
            val o = listOf(3, 6, 9, 12, 15)
            TvDialog.single(this, getString(R.string.message_seek_2), o.map { "$it min" }, o.indexOf(p.tsStepUdLongMin).coerceAtLeast(0)) { w -> p.tsStepUdLongMin = o[w]; markChanged("przewijanie ▲ ▼ długie = ${o[w]} min"); items = buildItems(); refresh() }
        },
        Item.Toggle(getString(R.string.setting_back_on_empty_screen_live_tv), { p.tsBackLive }, { p.tsBackLive = it }),
        Item.Header(getString(R.string.storage_header_recordings)),
        gated(getString(R.string.setting_rec_max_hours), { "${p.recMaxHours} h" }, needTimeshift = true) {
            val o = (1..6).toList()
            TvDialog.single(this, getString(R.string.setting_rec_max_hours), o.map { "$it h" }, o.indexOf(p.recMaxHours).coerceAtLeast(0)) { w ->
                p.recMaxHours = o[w]; markChanged("nagrania lokalne: limit nagrania = ${o[w]} h"); items = buildItems(); refresh()
            }
        },
        gated(getString(R.string.setting_rec_max_pct), { getString(R.string.setting_rec_pct_value, p.recMaxPct) }, needTimeshift = true) {
            val o = listOf(30, 50, 60, 70, 80, 90)
            TvDialog.single(this, getString(R.string.setting_rec_max_pct), o.map { getString(R.string.setting_rec_pct_value, it) }, o.indexOf(p.recMaxPct).coerceAtLeast(0)) { w ->
                p.recMaxPct = o[w]; markChanged("nagrania lokalne: limit miejsca = ${o[w]}%"); items = buildItems(); refresh()
            }
        },
    )) + listOf(
        Item.Header(getString(R.string.storage_header_logs)),
        Item.Action(getString(R.string.setting_log_and_dump_storage_location), { Storage.dir(this, p).absolutePath }) { chooseStorage() },
        Item.Action(getString(R.string.setting_app_storage), { getString(R.string.setting_app_storage_value) }) { showAppStorage() },
    )
    }

    /** Długość bufora bieżącego kanału (timeshift albo krótki bufor samego odtwarzania przez bufor). */
    private fun mainBufMin() = if (p.tsMin > 0) p.tsMin else TimeshiftBuffer.BUF_PLAY_MIN

    private fun writeBatchName(bytes: Int): String = when (bytes) {
        0 -> getString(R.string.setting_buffer_write_batch_off)
        256 * 1024 -> getString(R.string.setting_buffer_write_batch_256)
        512 * 1024 -> getString(R.string.setting_buffer_write_batch_512)
        else -> getString(R.string.setting_buffer_write_batch_1024)
    }

    private fun bgCountName(n: Int): String = when (n) {
        0 -> getString(R.string.settings_disabled)
        1 -> getString(R.string.setting_bg_buffer_1)
        else -> getString(R.string.setting_bg_buffer_n, n)
    }

    private fun bgSameName(): String = getString(if (p.tsMin > 0) R.string.setting_bg_buffer_same else R.string.setting_bg_buffer_same_buf, mainBufMin())

    private fun bgName(m: Int): String = when {
        m <= 0 -> bgSameName()
        else -> getString(R.string.setting_bg_buffer_manual, minOf(m, mainBufMin()))
    }

    private fun pipName(ms: Int): String = getString(R.string.setting_pip_frames_s,
        if (ms % 1000 == 0) "${ms / 1000}" else "%.2f".format(resources.configuration.locales[0], ms / 1000.0).trimEnd('0'))

    /** Kolejność zgodna z PlayerActivity.pipLayout. */
    private fun pipPositions() = listOf(R.string.pip_pos_top, R.string.pip_pos_bottom, R.string.pip_pos_right, R.string.pip_pos_left,
        R.string.pip_pos_tl_down, R.string.pip_pos_tr_down, R.string.pip_pos_bl_up, R.string.pip_pos_br_up).map { getString(it) }

    /** Czas bufora poprzednich kanałów: taki sam jak timeshift / ręczny w minutach (najwyżej jak timeshift). */
    private fun chooseBgBuffer() {
        val opts = listOf(bgSameName(),
            getString(R.string.setting_bg_buffer_manual_pick) + if (p.bgBufMin > 0) " (${minOf(p.bgBufMin, mainBufMin())} min)" else "")
        val sel = if (p.bgBufMin > 0) 1 else 0
        TvDialog.single(this, getString(R.string.setting_bg_buffer_time), opts, sel) { w ->
            when (w) {
                0 -> { p.bgBufMin = -1; markChanged("czas bufora poprzednich kanałów: ${bgName(p.bgBufMin)}"); items = buildItems(); refresh() }
                else -> {
                    val max = mainBufMin()
                    val et = EditText(this)
                    et.inputType = InputType.TYPE_CLASS_NUMBER
                    et.setText((if (p.bgBufMin > 0) minOf(p.bgBufMin, max) else minOf(10, max)).toString())
                    et.setSelection(et.text.length)
                    et.textSize = 22f
                    et.imeOptions = EditorInfo.IME_ACTION_DONE
                    et.setSingleLine(true)
                    val hint = getString(R.string.setting_bg_buffer_manual_hint, max)
                    et.hint = hint
                    TvDialog.input(this, getString(R.string.setting_bg_buffer_time), hint, et) {
                        val v = et.text.toString().toIntOrNull()
                        if (v == null || v < 1) { toast(hint); return@input }
                        p.bgBufMin = minOf(v, max)
                        if (v > max) toast(getString(R.string.setting_bg_buffer_capped, max))
                        markChanged("czas bufora poprzednich kanałów: ${bgName(p.bgBufMin)}"); items = buildItems(); refresh()
                    }
                }
            }
        }
    }

    /** Nośnik wybrany i dostępny (dysk podłączony / zgoda na pamięć urządzenia). */
    private fun mediumReady(): Boolean = TimeshiftBuffer.dir(this, p) != null

    private fun mediumMissing(): String = getString(when {
        p.tsDir.isNotBlank() -> R.string.storage_medium_unavailable
        else -> R.string.storage_requires_medium
    })

    /** Pozycja zależna od nośnika (i opcjonalnie od włączonego timeshiftu): bez niego opis i wybór nośnika. */
    private fun gated(label: String, value: () -> String, needTimeshift: Boolean = false, run: () -> Unit) = Item.Action(label, {
        when {
            !mediumReady() -> mediumMissing()
            needTimeshift && p.tsMin <= 0 -> getString(R.string.storage_requires_timeshift)
            else -> value()
        }
    }) {
        when {
            !mediumReady() -> { toast(mediumMissing()); chooseTsDir() }
            needTimeshift && p.tsMin <= 0 -> { toast(getString(R.string.storage_requires_timeshift)); chooseTs() }
            else -> run()
        }
    }

    // ------------------------------------------------------------ timeshift

    /** Czy wybrany jest dysk USB / karta SD (nie pamięć urządzenia). */
    private fun tsUsb(): Boolean = p.tsDir.isNotBlank() && p.tsDir != TimeshiftBuffer.internalBase(this).absolutePath

    private fun tsFit(): Int = TimeshiftBuffer.dir(this, p)?.let { TimeshiftBuffer.fitMinutes(it) } ?: 0

    private fun tsValue(): String {
        val d = TimeshiftBuffer.dir(this, p)
            ?: return getString(if (p.tsDir.isNotBlank()) R.string.ts_usb_unavailable else R.string.ts_choose_location)
        if (p.tsMin <= 0) return getString(R.string.settings_disabled)
        val fit = TimeshiftBuffer.fitMinutes(d)
        val rec = TimeshiftBuffer.recommended(fit, tsUsb())
        val cur = if (p.tsMin <= 0) getString(R.string.ts_off) else "${p.tsMin} min"
        return getString(R.string.ts_capacity, cur, fmtMin(fit)) + if (rec > 0) getString(R.string.ts_recommended_suffix, fmtMin(rec)) else getString(R.string.ts_no_space_suffix)
    }

    private fun tsDelayName(s: Int) = when { s == 0 -> getString(R.string.ts_immediately); s < 0 -> getString(R.string.ts_after_pause); else -> getString(R.string.ts_after_seconds, s) }

    private fun chooseTsDelay() {
        val opts = TS_DELAYS
        TvDialog.single(this, getString(R.string.message_automatic_timeshift), opts.map { tsDelayName(it) }, opts.indexOf(p.tsDelay).coerceAtLeast(0)) { w ->
            p.tsDelay = opts[w]
            markChanged("timeshift: start bufora = ${tsDelayName(opts[w])}")
            items = buildItems(); refresh()
        }
    }

    private fun fmtMin(m: Int) = if (m >= 120 && m % 60 == 0) "${m / 60} h" else "$m min"

    private fun tsPlace(): String {
        if (p.tsDir.isBlank()) return if (p.tsInternalOk) getString(R.string.ts_device_free, TimeshiftBuffer.gb(TimeshiftBuffer.internalBase(this).usableSpace)) else getString(R.string.ts_not_selected)
        if (!TimeshiftBuffer.available(p.tsDir)) return getString(R.string.ts_unavailable, storageName(p.tsDir))
        return getString(R.string.ts_free, storageName(p.tsDir), TimeshiftBuffer.gb(java.io.File(p.tsDir).usableSpace))
    }

    private fun storageName(path: String): String =
        Storage.recordingName(this, p, java.io.File(path))

    private fun chooseTs() {
        if (TimeshiftBuffer.dir(this, p) == null) {
            toast(getString(R.string.message_first_select_a_buffer_location_usb_drive_or_device_stor))
            chooseTsDir(); return
        }
        val usb = tsUsb()
        val fit = tsFit()
        val rec = TimeshiftBuffer.recommended(fit, usb)
        val ch = TimeshiftBuffer.choices(usb)
        val items = ch.map { m ->
            val label = if (m == 0) getString(R.string.player_disabled) else fmtMin(m)
            val sub = when {
                m == 0 -> ""
                m > fit -> getString(R.string.ts_insufficient)
                m == rec -> getString(R.string.ts_recommended)
                else -> String.format(Locale("pl"), "~%.1f GB", m * TimeshiftBuffer.MB_PER_MIN / 1000.0)
            }
            TvDialog.Item(label, sub, if (m == p.tsMin) 3 else 0)
        }
        TvDialog.list(this, getString(R.string.ts_capacity_title, fmtMin(fit)), items, ch.indexOf(p.tsMin).coerceAtLeast(0), widthFrac = 0.6f) { w ->
            val m = ch[w]
            if (m > fit) { toast(getString(R.string.ts_choose_shorter, fmtMin(m))); return@list }
            p.tsMin = m
            markChanged("timeshift = $m min")
            this.items = buildItems()
            refresh()
        }
    }

    private fun chooseTsDir() {
        val internal = TimeshiftBuffer.internalBase(this).absolutePath
        val opts = Storage.options(this).filter { it.path != filesDir.absolutePath }
        val items = opts.map { o ->
            val isInt = o.path == internal
            val usable = isInt && !p.tsInternalOk
            val fit = TimeshiftBuffer.fitMinutes(java.io.File(o.path, "timeshift"))
            val rec = TimeshiftBuffer.recommended(fit, !isInt)
            val cur = if (isInt) p.tsDir.isBlank() && p.tsInternalOk else o.path == p.tsDir
            TvDialog.Item(o.name,
                if (usable) getString(R.string.ts_permission_needed)
                else getString(R.string.ts_space_choice, TimeshiftBuffer.gb(java.io.File(o.path).usableSpace), fmtMin(fit)) + if (rec > 0) getString(R.string.ts_recommended_choice, fmtMin(rec)) else "",
                if (cur) 3 else 0)
        }
        val none = TvDialog.Item(getString(R.string.storage_none), getString(R.string.storage_none_desc),
            if (p.tsDir.isBlank() && !p.tsInternalOk) 3 else 0)
        TvDialog.list(this, getString(R.string.message_timeshift_buffer_location), items + none, -1, widthFrac = 0.6f) { w ->
            if (w >= opts.size) {
                // Brak nośnika wyłącza zapis bez kasowania ustawień na czas ponownego wyboru.
                p.tsDir = ""; p.tsInternalOk = false
                markChanged("pamięć masowa: brak nośnika")
                if (Storage.followTimeshift(p)) AppLog.init(this, Storage.dir(this, p))
                this.items = buildItems(); refresh()
                return@list
            }
            val o = opts[w]
            if (o.path == internal) {
                if (!p.tsInternalOk) {
                    TvDialog.confirm(this, getString(R.string.message_buffer_in_device_storage),
                        getString(R.string.ts_flash_warning),
                        listOf(TvDialog.Btn(getString(R.string.message_allow)) { p.tsInternalOk = true; setTsDir("", o.name) }))
                } else setTsDir("", o.name)
            } else setTsDir(o.path, o.name)
        }
    }

    private fun setTsDir(path: String, name: String) {
        p.setTimeshiftStorage(path, name)
        val fit = tsFit()
        val rec = TimeshiftBuffer.recommended(fit, tsUsb())
        if (p.tsMin > fit) {
            p.tsMin = rec
            toast(if (rec > 0) getString(R.string.ts_capacity_set, fmtMin(fit), fmtMin(rec)) else getString(R.string.ts_disabled_space))
        } else if (p.tsMin == 0 && rec > 0) {
            toast(getString(R.string.ts_capacity_recommended, fmtMin(fit), fmtMin(rec)))
        }
        markChanged("timeshift: miejsce = ${path.ifBlank { "pamięć urządzenia" }}")
        if (Storage.followTimeshift(p)) AppLog.init(this, Storage.dir(this, p))
        items = buildItems(); refresh()
    }

    private fun chooseAfr() {
        val opts = arrayOf(getString(R.string.settings_refresh_unchanged), getString(R.string.settings_refresh_50), getString(R.string.settings_refresh_auto))
        TvDialog.single(this, getString(R.string.message_screen_refresh_rate), opts.toList(), p.afrMode) { w -> p.afrMode = w; markChanged("odświeżanie = ${opts[w]}") }
    }

    private fun choosePinTime() {
        val mins = intArrayOf(1, 5, 15, 30, 60, 0)
        TvDialog.single(this, getString(R.string.message_ask_for_pin_again_after), mins.map { PinLock.label(this, it) }, mins.indexOf(p.pinMin)) { w ->
            p.pinMin = mins[w]
            if (PinLock.isUnlocked(p)) PinLock.unlock(p)      // nowy czas liczony od teraz
            refresh()
        }
    }

    private fun chooseEngine() {
        val ids = arrayOf("exo", "vlc")
        val opts = arrayOf(getString(R.string.engine_exo), getString(R.string.engine_vlc))
        TvDialog.single(this, getString(R.string.message_playback_engine_on_this_device), opts.toList(), ids.indexOf(p.engine)) { w ->
            p.engine = ids[w]; markChanged("silnik = ${ids[w]}")
            items = buildItems(); refresh()
        }
    }

    private val hwIds = arrayOf("auto", "off", "dec", "full")
    private fun hwName(id: String) = when (id) {
        "off" -> getString(R.string.hw_off); "dec" -> getString(R.string.hw_decode); "full" -> getString(R.string.hw_full); else -> getString(R.string.hw_auto)
    }

    private fun chooseHw() {
        TvDialog.single(this, getString(R.string.message_vlc_hardware_acceleration), hwIds.map { hwName(it) }, hwIds.indexOf(p.vlcHwMode)) { w ->
            p.vlcHwMode = hwIds[w]; markChanged("VLC akceleracja = ${hwIds[w]}")
        }
    }

    private fun chooseChroma() {
        val ids = arrayOf("RV32", "RV16", "YV12")
        val names = arrayOf(getString(R.string.chroma_recommended), "RGB 16-bit", "YUV (YV12)")
        TvDialog.single(this, getString(R.string.message_vlc_video_format), names.toList(), ids.indexOf(p.vlcChroma)) { w ->
            p.vlcChroma = ids[w]; markChanged("VLC chroma = ${ids[w]}")
        }
    }

    private val deintIds = arrayOf("default", "auto", "off", "yadif", "yadif2x", "bob", "linear", "blend", "x")
    private fun deintName(id: String) = when (id) {
        "default" -> getString(R.string.deint_default); "auto" -> getString(R.string.deint_auto); "off" -> getString(R.string.deint_off); "yadif2x" -> getString(R.string.deint_yadif); "bob" -> getString(R.string.deint_bob)
        else -> id
    }

    private fun chooseDeint() {
        TvDialog.single(this, getString(R.string.message_vlc_deinterlacing), deintIds.map { deintName(it) }, deintIds.indexOf(p.vlcDeint)) { w ->
            p.vlcDeint = deintIds[w]; markChanged("VLC przeplot = ${deintIds[w]}")
        }
    }

    private fun chooseStorage() {
        val opts = Storage.options(this)
        TvDialog.single(this, getString(R.string.message_log_and_ts_dump_storage_location), opts.map { it.label }, opts.indexOfFirst { it.path == p.storageDir }) { w ->
            p.storageDir = opts[w].path; p.storageDirAuto = false; markChanged("zapis = ${opts[w].path}")
            AppLog.init(this, Storage.dir(this, p))
        }
    }

    private fun discover() {
        if (discoveryThread?.isAlive == true) {
            toast(getString(R.string.receiver_search_in_progress)); return
        }
        val subnet = p.discoverySubnet
        if (subnet.isNotBlank() && Discovery.subnetHosts(subnet) == null) {
            toast(getString(R.string.receiver_search_invalid_subnet)); return
        }
        val httpPort = p.httpPort
        val pluginPort = p.pluginPort
        if (httpPort !in 1..65535 || pluginPort !in 1..65535) {
            toast(getString(R.string.receiver_search_invalid_port)); return
        }
        toast(getString(R.string.message_searching_for_receivers_on_the_network))
        val thread = Thread {
            val result = try {
                Discovery.scan(applicationContext, httpPort, pluginPort, subnet) { _, _ -> }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@Thread
            }
            runOnUiThread {
                discoveryThread = null
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result.warnings) toast(getString(R.string.receiver_search_warning))
                val found = result.receivers
                if (found.isEmpty()) {
                    toast(getString(R.string.receiver_not_found, httpPort)); return@runOnUiThread
                }
                val rows = found.map { receiver ->
                    val state = getString(if (receiver.verified) R.string.receiver_search_verified else R.string.receiver_search_unverified)
                    val source = if (receiver.viaMdns) "mDNS" else ""
                    val subtitle = listOf(state, receiver.description, source).filter { it.isNotBlank() }.joinToString("  ·  ")
                    TvDialog.Item("${receiver.ip}:${receiver.httpPort}", subtitle)
                }
                TvDialog.list(this, getString(R.string.receiver_search_devices), rows, stacked = true) { w ->
                    val receiver = found[w]
                    if (receiver.verified) selectReceiver(receiver)
                    else TvDialog.confirm(this, getString(R.string.receiver_search_unverified_title),
                        getString(R.string.receiver_search_confirm, receiver.ip, receiver.httpPort), listOf(
                            TvDialog.Btn(getString(R.string.receiver_search_use)) { selectReceiver(receiver) },
                        ))
                }
            }
        }
        discoveryThread = thread
        thread.start()
    }

    private fun selectReceiver(receiver: FoundReceiver) {
        if (p.host != receiver.ip || (receiver.pluginPort != null && p.pluginPort != receiver.pluginPort)) {
            p.pluginOk = false
            p.pluginVersion = ""
        }
        p.host = receiver.ip
        p.httpPort = receiver.httpPort
        receiver.pluginPort?.let { p.pluginPort = it }
        receiver.streamPort?.let { p.streamPort = it }
        markChanged("dekoder = ${receiver.ip}:${receiver.httpPort} (${receiver.description})")
        checkPluginQuiet()
        offerBackupNow()
    }

    /**
     * Pierwsza konfiguracja: zaraz po wybraniu dekodera pytanie o kopię ustawień z dekodera (okno w odtwarzaczu),
     * zanim cokolwiek zostanie zmienione - inaczej późniejsza synchronizacja mogłaby nadpisać kopię zmianami.
     */
    private fun offerBackupNow() {
        if (p.host.isBlank() || !SyncManager.needsBackupDecision || isFinishing) return
        AppLog.i("ustawienia: wybrano dekoder ${p.host} - od razu pytanie o kopię ustawień")
        finish()
    }

    private fun chooseBouquets() {
        toast(getString(R.string.message_loading_bouquets))
        Thread {
            val list = try {
                OpenWebif(p).bouquets() + Bouquet(getString(R.string.receiver_recordings), REC_BOUQUET) +
                    Bouquet(getString(R.string.player_local_recordings), REC_LOCAL_BOUQUET) +
                    Bouquet(getString(R.string.player_history), HIST_BOUQUET)
            } catch (e: Exception) { null }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (list.isNullOrEmpty()) { toast(getString(R.string.message_could_not_load_bouquets_from_the_receiver)); return@runOnUiThread }
                val hidden = p.hiddenBouquets
                val checked = BooleanArray(list.size) { list[it].ref !in hidden }
                TvDialog.multi(this, getString(R.string.receiver_visible_bouquets), list.map { it.name }, checked) { st ->
                    p.hiddenBouquets = list.indices.filter { !st[it] }.map { list[it].ref }.toSet()
                    markChanged("bukiety: ukryte ${p.hiddenBouquets.size}")
                }
            }
        }.start()
    }

    /** Wybór kanału startowego: bukiet → kanał. */
    private fun chooseStartChannel() {
        toast(getString(R.string.message_loading_bouquets))
        Thread {
            val api = OpenWebif(p)
            val bq = try {
                val all = api.bouquets(); val hidden = p.hiddenBouquets
                all.filter { it.ref !in hidden }.ifEmpty { all }
            } catch (e: Exception) { null }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (bq.isNullOrEmpty()) { toast(getString(R.string.message_could_not_load_bouquets)); return@runOnUiThread }
                TvDialog.single(this, getString(R.string.message_startup_channel_bouquet), bq.map { it.name }, -1) { bi ->
                        Thread {
                            val ch = try { api.channels(bq[bi]) } catch (e: Exception) { emptyList() }
                            runOnUiThread {
                                if (ch.isEmpty()) { toast(getString(R.string.message_empty_bouquet)); return@runOnUiThread }
                                val cur = ch.indexOfFirst { OpenWebif.key(it.ref) == OpenWebif.key(p.startChannel) }
                                TvDialog.single(this, getString(R.string.receiver_start_channel, bq[bi].name), ch.map { "${it.num}  ${it.name}" }, cur) { ci ->
                                    p.startBouquet = bq[bi].ref
                                    p.startChannel = ch[ci].ref
                                    p.startChannelName = "${ch[ci].name} (${bq[bi].name})"
                                    markChanged("kanał startowy = ${p.startChannelName}")
                                }
                            }
                        }.start()
                    }
            }
        }.start()
    }

    /** Sprawdzenie wtyczki w tle (po wyborze dekodera i przy wejściu w sekcje) — stan od razu w ustawieniach. */
    private fun checkPluginQuiet() {
        if (p.host.isBlank()) return
        Thread {
            val was = p.pluginOk
            val ok = PluginApi.check(p)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (ok != was) AppLog.i("ustawienia: wtyczka EPG Scan ${if (ok) p.pluginVersion else "brak"}")
                items = buildItems(); refresh()
            }
        }.start()
    }

    /** EIT ze strumienia: włączenie tylko wtedy, gdy Enigma2 wysyła EIT (ustawienie odczytane przez wtyczkę EPG Scan). */
    private fun enableStreamEit() {
        toast(getString(R.string.message_eit_checking))
        Thread {
            val info = if (p.host.isBlank()) null else PluginApi(p).info()
            val state: Boolean? = info?.takeIf { it.has("stream_eit") && !it.isNull("stream_eit") }?.optBoolean("stream_eit")
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                fun on() {
                    p.eitStream = true
                    markChanged("EIT ze strumienia = true (Enigma2: ${state ?: "nieznane"})")
                    items = buildItems(); refresh()
                }
                when (state) {
                    true -> on()
                    false -> {
                        AppLog.i("ustawienia: EIT ze strumienia - Enigma2 nie wysyła EIT (config.streaming.stream_eit = wyłączone)")
                        TvDialog.confirm(this, getString(R.string.eit_off_title), getString(R.string.eit_off_message),
                            listOf(TvDialog.Btn("OK")), hintOverride = getString(R.string.epg_hint_actions))
                    }
                    null -> TvDialog.confirm(this, getString(R.string.eit_unknown_title), getString(R.string.eit_unknown_message), listOf(
                        TvDialog.Btn(getString(R.string.eit_enable)) { on() },
                    ))
                }
            }
        }.start()
    }

    /** Zajętość pamięci przez aplikację z podziałem; czyszczenie pamięci podręcznej (ustawienia zostają). */
    private fun showAppStorage() {
        Thread {
            val parts = AppStorage.report(this, p)
            val text = parts.joinToString("\n") { "${it.label}: ${AppStorage.mb(it.bytes)}\n    ${it.path}" }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                TvDialog.confirm(this, getString(R.string.setting_app_storage), text, listOf(
                    TvDialog.Btn(getString(R.string.setting_app_storage_clear_cache)) {
                        Thread {
                            val freed = AppStorage.clearCache(this)
                            AppLog.i("pamięć aplikacji: wyczyszczono pamięć podręczną (${AppStorage.mb(freed)})")
                            runOnUiThread { toast(getString(R.string.setting_app_storage_cleared, AppStorage.mb(freed))) }
                        }.start()
                    },
                    TvDialog.Btn(getString(R.string.message_close)),
                ), timeoutSec = 0)
            }
        }.start()
    }

    private fun checkPlugin() {
        toast(getString(R.string.message_checking_epg_scan_plugin))
        Thread {
            val ok = PluginApi.check(p)
            runOnUiThread {
                toast(if (ok) getString(R.string.receiver_plugin_found, p.pluginVersion)
                else getString(R.string.receiver_plugin_missing, p.pluginPort))
                markChanged("wtyczka: ${if (ok) p.pluginVersion else "brak"}")
            }
        }.start()
    }

    private fun syncStatus(): String {
        val at = SyncManager.lastSyncAt
        val last = if (at > 0) SimpleDateFormat("dd.MM HH:mm", Locale.forLanguageTag(AppLanguage.selected(this))).format(Date(at)) else getString(R.string.settings_never)
        return if (SyncManager.running) getString(R.string.settings_sync_running) else getString(R.string.settings_sync_last, last) + (if (SyncManager.dirty) getString(R.string.settings_sync_dirty) else "")
    }

    private fun syncNow() {
        if (SyncManager.needsBackupDecision) {
            toast(getString(R.string.message_finish_receiver_setup_and_choose_whether_to_restore_a_s))
            return
        }
        toast(getString(R.string.message_synchronising))
        SyncManager.syncAsync { r -> toast(getString(R.string.receiver_sync_result, r)); refresh() }
    }

    private fun chooseProfile() {
        toast(getString(R.string.message_loading_device_profiles))
        Thread {
            val result = runCatching { SyncManager.profiles(p) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (result.isFailure) {
                    val error = result.exceptionOrNull()
                    AppLog.i("kopia ustawień: błąd pobierania: $error")
                    toast(getString(R.string.receiver_profiles_error, error?.message ?: error.toString()))
                    return@runOnUiThread
                }
                val others = result.getOrThrow().filter { (!it.file.contains(SyncManager.deviceId()) && !it.file.contains(SyncManager.legacyDeviceId())) }
                if (others.isEmpty()) { toast(getString(R.string.message_no_other_device_profiles_on_the_receiver)); return@runOnUiThread }
                val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.forLanguageTag(AppLanguage.selected(this)))
                TvDialog.list(this, getString(R.string.message_import_settings_from_device),
                    others.map { TvDialog.Item(it.name.ifBlank { it.model }, "${it.model}, ${fmt.format(Date(it.updated))}") }) { w ->
                    try {
                        val n = SyncManager.restore(others[w])
                        markChanged("przeniesiono $n ustawień z ${others[w].name}")
                        toast(getString(R.string.receiver_profile_restored, n, others[w].name))
                        recreate()
                    } catch (e: Exception) {
                        AppLog.i("kopia ustawień: błąd przywracania: $e")
                        toast(getString(R.string.receiver_restore_error, e.message ?: e.toString()))
                    }
                }
            }
        }.start()
    }

    private fun sync(reloadIcons: Boolean) {
        if (reloadIcons) { Picons.clear(this); p.picons = true }
        status = getString(R.string.receiver_downloading)
        refresh()
        EpgSync.run(applicationContext, p, store, true, { msg -> runOnUiThread { status = msg; refresh() } }) { r ->
            runOnUiThread { status = ""; toast(r); markChanged("EPG/picony pobrane") }
        }
    }

    // ------------------------------------------------------------ mapowanie klawiszy

    private fun showKeyMap() {
        val entries = KeyMap.load(p).entries.filter { it.key !in KeyMap.navigation }
        val rows = listOf(TvDialog.Item(getString(R.string.settings_add_key), getString(R.string.settings_press_remote))) +
            entries.map { TvDialog.Item(KeyMap.keyName(it.key), getString(it.value.labelRes)) } +
            listOf(TvDialog.Item(getString(R.string.settings_restore_defaults)))
        TvDialog.list(this, getString(R.string.message_key_mapping_during_playback), rows) { w ->
            when (w) {
                0 -> captureKey()
                rows.size - 1 -> { p.keyMap = ""; toast(getString(R.string.message_defaults_restored)); showKeyMap() }
                else -> chooseAct(entries[w - 1].key)
            }
        }
    }

    private fun captureKey() {
        val dlg = TvDialog.panel(this, getString(R.string.message_press_a_key_on_the_remote), "WSTECZ anuluj", widthFrac = 0.45f) { ctx, _ ->
            TextView(ctx).apply {
                text = getString(R.string.settings_key_help)
                textSize = 17f; setTextColor(TvDialog.GREY); isFocusable = true
            }
        }
        dlg.setOnKeyListener { d, code, e ->
            if (e.action != KeyEvent.ACTION_UP) return@setOnKeyListener true
            if (code == KeyEvent.KEYCODE_BACK) { d.dismiss(); return@setOnKeyListener true }
            if (code in KeyMap.navigation) { toast(getString(R.string.message_use_ok_and_arrow_key_mapping)); return@setOnKeyListener true }
            if (code in KeyMap.fixed) { toast(getString(R.string.settings_key_fixed, KeyMap.keyName(code))); return@setOnKeyListener true }
            d.dismiss()
            chooseAct(code)
            true
        }
    }

    private fun chooseAct(code: Int) {
        val m = KeyMap.load(p)
        val acts = Act.values().filter { it != Act.DEFAULT_OK }
        val cur = acts.indexOf(m[code] ?: Act.NONE)
        TvDialog.single(this, getString(R.string.settings_key, KeyMap.keyName(code)), acts.map { getString(it.labelRes) }, cur, onCancel = { showKeyMap() }) { w ->
            m[code] = acts[w]
            KeyMap.save(p, m)
            AppLog.i("mapowanie: ${KeyMap.keyName(code)} → ${acts[w].name}")
            showKeyMap()
        }
    }

    private fun showNavigationMap(withBanner: Boolean) {
        val stored = if (withBanner) p.keyMapBanner else p.keyMapNoBanner
        val defaults = if (withBanner) KeyMap.navigationBannerDefaults else KeyMap.navigationNoBannerDefaults
        val map = KeyMap.navigationMap(defaults, stored)
        val keys = KeyMap.navigation.keys.toList()
        val title = getString(if (withBanner) R.string.settings_map_with_banner else R.string.settings_map_without_banner)
        val rows = keys.map { TvDialog.Item(getString(KeyMap.navigation.getValue(it)), getString(map.getValue(it).labelRes)) } +
            TvDialog.Item(getString(R.string.settings_restore_defaults))
        TvDialog.list(this, title, rows) { selected ->
            if (selected == keys.size) {
                if (withBanner) p.keyMapBanner = "" else p.keyMapNoBanner = ""
                markChanged("mapowanie: $title — przywrócono domyślne")
                showNavigationMap(withBanner)
            } else {
                val code = keys[selected]
                val acts = Act.values().filter { it != Act.DEFAULT_OK || code == KeyEvent.KEYCODE_DPAD_CENTER }
                val current = acts.indexOf(map.getValue(code))
                TvDialog.single(this, "${getString(KeyMap.navigation.getValue(code))} — $title",
                    acts.map { getString(it.labelRes) }, current, onCancel = { showNavigationMap(withBanner) }) { index ->
                    val overrides = KeyMap.parse(stored)
                    overrides[code] = acts[index]
                    if (withBanner) p.keyMapBanner = KeyMap.encode(overrides) else p.keyMapNoBanner = KeyMap.encode(overrides)
                    markChanged("mapowanie: $title ${getString(KeyMap.navigation.getValue(code))} → ${acts[index].name}")
                    showNavigationMap(withBanner)
                }
            }
        }
    }

    // ------------------------------------------------------------ widok listy

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (items[position] is Item.Header) 0 else 1
        override fun isEnabled(position: Int) = items[position] !is Item.Header
        override fun areAllItemsEnabled() = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val it = items[position]
            if (it is Item.Header) {
                val v = (convertView ?: layoutInflater.inflate(R.layout.item_setting_header, parent, false)) as TextView
                v.text = it.label
                return v
            }
            val v = convertView ?: layoutInflater.inflate(R.layout.item_setting, parent, false)
            v.findViewById<TextView>(R.id.label).text = it.label
            val value = v.findViewById<TextView>(R.id.value)
            value.text = when (it) {
                is Item.Toggle -> getString(if (it.get()) R.string.settings_enabled else R.string.settings_disabled)
                is Item.Text -> if (it.secret) (if (it.get().isEmpty()) "—" else "••••") else it.get().ifEmpty { "—" }
                is Item.Action -> it.value()
                else -> ""
            }
            value.setTextColor(
                when {
                    it is Item.Toggle && it.get() -> 0xFF81C784.toInt()
                    it is Item.Toggle -> 0xFFE57373.toInt()
                    it is Item.Action && value.text.toString().startsWith(getString(R.string.settings_enabled)) -> 0xFF81C784.toInt()
                    it is Item.Action && value.text.toString().startsWith(getString(R.string.settings_disabled)) -> 0xFFE57373.toInt()
                    else -> 0xFFB0BEC5.toInt()
                }
            )
            return v
        }
    }
}

private val TS_DELAYS = listOf(-1, 0, 5, 10, 15, 30)
