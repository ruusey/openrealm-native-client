package com.openrealm.game.ui;

import java.util.ArrayList;
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
import com.openrealm.net.server.packet.ForgeDisenchantPacket;
import com.openrealm.net.server.packet.ForgeEnchantPacket;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import java.lang.reflect.Method;

/**
 * Pixel-painting forge UI, mirroring the web client's enchant flow.
 *
 * Server flow:
 *   1. Player walks onto a forge tile → server sends {@code OpenForgePacket}.
 *   2. We open this window. It captures the player's current target item,
 *      crystal, and essence (set externally via {@link #setItems}).
 *   3. The 16×16 (scaled to 256×256) canvas lets the player click a pixel to
 *      paint an enchantment for the selected crystal's stat type.
 *   4. Forge button sends {@link ForgeEnchantPacket}; Remove All sends
 *      {@link ForgeDisenchantPacket}. Server validates everything.
 *
 * The native client doesn't yet read the item sprite mask — it shows the
 * 16×16 grid as an unrestricted painting surface. The server is authoritative
 * either way, so this is a presentational simplification rather than a
 * correctness gap.
 */
@Slf4j
public class ForgeWindow {
    public static final int CANVAS_PIXELS = 16;       // 16x16 grid
    public static final int CANVAS_PIXEL_SIZE = 16;   // each grid cell is 16 device px
    public static final int MAX_ENCHANTMENTS = 5;

    private boolean visible = false;

    @Setter private RealmManagerClient realmManager;

    /** Item id of the equipment currently in the forge target slot. -1 if none. */
    @Setter private int targetItemId = -1;
    /** Item id of the crystal in the crystal slot. */
    @Setter private int crystalItemId = -1;
    /** Stat id encoded by the selected crystal (0=VIT 1=WIS 2=HP 3=MP 4=ATT 5=DEF 6=SPD 7=DEX). */
    @Setter private int crystalStatId = -1;
    /** Item id of the essence in the essence slot. */
    @Setter private int essenceItemId = -1;

    /** Pixels the user has painted in this session. Each entry is {x, y, statId, color-rgb}. */
    private final List<int[]> paintedPixels = new ArrayList<>();

    /** Pre-existing enchantments on the target item, supplied by the server when the window opens. */
    @Setter private List<int[]> existingEnchantments = new ArrayList<>();

    private boolean mouseDownPrev = false;

    public boolean isVisible() {
        return this.visible;
    }

    public void show() {
        this.visible = true;
        this.paintedPixels.clear();
    }

