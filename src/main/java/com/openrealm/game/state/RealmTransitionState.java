package com.openrealm.game.state;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
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

    private static final long FADE_IN_MS = 200L;
    private static final long HOLD_MS = 400L;
    private static final long FADE_OUT_MS = 200L;
    private static final long TOTAL_MS = FADE_IN_MS + HOLD_MS + FADE_OUT_MS;

    private long startTime = 0L;
    private String zoneName = "";
    private float difficulty = 0f;
    private boolean active = false;

    /** Triggered by ClientGameLogic when a new realm map is loaded. */
    public void trigger(String zoneName, float difficulty) {
        if (!Settings.get().isShowRealmTransition()) return;
        this.zoneName = zoneName == null ? "Realm" : zoneName;
        this.difficulty = difficulty;
        this.startTime = System.currentTimeMillis();
        this.active = true;
    }

    public boolean isActive() {
        return this.active;
    }

    public void update() {
        if (!this.active) return;
        if (System.currentTimeMillis() - this.startTime >= TOTAL_MS) {
            this.active = false;
        }
    }

    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        if (!this.active) return;

        long elapsed = System.currentTimeMillis() - this.startTime;
        float alpha;
        if (elapsed < FADE_IN_MS) {
            alpha = elapsed / (float) FADE_IN_MS;
        } else if (elapsed < FADE_IN_MS + HOLD_MS) {
            alpha = 1f;
        } else {
            alpha = Math.max(0f, 1f - (elapsed - FADE_IN_MS - HOLD_MS) / (float) FADE_OUT_MS);
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

        font.setColor(1f, 1f, 1f, alpha);
        font.draw(batch, "OPENREALM", w / 2f - 36, h / 2f + 30);
        font.draw(batch, this.zoneName, w / 2f - 30, h / 2f);
        font.setColor(0.95f, 0.25f, 0.25f, alpha);
        StringBuilder skulls = new StringBuilder();
        int skullCount = Math.min(10, Math.max(0, Math.round(this.difficulty)));
        for (int i = 0; i < skullCount; i++) skulls.append('X');
        font.draw(batch, "Difficulty " + String.format("%.1f  %s", this.difficulty, skulls.toString()),
                w / 2f - 60, h / 2f - 30);
        font.setColor(1f, 1f, 1f, 1f);
    }
}
