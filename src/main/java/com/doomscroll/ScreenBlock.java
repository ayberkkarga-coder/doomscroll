package com.doomscroll;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Screen block. Its orientation has two parts: {@link #FACING}, the front face (horizontal on a wall, UP on the floor, DOWN on the ceiling), and
 * {@link #TOP}, the direction of the image's top edge on a floor/ceiling screen (on a wall the top is always up; TOP is ignored).
 * Panel geometry is built everywhere from the same two vectors: right = top x front, extend (the viewer's left) = front x top.
 */
public class ScreenBlock extends BaseEntityBlock {
	public static final MapCodec<ScreenBlock> CODEC = simpleCodec(ScreenBlock::new);
	/** Direction the front face looks toward. Compatible with old worlds: same name and horizontal values, up/down added. */
	public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
	/** Top edge of the image on a floor/ceiling screen (north/south/east/west). Not used on a wall screen. */
	public static final EnumProperty<Direction> TOP = EnumProperty.create("top", Direction.class, Direction.Plane.HORIZONTAL);
	/** A powered-on screen emits light (lights up the room like a TV). Matched to the anchor's power state. */
	public static final BooleanProperty LIT = BlockStateProperties.LIT;

	public ScreenBlock(Properties properties) {
		super(properties);
		registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH).setValue(TOP, Direction.NORTH).setValue(LIT, true));
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, TOP, LIT);
	}

	// ---------- direction helpers (server and client use the same rule) ----------

	/** Direction of the image's top edge: up on a wall, TOP on the floor/ceiling. */
	public static Direction top(BlockState st) {
		Direction f = st.getValue(FACING);
		return f.getAxis().isHorizontal() ? Direction.UP : st.getValue(TOP);
	}

	/** Direction the panel extends from the anchor (the viewer's left) = front x top. On a wall this is the same as facing.getClockWise(). */
	public static Direction extend(BlockState st) {
		return cross(st.getValue(FACING), top(st));
	}

	/** The viewer's right = top x front. Renderer / ray intersection / light all use the same frame. */
	public static Direction right(Direction front, Direction top) {
		return cross(top, front);
	}

	public static Direction cross(Direction a, Direction b) {
		Vec3i u = a.getUnitVec3i();
		Vec3i v = b.getUnitVec3i();
		return Direction.getNearest(
				u.getY() * v.getZ() - u.getZ() * v.getY(),
				u.getZ() * v.getX() - u.getX() * v.getZ(),
				u.getX() * v.getY() - u.getY() * v.getX(),
				Direction.NORTH);
	}

	/** Whether two blocks can merge into the same panel: same block, same front face, same top edge. */
	public static boolean sameOrientation(BlockState a, BlockState b) {
		return a.getBlock() == b.getBlock() && a.getBlock() == Doomscroll.SCREEN_BLOCK
				&& a.getValue(FACING) == b.getValue(FACING) && top(a) == top(b);
	}

	/**
	 * Placement: (1) sneaking while placing on a block's top/bottom face gives a floor/ceiling screen — the top edge is your look
	 * direction on the floor (the far edge), the opposite on the ceiling (tilt your head up to look and the top of your view falls behind you);
	 * (2) in the plane of an adjacent screen it takes that screen's orientation (look direction is irrelevant while extending a panel); (3) otherwise a wall screen facing the player.
	 */
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		Level level = context.getLevel();
		BlockPos pos = context.getClickedPos();
		Direction face = context.getClickedFace();
		Direction look = context.getHorizontalDirection();
		if (context.isSecondaryUseActive() && face.getAxis().isVertical()) {
			return defaultBlockState().setValue(FACING, face)
					.setValue(TOP, face == Direction.UP ? look : look.getOpposite()).setValue(LIT, true);
		}
		for (Direction d : Direction.values()) {
			BlockState n = level.getBlockState(pos.relative(d));
			if (n.getBlock() == this && n.getValue(FACING).getAxis() != d.getAxis()) {
				return n.setValue(LIT, true);
			}
		}
		return defaultBlockState().setValue(FACING, look.getOpposite()).setValue(TOP, Direction.NORTH).setValue(LIT, true);
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new ScreenBlockEntity(pos, state);
	}

	/** Server: the BE ticker (only performs a one-time light/power sync after loading). */
	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		if (level.isClientSide()) {
			return null;
		}
		return createTickerHelper(type, Doomscroll.SCREEN_BE_TYPE, (lvl, pos, st, be) -> be.serverTick());
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, net.minecraft.world.item.ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (!level.isClientSide() && placer instanceof Player p && level.getBlockEntity(pos) instanceof ScreenBlockEntity be) {
			if (refuse(level, pos, p, be)) {
				return;
			}
			be.setOwner(p.getUUID(), p.getName().getString());
			if (level.getBlockEntity(be.getAnchor()) instanceof ScreenBlockEntity anchorBe
					&& anchorBe != be && anchorBe.getOwner() == null) {
				anchorBe.setOwner(p.getUUID(), p.getName().getString());
			}
			if (be.getWidth() * be.getHeight() > 1) {
				p.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("message.doomscroll.screen.panel", be.getWidth(), be.getHeight()));
			}
		}
	}

	/**
	 * Server limits: permission, panel size, number of screens per player.
	 * If a limit is exceeded the block is removed and the item refunded (so nobody builds a lag machine).
	 */
	private static boolean refuse(Level level, BlockPos pos, Player p, ScreenBlockEntity be) {
		ServerConfig sc = ServerConfig.get();
		boolean bypass = !(p instanceof net.minecraft.server.level.ServerPlayer sp)
				|| Perms.has(sp, Perms.BYPASS, Perms.GAMEMASTER);
		net.minecraft.network.chat.Component why = null;
		if (p instanceof net.minecraft.server.level.ServerPlayer sp2 && !Perms.has(sp2, Perms.PLACE, Perms.EVERYONE)) {
			why = net.minecraft.network.chat.Component.translatable("message.doomscroll.no_permission");
		} else if (!bypass && sc.maxPanelBlocks > 0 && be.getWidth() * be.getHeight() > sc.maxPanelBlocks) {
			why = net.minecraft.network.chat.Component.translatable("message.doomscroll.screen.too_big", sc.maxPanelBlocks);
		} else if (!bypass && sc.maxScreensPerPlayer > 0 && ownedBy(p) > sc.maxScreensPerPlayer) {
			why = net.minecraft.network.chat.Component.translatable("message.doomscroll.screen.too_many", sc.maxScreensPerPlayer);
		}
		if (why == null) {
			return false;
		}
		level.removeBlock(pos, false);
		if (!p.getAbilities().instabuild) {
			p.getInventory().placeItemBackInInventory(new net.minecraft.world.item.ItemStack(Doomscroll.SCREEN_BLOCK));
		}
		p.sendOverlayMessage(why);
		return true;
	}

	/** Number of screen blocks the player owns in loaded chunks. */
	private static int ownedBy(Player p) {
		int n = 0;
		for (ScreenBlockEntity s : ScreenBlockEntity.liveOnServer()) {
			if (s.isOwnedBy(p.getUUID())) {
				n++;
			}
		}
		return n;
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		super.onPlace(state, level, pos, oldState, movedByPiston);
		if (oldState.is(this)) {
			return; // only a property changed (light); the layout is unchanged
		}
		if (!level.isClientSide()) {
			ScreenMultiblock.recompute(level, pos, state);
		}
	}

	/** Redstone: when a signal reaches a block of the panel (rising edge), toggle the screen on/off; enabled per screen with /ds redstone. */
	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos, net.minecraft.world.level.block.Block neighborBlock, @Nullable net.minecraft.world.level.redstone.Orientation orientation, boolean movedByPiston) {
		super.neighborChanged(state, level, pos, neighborBlock, orientation, movedByPiston);
		if (level.isClientSide() || !ServerConfig.get().redstoneControl || REDSTONE_BUSY.get()) {
			return;
		}
		if (!(level.getBlockEntity(pos) instanceof ScreenBlockEntity be)) {
			return;
		}
		BlockPos ap = be.getAnchor();
		ScreenBlockEntity a = be.isAnchor() ? be : (level.getBlockEntity(ap) instanceof ScreenBlockEntity x ? x : null);
		if (a == null || !a.isRedstone()) {
			return;
		}
		// A signal arriving at any block of the panel counts
		Direction extend = extend(state);
		Direction up = top(state);
		boolean any = false;
		for (int i = 0; i < a.getWidth() && !any; i++) {
			for (int j = 0; j < a.getHeight() && !any; j++) {
				if (level.hasNeighborSignal(ap.relative(extend, i).relative(up, j))) {
					any = true;
				}
			}
		}
		// Write the signal state first: the neighbor updates triggered by setOn (panel blocks are each other's neighbors)
		// re-enter here; thanks to the up-to-date 'powered' value there is no second toggle.
		boolean rising = any && !a.isPowered();
		a.setPowered(any);
		if (rising && !REDSTONE_BUSY.get()) {
			REDSTONE_BUSY.set(true);
			try {
				a.setOn(!a.isOn()); // rising edge: toggle on/off
			} finally {
				REDSTONE_BUSY.set(false);
			}
		}
	}

	/** Ignore nested neighbor updates during a redstone toggle (same thread). */
	private static final ThreadLocal<Boolean> REDSTONE_BUSY = ThreadLocal.withInitial(() -> false);

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
		ServerQueue.forget(new Doomscroll.ScreenKey(level.dimension(), pos.immutable()));
		ScreenMultiblock.recomputeAround(level, pos, state);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		// Screen interaction is captured on the client side (DirectControl); the block must not swallow the click,
		// so that blocks held in hand can still be placed on/next to the screen.
		return InteractionResult.PASS;
	}
}
