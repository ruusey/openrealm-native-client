package com.openrealm.game.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.openrealm.game.contants.TextEffect;
import com.openrealm.game.math.Vector2f;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EffectText {
    // Both the float-up speed AND the lifetime are driven by this single
    // constant — animationDistance starts at 45 and counts down by velY/tick,
    // so a smaller magnitude buys longer read time without changing the visual
    // range. -0.72f (was -1.00f) is another ~40% read time so damage + the
    // status it applied can both be read before they fade.
    private static final float velY = -0.72f;

    private Vector2f sourcePos;
    private TextEffect effect;
    private String damage;
    @Builder.Default
    private boolean remove = false;
    @Builder.Default
    private float animationDistance = 45.0f;
    // Vertical separation (world px, float-up direction) applied on top of the
    // float animation. Status labels get a lane above the damage numbers so a
    // projectile's damage and the status it applies never overlap; same-lane
    // bursts also stack via this offset. Set at spawn in ClientGameLogic.
    @Builder.Default
    private float laneOffset = 0.0f;

    public void update() {
        this.animationDistance += EffectText.velY;
        if (this.animationDistance <= 0.0f) {
            this.remove = true;
        }
    }

    public void render(SpriteBatch batch, BitmapFont font) {
        Color color;
        switch (this.effect) {
        case DAMAGE:
            color = Color.RED;
            break;
        case HEAL:
            color = Color.GREEN;
            break;
        case ARMOR_BREAK:
            // Bright, saturated blue for armor-piercing/armor-broken hits.
            // Color.BLUE (0,0,255) is too dark to read on dungeon floors.
            color = new Color(0.30f, 0.55f, 1f, 1f);
            break;
        case ENVIRONMENT:
            color = Color.BLUE;
            break;
        case PLAYER_INFO:
            color = Color.ORANGE;
            break;
        default:
            color = Color.WHITE;
            break;
        }

        Color oldColor = font.getColor();
        font.setColor(color);
        font.draw(batch, this.damage, this.sourcePos.x - Vector2f.worldX,
                this.sourcePos.y - Vector2f.worldY - (64 - this.animationDistance) - this.laneOffset);
        font.setColor(oldColor);
    }

    public boolean getRemove() {
        return this.remove;
    }
}
