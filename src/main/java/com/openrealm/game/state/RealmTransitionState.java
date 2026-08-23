package com.openrealm.game.state;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.openrealm.game.OpenRealmGame;
import com.openrealm.game.Settings;

/**
 * Brief full-screen overlay shown when the player crosses into a new realm
 * (overworld zone, dungeon, etc.). Mirrors the web client's transition
 * screen — title + zone name + difficulty skulls + fade in/out.
 *
 * This is NOT a {@link GameState} — the native client's GameStateManager
 * uses fixed slots, not a stack, so a transient screen would interfere with
 * the steady-state PlayState slot. Instead, this is a self-contained overlay
 * that PlayerUI renders on top of the HUD when {@link #isActive()} is true.
 *
 * Disabled at runtime if {@code Settings.showRealmTransition} is false.
 */
public class RealmTransitionState {

    // Web parity (main.js): min 2s visible, dismiss once map data lands, hard
    // cap at 6s if it never does; 400ms fade-out.
    private static final long FADE_IN_MS = 200L;
    private static final long MIN_HOLD_MS = 2000L;
    private static final long MAX_MS = 6000L;
    private static final long FADE_OUT_MS = 400L;

    private long startTime = 0L;
    private long fadeOutStart = 0L; // 0 = not yet fading out
    private String zoneName = "";
    private float difficulty = 0f;
    private boolean active = false;
    private boolean dataReady = false;

    /**
     * Show the loading overlay at the START of a load-in / transition, before the
     * new realm's map data has arrived. Held until {@link #onDataReady} + the
     * minimum hold, or the hard timeout. No-op if already showing.
     */
    public void begin(String zoneName) {
        if (!Settings.get().isShowRealmTransition()) return;
        if (this.active) return; // don't restart an in-progress transition
        this.zoneName = zoneName == null ? "Loading..." : zoneName;
        this.difficulty = 0f;
        this.dataReady = false;
        this.fadeOutStart = 0L;
        this.startTime = System.currentTimeMillis();
        this.active = true;
    }

    /**
     * The new realm's map data has arrived — fill in the zone name/difficulty and
     * let the overlay dismiss once the minimum hold elapses. Starts the overlay
     * itself if {@link #begin} was never called for this transition.
     */
    public void onDataReady(String zoneName, float difficulty) {
        if (!Settings.get().isShowRealmTransition()) return;
        if (!this.active) {
            this.startTime = System.currentTimeMillis();
            this.fadeOutStart = 0L;
            this.active = true;
        }
        if (zoneName != null) this.zoneName = zoneName;
        this.difficulty = difficulty;
        this.dataReady = true;
    }

    /** Back-compat alias used by ClientGameLogic on LoadMap. */
    public void trigger(String zoneName, float difficulty) {
        onDataReady(zoneName, difficulty);
    }

    public boolean isActive() {
        return this.active;
    }

    public void update() {
        if (!this.active) return;
        final long now = System.currentTimeMillis();
        if (this.fadeOutStart == 0L) {
            final long elapsed = now - this.startTime;
            if ((this.dataReady && elapsed >= MIN_HOLD_MS) || elapsed >= MAX_MS) {
                this.fadeOutStart = now;
            }
        } else if (now - this.fadeOutStart >= FADE_OUT_MS) {
            this.active = false;
        }
    }

    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        if (!this.active) return;

        final long now = System.currentTimeMillis();
        float alpha;
        if (this.fadeOutStart != 0L) {
            alpha = Math.max(0f, 1f - (now - this.fadeOutStart) / (float) FADE_OUT_MS);
        } else {
            final long elapsed = now - this.startTime;
            alpha = elapsed < FADE_IN_MS ? (elapsed / (float) FADE_IN_MS) : 1f;
        }

        int w = OpenRealmGame.width;
        int h = OpenRealmGame.height;

        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, 0.85f * alpha);
        shapes.rect(0, 0, w, h);
        shapes.end();
        batch.begin();

        final GlyphLayout layout = new GlyphLayout();
        font.setColor(1f, 1f, 1f, alpha);
        layout.setText(font, "OPENREALM");
        font.draw(batch, layout, w / 2f - layout.width / 2f, h / 2f + 30);
        layout.setText(font, this.zoneName);
        font.draw(batch, layout, w / 2f - layout.width / 2f, h / 2f);
        // Difficulty line only once the map data has landed (avoids "Difficulty 0.0"
        // during the pre-data loading phase).
        if (this.dataReady && this.difficulty > 0f) {
            font.setColor(0.95f, 0.25f, 0.25f, alpha);
            StringBuilder skulls = new StringBuilder();
            int skullCount = Math.min(10, Math.max(0, Math.round(this.difficulty)));
            for (int i = 0; i < skullCount; i++) skulls.append('X');
            String difficultyLine = "Difficulty " + String.format("%.1f  %s", this.difficulty, skulls.toString());
            layout.setText(font, difficultyLine);
            font.draw(batch, layout, w / 2f - layout.width / 2f, h / 2f - 30);
        }
        font.setColor(1f, 1f, 1f, 1f);
    }
}
