/* ============================================================================
 * MBill i18n — language switcher with an English "minute" line under every label
 * ----------------------------------------------------------------------------
 * The app's source text is Tamil. Every Tamil phrase is looked up in
 *   i18n/<code>.json   ({ "<Tamil source phrase>": "<translation>" })
 * and replaced in the DOM (text, placeholder / aria-label / title, <option>,
 * wa.me links, confirm / alert / prompt, share / copy text, PDF / print HTML).
 *
 *   selected language  → bold (primary)
 *   English            → small line under it (turn off in the language sheet)
 *
 * Add a language: drop i18n/<code>.json + add one entry to i18n/languages.json.
 * Phrases a pack does not have — and master data typed in Tamil (item / group / supplier names ...) — are translated ONLINE by the
 * server (POST /api/translate, cached in the table `translations`) and shown in the selected language.
 * English shows ONLY English (no second line, nothing in Tamil); every other language shows itself + a small English line.
 * ========================================================================== */
(function () {
  "use strict";
  var LS_LANG = "bmb_lang", LS_EN = "bmb_en2", VER = "1";
  var TA = /[஀-௿]/;
  var MAX_SECOND = 150;            // English line is skipped for very long sentences
  var FALLBACK_LANGS = [
    { code: "ta", native: "தமிழ்", english: "Tamil", coverage: 100 },
    { code: "en", native: "English", english: "English", coverage: 100 }
  ];

  var langs = FALLBACK_LANGS.slice();
  var raw = {};                    // code -> raw JSON object
  var packs = {};                  // code -> built pack
  var LABELS = new Set();          // English-only labels of the app screens (i18n/labels.json) — translated online in other languages
  /* English is the default. The billing app keeps the language per USER on the server (the login page is always English);
     a page that sets window.I18N_PERSIST = true (the public shop) remembers the choice on the device instead. */
  var PERSIST = window.I18N_PERSIST === true;
  var state = { lang: "en", en2: true };
  try {
    if (PERSIST) { var sl = localStorage.getItem(LS_LANG); if (sl) state.lang = sl; }
    var se = localStorage.getItem(LS_EN); if (se !== null) state.en2 = se !== "0";
  } catch (e) { }

  /* ---------------------------------------------------------------- packs */
  function build(rawLang, rawEn) {
    var exact = new Map(), first = new Map(), enOnly = new Set(), k, v;
    function add(key, val) {
      if (!key || val === undefined || val === null || String(val) === "") return;
      exact.set(key, String(val));
    }
    for (k in rawLang) add(k, rawLang[k]);                              // only the language's own phrases; the rest is translated online
    exact.forEach(function (val, key) {
      if (!TA.test(key)) { enOnly.add(key); return; }                   // English-only labels: whole text only
      if (key.length < 3) return;                                       // 1-2 letter fragments: whole text only
      var c = key.charAt(0), arr = first.get(c);
      if (!arr) first.set(c, arr = []);
      arr.push([key, val]);
    });
    first.forEach(function (arr) { arr.sort(function (a, b) { return b[0].length - a[0].length; }); });
    return { exact: exact, first: first, enOnly: enOnly };
  }
  function ensurePack(code) {
    var jobs = [];
    if (!raw.en) jobs.push(load("en"));
    if (code !== "ta" && code !== "en" && !raw[code]) jobs.push(load(code));
    if (code !== "ta" && code !== "en" && !LABELS.size) jobs.push(fetch("i18n/labels.json?v=" + VER).then(function (r) { return r.ok ? r.json() : []; }).then(function (a) { LABELS = new Set(a); }).catch(function () { }));
    return Promise.all(jobs).then(function () {
      if (!packs.en) packs.en = build(raw.en || {}, null);
      if (code !== "ta" && code !== "en" && !packs[code]) packs[code] = build(raw[code] || {}, null);
    });
  }
  function load(code) {
    return fetch("i18n/" + encodeURIComponent(code) + ".json?v=" + VER, { cache: "no-cache" })
      .then(function (r) { if (!r.ok) throw new Error(r.status); return r.json(); })
      .then(function (j) { raw[code] = j || {}; })
      .catch(function () { raw[code] = raw[code] || {}; });
  }

  /* ----------------------------------------------------------- translation */
  /* ---- online translation of whatever the packs do not have (master data, new phrases, other languages) ---- */
  var RUN = /[஀-௿]+(?:[ \u00a0'’.\-][஀-௿]+)*/g;
  var dyn = {}, need = {}, tried = {}, flushT = null, flushing = 0, waiters = [], saveT = null;
  function dynMap(code) {
    if (dyn[code]) return dyn[code];
    var m = new Map();
    try { var j = JSON.parse(localStorage.getItem("bmb_dyn_" + code) || "{}"); for (var k in j) m.set(k, j[k]); } catch (e) { }
    return (dyn[code] = m);
  }
  function saveDyn(code) {
    clearTimeout(saveT);
    saveT = setTimeout(function () {
      try {
        var m = dyn[code]; if (!m) return; var o = {}, n = 0, skip = Math.max(0, m.size - 4000);
        m.forEach(function (v, k) { if (n++ >= skip) o[k] = v; });
        localStorage.setItem("bmb_dyn_" + code, JSON.stringify(o));
      } catch (e) { }
    }, 800);
  }
  function enOf(src) {                    // best English text we already have for a Tamil / English-only source
    var e = dynMap("en").get(src); if (e) return e;
    var P = packs.en; if (P) { var h = P.exact.get(src.trim()); if (h && !TA.test(h)) return h; }
    return "";
  }
  function queue(src, code) {
    var key = code + "\u0001" + src, now = Date.now();
    if (tried[key] && now - tried[key] < 60000) return;     // asked recently (or failed): do not hammer the server
    tried[key] = now;
    (need[code] || (need[code] = new Map())).set(src, code === "en" ? "" : enOf(src));
    clearTimeout(flushT); flushT = setTimeout(flush, 180);
  }
  function flush() {
    var codes = Object.keys(need);
    codes.forEach(function (code) {
      var m = need[code]; delete need[code];
      var arr = []; m.forEach(function (h, s) { arr.push({ s: s, e: h || "" }); });
      for (var i = 0; i < arr.length; i += 120) sendBatch(code, arr.slice(i, i + 120));
    });
  }
  function sendBatch(code, items) {
    flushing++;
    fetch("api/translate", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ to: [code], items: items }) })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (j) {
        var res = j && j.map && j.map[code], got = 0;
        if (res) { var D = dynMap(code); for (var s in res) { if (res[s] && !D.has(s)) { D.set(s, res[s]); got++; } } if (got) saveDyn(code); }
        if (got && state.lang) setTimeout(applyAll, 30);
      })
      .catch(function () { })
      .then(function () { flushing--; if (!flushing && !flushT) { var w = waiters; waiters = []; w.forEach(function (f) { f(); }); } });
  }
  /** resolves when the texts of this HTML / string have been translated (max ~5 s) — print / PDF documents */
  function settle(h) {
    h = String(h == null ? "" : h);
    tx(h, state.lang); if (state.lang !== "en") tx(h, "en");
    if (!flushT && !flushing) return Promise.resolve(h);
    return new Promise(function (ok) { var t = setTimeout(ok, 5000); waiters.push(function () { clearTimeout(t); ok(); }); }).then(function () { return h; });
  }
  function runsTx(s, code) {
    var D = dynMap(code);
    return s.replace(RUN, function (run) {
      var v = D.get(run);
      if (v !== undefined) return v;
      queue(run, code);
      if (code !== "en") { var e = enOf(run); if (e) return e; }        // meanwhile show the English we already have
      return run;
    });
  }
  function tx(s, code) {
    if (!s || code === "ta") return s;
    var P = packs[code]; if (!P) return s;
    var t = s.trim();
    var hit = P.exact.get(t);
    if (hit !== undefined) { if (t === s) return hit; var i0 = s.indexOf(t); return s.slice(0, i0) + hit + s.slice(i0 + t.length); }
    if (code !== "en" && LABELS.has(t)) {          // English-only app label (e.g. "Admin Panel") in another language
      var dv = dynMap(code).get(t);
      if (dv !== undefined) return s.replace(t, dv);
      queue(t, code); return s;
    }
    if (!TA.test(s)) return s;
    var out = "", i = 0, n = s.length, last = 0, any = false;
    while (i < n) {
      var arr = P.first.get(s.charAt(i)), m = null;
      if (arr) for (var j = 0; j < arr.length; j++) { if (s.startsWith(arr[j][0], i)) { m = arr[j]; break; } }
      if (m) { out += s.slice(last, i) + m[1]; i += m[0].length; last = i; any = true; }
      else i++;
    }
    var res = any ? out + s.slice(last) : s;
    return TA.test(res) ? runsTx(res, code) : res;                       // whatever is still Tamil: master data / new text -> online
  }
  function primary(s) { return state.lang === "ta" ? s : tx(s, state.lang); }
  function secondary(s, p) {
    if (!state.en2 || state.lang === "en") return "";          // English shows only English: no second line
    var e = tx(s, "en");
    if (!e || TA.test(e)) return "";
    var et = e.trim();
    if (et === String(p).trim() || et.length > MAX_SECOND) return "";
    return et;
  }
  function relevant(s) {
    if (TA.test(s)) return true;
    if (state.lang === "ta" || state.lang === "en") return false;
    return LABELS.has(s.trim());
  }
  function active() { return !(state.lang === "ta" && !state.en2); }

  /* dialogs (+ English below), share / copy / wa.me (selected language only) */
  function dlg(m) {
    m = String(m == null ? "" : m);
    if (!active()) return m;
    var p = primary(m), e = secondary(m, p);
    return e ? p + "\n\n" + e : p;
  }
  function msgOnly(m) { m = String(m == null ? "" : m); return state.lang === "ta" ? m : tx(m, state.lang); }

  /* ------------------------------------------------------------ DOM walker */
  var SKIP_TAG = { SCRIPT: 1, STYLE: 1, TEXTAREA: 1, NOSCRIPT: 1, INPUT: 1, TITLE: 1, "X-I18N": 1 };
  var INLINE_TAG = /^(B|STRONG|EM|I|U|SPAN|A|LABEL|SMALL|CODE|FONT|MARK|SUB|SUP)$/;
  var ATTRS = ["placeholder", "aria-label", "title", "alt"];
  var obs = null, running = false;

  function skipped(el) {
    for (var p = el; p && p.nodeType === 1; p = p.parentNode) {
      if (SKIP_TAG[p.tagName] || p.hasAttribute("data-noi18n")) return true;
    }
    return false;
  }
  function attrSkipped(el) {      // attributes are also translated on <input> / <textarea>
    for (var p = el; p && p.nodeType === 1; p = p.parentNode) {
      if (p.tagName === "SCRIPT" || p.tagName === "STYLE" || p.tagName === "X-I18N" || p.hasAttribute("data-noi18n")) return true;
    }
    return false;
  }
  function textNodes(root) {
    var list = [];
    if (root.nodeType === 3) { list.push(root); return list; }
    if (root.nodeType !== 1 && root.nodeType !== 11) return list;
    var w = document.createTreeWalker(root, 4, null), n;
    while ((n = w.nextNode())) list.push(n);
    return list;
  }
  function processRoot(root) {
    if (!active()) return;
    var nodes = textNodes(root), jobs = [], dispCache = new Map(), i;
    for (i = 0; i < nodes.length; i++) {
      var node = nodes[i], p = node.parentNode;
      if (!p || p.nodeType !== 1 || !node.data || !/\S/.test(node.data)) continue;
      if (!TA.test(node.data) && state.lang === "ta") continue;
      if (skipped(p)) continue;
      if (!relevant(node.data)) continue;
      jobs.push(node);
    }
    /* read phase */
    var infos = [];
    for (i = 0; i < jobs.length; i++) {
      var nd = jobs[i], par = nd.parentNode, tag = par.tagName, kind;
      if (tag === "OPTION") kind = "opt";
      else if (par.namespaceURI && par.namespaceURI.indexOf("svg") > -1) kind = "svg";
      else {
        var disp = dispCache.get(par);
        if (disp === undefined) { try { disp = getComputedStyle(par).display; } catch (e) { disp = "block"; } dispCache.set(par, disp); }
        kind = "inl";
        if (!(INLINE_TAG.test(tag) && disp === "inline")) {
          kind = "blk";
          for (var s = par.firstChild; s; s = s.nextSibling) {
            if (s !== nd && s.nodeType === 3 && /\S/.test(s.data)) { kind = "inl"; break; }
          }
        }
      }
      infos.push(kind);
    }
    /* write phase */
    for (i = 0; i < jobs.length; i++) {
      var node2 = jobs[i], src = node2.data, trimmed = src.trim();
      var pr = primary(trimmed), se = secondary(trimmed, pr), par2 = node2.parentNode;
      if (!par2) continue;
      if (pr === trimmed && !se) continue;
      var kind2 = infos[i], lead = src.slice(0, src.indexOf(trimmed)), trail = src.slice(src.indexOf(trimmed) + trimmed.length);
      if (kind2 === "opt" || kind2 === "svg") {
        par2.setAttribute("data-i18n-src", src);
        node2.data = (kind2 === "opt" && se) ? pr + " · " + se : pr;
        continue;
      }
      var wrap = document.createElement("x-i18n");
      wrap.setAttribute("data-src", trimmed);
      wrap.className = (se ? "two " : "") + kind2;
      var xp = document.createElement("x-p"); xp.textContent = pr; wrap.appendChild(xp);
      if (se) { var xs = document.createElement("x-s"); xs.textContent = kind2 === "inl" ? "(" + se + ")" : se; wrap.appendChild(xs); }
      var frag = document.createDocumentFragment();
      if (lead) frag.appendChild(document.createTextNode(lead));
      frag.appendChild(wrap);
      if (trail) frag.appendChild(document.createTextNode(trail));
      par2.replaceChild(frag, node2);
    }
    processAttrs(root);
  }
  function processAttrs(root) {
    if (!active()) return;
    var els = [];
    if (root.nodeType === 1) { els.push(root); }
    if (root.querySelectorAll) els = els.concat([].slice.call(root.querySelectorAll("[placeholder],[aria-label],[title],[alt],a[href*='wa.me']")));
    els.forEach(function (el) {
      if (attrSkipped(el)) return;
      ATTRS.forEach(function (a) {
        if (!el.hasAttribute(a)) return;
        var v = el.getAttribute(a);
        if (!v || !relevant(v)) return;
        var t = primary(v);
        if (t !== v) { el.setAttribute("data-i18n-a-" + a, v); el.setAttribute(a, t); }
      });
      if (el.tagName === "A") {
        var h = el.getAttribute("href") || "";
        if (/^https:\/\/wa\.me\//.test(h) && state.lang !== "ta") {
          var nh = fixWa(h);
          if (nh !== h) { el.setAttribute("data-i18n-a-href", h); el.setAttribute("href", nh); }
        }
      }
    });
  }
  function restoreAll() {
    document.querySelectorAll("x-i18n").forEach(function (w) {
      var t = document.createTextNode(w.getAttribute("data-src") || w.textContent);
      if (w.parentNode) w.parentNode.replaceChild(t, w);
    });
    document.querySelectorAll("[data-i18n-src]").forEach(function (el) {
      var tn = el.firstChild; var src = el.getAttribute("data-i18n-src");
      if (tn && tn.nodeType === 3) tn.data = src; el.removeAttribute("data-i18n-src");
    });
    document.querySelectorAll("*").forEach(function (el) {
      for (var i = el.attributes.length - 1; i >= 0; i--) {
        var at = el.attributes[i];
        if (at.name.indexOf("data-i18n-a-") === 0) {
          var real = at.name.slice(12); el.setAttribute(real, at.value); el.removeAttribute(at.name);
        }
      }
    });
  }
  function run(fn) {
    if (running) return; running = true;
    if (obs) obs.disconnect();
    try { fn(); } finally { running = false; if (obs) observe(); }
  }
  function observe() {
    obs.observe(document.body, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ATTRS.concat(["href"]) });
  }
  function onMutations(recs) {
    var roots = new Set(), attrRoots = new Set();
    recs.forEach(function (r) {
      if (r.type === "childList") { r.addedNodes.forEach(function (n) { if (n.nodeType === 1 || n.nodeType === 3) roots.add(n); }); }
      else if (r.type === "characterData") { if (r.target.parentNode) roots.add(r.target); }
      else if (r.type === "attributes") { attrRoots.add(r.target); }
    });
    if (!roots.size && !attrRoots.size) return;
    run(function () {
      updateLabels();
      attrRoots.forEach(function (n) { if (n.isConnected && !roots.has(n)) processAttrs(n); });
      roots.forEach(function (n) {
        if (!n.isConnected) return;
        if (n.nodeType === 1 && n.tagName === "X-I18N") return;
        if (n.nodeType === 1 && n.parentNode && n.parentNode.tagName === "X-I18N") return;
        if (n.nodeType === 3 && n.parentNode && n.parentNode.tagName === "X-P") return;
        processRoot(n);
      });
    });
  }
  function applyAll() {
    run(function () {
      restoreAll();
      document.body.normalize();
      processRoot(document.body);
      document.documentElement.lang = state.lang;
      setDir();
      updateLabels();
    });
  }
  function setDir() {
    var li = langInfo(state.lang), rtl = !!(li && li.rtl);
    document.documentElement.dir = rtl ? "rtl" : "ltr";
    document.documentElement.classList.toggle("rtl", rtl);
  }

  /* wa.me text param */
  function fixWa(u) {
    var q = u.indexOf("?text="); if (q < 0 || u.indexOf("&raw=1") > 0) return u;    // "&raw=1": keep the text as written (e.g. an order sent to the shop stays in the shop's language)
    var t; try { t = decodeURIComponent(u.slice(q + 6)); } catch (e) { return u; }
    return u.slice(0, q + 6) + encodeURIComponent(msgOnly(t));
  }

  /* -------------------------------------------------------- global sinks */
  var _confirm = window.confirm, _alert = window.alert, _prompt = window.prompt, _open = window.open;
  window.confirm = function (m) { return _confirm.call(window, dlg(m)); };
  window.alert = function (m) { return _alert.call(window, dlg(m)); };
  window.prompt = function (m, d) { return _prompt.call(window, dlg(m), d); };
  window.open = function (u) {
    var a = [].slice.call(arguments);
    if (typeof u === "string" && /^https:\/\/wa\.me\//.test(u) && state.lang !== "ta") a[0] = fixWa(u);
    return _open.apply(window, a);
  };
  try {
    if (navigator.share) {
      var _share = navigator.share.bind(navigator);
      navigator.share = function (d) { if (d && d.text && state.lang !== "ta") d = Object.assign({}, d, { text: msgOnly(d.text) }); return _share(d); };
    }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      var _cw = navigator.clipboard.writeText.bind(navigator.clipboard);
      navigator.clipboard.writeText = function (t) { return _cw(state.lang !== "ta" ? msgOnly(t) : t); };
    }
  } catch (e) { }

  /* ------------------------------------------------------------- language UI */
  function langInfo(code) { for (var i = 0; i < langs.length; i++) if (langs[i].code === code) return langs[i]; return null; }
  function updateLabels() {
    var li = langInfo(state.lang) || FALLBACK_LANGS[0];
    document.querySelectorAll("[data-langlabel]").forEach(function (el) { el.textContent = "🌐 " + li.native; });
  }
  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
  function openPicker() {
    var body = document.getElementById("sheetBody") || document.getElementById("sb"); if (!body || typeof window.showSheet !== "function") return;
    body.innerHTML = '<h2 data-noi18n>' + (state.lang === "en" ? '🌐 Language' : '🌐 மொழி / Language') + '</h2>' + (state.lang === "en" ? '<p class="sub">தேர்ந்தெடுத்த மொழி மட்டும் தெரியும்</p>' : '<p class="sub">தேர்ந்தெடுத்த மொழி தடிமனாகவும், ஆங்கிலம் சிறியதாகவும் தெரியும்</p>') +
      '<div class="box" style="padding:6px">' + langs.map(function (l) {
        var on = l.code === state.lang;
        return '<button type="button" class="mrow lang-row' + (on ? ' on' : '') + '" data-langpick="' + esc(l.code) + '" lang="' + esc(l.code) + '">' +
          '<b>' + esc(l.native) + '</b><em>' + esc(l.english) + '' + '</em>' + (on ? '<i>✓</i>' : '') + '</button>';
      }).join("") + '</div>' +
      (state.lang === "en" ? "" : '<label class="rem" style="margin:10px 2px"><input type="checkbox" id="langEn"' + (state.en2 ? " checked" : "") + '>சிறிய ஆங்கில வரியை காட்டு</label>') +
      '<p class="hint">மொழிபெயர்ப்பு இல்லாத சொற்கள் (ஐட்டம் / குரூப் பெயர்கள் உட்பட) இன்டர்நெட் மூலம் தானாக மொழிபெயர்க்கப்படும்</p>' +
      '<button class="cta ghost" data-close id="closeSheet2">மூடு (Close)</button>';
    window.showSheet();
  }
  function setLang(code, keepSheet) {
    if (!langInfo(code)) code = "en";
    return ensurePack(code).then(function () {
      state.lang = code; try { if (PERSIST) localStorage.setItem(LS_LANG, code); } catch (e) { }
      applyAll();
      if (!keepSheet && typeof window.closeSheet === "function") { try { window.closeSheet(); } catch (e) { } }
    });
  }
  function setEn2(on) {
    state.en2 = !!on; try { localStorage.setItem(LS_EN, on ? "1" : "0"); } catch (e) { }
    return ensurePack(state.lang).then(applyAll);
  }
  document.addEventListener("click", function (e) {
    var t = e.target.closest && e.target.closest("button"); if (!t) return;
    if (t.id === "langBtn" || t.id === "mLang") { e.stopPropagation(); e.preventDefault(); openPicker(); return; }
    if (t.hasAttribute("data-langpick")) {
      e.stopPropagation(); e.preventDefault(); var pick = t.getAttribute("data-langpick");
      setLang(pick).then(function () { if (typeof window.onLangPick === "function") { try { window.onLangPick(pick); } catch (x) { } } });
    }
  }, true);
  document.addEventListener("change", function (e) {
    if (e.target && e.target.id === "langEn") { e.stopPropagation(); setEn2(e.target.checked); }
  }, true);

  /* -------------------------------------------------------------- start */
  function injectCss() {
    var st = document.createElement("style");
    st.textContent =
      "x-i18n{display:inline}x-p,x-s{display:inline}" +
      "html.rtl body{text-align:right}html.rtl input[type=number],html.rtl input[type=tel],html.rtl input[type=password],html.rtl input[type=email],html.rtl .num{direction:ltr;text-align:right}" +
      "html.rtl x-i18n.inl>x-s{margin-left:0;margin-right:.3em}html.rtl .lang-row{text-align:right}html.rtl .lang-row i{margin-left:0;margin-right:auto}" +
      "x-i18n.two>x-p{font-weight:700}" +
      "x-i18n>x-s{font-size:.72em;font-weight:500;line-height:1.25;opacity:.8;letter-spacing:0;text-transform:none;font-style:normal}" +
      "x-i18n.blk>x-s{display:block;margin-top:1px}x-i18n.inl>x-s{margin-left:.3em}" +
      ".lang-row{display:flex;align-items:center;gap:10px;text-align:left}.lang-row b{font-size:16px}.lang-row em{font-style:normal;font-size:12px;color:var(--muted)}" +
      ".lang-row i{margin-left:auto;font-style:normal;color:var(--leaf);font-weight:800}.lang-row.on{background:var(--leaf-l)}" +
      ".langbtn.hdr{position:static;padding:6px 10px;font-size:12px;margin-left:auto;color:var(--leaf-d)}.langbtn.hdr+.call{margin-left:6px}.login{position:relative}.langbtn{color:var(--leaf-d);position:absolute;top:calc(10px + env(safe-area-inset-top,0px));right:12px;z-index:5;background:rgba(255,255,255,.92);border:1.5px solid var(--line);border-radius:999px;padding:7px 13px;font-weight:700;font-size:13px}";
    document.head.appendChild(st);
  }
  function init() {
    injectCss();
    document.documentElement.lang = state.lang;
    obs = new MutationObserver(onMutations);
    fetch("i18n/languages.json?v=" + VER, { cache: "no-cache" }).then(function (r) { return r.ok ? r.json() : null; })
      .then(function (j) { if (j && j.length) langs = j; }).catch(function () { })
      .then(function () {
        if (!langInfo(state.lang)) state.lang = "en";
        return ensurePack(state.lang);
      })
      .then(function () { applyAll(); });
    observe();
  }

  window.I18N = {
    get lang() { return state.lang; }, get en2() { return state.en2; }, langs: function () { return langs.slice(); },
    set: setLang, setEnglishLine: setEn2, openPicker: openPicker, refresh: applyAll,
    /** selected-language text (no English line) for strings that leave the DOM */
    t: msgOnly,
    /** same, for an HTML string (print / PDF documents) */
    html: function (h) { return state.lang === "ta" ? h : tx(String(h), state.lang); },
    /** original Tamil text of an element (undo the wrappers) — for copy buttons */
    srcText: function (el) {
      if (!el) return "";
      var c = el.cloneNode(true);
      c.querySelectorAll("x-i18n").forEach(function (w) { w.replaceWith(document.createTextNode(w.getAttribute("data-src") || "")); });
      c.querySelectorAll("[data-i18n-src]").forEach(function (o) { o.textContent = o.getAttribute("data-i18n-src"); });
      return c.textContent;
    },
    tx: tx,
    settle: settle,
    /** master data the user already typed in both languages: [[tamil, english], ...] — used before asking the server */
    learn: function (pairs) {
      var D = dynMap("en"), n = 0;
      (pairs || []).forEach(function (p) { if (p && p[0] && p[1] && TA.test(p[0]) && !TA.test(p[1]) && D.get(p[0]) !== p[1]) { D.set(p[0], String(p[1])); n++; } });
      if (n) { saveDyn("en"); if (state.lang !== "ta" || state.en2) setTimeout(applyAll, 30); }
    }
  };
  if (document.body) init(); else document.addEventListener("DOMContentLoaded", init);
})();
