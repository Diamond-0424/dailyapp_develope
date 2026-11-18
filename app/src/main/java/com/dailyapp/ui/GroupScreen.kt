package com.dailyapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailyapp.ai.AiAssist
import com.dailyapp.core.Platform
import com.dailyapp.data.AiStore
import com.dailyapp.core.parseNames
import com.dailyapp.data.AppDatabase
import com.dailyapp.data.ChatGroup
import com.dailyapp.data.GroupState
import com.dailyapp.data.Role
import kotlinx.coroutines.launch

@Composable
fun GroupScreen(db: AppDatabase) {
    val groups by remember { db.groups().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val roles by remember { db.roles().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val links by remember { db.roles().observeLinks() }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ChatGroup?>(null) }
    // 編輯中的身分：id = 0 代表新增
    var editingRole by remember { mutableStateOf<Role?>(null) }
    var assigning by remember { mutableStateOf<ChatGroup?>(null) }

    val rolesById = roles.associateBy { it.id }
    val rolesOfGroup = links.groupBy({ it.groupId }, { it.roleId })

    fun save(g: ChatGroup) = scope.launch { db.groups().update(g) }

    val sections = listOf(
        "新發現" to groups.filter { it.state == GroupState.NEW },
        "監聽中" to groups.filter { it.state == GroupState.ENABLED },
        "不監聽" to groups.filter { it.state == GroupState.IGNORED },
    )

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            PageHeader("群組", "收到通知的對話會列在這裡，預設不監聽。")
        }
        item(key = "roles") {
            RoleSection(roles, onEdit = { editingRole = it }, onAdd = { editingRole = Role(name = "", description = "", keywords = "") })
        }
        if (groups.isEmpty()) item {
            Text(
                "尚未發現任何對話。請先到「狀態」確認權限都已開啟。",
                style = MaterialTheme.typography.bodyMedium.copy(color = Ink.Secondary),
                modifier = Modifier.padding(Space.Page),
            )
        }
        for ((label, list) in sections) {
            if (list.isEmpty()) continue
            item(key = "h:$label") {
                SectionLabel("$label　${list.size}")
                Rule()
            }
            items(list, key = { it.id }) { g ->
                GroupItem(
                    g,
                    roleNames = rolesOfGroup[g.id].orEmpty().mapNotNull { rolesById[it]?.name },
                    onEnable = {
                        val names = g.displayNames.ifBlank { g.observedSelfName.orEmpty() }
                        save(g.copy(state = GroupState.ENABLED, displayNames = names))
                    },
                    onIgnore = { save(g.copy(state = GroupState.IGNORED)) },
                    onEditNames = { editing = g },
                    onAssign = { assigning = g },
                )
                Rule()
            }
        }
    }

    editing?.let { g ->
        var text by remember(g.id) { mutableStateOf(g.displayNames.ifBlank { g.observedSelfName.orEmpty() }) }
        AlertDialog(
            onDismissRequest = { editing = null },
            shape = RoundedCornerShape(4.dp),
            containerColor = Ink.White,
            title = { Text("我在此群組的名稱", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(g.title, style = MaterialTheme.typography.bodyMedium)
                    Text("多個名稱以逗號分隔，例如：小美, Mei, 美宣", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Discord 被 @ 時顯示的是「顯示名稱」或伺服器暱稱，不是帳號名稱，請一併填入。花體或上標字母直接輸入一般英文即可。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    InkField(text, { text = it }, "名稱")
                }
            },
            confirmButton = {
                PlainAction("儲存") {
                    save(g.copy(displayNames = parseNames(text).joinToString(", ")))
                    editing = null
                }
            },
            dismissButton = { PlainAction("取消") { editing = null } },
        )
    }

    editingRole?.let { r ->
        RoleDialog(
            role = r,
            onDismiss = { editingRole = null },
            onSave = { saved ->
                scope.launch { if (saved.id == 0L) db.roles().insert(saved) else db.roles().update(saved) }
                editingRole = null
            },
            onDelete = {
                scope.launch { db.roles().delete(r) }
                editingRole = null
            },
        )
    }

    assigning?.let { g ->
        GroupRolesDialog(
            group = g,
            roles = roles,
            initial = rolesOfGroup[g.id].orEmpty().toSet(),
            onDismiss = { assigning = null },
            onSave = { ids, keywords ->
                scope.launch {
                    db.roles().setGroupRoles(g.id, ids)
                    db.groups().setExtraKeywords(g.id, parseNames(keywords).joinToString(", "))
                }
                assigning = null
            },
        )
    }
}

