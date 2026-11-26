const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => document.querySelectorAll(selector);

const I18N = {
  zh: {
    title: "麦动 KTV 手机点歌",
    offline: "连接中断，正在重新连接…",
    connecting: "正在连接麦动 KTV…",
    connected: "已连接麦动 KTV",
    queueBadge: "已点歌曲",
    nowPlaying: "正在播放",
    noSong: "暂无歌曲",
    pickSong: "请先点一首喜欢的歌",
    replay: "重唱",
    pause: "暂停",
    resume: "继续",
    next: "切歌",
    soundControl: "声音控制",
    instant: "即时生效",
    accompany: "伴唱",
    original: "原唱",
    vocalBadgeAccompany: "伴",
    vocalBadgeOriginal: "原",
    musicVolume: "音乐音量",
    nextUp: "下一首",
    emptyQueue: "队列为空",
    viewQueue: "查看队列",
    search: "搜索",
    hotRanking: "热歌排行",
    loading: "正在加载…",
    prevPage: "上一页",
    nextPage: "下一页",
    page: "第 {n} 页",
    pageOf: "第 {x}/{y} 页",
    playQueue: "PLAY QUEUE",
    queuedSongs: "已点歌单",
    songCount: "{n} 首",
    pinTip: "置顶歌曲将排在当前歌曲之后",
    remote: "遥控",
    searchNav: "搜歌",
    downloadsNav: "下载",
    rankNav: "排行榜",
    queuedNav: "已点",
    downloadsEyebrow: "MY MUSIC",
    downloadedSongs: "已下载歌曲",
    downloadedList: "本地曲库",
    downloadsTabsAria: "下载分类",
    downloadsTabDownloaded: "已下载",
    downloadsTabActive: "下载中",
    downloadingSongs: "下载中歌曲",
    downloadQueue: "下载队列",
    downloadsSummary: "第 {a}-{b} 首 · 已下载",
    activeDownloadsSummary: "共 {n} 首",
    noDownloads: "还没有已下载的歌曲",
    noDownloadsHint: "在搜歌结果中下载后即可在本地点播",
    downloadsFailed: "已下载歌曲加载失败",
    noActiveDownloads: "暂无下载中或待下载歌曲",
    noActiveDownloadsHint: "在搜歌结果中下载后会显示在这里",
    activeDownloadsFailed: "下载任务加载失败",
    downloadStateWaiting: "等待下载",
    downloadStateDownloading: "下载中",
    downloadStateCancelling: "正在取消",
    downloadSpeedKb: "{n} KB/s",
    downloadSpeedMb: "{n} MB/s",
    rankEyebrow: "CHARTS",
    rankings: "排行榜",
    rankHint: "选择榜单",
    rankSummary: "第 {a}-{b} 名",
    noRank: "暂无榜单数据",
    rankFailed: "排行榜加载失败",
    rankCategoriesFailed: "榜单分类加载失败",
    rankSoaring: "飙升榜",
    rankNewSongs: "新歌榜",
    rankHotSongs: "热歌榜",
    rankChinese: "华语榜",
    rankDuet: "情歌对唱榜",
    rankSoundtrack: "影视金曲榜",
    rankClassic: "经典老歌榜",
    rankMainland: "内地榜",
    rankHkTw: "港台榜",
    rankCantonese: "粤语榜",
    rankWestern: "欧美榜",
    rankJapanese: "日语榜",
    langMandarin: "国语",
    langChinese: "中文",
    langCantonese: "粤语",
    langHokkien: "闽南语",
    langTaiwanese: "台语",
    langEnglish: "英语",
    langJapanese: "日语",
    langKorean: "韩语",
    langDialect: "地方语言",
    langTibetan: "藏语",
    langTeochew: "潮汕语",
    langIndian: "印度语",
    langKhmer: "高棉语",
    langItalian: "意大利语",
    langHmong: "苗语",
    langMongolian: "蒙古语",
    langYi: "彝语",
    langRussian: "俄语",
    langHani: "哈尼语",
    langSpanish: "西班牙语",
    langHakka: "客家话",
    langOther: "其它",
    langCosmic: "宇宙语",
    sourceLocal: "本地",
    sourceNas: "NAS 直读",
    sourceOnline: "需下载",
    sourceFallback: "在线",
    order: "点歌",
    top: "置顶",
    delete: "删除",
    playing: "正在播放",
    hotSummary: "第 {a}-{b} 首 · 本地优先",
    noHot: "暂无热歌排行",
    noData: "暂无数据",
    hotFailed: "热歌排行加载失败",
    retryPage: "请点击翻页重试",
    queueEmpty: "还没有点歌",
    queueEmptyHint: "去曲库选一首喜欢的歌吧",
    added: "已加入歌单",
    pinned: "已置顶为下一首",
    removed: "已从歌单移除",
    ordered: "已点",
    operationFailed: "操作失败",
    switchLang: "Switch to English",
    languageSwitched: "已切换为中文",
    browseEntryAria: "按分类浏览",
    browseSinger: "歌星",
    browseSingerHint: "按歌手逐页挑选",
    browseSong: "歌名",
    browseSongHint: "按歌名逐页挑选",
    browseLanguageTab: "歌曲语言",
    browseEyebrow: "BROWSE LIBRARY",
    browseTitle: "按歌星 / 歌名 / 歌曲语言浏览",
    browseModalAria: "浏览曲库",
    browseClose: "关闭",
    browseTabsAria: "浏览分类",
    browseBack: "返回",
    browseSingerList: "歌星列表",
    browseSongList: "歌曲列表",
    browseLanguageList: "语言歌曲列表",
    browseFilterWord: "字数",
    browseQuerySinger: "输入歌手名或拼音/首字母",
    browseQuerySong: "输入歌名或拼音/首字母",
    browseCatAll: "全部",
    browseCatMainlandMale: "大陆男",
    browseCatMainlandFemale: "大陆女",
    browseCatHkMale: "港台男",
    browseCatHkFemale: "港台女",
    browseCatChineseGroup: "中国组合",
    browseCatForeignGroup: "外国组合",
    browseWord1: "一字",
    browseWord2: "二字",
    browseWord3: "三字",
    browseWord4: "四字",
    browseWord5: "五字",
    browseWord6: "六字以上",
    browseSingerSongs: "{name} 的歌曲",
    browsePickSinger: "查看歌曲",
    browseSingerSummary: "第 {a}-{b} 位 · 共 {n} 位",
    browseSongSummary: "第 {a}-{b} 首 · 共 {n} 首",
    browseNoSingers: "暂无歌手数据",
    browseNoSongs: "暂无歌曲数据",
    browseFailed: "加载失败，请重试",
    browseSongDetail: "歌曲信息",
    browseDetailSinger: "歌手",
    browseDetailLanguage: "语言",
    browseDetailSource: "来源",
    browseOrderSong: "点歌",
    browseSelectHint: "点击歌曲可查看信息并点歌",
    browseBackspace: "退格",
    browseClear: "清空",
    browseKeyboardAria: "小键盘"
  },
  en: {
    title: "麦动 KTV Remote",
    offline: "Connection lost. Reconnecting…",
    connecting: "Connecting to 麦动 KTV…",
    connected: "Connected to 麦动 KTV",
    queueBadge: "Queue",
    nowPlaying: "Now playing",
    noSong: "No song yet",
    pickSong: "Pick a song to get started",
    replay: "Replay",
    pause: "Pause",
    resume: "Resume",
    next: "Next",
    soundControl: "Sound control",
    instant: "Instant",
    accompany: "Vocal Off",
    original: "Original",
    vocalBadgeAccompany: "Off",
    vocalBadgeOriginal: "On",
    musicVolume: "Music volume",
    nextUp: "Next Song",
    emptyQueue: "Queue is empty",
    viewQueue: "View queue",
    search: "Search",
    hotRanking: "Top charts",
    loading: "Loading…",
    prevPage: "Previous",
    nextPage: "Next",
    page: "Page {n}",
    pageOf: "Page {x}/{y}",
    playQueue: "PLAY QUEUE",
    queuedSongs: "Play queue",
    songCount: "{n} songs",
    pinTip: "Priority songs play right after the current one",
    remote: "Remote",
    searchNav: "Search",
    downloadsNav: "Downloads",
    rankNav: "Charts",
    queuedNav: "Queued",
    downloadsEyebrow: "MY MUSIC",
    downloadedSongs: "Downloaded songs",
    downloadedList: "Local library",
    downloadsTabsAria: "Download category",
    downloadsTabDownloaded: "Downloaded",
    downloadsTabActive: "Downloading",
    downloadingSongs: "Downloading songs",
    downloadQueue: "Download queue",
    downloadsSummary: "{a}-{b} of downloaded",
    activeDownloadsSummary: "{n} songs",
    noDownloads: "No downloaded songs yet",
    noDownloadsHint: "Download a song from search to play it offline",
    downloadsFailed: "Failed to load downloaded songs",
    noActiveDownloads: "No downloading or waiting songs",
    noActiveDownloadsHint: "Songs downloaded from search appear here",
    activeDownloadsFailed: "Failed to load download tasks",
    downloadStateWaiting: "Waiting",
    downloadStateDownloading: "Downloading",
    downloadStateCancelling: "Cancelling",
    downloadSpeedKb: "{n} KB/s",
    downloadSpeedMb: "{n} MB/s",
    rankEyebrow: "CHARTS",
    rankings: "Charts",
    rankHint: "Select a chart",
    rankSummary: "No. {a}-{b}",
    noRank: "No chart data yet",
    rankFailed: "Failed to load chart",
    rankCategoriesFailed: "Failed to load chart list",
    rankSoaring: "Soaring Chart",
    rankNewSongs: "New Songs Chart",
    rankHotSongs: "Top Songs Chart",
    rankChinese: "Chinese Chart",
    rankDuet: "Love Duet Chart",
    rankSoundtrack: "Soundtrack Chart",
    rankClassic: "Classic Hits Chart",
    rankMainland: "China Mainland Chart",
    rankHkTw: "Hong Kong & Taiwan Chart",
    rankCantonese: "Cantonese Chart",
    rankWestern: "Western Chart",
    rankJapanese: "Japanese Chart",
    langMandarin: "Chinese",
    langChinese: "Chinese",
    langCantonese: "Cantonese",
    langHokkien: "Hokkien",
    langTaiwanese: "Taiwanese",
    langEnglish: "English",
    langJapanese: "Japanese",
    langKorean: "Korean",
    langDialect: "Dialects",
    langTibetan: "Tibetan",
    langTeochew: "Teochew",
    langIndian: "Indian",
    langKhmer: "Khmer",
    langItalian: "Italian",
    langHmong: "Hmong",
    langMongolian: "Mongolian",
    langYi: "Yi",
    langRussian: "Russian",
    langHani: "Hani",
    langSpanish: "Spanish",
    langHakka: "Hakka",
    langOther: "Other",
    langCosmic: "Cosmic",
    sourceLocal: "Local",
    sourceNas: "NAS drive",
    sourceOnline: "Need Download",
    sourceFallback: "Online",
    order: "Select",
    top: "Priority",
    delete: "Delete",
    playing: "Playing",
    hotSummary: "{a}-{b} · Local first",
    noHot: "No chart data yet",
    noData: "No data",
    hotFailed: "Failed to load charts",
    retryPage: "Turn the page to retry",
    queueEmpty: "No songs queued",
    queueEmptyHint: "Pick one from the library",
    added: "Added to queue",
    pinned: "Moved to priority",
    removed: "Removed from queue",
    ordered: "Ordered",
    operationFailed: "Operation failed",
    switchLang: "切换到中文",
    languageSwitched: "Switched to English",
    browseEntryAria: "Browse by category",
    browseSinger: "Singers",
    browseSingerHint: "Browse singers page by page",
    browseSong: "Songs",
    browseSongHint: "Browse songs page by page",
    browseLanguageTab: "Song language",
    browseEyebrow: "BROWSE LIBRARY",
    browseTitle: "Browse by singer / song / language",
    browseModalAria: "Browse song library",
    browseClose: "Close",
    browseTabsAria: "Browse category",
    browseBack: "Back",
    browseSingerList: "Singer list",
    browseSongList: "Song list",
    browseLanguageList: "Songs by language",
    browseFilterWord: "Word count",
    browseQuerySinger: "Singer name or pinyin/initials",
    browseQuerySong: "Song name or pinyin/initials",
    browseCatAll: "All",
    browseCatMainlandMale: "Mainland male",
    browseCatMainlandFemale: "Mainland female",
    browseCatHkMale: "HK & TW male",
    browseCatHkFemale: "HK & TW female",
    browseCatChineseGroup: "Chinese groups",
    browseCatForeignGroup: "Foreign groups",
    browseWord1: "1 character",
    browseWord2: "2 characters",
    browseWord3: "3 characters",
    browseWord4: "4 characters",
    browseWord5: "5 characters",
    browseWord6: "6+ characters",
    browseSingerSongs: "Songs by {name}",
    browsePickSinger: "View songs",
    browseSingerSummary: "{a}-{b} of {n} singers",
    browseSongSummary: "{a}-{b} of {n} songs",
    browseNoSingers: "No singers yet",
    browseNoSongs: "No songs yet",
    browseFailed: "Failed to load. Please retry.",
    browseSongDetail: "Song details",
    browseDetailSinger: "Singer",
    browseDetailLanguage: "Language",
    browseDetailSource: "Source",
    browseOrderSong: "Select",
    browseSelectHint: "Tap a song to see details and select it",
    browseBackspace: "Backspace",
    browseClear: "Clear",
    browseKeyboardAria: "Keypad"
  }
};

