// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.*

/** A remote-accessible iOS style sheet; selecting a number is the only action that changes episodes. */
class EpisodePanel(
    private val activity: Activity,private val count: Int,private val current: Int,
    private val locate: (Int,View,(Int)->Unit)->Unit,
    private val select: (Int)->Unit,private val closed: ()->Unit
) {
    private val dialog=Dialog(activity)
    private var group=current/20
    private var focused=current
    private fun dp(n: Int)=(n*activity.resources.displayMetrics.density).toInt()
    private fun label(value: String,size: Float=16f,color: Int=TvStyle.text)=TextView(activity).apply {
        text=value; textSize=size; setTextColor(color); includeFontPadding=false; typeface=TvStyle.typeface(false)
    }
    private fun button(value: String,active: Boolean=false,compact: Boolean=false,action: ()->Unit)=label(value,if(compact) 13f else 14.5f).apply {
        id=View.generateViewId(); gravity=Gravity.CENTER
        minHeight=dp(if(compact) TvStyle.CONTROL_COMPACT else TvStyle.CONTROL_REGULAR)
        isFocusable=true; isFocusableInTouchMode=true
        setPadding(dp(if(compact) 8 else 10),dp(if(compact) 4 else 6),dp(if(compact) 8 else 10),dp(if(compact) 4 else 6))
        TvStyle.paintChip(this,false,active)
        setOnFocusChangeListener { view,_ -> TvStyle.paintChip(view as TextView,view.hasFocus(),active) }
        setOnClickListener { action() }
    }
    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setOnDismissListener { closed() }
        render()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(.58f)
            setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
            attributes=attributes.apply { x=(activity.resources.displayMetrics.widthPixels*.04f).toInt() }
            setLayout(minOf(dp(520),(activity.resources.displayMetrics.widthPixels*.78f).toInt()),(activity.resources.displayMetrics.heightPixels*.86f).toInt())
        }
        dialog.show()
    }
    fun dismiss()=dialog.dismiss()
    private fun render() {
        val content=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(12),dp(18),dp(14))
            background=TvStyle.panel(activity,24)
        }
        content.addView(View(activity).apply {
            background=TvStyle.shape(activity,Color.argb(150,235,235,245),Color.TRANSPARENT,3)
        },LinearLayout.LayoutParams(dp(32),dp(4)).apply { gravity=Gravity.CENTER_HORIZONTAL; bottomMargin=dp(12) })
        content.addView(label("选集",23f).apply { typeface=TvStyle.titleTypeface(); letterSpacing=(-.02f) })
        content.addView(label("正在播放第 ${current+1} 集 · 共 $count 集",12.5f,TvStyle.muted).apply { setPadding(0,dp(5),0,dp(10)) })
        val actions=LinearLayout(activity)
        val close=button("关闭",compact=true) { dismiss() }
        val previous=button("‹ 上一组",compact=true) { group--; focused=group*20; render() }.apply { isEnabled=group>0; isFocusable=isEnabled; alpha=if(isEnabled) 1f else .4f }
        val next=button("下一组 ›",compact=true) { group++; focused=group*20; render() }.apply { isEnabled=(group+1)*20<count; isFocusable=isEnabled; alpha=if(isEnabled) 1f else .4f }
        lateinit var jump: TextView
        jump=button("跳转集数",compact=true) { locate(focused,jump) { target -> focused=target; group=target/20; render() } }
        val actionViews=listOf(close,previous,next,jump)
        actionViews.forEachIndexed { index,view ->
            actions.addView(view,LinearLayout.LayoutParams(0,dp(TvStyle.CONTROL_COMPACT),1f).apply { if(index<actionViews.lastIndex) rightMargin=dp(6) })
        }
        content.addView(actions)
        content.addView(label("第 ${group*20+1}—${minOf((group+1)*20,count)} 集 / 共 $count 集",12f,TvStyle.muted).apply { setPadding(dp(2),dp(8),0,dp(5)) })
        val scroll=ScrollView(activity); val grid=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }; scroll.addView(grid)
        content.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val cells=mutableListOf<TextView>()
        (group*20 until minOf((group+1)*20,count)).toList().chunked(5).forEach { indexes ->
            val row=LinearLayout(activity)
            indexes.forEach { index ->
                val cell=button("${index+1}",index==current) { select(index) }
                cell.contentDescription="第 ${index+1} 集"+(if(index==current) "，当前播放" else "")
                row.addView(cell,LinearLayout.LayoutParams(0,dp(TvStyle.CONTROL_REGULAR),1f).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) }); cells+=cell
            }
            repeat(5-indexes.size) { row.addView(Space(activity),LinearLayout.LayoutParams(0,1,1f)) }
            grid.addView(row)
        }
        fun enabledFrom(index: Int,step: Int): TextView {
            for(offset in 1..actionViews.size) {
                val candidate=actionViews[(index+step*offset+actionViews.size*2)%actionViews.size]
                if(candidate.isFocusable) return candidate
            }
            return actionViews[index]
        }
        val upTargets=listOf(close,if(previous.isFocusable) previous else close,if(next.isFocusable) next else jump,jump,jump)
        cells.forEachIndexed { i,cell ->
            cell.nextFocusLeftId=cells[if(i%5==0) i else i-1].id
            cell.nextFocusRightId=cells[if(i%5==4 || i==cells.lastIndex) i else i+1].id
            cell.nextFocusUpId=if(i>=5) cells[i-5].id else upTargets[minOf(i,upTargets.lastIndex)].id
            cell.nextFocusDownId=cells[if(i+5<cells.size) i+5 else if(i/5<cells.lastIndex/5) cells.lastIndex else i].id
        }
        actionViews.forEachIndexed { index,view ->
            view.nextFocusLeftId=enabledFrom(index,-1).id
            view.nextFocusRightId=enabledFrom(index,1).id
            view.nextFocusUpId=view.id
            view.nextFocusDownId=cells[if(index==0) 0 else minOf(4,cells.lastIndex)].id
        }
        dialog.setContentView(content)
        cells[(focused-group*20).coerceIn(0,cells.lastIndex)].requestFocus()
    }
}
