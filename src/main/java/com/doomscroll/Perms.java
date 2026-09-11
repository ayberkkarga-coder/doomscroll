package com.doomscroll;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.Permissions;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Izin kontrolu. LuckPerms gibi bir izin yoneticisi varsa (fabric-permissions-api)
 * dugum adiyla sorar; yoksa oyunun kendi izin kumesine duser.
 *
 * <p>Bagimlilik eklemeden calisir: API sinifi yansima ile aranir, bulunamazsa bir kez
 * isaretlenip bir daha denenmez. Izin yoneticisi olmayan sunucuda hicbir maliyeti yok.
 *
 * <p>Dugumler ve izin yoneticisi yokken gecerli olan varsayilanlar:
 * <ul>
 *   <li>{@code doomscroll.place} — ekran koyabilme (varsayilan: herkes)</li>
 *   <li>{@code doomscroll.url} — ekranda adres degistirebilme (varsayilan: herkes)</li>
 *   <li>{@code doomscroll.broadcast} — yayin modu baslatabilme (varsayilan: herkes)</li>
 *   <li>{@code doomscroll.bypass} — engelli adresleri ve sinirlari asabilme (varsayilan: oyun yoneticisi)</li>
 *   <li>{@code doomscroll.admin} — /doomscroll komutlari ve rapor bildirimleri (varsayilan: oyun yoneticisi)</li>
 * </ul>
 */
public final class Perms {
	public static final String PLACE = "doomscroll.place";
	public static final String URL = "doomscroll.url";
	public static final String BROADCAST = "doomscroll.broadcast";
	public static final String BYPASS = "doomscroll.bypass";
	public static final String ADMIN = "doomscroll.admin";

	/** Yonetici islemleri icin oyunun kendi izni (op 2 karsiligi). */
	public static final Permission GAMEMASTER = Permissions.COMMANDS_GAMEMASTER;
	/** "Herkes" anlaminda: izin yoneticisi yoksa serbest. */
	public static final Permission EVERYONE = null;

	private static MethodHandle check;
	private static boolean looked;

	private Perms() {}

	/**
	 * Oyuncunun dugume izni var mi?
	 *
	 * @param fallback izin yoneticisi yokken bakilacak oyun izni; {@link #EVERYONE} ise serbest
	 */
	public static boolean has(ServerPlayer p, String node, @Nullable Permission fallback) {
		boolean vanilla = fallback == null || p.permissions().hasPermission(fallback);
		Boolean api = ask(p, node, vanilla);
		return api != null ? api : vanilla;
	}

	/** fabric-permissions-api varsa sonucu dondurur, yoksa null. */
	@Nullable
	private static Boolean ask(ServerPlayer p, String node, boolean fallback) {
		if (!looked) {
			looked = true;
			try {
				Class<?> c = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
				check = MethodHandles.publicLookup().findStatic(c, "check",
						MethodType.methodType(boolean.class, net.minecraft.world.entity.Entity.class, String.class, boolean.class));
				Doomscroll.LOGGER.info("izin yoneticisi bulundu: doomscroll.* dugumleri kullanilabilir");
			} catch (Throwable ignored) {
				check = null; // izin yoneticisi yok: oyunun kendi izinleriyle devam
			}
		}
		if (check == null) {
			return null;
		}
		try {
			return (boolean) check.invoke((net.minecraft.world.entity.Entity) p, node, fallback);
		} catch (Throwable t) {
			check = null;
			Doomscroll.LOGGER.warn("izin API'si cagrilamadi, oyunun izinlerine dusuluyor: {}", t.toString());
			return null;
		}
	}
}
