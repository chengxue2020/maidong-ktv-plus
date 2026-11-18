package com.local.ktv

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.util.Locale

/**
 * 内部逻辑键读取工具（与 ori 版一致）。
 *
 * 应用中大量中文字符串既会显示给用户，又用作状态判断、数据库取值、筛选参数。
 * 切换语言后，界面文案需要跟随语言，但逻辑键必须始终保持中文，
 * 否则会出现判断失效、筛选错乱、历史数据对不上等问题。
 *
 * 中文文案集中在 values-zh-rCN/strings_ui.xml、values-zh-rCN/strings_all.xml（及默认 values 兜底），
 * 英文在 values-en/ 下同名文件。显示文案用普通 getString()（跟随语言）；
 * 内部键用本对象的 [get]（固定中文）。
 */
object KtvRaw {
    @Volatile
    private var zhContext: Context? = null

    @Volatile
    private var appContext: Context? = null

    /** 在 Application.onCreate 最开头调用（大量字段初始化器在类加载期就会用到）。 */
    @JvmStatic
    fun init(app: Context) {
        appContext = app.applicationContext
    }

    private fun context(): Context {
        zhContext?.let { return it }
        val app = appContext
            ?: runCatching { KtvApplication.getInstance() }.getOrNull()
            ?: throw IllegalStateException("KtvRaw 需要 Application 已初始化")
        val config = Configuration(app.resources.configuration)
        config.setLocale(Locale.SIMPLIFIED_CHINESE)
        val ctx = app.createConfigurationContext(config)
        zhContext = ctx
        return ctx
    }

    /** 以固定简体中文读取字符串资源，用作内部逻辑键。 */
    @JvmStatic
    fun get(@StringRes id: Int): String = context().getString(id)

    @JvmStatic
    fun get(@StringRes id: Int, vararg args: Any?): String = context().getString(id, *args)

    /**
     * 以「当前界面语言」读取字符串资源，供没有 Context 的类显示文案使用。
     * 与 [get] 的区别：[get] 固定简体中文（内部键），本方法跟随语言切换（显示文案）。
     */
    @JvmStatic
    fun display(@StringRes id: Int, vararg args: Any?): String {
        val app = appContext
            ?: runCatching { KtvApplication.getInstance() }.getOrNull()
            ?: throw IllegalStateException("KtvRaw 需要 Application 已初始化")
        return if (args.isEmpty()) app.getString(id) else app.getString(id, *args)
    }