@Composable
private fun RoleSection(roles: List<Role>, onEdit: (Role) -> Unit, onAdd: () -> Unit) {
    Column {
        SectionLabel("身分　${roles.size}")
        Rule()
        if (roles.isEmpty()) Text(
            "例如「美宣」「總務」。AI 會依身分的職責與關鍵字，判斷哪些訊息跟你有關。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = Space.Page).padding(top = 12.dp),
        )
        roles.forEach { r ->
            Row(
                Modifier.fillMaxWidth().clickable { onEdit(r) }.padding(horizontal = Space.Page, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(r.name, style = MaterialTheme.typography.titleMedium)
                    val kw = parseNames(r.keywords)
                    if (kw.isNotEmpty()) Text(kw.joinToString("、"), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                Text("編輯", style = MaterialTheme.typography.labelLarge)
            }
        }
        Row(Modifier.padding(horizontal = Space.Page)) {
            PlainAction("＋ 新增身分", onAdd)
        }
    }
}

@Composable
private fun GroupItem(
    g: ChatGroup,
    roleNames: List<String>,
    onEnable: () -> Unit,
    onIgnore: () -> Unit,
    onEditNames: () -> Unit,
    onAssign: () -> Unit,
) {
    val label = Platform.fromId(g.platform)?.label ?: g.platform
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.Page, vertical = Space.Item),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(g.title, style = MaterialTheme.typography.titleMedium)
        Text("$label　${if (g.isGroup) "群組" else "私訊"}　最後訊息 ${fmtTime(g.lastSeenAt)}", style = MaterialTheme.typography.bodySmall)
        if (g.state == GroupState.ENABLED) {
            Text(
                "我的名稱：" + g.displayNames.ifBlank { "未設定（無法偵測點名）" },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("身分：" + roleNames.joinToString("、").ifBlank { "未設定" }, style = MaterialTheme.typography.bodyMedium)
            val kw = parseNames(g.extraKeywords)
            if (kw.isNotEmpty()) Text("附加關鍵字：" + kw.joinToString("、"), style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (g.state != GroupState.ENABLED) PlainAction("開始監聽", onEnable)
            if (g.state == GroupState.ENABLED) PlainAction("設定名稱", onEditNames)
            if (g.state == GroupState.ENABLED) PlainAction("設定身分", onAssign)
            if (g.state != GroupState.IGNORED) PlainAction("不監聽", onIgnore)
        }
    }
}

@Composable
private fun InkField(value: String, onChange: (String) -> Unit, label: String, singleLine: Boolean = true) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        textStyle = MaterialTheme.typography.bodyLarge,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        shape = RoundedCornerShape(2.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Ink.Black, unfocusedBorderColor = Ink.Rule, cursorColor = Ink.Black,
        ),
    )
}

@Composable
private fun RoleDialog(role: Role, onDismiss: () -> Unit, onSave: (Role) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(role.name) }
    var desc by remember { mutableStateOf(role.description) }
    var keywords by remember { mutableStateOf(role.keywords) }
    var confirmDelete by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasKey = remember { AiStore(ctx).hasKey }
    var suggesting by remember { mutableStateOf(false) }
    var suggestError by remember { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(4.dp),
        containerColor = Ink.White,
        title = { Text(if (role.id == 0L) "新增身分" else "編輯身分", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InkField(name, { name = it }, "名稱，例如：美宣")
                InkField(desc, { desc = it }, "職責描述，例如：負責海報與社群貼文設計", singleLine = false)
                InkField(keywords, { keywords = it }, "關鍵字，以逗號分隔", singleLine = false)
                Text("關鍵字只是給 AI 的提示，不會用來過濾訊息。", style = MaterialTheme.typography.bodySmall)
                if (hasKey && name.isNotBlank()) PlainAction(if (suggesting) "AI 思考中…" else "AI 建議關鍵字") {
                    if (suggesting) return@PlainAction
                    suggesting = true
                    suggestError = null
                    scope.launch {
                        AiAssist.suggestKeywords(ctx, name.trim(), desc.trim(), parseNames(keywords))
                            .onSuccess { suggestions = it; picked = it.toSet() }
                            .onFailure { suggestError = it.message }
                        suggesting = false
                    }
                }
                suggestError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (suggestions.isNotEmpty()) {
                    suggestions.forEach { kw ->
                        val on = kw in picked
                        Row(
                            Modifier.fillMaxWidth().clickable { picked = if (on) picked - kw else picked + kw },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = on,
                                onCheckedChange = { picked = if (it) picked + kw else picked - kw },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Ink.Black, uncheckedColor = Ink.Secondary, checkmarkColor = Ink.White,
                                ),
                            )
                            Text(kw, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    PlainAction("加入勾選的 ${picked.size} 個") {
                        keywords = (parseNames(keywords) + suggestions.filter { it in picked }).distinct().joinToString(", ")
                        suggestions = emptyList()
                    }
                }
                if (role.id != 0L) PlainAction(if (confirmDelete) "確定刪除？再按一次" else "刪除此身分") {
                    if (confirmDelete) onDelete() else confirmDelete = true
                }
            }
        },
        confirmButton = {
            PlainAction("儲存") {
                if (name.isNotBlank()) onSave(
                    role.copy(name = name.trim(), description = desc.trim(), keywords = parseNames(keywords).joinToString(", "))
                )
            }
        },
        dismissButton = { PlainAction("取消", onDismiss) },
    )
}

@Composable
private fun GroupRolesDialog(
    group: ChatGroup,
    roles: List<Role>,
    initial: Set<Long>,
    onDismiss: () -> Unit,
    onSave: (Set<Long>, String) -> Unit,
) {
    var selected by remember { mutableStateOf(initial) }
    var keywords by remember { mutableStateOf(group.extraKeywords) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(4.dp),
        containerColor = Ink.White,
        title = { Text("設定身分", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(group.title, style = MaterialTheme.typography.bodyMedium)
                if (roles.isEmpty()) Text("尚未建立任何身分，請先在群組頁上方新增。", style = MaterialTheme.typography.bodySmall)
                roles.forEach { r ->
                    val on = r.id in selected
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = if (on) selected - r.id else selected + r.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = on,
                            onCheckedChange = { selected = if (it) selected + r.id else selected - r.id },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Ink.Black, uncheckedColor = Ink.Secondary, checkmarkColor = Ink.White,
                            ),
                        )
                        Text(r.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                InkField(keywords, { keywords = it }, "此群組的附加關鍵字（選填）", singleLine = false)
            }
        },
        confirmButton = { PlainAction("儲存") { onSave(selected, keywords) } },
        dismissButton = { PlainAction("取消", onDismiss) },
    )
}
