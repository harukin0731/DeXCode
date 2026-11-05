package com.example.dexcode

import android.graphics.Rect
import android.text.method.TransformationMethod
import android.view.View

/** Always masks every character, including the last typed character on hardware keyboards. */
internal object SecretMask : TransformationMethod {
    override fun getTransformation(source: CharSequence?, view: View?): CharSequence {
        val text = source ?: return ""
        return object : CharSequence {
            override val length: Int get() = text.length
            override fun get(index: Int): Char {
                require(index in 0 until length)
                return '\u2022'
            }
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
                require(startIndex >= 0 && endIndex <= length && startIndex <= endIndex)
                return "\u2022".repeat(endIndex - startIndex)
            }
            override fun toString(): String = "\u2022".repeat(length)
        }
    }

    override fun onFocusChanged(
        view: View?, sourceText: CharSequence?, focused: Boolean,
        direction: Int, previouslyFocusedRect: Rect?,
    ) = Unit
}
