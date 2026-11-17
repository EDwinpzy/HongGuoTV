package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/** Lightweight loading and skeleton primitives shared by the catalog, search and player. */
object TvLoadingUi {
    private fun dp(context: Context,value: Number)=
        (value.toFloat()*context.resources.displayMetrics.density).toInt()

    fun dots(context: Context,color: Int=TvStyle.accentBright)=TvLoadingDots(context,color)

    fun inline(context: Context,label: String="正在加载",color: Int=TvStyle.mutedBright): LinearLayout =
        LinearLayout(context).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER
            setPadding(dp(context,12),dp(context,7),dp(context,12),dp(context,7))
            addView(
                dots(context),
                LinearLayout.LayoutParams(dp(context,38),dp(context,14)).apply { rightMargin=dp(context,9) },
            )
            addView(TextView(context).apply {
                text=label
                textSize=14f
                setTextColor(color)
                includeFontPadding=false
                typeface=TvStyle.typeface(true)
                maxLines=1
                ellipsize=TextUtils.TruncateAt.END
            })
        }

    fun player(context: Context): FrameLayout =
        FrameLayout(context).apply {
            background=TvStyle.shape(
                context,
                Color.argb(188,0,0,0),
                Color.argb(46,255,255,255),
                20,
            )
            addView(
                dots(context,Color.WHITE),
                FrameLayout.LayoutParams(dp(context,42),dp(context,15),Gravity.CENTER),
            )
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    fun overlay(context: Context): FrameLayout =
        FrameLayout(context).apply {
            setBackgroundColor(Color.argb(222,8,8,10))
            isClickable=true
            isFocusable=true
            addView(
                LinearLayout(context).apply {
                    orientation=LinearLayout.VERTICAL
                    gravity=Gravity.CENTER_HORIZONTAL
                    setPadding(dp(context,30),dp(context,22),dp(context,30),dp(context,22))
                    background=TvStyle.panel(context,22)
                    addView(
                        View(context).apply { background=TvStyle.shape(context,TvStyle.brand,Color.TRANSPARENT,2) },
                        LinearLayout.LayoutParams(dp(context,30),dp(context,3)).apply {
                            gravity=Gravity.CENTER_HORIZONTAL
                            bottomMargin=dp(context,14)
                        },
                    )
                    addView(
                        dots(context),
                        LinearLayout.LayoutParams(dp(context,42),dp(context,15)).apply {
                            gravity=Gravity.CENTER_HORIZONTAL
                        },
                    )
                    addView(TextView(context).apply {
                        text="正在加载"
                        textSize=14f
                        setTextColor(TvStyle.mutedBright)
                        includeFontPadding=false
                        typeface=TvStyle.typeface(true)
                        setPadding(0,dp(context,10),0,0)
                    })
                },
                FrameLayout.LayoutParams(-2,-2,Gravity.CENTER),
            )
        }

    fun catalogPlaceholder(
        context: Context,
        label: String,
        rows: Int=2,
        columns: Int=7,
    ): LinearLayout {
        val root=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(0,dp(context,2),0,dp(context,4))
        }
        root.addView(
            inline(context,label),
            LinearLayout.LayoutParams(-1,dp(context,42)).apply { bottomMargin=dp(context,6) },
        )
        val gap=dp(context,14)
        val width=(
            (context.resources.displayMetrics.widthPixels*.92f-gap*(columns-1))/columns
            ).toInt().coerceAtLeast(dp(context,92))
        repeat(rows.coerceAtLeast(1)) { rowIndex ->
            val line=LinearLayout(context).apply {
                orientation=LinearLayout.HORIZONTAL
                gravity=Gravity.TOP
            }
            repeat(columns) { columnIndex ->
                line.addView(
                    skeletonCard(context,width),
                    LinearLayout.LayoutParams(width,-2).apply {
                        if(columnIndex<columns-1) rightMargin=gap
                    },
                )
            }
            root.addView(
                line,
                LinearLayout.LayoutParams(-1,-2).apply {
                    if(rowIndex<rows-1) bottomMargin=dp(context,10)
                },
            )
        }
        root.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        return root
    }

    fun detailPlaceholder(context: Context,label: String="正在加载剧集"): LinearLayout {
        val root=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        root.addView(
            inline(context,label),
            LinearLayout.LayoutParams(-1,dp(context,42)).apply { bottomMargin=dp(context,4) },
        )
        val hero=LinearLayout(context).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.TOP
            setPadding(dp(context,18),dp(context,14),dp(context,18),dp(context,14))
            background=TvStyle.panel(context,20)
        }
        hero.addView(
            block(context,dp(context,128),(dp(context,128)/TvStyle.POSTER_ASPECT).toInt(),16),
            LinearLayout.LayoutParams(dp(context,128),(dp(context,128)/TvStyle.POSTER_ASPECT).toInt()).apply {
                rightMargin=dp(context,18)
            },
        )
        val copy=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        copy.addView(block(context,dp(context,300),dp(context,27),7))
        copy.addView(
            block(context,dp(context,180),dp(context,13),5),
            LinearLayout.LayoutParams(dp(context,180),dp(context,13)).apply { topMargin=dp(context,10) },
        )
        copy.addView(
            block(context,dp(context,440),dp(context,11),5),
            LinearLayout.LayoutParams(dp(context,440),dp(context,11)).apply { topMargin=dp(context,13) },
        )
        copy.addView(
            block(context,dp(context,360),dp(context,11),5),
            LinearLayout.LayoutParams(dp(context,360),dp(context,11)).apply { topMargin=dp(context,7) },
        )
        val actions=LinearLayout(context).apply {
            orientation=LinearLayout.HORIZONTAL
            setPadding(0,dp(context,12),0,0)
        }
        actions.addView(block(context,dp(context,120),dp(context,38),12).apply {
            layoutParams=LinearLayout.LayoutParams(dp(context,120),dp(context,38)).apply { rightMargin=dp(context,8) }
        })
        actions.addView(block(context,dp(context,86),dp(context,38),12))
        copy.addView(actions,LinearLayout.LayoutParams(-1,-2))
        hero.addView(copy,LinearLayout.LayoutParams(0,-2,1f))
        root.addView(hero,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(context,22) })

        root.addView(
            block(context,dp(context,64),dp(context,19),5),
            LinearLayout.LayoutParams(dp(context,64),dp(context,19)).apply { bottomMargin=dp(context,10) },
        )
        repeat(3) { rowIndex ->
            val line=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL }
            repeat(10) { columnIndex ->
                line.addView(
                    block(context,1,dp(context,34),10),
                    LinearLayout.LayoutParams(0,dp(context,34),1f).apply {
                        if(columnIndex<9) rightMargin=dp(context,6)
                    },
                )
            }
            root.addView(
                line,
                LinearLayout.LayoutParams(-1,dp(context,34)).apply {
                    if(rowIndex<2) bottomMargin=dp(context,5)
                },
            )
        }
        root.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        return root
    }

    private fun skeletonCard(context: Context,width: Int): LinearLayout {
        val card=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(context,3),dp(context,3),dp(context,3),dp(context,7))
        }
        val artWidth=width-dp(context,6)
        card.addView(
            block(context,artWidth,(artWidth/TvStyle.POSTER_ASPECT).toInt(),14),
            LinearLayout.LayoutParams(artWidth,(artWidth/TvStyle.POSTER_ASPECT).toInt()),
        )
        card.addView(
            block(context,(width*.82f).toInt(),dp(context,11),5),
            LinearLayout.LayoutParams((width*.82f).toInt(),dp(context,11)).apply { topMargin=dp(context,7) },
        )
        card.addView(
            block(context,(width*.54f).toInt(),dp(context,8),4),
            LinearLayout.LayoutParams((width*.54f).toInt(),dp(context,8)).apply { topMargin=dp(context,5) },
        )
        return card
    }

    private fun block(context: Context,width: Int,height: Int,radius: Int): View =
        View(context).apply {
            background=TvStyle.gradient(
                context,
                Color.rgb(52,52,56),
                Color.rgb(35,35,39),
                Color.argb(24,255,255,255),
                radius,
            )
            layoutParams=LinearLayout.LayoutParams(width.coerceAtLeast(1),height.coerceAtLeast(1))
        }
}