const uiState = {
  visible: true, connected: false, heartbeatFailures: 0,
  lang: "zh", queueSongs: [],
  downloadsPage: 1, downloadsPageSize: 10, downloadsBusy: false, downloadsSongs: [], downloadsError: false,
  downloadsTotal: 0, downloadsTotalPages: 1, downloadsHasPrevious: false, downloadsHasMore: false,
  downloadTab: "downloaded", downloadTasks: [], downloadTasksBusy: false,
  downloadTasksError: false, downloadTasksTotal: 0,
  rankCategories: [], rankId: "", rankName: "", rankPage: 1, rankPageSize: 10, rankTotalPages: 1,
  rankBusy: false, rankSongs: [], rankError: false, categoriesError: false,
  browse: {
    tab: "song", view: "list", returnView: "list", busy: false, error: false,
    keyboardVisible: false,
    page: 1, pageSize: 10, total: 0, totalPages: 1, hasPrevious: false, hasMore: false,
    singers: [], songs: [], singer: null, song: null, singerListPage: 1,
    languages: [],
    singerFilter: { category: "全部", keyword: "" },
    songFilter: { wordCount: 0, keyword: "" },
    languageFilter: { language: "全部" }
  }
};

// 与 APK 歌星点歌页 currentCategories 一致
const BROWSE_SINGER_CATEGORIES = [
  ["全部", "browseCatAll"], ["大陆男", "browseCatMainlandMale"], ["大陆女", "browseCatMainlandFemale"],
  ["港台男", "browseCatHkMale"], ["港台女", "browseCatHkFemale"],
  ["中国组合", "browseCatChineseGroup"], ["外国组合", "browseCatForeignGroup"]
];
const BROWSE_WORD_COUNTS = [
  [0, "browseCatAll"], [1, "browseWord1"], [2, "browseWord2"], [3, "browseWord3"],
  [4, "browseWord4"], [5, "browseWord5"], [6, "browseWord6"]
];
const BROWSE_LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".split("");
const BROWSE_DIGITS = "0123456789".split("");

