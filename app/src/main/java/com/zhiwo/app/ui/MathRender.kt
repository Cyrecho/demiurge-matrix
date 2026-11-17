package com.zhiwo.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * 把一段 TeX 画成 Bitmap。渲染失败就返回 null，让调用方退回显示原文，
 * 绝不让一条坏公式把整个界面带崩。
 */
object MathRender {

    /**
     * 按字节数限制，不是按张数。一张宽公式图可能几百 KB，
     * 按「最多 64 张」算有可能吃到上百 MB 内存。
     */
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun bitmap(tex: String, sizePx: Float, color: Int): Bitmap? {
        val key = "$tex|${sizePx.toInt()}|$color"
        cache.get(key)?.let { return it }
        return try {
            val drawable = JLatexMathDrawable.builder(tex)
                .textSize(sizePx)
                .color(color)
                .padding(6)
                .build()
            val w = drawable.intrinsicWidth.coerceAtLeast(1)
            val h = drawable.intrinsicHeight.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(Canvas(bmp))
            cache.put(key, bmp)
            bmp
        } catch (t: Throwable) {
            null
        }
    }
}
