package com.openrealm.game.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.fasterxml.jackson.databind.JsonNode;
import com.openrealm.account.service.OpenRealmClientDataService;
import com.openrealm.game.OpenRealmGame;
import com.openrealm.net.client.ClientGameLogic;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Account-level vault / chest UI shown on the character-select screen.
 *
 * The web client renders this on the char-select page; the native client's
 * existing PauseState is the closest equivalent (it loads the account when
 * opened). This window is meant to be opened by PauseState rather than being
 * available in-game.
 *
 * Currently shows the per-account chest count and an "Add Chest" button that
 * issues {@code POST /data/account/{accountUuid}/chest/new}. Drag-drop with
 * the inventory is a later enhancement — the server still owns chest contents
 * authoritatively.
 */
@Slf4j
public class VaultWindow {
    private boolean visible = false;
    private boolean mouseDownPrev = false;

    @Setter private String accountUuid;
    private int chestCount = 0;
    private String statusMsg = "";

    public boolean isVisible() {
        return this.visible;
    }

    public void show() {
        this.visible = true;
        this.refresh();
    }

    public void hide() {
        this.visible = false;
    }

    private void refresh() {
        if (this.accountUuid == null) return;
        try {
            JsonNode account = ClientGameLogic.DATA_SERVICE.executeGet(
                    "data/account/" + this.accountUuid, null, JsonNode.class);
            if (account != null && account.has("chests") && account.get("chests").isArray()) {
                this.chestCount = account.get("chests").size();
            }
        } catch (Exception e) {
            log.warn("[VAULT] refresh failed: {}", e.getMessage());
        }
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
        int dialogW = 400;
        int dialogH = 280;
        int x = (w - dialogW) / 2;
        int y = (h - dialogH) / 2;

        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, 0.65f);
        shapes.rect(0, 0, w, h);
        shapes.setColor(0.10f, 0.10f, 0.14f, 0.95f);
        shapes.rect(x, y, dialogW, dialogH);

        // Add Chest button
        int btnW = 140;
        int btnH = 32;
        int btnX = x + (dialogW - btnW) / 2;
        int btnY = y + 24;
        shapes.setColor(0.20f, 0.45f, 0.20f, 1f);
        shapes.rect(btnX, btnY, btnW, btnH);
        shapes.end();
        batch.begin();

        font.setColor(Color.WHITE);
        font.draw(batch, "VAULT",                    x + dialogW / 2 - 24, y + dialogH - 8);
        font.draw(batch, "Chests on account: " + this.chestCount, x + 16, y + dialogH - 48);
        font.draw(batch, "Each chest holds 8 items.",              x + 16, y + dialogH - 72);
        font.draw(batch, "Add Chest", btnX + 30, btnY + btnH - 10);

        if (!this.statusMsg.isEmpty()) {
            font.setColor(Color.LIME);
            font.draw(batch, this.statusMsg, x + 16, y + 80);
        }
        font.setColor(Color.LIGHT_GRAY);
        font.draw(batch, "Esc to close", x + dialogW - 100, y + 12);
    }

    private void handleClick(int mx, int my) {
        int w = OpenRealmGame.width;
        int h = OpenRealmGame.height;
        int dialogW = 400;
        int dialogH = 280;
        int x = (w - dialogW) / 2;
        int y = (h - dialogH) / 2;
        int btnW = 140;
        int btnH = 32;
        int btnX = x + (dialogW - btnW) / 2;
        int btnY = y + 24;
        if (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH) {
            this.addChest();
        }
    }

    private void addChest() {
        if (this.accountUuid == null) return;
        try {
            ClientGameLogic.DATA_SERVICE.executePost(
                    "data/account/" + this.accountUuid + "/chest/new", null, JsonNode.class);
            this.statusMsg = "Chest added";
            this.refresh();
        } catch (Exception e) {
            log.warn("[VAULT] add-chest failed: {}", e.getMessage());
            this.statusMsg = "Failed to add chest";
        }
    }
}
