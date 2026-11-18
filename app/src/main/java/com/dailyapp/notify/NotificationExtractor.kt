package com.dailyapp.notify

import android.app.Notification
import android.app.Person
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.dailyapp.core.ParseMode
import com.dailyapp.core.ParsedConversation
import com.dailyapp.core.ParsedMessage
import com.dailyapp.core.Platform

/**
 * 把通知轉成 ParsedConversation。
 * 優先讀 MessagingStyle 的 android.messages；沒有的話退回 title/text（備援模式）。
 * 欄位依據見 spec.md 2.1.1（M0 真機驗證結果）。
 */
object NotificationExtractor {

    sealed interface Result {
        data class Ok(val conv: ParsedConversation) : Result
        data object Skipped : Result     // 群組摘要通知等，不需處理
        data object Unparsed : Result    // 目標 App 的通知，但讀不到任何文字
    }

    fun extract(sbn: StatusBarNotification): Result? {
        val platform = Platform.fromPackage(sbn.packageName) ?: return null
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return Result.Skipped
        val e = n.extras

        val title = e.str(Notification.EXTRA_TITLE)
        val convTitle = e.str(Notification.EXTRA_CONVERSATION_TITLE)
        val isGroup = e.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, convTitle != null)
        @Suppress("DEPRECATION")
        val selfName = e.str(Notification.EXTRA_SELF_DISPLAY_NAME)
            ?: (e.getParcelable(Notification.EXTRA_MESSAGING_PERSON) as? Person)?.name?.toString()

        val name = convTitle ?: title ?: return Result.Unparsed
        val key = n.shortcutId?.takeIf { it.isNotBlank() } ?: name

        val styled = parseMessages(e)
        val (messages, mode) = if (styled.isNotEmpty()) {
            styled to ParseMode.MESSAGING_STYLE
        } else {
            val text = e.str(Notification.EXTRA_BIG_TEXT) ?: e.str(Notification.EXTRA_TEXT)
                ?: return Result.Unparsed
            // 備援：非群組時標題通常就是發言者
            listOf(ParsedMessage(sender = title ?: name, senderKey = null, text = text, time = sbn.postTime)) to
                ParseMode.FALLBACK
        }

        return Result.Ok(
            ParsedConversation(
                platform = platform,
                conversationKey = key,
                title = name,
                isGroup = isGroup,
                selfName = selfName,
                messages = messages,
                mode = mode,
            )
        )
    }

    @Suppress("DEPRECATION")
    private fun parseMessages(e: Bundle): List<ParsedMessage> {
        val arr = e.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
        return arr.mapNotNull { p ->
            val b = p as? Bundle ?: return@mapNotNull null
            val text = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
            val person = b.getParcelable("sender_person") as? Person
            val sender = person?.name?.toString() ?: b.getCharSequence("sender")?.toString()
            ParsedMessage(
                sender = sender,
                senderKey = person?.key,
                text = text,
                time = b.getLong("time"),
                mimeType = b.getString("type"),
            )
        }
    }

    private fun Bundle.str(k: String): String? = getCharSequence(k)?.toString()?.takeIf { it.isNotBlank() }
}
