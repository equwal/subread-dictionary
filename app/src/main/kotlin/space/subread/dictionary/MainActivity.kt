package space.subread.dictionary

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import space.subread.dictionary.core.DictionaryFormatException
import space.subread.dictionary.core.YomitanZip
import java.io.File
import kotlin.concurrent.thread

/**
 * The settings, and the one screen that opens from the launcher: the dictionaries, the local
 * audio, the remote audio, the text size, and a field to try a lookup.
 */
class MainActivity : Activity() {

    private lateinit var store: Store
    private lateinit var dictionaries: Dictionaries
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        dictionaries = Dictionaries(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.WHITE)
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(Color.WHITE)
            addView(content)
        })
    }

    override fun onResume() {
        super.onResume()
        draw()
    }

    override fun onDestroy() {
        dictionaries.close()
        super.onDestroy()
    }

    private fun draw() {
        content.removeAllViews()
        title(getString(R.string.app_name))
        note(getString(R.string.about))
        note(getString(R.string.privacy))

        // 1. Dictionaries
        step(R.string.step_dictionaries, getString(R.string.step_dictionaries_why), getString(R.string.import_dictionary)) {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                PICK_DICTIONARY,
            )
        }
        val list = dictionaries.list()
        if (list.isEmpty()) note(getString(R.string.dictionaries_none))
        for (dictionary in list) {
            note(
                resources.getQuantityString(R.plurals.dictionary_terms, dictionary.terms, dictionary.title, dictionary.terms),
                color = if (dictionary.enabled) Color.BLACK else Color.GRAY,
            )
            row(
                button(getString(if (dictionary.enabled) R.string.disable else R.string.enable)) {
                    dictionaries.setEnabled(dictionary.id, !dictionary.enabled)
                    draw()
                },
                button(getString(R.string.up)) {
                    dictionaries.moveUp(dictionary.id)
                    draw()
                },
                button(getString(R.string.delete)) {
                    AlertDialog.Builder(this).setMessage(getString(R.string.delete_ask, dictionary.title))
                        .setPositiveButton(R.string.delete) { _, _ -> thread { dictionaries.delete(dictionary.id); runOnUiThread { draw() } } }
                        .setNegativeButton(android.R.string.cancel, null).show()
                },
            )
        }

        // 2. Local audio
        val audio = store.localAudioFile
        step(R.string.step_local_audio, getString(R.string.step_local_audio_why), getString(R.string.local_audio_choose)) {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                PICK_AUDIO,
            )
        }
        if (audio.isFile) {
            note(getString(R.string.local_audio_have, size(audio.length())))
            content.addView(button(getString(R.string.local_audio_remove)) { audio.delete(); draw() }, LinearLayout.LayoutParams(-2, -2))
        } else {
            note(getString(R.string.local_audio_none))
        }
        note(getString(R.string.local_audio_folder, audio.parent ?: ""), color = Color.DKGRAY, size = 13f)
        note(getString(R.string.local_audio_sources), top = 12)
        val sources = field(store.localSources, single = true)
        content.addView(button(getString(R.string.save)) {
            store.localSources = sources.text.toString()
            toast(getString(R.string.saved))
        }, LinearLayout.LayoutParams(-2, -2))

        // 3. Remote audio
        val remote = store.remoteEnabled
        step(
            R.string.step_remote_audio,
            getString(R.string.step_remote_audio_why) + "\n\n" + getString(if (remote) R.string.remote_audio_on else R.string.remote_audio_off),
            getString(if (remote) R.string.turn_off else R.string.turn_on),
        ) {
            store.remoteEnabled = !remote
            draw()
        }
        val urls = field(store.remoteUrls, single = false)
        content.addView(button(getString(R.string.save)) {
            store.remoteUrls = urls.text.toString()
            toast(getString(R.string.saved))
        }, LinearLayout.LayoutParams(-2, -2))

        // 4. Text size
        step(R.string.step_size, "${store.textSizeSp.toInt()} sp", null) {}
        row(button("−") { store.textSizeSp -= 1f; draw() }, button("+") { store.textSizeSp += 1f; draw() })

        // 5. Try it
        step(R.string.step_try, "", null) {}
        val tryField = field("", single = true).apply { hint = getString(R.string.try_hint) }
        content.addView(button(getString(R.string.look_up)) {
            startActivity(Intent(this, LookupActivity::class.java).setAction(LOOKUP).putExtra(Intent.EXTRA_TEXT, tryField.text.toString()))
        }, LinearLayout.LayoutParams(-2, -2))

        val overlay = packageManager.getLaunchIntentForPackage(OVERLAY_PACKAGE)
        if (overlay != null) content.addView(button(getString(R.string.open_overlay)) { runCatching { startActivity(overlay) } }, wide(top = 24))
        if (BuildConfig.DONATE_LINK) content.addView(button(getString(R.string.donate)) { open(KOFI) }, wide())
    }

    @Deprecated("The platform Activity has no other result API, and this app has no AndroidX activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            PICK_DICTIONARY -> importDictionary(uri)
            PICK_AUDIO -> copyAudio(uri)
        }
    }

    /** Reads the index, then the banks, on a thread. A dialog shows the count. */
    private fun importDictionary(uri: Uri) {
        val dialog = AlertDialog.Builder(this).setMessage(displayName(uri)).setCancelable(false).show()
        thread {
            val result = runCatching {
                val index = contentResolver.openInputStream(uri)!!.use { YomitanZip.readIndex(it) }
                if (index.format != 3) throw DictionaryFormatException(getString(R.string.import_format, index.format))
                if (dictionaries.hasTitle(index.title)) throw DictionaryFormatException(getString(R.string.import_exists))
                contentResolver.openInputStream(uri)!!.use { zip ->
                    dictionaries.import(index, zip) { count -> runOnUiThread { dialog.setMessage(getString(R.string.importing, index.title, count)) } }
                }
            }
            runOnUiThread {
                dialog.dismiss()
                result.onFailure { toast(getString(R.string.import_failed, it.message ?: it.javaClass.simpleName)) }
                draw()
            }
        }
    }

    /** Copies the android.db into the app folder. It can be gigabytes, so the dialog shows the size so far. */
    private fun copyAudio(uri: Uri) {
        val target = store.localAudioFile
        val dialog = AlertDialog.Builder(this).setMessage(getString(R.string.local_audio_copying, "0 MB")).setCancelable(false).show()
        thread {
            val result = runCatching {
                val partial = File(target.path + ".part")
                contentResolver.openInputStream(uri)!!.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 20)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            total += n
                            if (total % (32L shl 20) < (1 shl 20)) runOnUiThread { dialog.setMessage(getString(R.string.local_audio_copying, size(total))) }
                        }
                    }
                }
                check(partial.renameTo(target)) { "rename" }
            }
            runOnUiThread {
                dialog.dismiss()
                result.onFailure { toast(getString(R.string.import_failed, it.message ?: it.javaClass.simpleName)) }
                draw()
            }
        }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()

    private fun size(bytes: Long) = "%.0f MB".format(bytes / 1048576.0)

    private fun open(link: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, link.toUri())) }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    // The screen is built in code: a few rows do not need a layout file each.

    private fun title(value: String) = content.addView(TextView(this).apply {
        text = value
        textSize = 26f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.BLACK)
    })

    private fun note(value: String, top: Int = 8, color: Int = Color.BLACK, size: Float = 15f) = content.addView(TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }, wide(top))

    private fun field(value: String, single: Boolean) = EditText(this).apply {
        setText(value)
        setTextColor(Color.BLACK)
        textSize = 14f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or if (single) 0 else InputType.TYPE_TEXT_FLAG_MULTI_LINE
        isSingleLine = single
    }.also { content.addView(it, wide()) }

    @SuppressLint("SetTextI18n")
    private fun step(name: Int, detail: String, action: String?, onAction: () -> Unit) {
        content.addView(View(this).apply { setBackgroundColor(Color.BLACK) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(16) })
        content.addView(TextView(this).apply {
            text = getString(name)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
        }, wide(top = 12))
        if (detail.isNotEmpty()) note(detail, top = 4)
        if (action != null) content.addView(button(action, onAction), LinearLayout.LayoutParams(-2, -2))
    }

    private fun row(vararg views: View) = content.addView(LinearLayout(this).apply { views.forEach { addView(it) } })

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun wide(top: Int = 8) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PICK_DICTIONARY = 1
        const val PICK_AUDIO = 2
        const val LOOKUP = "space.subread.dictionary.LOOKUP"
        const val OVERLAY_PACKAGE = "space.subread.overlay"
        const val KOFI = "https://ko-fi.com/truex"
    }
}
