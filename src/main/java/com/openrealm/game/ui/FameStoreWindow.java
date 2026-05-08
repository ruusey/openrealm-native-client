package com.openrealm.game.ui;

import java.util.Collections;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.openrealm.game.OpenRealmGame;
import com.openrealm.net.realm.RealmManagerClient;
import com.openrealm.net.server.packet.BuyFameItemPacket;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import java.lang.reflect.Method;

/**
 * Fame Store UI — buy cosmetic dyes (and future patterned cloths) with the
 * fame currency banked across dead characters.
 *
 * Server flow:
 *   1. Player walks onto a fame-store tile -> server sends OpenFameStorePacket
 *      with a list of available items + costs.
 *   2. We open this window, show the catalog, and on Buy click send a
 *      {@link BuyFameItemPacket} for that item id.
 *   3. Server validates fame balance + delivers the dye to the account.
 */
@Slf4j
public class FameStoreWindow {

    /** A single purchasable entry rendered in the grid. */
    public static class Entry {
        public int itemId;
        public String name;
        public long cost;
        public Entry(int itemId, String name, long cost) {
            this.itemId = itemId;
            this.name = name;
            this.cost = cost;
        }
    }

    private boolean visible = false;
    private boolean mouseDownPrev = false;

    @Setter private RealmManagerClient realmManager;
    @Getter @Setter private long accountFame = 0L;
    @Setter private List<Entry> entries = Collections.emptyList();

    private String statusMsg = "";
    private boolean statusIsError = false;

    public boolean isVisible() {
        return this.visible;
    }

    public void show() {
        this.visible = true;
        this.statusMsg = "";
    }

    public void hide() {
        this.visible = false;
    }

    /** Vertical scroll offset for the entry list, in rows. Increment
     *  on mouse-wheel down inside the dialog so >10 entries (the whole
     *  fame catalog: 8 dyes + crystals + essences) become reachable.
     *  The previous client capped rendering at 10 entries silently —
     *  that's why "the crystals" never appeared even when the server
     *  sent them. */
    private int scrollOffset = 0;