function t(key, vars) {
  const table = I18N[uiState.lang] || I18N.zh;
  const value = table[key] ?? I18N.zh[key] ?? key;
  return String(value).replace(/\{(\w+)\}/g, (_, name) => (vars && vars[name] != null ? String(vars[name]) : ""));
}

function calcTotalPages(total, pageSize, fallbackPage = 1) {
  if (total == null) return Math.max(1, fallbackPage);
  return Math.max(1, Math.ceil(Number(total) / Math.max(1, Number(pageSize) || 1)));
}

function pageLabel(current, total) {
  return t("pageOf", { x: current, y: Math.max(1, total || current || 1) });
}

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, (char) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;"
  }[char]));
}

function renderConnectionText() {
  const key = uiState.connected ? "connected" : "connecting";
  $("#connection").innerHTML = `<span></span><b class="i18n" data-i18n="${key}">${t(key)}</b>`;
}

function setConnected(connected) {
  uiState.connected = connected;
  $("#offline").classList.toggle("hidden", connected);
  renderConnectionText();
}

async function api(path, options = {}, timeoutMs = 8000) {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), timeoutMs);
  try {
    let response;
    try {
      response = await fetch(path, {
        cache: "no-store",
        headers: { "Content-Type": "application/json" },
        ...options,
        signal: controller.signal
      });
    } catch (error) { throw error; }
    // A server response proves connectivity even when an operation is rejected.
    setConnected(true);
    uiState.heartbeatFailures = 0;
    const data = await response.json();
    if (!response.ok || data.ok === false) throw new Error(data.error || t("operationFailed"));
    return data;
  } finally { window.clearTimeout(timeout); }
}

function toast(text) {
  const element = $("#toast");
  element.textContent = text;
  element.classList.add("show");
  window.setTimeout(() => element.classList.remove("show"), 1600);
}

/* Display-only source label. Falls back to the stock binary payload when the
   server does not provide `source` (e.g. an older TV build). */
function sourceLabel(song) {
  if (song.source === "local") return [t("sourceLocal"), "ok"];
  if (song.source === "nas") return [t("sourceNas"), "ok"];
  if (song.source === "online") return [t("sourceOnline"), "warn"];
  return song.local === true ? [t("sourceLocal"), "ok"] : [t("sourceFallback"), "warn"];
}

const RANK_NAME_KEYS = [
  ["飙升榜", "rankSoaring"],
  ["新歌榜", "rankNewSongs"],
  ["热歌榜", "rankHotSongs"],
  ["华语榜", "rankChinese"],
  ["情歌对唱榜", "rankDuet"],
  ["影视金曲榜", "rankSoundtrack"],
  ["经典老歌榜", "rankClassic"],
  ["内地榜", "rankMainland"],
  ["港台榜", "rankHkTw"],
  ["粤语榜", "rankCantonese"],
  ["欧美榜", "rankWestern"],
  ["日语榜", "rankJapanese"]
];

function rankDisplayName(name) {
  const raw = String(name ?? "");
  if (uiState.lang !== "en" || !raw) return raw;
  for (const [prefix, key] of RANK_NAME_KEYS) {
    if (!raw.startsWith(prefix)) continue;
    const suffix = raw.slice(prefix.length)
      .replace(/（(\d{4})第(\d+)期）/g, " ($1 Issue $2)")
      .replace(/（([^）]+)）/g, " ($1)");
    return t(key) + suffix;
  }
  return raw;
}

const LANGUAGE_NAME_KEYS = {
  "国语": "langMandarin", "普通话": "langMandarin", "华语": "langMandarin",
  "中文": "langChinese", "粤语": "langCantonese", "闽南语": "langHokkien",
  "台语": "langTaiwanese", "英语": "langEnglish", "日语": "langJapanese",
  "韩语": "langKorean", "地方语言": "langDialect", "藏语": "langTibetan",
  "潮汕语": "langTeochew", "印度语": "langIndian", "高棉语": "langKhmer",
  "意大利语": "langItalian", "苗语": "langHmong", "蒙古语": "langMongolian",
  "彝语": "langYi", "俄语": "langRussian", "哈尼语": "langHani", "哈尼": "langHani",
  "西班牙语": "langSpanish", "客家话": "langHakka", "客家语": "langHakka",
  "其它": "langOther", "其他": "langOther", "宇宙语": "langCosmic"
};

function languageDisplayName(lang) {
  const raw = String(lang ?? "").trim();
  if (!raw || uiState.lang !== "en") return raw;
  return raw.split(/[,，]/).map((part) => {
    const value = part.trim();
    const key = LANGUAGE_NAME_KEYS[value];
    return key ? t(key) : value;
  }).join(", ");
}

