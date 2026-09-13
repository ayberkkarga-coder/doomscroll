package com.doomscroll.client;

import com.doomscroll.net.QueueActionPayload;
import com.doomscroll.net.QueueBroadcast;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side copy of the shared video queue.
 *
 * <p>The queue now lives on the server: anyone can add to it, everyone sees the same list, and the next
 * video is <b>the one with the most votes</b>. Everything here either reads the latest list received from
 * the server or sends the server a request; the server always decides.
 */
public final class ScreenQueue {
	private static final Map<BlockPos, List<QueueBroadcast.Row>> MIRROR = new ConcurrentHashMap<>();

	private ScreenQueue() {}

	// ---------- from the server ----------

	public static void apply(QueueBroadcast msg) {
		if (msg.rows().isEmpty()) {
			MIRROR.remove(msg.pos());
		} else {
			MIRROR.put(msg.pos().immutable(), List.copyOf(msg.rows()));
		}
	}

	/** On leaving the world. */
	public static void clearAll() {
		MIRROR.clear();
	}

	// ---------- reading ----------

	/** The queue in playback order (most-voted first). */
	public static List<QueueBroadcast.Row> list(@Nullable BlockPos anchor) {
		if (anchor == null) {
			return List.of();
		}
		List<QueueBroadcast.Row> q = MIRROR.get(anchor.immutable());
		return q == null ? List.of() : q;
	}

	public static int size(@Nullable BlockPos anchor) {
		return list(anchor).size();
	}

	/** Address at the 1-based queue position; empty if there is none. */
	public static String urlAt(@Nullable BlockPos anchor, int index1) {
		List<QueueBroadcast.Row> q = list(anchor);
		return index1 >= 1 && index1 <= q.size() ? q.get(index1 - 1).url() : "";
	}

	/** Display name: the title from the server, otherwise the one we know locally, failing that the domain name. */
	public static String label(QueueBroadcast.Row row) {
		if (!row.title().isEmpty()) {
			return row.title();
		}
		String local = PageTitles.get(row.url());
		return local.isEmpty() ? RemoteScreen.siteName(row.url()) : local;
	}

	// ---------- requests to the server ----------

	private static void send(@Nullable BlockPos anchor, int action, String url, String title) {
		if (anchor == null) {
			return;
		}
		ClientPlayNetworking.send(new QueueActionPayload(anchor.immutable(), action, url, title));
	}

	/** Adds the address to the queue (if the same address is already queued, votes for it instead). False for an invalid address. */
	public static boolean add(@Nullable BlockPos anchor, String input) {
		if (anchor == null) {
			return false;
		}
		String url = Browsers.normalize(input);
		if (url.isEmpty()) {
			return false;
		}
		String title = PageTitles.get(url);
		send(anchor, QueueActionPayload.ADD, url, title.length() > 200 ? title.substring(0, 200) : title);
		return true;
	}

	/** Toggle the vote. */
	public static void vote(@Nullable BlockPos anchor, String url) {
		send(anchor, QueueActionPayload.VOTE, url, "");
	}

	/** Remove from the queue (whoever added it, or the screen owner). */
	public static void remove(@Nullable BlockPos anchor, String url) {
		send(anchor, QueueActionPayload.REMOVE, url, "");
	}

	/** Clear the queue (screen owner or an admin). */
	public static void clear(@Nullable BlockPos anchor) {
		send(anchor, QueueActionPayload.CLEAR, "", "");
	}

	/** Open the most-voted one right away. */
	public static void next(@Nullable BlockPos anchor) {
		send(anchor, QueueActionPayload.NEXT, "", "");
	}

	/** Open a specific video right away. */
	public static void playNow(@Nullable BlockPos anchor, String url) {
		send(anchor, QueueActionPayload.PLAY, url, "");
	}

	/** Request the current list (when the remote opens, when the home page loads). */
	/** Time of the last refresh request (it is called every time the UI is rebuilt; must not flood the server). */
	private static long lastRefreshMs;

	public static void refresh(@Nullable BlockPos anchor) {
		long now = System.currentTimeMillis();
		if (now - lastRefreshMs < 250L) {
			return;
		}
		lastRefreshMs = now;
		send(anchor, QueueActionPayload.REFRESH, "", "");
	}

	// ---------- auto-advance ----------

	/**
	 * Page: the video ended. The client driving the screen asks the server for the next one; the votes
	 * decide which one opens, and everyone switches to the same address.
	 */
	public static void onEnded(ScreenBrowsers.Screen s) {
		if (!s.drives() || size(s.pos) == 0) {
			return;
		}
		next(s.pos);
	}
}
