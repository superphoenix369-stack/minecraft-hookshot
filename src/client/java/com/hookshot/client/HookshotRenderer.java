package com.hookshot.client;

import com.hookshot.HookshotEntity;
import com.hookshot.HookshotMod;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the hook head plus a chain of little link sprites running back to the owner's hand.
 * Links alternate 90 degrees around the chain axis so it looks like a real chain from any angle.
 */
public class HookshotRenderer extends EntityRenderer<HookshotEntity, HookshotRenderState> {
	private static final double LINK_SPACING = 0.16;
	private static final float LINK_SCALE = 0.28F;
	private static final float HEAD_SCALE = 0.55F;
	private static final float QUARTER_TURN = (float) (Math.PI / 2.0);

	// Created lazily on first draw. Renderers are constructed during the game's very first resource
	// load, before items and the item-model system are fully ready, so touching them here would break startup.
	@Nullable private ItemStack headStack;
	@Nullable private ItemStack linkStack;

	public HookshotRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public HookshotRenderState createRenderState() {
		return new HookshotRenderState();
	}

	@Override
	public void extractRenderState(HookshotEntity entity, HookshotRenderState state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);

		Entity owner = entity.getOwner();
		Vec3 hookPos = new Vec3(
				Mth.lerp(partialTick, entity.xOld, entity.getX()),
				Mth.lerp(partialTick, entity.yOld, entity.getY()),
				Mth.lerp(partialTick, entity.zOld, entity.getZ()));
		state.toHand = owner == null ? null : handPosition(owner, partialTick).subtract(hookPos);

		if (headStack == null || linkStack == null) {
			headStack = new ItemStack(HookshotMod.HOOK_HEAD);
			linkStack = new ItemStack(HookshotMod.CHAIN_LINK);
		}
		ItemModelResolver itemModelResolver = Minecraft.getInstance().getItemModelResolver();
		itemModelResolver.updateForTopItem(state.head, headStack, ItemDisplayContext.GROUND, entity.level(), null, 0);
		itemModelResolver.updateForTopItem(state.link, linkStack, ItemDisplayContext.GROUND, entity.level(), null, 0);
	}

	@Override
	public void submit(HookshotRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		Vec3 toHand = state.toHand;
		if (toHand != null) {
			double length = toHand.length();
			if (length > 1.0E-3) {
				Vec3 dir = toHand.scale(1.0 / length);
				// Item sprites point "up" (+Y). Rotate +Y so it points away from the player, along the chain.
				Quaternionf outward = new Quaternionf().rotationTo(0.0F, 1.0F, 0.0F, (float) -dir.x, (float) -dir.y, (float) -dir.z);

				// Hook head (two crossed sprites so it never vanishes edge-on).
				for (int i = 0; i < 2; i++) {
					poseStack.pushPose();
					poseStack.rotate(new Quaternionf(outward).rotateY(i * QUARTER_TURN));
					poseStack.scale(HEAD_SCALE, HEAD_SCALE, HEAD_SCALE);
					state.head.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
					poseStack.popPose();
				}

				// Chain links back to the hand.
				int links = (int) (length / LINK_SPACING);
				for (int i = 1; i <= links; i++) {
					double t = i * LINK_SPACING;
					poseStack.pushPose();
					poseStack.translate(dir.x * t, dir.y * t, dir.z * t);
					poseStack.rotate(new Quaternionf(outward).rotateY((i % 2) * QUARTER_TURN));
					poseStack.scale(LINK_SCALE, LINK_SCALE, LINK_SCALE);
					state.link.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
					poseStack.popPose();
				}
			}
		}
		super.submit(state, poseStack, collector, camera);
	}

	/** Roughly where the hookshot is held: a bit in front of, right of, and below the eyes. */
	private static Vec3 handPosition(Entity owner, float partialTick) {
		Vec3 eye = new Vec3(
				Mth.lerp(partialTick, owner.xOld, owner.getX()),
				Mth.lerp(partialTick, owner.yOld, owner.getY()) + owner.getEyeHeight(),
				Mth.lerp(partialTick, owner.zOld, owner.getZ()));
		Vec3 forward = owner.getLookAngle();
		Vec3 right = forward.cross(new Vec3(0.0, 1.0, 0.0));
		right = right.lengthSqr() < 1.0E-4 ? new Vec3(1.0, 0.0, 0.0) : right.normalize();
		Vec3 up = right.cross(forward).normalize();

		double side = owner instanceof Player player && player.getMainArm() == HumanoidArm.LEFT ? -1.0 : 1.0;
		return eye.add(forward.scale(0.45)).add(right.scale(0.32 * side)).subtract(up.scale(0.32));
	}
}
