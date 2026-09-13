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
 * Per-screen shared video queue, with voting.
 *
 * <p>The queue used to live in each client's own memory; so "everyone can add to the queue"
 * did not really work, only the list of the person driving the screen was processed. Now on the server:
 * everyone can add, everyone sees it, and the next video is <b>the one with the most votes</b>.
 *
 * <p>Ordering: vote count first (most on top), ties broken by earliest added. The person who adds
 * counts as having voted for their own video automatically, so a list without votes keeps insertion order.
 *
 * <p>Session-only: not written to disk, gone when the server shuts down.
 */
public final class ServerQueue {
	/** Maximum videos per screen. */
	public static final int MAX = 50;

	private static final Map<Doomscroll.ScreenKey, List<Entry>> QUEUES = new ConcurrentHashMap<>();
	private static final AtomicLong SEQ = new AtomicLong();

	private ServerQueue() {}

	/** One video in the queue. {@code seq} is the insertion order; we use it as the tie-breaker on equal votes. */
	public static final class Entry {
		public final String url;
		public String title;
		public final UUID by;
		public final String byName;
		public final long seq;
		/** Players who voted; includes the one who added it. */
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

	/** Current state of the queue, in playback order. */
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
	 * Adds to the queue. If the same URL is already present it does not open a new entry but votes for that one:
	 * the natural way of saying "I want this too".
	 *
	 * @return true if added or voted; false if the queue is full
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

	/** Toggle a vote. False if there is no such entry. */
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

	/** Removes from the queue. Only the one who added it and admins can delete. */
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

	/** Empties the queue (screen owner or admin). */
	public static void clear(Doomscroll.ScreenKey key) {
		QUEUES.remove(key);
	}

	/** Removes and returns the next video (the one with the most votes); null if the queue is empty. */
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

	/** Removes and returns a specific URL from the queue (play now). */
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

	/** Screen broken: its queue goes too (otherwise it piles up for the server's lifetime). */
	public static void forget(Doomscroll.ScreenKey key) {
		QUEUES.remove(key);
	}

	/** On server shutdown / when the world unloads. */
	public static void clearAll() {
		QUEUES.clear();
	}
}