function downloadStateLabel(song) {
  if (song.downloadState === "downloading") return t("downloadStateDownloading");
  if (song.downloadState === "cancelling") return t("downloadStateCancelling");
  return t("downloadStateWaiting");
}

function downloadSpeedText(speed) {
  const value = Number(speed) || 0;
  if (value <= 0) return "";
  if (value >= 1048576) return t("downloadSpeedMb", { n: (value / 1048576).toFixed(1) });
  return t("downloadSpeedKb", { n: Math.max(1, Math.round(value / 1024)) });
}

function songRow(song, queue = false, current = false, index = 0, ranking = false, downloadTask = false) {
  const number = current ? "♪" : String(index + 1).padStart(2, "0");
  const [label, tone] = sourceLabel(song);
  const actions = queue && !current
    ? `<button class="top">${t("top")}</button><button class="delete" aria-label="${t("delete")}">×</button>`
    : !queue ? `<button class="order">${t("order")}</button>` : "";
  const stateCode = ["waiting", "downloading", "cancelling"].includes(song.downloadState)
    ? song.downloadState : "waiting";
  const progress = Math.min(99, Math.max(0, Number(song.downloadProgress) || 0));
  const speedText = stateCode === "downloading" ? downloadSpeedText(song.downloadSpeed) : "";
  const taskBadge = downloadTask
    ? `<span class="download-badge ${stateCode}">${downloadStateLabel(song)}${speedText ? " · " + speedText : ""}</span>`
    : "";
  const taskBar = downloadTask && stateCode !== "waiting"
    ? `<div class="download-progress"><i style="width:${progress}%"></i><span>${progress}%</span></div>`
    : "";
  // Active tasks show their download state instead of the generic source badge
  // (which would say "Need Download" for online songs).
  const sourceBadge = downloadTask
    ? ""
    : `<span class="source-badge ${tone}">${label}</span>`;
  return `<div class="song${ranking && index < 3 ? " podium" : ""}${downloadTask ? " download-task" : ""}" data-id="${escapeHtml(song.id)}">
    <div class="song-index">${number}</div>
    <div class="info">${current ? `<span class="playing">${t("playing")}</span>` : ""}<b>${escapeHtml(song.title)}</b><p>${taskBadge}${sourceBadge}${escapeHtml(song.singer)}${song.language ? " · " + escapeHtml(languageDisplayName(song.language)) : ""}</p>${taskBar}</div>
    ${actions}
  </div>`;
}

function openPage(name) {
  $$("nav button,.page").forEach((element) => element.classList.remove("active"));
  $(`nav button[data-page="${name}"]`)?.classList.add("active");
  $("#" + name)?.classList.add("active");
  if (name === "queue") loadQueue();
  if (name === "search" && !$("#browseList").children.length) loadBrowsePage(uiState.browse.page);
  if (name === "downloads") {
    if (uiState.downloadTab === "active") loadDownloadTasks();
    else loadDownloads(uiState.downloadsPage);
  }
  if (name === "rank" && !uiState.rankCategories.length && !uiState.categoriesError) loadRankCategories();
}

let refreshing = false;
async function refresh() {
  if (refreshing) return;
  refreshing = true;
  try {
    const data = await api("/api/v1/state");
    const current = data.current || {};
    const count = data.queueSize || 0;
    $("#nowTitle").textContent = current.title || t("noSong");
    $("#nowSinger").textContent = current.singer || t("pickSong");
    $("#queueBadge b").textContent = count;
    $("#navCount").textContent = count;
    $("#navCount").classList.toggle("hidden", count === 0);
    $("#playButton span:last-child").textContent = data.playing ? t("pause") : t("resume");
    $("#playButton").dataset.action = data.playing ? "pause" : "resume";
    $("#playButton .control-icon").textContent = data.playing ? "Ⅱ" : "▶";
    $("#musicVolume").value = data.musicVolume ?? 70;
    $("#musicValue").textContent = data.musicVolume ?? 70;
    $("#original").classList.toggle("active", data.vocalMode === "original");
    $("#accompany").classList.toggle("active", data.vocalMode !== "original");
    $("#nextSong").textContent = data.next?.title || t("emptyQueue");
  } catch (error) {
    // A slow search or suspended mobile tab is not proof of a lost LAN connection.
    // Only consecutive heartbeat failures change the connectivity indicator.
    uiState.heartbeatFailures += 1;
    if (uiState.heartbeatFailures >= 3) setConnected(false);
    console.warn("State refresh failed", error.message);
  } finally { refreshing = false; }
}

async function playerAction(action) {
  try {
    await api("/api/v1/player/actions", { method: "POST", body: JSON.stringify({ action }) });
    await refresh();
  } catch (error) { toast(error.message); }
}

async function updateAudio(values) {
  try {
    await api("/api/v1/player/audio", { method: "PATCH", body: JSON.stringify(values) });
    await refresh();
  } catch (error) { toast(error.message); }
}

function setI18nText(selector, key) {
  const element = $(selector);
  if (!element) return;
  element.dataset.i18n = key;
  element.textContent = t(key);
}

function renderDownloadsRows() {
  const activeTab = uiState.downloadTab === "active";
  const list = $("#downloadsList");
  if (activeTab) {
    const rows = uiState.downloadTasks;
    if (rows.length) {
      list.innerHTML = rows.map((song, index) =>
        songRow(song, false, false, index, false, true)).join("");
    } else if (uiState.downloadTasksError) {
      list.innerHTML = `<div class="empty compact">${t("activeDownloadsFailed")}</div>`;
    } else {
      list.innerHTML = `<div class="empty">${t("noActiveDownloads")}<br><small>${t("noActiveDownloadsHint")}</small></div>`;
    }
    $("#downloadsCount").textContent = t("songCount", { n: uiState.downloadTasksTotal });
    $("#downloadsSummary").textContent = uiState.downloadTasksError
      ? t("retryPage")
      : (rows.length ? t("activeDownloadsSummary", { n: rows.length }) : "");
    $("#downloadsPager").classList.add("hidden");
    return;
  }
  const rows = uiState.downloadsSongs;
  if (rows.length) {
    const offset = (uiState.downloadsPage - 1) * uiState.downloadsPageSize;
    list.innerHTML = rows.map((song, index) =>
      songRow(song, false, false, offset + index)).join("");
  } else if (uiState.downloadsError) {
    list.innerHTML = `<div class="empty compact">${t("downloadsFailed")}</div>`;
  } else {
    list.innerHTML = `<div class="empty">${t("noDownloads")}<br><small>${t("noDownloadsHint")}</small></div>`;
  }
  const first = (uiState.downloadsPage - 1) * uiState.downloadsPageSize + 1;
  const last = first + rows.length - 1;
  $("#downloadsCount").textContent = t("songCount", { n: uiState.downloadsTotal });
  $("#downloadsPage").textContent = pageLabel(uiState.downloadsPage, uiState.downloadsTotalPages);
  $("#downloadsSummary").textContent = uiState.downloadsError
    ? t("retryPage")
    : (rows.length ? t("downloadsSummary", { a: first, b: last }) : "");
  $("#downloadsPager").classList.remove("hidden");
  $("#downloadsPrev").disabled = !uiState.downloadsHasPrevious;
  $("#downloadsNext").disabled = !uiState.downloadsHasMore;
}

