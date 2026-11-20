/* Meeran Technologies website — language toggle, contact form, footer IP. No libraries. */
(function () {
  "use strict";
  var S = window.SITE || {};
  var LS = "mt_lang";
  var lang = "en";
  var NATIVE = { en: 1, ta: 1, hi: 1, te: 1, kn: 1, ml: 1, ar: 1 };   /* languages with a hand-written pack; every other language goes through Google Translate */
  var RTL = { ar: 1 };
  var EXTRA = ["Landmark: ", "Owner, Founder, CEO & CTO", "WhatsApp will open \u2014 just press send.", "Your e-mail app will open."];
  var EXTRA_TA = ["அடையாளம்: ", "உரிமையாளர், நிறுவனர், CEO & CTO", "WhatsApp திறக்கும் \u2014 செய்தியை அனுப்புங்கள்.", "உங்கள் மின்னஞ்சல் செயலி திறக்கும்."];
  var googleUsed = false;
  try { var saved = localStorage.getItem(LS); if (NATIVE[saved]) lang = saved; } catch (e) { }

  function $(s, r) { return (r || document).querySelector(s); }
  function $$(s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); }

  /* ---------- language ----------
     Every translatable element keeps its English in data-en. Tamil sits in data-ta; the other languages are
     JSON arrays (js/lang/xx.json) in the same order as the unique English strings of the page + EXTRA. */
  var order = [], tamil = [], map = {}, packs = {};
  $$("[data-ta]").forEach(function (el) {
    if (el.dataset.en === undefined) el.dataset.en = el.innerHTML;      /* innerHTML: a few headings carry <em>/<b> */
    var en = el.dataset.en.trim();
    if (order.indexOf(en) < 0) { order.push(en); tamil.push(el.getAttribute("data-ta")); }
  });
  EXTRA.forEach(function (e, i) { order.push(e); tamil.push(EXTRA_TA[i]); });
  packs.ta = tamil;

  function t(en) { return lang === "en" ? en : (map[en] || en); }

  function apply(l, arr) {
    lang = l;
    map = {};
    if (arr) order.forEach(function (en, i) { if (arr[i]) map[en] = arr[i]; });
    var h = document.documentElement;
    h.lang = l;
    h.dir = RTL[l] ? "rtl" : "ltr";
    $$("[data-ta]").forEach(function (el) {
      var en = el.dataset.en;
      var v = l === "en" ? en : (map[en.trim()] || en);
      if (el.tagName === "OPTION") el.textContent = v; else el.innerHTML = v;
    });
    var sel = $("#langSel"); if (sel && sel.value !== l && !googleUsed) sel.value = l;
    try { localStorage.setItem(LS, l); } catch (e) { }
    fillContact();
  }
  function setLang(l) {
    if (!NATIVE[l]) l = "en";
    if (l === "en" || packs[l]) return apply(l, packs[l]);
    fetch("js/lang/" + l + ".json", { credentials: "same-origin" })
      .then(function (r) { if (!r.ok) throw 0; return r.json(); })
      .then(function (a) { packs[l] = a; apply(l, a); })
      .catch(function () { apply("en"); });
  }

  /* ---------- every other language: Google Website Translator (needs internet; loaded only when chosen) ---------- */
  function clearGoogleCookie() {
    ["googtrans"].forEach(function (n) {
      document.cookie = n + "=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
      document.cookie = n + "=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/; domain=" + location.hostname;
    });
  }
  function showGoogle() {
    googleUsed = true;
    var box = $("#gt"); box.hidden = false;
    if (window.google && window.google.translate) return;
    window.gtInit = function () {
      new window.google.translate.TranslateElement({ pageLanguage: "en", autoDisplay: false, layout: window.google.translate.TranslateElement.InlineLayout.SIMPLE }, "gt_el");
    };
    var sc = document.createElement("script");
    sc.src = "https://translate.google.com/translate_a/element.js?cb=gtInit";
    sc.async = true;
    document.head.appendChild(sc);
  }
  var sel = $("#langSel");
  sel.addEventListener("change", function () {
    var v = sel.value;
    if (v === "other") { apply("en"); showGoogle(); return; }
    if (googleUsed) {                         /* leaving Google's translation: reset the page cleanly */
      clearGoogleCookie();
      try { localStorage.setItem(LS, v); } catch (e) { }
      location.reload();
      return;
    }
    setLang(v);
  });

  /* ---------- mobile menu ---------- */
  var burger = $("#burger"), nav = $("#nav");
  burger.addEventListener("click", function () {
    var o = nav.classList.toggle("open");
    burger.setAttribute("aria-expanded", o ? "true" : "false");
  });
  $$("#nav a").forEach(function (a) { a.addEventListener("click", function () { nav.classList.remove("open"); burger.setAttribute("aria-expanded", "false"); }); });

  /* ---------- product links ---------- */
  $$(".app-link").forEach(function (a) {
    var u = S[a.getAttribute("data-site")];
    if (u && /^https?:\/\//i.test(u)) { a.href = u.replace(/\/+$/, "") + "/" + (a.getAttribute("data-path") || ""); a.target = "_blank"; a.rel = "noopener"; a.hidden = false; }
  });

  /* ---------- contact box + footer ---------- */
  function li(html) { var e = document.createElement("li"); e.innerHTML = html; return e; }
  function esc(s) { return String(s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
  function fillContact() {
    var info = $("#contactInfo"), foot = $("#footContact"), form = $("#demoForm"), soon = $("#contactSoon");
    var wa = String(S.whatsapp || "").replace(/\D/g, ""), mail = S.email || "", ph = S.phone || "", ad = S.address || "";
    info.innerHTML = "";
    var rows = [];
    if (wa) rows.push("💬 <a href=\"https://wa.me/" + wa + "\" target=\"_blank\" rel=\"noopener\">WhatsApp</a>");
    if (ph) rows.push("📞 <a href=\"tel:" + esc(ph.replace(/[^+\d]/g, "")) + "\">" + esc(ph) + "</a>");
    if (mail) rows.push("✉️ <a href=\"mailto:" + esc(mail) + "\">" + esc(mail) + "</a>");
    if (ad) rows.push("📍 " + esc(ad));
    if (S.landmark) rows.push("🧭 " + t("Landmark: ") + esc(S.landmark));
    if (S.owner) rows.push("👤 " + esc(S.owner) + " — " + t("Owner, Founder, CEO & CTO"));
    rows.forEach(function (r) { var d = document.createElement("div"); d.innerHTML = r; info.appendChild(d); });
    foot.innerHTML = "";
    foot.appendChild(li("<a href=\"#contact\">" + t("Request a demo") + "</a>"));
    if (wa) foot.appendChild(li("<a href=\"https://wa.me/" + wa + "\" target=\"_blank\" rel=\"noopener\">WhatsApp</a>"));
    if (ph) foot.appendChild(li(esc(ph)));
    if (mail) foot.appendChild(li("<a href=\"mailto:" + esc(mail) + "\">" + esc(mail) + "</a>"));
    if (ad) foot.appendChild(li(esc(ad)));
    if (S.landmark) foot.appendChild(li(t("Landmark: ") + esc(S.landmark)));
    var can = !!(wa || mail);
    form.hidden = !can; soon.hidden = can;
    $("#fNote").textContent = can ? t(wa ? EXTRA[2] : EXTRA[3]) : "";
  }

  $("#demoForm").addEventListener("submit", function (ev) {
    ev.preventDefault();
    var f = ev.target;
    var name = f.name.value.trim(), phone = f.phone.value.trim(), type = f.type.value, msg = f.msg.value.trim();
    var text = "Hello Meeran Technologies, I would like a demo.\nName: " + name + (phone ? "\nMobile: " + phone : "") + "\nI run a: " + type + (msg ? "\n" + msg : "");
    var wa = String(S.whatsapp || "").replace(/\D/g, "");
    if (wa) window.open("https://wa.me/" + wa + "?text=" + encodeURIComponent(text), "_blank", "noopener");
    else if (S.email) window.location.href = "mailto:" + S.email + "?subject=" + encodeURIComponent("Demo request") + "&body=" + encodeURIComponent(text);
  });

  /* ---------- footer: year + visitor IP (same-origin servlet /api/myip) ---------- */
  $("#yr").textContent = new Date().getFullYear();
  fetch("api/myip", { cache: "no-store", credentials: "omit" })
    .then(function (r) { if (!r.ok) throw new Error(r.status); return r.json(); })
    .then(function (j) { $("#myip").textContent = (j && j.ip) || "–"; })
    .catch(function () { $("#myip").textContent = "–"; });

  setLang(lang);
})();
