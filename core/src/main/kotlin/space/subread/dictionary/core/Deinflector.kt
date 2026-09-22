package space.subread.dictionary.core

/**
 * A dictionary form that an inflected text can come from.
 *
 * @param text the dictionary form.
 * @param conditions bit mask of the word classes that the last rule produced, from [Conditions].
 *   Zero: the text is not deinflected, any term matches.
 * @param reasons the names of the rules, from the text to the dictionary form.
 */
data class Deinflection(val text: String, val conditions: Int, val reasons: List<String>) {

    /** True when a term with these rule identifiers (`v1`, `v5`, `vs`, `vk`, `vz`, `adj-i`) can be this form. */
    fun accepts(termRules: String): Boolean {
        if (conditions == 0) return true
        return Conditions.maskOf(termRules) and conditions != 0
    }
}

/** The word classes of the Japanese rules of Yomitan. Each leaf is one bit; a group is the bits of its leaves. */
object Conditions {
    private val leaves = listOf(
        "v1d", "v1p", "v5d", "v5s", "v5ss", "v5sp", "vk", "vs", "vz", "adj-i",
        "-ます", "-ません", "-て", "-ば", "-く", "-た", "-ん", "-なさい", "-ゃ",
    )
    private val bits: Map<String, Int> = buildMap {
        leaves.forEachIndexed { i, name -> put(name, 1 shl i) }
        put("v1", getValue("v1d") or getValue("v1p"))
        put("v5", getValue("v5d") or getValue("v5s") or getValue("v5ss") or getValue("v5sp"))
        put("v", getValue("v1") or getValue("v5") or getValue("vk") or getValue("vs") or getValue("vz"))
    }

    fun maskOf(names: Collection<String>): Int = names.fold(0) { acc, name -> acc or (bits[name] ?: 0) }

    /** The mask of a space-separated rule string of a term. */
    fun maskOf(rules: String): Int = maskOf(rules.split(' ').filter { it.isNotEmpty() })
}

/**
 * Japanese deinflection, after the transforms of Yomitan. A rule takes a text that ends with
 * `inflected`, in a word class of `conditionsIn`, and gives the text that ends with `base`, in a
 * word class of `conditionsOut`. The rules chain: 食べさせられた → 食べさせられる → 食べさせる → 食べる.
 */
object Deinflector {

    private class Rule(val reason: String, val inflected: String, val base: String, val conditionsIn: Int, val conditionsOut: Int)

    private val rules = ArrayList<Rule>()

    private fun rule(reason: String, inflected: String, base: String, conditionsIn: List<String>, conditionsOut: List<String>) {
        rules.add(Rule(reason, inflected, base, Conditions.maskOf(conditionsIn), Conditions.maskOf(conditionsOut)))
    }

    /** The godan stems: the inflected ending, then the dictionary ending. */
    private val godan = listOf("う" to "う", "く" to "く", "ぐ" to "ぐ", "す" to "す", "つ" to "つ", "ぬ" to "ぬ", "ぶ" to "ぶ", "む" to "む", "る" to "る")

    /** Rules for a suffix that follows the i-stem (連用形): ます, たい, なさい, そう, すぎる, たり. */
    private fun stemSuffix(reason: String, suffix: String, conditionsIn: List<String>, v5Out: String = "v5") {
        val stems = listOf("い" to "う", "き" to "く", "ぎ" to "ぐ", "し" to "す", "ち" to "つ", "に" to "ぬ", "び" to "ぶ", "み" to "む", "り" to "る")
        rule(reason, suffix, "る", conditionsIn, listOf("v1"))
        for ((stem, end) in stems) rule(reason, stem + suffix, end, conditionsIn, listOf(v5Out))
        rule(reason, "じ$suffix", "ずる", conditionsIn, listOf("vz"))
        rule(reason, "し$suffix", "する", conditionsIn, listOf("vs"))
        rule(reason, "為$suffix", "為る", conditionsIn, listOf("vs"))
        rule(reason, "き$suffix", "くる", conditionsIn, listOf("vk"))
        rule(reason, "来$suffix", "来る", conditionsIn, listOf("vk"))
        rule(reason, "來$suffix", "來る", conditionsIn, listOf("vk"))
    }

