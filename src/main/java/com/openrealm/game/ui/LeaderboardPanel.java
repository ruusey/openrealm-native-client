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
 * Top-N-by-fame leaderboard panel ported from the webclient (loadLeaderboard
 * in main.js): rank, class sprite, name, class, level, fame, and equipment icons.
 */
@Slf4j
public class LeaderboardPanel {

    /** All classes use the same 5-slot layout (weapon/armor/gauntlets/boots/ring). */
    private static final int EQUIP_SLOT_COUNT = 5;
    private static final long REFRESH_MS = 30_000L;
    private static final Color PANEL_FILL = new Color(0.10f, 0.09f, 0.12f, 0.95f);
    private static final Color PANEL_BORDER = new Color(0.78f, 0.66f, 0.43f, 1f);
    private static final Color UNDERLINE_COLOR = new Color(0.40f, 0.32f, 0.18f, 0.8f);
    private static final Color ROW_STRIPE = new Color(0.16f, 0.13f, 0.16f, 0.55f);
    private static final Color SLOT_FILL = new Color(0.06f, 0.06f, 0.08f, 0.9f);
    private static final Color SLOT_BORDER = new Color(0.30f, 0.26f, 0.20f, 1f);

    private List<LeaderboardRow> rows = Collections.emptyList();
    private long lastFetchAt = 0L;
    private boolean failed = false;

    /** Topmost visible row; render clamps it against visible row count. Row-snapped. */
    private int scrollIdx = 0;
    /** Panel bounds from the last render, so scroll only fires when the cursor is over it. */
    private int lastX = 0, lastY = 0, lastW = 0, lastH = 0;
    private int lastRowsAvail = 1;

    /**
     * Adjusts the scroll position by a mouse-wheel notch (positive = down).
     * Caller should gate this on cursor-over-panel using {@link #containsPoint}.
     */
    public void scrollBy(int notches) {
        this.scrollIdx = Math.max(0,
                Math.min(this.scrollIdx + notches, Math.max(0, this.rows.size() - this.lastRowsAvail)));
    }

    public boolean containsPoint(int mx, int my) {
        return mx >= this.lastX && mx <= this.lastX + this.lastW
                && my >= this.lastY && my <= this.lastY + this.lastH;
    }

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
            List<LeaderboardRow> parsed = new ArrayList<>();
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

                    int[] equip = new int[]{-1, -1, -1, -1, -1};
                    JsonNode equipNode = entry.get("equipment");
                    if (equipNode != null && equipNode.isArray()) {
                        for (JsonNode e : equipNode) {
                            int slot = e.has("slotIdx") ? e.get("slotIdx").asInt(-1) : -1;
                            int itemId = e.has("itemId") ? e.get("itemId").asInt(-1) : -1;
                            if (slot >= 0 && slot < EQUIP_SLOT_COUNT) equip[slot] = itemId;
                        }
                    }
                    parsed.add(new LeaderboardRow(rank++, name, className, classIdx, level, fame, equip));
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
        this.lastX = x; this.lastY = y; this.lastW = w; this.lastH = h;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        UiRender.panel(batch, shapes, x, y, w, h, PANEL_FILL, PANEL_BORDER);

        int headerH = 28;
        font.setColor(0.78f, 0.66f, 0.43f, 1f);
        font.draw(batch, "LEADERBOARD - TOP BY FAME", x + 10, y + 20);

        UiRender.fillRect(batch, shapes, x + 8, y + headerH, w - 16, 1, UNDERLINE_COLOR);

        if (this.rows.isEmpty()) {
            font.setColor(Color.LIGHT_GRAY);
            font.draw(batch, this.failed ? "Leaderboard unavailable" : "Loading...",
                    x + 12, y + headerH + 28);
            font.setColor(Color.WHITE);
            return;
        }

