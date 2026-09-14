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
import com.openrealm.game.ui.atlas.UiAtlas;
import com.openrealm.game.ui.atlas.UiComponent;
import com.openrealm.net.realm.RealmManagerClient;
import com.openrealm.net.server.packet.ForgeDisenchantPacket;
import com.openrealm.net.server.packet.ForgeEnchantPacket;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import java.lang.reflect.Method;

/** Pixel-painting forge UI mirroring the web client's enchant flow. The
 *  server is authoritative; this window only stages target/crystal/essence
 *  slots and painted pixels, then emits ForgeEnchantPacket / ForgeDisenchantPacket. */
@Slf4j
public class ForgeWindow {
    public static final int CANVAS_PIXELS = 16;
    public static final int CANVAS_PIXEL_SIZE = 16;
    public static final int CANVAS_RENDER_SIZE = CANVAS_PIXELS * CANVAS_PIXEL_SIZE;
    // Upper sanity bound only; the enforced cap is rarity-driven (currentMaxEnchantments()).
    public static final int MAX_ENCHANTMENTS = 5;
    // Painted-pixel statId marker for a gem (gems carry no stat), distinct from the eight stat crystals.
    private static final int GEM_PAINT_MARKER = 100;
    // Modal-only multiplier on top of UiAtlas.getDisplayScale(); matches the webclient's --forge-scale.
    private static final int MODAL_SCALE = 2;

    private boolean visible = false;

    @Setter private RealmManagerClient realmManager;
    @Setter private PlayState playState;

    // Forge slots store INVENTORY SLOT indices (not item ids); the packet is byte-typed on slots.
    @Setter private int targetSlot = -1;
    @Setter private int crystalSlot = -1;
    @Setter private int essenceSlot = -1;
    // Item id of the crystal is the only "id" the packet needs (server pulls stat/shard data from it).
    @Setter private int crystalItemId = -1;
    /** Stat id encoded by the selected crystal (0=VIT 1=WIS 2=HP 3=MP 4=STR 5=DEF 6=SPD 7=DEX). */
    @Setter private int crystalStatId = -1;

    /** Each entry is {x, y, statId, color-rgb}. */
    private final List<int[]> paintedPixels = new ArrayList<>();

    // Last-rendered grid dimension; render() sets it so handleClick maps clicks to the same sprite pixel.
    private int activeGridDim = CANVAS_PIXELS;

    @Setter private List<int[]> existingEnchantments = new ArrayList<>();

    private boolean mouseDownPrev = false;

    private String statusMessage = "";

    public boolean isVisible() {
        return this.visible;
    }

