package com.openrealm.game.ui;

import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import com.openrealm.account.dto.CharacterMetricsDto;
import com.openrealm.account.service.OpenRealmClientDataService;
import com.openrealm.game.OpenRealmGame;
import com.openrealm.net.client.ClientGameLogic;

/**
 * In-game lifetime-stats overlay (toggled with "."). Mirrors SkillsWindow: a
 * grid of curated metrics, each cell showing a value; hovering a cell shows
 * what the metric is and how it is counted. Fetches CharacterMetricsDto from the
 * data service on a background thread (drained in update()).
 */
public class MetricsWindow {

    private static final String[] LABELS = {
        "Kills", "Boss Kills", "Deaths",
        "Damage Dealt", "Damage Taken", "Shots Fired",
        "Accuracy", "Ability Casts", "Ability Damage",
        "Enemy Debuff (s)", "Ally Buff (s)", "Loot Picked Up",
        "XP Earned", "Time Played", "PvP Wins"
    };
    private static final String[] DESCS = {
        "Enemies you have killed. +1 per kill.",
        "Boss enemies killed. +1 per boss kill.",
        "Times your character has died.",
        "Total damage dealt to enemies (weapons + abilities).",
        "Total damage you have taken.",
        "Projectiles fired. +1 per shot.",
        "Shots that hit divided by total shots fired.",
        "Abilities cast. +1 per cast.",
        "Damage dealt by your abilities.",
        "Total seconds of debuffs you applied to enemies.",
        "Total seconds of buffs you granted to allies.",
        "Items picked up from the ground. +1 per item.",
        "Total character XP earned over this character's life.",
        "Total in-game time for this character.",
        "PvP matches won."
    };

    private final GlyphLayout layout = new GlyphLayout();
    private final AtomicReference<CharacterMetricsDto> pendingData = new AtomicReference<>();
    private final AtomicReference<String> pendingError = new AtomicReference<>();
    private boolean visible = false;
    private String status = "";
    private String[] values = new String[LABELS.length];

    public boolean isVisible() {
        return this.visible;
    }

    public void hide() {
        this.visible = false;
    }

    /** Toggle: hide if open, otherwise open and kick off the async metrics fetch. */
    public void toggleFor(final String characterUuid) {
        if (this.visible) {
            this.visible = false;
            return;
        }
        this.visible = true;
        this.status = "Loading...";
        this.pendingData.set(null);
        this.pendingError.set(null);
        if (characterUuid == null) {
            this.status = "No character.";
            return;
        }
        final OpenRealmClientDataService svc = ClientGameLogic.DATA_SERVICE;
        new Thread(() -> {
            try {
                this.pendingData.set(svc.getCharacterMetrics(characterUuid));
            } catch (Exception e) {
                this.pendingError.set("Could not load stats.");
            }
        }, "openrealm-metrics-fetch").start();
    }

