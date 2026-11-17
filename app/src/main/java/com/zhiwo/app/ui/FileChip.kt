package com.zhiwo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiwo.app.R

/** 文件在上传流程里的几种状态，文案沿用原版。 */
enum class DocStatus(val label: String) {
    UPLOADING("上传中..."),
    OK("已上传"),
    FAILED("解析失败"),
    /** 官方导出包里只带了文件名，文件本体没导出来。 */
    MISSING("附件未导出")
}

/**
 * 数据库里 attachment 的文档项统一用 `状态|file_id|文件名` 编码。
 * 老数据是 `file_id|文件名`，解析时按两段处理。
 */
data class DocRef(val fileId: String, val name: String, val status: DocStatus)

fun parseDocRef(raw: String): DocRef {
    val parts = raw.split("|")
    if (parts.size >= 3 && parts[0] in setOf("up", "ok", "err", "miss")) {
        val status = when (parts[0]) {
            "up" -> DocStatus.UPLOADING
            "err" -> DocStatus.FAILED
            "miss" -> DocStatus.MISSING
            else -> DocStatus.OK
        }
        return DocRef(parts[1], parts.drop(2).joinToString("|"), status)
    }
    if (parts.size >= 2) {
        return DocRef(parts[0], parts.drop(1).joinToString("|"), DocStatus.OK)
    }
    return DocRef(raw, raw, DocStatus.OK)
}

/** 按扩展名挑原版那套文件类型图标。 */
private fun iconFor(fileName: String): Int {
    val n = fileName.lowercase()
    return when {
        n.endsWith(".pdf") -> R.drawable.ic_type_pdf
        n.endsWith(".xls") || n.endsWith(".xlsx") || n.endsWith(".csv") -> R.drawable.ic_type_xls
        n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") ||
            n.endsWith(".webp") || n.endsWith(".gif") || n.endsWith(".bmp") ->
            R.drawable.ic_type_pic
        else -> R.drawable.ic_type_text
    }
}

/**
 * 原版那种文件卡片：类型图标 + 文件名 + 状态。
 * 输入框上方待发送时用它，消息气泡里也用同一张。
 */
@Composable
fun FileChip(
    fileName: String,
    status: DocStatus,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val statusColor = when (status) {
        DocStatus.UPLOADING -> scheme.onSurfaceVariant
        DocStatus.OK -> scheme.onSurfaceVariant.copy(alpha = 0.75f)
        DocStatus.FAILED -> scheme.error
        DocStatus.MISSING -> scheme.onSurfaceVariant.copy(alpha = 0.75f)
    }

    Row(
        modifier = modifier
            .widthIn(max = 230.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surface)
            .border(1.dp, scheme.outline, RoundedCornerShape(12.dp))
            .padding(start = 10.dp, end = if (onRemove != null) 6.dp else 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(iconFor(fileName)),
            contentDescription = null,
            // 关键：不 tint，否则会被 LocalContentColor 染成黑色，vector 里的颜色全丢
            tint = Color.Unspecified,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                text = fileName,
                fontSize = 12.5.sp,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = status.label,
                fontSize = 10.5.sp,
                color = statusColor
            )
        }
        if (onRemove != null) {
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onRemove() }
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("×", fontSize = 14.sp, color = scheme.onSurfaceVariant)
            }
        }
    }
}
