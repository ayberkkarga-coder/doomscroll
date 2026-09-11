package com.doomscroll.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tablet yer imleri ve gecmisi: config/doomscroll-tablet.json. Gecmis, adres degistikce kendiliginden yazilir
 * (ayni adres tekrar acilinca en uste tasinir); yer imleri arac cubugundaki yildizla eklenir/cikarilir.
 */
public final class TabletBookmarks {
	public static final class Entry {
		public String url = "";
		public String title = "";
		public long time = 0L;

		Entry() {}

		Entry(String url, String title, long time) {
			this.url = url;
			this.title = title;
			this.time = time;
		}

		/** Gosterilecek ad: kayitli baslik, yoksa oEmbed/site adi. */
		public String label() {
			return title == null || title.isBlank() ? PageTitles.get(url) : title;
		}
	}

	private static final class Data {
		List<Entry> bookmarks = new ArrayList<>();
		List<Entry> history = new ArrayList<>();
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("doomscroll-tablet.json");
	private static final int MAX_HISTORY = 40;
	private static final int MAX_BOOKMARKS = 30;
	private static Data data;

	private TabletBookmarks() {}

	private static Data data() {
		if (data == null) {
			data = new Data();
			try {
				if (Files.exists(FILE)) {
					Data d = GSON.fromJson(Files.readString(FILE), Data.class);
					if (d != null) {
						if (d.bookmarks != null) data.bookmarks = d.bookmarks;
						if (d.history != null) data.history = d.history;
					}
				}
			} catch (Exception ignored) {
			}
		}
		return data;
	}

	private static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(data()));
		} catch (Exception ignored) {
		}
	}

	/** Kaydedilmeye degmeyen adresler: bos sekme, ic sayfalar, yayin alicisi. */
	static boolean ignorable(String url) {
		if (url == null || url.isBlank()) return true;
		String u = url.toLowerCase(java.util.Locale.ROOT);
		return u.startsWith("about:") || u.startsWith("data:") || u.startsWith("chrome") || u.startsWith("file:") || u.startsWith("doomscroll:");
	}

	public static List<Entry> bookmarks() {
		return List.copyOf(data().bookmarks);
	}

	public static List<Entry> history() {
		return List.copyOf(data().history);
	}

	public static boolean isBookmarked(String url) {
		if (ignorable(url)) return false;
		for (Entry e : data().bookmarks) {
			if (e.url.equals(url)) return true;
		}
		return false;
	}

	/** Yildiz: varsa cikar, yoksa ekler. Eklendiyse true. */
	public static boolean toggleBookmark(String url, String title) {
		if (ignorable(url)) return false;
		List<Entry> list = data().bookmarks;
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i).url.equals(url)) {
				list.remove(i);
				save();
				return false;
			}
		}
		if (list.size() >= MAX_BOOKMARKS) list.remove(list.size() - 1);
		list.add(0, new Entry(url, title == null ? "" : title.trim(), System.currentTimeMillis()));
		save();
		return true;
	}

	/** Ana sayfadan "+ Ekle": yoksa ekler (varsa dokunmaz). */
	public static void addBookmark(String url, String title) {
		if (ignorable(url) || isBookmarked(url)) return;
		toggleBookmark(url, title);
	}

	public static void removeBookmark(String url) {
		if (data().bookmarks.removeIf(e -> e.url.equals(url))) save();
	}

	/** Ziyaret: en uste tasir (ayni adres tekrarlanmaz), basligi gunceller. */
	public static void noteVisit(String url, String title) {
		if (ignorable(url)) return;
		List<Entry> h = data().history;
		Entry found = null;
		for (int i = 0; i < h.size(); i++) {
			if (h.get(i).url.equals(url)) {
				found = h.remove(i);
				break;
			}
		}
		if (found == null) found = new Entry(url, "", 0L);
		if (title != null && !title.isBlank()) found.title = title.trim();
		found.time = System.currentTimeMillis();
		h.add(0, found);
		while (h.size() > MAX_HISTORY) h.remove(h.size() - 1);
		save();
	}

	/** Sayfa basligi sonradan gelince (rapor) gecmis ve yer imi kaydina yazar. */
	public static void updateTitle(String url, String title) {
		if (ignorable(url) || title == null || title.isBlank()) return;
		boolean changed = false;
		for (Entry e : data().history) {
			if (e.url.equals(url) && !title.equals(e.title)) {
				e.title = title.trim();
				changed = true;
			}
		}
		for (Entry e : data().bookmarks) {
			if (e.url.equals(url) && e.title.isBlank()) {
				e.title = title.trim();
				changed = true;
			}
		}
		if (changed) save();
	}

	public static void clearHistory() {
		data().history.clear();
		save();
	}
}
