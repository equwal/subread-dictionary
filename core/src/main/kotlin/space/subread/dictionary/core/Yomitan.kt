package space.subread.dictionary.core

import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/** The `index.json` of a Yomitan dictionary. */
data class DictionaryIndex(
    val title: String,
    val revision: String,
    /** 1, 2 or 3. This app reads format 3 only: the term banks of the other formats have other fields. */
    val format: Int,
    val sequenced: Boolean = false,
)

/** One row of a `term_bank_N.json` file, format 3. */
data class Term(
    val expression: String,
    /** Empty when the reading is the expression. */
    val reading: String,
    /** Space-separated tags of the definition. */
    val definitionTags: String,
    /** Space-separated rule identifiers (`v1`, `v5`, `vs`, `vk`, `adj-i`). The deinflector checks a match against them. */
    val rules: String,
    val score: Double,
    /** The glossary as JSON text: an array of strings and structured content. `Glossary` renders it. */
    val glossary: String,
    val sequence: Long,
    /** Space-separated tags of the term. */
    val termTags: String,
)

/** One row of a `term_meta_bank_N.json` file: frequency, pitch accent or IPA of a term. */
data class TermMeta(
    val expression: String,
    /** `freq`, `pitch` or `ipa`. */
    val mode: String,
    /** The data as JSON text. `Glossary` renders it. */
    val data: String,
)

/** One row of a `tag_bank_N.json` file. */
data class Tag(
    val name: String,
    val category: String,
    val order: Int,
    val notes: String,
    val score: Double,
)

/** Takes the rows of a dictionary as the reader finds them. */
interface DictionarySink {
    fun term(term: Term)
    fun termMeta(meta: TermMeta)
    fun tag(tag: Tag)
}

/**
 * Reads a Yomitan dictionary zip. The JSON files are in the root of the zip. Each file is read as
 * a stream, so a term bank of many megabytes takes little memory.
 */
object YomitanZip {

    /** The index alone. The zip can have the index after the term banks, so the caller opens the zip twice. */
    fun readIndex(zip: InputStream): DictionaryIndex {
        ZipInputStream(zip).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                if (entry.name == "index.json") return parseIndex(stream.readBytes().decodeToString())
            }
        }
        throw DictionaryFormatException("No index.json in the zip.")
    }

    fun parseIndex(json: String): DictionaryIndex {
        val root = JsonParser.parseString(json).asJsonObject
        val format = root.get("format")?.asInt ?: root.get("version")?.asInt
            ?: throw DictionaryFormatException("index.json has no format or version.")
        return DictionaryIndex(
            title = root.get("title")?.asString ?: throw DictionaryFormatException("index.json has no title."),
            revision = root.get("revision")?.asString ?: "",
            format = format,
            sequenced = root.get("sequenced")?.asBoolean ?: false,
        )
    }

    /** Reads the term, term meta and tag banks. Kanji banks are skipped. */
    fun readBanks(zip: InputStream, sink: DictionarySink) {
        ZipInputStream(zip).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                val reader = JsonReader(InputStreamReader(stream, Charsets.UTF_8))
                when {
                    name.startsWith("term_meta_bank_") -> readTermMetaBank(reader, sink)
                    name.startsWith("term_bank_") -> readTermBank(reader, sink)
                    name.startsWith("tag_bank_") -> readTagBank(reader, sink)
                }
            }
        }
    }

    fun readTermBank(reader: JsonReader, sink: DictionarySink) {
        reader.beginArray()
        while (reader.hasNext()) {
            reader.beginArray()
            val term = Term(
                expression = reader.nextString(),
                reading = reader.nextString(),
                definitionTags = reader.nextStringOrNull() ?: "",
                rules = reader.nextString(),
                score = reader.nextDouble(),
                glossary = JsonParser.parseReader(reader).toString(),
                sequence = reader.nextDouble().toLong(),
                termTags = reader.nextString(),
            )
            // A dictionary of another format has more fields, or fewer. Skip what is left of the row.
            while (reader.hasNext()) reader.skipValue()
            reader.endArray()
            sink.term(term)
        }
        reader.endArray()
    }

    fun readTermMetaBank(reader: JsonReader, sink: DictionarySink) {
        reader.beginArray()
        while (reader.hasNext()) {
            reader.beginArray()
            val meta = TermMeta(
                expression = reader.nextString(),
                mode = reader.nextString(),
                data = JsonParser.parseReader(reader).toString(),
            )
            while (reader.hasNext()) reader.skipValue()
            reader.endArray()
            sink.termMeta(meta)
        }
        reader.endArray()
    }

    fun readTagBank(reader: JsonReader, sink: DictionarySink) {
        reader.beginArray()
        while (reader.hasNext()) {
            reader.beginArray()
            val tag = Tag(
                name = reader.nextString(),
                category = reader.nextString(),
                order = reader.nextDouble().toInt(),
                notes = reader.nextString(),
                score = reader.nextDouble(),
            )
            while (reader.hasNext()) reader.skipValue()
            reader.endArray()
            sink.tag(tag)
        }
        reader.endArray()
    }

    private fun JsonReader.nextStringOrNull(): String? =
        if (peek() == JsonToken.NULL) {
            nextNull()
            null
        } else {
            nextString()
        }
}

class DictionaryFormatException(message: String) : Exception(message)
