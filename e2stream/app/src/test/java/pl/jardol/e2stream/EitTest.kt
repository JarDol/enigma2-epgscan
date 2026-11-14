package pl.jardol.e2stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EitTest {
    companion object {
        /** Sekcja EIT p/f (0x4E) z jedną audycją; start i czas w BCD jak w strumieniu. */
        fun section(sid: Int, number: Int, version: Int, eventId: Int, mjd: Int, hh: Int, mm: Int,
                    durMin: Int, title: ByteArray, text: ByteArray, ext: ByteArray = ByteArray(0)): ByteArray {
            fun bcd(v: Int) = (((v / 10) shl 4) or (v % 10)).toByte()
            val desc = java.io.ByteArrayOutputStream()
            desc.write(0x4D); desc.write(3 + 1 + title.size + 1 + text.size)
            desc.write("pol".toByteArray()); desc.write(title.size); desc.write(title); desc.write(text.size); desc.write(text)
            if (ext.isNotEmpty()) {
                desc.write(0x4E); desc.write(1 + 3 + 1 + 1 + ext.size)
                desc.write(0x00); desc.write("pol".toByteArray()); desc.write(0); desc.write(ext.size); desc.write(ext)
            }
            val d = desc.toByteArray()
            val ev = java.io.ByteArrayOutputStream()
            ev.write(eventId shr 8); ev.write(eventId and 0xff)
            ev.write(mjd shr 8); ev.write(mjd and 0xff); ev.write(bcd(hh).toInt()); ev.write(bcd(mm).toInt()); ev.write(0)
            ev.write(bcd(durMin / 60).toInt()); ev.write(bcd(durMin % 60).toInt()); ev.write(0)
            ev.write(0x80 or (d.size shr 8)); ev.write(d.size and 0xff); ev.write(d)
            val body = ev.toByteArray()
            val secLen = 11 + body.size + 4
            val s = java.io.ByteArrayOutputStream()
            s.write(0x4E); s.write(0xF0 or (secLen shr 8)); s.write(secLen and 0xff)
            s.write(sid shr 8); s.write(sid and 0xff); s.write(0xC1 or (version shl 1)); s.write(number); s.write(1)
            s.write(0x02); s.write(0x80); s.write(0x00); s.write(0x13); s.write(1); s.write(0x4E)
            s.write(body)
            val noCrc = s.toByteArray()
            val crc = Eit.crc32(noCrc, 0, noCrc.size)
            return noCrc + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
        }

        /** Sekcje jedna za drugą w pakietach TS PID 0x12 (z polem wskaźnika), reszta 0xFF. */
        fun packets(vararg sections: ByteArray): List<ByteArray> {
            val stream = java.io.ByteArrayOutputStream()
            for (s in sections) stream.write(s)
            val data = stream.toByteArray()
            val out = ArrayList<ByteArray>()
            var pos = 0
            var cc = 0
            var first = true
            while (pos < data.size) {
                val p = ByteArray(188) { 0xff.toByte() }
                p[0] = 0x47
                p[1] = ((if (first) 0x40 else 0) or 0x00).toByte()
                p[2] = 0x12
                p[3] = (0x10 or cc).toByte()
                var i = 4
                if (first) { p[i++] = 0; first = false }
                val n = minOf(188 - i, data.size - pos)
                System.arraycopy(data, pos, p, i, n)
                pos += n
                out.add(p)
                cc = (cc + 1) and 0x0f
            }
            return out
        }
    }

    @Test
    fun polishIso6937IsDecoded() {
        // Ł ó d ź  ż ą  (ISO 6937: znak diakrytyczny przed literą)
        val b = byteArrayOf(0xE8.toByte(), 0xC2.toByte(), 'o'.code.toByte(), 'd'.code.toByte(), 0xC2.toByte(), 'z'.code.toByte(),
            ' '.code.toByte(), 0xC7.toByte(), 'z'.code.toByte(), 0xCE.toByte(), 'a'.code.toByte())
        assertEquals("Łódź żą", DvbText.decode(b, 0, b.size))
    }

    @Test
    fun charsetPrefixesAndControlCodes() {
        val latin2 = byteArrayOf(0x10, 0x00, 0x02) + "Wiadomo".toByteArray() + byteArrayOf(0xB6.toByte(), 0x63, 0x69)
        assertEquals("Wiadomości", DvbText.decode(latin2, 0, latin2.size))
        val utf8 = byteArrayOf(0x15) + "Fakty – godz. 19:00".toByteArray(Charsets.UTF_8)
        assertEquals("Fakty – godz. 19:00", DvbText.decode(utf8, 0, utf8.size))
        val ctrl = byteArrayOf(0x86.toByte()) + "Film".toByteArray() + byteArrayOf(0x87.toByte(), 0x8A.toByte()) + "opis".toByteArray()
        assertEquals("Film\nopis", DvbText.decode(ctrl, 0, ctrl.size))
    }

    @Test
    fun presentAndFollowingAreParsedFromStream() {
        val mjd = 61358   // 2026-11-14
        val now = section(0x3DD2, 0, 3, 1001, mjd, 18, 30, 30, "Teleexpress".toByteArray(), "Serwis".toByteArray(),
            ext = "Dluzszy opis".toByteArray())
        val next = section(0x3DD2, 1, 3, 1002, mjd, 19, 0, 75, byteArrayOf(0x15) + "Wiadomości".toByteArray(), ByteArray(0))
        val other = section(0x1111, 0, 1, 7, mjd, 18, 0, 60, "Inny".toByteArray(), ByteArray(0))
        val got = ArrayList<Eit.PfSection>()
        val asm = SectionAssembler { b, len -> Eit.parsePf(b, len, 0x3DD2)?.let { got.add(it) } }
        for (p in packets(other, now, next)) asm.packet(p, 0, 4)
        assertEquals(2, got.size)
        val present = got.first { it.number == 0 }.event
        assertNotNull(present)
        assertEquals("Teleexpress", present!!.title)
        assertEquals("Serwis", present.shortDesc)
        assertEquals("Dluzszy opis", present.longDesc)
        assertEquals((mjd - 40587L) * 86400 + 18 * 3600 + 30 * 60, present.begin)
        assertEquals(30 * 60L, present.duration)
        val following = got.first { it.number == 1 }
        assertEquals(3, following.version)
        assertEquals("Wiadomości", following.event!!.title)
        assertEquals(75 * 60L, following.event!!.duration)
    }

    @Test
    fun brokenCrcAndOtherServiceAreIgnored() {
        val s = section(5, 0, 0, 1, 61358, 10, 0, 10, "A".toByteArray(), ByteArray(0))
        assertNull(Eit.parsePf(s, s.size, 6))
        s[20] = (s[20].toInt() xor 1).toByte()
        assertNull(Eit.parsePf(s, s.size, 5))
    }

    @Test
    fun lostPacketDropsUnfinishedSection() {
        val long = section(9, 0, 0, 1, 61358, 10, 0, 10, ByteArray(120) { 'x'.code.toByte() }, ByteArray(120) { 'y'.code.toByte() },
            ext = ByteArray(240) { 'z'.code.toByte() })
        val pk = packets(long)
        assertEquals(3, pk.size)
        val got = ArrayList<Eit.PfSection>()
        val asm = SectionAssembler { b, len -> Eit.parsePf(b, len, 9)?.let { got.add(it) } }
        asm.packet(pk[0], 0, 4)
        asm.packet(pk[2], 0, 4)   // pakiet 1 zgubiony
        assertEquals(0, got.size)
        val whole = SectionAssembler { b, len -> Eit.parsePf(b, len, 9)?.let { got.add(it) } }
        for (p in pk) whole.packet(p, 0, 4)
        assertEquals(1, got.size)
        assertEquals(240, got[0].event!!.longDesc.length)
    }
}

