package com.mschool.core;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Online translation of master data (item / group names ...) and of any screen phrase a language pack does not have.
 * Results are kept in the table {@code translations}, so every text is translated online only once, for all users.
 *
 * Provider (config): {@code translate.google.key} set -> Google Cloud Translation v2 (official, billed by Google);
 * otherwise the free Google web endpoint ({@code translate.url}, default translate.googleapis.com ... client=gtx).
 */
public class Translator {
    private static final Logger log = LoggerFactory.getLogger(Translator.class);
    private static final java.util.regex.Pattern TAMIL = java.util.regex.Pattern.compile("[\\u0B80-\\u0BFF]");
    static final int MAX_ITEMS = 150, MAX_LEN = 300;

    private final Db db;
    private final String key, url;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final ExecutorService pool = Executors.newFixedThreadPool(6, r -> { Thread t = new Thread(r, "translator"); t.setDaemon(true); return t; });
    private final Map<String, long[]> rate = new ConcurrentHashMap<>();   // ip -> {windowStartMs, count}

    /** English-only labels of the app (i18n/labels.json): only these English texts may be translated from English */
    private final java.util.Set<String> labels = new java.util.HashSet<>();

    public Translator(Db db, String key, String url) {
        this.db = db;
        try (java.io.InputStream in = Translator.class.getResourceAsStream("/static/i18n/labels.json")) {
            if (in != null) {
                com.fasterxml.jackson.databind.JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(in);
                for (com.fasterxml.jackson.databind.JsonNode k : n) if (k.asText().length() <= MAX_LEN) labels.add(k.asText());
            }
        } catch (Exception e) { log.warn("labels.json not read: {}", e.toString()); }
        this.key = key == null ? "" : key.trim();
        this.url = url == null || url.isBlank() ? "https://translate.googleapis.com/translate_a/single" : url.trim();
    }

