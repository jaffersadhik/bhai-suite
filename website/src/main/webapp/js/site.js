/* Meeran Technologies website — language toggle, contact form, footer IP. No libraries. */
(function () {
  "use strict";
  var S = window.SITE || {};
  var LS = "mt_lang";
  var lang = "en";
  try { var saved = localStorage.getItem(LS); if (saved === "ta" || saved === "en") lang = saved; } catch (e) { }

  function $(s, r) { return (r || document).querySelector(s); }
  function $$(s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); }

  /* ---------- language: every element with data-ta keeps its English in data-en and swaps text ---------- */
  function tag(el) {
    if (el.dataset.en === undefined) el.dataset.en = el.innerHTML;       // innerHTML: a few headings carry <em>
  }
  function setLang(l) {
    lang = l;
    document.documentElement.lang = l;
    $$("[data-ta]").forEach(function (el) {
      tag(el);
      var v = l === "ta" ? el.getAttribute("data-ta") : el.dataset.en;
      if (el.tagName === "OPTION") el.textContent = v; else el.innerHTML = v;
    });
    $$(".lang button").forEach(function (b) { b.classList.toggle("on", b.getAttribute("data-lang") === l); });
    try { localStorage.setItem(LS, l); } catch (e) { }
    fillContact();
  }
  $$(".lang button").forEach(function (b) {
    b.addEventListener("click", function () { setLang(b.getAttribute("data-lang")); });
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
    if (u && /^https?:\/\//i.test(u)) { a.href = u; a.target = "_blank"; a.rel = "noopener"; a.hidden = false; }
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
    rows.forEach(function (r) { var d = document.createElement("div"); d.innerHTML = r; info.appendChild(d); });
    foot.innerHTML = "";
    foot.appendChild(li("<a href=\"#contact\">" + (lang === "ta" ? "டெமோ கேளுங்கள்" : "Request a demo") + "</a>"));
    if (wa) foot.appendChild(li("<a href=\"https://wa.me/" + wa + "\" target=\"_blank\" rel=\"noopener\">WhatsApp</a>"));
    if (ph) foot.appendChild(li(esc(ph)));
    if (mail) foot.appendChild(li("<a href=\"mailto:" + esc(mail) + "\">" + esc(mail) + "</a>"));
    if (ad) foot.appendChild(li(esc(ad)));
    var can = !!(wa || mail);
    form.hidden = !can; soon.hidden = can;
    $("#fNote").textContent = can ? (lang === "ta" ? (wa ? "WhatsApp திறக்கும் — செய்தியை அனுப்புங்கள்." : "உங்கள் மின்னஞ்சல் செயலி திறக்கும்.") : (wa ? "WhatsApp will open — just press send." : "Your e-mail app will open.")) : "";
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
