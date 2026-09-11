package com.doomscroll.client;

import com.doomscroll.Doomscroll;
import com.doomscroll.cef.api.CefBrowserView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ekran isigi (ambilight): ekranin onundeki yuzeylere, ekranin o bolgesindeki renkle (uzaklastikca tum ekranin
 * ortalamasiyla karisarak) yari saydam, kendinden aydinlik isik yamalari cizilir. Boylece oda ekrandaki goruntuye
 * gore renklenir; shader paketleriyle de calisir (ekranin kendisiyle ayni "emissive" cizim yolu). Yuzey listesi
 * arka planda 2 sn'de bir hesaplanir (blok taramasi + gorus cizgisi, duvar arkasina isik sizmaz); renkler her
 * karede tarayicinin kaba renk haritasindan alinir.
 */
public final class ScreenGlow {
	private static final Logger LOGGER = LoggerFactory.getLogger("doomscroll-isik");

	/**
	 * Tek isik yamasi: anchor blok kosesine gore 4 kose (12 float), ekran kutucugu, agirlik 0..1, uzaklik (blok), yuz yonu.
	 * Yumusak isik icin kose basina en fazla 4 komsu yuzun (kutucuk, agirlik, karisim kovasi) kaydi: indeks kose*4+k, -1 = bos.
	 */
	public record Patch(float[] v, int tile, float weight, float dist, Direction dir, int[] vTiles, float[] vWeights, byte[] vMix) {}

	public static final int MIX_BUCKETS = 8;

	/** Uzakliga gore kenar rengi / ekran ortalamasi karisim orani, kovaya yuvarlanmis (renk tablosu indeksi). */
	public static int mixBucket(float dist) {
		float mix = Math.max(0.3f, Math.min(0.85f, dist / 5f));
		return Math.round((mix - 0.3f) / 0.55f * (MIX_BUCKETS - 1));
	}

	public static float mixOfBucket(int b) {
		return 0.3f + 0.55f * b / (MIX_BUCKETS - 1);
	}

	private record Entry(List<Patch> patches, long computedAt, long computeMs) {}

