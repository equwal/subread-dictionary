package space.subread.dictionary.core

/** Kana helpers. A dictionary has readings in hiragana; a text can have the same word in katakana. */
object Kana {

    fun isHiragana(c: Char) = c in 'ぁ'..'ゖ' || c == 'ゝ' || c == 'ゞ'

    fun isKatakana(c: Char) = c in 'ァ'..'ヶ' || c == 'ヽ' || c == 'ヾ'

    fun isKana(c: Char) = isHiragana(c) || isKatakana(c) || c == 'ー'

    fun isJapanese(c: Char) = isKana(c) || c in '一'..'鿿' || c in '㐀'..'䶿' || c == '々' || c == '〆'

    /** Katakana to hiragana. Other characters stay. */
    fun toHiragana(text: String): String = buildString(text.length) {
        for (c in text) append(if (isKatakana(c)) (c.code - 0x60).toChar() else c)
    }
}
