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

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Fame Store UI — buy cosmetic dyes (and future patterned cloths) with the
 * fame currency banked across dead characters.
 *
 * Server flow:
 *   1. Player walks onto a fame-store tile → server sends OpenFameStorePacket
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
    @Setter private long accountFame = 0L;
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

    public void update() {
        if (!this.visible) return;
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            this.hide();
            return;
        }
        boolean down = Gdx.input.isButtonPressed(Input.Buttons.LEFT);
        if (down && !this.mouseDownPrev) {
            this.handleClick(Gdx.input.getX(), Gdx.graphics.getHeight() - Gdx.input.getY());
        }
        this.mouseDownPrev = down;
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

        // Item rows
        int rowH = 36;
        int rowsTop = y + dialogH - 60;
        for (int i = 0; i < this.entries.size() && i < 10; i++) {
            int rowY = rowsTop - (i + 1) * rowH;
            shapes.setColor(0.16f, 0.16f, 0.20f, 1f);
            shapes.rect(x + 12, rowY, dialogW - 24, rowH - 4);
            // Buy button right-side
            shapes.setColor(0.20f, 0.45f, 0.20f, 1f);
            shapes.rect(x + dialogW - 90, rowY + 4, 70, rowH - 12);
        }

        shapes.end();
        batch.begin();

        font.setColor(Color.WHITE);
        font.draw(batch, "FAME STORE",                 x + dialogW / 2 - 36, y + dialogH - 4);
        font.draw(batch, "* " + this.accountFame + " Fame", x + 12,           y + dialogH - 28);

        for (int i = 0; i < this.entries.size() && i < 10; i++) {
            int rowY = rowsTop - (i + 1) * rowH;
            Entry e = this.entries.get(i);
            font.setColor(Color.WHITE);
            font.draw(batch, e.name + "  (#" + e.itemId + ")", x + 22, rowY + rowH - 14);
            font.setColor(this.accountFame >= e.cost ? Color.WHITE : Color.LIGHT_GRAY);
            font.draw(batch, e.cost + " *", x + dialogW / 2 + 60, rowY + rowH - 14);
            font.setColor(Color.WHITE);
            font.draw(batch, "Buy", x + dialogW - 78, rowY + rowH - 14);
        }

        if (!this.statusMsg.isEmpty()) {
            font.setColor(this.statusIsError ? Color.SCARLET : Color.LIME);
            font.draw(batch, this.statusMsg, x + 12, y + 24);
        }
        font.setColor(Color.LIGHT_GRAY);
        font.draw(batch, "Esc to close", x + dialogW - 100, y + 12);
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
        int rowH = 36;
        int rowsTop = y + dialogH - 60;
        for (int i = 0; i < this.entries.size() && i < 10; i++) {
            int rowY = rowsTop - (i + 1) * rowH;
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
                java.lang.reflect.Method m = packet.getClass().getMethod("setItemId", int.class);
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
