package space.subread.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.media.MediaPlayer
import com.google.gson.JsonParser
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One place that can have the audio of a term. The bytes are loaded when the user plays it. */
class AudioSource(val name: String, val load: () -> ByteArray?)

/**
 * The android.db of the Local Audio Server for Yomitan. Two tables: `entries` gives the file of
 * a term for each source, `android` holds the bytes of each file.
 */
class LocalAudio(private val file: File, private val sourceOrder: List<String>) {

    fun exists() = file.isFile

    fun sources(expression: String, reading: String): List<AudioSource> {
        if (!file.isFile) return emptyList()
        val db = runCatching { SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY) }.getOrNull() ?: return emptyList()
        val kana = reading.ifEmpty { expression }
        data class Row(val source: String, val speaker: String?, val display: String?, val file: String)
        val rows = db.rawQuery(
            "SELECT source, speaker, display, file FROM entries WHERE expression = ? AND (reading = ? OR reading IS NULL)",
            arrayOf(expression, kana),
        ).use { c ->
            buildList { while (c.moveToNext()) add(Row(c.getString(0), c.getString(1), c.getString(2), c.getString(3))) }
        }
        val ordered = rows.sortedBy { sourceOrder.indexOf(it.source).let { i -> if (i < 0) sourceOrder.size else i } }
        return ordered.map { row ->
            // Forvo puts the speaker in both columns: show it once.
            val name = listOfNotNull(label(row.source), row.speaker, row.display).distinct().joinToString(" ")
            AudioSource(name) {
                db.rawQuery("SELECT data FROM android WHERE file = ? AND source = ?", arrayOf(row.file, row.source)).use { c ->
                    if (c.moveToFirst()) c.getBlob(0) else null
                }
            }
        }
    }

    private fun label(source: String) = when (source) {
        "jpod" -> "JPod101"
        "jpod_alternate" -> "JPod101 Alternate"
        "nhk16" -> "NHK16"
        "shinmeikai8" -> "Shinmeikai 8"
        "forvo" -> "Forvo"
        else -> source
    }
}

/** Audio from a URL. A URL can answer with an audio file, or with the JSON source list of a Local Audio Server. */
object RemoteAudio {

    /** The size of the file that JapanesePod101 sends for a word it does not have. Yomitan skips it too. */
    private const val JPOD_MISSING_SIZE = 52288

    fun sources(templates: List<String>, expression: String, reading: String): List<AudioSource> = templates.map { template ->
        val url = template
            .replace("{term}", URLEncoder.encode(expression, "UTF-8"))
            .replace("{reading}", URLEncoder.encode(reading.ifEmpty { expression }, "UTF-8"))
        AudioSource(URL(url).host) { fetchAudio(url) }
    }

    private fun fetchAudio(url: String): ByteArray? {
        val bytes = fetch(url) ?: return null
        val text = if (bytes.size < 1_000_000) bytes.decodeToString().trimStart() else ""
        if (text.startsWith("{")) {
            // The JSON of a Local Audio Server: the first source that has bytes.
            val list = runCatching { JsonParser.parseString(text).asJsonObject.getAsJsonArray("audioSources") }.getOrNull() ?: return null
            for (source in list) {
                val sourceUrl = source.asJsonObject.get("url")?.asString ?: continue
                val audio = fetch(sourceUrl)
                if (audio != null && audio.isNotEmpty()) return audio
            }
            return null
        }
        if (bytes.isEmpty() || bytes.size == JPOD_MISSING_SIZE) return null
        return bytes
    }

    private fun fetch(url: String): ByteArray? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        try {
            if (connection.responseCode != 200) null else connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

/** Plays one audio file. The bytes go to a file in the cache: MediaPlayer wants a file descriptor. */
class AudioPlayer(private val context: Context) {

    private var player: MediaPlayer? = null

    fun play(bytes: ByteArray) {
        stop()
        val file = File(context.cacheDir, "audio.tmp")
        file.writeBytes(bytes)
        player = MediaPlayer().apply {
            FileInputStream(file).use { setDataSource(it.fd) }
            setOnCompletionListener { stop() }
            prepare()
            start()
        }
    }

    fun stop() {
        player?.release()
        player = null
    }
}
