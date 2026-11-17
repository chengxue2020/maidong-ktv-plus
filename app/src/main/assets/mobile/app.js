const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => document.querySelectorAll(selector);

// ─── 中英文切换 ───
// 设备端 LocalRemoteServer 只允许加载 index.html/app.css/logo.css/app.js 四个静态资源，
// 因此字典与切换逻辑全部内置于此，译文沿用 ori 版的 I18N 措辞。
const I18N = {
  zh: {
    title: "麦动 KTV 手机点歌", offline: "连接中断，正在重新连接…",
    connecting: "正在连接麦动 KTV…", connected: "已连接麦动 KTV", queueBadge: "已点歌曲",
    nowPlaying: "正在播放", noSong: "暂无歌曲", pickSong: "请先点一首喜欢的歌",
    replay: "重唱", pause: "暂停", resume: "继续", next: "切歌",
    soundControl: "声音控制", instant: "即时生效",
    accompany: "伴唱", original: "原唱", vocalBadgeAccompany: "伴", vocalBadgeOriginal: "原",
    musicVolume: "音乐音量", micVolume: "麦克风音量", micHwHint: "由功放/混音器硬件调节",
    equalizer: "均衡器", eqOff: "已关闭", eqEnable: "开启均衡器", eqDisable: "关闭均衡器",
    eqReset: "恢复标准", custom: "自定义",
    eqHint: "均衡器作用于本机播放输出，不改动系统音量",
    eqHintGlobal: "当前作用于全局混音（会影响本机所有声音）",
    nextUp: "下一首", emptyQueue: "队列为空", viewQueue: "查看队列",
    songLibrary: "SONG LIBRARY", wantToSing: "想唱什么？", vastLibrary: "海量本地曲库",
    singer: "歌手", song: "歌曲", inputSinger: "输入歌手名或拼音", inputSong: "输入歌曲名或拼音",
    searchModeAria: "搜索方式", search: "搜索",
    singerResults: "歌手搜索结果", songResults: "歌曲搜索结果",
    searching: "正在搜索“{q}”", moreSongs: "加载更多歌曲",
    hotRanking: "热歌排行", loading: "正在加载…", prevPage: "上一页", nextPage: "下一页",
    page: "第 {n} 页",
    playQueue: "PLAY QUEUE", queuedSongs: "已点歌单", songCount: "{n} 首",
    pinTip: "置顶歌曲将排在当前歌曲之后",
    remote: "遥控", searchNav: "搜歌", queuedNav: "已点",
    order: "点歌", top: "置顶", delete: "删除", playing: "正在播放",
    noResults: "没有找到相关歌曲", noResultsHint: "请更换关键词后重试",
    moreSuffix: "，还有更多", resultSummary: "“{q}”{more}",
    hotSummary: "第 {a}-{b} 名", noHot: "暂无热歌排行", noData: "暂无数据",
    hotFailed: "热歌排行加载失败", retryPage: "请点击翻页重试",
    queueEmpty: "还没有点歌", queueEmptyHint: "去曲库选一首喜欢的歌吧",
    inputRequired: "请输入{mode}名称", added: "已加入歌单", pinned: "已置顶为下一首",
    removed: "已从歌单移除", ordered: "已点", operationFailed: "操作失败",
    switchLang: "Switch to English", languageSwitched: "已切换为中文",
    presetStandard: "标准", presetPop: "流行", presetRock: "摇滚", presetJazz: "爵士",
    presetClassical: "古典", presetBass: "重低音", presetVocal: "人声"
  },
  en: {
    title: "麦动 KTV Remote", offline: "Connection lost. Reconnecting…",
    connecting: "Connecting to 麦动 KTV…", connected: "Connected to 麦动 KTV", queueBadge: "Queue",
    nowPlaying: "Now playing", noSong: "No song yet", pickSong: "Pick a song to get started",
    replay: "Replay", pause: "Pause", resume: "Resume", next: "Next",
    soundControl: "Sound control", instant: "Instant",
    accompany: "Vocal Off", original: "Original", vocalBadgeAccompany: "Off", vocalBadgeOriginal: "On",
    musicVolume: "Music volume", micVolume: "Mic volume", micHwHint: "Adjusted by amplifier/mixer hardware",
    equalizer: "Equalizer", eqOff: "Off", eqEnable: "Turn on EQ", eqDisable: "Turn off EQ",
    eqReset: "Reset to standard", custom: "Custom",
    eqHint: "EQ affects this device's playback only; system volume is unchanged",
    eqHintGlobal: "Applied to global mix (affects all sounds on this device)",
    nextUp: "Next Song", emptyQueue: "Queue is empty", viewQueue: "View queue",
    songLibrary: "SONG LIBRARY", wantToSing: "What do you want to sing?", vastLibrary: "Full local library",
    singer: "Singer", song: "Song", inputSinger: "Singer name or pinyin", inputSong: "Song name or pinyin",
    searchModeAria: "Search mode", search: "Search",
    singerResults: "Singer results", songResults: "Song results",
    searching: "Searching “{q}”", moreSongs: "Load more songs",
    hotRanking: "Top charts", loading: "Loading…", prevPage: "Previous", nextPage: "Next",
    page: "Page {n}",
    playQueue: "PLAY QUEUE", queuedSongs: "Play queue", songCount: "{n} songs",
    pinTip: "Priority songs play right after the current one",
    remote: "Remote", searchNav: "Search", queuedNav: "Queued",
    order: "Select", top: "Priority", delete: "Delete", playing: "Playing",
    noResults: "No songs found", noResultsHint: "Try a different keyword",
    moreSuffix: ", more available", resultSummary: "“{q}”{more}",
    hotSummary: "No. {a}-{b}", noHot: "No chart data yet", noData: "No data",
    hotFailed: "Failed to load charts", retryPage: "Turn the page to retry",
    queueEmpty: "No songs queued", queueEmptyHint: "Pick one from the library",
    inputRequired: "Enter a {mode} name", added: "Added to queue", pinned: "Moved to priority",
    removed: "Removed from queue", ordered: "Ordered", operationFailed: "Operation failed",
    switchLang: "切换到中文", languageSwitched: "Switched to English",
    presetStandard: "Standard", presetPop: "Pop", presetRock: "Rock", presetJazz: "Jazz",
    presetClassical: "Classical", presetBass: "Bass boost", presetVocal: "Vocal"
  }
};

