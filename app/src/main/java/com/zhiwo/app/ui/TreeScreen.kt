package com.zhiwo.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Offset as GeoOffset
import com.zhiwo.app.AppViewModel
import com.zhiwo.app.R
import com.zhiwo.app.data.Message

/** 一个节点的布局位置，单位是「格」，绘制时再乘间距。 */
private data class TreeLayout(
    val column: Map<String, Float>,
    val depth: Map<String, Int>,
    val maxDepth: Int,
    val maxColumn: Float
)

/**
 * 这棵树的画法：
 * 纵轴是深度（谁接在谁后面），横轴是分叉的顺序。
 * 叶子从左往右依次占一格，父节点落在子节点的中间 —— 就是教科书上的 tidy tree。
 */
private fun layoutTree(messages: List<Message>): TreeLayout {
    val children = messages.groupBy { it.parentId ?: "" }
    val column = HashMap<String, Float>()
    val depth = HashMap<String, Int>()
    var cursor = 0f
    var maxDepth = 0

    fun walk(id: String, d: Int): Float {
        depth[id] = d
        if (d > maxDepth) maxDepth = d
        val kids = (children[id] ?: emptyList()).sortedBy { it.createdAt }
        val x = if (kids.isEmpty()) {
            val v = cursor
            cursor += 1f
            v
        } else {
            val xs = kids.map { walk(it.id, d + 1) }
            (xs.min() + xs.max()) / 2f
        }
        column[id] = x
        return x
    }

    messages.filter { it.parentId == null }.sortedBy { it.createdAt }.forEach { walk(it.id, 0) }
    // 没有根（理论上不会）时兜底，避免整棵树消失
    if (column.isEmpty() && messages.isNotEmpty()) {
        messages.forEachIndexed { i, m ->
            column[m.id] = i.toFloat()
            depth[m.id] = 0
        }
    }
    return TreeLayout(column, depth, maxDepth, cursor.coerceAtLeast(1f))
}

/** 从当前叶子往回溯到根，这条链就是要高亮的路径。 */
private fun pathToRoot(messages: List<Message>, leafId: String?): Set<String> {
    if (leafId == null) return emptySet()
    val byId = messages.associateBy { it.id }
    val set = HashSet<String>()
    var cur = byId[leafId]
    var guard = 0
    while (cur != null && guard++ < 5000) {
        set.add(cur.id)
        cur = cur.parentId?.let { byId[it] }
    }
    return set
}

