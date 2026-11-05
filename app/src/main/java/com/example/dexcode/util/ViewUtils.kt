package com.example.dexcode.util

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun TextView.primaryTextStyle() {
    setTextColor(Color.rgb(32, 34, 39))
    textSize = 16f
}

fun TextView.secondaryTextStyle() {
    setTextColor(Color.rgb(100, 104, 115))
    textSize = 13f
}

fun View.cardBackground(): GradientDrawable = GradientDrawable().apply {
    setColor(Color.WHITE)
    cornerRadius = context.dp(12).toFloat()
    setStroke(context.dp(1), Color.rgb(224, 226, 232))
}