const uiState = {
  page: 1, query: "", searchMode: "song", busy: false,
  hotPage: 1, hotPageSize: 10, hotBusy: false,
  visible: true, connected: false, eqEnabled: false,
  equalizer: null, searchAbort: null, searchKey: "", searchHasMore: false,
  lang: "zh", state: null, queueSize: null,
  hotSummaryKey: null, hotSummaryVars: null,
  searchRows: null, hotRows: null, queueRows: null,
  orderedIds: new Set()
};

function markOrdered(id) {
  if (id == null) return;
  uiState.orderedIds.add(String(id));
}

function t(key, vars) {
  const table = I18N[uiState.lang] || I18N.zh;
  const value = table[key] ?? I18N.zh[key] ?? key;
  return String(value).replace(/\{(\w+)\}/g, (_, name) => (vars && vars[name] != null ? String(vars[name]) : ""));
}

// 设备端均衡器预设名保持中文用于回传, 仅显示时按语言映射。
function presetLabel(name) {
  const map = {
    "标准": "presetStandard", "流行": "presetPop", "摇滚": "presetRock", "爵士": "presetJazz",
    "古典": "presetClassical", "重低音": "presetBass", "人声": "presetVocal"
  };
  return uiState.lang === "en" && map[name] ? t(map[name]) : name;
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

// 切语言后重绘由 JS 动态生成、没有静态 data-i18n 标记的文案。
function renderDynamicTexts() {
  if (uiState.state) renderStateText(uiState.state);
  $("#query").placeholder = t(uiState.searchMode === "singer" ? "inputSinger" : "inputSong");
  if (!$("#searchResultBlock").classList.contains("hidden")) {
    $("#resultTitle").textContent = t(uiState.searchMode === "singer" ? "singerResults" : "songResults");
    if (uiState.query) {
      $("#resultSummary").textContent = t("resultSummary", {
        q: uiState.query, more: uiState.searchHasMore ? t("moreSuffix") : ""
      });
    }
  }
  $("#hotPage").textContent = t("page", { n: uiState.hotPage });
  if (uiState.hotSummaryKey) $("#hotSummary").textContent = t(uiState.hotSummaryKey, uiState.hotSummaryVars || {});
  if (uiState.queueSize != null) $("#queueCount").textContent = t("songCount", { n: uiState.queueSize });
  renderCachedLists();
  if (!$("#eqBody")?.classList.contains("hidden")) renderEqualizer(uiState.equalizer);
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

  $("#langButton").textContent = uiState.lang === "zh" ? "EN" : "中文";
  $("#langButton").setAttribute("aria-label", t("switchLang"));

  renderConnectionText();
  renderDynamicTexts();
}

function toggleLanguage() {
  applyLanguage(uiState.lang === "zh" ? "en" : "zh");
  toast(t("languageSwitched"));
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    cache: "no-store",
    headers: { "Content-Type": "application/json" },
    ...options
  });
  const data = await response.json();
  if (!response.ok || data.ok === false) throw new Error(data.error || t("operationFailed"));
  setConnected(true);
  return data;
}

