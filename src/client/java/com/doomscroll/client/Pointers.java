package com.doomscroll.client;

import com.doomscroll.net.ScreenPointerBroadcast;
import com.doomscroll.net.ScreenPointerPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paylasimli isaretci: ekrana bakan oyuncunun crosshair'i digerlerinin ekraninda renkli bir nokta olarak gorunur
 * ("suraya bak" demek icin). Konum panel orani (0..1) olarak sn'de en fazla 10 kez yayinlanir; ekrandan cikinca
 * bir kez "kayboldu" gonderilir. Renk oyuncu kimliginden turetilir.
 */
public final class Pointers {
	public record Pointer(BlockPos pos, UUID player, String name, float u, float v, long stampMs, int color) {}

	private static final long FRESH_MS = 2500L;
	private static final Map<UUID, Pointer> POINTERS = new ConcurrentHashMap<>();
	private static long lastSendMs = 0L;
	private static float lastU = -1f, lastV = -1f;
	@Nullable private static BlockPos lastPos;
	private static int sent = 0;
	private static int echoed = 0;

	/** Duman testi: sunucu kendi imlecimizi geri yansitti. */
	public static void noteEcho() {
		echoed++;
	}

	public static int echoCount() {
		return echoed;
	}

	public static int sentCount() {
		return sent;
	}

	private Pointers() {}

	/** Sunucudan gelen baskasinin imleci. */
	public static void apply(ScreenPointerBroadcast b) {
		if (b.u() < 0 || b.v() < 0) {
			POINTERS.remove(b.player());
			return;
		}
		POINTERS.put(b.player(), new Pointer(b.pos().immutable(), b.player(), b.name(), b.u(), b.v(), System.currentTimeMillis(), colorFor(b.player())));
	}

	/** Bu ekrandaki taze imlecler. */
	public static List<Pointer> at(BlockPos anchor) {
		if (POINTERS.isEmpty()) return List.of();
		long now = System.currentTimeMillis();
		List<Pointer> out = new ArrayList<>(2);
		for (Pointer p : POINTERS.values()) {
			if (now - p.stampMs > FRESH_MS) {
				POINTERS.remove(p.player());
				continue;
			}
			if (p.pos.equals(anchor)) out.add(p);
		}
		return out;
	}

	/** Istemci tick'i: bakilan ekrandaki konumu yayinla (degistiyse 100 ms'de bir, degismediyse sn'de bir). */
	public static void tick(Minecraft mc) {
		if (mc.player == null || mc.getConnection() == null) {
			return;
		}
		long now = System.currentTimeMillis();
		ScreenTracker.Hit h = DoomscrollConfig.get().pointer ? DirectControl.currentHit() : null;
		if (h == null) {
			if (lastPos != null) {
				send(lastPos, -1f, -1f, now);
				lastPos = null;
			}
			return;
		}
		float u = h.px() / (float) Math.max(1, Browsers.screenWidth());
		float v = h.py() / (float) Math.max(1, Browsers.screenHeight());
		boolean moved = Math.abs(u - lastU) > 0.004f || Math.abs(v - lastV) > 0.004f || !h.pos().equals(lastPos);
		if ((moved && now - lastSendMs >= 100L) || now - lastSendMs >= 1000L) {
			send(h.pos(), u, v, now);
		}
	}

	private static void send(BlockPos pos, float u, float v, long now) {
		ClientPlayNetworking.send(new ScreenPointerPayload(pos.immutable(), u, v));
		sent++;
		lastSendMs = now;
		lastU = u;
		lastV = v;
		lastPos = u < 0 ? null : pos.immutable();
	}

	/** Duman testi: dogrudan bir konum gonder. */
	public static void sendTest(BlockPos pos) {
		send(pos, 0.5f, 0.5f, System.currentTimeMillis());
	}

	public static void clear() {
		POINTERS.clear();
		lastPos = null;
		lastU = -1f;
		lastV = -1f;
	}

	/** Oyuncu kimliginden canli, ayirt edilebilir bir renk (HSB, doygun). */
	static int colorFor(UUID id) {
		float hue = ((id.hashCode() & 0x7fffffff) % 360) / 360f;
		return java.awt.Color.HSBtoRGB(hue, 0.85f, 1f) & 0xFFFFFF;
	}
}