class EitMergeTest {
    private fun ev(id: Int, b: Long, minutes: Int, t: String) = EitEvent(id, b, minutes * 60L, t, "", "")
    private fun merge(base: List<EitEvent>, eit: List<EitEvent>) =
        EitMerge.merge(base, eit, { it.begin }, { it.end }, { it.id })
    private fun changes(base: List<EitEvent>, eit: List<EitEvent>) =
        EitMerge.changes(base, eit, { it.begin }, { it.end }, { it.title })

    @Test
    fun changedProgrammeReplacesOverlappingEvents() {
        val base = listOf(ev(1, 0, 30, "Teleexpress"), ev(2, 1800, 30, "Pogoda"), ev(3, 3600, 60, "Film"))
        val eit = listOf(ev(10, 0, 45, "Sport - transmisja"), ev(11, 2700, 15, "Pogoda"))
        val merged = merge(base, eit)
        assertEquals(listOf("Sport - transmisja", "Pogoda", "Film"), merged.map { it.title })
        assertEquals(listOf(0L, 2700L, 3600L), merged.map { it.begin })
        val ch = changes(base, eit)
        assertEquals(2, ch.size)
        assertEquals("Teleexpress", ch[0].first!!.title)
        assertEquals("Sport - transmisja", ch[0].second.title)
    }

    @Test
    fun identicalEventsAreNotReportedAsChanges() {
        val base = listOf(ev(1, 0, 30, "Teleexpress"), ev(2, 1800, 30, "Pogoda"))
        val eit = listOf(ev(1, 30, 30, "TELEEXPRESS "), ev(2, 1800, 31, "Pogoda"))
        assertEquals(0, changes(base, eit).size)
        assertEquals(2, merge(base, eit).size)
    }
}
