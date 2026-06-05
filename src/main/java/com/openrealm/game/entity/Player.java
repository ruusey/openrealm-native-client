package com.openrealm.game.entity;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import java.io.DataOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.openrealm.account.dto.CharacterStatsDto;
import com.openrealm.account.dto.GameItemRefDto;
import com.openrealm.game.contants.CharacterClass;
import com.openrealm.game.contants.GlobalConstants;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.LootContainer;
import com.openrealm.game.entity.item.Stats;
import com.openrealm.game.graphics.Sprite;
import com.openrealm.game.graphics.SpriteRecolorCache;
import com.openrealm.game.model.AnimationModel;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.CharacterClassModel;
import com.openrealm.game.model.ability.Ability;
import com.openrealm.game.model.ability.PassiveAbility;
import com.openrealm.game.state.PlayState;
import com.openrealm.net.client.packet.UpdatePacket;
import com.openrealm.net.core.IOService;
import com.openrealm.net.entity.NetGameItemRef;
import com.openrealm.util.KeyHandler;
import com.openrealm.util.MouseHandler;
import com.openrealm.util.Tuple;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import com.openrealm.game.entity.item.Enchantment;
import com.openrealm.game.graphics.ShaderManager;
import com.openrealm.net.client.packet.PlayerStatePacket;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
@Data
@Builder
@EqualsAndHashCode(callSuper = false)
public class Player extends Entity {
	private GameItem[] inventory;
	private long lastStatsTime;
	private LootContainer currentLootContainer;
	private int classId;
	private String accountUuid;
	private String characterUuid;
	private long experience;
	private Stats stats;
	private boolean headless;
	@Builder.Default
	private boolean bot = false;
	// Chat role prefix cached at login for name coloring in chat.
	// Values: "sysadmin", "admin", "mod", "editor", or null (regular player)
	@Builder.Default
	private String chatRole = "";
	// Last input sequence number processed by the server (for client reconciliation)
	@Builder.Default
	private int lastInputSeq = 0;
	// Sequence-numbered input queue fields for new movement netcode
	@Builder.Default
	private int lastProcessedInputSeq = 0;
	// Last move direction observed from client (unit vector). (0,0) = stopped.
	@Builder.Default
	private float currentVx = 0f;
	@Builder.Default
	private float currentVy = 0f;
	// Queue elements: float[]{seq (cast from int), vx, vy}
	@Builder.Default
	private transient Queue<float[]> inputQueue = new ConcurrentLinkedQueue<>();

	// Consumable potion storage (separate from inventory)
	public static final int MAX_CONSUMABLE_POTIONS = 6;
	public static final int HP_POTION_ITEM_ID = 296;
	public static final int MP_POTION_ITEM_ID = 297;
	@Builder.Default
	private int hpPotions = 0;
	@Builder.Default
	private int mpPotions = 0;

	// Cosmetic dye id keyed in the client's dye-assets.json registry. 0 = no
	// dye. Renderer resolves the id to a recolor strategy (solid color today;
	// patterned cloths in the future). Persisted on the character so it
	// survives logout but is implicitly cleared on permadeath (character is
	// deleted, fresh char starts at dyeId=0).
	@Builder.Default
	private int dyeId = 0;

	// Last known account fame for this player, refreshed at login and after
	// each fame-shop purchase. NOT serialized to other clients; only used by
	// the server to validate purchases without re-fetching the account on
	// every request. Source of truth is the data service.
	@Builder.Default
	private transient long cachedAccountFame = 0L;

	// Visual interpolation override. The simulation position (this.pos) advances
	// in 1/64 s tick steps; rendering THAT directly at 144 FPS produces a
	// per-tick "lurch" because the camera (lerped) and the sprite (postTick)
	// disagree by up to one tick distance every render frame between ticks.
	// Web client mirrors this by exposing _renderX / _renderY and rendering
	// both camera AND player at the same lerped value. Set NaN means "use
	// pos.x / pos.y" (default for non-local players or before first tick).
	// Declared BEFORE abilityCooldowns/currentCast/hotbarBindings so Lombok's
	// generated ctor matches the explicit all-args ctor signature below.
	@Builder.Default
	private transient float renderX = Float.NaN;
	@Builder.Default
	private transient float renderY = Float.NaN;

	// Phase 2A runtime state (mirrors server-side Player). Hotbar bindings
	// inherit from CharacterClassModel.abilityTree.defaultHotbar on spawn;
	// runtime mutations come from HotbarSwapPacket. Transient — re-seeds on
	// login until Phase 2B persists them.
	@Builder.Default
	private transient long[] abilityCooldowns = new long[4];
	@Builder.Default
	private transient CastState currentCast = null;
	@Builder.Default
	private transient int[] hotbarBindings = new int[]{0, 0, 0, 0};

	// Phase 2D — skill-point pool + per-ability investment. Earned 1 per 2
	// levels from L2 to L20 (10 total). Caps per ability come from
	// Ability.maxSkillPoints (5 for non-ults, 3 for ults). Persisted via
	// CharacterStatsDto.
	@Builder.Default
	private int availableSkillPoints = 0;
	@Builder.Default
	private Map<Integer, Integer> abilitySkillPoints = new HashMap<>();

	public void setRenderPos(float rx, float ry) {
		this.renderX = rx;
		this.renderY = ry;
	}

	public float getEffectiveRenderX() {
		return Float.isNaN(this.renderX) ? this.pos.x : this.renderX;
	}

	public float getEffectiveRenderY() {
		return Float.isNaN(this.renderY) ? this.pos.y : this.renderY;
	}

	public Player() {
		super(0, null, 0);
		// CRITICAL: explicitly initialise renderX/Y to NaN here. The field
		// declarations have `= Float.NaN` inline, but Lombok's
		// @Builder.Default annotation captures that initialiser for the
		// generated builder and strips the inline init from the class
		// itself. With the no-arg ctor invoked via NetPlayer.toPlayer,
		// renderX/Y end up at Java's float default of 0.0f, not NaN —
		// which made getEffectiveRenderX() return 0.0 instead of pos.x
		// for every remote player. Result: every other player rendered
		// at world (0, 0). Fix: set the canonical default in the ctor
		// body so it's not at the mercy of Lombok's processor.
		this.renderX = Float.NaN;
		this.renderY = Float.NaN;
	}