function toast(text) {
  const element = $("#toast");
  element.textContent = text;
  element.classList.add("show");
  window.setTimeout(() => element.classList.remove("show"), 1600);
}

function songRow(song, queue = false, current = false, index = 0, ranking = false) {
  const number = current ? "♪" : String(index + 1).padStart(2, "0");
  const ordered = !queue && uiState.orderedIds.has(String(song.id));
  const actions = queue && !current
    ? `<button class="top" data-i18n="top">${t("top")}</button><button class="delete" aria-label="${t("delete")}" data-i18n-aria="delete">×</button>`
    : !queue ? `<button class="order" data-i18n="${ordered ? "ordered" : "order"}"${ordered ? " disabled" : ""}>${t(ordered ? "ordered" : "order")}</button>` : "";
  return `<div class="song${ranking && index < 3 ? " podium" : ""}" data-id="${escapeHtml(song.id)}">
    <div class="song-index">${number}</div>
    <div class="info">${current ? `<span class="playing" data-i18n="playing">${t("playing")}</span>` : ""}<b>${escapeHtml(song.title)}</b><p>${escapeHtml(song.singer)}${song.language ? " · " + escapeHtml(song.language) : ""}</p></div>
    ${actions}
  </div>`;
}

function renderCachedLists() {
  if (uiState.searchRows) {
    $("#results").innerHTML = uiState.searchRows.songs.map((song, index) =>
      songRow(song, false, false, uiState.searchRows.start + index)).join("") ||
      `<div class="empty">${t("noResults")}<br><small>${t("noResultsHint")}</small></div>`;
  }
  if (uiState.hotRows) {
    $("#hotList").innerHTML = uiState.hotRows.songs.map((song, index) =>
      songRow(song, false, false, uiState.hotRows.start + index, true)).join("") ||
      `<div class="empty compact">${t("noHot")}</div>`;
  }
  if (uiState.queueRows) {
    $("#queueList").innerHTML = uiState.queueRows.songs.map((song, index) =>
      songRow(song, true, index === 0, index)).join("") ||
      `<div class="empty">${t("queueEmpty")}<br><small>${t("queueEmptyHint")}</small></div>`;
  }
}

