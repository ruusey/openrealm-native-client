package com.openrealm.game.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Shared UI rendering helpers. The fonts are flipped for the Y-down ortho UI
 * camera, so {@code font.draw(x, y)} treats {@code y} as the top of the text
 * and extends downward — every centering formula here relies on that.
 */
public final class UiRender {

    private static final GlyphLayout LAYOUT = new GlyphLayout();

    private UiRender() {
    }

    public static float textWidth(BitmapFont font, CharSequence text) {
        LAYOUT.setText(font, text);
        return LAYOUT.width;
    }

    public static float textHeight(BitmapFont font, CharSequence text) {
        LAYOUT.setText(font, text);
        return LAYOUT.height;
    }

    /** Draws {@code text} horizontally centered on {@code centerX}, top at {@code topY}. */
    public static void drawCentered(SpriteBatch batch, BitmapFont font, CharSequence text, float centerX, float topY) {
        LAYOUT.setText(font, text);
        font.draw(batch, LAYOUT, centerX - LAYOUT.width / 2f, topY);
    }

    /** Draws {@code text} centered both horizontally and vertically inside the box. */
    public static void drawCenteredIn(SpriteBatch batch, BitmapFont font, CharSequence text,
            float x, float y, float width, float height) {
        LAYOUT.setText(font, text);
        font.draw(batch, LAYOUT, x + (width - LAYOUT.width) / 2f, y + (height - LAYOUT.height) / 2f);
    }

    /** Draws {@code text} with its right edge at {@code rightX}, top at {@code topY}. */
    public static void drawRightAligned(SpriteBatch batch, BitmapFont font, CharSequence text, float rightX, float topY) {
        LAYOUT.setText(font, text);
        font.draw(batch, LAYOUT, rightX - LAYOUT.width, topY);
    }

    /**
     * Fills a rectangle in the given color. Ends the batch, runs the shape pass,
     * and restarts the batch so callers can keep drawing text afterwards.
     */
    public static void fillRect(SpriteBatch batch, ShapeRenderer shapes,
            float x, float y, float width, float height, Color color) {
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(color);
        shapes.rect(x, y, width, height);
        shapes.end();
        batch.begin();
    }

    /** Draws a filled rectangle with a line border, restarting the batch afterwards. */
    public static void panel(SpriteBatch batch, ShapeRenderer shapes,
            float x, float y, float width, float height, Color fill, Color border) {
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(fill);
        shapes.rect(x, y, width, height);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(border);
        shapes.rect(x, y, width, height);
        shapes.end();
        batch.begin();
    }
}