	private static final Map<BlockPos, Entry> CACHE = new ConcurrentHashMap<>();
	private static final Set<BlockPos> PENDING = ConcurrentHashMap.newKeySet();
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "doomscroll-glow");
		t.setDaemon(true);
		return t;
	});
	private static final int MAX_PATCHES = 2500;
	private static final long REFRESH_MS = 2000;
	private static final float EPS = 0.004f;

	private ScreenGlow() {}

	/** Render is parcacigindan: guncel yamalar (henuz yoksa bos); gerekiyorsa arka planda yeniden hesap baslatir. */
	public static List<Patch> patches(BlockPos anchor, Direction facing, Direction top, float w, float h) {
		Entry e = CACHE.get(anchor);
		long now = System.currentTimeMillis();
		if ((e == null || now - e.computedAt > REFRESH_MS) && PENDING.add(anchor.immutable())) {
			final ClientLevel level = Minecraft.getInstance().level;
			final int range = DoomscrollConfig.get().screenGlowRange;
			final BlockPos a = anchor.immutable();
			final int before = e == null ? -1 : e.patches().size();
			WORKER.submit(() -> {
				List<Patch> list = List.of();
				long t0 = System.nanoTime();
				try {
					if (level != null) {
						list = compute(level, a, facing, top, w, h, range);
					}
				} catch (Throwable t) {
					LOGGER.warn("ekran isigi hesaplanamadi ({})", a.toShortString(), t);
					list = List.of();
				} finally {
					long ms = (System.nanoTime() - t0) / 1_000_000;
					if (list.size() != before) {
						LOGGER.info("[isik] {}: {} yama, {} ms (menzil {})", a.toShortString(), list.size(), ms, range);
					}
					CACHE.put(a, new Entry(list, System.currentTimeMillis(), ms));
					PENDING.remove(a);
				}
			});
		}
		return e == null ? List.of() : e.patches();
	}

	public static void clear() {
		CACHE.clear();
		PENDING.clear();
	}

	/**
	 * Parlakligi algisal egriyle yukseltir, renk tonunu ve doygunlugu korur (kanal kanal egri renkleri griye cekiyordu).
	 * Koyu renkler (parlaklik < 0.03) odayi aydinlatmaz.
	 */
	public static void boostRgb(float r, float g, float b, float[] out, int o) {
		float lum = 0.299f * r + 0.587f * g + 0.114f * b;
		if (lum < 0.03f) {
			out[o] = 0f;
			out[o + 1] = 0f;
			out[o + 2] = 0f;
			return;
		}
		float k = (float) (Math.pow(lum, 0.6) / lum);
		out[o] = Math.min(1f, r * k);
		out[o + 1] = Math.min(1f, g * k);
		out[o + 2] = Math.min(1f, b * k);
	}

	/** Algilanan parlaklik: koyu renkler odayi aydinlatmaz, orta tonlar belirgin gorunsun. */
	public static float boost(float c) {
		if (c < 0.03f) {
			return 0f;
		}
		return (float) Math.min(1.0, Math.pow(c, 0.65));
	}

	/** /ds isik icin durum metni. */
	/** Hesaplanmis isik yamasi sayisi (dilden bagimsiz; duman testi bunu kullanir). */
	public static int patchCount(@Nullable BlockPos anchor) {
		if (anchor == null) return -1;
		Entry e = CACHE.get(anchor);
		return e == null ? -1 : e.patches().size();
	}

	public static String info(@Nullable BlockPos anchor) {
		if (anchor == null) {
			return Lang.tr("command.doomscroll.glow.no_screen");
		}
		Entry e = CACHE.get(anchor);
		StringBuilder sb = new StringBuilder();
		sb.append(anchor.toShortString()).append(": ");
		sb.append(e == null ? Lang.tr("command.doomscroll.glow.not_computed")
				: Lang.tr("command.doomscroll.glow.patches", e.patches().size(), e.computeMs()));
		CefBrowserView b = ScreenBrowsers.browserAt(anchor);
		float[] t = b == null ? null : b.lightTiles();
		if (t == null) {
			sb.append(Lang.tr("command.doomscroll.glow.no_colors"));
		} else {
			float r = 0, g = 0, bl = 0;
			int n = t.length / 3;
			for (int i = 0; i < n; i++) {
				r += t[i * 3];
				g += t[i * 3 + 1];
				bl += t[i * 3 + 2];
			}
			sb.append(Lang.tr("command.doomscroll.glow.average"))
					.append(String.format(java.util.Locale.ROOT, " r%.2f g%.2f b%.2f", r / n, g / n, bl / n));
		}
		return sb.toString();
	}

	/**
	 * Yerel cerceve: F = ekranin baktigi yon, R = ekranin sagi (yerel +X), U = yukari; dunya = C0 + R*x + U*y + F*z.
	 * Panel: x in [0.5-w, 0.5], y in [-0.5, -0.5+h], z = 0.5 (ScreenBlockEntityRenderer ile ayni duzlem).
	 */
	private static List<Patch> compute(ClientLevel level, BlockPos anchor, Direction facing, Direction top, float w, float h, int range) {
		return withVertexBlend(scan(level, anchor, facing, top, w, h, range));
	}

	/** Ayni yonlu komsu yuzlerin ortak koselerine komsularin (kutucuk, agirlik) kayitlarini yazar: kesintisiz gecis icin. */
	private static List<Patch> withVertexBlend(List<Patch> in) {
		Map<Long, float[]> acc = new HashMap<>(in.size() * 3);
		for (Patch p : in) {
			for (int j = 0; j < 4; j++) {
				float[] a = acc.computeIfAbsent(vkey(p.dir(), p.v()[j * 3], p.v()[j * 3 + 1], p.v()[j * 3 + 2]), k -> new float[13]);
				int n = (int) a[0];
				if (n < 4) {
					a[1 + n * 3] = p.tile();
					a[2 + n * 3] = p.weight();
					a[3 + n * 3] = mixBucket(p.dist());
					a[0] = n + 1;
				}
			}
		}
		List<Patch> out = new ArrayList<>(in.size());
		for (Patch p : in) {
			int[] vt = new int[16];
			float[] vw = new float[16];
			byte[] vm = new byte[16];
			Arrays.fill(vt, -1);
			for (int j = 0; j < 4; j++) {
				float[] a = acc.get(vkey(p.dir(), p.v()[j * 3], p.v()[j * 3 + 1], p.v()[j * 3 + 2]));
				int n = a == null ? 0 : (int) a[0];
				for (int k = 0; k < n; k++) {
					vt[j * 4 + k] = (int) a[1 + k * 3];
					vw[j * 4 + k] = a[2 + k * 3];
					vm[j * 4 + k] = (byte) a[3 + k * 3];
				}
			}
			out.add(new Patch(p.v(), p.tile(), p.weight(), p.dist(), p.dir(), vt, vw, vm));
		}
		return out;
	}

	private static long vkey(Direction d, float x, float y, float z) {
		long xq = Math.round(x) + 0x8000L, yq = Math.round(y) + 0x8000L, zq = Math.round(z) + 0x8000L;
		return ((long) d.ordinal() << 48) | (xq << 32) | (yq << 16) | zq;
	}

	private static List<Patch> scan(ClientLevel level, BlockPos anchor, Direction facing, Direction top, float w, float h, int range) {
		// F = on yuz, U = resmin ustu, R = bakanin sagi (ust x on); yer/tavan ekraninda U yatay, F dikeydir
		Vec3 f = facing.getUnitVec3();
		Vec3 u = top.getUnitVec3();
		Vec3 r = com.doomscroll.ScreenBlock.right(facing, top).getUnitVec3();
		Vec3 c0 = Vec3.atCenterOf(anchor);
		double xMin = 0.5 - w, xMax = 0.5, yMin = -0.5, yMax = -0.5 + h;

		// tarama kutusu (yerel) -> dunya AABB
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		double[] lxs = {xMin - range, xMax + range}, lys = {yMin - range, yMax + range}, lzs = {0.5, 0.5 + range};
		for (double lx : lxs) {
			for (double ly : lys) {
				for (double lz : lzs) {
					double wx = c0.x + r.x * lx + u.x * ly + f.x * lz;
					double wy = c0.y + r.y * lx + u.y * ly + f.y * lz;
					double wz = c0.z + r.z * lx + u.z * ly + f.z * lz;
					minX = Math.min(minX, wx); maxX = Math.max(maxX, wx);
					minY = Math.min(minY, wy); maxY = Math.max(maxY, wy);
					minZ = Math.min(minZ, wz); maxZ = Math.max(maxZ, wz);
				}
			}
		}
		int bx0 = (int) Math.floor(minX), bx1 = (int) Math.floor(maxX);
		int by0 = Math.max(level.getMinY(), (int) Math.floor(minY)), by1 = Math.min(level.getMaxY(), (int) Math.floor(maxY));
		int bz0 = (int) Math.floor(minZ), bz1 = (int) Math.floor(maxZ);

		List<Patch> out = new ArrayList<>();
		BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos np = new BlockPos.MutableBlockPos();
		double rangeSq = (double) range * range;
		int cols = CefBrowserView.LIGHT_COLS, rows = CefBrowserView.LIGHT_ROWS;

		for (int bx = bx0; bx <= bx1; bx++) {
			for (int by = by0; by <= by1; by++) {
				for (int bz = bz0; bz <= bz1; bz++) {
					mp.set(bx, by, bz);
					BlockState st = level.getBlockState(mp);
					if (st.isAir() || !st.canOcclude() || st.getBlock() == Doomscroll.SCREEN_BLOCK) {
						continue;
					}
					for (Direction d : Direction.values()) {
						// yuz merkezi (dunya) ve yerel koordinatlari
						double fx = bx + 0.5 + d.getStepX() * 0.5, fy = by + 0.5 + d.getStepY() * 0.5, fz = bz + 0.5 + d.getStepZ() * 0.5;
						double px = fx - c0.x, py = fy - c0.y, pz = fz - c0.z;
						double lx = px * r.x + py * r.y + pz * r.z;
						double ly = px * u.x + py * u.y + pz * u.z;
						double lz = px * f.x + py * f.y + pz * f.z;
						if (lz <= 0.5) {
							continue; // ekran duzleminin onunde degil
						}
						np.setWithOffset(mp, d);
						if (level.getBlockState(np).canOcclude()) {
							continue; // yuz kapali
						}
						// en yakin panel noktasi: menzil siniri ve uzaklik icin
						double qx = clamp(lx, xMin, xMax), qy = clamp(ly, yMin, yMax);
						double ndx = lx - qx, ndy = ly - qy, dz = lz - 0.5;
						double nearSq = ndx * ndx + ndy * ndy + dz * dz;
						if (nearSq > rangeSq || nearSq < 0.04) {
							continue;
						}
						double near = Math.sqrt(nearSq);
						// yuz normali (yerel)
						double nx = d.getStepX() * r.x + d.getStepY() * r.y + d.getStepZ() * r.z;
						double ny = d.getStepX() * u.x + d.getStepY() * u.y + d.getStepZ() * u.z;
						double nz = d.getStepX() * f.x + d.getStepY() * f.y + d.getStepZ() * f.z;
						// Alan isigi: her kutucugun merkezinden gelen isinim toplanir (kenara bitisik zemin/duvar
						// en yakin noktadan sifir alsa da panelin yukarisindan/uzagindan isik alir)
						double sum = 0, best = 0, bqx = 0, bqy = 0;
						int bestTile = -1;
						for (int tr = 0; tr < rows; tr++) {
							double sy = -0.5 + (1.0 - (tr + 0.5) / rows) * h;
							for (int tc = 0; tc < cols; tc++) {
								double sx = 0.5 - (1.0 - (tc + 0.5) / cols) * w;
								double dx = lx - sx, dy = ly - sy;
								double d2 = dx * dx + dy * dy + dz * dz;
								double dist = Math.sqrt(d2);
								double cosFace = (-dx * nx - dy * ny - dz * nz) / dist; // yuz bu kutucugu goruyor mu
								if (cosFace <= 0) {
									continue;
								}
								double cosEmit = dz / dist; // kutucuk bu yuze dogru yayiyor mu
								double c = cosFace * cosEmit / (d2 + 0.25);
								sum += c;
								if (c > best) {
									best = c;
									bestTile = tr * cols + tc;
									bqx = sx;
									bqy = sy;
								}
							}
						}
						if (bestTile < 0) {
							continue;
						}
						double irradiance = sum * ((double) w * h / (cols * rows));
						double fall = 1.0 - near / range;
						// ton esleme: yandan/yatay gelen zayif isik da gorunsun, karsidan gelen guclu isik 1'e yaklassin
						double weight = (1.0 - Math.exp(-1.2 * irradiance)) * fall;
						if (weight < 0.02) {
							continue;
						}
						// gorus cizgisi: en guclu kutucugun merkezine; arada blok varsa isik sizmasin
						if (near > 1.2) {
							Vec3 from = new Vec3(fx + d.getStepX() * 0.05, fy + d.getStepY() * 0.05, fz + d.getStepZ() * 0.05);
							Vec3 to = new Vec3(c0.x + r.x * bqx + u.x * bqy + f.x * 0.56, c0.y + r.y * bqx + u.y * bqy + f.y * 0.56, c0.z + r.z * bqx + u.z * bqy + f.z * 0.56);
							BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
							if (hit.getType() == HitResult.Type.BLOCK && hit.getLocation().distanceToSqr(to) > 0.06) {
								continue;
							}
						}
						out.add(new Patch(faceQuad(bx - anchor.getX(), by - anchor.getY(), bz - anchor.getZ(), d), bestTile, (float) weight, (float) near, d, null, null, null));
						if (out.size() >= MAX_PATCHES) {
							return out;
						}
					}
				}
			}
		}
		return out;
	}

	/** Blogun d yuzundeki kare (anchor kosesine gore), yuzeyin biraz onunde; sira: sag-alt, sol-alt, sol-ust, sag-ust. */
	private static float[] faceQuad(int ox, int oy, int oz, Direction d) {
		float cx = ox + 0.5f + d.getStepX() * (0.5f + EPS);
		float cy = oy + 0.5f + d.getStepY() * (0.5f + EPS);
		float cz = oz + 0.5f + d.getStepZ() * (0.5f + EPS);
		// R' x U' = d olacak sekilde yerel eksenler
		float rx, ry, rz, ux, uy, uz;
		switch (d) {
			case SOUTH -> { rx = 1; ry = 0; rz = 0; ux = 0; uy = 1; uz = 0; }
			case NORTH -> { rx = -1; ry = 0; rz = 0; ux = 0; uy = 1; uz = 0; }
			case EAST -> { rx = 0; ry = 0; rz = -1; ux = 0; uy = 1; uz = 0; }
			case WEST -> { rx = 0; ry = 0; rz = 1; ux = 0; uy = 1; uz = 0; }
			case UP -> { rx = 1; ry = 0; rz = 0; ux = 0; uy = 0; uz = -1; }
			default -> { rx = 1; ry = 0; rz = 0; ux = 0; uy = 0; uz = 1; } // DOWN
		}
		return new float[] {
				cx + (rx - ux) * 0.5f, cy + (ry - uy) * 0.5f, cz + (rz - uz) * 0.5f,
				cx + (-rx - ux) * 0.5f, cy + (-ry - uy) * 0.5f, cz + (-rz - uz) * 0.5f,
				cx + (-rx + ux) * 0.5f, cy + (-ry + uy) * 0.5f, cz + (-rz + uz) * 0.5f,
				cx + (rx + ux) * 0.5f, cy + (ry + uy) * 0.5f, cz + (rz + uz) * 0.5f,
		};
	}

	private static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}
}
