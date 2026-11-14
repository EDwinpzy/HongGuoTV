// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/** Shared native colors and shapes; no blur, bitmap backgrounds or focus animation. */
object TvStyle {
    val background=Color.rgb(8,12,18)
    val backgroundTop=Color.rgb(18,26,37)
    val surface=Color.rgb(20,27,37)
    val raised=Color.rgb(27,37,50)
    val focusSurface=Color.rgb(39,51,67)
    val accent=Color.rgb(255,111,92)
    val accentBright=Color.rgb(255,174,148)
    val accentDim=Color.rgb(63,35,31)
    val text=Color.rgb(246,248,252)
    val muted=Color.rgb(150,163,181)
    val mutedBright=Color.rgb(190,200,214)
    val outline=Color.rgb(42,55,72)
    const val POSTER_ASPECT=.7f

    fun pageBackground(context: Context)=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(backgroundTop,background,Color.rgb(6,9,14)))

    fun shape(context: Context,color: Int,border: Int=Color.TRANSPARENT,radius: Int=12,strokeDp: Float=1f)=GradientDrawable().apply {
        setColor(color); cornerRadius=radius*context.resources.displayMetrics.density
        setStroke((strokeDp*context.resources.displayMetrics.density).toInt().coerceAtLeast(1),border)
    }

    /** Shared focus treatment for buttons, chips and navigation items. */
    fun control(context: Context,focused: Boolean,selected: Boolean=false,primary: Boolean=false,radius: Int=12): GradientDrawable {
        val fill=when {
            primary -> if(focused) accentBright else accent
            focused -> focusSurface
            selected -> accentDim
            else -> surface
        }
        val border=when {
            focused -> if(primary) Color.WHITE else accentBright
            selected -> accent
            else -> outline
        }
        return shape(context,fill,border,radius,if(focused) 2f else 1f)
    }

    fun nav(context: Context,focused: Boolean,selected: Boolean)=shape(
        context,
        if(focused) text else if(selected) accentDim else Color.TRANSPARENT,
        if(focused) accent else if(selected) accent else Color.TRANSPARENT,
        12,
        if(focused) 2f else 1f
    )

    fun card(context: Context,focused: Boolean)=shape(
        context,
        if(focused) focusSurface else surface,
        if(focused) accentBright else outline,
        14,
        if(focused) 2f else 1f
    )

    fun panel(context: Context,radius: Int=16)=shape(context,surface,outline,radius,1f)

    fun chip(context: Context,accented: Boolean=false)=shape(
        context,
        if(accented) accentDim else raised,
        if(accented) accent else outline,
        10,
        1f
    )
}

/** Image containers with stable rounded clipping on every API level used by the TV build. */
class RoundedFrameLayout(context: Context,private val radiusDp: Float=12f): FrameLayout(context) {
    init {
        clipToOutline=true
        outlineProvider=object: ViewOutlineProvider() {
            override fun getOutline(view: View,outline: Outline) {
                val radius=radiusDp*resources.displayMetrics.density
                outline.setRoundRect(0,0,view.width,view.height,radius)
            }
        }
    }
}
