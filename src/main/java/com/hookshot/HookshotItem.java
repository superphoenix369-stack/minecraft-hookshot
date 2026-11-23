package com.hookshot;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The hookshot.
 * <ul>
 *   <li>Right-click: fire the hook (or reel it back in early if it is already out).</li>
 *   <li>Sneak + right-click: switch between Fetch mode and Travel mode.</li>
 * </ul>
 */
public class HookshotItem extends Item {
	public HookshotItem(Properties properties) {
		super(properties);
	}

	public static boolean isTravelMode(ItemStack stack) {
		return Boolean.TRUE.equals(stack.get(HookshotMod.TRAVEL_MODE));
	}

	public static boolean isHoldingHookshot(Player player) {
		return player.getMainHandItem().getItem() instanceof HookshotItem
				|| player.getOffhandItem().getItem() instanceof HookshotItem;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		HookshotEntity active = HookshotEntity.findActiveHook(level, player);

		// A hook is already out: reel it back in (also cancels a pull-yourself in progress).
		if (active != null) {
			if (!level.isClientSide()) {
				active.recall();
			}
			return InteractionResult.SUCCESS;
		}

		// Sneak + use: toggle mode.
		if (player.isShiftKeyDown()) {
			boolean nowTravel = !isTravelMode(stack);
			player.playSound(HookshotEntity.sound("block.chain.place"), 0.8F, nowTravel ? 1.3F : 0.9F);
			if (!level.isClientSide()) {
				if (nowTravel) {
					stack.set(HookshotMod.TRAVEL_MODE, true);
				} else {
					stack.remove(HookshotMod.TRAVEL_MODE);
				}
				player.sendOverlayMessage(Component.translatable(nowTravel ? "message.hookshot.mode.travel" : "message.hookshot.mode.fetch"));
			}
			return InteractionResult.SUCCESS;
		}

		// Fire!
		player.playSound(HookshotEntity.sound("entity.fishing_bobber.throw"), 0.9F, 0.6F);
		if (!level.isClientSide()) {
			HookshotEntity.launch(level, player, isTravelMode(stack));
		}
		return InteractionResult.SUCCESS;
	}
}