    public void show() {
        this.visible = true;
        this.paintedPixels.clear();
        this.statusMessage = "";
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
            // Flipped ortho: getY() is already top-down, pass it through (do NOT flip).
            this.handleClick(Gdx.input.getX(), Gdx.input.getY());
        }
        this.mouseDownPrev = down;
    }

    /** Returns null when the atlas isn't ready; callers must bail so we never paint stale geometry. */
    private ForgeLayout computeLayout() {
        if (!UiAtlas.isReady()) return null;
        final UiComponent cont   = UiAtlas.componentOf("panel.forge.container");
        final UiComponent status = UiAtlas.componentOf("panel.forge.status");
        final UiComponent inItem = UiAtlas.componentOf("panel.forge.input.item");
        final UiComponent inCry  = UiAtlas.componentOf("panel.forge.input.crystal");
        final UiComponent inEss  = UiAtlas.componentOf("panel.forge.input.essence");
        final UiComponent lbItem = UiAtlas.componentOf("panel.forge.label.item");
        // The LABEL id carries the typo 'cyrstal' in ui-components.json; honor it verbatim or the lookup 404s.
        final UiComponent lbCry  = UiAtlas.componentOf("panel.forge.label.cyrstal");
        final UiComponent lbEss  = UiAtlas.componentOf("panel.forge.label.essence");
        final UiComponent output = UiAtlas.componentOf("panel.forge.output");
        if (cont == null || status == null || inItem == null || inCry == null
                || inEss == null || lbItem == null || lbCry == null
                || lbEss == null || output == null) return null;

        final int s = UiAtlas.getDisplayScale() * MODAL_SCALE;
        final ForgeLayout L = new ForgeLayout();
        L.s = s;
        L.containerW = cont.getW() * s;
        L.containerH = cont.getH() * s;
        L.containerX = (OpenRealmGame.width  - L.containerW) / 2;
        L.containerY = (OpenRealmGame.height - L.containerH) / 2;

        // screen = containerOrigin + (compSrc - containerSrc) * displayScale
        final int cox = cont.getX();
        final int coy = cont.getY();

        L.statusX = L.containerX + (status.getX() - cox) * s;
        L.statusY = L.containerY + (status.getY() - coy) * s;
        L.statusW = status.getW() * s;
        L.statusH = status.getH() * s;

        L.itemSlotX = L.containerX + (inItem.getX() - cox) * s;
        L.itemSlotY = L.containerY + (inItem.getY() - coy) * s;
        L.itemSlotW = inItem.getW() * s;
        L.itemSlotH = inItem.getH() * s;

        L.crystalSlotX = L.containerX + (inCry.getX() - cox) * s;
        L.crystalSlotY = L.containerY + (inCry.getY() - coy) * s;
        L.crystalSlotW = inCry.getW() * s;
        L.crystalSlotH = inCry.getH() * s;

        L.essenceSlotX = L.containerX + (inEss.getX() - cox) * s;
        L.essenceSlotY = L.containerY + (inEss.getY() - coy) * s;
        L.essenceSlotW = inEss.getW() * s;
        L.essenceSlotH = inEss.getH() * s;

        L.labelItemX = L.containerX + (lbItem.getX() - cox) * s;
        L.labelItemY = L.containerY + (lbItem.getY() - coy) * s;
        L.labelItemW = lbItem.getW() * s;
        L.labelItemH = lbItem.getH() * s;

        L.labelCrystalX = L.containerX + (lbCry.getX() - cox) * s;
        L.labelCrystalY = L.containerY + (lbCry.getY() - coy) * s;
        L.labelCrystalW = lbCry.getW() * s;
        L.labelCrystalH = lbCry.getH() * s;

        L.labelEssenceX = L.containerX + (lbEss.getX() - cox) * s;
        L.labelEssenceY = L.containerY + (lbEss.getY() - coy) * s;
        L.labelEssenceW = lbEss.getW() * s;
        L.labelEssenceH = lbEss.getH() * s;

        L.outputX = L.containerX + (output.getX() - cox) * s;
        L.outputY = L.containerY + (output.getY() - coy) * s;
        L.outputW = output.getW() * s;
        L.outputH = output.getH() * s;

        // Three equal-width buttons inside the status bar.
        L.btnH = Math.max(14, L.statusH - 2);
        L.btnY = L.statusY + (L.statusH - L.btnH) / 2;
        L.btnW = (L.statusW - 6) / 3;
        L.btnForgeX  = L.statusX + 2;
        L.btnRemoveX = L.btnForgeX + L.btnW + 1;
        L.btnCancelX = L.btnRemoveX + L.btnW + 1;

        // Square paint canvas inscribed in the output region; gridDim is computed at render time.
        L.canvasSize = Math.min(L.outputW, L.outputH);
        L.canvasX = L.outputX + (L.outputW - L.canvasSize) / 2;
        L.canvasY = L.outputY + (L.outputH - L.canvasSize) / 2;
        return L;
    }

    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        if (!this.visible) return;
        final ForgeLayout L = computeLayout();
        if (L == null) return; // atlas not ready — fail silently

        // ------------------------------------------------------------------
        // Backdrop dim — the only ShapeRenderer pass before the atlas blits.
        // ------------------------------------------------------------------
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, 0.65f);
        shapes.rect(0, 0, OpenRealmGame.width, OpenRealmGame.height);
        shapes.end();
        batch.begin();

        // Atlas chrome, back-to-front: container, status, then labels/inputs/output.
        blitAtlas(batch, "panel.forge.container", L.containerX, L.containerY, L.containerW, L.containerH);
        blitAtlas(batch, "panel.forge.status",    L.statusX,    L.statusY,    L.statusW,    L.statusH);
        blitAtlas(batch, "panel.forge.label.item",    L.labelItemX,    L.labelItemY,    L.labelItemW,    L.labelItemH);
        blitAtlas(batch, "panel.forge.label.cyrstal", L.labelCrystalX, L.labelCrystalY, L.labelCrystalW, L.labelCrystalH);
        blitAtlas(batch, "panel.forge.label.essence", L.labelEssenceX, L.labelEssenceY, L.labelEssenceW, L.labelEssenceH);
        blitAtlas(batch, "panel.forge.input.item",    L.itemSlotX,    L.itemSlotY,    L.itemSlotW,    L.itemSlotH);
        blitAtlas(batch, "panel.forge.input.crystal", L.crystalSlotX, L.crystalSlotY, L.crystalSlotW, L.crystalSlotH);
        blitAtlas(batch, "panel.forge.input.essence", L.essenceSlotX, L.essenceSlotY, L.essenceSlotW, L.essenceSlotH);
        blitAtlas(batch, "panel.forge.output",        L.outputX,      L.outputY,      L.outputW,      L.outputH);

        // Action buttons: flat-fill rects (the atlas carries no button art); hit-tests share these rects.
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        drawButtonFill(shapes, L.btnForgeX,  L.btnY, L.btnW, L.btnH);
        drawButtonFill(shapes, L.btnRemoveX, L.btnY, L.btnW, L.btnH);
        drawButtonFill(shapes, L.btnCancelX, L.btnY, L.btnW, L.btnH);
        shapes.end();
        batch.begin();

        final GameItem targetItem  = inventoryItem(this.targetSlot);
        final GameItem crystalItem = inventoryItem(this.crystalSlot);
        final GameItem essenceItem = inventoryItem(this.essenceSlot);
        drawItemCentered(batch, targetItem,  L.itemSlotX,    L.itemSlotY,    L.itemSlotW,    L.itemSlotH);
        drawItemCentered(batch, crystalItem, L.crystalSlotX, L.crystalSlotY, L.crystalSlotW, L.crystalSlotH);
        drawItemCentered(batch, essenceItem, L.essenceSlotX, L.essenceSlotY, L.essenceSlotW, L.essenceSlotH);

        // Pixel canvas: gridDim follows the target item's spriteSize so painted pixels land on the stored source pixel.
        final int gridDim;
        if (targetItem != null) {
            int sw = targetItem.getSpriteSize() > 0 ? targetItem.getSpriteSize() : 8;
            gridDim = Math.min(CANVAS_PIXELS, Math.max(1, sw));
            final TextureRegion bg = GameSpriteManager.ITEM_SPRITES != null
                    ? GameSpriteManager.ITEM_SPRITES.get(targetItem.getItemId()) : null;
            if (bg != null) {
                batch.draw(bg, L.canvasX, L.canvasY, L.canvasSize, L.canvasSize);
            }
        } else {
            gridDim = CANVAS_PIXELS;
        }
        final float cellPx = (float) L.canvasSize / (float) gridDim;
        this.activeGridDim = gridDim;

        // Existing + newly-staged enchantment pixels.
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int[] px : this.existingEnchantments) {
            float pxx = L.canvasX + px[0] * cellPx;
            float pxy = L.canvasY + px[1] * cellPx;
            shapes.setColor(0.85f, 0.7f, 0.2f, 1f);
            shapes.rect(pxx - 1, pxy - 1, cellPx + 2, cellPx + 2);
            shapes.setColor(statColor(px[2]));
            shapes.rect(pxx, pxy, cellPx, cellPx);
        }
        for (int[] px : this.paintedPixels) {
            float pxx = L.canvasX + px[0] * cellPx;
            float pxy = L.canvasY + px[1] * cellPx;
            shapes.setColor(statColor(px[2]));
            shapes.rect(pxx, pxy, cellPx, cellPx);
        }
        shapes.end();
        // Faint grid overlay.
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(1f, 1f, 1f, 0.18f);
        for (int i = 0; i <= gridDim; i++) {
            float gx = L.canvasX + i * cellPx;
            shapes.line(gx, L.canvasY, gx, L.canvasY + L.canvasSize);
            float gy = L.canvasY + i * cellPx;
            shapes.line(L.canvasX, gy, L.canvasX + L.canvasSize, gy);
        }
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
        batch.begin();

        font.setColor(Color.WHITE);
        font.getData().setScale(0.85f);
        UiRender.drawCenteredIn(batch, font, "Forge",      L.btnForgeX,  L.btnY, L.btnW, L.btnH);
        UiRender.drawCenteredIn(batch, font, "Remove All", L.btnRemoveX, L.btnY, L.btnW, L.btnH);
        UiRender.drawCenteredIn(batch, font, "Cancel",     L.btnCancelX, L.btnY, L.btnW, L.btnH);
        font.getData().setScale(1f);

        font.setColor(0.95f, 0.85f, 0.55f, 1f);
        font.getData().setScale(0.7f);
        UiRender.drawCenteredIn(batch, font, "Item",    L.labelItemX,    L.labelItemY,    L.labelItemW,    L.labelItemH);
        UiRender.drawCenteredIn(batch, font, "Crystal", L.labelCrystalX, L.labelCrystalY, L.labelCrystalW, L.labelCrystalH);
        UiRender.drawCenteredIn(batch, font, "Essence", L.labelEssenceX, L.labelEssenceY, L.labelEssenceW, L.labelEssenceH);

        // Empty-slot hints.
        font.setColor(0.5f, 0.5f, 0.55f, 1f);
        if (this.targetSlot  < 0) font.draw(batch, "drop item",
                L.itemSlotX    + 4, L.itemSlotY    + L.itemSlotH    - 6);
        if (this.crystalSlot < 0) font.draw(batch, "drop crystal/gem",
                L.crystalSlotX + 4, L.crystalSlotY + L.crystalSlotH - 6);
        if (this.essenceSlot < 0) font.draw(batch, "drop essence",
                L.essenceSlotX + 4, L.essenceSlotY + L.essenceSlotH - 6);
        font.getData().setScale(1f);
        font.setColor(Color.WHITE);

        // Enchant counter just below the output region.
        final int total = this.existingEnchantments.size() + this.paintedPixels.size();
        font.setColor(Color.LIGHT_GRAY);
        font.draw(batch, total + " / " + currentMaxEnchantments() + " enchantments",
                L.outputX, L.outputY + L.outputH + 14);
        if (this.statusMessage != null && !this.statusMessage.isEmpty()) {
            font.setColor(0.95f, 0.5f, 0.4f, 1f);
            font.draw(batch, this.statusMessage, L.outputX, L.outputY + L.outputH + 30);
        }
        font.setColor(Color.WHITE);
    }

    private static void blitAtlas(SpriteBatch batch, String id, int x, int y, int w, int h) {
        final TextureRegion r = UiAtlas.region(id);
        if (r != null) {
            batch.draw(r, x, y, w, h);
        }
    }

    private static void drawButtonFill(ShapeRenderer shapes, int x, int y, int w, int h) {
        shapes.setColor(0.18f, 0.18f, 0.22f, 1f);
        shapes.rect(x, y, w, h);
    }

    /** Center an item sprite inside an arbitrary rect with a small inset. */
    private static void drawItemCentered(SpriteBatch batch, GameItem item, int x, int y, int w, int h) {
        if (item == null) return;
        if (GameSpriteManager.ITEM_SPRITES == null) return;
        final TextureRegion tr = GameSpriteManager.ITEM_SPRITES.get(item.getItemId());
        if (tr == null) return;
        final int pad = Math.max(2, Math.min(w, h) / 8);
        batch.draw(tr, x + pad, y + pad, w - 2 * pad, h - 2 * pad);
    }

    private void handleClick(int mx, int my) {
        final ForgeLayout L = computeLayout();
        if (L == null) return;

        if (my >= L.btnY && my < L.btnY + L.btnH) {
            if (mx >= L.btnForgeX  && mx < L.btnForgeX  + L.btnW) { this.sendForge();      return; }
            if (mx >= L.btnRemoveX && mx < L.btnRemoveX + L.btnW) { this.sendDisenchant(); return; }
            if (mx >= L.btnCancelX && mx < L.btnCancelX + L.btnW) { this.hide();           return; }
        }

        // Canvas click -> paint. gridDim mirrors activeGridDim so click maps to the same source pixel.
        if (mx >= L.canvasX && mx < L.canvasX + L.canvasSize
                && my >= L.canvasY && my < L.canvasY + L.canvasSize) {
            final int gd = Math.max(1, this.activeGridDim);
            final float cellPx = (float) L.canvasSize / (float) gd;
            int gx = (int) ((mx - L.canvasX) / cellPx);
            int gy = (int) ((my - L.canvasY) / cellPx);
            if (gx < 0) gx = 0; if (gx >= gd) gx = gd - 1;
            if (gy < 0) gy = 0; if (gy >= gd) gy = gd - 1;
            final GameItem crystalItem = inventoryItem(this.crystalSlot);
            final boolean isGem = crystalItem != null && "gem".equals(crystalItem.getCategory());
            if (isGem) {
                // Gems use the single Epic+ socket, separate from crystal slots (a full crystal bar must not block it).
                final GameItem target = inventoryItem(this.targetSlot);
                if (target == null || target.getRarity() < 4) {
                    this.statusMessage = "This rarity has no gem socket (Epic+ only)";
                    return;
                }
                if (target.getGemstoneType() != 0) {
                    this.statusMessage = "Item already has a gem socketed";
                    return;
                }
                final int[] allowed = resolveGemSlots(crystalItem);
                if (!gemFitsSlot(allowed, target.getTargetSlot())) {
                    this.statusMessage = crystalItem.getName() + " only fits: "
                            + gemAllowedSlotNames(allowed);
                    return;
                }
            } else {
                if (this.crystalStatId < 0) {
                    log.info("[FORGE] Cannot paint - no crystal selected");
                    return;
                }
                final int cap = currentMaxEnchantments();
                if (this.existingEnchantments.size() + this.paintedPixels.size() >= cap) {
                    log.info("[FORGE] Item already has the maximum {} crystal enchantments", cap);
                    return;
                }
            }
            for (int[] px : this.existingEnchantments) {
                if (px[0] == gx && px[1] == gy) return;
            }
            for (int[] px : this.paintedPixels) {
                if (px[0] == gx && px[1] == gy) return;
            }
            final int paintStat = isGem ? GEM_PAINT_MARKER : this.crystalStatId;
            this.paintedPixels.add(new int[]{gx, gy, paintStat, statColorPacked(paintStat)});
            this.statusMessage = "";
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
        // Server forges one pixel per click; send the first and wait for the ack to refresh existingEnchantments.
        try {
            int[] firstPx = this.paintedPixels.get(0);
            ForgeEnchantPacket packet = new ForgeEnchantPacket();
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
        final ForgeLayout L = computeLayout();
        if (L == null) return null;
        switch (idx) {
            case 0: return new int[]{ L.itemSlotX,    L.itemSlotY,    L.itemSlotW,    L.itemSlotH    };
            case 1: return new int[]{ L.crystalSlotX, L.crystalSlotY, L.crystalSlotW, L.crystalSlotH };
            case 2: return new int[]{ L.essenceSlotX, L.essenceSlotY, L.essenceSlotW, L.essenceSlotH };
            default: return null;
        }
    }

    /** Bind a dropped inventory slot to the matching forge slot; returns false so the
     *  caller can fall through to its normal swap logic. Only stages client state;
     *  the enchant fires when Forge is clicked. */
    public boolean tryAcceptDrop(int mx, int my, int srcSlotIdx, int crystalItemId, int crystalStatId) {
        if (!this.visible) return false;
        // Ground-loot slots (20..27) can't drop into the forge; the item must live in inventory first.
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

    /** Rarity-driven enchantment cap of the target item; MAX_ENCHANTMENTS when none is staged. */
    private int currentMaxEnchantments() {
        final GameItem target = inventoryItem(this.targetSlot);
        if (target == null) return MAX_ENCHANTMENTS;
        return target.getMaxEnchantments();
    }

    // Equip slots each gemstoneType may socket into. MUST mirror Gemstone.canSocketInto on the server.
    // 0=Weapon 1=Armor 2=Gauntlet 3=Boots 4=Ring.
    private static int[] gemSocketSlotsByType(int gemType) {
        switch (gemType) {
            case 1: case 2: case 3: case 4: case 5: case 7: return new int[]{0};
            case 6: return new int[]{1, 2, 3};
            case 8: case 9: case 10: case 11: case 12: case 13: case 14: case 15:
                return new int[]{0, 1, 2, 3, 4};
            default: return null; // unknown gem — defer to the server
        }
    }

    // Prefer the item's data-driven socketSlots; fall back to the per-type default.
    private static int[] resolveGemSlots(GameItem gem) {
        if (gem == null) return null;
        final List<Integer> data = gem.getSocketSlots();
        if (data != null && !data.isEmpty()) {
            final int[] out = new int[data.size()];
            for (int i = 0; i < data.size(); i++) out[i] = data.get(i);
            return out;
        }
        return gemSocketSlotsByType(gem.getGemstoneType());
    }

    private static boolean gemFitsSlot(int[] slots, int targetSlot) {
        if (slots == null) return true;
        for (int s : slots) if (s == targetSlot) return true;
        return false;
    }

    private static String gemAllowedSlotNames(int[] slots) {
        final String[] names = {"Weapon", "Armor", "Gauntlet", "Boots", "Ring"};
        if (slots == null) return "?";
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < slots.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(slots[i] >= 0 && slots[i] < names.length ? names[slots[i]] : ("slot " + slots[i]));
        }
        return sb.toString();
    }

    /** Web client's stat-id -> tint color. */
    private static Color statColor(int statId) {
        switch (statId) {
            case 0: return new Color(0.95f, 0.45f, 0.10f, 1f); // VIT
            case 1: return new Color(0.55f, 0.30f, 0.85f, 1f); // WIS
            case 2: return new Color(0.85f, 0.20f, 0.20f, 1f); // HP
            case 3: return new Color(0.20f, 0.40f, 0.95f, 1f); // MP
            case 4: return new Color(0.85f, 0.60f, 0.10f, 1f); // STR
            case 5: return new Color(0.55f, 0.55f, 0.65f, 1f); // DEF
            case 6: return new Color(0.20f, 0.85f, 0.45f, 1f); // SPD
            case 7: return new Color(0.95f, 0.85f, 0.30f, 1f); // DEX
            case GEM_PAINT_MARKER: return new Color(0.69f, 0.31f, 0.88f, 1f); // gem
            default: return Color.GRAY;
        }
    }

    private static int statColorPacked(int statId) {
        Color c = statColor(statId);
        return ((int)(c.r * 255) << 16) | ((int)(c.g * 255) << 8) | (int)(c.b * 255);
    }
}
