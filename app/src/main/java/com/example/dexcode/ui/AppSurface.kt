package com.example.dexcode.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.TextView
import com.example.dexcode.util.dp

class AppSurface(private val context: Context, val dark: Boolean) {
    val background=if(dark)Color.rgb(18,23,32) else Color.rgb(241,245,251)
    val card=if(dark)Color.rgb(28,35,47) else Color.WHITE
    val ink=if(dark)Color.rgb(234,239,248) else Color.rgb(30,43,63)
    val muted=if(dark)Color.rgb(157,172,194) else Color.rgb(100,116,138)
    val accent=if(dark)Color.rgb(151,193,255) else Color.rgb(37,95,195)
    val line=if(dark)Color.rgb(49,63,83) else Color.rgb(219,228,240)
    fun shape(color: Int=card, radius: Int=16, border: Boolean=true)=GradientDrawable().apply {
        setColor(color);cornerRadius=context.dp(radius).toFloat();if(border)setStroke(context.dp(1),line)
    }
    fun ripple(color: Int=card)=RippleDrawable(ColorStateList.valueOf(0x337faaff),shape(color),null)
    fun label(value: String,size: Float=14f,secondary: Boolean=false)=TextView(context).apply {
        text=value;textSize=size;setTextColor(if(secondary)muted else ink)
    }
    fun button(value: String,primary: Boolean=false,action: ()->Unit)=label(value,14f).apply {
        gravity=Gravity.CENTER;minimumHeight=context.dp(48);setPadding(context.dp(16),0,context.dp(16),0)
        setTypeface(null,Typeface.BOLD);setTextColor(if(primary){if(dark)this@AppSurface.background else Color.WHITE}else accent)
        background=if(primary)ripple(accent) else ripple(card)
        isFocusable=true;setOnClickListener{action()}
    }
}
