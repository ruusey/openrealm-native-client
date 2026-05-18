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

/**
 * Pixel-painting forge UI, mirroring the web client's enchant flow.
 *
 * Server flow:
 *   1. Player walks onto a forge tile -> server sends {@code OpenForgePacket}.
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
    // Upper sanity bound. The actual cap shown on screen and enforced when
    // painting is rarity-driven (currentMaxEnchantments()) — Common = 1,
    // Mythical = 6. Mirrors webclient slotsForItem() in forge.js.
    public static final int MAX_ENCHANTMENTS = 6;

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
    /** Stat id encoded by the selected crystal (0=VIT 1=WIS 2=HP 3=MP 4=STR 5=DEF 6=SPD 7=DEX). */
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

    /** Atlas-driven per-frame layout. Single source of truth shared by
     *  render() + handleClick() + slotRect() so they can never disagree.
     *  All rects are flipped-ortho screen pixels, derived by translating
     *  each child panel's atlas (x,y) into the container's local coord
     *  space and multiplying by displayScale. The pixel canvas is sized
     *  to fit panel.forge.output exactly so painted pixels land in the
     *  same on-screen rect the user annotated. */
    private static final class Layout {
        int s;                                    // displayScale
        // Container chrome (panel.forge.container)
        int containerX, containerY, containerW, containerH;
        // Status bar (panel.forge.status) — also hosts the action buttons.
        int statusX, statusY, statusW, statusH;
        // Three button rects packed inside the status bar.
        int btnForgeX, btnRemoveX, btnCancelX, btnY, btnW, btnH;
        // Input slot rects (panel.forge.input.{item,crystal,essence}).
        int itemSlotX, itemSlotY, itemSlotW, itemSlotH;
        int crystalSlotX, crystalSlotY, crystalSlotW, crystalSlotH;
        int essenceSlotX, essenceSlotY, essenceSlotW, essenceSlotH;
        // Label rects (panel.forge.label.*).
        int labelItemX, labelItemY, labelItemW, labelItemH;
        int labelCrystalX, labelCrystalY, labelCrystalW, labelCrystalH;
        int labelEssenceX, labelEssenceY, labelEssenceW, labelEssenceH;
        // Output region (panel.forge.output) and the painted canvas inside.
        int outputX, outputY, outputW, outputH;
        int canvasX, canvasY, canvasSize;
    }

    /** Modal-only scale multiplier on top of UiAtlas.getDisplayScale().
     *  The atlas was authored at displayScale=2 to fit comfortably as
     *  in-game HUD chrome, but the forge dialog is a focused modal that
     *  needs more screen real-estate for buttons + paint canvas. The
     *  webclient lifts itself the same way via `--forge-scale: 2` on
     *  #forge-panel. Match that here so both clients render at the same
     *  effective size (4x the source atlas). */
    private static final int MODAL_SCALE = 2;

    /** Build a Layout from the atlas. Returns null when the atlas isn't
     *  ready — callers should bail without drawing/handling clicks so we
     *  never paint stale hardcoded geometry on top of the HUD. */
    private Layout computeLayout() {
        if (!UiAtlas.isReady()) return null;
        final UiComponent cont   = UiAtlas.componentOf("panel.forge.container");
        final UiComponent status = UiAtlas.componentOf("panel.forge.status");
        final UiComponent inItem = UiAtlas.componentOf("panel.forge.input.item");
        final UiComponent inCry  = UiAtlas.componentOf("panel.forge.input.crystal");
        final UiComponent inEss  = UiAtlas.componentOf("panel.forge.input.essence");
        final UiComponent lbItem = UiAtlas.componentOf("panel.forge.label.item");
        // Note: canonical ui-components.json has the typo 'cyrstal' on the
        // LABEL only (input.crystal is correctly spelled). Honor the typo
        // verbatim — re-spelling here would 404 the lookup.
        final UiComponent lbCry  = UiAtlas.componentOf("panel.forge.label.cyrstal");
        final UiComponent lbEss  = UiAtlas.componentOf("panel.forge.label.essence");
        final UiComponent output = UiAtlas.componentOf("panel.forge.output");
        if (cont == null || status == null || inItem == null || inCry == null
                || inEss == null || lbItem == null || lbCry == null
                || lbEss == null || output == null) return null;

        final int s = UiAtlas.getDisplayScale() * MODAL_SCALE;
        final Layout L = new Layout();
        L.s = s;
        L.containerW = cont.getW() * s;
        L.containerH = cont.getH() * s;
        L.containerX = (OpenRealmGame.width  - L.containerW) / 2;
        L.containerY = (OpenRealmGame.height - L.containerH) / 2;

        // Translate any atlas component to screen coords by:
        //   screen = containerOrigin + (compSrc - containerSrc) * displayScale
        // This keeps the rendered layout pixel-identical to the user's
        // annotation no matter where the dialog is centered on screen.
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

        // Action buttons live inside panel.forge.status (per user spec:
        // "you can put the existing remove all, forge and cancel buttons
        //  on the top component within the panel.forge.container called
        //  panel.forge.status. ALl of the aciton buttons can go there").
        // Lay them out as three equal-width regions inside the status bar.
        // Vertical: occupy almost the full status height with 1px padding.
        L.btnH = Math.max(14, L.statusH - 2);
        L.btnY = L.statusY + (L.statusH - L.btnH) / 2;
        // Reserve a sliver on the right of the status bar for a close ×
        // affordance — kept implicit (the ESC / Cancel button is the
        // primary close path), so all three buttons share the bar width.
        L.btnW = (L.statusW - 6) / 3;
        L.btnForgeX  = L.statusX + 2;
        L.btnRemoveX = L.btnForgeX + L.btnW + 1;
        L.btnCancelX = L.btnRemoveX + L.btnW + 1;

        // Square paint canvas inscribed in the output region. Sprite-size
        // grid math (gridDim) is computed at render time from the bound
        // target item; we just precompute the canvas screen rect here so
        // click hit-tests share the exact same pixel rect.
        L.canvasSize = Math.min(L.outputW, L.outputH);
        L.canvasX = L.outputX + (L.outputW - L.canvasSize) / 2;
        L.canvasY = L.outputY + (L.outputH - L.canvasSize) / 2;
        return L;
    }

    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        if (!this.visible) return;
        final Layout L = computeLayout();
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

        // ------------------------------------------------------------------
        // Atlas chrome — every panel from the user's annotated sprite sheet.
        // Order matters: container first (background), then status overlay,
        // then label/input/output regions on top.
        // ------------------------------------------------------------------
        blitAtlas(batch, "panel.forge.container", L.containerX, L.containerY, L.containerW, L.containerH);
        blitAtlas(batch, "panel.forge.status",    L.statusX,    L.statusY,    L.statusW,    L.statusH);
        blitAtlas(batch, "panel.forge.label.item",    L.labelItemX,    L.labelItemY,    L.labelItemW,    L.labelItemH);
        blitAtlas(batch, "panel.forge.label.cyrstal", L.labelCrystalX, L.labelCrystalY, L.labelCrystalW, L.labelCrystalH);
        blitAtlas(batch, "panel.forge.label.essence", L.labelEssenceX, L.labelEssenceY, L.labelEssenceW, L.labelEssenceH);
        blitAtlas(batch, "panel.forge.input.item",    L.itemSlotX,    L.itemSlotY,    L.itemSlotW,    L.itemSlotH);
        blitAtlas(batch, "panel.forge.input.crystal", L.crystalSlotX, L.crystalSlotY, L.crystalSlotW, L.crystalSlotH);
        blitAtlas(batch, "panel.forge.input.essence", L.essenceSlotX, L.essenceSlotY, L.essenceSlotW, L.essenceSlotH);
        blitAtlas(batch, "panel.forge.output",        L.outputX,      L.outputY,      L.outputW,      L.outputH);

        // ------------------------------------------------------------------
        // Action buttons inside panel.forge.status — flat-fill rects via
        // ShapeRenderer because the atlas itself doesn't carry button art
        // for them. Hit-tests in handleClick() share these same rects.
        // ------------------------------------------------------------------
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        drawButtonFill(shapes, L.btnForgeX,  L.btnY, L.btnW, L.btnH);
        drawButtonFill(shapes, L.btnRemoveX, L.btnY, L.btnW, L.btnH);
        drawButtonFill(shapes, L.btnCancelX, L.btnY, L.btnW, L.btnH);
        shapes.end();
        batch.begin();

        // ------------------------------------------------------------------
        // Slot contents — item sprites for whatever the player has bound.
        // ------------------------------------------------------------------
        final GameItem targetItem  = inventoryItem(this.targetSlot);
        final GameItem crystalItem = inventoryItem(this.crystalSlot);
        final GameItem essenceItem = inventoryItem(this.essenceSlot);
        drawItemCentered(batch, targetItem,  L.itemSlotX,    L.itemSlotY,    L.itemSlotW,    L.itemSlotH);
        drawItemCentered(batch, crystalItem, L.crystalSlotX, L.crystalSlotY, L.crystalSlotW, L.crystalSlotH);
        drawItemCentered(batch, essenceItem, L.essenceSlotX, L.essenceSlotY, L.essenceSlotW, L.essenceSlotH);

        // ------------------------------------------------------------------
        // Pixel canvas — square inscribed in panel.forge.output. The grid
        // dimension follows the bound target item's spriteSize so painted
        // pixels land on the SAME source pixel the server will store.
        // ------------------------------------------------------------------
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
        // Faint grid overlay so the user can see which sprite pixel
        // they're about to click on.
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

        // ------------------------------------------------------------------
        // Text overlays — title, button labels, slot labels, status line.
        // ------------------------------------------------------------------
        font.setColor(Color.WHITE);
        // Status bar title sits at the LEFT of the bar, before the buttons
        // would normally start — but we packed buttons across the entire
        // bar, so the title goes ABOVE the dialog, drawn small inside the
        // status panel near the very top edge.
        font.getData().setScale(0.85f);
        font.draw(batch, "Forge",      L.btnForgeX  + 6, L.btnY + L.btnH - 6);
        font.draw(batch, "Remove All", L.btnRemoveX + 6, L.btnY + L.btnH - 6);
        font.draw(batch, "Cancel",     L.btnCancelX + 6, L.btnY + L.btnH - 6);
        font.getData().setScale(1f);

        // Slot labels — text overlay drawn on top of the panel.forge.label.*
        // chrome so the user can read which slot is which.
        font.setColor(0.95f, 0.85f, 0.55f, 1f);
        font.getData().setScale(0.7f);
        font.draw(batch, "Item",    L.labelItemX    + 2, L.labelItemY    + L.labelItemH    - 4);
        font.draw(batch, "Crystal", L.labelCrystalX + 2, L.labelCrystalY + L.labelCrystalH - 4);
        font.draw(batch, "Essence", L.labelEssenceX + 2, L.labelEssenceY + L.labelEssenceH - 4);

        // Empty-slot hint — only when the slot is empty.
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
        font.setColor(Color.WHITE);
    }

    /** Blit a UiAtlas region at the given screen rect, falling back to
     *  a flat-fill placeholder if the region isn't bound (atlas missing
     *  for that id). */
    private static void blitAtlas(SpriteBatch batch, String id, int x, int y, int w, int h) {
        final TextureRegion r = UiAtlas.region(id);
        if (r != null) {
            batch.draw(r, x, y, w, h);
        }
    }

    /** Solid-fill button background. Kept private + uniform so all three
     *  status-bar buttons read identical until/unless the atlas grows
     *  dedicated button regions. */
    private static void drawButtonFill(ShapeRenderer shapes, int x, int y, int w, int h) {
        shapes.setColor(0.18f, 0.18f, 0.22f, 1f);
        shapes.rect(x, y, w, h);
    }

    /** Center an item sprite inside an arbitrary rect with a small inset.
     *  Slot dimensions vary because they come straight from the atlas. */
    private static void drawItemCentered(SpriteBatch batch, GameItem item, int x, int y, int w, int h) {
        if (item == null) return;
        if (GameSpriteManager.ITEM_SPRITES == null) return;
        final TextureRegion tr = GameSpriteManager.ITEM_SPRITES.get(item.getItemId());
        if (tr == null) return;
        final int pad = Math.max(2, Math.min(w, h) / 8);
        batch.draw(tr, x + pad, y + pad, w - 2 * pad, h - 2 * pad);
    }

    private void handleClick(int mx, int my) {
        final Layout L = computeLayout();
        if (L == null) return;

        // ------------------------------------------------------------------
        // Action buttons inside panel.forge.status. Hit-rects come straight
        // from the cached Layout so render() and click stay in lockstep.
        // ------------------------------------------------------------------
        if (my >= L.btnY && my < L.btnY + L.btnH) {
            if (mx >= L.btnForgeX  && mx < L.btnForgeX  + L.btnW) { this.sendForge();      return; }
            if (mx >= L.btnRemoveX && mx < L.btnRemoveX + L.btnW) { this.sendDisenchant(); return; }
            if (mx >= L.btnCancelX && mx < L.btnCancelX + L.btnW) { this.hide();           return; }
        }

        // ------------------------------------------------------------------
        // Pixel canvas click -> paint. The canvas rect is the inscribed
        // square inside panel.forge.output; gridDim mirrors the value
        // render() stamped into activeGridDim so click→pixel maps to the
        // exact source-sprite coord the player saw on screen.
        // ------------------------------------------------------------------
        if (mx >= L.canvasX && mx < L.canvasX + L.canvasSize
                && my >= L.canvasY && my < L.canvasY + L.canvasSize) {
            final int gd = Math.max(1, this.activeGridDim);
            final float cellPx = (float) L.canvasSize / (float) gd;
            int gx = (int) ((mx - L.canvasX) / cellPx);
            int gy = (int) ((my - L.canvasY) / cellPx);
            if (gx < 0) gx = 0; if (gx >= gd) gx = gd - 1;
            if (gy < 0) gy = 0; if (gy >= gd) gy = gd - 1;
            if (this.crystalStatId < 0) {
                log.info("[FORGE] Cannot paint — no crystal selected");
                return;
            }
            final int cap = currentMaxEnchantments();
            if (this.existingEnchantments.size() + this.paintedPixels.size() >= cap) {
                log.info("[FORGE] Item already has the maximum {} enchantments", cap);
                return;
            }
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
        final Layout L = computeLayout();
        if (L == null) return null;
        switch (idx) {
            case 0: return new int[]{ L.itemSlotX,    L.itemSlotY,    L.itemSlotW,    L.itemSlotH    };
            case 1: return new int[]{ L.crystalSlotX, L.crystalSlotY, L.crystalSlotW, L.crystalSlotH };
            case 2: return new int[]{ L.essenceSlotX, L.essenceSlotY, L.essenceSlotW, L.essenceSlotH };
            default: return null;
        }
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

    /** Rarity-driven enchantment cap of the item currently in the target slot.
     *  Falls back to MAX_ENCHANTMENTS when no item is staged so the counter
     *  still renders something sensible. Mirrors slotsForItem() in forge.js. */
    private int currentMaxEnchantments() {
        final GameItem target = inventoryItem(this.targetSlot);
        if (target == null) return MAX_ENCHANTMENTS;
        return target.getMaxEnchantments();
    }

    /** Web client's stat-id -> tint color. */
    private static Color statColor(int statId) {
        switch (statId) {
            case 0: return new Color(0.95f, 0.45f, 0.10f, 1f); // VIT — orange
            case 1: return new Color(0.55f, 0.30f, 0.85f, 1f); // WIS — purple
            case 2: return new Color(0.85f, 0.20f, 0.20f, 1f); // HP  — red
            case 3: return new Color(0.20f, 0.40f, 0.95f, 1f); // MP  — blue
            case 4: return new Color(0.85f, 0.60f, 0.10f, 1f); // STR — gold
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