	public Player(GameItem[] inventory, long lastStatsTime, LootContainer currentLootContainer, int classId,
			String accountUuid, String characterUuid, long experience, Stats stats, boolean headless, boolean bot,
			String chatRole, int lastInputSeq, int lastProcessedInputSeq, float currentVx, float currentVy,
			Queue<float[]> inputQueue, int hpPotions, int mpPotions, int dyeId, long cachedAccountFame,
			float renderX, float renderY,
			long[] abilityCooldowns, CastState currentCast, int[] hotbarBindings,
			int availableSkillPoints, Map<Integer, Integer> abilitySkillPoints) {
		super(0, null, 0);
		this.inventory = inventory;
		this.lastStatsTime = lastStatsTime;
		this.currentLootContainer = currentLootContainer;
		this.classId = classId;
		this.accountUuid = accountUuid;
		this.characterUuid = characterUuid;
		this.experience = experience;
		this.stats = stats;
		this.headless = headless;
		this.bot = bot;
		this.chatRole = chatRole;
		this.lastInputSeq = lastInputSeq;
		this.lastProcessedInputSeq = lastProcessedInputSeq;
		this.currentVx = currentVx;
		this.currentVy = currentVy;
		this.inputQueue = inputQueue != null ? inputQueue : new ConcurrentLinkedQueue<>();
		this.hpPotions = hpPotions;
		this.mpPotions = mpPotions;
		this.dyeId = dyeId;
		this.cachedAccountFame = cachedAccountFame;
		this.renderX = renderX;
		this.renderY = renderY;
		// Phase 2A — nullsafe defaults so existing Builder callers still get a usable Player.
		this.abilityCooldowns = abilityCooldowns != null ? abilityCooldowns : new long[4];
		this.currentCast = currentCast;
		this.hotbarBindings = hotbarBindings != null ? hotbarBindings : new int[]{0, 0, 0, 0};
		// Phase 2D — skill point pool + investment map.
		this.availableSkillPoints = availableSkillPoints;
		this.abilitySkillPoints = abilitySkillPoints != null ? abilitySkillPoints : new HashMap<>();
	}

	public Player(long id, Vector2f origin, int size, CharacterClass characterClass) {
		super(id, origin, size);
		this.resetEffects();
		this.resetInventory();
		this.classId = characterClass.classId;
		this.size = size;
		this.experience = 0;
		this.bounds.setWidth(this.size);
		this.bounds.setHeight(this.size);

		this.hitBounds.setWidth(this.size);
		this.hitBounds.setHeight(this.size);
		CharacterClassModel classModel = GameDataManager.CHARACTER_CLASSES.get(this.classId);
		this.health = classModel.getBaseStats().getHp();
		this.mana = classModel.getBaseStats().getMp();

		this.stats = classModel.getBaseStats().clone();
		// Phase 2A: seed hotbar from class default. Mirrors server logic.
		if (this.hotbarBindings == null) this.hotbarBindings = new int[]{0, 0, 0, 0};
		if (classModel.getAbilityTree() != null && classModel.getAbilityTree().getDefaultHotbar() != null) {
			final int[] src = classModel.getAbilityTree().getDefaultHotbar();
			for (int i = 0; i < this.hotbarBindings.length && i < src.length; i++) {
				this.hotbarBindings[i] = src[i];
			}
		}
		// Same Lombok-strips-inline-init dance as the no-arg ctor: without
		// these, the local player's renderX/Y starts at 0 (Java default)
		// instead of NaN, so getEffectiveRenderX returns 0 on the very
		// first render frame before PlayState's lerp pipeline writes a
		// real value. The minimap reads getEffectiveRenderX → 0, plots
		// the local dot at world (0, 0) → top-left corner of the
		// overworld minimap until the player presses a movement key.
		this.renderX = Float.NaN;
		this.renderY = Float.NaN;
	}

	public void applyStats(CharacterStatsDto stats) {
		this.setExperience(stats.getXp());
		this.health = stats.getHp();
		this.mana = stats.getMp();
		this.stats.setHp(stats.getHp().shortValue());
		this.stats.setMp(stats.getMp().shortValue());
		this.stats.setDef(stats.getDef().shortValue());
		this.stats.setStr(stats.getStr().shortValue());
		this.stats.setSpd(stats.getSpd().shortValue());
		this.stats.setDex(stats.getDex().shortValue());
		this.stats.setVit(stats.getVit().shortValue());
		this.stats.setWis(stats.getWis().shortValue());
		if (stats.getHpPotions() != null) this.hpPotions = stats.getHpPotions();
		if (stats.getMpPotions() != null) this.mpPotions = stats.getMpPotions();
		if (stats.getDyeId() != null) this.dyeId = stats.getDyeId();
		// Phase 2D — restore skill-point pool + per-ability investment map.
		this.availableSkillPoints = stats.getAvailableSkillPoints() != null
				? stats.getAvailableSkillPoints() : 0;
		this.abilitySkillPoints = stats.getAbilitySkillPoints() != null
				? new HashMap<>(stats.getAbilitySkillPoints()) : new HashMap<>();
	}

	public Set<GameItemRefDto> serializeItems() {
		final Set<GameItemRefDto> res = new HashSet<>();
		for (int i = 0; i < this.inventory.length; i++) {
			GameItem item = this.inventory[i];
			if (item != null) {
				res.add(item.toGameItemRefDto(i));
			}
		}
		return res;
	}

	public CharacterStatsDto serializeStats() {
		return CharacterStatsDto.builder().xp(this.getExperience()).hp(Integer.valueOf((int) this.stats.getHp()))
				.mp(Integer.valueOf((int) this.stats.getMp())).def(Integer.valueOf((int) this.stats.getDef()))
				.str(Integer.valueOf((int) this.stats.getStr())).spd(Integer.valueOf((int) this.stats.getSpd()))
				.dex(Integer.valueOf((int) this.stats.getDex())).vit(Integer.valueOf((int) this.stats.getVit()))
				.wis(Integer.valueOf((int) this.stats.getWis())).hpPotions(this.hpPotions).mpPotions(this.mpPotions)
				.dyeId(Integer.valueOf(this.dyeId))
				.availableSkillPoints(Integer.valueOf(this.availableSkillPoints))
				.abilitySkillPoints(this.abilitySkillPoints != null
						? new HashMap<>(this.abilitySkillPoints) : new HashMap<>())
				.build();
	}

