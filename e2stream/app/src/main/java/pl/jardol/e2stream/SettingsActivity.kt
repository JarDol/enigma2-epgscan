package pl.jardol.e2stream

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
            "Dekoder Enigma2" -> listOf(
                Item.Header(getString(R.string.setting_receiver)),
        Item.Text(getString(R.string.setting_receiver_ip_address), { p.host }, { p.host = it.trim(); checkPluginQuiet() }, hint = "np. 192.168.1.20"),
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
        Item.Action(getString(R.string.setting_exoplayer_decoder_frame_rate_experimental), { if (p.exoFps > 0) getString(R.string.settings_fps_value, p.exoFps) else getString(R.string.settings_fps) }) {
            val ids = intArrayOf(0, 25, 50)
            TvDialog.single(this, getString(R.string.message_decoder_frame_rate_experimental), listOf(getString(R.string.settings_fps), getString(R.string.settings_fps_value, 25), getString(R.string.settings_fps_value, 50)),
                ids.indexOf(p.exoFps)) { w -> p.exoFps = ids[w]; markChanged("ExoPlayer fps = ${ids[w]}") }
        },
        Item.Toggle(getString(R.string.setting_restart_on_stuttering_dropped_frames), { p.dropRestart }, { p.dropRestart = it }),
        num(getString(R.string.setting_playback_buffer_ms), { p.rebufferMs }, { p.rebufferMs = it }, 1000..15_000),
        Item.Toggle(getString(R.string.setting_build_buffer_by_slowing_playback_slightly_5_without_tun), { p.cushionSlow }, { p.cushionSlow = it }),
    )) + listOf(
        Item.Toggle(getString(R.string.setting_restart_frozen_video_watchdog), { p.watchdog }, { p.watchdog = it }),
        num(getString(R.string.setting_freeze_after_seconds_without_new_frames), { p.stallSec }, { p.stallSec = it }, 1..30),
        num(getString(R.string.setting_startup_buffer_ms_fast_picture), { p.bufferMs }, { p.bufferMs = it }, 250..10_000),
        Item.Action(getString(R.string.setting_screen_refresh_rate), { afrName(p.afrMode) }) { chooseAfr() },
                Item.Header(getString(R.string.setting_timeshift_pause_and_rewind_live_tv)),
        Item.Action(getString(R.string.setting_timeshift), { tsValue() }) { chooseTs() },
        Item.Action(getString(R.string.setting_timeshift_buffer_location), { tsPlace() }) { chooseTsDir() },
        Item.Action(getString(R.string.setting_automatic_timeshift), { tsDelayName(p.tsDelay) }) { chooseTsDelay() },
        Item.Action(getString(R.string.setting_seek_seconds), { "${p.tsStepSec} s" }) {
            val o = listOf(5, 10, 15, 30)
            TvDialog.single(this, getString(R.string.message_seek), o.map { "$it s" }, o.indexOf(p.tsStepSec).coerceAtLeast(0)) { w -> p.tsStepSec = o[w]; markChanged("timeshift: krok ◀ ▶ = ${o[w]} s"); items = buildItems(); refresh() }
        },
        Item.Action(getString(R.string.setting_seek_minutes), { "${p.tsStepMin} min" }) {
            val o = listOf(1, 3, 5, 10, 15)
            TvDialog.single(this, getString(R.string.message_seek_2), o.map { "$it min" }, o.indexOf(p.tsStepMin).coerceAtLeast(0)) { w -> p.tsStepMin = o[w]; markChanged("timeshift: krok ▲ ▼ = ${o[w]} min"); items = buildItems(); refresh() }
        },
        Item.Toggle(getString(R.string.setting_back_on_empty_screen_live_tv), { p.tsBackLive }, { p.tsBackLive = it }),
        Item.Toggle(getString(R.string.setting_allow_buffer_in_device_storage), { p.tsInternalOk }, { v ->
            p.tsInternalOk = v
            if (!v && p.tsDir.isBlank()) p.tsMin = 0        // bez zgody i bez dysku — timeshift wyłączony
        }),
                Item.Header(getString(R.string.setting_audio)),
        Item.Text(getString(R.string.setting_preferred_language), { p.audioLang }, { p.audioLang = it }, hint = "Kody lub nazwy, np. pl/pol/Polish, en/eng/English"),
        Item.Toggle(getString(R.string.setting_prefer_ac3_e_ac3), { p.preferAc3 }, { p.preferAc3 = it }),
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
        Item.Action(getString(R.string.setting_epg_scan_update), {
            getString(if (p.epgUpdateMode == EpgUpdatePolicy.STARTUP) R.string.settings_every_start else R.string.settings_daily)
        }) {
            val modes = listOf(EpgUpdatePolicy.DAILY, EpgUpdatePolicy.STARTUP)
            TvDialog.single(this, getString(R.string.message_epg_scan_update),
                listOf(getString(R.string.settings_daily), getString(R.string.settings_every_start)),
                modes.indexOf(p.epgUpdateMode).coerceAtLeast(0)) { w ->
                p.epgUpdateMode = modes[w]
                markChanged("EPG Scan: aktualizacja ${if (w == 0) "raz dziennie" else "przy uruchomieniu"}")
                refresh()
            }
        },
        Item.Toggle(getString(R.string.setting_download_epg_daily_without_epg_scan_plugin), { p.epgDaily }, { p.epgDaily = it }),
        Item.Text(getString(R.string.setting_epg_download_time), { p.epgHour },
            { v -> if (Regex("^\\d{1,2}:\\d{2}$").matches(v.trim())) p.epgHour = v.trim() else toast(getString(R.string.message_format_hh_mm)) },
            InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME, hint = "HH:MM"),
        num(getString(R.string.setting_days_of_epg_to_download), { p.epgDays }, { p.epgDays = it }, 1..14),
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
        Item.Toggle(getString(R.string.setting_resume_last_channel_on_startup), { p.resumeLast }, { p.resumeLast = it }),
        Item.Action(getString(R.string.setting_switch_to_channel_on_startup), {
            if (p.resumeLast) getString(R.string.settings_start_disabled) else p.startChannelName.ifBlank { getString(R.string.settings_first_channel) }
        }) { if (p.resumeLast) toast(getString(R.string.message_first_disable_resume_last_channel_on_startup)) else chooseStartChannel() },
        Item.Toggle(getString(R.string.setting_wake_tv_for_scheduled_switches_experimental), { p.wakeForZap }, { setWakeForZap(it) }),
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
        num(getString(R.string.setting_timeshift_banner_duration_s), { p.tsBannerSec }, { p.tsBannerSec = it }, 1..30),
        num(getString(R.string.setting_channel_list_duration_s_0_no_timeout), { p.channelListSec }, { p.channelListSec = it }, 0..600),
        num(getString(R.string.setting_action_dialog_duration_s_0_no_timeout), { p.actionDialogSec }, { p.actionDialogSec = it }, 0..600),
        num(getString(R.string.setting_confirmation_dialog_duration_s_0_no_timeout), { p.confirmDialogSec }, { p.confirmDialogSec = it }, 0..600),
        num(getString(R.string.setting_channel_epg_duration_s_0_no_timeout), { p.epgChannelSec }, { p.epgChannelSec = it }, 0..600),
        num(getString(R.string.setting_multi_epg_duration_s_0_no_timeout), { p.epgMultiSec }, { p.epgMultiSec = it }, 0..600),
        num(getString(R.string.setting_information_dialog_duration_s_0_no_timeout), { p.infoDialogSec }, { p.infoDialogSec = it }, 0..600),
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
        Item.Toggle(getString(R.string.setting_automatic_update), { p.autoUpdate }, { p.autoUpdate = it }),
                Item.Action(getString(R.string.setting_application_information_readme)) { showReadme("E2Stream", "e2stream/README.md") },
                Item.Header(getString(R.string.setting_epg_scan_plugin)),
                Item.Action(getString(R.string.setting_plugin_on_receiver), { if (p.pluginOk) getString(R.string.settings_version, p.pluginVersion) else getString(R.string.settings_plugin_absent_details) }) { checkPlugin() },
                Item.Action(getString(R.string.setting_plugin_information_readme)) { showReadme("EPG Scan", "epgscan/EPGScan/README.md") },
            )
            else -> listOfNotNull(
        Item.Toggle(getString(R.string.setting_on_screen_statistics), { p.showStats }, { p.showStats = it }),
                if (p.pluginOk) null else Item.Action(getString(R.string.setting_without_epg_scan_sending_to_the_receiver_requires_ftp),
                    { getString(R.string.settings_set_decoder) }) { openSection("Dekoder Enigma2") },
        Item.Action(getString(R.string.setting_send_log_to_receiver), { "${p.ftpDir}/${Tools.logName(p)}" }) { Tools.uploadLog(this, p, true) { toast(it) } },
        Item.Action(getString(R.string.setting_send_ts_dumps_to_receiver)) { Tools.uploadDumps(this, p) { toast(it) } },
        Item.Action(getString(R.string.setting_log_and_dump_storage_location), { Storage.dir(this, p).absolutePath }) { chooseStorage() },
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
            openSection("Odtwarzanie i obraz")
            val index = items.indexOfFirst { it is Item.Action && it.label == getString(R.string.setting_timeshift) }
            check(index >= 0) { "Brak ustawienia timeshiftu" }
            listView.setSelection(index)
        }
    }

    override fun onResume() {
        super.onResume()
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
        pendingWakeEnable = false
        p.wakeForZap = true
        ZapWakeAlarms.sync(this)
        markChanged("Budź TV dla harmonogramu = włączone")
        toast(getString(R.string.message_scheduled_wake_up_enabled))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 741) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) continueWakeEnable()
        else { pendingWakeEnable = false; toast(getString(R.string.message_without_notifications_reminders_may_not_appear_on_scree)) }
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(relock)
        if (changed) { p.bump(); changed = false }
        SyncManager.syncAsync() // zmiany od razu na dekoder
    }

    override fun onDestroy() {
        discoveryThread?.interrupt()
        super.onDestroy()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun refresh() = adapter.notifyDataSetChanged()

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

    // ------------------------------------------------------------ timeshift

    /** Czy wybrany jest dysk USB / karta SD (nie pamięć urządzenia). */
    private fun tsUsb(): Boolean = p.tsDir.isNotBlank() && p.tsDir != TimeshiftBuffer.internalBase(this).absolutePath

    private fun tsFit(): Int = TimeshiftBuffer.dir(this, p)?.let { TimeshiftBuffer.fitMinutes(it) } ?: 0

    private fun tsValue(): String {
        val d = TimeshiftBuffer.dir(this, p)
            ?: return getString(if (p.tsDir.isNotBlank()) R.string.ts_usb_unavailable else R.string.ts_choose_location)
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
        Storage.options(this).firstOrNull { it.path == path }?.label?.substringBefore("\n")?.substringBefore(" —") ?: path

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
            TvDialog.Item(o.label.substringBefore("\n").substringBefore(" —"),
                if (usable) getString(R.string.ts_permission_needed)
                else getString(R.string.ts_space_choice, TimeshiftBuffer.gb(java.io.File(o.path).usableSpace), fmtMin(fit)) + if (rec > 0) getString(R.string.ts_recommended_choice, fmtMin(rec)) else "",
                if (cur) 3 else 0)
        }
        TvDialog.list(this, getString(R.string.message_timeshift_buffer_location), items, -1, widthFrac = 0.6f) { w ->
            val o = opts[w]
            if (o.path == internal) {
                if (!p.tsInternalOk) {
                    TvDialog.confirm(this, getString(R.string.message_buffer_in_device_storage),
                        getString(R.string.ts_flash_warning),
                        listOf(TvDialog.Btn(getString(R.string.message_allow)) { p.tsInternalOk = true; setTsDir("") }, TvDialog.Btn(getString(R.string.message_no))))
                } else setTsDir("")
            } else setTsDir(o.path)
        }
    }

    private fun setTsDir(path: String) {
        p.tsDir = path
        val fit = tsFit()
        val rec = TimeshiftBuffer.recommended(fit, tsUsb())
        if (p.tsMin > fit) {
            p.tsMin = rec
            toast(if (rec > 0) getString(R.string.ts_capacity_set, fmtMin(fit), fmtMin(rec)) else getString(R.string.ts_disabled_space))
        } else if (p.tsMin == 0 && rec > 0) {
            toast(getString(R.string.ts_capacity_recommended, fmtMin(fit), fmtMin(rec)))
        }
        markChanged("timeshift: miejsce = ${path.ifBlank { "pamięć urządzenia" }}")
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
            p.storageDir = opts[w].path; markChanged("zapis = ${opts[w].path}")
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
                            TvDialog.Btn(getString(R.string.dialog_cancel)),
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
    }

    private fun chooseBouquets() {
        toast(getString(R.string.message_loading_bouquets))
        Thread {
            val list = try { OpenWebif(p).bouquets() + Bouquet(getString(R.string.receiver_recordings), REC_BOUQUET) } catch (e: Exception) { null }
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
                val others = result.getOrThrow().filter { !it.file.contains(SyncManager.deviceId()) || it.file.endsWith("#previous") }
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
                    else -> 0xFFB0BEC5.toInt()
                }
            )
            return v
        }
    }
}

private val TS_DELAYS = listOf(-1, 0, 5, 10, 15, 30)
