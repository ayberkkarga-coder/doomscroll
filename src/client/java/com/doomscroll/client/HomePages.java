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
 * Home menu pages: doomscroll://home/screen?pos=x,y,z (screen) and doomscroll://home/tablet (tablet).
 * The HTML template is assets/doomscroll/home/home.html; the data (channels, queue, bookmarks, history) is embedded as JSON.
 * The page sends commands over the console channel ({"home":...}); {@link #onScreenCommand} / {@link #onTabletCommand} handle them.
 * The generator is called on the CEF IO thread: only copied lists are read.
 */
public final class HomePages {
	public static final String SCHEME = "doomscroll://";
	public static final String SCREEN = SCHEME + "home/screen";
	public static final String TABLET = SCHEME + "home/tablet";
	private static final Gson GSON = new Gson();
	private static volatile String template;
	private static volatile String tabletTemplate;
	private static volatile String consentTemplate;
	/** font.css + tiles.css + icons.css + kit.css: the pages' shared visual language, read once. */
	private static volatile String kit;

	private HomePages() {}

	public static boolean isHome(@Nullable String url) {
		return url != null && url.startsWith(SCHEME);
	}

	/** Viewer consent card: opens instead of the real page, no request goes to the site. */
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

	/** The screen's home menu: the queue belongs to that screen. */
	public static String screenUrl(@Nullable BlockPos anchor) {
		return anchor == null ? SCREEN : SCREEN + "?pos=" + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ();
	}

	/** Request from CEF (IO thread): HTML or null (404). */
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
		if (path.startsWith("/receiver")) {
			String t = asset("receiver.html");
			if (t == null) {
				return null;
			}
			JsonObject r = new JsonObject();
			r.addProperty("unsupported", Lang.tr("gui.doomscroll.broadcast.unsupported"));
			return page(t).replace("/*__DATA__*/", "window.__DS=" + GSON.toJson(r) + ";");
		}
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
				// Refreshing the queue sends a packet; since this runs on CEF's IO thread,
				// hand the request over to the game thread.
				net.minecraft.client.Minecraft.getInstance().execute(() -> ScreenQueue.refresh(pos));
				JsonArray q = new JsonArray();
				for (com.doomscroll.net.QueueBroadcast.Row r : ScreenQueue.list(pos)) {
					JsonObject o = new JsonObject();
					o.addProperty("title", ScreenQueue.label(r));
					o.addProperty("url", r.url());
					o.addProperty("votes", r.votes());
					o.addProperty("by", r.by());
					o.addProperty("mine", r.mine());
					q.add(o);
				}
				data.add("queue", q);
			}
		} catch (Exception e) {
			// if the data could not be gathered (concurrent modification), the page should still open
		}
		String t = template(tablet);
		if (t == null) {
			return null;
		}
		return page(t).replace("/*__DATA__*/", "window.__DS=" + GSON.toJson(data) + ";");
	}

	/** Inserts the shared visual language and the translated texts into the template. */
	private static String page(String t) {
		return translate(t.replace("/*__KIT__*/", kit()));
	}

	/** Font, tiles, icons and the shared style; all embedded as a single piece. */
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
					Doomscroll.LOGGER.warn("could not read home page style ({}): {}", name, e.toString());
				}
			}
			k = sb.toString();
			kit = k;
		}
		return k;
	}

	/**
	 * Translates the {{key}} (HTML text) and {{js:key}} (inside a JS string) placeholders in the template
	 * into the player's language. The template stays cached in its raw form; translation happens on every request.
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

	/** For a single-quoted JS string: backslash, quotes and line breaks. */
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
			t = asset("consent.html");
			consentTemplate = t;
		}
		return t;
	}

	/** Reads a template under home/ (uncached; callers keep their own caches). */
	@Nullable
	private static String asset(String name) {
		try (InputStream in = HomePages.class.getResourceAsStream("/assets/doomscroll/home/" + name)) {
			return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			Doomscroll.LOGGER.warn("could not read home page template ({}): {}", name, e.toString());
			return null;
		}
	}

	/** Address of the broadcast receiver page (the viewer's screen navigates to it). */
	public static String receiverUrl() {
		return SCHEME + "home/receiver";
	}

	/** Template: home.html for the screen (TV menu), home_tablet.html for the tablet (iPad home screen). */
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

	/** Command from the screen's home menu (game thread): play from / remove from the queue. */
	public static void onScreenCommand(BlockPos anchor, JsonObject o) {
		String c = o.get("home").getAsString();
		switch (c) {
			case "play" -> ScreenQueue.playNow(anchor, str(o, "url"));
			case "qvote" -> ScreenQueue.vote(anchor, str(o, "url"));
			case "add" -> Channels.add(str(o, "name"), str(o, "url"));
			case "removeChannel" -> Channels.remove(str(o, "name"));
			case "trust" -> {
				ServerPolicy.trust(str(o, "host"));
				ScreenBrowsers.consentGiven(anchor);
			}
			case "consentHome" -> ScreenBrowsers.consentDeclined(anchor);
			case "qremove" -> ScreenQueue.remove(anchor, str(o, "url"));
			default -> {
			}
		}
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString().trim() : "";
	}

	/** Command from the tablet's home menu (game thread): remove bookmark / clear history. */
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
