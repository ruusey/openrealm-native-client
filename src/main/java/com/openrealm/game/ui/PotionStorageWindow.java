package com.openrealm.game.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.openrealm.game.OpenRealmGame;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.data.GameSpriteManager;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.state.PlayState;
import com.openrealm.game.ui.atlas.UiAtlas;
import com.openrealm.game.ui.atlas.UiComponent;
import com.openrealm.net.entity.NetGameItem;
import com.openrealm.net.realm.RealmManagerClient;
import com.openrealm.net.server.packet.ItemStoreMovePacket;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Native-client UI for the per-player Potion Storage container (32 slots,
 * stackables + gems only). All layout is driven by the canonical UI atlas
 * (openrealm-data/.../ui/ui-components.json + Open_Realm_User_Interface_V1.png):
 *
 *   - Parent chrome:   panel.hud.chat.text_area (the user reuses the chat
 *                      textarea panel as the storage dialog background)
 *   - Two slot grids:  panel.hud.inv_only.grid rendered twice, side-by-side
 *                      (16 cells each = 32 total)
 *
 * displayScale=2 means every source-coord dimension is doubled when
 * rendered to screen. Cell positions come from UiAtlas.gridCells, with
 * source-relative offsets translated to dialog-relative screen offsets.
 *
 * Server flow (unchanged from initial draft):
 *   - F-key on tile 328 in vault -> InteractTilePacket -> server replies
 *     with OpenItemStorePacket carrying the 32-slot snapshot.
 *   - Drag-drop sends ItemStoreMovePacket; server validates whitelist
 *     (item.stackable || category=="gem"), bounds, and stack semantics.
 *   - ItemStoreUpdatePacket from server triggers refresh().
 */
@Slf4j
public class PotionStorageWindow {
    public static final int SIZE = 32;

    /** Horizontal gap between the two side-by-side grids inside the dialog. */
    private static final int GRID_GAP_PX = 12;

    private boolean visible = false;
    private final GameItem[] items = new GameItem[SIZE];
    /** Which store this window is showing; echoed in every move (ItemStoreKind.POTION = 0). */
    private byte storeKind = 0;

    @Setter private RealmManagerClient realmManager;
    @Setter private PlayState playState;

    /** Storage-side drag state. -1 = no drag. Drags from inventory go
     *  through PlayerUI.executeDrop -> tryAcceptDrop. */
    private int dragStorageIdx = -1;
    private boolean mouseDownPrev = false;

    public boolean isVisible() { return this.visible; }

    public void open(byte storeKind, NetGameItem[] netItems) {
        this.storeKind = storeKind;
        this.refresh(netItems);
        this.visible = true;
    }

    public void hide() {
        this.visible = false;
        this.dragStorageIdx = -1;
    }

    public void refresh(NetGameItem[] netItems) {
        for (int i = 0; i < SIZE; i++) {
            this.items[i] = (netItems != null && i < netItems.length) ? fromNet(netItems[i]) : null;
        }
    }

    public GameItem[] getItems() { return this.items; }

    public byte getStoreKind() { return this.storeKind; }

    private static GameItem fromNet(NetGameItem net) {
        // Empty slots arrive as `new NetGameItem()` with default fields:
        // itemId=0, uid="", name="". CANNOT key off itemId because Potion
        // of Defense is legitimately itemId 0 — uid is empty for empties
        // and randomly generated for real items, so it's the correct
        // discriminator here.
        if (net == null) return null;
        if (net.getUid() == null || net.getUid().isEmpty()) return null;
        final GameItem template = GameDataManager.GAME_ITEMS.get(net.getItemId());
        if (template == null) return null;
        final GameItem clone = template.clone();
        clone.setUid(net.getUid());
        if (net.getStackCount() > 0) clone.setStackCount(net.getStackCount());
        return clone;
    }

