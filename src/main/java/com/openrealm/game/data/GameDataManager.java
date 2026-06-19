package com.openrealm.game.data;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openrealm.game.contants.CharacterClass;
import com.openrealm.game.contants.GlobalConstants;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.graphics.Sprite;
import com.openrealm.game.model.AnimationModel;
import com.openrealm.game.model.ability.Ability;
import com.openrealm.game.model.ability.PassiveAbility;
import com.openrealm.game.model.CharacterClassModel;
import com.openrealm.game.model.ClassMaskModel;
import com.openrealm.game.model.DyeAssetModel;
import com.openrealm.game.model.DungeonGraphNode;
import com.openrealm.game.model.EnemyModel;
import com.openrealm.game.model.ExperienceModel;
import com.openrealm.game.model.LootContainerModel;
import com.openrealm.game.model.LootGroupModel;
import com.openrealm.game.model.LootTableModel;
import com.openrealm.game.model.MapModel;
import com.openrealm.game.model.PortalModel;
import com.openrealm.game.model.Projectile;
import com.openrealm.game.model.ProjectileGroup;
import com.openrealm.game.model.WeaponArchetypeModel;
import com.openrealm.game.model.RealmEventModel;
import com.openrealm.game.model.SetPieceModel;
import com.openrealm.game.model.TerrainGenerationParameters;
import com.openrealm.game.model.TileModel;
import com.openrealm.net.client.ClientGameLogic;
import com.openrealm.net.core.IOService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GameDataManager {
	public static final transient ObjectMapper JSON_MAPPER = new ObjectMapper()
		.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	// {{...}} angle placeholders — PI_EXPR matches unit-circle radian forms:
	// PI, 2*PI, 2PI, PI/4, 3*PI/2, 3PI/2, -PI/2, 1.5*PI. Keep in sync with the
	// server's GameDataManager.
	private static final Pattern INJECT_VAR = Pattern.compile("\\{\\{(.*?)}}");
	private static final Pattern PI_EXPR = Pattern.compile("^(-?\\d*\\.?\\d*)\\*?PI(?:/(-?\\d*\\.?\\d+))?$");

	public static Map<Integer, ProjectileGroup>               PROJECTILE_GROUPS = null;
	public static Map<Byte, WeaponArchetypeModel>             WEAPON_ARCHETYPES = null;
	public static Map<Integer, GameItem>                      GAME_ITEMS = null;
	public static Map<Integer, EnemyModel>                    ENEMIES = null;
	public static Map<Integer, TileModel>                     TILES = null;
	public static Map<Integer, MapModel>                      MAPS = null;
	public static Map<Integer, TerrainGenerationParameters>   TERRAINS = null;
	public static Map<Integer, PortalModel>                   PORTALS = null;
	public static Map<Integer, CharacterClassModel>           CHARACTER_CLASSES = null;
	public static Map<Integer, LootTableModel>                LOOT_TABLES = null;
	public static Map<Integer, LootGroupModel>                LOOT_GROUPS = null;
	public static Map<Byte, LootContainerModel>               LOOT_CONTAINERS = null;
	public static ExperienceModel                             EXPERIENCE_LVLS = null;
	public static Map<String, DungeonGraphNode>               DUNGEON_GRAPH = null;
	public static Map<String, AnimationModel>                 ANIMATIONS = null;
	public static Map<Integer, SetPieceModel>                 SETPIECES = null;
	public static Map<Integer, RealmEventModel>               REALM_EVENTS = null;
	// Phase 2A — mirrors server-side ability/passive registries.
	public static Map<Integer, Ability>                       ABILITIES = null;
	public static Map<Integer, PassiveAbility>                PASSIVES = null;
	// Web-parity recolor data (renderer.js getDyedRegion). DYE_ASSETS maps
	// dyeId -> recolor strategy; CLASS_MASK_FRAMES is keyed by
	// "classId:row:col" so the renderer can look up a per-frame pixel
	// mask in O(1) at draw time.
	public static Map<Integer, DyeAssetModel>                 DYE_ASSETS = null;
	public static Map<Integer, ClassMaskModel>                CLASS_MASKS = null;
	public static Map<String, ClassMaskModel.Frame>           CLASS_MASK_FRAMES = null;

	private static void loadLootGroups(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Loot Groups...");
		GameDataManager.LOOT_GROUPS = new HashMap<>();
		String text = null;
		if (remote) {
			text = ClientGameLogic.DATA_SERVICE.executeGet("game-data/loot-groups.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader()
					.getResourceAsStream("data/loot-groups.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		LootGroupModel[] lootGroups = GameDataManager.JSON_MAPPER.readValue(text, LootGroupModel[].class);
		for (LootGroupModel lootGroup : lootGroups) {
			GameDataManager.LOOT_GROUPS.put(lootGroup.getLootGroupId(), lootGroup);
		}
		GameDataManager.log.info("Loading Loot Groups... DONE");
	}

	private static void loadLootTables(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Loot Tables...");
		GameDataManager.LOOT_TABLES = new HashMap<>();
		String text = null;
		if (remote) {
			text = ClientGameLogic.DATA_SERVICE.executeGet("game-data/loot-tables.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader()
					.getResourceAsStream("data/loot-tables.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		LootTableModel[] lootTables = GameDataManager.JSON_MAPPER.readValue(text, LootTableModel[].class);
		for (LootTableModel lootTable : lootTables) {
			GameDataManager.LOOT_TABLES.put(lootTable.getEnemyId(), lootTable);
		}
		GameDataManager.log.info("Loading Loot Tables... DONE");
	}

	private static void loadCharacterClasses(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Character Classes...");
		GameDataManager.CHARACTER_CLASSES = new HashMap<>();
		String text = null;
		if (remote) {
			text = ClientGameLogic.DATA_SERVICE.executeGet("game-data/character-classes.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader()
					.getResourceAsStream("data/character-classes.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		CharacterClassModel[] characterClasses = GameDataManager.JSON_MAPPER.readValue(text,
				CharacterClassModel[].class);
		for (CharacterClassModel characterClass : characterClasses) {
			GameDataManager.CHARACTER_CLASSES.put(characterClass.getClassId(), characterClass);
		}
		GameDataManager.log.info("Loading Character Classes... DONE");
	}

	private static void loadExperienceModel(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading ExperienceModel...");
		String text = null;
		if (remote) {
			text = ClientGameLogic.DATA_SERVICE.executeGet("game-data/exp-levels.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader()
					.getResourceAsStream("data/exp-levels.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		ExperienceModel expModel = GameDataManager.JSON_MAPPER.readValue(text, ExperienceModel.class);
		expModel.parseMap();
		GameDataManager.EXPERIENCE_LVLS = expModel;
		GameDataManager.log.info("Loading ExperienceModel... DONE");
	}

	private static void loadPortals(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Portals...");
		GameDataManager.PORTALS = new HashMap<>();
		String text = null;
		if (remote) {
			text = ClientGameLogic.DATA_SERVICE.executeGet("game-data/portals.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader().getResourceAsStream("data/portals.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		PortalModel[] maps = GameDataManager.JSON_MAPPER.readValue(text, PortalModel[].class);
		for (PortalModel map : maps) {
			GameDataManager.PORTALS.put(map.getPortalId(), map);
		}
		GameDataManager.log.info("Loading Portals... DONE");
	}

	private static void loadDungeonGraph(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Dungeon Graph...");
		GameDataManager.DUNGEON_GRAPH = new HashMap<>();
		String text = null;
		if (remote) {
			text = ClientGameLogic.DATA_SERVICE.executeGet("game-data/dungeon-graph.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader().getResourceAsStream("data/dungeon-graph.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		DungeonGraphNode[] nodes = GameDataManager.JSON_MAPPER.readValue(text, DungeonGraphNode[].class);
		for (DungeonGraphNode node : nodes) {
			GameDataManager.DUNGEON_GRAPH.put(node.getNodeId(), node);
		}
		GameDataManager.log.info("Loading Dungeon Graph... DONE ({} nodes)", GameDataManager.DUNGEON_GRAPH.size());
	}

	private static void loadTerrains(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Terrains...");
		GameDataManager.TERRAINS = new HashMap<>();
		String text = null;
		if (remote) {
			text = ClientGameLogic.DATA_SERVICE.executeGet("game-data/terrains.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader().getResourceAsStream("data/terrains.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		TerrainGenerationParameters[] maps = GameDataManager.JSON_MAPPER.readValue(text,
				TerrainGenerationParameters[].class);
		for (TerrainGenerationParameters map : maps) {
			GameDataManager.TERRAINS.put(map.getTerrainId(), map);
		}
		GameDataManager.log.info("Loading Terrains... DONE");
	}

	private static void loadMaps(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Maps... ");
		GameDataManager.MAPS = new HashMap<>();
		String text = null;
		if (remote) {
			text = ClientGameLogic.DATA_SERVICE.executeGet("game-data/maps.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader().getResourceAsStream("data/maps.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		MapModel[] maps = GameDataManager.JSON_MAPPER.readValue(text, MapModel[].class);
		for (MapModel map : maps) {
			GameDataManager.MAPS.put(map.getMapId(), map);
		}
		GameDataManager.log.info("Loading Maps... DONE");
	}

	private static void loadTiles(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Tiles...");
		GameDataManager.TILES = new HashMap<>();
		String text = null;
		if (remote) {
			text = ClientGameLogic.DATA_SERVICE.executeGet("game-data/tiles.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader().getResourceAsStream("data/tiles.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		TileModel[] tiles = GameDataManager.JSON_MAPPER.readValue(text, TileModel[].class);
		for (TileModel tile : tiles) {
			GameDataManager.TILES.put(tile.getTileId(), tile);
		}
		GameDataManager.log.info("Loading Tiles... DONE");
	}

	private static void loadEnemies(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Enemies...");
		GameDataManager.ENEMIES = new HashMap<>();
		String text = null;
		if (remote) {
			text = ClientGameLogic.DATA_SERVICE.executeGet("game-data/enemies.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader().getResourceAsStream("data/enemies.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		EnemyModel[] enemies = GameDataManager.JSON_MAPPER.readValue(text, EnemyModel[].class);
		for (EnemyModel enemy : enemies) {
			GameDataManager.ENEMIES.put(enemy.getEnemyId(), enemy);
		}
		GameDataManager.log.info("Loading Enemies... DONE");
	}

	private static void loadProjectileGroups(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Projectile Groups...");

		GameDataManager.PROJECTILE_GROUPS = new HashMap<>();
		String text = null;
		if (remote) {
			text = ClientGameLogic.DATA_SERVICE.executeGet("game-data/projectile-groups.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader()
					.getResourceAsStream("data/projectile-groups.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		ProjectileGroup[] projectileGroups = GameDataManager.JSON_MAPPER.readValue(text, ProjectileGroup[].class);

		for (ProjectileGroup group : projectileGroups) {
			if ((group.getAngleOffset() != null) && group.getAngleOffset().contains("{{")) {
				group.setAngleOffset(GameDataManager.replaceInjectVariables(group.getAngleOffset()));
			} else {
				group.setAngleOffset("0");
			}
			for (Projectile p : group.getProjectiles()) {
				if (p.getAngle().contains("{{")) {
					p.setAngle(GameDataManager.replaceInjectVariables(p.getAngle()));
				}
			}
			GameDataManager.PROJECTILE_GROUPS.put(group.getProjectileGroupId(), group);
		}
		GameDataManager.log.info("Loading Projectile Groups... DONE");

	}

	private static void loadWeaponArchetypes(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Weapon Archetypes...");
		GameDataManager.WEAPON_ARCHETYPES = new HashMap<>();
		String text;
		if (remote) {
			text = ClientGameLogic.DATA_SERVICE.executeGet("game-data/weapon-archetypes.json", null);
		} else {
			final InputStream in = GameDataManager.class.getClassLoader()
					.getResourceAsStream("data/weapon-archetypes.json");
			if (in == null) {
				GameDataManager.log.warn("weapon-archetypes.json missing — shot prediction will fall back to baseline 1.0 multipliers");
				return;
			}
			text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		final WeaponArchetypeModel[] models =
				GameDataManager.JSON_MAPPER.readValue(text, WeaponArchetypeModel[].class);
		for (final WeaponArchetypeModel m : models) {
			GameDataManager.WEAPON_ARCHETYPES.put(m.getId(), m);
		}
		GameDataManager.log.info("Loading Weapon Archetypes... DONE ({} entries)",
				GameDataManager.WEAPON_ARCHETYPES.size());
	}

	private static void loadAnimations(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Animations...");
		GameDataManager.ANIMATIONS = new HashMap<>();
		String text = null;
		if (remote) {
			text = ClientGameLogic.DATA_SERVICE.executeGet("game-data/animations.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader()
					.getResourceAsStream("data/animations.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		AnimationModel[] animations = GameDataManager.JSON_MAPPER.readValue(text, AnimationModel[].class);
		for (AnimationModel anim : animations) {
			GameDataManager.ANIMATIONS.put(animationKey(anim.getObjectType(), anim.getObjectId()), anim);
		}
		GameDataManager.log.info("Loading Animations... DONE ({} entries)", GameDataManager.ANIMATIONS.size());
	}

	/** Player classIds and enemyIds overlap, so animations are keyed by type ("player"/"enemy") + id. */
	public static String animationKey(String objectType, int objectId) {
		final String type = (objectType == null || objectType.isBlank()) ? "player" : objectType.toLowerCase();
		return type + ":" + objectId;
	}

	public static AnimationModel getAnimation(String objectType, int objectId) {
		return GameDataManager.ANIMATIONS == null ? null
				: GameDataManager.ANIMATIONS.get(animationKey(objectType, objectId));
	}

	private static void loadSetPieces(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading SetPieces...");
		GameDataManager.SETPIECES = new HashMap<>();
		String text = null;
		if (remote) {
			text = ClientGameLogic.DATA_SERVICE.executeGet("game-data/setpieces.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader()
					.getResourceAsStream("data/setpieces.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		SetPieceModel[] pieces = GameDataManager.JSON_MAPPER.readValue(text, SetPieceModel[].class);
		for (SetPieceModel piece : pieces) {
			GameDataManager.SETPIECES.put(piece.getSetPieceId(), piece);
		}
		GameDataManager.log.info("Loading SetPieces... DONE ({} entries)", GameDataManager.SETPIECES.size());
	}

	private static void loadRealmEvents(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Realm Events...");
		GameDataManager.REALM_EVENTS = new HashMap<>();
		String text = null;
		if (remote) {
			text = ClientGameLogic.DATA_SERVICE.executeGet("game-data/realm-events.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader()
					.getResourceAsStream("data/realm-events.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		RealmEventModel[] events = GameDataManager.JSON_MAPPER.readValue(text, RealmEventModel[].class);
		for (RealmEventModel event : events) {
			GameDataManager.REALM_EVENTS.put(event.getEventId(), event);
		}
		GameDataManager.log.info("Loading Realm Events... DONE ({} entries)", GameDataManager.REALM_EVENTS.size());
	}

	private static void loadDyeAssets(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Dye Assets...");
		GameDataManager.DYE_ASSETS = new HashMap<>();
		String text = null;
		if (remote) {
			text = ClientGameLogic.DATA_SERVICE.executeGet("game-data/dye-assets.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader()
					.getResourceAsStream("data/dye-assets.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		DyeAssetModel[] dyes = GameDataManager.JSON_MAPPER.readValue(text, DyeAssetModel[].class);
		for (DyeAssetModel d : dyes) {
			GameDataManager.DYE_ASSETS.put(d.getDyeId(), d);
		}
		GameDataManager.log.info("Loading Dye Assets... DONE ({} entries)", GameDataManager.DYE_ASSETS.size());
	}

	private static void loadClassMasks(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Class Masks...");
		GameDataManager.CLASS_MASKS = new HashMap<>();
		GameDataManager.CLASS_MASK_FRAMES = new HashMap<>();
		String text = null;
		if (remote) {
			text = ClientGameLogic.DATA_SERVICE.executeGet("game-data/character-class-masks.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader()
					.getResourceAsStream("data/character-class-masks.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		ClassMaskModel[] entries = GameDataManager.JSON_MAPPER.readValue(text, ClassMaskModel[].class);
		for (ClassMaskModel m : entries) {
			GameDataManager.CLASS_MASKS.put(m.getClassId(), m);
			if (m.getFrames() != null) {
				for (ClassMaskModel.Frame f : m.getFrames()) {
					GameDataManager.CLASS_MASK_FRAMES.put(
							m.getClassId() + ":" + f.getRow() + ":" + f.getCol(), f);
				}
			}
		}
		GameDataManager.log.info("Loading Class Masks... DONE ({} classes, {} frames)",
				GameDataManager.CLASS_MASKS.size(), GameDataManager.CLASS_MASK_FRAMES.size());
	}

	private static void loadAbilities(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Abilities...");
		GameDataManager.ABILITIES = new HashMap<>();
		String text = null;
		if (remote) {
			text = ClientGameLogic.DATA_SERVICE.executeGet("game-data/abilities.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader().getResourceAsStream("data/abilities.json");
			if (inputStream == null) {
				GameDataManager.log.info("Loading Abilities... DONE (no local file, empty table)");
				return;
			}
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		if (text == null || text.isBlank() || text.trim().equals("[]")) {
			GameDataManager.log.info("Loading Abilities... DONE (empty)");
			return;
		}
		final Ability[] abilities = GameDataManager.JSON_MAPPER.readValue(text, Ability[].class);
		for (final Ability a : abilities) {
			GameDataManager.ABILITIES.put(a.getId(), a);
		}
		GameDataManager.log.info("Loading Abilities... DONE ({} entries)", GameDataManager.ABILITIES.size());
	}

	private static void loadPassives(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Passives...");
		GameDataManager.PASSIVES = new HashMap<>();
		String text = null;
		if (remote) {
			text = ClientGameLogic.DATA_SERVICE.executeGet("game-data/passives.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader().getResourceAsStream("data/passives.json");
			if (inputStream == null) {
				GameDataManager.log.info("Loading Passives... DONE (no local file, empty table)");
				return;
			}
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		if (text == null || text.isBlank() || text.trim().equals("[]")) {
			GameDataManager.log.info("Loading Passives... DONE (empty)");
			return;
		}
		final PassiveAbility[] passives = GameDataManager.JSON_MAPPER.readValue(text, PassiveAbility[].class);
		for (final PassiveAbility p : passives) {
			GameDataManager.PASSIVES.put(p.getId(), p);
		}
		GameDataManager.log.info("Loading Passives... DONE ({} entries)", GameDataManager.PASSIVES.size());
	}

	private static void loadGameItems(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Game Items...");

		GameDataManager.GAME_ITEMS = new HashMap<>();
		String text = null;
		if (remote) {
			text = ClientGameLogic.DATA_SERVICE.executeGet("game-data/game-items.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader()
					.getResourceAsStream("data/game-items.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		GameItem[] gameItems = GameDataManager.JSON_MAPPER.readValue(text, GameItem[].class);

		for (GameItem item : gameItems) {
			GameDataManager.GAME_ITEMS.put(item.getItemId(), item);
		}
		GameDataManager.log.info("Loading Game Items... DONE");
	}

	private static void loadLootContainers(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Loot Containers...");
		GameDataManager.LOOT_CONTAINERS = new HashMap<>();
		String text = null;
		if (remote) {
			text = ClientGameLogic.DATA_SERVICE.executeGet("game-data/loot-containers.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader()
					.getResourceAsStream("data/loot-containers.json");
			if (inputStream != null) {
				text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
			}
		}
		if (text == null) {
			GameDataManager.log.warn("loot-containers.json missing; loot bags will fall back to hardcoded sprite slices");
			return;
		}
		LootContainerModel[] models = GameDataManager.JSON_MAPPER.readValue(text, LootContainerModel[].class);
		for (LootContainerModel m : models) {
			GameDataManager.LOOT_CONTAINERS.put((byte) m.getTierId(), m);
		}
		GameDataManager.log.info("Loading Loot Containers... DONE ({} entries)",
				GameDataManager.LOOT_CONTAINERS.size());
	}

	private static Sprite loadLootContainerSprite(byte tier, int fallbackCol, int fallbackRow, String fallbackSheet) {
		final LootContainerModel model = GameDataManager.LOOT_CONTAINERS != null
				? GameDataManager.LOOT_CONTAINERS.get(tier) : null;
		if (model != null && model.getSpriteKey() != null) {
			if (model.getSpriteSize() == 0) {
				model.setSpriteSize(GlobalConstants.BASE_SPRITE_SIZE);
			}
			return GameSpriteManager.loadSprite(model);
		}
		return GameSpriteManager.loadSprite(fallbackCol, fallbackRow, fallbackSheet,
				GlobalConstants.BASE_SPRITE_SIZE);
	}

	// Loot bag sprites — data-driven from loot-containers.json. Falls back to
	// the rotmg-misc.png row 9 / rotmg-projectiles defaults if the data file
	// is missing or doesn't define the requested tier.
	public static Sprite getLootSprite(int tier) {
		final byte t = (byte) tier;
		final int fallbackCol = (tier >= 0 && tier < 5) ? tier : 0;
		return loadLootContainerSprite(t, fallbackCol, 9, "rotmg-misc.png");
	}

	public static Sprite getGraveSprite() {
		return loadLootContainerSprite((byte) 5, 3, 1, "rotmg-projectiles.png");
	}

	public static Sprite getChestSprite() {
		return loadLootContainerSprite((byte) -1, 2, 0, "rotmg-projectiles.png");
	}

	public static Map<Integer, GameItem> getStartingEquipment(final CharacterClass characterClass) {
		final CharacterClassModel model = GameDataManager.CHARACTER_CLASSES.get(characterClass.classId);
		return model.getStartingEquipmentMap();
	}

	private static String replaceInjectVariables(String input) {
		Matcher matcher = INJECT_VAR.matcher(input);
		while (matcher.find()) {
			final String match = matcher.group(1);
			final float angle = evalPiExpression(match);
			if (!Float.isNaN(angle)) {
				input = GameDataManager.replaceGen(input, match, Float.toString(angle));
			}
		}
		return input;
	}

	/**
	 * Evaluate a unit-circle radian expression to radians: PI, 2*PI, 2PI, PI/4,
	 * 3*PI/2, 3PI/2, -PI/2, 1.5*PI, etc. Returns NaN when it isn't a PI form.
	 */
	public static float evalPiExpression(final String expr) {
		if (expr == null) return Float.NaN;
		final String e = expr.replace(" ", "");
		if (!e.contains("PI")) return Float.NaN;
		final Matcher m = PI_EXPR.matcher(e);
		if (!m.matches()) return Float.NaN;
		final String coefStr = m.group(1);
		final String divStr = m.group(2);
		final float coef = (coefStr == null || coefStr.isEmpty()) ? 1f
				: (coefStr.equals("-") ? -1f : Float.parseFloat(coefStr));
		final float div = (divStr == null || divStr.isEmpty()) ? 1f : Float.parseFloat(divStr);
		return (float) (coef * Math.PI / div);
	}

	/**
	 * Resolve an angle FIELD value to radians, accepting either a plain number
	 * ("0.5") or a unit-circle placeholder ("{{PI/2}}", "{{3*PI/4}}").
	 */
	public static float parseAngleValue(final String s) {
		if (s == null || s.isEmpty()) return 0f;
		final Matcher m = INJECT_VAR.matcher(s.trim());
		final String inner = m.find() ? m.group(1) : s.trim();
		final float pi = evalPiExpression(inner);
		if (!Float.isNaN(pi)) return pi;
		try {
			return Float.parseFloat(inner);
		} catch (final NumberFormatException e) {
			return 0f;
		}
	}

	public static void loadSpriteModel(GameItem item) {
		if (item.getItemId() > -1) {
			final GameItem fetched = GameDataManager.GAME_ITEMS.get(item.getItemId());
			item.applySpriteModel(fetched);
		}
	}

	public static DungeonGraphNode getEntryNode() {
		for (DungeonGraphNode node : DUNGEON_GRAPH.values()) {
			if (node.isEntryPoint()) return node;
		}
		return null;
	}

	public static DungeonGraphNode getNodeForPortal(String parentNodeId, int portalId) {
		DungeonGraphNode parent = DUNGEON_GRAPH.get(parentNodeId);
		if (parent == null) return null;
		for (Map.Entry<String, Integer> entry : parent.getPortalDropNodeMap().entrySet()) {
			if (entry.getValue() == portalId) {
				return DUNGEON_GRAPH.get(entry.getKey());
			}
		}
		return null;
	}

	public static String replaceGen(String source, String variable, String value) {
		final String text = source.replace("{{" + variable + "}}", value);
		return text;
	}

	public static void loadGameData(final boolean loadRemote) {
		GameDataManager.log.info("Loading Game Data from remote={}", loadRemote);
		// Load each data type independently so one failure doesn't prevent loading the rest
		Runnable[] loaders = {
			() -> { try { GameDataManager.loadProjectileGroups(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load projectile groups: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadWeaponArchetypes(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load weapon archetypes: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadGameItems(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load game items: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadEnemies(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load enemies: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadTiles(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load tiles: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadMaps(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load maps: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadTerrains(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load terrains: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadPortals(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load portals: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadDungeonGraph(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load dungeon graph: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadExperienceModel(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load experience model: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadCharacterClasses(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load character classes: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadLootTables(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load loot tables: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadLootGroups(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load loot groups: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadLootContainers(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load loot containers: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadAnimations(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load animations: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadSetPieces(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load set pieces: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadRealmEvents(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load realm events: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadAbilities(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load abilities: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadPassives(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load passives: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadDyeAssets(loadRemote); } catch (Exception e) {
				GameDataManager.log.error("Failed to load dye assets remotely ({}); trying local fallback", e.getMessage());
				try { GameDataManager.loadDyeAssets(false); } catch (Exception e2) { GameDataManager.log.error("Local dye-assets fallback failed: {}", e2.getMessage()); }
			} },
			() -> { try { GameDataManager.loadClassMasks(loadRemote); } catch (Exception e) {
				GameDataManager.log.error("Failed to load class masks remotely ({}); trying local fallback", e.getMessage());
				try { GameDataManager.loadClassMasks(false); } catch (Exception e2) { GameDataManager.log.error("Local class-masks fallback failed: {}", e2.getMessage()); }
			} },
			() -> { try { com.openrealm.game.ui.atlas.UiAtlas.load(loadRemote); } catch (Exception e) {
				GameDataManager.log.error("Failed to load UI atlas remotely ({}); trying local fallback", e.getMessage());
				try { com.openrealm.game.ui.atlas.UiAtlas.load(false); } catch (Exception e2) { GameDataManager.log.error("Local UI atlas fallback failed: {}", e2.getMessage()); }
			} },
		};
		for (Runnable loader : loaders) {
			loader.run();
		}
		try {
			IOService.mapSerializableData();
		} catch (Exception e) {
			GameDataManager.log.error("Failed to map serializable data: {}", e.getMessage());
		}
	}
}
