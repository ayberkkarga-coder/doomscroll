package com.doomscroll.client;

import com.doomscroll.ScreenBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers the screens (on and off) drawn by the renderer. Intersects the view ray with the screen
 * planes and converts the hit into browser pixel coordinates.
 * Screens that are off are used only for remote targeting.
 */
public final class ScreenTracker {
	/** facing = front face, top = top edge of the picture (UP on a wall). */
	public record ScreenInfo(BlockPos pos, Direction facing, Direction top, float width, float height, boolean on, long seenAt) {}

	public record Hit(BlockPos pos, int px, int py, double distance) {}

	private static final long STALE_NANOS = 2_000_000_000L;
	private static final Map<BlockPos, ScreenInfo> SCREENS = new ConcurrentHashMap<>();

	private ScreenTracker() {}

	public static void note(BlockPos pos, Direction facing, Direction top, float width, float height, boolean on) {
		SCREENS.put(pos.immutable(), new ScreenInfo(pos.immutable(), facing, top, width, height, on, System.nanoTime()));
	}

	/** All known screens (for the screen list command). */
	public static java.util.Collection<ScreenInfo> all() {
		return new java.util.ArrayList<>(SCREENS.values());
	}

	public static void forget(BlockPos pos) {
		SCREENS.remove(pos);
	}

	public static void clear() {
		SCREENS.clear();
	}

	/** Is there another screen, other than the given position, that is ON and was drawn within the last maxAgeNanos? */
	public static boolean hasOtherRecent(BlockPos except, long maxAgeNanos) {
		long now = System.nanoTime();
		for (ScreenInfo s : SCREENS.values()) {
			if (s.on() && !s.pos().equals(except) && now - s.seenAt() <= maxAgeNanos) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Is there another screen, other than the given position, that STILL EXISTS IN THE WORLD and is on?
	 * Checks that the block exists rather than the timestamp (so parts of a broken multiblock don't count each other as "live").
	 * Also removes entries that no longer exist.
	 */
	public static boolean hasOtherLive(BlockPos except, net.minecraft.world.level.Level level) {
		boolean found = false;
		for (ScreenInfo s : SCREENS.values()) {
			if (!(level.getBlockEntity(s.pos()) instanceof com.doomscroll.ScreenBlockEntity be) || be.isRemoved()) {
				SCREENS.remove(s.pos());
				continue;
			}
			if (!s.pos().equals(except) && be.isOn()) {
				found = true;
			}
		}
		return found;
	}

	/** Anchor position of the screen nearest to the player (includeOff: count screens that are off as well). */
	@Nullable
	public static BlockPos nearestAnchor(Vec3 from, boolean includeOff) {
		long now = System.nanoTime();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (ScreenInfo s : SCREENS.values()) {
			if (now - s.seenAt() > STALE_NANOS || (!includeOff && !s.on())) {
				continue;
			}
			double d = from.distanceToSqr(Vec3.atCenterOf(s.pos()));
			if (d < bestD) {
				bestD = d;
				best = s.pos();
			}
		}
		return best;
	}

	/**
	 * Casts a ray from the eye position along the look direction; returns the nearest screen intersection.
	 * The geometry must match the quad in ScreenBlockEntityRenderer exactly: local +X = the viewer's right (top x front),
	 * +Y = top of the picture, +Z = front face; panel x in [0.5-w, 0.5], y in [-0.5, -0.5+h], z = 0.503.
	 */
	@Nullable
	public static Hit raycast(Vec3 eye, Vec3 look, double maxDistance, boolean includeOff) {
		long now = System.nanoTime();
		Hit best = null;

		for (ScreenInfo s : SCREENS.values()) {
			if (now - s.seenAt() > STALE_NANOS) {
				SCREENS.remove(s.pos());
				continue;
			}
			if (!includeOff && !s.on()) {
				continue;
			}

			Direction front = s.facing();
			Direction top = s.top();
			Direction right = ScreenBlock.right(front, top);
			double ox = eye.x - (s.pos().getX() + 0.5);
			double oy = eye.y - (s.pos().getY() + 0.5);
			double oz = eye.z - (s.pos().getZ() + 0.5);
			double lx = ox * right.getStepX() + oy * right.getStepY() + oz * right.getStepZ();
			double ly = ox * top.getStepX() + oy * top.getStepY() + oz * top.getStepZ();
			double lz = ox * front.getStepX() + oy * front.getStepY() + oz * front.getStepZ();
			double dx = look.x * right.getStepX() + look.y * right.getStepY() + look.z * right.getStepZ();
			double dy = look.x * top.getStepX() + look.y * top.getStepY() + look.z * top.getStepZ();
			double dz = look.x * front.getStepX() + look.y * front.getStepY() + look.z * front.getStepZ();

			final double planeZ = 0.503;
			if (Math.abs(dz) < 1e-6) {
				continue;
			}
			double t = (planeZ - lz) / dz;
			if (t <= 0 || t > maxDistance) {
				continue;
			}
			double x = lx + dx * t;
			double y = ly + dy * t;

			float w = s.width();
			float h = s.height();
			if (x > 0.5 || x < 0.5 - w || y < -0.5 || y > -0.5 + h) {
				continue;
			}

			double u = 1.0 - (0.5 - x) / w;
			double v = 1.0 - (y + 0.5) / h;
			int px = (int) Math.round(u * Browsers.screenWidth());
			int py = (int) Math.round(v * Browsers.screenHeight());

			if (best == null || t < best.distance()) {
				best = new Hit(s.pos(), px, py, t);
			}
		}
		return best;
	}
}
