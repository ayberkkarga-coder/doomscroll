package com.doomscroll.client;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * TV kanallari: kumandadaki "Kanal ◀ / ▶" listede gezer. Liste config/doomscroll.json icinde
 * (/ds kanal ekle|sil|liste ile de duzenlenir). Her oyuncunun kendi listesi vardir.
 */
public final class Channels {
	public record Channel(String name, String url) {}

	private static int index = 0;

	private Channels() {}

	public static List<DoomscrollConfig.ChannelEntry> defaults() {
		List<DoomscrollConfig.ChannelEntry> l = new ArrayList<>();
		l.add(DoomscrollConfig.ChannelEntry.of("YouTube Shorts", Browsers.URL_SHORTS));
		l.add(DoomscrollConfig.ChannelEntry.of("Instagram Reels", Browsers.URL_REELS));
		l.add(DoomscrollConfig.ChannelEntry.of("TikTok", Browsers.URL_TIKTOK));
		l.add(DoomscrollConfig.ChannelEntry.of("YouTube", "https://www.youtube.com/"));
		l.add(DoomscrollConfig.ChannelEntry.of("Twitch", "https://www.twitch.tv/"));
		l.add(DoomscrollConfig.ChannelEntry.of("Kick", "https://kick.com/"));
		return l;
	}

	public static List<Channel> list() {
		List<Channel> out = new ArrayList<>();
		List<DoomscrollConfig.ChannelEntry> cfg = DoomscrollConfig.get().channels;
		// Kopya uzerinde gez: bu metot ana sayfa uretilirken CEF'in IO is parcaciginda da cagriliyor.
		cfg = cfg == null ? null : List.copyOf(cfg);
		if (cfg != null) {
			for (DoomscrollConfig.ChannelEntry e : cfg) {
				if (e != null && e.url != null && !e.url.isBlank()) {
					out.add(new Channel(e.name == null || e.name.isBlank() ? host(e.url) : e.name, e.url.trim()));
				}
			}
		}
		if (out.isEmpty()) {
			for (DoomscrollConfig.ChannelEntry e : defaults()) out.add(new Channel(e.name, e.url));
		}
		return out;
	}

	public static int size() {
		return list().size();
	}

	public static Channel current() {
		List<Channel> l = list();
		if (index < 0 || index >= l.size()) index = 0;
		return l.get(index);
	}

	public static Channel next() {
		int n = size();
		index = (index + 1) % n;
		return current();
	}

	public static Channel prev() {
		int n = size();
		index = (index - 1 + n) % n;
		return current();
	}

	/** Verilen adres bir kanala aitse o kanal (ve sayac oraya alinir); degilse null. */
	@Nullable
	public static Channel match(@Nullable String url) {
		if (url == null || url.isEmpty()) return null;
		List<Channel> l = list();
		String u = url.toLowerCase();
		// once en uzun (en ozel) kanal adresi
		Channel best = null;
		int bestLen = -1;
		for (int i = 0; i < l.size(); i++) {
			String cu = strip(l.get(i).url());
			if (u.contains(cu) && cu.length() > bestLen) {
				best = l.get(i);
				bestLen = cu.length();
				index = i;
			}
		}
		return best;
	}

	/** Kanal adi ya da site adi. */
	public static String nameFor(@Nullable String url, String fallback) {
		Channel c = match(url);
		return c != null ? c.name() : fallback;
	}

	private static String strip(String url) {
		String s = url.toLowerCase().replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
		while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
		return s;
	}

	private static String host(String url) {
		try {
			String h = java.net.URI.create(url.trim()).getHost();
			if (h != null) return h.startsWith("www.") ? h.substring(4) : h;
		} catch (Exception ignored) {
		}
		return url;
	}

	public static boolean add(String name, String url) {
		String u = url == null ? "" : url.trim();
		if (u.isEmpty()) return false;
		if (!u.contains("://")) u = "https://" + u;
		DoomscrollConfig cfg = DoomscrollConfig.get();
		if (cfg.channels == null || cfg.channels.isEmpty()) cfg.channels = defaults();
		cfg.channels.removeIf(e -> e != null && e.name != null && e.name.equalsIgnoreCase(name));
		cfg.channels.add(DoomscrollConfig.ChannelEntry.of(name, u));
		DoomscrollConfig.save();
		return true;
	}

	public static boolean remove(String name) {
		DoomscrollConfig cfg = DoomscrollConfig.get();
		if (cfg.channels == null || cfg.channels.isEmpty()) cfg.channels = defaults();
		boolean ok = cfg.channels.removeIf(e -> e != null && e.name != null && e.name.equalsIgnoreCase(name));
		if (ok) {
			DoomscrollConfig.save();
			index = 0;
		}
		return ok;
	}
}
