package space.subread.dictionary

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import space.subread.dictionary.core.AudioType
import space.subread.dictionary.core.Glossary
import space.subread.dictionary.core.Lookup
import java.io.File
import java.io.FileNotFoundException

/**
 * Gives the terms of a text, and the audio of a term, to another app. SubRead Anki uses it for
 * the definition, the reading and the word audio of a card. See docs/provider-api.md.
 *
 * `terms?text=…&offset=0`: one row per term at the offset, the longest first, with the columns
 * `expression`, `reading`, `length`, `reasons`, `dictionary`, `definition_html`, `score`.
 *
 * `audio?expression=…&reading=…`: one row with the columns `file` and `mime` when a source has
 * the audio, in the order of the settings; no row when none has. The bytes are then at
 * `audio/<file>`, to open with openInputStream.
 */
class LookupProvider : ContentProvider() {

    private val dictionaries by lazy { Dictionaries(context!!) }
    private val store by lazy { Store(context!!) }

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? =
        when (uri.pathSegments.firstOrNull()) {
            "terms" -> terms(uri.getQueryParameter("text").orEmpty(), uri.getQueryParameter("offset")?.toIntOrNull() ?: 0)
            "audio" -> audio(uri.getQueryParameter("expression").orEmpty(), uri.getQueryParameter("reading").orEmpty())
            else -> null
        }

    private fun terms(text: String, offset: Int): Cursor {
        val cursor = MatrixCursor(arrayOf("expression", "reading", "length", "reasons", "dictionary", "definition_html", "score"))
        if (text.isEmpty() || !dictionaries.anyEnabled()) return cursor
        for (match in Lookup.find(text, offset, dictionaries)) {
            val term = match.stored.term
            cursor.addRow(
                arrayOf<Any>(term.expression, term.reading, match.length, match.reasons.joinToString(" "), match.stored.dictionaryTitle, Glossary.toHtml(term.glossary), term.score),
            )
        }
        return cursor
    }

    /** The first source that has the audio writes it to the cache; the row names the file. */
    private fun audio(expression: String, reading: String): Cursor {
        val cursor = MatrixCursor(arrayOf("file", "mime"))
        if (expression.isEmpty()) return cursor
        val local = LocalAudio(store.localAudioFile, store.localSources.split(',').map { it.trim() })
        val sources = runCatching { local.sources(expression, reading) }.getOrDefault(emptyList()) +
            if (store.remoteEnabled) RemoteAudio.sources(store.remoteUrls.lines().map { it.trim() }.filter { it.isNotEmpty() }, expression, reading) else emptyList()
        for (source in sources) {
            val bytes = runCatching { source.load() }.getOrNull() ?: continue
            if (bytes.isEmpty()) continue
            val extension = AudioType.extension(bytes)
            val name = "${(expression + "\u0000" + reading).hashCode().toUInt()}.$extension"
            File(dir(), name).writeBytes(bytes)
            cursor.addRow(arrayOf(name, AudioType.mime(extension)))
            break
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != "audio") throw FileNotFoundException(uri.toString())
        val file = File(dir(), File(segments[1]).name)
        if (!file.isFile) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? {
        val segments = uri.pathSegments
        return when {
            segments.firstOrNull() == "terms" -> "vnd.android.cursor.dir/vnd.space.subread.dictionary.term"
            segments.firstOrNull() == "audio" && segments.size == 1 -> "vnd.android.cursor.item/vnd.space.subread.dictionary.audio"
            segments.firstOrNull() == "audio" && segments.size == 2 -> AudioType.mime(segments[1].substringAfterLast('.', ""))
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException("read only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException("read only")

    /** The audio files on their way out. Files older than an hour go. */
    private fun dir(): File {
        val dir = File(context!!.cacheDir, "audio-out").apply { mkdirs() }
        val limit = System.currentTimeMillis() - 60 * 60_000L
        dir.listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
        return dir
    }
}
