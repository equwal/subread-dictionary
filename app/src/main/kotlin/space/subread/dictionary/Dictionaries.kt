package space.subread.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import space.subread.dictionary.core.DictionaryIndex
import space.subread.dictionary.core.DictionarySink
import space.subread.dictionary.core.StoredTerm
import space.subread.dictionary.core.Tag
import space.subread.dictionary.core.Term
import space.subread.dictionary.core.TermMeta
import space.subread.dictionary.core.TermSource
import space.subread.dictionary.core.YomitanZip
import java.io.InputStream

/** One imported dictionary. `terms` counts the term rows and the meta rows: a pitch or frequency dictionary has meta rows alone. */
data class DictionaryInfo(val id: Long, val title: String, val revision: String, val position: Int, val enabled: Boolean, val terms: Int)

/** A frequency or pitch entry, with the title of the dictionary it comes from. */
data class StoredMeta(val dictionaryTitle: String, val meta: TermMeta)

/**
 * The imported dictionaries, in one SQLite database. The terms of every dictionary are in one
 * table, so a lookup is one query. The rows keep the fields of the Yomitan format as they are.
 */
class Dictionaries(context: Context, name: String = "dictionaries.db") : SQLiteOpenHelper(context, name, null, 1), TermSource {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE dictionaries (id INTEGER PRIMARY KEY, title TEXT NOT NULL UNIQUE, revision TEXT NOT NULL, position INTEGER NOT NULL, enabled INTEGER NOT NULL DEFAULT 1)")
        db.execSQL(
            "CREATE TABLE terms (id INTEGER PRIMARY KEY, dictionary INTEGER NOT NULL, expression TEXT NOT NULL, reading TEXT NOT NULL, " +
                "definition_tags TEXT NOT NULL, rules TEXT NOT NULL, score REAL NOT NULL, glossary TEXT NOT NULL, sequence INTEGER NOT NULL, term_tags TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX terms_expression ON terms(expression)")
        db.execSQL("CREATE INDEX terms_reading ON terms(reading)")
        db.execSQL("CREATE INDEX terms_dictionary ON terms(dictionary)")
        db.execSQL("CREATE TABLE meta (id INTEGER PRIMARY KEY, dictionary INTEGER NOT NULL, expression TEXT NOT NULL, mode TEXT NOT NULL, data TEXT NOT NULL)")
        db.execSQL("CREATE INDEX meta_expression ON meta(expression)")
        db.execSQL("CREATE INDEX meta_dictionary ON meta(dictionary)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun list(): List<DictionaryInfo> = readableDatabase.rawQuery(
        "SELECT d.id, d.title, d.revision, d.position, d.enabled, " +
            "(SELECT COUNT(*) FROM terms t WHERE t.dictionary = d.id) + (SELECT COUNT(*) FROM meta m WHERE m.dictionary = d.id) " +
            "FROM dictionaries d ORDER BY d.position",
        null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(DictionaryInfo(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4) != 0, c.getInt(5)))
        }
    }

    fun hasTitle(title: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM dictionaries WHERE title = ?", arrayOf(title)).use { it.moveToFirst() }

    /**
     * Imports the banks of a zip, in one transaction: a failure half way leaves nothing. The
     * progress callback gets the number of rows so far, terms and meta, from the import thread.
     */
    fun import(index: DictionaryIndex, zip: InputStream, progress: (Int) -> Unit): DictionaryInfo {
        require(!hasTitle(index.title)) { "exists" }
        return writableDatabase.transaction {
            val db = this
            val position = (db.rawQuery("SELECT MAX(position) FROM dictionaries", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }) + 1
            val id = db.compileStatement("INSERT INTO dictionaries (title, revision, position, enabled) VALUES (?, ?, ?, 1)").run {
                bindString(1, index.title)
                bindString(2, index.revision)
                bindLong(3, position.toLong())
                executeInsert()
            }
            val termInsert = db.compileStatement(
                "INSERT INTO terms (dictionary, expression, reading, definition_tags, rules, score, glossary, sequence, term_tags) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            )
            val metaInsert = db.compileStatement("INSERT INTO meta (dictionary, expression, mode, data) VALUES (?, ?, ?, ?)")
            var count = 0
            YomitanZip.readBanks(zip, object : DictionarySink {
                override fun term(term: Term) {
                    termInsert.bindLong(1, id)
                    termInsert.bindString(2, term.expression)
                    // A term with no reading is read as it is written. The stored reading is the text a kana lookup finds.
                    termInsert.bindString(3, term.reading.ifEmpty { term.expression })
                    termInsert.bindString(4, term.definitionTags)
                    termInsert.bindString(5, term.rules)
                    termInsert.bindDouble(6, term.score)
                    termInsert.bindString(7, term.glossary)
                    termInsert.bindLong(8, term.sequence)
                    termInsert.bindString(9, term.termTags)
                    termInsert.executeInsert()
                    if (++count % 1000 == 0) progress(count)
                }

                override fun termMeta(meta: TermMeta) {
                    metaInsert.bindLong(1, id)
                    metaInsert.bindString(2, meta.expression)
                    metaInsert.bindString(3, meta.mode)
                    metaInsert.bindString(4, meta.data)
                    metaInsert.executeInsert()
                    if (++count % 1000 == 0) progress(count)
                }

                // The tag bank gives the notes of a tag. The pop-up shows the tag names alone.
                override fun tag(tag: Tag) = Unit
            })
            DictionaryInfo(id, index.title, index.revision, position, true, count)
        }
    }

    fun delete(id: Long) {
        writableDatabase.transaction {
            delete("terms", "dictionary = ?", arrayOf(id.toString()))
            delete("meta", "dictionary = ?", arrayOf(id.toString()))
            delete("dictionaries", "id = ?", arrayOf(id.toString()))
        }
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        writableDatabase.execSQL("UPDATE dictionaries SET enabled = ? WHERE id = ?", arrayOf<Any>(if (enabled) 1 else 0, id))
    }

    /** Moves a dictionary one place up in the order. The first one stays. */
    fun moveUp(id: Long) {
        val all = list()
        val i = all.indexOfFirst { it.id == id }
        if (i <= 0) return
        val db = writableDatabase
        db.execSQL("UPDATE dictionaries SET position = ? WHERE id = ?", arrayOf<Any>(all[i - 1].position, id))
        db.execSQL("UPDATE dictionaries SET position = ? WHERE id = ?", arrayOf<Any>(all[i].position, all[i - 1].id))
    }

    override fun terms(texts: Collection<String>): List<StoredTerm> {
        val db = readableDatabase
        val out = ArrayList<StoredTerm>()
        // SQLite takes a limited number of parameters in one statement.
        for (chunk in texts.chunked(400)) {
            val marks = chunk.joinToString(",") { "?" }
            val args = (chunk + chunk).toTypedArray()
            db.rawQuery(
                "SELECT t.expression, t.reading, t.definition_tags, t.rules, t.score, t.glossary, t.sequence, t.term_tags, d.id, d.title, d.position " +
                    "FROM terms t JOIN dictionaries d ON d.id = t.dictionary " +
                    "WHERE d.enabled = 1 AND (t.expression IN ($marks) OR t.reading IN ($marks))",
                args,
            ).use { c ->
                while (c.moveToNext()) {
                    val term = Term(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getDouble(4), c.getString(5), c.getLong(6), c.getString(7))
                    out.add(StoredTerm(c.getLong(8), c.getString(9), c.getInt(10), term))
                }
            }
        }
        return out
    }

    /** The frequency and pitch entries of an expression, from the enabled dictionaries in their order. */
    fun meta(expression: String): List<StoredMeta> = readableDatabase.rawQuery(
        "SELECT m.expression, m.mode, m.data, d.title FROM meta m JOIN dictionaries d ON d.id = m.dictionary " +
            "WHERE d.enabled = 1 AND m.expression = ? ORDER BY d.position",
        arrayOf(expression),
    ).use { c ->
        buildList { while (c.moveToNext()) add(StoredMeta(c.getString(3), TermMeta(c.getString(0), c.getString(1), c.getString(2)))) }
    }

    fun anyEnabled(): Boolean = readableDatabase.rawQuery("SELECT 1 FROM dictionaries WHERE enabled = 1", null).use { it.moveToFirst() }
}
