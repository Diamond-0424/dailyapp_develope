package com.dailyapp.core

import java.security.MessageDigest

/** 支援的平台。core 套件不依賴 Android，可以直接做單元測試。 */
enum class Platform(val id: String, val label: String, val packages: Set<String>) {
    LINE("line", "LINE", setOf("jp.naver.line.android")),
    DISCORD("discord", "Discord", setOf("com.discord")),
    TELEGRAM(
        "telegram", "Telegram",
        setOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram"),
    );

    val mainPackage: String get() = packages.first()

    companion object {
        fun fromPackage(pkg: String): Platform? = entries.firstOrNull { pkg in it.packages }
        fun fromId(id: String): Platform? = entries.firstOrNull { it.id == id }
    }
}

/** 從通知解析出的一則訊息。sender 為 null 代表是使用者本人送出的。 */
data class ParsedMessage(
    val sender: String?,
    val senderKey: String?,
    val text: String,
    val time: Long,
    val mimeType: String? = null,
) {
    val isSelf: Boolean get() = sender == null
    val hasAttachment: Boolean get() = mimeType != null && !mimeType.startsWith("text/")
}

enum class ParseMode { MESSAGING_STYLE, FALLBACK }

/** 一則通知所代表的對話，以及其中的訊息。 */
data class ParsedConversation(
    val platform: Platform,
    /** 對話的穩定識別：優先使用 shortcutId，否則用對話名稱。 */
    val conversationKey: String,
    val title: String,
    val isGroup: Boolean,
    val selfName: String?,
    val messages: List<ParsedMessage>,
    val mode: ParseMode,
) {
    val groupId: String get() = groupIdOf(platform, conversationKey)
}

fun groupIdOf(platform: Platform, conversationKey: String) = "${platform.id}:$conversationKey"

/** 去除重複用的雜湊：同一對話、同一發言者、同一時間、同一內容視為同一則訊息。 */
fun messageHash(groupId: String, m: ParsedMessage): String {
    val raw = listOf(groupId, m.senderKey ?: m.sender ?: "<self>", m.time.toString(), m.text)
        .joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

/** 顯示名稱以逗號、頓號或換行分隔儲存。 */
fun parseNames(raw: String): List<String> =
    raw.split(',', '，', '、', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
