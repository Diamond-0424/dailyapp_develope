package com.dailyapp.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dailyapp.core.Platform
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dailyapp.core.ReminderPlanner
import com.dailyapp.data.HealthStore
import com.dailyapp.notify.Alerts
import com.dailyapp.notify.ChatListenerService
import com.dailyapp.notify.ReminderSettings
import com.dailyapp.notify.Reminders
import java.time.LocalTime

private const val STALE_MS = 6 * 60 * 60 * 1000L

@Composable
fun HealthScreen(tick: Int, onAskNotify: () -> Unit) {
    val ctx = LocalContext.current
    val health = remember { HealthStore(ctx) }
    val listenerOn = remember(tick) {
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }
    val connected = remember(tick) { ChatListenerService.isConnected }
    val notifyOn = remember(tick) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    // 已授權但沒連上：多半是自啟動被擋，請系統重新綁定一次
    LaunchedEffect(tick) { if (listenerOn && !connected) ChatListenerService.requestRebind(ctx) }

    val now = System.currentTimeMillis()
    val blocked = listenerOn && !connected
    val issues = listOf(!listenerOn, blocked, !notifyOn).count { it }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeader("狀態", if (issues == 0) "一切正常，正在監聽。" else "有 $issues 項需要處理。")

        SectionLabel("本 App")
        Rule()
        KeyValue(
            "讀取群組通知",
            when {
                !listenerOn -> "未授權"
                !connected -> "已授權，但未連線"
                else -> "監聽中"
            },
            emphasize = !listenerOn || !connected,
        )
        KeyValue("發送點名提醒", if (notifyOn) "已開啟" else "未開啟", emphasize = !notifyOn)
        KeyValue("監聽服務上次連線", fmtTime(health.connectedAt))
        KeyValue("監聽服務上次斷線", fmtTime(health.disconnectedAt))
        Row(Modifier.padding(horizontal = Space.Page, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (!listenerOn) PlainAction("開啟通知存取") { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            if (!notifyOn) PlainAction("允許發送通知", onAskNotify)
            PlainAction("本 App 設定") { openAppDetails(ctx, ctx.packageName) }
        }
        if (!listenerOn) Note("若無法開啟通知存取：本 App 設定 → 右上角 ⋮ → 允許受限設定。")
        if (blocked) {
            Note("系統拒絕啟動監聽服務，通常是「自啟動」未開啟。開啟後回到此頁，會自動重新連線。")
            Row(Modifier.padding(horizontal = Space.Page)) {
                OutlineAction("開啟自啟動管理", { openMiuiAutostart(ctx) }, Modifier.fillMaxWidth())
            }
        }

        ReminderSection(ctx, tick)

        SectionLabel("通訊 App")
        Rule()
        for (pl in Platform.entries) {
            PlatformBlock(ctx, pl, health, now)
            Rule()
        }

        SectionLabel("紅米 / HyperOS 設定")
        Rule()
        Note(
            "本 App 與 LINE、Discord、Telegram 都需要：\n" +
                "一、自啟動：開啟\n二、省電策略：無限制\n三、在最近使用畫面中下拉鎖定\n" +
                "四、群組不要在 App 內「關閉提醒」；嫌吵時改到 Android 通知設定，把類別設為靜音。"
        )
        Row(Modifier.padding(horizontal = Space.Page)) {
            OutlineAction("開啟自啟動管理", { openMiuiAutostart(ctx) }, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun PlatformBlock(ctx: Context, pl: Platform, health: HealthStore, now: Long) {
    val installed = pl.packages.firstOrNull { isInstalled(ctx, it) }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(pl.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Space.Page, vertical = 4.dp))
        if (installed == null) {
            KeyValue("安裝", "未安裝")
            return@Column
        }
        val last = health.lastNotificationAt(pl)
        val stale = last != null && now - last > STALE_MS
        KeyValue(
            "最後收到通知",
            when {
                last == null -> "尚未收到"
                stale -> "${fmtTime(last)}（超過 6 小時）"
                else -> fmtTime(last)
            },
            emphasize = last == null || stale,
        )
        val fb = health.fallbackCount(pl)
        val un = health.unparsedCount(pl)
        if (fb > 0 || un > 0) KeyValue("非標準格式／無法解析", "$fb／$un", emphasize = un > 0)
        Row(Modifier.padding(horizontal = Space.Page), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            PlainAction("通知設定") { openAppNotificationSettings(ctx, installed) }
            PlainAction("App 資訊") { openAppDetails(ctx, installed) }
        }
    }
}

@Composable
private fun ReminderSection(ctx: Context, tick: Int) {
    val settings = remember { ReminderSettings(ctx) }
    var times by remember { mutableStateOf(settings.digestTimes) }
    val exact = remember(tick) { Reminders.canExact(ctx) }

    fun save(newTimes: List<LocalTime>) {
        settings.digestTimes = newTimes
        times = settings.digestTimes
        Reminders.scheduleDigest(ctx)
    }

    // 正在編輯的時間：null 表示沒開對話框
    var editing by remember { mutableStateOf<Pair<LocalTime, (LocalTime) -> Unit>?>(null) }
    fun pick(initial: LocalTime, onPicked: (LocalTime) -> Unit) { editing = initial to onPicked }
    editing?.let { (initial, onPicked) ->
        TimeWheelDialog(
            title = "每日摘要時間",
            initial = initial,
            onDismiss = { editing = null },
            onConfirm = { editing = null; onPicked(it) },
        )
    }

    SectionLabel("提醒")
    Rule()
    KeyValue("準時提醒（精確鬧鐘）", if (exact) "可用" else "不可用，提醒可能延遲", emphasize = !exact)
    Text(
        "每日摘要",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = Space.Page).padding(top = 6.dp),
    )
    Row(Modifier.padding(horizontal = Space.Page), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        times.forEach { t ->
            PlainAction(t.toString()) { pick(t) { picked -> save(times - t + picked) } }
        }
        PlainAction("＋") { pick(LocalTime.of(12, 0)) { save(times + it) } }
    }
    Note(
        "點時間可修改。截止前一天與當天，會在第一個時間（${times.firstOrNull() ?: "09:00"}）提醒。\n" +
            "若要讓「被點名、今天截止」在勿擾模式下也響，請在下方開啟「覆寫勿擾」。"
    )
    Row(Modifier.padding(horizontal = Space.Page), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        PlainAction("緊急提醒設定") {
            ctx.startActivity(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, Alerts.CH_URGENT)
            )
        }
        if (times.size > 1) PlainAction("移除最後一個") { save(times.dropLast(1)) }
        PlainAction("恢復預設") { save(ReminderPlanner.parseTimes(ReminderSettings.DEFAULT)) }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = Space.Page, vertical = 12.dp),
    )
}

private fun isInstalled(ctx: Context, pkg: String) =
    runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

private fun openAppDetails(ctx: Context, pkg: String) =
    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)))

private fun openAppNotificationSettings(ctx: Context, pkg: String) =
    ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))

/** 小米的自啟動管理頁；找不到時退回本 App 設定。 */
private fun openMiuiAutostart(ctx: Context) {
    val i = Intent().setComponent(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
    )
    runCatching { ctx.startActivity(i) }.onFailure { openAppDetails(ctx, ctx.packageName) }
}
