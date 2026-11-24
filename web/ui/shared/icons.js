/* 图标：2px 圆角描边，与 TV 版同一风格。用 <svg class="icon"><use href="#i-xxx"/></svg> 引用。 */
(function () {
  var P = {
    home: '<path d="M3 11.2 12 3.5l9 7.7"/><path d="M5.5 9.8V20a1 1 0 0 0 1 1H10v-5.5h4V21h3.5a1 1 0 0 0 1-1V9.8"/>',
    chart: '<rect x="3.5" y="11" width="4.5" height="10" rx="1.4"/><rect x="9.75" y="3" width="4.5" height="18" rx="1.4"/><rect x="16" y="8" width="4.5" height="13" rx="1.4"/>',
    grid: '<rect x="3.5" y="3.5" width="7.5" height="7.5" rx="2.2"/><rect x="13" y="3.5" width="7.5" height="7.5" rx="2.2"/><rect x="3.5" y="13" width="7.5" height="7.5" rx="2.2"/><rect x="13" y="13" width="7.5" height="7.5" rx="2.2"/>',
    user: '<circle cx="12" cy="8" r="4.2"/><path d="M4 21c.6-4.2 3.7-6.5 8-6.5s7.4 2.3 8 6.5"/>',
    gear: '<circle cx="12" cy="12" r="3.2"/><path d="M19.4 14.6a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-1.8-.3 1.6 1.6 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.6 1.6 0 0 0-1-1.5 1.6 1.6 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.6 1.6 0 0 0 .3-1.8 1.6 1.6 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.6 1.6 0 0 0 1.5-1 1.6 1.6 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.6 1.6 0 0 0 1.8.3h0a1.6 1.6 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 1 1.5 1.6 1.6 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0-.3 1.8v0a1.6 1.6 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1z"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="m20.5 20.5-4.2-4.2"/>',
    play: '<path class="f" d="M7.5 4.6v14.8a1 1 0 0 0 1.5.9l12-7.4a1 1 0 0 0 0-1.7L9 3.7a1 1 0 0 0-1.5.9z"/>',
    pause: '<rect class="f" x="5.5" y="4" width="4.6" height="16" rx="1.6"/><rect class="f" x="13.9" y="4" width="4.6" height="16" rx="1.6"/>',
    next: '<path class="f" d="M5 5.2v13.6a.9.9 0 0 0 1.4.8l9.2-6.8a.9.9 0 0 0 0-1.6L6.4 4.4A.9.9 0 0 0 5 5.2z"/><rect class="f" x="17" y="4.5" width="2.8" height="15" rx="1.2"/>',
    prev: '<path class="f" d="M19 5.2v13.6a.9.9 0 0 1-1.4.8L8.4 12.8a.9.9 0 0 1 0-1.6l9.2-6.8A.9.9 0 0 1 19 5.2z"/><rect class="f" x="4.2" y="4.5" width="2.8" height="15" rx="1.2"/>',
    heart: '<path d="M12 20.5s-8-4.7-8-10.6A4.6 4.6 0 0 1 12 7a4.6 4.6 0 0 1 8 2.9c0 5.9-8 10.6-8 10.6z"/>',
    heartfill: '<path class="f" d="M12 20.5s-8-4.7-8-10.6A4.6 4.6 0 0 1 12 7a4.6 4.6 0 0 1 8 2.9c0 5.9-8 10.6-8 10.6z"/>',
    full: '<path d="M4 9V5a1 1 0 0 1 1-1h4M20 9V5a1 1 0 0 0-1-1h-4M4 15v4a1 1 0 0 0 1 1h4M20 15v4a1 1 0 0 1-1 1h-4"/>',
    unfull: '<path d="M9 4v4a1 1 0 0 1-1 1H4M15 4v4a1 1 0 0 0 1 1h4M9 20v-4a1 1 0 0 0-1-1H4M15 20v-4a1 1 0 0 1 1-1h4"/>',
    volume: '<path d="M4 9.5v5h3.5L12 18.5v-13L7.5 9.5z"/><path d="M15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11"/>',
    mute: '<path d="M4 9.5v5h3.5L12 18.5v-13L7.5 9.5z"/><path d="m16 9.5 5 5m0-5-5 5"/>',
    back: '<path d="m14.5 5-7 7 7 7"/>',
    chev: '<path d="m9 5 7 7-7 7"/>',
    close: '<path d="M6 6l12 12M18 6 6 18"/>',
    bell: '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.7 21a2 2 0 0 1-3.4 0"/>',
    list: '<path d="M8 6.5h12M8 12h12M8 17.5h12"/><circle cx="4" cy="6.5" r="1"/><circle cx="4" cy="12" r="1"/><circle cx="4" cy="17.5" r="1"/>',
    speed: '<path d="M12 4a9 9 0 1 0 9 9"/><path d="m12 13 5.5-5.5"/><path d="M21 4v4h-4" opacity="0"/>',
    danmaku: '<rect x="3" y="4.5" width="18" height="12.5" rx="3.5"/><path d="M8 20.5l3-3.5"/><path d="M7.5 9h6M7.5 12.5h9"/>',
    check: '<path d="m5 12.5 4.5 4.5L19 7.5"/>',
    trash: '<path d="M4.5 7h15M9.5 7V4.5h5V7M6.5 7l.8 12.2a1.5 1.5 0 0 0 1.5 1.3h6.4a1.5 1.5 0 0 0 1.5-1.3L17.5 7"/>',
    logout: '<path d="M14 4h4a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-4M10 8l-4 4 4 4M6 12h10"/>',
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2.5v2.2M12 19.3v2.2M2.5 12h2.2M19.3 12h2.2M5.3 5.3l1.6 1.6M17.1 17.1l1.6 1.6M5.3 18.7l1.6-1.6M17.1 6.9l1.6-1.6"/>',
    moon: '<path d="M20 14.5A8.5 8.5 0 0 1 9.5 4 8.5 8.5 0 1 0 20 14.5z"/>',
    auto: '<circle cx="12" cy="12" r="8.5"/><path class="f" d="M12 3.5a8.5 8.5 0 0 1 0 17z"/>',
    refresh: '<path d="M20 5v5h-5M4 19v-5h5"/><path d="M5.6 9A7.5 7.5 0 0 1 19 10M18.4 15A7.5 7.5 0 0 1 5 14"/>',
    rew: '<path d="M4 12a8 8 0 1 0 2.4-5.7L4 8.5"/><path d="M4 4v4.5h4.5"/><path d="M10 9.5v5M14.2 9.5h-2.4v2.4h1.6a1.2 1.2 0 0 1 0 2.4h-1.6" opacity=".0"/>',
    fwd: '<path d="M20 12a8 8 0 1 1-2.4-5.7L20 8.5"/><path d="M20 4v4.5h-4.5"/>',
    play2: '<path class="f" d="M8 5.2v13.6a.8.8 0 0 0 1.2.7l10.4-6.8a.8.8 0 0 0 0-1.4L9.2 4.5A.8.8 0 0 0 8 5.2z"/>',
    star: '<path class="f" d="m12 3.2 2.7 5.5 6 .9-4.4 4.2 1 6-5.3-2.8-5.4 2.8 1-6L3.3 9.6l6-.9z"/>',
    remote: '<rect x="7" y="2.5" width="10" height="19" rx="3"/><circle cx="12" cy="8" r="1.6"/><path d="M10 13h4M10 16.5h4"/>',
    film: '<rect x="3.5" y="4.5" width="17" height="15" rx="3.2"/><path d="M8.5 4.5v15M15.5 4.5v15M3.5 9.5h5M3.5 14.5h5M15.5 9.5h5M15.5 14.5h5"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    library: '<path d="M5 4.5v15M10 4.5v15"/><path d="m14.4 5.6 4.4-1.1 3.3 14.6-4.4 1.1z" transform="translate(-1.6 0)"/>',
    sliders: '<path d="M4 7h9M17 7h3M4 17h3M11 17h9"/><circle cx="15" cy="7" r="2"/><circle cx="9" cy="17" r="2"/>',
    clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
    lock: '<rect x="5" y="11" width="14" height="10" rx="2.2"/><path d="M8 11V7.5a4 4 0 0 1 8 0V11"/>',
    unlock: '<rect x="5" y="11" width="14" height="10" rx="2.2"/><path d="M8 11V7.5a4 4 0 0 1 7.6-1.7"/>'
  };
  var s = '<svg xmlns="http://www.w3.org/2000/svg" style="display:none"><defs>';
  for (var k in P) s += '<symbol id="i-' + k + '" viewBox="0 0 24 24">' + P[k] + '</symbol>';
  s += '<style>.f{fill:currentColor;stroke:none}</style></defs></svg>';
  document.body ? document.body.insertAdjacentHTML('afterbegin', s) : document.addEventListener('DOMContentLoaded', function () { document.body.insertAdjacentHTML('afterbegin', s); });
  window.ic = function (n, cls) { return '<svg class="icon ' + (cls || '') + '" aria-hidden="true"><use href="#i-' + n + '"/></svg>'; };
})();
