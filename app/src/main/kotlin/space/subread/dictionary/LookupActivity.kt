package space.subread.dictionary

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import space.subread.dictionary.core.Glossary
import space.subread.dictionary.core.Lookup
import space.subread.dictionary.core.Match
import kotlin.concurrent.thread

/**
 * The pop-up. It takes a text from the text selection menu, from the share sheet, or from an
 * intent, and shows the terms at the scan position: the longest first. A tap on a character of
 * the text moves the scan position there.
 */
class LookupActivity : Activity() {

    private lateinit var store: Store
    private lateinit var dictionaries: Dictionaries
    private lateinit var player: AudioPlayer
    private lateinit var textView: TextView
    private lateinit var results: LinearLayout
    private var text = ""
    private var offset = 0
    private var generation = 0

    // The touch listener finds the character under the finger; it calls performClick itself.
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        dictionaries = Dictionaries(this)
        player = AudioPlayer(this)

        // A panel at the bottom, over the app that sent the text. The height is fixed, so the
        // panel does not grow with the results: they scroll inside it.
        val height = (resources.displayMetrics.heightPixels * 0.6).toInt()
        window.attributes = window.attributes.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            this.height = height
            gravity = Gravity.BOTTOM
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        textView = TextView(this).apply {
            textSize = store.textSizeSp + 3
            setTextColor(Color.BLACK)
            maxLines = 4
            setPadding(dp(16), dp(12), dp(16), dp(8))
            setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    val at = (view as TextView).getOffsetForPosition(event.x, event.y)
                    if (at in text.indices) scan(at)
                    view.performClick()
                }
                true
            }
        }
        results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(16))
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.END
            addView(smallButton(getString(R.string.settings)) { startActivity(Intent(this@LookupActivity, MainActivity::class.java)) })
            addView(smallButton(getString(R.string.close)) { finish() })
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                addView(bar, LinearLayout.LayoutParams(-1, -2))
                addView(textView, LinearLayout.LayoutParams(-1, -2))
                addView(View(this@LookupActivity).apply { setBackgroundColor(Color.LTGRAY) }, LinearLayout.LayoutParams(-1, dp(1)))
                addView(ScrollView(this@LookupActivity).apply { addView(results) }, LinearLayout.LayoutParams(-1, 0, 1f))
            },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height),
        )
        take(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        take(intent)
    }

    override fun onDestroy() {
        player.stop()
        dictionaries.close()
        super.onDestroy()
    }

    /** The text of the intent, from whichever of the three ways in. */
    private fun take(intent: Intent) {
        text = (intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT) ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT) ?: "")
            .toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.no_text, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        scan(0)
    }

    private fun scan(at: Int) {
        offset = at
        val shown = SpannableString(text)
        textView.text = shown
        val run = ++generation
        thread {
            val found = if (dictionaries.anyEnabled()) Lookup.find(text, at, dictionaries) else null
            val metas = found.orEmpty().map { it.stored.term.expression }.distinct().associateWith { dictionaries.meta(it) }
            runOnUiThread { if (run == generation) show(found, metas) }
        }
    }

    private fun show(found: List<Match>?, metas: Map<String, List<StoredMeta>>) {
        results.removeAllViews()
        if (found == null) {
            note(getString(R.string.no_dictionaries))
            return
        }
        if (found.isEmpty()) {
            note(getString(R.string.no_results))
            return
        }
        // The longest match is marked in the text.
        val shown = SpannableString(text)
        shown.setSpan(BackgroundColorSpan(0xFFFFE082.toInt()), offset, offset + found.first().length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        textView.text = shown

        // One card per expression and reading, with the entries of each dictionary inside it.
        val groups = found.groupBy { it.stored.term.expression to it.stored.term.reading }
        for ((key, matches) in groups) {
            val (expression, reading) = key
            results.addView(View(this).apply { setBackgroundColor(Color.LTGRAY) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(10) })
            results.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@LookupActivity).apply {
                    text = if (reading.isEmpty() || reading == expression) expression else "$expression【$reading】"
                    textSize = store.textSizeSp + 5
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.BLACK)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(smallButton("▶ " + getString(R.string.audio_play)) { play(expression, reading, choose = false) }.apply {
                    setOnLongClickListener { play(expression, reading, choose = true); true }
                })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

            val reasons = matches.first().reasons
            val meta = metas[expression].orEmpty().filter { m ->
                val forReading = when (m.meta.mode) { "freq" -> Glossary.frequencyReading(m.meta.data); "pitch" -> Glossary.pitchReading(m.meta.data); else -> null }
                forReading == null || forReading == reading || reading.isEmpty()
            }
            val line = buildList {
                if (reasons.isNotEmpty()) add(reasons.joinToString(" ‹ "))
                for (m in meta) when (m.meta.mode) {
                    "freq" -> add("${m.dictionaryTitle}: ${Glossary.frequencyText(m.meta.data)}")
                    "pitch" -> add(Glossary.pitchText(m.meta.data))
                }
            }
            if (line.isNotEmpty()) note(line.joinToString("   "), color = Color.DKGRAY, size = store.textSizeSp - 3)

            for (match in matches) {
                val term = match.stored.term
                val tags = (term.definitionTags.split(' ') + term.termTags.split(' ')).filter { it.isNotEmpty() }.distinct()
                note(
                    listOf(match.stored.dictionaryTitle).plus(tags).joinToString("  "),
                    color = Color.GRAY,
                    size = store.textSizeSp - 4,
                    top = 6,
                )
                results.addView(TextView(this).apply {
                    text = Html.fromHtml(Glossary.toHtml(term.glossary), Html.FROM_HTML_MODE_COMPACT)
                    textSize = store.textSizeSp
                    setTextColor(Color.BLACK)
                    movementMethod = LinkMovementMethod.getInstance()
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            }
        }
    }

    /** Plays the first source that has audio: the local ones in their order, then the remote ones. */
    private fun play(expression: String, reading: String, choose: Boolean) {
        thread {
            val sources = LocalAudio(store.localAudioFile, store.localSources.split(',').map { it.trim() }).sources(expression, reading) +
                if (store.remoteEnabled) RemoteAudio.sources(store.remoteUrls.lines().map { it.trim() }.filter { it.isNotEmpty() }, expression, reading) else emptyList()
            if (choose) {
                runOnUiThread {
                    if (sources.isEmpty()) toast(getString(R.string.no_audio)) else {
                        AlertDialog.Builder(this).setItems(sources.map { it.name }.toTypedArray()) { _, i -> thread { playFirst(listOf(sources[i])) } }.show()
                    }
                }
            } else {
                playFirst(sources)
            }
        }
    }

    private fun playFirst(sources: List<AudioSource>) {
        for (source in sources) {
            val bytes = runCatching { source.load() }.getOrNull() ?: continue
            runOnUiThread {
                runCatching { player.play(bytes) }.onFailure { toast(getString(R.string.audio_failed, it.message ?: "")) }
            }
            return
        }
        runOnUiThread { toast(getString(R.string.no_audio)) }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun note(value: String, color: Int = Color.BLACK, size: Float = store.textSizeSp, top: Int = 8) = results.addView(
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) },
    )

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
