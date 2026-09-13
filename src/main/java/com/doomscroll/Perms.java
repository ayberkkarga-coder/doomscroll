package com.doomscroll;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.Permissions;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Permission checks. If a permission manager such as LuckPerms is present (fabric-permissions-api)
 * it is asked by node name; otherwise this falls back to the game's own permission set.
 *
 * <p>Works without adding a dependency: the API class is looked up via reflection; if it is missing,
 * that is noted once and never retried. On a server without a permission manager it costs nothing.
 *
 * <p>The nodes, and the defaults that apply when there is no permission manager:
 * <ul>
 *   <li>{@code doomscroll.place} — placing screens (default: everyone)</li>
 *   <li>{@code doomscroll.url} — changing the address on a screen (default: everyone)</li>
 *   <li>{@code doomscroll.broadcast} — starting broadcast mode (default: everyone)</li>
 *   <li>{@code doomscroll.bypass} — bypassing blocked addresses and the limits (default: gamemaster)</li>
 *   <li>{@code doomscroll.admin} — /doomscroll commands and report alerts (default: gamemaster)</li>
 * </ul>
 */
public final class Perms {
	public static final String PLACE = "doomscroll.place";
	public static final String URL = "doomscroll.url";
	public static final String BROADCAST = "doomscroll.broadcast";
	public static final String BYPASS = "doomscroll.bypass";
	public static final String ADMIN = "doomscroll.admin";

	/** The game's own permission for admin actions (equivalent of op level 2). */
	public static final Permission GAMEMASTER = Permissions.COMMANDS_GAMEMASTER;
	/** Means "everyone": open to all when there is no permission manager. */
	public static final Permission EVERYONE = null;

	private static MethodHandle check;
	private static boolean looked;

	private Perms() {}

	/**
	 * Does the player have permission for the node?
	 *
	 * @param fallback the game permission checked when there is no permission manager; {@link #EVERYONE} means open to all
	 */
	public static boolean has(ServerPlayer p, String node, @Nullable Permission fallback) {
		boolean vanilla = fallback == null || p.permissions().hasPermission(fallback);
		Boolean api = ask(p, node, vanilla);
		return api != null ? api : vanilla;
	}

	/** Returns the result if fabric-permissions-api is present, null otherwise. */
	@Nullable
	private static Boolean ask(ServerPlayer p, String node, boolean fallback) {
		if (!looked) {
			looked = true;
			try {
				Class<?> c = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
				check = MethodHandles.publicLookup().findStatic(c, "check",
						MethodType.methodType(boolean.class, net.minecraft.world.entity.Entity.class, String.class, boolean.class));
				Doomscroll.LOGGER.info("permission manager found: doomscroll.* nodes can be used");
			} catch (Throwable ignored) {
				check = null; // no permission manager: carry on with the game's own permissions
			}
		}
		if (check == null) {
			return null;
		}
		try {
			return (boolean) check.invoke((net.minecraft.world.entity.Entity) p, node, fallback);
		} catch (Throwable t) {
			check = null;
			Doomscroll.LOGGER.warn("permission API call failed, falling back to the game's permissions: {}", t.toString());
			return null;
		}
	}
}
