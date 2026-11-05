package com.example.dexcode.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.example.dexcode.R

fun copyForwardUrl(context: Context, url: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Forwarded URL", url))
    Toast.makeText(context, R.string.address_copied, Toast.LENGTH_SHORT).show()
}
