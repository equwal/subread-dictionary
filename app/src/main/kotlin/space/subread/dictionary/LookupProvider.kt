package space.subread.dictionary

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import space.subread.dictionary.core.Glossary
import space.subread.dictionary.core.Lookup
import java.io.File
import java.io.FileNotFoundException

/**
 * Answers other apps: the terms at the start of a text, and the audio of a term. SubRead Anki
 * fills its cards from it. The pop-up shows the same terms in the same order.
 *
 * A query of `content://space.subread.dictionary.lookup/lookup?text=食べた` (the extra
 * `offset` is the scan position in the text, 0 by default) returns one row for each term of
 * each enabled dictionary, the longest term first. The columns:
 *
 * - `expression`, `reading`: the term. The reading is the expression when the term has none.
 * - `length`: how many characters of the text the term covers.
 * - `reasons`: the deinflection steps, joined with ` ‹ `, or empty.
 * - `dictionary`: the title of the dictionary of this row.
 * - `glossary`: the entry as simple HTML, the same as the pop-up shows.
 * - `frequency`, `pitch`: as the pop-up shows them, or empty.
 * - `audio`: a Uri of this provider for the audio of the term, or null when no audio source is set up.
 *
 * `openInputStream` on the `audio` Uri gives the bytes of the first source that has the term:
 * the local sources in their order, then the remote ones when they are on. `FileNotFoundException`
 * when none has it. The bytes say the format; `getType` names it from them.
 *
 * The provider is open to each app. It gives what the dictionaries of the user say, nothing
 * about the user.
 */
class LookupProvider : ContentProvider() {

    private val dictionaries by lazy { Dictionaries(context!!) }
    private val store by lazy { Store(context!!) }

    /** The last audio loaded, so that `getType` and `openFile` on one Uri load it once. */
    private var lastAudio: Pair<String, ByteArray>? = null

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(COLUMNS)
        if (uri.lastPathSegment != PATH_LOOKUP) return cursor
        val text = uri.getQueryParameter(PARAM_TEXT).orEmpty().trim()
        val offset = uri.getQueryParameter(PARAM_OFFSET)?.toIntOrNull() ?: 0
        if (text.isEmpty() || !dictionaries.anyEnabled()) return cursor
        val found = Lookup.find(text, offset, dictionaries)
        val metas = found.map { it.stored.term.expression }.distinct().associateWith { dictionaries.meta(it) }
        val hasAudio = store.localAudioFile.isFile || store.remoteEnabled
        for (match in found) {
            val term = match.stored.term
            // The frequency and the pitch of this reading alone, as in the pop-up.
            val meta = metas[term.expression].orEmpty().filter { m ->
                val forReading = when (m.meta.mode) {
                    "freq" -> Glossary.frequencyReading(m.meta.data)
                    "pitch" -> Glossary.pitchReading(m.meta.data)
                    else -> null
                }
                forReading == null || forReading == term.reading || term.reading.isEmpty()
            }
            val frequency = meta.filter { it.meta.mode == "freq" }.joinToString("  ") { "${it.dictionaryTitle}: ${Glossary.frequencyText(it.meta.data)}" }
            val pitch = meta.filter { it.meta.mode == "pitch" }.joinToString("  ") { Glossary.pitchText(it.meta.data) }
            cursor.addRow(
                arrayOf<Any?>(
                    term.expression, term.reading, match.length, match.reasons.joinToString(" ‹ "),
                    match.stored.dictionaryTitle, Glossary.toHtml(term.glossary), frequency, pitch,
                    if (hasAudio) audioUri(term.expression, term.reading).toString() else null,
                ),
            )
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = when (uri.lastPathSegment) {
        PATH_LOOKUP -> "vnd.android.cursor.dir/vnd.space.subread.dictionary.term"
        PATH_AUDIO -> audioBytes(uri)?.let { mimeType(it) }
        else -> null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.lastPathSegment != PATH_AUDIO || mode != "r") throw FileNotFoundException(uri.toString())
        val bytes = audioBytes(uri) ?: throw FileNotFoundException("no audio for ${uri.query}")
        // A file in the cache carries the bytes to the other app. The descriptor keeps it
        // readable after the delete, so no file waits for a cleanup.
        val file = File.createTempFile("audio", ".bin", context!!.cacheDir)
        file.writeBytes(bytes)
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        file.delete()
        return descriptor
    }

