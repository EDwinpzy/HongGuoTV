// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * Apple TV / iOS inspired visual language for the whole client.
 *
 * The TV boxes this app targets are deliberately low-powered, so the "materials" are
 * static layered gradients rather than real blur. Shapes, spacing and the type scale do
 * the heavy lifting; focus remains a cheap drawable swap (no animations or bitmaps).
 */
object TvStyle {
    val background = Color.BLACK
    val backgroundTop = Color.rgb(24, 24, 32)
    val surface = Color.rgb(28, 28, 30)
    val raised = Color.rgb(44, 44, 46)
    val focusSurface = Color.rgb(63, 63, 66)
    val accent = Color.rgb(10, 132, 255)
    val accentBright = Color.rgb(100, 181, 255)
    val accentDim = Color.rgb(27, 55, 86)
    val brand = Color.rgb(255, 55, 95)
    val text = Color.rgb(245, 245, 247)
    val muted = Color.rgb(152, 152, 157)
    val mutedBright = Color.rgb(198, 198, 203)
    val outline = Color.rgb(56, 56, 58)
    val success = Color.rgb(48, 209, 88)
    val danger = Color.rgb(255, 69, 58)

    /** Fixed 2:3 poster ratio. Never squeeze artwork into a near-square slot. */
    const val POSTER_ASPECT = .66f

    private val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    fun typeface(emphasized: Boolean = false): Typeface = if (emphasized) medium else regular
    fun titleTypeface(): Typeface = bold

    private fun px(context: Context, value: Number): Int =
        (value.toFloat() * context.resources.displayMetrics.density).toInt()

    fun dp(context: Context, value: Number): Int = px(context, value)

    fun shape(
        context: Context,
        color: Int,
        border: Int = Color.TRANSPARENT,
        radius: Int = 18,
        strokeDp: Float = 1f,
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = px(context, radius).toFloat()
            if (border != Color.TRANSPARENT) {
                setStroke(px(context, strokeDp).coerceAtLeast(1), border)
            }
        }

