package com.openrealm.game.ui;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector2;
import com.openrealm.game.OpenRealmGame;

/** Shared chrome + text layout for the hover tooltips (ability, passive). */
public final class TooltipRenderer {

    public static final int PADDING = 8;
    public static final int LINE_HEIGHT = 18;
    public static final Color BG_COLOR     = new Color(0.12f, 0.12f, 0.15f, 0.95f);
    public static final Color BORDER_COLOR = new Color(0.4f,  0.4f,  0.5f,  1f);

    private TooltipRenderer() {
    }

    /** Clamped bottom-left origin keeping a w x h box fully on-screen. */
    public static Vector2 clampBox(float x, float y, float w, float h, boolean anchorAbove) {
        float bx = x;
        float by = anchorAbove ? y - h - 6 : y;
        if (bx + w > OpenRealmGame.width - 4) bx = OpenRealmGame.width - 4 - w;
        if (bx < 4) bx = 4;
        if (by + h > OpenRealmGame.height - 4) by = OpenRealmGame.height - 4 - h;
        if (by < 4) by = 4;
        return new Vector2(bx, by);
    }

    /**
     * Translucent backdrop + border. Enables GL_BLEND for the pass so the ~0.95-alpha
     * background actually blends; the caller draws its text lines afterward.
     */
    public static void drawFrame(SpriteBatch batch, ShapeRenderer shapes,
                                 float x, float y, float w, float h) {
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(BG_COLOR);
        shapes.rect(x, y, w, h);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(BORDER_COLOR);
        shapes.rect(x, y, w, h);
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
        batch.begin();
    }

    public static List<String> wrapLines(BitmapFont font, String text, float maxWidth) {
        final List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty() || maxWidth <= 0) return out;
        final String[] words = text.split("\\s+");
        final GlyphLayout layout = new GlyphLayout();
        StringBuilder current = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            final String trial = current.length() == 0 ? w : current + " " + w;
            layout.setText(font, trial);
            if (layout.width <= maxWidth) {
                current.setLength(0);
                current.append(trial);
            } else {
                if (current.length() > 0) out.add(current.toString());
                current.setLength(0);
                current.append(w);
            }
        }
        if (current.length() > 0) out.add(current.toString());
        return out;
    }
}