    /** Rules for a suffix that follows the a-stem (未然形): ない, ず, ぬ, ん, せる, れる. */
    private fun negativeStemSuffix(reason: String, suffix: String, conditionsIn: List<String>, v1Suffix: String = suffix) {
        val stems = listOf("わ" to "う", "は" to "う", "か" to "く", "が" to "ぐ", "さ" to "す", "た" to "つ", "な" to "ぬ", "ば" to "ぶ", "ま" to "む", "ら" to "る")
        rule(reason, v1Suffix, "る", conditionsIn, listOf("v1"))
        for ((stem, end) in stems) rule(reason, stem + suffix, end, conditionsIn, listOf("v5"))
        rule(reason, "じ$suffix", "ずる", conditionsIn, listOf("vz"))
        rule(reason, "し$suffix", "する", conditionsIn, listOf("vs"))
        rule(reason, "為$suffix", "為る", conditionsIn, listOf("vs"))
        rule(reason, "こ$suffix", "くる", conditionsIn, listOf("vk"))
        rule(reason, "来$suffix", "来る", conditionsIn, listOf("vk"))
        rule(reason, "來$suffix", "來る", conditionsIn, listOf("vk"))
    }

    /** Rules for a suffix that follows the te/ta sound change (音便): て, た, たら, たり, ちゃう. */
    private fun teSuffix(reason: String, te: String, conditionsIn: List<String>, vkOut: String = "vk") {
        // The voiced form after ん and after the い of ぐ: 読んで, 泳いだ, 死んじゃう.
        val de = when (te.first()) { 'て' -> "で"; 'た' -> "だ"; 'ち' -> "じ"; else -> te.take(1) } + te.drop(1)
        rule(reason, te, "る", conditionsIn, listOf("v1"))
        rule(reason, "い$te", "く", conditionsIn, listOf("v5"))
        rule(reason, "い$de", "ぐ", conditionsIn, listOf("v5"))
        rule(reason, "し$te", "す", conditionsIn, listOf("v5"))
        rule(reason, "っ$te", "う", conditionsIn, listOf("v5"))
        rule(reason, "っ$te", "つ", conditionsIn, listOf("v5"))
        rule(reason, "っ$te", "る", conditionsIn, listOf("v5"))
        // 行く, 往く, 逝く and いく sound like う-verbs in this form: 行った, not 行いた.
        for (iku in listOf("行", "往", "逝", "い")) rule(reason, "${iku}っ$te", "${iku}く", conditionsIn, listOf("v5"))
        rule(reason, "ん$de", "ぬ", conditionsIn, listOf("v5"))
        rule(reason, "ん$de", "ぶ", conditionsIn, listOf("v5"))
        rule(reason, "ん$de", "む", conditionsIn, listOf("v5"))
        rule(reason, "じ$te", "ずる", conditionsIn, listOf("vz"))
        rule(reason, "し$te", "する", conditionsIn, listOf("vs"))
        rule(reason, "為$te", "為る", conditionsIn, listOf("vs"))
        rule(reason, "き$te", "くる", conditionsIn, listOf(vkOut))
        rule(reason, "来$te", "来る", conditionsIn, listOf(vkOut))
        rule(reason, "來$te", "來る", conditionsIn, listOf(vkOut))
    }