function applyDownloadTab(tab) {
  if (tab !== "downloaded" && tab !== "active") return;
  uiState.downloadTab = tab;
  $$("[data-download-tab]").forEach((button) =>
    button.classList.toggle("active", button.dataset.downloadTab === tab));
  setI18nText("#downloadsTitle", tab === "active" ? "downloadingSongs" : "downloadedSongs");
  setI18nText("#downloadsSubtitle", tab === "active" ? "downloadQueue" : "downloadedList");
  renderDownloadsRows();
}

function switchDownloadTab(tab) {
  if (uiState.downloadTab === tab) { renderDownloadsRows(); return; }
  applyDownloadTab(tab);
  if (tab === "active") loadDownloadTasks();
  else loadDownloads(1);
}

async function loadDownloads(page, silent = false) {
  if (uiState.downloadsBusy || page < 1) return;
  uiState.downloadsBusy = true;
  if (!silent) {
    $("#downloadsPrev").disabled = true;
    $("#downloadsNext").disabled = true;
    $("#downloadsSummary").textContent = t("loading");
  }
  try {
    const data = await api(`/api/v1/downloads?page=${page}&pageSize=${uiState.downloadsPageSize}`);
    const songs = data.songs || [];
    uiState.downloadsPage = data.page || page;
    uiState.downloadsSongs = songs;
    uiState.downloadsTotal = data.total ?? songs.length;
    uiState.downloadsTotalPages = calcTotalPages(data.total, uiState.downloadsPageSize, uiState.downloadsPage);
    uiState.downloadsHasPrevious = !!data.hasPrevious;
    uiState.downloadsHasMore = !!data.hasMore;
    uiState.downloadsError = false;
    renderDownloadsRows();
  } catch (error) {
    uiState.downloadsError = true;
    renderDownloadsRows();
    if (!silent) toast(error.message);
  } finally { uiState.downloadsBusy = false; }
}

async function loadDownloadTasks(silent = false) {
  if (uiState.downloadTasksBusy) return;
  uiState.downloadTasksBusy = true;
  if (!silent) $("#downloadsSummary").textContent = t("loading");
  try {
    const data = await api("/api/v1/download-tasks");
    uiState.downloadTasks = (data.tasks || []).map((song) => ({ ...song, downloadTask: true }));
    uiState.downloadTasksTotal = data.total ?? uiState.downloadTasks.length;
    uiState.downloadTasksError = false;
    renderDownloadsRows();
  } catch (error) {
    uiState.downloadTasks = [];
    uiState.downloadTasksTotal = 0;
    uiState.downloadTasksError = true;
    renderDownloadsRows();
    if (!silent) toast(error.message);
  } finally { uiState.downloadTasksBusy = false; }
}

async function loadRankCategories() {
  try {
    const data = await api("/api/v1/rank");
    const categories = data.categories || [];
    uiState.rankCategories = categories;
    uiState.categoriesError = false;
    $("#rankCategories").innerHTML = categories.map((c) =>
      `<button type="button" data-rank-id="${escapeHtml(c.id)}" data-rank-name="${escapeHtml(c.name)}"><b>♪</b>${escapeHtml(rankDisplayName(c.name))}</button>`
    ).join("") || `<div class="empty compact">${t("noRank")}</div>`;
    if (categories.length) {
      const first = categories[0];
      uiState.rankId = first.id;
      uiState.rankName = first.name;
      $$("#rankCategories button").forEach((b) => b.classList.toggle("active", b.dataset.rankId === first.id));
      $("#rankTitle").textContent = rankDisplayName(first.name);
      loadRank(1);
    }
  } catch (error) {
    uiState.categoriesError = true;
    $("#rankCategories").innerHTML = `<div class="empty compact">${t("rankCategoriesFailed")}</div>`;
    toast(error.message);
  }
}

async function loadRank(page) {
  if (uiState.rankBusy || page < 1 || !uiState.rankId) return;
  uiState.rankBusy = true;
  $("#rankPrev").disabled = true;
  $("#rankNext").disabled = true;
  $("#rankSummary").textContent = t("loading");
  try {
    const data = await api(`/api/v1/rank?playlistId=${encodeURIComponent(uiState.rankId)}&page=${page}&pageSize=${uiState.rankPageSize}`);
    uiState.rankPage = data.page || page;
    uiState.rankTotalPages = calcTotalPages(data.total, uiState.rankPageSize, uiState.rankPage);
    const songs = data.songs || [];
    uiState.rankSongs = songs;
    uiState.rankError = false;
    $("#rankList").innerHTML = songs.map((song, index) =>
      songRow(song, false, false, (uiState.rankPage - 1) * uiState.rankPageSize + index, true)
    ).join("") || `<div class="empty compact">${t("noRank")}</div>`;
    $("#rankPage").textContent = pageLabel(uiState.rankPage, uiState.rankTotalPages);
    $("#rankSummary").textContent = songs.length ? t("rankSummary", {
      a: (uiState.rankPage - 1) * uiState.rankPageSize + 1,
      b: (uiState.rankPage - 1) * uiState.rankPageSize + songs.length
    }) : "";
    $("#rankPrev").disabled = !data.hasPrevious;
    $("#rankNext").disabled = !data.hasMore;
  } catch (error) {
    uiState.rankError = true;
    $("#rankList").innerHTML = `<div class="empty compact">${t("rankFailed")}</div>`;
    $("#rankSummary").textContent = t("retryPage");
    toast(error.message);
  } finally { uiState.rankBusy = false; }
}

async function loadQueue() {
  try {
    const data = await api("/api/v1/queue");
    const queue = data.queue || [];
    uiState.queueSongs = queue;
    $("#queueCount").textContent = t("songCount", { n: queue.length });
    $("#queueList").innerHTML = queue.map((song, index) => songRow(song, true, index === 0, index)).join("") || `<div class="empty">${t("queueEmpty")}<br><small>${t("queueEmptyHint")}</small></div>`;
  } catch (error) { toast(error.message); }
}

/* ---------- Paginated singer / song browser ---------- */
function browseSongById(id) {
  return uiState.browse.songs.find((song) => String(song.id) === String(id)) || null;
}

function renderBrowsePager() {
  const b = uiState.browse;
  const pager = $("#browsePager");
  if (!pager) return;
  if (b.view === "detail") { pager.classList.add("hidden"); return; }
  pager.classList.remove("hidden");
  $("#browsePage").textContent = pageLabel(b.page, b.totalPages);
  $("#browsePrev").disabled = !b.hasPrevious || b.busy;
  $("#browseNext").disabled = !b.hasMore || b.busy;
}

