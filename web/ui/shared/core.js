/* 绿果TV 前端共享层：接口、设置、主题、提示、卡片、弹幕、进度上报、扫码登录。PC / 手机共用。 */
(function () {
  var LV = (window.LV = {});
  var $ = (s, r) => (r || document).querySelector(s);
  LV.$ = $;
  LV.$$ = (s, r) => Array.from((r || document).querySelectorAll(s));
  LV.esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  // ------------------------------------------------------------ 设置与主题
  var DEF = { theme: 'auto', quality: '1080p', autonext: true, danmaku: true, dmOpacity: 0.85, dmSpeed: 1, dmSize: 1, dmArea: 0.5, keepRate: false, rate: 1, volume: 1, muted: false };
  var store = {};
  try { store = JSON.parse(localStorage.getItem('lvguo.settings') || '{}'); } catch (e) {}
  // 旧版卡顿时会把「默认清晰度」悄悄改成 720p 并存下来，之后一直拿不到 1080p：清掉一次，回到默认（1080p）。之后只有用户自己选的才保存。
  if (store.quality && !store.qv) { delete store.quality; store.qv = 2; try { localStorage.setItem('lvguo.settings', JSON.stringify(store)); } catch (e) {} }
  LV.set = function (k, v) {
    store[k] = v; if (k === 'quality') store.qv = 2;
    try { localStorage.setItem('lvguo.settings', JSON.stringify(store)); } catch (e) {}
    if (k === 'theme') applyTheme();
  };
  LV.get = (k) => (store[k] === undefined ? DEF[k] : store[k]);
  function applyTheme() {
    var m = LV.get('theme');
    var dark = m === 'dark' || (m === 'auto' && matchMedia('(prefers-color-scheme: dark)').matches);
    document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
    var meta = $('meta[name=theme-color]');
    if (meta) meta.content = dark ? '#0d1110' : '#f2f2f7';
  }
  applyTheme();
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', applyTheme);

  // ------------------------------------------------------------ 接口
  LV.loggedIn = false;
  // 短时缓存：返回、切换标签、悬停预取时立刻有内容（GET 才缓存；任何写操作会清空）
  var memo = new Map();
  LV.dropCache = () => memo.clear();
  LV.api = function (path, opt) {
    opt = opt || {};
    if (opt.body) memo.clear();
    if (opt.body || !opt.ttl) return rawApi(path, opt);
    var key = path + '?' + JSON.stringify(opt.query || {}), hit = memo.get(key);
    if (hit && Date.now() - hit.t < opt.ttl) return hit.p;
    var p = rawApi(path, opt); memo.set(key, { t: Date.now(), p: p });
    p.catch(() => memo.delete(key));
    return p;
  };
  async function rawApi(path, opt) {
    var url = path;
    if (opt.query) {
      var qs = new URLSearchParams();
      for (var k in opt.query) if (opt.query[k] !== undefined && opt.query[k] !== '') [].concat(opt.query[k]).forEach((v) => qs.append(k, v));
      url += '?' + qs;
    }
    var init = { credentials: 'same-origin', keepalive: !!opt.keepalive };
    if (opt.body) { init.method = 'POST'; init.headers = { 'Content-Type': 'application/json' }; init.body = JSON.stringify(opt.body); }
    var res;
    try { res = await fetch(url, init); } catch (e) { throw new Error('网络连接失败，请检查网络'); }
    var data = null;
    try { data = await res.json(); } catch (e) {}
    if (!res.ok) {
      var err = new Error((data && data.error) || '请求失败（' + res.status + '）');
      err.status = res.status;
      // 邀请制：访问资格失效（退出了红果 / 换号 / 邀请码被作废）→ 回到邀请页
      if (res.status === 401 && data && data.error === 'invite') { if (LV.guest) { err.message = data.message || '登录后可用'; throw err; } location.replace('/'); throw err; }
      if (res.status === 401 && LV.onAuthLost) LV.onAuthLost();
      throw err;
    }
    if (opt.body && (path === '/api/favorite' || path === '/api/reservation')) {
      window.dispatchEvent(new CustomEvent('lv:library-changed', { detail: path === '/api/favorite' ? 'fav' : 'reserve' }));
    }
    return data;
  }

  // ------------------------------------------------------------ 提示
  var toastT;
  LV.toast = function (msg) {
    var t = $('#toast');
    if (!t) { t = document.createElement('div'); t.id = 'toast'; document.body.appendChild(t); }
    t.textContent = msg; void t.offsetWidth; t.classList.add('on');
    clearTimeout(toastT); toastT = setTimeout(() => t.classList.remove('on'), 2600);
  };

  // ------------------------------------------------------------ 格式化
  LV.plays = function (n) {
    n = +n || 0;
    if (n >= 1e8) return (n / 1e8).toFixed(1) + '亿次播放';
    if (n >= 1e4) return (n / 1e4).toFixed(1) + '万次播放';
    return n ? n + '次播放' : '';
  };
  LV.time = function (s) {
    s = Math.max(0, Math.floor(s || 0));
    var h = (s / 3600) | 0, m = ((s % 3600) / 60) | 0, x = s % 60;
    return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(x).padStart(2, '0');
  };
  LV.ago = function (ms) {
    var d = (Date.now() - ms) / 1000;
    if (d < 60) return '刚刚';
    if (d < 3600) return ((d / 60) | 0) + ' 分钟前';
    if (d < 86400) return ((d / 3600) | 0) + ' 小时前';
    if (d < 86400 * 30) return ((d / 86400) | 0) + ' 天前';
    return new Date(ms).toLocaleDateString('zh-CN');
  };
  LV.day = function (ms) { var d = new Date(ms); return (d.getMonth() + 1) + '月' + d.getDate() + '日'; };

  // ------------------------------------------------------------ 卡片
  /** 封面 CDN 支持按宽度缩放：列表用小图，大图场景再放大 */
  LV.sized = (u, w) => (u && u.indexOf('~tplv-noop.jpeg') > 0 ? u.replace('~tplv-noop.jpeg', '~tplv-shrink:' + w + ':0.jpeg') : u);
  /** 图片占位：进入视口前不发请求；先取 32px 缩略图模糊垫底，再淡入清晰图。eager=true 立即加载 */
  LV.img = (u, alt, w, eager) => u ? '<span class="im" role="img" aria-label="' + LV.esc(alt || '') + '" data-s="' + LV.esc(LV.sized(u, w || 420)) + '" data-q="' + (u.indexOf('~tplv-noop.jpeg') > 0 ? LV.esc(LV.sized(u, 32)) : '') + '"' + (eager ? ' data-e="1"' : '') + '></span>' : '';
  var lzIO = 'IntersectionObserver' in window ? new IntersectionObserver((es) => {
    es.forEach((e) => {
      if (!e.isIntersecting) return;
      lzIO.unobserve(e.target); lzLoad(e.target);
      // 横向货架：屏幕外的卡片被滚动容器裁掉，不会触发；一排里任一张可见就整排预加载，横向滑动时不再一张张闪出来
      var row = e.target.closest('.row,.fchips');
      if (row) row.querySelectorAll('.im:not([data-d])').forEach((n) => { lzIO.unobserve(n); lzLoad(n); });
    });
  }, { rootMargin: '700px 200px' }) : null;
  function lzLoad(el) {
    if (el.dataset.d) return; el.dataset.d = '1';
    var q = el.dataset.q, s = el.dataset.s, lq;
    if (q) { lq = document.createElement('i'); lq.className = 'lq'; lq.style.backgroundImage = 'url("' + q + '")'; el.appendChild(lq); var t = new Image(); t.onload = () => lq.classList.add('on'); t.src = q; }
    var img = new Image(); img.alt = ''; img.decoding = 'async'; img.referrerPolicy = 'no-referrer';
    if (el.dataset.e) img.fetchPriority = 'high';
    img.onload = () => { el.appendChild(img); requestAnimationFrame(() => { img.classList.add('on'); setTimeout(() => lq && lq.remove(), 700); }); };
    img.onerror = () => { el.classList.add('fail'); if (lq) lq.remove(); };
    img.src = s;
  }
  function lzScan(root) {
    if (!root.querySelectorAll) return;
    var list = root.matches && root.matches('.im:not([data-d])') ? [root] : [];
    root.querySelectorAll('.im:not([data-d])').forEach((n) => list.push(n));
    list.forEach((n) => (lzIO && !n.dataset.e ? lzIO.observe(n) : lzLoad(n)));
  }
  new MutationObserver((ms) => ms.forEach((m) => m.addedNodes.forEach((n) => n.nodeType === 1 && lzScan(n)))).observe(document.documentElement, { childList: true, subtree: true });
  LV.card = function (s, o) {
    o = o || {};
    var id = s.id || s.seriesId;
    var rank = o.rank ? '<span class="rank' + (o.rank <= 3 ? ' top' : '') + '">' + o.rank + '</span>' : '';
    var eps = s.episodes || s.episodeCount;
    var badge = o.badge || (eps ? eps + ' 集' : '');
    var bar = o.progress > 0 ? '<span class="bar"><i style="width:' + Math.min(100, o.progress * 100) + '%"></i></span>' : '';
    var sub = o.sub !== undefined ? o.sub : (s.category || '').split(',').slice(0, 2).join(' · ');
    return '<button class="card' + (o.i != null ? ' rise' : '') + '" style="--i:' + Math.min(o.i || 0, 18) + '" data-id="' + id + '"><div class="poster">' + LV.img(s.cover, s.title) + rank +
      (badge ? '<span class="badge">' + LV.esc(badge) + '</span>' : '') + bar + '</div><div class="name">' + LV.esc(s.title) + '</div>' +
      (sub ? '<div class="sub">' + LV.esc(sub) + '</div>' : '') + '</button>';
  };
  /** 与真实卡片同结构的骨架，加载前后不跳动 */
  LV.skeletons = function (n) { return Array.from({ length: n }, () => '<div class="card sk"><div class="poster skel"></div><div class="name skel t m"></div><div class="sub skel t s"></div></div>').join(''); };
  LV.skelRows = function (n) { return Array.from({ length: n }, () => '<div class="rrow sk"><div class="skel" style="width:20px;height:20px;border-radius:6px"></div><div class="rp skel"></div><div class="ri"><div class="skel t m"></div><div class="skel t s"></div></div></div>').join(''); };

  /** 无限滚动：哨兵进入视口就调用 more()，返回 false 表示没有更多。
   *  opt.host + opt.skel(n)：加载期间把骨架直接补进网格末尾（补齐最后一行再多一行），数据到了原位替换，不留空档 */
  LV.infinite = function (sentinel, more, opt) {
    opt = opt || {};
    var busy = false, done = false;
    function cols(h) { var t = getComputedStyle(h).gridTemplateColumns; return t && t !== 'none' ? t.split(' ').length : 1; }
    function tail() {
      var h = opt.host; if (!h || !opt.skel || h.querySelector(':scope > .sk:not(.sk-tail)')) return;
      var n = h.querySelectorAll(':scope > :not(.sk-tail)').length, c = cols(h), k = (c - n % c) % c + c;
      var t = document.createElement('template'); t.innerHTML = opt.skel(k);
      Array.from(t.content.children).forEach((x) => x.classList.add('sk-tail')); h.appendChild(t.content);
    }
    function untail() { if (opt.host) opt.host.querySelectorAll(':scope > .sk-tail').forEach((x) => x.remove()); }
    var io = new IntersectionObserver(async (es) => {
      if (!es[0].isIntersecting || busy || done) return;
      busy = true; tail();
      try {
        var ok = await more(); untail();
        if (!ok) { done = true; io.disconnect(); sentinel.hidden = true; return; }
        // 哨兵可能仍在视口内（内容还没把页面撑满）：重新观察以便立刻续载
        busy = false; io.unobserve(sentinel); io.observe(sentinel);
      } catch (e) { untail(); LV.toast(e.message); done = true; io.disconnect(); sentinel.hidden = true; } finally { busy = false; }
    }, { rootMargin: '900px 0px' });
    io.observe(sentinel);
    return { stop: () => { done = true; io.disconnect(); untail(); }, poke: () => { if (!busy && !done) { io.unobserve(sentinel); io.observe(sentinel); } } };
  };

  // ------------------------------------------------------------ 评论/弹幕表情：[笑哭] → 表情图（与 TV 端同一套资源）
  var EMOJI = {"微笑": 1, "偷笑": 2, "笑": 3, "什么": 4, "害羞": 5, "爱慕": 6, "飞吻": 7, "奸笑": 8, "尬笑": 9, "思考": 10, "撇嘴": 11, "做鬼脸": 12, "酷": 13, "翻白眼": 14, "惊呆": 15, "震惊": 16, "送心": 17, "委屈": 18, "快哭了": 19, "笑哭": 20, "哭": 21, "大笑": 22, "舔屏": 23, "怒": 24, "捂脸": 25, "吐": 26, "恐惧": 27, "抓狂": 28, "敬礼": 29, "石化": 30, "OK": 31, "赞": 32, "爱心": 33, "伤心": 34, "KISS": 35, "懂了": 36, "探究": 37, "重拳出击": 38, "吃瓜": 39, "盯": 40, "你细品": 41, "赶稿中": 42, "注意": 43, "饱了": 44, "码住": 45, "学会了": 46, "顶帖": 47, "求爆更": 48, "求关注": 49, "爽": 50, "鼓掌": 51, "没看够": 52, "送花": 53};
  LV.emojiHTML = function (text) {
    return LV.esc(text || '').replace(/\[([^\[\]\r\n]{1,8})\]/g, function (all, name) {
      var n = EMOJI[name]; return n ? '<img class="emo" src="/shared/emoji/e' + n + '.webp" alt="[' + name + ']" draggable="false">' : all;
    });
  };

  // ------------------------------------------------------------ 弹幕（取自本集真实评论，按集时长铺开）
  LV.Danmaku = function (layer) {
    this.raw = [];
    var items = [], next = 0, lanes = [], live = [], playing = false, cfg = { on: true, op: 0.85, speed: 1, size: 1, area: 1 };
    var self = this;
    this.config = (c) => { Object.assign(cfg, c); layer.style.opacity = cfg.on ? cfg.op : 0; layer.style.visibility = cfg.on ? 'visible' : 'hidden'; };
    this.load = function (list, duration) {
      this.clear();
      list = (list || []).filter((x) => x.text && x.text.length <= 40);
      var d = Math.max(30, duration || 120);
      items = list.map((x, i) => ({ t: (i + 0.5) * d / Math.max(1, list.length) * 0.96, text: x.text, avatar: x.avatar }));
    };
    this.clear = function () { items = []; next = 0; live.forEach((a) => { a.el.remove(); }); live = []; lanes = []; layer.innerHTML = ''; };
    this.seek = function (t) { next = items.findIndex((x) => x.t >= t); if (next < 0) next = items.length; live.forEach((a) => a.el.remove()); live = []; lanes = []; };
    this.setPlaying = function (p) { playing = p; live.forEach((a) => (p ? a.anim.play() : a.anim.pause())); };
    this.tick = function (t) {
      if (!cfg.on || !playing) return;
      while (next < items.length && items[next].t <= t) { if (t - items[next].t < 3) emit(items[next]); next++; }
    };
    function emit(it) {
      var W = layer.clientWidth, H = layer.clientHeight;
      if (!W || !H) return;
      var lh = Math.round(34 * cfg.size), n = Math.max(1, Math.floor(H * (cfg.area || 1) / lh)), now = performance.now();
      var lane = -1;
      for (var i = 0; i < n; i++) if (!lanes[i] || lanes[i] < now) { lane = i; break; }
      if (lane < 0) return;
      var el = document.createElement('div');
      el.className = 'dm';
      el.style.cssText = 'top:' + (lane * lh + 6) + 'px;font-size:' + Math.round(20 * cfg.size) + 'px';
      el.innerHTML = LV.emojiHTML(it.text);   // 表情码渲染成图片，不再是一串 [xx]
      layer.appendChild(el);
      var w = el.offsetWidth, dist = W + w, dur = 9000 / cfg.speed * (0.8 + dist / 1800);
      lanes[lane] = now + (w + 40) / dist * dur;
      var anim = el.animate([{ transform: 'translateX(' + W + 'px)' }, { transform: 'translateX(-' + w + 'px)' }], { duration: dur, easing: 'linear' });
      var rec = { el: el, anim: anim };
      live.push(rec);
      anim.onfinish = () => { el.remove(); live = live.filter((x) => x !== rec); };
    }
    /** 拉取本集评论（最多 5 页）作为弹幕 */
    this.fetch = async function (seriesId, episodeVid, duration) {
      var all = [];
      for (var p = 0; p < 5; p++) {
        try {
          var r = await LV.api('/api/comments', { query: { id: seriesId, ep: episodeVid, offset: p * 20 } });
          if (!r.items || !r.items.length) break;
          all = all.concat(r.items);
        } catch (e) { break; }
      }
      self.raw = all;
      self.load(all, duration);
      return all.length;
    };
  };

  // ------------------------------------------------------------ 播放进度上报
  // 只写本站的本地播放记录库（服务端再由后台上传器批量交给红果），不直接触达上游。
  // 播放中每 15 秒记一次位置；暂停 / 切集 / 关闭时带 final，服务端几秒后批量上传。
  // 任何情况下两次发送至少隔 3 秒，位置没变不发——防止出错重试时变成每秒几次的风暴。
  LV.Reporter = function () {
    var cur = null, timer = 0, last = -1, sentAt = 0, pend = 0, queue = Promise.resolve();
    function send(final) {
      if (!cur || (!LV.loggedIn && !LV.guest)) return queue;
      var pos = Math.round(cur.pos() * 1000);
      if (!Number.isFinite(pos)) return queue;
      if (Math.abs(pos - last) < 1000 && !(final && !cur.finalSent)) return queue;
      var now = Date.now(), wait = 3000 - (now - sentAt);
      if (wait > 0) { clearTimeout(pend); var c = cur; pend = setTimeout(function () { if (cur === c) send(final); }, wait); return queue; }
      last = pos; sentAt = now; cur.finalSent = !!final;
      var body = { id: cur.id, vid: cur.vid, index: cur.index, pos: Math.max(0, pos), final: !!final };
      if (LV.guest && cur.meta) { body.title=cur.meta.title; body.cover=cur.meta.cover; body.episodeCount=cur.meta.episodeCount; }
	  if (LV.browseOnly) { window.dispatchEvent(new CustomEvent('lv:browse-progress',{detail:body})); return queue; }
      // Keep reports in viewing order, even when a previous request is slow.
      queue = queue.catch(function () {}).then(function () { return LV.api('/api/progress', { body: body, keepalive: true }); });
      queue.catch(function () {});
      return queue;
    }
    this.start = function (id, vid, index, pos, meta) { this.stop(); cur = { id: id, vid: vid, index: index, pos: pos, meta:meta }; last = -1; send(false); timer = setInterval(() => send(false), 15000); };
    this.flush = () => send(true);
    this.stop = function () { clearInterval(timer); clearTimeout(pend); var saved = queue; if (cur) { sentAt = 0; saved = send(true); } cur = null; return saved; };
    var self = this;
    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') self.flush(); });
    window.addEventListener('pagehide', () => self.flush());
  };

  // ------------------------------------------------------------ 扫码登录
  LV.mountLogin = function (host, onDone, onClose) {
    host.innerHTML = '<div class="login"><div class="panel">' + (onClose ? '<button class="lclose" aria-label="关闭">' + ic('close') + '</button>' : '') + '<img class="logo" src="/shared/logo.png" alt="">' +
      '<h1>绿果WEB</h1><p>用手机红果 App 扫码登录<br>收藏与观看进度自动同步</p><div class="qr"><div class="qr-skeleton" role="status" aria-label="正在生成二维码"><svg viewBox="0 0 160 160" aria-hidden="true"><path fill-rule="evenodd" d="M12 12h42v42H12zM19 19v28h28V19zM106 12h42v42h-42zM113 19v28h28V19zM12 106h42v42H12zM19 113v28h28v-28z"/><path d="M26 26h14v14H26zM120 26h14v14h-14zM26 120h14v14H26zM66 12h8v8h-8zM80 12h8v8h-8zM66 26h8v8h-8zM80 40h8v8h-8zM66 54h8v8h-8zM94 54h8v8h-8zM66 68h8v8h-8zM80 68h8v8h-8zM94 68h8v8h-8zM108 68h8v8h-8zM122 68h8v8h-8zM136 68h8v8h-8zM66 82h8v8h-8zM94 82h8v8h-8zM122 82h8v8h-8zM66 96h8v8h-8zM80 96h8v8h-8zM94 96h8v8h-8zM108 96h8v8h-8zM136 96h8v8h-8zM66 110h8v8h-8zM80 124h8v8h-8zM94 110h8v8h-8zM108 124h8v8h-8zM122 110h8v8h-8zM136 124h8v8h-8zM66 138h8v8h-8zM94 138h8v8h-8zM108 138h8v8h-8zM136 138h8v8h-8z"/></svg></div><img alt="" hidden><div class="veil"></div></div>' +
      '<div class="hint"></div><div class="qr-meta"><div class="count" aria-live="polite">正在生成二维码</div><button class="qr-refresh" type="button" aria-label="刷新二维码">刷新二维码</button></div></div></div>';
    var img = $('.qr img', host), skeleton = $('.qr-skeleton', host), veil = $('.veil', host), hint = $('.hint', host), count = $('.count', host), refresh = $('.qr-refresh', host);
    var alive = true, expires = 0, polling = 0, tick = 0, failures = 0, generation = 0;
    async function start() {
      var gen = ++generation;
      clearTimeout(polling); clearInterval(tick);
      veil.className = 'veil'; veil.textContent = ''; veil.onclick = null;
      skeleton.hidden = false; img.hidden = true; img.removeAttribute('src');
      refresh.disabled = true; count.textContent = '正在生成二维码';
      hint.textContent = ''; hint.className = 'hint';
      try {
        var r = await LV.api('/api/login/start', { body: {} });
        if (!alive || gen !== generation) return;
        expires = r.expiresAt; failures = 0;
        img.onload = function () {
          if (!alive || gen !== generation) return;
          img.hidden = false; skeleton.hidden = true; refresh.disabled = false;
        };
        img.onerror = function () {
          if (!alive || gen !== generation) return;
          refresh.disabled = false; veil.textContent = '二维码加载失败，请刷新重试'; veil.className = 'veil on';
          veil.onclick = start;
        };
        img.src = r.qr;
        if (img.complete && img.naturalWidth > 0) img.onload();
        tick = setInterval(() => {
          var left = Math.round((expires - Date.now()) / 1000);
          count.textContent = left > 0 ? '二维码 ' + left + ' 秒后失效' : '';
          if (left <= -1) start();
        }, 500);
        poll(gen);
      } catch (e) {
        if (!alive || gen !== generation) return;
        refresh.disabled = false; veil.textContent = '二维码获取失败，请刷新重试'; veil.className = 'veil on'; veil.onclick = start;
      }
    }
    async function poll(gen) {
      if (!alive || gen !== generation) return;
      try {
        var r = await LV.api('/api/login/poll');
        if (!alive || gen !== generation) return;
        if (r.state === 'confirmed') { hint.className = 'hint ok'; hint.textContent = '登录成功'; alive = false; clearInterval(tick); setTimeout(onDone, 350); return; }
        if (r.state === 'scanned') { hint.className = 'hint ok'; hint.textContent = '已扫码，请在手机上确认'; }
        else if (r.state === 'canceled') { hint.textContent = '已取消，正在刷新二维码'; return start(); }
        else if (r.state === 'expired') return start();
        else hint.textContent = '';
        failures = 0;
      } catch (e) { if (++failures > 4) { hint.textContent = e.message; return start(); } }
      if (alive && gen === generation) polling = setTimeout(() => poll(gen), 1600);
    }
    refresh.onclick = start;
    var stop = () => { alive = false; generation++; img.onload = img.onerror = null; clearTimeout(polling); clearInterval(tick); };
    var cb = $('.lclose', host);
    var close = () => { var l = $('.login', host); stop(); if (!l) return onClose(); l.classList.add('out'); setTimeout(() => { host.innerHTML = ''; }, 260); onClose(); };
    if (cb) cb.onclick = close;
    var lg = $('.login', host);
    if (onClose && lg) { lg.addEventListener('mousedown', (e) => { if (e.target === lg) close(); }); var esc = (e) => { if (e.key === 'Escape' && host.firstChild) { close(); document.removeEventListener('keydown', esc); } }; document.addEventListener('keydown', esc); }
    start();
    return stop;
  };

  // ------------------------------------------------------------ 杂项
  LV.group = function (n, size) { var g = []; for (var i = 0; i < n; i += size) g.push([i + 1, Math.min(n, i + size)]); return g; };
  LV.debounce = (fn, ms) => { var t; return function () { var a = arguments, s = this; clearTimeout(t); t = setTimeout(() => fn.apply(s, a), ms); }; };
  // 页面转场：支持 View Transitions 的浏览器用共享元素过渡，其余直接切换
  LV.vt = function (fn, name) {
    if (!document.startViewTransition) return fn();
    return document.startViewTransition(fn);
  };
})();
