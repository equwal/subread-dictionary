package space.subread.dictionary

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Sends a card to SubRead Anki. The strings are the intent contract of that app, see its
 * docs/intent-api.md. SubRead Anki adds the sentence and the screenshot of the moment, the
 * sentence audio, and puts the card in AnkiDroid.
 */
object Anki {
    const val PACKAGE = "space.subread.anki"
    const val INSTALL = "https://github.com/equwal/subread-anki/releases/latest"
    private const val ACTION_ADD = "space.subread.anki.action.ADD"
    private const val EXTRA_WORD = "space.subread.anki.extra.WORD"
    private const val EXTRA_READING = "space.subread.anki.extra.READING"
    private const val EXTRA_DEFINITION = "space.subread.anki.extra.DEFINITION"
    private const val EXTRA_SENTENCE = "space.subread.anki.extra.SENTENCE"
    private const val EXTRA_AUDIO = "space.subread.anki.extra.AUDIO"
    private const val EXTRA_SOURCE = "space.subread.anki.extra.SOURCE"
    private const val EXTRA_SCREENSHOT = "space.subread.anki.extra.SCREENSHOT"

    fun installed(context: Context): Boolean =
        context.packageManager.resolveActivity(Intent(ACTION_ADD).setPackage(PACKAGE), 0) != null

    /**
     * The intent for one card. The audio file, when there is one, goes through the file provider
     * of this app; the clip data of the intent carries the read permission for it.
     */
    fun intent(context: Context, word: String, reading: String, definition: String, sentence: String, source: String, audio: File?): Intent {
        val intent = Intent(ACTION_ADD)
            .setPackage(PACKAGE)
            .putExtra(EXTRA_WORD, word)
            .putExtra(EXTRA_READING, reading)
            .putExtra(EXTRA_DEFINITION, definition)
            .putExtra(EXTRA_SENTENCE, sentence)
            .putExtra(EXTRA_SOURCE, source)
            .putExtra(EXTRA_SCREENSHOT, true)
        if (audio != null) {
            val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".files", audio)
            intent.putExtra(EXTRA_AUDIO, uri.toString())
            intent.clipData = ClipData.newUri(context.contentResolver, "audio", uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return intent
    }
}
