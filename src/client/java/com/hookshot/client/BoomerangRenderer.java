package com.hookshot.client;

import com.hookshot.BoomerangEntity;
import com.hookshot.HookshotMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Draws the boomerang lying flat and spinning fast, like it should. */
public class BoomerangRenderer extends EntityRenderer<BoomerangEntity, BoomerangRenderState> {
	private static final float SPIN_DEGREES_PER_TICK = 45.0F;
	private static final float SCALE = 0.75F;

	// Created lazily on first draw (see HookshotRenderer for why).
	@Nullable private ItemStack stack;

	public BoomerangRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public BoomerangRenderState createRenderState() {
		return new BoomerangRenderState();
	}

	@Override
	public void extractRenderState(BoomerangEntity entity, BoomerangRenderState state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		if (stack == null) {
			stack = new ItemStack(HookshotMod.BOOMERANG);
		}
		ItemModelResolver itemModelResolver = Minecraft.getInstance().getItemModelResolver();
		itemModelResolver.updateForTopItem(state.item, stack, ItemDisplayContext.FIXED, entity.level(), null, 0);
	}

	@Override
	public void submit(BoomerangRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		poseStack.pushPose();
		poseStack.translate(0.0F, 0.1F, 0.0F);
		poseStack.rotate(Axis.YP, (float) Math.toRadians(state.ageInTicks * SPIN_DEGREES_PER_TICK));
		poseStack.rotate(Axis.XP, (float) (Math.PI / 2.0)); // lay it flat
		poseStack.scale(SCALE, SCALE, SCALE);
		state.item.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}
}
