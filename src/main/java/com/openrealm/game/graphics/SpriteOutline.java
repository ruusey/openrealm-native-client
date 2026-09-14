package com.openrealm.game.graphics;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

/**
 * Shared dark-silhouette sprite outline: 8 black-tinted copies (4 cardinal +
 * 4 diagonal) drawn behind a sprite. Restores the batch color afterward so the
 * caller can draw the real sprite on top. No per-call allocations (hot path).
 */
public final class SpriteOutline {

    // 8 unit offsets. The first five are always drawn; the last three carry
    // +Y (the "bottom" copies Player skips while wading), so a leading count
    // can drop them without reordering.
    private static final float[][] OFFSETS = {
            { 1f, 0f }, { -1f, 0f }, { 0f, -1f }, { 1f, -1f }, { -1f, -1f },
            { 0f, 1f }, { -1f, 1f }, { 1f, 1f } };

    private SpriteOutline() {
    }

    /** Simple form: unrotated, unscaled draw (region, x, y, w, h). */
    public static void drawOutline(SpriteBatch batch, TextureRegion region,
            float x, float y, float w, float h, float offset, float alpha) {
        final float prevColor = batch.getPackedColor();
        batch.setColor(0f, 0f, 0f, alpha);
        for (float[] off : OFFSETS) {
            batch.draw(region, x + off[0] * offset, y + off[1] * offset, w, h);
        }
        batch.setPackedColor(prevColor);
    }

    /** Scaled/rotated form mirroring batch.draw with origin, scale and rotation. */
    public static void drawOutline(SpriteBatch batch, TextureRegion region,
            float x, float y, float originX, float originY, float w, float h,
            float scaleX, float scaleY, float rotationDeg, float offset, float alpha) {
        drawOutline(batch, region, x, y, originX, originY, w, h, scaleX, scaleY,
                rotationDeg, offset, alpha, true);
    }

    /**
     * Scaled/rotated form; when includeBottom is false the three +Y copies are
     * skipped (Player waterline cut).
     */
    public static void drawOutline(SpriteBatch batch, TextureRegion region,
            float x, float y, float originX, float originY, float w, float h,
            float scaleX, float scaleY, float rotationDeg, float offset, float alpha,
            boolean includeBottom) {
        final int count = includeBottom ? OFFSETS.length : 5;
        final float prevColor = batch.getPackedColor();
        batch.setColor(0f, 0f, 0f, alpha);
        for (int i = 0; i < count; i++) {
            batch.draw(region, x + OFFSETS[i][0] * offset, y + OFFSETS[i][1] * offset,
                    originX, originY, w, h, scaleX, scaleY, rotationDeg);
        }
        batch.setPackedColor(prevColor);
    }
}
