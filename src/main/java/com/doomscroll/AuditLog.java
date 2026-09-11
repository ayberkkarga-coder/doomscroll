package com.doomscroll;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Denetim kaydi: hangi oyuncu, ne zaman, nerede, hangi adresi acti.
 * Ekrani "gorulmeyen yuzey" olmaktan cikarip yoneticinin sonradan bakabilecegi bir seye cevirir.
 *
 * Dosya: config/doomscroll-audit.log — satir basina bir olay, sekme ayracli.
 * Son {@link #MEMORY} olay bellekte de tutulur; /doomscroll kayit bunlari gosterir.
 *
 * <p><b>Onemli:</b> adres ve oyuncu adi hicbir zaman bicim dizesi olarak kullanilmaz.
 * Ayni islevi goren baska bir modda "%" iceren bir adres sunucuyu dusurmustu.
 */
public final class AuditLog {
	/** Bellekte tutulan son olay sayisi (komutla gosterilir). */
	private static final int MEMORY = 200;
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("doomscroll-audit.log");
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final Deque<Entry> RECENT = new ArrayDeque<>();
	private static final Object LOCK = new Object();

	/** Adres acildi. */
	public static final String OPEN = "open";
	/** Adres sunucu kurallarinca reddedildi. */
	public static final String BLOCKED = "blocked";
	/** Yayin modu baslatildi. */
	public static final String BROADCAST = "broadcast";
	/** Ekran acildi/kapatildi. */
	public static final String POWER = "power";
	/** Siraya video eklendi. */
	public static final String QUEUE = "queue";
	/** Oyuncu ekrani rapor etti. */
	public static final String REPORT = "report";
	/** Yonetici islemi (acil kapatma vb.). */
	public static final String ADMIN = "admin";

	public record Entry(String time, String player, String action, String where, String detail) {
		public String line() {
			return time + "  " + player + "  " + action + "  " + where + "  " + detail;
		}
	}

	private AuditLog() {}

	/** Ekranla ilgili bir olay. {@code a} null olabilir (ekran bulunamadi). */
	public static void record(@Nullable ServerPlayer p, @Nullable ScreenBlockEntity a, String action, String detail) {
		String where = "-";
		if (a != null) {
			BlockPos pos = a.getBlockPos();
			String dim = p == null ? "" : p.level().dimension().identifier().toString();
			where = (dim.isEmpty() ? "" : dim + " ") + pos.getX() + "," + pos.getY() + "," + pos.getZ();
		}
		String who = p == null ? "-" : p.getName().getString() + " (" + p.getUUID() + ")";
		write(new Entry(ZonedDateTime.now().format(STAMP), who, action, where, clean(detail)));
	}

	/** Ekrani olmayan olay (yonetici islemi). */
	public static void record(@Nullable ServerPlayer p, String action, String detail) {
		record(p, null, action, detail);
	}

	private static void write(Entry e) {
		synchronized (LOCK) {
			RECENT.addLast(e);
			while (RECENT.size() > MEMORY) {
				RECENT.removeFirst();
			}
			if (!ServerConfig.get().auditLog) {
				return;
			}
			try {
				Files.createDirectories(FILE.getParent());
				try (BufferedWriter w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
					w.write(e.time());
					w.write('\t');
					w.write(e.player());
					w.write('\t');
					w.write(e.action());
					w.write('\t');
					w.write(e.where());
					w.write('\t');
					w.write(e.detail());
					w.newLine();
				}
			} catch (IOException ex) {
				// Kayit tutulamiyorsa oyun durmasin; uyariyi bir kez basmak yeterli.
				Doomscroll.LOGGER.warn("denetim kaydi yazilamadi: {}", ex.toString());
			}
		}
	}

	/** Son n olay, eskiden yeniye. */
	public static List<Entry> recent(int n) {
		synchronized (LOCK) {
			List<Entry> all = new ArrayList<>(RECENT);
			int from = Math.max(0, all.size() - Math.max(1, n));
			return List.copyOf(all.subList(from, all.size()));
		}
	}

	public static Path file() {
		return FILE;
	}

	/** Satir sonlarini ve sekmeleri temizler; kayit dosyasinin bicimi bozulmasin. */
	private static String clean(String s) {
		if (s == null || s.isEmpty()) {
			return "-";
		}
		String t = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
		return t.length() > 512 ? t.substring(0, 512) + "..." : t;
	}
}
