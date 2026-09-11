package com.doomscroll;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Ayni yone bakan (ayni on yuz + ayni ust kenar) bitisik ekran bloklarini dikdortgenlere boler. Duvar, yer ve
 * tavan ekrani ayni kodla: panel duzlemi "uzama" (bakanin solu) ve "ust" vektorleriyle taranir.
 * Her dikdortgenin "ana blogu" (anchor) sag-alt kosedir (bakan kisiye gore); goruntuyu o cizer.
 */
public final class ScreenMultiblock {
	private static final int MAX_COMPONENT = 4096;

	private ScreenMultiblock() {}

	private static boolean sameScreen(Level level, BlockPos pos, BlockState ref) {
		return ScreenBlock.sameOrientation(level.getBlockState(pos), ref);
	}

	private static int along(BlockPos pos, Direction dir) {
		return dir.getAxis().choose(pos.getX(), pos.getY(), pos.getZ()) * dir.getAxisDirection().getStep();
	}

	/** origin'dan baslayarak bagli tum ekran bloklarini bulur ve dikdortgenlere atar. Sunucuda cagrilir. */
	public static void recompute(Level level, BlockPos origin, BlockState ref) {
		if (level.isClientSide() || ref.getBlock() != Doomscroll.SCREEN_BLOCK) {
			return;
		}
		if (!sameScreen(level, origin, ref)) {
			return;
		}
		Direction extend = ScreenBlock.extend(ref);
		Direction up = ScreenBlock.top(ref);
		Direction back = extend.getOpposite();
		Direction down = up.getOpposite();

		// 1) Bagli bileseni topla (BFS, ekran duzleminde 4 komsu)
		Set<BlockPos> component = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(origin.immutable());
		component.add(origin.immutable());
		while (!queue.isEmpty() && component.size() < MAX_COMPONENT) {
			BlockPos p = queue.poll();
			for (BlockPos n : new BlockPos[]{p.relative(extend), p.relative(back), p.relative(up), p.relative(down)}) {
				if (!component.contains(n) && sameScreen(level, n, ref)) {
					component.add(n.immutable());
					queue.add(n.immutable());
				}
			}
		}

		// 2) Kalanlardan sirayla dikdortgen kes: en alt satir (ust'e gore en kucuk), sonra en sagdaki (uzama'ya gore en kucuk) blok anchor olur
		Set<BlockPos> remaining = new HashSet<>(component);
		while (!remaining.isEmpty()) {
			BlockPos anchor = null;
			for (BlockPos p : remaining) {
				if (anchor == null
						|| along(p, up) < along(anchor, up)
						|| (along(p, up) == along(anchor, up) && along(p, extend) < along(anchor, extend))) {
					anchor = p;
				}
			}

			int w = 1;
			while (remaining.contains(anchor.relative(extend, w))) {
				w++;
			}
			int h = 1;
			boolean rowOk = true;
			while (rowOk) {
				for (int i = 0; i < w; i++) {
					if (!remaining.contains(anchor.relative(extend, i).relative(up, h))) {
						rowOk = false;
						break;
					}
				}
				if (rowOk) {
					h++;
				}
			}

			for (int i = 0; i < w; i++) {
				for (int j = 0; j < h; j++) {
					BlockPos p = anchor.relative(extend, i).relative(up, j);
					remaining.remove(p);
					BlockEntity be = level.getBlockEntity(p);
					if (be instanceof ScreenBlockEntity sbe) {
						sbe.setLayout(anchor, w, h);
					}
				}
			}
			// Panelin isigi anchor'un guc durumuna esitlenir (birlesen parcalar da ayni yanar/soner)
			if (level.getBlockEntity(anchor) instanceof ScreenBlockEntity abe) {
				applyLit(level, anchor, w, h, abe.isOn());
			}
		}
	}

	/** Panelin tum bloklarinda LIT ozelligini ayarlar (acik ekran isik yayar). Sunucuda cagrilir. */
	public static void applyLit(Level level, BlockPos anchor, int w, int h, boolean lit) {
		if (level.isClientSide()) {
			return;
		}
		BlockState as = level.getBlockState(anchor);
		if (as.getBlock() != Doomscroll.SCREEN_BLOCK) {
			return;
		}
		Direction extend = ScreenBlock.extend(as);
		Direction up = ScreenBlock.top(as);
		for (int i = 0; i < w; i++) {
			for (int j = 0; j < h; j++) {
				BlockPos p = anchor.relative(extend, i).relative(up, j);
				BlockState st = level.getBlockState(p);
				if (st.getBlock() == Doomscroll.SCREEN_BLOCK && st.getValue(ScreenBlock.LIT) != lit) {
					level.setBlock(p, st.setValue(ScreenBlock.LIT, lit), 3);
				}
			}
		}
	}

	/** Kaldirilan blogun komsularini yeniden hesaplar (blok zaten silinmis durumda; removedState eski durumu). */
	public static void recomputeAround(Level level, BlockPos removed, BlockState removedState) {
		if (removedState.getBlock() != Doomscroll.SCREEN_BLOCK) {
			return;
		}
		Direction extend = ScreenBlock.extend(removedState);
		Direction up = ScreenBlock.top(removedState);
		for (BlockPos n : new BlockPos[]{removed.relative(extend), removed.relative(extend.getOpposite()), removed.relative(up), removed.relative(up.getOpposite())}) {
			if (sameScreen(level, n, removedState)) {
				recompute(level, n, removedState);
			}
		}
	}
}