    private static String hash(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** simple limit per client address: 120 calls a minute */
    public void checkRate(String ip) {
        long now = System.currentTimeMillis();
        long[] w = rate.compute(ip == null ? "-" : ip, (k, v) -> (v == null || now - v[0] > 60000) ? new long[]{now, 1} : new long[]{v[0], v[1] + 1});
        if (w[1] > 120) throw new ApiException(429, "Too many requests");
        if (rate.size() > 5000) rate.clear();
    }

    /**
     * @param to    target languages (e.g. "en", "ar")
     * @param items each {s: Tamil text, e: optional English text of the same phrase (better source for non-English targets)}
     * @return {lang: {Tamil text: translated text}} - only texts that could be translated
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> translate(List<String> to, List<Object> items) {
        List<String> langs = new ArrayList<>();
        for (String l : to) { String c = l == null ? "" : l.trim().toLowerCase(Locale.ROOT); if (c.matches("[a-z]{2,3}(-[A-Za-z]{2,4})?") && !c.equals("ta") && !langs.contains(c)) langs.add(c); }
        if (langs.isEmpty() || langs.size() > 3) throw new ApiException(400, "Invalid language");
        if (items.size() > MAX_ITEMS) throw new ApiException(400, "Too many texts");
        Map<String, String> eng = new LinkedHashMap<>();                  // Tamil text -> English hint ("" if none)
        for (Object o : items) {
            String s, e = "";
            if (o instanceof Map) { Map<String, Object> m = (Map<String, Object>) o; s = String.valueOf(m.getOrDefault("s", "")); e = String.valueOf(m.getOrDefault("e", "")); if (e.equals("null")) e = ""; }
            else s = String.valueOf(o);
            s = s.trim();
            if (s.isEmpty() || s.length() > MAX_LEN) continue;
            if (!TAMIL.matcher(s).find()) { if (!labels.contains(s)) continue; e = s; }      // English app label: translated from English
            if (e.length() > MAX_LEN || TAMIL.matcher(e).find()) e = "";
            eng.putIfAbsent(s, e.trim());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String lang : langs) {
            Map<String, String> res = new LinkedHashMap<>();
            List<String> todo = new ArrayList<>();
            for (String s : eng.keySet()) {
                Map<String, Object> r = db.one("SELECT txt FROM translations WHERE src_hash = ? AND lang = ?", hash(s), lang);
                if (r != null) res.put(s, String.valueOf(r.get("txt"))); else todo.add(s);
            }
            if (!todo.isEmpty()) {
                List<Future<String>> fs = new ArrayList<>();
                for (String s : todo) {
                    String hint = eng.get(s);
                    fs.add(pool.submit(() -> online(hint.isEmpty() || lang.equals("en") ? s : hint, hint.isEmpty() || lang.equals("en") ? "ta" : "en", lang)));
                }
                for (int i = 0; i < todo.size(); i++) {
                    String s = todo.get(i), t = null;
                    try { t = fs.get(i).get(8, TimeUnit.SECONDS); } catch (Exception e) { log.warn("translate failed: {}", e.toString()); }
                    if (t == null || t.isBlank() || t.equals(s)) continue;
                    if (t.length() > 600) t = t.substring(0, 600);
                    try { db.update("INSERT IGNORE INTO translations (src_hash, lang, src, txt) VALUES (?, ?, ?, ?)", hash(s), lang, s, t); } catch (RuntimeException e) { log.warn("translation not stored: {}", e.toString()); }
                    res.put(s, t);
                }
            }
            out.put(lang, res);
        }
        return out;
    }

    /** one text through the online service; null on any failure (nothing is cached then) */
    String online(String text, String from, String to) {
        try {
            if (!key.isEmpty()) {
                String body = "q=" + URLEncoder.encode(text, StandardCharsets.UTF_8) + "&source=" + from + "&target=" + to + "&format=text&key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
                HttpRequest rq = HttpRequest.newBuilder(URI.create("https://translation.googleapis.com/language/translate/v2")).timeout(Duration.ofSeconds(7))
                        .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();
                HttpResponse<String> rs = http.send(rq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (rs.statusCode() != 200) return null;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"translatedText\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(rs.body());
                return m.find() ? unescape(m.group(1)) : null;
            }
            String u = url + (url.contains("?") ? "&" : "?") + "client=gtx&dt=t&sl=" + from + "&tl=" + to + "&q=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
            HttpRequest rq = HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofSeconds(7)).header("User-Agent", "Mozilla/5.0").GET().build();
            HttpResponse<String> rs = http.send(rq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (rs.statusCode() != 200) return null;
            return gtx(rs.body());
        } catch (Exception e) {
            log.warn("online translate: {}", e.toString());
            return null;
        }
    }

    /** reads [[["translated","source",...],["more","source"]], ...] : joins the first string of every segment */
    static String gtx(String body) {
        StringBuilder sb = new StringBuilder();
        int i = 0, n = body.length(), depth = 0;
        char last = 0;
        while (i < n) {
            char c = body.charAt(i);
            if (c == '"') {
                String[] r = readStr(body, i);
                if (depth == 3 && last == '[') sb.append(r[0]);     // first element of a segment = translated text
                i = Integer.parseInt(r[1]); last = '"'; continue;
            }
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth <= 0) break; }
            if (c != ' ' && c != '\n') last = c;
            i++;
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String[] readStr(String b, int i) {            // b[i] == '"' ; returns {value, indexAfterClosingQuote}
        StringBuilder sb = new StringBuilder(); int j = i + 1, n = b.length();
        while (j < n) { char c = b.charAt(j); if (c == '\\' && j + 1 < n) { sb.append(b, j, j + 2); j += 2; continue; } if (c == '"') break; sb.append(c); j++; }
        return new String[]{unescape(sb.toString()), String.valueOf(j + 1)};
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder o = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { o.append(c); continue; }
            char d = s.charAt(++i);
            switch (d) {
                case 'n': o.append('\n'); break;
                case 't': o.append('\t'); break;
                case 'r': break;
                case 'u': if (i + 4 < s.length()) { o.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; } break;
                default: o.append(d);
            }
        }
        return o.toString();
    }
}
