package com.openrealm.game.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.data.GameSpriteManager;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.graphics.SpriteRecolorCache;
import com.openrealm.game.math.Vector2f;
import com.openrealm.util.KeyHandler;
import com.openrealm.util.MouseHandler;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Slots {
    /** Slot background dimension — must match {@link com.openrealm.game.ui.PlayerUI}'s
     *  SLOT_SIZE so item sprites land inside the rectangles drawn by PlayerUI. */
    public static final int SLOT_PX = 56;
    /** Inner padding so the item icon visually breathes inside the slot frame
     *  (mirrors the webclient's #item-slot CSS, which has ~6px padding). */
    public static final int ICON_PADDING = 6;
    /** Effective icon dimension drawn inside each slot. */
    public static final int ICON_PX = SLOT_PX - 2 * ICON_PADDING;

    private GameItem item;
    private Button button;
    private boolean selected;
    private Vector2f dragPos;

    public Slots(Button button, GameItem item) {
        this.item = item;
        this.button = button;
    }

    public void update(double time) {
        if (this.button != null) {
            this.button.update(time);
        }
    }

    public void input(MouseHandler mouse, KeyHandler key) {
        if (this.button != null) {
            this.button.input(mouse, key);
        }

        if (this.button.isClicked()) {
            this.dragPos = new Vector2f(mouse.getX(), mouse.getY());
        } else {
            this.dragPos = null;
        }
    }

    /**
     * Render slot background (shapes pass). Call while ShapeRenderer is active.
     */
    public void renderBackground(ShapeRenderer shapes, Vector2f pos) {
        if (this.getItem() == null) return;
        if (this.isSelected()) {
            shapes.setColor(Color.YELLOW);
        } else {
            shapes.setColor(Color.GRAY);
        }
        shapes.rect(pos.x, pos.y, SLOT_PX, SLOT_PX);
    }

    /**
     * Render slot item sprite (batch pass). Call while SpriteBatch is active.
     */
    public void renderItem(SpriteBatch batch, Vector2f pos) {
        if (this.getItem() == null) return;
        if (this.getItem().getSpriteKey() == null) {
            GameDataManager.loadSpriteModel(this.getItem());
        }
        // Forge enchantments paint colored "crystal" pixels onto the
        // weapon icon (web parity: getItemSpriteUrl in main.js ~2762).
        // Try the composited region first; fall back to the un-painted
        // base sprite if the item has no enchantments or we couldn't
        // build the overlay.
        TextureRegion itemRegion = SpriteRecolorCache.getEnchantedItemRegion(this.item);
        if (itemRegion == null) {
            itemRegion = GameSpriteManager.ITEM_SPRITES.get(this.item.getItemId());
        }
        if (itemRegion == null) return;
        if (this.button != null) {
            this.button.render(batch);
        }
        // Inset the icon by ICON_PADDING on every side so it visually sits
        // inside the slot rectangle drawn by PlayerUI (was 64x64 -> overflowed
        // a 56x56 slot by 14% on each side; matches webclient #item-slot).
        final float ix = pos.x + ICON_PADDING, iy = pos.y + ICON_PADDING;
        // Dark silhouette outline: four 1px-offset tinted copies behind the
        // icon, then the real icon on top.
        final float prev = batch.getPackedColor();
        batch.setColor(0f, 0f, 0f, ITEM_OUTLINE_ALPHA);
        batch.draw(itemRegion, ix + ITEM_OUTLINE_OFFSET, iy, ICON_PX, ICON_PX);
        batch.draw(itemRegion, ix - ITEM_OUTLINE_OFFSET, iy, ICON_PX, ICON_PX);
        batch.draw(itemRegion, ix, iy + ITEM_OUTLINE_OFFSET, ICON_PX, ICON_PX);
        batch.draw(itemRegion, ix, iy - ITEM_OUTLINE_OFFSET, ICON_PX, ICON_PX);
        batch.setPackedColor(prev);
        batch.draw(itemRegion, ix, iy, ICON_PX, ICON_PX);
    }

    private static final float ITEM_OUTLINE_OFFSET = 1f;
    private static final float ITEM_OUTLINE_ALPHA = 0.85f;

    /**
     * Draw the "xN" overlay on stackable items with count > 1. Mirrors the
     * web client's {@code .item-stack} badge in main.js' updateInventoryUI
     * (~line 3311). Call AFTER renderItem so the text sits on top of the
     * sprite. Anchored to the bottom-right of the SLOT_PX rectangle.
     * Skipped silently when the item is not stackable or only has a single
     * unit.
     */
    public void renderStackCount(SpriteBatch batch, BitmapFont font, Vector2f pos) {
        if (this.getItem() == null) return;
        if (!this.getItem().isStackable()) return;
        final int count = this.getItem().getStackCount();
        if (count <= 1) return;
        // Match the webclient's .item-stack badge — gold "×N" with a black
        // outline so the count is readable against any sprite. The legacy
        // "x" was plain white with no outline and disappeared into light
        // sprites (essences, potions). Fake an outline by drawing the
        // string four times offset 1px in each diagonal, then the gold
        // text on top.
        final String text = "×" + count;
        final float x = pos.x + SLOT_PX - 18;
        final float y = pos.y + SLOT_PX - 4;
        font.setColor(Color.BLACK);
        font.draw(batch, text, x - 1, y - 1);
        font.draw(batch, text, x + 1, y - 1);
        font.draw(batch, text, x - 1, y + 1);
        font.draw(batch, text, x + 1, y + 1);
        font.setColor(1f, 0.847f, 0.42f, 1f); // #ffd86b matches the webclient
        font.draw(batch, text, x, y);
        font.setColor(Color.WHITE);
    }

    /** @deprecated Use renderBackground() + renderItem() for batched rendering */
    public void render(SpriteBatch batch, ShapeRenderer shapes, Vector2f pos) {
        if (this.getItem() == null)
            return;
        if (this.getItem().getSpriteKey() == null) {
            GameDataManager.loadSpriteModel(this.getItem());
        }

        // Draw slot background via ShapeRenderer
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        if (this.isSelected()) {
            shapes.setColor(Color.YELLOW);
        } else {
            shapes.setColor(Color.GRAY);
        }
        shapes.rect(pos.x, pos.y, SLOT_PX, SLOT_PX);
        shapes.end();
        batch.begin();

        TextureRegion itemRegion = GameSpriteManager.ITEM_SPRITES.get(this.item.getItemId());
        if (itemRegion == null)
            return;
        if (this.button != null) {
            this.button.render(batch);
        }
        batch.draw(itemRegion,
                pos.x + ICON_PADDING, pos.y + ICON_PADDING,
                ICON_PX, ICON_PX);
    }
}