	// ===== Phase 2D — skill point helpers =====================================

	/** Invested level for the given abilityId (0 if none invested). */
	public int getSkillLevel(int abilityId) {
		if (this.abilitySkillPoints == null) return 0;
		final Integer v = this.abilitySkillPoints.get(abilityId);
		return v == null ? 0 : v;
	}

	/**
	 * Try to invest one skill point into {@code abilityId}. Returns true on
	 * success. Fails if no points available, the ability id is unknown, or
	 * the per-ability cap is already met. Client-side mirror — the server
	 * is authoritative; this just keeps the local Player consistent until
	 * the InvestSkillPointPacket round-trip lands.
	 */
	public boolean investSkillPoint(int abilityId) {
		if (this.availableSkillPoints <= 0) return false;
		final Ability ab = GameDataManager.ABILITIES == null ? null
				: GameDataManager.ABILITIES.get(abilityId);
		if (ab == null) return false;
		final int cap = ab.getMaxSkillPoints() <= 0 ? 5 : ab.getMaxSkillPoints();
		if (this.abilitySkillPoints == null) this.abilitySkillPoints = new HashMap<>();
		final int current = this.abilitySkillPoints.getOrDefault(abilityId, 0);
		if (current >= cap) return false;
		this.abilitySkillPoints.put(abilityId, current + 1);
		this.availableSkillPoints--;
		return true;
	}

	/**
	 * Award skill points earned by reaching levels in (prevLevel, newLevel].
	 * Rule: 1 point per even level from L2 through L20. Caps total earnable
	 * at 10. Returns the number of points actually granted.
	 */
	public int awardSkillPointsForLevels(int prevLevel, int newLevel) {
		int granted = 0;
		for (int lvl = Math.max(prevLevel + 1, 2); lvl <= newLevel && lvl <= 20; lvl++) {
			if ((lvl & 1) == 0) {  // even level
				this.availableSkillPoints++;
				granted++;
			}
		}
		return granted;
	}

	// Equipment slot layout (Phase 1B combat rework):
	//   0=weapon, 1=armor, 2=gauntlets, 3=boots, 4=ring
	// Backpack: indices [EQUIPMENT_SLOT_COUNT .. inventory.length-1].
	// Must match server-side com.openrealm.game.entity.Player.
	public static final int EQUIPMENT_SLOT_COUNT = 5;
	public static final int BACKPACK_SIZE = 20;
	public static final int INVENTORY_SIZE = EQUIPMENT_SLOT_COUNT + BACKPACK_SIZE; // 21

	private void resetInventory() {
		this.inventory = new GameItem[INVENTORY_SIZE];
	}

	public int firstEmptyInvSlot() {
		for (int i = EQUIPMENT_SLOT_COUNT; i < this.inventory.length; i++) {
			if (this.inventory[i] == null)
				return i;
		}
		return -1;
	}

	public boolean equipSlot(int slot, GameItem item) {
		this.inventory[slot] = item;
		return true;
	}

	public void equipSlots(Map<Integer, GameItem> items) {
		for (Map.Entry<Integer, GameItem> entry : items.entrySet()) {
			this.equipSlot(entry.getKey(), entry.getValue());
		}
	}

	public int findItemIndex(GameItem item) {
		for (int i = 0; i < this.inventory.length; i++) {
			if (this.inventory[i] != null && this.inventory[i].getUid().equals(item.getUid())) {
				return i;
			}
		}
		return -1;
	}

	public GameItem getSlot(int slot) {
		return this.inventory[slot];
	}

	public GameItem[] getSlots(int start, int end) {
		int size = end - start;
		int idx = 0;
		GameItem[] items = new GameItem[size];
		if (this.inventory == null)
			return items;
		int limit = Math.min(end, this.inventory.length);
		for (int i = start; i < limit; i++) {
			items[idx++] = this.inventory[i];
		}

		return items;
	}

	public int getWeaponId() {
		GameItem weapon = this.getSlot(0);
		return weapon == null ? -1 : weapon.getDamage().getProjectileGroupId();
	}

	/**
	 * Phase 1B: ability is now class-bound, not equipped. Look up via
	 * CharacterClassModel.classAbilityId — Phase 2 replaces this with the
	 * full ability tree.
	 */
	public GameItem getAbility() {
		final CharacterClassModel cls = GameDataManager.CHARACTER_CLASSES.get(this.classId);
		if (cls == null) return null;
		final int abilityId = cls.getClassAbilityId();
		if (abilityId <= 0) return null;
		return GameDataManager.GAME_ITEMS.get(abilityId);
	}

	/** Phase 2A: hotbar-slot active ability lookup (mirrors server). */
	public Ability getActiveAbility(int slot) {
		final int id = this.getHotbarId(slot);
		if (id <= 0 || GameDataManager.ABILITIES == null) return null;
		return GameDataManager.ABILITIES.get(id);
	}

	/** Passive bound to a hotbar slot, if any. */
	public PassiveAbility getSlottedPassive(int slot) {
		final int id = this.getHotbarId(slot);
		if (id <= 0 || GameDataManager.PASSIVES == null) return null;
		return GameDataManager.PASSIVES.get(id);
	}

	/** The class's always-on passive (not bindable, separate from the hotbar). */
	public PassiveAbility getClassPassive() {
		final CharacterClassModel cls = GameDataManager.CHARACTER_CLASSES.get(this.classId);
		if (cls == null || cls.getAbilityTree() == null) return null;
		final int id = cls.getAbilityTree().getPassive();
		if (id <= 0 || GameDataManager.PASSIVES == null) return null;
		return GameDataManager.PASSIVES.get(id);
	}

	public int getHotbarId(int slot) {
		if (this.hotbarBindings == null || slot < 0 || slot >= this.hotbarBindings.length) return 0;
		return this.hotbarBindings[slot];
	}