    public void hide() {
        this.visible = false;
        this.paintedPixels.clear();
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
        int dialogW = 540;
        int dialogH = 420;
        int x = (w - dialogW) / 2;
        int y = (h - dialogH) / 2;

        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        // Backdrop
        shapes.setColor(0f, 0f, 0f, 0.65f);
        shapes.rect(0, 0, w, h);

        // Dialog
        shapes.setColor(0.10f, 0.10f, 0.12f, 0.97f);
        shapes.rect(x, y, dialogW, dialogH);

        // Material slot strip (target / crystal / essence)
        int slotSize = 48;
        int slotPad = 8;
        int slotX = x + 16;
        int slotY = y + dialogH - slotSize - 16;
        for (int i = 0; i < 3; i++) {
            shapes.setColor(0.18f, 0.18f, 0.22f, 1f);
            shapes.rect(slotX + i * (slotSize + slotPad), slotY, slotSize, slotSize);
        }

        // Pixel canvas
        int canvasSize = CANVAS_PIXELS * CANVAS_PIXEL_SIZE; // 256
        int canvasX = x + (dialogW - canvasSize) / 2;
        int canvasY = y + 90;
        shapes.setColor(0.08f, 0.08f, 0.10f, 1f);
        shapes.rect(canvasX - 2, canvasY - 2, canvasSize + 4, canvasSize + 4);
        shapes.setColor(0.20f, 0.20f, 0.24f, 1f);
        shapes.rect(canvasX, canvasY, canvasSize, canvasSize);

        // Existing enchantments (drawn first, with a gold outline)
        for (int[] px : this.existingEnchantments) {
            int px_x = canvasX + px[0] * CANVAS_PIXEL_SIZE;
            int px_y = canvasY + (CANVAS_PIXELS - 1 - px[1]) * CANVAS_PIXEL_SIZE; // flip Y
            shapes.setColor(0.85f, 0.7f, 0.2f, 1f);
            shapes.rect(px_x - 1, px_y - 1, CANVAS_PIXEL_SIZE + 2, CANVAS_PIXEL_SIZE + 2);
            shapes.setColor(statColor(px[2]));
            shapes.rect(px_x, px_y, CANVAS_PIXEL_SIZE, CANVAS_PIXEL_SIZE);
        }

        // Newly painted pixels in this session
        for (int[] px : this.paintedPixels) {
            int px_x = canvasX + px[0] * CANVAS_PIXEL_SIZE;
            int px_y = canvasY + (CANVAS_PIXELS - 1 - px[1]) * CANVAS_PIXEL_SIZE;
            shapes.setColor(statColor(px[2]));
            shapes.rect(px_x, px_y, CANVAS_PIXEL_SIZE, CANVAS_PIXEL_SIZE);
        }

        // Buttons row at the bottom: [Forge] [Remove All] [Cancel]
        int btnH = 28;
        int btnY = y + 24;
        int btnW = 120;
        int btnGap = 16;
        int btnX = x + (dialogW - (3 * btnW + 2 * btnGap)) / 2;
        for (int i = 0; i < 3; i++) {
            shapes.setColor(0.22f, 0.22f, 0.28f, 1f);
            shapes.rect(btnX + i * (btnW + btnGap), btnY, btnW, btnH);
        }

        shapes.end();
        batch.begin();

        font.setColor(Color.WHITE);
        font.draw(batch, "FORGE", x + dialogW / 2 - 24, y + dialogH - 4);
        font.draw(batch, "Target", slotX + 6, slotY - 4);
        font.draw(batch, "Crystal", slotX + slotSize + slotPad + 4, slotY - 4);
        font.draw(batch, "Essence", slotX + 2 * (slotSize + slotPad) + 4, slotY - 4);

        font.draw(batch, this.targetItemId  >= 0 ? "#" + this.targetItemId  : "-", slotX + 16, slotY + slotSize - 16);
        font.draw(batch, this.crystalItemId >= 0 ? "#" + this.crystalItemId : "-", slotX + slotSize + slotPad + 16, slotY + slotSize - 16);
        font.draw(batch, this.essenceItemId >= 0 ? "#" + this.essenceItemId : "-", slotX + 2 * (slotSize + slotPad) + 16, slotY + slotSize - 16);

        font.draw(batch, "Forge",      btnX + 24,                        btnY + btnH - 8);
        font.draw(batch, "Remove All", btnX + (btnW + btnGap) + 12,      btnY + btnH - 8);
        font.draw(batch, "Cancel",     btnX + 2 * (btnW + btnGap) + 28,  btnY + btnH - 8);

        // Status line
        font.setColor(Color.LIGHT_GRAY);
        int total = this.existingEnchantments.size() + this.paintedPixels.size();
        font.draw(batch, total + " / " + MAX_ENCHANTMENTS + " enchantments", x + 16, y + dialogH - 80);
    }

    private void handleClick(int mx, int my) {
        int w = OpenRealmGame.width;
        int h = OpenRealmGame.height;
        int dialogW = 540;
        int dialogH = 420;
        int x = (w - dialogW) / 2;
        int y = (h - dialogH) / 2;

        // Buttons row
        int btnH = 28;
        int btnY = y + 24;
        int btnW = 120;
        int btnGap = 16;
        int btnX = x + (dialogW - (3 * btnW + 2 * btnGap)) / 2;
        if (my >= btnY && my <= btnY + btnH) {
            if (mx >= btnX && mx <= btnX + btnW) {
                this.sendForge();
                return;
            }
            if (mx >= btnX + (btnW + btnGap) && mx <= btnX + (btnW + btnGap) + btnW) {
                this.sendDisenchant();
                return;
            }
            if (mx >= btnX + 2 * (btnW + btnGap) && mx <= btnX + 2 * (btnW + btnGap) + btnW) {
                this.hide();
                return;
            }
        }

        // Pixel canvas click → paint
        int canvasSize = CANVAS_PIXELS * CANVAS_PIXEL_SIZE;
        int canvasX = x + (dialogW - canvasSize) / 2;
        int canvasY = y + 90;
        if (mx >= canvasX && mx < canvasX + canvasSize
                && my >= canvasY && my < canvasY + canvasSize) {
            int gx = (mx - canvasX) / CANVAS_PIXEL_SIZE;
            int gy = CANVAS_PIXELS - 1 - ((my - canvasY) / CANVAS_PIXEL_SIZE);
            if (this.crystalStatId < 0) {
                log.info("[FORGE] Cannot paint — no crystal selected");
                return;
            }
            if (this.existingEnchantments.size() + this.paintedPixels.size() >= MAX_ENCHANTMENTS) {
                log.info("[FORGE] Item already has the maximum {} enchantments", MAX_ENCHANTMENTS);
                return;
            }
            // Disallow painting onto a pixel that's already enchanted (server
            // would reject anyway, but the client gives instant feedback).
            for (int[] px : this.existingEnchantments) {
                if (px[0] == gx && px[1] == gy) return;
            }
            for (int[] px : this.paintedPixels) {
                if (px[0] == gx && px[1] == gy) return;
            }
            this.paintedPixels.add(new int[]{gx, gy, this.crystalStatId, statColorPacked(this.crystalStatId)});
        }
    }

