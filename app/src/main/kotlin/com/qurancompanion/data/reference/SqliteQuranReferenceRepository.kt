package com.qurancompanion.data.reference

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.qurancompanion.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the prebuilt reference DB (asset `quran_ref_v1.db`) with a plain read-only
 * [SQLiteDatabase]. The asset is copied to the app's database dir on first run and again
 * whenever the APK is updated (so a new data version ships with a new app version).
 *
 * Schema: docs/REFERENCE_DB_SCHEMA.md (schema_version 1). Only the columns needed for display
 * are read; per-word script choice is centralised in [WordDisplayRule].
 */
@Singleton
class SqliteQuranReferenceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : QuranReferenceRepository {

    private val assetName = BuildConfig.REFERENCE_DB_ASSET
    private val mutex = Mutex()
    private var db: SQLiteDatabase? = null
    private var surahCache: List<SurahInfo>? = null

    private val _status = MutableStateFlow<ReferenceStatus>(ReferenceStatus.Loading)
    override val status: StateFlow<ReferenceStatus> = _status.asStateFlow()

    override suspend fun ensureReady(): ReferenceStatus = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (db != null) return@withLock _status.value
            val result = try {
                val file = copyAssetIfNeeded()
                if (file == null) {
                    ReferenceStatus.Missing
                } else {
                    val opened = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
                    db = opened
                    val version = readMeta(opened, "data_version") ?: readMeta(opened, "schema_version")
                    val count = opened.rawQuery("SELECT COUNT(*) FROM surah", null).use { c ->
                        if (c.moveToFirst()) c.getInt(0) else 0
                    }
                    ReferenceStatus.Ready(version, count)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to open reference DB", t)
                ReferenceStatus.Failed(t.message ?: t.javaClass.simpleName)
            }
            _status.value = result
            result
        }
    }

    /** Returns the local DB file, or null when the asset is not bundled. */
    private fun copyAssetIfNeeded(): File? {
        val target = context.getDatabasePath(assetName)
        val apkUpdated = context.packageManager
            .getPackageInfo(context.packageName, 0).lastUpdateTime
        if (target.exists() && target.lastModified() >= apkUpdated) return target
        val input = try {
            context.assets.open(assetName)
        } catch (_: FileNotFoundException) {
            return if (target.exists()) target else null
        }
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$assetName.tmp")
        input.use { src -> tmp.outputStream().use { dst -> src.copyTo(dst) } }
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Could not move reference DB into place" }
        }
        return target
    }

    private fun readMeta(db: SQLiteDatabase, key: String): String? = try {
        db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private suspend fun requireDb(): SQLiteDatabase? {
        ensureReady()
        return db
    }

    override suspend fun surahs(): List<SurahInfo> = withContext(Dispatchers.IO) {
        surahCache?.let { return@withContext it }
        val database = requireDb() ?: return@withContext emptyList()
        val list = database.rawQuery("SELECT * FROM surah ORDER BY id", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        SurahInfo(
                            id = c.int("id"),
                            nameArabic = c.str("name_arabic"),
                            nameTransliterated = c.str("name_transliterated"),
                            nameEnglish = c.str("name_english"),
                            ayahCount = c.int("ayah_count"),
                        )
                    )
                }
            }
        }
        surahCache = list
        list
    }

    override suspend fun surah(id: Int): SurahInfo? = surahs().firstOrNull { it.id == id }

    override suspend fun ayahs(surah: Int, script: Script): List<AyahText> =
        loadAyahs(surah, null, script)

    override suspend fun ayah(surah: Int, ayah: Int, script: Script): AyahText? =
        loadAyahs(surah, ayah, script).firstOrNull()

    private suspend fun loadAyahs(surah: Int, ayah: Int?, script: Script): List<AyahText> =
        withContext(Dispatchers.IO) {
            val database = requireDb() ?: return@withContext emptyList()
            val ayahFilter = if (ayah != null) " AND ayah = ?" else ""
            val args = listOfNotNull(surah.toString(), ayah?.toString()).toTypedArray()

            val wordsByAyah = HashMap<Int, MutableList<WordText>>()
            database.rawQuery(
                "SELECT * FROM word WHERE surah = ?$ayahFilter ORDER BY ayah, word_index", args
            ).use { c ->
                while (c.moveToNext()) {
                    val text = WordDisplayRule.select(
                        script = script,
                        indopak = c.strOrNull("text_indopak"),
                        indopakCheck = c.strOrNull("indopak_check"),
                        uthmani = c.strOrNull("text_uthmani"),
                    )
                    if (text != null) {
                        wordsByAyah.getOrPut(c.int("ayah")) { mutableListOf() }
                            .add(WordText(c.int("word_index"), text))
                    }
                }
            }
            database.rawQuery("SELECT * FROM ayah WHERE surah = ?$ayahFilter ORDER BY ayah", args).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val n = c.int("ayah")
                        val words = wordsByAyah[n]
                            ?: splitAyahText(c.strOrNull("text_uthmani").orEmpty())
                        add(AyahText(surah, n, c.intOrNull("global_index") ?: 0, words))
                    }
                }
            }
        }

    override suspend fun forEachEngineWord(consumer: (EngineWordRow) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val database = requireDb() ?: return@withContext false
        database.rawQuery(EngineReferenceBuilder.WORD_QUERY, null).use { c ->
            while (c.moveToNext()) {
                consumer(
                    EngineWordRow(
                        id = c.getInt(0), surah = c.getInt(1), ayah = c.getInt(2), wordIndex = c.getInt(3),
                        normSkeleton = c.getString(4).orEmpty(), phonetic = c.getString(5).orEmpty(),
                        stopMarks = c.getString(6).orEmpty(),
                    )
                )
            }
        }
        true
    }

    override suspend fun meta(key: String): String? = withContext(Dispatchers.IO) {
        requireDb()?.let { readMeta(it, key) }
    }

    private fun Cursor.idx(col: String): Int = getColumnIndex(col)
    private fun Cursor.int(col: String): Int = getInt(getColumnIndexOrThrow(col))
    private fun Cursor.intOrNull(col: String): Int? = idx(col).takeIf { it >= 0 && !isNull(it) }?.let { getInt(it) }
    private fun Cursor.str(col: String): String = strOrNull(col).orEmpty()
    private fun Cursor.strOrNull(col: String): String? = idx(col).takeIf { it >= 0 && !isNull(it) }?.let { getString(it) }

    companion object {
        private const val TAG = "QuranReference"

        /** Fallback when the word table has no rows for an ayah: whitespace segmentation. */
        fun splitAyahText(text: String): List<WordText> =
            text.split(Regex("\\s+")).filter { it.isNotBlank() }
                .mapIndexed { i, w -> WordText(i + 1, w) }
    }
}
