package com.hookshot;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Right-click to throw. It leaves your hand and comes back to it. */
public class BoomerangItem extends Item {
	public BoomerangItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		boolean creative = player.getAbilities().instabuild;

		player.playSound(HookshotEntity.sound("entity.player.attack.sweep"), 0.8F, 1.4F);
		if (!level.isClientSide()) {
			// In creative you keep the one in your hand, so the thrown copy doesn't come back as a duplicate.
			ItemStack thrown = creative ? ItemStack.EMPTY : stack.copyWithCount(1);
			BoomerangEntity.launch(level, player, hand, thrown);
		}
		if (!creative) {
			stack.shrink(1);
		}
		return InteractionResult.SUCCESS;
	}
}