function activeBrowseFilter() {
  return uiState.browse.tab === "song" ? uiState.browse.songFilter : uiState.browse.singerFilter;
}

function browseLanguageChips(selected) {
  const langs = uiState.browse.languages.length ? uiState.browse.languages : ["全部"];
  return langs.map((lang) =>
    `<button type="button" class="browse-chip${lang === selected ? " active" : ""}" data-browse-language="${escapeHtml(lang)}">${lang === "全部" ? t("browseCatAll") : escapeHtml(languageDisplayName(lang))}</button>`
  ).join("");
}

function renderBrowseFilters() {
  const b = uiState.browse;
  const show = b.view === "list";
  $("#browseFilters").classList.toggle("hidden", !show);
  $("#browseSearchBlock").classList.toggle("hidden", !show);
  if (!show) return;
  const chips = $("#browseChips");
  if (b.tab === "language") {
    $("#browseSearchBlock").classList.add("hidden");
    $("#browseQueryRow").classList.add("hidden");
    $("#browseLetters").classList.add("hidden");
    chips.innerHTML = browseLanguageChips(b.languageFilter.language);
    return;
  }
  $("#browseSearchBlock").classList.remove("hidden");
  $("#browseQueryRow").classList.remove("hidden");
  $("#browseLetters").classList.toggle("hidden", !b.keyboardVisible);
  $("#browseKeyboardToggle").classList.toggle("active", b.keyboardVisible);
  const filter = activeBrowseFilter();
  const input = $("#browseQuery");
  input.value = filter.keyword;
  if (b.tab === "singer") {
    input.placeholder = t("browseQuerySinger");
    chips.innerHTML = BROWSE_SINGER_CATEGORIES.map(([value, key]) =>
      `<button type="button" class="browse-chip${value === filter.category ? " active" : ""}" data-browse-category="${escapeHtml(value)}">${t(key)}</button>`).join("");
  } else {
    input.placeholder = t("browseQuerySong");
    const wordChips = BROWSE_WORD_COUNTS.map(([value, key]) =>
      `<button type="button" class="browse-chip${value === filter.wordCount ? " active" : ""}" data-browse-wordcount="${value}">${t(key)}</button>`).join("");
    chips.innerHTML = `<div class="filter-row"><span>${t("browseFilterWord")}</span><div class="browse-chips">${wordChips}</div></div>`;
  }
  // 字母与数字同页展示，退格/清空跟在 0-9 之后。
  $("#browseLetters").innerHTML = BROWSE_LETTERS.concat(BROWSE_DIGITS).map((key) =>
    `<button type="button" class="browse-letter" data-browse-letter="${key}">${key}</button>`).join("") +
    `<button type="button" class="browse-letter browse-letter-action" data-browse-letter-action="backspace" data-i18n-aria="browseBackspace" aria-label="退格">⌫</button>` +
    `<button type="button" class="browse-letter browse-letter-action" data-browse-letter-action="clear" data-i18n-aria="browseClear" aria-label="清空">×</button>`;
}

async function loadBrowseLanguages() {
  try {
    const data = await api("/api/v1/catalog-languages");
    uiState.browse.languages = data.languages || ["全部"];
  } catch (_) {
    uiState.browse.languages = ["全部", "国语", "粤语", "英语", "日语", "韩语"];
  }
  if (uiState.browse.tab === "song") renderBrowseFilters();
}

function applyBrowseQuery() {
  const b = uiState.browse;
  activeBrowseFilter().keyword = $("#browseQuery").value.trim();
  b.page = 1;
  renderBrowseFilters();
  loadBrowsePage(1);
}

function renderBrowseDetail() {
  const b = uiState.browse;
  const song = b.song;
  if (!song) return;
  const [label] = sourceLabel(song);
  $("#browseSectionTitle").textContent = t("browseTitle");
  $("#browseListTitle").textContent = t("browseSongDetail");
  $("#browseSummary").textContent = "";
  $("#browseList").innerHTML = `<div class="browse-detail">
    <div class="detail-art">♪</div>
    <h4>${escapeHtml(song.title)}</h4>
    <div class="detail-rows">
      <div><span>${t("browseDetailSinger")}</span><b>${escapeHtml(song.singer)}</b></div>
      <div><span>${t("browseDetailLanguage")}</span><b>${escapeHtml(languageDisplayName(song.language) || t("noData"))}</b></div>
      <div><span>${t("browseDetailSource")}</span><b>${escapeHtml(label)}</b></div>
    </div>
    <button type="button" class="detail-order" data-browse-order-id="${escapeHtml(song.id)}">${t("browseOrderSong")}</button>
    <p class="detail-hint">${t("browseSelectHint")}</p>
  </div>`;
  renderBrowsePager();
}

function renderBrowseList() {
  const b = uiState.browse;
  renderBrowseFilters();
  if (b.view === "detail") { renderBrowseDetail(); return; }
  const singerSongs = b.view === "singerSongs";
  const singersView = b.tab === "singer" && !singerSongs;
  $("#browseSectionTitle").textContent = singerSongs && b.singer
    ? t("browseSingerSongs", { name: b.singer.name }) : t("browseTitle");
  $("#browseListTitle").textContent = singersView ? t("browseSingerList")
    : b.tab === "language" ? t("browseLanguageList") : t("browseSongList");
  $$("[data-browse-tab]").forEach((item) =>
    item.classList.toggle("active", item.dataset.browseTab === b.tab));
  const crumb = $("#browseCrumb");
  crumb.classList.toggle("hidden", !(singerSongs && b.singer));
  if (singerSongs && b.singer) $("#browseCrumbText").textContent = b.singer.name;

  const list = $("#browseList");
  if (singersView && !b.singers.length) {
    $("#browseSummary").textContent = b.error ? t("browseFailed") : t("noData");
    list.innerHTML = `<div class="empty compact">${b.error ? t("browseFailed") : t("browseNoSingers")}</div>`;
  } else if (!singersView && !b.songs.length) {
    $("#browseSummary").textContent = b.error ? t("browseFailed") : t("noData");
    list.innerHTML = `<div class="empty compact">${b.error ? t("browseFailed") : t("browseNoSongs")}</div>`;
  } else if (singersView) {
    list.innerHTML = `<div class="browse-cards">` + b.singers.map((singer) => {
      const meta = [singer.region, singer.type].filter(Boolean).join(" · ");
      return `<button type="button" class="browse-card" data-browse-singer="${escapeHtml(singer.id)}" data-browse-singer-name="${escapeHtml(singer.name)}"><b>${escapeHtml(singer.name)}</b><small>${escapeHtml(meta || t("browsePickSinger"))}</small><em>${t("browsePickSinger")} ›</em></button>`;
    }).join("") + `</div>`;
    const first = (b.page - 1) * b.pageSize + 1;
    $("#browseSummary").textContent = t("browseSingerSummary", {
      a: first, b: first + b.singers.length - 1, n: b.total
    });
  } else {
    const offset = (b.page - 1) * b.pageSize;
    list.innerHTML = b.songs.map((song, index) => {
      const [label] = sourceLabel(song);
      return `<div class="browse-song" data-browse-song-id="${escapeHtml(song.id)}">
        <div class="song-index">${String(offset + index + 1).padStart(2, "0")}</div>
        <div class="info"><b>${escapeHtml(song.title)}</b><p>${escapeHtml(song.singer)}${song.language ? " · " + escapeHtml(languageDisplayName(song.language)) : ""} · ${escapeHtml(label)}</p></div>
        <button type="button" class="order" data-browse-order-id="${escapeHtml(song.id)}">${t("order")}</button>
        <span class="arrow">›</span>
      </div>`;
    }).join("");
    const first = offset + 1;
    $("#browseSummary").textContent = t("browseSongSummary", {
      a: first, b: first + b.songs.length - 1, n: b.total
    });
  }
  renderBrowsePager();
}

