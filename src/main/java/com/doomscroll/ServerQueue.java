package com.doomscroll;

import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ekran basina paylasilan video sirasi, oylamayla.
 *
 * <p>Eskiden sira her istemcinin kendi belleginde duruyordu; yani "herkes siraya eklesin"
 * aslinda calismiyordu, yalnizca ekrani suren kisinin listesi isliyordu. Artik sunucuda:
 * herkes ekleyebilir, herkes gorur, siradaki video <b>en cok oyu alan</b> olur.
 *
 * <p>Siralama: once oy sayisi (cok olan ustte), esitlikte once eklenen ustte. Ekleyen kisi
 * kendi videosuna otomatik oy vermis sayilir, yani oysuz bir liste eklenme sirasini korur.
 *
 * <p>Oturumluk: diske yazilmaz, sunucu kapaninca gider.
 */
public final class ServerQueue {
	/** Ekran basina en fazla video. */
	public static final int MAX = 50;

	private static final Map<Doomscroll.ScreenKey, List<Entry>> QUEUES = new ConcurrentHashMap<>();
	private static final AtomicLong SEQ = new AtomicLong();

	private ServerQueue() {}

	/** Siradaki bir video. {@code seq} eklenme sirasi, esit oyda bunu kullaniriz. */
	public static final class Entry {
		public final String url;
		public String title;
		public final UUID by;
		public final String byName;
		public final long seq;
		/** Oy veren oyuncular; ekleyen de icinde. */
		public final Set<UUID> votes = new LinkedHashSet<>();

		Entry(String url, String title, UUID by, String byName) {
			this.url = url;
			this.title = title == null ? "" : title;
			this.by = by;
			this.byName = byName == null ? "" : byName;
			this.seq = SEQ.incrementAndGet();
			if (by != null) {
				votes.add(by);
			}
		}

		public int voteCount() {
			return votes.size();
		}
	}

	private static final Comparator<Entry> ORDER =
			Comparator.<Entry>comparingInt(e -> -e.voteCount()).thenComparingLong(e -> e.seq);

	/** Siranin o anki hali, oynatma sirasiyla. */
	public static List<Entry> list(@Nullable Doomscroll.ScreenKey key) {
		if (key == null) {
			return List.of();
		}
		List<Entry> q = QUEUES.get(key);
		if (q == null) {
			return List.of();
		}
		synchronized (q) {
			List<Entry> copy = new ArrayList<>(q);
			copy.sort(ORDER);
			return copy;
		}
	}

	public static int size(@Nullable Doomscroll.ScreenKey key) {
		if (key == null) {
			return 0;
		}
		List<Entry> q = QUEUES.get(key);
		return q == null ? 0 : q.size();
	}

	/**
	 * Siraya ekler. Ayni adres zaten varsa yeni girdi acmaz, o girdiye oy verir:
	 * "ben de bunu istiyorum" demenin dogal yolu.
	 *
	 * @return eklendiyse ya da oy verildiyse true; sira doluysa false
	 */
	public static boolean add(Doomscroll.ScreenKey key, String url, String title, ServerPlayer by) {
		List<Entry> q = QUEUES.computeIfAbsent(key, k -> new ArrayList<>());
		synchronized (q) {
			for (Entry e : q) {
				if (e.url.equals(url)) {
					if (!title.isEmpty() && e.title.isEmpty()) {
						e.title = title;
					}
					e.votes.add(by.getUUID());
					return true;
				}
			}
			if (q.size() >= MAX) {
				return false;
			}
			q.add(new Entry(url, title, by.getUUID(), by.getName().getString()));
			return true;
		}
	}

	/** Oyu ac/kapat. Girdi yoksa false. */
	public static boolean vote(Doomscroll.ScreenKey key, String url, ServerPlayer p) {
		List<Entry> q = QUEUES.get(key);
		if (q == null) {
			return false;
		}
		synchronized (q) {
			for (Entry e : q) {
				if (e.url.equals(url)) {
					if (!e.votes.remove(p.getUUID())) {
						e.votes.add(p.getUUID());
					}
					return true;
				}
			}
		}
		return false;
	}

	/** Siradan cikarir. Yalnizca ekleyen ve yoneticiler silebilir. */
	public static boolean remove(Doomscroll.ScreenKey key, String url, ServerPlayer p, boolean admin) {
		List<Entry> q = QUEUES.get(key);
		if (q == null) {
			return false;
		}
		synchronized (q) {
			for (int i = 0; i < q.size(); i++) {
				Entry e = q.get(i);
				if (e.url.equals(url)) {
					if (!admin && e.by != null && !e.by.equals(p.getUUID())) {
						return false;
					}
					q.remove(i);
					if (q.isEmpty()) {
						QUEUES.remove(key);
					}
					return true;
				}
			}
		}
		return false;
	}

	/** Sirayi bosaltir (ekran sahibi ya da yonetici). */
	public static void clear(Doomscroll.ScreenKey key) {
		QUEUES.remove(key);
	}

	/** Bir sonraki videoyu cikarip dondurur (en cok oy alan); sira bossa null. */
	@Nullable
	public static Entry poll(Doomscroll.ScreenKey key) {
		List<Entry> q = QUEUES.get(key);
		if (q == null) {
			return null;
		}
		synchronized (q) {
			if (q.isEmpty()) {
				QUEUES.remove(key);
				return null;
			}
			Entry best = q.stream().min(ORDER).orElse(null);
			q.remove(best);
			if (q.isEmpty()) {
				QUEUES.remove(key);
			}
			return best;
		}
	}

	/** Belirli bir adresi siradan cikarip dondurur (hemen oynat). */
	@Nullable
	public static Entry take(Doomscroll.ScreenKey key, String url) {
		List<Entry> q = QUEUES.get(key);
		if (q == null) {
			return null;
		}
		synchronized (q) {
			for (int i = 0; i < q.size(); i++) {
				if (q.get(i).url.equals(url)) {
					Entry e = q.remove(i);
					if (q.isEmpty()) {
						QUEUES.remove(key);
					}
					return e;
				}
			}
		}
		return null;
	}

	/** Sunucu kapanirken / dunya bosalirken. */
	public static void clearAll() {
		QUEUES.clear();
	}
}
