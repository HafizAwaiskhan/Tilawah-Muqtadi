package com.qurancompanion.core.qari

import java.util.Locale

/**
 * Mishary Alafasy, per-ayah MP3 from everyayah.com — **for the owner's personal testing only; not
 * licensed for distribution** (docs/LICENSES.md row 9). URL pattern verified 2026-11-21:
 * `https://everyayah.com/data/Alafasy_128kbps/SSSAAA.mp3` (byte-identical to the Quran.com
 * recitation 7 files, so the Quran.com word segments in data/raw apply to them).
 */
object AlafasyTestQari {
    const val ID = "alafasy-test"
    const val TITLE = "Mishary Alafasy (for personal testing only — not licensed for distribution)"
    const val BASE_URL = "https://everyayah.com/data/Alafasy_128kbps/"
    /** Debug builds only: word timings converted from data/raw segments (build task `buildQariTestTimings`). */
    const val TIMINGS_ASSET = "qari/alafasy_timings.tsv"

    fun fileName(surah: Int, ayah: Int): String = String.format(Locale.ROOT, "%03d%03d.mp3", surah, ayah)
    fun url(surah: Int, ayah: Int): String = BASE_URL + fileName(surah, ayah)

    /** Ayah count per surah (index = surah − 1; Tanzil metadata). */
    val AYAH_COUNTS: IntArray = intArrayOf(
        7, 286, 200, 176, 120, 165, 206, 75, 129, 109, 123, 111, 43, 52, 99, 128, 111, 110, 98, 135, 112, 78, 118, 64,
        77, 227, 93, 88, 69, 60, 34, 30, 73, 54, 45, 83, 182, 88, 75, 85, 54, 53, 89, 59, 37, 35, 38, 29, 18, 45, 60,
        49, 62, 55, 78, 96, 29, 22, 24, 13, 14, 11, 11, 18, 12, 12, 30, 52, 52, 44, 28, 28, 20, 56, 40, 31, 50, 40, 46,
        42, 29, 19, 36, 25, 22, 17, 19, 26, 30, 20, 15, 21, 11, 8, 8, 19, 5, 8, 8, 11, 11, 8, 3, 9, 5, 4, 7, 3, 6, 3,
        5, 4, 5, 6,
    )

    /** Bytes per surah (sum of its ayah files) from the everyayah directory listing, 2026-11-21. */
    val SURAH_BYTES: LongArray = longArrayOf(
        796680, 116381298, 76780511, 76403065, 60182500, 69492808, 76797656, 29178029, 58063752, 43082818, 44401583,
        40315767, 19428009, 19492259, 15324888, 40551972, 31981568, 31996158, 20413825, 26105270, 25169887, 28303195,
        23847803, 31366320, 18873078, 31590917, 26802120, 32574755, 21754730, 18593821, 10457046, 7640747, 25569534,
        16658748, 15472278, 16947463, 23342583, 16991562, 26086383, 25110040, 18402470, 18658556, 19660361, 9203405,
        10225874, 15236533, 11736087, 11486264, 7943083, 8965486, 8838653, 7877626, 7501856, 7850799, 11092893,
        11734238, 13155173, 9692332, 10204457, 7151255, 4594726, 3340468, 4036788, 5117678, 5710424, 5657342, 7123316,
        7257336, 6446078, 5049501, 4453974, 5303684, 3834800, 5181888, 3349156, 5003134, 4556651, 4602928, 3823879,
        3263307, 2036625, 1796286, 4619660, 2339973, 2897567, 1417780, 1541748, 1959341, 3140999, 1711568, 1177618,
        1608041, 885376, 550212, 952288, 1349906, 575744, 1816208, 803077, 968131, 858210, 849469, 323462, 767677,
        634259, 530060, 774529, 249901, 715888, 404129, 510960, 215756, 383902, 657791,
    )

    val TOTAL_BYTES: Long get() = SURAH_BYTES.sum()

    fun ayahCount(surah: Int): Int = AYAH_COUNTS.getOrElse(surah - 1) { 0 }
    fun surahBytes(surah: Int): Long = SURAH_BYTES.getOrElse(surah - 1) { 0L }
}
