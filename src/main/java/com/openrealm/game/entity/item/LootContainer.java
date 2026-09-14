package com.openrealm.game.entity.item;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import java.time.Instant;
import java.util.UUID;

import com.openrealm.game.Settings;
import com.openrealm.game.contants.LootTier;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.graphics.Sprite;
import com.openrealm.game.graphics.SpriteOutline;
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

    // -1 = public; otherwise only this player id can see/interact with the bag.
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
        // Pack items contiguously from slot 0 (filter nulls to the front).
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
     * Loot tier from contents. WHITE: any untiered (tier -1). CYAN: any tier 8+.
     * PURPLE: tiered 0-7 or any forge material. BLUE: only potions. BROWN: empty.
     * CHEST/GRAVE/BOOSTED/BROWN are set explicitly and never reclassified.
     * Forge materials (crystal/essence/shard) force PURPLE regardless of their
     * authored tier so they don't read as rare (crystal) or common (essence) drops.
     */
    public LootTier determineTier() {
        if (this.tier.equals(LootTier.CHEST) || this.tier.equals(LootTier.GRAVE)
                || this.tier.equals(LootTier.BOOSTED) || this.tier.equals(LootTier.BROWN))
            return this.tier;

        boolean hasUntiered = false;
        boolean hasHighTier = false; // tier 8+
        boolean hasLowTier = false;  // tier 0-7 non-consumable, or a forge material
        boolean hasPotion = false;
        boolean hasAnyItem = false;

        for (GameItem item : this.items) {
            if (item == null) continue;
            hasAnyItem = true;
            byte t = item.getTier();
            final String cat = item.getCategory();
            final boolean isForgeMaterial = "crystal".equals(cat)
                    || "essence".equals(cat)
                    || "shard".equals(cat);
            if (item.isConsumable()) {
                hasPotion = true;
            } else if (isForgeMaterial) {
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

    /** Re-pack so non-null items are contiguous from slot 0. */
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
            final int draw = this.getDrawSize();
            // Center the smaller sprite inside the tile.
            final float offset = (32 - draw) / 2f;
            final float bx = this.pos.getWorldVar().x + offset;
            final float by = this.pos.getWorldVar().y + offset;
            final TextureRegion region = this.sprite.getRegion();
            // Dark silhouette outline: 8 offset copies behind the bag.
            if (Settings.get().isSpriteStroke()) {
                SpriteOutline.drawOutline(batch, region, bx, by, draw, draw, OUTLINE_OFFSET, OUTLINE_ALPHA);
            }
            // Soulbound bags get a red tint to distinguish them from public bags.
            if (!this.isPublicLoot()) {
                batch.setColor(1.0f, 0.55f, 0.55f, 1.0f);
            }
            batch.draw(region, bx, by, draw, draw);
            if (!this.isPublicLoot()) {
                batch.setColor(1.0f, 1.0f, 1.0f, 1.0f);
            }
        }
    }

    /** Render footprint (world px): drop bags 16px (rarity is color, not size);
     *  GRAVE/CHEST 32px (set-piece world objects). */
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
