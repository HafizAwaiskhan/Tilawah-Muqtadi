package com.qurancompanion

import com.qurancompanion.data.reference.EngineReferenceBuilder
import com.qurancompanion.data.reference.EngineWordRow
import com.qurancompanion.engine.reference.QuranReference
import org.junit.Assume.assumeTrue
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Opens data/build/quran_ref_v1.db directly with sqlite-jdbc (path from the `quranRefDb` system
 * property set in app/build.gradle.kts). Tests using it are skipped when the DB is absent.
 */
object RealReferenceDb {
    val file: File? = System.getProperty("quranRefDb")?.let(::File)?.takeIf { it.isFile }

    fun assumeAvailable() = assumeTrue("reference DB not built", file != null)

    fun <T> connect(block: (Connection) -> T): T {
        assumeAvailable()
        return DriverManager.getConnection("jdbc:sqlite:${file!!.absolutePath}").use(block)
    }

    /** Same mapping as the Android loader (SqliteQuranReferenceRepository.forEachEngineWord). */
    fun build(): QuranReference = connect { c ->
        val b = EngineReferenceBuilder(77_429)
        c.createStatement().use { st ->
            st.executeQuery(EngineReferenceBuilder.WORD_QUERY).use { rs ->
                while (rs.next()) {
                    b.add(
                        EngineWordRow(
                            rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                            rs.getString(5).orEmpty(), rs.getString(6).orEmpty(), rs.getString(7).orEmpty(),
                        )
                    )
                }
            }
        }
        b.build()
    }

    val reference: QuranReference by lazy { build() }
}