    fun gradient(
        context: Context,
        top: Int,
        bottom: Int,
        border: Int = Color.TRANSPARENT,
        radius: Int = 24,
        strokeDp: Float = 1f,
    ): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            cornerRadius = px(context, radius).toFloat()
            if (border != Color.TRANSPARENT) {
                setStroke(px(context, strokeDp).coerceAtLeast(1), border)
            }
        }

    fun pageBackground(context: Context): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(backgroundTop, Color.rgb(10, 10, 13), background),
        )

    /** A translucent acrylic-like panel used by sheets, settings groups and loading cards. */
    fun panel(context: Context, radius: Int = 24): GradientDrawable =
        gradient(
            context,
            Color.argb(238, 44, 44, 46),
            Color.argb(238, 28, 28, 30),
            Color.argb(38, 255, 255, 255),
            radius,
        )

    /** The floating tab-bar material. */
    fun glassBar(context: Context): GradientDrawable =
        gradient(
            context,
            Color.argb(225, 47, 47, 50),
            Color.argb(225, 29, 29, 31),
            Color.argb(42, 255, 255, 255),
            20,
        )

    /** Shared focus treatment for dialog rows and secondary controls. */
    fun control(
        context: Context,
        focused: Boolean,
        selected: Boolean = false,
        primary: Boolean = false,
        radius: Int = 12,
    ): GradientDrawable {
        val fill = when {
            primary -> if (focused) Color.rgb(52, 155, 255) else accent
            focused -> focusSurface
            selected -> accentDim
            else -> surface
        }
        val border = when {
            focused -> Color.WHITE
            primary || selected -> accentBright
            else -> outline
        }
        return shape(context, fill, border, radius, if (focused) 2f else 1f)
    }

    fun nav(context: Context, focused: Boolean, selected: Boolean): GradientDrawable =
        when {
            focused -> shape(context, Color.WHITE, Color.TRANSPARENT, 15)
            selected -> shape(context, Color.argb(42, 255, 255, 255), Color.TRANSPARENT, 15)
            else -> shape(context, Color.TRANSPARENT, Color.TRANSPARENT, 15)
        }

    fun card(context: Context, focused: Boolean): GradientDrawable =
        if (focused) {
            gradient(
                context,
                Color.rgb(63, 63, 66),
                Color.rgb(42, 42, 45),
                Color.WHITE,
                18,
                2f,
            )
        } else {
            shape(context, Color.TRANSPARENT, Color.TRANSPARENT, 18)
        }

    /** Placeholder/border behind a poster. The caller enables clipping to this outline. */
    fun poster(context: Context, radius: Int = 18): GradientDrawable =
        gradient(
            context,
            Color.rgb(52, 52, 55),
            Color.rgb(32, 32, 35),
            Color.argb(32, 255, 255, 255),
            radius,
        )

    fun artwork(image: ImageView, radius: Int = 18) {
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.background = poster(image.context, radius)
        image.clipToOutline = true
        image.outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    fun roundedContainer(view: View, radius: Int = 18) {
        view.background = poster(view.context, radius)
        view.clipToOutline = true
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    private fun focusedChip(
        context: Context,
        focused: Boolean,
        selected: Boolean = false,
        primary: Boolean = false,
    ): GradientDrawable =
        when {
            focused -> shape(context, Color.WHITE, Color.TRANSPARENT, 16)
            selected && primary ->
                gradient(
                    context,
                    Color.rgb(23, 145, 255),
                    accent,
                    Color.argb(80, 255, 255, 255),
                    16,
                )
            selected ->
                shape(context, Color.argb(58, 10, 132, 255), Color.argb(110, 10, 132, 255), 16)
            else ->
                shape(context, Color.argb(26, 255, 255, 255), Color.argb(22, 255, 255, 255), 16)
        }

    /** Static pill used for metadata and section hints. */
    fun chip(context: Context, accented: Boolean = false): GradientDrawable =
        shape(
            context,
            if (accented) accentDim else Color.argb(24, 255, 255, 255),
            if (accented) accent else Color.argb(28, 255, 255, 255),
            14,
        )

    fun chipTextColor(focused: Boolean, selected: Boolean = false): Int =
        when {
            focused -> Color.BLACK
            selected -> Color.WHITE
            else -> text
        }

    fun paintChip(view: TextView, focused: Boolean, selected: Boolean = false) {
        view.background = focusedChip(view.context, focused, selected, selected)
        view.setTextColor(chipTextColor(focused, selected))
        view.typeface = typeface(focused || selected)
    }

    /** Top navigation uses Apple's quiet selected pill instead of a filled blue chip. */
    fun paintNav(view: TextView, focused: Boolean, selected: Boolean) {
        view.background = nav(view.context, focused, selected)
        view.setTextColor(
            when {
                focused -> Color.BLACK
                selected -> Color.WHITE
                else -> Color.argb(220, 245, 245, 247)
            },
        )
        view.typeface = typeface(focused || selected)
    }

    fun paintRow(view: TextView, focused: Boolean) {
        view.background =
            if (focused) shape(view.context, Color.WHITE, Color.TRANSPARENT, 15)
            else shape(view.context, Color.argb(20, 255, 255, 255), Color.TRANSPARENT, 15)
        view.setTextColor(if (focused) Color.BLACK else text)
    }

    fun playerShade(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, Color.argb(205, 0, 0, 0), Color.argb(244, 0, 0, 0)),
        )

    /**
     * Static "aurora" backdrop: deep graphite with restrained blue/pink blooms.
     * A static shader is cheaper than layering translucent Views on low-end boxes.
     */
    fun backdrop(): Drawable = AuroraBackdrop()

    private class AuroraBackdrop : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            val width = bounds.width().toFloat()
            val height = bounds.height().toFloat()
            if (width <= 0f || height <= 0f) return

            paint.shader =
                LinearGradient(
                    0f,
                    0f,
                    0f,
                    height,
                    intArrayOf(Color.rgb(24, 24, 32), Color.rgb(10, 10, 13), Color.BLACK),
                    floatArrayOf(0f, .42f, 1f),
                    Shader.TileMode.CLAMP,
                )
            canvas.drawRect(bounds, paint)

            paint.shader =
                RadialGradient(
                    width * .16f,
                    -height * .04f,
                    width * .62f,
                    intArrayOf(
                        Color.argb(66, 10, 132, 255),
                        Color.argb(20, 10, 132, 255),
                        Color.TRANSPARENT,
                    ),
                    floatArrayOf(0f, .48f, 1f),
                    Shader.TileMode.CLAMP,
                )
            canvas.drawRect(bounds, paint)

            paint.shader =
                RadialGradient(
                    width * .9f,
                    -height * .08f,
                    width * .55f,
                    intArrayOf(
                        Color.argb(48, 255, 55, 95),
                        Color.argb(14, 255, 55, 95),
                        Color.TRANSPARENT,
                    ),
                    floatArrayOf(0f, .5f, 1f),
                    Shader.TileMode.CLAMP,
                )
            canvas.drawRect(bounds, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in API 29; kept for minSdk 24")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}

/** Image containers with stable rounded clipping on every API level used by the TV build. */
class RoundedFrameLayout(context: Context, private val radiusDp: Float = 12f) : FrameLayout(context) {
    init {
        clipToOutline = true
        outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val radius = radiusDp * resources.displayMetrics.density
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
    }
}