    /**
     * 曲库/歌单/榜单名称的显示层翻译：内部比较仍使用固定中文键，
     * 只在写到 TextView / 面包屑时调用本方法，避免切语言后筛选与图标匹配失效。
     */
    @JvmStatic
    fun displayName(value: String?): String {
        val raw = value ?: return ""
        if (raw.isEmpty()) return raw
        if (raw.contains(',')) {
            return raw.split(',').joinToString(", ") { displayName(it.trim()) }
        }
        val resId = when (raw) {
            // 已有资源：与 iconRes / rankIconRes 的内部键保持一致。
            get(R.string.ui_a_0069) -> R.string.ui_a_0069
            get(R.string.ui_a_0071) -> R.string.ui_a_0071
            get(R.string.ui_a_0072) -> R.string.ui_a_0072
            get(R.string.ui_a_0078) -> R.string.ui_a_0078
            get(R.string.ui_a_0079) -> R.string.ui_a_0079
            get(R.string.ui_a_0199) -> R.string.ui_a_0199
            get(R.string.ui_a_0319) -> R.string.ui_a_0319
            get(R.string.ui_a_0321) -> R.string.ui_a_0321
            get(R.string.ui_a_0480) -> R.string.ui_a_0480
            get(R.string.ui_a_0520) -> R.string.ui_a_0520
            get(R.string.ui_a_0521) -> R.string.ui_a_0521
            get(R.string.ui_a_0522) -> R.string.ui_a_0522
            get(R.string.ui_a_0523) -> R.string.ui_a_0523
            get(R.string.ui_a_0530) -> R.string.ui_a_0530
            get(R.string.ui_a_0651) -> R.string.ui_a_0651
            get(R.string.ui_a_0727) -> R.string.ui_a_0727
            get(R.string.ui_a_0728) -> R.string.ui_a_0728
            get(R.string.ui_a_0729) -> R.string.ui_a_0729
            get(R.string.ui_a_0857) -> R.string.ui_a_0857
            get(R.string.ui_a_0858) -> R.string.ui_a_0858
            get(R.string.ui_a_0860) -> R.string.ui_a_0860
            get(R.string.ui_a_0861) -> R.string.ui_a_0861
            get(R.string.ui_a_0863) -> R.string.ui_a_0863
            get(R.string.ui_a_0864) -> R.string.ui_a_0864
            get(R.string.ui_a_0865) -> R.string.ui_a_0865
            get(R.string.ui_a_0867) -> R.string.ui_a_0867
            get(R.string.ui_a_0868) -> R.string.ui_a_0868
            get(R.string.ui_a_0869) -> R.string.ui_a_0869
            get(R.string.ui_a_0870) -> R.string.ui_a_0870
            get(R.string.ui_a_0871) -> R.string.ui_a_0871
            // 本轮补充的遗漏词语。
            get(R.string.ui_p_cat_jinge) -> R.string.ui_p_cat_jinge
            get(R.string.ui_p_cat_dance) -> R.string.ui_p_cat_dance
            get(R.string.ui_p_cat_hot) -> R.string.ui_p_cat_hot
            get(R.string.ui_p_cat_rising) -> R.string.ui_p_cat_rising
            get(R.string.ui_p_cat_douyin) -> R.string.ui_p_cat_douyin
            get(R.string.ui_p_cat_new_chart) -> R.string.ui_p_cat_new_chart
            get(R.string.ui_p_cat_classmate) -> R.string.ui_p_cat_classmate
            get(R.string.ui_p_cat_love_song) -> R.string.ui_p_cat_love_song
            get(R.string.ui_p_cat_romance) -> R.string.ui_p_cat_romance
            get(R.string.ui_p_cat_love_short) -> R.string.ui_p_cat_love_short
            get(R.string.ui_p_cat_longing) -> R.string.ui_p_cat_longing
            get(R.string.ui_p_cat_golden) -> R.string.ui_p_cat_golden
            get(R.string.ui_p_cat_nostalgic_golden) -> R.string.ui_p_cat_nostalgic_golden
            get(R.string.ui_p_cat_huangmei) -> R.string.ui_p_cat_huangmei
            get(R.string.ui_p_cat_pingju) -> R.string.ui_p_cat_pingju
            get(R.string.ui_p_cat_opera_type) -> R.string.ui_p_cat_opera_type
            get(R.string.ui_p_cat_opera) -> R.string.ui_p_cat_opera
            get(R.string.ui_p_cat_mountain_song) -> R.string.ui_p_cat_mountain_song
            get(R.string.ui_p_cat_country) -> R.string.ui_p_cat_country
            get(R.string.ui_p_cat_cartoon) -> R.string.ui_p_cat_cartoon
            get(R.string.ui_p_cat_animation) -> R.string.ui_p_cat_animation
            get(R.string.ui_p_cat_nursery) -> R.string.ui_p_cat_nursery
            get(R.string.ui_p_cat_film_tv) -> R.string.ui_p_cat_film_tv
            get(R.string.ui_p_cat_movie) -> R.string.ui_p_cat_movie
            get(R.string.ui_p_cat_tv_series) -> R.string.ui_p_cat_tv_series
            get(R.string.ui_p_cat_theme) -> R.string.ui_p_cat_theme
            get(R.string.ui_p_cat_insert) -> R.string.ui_p_cat_insert
            get(R.string.ui_p_cat_rhythm) -> R.string.ui_p_cat_rhythm
            get(R.string.ui_p_cat_campus) -> R.string.ui_p_cat_campus
            get(R.string.ui_p_cat_youth) -> R.string.ui_p_cat_youth
            get(R.string.ui_p_cat_graduation) -> R.string.ui_p_cat_graduation
            get(R.string.ui_p_cat_military) -> R.string.ui_p_cat_military
            get(R.string.ui_p_cat_patriotic) -> R.string.ui_p_cat_patriotic
            get(R.string.ui_p_cat_mainstream) -> R.string.ui_p_cat_mainstream
            get(R.string.ui_p_cat_homesick) -> R.string.ui_p_cat_homesick
            get(R.string.ui_p_cat_family_love) -> R.string.ui_p_cat_family_love
            get(R.string.ui_p_cat_hometown) -> R.string.ui_p_cat_hometown
            get(R.string.ui_p_cat_mom) -> R.string.ui_p_cat_mom
            get(R.string.ui_p_cat_dad) -> R.string.ui_p_cat_dad
            get(R.string.ui_p_cat_family) -> R.string.ui_p_cat_family
            get(R.string.ui_p_cat_global) -> R.string.ui_p_cat_global
            get(R.string.ui_p_cat_singing) -> R.string.ui_p_cat_singing
            get(R.string.ui_p_cat_karaoke) -> R.string.ui_p_cat_karaoke
            get(R.string.ui_p_cat_cover) -> R.string.ui_p_cat_cover
            get(R.string.ui_p_cat_edm) -> R.string.ui_p_cat_edm
            get(R.string.ui_p_cat_high_energy) -> R.string.ui_p_cat_high_energy
            get(R.string.ui_p_cat_jazz) -> R.string.ui_p_cat_jazz
            get(R.string.ui_p_cat_blues) -> R.string.ui_p_cat_blues
            // 分类歌单完整名称。
            get(R.string.ui_p_cat_blessing_song) -> R.string.ui_p_cat_blessing_song
            get(R.string.ui_p_cat_friendship_forever) -> R.string.ui_p_cat_friendship_forever
            get(R.string.ui_p_cat_love_duet) -> R.string.ui_p_cat_love_duet
            get(R.string.ui_p_cat_blessing) -> R.string.ui_p_cat_blessing
            get(R.string.ui_p_cat_friendship) -> R.string.ui_p_cat_friendship
            get(R.string.ui_p_cat_song) -> R.string.ui_p_cat_song
            get(R.string.ui_p_cat_forever) -> R.string.ui_p_cat_forever
            get(R.string.ui_p_cat_duet) -> R.string.ui_p_cat_duet
            get(R.string.ui_p_cat_selection) -> R.string.ui_p_cat_selection
            // 分类目录中的完整歌单名称。
            get(R.string.ui_p_cat_opera_clip) -> R.string.ui_p_cat_opera_clip
            get(R.string.ui_p_cat_disco) -> R.string.ui_p_cat_disco
            get(R.string.ui_p_cat_revolutionary_song) -> R.string.ui_p_cat_revolutionary_song
            get(R.string.ui_p_cat_military_song) -> R.string.ui_p_cat_military_song
            get(R.string.ui_p_cat_praise_motherland) -> R.string.ui_p_cat_praise_motherland
            get(R.string.ui_p_cat_folk_singing) -> R.string.ui_p_cat_folk_singing
            get(R.string.ui_p_cat_clip) -> R.string.ui_p_cat_clip
            get(R.string.ui_p_cat_revolutionary) -> R.string.ui_p_cat_revolutionary
            get(R.string.ui_p_cat_praise) -> R.string.ui_p_cat_praise
            get(R.string.ui_p_cat_motherland) -> R.string.ui_p_cat_motherland
            get(R.string.ui_p_cat_singing_heritage) -> R.string.ui_p_cat_singing_heritage
            // 图标/封面匹配词对应的显示翻译。
            get(R.string.ui_p_cat_birthday) -> R.string.ui_p_cat_birthday
            get(R.string.ui_p_cat_festive) -> R.string.ui_p_cat_festive
            get(R.string.ui_p_cat_congrats) -> R.string.ui_p_cat_congrats
            get(R.string.ui_p_cat_newyear) -> R.string.ui_p_cat_newyear
            get(R.string.ui_p_cat_friendship_alt) -> R.string.ui_p_cat_friendship_alt
            get(R.string.ui_p_cat_friends) -> R.string.ui_p_cat_friends
            get(R.string.ui_p_cat_sweet) -> R.string.ui_p_cat_sweet
            get(R.string.ui_p_cat_lover) -> R.string.ui_p_cat_lover
            get(R.string.ui_p_cat_dj) -> R.string.ui_p_cat_dj
            get(R.string.ui_p_cat_index) -> R.string.ui_p_cat_index
            // 排行榜二级页面的榜单原名（曲库返回，非资源键）。
            get(R.string.ui_p_rank_rising) -> R.string.ui_p_rank_rising
            get(R.string.ui_p_rank_douyin) -> R.string.ui_p_rank_douyin
            get(R.string.ui_p_rank_mainland) -> R.string.ui_p_rank_mainland
            get(R.string.ui_p_rank_hongkong) -> R.string.ui_p_rank_hongkong
            get(R.string.ui_p_rank_taiwan) -> R.string.ui_p_rank_taiwan
            get(R.string.ui_p_rank_trend) -> R.string.ui_p_rank_trend
            get(R.string.ui_p_rank_dance) -> R.string.ui_p_rank_dance
            // 分类二级页面的中英混合歌单名（曲库返回原始中文名，片段替换后仍有残留中文）。
            get(R.string.ui_p_cat2_sweet_happiness) -> R.string.ui_p_cat2_sweet_happiness
            get(R.string.ui_p_cat2_joy_celebration) -> R.string.ui_p_cat2_joy_celebration
            get(R.string.ui_p_cat2_heartbreak) -> R.string.ui_p_cat2_heartbreak
            get(R.string.ui_p_cat2_lonely_ethereal) -> R.string.ui_p_cat2_lonely_ethereal
            get(R.string.ui_p_cat2_hd_songs) -> R.string.ui_p_cat2_hd_songs
            get(R.string.ui_p_cat2_grassland_songs) -> R.string.ui_p_cat2_grassland_songs
            get(R.string.ui_p_cat2_men_ktv) -> R.string.ui_p_cat2_men_ktv
            get(R.string.ui_p_cat2_women_club) -> R.string.ui_p_cat2_women_club
            get(R.string.ui_p_cat2_campus_folk) -> R.string.ui_p_cat2_campus_folk
            get(R.string.ui_p_cat2_exotic) -> R.string.ui_p_cat2_exotic
            get(R.string.ui_p_cat2_instrumental) -> R.string.ui_p_cat2_instrumental
            get(R.string.ui_p_cat2_medley) -> R.string.ui_p_cat2_medley
            get(R.string.ui_p_cat2_dj_mix) -> R.string.ui_p_cat2_dj_mix
            get(R.string.ui_p_cat2_vocal_coaching) -> R.string.ui_p_cat2_vocal_coaching
            get(R.string.ui_p_cat2_slow_dance) -> R.string.ui_p_cat2_slow_dance
            get(R.string.ui_p_cat2_ktv_must_sing) -> R.string.ui_p_cat2_ktv_must_sing
            else -> 0
        }
        return if (resId != 0) display(resId) else raw
    }

