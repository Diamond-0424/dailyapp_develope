package com.dailyapp.probe

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Screen() } }
    }

    override fun onResume() {
        super.onResume()
        tick++
    }

    @Composable
    private fun Screen() {
        val t = tick
        val granted = remember(t) {
            NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        }
        val stats = remember(t) { LogStore.stats(this) }
        var confirmClear by remember { mutableStateOf(false) }

        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("dailyapp 探針（M0）", style = MaterialTheme.typography.headlineSmall)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("通知存取權：" + if (granted) "✅ 已開啟" else "❌ 未開啟")
                    Text("監聽服務上次連線：${fmt(stats.lastConnected)}")
                    Text("監聽服務上次斷線：${fmt(stats.lastDisconnected)}")
                    Text("勿擾模式下收到的通知：${stats.dndCount} 則")
                    Text("記錄檔大小：${stats.sizeBytes / 1024} KB")
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("各平台收到的通知", style = MaterialTheme.typography.titleMedium)
                    if (stats.perApp.isEmpty()) Text("尚無資料")
                    stats.perApp.forEach { (app, v) ->
                        Text("$app：${v.first} 則，最後 ${fmt(v.second)}")
                    }
                }
            }

            Button(
                onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("開啟通知存取設定") }

            OutlinedButton(
                onClick = {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null),
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("開啟本 App 設定（受限設定、省電）") }

            OutlinedButton(onClick = { tick++ }, modifier = Modifier.fillMaxWidth()) { Text("重新整理") }
            OutlinedButton(onClick = { export() }, modifier = Modifier.fillMaxWidth()) { Text("匯出記錄檔") }
            TextButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) { Text("清除記錄") }

            Text(
                "記錄只存在本 App 的私有空間，內容是真實訊息。匯出前請先確認分享對象。",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("清除所有記錄？") },
                confirmButton = {
                    TextButton(onClick = {
                        LogStore.clear(this)
                        confirmClear = false
                        tick++
                    }) { Text("清除") }
                },
                dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
            )
        }
    }

    private fun export() {
        val f = LogStore.file(this)
        if (!f.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "匯出記錄檔"))
    }

    private fun fmt(ts: Long?): String =
        if (ts == null || ts == 0L) "—"
        else SimpleDateFormat("MM/dd HH:mm:ss", Locale.TAIWAN).format(Date(ts))
}
