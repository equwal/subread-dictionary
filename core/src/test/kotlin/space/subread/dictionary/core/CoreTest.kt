package space.subread.dictionary.core

import com.google.gson.JsonArray
import com.google.gson.JsonPrimitive
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DeinflectorTest {

    private fun forms(text: String) = Deinflector.deinflect(text).map { it.text }.toSet()

    private fun accepted(text: String, base: String, rules: String) =
        Deinflector.deinflect(text).any { it.text == base && it.accepts(rules) }

    @Test
    fun theTextItselfComesFirst() {
        val first = Deinflector.deinflect("食べた").first()
        assertEquals("食べた", first.text)
        assertEquals(0, first.conditions)
        assertTrue(first.accepts(""))
        assertTrue(first.accepts("v5"))
    }

    @Test
    fun commonVerbForms() {
        assertTrue(accepted("食べた", "食べる", "v1"))
        assertTrue(accepted("食べなかった", "食べる", "v1"))
        assertTrue(accepted("食べさせられた", "食べる", "v1"))
        assertTrue(accepted("食べています", "食べる", "v1"))
        assertTrue(accepted("食べてる", "食べる", "v1"))
        assertTrue(accepted("書いて", "書く", "v5"))
        assertTrue(accepted("行った", "行く", "v5"))
        assertTrue(accepted("泳いだ", "泳ぐ", "v5"))
        assertTrue(accepted("読んで", "読む", "v5"))
        assertTrue(accepted("飲まれた", "飲む", "v5"))
        assertTrue(accepted("話せば", "話す", "v5"))
        assertTrue(accepted("待ちます", "待つ", "v5"))
        assertTrue(accepted("死んじゃった", "死ぬ", "v5"))
        assertTrue(accepted("来なかった", "来る", "vk"))
        assertTrue(accepted("しました", "する", "vs"))
        assertTrue(accepted("できる", "する", "vs"))
        assertTrue(accepted("勉強しよう", "勉強する", "vs"))
        assertTrue(accepted("知らず", "知る", "v5"))
        assertTrue(accepted("わからん", "わかる", "v5"))
        assertTrue(accepted("見とく", "見る", "v1"))
    }

    @Test
    fun adjectiveForms() {
        assertTrue(accepted("高くなかった", "高い", "adj-i"))
        assertTrue(accepted("高くて", "高い", "adj-i"))
        assertTrue(accepted("高ければ", "高い", "adj-i"))
        assertTrue(accepted("高そう", "高い", "adj-i"))
        assertTrue(accepted("高さ", "高い", "adj-i"))
        assertTrue(accepted("早く", "早い", "adj-i"))
    }

    @Test
    fun theWordClassIsChecked() {
        // 食べた is not the past of a godan verb that ends in た.
        assertTrue(!accepted("食べた", "食べる", "v5"))
        // A noun has no rules, so it matches the text itself only.
        assertTrue(!accepted("本た", "本", ""))
        assertTrue(accepted("本", "本", ""))
    }

    @Test
    fun eachRuleChainGivesAShorterOrEqualOrTheSameKindOfText(): Unit = runBlocking {
        // The deinflector ends for every text and never returns an empty form.
        checkAll(Arb.stringPattern("[あ-んア-ン一-十]{0,12}")) { text ->
            val all = Deinflector.deinflect(text)
            assertTrue(all.size < 2000)
            assertTrue(all.all { it.text.isNotEmpty() })
            assertEquals(all.size, all.map { it.text to it.conditions }.toSet().size)
        }
    }

    @Test
    fun noneOfTheRuleChainsLoop() {
        for (text in listOf("いる", "い", "きる", "し", "する", "くる", "た", "て", "ない", "ます", "れる", "られる", "せる")) {
            forms(text)
        }
    }
}

class KanaTest {
    @Test
    fun katakanaBecomesHiragana() {
        assertEquals("ぱん", Kana.toHiragana("パン"))
        assertEquals("コーヒー".let { "こーひー" }, Kana.toHiragana("コーヒー"))
        assertEquals("漢字abc", Kana.toHiragana("漢字abc"))
    }
}

