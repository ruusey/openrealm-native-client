package com.openrealm.game.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.fasterxml.jackson.databind.JsonNode;
import com.openrealm.account.service.OpenRealmClientDataService;
import com.openrealm.game.contants.CharacterClass;
import com.openrealm.game.data.GameSpriteManager;
import com.openrealm.game.graphics.SpriteSheet;
import com.openrealm.net.client.ClientGameLogic;

import lombok.extern.slf4j.Slf4j;

/**
 * Top-N leaderboard panel ported from the openrealm-data webclient
 * (loadLeaderboard in main.js).
 *
 * For each entry we render: rank, class sprite, account name, class name,
 * level, fame, and the four equipment slot icons inline. This matches the
 * web client's leaderboard rather than the previous text-only stub which
 * was unreadable in the corner of the screen.
 */
@Slf4j
public class LeaderboardPanel {

    public static class Row {
        public int rank;
        public String accountName;
        public String className;
        public int classIdx;
        public int level;
        public long fame;
        /** Item id per slot index (0=weapon, 1=ability, 2=armor, 3=ring); -1 = empty. */
        public int[] equipment = new int[]{-1, -1, -1, -1};

        public Row(int rank, String accountName, String className, int classIdx,
                   int level, long fame, int[] equipment) {
            this.rank = rank;
            this.accountName = accountName;
            this.className = className;
            this.classIdx = classIdx;
            this.level = level;
            this.fame = fame;
            if (equipment != null && equipment.length == 4) this.equipment = equipment;
        }
    }

