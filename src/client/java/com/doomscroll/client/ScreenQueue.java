package com.doomscroll.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Video sirasi (izleme partisi kuyrugu): ekran basina adres listesi. Oynayan video bitince (sayfa "ended" bildirir)
 * ekrani suren istemci siradakini acar; herkes sunucu uzerinden ayni adrese gecer. Oturumluk (diske yazilmaz).
 */
public final class ScreenQueue {
	private static final Map<BlockPos, List<String>> QUEUES = new HashMap<>();
	private static final int MAX = 50;

	private ScreenQueue() {}

	public static List<String> list(@Nullable BlockPos anchor) {
		if (anchor == null) return List.of();
		List<String> q = QUEUES.get(anchor.immutable());
		return q == null ? List.of() : List.copyOf(q);
	}

	public static int size(@Nullable BlockPos anchor) {
		if (anchor == null) return 0;
		List<String> q = QUEUES.get(anchor.immutable());
		return q == null ? 0 : q.size();
	}

	/** Adresi siraya ekler; gecersizse ya da sira doluysa false. */
	public static boolean add(@Nullable BlockPos anchor, String input) {
		if (anchor == null) return false;
		String url = Browsers.normalize(input);
		if (url.isEmpty()) return false;
		List<String> q = QUEUES.computeIfAbsent(anchor.immutable(), k -> new ArrayList<>());
		if (q.size() >= MAX) return false;
		q.add(url);
		return true;
	}

	/** 1 tabanli sira numarasiyla siler. */
	public static boolean remove(@Nullable BlockPos anchor, int index1) {
		if (anchor == null) return false;
		List<String> q = QUEUES.get(anchor.immutable());
		if (q == null || index1 < 1 || index1 > q.size()) return false;
		q.remove(index1 - 1);
		return true;
	}

	public static void clear(@Nullable BlockPos anchor) {
		if (anchor != null) QUEUES.remove(anchor.immutable());
	}

	public static void clearAll() {
		QUEUES.clear();
	}

	/** Siradaki belirli bir videoyu (1 tabanli) hemen acar; listeden cikar. */
	public static boolean playNow(@Nullable BlockPos anchor, int index1) {
		if (anchor == null) return false;
		List<String> q = QUEUES.get(anchor.immutable());
		if (q == null || index1 < 1 || index1 > q.size()) return false;
		String url = q.remove(index1 - 1);
		if (q.isEmpty()) QUEUES.remove(anchor.immutable());
		ScreenBrowsers.requestNavigate(anchor, url);
		return true;
	}

	/** Siradaki adresi ekrana acar (kontrol alinir); sira bossa false. */
	public static boolean next(@Nullable BlockPos anchor) {
		if (anchor == null) return false;
		List<String> q = QUEUES.get(anchor.immutable());
		if (q == null || q.isEmpty()) return false;
		String url = q.remove(0);
		if (q.isEmpty()) QUEUES.remove(anchor.immutable());
		ScreenBrowsers.requestNavigate(anchor, url);
		return true;
	}

	/** Sayfa: video bitti. Ekrani suren istemci siradakine gecer. */
	public static void onEnded(ScreenBrowsers.Screen s) {
		if (!s.drives() || size(s.pos) == 0) return;
		int left = size(s.pos) - 1;
		if (next(s.pos)) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) {
				mc.player.sendOverlayMessage(left > 0
						? Component.translatable("message.doomscroll.queue.next_n", left)
						: Component.translatable("message.doomscroll.queue.next"));
			}
		}
	}
}
