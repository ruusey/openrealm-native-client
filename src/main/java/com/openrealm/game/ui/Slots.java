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
    /** Must match {@link PlayerUI}'s SLOT_SIZE so item sprites land inside its rectangles. */
    public static final int SLOT_PX = 56;
    public static final int ICON_PADDING = 6;
    public static final int ICON_PX = SLOT_PX - 2 * ICON_PADDING;
    private static final float ITEM_OUTLINE_OFFSET = 1f;
    private static final float ITEM_OUTLINE_ALPHA = 0.85f;

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

    /** Slot background. Call while ShapeRenderer is active. */
    public void renderBackground(ShapeRenderer shapes, Vector2f pos) {
        if (this.getItem() == null) return;
        if (this.isSelected()) {
            shapes.setColor(Color.YELLOW);
        } else {
            shapes.setColor(Color.GRAY);
        }
        shapes.rect(pos.x, pos.y, SLOT_PX, SLOT_PX);
    }

    /** Slot item sprite. Call while SpriteBatch is active. */
    public void renderItem(SpriteBatch batch, Vector2f pos) {
        if (this.getItem() == null) return;
        if (this.getItem().getSpriteKey() == null) {
            GameDataManager.loadSpriteModel(this.getItem());
        }
        // Composited enchantment region first; fall back to the un-painted base sprite.
        TextureRegion itemRegion = SpriteRecolorCache.getEnchantedItemRegion(this.item);
        if (itemRegion == null) {
            itemRegion = GameSpriteManager.ITEM_SPRITES.get(this.item.getItemId());
        }
        if (itemRegion == null) return;
        if (this.button != null) {
            this.button.render(batch);
        }
        final float ix = pos.x + ICON_PADDING, iy = pos.y + ICON_PADDING;
        // Dark silhouette outline: four offset tinted copies behind the icon.
        final float prev = batch.getPackedColor();
        batch.setColor(0f, 0f, 0f, ITEM_OUTLINE_ALPHA);
        batch.draw(itemRegion, ix + ITEM_OUTLINE_OFFSET, iy, ICON_PX, ICON_PX);
        batch.draw(itemRegion, ix - ITEM_OUTLINE_OFFSET, iy, ICON_PX, ICON_PX);
        batch.draw(itemRegion, ix, iy + ITEM_OUTLINE_OFFSET, ICON_PX, ICON_PX);
        batch.draw(itemRegion, ix, iy - ITEM_OUTLINE_OFFSET, ICON_PX, ICON_PX);
        batch.setPackedColor(prev);
        batch.draw(itemRegion, ix, iy, ICON_PX, ICON_PX);
    }

    /** Draw the "xN" overlay (black outline + gold text) on stackable items with
     *  count > 1. Anchored bottom-right of the slot; call AFTER renderItem. */
    public void renderStackCount(SpriteBatch batch, BitmapFont font, Vector2f pos) {
        if (this.getItem() == null) return;
        if (!this.getItem().isStackable()) return;
        final int count = this.getItem().getStackCount();
        if (count <= 1) return;
        final String text = "x" + count;
        final float x = pos.x + SLOT_PX - 18;
        final float y = pos.y + SLOT_PX - 4;
        font.setColor(Color.BLACK);
        font.draw(batch, text, x - 1, y - 1);
        font.draw(batch, text, x + 1, y - 1);
        font.draw(batch, text, x - 1, y + 1);
        font.draw(batch, text, x + 1, y + 1);
        font.setColor(1f, 0.847f, 0.42f, 1f);
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

        UiRender.fillRect(batch, shapes, pos.x, pos.y, SLOT_PX, SLOT_PX,
                this.isSelected() ? Color.YELLOW : Color.GRAY);

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