    public void update() {
        if (!this.visible) return;
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            this.hide();
            return;
        }
        // Storage-internal drag: press starts on a non-empty cell, release
        // routes the move. Inventory→storage drops are handled by PlayerUI's
        // executeDrop -> tryAcceptDrop, so we don't intercept those here.
        //
        // Guard: if PlayerUI is mid-inventory-drag (user is dragging an item
        // FROM the inventory bag), don't latch onto a storage cell that
        // happens to sit under the cursor. Without this guard, an inventory
        // drag whose first frame lands on a non-empty storage cell would
        // accidentally start a storage→storage drag at the same time.
        final boolean invDragActive = this.playState != null
                && this.playState.getPui() != null
                && this.playState.getPui().isDragging();
        final boolean down = Gdx.input.isButtonPressed(Input.Buttons.LEFT);
        // Close × hit-test on press: clicking the X in the top-right
        // corner hides the modal, matching the webclient's close button.
        if (down && !this.mouseDownPrev && this.dragStorageIdx < 0 && !invDragActive) {
            final PotionStorageLayout layoutForClose = computeLayout();
            if (layoutForClose != null) {
                final int[] xr = closeButtonRect(layoutForClose);
                final int mx = Gdx.input.getX();
                final int my = Gdx.input.getY();
                if (mx >= xr[0] && mx < xr[0] + xr[2]
                        && my >= xr[1] && my < xr[1] + xr[3]) {
                    this.hide();
                    this.mouseDownPrev = down;
                    return;
                }
            }
        }
        if (down && !this.mouseDownPrev && this.dragStorageIdx < 0 && !invDragActive) {
            final int hit = hitTestStorage(Gdx.input.getX(), Gdx.input.getY());
            if (hit >= 0 && this.items[hit] != null) {
                this.dragStorageIdx = hit;
            }
        } else if (!down && this.mouseDownPrev && this.dragStorageIdx >= 0) {
            final int mx = Gdx.input.getX();
            final int my = Gdx.input.getY();
            final int storageHit = hitTestStorage(mx, my);
            if (storageHit >= 0 && storageHit != this.dragStorageIdx) {
                sendMove(ItemStoreMovePacket.SIDE_STORAGE, this.dragStorageIdx,
                        ItemStoreMovePacket.SIDE_STORAGE, storageHit);
            } else if (storageHit < 0) {
                final int invHit = hitTestInventory(mx, my);
                if (invHit >= 0) {
                    sendMove(ItemStoreMovePacket.SIDE_STORAGE, this.dragStorageIdx,
                            ItemStoreMovePacket.SIDE_INV, invHit);
                }
            }
            this.dragStorageIdx = -1;
        }
        this.mouseDownPrev = down;
    }

    /** Inventory→storage drop hook called from PlayerUI.executeDrop. */
    public boolean tryAcceptDrop(int mx, int my, int fromIndex) {
        if (!this.visible) return false;
        final int hit = hitTestStorage(mx, my);
        if (hit < 0) return false;
        sendMove(ItemStoreMovePacket.SIDE_INV, fromIndex,
                ItemStoreMovePacket.SIDE_STORAGE, hit);
        return true;
    }

    private void sendMove(byte fromSide, int fromIdx, byte toSide, int toIdx) {
        if (this.realmManager == null || this.playState == null || this.playState.getPlayer() == null) return;
        try {
            final ItemStoreMovePacket p = new ItemStoreMovePacket(this.storeKind, fromSide, fromIdx, toSide, toIdx);
            this.realmManager.getClient().getOutboundPacketQueue().add(p);
        } catch (Exception e) {
            log.error("[ItemStore] Failed to send move: {}", e.getMessage());
        }
    }

    // ----------------------------------------------------------------------
    // Layout — entirely atlas-driven. All screen rects derive from UiAtlas
    // entries; nothing in this section uses hardcoded panel dimensions.
    // ----------------------------------------------------------------------

    private PotionStorageLayout computeLayout() {
        if (!UiAtlas.isReady()) return null;
        final UiComponent text = UiAtlas.componentOf("panel.hud.chat.text_area");
        final UiComponent grid = UiAtlas.componentOf("panel.hud.inv_only.grid");
        if (text == null || grid == null || !grid.isGrid()) return null;
        final int[][] cells = UiAtlas.gridCells("panel.hud.inv_only.grid");
        if (cells == null || cells.length < 16) return null;

        final int s = UiAtlas.getDisplayScale();

        // The two grids stand side-by-side. Grid w in screen px:
        final int gridW = grid.getW() * s;
        final int gridH = grid.getH() * s;

        // Dialog has to be at least wide enough for two grids + gap, and
        // at least tall enough for a header band + the grid. Take the max
        // of (text_area scaled) and (content needs) so the chrome stretches
        // when the source rect is smaller than the content.
        final int contentW = gridW * 2 + GRID_GAP_PX + 32;   // 16 px padding L/R
        final int headerH  = 28;
        final int contentH = headerH + gridH + 24;
        final int chromeW  = text.getW() * s;
        final int chromeH  = text.getH() * s;
        final int dialogW  = Math.max(contentW, chromeW);
        final int dialogH  = Math.max(contentH, chromeH);

        final PotionStorageLayout L = new PotionStorageLayout();
        L.s = s;
        L.dialogX = (OpenRealmGame.width  - dialogW) / 2;
        L.dialogY = (OpenRealmGame.height - dialogH) / 2;
        L.dialogW = dialogW;
        L.dialogH = dialogH;

        // Center the two grids horizontally inside the dialog, anchored
        // below the header band.
        final int gridsTotalW = gridW * 2 + GRID_GAP_PX;
        L.leftGridScreenX  = L.dialogX + (dialogW - gridsTotalW) / 2;
        L.rightGridScreenX = L.leftGridScreenX + gridW + GRID_GAP_PX;
        L.gridScreenY      = L.dialogY + headerH;

        // Grid source origin is the panel's own (x, y). Per-cell rects in
        // gridCells use these as their base — translated to screen below.
        L.gridSrcX_left = L.gridSrcX_right = grid.getX();
        L.gridSrcY_left = L.gridSrcY_right = grid.getY();
        L.cells = cells;
        L.gridDef = grid;
        return L;
    }

    /** Convert (slot 0..31) into a screen rect using the current frame's layout. */
    private int[] cellRectFor(int slot, PotionStorageLayout L) {
        if (L == null) return null;
        final int side = slot < 16 ? 0 : 1;
        final int local = slot < 16 ? slot : slot - 16;
        final int[] cell = L.cells[local];           // {srcX, srcY, w, h}
        final int gridScreenX = side == 0 ? L.leftGridScreenX : L.rightGridScreenX;
        final int srcOrigX    = side == 0 ? L.gridSrcX_left   : L.gridSrcX_right;
        final int srcOrigY    = side == 0 ? L.gridSrcY_left   : L.gridSrcY_right;
        final int cx = gridScreenX + (cell[0] - srcOrigX) * L.s;
        final int cy = L.gridScreenY + (cell[1] - srcOrigY) * L.s;
        return new int[] { cx, cy, cell[2] * L.s, cell[3] * L.s };
    }

    private int hitTestStorage(int mx, int my) {
        final PotionStorageLayout L = computeLayout();
        if (L == null) return -1;
        for (int i = 0; i < SIZE; i++) {
            final int[] r = cellRectFor(i, L);
            if (r == null) continue;
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) return i;
        }
        return -1;
    }

    /** Close-× button rect at the top-right of the header band. Sized to
     *  fit comfortably inside the 28-px header above the grids. Returns
     *  {x, y, w, h} in flipped-ortho screen coords. */
    private int[] closeButtonRect(PotionStorageLayout L) {
        final int w = 18;
        final int h = 18;
        final int x = L.dialogX + L.dialogW - w - 8;
        final int y = L.dialogY + 4;
        return new int[] { x, y, w, h };
    }

    /** Public cell rect lookup for tooltip / external hit-tests. Returns
     *  {x, y, w, h} for the given storage slot in flipped-ortho screen
     *  coords, or null when the modal is hidden / atlas not ready. */
    public int[] getCellRect(int slot) {
        if (!this.visible || slot < 0 || slot >= SIZE) return null;
        final PotionStorageLayout L = computeLayout();
        if (L == null) return null;
        return cellRectFor(slot, L);
    }

    private int hitTestInventory(int mx, int my) {
        if (this.playState == null || this.playState.getPui() == null) return -1;
        final Slots[] inv = this.playState.getPui().getInventory();
        if (inv == null) return -1;
        for (int i = 0; i < inv.length; i++) {
            final Slots s = inv[i];
            if (s == null || s.getButton() == null) continue;
            final Button btn = s.getButton();
            final float bx = btn.getPos().getX();
            final float by = btn.getPos().getY();
            if (mx >= bx && mx < bx + btn.getWidth()
                    && my >= by && my < by + btn.getHeight()) return i;
        }
        return -1;
    }

    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        if (!this.visible) return;
        final PotionStorageLayout L = computeLayout();
        if (L == null) {
            // Atlas not yet bound — show nothing so we don't paint stale
            // hardcoded rects on top of the HUD.
            return;
        }

        // Dimmed backdrop.
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, 0.65f);
        shapes.rect(0, 0, OpenRealmGame.width, OpenRealmGame.height);
        shapes.end();
        batch.begin();

        // panel.hud.chat.text_area chrome stretched to dialog dimensions.
        // The atlas region is already y-flipped by UiAtlas.region().
        final TextureRegion textArea = UiAtlas.region("panel.hud.chat.text_area");
        if (textArea != null) {
            batch.draw(textArea, L.dialogX, L.dialogY, L.dialogW, L.dialogH);
        } else {
            batch.end();
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0x18 / 255f, 0x14 / 255f, 0x1a / 255f, 0.97f);
            shapes.rect(L.dialogX, L.dialogY, L.dialogW, L.dialogH);
            shapes.end();
            batch.begin();
        }

        // Title in the header band — y=12 (was 20) so the baseline clears
        // the grids that sit at L.dialogY + 28. At y=20 the descenders
        // were ducking under the left grid's first cell.
        font.setColor(Color.WHITE);
        font.draw(batch, "POTION STORAGE", L.dialogX + 14, L.dialogY + 14);

        // Close × at top-right corner. Hit-tested in update() via
        // closeButtonRect(L). Rendered as a single character so font
        // scaling matches the surrounding header text.
        final int[] xr = closeButtonRect(L);
        font.draw(batch, "x", xr[0] + 4, L.dialogY + 14);

        // Render the two grids: blit panel.hud.inv_only.grid chrome under
        // each, then per-cell content (item sprite + stack badge).
        final TextureRegion gridChrome = UiAtlas.region("panel.hud.inv_only.grid");
        if (gridChrome != null) {
            final int gw = L.gridDef.getW() * L.s;
            final int gh = L.gridDef.getH() * L.s;
            batch.draw(gridChrome, L.leftGridScreenX,  L.gridScreenY, gw, gh);
            batch.draw(gridChrome, L.rightGridScreenX, L.gridScreenY, gw, gh);
        }

        // Per-cell content + drag-source dimming.
        for (int i = 0; i < SIZE; i++) {
            final int[] r = cellRectFor(i, L);
            if (r == null) continue;
            final GameItem it = this.items[i];
            if (it == null) continue;
            final TextureRegion sprite = GameSpriteManager.ITEM_SPRITES != null
                    ? GameSpriteManager.ITEM_SPRITES.get(it.getItemId()) : null;
            if (sprite != null) {
                final int pad = Math.max(2, r[2] / 8);
                final boolean dimmed = (i == this.dragStorageIdx);
                if (dimmed) batch.setColor(1f, 1f, 1f, 0.35f);
                batch.draw(sprite, r[0] + pad, r[1] + pad, r[2] - 2 * pad, r[3] - 2 * pad);
                if (dimmed) batch.setColor(Color.WHITE);
            }
            if (it.isStackable() && it.getStackCount() > 1) {
                font.setColor(1f, 0.85f, 0.42f, 1f);
                font.draw(batch, "x" + it.getStackCount(), r[0] + r[2] - 22, r[1] + r[3] - 4);
                font.setColor(Color.WHITE);
            }
        }

        // Drag preview attached to the cursor.
        if (this.dragStorageIdx >= 0 && this.items[this.dragStorageIdx] != null) {
            final TextureRegion sprite = GameSpriteManager.ITEM_SPRITES != null
                    ? GameSpriteManager.ITEM_SPRITES.get(this.items[this.dragStorageIdx].getItemId()) : null;
            if (sprite != null) {
                batch.draw(sprite, Gdx.input.getX() - 16, Gdx.input.getY() - 16, 32, 32);
            }
        }
    }
}