function openPage(name) {
  $$("nav button,.page").forEach((element) => element.classList.remove("active"));
  $(`nav button[data-page="${name}"]`)?.classList.add("active");
  $("#" + name)?.classList.add("active");
  if (name === "queue") loadQueue();
  if (name === "search" && !$("#hotList").children.length) loadHot(1);
}

function renderStateText(data) {
  const current = data.current || {};
  $("#nowTitle").textContent = current.title || t("noSong");
  $("#nowSinger").textContent = current.singer || t("pickSong");
  $("#playButton span:last-child").textContent = data.playing ? t("pause") : t("resume");
  $("#playButton").dataset.action = data.playing ? "pause" : "resume";
  $("#playButton .control-icon").textContent = data.playing ? "Ⅱ" : "▶";
  $("#nextSong").textContent = data.next?.title || t("emptyQueue");
}

async function refresh() {
  try {
    const data = await api("/api/v1/state");
    uiState.state = data;
    const count = data.queueSize || 0;
    renderStateText(data);
    uiState.queueSize = count;
    $("#queueCount").textContent = t("songCount", { n: count });
    $("#queueBadge b").textContent = count;
    $("#navCount").textContent = count;
    $("#navCount").classList.toggle("hidden", count === 0);
    $("#musicVolume").value = data.musicVolume ?? 85;
    $("#micVolume").value = data.micVolume ?? 70;
    $("#musicValue").textContent = data.musicVolume ?? 85;
    // 麦克风音量由功放/混音器硬件调节(滑杆已禁用), 仅保留显示值。
    const micHw = $("#micValue");
    if (micHw) micHw.textContent = data.micVolume ?? 70;
    $("#original").classList.toggle("active", data.vocalMode === "original");
    $("#accompany").classList.toggle("active", data.vocalMode !== "original");
    // 均衡器卡片默认收起: 收起时跳过 DOM 更新(避免每秒轮询重绘), 展开时再渲染一次。
    uiState.equalizer = data.equalizer;
    if (!$("#eqBody")?.classList.contains("hidden")) renderEqualizer(data.equalizer);
  } catch (_) {
    setConnected(false);
  }
}

// ─── 均衡器（与设备端 KtvEqualizer 同步，频段由设备真实能力决定）───
function renderEqualizer(eq) {
  if (!eq) return;
  uiState.eqEnabled = !!eq.enabled;
  const state = $("#eqState");
  if (state) state.textContent = eq.enabled ? (presetLabel(eq.preset) || t("custom")) : t("eqOff");
  const toggle = $("#eqToggle");
  if (toggle) {
    toggle.textContent = eq.enabled ? t("eqDisable") : t("eqEnable");
    toggle.classList.toggle("active", !!eq.enabled);
  }
  const hint = $("#eqHint");
  if (hint) hint.textContent = t(eq.globalMix ? "eqHintGlobal" : "eqHint");
  const bands = Array.isArray(eq.bands) ? eq.bands : [];
  const container = $("#eqBands");
  if (container && container.childElementCount !== bands.length) {
    container.innerHTML = bands.map((band, index) => `<div class="eq-band"><span>${escapeHtml(band.label)}Hz</span><b><em id="eqValue${index}">${Number(band.level).toFixed(1)}</em>dB</b><input id="eqBand${index}" type="range" min="${eq.minDb}" max="${eq.maxDb}" step="1" value="${band.level}"></div>`).join("");
    bands.forEach((band, index) => {
      const input = $("#eqBand" + index);
      if (!input) return;
      attachThumbOnlyDrag(input);
      input.addEventListener("input", () => {
        const value = $("#eqValue" + index);
        if (value) value.textContent = Number(input.value).toFixed(1);
      });
      input.addEventListener("change", () => updateEqualizer({ levels: currentEqLevels() }));
    });
  } else if (container) {
    bands.forEach((band, index) => {
      const input = $("#eqBand" + index);
      // 正在拖动的那根滑杆不要被轮询覆盖。
      if (input && document.activeElement !== input) input.value = band.level;
      const value = $("#eqValue" + index);
      if (value) value.textContent = Number(band.level).toFixed(1);
    });
  }
  const presets = Array.isArray(eq.presets) ? eq.presets : [];
  const presetBox = $("#eqPresets");
  if (presetBox && presetBox.childElementCount !== presets.length) {
    presetBox.innerHTML = presets.map((name) => `<button type="button" class="eq-preset" data-preset="${escapeHtml(name)}">${escapeHtml(presetLabel(name))}</button>`).join("");
    presetBox.querySelectorAll(".eq-preset").forEach((button) => {
      button.addEventListener("click", () => updateEqualizer({ preset: button.dataset.preset }));
    });
  } else if (presetBox) {
    presetBox.querySelectorAll(".eq-preset").forEach((button) => {
      button.textContent = presetLabel(button.dataset.preset);
    });
  }
  presetBox?.querySelectorAll(".eq-preset").forEach((button) => {
    button.classList.toggle("active", button.dataset.preset === eq.preset);
  });
}

