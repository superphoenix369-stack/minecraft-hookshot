package com.hookshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A thrown boomerang. Flies out in a straight line, then homes back to the thrower.
 * Stuns mobs it passes through, cuts grass, and carries back any items/XP it touches.
 * The real item stack rides along inside the entity and is handed back on catch
 * (or dropped on the ground if the thrower is gone).
 */
public class BoomerangEntity extends Projectile {
	// ---- Tuning ----
	public static final double OUT_SPEED = 1.5;     // blocks per tick on the way out
	public static final double MAX_OUT_DISTANCE = 16.0;
	public static final int MAX_OUT_TICKS = 14;
	public static final double RETURN_SPEED = 1.6;  // top speed coming back
	public static final float HIT_DAMAGE = 1.0F;    // half a heart
	public static final int STUN_TICKS = 50;        // Stunning I = 2.5 seconds, +1 second per extra level
	private static final int STUN_AMPLIFIER = 6;    // Slowness VII = frozen in place
	private static final double CATCH_DISTANCE = 1.4;
	private static final double PICKUP_RADIUS = 0.9;
	private static final int MAX_LIFE_TICKS = 200;

	/** Plants the boomerang slices through (grass, ferns, dead bushes...). Editable with a data pack. */
	public static final TagKey<Block> CUTTABLE = TagKey.create(Registries.BLOCK, HookshotMod.id("boomerang_cuttable"));

	private static final EntityDataAccessor<Boolean> DATA_RETURNING =
			SynchedEntityData.defineId(BoomerangEntity.class, EntityDataSerializers.BOOLEAN);

	// Server-side only
	private ItemStack stack = ItemStack.EMPTY;   // what gets handed back (empty in creative)
	private InteractionHand hand = InteractionHand.MAIN_HAND;
	private Vec3 throwOrigin = Vec3.ZERO;
	private int outTicks;
	private int returnTicks;
	// Enchantments (read once at throw time)
	private double outSpeed = OUT_SPEED;              // Long Throw
	private double returnSpeed = RETURN_SPEED;        // Long Throw
	private double maxOutDistance = MAX_OUT_DISTANCE; // Long Throw
	private int maxOutTicks = MAX_OUT_TICKS;          // Long Throw
	private float damage = HIT_DAMAGE;                // Keen Edge
	private boolean blazing;                          // Blazing
	private int stunTicks;                            // Stunning (0 = no stun)
	private int ricochetsLeft;                        // Ricochet
	@Nullable private LivingEntity ricochetTarget;
	private final Set<Integer> alreadyHit = new HashSet<>();
	private final List<Entity> cargo = new ArrayList<>();

