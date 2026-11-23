package com.local.ktv

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.ceil

data class SongRowState(
    val current: Boolean = false,
    val queued: Boolean = false,
    val local: Boolean = false,
    val downloading: Boolean = false,
    val failed: Boolean = false,
    val paused: Boolean = false,
    val progress: Int = 0,
)

class SongListCallbacks(
    val onPrimary: (Song) -> Unit,
    val onDownload: (Song) -> Unit,
    val onMore: (Song) -> Unit,
    val onTop: (Song) -> Unit,
    val onDelete: (Song) -> Unit,
    val onPauseResume: (Song) -> Unit,
)

/** Stateful row renderer based on the original OTT song/rank/order/download layouts. */
class SongListAdapter(
    private val context: Context,
    private val songs: List<Song>,
    private val mode: String,
    private val pageOffset: Int,
    private val previousPageFocusId: Int,
    private val nextPageFocusId: Int,
    private val stateFor: (Song) -> SongRowState,
    private val callbacks: SongListCallbacks,
    private val initialRowHeightPx: Int = 0,
    private val rowsPerPage: Int = 0,
) : BaseAdapter() {
    private val density = context.resources.displayMetrics.density
    private val columns = if (mode in SINGLE_COLUMN_MODES) 1 else 2
    private val cardIds = HashMap<String, Int>()

    /** 当前行高(px)。0 = 保持内容自然高度(滚动列表用)。 */
    var rowHeightPx: Int = initialRowHeightPx
        private set

    /**
     * 让行高铺满可用高度: 把列表可视高度按每页行数均分(分页列表专用)。
     * 超出 min/max 范围时收敛, 避免过小/过大。滚动列表(rowsPerPage<=0)忽略。
     */
    fun fitRowHeight(availableHeightPx: Int, rowGapPx: Int = 0, heightScale: Float = 1f, exactFill: Boolean = false) {
        if (rowsPerPage <= 0 || availableHeightPx <= 0) return
        val fitted = if (exactFill) {
            // 排行榜: (可视高度 - 间隔) / 每页数, 10 行连同间隔精确铺满。
            // 下限按歌名字号(16sp)推算: 单行完整显示约需 字号×1.4 + 上下 padding, 避免矮屏裁掉歌名。
            val minByText = dp((16f * 1.4f).toInt() + 12)
            ((availableHeightPx - rowGapPx * (rowsPerPage - 1)) / rowsPerPage).coerceAtLeast(minByText)
        } else {
            val minPx = dp(46)
            val maxPx = dp(160)
            // 扣掉行间 divider 占用的高度, 加间隔后各行仍能铺满可视高度、不会挤出最后一行。
            val usable = availableHeightPx - rowGapPx * (rowsPerPage - 1)
            ((usable / rowsPerPage) * heightScale).toInt().coerceIn(minPx, maxPx)
        }
        if (fitted != rowHeightPx) {
            rowHeightPx = fitted
            notifyDataSetChanged()
        }
    }

    private fun cardId(index: Int): Int = cardIds.getOrPut(songKey(songs[index])) { View.generateViewId() }

    override fun getCount(): Int = ceil(songs.size / columns.toDouble()).toInt()
    override fun getItem(position: Int): Any = position
    override fun getItemId(position: Int): Long = songKey(songs[position * columns]).hashCode().toLong()
    override fun hasStableIds(): Boolean = true

    fun matches(renderMode: String, renderPageOffset: Int): Boolean =
        mode == renderMode && pageOffset == renderPageOffset

    fun adapterPositionFor(focusMarker: String): Int? {
        // 右侧操作图标的 marker 形如 focus:song:KEY|action|N, 需剥掉 |action| 后缀才能定位到歌曲行。
        val key = focusMarker.removePrefix(FOCUS_PREFIX).substringBefore("|action|")
        val index = songs.indexOfFirst { songKey(it) == key }
        return index.takeIf { it >= 0 }?.div(columns)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = if (columns == 1) {
            createSingleRow(songs[position], position).apply {
                if (position == count - 1) nextFocusDownId = previousPageFocusId
            }
        } else {
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                repeat(2) { column ->
                    val index = position * 2 + column
                    val cell = songs.getOrNull(index)?.let {
                        createCatalogCell(it, index, column, position == count - 1)
                    } ?: View(context)
                    addView(
                        cell,
                        LinearLayout.LayoutParams(0, -1, 1f).apply {
                            // 两列网格: 左列右侧留间隔, 让相邻背景块边界清晰(右列不缩进以保持右边缘对齐)。
                            if (column == 0) marginEnd = dp(6)
                        },
                    )
                }
            }
        }
        // 分页列表: 让整行背景铺满均分行高; 滚动列表保持自然高度。
        if (rowHeightPx > 0) {
            view.layoutParams = android.widget.AbsListView.LayoutParams(
                android.widget.AbsListView.LayoutParams.MATCH_PARENT,
                rowHeightPx,
            )
        }
        return view
    }

    private fun createSingleRow(song: Song, index: Int): View = when (mode) {
        "rank" -> createRankRow(song, index)
        "ordered" -> createOrderedRow(song, index)
        "downloads" -> createDownloadRow(song, index)
        "sang" -> createSangRow(song, index)
        else -> createCatalogCell(song, index, 0, index == count - 1)
    }

    private fun createCatalogCell(song: Song, songIndex: Int, column: Int, lastRow: Boolean): View {
        val state = stateFor(song)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), 0, dp(4))
            setBackgroundResource(R.drawable.ott_bg_item)
            val info = songInfo(song, stacked = true)
            addView(info, LinearLayout.LayoutParams(0, dp(52), 1f))
            if (!state.local || state.queued) {
                addView(icon(if (state.queued) R.drawable.ic_order_song else R.drawable.ott_ic_download, null), LinearLayout.LayoutParams(dp(40), dp(40)))
            }
            addView(icon(R.drawable.ott_ic_more, null), LinearLayout.LayoutParams(dp(40), dp(40)))
            configureSongCard(this, song, songIndex)
            if (songIndex >= columns) nextFocusUpId = cardId(songIndex - columns)
            if (column == 1) nextFocusLeftId = cardId(songIndex - 1)
            if (column == 0 && songIndex + 1 < songs.size) nextFocusRightId = cardId(songIndex + 1)
            if (!lastRow && songIndex + columns < songs.size) nextFocusDownId = cardId(songIndex + columns)
            if (lastRow) {
                nextFocusDownId = if (column == 0) previousPageFocusId else nextPageFocusId
            }
        }
    }

    private fun createRankRow(song: Song, index: Int): View {
        val state = stateFor(song)
        val order = pageOffset + index + 1
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.ott_bg_item)
            val badge = if (order <= 3) {
                icon(
                    intArrayOf(R.drawable.ic_rank_order_1, R.drawable.ic_rank_order_2, R.drawable.ic_rank_order_3)[order - 1],
                    null,
                )
            } else {
                text(order.toString(), 16, Color.WHITE, Gravity.CENTER)
            }
            // 高度用 MATCH_PARENT 跟随均分行高, 行高变矮时内容自动居中、不溢出。
            addView(badge, LinearLayout.LayoutParams(dp(47), -1))
            addView(songInfo(song, stacked = false), LinearLayout.LayoutParams(0, -1, 1f))
            if (!state.local || state.queued) {
                addView(icon(if (state.queued) R.drawable.ic_order_song else R.drawable.ott_ic_download, null), LinearLayout.LayoutParams(dp(40), -1))
            }
            addView(icon(R.drawable.ott_ic_more, null), LinearLayout.LayoutParams(dp(40), -1))
            configureSongCard(this, song, index)
        }
    }

    private fun createOrderedRow(song: Song, index: Int): View {
        val state = stateFor(song)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.ott_bg_download)
            addView(
                if (state.current) icon(R.drawable.ott_ic_playing, null)
                else text((index + 1).toString(), 16, Color.WHITE, Gravity.CENTER),
                LinearLayout.LayoutParams(dp(47), dp(34)),
            )
            addView(songInfo(song, stacked = false), LinearLayout.LayoutParams(0, dp(34), 1f))
            val actions = ArrayList<View>()
            if (!state.current) {
                val top = icon(R.drawable.ic_add_to_top) { callbacks.onTop(song) }
                val delete = icon(R.drawable.ic_delete) { callbacks.onDelete(song) }
                addView(top, LinearLayout.LayoutParams(dp(30), dp(30)))
                addView(delete, LinearLayout.LayoutParams(dp(30), dp(30)))
                actions.add(top)
                actions.add(delete)
            }
            configureSongCard(this, song, index, actions)
        }
    }

    private fun createDownloadRow(song: Song, index: Int): View {
        val state = stateFor(song)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.ott_bg_download)
            addView(text((index + 1).toString(), 16, Color.WHITE, Gravity.CENTER), LinearLayout.LayoutParams(dp(47), dp(34)))
            addView(songInfo(song, stacked = false), LinearLayout.LayoutParams(0, dp(34), 1f))
            if (state.downloading) {
                addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = state.progress
                    progressDrawable = ContextCompat.getDrawable(context, R.drawable.bg_download_progress)
                }, LinearLayout.LayoutParams(dp(120), dp(8)).apply { marginEnd = dp(8) })
            }
            // 主操作图标(重试/暂停/播放)按当前状态只显示一个, 与删除图标一起纳入遥控器导航。
            val primary = when {
                state.failed || state.paused -> icon(R.drawable.ic_download_retry) { callbacks.onPauseResume(song) }
                state.downloading -> icon(R.drawable.ic_pause) { callbacks.onPauseResume(song) }
                state.local -> icon(R.drawable.ott_ic_play) { callbacks.onPrimary(song) }
                else -> null
            }
            val actions = ArrayList<View>()
            primary?.let {
                addView(it, LinearLayout.LayoutParams(dp(30), dp(30)))
                actions.add(it)
            }
            val delete = icon(R.drawable.ic_delete) { callbacks.onDelete(song) }
            addView(delete, LinearLayout.LayoutParams(dp(30), dp(30)))
            actions.add(delete)
            configureSongCard(this, song, index, actions)
        }
    }

    private fun createSangRow(song: Song, index: Int): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.ott_bg_item)
        addView(text((index + 1).toString(), 16, Color.WHITE, Gravity.CENTER), LinearLayout.LayoutParams(dp(47), dp(34)))
        addView(songInfo(song, stacked = false), LinearLayout.LayoutParams(0, dp(34), 1f))
        val order = icon(R.drawable.ic_order_song) { callbacks.onPrimary(song) }
        addView(order, LinearLayout.LayoutParams(dp(30), dp(30)))
        configureSongCard(this, song, index, listOf(order))
    }

    private fun songInfo(song: Song, stacked: Boolean): View = LinearLayout(context).apply {
        orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (stacked) {
            // 歌名：长歌名用按需跑马灯(超宽才滚，先静止 1.5 秒)；歌手名保持原样。
            addView(MarqueeTitleView(context).apply {
                text = song.title.orEmpty()
                textSize = 16f
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(text(song.singer.orEmpty(), 14, Color.rgb(193, 193, 193), Gravity.START or Gravity.CENTER_VERTICAL), LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            addView(text(song.title.orEmpty(), 16, Color.WHITE, Gravity.START or Gravity.CENTER_VERTICAL), LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(12) })
            addView(text(song.singer.orEmpty(), 16, Color.rgb(193, 193, 193), Gravity.START or Gravity.CENTER_VERTICAL), LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    private fun text(value: String, size: Int, color: Int, gravityValue: Int) = TextView(context).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        gravity = gravityValue
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun icon(resource: Int, action: (() -> Unit)?): ImageView = ImageView(context).apply {
        setImageResource(resource)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(3), dp(3), dp(3), dp(3))
        isFocusable = action != null
        isClickable = action != null
        action?.let { callback ->
            id = View.generateViewId()
            installPressAnimation(this)
            setOnClickListener { callback() }
        }
    }

    private fun configureSongCard(card: View, song: Song, songIndex: Int, actions: List<View> = emptyList()) {
        card.id = cardId(songIndex)
        val state = stateFor(song)
        val stateText = when {
            state.current -> KtvRaw.display(R.string.ui_p_108)
            state.queued -> KtvRaw.display(R.string.ui_a_0229)
            state.downloading -> KtvRaw.display(R.string.ui_p_109, state.progress)
            state.local -> KtvRaw.display(R.string.ui_p_110)
            else -> KtvRaw.display(R.string.ui_p_111)
        }
        card.setAccessibleFocus(
            FOCUS_PREFIX + songKey(song),
            KtvRaw.display(R.string.ui_p_116,
                song.title.orEmpty(), song.singer.orEmpty(), stateText),
        )
        installPressAnimation(card)
        card.setOnClickListener { callbacks.onPrimary(song) }
        card.setOnLongClickListener { callbacks.onMore(song); true }
        val group = card as? ViewGroup
        if (actions.isNotEmpty() && group != null) {
            // 有操作图标(已点/下载/已唱): 行自身优先聚焦, 并给图标装遥控器左右导航,
            // 让"暂停/恢复下载""删除"等图标能被遥控器选中(对齐 ori)。
            installRowActionNavigation(group, song, songIndex, actions)
        } else {
            // 无操作图标(目录网格/排行榜): 整行锁成一个焦点, 子 View 不参与聚焦。
            group?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    }

    /**
     * 单列歌曲行的遥控器导航(移植 ori 的 installSingleNavigation):
     * 行自身先聚焦; 按右键进入第一个操作图标, 图标间左右移动, 上下键在行间移动。
     * 每个图标带 |action|N 后缀的 marker, 焦点锚定恢复时由 adapterPositionFor 剥后缀定位到行。
     */
    private fun installRowActionNavigation(row: ViewGroup, song: Song, index: Int, actions: List<View>) {
        row.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        fun visibleActions() = actions.filter { it.visibility == View.VISIBLE && it.isEnabled }
        fun vertical(key: Int): Boolean {
            val next = index + if (key == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
            if (next !in songs.indices) return false
            val list = row.parent as? android.widget.ListView ?: return false
            val target = list.getChildAt(next - list.firstVisiblePosition)
            if (target?.requestFocus() != true) {
                list.setSelectionFromTop(next, if (next > index) row.height else 0)
                list.post { list.getChildAt(next - list.firstVisiblePosition)?.requestFocus() }
            }
            return true
        }
        row.setOnKeyListener { _, key, event ->
            when (key) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val target = visibleActions().firstOrNull()
                    if (event.action == KeyEvent.ACTION_DOWN) target?.requestFocus() == true else target != null
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                    if (event.action == KeyEvent.ACTION_DOWN) vertical(key) else true
                else -> false
            }
        }
        actions.forEachIndexed { actionIndex, button ->
            button.setAccessibleFocus(
                FOCUS_PREFIX + songKey(song) + "|action|" + actionIndex,
                KtvRaw.display(R.string.ui_a_0877, song.title.orEmpty()),
            )
            button.setOnKeyListener { _, key, event ->
                when (key) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            val visible = visibleActions()
                            val at = visible.indexOf(button)
                            val target = if (key == KeyEvent.KEYCODE_DPAD_LEFT)
                                visible.getOrNull(at - 1) ?: row
                            else visible.getOrNull(at + 1) ?: button
                            target.requestFocus()
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                        if (event.action == KeyEvent.ACTION_DOWN) vertical(key) else true
                    else -> false
                }
            }
        }
    }

    private fun installPressAnimation(view: View) {
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        view.isClickable = true
        TvFocusStyler.install(view)
        view.setOnTouchListener { target, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> target.animate().scaleX(0.96f).scaleY(0.96f).alpha(0.82f).setDuration(80L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    target.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120L).start()
            }
            false
        }
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    companion object {
        const val FOCUS_PREFIX = "focus:song:"
        private val SINGLE_COLUMN_MODES = setOf("rank", "ordered", "downloads", "sang")

        private fun songKey(song: Song): String =
            (song.id ?: song.dbId ?: song.filename ?: "${song.title}|${song.singer}").toString()
    }
}