@Composable
fun TreeScreen(
    vm: AppViewModel,
    convId: String?,
    activeLeafId: String?,
    onClose: () -> Unit
) {
    if (convId == null) {
        onClose()
        return
    }

    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    LaunchedEffect(convId) { messages = vm.allMessages(convId) }

    val layout = remember(messages) { layoutTree(messages) }
    val path = remember(messages, activeLeafId) { pathToRoot(messages, activeLeafId) }
    val byId = remember(messages) { messages.associateBy { it.id } }

    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(GeoOffset.Zero) }
    var selected by remember { mutableStateOf<Message?>(null) }
    var canvasWidth by remember { mutableFloatStateOf(0f) }

    val density = LocalDensity.current
    // 世界坐标里树的中心已经在 x=0（绘制时减掉了 centerShift），
    // 所以 pan.x 要等于画布宽度的一半，树才真正落在屏幕中间。
    // 之前拿根节点位置去算，根不在中间时整棵树就偏到左边了。
    LaunchedEffect(layout, messages, canvasWidth) {
        if (messages.isNotEmpty() && canvasWidth > 0f) {
            pan = GeoOffset(canvasWidth / 2f, with(density) { 110.dp.toPx() })
            scale = 1f
        }
    }

    val scheme = MaterialTheme.colorScheme

    Box(
        Modifier
            .fillMaxSize()
            .background(scheme.background)
            .onSizeChanged { canvasWidth = it.width.toFloat() }
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, panChange, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.35f, 2.6f)
                        pan += panChange
                    }
                }
                .pointerInput(messages, layout) {
                    detectTapGestures { tap ->
                        // 绘制时是 screen = world * scale + pan，这里反推回世界坐标。
                        // 注意别再加 size.width/2 —— 横向居中是在绘制阶段用 centerShift 做的，
                        // 命中测试必须和绘制用同一套坐标系。
                        val worldX = (tap.x - pan.x) / scale
                        val worldY = (tap.y - pan.y) / scale
                        val stepX = 52.dp.toPx()
                        val stepY = 64.dp.toPx()
                        val centerShift = layout.maxColumn * stepX / 2f
                        // 屏幕上的容差固定 30dp，换算到世界坐标要除以缩放
                        val tolerance = 30.dp.toPx() / scale

                        var best: Message? = null
                        var bestDist = Float.MAX_VALUE
                        messages.forEach { m ->
                            val cx = (layout.column[m.id] ?: 0f) * stepX - centerShift
                            val cy = (layout.depth[m.id] ?: 0) * stepY
                            val dx = cx - worldX
                            val dy = cy - worldY
                            val d = dx * dx + dy * dy
                            if (d < bestDist) {
                                bestDist = d
                                best = m
                            }
                        }
                        val hit = best
                        if (hit != null && bestDist <= tolerance * tolerance) {
                            selected = hit
                        }
                    }
                }
        ) {
            val stepX = 52.dp.toPx()
            val stepY = 64.dp.toPx()
            val centerShift = layout.maxColumn * stepX / 2f

            withTransform({
                translate(pan.x, pan.y)
                scale(scale, scale, pivot = GeoOffset.Zero)
            }) {
                // 先画连线：父子之间走一条竖直方向的贝塞尔，比直线柔和
                messages.forEach { m ->
                    val parentId = m.parentId ?: return@forEach
                    val px = (layout.column[parentId] ?: 0f) * stepX - centerShift
                    val py = (layout.depth[parentId] ?: 0) * stepY
                    val cx = (layout.column[m.id] ?: 0f) * stepX - centerShift
                    val cy = (layout.depth[m.id] ?: 0) * stepY
                    val onPath = path.contains(m.id) && path.contains(parentId)
                    val color = if (onPath) scheme.primary.copy(alpha = 0.6f)
                    else scheme.outline.copy(alpha = 0.9f)
                    val width = if (onPath) 2.2f else 1.5f

                    if (kotlin.math.abs(cx - px) < 0.5f) {
                        drawLine(color, GeoOffset(px, py), GeoOffset(cx, cy), width)
                    } else {
                        val midY = (py + cy) / 2f
                        val curve = Path().apply {
                            moveTo(px, py)
                            // 两个控制点都落在纵向中线上，出来是平滑的 S 形
                            cubicTo(px, midY, cx, midY, cx, cy)
                        }
                        drawPath(curve, color, style = Stroke(width = width))
                    }
                }

                // 再画节点
                messages.forEach { m ->
                    val cx = (layout.column[m.id] ?: 0f) * stepX - centerShift
                    val cy = (layout.depth[m.id] ?: 0) * stepY
                    val onPath = path.contains(m.id)
                    val isSelected = selected?.id == m.id
                    val radius = when {
                        isSelected -> 7.dp.toPx()
                        onPath -> 5.dp.toPx()
                        else -> 3.6.dp.toPx()
                    }
                    val color = when {
                        !m.isUser && onPath -> scheme.primary
                        m.isUser && onPath -> scheme.primary
                        m.isUser -> scheme.onSurfaceVariant.copy(alpha = 0.55f)
                        else -> scheme.onSurfaceVariant.copy(alpha = 0.35f)
                    }
                    drawCircle(color = color, radius = radius, center = GeoOffset(cx, cy))
                    if (isSelected) {
                        drawCircle(
                            color = scheme.primary,
                            radius = radius + 3.dp.toPx(),
                            center = GeoOffset(cx, cy),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
            }
        }

        // 顶栏：必须避开状态栏，否则标题和返回键会被刘海/状态栏压住
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(R.drawable.ic_ds_chevron_left),
                    contentDescription = "返回",
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = "分支结构",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                // 和进入时的初始位置保持一致，别再把 pan 设成 0
                scale = 1f
                pan = GeoOffset(canvasWidth / 2f, with(density) { 110.dp.toPx() })
                selected = null
            }) { Text("复位", fontSize = 13.sp) }
        }

        // 底部选中卡片
        selected?.let { msg ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.12f))
            )
            Surface(
                color = scheme.surface,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(12.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        text = if (msg.isUser) "你问的" else "它答的",
                        fontSize = 11.5.sp,
                        color = scheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = msg.content.ifBlank { "（图片消息）" }.take(120),
                        fontSize = 13.5.sp,
                        lineHeight = 20.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        color = scheme.onSurface
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = {
                            vm.switchBranch(msg.id)
                            onClose()
                        }) { Text("切到这条", fontSize = 13.sp) }
                        TextButton(onClick = { selected = null }) { Text("关闭", fontSize = 13.sp) }
                    }
                }
            }
        }

        if (messages.isEmpty()) {
            Text(
                text = "这条对话还没有分支",
                fontSize = 13.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
