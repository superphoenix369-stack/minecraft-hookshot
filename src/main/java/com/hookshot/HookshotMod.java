package com.hookshot;

import com.mojang.serialization.Codec;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HookshotMod implements ModInitializer {
	public static final String MOD_ID = "hookshot";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	/**
	 * Present on a hookshot stack when it is in "travel" mode (pull yourself to the hook).
	 * Absent = "fetch" mode (pull the target to you). The item model also switches on this.
	 */
	public static final DataComponentType<Boolean> TRAVEL_MODE = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			id("travel_mode"),
			DataComponentType.<Boolean>builder().persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL).build());

	// --- Items ---
	public static final ResourceKey<Item> HOOKSHOT_KEY = itemKey("hookshot");
	public static final Item HOOKSHOT = Registry.register(BuiltInRegistries.ITEM, HOOKSHOT_KEY,
			new HookshotItem(new Item.Properties().setId(HOOKSHOT_KEY).durability(1200).enchantable(14)));

	public static final ResourceKey<Item> BOOMERANG_KEY = itemKey("boomerang");
	public static final Item BOOMERANG = Registry.register(BuiltInRegistries.ITEM, BOOMERANG_KEY,
			new BoomerangItem(new Item.Properties().setId(BOOMERANG_KEY).durability(300).enchantable(15)));

	// Render-only items used to draw the flying hook head and its chain. Not added to any creative tab.
	public static final ResourceKey<Item> HOOK_HEAD_KEY = itemKey("hook_head");
	public static final Item HOOK_HEAD = Registry.register(BuiltInRegistries.ITEM, HOOK_HEAD_KEY,
			new Item(new Item.Properties().setId(HOOK_HEAD_KEY)));

	public static final ResourceKey<Item> CHAIN_LINK_KEY = itemKey("chain_link");
	public static final Item CHAIN_LINK = Registry.register(BuiltInRegistries.ITEM, CHAIN_LINK_KEY,
			new Item(new Item.Properties().setId(CHAIN_LINK_KEY)));

	// --- Entities ---
	public static final ResourceKey<EntityType<?>> HOOK_ENTITY_KEY = ResourceKey.create(Registries.ENTITY_TYPE, id("hook"));
	public static final EntityType<HookshotEntity> HOOK_ENTITY = Registry.register(BuiltInRegistries.ENTITY_TYPE, HOOK_ENTITY_KEY,
			EntityType.Builder.<HookshotEntity>of(HookshotEntity::new, MobCategory.MISC)
					.sized(0.25F, 0.25F)
					.clientTrackingRange(4)
					.updateInterval(1)
					.build(HOOK_ENTITY_KEY));

	public static final ResourceKey<EntityType<?>> BOOMERANG_ENTITY_KEY = ResourceKey.create(Registries.ENTITY_TYPE, id("boomerang"));
	public static final EntityType<BoomerangEntity> BOOMERANG_ENTITY = Registry.register(BuiltInRegistries.ENTITY_TYPE, BOOMERANG_ENTITY_KEY,
			EntityType.Builder.<BoomerangEntity>of(BoomerangEntity::new, MobCategory.MISC)
					.sized(0.5F, 0.25F)
					.clientTrackingRange(4)
					.updateInterval(1)
					.build(BOOMERANG_ENTITY_KEY));

	private static ResourceKey<Item> itemKey(String path) {
		return ResourceKey.create(Registries.ITEM, id(path));
	}

	@Override
	public void onInitialize() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(output -> {
			output.accept(HOOKSHOT);
			output.accept(BOOMERANG);
		});

		// Built-in resource pack that turns emeralds into rupees (texture + name).
		// On by default; players can switch it off under Options > Resource Packs.
		FabricLoader.getInstance().getModContainer(MOD_ID).ifPresent(container ->
				ResourceLoader.registerBuiltinPack(id("rupees"), container,
						Component.literal("Hookshot: Rupees"), PackActivationType.DEFAULT_ENABLED));

		LOGGER.info("Hookshot loaded. Hyah!");
	}
}