	public boolean isCasting() { return this.currentCast != null; }

	@Override
	public void update(double time) {
		super.update(time);
		Stats stats = this.getComputedStats();
		float currentHealthPercent = (float) this.getHealth() / (float) this.getComputedStats().getHp();
		float currentManaPercent = (float) this.getMana() / (float) this.getComputedStats().getMp();

		this.setHealthpercent(currentHealthPercent);
		this.setManapercent(currentManaPercent);

		if (((Instant.now().toEpochMilli() - this.lastStatsTime) >= 1000)) {
			this.lastStatsTime = System.currentTimeMillis();
			float mult = 1.0f;
			if (this.hasEffect(StatusEffectType.HEALING)) {
				mult = 1.5f;
			}
			final int vit = (int) ((0.24f * (stats.getVit() + 4.2f)) * mult);
			if (this.getHealth() < stats.getHp()) {
				int targetHealth = this.getHealth() + vit;
				if (targetHealth > stats.getHp()) {
					targetHealth = stats.getHp();
				}
				this.setHealth(targetHealth);
			} else if (this.getHealth() > stats.getHp()) {
				int targetHealth = this.getHealth() - stats.getHp();
				this.setHealth(this.getHealth() - targetHealth);
			}
			final int wis = (int) ((0.12f * (stats.getWis() + 4.2f)));
			if (this.getMana() < stats.getMp()) {
				int targetMana = this.getMana() + wis;
				if (targetMana > stats.getMp()) {
					targetMana = stats.getMp();
				}
				this.setMana(targetMana);
			}
		}
	}

	public Stats getComputedStats() {
		if (this.stats == null)
			return new Stats();
		Stats stats = this.stats.clone();
		GameItem[] equipment = this.getSlots(0, EQUIPMENT_SLOT_COUNT);
		for (GameItem item : equipment) {
			if (item != null) {
				stats = stats.concat(item.getStats());
				// Pixel-forge enchantments: each entry adds +deltaValue to the
				// matching stat. statId order: 0=VIT 1=WIS 2=HP 3=MP 4=STR 5=DEF 6=SPD 7=DEX
				if (item.getEnchantments() != null && !item.getEnchantments().isEmpty()) {
					for (Enchantment e : item.getEnchantments()) {
						final short delta = e.getDeltaValue();
						switch (e.getStatId()) {
						case 0: stats.setVit((short) (stats.getVit() + delta)); break;
						case 1: stats.setWis((short) (stats.getWis() + delta)); break;
						case 2: stats.setHp(stats.getHp() + delta); break;
						case 3: stats.setMp((short) (stats.getMp() + delta)); break;
						case 4: stats.setStr((short) (stats.getStr() + delta)); break;
						case 5: stats.setDef((short) (stats.getDef() + delta)); break;
						case 6: stats.setSpd((short) (stats.getSpd() + delta)); break;
						case 7: stats.setDex((short) (stats.getDex() + delta)); break;
						}
					}
				}
			}
		}
		// ARMOR_BROKEN zeroes defense
		if (this.hasEffect(StatusEffectType.ARMOR_BROKEN)) {
			stats.setDef((short) 0);
		}
		// ARMORED doubles defense (cannot apply while armor broken)
		else if (this.hasEffect(StatusEffectType.ARMORED)) {
			stats.setDef((short) (stats.getDef() * 2));
		}
		return stats;
	}

	public void drinkHp() {
		this.stats.setHp((short) (this.stats.getHp() + 5));
	}

	public void drinkMp() {
		this.stats.setMp((short) (this.stats.getMp() + 5));
	}

	public boolean addHpPotion() {
		if (this.hpPotions >= MAX_CONSUMABLE_POTIONS) return false;
		this.hpPotions++;
		return true;
	}

	public boolean addMpPotion() {
		if (this.mpPotions >= MAX_CONSUMABLE_POTIONS) return false;
		this.mpPotions++;
		return true;
	}

	public boolean consumeHpPotion() {
		if (this.hpPotions <= 0) return false;
		this.hpPotions--;
		this.health = Math.min(this.health + 100, this.getComputedStats().getHp());
		return true;
	}

	public boolean consumeMpPotion() {
		if (this.mpPotions <= 0) return false;
		this.mpPotions--;
		this.mana = Math.min(this.mana + 100, this.getComputedStats().getMp());
		return true;
	}

	@Override
	public float getHealthpercent() {
		return this.healthpercent;
	}

	@Override
	public int getHealth() {
		return this.health;
	}

	@Override
	public int getMana() {
		return this.mana;
	}

	@Override
	public void updateEffectState() {
		if (this.getSpriteSheet() == null)
			return;
		// Priority order matches the web client: more "loud" / overriding
		// effects take precedence over passive ones. INVINCIBLE / STASIS /
		// BERSERK win over things like POISONED so the player can read at a
		// glance what's most impactful.
		final Sprite.EffectEnum target;
		if (this.hasEffect(StatusEffectType.INVINCIBLE))      target = Sprite.EffectEnum.INVINCIBLE;
		else if (this.hasEffect(StatusEffectType.STASIS))     target = Sprite.EffectEnum.STASIS;
		else if (this.hasEffect(StatusEffectType.BERSERK))    target = Sprite.EffectEnum.BERSERK;
		else if (this.hasEffect(StatusEffectType.DAMAGING))   target = Sprite.EffectEnum.DAMAGING;
		else if (this.hasEffect(StatusEffectType.STUNNED))    target = Sprite.EffectEnum.STUNNED;
		else if (this.hasEffect(StatusEffectType.PARALYZED))  target = Sprite.EffectEnum.GRAYSCALE;
		else if (this.hasEffect(StatusEffectType.DAZED))      target = Sprite.EffectEnum.DAZED;
		else if (this.hasEffect(StatusEffectType.CURSED))     target = Sprite.EffectEnum.CURSED;
		else if (this.hasEffect(StatusEffectType.POISONED))   target = Sprite.EffectEnum.POISONED;
		else if (this.hasEffect(StatusEffectType.ARMOR_BROKEN)) target = Sprite.EffectEnum.ARMOR_BROKEN;
		else if (this.hasEffect(StatusEffectType.ARMORED))    target = Sprite.EffectEnum.ARMORED;
		else if (this.hasEffect(StatusEffectType.HEALING))    target = Sprite.EffectEnum.REDISH;
		else if (this.hasEffect(StatusEffectType.HEAL))       target = Sprite.EffectEnum.REDISH;
		else if (this.hasEffect(StatusEffectType.SPEEDY))     target = Sprite.EffectEnum.DECAY;
		else if (this.hasEffect(StatusEffectType.HIDDEN))  target = Sprite.EffectEnum.SEPIA;
		else if (this.hasNoEffects())                         target = Sprite.EffectEnum.NORMAL;
		else                                                  target = Sprite.EffectEnum.NORMAL;

		if (!this.getSpriteSheet().hasEffect(target)) {
			this.getSpriteSheet().setEffect(target);
		}
	}

