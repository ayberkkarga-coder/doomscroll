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
 * Ekran blogu. Yonu iki parcadan olusur: {@link #FACING} on yuz (duvarda yatay, yerde UP, tavanda DOWN) ve
 * {@link #TOP} yer/tavan ekraninda resmin ust kenarinin yonu (duvarda ust hep yukari; TOP yok sayilir).
 * Panel geometrisi her yerde ayni iki vektorle kurulur: sag = ust x on, uzama (bakanin solu) = on x ust.
 */
public class ScreenBlock extends BaseEntityBlock {
	public static final MapCodec<ScreenBlock> CODEC = simpleCodec(ScreenBlock::new);
	/** On yuzun baktigi yon. Eski dunyalarla uyumlu: ad ve yatay degerler ayni, up/down eklendi. */
	public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
	/** Yer/tavan ekraninda resmin ust kenari (kuzey/guney/dogu/bati). Duvar ekraninda kullanilmaz. */
	public static final EnumProperty<Direction> TOP = EnumProperty.create("top", Direction.class, Direction.Plane.HORIZONTAL);
	/** Acik ekran isik yayar (odayi TV gibi aydinlatir). Anchor'un guc durumuyla eslenir. */
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

	// ---------- yon yardimcilari (sunucu ve istemci ayni kurali kullanir) ----------

	/** Resmin ust kenarinin yonu: duvarda yukari, yer/tavanda TOP. */
	public static Direction top(BlockState st) {
		Direction f = st.getValue(FACING);
		return f.getAxis().isHorizontal() ? Direction.UP : st.getValue(TOP);
	}

	/** Panelin anchor'dan uzadigi yon (bakan kisinin solu) = on x ust. Duvarda facing.getClockWise() ile ayni. */
	public static Direction extend(BlockState st) {
		return cross(st.getValue(FACING), top(st));
	}

	/** Bakan kisinin sagi = ust x on. Renderer / isin kesisimi / isik ayni cerceveyi kullanir. */
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

	/** Ayni panelde birlesebilir mi: ayni blok, ayni on yuz, ayni ust kenar. */
	public static boolean sameOrientation(BlockState a, BlockState b) {
		return a.getBlock() == b.getBlock() && a.getBlock() == Doomscroll.SCREEN_BLOCK
				&& a.getValue(FACING) == b.getValue(FACING) && top(a) == top(b);
	}

	/**
	 * Yerlestirme: (1) egilerek bir blogun ust/alt yuzune koyunca yer/tavan ekrani — ust kenar yerde baktigin yon
	 * (uzak kenar), tavanda tersi (kafani kaldirip bakinca gorusun ustu arkana duser); (2) bitisik bir ekranin
	 * duzlemindeyse onun yonunu alir (panel uzatirken bakis yonu onemsiz); (3) yoksa duvar ekrani, oyuncuya bakar.
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

	/** Sunucu: BE tick'i (yalnizca yuklenince tek seferlik isik/guc esitlemesi yapar). */
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
			be.setOwner(p.getUUID(), p.getName().getString());
			if (be.getWidth() * be.getHeight() > 1) {
				p.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("message.doomscroll.screen.panel", be.getWidth(), be.getHeight()));
			}
		}
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		super.onPlace(state, level, pos, oldState, movedByPiston);
		if (oldState.is(this)) {
			return; // yalnizca ozellik degisti (isik); yerlesim ayni
		}
		if (!level.isClientSide()) {
			ScreenMultiblock.recompute(level, pos, state);
		}
	}

	/** Redstone: panelin bir bloguna sinyal geldiginde (yukselen kenar) ekrani ac/kapat; ekran basina /ds redstone ile acilir. */
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
		// Panelin herhangi bir bloguna gelen sinyal sayilir
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
		// Once sinyal durumunu yaz: setOn'un tetikledigi komsu guncellemeleri (panel bloklari birbirinin komsusu)
		// yeniden buraya girer; guncel 'powered' degeri sayesinde ikinci kez toggle olmaz.
		boolean rising = any && !a.isPowered();
		a.setPowered(any);
		if (rising && !REDSTONE_BUSY.get()) {
			REDSTONE_BUSY.set(true);
			try {
				a.setOn(!a.isOn()); // yukselen kenar: ac/kapat
			} finally {
				REDSTONE_BUSY.set(false);
			}
		}
	}

	/** Redstone toggle sirasinda ic ice komsu guncellemelerini yoksay (ayni is parcacigi). */
	private static final ThreadLocal<Boolean> REDSTONE_BUSY = ThreadLocal.withInitial(() -> false);

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
		ScreenMultiblock.recomputeAround(level, pos, state);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		// Ekran etkilesimi istemci tarafinda (DirectControl) yakalanir; blok tiki yutmasin ki
		// elindeki bloklar ekranin uzerine/yanina yerlestirilebilsin.
		return InteractionResult.PASS;
	}
}
