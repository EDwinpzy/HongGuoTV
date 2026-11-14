// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object TvDialogUi {
    private fun dp(activity: Activity,value: Int)=(value*activity.resources.displayMetrics.density).toInt()

    fun choice(
        activity: Activity,
        title: String,
        labels: List<String>,
        selected: Int=-1,
        extra: String?=null,
        onChoice: (Int)->Unit,
        onExtra: ()->Unit={}
    ): Dialog {
        var dialog: Dialog?=null
        val card=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(activity,22),dp(activity,20),dp(activity,22),dp(activity,16))
            background=TvStyle.panel(activity,18)
        }
        card.addView(TextView(activity).apply {
            text=title
            textSize=21f
            setTextColor(TvStyle.text)
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            includeFontPadding=false
            maxLines=2
            ellipsize=TextUtils.TruncateAt.END
        },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(activity,14) })

        val rows=mutableListOf<View>()
        val list=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        labels.forEachIndexed { index,value ->
            val row=LinearLayout(activity).apply {
                id=View.generateViewId()
                orientation=LinearLayout.HORIZONTAL
                gravity=Gravity.CENTER_VERTICAL
                isFocusable=true
                isFocusableInTouchMode=true
                contentDescription=value
                setPadding(dp(activity,14),dp(activity,10),dp(activity,14),dp(activity,10))
            }
            val marker=View(activity).apply {
                isFocusable=false
                isClickable=false
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                background=TvStyle.shape(activity,TvStyle.accent,Color.TRANSPARENT,2)
            }
            row.addView(marker,LinearLayout.LayoutParams(dp(activity,3),dp(activity,18)).apply { rightMargin=dp(activity,11) })
            val text=TextView(activity).apply {
                text=value
                textSize=16f
                includeFontPadding=false
                maxLines=2
                ellipsize=TextUtils.TruncateAt.END
            }
            row.addView(text,LinearLayout.LayoutParams(0,-2,1f))
            fun paint(focused: Boolean) {
                val active=index==selected
                row.background=TvStyle.control(activity,focused,active,radius=10)
                text.setTextColor(if(focused) TvStyle.text else if(active) TvStyle.accentBright else TvStyle.text)
                text.setTypeface(null,if(focused || active) Typeface.BOLD else Typeface.NORMAL)
                marker.visibility=if(active) View.VISIBLE else View.INVISIBLE
            }
            paint(false)
            row.setOnFocusChangeListener { _,focused -> paint(focused) }
            row.setOnClickListener {
                dialog?.dismiss()
                onChoice(index)
            }
            rows+=row
            list.addView(row,LinearLayout.LayoutParams(-1,dp(activity,50)).apply {
                if(index<labels.lastIndex) bottomMargin=dp(activity,6)
            })
        }

        val scroll=ScrollView(activity).apply {
            isFillViewport=false
            isVerticalScrollBarEnabled=false
            isFocusable=false
            isFocusableInTouchMode=false
            clipToPadding=false
            addView(list)
        }
        val rowHeight=dp(activity,50)
        val rowGaps=(labels.size-1).coerceAtLeast(0)*dp(activity,6)
        val desiredHeight=labels.size*rowHeight+rowGaps
        val maxHeight=(activity.resources.displayMetrics.heightPixels*.56f).toInt()
        card.addView(scroll,LinearLayout.LayoutParams(-1,desiredHeight.coerceAtMost(maxHeight)))

        val actions=LinearLayout(activity).apply {
            gravity=Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0,dp(activity,14),0,0)
        }
        fun action(label: String,click: ()->Unit): TextView {
            val view=TextView(activity).apply {
                id=View.generateViewId()
                text=label
                textSize=15f
                gravity=Gravity.CENTER
                typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                includeFontPadding=false
                isFocusable=true
                isFocusableInTouchMode=true
                setPadding(dp(activity,18),dp(activity,10),dp(activity,18),dp(activity,10))
                minHeight=dp(activity,44)
            }
            fun paint(focused: Boolean) {
                view.background=TvStyle.control(activity,focused,radius=10)
                view.setTextColor(TvStyle.text)
            }
            paint(false)
            view.setOnFocusChangeListener { _,focused -> paint(focused) }
            view.setOnClickListener { click() }
            return view
        }
        val extraButton=if(extra!=null) action(extra) { dialog?.dismiss(); onExtra() } else null
        if(extraButton!=null) actions.addView(extraButton,LinearLayout.LayoutParams(-2,-2).apply { rightMargin=dp(activity,8) })
        val cancel=action("取消") { dialog?.dismiss() }
        actions.addView(cancel,LinearLayout.LayoutParams(-2,-2))
        card.addView(actions,-1,-2)
        rows.forEachIndexed { index,row ->
            row.nextFocusUpId=if(index==0) row.id else rows[index-1].id
            row.nextFocusDownId=if(index==rows.lastIndex) cancel.id else rows[index+1].id
            row.nextFocusLeftId=row.id
            row.nextFocusRightId=row.id
        }
        cancel.nextFocusUpId=rows.lastOrNull()?.id ?: cancel.id
        cancel.nextFocusDownId=rows.lastOrNull()?.id ?: cancel.id
        cancel.nextFocusLeftId=extraButton?.id ?: cancel.id
        cancel.nextFocusRightId=cancel.id
        extraButton?.apply {
            nextFocusUpId=rows.lastOrNull()?.id ?: id
            nextFocusDownId=rows.lastOrNull()?.id ?: id
            nextFocusLeftId=id
            nextFocusRightId=cancel.id
        }

        val created=Dialog(activity).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(card)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        dialog=created
        created.setOnShowListener {
            val width=(activity.resources.displayMetrics.widthPixels*.58f).toInt().coerceAtMost(dp(activity,680))
            created.window?.setLayout(width,ViewGroup.LayoutParams.WRAP_CONTENT)
            rows.getOrNull(selected.coerceAtLeast(0))?.requestFocus()
        }
        return created
    }
}
