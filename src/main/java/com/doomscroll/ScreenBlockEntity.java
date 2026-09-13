package com.doomscroll;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Screen block data. In a multi-block panel only the anchor is meaningful (URL, power, owner, control).
 * Control (controller) is not written to disk; the server hands it out from scratch on every start.
 */
public class ScreenBlockEntity extends BlockEntity {
	// Multi-block layout: anchor = the rectangle's main block (may be this block itself)
	private BlockPos anchor;
	private int width = 1;
	private int height = 1;
	private boolean on = true;
	/** URL open on the screen (synced). */
	private String url = "";
	// Owner: the player who placed the block (power-off/lock authority)
	@Nullable
	private UUID owner;
	private String ownerName = "";
	// Control: the player driving the screen; the URL and playback position are propagated from their browser
	@Nullable
	private UUID controller;
	private String controllerName = "";
	/** Lock: only the owner (and admins) can take control. */
	private boolean locked = false;
	/** The screen's shared volume (0..1): everyone in the room hears it at this level; the listener's own slider is applied on top. */
	private float volume = 1.0f;
	/** Broadcaster: the player whose view is streamed to everyone (session-only, not written to disk). */
	@Nullable
	private UUID broadcaster;
	private String broadcasterName = "";
	/** Redstone: the rising edge of a signal arriving at any block of the panel toggles the screen on/off. */
	private boolean redstone = false;
	/** Last known signal state (rising-edge detection). */
	private boolean powered = false;

	public ScreenBlockEntity(BlockPos pos, BlockState state) {
		super(Doomscroll.SCREEN_BE_TYPE, pos, state);
		this.anchor = pos.immutable();
	}

	@Nullable
	public UUID getOwner() { return owner; }
	public String getOwnerName() { return ownerName; }

	/** On an unowned screen everyone counts as the owner. */
	public boolean isOwner(UUID uuid) {
		return owner == null || owner.equals(uuid);
	}

	/** Whether it really belongs to this player (an unowned screen counts for nobody). */
	public boolean isOwnedBy(UUID uuid) {
		return owner != null && owner.equals(uuid);
	}

	/**
	 * When panels merge and the anchor changes, carries the old anchor's state over to the new anchor.
	 * Without this, someone placing a single block next to it would reset the URL, the lock and the owner.
	 * The controller and the broadcaster are session-only, so they are not carried over.
	 */
	public void adoptPanelState(ScreenBlockEntity from) {
		if (from == this) {
			return;
		}
		this.url = from.url;
		this.owner = from.owner;
		this.ownerName = from.ownerName;
		this.locked = from.locked;
		this.on = from.on;
		this.volume = from.volume;
		this.redstone = from.redstone;
		this.powered = from.powered;
		sync();
	}

	/** Server: set the owner and send to clients. */
	public void setOwner(UUID uuid, String name) {
		this.owner = uuid;
		this.ownerName = name == null ? "" : name;
		sync();
	}

	@Nullable
	public UUID getController() { return controller; }
	public String getControllerName() { return controllerName; }
	public boolean isLocked() { return locked; }
	public boolean isRedstone() { return redstone; }

	@Nullable
	public UUID getBroadcaster() { return broadcaster; }
	public String getBroadcasterName() { return broadcasterName; }

	public boolean isBroadcaster(@Nullable UUID uuid) {
		return uuid != null && uuid.equals(broadcaster);
	}

	/** Server: set/clear the broadcaster, send to clients. */
	public void setBroadcaster(@Nullable UUID uuid, @Nullable String name) {
		boolean changed = (uuid == null) != (broadcaster == null) || (uuid != null && !uuid.equals(broadcaster));
		broadcaster = uuid;
		broadcasterName = uuid == null || name == null ? "" : name;
		if (changed) {
			sync();
		}
	}
	public boolean isPowered() { return powered; }

	/** Server: enable/disable redstone control, send to clients. */
	public void setRedstone(boolean redstone) {
		if (this.redstone != redstone) {
			this.redstone = redstone;
			sync();
		}
	}

	/** Server: store the signal state (disk only; of no concern to the client). */
	public void setPowered(boolean powered) {
		if (this.powered != powered) {
			this.powered = powered;
			setChanged();
		}
	}

	public boolean isController(@Nullable UUID uuid) {
		return controller != null && controller.equals(uuid);
	}

	/** Only the owner when locked; otherwise everyone. (The admin exception lives on the server side.) */
	public boolean canControl(UUID uuid) {
		return !locked || isOwner(uuid);
	}

	/** Server: give/take control and send to clients. */
	public void setController(@Nullable UUID uuid, @Nullable String name) {
		String n = uuid == null || name == null ? "" : name;
		if (Objects.equals(uuid, controller) && n.equals(controllerName)) {
			return;
		}
		controller = uuid;
		controllerName = n;
		sync();
	}

	public float getVolume() { return volume; }

	/** Language-file key (not server text: the receiving client resolves it in its own language). */
	public static String volumeKey(float v) {
		return v <= 0.01f ? "gui.doomscroll.volume.off"
				: v <= 0.4f ? "gui.doomscroll.volume.low"
				: v <= 0.75f ? "gui.doomscroll.volume.mid" : "gui.doomscroll.volume.full";
	}

	/** Server: set the screen's shared volume and send to clients. */
	public void setVolume(float v) {
		float n = Math.max(0f, Math.min(1f, v));
		if (n != volume) {
			volume = n;
			sync();
		}
	}

