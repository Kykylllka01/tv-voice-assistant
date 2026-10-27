package com.tvvoice.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compared module by module with segno 1.6.6, a mature Python QR generator: test/resources/qr/caseN.txt holds the
 * text, "version mask", then the matrix, for links from 14 to 420 bytes (versions 1 to 16), Cyrillic and every mask.
 * Two settings made segno follow the standard where it otherwise differs: text as UTF-8 (it picks Shift_JIS for
 * Cyrillic), and no extra zero byte when the data already ends on a byte boundary (ISO/IEC 18004, 7.4.10).
 */
class QrCodeTest {
    @Test
    fun matchesTheReferenceGenerator() {
        for (i in 0..5) {
            val lines = javaClass.getResourceAsStream("/qr/case$i.txt")!!.bufferedReader(Charsets.UTF_8).readLines()
            val text = lines[0]
            val (version, mask) = lines[1].split(" ").map(String::toInt)
            val expected = lines.drop(2).filter { it.isNotEmpty() }
            val qr = QrCode.encode(text, QrCode.Ecc.M, mask)
            assertEquals("version of case $i", version, qr.version)
            val actual = qr.modules.map { row -> row.joinToString("") { if (it) "1" else "0" } }
            assertEquals("modules of case $i", expected, actual)
        }
    }

    @Test
    fun picksAMaskByItself() {
        val qr = QrCode.encode("https://rbc.ru/economics/27/10/2026/kurs")
        assertTrue(qr.mask in 0..7)
        assertEquals(qr.version * 4 + 17, qr.modules.size)
    }
}
