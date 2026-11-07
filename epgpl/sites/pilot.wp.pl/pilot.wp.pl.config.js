// SPDX-License-Identifier: GPL-2.0-or-later
// epgpl - grabber pilot.wp.pl dla iptv-org/epg
// Wlasny kod epgpl w formacie konfiguracji serwisu iptv-org/epg (Unlicense); uruchamiany przez grabber iptv-org,
// korzysta z jego bibliotek cheerio i dayjs (licencja MIT). Szczegoly: THIRD_PARTY.md.
// Strona kanalu zawiera w __PRELOAD_STATE__ program na ok. 7 dni; parser wybiera audycje z danego dnia.
const dayjs = require('dayjs')
const utc = require('dayjs/plugin/utc')
const timezone = require('dayjs/plugin/timezone')
dayjs.extend(utc)
dayjs.extend(timezone)

const TZ = 'Europe/Warsaw'

module.exports = {
  site: 'pilot.wp.pl',
  days: 2,
  delay: 3000,
  request: {
    headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)' },
    // strona kanalu zawiera caly tydzien - kolejne dni biora ja z pamieci zamiast pobierac ponownie
    cache: { ttl: 3 * 60 * 60 * 1000 }
  },
  url({ channel }) {
    return `https://pilot.wp.pl/program-tv/kanal/${channel.site_id}`
  },
  parser({ content, date }) {
    const m = (content || '').match(/<script id="__PRELOAD_STATE__" type="application\/json">([\s\S]*?)<\/script>/)
    if (!m) return []
    let state
    try {
      state = JSON.parse(m[1])
    } catch (e) {
      return []
    }
    const entries = Object.values(state?.ProgramStore?.singleChannelEntries || {})
    const day = date.format('YYYY-MM-DD')
    return entries
      .filter(e => e.start && e.end && e.title && dayjs(e.start).tz(TZ).format('YYYY-MM-DD') === day)
      .sort((a, b) => a.start - b.start)
      .map(e => ({
        title: e.title,
        sub_title: e.episode_title || undefined,
        description: e.genre || undefined,
        category: e.category || undefined,
        start: dayjs(e.start),
        stop: dayjs(e.end)
      }))
  }
}
