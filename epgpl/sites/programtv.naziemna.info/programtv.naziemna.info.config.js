// SPDX-License-Identifier: GPL-2.0-or-later
// epgpl - grabber programtv.naziemna.info dla iptv-org/epg (strona stacji na dany dzien)
// Wlasny kod epgpl w formacie konfiguracji serwisu iptv-org/epg (Unlicense); uruchamiany przez grabber iptv-org,
// korzysta z jego bibliotek cheerio i dayjs (licencja MIT). Szczegoly: THIRD_PARTY.md.
// Dzien na stronie trwa od ok. 4:00 do ok. 4:00 nastepnego dnia - godziny po polnocy naleza do dnia nastepnego.
const cheerio = require('cheerio')
const dayjs = require('dayjs')
const utc = require('dayjs/plugin/utc')
const timezone = require('dayjs/plugin/timezone')
const customParseFormat = require('dayjs/plugin/customParseFormat')
dayjs.extend(utc)
dayjs.extend(timezone)
dayjs.extend(customParseFormat)

const TZ = 'Europe/Warsaw'
const MONTHS = ['stycznia', 'lutego', 'marca', 'kwietnia', 'maja', 'czerwca', 'lipca', 'sierpnia',
  'września', 'października', 'listopada', 'grudnia']

module.exports = {
  site: 'programtv.naziemna.info',
  days: 2,
  delay: 3000,
  request: {
    headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)' }
  },
  url({ channel, date }) {
    const d = date.format('DD') + '-' + encodeURIComponent(MONTHS[date.month()])
    return `https://programtv.naziemna.info/program/stacja/${channel.site_id},${d}`
  },
  parser({ content, date }) {
    const $ = cheerio.load(content || '')
    const day = date.format('YYYY-MM-DD')
    const programs = []
    let offset = 0
    let prev = -1
    $('div.my-record-cell').each((i, el) => {
      const item = $(el)
      const m = item.find('span.text-monospace').first().text().trim().match(/^(\d{1,2}):(\d{2})/)
      if (!m) return
      const title = item.find('strong').first().text().trim()
      if (!title) return
      const mins = parseInt(m[1], 10) * 60 + parseInt(m[2], 10)
      if (prev >= 0 && mins < prev) offset++          // przejscie przez polnoc
      prev = mins
      const p = item.find('p').first().clone()
      p.find('span, strong, br').remove()
      const genre = p.text().replace(/[|\s]+/g, ' ').trim()
      const start = dayjs.tz(`${day} ${m[1]}:${m[2]}`, 'YYYY-MM-DD H:mm', TZ).add(offset, 'day')
      programs.push({ title, description: genre || undefined, category: genre || undefined, start, stop: start.add(1, 'hour') })
    })
    for (let i = 0; i < programs.length - 1; i++) programs[i].stop = programs[i + 1].start
    return programs
  }
}