async function loadBrowsePage(page) {
  const b = uiState.browse;
  if (b.busy || page < 1) return;
  b.busy = true;
  b.error = false;
  renderBrowsePager();
  try {
    let data;
    if (b.view === "singerSongs" && b.singer) {
      data = await api(`/api/v1/singer-songs?singerId=${encodeURIComponent(b.singer.id)}&page=${page}&pageSize=${b.pageSize}`);
    } else if (b.tab === "singer") {
      const f = b.singerFilter;
      data = await api(`/api/v1/singers?category=${encodeURIComponent(f.category)}&q=${encodeURIComponent(f.keyword)}&page=${page}&pageSize=${b.pageSize}`);
    } else if (b.tab === "language") {
      const lang = b.languageFilter.language;
      data = await api(`/api/v1/song-catalog?language=${encodeURIComponent(lang)}&page=${page}&pageSize=${b.pageSize}`);
    } else {
      const f = b.songFilter;
      data = await api(`/api/v1/song-catalog?language=${encodeURIComponent("全部")}&wordcount=${f.wordCount}&q=${encodeURIComponent(f.keyword)}&page=${page}&pageSize=${b.pageSize}`);
    }
    b.page = data.page || page;
    b.total = data.total ?? (data.songs?.length || 0);
    b.totalPages = calcTotalPages(b.total, b.pageSize, b.page);
    b.hasPrevious = data.hasPrevious != null ? !!data.hasPrevious : b.page > 1;
    b.hasMore = !!data.hasMore;
    b.singers = data.singers || [];
    b.songs = data.songs || [];
  } catch (error) {
    b.error = true;
    b.singers = [];
    b.songs = [];
    toast(error.message);
  } finally {
    b.busy = false;
    renderBrowseList();
  }
}

function applyBrowseTab(tab) {
  const b = uiState.browse;
  if (b.tab === tab && b.view !== "singerSongs" && b.view !== "detail") return;
  b.tab = tab === "song" || tab === "language" ? tab : "singer";
  b.view = "list";
  b.singer = null;
  b.song = null;
  b.page = 1;
  b.singers = [];
  b.songs = [];
  b.error = false;
  renderBrowseList();
  loadBrowsePage(1);
}

function openBrowseSinger(id, name) {
  const b = uiState.browse;
  b.singerListPage = b.page;
  b.singer = { id: id, name: name };
  b.view = "singerSongs";
  b.song = null;
  b.page = 1;
  b.singers = [];
  b.songs = [];
  b.error = false;
  renderBrowseList();
  loadBrowsePage(1);
}

function showBrowseSongDetail(id) {
  const b = uiState.browse;
  const song = browseSongById(id);
  if (!song) return;
  b.returnView = b.view;
  b.song = song;
  b.view = "detail";
  renderBrowseList();
}

function backBrowse() {
  const b = uiState.browse;
  if (b.view === "detail") {
    b.song = null;
    b.view = b.returnView === "singerSongs" ? "singerSongs" : "list";
    renderBrowseList();
    return;
  }
  if (b.view === "singerSongs") {
    b.view = "list";
    b.singer = null;
    b.song = null;
    b.page = b.singerListPage || 1;
    b.singers = [];
    b.songs = [];
    renderBrowseList();
    loadBrowsePage(b.page);
  }
}

async function orderBrowseSong(id) {
  const song = browseSongById(id);
  if (!song) return;
  try {
    await api("/api/v1/queue", { method: "POST", body: JSON.stringify({ songId: song.id }) });
    toast(t("added"));
    refresh();
  } catch (error) { toast(error.message); }
}

function renderCachedLists() {
  if (uiState.queueSongs.length) {
    $("#queueList").innerHTML = uiState.queueSongs
      .map((song, index) => songRow(song, true, index === 0, index)).join("");
  }
  renderDownloadsRows();
  if (uiState.rankCategories.length) {
    $("#rankCategories").innerHTML = uiState.rankCategories.map((c) =>
      `<button type="button" data-rank-id="${escapeHtml(c.id)}" data-rank-name="${escapeHtml(c.name)}"${c.id === uiState.rankId ? ' class="active"' : ''}><b>♪</b>${escapeHtml(rankDisplayName(c.name))}</button>`
    ).join("");
    $("#rankTitle").textContent = rankDisplayName(uiState.rankName);
  }
  if (uiState.rankSongs.length) {
    $("#rankList").innerHTML = uiState.rankSongs.map((song, index) =>
      songRow(song, false, false, (uiState.rankPage - 1) * uiState.rankPageSize + index, true)
    ).join("");
    $("#rankSummary").textContent = t("rankSummary", {
      a: (uiState.rankPage - 1) * uiState.rankPageSize + 1,
      b: (uiState.rankPage - 1) * uiState.rankPageSize + uiState.rankSongs.length
    });
  } else if (uiState.rankError) {
    $("#rankList").innerHTML = `<div class="empty compact">${t("rankFailed")}</div>`;
  }
  renderBrowseList();
  $("#downloadsPage").textContent = pageLabel(uiState.downloadsPage, uiState.downloadsTotalPages);
  $("#rankPage").textContent = pageLabel(uiState.rankPage, uiState.rankTotalPages);
  $("#queueCount").textContent = t("songCount", { n: uiState.queueSongs.length });
}

function applyLanguage(lang, persist = true) {
  uiState.lang = lang === "en" ? "en" : "zh";
  if (persist) {
    try { localStorage.setItem("ktv-mobile-lang", uiState.lang); } catch (_) {}
  }
  document.documentElement.lang = uiState.lang === "en" ? "en" : "zh-CN";

  $$("[data-i18n]").forEach((element) => { element.textContent = t(element.dataset.i18n); });
  $$("[data-i18n-placeholder]").forEach((element) => { element.placeholder = t(element.dataset.i18nPlaceholder); });
  $$("[data-i18n-aria]").forEach((element) => { element.setAttribute("aria-label", t(element.dataset.i18nAria)); });

  $("#langButton").textContent = uiState.lang === "zh" ? "EN" : "中";
  $("#langButton").setAttribute("aria-label", t("switchLang"));

  renderConnectionText();
  renderCachedLists();
}