	public BoomerangEntity(EntityType<? extends BoomerangEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	/**
	 * @param thrownStack the stack to hand back on catch (empty in creative)
	 * @param source      the boomerang as held, used to read its enchantments
	 */
	public static void launch(Level level, Player player, InteractionHand hand, ItemStack thrownStack, ItemStack source) {
		BoomerangEntity boomerang = new BoomerangEntity(HookshotMod.BOOMERANG_ENTITY, level);
		boomerang.setOwner(player);
		boomerang.stack = thrownStack;
		boomerang.hand = hand;

		int longThrow = HookshotEnchantments.level(level, source, HookshotEnchantments.LONG_THROW);
		boomerang.outSpeed = OUT_SPEED * (1.0 + 0.15 * longThrow);
		boomerang.returnSpeed = RETURN_SPEED * (1.0 + 0.15 * longThrow);
		boomerang.maxOutDistance = MAX_OUT_DISTANCE + 6.0 * longThrow;
		boomerang.maxOutTicks = (int) Math.ceil(boomerang.maxOutDistance / boomerang.outSpeed) + 2;
		boomerang.damage = HIT_DAMAGE + HookshotEnchantments.level(level, source, HookshotEnchantments.KEEN_EDGE);
		boomerang.blazing = HookshotEnchantments.level(level, source, HookshotEnchantments.BLAZING) > 0;
		int stunning = HookshotEnchantments.level(level, source, HookshotEnchantments.STUNNING);
		boomerang.stunTicks = stunning > 0 ? STUN_TICKS + 20 * (stunning - 1) : 0;
		boomerang.ricochetsLeft = HookshotEnchantments.level(level, source, HookshotEnchantments.RICOCHET);

		Vec3 look = player.getLookAngle();
		Vec3 start = player.getEyePosition().add(look.scale(0.6)).subtract(0.0, 0.2, 0.0);
		boomerang.setPos(start.x, start.y, start.z);
		boomerang.throwOrigin = start;
		boomerang.setDeltaMovement(look.scale(boomerang.outSpeed));
		level.addFreshEntity(boomerang);
	}

	// ------------------------------------------------------------------------------------------
	// Entity plumbing
	// ------------------------------------------------------------------------------------------

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_RETURNING, false);
	}

	public boolean isReturning() {
		return this.entityData.get(DATA_RETURNING);
	}

	private void startReturning() {
		if (!isReturning()) {
			this.entityData.set(DATA_RETURNING, true);
		}
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		// So the boomerang isn't lost if the world is saved while it's in the air.
		if (!stack.isEmpty()) {
			output.store("Boomerang", ItemStack.CODEC, stack);
		}
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		this.stack = input.read("Boomerang", ItemStack.CODEC).orElse(ItemStack.EMPTY);
		this.entityData.set(DATA_RETURNING, true);
	}

	// ------------------------------------------------------------------------------------------
	// Ticking (server)
	// ------------------------------------------------------------------------------------------

	@Override
	public void tick() {
		super.tick();
		if (!(level() instanceof ServerLevel serverLevel)) {
			return;
		}

		Entity ownerEntity = getOwner();
		if (!(ownerEntity instanceof Player owner) || !owner.isAlive() || owner.level() != level()) {
			dropAndDiscard(serverLevel);
			return;
		}

		if (this.tickCount > MAX_LIFE_TICKS) {
			catchBy(owner, serverLevel); // took too long; just hand it back
			return;
		}

		if (this.tickCount % 5 == 0) {
			playSound(HookshotEntity.sound("entity.player.attack.sweep"), 0.35F, 1.7F);
		}

		Vec3 from = position();
		Vec3 velocity;

		if (!isReturning()) {
			outTicks++;
			if (ricochetTarget != null) {
				if (ricochetTarget.isAlive() && ricochetTarget.level() == level()) {
					Vec3 aim = ricochetTarget.position().add(0.0, ricochetTarget.getBbHeight() * 0.5, 0.0);
					setDeltaMovement(aim.subtract(from).normalize().scale(outSpeed));
				} else {
					ricochetTarget = null;
					startReturning();
				}
			}
			velocity = getDeltaMovement();
			Vec3 to = from.add(velocity);

			BlockHitResult wall = level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
			if (wall.getType() != HitResult.Type.MISS) {
				Vec3 at = wall.getLocation();
				hitEntitiesAlong(serverLevel, owner, from, at);
				if (isRemoved()) {
					return;
				}
				if (blazing) {
					lightBlock(serverLevel, wall.getBlockPos());
				}
				setPos(at.x, at.y, at.z);
				this.hitTargetOrDeflectSelf(wall); // rings bells, triggers target blocks, etc.
				playSound(HookshotEntity.sound("block.wood.hit"), 1.0F, 1.4F);
				startReturning();
				return;
			}

			if (ricochetTarget == null && (outTicks >= maxOutTicks || to.distanceTo(throwOrigin) >= maxOutDistance)) {
				startReturning();
			}
		} else {
			Vec3 target = owner.getEyePosition().subtract(0.0, 0.5, 0.0);
			Vec3 toOwner = target.subtract(from);
			double distance = toOwner.length();
			if (distance < CATCH_DISTANCE + RETURN_SPEED * 0.5) {
				catchBy(owner, serverLevel);
				return;
			}
			// Ease into the return: slow at the turnaround, full speed shortly after.
			returnTicks++;
			double speed = Math.min(returnSpeed, 0.3 + 0.25 * returnTicks);
			velocity = toOwner.normalize().scale(Math.min(speed, distance));
			setDeltaMovement(velocity);
		}

		Vec3 to = from.add(velocity);
		hitEntitiesAlong(serverLevel, owner, from, to);
		if (isRemoved()) {
			return; // it broke
		}
		cutPlantsAlong(serverLevel, owner, from, to);
		if (blazing) {
			serverLevel.sendParticles(ParticleTypes.FLAME, getX(), getY() + 0.1, getZ(), 2, 0.1, 0.05, 0.1, 0.01);
		}
		setPos(to.x, to.y, to.z);
		collectLoot();
	}

	private void hitEntitiesAlong(ServerLevel level, Player owner, Vec3 from, Vec3 to) {
		double r = getBbWidth() * 0.5 + 0.3;
		AABB swept = new AABB(
				Math.min(from.x, to.x) - r, Math.min(from.y, to.y) - r, Math.min(from.z, to.z) - r,
				Math.max(from.x, to.x) + r, Math.max(from.y, to.y) + r, Math.max(from.z, to.z) + r);
		List<Entity> hits = level.getEntities(this, swept,
				e -> e instanceof LivingEntity && e.isAlive() && e != owner && !alreadyHit.contains(e.getId()) && canHitEntity(e));

		for (Entity e : hits) {
			alreadyHit.add(e.getId());
			LivingEntity living = (LivingEntity) e;
			living.hurtServer(level, damageSources().playerAttack(owner), damage);
			if (blazing) {
				living.igniteForSeconds(4.0F);
			}
			if (stunTicks > 0) {
				living.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, stunTicks, STUN_AMPLIFIER));
			}
			playSound(HookshotEntity.sound("block.wood.hit"), 1.0F, 1.0F);

			if (!stack.isEmpty()) {
				stack.hurtAndBreak(1, owner, hand);
				if (stack.isEmpty()) { // snapped!
					playSound(HookshotEntity.sound("entity.item.break"), 1.0F, 1.0F);
					discard();
					return;
				}
			}
		}

		if (!hits.isEmpty()) {
			if (ricochetTarget != null && !ricochetTarget.isAlive()) {
				ricochetTarget = null;
			}
			// Ricochet: bounce on to the nearest other monster. Otherwise, like Zelda, come straight back.
			LivingEntity next = ricochetsLeft > 0 && !isReturning() ? findRicochetTarget(level, owner) : null;
			if (next != null) {
				ricochetsLeft--;
				ricochetTarget = next;
				outTicks = 0;
				throwOrigin = position();
				playSound(HookshotEntity.sound("block.wood.hit"), 0.8F, 1.6F);
			} else {
				ricochetTarget = null;
				startReturning();
			}
		}
	}

	@Nullable
	private LivingEntity findRicochetTarget(ServerLevel level, Player owner) {
		LivingEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		Vec3 here = position();
		List<Entity> candidates = level.getEntities(this, getBoundingBox().inflate(10.0),
				e -> e instanceof LivingEntity && e.isAlive() && e != owner && !alreadyHit.contains(e.getId())
						&& e.getType().getCategory() == MobCategory.MONSTER);
		for (Entity e : candidates) {
			Vec3 aim = e.position().add(0.0, e.getBbHeight() * 0.5, 0.0);
			double d = aim.distanceTo(here);
			if (d >= bestDistance) {
				continue;
			}
			// Must have a clear line to it.
			BlockHitResult sight = level.clip(new ClipContext(here, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
			if (sight.getType() == HitResult.Type.MISS) {
				best = (LivingEntity) e;
				bestDistance = d;
			}
		}
		return best;
	}

	/** Blazing: light any candle, candle cake or campfire at this spot. */
	private void lightBlock(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if ((CampfireBlock.canLight(state) || CandleBlock.canLight(state) || CandleCakeBlock.canLight(state))
				&& this.mayInteract(level, pos)) {
			level.setBlock(pos, state.setValue(BlockStateProperties.LIT, true), 11);
			playSound(HookshotEntity.sound("item.firecharge.use"), 0.5F, 1.4F);
		}
	}

	private void cutPlantsAlong(ServerLevel level, Player owner, Vec3 from, Vec3 to) {
		double length = to.distanceTo(from);
		int steps = Math.max(1, (int) Math.ceil(length / 0.5));
		for (int i = 0; i <= steps; i++) {
			Vec3 p = from.add(to.subtract(from).scale((double) i / steps));
			BlockPos pos = BlockPos.containing(p);
			if (level.getBlockState(pos).is(CUTTABLE) && this.mayInteract(level, pos)) {
				level.destroyBlock(pos, true); // drops seeds etc., which we then scoop up
			}
			if (blazing) {
				lightBlock(level, pos);
			}
		}
	}

	private void collectLoot() {
		cargo.removeIf(e -> !e.isAlive());
		AABB reach = getBoundingBox().inflate(PICKUP_RADIUS);
		for (Entity e : level().getEntitiesOfClass(Entity.class, reach,
				e -> e.isAlive() && (e instanceof ItemEntity || e instanceof ExperienceOrb))) {
			if (!cargo.contains(e)) {
				cargo.add(e);
			}
		}
		for (Entity e : cargo) {
			e.setPos(getX(), getY() - 0.1, getZ());
			e.setDeltaMovement(Vec3.ZERO);
		}
	}

	private void catchBy(Player owner, ServerLevel level) {
		for (Entity e : cargo) {
			if (e.isAlive()) {
				e.setPos(owner.getX(), owner.getY() + 0.2, owner.getZ()); // picked up next tick
			}
		}
		cargo.clear();

		if (!stack.isEmpty()) {
			if (owner.getItemInHand(hand).isEmpty()) {
				owner.setItemInHand(hand, stack);
			} else if (!owner.getInventory().add(stack)) {
				owner.spawnAtLocation(level, stack, 0.5F);
			}
			stack = ItemStack.EMPTY;
		}
		playSound(HookshotEntity.sound("entity.item.pickup"), 0.6F, 1.3F);
		discard();
	}

	private void dropAndDiscard(ServerLevel level) {
		if (!stack.isEmpty()) {
			spawnAtLocation(level, stack, 0.0F);
			stack = ItemStack.EMPTY;
		}
		discard();
	}
}
