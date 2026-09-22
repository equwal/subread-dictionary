package space.subread.dictionary

import android.content.Context
import androidx.core.content.edit
import java.io.File

/** What the user chose. It stays when the app stops. */
class Store(private val context: Context) {

    private val prefs = context.getSharedPreferences("dictionary", Context.MODE_PRIVATE)

    /** The sources of the local audio, in the order to try them. The names are the folder names of the Local Audio Server. */
    var localSources: String
        get() = prefs.getString("local_sources", DEFAULT_LOCAL_SOURCES) ?: DEFAULT_LOCAL_SOURCES
        set(value) = prefs.edit { putString("local_sources", value) }

    /** The remote audio URLs, one per line, with {term} and {reading}. */
    var remoteUrls: String
        get() = prefs.getString("remote_urls", DEFAULT_REMOTE_URLS) ?: DEFAULT_REMOTE_URLS
        set(value) = prefs.edit { putString("remote_urls", value) }

    /** Off until the user turns it on: a remote source sends the word to a server. */
    var remoteEnabled: Boolean
        get() = prefs.getBoolean("remote_enabled", false)
        set(value) = prefs.edit { putBoolean("remote_enabled", value) }

    var textSizeSp: Float
        get() = prefs.getFloat("text_size", 17f)
        set(value) = prefs.edit { putFloat("text_size", value.coerceIn(11f, 40f)) }

    /**
     * The android.db of the local audio. In the app folder on the external storage: `adb push` and
     * a file manager with a USB cable can reach it, and the app needs no permission for it.
     */
    val localAudioFile: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "android.db")

    companion object {
        const val DEFAULT_LOCAL_SOURCES = "jpod, jpod_alternate, nhk16, shinmeikai8, forvo"
        const val DEFAULT_REMOTE_URLS =
            "https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?kanji={term}&kana={reading}"
    }
}