function toggleLanguage() {
  applyLanguage(uiState.lang === "zh" ? "en" : "zh");
  toast(t("languageSwitched"));
}

document.addEventListener("click", async (event) => {
  const browseSong = event.target.closest("[data-browse-song-id]");
  if (browseSong && !event.target.closest("button")) {
    showBrowseSongDetail(browseSong.dataset.browseSongId);
    return;
  }
  const button = event.target.closest("button");
  if (!button) return;
  if (button.id === "browseBack") { backBrowse(); return; }
  if (button.id === "browsePrev") { loadBrowsePage(uiState.browse.page - 1); return; }
  if (button.id === "browseNext") { loadBrowsePage(uiState.browse.page + 1); return; }
  if (button.dataset.browseTab) { applyBrowseTab(button.dataset.browseTab); return; }
  if (button.dataset.browseCategory) {
    uiState.browse.singerFilter.category = button.dataset.browseCategory;
    uiState.browse.page = 1; renderBrowseFilters(); loadBrowsePage(1); return;
  }
  if (button.dataset.browseLanguage) {
    uiState.browse.languageFilter.language = button.dataset.browseLanguage;
    uiState.browse.page = 1; renderBrowseFilters(); loadBrowsePage(1); return;
  }
  if (button.dataset.browseWordcount) {
    uiState.browse.songFilter.wordCount = Number(button.dataset.browseWordcount) || 0;
    uiState.browse.page = 1; renderBrowseFilters(); loadBrowsePage(1); return;
  }
  if (button.dataset.browseLetter || button.dataset.browseLetterAction) {
    const letterAction = button.dataset.browseLetterAction;
    const filter = activeBrowseFilter();
    if (button.dataset.browseLetter) {
      // 与 APK 内置键盘一致：连续按键追加，可组成 YY / ZJL 等多字母筛选。
      filter.keyword = (filter.keyword + button.dataset.browseLetter).slice(0, 12);
    } else if (letterAction === "backspace") {
      filter.keyword = filter.keyword.slice(0, -1);
    } else {
      filter.keyword = "";
    }
    uiState.browse.page = 1; renderBrowseFilters(); loadBrowsePage(1); return;
  }
  if (button.id === "browseKeyboardToggle") {
    uiState.browse.keyboardVisible = !uiState.browse.keyboardVisible;
    renderBrowseFilters();
    return;
  }
  if (button.id === "browseQueryBtn") { applyBrowseQuery(); return; }
  if (button.dataset.browseSinger) { openBrowseSinger(button.dataset.browseSinger, button.dataset.browseSingerName); return; }
  if (button.dataset.browseOrderId) { orderBrowseSong(button.dataset.browseOrderId); return; }
  if (button.id === "langButton") { toggleLanguage(); return; }
  if (button.dataset.downloadTab) { switchDownloadTab(button.dataset.downloadTab); return; }
  if (button.dataset.rankId) {
    uiState.rankId = button.dataset.rankId;
    uiState.rankName = button.dataset.rankName || button.textContent.trim();
    uiState.rankPage = 1;
    $$("#rankCategories button").forEach((item) => item.classList.toggle("active", item === button));
    $("#rankTitle").textContent = rankDisplayName(uiState.rankName);
    loadRank(1);
    return;
  }
  if (button.dataset.page) openPage(button.dataset.page);
  if (button.dataset.action) playerAction(button.dataset.action);
  if (button.dataset.vocal) updateAudio({ vocalMode: button.dataset.vocal });
  const row = button.closest(".song");
  if (row && button.classList.contains("order")) {
    try {
      await api("/api/v1/queue", { method: "POST", body: JSON.stringify({ songId: row.dataset.id }) });
      button.textContent = t("ordered"); button.disabled = true; toast(t("added")); refresh();
    } catch (error) { toast(error.message); }
  }
  if (row && button.classList.contains("top")) {
    try {
      await api("/api/v1/queue/" + encodeURIComponent(row.dataset.id), { method: "PATCH", body: "{}" });
      toast(t("pinned")); loadQueue();
    } catch (error) { toast(error.message); }
  }
  if (row && button.classList.contains("delete")) {
    try {
      await api("/api/v1/queue/" + encodeURIComponent(row.dataset.id), { method: "DELETE" });
      toast(t("removed")); loadQueue(); refresh();
    } catch (error) { toast(error.message); }
  }
});

$("#browseQuery").addEventListener("keydown", (event) => {
  if (event.key === "Enter") { event.preventDefault(); applyBrowseQuery(); }
});
$("#downloadsPrev").addEventListener("click", () => loadDownloads(uiState.downloadsPage - 1));
$("#downloadsNext").addEventListener("click", () => loadDownloads(uiState.downloadsPage + 1));
$("#rankPrev").addEventListener("click", () => loadRank(uiState.rankPage - 1));
$("#rankNext").addEventListener("click", () => loadRank(uiState.rankPage + 1));
$("#musicVolume").addEventListener("input", (event) => { $("#musicValue").textContent = event.target.value; });
$("#musicVolume").addEventListener("change", (event) => updateAudio({ musicVolume: Number(event.target.value) }));
let heartbeatTimer;
let heartbeatRunning = false;
let downloadsHeartbeatTick = 0;
async function heartbeat() {
  if (heartbeatRunning) return;
  heartbeatRunning = true;
  window.clearTimeout(heartbeatTimer);
  try {
    if (uiState.visible) await refresh();
    if (uiState.visible && $("#downloads").classList.contains("active") && ++downloadsHeartbeatTick % 2 === 0) {
      if (uiState.downloadTab === "active") loadDownloadTasks(true);
      else loadDownloads(uiState.downloadsPage, true);
    }
  } finally {
    heartbeatRunning = false;
    heartbeatTimer = window.setTimeout(heartbeat,
      uiState.connected ? 1500 : Math.min(8000, 1500 * (uiState.heartbeatFailures + 1)));
  }
}
function resumeConnection() {
  uiState.visible = !document.hidden;
  if (uiState.visible) heartbeat();
}
document.addEventListener("visibilitychange", resumeConnection);
window.addEventListener("pageshow", resumeConnection);
window.addEventListener("online", resumeConnection);

let savedLang = "zh";
try { savedLang = localStorage.getItem("ktv-mobile-lang") || "zh"; } catch (_) {}
applyLanguage(savedLang, false);
heartbeat();
loadBrowseLanguages();
loadBrowsePage(1);