/** Three softly pulsing dots; cheaper and less obtrusive than the platform spinner. */
class TvLoadingDots(context: Context,private val dotColor: Int=TvStyle.accentBright): View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var startedAt=SystemClock.uptimeMillis()

    private fun dp(value: Float)=value*resources.displayMetrics.density

    init {
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription="正在加载"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startedAt=SystemClock.uptimeMillis()
    }

    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(38f).toInt(),widthMeasureSpec),
            resolveSize(dp(14f).toInt(),heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val width=width.toFloat()
        val height=height.toFloat()
        if(width<=0f || height<=0f) return
        val radius=min(dp(2.8f),height*.22f)
        val spacing=min(width/3.6f,radius*3.2f)
        val centerX=width/2f
        val centerY=height/2f
        val cycleMs=980f
        val elapsed=(SystemClock.uptimeMillis()-startedAt).toFloat()
        paint.color=dotColor
        repeat(3) { index ->
            val offset=(elapsed-index*145f)%cycleMs
            val phase=(offset/cycleMs)*2f*PI
            val wave=((cos(phase-PI)+1f)/2f).toFloat()
            paint.alpha=(72+183*wave).toInt().coerceIn(0,255)
            canvas.drawCircle(centerX+(index-1)*spacing,centerY,radius,paint)
        }
        if(isAttachedToWindow) postInvalidateOnAnimation()
    }
}
