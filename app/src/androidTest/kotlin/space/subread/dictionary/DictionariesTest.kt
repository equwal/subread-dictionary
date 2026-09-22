package space.subread.dictionary

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import space.subread.dictionary.core.DictionaryIndex
import space.subread.dictionary.core.Lookup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The import into SQLite and the lookup through it, on a device. */
@RunWith(AndroidJUnit4::class)
class DictionariesTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var dictionaries: Dictionaries

    @Before
    fun open() {
        context.deleteDatabase("test.db")
        dictionaries = Dictionaries(context, "test.db")
    }

    @After
    fun close() {
        dictionaries.close()
        context.deleteDatabase("test.db")
    }

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

    private val termBank = """[
        ["食べる","たべる","v1","v1",10,["to eat"],1,"P"],
        ["食べ物","たべもの","n","",5,["food"],2,""],
        ["物","もの","n","",1,["thing"],3,""]
    ]"""

    private val metaBank = """[["食べる","freq",{"value":100,"displayValue":"100"}],["食べる","pitch",{"reading":"たべる","pitches":[{"position":2}]}]]"""

    @Test
    fun importThenLookup() {
        val index = DictionaryIndex("Test", "1", 3)
        val counts = ArrayList<Int>()
        val info = dictionaries.import(index, ByteArrayInputStream(zip("term_bank_1.json" to termBank, "term_meta_bank_1.json" to metaBank))) { counts.add(it) }
        // Three terms and two meta rows.
        assertEquals(5, info.terms)
        assertEquals(listOf(info), dictionaries.list())

        val found = Lookup.find("食べ物を食べた", 4, dictionaries)
        assertEquals("食べる", found.first().stored.term.expression)
        assertEquals(listOf("past"), found.first().reasons)
        // A kana text finds the reading.
        assertEquals("食べ物", Lookup.find("たべもの", 0, dictionaries).first().stored.term.expression)

        val meta = dictionaries.meta("食べる")
        assertEquals(listOf("freq", "pitch"), meta.map { it.meta.mode })
    }

    @Test
    fun aDisabledDictionaryIsNotSearched() {
        val info = dictionaries.import(DictionaryIndex("Test", "1", 3), ByteArrayInputStream(zip("term_bank_1.json" to termBank))) {}
        dictionaries.setEnabled(info.id, false)
        assertTrue(Lookup.find("食べる", 0, dictionaries).isEmpty())
        assertTrue(!dictionaries.anyEnabled())
    }

    @Test
    fun deleteRemovesTheTermsToo() {
        val info = dictionaries.import(DictionaryIndex("Test", "1", 3), ByteArrayInputStream(zip("term_bank_1.json" to termBank, "term_meta_bank_1.json" to metaBank))) {}
        dictionaries.delete(info.id)
        assertTrue(dictionaries.list().isEmpty())
        assertTrue(dictionaries.terms(listOf("食べる")).isEmpty())
        assertTrue(dictionaries.meta("食べる").isEmpty())
    }

    @Test
    fun orderFollowsMoveUp() {
        val a = dictionaries.import(DictionaryIndex("A", "1", 3), ByteArrayInputStream(zip("term_bank_1.json" to "[]"))) {}
        val b = dictionaries.import(DictionaryIndex("B", "1", 3), ByteArrayInputStream(zip("term_bank_1.json" to "[]"))) {}
        assertEquals(listOf(a.id, b.id), dictionaries.list().map { it.id })
        dictionaries.moveUp(b.id)
        assertEquals(listOf(b.id, a.id), dictionaries.list().map { it.id })
    }
}
