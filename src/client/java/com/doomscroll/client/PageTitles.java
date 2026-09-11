package com.doomscroll.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Adres -> okunabilir baslik. Video sitelerinde oEmbed (YouTube, TikTok, Vimeo, Dailymotion) arka planda sorulur;
 * gelene kadar ve diger sitelerde site adi + yol gosterilir. Sira listesi ve gecmis icin.
 */
public final class PageTitles {
	/** Adres -> baslik. En fazla bu kadar; en eskisi dusulur (SponsorBlock onbellegi gibi). */
	private static final int MAX_CACHE = 500;
	private static final Map<String, String> CACHE = java.util.Collections.synchronizedMap(
			new java.util.LinkedHashMap<>(64, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
					return size() > MAX_CACHE;
				}
			});
	private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.ALWAYS).build();
	private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "doomscroll-titles");
		t.setDaemon(true);
		return t;
	});

	private PageTitles() {}

	/** Bilinen baslik; yoksa arka planda sorgular ve kisa ad dondurur. Her cagri ucuz (ana is parcaciginda). */
	public static String get(String url) {
		if (url == null || url.isEmpty()) return "";
		String t = CACHE.get(url);
		if (t != null) return t.isEmpty() ? fallback(url) : t;
		String api = oembedUrl(url);
		if (api != null && PENDING.add(url)) {
			EXEC.submit(() -> {
				String title = fetch(api);
				CACHE.put(url, title == null ? "" : title);
				PENDING.remove(url);
			});
		} else if (api == null) {
			CACHE.put(url, "");
		}
		return fallback(url);
	}

	/** Bilinen bir basligi (sayfa raporundan) kaydeder; oEmbed sorgusuna gerek kalmaz. */
	public static void remember(String url, String title) {
		if (url == null || url.isEmpty() || title == null || title.isBlank()) return;
		CACHE.put(url, title.trim());
	}

	private static String oembedUrl(String url) {
		String u = url.toLowerCase(Locale.ROOT);
		String enc = URLEncoder.encode(url, StandardCharsets.UTF_8);
		if (u.contains("youtube.com/") || u.contains("youtu.be/")) return "https://www.youtube.com/oembed?format=json&url=" + enc;
		if (u.contains("tiktok.com/")) return "https://www.tiktok.com/oembed?url=" + enc;
		if (u.contains("vimeo.com/")) return "https://vimeo.com/api/oembed.json?url=" + enc;
		if (u.contains("dailymotion.com/")) return "https://www.dailymotion.com/services/oembed?format=json&url=" + enc;
		return null;
	}

	private static String fetch(String api) {
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(api)).timeout(Duration.ofSeconds(10))
					.header("User-Agent", "doomscroll-minecraft").GET().build();
			HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() / 100 != 2) return null;
			JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
			if (!o.has("title")) return null;
			String t = o.get("title").getAsString().trim();
			return t.isEmpty() ? null : t;
		} catch (Exception e) {
			return null;
		}
	}

	/** Site adi + yolun okunabilir kismi ("YouTube · watch?v=abc", "tiktok.com · @kanal/video/123"). */
	static String fallback(String url) {
		String site = RemoteScreen.siteName(url);
		try {
			URI u = URI.create(url);
			String path = u.getPath() == null ? "" : u.getPath();
			String q = u.getQuery() == null ? "" : "?" + u.getQuery();
			String tail = (path.length() > 1 ? path.substring(1) : "") + q;
			if (tail.isEmpty()) return site;
			return site + " · " + (tail.length() > 28 ? tail.substring(0, 27) + "…" : tail);
		} catch (Exception e) {
			return site;
		}
	}
}
