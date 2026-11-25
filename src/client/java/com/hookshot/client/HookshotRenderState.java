package com.hookshot.client;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.phys.Vec3;

public class HookshotRenderState extends EntityRenderState {
	public final ItemStackRenderState head = new ItemStackRenderState();
	public final ItemStackRenderState link = new ItemStackRenderState();
	/** Vector from the hook to the owner's hand, or null if there is no owner to draw the chain to. */
	@Nullable
	public Vec3 toHand;
}
