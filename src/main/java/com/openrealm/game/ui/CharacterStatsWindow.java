package com.openrealm.game.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.openrealm.account.dto.CharacterDto;
import com.openrealm.account.dto.CharacterMetricsDto;
import com.openrealm.account.service.OpenRealmClientDataService;
import com.openrealm.game.OpenRealmGame;
import com.openrealm.net.client.ClientGameLogic;

import lombok.extern.slf4j.Slf4j;

/**
 * Read-only lifetime-stats report for a character, opened by right-clicking a
 * row on the character-select screen. Fetches {@link CharacterMetricsDto} from
 * the data service on a background thread and renders a scrollable two-column
 * table (label / value) grouped into sections.
 *
 * Self-contained like {@link FameStoreWindow}: the HTTP call runs off the GL
 * thread and the result is drained in {@link #update()} via an AtomicReference.
 */
@Slf4j
public class CharacterStatsWindow {

    /** A rendered line: a section header (value == null) or a stat row. */
    private static final class Row {
        final String label;
        final String value;
        Row(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    private static final int ROW_H = 20;
    private static final int HEADER_H = 32;

    private boolean visible = false;
    private boolean mouseDownPrev = false;
    private int scrollOffset = 0;

    private String title = "Lifetime Stats";
    private String status = "";
    private final List<Row> rows = new ArrayList<>();

    private final AtomicReference<CharacterMetricsDto> pendingData = new AtomicReference<>();
    private final AtomicReference<String> pendingError = new AtomicReference<>();

    public boolean isVisible() {
        return this.visible;
    }

    public void hide() {
        this.visible = false;
    }

    /** Open the window for a character and kick off the async metrics fetch. */
    public void show(CharacterDto character, String className) {
        if (character == null || character.getCharacterUuid() == null) return;
        this.visible = true;
        this.scrollOffset = 0;
        this.rows.clear();
        this.status = "Loading…";
        this.title = (className == null ? "Character" : className) + " — Lifetime Stats";
        this.pendingData.set(null);
        this.pendingError.set(null);

        final OpenRealmClientDataService svc = ClientGameLogic.DATA_SERVICE;
        final String uuid = character.getCharacterUuid();
        new Thread(() -> {
            try {
                this.pendingData.set(svc.getCharacterMetrics(uuid));
            } catch (Exception e) {
                log.error("[STATS] failed to load metrics for {}: {}", uuid, e.getMessage());
                this.pendingError.set("Could not load stats: " + e.getMessage());
            }
        }, "openrealm-stats-fetch").start();
    }

    public void update() {
        if (!this.visible) return;

        final CharacterMetricsDto data = this.pendingData.getAndSet(null);
        if (data != null) {
            this.buildRows(data);
            this.status = "";
        }
        final String err = this.pendingError.getAndSet(null);
        if (err != null) {
            this.status = err;
            this.rows.clear();
        }

        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            this.hide();
            return;
        }
        final boolean down = Gdx.input.isButtonPressed(Input.Buttons.LEFT);
        if (down && !this.mouseDownPrev) {
            this.handleClick(Gdx.input.getX(), Gdx.input.getY());
        }
        this.mouseDownPrev = down;
    }

    /** Mouse-wheel scroll while the report is open. */
    public void onWheel(float wheel) {
        if (!this.visible) return;
        final int max = Math.max(0, this.rows.size() - visibleRowCount());
        this.scrollOffset = Math.max(0, Math.min(max, this.scrollOffset + (wheel > 0 ? 1 : -1)));
    }

    private int dialogH() {
        return Math.min(560, OpenRealmGame.height - 60);
    }

    private int visibleRowCount() {
        return Math.max(1, (dialogH() - HEADER_H - 12) / ROW_H);
    }

    private void handleClick(int mx, int my) {
        final int w = OpenRealmGame.width;
        final int h = OpenRealmGame.height;
        final int dialogW = 460;
        final int dialogH = dialogH();
        final int x = (w - dialogW) / 2;
        final int y = (h - dialogH) / 2;

        // Close button in the header (right side); clicking outside the dialog also closes.
        final int closeBtnW = 60, closeBtnH = HEADER_H - 8;
        final int closeBtnX = x + dialogW - closeBtnW - 6;
        final int closeBtnY = y + 4;
        if (mx >= closeBtnX && mx <= closeBtnX + closeBtnW
                && my >= closeBtnY && my <= closeBtnY + closeBtnH) {
            this.hide();
            return;
        }
        if (mx < x || mx > x + dialogW || my < y || my > y + dialogH) {
            this.hide();
        }
    }

    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        if (!this.visible) return;

        final int w = OpenRealmGame.width;
        final int h = OpenRealmGame.height;
        final int dialogW = 460;
        final int dialogH = dialogH();
        final int x = (w - dialogW) / 2;
        final int y = (h - dialogH) / 2;

        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        shapes.setColor(0f, 0f, 0f, 0.65f);
        shapes.rect(0, 0, w, h);
        shapes.setColor(0.10f, 0.09f, 0.11f, 0.98f);
        shapes.rect(x, y, dialogW, dialogH);
        // Header strip at the top (flipped ortho).
        shapes.setColor(0.06f, 0.06f, 0.08f, 1f);
        shapes.rect(x, y, dialogW, HEADER_H);
        // Close button.
        final int closeBtnW = 60, closeBtnH = HEADER_H - 8;
        final int closeBtnX = x + dialogW - closeBtnW - 6;
        final int closeBtnY = y + 4;
        shapes.setColor(0.40f, 0.20f, 0.20f, 1f);
        shapes.rect(closeBtnX, closeBtnY, closeBtnW, closeBtnH);
        shapes.end();

