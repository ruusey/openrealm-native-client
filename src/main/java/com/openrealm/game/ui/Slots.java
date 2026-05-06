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
        shapes.rect(pos.x, pos.y, 64, 64);
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
        batch.draw(itemRegion, pos.x, pos.y, 64, 64);
    }

    /**
     * Draw the "xN" overlay on stackable items with count > 1. Mirrors the
     * web client's {@code .item-stack} badge in main.js' updateInventoryUI
     * (~line 3311). Call AFTER renderItem so the text sits on top of the
     * sprite. The slot is 64x64 and the overlay anchors to the bottom-right
     * corner so it doesn't obscure the item face. Skipped silently when the
     * item is not stackable or only has a single unit.
     */
    public void renderStackCount(SpriteBatch batch, BitmapFont font, Vector2f pos) {
        if (this.getItem() == null) return;
        if (!this.getItem().isStackable()) return;
        final int count = this.getItem().getStackCount();
        if (count <= 1) return;
        font.setColor(Color.WHITE);
        font.draw(batch, "x" + count, pos.x + 36, pos.y + 60);
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
        shapes.rect(pos.x, pos.y, 64, 64);
        shapes.end();
        batch.begin();

        TextureRegion itemRegion = GameSpriteManager.ITEM_SPRITES.get(this.item.getItemId());
        if (itemRegion == null)
            return;
        if (this.button != null) {
            this.button.render(batch);
        }
        batch.draw(itemRegion, pos.x, pos.y, 64, 64);
    }
}