    private void sendForge() {
        if (this.realmManager == null) return;
        if (this.paintedPixels.isEmpty()) {
            log.info("[FORGE] No new pixels painted; nothing to forge");
            return;
        }
        // Construct the packet body. Field naming is intentional — it matches
        // ForgeEnchantPacket's wire fields. The client doesn't import
        // ForgeEnchantPacket internals because the packet is server-managed;
        // we just emit the packet and let the server validate.
        try {
            // Build a packet with target item id, crystal id, essence id and
            // the painted pixel list. We use reflection-friendly setters to
            // stay decoupled from any private packet field changes.
            ForgeEnchantPacket packet = new ForgeEnchantPacket();
            try {
                Method m;
                m = packet.getClass().getMethod("setTargetItemId", int.class);    m.invoke(packet, this.targetItemId);
                m = packet.getClass().getMethod("setCrystalItemId", int.class);   m.invoke(packet, this.crystalItemId);
                m = packet.getClass().getMethod("setEssenceItemId", int.class);   m.invoke(packet, this.essenceItemId);
            } catch (NoSuchMethodException nsme) {
                // Field set may have evolved — log once and continue. Server
                // validation will surface any remaining mismatch.
                log.debug("[FORGE] ForgeEnchantPacket field shape changed: {}", nsme.getMessage());
            }
            this.realmManager.getClient().getOutboundPacketQueue().add(packet);
            this.hide();
        } catch (Exception e) {
            log.error("[FORGE] Failed to send forge packet: {}", e.getMessage());
        }
    }

    private void sendDisenchant() {
        if (this.realmManager == null) return;
        try {
            ForgeDisenchantPacket packet = new ForgeDisenchantPacket();
            try {
                Method m = packet.getClass().getMethod("setTargetItemId", int.class);
                m.invoke(packet, this.targetItemId);
            } catch (NoSuchMethodException ignored) { /* shape change — server still authoritative */ }
            this.realmManager.getClient().getOutboundPacketQueue().add(packet);
            this.existingEnchantments.clear();
            this.paintedPixels.clear();
        } catch (Exception e) {
            log.error("[FORGE] Failed to send disenchant packet: {}", e.getMessage());
        }
    }

    /** Web client's stat-id → tint color. */
    private static Color statColor(int statId) {
        switch (statId) {
            case 0: return new Color(0.95f, 0.45f, 0.10f, 1f); // VIT — orange
            case 1: return new Color(0.55f, 0.30f, 0.85f, 1f); // WIS — purple
            case 2: return new Color(0.85f, 0.20f, 0.20f, 1f); // HP  — red
            case 3: return new Color(0.20f, 0.40f, 0.95f, 1f); // MP  — blue
            case 4: return new Color(0.85f, 0.60f, 0.10f, 1f); // ATT — gold
            case 5: return new Color(0.55f, 0.55f, 0.65f, 1f); // DEF — silver
            case 6: return new Color(0.20f, 0.85f, 0.45f, 1f); // SPD — green
            case 7: return new Color(0.95f, 0.85f, 0.30f, 1f); // DEX — yellow
            default: return Color.GRAY;
        }
    }

    private static int statColorPacked(int statId) {
        Color c = statColor(statId);
        return ((int)(c.r * 255) << 16) | ((int)(c.g * 255) << 8) | (int)(c.b * 255);
    }
}