        int rowH = 104;
        int rowsAvail = Math.max(1, (h - headerH - 8) / rowH);
        this.lastRowsAvail = rowsAvail;
        if (this.scrollIdx > Math.max(0, this.rows.size() - rowsAvail)) {
            this.scrollIdx = Math.max(0, this.rows.size() - rowsAvail);
        }
        if (this.scrollIdx < 0) this.scrollIdx = 0;
        int startIdx = this.scrollIdx;
        int endIdx = Math.min(this.rows.size(), startIdx + rowsAvail);
        int firstRowTop = y + headerH + 6;

        final int nameBaselineOff = 28;
        final int eqYOff          = 42;
        final int eqIconSize      = 30;
        final int eqGap           = 6;
        final int fameBaselineOff = eqYOff + eqIconSize + 14;

        for (int visIdx = 0, i = startIdx; i < endIdx; i++, visIdx++) {
            LeaderboardRow r = this.rows.get(i);
            int rowTop = firstRowTop + visIdx * rowH;

            // Stripe keyed off VISUAL index so the pattern stays stable while scrolling.
            if ((visIdx & 1) == 1) {
                UiRender.fillRect(batch, shapes, x + 4, rowTop, w - 8, rowH - 4, ROW_STRIPE);
            }

            int nameBaseline = rowTop + nameBaselineOff;
            int fameBaseline = rowTop + fameBaselineOff;
            font.setColor(0.85f, 0.78f, 0.55f, 1f);
            font.draw(batch, "#" + r.rank, x + 8, nameBaseline);

            int iconX = x + 56;
            int iconSize = 40;
            int iconY = rowTop + (rowH - iconSize) / 2;
            TextureRegion classFrame = classIcon(r.classIdx);
            if (classFrame != null) {
                batch.draw(classFrame, iconX, iconY, iconSize, iconSize);
            }

            int textX = iconX + iconSize + 14;
            float rightEdge = x + w - 8;
            String header = r.accountName
                    + (r.className == null || r.className.isEmpty() ? "" : " - " + r.className)
                    + " Lv " + r.level;
            header = ellipsize(font, header, rightEdge - textX);
            font.setColor(Color.WHITE);
            font.draw(batch, header, textX, nameBaseline);

            int eqX = textX;
            int eqY = rowTop + eqYOff;
            for (int s = 0; s < EQUIP_SLOT_COUNT; s++) {
                int slotX = eqX + s * (eqIconSize + eqGap);
                UiRender.panel(batch, shapes, slotX, eqY, eqIconSize, eqIconSize, SLOT_FILL, SLOT_BORDER);
                int itemId = r.equipment[s];
                if (itemId >= 0 && GameSpriteManager.ITEM_SPRITES != null) {
                    TextureRegion sprite = GameSpriteManager.ITEM_SPRITES.get(itemId);
                    if (sprite != null) {
                        batch.draw(sprite, slotX + 3, eqY + 3, eqIconSize - 6, eqIconSize - 6);
                    }
                }
            }

            font.setColor(0.95f, 0.78f, 0.30f, 1f);
            UiRender.drawRightAligned(batch, font, "Fame " + formatLong(r.fame), rightEdge, fameBaseline);
        }

        int hidden = this.rows.size() - endIdx;
        int hiddenAbove = startIdx;
        if (hidden > 0 || hiddenAbove > 0) {
            font.setColor(0.55f, 0.50f, 0.40f, 1f);
            String hint;
            if (hiddenAbove > 0 && hidden > 0) {
                hint = "^ " + hiddenAbove + "  |  v " + hidden + "   (scroll)";
            } else if (hidden > 0) {
                hint = "v " + hidden + " more   (scroll)";
            } else {
                hint = "^ " + hiddenAbove + " above   (scroll)";
            }
            font.draw(batch, hint, x + 12, y + h - 8);
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
        StringBuilder sb = new StringBuilder(Long.toString(v));
        for (int i = sb.length() - 3; i > 0; i -= 3) sb.insert(i, ',');
        return sb.toString();
    }
}