	@Override
	public void render(SpriteBatch batch) {
		// PlayState's batched world render calls renderBody(batch) on each
		// entity, NOT render(). This method is kept for direct callers
		// (e.g. char-select preview) but the in-world player draw goes
		// through renderBody() below, which is where the lerped render
		// position MUST be applied to fix the per-tick lurch.
		if (this.getSpriteSheet() == null) return;
		this.updateEffectState();
		this.renderBody(batch);
	}

	/**
	 * Override Entity.renderBody so the LOCAL player draws at its lerped
	 * render position (set every frame in PlayState.input()) rather than
	 * pos.x / pos.y (which advances in 1/64s tick steps and causes a
	 * visible per-tick "lurch" between camera and sprite).
	 *
	 * Non-local players have renderX=NaN -> effective position falls back
	 * to pos.x / pos.y, identical to the old behaviour.
	 */
	/** One-shot warn log per (player-id, state-bit) so the diagnostic
	 *  output is bounded. Static map keeps Player free of an extra
	 *  Lombok-tracked field that would otherwise propagate into
	 *  @AllArgsConstructor. */
	private static final ConcurrentHashMap<Long, Byte> RENDER_DBG =
			new ConcurrentHashMap<>();
	private static final byte DBG_NULL_SHEET   = 1;
	private static final byte DBG_NULL_FRAME   = 2;
	private static final byte DBG_RENDERED_OK  = 4;

	private boolean dbgFirstSeen(long id, byte bit) {
		final Byte cur = RENDER_DBG.get(id);
		final byte b = cur == null ? 0 : cur;
		if ((b & bit) != 0) return false;
		RENDER_DBG.put(id, (byte) (b | bit));
		return true;
	}

	@Override
	public void renderOutline(SpriteBatch batch) {
		if (this.getSpriteSheet() == null) return;
		final TextureRegion frame = this.getSpriteSheet().getCurrentFrame();
		if (frame == null) return;
		final int rw = frame.getRegionWidth();
		final int rh = frame.getRegionHeight();
		if (rw <= 0 || rh <= 0) return;
		// Scale visual render size proportionally with collision size so the
		// /size command actually resizes the character. Default ratio
		// (PLAYER_RENDER_SIZE / PLAYER_SIZE = 32/28) is preserved when size
		// is at its baseline of 28.
		final float sizeScale = (float) this.size / GlobalConstants.PLAYER_SIZE;
		final int rs = Math.round(GlobalConstants.PLAYER_RENDER_SIZE * sizeScale);
		final float offset = (rs - this.size) / 2f;
		final float px = this.getEffectiveRenderX();
		final float py = this.getEffectiveRenderY();
		final float wx = (px - Vector2f.worldX) - offset;
		final float wy = (py - Vector2f.worldY) - offset;
		// Match body draw rect (see renderBody) so the outline aligns with
		// wide/tall attack frames. 1 source-pixel padding around the rect
		// gives the outline shader its neighbor sample.
		final int refW = this.getSpriteSheet().getSpriteImageWidth();
		final int refH = this.getSpriteSheet().getSpriteImageHeight();
		if (refW <= 0 || refH <= 0) return;
		final float unitX = (float) rs / refW;
		final float unitY = (float) rs / refH;
		final float drawW = rw * unitX;
		final float drawH = rh * unitY;
		final float padX = unitX;
		final float padY = unitY;
		final float drawY = wy + rs - drawH;
		if (this.left) {
			batch.draw(frame, wx + rs + padX, drawY - padY,
					-(drawW + 2 * padX), drawH + 2 * padY);
		} else {
			batch.draw(frame, wx - padX, drawY - padY,
					drawW + 2 * padX, drawH + 2 * padY);
		}
	}

