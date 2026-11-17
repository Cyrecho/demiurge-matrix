package com.zhiwo.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zhiwo.app.R
import com.zhiwo.app.data.Conversation

/**
 * 长按会话后从底部升起的操作面板。
 * 文案沿用原版：重命名 / 置顶 / 取消置顶 / 删除。
 */
@Composable
fun ConversationActionSheet(
    conv: Conversation,
    onDismiss: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onUnlock: () -> Unit,
    onAddProfile: () -> Unit,
    onContext: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val dismissInteraction = remember { MutableInteractionSource() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f))
                .clickable(
                    indication = null,
                    interactionSource = dismissInteraction
                ) { onDismiss() },
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                color = scheme.surface,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(
                        text = conv.title,
                        fontSize = 12.5.sp,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp)
                    )
                    HorizontalDivider(color = scheme.outline)

                    SheetRow(
                        icon = R.drawable.ic_ds_pin,
                        label = if (conv.pinned) "取消置顶" else "置顶",
                        onClick = onPin
                    )
                    SheetRow(
                        icon = R.drawable.ic_ds_edit,
                        label = "重命名",
                        onClick = onRename
                    )
                    SheetRow(
                        icon = null,
                        label = "移动到知识库",
                        onClick = onMove
                    )
                    SheetRow(
                        icon = null,
                        label = if (conv.inProfile) "已在用户档案中" else "加入用户档案",
                        enabled = !conv.inProfile,
                        onClick = onAddProfile
                    )
                    SheetRow(
                        icon = null,
                        label = "上下文占用",
                        onClick = onContext
                    )
                    SheetRow(
                        icon = null,
                        label = "导出为 Markdown",
                        onClick = onExport
                    )
                    if (conv.kbLocked) {
                        SheetRow(
                            icon = null,
                            label = "解除锁定",
                            onClick = onUnlock
                        )
                    }

                    HorizontalDivider(color = scheme.outline)
                    SheetRow(
                        icon = R.drawable.ic_ds_trash,
                        label = "删除",
                        destructive = true,
                        onClick = onDelete
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun SheetRow(
    @DrawableRes icon: Int?,
    label: String,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val color = when {
        !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.5f)
        destructive -> scheme.error
        else -> scheme.onSurface
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.width(14.dp))
        } else {
            Spacer(Modifier.width(31.dp))
        }
        Text(text = label, fontSize = 14.5.sp, color = color)
    }
}
