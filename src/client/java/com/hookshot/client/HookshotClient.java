package com.hookshot.client;

import com.hookshot.HookshotMod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class HookshotClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(HookshotMod.HOOK_ENTITY, HookshotRenderer::new);
		EntityRendererRegistry.register(HookshotMod.BOOMERANG_ENTITY, BoomerangRenderer::new);
	}
}