	@Override
	public void renderBody(SpriteBatch batch) {
		if (this.getSpriteSheet() == null) {
			if (dbgFirstSeen(this.getId(), DBG_NULL_SHEET)) {
				log.warn("[RENDER] Player {} ({}) skipped: spriteSheet=null classId={}",
						this.getId(), this.getName(), this.classId);
			}
			return;
		}
		final TextureRegion frame = this.getSpriteSheet().getCurrentFrame();
		if (frame == null) {
			if (dbgFirstSeen(this.getId(), DBG_NULL_FRAME)) {
				log.warn("[RENDER] Player {} ({}) skipped: currentFrame=null classId={} frameCount={}",
						this.getId(), this.getName(), this.classId,
						this.getSpriteSheet().getFrameCount());
			}
			return;
		}
		// Scale visual render size proportionally with collision size so the
		// /size command actually resizes the character. Default ratio
		// (PLAYER_RENDER_SIZE / PLAYER_SIZE = 32/28) is preserved when size
		// is at its baseline of 28.
		final float sizeScale = (float) this.size / GlobalConstants.PLAYER_SIZE;
		final int rs = Math.round(GlobalConstants.PLAYER_RENDER_SIZE * sizeScale);
		final float offset = (rs - this.size) / 2f;
		final float px = this.getEffectiveRenderX();
		final float py = this.getEffectiveRenderY();
		final float wx = (px - Vector2f.worldX) - offset;
		final float wy = (py - Vector2f.worldY) - offset;
		if (dbgFirstSeen(this.getId(), DBG_RENDERED_OK)) {
			log.info("[RENDER] Player {} ({}) drawing at effRender({}, {}) pos=({}, {}) renderXY=({}, {}) size={} rs={}",
					this.getId(), this.getName(),
					px, py,
					this.pos == null ? Float.NaN : this.pos.x,
					this.pos == null ? Float.NaN : this.pos.y,
					this.renderX, this.renderY,
					this.size, rs);
		}
		// Mask-based dye recolor (web parity: renderer.js getDyedRegion).
		// We replace the rendered TextureRegion outright with a recolored
		// copy that has only the masked clothing/accessory pixels shifted
		// to the dye color, luminance preserved. Falls back to the raw
		// frame when the dye is 0, the dye registry hasn't loaded, or
		// this particular cell has no mask entry — so a missing mask
		// degrades to the un-dyed sprite instead of crashing.
		TextureRegion drawFrame = frame;
		if (this.dyeId > 0) {
			final TextureRegion dyed = resolveDyedRegion(frame);
			if (dyed != null) drawFrame = dyed;
		}
		// Scale draw rect by frame region vs reference cell so a wide
		// attack frame extends past the body's right edge instead of being
		// squished. Body anchored at bottom-left of the frame; wider frames
		// extend right (mirrored when facing left), taller frames extend up.
		final int refW = this.getSpriteSheet().getSpriteImageWidth();
		final int refH = this.getSpriteSheet().getSpriteImageHeight();
		final int rw = frame.getRegionWidth();
		final int rh = frame.getRegionHeight();
		if (refW <= 0 || refH <= 0 || rw <= 0 || rh <= 0) {
			if (this.left) batch.draw(drawFrame, wx, wy, rs * 0.5f, rs * 0.5f, rs, rs, -1f, 1f, 0f);
			else            batch.draw(drawFrame, wx, wy, rs * 0.5f, rs * 0.5f, rs, rs, 1f, 1f, 0f);
			return;
		}
		final float unitX = (float) rs / refW;
		final float unitY = (float) rs / refH;
		final float drawW = rw * unitX;
		final float drawH = rh * unitY;
		final float drawY = wy + rs - drawH;
		final float drawX = this.left ? (wx + rs - drawW) : wx;
		final float flipX = this.left ? -1f : 1f;

		// Water-sink: while standing on a slowing tile, draw only the top
		// portion of the frame so the bottom third reads as submerged legs
		// (web parity: renderer.js wadingClip = 0.30, masked sprite).
		TextureRegion bodyRegion = drawFrame;
		float bodyH = drawH;
		if (this.wading) {
			final int keepRows = Math.max(1, Math.round(rh * (1f - WADING_CLIP_FRACTION)));
			bodyRegion = new TextureRegion(drawFrame, 0, 0, rw, keepRows);
			bodyH = drawH * (1f - WADING_CLIP_FRACTION);
		}

		// Outline: dark offset copies behind the body, matching the webclient's
		// addSpriteWithOutline halo. The bottom copy is skipped while wading so
		// the waterline edge stays a clean cut rather than an outlined rim.
		final float prevColor = batch.getPackedColor();
		batch.setColor(0f, 0f, 0f, BODY_OUTLINE_ALPHA);
		batch.draw(bodyRegion, drawX - BODY_OUTLINE_OFFSET, drawY, drawW * 0.5f, bodyH * 0.5f, drawW, bodyH, flipX, 1f, 0f);
		batch.draw(bodyRegion, drawX + BODY_OUTLINE_OFFSET, drawY, drawW * 0.5f, bodyH * 0.5f, drawW, bodyH, flipX, 1f, 0f);
		batch.draw(bodyRegion, drawX, drawY - BODY_OUTLINE_OFFSET, drawW * 0.5f, bodyH * 0.5f, drawW, bodyH, flipX, 1f, 0f);
		if (!this.wading) {
			batch.draw(bodyRegion, drawX, drawY + BODY_OUTLINE_OFFSET, drawW * 0.5f, bodyH * 0.5f, drawW, bodyH, flipX, 1f, 0f);
		}
		batch.setPackedColor(prevColor);

		batch.draw(bodyRegion, drawX, drawY, drawW * 0.5f, bodyH * 0.5f, drawW, bodyH, flipX, 1f, 0f);
	}

	/** Fraction of the sprite hidden at the bottom while wading (web parity). */
	private static final float WADING_CLIP_FRACTION = 0.30f;
	/** Player body outline offset (world px → 2 screen px at WORLD_SCALE). */
	private static final float BODY_OUTLINE_OFFSET = 1f;
	private static final float BODY_OUTLINE_ALPHA = 0.85f;

	/** Resolve the current sprite cell to a dyed TextureRegion. The
	 *  cell coordinates come from the source TextureRegion's region
	 *  bounds — libGDX flips the Y axis when the SpriteSheet is built,
	 *  so we read regionY relative to the texture height. */
	private TextureRegion resolveDyedRegion(TextureRegion frame) {
		final AnimationModel anim = GameDataManager.ANIMATIONS != null
				? GameDataManager.ANIMATIONS.get(this.classId) : null;
		if (anim == null) {
			dyeWarnOnce("anim-null-" + this.classId,
					"[DYE] No AnimationModel for classId={}, dye won't apply", this.classId);
			return null;
		}
		final String spriteKey = anim.getSpriteKey();
		final int spW = anim.getSpriteSize() > 0 ? anim.getSpriteSize() : 8;
		final int spH = anim.getEffectiveSpriteHeight() > 0 ? anim.getEffectiveSpriteHeight() : spW;
		if (frame == null || frame.getTexture() == null) return null;
		// SpriteSheet calls TextureRegion.flip(false, true) on every cell so
		// the image renders right-side-up against our Y-down ortho camera.
		// As a side effect getRegionY() returns the BOTTOM edge of the
		// original cell (= (i+1)*spH) instead of the top. Reading it
		// directly off-by-ones every row -> CLASS_MASK_FRAMES lookup
		// missed and dyeing silently fell back to the un-recolored sprite.
		// Compensate by subtracting regionHeight on flipped regions
		// (same idea for X if anyone ever flips horizontally — unflipped
		// regions are unaffected because the subtract collapses to 0
		// when the flag is false).
		final int spX = frame.isFlipX()
				? frame.getRegionX() - frame.getRegionWidth()
				: frame.getRegionX();
		final int spY = frame.isFlipY()
				? frame.getRegionY() - frame.getRegionHeight()
				: frame.getRegionY();
		final int col = spX / spW;
		final int row = spY / spH;
		// Frame dims come from the current TextureRegion: idle frames match
		// the cell, attack frames may be wider/taller. Passing them through
		// keeps the dyed Pixmap the same size as the source slice so the
		// renderer's drawW/drawH math doesn't stretch a cell-sized region
		// across a frame-sized destination rect.
		final int frameW = frame.getRegionWidth();
		final int frameH = frame.getRegionHeight();
		final TextureRegion dyed = SpriteRecolorCache.getDyedRegion(
				spriteKey, this.classId, row, col, spW, spH, frameW, frameH, this.dyeId);
		if (dyed == null) {
			dyeWarnOnce("dye-miss-" + this.classId + "-" + row + "-" + col + "-" + this.dyeId,
					"[DYE] Recolor returned null for classId={} sheet={} row={} col={} dyeId={}",
					this.classId, spriteKey, row, col, this.dyeId);
		} else {
			dyeWarnOnce("dye-hit-" + this.classId + "-" + this.dyeId,
					"[DYE] Recolor applied for classId={} sheet={} dyeId={} (first hit)",
					this.classId, spriteKey, this.dyeId);
		}
		return dyed;
	}