    init {
        // -ば
        rule("-ば", "ければ", "い", listOf("-ば"), listOf("adj-i"))
        for ((_, end) in godan) {
            val e = when (end) { "う" -> "え"; "く" -> "け"; "ぐ" -> "げ"; "す" -> "せ"; "つ" -> "て"; "ぬ" -> "ね"; "ぶ" -> "べ"; "む" -> "め"; else -> "れ" }
            rule("-ば", "${e}ば", end, listOf("-ば"), listOf("v5"))
        }
        rule("-ば", "へば", "う", listOf("-ば"), listOf("v5"))
        rule("-ば", "れば", "る", listOf("-ば"), listOf("v1", "v5", "vk", "vs", "vz"))
        rule("-ば", "れば", "", listOf("-ば"), listOf("-ます"))
        // -ちゃう, -しまう
        teSuffix("-ちゃう", "ちゃう", listOf("v5"))
        rule("-しまう", "てしまう", "て", listOf("v5"), listOf("-て"))
        rule("-しまう", "でしまう", "で", listOf("v5"), listOf("-て"))
        // -なさい, -そう, -すぎる, -たい
        stemSuffix("-なさい", "なさい", listOf("-なさい"))
        stemSuffix("-そう", "そう", listOf())
        rule("-そう", "そう", "い", listOf(), listOf("adj-i"))
        stemSuffix("-すぎる", "すぎる", listOf("v1"))
        rule("-すぎる", "すぎる", "い", listOf("v1"), listOf("adj-i"))
        stemSuffix("-たい", "たい", listOf("adj-i"))
        // -たら, -たり
        rule("-たら", "かったら", "い", listOf(), listOf("adj-i"))
        teSuffix("-たら", "たら", listOf())
        rule("-たら", "ましたら", "ます", listOf(), listOf("-ます"))
        rule("-たり", "かったり", "い", listOf(), listOf("adj-i"))
        teSuffix("-たり", "たり", listOf())
        rule("-たり", "ましたり", "ます", listOf(), listOf("-ます"))
        // -ず, -ぬ, -ん (negative)
        negativeStemSuffix("-ず", "ず", listOf())
        negativeStemSuffix("-ぬ", "ぬ", listOf())
        negativeStemSuffix("-ん", "ん", listOf("-ん"))
        rule("-ん", "ません", "ます", listOf("-ん"), listOf("-ます"))
        // adverb, -さ
        rule("adv", "く", "い", listOf("-く"), listOf("adj-i"))
        rule("-さ", "さ", "い", listOf(), listOf("adj-i"))
        // causative
        negativeStemSuffix("causative", "せる", listOf("v1"), v1Suffix = "させる")
        rule("causative", "させる", "する", listOf("v1"), listOf("vs"))
        rule("causative", "為せる", "為る", listOf("v1"), listOf("vs"))
        rule("causative", "こさせる", "くる", listOf("v1"), listOf("vk"))
        rule("causative", "来させる", "来る", listOf("v1"), listOf("vk"))
        rule("causative", "來させる", "來る", listOf("v1"), listOf("vk"))
        // imperative
        rule("imperative", "ろ", "る", listOf(), listOf("v1"))
        rule("imperative", "よ", "る", listOf(), listOf("v1"))
        for ((_, end) in godan) {
            val e = when (end) { "う" -> "え"; "く" -> "け"; "ぐ" -> "げ"; "す" -> "せ"; "つ" -> "て"; "ぬ" -> "ね"; "ぶ" -> "べ"; "む" -> "め"; else -> "れ" }
            rule("imperative", e, end, listOf(), listOf("v5"))
        }
        rule("imperative", "へ", "う", listOf(), listOf("v5"))
        rule("imperative", "じろ", "ずる", listOf(), listOf("vz"))
        rule("imperative", "ぜよ", "ずる", listOf(), listOf("vz"))
        rule("imperative", "しろ", "する", listOf(), listOf("vs"))
        rule("imperative", "せよ", "する", listOf(), listOf("vs"))
        rule("imperative", "為ろ", "為る", listOf(), listOf("vs"))
        rule("imperative", "為よ", "為る", listOf(), listOf("vs"))
        rule("imperative", "こい", "くる", listOf(), listOf("vk"))
        rule("imperative", "来い", "来る", listOf(), listOf("vk"))
        rule("imperative", "來い", "來る", listOf(), listOf("vk"))
        // continuative (連用形) as a word on its own
        for (stem in listOf("い", "え", "き", "ぎ", "け", "げ", "じ", "せ", "ぜ", "ち", "て", "で", "に", "ね", "ひ", "び", "へ", "べ", "み", "め", "り", "れ")) {
            rule("continuative", stem, "${stem}る", listOf(), listOf("v1d"))
        }
        for ((stem, end) in listOf("い" to "う", "ひ" to "う", "き" to "く", "ぎ" to "ぐ", "し" to "す", "ち" to "つ", "に" to "ぬ", "び" to "ぶ", "み" to "む", "り" to "る")) {
            rule("continuative", stem, end, listOf(), listOf("v5"))
        }
        rule("continuative", "き", "くる", listOf(), listOf("vk"))
        rule("continuative", "し", "する", listOf(), listOf("vs"))
        rule("continuative", "来", "来る", listOf(), listOf("vk"))
        rule("continuative", "來", "來る", listOf(), listOf("vk"))
        // negative
        rule("negative", "くない", "い", listOf("adj-i"), listOf("adj-i"))
        negativeStemSuffix("negative", "ない", listOf("adj-i"))
        rule("negative", "ません", "ます", listOf("-ません"), listOf("-ます"))
        // passive
        negativeStemSuffix("passive", "れる", listOf("v1"), v1Suffix = "られる")
        rule("passive", "される", "する", listOf("v1"), listOf("vs"))
        rule("passive", "為れる", "為る", listOf("v1"), listOf("vs"))
        rule("passive", "こられる", "くる", listOf("v1"), listOf("vk"))
        rule("passive", "来られる", "来る", listOf("v1"), listOf("vk"))
        rule("passive", "來られる", "來る", listOf("v1"), listOf("vk"))
        // past
        rule("past", "かった", "い", listOf("-た"), listOf("adj-i"))
        teSuffix("past", "た", listOf("-た"))
        rule("past", "ました", "ます", listOf("-た"), listOf("-ます"))
        rule("past", "でした", "", listOf("-た"), listOf("-ません"))
        rule("past", "かった", "", listOf("-た"), listOf("-ません", "-ん"))
        // polite
        stemSuffix("polite", "ます", listOf("-ます"), v5Out = "v5d")
        rule("polite", "ひます", "う", listOf("-ます"), listOf("v5d"))
        rule("polite", "くあります", "い", listOf("-ます"), listOf("adj-i"))
        // potential
        rule("potential", "れる", "る", listOf("v1"), listOf("v1", "v5d"))
        for ((_, end) in godan) {
            if (end == "る") continue
            val e = when (end) { "う" -> "え"; "く" -> "け"; "ぐ" -> "げ"; "す" -> "せ"; "つ" -> "て"; "ぬ" -> "ね"; "ぶ" -> "べ"; else -> "め" }
            rule("potential", "${e}る", end, listOf("v1"), listOf("v5d"))
        }
        rule("potential", "できる", "する", listOf("v1"), listOf("vs"))
        rule("potential", "出来る", "する", listOf("v1"), listOf("vs"))
        rule("potential", "これる", "くる", listOf("v1"), listOf("vk"))
        rule("potential", "来れる", "来る", listOf("v1"), listOf("vk"))
        rule("potential", "來れる", "來る", listOf("v1"), listOf("vk"))
        // potential or passive
        rule("potential or passive", "られる", "る", listOf("v1"), listOf("v1"))
        // volitional
        rule("volitional", "よう", "る", listOf(), listOf("v1"))
        rule("volitional", "やう", "る", listOf(), listOf("v1"))
        for ((_, end) in godan) {
            val o = when (end) { "う" -> "お"; "く" -> "こ"; "ぐ" -> "ご"; "す" -> "そ"; "つ" -> "と"; "ぬ" -> "の"; "ぶ" -> "ぼ"; "む" -> "も"; else -> "ろ" }
            val a = when (end) { "う" -> "は"; "く" -> "か"; "ぐ" -> "が"; "す" -> "さ"; "つ" -> "た"; "ぬ" -> "な"; "ぶ" -> "ば"; "む" -> "ま"; else -> "ら" }
            rule("volitional", "${o}う", end, listOf(), listOf("v5"))
            rule("volitional", "${a}う", end, listOf(), listOf("v5"))
        }
        rule("volitional", "じよう", "ずる", listOf(), listOf("vz"))
        rule("volitional", "しよう", "する", listOf(), listOf("vs"))
        rule("volitional", "為よう", "為る", listOf(), listOf("vs"))
        rule("volitional", "こよう", "くる", listOf(), listOf("vk"))
        rule("volitional", "来よう", "来る", listOf(), listOf("vk"))
        rule("volitional", "來よう", "來る", listOf(), listOf("vk"))
        rule("volitional", "ましょう", "ます", listOf(), listOf("-ます"))
        rule("volitional", "かろう", "い", listOf(), listOf("adj-i"))
        // -て
        rule("-て", "くて", "い", listOf("-て"), listOf("adj-i"))
        teSuffix("-て", "て", listOf("-て"))
        rule("-て", "まして", "ます", listOf(), listOf("-ます"))
        // -ている, -ておく, -ていく, -てくる
        rule("-ている", "ている", "て", listOf("v1"), listOf("-て"))
        rule("-ている", "てゐる", "て", listOf("v1"), listOf("-て"))
        rule("-ている", "ておる", "て", listOf("v5"), listOf("-て"))
        rule("-ている", "てる", "て", listOf("v1p"), listOf("-て"))
        rule("-ている", "でいる", "で", listOf("v1"), listOf("-て"))
        rule("-ている", "でゐる", "で", listOf("v1"), listOf("-て"))
        rule("-ている", "でおる", "で", listOf("v5"), listOf("-て"))
        rule("-ている", "でる", "で", listOf("v1p"), listOf("-て"))
        rule("-ている", "とる", "て", listOf("v5"), listOf("-て"))
        rule("-ている", "ないでいる", "ない", listOf("v1"), listOf("adj-i"))
        rule("-ておく", "ておく", "て", listOf("v5"), listOf("-て"))
        rule("-ておく", "でおく", "で", listOf("v5"), listOf("-て"))
        rule("-ておく", "とく", "て", listOf("v5"), listOf("-て"))
        rule("-ておく", "どく", "で", listOf("v5"), listOf("-て"))
        rule("-ておく", "ないでおく", "ない", listOf("v5"), listOf("adj-i"))
        rule("-ていく", "ていく", "て", listOf("v5"), listOf("-て"))
        rule("-ていく", "でいく", "で", listOf("v5"), listOf("-て"))
        rule("-てくる", "てくる", "て", listOf("vk"), listOf("-て"))
        rule("-てくる", "でくる", "で", listOf("vk"), listOf("-て"))
    }

    /** The maximum number of forms tried for one text. A text of normal length is far below it. */
    private const val LIMIT = 2000

    /**
     * Each dictionary form that the text can come from. The first result is the text itself, with
     * no conditions. The others come in breadth-first order: the fewest rules first.
     */
    fun deinflect(text: String): List<Deinflection> {
        if (text.isEmpty()) return emptyList()
        val results = ArrayList<Deinflection>()
        val seen = HashSet<Pair<String, Int>>()
        results.add(Deinflection(text, 0, emptyList()))
        seen.add(text to 0)
        var i = 0
        while (i < results.size && results.size < LIMIT) {
            val current = results[i++]
            for (rule in rules) {
                if (rule.conditionsIn != 0 && current.conditions != 0 && rule.conditionsIn and current.conditions == 0) continue
                if (!current.text.endsWith(rule.inflected)) continue
                val base = current.text.dropLast(rule.inflected.length) + rule.base
                if (base.isEmpty()) continue
                if (!seen.add(base to rule.conditionsOut)) continue
                results.add(Deinflection(base, rule.conditionsOut, current.reasons + rule.reason))
            }
        }
        return results
    }
}