function currentEqLevels() {
  return Array.from($$("#eqBands input[type=range]")).map((input) => Number(input.value));
}

/**
 * 触控优化：滑块必须按住「控件点（滑块）」才能拖动，点轨道空白处不跳变数值。
 */
function attachThumbOnlyDrag(input) {
  if (!input || input.dataset.thumbOnly === "1") return;
  input.dataset.thumbOnly = "1";
  const blocked = (event) => {
    const rect = input.getBoundingClientRect();
    const point = event.touches && event.touches.length ? event.touches[0] : event;
    const x = point.clientX;
    const y = point.clientY;
    const thumb = 32;
    const trackLeft = rect.left + thumb / 2;
    const trackRight = rect.right - thumb / 2;
    const span = trackRight - trackLeft;
    if (span <= 0 || y < rect.top - thumb || y > rect.bottom + thumb) return true;
    const min = Number(input.min || 0);
    const max = Number(input.max || 100);
    const ratio = (Number(input.value) - min) / (max - min || 1);
    const thumbX = trackLeft + ratio * span;
    return Math.abs(x - thumbX) > Math.max(thumb, 26);
  };
  const guard = (event) => { if (blocked(event)) event.preventDefault(); };
  input.addEventListener("pointerdown", guard, { passive: false });
  input.addEventListener("touchstart", guard, { passive: false });
  input.addEventListener("mousedown", guard);
}

