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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Audit log: which player opened which address, when and where.
 * Turns the screen from an "unseen surface" into something an admin can look back at later.
 *
 * File: config/doomscroll-audit.log — one event per line, tab-separated.
 * The last {@link #MEMORY} events are also kept in memory; /doomscroll audit shows them.
 *
 * <p><b>Important:</b> the address and the player name are never used as a format string.
 * In another mod serving the same purpose, an address containing "%" crashed the server.
 */
public final class AuditLog {
	/** Number of recent events kept in memory (shown by the command). */
	private static final int MEMORY = 200;
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("doomscroll-audit.log");
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final Deque<Entry> RECENT = new ArrayDeque<>();
	private static final Object LOCK = new Object();
	/** Once the file grows this big it is moved to .1; the log must not grow forever. */
	private static final long MAX_BYTES = 8L * 1024 * 1024;
	/** Write queue: the server thread never touches the file. When it fills up, the oldest entry is dropped. */
	private static final BlockingQueue<Entry> PENDING = new ArrayBlockingQueue<>(4096);
	private static volatile Thread writer;
	private static volatile boolean warned;

	/** An address was opened. */
	public static final String OPEN = "open";
	/** The address was refused by the server rules. */
	public static final String BLOCKED = "blocked";
	/** Broadcast mode was started. */
	public static final String BROADCAST = "broadcast";
	/** A screen was turned on/off. */
	public static final String POWER = "power";
	/** A video was added to the queue. */
	public static final String QUEUE = "queue";
	/** A player reported the screen. */
	public static final String REPORT = "report";
	/** Admin action (emergency shutdown etc.). */
	public static final String ADMIN = "admin";

	public record Entry(String time, String player, String action, String where, String detail) {
		public String line() {
			return time + "  " + player + "  " + action + "  " + where + "  " + detail;
		}
	}

	private AuditLog() {}

	/** An event related to a screen. {@code a} may be null (screen not found). */
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

	/** An event without a screen (admin action). */
	public static void record(@Nullable ServerPlayer p, String action, String detail) {
		record(p, null, action, detail);
	}

	private static void write(Entry e) {
		synchronized (LOCK) {
			RECENT.addLast(e);
			while (RECENT.size() > MEMORY) {
				RECENT.removeFirst();
			}
		}
		if (!ServerConfig.get().auditLog) {
			return;
		}
		ensureWriter();
		if (!PENDING.offer(e)) {
			PENDING.poll(); // queue full: drop the oldest, never make the server wait
			PENDING.offer(e);
		}
	}

	/** Writer thread: starts on the first event, shuts down together with the server. */
	private static void ensureWriter() {
		if (writer != null) {
			return;
		}
		synchronized (LOCK) {
			if (writer != null) {
				return;
			}
			Thread t = new Thread(AuditLog::drainLoop, "doomscroll-audit");
			t.setDaemon(true);
			writer = t;
			t.start();
		}
	}

	private static void drainLoop() {
		while (true) {
			try {
				Entry first = PENDING.take();
				List<Entry> batch = new ArrayList<>();
				batch.add(first);
				PENDING.drainTo(batch, 256);
				append(batch);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (Throwable t) {
				warnOnce(t.toString());
			}
		}
	}

	private static void append(List<Entry> batch) {
		try {
			Files.createDirectories(FILE.getParent());
			rotateIfBig();
			try (BufferedWriter w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
				for (Entry e : batch) {
					w.write(e.time());
					w.write('	');
					w.write(e.player());
					w.write('	');
					w.write(e.action());
					w.write('	');
					w.write(e.where());
					w.write('	');
					w.write(e.detail());
					w.newLine();
				}
			}
		} catch (IOException ex) {
			// If the log cannot be written, the game must not stop.
			warnOnce(ex.toString());
		}
	}

	private static void rotateIfBig() throws IOException {
		if (!Files.exists(FILE) || Files.size(FILE) < MAX_BYTES) {
			return;
		}
		Files.move(FILE, FILE.resolveSibling(FILE.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
	}

	private static void warnOnce(String what) {
		if (!warned) {
			warned = true;
			Doomscroll.LOGGER.warn("audit log could not be written: {}", what);
		}
	}

	/** On server shutdown: write whatever is left in the queue to disk (waits at most two seconds). */
	public static void flush() {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (!PENDING.isEmpty() && System.nanoTime() < deadline) {
			List<Entry> batch = new ArrayList<>();
			PENDING.drainTo(batch, 512);
			if (!batch.isEmpty()) {
				append(batch);
			}
		}
	}

	/** The last n events, oldest to newest. */
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

	/** Strips line breaks and tabs so the log file's format cannot be broken. */
	private static String clean(String s) {
		if (s == null || s.isEmpty()) {
			return "-";
		}
		StringBuilder b = new StringBuilder(Math.min(s.length(), 512));
		for (int i = 0; i < s.length() && b.length() < 512; i++) {
			char c = s.charAt(i);
			// A tab/line break corrupts the log format; § could be used to hide the line
			// in the /doomscroll audit output or to fake an extra line.
			b.append(c == '§' || c < ' ' || c == 127 ? ' ' : c);
		}
		return s.length() > 512 ? b + "..." : b.toString();
	}
}
