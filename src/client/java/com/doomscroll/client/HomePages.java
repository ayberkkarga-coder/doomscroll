package com.doomscroll.client;

import com.doomscroll.Doomscroll;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Ana menu sayfalari: doomscroll://home/screen?pos=x,y,z (ekran) ve doomscroll://home/tablet (tablet).
 * HTML sablonu assets/doomscroll/home/home.html; veriler (kanallar, sira, yer imleri, gecmis) JSON olarak gomulur.
 * Sayfa, komutlari console kanaliyla ({"home":...}) gonderir; {@link #onScreenCommand} / {@link #onTabletCommand} isler.
 * Uretici CEF IO is parcaciginda cagrilir: yalnizca kopya listeler okunur.
 */
public final class HomePages {
	public static final String SCHEME = "doomscroll://";
	public static final String SCREEN = SCHEME + "home/screen";
	public static final String TABLET = SCHEME + "home/tablet";
	private static final Gson GSON = new Gson();
	private static volatile String template;
	private static volatile String tabletTemplate;
	private static volatile String consentTemplate;
	/** font.css + tiles.css + icons.css + kit.css: sayfalarin ortak cizim dili, bir kez okunur. */
	private static volatile String kit;

	private HomePages() {}

	public static boolean isHome(@Nullable String url) {
		return url != null && url.startsWith(SCHEME);
	}

	/** Izleyici onayi karti: gercek sayfa yerine bu acilir, siteye hicbir istek gitmez. */
	public static String consentUrl(@Nullable BlockPos anchor, String host, String by) {
		StringBuilder sb = new StringBuilder(SCHEME).append("home/consent?host=").append(enc(host));
		if (by != null && !by.isEmpty()) {
			sb.append("&by=").append(enc(by));
		}
		if (anchor != null) {
			sb.append("&pos=").append(anchor.getX()).append(',').append(anchor.getY()).append(',').append(anchor.getZ());
		}
		return sb.toString();
	}

	private static String enc(String s) {
		return java.net.URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
	}

	@Nullable
	private static String query(@Nullable String q, String key) {
		if (q == null) return null;
		for (String part : q.split("&")) {
			if (part.startsWith(key + "=")) {
				return java.net.URLDecoder.decode(part.substring(key.length() + 1), StandardCharsets.UTF_8);
			}
		}
		return null;
	}

	/** Ekranin ana menusu: sira o ekrana ait. */
	public static String screenUrl(@Nullable BlockPos anchor) {
		return anchor == null ? SCREEN : SCREEN + "?pos=" + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ();
	}

	/** CEF'ten (IO is parcacigi) istek: HTML ya da null (404). */
	@Nullable
	public static String html(String url) {
		URI u;
		try {
			u = URI.create(url);
		} catch (Exception e) {
			return null;
		}
		if (!"home".equals(u.getHost())) {
			return null;
		}
		String path = u.getPath() == null ? "" : u.getPath();
		if (path.startsWith("/consent")) {
			String t = consentTemplate();
			if (t == null) {
				return null;
			}
			JsonObject c = new JsonObject();
			String host = query(u.getQuery(), "host");
			c.addProperty("host", host == null ? "?" : host);
			String by = query(u.getQuery(), "by");
			if (by != null && !by.isEmpty()) {
				c.addProperty("by", by);
			}
			return page(t).replace("/*__DATA__*/", "window.__DS=" + GSON.toJson(c) + ";");
		}
		boolean tablet = path.startsWith("/tablet");
		JsonObject data = new JsonObject();
		data.addProperty("mode", tablet ? "tablet" : "screen");
		try {
			JsonArray ch = new JsonArray();
			for (Channels.Channel c : Channels.list()) {
				JsonObject o = new JsonObject();
				o.addProperty("name", c.name());
				o.addProperty("url", c.url());
				ch.add(o);
			}
			data.add("channels", ch);
			if (tablet) {
				data.add("bookmarks", entries(TabletBookmarks.bookmarks()));
				data.add("history", entries(TabletBookmarks.history()));
			} else {
				BlockPos pos = parsePos(u.getQuery());
				JsonArray q = new JsonArray();
				for (String qu : ScreenQueue.list(pos)) {
					JsonObject o = new JsonObject();
					o.addProperty("title", PageTitles.get(qu));
					o.addProperty("url", qu);
					q.add(o);
				}
				data.add("queue", q);
			}
		} catch (Exception e) {
			// veri toplanamadiysa (es zamanli degisiklik) sayfa yine acilsin
		}
		String t = template(tablet);
		if (t == null) {
			return null;
		}
		return page(t).replace("/*__DATA__*/", "window.__DS=" + GSON.toJson(data) + ";");
	}

	/** Ortak cizim dilini ve dil metinlerini sablona yerlestirir. */
	private static String page(String t) {
		return translate(t.replace("/*__KIT__*/", kit()));
	}

	/** Yazi tipi, karolar, simgeler ve ortak stil; hepsi tek parca halinde gomulur. */
	private static String kit() {
		String k = kit;
		if (k == null) {
			StringBuilder sb = new StringBuilder(70000);
			for (String name : new String[]{"font.css", "tiles.css", "icons.css", "kit.css"}) {
				try (InputStream in = HomePages.class.getResourceAsStream("/assets/doomscroll/home/" + name)) {
					if (in != null) {
						sb.append(new String(in.readAllBytes(), StandardCharsets.UTF_8)).append(System.lineSeparator());
					}
				} catch (Exception e) {
					Doomscroll.LOGGER.warn("ana sayfa stili okunamadi ({}): {}", name, e.toString());
				}
			}
			k = sb.toString();
			kit = k;
		}
		return k;
	}

	/**
	 * Sablondaki {{anahtar}} (HTML metni) ve {{js:anahtar}} (JS dizgisi icinde) yer tutucularini
	 * oyuncunun diline cevirir. Sablon ham haliyle onbellekte kalir, ceviri her istekte yapilir.
	 */
	private static String translate(String t) {
		StringBuilder out = new StringBuilder(t.length() + 256);
		int i = 0;
		while (true) {
			int a = t.indexOf("{{", i);
			if (a < 0) {
				out.append(t, i, t.length());
				return out.toString();
			}
			int b = t.indexOf("}}", a + 2);
			if (b < 0) {
				out.append(t, i, t.length());
				return out.toString();
			}
			out.append(t, i, a);
			String key = t.substring(a + 2, b);
			boolean js = key.startsWith("js:");
			if (js) key = key.substring(3);
			String value = Lang.tr(key);
			out.append(js ? escapeJs(value) : escapeHtml(value));
			i = b + 2;
		}
	}

	private static String escapeHtml(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
				.replace("\"", "&quot;").replace("'", "&#39;");
	}

	/** Tek tirnakli JS dizgisi icin: ters bolu, tirnaklar ve satir sonu. */
	private static String escapeJs(String s) {
		return s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
				.replace("\n", "\\n").replace("\r", "").replace("<", "\\u003c");
	}

	private static JsonArray entries(java.util.List<TabletBookmarks.Entry> list) {
		JsonArray a = new JsonArray();
		for (TabletBookmarks.Entry e : list) {
			JsonObject o = new JsonObject();
			o.addProperty("title", e.label());
			o.addProperty("url", e.url);
			a.add(o);
		}
		return a;
	}

	@Nullable
	private static BlockPos parsePos(@Nullable String query) {
		if (query == null) return null;
		for (String part : query.split("&")) {
			if (part.startsWith("pos=")) {
				String[] xyz = part.substring(4).split(",");
				if (xyz.length == 3) {
					try {
						return new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
					} catch (NumberFormatException ignored) {
					}
				}
			}
		}
		return null;
	}

	@Nullable
	private static String consentTemplate() {
		String t = consentTemplate;
		if (t == null) {
			try (InputStream in = HomePages.class.getResourceAsStream("/assets/doomscroll/home/consent.html")) {
				if (in == null) return null;
				t = new String(in.readAllBytes(), StandardCharsets.UTF_8);
				consentTemplate = t;
			} catch (Exception e) {
				return null;
			}
		}
		return t;
	}

	/** Sablon: ekran icin home.html (TV menusu), tablet icin home_tablet.html (iPad ana ekrani). */
	@Nullable
	private static String template(boolean tablet) {
		String t = tablet ? tabletTemplate : template;
		if (t == null) {
			String name = tablet ? "home_tablet.html" : "home.html";
			try (InputStream in = HomePages.class.getResourceAsStream("/assets/doomscroll/home/" + name)) {
				if (in == null) return null;
				t = new String(in.readAllBytes(), StandardCharsets.UTF_8);
				if (tablet) tabletTemplate = t; else template = t;
			} catch (Exception e) {
				return null;
			}
		}
		return t;
	}

	/** Ekran ana menusunden komut (oyun is parcacigi): siradan oynat / cikar. */
	public static void onScreenCommand(BlockPos anchor, JsonObject o) {
		String c = o.get("home").getAsString();
		switch (c) {
			case "play" -> ScreenQueue.playNow(anchor, o.has("n") ? o.get("n").getAsInt() : 1);
			case "add" -> Channels.add(str(o, "name"), str(o, "url"));
			case "removeChannel" -> Channels.remove(str(o, "name"));
			case "trust" -> {
				ServerPolicy.trust(str(o, "host"));
				ScreenBrowsers.consentGiven(anchor);
			}
			case "consentHome" -> ScreenBrowsers.consentDeclined(anchor);
			case "qremove" -> {
				String url = o.has("url") ? o.get("url").getAsString() : "";
				java.util.List<String> q = ScreenQueue.list(anchor);
				for (int i = 0; i < q.size(); i++) {
					if (q.get(i).equals(url)) {
						ScreenQueue.remove(anchor, i + 1);
						break;
					}
				}
			}
			default -> {
			}
		}
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString().trim() : "";
	}

	/** Tablet ana menusunden komut (oyun is parcacigi): yer imi cikar / gecmisi temizle. */
	public static void onTabletCommand(JsonObject o) {
		String c = o.get("home").getAsString();
		switch (c) {
			case "unbookmark" -> TabletBookmarks.removeBookmark(o.has("url") ? o.get("url").getAsString() : "");
			case "add" -> TabletBookmarks.addBookmark(str(o, "url"), str(o, "name"));
			case "clearHistory" -> TabletBookmarks.clearHistory();
			default -> {
			}
		}
	}
}
