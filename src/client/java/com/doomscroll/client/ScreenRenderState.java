package com.doomscroll.client;

import net.minecraft.resources.Identifier;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;

public class ScreenRenderState extends BlockEntityRenderState {
	public boolean anchor = true;
	public boolean on = true;
	/** Front face (horizontal on a wall, UP on the floor, DOWN on the ceiling) and the picture's top edge (UP on a wall). */
	public Direction facing = Direction.NORTH;
	public Direction top = Direction.UP;
	public float screenWidth = 1.0f;
	public float screenHeight = 1.0f;
	public boolean hasTexture = false;
	public Identifier texId;
	/** Screen glow patches (null = off), the browser's color map and the intensity. */
	public java.util.List<ScreenGlow.Patch> glow;
	public float[] tiles;
	public float glowIntensity;
	/** Other players' pointers (null = off). */
	public java.util.List<Pointers.Pointer> pointers;
}
