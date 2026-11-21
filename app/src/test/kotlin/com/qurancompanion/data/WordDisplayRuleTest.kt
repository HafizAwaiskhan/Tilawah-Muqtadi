package com.qurancompanion.data

import com.qurancompanion.data.reference.Script
import com.qurancompanion.data.reference.SqliteQuranReferenceRepository
import com.qurancompanion.data.reference.WordDisplayRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Placeholder tokens only: no Quran text is typed into code (texts come from the reference DB).
class WordDisplayRuleTest {
    @Test fun indopakUsedOnlyWhenVerified() {
        assertEquals("IP", WordDisplayRule.select(Script.INDOPAK, "IP", "match", "UT"))
        assertEquals("UT", WordDisplayRule.select(Script.INDOPAK, "IP", "fuzzy", "UT"))
        assertEquals("UT", WordDisplayRule.select(Script.INDOPAK, "IP", "mismatch", "UT"))
        assertEquals("UT", WordDisplayRule.select(Script.INDOPAK, null, "match", "UT"))
    }

    @Test fun uthmaniScriptIgnoresIndopak() {
        assertEquals("UT", WordDisplayRule.select(Script.UTHMANI, "IP", "match", "UT"))
        assertNull(WordDisplayRule.select(Script.UTHMANI, "IP", "match", " "))
    }

    @Test fun whitespaceSplitFallbackIsOneBased() {
        val words = SqliteQuranReferenceRepository.splitAyahText("  a  b c ")
        assertEquals(listOf(1, 2, 3), words.map { it.index })
        assertEquals(listOf("a", "b", "c"), words.map { it.text })
    }
}