	/** One-shot diagnostic logger to keep the hot render path quiet. */
	private static final java.util.Set<String> DYE_WARNED =
			java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static void dyeWarnOnce(String key, String fmt, Object... args) {
		if (DYE_WARNED.add(key)) log.warn(fmt, args);
	}


	public void input(MouseHandler mouse, KeyHandler key) {
		if (key.up.down) {
			this.up = true;
		} else {
			this.up = false;
		}
		if (key.down.down) {
			this.down = true;
		} else {
			this.down = false;
		}
		if (key.left.down) {
			this.left = true;
		} else {
			this.left = false;
		}
		if (key.right.down) {
			this.right = true;
		} else {
			this.right = false;
		}
		if (this.up && this.down) {
			this.up = false;
			this.down = false;
		}
		if (this.right && this.left) {
			this.right = false;
			this.left = false;
		}
	}

	public void queueInput(int seq, float vx, float vy) {
		if (this.inputQueue == null) this.inputQueue = new ConcurrentLinkedQueue<>();
		if (seq > this.lastProcessedInputSeq) {
			this.inputQueue.add(new float[]{(float) seq, vx, vy});
		}
	}

	public int getUpperExperienceBound() {
		if (this.experience > GameDataManager.EXPERIENCE_LVLS.maxExperience())
			return GameDataManager.EXPERIENCE_LVLS.maxExperience();

		final Tuple<Integer, Integer> expRange = GameDataManager.EXPERIENCE_LVLS.getParsedMap()
				.get(GameDataManager.EXPERIENCE_LVLS.getLevel(this.experience));

		return expRange.getY();
	}

	public float getExperiencePercent() {
		if (this.experience > GameDataManager.EXPERIENCE_LVLS.maxExperience())
			return 1.0f;

		final Tuple<Integer, Integer> expRange = GameDataManager.EXPERIENCE_LVLS.getParsedMap()
				.get(GameDataManager.EXPERIENCE_LVLS.getLevel(this.experience));

		return ((float) this.experience / (float) expRange.getY());
	}

	public float getHealthPercent() {
		return this.healthpercent;
	}

	public float getManaPercent() {
		return this.manapercent;
	}

	public int incrementExperience(long experience) {
		final long newExperience = this.getExperience() + experience;
		final int currentLevel = GameDataManager.EXPERIENCE_LVLS.getLevel(this.experience);
		final int newLevel = GameDataManager.EXPERIENCE_LVLS.getLevel(newExperience);
		final CharacterClassModel classModel = GameDataManager.CHARACTER_CLASSES.get(this.getClassId());
		final int levelsGained = newLevel - currentLevel;
		if (levelsGained > 0) {
			// Apply random stat increases for EACH level gained
			for (int i = 0; i < levelsGained; i++) {
				this.setStats(this.getStats().concat(classModel.getRandomLevelUpStats()));
			}
			// Restore health and mana to new max after all stat increases
			this.setHealth(this.stats.getHp());
			this.setMana(this.stats.getMp());
			// Phase 2D — award skill points for every even level reached in
			// (currentLevel, newLevel]. Server is authoritative; this is a
			// local mirror so the HUD pip count tracks the level-up in real
			// time before the next stats sync lands.
			this.awardSkillPointsForLevels(currentLevel, newLevel);
		}
		this.setExperience(newExperience);
		return levelsGained;
	}

	public void applyUpdate(UpdatePacket packet, PlayState state) {
		this.name = packet.getPlayerName();
		this.stats = packet.getStats().asStats();
		this.inventory = packet.getInventory() == null ? null
				: IOService.mapModel(packet.getInventory(), GameItem[].class);
		if (this.inventory != null) {
			for (GameItem item : this.inventory) {
				if (item != null) {
					GameDataManager.loadSpriteModel(item);
				}
			}
		}
		this.health = packet.getHealth();
		this.mana = packet.getMana();
		// Server-authoritative potion counts. Without these the local count was only
		// ever set at login + local pickup/drink prediction, so a realm switch (press-R
		// nexus) left it reading 0 until you picked another up.
		this.hpPotions = packet.getHpPotions();
		this.mpPotions = packet.getMpPotions();
		if (packet.getPlayerId() == state.getPlayerId()) {
			state.getPui().setEquipment(this.inventory);
		}
		this.experience = packet.getExperience();
		// Update animation speeds based on current stats
		if (this.getSpriteSheet() != null && this.stats != null) {
			this.getSpriteSheet().updateDurationsFromStats(this.stats.getSpd(), this.stats.getDex());
		}
	}

	public void applyState(PlayerStatePacket packet) {
		this.health = packet.getHealth();
		this.mana = packet.getMana();
		this.setEffectIds(packet.getEffectIds());
		this.setEffectTimes(packet.getEffectTimes());
	}