	/** Server: set the lock and send to clients. */
	public void setLocked(boolean locked) {
		if (this.locked != locked) {
			this.locked = locked;
			sync();
		}
	}

	public boolean isAnchor() {
		return anchor == null || anchor.equals(getBlockPos());
	}

	public BlockPos getAnchor() {
		return anchor == null ? getBlockPos() : anchor;
	}

	public int getWidth() { return width; }
	public int getHeight() { return height; }
	public boolean isOn() { return on; }
	public String getUrl() { return url; }

	/** Server: change the URL and send to clients. */
	public void setUrl(String url) {
		String u = url == null ? "" : url;
		if (!this.url.equals(u)) {
			this.url = u;
			sync();
		}
	}

	/** Server: updates the layout and sends it to clients. */
	public void setLayout(BlockPos anchor, int width, int height) {
		boolean changed = !anchor.equals(this.anchor) || width != this.width || height != this.height;
		this.anchor = anchor.immutable();
		this.width = width;
		this.height = height;
		if (changed) {
			sync();
		}
	}

	/** Server: turn on/off, send to clients; sync the light (LIT) of every block in the panel. */
	public void setOn(boolean on) {
		if (this.on != on) {
			this.on = on;
			sync();
		}
		if (level != null && !level.isClientSide() && isAnchor()) {
			ScreenMultiblock.applyLit(level, getAnchor(), width, height, on);
		}
	}

	private boolean litChecked = false;

	/** Server tick: once after loading, matches the block light (LIT) to the power state (for old worlds). */
	public void serverTick() {
		if (litChecked || level == null) {
			return;
		}
		litChecked = true;
		if (isAnchor()) {
			BlockState st = getBlockState();
			if (st.hasProperty(ScreenBlock.LIT) && st.getValue(ScreenBlock.LIT) != on) {
				ScreenMultiblock.applyLit(level, getAnchor(), width, height, on);
			}
		}
	}

	private void sync() {
		setChanged();
		if (level != null && !level.isClientSide()) {
			BlockState s = getBlockState();
			level.sendBlockUpdated(getBlockPos(), s, s, 3);
		}
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		BlockPos a = getAnchor();
		output.putInt("ax", a.getX());
		output.putInt("ay", a.getY());
		output.putInt("az", a.getZ());
		output.putInt("w", width);
		output.putInt("h", height);
		output.putBoolean("on", on);
		output.putString("url", url);
		output.putString("owner", owner == null ? "" : owner.toString());
		output.putString("owner_name", ownerName);
		output.putBoolean("locked", locked);
		output.putFloat("volume", volume);
		output.putBoolean("redstone", redstone);
		output.putBoolean("powered", powered);
		// controller is deliberately not written: session-only data (goes to clients only, via getUpdateTag)
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		BlockPos self = getBlockPos();
		anchor = new BlockPos(
				input.getIntOr("ax", self.getX()),
				input.getIntOr("ay", self.getY()),
				input.getIntOr("az", self.getZ())
		);
		width = Math.max(1, input.getIntOr("w", 1));
		height = Math.max(1, input.getIntOr("h", 1));
		on = input.getBooleanOr("on", true);
		url = input.getStringOr("url", "");
		owner = parseUuid(input.getStringOr("owner", ""));
		ownerName = input.getStringOr("owner_name", "");
		locked = input.getBooleanOr("locked", false);
		volume = Math.max(0f, Math.min(1f, input.getFloatOr("volume", 1.0f)));
		redstone = input.getBooleanOr("redstone", false);
		powered = input.getBooleanOr("powered", false);
		controller = parseUuid(input.getStringOr("controller", ""));
		controllerName = input.getStringOr("controller_name", "");
		broadcaster = parseUuid(input.getStringOr("broadcaster", ""));
		broadcasterName = input.getStringOr("broadcaster_name", "");
	}

	@Nullable
	private static UUID parseUuid(String s) {
		if (s == null || s.isEmpty()) {
			return null;
		}
		try {
			return UUID.fromString(s);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** Screen blocks loaded on the server: the emergency shutdown and the count limit work off these. */
	private static final java.util.Set<ScreenBlockEntity> SERVER_LIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/**
	 * A copy of the loaded (chunk open) server screens.
	 * Removed entries and entries whose level is gone are weeded out here: chunk unloading
	 * calls setRemoved, but in single-player a few entries can linger when the world closes.
	 */
	/** On server shutdown: don't let stale entries leak into the next world. */
	public static void clearServerLive() {
		SERVER_LIVE.clear();
	}

	public static java.util.List<ScreenBlockEntity> liveOnServer() {
		SERVER_LIVE.removeIf(s -> s.isRemoved() || s.level == null);
		return java.util.List.copyOf(SERVER_LIVE);
	}

	@Override
	public void setLevel(net.minecraft.world.level.Level level) {
		super.setLevel(level);
		if (!level.isClientSide()) {
			SERVER_LIVE.add(this);
		}
	}

	@Override
	public void setRemoved() {
		super.setRemoved();
		SERVER_LIVE.remove(this);
		if (level != null && level.isClientSide()) {
			Doomscroll.screenRemoved.accept(getBlockPos());
		}
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
		CompoundTag tag = saveWithoutMetadata(provider);
		tag.putString("controller", controller == null ? "" : controller.toString());
		tag.putString("controller_name", controllerName);
		tag.putString("broadcaster", broadcaster == null ? "" : broadcaster.toString());
		tag.putString("broadcaster_name", broadcasterName);
		return tag;
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}
}
