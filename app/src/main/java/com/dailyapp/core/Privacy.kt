package com.dailyapp.core

/*
 * 送給 AI 之前在手機上做的個資處理（spec 4.3）：
 * - Redactor：遮蔽網址、Email、電話、身分證字號、長串數字（帳號）。
 * - Pseudonymizer：其他發言者的名字換成「成員A」「成員B」，AI 回傳後再換回真名。
 * 使用者本人的名字、金額、日期保留。
 */

object Redactor {
    // 遇到空白、引號、中文標點就結束網址
    private val url = Regex("""(?:https?://|www\.)[^\s<>"'，。、；！？）」』】]+""", RegexOption.IGNORE_CASE)
    private val email = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    /** 身分證字號、居留證號：英文字母 + 1/2/8/9 + 8 位數字。 */
    private val nationalId = Regex("""(?<![A-Za-z0-9])[A-Za-z][1289]\d{8}(?!\d)""")
    private val mobile = Regex("""(?<![\d+])(?:\+886[-\s]?|0)9\d{2}[-\s]?\d{3}[-\s]?\d{3}(?!\d)""")
    private val landline = Regex("""(?<!\d)(?:\(0\d{1,2}\)|0\d{1,2})[-\s]?\d{3,4}[-\s]?\d{4}(?!\d)""")
    /** 10 位以上數字（中間可夾 - 或空白），視為帳號。 */
    private val longDigits = Regex("""(?<!\d)\d(?:[-\s]?\d){9,}(?!\d)""")

    fun redact(text: String): String {
        var s = url.replace(text) { "[連結：${hostOf(it.value)}]" }
        s = email.replace(s, "[Email]")
        s = nationalId.replace(s, "[身分證]")
        s = mobile.replace(s, "[電話]")
        s = landline.replace(s, "[電話]")
        s = longDigits.replace(s) { m -> if (isAmount(s, m.range)) m.value else "[號碼]" }
        return s
    }

    private fun hostOf(u: String): String =
        u.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            .substringBefore(':').removePrefix("www.").removePrefix("WWW.").lowercase()

    /** 前面有 $、NT，或後面接 元／塊／円，就是金額，不遮。 */
    private fun isAmount(s: String, r: IntRange): Boolean {
        val before = s.substring(maxOf(0, r.first - 4), r.first)
        if ('$' in before || '＄' in before || before.contains("NT", ignoreCase = true)) return true
        val after = s.substring(r.last + 1).trimStart()
        return after.startsWith("元") || after.startsWith("塊") || after.startsWith("円")
    }
}

/**
 * 發言者代稱。同一批次內同一人固定同一代稱；本人名稱不替換。
 * 代稱格式「成員A」…「成員Z」「成員AA」…，還原時容許 AI 在中間加空白。
 */
class Pseudonymizer(selfNames: Collection<String>) {
    private val self = selfNames.map { MentionDetector.normalize(it).trim() }.filter { it.isNotEmpty() }.toSet()
    private val toAlias = LinkedHashMap<String, String>()   // 真名 → 代稱
    private val toReal = HashMap<String, String>()           // 代稱字母 → 真名
    private var pattern: Regex? = null

    fun isSelf(name: String) = MentionDetector.normalize(name).trim() in self

    fun alias(name: String): String {
        if (isSelf(name)) return name
        toAlias[name]?.let { return it }
        val letters = letters(toAlias.size)
        val a = "成員$letters"
        toAlias[name] = a
        toReal[letters] = name
        pattern = null
        return a
    }

    /** 把文字中出現的已知發言者名字換成代稱（一次掃描，不會重複替換）。 */
    fun apply(text: String): String {
        if (toAlias.isEmpty()) return text
        val re = pattern ?: buildPattern().also { pattern = it }
        val byLower = toAlias.entries.associate { it.key.lowercase() to it.value }
        return re.replace(text) { byLower[it.value.lowercase()] ?: it.value }
    }

    fun restore(text: String): String =
        restorePattern.replace(text) { m -> toReal[m.groupValues[1]] ?: m.value }

    private fun buildPattern(): Regex {
        val alts = toAlias.keys.filter { it.isNotBlank() }.sortedByDescending { it.length }.map { n ->
            val e = Regex.escape(n)
            if (asciiWord.matches(n)) "(?<![A-Za-z0-9_])$e(?![A-Za-z0-9_])" else e
        }
        return Regex(alts.joinToString("|"), RegexOption.IGNORE_CASE)
    }

    companion object {
        private val asciiWord = Regex("^[A-Za-z0-9_.\\-]+$")
        private val restorePattern = Regex("""成員\s?([A-Z]{1,2})(?![A-Za-z])""")

        /** 0→A、25→Z、26→AA。 */
        fun letters(n: Int): String {
            var i = n
            val sb = StringBuilder()
            do {
                sb.insert(0, 'A' + i % 26)
                i = i / 26 - 1
            } while (i >= 0)
            return sb.toString()
        }
    }
}

/** 一批訊息的完整前處理：先遮蔽（避免名字被 Email 等吃掉一半），再替換名字。 */
object Sanitizer {
    data class Input(val sender: String, val text: String)
    data class Output(val messages: List<Input>, val pseudonymizer: Pseudonymizer)

    fun sanitize(selfNames: Collection<String>, messages: List<Input>): Output {
        val p = Pseudonymizer(selfNames)
        messages.forEach { p.alias(it.sender) }
        val out = messages.map { Input(p.alias(it.sender), p.apply(Redactor.redact(it.text))) }
        return Output(out, p)
    }
}
