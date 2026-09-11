package com.doomscroll;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hoparlor: bagli oldugu ekranin sesini kendi konumundan duyurur.
 *
 * <p>Ses akisi tektir (tarayicidan gelir), yani "birden fazla yerden ayni anda cal" diye bir sey
 * yok. Onun yerine her istemci, kendi oyuncusuna <b>en yakin</b> ses kaynagini secer: panelin
 * yuzeyi ya da bagli hoparlorlerden biri. Kulak tek yerde oldugu icin sonuc dogru, maliyet sifir.
 * Iki oyuncu ayri hoparlorlerin yanindaysa her biri kendi yakinindakinden duyar.
 */
public class SpeakerBlockEntity extends BlockEntity {
	/** Hoparlorun duyuldugu yaricap (blok). */
	public static final double RANGE = 24.0;

	/** Istemcide yuklu hoparlorler: ses konumu her karede buradan secilir. */
	private static final Set<SpeakerBlockEntity> CLIENT_LIVE = ConcurrentHashMap.newKeySet();

	@Nullable
	private BlockPos screen;

	public SpeakerBlockEntity(BlockPos pos, BlockState state) {
		super(Doomscroll.SPEAKER_BE_TYPE, pos, state);
	}

	@Nullable
	public BlockPos getScreen() {
		return screen;
	}

	public void setScreen(@Nullable BlockPos anchor) {
		this.screen = anchor == null ? null : anchor.immutable();
		setChanged();
		if (level != null && !level.isClientSide()) {
			level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
		}
	}

	/** Bu ekrana bagli, yuklu hoparlorler (istemci). */
	public static List<SpeakerBlockEntity> boundTo(BlockPos anchor) {
		CLIENT_LIVE.removeIf(s -> s.isRemoved() || s.level == null);
		return CLIENT_LIVE.stream().filter(s -> anchor.equals(s.screen)).toList();
	}

	/** Istemcide yuklu tum hoparlorler. */
	public static Collection<SpeakerBlockEntity> clientLive() {
		CLIENT_LIVE.removeIf(s -> s.isRemoved() || s.level == null);
		return List.copyOf(CLIENT_LIVE);
	}

	@Override
	public void setLevel(Level level) {
		super.setLevel(level);
		if (level.isClientSide()) {
			CLIENT_LIVE.add(this);
		}
	}

	@Override
	public void setRemoved() {
		super.setRemoved();
		CLIENT_LIVE.remove(this);
	}

	@Override
	protected void loadAdditional(ValueInput in) {
		super.loadAdditional(in);
		// Ekran blogu konumu uc ayri alanda (ScreenBlockEntity ile ayni bicim).
		int x = in.getIntOr("sx", Integer.MIN_VALUE);
		screen = x == Integer.MIN_VALUE ? null
				: new BlockPos(x, in.getIntOr("sy", 0), in.getIntOr("sz", 0));
	}

	@Override
	protected void saveAdditional(ValueOutput out) {
		super.saveAdditional(out);
		if (screen != null) {
			out.putInt("sx", screen.getX());
			out.putInt("sy", screen.getY());
			out.putInt("sz", screen.getZ());
		}
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
		return saveWithoutMetadata(provider);
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}
}
