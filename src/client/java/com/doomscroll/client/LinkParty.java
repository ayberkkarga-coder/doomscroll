package com.doomscroll.client;

import com.doomscroll.Doomscroll;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Link partisi: Reels/TikTok linkini indir (yt-dlp), Chromium'un oynatabildigi VP9/WebM'e cevir (ffmpeg),
 * yerel oynatici sayfasinda sirayla oynat.
 */
public final class LinkParty {
	// Sadece TEKIL video linkleri (ana sayfalar tarayicida acilir)
	private static final Pattern MEDIA_LINK = Pattern.compile(
			"(?i)https?://(www\\.|m\\.)?instagram\\.com/(reel|reels|p)/[A-Za-z0-9_-]{5,}"
					+ "|(?i)https?://(www\\.|m\\.)?tiktok\\.com/@[^/\\s]+/video/\\d+"
					+ "|(?i)https?://(www\\.)?tiktok\\.com/t/[A-Za-z0-9]+"
					+ "|(?i)https?://(vm|vt)\\.tiktok\\.com/[A-Za-z0-9]+");

	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "doomscroll-linkparty");
		t.setDaemon(true);
		return t;
	});

	private static final List<Path> playlist = new ArrayList<>();

	private LinkParty() {}

	public static boolean isMediaLink(String url) {
		return MEDIA_LINK.matcher(url.trim()).find();
	}

	public static void clear() {
		synchronized (playlist) {
			playlist.clear();
		}
		Minecraft.getInstance().execute(LinkParty::showPlayer);
		wipeCache();
	}

	/**
	 * Gecici video dosyalarini siler. Oynatma sirasini da bosaltir.
	 * Acik dosya (o an oynayan) silinemezse bir sonraki acilista temizlenir.
	 */
	public static void wipeCache() {
		synchronized (playlist) {
			playlist.clear();
		}
		WORKER.submit(() -> {
			try {
				if (!Files.isDirectory(Tools.CACHE)) {
					return;
				}
				try (var files = Files.list(Tools.CACHE)) {
					files.forEach(p -> {
						for (int attempt = 0; attempt < 5; attempt++) {
							try {
								Files.deleteIfExists(p);
								return;
							} catch (IOException e) {
								try {
									Thread.sleep(300);
								} catch (InterruptedException ignored) {
									return;
								}
							}
						}
						Doomscroll.LOGGER.warn("silinemedi (sonraki acilista denenecek): {}", p);
					});
				}
			} catch (IOException e) {
				Doomscroll.LOGGER.warn("onbellek temizlenemedi", e);
			}
		});
	}

	/** Linki siraya al: indir, cevir, oynat. */
	public static void play(String url) {
		String u = url.trim();
		status(Lang.tr("message.doomscroll.link.queued", shortUrl(u)));
		WORKER.submit(() -> {
			try {
				Tools.ensure(LinkParty::status);
				Path webm = prepare(u);
				synchronized (playlist) {
					if (!playlist.contains(webm)) {
						playlist.add(webm);
					}
				}
				Minecraft.getInstance().execute(LinkParty::showPlayer);
				status(Lang.tr("message.doomscroll.link.ready", shortUrl(u)));
			} catch (Exception e) {
				Doomscroll.LOGGER.error("link partisi hatasi", e);
				chat(Lang.tr("message.doomscroll.link.failed", String.valueOf(e.getMessage())));
			}
		});
	}

	/** Indir + donustur; onbellekte varsa dogrudan doner. */
	private static Path prepare(String url) throws Exception {
		String key = sha1(url).substring(0, 16);
		Path webm = Tools.CACHE.resolve(key + ".webm");
		if (Files.isRegularFile(webm) && Files.size(webm) > 0) {
			return webm;
		}
		Path mp4 = Tools.CACHE.resolve(key + ".mp4");

		status(Lang.tr("message.doomscroll.link.downloading", shortUrl(url)));
		run(List.of(
				Tools.ytdlp().toString(),
				"--no-playlist",
				"--ffmpeg-location", Tools.ffmpeg().toString(),
				"-f", "bv*[height<=720]+ba/b[height<=720]/b",
				"--merge-output-format", "mp4",
				"--force-overwrites",
				"-o", mp4.toString(),
				url
		), "yt-dlp");
		if (!Files.isRegularFile(mp4)) {
			throw new IOException("indirme sonucu dosya yok (yt-dlp)");
		}

		status(Lang.tr("message.doomscroll.link.converting"));
		Path tmp = Tools.CACHE.resolve(key + ".part.webm");
		run(List.of(
				Tools.ffmpeg().toString(),
				"-y", "-hide_banner", "-loglevel", "error",
				"-i", mp4.toString(),
				"-vf", "scale=-2:'min(720,ih)'",
				"-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-row-mt", "1",
				"-b:v", "2500k",
				"-c:a", "libopus", "-b:a", "96k",
				tmp.toString()
		), "ffmpeg");
		Files.move(tmp, webm, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		Files.deleteIfExists(mp4);
		return webm;
	}

	private static void run(List<String> cmd, String name) throws IOException, InterruptedException {
		ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
		Process p = pb.start();
		StringBuilder tail = new StringBuilder();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				Doomscroll.LOGGER.info("[{}] {}", name, line);
				if (tail.length() > 600) {
					tail.setLength(0);
				}
				tail.append(line).append('\n');
			}
		}
		int code = p.waitFor();
		if (code != 0) {
			String t = tail.toString().trim();
			int nl = t.lastIndexOf('\n');
			throw new IOException(name + " hata " + code + ": " + (nl >= 0 ? t.substring(nl + 1) : t));
		}
	}

	/** Oynatici sayfasini yaz ve mevcut listeyle ac (hash degisince sayfa yenilenmez, sadece liste guncellenir). */
	private static void showPlayer() {
		try {
			Path html = Tools.DIR.resolve("player.html");
			if (!Files.isRegularFile(html)) {
				Files.createDirectories(Tools.DIR);
				Files.writeString(html, PLAYER_HTML, StandardCharsets.UTF_8);
			}
			StringBuilder hash = new StringBuilder();
			synchronized (playlist) {
				for (Path p : playlist) {
					if (hash.length() > 0) {
						hash.append('|');
					}
					hash.append(p.toUri().toString());
				}
			}
			String url = html.toUri().toString() + "#" + URLEncoder.encode(hash.toString(), StandardCharsets.UTF_8).replace("+", "%20");
			if (Browsers.getOrCreate() == null) {
				status(Lang.tr("message.doomscroll.chromium_installing", (int) Browsers.initProgress()));
				return;
			}
			Browsers.navigateRaw(url);
		} catch (IOException e) {
			Doomscroll.LOGGER.error("oynatici yazilamadi", e);
		}
	}

	private static void status(String msg) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) {
				mc.player.sendOverlayMessage(Component.literal(msg));
			}
		});
	}

	private static void chat(String msg) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) {
				mc.player.sendSystemMessage(Component.literal(msg));
			}
		});
	}

	private static String shortUrl(String u) {
		String s = u.replaceFirst("^https?://(www\\.)?", "");
		return s.length() > 40 ? s.substring(0, 40) + "…" : s;
	}

	private static String sha1(String s) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
	}

	private static final String PLAYER_HTML = """
			<!doctype html>
			<html><head><meta charset="utf-8"><title>doomscroll</title>
			<style>
			html,body{margin:0;background:#000;height:100%;overflow:hidden}
			video{width:100%;height:100%;object-fit:contain;background:#000}
			#q{position:fixed;top:10px;left:14px;color:#fff;font:15px sans-serif;opacity:.55;pointer-events:none}
			</style></head>
			<body>
			<video id="v" autoplay playsinline></video>
			<div id="q"></div>
			<script>
			let list=[],i=0;
			const v=document.getElementById('v'),q=document.getElementById('q');
			function parse(){
			  const h=decodeURIComponent(location.hash.slice(1));
			  const l=h?h.split('|'):[];
			  const changed=JSON.stringify(l)!==JSON.stringify(list);
			  list=l;
			  if(changed){ if(i>=list.length) i=0; if(!v.src||v.ended||v.paused&&v.currentTime===0) load(); else q.textContent=(i+1)+' / '+list.length; }
			}
			function load(){
			  if(!list.length){ v.removeAttribute('src'); v.load(); q.textContent='sırada video yok — /ds play <link>'; return; }
			  v.src=list[i]; q.textContent=(i+1)+' / '+list.length;
			  v.play().catch(function(){});
			}
			v.addEventListener('ended',function(){ i=(i+1)%list.length; load(); });
			document.addEventListener('click',function(){ if(v.paused) v.play().catch(function(){}); });
			document.addEventListener('keydown',function(e){
			  if(e.key==='ArrowRight'||e.key==='ArrowDown'){ i=(i+1)%list.length; load(); }
			  if(e.key==='ArrowLeft'||e.key==='ArrowUp'){ i=(i-1+list.length)%list.length; load(); }
			  if(e.key===' '){ v.paused?v.play():v.pause(); }
			});
			window.addEventListener('hashchange',parse);
			parse();
			</script>
			</body></html>
			""";
}
