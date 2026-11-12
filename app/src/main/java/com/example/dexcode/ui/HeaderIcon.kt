package com.example.dexcode.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Small stroke icons independent of font glyph coverage or baseline. */
enum class HeaderIcon { CONNECTIONS, WORKSPACES, ADD, SEARCH, BACK, FORWARD, SIDEBAR_LEFT, PANEL, SIDEBAR_RIGHT, SELECT_TEXT, MORE, STATUS, COMMAND, TERMINAL, PORT, RELOAD, SETTINGS, CLOSE, COPY, WINDOW, THEME, INFO, KEYBOARD, SAVE, UNDO, REDO, CURSOR, UP, DOWN }

class HeaderIconDrawable(private val icon: HeaderIcon, private val color: Int, private val pixels: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.7f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun getIntrinsicWidth() = pixels
    override fun getIntrinsicHeight() = pixels
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        paint.color = color; paint.style = Paint.Style.STROKE
        fun line(x: Float, y: Float, xx: Float, yy: Float) = canvas.drawLine(x, y, xx, yy, paint)
        fun path(vararg points: Float) {
            val p = Path(); p.moveTo(points[0], points[1]); var i = 2
            while (i < points.size) { p.lineTo(points[i], points[i + 1]); i += 2 }
            canvas.drawPath(p, paint)
        }
        when (icon) {
            HeaderIcon.CONNECTIONS -> for (x in listOf(4f, 14f)) for (y in listOf(4f, 14f)) canvas.drawRoundRect(RectF(x,y,x+6,y+6),1f,1f,paint)
            HeaderIcon.WORKSPACES -> path(6f,9f,12f,15f,18f,9f)
            HeaderIcon.ADD -> { line(5f,12f,19f,12f); line(12f,5f,12f,19f) }
            HeaderIcon.SEARCH -> { canvas.drawCircle(10f,10f,6f,paint); line(15f,15f,21f,21f) }
            HeaderIcon.BACK -> { path(11f,5f,4f,12f,11f,19f); line(4f,12f,21f,12f) }
            HeaderIcon.FORWARD -> { path(13f,5f,20f,12f,13f,19f); line(3f,12f,20f,12f) }
            HeaderIcon.SIDEBAR_LEFT, HeaderIcon.PANEL, HeaderIcon.SIDEBAR_RIGHT -> {
                canvas.drawRoundRect(RectF(3f,4f,21f,20f),2f,2f,paint)
                when(icon) { HeaderIcon.SIDEBAR_LEFT -> line(9f,4f,9f,20f); HeaderIcon.SIDEBAR_RIGHT -> line(15f,4f,15f,20f); else -> line(3f,14f,21f,14f) }
            }
            HeaderIcon.SELECT_TEXT -> { line(10f,4f,10f,20f); line(6f,4f,14f,4f); line(6f,20f,14f,20f); path(18f,7f,21f,7f,21f,17f,18f,17f) }
            HeaderIcon.MORE -> { paint.style = Paint.Style.FILL; for(x in listOf(5f,12f,19f)) canvas.drawCircle(x,12f,1.6f,paint) }
            HeaderIcon.KEYBOARD -> {
                canvas.drawRoundRect(RectF(2f,5f,22f,19f),2f,2f,paint)
                for(x in listOf(6f,10f,14f,18f)) { line(x,9f,x+.1f,9f); line(x,12f,x+.1f,12f) }
                line(7f,16f,17f,16f)
            }
            HeaderIcon.SAVE -> {path(4f,3f,17f,3f,21f,7f,21f,21f,3f,21f,3f,3f,4f,3f);path(7f,3f,7f,10f,17f,10f,17f,3f);canvas.drawRect(7f,14f,17f,21f,paint)}
            HeaderIcon.UNDO -> {path(9f,5f,3f,10f,9f,15f);canvas.drawArc(RectF(3f,9f,21f,21f),180f,200f,false,paint)}
            HeaderIcon.REDO -> {path(15f,5f,21f,10f,15f,15f);canvas.drawArc(RectF(3f,9f,21f,21f),0f,-200f,false,paint)}
            HeaderIcon.CURSOR -> {line(3f,12f,21f,12f);line(12f,3f,12f,21f);path(8f,7f,12f,3f,16f,7f);path(8f,17f,12f,21f,16f,17f);path(7f,8f,3f,12f,7f,16f);path(17f,8f,21f,12f,17f,16f)}
            HeaderIcon.UP -> {path(5f,11f,12f,4f,19f,11f);line(12f,4f,12f,21f)}
            HeaderIcon.DOWN -> {path(5f,13f,12f,20f,19f,13f);line(12f,3f,12f,20f)}
            HeaderIcon.COMMAND -> { path(4f,7f,9f,12f,4f,17f); line(12f,17f,20f,17f) }
            HeaderIcon.TERMINAL -> { canvas.drawRoundRect(RectF(2f,4f,22f,20f),2f,2f,paint); path(6f,9f,10f,12f,6f,15f); line(13f,16f,18f,16f) }
            HeaderIcon.PORT -> { canvas.drawRoundRect(RectF(3f,4f,21f,10f),2f,2f,paint); canvas.drawRoundRect(RectF(3f,14f,21f,20f),2f,2f,paint); line(7f,7f,7.1f,7f); line(7f,17f,7.1f,17f) }
            HeaderIcon.RELOAD -> { canvas.drawArc(RectF(4f,4f,20f,20f),-45f,285f,false,paint); path(20f,4f,20f,10f,14f,10f) }
            HeaderIcon.SETTINGS -> { canvas.drawCircle(12f,12f,4f,paint); for(i in 0..7){val a=i*Math.PI/4;line((12+7*kotlin.math.cos(a)).toFloat(),(12+7*kotlin.math.sin(a)).toFloat(),(12+10*kotlin.math.cos(a)).toFloat(),(12+10*kotlin.math.sin(a)).toFloat())} }
            HeaderIcon.CLOSE -> {line(6f,6f,18f,18f);line(6f,18f,18f,6f)}
            HeaderIcon.COPY -> {canvas.drawRoundRect(RectF(8f,8f,21f,21f),2f,2f,paint);path(15f,4f,4f,4f,4f,15f)}
            HeaderIcon.WINDOW -> {canvas.drawRoundRect(RectF(3f,4f,21f,20f),2f,2f,paint);line(3f,9f,21f,9f)}
            HeaderIcon.THEME -> {canvas.drawCircle(12f,12f,8f,paint);paint.style=Paint.Style.FILL;canvas.drawArc(RectF(4f,4f,20f,20f),90f,180f,false,paint)}
            HeaderIcon.INFO -> {canvas.drawCircle(12f,12f,9f,paint);line(12f,11f,12f,17f);line(12f,7f,12.1f,7f)}
            HeaderIcon.STATUS -> { paint.style = Paint.Style.FILL; canvas.drawCircle(12f,12f,4f,paint) }
        }
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
