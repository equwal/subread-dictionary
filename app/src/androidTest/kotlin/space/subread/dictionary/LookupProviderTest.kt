package space.subread.dictionary

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import space.subread.dictionary.core.DictionaryIndex
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The provider that another app asks for the terms of a text. It reads the dictionaries of
 * the app itself, so the test imports one there and deletes it after.
 */
@RunWith(AndroidJUnit4::class)
class LookupProviderTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var dictionaries: Dictionaries
    private var imported: Long = -1

    private val termBank = """[
        ["食べる","たべる","v1","v1",10,["to eat"],1,"P"],
        ["食べ物","たべもの","n","",5,["food"],2,""]
    ]"""

    private val metaBank = """[["食べる","freq",{"value":100,"displayValue":"100"}],["食べる","pitch",{"reading":"たべる","pitches":[{"position":2}]}]]"""

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            for ((name, body) in files) {
                out.putNextEntry(ZipEntry(name))
                out.write(body.toByteArray())
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Before
    fun import() {
        dictionaries = Dictionaries(context)
        dictionaries.list().firstOrNull { it.title == TITLE }?.let { dictionaries.delete(it.id) }
        imported = dictionaries.import(
            DictionaryIndex(TITLE, "1", 3),
            ByteArrayInputStream(zip("term_bank_1.json" to termBank, "term_meta_bank_1.json" to metaBank)),
        ) {}.id
    }

    @After
    fun remove() {
        dictionaries.delete(imported)
        dictionaries.close()
    }

    @Test
    fun theTermsOfATextComeAsRows() {
        val rows = context.contentResolver.query(LookupProvider.lookupUri("食べたい"), null, null, null, null)!!.use { c ->
            buildList {
                while (c.moveToNext()) add(
                    LookupProvider.COLUMNS.associateWith { column ->
                        val i = c.getColumnIndexOrThrow(column)
                        if (c.isNull(i)) null else c.getString(i)
                    },
                )
            }
        }
        val first = rows.first { it[LookupProvider.COLUMN_DICTIONARY] == TITLE }
        assertEquals("食べる", first[LookupProvider.COLUMN_EXPRESSION])
        assertEquals("たべる", first[LookupProvider.COLUMN_READING])
        assertEquals("3", first[LookupProvider.COLUMN_LENGTH])
        assertTrue(first[LookupProvider.COLUMN_GLOSSARY]!!.contains("to eat"))
        assertTrue(first[LookupProvider.COLUMN_FREQUENCY]!!.contains("100"))
        assertTrue(first[LookupProvider.COLUMN_PITCH]!!.isNotEmpty())
        // No audio source is set up on a test device: the row says so.
        if (!Store(context).localAudioFile.isFile && !Store(context).remoteEnabled) assertNull(first[LookupProvider.COLUMN_AUDIO])
    }

    @Test
    fun anEmptyTextGivesNoRow() {
        context.contentResolver.query(LookupProvider.lookupUri(""), null, null, null, null)!!.use { assertEquals(0, it.count) }
    }

    @Test
    fun aTermWithoutAudioIsNotFound() {
        val uri = LookupProvider.audioUri("食べる", "たべる")
        if (Store(context).localAudioFile.isFile || Store(context).remoteEnabled) return
        try {
            context.contentResolver.openInputStream(uri)?.close()
            assertTrue("openInputStream should throw", false)
        } catch (e: FileNotFoundException) {
            // Expected: no source has the term.
        }
    }

    private companion object {
        const val TITLE = "LookupProviderTest"
    }
}