async function updateEqualizer(values) {
  try {
    await api("/api/v1/player/equalizer", { method: "PATCH", body: JSON.stringify(values) });
    await refresh();
  } catch (error) { toast(error.message); }
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

async function search(reset = true) {
  if (reset) {
    const nextQuery = $("#query").value.trim();
    if (!nextQuery) {
      toast(t("inputRequired", { mode: t(uiState.searchMode === "singer" ? "singer" : "song") }));
      $("#query").focus();
      return;
    }
    // 同一关键词+同一搜索方式且结果已渲染 → 不重复请求设备(设备端也要查 1GB 曲库)。
    const key = `${uiState.searchMode}|${nextQuery}`;
    if (key === uiState.searchKey && $("#results").childElementCount) {
      $("#searchResultBlock").scrollIntoView({ behavior: "smooth", block: "start" });
      return;
    }
    uiState.page = 1;
    uiState.query = nextQuery;
    uiState.searchKey = key;
    uiState.searchHasMore = false;
    uiState.searchSongs = [];
    uiState.searchRows = null;
    $("#results").innerHTML = "";
    $("#searchResultBlock").classList.remove("hidden");
    $("#resultTitle").textContent = t(uiState.searchMode === "singer" ? "singerResults" : "songResults");
    $("#resultSummary").textContent = t("searching", { q: uiState.query });
    $("#more").classList.add("hidden");
    if (uiState.searchAbort) uiState.searchAbort.abort();
    uiState.searchAbort = new AbortController();
  }
  const signal = uiState.searchAbort ? uiState.searchAbort.signal : undefined;
  const requestedPage = uiState.page;
  uiState.busy = true;
  try {
    const data = await api(`/api/v1/songs?q=${encodeURIComponent(uiState.query)}&type=${uiState.searchMode}&page=${requestedPage}&pageSize=20`, { signal });
    const songs = data.songs || [];
    const start = (requestedPage - 1) * 20;
    const rows = songs.map((song, index) => songRow(song, false, false, start + index)).join("");
    if (rows) $("#results").insertAdjacentHTML("beforeend", rows);
    else if (reset) $("#results").innerHTML = `<div class="empty">${t("noResults")}<br><small>${t("noResultsHint")}</small></div>`;
    uiState.searchHasMore = !!data.hasMore;
    $("#resultSummary").textContent = t("resultSummary", {
      q: uiState.query, more: data.hasMore ? t("moreSuffix") : ""
    });
    $("#more").classList.toggle("hidden", !data.hasMore);
    uiState.page = requestedPage + 1;
    if (!uiState.searchSongs) uiState.searchSongs = [];
    uiState.searchSongs.push(...songs);
    uiState.searchRows = { songs: uiState.searchSongs.slice(), start: 0 };
    // 预热下一页: 设备端会把它缓存下来, 用户点「更多」时基本瞬时。
    if (requestedPage === 1 && data.hasMore) {
      window.setTimeout(() => {
        api(`/api/v1/songs?q=${encodeURIComponent(uiState.query)}&type=${uiState.searchMode}&page=2&pageSize=20`)
          .catch(() => {});
      }, 500);
    }
  } catch (error) {
    // 被下一次搜索取消属于正常情况, 不弹提示。
    if (error && error.name !== "AbortError") toast(error.message);
  } finally { uiState.busy = false; }
}

async function loadHot(page) {
  if (uiState.hotBusy || page < 1) return;
  uiState.hotBusy = true;
  $("#hotPrev").disabled = true;
  $("#hotNext").disabled = true;
  uiState.hotSummaryKey = "loading";
  $("#hotSummary").textContent = t("loading");
  try {
    const data = await api(`/api/v1/hot?page=${page}&pageSize=${uiState.hotPageSize}`);
    uiState.hotPage = data.page || page;
    const songs = data.songs || [];
    const start = (uiState.hotPage - 1) * uiState.hotPageSize;
    uiState.hotRows = { songs, start };
    $("#hotList").innerHTML = songs.map((song, index) =>
      songRow(song, false, false, start + index, true)).join("") ||
      `<div class="empty compact">${t("noHot")}</div>`;
    $("#hotPage").textContent = t("page", { n: uiState.hotPage });
    if (songs.length) {
      uiState.hotSummaryKey = "hotSummary";
      uiState.hotSummaryVars = { a: start + 1, b: start + songs.length };
    } else {
      uiState.hotSummaryKey = "noData";
      uiState.hotSummaryVars = null;
    }
    $("#hotSummary").textContent = t(uiState.hotSummaryKey, uiState.hotSummaryVars || {});
    $("#hotPrev").disabled = !data.hasPrevious;
    $("#hotNext").disabled = !data.hasMore;
    if (page > 1) $("#hot").scrollIntoView({ behavior: "smooth", block: "start" });
  } catch (error) {
    uiState.hotSummaryKey = "hotFailed";
    $("#hotList").innerHTML = `<div class="empty compact">${t("retryPage")}</div>`;
    $("#hotSummary").textContent = t("hotFailed");
    $("#hotPrev").disabled = uiState.hotPage <= 1;
    $("#hotNext").disabled = false;
    toast(error.message);
  } finally { uiState.hotBusy = false; }
}

async function loadQueue() {
  try {
    const data = await api("/api/v1/queue");
    const queue = data.queue || [];
    uiState.queueSize = queue.length;
    uiState.queueRows = { songs: queue };
    $("#queueCount").textContent = t("songCount", { n: queue.length });
    $("#queueList").innerHTML = queue.map((song, index) => songRow(song, true, index === 0, index)).join("") ||
      `<div class="empty">${t("queueEmpty")}<br><small>${t("queueEmptyHint")}</small></div>`;
  } catch (error) { toast(error.message); }
}

document.addEventListener("click", async (event) => {
  const button = event.target.closest("button");
  if (!button) return;
  if (button.id === "langButton") { toggleLanguage(); return; }
  if (button.dataset.page) openPage(button.dataset.page);
  if (button.dataset.action) playerAction(button.dataset.action);
  if (button.dataset.vocal) updateAudio({ vocalMode: button.dataset.vocal });
  if (button.dataset.searchMode) {
    uiState.searchMode = button.dataset.searchMode;
    $$('[data-search-mode]').forEach((item) => item.classList.toggle("active", item === button));
    $("#query").placeholder = t(uiState.searchMode === "singer" ? "inputSinger" : "inputSong");
    $("#query").value = "";
    $("#results").innerHTML = "";
    $("#searchResultBlock").classList.add("hidden");
    $("#more").classList.add("hidden");
    $("#query").focus();
  }
  const row = button.closest(".song");
  if (row && button.classList.contains("order")) {
    try {
      await api("/api/v1/queue", { method: "POST", body: JSON.stringify({ songId: row.dataset.id }) });
      markOrdered(row.dataset.id);
      button.dataset.i18n = "ordered"; button.textContent = t("ordered"); button.disabled = true; toast(t("added")); refresh();
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

$("#searchForm").addEventListener("submit", (event) => { event.preventDefault(); search(); });
$("#more").addEventListener("click", () => search(false));
$("#hotPrev").addEventListener("click", () => loadHot(uiState.hotPage - 1));
$("#hotNext").addEventListener("click", () => loadHot(uiState.hotPage + 1));
// 音乐音量可调; 麦克风音量由功放硬件控制, 不再向设备发送无效果请求。
attachThumbOnlyDrag($("#musicVolume"));
$("#musicVolume").addEventListener("input", (event) => { $("#musicValue").textContent = event.target.value; });
$("#musicVolume").addEventListener("change", (event) => updateAudio({ musicVolume: Number(event.target.value) }));
// 均衡器入口(与设备端弹窗等价): 开关 / 恢复标准
$("#eqToggle")?.addEventListener("click", () => updateEqualizer({ enabled: !uiState.eqEnabled }));
$("#eqReset")?.addEventListener("click", () => updateEqualizer({ preset: "标准" }));
// 均衡器文字栏作为折叠按钮: 默认收起; 展开后在同一次页面会话内保持, 重新加载页面回到收起态。
$("#eqCollapse")?.addEventListener("click", () => {
  const body = $("#eqBody");
  if (!body) return;
  const collapsed = body.classList.toggle("hidden");
  $("#eqCollapse").setAttribute("aria-expanded", collapsed ? "false" : "true");
  // 展开时补一次渲染(收起期间轮询跳过了 EQ 的 DOM 更新)。
  if (!collapsed) renderEqualizer(uiState.equalizer);
});
document.addEventListener("visibilitychange", () => { uiState.visible = !document.hidden; if (uiState.visible) refresh(); });
// 搜索进行中暂停状态轮询: 避免与设备端 1GB 曲库查询抢 I/O。
window.setInterval(() => { if (uiState.visible && !uiState.busy) refresh(); }, 1000);

let savedLang = "zh";
try { savedLang = localStorage.getItem("ktv-mobile-lang") || "zh"; } catch (_) {}
applyLanguage(savedLang, false);
refresh();
loadHot(1);