    public void update() {
        if (!this.visible) return;
        final CharacterMetricsDto data = this.pendingData.getAndSet(null);
        if (data != null) {
            this.values = buildValues(data);
            this.status = "";
        }
        final String err = this.pendingError.getAndSet(null);
        if (err != null) this.status = err;
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) this.hide();
    }

    public void render(final SpriteBatch batch, final ShapeRenderer shapes, final BitmapFont font) {
        if (!this.visible) return;

        final int w = OpenRealmGame.width;
        final int h = OpenRealmGame.height;
        final int cols = 3, rows = 5;
        final int cellW = 176, cellH = 60, pad = 10;
        final int headerH = 32;
        final int dialogW = cols * cellW + (cols + 1) * pad;
        final int dialogH = headerH + rows * cellH + (rows + 1) * pad;
        final int x = (w - dialogW) / 2;
        final int y = (h - dialogH) / 2;

        final int mouseX = Gdx.input.getX();
        final int mouseY = Gdx.input.getY();
        int hoverIdx = -1;

        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, 0.65f);
        shapes.rect(0, 0, w, h);
        shapes.setColor(0.10f, 0.07f, 0.09f, 0.97f);
        shapes.rect(x, y, dialogW, dialogH);
        shapes.setColor(0.06f, 0.06f, 0.08f, 1f);
        shapes.rect(x, y, dialogW, headerH);

        final boolean ready = this.status.isEmpty();
        if (ready) {
            for (int i = 0; i < LABELS.length; i++) {
                final int c = i % cols, r = i / cols;
                final int cx = x + pad + c * (cellW + pad);
                final int cy = y + headerH + pad + r * (cellH + pad);
                shapes.setColor(0.16f, 0.14f, 0.18f, 1f);
                shapes.rect(cx, cy, cellW, cellH);
                if (mouseX >= cx && mouseX <= cx + cellW && mouseY >= cy && mouseY <= cy + cellH) hoverIdx = i;
            }
        }
        shapes.end();
        batch.begin();

        font.setColor(0.78f, 0.66f, 0.43f, 1f);
        font.draw(batch, "CHARACTER STATS", x + 14, y + 22);
        font.setColor(0.53f, 0.47f, 0.41f, 1f);
        font.draw(batch, "Lifetime totals. Press . or ESC to close", x + dialogW - 300, y + 22);

        if (!ready) {
            font.setColor(0.80f, 0.72f, 0.55f, 1f);
            font.draw(batch, this.status, x + 14, y + headerH + 28);
            font.setColor(Color.WHITE);
            return;
        }

        for (int i = 0; i < LABELS.length; i++) {
            final int c = i % cols, r = i / cols;
            final int cx = x + pad + c * (cellW + pad);
            final int cy = y + headerH + pad + r * (cellH + pad);
            font.setColor(0.72f, 0.68f, 0.77f, 1f);
            font.draw(batch, LABELS[i], cx + 10, cy + 22);
            font.setColor(0.94f, 0.91f, 0.85f, 1f);
            font.draw(batch, this.values[i] == null ? "0" : this.values[i], cx + 10, cy + 46);
        }

        if (hoverIdx >= 0) {
            final String desc = DESCS[hoverIdx];
            this.layout.setText(font, desc);
            final float tipW = this.layout.width + 16;
            final float tipH = this.layout.height + 12;
            final int c = hoverIdx % cols, r = hoverIdx / cols;
            final float cx = x + pad + c * (cellW + pad);
            final float cy = y + headerH + pad + r * (cellH + pad);
            final float tipX = cx;
            final float tipY = cy - tipH - 4;
            batch.end();
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0.04f, 0.04f, 0.06f, 0.98f);
            shapes.rect(tipX, tipY, tipW, tipH);
            shapes.end();
            batch.begin();
            font.setColor(Color.WHITE);
            font.draw(batch, desc, tipX + 8, tipY + tipH - 8);
        }
    }

    private static String[] buildValues(final CharacterMetricsDto m) {
        final long shots = m.getProjectilesHit() + m.getProjectilesMissed();
        final String accuracy = shots > 0 ? Math.round(m.getProjectilesHit() * 100.0 / shots) + "%" : "-";
        return new String[] {
            String.format("%,d", m.getKillsTotal()),
            String.format("%,d", m.getBossKills()),
            String.format("%,d", m.getDeaths()),
            String.format("%,d", m.getDamageDealtTotal()),
            String.format("%,d", m.getDamageTakenTotal()),
            String.format("%,d", m.getProjectilesFired()),
            accuracy,
            String.format("%,d", m.getAbilityCastsTotal()),
            String.format("%,d", m.getAbilityDamageDealt()),
            String.format("%,d", m.getAbilityDebuffSecondsEnemy()),
            String.format("%,d", m.getAbilityBuffSecondsAlly()),
            String.format("%,d", m.getItemsPickedUp()),
            String.format("%,d", m.getXpEarned()),
            String.format("%,d min", Math.round(m.getPlayTimeSeconds() / 60.0)),
            String.format("%,d", m.getPvpWins())
        };
    }
}