    public void update() {
        if (!this.visible) return;
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            this.hide();
            return;
        }
        boolean down = Gdx.input.isButtonPressed(Input.Buttons.LEFT);
        if (down && !this.mouseDownPrev) {
            // Top-down click coords to match the flipped-ortho render
            // — the previous (height - getY()) inversion is why every
            // Buy button hit-test missed.
            this.handleClick(Gdx.input.getX(), Gdx.input.getY());
        }
        this.mouseDownPrev = down;
    }

    /** Apply a wheel scroll while the catalog is open. Wired from
     *  PlayerUI so a single global wheel-handler stays authoritative. */
    public void onWheel(float wheel) {
        if (!this.visible || this.entries == null || this.entries.isEmpty()) return;
        final int visibleRows = visibleRowCount();
        final int max = Math.max(0, this.entries.size() - visibleRows);
        this.scrollOffset = Math.max(0, Math.min(max,
                this.scrollOffset + (wheel > 0 ? 1 : -1)));
    }

    private int visibleRowCount() {
        final int h = OpenRealmGame.height;
        final int dialogH = Math.min(480, h - 80);
        // 32px header + ~16px padding + 36px rows + footer ~32px
        return Math.max(1, (dialogH - 80) / 36);
    }

    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        if (!this.visible) return;

        int w = OpenRealmGame.width;
        int h = OpenRealmGame.height;
        int dialogW = 480;
        int dialogH = Math.min(480, h - 80);
        int x = (w - dialogW) / 2;
        int y = (h - dialogH) / 2;

        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        shapes.setColor(0f, 0f, 0f, 0.65f);
        shapes.rect(0, 0, w, h);
        shapes.setColor(0.10f, 0.10f, 0.12f, 0.97f);
        shapes.rect(x, y, dialogW, dialogH);

        // Header strip at the TOP (flipped ortho).
        int headerH = 32;
        shapes.setColor(0.06f, 0.06f, 0.08f, 1f);
        shapes.rect(x, y, dialogW, headerH);

        // Item rows below the header. Each row's Y grows DOWNWARD.
        int rowH = 36;
        int rowsTop = y + headerH + 24;
        int visibleRows = visibleRowCount();
        int total = this.entries.size();
        int firstIdx = Math.max(0, Math.min(this.scrollOffset, Math.max(0, total - visibleRows)));
        int lastIdx  = Math.min(total, firstIdx + visibleRows);

        for (int i = firstIdx; i < lastIdx; i++) {
            int rowY = rowsTop + (i - firstIdx) * rowH;
            shapes.setColor(0.16f, 0.16f, 0.20f, 1f);
            shapes.rect(x + 12, rowY, dialogW - 24, rowH - 4);
            // Buy button on the right side of each row.
            shapes.setColor(0.20f, 0.45f, 0.20f, 1f);
            shapes.rect(x + dialogW - 90, rowY + 4, 70, rowH - 12);
        }

        // Cancel button in the header bar (right side) so the player has
        // a clickable close target even on machines without ESC handy.
        int closeBtnW = 60, closeBtnH = headerH - 8;
        int closeBtnX = x + dialogW - closeBtnW - 6;
        int closeBtnY = y + 4;
        shapes.setColor(0.40f, 0.20f, 0.20f, 1f);
        shapes.rect(closeBtnX, closeBtnY, closeBtnW, closeBtnH);

        shapes.end();
        batch.begin();

        font.setColor(Color.WHITE);
        font.draw(batch, "FAME STORE", x + 16, y + 22);
        font.draw(batch, "* " + this.accountFame + " Fame", x + 160, y + 22);
        font.draw(batch, "Cancel", closeBtnX + 8, closeBtnY + closeBtnH - 6);

        for (int i = firstIdx; i < lastIdx; i++) {
            int rowY = rowsTop + (i - firstIdx) * rowH;
            Entry e = this.entries.get(i);
            font.setColor(Color.WHITE);
            font.draw(batch, e.name + "  (#" + e.itemId + ")", x + 22, rowY + rowH - 14);
            font.setColor(this.accountFame >= e.cost ? Color.WHITE : Color.LIGHT_GRAY);
            font.draw(batch, e.cost + " *", x + dialogW / 2 + 60, rowY + rowH - 14);
            font.setColor(Color.WHITE);
            font.draw(batch, "Buy", x + dialogW - 78, rowY + rowH - 14);
        }

        // Scroll indicator if the catalog is taller than the visible area.
        if (total > visibleRows) {
            font.setColor(Color.LIGHT_GRAY);
            font.draw(batch, (firstIdx + 1) + "-" + lastIdx + " / " + total
                            + "  (scroll)",
                    x + 12, y + dialogH - 8);
        }

        if (!this.statusMsg.isEmpty()) {
            font.setColor(this.statusIsError ? Color.SCARLET : Color.LIME);
            font.draw(batch, this.statusMsg, x + 12, y + dialogH - 28);
        }
    }

    public void setStatus(String msg, boolean isError) {
        this.statusMsg = msg == null ? "" : msg;
        this.statusIsError = isError;
    }

    private void handleClick(int mx, int my) {
        int w = OpenRealmGame.width;
        int h = OpenRealmGame.height;
        int dialogW = 480;
        int dialogH = Math.min(480, h - 80);
        int x = (w - dialogW) / 2;
        int y = (h - dialogH) / 2;
        int headerH = 32;

        // Cancel button in the header.
        int closeBtnW = 60, closeBtnH = headerH - 8;
        int closeBtnX = x + dialogW - closeBtnW - 6;
        int closeBtnY = y + 4;
        if (mx >= closeBtnX && mx <= closeBtnX + closeBtnW
                && my >= closeBtnY && my <= closeBtnY + closeBtnH) {
            this.hide();
            return;
        }

        int rowH = 36;
        int rowsTop = y + headerH + 24;
        int visibleRows = visibleRowCount();
        int total = this.entries.size();
        int firstIdx = Math.max(0, Math.min(this.scrollOffset, Math.max(0, total - visibleRows)));
        int lastIdx  = Math.min(total, firstIdx + visibleRows);
        for (int i = firstIdx; i < lastIdx; i++) {
            int rowY = rowsTop + (i - firstIdx) * rowH;
            int btnX = x + dialogW - 90;
            int btnY = rowY + 4;
            int btnW = 70;
            int btnH = rowH - 12;
            if (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH) {
                this.attemptBuy(this.entries.get(i));
                return;
            }
        }
    }

    private void attemptBuy(Entry entry) {
        if (entry == null || this.realmManager == null) return;
        if (this.accountFame < entry.cost) {
            this.setStatus("Not enough fame", true);
            return;
        }
        try {
            BuyFameItemPacket packet = new BuyFameItemPacket();
            try {
                Method m = packet.getClass().getMethod("setItemId", int.class);
                m.invoke(packet, entry.itemId);
            } catch (NoSuchMethodException nsme) {
                log.debug("[FAME] BuyFameItemPacket field shape changed: {}", nsme.getMessage());
            }
            this.realmManager.getClient().getOutboundPacketQueue().add(packet);
            this.setStatus("Purchase requested...", false);
        } catch (Exception e) {
            log.error("[FAME] Failed to send buy packet: {}", e.getMessage());
            this.setStatus("Network error", true);
        }
    }
}
