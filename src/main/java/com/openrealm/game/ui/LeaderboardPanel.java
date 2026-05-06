package com.openrealm.game.ui;

import java.util.Collections;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.fasterxml.jackson.databind.JsonNode;
import com.openrealm.account.service.OpenRealmClientDataService;
import com.openrealm.net.client.ClientGameLogic;

import lombok.extern.slf4j.Slf4j;
import com.badlogic.gdx.Gdx;
import java.util.ArrayList;

/**
 * Top-N leaderboard panel for the character-select / pause screen.
 *
 * Pulls the leaderboard from the data service via {@code GET /data/leaderboard}
 * (or whatever the server-side endpoint resolves to) and caches the result so
 * we don't re-fetch on every render frame. Displays rank, name, fame.
 *
 * Designed to be self-contained: pass it a position + size and let it render.
 * If the REST endpoint is unreachable (e.g., older data service), it shows a
 * single "Leaderboard unavailable" line rather than throwing.
 */
@Slf4j
public class LeaderboardPanel {

    public static class Row {
        public int rank;
        public String name;
        public long fame;
        public Row(int rank, String name, long fame) {
            this.rank = rank;
            this.name = name;
            this.fame = fame;
        }
    }

    private List<Row> rows = Collections.emptyList();
    private long lastFetchAt = 0L;
    private static final long REFRESH_MS = 30_000L; // 30s; leaderboard isn't real-time
    private boolean failed = false;

    /** Refresh from the data service if the cache is stale. Cheap to call every frame. */
    public void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (now - this.lastFetchAt < REFRESH_MS && !this.rows.isEmpty()) return;
        if (this.failed && now - this.lastFetchAt < REFRESH_MS) return;
        this.lastFetchAt = now;
        this.fetch();
    }

    private void fetch() {
        OpenRealmClientDataService svc = ClientGameLogic.DATA_SERVICE;
        if (svc == null) return;
        try {
            // Real endpoint exposed by the data service:
            //   GET /data/stats/top?count=N → List<LeaderboardEntryDto>
            // (LeaderboardEntryDto has accountName, characterClass, level,
            //  fame, equipment, stats — see openrealm-data).
            JsonNode body = svc.executeGet("data/stats/top?count=10", null, JsonNode.class);
            List<Row> parsed = new ArrayList<>();
            if (body != null && body.isArray()) {
                int rank = 1;
                for (JsonNode entry : body) {
                    String name = entry.has("accountName") ? entry.get("accountName").asText()
                            : entry.has("name") ? entry.get("name").asText()
                            : "?";
                    long fame = entry.has("fame") ? entry.get("fame").asLong() : 0L;
                    parsed.add(new Row(rank++, name, fame));
                }
            }
            this.rows = parsed;
            this.failed = false;
        } catch (Exception e) {
            // Older servers may not expose this endpoint — treat as soft fail
            // so the rest of the screen renders normally.
            log.warn("[LEADERBOARD] fetch failed: {}", e.getMessage());
            this.failed = true;
        }
    }

    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font, int x, int y, int w, int h) {
        this.refreshIfStale();

        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.10f, 0.10f, 0.12f, 0.90f);
        shapes.rect(x, y, w, h);
        shapes.end();
        batch.begin();

        font.setColor(Color.WHITE);
        font.draw(batch, "TOP 10 BY FAME", x + 8, y + h - 8);

        if (this.rows.isEmpty()) {
            font.setColor(Color.LIGHT_GRAY);
            font.draw(batch, this.failed ? "Leaderboard unavailable" : "Loading...", x + 8, y + h - 32);
            return;
        }

        int rowH = 18;
        for (int i = 0; i < this.rows.size() && i < 10; i++) {
            Row r = this.rows.get(i);
            font.setColor(Color.WHITE);
            font.draw(batch, String.format("%2d. %-16s  *%d", r.rank, r.name, r.fame),
                    x + 8, y + h - 32 - i * rowH);
        }
    }
}
