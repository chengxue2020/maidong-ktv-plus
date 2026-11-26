package com.local.ktv

import android.Manifest
import android.annotation.TargetApi
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.DialogInterface.OnShowListener
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaPlayer.OnCompletionListener
import android.media.MediaPlayer.OnPreparedListener
import android.media.MediaPlayer.TrackInfo
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.OnFocusChangeListener
import android.view.View.OnTouchListener
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.AdapterView.OnItemClickListener
import android.widget.AdapterView.OnItemLongClickListener
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.Space
import android.widget.TextView
import android.widget.TextView.OnEditorActionListener
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.ImageViewCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.local.ktv.KtvStore.Companion.stableId
import com.local.ktv.Song.Companion.local
import com.local.ktv.SongApiClient.getSongDownloadUrl
import com.local.ktv.SongApiClient.init
import com.local.ktv.SongOkDownloadManager.DownloadCallback
import com.local.ktv.SongOkDownloadManager.download
import com.local.ktv.SongOkDownloadManager.getLocalFile
import com.local.ktv.SongOkDownloadManager.isDownloaded
import com.local.ktv.player.PlayerControllerView
import com.local.ktv.player.PlayerControllerView.OnControlListener
import com.local.ktv.player.KtvPlaybackEngine
import com.local.ktv.player.KtvVideoView
import com.local.ktv.player.VocalSwitchHelper
import com.local.ktv.player.VocalSwitchHelper.switchVocal
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.ArrayDeque
import java.util.Locale
import java.util.LinkedHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.Any
import kotlin.Array
import kotlin.Boolean
import kotlin.ByteArray
import kotlin.Comparator
import kotlin.Exception
import kotlin.Float
import kotlin.FloatArray
import kotlin.Int
import kotlin.IntArray
import kotlin.String
import kotlin.Throws
import kotlin.also
import kotlin.arrayOf
import kotlin.arrayOfNulls
import kotlin.check
import kotlin.floatArrayOf
import kotlin.intArrayOf
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.plus
import kotlin.toString

class MainActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val keyboardSearchRunnable = Runnable { performSearch() }
    // ⚠️ 必须用 DiscardPolicy 而非 Executors 默认的 AbortPolicy：
    // 切语言会重建 Activity，onDestroy 里 io.shutdownNow() 关闭线程池；此时 initializeDatabase
    // 等残留的 main.post 回调若再 io.execute()，默认策略会抛 RejectedExecutionException 导致启动闪退。
    // 静默丢弃这些本就不再需要的任务是正确行为，运行期与 newFixedThreadPool 完全一致（对齐 ori）。
    private val io: ExecutorService = ThreadPoolExecutor(
        4, 4, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>(),
        ThreadPoolExecutor.DiscardPolicy()
    )
    private val stateIo: ExecutorService = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>(),
        ThreadPoolExecutor.DiscardPolicy()
    )

    /**
     * 「切歌 → 公放」专用单线程执行器。
     *
     * ⚠️ **绝不能用 [io] 池**：它只有 4 个线程，"点歌即下即播"（`streamDownloadAndPlay`）这类
     * 几十秒的长任务也在里面。极米 H1S 上并发点几首歌就能把池占满，切歌任务于是一直排队 ——
     * 旧歌播完后彻底没声、按什么都没反应（用户报的"老设备卡死"，新设备因下载快而看不出来）。
     */
    private val randomPlayExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ktv-random-play").apply { isDaemon = true }
    }

    /**
     * 公放候选缓存（本机可播放歌曲）：启动时后台预热一次，下载完成后增量更新。
     *
     * ⚠️ **切歌时不能现扫**：候选来自 `localSongInventory()` + `library.scanLocal()`，
     * 代价是「每个文件在头尾各读约 4.3MB 做 TS 校验」+「遍历 `/storage/ 各挂载点`
     * （老 ROM 上的失效挂载点会让 `File.listFiles()` 永久阻塞）」。新设备上很快，
     * 极米 H1S 上足以让切歌看起来卡死。
     */
    private val randomCandidates = java.util.concurrent.ConcurrentHashMap<String, Song>()
    @Volatile private var randomCandidatesPrimed = false
    @Volatile private var randomCandidatesPriming = false
    private val library = SongLibrary()
    private lateinit var stateDatabase: KtvStateDatabase
    private lateinit var playbackEngine: KtvPlaybackEngine

    /**
     * 获取 KTV 状态存储对象(供 Fragment 访问已点/已唱/收藏/歌单数据)。
     * 
     * @return 全局 KtvStore 实例
     */
    val store: KtvStore = KtvStore()
    private val remoteServer by lazy { LocalRemoteServer(this) }
    @Volatile private var remoteStateVersion = 1L
    private val remoteSongCache = Collections.synchronizedMap(LinkedHashMap<String, Song>())

    /**
     * 远程搜索结果的短期缓存（LRU，最多 24 条 = 最近 4 个查询 × 6 页）。
     *
     * 曲库 DB 有 67 万首/约 1GB，搜索是 I/O 密集操作（实测同一查询冷态数秒、热态数十至数百毫秒）。
     * 同一「类型+关键词+页码」重复请求直接复用上次的 JSON，翻页（「更多」）与重复搜索不再查库。
     * TTL 60s + 曲库刷新时清空，保证隐藏/删除歌曲后结果及时跟上。
     */
    private val remoteSearchCache = object : LinkedHashMap<String, Pair<String, Long>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, Long>>?): Boolean =
            size > 24
    }
    private var mobileQrOverlay: View? = null
    private var fullScreenQrOverlay: View? = null
    /** 播放页面右上角「网络未连接」提示。 */
    private var fullScreenNetworkBadge: TextView? = null
    private var updateCheckStarted = false
    private var compactUi = false
    private val remoteNetworkMonitor = object : Runnable {
        override fun run() {
            val ip = localIp().takeIf { LocalRemoteServer.isPrivateIpv4(it) }
            // 无可用局域网 IP(离线/切换中): 不做重启与刷新, 避免每 5s 空转(网络恢复由
            // KtvNetworkMonitor 的回调负责)。
            if (ip.isNullOrEmpty()) {
                main.postDelayed(this, 5000)
                return
            }
            if (ip != remoteServer.address()) {
                remoteServer.stop()
                startRemoteServer()
                // 换网/换 IP 后出口 IP 可能变化, 歌曲源会话必须重建(见 refreshNetworkState)。
                refreshNetworkState("ip-change:$ip", notify = false)
            }
            main.postDelayed(this, 5000)
        }
    }

    /** 周期向 hbeat.ktvsky.com 上报设备身份,维持歌曲源对"合法设备"的判定。 */
    private var heartbeatTicks = 0

    private val ktvHeartbeatRunnable = object : Runnable {
        override fun run() {
            sendDeviceHeartbeat(boot = false)
            // 每 6 小时留一条健康快照：日志此前只有"事件"(点歌/失败/升级)，
            // 没有"状态样本"，现场排查空间/曲库/网络随时间的变化时缺参照。
            heartbeatTicks++
            if (heartbeatTicks % 6 == 0) logHealthSnapshot()
            main.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    /** 健康快照：版本 / 在线 / 可用空间 / 曲库文件 —— 单行，便于按 HEALTH 检索。 */
    private fun logHealthSnapshot() {
        runCatching {
            val db = AppPaths.databaseFile
            val freeGb = AppPaths.databaseDir.usableSpace / 1024.0 / 1024.0 / 1024.0
            MiliLog.notice(
                "HEALTH ver=${BuildConfig.VERSION_NAME} online=${KtvNetworkMonitor.online}" +
                    " free=${String.format(Locale.ROOT, "%.2f", freeGb)}GB" +
                    " dbExist=${db.exists()} dbMB=${db.length() / 1024 / 1024}" +
                    " root=${AppPaths.root.absolutePath}",
            )
        }
    }
    private val visibleSongs: MutableList<Song> = ArrayList<Song>()
    private val visibleSingers: MutableList<Array<String?>?> = ArrayList<Array<String?>?>()
    private val visiblePlaylists: MutableList<Array<String?>> = ArrayList()
    private val orderQueue: MutableList<Song>? = store.orderQueue
    private val sangHistory = store.sangHistory

    /**
     * 获取当前下载任务列表(供 DownloadListFragment 展示下载队列)。
     * 
     * @return 全局下载任务列表
     */
    val downloads: MutableList<DownloadTask> = ArrayList<DownloadTask>()

    private var content: LinearLayout? = null
    private var nav: LinearLayout? = null
    private var header: TextView? = null
    private var status: TextView? = null
    private var progress: ProgressBar? = null
    private var databaseLoadingOverlay: FrameLayout? = null
    private var databaseLoadingText: TextView? = null
    private var databaseLoadingProgress: ProgressBar? = null
    private var databaseRetryButton: TextView? = null
    @Volatile private var databaseBootstrapRunning = false
    private var player: KtvVideoView? = null

    /** 播放器控制层(叠加在 player 之上)  */
    private var playerController: PlayerControllerView? = null
    private var currentMediaPlayer: MediaPlayer? = null
    private var vocalPlayer: MediaPlayer? = null
    private var embeddedVocalEngine: KtvPlaybackEngine? = null
    private var vocalPlayerPrepared = false
    private var pendingVocalPosition = 0
    private var embeddedDualTrackMode = false
    private var embeddedMainTrackOriginal = true

    /**
     * 获取当前正在播放的歌曲(供已点列表标记播放状态)。
     * 
     * @return 当前播放的歌曲,未播放时返回 null
     */
    var currentSong: Song? = null
        private set
    private var currentVocalPath = ""
    private var lyricCurrent: TextView? = null
    private var lyricNext: TextView? = null
    private val lyricLines: MutableList<LyricLine?> = ArrayList<LyricLine?>()
    private val lyricTicker: Runnable = object : Runnable {
        override fun run() {
            updateFullScreenProgress()
            updateLyricView()
            // 播放会话会随歌曲变化，均衡器需要周期性对齐到当前实际播放的会话。
            syncEqualizerSessions()
            // 播放界面左上角的「当前播放 / 下一首」实时刷新: 无论队列是本地点歌、
            // 手机扫码点歌还是自动切歌而变化, 500ms 内必然同步到界面。
            refreshNowPlayingLabels()
            main.postDelayed(this, 500)
        }
    }
    private var search: EditText? = null
    private var songList: ListView? = null
    private var modernSongAdapter: SongListAdapter? = null
    private var queueList: ListView? = null
    private var songAdapter: ArrayAdapter<String?>? = null
    private var queueAdapter: ArrayAdapter<String?>? = null
    private var audioManager: AudioManager? = null
    private var recorder: MediaRecorder? = null
    /** 电视/投影等设备需 App 占用麦克风输入后才会启用麦克风声音输出(供演唱)。 */
    private val micActivator = MicActivator()
    private var currentRecording: File? = null
    private var currentPage = KtvRaw.get(R.string.ui_a_0108)

    private data class FocusBookmark(
        val resourceName: String?,
        val marker: String?,
        val text: String?,
        val groupText: String?,
    )

    private data class FocusReturnPoint(
        val restorePage: () -> Unit,
        val focus: FocusBookmark?,
        val directFocus: View?,
    )

    private val focusReturnStack = ArrayDeque<FocusReturnPoint>()
    private var restoringFocusRoute = false
    private var focusRestoreSerial = 0

    /** 导航按钮列表,用于维护选中态视觉  */
    private val navButtons: MutableList<TextView> = ArrayList<TextView>()

    /** 当前选中的导航索引(-1 表示未选中,如初始主页)  */
    private var currentNavIndex = -1

    // ===== 分页浏览状态(用于数据库驱动的曲库浏览) =====
    /** 当前浏览模式的页码(从0开始)  */
    private var browsePage = 0

    /** 当前浏览模式的总页数  */
    private var browseTotalPages = 0

    /** 一屏展示量：歌曲 2x5、歌手 4x2、排行榜 1x10。 */
    private fun browsePageSize(): Int = when (browseMode) {
        "rank" -> 10
        "singer_list" -> 8
        else -> MuseDatabase.PAGE_SIZE
    }

    /** 当前浏览类型:"hot"/"language"/"wordcount"/"singer"/"search"  */
    private var browseMode = "hot"

    /** 当前浏览的筛选参数(如语种名、歌手ID、搜索关键词等)  */
    private var browseParam: String? = ""

    /** 当前浏览的总记录数  */
    private var browseTotalCount = 0
    private var browseRequestVersion = 0
    private data class BrowseCacheKey(
        val mode: String,
        val param: String,
        val language: String,
        val searchQuery: String,
        val page: Int,
        val pageSize: Int,
    )
    private data class BrowsePageResult(val songs: List<Song>, val total: Int)
    private val browsePageCache = object : LinkedHashMap<BrowseCacheKey, BrowsePageResult>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BrowseCacheKey, BrowsePageResult>?): Boolean =
            size > 48
    }
    private val browseCountCache = object : LinkedHashMap<String, Int>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > 24
    }
    private val browseRequestsInFlight = HashSet<BrowseCacheKey>()
    private val browseDisplayRequests = HashMap<BrowseCacheKey, Int>()
    private var searchScope = "song"
    private var activeSettingsEntries: List<SettingsEntry> = emptyList()
    private var activeSettingsAdapter: SettingsListAdapter? = null
    private var activeSearchQuery = ""
    private var searchLanguage = KtvRaw.get(R.string.ui_a_0069)
    private var searchContextId = ""
    private var suppressSearchWatcher = false

    /** 用户在大曲库打开前进入了曲库页面，打开完成后需要重放该次查询。 */
    private var catalogRefreshPending = false

    /** 显示分页信息的 TextView  */
    private var pageInfo: TextView? = null
    private var loopOne = false
    private var autoNext = true
    private var songRetryCount = 0 // 播放错误重试计数
    private var playbackPreparing = false
    private var playWhenPrepared = true
    private var playbackGeneration = 0L
    private val pendingQueueSongIds = LinkedHashSet<String>()
    private var suppressCompletionUntil = 0L
    private val autoFullscreenRunnable = Runnable {
        val eligible = autoFullscreenSeconds > 0 && currentSong != null && playWhenPrepared && !isFullScreen
        Log.d(TAG, "autoFullscreen fired eligible=$eligible seconds=$autoFullscreenSeconds " +
            "song=${currentSong?.let(::stableId)} playing=$playWhenPrepared full=$isFullScreen")
        if (eligible) {
            showFullScreenPlayer()
        }
    }
    private var homeBannerIndex = 0
    // 与金调 KTV 一致仅 3 张: 新歌榜 / 戏曲 / 广场舞。
    private val homeBanners = intArrayOf(
        R.drawable.home_banner_0,
        R.drawable.home_banner_1,
        R.drawable.home_banner_2,
    )
    private val homeBannerRunnable = object : Runnable {
        override fun run() {
            homeBannerIndex = (homeBannerIndex + 1) % homeBanners.size
            homePosterView?.setBackgroundResource(homeBanners[homeBannerIndex])
            textPosterPage?.text = "${homeBannerIndex + 1}/${homeBanners.size}"
            main.postDelayed(this, 5000L)
        }
    }
    private var originalVocal = true
    private var vocalChannelMode = "自动"
    private var musicVolume = KtvStore.DEFAULT_MUSIC_VOLUME
    private var micVolume = 70
    private var tone = 0
    private var atmosphere = "标准"
    private var singMode = "普通演唱"
    private var recordEnabled = true
    private var scoreEnabled = true
    private var marqueeEnabled = true
    private var marqueeText: String? = "欢迎使用麦动"
    private var playWhileDownloading = false
    private var clearDownloadsOnBoot = false
    private var autoFullscreenSeconds = 30
    private var showUsbSongs = true
    private var autoDeleteSongs = true
    private var reserveStorageGb = 1.0
    private var floatingButtonEnabled = true
    private var songTitleSubtitleEnabled = true
    private var orderedSongOriginal = true
    private var orderedVolumeMode = 0
    private var orderedVocalMode = 0
    private var voiceEngineEnabled = true
    private var voiceEngineVolume = 70
    private var lightMode: String? = "自动"
    private var audioDelayMs = 0
    private var screenBrightness = 80
    private var screenMode = "适应屏幕"
    private var doubleScreenEnabled = false
    private var tableBroadcastEnabled = true
    private var tableBroadcastSeconds = 8
    private var tableBroadcastText: String? = "欢迎光临"

    private class LyricLine(val timeMs: Int, val text: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getWindow().getDecorView().setSystemUiVisibility(
            (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        )
        // 使用新的横屏TV布局
        setContentView(R.layout.activity_main)
        applyResponsiveLayout()
        if (!hasStoragePermission()) {
            requestStorage()
            return
        }
        // 存储目录建不出来时**不再闪退**（极米 H1S / Android 5.1.1 的历史崩溃点），
        // AppPaths.init 已在 Application.attachBaseContext 里挑好可写根目录。
        if (!AppPaths.ensureDirectories()) {
            Log.w(TAG, "存储目录创建失败: ${AppPaths.storageFacts(this)}")
        }
        store.load()
        loadStateFromStore()
        stateDatabase = KtvStateDatabase(applicationContext)
        if (clearDownloadsOnBoot) clearDownloadedFilesOnBoot()
        val restoredState = runCatching {
            stateDatabase.restoreOrSeed(orderQueue!!, sangHistory)
        }.getOrElse { KtvStateDatabase.RestoredState(null, "idle") }
        currentSong = restoredState.currentSong
        playWhenPrepared = restoredState.playbackState != "paused"
        runCatching {
            stateDatabase.restoreDownloads().forEach { snapshot ->
                if (snapshot.status == "completed") return@forEach
                snapshot.localPath?.let { snapshot.song.path = it }
                downloads += DownloadTask(snapshot.song).apply {
                    progress = snapshot.progress
                    state = when (snapshot.status) {
                        "queued", "downloading" -> KtvRaw.get(R.string.ui_a_0109)
                        "completed" -> KtvRaw.get(R.string.ui_a_0110)
                        "failed" -> "失败：${snapshot.error.orEmpty()}"
                        else -> snapshot.status
                    }
                }
                if (snapshot.status == "queued" || snapshot.status == "downloading") {
                    stateDatabase.updateDownload(
                        snapshot.song,
                        "failed",
                        snapshot.progress,
                        snapshot.localPath,
                        KtvRaw.get(R.string.ui_a_0112),
                    )
                }
            }
        }
        applyScreenBrightness()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager?
        // 初始化 API 客户端 (歌曲下载链接)
        init("abe235a87118f6de", "080027deed4f")
        playbackEngine = KtvPlaybackEngine(applicationContext)
        // 远程日志系统(GlogCenter): 启动即上报设备/网络信息。
        MiliLog.init(applicationContext)
        // ⚠️ STORAGE 探测含真实建目录/写盘与 /proc/mounts 读取，**不能放在主线程**，
        // 而且必须在 refreshLogDeviceContext() 之后，否则这一行的
        // serverip/clientip/user/traceid 全是 undefined（旧日志就是这样，无法按设备检索）。
        // 现在统一在 reportDeviceBootInfo() 的后台线程里、awaitServerIp() 之后上报。
        refreshLogDeviceContext()
        reportDeviceBootInfo()
        // 歌曲源窗口/风控到期守卫: 源对陌生出口 IP 可能在短暂窗口后返回 403,
        // 换公网 IP 即恢复。到期时弹一次提示引导用户重启光猫/路由器(重拨换 IP)。
        Eac19WindowGuard.onBlocked = {
            main.post {
                if (isFinishing || isDestroyed) return@post
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.ui_p_001))
                    .setMessage(
                        getString(R.string.ui_p_risk_dialog),
                    )
                    .setPositiveButton(getString(R.string.ui_p_002), null)
                    .create()
                    .showForTv(DialogInterface.BUTTON_POSITIVE)
            }
        }
        // 初始化新布局的 UI 组件
        initTvLayout()
        val languageResume = LanguageSwitchPlayback.consume(restoredState.currentSong)
        val startupSong = restoredState.currentSong ?: orderQueue!!.firstOrNull()
        val startupShouldPlay = languageResume?.shouldPlay ?: true
        startupSong?.let { restoredSong ->
            play(restoredSong, languageResume?.positionMs ?: 0, startupShouldPlay)
            // 语言切换重建后：若续播歌曲仍在下载，把 UI 回调重新挂到新 Activity。
            if (SongOkDownloadManager.isDownloading(restoredSong)) {
                SongOkDownloadManager.reattachCallback(restoredSong, downloadUiCallback(restoredSong, null))
            }
            updateBottomBar(restoredSong, startupShouldPlay)
        } ?: run {
            // 启动时没有恢复的播放与已点歌曲: 曲库就绪后自动随机播放本地已下载歌曲
            // (与"点歌列表播放完毕自动随机本地歌曲"同一逻辑), 避免开机后空白等待。
            startupRandomPending = true
        }
        // Keep initialization on the loading state until the external catalog is ready.
        SongApiClient.init(this)  // 主线程初始化 JS Bridge
        initializeDatabase()
        startRemoteServer()
        updateMobileQrOverlay()
        // 广告过滤已按需求全部移除: 启动时不再清理任何"注入广告副本"本地文件。
        // 预热公放候选缓存（独立守护线程 + 内部超时），让之后的「切歌 → 公放」零 I/O。
        // 延后一点避开曲库加载/启动高峰，但不影响正确性：预热完成前切歌会走安全兜底路径。
        main.postDelayed({ primeRandomCandidates() }, 5_000L)
        main.postDelayed(remoteNetworkMonitor, 5000)
        // 网络状态监测: 断网提示 + 恢复后重建歌曲源会话/重试下载。
        KtvNetworkMonitor.start(applicationContext) { online, initial ->
            onNetworkStateChanged(online, initial)
        }
        // 设备心跳:开机立即上报一次(boot=1),之后每 3600s 保活,维持歌曲源合法设备判定。
        sendDeviceHeartbeat(boot = true)
        main.postDelayed(ktvHeartbeatRunnable, HEARTBEAT_INTERVAL_MS)
        main.post(lyricTicker)
        main.postDelayed({ checkAppUpdate(false) }, 2500L)
        // 电视/投影等设备: 请求麦克风权限并保持输入占用, 否则麦克风声音不会输出到扬声器。
        main.postDelayed({ requestMicActivation() }, 4000L)
    }

    /** 申请并激活麦克风输入(演唱用)。授权后由 [MicActivator] 常驻占用输入通道。 */
    private fun requestMicActivation() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC_PERMISSION)
                return
            }
        }
        micActivator.start()
        registerMicInputWatcher()
        Log.i(TAG, "麦克风输入已激活(演唱输出通道占用)")
    }

    /**
     * 监听输入设备变化: 外接麦克风(USB 声卡/3.5mm 麦)插入时系统会把默认输入路由切到外接,
     * 原 AudioRecord 随之失效。检测到变化后重建占用, 让演唱输出通道跟随当前麦克风。
     */
    private fun registerMicInputWatcher() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= 23) {
            manager.registerAudioDeviceCallback(ensureMicDeviceCallback(), null)
        } else {
            runCatching {
                val filter = IntentFilter(Intent.ACTION_HEADSET_PLUG)
                registerReceiver(micHotplugReceiver, filter)
            }
        }
    }

    private fun unregisterMicInputWatcher() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= 23) {
            micDeviceCallback?.let { manager.unregisterAudioDeviceCallback(it) }
            micDeviceCallback = null
        } else {
            runCatching { unregisterReceiver(micHotplugReceiver) }
        }
    }

    /**
     * 输入设备变化回调(`android.media.AudioDeviceCallback`, API 23+)。
     *
     * ⚠️ **必须按需创建，不能写成字段初始化器**：字段初始化在 Activity 构造时执行，
     * 早于 `onCreate`，而 `Build.VERSION.SDK_INT` 判断发生在 `onCreate` 之后。
     * Android 5.x(API 22) 没有 `AudioDeviceCallback` 这个类 —— 对象在构造期被实例化会直接
     * `NoClassDefFoundError` 闪退（极米 H1S / GMUI2.0 / Android 5.1.1 的闪退根因）。
     */
    private var micDeviceCallback: android.media.AudioDeviceCallback? = null

    @TargetApi(23)
    private fun ensureMicDeviceCallback(): android.media.AudioDeviceCallback {
        micDeviceCallback?.let { return it }
        val created = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>) {
                handleMicInputDevicesChanged(addedDevices.any { isMicInputDevice(it) })
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>) {
                handleMicInputDevicesChanged(removedDevices.any { isMicInputDevice(it) })
            }
        }
        micDeviceCallback = created
        return created
    }

    private val micHotplugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handleMicInputDevicesChanged(true)
        }
    }

    /** 是否为可用的麦克风输入设备(内置/耳机/USB 等)。仅由 API 23+ 的输入设备回调调用。 */
    @TargetApi(23)
    private fun isMicInputDevice(device: android.media.AudioDeviceInfo): Boolean {
        if (!device.isSource) return false
        return when (device.type) {
            android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
            android.media.AudioDeviceInfo.TYPE_HDMI,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            android.media.AudioDeviceInfo.TYPE_LINE_ANALOG,
            -> true
            else -> false
        }
    }

    /** 输入设备增删后: 重新打开麦克风占用, 跟随系统当前路由(优先外接)。 */
    private fun handleMicInputDevicesChanged(_changed: Boolean) {
        // 延迟少许等系统完成路由切换, 再重建 AudioRecord。
        main.postDelayed({ micActivator.restart() }, 300L)
    }

    private fun initializeDatabase() {
        if (databaseBootstrapRunning) return
        databaseBootstrapRunning = true
        lastDatabaseProgressText = ""
        showDatabaseLoading(true, getString(R.string.ui_p_003), null)
        io.execute {
            var bootstrapError: Throwable? = null
            var usedOldDatabase = false

            // 先尝试打开本地曲库；仅当本地不可用或已过 30 天时效时才联网同步。
            var ok = library.muse.open()
            val syncDue = DatabaseBootstrapper.isSyncDue()
            if (!ok || syncDue) {
                val reason = if (!ok) "本地曲库不可用" else "已到 ${DatabaseBootstrapper.SYNC_TTL_DAYS} 天同步时效"
                Log.i(TAG, "触发曲库服务器同步：$reason")
                val remoteVersion = DatabaseBootstrapper.fetchRemoteVersion()
                if (remoteVersion != null) {
                    val localVersion = DatabaseBootstrapper.getLocalDbVersion()
                    if (!ok || remoteVersion != localVersion) {
                        library.muse.close()
                        val result = DatabaseBootstrapper.download(::showDatabaseDownloadProgress)
                        bootstrapError = result.exceptionOrNull()
                        ok = result.isSuccess && library.muse.open()
                        if (!ok) {
                            // A failed online update must not make an existing local catalog unusable.
                            usedOldDatabase = library.muse.open()
                            ok = usedOldDatabase
                        }
                    } else {
                        // 版本一致：本次已完成有效校验，重置时效起点。
                        DatabaseBootstrapper.markSynced()
                    }
                } else {
                    // 取远端版本失败 = 登录/同步没成功（老设备常见于 TLS 根证书缺失）。
                    // 以前这里只写 logcat 且不设 bootstrapError，界面只会显示兜底的
                    // 「未找到可用曲库」，远程日志里也什么都看不到 —— 排查非常困难。
                    bootstrapError = IllegalStateException("无法连接曲库服务器（获取远端版本失败）")
                    Log.w(TAG, "曲库同步到期，但获取远端版本失败，本次继续使用本地曲库")
                    MiliLog.warn(
                        "DB-SYNC fetchRemoteVersion=null localDbOk=$ok syncDue=$syncDue " +
                            "sdk=${android.os.Build.VERSION.SDK_INT} root=${AppPaths.root.absolutePath}",
                    )
                }
            } else {
                Log.i(TAG, "曲库未到期(${DatabaseBootstrapper.syncStatusText()})，跳过服务器同步")
            }

            val count = if (ok) library.muse.songCount() else 0
            if (ok) warmRemoteBrowseCache(count)
            main.post {
                databaseBootstrapRunning = false
                if (ok) {
                    refreshLibrary {
                        showDatabaseLoading(false, "", null)
                        showHomePage()
                        if (usedOldDatabase && bootstrapError != null) toast(getString(R.string.ui_p_004))
                        if (catalogRefreshPending) refreshCurrentCatalogPage()
                        // 启动自动随机播放: 仅当启动时无恢复播放/空队列且允许顺序播放时触发一次。
                        if (startupRandomPending) {
                            startupRandomPending = false
                            if (autoNext && orderQueue!!.isEmpty() && currentSong == null) {
                                main.postDelayed({ playRandomLocalSong(startup = true) }, 600L)
                            }
                        }
                    }
                } else {
                    val reason = bootstrapError?.message.orEmpty().ifBlank { getString(R.string.ui_db_not_found) }
                    showDatabaseLoadingFailure(getString(R.string.ui_db_init_failed, reason))
                }
            }
        }
    }

    /**
     * 最近一次真正刷到界面上的曲库进度文案。
     *
     * ⚠️ 曲库下载是 430MB，会回调约 1700 次；每次都在主线程做「改文本 + 改进度 + 置顶视图」
     * 会把 UI 线程压死（表现为界面卡死、进度停滞，整机 CPU 被布局任务吃掉后下载本身也变慢）。
     * 因此这里按**最终显示文案**去重：既能挡掉高频分块回调，又能让「正在解压曲库 xxxMB」
     * 这类明细正常刷新。
     */
    @Volatile private var lastDatabaseProgressText = ""

    private fun showDatabaseDownloadProgress(value: Int, detail: String?) {
        val percent = value.coerceIn(0, 100)
        val phase = when {
            percent < 80 -> getString(R.string.ui_p_sync_dl)
            percent < 90 -> getString(R.string.ui_p_sync_unzip)
            percent < 100 -> getString(R.string.ui_p_sync_verify)
            else -> getString(R.string.ui_p_055)
        }
        val text = detail ?: "$phase $percent%"
        if (text == lastDatabaseProgressText) return
        lastDatabaseProgressText = text
        main.post { showDatabaseLoading(true, text, percent) }
    }

    private fun warmRemoteBrowseCache(count: Int) {
        val pageSize = MuseDatabase.PAGE_SIZE
        val songs = library.muse.hotSongs(0, pageSize * 2)
        synchronized(browseCountCache) { browseCountCache["hot\\u0001\\u0001全部\\u0001"] = count }
        songs.chunked(pageSize).forEachIndexed { page, pageSongs ->
            val key = BrowseCacheKey("hot", "", "全部", "", page, pageSize)
            synchronized(browsePageCache) { browsePageCache[key] = BrowsePageResult(pageSongs, count) }
        }
    }

    override fun onResume() {
        super.onResume()
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
        main.removeCallbacks(lyricTicker)
        main.removeCallbacks(homeBannerRunnable)
        main.removeCallbacks(autoFullscreenRunnable)
        main.removeCallbacks(remoteNetworkMonitor)
        KtvNetworkMonitor.stop()
        stopRecording(false)
        unregisterMicInputWatcher()
        micActivator.stop()
        releaseVocalPlayer()
        if (::playbackEngine.isInitialized) playbackEngine.release()
        KtvEqualizer.releaseAll()
        if (isFinishing) {
            // 真退出时丢弃一次性语言切换续播状态，避免下次冷启动误恢复。
            LanguageSwitchPlayback.clear()
            // 公放随机清单只在内存中，App 退出即清理（不属于已点清单，也不落盘）。
            RandomPlayQueue.clear()
            MiliLog.info("RANDOM-CLEAR app exit")
        }
        remoteServer.stop()
        library.muse.close()
        stateIo.shutdown()
        runCatching { stateIo.awaitTermination(2, TimeUnit.SECONDS) }
        if (::stateDatabase.isInitialized) stateDatabase.close()
    }

    private fun checkAppUpdate(manual: Boolean) {
        if (updateCheckStarted) return
        updateCheckStarted = true
        if (manual) toast(getString(R.string.ui_a_0126))
        AppUpdateManager.check(BuildConfig.VERSION_NAME) { result ->
            main.post {
                updateCheckStarted = false
                result.onSuccess { release ->
                    if (release == null) {
                        if (manual) toast(getString(R.string.ui_p_073, BuildConfig.VERSION_NAME))
                    } else {
                        showAppUpdateDialog(release)
                    }
                }.onFailure { error ->
                    Log.w(TAG, "Update check failed", error)
                    if (manual) toast(error.message ?: getString(R.string.ui_a_0128))
                }
            }
        }
    }

    private fun showAppUpdateDialog(release: AppUpdateManager.Release) {
        val notes = release.notes.trim().ifBlank { getString(R.string.ui_a_0129) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_p_074, release.version))
            .setMessage("${release.title}\n\n$notes")
            .setNegativeButton(getString(R.string.ui_a_0131), null)
            .setPositiveButton(getString(R.string.ui_a_0132)) { _, _ -> downloadAppUpdate(release) }
            .create().showForTv(DialogInterface.BUTTON_POSITIVE)
    }

    private fun downloadAppUpdate(release: AppUpdateManager.Release) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(12))
        }
        val value = label(getString(R.string.ui_p_075, release.apkName), 16, Color.WHITE).apply { gravity = Gravity.CENTER }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        box.addView(value, LinearLayout.LayoutParams(-1, dp(44)))
        box.addView(bar, LinearLayout.LayoutParams(-1, dp(24)))
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_set_update))
            .setView(box)
            .setNegativeButton(getString(R.string.ui_a_0135), null)
            .create()
        dialog.showForTv()
        AppUpdateManager.download(this, release, { progress ->
            main.post {
                bar.progress = progress
                value.text = getString(R.string.ui_p_076, release.apkName, progress)
            }
        }) { result ->
            main.post {
                dialog.dismiss()
                result.onSuccess { apk ->
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.ui_a_0137))
                        .setMessage(getString(R.string.ui_p_077, release.version))
                        .setNegativeButton(getString(R.string.ui_a_0131), null)
                        .setPositiveButton(getString(R.string.ui_a_0139)) { _, _ -> AppUpdateManager.install(this, apk) }
                        .create().showForTv(DialogInterface.BUTTON_POSITIVE)
                }.onFailure { error ->
                    AlertDialog.Builder(this).setTitle(getString(R.string.ui_a_0140))
                        .setMessage(error.message ?: getString(R.string.ui_a_0141))
                        .setPositiveButton(getString(R.string.ui_ok), null).show()
                }
            }
        }
    }

    /**
     * XML 保留电视端的大触控/遥控焦点尺寸；窄屏横屏设备在运行时切换紧凑尺寸。
     * 平板及电视继续使用原始布局，手机不会再因固定 370dp 左栏和顶部按钮而溢出。
     */
    private fun applyResponsiveLayout() {
        val config = resources.configuration
        compactUi = config.screenWidthDp < 1000 || config.screenHeightDp < 600
        if (!compactUi) return
        val widthDp = config.screenWidthDp.coerceAtLeast(480)
        val barHeight = dp(52)
        findViewById<ViewGroup?>(R.id.top_bar)?.apply {
            layoutParams = layoutParams.apply { height = barHeight }
            setPadding(dp(10), 0, dp(8), 0)
        }
        findViewById<ViewGroup?>(R.id.bottom_bar)?.apply {
            layoutParams = layoutParams.apply { height = barHeight }
            setPadding(dp(10), 0, dp(10), 0)
        }
        findViewById<View?>(R.id.text_logo)?.layoutParams =
            findViewById<View>(R.id.text_logo).layoutParams.apply { width = dp(34); height = dp(34) }
        findViewById<TextView?>(R.id.top_brand_name)?.apply { textSize = 16f }
        if (widthDp < 760) {
            findViewById<View?>(R.id.top_brand_name)?.visibility = View.GONE
            findViewById<View?>(R.id.btn_top_search)?.visibility = View.GONE
        }
        listOf(
            R.id.btn_top_search, R.id.text_top_order_label, R.id.btn_top_vocal,
            R.id.btn_top_next, R.id.btn_top_pause, R.id.btn_top_replay, R.id.btn_top_tone,
        ).forEach { id ->
            findViewById<TextView?>(id)?.apply {
                textSize = 11f
                setPadding(dp(5), paddingTop, dp(6), paddingBottom)
            }
        }
        findViewById<FrameLayout?>(R.id.content_frame)?.let { frame ->
            (frame.layoutParams as? FrameLayout.LayoutParams)?.apply {
                topMargin = barHeight
                bottomMargin = barHeight
                frame.layoutParams = this
            }
        }
        findViewById<LinearLayout?>(R.id.main_layout)?.setPadding(dp(12), dp(10), dp(12), dp(8))
        findViewById<LinearLayout?>(R.id.home_layout)?.setPadding(dp(12), dp(10), dp(12), dp(8))
        findViewById<LinearLayout?>(R.id.left_column)?.let { left ->
            val desiredWidthDp = (widthDp * 0.34f).toInt().coerceIn(230, 340)
            left.layoutParams = left.layoutParams.apply { width = dp(desiredWidthDp) }
            findViewById<View?>(R.id.player_container)?.layoutParams =
                findViewById<View>(R.id.player_container).layoutParams.apply {
                    height = dp((desiredWidthDp * 9f / 16f).toInt())
                }
        }
        listOf(
            R.id.home_card_regular, R.id.home_card_favorite, R.id.home_card_category,
            R.id.home_card_rank, R.id.home_card_song, R.id.home_card_singer, R.id.home_card_local,
        ).forEach { id ->
            findViewById<TextView?>(id)?.apply { textSize = 15f; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        }
    }

    private fun chromeBarHeight(): Int = dp(if (compactUi) 52 else 64)

    /**
     * 首页视频窗锁定 16:9（全分辨率/屏幕尺寸自适应）。
     *
     * 背景: 首页左列宽度由 layout_weight、高度由剩余空间决定，XML 里没有任何比例约束，
     * 不同分辨率/密度下窗体比例会漂移（实测窄屏设备 2.34:1、TV 1.21:1），而 MV 画面是
     * 16:9 —— 画面填不满窗体，左右(或上下)出现大块黑边，与卡片行/中列宽度互相错位。
     *
     * 做法（纯运行时计算，不写死任何 dp）:
     *   1. 可用高度 = 列高 - 卡片行高 - 列间距；
     *   2. 目标宽度 = 可用高度 × 16/9，上限为行宽的 62%（避免挤占中列/海报列）；
     *   3. 左列宽度改为固定目标宽（剩余宽度按权重还给中列/海报列）；
     *   4. 容器高度 = 目标宽 × 9/16；若受上限约束导致高度有富余，用 spacer 吸收，
     *      卡片行保持贴底，与中列/海报列底边对齐。
     * 通过 GlobalLayout 监听挂载，布局变化（首次布局/切页返回/旋转）时自动重算，
     * 目标值已满足时不做任何改动，不会引发布局抖动。
     */
    private var homeAspectSpacer: View? = null

    private fun applyHomePlayerAspect() {
        val host = findViewById<FrameLayout?>(R.id.home_player_container) ?: return
        val column = host.parent as? LinearLayout ?: return
        val row = column.parent as? LinearLayout ?: return
        if (column.childCount < 2 || column.visibility != View.VISIBLE) return
        if (column.width <= 0 || column.height <= 0 || row.width <= 0) return
        val cards = column.getChildAt(column.childCount - 1)
        val hostLp = host.layoutParams as? LinearLayout.LayoutParams ?: return
        val colLp = column.layoutParams as? LinearLayout.LayoutParams ?: return
        val gap = hostLp.bottomMargin.takeIf { it > 0 } ?: dp(8)
        val cardsH = if (cards.visibility == View.GONE) 0 else cards.height
        val availH = column.height - cardsH - gap
        if (availH <= 0) return
        val rowInner = row.width - row.paddingLeft - row.paddingRight
        val capW = ((rowInner - colLp.leftMargin - colLp.rightMargin) * 0.62f).toInt()
        val targetW = ((availH * 16f) / 9f).toInt().coerceIn(dp(120), capW.coerceAtLeast(dp(120)))
        val targetH = ((targetW * 9f) / 16f).toInt()
        val tolerance = dp(2)
        if (colLp.weight != 0f || kotlin.math.abs(colLp.width - targetW) > tolerance) {
            colLp.weight = 0f
            colLp.width = targetW
            column.layoutParams = colLp
        }
        if (hostLp.weight != 0f || kotlin.math.abs(hostLp.height - targetH) > tolerance) {
            hostLp.weight = 0f
            hostLp.height = targetH
            host.layoutParams = hostLp
        }
        val spacerH = availH - targetH
        val spacer = homeAspectSpacer
        if (spacerH > tolerance) {
            if (spacer == null) {
                val created = Space(this)
                column.addView(
                    created,
                    column.indexOfChild(cards),
                    LinearLayout.LayoutParams(0, 0, 1f),
                )
                homeAspectSpacer = created
            }
        } else if (spacer != null) {
            column.removeView(spacer)
            homeAspectSpacer = null
        }
    }

    /** 在首页列上挂布局监听，驱动 [applyHomePlayerAspect] 自适应。 */
    private fun installHomePlayerAspectGuard() {
        val host = findViewById<FrameLayout?>(R.id.home_player_container) ?: return
        val column = host.parent as? View ?: return
        column.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    runCatching { applyHomePlayerAspect() }
                        .onFailure { Log.w(TAG, "home player aspect failed", it) }
                    runCatching { applyHomeBottomCardsScale() }
                        .onFailure { Log.w(TAG, "home bottom cards scale failed", it) }
                }
            },
        )
    }

    /**
     * 首页底部三卡(常唱/收藏/分类)与右列卡片同高自适应：
     * 行高直接取右列卡片(home_card_rank)的实际渲染高度，配图占满卡高、
     * 字号按卡高等比缩放 —— 无论分辨率/密度如何，左右两侧视觉一致。
     */
    private fun applyHomeBottomCardsScale() {
        val row = findViewById<LinearLayout?>(R.id.home_bottom_cards_row) ?: return
        val ref = findViewById<View?>(R.id.home_card_rank) ?: return
        if (row.visibility != View.VISIBLE || ref.height <= 0 || row.height <= 0) return
        val target = ref.height
        if (target > 0 && kotlin.math.abs(row.layoutParams.height - target) > dp(2)) {
            row.layoutParams = row.layoutParams.apply { height = target }
        }
        val artSize = (target - dp(16)).coerceAtLeast(dp(26))
        val labelSp = target / resources.displayMetrics.density * 0.24f
        mapOf(
            R.id.home_card_regular to R.drawable.home_art_regular,
            R.id.home_card_favorite to R.drawable.home_art_favorite,
            R.id.home_card_category to R.drawable.home_art_category,
        ).forEach { (viewId, artRes) ->
            findViewById<TextView?>(viewId)?.apply {
                val art = AppCompatResources.getDrawable(context, artRes)?.mutate() ?: return@apply
                art.setBounds(0, 0, artSize, artSize)
                setCompoundDrawablesRelative(null, null, art, null)
                compoundDrawablePadding = dp(8)
                textSize = labelSp
                setPadding(dp(14), dp(6), dp(10), dp(6))
            }
        }
    }

    private fun buildShell() {
        val root = FrameLayout(this)
        root.setBackgroundColor(BG)

        val shell = LinearLayout(this)
        shell.setOrientation(LinearLayout.HORIZONTAL)
        shell.setPadding(dp(16), dp(14), dp(16), dp(12))
        root.addView(shell, FrameLayout.LayoutParams(-1, -1))

        val navScroll = ScrollView(this)
        navScroll.setFillViewport(true)
        navScroll.setBackgroundColor(Color.rgb(12, 18, 25))
        shell.addView(navScroll, LinearLayout.LayoutParams(dp(168), -1))

        nav = LinearLayout(this)
        nav!!.setOrientation(LinearLayout.VERTICAL)
        nav!!.setPadding(dp(10), dp(10), dp(10), dp(10))
        navScroll.addView(nav, FrameLayout.LayoutParams(-1, -2))

        val brand = label(getString(R.string.ui_brand), 26, GOLD)
        brand.setGravity(Gravity.CENTER)
        nav!!.addView(brand, LinearLayout.LayoutParams(-1, dp(64)))
        // 导航项(图标 + 文字),选中态由 selectNav() 维护
        navButtons.clear()
        addNav(getString(R.string.ui_nav_home), View.OnClickListener { v: View? -> showHome() })
        addNav(getString(R.string.ui_nav_songs), View.OnClickListener { v: View? -> showSongs(KtvRaw.get(R.string.ui_a_0069)) })
        addNav(getString(R.string.ui_nav_singers), View.OnClickListener { v: View? -> showSingers() })
        addNav(getString(R.string.ui_nav_rank), View.OnClickListener { v: View? -> showRank() })
        addNav(getString(R.string.ui_nav_language), View.OnClickListener { v: View? -> showLanguages() })
        addNav(getString(R.string.ui_nav_wordcount), View.OnClickListener { v: View? -> showWordCounts() })
        addNav(getString(R.string.ui_nav_favorites), View.OnClickListener { v: View? -> showFavorites() })
        addNav(getString(R.string.ui_nav_playlist), View.OnClickListener { v: View? -> showPlaylists() })
        addNav(getString(R.string.ui_nav_queue), View.OnClickListener { v: View? -> showQueue() })
        addNav(getString(R.string.ui_nav_sang), View.OnClickListener { v: View? -> showSang() })
        addNav(getString(R.string.ui_nav_download), View.OnClickListener { v: View? -> showDownloads() })
        addNav(getString(R.string.ui_nav_udisk), View.OnClickListener { v: View? -> showUdisk() })
        addNav(getString(R.string.ui_nav_movie), View.OnClickListener { v: View? -> showLocalMovies() })
        addNav(getString(R.string.ui_nav_party), View.OnClickListener { v: View? -> showDisco() })
        addNav(getString(R.string.ui_nav_network), View.OnClickListener { v: View? -> showNetworkDialog() })
        addNav(getString(R.string.ui_nav_screen), View.OnClickListener { v: View? -> showScreenDialog() })
        addNav(getString(R.string.ui_nav_app), View.OnClickListener { v: View? -> showAppCenter() })
        addNav(getString(R.string.ui_nav_maintenance), View.OnClickListener { v: View? -> showLibraryMaintenance() })
        addNav(getString(R.string.ui_nav_broadcast), View.OnClickListener { v: View? -> showTableBroadcast() })
        addNav(getString(R.string.ui_nav_settings), View.OnClickListener { v: View? -> showSettings() })

        val body = LinearLayout(this)
        body.setOrientation(LinearLayout.VERTICAL)
        body.setPadding(dp(14), 0, 0, 0)
        shell.addView(body, LinearLayout.LayoutParams(0, -1, 1f))

        val top = LinearLayout(this)
        top.setGravity(Gravity.CENTER_VERTICAL)
        top.setPadding(0, 0, 0, dp(6))
        header = label(getString(R.string.ui_header_home), 24, TEXT_WHITE)
        top.addView(header, LinearLayout.LayoutParams(0, dp(54), 1f))
        status = label(getString(R.string.ui_status_ready), 16, TEXT_DIM)
        status!!.setGravity(Gravity.RIGHT or Gravity.CENTER_VERTICAL)
        top.addView(status, LinearLayout.LayoutParams(dp(430), dp(54)))
        body.addView(top)
        // 顶栏底部分割线(原版风格)
        val topDivider = View(this)
        topDivider.setBackgroundColor(DIVIDER)
        body.addView(topDivider, LinearLayout.LayoutParams(-1, dp(1)))

        content = LinearLayout(this)
        content!!.setOrientation(LinearLayout.HORIZONTAL)
        body.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        progress = ProgressBar(this)
        progress!!.setVisibility(View.GONE)
        val pp = FrameLayout.LayoutParams(dp(42), dp(42), Gravity.BOTTOM or Gravity.RIGHT)
        pp.rightMargin = dp(24)
        pp.bottomMargin = dp(14)
        root.addView(progress, pp)

        // 将构建好的 shell 内容挂载到 main_activity 布局的 R.id.container 容器中
        // 注意:现在使用 MainFragment 加载原版布局,buildShell 的程序化 UI 不再直接添加到 container
        // 保留 buildShell 中的播放器、下载等核心组件初始化逻辑,但 UI 由 Fragment 管理
        val container = findViewById<FrameLayout?>(R.id.container)
        if (container != null) {
            // 不再添加程序化导航 UI,避免与 MainFragment 冲突
            // shell 中的播放器和下载组件仍通过 root 引用保持工作
        } else {
            setContentView(root)
        }
    }

    /**
     * 更新导航栏选中态视觉
     * @param index 要选中的导航项索引(0-based),-1 表示取消选中
     */
    private fun selectNav(index: Int) {
        currentNavIndex = index
        for (i in navButtons.indices) {
            val btn = navButtons.get(i)
            if (i == index) {
                // 选中态:半透明红背景 + 红色文字 + 左侧红色色条效果(通过 padding 模拟)
                btn.setBackgroundColor(NAV_SELECTED)
                btn.setTextColor(ACCENT_RED)
                btn.setPadding(dp(16), dp(6), dp(8), dp(6))
            } else {
                // 未选中:透明背景 + 白色文字
                btn.setBackgroundColor(Color.TRANSPARENT)
                btn.setTextColor(TEXT_WHITE)
                btn.setPadding(dp(8), dp(6), dp(8), dp(6))
            }
        }
    }

    private fun showHome() {
        if (content == null) return  // buildShell 未启用

        setPage("首页", getString(R.string.ui_a_0108))
        selectNav(0)
        if (content == null) return
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(0, -1, 1.25f))
        left.addView(sectionTitle(getString(R.string.ui_a_0143)))
        val grid = GridLayout(this)
        grid.setColumnCount(3)
        left.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        addTile(
            grid,
            getString(R.string.ui_a_0144),
            getString(R.string.ui_a_0145),
            View.OnClickListener { v: View? -> showSongs(KtvRaw.get(R.string.ui_a_0069)) })
        addTile(grid, getString(R.string.ui_a_0146), getString(R.string.ui_a_0147), View.OnClickListener { v: View? -> showRank() })
        addTile(grid, getString(R.string.ui_a_0148), getString(R.string.ui_a_0149), View.OnClickListener { v: View? -> showSingers() })
        addTile(
            grid,
            getString(R.string.ui_a_0150),
            getString(R.string.ui_a_0151),
            View.OnClickListener { v: View? -> showLanguages() })
        addTile(
            grid,
            getString(R.string.ui_a_0152),
            getString(R.string.ui_a_0153),
            View.OnClickListener { v: View? -> showWordCounts() })
        addTile(
            grid,
            getString(R.string.ui_a_0154),
            getString(R.string.ui_a_0155),
            View.OnClickListener { v: View? -> showSearch() })
        addTile(
            grid,
            getString(R.string.ui_a_0094),
            getString(R.string.ui_a_0156),
            View.OnClickListener { v: View? -> showFavorites() })
        addTile(
            grid,
            getString(R.string.ui_a_0157),
            getString(R.string.ui_a_0158),
            View.OnClickListener { v: View? -> showPlaylists() })
        addTile(grid, getString(R.string.ui_a_0159), getString(R.string.ui_a_0160), View.OnClickListener { v: View? -> showUdisk() })
        addTile(
            grid,
            getString(R.string.ui_a_0161),
            getString(R.string.ui_a_0162),
            View.OnClickListener { v: View? -> showDownloads() })

        addTile(
            grid,
            getString(R.string.ui_a_0163),
            getString(R.string.ui_p_vol_eq),
            View.OnClickListener { v: View? -> showTuningDialog() })
        addTile(
            grid,
            getString(R.string.ui_a_0165),
            atmosphere,
            View.OnClickListener { v: View? -> showAtmosphereDialog() })
        addTile(
            grid,
            getString(R.string.ui_a_0166),
            if (scoreEnabled) scoreSummary() else getString(R.string.ui_a_0167),
            View.OnClickListener { v: View? -> showScoreDialog() })
        addTile(
            grid,
            getString(R.string.ui_a_0168),
            if (recorder == null) getString(R.string.ui_a_0169) else getString(R.string.ui_a_0170),
            View.OnClickListener { v: View? -> showRecordings() })
        addTile(
            grid,
            getString(R.string.ui_a_0171),
            remoteUrl(),
            View.OnClickListener { v: View? -> showMobileRemoteDialog() })
        addTile(
            grid,
            getString(R.string.ui_a_0172),
            if (marqueeEnabled) marqueeText else getString(R.string.ui_a_0173),
            View.OnClickListener { v: View? -> showMarqueeSettings() })
        addTile(grid, getString(R.string.ui_a_0174), lightMode, View.OnClickListener { v: View? -> showDisco() })
        addTile(
            grid,
            getString(R.string.ui_a_0175),
            getString(R.string.ui_a_0176),
            View.OnClickListener { v: View? -> showLocalMovies() })
        addTile(
            grid,
            getString(R.string.ui_a_0177),
            remoteUrl(),
            View.OnClickListener { v: View? -> showNetworkDialog() })
        addTile(
            grid,
            getString(R.string.ui_a_0178),
            screenMode,
            View.OnClickListener { v: View? -> showScreenDialog() })
        addTile(
            grid,
            getString(R.string.ui_a_0179),
            getString(R.string.ui_a_0180),
            View.OnClickListener { v: View? -> showAppCenter() })
        addTile(
            grid,
            getString(R.string.ui_a_0181),
            getString(R.string.ui_a_0182),
            View.OnClickListener { v: View? -> showLibraryMaintenance() })
        addTile(
            grid,
            getString(R.string.ui_a_0183),
            if (tableBroadcastEnabled) tableBroadcastText else getString(R.string.ui_a_0184),
            View.OnClickListener { v: View? -> showTableBroadcast() })

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 0.8f))
        right.addView(sectionTitle(getString(R.string.ui_a_0185)))
        // 用 FrameLayout 包裹共享播放 Surface,以便叠加控制层
        val playerContainer = FrameLayout(this)
        player = KtvVideoView(this).also {
            it.bind(playbackEngine)
            playbackEngine.attach(it)
        }
        player!!.setBackgroundColor(Color.BLACK)
        playerContainer.addView(player, FrameLayout.LayoutParams(-1, -1))
        // 初始化播放器控制层并叠加到视频上方
        playerController = PlayerControllerView(this)
        initPlayerControllerCallbacks()
        playerContainer.addView(playerController, FrameLayout.LayoutParams(-1, -1))
        right.addView(playerContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        // 同步当前歌曲信息到控制层(若已在播放)
        if (currentSong != null) {
            playerController!!.updateSongInfo(currentSong)
            playerController!!.updatePlayState(playWhenPrepared)
            playerController!!.setVocalMode(originalVocal)
        }
        lyricCurrent = label(getString(R.string.ui_no_lyrics), 22, GOLD)
        lyricCurrent!!.setGravity(Gravity.CENTER)
        lyricNext = label("", 18, Color.LTGRAY)
        lyricNext!!.setGravity(Gravity.CENTER)
        right.addView(lyricCurrent, LinearLayout.LayoutParams(-1, dp(42)))
        right.addView(lyricNext, LinearLayout.LayoutParams(-1, dp(34)))
        val controls = LinearLayout(this)
        controls.setGravity(Gravity.CENTER_VERTICAL)
        controls.addView(
            button(getString(R.string.ui_a_0186), View.OnClickListener { v: View? -> togglePlay() }),
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )
        controls.addView(
            button(getString(R.string.ui_repeat), View.OnClickListener { v: View? -> replay() }),
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )
        controls.addView(
            button(getString(R.string.ui_next_song), View.OnClickListener { v: View? -> playNext() }),
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )
        controls.addView(button(getString(R.string.ui_a_0189), View.OnClickListener { v: View? ->
            loopOne = !loopOne
            toast(if (loopOne) getString(R.string.ui_a_0189) else getString(R.string.ui_a_0190))
        }), LinearLayout.LayoutParams(0, dp(50), 1f))
        controls.addView(
            button(
                vocalActionText(),
                View.OnClickListener { v: View? -> toggleOriginalVocal() }),
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )
        controls.addView(
            button(
                getString(R.string.ui_a_0191),
                View.OnClickListener { v: View? -> showTuningDialog() }),
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )
        controls.addView(
            button(
                if (recorder == null) getString(R.string.ui_a_0192) else getString(R.string.ui_a_0193),
                View.OnClickListener { v: View? -> toggleRecording() }),
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )
        controls.addView(
            button(
                getString(R.string.ui_a_0194),
                View.OnClickListener { v: View? -> showPlayerStatusDialog() }),
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )
        right.addView(controls)
        right.addView(sectionTitle(getString(R.string.ui_p_005) + orderQueue!!.size))
        queueList = listView()
        queueAdapter = tvAdapter()
        queueList!!.setAdapter(queueAdapter)
        queueList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            play(
                orderQueue.get(pos)
            )
        })
        right.addView(queueList, LinearLayout.LayoutParams(-1, 0, 1f))
        renderQueue()
    }

    private fun showSearch() {
        showSongs(KtvRaw.get(R.string.ui_a_0069))
    }

    fun showSongs(category: String?) {
        setPage("点歌", getString(R.string.ui_a_0196))
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(230), -1))
        left.addView(sectionTitle(getString(R.string.ui_a_0197)))
        // 分类按钮:优先使用数据库语种,回退到本地分类
        val cats: MutableList<String?> = ArrayList<String?>()
        if (library.muse.isAvailable()) {
            cats.add("全部")
            cats.addAll(library.muse.languages())
            cats.add("本地")
            cats.add("网络")
        } else {
            cats.addAll(library.categories())
        }
        for (item in cats) {
            left.addView(button(displayCategory(item), View.OnClickListener { v: View? ->
                val cat = item ?: ""
                if (library.muse.isAvailable() && ("本地" != cat) && ("网络" != cat)) {
                    browseMode = "language"
                    browseParam = cat
                    browsePage = 0
                    loadBrowsePage()
                } else {
                    filterSongs(cat, "")
                }
            }))
        }
        left.addView(button(getString(R.string.ui_a_0200), View.OnClickListener { v: View? -> fetchCatalog() }))

        val center = panel()
        content!!.addView(center, LinearLayout.LayoutParams(0, -1, 1f))
        val searchRow = LinearLayout(this)
        searchRow.setGravity(Gravity.CENTER_VERTICAL)
        search = EditText(this)
        search!!.setSingleLine(true)
        search!!.setHint(getString(R.string.ui_a_0201))
        search!!.setTextColor(Color.WHITE)
        search!!.setHintTextColor(Color.GRAY)
        search!!.setInputType(InputType.TYPE_CLASS_TEXT)
        search!!.setOnEditorActionListener(OnEditorActionListener { v: TextView?, actionId: Int, event: KeyEvent? ->
            filterSongs(category, search!!.getText().toString())
            true
        })
        searchRow.addView(search, LinearLayout.LayoutParams(0, dp(54), 1f))
        searchRow.addView(
            button(getString(R.string.ui_a_0202), View.OnClickListener { v: View? -> showKeyboardDialog() }),
            LinearLayout.LayoutParams(dp(110), dp(54))
        )
        searchRow.addView(
            button(
                getString(R.string.ui_a_0203),
                View.OnClickListener { v: View? ->
                    filterSongs(
                        category,
                        search!!.getText().toString()
                    )
                }), LinearLayout.LayoutParams(dp(110), dp(54))
        )
        center.addView(searchRow)
        // 分页控制栏
        val pageBar = LinearLayout(this)
        pageBar.setGravity(Gravity.CENTER_VERTICAL)
        pageBar.addView(
            button(getString(R.string.ui_prev_page), View.OnClickListener { v: View? -> prevPage() }),
            LinearLayout.LayoutParams(dp(110), dp(44))
        )
        pageInfo = label("", 14, GOLD)
        pageInfo!!.setGravity(Gravity.CENTER)
        pageBar.addView(pageInfo, LinearLayout.LayoutParams(0, dp(44), 1f))
        pageBar.addView(
            button(getString(R.string.ui_next_page), View.OnClickListener { v: View? -> nextPage() }),
            LinearLayout.LayoutParams(dp(110), dp(44))
        )
        center.addView(pageBar)
        songList = listView()
        songAdapter = tvAdapter()
        songList!!.setAdapter(songAdapter)
        songList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            handleSong(
                visibleSongs.get(pos)
            )
        })
        songList!!.setOnItemLongClickListener(OnItemLongClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            showSongActions(visibleSongs.get(pos))
            true
        })
        center.addView(songList, LinearLayout.LayoutParams(-1, 0, 1f))
        // 默认加载热门歌曲第一页
        if (library.muse.isAvailable()) {
            browseMode = "hot"
            browseParam = ""
            browsePage = 0
            loadBrowsePage()
        } else {
            filterSongs(category, "")
        }
    }

    private fun showSingers() {
        setPage("歌星", getString(R.string.ui_a_0206))
        content!!.removeAllViews()
        val groupPanel = panel()
        content!!.addView(groupPanel, LinearLayout.LayoutParams(dp(190), -1))
        groupPanel.addView(sectionTitle(getString(R.string.ui_a_0207)))

        val singerPanel = panel()
        content!!.addView(singerPanel, LinearLayout.LayoutParams(dp(340), -1))
        singerPanel.addView(sectionTitle(getString(R.string.ui_a_0208)))
        val singers = listView()
        val adapter = tvAdapter()
        singers.setAdapter(adapter)
        singerPanel.addView(singers, LinearLayout.LayoutParams(-1, 0, 1f))

        val songs = panel()
        content!!.addView(songs, LinearLayout.LayoutParams(0, -1, 1f))
        songs.addView(sectionTitle(getString(R.string.ui_a_0209)))
        // 分页控制栏
        val pageBar = LinearLayout(this)
        pageBar.setGravity(Gravity.CENTER_VERTICAL)
        pageBar.addView(
            button(getString(R.string.ui_prev_page), View.OnClickListener { v: View? -> prevPage() }),
            LinearLayout.LayoutParams(dp(100), dp(44))
        )
        pageInfo = label("", 13, GOLD)
        pageInfo!!.setGravity(Gravity.CENTER)
        pageBar.addView(pageInfo, LinearLayout.LayoutParams(0, dp(44), 1f))
        pageBar.addView(
            button(getString(R.string.ui_next_page), View.OnClickListener { v: View? -> nextPage() }),
            LinearLayout.LayoutParams(dp(100), dp(44))
        )
        songs.addView(pageBar)
        songList = listView()
        songAdapter = tvAdapter()
        songList!!.setAdapter(songAdapter)
        songList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            handleSong(
                visibleSongs.get(pos)
            )
        })
        songList!!.setOnItemLongClickListener(OnItemLongClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            showSongActions(visibleSongs.get(pos))
            true
        })
        songs.addView(songList, LinearLayout.LayoutParams(-1, 0, 1f))
        singers.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            val singer = adapter.getItem(pos)
            if (library.muse.isAvailable()) {
                browseMode = "singer"
                browseParam = singer
                browsePage = 0
                loadBrowsePage()
            } else {
                visibleSongs.clear()
                for (song in library.allSongs()) {
                    if (isVisibleSong(song) && singerName(song) == singer) visibleSongs.add(song)
                }
                renderSongs()
            }
        })
        // 歌手分类:优先使用数据库区域
        if (library.muse.isAvailable()) {
            val groups =
                arrayOf<String?>("全部歌手", "大陆", "港台", "外国", "中国", "男", "女", "组合")
            for (group in groups) {
                groupPanel.addView(
                    button(
                        displayCategory(group),
                        View.OnClickListener { v: View? ->
                            populateSingerListFromDb(
                                adapter,
                                group
                            )
                        })
                )
            }
            populateSingerListFromDb(adapter, "全部歌手")
        } else {
            val groups = arrayOf<String?>(
                "全部歌手",
                KtvRaw.get(R.string.ui_a_0213),
                KtvRaw.get(R.string.ui_a_0214),
                KtvRaw.get(R.string.ui_a_0077),
                KtvRaw.get(R.string.ui_a_0078),
                KtvRaw.get(R.string.ui_a_0079),
                KtvRaw.get(R.string.ui_a_0215),
                KtvRaw.get(R.string.ui_a_0080)
            )
            for (group in groups) {
                groupPanel.addView(
                    button(
                        displayCategory(group),
                        View.OnClickListener { v: View? ->
                            populateSingerList(
                                adapter,
                                group
                            )
                        })
                )
            }
            populateSingerList(adapter, KtvRaw.get(R.string.ui_a_0210))
        }
    }

    /**
     * 从数据库分页加载歌手列表。
     * 
     * @param adapter 歌手列表适配器
     * @param group   分类组名(全部歌手/大陆/港台/外国/中国/男/女/组合)
     */
    private fun populateSingerListFromDb(adapter: ArrayAdapter<String?>, group: String?) {
        if (!library.muse.isAvailable()) return
        adapter.clear()
        busy(true, getString(R.string.ui_a_0216))
        io.execute(Runnable {
            var area: String? = null
            var type: String? = null
            if (KtvRaw.get(R.string.ui_a_0072) == group) area = KtvRaw.get(R.string.ui_a_0072)
            else if (KtvRaw.get(R.string.ui_a_0077) == group) area = KtvRaw.get(R.string.ui_a_0077)
            else if (KtvRaw.get(R.string.ui_a_0211) == group) area = KtvRaw.get(R.string.ui_a_0211)
            else if (KtvRaw.get(R.string.ui_a_0212) == group) area = KtvRaw.get(R.string.ui_a_0212)
            else if (KtvRaw.get(R.string.ui_a_0073) == group) type = KtvRaw.get(R.string.ui_a_0073)
            else if (KtvRaw.get(R.string.ui_a_0074) == group) type = KtvRaw.get(R.string.ui_a_0074)
            else if (KtvRaw.get(R.string.ui_a_0075) == group) type = KtvRaw.get(R.string.ui_a_0075)
            val result: MutableList<Array<String?>> = library.muse.singers(area, type, 0, 200)
            main.post(Runnable {
                for (s in result) adapter.add(s[1]) // s[1] 是歌手名
                busy(false, getString(R.string.ui_p_006) + result.size + getString(R.string.ui_p_007))
            })
        })
    }

    private fun showLanguages() {
        setPage("语种", getString(R.string.ui_a_0219))
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(230), -1))
        left.addView(sectionTitle(getString(R.string.ui_a_0219)))
        val langs: MutableList<String?> = ArrayList<String?>()
        if (library.muse.isAvailable()) {
            langs.add(KtvRaw.get(R.string.ui_a_0069))
            langs.addAll(library.muse.languages())
        } else {
            langs.addAll(library.languages())
        }
        for (language in langs) {
            left.addView(button(displayCategory(language), View.OnClickListener { v: View? ->
                val lang = language
                if (library.muse.isAvailable()) {
                    browseMode = "language"
                    browseParam = lang
                    browsePage = 0
                    loadBrowsePage()
                } else {
                    filterLanguage(lang)
                }
            }))
        }

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle(getString(R.string.ui_a_0150)))
        // 分页控制栏
        val pageBar = LinearLayout(this)
        pageBar.setGravity(Gravity.CENTER_VERTICAL)
        pageBar.addView(
            button(getString(R.string.ui_prev_page), View.OnClickListener { v: View? -> prevPage() }),
            LinearLayout.LayoutParams(dp(110), dp(44))
        )
        pageInfo = label("", 14, GOLD)
        pageInfo!!.setGravity(Gravity.CENTER)
        pageBar.addView(pageInfo, LinearLayout.LayoutParams(0, dp(44), 1f))
        pageBar.addView(
            button(getString(R.string.ui_next_page), View.OnClickListener { v: View? -> nextPage() }),
            LinearLayout.LayoutParams(dp(110), dp(44))
        )
        right.addView(pageBar)
        songList = listView()
        songAdapter = tvAdapter()
        songList!!.setAdapter(songAdapter)
        songList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            handleSong(
                visibleSongs.get(pos)
            )
        })
        songList!!.setOnItemLongClickListener(OnItemLongClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            showSongActions(visibleSongs.get(pos))
            true
        })
        right.addView(songList, LinearLayout.LayoutParams(-1, 0, 1f))
        if (library.muse.isAvailable()) {
            browseMode = "language"
            browseParam = KtvRaw.get(R.string.ui_a_0069)
            browsePage = 0
            loadBrowsePage()
        } else {
            filterLanguage(KtvRaw.get(R.string.ui_a_0069))
        }
    }

    private fun showWordCounts() {
        setPage("字数", getString(R.string.ui_a_0220))
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(230), -1))
        left.addView(sectionTitle(getString(R.string.ui_a_0221)))
        val labels = arrayOf(getString(R.string.ui_a_0069), getString(R.string.ui_a_0222), getString(R.string.ui_a_0223), getString(R.string.ui_a_0224), getString(R.string.ui_a_0225), getString(R.string.ui_a_0226), getString(R.string.ui_a_0227))
        val values = arrayOf(KtvRaw.get(R.string.ui_a_0069), KtvRaw.get(R.string.ui_a_0222), KtvRaw.get(R.string.ui_a_0223), KtvRaw.get(R.string.ui_a_0224), KtvRaw.get(R.string.ui_a_0225), KtvRaw.get(R.string.ui_a_0226), KtvRaw.get(R.string.ui_a_0227))
        for (i in labels.indices) {
            left.addView(button(labels[i], View.OnClickListener { _ ->
                val g = values[i]
                if (library.muse.isAvailable()) {
                    val wc = if (KtvRaw.get(R.string.ui_a_0222) == g) 1 else if (KtvRaw.get(R.string.ui_a_0223) == g) 2 else if (KtvRaw.get(R.string.ui_a_0224) == g)
                        3
                    else
                        if (KtvRaw.get(R.string.ui_a_0225) == g) 4 else if (KtvRaw.get(R.string.ui_a_0226) == g) 5 else if (KtvRaw.get(R.string.ui_a_0227) == g) 6 else 0
                    browseMode = "wordcount"
                    browseParam = wc.toString()
                    browsePage = 0
                    loadBrowsePage()
                } else {
                    filterWordCount(g)
                }
            }))
        }

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle(getString(R.string.ui_a_0152)))
        // 分页控制栏
        val pageBar = LinearLayout(this)
        pageBar.setGravity(Gravity.CENTER_VERTICAL)
        pageBar.addView(
            button(getString(R.string.ui_prev_page), View.OnClickListener { v: View? -> prevPage() }),
            LinearLayout.LayoutParams(dp(110), dp(44))
        )
        pageInfo = label("", 14, GOLD)
        pageInfo!!.setGravity(Gravity.CENTER)
        pageBar.addView(pageInfo, LinearLayout.LayoutParams(0, dp(44), 1f))
        pageBar.addView(
            button(getString(R.string.ui_next_page), View.OnClickListener { v: View? -> nextPage() }),
            LinearLayout.LayoutParams(dp(110), dp(44))
        )
        right.addView(pageBar)
        songList = listView()
        songAdapter = tvAdapter()
        songList!!.setAdapter(songAdapter)
        songList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            handleSong(
                visibleSongs.get(pos)
            )
        })
        songList!!.setOnItemLongClickListener(OnItemLongClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            showSongActions(visibleSongs.get(pos))
            true
        })
        right.addView(songList, LinearLayout.LayoutParams(-1, 0, 1f))
        if (library.muse.isAvailable()) {
            browseMode = "wordcount"
            browseParam = "0"
            browsePage = 0
            loadBrowsePage()
        } else {
            filterWordCount(KtvRaw.get(R.string.ui_a_0069))
        }
    }

    private fun showRank() {
        setPage("排行", getString(R.string.ui_a_0228))
        content!!.removeAllViews()
        val panel = panel()
        content!!.addView(panel, LinearLayout.LayoutParams(-1, -1))
        panel.addView(sectionTitle(getString(R.string.ui_a_0146)))
        // 分页控制栏
        val pageBar = LinearLayout(this)
        pageBar.setGravity(Gravity.CENTER_VERTICAL)
        pageBar.addView(
            button(getString(R.string.ui_prev_page), View.OnClickListener { v: View? -> prevPage() }),
            LinearLayout.LayoutParams(dp(110), dp(44))
        )
        pageInfo = label("", 14, GOLD)
        pageInfo!!.setGravity(Gravity.CENTER)
        pageBar.addView(pageInfo, LinearLayout.LayoutParams(0, dp(44), 1f))
        pageBar.addView(
            button(getString(R.string.ui_next_page), View.OnClickListener { v: View? -> nextPage() }),
            LinearLayout.LayoutParams(dp(110), dp(44))
        )
        panel.addView(pageBar)
        songList = listView()
        songAdapter = tvAdapter()
        songList!!.setAdapter(songAdapter)
        songList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            handleSong(
                visibleSongs.get(pos)
            )
        })
        songList!!.setOnItemLongClickListener(OnItemLongClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            showSongActions(visibleSongs.get(pos))
            true
        })
        panel.addView(songList, LinearLayout.LayoutParams(-1, 0, 1f))
        if (library.muse.isAvailable()) {
            browseMode = "hot"
            browseParam = ""
            browsePage = 0
            loadBrowsePage()
        } else {
            visibleSongs.clear()
            for (song in library.allSongs()) {
                if (isVisibleSong(song)) visibleSongs.add(song)
            }
            Collections.sort<Song?>(
                visibleSongs,
                Comparator { a: Song?, b: Song? -> Integer.compare(b!!.playCount, a!!.playCount) })
            renderSongs()
        }
    }

    private fun showQueue() {
        setPage("已点", getString(R.string.ui_a_0229))
        content!!.removeAllViews()
        val panel = panel()
        content!!.addView(panel, LinearLayout.LayoutParams(-1, -1))
        val actions = LinearLayout(this)
        actions.addView(
            button(
                getString(R.string.ui_a_0230),
                View.OnClickListener { v: View? ->
                    if (!orderQueue!!.isEmpty()) play(
                        orderQueue.get(
                            0
                        )
                    )
                }), LinearLayout.LayoutParams(dp(150), dp(54))
        )
        actions.addView(
            button(getString(R.string.ui_next_song), View.OnClickListener { v: View? -> playNext() }),
            LinearLayout.LayoutParams(dp(110), dp(54))
        )
        actions.addView(
            button(
                getString(R.string.ui_a_0231),
                View.OnClickListener { v: View? -> moveSelectedQueueToTop() }),
            LinearLayout.LayoutParams(dp(110), dp(54))
        )
        actions.addView(
            button(getString(R.string.ui_a_0232), View.OnClickListener { v: View? -> removeSelectedQueue() }),
            LinearLayout.LayoutParams(dp(110), dp(54))
        )
        actions.addView(button(getString(R.string.ui_a_0233), View.OnClickListener { v: View? ->
            orderQueue!!.clear()
            renderQueue()
            saveState()
        }), LinearLayout.LayoutParams(dp(110), dp(54)))
        panel.addView(actions)
        queueList = listView()
        queueAdapter = tvAdapter()
        queueList!!.setAdapter(queueAdapter)
        queueList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            play(
                orderQueue!!.get(pos)
            )
        })
        panel.addView(queueList, LinearLayout.LayoutParams(-1, 0, 1f))
        renderQueue()
    }

    private fun showSang() {
        setPage("已唱", getString(R.string.ui_a_0234))
        content!!.removeAllViews()
        val panel = panel()
        content!!.addView(panel, LinearLayout.LayoutParams(-1, -1))
        panel.addView(sectionTitle(getString(R.string.ui_a_0235)))
        val list = listView()
        val adapter = tvAdapter()
        for (song in sangHistory) adapter.add(song.displayTitle())
        list.setAdapter(adapter)
        panel.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showDownloads() {
        setPage("下载", getString(R.string.ui_a_0236))
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(280), -1))
        left.addView(sectionTitle(getString(R.string.ui_a_0237)))
        left.addView(button(getString(R.string.ui_a_0238), View.OnClickListener { v: View? -> fetchCatalog() }))
        left.addView(button(getString(R.string.ui_a_0239), View.OnClickListener { v: View? -> showCatalogDialog() }))
        left.addView(
            button(
                getString(R.string.ui_a_0240),
                View.OnClickListener { v: View? -> refreshLibrary(null) })
        )
        left.addView(
            button(
                getString(R.string.ui_a_0241),
                View.OnClickListener { v: View? -> cancelAllDownloads() })
        )
        left.addView(button(getString(R.string.ui_a_0242), View.OnClickListener { v: View? ->
            for (i in downloads.indices.reversed()) {
                if (KtvRaw.get(R.string.ui_a_0110) == downloads.get(i).state) downloads.removeAt(i)
            }
            showDownloads()
        }))
        left.addView(button(getString(R.string.ui_a_0243), View.OnClickListener { v: View? ->
            for (i in downloads.indices.reversed()) {
                if (downloads.get(i).state.startsWith("失败") || "已取消" == downloads.get(i).state) downloads.removeAt(
                    i
                )
            }
            showDownloads()
        }))

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle(getString(R.string.ui_a_0246)))
        val list = listView()
        val adapter = tvAdapter()
        for (task in downloads) adapter.add(task.display())
        list.setAdapter(adapter)
        list.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            if (pos >= 0 && pos < downloads.size) {
                downloads.get(pos).cancelled = true
                downloads.get(pos).state = KtvRaw.get(R.string.ui_a_0247)
                showDownloads()
            }
        })
        right.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showUdisk() {
        setPage("U盘", getString(R.string.ui_a_0248))
        content!!.removeAllViews()
        val panel = panel()
        content!!.addView(panel, LinearLayout.LayoutParams(-1, -1))
        panel.addView(sectionTitle(getString(R.string.ui_a_0249)))
        panel.addView(
            label(
                getString(R.string.ui_p_import_hint, AppPaths.importDir.absolutePath, AppPaths.songsDir.absolutePath),
                20,
                Color.LTGRAY
            )
        )
        panel.addView(button(getString(R.string.ui_a_0160), View.OnClickListener { v: View? ->
            refreshLibrary(
                Runnable { showSongs(KtvRaw.get(R.string.ui_a_0198)) })
        }), LinearLayout.LayoutParams(dp(180), dp(58)))
        songList = listView()
        songAdapter = tvAdapter()
        songList!!.setAdapter(songAdapter)
        songList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            handleSong(
                visibleSongs.get(pos)
            )
        })
        panel.addView(songList, LinearLayout.LayoutParams(-1, 0, 1f))
        filterSongs(KtvRaw.get(R.string.ui_a_0198), "")
    }

    fun showSettings() {
        currentTabIndex = 8
        setupTabs()
        showSettingsPage()
    }

    /**
     * 退出确认弹窗(对齐原app风格)
     */
    private fun AlertDialog.showForTv(
        preferredButton: Int = DialogInterface.BUTTON_NEGATIVE,
        afterShow: (() -> Unit)? = null,
    ): AlertDialog {
        val sourcePage = currentPage
        val sourceView = this@MainActivity.window.decorView.findFocus()
        val sourceFocus = captureFocusBookmark(sourceView)
        setOnShowListener {
            listView?.apply {
                setSelector(R.drawable.bg_tv_dialog_choice_focus)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    defaultFocusHighlightEnabled = false
                }
            }
            window?.decorView?.let(TvFocusStyler::installTree)
            listOf(
                DialogInterface.BUTTON_NEGATIVE,
                DialogInterface.BUTTON_POSITIVE,
                DialogInterface.BUTTON_NEUTRAL,
            ).mapNotNull(::getButton).forEach(TvFocusStyler::installAction)
            val preferred = getButton(preferredButton)
                ?: getButton(DialogInterface.BUTTON_NEGATIVE)
                ?: getButton(DialogInterface.BUTTON_POSITIVE)
            preferred?.let {
                if (it.requestFocus() || it.requestFocusFromTouch()) {
                    it.refreshDrawableState()
                    it.jumpDrawablesToCurrentState()
                    it.invalidate()
                }
            }
            afterShow?.invoke()
        }
        setOnDismissListener {
            if (currentPage == sourcePage) restoreFocusBookmark(sourceFocus, sourceView)
        }
        show()
        return this
    }

    private fun AlertDialog.Builder.showForTv(
        preferredButton: Int = DialogInterface.BUTTON_NEGATIVE,
        afterShow: (() -> Unit)? = null,
    ): AlertDialog = create().showForTv(preferredButton, afterShow)

    /**
     * 弹窗分辨率自适应（在 [showForTv] 的 afterShow 里调用）。
     *
     * TV / 投影 / 盒子的分辨率与 density 差异很大（1280x720、1920x1080，density 1.0~2.0），
     * 固定 dp 尺寸在矮屏上会被屏幕裁掉。这里统一按当前屏幕可用区域收敛：
     * - 宽度取 `min(preferredWidthDp, 屏幕宽 * widthRatio)`；
     * - 高度不交给 `WRAP_CONTENT`，而是把内容所在的 [scroll] 高度限制为
     *   `屏幕高 * heightRatio - 24dp 边框余量`，内容更高时在弹窗内滚动。
     *
     * 这样内容放得下时窗口紧凑，放不下时滚动，任何分辨率下都不会被裁切。
     */
    private fun fitDialogToScreen(
        dialog: AlertDialog,
        scroll: View,
        content: View,
        preferredWidthDp: Int,
        widthRatio: Float = 0.94f,
        heightRatio: Float = 0.92f,
    ) {
        val metrics = resources.displayMetrics
        val availableWidth = (metrics.widthPixels * widthRatio).toInt()
        val availableHeight = (metrics.heightPixels * heightRatio).toInt()
        val width = if (preferredWidthDp > 0) {
            min(dp(preferredWidthDp), availableWidth)
        } else {
            availableWidth
        }

        // 先按最终宽度量一次内容的原始高度：放得下就紧凑，放不下才滚动。
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        // 窗口高度 = 内容 + 24dp 边框余量(与下方显式窗口高度一致), 别再虚留 chrome 空间。
        val maxContentHeight = (availableHeight - dp(24)).coerceAtLeast(dp(120))
        val contentHeight = content.measuredHeight.coerceAtMost(maxContentHeight)
        val params = scroll.layoutParams
        if (params != null) {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = contentHeight
            scroll.layoutParams = params
        } else {
            scroll.layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                contentHeight,
            )
        }
        // 显式设定窗口高度(内容+少量边框余量, 钳制在可用高度内):
        // 部分系统在 show() 后修改子 View 高度不会触发 WRAP_CONTENT 窗口重排,
        // 导致弹窗仍按原始内容高度摆位、底部被顶出屏幕 —— 显式高度可彻底规避。
        dialog.window?.apply {
            setGravity(Gravity.CENTER)
            setLayout(
                width,
                (contentHeight + dp(24)).coerceAtMost(availableHeight),
            )
        }
    }

    private fun AlertDialog.restoreSourceFocusOnDismiss(): AlertDialog {
        val sourcePage = currentPage
        val sourceView = this@MainActivity.window.decorView.findFocus()
        val sourceFocus = captureFocusBookmark(sourceView)
        setOnDismissListener {
            if (currentPage == sourcePage) restoreFocusBookmark(sourceFocus, sourceView)
        }
        return this
    }

    private fun showExitDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_exit_confirm_title))
            .setMessage(getString(R.string.ui_exit_confirm_msg))
            .setPositiveButton(
                getString(R.string.ui_exit_confirm_ok),
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int -> finish() })
            .setNegativeButton(getString(R.string.ui_cancel), null)
            .create()
            .showForTv(DialogInterface.BUTTON_POSITIVE)
    }

    /** 顶栏/键盘相关 占位字段  */
    private val key_abc: TextView? = null
    private val key_123: TextView? = null
    private val key_hand: TextView? = null

    /** 设置弹窗(对齐原app: 左侧菜单 + 右侧内容)  */
    private fun showSettingsDialog() {
        val root = getLayoutInflater().inflate(R.layout.dialog_settings, null)
        val btnClose = root.findViewById<TextView>(R.id.btn_settings_close)
        val menu = root.findViewById<ListView>(R.id.settings_menu)
        val content = root.findViewById<LinearLayout>(R.id.settings_content)

        val menuItems = arrayOf<String?>(
            KtvRaw.get(R.string.ui_a_0251), KtvRaw.get(R.string.ui_a_0252), KtvRaw.get(R.string.ui_a_0253), KtvRaw.get(R.string.ui_a_0254),
            KtvRaw.get(R.string.ui_a_0255), KtvRaw.get(R.string.ui_a_0256), KtvRaw.get(R.string.ui_a_0257), KtvRaw.get(R.string.ui_a_0258)
        )
        val adapter: ArrayAdapter<String?> =
            object : ArrayAdapter<String?>(this, R.layout.settings_menu_item, menuItems) {
                override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                    val v = super.getView(pos, cv, parent) as TextView
                    v.setTextSize(15f)
                    v.setMinHeight(dp(44))
                    v.setGravity(Gravity.CENTER_VERTICAL)
                    v.setPadding(dp(16), dp(10), dp(16), dp(10))
                    v.setTextColor(Color.rgb(225, 225, 245))
                    return v
                }
            }
        menu.setAdapter(adapter)
        // 点击左侧菜单渲染右侧内容
        menu.setOnItemClickListener(OnItemClickListener { parent: AdapterView<*>?, view: View?, position: Int, id: Long ->
            renderSettingsContent(content, position)
        })
        // 默认显示第一个
        renderSettingsContent(content, 0)

        val dialog = AlertDialog.Builder(this)
            .setView(root)
            .create()
        if (dialog.getWindow() != null) {
            dialog.getWindow()!!.setBackgroundDrawableResource(android.R.color.transparent)
        }
        btnClose.setOnClickListener(View.OnClickListener { v: View? -> dialog.dismiss() })
        dialog.restoreSourceFocusOnDismiss()
        dialog.setOnShowListener {
            menu.post {
                if (menu.childCount > 0) menu.getChildAt(0)?.requestFocus()
            }
        }
        dialog.show()
    }

    /** 渲染设置弹窗右侧内容  */
    private fun renderSettingsContent(content: LinearLayout, index: Int) {
        content.removeAllViews()
        when (index) {
            0 -> {
                // 演唱模式
                addSettingsTitle(content, getString(R.string.ui_a_0259))
                addSettingsOptions(
                    content,
                    getString(R.string.ui_a_0259),
                    arrayOf<String>(
                        KtvRaw.get(R.string.ui_a_0088), KtvRaw.get(R.string.ui_a_0260), KtvRaw.get(R.string.ui_a_0261), KtvRaw.get(R.string.ui_a_0262)
                    ),
                    arrayOf<String?>(null, null, KtvRaw.get(R.string.ui_a_0261), KtvRaw.get(R.string.ui_a_0262)),
                    singMode,
                    OnValueClickListener { v: View?, `val`: Any? ->
                        setSingMode((`val` as kotlin.String?)!!)
                    })
            }

            1 -> {
                // 音量与均衡器统一走「调音」弹窗（升降调在下一页单独设置）
                addSettingsTitle(content, getString(R.string.ui_a_0263))
                addSettingsItem(
                    content,
                    getString(R.string.ui_p_vol_eq2),
                    getString(R.string.ui_p_vol_eq_status, musicVolume, if (KtvEqualizer.enabled) getString(R.string.ui_a_0269) else getString(R.string.ui_a_0270)),
                    View.OnClickListener { v: View? -> showTuningDialog() })
                addSettingsItem(
                    content,
                    getString(R.string.ui_p_eq_preset),
                    KtvEqualizer.preset,
                    View.OnClickListener { v: View? -> showTuningDialog() })
                addSettingsItem(
                    content,
                    getString(R.string.ui_a_0264),
                    getString(R.string.ui_p_mic_hw),
                    View.OnClickListener { v: View? -> toast(getString(R.string.ui_p_008)) })
                addSettingsItem(
                    content,
                    getString(R.string.ui_a_0268),
                    if (originalVocal) getString(R.string.ui_a_0269) else getString(R.string.ui_a_0270),
                    View.OnClickListener { v: View? -> toggleOriginalVocal() })
            }

            2 -> {
                // 声道设置
                addSettingsTitle(content, getString(R.string.ui_a_0271))
                addSettingsOptions(
                    content,
                    getString(R.string.ui_a_0272),
                    arrayOf<String>(
                        getString(R.string.ui_a_0086), getString(R.string.ui_a_0273), getString(R.string.ui_a_0274), getString(R.string.ui_a_0275)
                    ),
                    arrayOf<String>(KtvRaw.get(R.string.ui_a_0086), KtvRaw.get(R.string.ui_a_0273), KtvRaw.get(R.string.ui_a_0274), KtvRaw.get(R.string.ui_a_0275)),
                    vocalChannelMode,
                    OnValueClickListener { v: View?, `val`: Any? ->
                        setVocalChannelMode((`val` as kotlin.String?)!!)
                    })
            }

            3 -> {
                // 音调控制
                addSettingsTitle(content, getString(R.string.ui_a_0276))
                addSettingsItem(content, getString(R.string.ui_a_0277), getString(R.string.ui_a_0278), View.OnClickListener { v: View? ->
                    tone = clamp(tone - 1, -5, 5)
                    player?.setTone(tone)
                    saveState()
                })
                addSettingsItem(content, getString(R.string.ui_a_0279), getString(R.string.ui_a_0280), View.OnClickListener { v: View? ->
                    tone = clamp(tone + 1, -5, 5)
                    player?.setTone(tone)
                    saveState()
                })
            }

            4 -> {
                // 画面设置
                addSettingsTitle(content, getString(R.string.ui_a_0281))
                addSettingsItem(content, getString(R.string.ui_a_0282), "16:9", null)
                addSettingsItem(content, getString(R.string.ui_a_0283), getString(R.string.ui_a_0284), null)
                addSettingsItem(content, getString(R.string.ui_a_0285), getString(R.string.ui_a_0286), null)
            }

            5 -> {
                // 跑马灯
                addSettingsTitle(content, getString(R.string.ui_a_0172))
                addSettingsItem(content, getString(R.string.ui_a_0287), getString(R.string.ui_a_0270), null)
                addSettingsItem(content, getString(R.string.ui_a_0288), getString(R.string.ui_a_0289), null)
                addSettingsItem(content, getString(R.string.ui_a_0290), getString(R.string.ui_a_0291), null)
            }

            6 -> {
                // 网络设置
                addSettingsTitle(content, getString(R.string.ui_a_0177))
                addSettingsItem(content, getString(R.string.ui_a_0292), getString(R.string.ui_a_0293), null)
                addSettingsItem(content, getString(R.string.ui_a_0294), "pub.cdn.cherryonline.cn", null)
                addSettingsItem(content, getString(R.string.ui_a_0295), "pub.mcdn.cherryonline.cn", null)
            }

            7 -> {
                // 关于
                addSettingsTitle(content, getString(R.string.ui_set_about))
                addSettingsItem(content, getString(R.string.ui_a_0298), getString(R.string.ui_a_0299), null)
                addSettingsItem(content, getString(R.string.ui_p_009), getString(R.string.ui_p_010), null)
                addSettingsItem(content, getString(R.string.ui_p_011), getString(R.string.ui_p_012), null)
                addSettingsItem(content, getString(R.string.ui_a_0300), BuildConfig.VERSION_NAME, null)
                addSettingsItem(
                    content,
                    getString(R.string.ui_sec_library),
                    if (library.muse.isAvailable()) getString(R.string.ui_a_0302) + library.muse.songCount() + getString(R.string.ui_a_0303) else getString(R.string.ui_a_0304),
                    null
                )
            }
        }
    }

    /** 设置项标题  */
    private fun addSettingsTitle(parent: LinearLayout, title: String?) {
        val tv = TextView(this)
        tv.setText(title)
        tv.setTextSize(17f)
        tv.setTextColor(Color.rgb(0x27, 0xCC, 0xA4))
        tv.setTextSize(17f)
        tv.setPadding(dp(8), dp(8), dp(8), dp(12))
        tv.setTypeface(null, Typeface.BOLD)
        parent.addView(
            tv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    /** 设置项 - 单行(label + value, 点击有 action)  */
    private fun addSettingsItem(
        parent: LinearLayout,
        label: String?,
        value: String?,
        onClick: View.OnClickListener?
    ) {
        val row = LinearLayout(this)
        row.setOrientation(LinearLayout.HORIZONTAL)
        row.setGravity(Gravity.CENTER_VERTICAL)
        row.setBackgroundResource(R.drawable.bg_settings_item)
        row.setPadding(dp(14), dp(12), dp(14), dp(12))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(8)
        parent.addView(row, lp)
        val l = TextView(this)
        l.setText(label)
        l.setTextSize(15f)
        l.setTextColor(Color.rgb(225, 225, 245))
        row.addView(l, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val r = TextView(this)
        r.setText(value)
        r.setTextSize(15f)
        r.setTextColor(Color.rgb(0xFF, 0xD5, 0x6B))
        row.addView(
            r,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        if (onClick != null) {
            row.setClickable(true)
            row.setOnClickListener(onClick)
        }
    }

    /** 选项值点击回调 (传 value)  */
    private fun interface OnValueClickListener {
        fun onClick(v: View?, value: Any?)
    }

    /** 设置项 - 选项组(单选)  */
    private fun addSettingsOptions(
        parent: LinearLayout,
        title: String?,
        options: Array<out String?>,
        values: Array<out Any?>,
        current: Any?,
        onClick: OnValueClickListener
    ) {
        val row = LinearLayout(this)
        row.setOrientation(LinearLayout.VERTICAL)
        row.setBackgroundResource(R.drawable.bg_settings_item)
        row.setPadding(dp(14), dp(10), dp(14), dp(10))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(8)
        parent.addView(row, lp)
        val l = TextView(this)
        l.setText(title)
        l.setTextSize(15f)
        l.setTextColor(Color.rgb(225, 225, 245))
        row.addView(
            l,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        // 选项按钮横向布局
        val btnRow = LinearLayout(this)
        btnRow.setOrientation(LinearLayout.HORIZONTAL)
        btnRow.setPadding(0, dp(8), 0, 0)
        row.addView(
            btnRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        for (i in options.indices) {
            val opt = options[i]
            val `val` = values[i]
            val btn = TextView(this)
            btn.setText(opt)
            val isOn = `val` != null && `val` == current
            btn.setBackgroundResource(if (isOn) R.drawable.bg_btn_toggle_on else R.drawable.bg_btn_toggle_off)
            btn.setTextColor(if (isOn) Color.WHITE else Color.rgb(204, 204, 204))
            btn.setTextSize(13f)
            btn.setGravity(Gravity.CENTER)
            btn.setPadding(dp(10), dp(8), dp(10), dp(8))
            val bLp = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            bLp.leftMargin = if (i == 0) 0 else dp(4)
            bLp.rightMargin = if (i == options.size - 1) 0 else dp(4)
            btnRow.addView(btn, bLp)
            val fv = `val`
            val fi = i
            btn.setOnClickListener(View.OnClickListener { v: View? ->
                if (fv != null) onClick.onClick(v, fv)
                // 更新按钮状态
                for (j in 0 until btnRow.getChildCount()) {
                    val sib = btnRow.getChildAt(j) as TextView
                    val isOn2 = j == fi
                    sib.setBackgroundResource(if (isOn2) R.drawable.bg_btn_toggle_on else R.drawable.bg_btn_toggle_off)
                    sib.setTextColor(if (isOn2) Color.WHITE else Color.rgb(204, 204, 204))
                }
            })
        }
    }

    private fun toggleOriginalVocal() {
        setOriginalVocalMode(!originalVocal)
    }

    /** 按按钮表达的目标模式切换，提示框和实际声道使用同一个目标值。 */
    private fun setOriginalVocalMode(targetOriginal: Boolean) {
        val shouldPlay = playWhenPrepared
        originalVocal = targetOriginal
        applyPlaybackMode()
        saveState()
        showPlaybackModeNotice(if (targetOriginal) R.string.ui_a_0629 else R.string.ui_a_0630)
        // 同步状态到控制层
        if (playerController != null) {
            playerController!!.setVocalMode(originalVocal)
        }
        // 更新 TV 布局底部控制栏
        if (textVocalMode != null) {
            textVocalMode.setText(vocalActionText())
        }
        updateBottomBar(currentSong, shouldPlay)
    }

    /** 控制按钮显示下一次动作；播放框提示显示本次切换后实际生效的模式。 */
    private fun vocalActionText(): String = if (originalVocal) getString(R.string.ui_a_0306) else getString(R.string.ui_a_0305)

    /** 语言按钮显示「点击后将切换到的语言」：中文界面显示 EN，英文界面显示「中文」。 */
    private fun refreshLanguageButtonText() {
        val label = if (KtvApplication.isEnglish()) {
            getString(R.string.ui_lang_short_zh)
        } else {
            getString(R.string.ui_lang_short_en)
        }
        btnTopLanguage?.text = label
        btnTopLanguage?.contentDescription = getString(R.string.ui_set_language)
    }

    /**
     * 语言切换会重建 Activity 并释放播放器：切换前记录当前歌曲的进度与播放/暂停状态，
     * 重建后的 onCreate 会从原位置恢复，避免从头重放。
     */
    private fun switchLanguageKeepingPlayback(toEnglish: Boolean) {
        LanguageSwitchPlayback.capture(currentSong, playbackPosition, playWhenPrepared)
        KtvApplication.switchLanguage(toEnglish)
    }

    private fun vocalActionIcon(): Int = if (originalVocal) {
        R.drawable.ott_ic_ctrl_music_accomp
    } else {
        R.drawable.ott_ic_ctrl_music_origin
    }

    /**
     * 初始化播放器控制层的回调。
     * 
     * 
     * 将 [PlayerControllerView] 的按钮事件路由到 MainActivity 的现有播放逻辑:
     * 
     *  * `onPlayPause` → [.togglePlay]
     *  * `onPrev` / `onNext` → [.playPrevSong] / [.playNext]
     *  * `onVocalSwitch` → 使用 [VocalSwitchHelper] 切换原唱/伴唱声道
     *  * `onVolume` → [.showTuningDialog]
     *  * `onFullScreen` → [.showFullScreenPlayer]
     * 
     */
    private fun initPlayerControllerCallbacks() {
        if (playerController == null) return
        playerController!!.setOnControlListener(object : OnControlListener {
            override fun onPlayPause() {
                togglePlay()
            }

            override fun onPrev() {
                playPrevSong()
            }

            override fun onNext() {
                playNext()
            }

            override fun onVocalSwitch(original: Boolean) {
                setOriginalVocalMode(original)
            }

            override fun onVolume() {
                showTuningDialog()
            }

            override fun onFullScreen() {
                showFullScreenPlayer()
            }
        })
    }

    /**
     * 进入全屏播放界面。
     * 
     * 
     * 加载 [FullScreenPlayerFragment] 到 `R.id.container` 容器,
     * 替换当前 MainFragment,并加入回退栈以便返回。
     */
    fun showFullScreenPlayer() {
        if (player == null) player = videoBackground
        if (isFullScreen || player == null || fullScreenContainer == null) return
        main.removeCallbacks(autoFullscreenRunnable)
        playerReturnSurface = player
        playerReturnHost = activePlayerHost
        playerTouchOverlay?.visibility = View.GONE

        fullScreenContainer!!.removeAllViews()
        fullScreenContainer!!.setBackgroundColor(Color.TRANSPARENT)
        fullScreenContainer!!.setOnClickListener {
            setFullScreenChromeVisible(!fullScreenChromeVisible)
        }

        val header = LinearLayout(this)
        header.setGravity(Gravity.CENTER_VERTICAL)
        header.setPadding(dp(12), dp(4), dp(12), dp(4))
        header.setBackgroundColor(Color.TRANSPARENT)
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.ktv_logo)
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER)
        header.addView(logo, LinearLayout.LayoutParams(dp(42), dp(42)))
        val song = label(currentAndNextText(), 18, Color.WHITE)
        fullScreenSongInfo = song
        header.addView(song, LinearLayout.LayoutParams(0, dp(48), 1f))
        // 播放页面右上角「网络未连接」提示(仅断网时显示, 网络恢复自动消失)。
        val networkBadge = label(getString(R.string.ui_p_013), 14, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(11).toFloat()
                setColor(Color.rgb(198, 40, 74))
                setStroke(dp(1), Color.argb(140, 255, 255, 255))
            }
            includeFontPadding = false
            visibility = if (KtvNetworkMonitor.online) View.GONE else View.VISIBLE
        }
        fullScreenNetworkBadge = networkBadge
        header.addView(
            networkBadge,
            LinearLayout.LayoutParams(dp(if (compactUi) 108 else 124), dp(28)).apply { marginEnd = dp(8) },
        )
        val back = label(getString(R.string.ui_a_0307), 15, Color.WHITE)
        back.setGravity(Gravity.CENTER)
        back.setBackgroundResource(R.drawable.bg_btn_glass)
        back.setOnClickListener(View.OnClickListener { v: View? -> exitFullScreenPlayer() })
        back.visibility = View.GONE
        header.addView(back, LinearLayout.LayoutParams(dp(76), dp(36)))
        val headerParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52), Gravity.TOP
        )
        fullScreenContainer!!.addView(header, headerParams)

        val controls = LinearLayout(this)
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER
        controls.clipChildren = false
        controls.clipToPadding = false
        controls.setPadding(dp(12), dp(5), dp(12), dp(5))
        // 半透明深色圆角底衬: 保证图标/文字压在明亮 MV 画面上也清晰可读。
        controls.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.argb(135, 0, 0, 0))
        }
        val controlItems = ArrayList<View>(7)

        // 右上角扫码二维码浮层宽约 88/106dp 且高程更高, 会盖住控制行右端;
        // 两侧对称预留相同宽度(保持整行视觉居中), 屏幕放不下时等比收窄每个按钮,
        // 保证 7 个控制项完整可见、整行居中、永不与二维码重叠。
        // 40dp = 行内边距 16 + 6 个按钮间 margin 24。
        val qrReserve = dp(if (compactUi) 94 else 112)
        val screenW = fullScreenContainer?.width?.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val controlItemW = minOf(
            dp(78),
            (screenW - qrReserve * 2 - dp(40)) / 7,
        ).coerceAtLeast(dp(52))

        fun addControl(iconRes: Int, text: String, action: () -> Unit): Pair<TextView, ImageView> {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isFocusable = true
                isFocusableInTouchMode = true
                setBackgroundColor(Color.TRANSPARENT)
                id = View.generateViewId()
                clipChildren = false
                clipToPadding = false
            }
            val icon = ImageView(this).apply {
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                alpha = 0.95f
            }
            val caption = label(text, if (compactUi) 13 else 14, Color.WHITE).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
            }
            item.addView(icon, LinearLayout.LayoutParams(dp(28), dp(30)))
            item.addView(caption, LinearLayout.LayoutParams(-1, dp(24)))
            item.setOnClickListener {
                action()
                if (isFullScreen) setFullScreenChromeVisible(true)
            }
            installPressFeedback(item)
            controlItems.add(item)
            val params = LinearLayout.LayoutParams(controlItemW, dp(68))
            params.setMargins(dp(2), 0, dp(2), 0)
            controls.addView(item, params)
            return caption to icon
        }

        addControl(R.drawable.ic_return_order, getString(R.string.ui_a_0308)) { exitFullScreenPlayer() }
        addControl(R.drawable.ott_ic_ctrl_orderlist, getString(R.string.ui_a_0229)) {
            exitFullScreenPlayer()
            showOrderListPage()
        }
        addControl(
            vocalActionIcon(),
            vocalActionText()
        ) {
            toggleOriginalVocal()
        }.also { (text, icon) -> fullScreenVocalText = text; fullScreenVocalIcon = icon }
        addControl(R.drawable.ott_ic_ctrl_switch_song, getString(R.string.ui_a_0188)) { playNext() }
        addControl(
            if (playWhenPrepared) R.drawable.ott_ic_ctrl_pause else R.drawable.ott_ic_ctrl_play,
            if (playWhenPrepared) getString(R.string.ui_a_0309) else getString(R.string.ui_a_0310)
        ) { togglePlay() }.also { (text, icon) -> fullScreenPauseText = text; fullScreenPauseIcon = icon }
        addControl(R.drawable.ott_ic_ctrl_replay, getString(R.string.ui_a_0187)) { replay() }
        addControl(R.drawable.ott_ic_ctrl_volume, getString(R.string.ui_a_0311)) { showTuningDialog() }

        controlItems.forEachIndexed { index, item ->
            item.nextFocusLeftId = controlItems.getOrNull(index - 1)?.id ?: item.id
            item.nextFocusRightId = controlItems.getOrNull(index + 1)?.id ?: item.id
            item.nextFocusUpId = item.id
            item.nextFocusDownId = item.id
        }

        val controlsParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(74), Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        controlsParams.topMargin = dp(48)
        fullScreenContainer!!.addView(controls, controlsParams)
        fullScreenControls = controls

        val progressRow = LinearLayout(this)
        progressRow.orientation = LinearLayout.HORIZONTAL
        progressRow.gravity = Gravity.CENTER_VERTICAL
        progressRow.setPadding(dp(18), dp(4), dp(18), dp(4))
        progressRow.setBackgroundColor(Color.argb(155, 0, 0, 0))

        fullScreenSeekBar = SeekBar(this).apply {
            max = 1
            isFocusable = true
            setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        fullScreenTime?.text = formatPlaybackTime(progress) + " / " +
                            formatPlaybackTime(seekBar?.max ?: 0)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    fullScreenSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val position = seekBar?.progress ?: 0
                    runCatching {
                        player?.seekTo(position)
                        prepareExternalVocal(currentSong, position)
                    }
                    fullScreenSeeking = false
                    updateLyricView()
                    persistRuntimeState()
                }
            })
        }
        progressRow.addView(fullScreenSeekBar, LinearLayout.LayoutParams(0, dp(48), 1f))
        fullScreenTime = label("00:00 / 00:00", 14, Color.WHITE).apply {
            gravity = Gravity.CENTER
        }
        progressRow.addView(fullScreenTime, LinearLayout.LayoutParams(dp(132), dp(48)))
        fullScreenContainer!!.addView(
            progressRow,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58), Gravity.BOTTOM
            )
        )
        fullScreenProgressRow = progressRow

        isFullScreen = true
        fullScreenContainer!!.setVisibility(View.VISIBLE)
        expandPlayerToFullScreen()
        updateFullScreenQrOverlay()
        setFullScreenChromeVisible(true)
        updateFullScreenProgress()
    }

    private fun exitFullScreenPlayer() {
        if (!isFullScreen || player == null || fullScreenContainer == null) return
        fullScreenContainer!!.removeAllViews()
        fullScreenContainer!!.setOnClickListener(null)
        fullScreenContainer!!.setVisibility(View.GONE)
        isFullScreen = false
        playerReturnHost?.let {
            activePlayerHost = it
            applyPlayerBounds(it)
        }
        playerReturnSurface = null
        playerReturnHost = null
        fullScreenPauseText = null
        fullScreenVocalText = null
        fullScreenPauseIcon = null
        fullScreenVocalIcon = null
        fullScreenSongInfo = null
        fullScreenSeekBar = null
        fullScreenTime = null
        fullScreenSeeking = false
        fullScreenControls = null
        fullScreenProgressRow = null
        fullScreenQrOverlay = null
        fullScreenNetworkBadge = null
        fullScreenChromeVisible = false
        main.removeCallbacks(hideFullScreenChromeRunnable)
        scheduleAutoFullscreen()
    }

    private fun setFullScreenChromeVisible(visible: Boolean) {
        if (!isFullScreen && !visible) return
        fullScreenChromeVisible = visible
        fullScreenControls?.visibility = if (visible) View.VISIBLE else View.GONE
        fullScreenProgressRow?.visibility = if (visible) View.VISIBLE else View.GONE
        // 右上角二维码全屏时常显, 不跟随控制层隐藏(与 ori 一致)。
        fullScreenQrOverlay?.visibility = View.VISIBLE
        main.removeCallbacks(hideFullScreenChromeRunnable)
        if (visible && isFullScreen) {
            val controls = fullScreenControls
            if (controls != null && !controls.hasFocus()) {
                controls.getChildAt(0)?.requestFocus()
            }
            main.postDelayed(hideFullScreenChromeRunnable, 5000L)
        }
    }

    private fun updateFullScreenProgress() {
        if (!isFullScreen || player == null || fullScreenSeekBar == null) return
        runCatching {
            val duration = max(0, player!!.duration)
            val position = max(0, player!!.currentPosition)
            if (duration > 0) fullScreenSeekBar!!.max = duration
            if (!fullScreenSeeking) fullScreenSeekBar!!.progress = min(position, max(1, duration))
            fullScreenTime?.text = formatPlaybackTime(position) + " / " + formatPlaybackTime(duration)
        }
    }

    private fun formatPlaybackTime(milliseconds: Int): String {
        val totalSeconds = max(0, milliseconds) / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return (if (minutes < 10) "0" else "") + minutes + ":" +
            (if (seconds < 10) "0" else "") + seconds
    }

    /**
     * 供 [FullScreenPlayerFragment] 调用打开音量控制面板。
     */
    fun showAudioControlFromFragment() {
        showTuningDialog()
    }

    fun playPrevSong() {
        if (orderQueue!!.isEmpty() || currentSong == null) return
        val index = orderQueue.indexOf(currentSong!!)
        if (index <= 0) {
            // 当前歌曲不在队列或已是首曲,从已唱历史取上一首
            if (!sangHistory.isEmpty()) {
                val prev = sangHistory.get(0)
                orderQueue.add(0, prev)
                sangHistory.removeAt(0)
                saveState()
                play(prev)
            }
            return
        }
        val prev = orderQueue.get(index - 1)
        // 将当前歌曲放回队首
        orderQueue.removeAt(index)
        orderQueue.add(index, prev)
        play(prev)
    }

    private fun setSingMode(mode: String) {
        singMode = mode
        if (KtvRaw.get(R.string.ui_a_0260) == mode) {
            recordEnabled = true
            scoreEnabled = false
        } else if (KtvRaw.get(R.string.ui_a_0261) == mode) {
            recordEnabled = true
            scoreEnabled = true
        } else if (KtvRaw.get(R.string.ui_a_0262) == mode) {
            recordEnabled = true
            scoreEnabled = false
            atmosphere = KtvRaw.get(R.string.ui_a_0262)
        }
        applyPlaybackMode()
        saveState()
        toast(getString(R.string.ui_a_0316) + singMode)
    }

    private fun setVocalChannelMode(mode: String) {
        vocalChannelMode = mode
        applyPlaybackMode()
        saveState()
        toast(getString(R.string.ui_a_0317) + vocalChannelMode)
    }
    // ===== 均衡器 =====

    /** 把持久化的 "1.0,-2.0,..." 解析成频段增益数组。 */
    private fun parseEqLevels(raw: String?): FloatArray {
        if (raw.isNullOrBlank()) return FloatArray(0)
        return raw.split(',').mapNotNull { it.trim().toFloatOrNull() }.toFloatArray()
    }

    /**
     * 均衡器滑杆的刻度数。
     *
     * 步进 = 设备增益范围 / 该刻度数，这样刻度会随设备自适应，同时保证 **0 dB 一定落在
     * 某个刻度上**（若写死 2 dB 步进，-15~+15 的范围里就没有 0 dB 这一档，无法调到"平坦"）。
     */
    private val eqSliderSteps = 30

    private fun eqLevelText(level: Float): String =
        (if (level > 0f) "+" else "") + String.format(Locale.ROOT, "%.1f", level)

    /**
     * 让均衡器挂到当前所有实际播放会话上。
     *
     * 会话 id 随歌曲变化(IJK 每次 prepare 都可能重建 AudioTrack)，因此由 500ms 的
     * `lyricTicker` 周期调用：新会话自动挂上，消失的会话延迟释放。
     */
    private fun syncEqualizerSessions() {
        if (KtvEqualizer.attachedSessionCount() == 0 && !KtvEqualizer.enabled) return
        KtvEqualizer.syncSessions(
            listOf(
                player?.audioSessionId() ?: 0,
                vocalPlayer?.audioSessionId ?: 0,
                embeddedVocalEngine?.audioSessionId() ?: 0,
            ),
        )
    }

    /** 均衡器开关/参数变化后的统一收尾：立刻挂载 + 落盘。 */
    private fun applyEqualizerSettings() {
        syncEqualizerSessions()
        saveState()
    }
    /**
     * 「调音」弹窗 —— 播放界面与主界面右上角共用的**唯一**调音入口。
     *
     * 自上而下（全部即时生效，所以没有「完成 / 确认」按钮）：
     *   1. 音量：横向控制杆 —— App 内部输出音量，不改动系统音量；
     *   2. 均衡器开关（默认关闭）；
     *   3. 均衡器预设：自定义（用户改动频段后自动保存）/ 流行 / 摇滚 / 舞曲；
     *   4. EQ 频段调节：竖向控制杆（频段数与增益范围取自设备真实能力）。
     */
    /**
     * 把均衡器预设的内部中文键转成跟随语言的显示文案。
     * 内部键（PRESETS / applyPreset / markCustomPreset 的比较）仍用固定中文，
     * 仅显示层用本方法，切语言不影响预设逻辑。
     */
    private fun displayPreset(name: String?): String = when (name) {
        KtvEqualizer.CUSTOM_PRESET -> getString(R.string.ui_preset_custom)
        KtvRaw.get(R.string.ui_a_0319) -> getString(R.string.ui_a_0319)
        KtvRaw.get(R.string.ui_a_0321) -> getString(R.string.ui_a_0321)
        KtvRaw.get(R.string.ui_a_0854) -> getString(R.string.ui_a_0854)
        else -> name ?: ""
    }

    private fun showTuningDialog() {
        val caps = KtvEqualizer.capabilities(this)
        val bandLabels = KtvEqualizer.bandLabels(caps)
        val working = KtvEqualizer.levelsSnapshot().let { saved ->
            FloatArray(caps.bandCount) { saved.getOrElse(it) { 0f } }
        }
        // 分辨率自适应：**文字行全部 wrap_content**（高度由文本决定，永不被压扁/裁字），
        // 只有均衡器频段行是可伸缩的 —— 频段行高 = (可用高度 - 其余固定行) / 频段数，
        // 全部内容一次放下，不需要滚动。
        val metrics = resources.displayMetrics
        val screenHeightDp = metrics.heightPixels / metrics.density
        val compact = screenHeightDp < 620f
        // 紧凑屏固定行合计(dp)：上下边距18 + 标题23 + 分隔线(4+2) + 音量标题18 + 音量杆30 +
        // 开关(4+25) + 预设(4+25) ≈ 153；普通屏 ≈ 252。
        val fixedRowsDp = if (compact) 153f else 252f
        val dialogBudgetDp = screenHeightDp * (if (compact) 0.80f else 0.92f)
        val bandCount = caps.bandCount.coerceAtLeast(1)
        var bandRowHeight = dp(
            ((dialogBudgetDp - fixedRowsDp) / bandCount).coerceIn(if (compact) 24f else 38f, 56f).toInt(),
        )
        Log.i(
            TAG,
            "tuning dialog adaptive: screen=${screenHeightDp}dp compact=$compact " +
                "budget=${dialogBudgetDp}dp fixed=${fixedRowsDp}dp bandRows=$bandCount row=${bandRowHeight}px",
        )

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_tuning_dialog)
            setPadding(
                dp(if (compact) 16 else 26),
                dp(if (compact) 8 else 14),
                dp(if (compact) 16 else 26),
                dp(if (compact) 10 else 16),
            )
        }
        TvFocusStyler.suppressFill(panel)

        fun pill(text: String, action: () -> Unit): TextView = TextView(this).apply {
            this.text = text
            textSize = if (compact) 13f else 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(context, R.drawable.bg_btn_glass)
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(if (compact) 4 else 6), dp(10), dp(if (compact) 4 else 6))
            setOnClickListener { action() }
            TvFocusStyler.installAction(this)
        }

        // ── 标题 ──
        val titleRow = FrameLayout(this)
        titleRow.addView(
            label(getString(R.string.ui_tune), if (compact) 17 else 23, Color.WHITE).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                typeface = Typeface.DEFAULT_BOLD
            },
            FrameLayout.LayoutParams(-1, -2),
        )
        val close = label("×", 22, Color.argb(153, 255, 255, 255)).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            // 圆形底衬让关闭按钮更像可点目标, 而不是一行漂浮的字符。
            background = ContextCompat.getDrawable(context, R.drawable.bg_close_circle)
            TvFocusStyler.installAction(this)
        }
        titleRow.addView(
            close,
            FrameLayout.LayoutParams(dp(44), dp(38), Gravity.END or Gravity.CENTER_VERTICAL),
        )
        panel.addView(titleRow, LinearLayout.LayoutParams(-1, -2))
        // 标题下的金色渐变分隔线: 两端淡出, 提升弹窗精致感且几乎不占高度。
        panel.addView(
            View(this).apply { background = ContextCompat.getDrawable(context, R.drawable.bg_dialog_divider) },
            LinearLayout.LayoutParams(-1, dp(2)).apply { topMargin = dp(if (compact) 4 else 6) },
        )

        // ── 1. 音量：横向控制杆（即时生效）──
        // 交互：遥控器按「确认」进入调节态后才能左右调；触控必须按住滑块才能拖动。
        val volumeTitle = label(getString(R.string.ui_p_078, musicVolume), if (compact) 13 else 16, Color.WHITE).apply {
            typeface = Typeface.DEFAULT_BOLD
        }
        panel.addView(volumeTitle, LinearLayout.LayoutParams(-1, -2))
        var lastVolumeAppliedAt = 0L
        val volumeBar = EqSlider(this).apply {
            setRange(0f, 100f, 100)
            setLevel(musicVolume.toFloat())
            onLevelChanged = { value, fromUser ->
                if (fromUser) {
                    musicVolume = Math.round(value).coerceIn(0, 100)
                    volumeTitle.text = getString(R.string.ui_p_079, musicVolume)
                    // 按 100ms 节流即时生效，避免每一步都重建音轨。
                    val now = System.currentTimeMillis()
                    if (now - lastVolumeAppliedAt >= 100L) {
                        lastVolumeAppliedAt = now
                        applyPlaybackMode()
                    }
                }
            }
            onInteractionFinished = {
                applyPlaybackMode()
                saveState()
            }
        }
        panel.addView(volumeBar, LinearLayout.LayoutParams(-1, dp(if (compact) 30 else 44)))

        // ── 2. 均衡器开关（默认关闭）──
        lateinit var eqSwitch: TextView
        lateinit var eqArea: LinearLayout
        fun renderEqEnabled() {
            eqSwitch.text = if (KtvEqualizer.enabled) getString(R.string.ui_p_016) else getString(R.string.ui_p_017)
            eqSwitch.setTextColor(if (KtvEqualizer.enabled) Color.rgb(39, 204, 164) else Color.WHITE)
            // 关闭时预设/频段整体降透明度，直观表达"当前不生效"。
            eqArea.alpha = if (KtvEqualizer.enabled) 1f else 0.45f
        }
        eqSwitch = pill(if (KtvEqualizer.enabled) getString(R.string.ui_p_016) else getString(R.string.ui_p_017)) {
            KtvEqualizer.setEnabled(!KtvEqualizer.enabled)
            renderEqEnabled()
            applyEqualizerSettings()
            toast(if (KtvEqualizer.enabled) getString(R.string.ui_p_018) else getString(R.string.ui_p_019))
        }
        panel.addView(
            eqSwitch,
            LinearLayout.LayoutParams(dp(if (compact) 170 else 200), -2).apply {
                topMargin = dp(if (compact) 4 else 8)
            },
        )

        eqArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(eqArea, LinearLayout.LayoutParams(-1, -2))

        // ── 3. 均衡器预设 ──
        val presetButtons = LinkedHashMap<String, TextView>()
        fun renderPresets() {
            presetButtons.forEach { (name, view) ->
                val active = name == KtvEqualizer.preset
                view.setTextColor(if (active) Color.rgb(255, 204, 92) else Color.WHITE)
                view.alpha = if (active) 1f else 0.85f
                // 选中态换金色描边底, 未选中用紫色渐变底 —— 一眼可辨当前预设。
                view.background = ContextCompat.getDrawable(
                    this@MainActivity,
                    if (active) R.drawable.bg_preset_active else R.drawable.bg_tuning_counter,
                )
            }
        }
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        eqArea.addView(presetRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (compact) 4 else 10) })

        // ── 4. EQ 频段：横向控制杆（每个频段一行：频率 | 滑杆 | 增益）──
        // 刻度文字用无内边距的 caption：高度由文本自身决定, 不会被固定 dp 裁掉。
        fun caption(text: String, sp: Int, color: Int): TextView = TextView(this).apply {
            this.text = text
            textSize = sp.toFloat()
            setTextColor(color)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
        }
        val bars = ArrayList<EqSlider>(bandCount)
        val valueViews = ArrayList<TextView>(bandCount)
        val bandRows = ArrayList<View>(bandCount)
        fun syncBars() {
            val applied = KtvEqualizer.levelsSnapshot()
            for (index in working.indices) {
                working[index] = applied.getOrElse(index) { 0f }
                bars.getOrNull(index)?.setLevel(working[index])
                valueViews.getOrNull(index)?.text = eqLevelText(working[index])
            }
        }
        caps.frequencies.forEachIndexed { index, _ ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                caption(
                    bandLabels.getOrElse(index) { "${index + 1}" } + "Hz",
                    if (compact) 12 else 13,
                    Color.rgb(206, 200, 238),
                ),
                LinearLayout.LayoutParams(dp(62), -1),
            )
            val value = caption(
                eqLevelText(working.getOrElse(index) { 0f }),
                if (compact) 12 else 13,
                Color.argb(170, 255, 255, 255),
            )
            valueViews.add(value)
            val bar = EqSlider(this).apply {
                setRange(caps.minDb, caps.maxDb, eqSliderSteps, zeroLine = true)
                setLevel(working.getOrElse(index) { 0f })
                onLevelChanged = { level, fromUser ->
                    if (fromUser) {
                        working[index] = level
                        value.text = eqLevelText(level)
                        value.setTextColor(
                            if (level == 0f) Color.argb(170, 255, 255, 255) else Color.rgb(255, 204, 92),
                        )
                        // 用户手动改动 → 预设转为「自定义」并自动保存。
                        KtvEqualizer.setLevels(working.copyOf())
                        KtvEqualizer.markCustomPreset()
                        renderPresets()
                        applyEqualizerSettings()
                    }
                }
                onInteractionFinished = { applyEqualizerSettings() }
            }
            bars.add(bar)
            row.addView(bar, LinearLayout.LayoutParams(0, -1, 1f))
            row.addView(value, LinearLayout.LayoutParams(dp(58), -1))
            bandRows.add(row)
            eqArea.addView(row, LinearLayout.LayoutParams(-1, bandRowHeight))
        }

        KtvEqualizer.appPresetNames().forEach { name ->
            val presetButton = TextView(this).apply {
                text = displayPreset(name)
                textSize = if (compact) 13f else 15f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                background = ContextCompat.getDrawable(context, R.drawable.bg_tuning_counter)
                includeFontPadding = false
                setPadding(dp(6), dp(if (compact) 4 else 7), dp(6), dp(if (compact) 4 else 7))
                TvFocusStyler.installAction(this)
                setOnClickListener {
                    if (name == KtvEqualizer.CUSTOM_PRESET) {
                        // 保留当前频段值，仅把预设标记为「自定义」。
                        KtvEqualizer.markCustomPreset()
                    } else {
                        KtvEqualizer.applyPreset(name)
                        syncBars()
                    }
                    renderPresets()
                    applyEqualizerSettings()
                }
            }
            presetButtons[name] = presetButton
            presetRow.addView(
                presetButton,
                LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                },
            )
        }
        renderEqEnabled()
        renderPresets()

        val scroll = android.widget.ScrollView(this).apply { addView(panel) }
        val dialog = AlertDialog.Builder(this).setView(scroll).create()
        close.setOnClickListener { dialog.dismiss() }
        dialog.restoreSourceFocusOnDismiss()
        dialog.showForTv(afterShow = {
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                attributes = attributes.apply { dimAmount = 0.72f }
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            // 两遍自适应：先按内容实测高度判断是否超出可用空间，超出就只压缩 EQ 频段区
            // （文字行 wrap_content 保持不变），最后再收敛窗口尺寸。
            val availableHeight = (metrics.heightPixels * 0.92f).toInt()
            val maxContentHeight = (availableHeight - dp(24)).coerceAtLeast(dp(150))
            val probeWidth = min(dp(760), (metrics.widthPixels * 0.94f).toInt())
            panel.measure(
                View.MeasureSpec.makeMeasureSpec(probeWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val overflow = panel.measuredHeight - maxContentHeight
            if (overflow > 0 && bandRows.isNotEmpty()) {
                val shrunk = (bandRowHeight - overflow / bandRows.size).coerceAtLeast(dp(26))
                Log.i(
                    TAG,
                    "tuning dialog shrink band rows: measured=${panel.measuredHeight}px " +
                        "max=${maxContentHeight}px row=$bandRowHeight->$shrunk",
                )
                if (shrunk < bandRowHeight) {
                    bandRowHeight = shrunk
                    bandRows.forEach { row ->
                        row.layoutParams = row.layoutParams.apply { height = bandRowHeight }
                    }
                }
            }
            // 最终兜底：宽度上限 + 内容区高度上限（仍放不下时弹窗内滚动，绝不裁切）。
            // 紧凑屏宽度比收到 0.72 —— 弹窗明显小于屏幕、四周留白，视觉更协调。
            fitDialogToScreen(
                dialog = dialog,
                scroll = scroll,
                content = panel,
                preferredWidthDp = 760,
                widthRatio = if (compact) 0.72f else 0.94f,
            )
            volumeBar.requestFocus()
        })
    }

    private fun controlRow(
        left: String?,
        right: String?,
        leftAction: Runnable,
        rightAction: Runnable
    ): LinearLayout {
        val row = LinearLayout(this)
        row.setGravity(Gravity.CENTER_VERTICAL)
        row.addView(
            button(left, View.OnClickListener { v: View? -> leftAction.run() }),
            LinearLayout.LayoutParams(0, dp(54), 1f)
        )
        row.addView(
            button(right, View.OnClickListener { v: View? -> rightAction.run() }),
            LinearLayout.LayoutParams(0, dp(54), 1f)
        )
        return row
    }

    private fun showAtmosphereDialog() {
        val labels = arrayOf(getString(R.string.ui_a_0087), getString(R.string.ui_a_0324), getString(R.string.ui_a_0325), getString(R.string.ui_a_0326), getString(R.string.ui_a_0262))
        val values = arrayOf(KtvRaw.get(R.string.ui_a_0087), KtvRaw.get(R.string.ui_a_0324), KtvRaw.get(R.string.ui_a_0325), KtvRaw.get(R.string.ui_a_0326), KtvRaw.get(R.string.ui_a_0262))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_a_0165))
            .setItems(
                labels,
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    atmosphere = values[which]
                    saveState()
                    toast(getString(R.string.ui_a_0327) + atmosphere)
                })
            .create().showForTv()
    }

    private fun showPlayerStatusDialog() {
        val now = if (orderQueue!!.isEmpty()) null else orderQueue.get(0)
        val message = (getString(R.string.ui_a_0328) + (if (now == null) getString(R.string.ui_a_0329) else now.displayTitle())
                + getString(R.string.ui_a_0330) + orderQueue.size
                + getString(R.string.ui_a_0331) + sangHistory.size
                + getString(R.string.ui_a_0332) + (if (loopOne) getString(R.string.ui_a_0189) else (if (autoNext) getString(R.string.ui_a_0190) else getString(R.string.ui_a_0333)))
                + getString(R.string.ui_a_0334) + (if (originalVocal) getString(R.string.ui_a_0305) else getString(R.string.ui_a_0306))
                + getString(R.string.ui_a_0335) + vocalChannelMode
                + getString(R.string.ui_a_0336) + singMode
                + getString(R.string.ui_a_0337) + musicVolume
                + getString(R.string.ui_a_0338) + micVolume
                + getString(R.string.ui_a_0339) + tone
                + getString(R.string.ui_a_0340) + atmosphere
                + getString(R.string.ui_a_0341) + scoreSummary()
                + getString(R.string.ui_a_0342) + (if (recorder == null) getString(R.string.ui_a_0343) else getString(R.string.ui_a_0344))
                + getString(R.string.ui_a_0345) + lightMode
                + getString(R.string.ui_a_0346) + (if (marqueeEnabled) marqueeText else getString(R.string.ui_a_0037))
                + getString(R.string.ui_a_0347) + audioDelayMs + "ms"
                + getString(R.string.ui_a_0348) + (if (tableBroadcastEnabled) tableBroadcastText else getString(R.string.ui_a_0037)))
        AlertDialog.Builder(this).setTitle(getString(R.string.ui_a_0349)).setMessage(message)
            .setPositiveButton(getString(R.string.ui_ok), null).create().showForTv(DialogInterface.BUTTON_POSITIVE)
    }

    private fun showStorageDialog() {
        val root = library.rootDir
        val importRoot = library.importDir
        val message = (KtvRaw.get(R.string.ui_a_0350) + root.getAbsolutePath()
                + KtvRaw.get(R.string.ui_a_0351) + formatSize(root.getFreeSpace())
                + KtvRaw.get(R.string.ui_a_0352) + importRoot.getAbsolutePath()
                + KtvRaw.get(R.string.ui_a_0353) + recordDir().getAbsolutePath()
                + KtvRaw.get(R.string.ui_a_0354) + library.allSongs().size
                + KtvRaw.get(R.string.ui_a_0355) + downloads.size)
        AlertDialog.Builder(this).setTitle(getString(R.string.ui_a_0356)).setMessage(message)
            .setPositiveButton(getString(R.string.ui_ok), null).create().showForTv(DialogInterface.BUTTON_POSITIVE)
    }

    private fun showNetworkDialog() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager?
        val info = if (cm == null) null else cm.getActiveNetworkInfo()
        val message =
            (KtvRaw.get(R.string.ui_a_0357) + (if (info != null && info.isConnected()) KtvRaw.get(R.string.ui_a_0358) else KtvRaw.get(R.string.ui_a_0359))
                    + KtvRaw.get(R.string.ui_a_0360) + (if (info == null) KtvRaw.get(R.string.ui_a_0361) else info.getTypeName())
                    + KtvRaw.get(R.string.ui_a_0362) + prefs()!!.getString(KEY_CATALOG_URL, KtvRaw.get(R.string.ui_a_0363))
                    + KtvRaw.get(R.string.ui_a_0364) + library.allSongs().size + getString(R.string.ui_p_064))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_a_0366))
            .setMessage(message)
            .setPositiveButton(
                getString(R.string.ui_a_0367),
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int -> fetchCatalog() })
            .setNegativeButton(getString(R.string.ui_close), null)
            .create().showForTv()
    }


    private fun showAppCenter() {
        setPage("应用", getString(R.string.ui_a_0368))
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(320), -1))
        left.addView(sectionTitle(getString(R.string.ui_a_0179)))
        left.addView(button(getString(R.string.ui_sec_network), View.OnClickListener { v: View? -> showNetworkDialog() }))
        left.addView(button(getString(R.string.ui_a_0178), View.OnClickListener { v: View? -> showScreenDialog() }))
        left.addView(
            button(
                getString(R.string.ui_a_0181),
                View.OnClickListener { v: View? -> showLibraryMaintenance() })
        )
        left.addView(button(getString(R.string.ui_a_0356), View.OnClickListener { v: View? -> showStorageDialog() }))
        left.addView(button(getString(R.string.ui_a_0369), View.OnClickListener { v: View? -> showBackupTools() }))

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle(getString(R.string.ui_a_0370)))
        val tools = arrayOf<String?>(
            KtvRaw.get(R.string.ui_a_0371),
            getString(R.string.ui_a_0150),
            getString(R.string.ui_a_0152),
            KtvRaw.get(R.string.ui_a_0372),
            getString(R.string.ui_a_0161),
            getString(R.string.ui_sec_usb_add),
            KtvRaw.get(R.string.ui_a_0374),
            getString(R.string.ui_a_0174),
            getString(R.string.ui_a_0171),
            KtvRaw.get(R.string.ui_a_0375)
        )
        val grid = GridLayout(this)
        grid.setColumnCount(4)
        right.addView(grid)
        for (tool in tools) {
            addTile(
                grid,
                tool,
                KtvRaw.get(R.string.ui_a_0376),
                View.OnClickListener { v: View? ->
                    openTool(
                        (v as TextView).getText().toString().split("\n".toRegex())
                            .dropLastWhile { it.isEmpty() }.toTypedArray()[0])
                })
        }
        right.addView(sectionTitle(getString(R.string.ui_a_0377)))
        val list = listView()
        val adapter = tvAdapter()
        val apks = localApks()
        for (apk in apks) adapter.add(apk.getName() + "    " + formatSize(apk.length()))
        if (adapter.isEmpty()) adapter.add(getString(R.string.ui_p_no_apk, AppPaths.appsDir.absolutePath))
        list.setAdapter(adapter)
        list.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            if (pos >= 0 && pos < apks.size) installApk(apks.get(pos))
        })
        right.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showTableBroadcast() {
        setPage("广播", getString(R.string.ui_a_0379))
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(340), -1))
        left.addView(sectionTitle(getString(R.string.ui_a_0183)))
        left.addView(
            button(
                if (tableBroadcastEnabled) KtvRaw.get(R.string.ui_a_0380) else KtvRaw.get(R.string.ui_a_0381),
                View.OnClickListener { v: View? ->
                    tableBroadcastEnabled = !tableBroadcastEnabled
                    saveState()
                    showTableBroadcast()
                })
        )
        left.addView(
            button(
                getString(R.string.ui_a_0382),
                View.OnClickListener { v: View? -> sendTableBroadcast(tableBroadcastText) })
        )
        left.addView(controlRow(getString(R.string.ui_a_0383), getString(R.string.ui_a_0384), Runnable {
            tableBroadcastSeconds = clamp(tableBroadcastSeconds - 1, 2, 60)
            saveState()
            showTableBroadcast()
        }, Runnable {
            tableBroadcastSeconds = clamp(tableBroadcastSeconds + 1, 2, 60)
            saveState()
            showTableBroadcast()
        }))

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle(getString(R.string.ui_a_0385)))
        val input = EditText(this)
        input.setSingleLine(true)
        input.setText(tableBroadcastText)
        input.setHint(getString(R.string.ui_a_0386))
        right.addView(input, LinearLayout.LayoutParams(-1, dp(58)))
        right.addView(button(getString(R.string.ui_a_0387), View.OnClickListener { v: View? ->
            tableBroadcastText = input.getText().toString().trim { it <= ' ' }
            if (tableBroadcastText!!.isEmpty()) tableBroadcastText = getString(R.string.ui_a_0090)
            saveState()
            sendTableBroadcast(tableBroadcastText)
            showTableBroadcast()
        }), LinearLayout.LayoutParams(dp(180), dp(58)))
        right.addView(label(getString(R.string.ui_a_0388) + tableBroadcastSeconds + getString(R.string.ui_p_065), 20, Color.WHITE))
        right.addView(sectionTitle(getString(R.string.ui_a_0390)))
        val list = listView()
        val adapter = tvAdapter()
        if (store.broadcastHistory.isEmpty()) adapter.add(getString(R.string.ui_a_0391))
        else adapter.addAll(store.broadcastHistory)
        list.setAdapter(adapter)
        right.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showBackupTools() {
        setPage("备份", getString(R.string.ui_a_0392))
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(340), -1))
        left.addView(sectionTitle(getString(R.string.ui_a_0369)))
        left.addView(button(getString(R.string.ui_a_0393), View.OnClickListener { v: View? -> backupConfig() }))
        left.addView(button(getString(R.string.ui_a_0394), View.OnClickListener { v: View? -> restoreConfig() }))
        left.addView(
            button(
                getString(R.string.ui_a_0395),
                View.OnClickListener { v: View? -> showLibraryMaintenance() })
        )

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle(getString(R.string.ui_a_0396)))
        val backupDir = backupDir()
        right.addView(label(getString(R.string.ui_a_0397) + backupDir.getAbsolutePath(), 20, Color.LTGRAY))
        val list = listView()
        val adapter = tvAdapter()
        val files = backupDir.listFiles()
        if (files != null) {
            val values: MutableList<File> = ArrayList<File>()
            for (file in files) if (file.isFile()) values.add(file)
            Collections.sort<File?>(
                values,
                Comparator { a: File?, b: File? ->
                    b!!.lastModified().compareTo(a!!.lastModified())
                })
            for (file in values) adapter.add(file.getName() + "    " + formatSize(file.length()))
        }
        if (adapter.isEmpty()) adapter.add(getString(R.string.ui_a_0398))
        list.setAdapter(adapter)
        right.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showLibraryMaintenance() {
        setPage("维护", getString(R.string.ui_a_0399))
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(340), -1))
        left.addView(sectionTitle(getString(R.string.ui_a_0181)))
        left.addView(button(getString(R.string.ui_a_0400), View.OnClickListener { v: View? ->
            refreshLibrary(
                Runnable { showLibraryMaintenance() })
        }))
        left.addView(button(getString(R.string.ui_a_0238), View.OnClickListener { v: View? -> fetchCatalog() }))
        left.addView(button(getString(R.string.ui_a_0401), View.OnClickListener { v: View? ->
            val deleted = library.clearRemoteCache()
            refreshLibrary(Runnable {
                toast(if (deleted) getString(R.string.ui_a_0402) else getString(R.string.ui_a_0403))
                showLibraryMaintenance()
            })
        }))
        left.addView(button(getString(R.string.ui_a_0404), View.OnClickListener { v: View? ->
            downloads.clear()
            showLibraryMaintenance()
        }))
        left.addView(button(getString(R.string.ui_a_0405), View.OnClickListener { v: View? ->
            removeMissingQueueItems()
            showLibraryMaintenance()
        }))
        left.addView(button(getString(R.string.ui_a_0406), View.OnClickListener { v: View? ->
            store.hiddenSongIds.clear()
            saveState()
            refreshLibrary(Runnable {
                toast(getString(R.string.ui_a_0407))
                showLibraryMaintenance()
            })
        }))

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle(getString(R.string.ui_a_0408)))
        right.addView(label(getString(R.string.ui_a_0409) + library.allSongs().size + getString(R.string.ui_p_064), 22, Color.WHITE))
        right.addView(label(getString(R.string.ui_a_0330) + orderQueue!!.size + getString(R.string.ui_p_064), 20, Color.LTGRAY))
        right.addView(label(getString(R.string.ui_a_0331) + sangHistory.size + getString(R.string.ui_p_064), 20, Color.LTGRAY))
        right.addView(label(getString(R.string.ui_a_0355) + downloads.size, 20, Color.LTGRAY))
        right.addView(label(getString(R.string.ui_a_0413) + store.hiddenSongIds.size + getString(R.string.ui_p_064), 20, Color.LTGRAY))
        right.addView(label(getString(R.string.ui_a_0414) + library.rootDir.getAbsolutePath(), 18, Color.LTGRAY))
        right.addView(label(getString(R.string.ui_a_0352) + library.importDir.getAbsolutePath(), 18, Color.LTGRAY))
    }

    private fun showMobileRemoteDialog() {
        // 自愈:服务没在跑就先尝试启动(可能因网络切换/瞬时问题停止过)。
        if (!remoteServer.isRunning()) startRemoteServer()
        val url = remoteUrl()
        if (url.isBlank()) {
            val ip = localIp()
            val reason = remoteServer.error() ?: getString(R.string.ui_a_0416)
            val detected = when {
                ip.isEmpty() -> getString(R.string.ui_p_no_lan_ip)
                ip == "127.0.0.1" || ip.startsWith("169.254") -> getString(R.string.ui_p_bad_ip, ip)
                else -> getString(R.string.ui_p_detected_ip, ip)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.ui_a_0171))
                .setMessage("$reason\n$detected")
                .setNegativeButton(getString(R.string.ui_close), null)
                .setPositiveButton(getString(R.string.ui_a_0417)) { _, _ -> restartRemoteServer() }
                .create().showForTv(DialogInterface.BUTTON_POSITIVE)
            return
        }
        // 分辨率自适应：横版布局 —— 二维码居左、文字/按钮居右，面板又宽又扁
        // (~60% 屏宽、<45% 屏高)，屏幕居中，只占少量画面，不遮挡底下内容。
        val metrics = resources.displayMetrics
        val screenHeightDp = metrics.heightPixels / metrics.density
        val compact = screenHeightDp < 620f
        val qrSide = min(
            (screenHeightDp * 0.30f).toInt(),
            if (compact) 118 else 158,
        ).coerceAtLeast(96)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(context, R.drawable.bg_tuning_dialog)
            setPadding(
                dp(if (compact) 14 else 20),
                dp(if (compact) 10 else 14),
                dp(if (compact) 16 else 22),
                dp(if (compact) 10 else 14),
            )
        }
        // 左：白色圆角卡片承载二维码，对比度更好、更精致。
        val qrCard = FrameLayout(this).apply {
            background = ContextCompat.getDrawable(context, R.drawable.bg_qr_card)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        // 位图按最终显示尺寸生成，保证任何分辨率下二维码都清晰可扫。
        val qr = ImageView(this)
        qr.setImageBitmap(qrBitmap(url, dp(qrSide)))
        qrCard.addView(qr, FrameLayout.LayoutParams(dp(qrSide), dp(qrSide)))
        panel.addView(qrCard, LinearLayout.LayoutParams(-2, -2))
        // 右：标题 + 金色分隔线 + 说明 + 地址 + 确定按钮。
        val lanAddress = remoteServer.address()
            ?.takeIf { remoteServer.port() > 0 }?.let { "${it}:${remoteServer.port()}" }.orEmpty()
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(if (compact) 14 else 18), 0, 0, 0)
        }
        // 右列固定宽度: 横版布局下避免被压缩导致地址/文案被截断。
        val infoWidth = dp(if (compact) 196 else 240)
        info.addView(
            label(getString(R.string.ui_a_0419), if (compact) 16 else 20, Color.WHITE).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTypeface(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        info.addView(
            View(this).apply {
                background = ContextCompat.getDrawable(context, R.drawable.bg_dialog_divider)
            },
            LinearLayout.LayoutParams(-1, dp(2)).apply { topMargin = dp(5); bottomMargin = dp(6) },
        )
        val tip = label(
            getString(R.string.ui_p_scan_tip),
            if (compact) 12 else 14,
            Color.argb(214, 255, 255, 255),
        )
        tip.gravity = Gravity.CENTER
        info.addView(tip, LinearLayout.LayoutParams(-1, -2))
        if (lanAddress.isNotEmpty()) {
            val addressLine = label(lanAddress, if (compact) 12 else 14, GOLD).apply {
                gravity = Gravity.CENTER
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                maxLines = 1
            }
            info.addView(
                addressLine,
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) },
            )
        }
        val confirm = TextView(this).apply {
            text = getString(R.string.ui_p_confirm)
            textSize = if (compact) 13f else 15f
            setTextColor(Color.rgb(59, 18, 92))
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(context, R.drawable.bg_confirm_gold)
            setPadding(dp(30), dp(if (compact) 6 else 8), dp(30), dp(if (compact) 6 else 8))
        }
        info.addView(
            confirm,
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(if (compact) 8 else 12) },
        )
        panel.addView(info, LinearLayout.LayoutParams(infoWidth, -2))
        val scroll = android.widget.ScrollView(this).apply { addView(panel) }
        var dialogRef: AlertDialog? = null
        confirm.setOnClickListener { dialogRef?.dismiss() }
        val dialog = AlertDialog.Builder(this)
            .setView(scroll)
            .create()
        dialogRef = dialog
        dialog.showForTv {
            fitDialogToScreen(
                dialog = dialog,
                scroll = scroll,
                content = panel,
                preferredWidthDp = if (compact) 420 else 520,
                widthRatio = 0.9f,
            )
            TvFocusStyler.installAction(confirm)
            confirm.requestFocus()
        }
    }

    private fun restartRemoteServer() {
        remoteServer.stop()
        startRemoteServer()
        updateMobileQrOverlay()
        updateFullScreenQrOverlay()
        main.postDelayed({ showMobileRemoteDialog() }, 180)
    }

    private fun updateMobileQrOverlay() {
        val root = findViewById<FrameLayout?>(R.id.layout_root) ?: return
        mobileQrOverlay?.let { root.removeView(it) }
        mobileQrOverlay = null
        if (prefs()?.getBoolean(KEY_SHOW_MOBILE_QR, false) != true) return
        val url = remoteUrl()
        if (url.isBlank()) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(4))
            setBackgroundColor(Color.argb(205, 255, 255, 255))
            addView(ImageView(this@MainActivity).apply {
                setImageBitmap(qrBitmap(url, dp(112)))
                contentDescription = getString(R.string.ui_a_0420)
            }, LinearLayout.LayoutParams(dp(116), dp(116)))
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.ui_a_0171)
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(59, 18, 92))
            }, LinearLayout.LayoutParams(-1, dp(24)))
            setOnClickListener { showMobileRemoteDialog() }
        }
        val params = FrameLayout.LayoutParams(dp(128), dp(152), Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = dp(20)
            bottomMargin = dp(76)
        }
        root.addView(box, params)
        // 二维码浮层是展示型表面: 被焦点填充盖住会直接遮掉二维码。
        TvFocusStyler.suppressFill(box)
        mobileQrOverlay = box
    }

    /** 全屏播放时固定显示在右上角的小型手机点歌二维码。 */
    private fun updateFullScreenQrOverlay() {
        val container = fullScreenContainer ?: return
        fullScreenQrOverlay?.let { container.removeView(it) }
        fullScreenQrOverlay = null
        if (!isFullScreen) return
        val url = remoteUrl()
        if (url.isBlank()) return
        // 二维码及容器放大到原来的 1.3 倍(68→88/84→109, 容器 78→101/96→125)。
        val qrSize = dp(if (compactUi) 88 else 109)
        val totalWidth = dp(if (compactUi) 101 else 125)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(3))
            setBackgroundColor(Color.argb(218, 255, 255, 255))
            UiCompat.setElevation(this, dp(12).toFloat())
            addView(ImageView(this@MainActivity).apply {
                setImageBitmap(qrBitmap(url, qrSize))
                contentDescription = getString(R.string.ui_a_0421)
            }, LinearLayout.LayoutParams(qrSize, qrSize))
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.ui_a_0419)
                textSize = if (compactUi) 9f else 10f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(59, 18, 92))
                includeFontPadding = false
            }, LinearLayout.LayoutParams(-1, dp(if (compactUi) 16 else 18)))
            setOnClickListener { showMobileRemoteDialog() }
        }
        val params = FrameLayout.LayoutParams(totalWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP).apply {
            topMargin = dp(8)
            rightMargin = dp(10)
        }
        container.addView(box, params)
        box.bringToFront()
        // 全屏时常显：不跟随控制层收起而隐藏(与 ori 一致)。
        box.visibility = View.VISIBLE
        // 同手机点歌二维码: 展示型表面不做焦点填充。
        TvFocusStyler.suppressFill(box)
        fullScreenQrOverlay = box
    }

    private fun showMobileRemoteSettings() {
        val enabled = prefs()?.getBoolean(KEY_SHOW_MOBILE_QR, false) == true
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_a_0171))
            .setSingleChoiceItems(arrayOf(getString(R.string.ui_a_0422), getString(R.string.ui_a_0423)), if (enabled) 1 else 0) { dialog, which ->
                prefs()?.edit()?.putBoolean(KEY_SHOW_MOBILE_QR, which == 1)?.apply()
                updateMobileQrOverlay()
                dialog.dismiss()
            }
            .setNeutralButton(getString(R.string.ui_a_0424)) { _, _ -> showMobileRemoteDialog() }
            .setNegativeButton(getString(R.string.ui_close), null)
            .create().showForTv()
    }

    private fun startRemoteServer() {
        val address = localIp().takeIf { LocalRemoteServer.isPrivateIpv4(it) }
        remoteServer.start(
            address,
            object : LocalRemoteServer.Callback {
            override fun handle(
                method: String,
                path: String,
                query: Map<String, String>,
                body: String
            ): LocalRemoteServer.ApiResponse = remoteApi(method, path, query, body)

            override fun statusJson(): String {
                try {
                    val json = JSONObject()
                    val now = if (orderQueue!!.isEmpty()) null else orderQueue.get(0)
                    json.put("ok", true)
                    json.put("current", if (now == null) "" else now.displayTitle())
                    json.put("queueSize", orderQueue.size)
                    json.put("historySize", sangHistory.size)
                    json.put("playing", player?.isPlaying == true)
                    json.put("score", scoreSummary())
                    json.put("vocal", if (originalVocal) KtvRaw.get(R.string.ui_a_0305) else KtvRaw.get(R.string.ui_a_0306))
                    json.put("vocalChannelMode", vocalChannelMode)
                    json.put("singMode", singMode)
                    json.put("remoteUrl", remoteUrl())
                    return json.toString()
                } catch (e: Exception) {
                    return errorJson(e)
                }
            }

            override fun catalogJson(): String {
                try {
                    val array = JSONArray()
                    for (song in library.allSongs()) {
                        if (isVisibleSong(song)) array.put(songJson(song))
                    }
                    val json = JSONObject()
                    json.put("ok", true)
                    json.put("songs", array)
                    return json.toString()
                } catch (e: Exception) {
                    return errorJson(e)
                }
            }

            override fun queueJson(): String {
                try {
                    val queue = JSONArray()
                    for (song in orderQueue!!) queue.put(songJson(song))
                    val history = JSONArray()
                    for (song in sangHistory) history.put(songJson(song))
                    val json = JSONObject()
                    json.put("ok", true)
                    json.put("queue", queue)
                    json.put("history", history)
                    return json.toString()
                } catch (e: Exception) {
                    return errorJson(e)
                }
            }

            override fun rankJson(): String {
                try {
                    val ranked: MutableList<Song?> = ArrayList<Song?>()
                    for (song in library.allSongs()) {
                        if (isVisibleSong(song)) ranked.add(song)
                    }
                    Collections.sort<Song?>(
                        ranked,
                        Comparator { a: Song?, b: Song? ->
                            Integer.compare(
                                b!!.playCount,
                                a!!.playCount
                            )
                        })
                    val array = JSONArray()
                    var i = 0
                    while (i < ranked.size && i < 80) {
                        array.put(songJson(ranked.get(i)!!))
                        i++
                    }
                    val json = JSONObject()
                    json.put("ok", true)
                    json.put("songs", array)
                    return json.toString()
                } catch (e: Exception) {
                    return errorJson(e)
                }
            }

            override fun filterJson(type: String?, value: String?): String {
                try {
                    val t = if (type == null) "" else type.trim { it <= ' ' }
                    val v = if (value == null) "" else value.trim { it <= ' ' }
                    val array = JSONArray()
                    for (song in library.allSongs()) {
                        if (!isVisibleSong(song)) continue
                        val compare = if ("language" == t) song.language else song.category
                        if (v.isEmpty() || v == compare) array.put(songJson(song))
                        if (array.length() >= 120) break
                    }
                    val json = JSONObject()
                    json.put("ok", true)
                    json.put("songs", array)
                    return json.toString()
                } catch (e: Exception) {
                    return errorJson(e)
                }
            }

            override fun favoritesJson(): String {
                try {
                    val array = JSONArray()
                    for (song in library.allSongs()) {
                        if (isVisibleSong(song) && store.isFavorite(song)) array.put(songJson(song))
                    }
                    val json = JSONObject()
                    json.put("ok", true)
                    json.put("songs", array)
                    return json.toString()
                } catch (e: Exception) {
                    return errorJson(e)
                }
            }

            override fun search(query: String?): String {
                try {
                    val array = JSONArray()
                    val q = if (query == null) "" else query.lowercase().trim { it <= ' ' }
                    var count = 0
                    for (song in library.allSongs()) {
                        if (!isVisibleSong(song)) continue
                        val text =
                            (song.title + " " + song.singer + " " + song.category + " " + song.language + " " + song.pinyin).lowercase()
                        if (q.isEmpty() || text.contains(q)) {
                            array.put(songJson(song))
                            if (++count >= 80) break
                        }
                    }
                    val json = JSONObject()
                    json.put("ok", true)
                    json.put("songs", array)
                    return json.toString()
                } catch (e: Exception) {
                    return errorJson(e)
                }
            }

            override fun order(query: String?): String {
                val q = if (query == null) "" else query.lowercase().trim { it <= ' ' }
                if (q.isEmpty()) return "{\"ok\":false,\"error\":\"missing q\"}"
                for (song in library.allSongs()) {
                    if (store.hiddenSongIds.contains(stableId(song))) continue
                    val text = (song.title + " " + song.singer + " " + song.pinyin).lowercase()
                    if (text.contains(q)) {
                        main.post(Runnable { handleSong(song) })
                        return "{\"ok\":true,\"message\":\"ordered\"}"
                    }
                }
                return "{\"ok\":false,\"error\":\"song not found\"}"
            }

            override fun command(action: String?, params: Map<String, String>): String {
                val a = if (action == null) "" else action
                if ("next" == a) main.post(Runnable { playNext() })
                else if ("pause" == a) main.post(Runnable { togglePlay() })
                else if ("replay" == a) main.post(Runnable { replay() })
                else if ("vocal" == a) {
                    val mode = params.get("mode")
                    main.post(Runnable {
                        originalVocal = "original" == mode || KtvRaw.get(R.string.ui_a_0305) == mode
                        applyPlaybackMode()
                        saveState()
                        status!!.setText(if (originalVocal) getString(R.string.ui_a_0425) else getString(R.string.ui_a_0426))
                    })
                } else if ("singmode" == a) {
                    val mode = params.get("mode")
                    if (mode == null || mode.trim { it <= ' ' }
                            .isEmpty()) return "{\"ok\":false,\"error\":\"missing mode\"}"
                    main.post(Runnable { setSingMode(mode.trim { it <= ' ' }) })
                } else if ("channel" == a) {
                    val mode = params.get("mode")
                    if (mode == null || mode.trim { it <= ' ' }
                            .isEmpty()) return "{\"ok\":false,\"error\":\"missing mode\"}"
                    main.post(Runnable { setVocalChannelMode(mode.trim { it <= ' ' }) })
                } else if ("light" == a) {
                    val mode = params.get("mode")
                    if (mode == null || mode.trim { it <= ' ' }
                            .isEmpty()) return "{\"ok\":false,\"error\":\"missing mode\"}"
                    main.post(Runnable {
                        lightMode = mode
                        saveState()
                        status!!.setText(getString(R.string.ui_a_0427) + mode)
                    })
                } else if ("marquee" == a) {
                    val text = params.get("text")
                    main.post(Runnable {
                        if (text != null && !text.trim { it <= ' ' }.isEmpty()) marqueeText =
                            text.trim { it <= ' ' }
                        marqueeEnabled = true
                        saveState()
                        status!!.setText(getString(R.string.ui_a_0428) + marqueeText)
                    })
                } else if ("broadcast" == a) {
                    val text = params.get("text")
                    if (text == null || text.trim { it <= ' ' }
                            .isEmpty()) return "{\"ok\":false,\"error\":\"missing text\"}"
                    main.post(Runnable { sendTableBroadcast(text.trim { it <= ' ' }) })
                } else if ("status" == a) {
                    return statusJson()
                } else if ("cancel_downloads" == a) {
                    main.post(Runnable { cancelAllDownloads() })
                } else if ("clear_queue" == a) {
                    main.post(Runnable {
                        orderQueue!!.clear()
                        saveState()
                        renderQueue()
                        status!!.setText(getString(R.string.ui_a_0429))
                    })
                } else if ("remove_queue" == a) {
                    val index = params.get("index")
                    try {
                        val pos = index!!.toInt()
                        main.post(Runnable {
                            if (pos >= 0 && pos < orderQueue!!.size) {
                                val removed = orderQueue.removeAt(pos)
                                saveState()
                                renderQueue()
                                status!!.setText(getString(R.string.ui_a_0430) + removed.title)
                            }
                        })
                    } catch (e: Exception) {
                        return "{\"ok\":false,\"error\":\"bad index\"}"
                    }
                } else if ("favorite" == a) {
                    val q = params.get("q")
                    if (q == null || q.trim { it <= ' ' }
                            .isEmpty()) return "{\"ok\":false,\"error\":\"missing q\"}"
                    for (song in library.allSongs()) {
                        if (!isVisibleSong(song)) continue
                        val text = (song.title + " " + song.singer + " " + song.pinyin).lowercase()
                        if (text.contains(q.lowercase())) {
                            main.post(Runnable {
                                store.toggleFavorite(song)
                                saveState()
                                status!!.setText(getString(R.string.ui_a_0431) + song.title)
                            })
                            return "{\"ok\":true,\"message\":\"favorite toggled\"}"
                        }
                    }
                    return "{\"ok\":false,\"error\":\"song not found\"}"
                } else if ("hide" == a) {
                    val q = params.get("q")
                    if (q == null || q.trim { it <= ' ' }
                            .isEmpty()) return "{\"ok\":false,\"error\":\"missing q\"}"
                    for (song in library.allSongs()) {
                        val text = (song.title + " " + song.singer + " " + song.pinyin).lowercase()
                        if (text.contains(q.lowercase())) {
                            main.post(Runnable { removeSong(song) })
                            return "{\"ok\":true,\"message\":\"hidden\"}"
                        }
                    }
                    return "{\"ok\":false,\"error\":\"song not found\"}"
                } else return "{\"ok\":false,\"error\":\"unknown command\"}"
                return "{\"ok\":true}"
            }
        })
    }

    /** 复刻原版「时光KTV」的 whoami 心跳,维持设备在歌曲源后台的"合法/活跃"状态。 */
    private fun sendDeviceHeartbeat(boot: Boolean) {
        Thread({
            try {
                val now = System.currentTimeMillis()
                val sb = StringBuilder("http://hbeat.ktvsky.com/whoami?_src=600007&_src_at=600001")
                sb.append("&_m=").append(java.net.URLEncoder.encode(Build.MODEL, "UTF-8"))
                sb.append("&_mac=").append(deviceMac())
                sb.append("&_vc=4&_vn=4.1.3.03281430")
                sb.append("&_pkn=").append(LEGACY_PACKAGE)
                sb.append("&_player=0&_boot=").append(if (boot) 1 else 0)
                sb.append("&_ts=").append(now)
                sb.append("&_api=").append(Build.VERSION.SDK_INT)
                sb.append("&ktv_id=").append(LEGACY_KTV_ID)
                sb.append("&androidid=").append(deviceMac())
                sb.append("&_manuf=").append(java.net.URLEncoder.encode(Build.MANUFACTURER, "UTF-8"))
                sb.append("&_cid=A_").append(deviceMac())
                sb.append("&d_name=").append(java.net.URLEncoder.encode(Build.PRODUCT, "UTF-8"))
                sb.append("&d_device=").append(java.net.URLEncoder.encode(Build.DEVICE, "UTF-8"))
                sb.append("&d_board=").append(java.net.URLEncoder.encode(Build.BOARD, "UTF-8"))
                sb.append("&d_hardware=").append(java.net.URLEncoder.encode(Build.HARDWARE, "UTF-8"))
                sb.append("&d_fingerprint=").append(java.net.URLEncoder.encode(Build.FINGERPRINT, "UTF-8"))
                sb.append("&d_incremental=").append(java.net.URLEncoder.encode(Build.VERSION.INCREMENTAL, "UTF-8"))
                sb.append("&d_release=").append(java.net.URLEncoder.encode(Build.VERSION.RELEASE, "UTF-8"))
                sb.append("&d_utc=").append(now).append("&d_user=dpi")
                val conn = (URL(sb.toString()).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                }
                try {
                    conn.outputStream.use { it.write(ByteArray(0)) }
                    Log.i(TAG, "whoami heartbeat HTTP ${conn.responseCode}")
                } finally {
                    conn.disconnect()
                }
            } catch (ignored: Exception) {
            }
        }, "ktv-heartbeat").start()
    }

    /** 当前设备 MAC(12 位小写十六进制): 本机当前在用网卡, 读不到时回退历史抓包值。 */
    private fun deviceMac(): String = DeviceMac.effective()

    /**
     * 设备网络状态变化处理。
     *
     * 断网: 播放页右上角显示「网络未连接」。
     * 恢复: 刷新对网络敏感的全部状态, 保证歌曲源能重新取址并正常下载/播放 ——
     *   1. 重新解析当前网卡 MAC 与日志上报 IP；
     *   2. **重置歌曲源会话**（清 JS 侧 token 与 API 实例）—— 源站按出口 IP 计试用窗口，
     *      断网/重连后旧 token 失效，若继续复用会出现「地址取到了但下载 403/失败」；
     *   3. 复位歌曲源窗口守卫，便于下一次到期能再次提示；
     *   4. 拉起本地点歌服务并刷新二维码；
     *   5. 自动重试断网期间失败的下载。
     */
    private fun onNetworkStateChanged(online: Boolean, initial: Boolean = false) {
        fullScreenNetworkBadge?.visibility = if (online) View.GONE else View.VISIBLE
        if (!online) {
            status?.text = getString(R.string.ui_p_014)
            MiliLog.warn("NET-DOWN 设备网络已断开")
            return
        }
        // ⚠️ initial=true 表示「启动时的初始状态」，**不是**「断网后恢复」。
        // 启动流程（onCreate）已经做过 startRemoteServer()/updateMobileQrOverlay()/
        // refreshLogDeviceContext()/DeviceMac 解析，这里再走一遍恢复逻辑只会:
        //   1) 白白清掉刚建立的歌曲源会话（resetSourceSession）；
        //   2) 语义错误地 noteUrlOk()，可能掩盖真正的歌曲源窗口到期提示；
        //   3) 把状态栏错写成"网络已恢复"（启动瞬间就显示，用户可见的错误文案）。
        // 因此初始状态只更新断网角标，不做任何恢复动作。
        if (initial) {
            Log.i(TAG, "initial network state: online=true (skip recovery actions)")
            return
        }
        MiliLog.notice("NET-UP 设备网络已恢复, 重建歌曲源会话")
        if (!remoteServer.isRunning()) startRemoteServer()
        refreshNetworkState("net-up", notify = true)
    }

    /**
     * 网络路径变化后的统一维护（断网恢复 / 换网换 IP 都走这里）。
     *
     * 关键在于**重建歌曲源会话**：源站按「出口 IP + device」识别，并把试用窗口记在出口 IP 上，
     * 断网重连或换网后旧 token 已失效 —— 不重置就会出现「日志提示取址成功、实际下载
     * 403/失败」。同时复位窗口守卫、刷新二维码与日志上下文，并自动重试断网期间失败的下载。
     *
     * @param notify 是否弹提示（断网恢复时提示，换 IP 时静默）
     */
    private fun refreshNetworkState(reason: String, notify: Boolean) {
        DeviceMac.refresh()
        refreshLogDeviceContext()
        SongApiClient.resetSourceSession()
        Eac19WindowGuard.noteUrlOk()
        updateMobileQrOverlay()
        updateFullScreenQrOverlay()
        val retried = SongOkDownloadManager.retryNetworkFailed()
        MiliLog.notice("NET-REFRESH reason=$reason retried=$retried")
        if (retried > 0) {
            status?.text = getString(R.string.ui_p_080, retried)
            if (notify) toast(getString(R.string.ui_p_081, retried))
        } else if (notify) {
            status?.text = getString(R.string.ui_p_015)
        }
    }

    /** 远程日志: 把设备网卡 IP 与设备标识同步给日志客户端(GlogCenter 的 clientip/user/traceid)。 */
    private fun refreshLogDeviceContext() {
        MiliLog.updateDeviceContext(ip = localIp(), deviceId = deviceMac())
    }

    /** 远程日志: App 启动时上报设备信息(异步, 尽力而为)。 */
    private fun reportDeviceBootInfo() {
        Thread({
            try {
                val sb = StringBuilder()
                sb.append("BOOT")
                sb.append(" pkg=").append(packageName)
                sb.append(" ver=").append(BuildConfig.VERSION_NAME)
                sb.append(" install=").append(installTimeText())
                sb.append(" brand=").append(Build.BRAND)
                sb.append(" model=").append(Build.MODEL)
                sb.append(" device=").append(Build.DEVICE)
                sb.append(" android=").append(Build.VERSION.RELEASE)
                    .append("(api").append(Build.VERSION.SDK_INT).append(')')
                sb.append(" abi=").append(compatAbis())
                sb.append(" kernel=").append(System.getProperty("os.version").orEmpty())
                sb.append(" screen=").append(screenResolutionText())
                // 等主机 IP(公网出口 IP)就绪，避免启动这条日志的 serverip 为空。
                MiliLog.awaitServerIp()
                MiliLog.notice(sb.toString())
                // 存储根目录探测结果（排查不同盒子存储差异用；单行便于按 STORAGE 检索）。
                // 放在这里而不是启动主线程：既有完整上下文，又避免了主线程磁盘探测。
                runCatching { MiliLog.notice("STORAGE ${AppPaths.storageFacts(this@MainActivity)}") }
            } catch (ignored: Exception) {
            }
        }, "ktv-boot-log").start()
    }

    /** 应用安装时间(yyyy-MM-dd HH:mm:ss); 取不到返回 unknown。 */
    private fun installTimeText(): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(info.firstInstallTime))
    } catch (e: Exception) {
        "unknown"
    }

    /** 屏幕物理分辨率(宽x高); 取不到返回 unknown。 */
    /**
     * 设备 ABI 列表。
     * `Build.SUPPORTED_ABIS` 是 API 21 引入的，Android 4.4 退回已废弃但仍可用的 CPU_ABI/CPU_ABI2。
     */
    @Suppress("DEPRECATION")
    private fun compatAbis(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS?.joinToString(",").orEmpty()
        } else {
            listOfNotNull(Build.CPU_ABI, Build.CPU_ABI2)
                .filter { it.isNotEmpty() }
                .joinToString(",")
        }

    private fun screenResolutionText(): String = try {
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        metrics.widthPixels.toString() + "x" + metrics.heightPixels
    } catch (e: Exception) {
        "unknown"
    }

    private fun remoteApi(
        method: String,
        path: String,
        query: Map<String, String>,
        body: String
    ): LocalRemoteServer.ApiResponse {
        return try {
            when {
                method == "GET" && path == "/api/v1/state" -> remoteOk(remoteStateJson())
                method == "GET" && path == "/api/v1/songs" -> remoteOk(remoteSearchJson(query))
                method == "GET" && path == "/api/v1/hot" -> remoteOk(remoteHotJson(query))
                // ori 手机点歌页依赖的扩展端点（全套界面移植所需）。
                method == "GET" && path == "/api/v1/singers" -> remoteOk(remoteSingersJson(query))
                method == "GET" && path == "/api/v1/singer-songs" -> remoteOk(remoteSingerSongsJson(query))
                method == "GET" && path == "/api/v1/song-catalog" -> remoteOk(remoteSongCatalogJson(query))
                method == "GET" && path == "/api/v1/catalog-languages" -> remoteOk(remoteCatalogLanguagesJson())
                method == "GET" && path == "/api/v1/downloads" -> remoteOk(remoteDownloadsJson(query))
                method == "GET" && path == "/api/v1/download-tasks" -> remoteOk(remoteDownloadTasksJson())
                method == "GET" && path == "/api/v1/rank" -> remoteOk(remoteRankJson(query))
                method == "GET" && path == "/api/v1/queue" -> remoteOk(remoteQueueJson())
                method == "POST" && path == "/api/v1/queue" -> remoteOrder(JSONObject(body))
                method == "POST" && path == "/api/v1/player/actions" -> remotePlayerAction(JSONObject(body))
                method == "PATCH" && path == "/api/v1/player/audio" -> remoteAudio(JSONObject(body))
                method == "PATCH" && path == "/api/v1/player/equalizer" -> remoteEqualizer(JSONObject(body))
                path.startsWith("/api/v1/queue/") && method in arrayOf("PATCH", "DELETE") -> {
                    remoteQueueMutation(method, URLDecoder.decode(path.substringAfterLast('/'), "UTF-8"))
                }
                else -> remoteError(404, "NOT_FOUND", getString(R.string.ui_a_0104))
            }
        } catch (e: Exception) {
            remoteError(400, "BAD_REQUEST", e.message ?: getString(R.string.ui_a_0433))
        }
    }

    private fun remoteStateJson(): JSONObject {
        val now = currentSong ?: orderQueue?.firstOrNull()
        val next = orderQueue?.firstOrNull { now == null || stableId(it) != stableId(now) }
        return JSONObject().apply {
            put("current", now?.let { songJson(it) } ?: JSONObject.NULL)
            put("next", next?.let { songJson(it) } ?: JSONObject.NULL)
            put("queueSize", orderQueue?.size ?: 0)
            put("playing", player?.isPlaying == true)
            put("vocalMode", if (originalVocal) "original" else "accompany")
            put("musicVolume", musicVolume)
            put("micVolume", micVolume)
            // 均衡器状态(远程点歌页面的均衡器入口据此渲染频段)
            put("equalizer", equalizerJson())
        }
    }

    /** 均衡器状态 + 本机支持的频段(供远程页面渲染)。 */
    private fun equalizerJson(): JSONObject {
        val caps = KtvEqualizer.capabilities(this)
        val labels = KtvEqualizer.bandLabels(caps)
        val levels = KtvEqualizer.levelsSnapshot()
        return JSONObject().apply {
            put("enabled", KtvEqualizer.enabled)
            put("preset", KtvEqualizer.preset)
            put("globalMix", KtvEqualizer.globalMix)
            put("globalMixSupported", KtvEqualizer.globalMixSupported())
            put("minDb", caps.minDb.toDouble())
            put("maxDb", caps.maxDb.toDouble())
            put("bands", JSONArray().apply {
                for (index in 0 until caps.bandCount) {
                    put(
                        JSONObject().apply {
                            put("label", labels.getOrElse(index) { (index + 1).toString() })
                            put("freq", caps.frequencies.getOrElse(index) { 0f }.toDouble())
                            put("level", levels.getOrElse(index) { 0f }.toDouble())
                        },
                    )
                }
            })
            put("presets", JSONArray(KtvEqualizer.presetNames()))
        }
    }

    /** 远程点歌页面：调节均衡器（与 App 内均衡器弹窗等价）。 */
    private fun remoteEqualizer(json: JSONObject): LocalRemoteServer.ApiResponse {
        if (json.has("globalMix")) {
            if (!KtvEqualizer.globalMixSupported()) {
                return remoteError(400, "GLOBAL_MIX_UNSUPPORTED", getString(R.string.ui_p_020))
            }
            KtvEqualizer.setGlobalMix(json.optBoolean("globalMix"))
        }
        if (json.has("enabled")) {
            KtvEqualizer.setEnabled(json.optBoolean("enabled"))
        }
        if (json.has("preset")) {
            val name = json.optString("preset")
            if (name !in KtvEqualizer.presetNames()) {
                return remoteError(400, "BAD_PRESET", getString(R.string.ui_p_021))
            }
            KtvEqualizer.applyPreset(name)
        }
        if (json.has("levels")) {
            val array = json.optJSONArray("levels")
                ?: return remoteError(400, "BAD_LEVELS", getString(R.string.ui_p_022))
            val caps = KtvEqualizer.capabilities(this)
            val levels = FloatArray(caps.bandCount) { index ->
                array.optDouble(index, 0.0).toFloat().coerceIn(caps.minDb, caps.maxDb)
            }
            KtvEqualizer.setLevels(levels)
            KtvEqualizer.markCustomPreset()
        }
        main.post {
            syncEqualizerSessions()
            saveState()
            remoteStateVersion++
        }
        return remoteOk(equalizerJson().put("message", getString(R.string.ui_p_eq_updated)))
    }

    /** 远程搜索结果缓存有效期。 */
    private val SEARCH_CACHE_TTL_MS = 60_000L

    private fun remoteSearchJson(query: Map<String, String>): JSONObject {
        val keyword = query["q"].orEmpty().trim()
        val type = query["type"].orEmpty().lowercase(Locale.ROOT).let { if (it == "song") "song" else "singer" }
        val page = (query["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val pageSize = (query["pageSize"]?.toIntOrNull() ?: 20).coerceIn(1, 50)
        val offset = (page - 1) * pageSize
        // 命中缓存: 不再查 1GB 曲库(翻页/重复搜索基本瞬时)。
        val cacheKey = "$type|$keyword|$page|$pageSize"
        remoteSearchCache[cacheKey]?.let { (json, at) ->
            if (System.currentTimeMillis() - at < SEARCH_CACHE_TTL_MS) {
                runCatching { JSONObject(json) }.getOrNull()?.let { return it }
            }
        }
        val songs = if (library.muse.isAvailable() && keyword.isNotBlank()) {
            if (type == "singer") library.muse.searchSongsBySingerKeyword(keyword, offset, pageSize + 1)
            else library.muse.searchSongs(keyword, offset, pageSize + 1)
        } else {
            library.allSongs().asSequence().filter { song ->
                isVisibleSong(song) && keyword.isNotBlank() && if (type == "singer") {
                    song.singer.orEmpty().contains(keyword, true)
                } else {
                    (song.title + " " + song.pinyin).contains(keyword, true)
                }
            }.drop(offset).take(pageSize + 1).toMutableList()
        }
        val hasMore = songs.size > pageSize
        val pageSongs = songs.take(pageSize)
        val array = JSONArray()
        pageSongs.forEach { song ->
            remoteSongCache[stableId(song)] = song
            array.put(songJson(song))
        }
        while (remoteSongCache.size > 500) remoteSongCache.remove(remoteSongCache.keys.first())
        val payload = JSONObject().put("songs", array).put("page", page).put("pageSize", pageSize)
            .put("type", type).put("hasMore", hasMore)
        remoteSearchCache[cacheKey] = payload.toString() to System.currentTimeMillis()
        return payload
    }

    private fun remoteHotJson(query: Map<String, String>): JSONObject {
        val page = (query["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val pageSize = (query["pageSize"]?.toIntOrNull() ?: 10).coerceIn(1, 30)
        val offset = (page - 1) * pageSize
        val songs = if (library.muse.isAvailable()) {
            library.muse.hotSongs(offset, pageSize + 1)
        } else {
            library.allSongs().asSequence().filter(::isVisibleSong)
                .sortedByDescending { it.playCount }.drop(offset).take(pageSize + 1).toMutableList()
        }
        val hasMore = songs.size > pageSize
        val array = JSONArray()
        songs.take(pageSize).forEach { song ->
            remoteSongCache[stableId(song)] = song
            array.put(songJson(song))
        }
        while (remoteSongCache.size > 500) remoteSongCache.remove(remoteSongCache.keys.first())
        return JSONObject().put("songs", array).put("page", page).put("pageSize", pageSize)
            .put("hasPrevious", page > 1).put("hasMore", hasMore)
    }

    // ===== ori 手机点歌页扩展端点（全套界面移植）=====

    /** 把一页 Song 打包成手机端列表响应，并登记到点歌反查缓存。 */
    private fun remoteSongPageJson(
        songs: List<Song>, page: Int, pageSize: Int, hasMore: Boolean
    ): JSONObject {
        val array = JSONArray()
        songs.forEach { song ->
            remoteSongCache[stableId(song)] = song
            array.put(songJson(song))
        }
        while (remoteSongCache.size > 500) remoteSongCache.remove(remoteSongCache.keys.first())
        return JSONObject().put("songs", array).put("page", page).put("pageSize", pageSize)
            .put("hasPrevious", page > 1).put("hasMore", hasMore)
    }

    /** 分页返回歌手列表，供手机端"歌星"浏览弹窗使用。 */
    private fun remoteSingersJson(query: Map<String, String>): JSONObject {
        if (!library.muse.isAvailable()) throw IllegalStateException(getString(R.string.ui_a_0435))
        val page = (query["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val pageSize = (query["pageSize"]?.toIntOrNull() ?: 10).coerceIn(1, 50)
        val (area, type) = singerFilter(query["category"].orEmpty().trim())
        val keyword = query["q"].orEmpty().trim()
        val offset = (page - 1) * pageSize
        val rows = if (keyword.isEmpty()) library.muse.singers(area, type, offset, pageSize)
        else library.muse.searchSingers(keyword, area, type, offset, pageSize)
        val total = if (keyword.isEmpty()) library.muse.singerCount(area, type)
        else library.muse.searchSingerCount(keyword, area, type)
        val array = JSONArray()
        rows.forEach { row ->
            array.put(JSONObject()
                .put("id", row.getOrNull(0))
                .put("name", row.getOrNull(1))
                .put("type", row.getOrNull(2))
                .put("region", row.getOrNull(3))
                .put("popularity", row.getOrNull(4)?.toString()?.toIntOrNull() ?: 0)
                .put("image", row.getOrNull(5)))
        }
        return JSONObject().put("singers", array).put("page", page).put("pageSize", pageSize)
            .put("total", total).put("hasPrevious", page > 1)
            .put("hasMore", offset + pageSize < total)
    }

    /** 分页返回某歌手的歌曲。 */
    private fun remoteSingerSongsJson(query: Map<String, String>): JSONObject {
        if (!library.muse.isAvailable()) throw IllegalStateException(getString(R.string.ui_a_0435))
        val singerId = query["singerId"]?.trim().orEmpty()
        if (singerId.isEmpty()) throw IllegalArgumentException(getString(R.string.ui_a_0436))
        val page = (query["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val pageSize = (query["pageSize"]?.toIntOrNull() ?: 10).coerceIn(1, 50)
        val offset = (page - 1) * pageSize
        val songs = library.muse.songsBySinger(singerId, offset, pageSize + 1)
        val hasMore = songs.size > pageSize
        val total = library.muse.countSongsBySinger(singerId)
        return remoteSongPageJson(songs.take(pageSize), page, pageSize, hasMore)
            .put("total", total)
    }

    /** 分页返回全曲库歌曲，供手机端"歌名"浏览弹窗使用。 */
    private fun remoteSongCatalogJson(query: Map<String, String>): JSONObject {
        if (!library.muse.isAvailable()) throw IllegalStateException(getString(R.string.ui_a_0435))
        val page = (query["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val pageSize = (query["pageSize"]?.toIntOrNull() ?: 10).coerceIn(1, 50)
        val offset = (page - 1) * pageSize
        val keyword = query["q"].orEmpty().trim()
        val language = query["language"].orEmpty().trim()
        val wordCount = (query["wordcount"]?.toIntOrNull() ?: 0).coerceIn(0, 6)
        val songs: MutableList<Song>
        val total: Int
        when {
            keyword.isNotEmpty() -> {
                songs = library.muse.searchSongs(keyword, offset, pageSize)
                total = library.muse.searchSongCount(keyword)
            }
            language.isNotEmpty() && language != KtvRaw.get(R.string.ui_a_0069) -> {
                songs = library.muse.songsByLanguage(language, offset, pageSize)
                total = library.muse.countByLanguage(language)
            }
            wordCount > 0 -> {
                songs = library.muse.songsByWordCount(wordCount, offset, pageSize)
                total = library.muse.countByWordCount(wordCount)
            }
            else -> {
                songs = library.muse.hdSongs(offset, pageSize)
                total = library.muse.songCount()
            }
        }
        return remoteSongPageJson(songs, page, pageSize, offset + pageSize < total)
            .put("total", total)
    }

    /** 歌名浏览页的语种筛选项，取曲库 DISTINCT language。 */
    private fun remoteCatalogLanguagesJson(): JSONObject {
        val array = JSONArray().put(KtvRaw.get(R.string.ui_a_0069))
        if (library.muse.isAvailable()) library.muse.languages().forEach { array.put(it) }
        return JSONObject().put("languages", array)
    }

    /** 已下载歌曲：本地曲库中已落盘、可直接点播的歌曲。 */
    private fun remoteDownloadsJson(query: Map<String, String>): JSONObject {
        val page = (query["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val pageSize = (query["pageSize"]?.toIntOrNull() ?: 10).coerceIn(1, 30)
        val all = localSongInventory().values.toList()
        val offset = (page - 1) * pageSize
        val pageSongs = all.drop(offset).take(pageSize)
        return remoteSongPageJson(pageSongs, page, pageSize, offset + pageSize < all.size)
            .put("total", all.size)
    }

    /** 下载中/等待中的任务（plus 无 transferStatus，进度直接用 task.progress）。 */
    private fun remoteDownloadTasksJson(): JSONObject {
        if (Looper.myLooper() != main.looper) {
            val snapshot = java.util.concurrent.FutureTask<JSONObject> { remoteDownloadTasksJson() }
            check(main.post(snapshot)) { getString(R.string.ui_a_0437) }
            return try { snapshot.get(3, TimeUnit.SECONDS) }
            catch (error: Exception) { snapshot.cancel(false); throw error }
        }
        val activeStates = setOf(KtvRaw.get(R.string.ui_a_0014), KtvRaw.get(R.string.ui_a_0438),
            KtvRaw.get(R.string.ui_a_0084), KtvRaw.get(R.string.ui_a_0247))
        val array = JSONArray()
        downloads.filter { it.state in activeStates }.forEach { task ->
            val state = when (task.state) {
                KtvRaw.get(R.string.ui_a_0084) -> "downloading"
                KtvRaw.get(R.string.ui_a_0247) -> "cancelling"
                else -> "waiting"
            }
            val item = songJson(task.song)
                .put("downloadState", state)
                .put("downloadStateText", task.state)
                .put("downloadProgress", task.progress.coerceIn(0, 99))
                .put("downloadSpeed", 0L)
                .put("downloadCancelled", task.cancelled)
            remoteSongCache[stableId(task.song)] = task.song
            array.put(item)
        }
        while (remoteSongCache.size > 500) remoteSongCache.remove(remoteSongCache.keys.first())
        return JSONObject().put("tasks", array).put("total", array.length())
    }

    /** 排行榜：不带 playlistId 返回榜单分类，带 playlistId 返回该榜歌曲分页。 */
    private fun remoteRankJson(query: Map<String, String>): JSONObject {
        if (!library.muse.isAvailable()) throw IllegalStateException(getString(R.string.ui_a_0435))
        val playlistId = query["playlistId"]?.trim().orEmpty()
        if (playlistId.isEmpty()) {
            val categories = JSONArray()
            library.muse.rankPlaylists().forEach { row ->
                categories.put(JSONObject().put("id", row[0]).put("name", row[1])
                    .put("count", row[3]?.toIntOrNull() ?: 0))
            }
            return JSONObject().put("categories", categories)
        }
        val page = (query["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val pageSize = (query["pageSize"]?.toIntOrNull() ?: 10).coerceIn(1, 30)
        val offset = (page - 1) * pageSize
        val songs = library.muse.rankSongs(playlistId, offset, pageSize + 1)
        val hasMore = songs.size > pageSize
        val total = library.muse.rankSongCount(playlistId)
        return remoteSongPageJson(songs.take(pageSize), page, pageSize, hasMore)
            .put("total", total)
    }

    private fun remoteQueueJson(): JSONObject {
        val array = JSONArray()
        orderQueue?.toList()?.forEach { array.put(songJson(it)) }
        return JSONObject().put("queue", array)
    }

    private fun remoteOrder(json: JSONObject): LocalRemoteServer.ApiResponse {
        val id = json.optString("songId").trim()
        if (id.isEmpty()) return remoteError(400, "MISSING_SONG_ID", KtvRaw.get(R.string.ui_a_0439))
        val song = remoteSongCache[id] ?: library.allSongs().firstOrNull { stableId(it) == id }
            ?: return remoteError(404, "SONG_NOT_FOUND", KtvRaw.get(R.string.ui_a_0440))
        if (orderQueue?.any { stableId(it) == id } == true) {
            return remoteError(409, "ALREADY_QUEUED", KtvRaw.get(R.string.ui_a_0442))
        }
        main.post {
            addToQueue(song)
            remoteStateVersion++
            saveState()
        }
        return remoteOk(JSONObject().put("message", KtvRaw.get(R.string.ui_a_0443)), 201)
    }

    private fun remoteQueueMutation(method: String, id: String): LocalRemoteServer.ApiResponse {
        val song = orderQueue?.firstOrNull { stableId(it) == id }
            ?: return remoteError(404, "QUEUE_ITEM_NOT_FOUND", KtvRaw.get(R.string.ui_a_0444))
        if (stableId(currentSong ?: song) == id && currentSong != null) {
            return remoteError(409, "CURRENT_SONG", KtvRaw.get(R.string.ui_a_0445))
        }
        main.post {
            if (method == "PATCH") moveQueuedSongToNext(song) else deleteQueuedSong(song)
            remoteStateVersion++
        }
        return remoteOk(JSONObject().put("message", if (method == "PATCH") KtvRaw.get(R.string.ui_a_0446) else KtvRaw.get(R.string.ui_a_0447)))
    }

    private fun remotePlayerAction(json: JSONObject): LocalRemoteServer.ApiResponse {
        val action = json.optString("action")
        if (action !in arrayOf("pause", "resume", "next", "replay")) {
            return remoteError(400, "BAD_ACTION", KtvRaw.get(R.string.ui_a_0448))
        }
        main.post {
            when (action) {
                "pause" -> if (player?.isPlaying == true) togglePlay()
                "resume" -> if (player?.isPlaying != true) togglePlay()
                "next" -> playNext()
                "replay" -> replay()
            }
            remoteStateVersion++
        }
        return remoteOk(JSONObject().put("message", KtvRaw.get(R.string.ui_a_0449)))
    }

    private fun remoteAudio(json: JSONObject): LocalRemoteServer.ApiResponse {
        if (!json.has("vocalMode") && !json.has("musicVolume") && !json.has("micVolume")) {
            return remoteError(400, "MISSING_AUDIO_VALUE", KtvRaw.get(R.string.ui_a_0450))
        }
        val vocal = json.optString("vocalMode", "")
        if (vocal.isNotEmpty() && vocal !in arrayOf("original", "accompany")) {
            return remoteError(400, "BAD_VOCAL_MODE", KtvRaw.get(R.string.ui_a_0451))
        }
        val music = if (json.has("musicVolume")) json.optInt("musicVolume", -1) else null
        val mic = if (json.has("micVolume")) json.optInt("micVolume", -1) else null
        if ((music != null && music !in 0..100) || (mic != null && mic !in 0..100)) {
            return remoteError(400, "BAD_VOLUME", KtvRaw.get(R.string.ui_a_0452))
        }
        main.post {
            if (vocal.isNotEmpty()) originalVocal = vocal == "original"
            music?.let { musicVolume = it }
            mic?.let { micVolume = it }
            applyPlaybackMode()
            playerController?.setVocalMode(originalVocal)
            saveState()
            remoteStateVersion++
        }
        return remoteOk(JSONObject().put("message", KtvRaw.get(R.string.ui_a_0453)))
    }

    private fun remoteOk(data: JSONObject, statusCode: Int = 200): LocalRemoteServer.ApiResponse {
        data.put("ok", true).put("stateVersion", remoteStateVersion)
        return LocalRemoteServer.ApiResponse(statusCode, data.toString())
    }

    private fun remoteError(statusCode: Int, code: String, message: String): LocalRemoteServer.ApiResponse =
        LocalRemoteServer.ApiResponse(statusCode, JSONObject().put("ok", false).put("code", code)
            .put("error", message).put("stateVersion", remoteStateVersion).toString())

    private fun errorJson(e: Exception): String {
        return "{\"ok\":false,\"error\":\"" + e.message.toString().replace("\"", "'") + "\"}"
    }

    @Throws(Exception::class)
    private fun songJson(song: Song): JSONObject {
        val item = JSONObject()
        item.put("id", stableId(song))
        item.put("title", song.title)
        item.put("singer", song.singer)
        item.put("category", song.category)
        item.put("language", song.language)
        item.put("remote", song.remote)
        item.put("favorite", store.isFavorite(song))
        item.put("playCount", song.playCount)
        item.put("hasOriginal", hasUrl(song.originalUrl) || hasUrl(song.originalPath))
        item.put("hasAccompany", hasUrl(song.accompanyUrl) || hasUrl(song.accompanyPath))
        item.put("hasLyric", hasUrl(song.lyricUrl) || hasUrl(song.lyricPath))
        return item
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        try {
            // MARGIN=1 减小二维码四周静区白边(默认 4)，让二维码图案更大、更少白边(与 ori 一致)。
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            return bitmap
        } catch (e: Exception) {
            val fallback = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            fallback.eraseColor(Color.WHITE)
            return fallback
        }
    }

    private fun remoteUrl(): String {
        if (!remoteServer.isRunning() || remoteServer.port() <= 0) return ""
        val overrideHost = Settings.Global.getString(contentResolver, "maidong_remote_host")
            ?.takeIf { LocalRemoteServer.isPrivateIpv4(it) }
        val host = overrideHost ?: remoteServer.address() ?: return ""
        return "http://$host:${remoteServer.port()}/mobile"
    }

    /**
     * 选择"扫码点歌"应公布的局域网 IP。手机(如 S10)常有蜂窝(rmnet/10.x)、
     * 抓包代理(VPN tun)等多接口,若随机选到这些会被手机扫描后无法访问,
     * 导致扫码点歌连不上。这里按可靠性排序:
     *  1) 首选 up、非虚拟接口上的站点本地 IPv4(优先 wlan/eth 网卡);
     *  2) 退而求其次选任意非虚拟接口的私有 IPv4;
     *  3) 再选任意私有 IPv4(兼容个别厂商网卡命名);
     *  4) 都找不到返回空串(不返回 127.0.0.1,避免生成只能本机访问的无效二维码)。
     */
    private fun localIp(): String {
        var firstPrivate = ""
        var firstNonVirtual = ""
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                val name = network.name.orEmpty().lowercase(Locale.ROOT)
                val virtual = network.isVirtual || name.startsWith("tun") || name.startsWith("ppp") ||
                    name.startsWith("wg") || name.startsWith("rmnet") || name.startsWith("radio") ||
                    name.startsWith("ccmni") || name.startsWith("pdp") || name.contains("vpn")
                val up = network.isUp && !network.isLoopback
                val addresses = network.getInetAddresses()
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                    val host = address.hostAddress ?: continue
                    if (host.indexOf(':') >= 0 || !LocalRemoteServer.isPrivateIpv4(host)) continue
                    if (firstPrivate.isEmpty()) firstPrivate = host
                    if (up && !virtual && firstNonVirtual.isEmpty()) firstNonVirtual = host
                    if (up && !virtual && (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("en"))) {
                        return host
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        return firstNonVirtual.ifEmpty { firstPrivate }
    }

    private fun showScoreDialog() {
        val box = LinearLayout(this)
        box.setOrientation(LinearLayout.VERTICAL)
        box.addView(label(getString(R.string.ui_a_0454) + (if (scoreEnabled) getString(R.string.ui_a_0455) else getString(R.string.ui_a_0456)), 20, Color.WHITE))
        box.addView(label(getString(R.string.ui_a_0457) + scoreSummary(), 20, Color.LTGRAY))
        box.addView(sectionTitle(getString(R.string.ui_a_0458)))
        val list = listView()
        val adapter = tvAdapter()
        if (store.scoreHistory.isEmpty()) adapter.add(getString(R.string.ui_a_0459))
        else adapter.addAll(store.scoreHistory)
        list.setAdapter(adapter)
        box.addView(list, LinearLayout.LayoutParams(-1, dp(260)))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_a_0166))
            .setView(box)
            .setPositiveButton(
                if (scoreEnabled) KtvRaw.get(R.string.ui_a_0460) else KtvRaw.get(R.string.ui_a_0461),
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    scoreEnabled = !scoreEnabled
                    saveState()
                })
            .setNegativeButton(getString(R.string.ui_ok), null)
            .create().showForTv()
    }

    private fun showRecordings() {
        setPage("录音", getString(R.string.ui_a_0192))
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(280), -1))
        left.addView(sectionTitle(getString(R.string.ui_a_0462)))
        left.addView(
            button(
                if (recorder == null) getString(R.string.ui_a_0463) else getString(R.string.ui_a_0193),
                View.OnClickListener { v: View? -> toggleRecording() })
        )
        left.addView(button(getString(R.string.ui_a_0464), View.OnClickListener { v: View? -> showRecordings() }))
        left.addView(
            button(
                if (recordEnabled) KtvRaw.get(R.string.ui_a_0465) else KtvRaw.get(R.string.ui_a_0466),
                View.OnClickListener { v: View? ->
                    recordEnabled = !recordEnabled
                    if (!recordEnabled) stopRecording(true)
                    saveState()
                    showRecordings()
                })
        )
        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle(getString(R.string.ui_a_0467)))
        val list = listView()
        val adapter = tvAdapter()
        val files = recordDir().listFiles()
        if (files != null) {
            val values: MutableList<File> = ArrayList<File>()
            for (file in files) if (file.isFile()) values.add(file)
            Collections.sort<File?>(
                values,
                Comparator { a: File?, b: File? ->
                    b!!.lastModified().compareTo(a!!.lastModified())
                })
            for (file in values) adapter.add(file.getName() + "    " + formatSize(file.length()))
        }
        if (adapter.isEmpty()) adapter.add(getString(R.string.ui_a_0468))
        list.setAdapter(adapter)
        right.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showMarqueeSettings() {
        val box = LinearLayout(this)
        box.setOrientation(LinearLayout.VERTICAL)
        val input = EditText(this)
        input.setSingleLine(true)
        input.setText(marqueeText)
        input.setHint(getString(R.string.ui_a_0469))
        box.addView(label(getString(R.string.ui_a_0454) + (if (marqueeEnabled) getString(R.string.ui_a_0455) else getString(R.string.ui_a_0456)), 20, Color.WHITE))
        box.addView(input, LinearLayout.LayoutParams(-1, dp(58)))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_a_0470))
            .setView(box)
            .setPositiveButton(
                getString(R.string.ui_a_0471),
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    marqueeText = input.getText().toString().trim { it <= ' ' }
                    if (marqueeText!!.isEmpty()) marqueeText = getString(R.string.ui_a_0089)
                    saveState()
                    toast(getString(R.string.ui_a_0472))
                })
            .setNegativeButton(
                if (marqueeEnabled) getString(R.string.ui_a_0037) else getString(R.string.ui_a_0473),
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    marqueeEnabled = !marqueeEnabled
                    saveState()
                })
            .create().showForTv()
    }

    private fun showDisco() {
        setPage("迪斯科", getString(R.string.ui_a_0474))
        content!!.removeAllViews()
        val panel = panel()
        content!!.addView(panel, LinearLayout.LayoutParams(-1, -1))
        panel.addView(sectionTitle(getString(R.string.ui_a_0475)))
        val labels = arrayOf(getString(R.string.ui_a_0086), getString(R.string.ui_a_0476), getString(R.string.ui_a_0477), getString(R.string.ui_a_0478), getString(R.string.ui_a_0479), getString(R.string.ui_a_0480), getString(R.string.ui_a_0325), getString(R.string.ui_a_0481))
        val values = arrayOf(KtvRaw.get(R.string.ui_a_0086), KtvRaw.get(R.string.ui_a_0476), KtvRaw.get(R.string.ui_a_0477), KtvRaw.get(R.string.ui_a_0478), KtvRaw.get(R.string.ui_a_0479), KtvRaw.get(R.string.ui_a_0480), KtvRaw.get(R.string.ui_a_0325), KtvRaw.get(R.string.ui_a_0481))
        val grid = GridLayout(this)
        grid.setColumnCount(4)
        panel.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        for (i in labels.indices) {
            val mode = values[i]
            addTile(
                grid,
                labels[i],
                if (mode == lightMode) getString(R.string.ui_a_0482) else getString(R.string.ui_a_0483),
                View.OnClickListener { _ ->
                    lightMode = mode
                    if (KtvRaw.get(R.string.ui_a_0480) == lightMode) atmosphere = KtvRaw.get(R.string.ui_a_0326)
                    if (KtvRaw.get(R.string.ui_a_0325) == lightMode) atmosphere = KtvRaw.get(R.string.ui_a_0325)
                    saveState()
                    showDisco()
                })
        }
        panel.addView(
            label(
                getString(R.string.ui_a_0484),
                18,
                Color.LTGRAY
            )
        )
    }

    private fun showLocalMovies() {
        setPage("本地影片", getString(R.string.ui_a_0175))
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(300), -1))
        left.addView(sectionTitle(getString(R.string.ui_a_0485)))
        left.addView(button(getString(R.string.ui_a_0486), View.OnClickListener { v: View? -> showLocalMovies() }))
        left.addView(label(getString(R.string.ui_p_082, AppPaths.moviesDir.absolutePath), 18, Color.LTGRAY))

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle(getString(R.string.ui_a_0488)))
        val list = listView()
        val adapter = tvAdapter()
        val movies = localMovies()
        for (file in movies) adapter.add(file.getName() + "    " + formatSize(file.length()))
        if (adapter.isEmpty()) adapter.add(getString(R.string.ui_a_0489))
        list.setAdapter(adapter)
        list.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: kotlin.Long ->
            if (pos >= 0 && pos < movies.size) playMovie(movies.get(pos))
        })
        right.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun networkSummary(): String {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager?
        val info = if (cm == null) null else cm.getActiveNetworkInfo()
        val wifi = getApplicationContext().getSystemService(WIFI_SERVICE) as WifiManager?
        val wifiState =
            if (wifi == null) KtvRaw.get(R.string.ui_a_0361) else (if (wifi.isWifiEnabled()) getString(R.string.ui_a_0455) else getString(R.string.ui_a_0456))
        return (KtvRaw.get(R.string.ui_a_0490) + (if (info != null && info.isConnected()) KtvRaw.get(R.string.ui_a_0358) else KtvRaw.get(R.string.ui_a_0359))
                + KtvRaw.get(R.string.ui_a_0491) + (if (info == null) KtvRaw.get(R.string.ui_a_0361) else info.getTypeName())
                + "\nWiFi：" + wifiState
                + "\nIP：" + localIp())
    }

    private fun testCatalogSource() {
        val url: String = prefs()!!.getString(KEY_CATALOG_URL, "")!!
        if (url.trim { it <= ' ' }.isEmpty()) {
            showCatalogDialog()
            return
        }
        busy(true, getString(R.string.ui_a_0492))
        io.execute(Runnable {
            try {
                val array = CatalogClient().fetch(url)
                main.post(Runnable { busy(false, getString(R.string.ui_a_0493) + array.length() + getString(R.string.ui_p_064)) })
            } catch (e: Exception) {
                main.post(Runnable { busy(false, getString(R.string.ui_a_0494) + e.message) })
            }
        })
    }

    private fun openTool(tool: String?) {
        if (KtvRaw.get(R.string.ui_a_0371) == tool) showSongs(KtvRaw.get(R.string.ui_a_0069))
        else if (KtvRaw.get(R.string.ui_a_0150) == tool) showLanguages()
        else if (KtvRaw.get(R.string.ui_a_0152) == tool) showWordCounts()
        else if (KtvRaw.get(R.string.ui_a_0372) == tool) showSingers()
        else if (KtvRaw.get(R.string.ui_a_0161) == tool) showDownloads()
        else if (KtvRaw.get(R.string.ui_a_0373) == tool) showUdisk()
        else if (KtvRaw.get(R.string.ui_a_0374) == tool) showLocalMovies()
        else if (KtvRaw.get(R.string.ui_a_0174) == tool) showDisco()
        else if (KtvRaw.get(R.string.ui_a_0171) == tool) showMobileRemoteDialog()
        else if (KtvRaw.get(R.string.ui_a_0375) == tool) showScoreDialog()
    }

    private fun sendTableBroadcast(text: String?) {
        if (!tableBroadcastEnabled) {
            toast(getString(R.string.ui_a_0495))
            return
        }
        var value: String? = if (text == null) "" else text.trim { it <= ' ' }
        if (value!!.isEmpty()) value = tableBroadcastText
        if (value == null || value.trim { it <= ' ' }.isEmpty()) value = KtvRaw.get(R.string.ui_a_0090)
        tableBroadcastText = value
        marqueeEnabled = true
        marqueeText = value
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        store.broadcastHistory.add(0, stamp + "  " + value)
        while (store.broadcastHistory.size > 100) store.broadcastHistory.removeAt(store.broadcastHistory.size - 1)
        saveState()
        status!!.setText(getString(R.string.ui_a_0496) + value + "（" + tableBroadcastSeconds + getString(R.string.ui_a_0497))
        toast(getString(R.string.ui_a_0498))
    }

    private fun applyScreenBrightness() {
        val attrs = getWindow().getAttributes()
        attrs.screenBrightness = screenBrightness / 100f
        getWindow().setAttributes(attrs)
    }

    private fun removeMissingQueueItems() {
        val before = orderQueue!!.size
        for (i in orderQueue.indices.reversed()) {
            val song = orderQueue.get(i)
            val localPath = song.path
            if (!song.remote && (localPath.isNullOrEmpty() || !File(localPath).exists())) {
                orderQueue.removeAt(i)
            }
        }
        saveState()
        toast(getString(R.string.ui_a_0499) + (before - orderQueue.size) + getString(R.string.ui_p_064))
    }

    private fun toggleRecording() {
        if (!recordEnabled) {
            toast(getString(R.string.ui_a_0500))
            return
        }
        if (recorder == null) startRecording()
        else stopRecording(true)
    }

    private fun startRecording() {
        if (Build.VERSION.SDK_INT >= 23
            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf<String>(Manifest.permission.RECORD_AUDIO), 11)
            return
        }
        // MediaRecorder 需要独占麦克风: 先暂停 MicActivator 占用。
        micActivator.pause()
        try {
            val dir = recordDir()
            if (!dir.exists()) dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            val now = if (orderQueue!!.isEmpty()) null else orderQueue.get(0)
            val name =
                (if (now == null) "ktv" else safeFileName(now.title + "-" + now.singer)) + "-" + stamp + ".m4a"
            currentRecording = File(dir, name)
            recorder = MediaRecorder()
            recorder!!.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder!!.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder!!.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder!!.setOutputFile(currentRecording!!.getAbsolutePath())
            recorder!!.prepare()
            recorder!!.start()
            toast(getString(R.string.ui_a_0463))
            status!!.setText(getString(R.string.ui_a_0501) + currentRecording!!.getName())
            if (KtvRaw.get(R.string.ui_a_0108) == currentPage) showHome()
        } catch (e: Exception) {
            // 录音启动失败: 释放 recorder 并恢复麦克风输入占用。
            if (recorder != null) stopRecording(false) else micActivator.resume()
            toast(getString(R.string.ui_a_0502) + e.message)
        }
    }

    private fun stopRecording(notify: Boolean) {
        if (recorder == null) return
        try {
            recorder!!.stop()
        } catch (ignored: Exception) {
        }
        try {
            recorder!!.release()
        } catch (ignored: Exception) {
        }
        recorder = null
        // 录音结束 → 恢复麦克风输入占用(演唱声音输出通道)。
        micActivator.resume()
        if (notify && currentRecording != null) toast(getString(R.string.ui_a_0503) + currentRecording!!.getName())
        currentRecording = null
        if (KtvRaw.get(R.string.ui_a_0192) == currentPage) showRecordings()
        else if (KtvRaw.get(R.string.ui_a_0108) == currentPage) showHome()
    }

    private fun recordScore(song: Song?) {
        if (!scoreEnabled || song == null) return
        val value = 60 + abs((stableId(song) + System.currentTimeMillis() / 60000L).hashCode() % 40)
        store.lastScore = value
        store.lastScoreSong = song.displayTitle()
        store.scoreHistory.add(0, value.toString() + getString(R.string.ui_a_0504) + song.displayTitle())
        while (store.scoreHistory.size > 100) store.scoreHistory.removeAt(store.scoreHistory.size - 1)
    }

    private fun scoreSummary(): String {
        if (store.lastScore <= 0 || store.lastScoreSong == null || store.lastScoreSong.isEmpty()) return KtvRaw.get(R.string.ui_a_0505)
        return store.lastScore.toString() + getString(R.string.ui_a_0506) + store.lastScoreSong
    }

    private fun recordDir(): File {
        return AppPaths.recordsDir
    }

    private fun localMovies(): MutableList<File> {
        val out: MutableList<File> = ArrayList<File>()
        scanMovieDir(
            AppPaths.moviesDir,
            out
        )
        val storage = File("/storage")
        val roots = storage.listFiles()
        if (roots != null) {
            for (root in roots) {
                val name = root.getName()
                if ("emulated" == name || "self" == name) continue
                scanMovieDir(File(root, "movies"), out)
                scanMovieDir(File(root, "Movies"), out)
            }
        }
        Collections.sort<File?>(
            out,
            Comparator { a: File?, b: File? ->
                a!!.getName().compareTo(b!!.getName(), ignoreCase = true)
            })
        return out
    }

    private fun localApks(): MutableList<File> {
        val out: MutableList<File> = ArrayList<File>()
        scanApkDir(AppPaths.appsDir, out)
        val storage = File("/storage")
        val roots = storage.listFiles()
        if (roots != null) {
            for (root in roots) {
                val name = root.getName()
                if ("emulated" == name || "self" == name) continue
                scanApkDir(File(root, "apps"), out)
                scanApkDir(File(root, "apk"), out)
            }
        }
        Collections.sort<File?>(
            out,
            Comparator { a: File?, b: File? ->
                b!!.getName().compareTo(a!!.getName(), ignoreCase = true)
            })
        return out
    }

    private fun scanApkDir(dir: File, out: MutableList<File>) {
        val files = dir.listFiles()
        if (files == null) return
        for (file in files) {
            if (file.isDirectory()) scanApkDir(file, out)
            else if (file.getName().lowercase().endsWith(".apk")) out.add(file)
        }
    }

    private fun installApk(apk: File) {
        try {
            val uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (e: Exception) {
            toast(getString(R.string.ui_a_0507) + e.message)
        }
    }

    private fun scanMovieDir(dir: File, out: MutableList<File>) {
        val files = dir.listFiles()
        if (files == null) return
        for (file in files) {
            if (file.isDirectory()) scanMovieDir(file, out)
            else if (isMovie(file.getName())) out.add(file)
        }
    }

    private fun isMovie(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi")
                || lower.endsWith(".mov") || lower.endsWith(".ts") || lower.endsWith(".flv")
    }

    private fun playMovie(file: File) {
        if (player == null) showHome()
        try {
            releaseVocalPlayer()
            currentSong = null
            clearLyrics()
            player!!.setOnPreparedListener(OnPreparedListener { mp: MediaPlayer? ->
                player!!.start()
                status!!.setText(getString(R.string.ui_a_0508) + file.getName())
            })
            player!!.setOnCompletionListener(OnCompletionListener { mp: MediaPlayer? ->
                status!!.setText(getString(R.string.ui_a_0509) + file.getName())
                if (autoNext && !orderQueue!!.isEmpty()) play(orderQueue.get(0))
            })
            player!!.setVideoURI(Uri.fromFile(file))
        } catch (e: Exception) {
            toast(getString(R.string.ui_a_0510) + e.message)
        }
    }

    private fun safeFileName(value: String?): String {
        return if (value == null) "ktv" else value.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
    }

    private fun backupDir(): File {
        return AppPaths.backupDir
    }

    private fun backupConfig() {
        try {
            val dir = backupDir()
            if (!dir.exists()) dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            copyFile(store.stateFile(), File(dir, "state-" + stamp + ".json"))
            if (library.catalogFile().exists()) copyFile(
                library.catalogFile(),
                File(dir, "catalog-" + stamp + ".json")
            )
            toast(getString(R.string.ui_a_0511))
            showBackupTools()
        } catch (e: Exception) {
            toast(getString(R.string.ui_a_0512) + e.message)
        }
    }

    private fun restoreConfig() {
        val dir = backupDir()
        val files = dir.listFiles()
        if (files == null) {
            toast(getString(R.string.ui_a_0513))
            return
        }
        var newestState: File? = null
        var newestCatalog: File? = null
        for (file in files) {
            if (file.getName().startsWith("state-") && file.getName().endsWith(".json")
                && (newestState == null || file.lastModified() > newestState.lastModified())
            ) newestState = file
            if (file.getName().startsWith("catalog-") && file.getName().endsWith(".json")
                && (newestCatalog == null || file.lastModified() > newestCatalog.lastModified())
            ) newestCatalog = file
        }
        val state = newestState
        val catalog = newestCatalog
        if (state == null && catalog == null) {
            toast(getString(R.string.ui_a_0514))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_a_0394))
            .setMessage(getString(R.string.ui_a_0515))
            .setPositiveButton(
                getString(R.string.ui_a_0516),
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    try {
                        if (state != null) copyFile(state, store.stateFile())
                        if (catalog != null) copyFile(catalog, library.catalogFile())
                        store.load()
                        loadStateFromStore()
                        applyScreenBrightness()
                        refreshLibrary(Runnable {
                            toast(getString(R.string.ui_a_0517))
                            showHome()
                        })
                    } catch (e: Exception) {
                        toast(getString(R.string.ui_a_0518) + e.message)
                    }
                })
            .setNegativeButton(getString(R.string.ui_cancel), null)
            .create().showForTv()
    }

    @Throws(Exception::class)
    private fun copyFile(source: File?, target: File) {
        if (source == null || !source.exists()) return
        val parent = target.getParentFile()
        if (parent != null && !parent.exists()) parent.mkdirs()
        BufferedInputStream(FileInputStream(source)).use { `in` ->
            FileOutputStream(target, false).use { out ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while ((`in`.read(buffer).also { read = it }) > 0) out.write(buffer, 0, read)
            }
        }
    }

    private fun clamp(value: Int, min: Int, max: Int): Int {
        return max(min, min(max, value))
    }

    private fun formatSize(bytes: kotlin.Long): String {
        if (bytes > 1024L * 1024L * 1024L) return String.format(
            Locale.ROOT,
            "%.1f GB",
            bytes / 1024f / 1024f / 1024f
        )
        if (bytes > 1024L * 1024L) return String.format(
            Locale.ROOT,
            "%.1f MB",
            bytes / 1024f / 1024f
        )
        return bytes.toString() + " B"
    }

    private fun populateSingerList(adapter: ArrayAdapter<String?>, group: String?) {
        adapter.clear()
        val names: MutableList<String?> = ArrayList<String?>()
        for (song in library.allSongs()) {
            if (!isVisibleSong(song) || !singerMatchesGroup(song, group)) continue
            val name = singerName(song)
            if (!names.contains(name)) names.add(name)
        }
        Collections.sort<String?>(
            names,
            Comparator { obj: String?, str: String? -> obj!!.compareTo(str!!, ignoreCase = true) })
        if (KtvRaw.get(R.string.ui_a_0213) == group) {
            Collections.sort<String?>(
                names,
                Comparator { a: String?, b: String? ->
                    Integer.compare(
                        singerPlayCount(b),
                        singerPlayCount(a)
                    )
                })
        }
        adapter.addAll(names)
        adapter.notifyDataSetChanged()
        visibleSongs.clear()
        renderSongs()
        header!!.setText(getString(R.string.ui_p_066) + group + "  " + names.size)
    }

    private fun singerPlayCount(singer: String?): Int {
        var count = 0
        for (song in library.allSongs()) {
            if (isVisibleSong(song) && singerName(song) == singer) count += song.playCount
        }
        return count
    }

    private fun singerMatchesGroup(song: Song, group: String?): Boolean {
        if (KtvRaw.get(R.string.ui_a_0210) == group) return true
        val text = (((if (song.singer == null) "" else song.singer) + " "
                + (if (song.category == null) "" else song.category) + " "
                + (if (song.language == null) "" else song.language))).lowercase()
        if (KtvRaw.get(R.string.ui_a_0213) == group) return song.playCount > 0 || !song.remote
        if ("大陆/内地" == group) return text.contains("大陆") || text.contains("内地") || text.contains(
            "国语"
        )
        if ("港台" == group) return text.contains("香港") || text.contains("台湾") || text.contains(
            "港台"
        ) || text.contains("粤语") || text.contains("闽南")
        if ("日韩" == group) return text.contains("日本") || text.contains("韩国") || text.contains(
            "日韩"
        ) || text.contains("日语") || text.contains("韩语")
        if ("欧美" == group) return text.contains("欧美") || text.contains("英语") || text.contains(
            "英文"
        ) || text.contains("外语")
        if ("组合/乐队" == group) return text.contains("组合") || text.contains("乐队") || text.contains(
            "band"
        ) || text.contains("group")
        return !singerMatchesGroup(song, KtvRaw.get(R.string.ui_a_0214)) && !singerMatchesGroup(
            song,
            KtvRaw.get(R.string.ui_a_0077)
        ) && !singerMatchesGroup(song, KtvRaw.get(R.string.ui_a_0078)) && !singerMatchesGroup(
            song,
            KtvRaw.get(R.string.ui_a_0079)
        ) && !singerMatchesGroup(song, KtvRaw.get(R.string.ui_a_0215))
    }

    private fun filterLanguage(language: String?) {
        visibleSongs.clear()
        for (song in library.allSongs()) {
            val value = song.language?.takeIf(String::isNotEmpty) ?: KtvRaw.get(R.string.ui_a_0080)
            if (isVisibleSong(song) && (KtvRaw.get(R.string.ui_a_0069) == language || value == language)) visibleSongs.add(
                song
            )
        }
        Collections.sort(visibleSongs, compareBy { it.title!!.lowercase() })
        renderSongs()
    }

    private fun filterWordCount(group: String?) {
        visibleSongs.clear()
        for (song in library.allSongs()) {
            val count = songWordCount(song.title)
            if (isVisibleSong(song) && (KtvRaw.get(R.string.ui_a_0069) == group || wordGroupMatches(
                    group,
                    count
                ))
            ) visibleSongs.add(song)
        }
        Collections.sort(visibleSongs, compareBy { it.title!!.lowercase() })
        renderSongs()
    }

    private fun wordGroupMatches(group: String?, count: Int): Boolean {
        if (KtvRaw.get(R.string.ui_a_0222) == group) return count == 1
        if (KtvRaw.get(R.string.ui_a_0223) == group) return count == 2
        if (KtvRaw.get(R.string.ui_a_0224) == group) return count == 3
        if (KtvRaw.get(R.string.ui_a_0225) == group) return count == 4
        if (KtvRaw.get(R.string.ui_a_0226) == group) return count == 5
        return KtvRaw.get(R.string.ui_a_0227) == group && count >= 6
    }

    private fun songWordCount(title: String?): Int {
        var count = 0
        val value = if (title == null) "" else title
        for (i in 0 until value.length) {
            val ch = value.get(i)
            if (Character.isLetterOrDigit(ch) || (ch >= '\u4e00' && ch <= '\u9fa5')) count++
        }
        return count
    }

    private fun singerName(song: Song): String {
        return song.singer?.takeIf(String::isNotEmpty) ?: KtvRaw.get(R.string.ui_a_0532)
    }

    private fun isVisibleSong(song: Song): Boolean {
        return !store.hiddenSongIds.contains(stableId(song))
    }

    private fun filterSongs(category: String?, query: String?) {
        val q = if (query == null) "" else query.trim { it <= ' ' }
        // 优先使用数据库搜索(64 万+歌曲)
        if (library.muse.isAvailable() && !q.isEmpty()) {
            browseMode = "search"
            browseParam = q
            browsePage = 0
            loadBrowsePage()
            return
        }
        // 数据库不可用或空查询时,回退到本地文件扫描
        val ql = q.lowercase()
        visibleSongs.clear()
        for (song in library.allSongs()) {
            val categoryOk = KtvRaw.get(R.string.ui_a_0069) == category
                    || (song.category != null && song.category == category)
                    || (KtvRaw.get(R.string.ui_a_0198) == category && !song.remote)
                    || (KtvRaw.get(R.string.ui_a_0199) == category && song.remote)
            val text =
                (song.title + " " + song.singer + " " + song.category + " " + song.language + " " + song.pinyin).lowercase()
            if (isVisibleSong(song) && categoryOk && (ql.isEmpty() || text.contains(ql))) visibleSongs.add(
                song
            )
        }
        Collections.sort(visibleSongs, compareBy { it.title!!.lowercase() })
        renderSongs()
        if (pageInfo != null) pageInfo!!.setText(getString(R.string.ui_p_067) + visibleSongs.size + getString(R.string.ui_p_064))
    }

    /**
     * 加载当前分页浏览模式的歌曲列表。
     * 根据 browseMode 和 browseParam 从数据库查询对应页的数据。
     */
    /**
     * 安全解析整数。
     * 
     * @param value        字符串
     * @param defaultValue 默认值
     * @return 解析结果
     */
    private fun parseIntSafe(value: String?, defaultValue: Int): Int {
        if (value == null || value.isEmpty()) return defaultValue
        try {
            return value.trim { it <= ' ' }.toInt()
        } catch (e: Exception) {
            return defaultValue
        }
    }

    /**
     * 公共点歌方法:将歌曲加入已点队列并开始播放。
     * 
     * 
     * 供 [SongSearchFragment] 等外部组件调用,实现点歌功能。
     * 
     * @param song 要点播的歌曲
     */
    fun orderSong(song: Song) {
        handleSong(song)
    }

    val muse: MuseDatabase
        /**
         * 获取 Muse 曲库数据库访问层(供 Fragment 查询歌曲/歌手/排行)。
         * 
         * @return 全局 MuseDatabase 实例
         */
        get() = library.muse

    val playbackPosition: Int
        get() {
            try {
                return player?.currentPosition ?: 0
            } catch (ignored: Exception) {
                return 0
            }
        }

    /**
     * 语言切换专用的一次性续播状态。只保存在内存，不落库，避免影响普通冷启动；
     * 时间戳用于丢弃超时的残留状态（例如切换后进程异常未完成的场景）。
     */
    private object LanguageSwitchPlayback {
        private const val MAX_AGE_MS = 60_000L

        data class Pending(
            val stableId: String,
            val positionMs: Int,
            val shouldPlay: Boolean,
            val savedAt: Long,
        )

        @Volatile
        private var pending: Pending? = null

        fun capture(song: Song?, positionMs: Int, shouldPlay: Boolean) {
            pending = song?.let {
                Pending(
                    stableId = KtvStore.stableId(it),
                    positionMs = maxOf(0, positionMs),
                    shouldPlay = shouldPlay,
                    savedAt = System.currentTimeMillis(),
                )
            }
            pending?.let {
                Log.i(TAG, "LANG-RESUME capture id=${it.stableId} pos=${it.positionMs} play=${it.shouldPlay}")
            }
        }

        fun consume(song: Song?): Pending? {
            val value = pending ?: return null
            pending = null
            if (song == null) return null
            if (System.currentTimeMillis() - value.savedAt > MAX_AGE_MS) return null
            if (KtvStore.stableId(song) != value.stableId) return null
            Log.i(TAG, "LANG-RESUME restore id=${value.stableId} pos=${value.positionMs} play=${value.shouldPlay}")
            return value
        }

        fun clear() {
            pending = null
        }
    }

    fun resumeMainPlaybackAt(positionMs: Int) {
        try {
            if (player != null && currentSong != null) {
                player!!.seekTo(max(0, positionMs))
                player!!.start()
                startVocalIfReady()
                updateBottomBar(currentSong, true)
            }
        } catch (ignored: Exception) {
        }
    }

    /**
     * 播放指定歌曲(供已点列表点击播放)。
     * 
     * @param song 要播放的歌曲
     */
    fun playSong(song: Song?) {
        play(song)
    }

    /**
     * 切换到下一首歌曲(供已点列表切歌操作)。
     */
    fun playNextSong() {
        playNext()
    }

    private fun handleSong(song: Song) {
        addToQueue(song)
        if (currentPage == "已点" || currentPage == "首页") renderQueue()
    }

    private fun download(song: Song) {
        val mainUrl = if (song.downloadUrl == null || song.downloadUrl!!.trim { it <= ' ' }
                .isEmpty()) song.videoUrl else song.downloadUrl
        if (mainUrl == null || mainUrl.trim { it <= ' ' }.isEmpty()) {
            toast(getString(R.string.ui_p_023))
            return
        }
        val task = DownloadTask(song)
        downloads.add(task)
        busy(true, getString(R.string.ui_p_024) + song.title)
        io.execute(Runnable {
            val target = library.targetFor(song)
            try {
                downloadToFile(mainUrl, target, task, 0, 75)
                val originalFile = sidecarFile(target, ".original", song.originalUrl)
                val accompanyFile = sidecarFile(target, ".accompany", song.accompanyUrl)
                val lyricFile = sidecarFile(target, "", song.lyricUrl)
                if (hasUrl(song.originalUrl)) downloadToFile(
                    song.originalUrl,
                    originalFile,
                    task,
                    75,
                    85
                )
                if (hasUrl(song.accompanyUrl)) downloadToFile(
                    song.accompanyUrl,
                    accompanyFile,
                    task,
                    85,
                    95
                )
                if (hasUrl(song.lyricUrl)) downloadToFile(song.lyricUrl, lyricFile, task, 95, 99)
                if (task.cancelled) throw InterruptedException("cancelled")
                task.state = KtvRaw.get(R.string.ui_a_0110)
                task.progress = 100
                val local = local(target.getAbsolutePath(), target.getName())
                local.originalPath =
                    if (originalFile.exists()) originalFile.getAbsolutePath() else ""
                local.accompanyPath =
                    if (accompanyFile.exists()) accompanyFile.getAbsolutePath() else ""
                local.lyricPath = if (lyricFile.exists()) lyricFile.getAbsolutePath() else ""
                main.post(Runnable {
                    orderQueue!!.add(local)
                    saveState()
                    refreshLibrary(null)
                    busy(false, getString(R.string.ui_p_025) + local.title)
                    if (currentTabIndex == 6) loadDownloadedList()
                })
            } catch (e: InterruptedException) {
                task.state = KtvRaw.get(R.string.ui_a_0245)
                target.delete()
                cleanupSidecars(target)
                main.post(Runnable {
                    busy(false, getString(R.string.ui_p_026) + song.title)
                    if (currentTabIndex == 6) loadDownloadedList()
                })
            } catch (e: Exception) {
                task.state = "失败：" + e.message
                main.post(Runnable {
                    busy(false, getString(R.string.ui_p_027) + e.message)
                    if (currentTabIndex == 6) loadDownloadedList()
                })
            }
        })
    }

    @Throws(Exception::class)
    private fun downloadToFile(
        url: String?,
        target: File?,
        task: DownloadTask,
        base: Int,
        span: Int
    ) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setConnectTimeout(10000)
        conn.setReadTimeout(30000)
        val code = conn.getResponseCode()
        check(!(code < 200 || code >= 300)) { "HTTP " + code }
        val total = conn.getContentLength()
        var done = 0
        task.state = KtvRaw.get(R.string.ui_a_0084)
        try {
            BufferedInputStream(conn.getInputStream()).use { `in` ->
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while ((`in`.read(buffer).also { read = it }) > 0) {
                        if (task.cancelled) throw InterruptedException("cancelled")
                        out.write(buffer, 0, read)
                        done += read
                        if (total > 0) task.progress = min(99, base + (done * span) / total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun hasUrl(value: String?): Boolean {
        return value != null && !value.trim { it <= ' ' }.isEmpty()
    }

    private fun sidecarFile(media: File, suffix: String?, url: String?): File {
        var ext = ".mp3"
        if (url != null) {
            val dot = url.lastIndexOf('.')
            val q = url.indexOf('?', dot)
            if (dot >= 0) ext = url.substring(dot, if (q > dot) q else url.length)
            if (ext.length > 8 || ext.contains("/")) ext = ".mp3"
        }
        val path = media.getAbsolutePath()
        val dot = path.lastIndexOf('.')
        val base = if (dot > 0) path.substring(0, dot) else path
        return File(base + suffix + ext)
    }

    private fun cleanupSidecars(media: File) {
        val dir = media.getParentFile()
        if (dir == null) return
        val path = media.getAbsolutePath()
        val dot = path.lastIndexOf('.')
        val base = if (dot > 0) path.substring(0, dot) else path
        val files = dir.listFiles()
        if (files == null) return
        for (file in files) {
            val value = file.getAbsolutePath()
            if (value.startsWith(base + ".original") || value.startsWith(base + ".accompany") || value.startsWith(
                    base + ".lrc"
                )
            ) {
                file.delete()
            }
        }
    }

    private fun cancelAllDownloads() {
        for (task in downloads) {
            if (KtvRaw.get(R.string.ui_a_0110) != task.state) {
                task.cancelled = true
                task.state = KtvRaw.get(R.string.ui_a_0247)
            }
        }
        showDownloads()
    }

    /**
     * 判断歌曲是否已有可播放的本地文件（**廉价**检查：只看存在与大小）。
     *
     * ⚠️ 播放路径上不要用 `Song.hasLocalFile()`：它内部是 `SongFileValidator.inspect`，
     * 会在文件**头尾各读约 4.3MB** 做 TS 包校验 —— 极米 H1S 这类慢存储上一次调用就是好几秒，
     * 足以让主线程 ANR（用户感知即"按了切歌卡死"）。深度校验统一交给后台
     * （`localSongInventory()` / `SongOkDownloadManager.isDownloaded()`）完成。
     */
    private fun hasPlayableLocalFile(song: Song?): Boolean {
        val path = song?.path?.takeIf { it.isNotEmpty() } ?: return false
        val file = File(path)
        return file.isFile && file.length() >= SongFileValidator.MIN_VALID_FILE_SIZE
    }

    private fun play(song: Song?, startPositionMs: Int = 0, playAfterPrepare: Boolean = true) {
        if (player == null) player = videoBackground
        if (song == null) return
        try {
            // 远程日志: 点歌使用的源(本地文件 / 在线源)。
            MiliLog.info(
                "PLAY id=${extractTid(song)} title=${song.title} src=" +
                    (if (hasPlayableLocalFile(song)) "local" else "online"),
            )
            // 取消仍在后台扫描的“已点播完后随机本地歌曲”请求，避免抢占新点歌曲。
            randomLocalRequestSerial++
            playbackGeneration++
            songRetryCount = 0 // 重置重试计数
            playWhenPrepared = playAfterPrepare
            hidePlaybackNoticeNow()
            // 切歌/点歌的统一入口：先彻底停掉旧主播放器，再释放副音轨。
            // 偶发的"两首歌同时响"是旧音源(主播放器或副音轨)在切歌时没停干净残留下来，
            // 这里在起点强制清一次，保证切歌后只有新歌在播。IJK 的 stop 在未 prepared 时
            // 可能抛异常，用 runCatching 兜住；playLocalFile 后续会重新 prepare + start。
            runCatching { player?.stopPlayback() }
            releaseVocalPlayer()
            currentMediaPlayer = null
            currentSong = song
            loadLyrics(song)
            persistRuntimeState()

            if (playerController != null) {
                playerController!!.updateSongInfo(song)
                playerController!!.setVocalMode(originalVocal)
            }

            updateBottomBar(song, playAfterPrepare)
            scheduleAutoFullscreen()

            if (hasPlayableLocalFile(song)) {
                playLocalFile(song, startPositionMs)
            } else {
                playbackPreparing = true
                // 统一使用支持断点续传和下载地址重试的下载管理器。
                if (isDownloading(song)) {
                    showDownloadProgressFor(song)
                    return
                }
                toast(getString(R.string.ui_p_029) + song.title + "...")
                showDownloadProgressFor(song)
                startDownloadAndPlay(song)
            }
        } catch (e: Exception) {
            toast(getString(R.string.ui_a_0536) + e.message)
        }
    }

    private fun playLocalFile(song: Song, startPositionMs: Int = 0) {
        try {
            playbackPreparing = true
            val localPath = song.path
            if (localPath.isNullOrEmpty()) {
                playbackPreparing = false
                startDownloadAndPlay(song)
                return
            }
            val f = File(localPath)
            val inspection = SongFileValidator.inspect(f, SongFileValidator.requiresTransportStream(f))
            if (!inspection.valid) {
                Log.w(TAG, "Invalid local file: ${song.path}, ${inspection.reason}")
                if (f.exists()) runCatching { f.delete() }
                song.path = null
                toast(getString(R.string.ui_p_030))
                startDownloadAndPlay(song)
                return
            }
            Log.i(
                TAG,
                "playLocalFile song=${stableId(song)} generation=$playbackGeneration " +
                    "startPositionMs=$startPositionMs path=${f.absolutePath} size=${f.length()}",
            )
            // 自动清理以文件时间作为最近使用时间；每次实际播放都刷新，避免常唱歌曲被误删。
            runCatching { f.setLastModified(System.currentTimeMillis()) }
            player!!.stopPlayback()
            setupPlayerListeners(song, startPositionMs, playbackGeneration)
            player!!.setVideoURI(Uri.fromFile(f))
            if (playWhenPrepared) player!!.start()
        } catch (e: Exception) {
            playbackPreparing = false
            Log.e(TAG, "playLocalFile error: " + e.message, e)
            toast(getString(R.string.ui_a_0543))
        }
    }

    /**
     * 完整下载后播放。VideoView不支持流式TS，必须下载完再播。
     * 使用256KB大缓冲区加速下载，实时显示进度。
     */
    private fun streamDownloadAndPlay(song: Song) {
        io.execute(Runnable {
            var tmpFile: File? = null
            try {
                var url = song.downloadUrl
                if (url == null || url.isEmpty()) {
                    url = getSongDownloadUrl(extractTid(song))
                    if (url != null) song.downloadUrl = url
                }
                if (url == null || url.isEmpty()) {
                    MiliLog.error(
                        "点歌编号=${song?.let(::extractTid)} 标题=${song?.title} 请求源=w.w345.my 状态=失败 详情=无法获取播放地址",
                    )
                    main.post(Runnable {
                        hideDownloadProgress()
                        toast(getString(R.string.ui_p_031))
                    })
                    return@Runnable
                }

                val finalFile = getLocalFile(song)
                finalFile.getParentFile().mkdirs()
                tmpFile = File(finalFile.getParent(), finalFile.getName() + ".tmp")

                val conn = URL(url).openConnection() as HttpURLConnection
                conn.setConnectTimeout(10000)
                conn.setReadTimeout(30000)
                conn.setRequestProperty("User-Agent", "ThunderSDK/4.1.3")
                conn.connect()

                val totalSize = conn.getContentLength().toLong()
                val `in`: InputStream = BufferedInputStream(conn.getInputStream(), 262144)
                val out = FileOutputStream(tmpFile)

                val buf = ByteArray(262144) // 256KB buffer for speed
                var downloaded: kotlin.Long = 0
                var lastPct = 0
                var lastTime = System.currentTimeMillis()
                var lastBytes: kotlin.Long = 0

                while (true) {
                    val read = `in`.read(buf)
                    if (read < 0) break
                    out.write(buf, 0, read)
                    downloaded += read.toLong()

                    // 每秒更新一次进度和速度
                    val now = System.currentTimeMillis()
                    if (now - lastTime > 1000) {
                        val pct = if (totalSize > 0) (downloaded * 100 / totalSize).toInt() else 0
                        val speed = downloaded - lastBytes
                        lastPct = pct
                        lastTime = now
                        lastBytes = downloaded
                        val p = pct
                        val speedStr = formatSize(speed) + "/s"
                        main.post(Runnable {
                            updateDownloadProgress(song, p)
                            if (textDownloadSpeed != null) textDownloadSpeed!!.setText(speedStr)
                        })
                    }
                }
                out.close()
                `in`.close()
                conn.disconnect()

                // 移到正式位置
                tmpFile.renameTo(finalFile)
                song.path = finalFile.getAbsolutePath()
                randomCandidates[stableId(song)] = song

                main.post(Runnable {
                    hideDownloadProgress()
                    if (currentSong != null && currentSong!!.equals(song)) {
                        playLocalFile(song)
                    }
                    toast(song.title + getString(R.string.ui_p_032))
                })
            } catch (e: Exception) {
                Log.e(TAG, "download error: " + e.message)
                if (tmpFile != null) tmpFile.delete()
                MiliLog.error("DL-ERR id=${song?.let(::extractTid)} title=${song?.title} ${e.message}")
                main.post(Runnable {
                    hideDownloadProgress()
                    toast(getString(R.string.ui_p_028) + e.message)
                })
            }
        })
    }

    /**
     * 下载歌曲并在完成后自动播放 (旧版，备用)。
     */
    private fun startDownloadAndPlay(song: Song) {
        downloadSong(song, object : DownloadCallback {
            override fun onDownloadStart(s: Song) {
                main.post(Runnable {
                    updateDownloadProgress(s, 0)
                })
            }

            override fun onDownloadProgress(s: Song, progress: Int) {
                main.post(Runnable { updateDownloadProgress(s, progress) })
            }

            override fun onDownloadReadyToPlay(s: Song) {}
            override fun onDownloadComplete(s: Song, localPath: String) {
                main.post { hideDownloadProgress() }
            }

            override fun onDownloadFailed(s: Song, error: String) {
                MiliLog.error("DL-FAIL id=${extractTid(s)} title=${s.title} $error")
                main.post(Runnable {
                    hideDownloadProgress()
                    toast(getString(R.string.ui_p_028) + error)
                })
            }
        })
    }

    private fun showDownloadProgressFor(song: Song) {
        if (downloadProgressContainer != null) downloadProgressContainer!!.setVisibility(View.VISIBLE)
        if (textDownloadStatus != null) textDownloadStatus!!.setText(getString(R.string.ui_p_033) + song.title)
        if (textDownloadSpeed != null) textDownloadSpeed!!.setText("")
        if (downloadProgressBar != null) downloadProgressBar!!.setProgress(0)
    }

    private fun updateDownloadProgress(song: Song?, progress: Int) {
        if (downloadProgressContainer != null) downloadProgressContainer!!.setVisibility(View.VISIBLE)
        if (textDownloadStatus != null) textDownloadStatus!!.setText(getString(R.string.ui_p_034) + (if (song != null) song.title else "") + " " + progress + "%")
        if (textDownloadSpeed != null) textDownloadSpeed!!.setText(progress.toString() + "%")
        if (downloadProgressBar != null) downloadProgressBar!!.setProgress(progress)
    }

    private fun hideDownloadProgress() {
        if (downloadProgressContainer != null) downloadProgressContainer!!.setVisibility(View.GONE)
    }

    private fun setupPlayerListeners(song: Song?, startPositionMs: Int = 0, generation: Long = playbackGeneration) {
        player!!.setOnPreparedListener(OnPreparedListener { mp: MediaPlayer? ->
            if (generation != playbackGeneration || currentSong?.equals(song) != true) return@OnPreparedListener
            Log.i(TAG, "onPrepared song=${song?.let(::stableId)} generation=$generation play=$playWhenPrepared")
            playbackPreparing = false
            currentMediaPlayer = mp
            applyPlaybackMode()
            // 无条件 seek 到目标位置：切歌/点歌时 startPositionMs=0，必须显式归零，
            // 不能依赖 IJK 在 stop→reset→prepareAsync 链里隐式把 currentPosition 清零
            // （个别机型会残留上一首的进度，导致切歌后画面/歌词从中间开始）。
            // 语言切换续播时 startPositionMs>0，则跳到保存的进度。
            player!!.seekTo(startPositionMs)
            prepareExternalVocal(song, startPositionMs)
            if (playWhenPrepared) {
                player!!.start()
                startVocalIfReady()
            }
            updateBottomBar(song, playWhenPrepared)
            if (startPositionMs <= 0) showSongIntro(song)
            persistRuntimeState()
        })
        player!!.setOnCompletionListener(OnCompletionListener { mp: MediaPlayer? ->
            if (generation != playbackGeneration || currentSong?.equals(song) != true) return@OnCompletionListener
            if (System.currentTimeMillis() < suppressCompletionUntil) return@OnCompletionListener
            // 模拟器/部分设备会连续触发多次 onCompletion, 加 800ms 去抖, 防止跳歌或同一首被重复处理。
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastAutoAdvanceAtMs < 800L) return@OnCompletionListener
            lastAutoAdvanceAtMs = nowMs
            main.post(Runnable { playNext(true) })
        })
        player!!.setOnErrorListener(MediaPlayer.OnErrorListener { mp: MediaPlayer?, what: Int, extra: Int ->
            if (generation != playbackGeneration || currentSong?.equals(song) != true) return@OnErrorListener true
            playbackPreparing = false
            Log.e(TAG, "MediaPlayer error what=" + what + " extra=" + extra)
            // 远程日志: 播放网络/解码错误实时上报。
            MiliLog.error(
                "PLAY-ERR id=${song?.let(::extractTid)} title=${song?.title} what=$what extra=$extra" +
                    (if (hasPlayableLocalFile(song)) " local" else " stream"),
            )
            if (songRetryCount < 2 && song != null && hasPlayableLocalFile(song)) {
                songRetryCount++
                val retry = songRetryCount
                main.postDelayed(Runnable {
                    toast(getString(R.string.ui_p_035) + retry + "/2")
                    playLocalFile(song)
                }, 700L * retry)
            } else {
                songRetryCount = 0
                main.post(Runnable { this.playNext() })
            }
            true
        })
    }

    private fun switchToLocalPlayback(song: Song?) {
        if (currentSong == null || !currentSong!!.equals(song)) return
        if (song == null || !hasPlayableLocalFile(song)) return
        var pos = 0
        try {
            pos = player!!.currentPosition
        } catch (ignored: Exception) {
        }
        player!!.stopPlayback()
        playLocalFile(song, pos)
    }

    /**
     * 更新底部控制栏显示
     */
    private fun updateBottomBar(song: Song?, playing: Boolean) {
        if (playing && playbackModeNotice?.text?.contains(getString(R.string.ui_a_0309)) == true) hidePlaybackNoticeNow()
        if (playerSongTitle != null) playerSongTitle!!.setText(if (song != null) song.title else getString(R.string.ui_a_0549))
        if (playerSongSinger != null) playerSongSinger!!.setText(if (song != null) (if (song.singer != null) song.singer else "") else "")
        if (textVocalMode != null) textVocalMode.setText(vocalActionText())
        if (textPlayPause != null) textPlayPause.setText(if (playing) getString(R.string.ui_a_0309) else getString(R.string.ui_a_0310))
        if (btnTopPause != null) btnTopPause!!.setText(if (playing) getString(R.string.ui_a_0309) else getString(R.string.ui_a_0310))
        if (btnTopVocal != null) btnTopVocal!!.setText(vocalActionText())
        setTopControlIcon(btnTopPause, if (playing) R.drawable.ott_ic_ctrl_pause else R.drawable.ott_ic_ctrl_play)
        setTopControlIcon(
            btnTopVocal,
            vocalActionIcon(),
        )
        fullScreenPauseText?.text = if (playing) getString(R.string.ui_a_0309) else getString(R.string.ui_a_0310)
        fullScreenPauseIcon?.setImageResource(if (playing) R.drawable.ott_ic_ctrl_pause else R.drawable.ott_ic_ctrl_play)
        fullScreenVocalText?.text = vocalActionText()
        fullScreenVocalIcon?.setImageResource(vocalActionIcon())
        playerController?.updatePlayState(playing)
        playerController?.setVocalMode(originalVocal)
        syncPlayerInfo()
        updateOrderBadge()
    }

    /**
     * 从 CDN 下载歌曲文件。
     * 使用 global_confs 表中的 cdn_path 作为基础 URL。
     * 
     * @param song 要下载的歌曲
     */
    private fun downloadFromCdn(song: Song) {
        if (song.filename == null || song.filename!!.isEmpty()) {
            toast(getString(R.string.ui_p_036))
            return
        }
        val cdnBase = "https://pub.mcdn.cherryonline.cn/"
        val url = cdnBase + "cloud-song/" + song.filename
        val task = DownloadTask(song)
        downloads.add(task)
        busy(true, getString(R.string.ui_p_037) + song.title)
        io.execute(Runnable {
            val target =
                File(MuseDatabase.VIDEO_ROOT + "/" + MuseDatabase.CLOUD_SONG_DIR, song.filename)
            try {
                File(target.getParent()).mkdirs()
                downloadToFile(url, target, task, 0, 100)
                task.state = KtvRaw.get(R.string.ui_a_0110)
                task.progress = 100
                song.path = target.getAbsolutePath()
                main.post(Runnable {
                    busy(false, getString(R.string.ui_a_0137))
                    if (currentTabIndex == 6) loadDownloadedList()
                    if (currentSong?.equals(song) == true && playbackPreparing) {
                        switchToLocalPlayback(song)
                    }
                })
            } catch (e: Exception) {
                task.state = KtvRaw.get(R.string.ui_a_0554) + e.message
                main.post(Runnable {
                    busy(false, getString(R.string.ui_a_0544) + e.message)
                    if (currentTabIndex == 6) loadDownloadedList()
                })
            }
        })
    }

    /**
     * 使用 SongOkDownloadManager 下载歌曲到本地。
     */
    private fun downloadSong(song: Song, extraCallback: DownloadCallback? = null, storageChecked: Boolean = false) {
        if (SongOkDownloadManager.isDownloading(song)) {
            // 已在下载(含 Activity 重建后的场景): 确保 downloads 里有登记,
            // 否则手机"下载中"页读不到这个任务。
            if (downloads.none { stableId(it.song) == stableId(song) }) {
                downloads.add(DownloadTask(song).apply { state = KtvRaw.get(R.string.ui_a_0084) })
                refreshSongAdapter()
            }
            if (extraCallback != null) extraCallback.onDownloadStart(song)
            return
        }
        if (!storageChecked && autoDeleteSongs && isBelowReservedStorage()) {
            io.execute {
                purgeColdDownloadedFiles()
                main.post { downloadSong(song, extraCallback, storageChecked = true) }
            }
            return
        }
        if (::stateDatabase.isInitialized) runCatching {
            stateIo.execute { stateDatabase.updateDownload(song, "queued", 0) }
        }
        // 发起下载时就把任务登记进 downloads, 与 ori 保持一致:
        // 手机点歌后 TV 自动下载, 手机"下载中"页读的就是这个集合。
        // 若只等 onDownloadStart 回调再 add, 当 SongOkDownloadManager 已判定该歌
        // isDownloading 而提前 return 时, 回调里的 add 不会发生, 列表会一直为空。
        downloads.removeAll { stableId(it.song) == stableId(song) }
        downloads.add(DownloadTask(song).apply { state = KtvRaw.get(R.string.ui_a_0014) })
        refreshSongAdapter()
        download(song, downloadUiCallback(song, extraCallback))
    }

    /** 下载 UI 回调：正常下载与 Activity 重建后重新挂接共用，保证新界面收到进度/完成/失败通知。 */
    private fun downloadUiCallback(song: Song, extraCallback: DownloadCallback?): DownloadCallback =
        object : DownloadCallback {
            override fun onDownloadStart(song: Song) {
                if (extraCallback != null) extraCallback.onDownloadStart(song)
                if (::stateDatabase.isInitialized) runCatching {
                    stateDatabase.updateDownload(song, "downloading", 0)
                }
                main.post(Runnable {
                    val task = downloads.firstOrNull { stableId(it.song) == stableId(song) }
                        ?: DownloadTask(song).also { downloads.add(it) }
                    task.state = KtvRaw.get(R.string.ui_a_0084)
                    task.progress = 0
                    refreshSongAdapter()
                    if (currentTabIndex == 6) loadDownloadedList()
                    if (currentSong?.equals(song) == true && playbackPreparing) updateDownloadProgress(song, 0)
                })
            }

            override fun onDownloadProgress(song: Song, progress: Int) {
                if (extraCallback != null) extraCallback.onDownloadProgress(song, progress)
                if (::stateDatabase.isInitialized) runCatching {
                    stateDatabase.updateDownload(song, "downloading", progress)
                }
                main.post(Runnable {
                    for (task in downloads) {
                        if (task.song === song || task.song.id != null && task.song.id == song.id) {
                            task.progress = progress
                            break
                        }
                    }
                    // 进度刷新只重绘行状态(内部走焦点锚定), 不能调用 loadDownloadedList():
                    // 后者会 showSubPageShell() 重建整个子页面并重置焦点, 高频进度回调下焦点会
                    // 被顶栏的中英文切换键抢走。与 ori 保持一致。
                    if (currentTabIndex == 6) renderSongList()
                    if (currentSong?.equals(song) == true && playbackPreparing) updateDownloadProgress(song, progress)
                })
            }

            override fun onDownloadReadyToPlay(song: Song) {
                if (extraCallback != null) extraCallback.onDownloadReadyToPlay(song)
                // 后台预下载不能抢占当前播放；当前点播在完整文件落盘后切换。
            }

            override fun onDownloadComplete(song: Song, localPath: String) {
                if (extraCallback != null) extraCallback.onDownloadComplete(song, localPath)
                if (::stateDatabase.isInitialized) runCatching {
                    stateDatabase.removeDownload(song)
                }
                main.post(Runnable {
                    downloads.removeAll { stableId(it.song) == stableId(song) }
                    song.path = localPath
                    // 立刻进公放候选缓存：不必等下次预热扫描，新下载的歌马上能被随机到。
                    randomCandidates[stableId(song)] = song
                    refreshSongAdapter()
                    val shouldAddToQueue = pendingQueueSongIds.remove(stableId(song))
                    Log.i(
                        TAG,
                        "downloadComplete song=${stableId(song)} pendingQueue=$shouldAddToQueue " +
                            "current=${currentSong?.let(::stableId)} preparing=$playbackPreparing path=$localPath",
                    )
                    if (shouldAddToQueue) addReadySongToQueue(song)
                    if (currentTabIndex == 6) loadDownloadedList()
                    if (currentSong?.equals(song) == true && playbackPreparing) hideDownloadProgress()
                    if (!shouldAddToQueue && currentSong?.equals(song) == true && playbackPreparing) {
                        switchToLocalPlayback(song)
                    }
                })
                enforceStorageReserve()
            }

            override fun onDownloadFailed(song: Song, error: String) {
                if (extraCallback != null) extraCallback.onDownloadFailed(song, error)
                if (::stateDatabase.isInitialized) runCatching {
                    stateDatabase.updateDownload(song, "failed", 0, error = error)
                }
                main.post(Runnable {
                    val wasPendingQueue = pendingQueueSongIds.remove(stableId(song))
                    if (currentSong != null && currentSong!!.equals(song)) playbackPreparing = false
                    for (task in downloads) {
                        if (task.song === song || task.song.id != null && task.song.id == song.id) {
                            task.state = KtvRaw.get(R.string.ui_a_0554) + error
                            break
                        }
                    }
                    refreshSongAdapter()
                    if (currentTabIndex == 6) loadDownloadedList()
                    if (currentSong?.equals(song) == true) hideDownloadProgress()
                    if (wasPendingQueue) toast(getString(R.string.ui_p_083, song.title))
                })
            }
        }

    /**
     * 检查指定歌曲是否正在下载中。
     * 
     * 
     * 使用 SongOkDownloadManager 检查真实的下载状态。
     * 
     * @param song 要检查的歌曲
     * @return true 表示正在下载
     */
    /** 从 Song 中提取云端歌曲 TID (filename 格式: "7678785.ts" / "7586669.ls" → "7678785" / "7586669")  */
    private fun extractTid(song: Song): String? {
        if (song.filename != null) {
            // 去掉 .ts 或 .ls 扩展名
            if (song.filename!!.endsWith(".ts")) return song.filename!!.substring(
                0,
                song.filename!!.length - 3
            )
            if (song.filename!!.endsWith(".ls")) return song.filename!!.substring(
                0,
                song.filename!!.length - 3
            )
            return song.filename
        }
        return song.id
    }

    private fun isDownloading(song: Song): Boolean {
        if (SongOkDownloadManager.isDownloading(song)) {
            return true
        }
        for (task in downloads) {
            val sameSong = task.song === song || stableId(task.song) == stableId(song)
            val active = task.state == "等待" || task.state == "等待下载" ||
                task.state == "下载中" || task.state == "正在取消"
            if (sameSong && active) return true
        }
        return false
    }

    private fun playNext(fromCompletion: Boolean = false) {
        if (advancingPlayback) return
        advancingPlayback = true
        try {
            advancePlayback(fromCompletion)
        } finally {
            advancingPlayback = false
        }
    }

    private fun advancePlayback(fromCompletion: Boolean) {
        // 单曲循环只影响自然播完；用户主动切歌始终进入下一首。
        if (fromCompletion && loopOne && player != null && currentSong != null) {
            playWhenPrepared = true
            player!!.seekTo(0)
            prepareExternalVocal(currentSong, 0)
            player!!.start()
            startVocalIfReady()
            return
        }
        // 队列为空(最后一首已播完/随机续播中未点歌)
        if (orderQueue!!.isEmpty()) {
            if (fromCompletion && autoNext) {
                // 公放清单里的这首播完了：计数 +1，然后取清单里的下一首（同一轮不重复）。
                currentSong?.takeIf { RandomPlayQueue.isCurrentTrack(it) }?.let { finished ->
                    val count = RandomPlayQueue.markFinished(finished)
                    MiliLog.info(
                        "RANDOM-DONE title=${finished.title} count=$count " +
                            "cycle=${RandomPlayQueue.cycle} remaining=${RandomPlayQueue.remaining}",
                    )
                }
                playRandomLocalSong()
                return
            }
            // 公放(随机)歌曲播放中按「切歌」：不该停下来，接着播公放清单里的下一首。
            // 注意这里不调用 markFinished —— 用户是跳过而不是播完，不该 +1；
            // 但它已在 markStarted 时计入本轮已播，所以本轮不会再重复。
            if (!fromCompletion && currentSong?.let { RandomPlayQueue.isCurrentTrack(it) } == true) {
                playRandomLocalSong(skipped = true)
                return
            }
            // 其它情况(没有公放曲目在播 / 用户主动停止):取消挂起的随机续播,进入空闲态。
            // 一并停掉播放器并清空播放器状态：只要出现"音频在跑但 currentSong 已空"，
            // 那首歌播完时 onCompletion 就会因 currentSong 不匹配而直接 return，
            // 之后按切歌/播放都没有反应（僵尸态）。
            if (player != null) runCatching { player!!.stopPlayback() }
            currentMediaPlayer = null
            randomLocalRequestSerial++
            playbackPreparing = false
            playWhenPrepared = false
            releaseVocalPlayer()
            currentSong = null
            clearLyrics()
            updateBottomBar(null, false)
            if (status != null) status!!.setText(getString(R.string.ui_a_0556))
            return
        }
        // 移除当前歌曲进已唱列表。按稳定 ID 匹配,避免对象实例不同导致移不掉而重复播放同一首。
        val current = currentSong
        if (current != null) {
            sangHistory.add(0, current)
            recordScore(current)
            removeFromQueueByStableId(current)
        }
        saveState()
        renderQueue()
        if ((!fromCompletion || autoNext) && !orderQueue.isEmpty()) {
            play(orderQueue!![0])
        } else if (fromCompletion && autoNext && orderQueue.isEmpty()) {
            // 点歌清单播放完毕 → 自动续播本地已下载歌曲
            playRandomLocalSong()
        } else if (!fromCompletion) {
            // 点歌清单**最后一首**还在播时按「切歌」→ 直接进入公放（随机本地歌曲）。
            // ⚠️ 旧逻辑在这里只把 currentSong 置空、**没有停播放器**：那首歌会继续播完，
            // 而 onCompletion 的第一行是 `currentSong?.equals(song) != true → return`，
            // 于是再也不会推进 —— 形成"音频已结束 / currentSong 为空 / playWhenPrepared=false"
            // 的僵尸态，之后按「切歌」「播放」都毫无反应（用户报的卡死）。
            // 公放清单与点歌清单互不影响：随机歌曲不入 orderQueue，点歌时又会立即取消随机请求。
            MiliLog.info("点歌清单已空，切歌转入公放（随机本地歌曲）")
            playRandomLocalSong(skipped = true)
        } else {
            // 手动切歌模式（autoNext=false）下自然播完：保持原逻辑，停在空闲态等待点歌。
            // 这里把播放器一并停掉并清空状态，避免出现上面那种"音频在跑、状态已清"的僵尸态。
            if (player != null) runCatching { player!!.stopPlayback() }
            randomLocalRequestSerial++
            playbackPreparing = false
            playWhenPrepared = false
            currentMediaPlayer = null
            releaseVocalPlayer()
            currentSong = null
            clearLyrics()
            updateBottomBar(null, false)
            if (status != null) status!!.setText(getString(R.string.ui_a_0557) + (if (orderQueue.isEmpty()) "" else getString(R.string.ui_a_0558)))
        }
    }

    /** 按稳定 ID 从已点队列移除歌曲(等价于移除“当前播放”，但不依赖对象实例相等)。 */
    private fun removeFromQueueByStableId(song: Song) {
        val id = stableId(song)
        val index = orderQueue!!.indexOfFirst { stableId(it) == id }
        if (index >= 0) orderQueue!!.removeAt(index)
    }

    /**
     * 从本机所有有效歌曲中随机续播(启动自动播放 / 点歌列表播放完毕 / 公放中切歌均走此逻辑)。
     * 随机歌曲不写入已点列表；用户此时点歌会立即取消本次随机请求并优先播放已点歌曲。
     *
     * @param startup 是否开机自动随机播放
     * @param skipped 是否是用户在公放歌曲上按了「切歌」(仅影响提示文案)
     */
    private fun playRandomLocalSong(startup: Boolean = false, skipped: Boolean = false) {
        val previousSongId = currentSong?.let(::stableId) ?: lastRandomLocalSongId
        val requestSerial = ++randomLocalRequestSerial
        playbackPreparing = true
        playWhenPrepared = true
        // 这里**不立刻停播放器**：本地曲库扫描（含文件校验）要花一点时间，
        // 让当前这首歌继续放到下一首真正开始时再停，避免"切歌后一段静音"。
        // 僵尸态的防范放在"确实没歌可播"的分支里（见下面 selected == null 的处理）。
        releaseVocalPlayer()
        currentSong = null
        clearLyrics()
        updateBottomBar(null, false)
        status?.text = when {
            startup -> getString(R.string.ui_p_random_start)
            skipped -> getString(R.string.ui_p_random_skip)
            else -> getString(R.string.ui_a_0559)
        }
        persistRuntimeState()

        // ⚠️ 两条硬约束：
        // 1) 必须用 randomPlayExecutor（专用线程），否则会被几十秒的下载任务挤在后面排队；
        // 2) 必须优先读预热缓存，不能在切歌时现扫目录（老盒子上"逐文件校验 + 遍历 /storage/ 各挂载点"
        //    会让切歌看起来卡死）。
        randomPlayExecutor.execute {
            val localSongs = LinkedHashMap<String, Song>()
            randomCandidates.forEach { (id, song) -> localSongs[id] = song }
            if (localSongs.isEmpty()) {
                // 缓存还没预热好（例如刚开机就切歌）：做一次**安全的**库存查询兜底。
                // 刻意不调用 library.scanLocal() —— 它会遍历 /storage/ 各挂载点，老 ROM 的失效挂载点
                // 可能让 File.listFiles() 永久阻塞；外部目录交给带超时的预热流程去补。
                runCatching {
                    localSongInventory().forEach { (id, song) -> localSongs[id] = song }
                }.onFailure { Log.w(TAG, "random candidates fallback failed", it) }
                primeRandomCandidates()
            }
            val allLocalSongs = localSongs.values.toList()
            main.post {
                if (requestSerial != randomLocalRequestSerial || orderQueue!!.isNotEmpty() || currentSong != null) {
                    return@post
                }
                // 伪随机公放清单：按打乱后的顺序取下一首（一轮内不重复，自动吸纳新增歌曲）。
                val selected = RandomPlayQueue.next(allLocalSongs, avoidId = previousSongId)
                if (selected != null) {
                    lastRandomLocalSongId = stableId(selected)
                    RandomPlayQueue.markStarted(selected)
                    status?.text = getString(R.string.ui_p_084, selected.title)
                    MiliLog.info(
                        "RANDOM-PLAY title=${selected.title} cycle=${RandomPlayQueue.cycle} " +
                            "remaining=${RandomPlayQueue.remaining} played=${RandomPlayQueue.playedInCycle}",
                    )
                    play(selected)
                } else {
                    // ⚠️ 本地确实没有可播歌曲：必须**停掉播放器**再进入空闲态。
                    // 否则会出现"歌曲还在放、currentSong 却已置空"的僵尸态 —— 那首歌播完时
                    // onCompletion 因 `currentSong?.equals(song) != true` 直接 return，
                    // 之后按切歌/播放都没反应（本轮用户报的卡死正是这一类状态）。
                    if (player != null) runCatching { player!!.stopPlayback() }
                    currentMediaPlayer = null
                    playbackPreparing = false
                    playWhenPrepared = false
                    updateBottomBar(null, false)
                    status?.text = getString(R.string.ui_a_0561)
                    persistRuntimeState()
                }
            }
        }
    }

    private fun togglePlay() {
        if (player == null) return
        val targetPlaying = !playWhenPrepared
        playWhenPrepared = targetPlaying
        if (playbackPreparing) {
            updateBottomBar(currentSong, targetPlaying)
            if (targetPlaying) hidePlaybackNoticeNow() else showPlaybackModeNotice(R.string.ui_a_0628, true)
            scheduleAutoFullscreen()
            persistRuntimeState()
            return
        }
        if (!targetPlaying) {
            player!!.pause()
            pauseVocalIfReady()
            if (textPlayPause != null) textPlayPause.setText(getString(R.string.ui_a_0310))
            showPlaybackModeNotice(R.string.ui_a_0628, true)
        } else {
            player!!.start()
            startVocalIfReady()
            if (textPlayPause != null) textPlayPause.setText(getString(R.string.ui_a_0309))
            hidePlaybackNoticeNow()
        }
        updateBottomBar(currentSong, targetPlaying)
        scheduleAutoFullscreen()
        persistRuntimeState()
    }

    private fun replay() {
        if (player == null) return
        playWhenPrepared = true
        suppressCompletionUntil = System.currentTimeMillis() + 1500L
        player!!.seekTo(0)
        updateLyricView()
        prepareExternalVocal(currentSong, 0)
        player!!.start()
        startVocalIfReady()
        hidePlaybackNoticeNow()
        updateBottomBar(currentSong, true)
        showSongIntro(currentSong)
        persistRuntimeState()
    }

    /**
     * 应用原伴唱/声道与输出音量。
     *
     * 音量语义：`musicVolume`(0-100) 是 **App 内部输出音量**，按线性系数施加到播放器
     * (`KtvVideoView.setPlaybackVolume` / `selectAudioChannel`) 与副音轨播放器上，
     * **绝不修改系统音量**（不调用 `AudioManager.setStreamVolume`）。
     * 播放界面-调音、声音设置-歌曲最大音量、远程控制-音乐音量三处都收敛到这里，
     * 保证三条入口行为一致且都不影响系统音量。
     */
    private fun applyPlaybackMode() {
        val videoPlayer = player ?: return
        videoPlayer.setTone(tone)
        val external = prepareExternalVocal(
            currentSong,
            player?.currentPosition ?: 0
        )
        val embedded = !external && KtvRaw.get(R.string.ui_a_0086) == vocalChannelMode &&
            prepareEmbeddedDualTrackVocal(currentSong, player?.currentPosition ?: 0)
        var trackSelected = false
        var channelApplied = false
        if (!external && !embedded && KtvRaw.get(R.string.ui_a_0086) == vocalChannelMode) {
            trackSelected = applySelectedAudioTrack()
            if (!trackSelected && currentSong != null && currentSong!!.accomp > 0) {
                channelApplied = applyChannelByAccomp()
            }
        }
        val alternateTrackActive = embedded && originalVocal != embeddedMainTrackOriginal
        val volume = if (external || alternateTrackActive || KtvRaw.get(R.string.ui_a_0262) == singMode) {
            0f
        } else {
            max(0f, min(1f, musicVolume / 100f))
        }
        try {
            when {
                external || KtvRaw.get(R.string.ui_a_0262) == singMode -> videoPlayer.setPlaybackVolume(0f, 0f)
                embedded -> videoPlayer.setPlaybackVolume(volume, volume)
                trackSelected -> videoPlayer.selectAudioChannel(IjkMediaPlayer.AUDIO_CHANNEL_STEREO, volume)
                "自动" != vocalChannelMode -> {
                    videoPlayer.selectAudioChannel(selectedChannelForMode(), volume)
                    status?.text = getString(R.string.ui_p_085, vocalChannelMode, if (originalVocal) getString(R.string.ui_a_0305) else getString(R.string.ui_a_0306))
                }
                channelApplied -> Unit
                else -> videoPlayer.selectAudioChannel(IjkMediaPlayer.AUDIO_CHANNEL_STEREO, volume)
            }
        } catch (ignored: Exception) {
        }
        applyVocalVolume()
        if (embedded && vocalPlayerPrepared) {
            val mainPosition = videoPlayer.currentPosition
            val alternatePosition = embeddedVocalEngine?.currentPosition ?: mainPosition
            Log.i(
                TAG,
                "Embedded seamless switch original=$originalVocal main=$mainPosition " +
                    "alternate=$alternatePosition drift=${alternatePosition - mainPosition}",
            )
        }
    }

    /**
     * 基于 songs.accomp 字段自动切换原唱/伴奏声道。
     * 
     * 
     * accomp=1:左声道=伴奏,右声道=原唱
     * accomp=2:左声道=原唱,右声道=伴奏
     * 
     * 
     * 当 originalVocal=true 时,播放原唱声道,静音伴奏声道;
     * 当 originalVocal=false 时,播放伴奏声道,静音原唱声道。
     * 
     * @return true 表示成功应用声道切换
     */
    private fun applyChannelByAccomp(): Boolean {
        val videoPlayer = player ?: return false
        if (currentSong == null || currentSong!!.accomp <= 0) return false
        try {
            val volume = max(0f, min(1f, musicVolume / 100f))
            val channel = if (currentSong!!.accomp == 1) {
                if (originalVocal) IjkMediaPlayer.AUDIO_CHANNEL_RIGHT else IjkMediaPlayer.AUDIO_CHANNEL_LEFT
            } else {
                if (originalVocal) IjkMediaPlayer.AUDIO_CHANNEL_LEFT else IjkMediaPlayer.AUDIO_CHANNEL_RIGHT
            }
            videoPlayer.selectAudioChannel(channel, volume)
            status!!.setText((if (originalVocal) getString(R.string.ui_a_0305) else getString(R.string.ui_a_0306)) + getString(R.string.ui_a_0562) + currentSong!!.accomp + ")")
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun applySelectedAudioTrack(): Boolean {
        val selected = player?.selectAudioTrack(originalVocal) == true
        if (selected) status?.text = if (originalVocal) getString(R.string.ui_a_0563) else getString(R.string.ui_a_0564)
        return selected
    }

    private fun selectedChannelForMode(): Int = when (vocalChannelMode) {
        "左伴右原" -> if (originalVocal) IjkMediaPlayer.AUDIO_CHANNEL_RIGHT else IjkMediaPlayer.AUDIO_CHANNEL_LEFT
        "右伴左原" -> if (originalVocal) IjkMediaPlayer.AUDIO_CHANNEL_LEFT else IjkMediaPlayer.AUDIO_CHANNEL_RIGHT
        else -> IjkMediaPlayer.AUDIO_CHANNEL_STEREO
    }

    private fun channelVolumes(volume: Float): FloatArray {
        if (KtvRaw.get(R.string.ui_a_0273) == vocalChannelMode) {
            return if (originalVocal) floatArrayOf(0f, volume) else floatArrayOf(volume, 0f)
        }
        if (KtvRaw.get(R.string.ui_a_0274) == vocalChannelMode) {
            return if (originalVocal) floatArrayOf(volume, 0f) else floatArrayOf(0f, volume)
        }
        return floatArrayOf(volume, volume)
    }

    private fun prepareExternalVocal(song: Song?, positionMs: Int): Boolean {
        val path = selectedVocalPath(song)
        if (!hasUrl(path)) {
            if (embeddedDualTrackMode && embeddedVocalEngine != null) {
                pendingVocalPosition = adjustedAudioPosition(positionMs)
                if (vocalPlayerPrepared) runCatching {
                    val drift = kotlin.math.abs(embeddedVocalEngine!!.currentPosition - pendingVocalPosition)
                    if (drift > 300 && originalVocal == embeddedMainTrackOriginal) {
                        embeddedVocalEngine!!.seekTo(pendingVocalPosition)
                    }
                }
                return false
            }
            releaseVocalPlayer()
            return false
        }
        if (path == currentVocalPath && vocalPlayer != null) {
            pendingVocalPosition = adjustedAudioPosition(positionMs)
            if (vocalPlayerPrepared) {
                runCatching {
                    vocalPlayer!!.seekTo(pendingVocalPosition)
                    applyVocalVolume()
                    if (playWhenPrepared) startVocalIfReady() else pauseVocalIfReady()
                }
            }
            return true
        }
        releaseVocalPlayer()
        try {
            embeddedDualTrackMode = false
            val candidate = MediaPlayer()
            vocalPlayer = candidate
            vocalPlayerPrepared = false
            pendingVocalPosition = adjustedAudioPosition(positionMs)
            currentVocalPath = path
            candidate.setDataSource(path)
            candidate.setAudioStreamType(AudioManager.STREAM_MUSIC)
            candidate.setOnCompletionListener(OnCompletionListener { _: MediaPlayer? -> })
            candidate.setOnPreparedListener { preparedPlayer ->
                if (vocalPlayer !== preparedPlayer || currentVocalPath != path) {
                    runCatching { preparedPlayer.release() }
                    return@setOnPreparedListener
                }
                vocalPlayerPrepared = true
                if (pendingVocalPosition > 0) runCatching { preparedPlayer.seekTo(pendingVocalPosition) }
                applyVocalVolume()
                if (playWhenPrepared) startVocalIfReady() else pauseVocalIfReady()
            }
            candidate.setOnErrorListener { failedPlayer, _, _ ->
                if (vocalPlayer === failedPlayer) releaseVocalPlayer()
                true
            }
            candidate.prepareAsync()
            return true
        } catch (e: Exception) {
            releaseVocalPlayer()
            toast(getString(R.string.ui_a_0566) + e.message)
            return false
        }
    }

    /**
     * 双音轨 TS 使用一个常驻的静音副播放器预解码另一条音轨。切换时只交叉调整两个
     * 播放器的音量，不再对主 IJKPlayer 调用 selectTrack，避免 AudioTrack 被销毁重建。
     */
    private fun prepareEmbeddedDualTrackVocal(song: Song?, positionMs: Int): Boolean {
        val videoPlayer = player ?: return false
        val path = song?.path?.takeIf { it.isNotBlank() } ?: return false
        if (videoPlayer.audioTrackCount() < 2 || !File(path).isFile) {
            if (embeddedDualTrackMode) releaseVocalPlayer()
            return false
        }
        if (embeddedDualTrackMode && currentVocalPath == path && embeddedVocalEngine != null) {
            if (!vocalPlayerPrepared && embeddedMainTrackOriginal != originalVocal) {
                releaseVocalPlayer()
            } else {
                applyVocalVolume()
                return vocalPlayerPrepared
            }
        }

        releaseVocalPlayer()
        return try {
            embeddedDualTrackMode = true
            embeddedMainTrackOriginal = originalVocal
            pendingVocalPosition = adjustedAudioPosition(positionMs)
            currentVocalPath = path
            val candidate = KtvPlaybackEngine(this)
            embeddedVocalEngine = candidate
            vocalPlayerPrepared = false
            candidate.setVolume(0f, 0f)
            candidate.setOnCompletionListener(null)
            candidate.setOnPreparedListener {
                if (embeddedVocalEngine !== candidate || currentVocalPath != path || !embeddedDualTrackMode) {
                    candidate.release()
                    return@setOnPreparedListener
                }
                if (candidate.audioTrackCount() < 2 ||
                    !candidate.selectAudioTrack(!embeddedMainTrackOriginal)
                ) {
                    Log.w(
                        TAG,
                        "Embedded seamless vocal unavailable: tracks=${candidate.audioTrackCount()} path=$path",
                    )
                    releaseVocalPlayer()
                    return@setOnPreparedListener
                }
                vocalPlayerPrepared = true
                // The secondary decoder starts roughly one audio buffer behind the visible player.
                // Give it a small head start once, while it is still muted, so later volume-only
                // switches land on the same presentation timestamp.
                val synchronizedPosition = adjustedAudioPosition(
                    (player?.currentPosition ?: pendingVocalPosition) + 120,
                )
                if (synchronizedPosition > 0) candidate.seekTo(synchronizedPosition)
                applyVocalVolume()
                if (playWhenPrepared) startVocalIfReady() else pauseVocalIfReady()
                Log.i(
                    TAG,
                    "Embedded seamless vocal ready tracks=${candidate.audioTrackCount()} " +
                        "mainOriginal=$embeddedMainTrackOriginal path=$path",
                )
            }
            candidate.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Embedded seamless vocal failed what=$what extra=$extra path=$path")
                if (embeddedVocalEngine === candidate) releaseVocalPlayer()
                true
            }
            candidate.setVideoUri(Uri.fromFile(File(path)))
            false
        } catch (e: Exception) {
            Log.w(TAG, "Embedded seamless vocal prepare failed path=$path", e)
            releaseVocalPlayer()
            false
        }
    }

    private fun selectedVocalPath(song: Song?): String {
        if (song == null) return ""
        val direct = if (originalVocal) song.originalPath else song.accompanyPath
        val databasePath = if (originalVocal) song.pOrigin else song.pAccomp
        return direct?.takeIf { it.isNotBlank() && it !in setOf("0", "1", "2") }
            ?: databasePath?.takeIf { it.isNotBlank() && it !in setOf("0", "1", "2") }
            ?: ""
    }

    private fun applyVocalVolume() {
        if (vocalPlayer == null && embeddedVocalEngine == null) return
        val alternateTrackActive = embeddedDualTrackMode && originalVocal != embeddedMainTrackOriginal
        val volume = if (KtvRaw.get(R.string.ui_a_0262) == singMode || embeddedDualTrackMode && !alternateTrackActive) {
            0f
        } else {
            max(0f, min(1f, musicVolume / 100f))
        }
        try {
            if (embeddedDualTrackMode) {
                embeddedVocalEngine?.setVolume(volume, volume)
            } else {
                vocalPlayer?.setVolume(volume, volume)
            }
        } catch (ignored: Exception) {
        }
    }

    private fun startVocalIfReady() {
        if (!vocalPlayerPrepared) return
        try {
            if (embeddedDualTrackMode) {
                if (embeddedVocalEngine?.isPlaying != true) embeddedVocalEngine?.start()
            } else if (vocalPlayer?.isPlaying != true) {
                vocalPlayer?.start()
            }
        } catch (ignored: Exception) {
        }
    }

    private fun pauseVocalIfReady() {
        if (!vocalPlayerPrepared) return
        try {
            if (embeddedDualTrackMode) {
                if (embeddedVocalEngine?.isPlaying == true) embeddedVocalEngine?.pause()
            } else if (vocalPlayer?.isPlaying == true) {
                vocalPlayer?.pause()
            }
        } catch (ignored: Exception) {
        }
    }

    private fun releaseVocalPlayer() {
        embeddedVocalEngine?.release()
        embeddedVocalEngine = null
        if (vocalPlayer != null) {
            try {
                vocalPlayer!!.release()
            } catch (ignored: Exception) {
            }
        }
        vocalPlayer = null
        vocalPlayerPrepared = false
        embeddedDualTrackMode = false
        embeddedMainTrackOriginal = true
        pendingVocalPosition = 0
        currentVocalPath = ""
    }

    private fun adjustedAudioPosition(videoPositionMs: Int): Int {
        return max(0, videoPositionMs - audioDelayMs)
    }

    private fun loadLyrics(song: Song?) {
        lyricLines.clear()
        if (song == null || !hasUrl(song.lyricPath)) {
            showLyricText(getString(R.string.ui_no_lyrics), "")
            return
        }
        val lyricPath = song.lyricPath.orEmpty()
        val file = File(lyricPath)
        if (!file.exists() || !file.getName().lowercase().endsWith(".lrc")) {
            showLyricText(getString(R.string.ui_a_0567) + file.getName(), getString(R.string.ui_a_0568))
            return
        }
        try {
            BufferedReader(InputStreamReader(FileInputStream(file), "UTF-8")).use { reader ->
                var line: String?
                while ((reader.readLine().also { line = it }) != null) parseLyricLine(line)
                Collections.sort(lyricLines, compareBy { it!!.timeMs })
                updateLyricView()
            }
        } catch (e: Exception) {
            showLyricText(getString(R.string.ui_a_0569), e.message)
        }
    }

    private fun parseLyricLine(line: String?) {
        val value = if (line == null) "" else line.trim { it <= ' ' }
        if (value.isEmpty()) return
        val lastBracket = value.lastIndexOf(']')
        if (!value.startsWith("[") || lastBracket < 0 || lastBracket >= value.length - 1) return
        val text = value.substring(lastBracket + 1).trim { it <= ' ' }
        if (text.isEmpty()) return
        var index = 0
        while (index < lastBracket) {
            val open = value.indexOf('[', index)
            val close = value.indexOf(']', open + 1)
            if (open < 0 || close < 0 || close > lastBracket) break
            val time = parseLyricTime(value.substring(open + 1, close))
            if (time >= 0) lyricLines.add(LyricLine(time, text))
            index = close + 1
        }
    }

    private fun parseLyricTime(value: String): Int {
        try {
            val parts = value.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (parts.size != 2) return -1
            val minutes = parts[0].toInt()
            val sec = parts[1].split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            val seconds = sec[0].toInt()
            var millis = 0
            if (sec.size > 1) {
                val fraction = (sec[1] + "00").substring(0, 2)
                millis = fraction.toInt() * 10
            }
            return minutes * 60000 + seconds * 1000 + millis
        } catch (e: Exception) {
            return -1
        }
    }

    private fun updateLyricView() {
        if (lyricCurrent == null || lyricNext == null || lyricLines.isEmpty() || player == null) return
        val pos = player!!.currentPosition
        var current = 0
        for (i in lyricLines.indices) {
            if (lyricLines.get(i)!!.timeMs <= pos) current = i
            else break
        }
        val next = if (current + 1 < lyricLines.size) lyricLines.get(current + 1)!!.text else ""
        showLyricText(lyricLines.get(current)!!.text, next)
    }

    private fun clearLyrics() {
        lyricLines.clear()
        showLyricText(getString(R.string.ui_no_lyrics), "")
    }

    private fun showLyricText(current: String?, next: String?) {
        if (lyricCurrent != null) lyricCurrent!!.setText(if (current == null) "" else current)
        if (lyricNext != null) lyricNext!!.setText(if (next == null) "" else next)
    }

    private fun moveSelectedQueueToTop() {
        if (queueList == null || orderQueue!!.isEmpty()) return
        var pos = queueList!!.getCheckedItemPosition()
        if (pos < 0) pos = queueList!!.getSelectedItemPosition()
        if (pos > 0 && pos < orderQueue.size) {
            val song = orderQueue.removeAt(pos)
            orderQueue.add(0, song)
            saveState()
            renderQueue()
        }
    }

    private fun removeSelectedQueue() {
        if (queueList == null || orderQueue!!.isEmpty()) return
        var pos = queueList!!.getCheckedItemPosition()
        if (pos < 0) pos = queueList!!.getSelectedItemPosition()
        if (pos >= 0 && pos < orderQueue.size) {
            orderQueue.removeAt(pos)
            saveState()
            renderQueue()
        }
    }

    private fun showSongActions(song: Song) {
        val hasLocalFile = hasValidLocalSongFile(song)
        val isFavorite = store.isFavorite(song)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(8), dp(28), dp(22))
        }
        val title = TextView(this).apply {
            text = song.title.orEmpty()
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(20))
        }
        panel.addView(title, LinearLayout.LayoutParams(-1, -2))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        lateinit var dialog: AlertDialog
        fun action(iconRes: Int, labelText: String, iconTint: Int? = null, block: () -> Unit): View = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setBackgroundResource(R.drawable.bg_song_action_focus)
            addView(ImageView(this@MainActivity).apply {
                setImageResource(iconRes)
                // imageTintList 属性是 API 21 引入的, 用 ImageViewCompat 兼容到 API 14。
                iconTint?.let { ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(it)) }
                setPadding(dp(18), dp(18), dp(18), dp(18))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb(92, 92, 97))
                }
            }, LinearLayout.LayoutParams(dp(76), dp(76)))
            addView(TextView(this@MainActivity).apply {
                text = labelText; textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                setPadding(0, dp(8), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
            setOnClickListener { dialog.dismiss(); block() }
        }
        actions.addView(action(R.drawable.ott_ic_top, KtvRaw.get(R.string.ui_a_0231)) {
            if (!orderQueue!!.contains(song)) addToQueue(song)
            moveQueuedSongToNext(song)
            toast(getString(R.string.ui_p_086, song.title))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(action(
            if (isFavorite) R.drawable.ott_ic_favorite_cancel else R.drawable.ott_ic_favorite,
            if (isFavorite) KtvRaw.get(R.string.ui_a_0571) else getString(R.string.ui_a_0572),
            if (isFavorite) Color.rgb(233, 30, 99) else null,
        ) {
            val added = store.toggleFavorite(song)
            saveState()
            toast(if (added) getString(R.string.ui_a_0573) else getString(R.string.ui_a_0574))
            if (browseMode == "favorites") {
                visibleSongs.removeAll { stableId(it) == stableId(song) }
                renderSongList()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (hasLocalFile) {
            actions.addView(action(R.drawable.ott_ic_delete, getString(R.string.ui_p_087)) {
                confirmDeleteLocalFile(song)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        panel.addView(actions, LinearLayout.LayoutParams(-1, -2))
        panel.addView(TextView(this).apply {
            text = song.singer.orEmpty()
            textSize = 20f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
            setPadding(dp(24), 0, dp(24), 0)
            setBackgroundResource(R.drawable.ott_bg_item)
            isFocusable = false
            isClickable = false
        }, LinearLayout.LayoutParams(-1, dp(70)).apply { topMargin = dp(22) })
        dialog = AlertDialog.Builder(this).setView(panel).create()
        dialog.showForTv(afterShow = { actions.getChildAt(0)?.requestFocus() })
    }

    private fun hasValidLocalSongFile(song: Song): Boolean {
        val direct = song.path?.let(::File)
        if (direct != null && SongFileValidator.inspect(
                direct,
                SongFileValidator.requiresTransportStream(direct),
            ).valid
        ) return true
        return isDownloaded(song)
    }

    private fun confirmDeleteLocalFile(song: Song) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_a_0576))
            .setMessage(getString(R.string.ui_p_088, song.title))
            .setNegativeButton(getString(R.string.ui_cancel), null)
            .setPositiveButton(getString(R.string.ui_ok)) { _, _ -> deleteDownloadedSong(song) }
            .create().showForTv()
    }

    private fun showSongInfo(song: Song) {
        val message = (KtvRaw.get(R.string.ui_a_0586) + song.title
                + KtvRaw.get(R.string.ui_a_0587) + song.singer
                + KtvRaw.get(R.string.ui_a_0588) + song.category
                + KtvRaw.get(R.string.ui_a_0589) + song.language
                + KtvRaw.get(R.string.ui_a_0590) + song.pinyin
                + KtvRaw.get(R.string.ui_a_0591) + resourceState(song.originalUrl, song.originalPath)
                + KtvRaw.get(R.string.ui_a_0592) + resourceState(song.accompanyUrl, song.accompanyPath)
                + KtvRaw.get(R.string.ui_a_0593) + resourceState(song.lyricUrl, song.lyricPath)
                + KtvRaw.get(R.string.ui_a_0594) + (if (song.remote) KtvRaw.get(R.string.ui_a_0595) else song.path))
        AlertDialog.Builder(this).setTitle(getString(R.string.ui_a_0596)).setMessage(message)
            .setPositiveButton(getString(R.string.ui_ok), null).create().showForTv(DialogInterface.BUTTON_POSITIVE)
    }

    private fun resourceState(remoteUrl: String?, localPath: String?): String {
        if (hasUrl(localPath)) return KtvRaw.get(R.string.ui_a_0597)
        if (hasUrl(remoteUrl)) return KtvRaw.get(R.string.ui_a_0598)
        return KtvRaw.get(R.string.ui_a_0329)
    }

    private fun confirmRemoveSong(song: Song) {
        val title = if (song.remote) getString(R.string.ui_a_0599) else getString(R.string.ui_a_0576)
        val message = if (song.remote)
            KtvRaw.get(R.string.ui_a_0600)
        else
            KtvRaw.get(R.string.ui_a_0601)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message + "\n\n" + song.displayTitle())
            .setPositiveButton(
                getString(R.string.ui_ok),
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int -> removeSong(song) })
            .setNegativeButton(getString(R.string.ui_cancel), null)
            .create().showForTv()
    }

    private fun removeSong(song: Song) {
        val id = stableId(song)
        if (!song.remote && song.path != null && !song.path!!.isEmpty()) {
            val file = File(song.path.orEmpty())
            if (file.exists() && !file.delete()) {
                toast(getString(R.string.ui_a_0602))
                return
            }
        }
        store.hiddenSongIds.add(id)
        store.favoriteIds.remove(id)
        for (ids in store.playlistSongs.values) ids.remove(id)
        removeSongFromList(orderQueue!!, id)
        removeSongFromList(sangHistory, id)
        saveState()
        refreshLibrary(Runnable {
            toast(getString(R.string.ui_a_0603) + song.title)
            if (KtvRaw.get(R.string.ui_a_0572) == currentPage) showFavorites()
            else if (KtvRaw.get(R.string.ui_a_0229) == currentPage) showQueue()
            else showSongs(KtvRaw.get(R.string.ui_a_0069))
        })
    }

    private fun removeSongFromList(songs: MutableList<Song>, id: String?) {
        for (i in songs.indices.reversed()) {
            if (stableId(songs.get(i)) == id) songs.removeAt(i)
        }
    }

    private fun showCreatePlaylistDialog() {
        val input = EditText(this)
        input.setSingleLine(true)
        input.setHint(getString(R.string.ui_a_0604))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_a_0605))
            .setView(input)
            .setPositiveButton(
                getString(R.string.ui_a_0606),
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    val name = input.getText().toString().trim { it <= ' ' }
                    if (!name.isEmpty() && !store.playlists.contains(name)) {
                        store.playlists.add(name)
                        saveState()
                    }
                    showPlaylists()
                })
            .setNegativeButton(getString(R.string.ui_cancel), null)
            .create().showForTv()
    }

    private fun fetchCatalog() {
        val url: String = prefs()!!.getString(KEY_CATALOG_URL, "")!!
        if (url.trim { it <= ' ' }.isEmpty()) {
            showCatalogDialog()
            return
        }
        busy(true, getString(R.string.ui_a_0607))
        io.execute(Runnable {
            try {
                val array = CatalogClient().fetch(url)
                library.saveRemote(array)
                library.scanLocal()
                main.post(Runnable {
                    busy(false, getString(R.string.ui_a_0608) + array.length() + getString(R.string.ui_p_064))
                    if (currentTabIndex == 6) loadDownloadedList()
                    else showSongs(KtvRaw.get(R.string.ui_a_0199))
                })
            } catch (e: Exception) {
                main.post(Runnable { busy(false, getString(R.string.ui_a_0609) + e.message) })
            }
        })
    }

    private fun refreshLibrary(after: Runnable?) {
        // 曲库变化后远程搜索结果缓存立即失效，避免还搜到已隐藏/已删除的歌。
        remoteSearchCache.clear()
        busy(true, getString(R.string.ui_a_0611))
        io.execute(Runnable {
            val locals = library.scanLocal().size
            library.loadCachedRemote()
            main.post(Runnable {
                busy(
                    false,
                    getString(R.string.ui_p_lib_updated, locals, library.allSongs().size)
                )
                if (after != null) after.run()
            })
        })
    }

    private fun showCatalogDialog() {
        val input = EditText(this)
        input.setSingleLine(true)
        input.setText(prefs()!!.getString(KEY_CATALOG_URL, ""))
        input.setHint(getString(R.string.ui_a_0615))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_a_0616))
            .setMessage(getString(R.string.ui_a_0617))
            .setView(input)
            .setPositiveButton(
                getString(R.string.ui_a_0618),
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    prefs()!!.edit()
                        .putString(KEY_CATALOG_URL, input.getText().toString().trim { it <= ' ' })
                        .apply()
                    fetchCatalog()
                })
            .setNegativeButton(getString(R.string.ui_cancel), null)
            .create().showForTv()
    }

    private fun showKeyboardDialog() {
        if (search == null) return
        val box = LinearLayout(this)
        box.setOrientation(LinearLayout.VERTICAL)
        box.setPadding(dp(12), dp(12), dp(12), dp(12))
        val preview = label(search!!.getText().toString(), 22, Color.WHITE)
        preview.setMinHeight(dp(54))
        preview.setBackgroundColor(PANEL_2)
        box.addView(preview, LinearLayout.LayoutParams(-1, dp(58)))

        val grid = GridLayout(this)
        grid.setColumnCount(8)
        val keys = arrayOf<String?>(
            "A", "B", "C", "D", "E", "F", "G", "H",
            "I", "J", "K", "L", "M", "N", "O", "P",
            "Q", "R", "S", "T", "U", "V", "W", "X",
            "Y", "Z", "0", "1", "2", "3", "4", "5",
            "6", "7", "8", "9", KtvRaw.get(R.string.ui_a_0619), KtvRaw.get(R.string.ui_a_0620), KtvRaw.get(R.string.ui_a_0621), KtvRaw.get(R.string.ui_a_0203)
        )
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.ui_a_0154)).setView(box).create()
        for (key in keys) {
            val keyView = button(key, View.OnClickListener { v: View? ->
                val value = (v as TextView).getText().toString()
                var text = preview.getText().toString()
                if (KtvRaw.get(R.string.ui_a_0619) == value) {
                    text = if (text.length > 0) text.substring(0, text.length - 1) else ""
                } else if (KtvRaw.get(R.string.ui_a_0620) == value) {
                    text = ""
                } else if (KtvRaw.get(R.string.ui_a_0621) == value) {
                    text += " "
                } else if (KtvRaw.get(R.string.ui_a_0203) == value) {
                    search!!.setText(text)
                    filterSongs(KtvRaw.get(R.string.ui_a_0069), text)
                    dialog.dismiss()
                    return@OnClickListener
                } else {
                    text += value
                }
                preview.setText(text)
                search!!.setText(text)
            })
            keyView.setGravity(Gravity.CENTER)
            val lp = GridLayout.LayoutParams()
            lp.width = dp(if (KtvRaw.get(R.string.ui_a_0621) == key || KtvRaw.get(R.string.ui_a_0203) == key) 126 else 72)
            lp.height = dp(58)
            lp.setMargins(dp(5), dp(5), dp(5), dp(5))
            grid.addView(keyView, lp)
        }
        box.addView(grid)
        dialog.setOnShowListener(OnShowListener { d: DialogInterface? ->
            if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus()
        })
        dialog.restoreSourceFocusOnDismiss()
        dialog.show()
    }

    private fun renderSongs() {
        if (songAdapter == null) return
        songAdapter!!.clear()
        for (i in visibleSongs.indices) {
            val song = visibleSongs.get(i)
            songAdapter!!.add(
                String.format(
                    Locale.ROOT,
                    "%02d  %s%s  -  %s    [%s/%s]%s",
                    i + 1,
                    if (store.isFavorite(song)) "★ " else "",
                    song.title,
                    song.singer,
                    song.category,
                    song.language,
                    if (song.remote) getString(R.string.ui_a_0622) else getString(R.string.ui_a_0623)
                )
            )
        }
        songAdapter!!.notifyDataSetChanged()
        header!!.setText(pageDisplayName(currentPage) + "  " + visibleSongs.size)
    }

    private fun renderQueue() {
        if (queueAdapter == null) return
        queueAdapter!!.clear()
        for (i in orderQueue!!.indices) queueAdapter!!.add(
            (i + 1).toString() + ". " + orderQueue.get(
                i
            ).displayTitle()
        )
        queueAdapter!!.notifyDataSetChanged()
    }

    private fun addNav(text: String?, listener: View.OnClickListener) {
        val index = navButtons.size
        val btn = button(text, listener)
        // 注册到列表以便维护选中态
        navButtons.add(btn)
        // 点击时更新选中态
        btn.setOnClickListener(View.OnClickListener { v: View? ->
            selectNav(index)
            listener.onClick(v)
        })
        nav!!.addView(btn, LinearLayout.LayoutParams(-1, dp(54)))
    }

    private fun addTile(
        grid: GridLayout,
        title: String?,
        sub: String?,
        listener: View.OnClickListener?
    ) {
        // 卡片式磁贴:圆角背景 + 描边 + 焦点高亮
        val tile = label(title + "\n" + sub, 20, TEXT_WHITE)
        tile.setFocusable(true)
        tile.setClickable(true)
        tile.setGravity(Gravity.CENTER)
        tile.setMinHeight(dp(110))
        // 默认背景:深色圆角 + 浅描边
        tile.setBackground(roundedBg(PANEL, 8, DIVIDER, 1))
        tile.setOnClickListener(listener)
        tile.setOnFocusChangeListener(OnFocusChangeListener { v: View?, hasFocus: Boolean ->
            if (hasFocus) {
                // 焦点态:青绿色圆角 + 白色描边
                v!!.setBackground(roundedBg(FOCUS, 8, Color.argb(180, 255, 255, 255), 2))
                tile.setTextColor(Color.rgb(8, 12, 18))
            } else {
                v!!.setBackground(roundedBg(PANEL, 8, DIVIDER, 1))
                tile.setTextColor(TEXT_WHITE)
            }
        })
        val lp = GridLayout.LayoutParams()
        lp.width = dp(210)
        lp.height = dp(126)
        lp.setMargins(dp(8), dp(8), dp(8), dp(8))
        grid.addView(tile, lp)
    }

    /** 由页面内部键（固定中文）取回当前语言的显示名。 */
    private fun pageDisplayName(page: String): String {
        val key = when (page) {
            KtvRaw.get(R.string.ui_a_0108) -> R.string.ui_a_0108
            KtvRaw.get(R.string.ui_a_0196) -> R.string.ui_a_0196
            KtvRaw.get(R.string.ui_a_0206) -> R.string.ui_a_0206
            KtvRaw.get(R.string.ui_a_0219) -> R.string.ui_a_0219
            KtvRaw.get(R.string.ui_a_0220) -> R.string.ui_a_0220
            KtvRaw.get(R.string.ui_a_0228) -> R.string.ui_a_0228
            KtvRaw.get(R.string.ui_a_0229) -> R.string.ui_a_0229
            KtvRaw.get(R.string.ui_a_0234) -> R.string.ui_a_0234
            KtvRaw.get(R.string.ui_a_0236) -> R.string.ui_a_0236
            KtvRaw.get(R.string.ui_a_0248) -> R.string.ui_a_0248
            KtvRaw.get(R.string.ui_a_0368) -> R.string.ui_a_0368
            KtvRaw.get(R.string.ui_a_0379) -> R.string.ui_a_0379
            KtvRaw.get(R.string.ui_a_0392) -> R.string.ui_a_0392
            KtvRaw.get(R.string.ui_a_0399) -> R.string.ui_a_0399
            KtvRaw.get(R.string.ui_a_0192) -> R.string.ui_a_0192
            KtvRaw.get(R.string.ui_a_0474) -> R.string.ui_a_0474
            KtvRaw.get(R.string.ui_a_0175) -> R.string.ui_a_0175
            KtvRaw.get(R.string.ui_a_0572) -> R.string.ui_a_0572
            KtvRaw.get(R.string.ui_a_0779) -> R.string.ui_a_0779
            else -> return page
        }
        return getString(key)
    }

    private fun setPage(page: String, display: String = page) {
        currentPage = page
        if (header != null) header!!.setText(display)
        if (listTitle != null) listTitle!!.setText(display)
    }

    private fun panel(): LinearLayout {
        val layout = LinearLayout(this)
        layout.setOrientation(LinearLayout.VERTICAL)
        layout.setPadding(dp(12), dp(12), dp(12), dp(12))
        // 圆角面板背景
        layout.setBackground(roundedBg(PANEL, 6, 0, 0))
        return layout
    }

    private fun sectionTitle(text: String?): TextView {
        val view = label(text, 22, GOLD)
        view.setMinHeight(dp(46))
        // 标题底部加一条浅色分割线
        view.setPadding(dp(8), dp(6), dp(8), dp(10))
        return view
    }

    // ===== TV 布局视图字段 =====
    /** 视频背景  */
    private var videoBackground: KtvVideoView? = null
    private var playerLayerRoot: FrameLayout? = null
    private var playerFocusBorder: View? = null
    private var playerTouchOverlay: View? = null
    private var homePlayerHost: View? = null
    private var subPagePlayerHost: View? = null
    private var activePlayerHost: View? = null
    private var lastHomeFocusSource: View? = null
    private var lastHomeFocusTarget: View? = null
    private var lastHomeFocusReverseKey: Int = KeyEvent.KEYCODE_UNKNOWN

    /** 顶部状态栏区域  */
    private val topStatusBar: LinearLayout? = null
    /** 当前播放提示  */
    /** 下载速度提示  */
    private var textDownloadSpeed: TextView? = null

    /** 下载进度容器  */
    private var downloadProgressContainer: LinearLayout? = null

    /** 下载状态文字  */
    private var textDownloadStatus: TextView? = null

    /** 下载进度条  */
    private var downloadProgressBar: ProgressBar? = null

    /** Tab 容器  */
    private var tabContainer: LinearLayout? = null

    /** 已点列表按钮  */

    /** 已点数量徽标  */
    private var textOrderBadge: TextView? = null
    /** 搜索按钮  */
    /** 搜索输入框  */
    private var searchInput: EditText? = null

    /** 搜索执行按钮  */
    private var btnDoSearch: TextView? = null

    /** 分类列表容器  */
    private var categoryList: LinearLayout? = null

    /** 列表标题  */
    private var listTitle: TextView? = null

    /** 分页信息  */
    private var textPageInfo: TextView? = null

    /** 上一页按钮  */
    private var btnPrevPage: TextView? = null

    /** 下一页按钮  */
    private var btnNextPage: TextView? = null

    /** 当前播放歌曲标题  */
    private var playerSongTitle: TextView? = null

    /** 当前播放歌手  */
    private var playerSongSinger: TextView? = null
    /** 原唱/伴唱切换按钮  */
    /** 原唱/伴唱文本  */
    private val textVocalMode: TextView? = null
    private var breadcrumbCurrent: TextView? = null

    /** 播放/暂停文本  */
    private val textPlayPause: TextView? = null

    /** 切歌按钮  */
    private val btnNextSong: TextView? = null

    /** 重唱按钮  */
    private val btnReplay: TextView? = null

    /** 音量按钮  */
    private val btnVolume: TextView? = null
    /** ===== 新版UI字段(顶栏/底栏/首页3列) =====  */
    /** 顶部状态栏  */
    private var topBar: View? = null

    /** 底部控制栏  */
    private var bottomBar: View? = null

    /** 顶栏 logo  */
    private var textLogo: ImageView? = null
    private var topBrandName: TextView? = null
    private var contentFrame: FrameLayout? = null
    private var browsePagination: View? = null

    /** 顶栏搜索按钮  */
    private var btnTopSearch: TextView? = null

    /** 顶栏语言切换按钮(中/英)  */
    private var btnTopLanguage: TextView? = null

    /** 顶栏已点按钮(整条)  */
    private var btnTopOrder: LinearLayout? = null
    private var textTopOrderLabel: TextView? = null

    /** 已点数量徽章  */
    private var textTopOrderBadge: TextView? = null

    /** 顶栏伴唱按钮  */
    private var btnTopVocal: TextView? = null

    /** 顶栏切歌按钮  */
    private var btnTopNext: TextView? = null

    /** 顶栏暂停按钮  */
    private var btnTopPause: TextView? = null

    /** 顶栏重唱按钮  */
    private var btnTopReplay: TextView? = null

    /** 顶栏调音按钮  */
    private var btnTopTone: TextView? = null

    /** 底栏猜你想唱  */
    private var btnBottomGuess: TextView? = null

    /** 底栏设置  */
    private var btnBottomSettings: TextView? = null

    /** 底栏扫码点歌 */
    private var btnBottomMobileOrder: TextView? = null

    /** 底栏退出  */
    private var btnBottomExit: TextView? = null

    /** 首页 3 列布局根  */
    private var homeLayout: LinearLayout? = null

    /** 首页视频窗口  */
    private var homeVideo: KtvVideoView? = null

    /** 首页视频标题  */
    private var homePlayerTitle: TextView? = null

    /** 首页视频歌手  */
    private var homePlayerSinger: TextView? = null

    /** 首页4个分类卡片  */
    private var homeCardRank: TextView? = null
    private var homeCardSong: TextView? = null
    private var homeCardSinger: TextView? = null
    private var homeCardLocal: TextView? = null
    private var homeCardRegular: TextView? = null
    private var homeCardFavorite: TextView? = null
    private var homeCardCategory: TextView? = null

    /** 新歌榜海报分页  */
    private var textPosterPage: TextView? = null
    private var homePosterView: View? = null

    /** 当前歌曲顶部提示(用于子页面左列)  */
    private var textCurrentSongTip: TextView? = null

    /** 子页面返回按钮(右栏顶部)  */
    private var btnBack: TextView? = null

    /** 品牌标识  */
    private var textBrandRight: TextView? = null

    /** 左侧栏布局引用  */
    private var leftColumn: LinearLayout? = null
    private var rightColumn: LinearLayout? = null

    /** 子页面二级分类滚动容器(语种筛选)  */
    private var subCategoryScroll: HorizontalScrollView? = null
    private var keyboardArea: View? = null
    private var rankCategoryGrid: GridLayout? = null
    private var paginationBar: View? = null
    private var fullScreenContainer: FrameLayout? = null
    private var isFullScreen = false
    private var playerReturnSurface: KtvVideoView? = null
    private var playerReturnHost: View? = null
    private var fullScreenPauseText: TextView? = null
    private var fullScreenVocalText: TextView? = null
    private var fullScreenPauseIcon: ImageView? = null
    private var fullScreenVocalIcon: ImageView? = null
    private var fullScreenSongInfo: TextView? = null
    private var fullScreenSeekBar: SeekBar? = null
    private var fullScreenTime: TextView? = null
    private var fullScreenSeeking = false
    private var fullScreenControls: LinearLayout? = null
    private var fullScreenProgressRow: View? = null
    private var randomLocalRequestSerial = 0L
    private var lastRandomLocalSongId: String? = null
    /** 启动时无恢复播放/空队列: 曲库就绪后自动随机播放本地已下载歌曲(仅触发一次)。 */
    private var startupRandomPending = false
    private var advancingPlayback = false
    private var lastAutoAdvanceAtMs = 0L
    private var fullScreenChromeVisible = false
    private val hideFullScreenChromeRunnable = Runnable {
        if (isFullScreen) {
            setFullScreenChromeVisible(false)
        }
    }
    private var playbackModeNotice: TextView? = null
    private var songIntroNotice: TextView? = null
    private var songIntroIcon: TextView? = null
    private val hidePlaybackModeNotice = Runnable {
        playbackModeNotice?.animate()?.alpha(0f)?.setDuration(180L)?.withEndAction {
            playbackModeNotice?.visibility = View.GONE
        }?.start()
    }
    private val hideSongIntroNotice = Runnable {
        songIntroNotice?.animate()?.alpha(0f)?.setDuration(250L)?.withEndAction {
            songIntroNotice?.visibility = View.GONE
            songIntroIcon?.visibility = View.GONE
        }?.start()
    }

    /** 当前选中的 Tab 索引  */
    private var currentTabIndex = -1 // -1 = 首页

    /** 当前分类列表  */
    private val currentCategories: MutableList<String> = ArrayList<String>()

    /** 排行榜显示名对应的数据库 playlist id，索引与 currentCategories 一致。 */
    private val rankPlaylistIds: MutableList<String> = ArrayList<String>()

    /** 当前分类索引  */
    private var currentCategoryIndex = 0

    private var categoryParentPage = 0
    private var categoryParentQuery = ""

    private fun label(text: String?, sp: Int, color: Int): TextView {
        val view = TextView(this)
        view.setText(text)
        view.setTextSize(sp.toFloat())
        view.setTextColor(color)
        view.setGravity(Gravity.CENTER_VERTICAL)
        view.setPadding(dp(8), dp(6), dp(8), dp(6))
        return view
    }

    private fun setTopControlIcon(view: TextView?, drawableRes: Int) {
        if (view == null) return
        val icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(this, drawableRes)?.mutate()
        icon?.setBounds(0, 0, dp(18), dp(18))
        view.setCompoundDrawablesRelative(icon, null, null, null)
        view.compoundDrawablePadding = dp(5)
    }

    private fun button(text: String?, listener: View.OnClickListener?): TextView {
        val view = label(text, 18, TEXT_WHITE)
        view.setFocusable(true)
        view.setClickable(true)
        view.setMinHeight(dp(50))
        view.setOnClickListener(listener)
        // 焦点态:圆角青绿色背景;失去焦点:透明
        view.setOnFocusChangeListener(OnFocusChangeListener { v: View?, hasFocus: Boolean ->
            if (hasFocus) {
                val gd = GradientDrawable()
                gd.setColor(FOCUS)
                gd.setCornerRadius(dp(6).toFloat())
                gd.setStroke(dp(1), Color.argb(100, 255, 255, 255))
                v!!.setBackground(gd)
                view.setTextColor(Color.rgb(8, 12, 18))
            } else {
                v!!.setBackgroundColor(Color.TRANSPARENT)
                view.setTextColor(TEXT_WHITE)
            }
        })
        return view
    }

    /**
     * 创建圆角面板背景 drawable
     * @param bgColor 背景色
     * @param cornerRadiusDp 圆角半径(dp)
     * @param strokeColor 描边色(0 表示无描边)
     * @param strokeWidthDp 描边宽度(dp)
     * @return GradientDrawable 圆角背景
     */
    private fun roundedBg(
        bgColor: Int,
        cornerRadiusDp: Int,
        strokeColor: Int,
        strokeWidthDp: Int
    ): GradientDrawable {
        val gd = GradientDrawable()
        gd.setColor(bgColor)
        gd.setCornerRadius(dp(cornerRadiusDp).toFloat())
        if (strokeColor != 0 && strokeWidthDp > 0) {
            gd.setStroke(dp(strokeWidthDp), strokeColor)
        }
        return gd
    }

    private fun listView(): ListView {
        val view = ListView(this)
        view.setBackgroundColor(PANEL_2)
        view.setCacheColorHint(Color.TRANSPARENT)
        // 分割线:半透明白色,1px 高
        view.setDivider(ColorDrawable(DIVIDER))
        view.setDividerHeight(dp(1))
        view.setFocusable(true)
        return view
    }

    private fun tvAdapter(): ArrayAdapter<String?> {
        return object :
            ArrayAdapter<String?>(this, android.R.layout.simple_list_item_1, ArrayList<String?>()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(TEXT_WHITE)
                view.setTextSize(18f)
                view.setMinHeight(dp(48))
                view.setGravity(Gravity.CENTER_VERTICAL)
                view.setPadding(dp(14), dp(4), dp(14), dp(4))
                return view
            }
        }
    }

    /**
     * 初始化 TV 布局的视图组件和事件监听
     */
    private fun initTvLayout() {
        // 绑定主布局视图组件
        val subPageVideoPlaceholder = findViewById<KtvVideoView?>(R.id.video_background)
        playerLayerRoot = findViewById(R.id.layout_root)
        homePlayerHost = findViewById(R.id.home_player_container)
        subPagePlayerHost = findViewById(R.id.player_container)
        installHomePlayerAspectGuard()
        textDownloadSpeed = findViewById<TextView?>(R.id.text_download_speed)
        downloadProgressContainer = findViewById<LinearLayout?>(R.id.download_progress_container)
        textDownloadStatus = findViewById<TextView?>(R.id.text_download_status)
        downloadProgressBar = findViewById<ProgressBar?>(R.id.download_progress_bar)
        tabContainer = findViewById<LinearLayout?>(R.id.tab_container)
        // 子页面沿用顶部已点数字徽章。
        textOrderBadge = null
        searchInput = findViewById<EditText?>(R.id.search_input)
        btnDoSearch = findViewById<TextView?>(R.id.btn_do_search)
        categoryList = findViewById<LinearLayout>(R.id.category_list)
        textPageInfo = findViewById<TextView>(R.id.text_page_info)
        btnPrevPage = findViewById<TextView?>(R.id.btn_prev_page)
        btnNextPage = findViewById<TextView?>(R.id.btn_next_page)
        songList = findViewById<ListView?>(R.id.song_list)
        playerSongTitle = findViewById<TextView?>(R.id.player_song_title)
        playerSongSinger = findViewById<TextView?>(R.id.player_song_singer)
        breadcrumbCurrent = findViewById<TextView?>(R.id.breadcrumb_current)
        // 新布局不显示独立列表标题，不能让业务标题覆盖面包屑。
        listTitle = TextView(this)
        val breadcrumbHome = findViewById<TextView?>(R.id.breadcrumb_home)
        if (breadcrumbHome != null) breadcrumbHome.setOnClickListener { navigateBackToHome() }

        // ===== 顶栏/底栏控件 =====
        topBar = findViewById<View?>(R.id.top_bar)
        bottomBar = findViewById<View?>(R.id.bottom_bar)
        textLogo = findViewById<ImageView?>(R.id.text_logo)
        topBrandName = findViewById<TextView?>(R.id.top_brand_name)
        contentFrame = findViewById<FrameLayout?>(R.id.content_frame)
        browsePagination = findViewById<View?>(R.id.browse_pagination)
        btnTopSearch = findViewById<TextView?>(R.id.btn_top_search)
        btnTopLanguage = findViewById<TextView?>(R.id.btn_top_language)
        refreshLanguageButtonText()
        btnTopOrder = findViewById<LinearLayout?>(R.id.btn_top_order)
        textTopOrderLabel = findViewById<TextView?>(R.id.text_top_order_label)
        textTopOrderBadge = findViewById<TextView?>(R.id.text_top_order_badge)
        btnTopVocal = findViewById<TextView?>(R.id.btn_top_vocal)
        btnTopNext = findViewById<TextView?>(R.id.btn_top_next)
        btnTopPause = findViewById<TextView?>(R.id.btn_top_pause)
        btnTopReplay = findViewById<TextView?>(R.id.btn_top_replay)
        btnTopTone = findViewById<TextView?>(R.id.btn_top_tone)
        btnTopVocal?.text = vocalActionText()
        btnTopPause?.text = if (playWhenPrepared) getString(R.string.ui_a_0309) else getString(R.string.ui_a_0310)
        setTopControlIcon(textTopOrderLabel, R.drawable.ott_ic_ctrl_orderlist)
        setTopControlIcon(btnTopVocal, vocalActionIcon())
        setTopControlIcon(btnTopNext, R.drawable.ott_ic_ctrl_switch_song)
        setTopControlIcon(
            btnTopPause,
            if (playWhenPrepared) R.drawable.ott_ic_ctrl_pause else R.drawable.ott_ic_ctrl_play,
        )
        setTopControlIcon(btnTopReplay, R.drawable.ott_ic_ctrl_replay)
        setTopControlIcon(btnTopTone, R.drawable.ott_ic_ctrl_volume)
        btnBottomGuess = findViewById<TextView?>(R.id.btn_bottom_guess)
        btnBottomMobileOrder = findViewById<TextView?>(R.id.btn_bottom_mobile_order)
        btnBottomSettings = findViewById<TextView?>(R.id.btn_bottom_settings)
        btnBottomExit = findViewById<TextView?>(R.id.btn_bottom_exit)

        // ===== 首页 3 列布局 =====
        homeLayout = findViewById<LinearLayout?>(R.id.home_layout)
        val homeVideoPlaceholder = findViewById<KtvVideoView?>(R.id.home_video_background)
        homePlayerTitle = findViewById<TextView?>(R.id.home_player_song_title)
        homePlayerSinger = findViewById<TextView?>(R.id.home_player_song_singer)
        homeCardRank = findViewById<TextView?>(R.id.home_card_rank)
        homeCardSong = findViewById<TextView?>(R.id.home_card_song)
        homeCardSinger = findViewById<TextView?>(R.id.home_card_singer)
        homeCardLocal = findViewById<TextView?>(R.id.home_card_local)
        homeCardRegular = findViewById<TextView?>(R.id.home_card_regular)
        homeCardFavorite = findViewById<TextView?>(R.id.home_card_favorite)
        homeCardCategory = findViewById<TextView?>(R.id.home_card_category)
        // 首页底部三卡(常唱/收藏/分类): 渐变底 + 右侧独立配图，替代整张被
        // 拉伸变形的 webp 背景；配图尺寸随屏幕密度/紧凑模式自适应。
        run {
            val artSize = dp(if (compactUi) 34 else 44)
            mapOf(
                R.id.home_card_regular to R.drawable.home_art_regular,
                R.id.home_card_favorite to R.drawable.home_art_favorite,
                R.id.home_card_category to R.drawable.home_art_category,
            ).forEach { (viewId, artRes) ->
                findViewById<TextView?>(viewId)?.apply {
                    val art = AppCompatResources.getDrawable(context, artRes)?.mutate() ?: return@apply
                    art.setBounds(0, 0, artSize, artSize)
                    setCompoundDrawablesRelative(null, null, art, null)
                    compoundDrawablePadding = dp(6)
                }
            }
        }
        textPosterPage = findViewById<TextView?>(R.id.text_poster_page)

        // ===== 子页面外壳 =====
        textCurrentSongTip = findViewById<TextView?>(R.id.text_current_song_tip)
        btnBack = findViewById<TextView?>(R.id.btn_back)
        textBrandRight = findViewById<TextView?>(R.id.text_brand_right)
        leftColumn = findViewById<LinearLayout?>(R.id.left_column)
        rightColumn = findViewById<LinearLayout?>(R.id.right_column)
        subCategoryScroll = findViewById<HorizontalScrollView?>(R.id.sub_category_scroll)
        keyboardArea = findViewById<View?>(R.id.keyboard_area)
        rankCategoryGrid = findViewById(R.id.rank_category_grid)
        paginationBar = findViewById<View?>(R.id.pagination_bar)
        fullScreenContainer = findViewById<FrameLayout?>(R.id.container)
        // 全屏播放容器: 点击用于显隐控制层, 本身是铺满屏幕的表面, 不能有焦点/按下填充。
        fullScreenContainer?.let { TvFocusStyler.suppressFill(it) }

        // 设置顶栏按钮事件
        if (btnTopSearch != null) btnTopSearch!!.setOnClickListener(View.OnClickListener { v: View? -> showQuickSearchPage() })
        // 语言切换：先记录当前歌曲进度，AppCompat 重建 Activity 后从原位置恢复。
        btnTopLanguage?.setOnClickListener(View.OnClickListener { v: View? ->
            switchLanguageKeepingPlayback(!KtvApplication.isEnglish())
        })
        if (btnTopOrder != null) btnTopOrder!!.setOnClickListener(View.OnClickListener { v: View? -> showOrderListPage() })
        if (btnTopVocal != null) btnTopVocal!!.setOnClickListener(View.OnClickListener { v: View? -> toggleOriginalVocal() })
        if (btnTopNext != null) btnTopNext!!.setOnClickListener(View.OnClickListener { v: View? -> playNext() })
        if (btnTopPause != null) btnTopPause!!.setOnClickListener(View.OnClickListener { v: View? -> togglePlay() })
        if (btnTopReplay != null) btnTopReplay!!.setOnClickListener(View.OnClickListener { v: View? -> replay() })
        if (btnTopTone != null) btnTopTone!!.setOnClickListener(View.OnClickListener { v: View? -> showTuningDialog() })

        // 设置底栏按钮事件
        if (btnBottomGuess != null) btnBottomGuess!!.setOnClickListener(View.OnClickListener { v: View? -> showGuessPage() })
        if (btnBottomMobileOrder != null) btnBottomMobileOrder!!.setOnClickListener(View.OnClickListener { showMobileRemoteDialog() })
        if (btnBottomSettings != null) btnBottomSettings!!.setOnClickListener(View.OnClickListener { v: View? -> showSettingsPage() })
        if (btnBottomExit != null) btnBottomExit!!.setOnClickListener(View.OnClickListener { v: View? -> showExitDialog() })

        // 设置首页4个分类卡片点击事件
        if (homeCardRank != null) homeCardRank!!.setOnClickListener(View.OnClickListener { v: View? -> loadHotSongs() })
        if (homeCardSong != null) homeCardSong!!.setOnClickListener(View.OnClickListener { v: View? -> showSearchPage() })
        if (homeCardSinger != null) homeCardSinger!!.setOnClickListener(View.OnClickListener { v: View? -> loadSingers() })
        if (homeCardLocal != null) homeCardLocal!!.setOnClickListener(View.OnClickListener { v: View? -> showLocalPage() })
        if (homeCardRegular != null) homeCardRegular!!.setOnClickListener(View.OnClickListener { v: View? -> loadSangHistory() })
        if (homeCardFavorite != null) homeCardFavorite!!.setOnClickListener(View.OnClickListener { v: View? -> loadFavorites() })
        if (homeCardCategory != null) homeCardCategory!!.setOnClickListener(View.OnClickListener { v: View? -> loadCategoryPlaylists() })
        val homePoster = findViewById<View?>(R.id.home_poster_container)
        homePosterView = homePoster
        textPosterPage?.text = "1/${homeBanners.size}"
        main.removeCallbacks(homeBannerRunnable)
        main.postDelayed(homeBannerRunnable, 5000L)
        if (homePoster != null) homePoster.setOnClickListener(View.OnClickListener { v: View? -> openHomeBannerTarget(homeBannerIndex) })
        // 原版始终保留同一个视频窗口，仅在首页、子页和全屏间调整边界。
        // 两个 XML View 只用于提供精确的布局锚点，避免页面操作重建解码 Surface。
        homeVideoPlaceholder?.visibility = View.GONE
        subPageVideoPlaceholder?.visibility = View.GONE
        val persistentVideo = KtvVideoView(this).apply {
            setOnClickListener {
                if (isFullScreen) exitFullScreenPlayer() else showFullScreenPlayer()
            }
        }
        // 视频层本身也是表面型 View, 只做点击进入/退出全屏, 不要焦点填充。
        TvFocusStyler.suppressFill(persistentVideo)
        val root = playerLayerRoot
        if (root != null) {
            val maskIndex = root.indexOfChild(findViewById<View>(R.id.mask))
            val insertionIndex = if (maskIndex >= 0) maskIndex else root.childCount
            root.addView(persistentVideo, insertionIndex, FrameLayout.LayoutParams(1, 1))
            playerFocusBorder = View(this).apply {
                setBackgroundResource(R.drawable.bg_player_focus)
                isClickable = false
                isFocusable = false
                visibility = View.GONE
                UiCompat.setElevation(this, dp(6).toFloat())
            }.also { root.addView(it, insertionIndex + 1, FrameLayout.LayoutParams(1, 1)) }
            playbackModeNotice = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 18f
                setTextColor(Color.WHITE)
                visibility = View.GONE
                alpha = 0f
                UiCompat.setElevation(this, dp(8).toFloat())
            }.also { root.addView(it, insertionIndex + 1, FrameLayout.LayoutParams(dp(104), dp(104))) }
            songIntroNotice = TextView(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(94), 0, dp(20), 0)
                textSize = 18f
                setTextColor(Color.WHITE)
                maxLines = 2
                visibility = View.GONE
                alpha = 0f
                UiCompat.setElevation(this, dp(7).toFloat())
            }.also { root.addView(it, insertionIndex + 1, FrameLayout.LayoutParams(dp(330), dp(92))) }
            songIntroIcon = TextView(this).apply {
                text = "♫"
                textSize = 34f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(254, 78, 184), Color.rgb(87, 52, 230))
                ).apply { shape = GradientDrawable.OVAL; setStroke(dp(3), Color.argb(210, 255, 255, 255)) }
                visibility = View.GONE
                alpha = 0f
                UiCompat.setElevation(this, dp(9).toFloat())
            }.also { root.addView(it, insertionIndex + 2, FrameLayout.LayoutParams(dp(78), dp(78))) }
            playerTouchOverlay = View(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = true
                isFocusable = false
                setOnClickListener { showFullScreenPlayer() }
                UiCompat.setElevation(this, dp(10).toFloat())
                // 这层铺满播放画面且浮在最上层: 一旦被套上焦点/按下填充,
                // 触摸就会把整块播放画面刷成纯色(遮罩层), 必须显式排除。
                // 它只负责抓触摸, 保持 isFocusable=false, 不参与遥控器焦点。
                TvFocusStyler.suppressFill(this, focusable = false)
            }.also { root.addView(it, insertionIndex + 1, FrameLayout.LayoutParams(1, 1)) }
        }
        homeVideo = persistentVideo
        videoBackground = persistentVideo
        player = persistentVideo
        persistentVideo.bind(playbackEngine)
        playbackEngine.attach(persistentVideo)
        movePlayerToHost(homePlayerHost)
        listOfNotNull(homePlayerHost, subPagePlayerHost).forEach(::bindPlayerHostFocus)
        // The persistent video surface sits above its placeholder; forward touch entry to full screen too.
        persistentVideo.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) showFullScreenPlayer()
            true
        }

        // 设置子页面返回按钮
        if (btnBack != null) btnBack!!.setOnClickListener { navigateBack() }

        // 设置 Tab 导航
        setupTabs()
        // 构建虚拟键盘
        buildKeyboard()

        // 设置搜索/翻页事件
        if (btnDoSearch != null) btnDoSearch!!.setOnClickListener(View.OnClickListener { v: View? -> performSearch() })
        if (searchInput != null) {
            searchInput!!.setOnEditorActionListener(OnEditorActionListener { v: TextView?, actionId: Int, event: KeyEvent? ->
                performSearch()
                true
            })
            searchInput!!.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(text: Editable?) {
                    if (!suppressSearchWatcher) scheduleKeyboardSearch()
                }
            })
        }
        if (btnPrevPage != null) btnPrevPage!!.setOnClickListener(View.OnClickListener { v: View? -> prevPage() })
        if (btnNextPage != null) btnNextPage!!.setOnClickListener(View.OnClickListener { v: View? -> nextPage() })
        listOfNotNull(
            btnTopSearch, btnTopLanguage, btnTopOrder, btnTopVocal, btnTopNext, btnTopPause, btnTopReplay, btnTopTone,
            btnBottomGuess, btnBottomMobileOrder, btnBottomSettings, btnBottomExit, btnBack, btnPrevPage, btnNextPage,
            homeCardRank, homeCardSong, homeCardSinger, homeCardLocal, homeCardRegular,
            homeCardFavorite, homeCardCategory,
        ).forEach(::installPressFeedback)
        // 播放画面宿主/海报是「表面型」View: 不能套焦点/按下填充,
        // 否则触摸一次就会用纯色层盖住整个播放画面或海报(见 TvFocusStyler.suppressFill)。
        listOfNotNull(homePlayerHost, subPagePlayerHost, homePoster).forEach(TvFocusStyler::suppressFill)
        (homePoster as? FrameLayout)?.foreground = ContextCompat.getDrawable(this, R.drawable.bg_player_focus)
        configureTvFocusNavigation()
        prepareTvFocusableTree(window.decorView)

        // 设置歌曲列表点击事件
        songList!!.setOnItemClickListener(OnItemClickListener { parent: AdapterView<*>?, view: View?, position: Int, id: kotlin.Long ->
            if (browseMode == "settings" || browseMode == "settings_section") {
                activeSettingsEntries.getOrNull(position)?.takeIf { it.checked == null }?.action?.invoke()
                return@OnItemClickListener
            }
            if (browseMode == "quick" || browseMode == "category_list") return@OnItemClickListener
            if ("singer_list" == browseMode) {
                if (position < visibleSingers.size) {
                    val singer = visibleSingers.get(position)!!
                    loadSingerSongs(singer[0], singer[1])
                }
                return@OnItemClickListener
            }
            if (position < visibleSongs.size) {
                val song = visibleSongs.get(position)
                if (currentTabIndex == -1) {
                    // 首页: 点击卡片导航
                    showHomePage()
                } else if (currentTabIndex == 4) {
                    // 已点列表点击只调整为下一首，切歌只能由明确的“切歌”操作触发。
                    if (currentSong != null && currentSong!!.equals(song)) {
                        toast(getString(R.string.ui_p_038) + song.title)
                    } else {
                        moveQueuedSongToNext(song)
                    }
                } else if (currentTabIndex == 5) {
                    // 已唱历史: 重新点歌
                    addToQueue(song)
                    renderSongList()
                } else if (currentTabIndex == 6) {
                    // 下载列表中的歌曲同样按点播入队，不能抢占当前播放。
                    addToQueue(song)
                } else if (currentTabIndex == 7) {
                    // 收藏: 点歌
                    addToQueue(song)
                    renderSongList()
                } else {
                    addToQueue(song)
                    renderSongList()
                }
            }
        })
        songList!!.setOnItemLongClickListener(OnItemLongClickListener { parent: AdapterView<*>?, view: View?, position: Int, id: kotlin.Long ->
            if ("settings" == browseMode || "singer_list" == browseMode) return@OnItemLongClickListener false
            if (position < 0 || position >= visibleSongs.size) return@OnItemLongClickListener false
            val song = visibleSongs.get(position)
            if (currentTabIndex == 4) {
                if (currentSong != null && currentSong!!.equals(song)) {
                    toast(getString(R.string.ui_a_0581))
                    return@OnItemLongClickListener true
                }
                orderQueue!!.remove(song)
                saveState()
                loadOrderedList()
                toast(getString(R.string.ui_p_039) + song.title)
            } else {
                showSongActions(song)
            }
            true
        })
    }

    /**
     * 首页 - 仿原版3列布局(视频+4卡片+新歌榜海报)
     */
    private fun showHomePage() {
        if (!restoringFocusRoute) focusReturnStack.clear()
        browseRequestVersion++
        main.removeCallbacks(keyboardSearchRunnable)
        currentPage = KtvRaw.get(R.string.ui_a_0108)
        currentTabIndex = -1
        currentCategories.clear()
        currentCategoryIndex = -1
        clearHomeFocusTransition()
        updateCategories()
        // 隐藏子页面外壳,显示首页 3 列
        val shell = findViewById<View?>(R.id.main_layout)
        if (shell != null) shell.setVisibility(View.GONE)
        topBar?.apply {
            visibility = View.VISIBLE
            translationY = 0f
            setBackgroundResource(R.drawable.bg_top_bar)
        }
        textLogo?.visibility = View.VISIBLE
        topBrandName?.visibility = View.VISIBLE
        btnTopSearch?.visibility = View.VISIBLE
        // 恢复首页顶栏: 弹性占位放回，控制按钮仍靠右。
        findViewById<View?>(R.id.top_bar_spacer)?.visibility = View.VISIBLE
        findViewById<View?>(R.id.sub_controls_spacer)?.visibility = View.VISIBLE
        bottomBar?.visibility = View.VISIBLE
        updateContentFrameMargins(chromeBarHeight(), chromeBarHeight())
        if (homeLayout != null) homeLayout!!.setVisibility(View.VISIBLE)
        configureTvFocusNavigation()
        movePlayerToHost(homePlayerHost)
        // 同步当前播放信息到首页
        syncPlayerInfo()
        // 隐藏已点的子页面辅助控件
        if (subCategoryScroll != null) subCategoryScroll!!.setVisibility(View.GONE)
        // 首页(菜单界面)显示时把默认焦点落到"歌名"卡片, 遥控器一进首页即可按确认搜歌。
        // 从子页面返回时 restoreFocusBookmark 会递增 focusRestoreSerial 并恢复到进入前的卡片,
        // 这里以 serial 校验避让, 不抢占专项焦点恢复; 触屏模式也不主动设焦。
        val focusSerialAtShow = focusRestoreSerial
        window.decorView.post {
            prepareTvFocusableTree(window.decorView)
            if (focusRestoreSerial == focusSerialAtShow && !window.decorView.isInTouchMode &&
                homeCardSong?.isShown == true && homeCardSong?.isFocusable == true
            ) {
                homeCardSong?.requestFocus()
            }
        }
    }

    /**
     * 切换到子页面外壳(左视频+键盘 / 右内容)
     * @param breadcrumb 面包屑显示文字(主页 / xxx)
     */
    private fun showSubPageShell(breadcrumb: String, displayBreadcrumb: String = breadcrumb) {
        rememberReturnPointIfNeeded(breadcrumb)
        browseRequestVersion++
        visibleSongs.clear()
        visibleSingers.clear()
        songList?.adapter = tvAdapter()
        currentPage = breadcrumb
        if (homeLayout != null) homeLayout!!.setVisibility(View.GONE)
        clearHomeTopFocusNavigation()
        topBar?.apply {
            visibility = View.VISIBLE
            // 内容区已经让出整条顶栏的高度，顶栏不需要再下移；
            // 之前靠 translationY 躲内容，反而让按钮与面包屑挤在一起。
            translationY = 0f
            setBackgroundColor(Color.TRANSPARENT)
        }
        // logo/品牌名在子页面同样保留，作为顶栏最左侧的落点。
        textLogo?.visibility = View.VISIBLE
        topBrandName?.visibility = View.VISIBLE
        btnTopSearch?.visibility = View.GONE
        // 控制按钮与首页一致靠右贴住 WiFi，弹性占位保留(左侧留给 logo/品牌名)。
        findViewById<View?>(R.id.top_bar_spacer)?.visibility = View.VISIBLE
        // 内容区已经让出顶栏高度，右列顶部那条 48dp 占位就多余了，
        // 否则顶栏与面包屑之间会空出一整条(红框位置)。
        findViewById<View?>(R.id.sub_controls_spacer)?.visibility = View.GONE
        bottomBar?.visibility = View.GONE
        // 共享播放窗是挂在 layout_root 上的浮层，位置完全跟随宿主 View 的
        // 窗口坐标；内容区若顶到 y=0，视频窗就会压到顶栏左侧(logo/控制按钮)。
        // 因此子页面同样要留出顶栏高度，只放开底部(子页面没有底栏)。
        updateContentFrameMargins(chromeBarHeight(), 0)
        val shell = findViewById<View?>(R.id.main_layout)
        if (shell != null) shell.setVisibility(View.VISIBLE)
        if (breadcrumbCurrent != null) {
            breadcrumbCurrent!!.text = displayBreadcrumb.removePrefix(getString(R.string.ui_a_0626)).ifEmpty { getString(R.string.ui_p_044) }
        }
        if (keyboardArea != null) keyboardArea!!.setVisibility(View.VISIBLE)
        rankCategoryGrid?.visibility = View.GONE
        if (tabContainer != null) tabContainer!!.setVisibility(View.VISIBLE)
        if (paginationBar != null) paginationBar!!.setVisibility(View.GONE)
        browsePagination?.visibility = View.VISIBLE
        // 进入新子页面时先把分页显示归位为第 1/1 页: browseTotalPages 是全局共享状态,
        // 上面的 browseRequestVersion++ 会让上一页的异步加载回调失效, 若不在此重置,
        // 分页栏会短暂(或在加载被拦截时一直)沿用上一页残留的大总页数(如只有1页却显示"1/50页")。
        // 加载完成后各 load 函数/ applyBrowsePage 会再刷新成真实页数; browsePage 不在此重置,
        // 以便返回父页面时能恢复原先浏览的页码。
        browseTotalPages = 1
        // 当前页码固定显示 1: 此处是进入页面的入口, 各 load 函数随后才会设置 browsePage
        // (部分页面如收藏/已点/下载甚至不分页)。若读 browsePage 会拿到上一页残留值(如残留2→显示"3/1")。
        // 需要分页的页面加载完成后会用真实 browsePage 通过 updatePageInfo() 刷新。
        textPageInfo?.setText(String.format(getString(R.string.ui_a_0765), 1, 1))
        if (songList != null) {
            songList!!.setBackgroundResource(R.drawable.bg_song_list)
            songList!!.setDivider(ColorDrawable(Color.argb(34, 255, 255, 255)))
            songList!!.setDividerHeight(dp(1))
            songList!!.selector = ColorDrawable(Color.TRANSPARENT)
            songList!!.itemsCanFocus = true
            songList!!.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        movePlayerToHost(subPagePlayerHost)
        window.decorView.post {
            prepareTvFocusableTree(window.decorView)
        }
    }

    private fun updateContentFrameMargins(top: Int, bottom: Int) {
        val frame = contentFrame ?: return
        val params = frame.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.topMargin == top && params.bottomMargin == bottom) return
        params.topMargin = top
        params.bottomMargin = bottom
        frame.layoutParams = params
    }

    private fun movePlayerToHost(host: View?) {
        if (host == null || isFullScreen) return
        activePlayerHost = host
        host.post { applyPlayerBounds(host) }
    }

    private fun bindPlayerHostFocus(host: View) {
        host.isClickable = true
        host.isFocusable = true
        host.isFocusableInTouchMode = false
        host.setOnClickListener { showFullScreenPlayer() }
        host.setOnFocusChangeListener { _, hasFocus ->
            playerFocusBorder?.apply {
                isActivated = hasFocus
                visibility = if (hasFocus && !isFullScreen) View.VISIBLE else View.GONE
            }
            if (hasFocus && !isFullScreen) {
                activePlayerHost = host
                applyPlayerBounds(host)
            }
        }
    }

    private fun applyPlayerBounds(host: View) {
        val root = playerLayerRoot ?: return
        val video = player ?: return
        if (host.width <= 0 || host.height <= 0 || host.visibility != View.VISIBLE) return
        val rootLocation = IntArray(2)
        val hostLocation = IntArray(2)
        root.getLocationInWindow(rootLocation)
        host.getLocationInWindow(hostLocation)
        val params = (video.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(host.width, host.height)
        params.width = host.width
        params.height = host.height
        params.leftMargin = hostLocation[0] - rootLocation[0]
        params.topMargin = hostLocation[1] - rootLocation[1]
        video.layoutParams = params
        video.visibility = View.VISIBLE
        playerFocusBorder?.let { border ->
            val borderParams = (border.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(host.width, host.height)
            borderParams.width = host.width
            borderParams.height = host.height
            borderParams.leftMargin = params.leftMargin
            borderParams.topMargin = params.topMargin
            border.layoutParams = borderParams
            border.isActivated = host.hasFocus()
            border.visibility = if (host.hasFocus() && !isFullScreen) View.VISIBLE else View.GONE
        }
        playerTouchOverlay?.let { overlay ->
            val overlayParams = (overlay.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(host.width, host.height)
            overlayParams.width = host.width
            overlayParams.height = host.height
            overlayParams.leftMargin = params.leftMargin
            overlayParams.topMargin = params.topMargin
            overlay.layoutParams = overlayParams
            overlay.visibility = if (isFullScreen) View.GONE else View.VISIBLE
        }
        updatePlaybackNoticeBounds()
    }

    private fun expandPlayerToFullScreen() {
        val root = playerLayerRoot ?: return
        val video = player ?: return
        val params = (video.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        params.leftMargin = 0
        params.topMargin = 0
        video.layoutParams = params
        playerFocusBorder?.visibility = View.GONE
        video.bringToFront()
        songIntroNotice?.bringToFront()
        songIntroIcon?.bringToFront()
        playbackModeNotice?.bringToFront()
        updatePlaybackNoticeBounds()
        fullScreenContainer?.bringToFront()
    }

    private fun updatePlaybackNoticeBounds() {
        val root = playerLayerRoot ?: return
        val video = player ?: return
        val videoParams = video.layoutParams as? FrameLayout.LayoutParams ?: return
        val width = if (videoParams.width > 0) videoParams.width else root.width
        val height = if (videoParams.height > 0) videoParams.height else root.height
        playbackModeNotice?.let { notice ->
            val size = dp(104)
            (notice.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.width = size; params.height = size
                params.leftMargin = videoParams.leftMargin + (width - size) / 2
                params.topMargin = videoParams.topMargin + (height - size) / 2
                notice.layoutParams = params
            }
        }
        songIntroNotice?.let { notice ->
            val noticeWidth = min(dp(330), (width * 0.72f).toInt())
            val noticeHeight = dp(92)
            (notice.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.width = noticeWidth; params.height = noticeHeight
                params.leftMargin = videoParams.leftMargin + (width - noticeWidth) / 2
                params.topMargin = videoParams.topMargin + (height - noticeHeight) / 2
                notice.layoutParams = params
                songIntroIcon?.let { icon ->
                    (icon.layoutParams as? FrameLayout.LayoutParams)?.let { iconParams ->
                        iconParams.width = dp(78); iconParams.height = dp(78)
                        iconParams.leftMargin = params.leftMargin + dp(8)
                        iconParams.topMargin = params.topMargin + dp(7)
                        icon.layoutParams = iconParams
                    }
                }
            }
        }
    }

    private fun showPlaybackModeNotice(modeResId: Int, persistent: Boolean = false) {
        val notice = playbackModeNotice ?: return
        main.removeCallbacks(hidePlaybackModeNotice)
        notice.text = getString(modeResId)
        notice.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(151, 126, 255), Color.rgb(74, 39, 220))
        ).apply { shape = GradientDrawable.OVAL }
        updatePlaybackNoticeBounds()
        notice.visibility = View.VISIBLE
        notice.alpha = 0f
        notice.animate().alpha(1f).setDuration(140L).start()
        if (!persistent) main.postDelayed(hidePlaybackModeNotice, 1400L)
    }

    private fun hidePlaybackNoticeNow() {
        main.removeCallbacks(hidePlaybackModeNotice)
        playbackModeNotice?.visibility = View.GONE
        playbackModeNotice?.alpha = 0f
    }

    private fun showSongIntro(song: Song?) {
        if (!songTitleSubtitleEnabled || song == null) return
        val notice = songIntroNotice ?: return
        main.removeCallbacks(hideSongIntroNotice)
        notice.text = "${song.title}\n${song.singer.orEmpty()}"
        notice.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.rgb(163, 67, 225), Color.rgb(242, 48, 174), Color.argb(20, 242, 48, 174))
        ).apply { cornerRadius = dp(3).toFloat() }
        updatePlaybackNoticeBounds()
        notice.visibility = View.VISIBLE
        songIntroIcon?.visibility = View.VISIBLE
        notice.alpha = 0f
        songIntroIcon?.alpha = 0f
        notice.animate().alpha(1f).setDuration(180L).start()
        songIntroIcon?.animate()?.alpha(1f)?.setDuration(180L)?.start()
        main.postDelayed(hideSongIntroNotice, 3800L)
    }

    /**
     * 同步当前播放信息到首页和子页面的播放器覆盖层
     */
    private fun syncPlayerInfo() {
        refreshNowPlayingLabels()
        updateOrderBadge()
        if (browseMode == "ordered" || browseMode == "sang" || browseMode == "downloads") {
            renderSongList()
        }
    }

    /**
     * 实时刷新「当前播放 / 下一首」文本。
     *
     * 覆盖播放界面左上角、首页播放条、旧版播放区与顶部状态栏四处; 每次只在文本
     * 真正变化时才写 View, 因此可以安全地由 `lyricTicker` 每 500ms 调用一次。
     */
    private fun refreshNowPlayingLabels() {
        val nowNext = nowNextText()
        val combined = nowNext.combined()
        if (fullScreenSongInfo?.text?.toString() != combined) fullScreenSongInfo?.text = combined
        if (homePlayerTitle?.text?.toString() != combined) homePlayerTitle?.text = combined
        val singer = currentSong?.singer.orEmpty()
        if (homePlayerSinger?.text?.toString() != singer) homePlayerSinger?.setText(singer)
        val title = currentSong?.title ?: getString(R.string.ui_a_0549)
        if (playerSongTitle?.text?.toString() != title) playerSongTitle?.setText(title)
        if (playerSongSinger?.text?.toString() != singer) playerSongSinger?.setText(singer)
        val tip = getString(R.string.ui_p_now_next_tip, nowNext.current, nowNext.next)
        if (textCurrentSongTip?.text?.toString() != tip) textCurrentSongTip?.setText(tip)
    }

    /** 「当前播放 / 下一首」文本对(下一首按当前曲目在队列中的位置取其后一首)。 */
    private data class NowNextText(val current: String, val next: String) {
        fun combined(): String = KtvRaw.display(R.string.ui_now_next, current, next)
    }

    private fun nowNextText(): NowNextText {
        val current = currentSong?.let { song ->
            song.title.orEmpty() + song.singer?.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
        } ?: getString(R.string.ui_a_0549)
        val currentIndex = currentSong?.let { orderQueue?.indexOf(it) } ?: -1
        val next = when {
            currentIndex >= 0 -> orderQueue?.getOrNull(currentIndex + 1)
            currentSong == null -> orderQueue?.firstOrNull()
            else -> orderQueue?.firstOrNull { currentSong?.equals(it) != true }
        }?.let { song -> song.title.orEmpty() + song.singer?.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty() }
            ?: getString(R.string.ui_a_0329)
        return NowNextText(current, next)
    }

    private fun currentAndNextText(): String = nowNextText().combined()

    /** 顶栏搜索按钮 - 跳到搜索页  */
    private fun showSearchPage() {
        searchScope = "song"
        activeSearchQuery = ""
        setSearchText("")
        showSubPageShell(KtvRaw.get(R.string.ui_a_0631), getString(R.string.ui_a_0631))
        currentTabIndex = 0
        setupTabs()
        currentCategories.clear()
        currentCategories.add(KtvRaw.get(R.string.ui_a_0069))
        currentCategoryIndex = 0
        searchLanguage = KtvRaw.get(R.string.ui_a_0069)
        updateCategories()
        performSearch()
        io.execute {
            val languages = if (library.muse.isAvailable()) library.muse.languages() else emptyList()
            main.post {
                if (currentTabIndex != 0 || searchScope != "song") return@post
                currentCategories.clear()
                currentCategories.add(KtvRaw.get(R.string.ui_a_0069))
                currentCategories.addAll(languages)
                currentCategoryIndex = currentCategories.indexOf(searchLanguage).coerceAtLeast(0)
                updateCategories()
            }
        }
    }

    private fun showQuickSearchPage() {
        searchScope = "quick"
        activeSearchQuery = ""
        setSearchText("")
        showSubPageShell(KtvRaw.get(R.string.ui_a_0632), getString(R.string.ui_a_0632))
        currentTabIndex = 8
        setupTabs()
        currentCategories.clear()
        currentCategoryIndex = -1
        updateCategories()
        browseMode = "quick"
        browsePage = 0
        loadQuickSearchPage("")
    }

    /** 顶栏已点按钮  */
    private fun showOrderListPage() {
        showSubPageShell(KtvRaw.get(R.string.ui_a_0633), getString(R.string.ui_a_0633))
        currentTabIndex = 4
        setupTabs()
        loadOrderedList()
    }

    /** 首页"本地"卡片 - 显示已下载歌曲  */
    private fun showLocalPage() {
        searchScope = "local"
        activeSearchQuery = ""
        setSearchText("")
        currentTabIndex = 10
        showSubPageShell(KtvRaw.get(R.string.ui_a_0634), getString(R.string.ui_a_0634))
        setupTabs()
        keyboardArea?.visibility = View.VISIBLE
        browseMode = "local"
        browseParam = KtvRaw.get(R.string.ui_a_0069)
        browsePage = 0
        currentCategories.clear()
        currentCategories.add(KtvRaw.get(R.string.ui_a_0069))
        currentCategoryIndex = 0
        searchLanguage = KtvRaw.get(R.string.ui_a_0069)
        updateCategories()
        loadLocalCatalogPage()
        io.execute {
            val languages = if (library.muse.isAvailable()) library.muse.languages() else emptyList()
            main.post {
                if (currentTabIndex != 10 || searchScope != "local") return@post
                currentCategories.clear()
                currentCategories.add(KtvRaw.get(R.string.ui_a_0069))
                currentCategories.addAll(languages)
                currentCategoryIndex = currentCategories.indexOf(searchLanguage).coerceAtLeast(0)
                updateCategories()
            }
        }
    }

    private fun showAllSongsPage() {
        showSearchPage()
    }

    /** 底栏"猜你想唱" - 加载推荐歌曲  */
    private fun showGuessPage() {
        val root = layoutInflater.inflate(R.layout.dialog_song_recommend, null)
        val grid = root.findViewById<GridLayout>(R.id.recommend_song_grid)
        val confirm = root.findViewById<TextView>(R.id.button_recommend_confirm)
        val dialog = AlertDialog.Builder(this).setView(root).create()
        root.findViewById<View>(R.id.image_recommend_close).setOnClickListener { dialog.dismiss() }
        confirm.isEnabled = false
        confirm.alpha = 0.55f
        val selected = ArrayList<Song>()
        confirm.setOnClickListener {
            selected.forEach(::addToQueue)
            dialog.dismiss()
        }
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(
                    (resources.displayMetrics.widthPixels * 0.87f).toInt(),
                    (resources.displayMetrics.heightPixels * 0.90f).toInt(),
                )
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply { dimAmount = 0.56f }
            }
            TvFocusStyler.disablePlatformHighlightTree(root)
            root.alpha = 0f
            root.scaleX = 0.96f
            root.scaleY = 0.96f
            root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160L).start()
            root.findViewById<View>(R.id.image_recommend_close).apply {
                isFocusable = true
                requestFocus()
            }
        }
        dialog.restoreSourceFocusOnDismiss()
        dialog.show()

        val historySnapshot = sangHistory.toList()
        val queueSnapshot = orderQueue.orEmpty().toList()
        val immediateRecommendations = (historySnapshot.asSequence() + queueSnapshot.asSequence() +
            library.allSongs().asSequence())
            .distinctBy(::stableId)
            .take(10)
            .toList()
        selected.addAll(immediateRecommendations)
        populateRecommendationGrid(grid, immediateRecommendations, historySnapshot)
        confirm.text = getString(R.string.ui_p_089, immediateRecommendations.size)
        confirm.isEnabled = immediateRecommendations.isNotEmpty()
        confirm.alpha = if (immediateRecommendations.isEmpty()) 0.55f else 1f
        io.execute {
            val quickSongs = library.muse.quickSongs(30)
            val quickRecommendations = (historySnapshot.asSequence() + queueSnapshot.asSequence() +
                quickSongs.asSequence() + immediateRecommendations.asSequence())
                .distinctBy(::stableId)
                .take(10)
                .toList()
            main.post {
                if (!dialog.isShowing) return@post
                selected.clear()
                selected.addAll(quickRecommendations)
                populateRecommendationGrid(grid, quickRecommendations, historySnapshot)
                confirm.text = getString(R.string.ui_p_090, quickRecommendations.size)
                confirm.isEnabled = quickRecommendations.isNotEmpty()
                confirm.alpha = if (quickRecommendations.isEmpty()) 0.55f else 1f
            }
            val hotSongs = library.muse.hotSongs(0, 30)
            val recommendations = (historySnapshot.asSequence() + queueSnapshot.asSequence() +
                hotSongs.asSequence() + immediateRecommendations.asSequence())
                .distinctBy(::stableId)
                .take(10)
                .toList()
            main.post {
                if (!dialog.isShowing) return@post
                selected.clear()
                selected.addAll(recommendations)
                populateRecommendationGrid(grid, recommendations, historySnapshot)
                confirm.text = getString(R.string.ui_p_091, recommendations.size)
                confirm.isEnabled = recommendations.isNotEmpty()
                confirm.alpha = if (recommendations.isEmpty()) 0.55f else 1f
            }
        }
    }

    private fun populateRecommendationGrid(
        grid: GridLayout,
        songs: List<Song>,
        history: List<Song>,
    ) {
        grid.removeAllViews()
        songs.take(10).forEachIndexed { index, song ->
            val item = FrameLayout(this).apply {
                setBackgroundResource(R.drawable.ott_bg_item_not_selected)
                isClickable = true
                isFocusable = true
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(5), dp(104), dp(5))
                    addView(label(song.title, 16, Color.WHITE).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, 0, 0, 0)
                    }, LinearLayout.LayoutParams(-1, dp(25)))
                    addView(label(song.singer, 13, Color.rgb(193, 193, 193)).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, 0, 0, 0)
                    }, LinearLayout.LayoutParams(-1, dp(22)))
                }, FrameLayout.LayoutParams(-1, -1))
                val historyCount = history.count { stableId(it) == stableId(song) }
                val recommendationLabel = when {
                    historyCount > 0 -> getString(R.string.ui_p_sung_times, historyCount)
                    index == 3 -> getString(R.string.ui_a_0640)
                    index == 5 -> getString(R.string.ui_a_0641)
                    index == 7 -> getString(R.string.ui_a_0642)
                    index >= 8 -> getString(R.string.ui_a_0643)
                    else -> getString(R.string.ui_p_hot_top, index + 1)
                }
                addView(label(
                    recommendationLabel,
                    12,
                    if (historyCount > 0) Color.rgb(193, 193, 193) else Color.rgb(253, 51, 89),
                ).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(0, 0, dp(8), 0)
                }, FrameLayout.LayoutParams(dp(104), -1, Gravity.END))
                setOnClickListener {
                    addToQueue(song)
                    toast(getString(R.string.ui_p_092, song.title))
                }
            }
            installPressFeedback(item)
            grid.addView(item, GridLayout.LayoutParams().apply {
                rowSpec = GridLayout.spec(index / 2)
                columnSpec = GridLayout.spec(index % 2)
                width = (((resources.displayMetrics.widthPixels * 0.87f).toInt() - dp(28)) / 2)
                height = max(dp(48), (((resources.displayMetrics.heightPixels * 0.90f).toInt() - dp(163)) / 5) - dp(6))
                setMargins(dp(2), dp(3), dp(2), dp(3))
            })
        }
    }

    /**
     * 设置顶部 Tab 导航栏
     */
    private fun setupTabs() {
        if (tabContainer == null) return
        val tabs: Array<out String?>
        val targets: IntArray
        if (currentTabIndex >= 4 && currentTabIndex <= 6) {
            tabs = arrayOf<String>(getString(R.string.ui_a_0229), getString(R.string.ui_a_0234), getString(R.string.ui_a_0236))
            targets = intArrayOf(4, 5, 6)
        } else if (currentTabIndex == 1 || currentTabIndex == 2 || currentTabIndex == 3 ||
            currentTabIndex == 7 || currentTabIndex == 8 || currentTabIndex == 9
        ) {
            tabContainer!!.setVisibility(View.GONE)
            return
        } else {
            tabs = arrayOf<String>(getString(R.string.ui_a_0646), getString(R.string.ui_a_0198))
            targets = intArrayOf(0, 10)
        }
        tabContainer!!.setVisibility(View.VISIBLE)

        fun bindTab(tab: TextView, label: String, index: Int) {
            tab.text = label
            tab.textSize = 14f
            tab.minWidth = 0
            tab.setPadding(0, 0, 0, 0)
            tab.gravity = Gravity.CENTER
            tab.setAccessibleFocus("focus:tab:$label", getString(R.string.ui_a_0647, label))
            tab.isFocusable = true
            tab.isClickable = true
            updateTabStyle(tab, index == currentTabIndex)
            tab.setOnClickListener {
                currentTabIndex = index
                when (index) {
                    0 -> showAllSongsPage()
                    1 -> loadSingers()
                    2 -> loadLanguages()
                    3 -> loadWordCounts()
                    4 -> loadOrderedList()
                    5 -> loadSangHistory()
                    6 -> loadDownloadedList()
                    7 -> loadFavorites()
                    8 -> showSettingsPage()
                    10 -> showLocalPage()
                }
            }
        }

        val canReuse = tabContainer!!.childCount == tabs.size && tabs.indices.all { position ->
            (tabContainer!!.getChildAt(position) as? TextView)?.text?.toString() == tabs[position]
        }
        if (canReuse) {
            tabs.indices.forEach { position ->
                bindTab(
                    tabContainer!!.getChildAt(position) as TextView,
                    tabs[position].orEmpty(),
                    targets[position],
                )
            }
            return
        }

        val focusedTab = window.decorView.findFocus()?.takeIf { isDescendantOf(it, tabContainer) }
            ?.let(::captureFocusBookmark)
        tabContainer!!.removeAllViews()
        for (i in tabs.indices) {
            val index = targets[i]
            val tab = TextView(this)
            bindTab(tab, tabs[i].orEmpty(), index)
            installPressFeedback(tab)
            tabContainer!!.addView(tab, LinearLayout.LayoutParams(dp(76), -1))
        }
        restoreFocusBookmark(focusedTab)
    }

    private fun installPressFeedback(view: View) {
        TvFocusStyler.install(view)
        if (view.isClickable) {
            view.isFocusable = true
            view.isFocusableInTouchMode = false
        }
        view.setOnTouchListener { target, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN ->
                    target.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.84f).setDuration(80L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    target.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120L).start()
            }
            false
        }
    }

    private fun configureTvFocusNavigation() {
        btnTopSearch?.nextFocusDownId = R.id.home_player_container
        btnTopOrder?.nextFocusDownId = R.id.home_card_rank
        btnTopVocal?.nextFocusDownId = R.id.home_card_rank
        btnTopNext?.nextFocusDownId = R.id.home_card_rank
        btnTopPause?.nextFocusDownId = R.id.home_card_rank
        btnTopReplay?.nextFocusDownId = R.id.home_poster_container
        btnTopTone?.nextFocusDownId = R.id.home_poster_container

        homePlayerHost?.nextFocusUpId = R.id.btn_top_search
        homePlayerHost?.nextFocusDownId = R.id.home_card_regular
        homePlayerHost?.nextFocusRightId = R.id.home_card_rank

        homeCardRank?.nextFocusUpId = R.id.btn_top_pause
        homeCardRank?.nextFocusLeftId = R.id.home_player_container
        homeCardRank?.nextFocusDownId = R.id.home_card_song
        homeCardRank?.nextFocusRightId = R.id.home_poster_container
        homeCardSong?.nextFocusUpId = R.id.home_card_rank
        homeCardSong?.nextFocusDownId = R.id.home_card_singer
        homeCardSong?.nextFocusRightId = R.id.home_poster_container
        homeCardSinger?.nextFocusUpId = R.id.home_card_song
        homeCardSinger?.nextFocusDownId = R.id.home_card_local
        homeCardSinger?.nextFocusRightId = R.id.home_poster_container
        homeCardLocal?.nextFocusUpId = R.id.home_card_singer
        homeCardLocal?.nextFocusLeftId = R.id.home_card_category
        homeCardLocal?.nextFocusDownId = R.id.btn_bottom_settings
        homeCardLocal?.nextFocusRightId = R.id.home_poster_container
        homeCardRegular?.nextFocusUpId = R.id.home_player_container
        homeCardRegular?.nextFocusRightId = R.id.home_card_favorite
        homeCardRegular?.nextFocusDownId = R.id.btn_bottom_guess
        homeCardFavorite?.nextFocusUpId = R.id.btn_top_search
        homeCardFavorite?.nextFocusLeftId = R.id.home_card_regular
        homeCardFavorite?.nextFocusRightId = R.id.home_card_category
        homeCardFavorite?.nextFocusDownId = R.id.btn_bottom_guess
        homeCardCategory?.nextFocusUpId = R.id.home_card_rank
        homeCardCategory?.nextFocusLeftId = R.id.home_card_favorite
        homeCardCategory?.nextFocusRightId = R.id.home_card_local
        homeCardCategory?.nextFocusDownId = R.id.btn_bottom_settings
        homePosterView?.nextFocusUpId = R.id.btn_top_tone
        homePosterView?.nextFocusLeftId = R.id.home_card_song
        homePosterView?.nextFocusDownId = R.id.btn_bottom_exit

        btnBottomGuess?.nextFocusUpId = R.id.home_card_regular
        btnBottomGuess?.nextFocusRightId = R.id.btn_bottom_mobile_order
        btnBottomMobileOrder?.nextFocusUpId = R.id.home_card_local
        btnBottomMobileOrder?.nextFocusLeftId = R.id.btn_bottom_guess
        btnBottomMobileOrder?.nextFocusRightId = R.id.btn_bottom_settings
        btnBottomSettings?.nextFocusUpId = R.id.home_card_local
        btnBottomSettings?.nextFocusLeftId = R.id.btn_bottom_mobile_order
        btnBottomSettings?.nextFocusRightId = R.id.btn_bottom_exit
        btnBottomExit?.nextFocusUpId = R.id.home_poster_container
        btnBottomExit?.nextFocusLeftId = R.id.btn_bottom_settings
    }

    private fun clearHomeFocusTransition() {
        lastHomeFocusSource = null
        lastHomeFocusTarget = null
        lastHomeFocusReverseKey = KeyEvent.KEYCODE_UNKNOWN
    }

    private fun oppositeDpadKey(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_DPAD_LEFT -> KeyEvent.KEYCODE_DPAD_RIGHT
        KeyEvent.KEYCODE_DPAD_RIGHT -> KeyEvent.KEYCODE_DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_UP -> KeyEvent.KEYCODE_DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_DOWN -> KeyEvent.KEYCODE_DPAD_UP
        else -> KeyEvent.KEYCODE_UNKNOWN
    }

    private fun clearHomeTopFocusNavigation() {
        listOfNotNull(
            btnTopSearch, btnTopLanguage, btnTopOrder, btnTopVocal, btnTopNext, btnTopPause, btnTopReplay, btnTopTone,
        ).forEach { it.nextFocusDownId = View.NO_ID }
    }

    /**
     * 更新 Tab 样式(选中/未选中)
     */
    private fun updateTabStyle(tab: TextView, selected: Boolean) {
        val normalText = if (selected) Color.rgb(253, 51, 89) else Color.argb(180, 241, 241, 241)
        tab.setTextColor(ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_focused),
                intArrayOf(android.R.attr.state_pressed),
                intArrayOf(),
            ),
            intArrayOf(Color.WHITE, Color.WHITE, normalText),
        ))
        val normal = GradientDrawable().apply {
            setColor(if (selected) Color.argb(30, 253, 51, 89) else Color.TRANSPARENT)
            cornerRadius = dp(4).toFloat()
        }
        val focused = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.rgb(232, 49, 111), Color.rgb(255, 154, 48)),
        ).apply {
            cornerRadius = dp(4).toFloat()
            setStroke(dp(1), Color.argb(150, 255, 255, 255))
        }
        tab.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), normal)
        }
    }

    /**
     * 构建虚拟键盘 (拼音首字母 + 数字 + 功能键)
     * 4 行字母 + 1 行数字 = 完整 26 字母键盘
     */
    private fun buildKeyboard() {
        val grid = findViewById<LinearLayout?>(R.id.keyboard_grid)
        if (grid == null) return
        renderKeyboard("abc")
        // 绑定输入模式切换
        val keyAbc = findViewById<TextView?>(R.id.key_abc)
        val key123 = findViewById<TextView?>(R.id.key_123)
        val keySpace = findViewById<TextView?>(R.id.key_space)
        val keyBackspace = findViewById<TextView?>(R.id.key_backspace)
        val keyClear = findViewById<TextView?>(R.id.key_clear)
        if (keyAbc != null) keyAbc.setOnClickListener(View.OnClickListener { v: View? ->
            hideSystemKeyboard()
            keyAbc.setBackgroundResource(R.drawable.bg_key_mode)
            keyAbc.setTextColor(Color.rgb(0x27, 0xCC, 0xA4))
            if (key123 != null) {
                key123.setBackgroundResource(R.drawable.bg_key_mode_dim)
                key123.setTextColor(Color.argb(153, 255, 255, 255))
            }
            renderKeyboard("abc")
        })
        if (key123 != null) key123.setOnClickListener(View.OnClickListener { v: View? ->
            showSystemKeyboard()
        })
        if (keySpace != null) keySpace.setOnClickListener(View.OnClickListener { v: View? ->
            showSystemKeyboard()
        })
        if (keyBackspace != null) keyBackspace.setOnClickListener(View.OnClickListener { v: View? ->
            if (searchInput == null) return@OnClickListener
            val s = searchInput!!.getText().toString()
            if (!s.isEmpty()) {
                searchInput!!.setText(s.substring(0, s.length - 1))
                searchInput!!.setSelection(searchInput!!.getText().length)
                scheduleKeyboardSearch()
            }
        })
        if (keyClear != null) keyClear.setOnClickListener(View.OnClickListener { v: View? ->
            if (searchInput != null) {
                searchInput!!.setText("")
                scheduleKeyboardSearch()
            }
        })
    }

    /**
     * 切换键盘模式(ABC/123)并重建键盘
     * @param mode "abc" 或 "123"
     */
    private var keyboardMode: String? = "abc"
    private fun renderKeyboard(mode: String?) {
        keyboardMode = mode
        val grid = findViewById<LinearLayout?>(R.id.keyboard_grid)
        if (grid == null) return
        grid.removeAllViews()
        val rows: Array<Array<String?>?>?
        if ("123" == mode) {
            rows = arrayOf<Array<String?>?>(
                arrayOf<String?>("1", "2", "3", "4", "5", "6", "7"),
                arrayOf<String?>("8", "9", "0", "", "", "", ""),
                arrayOf<String?>("", "", "", "", "", "", ""),
                arrayOf<String?>("ABC", "", "", "", "", "", ""),
            )
        } else {
            rows = arrayOf<Array<String?>?>(
                arrayOf<String?>("A", "B", "C", "D", "E", "F", "G"),
                arrayOf<String?>("H", "I", "J", "K", "L", "M", "N"),
                arrayOf<String?>("O", "P", "Q", "R", "S", "T", "U"),
                arrayOf<String?>("V", "W", "X", "Y", "Z", "123", ""),
            )
        }
        for (row in rows) {
            val rowLayout = LinearLayout(this)
            rowLayout.setOrientation(LinearLayout.HORIZONTAL)
            rowLayout.setGravity(Gravity.CENTER)
            rowLayout.setWeightSum(row!!.size.toFloat())
            val rowLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            rowLp.bottomMargin = dp(2)
            grid.addView(rowLayout, rowLp)
            for (key in row) {
                val btn = TextView(this)
                btn.setText(key)
                btn.setTextSize(15f)
                btn.setTextColor(Color.rgb(241, 241, 241))
                btn.setGravity(Gravity.CENTER)
                btn.setBackgroundColor(Color.TRANSPARENT)
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                lp.leftMargin = 2
                lp.rightMargin = 2
                btn.setLayoutParams(lp)
                btn.visibility = if (key.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
                btn.setOnClickListener {
                    when (key) {
                        "123" -> renderKeyboard("123")
                        "ABC" -> renderKeyboard("abc")
                        else -> appendToSearch(key)
                    }
                }
                btn.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> btn.setBackgroundResource(R.drawable.bg_key_char_pressed)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> btn.setBackgroundColor(Color.TRANSPARENT)
                    }
                    false
                }
                rowLayout.addView(btn)
            }
        }
    }

    /**
     * 向搜索框追加文本
     */
    private fun appendToSearch(key: String?) {
        if (searchInput == null) return
        val s = searchInput!!.getText().toString()
        searchInput!!.setText(s + key)
        searchInput!!.setSelection(searchInput!!.getText().length)
        scheduleKeyboardSearch()
    }

    private fun scheduleKeyboardSearch() {
        main.removeCallbacks(keyboardSearchRunnable)
        main.postDelayed(keyboardSearchRunnable, 350L)
    }

    private fun setSearchText(value: String) {
        val input = searchInput ?: return
        main.removeCallbacks(keyboardSearchRunnable)
        suppressSearchWatcher = true
        input.setText(value)
        input.setSelection(input.text.length)
        suppressSearchWatcher = false
    }

    private fun clearSearchForFilterChange() {
        activeSearchQuery = ""
        setSearchText("")
    }

    private fun showSystemKeyboard() {
        val input = searchInput ?: return
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideSystemKeyboard() {
        val input = searchInput ?: return
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    /**
     * 加载热门歌曲列表
     */
    private fun loadHotSongs(preferredRankName: String? = null) {
        searchScope = "none"
        activeSearchQuery = ""
        setSearchText("")
        currentTabIndex = 9
        showSubPageShell(KtvRaw.get(R.string.ui_a_0648), getString(R.string.ui_a_0648))
        setupTabs()
        if (keyboardArea != null) keyboardArea!!.setVisibility(View.GONE)
        rankCategoryGrid?.visibility = View.VISIBLE
        listTitle!!.setText(getString(R.string.ui_a_0649))
        browseMode = "rank"
        browseParam = ""
        browsePage = 0
        currentCategories.clear()
        rankPlaylistIds.clear()
        currentCategoryIndex = 0
        updateCategories()
        if (!library.muse.isAvailable()) {
            catalogRefreshPending = true
            busy(true, getString(R.string.ui_a_0650))
            return
        }
        io.execute {
            val playlists = library.muse.rankPlaylists()
            main.post {
                if (currentTabIndex != 9 || browseMode != "rank") return@post
                currentCategories.clear()
                rankPlaylistIds.clear()
                playlists.forEach { playlist ->
                    rankPlaylistIds.add(playlist[0].orEmpty())
                    currentCategories.add(playlist[1].orEmpty())
                }
                // 幻灯片入口可指定首选榜单名(如"新歌榜"); 未命中时沿用默认热歌榜逻辑。
                val preferred = preferredRankName?.trim().orEmpty()
                currentCategoryIndex = if (preferred.isNotEmpty()) {
                    playlists.indexOfFirst { (it[1] ?: "").contains(preferred) }
                        .takeIf { it >= 0 }
                        ?: playlists.indexOfFirst { it[2] == "2" || it[1] == "热歌榜" }
                } else {
                    playlists.indexOfFirst { it[2] == "2" || it[1] == "热歌榜" }
                }.takeIf { it >= 0 } ?: 0
                browseParam = rankPlaylistIds.getOrNull(currentCategoryIndex).orEmpty()
                val rankTitle = currentCategories.getOrNull(currentCategoryIndex)
        listTitle!!.text = if (rankTitle.isNullOrEmpty()) getString(R.string.ui_a_0649) else displayCategory(rankTitle)
                updateRankCategoryGrid()
                subCategoryScroll?.visibility = View.GONE
                loadBrowsePage()
            }
        }
    }

    /**
     * 按榜单名挑专属主题图, 未命中回退默认奖杯。
     * 以下是曲库返回的固定中文分类键，仅用于图标匹配，不直接显示。
     * 界面显示统一走 displayCategory() / KtvRaw.displayPlaylistName()。
     */
    private fun rankIconRes(title: String): Int = when {
        title.contains("劲歌") || title.contains("热舞") || title.contains("舞") -> R.drawable.img_rank_dance
        title.contains("新歌") -> R.drawable.img_rank_new
        title.contains("热歌") -> R.drawable.img_rank_hot
        title.contains("飙升") -> R.drawable.img_rank_rise
        title.contains("抖音") -> R.drawable.img_rank_douyin
        title.contains("内地") || title.contains("大陆") -> R.drawable.img_rank_mainland
        title.contains("香港") || title.contains("粤语") -> R.drawable.img_rank_hk
        title.contains("台湾") || title.contains("闽南") -> R.drawable.img_rank_tw
        title.contains("流行") || title.contains("指数") -> R.drawable.img_rank_trend
        else -> R.drawable.ic_default_rank_avatar
    }

    private fun updateRankCategoryGrid() {
        val grid = rankCategoryGrid ?: return
        // 内容区让出顶栏高度后，榜单格子的行高会变紧；必须按真实行高缩放
        // 图标/字号，否则"图标在上文字在下"的单元格会把文字整行裁掉。
        if (grid.width <= 0 || grid.height <= 0) {
            grid.post { if (rankCategoryGrid === grid) updateRankCategoryGrid() }
            return
        }
        grid.removeAllViews()
        val rowH = (grid.height - dp(18)) / 3
        val iconSize = when {
            rowH >= dp(64) -> dp(42)
            rowH >= dp(52) -> dp(30)
            rowH > 0 -> dp(24)
            else -> dp(if (compactUi) 30 else 42)
        }
        val labelSp = if (iconSize >= dp(42)) 14f else 12f
        currentCategories.take(9).forEachIndexed { index, title ->
            val tile = TextView(this).apply {
                text = displayCategory(title)
                textSize = labelSp
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setBackgroundResource(
                    if (index == currentCategoryIndex) R.drawable.ott_bg_rank_item_selected
                    else R.drawable.ott_bg_rank_item_not_selected
                )
                val icon = AppCompatResources.getDrawable(context, rankIconRes(title))?.mutate()
                icon?.setBounds(0, 0, iconSize, iconSize)
                setCompoundDrawables(null, icon, null, null)
                compoundDrawablePadding = dp(1)
                setOnClickListener {
                    if (index == currentCategoryIndex) return@setOnClickListener
                    currentCategoryIndex = index
                    browseMode = "rank"
                    browseParam = rankPlaylistIds.getOrNull(index).orEmpty()
                    browsePage = 0
                    listTitle?.text = displayCategory(title)
                    updateRankCategoryGrid()
                    loadBrowsePage()
                }
            }
            installPressFeedback(tile)
            val gridParams = GridLayout.LayoutParams().apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                gridParams.rowSpec = GridLayout.spec(index / 3, 1f)
                gridParams.columnSpec = GridLayout.spec(index % 3, 1f)
                gridParams.width = 0
                gridParams.height = 0
            } else {
                // Android 4.4 的 GridLayout 没有带 weight 的 spec(API 21 引入),
                // 改为按容器尺寸三等分算出每格固定宽高(减掉 3 列各自的左右 margin)。
                gridParams.rowSpec = GridLayout.spec(index / 3)
                gridParams.columnSpec = GridLayout.spec(index % 3)
                gridParams.width = if (grid.width > 0) (grid.width - dp(18)) / 3 else dp(96)
                gridParams.height = if (grid.height > 0) (grid.height - dp(18)) / 3 else dp(96)
            }
            grid.addView(tile, gridParams)
        }
    }

    private fun loadCategoryPlaylists() {
        searchScope = "playlist"
        activeSearchQuery = ""
        setSearchText("")
        currentTabIndex = 2
        showSubPageShell(KtvRaw.get(R.string.ui_a_0652), getString(R.string.ui_a_0652))
        setupTabs()
        currentCategories.clear()
        currentCategoryIndex = -1
        updateCategories()
        browseMode = "category_list"
        browseParam = ""
        browsePage = 0
        loadCategoryPlaylistPage("")
    }

    private fun returnToCategoryPlaylists() {
        searchScope = "playlist"
        activeSearchQuery = categoryParentQuery
        setSearchText(categoryParentQuery)
        currentTabIndex = 2
        showSubPageShell(KtvRaw.get(R.string.ui_a_0652), getString(R.string.ui_a_0652))
        setupTabs()
        currentCategories.clear()
        currentCategoryIndex = -1
        updateCategories()
        browseMode = "category_list"
        browseParam = categoryParentQuery
        browsePage = categoryParentPage
        loadCategoryPlaylistPage(categoryParentQuery)
    }

    /**
     * 幻灯片(分类入口图)点击: 按当前轮播页跳转, 与金调 KTV 行为一致。
     * 0=新歌榜(榜单页选中新歌榜) 1=戏曲(剧种歌单网格) 2=广场舞(聚合歌单卡片)。
     */
    private fun openHomeBannerTarget(index: Int) {
        when (index % homeBanners.size) {
            0 -> loadHotSongs("新歌榜")
            1 -> loadTagGroupPage(KtvRaw.get(R.string.ui_a_0728))
            else -> showKeywordPlaylistPage(KtvRaw.get(R.string.ui_a_0729))
        }
    }

    /**
     * 关键词虚拟歌单页(金调"广场舞"同款): 网格展示 1 个聚合歌单卡片,
     * 点击卡片进入该关键词的歌曲聚合列表。
     */
    private fun showKeywordPlaylistPage(keyword: String) {
        if (!library.muse.isAvailable()) {
            catalogRefreshPending = true
            busy(true, getString(R.string.ui_a_0650))
            return
        }
        searchScope = "none"
        activeSearchQuery = ""
        setSearchText("")
        currentTabIndex = 2
        showSubPageShell(
            getString(R.string.ui_header_home) + " / $keyword",
            getString(R.string.ui_header_home) + " / " + displayCategory(keyword)
        )
        setupTabs()
        currentCategories.clear()
        currentCategoryIndex = -1
        updateCategories()
        keyboardArea?.visibility = View.GONE
        rankCategoryGrid?.visibility = View.GONE
        subCategoryScroll?.visibility = View.GONE
        browseMode = "keyword_playlist"
        browseParam = keyword
        browsePage = 0
        browseTotalPages = 1
        browseTotalCount = 1
        val virtualCard = arrayOf<String?>("keyword:$keyword", keyword, null, null)
        visiblePlaylists.clear()
        visiblePlaylists.add(virtualCard)
        songList?.apply {
            dividerHeight = 0
            // 虚拟歌单卡片封面: 用对应幻灯片主题图(0=新歌榜 1=戏曲 2=广场舞), 与金调视觉一致。
            val cover = when (keyword) {
                "新歌榜" -> R.drawable.home_banner_0
                "戏曲" -> R.drawable.home_banner_1
                else -> R.drawable.home_banner_2
            }
            adapter = PlaylistGridAdapter(this@MainActivity, listOf(virtualCard), cover) { _ ->
                loadKeywordSongs(keyword)
            }
        }
        updatePageInfo()
    }

    /**
     * 标签组歌单网格页(金调同款): 如"戏曲"组 → 黄梅戏/京剧/粤剧…剧种歌单。
     * 组不存在或为空时回退到按关键词搜索分类歌单, 保证永远有可用落点。
     */
    private fun loadTagGroupPage(groupName: String) {
        if (!library.muse.isAvailable()) {
            catalogRefreshPending = true
            busy(true, getString(R.string.ui_a_0650))
            return
        }
        if (library.muse.tagGroupPlaylistCount(groupName) <= 0) {
            openCategoryPlaylistByKeyword(groupName)
            return
        }
        searchScope = "none"
        activeSearchQuery = ""
        setSearchText("")
        currentTabIndex = 2
        showSubPageShell(
            getString(R.string.ui_header_home) + " / $groupName",
            getString(R.string.ui_header_home) + " / " + displayCategory(groupName)
        )
        setupTabs()
        currentCategories.clear()
        currentCategoryIndex = -1
        updateCategories()
        keyboardArea?.visibility = View.GONE
        rankCategoryGrid?.visibility = View.GONE
        subCategoryScroll?.visibility = View.GONE
        browseMode = "tag_group"
        browseParam = groupName
        val requestVersion = ++browseRequestVersion
        val requestedPage = browsePage
        val pageSize = 12
        io.execute {
            val playlists = library.muse.tagGroupPlaylists(groupName, requestedPage * pageSize, pageSize)
            val total = library.muse.tagGroupPlaylistCount(groupName)
            main.post {
                if (requestVersion != browseRequestVersion || browseMode != "tag_group" || browsePage != requestedPage
                ) return@post
                browseTotalCount = total
                browseTotalPages = (total + pageSize - 1) / pageSize
                visiblePlaylists.clear()
                visiblePlaylists.addAll(playlists)
                songList?.apply {
                    dividerHeight = 0
                    adapter = PlaylistGridAdapter(this@MainActivity, playlists) { playlist ->
                        openCategoryPlaylist(playlist)
                    }
                }
                updatePageInfo()
            }
        }
    }

    /** 按歌名关键词搜索歌曲(金调"广场舞"同款行为): 聚合曲库内全部同名主题歌曲。 */
    private fun loadKeywordSongs(keyword: String) {
        searchScope = "none"
        activeSearchQuery = ""
        setSearchText("")
        currentTabIndex = 9
        showSubPageShell(
            getString(R.string.ui_header_home) + " / $keyword",
            getString(R.string.ui_header_home) + " / " + displayCategory(keyword)
        )
        setupTabs()
        currentCategories.clear()
        currentCategoryIndex = -1
        updateCategories()
        keyboardArea?.visibility = View.GONE
        rankCategoryGrid?.visibility = View.GONE
        subCategoryScroll?.visibility = View.GONE
        browseMode = "search"
        browseParam = keyword
        browsePage = 0
        searchLanguage = KtvRaw.get(R.string.ui_a_0069)
        listTitle?.text = keyword
        loadBrowsePage()
    }

    /**
     * 按关键词打开分类歌单: 命中即直接进入首个歌单的歌曲列表;
     * 未命中(曲库未同步等)则回退到分类列表页, 保证永远有可用落点。
     */
    private fun openCategoryPlaylistByKeyword(keyword: String) {
        if (!library.muse.isAvailable()) {
            catalogRefreshPending = true
            busy(true, getString(R.string.ui_a_0650))
            return
        }
        io.execute {
            val playlists = library.muse.categoryPlaylists(keyword, 0, 1)
            main.post {
                val first = playlists.firstOrNull()
                if (first == null) loadCategoryPlaylists() else openCategoryPlaylist(first)
            }
        }
    }

    private fun loadCategoryPlaylistPage(query: String = activeSearchQuery) {
        browseMode = "category_list"
        browseParam = query
        val requestVersion = ++browseRequestVersion
        val requestedPage = browsePage
        val requestedQuery = query.trim()
        val pageSize = 12
        io.execute {
            val playlists = library.muse.categoryPlaylists(requestedQuery, requestedPage * pageSize, pageSize)
            val total = library.muse.categoryPlaylistCount(requestedQuery)
            main.post {
                if (requestVersion != browseRequestVersion || browseMode != "category_list" ||
                    browsePage != requestedPage || activeSearchQuery != requestedQuery
                ) return@post
                browseTotalCount = total
                browseTotalPages = (total + pageSize - 1) / pageSize
                visiblePlaylists.clear()
                visiblePlaylists.addAll(playlists)
                songList?.apply {
                    dividerHeight = 0
                    adapter = PlaylistGridAdapter(this@MainActivity, playlists) { playlist ->
                        openCategoryPlaylist(playlist)
                    }
                }
                updatePageInfo()
            }
        }
    }

    private fun openCategoryPlaylist(playlist: Array<String?>) {
        categoryParentPage = browsePage
        categoryParentQuery = activeSearchQuery
        clearSearchForFilterChange()
        val playlistName = playlist.getOrNull(1).orEmpty()
        showSubPageShell(
            KtvRaw.get(R.string.ui_a_0653, playlistName),
            getString(R.string.ui_a_0653, KtvRaw.displayPlaylistName(playlistName))
        )
        currentTabIndex = 2
        setupTabs()
        searchScope = "playlist_songs"
        searchContextId = playlist.getOrNull(0).orEmpty()
        currentCategories.clear()
        currentCategoryIndex = -1
        updateCategories()
        browseMode = "playlist_id"
        browseParam = playlist.getOrNull(0).orEmpty()
        browsePage = 0
        loadBrowsePage()
    }

    private fun loadQuickSearchPage(query: String = activeSearchQuery) {
        browseMode = "quick"
        browseParam = query
        val requestVersion = ++browseRequestVersion
        val requestedPage = browsePage
        val requestedQuery = query.trim()
        io.execute {
            val songs = if (requestedQuery.isEmpty()) {
                library.muse.hotSongs(requestedPage * 6, 6)
            } else {
                library.muse.searchSongs(requestedQuery, requestedPage * 6, 6)
            }
            val singers = if (requestedQuery.isEmpty()) {
                library.muse.singers("", "", requestedPage * 4, 4)
            } else {
                library.muse.searchSingers(requestedQuery, "", "", requestedPage * 4, 4)
            }
            val total = if (requestedQuery.isEmpty()) {
                library.muse.songCount()
            } else {
                library.muse.searchSongCount(requestedQuery)
            }
            main.post {
                if (requestVersion != browseRequestVersion || browseMode != "quick" ||
                    browsePage != requestedPage || activeSearchQuery != requestedQuery
                ) return@post
                browseTotalCount = total
                browseTotalPages = (total + 5) / 6
                visibleSongs.clear()
                visibleSongs.addAll(songs)
                visibleSingers.clear()
                visibleSingers.addAll(singers)
                songList?.apply {
                    dividerHeight = 0
                    adapter = QuickSearchAdapter(
                        this@MainActivity,
                        singers,
                        songs,
                        onSingerClick = { singer -> loadSingerSongs(singer.getOrNull(0), singer.getOrNull(1)) },
                        onSongClick = { song -> addToQueue(song) },
                        onSongMore = { song -> showSongActions(song) },
                    )
                }
                updatePageInfo()
            }
        }
    }

    /**
     * 加载语种列表
     */
    private fun loadLanguages() {
        currentTabIndex = 2
        showSubPageShell(KtvRaw.get(R.string.ui_a_0654), getString(R.string.ui_a_0654))
        setupTabs()
        listTitle!!.setText(getString(R.string.ui_a_0150))
        if (library.muse.isAvailable()) {
            currentCategories.clear()
            currentCategories.addAll(library.muse.languages())
            currentCategoryIndex = 0
            updateCategories()
            if (!currentCategories.isEmpty()) {
                browseMode = "language"
                browseParam = currentCategories.get(0)
                browsePage = 0
                loadBrowsePage()
            }
        }
    }

    /**
     * 加载字数分类
     */
    private fun loadWordCounts() {
        currentTabIndex = 3
        showSubPageShell(KtvRaw.get(R.string.ui_a_0655), getString(R.string.ui_a_0655))
        setupTabs()
        listTitle!!.setText(getString(R.string.ui_a_0152))
        currentCategories.clear()
        currentCategories.add(KtvRaw.get(R.string.ui_a_0656))
        currentCategories.add(KtvRaw.get(R.string.ui_a_0657))
        currentCategories.add(KtvRaw.get(R.string.ui_a_0658))
        currentCategories.add(KtvRaw.get(R.string.ui_a_0659))
        currentCategories.add(KtvRaw.get(R.string.ui_a_0660))
        currentCategories.add(KtvRaw.get(R.string.ui_a_0661))
        currentCategoryIndex = 0
        updateCategories()
        browseMode = "wordcount"
        browseParam = "4" // 默认显示4字歌曲
        browsePage = 0
        loadBrowsePage()
    }

    /**
     * 加载歌手列表
     */
    private fun loadSingers() {
        searchScope = "singer"
        activeSearchQuery = ""
        setSearchText("")
        currentTabIndex = 1
        showSubPageShell(KtvRaw.get(R.string.ui_a_0662), getString(R.string.ui_a_0662))
        setupTabs()
        listTitle!!.setText(getString(R.string.ui_a_0148))
        currentCategories.clear()
        currentCategories.add(KtvRaw.get(R.string.ui_a_0069))
        currentCategories.add(KtvRaw.get(R.string.ui_a_0663))
        currentCategories.add(KtvRaw.get(R.string.ui_a_0664))
        currentCategories.add(KtvRaw.get(R.string.ui_a_0665))
        currentCategories.add(KtvRaw.get(R.string.ui_a_0666))
        currentCategories.add(KtvRaw.get(R.string.ui_a_0667))
        currentCategories.add(KtvRaw.get(R.string.ui_a_0668))
        currentCategoryIndex = 0
        updateCategories()
        browsePage = 0
        loadSingerList(KtvRaw.get(R.string.ui_a_0069))
    }

    private fun loadSingerList(type: String?, query: String = activeSearchQuery) {
        browseMode = "singer_list"
        browseParam = type
        if (!library.muse.isAvailable()) {
            catalogRefreshPending = true
            busy(true, getString(R.string.ui_a_0650))
            return
        }
        val requestVersion = ++browseRequestVersion
        val requestedPage = browsePage
        val requestedType = type.orEmpty()
        val requestedQuery = query.trim()
        val pageSize = 8
        val offset = requestedPage * pageSize
        val (areaFilter, typeFilter) = singerFilter(type)
        io.execute(Runnable {
            val singers: MutableList<Array<String?>> = if (requestedQuery.isEmpty()) {
                library.muse.singers(areaFilter, typeFilter, offset, pageSize)
            } else {
                library.muse.searchSingers(requestedQuery, areaFilter, typeFilter, offset, pageSize)
            }
            val total = if (requestedQuery.isEmpty()) {
                library.muse.singerCount(areaFilter, typeFilter)
            } else {
                library.muse.searchSingerCount(requestedQuery, areaFilter, typeFilter)
            }
            main.post(Runnable {
                if (requestVersion != browseRequestVersion || browseMode != "singer_list" ||
                    browseParam.orEmpty() != requestedType || browsePage != requestedPage ||
                    activeSearchQuery != requestedQuery
                ) return@Runnable
                browseTotalCount = total
                browseTotalPages = (total + pageSize - 1) / pageSize
                visibleSingers.clear()
                visibleSingers.addAll(singers)
                songList!!.dividerHeight = 0
                songList!!.adapter = SingerGridAdapter(this, singers) { singer ->
                    loadSingerSongs(singer.getOrNull(0), singer.getOrNull(1))
                }
                updatePageInfo()
            })
        })
    }

    private fun singerFilter(category: String?): Pair<String, String> = when (category) {
        "大陆男" -> "大陆" to "男"
        "大陆女" -> "大陆" to "女"
        "港台男" -> "港台" to "男"
        "港台女" -> "港台" to "女"
        "中国组合" -> "中国" to "组合"
        "外国组合" -> "外国" to "组合"
        else -> "" to ""
    }

    private fun loadSingerSongs(singerId: String?, singerName: String?) {
        searchScope = "singer_songs"
        searchContextId = singerId.orEmpty()
        activeSearchQuery = ""
        setSearchText("")
        showSubPageShell(KtvRaw.get(R.string.ui_a_0669) + singerName, getString(R.string.ui_a_0669) + singerName)
        currentTabIndex = 1
        setupTabs()
        browseMode = "singer_id"
        browseParam = singerId
        browsePage = 0
        listTitle!!.setText(singerName)
        loadBrowsePage()
    }

    /**
     * 加载收藏歌曲
     */
    /**
     * 加载歌单
     */
    private fun loadPlaylists() {
        showSubPageShell(KtvRaw.get(R.string.ui_a_0670), getString(R.string.ui_a_0670))
        listTitle!!.setText(getString(R.string.ui_a_0157))
        currentCategories.clear()
        // 获取歌单列表(从数据库 playlists 表)
        val playlistData: MutableList<Array<String?>> = library.muse.playlists(0, 100)
        if (playlistData.isEmpty()) {
            currentCategories.add(KtvRaw.get(R.string.ui_a_0671))
        } else {
            for (p in playlistData) {
                currentCategories.add(p[1].orEmpty()) // p[1] 是歌单名称
            }
        }
        currentCategoryIndex = 0
        updateCategories()
        if (!currentCategories.isEmpty() && currentCategories.get(0) != "暂无歌单") {
            browseMode = "playlist"
            browseParam = currentCategories.get(0)
            browsePage = 0
            loadBrowsePage()
        }
    }

    /**
     * 加载已点歌曲列表 (Tab 4)
     */
    private fun loadOrderedList() {
        currentTabIndex = 4
        showSubPageShell(KtvRaw.get(R.string.ui_a_0633), getString(R.string.ui_a_0633))
        setupTabs()
        if (keyboardArea != null) keyboardArea!!.setVisibility(View.GONE)
        listTitle!!.setText(getString(R.string.ui_a_0672) + orderQueue!!.size + ")")
        currentCategories.clear()
        currentCategoryIndex = 0
        browseMode = "ordered"
        browseParam = ""
        browsePage = 0
        // 直接更新歌曲列表
        visibleSongs.clear()
        visibleSongs.addAll(orderQueue)
        updateCategories()
        renderSongList()
        updateOrderBadge()
    }

    /**
     * 加载下载列表 (Tab 5)
     */
    private fun loadDownloadedList() {
        currentTabIndex = 6
        showSubPageShell(KtvRaw.get(R.string.ui_a_0673), getString(R.string.ui_a_0673))
        setupTabs()
        listTitle!!.setText(getString(R.string.ui_a_0161))
        currentCategories.clear()
        currentCategoryIndex = 0
        browseMode = "downloads"
        browseParam = ""
        browsePage = 0
        // The original download page is a task queue. Completed files move to the local-song page.
        visibleSongs.clear()
        for (task in downloads) {
            visibleSongs.add(task.song)
        }
        if (visibleSongs.isEmpty()) {
            toast(getString(R.string.ui_a_0674))
        }
        updateCategories()
        renderSongList()
    }

    private fun loadLocalCatalogPage() {
        browseMode = "local"
        val language = searchLanguage.ifEmpty { browseParam.orEmpty() }
        val query = activeSearchQuery.trim().uppercase(Locale.ROOT)
        val requestedPage = browsePage
        val requestVersion = ++browseRequestVersion
        val pageSize = MuseDatabase.PAGE_SIZE
        io.execute {
            val localSongs = localSongInventory()
            downloads.asSequence().map { it.song }
                .plus(library.allSongs().asSequence())
                .forEach { song ->
                if (song.hasLocalFile() || isDownloaded(song)) {
                    localSongs.getOrPut(stableId(song)) { song }
                }
            }
            val filtered = localSongs.values.filter {
                val languageMatches = language.isEmpty() || language == "全部" || it.language == language
                val text = listOf(it.title, it.singer, it.pinyin, it.pinyinFull)
                    .joinToString(" ").uppercase(Locale.ROOT)
                languageMatches && (query.isEmpty() || query in text)
            }
            val from = (requestedPage * pageSize).coerceAtMost(filtered.size)
            val page = filtered.drop(from).take(pageSize)
            main.post {
                if (requestVersion != browseRequestVersion || browseMode != "local" ||
                    browsePage != requestedPage || activeSearchQuery.uppercase(Locale.ROOT) != query ||
                    searchLanguage != language
                ) return@post
                browseTotalCount = filtered.size
                browseTotalPages = (filtered.size + pageSize - 1) / pageSize
                visibleSongs.clear()
                visibleSongs.addAll(page)
                renderSongList()
                updatePageInfo()
            }
        }
    }

    /**
     * 加载已唱历史 (Tab 5)
     */
    private fun loadSangHistory() {
        currentTabIndex = 5
        showSubPageShell(KtvRaw.get(R.string.ui_a_0675), getString(R.string.ui_a_0675))
        setupTabs()
        if (keyboardArea != null) keyboardArea!!.setVisibility(View.GONE)
        listTitle!!.setText(getString(R.string.ui_a_0676) + sangHistory.size + ")")
        currentCategories.clear()
        currentCategoryIndex = 0
        browseMode = "sang"
        browseParam = ""
        browsePage = 0
        visibleSongs.clear()
        visibleSongs.addAll(sangHistory)
        updateCategories()
        renderSongList()
    }

    /**
     * 加载收藏列表 (Tab 7) - 从 KtvStore 收藏和数据库查询
     */
    private fun loadFavorites() {
        currentTabIndex = 7
        showSubPageShell(KtvRaw.get(R.string.ui_a_0677), getString(R.string.ui_a_0677))
        setupTabs()
        listTitle!!.setText(getString(R.string.ui_a_0094))
        currentCategories.clear()
        currentCategoryIndex = 0
        browseMode = "favorites"
        browseParam = ""
        browsePage = 0
        visibleSongs.clear()
        if (!library.muse.isAvailable()) {
            catalogRefreshPending = true
            busy(true, getString(R.string.ui_a_0650))
            return
        }
        // 从数据库查询收藏歌曲
        io.execute(Runnable {
            val result: MutableList<Song> = ArrayList<Song>()
            for (songId in store.favoriteIds) {
                val song = library.muse.songById(songId)
                    ?: library.allSongs().firstOrNull { stableId(it) == songId }
                if (song != null) result.add(song)
            }
            main.post(Runnable {
                visibleSongs.clear()
                visibleSongs.addAll(result)
                updateCategories()
                renderSongList()
                if (visibleSongs.isEmpty()) toast(getString(R.string.ui_a_0678))
            })
        })
    }

    /**
     * 显示设置对话框 (旧版 Tab 8, 现已改为弹窗)
     */
    private fun showSettingsPage(requestInitialFocus: Boolean = true) {
        currentTabIndex = 8
        showSubPageShell(getString(R.string.ui_header_home) + " / " + getString(R.string.ui_set_action_settings))
        browseMode = "settings"
        visibleSongs.clear()
        currentCategories.clear()
        updateCategories()
        if (keyboardArea != null) keyboardArea!!.setVisibility(View.GONE)
        if (tabContainer != null) tabContainer!!.setVisibility(View.GONE)
        if (subCategoryScroll != null) subCategoryScroll!!.setVisibility(View.GONE)
        if (paginationBar != null) paginationBar!!.setVisibility(View.GONE)
        browsePagination?.visibility = View.GONE

        val entries = listOf(
            SettingsEntry(R.drawable.ott_ic_data_setting, getString(R.string.ui_sec_data), getString(R.string.ui_set_data_sub)) { showSettingsSection(1) },
            SettingsEntry(R.drawable.ott_ic_play, getString(R.string.ui_sec_playback), getString(R.string.ui_set_playback_sub)) { showSettingsSection(2) },
            SettingsEntry(R.drawable.ott_ic_sound, getString(R.string.ui_a_0740)) { showSoundSettingsDialog() },
            SettingsEntry(
                R.drawable.ott_ic_data_upgrade,
                getString(R.string.ui_a_0134),
                getString(R.string.ui_p_101, BuildConfig.VERSION_NAME),
                getString(R.string.ui_set_action_update),
            ) { checkAppUpdate(true) },
            SettingsEntry(R.drawable.ott_ic_about_us, getString(R.string.ui_a_0297), actionText = getString(R.string.ui_set_action_view)) { showAboutDialog() },
        )
        activeSettingsEntries = entries
        songList!!.setDivider(ColorDrawable(Color.TRANSPARENT))
        songList!!.setDividerHeight(dp(12))
        songList!!.setBackgroundColor(Color.TRANSPARENT)
        val settingsAdapter = SettingsListAdapter(this, entries, R.id.btn_back)
        songList!!.adapter = settingsAdapter
        btnBack?.nextFocusDownId = settingsAdapter.firstActionId
        if (requestInitialFocus) settingsAdapter.requestInitialFocus(songList!!)
    }

    private fun openSettingsItem(position: Int) {
        activeSettingsEntries.getOrNull(position)?.action?.invoke()
    }

    private fun showSettingsSection(section: Int, requestInitialFocus: Boolean = true) {
        val names = arrayOf(getString(R.string.ui_a_0177), getString(R.string.ui_sec_data), getString(R.string.ui_sec_playback), getString(R.string.ui_a_0740), getString(R.string.ui_sec_display))
        currentTabIndex = 8
        showSubPageShell(getString(R.string.ui_header_home) + " / " + getString(R.string.ui_set_action_settings) + " / ${names[section]}")
        browseMode = "settings_section"
        currentCategories.clear()
        updateCategories()
        keyboardArea?.visibility = View.GONE
        tabContainer?.visibility = View.GONE
        browsePagination?.visibility = View.GONE
        val entries = when (section) {
            0 -> listOf(
                SettingsEntry(R.drawable.ott_ic_wifi_setting, getString(R.string.ui_a_0681)) {
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) }
                },
            )
            1 -> listOf(
                SettingsEntry(
                    R.drawable.ott_ic_data_upgrade,
                    getString(R.string.ui_sec_library),
                    getString(R.string.ui_p_counting),
                    getString(R.string.ui_update),
                ) {
                    // 手动强制检查更新(绕过 30 天时效; 仅当服务器版本更新时才下载)
                    forceDatabaseSync()
                },
                SettingsEntry(R.drawable.ott_ic_data_setting, getString(R.string.ui_p_045), getString(R.string.ui_p_046), getString(R.string.ui_a_0683)) { toast(getString(R.string.ui_p_047)) },
                SettingsEntry(R.drawable.ott_ic_setting_storage_space, getString(R.string.ui_sec_reserve), getString(R.string.ui_p_102, String.format(Locale.ROOT, "%.1f", reserveStorageGb))) { showReserveStorageDialog() },
                SettingsEntry(R.drawable.ott_ic_data_reset, getString(R.string.ui_sec_auto_delete), getString(R.string.ui_sec_auto_delete_sub), action = {
                    autoDeleteSongs = !autoDeleteSongs
                    saveState()
                    if (autoDeleteSongs) enforceStorageReserve(showResult = true)
                }, checked = autoDeleteSongs),
                SettingsEntry(R.drawable.ott_ic_data_upgrade, getString(R.string.ui_p_056), getString(R.string.ui_p_103), getString(R.string.ui_sec_action_reset)) { confirmResetDatabase() },
                SettingsEntry(R.drawable.ott_ic_setting_storage_space, getString(R.string.ui_sec_permission)) { toast(storageStatusText()) },
            )
            2 -> listOf(
                SettingsEntry(R.drawable.ott_ic_download_setting, getString(R.string.ui_play_clear_downloads), getString(R.string.ui_play_clear_downloads_sub), action = { clearDownloadsOnBoot = !clearDownloadsOnBoot; saveState() }, checked = clearDownloadsOnBoot),
                SettingsEntry(R.drawable.ott_ic_tv_display_mode, getString(R.string.ui_play_autofull), getString(R.string.ui_play_autofull_sub), getString(R.string.ui_a_0724)) { showAutoFullscreenDialog() },
                SettingsEntry(R.drawable.ott_ic_play, getString(R.string.ui_a_0725)) { showAudioSyncDialog() },
                SettingsEntry(R.drawable.ott_ic_marquee_setting, getString(R.string.ui_play_subtitle), action = { songTitleSubtitleEnabled = !songTitleSubtitleEnabled; saveState() }, checked = songTitleSubtitleEnabled),
            )
            3 -> listOf(
                SettingsEntry(R.drawable.ott_ic_sound, getString(R.string.ui_a_0740)) { showSoundSettingsDialog() },
            )
            else -> emptyList()
        }
        activeSettingsEntries = entries
        songList!!.divider = ColorDrawable(Color.TRANSPARENT)
        songList!!.dividerHeight = dp(12)
        songList!!.setBackgroundColor(Color.TRANSPARENT)
        val settingsAdapter = SettingsListAdapter(this, entries, R.id.btn_back)
        activeSettingsAdapter = settingsAdapter
        songList!!.adapter = settingsAdapter
        btnBack?.nextFocusDownId = settingsAdapter.firstActionId
        if (requestInitialFocus) settingsAdapter.requestInitialFocus(songList!!)
        // 数据设置: 曲库/下载数统计较耗时(逐文件校验), 移到 IO 线程, 避免进入界面卡顿。
        if (section == 1) refreshLibraryStatsAsync(settingsAdapter)
    }

    /** IO 线程统计曲库歌曲数与本地已下载数, 完成后原位回填"曲库"条副标题。 */
    private fun refreshLibraryStatsAsync(adapter: SettingsListAdapter) {
        io.execute {
            val count = runCatching { library.muse.songCount() }.getOrDefault(0)
            val downloaded = runCatching { countDownloadedFiles() }.getOrDefault(0)
            // 同步时效(30 天): 未到期不联网, 到期后才触发服务器同步。
            val syncText = DatabaseBootstrapper.syncStatusText()
            main.post {
                if (activeSettingsAdapter === adapter) {
                    adapter.updateSubtitle(
                        getString(R.string.ui_sec_library),
                        getString(R.string.ui_p_104, count, downloaded, DatabaseBootstrapper.SYNC_TTL_DAYS, syncText),
                    )
                }
            }
        }
    }

    /**
     * 手动强制检查曲库更新(绕过 30 天时效)。
     * 仅在服务器版本更新(或本地库不可用)时才下载, 避免无谓的大文件传输。
     */
    private fun forceDatabaseSync() {
        showDatabaseLoading(true, getString(R.string.ui_p_048), null)
        io.execute {
            val remoteVersion = DatabaseBootstrapper.fetchRemoteVersion()
            if (remoteVersion == null) {
                main.post {
                    showDatabaseLoading(false, "", null)
                    toast(getString(R.string.ui_p_049))
                    showSettingsSection(1, false)
                }
                return@execute
            }
            val localVersion = DatabaseBootstrapper.getLocalDbVersion()
            if (library.muse.isAvailable() && remoteVersion == localVersion) {
                DatabaseBootstrapper.markSynced()
                main.post {
                    showDatabaseLoading(false, "", null)
                    toast(getString(R.string.ui_p_050))
                    showSettingsSection(1, false)
                }
                return@execute
            }
            library.muse.close()
            val result = DatabaseBootstrapper.download(::showDatabaseDownloadProgress)
            val ok = result.isSuccess && library.muse.open()
            main.post {
                if (ok) {
                    showDatabaseLoading(false, "", null)
                    toast(getString(R.string.ui_p_051))
                    showSettingsSection(1, false)
                } else {
                    showDatabaseLoadingFailure(getString(R.string.ui_p_093, result.exceptionOrNull()?.message.orEmpty()))
                }
            }
        }
    }

    private fun isNetworkConnected(): Boolean {
        val manager = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
        return manager?.activeNetworkInfo?.isConnected == true
    }

    private fun networkStatusText(): String = if (isNetworkConnected()) KtvRaw.get(R.string.ui_a_0358) else KtvRaw.get(R.string.ui_a_0359)

    private fun storageStatusText(): String {
        val root = Environment.getExternalStorageDirectory()
        return getString(R.string.ui_p_105, formatSize(root.usableSpace), formatSize(root.totalSpace))
    }

    private fun downloadedSongDirectory(): File =
        File(MuseDatabase.VIDEO_ROOT, MuseDatabase.CLOUD_SONG_DIR)

    private fun countDownloadedFiles(): Int = localSongInventory().size

    /**
     * 预热公放候选缓存（启动时调用一次；下载完成后由回调增量补充）。
     *
     * 用**独立守护线程**而不是 [io] 池：里面的 `library.scanLocal()` 在老盒子上可能永久阻塞，
     * 绝不能占住 io 线程；[scanWithTimeout] 保证即使它卡住，也只损失"导入目录/外置存储"那部分候选，
     * 已下载曲库照样可用。
     */
    private fun primeRandomCandidates() {
        if (randomCandidatesPriming || randomCandidatesPrimed) return
        randomCandidatesPriming = true
        Thread({
            val startedAt = System.currentTimeMillis()
            runCatching {
                // 1) 已下载曲库：只读我们自己的目录 + 曲库 DB，安全。
                localSongInventory().forEach { (id, song) -> randomCandidates.putIfAbsent(id, song) }
                // 2) 导入目录 / 外置存储：遍历 /storage/ 各挂载点 有阻塞风险，必须带超时。
                val scanned = scanWithTimeout(RANDOM_SCAN_TIMEOUT_MS) { library.scanLocal() }
                if (scanned == null) {
                    MiliLog.warn("RANDOM-CACHE 目录扫描超时(${RANDOM_SCAN_TIMEOUT_MS}ms)，仅使用已下载曲库")
                } else {
                    scanned.forEach { song ->
                        val file = song.path?.let(::File) ?: return@forEach
                        if (SongFileValidator.inspect(
                                file,
                                SongFileValidator.requiresTransportStream(file),
                            ).valid
                        ) {
                            randomCandidates.putIfAbsent(stableId(song), song)
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "prime random candidates failed", it) }
            randomCandidatesPrimed = true
            randomCandidatesPriming = false
            MiliLog.info(
                "RANDOM-CACHE 预热完成 size=${randomCandidates.size} " +
                    "用时=${System.currentTimeMillis() - startedAt}ms",
            )
        }, "ktv-random-prime").apply { isDaemon = true }.start()
    }

    /**
     * 在独立守护线程里执行"可能永久阻塞"的文件系统扫描，超时返回 null。
     *
     * 不能放进任何线程池：老盒子（极米 H1S，非标准 ROM）上 `/storage/ 各挂载点` 里的失效挂载点
     * 会让 `File.listFiles()` 无限期阻塞，占住池线程会连带拖死后续所有任务。
     */
    private fun <T> scanWithTimeout(timeoutMs: Long, block: () -> T): T? {
        val holder = java.util.concurrent.atomic.AtomicReference<T>()
        val done = java.util.concurrent.CountDownLatch(1)
        Thread({
            runCatching { holder.set(block()) }
            done.countDown()
        }, "ktv-scan").apply { isDaemon = true }.start()
        val finished = runCatching {
            done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        return if (finished) holder.get() else null
    }

    private fun localSongInventory(): LinkedHashMap<String, Song> {
        val result = LinkedHashMap<String, Song>()
        library.muse.localPathSongs().forEach { song ->
            val resolved = File(MuseDatabase.resolveSongFilePath(song))
            if (SongFileValidator.inspect(
                    resolved,
                    SongFileValidator.requiresTransportStream(resolved),
                ).valid
            ) {
                song.path = resolved.absolutePath
                result.getOrPut(stableId(song)) { song }
            }
        }

        val downloadedFiles = MuseDatabase.songDirectories()
            .flatMap { it.listFiles().orEmpty().asIterable() }
            .distinctBy { it.name }
        val inspections = downloadedFiles.asSequence()
            .filter { it.isFile && !it.name.endsWith(".download") }
            .associateWith { file ->
                SongFileValidator.inspect(file, SongFileValidator.requiresTransportStream(file))
            }
        inspections.filter { (file, inspection) ->
            file.parentFile?.absolutePath == downloadedSongDirectory().absolutePath && !inspection.valid
        }.keys.forEach { invalid ->
            Log.w(TAG, "Deleting invalid downloaded song: ${invalid.absolutePath}, ${inspections[invalid]?.reason}")
            SongFileValidator.forget(invalid)
            cleanupSidecars(invalid)
            invalid.delete()
        }
        val validFiles = inspections.filter { (file, inspection) -> file.exists() && inspection.valid }.keys
        val filesByName = validFiles.associateBy { it.name }
        val databaseSongs = library.muse.songsByFilenames(filesByName.keys)
        databaseSongs.forEach { song ->
            filesByName[song.filename]?.let { song.path = it.absolutePath }
            result.getOrPut(stableId(song)) { song }
        }
        val matchedNames = databaseSongs.mapNotNullTo(HashSet()) { it.filename }
        validFiles.filter { it.name !in matchedNames }.forEach { file ->
            val song = Song.local(file.absolutePath, file.name)
            song.filename = file.name
            result.getOrPut(stableId(song)) { song }
        }
        return result
    }

    private fun clearDownloadedFilesOnBoot() {
        downloadedSongDirectory().listFiles().orEmpty().forEach { file ->
            if (file.isFile) {
                cleanupSidecars(file)
                runCatching { file.delete() }
            }
        }
        downloads.clear()
        runCatching { stateDatabase.clearDownloads() }
    }

    private fun isBelowReservedStorage(): Boolean {
        val required = (reserveStorageGb * 1024.0 * 1024.0 * 1024.0).toLong()
        return downloadedSongDirectory().usableSpace < required
    }

    private fun enforceStorageReserve(showResult: Boolean = false) {
        if (!autoDeleteSongs || !isBelowReservedStorage()) return
        io.execute {
            val deleted = purgeColdDownloadedFiles()
            val enough = !isBelowReservedStorage()
            if (showResult) main.post {
                toast(
                    if (enough) getString(R.string.ui_a_0701, deleted)
                    else getString(R.string.ui_a_0702, deleted),
                )
                if (browseMode == "settings_section") showSettingsSection(1, false)
            }
        }
    }

    private fun purgeColdDownloadedFiles(): Int {
        val required = (reserveStorageGb * 1024.0 * 1024.0 * 1024.0).toLong()
        val protectedNames = buildSet {
            currentSong?.filename?.let(::add)
            orderQueue.orEmpty().mapNotNullTo(this) { it.filename }
        }
        var deletedCount = 0
        downloadedSongDirectory().listFiles().orEmpty()
            .filter { it.isFile && it.name !in protectedNames && !it.name.endsWith(".download") }
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .forEach { file ->
                if (downloadedSongDirectory().usableSpace >= required) return deletedCount
                cleanupSidecars(file)
                if (file.delete()) {
                    deletedCount++
                    library.allSongs().filter { it.filename == file.name }.forEach { song ->
                        song.path = null
                        runCatching { stateDatabase.removeDownload(song) }
                    }
                }
            }
        return deletedCount
    }

    private fun confirmResetDatabase() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_p_056))
            .setMessage(getString(R.string.ui_p_057))
            .setNegativeButton(getString(R.string.ui_cancel), null)
            .setPositiveButton(getString(R.string.ui_a_0706)) { _, _ -> resetDatabaseFromMuse() }
            .create().showForTv()
    }

    private fun resetDatabaseFromMuse() {
        showDatabaseLoading(true, getString(R.string.ui_p_058), null)
        io.execute {
            library.muse.close()
            MuseDatabase.defaultDbFile().delete()
            val result = DatabaseBootstrapper.download(::showDatabaseDownloadProgress)
            val opened = result.isSuccess && library.muse.open()
            main.post {
                if (opened) {
                    showDatabaseLoading(false, "", null)
                    toast(getString(R.string.ui_p_059))
                    showSettingsSection(1, false)
                } else {
                    showDatabaseLoadingFailure(getString(R.string.ui_p_094, result.exceptionOrNull()?.message.orEmpty()))
                }
            }
        }
    }

    private fun showDatabaseLoading(show: Boolean, message: String, progressValue: Int?) {
        if (!show) {
            databaseLoadingOverlay?.visibility = View.GONE
            return
        }
        if (databaseLoadingOverlay == null) {
            val root = findViewById<FrameLayout>(R.id.layout_root)
            val overlay = FrameLayout(this).apply {
                setBackgroundColor(Color.rgb(24, 17, 45))
                isClickable = true
                isFocusable = true
                UiCompat.setElevation(this, dp(30).toFloat())
            }
            // 启动加载遮罩铺满整屏: 不允许被焦点/按下填充覆盖, 否则点击启动界面会整屏变纯色。
            TvFocusStyler.suppressFill(overlay)
            val center = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ktv_logo)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }, LinearLayout.LayoutParams(dp(112), dp(112)))
                addView(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        ProgressBar(this@MainActivity, null, 0, R.style.LoadingProgressBar)
                    } else {
                        // Android 4.4 没有带 defStyleRes 的 4 参构造器(API 21 引入),
                        // 手动套用 LoadingProgressBar 里同样的 indeterminate drawable。
                        ProgressBar(this@MainActivity).apply {
                            indeterminateDrawable = ContextCompat.getDrawable(this@MainActivity, R.anim.loading_anim)
                        }
                    },
                    LinearLayout.LayoutParams(dp(54), dp(54)).apply { topMargin = dp(22) },
                )
                databaseLoadingText = TextView(this@MainActivity).apply {
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setPadding(dp(20), dp(18), dp(20), dp(12))
                }
                addView(databaseLoadingText, LinearLayout.LayoutParams(dp(620), -2))
                databaseLoadingProgress = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progressDrawable = ContextCompat.getDrawable(context, R.drawable.bg_download_progress)
                }
                addView(databaseLoadingProgress, LinearLayout.LayoutParams(dp(620), dp(10)))
                databaseRetryButton = TextView(this@MainActivity).apply {
                    text = getString(R.string.ui_a_0713)
                    textSize = 19f
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(45, 20, 65))
                    isClickable = true
                    isFocusable = true
                    visibility = View.GONE
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(Color.rgb(255, 204, 92))
                    }
                }
                addView(databaseRetryButton, LinearLayout.LayoutParams(dp(180), dp(54)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(24)
                })
            }
            overlay.addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
            databaseLoadingOverlay = overlay
        }
        // ⚠️ 这里是**每次进度刷新都会走**的路径（曲库下载 430MB）。必须做到「只在真正变化时
        // 才改 View、且不做重排」：早先的实现每次都 setText + 改 progress + setOnClickListener(null)
        // + bringToFront()（整棵视图树 requestLayout），把主线程压死 → 界面卡死、下载也变慢。
        val overlay = databaseLoadingOverlay ?: return
        if (overlay.visibility != View.VISIBLE) {
            overlay.visibility = View.VISIBLE
            overlay.bringToFront()
        }
        val textView = databaseLoadingText
        if (textView != null && textView.text?.toString() != message) {
            textView.text = message
        }
        val bar = databaseLoadingProgress
        if (bar != null) {
            val wanted = if (progressValue == null) View.INVISIBLE else View.VISIBLE
            if (bar.visibility != wanted) bar.visibility = wanted
            val percent = progressValue ?: 0
            if (bar.progress != percent) bar.progress = percent
        }
        val retry = databaseRetryButton
        if (retry != null && retry.visibility == View.VISIBLE) {
            retry.visibility = View.GONE
            retry.isEnabled = true
            retry.setOnClickListener(null)
        }
    }

    private fun showDatabaseLoadingFailure(message: String) {
        showDatabaseLoading(true, getString(R.string.ui_db_fail_hint, message), null)
        databaseRetryButton?.apply {
            visibility = View.VISIBLE
            setOnClickListener {
                isEnabled = false
                initializeDatabase()
            }
            requestFocus()
        }
    }

    // ===== 设置子功能 (新Tab版) =====
    private fun dialogSingleChoiceGroup(
        labels: Array<String>,
        selected: Int,
        onSelected: (Int) -> Unit,
    ): android.widget.RadioGroup = android.widget.RadioGroup(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(6), dp(20), dp(6))
        labels.forEachIndexed { index, label ->
            addView(android.widget.RadioButton(this@MainActivity).apply {
                id = View.generateViewId()
                tag = index
                text = label
                textSize = 17f
                setTextColor(Color.WHITE)
                isChecked = index == selected
                minimumHeight = 0
                minHeight = 0
                setPadding(dp(8), 0, dp(14), 0)
                layoutParams = android.widget.RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(48),
                )
                TvFocusStyler.installAction(this)
            })
        }
        setOnCheckedChangeListener { group, checkedId ->
            val index = group.findViewById<View>(checkedId)?.tag as? Int
                ?: return@setOnCheckedChangeListener
            onSelected(index)
        }
    }

    private fun dialogMultiChoiceGroup(
        labels: Array<String>,
        checked: BooleanArray,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(6), dp(20), dp(6))
        labels.forEachIndexed { index, label ->
            addView(android.widget.CheckBox(this@MainActivity).apply {
                text = label
                textSize = 17f
                setTextColor(Color.WHITE)
                isChecked = checked.getOrElse(index) { false }
                minimumHeight = 0
                minHeight = 0
                setPadding(dp(8), 0, dp(14), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(48),
                )
                setOnCheckedChangeListener { _, value -> checked[index] = value }
                TvFocusStyler.installAction(this)
            })
        }
    }

    private fun showScreenDialog() {
        val items = arrayOf<String>("16 : 9", "4 : 3", getString(R.string.ui_a_0717))
        val selected = when (screenMode) {
            "16 : 9" -> 0
            "4 : 3" -> 1
            else -> 2
        }
        var pending = selected
        val choices = dialogSingleChoiceGroup(items, selected) { pending = it }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_a_0718))
            .setView(choices)
            .setNegativeButton(getString(R.string.ui_close), null)
            .setPositiveButton(getString(R.string.ui_a_0706)) { _, _ ->
                screenMode = items[pending]
                saveState()
                applyScreenMode()
            }.create().showForTv()
    }

    private fun showAutoFullscreenDialog() {
        val items = arrayOf(getString(R.string.ui_a_0719), getString(R.string.ui_a_0720), getString(R.string.ui_a_0721), getString(R.string.ui_a_0722), getString(R.string.ui_a_0723))
        val seconds = intArrayOf(30, 45, 60, 120, 0)
        val selected = seconds.indexOf(autoFullscreenSeconds).let { if (it >= 0) it else 4 }
        var pending = selected
        val choices = dialogSingleChoiceGroup(items, selected) { pending = it }
        AlertDialog.Builder(this).setTitle(getString(R.string.ui_play_action_set))
            .setView(choices)
            .setNegativeButton(getString(R.string.ui_close), null)
            .setPositiveButton(getString(R.string.ui_a_0706)) { _, _ ->
                autoFullscreenSeconds = seconds[pending]
                saveState()
                scheduleAutoFullscreen()
                showSettingsSection(2, false)
            }.create().showForTv()
    }

    private fun showReserveStorageDialog() {
        val totalGb = downloadedSongDirectory().totalSpace.toDouble() / 1024.0 / 1024.0 / 1024.0
        val values = listOf(0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0)
            .filter { it <= max(0.5, totalGb * 0.95) }
            .ifEmpty { listOf(0.5) }
        val labels = values.map { value ->
            "${String.format(Locale.ROOT, if (value < 1) "%.1f" else "%.0f", value)} GB"
        }.toTypedArray()
        var selected = values.indices.minByOrNull { kotlin.math.abs(values[it] - reserveStorageGb) } ?: 0
        AlertDialog.Builder(this).setTitle(getString(R.string.ui_sec_reserve))
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setNegativeButton(getString(R.string.ui_cancel), null).setPositiveButton(getString(R.string.ui_a_0471)) { _, _ ->
                reserveStorageGb = values[selected]
                saveState()
                if (autoDeleteSongs) enforceStorageReserve(showResult = true)
                showSettingsSection(1, false)
            }.create().showForTv()
    }

    private fun showAudioSyncDialog() {
        var pendingDelay = audioDelayMs
        val seek = SeekBar(this).apply { max = 40; progress = (pendingDelay / 50 + 20).coerceIn(0, 40) }
        val value = TextView(this).apply { gravity = Gravity.CENTER; textSize = 18f }
        fun update() { value.text = getString(R.string.ui_p_097, if (pendingDelay >= 0) getString(R.string.ui_p_095) else getString(R.string.ui_p_096), kotlin.math.abs(pendingDelay) / 1000f) }
        update()
        seek.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { pendingDelay = (progress - 20) * 50; update() }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(32), dp(8), dp(32), dp(16)); addView(value); addView(seek) }
        AlertDialog.Builder(this).setTitle(getString(R.string.ui_play_avsync)).setView(box).setMessage(getString(R.string.ui_a_0726))
            .setNegativeButton(getString(R.string.ui_close), null)
            .setPositiveButton(getString(R.string.ui_a_0706)) { _, _ -> audioDelayMs = pendingDelay; saveState() }
            .create().showForTv()
    }

    private fun showBannerConfigDialog() {
        val labels = arrayOf(getString(R.string.ui_a_0727), getString(R.string.ui_a_0728), getString(R.string.ui_a_0729))
        val pending = booleanArrayOf(true, true, true)
        val choices = dialogMultiChoiceGroup(labels, pending)
        AlertDialog.Builder(this).setTitle(getString(R.string.ui_a_0730))
            .setView(choices)
            .setNegativeButton(getString(R.string.ui_close), null).setPositiveButton(getString(R.string.ui_a_0706), null)
            .create().showForTv()
    }

    private fun showSoundSettingsDialog() {
        var pendingOrderedSongOriginal = orderedSongOriginal
        var pendingOrderedVolumeMode = orderedVolumeMode
        var pendingOrderedVocalMode = orderedVocalMode
        var pendingSingMode = singMode
        // 演唱模式：显示文案跟随语言，内部写入固定中文键（与曲库/状态值一致）。
        // 这里把它补回"声音设置"弹窗——旧版 showSingModeDialog() 已成死代码，
        // 导致用户一旦被残留成"静音练唱"就无处改回。
        val singModeLabels = arrayOf(
            getString(R.string.ui_a_0088), getString(R.string.ui_a_0260),
            getString(R.string.ui_a_0261), getString(R.string.ui_a_0262),
        )
        val singModeValues = arrayOf(
            KtvRaw.get(R.string.ui_a_0088), KtvRaw.get(R.string.ui_a_0260),
            KtvRaw.get(R.string.ui_a_0261), KtvRaw.get(R.string.ui_a_0262),
        )
        fun title(text: String) = TextView(this).apply {
            this.text = text; textSize = 16f; setTextColor(Color.WHITE); setPadding(0, dp(4), 0, 0)
        }
        fun choices(labels: Array<String>, selected: Int, horizontal: Boolean = false, changed: (Int) -> Unit) =
            android.widget.RadioGroup(this).apply {
                orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                labels.forEachIndexed { index, label ->
                    addView(android.widget.RadioButton(this@MainActivity).apply {
                        text = label; textSize = 15f; setTextColor(Color.WHITE); isChecked = index == selected
                        id = View.generateViewId(); tag = index
                        minimumHeight = 0
                        minHeight = 0
                        setPadding(0, 0, dp(6), 0)
                        layoutParams = android.widget.RadioGroup.LayoutParams(-2, dp(30))
                        TvFocusStyler.installAction(this)
                    })
                }
                setOnCheckedChangeListener { group, checkedId ->
                    val index = group.findViewById<View>(checkedId)?.tag as? Int ?: return@setOnCheckedChangeListener
                    changed(index)
                }
            }
        // 说明：音量调节与均衡器入口已从这里移除 —— 它们统一收敛到「调音」弹窗，
        // 避免同一功能出现两套入口（音量滑杆 + 均衡器开关/预设/频段都在「调音」里）。
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title(getString(R.string.ui_a_0732)))
            addView(choices(arrayOf(getString(R.string.ui_a_0733), getString(R.string.ui_a_0734)), pendingOrderedVolumeMode) { pendingOrderedVolumeMode = it })
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title(getString(R.string.ui_a_0735)))
            addView(choices(arrayOf(getString(R.string.ui_a_0305), getString(R.string.ui_a_0306)), if (pendingOrderedSongOriginal) 0 else 1, true) { pendingOrderedSongOriginal = it == 0 })
            addView(title(getString(R.string.ui_a_0736)))
            addView(choices(arrayOf(getString(R.string.ui_a_0737), getString(R.string.ui_a_0738), getString(R.string.ui_a_0739)), pendingOrderedVocalMode) { pendingOrderedVocalMode = it })
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(28), dp(4), dp(28), dp(12))
            addView(left, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(28) })
            addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title(getString(R.string.ui_a_0259)))
            addView(
                choices(singModeLabels, singModeValues.indexOf(pendingSingMode).coerceAtLeast(0)) { pendingSingMode = singModeValues[it] }
            )
            addView(content, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        val scroll = android.widget.ScrollView(this).apply { addView(body) }
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.ui_set_sound))
            .setView(scroll)
            .setNegativeButton(getString(R.string.ui_close), null)
            .setPositiveButton(getString(R.string.ui_a_0706)) { _, _ ->
                orderedSongOriginal = pendingOrderedSongOriginal
                orderedVolumeMode = pendingOrderedVolumeMode
                orderedVocalMode = pendingOrderedVocalMode
                if (pendingSingMode != singMode) setSingMode(pendingSingMode)
                applyPlaybackMode()
                saveState()
            }.create()
        dialog.showForTv(DialogInterface.BUTTON_NEGATIVE) {
            // 同样的分辨率自适应：内容过高时在弹窗内滚动而不是被裁切。
            fitDialogToScreen(
                dialog = dialog,
                scroll = scroll,
                content = body,
                preferredWidthDp = 790,
                widthRatio = 0.92f,
            )
        }
    }

    private fun showSingModeDialog() {
        val labels = arrayOf(getString(R.string.ui_a_0088), getString(R.string.ui_a_0261), getString(R.string.ui_a_0262))
        val values = arrayOf(KtvRaw.get(R.string.ui_a_0088), KtvRaw.get(R.string.ui_a_0261), KtvRaw.get(R.string.ui_a_0262))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_p_060) + singMode + ")")
            .setItems(labels, DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                singMode = values[w]
                if (KtvRaw.get(R.string.ui_a_0261) == singMode) scoreEnabled = true
                else if (KtvRaw.get(R.string.ui_a_0262) == singMode) {
                    scoreEnabled = false
                    musicVolume = 0
                }
                saveState()
                applyPlaybackMode()
                toast(getString(R.string.ui_p_061) + singMode)
            }).create().showForTv()
    }

    private fun showVocalChannelDialog() {
        val labels = arrayOf(getString(R.string.ui_a_0086), getString(R.string.ui_a_0273), getString(R.string.ui_a_0274), getString(R.string.ui_a_0275))
        val values = arrayOf(KtvRaw.get(R.string.ui_a_0086), KtvRaw.get(R.string.ui_a_0273), KtvRaw.get(R.string.ui_a_0274), KtvRaw.get(R.string.ui_a_0275))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_a_0743, vocalChannelMode))
            .setItems(labels) { _, which ->
                setVocalChannelMode(values[which])
                showSettingsSection(3)
            }
            .create().showForTv()
    }

    private fun showMarqueeDialog() {
        val status = if (marqueeEnabled) getString(R.string.ui_a_0744) + marqueeText else getString(R.string.ui_a_0456)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_p_062) + status)
            .setItems(
                arrayOf<String>(getString(R.string.ui_a_0473), getString(R.string.ui_a_0037), getString(R.string.ui_a_0746)),
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    if (w == 0) {
                        marqueeEnabled = true
                        saveState()
                    } else if (w == 1) {
                        marqueeEnabled = false
                        saveState()
                    } else {
                        val input = EditText(this)
                        input.setText(marqueeText)
                        input.setHint(getString(R.string.ui_a_0747))
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.ui_a_0748))
                            .setView(input)
                            .setPositiveButton(
                                getString(R.string.ui_ok),
                                DialogInterface.OnClickListener { dd: DialogInterface?, ww: Int ->
                                    marqueeText = input.getText().toString()
                                    marqueeEnabled = true
                                    saveState()
                                }).setNegativeButton(getString(R.string.ui_cancel), null).create().showForTv()
                    }
                    toast(getString(R.string.ui_p_063) + (if (marqueeEnabled) marqueeText else getString(R.string.ui_a_0456)))
                }).create().showForTv()
    }

    private fun showNetworkDlg() {
        var ip = KtvRaw.get(R.string.ui_a_0361)
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val ni = en.nextElement()
                val addr = ni.getInetAddresses()
                while (addr.hasMoreElements()) {
                    val ia = addr.nextElement()
                    if (!ia.isLoopbackAddress() && ia is Inet4Address) {
                        ip = ia.getHostAddress().orEmpty()
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_sec_network))
            .setMessage("IP: " + ip + getString(R.string.ui_a_0750))
            .setPositiveButton(getString(R.string.ui_ok), null).create().showForTv(DialogInterface.BUTTON_POSITIVE)
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_a_0751))
            .setMessage(
                getString(R.string.ui_p_106) +
                    getString(R.string.ui_p_107) +
                    KtvRaw.get(R.string.ui_a_0753) +
                    KtvRaw.get(R.string.ui_a_0754) + BuildConfig.VERSION_NAME
            )
            .setPositiveButton(getString(R.string.ui_ok), null).create().showForTv(DialogInterface.BUTTON_POSITIVE)
    }

    private fun applyScreenMode() {
        if (player != null) {
            try {
                applyPlaybackMode()
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * 把曲库/内部返回的中文类别键转成「跟随当前界面语言」的显示文案。
     * 内部比较仍使用固定中文键（KtvRaw.get），只有显示层用本方法，
     * 与 ori 版一致，切语言后不影响筛选/分组判断。
     */
    private fun displayCategory(value: String?): String {
        val raw = value ?: return ""
        if (raw.isEmpty()) return raw
        if (raw.contains(',')) {
            return raw.split(',').joinToString(", ") { displayCategory(it.trim()) }
        }
        val quick = KtvRaw.displayName(raw)
        if (quick != raw) return quick
        val resId = when (raw) {
            KtvRaw.get(R.string.ui_a_0069) -> R.string.ui_a_0069
            KtvRaw.get(R.string.ui_a_0072) -> R.string.ui_a_0072
            KtvRaw.get(R.string.ui_a_0073) -> R.string.ui_a_0073
            KtvRaw.get(R.string.ui_a_0074) -> R.string.ui_a_0074
            KtvRaw.get(R.string.ui_a_0075) -> R.string.ui_a_0075
            KtvRaw.get(R.string.ui_a_0077) -> R.string.ui_a_0077
            KtvRaw.get(R.string.ui_a_0078) -> R.string.ui_a_0078
            KtvRaw.get(R.string.ui_a_0079) -> R.string.ui_a_0079
            KtvRaw.get(R.string.ui_a_0080) -> R.string.ui_a_0080
            KtvRaw.get(R.string.ui_a_0198) -> R.string.ui_a_0198
            KtvRaw.get(R.string.ui_a_0199) -> R.string.ui_a_0199
            KtvRaw.get(R.string.ui_a_0200) -> R.string.ui_a_0200
            KtvRaw.get(R.string.ui_a_0210) -> R.string.ui_a_0210
            KtvRaw.get(R.string.ui_a_0211) -> R.string.ui_a_0211
            KtvRaw.get(R.string.ui_a_0212) -> R.string.ui_a_0212
            KtvRaw.get(R.string.ui_a_0213) -> R.string.ui_a_0213
            KtvRaw.get(R.string.ui_a_0214) -> R.string.ui_a_0214
            KtvRaw.get(R.string.ui_a_0215) -> R.string.ui_a_0215
            KtvRaw.get(R.string.ui_a_0656) -> R.string.ui_a_0656
            KtvRaw.get(R.string.ui_a_0657) -> R.string.ui_a_0657
            KtvRaw.get(R.string.ui_a_0658) -> R.string.ui_a_0658
            KtvRaw.get(R.string.ui_a_0659) -> R.string.ui_a_0659
            KtvRaw.get(R.string.ui_a_0660) -> R.string.ui_a_0660
            KtvRaw.get(R.string.ui_a_0661) -> R.string.ui_a_0661
            KtvRaw.get(R.string.ui_a_0663) -> R.string.ui_a_0663
            KtvRaw.get(R.string.ui_a_0664) -> R.string.ui_a_0664
            KtvRaw.get(R.string.ui_a_0665) -> R.string.ui_a_0665
            KtvRaw.get(R.string.ui_a_0666) -> R.string.ui_a_0666
            KtvRaw.get(R.string.ui_a_0667) -> R.string.ui_a_0667
            KtvRaw.get(R.string.ui_a_0668) -> R.string.ui_a_0668
            KtvRaw.get(R.string.lang_show_other) -> R.string.lang_show_other
            KtvRaw.get(R.string.lang_show_russian) -> R.string.lang_show_russian
            KtvRaw.get(R.string.lang_show_hindi) -> R.string.lang_show_hindi
            KtvRaw.get(R.string.lang_show_taiwanese) -> R.string.lang_show_taiwanese
            KtvRaw.get(R.string.lang_show_hani) -> R.string.lang_show_hani
            KtvRaw.get(R.string.lang_show_mandarin) -> R.string.lang_show_mandarin
            KtvRaw.get(R.string.lang_show_dialect) -> R.string.lang_show_dialect
            KtvRaw.get(R.string.lang_show_cosmic) -> R.string.lang_show_cosmic
            KtvRaw.get(R.string.lang_show_hakka) -> R.string.lang_show_hakka
            KtvRaw.get(R.string.lang_show_hakka2) -> R.string.lang_show_hakka2
            KtvRaw.get(R.string.lang_show_yi) -> R.string.lang_show_yi
            KtvRaw.get(R.string.lang_show_italian) -> R.string.lang_show_italian
            KtvRaw.get(R.string.lang_show_japanese) -> R.string.lang_show_japanese
            KtvRaw.get(R.string.lang_show_thai) -> R.string.lang_show_thai
            KtvRaw.get(R.string.lang_show_hainanese) -> R.string.lang_show_hainanese
            KtvRaw.get(R.string.lang_show_teochew) -> R.string.lang_show_teochew
            KtvRaw.get(R.string.lang_show_aini) -> R.string.lang_show_aini
            KtvRaw.get(R.string.lang_show_qidong) -> R.string.lang_show_qidong
            KtvRaw.get(R.string.lang_show_fuding) -> R.string.lang_show_fuding
            KtvRaw.get(R.string.lang_show_cantonese) -> R.string.lang_show_cantonese
            KtvRaw.get(R.string.lang_show_uyghur) -> R.string.lang_show_uyghur
            KtvRaw.get(R.string.lang_show_hmong) -> R.string.lang_show_hmong
            KtvRaw.get(R.string.lang_show_english) -> R.string.lang_show_english
            KtvRaw.get(R.string.lang_show_mongolian) -> R.string.lang_show_mongolian
            KtvRaw.get(R.string.lang_show_tibetan) -> R.string.lang_show_tibetan
            KtvRaw.get(R.string.lang_show_spanish) -> R.string.lang_show_spanish
            KtvRaw.get(R.string.lang_show_minnan) -> R.string.lang_show_minnan
            KtvRaw.get(R.string.lang_show_korean) -> R.string.lang_show_korean
            KtvRaw.get(R.string.lang_show_khmer) -> R.string.lang_show_khmer
            else -> 0
        }
        if (resId != 0) return getString(resId)
        // 分类名若带后缀/前缀(如"甜蜜幸福精选")或含可翻译片段，走片段替换兜底，
        // 保证中英混合的曲库分类名在英文态不残留中文。没有命中任何片段时原样返回。
        return KtvRaw.displayPlaylistName(raw)
    }

    /**
     * 更新分类列表 UI
     */
    private fun updateCategories() {
        val focusedCategory = window.decorView.findFocus()?.takeIf { isDescendantOf(it, categoryList) }
            ?.let(::captureFocusBookmark)
        categoryList!!.removeAllViews()
        subCategoryScroll?.visibility = if (currentCategories.isEmpty()) View.GONE else View.VISIBLE
        for (i in currentCategories.indices) {
            val index = i
            val cat = TextView(this)
            cat.setText(displayCategory(currentCategories.get(i)))
            cat.setTextSize(16f)
            cat.setPadding(dp(12), 0, dp(12), 0)
            cat.gravity = Gravity.CENTER
            cat.setAccessibleFocus("focus:category:${currentCategories[i]}", getString(R.string.ui_a_0647, displayCategory(currentCategories[i])))
            cat.setFocusable(true)
            cat.setClickable(true)
            // 设置选中状态
            if (i == currentCategoryIndex) {
                cat.setTextColor(Color.rgb(253, 51, 89))
                cat.setBackgroundColor(Color.argb(30, 253, 51, 89))
            } else {
                cat.setTextColor(Color.argb(200, 241, 241, 241))
                cat.setBackgroundColor(Color.TRANSPARENT)
            }
            cat.setOnClickListener(View.OnClickListener { v: View? ->
                currentCategoryIndex = index
                updateCategories()
                // 根据分类加载歌曲
                val category = currentCategories.get(index)
                clearSearchForFilterChange()
                when (currentTabIndex) {
                    0 -> if (KtvRaw.get(R.string.ui_a_0069) == category) {
                        browseMode = "hot"
                        browseParam = ""
                        searchLanguage = KtvRaw.get(R.string.ui_a_0069)
                    } else {
                        browseMode = "language"
                        browseParam = category
                        searchLanguage = category
                    }

                    1 -> {
                        browsePage = 0
                        loadSingerList(category)
                        return@OnClickListener
                    }

                    2 -> {
                        browseMode = "language"
                        browseParam = category
                    }

                    3 -> {
                        browseMode = "wordcount"
                        browseParam = getWordCountParam(category)
                    }

                    5 -> {
                        browseMode = "playlist"
                        browseParam = category
                    }

                    9 -> {
                        browseMode = "rank"
                        browseParam = rankPlaylistIds.getOrNull(index).orEmpty()
                        listTitle!!.text = displayCategory(category)
                    }

                    10 -> {
                        browseMode = "local"
                        browseParam = category
                        searchLanguage = category
                        browsePage = 0
                        loadLocalCatalogPage()
                        return@OnClickListener
                    }
                }
                browsePage = 0
                loadBrowsePage()
            })
            installPressFeedback(cat)
            categoryList!!.addView(cat)
        }
        restoreFocusBookmark(focusedCategory)
    }

    /**
     * 将字数分类转换为查询参数
     */
    private fun getWordCountParam(category: String): String {
        when (category) {
            "2字" -> return "2"
            "3字" -> return "3"
            KtvRaw.get(R.string.ui_a_0658) -> return "4"
            KtvRaw.get(R.string.ui_a_0659) -> return "5"
            KtvRaw.get(R.string.ui_a_0660) -> return "6"
            else -> return "7" // 7字以上
        }
    }

    /**
     * 执行搜索
     */
    private fun performSearch() {
        val query = searchInput?.text?.toString()?.trim().orEmpty()
        activeSearchQuery = query
        browsePage = 0
        when (searchScope) {
            "quick" -> loadQuickSearchPage(query)
            "singer" -> {
                val category = currentCategories.getOrNull(currentCategoryIndex) ?: KtvRaw.get(R.string.ui_a_0069)
                loadSingerList(category, query)
            }
            "playlist" -> loadCategoryPlaylistPage(query)
            "local" -> loadLocalCatalogPage()
            "singer_songs" -> {
                browseMode = if (query.isEmpty()) "singer_id" else "singer_id_search"
                browseParam = searchContextId
                loadBrowsePage()
            }
            "playlist_songs" -> {
                browseMode = if (query.isEmpty()) "playlist_id" else "playlist_id_search"
                browseParam = searchContextId
                loadBrowsePage()
            }
            else -> {
                searchLanguage = currentCategories.getOrNull(currentCategoryIndex) ?: KtvRaw.get(R.string.ui_a_0069)
                if (query.isEmpty()) {
                    browseMode = if (searchLanguage == "全部") "hot" else "language"
                    browseParam = if (searchLanguage == "全部") "" else searchLanguage
                    listTitle?.text = if (searchLanguage == "全部") getString(R.string.ui_a_0756) else searchLanguage
                } else {
                    browseMode = "search"
                    browseParam = query
                    listTitle?.text = getString(R.string.ui_a_0757, query)
                }
                loadBrowsePage()
            }
        }
    }

    /**
     * 加载浏览分页数据
     */
    private fun loadBrowsePage() {
        if (!library.muse.isAvailable()) {
            catalogRefreshPending = true
            busy(true, getString(R.string.ui_a_0650))
            return
        }
        catalogRefreshPending = false
        busy(false, "")
        val requestVersion = ++browseRequestVersion
        val requestedMode = browseMode
        val requestedParam = browseParam.orEmpty()
        val requestedPage = browsePage
        val requestedLanguage = searchLanguage
        val requestedSearchQuery = activeSearchQuery
        val pageSize = when (requestedMode) {
            "rank" -> 10
            "singer_list" -> 8
            else -> MuseDatabase.PAGE_SIZE
        }
        val key = BrowseCacheKey(
            requestedMode,
            requestedParam,
            requestedLanguage,
            requestedSearchQuery,
            requestedPage,
            pageSize,
        )
        val cached = synchronized(browsePageCache) { browsePageCache[key] }
        if (cached != null) {
            applyBrowsePage(key, cached, requestVersion)
            prefetchBrowseNeighbors(key, cached.total)
            return
        }
        requestBrowsePage(key, requestVersion, true)
    }

    private fun browseCountKey(key: BrowseCacheKey): String =
        listOf(key.mode, key.param, key.language, key.searchQuery).joinToString("\u0001")

    private fun requestBrowsePage(key: BrowseCacheKey, requestVersion: Int, display: Boolean) {
        if (display) synchronized(browseDisplayRequests) { browseDisplayRequests[key] = requestVersion }
        synchronized(browseRequestsInFlight) {
            if (!browseRequestsInFlight.add(key)) return
        }
        io.execute(Runnable {
            try {
                val result = queryBrowsePage(key)
                synchronized(browsePageCache) { browsePageCache[key] = result }
                val displayVersion = synchronized(browseDisplayRequests) { browseDisplayRequests.remove(key) }
                if (displayVersion != null) main.post { applyBrowsePage(key, result, displayVersion) }
                prefetchBrowseNeighbors(key, result.total)
            } finally {
                synchronized(browseRequestsInFlight) { browseRequestsInFlight.remove(key) }
            }
        })
    }

    private fun queryBrowsePage(key: BrowseCacheKey): BrowsePageResult {
            var songs: MutableList<Song>? = null
            val countKey = browseCountKey(key)
            var total = synchronized(browseCountCache) { browseCountCache[countKey] } ?: -1
            val offset = key.page * key.pageSize
            if (library.muse.isAvailable()) {
                when (key.mode) {
                    "hot" -> {
                        songs = library.muse.hotSongs(offset, key.pageSize)
                        if (total < 0) total = library.muse.songCount()
                    }

                    "guess" -> {
                        songs = library.muse.hotSongs(offset, key.pageSize)
                        if (total < 0) total = library.muse.songCount()
                    }

                    "rank" -> {
                        songs = library.muse.rankSongs(key.param, offset, key.pageSize)
                        if (total < 0) total = library.muse.rankSongCount(key.param)
                    }

                    "language" -> {
                        songs = library.muse.songsByLanguage(key.param, offset, key.pageSize)
                        if (total < 0) total = library.muse.countByLanguage(key.param)
                    }

                    "wordcount" -> {
                        val wc = parseIntSafe(key.param, 0)
                        songs = library.muse.songsByWordCount(wc, offset, key.pageSize)
                        if (total < 0) total = library.muse.countByWordCount(wc)
                    }

                    "singer" -> {
                        songs = library.muse.songsBySingerName(key.param, offset, key.pageSize)
                        if (total < 0) total = library.muse.countSongsBySingerName(key.param)
                    }

                    "singer_id" -> {
                        songs = library.muse.songsBySinger(key.param, offset, key.pageSize)
                        if (total < 0) total = library.muse.countSongsBySinger(key.param)
                    }

                    "singer_id_search" -> {
                        songs = library.muse.searchSongsBySinger(key.param, key.searchQuery, offset, key.pageSize)
                        if (total < 0) total = library.muse.countSearchSongsBySinger(key.param, key.searchQuery)
                    }

                    "search" -> {
                        songs = library.muse.searchSongs(key.param, offset, key.pageSize, key.language)
                        if (total < 0) total = library.muse.searchSongCount(key.param, key.language)
                    }

                    "favorite" -> {
                        // 收藏功能暂未实现,返回空列表
                        songs = ArrayList<Song>()
                        total = 0
                    }

                    "playlist" -> {
                        // 歌单查询(通过歌单名称查找 ID)
                        val plist: MutableList<Array<String?>> = library.muse.playlists(0, 100)
                        var playlistId = ""
                        for (p in plist) {
                            if (key.param == p[1]) {
                                playlistId = p[0].orEmpty()
                                break
                            }
                        }
                        if (!playlistId.isEmpty()) {
                            songs = library.muse.songsInPlaylist(playlistId, offset, key.pageSize)
                            if (total < 0) total = library.muse.playlistSongCount(playlistId)
                        } else {
                            songs = ArrayList<Song>()
                            total = 0
                        }
                    }

                    "playlist_id" -> {
                        songs = library.muse.songsInPlaylist(key.param, offset, key.pageSize)
                        if (total < 0) total = library.muse.playlistSongCount(key.param)
                    }

                    "playlist_id_search" -> {
                        songs = library.muse.searchSongsInPlaylist(key.param, key.searchQuery, offset, key.pageSize)
                        if (total < 0) total = library.muse.countSearchSongsInPlaylist(key.param, key.searchQuery)
                    }
                }
            }
            if (total < 0) total = 0
            synchronized(browseCountCache) { browseCountCache[countKey] = total }
            return BrowsePageResult(songs.orEmpty(), total)
    }

    private fun applyBrowsePage(key: BrowseCacheKey, result: BrowsePageResult, requestVersion: Int) {
        if (requestVersion != browseRequestVersion || key.mode != browseMode ||
            key.param != browseParam.orEmpty() || key.page != browsePage ||
            key.language != searchLanguage || key.searchQuery != activeSearchQuery
        ) return
        browseTotalCount = result.total
        browseTotalPages = (result.total + key.pageSize - 1) / key.pageSize
        visibleSongs.clear()
        visibleSongs.addAll(result.songs)
        renderSongList()
        updatePageInfo()
    }

    private fun prefetchBrowseNeighbors(key: BrowseCacheKey, total: Int) {
        val totalPages = (total + key.pageSize - 1) / key.pageSize
        listOf(key.page - 1, key.page + 1)
            .filter { it in 0 until totalPages }
            .forEach { page ->
                val neighbor = key.copy(page = page)
                val cached = synchronized(browsePageCache) { browsePageCache.containsKey(neighbor) }
                if (!cached) requestBrowsePage(neighbor, browseRequestVersion, false)
            }
    }

    private fun refreshCurrentCatalogPage() {
        catalogRefreshPending = false
        synchronized(browsePageCache) { browsePageCache.clear() }
        synchronized(browseCountCache) { browseCountCache.clear() }
        busy(false, getString(R.string.ui_a_0758))
        when {
            browseMode == "singer_list" -> loadSingerList(browseParam)
            browseMode == "category_list" -> loadCategoryPlaylistPage(activeSearchQuery)
            browseMode == "tag_group" -> loadTagGroupPage(browseParam.orEmpty())
            browseMode == "keyword_playlist" -> showKeywordPlaylistPage(browseParam.orEmpty())
            browseMode == "quick" -> loadQuickSearchPage(activeSearchQuery)
            currentTabIndex == 3 -> loadWordCounts()
            currentTabIndex == 7 -> loadFavorites()
            else -> loadBrowsePage()
        }
    }

    // 歌曲列表焦点锚定: 列表刷新(notifyDataSetChanged/重建行)会让当前获得焦点的行 View
    // 被回收, Android 随即把焦点回退到顶栏第一个可聚焦控件(中英文切换键)。与 ori 保持一致,
    // 刷新前记下焦点行的 marker, 刷新后两个时机重新聚焦到该行, 避免焦点跑偏。
    private data class SongFocusAnchor(val marker: String, val scope: String, val inputSerial: Int)
    private var songFocusAnchor: SongFocusAnchor? = null

    private fun songFocusScope(): String =
        listOf(currentPage, browseMode, browseParam, activeSearchQuery, browsePage, currentTabIndex).joinToString("\u0001")

    private fun rememberSongFocus(): SongFocusAnchor? {
        val list = songList ?: return null
        if (window.decorView.isInTouchMode) {
            songFocusAnchor = null
            return null
        }
        val scope = songFocusScope()
        songFocusAnchor?.takeIf { it.scope == scope && it.inputSerial == focusRestoreSerial }?.let { return it }
        val focused = window.decorView.findFocus()?.takeIf { isDescendantOf(it, list) }
        val marker = focused?.focusMarker()?.takeIf { it.startsWith(SongListAdapter.FOCUS_PREFIX) }
        return marker?.let { SongFocusAnchor(it, scope, focusRestoreSerial) }.also { songFocusAnchor = it }
    }

    private fun retainSongFocus(anchor: SongFocusAnchor?) {
        anchor ?: return
        val list = songList ?: return
        fun restore() {
            if (focusRestoreSerial != anchor.inputSerial || songFocusScope() != anchor.scope ||
                !window.decorView.hasWindowFocus() || window.decorView.isInTouchMode) return
            val exact = findViewByFocusMarker(list, anchor.marker)
            val target = exact?.takeIf { it.isShown && it.isFocusable }
                ?: findViewByFocusMarker(list, anchor.marker.substringBefore("|action|"))
                    ?.takeIf { it.isShown && it.isFocusable }
            if (target != null && !target.hasFocus()) target.requestFocus()
        }
        // ListView 会在下一次 layout 中移动焦点, 因此紧跟 layout 后再复查一次。
        list.post { restore() }
        list.postDelayed({ restore() }, 60L)
    }

    /**
     * 轻量刷新当前歌曲列表 Adapter (不重建), 用于下载状态变更后即时更新 UI。
     */
    private fun refreshSongAdapter() {
        val list = songList ?: return
        val anchor = rememberSongFocus()
        modernSongAdapter?.let { adapter ->
            if (list.adapter === adapter) adapter.notifyDataSetChanged()
        }
        retainSongFocus(anchor)
    }

    /**
     * 渲染歌曲列表
     */
    private fun renderSongListContents() {
        val list = songList ?: return
        val focusedView = window.decorView.findFocus()?.takeIf { isDescendantOf(it, list) }
        val focusedMarker = focusedView?.focusMarker()
            ?.takeIf { it.startsWith(SongListAdapter.FOCUS_PREFIX) }
        val oldSelectedPosition = list.selectedItemPosition
        val oldFocusedTop = focusedView?.top
        if (browseMode == "ordered") {
            visibleSongs.clear()
            visibleSongs.addAll(orderQueue.orEmpty())
        } else if (browseMode == "sang") {
            visibleSongs.clear()
            visibleSongs.addAll(sangHistory)
        }
        if (visibleSongs.isEmpty()) {
            modernSongAdapter = null
            list.adapter = tvAdapter().apply { add(getString(R.string.ui_a_0759)) }
            return
        }
        val mode = when (browseMode) {
            "rank", "ordered", "downloads", "sang" -> browseMode
            else -> "catalog"
        }
        // 排行榜: 行间留 2dp 透明间隔, 行高=可视高度/10(扣除间隔)精确铺满, 不出滚动条。
        // 其它目录页: 行间留 6dp 透明间隔, 让圆角背景块边界清晰。
        val rowGapPx = if (mode == "rank") dp(2) else dp(6)
        list.divider = ColorDrawable(Color.TRANSPARENT)
        list.dividerHeight = rowGapPx
        val pageOffset = if (mode == "rank") browsePage * browsePageSize() else 0
        val existing = modernSongAdapter?.takeIf { it.matches(mode, pageOffset) && list.adapter === it }
        // 分页网格/排行列表: 行高自适应铺满屏幕(2列目录每页5行, 排行单列每页10行);
        // 可滚动的队列/下载/已唱列表保持自然行高(rowsPerPage=0)。
        val rowsPerPage = when (mode) {
            "catalog" -> 5
            "rank" -> 10
            else -> 0
        }
        val listAdapter = existing ?: SongListAdapter(
            this,
            visibleSongs,
            mode,
            pageOffset,
            btnPrevPage?.id ?: View.NO_ID,
            btnNextPage?.id ?: View.NO_ID,
            { song -> songRowState(song) },
            SongListCallbacks(
                onPrimary = { song -> handleSongPrimary(song) },
                onDownload = { song -> downloadSong(song) },
                onMore = { song -> showSongActions(song) },
                onTop = { song -> moveQueuedSongToNext(song) },
                onDelete = { song ->
                    if (browseMode == "downloads") deleteDownloadedSong(song) else deleteQueuedSong(song)
                },
                onPauseResume = { song -> toggleDownloadPause(song) },
            ),
            initialRowHeightPx = 0,
            rowsPerPage = rowsPerPage,
        ).also {
            modernSongAdapter = it
            list.adapter = it
        }
        if (existing != null) listAdapter.notifyDataSetChanged()
        // 布局完成后按实际可视高度拉满各行, 消除投影/高分屏底部空白。
        // 排行榜(rank): 行高 = 可视高度/10 精确铺满(exactFill), 无间隔无滚动条。
        // 目录(catalog): 行高缩为 0.9 倍, 保留 6dp 间隔。
        if (rowsPerPage > 0) {
            val isRank = mode == "rank"
            val heightScale = if (mode == "catalog") 0.9f else 1f
            list.post {
                if (list.adapter !== listAdapter) return@post
                // ListView 的 height 含 padding, 行项绘制在 padding 之内; 排行榜要精确铺满须扣掉上下 padding。
                val usableHeight = if (isRank) list.height - list.paddingTop - list.paddingBottom else list.height
                listAdapter.fitRowHeight(usableHeight, rowGapPx, heightScale, isRank)
            }
        }

        val targetPosition = focusedMarker?.let(listAdapter::adapterPositionFor)
            ?: oldSelectedPosition.takeIf { it >= 0 }
        if (targetPosition != null && focusedMarker != null) {
            list.setSelectionFromTop(targetPosition, oldFocusedTop ?: 0)
            list.post {
                if (list.adapter !== listAdapter) return@post
                list.setSelection(targetPosition)
                val row = list.getChildAt(targetPosition - list.firstVisiblePosition)
                val target = row?.let { findViewByFocusMarker(it, focusedMarker) }
                if (target != null) {
                    target.requestFocus()
                    target.refreshDrawableState()
                    target.jumpDrawablesToCurrentState()
                    target.invalidate()
                }
            }
        }
    }

    private fun renderSongList() {
        val anchor = rememberSongFocus()
        renderSongListContents()
        retainSongFocus(anchor)
    }

    private fun findViewByFocusMarker(root: View, marker: String): View? {
        if (root.focusMarker() == marker) return root
        if (root is ViewGroup) repeat(root.childCount) { index ->
            findViewByFocusMarker(root.getChildAt(index), marker)?.let { return it }
        }
        return null
    }

    private fun songRowState(song: Song): SongRowState {
        val task = downloads.lastOrNull { stableId(it.song) == stableId(song) }
        val taskState = task?.state.orEmpty()
        return SongRowState(
            current = currentSong?.equals(song) == true,
            queued = orderQueue!!.contains(song),
            local = hasPlayableLocalFile(song),
            downloading = isDownloading(song),
            failed = taskState.startsWith("失败"),
            paused = taskState.contains("暂停") || taskState.contains("中断"),
            progress = task?.progress ?: SongOkDownloadManager.getDownloadProgress(song),
        )
    }

    private fun handleSongPrimary(song: Song) {
        var rendered = false
        when (browseMode) {
            "ordered" -> {
                if (currentSong?.equals(song) == true) toast(getString(R.string.ui_p_098, song.title))
                else moveQueuedSongToNext(song)
                rendered = true
            }
            "downloads" -> addToQueue(song)
            "sang" -> addToQueue(song)
            else -> addToQueue(song)
        }
        persistRuntimeState()
        if (!rendered) renderSongList()
    }

    private fun moveQueuedSongToNext(song: Song) {
        if (currentSong?.equals(song) == true) return
        orderQueue!!.remove(song)
        val target = if (currentSong != null && orderQueue!!.isNotEmpty()) 1 else 0
        orderQueue.add(target.coerceAtMost(orderQueue.size), song)
        persistRuntimeState()
        syncPlayerInfo()
        renderSongList()
    }

    private fun deleteQueuedSong(song: Song) {
        if (currentSong?.equals(song) == true) {
            toast(getString(R.string.ui_a_0581))
            return
        }
        orderQueue!!.remove(song)
        persistRuntimeState()
        syncPlayerInfo()
        renderSongList()
    }

    private fun toggleDownloadPause(song: Song) {
        val task = downloads.lastOrNull { stableId(it.song) == stableId(song) }
        if (isDownloading(song)) {
            SongOkDownloadManager.cancelDownload(song)
            task?.state = KtvRaw.get(R.string.ui_a_0534)
            stateIo.execute { stateDatabase.updateDownload(song, "paused", task?.progress ?: 0) }
            renderSongList()
        } else {
            downloadSong(song)
        }
    }

    private fun deleteDownloadedSong(song: Song) {
        if (currentSong?.equals(song) == true) {
            toast(getString(R.string.ui_a_0581))
            return
        }
        SongOkDownloadManager.cancelDownload(song)
        val files = listOfNotNull(song.path?.let(::File), SongOkDownloadManager.getLocalFile(song))
            .distinctBy { it.absolutePath }
        io.execute {
            val failed = files.filter { file ->
                if (!file.exists()) return@filter false
                cleanupSidecars(file)
                !runCatching { file.delete() }.getOrDefault(false)
            }
            if (failed.isEmpty()) {
                song.path = null
                runCatching { stateDatabase.removeDownload(song) }
                main.post {
                    downloads.removeAll { stableId(it.song) == stableId(song) }
                    if (browseMode == "downloads") loadDownloadedList() else reloadCurrentBrowsePage()
                    toast(getString(R.string.ui_p_042))
                }
            } else {
                main.post { toast(getString(R.string.ui_p_043)) }
            }
        }
    }

    /**
     * 更新分页信息
     */
    private fun updatePageInfo() {
        textPageInfo!!.setText(String.format(getString(R.string.ui_a_0765), browsePage + 1, max(1, browseTotalPages)))
    }

    /**
     * 上一页
     */
    private fun prevPage() {
        if (browsePage > 0) {
            browsePage--
            reloadCurrentBrowsePage()
        }
    }

    /**
     * 下一页
     */
    private fun nextPage() {
        if (browsePage < browseTotalPages - 1) {
            browsePage++
            reloadCurrentBrowsePage()
        }
    }

    private fun reloadCurrentBrowsePage() {
        when (browseMode) {
            "singer_list" -> loadSingerList(browseParam, activeSearchQuery)
            "local" -> loadLocalCatalogPage()
            "category_list" -> loadCategoryPlaylistPage(activeSearchQuery)
            "tag_group" -> loadTagGroupPage(browseParam.orEmpty())
            "keyword_playlist" -> showKeywordPlaylistPage(browseParam.orEmpty())
            "quick" -> loadQuickSearchPage(activeSearchQuery)
            else -> loadBrowsePage()
        }
    }

    /**
     * 显示已点列表对话框
     */
    private fun showQueueDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.ui_a_0766) + orderQueue!!.size + ")")
        if (orderQueue.isEmpty()) {
            builder.setMessage(getString(R.string.ui_a_0767))
            builder.setPositiveButton(getString(R.string.ui_ok), null)
        } else {
            val items: Array<String> = Array(orderQueue.size) { "" }
            for (i in orderQueue.indices) {
                val s = orderQueue.get(i)
                val marker = if (currentSong != null && currentSong!!.equals(s)) " ▶" else ""
                items[i] = (i + 1).toString() + ". " + s.title + " - " + s.singer + marker
            }
            builder.setItems(
                items,
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    val selected = orderQueue.get(which)
                    if (currentSong != null && currentSong!!.equals(selected)) {
                        toast(getString(R.string.ui_p_038) + selected.title)
                    } else {
                        // 只设为下一首，不打断当前歌曲。
                        moveQueuedSongToNext(selected)
                    }
                })
            builder.setNegativeButton(
                getString(R.string.ui_a_0768),
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    // 显示删除对话框
                    val delItems: Array<String> = Array(orderQueue.size) { "" }
                    for (i in orderQueue.indices) {
                        delItems[i] = orderQueue.get(i).title + " - " + orderQueue.get(i).singer
                    }
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.ui_a_0769))
                        .setItems(
                            delItems,
                            DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                                val removed = orderQueue.removeAt(w)
                                if (currentSong != null && currentSong!!.equals(removed)) {
                                    playNext()
                                }
                                updateOrderBadge()
                                saveState()
                                if (currentTabIndex == 4) loadOrderedList()
                                toast(getString(R.string.ui_p_039) + removed.title)
                            })
                        .create().showForTv()
                })
            builder.setNeutralButton(
                KtvRaw.get(R.string.ui_a_0770),
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.ui_a_0771))
                        .setMessage(getString(R.string.ui_a_0772) + orderQueue.size + getString(R.string.ui_a_0773))
                        .setPositiveButton(
                            getString(R.string.ui_ok),
                            DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                                orderQueue.clear()
                                if (player != null) player!!.stopPlayback()
                                playbackPreparing = false
                                playWhenPrepared = false
                                currentMediaPlayer = null
                                releaseVocalPlayer()
                                currentSong = null
                                clearLyrics()
                                updateBottomBar(null, false)
                                updateOrderBadge()
                                saveState()
                                if (currentTabIndex == 4) loadOrderedList()
                                toast(getString(R.string.ui_a_0774))
                            })
                        .setNegativeButton(getString(R.string.ui_cancel), null)
                        .create().showForTv()
                })
        }
        builder.create().showForTv()
    }

    /**
     * 更新已点数量徽标(顶栏 + 子页面旧按钮)
     */
    private fun updateOrderBadge() {
        val n = if (orderQueue == null) 0 else orderQueue.size
        if (textTopOrderBadge != null) textTopOrderBadge!!.setText(n.toString())
        if (textOrderBadge != null) textOrderBadge!!.setText(n.toString())
    }

    /**
     * 添加歌曲到已点列表
     */
    private fun addToQueue(song: Song) {
        if (orderQueue!!.contains(song)) {
            toast(getString(R.string.ui_p_040) + song.title)
            return
        }
        if (!isSongReadyForQueue(song)) {
            val id = stableId(song)
            val newlyPending = pendingQueueSongIds.add(id)
            toast(
                if (newlyPending) getString(R.string.ui_p_099, song.title)
                else getString(R.string.ui_p_100, song.title),
            )
            if (!isDownloading(song)) downloadSong(song)
            return
        }
        addReadySongToQueue(song)
    }

    private fun isSongReadyForQueue(song: Song): Boolean =
        hasPlayableLocalFile(song) || SongOkDownloadManager.isDownloaded(song)

    private fun addReadySongToQueue(song: Song) {
        if (orderQueue!!.contains(song)) return
        val wasQueueEmpty = orderQueue!!.isEmpty()
        orderQueue.add(song)
        song.playCount++
        if (::stateDatabase.isInitialized) runCatching {
            stateIo.execute { stateDatabase.markSongClicked(song) }
        }
        saveState()
        updateOrderBadge()
        if (currentPage == "已点" || currentPage == "首页") renderQueue()
        toast(getString(R.string.ui_p_041) + song.title + (if (song.singer != null) " - " + song.singer else ""))
        if (wasQueueEmpty) play(song)
    }

    private fun scheduleAutoFullscreen() {
        main.removeCallbacks(autoFullscreenRunnable)
        if (autoFullscreenSeconds > 0 && currentSong != null && playWhenPrepared && !isFullScreen) {
            main.postDelayed(autoFullscreenRunnable, autoFullscreenSeconds * 1000L)
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (!isFullScreen) scheduleAutoFullscreen()
    }

    private fun busy(show: Boolean, message: String?) {
        if (progress != null) {
            progress!!.setVisibility(if (show) View.VISIBLE else View.GONE)
        }
        if (status != null) {
            status!!.setText(message)
        }
    }

    private fun toast(text: String?) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun prefs(): SharedPreferences? {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
    }

    /**
     * 将当前运行时状态同步到 KtvStore 并持久化到磁盘。
     * 
     * 
     * 供 Fragment 在修改已点队列、收藏等数据后调用,确保状态不丢失。
     */
    fun saveState() {
        store.loopOne = loopOne
        store.autoNext = autoNext
        store.originalVocal = originalVocal
        store.vocalChannelMode = vocalChannelMode
        store.musicVolume = musicVolume
        store.micVolume = micVolume
        // 均衡器状态同样随设置一起持久化(下次启动自动恢复)。
        store.eqEnabled = KtvEqualizer.enabled
        store.eqPreset = KtvEqualizer.preset
        store.eqLevels = KtvEqualizer.levelsSnapshot().joinToString(",")
        store.eqGlobalMix = KtvEqualizer.globalMix
        store.tone = tone
        store.atmosphere = atmosphere
        store.singMode = singMode
        store.recordEnabled = recordEnabled
        store.scoreEnabled = scoreEnabled
        store.marqueeEnabled = marqueeEnabled
        store.marqueeText = marqueeText!!
        store.playWhileDownloading = playWhileDownloading
        store.clearDownloadsOnBoot = clearDownloadsOnBoot
        store.autoFullscreenSeconds = autoFullscreenSeconds
        store.showUsbSongs = showUsbSongs
        store.autoDeleteSongs = autoDeleteSongs
        store.reserveStorageGb = reserveStorageGb
        store.floatingButtonEnabled = floatingButtonEnabled
        store.songTitleSubtitleEnabled = songTitleSubtitleEnabled
        store.orderedSongOriginal = orderedSongOriginal
        store.orderedVolumeMode = orderedVolumeMode
        store.orderedVocalMode = orderedVocalMode
        store.voiceEngineEnabled = voiceEngineEnabled
        store.voiceEngineVolume = voiceEngineVolume
        store.lightMode = lightMode!!
        store.audioDelayMs = audioDelayMs
        store.screenBrightness = screenBrightness
        store.screenMode = screenMode
        store.doubleScreenEnabled = doubleScreenEnabled
        store.tableBroadcastEnabled = tableBroadcastEnabled
        store.tableBroadcastSeconds = tableBroadcastSeconds
        store.tableBroadcastText = tableBroadcastText!!
        val storeSnapshot = store.snapshotJson()
        runCatching { stateIo.execute { store.writeSnapshot(storeSnapshot) } }
        persistRuntimeState()
    }

    private fun persistRuntimeState() {
        if (!::stateDatabase.isInitialized) return
        val playbackState = when {
            currentSong == null -> "idle"
            playbackPreparing && playWhenPrepared -> "preparing"
            !playWhenPrepared -> "paused"
            player?.isPlaying == true -> "playing"
            else -> "queued"
        }
        val queueSnapshot = orderQueue.orEmpty().take(200).map { Song.fromJson(it.toJson()) }
        val historySnapshot = sangHistory.take(200).map { Song.fromJson(it.toJson()) }
        val currentId = currentSong?.let(::stableId)
        val currentSnapshot = currentId?.let { id ->
            queueSnapshot.firstOrNull { stableId(it) == id }
        }
        runCatching {
            stateIo.execute {
                stateDatabase.syncQueue(queueSnapshot, historySnapshot, currentSnapshot, playbackState)
            }
        }
    }

    private fun loadStateFromStore() {
        loopOne = store.loopOne
        autoNext = store.autoNext
        originalVocal = store.originalVocal
        // 第二道防线：状态里的演唱模式/声道模式必须是合法内部键，非法脏值(尤其"静音练唱")
        // 会让播放器静音且旧界面无入口改回，这里统一回退默认，避免覆盖安装后永久无声。
        vocalChannelMode = if (store.vocalChannelMode in KtvStore.VOCAL_CHANNEL_MODES)
            store.vocalChannelMode else KtvStore.DEFAULT_VOCAL_CHANNEL_MODE
        musicVolume = store.musicVolume
        // 均衡器：先恢复保存值，再按本机真实频段数对齐(不同设备频段数/增益范围不同)。
        KtvEqualizer.restore(
            enabled = store.eqEnabled,
            presetName = store.eqPreset,
            savedLevels = parseEqLevels(store.eqLevels),
            useGlobalMix = store.eqGlobalMix && KtvEqualizer.globalMixSupported(),
        )
        KtvEqualizer.alignToDevice(KtvEqualizer.capabilities(this))
        micVolume = store.micVolume
        tone = store.tone.coerceIn(-5, 5)
        atmosphere = store.atmosphere
        // 同上：演唱模式脏值归一化，防止"静音练唱"残留导致永久无声。
        singMode = if (store.singMode in KtvStore.SING_MODES)
            store.singMode else KtvStore.DEFAULT_SING_MODE
        recordEnabled = store.recordEnabled
        scoreEnabled = store.scoreEnabled
        marqueeEnabled = store.marqueeEnabled
        marqueeText = store.marqueeText
        playWhileDownloading = store.playWhileDownloading
        clearDownloadsOnBoot = store.clearDownloadsOnBoot
        autoFullscreenSeconds = store.autoFullscreenSeconds
        showUsbSongs = store.showUsbSongs
        autoDeleteSongs = store.autoDeleteSongs
        reserveStorageGb = store.reserveStorageGb
        floatingButtonEnabled = store.floatingButtonEnabled
        songTitleSubtitleEnabled = store.songTitleSubtitleEnabled
        orderedSongOriginal = store.orderedSongOriginal
        orderedVolumeMode = store.orderedVolumeMode
        orderedVocalMode = store.orderedVocalMode
        voiceEngineEnabled = store.voiceEngineEnabled
        voiceEngineVolume = store.voiceEngineVolume
        lightMode = store.lightMode
        audioDelayMs = store.audioDelayMs
        screenBrightness = store.screenBrightness
        screenMode = store.screenMode
        doubleScreenEnabled = store.doubleScreenEnabled
        tableBroadcastEnabled = store.tableBroadcastEnabled
        tableBroadcastSeconds = store.tableBroadcastSeconds
        tableBroadcastText = store.tableBroadcastText
    }

    private fun dp(value: Int): Int {
        return (value * getResources().getDisplayMetrics().density + 0.5f).toInt()
    }

    private fun hasStoragePermission(): Boolean =
        Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= 23
            && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf<String>(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ), 10
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            recreate()
        }
        if (requestCode == 11 && grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startRecording()
        if (requestCode == REQUEST_MIC_PERMISSION && grantResults.size > 0 &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            micActivator.start()
            Log.i(TAG, "麦克风权限已授予, 麦克风输入激活")
        } else if (requestCode == REQUEST_MIC_PERMISSION) {
            Log.w(TAG, "麦克风权限被拒绝: 部分设备将无麦克风演唱声音输出")
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        focusRestoreSerial++
        val code = event.getKeyCode()
        if (isFullScreen) {
            val center = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                code == KeyEvent.KEYCODE_NUMPAD_ENTER
            val direction = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
            if (center && !fullScreenChromeVisible) {
                setFullScreenChromeVisible(true)
                return true
            }
            if (center && fullScreenControls?.hasFocus() != true) {
                setFullScreenChromeVisible(true)
                fullScreenControls?.getChildAt(0)?.requestFocus()
                return true
            }
            if (direction && !fullScreenChromeVisible) {
                setFullScreenChromeVisible(true)
                return true
            }
            if ((center || direction) && fullScreenChromeVisible) {
                main.removeCallbacks(hideFullScreenChromeRunnable)
                main.postDelayed(hideFullScreenChromeRunnable, 5000L)
            }
        }
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_SPACE) {
            togglePlay()
            return true
        }
        if (code == KeyEvent.KEYCODE_MEDIA_NEXT) {
            playNext()
            return true
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (isFullScreen) {
                exitFullScreenPlayer()
                return true
            }
            if (currentTabIndex != -1 || KtvRaw.get(R.string.ui_a_0108) != currentPage) {
                navigateBack()
                return true
            }
        }
        val homeDirection = homeLayout?.visibility == View.VISIBLE && currentPage == "首页" &&
            (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)
        if (homeDirection) {
            val focused = window.decorView.findFocus()
            val reverseTarget = lastHomeFocusSource
            if (focused === lastHomeFocusTarget && code == lastHomeFocusReverseKey &&
                reverseTarget?.isAttachedToWindow == true && reverseTarget.isShown && reverseTarget.isEnabled
            ) {
                if (reverseTarget.requestFocus()) {
                    lastHomeFocusSource = focused
                    lastHomeFocusTarget = reverseTarget
                    lastHomeFocusReverseKey = oppositeDpadKey(code)
                    return true
                }
                clearHomeFocusTransition()
            }

            val handled = super.dispatchKeyEvent(event)
            main.post {
                val destination = window.decorView.findFocus()
                if (focused != null && destination != null && destination !== focused &&
                    homeLayout?.visibility == View.VISIBLE && currentPage == "首页"
                ) {
                    lastHomeFocusSource = focused
                    lastHomeFocusTarget = destination
                    lastHomeFocusReverseKey = oppositeDpadKey(code)
                }
            }
            return handled
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.post { prepareTvFocusableTree(window.decorView) }
    }

    private fun navigateBack() {
        val returnPoint = focusReturnStack.pollLast()
        if (returnPoint != null) {
            restoringFocusRoute = true
            try {
                returnPoint.restorePage()
            } finally {
                restoringFocusRoute = false
            }
            restoreFocusBookmark(returnPoint.focus, returnPoint.directFocus)
            return
        }
        when (browseMode) {
            "playlist_id", "playlist_id_search" -> returnToCategoryPlaylists()
            "settings_section" -> showSettingsPage()
            else -> showHomePage()
        }
    }

    private fun navigateBackToHome() {
        while (focusReturnStack.size > 1) focusReturnStack.pollLast()
        if (focusReturnStack.isNotEmpty()) navigateBack() else showHomePage()
    }

    private fun rememberReturnPointIfNeeded(targetPage: String) {
        if (restoringFocusRoute || targetPage == currentPage) return
        val focus = captureFocusBookmark(window.decorView.findFocus())
        val fromHome = homeLayout?.visibility == View.VISIBLE || currentPage == "首页"
        if (fromHome) {
            focusReturnStack.clear()
            focusReturnStack.addLast(FocusReturnPoint({ showHomePage() }, focus, window.decorView.findFocus()))
            return
        }
        val currentDepth = currentPage.count { it == '/' }
        val targetDepth = targetPage.count { it == '/' }
        if (targetDepth <= currentDepth) return

        val savedMode = browseMode
        val savedParam = browseParam
        val savedPage = browsePage
        val savedQuery = activeSearchQuery
        val savedCategory = currentCategories.getOrNull(currentCategoryIndex)
        val restorePage: () -> Unit = when (savedMode) {
            "singer_list" -> ({
                loadSingers()
                browsePage = savedPage
                savedCategory?.let { browseParam = it }
                activeSearchQuery = savedQuery
                loadSingerList(savedCategory ?: KtvRaw.get(R.string.ui_a_0069), savedQuery)
            })
            "category_list" -> ({
                searchScope = "playlist"
                activeSearchQuery = savedQuery
                setSearchText(savedQuery)
                currentTabIndex = 2
                showSubPageShell(KtvRaw.get(R.string.ui_a_0652), getString(R.string.ui_a_0652))
                setupTabs()
                currentCategories.clear()
                currentCategoryIndex = -1
                updateCategories()
                browseMode = "category_list"
                browseParam = savedParam
                browsePage = savedPage
                loadCategoryPlaylistPage(savedQuery)
            })
            "settings" -> ({ showSettingsPage(false) })
            else -> ({ showHomePage() })
        }
        focusReturnStack.addLast(FocusReturnPoint(restorePage, focus, window.decorView.findFocus()))
    }

    private fun captureFocusBookmark(view: View?): FocusBookmark? {
        if (view == null) return null
        val resourceName = if (view.id != View.NO_ID && (view.id ushr 24) != 0) {
            runCatching { resources.getResourceName(view.id) }.getOrNull()
        } else null
        val marker = view.focusMarker()
        val text = (view as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() }
        val groupText = if (view is ViewGroup) collectFocusText(view).takeIf { it.isNotBlank() } else null
        return FocusBookmark(resourceName, marker, text, groupText)
    }

    private fun collectFocusText(view: View): String {
        val values = ArrayList<String>(3)
        fun collect(node: View) {
            if (values.size >= 3) return
            if (node is TextView) {
                node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(values::add)
            } else if (node is ViewGroup) {
                for (index in 0 until node.childCount) collect(node.getChildAt(index))
            }
        }
        collect(view)
        return values.joinToString("|")
    }

    private fun restoreFocusBookmark(bookmark: FocusBookmark?, preferredView: View? = null) {
        if (bookmark == null && preferredView == null) return
        val serial = ++focusRestoreSerial
        val settingsList = songList
        val settingsAdapter = settingsList?.adapter as? SettingsListAdapter
        val settingsPosition = bookmark?.marker?.let { marker ->
            settingsAdapter?.positionForMarker(marker)
        }
        if (settingsList != null && settingsAdapter != null && settingsPosition != null) {
            settingsAdapter.requestFocusAt(settingsList, settingsPosition)
            return
        }
        var restored = false
        listOf(0L, 60L, 180L, 450L, 900L).forEach { delay ->
            main.postDelayed({
                if (restored || serial != focusRestoreSerial) return@postDelayed
                val target = preferredView?.takeIf { it.isAttachedToWindow && it.isShown && it.isEnabled && it.isFocusable }
                    ?: bookmark?.let { findFocusTarget(window.decorView, it) }
                if (target != null) {
                    restored = target.requestFocus() || target.requestFocusFromTouch()
                    if (restored) {
                        target.refreshDrawableState()
                        target.jumpDrawablesToCurrentState()
                        target.invalidate()
                    }
                }
            }, delay)
        }
    }

    private fun findFocusTarget(root: View, bookmark: FocusBookmark): View? {
        var textMatch: View? = null
        var groupMatch: View? = null
        fun visit(view: View): View? {
            if (view.visibility != View.VISIBLE || !view.isShown || !view.isEnabled) return null
            if (view.isFocusable) {
                val resourceName = if (view.id != View.NO_ID) {
                    runCatching { resources.getResourceName(view.id) }.getOrNull()
                } else null
                if (bookmark.resourceName != null && resourceName == bookmark.resourceName) return view
                if (bookmark.marker != null && view.focusMarker() == bookmark.marker) return view
                if (textMatch == null && bookmark.text != null &&
                    (view as? TextView)?.text?.toString() == bookmark.text
                ) textMatch = view
                if (groupMatch == null && bookmark.groupText != null && view is ViewGroup &&
                    collectFocusText(view) == bookmark.groupText
                ) groupMatch = view
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        return visit(root) ?: textMatch ?: groupMatch
    }

    private fun isDescendantOf(view: View, ancestor: View?): Boolean {
        if (ancestor == null) return false
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun prepareTvFocusableTree(view: View) {
        if (view.isClickable && view.visibility == View.VISIBLE && view.isEnabled) {
            TvFocusStyler.install(view)
            if (view.contentDescription.isNullOrBlank()) {
                val spoken = when (view) {
                    is TextView -> view.text?.toString()?.trim().orEmpty()
                    is ViewGroup -> view.visibleTextLabel()
                    else -> ""
                }
                if (spoken.isNotEmpty()) view.contentDescription = spoken
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) prepareTvFocusableTree(view.getChildAt(index))
        }
    }

    private fun showFavorites() {
        setPage("收藏", getString(R.string.ui_a_0572))
        content!!.removeAllViews()
        val panel = panel()
        content!!.addView(panel, LinearLayout.LayoutParams(-1, -1))
        panel.addView(sectionTitle(getString(R.string.ui_a_0094)))
        songList = listView()
        songAdapter = tvAdapter()
        songList!!.setAdapter(songAdapter)
        songList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: kotlin.Long ->
            handleSong(
                visibleSongs.get(pos)
            )
        })
        songList!!.setOnItemLongClickListener(OnItemLongClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: kotlin.Long ->
            showSongActions(visibleSongs.get(pos))
            true
        })
        panel.addView(songList, LinearLayout.LayoutParams(-1, 0, 1f))
        visibleSongs.clear()
        for (song in library.allSongs()) {
            if (store.isFavorite(song)) visibleSongs.add(song)
        }
        renderSongs()
    }

    private fun showPlaylists() {
        setPage("歌单", getString(R.string.ui_a_0779))
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(300), -1))
        left.addView(sectionTitle(getString(R.string.ui_a_0157)))
        for (playlist in store.playlists) {
            left.addView(
                button(
                    playlist + "  " + store.songsInPlaylist(playlist).size,
                    View.OnClickListener { v: View? -> showPlaylistSongs(playlist) })
            )
        }
        left.addView(
            button(
                getString(R.string.ui_a_0605),
                View.OnClickListener { v: View? -> showCreatePlaylistDialog() })
        )
        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle(getString(R.string.ui_a_0780)))
        right.addView(
            label(
                getString(R.string.ui_a_0781),
                20,
                Color.LTGRAY
            )
        )
    }

    private fun showPlaylistSongs(playlist: String) {
        setPage("歌单", getString(R.string.ui_a_0779))
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(300), -1))
        left.addView(sectionTitle(getString(R.string.ui_a_0157)))
        for (item in store.playlists) {
            left.addView(
                button(
                    (if (item == playlist) "> " else "") + item + "  " + store.songsInPlaylist(item).size,
                    View.OnClickListener { v: View? -> showPlaylistSongs(item) })
            )
        }
        left.addView(
            button(
                getString(R.string.ui_a_0605),
                View.OnClickListener { v: View? -> showCreatePlaylistDialog() })
        )

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle(playlist))
        songList = listView()
        songAdapter = tvAdapter()
        songList!!.setAdapter(songAdapter)
        songList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: kotlin.Long ->
            handleSong(
                visibleSongs.get(pos)
            )
        })
        songList!!.setOnItemLongClickListener(OnItemLongClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: kotlin.Long ->
            showSongActions(visibleSongs.get(pos))
            true
        })
        right.addView(songList, LinearLayout.LayoutParams(-1, 0, 1f))
        visibleSongs.clear()
        for (song in library.allSongs()) {
            if (store.isInPlaylist(playlist, song)) visibleSongs.add(song)
        }
        renderSongs()
    }

    private fun showPlaylistPicker(song: Song) {
        val items: Array<String> = Array(store.playlists.size) { "" }
        for (i in store.playlists.indices) {
            val playlist = store.playlists.get(i)
            items[i] = (if (store.isInPlaylist(playlist, song)) getString(R.string.ui_a_0782) else getString(R.string.ui_a_0783)) + playlist
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_a_0784))
            .setItems(
                items,
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    val playlist = store.playlists.get(which)
                    val added = store.togglePlaylistSong(playlist, song)
                    saveState()
                    toast((if (added) getString(R.string.ui_a_0785) else getString(R.string.ui_a_0786)) + playlist)
                    if (KtvRaw.get(R.string.ui_a_0779) == currentPage) showPlaylistSongs(playlist)
                })
            .create().showForTv()
    }

    companion object {
        // ===== 歌曲源设备身份(用于 w.w345.my 等歌曲源的保活/认证伪装) =====
        // 歌曲源通过共享后台按 device 判定合法性, App 会向 hbeat.ktvsky.com
        // 周期心跳(whoami)维持设备活跃;本 App 若不做心跳,设备可能被判为不合法而限流。
        // 设备 MAC 取本机「当前在用网卡」的硬件地址(见 DeviceMac, 读不到时回退历史抓包值);
        // 抓包时原版 App 的 _mac / androidid / _cid 三者同值, 这里保持该一致性。
        // sn 见 KtvJsBridge.DEVICE_SN。
        private const val LEGACY_PACKAGE = "com.origjoy.local.ktv"
        private const val LEGACY_KTV_ID = "10006789"        // 门店/包厢 id
        private const val HEARTBEAT_INTERVAL_MS = 3600_000L
        /** 公放候选预热时，外部目录扫描（可能遍历 /storage/ 各挂载点）的超时上限。 */
        private const val RANDOM_SCAN_TIMEOUT_MS = 4_000L

        // ===== 主题色(对标原版麦动 配色) =====
        /** 主背景:极深蓝黑(原版 #080C12 系)  */
        private val BG = Color.rgb(8, 12, 18)

        /** 面板背景:深蓝灰(原版卡片底色)  */
        private val PANEL = Color.rgb(22, 31, 42)

        /** 次级面板:更深(列表区背景)  */
        private val PANEL_2 = Color.rgb(15, 22, 31)

        /** 焦点色:青绿色(原版选中态)  */
        private val FOCUS = Color.rgb(39, 204, 164)

        /** 金色:标题与高亮(原版 color_yellow_3 #FFE363 系)  */
        private val GOLD = Color.rgb(245, 190, 89)

        /** 强调红:选中态/激活态(原版 color_red #FD3359)  */
        private val ACCENT_RED = Color.rgb(253, 51, 89)

        /** 文本白:主文本色(原版 color_white_3 #F1F1F1)  */
        private val TEXT_WHITE = Color.rgb(241, 241, 241)

        /** 次要文本:70% 透明白(原版 color_white_3_70)  */
        private val TEXT_DIM = Color.argb(180, 241, 241, 241)

        /** 导航选中背景:半透明红  */
        private val NAV_SELECTED = Color.argb(50, 253, 51, 89)

        /** 分割线颜色  */
        private val DIVIDER = Color.argb(80, 241, 241, 241)
        private const val PREFS = "ktv"
        private const val KEY_CATALOG_URL = "catalog_url"
        private const val KEY_SHOW_MOBILE_QR = "show_mobile_qr"
        private const val REQUEST_MIC_PERMISSION = 12

        private const val TAG = "MainActivity"
    }
}
