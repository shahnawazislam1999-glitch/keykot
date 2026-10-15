/*
 * PhoneDeck helper script (link mode).
 * Injected by DeckActivity into the PC page after it loads. It cannot see the page's source, so it works by
 * watching what is tapped / typed. Search for "ANCHOR:" to jump to a section:
 *   ANCHOR:JS_CONFIG        settings pushed from the app
 *   ANCHOR:JS_KEY_CLASSIFY  decides what a tapped element is (Shift, Space, a letter ...)
 *   ANCHOR:JS_SHIFT_SYNC    Shift / Caps state + small <-> CAPITAL key labels
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
  var cfg = { shiftLabels: true, symbols: true };
  window.__pdConfig = function (o) {
    for (var k in o) { cfg[k] = o[k]; }
    applyCase();
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
  function schedApply() { clearTimeout(applyT); applyT = setTimeout(applyCase, 40); }

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
