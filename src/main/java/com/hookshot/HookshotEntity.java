package com.hookshot;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.resources.Identifier;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The flying hook. All game logic runs on the server, except the "pull yourself" movement,
 * which runs on the owner's own client (players move client-side, so that is the smooth way to do it).
 *
 * <p>Lifecycle:
 * <pre>
 *   FLYING --hit--> REELING  (fetch mode: drags items / small mobs / a loose block back to you)
 *          --hit--> LATCHED  (travel mode: pulls you to the hook)
 *          --miss / max range / can't grab--> RETURNING
 * </pre>
 */
public class HookshotEntity extends Projectile {
	// ---- Tuning ----
	public static final double MAX_RANGE = 24.0;        // blocks
	public static final double FLY_SPEED = 2.2;         // blocks per tick
	public static final double RETURN_SPEED = 2.4;      // empty hook coming back
	public static final double REEL_SPEED = 1.4;        // dragging a target back
	public static final double TRAVEL_SPEED = 1.25;     // pulling the player
	public static final float MAX_GRAB_HARDNESS = 2.0F; // dirt/sand/gravel/wood/leaves... yes; stone-tier & up, no
	public static final float HIT_DAMAGE = 2.0F;        // one heart, like a little Zelda "stun"
	public static final float MAX_PULL_WIDTH = 1.0F;    // bigger mobs can't be dragged
	public static final float MAX_PULL_HEIGHT = 2.2F;
	private static final int MAX_REEL_TICKS = 80;
	private static final int MAX_TRAVEL_TICKS = 60;
	private static final double ARRIVE_DISTANCE = 1.6;

	public static final int STATE_FLYING = 0;
	public static final int STATE_RETURNING = 1;
	public static final int STATE_REELING = 2;
	public static final int STATE_LATCHED = 3;

	/** Blocks the hookshot will never yank loose (glass, ice...). Editable with a data pack. */
	public static final TagKey<Block> UNGRABBABLE = TagKey.create(Registries.BLOCK, HookshotMod.id("ungrabbable"));

	private static final EntityDataAccessor<Integer> DATA_STATE =
			SynchedEntityData.defineId(HookshotEntity.class, EntityDataSerializers.INT);

	// Server-side only
	private boolean travelMode;
	private final List<Entity> cargo = new ArrayList<>();
	@Nullable private Entity latchedEntity;
	@Nullable private BlockPos latchedBlock;
	private int stateTicks;
	private double lastTravelDistance = Double.MAX_VALUE;
	private int stuckTicks;

	// Client-side only
	private boolean clientArrived;

	public HookshotEntity(EntityType<? extends HookshotEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true; // we move it ourselves and do our own hit detection
	}

	// ------------------------------------------------------------------------------------------
	// Creation / lookup
	// ------------------------------------------------------------------------------------------

	public static void launch(Level level, Player player, boolean travelMode) {
		HookshotEntity hook = new HookshotEntity(HookshotMod.HOOK_ENTITY, level);
		hook.setOwner(player);
		hook.travelMode = travelMode;

		Vec3 look = player.getLookAngle();
		Vec3 start = player.getEyePosition().add(look.scale(0.5)).subtract(0.0, 0.15, 0.0);
		hook.setPos(start.x, start.y, start.z);
		hook.setDeltaMovement(look.scale(FLY_SPEED));
		hook.faceMotion();
		level.addFreshEntity(hook);
	}

	@Nullable
	public static HookshotEntity findActiveHook(Level level, Player player) {
		List<HookshotEntity> hooks = level.getEntitiesOfClass(HookshotEntity.class,
				player.getBoundingBox().inflate(MAX_RANGE + 16.0),
				hook -> hook.getOwner() == player && hook.isAlive());
		return hooks.isEmpty() ? null : hooks.get(0);
	}

	public static SoundEvent sound(String vanillaId) {
		return SoundEvent.createVariableRangeEvent(Identifier.withDefaultNamespace(vanillaId));
	}

