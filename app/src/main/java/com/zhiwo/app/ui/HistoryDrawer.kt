package com.zhiwo.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiwo.app.R
import com.zhiwo.app.data.Conversation
import com.zhiwo.app.data.Kb
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 抽屉里的知识库筛选：null = 全部，空串 = 未分类。 */
const val KB_UNCLASSIFIED = ""

@Composable
fun HistoryDrawer(
    conversations: List<Conversation>,
    currentId: String?,
    kbs: List<Kb>,
    selectedKbId: String?,
    onSelectKb: (String?) -> Unit,
    onNewKb: () -> Unit,
    onKbMenu: (Kb) -> Unit,
    onOpenMaster: (String?) -> Unit,
    masterInstances: List<Conversation>,
    onNewMaster: () -> Unit,
    onDeleteMaster: (String) -> Unit,
    onMasterScope: (Conversation) -> Unit,
    onOpenKbMaster: (String) -> Unit,
    masterName: String,
    onNewChat: () -> Unit,
    onOpen: (String) -> Unit,
    onLongPress: (Conversation) -> Unit,
    onOpenImport: () -> Unit,
    onOrganize: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var query by remember { mutableStateOf("") }

    val filtered = remember(conversations, query, selectedKbId) {
        val q = query.trim()
        conversations.filter { conv ->
            val kbOk = when (selectedKbId) {
                null -> true
                KB_UNCLASSIFIED -> conv.kbId == null
                else -> conv.kbId == selectedKbId
            }
            val qOk = q.isEmpty() || conv.title.contains(q, ignoreCase = true)
            kbOk && qOk
        }
    }
    val sections = remember(filtered) {
        val order = listOf("置顶", "今天", "昨天", "7 天内", "更早")
        val map = LinkedHashMap<String, MutableList<Conversation>>()
        // 置顶的单独拎出来放最上面，不参与日期分组
        filtered.filter { it.pinned }.takeIf { it.isNotEmpty() }?.let { pinned ->
            map["置顶"] = pinned.toMutableList()
        }
        filtered.filter { !it.pinned }.forEach { conv ->
            map.getOrPut(dateBucket(conv.updatedAt)) { mutableListOf() }.add(conv)
        }
        order.mapNotNull { key -> map[key]?.let { key to it } }
    }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(18.dp))
        Text(
            text = "对话记录",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text("搜索对话", fontSize = 13.sp) },
            singleLine = true,
            shape = RoundedCornerShape(20.dp),
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_ds_search),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = LocalZhiwoExtra.current.inputBar,
                unfocusedContainerColor = LocalZhiwoExtra.current.inputBar,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent
            )
        )

        Spacer(Modifier.height(4.dp))

        // 总 AI 实例列表：主实例不可删，其余的可以删；长按任何一条都能再开一个
        val list = if (masterInstances.isEmpty()) {
            emptyList()
        } else {
            masterInstances
        }
        list.forEach { master ->
            var menu by remember(master.id) { mutableStateOf(false) }
            Box {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 1.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (master.id == currentId) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            }
                        )
                        .combinedClickable(
                            onClick = { onOpenMaster(master.id) },
                            onLongClick = { menu = true }
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_ds_think),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = master.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (master.isPrimary) {
                        Text(
                            text = "主",
                            fontSize = 9.5.sp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                    }
                }

                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("新建一个 $masterName") },
                        onClick = {
                            menu = false
                            onNewMaster()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (master.kbScope.isEmpty()) "知识范围：全部"
                                else "知识范围：${master.kbScope.size} 个库"
                            )
                        },
                        onClick = {
                            menu = false
                            onMasterScope(master)
                        }
                    )
                    if (!master.isPrimary) {
                        DropdownMenuItem(
                            text = { Text("删除这个") },
                            onClick = {
                                menu = false
                                onDeleteMaster(master.id)
                            }
                        )
                    }
                }
            }
        }

        // 还没有任何实例时给一个入口，避免整块消失
        if (list.isEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                    .clickable { onOpenMaster(null) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = masterName,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "记得你们聊过的一切",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable { onNewChat() }
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_ds_new_chat),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(text = "新建对话", fontSize = 14.5.sp, color = MaterialTheme.colorScheme.primary)
        }

        // 知识库筛选条
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KbChip(label = "全部", selected = selectedKbId == null, onClick = { onSelectKb(null) })
            kbs.forEach { kb ->
                KbChip(
                    label = kb.name,
                    selected = selectedKbId == kb.id,
                    onClick = { onSelectKb(kb.id) },
                    onLongClick = { onKbMenu(kb) }
                )
            }
            KbChip(
                label = "未分类",
                selected = selectedKbId == KB_UNCLASSIFIED,
                onClick = { onSelectKb(KB_UNCLASSIFIED) }
            )
            KbChip(label = "+ 新建库", selected = false, onClick = onNewKb)
        }

        Spacer(Modifier.height(4.dp))

        // 筛选到某个库时，顶部给这个库的助手一个明确入口
        val selectedKb = kbs.firstOrNull { it.id == selectedKbId }
        if (selectedKb != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
                    .clickable { onOpenKbMaster(selectedKb.id) }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_ds_think),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${selectedKb.name} · 知识库助手",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "这个库里聊过的一切都记得",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (sections.isEmpty()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when {
                        query.isNotBlank() -> "没找到"
                        selectedKbId != null -> "这个库还是空的"
                        else -> "还没有对话"
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                sections.forEach { (label, list) ->
                    item(key = "header-$label") {
                        Text(
                            text = label,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp)
                        )
                    }
                    items(list, key = { it.id }) { conv ->
                        HistoryRow(
                            conv = conv,
                            selected = conv.id == currentId,
                            onClick = { onOpen(conv.id) },
                            onLongPress = { onLongPress(conv) }
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        DrawerFooterItem("一键整理", onOrganize)
        DrawerFooterItem("导入对话", onOpenImport)
        DrawerFooterItem("设置", onOpenSettings)
        Spacer(Modifier.height(14.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KbChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) scheme.primaryContainer.copy(alpha = 0.7f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = { onLongClick?.invoke() })
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (selected) scheme.primary else scheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun DrawerFooterItem(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    conv: Conversation,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) scheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = conv.title,
            fontSize = 14.sp,
            color = if (selected) scheme.primary else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (conv.pinned) {
            Spacer(Modifier.width(6.dp))
            Icon(
                painter = painterResource(R.drawable.ic_ds_pin),
                contentDescription = "已置顶",
                tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp)
            )
        }
        if (conv.kbLocked) {
            Spacer(Modifier.width(5.dp))
            Text(
                text = "锁定",
                fontSize = 9.5.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

private fun dateBucket(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when {
        date == today -> "今天"
        date == today.minusDays(1) -> "昨天"
        date.isAfter(today.minusDays(7)) -> "7 天内"
        else -> "更早"
    }
}
