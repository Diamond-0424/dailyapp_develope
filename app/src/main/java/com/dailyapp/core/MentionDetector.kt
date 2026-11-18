package com.dailyapp.core

import java.text.Normalizer

/**
 * 判斷訊息是否點名使用者。不經過 AI，在手機上用規則比對。
 *
 * 比對前先正規化（文字與名稱都做）：
 * - NFKC：上標、全形、花體等相容字元轉回一般字元（ᴰ→D、Ｍ→M、＠→@）。
 *   Discord 顯示名稱常用這類裝飾字母，見 spec.md 2.1.1。
 * - 移除不可見的格式字元（雙向隔離 U+2066–2069、零寬字元等），
 *   Discord 會用它們包住 @ 提及。
 * - 轉小寫。
 *
 * 規則：
 * - 「@名稱」一律視為點名
 * - 英數名稱：需完整單字相符，避免「Mei」誤中「Meiko」
 * - 中日韓等名稱：沒有單字邊界，直接比對子字串
 */
object MentionDetector {
    private val asciiWord = Regex("^[a-z0-9_.\\-]+$")

    fun normalize(s: String): String {
        val nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        var i = 0
        while (i < nfkc.length) {
            val cp = nfkc.codePointAt(i)
            if (Character.getType(cp) != Character.FORMAT.toInt()) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString().lowercase()
    }

    fun isMentioned(text: String, names: Collection<String>): Boolean {
        val t = normalize(text)
        for (raw in names) {
            val n = normalize(raw).trim()
            if (n.isEmpty()) continue
            if (t.contains("@$n")) return true
            if (asciiWord.matches(n)) {
                val re = Regex("(?<![a-z0-9_])${Regex.escape(n)}(?![a-z0-9_])")
                if (re.containsMatchIn(t)) return true
            } else if (t.contains(n)) {
                return true
            }
        }
        return false
    }
}
