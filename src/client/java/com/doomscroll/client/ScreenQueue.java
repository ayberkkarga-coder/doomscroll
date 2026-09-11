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
 * Paylasilan video sirasinin istemcideki kopyasi.
 *
 * <p>Sira artik sunucuda duruyor: herkes ekleyebilir, herkes ayni listeyi gorur ve siradaki
 * video <b>en cok oyu alan</b> olur. Buradaki her sey ya sunucudan gelen son listeyi okur
 * ya da sunucuya bir istek yollar; karar hep sunucunun.
 */
public final class ScreenQueue {
	private static final Map<BlockPos, List<QueueBroadcast.Row>> MIRROR = new ConcurrentHashMap<>();

	private ScreenQueue() {}

	// ---------- sunucudan gelen ----------

	public static void apply(QueueBroadcast msg) {
		if (msg.rows().isEmpty()) {
			MIRROR.remove(msg.pos());
		} else {
			MIRROR.put(msg.pos().immutable(), List.copyOf(msg.rows()));
		}
	}

	/** Dunyadan cikinca. */
	public static void clearAll() {
		MIRROR.clear();
	}

	// ---------- okuma ----------

	/** Sira, oynatma sirasiyla (en cok oy alan basta). */
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

	/** 1 tabanli sira numarasindaki adres; yoksa bos. */
	public static String urlAt(@Nullable BlockPos anchor, int index1) {
		List<QueueBroadcast.Row> q = list(anchor);
		return index1 >= 1 && index1 <= q.size() ? q.get(index1 - 1).url() : "";
	}

	/** Gosterilecek ad: sunucudaki baslik, yoksa bizim bildigimiz, o da yoksa alan adi. */
	public static String label(QueueBroadcast.Row row) {
		if (!row.title().isEmpty()) {
			return row.title();
		}
		String local = PageTitles.get(row.url());
		return local.isEmpty() ? RemoteScreen.siteName(row.url()) : local;
	}

	// ---------- sunucuya istek ----------

	private static void send(@Nullable BlockPos anchor, int action, String url, String title) {
		if (anchor == null) {
			return;
		}
		ClientPlayNetworking.send(new QueueActionPayload(anchor.immutable(), action, url, title));
	}

	/** Adresi siraya ekler (ayni adres zaten varsa ona oy verir). Gecersiz adreste false. */
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

	/** Oyu ac/kapat. */
	public static void vote(@Nullable BlockPos anchor, String url) {
		send(anchor, QueueActionPayload.VOTE, url, "");
	}

	/** Siradan cikar (ekleyen ya da ekran sahibi). */
	public static void remove(@Nullable BlockPos anchor, String url) {
		send(anchor, QueueActionPayload.REMOVE, url, "");
	}

	/** Sirayi bosalt (ekran sahibi ya da yonetici). */
	public static void clear(@Nullable BlockPos anchor) {
		send(anchor, QueueActionPayload.CLEAR, "", "");
	}

	/** En cok oy alani hemen ac. */
	public static void next(@Nullable BlockPos anchor) {
		send(anchor, QueueActionPayload.NEXT, "", "");
	}

	/** Belirli bir videoyu hemen ac. */
	public static void playNow(@Nullable BlockPos anchor, String url) {
		send(anchor, QueueActionPayload.PLAY, url, "");
	}

	/** Guncel listeyi iste (kumanda acilinca, ana sayfa yuklenince). */
	public static void refresh(@Nullable BlockPos anchor) {
		send(anchor, QueueActionPayload.REFRESH, "", "");
	}

	// ---------- otomatik gecis ----------

	/**
	 * Sayfa: video bitti. Ekrani suren istemci sunucudan siradakini ister; hangisinin
	 * acilacagina oylar karar verir, herkes ayni adrese gecer.
	 */
	public static void onEnded(ScreenBrowsers.Screen s) {
		if (!s.drives() || size(s.pos) == 0) {
			return;
		}
		next(s.pos);
	}
}
