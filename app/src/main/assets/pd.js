/*
 * PhoneDeck helper script (link mode).
 * Injected by DeckActivity into the PC page after it loads. It cannot see the page's source, so it works by
 * watching what is tapped / typed. Search for "ANCHOR:" to jump to a section:
 *   ANCHOR:JS_CONFIG        settings pushed from the app
 *   ANCHOR:JS_KEY_CLASSIFY  decides what a tapped element is (Shift, Space, a letter ...)
 *   ANCHOR:JS_SHIFT_SYNC    Shift / Caps state + small <-> CAPITAL key labels
 *   ANCHOR:JS_UI_TABS      pretty scrolling tabs + hide touchpad slider/Left/Right controls
 *   ANCHOR:JS_SUGBAR        suggestion strip drawn just above the keyboard's top key row
 *   ANCHOR:JS_SETTINGS      PhoneDeck section inside the page's own Settings panel
 *   ANCHOR:JS_SUGGEST       word tracking for the suggestion bar
 *   ANCHOR:JS_INSERT        typing a chosen suggestion back into the page
 *   ANCHOR:JS_DOWNLOADS     real file names + blob downloads
 *   ANCHOR:JS_PROBE         "Test key detection" report
 */
(function () {
  'use strict';
  if (window.__pdInit || !window.PDNative) return;
  window.__pdInit = true;
  var N = window.PDNative;
  function call(fn) { try { return fn(); } catch (e) { return undefined; } }

  // ==== ANCHOR:JS_CONFIG ====
  var cfg = { shiftLabels: true, symbols: true, tabs: true, hidePad: false, sugg: true, sugnext: true, learned: 0 };
  window.__pdConfig = function (o) {
    for (var k in o) { cfg[k] = o[k]; }
    applyCase();
    applyUi();
    renderBar();
    refreshSection();
  };

  var KEY_SEL = 'button,[role=button],[data-key],[data-k],kbd,.key,[class*=key],[class*=Key]';
  var TEXT_TYPES = { '': 1, text: 1, search: 1, url: 1, email: 1, tel: 1 };

  function isEditable(t) {
    return !!t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable === true);
  }
  function isTextual(t) {
    if (!t) return false;
    if (t.tagName === 'TEXTAREA' || t.isContentEditable === true) return true;
    if (t.tagName !== 'INPUT') return false;
    return !!TEXT_TYPES[(t.type || '').toLowerCase()];
  }
  function isSecret(t) { return !!t && t.tagName === 'INPUT' && (t.type || '').toLowerCase() === 'password'; }
  function visible(el) { return !!el && el.getClientRects().length > 0; }

  // ==== ANCHOR:JS_KEY_CLASSIFY ====
  function infoFromText(t) {
    t = (t || '').trim();
    if (!t) return null;
    if (t.length === 1) {
      if (t === '\u21E7') return 'Shift';
      if (t === '\u21EA') return 'Caps';
      if (t === '\u232B') return 'Backspace';
      if (t === '\u21B5' || t === '\u23CE') return 'Enter';
      if (t === '\u2423') return ' ';
      return t;
    }
    var l = t.toLowerCase();
    if (l === 'shift') return 'Shift';
    if (l === 'caps' || l === 'caps lock' || l === 'capslock') return 'Caps';
    if (l === 'backspace' || l === 'bksp' || l === 'bs') return 'Backspace';
    if (l === 'enter' || l === 'return') return 'Enter';
    if (l === 'space' || l === 'spacebar') return ' ';
    if (l === 'tab') return 'Tab';
    if (l === 'ctrl' || l === 'control' || l === 'alt' || l === 'win' || l === 'windows' ||
        l === 'meta' || l === 'cmd' || l === 'super' || l === 'fn') return 'Mod';
    return null;
  }
  function ownInfo(el) {
    var d = el.getAttribute('data-key') || el.getAttribute('data-k') || el.getAttribute('aria-label') || el.title || '';
    return infoFromText(d) || infoFromText(el.textContent);
  }
  function keyInfo(el) {
    for (var i = 0; i < 4 && el && el !== document.body && el.nodeType === 1; i++, el = el.parentElement) {
      var r = ownInfo(el);
      if (r) return r;
    }
    return null;
  }

  // innermost element of every key that shows exactly one character (the "label holder")
  function holders() {
    var set = new Set(), res = [];
    var els = document.querySelectorAll(KEY_SEL);
    for (var i = 0; i < els.length; i++) {
      var h = els[i];
      while (h.childElementCount === 1) h = h.firstElementChild;
      if (h.childElementCount !== 0 || set.has(h)) continue;
      var t = (h.textContent || '').trim();
      if (t.length !== 1) continue;
      set.add(h);
      res.push(h);
    }
    return res;
  }

  // ==== ANCHOR:JS_SHIFT_SYNC ====
  var SHIFT_MAP = { '`': '~', '1': '!', '2': '@', '3': '#', '4': '$', '5': '%', '6': '^', '7': '&', '8': '*',
    '9': '(', '0': ')', '-': '_', '=': '+', '[': '{', ']': '}', '\\': '|', ';': ':', "'": '"', ',': '<', '.': '>', '/': '?' };
  var shift = false, caps = false, shiftPtr = -1, shiftT0 = 0, lastShiftTap = 0, pendingReset = false;
  var applying = false, applyT = 0, mo = null, observing = false;

  function setLabel(h, want, old) {
    h.textContent = want;
    var el = h;
    for (var i = 0; i < 3 && el && el !== document.body; i++, el = el.parentElement) {
      var names = ['data-key', 'data-k', 'data-char', 'data-value', 'data-label'];
      for (var j = 0; j < names.length; j++) {
        var a = el.getAttribute(names[j]);
        if (a && a.length === 1 && (a === old || a.toLowerCase() === old.toLowerCase())) el.setAttribute(names[j], want);
      }
    }
  }
  function noTransform(h) {
    var el = h;
    for (var i = 0; i < 3 && el && el !== document.body; i++, el = el.parentElement) {
      if (el.style && el.style.textTransform !== 'none') el.style.textTransform = 'none';
    }
  }

  function applyCase() {
    if (!cfg.shiftLabels || applying) return;
    applying = true;
    try {
      var hs = holders(), letters = 0, i, t;
      for (i = 0; i < hs.length; i++) { if (/^[a-zA-Z]$/.test((hs[i].textContent || '').trim())) letters++; }
      if (letters >= 20) {                       // only act when the page really has a letter keyboard
        var up = (shift !== caps);
        for (i = 0; i < hs.length; i++) {
          var h = hs[i];
          t = (h.textContent || '').trim();
          if (/^[a-zA-Z]$/.test(t)) {
            var want = up ? t.toUpperCase() : t.toLowerCase();
            if (want !== t) setLabel(h, want, t);
            noTransform(h);                       // page CSS must not force capitals
          } else if (cfg.symbols) {
            var base = h.__pdBase;
            if (base !== undefined && t !== base && t !== SHIFT_MAP[base]) { base = undefined; delete h.__pdBase; }
            if (base === undefined && SHIFT_MAP[t] !== undefined) base = t;
            if (base !== undefined) {
              var w2 = shift ? SHIFT_MAP[base] : base;
              h.__pdBase = base;
              if (w2 !== t) setLabel(h, w2, t);
            }
          }
        }
      }
    } catch (e) {}
    try { if (mo) mo.takeRecords(); } catch (e) {}   // ignore the changes we just made
    applying = false;
  }
  function schedApply() { clearTimeout(applyT); applyT = setTimeout(function () { applyCase(); applyUi(); }, 40); }

  function attachObserver() {
    if (observing || !document.documentElement) return;
    try {
      mo = new MutationObserver(function () { if (!applying) schedApply(); });
      mo.observe(document.documentElement, { childList: true, subtree: true, characterData: true });
      observing = true;
    } catch (e) {}
  }
  attachObserver();
  document.addEventListener('DOMContentLoaded', function () { attachObserver(); applyCase(); });
  window.addEventListener('load', function () { attachObserver(); applyCase(); setTimeout(applyCase, 400); });
  applyCase();
  applyUi();


  // ==== ANCHOR:JS_UI_TABS ====
  // Pretty, horizontally scrolling tab bar + optional hiding of the touchpad tab's slider / Left / Right controls.
  var STYLE_ID = 'pd-style';
  var CSS =
    '[data-pdtabs]{display:flex!important;flex-wrap:nowrap!important;overflow-x:auto!important;overflow-y:hidden!important;' +
    '-webkit-overflow-scrolling:touch;scroll-snap-type:x proximity;scrollbar-width:none;gap:6px;padding:6px 8px!important;' +
    'justify-content:flex-start!important;touch-action:pan-x;overscroll-behavior-x:contain;box-sizing:border-box}' +
    '[data-pdtabs]::-webkit-scrollbar{display:none}' +
    '[data-pdtabs]>[data-pdtab]{flex:0 0 auto!important;width:auto!important;min-width:84px;white-space:nowrap!important;' +
    'padding:10px 18px!important;margin:0!important;border-radius:999px!important;scroll-snap-align:center;text-align:center;' +
    'background:rgba(255,255,255,.04);border:1px solid rgba(255,255,255,.07)!important;box-shadow:none!important;' +
    'transition:background .18s,color .18s,border-color .18s,transform .12s}' +
    '[data-pdtabs]>[data-pdtab]::before,[data-pdtabs]>[data-pdtab]::after{display:none!important}' +
    '[data-pdtabs]>[data-pdtab]:active{transform:scale(.95)}' +
    '[data-pdtabs]>[data-pdtab][aria-selected=true],[data-pdtabs]>[data-pdtab].active,[data-pdtabs]>[data-pdtab].on,' +
    '[data-pdtabs]>[data-pdtab].sel,[data-pdtabs]>[data-pdtab].selected,[data-pdtabs]>[data-pdtab][class*=active]{' +
    'background:rgba(77,163,255,.18)!important;border-color:rgba(77,163,255,.6)!important;color:#4DA3FF!important}' +
    '[data-pdhide]{display:none!important}';
  var TAB_RE = /^(keys|numpad|text|touchpad|files|clipboard)$/i;

  function ensureStyle() {
    if (document.getElementById(STYLE_ID) || !document.documentElement) return;
    var st = document.createElement('style');
    st.id = STYLE_ID;
    st.textContent = CSS;
    (document.head || document.documentElement).appendChild(st);
  }
  function plainText(el) { return (el.textContent || '').replace(/[^A-Za-z]+/g, ''); }

  function findTabBar() {
    var best = null, bestCount = 0, all = document.querySelectorAll('div,nav,ul,header,section');
    for (var i = 0; i < all.length; i++) {
      var kids = all[i].children;
      if (kids.length < 4 || kids.length > 12) continue;
      var c = 0;
      for (var j = 0; j < kids.length; j++) { if (TAB_RE.test(plainText(kids[j]))) c++; }
      if (c >= 4 && c >= bestCount) { best = all[i]; bestCount = c; }   // later (deeper) match wins ties
    }
    return best;
  }
  function activeTab(bar) {
    var k = bar.children;
    for (var i = 0; i < k.length; i++) {
      var cn = String(k[i].className && k[i].className.baseVal !== undefined ? k[i].className.baseVal : k[i].className);
      if (k[i].getAttribute('aria-selected') === 'true' || /(^|[\s_-])(active|on|sel|selected|current)([\s_-]|$)/i.test(cn)) return k[i];
    }
    return null;
  }
  var tabObs = null, tabBarEl = null;
  function centerActive() {
    if (!tabBarEl) return;
    var a = activeTab(tabBarEl);
    if (a && a.scrollIntoView) { try { a.scrollIntoView({ inline: 'center', block: 'nearest', behavior: 'smooth' }); } catch (e) {} }
  }
  function applyTabs() {
    var old = document.querySelector('[data-pdtabs]');
    if (!cfg.tabs) {
      if (old) {
        old.removeAttribute('data-pdtabs');
        var ts = old.querySelectorAll('[data-pdtab]');
        for (var i = 0; i < ts.length; i++) ts[i].removeAttribute('data-pdtab');
      }
      return;
    }
    var bar = old || findTabBar();
    if (!bar) return;
    if (!bar.hasAttribute('data-pdtabs')) bar.setAttribute('data-pdtabs', '1');
    for (var j = 0; j < bar.children.length; j++) { bar.children[j].setAttribute('data-pdtab', '1'); }
    if (bar !== tabBarEl) {
      tabBarEl = bar;
      try { if (tabObs) tabObs.disconnect(); } catch (e) {}
      try {
        tabObs = new MutationObserver(function () { setTimeout(centerActive, 30); });
        tabObs.observe(bar, { attributes: true, subtree: true, attributeFilter: ['class', 'aria-selected'] });
      } catch (e) {}
      setTimeout(centerActive, 60);
    }
  }
  document.addEventListener('click', function (e) {
    var t = e.target && e.target.closest ? e.target.closest('[data-pdtab]') : null;
    if (t) setTimeout(centerActive, 80);
  }, true);

  // Speed / Scroll sliders and the Left / Right buttons of the Touchpad tab
  function words(el) { return (el.textContent || '').toLowerCase().replace(/[^a-z]+/g, ' ').trim(); }
  function unhideAll() {
    var hs = document.querySelectorAll('[data-pdhide]');
    for (var i = 0; i < hs.length; i++) hs[i].removeAttribute('data-pdhide');
  }
  function hideBoth(items, pattern) {
    if (items.length === 0) return;
    var p = items[0].parentElement;
    if (items.length > 1 && p) {
      var ok = true;
      for (var i = 0; i < items.length; i++) { if (items[i].parentElement !== p) ok = false; }
      if (ok && pattern.test(words(p))) { p.setAttribute('data-pdhide', '1'); return; }
    }
    for (var j = 0; j < items.length; j++) items[j].setAttribute('data-pdhide', '1');
  }
  function applyPadControls() {
    if (!cfg.hidePad) { unhideAll(); return; }
    var ranges = document.querySelectorAll('input[type=range]'), labelled = [], i;
    for (i = 0; i < ranges.length; i++) {
      var par = ranges[i].parentElement;
      if (par && /^(speed|scroll)( (speed|scroll))*$/.test(words(par))) labelled.push(par);   // "Speed [slider]" wrapper
    }
    if (labelled.length) {
      var gp = labelled[0].parentElement, same = !!gp;
      for (i = 0; i < labelled.length; i++) { if (labelled[i].parentElement !== gp) same = false; }
      if (same && gp && /^(speed|scroll)( (speed|scroll))*$/.test(words(gp))) gp.setAttribute('data-pdhide', '1');
      else for (i = 0; i < labelled.length; i++) labelled[i].setAttribute('data-pdhide', '1');
    }
    var btns = [], els = document.querySelectorAll('button,[role=button],div,span'), n;
    for (n = 0; n < els.length; n++) {
      var e = els[n];
      if (e.childElementCount > 1) continue;
      var t = (e.textContent || '').trim().toLowerCase();
      if ((t === 'left' || t === 'right') && !e.closest('[data-pdhide]')) {
        var h = e;
        while (h.parentElement && h.parentElement.childElementCount === 1 && h.parentElement !== document.body) h = h.parentElement;
        if (btns.indexOf(h) < 0) btns.push(h);
      }
    }
    hideBoth(btns, /^(left right|right left)$/);
  }
  function applyUi() {
    try { ensureStyle(); applyTabs(); applyPadControls(); } catch (e) {}
  }
  applyUi();

  // ==== ANCHOR:JS_SUGBAR ====
  // Suggestion strip: a thin bar placed directly above the topmost row of the on-screen keyboard (Esc / F1..F12).
  // It is only shown while there is something to suggest, so the page's own resize handle stays reachable.
  var sugBar = null, sugCells = [], sugList = [], BAR_H = 36;
  var KEYLBL = /^(esc|f\d{1,2}|del|ins|tab|caps|shift|ctrl|alt|win|enter|space|home|end|pgup|pgdn|[a-z0-9`\-=\[\]\\;',.\/])$/i;

  function keyboardTop() {
    var els = document.querySelectorAll(KEY_SEL), top = 1e9, letters = 0;
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el.childElementCount > 1) continue;
      var t = (el.textContent || '').trim();
      if (!KEYLBL.test(t) || !visible(el)) continue;
      var r = el.getBoundingClientRect();
      if (r.width < 8 || r.height < 8) continue;
      if (/^[a-z]$/i.test(t)) letters++;
      if (r.top < top) top = r.top;
    }
    return letters >= 10 ? top : -1;
  }
  function buildBar() {
    if (sugBar && document.documentElement.contains(sugBar)) return sugBar;
    if (!document.body) return null;
    sugBar = document.createElement('div');
    sugBar.id = 'pd-sugbar';
    sugBar.style.cssText = 'position:fixed;left:0;right:0;top:0;height:' + BAR_H + 'px;z-index:2147483000;display:none;' +
      'box-sizing:border-box;padding:3px 6px;gap:6px;align-items:stretch;background:rgba(24,27,34,.97);' +
      'border-top:1px solid rgba(255,255,255,.08);user-select:none;-webkit-user-select:none;touch-action:manipulation';
    sugCells = [];
    for (var i = 0; i < 4; i++) {
      (function (idx) {
        var c = document.createElement('div');
        c.style.cssText = 'flex:1 1 0;min-width:0;border-radius:8px;background:#2A2F3A;color:#E8EBF2;font:15px/30px sans-serif;' +
          'text-align:center;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;padding:0 6px';
        var timer = 0, longDone = false;
        c.addEventListener('pointerdown', function (e) {
          e.preventDefault(); e.stopPropagation();           // never steal focus from the page
          longDone = false;
          c.style.background = '#4DA3FF';
          timer = setTimeout(function () { timer = 0; longDone = true; call(function () { N.forgetAt(idx); }); }, 650);
        });
        var up = function (e) {
          e.preventDefault(); e.stopPropagation();
          c.style.background = '#2A2F3A';
          if (timer) { clearTimeout(timer); timer = 0; if (!longDone) call(function () { N.pick(idx); }); }
        };
        c.addEventListener('pointerup', up);
        c.addEventListener('pointercancel', function () { clearTimeout(timer); timer = 0; c.style.background = '#2A2F3A'; });
        sugCells.push(c);
        sugBar.appendChild(c);
      })(i);
    }
    document.body.appendChild(sugBar);
    return sugBar;
  }
  function renderBar() {
    var show = cfg.sugg && sugList.length > 0;
    var top = show ? keyboardTop() : -1;
    if (!show || top < 0) { if (sugBar) sugBar.style.display = 'none'; return; }
    var b = buildBar();
    if (!b) return;
    for (var i = 0; i < sugCells.length; i++) {
      var w = sugList[i];
      sugCells[i].textContent = w || '';
      sugCells[i].style.visibility = w ? 'visible' : 'hidden';
    }
    b.style.top = Math.max(0, Math.round(top - BAR_H)) + 'px';
    b.style.display = 'flex';
  }
  window.__pdSug = function (list) { sugList = list || []; renderBar(); };
  setInterval(function () { if (sugList.length && cfg.sugg) renderBar(); }, 350);   // follow layout changes
  window.addEventListener('resize', renderBar);
  window.addEventListener('orientationchange', function () { setTimeout(renderBar, 200); });

  // ==== ANCHOR:JS_SETTINGS ====
  // Adds a "PhoneDeck app" section to the page's own Settings panel when its gear button is tapped.
  // If the panel cannot be recognised, the app's own settings dialog opens instead.
  var section = null, stamp = 0;
  var CTRL_SEL = 'input[type=range],input[type=checkbox],input[type=radio],select';

  function isGear(el) {
    var t = (el.textContent || '').replace(/[\uFE0F\s]/g, '');
    if (t === '\u2699' || t === '\u26ED') return true;
    var cn = String(el.className && el.className.baseVal !== undefined ? el.className.baseVal : el.className);
    return t.length <= 2 && /gear|cog|settings/i.test(cn + ' ' + (el.id || ''));
  }
  function mkSwitch(on) {
    var s = document.createElement('div');
    s.setAttribute('role', 'switch');
    s.style.cssText = 'flex:0 0 auto;width:42px;height:24px;border-radius:12px;position:relative;transition:background .15s;cursor:pointer';
    var k = document.createElement('div');
    k.style.cssText = 'position:absolute;top:3px;width:18px;height:18px;border-radius:9px;background:#fff;transition:left .15s';
    s.appendChild(k);
    s.__set = function (v) { s.style.background = v ? '#4DA3FF' : '#4a5163'; k.style.left = v ? '21px' : '3px'; s.setAttribute('aria-checked', v ? 'true' : 'false'); };
    s.__set(on);
    return s;
  }
  var TOGGLES = [
    ['sugg', 'sugg', 'Word suggestions'],
    ['sugnext', 'sugnext', 'Suggest the next word'],
    ['shift', 'shiftLabels', 'Shift changes the key letters'],
    ['tabs', 'tabs', 'Pretty scrolling tabs'],
    ['hidepad', 'hidePad', 'Touchpad: hide Speed/Scroll and Left/Right']
  ];
  var ACTIONS = [
    ['clearwords', 'Clear learned words'],
    ['reload', 'Reload page'],
    ['find', 'Find PC on the network'],
    ['testkeys', 'Test key detection'],
    ['disconnect', 'Disconnect']
  ];
  var switches = {}, clearBtn = null;

  function buildSection() {
    var d = document.createElement('div');
    d.id = 'pd-set';
    d.style.cssText = 'margin:14px 0 6px;padding:12px;border-radius:14px;background:rgba(77,163,255,.08);' +
      'border:1px solid rgba(77,163,255,.28);font:14px sans-serif;color:inherit;box-sizing:border-box;width:100%';
    var h = document.createElement('div');
    h.textContent = 'PhoneDeck app';
    h.style.cssText = 'font-weight:700;margin-bottom:8px;color:#4DA3FF';
    d.appendChild(h);
    TOGGLES.forEach(function (t) {
      var row = document.createElement('div');
      row.style.cssText = 'display:flex;align-items:center;justify-content:space-between;gap:12px;padding:8px 0';
      var lb = document.createElement('div');
      lb.textContent = t[2];
      var sw = mkSwitch(!!cfg[t[1]]);
      sw.addEventListener('click', function () {
        var v = !cfg[t[1]];
        cfg[t[1]] = v;
        if (t[0] === 'shift') cfg.symbols = v;
        sw.__set(v);
        call(function () { N.setPref(t[0], v); });
        applyCase(); applyUi(); renderBar();
      });
      switches[t[1]] = sw;
      row.appendChild(lb); row.appendChild(sw);
      d.appendChild(row);
    });
    var wrap = document.createElement('div');
    wrap.style.cssText = 'display:flex;flex-wrap:wrap;gap:8px;margin-top:8px';
    ACTIONS.forEach(function (a) {
      var b = document.createElement('div');
      b.textContent = a[1];
      b.style.cssText = 'padding:9px 14px;border-radius:999px;background:rgba(255,255,255,.08);cursor:pointer';
      if (a[0] === 'clearwords') clearBtn = b;
      b.addEventListener('click', function () { call(function () { N.action(a[0]); }); });
      wrap.appendChild(b);
    });
    d.appendChild(wrap);
    return d;
  }
  function refreshSection() {
    if (!section) return;
    for (var k in switches) { if (switches[k] && switches[k].__set) switches[k].__set(!!cfg[k]); }
    if (clearBtn) clearBtn.textContent = 'Clear learned words (' + (cfg.learned || 0) + ')';
  }
  function lca(list) {
    var a = list[0].parentElement;
    while (a) {
      var all = true;
      for (var i = 1; i < list.length; i++) { if (!a.contains(list[i])) { all = false; break; } }
      if (all) return a;
      a = a.parentElement;
    }
    return null;
  }
  // the panel = smallest element that contains every control that just became visible
  function findPanel() {
    var ctr = document.querySelectorAll(CTRL_SEL), fresh = [];
    for (var i = 0; i < ctr.length; i++) {
      if (ctr[i].closest('#pd-set')) continue;
      if (visible(ctr[i]) && ctr[i].__pdv !== stamp) fresh.push(ctr[i]);
    }
    if (!fresh.length) return null;
    var host = fresh.length > 1 ? lca(fresh) : (fresh[0].parentElement && fresh[0].parentElement.parentElement);
    return host || null;
  }
  document.addEventListener('click', function (e) {
    if (!e.target || !e.target.closest || e.target.closest('#pd-set,#pd-sugbar')) return;
    var g = e.target.closest('button,[role=button],a,div,span');
    if (!g || !isGear(g)) return;
    var wasOpen = !!(section && document.documentElement.contains(section) && visible(section));
    stamp++;
    var ctr = document.querySelectorAll(CTRL_SEL);
    for (var i = 0; i < ctr.length; i++) { if (visible(ctr[i])) ctr[i].__pdv = stamp; }   // what is visible before the page reacts
    setTimeout(function () {
      if (wasOpen) return;                                  // this tap closed the panel
      var host = findPanel();
      if (host) {
        if (!section) section = buildSection();
        host.appendChild(section);
        refreshSection();
      } else {
        call(function () { N.openMenu(); });
      }
    }, 250);
  }, true);

  // ==== ANCHOR:JS_SUGGEST ====
  var buf = '', modLatch = false, inserting = false;
  function rep() { call(function () { N.word(buf); }); }
  function clr() { buf = ''; call(function () { N.reset(); }); }
  function fin() {
    if (buf) { var w = buf; call(function () { N.learn(w); }); }
    buf = '';
    rep();
  }
  function sentenceEnd() { fin(); call(function () { N.reset(); }); }

  function key(k, mod) {
    if (mod || modLatch) { modLatch = false; clr(); return; }
    if (k.length === 1) {
      if (/[a-z0-9]/i.test(k)) { buf += k.toLowerCase(); rep(); }
      else if ((k === "'" || k === '\u2019') && buf) { buf += "'"; rep(); }
      else if (k === '.' || k === '!' || k === '?') sentenceEnd();
      else fin();
    } else if (k === 'Backspace') { buf = buf.slice(0, -1); rep(); }
    else if (k === 'Enter') sentenceEnd();
    else if (k === 'Tab') fin();
    else clr();
  }

  // physical keyboard (not typing in a text box)
  document.addEventListener('keydown', function (e) {
    if (!e.isTrusted || isEditable(e.target) || e.key === 'Unidentified' || e.key === 'Process') return;
    if (e.key === 'Shift' || e.key === 'CapsLock' || e.key === 'Control' || e.key === 'Alt' || e.key === 'Meta') return;
    key(e.key, e.ctrlKey || e.altKey || e.metaKey);
  }, true);

  // typing in a real text box (Android keyboard): read the word before the cursor
  function wordAtCaret(el) {
    var before = '';
    if (el.isContentEditable) {
      var s = window.getSelection();
      if (!s || !s.anchorNode) return null;
      var n = s.anchorNode;
      before = n.nodeType === 3 ? n.textContent.slice(0, s.anchorOffset) : (n.textContent || '');
    } else {
      var p = el.selectionStart;
      if (p === null || p === undefined) return null;
      before = el.value.slice(0, p);
    }
    var m = /([A-Za-z0-9'\u2019]+)([^A-Za-z0-9'\u2019]*)$/.exec(before);
    return m ? { w: m[1].replace(/\u2019/g, "'"), sep: m[2] } : { w: '', sep: '' };
  }
  document.addEventListener('input', function (e) {
    if (inserting || !e.isTrusted || !isTextual(e.target)) return;
    var t = e.inputType || '';
    if (t === 'insertFromPaste' || t === 'insertFromDrop' || t === 'historyUndo' || t === 'historyRedo') { clr(); return; }
    var r = wordAtCaret(e.target);
    if (!r) { clr(); return; }
    if (r.sep === '') { buf = r.w.toLowerCase(); rep(); }
    else {
      if (buf) { var w = r.w.toLowerCase(); call(function () { N.learn(w); }); }
      buf = '';
      if (/[.!?\n]/.test(r.sep)) call(function () { N.reset(); });
      rep();
    }
  }, true);
  document.addEventListener('focusin', function () { if (!inserting) clr(); }, true);

  // taps on the page's on-screen keyboard
  document.addEventListener('pointerdown', function (e) {
    if (!e.isTrusted) return;
    if (e.target && e.target.closest && e.target.closest('#pd-sugbar,#pd-set')) return;
    if (isEditable(e.target)) { clr(); return; }
    var k = keyInfo(e.target);
    if (!k) return;
    if (k === 'Shift') {
      var now = Date.now();
      if (now - lastShiftTap < 380) { caps = !caps; shift = false; }   // double tap = Caps Lock
      else { shift = !shift; }
      lastShiftTap = now; shiftT0 = now; shiftPtr = e.pointerId;
      applyCase();
    } else if (k === 'Caps') {
      caps = !caps;
      applyCase();
    } else if (k === 'Mod') {
      clr(); modLatch = true;
    } else {
      key(k, false);
      if (shift && shiftPtr < 0) pendingReset = true;                 // one-shot Shift ends after this key
    }
  }, true);

  function onUp(e) {
    if (!e.isTrusted) return;
    if (e.pointerId === shiftPtr) {
      shiftPtr = -1;
      if (Date.now() - shiftT0 > 420) { shift = false; applyCase(); } // Shift was held, not tapped
    }
    if (pendingReset) {
      pendingReset = false;
      setTimeout(function () { if (shiftPtr < 0) { shift = false; applyCase(); } }, 150);  // after the page handled the tap
    }
  }
  document.addEventListener('pointerup', onUp, true);
  document.addEventListener('pointercancel', onUp, true);

  // ==== ANCHOR:JS_INSERT ====
  function findKey(ch) {
    var hs = holders(), lc = ch.toLowerCase();
    for (var i = 0; i < hs.length; i++) {
      var t = (hs[i].textContent || '').trim();
      if (t.toLowerCase() !== lc) continue;
      var tgt = (hs[i].closest && hs[i].closest(KEY_SEL)) || hs[i];
      if (visible(tgt)) return tgt;          // keys on a hidden layer cannot be pressed
    }
    return null;
  }
  function findSpace() {
    var els = document.querySelectorAll(KEY_SEL);
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (!visible(el)) continue;
      var d = el.getAttribute('data-key') || el.getAttribute('data-k');
      if (d === ' ' || infoFromText(d || el.getAttribute('aria-label') || el.title || el.textContent) === ' ') return el;
      if ((el.textContent || '').trim() === '') {
        var cn = String(el.className && el.className.baseVal !== undefined ? el.className.baseVal : el.className);
        if (/space/i.test(cn + ' ' + el.id)) return el;
      }
    }
    return null;
  }
  function press(el) {
    var r = el.getBoundingClientRect(), x = r.left + r.width / 2, y = r.top + r.height / 2;
    var base = { bubbles: true, cancelable: true, clientX: x, clientY: y, view: window };
    var pi = { bubbles: true, cancelable: true, clientX: x, clientY: y, pointerId: 1, pointerType: 'touch', isPrimary: true };
    var prevented = false;
    try { if (!el.dispatchEvent(new PointerEvent('pointerdown', pi))) prevented = true; } catch (e) {}
    try {
      var t = new Touch({ identifier: 1, target: el, clientX: x, clientY: y });
      var ti = { bubbles: true, cancelable: true, touches: [t], targetTouches: [t], changedTouches: [t] };
      if (!el.dispatchEvent(new TouchEvent('touchstart', ti))) prevented = true;
    } catch (e) {}
    try { el.dispatchEvent(new PointerEvent('pointerup', pi)); } catch (e) {}
    try {
      var t2 = new Touch({ identifier: 1, target: el, clientX: x, clientY: y });
      var te = { bubbles: true, cancelable: true, touches: [], targetTouches: [], changedTouches: [t2] };
      if (!el.dispatchEvent(new TouchEvent('touchend', te))) prevented = true;
    } catch (e) {}
    if (!prevented) {
      el.dispatchEvent(new MouseEvent('mousedown', base));
      el.dispatchEvent(new MouseEvent('mouseup', base));
      el.dispatchEvent(new MouseEvent('click', base));
    }
  }
  // last resort when the page's keys cannot be found: ordinary key events
  function synth(ch) {
    var code = ch === ' ' ? 'Space' : /[a-z]/i.test(ch) ? 'Key' + ch.toUpperCase() : /[0-9]/.test(ch) ? 'Digit' + ch : '';
    var kc = ch === ' ' ? 32 : ch.toUpperCase().charCodeAt(0);
    var target = document.activeElement || document.body;
    ['keydown', 'keypress', 'keyup'].forEach(function (type) {
      try {
        target.dispatchEvent(new KeyboardEvent(type, { key: ch, code: code, keyCode: kc, which: kc, bubbles: true, cancelable: true }));
      } catch (e) {}
    });
  }

  // Replaces what you typed: types the rest of the chosen word (plus a space) as if you typed it.
  window.__pdInsert = function (text) {
    if (!text) return;
    inserting = true;
    var upper = caps;
    if (shift) { shift = false; applyCase(); }
    if (upper) text = text.toUpperCase();
    var a = document.activeElement, done = false;
    if (isTextual(a) && !isSecret(a)) {
      try { done = document.execCommand('insertText', false, text); } catch (e) { done = false; }
    }
    if (done) { buf = ''; inserting = false; rep(); return; }
    var i = 0, missed = 0;
    (function next() {
      if (i >= text.length) {
        buf = ''; inserting = false; rep();
        if (missed) call(function () { N.report('fallback', String(missed)); });
        return;
      }
      var ch = text.charAt(i++);
      var el = ch === ' ' ? findSpace() : findKey(ch);
      if (el) press(el); else { missed++; synth(ch); }
      setTimeout(next, 25);
    })();
  };

  // ==== ANCHOR:JS_DOWNLOADS ====
  var blobs = {};
  try {
    var cou = URL.createObjectURL;
    URL.createObjectURL = function (o) {
      var u = cou.apply(this, arguments);
      try { blobs[u] = o; } catch (e) {}
      return u;
    };
  } catch (e) {}

  function sendBlob(blob, name) {
    var id = 'b' + Date.now() + '_' + Math.floor(Math.random() * 1e6);
    var size = blob.size, off = 0, CH = 768 * 1024;          // multiple of 3 so base64 pieces join cleanly
    N.blobBegin(id, name || '', blob.type || '', size);
    (function next() {
      if (off >= size) { N.blobEnd(id); return; }
      var fr = new FileReader();
      fr.onload = function () {
        var s = String(fr.result);
        var ok = N.blobChunk(id, s.substring(s.indexOf(',') + 1));
        off += CH;
        if (ok === false) return;                              // cancelled in the app
        setTimeout(next, 0);
      };
      fr.onerror = function () { N.blobFail(id, 'Could not read the file in the page'); };
      fr.readAsDataURL(blob.slice(off, Math.min(off + CH, size)));
    })();
  }
  function sendBlobUrl(href, name) {
    var b = blobs[href];
    if (b) { sendBlob(b, name); return; }
    fetch(href).then(function (r) { return r.blob(); }).then(function (bb) { sendBlob(bb, name); })
      .catch(function () { call(function () { N.report('blobfail', ''); }); });
  }
  window.__pdFetchBlob = function (href) { sendBlobUrl(href, ''); };

  // returns true when the app takes over the download
  function handleAnchor(a) {
    var href = a.href || '', name = a.getAttribute('download') || '';
    if (!a.hasAttribute('download') && href.indexOf('blob:') !== 0) return false;
    if (href.indexOf('blob:') === 0) { sendBlobUrl(href, name); return true; }
    if (href) call(function () { N.hint(href, name); });     // the app learns the real file name
    return false;
  }
  document.addEventListener('click', function (e) {
    if (!e.isTrusted || !e.target || !e.target.closest) return;
    var a = e.target.closest('a');
    if (a && handleAnchor(a)) { e.preventDefault(); e.stopPropagation(); }
  }, true);
  try {
    var oclick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
      try { if (handleAnchor(this)) return; } catch (e) {}
      return oclick.apply(this, arguments);
    };
    var odispatch = EventTarget.prototype.dispatchEvent;
    EventTarget.prototype.dispatchEvent = function (ev) {
      try {
        if (this instanceof HTMLAnchorElement && ev && ev.type === 'click' && handleAnchor(this)) return true;
      } catch (e) {}
      return odispatch.apply(this, arguments);
    };
  } catch (e) {}

  // ==== ANCHOR:JS_PROBE ====
  window.__pdProbe = function () {
    var hs = holders(), seen = {}, digits = {}, i, t;
    for (i = 0; i < hs.length; i++) {
      t = (hs[i].textContent || '').trim().toLowerCase();
      if (/^[a-z]$/.test(t)) seen[t] = 1;
      if (/^[0-9]$/.test(t)) digits[t] = 1;
    }
    var sp = {}, els = document.querySelectorAll(KEY_SEL);
    for (i = 0; i < els.length; i++) {
      var r = ownInfo(els[i]);
      if (r === 'Shift' || r === 'Caps' || r === 'Backspace' || r === 'Enter' || r === ' ') sp[r] = 1;
    }
    var inputs = 0, ins = document.querySelectorAll('input,textarea,[contenteditable=true]');
    for (i = 0; i < ins.length; i++) { if (isTextual(ins[i])) inputs++; }
    return 'Letter keys found: ' + Object.keys(seen).length + ' / 26\n' +
      'Digit keys found: ' + Object.keys(digits).length + ' / 10\n' +
      'Space key: ' + (sp[' '] ? 'yes' : 'NOT found') + '\n' +
      'Shift key: ' + (sp.Shift ? 'yes' : 'NOT found') + '\n' +
      'Caps key: ' + (sp.Caps ? 'yes' : 'not found') + '\n' +
      'Backspace key: ' + (sp.Backspace ? 'yes' : 'NOT found') + '\n' +
      'Enter key: ' + (sp.Enter ? 'yes' : 'NOT found') + '\n' +
      'Text boxes on page: ' + inputs + '\n' +
      'Shift now: ' + (caps ? 'CAPS LOCK' : shift ? 'on' : 'off') + '\n' +
      'Key letters follow Shift: ' + (cfg.shiftLabels ? 'on' : 'off');
  };
})();
