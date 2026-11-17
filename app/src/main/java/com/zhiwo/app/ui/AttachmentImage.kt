package com.zhiwo.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** 按目标边长降采样解码，避免一张 4000px 的图直接把内存顶爆。 */
private fun decodeScaled(path: String, maxEdge: Int): Bitmap? {
    if (!File(path).exists()) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeFile(path, opts)
    } catch (e: Throwable) {
        null
    }
}

/**
 * 消息附件里的图片。
 * 文件没了（比如换了手机、重装后只恢复了数据库）就给个明确的占位，
 * 而不是留一块莫名其妙的空白让人以为界面坏了。
 */
@Composable
fun AttachmentImage(path: String, modifier: Modifier = Modifier) {
    val bitmap = remember(path) { decodeScaled(path, 1080) }

    if (bitmap == null) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "图片文件已丢失",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val ratio = (bitmap.width.toFloat() / bitmap.height.toFloat()).coerceIn(0.55f, 1.9f)
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "图片",
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
    )
}
