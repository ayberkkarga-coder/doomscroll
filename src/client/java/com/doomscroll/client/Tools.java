package com.doomscroll.client;

import com.doomscroll.Doomscroll;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * yt-dlp ve ffmpeg'i config/doomscroll/tools altina kurar (ilk kullanimda resmi kaynaklardan indirir).
 * Indirme yarim kalirsa kaldigi yerden devam eder. Simdilik Windows x64.
 */
public final class Tools {
	public static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("doomscroll");
	public static final Path TOOLS = DIR.resolve("tools");
	public static final Path CACHE = DIR.resolve("cache");

	private static final String YTDLP_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";
	// Sirayla denenir: GitHub CDN cok hizli (olcum: ~22 MB/s), gyan.dev yedek (~0.6 MB/s)
	private static final String[] FFMPEG_ZIP_URLS = {
			"https://github.com/yt-dlp/FFmpeg-Builds/releases/latest/download/ffmpeg-master-latest-win64-gpl.zip",
			"https://github.com/BtbN/FFmpeg-Builds/releases/latest/download/ffmpeg-master-latest-win64-gpl.zip",
			"https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"
	};

	private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();

	private Tools() {}

	public static Path ytdlp() {
		return TOOLS.resolve("yt-dlp.exe");
	}

	public static Path ffmpeg() {
		return TOOLS.resolve("ffmpeg.exe");
	}

	public static boolean ready() {
		return Files.isRegularFile(ytdlp()) && Files.isRegularFile(ffmpeg());
	}

	/** Eksik araclari indirir. Arka plan is parcaciginda cagrilmali. */
	public static void ensure(Consumer<String> status) throws IOException, InterruptedException {
		Files.createDirectories(TOOLS);
		Files.createDirectories(CACHE);

		if (!Files.isRegularFile(ytdlp())) {
			download(YTDLP_URL, ytdlp(), "yt-dlp", status);
		}

		if (!Files.isRegularFile(ffmpeg())) {
			Path zip = TOOLS.resolve("ffmpeg.zip");
			IOException last = null;
			for (String url : FFMPEG_ZIP_URLS) {
				try {
					download(url, zip, "ffmpeg", status);
					last = null;
					break;
				} catch (IOException e) {
					Doomscroll.LOGGER.warn("ffmpeg kaynagi basarisiz, sonraki deneniyor: {} ({})", url, e.getMessage());
					last = e;
				}
			}
			if (last != null) {
				throw last;
			}
			status.accept(Lang.tr("message.doomscroll.ffmpeg_extracting"));
			extractFfmpeg(zip);
			Files.deleteIfExists(zip);
		}
	}

	/** Kaldigi yerden devam eden, ilerleme bildiren indirme. */
	private static void download(String url, Path target, String label, Consumer<String> status) throws IOException, InterruptedException {
		Path part = target.resolveSibling(target.getFileName() + ".part");
		Path src = target.resolveSibling(target.getFileName() + ".src");
		// Yarim parca baska bir kaynaktan kaldiysa isine yaramaz; sil
		if (Files.isRegularFile(part)) {
			String prev = Files.isRegularFile(src) ? Files.readString(src).trim() : "";
			if (!url.equals(prev)) {
				Files.deleteIfExists(part);
			}
		}
		Files.writeString(src, url);
		long have = Files.isRegularFile(part) ? Files.size(part) : 0L;

		HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", "doomscroll-minecraft-mod")
				.GET();
		if (have > 0) {
			rb.header("Range", "bytes=" + have + "-");
		}
		HttpResponse<InputStream> resp = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
		int code = resp.statusCode();
		boolean resume = code == 206 && have > 0;
		if (code / 100 != 2) {
			throw new IOException("HTTP " + code + " - " + url);
		}
		if (!resume) {
			have = 0L;
		}
		long total = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
		if (total > 0) {
			total += have;
		}

		long done = have;
		long lastNotice = 0L;
		try (InputStream in = resp.body();
			 OutputStream out = Files.newOutputStream(part,
					 resume ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
							 : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE})) {
			byte[] buf = new byte[1 << 16];
			int n;
			while ((n = in.read(buf)) > 0) {
				out.write(buf, 0, n);
				done += n;
				long now = System.nanoTime();
				if (now - lastNotice > 700_000_000L) {
					lastNotice = now;
					String pct = total > 0 ? " %" + (int) (done * 100 / total) : "";
					status.accept(label + " indiriliyor" + pct + "  (" + (done / 1_048_576) + (total > 0 ? "/" + (total / 1_048_576) : "") + " MB)");
				}
			}
		}
		Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
		Files.deleteIfExists(src);
		Doomscroll.LOGGER.info("indirildi: {} ({} MB)", target, done / 1_048_576);
	}

	/** Zip icindeki bin/ffmpeg.exe dosyasini cikarir. */
	private static void extractFfmpeg(Path zip) throws IOException {
		boolean found = false;
		try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
			ZipEntry e;
			while ((e = zin.getNextEntry()) != null) {
				String name = e.getName().replace('\\', '/');
				if (!e.isDirectory() && name.endsWith("/bin/ffmpeg.exe")) {
					Files.copy(zin, ffmpeg(), StandardCopyOption.REPLACE_EXISTING);
					found = true;
					break;
				}
			}
		}
		if (!found) {
			throw new IOException("zip icinde bin/ffmpeg.exe bulunamadi");
		}
	}
}
