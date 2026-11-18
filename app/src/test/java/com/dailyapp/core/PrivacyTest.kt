package com.dailyapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {
    private fun r(s: String) = Redactor.redact(s)

    @Test fun urlBecomesHostOnly() {
        assertEquals(
            "稿子在 [連結：drive.google.com] 請看",
            r("稿子在 https://drive.google.com/file/d/1AbCdEf/view?usp=sharing 請看"),
        )
        assertEquals("[連結：canva.com]", r("www.canva.com/design/XYZ/edit"))
    }

    @Test fun email() {
        assertEquals("寄到 [Email] 就好", r("寄到 mei.design+club@gmail.com 就好"))
    }

    @Test fun taiwanMobileAndLandline() {
        assertEquals("打 [電話] 找我", r("打 0912-345-678 找我"))
        assertEquals("打 [電話]", r("打 0912345678"))
        assertEquals("[電話]", r("+886 912 345 678"))
        assertEquals("辦公室 [電話]", r("辦公室 02-2345-6789"))
        assertEquals("辦公室 [電話]", r("辦公室 (02)2345-6789"))
    }

    @Test fun nationalId() {
        assertEquals("身分證 [身分證]", r("身分證 A123456789"))
        assertEquals("[身分證]", r("f223456789"))
    }

    @Test fun longDigitsMasked() {
        assertEquals("匯到 [號碼]", r("匯到 1234-5678-9012"))
        assertEquals("帳號 [號碼]", r("帳號 012345678901234"))
    }

    @Test fun amountsAndDatesKept() {
        assertEquals("預算 NT$1200000000", r("預算 NT$1200000000"))
        assertEquals("共 3500000000 元", r("共 3500000000 元"))
        assertEquals("$1,200 / 2026-11-19 / 18:00 / 11/20", r("$1,200 / 2026-11-19 / 18:00 / 11/20"))
        assertEquals("訂 30 份，每份 120", r("訂 30 份，每份 120"))
    }

    @Test fun plainTextUnchanged() {
        assertEquals("小美明天前給主視覺", r("小美明天前給主視覺"))
    }
}

class PseudonymizerTest {
    @Test fun sendersGetStableLetters() {
        val p = Pseudonymizer(selfNames = emptyList())
        assertEquals("成員A", p.alias("王大明"))
        assertEquals("成員B", p.alias("Alice"))
        assertEquals("成員A", p.alias("王大明"))
    }

    @Test fun selfNamesAreKept() {
        val p = Pseudonymizer(selfNames = listOf("小美"))
        assertEquals("小美", p.alias("小美"))
        assertEquals("成員A", p.alias("阿華"))
    }

    @Test fun replacesKnownNamesInsideText() {
        val p = Pseudonymizer(selfNames = listOf("小美"))
        p.alias("王大明"); p.alias("Al")
        assertEquals("@成員A 請跟小美確認", p.apply("@王大明 請跟小美確認"))
        // 英數名稱需完整單字，不誤中 Also、ALbum 以外的單字
        assertEquals("成員B said Also fine", p.apply("Al said Also fine"))
    }

    @Test fun longerNamesReplacedFirst() {
        val p = Pseudonymizer(selfNames = emptyList())
        p.alias("大明"); p.alias("王大明")
        assertEquals("成員B 和 成員A", p.apply("王大明 和 大明"))
    }

    @Test fun restoreHandlesOptionalSpace() {
        val p = Pseudonymizer(selfNames = emptyList())
        p.alias("王大明"); p.alias("阿華")
        assertEquals("王大明交辦，阿華協助", p.restore("成員A交辦，成員 B協助"))
        // 未知代稱原樣保留
        assertEquals("成員Z", p.restore("成員Z"))
    }

    @Test fun manyMembersGetDoubleLetters() {
        val p = Pseudonymizer(selfNames = emptyList())
        repeat(26) { p.alias("人$it") }
        assertEquals("成員AA", p.alias("第27人"))
        assertEquals("第27人", p.restore("成員AA"))
        assertEquals("人0", p.restore("成員A"))
    }
}

class SanitizerTest {
    @Test fun batchRedactsThenPseudonymizes() {
        val out = Sanitizer.sanitize(
            selfNames = listOf("小美"),
            messages = listOf(
                Sanitizer.Input(sender = "王大明", text = "@小美 海報寄到 wang.daming@gmail.com"),
                Sanitizer.Input(sender = "阿華", text = "王大明 電話 0912-345-678"),
            ),
        )
        assertEquals(listOf("成員A", "成員B"), out.messages.map { it.sender })
        assertEquals("@小美 海報寄到 [Email]", out.messages[0].text)
        assertEquals("成員A 電話 [電話]", out.messages[1].text)
        assertEquals("王大明請阿華處理", out.pseudonymizer.restore("成員A請成員B處理"))
        assertFalse(out.messages.any { "王大明" in it.text || "gmail" in it.text })
        assertTrue(out.messages.none { "0912" in it.text })
    }
}
