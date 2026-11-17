// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import com.hongguotv.core.DanmakuPrefs
import com.hongguotv.core.PlaybackQuality
import com.hongguotv.core.PlaybackSpeed
import com.hongguotv.core.VideoFrameMode

enum class PlayerSidebarTab { EPISODES, SETTINGS }

data class PlayerSidebarState(
    val title: String,
    val cover: String?,
    val episodeCount: Int,
    val current: Int,
    val speed: Float,
    val quality: Int,
    val actualQuality: String,
    val frameMode: VideoFrameMode,
    val danmaku: Boolean,
    val danmakuSize: Int,
    val danmakuSpeed: Int,
    val danmakuRows: Int,
    val danmakuAlpha: Int,
    val sleepLabel: String,
)

/**
 * Full-height player sidebar modelled after video-pro's ClassicMorePanel. Selection and
 * playback settings share one focus-safe surface instead of separate dialogs.
 */
class PlayerSidebar(
    private val activity: Activity,
    private var state: PlayerSidebarState,
    private val loadCover: (ImageView, String) -> Unit,
    private val locate: (Int, View, (Int) -> Unit) -> Unit,
    private val selectEpisode: (Int) -> Unit,
    private val setSpeed: (Float) -> Unit,
    private val setQuality: (Int) -> Unit,
    private val setFrameMode: (VideoFrameMode) -> Unit,
    private val setDanmaku: (Boolean) -> Unit,
    private val setDanmakuSize: (Int) -> Unit,
    private val setDanmakuSpeed: (Int) -> Unit,
    private val setDanmakuRows: (Int) -> Unit,
    private val setDanmakuAlpha: (Int) -> Unit,
    private val openSleep: (View) -> Unit,
    private val restart: () -> Unit,
    private val resetCapabilities: () -> Unit,
    private val closed: () -> Unit,
) {
    private val dialog = Dialog(activity)
    private val accent = TvStyle.accent
    private val accentSoft = TvStyle.accentBright
    private val muted = TvStyle.muted
    private val chipColor = Color.rgb(36, 36, 40)
    private val chipActive = TvStyle.accentDim
    private val groupSize = 60
    private var page = state.current / groupSize
    private var tab = PlayerSidebarTab.EPISODES
    private var firstFocus: View? = null
    private var focusEpisode: Int? = null

    private fun dp(n: Number) = (n.toFloat() * activity.resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float, color: Int = TvStyle.text) =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            typeface = TvStyle.typeface(false)
            letterSpacing = .005f
        }

    private fun panelBackground() =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.rgb(38, 38, 42), Color.rgb(22, 22, 25), Color.rgb(14, 14, 16)),
        ).apply {
            cornerRadii = floatArrayOf(
                dp(28).toFloat(), dp(28).toFloat(),
                0f, 0f,
                0f, 0f,
                dp(28).toFloat(), dp(28).toFloat(),
            )
            setStroke(dp(1).coerceAtLeast(1), Color.argb(68, 255, 255, 255))
        }

    private fun chipBackground(focused: Boolean, selected: Boolean, currentEpisode: Boolean = false) =
        TvStyle.shape(
            activity,
            when {
                focused -> Color.WHITE
                currentEpisode -> accent
                selected -> chipActive
                else -> chipColor
            },
            when {
                focused -> Color.WHITE
                currentEpisode -> TvStyle.accentBright
                selected -> TvStyle.accent
                else -> TvStyle.outline
            },
            11,
            if (focused || currentEpisode) 2f else 1f,
        )

    private fun chipTextColor(focused: Boolean, selected: Boolean, currentEpisode: Boolean = false) =
        when {
            focused -> Color.BLACK
            currentEpisode -> Color.WHITE
            selected -> accentSoft
            else -> TvStyle.text
        }

    private fun chip(
        value: String,
        selected: Boolean = false,
        currentEpisode: Boolean = false,
        compact: Boolean = false,
        action: (() -> Unit)? = null,
    ) = label(value, if (compact) 12.5f else 13.5f, chipTextColor(false, selected, currentEpisode)).apply {
        id = View.generateViewId()
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        minHeight = dp(if (compact) 30 else 34)
        setPadding(dp(9), dp(4), dp(9), dp(4))
        background = chipBackground(false, selected, currentEpisode)
        setOnFocusChangeListener { view, focused ->
            (view as TextView).apply {
                background = chipBackground(focused, selected, currentEpisode)
                setTextColor(chipTextColor(focused, selected, currentEpisode))
                typeface = TvStyle.typeface(focused || selected)
            }
        }
        action?.let { setOnClickListener { it() } }
    }

    fun show(initial: PlayerSidebarTab = PlayerSidebarTab.EPISODES) {
        tab = initial
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setOnDismissListener { closed() }
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(.55f)
            setGravity(Gravity.END or Gravity.TOP)
            attributes = attributes.apply {
                width = minOf(dp(560), (activity.resources.displayMetrics.widthPixels * .82f).toInt())
                height = WindowManager.LayoutParams.MATCH_PARENT
            }
        }
        render()
        dialog.show()
        dialog.window?.setLayout(
            minOf(dp(560), (activity.resources.displayMetrics.widthPixels * .82f).toInt()),
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        dialog.window?.decorView?.post { firstFocus?.requestFocus() }
    }

    fun dismiss() = dialog.dismiss()

    private fun render() {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(22), dp(28), dp(22))
            background = panelBackground()
        }
        panel.addView(buildHeader())
        val tabs = buildTabs()
        panel.addView(tabs, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(18) })
        val content = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        panel.addView(content, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(14) })
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(body, LinearLayout.LayoutParams(-1, -2))
        val rows = if (tab == PlayerSidebarTab.EPISODES) buildEpisodes(body) else buildSettings(body)
        wireNavigation(tabs, rows)
        dialog.setContentView(panel)
    }

    private fun buildHeader(): LinearLayout {
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            minimumHeight = dp(118)
        }
        val art = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = TvStyle.poster(activity, 9)
            clipToOutline = true
            outlineProvider = ViewOutlineProviderCompat(dp(9).toFloat())
            contentDescription = "剧集封面"
        }
        header.addView(art, LinearLayout.LayoutParams(dp(84), dp(112)).apply { rightMargin = dp(16) })
        val copy = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(
            label(state.title, 24f).apply {
                typeface = TvStyle.titleTypeface()
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                letterSpacing = -.02f
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        copy.addView(
            label(
                "第 ${state.current + 1} 集 / 共 ${state.episodeCount} 集 · 实际 ${state.actualQuality}",
                12f,
                muted,
            ).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(7), 0, 0)
            },
        )
        copy.addView(
            label("清晰度上限 ${state.quality}P · 倍速 ${PlaybackSpeed.label(state.speed)}", 11.5f, accentSoft).apply {
                setPadding(0, dp(6), 0, 0)
            },
        )
        copy.addView(
            label("遥控器：左右快进/快退 · 上下切集 · MENU 打开侧栏", 10.5f, muted).apply {
                maxLines = 2
                setPadding(0, dp(8), 0, 0)
            },
        )
        header.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        state.cover?.takeIf { it.isNotBlank() }?.let { loadCover(art, it) }
        return header
    }

    private fun buildTabs(): LinearLayout {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = TvStyle.shape(activity, TvStyle.raised, TvStyle.outline, 11)
        }
        val items = listOf(PlayerSidebarTab.EPISODES to "选集", PlayerSidebarTab.SETTINGS to "播放设置")
        val views = items.map { (target, name) ->
            chip(name, selected = tab == target, compact = true) {
                if (tab != target) {
                    tab = target
                    render()
                    dialog.window?.decorView?.post { firstFocus?.requestFocus() }
                }
            }.apply {
                minHeight = dp(32)
                setPadding(dp(8), 0, dp(8), 0)
            }
        }
        views.forEachIndexed { index, view ->
            bar.addView(view, LinearLayout.LayoutParams(0, dp(32), 1f).apply {
                if (index > 0) leftMargin = dp(3)
            })
        }
        views.forEachIndexed { index, view ->
            view.nextFocusLeftId = views[(index - 1 + views.size) % views.size].id
            view.nextFocusRightId = views[(index + 1) % views.size].id
            view.nextFocusUpId = view.id
        }
        return bar
    }

    private fun buildEpisodes(body: LinearLayout): List<MutableList<View>> {
        body.addView(
            label("当前第 ${state.current + 1} 集", 12f, muted).apply {
                setPadding(dp(2), 0, 0, dp(9))
            },
        )
        val pages = (0 until ((state.episodeCount + groupSize - 1) / groupSize)).toList()
        val pageRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pageViews = mutableListOf<View>()
        if (pages.size > 1) {
            pages.forEach { value ->
                val pageChip = chip(
                    "${value * groupSize + 1}-${minOf((value + 1) * groupSize, state.episodeCount)}",
                    selected = value == page,
                    compact = true,
                ) {
                    page = value
                    render()
                    dialog.window?.decorView?.post { firstFocus?.requestFocus() }
                }
                pageRow.addView(pageChip, LinearLayout.LayoutParams(0, dp(30), 1f).apply {
                    if (pageViews.isNotEmpty()) leftMargin = dp(6)
                })
                pageViews += pageChip
            }
            val jump = chip("跳转", compact = true)
            pageRow.addView(jump, LinearLayout.LayoutParams(dp(66), dp(30)).apply { leftMargin = dp(6) })
            pageViews += jump
            jump.setOnClickListener {
                locate(state.current, jump) { target ->
                    page = target / groupSize
                    focusEpisode = target
                    render()
                    dialog.window?.decorView?.post { firstFocus?.requestFocus() }
                }
            }
            body.addView(pageRow, LinearLayout.LayoutParams(-1, dp(30)).apply { bottomMargin = dp(12) })
        }
        val rows = mutableListOf<MutableList<View>>()
        val start = page * groupSize
        val end = minOf((page + 1) * groupSize, state.episodeCount)
        val targetFocus = focusEpisode ?: state.current
        (start until end).toList().chunked(5).forEach { indexes ->
            val line = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val rowViews = mutableListOf<View>()
            indexes.forEach { index ->
                val item = chip(
                    "${index + 1}",
                    currentEpisode = index == state.current,
                    compact = false,
                ) {
                    if (index == state.current) dismiss() else selectEpisode(index)
                }.apply {
                    contentDescription = "第 ${index + 1} 集" + if (index == state.current) "，当前播放" else ""
                }
                line.addView(item, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    if (rowViews.isNotEmpty()) leftMargin = dp(8)
                })
                rowViews += item
                if (index == targetFocus) firstFocus = item
            }
            repeat(5 - indexes.size) {
                line.addView(Space(activity), LinearLayout.LayoutParams(0, 1, 1f).apply {
                    if (rowViews.isNotEmpty()) leftMargin = dp(8)
                })
            }
            body.addView(line, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(8) })
            rows += rowViews
        }
        focusEpisode = null
        if (pageViews.isNotEmpty()) {
            pageViews.forEachIndexed { index, view ->
                view.nextFocusLeftId = pageViews[(index - 1 + pageViews.size) % pageViews.size].id
                view.nextFocusRightId = pageViews[(index + 1) % pageViews.size].id
            }
            rows.add(0, pageViews)
        }
        firstFocus = firstFocus ?: rows.firstOrNull()?.firstOrNull()
        return rows
    }

    private fun settingRow(
        body: LinearLayout,
        title: String,
        values: List<String>,
        selected: Int,
        onPick: (Int) -> Unit,
    ): MutableList<View> {
        val line = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(38)
            setPadding(0, dp(2), 0, dp(2))
        }
        line.addView(label(title, 12f, muted), LinearLayout.LayoutParams(dp(76), -2))
        val choices = mutableListOf<View>()
        var selectedIndex = selected
        val chipViews = values.mapIndexed { index, value ->
            chip(value, selected = index == selectedIndex, compact = true) {
                if (selectedIndex != index) {
                    selectedIndex = index
                    onPick(index)
                    if (dialog.isShowing) {
                        render()
                        dialog.window?.decorView?.post { firstFocus?.requestFocus() }
                    }
                }
            }
        }
        chipViews.forEachIndexed { index, view ->
            line.addView(view, LinearLayout.LayoutParams(0, dp(30), 1f).apply {
                if (index > 0) leftMargin = dp(5)
            })
            choices += view
        }
        body.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
        return choices
    }

    private fun buildSettings(body: LinearLayout): List<MutableList<View>> {
        val rows = mutableListOf<MutableList<View>>()
        body.addView(label("画面与播放", 11f, accentSoft).apply { setPadding(0, 0, 0, dp(7)) })
        val qualityChoices = settingRow(
            body,
            "清晰度上限",
            PlaybackQuality.options.map { "${it}P" },
            PlaybackQuality.options.indexOf(state.quality).coerceAtLeast(0),
        ) {
            val value = PlaybackQuality.options[it]
            state = state.copy(quality = value)
            setQuality(value)
        }
        rows += qualityChoices
        rows += settingRow(
            body,
            "倍速",
            PlaybackSpeed.options.map(PlaybackSpeed::label),
            PlaybackSpeed.options.indexOf(state.speed).coerceAtLeast(0),
        ) {
            val value = PlaybackSpeed.options[it]
            state = state.copy(speed = value)
            setSpeed(value)
        }
        rows += settingRow(
            body,
            "画面",
            VideoFrameMode.entries.map { it.label },
            VideoFrameMode.entries.indexOf(state.frameMode).coerceAtLeast(0),
        ) {
            val value = VideoFrameMode.entries[it]
            state = state.copy(frameMode = value)
            setFrameMode(value)
        }

        body.addView(label("弹幕", 11f, accentSoft).apply { setPadding(0, dp(13), 0, dp(7)) })
        rows += settingRow(body, "显示", listOf("关闭", "开启"), if (state.danmaku) 1 else 0) {
            state = state.copy(danmaku = it == 1)
            setDanmaku(it == 1)
        }
        rows += settingRow(
            body,
            "字号",
            DanmakuPrefs.textSizes.indices.map(DanmakuPrefs::sizeLabel),
            state.danmakuSize,
        ) {
            state = state.copy(danmakuSize = it)
            setDanmakuSize(it)
        }
        rows += settingRow(
            body,
            "速度",
            DanmakuPrefs.travelSecs.indices.map(DanmakuPrefs::speedLabel),
            state.danmakuSpeed,
        ) {
            state = state.copy(danmakuSpeed = it)
            setDanmakuSpeed(it)
        }
        rows += settingRow(
            body,
            "行数",
            DanmakuPrefs.rowCounts.indices.map(DanmakuPrefs::rowsLabel),
            state.danmakuRows,
        ) {
            state = state.copy(danmakuRows = it)
            setDanmakuRows(it)
        }
        rows += settingRow(
            body,
            "透明度",
            DanmakuPrefs.alphas.indices.map(DanmakuPrefs::alphaLabel),
            state.danmakuAlpha,
        ) {
            state = state.copy(danmakuAlpha = it)
            setDanmakuAlpha(it)
        }

        body.addView(label("观看", 11f, accentSoft).apply { setPadding(0, dp(13), 0, dp(7)) })
        val sleepRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        sleepRow.addView(label("定时停止", 12f, muted), LinearLayout.LayoutParams(dp(76), -2))
        lateinit var sleep: TextView
        sleep = chip("定时 ${state.sleepLabel}", compact = true) { openSleep(sleep) }
        sleepRow.addView(sleep, LinearLayout.LayoutParams(0, dp(30), 1f))
        body.addView(sleepRow, LinearLayout.LayoutParams(-1, dp(36)))
        rows += mutableListOf(sleep)

        val actionRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val restartChip = chip("从头播放", compact = true) { restart() }
        val resetChip = chip("重置设备能力记录", compact = true) { resetCapabilities() }
        actionRow.addView(restartChip, LinearLayout.LayoutParams(0, dp(32), 1f).apply { rightMargin = dp(6) })
        actionRow.addView(resetChip, LinearLayout.LayoutParams(0, dp(32), 1f))
        body.addView(actionRow)
        rows += mutableListOf(restartChip, resetChip)
        firstFocus = qualityChoices.getOrNull(PlaybackQuality.options.indexOf(state.quality))
            ?: rows.firstOrNull()?.firstOrNull()
        return rows
    }

    private fun wireNavigation(tabs: LinearLayout, rows: List<MutableList<View>>) {
        val tabViews = (0 until tabs.childCount).map { tabs.getChildAt(it) }
        tabViews.forEach { tabView ->
            tabView.nextFocusDownId = rows.firstOrNull()?.firstOrNull()?.id ?: tabView.id
        }
        rows.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { index, view ->
                view.nextFocusLeftId = row[(index - 1 + row.size) % row.size].id
                view.nextFocusRightId = row[(index + 1) % row.size].id
                view.nextFocusUpId =
                    if (rowIndex == 0) tabViews.firstOrNull { it.isSelected }?.id ?: tabViews.first().id
                    else rows[rowIndex - 1][index.coerceAtMost(rows[rowIndex - 1].lastIndex)].id
                view.nextFocusDownId =
                    if (rowIndex == rows.lastIndex) view.id
                    else rows[rowIndex + 1][index.coerceAtMost(rows[rowIndex + 1].lastIndex)].id
            }
        }
        tabViews.forEachIndexed { index, tabView ->
            tabView.isSelected = index == tab.ordinal
        }
    }

    private class ViewOutlineProviderCompat(private val radius: Float) : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }
}
