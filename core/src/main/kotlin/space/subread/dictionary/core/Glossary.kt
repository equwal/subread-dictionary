package space.subread.dictionary.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Turns the glossary of a term, and the meta of a term, into simple HTML: `b`, `i`, `u`, `br`,
 * `ul`, `li`, `a`. A phone shows it with `Html.fromHtml`, which knows these tags and no table.
 */
object Glossary {

    /** The glossary array of a term as HTML. Each definition is one line. */
    fun toHtml(glossaryJson: String): String {
        val array = runCatching { JsonParser.parseString(glossaryJson) }.getOrNull()
        if (array == null || !array.isJsonArray) return escape(glossaryJson)
        val lines = array.asJsonArray.map { definition(it).trim() }.filter { it.isNotEmpty() }
        return lines.joinToString("<br>")
    }

    /** The glossary as plain text, one definition per line. For a search result list, or a test. */
    fun toText(glossaryJson: String): String = toHtml(glossaryJson)
        .replace("<br>", "\n")
        .replace(Regex("<[^>]+>"), "")
        .let(::unescape)

    private fun definition(element: JsonElement): String = when {
        element.isJsonPrimitive -> escape(element.asString)
        element.isJsonArray -> element.asJsonArray.joinToString("") { definition(it) }
        element.isJsonObject -> {
            val obj = element.asJsonObject
            when (obj.get("type")?.asString) {
                "text" -> escape(obj.get("text")?.asString ?: "")
                "image" -> image(obj)
                "structured-content" -> node(obj.get("content"))
                else -> node(element)
            }
        }
        else -> ""
    }

    /** A node of structured content: a string, an array of nodes, or an element with a tag. */
    private fun node(element: JsonElement?): String {
        if (element == null || element.isJsonNull) return ""
        if (element.isJsonPrimitive) return escape(element.asString)
        if (element.isJsonArray) return element.asJsonArray.joinToString("") { node(it) }
        val obj = element.asJsonObject
        val tag = obj.get("tag")?.asString ?: return ""
        val inner = node(obj.get("content"))
        return when (tag) {
            "br" -> "<br>"
            "img" -> image(obj)
            "a" -> {
                val href = obj.get("href")?.asString
                if (href != null && href.startsWith("http")) "<a href=\"${escape(href)}\">$inner</a>" else inner
            }
            "ruby" -> inner
            "rt" -> "<small>($inner)</small>"
            "rp" -> ""
            "ol", "ul" -> "<ul>$inner</ul>"
            "li" -> "<li>${styled(obj, inner)}</li>"
            "table", "thead", "tbody", "tfoot" -> inner
            "tr" -> "$inner<br>"
            "td", "th" -> "$inner&nbsp;| "
            "div", "details", "summary" -> block(styled(obj, inner))
            else -> styled(obj, inner)
        }
    }

    /** A block starts on its own line. Two blocks in a row keep one line break between them. */
    private fun block(inner: String): String =
        if (inner.isEmpty()) "" else if (inner.endsWith("<br>")) inner else "$inner<br>"

    private fun styled(obj: JsonObject, inner: String): String {
        val style = obj.get("style")?.takeIf { it.isJsonObject }?.asJsonObject ?: return inner
        var out = inner
        if (style.get("fontWeight")?.asString?.let { it == "bold" || (it.toIntOrNull() ?: 0) >= 600 } == true) out = "<b>$out</b>"
        if (style.get("fontStyle")?.asString == "italic") out = "<i>$out</i>"
        if (style.get("textDecorationLine")?.let { it.toString().contains("underline") } == true) out = "<u>$out</u>"
        if (style.get("verticalAlign")?.asString == "super") out = "<sup>$out</sup>"
        if (style.get("verticalAlign")?.asString == "sub") out = "<sub>$out</sub>"
        return out
    }

    /** The app has no image store. The alt text, or the file name, stands in for the picture. */
    private fun image(obj: JsonObject): String {
        val alt = obj.get("alt")?.asString ?: obj.get("title")?.asString ?: obj.get("path")?.asString?.substringAfterLast('/') ?: ""
        return "<i>[${escape(alt)}]</i>"
    }

    /** Frequency meta as short text: the display value when there is one, else the rank. */
    fun frequencyText(dataJson: String): String {
        val data = runCatching { JsonParser.parseString(dataJson) }.getOrNull() ?: return ""
        return frequency(data)
    }

    private fun frequency(data: JsonElement): String = when {
        data.isJsonPrimitive -> data.asString
        data.isJsonObject -> {
            val obj = data.asJsonObject
            when {
                obj.has("frequency") -> frequency(obj.get("frequency"))
                obj.has("displayValue") -> obj.get("displayValue").asString
                obj.has("value") -> obj.get("value").asString
                else -> ""
            }
        }
        else -> ""
    }

    /** The reading that a frequency entry is for, or null when it is for every reading. */
    fun frequencyReading(dataJson: String): String? {
        val data = runCatching { JsonParser.parseString(dataJson) }.getOrNull() ?: return null
        return data.takeIf { it.isJsonObject }?.asJsonObject?.get("reading")?.asString
    }

    /** Pitch meta as text: the reading and the accent positions, `はし [2]`. */
    fun pitchText(dataJson: String): String {
        val obj = runCatching { JsonParser.parseString(dataJson) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return ""
        val reading = obj.get("reading")?.asString ?: ""
        val pitches = obj.get("pitches")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        val positions = pitches.mapNotNull { it.asJsonObject.get("position")?.asString }
        return if (positions.isEmpty()) reading else "$reading [${positions.joinToString(", ")}]"
    }

    fun pitchReading(dataJson: String): String? =
        runCatching { JsonParser.parseString(dataJson) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.get("reading")?.asString

    fun escape(text: String): String = buildString(text.length) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }

    private fun unescape(text: String): String =
        text.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
}