	// ------------------------------------------------------------------------------------------
	// Entity plumbing
	// ------------------------------------------------------------------------------------------

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_STATE, STATE_FLYING);
	}

	public int getState() {
		return this.entityData.get(DATA_STATE);
	}

	private void setState(int state) {
		this.entityData.set(DATA_STATE, state);
		this.stateTicks = 0;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false; // can't be shot down
	}

	@Override
	protected boolean canHitEntity(Entity target) {
		if (target instanceof HookshotEntity) {
			return false;
		}
		// Items and XP aren't normally "hittable" by projectiles; the hookshot can grab them.
		if (target instanceof ItemEntity || target instanceof ExperienceOrb) {
			return target.isAlive();
		}
		return super.canHitEntity(target);
	}

	/** Called by the item when the player right-clicks again while the hook is out. */
	public void recall() {
		this.cargo.clear();
		this.latchedEntity = null;
		this.latchedBlock = null;
		if (getState() != STATE_RETURNING) {
			setState(STATE_RETURNING);
		}
	}

	// ------------------------------------------------------------------------------------------
	// Ticking
	// ------------------------------------------------------------------------------------------

	@Override
	public void tick() {
		super.tick();
		Entity owner = getOwner();

		if (level().isClientSide()) {
			clientTick(owner);
			return;
		}

		if (!(owner instanceof Player player) || !player.isAlive() || player.isSpectator()
				|| player.level() != level() || !HookshotItem.isHoldingHookshot(player)
				|| distanceTo(player) > MAX_RANGE + 16.0) {
			discard();
			return;
		}

		stateTicks++;
		switch (getState()) {
			case STATE_FLYING -> tickFlying(player);
			case STATE_REELING -> tickReeling(player);
			case STATE_LATCHED -> tickLatched(player);
			default -> tickReturning(player);
		}
	}

	private void tickFlying(Player owner) {
		HitResult hit = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
		if (hit.getType() != HitResult.Type.MISS) {
			Vec3 at = hit.getLocation();
			setPos(at.x, at.y, at.z);
			this.hitTargetOrDeflectSelf(hit); // -> onHitEntity / onHitBlock
		} else {
			Vec3 next = position().add(getDeltaMovement());
			setPos(next.x, next.y, next.z);
		}
		faceMotion();

		if (getState() == STATE_FLYING && distanceTo(owner) >= MAX_RANGE) {
			setState(STATE_RETURNING);
		}
	}

	private void tickReturning(Player owner) {
		Vec3 target = owner.getEyePosition().subtract(0.0, 0.4, 0.0);
		Vec3 toOwner = target.subtract(position());
		double distance = toOwner.length();
		if (distance < RETURN_SPEED + 0.5 || stateTicks > 100) {
			playSound(sound("entity.fishing_bobber.retrieve"), 0.8F, 1.4F);
			discard();
			return;
		}
		setDeltaMovement(toOwner.normalize().scale(RETURN_SPEED));
		Vec3 next = position().add(getDeltaMovement());
		setPos(next.x, next.y, next.z);
		faceMotion();
	}

	private void tickReeling(Player owner) {
		cargo.removeIf(e -> !e.isAlive() || e.level() != level());
		if (cargo.isEmpty()) {
			setState(STATE_RETURNING);
			return;
		}

		Vec3 target = owner.position().add(0.0, owner.getBbHeight() * 0.5, 0.0);
		Entity lead = cargo.get(0);
		boolean arrived = lead.position().distanceTo(target) < ARRIVE_DISTANCE;

		if (arrived || stateTicks > MAX_REEL_TICKS) {
			for (Entity e : cargo) {
				if (e instanceof ItemEntity || e instanceof ExperienceOrb) {
					// Drop loot right on the player so it's picked up immediately.
					e.setPos(owner.getX(), owner.getY() + 0.2, owner.getZ());
				}
				e.setDeltaMovement(Vec3.ZERO);
			}
			playSound(sound("entity.fishing_bobber.retrieve"), 1.0F, 1.0F);
			discard();
			return;
		}

		for (Entity e : cargo) {
			Vec3 toOwner = target.subtract(e.position());
			double d = toOwner.length();
			if (d > 0.01) {
				e.setDeltaMovement(toOwner.normalize().scale(Math.min(REEL_SPEED, d)));
			}
			e.resetFallDistance();
		}

		Vec3 holdAt = lead.position().add(0.0, lead.getBbHeight() * 0.5, 0.0);
		setPos(holdAt.x, holdAt.y, holdAt.z);
	}

	private void tickLatched(Player owner) {
		// Follow whatever we're hooked on; let go if it disappears.
		if (latchedEntity != null) {
			if (!latchedEntity.isAlive() || latchedEntity.level() != level()) {
				recall();
				return;
			}
			Vec3 at = latchedEntity.position().add(0.0, latchedEntity.getBbHeight() * 0.5, 0.0);
			setPos(at.x, at.y, at.z);
		} else if (latchedBlock != null && level().getBlockState(latchedBlock).getCollisionShape(level(), latchedBlock).isEmpty()) {
			recall();
			return;
		}

		owner.resetFallDistance();

		Vec3 center = owner.position().add(0.0, owner.getBbHeight() * 0.5, 0.0);
		double distance = center.distanceTo(position());

		// Progress check so the player can't get stuck hanging forever on a corner.
		if (lastTravelDistance - distance < 0.05) {
			stuckTicks++;
		} else {
			stuckTicks = 0;
		}
		lastTravelDistance = distance;

		if (distance < ARRIVE_DISTANCE || stuckTicks > 8 || stateTicks > MAX_TRAVEL_TICKS || owner.isShiftKeyDown()) {
			playSound(sound("entity.fishing_bobber.retrieve"), 0.8F, 1.2F);
			discard();
		}
	}

	/** Pull-yourself movement. Runs on the owning player's client only. */
	private void clientTick(@Nullable Entity owner) {
		if (getState() != STATE_LATCHED || !(owner instanceof Player player) || !player.isLocalPlayer()) {
			return;
		}

		Vec3 center = player.position().add(0.0, player.getBbHeight() * 0.5, 0.0);
		Vec3 toHook = position().subtract(center);
		double distance = toHook.length();

		if (distance > ARRIVE_DISTANCE) {
			player.setDeltaMovement(toHook.normalize().scale(Math.min(TRAVEL_SPEED, distance)));
			player.resetFallDistance();
		} else if (!clientArrived) {
			clientArrived = true;
			// Little hop at the end, so latching onto the top of a wall lets you climb over the edge.
			double hop = toHook.y > -0.2 ? 0.45 : 0.1;
			player.setDeltaMovement(toHook.normalize().scale(0.2).add(0.0, hop, 0.0));
		}
	}

	// ------------------------------------------------------------------------------------------
	// Hits (server side)
	// ------------------------------------------------------------------------------------------

	@Override
	protected void onHitEntity(EntityHitResult result) {
		super.onHitEntity(result);
		if (!(level() instanceof ServerLevel serverLevel) || !(getOwner() instanceof Player owner)) {
			return;
		}

		Entity target = result.getEntity();
		if (target instanceof LivingEntity living) {
			living.hurtServer(serverLevel, damageSources().playerAttack(owner), HIT_DAMAGE);
		}
		playSound(sound("block.chain.hit"), 1.0F, 1.2F);

		if (travelMode) {
			latchedEntity = target;
			setState(STATE_LATCHED);
		} else if (canBePulled(target)) {
			cargo.add(target);
			setState(STATE_REELING);
		} else {
			setState(STATE_RETURNING);
		}
	}

	@Override
	protected void onHitBlock(BlockHitResult result) {
		super.onHitBlock(result);
		if (!(level() instanceof ServerLevel serverLevel) || !(getOwner() instanceof Player owner)) {
			return;
		}

		Vec3 at = result.getLocation();
		BlockPos pos = result.getBlockPos();

		if (travelMode) {
			latchedBlock = pos;
			setState(STATE_LATCHED);
			playSound(sound("block.chain.hit"), 1.2F, 0.8F);
			return;
		}

		// Fetch mode, step 1: any loose items / XP right where we landed? Grab those.
		List<Entity> loose = level().getEntitiesOfClass(Entity.class, new AABB(at.x - 1.25, at.y - 1.25, at.z - 1.25, at.x + 1.25, at.y + 1.25, at.z + 1.25),
				e -> e.isAlive() && (e instanceof ItemEntity || e instanceof ExperienceOrb));
		if (!loose.isEmpty()) {
			cargo.addAll(loose);
			setState(STATE_REELING);
			playSound(sound("block.chain.hit"), 1.0F, 1.3F);
			return;
		}

		// Step 2: try to yank the block itself loose.
		if (tryGrabBlock(serverLevel, owner, pos, at)) {
			setState(STATE_REELING);
			playSound(sound("block.chain.hit"), 1.0F, 1.0F);
			return;
		}

		// Can't grab it: "clink" and come back, Zelda style.
		playSound(sound("block.anvil.land"), 0.25F, 2.0F);
		setState(STATE_RETURNING);
	}

	private static boolean canBePulled(Entity target) {
		if (target instanceof ItemEntity || target instanceof ExperienceOrb) {
			return true;
		}
		return target instanceof LivingEntity
				&& !(target instanceof Player)
				&& !target.isPassenger()
				&& target.getBbWidth() <= MAX_PULL_WIDTH
				&& target.getBbHeight() <= MAX_PULL_HEIGHT;
	}

	/**
	 * A block is "movable" if a piston could move it, it is soft enough, it is not a container or other
	 * block entity, it does not need a proper tool to drop anything, and the player is allowed to break it here.
	 */
	private boolean tryGrabBlock(ServerLevel level, Player owner, BlockPos pos, Vec3 at) {
		BlockState state = level.getBlockState(pos);
		PushReaction push = state.getPistonPushReaction();
		boolean pistonMovable = push == PushReaction.PUSH || push == PushReaction.POPPED;
		if (state.isAir() || state.hasBlockEntity() || state.is(UNGRABBABLE) || !pistonMovable) {
			return false;
		}
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0.0F || hardness > MAX_GRAB_HARDNESS || state.requiresCorrectToolForDrops()) {
			return false;
		}
		if (!owner.getAbilities().mayBuild || !this.mayInteract(level, pos)) {
			return false;
		}

		List<ItemStack> drops = Block.getDrops(state, level, pos, null, owner, ItemStack.EMPTY);
		if (drops.isEmpty()) {
			return false; // e.g. glass: don't just smash it
		}

		level.destroyBlock(pos, false);
		for (ItemStack drop : drops) {
			ItemEntity item = new ItemEntity(level, at.x, at.y, at.z, drop);
			item.setNoPickUpDelay();
			item.setDeltaMovement(Vec3.ZERO);
			level.addFreshEntity(item);
			cargo.add(item);
		}

		damageHookshot(owner);
		return true;
	}

	private static void damageHookshot(Player owner) {
		if (owner.getAbilities().instabuild) {
			return;
		}
		InteractionHand hand = owner.getMainHandItem().getItem() instanceof HookshotItem
				? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
		owner.getItemInHand(hand).hurtAndBreak(1, owner, hand);
	}

	// ------------------------------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------------------------------

	private void faceMotion() {
		Vec3 v = getDeltaMovement();
		if (v.lengthSqr() > 1.0E-6) {
			double horizontal = v.horizontalDistance();
			setYRot((float) (Math.atan2(v.x, v.z) * (180.0 / Math.PI)));
			setXRot((float) (Math.atan2(v.y, horizontal) * (180.0 / Math.PI)));
		}
	}
}
