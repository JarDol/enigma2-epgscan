// SPDX-License-Identifier: GPL-2.0-or-later
// epgpl - grabber teleman.pl dla iptv-org/epg (strona stacji, caly dzien)
// Wlasny kod epgpl w formacie konfiguracji serwisu iptv-org/epg (Unlicense); uruchamiany przez grabber iptv-org,
// korzysta z jego bibliotek cheerio i dayjs (licencja MIT). Szczegoly: THIRD_PARTY.md.
const cheerio = require('cheerio')
const dayjs = require('dayjs')
const utc = require('dayjs/plugin/utc')
const timezone = require('dayjs/plugin/timezone')
const customParseFormat = require('dayjs/plugin/customParseFormat')
dayjs.extend(utc)
dayjs.extend(timezone)
dayjs.extend(customParseFormat)

const TZ = 'Europe/Warsaw'

module.exports = {
  site: 'teleman.pl',
  days: 2,
  delay: 3000,
  request: {
    headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)' }
  },
  url({ channel, date }) {
    return `https://www.teleman.pl/program-tv/stacje/${channel.site_id}?date=${date.format('YYYY-MM-DD')}&hour=-1`
  },
  parser({ content, date }) {
    const $ = cheerio.load(content || '')
    const day = date.format('YYYY-MM-DD')
    const programs = []
    let offset = 0
    let prev = -1
    $('ul.stationItems > li[id^="prog"]').each((i, el) => {
      const item = $(el)
      const m = item.children('em').first().text().trim().match(/^(\d{1,2}):(\d{2})$/)
      if (!m) return
      const mins = parseInt(m[1], 10) * 60 + parseInt(m[2], 10)
      if (prev >= 0 && mins < prev) offset++          // przejscie przez polnoc
      prev = mins
      const start = dayjs.tz(`${day} ${m[1]}:${m[2]}`, 'YYYY-MM-DD H:mm', TZ).add(offset, 'day')
      const detail = item.children('.detail')
      const title = detail.children('a').first().text().trim()
      if (!title) return
      const genre = detail.children('p.genre').first().text().trim()
      const desc = detail.children('p').not('.genre').map((j, p) => $(p).text().trim()).get().join(' ')
      programs.push({
        title,
        description: [genre, desc].filter(Boolean).join('\n') || undefined,
        start,
        stop: start.add(1, 'hour')
      })
    })
    for (let i = 0; i < programs.length - 1; i++) programs[i].stop = programs[i + 1].start
    return programs
  }
}
