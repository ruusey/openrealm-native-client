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
    // Drives both float-up speed and lifetime: animationDistance counts down by velY/tick.
    private static final float velY = -0.72f;

    private Vector2f sourcePos;
    private TextEffect effect;
    private String damage;
    @Builder.Default
    private boolean remove = false;
    @Builder.Default
    private float animationDistance = 45.0f;
    // World-px lane offset so status labels don't overlap damage numbers. Set at spawn.
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