    private List<Row> rows = Collections.emptyList();
    private long lastFetchAt = 0L;
    private static final long REFRESH_MS = 30_000L;
    private boolean failed = false;

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
            JsonNode body = svc.executeGet("data/stats/top?count=10", null, JsonNode.class);
            List<Row> parsed = new ArrayList<>();
            if (body != null && body.isArray()) {
                int rank = 1;
                for (JsonNode entry : body) {
                    String name = entry.has("accountName") ? entry.get("accountName").asText("?") : "?";
                    String className = entry.has("className") ? entry.get("className").asText("") : "";
                    int classIdx = entry.has("characterClass") ? entry.get("characterClass").asInt(0) : 0;
                    long fame = entry.has("fame") ? entry.get("fame").asLong(0L) : 0L;
                    boolean isFameMode = fame > 0;
                    int level = isFameMode ? 20
                            : (entry.has("level") ? entry.get("level").asInt(0) : 0);

                    int[] equip = new int[]{-1, -1, -1, -1};
                    JsonNode equipNode = entry.get("equipment");
                    if (equipNode != null && equipNode.isArray()) {
                        for (JsonNode e : equipNode) {
                            int slot = e.has("slotIdx") ? e.get("slotIdx").asInt(-1) : -1;
                            int itemId = e.has("itemId") ? e.get("itemId").asInt(-1) : -1;
                            if (slot >= 0 && slot < 4) equip[slot] = itemId;
                        }
                    }
                    parsed.add(new Row(rank++, name, className, classIdx, level, fame, equip));
                }
            }
            this.rows = parsed;
            this.failed = false;
        } catch (Exception e) {
            log.warn("[LEADERBOARD] fetch failed: {}", e.getMessage());
            this.failed = true;
        }
    }

    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font, int x, int y, int w, int h) {
        this.refreshIfStale();

        // Panel background + border
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.10f, 0.09f, 0.12f, 0.95f);
        shapes.rect(x, y, w, h);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0.78f, 0.66f, 0.43f, 1f);
        shapes.rect(x, y, w, h);
        shapes.end();
        batch.begin();

        // Coordinate convention: this project uses a Y-down orthographic camera
        // and a flipped BitmapFont, so larger Y = further down the screen.
        // (x, y) is the top-left of the panel; (x+w, y+h) is the bottom-right.
        int headerH = 28;
        font.setColor(0.78f, 0.66f, 0.43f, 1f);
        font.draw(batch, "LEADERBOARD - TOP BY FAME", x + 10, y + 20);

        // Underline beneath header
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.40f, 0.32f, 0.18f, 0.8f);
        shapes.rect(x + 8, y + headerH, w - 16, 1);
        shapes.end();
        batch.begin();

        if (this.rows.isEmpty()) {
            font.setColor(Color.LIGHT_GRAY);
            font.draw(batch, this.failed ? "Leaderboard unavailable" : "Loading...",
                    x + 12, y + headerH + 28);
            font.setColor(Color.WHITE);
            return;
        }

        // Each row: 56 px tall, drawn top-to-bottom (rank #1 first).
        //   [rank][32x32 class icon] AccountName - ClassName Lv N        Fame N
        //                            [weapon][ability][armor][ring]
        int rowH = 56;
        int rowsAvail = Math.max(1, (h - headerH - 8) / rowH);
        int n = Math.min(this.rows.size(), rowsAvail);
        int firstRowTop = y + headerH + 6;

        for (int i = 0; i < n; i++) {
            Row r = this.rows.get(i);
            int rowTop = firstRowTop + i * rowH;

            // Subtle alternating background
            if ((i & 1) == 1) {
                batch.end();
                shapes.begin(ShapeRenderer.ShapeType.Filled);
                shapes.setColor(0.16f, 0.13f, 0.16f, 0.55f);
                shapes.rect(x + 4, rowTop, w - 8, rowH - 4);
                shapes.end();
                batch.begin();
            }

            // Rank label (line 1 baseline)
            int line1Baseline = rowTop + 18;
            font.setColor(0.85f, 0.78f, 0.55f, 1f);
            font.draw(batch, "#" + r.rank, x + 8, line1Baseline);

            // Class icon
            int iconX = x + 50;
            int iconY = rowTop + 4;
            int iconSize = 32;
            TextureRegion classFrame = classIcon(r.classIdx);
            if (classFrame != null) {
                batch.draw(classFrame, iconX, iconY, iconSize, iconSize);
            }

            // Name + class + level (line 1)
            int textX = iconX + iconSize + 8;
            float fameRightEdge = x + w - 8;
            String fameStr = "Fame " + formatLong(r.fame);
            GlyphLayout fl = new GlyphLayout(font, fameStr);
            float maxHeaderW = fameRightEdge - textX - fl.width - 12;
            String header = r.accountName
                    + (r.className == null || r.className.isEmpty() ? "" : " - " + r.className)
                    + " Lv " + r.level;
            header = ellipsize(font, header, maxHeaderW);
            font.setColor(Color.WHITE);
            font.draw(batch, header, textX, line1Baseline);

            // Fame, right-aligned
            font.setColor(0.95f, 0.78f, 0.30f, 1f);
            font.draw(batch, fameStr, fameRightEdge - fl.width, line1Baseline);

            // Equipment row (line 2): 4 small item sprites
            int eqIconSize = 20;
            int eqGap = 4;
            int eqX = textX;
            int eqY = rowTop + 28;
            for (int s = 0; s < 4; s++) {
                int slotX = eqX + s * (eqIconSize + eqGap);
                batch.end();
                shapes.begin(ShapeRenderer.ShapeType.Filled);
                shapes.setColor(0.06f, 0.06f, 0.08f, 0.9f);
                shapes.rect(slotX, eqY, eqIconSize, eqIconSize);
                shapes.end();
                shapes.begin(ShapeRenderer.ShapeType.Line);
                shapes.setColor(0.30f, 0.26f, 0.20f, 1f);
                shapes.rect(slotX, eqY, eqIconSize, eqIconSize);
                shapes.end();
                batch.begin();

                int itemId = r.equipment[s];
                if (itemId >= 0 && GameSpriteManager.ITEM_SPRITES != null) {
                    TextureRegion sprite = GameSpriteManager.ITEM_SPRITES.get(itemId);
                    if (sprite != null) {
                        batch.draw(sprite, slotX, eqY, eqIconSize, eqIconSize);
                    }
                }
            }
        }

        // Truncated indicator if more rows than fit
        if (this.rows.size() > n) {
            font.setColor(0.55f, 0.50f, 0.40f, 1f);
            font.draw(batch, "+ " + (this.rows.size() - n) + " more...",
                    x + 12, y + h - 8);
        }

        font.setColor(Color.WHITE);
    }

    private static TextureRegion classIcon(int classIdx) {
        try {
            CharacterClass cc = CharacterClass.valueOf(classIdx);
            if (cc == null) return null;
            SpriteSheet ss = GameSpriteManager.loadClassSprites(cc);
            if (ss == null) return null;
            return ss.getCurrentFrame();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String ellipsize(BitmapFont font, String text, float maxWidth) {
        if (maxWidth <= 0) return text;
        GlyphLayout layout = new GlyphLayout(font, text);
        if (layout.width <= maxWidth) return text;
        String s = text;
        while (s.length() > 1) {
            s = s.substring(0, s.length() - 1);
            layout = new GlyphLayout(font, s + "...");
            if (layout.width <= maxWidth) return s + "...";
        }
        return text;
    }

    private static String formatLong(long v) {
        if (v < 1000) return Long.toString(v);
        // Insert thousands separators.
        StringBuilder sb = new StringBuilder(Long.toString(v));
        for (int i = sb.length() - 3; i > 0; i -= 3) sb.insert(i, ',');
        return sb.toString();
    }
}
