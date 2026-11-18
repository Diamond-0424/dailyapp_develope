package com.dailyapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionDetectorTest {
    @Test fun atMentionMatchesCaseInsensitive() {
        assertTrue(MentionDetector.isMentioned("@mei_design 海報幫我改", listOf("Mei_Design")))
    }

    @Test fun chineseNameMatchesAsSubstring() {
        assertTrue(MentionDetector.isMentioned("小美明天前給主視覺", listOf("小美")))
    }

    @Test fun asciiNameRequiresWordBoundary() {
        assertFalse(MentionDetector.isMentioned("Meiko 已經處理了", listOf("Mei")))
        assertTrue(MentionDetector.isMentioned("Mei, can you check?", listOf("Mei")))
    }

    @Test fun blankNamesIgnored() {
        assertFalse(MentionDetector.isMentioned("大家好", listOf("", "  ")))
    }

    @Test fun noNamesNoMention() {
        assertFalse(MentionDetector.isMentioned("@someone", emptyList()))
    }

    // M1 實測：Discord 顯示名稱使用上標大寫字母，且 @ 提及被雙向隔離字元包住
    @Test fun discordStylizedMentionMatchesPlainName() {
        // U+2068 + @ + ᴹᴼᴼᴺ (MOON 的上標大寫) + U+2069
        val text = "⁨@ᴹᴼᴼᴺ⁩ 幫我看一下"
        assertTrue(MentionDetector.isMentioned(text, listOf("Moon")))
    }

    @Test fun stylizedNameInSettingsAlsoWorks() {
        assertTrue(MentionDetector.isMentioned("@moon 收到嗎", listOf("ᴹᴼᴼᴺ")))
    }

    @Test fun fullwidthAtAndLettersNormalized() {
        assertTrue(MentionDetector.isMentioned("＠Ｍｅｉ 明天交", listOf("Mei")))
    }

    @Test fun zeroWidthInsideNameIgnored() {
        assertTrue(MentionDetector.isMentioned("小​美 這個給你", listOf("小美")))
    }

    @Test fun stylizedDoesNotBreakWordBoundary() {
        // ᴹᴱᴵᴷᴼ → meiko，不應誤中 Mei
        assertFalse(MentionDetector.isMentioned("ᴹᴱᴵᴷᴼ 處理了", listOf("Mei")))
    }
}

class ModelTest {
    private val m = ParsedMessage("A", "k1", "hi", 1000L)

    @Test fun hashStableForSameMessage() {
        assertEquals(messageHash("discord:g", m), messageHash("discord:g", m.copy()))
    }

    @Test fun hashDiffersByGroupTimeOrText() {
        val h = messageHash("discord:g", m)
        assertNotEquals(h, messageHash("discord:h", m))
        assertNotEquals(h, messageHash("discord:g", m.copy(time = 1001L)))
        assertNotEquals(h, messageHash("discord:g", m.copy(text = "hi!")))
    }

    @Test fun attachmentDetectedByMime() {
        assertTrue(m.copy(mimeType = "image/webp").hasAttachment)
        assertFalse(m.hasAttachment)
        assertFalse(m.copy(mimeType = "text/plain").hasAttachment)
    }

    @Test fun platformFromPackage() {
        assertEquals(Platform.TELEGRAM, Platform.fromPackage("org.telegram.messenger.web"))
        assertEquals(null, Platform.fromPackage("com.instagram.android"))
    }

    @Test fun parseNamesSplitsAndTrims() {
        assertEquals(listOf("小美", "Mei", "美宣"), parseNames(" 小美, Mei，美宣、小美 "))
    }
}
