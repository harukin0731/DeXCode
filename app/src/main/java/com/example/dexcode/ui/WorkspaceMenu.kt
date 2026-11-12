package com.example.dexcode.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.dexcode.R
import com.example.dexcode.util.dp

/** A single, scrollable workspace surface; every row owns one explicit action. */
class WorkspaceMenu(private val activity: AppCompatActivity, private val dark: Boolean) {
    data class Action(val label: String, val icon: HeaderIcon, val hint: String? = null,
        val enabled: Boolean = true, val checked: (() -> Boolean)? = null,
        val selected: Boolean = false, val destructive: Boolean = false, val closeOnClick: Boolean = true, val run: () -> Unit)
    data class Section(val title: String, val actions: List<Action>)
    private val ink = if (dark) Color.rgb(233,237,245) else Color.rgb(30,40,57)
    private val muted = if (dark) Color.rgb(151,163,183) else Color.rgb(97,111,132)
    private val surface = if (dark) Color.rgb(28,33,43) else Color.rgb(250,252,255)
    private val line = if (dark) Color.rgb(53,63,80) else Color.rgb(221,228,238)
    private val accent = if (dark) Color.rgb(145,187,255) else Color.rgb(40,94,194)
    private fun shape(color: Int, border: Int = Color.TRANSPARENT, radius: Int = 12) = GradientDrawable().apply {
        setColor(color); cornerRadius = activity.dp(radius).toFloat(); setStroke(activity.dp(1),border)
    }
    private fun text(label: String, size: Float, color: Int) = TextView(activity).apply {
        text=label; textSize=size; setTextColor(color); maxLines=1; ellipsize=TextUtils.TruncateAt.END
    }
    fun show(anchor: View, availableWidth: Int, availableHeight: Int, title: String, subtitle: String,
             quick: List<Action>, sections: List<Section>) {
        val compact=availableHeight<activity.dp(420)
        val content=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL; setPadding(activity.dp(12),activity.dp(12),activity.dp(12),activity.dp(12))
            background=shape(surface,line,16)
        }
        val popup=PopupWindow(content, minOf(activity.dp(368), (availableWidth-activity.dp(16)).coerceAtLeast(activity.dp(160))), -2, true).apply {
            elevation=activity.dp(16).toFloat(); isOutsideTouchable=true
            setBackgroundDrawable(shape(surface,line,16))
            inputMethodMode=PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
        content.addView(text(activity.getString(R.string.header_workspace_label),10f,muted).apply {
            visibility=if(compact)View.GONE else View.VISIBLE
            letterSpacing=.12f; setTypeface(null,Typeface.BOLD); setPadding(activity.dp(8),activity.dp(2),0,activity.dp(6))
        })
        content.addView(text(title,16f,ink).apply {setTypeface(null,Typeface.BOLD);setPadding(activity.dp(8),0,activity.dp(8),activity.dp(4))})
        content.addView(text(subtitle,11f,muted).apply {visibility=if(compact)View.GONE else View.VISIBLE;setPadding(activity.dp(8),0,activity.dp(8),activity.dp(12))})
        fun activate(action: Action, update: () -> Unit = {}) {
            if(action.closeOnClick) popup.dismiss()
            action.run(); update()
        }
        val shortcuts=LinearLayout(activity)
        quick.forEachIndexed { index, action ->
            val tile=LinearLayout(activity).apply {
                orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_VERTICAL
                setPadding(activity.dp(12),activity.dp(if(compact)6 else 10),activity.dp(12),activity.dp(if(compact)6 else 10))
                background=RippleDrawable(ColorStateList.valueOf(0x227faaff),shape(if(dark) Color.rgb(39,50,69) else Color.rgb(232,240,254),radius=10),null)
                isEnabled=action.enabled; alpha=if(action.enabled)1f else .4f
                contentDescription=action.label; tooltipText=action.hint
                setOnClickListener{activate(action)}
            }
            tile.addView(ImageView(activity).apply {setImageDrawable(HeaderIconDrawable(action.icon,accent,activity.dp(19)))},LinearLayout.LayoutParams(activity.dp(20),activity.dp(20)))
            tile.addView(text(action.label,12f,ink).apply {setTypeface(null,Typeface.BOLD);setPadding(0,activity.dp(if(compact)4 else 7),0,0)})
            if(!compact)action.hint?.let{tile.addView(text(it,10f,muted).apply{setPadding(0,activity.dp(3),0,0)})}
            shortcuts.addView(tile,LinearLayout.LayoutParams(0,activity.dp(if(compact)56 else 88),1f).apply {if(index>0)leftMargin=activity.dp(8)})
        }
        content.addView(shortcuts)
        val list=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
        sections.filter{it.actions.isNotEmpty()}.forEach { section ->
            list.addView(View(activity).apply{setBackgroundColor(line)},LinearLayout.LayoutParams(-1,activity.dp(1)).apply{topMargin=activity.dp(12);bottomMargin=activity.dp(10)})
            list.addView(text(section.title,10f,muted).apply{letterSpacing=.08f;setTypeface(null,Typeface.BOLD);setPadding(activity.dp(8),0,0,activity.dp(6));androidx.core.view.ViewCompat.setAccessibilityHeading(this,true)})
            section.actions.forEach { action ->
                val row=LinearLayout(activity).apply {
                    gravity=Gravity.CENTER_VERTICAL; setPadding(activity.dp(8),0,activity.dp(8),0)
                    minimumHeight=activity.dp(42); isEnabled=action.enabled; alpha=if(action.enabled)1f else .4f
                    background=RippleDrawable(ColorStateList.valueOf(0x227faaff),shape(Color.TRANSPARENT,radius=8),null)
                    contentDescription=action.label; tooltipText=action.hint
                }
                val color=if(action.destructive) {if(dark) Color.rgb(255,151,151) else Color.rgb(178,48,59)} else ink
                row.addView(ImageView(activity).apply{setImageDrawable(HeaderIconDrawable(action.icon,if(action.destructive)color else muted,activity.dp(18)))},LinearLayout.LayoutParams(activity.dp(20),activity.dp(20)).apply{rightMargin=activity.dp(10)})
                row.addView(text(action.label,13f,color),LinearLayout.LayoutParams(0,-2,1f))
                val suffix=text(if(action.selected)activity.getString(R.string.header_active_workspace) else action.hint.orEmpty(),10f,if(action.selected)accent else muted).apply{maxWidth=popup.width/3;setPadding(activity.dp(8),0,0,0)}
                row.addView(suffix)
                fun update() { action.checked?.let { get ->
                    val on=get(); suffix.text=activity.getString(if(on)R.string.header_on else R.string.header_off)
                    suffix.setTextColor(if(on)accent else muted); row.isSelected=on
                    row.contentDescription=action.label+", "+suffix.text
                } }
                row.isSelected=action.selected
                row.isFocusable=true
                update(); row.setOnClickListener{activate(action){update()}}
                list.addView(row,LinearLayout.LayoutParams(-1,-2))
            }
        }
        content.addView(ScrollView(activity).apply{isFillViewport=false;addView(list)},LinearLayout.LayoutParams(-1,-2))
        val widthSpec=View.MeasureSpec.makeMeasureSpec(popup.width,View.MeasureSpec.EXACTLY)
        content.measure(widthSpec,View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        popup.height=minOf(content.measuredHeight,(availableHeight-activity.dp(16)).coerceAtLeast(activity.dp(160)))
        // Scroll only the action list; workspace identity and the two primary actions stay visible.
        val scroll=content.getChildAt(content.childCount-1)
        scroll.layoutParams=LinearLayout.LayoutParams(-1,0,1f)
        val lifecycleObserver=androidx.lifecycle.LifecycleEventObserver {_,event->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_STOP)popup.dismiss()
        }
        activity.lifecycle.addObserver(lifecycleObserver)
        popup.setOnDismissListener{activity.lifecycle.removeObserver(lifecycleObserver)}
        popup.showAsDropDown(anchor,0,activity.dp(6),Gravity.END)
    }
}
