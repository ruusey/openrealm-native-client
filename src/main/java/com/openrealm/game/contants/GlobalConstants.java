package com.openrealm.game.contants;

public class GlobalConstants {

    public static final float LOOT_TIER_UPGRADE_BASE_PERCENT = 0.0f;
    public static final float LOOT_TIER_UPGRADE_PER_DIFFICULTY = 5.0f;
    public static final float LOOT_TIER_UPGRADE_MIN_DIFFICULTY = 1.0f;

    /** Fraction of total damage a player must deal to qualify for soulbound drops. */
    public static final float SOULBOUND_DAMAGE_THRESHOLD = 0.05f;

    // Items at or above these tiers drop in CYAN bags, lower tiers in PURPLE.
    public static final byte CYAN_BAG_MIN_WEAPON_TIER = 9;
    public static final byte CYAN_BAG_MIN_ABILITY_TIER = 11;
    public static final byte CYAN_BAG_MIN_ARMOR_TIER = 9;

    public static final int BASE_SIZE = 32;

    public static final int BASE_TILE_SIZE = 32;

    public static final int BASE_SPRITE_SIZE = 8;

    /** Base tiles within this Euclidean RGB distance are treated as one material and not feathered.
     *  Non-final: GameSpriteManager.tilesShouldBlend re-reads it and clears its cache when it changes. */
    public static float TILE_BLEND_MIN_COLOR_DIST = 36f;

    public static final int PLAYER_CAP = 40;

    public static final int TILE_SIZE_NORM = 64;

    public static final int MEDIUM_ART_SIZE = 16;

    public static final int LARGE_ART_SIZE = 32;

    public static final int PLAYER_SIZE = 28;

    public static final int PLAYER_RENDER_SIZE = 32;

    /** radius = sprite_size * factor. Mirror of BULLET_HIT_RADIUS_FACTOR in game.js - keep in sync. */
    public static final float HIT_RADIUS_FACTOR = 0.4f;

    // Difficulty-based enemy damage scaling, applied before defense is subtracted.
    // Dungeons use a lower threshold so they hit harder than the same-numbered overworld zone.
    public static final float DAMAGE_SCALE_MIN_DIFFICULTY = 2.0f;
    public static final float DAMAGE_SCALE_DUNGEON_MIN_DIFFICULTY = 1.0f;
    public static final float DAMAGE_SCALE_PER_LEVEL = 0.10f;
    public static final float DAMAGE_SCALE_KNEE_DIFFICULTY = 6.0f;         // slope halves past this difficulty
    public static final float DAMAGE_SCALE_PER_LEVEL_AFTER_KNEE = 0.05f;
    public static final float DAMAGE_SCALE_CAP = 2.0f;

    // High to survive alt-tab on web: backgrounded tabs throttle the heartbeat interval.
    public static final long SOCKET_READ_TIMEOUT = 60000;
}