	public int numStatsMaxed() {
		int count = 0;
		for (int i = 0; i < 8; i++) {
			if (this.isStatMaxed(i)) {
				count++;
			}
		}
		return count;
	}

	public boolean isStatMaxed(int statIdx) {
		final CharacterClassModel characterClass = GameDataManager.CHARACTER_CLASSES.get(this.classId);
		final Stats maxStats = characterClass.getMaxStats();
		boolean maxed = false;
		switch (statIdx) {
		case 0:
			maxed = this.stats.getHp() >= maxStats.getHp();
			break;
		case 1:
			maxed = this.stats.getMp() >= maxStats.getMp();
			break;
		case 2:
			maxed = this.stats.getDef() >= maxStats.getDef();
			break;
		case 3:
			maxed = this.stats.getStr() >= maxStats.getStr();
			break;
		case 4:
			maxed = this.stats.getSpd() >= maxStats.getSpd();
			break;
		case 5:
			maxed = this.stats.getDex() >= maxStats.getDex();
			break;
		case 6:
			maxed = this.stats.getVit() >= maxStats.getVit();
			break;
		case 7:
			maxed = this.stats.getWis() >= maxStats.getWis();
			break;
		}
		return maxed;
	}

	public boolean canConsume(final GameItem item) {
		boolean canConsume = true;
		if (((item.getStats().getHp() > 0) && this.isStatMaxed(0))
				|| ((item.getStats().getMp() > 0) && this.isStatMaxed(1))) {
			canConsume = false;
		} else if (((item.getStats().getMp() > 0) && this.isStatMaxed(1))
				|| ((item.getStats().getDef() > 0) && this.isStatMaxed(2))) {
			canConsume = false;
		} else if (((item.getStats().getStr() > 0) && this.isStatMaxed(3))
				|| ((item.getStats().getSpd() > 0) && this.isStatMaxed(4))) {
			canConsume = false;
		} else if (((item.getStats().getDex() > 0) && this.isStatMaxed(5))
				|| ((item.getStats().getVit() > 0) && this.isStatMaxed(6))) {
			canConsume = false;
		} else if ((item.getStats().getWis() > 0) && this.isStatMaxed(7)) {
			canConsume = false;
		}
		return canConsume;
	}

	public boolean getIsUp() {
		return this.up;
	}

	public boolean getIsDown() {
		return this.down;
	}

	public boolean getIsLeft() {
		return this.left;
	}

	public boolean getIsRight() {
		return this.right;
	}

	public void write(DataOutputStream stream) throws Exception {
		stream.writeLong(this.getId());
		stream.writeUTF(this.getName());
		stream.writeUTF(this.accountUuid);
		stream.writeUTF(this.characterUuid);
		stream.writeInt(this.getClassId());
		stream.writeShort(this.getSize());
		stream.writeFloat(this.getPos().x);
		stream.writeFloat(this.getPos().y);
		stream.writeFloat(this.dx);
		stream.writeFloat(this.dy);
	}

	public GameItem[] selectGameItems(Boolean[] selectedIdx) {
		GameItem[] inv = this.getSlots(EQUIPMENT_SLOT_COUNT, EQUIPMENT_SLOT_COUNT + 8);
		if (selectedIdx.length != inv.length) {
			System.err.println("SELECT GAME ITEM IDX SIZES NOT EQUAL");
			return null;
		}
		List<GameItem> selected = new ArrayList<>();
		for (int i = 0; i < inv.length; i++) {
			if (inv[i] == null)
				continue;

			if (selectedIdx[i] != null && selectedIdx[i]) {
				selected.add(inv[i]);
			}
		}
		return selected.toArray(new GameItem[0]);
	}

	public NetGameItemRef[] getInventoryAsNetGameItemRefs() {
		final GameItem[] inv = this.getSlots(EQUIPMENT_SLOT_COUNT, EQUIPMENT_SLOT_COUNT + 8);
		final List<NetGameItemRef> results = new ArrayList<>();
		for (int i = 0; i < inv.length; i++) {
			if (inv[i] == null)
				continue;
			results.add(inv[i].asNetGameItemRef(i + EQUIPMENT_SLOT_COUNT));
		}
		return results.toArray(new NetGameItemRef[0]);

	}

	public void addItems(GameItem[] items) {
		for (GameItem item : items) {
			if (item == null)
				continue;
			// Stackable items (shards, essence) merge into existing stacks of
			// the same itemId before spilling into a free slot, mirroring the
			// pickup logic in ServerItemHelper. This keeps trade-received
			// stacks consistent with how loot pickups behave.
			if (item.isStackable()) {
				int remaining = item.getStackCount();
				for (int i = EQUIPMENT_SLOT_COUNT; i < this.inventory.length && remaining > 0; i++) {
					final GameItem existing = this.inventory[i];
					if (existing == null) continue;
					if (existing.getItemId() != item.getItemId()) continue;
					if (!existing.isStackable()) continue;
					final int room = existing.getMaxStack() - existing.getStackCount();
					if (room <= 0) continue;
					final int move = Math.min(room, remaining);
					existing.setStackCount(existing.getStackCount() + move);
					remaining -= move;
				}
				if (remaining > 0) {
					final int slot = this.firstEmptyInvSlot();
					if (slot == -1) break;
					item.setStackCount(remaining);
					this.inventory[slot] = item;
				}
				continue;
			}
			int slot = this.firstEmptyInvSlot();
			if (slot == -1)
				break;
			this.inventory[slot] = item;
		}
	}

	public void removeItems(GameItem[] items) {
		final GameItem[] inv = this.getSlots(EQUIPMENT_SLOT_COUNT, EQUIPMENT_SLOT_COUNT + 8);

		for (int i = 0; i < inv.length; i++) {
			GameItem invItem = inv[i];
			if (invItem == null)
				continue;
			for (GameItem toRemove : items) {
				if (invItem.getUid() != null && invItem.getUid().equals(toRemove.getUid())) {
					this.inventory[i + EQUIPMENT_SLOT_COUNT] = null;
					break;
				}
			}
		}
	}

	@Override
	public String toString() {
		return this.getId() + " , Pos: " + this.pos.toString() + ", Class: " + this.getClassId() + ", Headless: "
				+ this.isHeadless();
	}

}
