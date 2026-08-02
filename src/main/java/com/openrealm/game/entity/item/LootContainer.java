package com.openrealm.game.entity.item;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import java.time.Instant;
import java.util.UUID;

import com.openrealm.game.Settings;
import com.openrealm.game.contants.LootTier;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.graphics.Sprite;
import com.openrealm.game.math.Vector2f;
import com.openrealm.net.realm.Realm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LootContainer {

    public static final int SIZE = 10;

    private static final float OUTLINE_OFFSET = 1f;
    private static final float OUTLINE_ALPHA = 0.85f;

    private long lootContainerId;
    private LootTier tier;
    private Sprite sprite;
    private String uid;
    private GameItem[] items;
    private Vector2f pos;

    private long spawnedTime;

    private boolean contentsChanged;

    // Soulbound loot: -1 means public (anyone can see/pickup),
    // otherwise only the player with this ID can see/interact with this bag
    @Builder.Default
    private long soulboundPlayerId = -1;

    public LootContainer(LootTier tier, Vector2f pos) {
        this.tier = tier;
        this.sprite = LootTier.getLootSprite(tier.tierId);
        this.uid = UUID.randomUUID().toString();
        this.items = new GameItem[SIZE];
        this.pos = pos;
        this.items[0] = GameDataManager.GAME_ITEMS.get(Realm.RANDOM.nextInt(8));
        for (int i = 1; i < (Realm.RANDOM.nextInt(7) + 1); i++) {
            this.items[i] = GameDataManager.GAME_ITEMS.get(Realm.RANDOM.nextInt(152) + 1);
        }
        this.spawnedTime = System.currentTimeMillis();
        this.tier = this.determineTier();
        this.soulboundPlayerId = -1;
    }

    public boolean isPublicLoot() {
        return this.soulboundPlayerId == -1;
    }

    public boolean isVisibleToPlayer(long playerId) {
        return this.soulboundPlayerId == -1 || this.soulboundPlayerId == playerId;
    }

    public boolean getContentsChanged() {
        return this.contentsChanged;
    }

    public LootContainer(LootTier tier, Vector2f pos, GameItem loot) {
        this.tier = tier;
        this.sprite = LootTier.getLootSprite(tier.tierId);
        this.pos = pos;
        this.uid = UUID.randomUUID().toString();
        this.items = new GameItem[SIZE];
        this.items[0] = loot;
        this.spawnedTime = Instant.now().toEpochMilli();
        this.tier = this.determineTier();
    }

    public LootContainer(LootTier tier, Vector2f pos, GameItem[] loot) {
        this.tier = tier;
        this.sprite = LootTier.getLootSprite(tier.tierId);
        this.pos = pos;
        this.uid = UUID.randomUUID().toString();
        // Pack items contiguously from slot 0 with no gaps.
        // Arrays.copyOf(loot, 8) would leave nulls between items if the
        // source had gaps; instead, filter nulls and pack to the front.
        this.items = new GameItem[SIZE];
        int slot = 0;
        for (GameItem item : loot) {
            if (item != null && slot < SIZE) {
                this.items[slot++] = item;
            }
        }
        this.spawnedTime = Instant.now().toEpochMilli();
        this.tier = this.determineTier();
    }

    public boolean isExpired() {
        return (Instant.now().toEpochMilli() - this.spawnedTime) > 45000;
    }

    public boolean isEmpty() {
        for (GameItem item : this.items) {
            if (item != null)
                return false;
        }
        return true;
    }

    public boolean hasUntieredItem() {
        if (this.tier.equals(LootTier.CHEST) || this.tier.equals(LootTier.GRAVE))
            return false;
        for (GameItem item : this.items) {
            if ((item != null) && (item.getTier() == (byte) -1))
                return true;
        }
        return false;
    }

    /**
     * Determine the appropriate loot tier based on the items inside.
     * WHITE(4): any untiered item (tier -1)
     * BLUE(3): only potions (consumable items)
     * CYAN(2): any item tier 8+
     * PURPLE(1): tiered items 0-7, plus all forge materials (crystals + essences)
     * BROWN(0): fallback / empty
     * CHEST, GRAVE, and BOOSTED are never reclassified — callers set those
     * explicitly and the auto-classifier would clobber them based on contents.
     *
     * Forge materials (crystals, essences) are forced into PURPLE regardless
     * of their authored tier — crystals are tier 8 (would land in CYAN) and
     * essences are tier -1 (would land in WHITE), but neither feels right
     * for what is essentially "common forge currency". Treating them as
     * PURPLE keeps players from confusing forge mats with rare drops.
     *
     * All consumables (potions) go to BLUE. Previously they fell through to
     * BROWN, which made stat-potion drops indistinguishable from empty bags.
     */
    public LootTier determineTier() {
        // BROWN is an explicit public-drop request (player-dropped items, HP/MP
        // potion drops): keep it brown rather than re-deriving a higher colour
        // from contents, so everything a player drops lands in a public brown bag.
        if (this.tier.equals(LootTier.CHEST) || this.tier.equals(LootTier.GRAVE)
                || this.tier.equals(LootTier.BOOSTED) || this.tier.equals(LootTier.BROWN))
            return this.tier;

        boolean hasUntiered = false;
        boolean hasHighTier = false; // tier 8+
        boolean hasLowTier = false;  // tier 0-7, non-consumable, OR a forge material
        boolean hasPotion = false;
        boolean hasAnyItem = false;

        for (GameItem item : this.items) {
            if (item == null) continue;
            hasAnyItem = true;
            byte t = item.getTier();
            final String cat = item.getCategory();
            // "shard" is the partial-crystal forge material (8 stat shards
            // combine into a full crystal). Treat it the same as full
            // crystals + essences so it lands in a PURPLE bag instead of a
            // WHITE one — matches player expectation that all forge mats
            // drop in purple.
            final boolean isForgeMaterial = "crystal".equals(cat)
                    || "essence".equals(cat)
                    || "shard".equals(cat);
            if (item.isConsumable()) {
                hasPotion = true;
            } else if (isForgeMaterial) {
                // Crystals + essences + shards classify as PURPLE regardless
                // of authored tier.
                hasLowTier = true;
            } else if (t == (byte) -1) {
                hasUntiered = true;
            } else if (t >= 8) {
                hasHighTier = true;
            } else {
                hasLowTier = true;
            }
        }

        if (!hasAnyItem) return LootTier.BROWN;
        if (hasUntiered) return LootTier.WHITE;
        if (hasHighTier) return LootTier.CYAN;
        if (hasLowTier) return LootTier.PURPLE;
        if (hasPotion) return LootTier.BLUE;
        return LootTier.BROWN;
    }

    public void setItems(GameItem[] items) {
        this.items = items;
        this.contentsChanged = true;
    }

    /**
     * Re-pack items to fill gaps (nulls) left by removed items.
     * After this call, all non-null items are contiguous from slot 0.
     */
    public void repackItems() {
        GameItem[] packed = new GameItem[SIZE];
        int slot = 0;
        for (GameItem item : this.items) {
            if (item != null && slot < SIZE) {
                packed[slot++] = item;
            }
        }
        this.items = packed;
        this.contentsChanged = true;
    }

    public void setItem(int idx, GameItem replacement) {
        this.items[idx] = replacement;
        this.contentsChanged = true;
    }

    public int getFirstNullIdx() {
        int idx = -1;
        for (int i = 0; i < this.items.length; i++) {
            if (this.items[i] == null) {
                idx = i;
                return idx;
            }
        }
        return idx;
    }

    public void render(SpriteBatch batch) {
        if (this.sprite != null && this.sprite.getRegion() != null) {
            // Regular loot bags render at half-tile (16px) so they read as
            // pickups rather than environment props. Chests override this in
            // Chest.render to keep their full 32px footprint, since chests
            // are an interactive set-piece (vault) and need to be visually
            // distinct from drop bags.
            final int draw = this.getDrawSize();
            // Center the smaller sprite inside the tile so the visual
            // anchor matches the underlying tile pos.
            final float offset = (32 - draw) / 2f;
            final float bx = this.pos.getWorldVar().x + offset;
            final float by = this.pos.getWorldVar().y + offset;
            final TextureRegion region = this.sprite.getRegion();
            // Dark silhouette outline (matches the in-world sprite stroke): 8
            // offset tinted copies (4 cardinal + 4 diagonal) behind the bag, then
            // the bag on top. The diagonals fill the corner pixels a cardinal-only
            // stroke misses. Skipped when the global sprite-stroke toggle is off.
            if (Settings.get().isSpriteStroke()) {
                final float prevColor = batch.getPackedColor();
                batch.setColor(0f, 0f, 0f, OUTLINE_ALPHA);
                batch.draw(region, bx + OUTLINE_OFFSET, by,                 draw, draw);
                batch.draw(region, bx - OUTLINE_OFFSET, by,                 draw, draw);
                batch.draw(region, bx,                 by + OUTLINE_OFFSET, draw, draw);
                batch.draw(region, bx,                 by - OUTLINE_OFFSET, draw, draw);
                batch.draw(region, bx + OUTLINE_OFFSET, by + OUTLINE_OFFSET, draw, draw);
                batch.draw(region, bx + OUTLINE_OFFSET, by - OUTLINE_OFFSET, draw, draw);
                batch.draw(region, bx - OUTLINE_OFFSET, by + OUTLINE_OFFSET, draw, draw);
                batch.draw(region, bx - OUTLINE_OFFSET, by - OUTLINE_OFFSET, draw, draw);
                batch.setPackedColor(prevColor);
            }
            // Soulbound bags get a red tint so the player visually distinguishes
            // their own loot from other players' soulbound drops (which they
            // also see but cannot pick up). Public bags render at neutral tint.
            if (!this.isPublicLoot()) {
                batch.setColor(1.0f, 0.55f, 0.55f, 1.0f);
            }
            batch.draw(region, bx, by, draw, draw);
            if (!this.isPublicLoot()) {
                batch.setColor(1.0f, 1.0f, 1.0f, 1.0f);
            }
        }
    }

    /** Render footprint in world pixels. Tier rules:
     *    BROWN / PURPLE / CYAN / BLUE / WHITE / BOOSTED -> 16 px
     *        (all regular drop bags read as same-size pickups; rarity is
     *        conveyed by bag color, not size)
     *    GRAVE / CHEST                                  -> 32 px
     *        (set-piece world objects, visually distinct from drop bags)
     *  Chest also overrides this for clarity, but the tier check below
     *  handles it identically — keeping the override means a Chest
     *  built without a CHEST tier (defensive bug) still renders large. */
    protected int getDrawSize() {
        if (this.tier == null) return 16;
        switch (this.tier) {
            case GRAVE:
            case CHEST:
                return 32;
            default:
                return 16;
        }
    }

    public int getNonEmptySlotCount() {
        int count = 0;
        for (GameItem s : this.getItems()) {
            if (s != null) {
                count++;
            }
        }
        return count;
    }

    public boolean equals(LootContainer other) {
    	final boolean basic = (this.lootContainerId == other.getLootContainerId()) && this.pos.equals(other.getPos());
    	final boolean tierMatch = this.getTier().equals(other.getTier());
    	boolean loot = true;
        for (int i = 0; i < SIZE; i++) {
            final GameItem a = this.items[i];
            final GameItem b = other.getItems()[i];
            if (a == null && b == null) continue;
            if (a == null || b == null || !a.equals(b)) {
                loot = false;
                break;
            }
        }
        return basic && loot && tierMatch;
    }
}