    /** The bytes of the first source that has the term of an audio Uri, or null. */
    private fun audioBytes(uri: Uri): ByteArray? {
        val key = uri.toString()
        lastAudio?.let { if (it.first == key) return it.second }
        val expression = uri.getQueryParameter(PARAM_EXPRESSION).orEmpty()
        val reading = uri.getQueryParameter(PARAM_READING).orEmpty()
        if (expression.isEmpty()) return null
        val local = LocalAudio(store.localAudioFile, store.localSources.split(',').map { it.trim() }).sources(expression, reading)
        val remote = if (store.remoteEnabled) {
            RemoteAudio.sources(store.remoteUrls.lines().map { it.trim() }.filter { it.isNotEmpty() }, expression, reading)
        } else {
            emptyList()
        }
        for (source in local + remote) {
            val bytes = runCatching { source.load() }.getOrNull() ?: continue
            if (bytes.isEmpty()) continue
            lastAudio = key to bytes
            return bytes
        }
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    companion object {
        // The debug build has its own authority, so it installs next to the release.
        const val AUTHORITY = BuildConfig.APPLICATION_ID + ".lookup"
        const val PATH_LOOKUP = "lookup"
        const val PATH_AUDIO = "audio"
        const val PARAM_TEXT = "text"
        const val PARAM_OFFSET = "offset"
        const val PARAM_EXPRESSION = "expression"
        const val PARAM_READING = "reading"

        const val COLUMN_EXPRESSION = "expression"
        const val COLUMN_READING = "reading"
        const val COLUMN_LENGTH = "length"
        const val COLUMN_REASONS = "reasons"
        const val COLUMN_DICTIONARY = "dictionary"
        const val COLUMN_GLOSSARY = "glossary"
        const val COLUMN_FREQUENCY = "frequency"
        const val COLUMN_PITCH = "pitch"
        const val COLUMN_AUDIO = "audio"

        val COLUMNS = arrayOf(
            COLUMN_EXPRESSION, COLUMN_READING, COLUMN_LENGTH, COLUMN_REASONS, COLUMN_DICTIONARY,
            COLUMN_GLOSSARY, COLUMN_FREQUENCY, COLUMN_PITCH, COLUMN_AUDIO,
        )

        /** The Uri of the audio of a term. */
        fun audioUri(expression: String, reading: String): Uri = Uri.Builder()
            .scheme("content").authority(AUTHORITY).appendPath(PATH_AUDIO)
            .appendQueryParameter(PARAM_EXPRESSION, expression)
            .appendQueryParameter(PARAM_READING, reading)
            .build()

        /** The Uri of the terms at the start of a text. */
        fun lookupUri(text: String, offset: Int = 0): Uri = Uri.Builder()
            .scheme("content").authority(AUTHORITY).appendPath(PATH_LOOKUP)
            .appendQueryParameter(PARAM_TEXT, text)
            .appendQueryParameter(PARAM_OFFSET, offset.toString())
            .build()

        /** The MIME type of an audio file, from its first bytes. */
        fun mimeType(bytes: ByteArray): String {
            fun at(offset: Int, text: String) =
                bytes.size >= offset + text.length && text.indices.all { bytes[offset + it] == text[it].code.toByte() }
            return when {
                at(0, "ID3") -> "audio/mpeg"
                bytes.size > 2 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0 -> "audio/mpeg"
                at(0, "OggS") -> "audio/ogg"
                at(0, "RIFF") -> "audio/x-wav"
                at(0, "fLaC") -> "audio/flac"
                at(4, "ftyp") -> "audio/mp4"
                else -> "application/octet-stream"
            }
        }
    }
}