class GlossaryTest {
    @Test
    fun plainStringsAreLines() {
        assertEquals("to eat<br>to live on", Glossary.toHtml("""["to eat","to live on"]"""))
        assertEquals("to eat\nto live on", Glossary.toText("""["to eat","to live on"]"""))
    }

    @Test
    fun structuredContentKeepsTheTextAndSimpleTags() {
        val json = """[{"type":"structured-content","content":[
            {"tag":"div","content":[{"tag":"span","style":{"fontWeight":"bold"},"content":"1."}," to eat"]},
            {"tag":"ul","content":[{"tag":"li","content":"first"},{"tag":"li","content":"second"}]},
            {"tag":"ruby","content":["食",{"tag":"rt","content":"た"}]},
            {"tag":"img","path":"img/x.png","alt":"picture"},
            {"tag":"a","href":"https://example.com","content":"link"},
            {"tag":"table","content":[{"tag":"tr","content":[{"tag":"td","content":"a"},{"tag":"td","content":"b"}]}]}
        ]}]"""
        val html = Glossary.toHtml(json)
        assertTrue(html, html.contains("<b>1.</b> to eat<br>"))
        assertTrue(html, html.contains("<ul><li>first</li><li>second</li></ul>"))
        assertTrue(html, html.contains("食<small>(た)</small>"))
        assertTrue(html, html.contains("<i>[picture]</i>"))
        assertTrue(html, html.contains("<a href=\"https://example.com\">link</a>"))
        assertTrue(html, html.contains("a&nbsp;| b&nbsp;| <br>"))
    }

    @Test
    fun textIsEscaped() {
        assertEquals("a &lt;b&gt; &amp; c", Glossary.toHtml("""["a <b> & c"]"""))
        assertEquals("a <b> & c", Glossary.toText("""["a <b> & c"]"""))
    }

    @Test
    fun meta() {
        assertEquals("12", Glossary.frequencyText("12"))
        assertEquals("12㋕", Glossary.frequencyText("""{"value":12,"displayValue":"12㋕"}"""))
        assertEquals("3", Glossary.frequencyText("""{"reading":"はし","frequency":{"value":3}}"""))
        assertEquals("はし", Glossary.frequencyReading("""{"reading":"はし","frequency":3}"""))
        assertEquals("はし [2, 0]", Glossary.pitchText("""{"reading":"はし","pitches":[{"position":2},{"position":0}]}"""))
    }
}

class YomitanZipTest {

    private val arbTerm = Arb.list(
        Arb.stringPattern("[ぁ-ん一-十a-z]{1,6}"),
        1..40,
    ).map { words ->
        words.mapIndexed { i, word ->
            Term(
                expression = word,
                reading = if (i % 2 == 0) "" else "よみ$i",
                definitionTags = if (i % 3 == 0) "" else "n vs",
                rules = if (i % 4 == 0) "v1" else "",
                score = (i * 7 % 11).toDouble(),
                glossary = JsonArray().apply { add(JsonPrimitive("gloss $i \"quoted\" <b>")); add(JsonPrimitive("second")) }.toString(),
                sequence = i.toLong() * 100,
                termTags = "P",
            )
        }
    }

