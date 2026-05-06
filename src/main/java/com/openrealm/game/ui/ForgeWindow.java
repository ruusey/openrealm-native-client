package com.openrealm.game.ui;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.openrealm.game.OpenRealmGame;
import com.openrealm.game.data.GameSpriteManager;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.state.PlayState;
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
    /** Default grid cell count. Items with spriteSize == 8 collapse the
     *  visible grid to 8×8 with bigger cells so the painting surface
     *  shows one cell per actual sprite pixel — the previous fixed
     *  16×16 grid had the player painting at half the per-pixel
     *  resolution, and the staged pixels never lined up with the
     *  authored item art. */
    public static final int CANVAS_PIXELS = 16;
    public static final int CANVAS_PIXEL_SIZE = 16;   // each grid cell is 16 device px
    public static final int CANVAS_RENDER_SIZE = CANVAS_PIXELS * CANVAS_PIXEL_SIZE; // 256 px
    public static final int MAX_ENCHANTMENTS = 5;

    private boolean visible = false;

    @Setter private RealmManagerClient realmManager;
    /** PlayState handle so we can resolve a forge slot index to an
     *  actual GameItem from the player's inventory at render time. The
     *  webclient does this trivially via {@code _game.inventory[slot]};
     *  the native client needs the explicit reference because the forge
     *  window doesn't otherwise know about the player. Set right next
     *  to setRealmManager from the OpenForgePacket handler. */
    @Setter private PlayState playState;

    /** Inventory slot index of the equipment currently in the forge
     *  target slot (-1 when empty). The webclient stores the SLOT, not
     *  the item id, because the server's ForgeEnchantPacket wants
     *  byte-typed inventory slot indices for target / crystal /
     *  essence — passing item ids was the bug that made the previous
     *  reflective send do nothing (no setTargetItemId method exists). */
    @Setter private int targetSlot = -1;
    /** Inventory slot of the chosen crystal (-1 when empty). */
    @Setter private int crystalSlot = -1;
    /** Inventory slot of the essence (-1 when empty). */
    @Setter private int essenceSlot = -1;
    /** Item id of the crystal — the only "id" the packet needs (the
     *  server uses it to pull the stat / shard data). Mirrors the
     *  ForgeEnchantPacket field layout. */
    @Setter private int crystalItemId = -1;
    /** Stat id encoded by the selected crystal (0=VIT 1=WIS 2=HP 3=MP 4=ATT 5=DEF 6=SPD 7=DEX). */
    @Setter private int crystalStatId = -1;

    /** Pixels the user has painted in this session. Each entry is {x, y, statId, color-rgb}. */
    private final List<int[]> paintedPixels = new ArrayList<>();

    /** Last-rendered grid dimension. Updated by render() so handleClick
     *  can map mouse-pixel coordinates back to the matching sprite-pixel
     *  no matter what spriteSize the target item turned out to be. */
    private int activeGridDim = CANVAS_PIXELS;

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
            // Render uses flipped ortho (y=0 at TOP of screen), and
            // Gdx.input.getY() is also top-down, so pass it through —
            // do NOT flip it. The previous code ran (height - getY())
            // which inverted clicks against the rendered layout, so
            // every button hit-test missed and Cancel was unclickable.
            this.handleClick(Gdx.input.getX(), Gdx.input.getY());
        }
        this.mouseDownPrev = down;
    }

    /** Layout constants — single source of truth so render() and
     *  handleClick() can never disagree. All Y values are TOP-DOWN
     *  (flipped ortho), since that's the projection the rest of the
     *  HUD uses. */
    private static final int DIALOG_W = 540;
    private static final int DIALOG_H = 420;
    private static final int BTN_H    = 28;
    private static final int BTN_W    = 120;
    private static final int BTN_GAP  = 16;
    private static final int SLOT_SIZE = 48;
    private static final int SLOT_PAD  = 8;

    /** Compute the dialog rect once per frame so render+click share it. */
    private int[] layout() {
        final int w = OpenRealmGame.width;
        final int h = OpenRealmGame.height;
        final int x = (w - DIALOG_W) / 2;
        final int y = (h - DIALOG_H) / 2;
        return new int[]{ x, y };
    }

    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        if (!this.visible) return;

        final int w = OpenRealmGame.width;
        final int h = OpenRealmGame.height;
        final int[] L = layout();
        final int x = L[0];
        final int y = L[1];

        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        // Backdrop
        shapes.setColor(0f, 0f, 0f, 0.65f);
        shapes.rect(0, 0, w, h);

        // Dialog
        shapes.setColor(0.10f, 0.10f, 0.12f, 0.97f);
        shapes.rect(x, y, DIALOG_W, DIALOG_H);

        // Header strip (top edge in flipped ortho = smaller y)
        final int headerH = 32;
        shapes.setColor(0.06f, 0.06f, 0.08f, 1f);
        shapes.rect(x, y, DIALOG_W, headerH);

        // Buttons row sits BELOW the header (top of dialog), where the
        // user's eye expects "primary actions" to live. Cancel is the
        // last button so the layout reads left-to-right.
        final int btnY = y + (headerH - BTN_H) / 2;
        final int btnX = x + (DIALOG_W - (3 * BTN_W + 2 * BTN_GAP)) / 2;
        for (int i = 0; i < 3; i++) {
            shapes.setColor(0.22f, 0.22f, 0.28f, 1f);
            shapes.rect(btnX + i * (BTN_W + BTN_GAP), btnY, BTN_W, BTN_H);
        }

        // Material slot strip (target / crystal / essence) just below
        // the header so the player sees what they're working with.
        final int slotX = x + 16;
        final int slotY = y + headerH + 24;
        for (int i = 0; i < 3; i++) {
            shapes.setColor(0.18f, 0.18f, 0.22f, 1f);
            shapes.rect(slotX + i * (SLOT_SIZE + SLOT_PAD), slotY, SLOT_SIZE, SLOT_SIZE);
        }

        // Pixel canvas: centered horizontally, anchored below the slots
        // so the workspace is the visual focus of the dialog.
        final int canvasSize = CANVAS_RENDER_SIZE; // 256
        final int canvasX = x + (DIALOG_W - canvasSize) / 2;
        final int canvasY = slotY + SLOT_SIZE + 28;
        shapes.setColor(0.08f, 0.08f, 0.10f, 1f);
        shapes.rect(canvasX - 2, canvasY - 2, canvasSize + 4, canvasSize + 4);
        shapes.setColor(0.20f, 0.20f, 0.24f, 1f);
        shapes.rect(canvasX, canvasY, canvasSize, canvasSize);

        shapes.end();
        batch.begin();

        // Resolve the actual GameItems sitting in each forge slot from
        // the player's inventory. With these we can blit the item sprite
        // into the slot square (visual) AND scale the target weapon up
        // to fill the canvas as a paint background — the user's "where
        // does this pixel go on my sword" question is impossible to
        // answer without seeing the sword.
        final GameItem targetItem  = inventoryItem(this.targetSlot);
        final GameItem crystalItem = inventoryItem(this.crystalSlot);
        final GameItem essenceItem = inventoryItem(this.essenceSlot);

        // Target weapon scaled to fill the canvas, so existing + new
        // enchantment pixels visibly land on the sword/wand/etc. The
        // grid cells map 1:1 to the item's sprite pixels (8×8 most of
        // the time → 32 device px per cell).
        final int gridDim;
        if (targetItem != null) {
            int sw = targetItem.getSpriteSize() > 0 ? targetItem.getSpriteSize() : 8;
            // Cap at CANVAS_PIXELS so unusual sprite sizes still fit.
            gridDim = Math.min(CANVAS_PIXELS, Math.max(1, sw));
            final TextureRegion bg = GameSpriteManager.ITEM_SPRITES != null
                    ? GameSpriteManager.ITEM_SPRITES.get(targetItem.getItemId()) : null;
            if (bg != null) {
                batch.draw(bg, canvasX, canvasY, canvasSize, canvasSize);
            }
        } else {
            gridDim = CANVAS_PIXELS;
        }
        final float cellPx = (float) canvasSize / (float) gridDim;

        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        // Item sprites inside the forge slots so the player can SEE
        // which item they dropped where without parsing slot indices.
        // Drawn through the batch, framed by the slot's fill rect.
        shapes.end();
        batch.begin();
        drawItemInSlot(batch, targetItem,  slotX,                                 slotY);
        drawItemInSlot(batch, crystalItem, slotX + (SLOT_SIZE + SLOT_PAD),        slotY);
        drawItemInSlot(batch, essenceItem, slotX + 2 * (SLOT_SIZE + SLOT_PAD),    slotY);
        batch.end();

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        // Existing enchantments — flat stat-color square per pixel,
        // sized to the dynamic cell so the dot lands on the SAME pixel
        // the server stored. Gold outline behind so existing forge
        // pixels read distinct from newly-staged ones.
        for (int[] px : this.existingEnchantments) {
            float pxx = canvasX + px[0] * cellPx;
            float pxy = canvasY + px[1] * cellPx;
            shapes.setColor(0.85f, 0.7f, 0.2f, 1f);
            shapes.rect(pxx - 1, pxy - 1, cellPx + 2, cellPx + 2);
            shapes.setColor(statColor(px[2]));
            shapes.rect(pxx, pxy, cellPx, cellPx);
        }

        // Newly painted pixels in this session
        for (int[] px : this.paintedPixels) {
            float pxx = canvasX + px[0] * cellPx;
            float pxy = canvasY + px[1] * cellPx;
            shapes.setColor(statColor(px[2]));
            shapes.rect(pxx, pxy, cellPx, cellPx);
        }

        // Faint grid lines so the player can see exactly which pixel
        // they're about to click. Drawn in Line mode.
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(1f, 1f, 1f, 0.18f);
        for (int i = 0; i <= gridDim; i++) {
            float gx = canvasX + i * cellPx;
            shapes.line(gx, canvasY, gx, canvasY + canvasSize);
            float gy = canvasY + i * cellPx;
            shapes.line(canvasX, gy, canvasX + canvasSize, gy);
        }
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
        batch.begin();
        // Stash the active grid dim so handleClick can convert mouse-px
        // back into the matching sprite pixel. Without this the click
        // path was hardcoded to a 16×16 grid even when render switched
        // to a smaller dim.
        this.activeGridDim = gridDim;

        // Title in the header bar.
        font.setColor(Color.WHITE);
        font.draw(batch, "FORGE", x + 16, y + 22);

        // Button labels — text baseline sits ~8 px above the button's
        // bottom edge in flipped ortho, so y = btnY + BTN_H - 8.
        font.draw(batch, "Forge",      btnX + 36,                        btnY + BTN_H - 9);
        font.draw(batch, "Remove All", btnX + (BTN_W + BTN_GAP) + 16,    btnY + BTN_H - 9);
        font.draw(batch, "Cancel",     btnX + 2 * (BTN_W + BTN_GAP) + 36, btnY + BTN_H - 9);

        // Slot labels above the slots.
        font.draw(batch, "Target",  slotX + 6,                                 slotY - 4);
        font.draw(batch, "Crystal", slotX + SLOT_SIZE + SLOT_PAD + 4,          slotY - 4);
        font.draw(batch, "Essence", slotX + 2 * (SLOT_SIZE + SLOT_PAD) + 4,    slotY - 4);

        // Slot empty marker — only drawn when the slot has nothing in
        // it, since drawItemInSlot already painted the sprite for
        // populated slots.
        if (this.targetSlot < 0) font.draw(batch, "Drop item here",
                slotX + 4, slotY + SLOT_SIZE / 2 + 4);
        if (this.crystalSlot < 0) font.draw(batch, "Drop crystal",
                slotX + SLOT_SIZE + SLOT_PAD + 4, slotY + SLOT_SIZE / 2 + 4);
        if (this.essenceSlot < 0) font.draw(batch, "Drop essence",
                slotX + 2 * (SLOT_SIZE + SLOT_PAD) + 4, slotY + SLOT_SIZE / 2 + 4);

        // Status line below the canvas — N / 5 enchantments.
        font.setColor(Color.LIGHT_GRAY);
        int total = this.existingEnchantments.size() + this.paintedPixels.size();
        font.draw(batch, total + " / " + MAX_ENCHANTMENTS + " enchantments",
                x + 16, canvasY + canvasSize + 24);
    }

    private void handleClick(int mx, int my) {
        final int[] L = layout();
        final int x = L[0];
        final int y = L[1];

        // Buttons row (matches render exactly).
        final int headerH = 32;
        final int btnY = y + (headerH - BTN_H) / 2;
        final int btnX = x + (DIALOG_W - (3 * BTN_W + 2 * BTN_GAP)) / 2;
        if (my >= btnY && my <= btnY + BTN_H) {
            if (mx >= btnX && mx <= btnX + BTN_W) {
                this.sendForge();
                return;
            }
            if (mx >= btnX + (BTN_W + BTN_GAP) && mx <= btnX + (BTN_W + BTN_GAP) + BTN_W) {
                this.sendDisenchant();
                return;
            }
            if (mx >= btnX + 2 * (BTN_W + BTN_GAP)
                    && mx <= btnX + 2 * (BTN_W + BTN_GAP) + BTN_W) {
                this.hide();
                return;
            }
        }

        // Pixel canvas click → paint (mirror render's layout).
        final int slotY = y + headerH + 24;
        final int canvasSize = CANVAS_RENDER_SIZE;
        final int canvasX = x + (DIALOG_W - canvasSize) / 2;
        final int canvasY = slotY + SLOT_SIZE + 28;
        if (mx >= canvasX && mx < canvasX + canvasSize
                && my >= canvasY && my < canvasY + canvasSize) {
            // Convert mouse-pixel → sprite-pixel using the SAME grid
            // dim render() last drew. Hardcoding CANVAS_PIXELS=16 here
            // would mis-locate every click whenever the target weapon
            // is an 8×8 sprite (i.e. nearly every weapon).
            final int gd = Math.max(1, this.activeGridDim);
            final float cellPx = (float) canvasSize / (float) gd;
            int gx = (int) ((mx - canvasX) / cellPx);
            int gy = (int) ((my - canvasY) / cellPx);
            if (gx < 0) gx = 0; if (gx >= gd) gx = gd - 1;
            if (gy < 0) gy = 0; if (gy >= gd) gy = gd - 1;
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
        if (this.targetSlot < 0 || this.crystalSlot < 0 || this.essenceSlot < 0) {
            log.info("[FORGE] Drop target / crystal / essence into the slots first");
            return;
        }
        // Server expects the FIRST painted pixel only — the webclient
        // also forges a single pixel per Forge click. Send that one and
        // wait for the server's ack to refresh existingEnchantments.
        try {
            int[] firstPx = this.paintedPixels.get(0);
            ForgeEnchantPacket packet = new ForgeEnchantPacket();
            packet.setPlayerId(this.realmManager.getCurrentPlayerId());
            packet.setTargetItemSlot((byte) this.targetSlot);
            packet.setCrystalItemId(this.crystalItemId);
            packet.setCrystalSlotIndex((byte) this.crystalSlot);
            packet.setEssenceSlotIndex((byte) this.essenceSlot);
            packet.setPixelX((byte) firstPx[0]);
            packet.setPixelY((byte) firstPx[1]);
            this.realmManager.getClient().getOutboundPacketQueue().add(packet);
            this.hide();
        } catch (Exception e) {
            log.error("[FORGE] Failed to send forge packet: {}", e.getMessage());
        }
    }

    private void sendDisenchant() {
        if (this.realmManager == null) return;
        if (this.targetSlot < 0) {
            log.info("[FORGE] Drop a target item first to disenchant");
            return;
        }
        try {
            ForgeDisenchantPacket packet = new ForgeDisenchantPacket();
            // Wire shape varies between server versions — try each known
            // setter via reflection so an upgrade doesn't crash the UI.
            try {
                Method m = packet.getClass().getMethod("setPlayerId", long.class);
                m.invoke(packet, this.realmManager.getCurrentPlayerId());
            } catch (NoSuchMethodException ignored) { }
            try {
                Method m = packet.getClass().getMethod("setTargetItemSlot", byte.class);
                m.invoke(packet, (byte) this.targetSlot);
            } catch (NoSuchMethodException ignored) {
                try {
                    Method m = packet.getClass().getMethod("setTargetItemId", int.class);
                    m.invoke(packet, this.targetSlot);
                } catch (NoSuchMethodException ignored2) { }
            }
            this.realmManager.getClient().getOutboundPacketQueue().add(packet);
            this.existingEnchantments.clear();
            this.paintedPixels.clear();
        } catch (Exception e) {
            log.error("[FORGE] Failed to send disenchant packet: {}", e.getMessage());
        }
    }

    /** Geometry helpers so PlayerUI can hit-test against forge slots
     *  during inventory drag-drop. Returns {x, y, w, h} of each slot in
     *  flipped-ortho coords, or null if the window is closed. */
    public int[] targetSlotRect() { return slotRect(0); }
    public int[] crystalSlotRect() { return slotRect(1); }
    public int[] essenceSlotRect() { return slotRect(2); }

    private int[] slotRect(int idx) {
        if (!this.visible) return null;
        final int[] L = layout();
        final int x = L[0];
        final int y = L[1];
        final int headerH = 32;
        final int slotX = x + 16 + idx * (SLOT_SIZE + SLOT_PAD);
        final int slotY = y + headerH + 24;
        return new int[]{ slotX, slotY, SLOT_SIZE, SLOT_SIZE };
    }

    /** Try to consume a drop at (mx, my) by binding the source inventory
     *  slot to the matching forge slot. Returns true if accepted, false
     *  otherwise so the caller can fall through to its normal swap
     *  logic.
     *
     *  Server flow stays authoritative: this only updates client-side
     *  state. The actual enchantment fires when the player clicks the
     *  Forge button, at which point sendForge() emits ForgeEnchantPacket
     *  with the slot indices. */
    public boolean tryAcceptDrop(int mx, int my, int srcSlotIdx, int crystalItemId, int crystalStatId) {
        if (!this.visible) return false;
        // Ground-loot slots (20..27) are NOT droppable into the forge —
        // server requires the item to live in inventory first.
        if (srcSlotIdx < 0 || srcSlotIdx > 19) return false;
        if (hits(mx, my, targetSlotRect()))  { this.targetSlot  = srcSlotIdx; return true; }
        if (hits(mx, my, crystalSlotRect())) {
            this.crystalSlot = srcSlotIdx;
            this.crystalItemId = crystalItemId;
            this.crystalStatId = crystalStatId;
            return true;
        }
        if (hits(mx, my, essenceSlotRect())) { this.essenceSlot = srcSlotIdx; return true; }
        return false;
    }

    private static boolean hits(int mx, int my, int[] r) {
        return r != null && mx >= r[0] && mx <= r[0] + r[2]
                          && my >= r[1] && my <= r[1] + r[3];
    }

    /** Resolve a forge-slot's bound inventory index to the actual
     *  GameItem in the player's bag. Returns null when the slot is
     *  empty or the player isn't reachable yet. */
    private GameItem inventoryItem(int invSlot) {
        if (invSlot < 0) return null;
        if (this.playState == null) return null;
        try {
            final Player p = this.playState.getPlayer();
            if (p == null) return null;
            final GameItem[] inv = p.getInventory();
            if (inv == null || invSlot >= inv.length) return null;
            return inv[invSlot];
        } catch (Exception e) {
            return null;
        }
    }

    /** Blit the item icon centered inside the slot square so the player
     *  can visually confirm what they dropped. The 64×64 inventory
     *  icons are too large for the 48-px slot, so we draw at 40 px
     *  with a 4-px inset all around. */
    private void drawItemInSlot(SpriteBatch batch, GameItem item, int sx, int sy) {
        if (item == null) return;
        if (GameSpriteManager.ITEM_SPRITES == null) return;
        final TextureRegion tr = GameSpriteManager.ITEM_SPRITES.get(item.getItemId());
        if (tr == null) return;
        final int iconSize = SLOT_SIZE - 8;
        batch.draw(tr, sx + 4, sy + 4, iconSize, iconSize);
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
