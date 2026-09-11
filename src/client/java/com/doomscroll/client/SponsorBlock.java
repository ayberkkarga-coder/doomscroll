package com.doomscroll.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SponsorBlock: YouTube videolarindaki sponsor / kendi reklami / abone ol hatirlatmasi / intro / outro bolumlerini
 * topluluk verisiyle (sponsor.ajay.app) atlar. Video kimligi degisince bolumler cekilir (onbellekli) ve sayfaya
 * enjekte edilir; sayfadaki raporcu 250 ms'de bir konumu kontrol edip bolumun sonuna atlar.
 */
public final class SponsorBlock {
	private static final Logger LOGGER = LoggerFactory.getLogger("doomscroll-sponsor");
	private static final String API = "https://sponsor.ajay.app/api/skipSegments?videoID=%s&categories=%s";
	private static final String CATEGORIES = URLEncoder.encode(
			"[\"sponsor\",\"selfpromo\",\"interaction\",\"intro\",\"outro\",\"preview\"]", StandardCharsets.UTF_8);
	private static final Pattern[] ID_PATTERNS = {
			Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})"),
			Pattern.compile("/shorts/([A-Za-z0-9_-]{11})"),
			Pattern.compile("youtu\\.be/([A-Za-z0-9_-]{11})"),
			Pattern.compile("/embed/([A-Za-z0-9_-]{11})"),
			Pattern.compile("/live/([A-Za-z0-9_-]{11})"),
	};
	private static final Map<String, List<double[]>> CACHE = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, List<double[]>> e) {
			return size() > 200;
		}
	});
	private static final Map<String, List<Consumer<List<double[]>>>> WAITERS = new HashMap<>();
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.ALWAYS).build();
	private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "doomscroll-sponsor");
		t.setDaemon(true);
		return t;
	});
	private static final AtomicInteger SKIPPED = new AtomicInteger();
	private static long lastErrorLogMs = 0L;

	private SponsorBlock() {}

	/** YouTube video kimligi (11 karakter) ya da null. */
	@Nullable
	public static String videoId(@Nullable String url) {
		if (url == null || !url.contains("youtu")) {
			return null;
		}
		for (Pattern p : ID_PATTERNS) {
			Matcher m = p.matcher(url);
			if (m.find()) {
				return m.group(1);
			}
		}
		return null;
	}

	/** Bolumleri getirir (onbellek/arka plan), sonucu Minecraft is parcaciginda verir. */
	public static void request(String id, Consumer<List<double[]>> onDone) {
		List<double[]> cached = CACHE.get(id);
		if (cached != null) {
			onDone.accept(cached);
			return;
		}
		boolean first;
		synchronized (WAITERS) {
			List<Consumer<List<double[]>>> w = WAITERS.computeIfAbsent(id, k -> new ArrayList<>());
			first = w.isEmpty();
			w.add(onDone);
		}
		if (!first) {
			return;
		}
		EXEC.submit(() -> {
			List<double[]> segs = fetch(id);
			CACHE.put(id, segs);
			List<Consumer<List<double[]>>> w;
			synchronized (WAITERS) {
				w = WAITERS.remove(id);
			}
			if (w != null) {
				Minecraft.getInstance().execute(() -> {
					for (Consumer<List<double[]>> c : w) {
						c.accept(segs);
					}
				});
			}
		});
	}

	private static List<double[]> fetch(String id) {
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(String.format(Locale.ROOT, API, id, CATEGORIES)))
					.timeout(Duration.ofSeconds(15)).header("User-Agent", "doomscroll-minecraft").GET().build();
			HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() == 404) {
				return List.of(); // bu video icin bolum yok
			}
			if (resp.statusCode() / 100 != 2) {
				logError("SponsorBlock HTTP " + resp.statusCode());
				return List.of();
			}
			List<double[]> out = new ArrayList<>();
			JsonElement root = JsonParser.parseString(resp.body());
			if (root.isJsonArray()) {
				for (JsonElement e : root.getAsJsonArray()) {
					if (!e.isJsonObject() || !e.getAsJsonObject().has("segment")) continue;
					JsonArray seg = e.getAsJsonObject().getAsJsonArray("segment");
					if (seg.size() < 2) continue;
					double s = seg.get(0).getAsDouble(), t = seg.get(1).getAsDouble();
					if (t - s >= 1.0) {
						out.add(new double[] {s, t});
					}
				}
			}
			out.sort((a, b) -> Double.compare(a[0], b[0]));
			// bindirmeleri birlestir
			List<double[]> merged = new ArrayList<>();
			for (double[] sg : out) {
				if (!merged.isEmpty() && sg[0] <= merged.get(merged.size() - 1)[1] + 0.5) {
					merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], sg[1]);
				} else {
					merged.add(new double[] {sg[0], sg[1]});
				}
			}
			LOGGER.info("[sponsor] {}: {} bolum", id, merged.size());
			return merged;
		} catch (Exception e) {
			logError("SponsorBlock alinamadi: " + e);
			return List.of();
		}
	}

	private static void logError(String msg) {
		long now = System.currentTimeMillis();
		if (now - lastErrorLogMs > 60_000L) {
			lastErrorLogMs = now;
			LOGGER.warn(msg);
		}
	}

	/** Sayfaya enjekte edilecek bolum listesi. */
	public static String injectJs(String id, List<double[]> segs) {
		StringBuilder sb = new StringBuilder("window.__dsSegs={id:'").append(id).append("',segs:[");
		for (int i = 0; i < segs.size(); i++) {
			if (i > 0) sb.append(',');
			sb.append('[').append(String.format(Locale.ROOT, "%.2f", segs.get(i)[0])).append(',')
					.append(String.format(Locale.ROOT, "%.2f", segs.get(i)[1])).append(']');
		}
		return sb.append("]};").toString();
	}

	public static void noteSkipped() {
		SKIPPED.incrementAndGet();
	}

	public static int skippedTotal() {
		return SKIPPED.get();
	}

	public static int cachedVideos() {
		return CACHE.size();
	}
}
