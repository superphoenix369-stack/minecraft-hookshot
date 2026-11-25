package com.hookshot;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;

/**
 * The mod's enchantments. Their definitions (max level, rarity, which items accept them, enchanting-table
 * and loot/trade availability) live in data/hookshot/enchantment/*.json and the enchantment tags.
 * This class just names them and reads their level off an item.
 */
public final class HookshotEnchantments {
	// Hookshot
	public static final ResourceKey<Enchantment> REACH = key("reach");
	public static final ResourceKey<Enchantment> QUICK_DRAW = key("quick_draw");
	public static final ResourceKey<Enchantment> STRONG_GRIP = key("strong_grip");
	public static final ResourceKey<Enchantment> SILK_GRIP = key("silk_grip");
	// Boomerang
	public static final ResourceKey<Enchantment> RICOCHET = key("ricochet");
	public static final ResourceKey<Enchantment> LONG_THROW = key("long_throw");
	public static final ResourceKey<Enchantment> KEEN_EDGE = key("keen_edge");
	public static final ResourceKey<Enchantment> BLAZING = key("blazing");
	public static final ResourceKey<Enchantment> STUNNING = key("stunning");

	private HookshotEnchantments() {
	}

	private static ResourceKey<Enchantment> key(String path) {
		return ResourceKey.create(Registries.ENCHANTMENT, HookshotMod.id(path));
	}

	/** Level of the given enchantment on the stack, or 0. Safe if a data pack removed the enchantment. */
	public static int level(Level level, ItemStack stack, ResourceKey<Enchantment> enchantment) {
		if (stack.isEmpty()) {
			return 0;
		}
		return level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(enchantment)
				.map(holder -> EnchantmentHelper.getItemEnchantmentLevel(holder, stack))
				.orElse(0);
	}
}