    private fun zip(index: String, terms: List<Term>, metas: List<TermMeta>, tags: List<Tag>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            fun file(name: String, body: String) {
                out.putNextEntry(ZipEntry(name))
                out.write(body.toByteArray())
                out.closeEntry()
            }
            file("term_bank_1.json", JsonArray().apply {
                for (t in terms) add(JsonArray().apply {
                    add(t.expression); add(t.reading)
                    if (t.definitionTags.isEmpty()) add(com.google.gson.JsonNull.INSTANCE) else add(t.definitionTags)
                    add(t.rules); add(t.score); add(com.google.gson.JsonParser.parseString(t.glossary)); add(t.sequence); add(t.termTags)
                })
            }.toString())
            file("term_meta_bank_1.json", JsonArray().apply {
                for (m in metas) add(JsonArray().apply { add(m.expression); add(m.mode); add(com.google.gson.JsonParser.parseString(m.data)) })
            }.toString())
            file("tag_bank_1.json", JsonArray().apply {
                for (t in tags) add(JsonArray().apply { add(t.name); add(t.category); add(t.order); add(t.notes); add(t.score) })
            }.toString())
            file("kanji_bank_1.json", "[[\"日\",\"\",\"\",\"\",[],{}]]")
            // The index last: a reader that stops at the first file would miss it.
            file("index.json", index)
        }
        return bytes.toByteArray()
    }

    private class ListSink : DictionarySink {
        val terms = ArrayList<Term>()
        val metas = ArrayList<TermMeta>()
        val tags = ArrayList<Tag>()
        override fun term(term: Term) { terms.add(term) }
        override fun termMeta(meta: TermMeta) { metas.add(meta) }
        override fun tag(tag: Tag) { tags.add(tag) }
    }

    @Test
    fun whatIsWrittenIsReadBack(): Unit = runBlocking {
        checkAll(arbTerm) { terms ->
            val metas = terms.take(3).map { TermMeta(it.expression, "freq", """{"value":5,"displayValue":"5"}""") }
            val tags = listOf(Tag("n", "partOfSpeech", 0, "noun", 0.0))
            val bytes = zip("""{"title":"Test","revision":"1","format":3}""", terms, metas, tags)
            val index = YomitanZip.readIndex(ByteArrayInputStream(bytes))
            assertEquals(DictionaryIndex("Test", "1", 3), index)
            val sink = ListSink()
            YomitanZip.readBanks(ByteArrayInputStream(bytes), sink)
            assertEquals(terms, sink.terms)
            assertEquals(metas, sink.metas)
            assertEquals(tags, sink.tags)
        }
    }

    @Test
    fun versionIsAnAliasOfFormat() {
        assertEquals(3, YomitanZip.parseIndex("""{"title":"T","revision":"r","version":3}""").format)
    }
}

class LookupTest {

    private val dictionary = listOf(
        term("食べる", "たべる", "v1", 10.0),
        term("食べ物", "たべもの", "", 5.0),
        term("物", "もの", "", 1.0),
        term("本", "ほん", "", 1.0),
        term("パン", "ぱん", "", 1.0),
        term("高い", "たかい", "adj-i", 3.0),
        term("た", "", "", 0.0),
    )

    private fun term(expression: String, reading: String, rules: String, score: Double) =
        StoredTerm(1, "Test", 0, Term(expression, reading, "", rules, score, "[\"x\"]", 0, ""))

    private val source = object : TermSource {
        override fun terms(texts: Collection<String>) =
            dictionary.filter { it.term.expression in texts || it.term.reading in texts }
    }

    @Test
    fun theLongestTermComesFirst() {
        val found = Lookup.find("食べ物を食べた", 0, source).map { it.stored.term.expression to it.length }
        assertEquals(listOf("食べ物" to 3, "食べる" to 2), found)
    }

    @Test
    fun anInflectedVerbIsFound() {
        val found = Lookup.find("食べ物を食べた", 4, source)
        assertEquals("食べる", found.first().stored.term.expression)
        assertEquals(3, found.first().length)
        assertEquals(listOf("past"), found.first().reasons)
    }

    @Test
    fun kanaMatchesTheReadingAndKatakanaMatchesToo() {
        assertEquals("食べる", Lookup.find("たべます", 0, source).first().stored.term.expression)
        assertEquals("パン", Lookup.find("ぱんを", 0, source).first().stored.term.expression)
        assertEquals("パン", Lookup.find("パンを", 0, source).first().stored.term.expression)
    }

    @Test
    fun aTermIsShownOnceWithItsLongestMatch() {
        // 食べる is the text itself (3 characters) and the continuative 食べ + る (2 characters).
        val found = Lookup.find("食べる", 0, source).filter { it.stored.term.expression == "食べる" }
        assertEquals(listOf(3), found.map { it.length })
        assertEquals(emptyList<String>(), found.first().reasons)
    }

    @Test
    fun aNounWithNoRulesIsNotDeinflected() {
        // 本た: the noun 本 is found for one character, not for two.
        val found = Lookup.find("本た", 0, source)
        assertEquals(listOf("本" to 1), found.map { it.stored.term.expression to it.length })
    }

    @Test
    fun outOfRangeGivesNothing() {
        assertTrue(Lookup.find("abc", 3, source).isEmpty())
        assertTrue(Lookup.find("abc", -1, source).isEmpty())
    }
}
