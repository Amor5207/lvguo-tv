/* 绿果TV · PC 网页版 v3（Apple TV 风）：路由、页面、货架、播放器。共享层见 /shared/core.js */
(function () {
  'use strict';
  var $ = LV.$, $$ = LV.$$, esc = LV.esc, api = LV.api;
  var view = $('#view'), nav = $('#nav'), navbg = $('#navbg');
  var me = { loggedIn: false }, hist = [], cache = {}, cleanups = [];
  var recentPlayback = new Map(), historyRequest = 0, historyLoaded = false;
  var BROWSE_ONLY = false, browseStorageKey = '', browseStorageWarned = false;
  function saveBrowseHistory() {
    try { localStorage.setItem(browseStorageKey, JSON.stringify(hist.slice(0, 300))); }
    catch(e) { if (!browseStorageWarned) { browseStorageWarned = true; LV.toast('浏览器存储不可用，观看记录无法保存'); } }
  }
  function readBrowseHistory() {
    try { var rows = JSON.parse(localStorage.getItem(browseStorageKey) || '[]'); return Array.isArray(rows) ? rows : []; } catch(e) { return []; }
  }
  async function browseCachePresent() {
    try {
      var token=localStorage.getItem('lvguo.browse.proof')||'';if(!/^[0-9a-f]{32}$/.test(token))return false;
      var r=await fetch('/shared/browse-proof?token='+token,{cache:'only-if-cached',mode:'same-origin',credentials:'same-origin'});
      return r.ok;
    } catch(e) { return false; }
  }
  window.addEventListener('lv:browse-progress', function(e) {
    if (!BROWSE_ONLY || !e.detail) return;
    var p=e.detail, row={seriesId:p.id,vid:p.vid,episodeIndex:p.index,positionMs:p.pos,time:Date.now(),title:p.title||'',cover:p.cover||'',episodeCount:p.episodeCount||0};
    hist=[row].concat(hist.filter(function(x){return x.seriesId!==row.seriesId}));saveBrowseHistory();
    window.dispatchEvent(new CustomEvent('lv:progress',{detail:row}));
  });
  var TTL = 90 * 1000, BOARDS = null;
  var memo = {}, pendingRestore = 0, navFresh = false, curRoute = '', hasHero = false;
  var BASE = /^\/pc(?:\/|$)/.test(location.pathname) ? '/pc' : '';

  // ------------------------------------------------------------ 小工具
  function remember(list) { (list || []).forEach(function (s) { var id = s.id || s.seriesId; if (id) cache[id] = Object.assign(cache[id] || {}, s); }); }
  function cleanup() { cleanups.splice(0).forEach(function (f) { try { f(); } catch (e) {} }); }
  function title(t) { document.title = t ? t + ' - 绿果WEB' : '绿果WEB'; }
  function mount(h) { view.innerHTML = '<div class="page">' + h + '</div>'; return view.firstChild; }
  function head(t, sub) { return '<div class="ph"><h1 class="lt">' + t + '</h1>' + (sub ? '<p>' + sub + '</p>' : '') + '</div>'; }
  function empty(icon, h, sub, btn) { return '<div class="empty"><i>' + ic(icon) + '</i><b>' + esc(h) + '</b>' + (sub ? '<div>' + esc(sub) + '</div>' : '') + (btn || '') + '</div>'; }
  function failed(msg) { return empty('refresh', '加载失败', msg, '<button class="btn primary" data-retry>' + ic('refresh') + '重试</button>'); }
  function cats(s, n) { return (s.category || '').split(',').filter(Boolean).slice(0, n || 2); }
  function playsShort(n) { return LV.plays(n).replace('次播放', ''); }
  function hl(text, q) {
    text = esc(text); if (!q) return text;
    var e = esc(q).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    return text.replace(new RegExp('(' + e + ')', 'ig'), '<mark>$1</mark>');
  }
  // 取榜单前 want 部：第一页常常很少，最多再翻 3 页
  async function boardItems(id, want) {
    var list = [], seen = {}, off = 0, sess = '';
    for (var k = 0; k < 4 && list.length < want; k++) {
      var r = await api('/api/rank', { query: { board: id, offset: off, session: sess }, ttl: TTL });
      r.items.forEach(function (s) { if (!seen[s.id] && list.length < want) { seen[s.id] = 1; list.push(s); } });
      if (!r.more || !r.items.length) break; off = r.next; sess = r.session;
    }
    remember(list); return list;
  }
  async function boards() { if (!BOARDS) BOARDS = (await api('/api/category/options', { query: { type: 'all' }, ttl: 600000 })).boards; return BOARDS; }

  // ------------------------------------------------------------ 组件
  function card(s, o) {
    o = o || {}; var id = s.id || s.seriesId, eps = s.episodes || s.episodeCount;
    return '<button class="card' + (o.i != null ? ' rise' : '') + '"' + (o.i != null ? ' style="--i:' + Math.min(o.i, 17) + '"' : '') + ' data-id="' + id + '"><div class="poster">' + LV.img(s.cover, s.title, o.w || 360) +
      (o.rank ? '<span class="rk' + (o.rank <= 3 ? ' top' : '') + '">' + o.rank + '</span>' : '') +
      (eps && !o.nobadge ? '<span class="badge">' + eps + '集</span>' : '') + (o.x ? '<span class="x" data-x="' + o.x + '" title="移除">' + ic('close') + '</span>' : '') + '</div>' +
      '<div class="t">' + (o.th || esc(s.title)) + '</div>' + (o.sub ? '<div class="s">' + esc(o.sub) + '</div>' : '') + '</button>';
  }
  function cardSk(n) { return Array.from({ length: n }, function () { return '<div class="card sk"><div class="poster skel"></div><div class="t skel"></div></div>'; }).join(''); }
  function gridCols() {
    var g = $('.grid');
    var n = parseInt(getComputedStyle(g || document.documentElement).getPropertyValue('--n'), 10);
    if (n > 0) return n;
    return innerWidth <= 760 ? 3 : innerWidth <= 1366 ? 5 : innerWidth >= 2000 ? 8 : innerWidth >= 1680 ? 7 : 6;
  }
  function gridSk(rows) { return cardSk(gridCols() * (rows || 2)); }
  function cwCard(c, i, del) {
    var pr = c.episodeCount ? Math.min(1, (c.episodeIndex + (c.positionMs > 0 ? 0.5 : 0)) / c.episodeCount) : 0;
    return '<button class="cw" data-cw="' + c.seriesId + '"><div class="bgx">' + LV.img(c.cover, '', 200) + '</div><div class="pp">' + LV.img(c.cover, c.title, 240) + '</div>' +
      '<div class="info"><b>' + esc(c.title) + '</b><span>第 ' + (c.episodeIndex + 1) + ' 集' + (c.episodeCount ? ' / 共 ' + c.episodeCount + ' 集' : '') + ' · ' + LV.ago(c.time) + '</span><div class="pb"><i style="width:' + pr * 100 + '%"></i></div></div>' +
      '<span class="go">' + ic('play2') + '</span>' + (del ? '<span class="del" data-x="history" title="删除记录">' + ic('close') + '</span>' : '') + '</button>';
  }
  function cwSk(n) { return Array.from({ length: n }, function () { return '<div class="cw sk skel"></div>'; }).join(''); }
  // 继续观看 / 本机记录的小卡（TV 风：小海报 + 进度条），点卡续播（data-cw）
  function cwMini(c, i, del) {
    var pr = c.episodeCount ? Math.min(1, (c.episodeIndex + (c.positionMs > 0 ? 0.5 : 0)) / c.episodeCount) : 0;
    return '<button class="card rise" style="--i:' + Math.min(i, 17) + '" data-cw="' + c.seriesId + '"><div class="poster">' + LV.img(c.cover, c.title, 360) +
      '<div class="cwpb"><i style="width:' + pr * 100 + '%"></i></div>' +
      (del ? '<span class="x" data-x="history" title="移除记录">' + ic('close') + '</span>' : '') + '</div>' +
      '<div class="t">' + esc(c.title) + '</div><div class="s">看到第 ' + ((c.episodeIndex || 0) + 1) + ' 集' + (c.episodeCount ? ' / 共 ' + c.episodeCount + ' 集' : '') + '</div></button>';
  }
  function crRow(s, no, i) {
    var meta = [s.episodes ? '全 ' + s.episodes + ' 集' : '', LV.plays(s.plays)].concat(cats(s, 2)).filter(Boolean).join(' · ');
    return '<button class="cr" data-id="' + s.id + '"><span class="rn">' + no + '</span><div class="poster">' + LV.img(s.cover, s.title, 160) + '</div><div class="ri"><b>' + esc(s.title) + '</b><span>' + esc(meta) + '</span></div><span class="go" data-play="' + s.id + '">' + ic('play2') + '</span></button>';
  }
  function crSk(n) { return Array.from({ length: n }, function () { return '<div class="cr sk"><span class="rn"></span><div class="poster skel"></div><div class="ri"><div class="skel t m"></div><div class="skel t s"></div></div></div>'; }).join(''); }
  function arrows() { return '<button class="arr l off" data-arr="-1" aria-label="向左">' + ic('back') + '</button><button class="arr r" data-arr="1" aria-label="向右">' + ic('chev') + '</button>'; }
  function shelf(t, more, cls, inner, attrs) {
    return '<section class="shelf"' + (attrs || '') + '><div class="sh"><h2>' + t + '</h2>' + (more ? '<a class="more" href="' + more + '">查看全部' + ic('chev') + '</a>' : '') + '</div>' + arrows() + '<div class="row ' + (cls || '') + '">' + inner + '</div></section>';
  }
  function seg(items, cur, attr) { return '<div class="seg"><i class="th"></i>' + items.map(function (x) { return '<button ' + attr + '="' + x[0] + '" class="' + (x[0] === cur ? 'on' : '') + '">' + esc(x[1]) + '</button>'; }).join('') + '</div>'; }
  function placeSeg(root) {
    $$('.seg', root || document).forEach(function (s) {
      var b = $('button.on', s), t = $('.th', s); if (!b || !t) return;
      var first = !t.classList.contains('show');
      if (first) t.style.transition = 'none';
      t.style.width = b.offsetWidth + 'px'; t.style.transform = 'translateX(' + b.offsetLeft + 'px)'; t.classList.add('show');
      if (first) { void t.offsetWidth; t.style.transition = ''; }
    });
  }
  // 货架箭头：随滚动位置启用/禁用；点击翻一页
  function arrState(row) {
    var sec = row.closest('.shelf'); if (!sec || !$('.arr', sec)) return;
    var max = row.scrollWidth - row.clientWidth - 4;
    $('.arr.l', sec).classList.toggle('off', row.scrollLeft <= 4);
    $('.arr.r', sec).classList.toggle('off', row.scrollLeft >= max);
  }
  var arrT = 0;
  document.addEventListener('scroll', function (e) { var t = e.target; if (t.classList && t.classList.contains('row')) { cancelAnimationFrame(arrT); arrT = requestAnimationFrame(function () { arrState(t); }); } }, true);
  // 翻一页：按卡片宽度对齐，ease-in-out 600ms；动画期间关吸附，结束后恢复
  function glide(row, dir) {
    if (row._g) cancelAnimationFrame(row._g);
    var c = row.children[1] ? row.children[1].offsetLeft - row.children[0].offsetLeft : row.clientWidth;
    var per = Math.max(1, Math.floor((row.clientWidth - 80) / c));
    var from = row.scrollLeft, max = row.scrollWidth - row.clientWidth;
    var to = Math.max(0, Math.min(max, Math.round(from / c + dir * per) * c));
    if (to === from) return;
    var t0 = performance.now(), D = 620;
    row.style.scrollSnapType = 'none';
    (function step(t) {
      var k = Math.min(1, (t - t0) / D), e = k < .5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2;
      row.scrollLeft = from + (to - from) * e;
      if (k < 1) row._g = requestAnimationFrame(step); else { row._g = 0; row.style.scrollSnapType = ''; arrState(row); }
    })(t0);
  }
  // 鼠标按住拖动横向滚动（触控板本来就能横滑）；拖动过就吞掉这次点击
  (function () {
    var drag = null;
    document.addEventListener('pointerdown', function (e) {
      if (e.pointerType !== 'mouse' || e.button !== 0) return;
      var row = e.target.closest('.row'); if (!row || e.target.closest('.arr')) return;
      drag = { row: row, x: e.clientX, left: row.scrollLeft, moved: false, id: e.pointerId };
    });
    document.addEventListener('pointermove', function (e) {
      if (!drag) return; var dx = e.clientX - drag.x;
      if (!drag.moved && Math.abs(dx) > 6) { drag.moved = true; drag.row.classList.add('dragging'); drag.row.style.scrollSnapType = 'none'; }
      if (drag.moved) { drag.row.scrollLeft = drag.left - dx; e.preventDefault(); }
    });
    document.addEventListener('pointerup', function () {
      if (!drag) return; var d = drag; drag = null;
      if (d.moved) {
        d.row.classList.remove('dragging');
        var c = d.row.children[1] ? d.row.children[1].offsetLeft - d.row.children[0].offsetLeft : 1;
        var to = Math.round(d.row.scrollLeft / c) * c;
        d.row.scrollTo({ left: to, behavior: 'smooth' }); setTimeout(function () { d.row.style.scrollSnapType = ''; arrState(d.row); }, 400);
        var stop = function (ev) { ev.stopPropagation(); ev.preventDefault(); }; window.addEventListener('click', stop, { capture: true, once: true }); setTimeout(function () { window.removeEventListener('click', stop, true); }, 50);
      }
    });
    document.addEventListener('dragstart', function (e) { if (e.target.closest && e.target.closest('.row')) e.preventDefault(); });
  })();
  function rowsReady(root) { $$('.row', root).forEach(arrState); }
  window.addEventListener('resize', LV.debounce(function () { rowsReady(view); placeSeg(); setTab(curTab); }, 120));

  // ------------------------------------------------------------ 导航
  var tabs = $('#tabs'), hlb = $('.hl', tabs), curTab = '';
  function setTab(name) {
    curTab = name; var a = null;
    $$('a', tabs).forEach(function (x) { var on = x.dataset.tab === name; x.classList.toggle('on', on); if (on) a = x; });
    if (!a) return hlb.classList.remove('show');
    var first = !hlb.classList.contains('show');
    if (first) hlb.style.transition = 'none';
    hlb.style.width = a.offsetWidth + 'px'; hlb.style.transform = 'translateX(' + a.offsetLeft + 'px)'; hlb.classList.add('show');
    if (first) { void hlb.offsetWidth; hlb.style.transition = ''; }
  }
  // 首页始终显示导航背景，其他页面在滚动后显示。
  var scT = 0;
  function onScroll() {
    scT = 0; var y = window.scrollY, hero = $('.hero', view), heroH = hero ? hero.offsetHeight : 0;
    var over = hasHero && y < heroH - nav.offsetHeight;
    nav.classList.toggle('over', over);
    nav.classList.toggle('solid', !over && y > 4); navbg.classList.toggle('on', curTab === 'home' || curTab === 'feed' || y > 4);
    var b2 = $('.bar2', view), stuck = !!b2 && b2.getBoundingClientRect().top <= nav.offsetHeight + 1;
    if (b2) b2.classList.toggle('stuck', stuck);
  }
  window.addEventListener('scroll', function () { if (!scT) scT = requestAnimationFrame(onScroll); }, { passive: true });

  function renderAcct() {
    var b = $('#acct');
    if (LV.loggedIn && me.profile) { var p = me.profile; b.innerHTML = (p.avatar ? LV.img(p.avatar, '', 120, true) : '') + '<em>' + esc((p.name || '绿')[0]) + '</em>'; b.title = p.name || '账号'; }
    else { b.innerHTML = ic('user'); b.title = GUEST ? '游客' : '登录'; }
  }
  function closeMenu() { var m = $('.menu'); if (m) m.remove(); }
  $('#acct').onclick = function (e) {
    e.stopPropagation();
    if ($('.menu')) return closeMenu();
    if (GUEST) {   // 游客：菜单里只有「前往登录」
      var g = document.createElement('div'); g.className = 'menu';
      g.innerHTML = BROWSE_ONLY ? '<div class="mh"><b>仅浏览</b><span>观看记录仅保存在本机</span></div><hr><a href="/mine">' + ic('film') + '观看历史</a>' : '<div class="mh"><b>临时身份</b><span>登录后可绑定红果账号</span></div><hr><a href="/mine">' + ic('film') + '观看历史</a><button data-golog>' + ic('user') + '登录并绑定</button>';
      g.onclick = function (ev) { if (ev.target.closest('[data-golog]')) goLogin(); closeMenu(); };
      return document.body.appendChild(g);
    }
    if (!LV.loggedIn) return showLogin();
    var p = me.profile || {}, m = document.createElement('div'); m.className = 'menu';
    m.innerHTML = '<div class="mh"><b>' + esc(p.name || '红果用户') + '</b><span>' + (me.daysLeft >= 0 ? '登录有效期还剩 ' + me.daysLeft + ' 天' : '已登录') + '</span></div><hr>' +
      '<a href="/mine">' + ic('film') + '我的</a><a href="/settings">' + ic('gear') + '设置</a>' + (SITE.admin ? '<a href="/admin">' + ic('sliders') + '管理后台</a>' : '') + '<hr><button data-out class="danger">' + ic('logout') + '退出登录</button>';
    m.onclick = function (ev) { if (ev.target.closest('[data-out]')) logout(); closeMenu(); };
    document.body.appendChild(m);
  };
  document.addEventListener('click', function (e) { if (!e.target.closest('.menu,#acct')) closeMenu(); });
  async function logout() {
    if (!(await confirmBox('退出登录？', '退出后需重新登录红果账号。', '退出登录', true))) return;
    try { await api('/api/logout', { body: {} }); } catch (e) {}
    persist('me', { loggedIn: false }, 7 * 86400e3); persist('hist', [], 7 * 86400e3);
    location.reload();
  }
  document.addEventListener('keydown', function (e) {
    if ((e.key === '/' || (e.key === 'k' && (e.metaKey || e.ctrlKey))) && !/INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName) && !PL) {
      e.preventDefault(); openSearch();
    }
  });

  // ------------------------------------------------------------ 路由（HTML5 History）
  var hops = 0;
  function stripBase(p) { return p.replace(/^\/pc(?=\/|$)/, '') || '/'; }
  function currentURL() { return stripBase(location.pathname) + location.search; }
  function fullPath(url) { return BASE ? BASE + (url === '/' ? '' : url) : url; }
  function isAppPath(p) {
    return /^\/(?:pc(?:\/|$))?(?:$|home$|feed$|rank$|category$|mine(?:\/(?:history|fav|reserve))?$|detail\/\d+$|settings$|search$)/.test(p);
  }
  function isHomePath() { return /^\/(?:home)?$/.test(stripBase(location.pathname)); }
  function go(url, replace) {
    var clean = stripBase(url);
    var target = fullPath(clean);
    if (clean === currentURL() && curRoute) return;
    navFresh = true;
    if (replace) history.replaceState(null, '', target);
    else { hops++; history.pushState(null, '', target); }
    window.dispatchEvent(new CustomEvent('lv:nav'));
    LV.vt(function () { route(); });
  }
  function quietURL(url) {
    var clean = stripBase(url);
    var target = fullPath(clean);
    if (currentURL() === clean) return;
    history.replaceState(history.state, '', target);
    curRoute = clean;
  }
  document.addEventListener('click', function (e) {
    if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    var a = e.target.closest('a[href]');
    if (!a || a.target || a.hasAttribute('download')) return;
    var url;
    try { url = new URL(a.href, location.href); } catch (er) { return; }
    if (url.origin !== location.origin || !isAppPath(url.pathname)) return;
    e.preventDefault();
    go(url.pathname + url.search);
  });
  view.addEventListener('click', function (e) { if (e.target.closest('[data-back]')) { if (hops > 0) history.back(); else go('/'); } });
  window.addEventListener('popstate', function () {
    var next = currentURL();
    if (next === curRoute) return;
    hops++;
    LV.vt(function () { route(); });
  });
  window.addEventListener('hashchange', function () {
    if (location.hash.indexOf('#/') === 0) go(location.hash.slice(1), true);
  });
  var restoring = 0;
  function ready() {
    var y = pendingRestore; pendingRestore = 0; if (!y) return;
    var token = ++restoring, tries = 0, stop = false;
    function halt() { stop = true; }
    ['wheel', 'keydown', 'mousedown'].forEach(function (ev) { window.addEventListener(ev, halt, { once: true, passive: true }); });
    (function step() { if (stop || token !== restoring) return; window.scrollTo(0, y); if (Math.abs(window.scrollY - y) > 4 && tries++ < 40) setTimeout(step, 120); })();
  }
  function route() {
    if (location.hash.indexOf('#/') === 0) history.replaceState(null, '', fullPath(location.hash.slice(1)));
    if (curRoute) memo[curRoute] = window.scrollY;
    curRoute = currentURL();
    pendingRestore = navFresh ? 0 : (memo[curRoute] || 0); navFresh = false;
    cleanup(); closeMenu(); closeSearch(); hasHero = false;
    if (!pendingRestore) window.scrollTo(0, 0);
    var p = stripBase(location.pathname), m;
    if ((m = p.match(/^\/detail\/(\d+)$/))) { setTab(''); detail(m[1]); }
    else if (/^\/search$/.test(p)) {
      var sq = new URLSearchParams(location.search).get('q') || '';
      quietURL('/'); setTab('home'); home(); setTimeout(function () { openSearch(sq); }, 50);
    }
    else if (/^\/settings$/.test(p)) { setTab(''); settings(); }
    else if ((m = p.match(/^\/mine\/(history|fav|reserve)$/))) { setTab('mine'); mine(m[1]); }
    else {
      var n = (p.match(/^\/(\w+)/) || [])[1] || 'home';
      if (['home', 'feed', 'rank', 'category', 'mine'].indexOf(n) < 0) n = 'home';
      setTab(n); ({ home: home, feed: feedPage, rank: rank, category: category, mine: mine })[n]();
    }
    onScroll();
  }

  // ------------------------------------------------------------ 全局点击
  function openDetail(id, from) {
    $$('.poster,.pp,.th,.art').forEach(function (n) { if (n.style.viewTransitionName) n.style.viewTransitionName = ''; });
    var p = from && from.querySelector('.poster, .pp'); if (p) p.style.viewTransitionName = 'cover';
    go('/detail/' + id);
  }
  view.addEventListener('click', function (e) {
    var x = e.target.closest('[data-x]'); if (x) { e.stopPropagation(); return removeItem(x); }
    var a = e.target.closest('.arr'); if (a) { var row = $('.row', a.closest('.shelf')); return glide(row, +a.dataset.arr); }
    if (e.target.closest('[data-retry]')) return route();
    if (e.target.closest('[data-golog]')) return goLogin();
    if (e.target.closest('[data-login]')) return showLogin();
    var pl = e.target.closest('[data-play]'); if (pl) { e.stopPropagation(); return playSeries(pl.dataset.play); }
    var inf = e.target.closest('[data-info]'); if (inf) return openDetail(inf.dataset.info);
    var cw = e.target.closest('[data-cw]'); if (cw) return playSeries(cw.dataset.cw);
    var sn = e.target.closest('[data-soon]'); if (sn) return soonPopup(sn.dataset.soon);
    var el = e.target.closest('[data-id]'); if (el) openDetail(el.dataset.id, el);
  });
  // 悬停预取详情与播放源：点开时数据已在缓存里
  var warmT = 0;
  view.addEventListener('pointerover', function (e) {
    var c = e.target.closest('[data-id],[data-cw],[data-play]'); if (!c || c.contains(e.relatedTarget)) return;
    clearTimeout(warmT); var id = c.dataset.id || c.dataset.cw || c.dataset.play, isDirect = !!(c.dataset.cw || c.dataset.play);
    warmT = setTimeout(function () {
      if (PL || OPENING) return;
      var p = progressOf(id), lite = (isDirect && (historyLoaded || p)) ? '1' : undefined;
      api('/api/detail', { query: { id: id, lite: lite }, ttl: TTL }).catch(function () {});
      var vid = (p && p.vid) || (cache[id] && cache[id].firstVid) || '';
      if (vid) playInfo(vid, LV.get('quality')).catch(function () {});
    }, 120);
  });
  function saveCachedHist() {
    if (!BROWSE_ONLY && (LV.loggedIn || GUEST)) persist('hist', hist.slice(0, 24), 7 * 86400e3);
  }
  function mergeRecentHistory(cloud) {
    var by = new Map(); (cloud || []).forEach(function (e) { if (e && e.seriesId && (!by.has(e.seriesId) || (e.time || 0) >= (by.get(e.seriesId).time || 0))) by.set(e.seriesId, e); });
    recentPlayback.forEach(function (e, id) {
      var c = by.get(id);
      if (Date.now() - (e.time || 0) >= 120000 || (c && (c.time || 0) >= (e.time || 0))) recentPlayback.delete(id);
      else by.set(id, e);
    });
    return Array.from(by.values()).sort(function (a, b) { return (b.time || 0) - (a.time || 0); });
  }
  async function loadHistory(fresh) { if (!LV.loggedIn && !GUEST) return hist; var request = ++historyRequest; try { var rows = BROWSE_ONLY ? readBrowseHistory() : (await api(fresh === true ? '/api/history?fresh=1' : '/api/history')).items || []; if (request !== historyRequest) return hist; hist = mergeRecentHistory(rows); historyLoaded = true; saveCachedHist(); window.dispatchEvent(new CustomEvent('lv:history', { detail: hist })); } catch (e) {} return hist; }
  function progressOf(id) { return hist.find(function (h) { return h.seriesId === id; }); }
  function latestProgress(id, supplied) { var local = progressOf(id); return local && (!supplied || (local.time || 0) >= (supplied.time || 0)) ? local : supplied; }
  function playSeries(id) { openSeriesLoader(id); }

  // ------------------------------------------------------------ 主视觉
  function metaHtml(s) {
    var m = [];
    if (s.rating) m.push('<span class="star">★ ' + esc(s.rating) + '</span>');
    if (s.tag) m.push('<span class="pill">' + esc(s.tag) + '</span>');
    var n = s.episodes || s.count;
    return m.concat([n ? '全 ' + n + ' 集' : '', s.plays ? playsShort(s.plays) + ' 次播放' : ''].concat(cats(s, 3)).filter(Boolean).map(esc)).join('<span class="dot"></span>');
  }
  function slide(s, on, kick, eager, acts) {
    return '<div class="hs' + (on ? ' on' : '') + '"><div class="bg" style="background-image:url(\'' + esc(LV.sized(s.cover, 24)) + '\')"></div><div class="art" data-info="' + s.id + '">' + LV.img(s.cover, s.title, 900, eager) + '</div><div class="scrim"></div>' +
      '<div class="txt"><div class="kick"><i></i>' + kick + '</div><div class="ttl">' + esc(s.title) + '</div><div class="meta">' + metaHtml(s) + '</div>' +
      '<p class="intro">' + esc(s.intro || '') + '</p><div class="acts">' + (acts || '<button class="bplay" data-play="' + s.id + '">' + ic('play2') + '播放</button><button class="bglass" data-info="' + s.id + '">详情' + ic('chev') + '</button>') + '</div></div></div>';
  }
  function heroSkTxt() { return '<div class="hs on"><div class="txt"><div class="skel" style="position:relative;width:96px;height:14px"></div><div class="skel" style="position:relative;width:78%;height:54px;margin-top:22px"></div><div class="skel" style="position:relative;width:52%;height:54px;margin-top:12px"></div><div class="skel" style="position:relative;width:60%;height:16px;margin-top:26px"></div><div class="skel" style="position:relative;width:200px;height:52px;margin-top:30px;border-radius:99px"></div></div></div>'; }

  // ------------------------------------------------------------ 首页
  var HOME_BOARD = 'ranklist_hot_play_sc';
  var HERO_BOARD = 'ranklist_hot_sc';
  // 本地持久缓存：再次打开时先用上次的内容立即渲染，新数据后台拉取（不打断已显示的内容）
  function persist(k, v, maxAge) {
    try { if (v !== undefined) localStorage.setItem('lvguo.c.' + k, JSON.stringify({ t: Date.now(), v: v })); else { var o = JSON.parse(localStorage.getItem('lvguo.c.' + k) || 'null'); return o && Date.now() - o.t < (maxAge || 3 * 86400e3) ? o.v : null; } } catch (e) { return null; }
  }
  function slim(s) { return { id: s.id, title: s.title, cover: s.cover, intro: s.intro, episodes: s.episodes, plays: s.plays, category: s.category, firstVid: s.firstVid }; }
  function renderHero(hero, picks) {
    hero.classList.add('wait');
    hero.innerHTML = picks.map(function (s, i) { return slide(s, !i, '推荐榜', true); }).join('') + '<div class="hnav">' + picks.map(function (_, i) { return '<button class="' + (i ? '' : 'on') + '" data-b="' + i + '" aria-label="第 ' + (i + 1) + ' 张"><i></i></button>'; }).join('') + '</div>';
    // 等首张封面（缩略图或大图）到了再淡入，避免先出空底 + 文字
    var done = false, reveal = function () { if (done) return; done = true; requestAnimationFrame(function () { hero.classList.remove('wait'); }); };
    [LV.sized(picks[0].cover, 24), LV.sized(picks[0].cover, 900)].forEach(function (u) { var im = new Image(); im.referrerPolicy = 'no-referrer'; im.onload = reveal; im.onerror = reveal; im.src = u; });
    setTimeout(reveal, 1200);
    picks.slice(1).forEach(function (s) { var im = new Image(); im.referrerPolicy = 'no-referrer'; im.src = LV.sized(s.cover, 900); });
  }
  function heroCarousel(hero, picks) {
    var n = picks.length;
    var cur = 0, timer = 0, hold = false, seen = true, prevTimer = 0;
    function sl() { return $$('.hs', hero); }
    function arm() { clearTimeout(timer); timer = setTimeout(function () { if (!hold && seen && !PL && document.visibilityState === 'visible') show(cur + 1); else arm(); }, 8000); }
    function show(i) {
      var nx = (i + n) % n; if (nx === cur && i !== cur) return arm();
      var old = cur, dir = i < cur ? -1 : 1; hero.style.setProperty('--dir', dir);
      cur = nx;
      clearTimeout(prevTimer);
      hero.classList.add('sliding');
      sl().forEach(function (s, k) {
        s.classList.remove('prev', 'on');
        if (k === old && old !== cur) { void s.offsetWidth; s.classList.add('prev'); }
        else if (k === cur) { void s.offsetWidth; s.classList.add('on'); }
      });
      prevTimer = setTimeout(function () {
        sl().forEach(function (s) { s.classList.remove('prev'); });
        hero.classList.remove('sliding');
      }, 820);
      $$('.hnav button', hero).forEach(function (b, k) { b.classList.remove('on'); if (k === cur) { void b.offsetWidth; b.classList.add('on'); } });
      arm();
    }
    arm();
    hero.addEventListener('click', function (e) { var d = e.target.closest('[data-b]'); if (d && +d.dataset.b !== cur) show(+d.dataset.b); });
    hero.addEventListener('mouseover', function (e) { if (e.target.closest('.txt,.hnav')) { hold = true; hero.classList.add('hold'); } });
    hero.addEventListener('mouseout', function (e) { if (e.target.closest('.txt,.hnav') && !(e.relatedTarget && e.relatedTarget.closest && e.relatedTarget.closest('.txt,.hnav'))) { hold = false; hero.classList.remove('hold'); arm(); } });
    var hio = new IntersectionObserver(function (es) { seen = es[0].isIntersecting; }, { threshold: 0.35 }); hio.observe(hero);
    function keys(e) { if (/INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName) || PL || SP || window.scrollY > 200) return; if (e.key === 'ArrowLeft') show(cur - 1); else if (e.key === 'ArrowRight') show(cur + 1); }
    document.addEventListener('keydown', keys);
    cleanups.push(function () { clearTimeout(timer); clearTimeout(prevTimer); hio.disconnect(); document.removeEventListener('keydown', keys); });
  }
  async function home() {
    title(''); hasHero = false;
    var cached = persist('home'), cachedHero = persist('hero.' + HERO_BOARD, undefined, 7 * 86400e3);
    var root = mount('<section class="hero">' + heroSkTxt() + '</section><div class="shelves"><div id="cont"></div>' +
      shelf('热播榜', '/rank?board=' + HOME_BOARD, '', cardSk(Math.max(10, gridCols() * 2)), ' id="hot"') +
      '<section class="shelf"><div class="sh"><h2>为你推荐</h2><div class="fych" id="fych"></div><button class="fy-swap" id="fy-swap" type="button">换一批 ' + ic('refresh') + '</button></div><div class="grid" id="fy">' + gridSk(2) + '</div><div class="sentinel" id="sent"></div></section></div>');
    rowsReady(root);
    var hero = $('.hero', root), fy = $('#fy', root), shown = null, seenReco = {};
    function paintHero(items) {
      var picks = (items || []).slice(0, 5);
      if (!picks.length || !root.isConnected || !hero.isConnected) return;
      renderHero(hero, picks); heroCarousel(hero, picks); hasHero = true; onScroll();
    }
    function paint(items) {
      shown = items; remember(items);
      items.forEach(function (s) { if (s && s.id) seenReco[s.id] = 1; });
      fy.innerHTML = items.map(function (s, i) { return card(s, { i: i }); }).join('');
    }
    function updateContinue(list) {
      var el = $('#cont', root); if (!el || !root.isConnected) return;
      el.innerHTML = list.length ? shelf('继续观看', '/mine', '', list.slice(0, 12).map(function (c, i) { return cwMini(c, i); }).join('')) : '';
      if (list.length) rowsReady(root);
    }
    function historyChanged(e) { if (root.isConnected) updateContinue(e.detail && e.detail.seriesId ? mergeRecentHistory([e.detail].concat(hist)) : (e.detail || hist)); }
    window.addEventListener('lv:history', historyChanged); window.addEventListener('lv:progress', historyChanged);
    cleanups.push(function () { window.removeEventListener('lv:history', historyChanged); window.removeEventListener('lv:progress', historyChanged); });
    if (hist.length) updateContinue(hist);
    if (BROWSE_ONLY) loadHistory(false);
    if (cached && cached.length >= 6) paint(cached);
    if (cachedHero && cachedHero.length) paintHero(cachedHero);
    else boardItems(HERO_BOARD, 5).then(function (list) {
      if (!root.isConnected || !isHomePath()) return;
      if (!list.length) { hero.remove(); return; }
      var picks = list.slice(0, 5).map(slim); persist('hero.' + HERO_BOARD, picks); paintHero(picks);
    }).catch(function () { if (!cachedHero && hero.isConnected) { hero.remove(); hasHero = false; onScroll(); } });
    // 预热首部最可能被点击的剧集（继续观看第 1 部或主视觉第 1 部），点击播放时详情与视频流已在缓存中
    var warmFirstTimer = setTimeout(function () {
      if (PL || OPENING || !root.isConnected || !isHomePath()) return;
      var top = hist[0];
      if (top && top.seriesId) {
        api('/api/detail', { query: { id: top.seriesId, lite: '1' }, ttl: TTL }).catch(function () {});
        if (top.vid) playInfo(top.vid, LV.get('quality')).catch(function () {});
      } else if (cachedHero && cachedHero[0] && cachedHero[0].id) {
        api('/api/detail', { query: { id: cachedHero[0].id, lite: '1' }, ttl: TTL }).catch(function () {});
        if (cachedHero[0].firstVid) playInfo(cachedHero[0].firstVid, LV.get('quality')).catch(function () {});
      }
    }, 180);
    cleanups.push(function () { clearTimeout(warmFirstTimer); });
    // 热播榜（一排）：先用本地缓存，与 /api/home 并行后台刷新，不阻塞在 /api/home 之后
    var hot = $('#hot', root), hrow = $('.row', hot), hk = 'b.' + HOME_BOARD, hhit = persist(hk), hdrawn = false;
    function hdraw(list) { hrow.innerHTML = list.map(function (s, i) { return card(s, { rank: i + 1, i: i }); }).join(''); arrState(hrow); hdrawn = true; }
    if (hhit && hhit.length >= 6) { remember(hhit); hdraw(hhit); }
    boardItems(HOME_BOARD, 12).then(function (list) {
      if (!root.isConnected || !isHomePath()) return;
      if (list.length >= 6) persist(hk, list.map(slim));
      if (!hdrawn) { if (list.length < 6) hot.remove(); else hdraw(list); }
    }).catch(function () { if (!hdrawn && hot.isConnected) hot.remove(); });
    var data; try { data = await api('/api/home', { ttl: 0 }); } catch (e) {
      if (!shown && !cachedHero) { hero.remove(); hasHero = false; $('.shelves', root).innerHTML = '<div style="padding-top:var(--nav)">' + failed(e.message) + '</div>'; onScroll(); return; }
      data = { forYou: { items: shown, next: -1, more: false }, continue: [], channels: [], cell: '', session: '' };
    }
    if (!isHomePath() || !root.isConnected) return;
    var items = data.forYou.items || []; remember(items);
    if (items.length) persist('home', items.map(slim));
    // 首页「为你推荐」可随会话更新；主视觉独立使用推荐榜持久缓存。
    if (items.length && (!shown || shown.map(function (x) { return x.id; }).join() !== items.map(function (x) { return x.id; }).join())) paint(items);
    remember(data.continue.map(function (c) { return { id: c.seriesId, title: c.title, cover: c.cover, episodes: c.episodeCount }; }));
    if (!BROWSE_ONLY && (LV.loggedIn || GUEST)) {
      hist = historyLoaded ? mergeRecentHistory((data.continue || []).concat(hist)) : mergeRecentHistory(data.continue || []);
      saveCachedHist(); updateContinue(hist);
    }
    // 为你推荐：接口每页只有 6 部、约 1 秒 → 后台预取攒到缓冲区，每次按整行追加（至少两行），不再一张张往外冒
    function cols() { var t = getComputedStyle(fy).gridTemplateColumns; return t && t !== 'none' ? t.split(' ').length : 6; }
    var inf = null, feedGen = 0, sent = $('#sent', root);
    // 一个频道的推荐流：first 为已经画出来的第一页（可选 buf 放多余尾项，保证首屏凑整行）
    function startFeed(ch, first) {
      var gen = ++feedGen, seenId = {}, next = first.next, cell = first.cell, sess = first.session, buf = (first.buf || []).slice(), more = true, pending = null;
      first.items.forEach(function (s) { seenId[s.id] = 1; seenReco[s.id] = 1; });
      buf.forEach(function (s) { seenId[s.id] = 1; seenReco[s.id] = 1; });
      // 推荐频道翻几页后只会回已出现过的剧：连续 2 页没有新剧就改从各榜单接着取（去重）；漫剧等频道不混入榜单
      var dry = 0, src = first.more ? 'fy' : 'board', bq = null, bi = 0, boff = 0, bsess = '';
      if (src === 'board' && ch !== '16') more = false;
      function fetchPage() {
        if (pending || !more) return pending || Promise.resolve();
        var before = buf.length, req;
        if (src === 'fy') req = api('/api/foryou', { query: { channel: ch, offset: next, cell: cell, session: sess } }).then(function (r) {
          remember(r.page.items);
          r.page.items.forEach(function (s) { if (!seenId[s.id] && !seenReco[s.id]) { seenId[s.id] = 1; seenReco[s.id] = 1; buf.push(s); } });
          next = r.page.next; cell = r.cell; sess = r.session;
          if (!(r.page.more && r.page.items.length)) dry = 9;
        });
        else req = (bq ? Promise.resolve(bq) : boards().then(function (bl) { bq = bl.map(function (b) { return b.id; }); return bq; })).then(function (ids) {
          if (bi >= ids.length) { more = false; return; }
          return api('/api/rank', { query: { board: ids[bi], offset: boff, session: bsess } }).then(function (r) {
            remember(r.items);
            r.items.forEach(function (s) { if (!seenId[s.id] && !seenReco[s.id]) { seenId[s.id] = 1; seenReco[s.id] = 1; buf.push(s); } });
            if (r.more && r.items.length) { boff = r.next; bsess = r.session; } else { bi++; boff = 0; bsess = ''; }
          });
        });
        pending = req.then(function () {
          dry = buf.length > before ? 0 : dry + 1;
          if (src === 'fy' && dry >= 2) { if (ch === '16') { src = 'board'; dry = 0; } else more = false; }
          else if (src === 'board' && dry >= 3) { bi++; boff = 0; bsess = ''; dry = 0; }
        }).catch(function () { if (src === 'fy' && ch === '16') { src = 'board'; dry = 0; } else more = false; }).then(function () { pending = null; });
        return pending;
      }
      function live() { return gen === feedGen && root.isConnected; }
      function prefetch() { var c = cols(); (function go() { if (PL || OPENING) return; if (buf.length < c * 3 && more && live()) fetchPage().then(go); })(); }
      if (inf) inf.stop();
      sent.hidden = false;
      inf = LV.infinite(sent, async function () {
        var c = cols();
        while (buf.length < c * 2 && more && live()) await fetchPage();
        if (!live()) return false;
        var have = fy.querySelectorAll(':scope > .card:not(.sk)').length;
        var take = more ? Math.max(0, Math.floor((have + buf.length) / c) * c - have) : buf.length;
        var h = buf.splice(0, take).map(function (s, k) { return card(s, { i: k }); }).join('');
        fy.insertAdjacentHTML('beforeend', h);
        prefetch();
        return more || buf.length > 0;
      }, { host: fy, skel: cardSk });
      prefetch(); // 首屏一画完就开始预取
    }
    var initialForYou = (shown || []).concat(items);
    startFeed('16', { items: initialForYou, next: data.forYou.next, more: data.forYou.more, cell: data.cell, session: data.session });
    // 频道切换（推荐 / 漫剧等）：预热次要频道首屏 + 本地缓存 + 凑整行展示，切频道即时出图不闪两次加载
    var chs = data.channels || [], chBox = $('#fych', root), curCh = '16';
    var chCache = { '16': { page: { items: initialForYou, next: data.forYou.next, more: data.forYou.more }, cell: data.cell, session: data.session } };
    var chPending = {};
    function fetchChannelFirst(chId) {
      if (chCache[chId]) return Promise.resolve(chCache[chId]);
      if (chPending[chId]) return chPending[chId];
      chPending[chId] = api('/api/foryou', { query: { channel: chId, offset: 0 } }).then(function (r) {
        if (r && r.page && r.page.items && r.page.items.length) chCache[chId] = r;
        delete chPending[chId];
        return r;
      }, function (er) { delete chPending[chId]; throw er; });
      return chPending[chId];
    }
    function applyChannelResult(chId, r) {
      var raw = (r && r.page && r.page.items) || [];
      remember(raw);
      var c = cols(), moreFlag = !!(r && r.page && r.page.more);
      var drawN = (moreFlag && raw.length >= c * 2) ? Math.floor(raw.length / c) * c : raw.length;
      var vis = raw.slice(0, drawN), rest = raw.slice(drawN);
      fy.innerHTML = vis.map(function (s, i) { return card(s, { i: i }); }).join('') || '<div class="mempty">暂无内容</div>';
      feedGen--;   // startFeed 自己会 +1，保持与当前切换同一代
      startFeed(chId, { items: vis, buf: rest, next: r.page.next, more: r.page.more, cell: r.cell, session: r.session });
    }
    if (chs.length > 1) {
      chBox.innerHTML = chs.map(function (c) { return '<button class="chip' + (c.id === curCh ? ' on' : '') + '" data-ch="' + esc(c.id) + '">' + esc(c.label) + '</button>'; }).join('');
      var warmTimer = setTimeout(function () {
        if (!root.isConnected || !isHomePath() || PL || OPENING) return;
        chs.forEach(function (c) { if (c.id !== '16') fetchChannelFirst(c.id).catch(function () {}); });
      }, 1200);
      cleanups.push(function () { clearTimeout(warmTimer); });
      chBox.addEventListener('click', function (e) {
        var b = e.target.closest('[data-ch]'); if (!b) return;
        if (b.dataset.ch === curCh) { swapBatch(); return; }
        curCh = b.dataset.ch; $$('.chip', chBox).forEach(function (x) { x.classList.toggle('on', x === b); });
        var gen = ++feedGen; if (inf) inf.stop();
        if (chCache[curCh]) { applyChannelResult(curCh, chCache[curCh]); return; }
        fy.innerHTML = gridSk(2);
        fetchChannelFirst(curCh).then(function (r) {
          if (gen !== feedGen || !root.isConnected) return;
          applyChannelResult(curCh, r);
        }, function (er) { if (gen === feedGen) { fy.innerHTML = '<div class="mempty">' + esc(er.message) + '</div>'; } });
      });
    }
    var swapButton = $('#fy-swap', root), swapping = false;
    async function swapBatch() {
      if (swapping || !root.isConnected) return;
      swapping = true; swapButton.disabled = true; swapButton.classList.add('busy');
      try {
        // Start a new upstream feed session and keep paging until it yields a fresh batch.
        var first = await api('/api/foryou', { query: { channel: curCh, offset: 0 } });
        var rows = [], local = {}, page = first.page, cell = first.cell, session = first.session;
        function addFresh(items) {
          (items || []).forEach(function (s) {
            if (s && s.id && !seenReco[s.id] && !local[s.id]) { local[s.id] = 1; rows.push(s); }
          });
        }
        addFresh(page.items);
        var tries = 0;
        while (rows.length < Math.max(12, cols() * 2) && page.more && tries++ < 8) {
          var nextPage = await api('/api/foryou', { query: { channel: curCh, offset: page.next, cell: cell, session: session } });
          page = nextPage.page; cell = nextPage.cell; session = nextPage.session;
          addFresh(page.items);
        }
        if (!root.isConnected) return;
        if (!rows.length) { LV.toast('暂时没有更多新推荐'); return; }
        // Invalidate an in-flight old infinite-scroll request before replacing its grid.
        feedGen++; if (inf) inf.stop();
        remember(rows); rows.forEach(function (s) { seenReco[s.id] = 1; });
        fy.innerHTML = rows.map(function (s, i) { return card(s, { i: i }); }).join('');
        persist('home', rows.map(slim));
        startFeed(curCh, { items: rows, next: page.next, more: page.more, cell: cell, session: session });
      } catch (e) { LV.toast(e.message || '换一批失败'); }
      finally { swapping = false; swapButton.disabled = false; swapButton.classList.remove('busy'); }
    }
    swapButton.addEventListener('click', swapBatch);
    cleanups.push(function () { feedGen++; if (inf) inf.stop(); }); rowsReady(root); ready(); onScroll();
  }

  // ------------------------------------------------------------ 榜单
  async function rank() {
    title('榜单');
    var root = mount(head('榜单') + '<div class="filters" id="rty"></div><div class="bar2 ranktabs"><div class="seg sk">' + '<div class="skel" style="width:64px;height:22px;border-radius:8px;flex:none;margin:9px 14px"></div>'.repeat(8) + '</div></div><div id="rc"><div class="grid">' + gridSk(3) + '</div></div>');
    var bl, types = [], type = 'all';
    try { var o0 = await api('/api/category/options', { query: { type: 'all' }, ttl: 600000 }); bl = o0.boards; types = o0.types || []; } catch (e) { return $('#rc', root).innerHTML = failed(e.message); }
    var cur = new URLSearchParams(location.search).get('board') || bl[0].id, inf = null;
    if (!bl.some(function (b) { return b.id === cur; })) cur = bl[0].id;
    function drawBoards() { $('.bar2', root).innerHTML = seg(bl.map(function (b) { return [b.id, b.label]; }), cur, 'data-b'); placeSeg(root); }
    drawBoards();
    // 类型（全部 / 漫剧 / 真人剧 / AI剧）：与分类页同一套 .frow 样式；切换后榜单标签与内容一起联动
    function drawTypes() {
      $('#rty', root).innerHTML = types.length > 1 ? '<div class="frow"><div class="fchips">' +
        types.map(function (t) { return '<button class="chip' + (type === t.id ? ' on' : '') + '" data-t="' + esc(t.id) + '">' + esc(t.label) + '</button>'; }).join('') + '</div></div>' : '';
    }
    drawTypes();
    $('#rty', root).addEventListener('click', async function (e) {
      var b = e.target.closest('[data-t]'); if (!b || b.dataset.t === type) return;
      var want = b.dataset.t; type = want; drawTypes();
      try { var o = await api('/api/category/options', { query: { type: want }, ttl: 600000 }); if (type !== want) return; var ob = (o.boards || []).filter(function (x) { return want === 'all' || !/_hot_(sc|rank)$/.test(x.id); }); if (ob.length) bl = ob; } catch (er) { LV.toast(er.message); }
      if (!bl.some(function (x) { return x.id === cur; })) cur = bl[0].id;
      drawBoards(); load(cur);
    });
    function load(id) {
      cur = id; if (inf) inf.stop();
      $$('.bar2 button', root).forEach(function (b) { b.classList.toggle('on', b.dataset.b === id); }); placeSeg(root);
      title((bl.find(function (b) { return b.id === id; }) || {}).label || '榜单');
      var rc = $('#rc', root); rc.innerHTML = '<div class="grid" id="rg">' + gridSk(3) + '</div><div class="sentinel" id="sent"></div>';
      var g = $('#rg', rc), off = 0, sess = '', seen = {}, no = 0, first = true;
      inf = LV.infinite($('#sent', rc), async function () {
        var ty = type;
        var r = ty === 'all' ? await api('/api/rank', { query: { board: id, offset: off, session: sess }, ttl: off ? 0 : TTL })
          : await api('/api/category', { query: { type: ty, board: id, offset: off, session: sess }, ttl: off ? 0 : TTL });
        if (cur !== id || type !== ty) return false;
        remember(r.items); var h = '', k = 0;
        r.items.forEach(function (s) { if (seen[s.id]) return; seen[s.id] = 1; no++; h += card(s, { rank: no, i: k++ }); });
        if (first) { first = false; g.innerHTML = ''; if (!h) { rc.innerHTML = empty('chart', '这个榜单暂时没有内容'); return false; } ready(); }
        g.insertAdjacentHTML('beforeend', h);
        off = r.next; sess = r.session; return !!(r.more && r.items.length);
      }, { host: g, skel: cardSk });
      cleanups.push(inf.stop);
    }
    $('.bar2', root).addEventListener('click', function (e) {
      var b = e.target.closest('[data-b]'); if (!b || b.dataset.b === cur) return;
      quietURL('/rank?board=' + b.dataset.b);
      var top = $('.bar2', root).offsetTop - nav.offsetHeight - 12; if (window.scrollY > top) window.scrollTo({ top: top, behavior: 'smooth' });
      load(b.dataset.b);
    });
    load(cur);
  }

  // ------------------------------------------------------------ 分类
  async function category() {
    title('分类');
    var st = { type: 'all', board: '', sel: {} };
    var root = mount(head('分类') + '<div class="filters" id="fl">' + [180, 520, 760, 760].map(function (w) { return '<div class="frow"><div class="skel t" style="width:36px;flex:none;margin-right:28px"></div><div class="skel" style="width:' + w + 'px;height:36px;border-radius:99px"></div></div>'; }).join('') + '</div>' +
      '<div class="grid" id="g" style="margin-top:34px">' + gridSk(3) + '</div><div class="sentinel" id="sent"></div>');
    var inf, opts;
    async function loadOpts() {
      var keep = {}; $$('.fchips', root).forEach(function (f, i) { keep[i] = f.scrollLeft; });
      opts = await api('/api/category/options', { query: { type: st.type }, ttl: 600000 });
      if (!st.board || !opts.boards.some(function (b) { return b.id === st.board; })) st.board = opts.boards[0] ? opts.boards[0].id : '';
      var cur = opts.boards.find(function (b) { return b.id === st.board; });
      var NAME = { '综合': '受众', '时代背景': '背景', '主题情节': '题材', '角色设定': '人设' };
      var html = '';
      // 分类页不放类型（漫剧 / 真人剧 / AI剧）：类型只在榜单页区分
      html += (cur ? cur.groups.map(function (g) {
          return '<div class="frow"><h4>' + esc(NAME[g.name] || g.name) + '</h4><div class="fchips"><button class="chip' + (!st.sel[g.name] ? ' on' : '') + '" data-g="' + esc(g.name) + '" data-o="">全部</button>' +
            g.items.map(function (o) { return '<button class="chip' + (st.sel[g.name] === o.id ? ' on' : '') + '" data-g="' + esc(g.name) + '" data-o="' + o.id + '">' + esc(o.label) + '</button>'; }).join('') + '</div></div>';
        }).join('') : '');
      $('#fl', root).innerHTML = html;
      $$('.fchips', root).forEach(function (f, i) { if (keep[i]) f.scrollLeft = keep[i]; });
      placeSeg(root);
    }
    function loadGrid() {
      if (inf) inf.stop(); var g = $('#g', root);
      if (!g.classList.contains('grid')) { g.outerHTML = '<div class="grid" id="g" style="margin-top:34px"></div>'; g = $('#g', root); }
      g.innerHTML = gridSk(2); $('#sent', root).hidden = false;
      var off = 0, sess = '', seen = {}, first = true, tok = JSON.stringify(st);
      var sel = Object.keys(st.sel).filter(function (k) { return st.sel[k]; }).map(function (k) { return k + ':' + st.sel[k]; });
      inf = LV.infinite($('#sent', root), async function () {
        var r = await api('/api/category', { query: { type: st.type, board: st.board, offset: off, session: sess, sel: sel } }); if (tok !== JSON.stringify(st)) return false;
        remember(r.items); var h = '', k = 0; r.items.forEach(function (s) { if (!seen[s.id]) { seen[s.id] = 1; h += card(s, { i: k++ }); } });
        if (first) { g.innerHTML = ''; first = false; if (!h && !r.more) { g.outerHTML = '<div id="g">' + empty('grid', '没有符合条件的剧', '换个条件试试') + '</div>'; return false; } ready(); }
        g.insertAdjacentHTML('beforeend', h); off = r.next; sess = r.session; return !!r.more;
      }, { host: g, skel: cardSk });
      cleanups.push(inf.stop);
    }
    async function apply() { try { await loadOpts(); loadGrid(); } catch (er) { LV.toast(er.message); } }
    root.addEventListener('click', function (e) {
      var t = e.target.closest('[data-t]'), b = e.target.closest('[data-bd]'), c = e.target.closest('[data-g]');
      if (t && t.dataset.t !== st.type) { st.type = t.dataset.t; st.board = ''; st.sel = {}; apply(); }
      else if (b && b.dataset.bd !== st.board) { st.board = b.dataset.bd; st.sel = {}; apply(); }
      else if (c && (st.sel[c.dataset.g] || '') !== c.dataset.o) { st.sel[c.dataset.g] = c.dataset.o; apply(); }
    });
    try { await loadOpts(); loadGrid(); } catch (e) { $('#g', root).outerHTML = '<div id="g">' + failed(e.message) + '</div>'; }
  }

  // ------------------------------------------------------------ 搜索：右上角弹出卡片（同登录），边输入边出结果
  var recent = [];
  try { recent = JSON.parse(localStorage.getItem('lvguo.recent') || '[]'); } catch (e) {}
  function saveRecent(t) { recent = [t].concat(recent.filter(function (x) { return x !== t; })).slice(0, 12); try { localStorage.setItem('lvguo.recent', JSON.stringify(recent)); } catch (e) {} }
  var SP = null, HOT = null, HOT_T = 0;
  (function () { var h = persist('hot'); if (h && h.v) { HOT = h.v; HOT_T = h.t; remember(HOT); } })();
  function closeSearch(now) {
    if (!SP) return; var w = SP.w; SP.tok++; if (SP.cleanup) SP.cleanup(); SP = null; $('#toSearch').classList.remove('on');
    if (now === true) return w.remove();
    w.classList.add('out'); setTimeout(function () { w.remove(); }, 220);
  }
  function srow(s, i, q) {
    var meta = [s.episodes ? '全 ' + s.episodes + ' 集' : '', LV.plays(s.plays)].concat(cats(s, 2)).filter(Boolean).join(' · ');
    return '<button class="sitem" data-sid="' + s.id + '"><div class="poster">' + LV.img(s.cover, s.title, 160, true) + '</div><div class="ri"><b>' + hl(s.title, q) + '</b><span>' + esc(meta) + '</span></div>' + (i != null ? '<span class="sn">' + (i + 1) + '</span>' : '') + '</button>';
  }
  function srSk(n) { return Array.from({ length: n }, function () { return '<div class="sitem sk"><div class="poster skel"></div><div class="ri"><div class="skel t m"></div><div class="skel t s"></div></div></div>'; }).join(''); }
  function openSearch(q0) {
    if (PL) return; if (SP) { SP.inp.focus(); SP.inp.select(); return; }
    closeMenu(); if (closeLoginFn) closeLoginFn();
    var w = document.createElement('div'); w.className = 'spw';
    w.innerHTML = '<div class="spop" role="dialog" aria-label="搜索"><form class="sform" autocomplete="off">' + ic('search') + '<input type="text" placeholder="搜索短剧、演员" spellcheck="false"><button type="button" class="clr" aria-label="清除">' + ic('close') + '</button></form><div class="sbody"></div></div>';
    document.body.appendChild(w); $('#toSearch').classList.add('on');
    var inp = $('input', w), body = $('.sbody', w), form = $('.sform', w);
    SP = { w: w, inp: inp, tok: 0 }; var me2 = SP, page = 1, lastQ = null;
    inp.value = q0 || ''; setTimeout(function () { inp.focus(); }, 30);
    function fitHot() {
      if (SP !== me2 || inp.value.trim()) return;
      var hot = $('#hot', body), pop = $('.spop', w); if (!hot || !pop) return;
      var items = Array.from(hot.children); items.forEach(function (item) { item.hidden = false; });
      var bottom = pop.getBoundingClientRect().top + parseFloat(getComputedStyle(pop).maxHeight) - 14;
      items.forEach(function (item) { if (item.getBoundingClientRect().bottom > bottom) item.hidden = true; });
    }
    window.addEventListener('resize', fitHot); me2.cleanup = function () { window.removeEventListener('resize', fitHot); };
    function idle() {
      body.scrollTop = 0;
      body.innerHTML = (recent.length ? '<div class="sh3">最近搜索<button type="button" data-clear>清空</button></div><div class="rchips">' + recent.slice(0, 8).map(function (t) { return '<button type="button" class="chip" data-q="' + esc(t) + '">' + esc(t) + '</button>'; }).join('') + '</div>' : '') +
        '<div class="sh3">大家都在搜</div><div class="slist2" id="hot">' + (HOT ? HOT.map(function (s, i) { return srow(s, i, ''); }).join('') : srSk(6)) + '</div>';
      requestAnimationFrame(fitHot);
      if (HOT && Date.now() - HOT_T < 600000) return;
      boards().then(function (bl) { return boardItems((bl.find(function (b) { return /热搜/.test(b.label); }) || bl[0]).id, 8); }).then(function (list) {
        if (!list.length) return; var had = !!HOT; HOT = list.map(slim); HOT_T = Date.now(); persist('hot', { t: HOT_T, v: HOT });
        var el = $('#hot', body); if (!had && el && SP === me2 && !inp.value.trim()) { el.innerHTML = HOT.map(function (s, i) { return srow(s, i, ''); }).join(''); requestAnimationFrame(fitHot); }
      }).catch(function () {});
    }
    function run(q, commit) {
      q = q.trim(); form.classList.toggle('has', !!q);
      if (commit && q) saveRecent(q);
      if (q === lastQ) return; lastQ = q; var t = ++me2.tok;
      if (!q) return idle();
      body.scrollTop = 0;
      body.innerHTML = '<div class="sugs" id="sugs"></div><div class="slist2" id="res">' + srSk(5) + '</div>';
      api('/api/suggest', { query: { q: q }, ttl: 300000 }).then(function (r) { if (t !== me2.tok) return; var el = $('#sugs', body); if (el) el.innerHTML = (r.items || []).filter(function (x) { return x !== q; }).slice(0, 6).map(function (x) { return '<button type="button" class="chip" data-q="' + esc(x) + '">' + hl(x, q) + '</button>'; }).join(''); }).catch(function () {});
      page = 1; more(q, t);
    }
    function more(q, t) {
      api('/api/search', { query: { q: q, page: page }, ttl: TTL }).then(function (r) {
        if (t !== me2.tok) return; remember(r.items); var el = $('#res', body); if (!el) return;
        if (page === 1) el.innerHTML = r.items.length ? '' : '<div class="snone">没有找到“' + esc(q) + '”相关的剧</div>';
        var m = $('.smore', el); if (m) m.remove();
        el.insertAdjacentHTML('beforeend', r.items.map(function (s) { return srow(s, null, q); }).join('') + (r.more ? '<button type="button" class="smore" data-more>加载更多</button>' : ''));
        page++;
      }).catch(function (e) { var el = $('#res', body); if (el && t === me2.tok) el.innerHTML = '<div class="snone">' + esc(e.message) + '</div>'; });
    }
    var live = LV.debounce(function () { run(inp.value, false); }, 280);
    // 拼音输入法组字过程中不搜（会拿半截拼音去搜），上屏后再搜
    inp.addEventListener('input', function (e) { form.classList.toggle('has', !!inp.value.trim()); if (!e.isComposing) live(); });
    inp.addEventListener('compositionend', function () { live(); });
    form.addEventListener('submit', function (e) { e.preventDefault(); run(inp.value, true); });
    inp.addEventListener('keydown', function (e) { if (e.key === 'Escape') { e.preventDefault(); if (inp.value) { inp.value = ''; run(''); } else closeSearch(); } });
    $('.clr', w).onclick = function () { inp.value = ''; run(''); inp.focus(); };
    w.addEventListener('mousedown', function (e) { if (e.target === w) closeSearch(); });
    body.addEventListener('click', function (e) {
      var s = e.target.closest('[data-sid]'), c = e.target.closest('[data-q]'), cl = e.target.closest('[data-clear]'), m = e.target.closest('[data-more]');
      if (s) { if (inp.value.trim()) saveRecent(inp.value.trim()); closeSearch(true); openDetail(s.dataset.sid); }
      else if (c) { inp.value = c.dataset.q; run(c.dataset.q, true); }
      else if (cl) { recent = []; try { localStorage.removeItem('lvguo.recent'); } catch (x) {} lastQ = null; run(''); }
      else if (m) { m.disabled = true; m.textContent = '加载中…'; more(lastQ, me2.tok); }
    });
    run(inp.value, !!inp.value);
  }
  $('#toSearch').onclick = function (e) { e.stopPropagation(); if (SP) closeSearch(); else openSearch(); };

  // ------------------------------------------------------------ 详情：海报 + 信息 + 选集（简介只出现一次）
  function epGroups(n) { return n > 110 ? LV.group(n, 100) : [[1, n]]; }
  function epNum(e, i, cur, seen, playing) {
    return '<button class="epn' + (cur ? ' cur' : '') + (seen ? ' seen' : '') + '" data-ep="' + i + '" title="第 ' + e.index + ' 集' + (e.duration ? ' · ' + LV.time(e.duration) : '') + '">' +
      (cur && playing ? '<i class="eq"><b></b><b></b><b></b></i>' : '') + '<span>' + e.index + '</span></button>';
  }
  function epSk(n) { return Array.from({ length: n }, function () { return '<div class="epn sk skel"></div>'; }).join(''); }
  function epTabs(G, gi, attr) { return G.length > 1 ? '<div class="eptabs">' + G.map(function (g, i) { return '<button class="' + (i === gi ? 'on' : '') + '" ' + attr + '="' + i + '">' + g[0] + '–' + g[1] + '</button>'; }).join('') + '</div>' : ''; }
  async function detail(id) {
    var s = cache[id] || {}, p0 = progressOf(id), warmVid0 = (p0 && p0.vid) || s.firstVid || ''; title(s.title || '');
    if (warmVid0) playInfo(warmVid0, LV.get('quality')).catch(function () {});
    var root = mount('<div class="dpage"><button class="dback" data-back>' + ic('back') + '<span>返回</span></button><section class="dhead"><div class="dglow">' + (s.cover ? LV.img(s.cover, '', 200, true) : '') + '</div><div class="dposter poster">' + (s.cover ? LV.img(s.cover, s.title, 600, true) : '') + '</div>' +
      '<div class="dinfo">' + (s.title ? '<h1>' + esc(s.title) + '</h1>' : '<div class="skel" style="width:60%;height:44px"></div>') + '<div class="skel t" style="width:40%;margin-top:22px"></div><div class="skel t" style="width:92%;margin-top:22px"></div><div class="skel t" style="width:86%;margin-top:12px"></div><div class="skel t s" style="margin-top:12px"></div><div class="skel" style="width:190px;height:48px;border-radius:99px;margin-top:30px"></div></div></section>' +
      '<section class="dsec"><div class="sh2"><h2>选集</h2></div><div class="epg">' + epSk(30) + '</div></section></div>');
    var r; try { r = await api('/api/detail', { query: { id: id }, ttl: TTL }); } catch (e) { $('.dsec', root).outerHTML = failed(e.message); return; }
    if (stripBase(location.pathname) !== '/detail/' + id) return;
    var d = r.detail, prog = latestProgress(id, r.progress), fav = !!r.favorite, cur = prog ? prog.episodeIndex : -1, n = d.episodes.length;
    if (n === 0) { if (hops > 0) window.history.back(); else go('/'); setTimeout(function () { soonPopup(id); }, 60); return; }   // 未上线：弹窗预约，不进详情
    remember([{ id: id, title: d.title, cover: d.cover, episodes: d.count || n, intro: d.intro, plays: d.plays }]);
    title(d.title);
    var info = { id: id, title: d.title, cover: d.cover, rating: d.rating, tag: d.tag, count: d.count || n, plays: d.plays };
    if (!$('.dposter .im', root)) { $('.dposter', root).innerHTML = LV.img(d.cover, d.title, 600, true); $('.dglow', root).innerHTML = LV.img(d.cover, '', 200, true); }
    var long = (d.intro || '').length > 120;
    var rv = r.reservation || null, reserved = !!r.reserved;
    var when = rv ? (rv.publishText || (rv.time ? LV.day(rv.time) + ' 上线' : '')) : '';
    function resvBtn() { return reserved
      ? '<button class="btn lg resv on" data-act="resv">' + ic('check') + '<span>已预约</span></button>'
      : '<button class="btn primary lg resv" data-act="resv">' + ic('bell') + '<span>预约</span></button>'; }
    $('.dinfo', root).innerHTML = '<h1>' + esc(d.title) + '</h1>' +
      (n === 0 ? '<div class="dsoon"><span class="tag">待上线</span>' + (when ? '<span>' + esc(when) + '</span>' : '') + '</div>' : '<div class="dmeta">' + metaHtml(info) + '</div>') +
      (d.intro ? '<div class="dintro' + (long ? ' clamp' : '') + '"><p>' + esc(d.intro) + '</p>' + (long ? '<button class="more2" data-act="more">展开</button>' : '') + '</div>' : '') +
      (d.actors.length ? '<div class="dcast"><span>主演</span>' + d.actors.slice(0, 6).map(esc).join('<i>/</i>') + '</div>' : '') +
      '<div class="dacts">' +
      // 未上线（没有可播集）→ 预约为主操作，替代「播放」；上线的才显示播放。预约只对未上线的剧/季出现。
      (n === 0
        ? resvBtn()
        : '<button class="btn primary lg" data-act="play">' + ic('play2') + (prog ? '继续观看 第 ' + (cur + 1) + ' 集' : '播放第 1 集') + '</button>') +
      '<button class="btn lg' + (fav ? ' faved' : '') + '" data-act="fav">' + ic(fav ? 'heartfill' : 'heart') + '<span>' + (fav ? '已收藏' : '收藏') + '</span></button></div>';
    var di = $('.dintro.clamp', root); if (di) { var p = $('p', di); if (p.scrollHeight <= p.clientHeight + 2) { di.classList.remove('clamp'); $('.more2', di).remove(); } }
    var warmed = false, targetEp = d.episodes[Math.max(0, cur)];
    if (!PL && targetEp) { warmed = true; playInfo(targetEp.vid, LV.get('quality')).catch(function () {}); }
    $('.dhead', root).addEventListener('pointerover', function (e) { if (!warmed && e.target.closest('[data-act=play]') && d.episodes[Math.max(0, cur)]) { warmed = true; prewarm(d.episodes[Math.max(0, cur)].vid); } });
    $('.dhead', root).addEventListener('click', function (e) {
      var a = e.target.closest('[data-act]'); if (!a) return;
      if (a.dataset.act === 'more') { var w = a.parentNode, c = w.classList.toggle('clamp'); a.textContent = c ? '展开' : '收起'; return; }
      if (a.dataset.act === 'play') return openPlayer(d, Math.max(0, cur), prog ? prog.positionMs : 0);
      if (a.dataset.act === 'resv') {
        if (!LV.loggedIn) return showLogin(function () { route(); });
        var add = !reserved;
        // 取消预约必须二次确认，避免误触
        return (add ? Promise.resolve(true) : confirmBox('取消预约？', '《' + d.title + '》上线后将不再提醒。', '取消预约', true)).then(function (ok) {
          if (!ok) return;
          a.disabled = true;
          return api('/api/reservation', { body: { id: id, add: add } }).then(function () {
            reserved = add; a.outerHTML = resvBtn();
            LV.toast(add ? '已预约' : '已取消预约');
          }, function (er) { a.disabled = false; LV.toast(er.message); });
        });
      }
      if (!LV.loggedIn) return showLogin(function () { route(); });
      var want = !fav; a.disabled = true;
      api('/api/favorite', { body: { id: id, add: want } }).then(function () { fav = want; a.classList.toggle('faved', fav); a.innerHTML = ic(fav ? 'heartfill' : 'heart') + '<span>' + (fav ? '已收藏' : '收藏') + '</span>'; LV.toast(fav ? '已加入收藏' : '已取消收藏'); }, function (e2) { LV.toast(e2.message); }).then(function () { a.disabled = false; });
    });
    var G = epGroups(n), gi = cur > 0 && G.length > 1 ? Math.floor(cur / 100) : 0, sec = $('.dsec', root);
    function progressChanged(e) { if (!e.detail || e.detail.seriesId !== id) return; prog = e.detail; cur = prog.episodeIndex; gi = cur > 0 && G.length > 1 ? Math.floor(cur / 100) : 0; var button = $('[data-act=play]', root); if (button) button.innerHTML = ic('play2') + '继续观看 第 ' + (cur + 1) + ' 集'; drawEps(false); }
    window.addEventListener('lv:progress', progressChanged); cleanups.push(function () { window.removeEventListener('lv:progress', progressChanged); });
    function drawEps(anim) {
      sec.innerHTML = '<div class="sh2"><h2>选集</h2><span>共 ' + n + ' 集' + (prog ? ' · 上次看到第 ' + (cur + 1) + ' 集' : '') + '</span>' + epTabs(G, gi, 'data-gi') + '</div>' +
        '<div class="epg' + (anim ? ' swap' : '') + '">' + d.episodes.slice(G[gi][0] - 1, G[gi][1]).map(function (e, k) { var i = G[gi][0] - 1 + k; return epNum(e, i, i === cur, i < cur, false); }).join('') + '</div>';
    }
    if (n === 0) { sec.innerHTML = ''; sec.hidden = true; }   // 未上线没有选集，不放空壳（保留节点给季选择器定位）
    else drawEps(false);
    {
      // 多季剧先放占位（季列表第一次要几秒），避免看起来「不能选季」
      var box = null;
      if (d.multiSeason) {
        box = document.createElement('section'); box.className = 'dseasons';
        box.innerHTML = '<div class="sh2"><h2>选择季</h2><span>' + (d.seasonIndex ? '当前第 ' + d.seasonIndex + ' 季' : '') + '</span></div><div class="seasrow">' + [1, 2, 3, 4].map(function () { return '<span class="seachip sk skel"></span>'; }).join('') + '</div>';
        sec.parentNode.insertBefore(box, sec);
      }
      api('/api/seasons', { query: { id: id } }).then(function (sr) {
        var list = (sr && sr.seasons) || [];
        if (stripBase(location.pathname) !== '/detail/' + id) return;
        if (list.length < 2) { if (box) box.remove(); return; }
        if (!box) { box = document.createElement('section'); box.className = 'dseasons'; sec.parentNode.insertBefore(box, sec); }
        box.innerHTML = '<div class="sh2"><h2>选择季</h2><span>共 ' + list.length + ' 季</span></div><div class="seasrow">' +
          list.map(function (se) {
            var soon = !se.episodes;
            return '<button class="seachip' + (se.current ? ' on' : '') + (soon ? ' soon' : '') + '" data-sid="' + se.id + '">' +
              '<b>第 ' + se.number + ' 季</b>' + (soon ? '<i>待上线</i>' : '') + '</button>';
          }).join('') + '</div>';
        var on = $('.seachip.on', box), row = $('.seasrow', box); if (on && row.scrollWidth > row.clientWidth) row.scrollLeft = on.offsetLeft - (row.clientWidth - on.offsetWidth) / 2;
        box.addEventListener('click', function (e) {
          var b = e.target.closest('[data-sid]'); if (!b || b.dataset.sid === id) return;
          if (b.classList.contains('soon')) soonPopup(b.dataset.sid); else go('/detail/' + b.dataset.sid);
        });
      }).catch(function () { if (box) box.remove(); });
    }
    sec.addEventListener('click', function (e) {
      var g = e.target.closest('[data-gi]'), ep = e.target.closest('[data-ep]');
      if (g && +g.dataset.gi !== gi) { gi = +g.dataset.gi; drawEps(true); }
      else if (ep) { var i = +ep.dataset.ep; openPlayer(d, i, prog && i === cur ? prog.positionMs : 0); }
    });
    ready(); onScroll();
  }


  // ------------------------------------------------------------ 我的
  // 资料行（无卡片）+ 页签（观看历史 / 收藏 / 预约）同页切换，记录可直接删除
  function histCard(c, i, del) {   // 历史卡：海报 + 进度条 + 看到第几集，点击直接续播
    var pr = c.episodeCount ? Math.min(1, ((c.episodeIndex || 0) + (c.positionMs > 0 ? 0.5 : 0)) / c.episodeCount) : 0;
    return '<button class="card rise" style="--i:' + Math.min(i, 17) + '" data-cw="' + c.seriesId + '"><div class="poster">' + LV.img(c.cover, c.title, 360) +
      '<div class="cwpb"><i style="width:' + pr * 100 + '%"></i></div>' + (del ? '<span class="x" data-x="history" data-t="' + esc(c.title) + '" title="删除记录">' + ic('close') + '</span>' : '') + '</div>' +
      '<div class="t">' + esc(c.title) + '</div><div class="s">看到第 ' + ((c.episodeIndex || 0) + 1) + ' 集' + (c.episodeCount ? ' · 共 ' + c.episodeCount + ' 集' : '') + '</div></button>';
  }
  function rsvWhen(c) {   // 待上线：有预计时间就写时间，没有就只写「待上线」
    if (c.online) return '已上线';
    if (c.publishText) return c.publishText;
    if (c.time) return LV.day(c.time) + ' 上线';
    return '待上线';
  }
  function rsvCard(c, i) {
    var h = card({ id: c.seriesId, title: c.title, cover: c.cover, episodes: c.online ? c.episodeCount : 0 }, { i: i, nobadge: true });
    if (!c.online) h = h.replace('data-id="', 'data-soon="');
    return '<div class="rsv' + (c.online ? ' on' : ' soon') + '">' + h +
      '<span class="soon-tag' + (c.online ? ' live' : '') + '">' + esc(rsvWhen(c)) + '</span></div>';
  }
  function favCard(c, i, del) { var x = card({ id: c.seriesId, title: c.title, cover: c.cover, episodes: c.episodeCount }, { i: i, sub: '', x: del ? 'fav' : '' }); return del ? x.replace('data-x="fav"', 'data-x="fav" data-t="' + esc(c.title) + '"') : x; }
  function mine(kind) {
    title('我的');
    var KINDS = [['history', '观看历史'], ['fav', '收藏'], ['reserve', '预约']];
    var EMPTY = { history: ['clock', '还没有观看记录'], fav: ['heart', '还没有收藏'], reserve: ['bell', '还没有预约'] };
    var cur = KINDS.some(function (k) { return k[0] === kind; }) ? kind : 'history', data = {}, err = {}, limited = {}, uncertain = {}, inf = null;
    var p = me.profile || {}, guest = !LV.loggedIn;
    var tabs = guest ? [KINDS[0]] : KINDS;
    var stats = '<dl class="mstat">' + tabs.map(function(k){return '<div><dt>'+k[1]+'</dt><dd data-count="'+k[0]+'">—</dd></div>'}).join('') + '</dl>';
    var root = mount('<div class="minep"><header class="mhead">' +
      (guest ? '<div class="mav guest">' + ic('user') + '</div><div class="mnm"><h1>'+(BROWSE_ONLY?'仅浏览':'临时身份')+'</h1><p>'+(BROWSE_ONLY?'观看记录仅保存在当前浏览器':'观看记录保存在邀请码对应的临时身份中')+'</p></div>'+stats+(BROWSE_ONLY?'':'<button class="btn primary" data-golog>登录并绑定</button>')
        : '<div class="mav">' + (p.avatar ? LV.img(p.avatar, '', 160, true) : '') + '<em>' + esc((p.name || '绿')[0]) + '</em></div>' +
          '<div class="mnm"><h1>' + esc(p.name || '红果用户') + (p.vip ? '<span class="vip">VIP</span>' : '') + '</h1>' + (p.signature ? '<p>' + esc(p.signature) + '</p>' : '') + '</div>' + stats) +
      '</header><div class="bar2 mtabs"></div><div id="mc"><div class="grid">' + gridSk(2) + '</div></div></div>');
    function drawTabs() {
      $('.mtabs', root).innerHTML = '<div class="seg"><i class="th"></i>' + tabs.map(function (k) {
        return '<button data-mt="' + k[0] + '" class="' + (k[0] === cur ? 'on' : '') + '">' + k[1] + '</button>';
      }).join('') + '</div>'; placeSeg(root);
    }
    function drawStats() {
      tabs.forEach(function(k){var el=$('[data-count="'+k[0]+'"]',root),list=data[k[0]];if(!el)return;
        el.textContent=err[k[0]]||uncertain[k[0]]||!list?'—':String(list.length)+(limited[k[0]]?'+':'');
        el.title=err[k[0]]?'加载失败':uncertain[k[0]]?'记录未完全加载':limited[k[0]]?'已加载的记录数，可能还有更多':'';
      });
    }
    function cardOf(k) { return k === 'history' ? function (c, i) { return histCard(c, i, true); } : k === 'fav' ? function (c, i) { return favCard(c, i, true); } : rsvCard; }
    function draw() {
      if (inf) { inf.stop(); inf = null; }
      var mc = $('#mc', root), list = data[cur];
      if (err[cur]) { mc.innerHTML = failed(err[cur]); return; }
      if (!list) { mc.innerHTML = '<div class="grid">' + gridSk(2) + '</div>'; return; }
      if (!list.length) { mc.innerHTML = empty(EMPTY[cur][0], EMPTY[cur][1], '', '<a class="btn" href="/">去首页看看</a>'); return; }
      // 记录多时分批渲染：首屏 36 张，滚到底再接 36 张
      mc.innerHTML = '<div class="grid" id="mg"></div><div class="sentinel" id="msent"></div>';
      var g = $('#mg', mc), at = 0, render = cardOf(cur), k = cur;
      function more() { var part = list.slice(at, at + 36), base = at; at += part.length; g.insertAdjacentHTML('beforeend', part.map(function (c, i) { return render(c, base ? i : i); }).join('')); return at < list.length; }
      if (more()) { inf = LV.infinite($('#msent', mc), async function () { return cur === k && more(); }, { host: g, skel: function () { return ''; } }); cleanups.push(inf.stop); }
      ready();
    }
    function load(k, url, pick) {
      return api(url, k === 'history' && guest ? { ttl: TTL } : undefined).then(function (r) {
        var list = pick(r) || []; data[k] = list; err[k]=null; limited[k]=k==='history'?list.length>=300:k==='reserve'?!!r.limited:false;
        uncertain[k]=k==='reserve'&&!!r.partial;
        if (k === 'history') { ++historyRequest; hist = mergeRecentHistory(list); historyLoaded = true; data[k] = hist; }
        remember(list.map(function (c) { return { id: c.seriesId, title: c.title, cover: c.cover, episodes: c.episodeCount }; }));
      }, function (e) { err[k] = e.message; }).then(function () { if (!root.isConnected) return; drawStats(); if (cur === k) draw(); });
    }
    drawTabs();
    if (guest && BROWSE_ONLY) { data.history=readBrowseHistory();hist=data.history;drawStats();draw(); }
    else if (guest) load('history', '/api/history', function (d) { return d && d.items; });
    else {
      load('history', '/api/history?fresh=1', function (r) { return r.items; });
      load('fav', '/api/favorites', function (r) { return r.items; });
      load('reserve', '/api/reservations', function (r) { return (r.upcoming || []).concat(r.online || []); });
    }
    $('.mtabs', root).addEventListener('click', function (e) {
      var b = e.target.closest('[data-mt]'); if (!b || b.dataset.mt === cur) return;
      cur = b.dataset.mt; quietURL(cur === 'history' ? '/mine' : '/mine/' + cur);
      $$('.mtabs button', root).forEach(function (x) { x.classList.toggle('on', x.dataset.mt === cur); }); placeSeg(root);
      window.scrollTo(0, 0); draw();
    });
    function syncHistory(e) {
      if (!root.isConnected) return;
      data.history=e.detail&&e.detail.seriesId?mergeRecentHistory([e.detail].concat(data.history||[])):(e.detail||hist);
      limited.history=data.history.length>=300;err.history=null;drawStats();if(cur==='history')draw();
    }
    function libraryChanged(e) {
      if(!root.isConnected||guest)return;
      if(e.detail==='fav')load('fav','/api/favorites',function(r){return r.items});
      if(e.detail==='reserve')load('reserve','/api/reservations',function(r){return (r.upcoming||[]).concat(r.online||[])});
    }
    window.addEventListener('lv:history',syncHistory);window.addEventListener('lv:progress',syncHistory);window.addEventListener('lv:library-changed',libraryChanged);
    // 删除后更新顶部数量
    mineRemoved = function (id, isHist) {
      var k = isHist ? 'history' : 'fav'; if (!data[k]) return;
      data[k] = data[k].filter(function (c) { return c.seriesId !== id; }); drawStats();
      if (cur === k && !data[k].length) draw();
    };
    cleanups.push(function () { mineRemoved = null;window.removeEventListener('lv:history',syncHistory);window.removeEventListener('lv:progress',syncHistory);window.removeEventListener('lv:library-changed',libraryChanged); });
  }
  var mineRemoved = null;
  async function removeItem(x) {
    var c = x.closest('[data-id],[data-cw]'), id = c.dataset.id || c.dataset.cw, isHist = x.dataset.x === 'history', t = x.dataset.t || '';
    // 删除记录 / 取消收藏都要二次确认（和取消预约一样）
    var ok = await confirmBox(isHist ? '删除这条观看记录？' : '取消收藏？', (t ? '《' + t + '》' : '') + (isHist ? (GUEST?'将从当前观看历史中删除。':'将从观看历史中删除，并同步到红果 App。') : '将从收藏中移除，并同步到红果 App。'), isHist ? '删除' : '取消收藏', true);
    if (!ok) return;
    try {
      if (isHist && BROWSE_ONLY) { hist=hist.filter(function(h){return h.seriesId!==id});recentPlayback.delete(id);saveBrowseHistory(); }
      else if (isHist) await api('/api/history/delete', { body: { id: id } }); else await api('/api/favorite', { body: { id: id, add: false } });
      c.style.transition = 'transform .45s var(--ease),opacity .3s'; c.style.transform = 'scale(.85)'; c.style.opacity = 0;
      setTimeout(function () { var row = c.parentNode; c.remove(); if (row && row.classList.contains('row')) arrState(row); }, 360);
      hist = hist.filter(function (h) { return h.seriesId !== id; });
      saveCachedHist();
      if (mineRemoved) setTimeout(function () { if (mineRemoved) mineRemoved(id, isHist); }, 380);
      LV.toast(isHist ? '已删除' : '已取消收藏');
    } catch (e) { LV.toast(e.message); }
  }

  // ------------------------------------------------------------ 设置
  function settings() {
    title('设置');
    var GROUPS = [['acct', '账号', 'user'], ['invite', '邀请', 'remote'], ['play', '播放', 'play2'], ['dm', '弹幕', 'danmaku'], ['look', '外观', 'auto'], ['keys', '快捷键', 'sliders'], ['about', '关于', 'film']];
    function row(t, s, right) { return '<div class="srow"><div class="sl"><b>' + t + '</b>' + (s ? '<small>' + s + '</small>' : '') + '</div>' + (right || '') + '</div>'; }
    function sw(k, t, s) { return row(t, s, '<button class="sw' + (LV.get(k) ? ' on' : '') + '" data-sw="' + k + '" role="switch" aria-checked="' + !!LV.get(k) + '" aria-label="' + t + '"></button>'); }
    function pick(k, t, s, opts) { return row(t, s, seg(opts.map(function (o) { return [String(o[0]), o[1]]; }), String(LV.get(k)), 'data-pk-' + k)); }
    function rg(k, t, mn, mx, st, fmt) { return row(t, '', '<div class="rg"><input type="range" min="' + mn + '" max="' + mx + '" step="' + st + '" value="' + LV.get(k) + '" data-rg="' + k + '" aria-label="' + t + '"><em data-rv="' + k + '">' + fmt(LV.get(k)) + '</em></div>'); }
    var pct = function (v) { return Math.round(v * 100) + '%'; }, mul = function (v) { return (+v).toFixed(1) + 'x'; };
    var ROLE = { owner: '超管', admin: '管理员', member: '成员' };
    function when(s) { if (!s) return '—'; var n = Date.now() / 1000 - s; if (n < 3600) return '刚刚'; if (n < 86400) return Math.floor(n / 3600) + ' 小时前'; if (n < 86400 * 30) return Math.floor(n / 86400) + ' 天前'; var d = new Date(s * 1000); return (d.getMonth() + 1) + '月' + d.getDate() + '日'; }
    function inviteBody() {
      if (!SITE.invited) return row('邀请码', GUEST ? '登录并绑定后显示' : '', '');
      return row('邀请码<span class="srole ' + SITE.role + '">' + ROLE[SITE.role] + '</span>', SITE.uidName ? '已绑定「' + esc(SITE.uidName) + '」' : '',
          '<span class="scode" data-copycode="' + esc(SITE.code) + '" title="点击复制">' + esc(SITE.code) + '</span>') +
        (SITE.admin ? row('管理后台', '', '<a class="btn" href="/admin">' + ic('sliders') + '打开</a>') : '');
    }
    function groupBody(k) {
      if (k === 'acct') return LV.loggedIn
        ? '<div class="srow"><div class="sav">' + (me.profile && me.profile.avatar ? LV.img(me.profile.avatar, '', 120, true) : '') + '<em>' + esc(((me.profile && me.profile.name) || '红')[0]) + '</em></div><div class="sl"><b>' + esc((me.profile && me.profile.name) || '红果用户') + '</b><small>' + (me.daysLeft >= 0 ? '登录有效期 ' + me.daysLeft + ' 天' : '已登录') + '</small></div><button class="btn ghost" data-out>退出登录</button></div>'
        : GUEST ? (BROWSE_ONLY ? row('仅浏览','观看记录仅保存在当前浏览器','') : row('临时身份', '登录后可绑定红果账号并合并观看记录', '<button class="btn primary" data-golog>登录并绑定</button>'))
        : row('红果账号未登录', '', '<button class="btn primary" data-login>' + ic('user') + '扫码登录</button>');
      if (k === 'invite') return inviteBody();
      if (k === 'play') return pick('quality', '默认清晰度', '', [['360p', '360P'], ['540p', '540P'], ['720p', '720P'], ['1080p', '1080P']]) +
        sw('autonext', '自动连播', '') + sw('keepRate', '换集保持倍速', '') + sw('dock', '播放时展开选集', '');
      if (k === 'dm') return sw('danmaku', '显示弹幕', '') + pick('dmArea', '显示区域', '', [[0.25, '1/4 屏'], [0.5, '半屏'], [0.75, '3/4 屏'], [1, '全屏']]) +
        rg('dmOpacity', '不透明度', 0.2, 1, 0.05, pct) + rg('dmSpeed', '速度', 0.6, 1.8, 0.1, mul) + rg('dmSize', '字号', 0.7, 1.6, 0.1, pct);
      if (k === 'look') return pick('theme', '主题', '', [['auto', '跟随系统'], ['light', '浅色'], ['dark', '深色']]);
      if (k === 'keys') return '<div class="skeys">' + [['/', '搜索'], ['空格 / K', '播放 / 暂停'], ['← →', '后退 / 前进 10 秒'], ['↑ ↓', '上一集 / 下一集'], ['滚轮', '切换剧集'], ['F', '全屏'], ['M', '静音'], ['D', '弹幕开关'], ['T', '选集面板'], ['Esc', '退出播放']].map(function (x) { return '<div><span>' + x[1] + '</span><kbd>' + x[0] + '</kbd></div>'; }).join('') + '</div>';
      return row('绿果WEB', '第三方客户端，与红果短剧无关；不存储、不分发视频', '');
    }
    var root = mount('<div class="setp"><nav class="stoc">' + GROUPS.map(function (g) { return '<a href="#s-' + g[0] + '" data-to="' + g[0] + '">' + ic(g[2]) + '<span>' + g[1] + '</span></a>'; }).join('') + '</nav>' +
      '<div class="smain"><h1>设置</h1>' + GROUPS.map(function (g) { return '<section class="sgrp" id="s-' + g[0] + '"><h2>' + g[1] + '</h2><div class="scard" data-g="' + g[0] + '">' + groupBody(g[0]) + '</div></section>'; }).join('') + '</div></div>');
    function fill(r) { r.style.setProperty('--p', (r.value - r.min) / (r.max - r.min) * 100 + '%'); }
    $$('[data-rg]', root).forEach(fill); placeSeg(root);
    function redraw(k) { var c = $('[data-g="' + k + '"]', root); if (c) { c.innerHTML = groupBody(k); placeSeg(c); } }
    // 邀请信息每次进设置时刷新一次（设备列表可能在别处被解绑）
    fetch('/api/invite/me', { credentials: 'same-origin', cache: 'no-store' }).then(function (r) { return r.ok ? r.json() : {}; }).then(function (j) { SITE = j || {}; redraw('invite'); }).catch(function () {});
    // 目录：点击平滑滚动，滚动时高亮当前分组
    function spy() {
      var cur = GROUPS[0][0];
      GROUPS.forEach(function (g) { var el = $('#s-' + g[0], root); if (el && el.getBoundingClientRect().top < 160) cur = g[0]; });
      if (window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 4) cur = GROUPS[GROUPS.length - 1][0];
      $$('.stoc a', root).forEach(function (a) { a.classList.toggle('on', a.dataset.to === cur); });
    }
    window.addEventListener('scroll', spy, { passive: true }); cleanups.push(function () { window.removeEventListener('scroll', spy); }); spy();
    root.addEventListener('click', function (e) {
      var to = e.target.closest('[data-to]'), s = e.target.closest('[data-sw]'), pk = e.target.closest('.seg button'), cc = e.target.closest('[data-copycode]');
      if (to) { e.preventDefault(); var el = $('#s-' + to.dataset.to, root); if (el) window.scrollTo({ top: el.getBoundingClientRect().top + window.scrollY - 96, behavior: 'smooth' }); }
      else if (s) { var v = !LV.get(s.dataset.sw); LV.set(s.dataset.sw, v); s.classList.toggle('on', v); s.setAttribute('aria-checked', v); }
      else if (pk) {
        var attr = Array.from(pk.attributes).find(function (a) { return a.name.indexOf('data-pk-') === 0; }); if (!attr) return;
        var k = attr.name.slice(8).replace(/-(\w)/g, function (_, c) { return c.toUpperCase(); }), raw = attr.value, val = isNaN(+raw) ? raw : +raw;
        if (k === 'dmarea') k = 'dmArea';
        LV.set(k, val); $$('button', pk.parentNode).forEach(function (b) { b.classList.toggle('on', b === pk); }); placeSeg(pk.parentNode.parentNode);
      }
      else if (cc) {
        var code = cc.dataset.copycode; (navigator.clipboard ? navigator.clipboard.writeText(code) : Promise.reject()).then(function () { LV.toast('邀请码已复制'); }, function () {});
      }
      else if (e.target.closest('[data-out]')) logout();
      else if (e.target.closest('[data-login]')) showLogin(function () { route(); });
    });
    root.addEventListener('input', function (e) { var r = e.target.closest('[data-rg]'); if (r) { LV.set(r.dataset.rg, +r.value); fill(r); var lab = $('[data-rv="' + r.dataset.rg + '"]', root); if (lab) lab.textContent = r.dataset.rg === 'dmSpeed' ? (+r.value).toFixed(1) + 'x' : Math.round(r.value * 100) + '%'; } });
  }

  // ------------------------------------------------------------ 播放器（全屏沉浸）
  var rep = new LV.Reporter(), PL = null, afterPlayer = null, OPENING = null;
  // Cache successful source discovery briefly; recovery bypasses this cache to force a fresh model.
  function playInfo(vid, q, force) { return api('/api/play', { query: { vid: vid, q: q, fresh: force ? '1' : undefined }, ttl: force ? 0 : 60000 }); }
  // 预热：取播放地址并让服务端提前下载解密（缓存在服务端），点播放时首帧几乎即到
  function prewarm(vid, q) { playInfo(vid, q || LV.get('quality')).then(function (r) { fetch(r.src, { headers: { Range: 'bytes=0-1' } }).catch(function () {}); }).catch(function () {}); }

  // Open immediately while the series metadata is fetched. The same history
  // entry is handed to the real player once the episodes are ready.
  function openSeriesLoader(id) {
    if (PL || OPENING) return;
    closeMenu();var el=$('#player'),info=cache[id]||{},state={id:id,request:0};OPENING=state;
    el.hidden=false;el.className='';el.innerHTML='<div class="amb"><i style="background-image:url(\''+esc(LV.sized(info.cover,200)||'')+'\')"></i><b></b></div><div class="pload"><i></i><span>正在加载</span></div><div class="ui"><div class="shade t"></div><div class="ptop"><button class="cbtn" data-opening-close aria-label="返回">'+ic('back')+'</button><div class="title"><b>'+esc(info.title||'正在加载')+'</b></div></div></div>';
    window.history.pushState({p:1},'');
    function detach(){document.removeEventListener('keydown',key);window.removeEventListener('popstate',pop);el.removeEventListener('click',click)}
    function close(fromPop){if(OPENING!==state)return;OPENING=null;state.request++;detach();if(!fromPop&&history.state&&history.state.p)history.replaceState(null,'',location.href);el.hidden=true;el.innerHTML='';el.className=''}
    function key(e){if(e.key==='Escape'&&!document.fullscreenElement){e.preventDefault();close(false)}}
    function pop(){close(true)}
    function click(e){if(e.target.closest('[data-opening-close]'))close(false);else if(e.target.closest('[data-opening-retry]'))fetchDetail()}
    document.addEventListener('keydown',key);window.addEventListener('popstate',pop);el.addEventListener('click',click);
    function fetchDetail(){
      var n=++state.request,old=$('.perr',el);if(old)old.remove();$('.pload',el).classList.remove('hide');
      var p0=progressOf(id),warmVid=(p0&&p0.vid)||(info&&info.firstVid)||'';
      if(warmVid)playInfo(warmVid,LV.get('quality')).catch(function(){});
      api('/api/detail',{query:{id:id,lite:(historyLoaded||p0)?'1':undefined},ttl:TTL}).then(function(r){
        if(OPENING!==state||n!==state.request)return;
        if(!r.detail||!r.detail.episodes||!r.detail.episodes.length)throw new Error('暂无可播放的剧集');
        var p=latestProgress(id,r.progress);OPENING=null;detach();openPlayer(r.detail,p?p.episodeIndex:0,p?p.positionMs:0,true);
      }).catch(function(e){
        if(OPENING!==state||n!==state.request)return;$('.pload',el).classList.add('hide');
        el.insertAdjacentHTML('beforeend','<div class="perr"><div class="box"><button class="perr-close" data-opening-close aria-label="关闭播放器">'+ic('close')+'</button><h3>加载失败</h3><p>'+esc(e.message||'请稍后重试')+'</p><button class="bplay" data-opening-retry>重试</button></div></div>');
      });
    }
    fetchDetail();
  }

  function feedPage() {
    if (PL) return;
    title('刷剧'); closeMenu();
    view.innerHTML = '';
    var el = $('#player'); el.hidden = false; el.className = 'feed';
    el.innerHTML = '<div class="pload"><i></i><span>正在加载</span></div>';
    var aborted = false, items = [], seen = {}, src = 'fy', ch = '16', next = 0, cell = '', sess = '', more = true, dry = 0, pending = null, bq = null, bi = 0, boff = 0, bsess = '';
    function fetchMore() {
      if (pending) return pending;
      if (!more) return Promise.resolve();
      var before = items.length, req;
      if (src === 'fy') {
        req = api('/api/foryou', { query: { channel: ch, offset: next, cell: cell || undefined, session: sess || undefined } }).then(function (r) {
          var list = (r && r.page && r.page.items) || [];
          remember(list);
          list.forEach(function (s) { if (s && s.id && !seen[s.id]) { seen[s.id] = 1; items.push(s); } });
          next = r && r.page ? r.page.next : 0; cell = r ? r.cell : ''; sess = r ? r.session : '';
          if (!(r && r.page && r.page.more && list.length)) dry = 9;
        });
      } else {
        req = (bq ? Promise.resolve(bq) : boards().then(function (bl) { bq = bl.map(function (b) { return b.id; }); return bq; })).then(function (ids) {
          if (bi >= ids.length) { more = false; return; }
          return api('/api/rank', { query: { board: ids[bi], offset: boff, session: bsess } }).then(function (r) {
            var list = (r && r.items) || [];
            remember(list);
            list.forEach(function (s) { if (s && s.id && !seen[s.id]) { seen[s.id] = 1; items.push(s); } });
            if (r && r.more && list.length) { boff = r.next; bsess = r.session; } else { bi++; boff = 0; bsess = ''; }
          });
        });
      }
      pending = req.then(function () {
        dry = items.length > before ? 0 : dry + 1;
        if (src === 'fy' && dry >= 2) { src = 'board'; dry = 0; }
        else if (src === 'board' && dry >= 3) { bi++; boff = 0; bsess = ''; dry = 0; }
      }).catch(function () {
        if (src === 'fy') { src = 'board'; dry = 0; } else more = false;
      }).then(function () { pending = null; });
      return pending;
    }
    var feedCtrl = {
      items: items, index: 0,
      hasMore: function () { return more; },
      itemAt: async function (idx) {
        if (idx < 0) return null;
        while (idx >= items.length && more && !aborted) await fetchMore();
        if (aborted) return null;
        return items[idx] || null;
      },
      prefetchAhead: function (idx) {
        if (idx + 4 >= items.length && more && !aborted) fetchMore().catch(function () {});
        [idx + 1, idx + 2].forEach(function (k) {
          var s = items[k];
          if (s && s.id) {
            if (s.cover) new Image().src = LV.sized(s.cover, 720);
            api('/api/detail', { query: { id: s.id, lite: '1' }, ttl: TTL }).catch(function () {});
          }
        });
      }
    };
    cleanups.push(function () {
      aborted = true;
      if (PL && PL.feed === feedCtrl && PL.close) PL.close(true);
      else if (!PL && !OPENING) { el.hidden = true; el.innerHTML = ''; el.className = ''; }
    });
    async function boot() {
      try {
        while (!items.length && more && !aborted) await fetchMore();
        if (aborted) return;
        var lastErr = null;
        for (var k = 0; k < items.length && k < 5; k++) {
          var s = items[k], r;
          feedCtrl.prefetchAhead(k);
          try { r = await api('/api/detail', { query: { id: s.id, lite: '1' }, ttl: TTL }); } catch (er) { lastErr = er; continue; }
          if (aborted) return;
          if (r && r.detail && r.detail.episodes && r.detail.episodes.length) {
            r.detail.favorite = !!r.favorite;
            feedCtrl.index = k;
            var p = latestProgress(r.detail.id, r.progress);
            openPlayer(r.detail, p ? p.episodeIndex : 0, p ? p.positionMs : 0, true, feedCtrl);
            return;
          }
        }
        throw lastErr || new Error('暂时没有可刷的剧集');
      } catch (e) {
        if (aborted) return;
        $('.pload', el).classList.add('hide');
        el.innerHTML = '<div class="perr"><div class="box"><h3>加载失败</h3><p>' + esc(e && e.message || '请稍后重试') + '</p><button class="bplay" data-feed-retry>' + ic('refresh') + '重试</button></div></div>';
        $('[data-feed-retry]', el).onclick = function () { if (!aborted) feedPage(); };
      }
    }
    boot();
  }

  function openPlayer(d, index, posMs, reuseHistory, feed) {
    if (PL) return; closeMenu();
    var el = $('#player'); el.hidden = false; el.className = feed ? 'feed' : '';
    var TOUCH = matchMedia('(hover: none)').matches, dockOn = !feed && LV.get('dock') !== false && innerWidth >= 1280;
    el.innerHTML = '<div class="amb"><i style="background-image:url(\'' + esc(LV.sized(d.cover, 200)) + '\')"></i><b></b></div>' +
      '<div class="stage">' + (feed ? '<div class="fd-cover"></div>' : '') + '</div><div class="dml"></div><div class="flash"></div><div class="pload"><i></i><span>正在加载</span></div>' +
      '<div class="ui"><div class="shade t"></div><div class="shade b"></div>' +
      '<div class="ptop"><button class="cbtn" data-a="close" title="返回 (Esc)" aria-label="返回">' + ic('back') + '</button><div class="title"><b class="tt"></b><small class="ee"></small></div>' +
      (feed ? '<span class="fd-hint">上下滑动 / ↑ ↓ 切换剧集</span>' : '') + '</div>' +
      (feed ? '<div class="fd-nav"><button class="cbtn fd-up" data-a="fprev" title="上一部 (↑ / 滚轮上)" aria-label="上一部">' + ic('back') + '</button><button class="cbtn fd-dn" data-a="fnext" title="下一部 (↓ / 滚轮下)" aria-label="下一部">' + ic('back') + '</button></div>' : '') +
      '<div class="mid"><button class="big" data-a="toggle" title="播放 (空格)" aria-label="播放">' + ic('play', 'play') + '</button></div>' +
      '<div class="pbot">' + (feed ? '<div class="fd-info"><h2></h2><div class="fd-sub"></div><p class="fd-desc"></p></div>' : '') +
      '<div class="prog"><div class="r2"><div class="buf"></div><div class="fill"></div><div class="knob"></div></div><div class="tip">0:00</div></div>' +
      '<div class="ctrl"><button class="cbtn plain pp" data-a="toggle" title="播放 / 暂停 (空格)" aria-label="播放 / 暂停">' + ic('pause') + '</button><button class="cbtn plain" data-a="prev" title="上一集 (P)" aria-label="上一集">' + ic('prev') + '</button><button class="cbtn plain" data-a="next" title="下一集 (N)" aria-label="下一集">' + ic('next') + '</button><span class="time">0:00 / 0:00</span><span class="sp"></span>' +
      (feed ? '<button class="cbtn plain" data-a="fav" title="追剧" aria-label="追剧">' + ic('heart') + '</button>' : '') +
      '<button class="cbtn plain" data-a="dm" title="弹幕开关 (D)" aria-label="弹幕开关">' + ic('danmaku') + '</button><button class="cbtn plain" data-a="dmset" title="弹幕设置" aria-label="弹幕设置">' + ic('sliders') + '</button><button class="cbtn plain" data-a="vol" title="音量 (M 静音)" aria-label="音量">' + ic('volume') + '</button>' +
      '<button class="cbtn plain lbl" data-a="rate" title="倍速">1x</button><button class="cbtn plain lbl" data-a="quality" title="清晰度">720p</button><button class="cbtn plain lbl" data-a="dock" title="选集与评论 (T)">选集</button><button class="cbtn plain" data-a="full" title="全屏 (F)" aria-label="全屏">' + ic('full') + '</button></div></div></div>' +
      (feed ? '<div class="fd-bar"><i></i></div>' : '') +
      '<aside class="dock"><div class="dh"><div class="dseg"><i></i><button class="dtab on" data-dt="eps">选集</button><button class="dtab" data-dt="cm">评论</button></div><button class="cbtn plain" data-a="dock" aria-label="收起">' + ic('close') + '</button></div><div class="db"></div></aside>';
    el.classList.toggle('dock-on', dockOn);
    var stage = $('.stage', el), v = null, dm = new LV.Danmaku($('.dml', el)), db = $('.db', el);
    var fdCover = $('.fd-cover', stage), fdBar = $('.fd-bar i', el);
    var S = { d: d, i: Math.max(0, Math.min(index, d.episodes.length - 1)), gears: [], quality: LV.get('quality'), activeQuality: LV.get('quality'), def: '', start: posMs / 1000, idle: 0, token: 0, tab: 'eps', cmN: 30, pre: null, browseDetail: d, browseSeasonID: d.id, seasonDetails: {}, seasonRequest: 0, browseLoading: false, recovery: { attempts: 0, timer: null, stable: null, stall: null },
      feed: feed || null, feedIdx: feed ? feed.index : 0, feedWarms: Object.create(null), feedNavToken: 0, fav: !!d.favorite, slideTimer: null };
    S.seasonDetails[d.id] = d;
    PL = S;
    $('[data-a=rate]', el).textContent = LV.get('rate') + 'x';
    dm.config({ on: LV.get('danmaku'), op: LV.get('dmOpacity'), speed: LV.get('dmSpeed'), size: LV.get('dmSize'), area: LV.get('dmArea') });
    $('[data-a=dm]', el).classList.toggle('on', LV.get('danmaku'));
    if (!reuseHistory && !feed) window.history.pushState({ p: 1 }, '');
    var prog = $('.prog', el), fill = $('.fill', el), buf = $('.buf', el), knob = $('.knob', el), tip = $('.tip', el), timeEl = $('.time', el);
    function setIcon(b, n) { b.innerHTML = ic(n, n === 'play' ? 'play' : ''); }
    function ppIcon(n) { var b = $('.ctrl [data-a=toggle]', el); if (b) setIcon(b, n); var mb = $('.mid .big', el); if (mb) setIcon(mb, n); }
    function volIcon() { setIcon($('[data-a=vol]', el), !v || v.muted || !v.volume ? 'mute' : 'volume'); }
    function flash(n) { var f = $('.flash', el); f.innerHTML = ic(n); f.classList.remove('go'); void f.offsetWidth; f.classList.add('go'); }
    function showUI() {
      el.classList.remove('idle'); clearTimeout(S.idle);
      S.idle = setTimeout(function hideWhenIdle() {
        if (PL !== S || !v || v.paused) return;
        if ($('.prog.drag', el)) { S.idle = setTimeout(hideWhenIdle, 500); return; }
        closePm(); el.classList.add('idle');
      }, 2800);
    }
    function closePm() { var m = $('.pmenu', el); if (m) m.remove(); }

    // ---------- 视频元素：两路轮换，下一集在后台缓冲好，切集直接换上
    function mkVideo(poster) {
      var x = document.createElement('video'); x.playsInline = true; x.preload = 'auto'; x.className = 'vid';
      if (poster) x.poster = poster;
      x.volume = LV.get('volume'); x.muted = LV.get('muted');
      ['loadedmetadata', 'playing', 'waiting', 'pause', 'play', 'seeked', 'canplay', 'error', 'ended'].forEach(function (ev) { x.addEventListener(ev, function (e) { if (PL === S && x === v) on[ev](e); }); });
      return x;
    }
    function disposeVideo(x) {
      if (!x) return;
      if (x._warm) x._warm.cancelled = true;
      x.pause(); x.removeAttribute('src'); x.load(); x.remove();
    }
    function prepare(i) {
      if (PL !== S || i < 0 || i >= d.episodes.length) return;
      if (S.pre && S.pre.i === i && S.pre.q === S.quality) return S.pre.promise;
      dropPre();
      var ep = d.episodes[i], x = mkVideo(ep.cover ? LV.sized(ep.cover, 720) : '');
      var tk = { i: i, q: S.quality, v: x, cancelled: false };
      x._warm = tk; x.muted = true; x.classList.add('pre'); stage.appendChild(x); S.pre = tk;
      tk.promise = playInfo(ep.vid, S.quality).then(function (r) {
        if (tk.cancelled || PL !== S) throw new Error('preload cancelled');
        tk.r = r; x.src = r.src; x.load(); return r;
      }).catch(function (e) {
        if (S.pre === tk) { S.pre = null; disposeVideo(x); }
        throw e;
      });
      tk.promise.catch(function () {});
      return tk.promise;
    }
    function dropPre() { var tk = S.pre; S.pre = null; if (tk) disposeVideo(tk.v); }
    function dropNextSeason() {
      var warm=S.nextSeasonWarm;S.nextSeasonWarm=null;
      if(warm){warm.cancelled=true;if(warm.v)disposeVideo(warm.v)}
    }
    function disposeFeedWarm(w) {
      if (!w) return;
      w.cancelled = true;
      if (w.v && w.v !== v && w.v !== S.visible && w.v !== S.retiring) disposeVideo(w.v);
    }
    function dropFeedWarms(keepIds) {
      Object.keys(S.feedWarms).forEach(function (id) {
        if (keepIds && keepIds[id]) return;
        disposeFeedWarm(S.feedWarms[id]);
        delete S.feedWarms[id];
      });
    }
    function trimFeedWarms(centerIdx) {
      var keys = Object.keys(S.feedWarms);
      if (keys.length <= 5) return;
      var keep = {};
      [centerIdx - 1, centerIdx, centerIdx + 1, centerIdx + 2, centerIdx + 3].forEach(function (k) {
        var it = feed && feed.items[k];
        if (it) keep[it.id] = 1;
      });
      keys.forEach(function (id) {
        if (!keep[id] && Object.keys(S.feedWarms).length > 5) {
          disposeFeedWarm(S.feedWarms[id]);
          delete S.feedWarms[id];
        }
      });
    }
    function cssUrl(u) { return u ? "url('" + String(u).replace(/['\\\n\r]/g, '') + "')" : ''; }
    function persistSeriesProgress() {
      if (!((LV.loggedIn || GUEST) && S.reportStarted && v && d.episodes[S.i])) return null;
      var saved = Object.assign({}, progressOf(d.id) || {}, { seriesId: d.id, title: d.title, cover: d.cover,
        vid: d.episodes[S.i].vid, episodeIndex: S.i, positionMs: Math.max(0, Math.round((v.currentTime || 0) * 1000)),
        episodeCount: d.episodes.length, time: Date.now() });
      recentPlayback.set(d.id, saved);
      hist = [saved].concat(hist.filter(function (x) { return x.seriesId !== d.id; }));
      saveCachedHist();
      window.dispatchEvent(new CustomEvent('lv:progress', { detail: saved }));
      return saved;
    }
    function syncFeedUI() {
      if (!feed) return;
      var ep = d.episodes[S.i];
      var h2 = $('.fd-info h2', el), sub = $('.fd-info .fd-sub', el), desc = $('.fd-info .fd-desc', el);
      if (h2) h2.textContent = d.title || '';
      if (sub) {
        var parts = ['第 ' + ep.index + ' 集 · 共 ' + d.episodes.length + ' 集'];
        if (d.tag) parts.push(d.tag);
        if (d.plays) parts.push(playsShort(d.plays) + ' 次播放');
        sub.textContent = parts.join(' · ');
      }
      if (desc) desc.textContent = d.intro || '';
      var fb = $('[data-a=fav]', el);
      if (fb) {
        fb.classList.toggle('on', !!S.fav);
        fb.innerHTML = ic(S.fav ? 'heartfill' : 'heart');
        fb.setAttribute('aria-label', S.fav ? '已追剧' : '追剧');
        fb.title = S.fav ? '已追剧' : '追剧';
      }
      var up = $('.fd-up', el), dn = $('.fd-dn', el);
      if (up) up.disabled = S.feedIdx <= 0;
      if (dn) dn.disabled = S.feedIdx >= feed.items.length - 1 && !feed.hasMore();
    }
    function warmFeedDrama(targetIdx) {
      if (!feed || PL !== S || targetIdx < 0) return;
      var q = S.quality, tk = S.token;
      feed.itemAt(targetIdx).then(function (item) {
        if (PL !== S || !item || !item.id) return;
        if (item.cover) new Image().src = LV.sized(item.cover, 720);
        feed.prefetchAhead(targetIdx);
        var existing = S.feedWarms[item.id];
        if (existing && existing.q === q && !existing.cancelled) return existing.promise;
        if (existing) disposeFeedWarm(existing);
        var warm = { id: item.id, q: q, cancelled: false, v: null, detail: null, favorite: !!item.favorite, r: null, i: 0, startSec: 0 };
        S.feedWarms[item.id] = warm;
        trimFeedWarms(S.feedIdx);
        function alive() { return PL === S && !warm.cancelled && S.feedWarms[item.id] === warm; }
        warm.promise = api('/api/detail', { query: { id: item.id, lite: '1' }, ttl: TTL }).then(function (r) {
          if (!alive()) throw new Error('preload cancelled');
          if (!r || !r.detail || !r.detail.episodes || !r.detail.episodes.length) throw new Error('no episodes');
          warm.detail = r.detail; warm.favorite = !!r.favorite; warm.detail.favorite = warm.favorite;
          var p = latestProgress(item.id, r.progress);
          var epIdx = p ? Math.max(0, Math.min(p.episodeIndex || 0, r.detail.episodes.length - 1)) : 0;
          warm.i = epIdx; warm.startSec = p ? (p.positionMs || 0) / 1000 : 0;
          var ep = r.detail.episodes[epIdx], x = mkVideo('');
          x.muted = true; x.classList.add('pre'); stage.appendChild(x);
          warm.v = x; x._warm = warm;
          if (warm.startSec > 0) {
            x.addEventListener('loadedmetadata', function () {
              if (!warm.cancelled && Number.isFinite(x.duration) && x.duration > 0) {
                x.currentTime = Math.min(warm.startSec, Math.max(0, x.duration - 0.25));
              }
            }, { once: true });
          }
          return playInfo(ep.vid, q).then(function (info) {
            if (!alive()) throw new Error('preload cancelled');
            warm.r = info; x.src = info.src; x.load();
            return info;
          });
        }).catch(function (e) {
          if (S.feedWarms[item.id] === warm) { delete S.feedWarms[item.id]; disposeFeedWarm(warm); }
          throw e;
        });
        warm.promise.catch(function () {});
        return warm.promise;
      }).catch(function () {});
    }
    async function feedSwitch(targetIdx, dir) {
      if (!feed || PL !== S) return;
      if (targetIdx < 0) return LV.toast('已经是第一部');
      var navToken = ++S.feedNavToken;
      var item = await feed.itemAt(targetIdx);
      if (PL !== S || navToken !== S.feedNavToken) return;
      if (!item || !item.id) return LV.toast('暂时没有更多新剧');
      persistSeriesProgress();
      var warm = S.feedWarms[item.id];
      if (warm && warm.q === S.quality && !warm.cancelled) delete S.feedWarms[item.id];
      else warm = null;
      var detail = warm && warm.detail, fav = warm ? warm.favorite : !!item.favorite, epIdx = warm ? warm.i : 0, startSec = warm ? warm.startSec : 0;
      if (!detail) {
        try {
          var r = warm ? await warm.promise.then(function () { return null; }).catch(function () { return null; }) : null;
          if (PL !== S || navToken !== S.feedNavToken) { if (warm) disposeFeedWarm(warm); return; }
          if (warm && warm.detail) {
            detail = warm.detail; fav = warm.favorite; epIdx = warm.i; startSec = warm.startSec;
          } else {
            r = await api('/api/detail', { query: { id: item.id, lite: '1' }, ttl: TTL });
            if (PL !== S || navToken !== S.feedNavToken) { if (warm) disposeFeedWarm(warm); return; }
            if (!r || !r.detail || !r.detail.episodes || !r.detail.episodes.length) throw new Error('暂无可播放剧集');
            detail = r.detail; fav = !!r.favorite; detail.favorite = fav;
            var p = latestProgress(item.id, r.progress);
            epIdx = p ? Math.max(0, Math.min(p.episodeIndex || 0, detail.episodes.length - 1)) : 0;
            startSec = p ? (p.positionMs || 0) / 1000 : 0;
            warm = null;
          }
        } catch (e) {
          if (warm) disposeFeedWarm(warm);
          if (PL === S && navToken === S.feedNavToken) LV.toast((e && e.message) || '加载失败，请重试');
          return;
        }
      }
      dropPre(); dropNextSeason();
      d = detail; S.d = d; S.browseDetail = d; S.browseSeasonID = d.id;
      S.seasonDetails = {}; S.seasonDetails[d.id] = d; S.seasons = undefined; S.seasonsPending = null;
      S.gi = null; S.fav = fav; S.feedIdx = targetIdx; feed.index = targetIdx;
      trimFeedWarms(targetIdx);
      return load(epIdx, startSec, dir, { slideDir: dir, feedWarm: warm });
    }
    function feedStep(dir) { return feedSwitch(S.feedIdx + dir, dir); }
    function seasonsFor(id) {
      if(Array.isArray(S.seasons)&&S.seasons.some(function(x){return x.id===id}))return Promise.resolve(S.seasons);
      if(S.seasonsPending&&S.seasonsPending.id===id)return S.seasonsPending.promise;
      var pending={id:id};S.seasonsPending=pending;
      pending.promise=api('/api/seasons',{query:{id:id},ttl:TTL}).then(function(r){
        var list=(r&&r.seasons)||[];if(PL===S){S.seasons=list;if(S.tab==='eps'&&el.classList.contains('dock-on'))drawDock()}return list;
      }).finally(function(){if(S.seasonsPending===pending)S.seasonsPending=null});
      return pending.promise;
    }
    function warmNextSeason() {
      var origin=d.id,q=S.quality;
      if(S.nextSeasonWarm&&S.nextSeasonWarm.origin===origin&&S.nextSeasonWarm.q===q)return S.nextSeasonWarm.promise;
      dropNextSeason();var warm={origin:origin,q:q,cancelled:false,v:null};S.nextSeasonWarm=warm;
      function alive(){return PL===S&&!warm.cancelled&&d.id===origin}
      warm.promise=seasonsFor(origin).then(function(list){
        if(!alive())throw new Error('preload cancelled');
        var current=list.find(function(x){return x.id===origin})||list.find(function(x){return x.current});
        if(!current)return null;
        var next=list.filter(function(x){return x.number>current.number}).sort(function(a,b){return a.number-b.number})[0];
        if(!next)return null;warm.next=next;if(!(next.episodes>0))return warm;
        var cached=S.seasonDetails[next.id];
        return (cached?Promise.resolve({detail:cached}):api('/api/detail',{query:{id:next.id,lite:'1'},ttl:TTL})).then(function(r){
          if(!alive())throw new Error('preload cancelled');
          if(!r.detail||!r.detail.episodes||!r.detail.episodes.length)return warm;
          warm.detail=r.detail;S.seasonDetails[next.id]=r.detail;
          var ep=r.detail.episodes[0],x=mkVideo(ep.cover?LV.sized(ep.cover,720):'');warm.v=x;warm.i=0;x._warm=warm;x.muted=true;x.classList.add('pre');stage.appendChild(x);
          return playInfo(ep.vid,q).then(function(info){if(!alive())throw new Error('preload cancelled');warm.r=info;x.src=info.src;x.load();return warm});
        });
      }).catch(function(e){if(S.nextSeasonWarm===warm)dropNextSeason();throw e});
      warm.promise.catch(function(){});return warm.promise;
    }
    function presentVideo(x, token) {
      if (PL !== S || S.token !== token || x !== v) return;
      clearTimeout(S.loadingTimer);
      if (fdCover) fdCover.classList.remove('show');
      var old = S.visible, sliding = !!(S.slideTimer && S.retiring === old);
      S.visible = x; x.classList.remove('pre', 'pending'); x.style.zIndex = '';
      el.classList.add('ready'); $('.pload', el).classList.add('hide');
      var error = $('.perr', el); if (error) error.remove();
      if (old && old !== x && !sliding) {
        if (S.retiring && S.retiring !== old) disposeVideo(S.retiring);
        S.retiring = old;
        x.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 120 });
        setTimeout(function () { if (S.visible !== old) disposeVideo(old); if (S.retiring === old) S.retiring = null; }, 140);
      }
    }
    function presentFirstFrame() {
      if (S.frameCancel) S.frameCancel();
      var x = v, token = S.token;
      if (x.requestVideoFrameCallback) {
        // A fully transparent video may never be composited, so some browsers
        // never fire the frame callback even though decoding and audio continue.
        var fallback = setTimeout(function () {
          if (PL === S && S.token === token && x === v && x.readyState >= 2 && !x.paused) {
            if (x.cancelVideoFrameCallback) x.cancelVideoFrameCallback(id);
            S.frameCancel = null; presentVideo(x, token);
          }
        }, 700);
        var id = x.requestVideoFrameCallback(function () { clearTimeout(fallback); S.frameCancel = null; presentVideo(x, token); });
        S.frameCancel = function () { clearTimeout(fallback); if (x.cancelVideoFrameCallback) x.cancelVideoFrameCallback(id); };
      } else {
        var id2 = requestAnimationFrame(function () { S.frameCancel = null; if (x.readyState >= 2) presentVideo(x, token); });
        S.frameCancel = function () { cancelAnimationFrame(id2); };
      }
    }
    function applyInfo(r) {
      S.gears = r.gears || []; S.def = r.definition;
      S.activeQuality = r.definition || S.activeQuality;
      var gg = S.gears.find(function (g) { return g.definition === r.definition; }) || S.gears[0];
      if (gg && gg.width && gg.height) { S.ar = gg.width / gg.height; el.style.setProperty('--ar', S.ar); el.classList.remove('noar'); }
      $('[data-a=quality]', el).textContent = r.definition || '自动';
    }

    function drawDock() {
      $$('.dtab', el).forEach(function (t) { t.classList.toggle('on', t.dataset.dt === S.tab); });
      var on2 = $('.dtab.on', el), ind = $('.dseg i', el); if (on2 && ind) { ind.style.width = on2.offsetWidth + 'px'; ind.style.transform = 'translateX(' + on2.offsetLeft + 'px)'; }
      if (S.tab === 'eps') {
        var listDetail = S.browseDetail || d, sameSeason = listDetail.id === d.id;
        var total = listDetail.episodes.length, groups = total ? (total > 110 ? LV.group(total, 100) : [[1, total]]) : [];
        var gi = S.gi == null ? Math.floor((sameSeason ? S.i : 0) / 100) : S.gi;
        if (gi >= groups.length) gi = 0; S.gi = gi;
        // 季标签只切换选集面板的数据；当前播放剧集与自动续播仍由 d 保持。
        if (S.seasons === undefined) { S.seasons = null; seasonsFor(d.id).catch(function () { if(PL===S)S.seasons=[]; }); }
        var seas = S.seasons && S.seasons.length > 1 ? S.seasons : null;
        var currentEpisode = d.episodes[S.i], currentSeason = (S.seasons || []).filter(function (x) { return x.id === d.id; })[0];
        var status = !sameSeason && currentEpisode ? '<div class="epstatus">当前仍在播放：' + (currentSeason ? '第 ' + currentSeason.number + ' 季 · ' : '') + '第 ' + currentEpisode.index + ' 集</div>' : '';
        var episodeList = S.browseLoading ? epSk(12) : total && groups.length
          ? listDetail.episodes.slice(groups[gi][0] - 1, groups[gi][1]).map(function (e, k) { var i = groups[gi][0] - 1 + k; return epNum(e, i, sameSeason && i === S.i, sameSeason && i < S.i, sameSeason); }).join('')
          : '<div class="epempty">这一季暂时没有可播放的剧集</div>';
        db.innerHTML = '<div class="eptop">' +
          (seas ? '<div class="epseas">' + seas.map(function (x) { var cur = x.id === S.browseSeasonID, soon = !x.episodes; return '<button class="' + (cur ? 'on' : '') + (soon ? ' soon' : '') + '" data-season="' + x.id + '"' + (cur ? ' disabled' : '') + '>第 ' + x.number + ' 季' + (soon ? '<em>待上线</em>' : '') + '</button>'; }).join('') + '</div>' : '') +
          status + (groups.length > 1 ? '<div class="rngs">' + groups.map(function (g, k) { var playing = sameSeason && S.i >= g[0] - 1 && S.i <= g[1] - 1; return '<button class="' + (k === gi ? 'on' : '') + (playing ? ' has' : '') + '" data-gi="' + k + '">' + g[0] + '–' + g[1] + '</button>'; }).join('') + '</div>' : '') + '</div>' +
          '<div class="deps' + (S.browseLoading ? ' loading' : '') + '">' + episodeList + '</div>';
        var c = $('.epn.cur', db), top = $('.eptop', db); if (c) db.scrollTop = Math.max(0, c.offsetTop - (top ? top.offsetHeight : 0) - (db.clientHeight - (top ? top.offsetHeight : 0)) / 2 + c.offsetHeight / 2);
        var rg = $('.rngs .on', db); if (rg) rg.scrollIntoView({ block: 'nearest', inline: 'center' });
        var sr = $('.epseas', db), so = $('.epseas .on', db); if (sr && so) sr.scrollLeft = so.offsetLeft - (sr.clientWidth - so.offsetWidth) / 2;   // 当前季滚到中间
      } else {
        var list = dm.raw || [];
        if (!list.length) { db.innerHTML = '<div class="cmempty">' + (S.cmLoading ? '正在加载评论…' : '这一集还没有评论') + '</div>'; return; }
        db.innerHTML = '<div class="cms">' + list.slice(0, S.cmN).map(function (c) { return '<div class="cm"><div class="ca">' + (c.avatar ? LV.img(c.avatar, '', 100) : '') + esc((c.user || '用')[0]) + '</div><div class="cb"><div class="cn">' + esc(c.user || '匿名用户') + '</div><div class="cx">' + LV.emojiHTML(c.text) + '</div></div></div>'; }).join('') + '</div>' +
          (list.length > S.cmN ? '<button class="cmmore" data-cmmore>加载更多</button>' : '');
      }
    }
    $('.dock', el).addEventListener('click', function (e) {
      var t = e.target.closest('[data-dt]'), g = e.target.closest('[data-gi]'), b = e.target.closest('[data-ep]'), m = e.target.closest('[data-cmmore]'), se = e.target.closest('[data-season]');
      if (se && !se.disabled) return switchSeason(se.dataset.season);
      if (t) { S.tab = t.dataset.dt; if (S.tab === 'cm' && S.loadComments) S.loadComments(); drawDock(); } else if (g) { S.gi = +g.dataset.gi; drawDock(); } else if (b) selectEpisode(+b.dataset.ep); else if (m) { S.cmN += 30; drawDock(); }
    });

    // 切集先保留旧帧，等新视频实际呈现首帧后再交接；刷剧切剧则立刻执行纵向滑动转场。
    async function load(i, startSec, dir, options) {
      options = options || {};
      var slideDir = options.slideDir || 0, feedWarm = options.feedWarm || null;
      if (!options.recovery) {
        clearTimeout(S.recovery.timer); clearTimeout(S.recovery.stable); clearTimeout(S.recovery.stall);
        S.recovery = { attempts: 0, timer: null, stable: null, stall: null };
        S.activeQuality = S.quality;
      } else if (options.quality) S.activeQuality = options.quality;
      if (S.loaded && !LV.get('keepRate')) { LV.set('rate', 1); $('[data-a=rate]', el).textContent = '1x'; }
      var first = !S.loaded; S.loaded = true;
      rep.stop(); S.report = null; S.reportStarted = false; var tk = ++S.token; S.i = i; S.gi = null; S.cmN = 30; var ep = d.episodes[i];
      // 选新集后不能用上一集的档位列表做恢复；/api/play 的 q 只选清晰度，
      // 上游取源失败时换 q 不会让同一集更容易取到源。
      if (S.gearVid !== ep.vid) { S.gearVid = ep.vid; S.gears = []; S.def = ''; }
      $('.tt', el).textContent = d.title; $('.ee', el).textContent = '第 ' + ep.index + ' 集 · 共 ' + d.episodes.length + ' 集';
      document.title = (feed ? '刷剧 · ' : '') + '第 ' + ep.index + ' 集 · ' + d.title + ' - 绿果WEB';
      syncFeedUI();
      if (fdCover) { fdCover.classList.remove('show'); fdCover.style.backgroundImage = cssUrl(LV.sized(ep.cover || d.cover, 720)); }
      var pe = $('.perr', el); if (pe) pe.remove(); var un = $('.upnext', el); if (un) un.remove(); clearTimeout(S.nextT);
      dm.clear(); dm.setPlaying(false); S.cmLoading = true; if (el.classList.contains('dock-on')) drawDock();
      clearTimeout(S.preT); clearTimeout(S.loadingTimer);
      if (S.slideTimer) { clearTimeout(S.slideTimer); S.slideTimer = null; }
      if (S.frameCancel) { S.frameCancel(); S.frameCancel = null; }
      var old = S.visible || null, nv, r, warm = null;
      if (S.retiring && S.retiring !== old) { disposeVideo(S.retiring); S.retiring = null; }
      if (v && v !== old) disposeVideo(v);
      if (feedWarm && feedWarm.v && !options.force) {
        warm = feedWarm; nv = warm.v; r = warm.r; nv._warm = null;
      } else if (S.pre && S.pre.i === i && S.pre.q === S.quality && !options.force) {
        warm = S.pre; S.pre = null; nv = warm.v; r = warm.r;
      } else {
        dropPre(); nv = mkVideo(feed ? '' : (ep.cover ? LV.sized(ep.cover, 720) : (first ? LV.sized(d.cover, 720) : '')));
        nv.classList.add('pre'); stage.appendChild(nv);
      }
      nv.classList.remove('pre');
      if (nv.readyState >= 2) nv.classList.remove('pending');
      else nv.classList.add('pending');
      v = nv; v.muted = LV.get('muted'); v.playbackRate = LV.get('rate'); volIcon();
      if (old && old !== nv) old.pause();
      if (slideDir && old && old !== nv) {
        if (old.getAnimations) old.getAnimations().forEach(function (a) { a.cancel(); });
        if (nv.getAnimations) nv.getAnimations().forEach(function (a) { a.cancel(); });
        old.style.transform = ''; old.style.zIndex = '0';
        nv.style.transform = ''; nv.style.zIndex = '1';
        S.retiring = old;
        if (nv.readyState >= 2) nv.classList.remove('pending');
        var ease = 'cubic-bezier(.22,1,.36,1)';
        old.animate([{ transform: 'translateY(0)' }, { transform: 'translateY(' + (-slideDir * 100) + '%)' }], { duration: 260, easing: ease, fill: 'forwards' });
        nv.animate([{ transform: 'translateY(' + (slideDir * 100) + '%)' }, { transform: 'translateY(0)' }], { duration: 260, easing: ease, fill: 'forwards' });
        S.slideTimer = setTimeout(function () {
          S.slideTimer = null;
          if (S.visible !== old) disposeVideo(old);
          if (S.retiring === old) S.retiring = null;
          nv.style.zIndex = '';
        }, 280);
      }
      el.classList.remove('paused');
      $('.pload span', el).textContent = '正在加载';
      if (!old) el.classList.remove('ready');
      $('.pload', el).classList.add('hide');
      if (feed) { warmFeedDrama(S.feedIdx + 1); warmFeedDrama(S.feedIdx + 2); }
      S.loadingTimer = setTimeout(function () {
        if (PL === S && S.token === tk && S.visible !== nv && nv.readyState < 2) {
          if (slideDir && fdCover) fdCover.classList.add('show');
          $('.pload', el).classList.remove('hide');
        }
      }, slideDir ? 400 : 350);
      if (!S.ar) el.classList.add('noar');
      try {
        if (!r) { r = warm ? await warm.promise : await playInfo(ep.vid, S.activeQuality, !!options.force); if (PL !== S || tk !== S.token) return; if (!warm) nv.src = r.src; }
        applyInfo(r);
        S.start = startSec || 0;
        if (v.readyState >= 1 && S.start > 0 && Number.isFinite(v.duration) && v.duration > 0) {
          if (Math.abs((v.currentTime || 0) - S.start) > 1) v.currentTime = Math.min(S.start, Math.max(0, v.duration - 0.25));
          S.start = 0;
        }
        var p = v.play(); if (p && p.catch) p.catch(function (er) { if (tk !== S.token) return; if (er && er.name === 'NotAllowedError') { presentVideo(nv, tk); ppIcon('play'); $('.pload', el).classList.add('hide'); el.classList.add('ready', 'paused'); } else if (!er || er.name !== 'AbortError') autoRecover(tk); });
        // 真正开始播放后才上报进度/历史、拉取弹幕评论并预热下一集（见 on.playing），避免当前集首帧未出时其它请求抢占连接和带宽
        var vv = v; S.report = function () { S.report = null; if (tk === S.token) { S.reportStarted = true; rep.start(d.id, ep.vid, i, function () { return vv.currentTime; }, {title:d.title,cover:d.cover,episodeCount:d.episodes.length}); } };
        S.loadComments = function () { S.loadComments = null; dm.fetch(d.id, ep.vid, ep.duration).then(function () { S.cmLoading = false; if (tk === S.token) { dm.seek(v.currentTime || 0); if (S.tab === 'cm' && el.classList.contains('dock-on')) drawDock(); } }); };
        if (S.tab === 'cm' && el.classList.contains('dock-on')) S.loadComments();
      } catch (e) { if (tk === S.token) autoRecover(tk); }
    }
    function autoRecover(tk) {
      if (!PL || tk !== S.token || S.recovery.timer) return;
      var recovery = S.recovery, ep = d.episodes[S.i];
      clearTimeout(recovery.stall); recovery.stall = null;
      // navigator.onLine=false is a recoverable wait, not an exhausted source.
      // Keep the current episode open and resume automatically when the network returns.
      if (navigator.onLine === false) {
        $('.pload span', el).textContent = '正在缓冲'; $('.pload', el).classList.remove('hide');
        recovery.timer = setTimeout(function () {
          recovery.timer = null;
          if (PL === S && S.token === tk) autoRecover(tk);
        }, 5000);
        return;
      }
      recovery.attempts++;
      var current = v && isFinite(v.currentTime) ? v.currentTime : S.start || 0;
      var gears = (S.gears || []).filter(function (g) { return g && g.definition && +g.short > 0 && !/bvc2|bytevc2/i.test(g.codec || ''); })
        .sort(function (a, b) { return +b.short - +a.short; });
      var currentGear = gears.find(function (g) { return g.definition === S.activeQuality; });
      var qualityIndex = currentGear ? gears.indexOf(currentGear) : -1;
      // If source discovery itself keeps failing there is no quality ladder yet. Keep
      // retrying with a longer backoff before treating the selected episode as terminal.
      if (recovery.budget == null) recovery.budget = qualityIndex >= 0 ? 2 * (gears.length - qualityIndex) : 12;
      if (recovery.attempts > recovery.budget) { fail('自动恢复多次后仍无法播放本集'); return; }
      recovery.qualityFailures = (recovery.qualityFailures || 0) + 1;
      if (recovery.qualityFailures >= 2) {
        if (qualityIndex >= 0 && qualityIndex < gears.length - 1) {
          S.activeQuality = gears[qualityIndex + 1].definition;
          recovery.qualityFailures = 0;
        } else if (qualityIndex >= 0 || recovery.attempts >= recovery.budget) {
          fail('自动恢复多次后仍无法播放本集'); return;
        }
      }
      $('.pload span', el).textContent = '正在缓冲'; $('.pload', el).classList.remove('hide');
      if (!S.visible) el.classList.remove('ready');
      var delay = Math.min(30000, 600 * Math.pow(2, Math.min(recovery.attempts - 1, 6)));
      var failedToken = tk;
      recovery.timer = setTimeout(function () {
        recovery.timer = null;
        if (!PL || S.token !== failedToken || S.i !== d.episodes.indexOf(ep)) return;
        load(S.i, current, 0, { recovery: true, quality: S.activeQuality, force: true });
      }, delay);
    }
    function fail(msg) {
      $('.pload', el).classList.add('hide'); var o = $('.perr', el); if (o) o.remove();
      var gear = (S.gears || []).find(function (g) { return g.definition === S.activeQuality; });
      var hevc = (v || document.createElement('video')).canPlayType('video/mp4; codecs="hvc1.1.6.L93.B0"');
      var codecHelp = gear && /hvc1|hev1|hevc/i.test(gear.codec || '') && !hevc;
      var explanation = codecHelp
        ? '当前浏览器不支持这一路 H.265（HEVC）视频解码。'
        : (msg || '已自动尝试恢复这一集，但暂时无法播放。');
      el.insertAdjacentHTML('beforeend', '<div class="perr"><div class="box"><button class="perr-close" data-a="close" aria-label="关闭播放器">' + ic('close') + '</button><h3>无法播放</h3><p>' + esc(explanation) + '</p><button class="bplay" data-a="retry">' + ic('refresh') + '重试</button></div></div>');
    }
    var on = {
      loadedmetadata: function () {
        if (v.videoWidth) { S.ar = v.videoWidth / v.videoHeight; el.style.setProperty('--ar', S.ar); el.classList.remove('noar'); }
        if (S.start > 0 && Number.isFinite(v.duration) && v.duration > 0) v.currentTime = Math.min(S.start, Math.max(0, v.duration - 0.25)); S.start = 0; dm.seek(v.currentTime);
      },
      playing: function () {
        clearTimeout(S.recovery.stall); clearTimeout(S.loadingTimer);
        watchDrops(S.token); if (S.report) S.report(); if (S.loadComments) S.loadComments(); presentFirstFrame();
        el.classList.remove('paused'); ppIcon('pause'); dm.setPlaying(true);
        clearTimeout(S.recovery.stable); var stableToken = S.token;
        S.recovery.stable = setTimeout(function () { if (PL === S && S.token === stableToken) { S.recovery.attempts = 0; S.recovery.qualityFailures = 0; } }, 6000);
        // Normal mode already triggers prepare(at + 1) inside load() after 120ms; idempotently ensure next/season preload here too.
        if (S.preFor !== stableToken) {
          S.preFor = stableToken; var at = S.i;
          if (feed) { warmFeedDrama(S.feedIdx + 1); warmFeedDrama(S.feedIdx + 2); }
          clearTimeout(S.preT);
          S.preT = setTimeout(function () {
            if (PL !== S || S.i !== at || S.token !== stableToken) return;
            var next = prepare(at + 1);
            if (!feed && at >= d.episodes.length - 2) warmNextSeason().catch(function () {});
            if (next && at + 2 < d.episodes.length) next.then(function () {
              if (PL === S && S.i === at && S.token === stableToken) prewarm(d.episodes[at + 2].vid, S.quality);
            }).catch(function () {});
          }, feed ? 1000 : 80);
        }
      },
      waiting: function () {
        if (v && v.paused) return; // 暂停中的缓冲不显示「正在加载」
        clearTimeout(S.loadingTimer);
        var loadingToken = S.token;
        S.loadingTimer = setTimeout(function () { if (PL === S && S.token === loadingToken && v && !v.paused && v.readyState < 3) $('.pload', el).classList.remove('hide'); }, 500);
        dm.setPlaying(false);
        if (S.recovery.stall) return;
        var waitingToken = S.token, waitingVideo = v, waitingAt = v && v.currentTime || 0;
        // Some browsers leave a dead stream in HAVE_CURRENT_DATA forever and never
        // dispatch "error". Detect a real stall and recover the same vid at its position.
        var recoveryState = S.recovery;
        recoveryState.stall = setTimeout(function () {
          recoveryState.stall = null;
          if (PL !== S || S.token !== waitingToken || v !== waitingVideo || !v || v.paused) return;
          if (v.readyState < 3 || Math.abs(v.currentTime - waitingAt) < 0.25) autoRecover(waitingToken);
        }, 18000);
      },
      pause: function () { clearTimeout(S.recovery.stall); $('.pload', el).classList.add('hide'); if (v.ended && LV.get('autonext') && (feed || S.i + 1 < d.episodes.length || (S.nextSeasonWarm && S.nextSeasonWarm.next && S.nextSeasonWarm.next.episodes > 0))) { dm.setPlaying(false); rep.flush(); return; } if (v.readyState >= 2) el.classList.add('paused'); ppIcon('play'); dm.setPlaying(false); showUI(); rep.flush(); },
      play: function () { el.classList.remove('paused'); ppIcon('pause'); if (!el.classList.contains('idle')) showUI(); },
      seeked: function () { dm.seek(v.currentTime); rep.flush(); if (v.paused) $('.pload', el).classList.add('hide'); },
      canplay: function () { if (v.paused) $('.pload', el).classList.add('hide'); },
      error: function () { if (v.getAttribute('src')) autoRecover(S.token); },
      ended: function () {
        rep.flush(); var nx = S.i + 1;
        // 播完直接切下一集，无倒计时（下一集已在后台缓冲，衔接无感）
        if (nx < d.episodes.length) { if (LV.get('autonext')) load(nx, 0, 1); return; }
        if (LV.get('autonext')) { if (feed) feedStep(1); else nextSeason(); }   // 刷剧播完一部自动续播下一部
      }
    };
    function selectEpisode(i) {
      var target = S.browseDetail || d, changedSeason = target.id !== d.id;
      if (changedSeason) {
        dropPre();dropNextSeason();
        d = target; S.d = d; S.browseSeasonID = d.id; S.gi = null;
      }
      return load(i, 0, changedSeason ? 0 : (i > S.i ? 1 : -1));
    }
    // 切季只加载该季的选集列表；点某一集后才切换播放季。
    function switchSeason(id) {
      if (S.browseSeasonID === id) return;
      var request = ++S.seasonRequest, cached = S.seasonDetails[id];
      S.browseSeasonID = id; S.gi = null;
      if (cached) { S.browseDetail = cached; S.browseLoading = false; drawDock(); return; }
      S.browseLoading = true; drawDock();
      api('/api/detail', { query: { id: id } }).then(function (r) {
        if (PL !== S || request !== S.seasonRequest || !r.detail) return;
        S.seasonDetails[id] = r.detail; S.browseDetail = r.detail; S.browseLoading = false; drawDock();
      }, function (er) {
        if (PL !== S || request !== S.seasonRequest) return;
        S.browseSeasonID = (S.browseDetail || d).id; S.browseLoading = false; drawDock(); LV.toast(er.message);
      });
    }
    function nextSeason(manual) {
      var tk = S.token,origin=d.id;
      warmNextSeason().then(function (warm) {
        if (PL!==S || S.token!==tk || d.id!==origin) return;
        if(!warm||!warm.next){if(manual)LV.toast('已经是最后一集');return}
        var nxt=warm.next;
        var label = '第 ' + nxt.number + ' 季';
        if (nxt.episodes > 0) {   // 下一季已上线：自动续播
          if(!warm.detail||!warm.detail.episodes.length)return LV.toast(label+'暂时无法播放');
          if((LV.loggedIn||GUEST)&&S.reportStarted&&v&&d.episodes[S.i]){
            var saved=Object.assign({},progressOf(d.id)||{},{seriesId:d.id,title:d.title,cover:d.cover,vid:d.episodes[S.i].vid,episodeIndex:S.i,positionMs:Math.max(0,Math.round((v.currentTime||0)*1000)),episodeCount:d.episodes.length,time:Date.now()});
            recentPlayback.set(d.id,saved);hist=[saved].concat(hist.filter(function(x){return x.seriesId!==d.id}));saveCachedHist();window.dispatchEvent(new CustomEvent('lv:progress',{detail:saved}));
          }
          LV.toast('本季已播完，继续播放' + label);
          dropPre();S.nextSeasonWarm=null;S.pre=warm;d=warm.detail;S.d=d;S.browseDetail=d;S.browseSeasonID=d.id;S.gi=null;
          $('.amb i',el).style.backgroundImage="url('"+(LV.sized(d.cover,200)||'').replace(/['\\\n\r]/g,'')+"')";
          return load(0,0,1);
        }
        // 下一季已公布但未上线：已预约就提示；没预约就问要不要预约（这里就能直接预约）
        api('/api/detail', { query: { id: nxt.id } }).then(function (r3) {
          if (!PL || S.token !== tk) return;
          if (r3.reserved) return LV.toast('本季已播完 · ' + label + '已预约');
          if (!LV.loggedIn) return LV.toast('本季已播完 · ' + label + '尚未上线');
          confirmBox(label + '尚未上线', '预约后，上线时会出现在「我的 · 预约」。', '预约', false).then(function (ok) {
            if (!ok) return;
            api('/api/reservation', { body: { id: nxt.id, add: true } }).then(function () { LV.toast('已预约' + label); }, function (er) { LV.toast(er.message); });
          });
        }).catch(function () {});
      }).catch(function () { if(PL===S&&S.token===tk)LV.toast('下一季加载失败，请重试'); });
    }
    // 1080p 掉帧监测：这台电脑解码吃力（无 HEVC 硬解）时自动降到 720p，下次直接从 720p 起播
    function watchDrops(tk) {
      clearInterval(S.dropT);
      if (!v || !v.getVideoPlaybackQuality || !/1080/.test(S.def || '')) return;
      // 持续严重掉帧才降到 720p：连续 3 个 4 秒窗口掉帧都超过 20%；刚起播 / 刚拖动后的第一个窗口不算（解码器预热本来就会丢几帧）。
      // 只对本次播放生效，不改「默认清晰度」设置——下次打开仍按用户选的清晰度。
      var last = v.getVideoPlaybackQuality(), bad = 0, skip = 1;
      S.dropT = setInterval(function () {
        if (PL !== S || S.token !== tk || !v) { clearInterval(S.dropT); return; }
        if (v.paused || v.seeking || document.hidden || !/1080/.test(S.def || '')) { skip = 1; return; }
        var q = v.getVideoPlaybackQuality(), tot = q.totalVideoFrames - last.totalVideoFrames, dr = q.droppedVideoFrames - last.droppedVideoFrames; last = q;
        if (skip) { skip = 0; return; }
        if (tot < 40) return;
        bad = dr / tot > 0.2 ? bad + 1 : 0;
        if (bad >= 3) {
          clearInterval(S.dropT); S.quality = '720p'; dropPre();dropNextSeason();
          LV.toast('播放卡顿，本次改用 720p');
          load(S.i, v.currentTime || 0, 0);
        }
      }, 4000);
    }
    function frame() {
      if (!PL) return; var du = v && v.duration || 0, t = v && v.currentTime || 0;
      if (du) {
        var pc = t / du * 100; if (!prog.classList.contains('drag')) { fill.style.width = pc + '%'; knob.style.left = pc + '%'; }
        if (fdBar) fdBar.style.width = pc + '%';
        if (v.buffered.length) { var bf = 0; for (var k = 0; k < v.buffered.length; k++) if (v.buffered.start(k) <= t + 0.5) bf = Math.max(bf, v.buffered.end(k)); buf.style.width = bf / du * 100 + '%'; }
        timeEl.textContent = LV.time(t) + ' / ' + LV.time(du);
      }
      dm.tick(t); S.raf = requestAnimationFrame(frame);
    }
    S.raf = requestAnimationFrame(frame);

    function posOf(ev) { var r = prog.getBoundingClientRect(); return Math.max(0, Math.min(1, (ev.clientX - r.left) / r.width)); }
    prog.addEventListener('pointermove', function (ev) { var p = posOf(ev); tip.style.left = p * 100 + '%'; tip.textContent = LV.time(p * (v && v.duration || 0)); });
    prog.addEventListener('pointerdown', function (ev) {
      prog.setPointerCapture(ev.pointerId); prog.classList.add('drag');
      var mv = function (e) { var p = posOf(e); fill.style.width = p * 100 + '%'; knob.style.left = p * 100 + '%'; tip.style.left = p * 100 + '%'; tip.textContent = LV.time(p * (v.duration || 0)); S.seekTo = p; };
      mv(ev);
      var up = function () { prog.classList.remove('drag'); prog.removeEventListener('pointermove', mv); prog.removeEventListener('pointerup', up); if (v.duration) v.currentTime = S.seekTo * v.duration; };
      prog.addEventListener('pointermove', mv); prog.addEventListener('pointerup', up);
    });
    function popAt(m, kind, extra) {
      var btn = $('[data-a=' + kind + ']', el), r = btn.getBoundingClientRect();
      m.style.right = Math.max(12, innerWidth - r.right - (extra || 30)) + 'px'; m.style.bottom = (innerHeight - r.top + 12) + 'px';
      el.appendChild(m);
    }
    function pmenu(kind, rows, pick) {
      var had = $('.pmenu[data-k=' + kind + ']', el); closePm(); if (had) return;
      var m = document.createElement('div'); m.className = 'pmenu'; m.dataset.k = kind;
      m.innerHTML = rows.map(function (x, i) { return '<button data-r="' + i + '" class="' + (x[2] ? 'on' : '') + '">' + x[0] + (x[2] ? ic('check') : '') + '</button>'; }).join('');
      m.onclick = function (e) { e.stopPropagation(); var b = e.target.closest('button'); if (b) { pick(rows[+b.dataset.r][1]); closePm(); } };
      popAt(m, kind);
    }
    function fillr(i) { i.style.setProperty('--p', (i.value - i.min) / (i.max - i.min) * 100 + '%'); }
    function volPanel() {
      var had = $('.pmenu[data-k=vol]', el); closePm(); if (had) return;
      var m = document.createElement('div'); m.className = 'pmenu volp'; m.dataset.k = 'vol';
      var val = v.muted ? 0 : v.volume;
      m.innerHTML = '<button class="vm" data-vm aria-label="静音">' + ic(val ? 'volume' : 'mute') + '</button><input type="range" min="0" max="1" step="0.02" value="' + val + '" aria-label="音量"><em>' + Math.round(val * 100) + '</em>';
      var inp = $('input', m); fillr(inp);
      function sync() { var x = v.muted ? 0 : v.volume; inp.value = x; fillr(inp); $('em', m).textContent = Math.round(x * 100); $('.vm', m).innerHTML = ic(x ? 'volume' : 'mute'); volIcon(); }
      inp.addEventListener('input', function () { v.muted = false; v.volume = +inp.value; LV.set('volume', v.volume); LV.set('muted', false); sync(); });
      m.addEventListener('click', function (e) { e.stopPropagation(); if (e.target.closest('[data-vm]')) { v.muted = !v.muted || !v.volume; if (!v.volume) v.volume = .6; LV.set('muted', v.muted); sync(); } });
      popAt(m, 'vol', 90);
    }
    function dmPanel() {
      var had = $('.pmenu[data-k=dmset]', el); closePm(); if (had) return;
      var m = document.createElement('div'); m.className = 'pmenu dmp'; m.dataset.k = 'dmset';
      function row(k, t, mn, mx, st, f) { return '<label class="dr"><span>' + t + '</span><input type="range" min="' + mn + '" max="' + mx + '" step="' + st + '" value="' + LV.get(k) + '" data-k="' + k + '"><em>' + f(LV.get(k)) + '</em></label>'; }
      var pc = function (x) { return Math.round(x * 100) + '%'; }, sp = function (x) { return (+x).toFixed(1) + 'x'; };
      var F = { dmOpacity: pc, dmSpeed: sp, dmSize: pc };
      m.innerHTML = '<div class="dh2"><b>弹幕</b><button class="sw' + (LV.get('danmaku') ? ' on' : '') + '" data-k="sw" aria-label="显示弹幕"></button></div>' +
        '<div class="dr"><span>区域</span><div class="dseg2">' + [[0.25, '1/4'], [0.5, '半屏'], [0.75, '3/4'], [1, '全屏']].map(function (a) { return '<button data-area="' + a[0] + '" class="' + (LV.get('dmArea') === a[0] ? 'on' : '') + '">' + a[1] + '</button>'; }).join('') + '</div></div>' +
        row('dmOpacity', '不透明度', 0.2, 1, 0.05, pc) + row('dmSpeed', '速度', 0.6, 1.8, 0.1, sp) + row('dmSize', '字号', 0.7, 1.6, 0.1, pc);
      $$('input', m).forEach(fillr);
      m.addEventListener('click', function (e) {
        e.stopPropagation();
        var s = e.target.closest('[data-k=sw]'), a = e.target.closest('[data-area]');
        if (s) { var o = !LV.get('danmaku'); LV.set('danmaku', o); dm.config({ on: o }); s.classList.toggle('on', o); $('[data-a=dm]', el).classList.toggle('on', o); }
        else if (a) { LV.set('dmArea', +a.dataset.area); dm.config({ area: +a.dataset.area }); $$('[data-area]', m).forEach(function (b) { b.classList.toggle('on', b === a); }); }
      });
      m.addEventListener('input', function (e) {
        var i = e.target.closest('input[data-k]'); if (!i) return; var k = i.dataset.k; LV.set(k, +i.value); fillr(i); i.nextElementSibling.textContent = F[k](+i.value);
        dm.config(k === 'dmOpacity' ? { op: +i.value } : k === 'dmSpeed' ? { speed: +i.value } : { size: +i.value });
      });
      popAt(m, 'dmset', 120);
    }
    function seek(dt) { if (!v || !v.duration) return; v.currentTime = Math.max(0, Math.min(v.duration, v.currentTime + dt)); flash(dt < 0 ? 'rew' : 'fwd'); }
    function step(dir) {
      var n = S.i + dir;
      if (n < 0) return LV.toast('已经是第一集');
      if (n >= d.episodes.length) { if(dir>0&&!feed)return nextSeason(true); return LV.toast('已经是最后一集'); }
      load(n, 0, dir);
    }
    function act(a) {
      switch (a) {
        case 'close':
          if (feed) { if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen().catch(function () {}); else el.classList.remove('full'); return; }
          return closePlayer();
        case 'toggle': if (!v) return; if (v.paused) v.play(); else v.pause(); break;
        case 'prev': return step(-1);
        case 'next': return step(1);
        case 'fprev': return feedStep(-1);
        case 'fnext': return feedStep(1);
        case 'fav':
          if (!feed) return;
          if (!LV.loggedIn) return showLogin(function () { if (PL === S) route(); });
          var nextFav = !S.fav; S.fav = nextFav; syncFeedUI();
          api('/api/favorite', { body: { id: d.id, add: nextFav } }).then(function () { LV.toast(nextFav ? '已加入追剧' : '已取消追剧'); }, function (er) {
            if (PL === S) { S.fav = !nextFav; syncFeedUI(); }
            LV.toast((er && er.message) || '操作失败');
          });
          break;
        case 'dm': var o = !LV.get('danmaku'); LV.set('danmaku', o); dm.config({ on: o }); $('[data-a=dm]', el).classList.toggle('on', o); LV.toast(o ? '弹幕已开启' : '弹幕已关闭'); break;
        case 'vol': return volPanel();
        case 'mute': v.muted = !v.muted; LV.set('muted', v.muted); volIcon(); LV.toast(v.muted ? '已静音' : '音量 ' + Math.round(v.volume * 100) + '%'); break;
        case 'full': if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen().catch(function () {}); else if (el.requestFullscreen) el.requestFullscreen().catch(function () { if (v && v.webkitEnterFullscreen) v.webkitEnterFullscreen(); }); else if (v && v.webkitEnterFullscreen) v.webkitEnterFullscreen(); break;   // iPhone 只支持视频元素自身全屏
        case 'retry': load(S.i, v && v.currentTime || 0, 0); break;
        case 'cancelnext': clearTimeout(S.nextT); var u = $('.upnext', el); if (u) u.remove(); break;
        case 'dock': var o2 = !el.classList.contains('dock-on'); el.classList.toggle('dock-on', o2); if (!feed) LV.set('dock', o2); if (o2) drawDock(); break;
        case 'dmset': return dmPanel();
        case 'rate': return pmenu('rate', [0.75, 1, 1.25, 1.5, 2].map(function (r) { return [r + 'x', r, v.playbackRate === r]; }), function (r) { v.playbackRate = r; LV.set('rate', r); $('[data-a=rate]', el).textContent = r + 'x'; });
        case 'quality': if (!S.gears.length) return; return pmenu('quality', S.gears.map(function (g) { return [g.definition, g.definition, g.definition === S.def]; }).reverse(), function (q) { S.quality = q; LV.set('quality', q); dropPre();dropNextSeason();dropFeedWarms(); load(S.i, v.currentTime || 0, 0); });
      }
    }
    el.addEventListener('click', function (e) {
      if (e.target.closest('.pbot,.ptop,.pmenu,.dock,.fd-nav')) showUI();
      var b = e.target.closest('[data-a]'); if (b) { e.stopPropagation(); return act(b.dataset.a); }
      if (e.target.closest('.dock,.pbot,.ptop,.pmenu,.upnext,.perr,.mid,.fd-nav')) { if (!e.target.closest('.pmenu')) closePm(); return; }
      if ($('.pmenu', el)) return closePm();
      // 触屏：点画面只呼出 / 收起控件（播放暂停用中间按钮），和手机视频 App 一致
      if (TOUCH) { if (el.classList.contains('dock-on')) { el.classList.remove('dock-on'); if (!feed) LV.set('dock', false); return; } if (el.classList.contains('idle') || (v && v.paused)) showUI(); else { clearTimeout(S.idle); el.classList.add('idle'); } return; }
      clearTimeout(S.ck); S.ck = setTimeout(function () { act('toggle'); }, 220);
    });
    // 触屏：画面上下滑切集/切剧，抽屉把手下滑收起
    var sw = null;
    el.addEventListener('touchstart', function (e) { if (e.touches.length !== 1) { sw = null; return; } var t = e.touches[0]; sw = { x: t.clientX, y: t.clientY, at: Date.now(), dock: !!e.target.closest('.dh'), skip: !!e.target.closest('.dock:not(.dh),.pbot,.pmenu,.perr,.upnext,.fd-nav') && !e.target.closest('.dh') }; }, { passive: true });
    el.addEventListener('touchend', function (e) {
      if (!sw || sw.skip) return; var t = e.changedTouches[0], dx = t.clientX - sw.x, dy = t.clientY - sw.y, fast = Date.now() - sw.at < 600; var s0 = sw; sw = null;
      if (!fast || Math.abs(dy) < 70 || Math.abs(dy) < Math.abs(dx) * 1.5) return;
      if (s0.dock) { if (dy > 0) { el.classList.remove('dock-on'); if (!feed) LV.set('dock', false); } return; }
      if (el.classList.contains('dock-on')) return;
      clearTimeout(S.ck);
      if (feed) feedStep(dy < 0 ? 1 : -1); else step(dy < 0 ? 1 : -1);
    }, { passive: true });
    el.addEventListener('dblclick', function (e) { if (!e.target.closest('.dock,.pbot,.ptop,.pmenu,.mid,.fd-nav')) { clearTimeout(S.ck); act('full'); } });
    var lastPointerX = null, lastPointerY = null;
    el.addEventListener('pointermove', function (e) {
      if (e.pointerType !== 'mouse' || (e.clientX === lastPointerX && e.clientY === lastPointerY)) return;
      lastPointerX = e.clientX; lastPointerY = e.clientY; showUI();
    });
    showUI();   // 开播后没动过鼠标/没点过画面，控件也按时自动隐藏   // 触屏点击会补发模拟 mousemove，不能当成「呼出控件」
    // 滚轮切集/切剧：一次手势只切一次
    var wheelLock = 0, wheelAcc = 0;
    el.addEventListener('wheel', function (e) {
      if (!el.classList.contains('idle')) showUI();
      if (e.target.closest('.dock,.pmenu')) return;
      e.preventDefault(); var now = Date.now();
      if (now < wheelLock) return;
      wheelAcc += e.deltaY;
      if (Math.abs(wheelAcc) > 60) {
        if (feed) feedStep(wheelAcc > 0 ? 1 : -1); else step(wheelAcc > 0 ? 1 : -1);
        wheelAcc = 0; wheelLock = now + 700;
      }
    }, { passive: false });
    function key(e) {
      if (!PL || (/INPUT|SELECT|TEXTAREA/.test(e.target.tagName) && e.target.type !== 'range')) return;
      var k = e.key, epKey = k === 'ArrowUp' || k === 'ArrowDown' || k === 'n' || k === 'N' || k === 'p' || k === 'P';
      if (!epKey || !el.classList.contains('idle')) showUI();
      if (k === ' ' || k === 'k' || k === 'K') { e.preventDefault(); act('toggle'); }
      else if (k === 'ArrowRight') { e.preventDefault(); seek(10); }
      else if (k === 'ArrowLeft') { e.preventDefault(); seek(-10); }
      else if (k === 'ArrowUp') { e.preventDefault(); if (!e.repeat) { if (feed) feedStep(-1); else step(-1); } }
      else if (k === 'ArrowDown') { e.preventDefault(); if (!e.repeat) { if (feed) feedStep(1); else step(1); } }
      else if (k === 'n' || k === 'N') step(1); else if (k === 'p' || k === 'P') step(-1);
      else if (k === 'f' || k === 'F') act('full'); else if (k === 'm' || k === 'M') act('mute'); else if (k === 'd' || k === 'D') act('dm'); else if (k === 't' || k === 'T') act('dock');
      else if (k === 'Escape') {
        if ($('.pmenu', el)) closePm();
        else if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen().catch(function () {});
        else if (feed) el.classList.remove('full');
        else closePlayer();
      }
    }
    document.addEventListener('keydown', key);
    function onFs() { el.classList.toggle('full', document.fullscreenElement === el); }
    document.addEventListener('fullscreenchange', onFs);
    function onPop() { if (PL && !feed) closePlayer(true); }
    window.addEventListener('popstate', onPop);
    function closePlayer(fromPop) {
      if (!PL) return; var cur = S; PL = null;
      cancelAnimationFrame(cur.raf); clearTimeout(cur.idle); clearTimeout(cur.nextT); clearTimeout(cur.ck); clearInterval(cur.dropT);
      clearTimeout(cur.slideTimer);
      document.removeEventListener('keydown', key); document.removeEventListener('fullscreenchange', onFs); window.removeEventListener('popstate', onPop);
      clearTimeout(cur.preT); clearTimeout(cur.loadingTimer); clearTimeout(cur.recovery.timer); clearTimeout(cur.recovery.stall); clearTimeout(cur.recovery.stable); if (cur.frameCancel) cur.frameCancel();
      var savedProgress = persistSeriesProgress();
      var saved = rep.stop(); dropPre(); dropNextSeason(); dropFeedWarms(); $$('video', stage).forEach(function (x) { x.pause(); x.removeAttribute('src'); x.load(); }); dm.clear();
      if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen().catch(function () {});
      if (fromPop !== true && window.history.state && window.history.state.p) window.history.replaceState(null, '', location.href);
      if (cur.feed) {
        if (!PL && !OPENING) { el.hidden = true; el.innerHTML = ''; el.className = ''; }
      } else {
        el.classList.add('out'); setTimeout(function () { if (!PL && !OPENING) { el.hidden = true; el.innerHTML = ''; el.className = ''; } }, 300);
        title((cache[d.id] && cache[d.id].title) || d.title);
      }
      Promise.resolve(saved).then(loadHistory).catch(function () { if (savedProgress) LV.toast('播放进度保存失败，请检查网络'); });
      if (afterPlayer) { var cb = afterPlayer; afterPlayer = null; cb(d.id, cur.i); }
    }
    S.close = closePlayer;
    showUI(); if (dockOn) drawDock(); load(S.i, S.start, 0);
  }
  var suppressPop = false;

  // ------------------------------------------------------------ 登录与启动
  var closeLoginFn = null;
  // 二次确认弹窗：返回 Promise<boolean>。Esc / 点遮罩 / 「再想想」都算取消。
  // 未上线的剧：海报 + 上线时间 + 预约，不进详情页
  function soonPopup(id) {
    var w = document.createElement('div'); w.className = 'cfm soonw';
    w.innerHTML = '<div class="cfm-card soonp"><div class="sp-poster poster skel"></div><div class="sp-body"><div class="skel" style="height:24px;width:70%"></div><div class="skel" style="height:14px;width:40%;margin-top:12px"></div></div></div>';
    document.body.appendChild(w); requestAnimationFrame(function () { w.classList.add('on'); });
    var done = false, born = Date.now();
    function close() { if (done) return; done = true; document.removeEventListener('keydown', key, true); window.removeEventListener('lv:nav', nav); window.removeEventListener('popstate', nav); w.classList.remove('on'); setTimeout(function () { w.remove(); }, 200); }
    function key(e) { if (e.key === 'Escape' && !$('.cfm:not(.soonw)')) { e.preventDefault(); close(); } }
    function nav() { if (Date.now() - born > 400) close(); }
    document.addEventListener('keydown', key, true); window.addEventListener('lv:nav', nav); window.addEventListener('popstate', nav);
    w.addEventListener('click', function (e) { if (e.target === w || e.target.closest('[data-close]')) close(); });
    api('/api/detail', { query: { id: id }, ttl: TTL }).then(function (r) {
      if (done) return;
      var d = r.detail; if (d.episodes.length) { close(); return go('/detail/' + id); }
      var rv = r.reservation || null, reserved = !!r.reserved, when = rv ? (rv.publishText || (rv.time ? LV.day(rv.time) + ' 上线' : '')) : '';
      function btn() { return reserved ? '<button class="btn lg resv on" data-rs>' + ic('check') + '<span>已预约</span></button>' : '<button class="btn primary lg" data-rs>' + ic('bell') + '<span>预约</span></button>'; }
      $('.soonp', w).innerHTML = '<div class="sp-poster poster">' + LV.img(d.cover, d.title, 400) + '</div><div class="sp-body"><span class="sp-tag">' + esc(when || '待上线') + '</span><h3>' + esc(d.title) + '</h3>' +
        (d.intro ? '<p>' + esc(d.intro) + '</p>' : '') + '<div class="sp-acts">' + btn() + '<button class="btn lg" data-close>关闭</button></div></div>';
      $('.soonp', w).addEventListener('click', function (e) {
        var a = e.target.closest('[data-rs]'); if (!a) return;
        if (!LV.loggedIn) { close(); return showLogin(); }
        var add = !reserved;
        (add ? Promise.resolve(true) : confirmBox('取消预约？', '《' + d.title + '》上线后将不再提醒。', '取消预约', true)).then(function (ok) {
          if (!ok) return; a.disabled = true;
          api('/api/reservation', { body: { id: id, add: add } }).then(function () { reserved = add; a.outerHTML = btn(); LV.dropCache(); LV.toast(add ? '已预约' : '已取消预约'); }, function (er) { a.disabled = false; LV.toast(er.message); });
        });
      });
    }).catch(function (er) { if (!done) { close(); LV.toast(er.message); } });
  }
  function confirmBox(title, msg, okText, danger) {
    return new Promise(function (resolve) {
      var w = document.createElement('div'); w.className = 'cfm';
      w.innerHTML = '<div class="cfm-card" role="alertdialog" aria-modal="true"><h3>' + esc(title) + '</h3><p>' + esc(msg) + '</p>' +
        '<div class="cfm-acts"><button class="btn" data-c="0">再想想</button><button class="btn ' + (danger ? 'danger' : 'primary') + '" data-c="1">' + esc(okText) + '</button></div></div>';
      (document.fullscreenElement || document.body).appendChild(w); requestAnimationFrame(function () { w.classList.add('on'); });   // 全屏播放时挂到全屏元素里才看得见
      var done = false;
      function close(v) { if (done) return; done = true; document.removeEventListener('keydown', key, true); window.removeEventListener('lv:nav', nav); window.removeEventListener('popstate', nav); w.classList.remove('on'); setTimeout(function () { w.remove(); }, 200); resolve(v); }
      function key(e) { if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(false); } }
      function nav() { close(false); }   // 切页面（含手机返回键）时当作取消
      window.addEventListener('lv:nav', nav); window.addEventListener('popstate', nav);
      document.addEventListener('keydown', key, true);
      w.addEventListener('click', function (e) { var b = e.target.closest('[data-c]'); if (b) close(b.dataset.c === '1'); else if (e.target === w) close(false); });
      setTimeout(function () { var b = $('[data-c="0"]', w); if (b) b.focus(); }, 30);   // 默认焦点在「再想想」
    });
  }
  // 游客（未登录也可浏览）：播放与个人数据需要登录；登录页在 /login，登录后回到当前页面
  var GUEST = false;
  function goLogin() { try { sessionStorage.setItem('lvguo.back', currentURL()); } catch (e) {} location.href = '/login'; }
  function needLogin(title, msg) {
    closeMenu();
    return confirmBox(title || '登录后可用', msg || '登录红果账号后即可观看，收藏与进度与红果 App 同步。', '前往登录').then(function (ok) { if (ok) goLogin(); });
  }
  function showLogin(then) {
    if (GUEST) return needLogin();
    var host = $('#auth'); if (closeLoginFn) closeLoginFn(); closeSearch();
    closeLoginFn = LV.mountLogin(host, async function () {
      host.innerHTML = ''; closeLoginFn = null;
      var previousUID = me.profile && me.profile.uid;
      me = await api('/api/me');
      if (previousUID !== (me.profile && me.profile.uid)) { hist = []; recentPlayback.clear(); historyLoaded = false; ++historyRequest; }
      LV.loggedIn = !!me.loggedIn; LV.dropCache(); renderAcct(); loadHistory();
      if (then) then(); else route();
    }, function () { closeLoginFn = null; });
  }
  // 网页版账号（认证中心 + 邀请制）：管理员可从菜单 / 设置进入邀请码管理
  var SITE = {};
  async function boot() {
    fetch('/api/invite/me', { credentials: 'same-origin' }).then(function (r) { return r.ok ? r.json() : {}; }).then(function (j) { SITE = j || {}; }).catch(function () {});
    LV.onAuthLost = function () { me = { loggedIn: false }; LV.loggedIn = false; hist = []; recentPlayback.clear(); historyLoaded = false; ++historyRequest; persist('me', me, 7 * 86400e3); persist('hist', [], 7 * 86400e3); renderAcct(); LV.toast('登录已过期，请重新登录'); };
    var cachedMe = persist('me', undefined, 7 * 86400e3), routedEarly = false;
    if (cachedMe && (cachedMe.loggedIn || cachedMe.guest) && !cachedMe.browseOnly) {
      me = cachedMe; GUEST = LV.guest = !!me.guest; BROWSE_ONLY = LV.browseOnly = false; LV.loggedIn = !!me.loggedIn;
      document.body.classList.toggle('guest', GUEST);
      var cachedHist = persist('hist', undefined, 7 * 86400e3);
      if (Array.isArray(cachedHist) && cachedHist.length) {
        hist = mergeRecentHistory(cachedHist);
        remember(hist.map(function (c) { return { id: c.seriesId, title: c.title, cover: c.cover, episodes: c.episodeCount }; }));
      }
      renderAcct();
      routedEarly = true;
      route();
    } else {
      renderAcct();
    }
    var prevLogged = !!me.loggedIn, prevGuest = !!me.guest, prevBrowse = !!me.browseOnly;
    try { me = await api('/api/me'); } catch (e) { me = { loggedIn: false }; }
    persist('me', me, 7 * 86400e3);
    GUEST = LV.guest = !!me.guest; BROWSE_ONLY = LV.browseOnly = !!me.browseOnly; browseStorageKey='lvguo.browse.history.'+(me.guestKey||''); document.body.classList.toggle('guest', GUEST);
    if (BROWSE_ONLY && !(await browseCachePresent())) {
      try { localStorage.removeItem(browseStorageKey); localStorage.removeItem('lvguo.browse.proof'); } catch(e) {}
      try { await api('/api/invite/guest/clear',{body:{}}); } catch(e) {}
      location.replace('/'); return;
    }
    LV.loggedIn = !!me.loggedIn; renderAcct();
    if (!LV.loggedIn && !GUEST) { hist = []; persist('hist', [], 7 * 86400e3); }
    if (me.loggedIn && me.hasGuest) api('/api/guest/import', {body:{}}).then(function () { loadHistory(true); }).catch(function () { LV.toast('临时观看记录合并未完成，稍后会重试'); });
    if ((me.loggedIn || GUEST) && !isHomePath()) loadHistory();
    setInterval(function () {
      if (!document.hidden && (GUEST || BROWSE_ONLY || LV.loggedIn)) {
        fetch('/api/ping', { credentials: 'same-origin' }).catch(function () {});
      }
    }, 60000);
    if (!routedEarly || prevLogged !== !!me.loggedIn || prevGuest !== !!me.guest || prevBrowse !== !!me.browseOnly) {
      route();
    }
  }
  window.history.scrollRestoration = 'manual';
  boot();
})();