        batch.begin();
        font.setColor(0.78f, 0.66f, 0.43f, 1f);
        font.draw(batch, this.title, x + 14, y + 22);
        font.setColor(Color.WHITE);
        font.draw(batch, "Close", closeBtnX + 8, closeBtnY + closeBtnH - 6);

        if (!this.status.isEmpty()) {
            font.setColor(0.80f, 0.72f, 0.55f, 1f);
            font.draw(batch, this.status, x + 14, y + HEADER_H + 24);
            font.setColor(Color.WHITE);
            return;
        }

        final int rowsTop = y + HEADER_H + 6;
        final int visibleRows = visibleRowCount();
        final int total = this.rows.size();
        final int firstIdx = Math.max(0, Math.min(this.scrollOffset, Math.max(0, total - visibleRows)));
        final int lastIdx = Math.min(total, firstIdx + visibleRows);
        final int valueX = x + dialogW - 150;

        for (int i = firstIdx; i < lastIdx; i++) {
            final Row row = this.rows.get(i);
            final int rowY = rowsTop + (i - firstIdx) * ROW_H + ROW_H - 6;
            if (row.value == null) {
                // Section header.
                font.setColor(0.95f, 0.82f, 0.42f, 1f);
                font.draw(batch, row.label, x + 12, rowY);
            } else {
                font.setColor(0.72f, 0.66f, 0.56f, 1f);
                font.draw(batch, row.label, x + 20, rowY);
                font.setColor(0.90f, 0.86f, 0.75f, 1f);
                font.draw(batch, row.value, valueX, rowY);
            }
        }

        if (total > visibleRows) {
            font.setColor(0.55f, 0.50f, 0.45f, 1f);
            font.draw(batch, "scroll for more", x + 12, y + dialogH - 6);
        }
        font.setColor(Color.WHITE);
    }

    private void section(String name) {
        this.rows.add(new Row(name, null));
    }

    private void stat(String label, long value) {
        this.rows.add(new Row(label, String.format("%,d", value)));
    }

    private void stat(String label, String value) {
        this.rows.add(new Row(label, value));
    }

    private void buildRows(CharacterMetricsDto m) {
        this.rows.clear();

        final long shotsTaken = m.getProjectilesHit() + m.getProjectilesMissed();
        final String accuracy = shotsTaken > 0
                ? Math.round(m.getProjectilesHit() * 100.0 / shotsTaken) + "%" : "—";
        final String kd = m.getDeaths() > 0
                ? String.format("%.2f", m.getKillsTotal() / (double) m.getDeaths())
                : String.format("%,d", m.getKillsTotal());
        final long playMinutes = Math.round(m.getPlayTimeSeconds() / 60.0);
        final long pvpTotal = m.getPvpMatches();
        final String pvpWinRate = pvpTotal > 0
                ? Math.round(m.getPvpWins() * 100.0 / pvpTotal) + "%" : "—";

        this.section("Combat");
        this.stat("Shots fired", m.getProjectilesFired());
        this.stat("Shots hit", m.getProjectilesHit());
        this.stat("Shots missed", m.getProjectilesMissed());
        this.stat("Accuracy", accuracy);
        this.stat("Damage dealt", m.getDamageDealtTotal());
        this.stat("Damage taken", m.getDamageTakenTotal());
        this.stat("Kills", m.getKillsTotal());
        this.stat("Boss kills", m.getBossKills());
        this.stat("Deaths", m.getDeaths());
        this.stat("K/D ratio", kd);

        this.section("Abilities");
        this.stat("Ability casts", m.getAbilityCastsTotal());
        this.stat("Ability damage", m.getAbilityDamageDealt());
        this.stat("Enemies affected", m.getAbilityEnemiesAffected());
        this.stat("Allies affected", m.getAbilityAlliesAffected());
        this.stat("Ally buff seconds", m.getAbilityBuffSecondsAlly());
        this.stat("Enemy debuff seconds", m.getAbilityDebuffSecondsEnemy());
        this.stat("Friendly debuff seconds", m.getAbilityDebuffSecondsFriendly());

        this.section("Items & Progression");
        this.stat("Loot picked up", m.getItemsPickedUp());
        this.stat("Items enchanted", m.getItemsEnchanted());
        this.stat("HP potions drank", m.getHpPotionsDrank());
        this.stat("MP potions drank", m.getMpPotionsDrank());
        this.stat("XP earned", m.getXpEarned());
        this.stat("Skill points spent", m.getSkillPointsSpent());

        this.section("Social & PvP");
        this.stat("Time played", String.format("%,d min", playMinutes));
        this.stat("Trades", m.getTradesCompleted());
        this.stat("Chat messages", m.getChatMessagesSent());
        this.stat("PvP matches", m.getPvpMatches());
        this.stat("PvP wins", m.getPvpWins());
        this.stat("PvP losses", m.getPvpLosses());
        this.stat("PvP win rate", pvpWinRate);

        this.section("Dungeon completions");
        final Map<String, Long> dungeons = m.getDungeonCompletionsByDungeonId();
        if (dungeons == null || dungeons.isEmpty()) {
            this.stat("None yet", "—");
        } else {
            for (Map.Entry<String, Long> e : dungeons.entrySet()) {
                this.stat("Dungeon " + e.getKey(), e.getValue() == null ? 0L : e.getValue());
            }
        }
    }
}