    /** 分类歌单名里常见的中文片段；用于长名称的显示层翻译，匹配时按长度倒序避免短词抢先。 */
    private val playlistTermResIds = intArrayOf(
        R.string.ui_a_0069, R.string.ui_a_0071, R.string.ui_a_0072, R.string.ui_a_0078,
        R.string.ui_a_0079, R.string.ui_a_0199, R.string.ui_a_0319, R.string.ui_a_0321,
        R.string.ui_a_0480, R.string.ui_a_0520, R.string.ui_a_0521, R.string.ui_a_0522,
        R.string.ui_a_0523, R.string.ui_a_0530, R.string.ui_a_0651, R.string.ui_a_0727,
        R.string.ui_a_0728, R.string.ui_a_0729, R.string.ui_a_0857, R.string.ui_a_0858,
        R.string.ui_a_0860, R.string.ui_a_0861, R.string.ui_a_0863, R.string.ui_a_0864,
        R.string.ui_a_0865, R.string.ui_a_0867, R.string.ui_a_0868, R.string.ui_a_0869,
        R.string.ui_a_0870, R.string.ui_a_0871,
        R.string.ui_p_cat_jinge, R.string.ui_p_cat_dance, R.string.ui_p_cat_hot,
        R.string.ui_p_cat_rising, R.string.ui_p_cat_douyin, R.string.ui_p_cat_new_chart,
        R.string.ui_p_cat_classmate, R.string.ui_p_cat_love_song, R.string.ui_p_cat_romance,
        R.string.ui_p_cat_love_short, R.string.ui_p_cat_longing, R.string.ui_p_cat_golden,
        R.string.ui_p_cat_nostalgic_golden, R.string.ui_p_cat_huangmei, R.string.ui_p_cat_pingju,
        R.string.ui_p_cat_opera_type, R.string.ui_p_cat_opera, R.string.ui_p_cat_mountain_song,
        R.string.ui_p_cat_country, R.string.ui_p_cat_cartoon, R.string.ui_p_cat_animation,
        R.string.ui_p_cat_nursery, R.string.ui_p_cat_film_tv, R.string.ui_p_cat_movie,
        R.string.ui_p_cat_tv_series, R.string.ui_p_cat_theme, R.string.ui_p_cat_insert,
        R.string.ui_p_cat_rhythm, R.string.ui_p_cat_campus, R.string.ui_p_cat_youth,
        R.string.ui_p_cat_graduation, R.string.ui_p_cat_military, R.string.ui_p_cat_patriotic,
        R.string.ui_p_cat_mainstream, R.string.ui_p_cat_homesick, R.string.ui_p_cat_family_love,
        R.string.ui_p_cat_hometown, R.string.ui_p_cat_mom, R.string.ui_p_cat_dad,
        R.string.ui_p_cat_family, R.string.ui_p_cat_global, R.string.ui_p_cat_singing,
        R.string.ui_p_cat_karaoke, R.string.ui_p_cat_cover, R.string.ui_p_cat_edm,
        R.string.ui_p_cat_high_energy, R.string.ui_p_cat_jazz, R.string.ui_p_cat_blues,
        R.string.ui_p_cat_blessing_song, R.string.ui_p_cat_friendship_forever,
        R.string.ui_p_cat_love_duet, R.string.ui_p_cat_blessing, R.string.ui_p_cat_friendship,
        R.string.ui_p_cat_song, R.string.ui_p_cat_forever, R.string.ui_p_cat_duet,
        R.string.ui_p_cat_selection,
        R.string.ui_p_cat_opera_clip, R.string.ui_p_cat_disco,
        R.string.ui_p_cat_revolutionary_song, R.string.ui_p_cat_military_song,
        R.string.ui_p_cat_praise_motherland, R.string.ui_p_cat_folk_singing,
        R.string.ui_p_cat_clip, R.string.ui_p_cat_revolutionary, R.string.ui_p_cat_praise,
        R.string.ui_p_cat_motherland, R.string.ui_p_cat_singing_heritage,
        R.string.ui_p_cat_birthday, R.string.ui_p_cat_festive, R.string.ui_p_cat_congrats,
        R.string.ui_p_cat_newyear, R.string.ui_p_cat_friendship_alt, R.string.ui_p_cat_friends,
        R.string.ui_p_cat_sweet, R.string.ui_p_cat_lover, R.string.ui_p_cat_dj,
        R.string.ui_p_cat_index,
        R.string.ui_p_rank_rising, R.string.ui_p_rank_douyin, R.string.ui_p_rank_mainland,
        R.string.ui_p_rank_hongkong, R.string.ui_p_rank_taiwan, R.string.ui_p_rank_trend,
        R.string.ui_p_rank_dance,
        // 分类二级页面中英混合歌单名：完整名 + 通用片段（片段匹配按长度倒序，长词优先）。
        R.string.ui_p_cat2_sweet_happiness, R.string.ui_p_cat2_joy_celebration,
        R.string.ui_p_cat2_heartbreak, R.string.ui_p_cat2_lonely_ethereal,
        R.string.ui_p_cat2_hd_songs, R.string.ui_p_cat2_grassland_songs,
        R.string.ui_p_cat2_men_ktv, R.string.ui_p_cat2_women_club,
        R.string.ui_p_cat2_campus_folk, R.string.ui_p_cat2_exotic,
        R.string.ui_p_cat2_instrumental, R.string.ui_p_cat2_medley,
        R.string.ui_p_cat2_dj_mix, R.string.ui_p_cat2_vocal_coaching,
        R.string.ui_p_cat2_slow_dance, R.string.ui_p_cat2_ktv_must_sing,
        R.string.ui_p_cat2_happiness, R.string.ui_p_cat2_joy, R.string.ui_p_cat2_celebration,
        R.string.ui_p_cat2_heartbroken, R.string.ui_p_cat2_sad, R.string.ui_p_cat2_lonely,
        R.string.ui_p_cat2_ethereal, R.string.ui_p_cat2_hd, R.string.ui_p_cat2_grassland,
        R.string.ui_p_cat2_man, R.string.ui_p_cat2_woman, R.string.ui_p_cat2_beauty_club,
        R.string.ui_p_cat2_folk, R.string.ui_p_cat2_exotic_seg, R.string.ui_p_cat2_charm,
        R.string.ui_p_cat2_medley_seg, R.string.ui_p_cat2_dj_seg, R.string.ui_p_cat2_teaching,
        R.string.ui_p_cat2_slow_seg, R.string.ui_p_cat2_king, R.string.ui_p_cat2_must_sing,
    )

    private val playlistTerms: List<Pair<String, Int>> by lazy {
        playlistTermResIds.map { get(it) to it }.sortedByDescending { it.first.length }
    }

    /**
     * 分类歌单名称显示：先做完整名称匹配，未命中时对已知中文片段做最长优先替换，
     * 这样“儿歌精选”“经典老歌怀旧版”这类长名称也能在英文界面显示英文。
     * 仅用于显示层，查询参数与点击回调仍使用原始中文名称。
     */
    @JvmStatic
    fun displayPlaylistName(value: String?): String {
        val raw = value ?: return ""
        if (raw.isEmpty()) return raw
        val exact = displayName(raw)
        if (exact != raw) return exact
        val out = StringBuilder()
        var i = 0
        var matched = false
        while (i < raw.length) {
            val hit = playlistTerms.firstOrNull { (term, _) -> raw.startsWith(term, i) }
            if (hit != null) {
                out.append(display(hit.second)).append(' ')
                i += hit.first.length
                matched = true
            } else {
                out.append(raw[i])
                i += 1
            }
        }
        return if (matched) out.toString().trim() else raw
    }
}
