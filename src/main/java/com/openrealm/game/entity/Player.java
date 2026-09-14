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
import com.openrealm.game.entity.item.AttributeModifier;
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
	// True once an UpdatePacket populated this.stats: the wire value is already
	// getComputedStats() (base+equipment+enchants+gems+buffs), so getComputedStats()
	// must NOT re-sum equipment or it double-counts. The embedded server role
	// leaves this false (this.stats is raw base, re-summing is correct).
	private transient boolean statsServerComputed = false;
	private boolean headless;
	@Builder.Default
	private boolean bot = false;
	// "sysadmin" | "admin" | "mod" | "editor" | "" — chat name coloring.
	@Builder.Default
	private String chatRole = "";
	@Builder.Default
	private int lastInputSeq = 0;
	@Builder.Default
	private int lastProcessedInputSeq = 0;
	@Builder.Default
	private float currentVx = 0f;
	@Builder.Default
	private float currentVy = 0f;
	// Queue elements: float[]{seq (cast from int), vx, vy}
	@Builder.Default
	private transient Queue<float[]> inputQueue = new ConcurrentLinkedQueue<>();

	public static final int MAX_CONSUMABLE_POTIONS = 6;
	public static final int HP_POTION_ITEM_ID = 296;
	public static final int MP_POTION_ITEM_ID = 297;
	@Builder.Default
	private int hpPotions = 0;
	@Builder.Default
	private int mpPotions = 0;

	// Cosmetic dye id in dye-assets.json; 0 = none. Persisted on the character,
	// implicitly cleared on permadeath (fresh char starts at 0).
	@Builder.Default
	private int dyeId = 0;

	// Account fame refreshed at login + after each fame-shop purchase. NOT
	// serialized to clients; server uses it to validate purchases without a refetch.
	@Builder.Default
	private transient long cachedAccountFame = 0L;

	// Visual interpolation override; NaN means "use pos.x/pos.y". pos advances
	// in 1/64s tick steps and rendering that directly lurches against the lerped
	// camera. Declared BEFORE abilityCooldowns/currentCast/hotbarBindings so
	// Lombok's generated ctor matches the explicit all-args ctor below.
	@Builder.Default
	private transient float renderX = Float.NaN;
	@Builder.Default
	private transient float renderY = Float.NaN;

	// Phase 2A runtime state. Hotbar seeds from
	// CharacterClassModel.abilityTree.defaultHotbar on spawn; mutations via
	// HotbarSwapPacket. Transient — re-seeds on login until Phase 2B persists.
	@Builder.Default
	private transient long[] abilityCooldowns = new long[4];
	@Builder.Default
	private transient CastState currentCast = null;
	@Builder.Default
	private transient int[] hotbarBindings = new int[]{0, 0, 0, 0};

	// Phase 2D — skill-point pool + per-ability investment (1 per even level
	// L2..L20, 10 total). Per-ability caps from Ability.maxSkillPoints.
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
		// @Builder.Default strips the inline `= Float.NaN` from the class, so
		// the no-arg ctor (NetPlayer.toPlayer) would leave renderX/Y at 0.0f
		// and draw every remote player at world (0,0). Set NaN explicitly here.
		this.renderX = Float.NaN;
		this.renderY = Float.NaN;
	}

	public Player(GameItem[] inventory, long lastStatsTime, LootContainer currentLootContainer, int classId,
			String accountUuid, String characterUuid, long experience, Stats stats, boolean statsServerComputed,
			boolean headless, boolean bot,
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
		this.statsServerComputed = statsServerComputed;
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
		// Same @Builder.Default inline-init strip as the no-arg ctor: set NaN
		// so the first-frame minimap doesn't plot the local dot at (0,0).
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

	/** Invest one skill point into {@code abilityId} (client mirror; server is
	 *  authoritative). Fails on no points, unknown ability, or cap reached. */
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
			if ((lvl & 1) == 0) {
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
	// Backpack is two 20-slot pages (Main 5..24, Backpack 25..44) shown as tabs.
	public static final int BACKPACK_SIZE = 40;
	public static final int INVENTORY_SIZE = EQUIPMENT_SLOT_COUNT + BACKPACK_SIZE; // 45
	// Only the Main page (first 20 backpack slots) is tradeable.
	public static final int TRADE_SLOT_COUNT = 20;

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

	/** Class-bound ability (not equipped) via CharacterClassModel.classAbilityId. */
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
		// statsServerComputed => the wire value already folded in equipment; re-summing double-counts.
		if (this.statsServerComputed)
			return this.stats.clone();
		Stats stats = this.stats.clone();
		GameItem[] equipment = this.getSlots(0, EQUIPMENT_SLOT_COUNT);
		for (GameItem item : equipment) {
			if (item != null) {
				stats = stats.concat(item.getStats());
				// Enchantment statId order: 0=VIT 1=WIS 2=HP 3=MP 4=STR 5=DEF 6=SPD 7=DEX
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

	/** Additive equipment contribution (item stats + attribute modifiers +
	 *  enchantments). Must mirror the server so the reconstructed base reads
	 *  maxed off the leveled stat alone. Scaling gems / buffs are left out. */
	public Stats getEquipmentBonus() {
		Stats bonus = new Stats();
		final GameItem[] equipment = this.getSlots(0, EQUIPMENT_SLOT_COUNT);
		for (final GameItem item : equipment) {
			if (item == null) continue;
			bonus = bonus.concat(item.getStats());
			if (item.getAttributeModifiers() != null) {
				for (final AttributeModifier m : item.getAttributeModifiers()) {
					addStatDelta(bonus, m.getStatId(), m.getDeltaValue());
				}
			}
			if (item.getEnchantments() != null) {
				for (final Enchantment e : item.getEnchantments()) {
					addStatDelta(bonus, e.getStatId(), e.getDeltaValue());
				}
			}
		}
		return bonus;
	}

	/** statId order: 0=VIT 1=WIS 2=HP 3=MP 4=STR 5=DEF 6=SPD 7=DEX. */
	private static void addStatDelta(Stats bonus, int statId, int delta) {
		switch (statId) {
		case 0: bonus.setVit((short) (bonus.getVit() + delta)); break;
		case 1: bonus.setWis((short) (bonus.getWis() + delta)); break;
		case 2: bonus.setHp(bonus.getHp() + delta); break;
		case 3: bonus.setMp((short) (bonus.getMp() + delta)); break;
		case 4: bonus.setStr((short) (bonus.getStr() + delta)); break;
		case 5: bonus.setDef((short) (bonus.getDef() + delta)); break;
		case 6: bonus.setSpd((short) (bonus.getSpd() + delta)); break;
		case 7: bonus.setDex((short) (bonus.getDex() + delta)); break;
		}
	}

	/** Leveled stats (wire value minus equipment); a stat is "maxed" only when
	 *  this base hits the cap, not when equipment pushes it over. */
	public Stats getBaseStats() {
		if (this.stats == null) return new Stats();
		// Embedded-server role stores raw base; only the networked client folds equipment in.
		if (!this.statsServerComputed) return this.stats.clone();
		return this.stats.subtract(this.getEquipmentBonus());
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
		// Priority order matches the web client: louder effects win over passive ones.
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
		// For direct callers (e.g. char-select preview); the in-world draw goes
		// through renderBody(), where the lerped render position is applied.
		if (this.getSpriteSheet() == null) return;
		this.updateEffectState();
		this.renderBody(batch);
	}

	/** One-shot warn log per (player-id, state-bit); static so it isn't a
	 *  Lombok-tracked field feeding @AllArgsConstructor. */
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
		// Scale render size with collision size so /size resizes the character.
		final float sizeScale = (float) this.size / GlobalConstants.PLAYER_SIZE;
		final int rs = Math.round(GlobalConstants.PLAYER_RENDER_SIZE * sizeScale);
		final float offset = (rs - this.size) / 2f;
		final float px = this.getEffectiveRenderX();
		final float py = this.getEffectiveRenderY();
		final float wx = (px - Vector2f.worldX) - offset;
		final float wy = (py - Vector2f.worldY) - offset;
		// Match body draw rect (see renderBody); 1 source-pixel padding for the shader sample.
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

	/** No-op: the player draws its own outline in renderBody at the lerped
	 *  position; the base renderStroke would lag it by a frame (detached shadow). */
	@Override
	public void renderStroke(SpriteBatch batch) {
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
		// Scale render size with collision size so /size resizes the character.
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
		// Mask-based dye recolor (web parity). Falls back to the raw frame when
		// dye is 0, the registry hasn't loaded, or the cell has no mask entry.
		TextureRegion drawFrame = frame;
		if (this.dyeId > 0) {
			final TextureRegion dyed = resolveDyedRegion(frame);
			if (dyed != null) drawFrame = dyed;
		}
		// Scale draw rect by frame region vs reference cell: body anchored at
		// bottom-left, so wider frames extend right (mirrored left), taller up.
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

		// Water-sink: draw only the top of the frame so the bottom reads as submerged legs.
		TextureRegion bodyRegion = drawFrame;
		float bodyH = drawH;
		float bodyDrawY = drawY;
		if (this.wading) {
			final float keep = 1f - WADING_CLIP_FRACTION;
			final int keepRows = Math.max(1, Math.round(rh * keep));
			// drawFrame is Y-flipped, so its display top is the low-V edge. Slice
			// keepRows from that edge in UNFLIPPED pixel space then restore the
			// flip — slicing off the flipped coords picks the wrong band (garble).
			final int texH = drawFrame.getTexture().getHeight();
			final int cellTopY = Math.min(Math.round(drawFrame.getV() * texH),
					Math.round(drawFrame.getV2() * texH));
			bodyRegion = new TextureRegion(drawFrame.getTexture(),
					drawFrame.getRegionX(), cellTopY, rw, keepRows);
			bodyRegion.flip(false, true);
			bodyH = drawH * keep;
			bodyDrawY = drawY + (drawH - bodyH);
		}

		// Outline: 8 dark offset copies behind the body; the bottom (+Y) copies
		// are skipped while wading so the waterline edge stays a clean cut.
		final float prevColor = batch.getPackedColor();
		batch.setColor(0f, 0f, 0f, BODY_OUTLINE_ALPHA);
		batch.draw(bodyRegion, drawX - BODY_OUTLINE_OFFSET, bodyDrawY,                      drawW * 0.5f, bodyH * 0.5f, drawW, bodyH, flipX, 1f, 0f);
		batch.draw(bodyRegion, drawX + BODY_OUTLINE_OFFSET, bodyDrawY,                      drawW * 0.5f, bodyH * 0.5f, drawW, bodyH, flipX, 1f, 0f);
		batch.draw(bodyRegion, drawX,                       bodyDrawY - BODY_OUTLINE_OFFSET, drawW * 0.5f, bodyH * 0.5f, drawW, bodyH, flipX, 1f, 0f);
		batch.draw(bodyRegion, drawX - BODY_OUTLINE_OFFSET, bodyDrawY - BODY_OUTLINE_OFFSET, drawW * 0.5f, bodyH * 0.5f, drawW, bodyH, flipX, 1f, 0f);
		batch.draw(bodyRegion, drawX + BODY_OUTLINE_OFFSET, bodyDrawY - BODY_OUTLINE_OFFSET, drawW * 0.5f, bodyH * 0.5f, drawW, bodyH, flipX, 1f, 0f);
		if (!this.wading) {
			batch.draw(bodyRegion, drawX,                       bodyDrawY + BODY_OUTLINE_OFFSET, drawW * 0.5f, bodyH * 0.5f, drawW, bodyH, flipX, 1f, 0f);
			batch.draw(bodyRegion, drawX - BODY_OUTLINE_OFFSET, bodyDrawY + BODY_OUTLINE_OFFSET, drawW * 0.5f, bodyH * 0.5f, drawW, bodyH, flipX, 1f, 0f);
			batch.draw(bodyRegion, drawX + BODY_OUTLINE_OFFSET, bodyDrawY + BODY_OUTLINE_OFFSET, drawW * 0.5f, bodyH * 0.5f, drawW, bodyH, flipX, 1f, 0f);
		}
		batch.setPackedColor(prevColor);

		batch.draw(bodyRegion, drawX, bodyDrawY, drawW * 0.5f, bodyH * 0.5f, drawW, bodyH, flipX, 1f, 0f);
	}

	/** Fraction of the sprite hidden at the bottom while wading (web parity). */
	private static final float WADING_CLIP_FRACTION = 0.30f;
	private static final float BODY_OUTLINE_OFFSET = 1f;
	private static final float BODY_OUTLINE_ALPHA = 0.85f;

	/** Resolve the current sprite cell to a dyed TextureRegion. */
	private TextureRegion resolveDyedRegion(TextureRegion frame) {
		final AnimationModel anim = GameDataManager.getAnimation("player", this.classId);
		if (anim == null) {
			dyeWarnOnce("anim-null-" + this.classId,
					"[DYE] No AnimationModel for classId={}, dye won't apply", this.classId);
			return null;
		}
		final String spriteKey = anim.getSpriteKey();
		final int spW = anim.getSpriteSize() > 0 ? anim.getSpriteSize() : 8;
		final int spH = anim.getEffectiveSpriteHeight() > 0 ? anim.getEffectiveSpriteHeight() : spW;
		if (frame == null || frame.getTexture() == null) return null;
		// SpriteSheet flips each cell, so getRegionY() returns the cell's BOTTOM
		// edge, not the top; subtract regionHeight on flipped regions to recover
		// the row (unflipped regions collapse the subtract to 0).
		final int spX = frame.isFlipX()
				? frame.getRegionX() - frame.getRegionWidth()
				: frame.getRegionX();
		final int spY = frame.isFlipY()
				? frame.getRegionY() - frame.getRegionHeight()
				: frame.getRegionY();
		final int col = spX / spW;
		final int row = spY / spH;
		// Pass the frame's own dims (attack frames differ from the cell) so the
		// dyed Pixmap matches the source slice and isn't stretched on draw.
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
	private static final Set<String> DYE_WARNED = ConcurrentHashMap.newKeySet();
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
			for (int i = 0; i < levelsGained; i++) {
				this.setStats(this.getStats().concat(classModel.getRandomLevelUpStats()));
			}
			this.setHealth(this.stats.getHp());
			this.setMana(this.stats.getMp());
			// Local mirror of the server's skill-point award so the HUD tracks it immediately.
			this.awardSkillPointsForLevels(currentLevel, newLevel);
		}
		this.setExperience(newExperience);
		return levelsGained;
	}

	public void applyUpdate(UpdatePacket packet, PlayState state) {
		this.name = packet.getPlayerName();
		this.stats = packet.getStats().asStats();
		this.statsServerComputed = true;
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
		// Server-authoritative potion counts (a realm switch otherwise left the local count stale).
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
		// Compare the BASE (leveled) stat to the cap, not the equipment-folded wire value.
		final Stats s = this.getBaseStats();
		boolean maxed = false;
		switch (statIdx) {
		case 0:
			maxed = s.getHp() >= maxStats.getHp();
			break;
		case 1:
			maxed = s.getMp() >= maxStats.getMp();
			break;
		case 2:
			maxed = s.getDef() >= maxStats.getDef();
			break;
		case 3:
			maxed = s.getStr() >= maxStats.getStr();
			break;
		case 4:
			maxed = s.getSpd() >= maxStats.getSpd();
			break;
		case 5:
			maxed = s.getDex() >= maxStats.getDex();
			break;
		case 6:
			maxed = s.getVit() >= maxStats.getVit();
			break;
		case 7:
			maxed = s.getWis() >= maxStats.getWis();
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
		GameItem[] inv = this.getSlots(EQUIPMENT_SLOT_COUNT, EQUIPMENT_SLOT_COUNT + TRADE_SLOT_COUNT);
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
		final GameItem[] inv = this.getSlots(EQUIPMENT_SLOT_COUNT, EQUIPMENT_SLOT_COUNT + TRADE_SLOT_COUNT);
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
			// Stackable items merge into existing same-itemId stacks before
			// spilling into a free slot (mirrors ServerItemHelper pickup).
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
		// Remove exactly ONE slot per item: match by reference then UID, stopping
		// at the first hit. clone() copies the uid, so UID-equal slots are common
		// — matching all of them would wipe a whole row.
		for (GameItem toRemove : items) {
			if (toRemove == null)
				continue;
			for (int i = EQUIPMENT_SLOT_COUNT; i < EQUIPMENT_SLOT_COUNT + TRADE_SLOT_COUNT; i++) {
				final GameItem invItem = this.inventory[i];
				if (invItem == null)
					continue;
				if (invItem == toRemove
						|| (invItem.getUid() != null && invItem.getUid().equals(toRemove.getUid()))) {
					this.inventory[i] = null;
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
