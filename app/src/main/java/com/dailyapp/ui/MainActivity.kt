package com.dailyapp.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailyapp.data.AppDatabase

class MainActivity : ComponentActivity() {
    private var tab by mutableIntStateOf(TAB_TODO)
    /** onResume 時遞增，讓健康檢查頁重新讀取權限狀態。 */
    private var resumeTick by mutableIntStateOf(0)

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.WHITE, Color.WHITE),
            navigationBarStyle = SystemBarStyle.light(Color.WHITE, Color.WHITE),
        )
        super.onCreate(savedInstanceState)
        tab = intent.getIntExtra(EXTRA_TAB, TAB_TODO)
        val db = AppDatabase.get(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            AppTheme {
                val newGroups by db.groups().observeNewCount().collectAsStateWithLifecycle(0)
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            TAB_TODO -> TodoScreen(db)
                            TAB_GROUPS -> GroupScreen(db)
                            else -> HealthScreen(resumeTick, onAskNotify = {
                                askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                            })
                        }
                    }
                    BottomTabs(
                        labels = listOf("待辦", if (newGroups > 0) "群組・$newGroups" else "群組", "狀態"),
                        selected = tab,
                        onSelect = { tab = it },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tab = intent.getIntExtra(EXTRA_TAB, tab)
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_TODO = 0
        const val TAB_GROUPS = 1
        const val TAB_HEALTH = 2
    }
}
