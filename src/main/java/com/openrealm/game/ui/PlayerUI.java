package com.openrealm.game.ui;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.openrealm.game.OpenRealmGame;
import com.openrealm.game.contants.CharacterClass;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.data.GameSpriteManager;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.Stats;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.ItemTooltip;
import com.openrealm.game.state.PlayState;
import com.openrealm.game.state.RealmTransitionState;
import com.openrealm.net.client.packet.UpdatePlayerTradeSelectionPacket;
import com.openrealm.net.entity.NetInventorySelection;
import com.openrealm.net.entity.NetTradeSelection;
import com.openrealm.net.messaging.CommandType;
import com.openrealm.net.messaging.ServerCommandMessage;
import com.openrealm.net.server.packet.CommandPacket;
import com.openrealm.net.server.packet.TextPacket;
import com.openrealm.util.KeyHandler;
import com.openrealm.util.MouseHandler;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.openrealm.game.entity.Portal;
import com.openrealm.game.model.PortalModel;
import com.openrealm.game.ui.atlas.UiAtlas;
import com.openrealm.game.ui.atlas.UiComponent;
import com.openrealm.game.graphics.SpriteRecolorCache;

@Data
@Slf4j
public class PlayerUI {
    private boolean isTrading;
    private FillBars hp;
    private FillBars mp;
    private FillBars xp;

    private Slots[] inventory;
    private Slots[] groundLoot;

    private PlayState playState;
    private PlayerChat playerChat;
    private Minimap minimap;
    private long lastAction = Instant.now().toEpochMilli();
    private Button menuButton = null;

    private NetTradeSelection currentTradeSelection = null;
    private String tradePartnerName = null;
    private Button confirmTradeButton = null;
    private Button cancelTradeButton = null;

    private Map<Integer, TextureRegion> classIconCache = new HashMap<>();
    private List<Button> nearbyPlayerButtons = new ArrayList<>();
    private List<Player> nearbyPlayerList = new ArrayList<>();
    private Player hoveredPlayer = null;
    private long lastNearbyRefresh = 0;

    // Click-context menu shown next to a nearby-player entry. Mirrors
    // webclient trade.js showPlayerContextMenu — Trade / Teleport options
    // wired to the same SERVER_COMMAND payloads (/trade <name>, /tp <name>)
    // the chat box uses. Set on player-name click; cleared on menu-button
    // click or click-elsewhere.
    private Player contextMenuPlayer = null;
    private int contextMenuX = 0;
    private int contextMenuY = 0;
    private boolean prevContextMenuMouseDown = false;

    private int dragSourceIndex = -1;
    private boolean isDragging = false;
    private Vector2f dragStartPos = null;
    private static final float DRAG_THRESHOLD = 8.0f;
    private ItemTooltip activeTooltip = null;
    /**
     * Currently visible bag tab. 0 = BAG 1 (inventory slots 4–11),
     * 1 = BAG 2 (inventory slots 12–19). Mirrors the web client's
     * tab-switched bag layout — only one bag is on screen at a time so
     * the panel stays compact.
     */
    private int activeBag = 0;

    // Web-parity UIs. These live on PlayerUI so packet handlers and the input
    // loop can reach them via realmManager.state.pui.<name>.
    private final ForgeWindow forgeWindow = new ForgeWindow();
    private final FameStoreWindow fameStoreWindow = new FameStoreWindow();
    private final OptionsWindow optionsWindow = new OptionsWindow();
    private final RealmTransitionState realmTransition = new RealmTransitionState();

    // Right-sidebar layout, mirroring the webclient #hud column order:
    //   [name+lvl header] [fame badge] [square minimap] [HP/MP/XP bars]
    //   [stats grid] [Equipment label + 4 slots] [Bag tabs + 8 slots]
    //   [Potion row] [Players Nearby]
    // Constants below are referenced by both render() and the slot/click
    // hit-test helpers so visual + hit bounds stay in sync.
    private static final int PANEL_INSET     = 8;
    private static final int SLOT_SIZE       = 56;
    private static final int SLOT_GAP        = 4;
    private static final int HEADER_Y        = 16;   // name + level baseline
    private static final int FAME_Y          = 28;   // fame badge top
    private static final int FAME_H          = 24;
    // Anchors recomputed per frame from these
    private int layoutMinimapY  = 60;
    private int layoutMinimapBot = 260;
    private int layoutBarsY     = 264;
    private int layoutStatsY    = 348;
    private int layoutEquipY    = 408;
    private int layoutBagTabY   = 478;
    private int layoutBag1Y     = 506;
    private int layoutBag2Y     = 506 + SLOT_SIZE + SLOT_GAP;
    private int layoutPotionY   = 506 + (SLOT_SIZE + SLOT_GAP) * 2 + 8;
    private int layoutNearbyY   = 506 + (SLOT_SIZE + SLOT_GAP) * 2 + 8 + SLOT_SIZE + 16;

    public PlayerUI(PlayState p) {
        int panelWidth = OpenRealmGame.width / 5;
        int startX = OpenRealmGame.width - panelWidth;
        int barHeight = 24;
        int barY = 32;

        this.isTrading = false;
        this.playState = p;
        this.hp = new FillBars(p.getPlayer(), new Vector2f(startX, barY),
                panelWidth, barHeight, "getHealthPercent", Color.DARK_GRAY, Color.RED);
        this.mp = new FillBars(p.getPlayer(), new Vector2f(startX, barY + barHeight),
                panelWidth, barHeight, "getManaPercent", Color.DARK_GRAY, Color.BLUE);
        this.xp = new FillBars(p.getPlayer(), new Vector2f(startX, barY + barHeight * 2),
                panelWidth, barHeight, "getExperiencePercent", Color.DARK_GRAY, new Color(1.0f, 0.5f, 0.0f, 1.0f));
        this.groundLoot = new Slots[8];
        this.inventory = new Slots[20];
        this.playerChat = new PlayerChat(p);
        this.minimap = new Minimap(p);
    }

    /**
     * Recompute the right-sidebar Y anchors based on the current window size.
     * The webclient layout (name -> fame -> minimap -> bars -> stats -> equip ->
     * bags -> potions -> nearby) collapses gracefully on shorter windows; we
     * mirror that by deriving everything from the panel width / window height.
     */
    private void recomputeLayout() {
        final int panelW = OpenRealmGame.width / 5;
        final int minimapSize = Math.max(96, Math.min(panelW - 2 * PANEL_INSET,
                OpenRealmGame.height / 4));
        this.layoutMinimapY = FAME_Y + FAME_H + 6;
        this.layoutMinimapBot = this.layoutMinimapY + minimapSize;
        this.layoutBarsY    = this.layoutMinimapBot + 6;          // 3 bars * 22 = 66
        this.layoutStatsY   = this.layoutBarsY + 22 * 3 + 8;      // 3-row stats
        this.layoutEquipY   = this.layoutStatsY + 22 * 3 + 14;
        this.layoutBagTabY  = this.layoutEquipY + SLOT_SIZE + 18;
        this.layoutBag1Y    = this.layoutBagTabY + 24 + 4;
        this.layoutBag2Y    = this.layoutBag1Y + SLOT_SIZE + SLOT_GAP;
        this.layoutPotionY  = this.layoutBag2Y + SLOT_SIZE + 10;
        this.layoutNearbyY  = this.layoutPotionY + 56 + 14;
    }

    /** Screen X for slot column 0..3 — divides the full usable HUD width
     *  into 4 equal cells and centers the slot inside each cell, so the
     *  4 slots span the panel with even gaps instead of clustering at
     *  the left. Mirrors webclient #equipment-row CSS which uses
     *  display:flex / justify-content:space-between. */
    private int slotX(int col) {
        final int panelW = OpenRealmGame.width / 5;
        final int startX = OpenRealmGame.width - panelW;
        final int rowW = 4 * SLOT_SIZE + 3 * SLOT_GAP;
        final int rowStart = startX + (panelW - rowW) / 2;
        return rowStart + col * (SLOT_SIZE + SLOT_GAP);
    }

    private int groundLootRowY(int row) {
        // Ground loot anchors to the bottom of the screen so it doesn't fight
        // the rest of the (taller) sidebar layout. Two rows of four 56 px slots.
        final int bottom = OpenRealmGame.height - 16;
        return bottom - (2 - row) * (SLOT_SIZE + SLOT_GAP);
    }

    public Slots getSlot(int slot) {
        return this.inventory[slot];
    }

    public Slots[] getSlots(int start, int end) {
        int size = end - start;
        int idx = 0;
        Slots[] items = new Slots[size];
        for (int i = start; i < end; i++) {
            items[idx++] = this.inventory[i];
        }
        return items;
    }

    public int firstNullIdx(GameItem[] objs) {
        for (int i = 0; i < objs.length; i++) {
            if (objs[i] == null || objs[i].getItemId() == -1)
                return i;
        }
        return -1;
    }

    public void enqueueChat(final TextPacket packet) {
        this.playerChat.addChatMessage(packet);
    }

    public void setEquipment(GameItem[] loot) {
        this.inventory = new Slots[20];

        // Load all 20 slots (4 equipment + 16 inventory). Previously truncated
        // to slots 4..11 because the legacy HUD uses bag tabs to view 4..11
        // and 12..19 separately; the new sprite HUD shows all 16 in one 4×4
        // grid (panel.hud.main.grid). Bounds-check loot.length for older
        // servers that send a shorter payload.
        final int equipEnd = Math.min(4, loot.length);
        final int invEnd   = Math.min(20, loot.length);
        GameItem[] equipmentArr = Arrays.copyOfRange(loot, 0, equipEnd);
        GameItem[] inventoryArr = invEnd > 4 ? Arrays.copyOfRange(loot, 4, invEnd) : new GameItem[0];

        this.buildEquipmentSlots(equipmentArr);
        this.buildInventorySlots(inventoryArr);
    }

    public void setGroundLoot(GameItem[] loot) {
        if (this.isTrading && this.currentTradeSelection != null) {
            loot = this.getOtherPlayerSelectedItems();
        }
        this.groundLoot = new Slots[8];
        for (int i = 0; i < loot.length; i++) {
            GameItem item = loot[i];
            if (item == null || item.getItemId() == -1) continue;
            this.buildGroundLootSlotButton(i, item);
        }
    }

    /**
     * Get the other player's NetInventorySelection (not filtered).
     */
    private NetInventorySelection getOtherPlayerSelection() {
        if (this.currentTradeSelection == null) return null;

        long myId = this.playState.getPlayerId();
        if (this.currentTradeSelection.getPlayer0Selection() != null
                && this.currentTradeSelection.getPlayer0Selection().getPlayerId() == myId) {
            return this.currentTradeSelection.getPlayer1Selection();
        } else {
            return this.currentTradeSelection.getPlayer0Selection();
        }
    }

    /**
     * Get the items that the OTHER player has selected for trade.
     * Determines which selection is "other" based on local player ID.
     */
    private GameItem[] getOtherPlayerSelectedItems() {
        NetInventorySelection otherSelection = this.getOtherPlayerSelection();
        if (otherSelection == null || otherSelection.getItemRefs() == null) {
            return new GameItem[8];
        }

        GameItem[] allItems = otherSelection.getGameItems();
        Boolean[] selection = otherSelection.getSelection();
        if (selection == null) return new GameItem[8];

        GameItem[] selectedItems = new GameItem[8];
        int idx = 0;
        for (int i = 0; i < selection.length && i < allItems.length; i++) {
            if (selection[i] != null && selection[i] && allItems[i] != null) {
                if (idx < 8) {
                    selectedItems[idx++] = allItems[i];
                }
            }
        }
        return selectedItems;
    }

    public void clearTradeSelections() {
        Slots[] invSlots = this.getSlots(4, 12);
        for (Slots slot : invSlots) {
            if (slot != null) {
                slot.setSelected(false);
            }
        }
        this.confirmTradeButton = null;
        this.cancelTradeButton = null;
    }

    public int getNonEmptySlotCount() {
        int count = 0;
        for (Slots s : this.getGroundLoot()) {
            if (s != null && s.getItem() != null) {
                count++;
            }
        }
        return count;
    }

    private void buildGroundLootSlotButton(int index, GameItem item) {
        this.recomputeLayout();
        if (item != null) {
            final int actualIdx = index;
            final int row = (index > 3) ? 1 : 0;
            final int col = (index > 3) ? index - 4 : index;
            final int x = this.slotX(col);
            final int y = this.groundLootRowY(row);
            Button b = new Button(new Vector2f(x, y), SLOT_SIZE);

            b.onMouseUp(event -> {
                // Don't allow picking up items from ground loot area during trade
                if (this.isTrading) return;
                if (this.isDragging) return;
                this.activeTooltip = null;
                if (this.canSwap()) {
                    this.setActionTime();
                    // HP/MP potions route into the potion counter slots on the
                    // server (Player.HP_POTION_ITEM_ID / MP_POTION_ITEM_ID) and
                    // never occupy a regular inventory slot — so the
                    // "first-null inventory slot" precheck below would
                    // incorrectly block pickup whenever the inventory was
                    // full. Send the move directly with a sentinel target.
                    final GameItem groundItem = item;
                    if (groundItem != null
                            && (groundItem.getItemId() == com.openrealm.game.entity.Player.HP_POTION_ITEM_ID
                             || groundItem.getItemId() == com.openrealm.game.entity.Player.MP_POTION_ITEM_ID)) {
                        try {
                            // targetSlot == 0 is harmless here; the server's
                            // ground-loot branch reads only fromIdx and the
                            // pot itemId before routing to addHp/MpPotion.
                            this.playState.getRealmManager().moveItem((byte) 4, actualIdx + 20, false, false);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        return;
                    }
                    GameItem[] currentInv = this.playState.getPlayer().getSlots(4, 12);
                    int idx = this.firstNullIdx(currentInv);
                    Slots currentEquip = this.inventory[idx + 4];

                    if ((currentEquip == null) && (idx > -1)) {
                        try {
                            this.playState.getRealmManager().moveItem(idx + 4, actualIdx + 20, false, false);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            });
            this.groundLoot[actualIdx] = new Slots(b, item);
        }
    }

    private void buildEquipmentSlots(GameItem[] equipment) {
        for (int i = 0; i < equipment.length; i++) {
            GameItem item = equipment[i];
            if (item == null || item.getItemId() == -1) continue;
            this.buildEquipmentSlotButton(i, item);
        }
    }

    private void buildEquipmentSlotButton(int idx, GameItem item) {
        this.recomputeLayout();
        if (item != null) {
            int actualIdx = (int) item.getTargetSlot();
            if (actualIdx == -1) {
                actualIdx = idx;
            }
            Button b = new Button(new Vector2f(this.slotX(actualIdx), this.layoutEquipY), SLOT_SIZE);

            b.onRightClick(event -> {
                // Don't allow equipment swaps during trade
                if (this.isTrading) return;
                Slots dropped = this.getOverlapping(event);
                if ((dropped != null) && this.canSwap()) {
                    this.setActionTime();
                    int dropIndex = this.getOverlapIdx(event);
                    this.playState.getRealmManager().moveItem(-1, dropIndex, true, false);
                }
            });

            this.inventory[actualIdx] = new Slots(b, item);
        }
    }

    private void buildInventorySlots(GameItem[] inventory) {
        for (int i = 0; i < (inventory.length); i++) {
            GameItem item = inventory[i];
            if (item == null || item.getItemId() == -1) continue;
            this.buildInventorySlotsButton(i, item);
        }
    }

    private void buildInventorySlotsButton(int index, GameItem item) {
        this.recomputeLayout();
        final int inventoryOffset = 4;

        if (item != null) {
            final int actualIdx = index + inventoryOffset;
            final int col = (index > 3) ? index - 4 : index;
            final int y = (index > 3) ? this.layoutBag2Y : this.layoutBag1Y;
            Button b = new Button(new Vector2f(this.slotX(col), y), SLOT_SIZE);

            b.onRightClick(event -> {
                if (this.isTrading) {
                    Slots dropped = this.getOverlapping(event);
                    if ((dropped != null)) {
                        dropped.setSelected(!dropped.isSelected());
                        final UpdatePlayerTradeSelectionPacket updatedTrade = UpdatePlayerTradeSelectionPacket
                                .fromSelection(this.getPlayState().getPlayer(), this);
                        try {
                            this.playState.getRealmManager().getClient().sendRemote(updatedTrade);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                } else {
                    Slots dropped = this.getOverlapping(event);
                    if ((dropped != null) && this.canSwap()) {
                        this.setActionTime();
                        int idx = this.getOverlapIdx(event);
                        this.playState.getRealmManager().moveItem(-1, idx, true, false);
                    }
                }
            });

            this.inventory[actualIdx] = new Slots(b, item);
        }
    }

    private int getOverlapIdx(Vector2f pos) {
        Slots[] equipSlots = this.getSlots(4, 12);
        int returnIdx = -1;
        for (int i = 0; i < equipSlots.length; i++) {
            Slots s = equipSlots[i];
            if ((s == null) || (s.getButton() == null)) continue;
            if (s.getButton().getBounds().inside((int) pos.x, (int) pos.y)) {
                returnIdx = i;
            }
        }
        return returnIdx + 4;
    }

    private Slots getOverlapping(Vector2f pos) {
        Slots[] equipSlots = this.getSlots(0, 12);
        for (Slots s : equipSlots) {
            if ((s == null) || (s.getButton() == null)) continue;
            if (s.getButton().getBounds().inside((int) pos.x, (int) pos.y))
                return s;
        }
        return null;
    }

    public void update(double time) {
        for (int i = 0; i < this.inventory.length; i++) {
            Slots curr = this.inventory[i];
            if (curr != null) {
                curr.update(time);
            }
        }

        // Update trade buttons
        if (this.isTrading) {
            if (this.confirmTradeButton != null) {
                this.confirmTradeButton.update(time);
            }
            if (this.cancelTradeButton != null) {
                this.cancelTradeButton.update(time);
            }
        }

        // Update nearby player buttons
        for (Button btn : this.nearbyPlayerButtons) {
            btn.update(time);
        }
    }

    public void input(MouseHandler mouse, KeyHandler key) {
        // Bag-tab clicks have to fire BEFORE drag-drop / slot input so a
        // tab click doesn't get swallowed by a slot underneath. Position
        // math mirrors the render() tab strip exactly so click bounds and
        // visual bounds line up.
        this.handleBagTabClick(mouse);

        // Minimap zoom + click-to-teleport — runs before drag-drop so a
        // click that lands on the minimap doesn't get consumed by a slot.
        if (this.minimap != null) {
            this.minimap.input(mouse);
        }

        this.handleDragAndDrop(mouse);

        for (int i = 0; i < this.inventory.length; i++) {
            Slots curr = this.inventory[i];
            if (curr != null) {
                curr.input(mouse, key);
            }
        }

        for (int i = 0; i < this.groundLoot.length; i++) {
            Slots curr = this.groundLoot[i];
            if (curr != null) {
                curr.input(mouse, key);
            }
        }

        // Poll-based tooltip: check mouse position against all slots each frame
        this.updateTooltip(mouse);

        // Handle trade button input
        if (this.isTrading) {
            if (this.confirmTradeButton != null) {
                this.confirmTradeButton.input(mouse, key);
            }
            if (this.cancelTradeButton != null) {
                this.cancelTradeButton.input(mouse, key);
            }
        }

        // Handle nearby player button input
        for (Button btn : this.nearbyPlayerButtons) {
            btn.input(mouse, key);
        }

        // Trade/Teleport context menu — runs LAST in the pipeline so a
        // click on a menu row isn't first consumed by an underlying
        // nearby-player button (which would re-open the same menu).
        this.handleContextMenuInput(mouse);

        try {
            this.playerChat.input(mouse, key, this.playState.getRealmManager().getClient());
        } catch (Exception e) {
        }
    }

    private void updateTooltip(MouseHandler mouse) {
        int mx = mouse.getX();
        int my = mouse.getY();
        int panelWidth = (OpenRealmGame.width / 5);
        int startX = OpenRealmGame.width - panelWidth;
        int tooltipX = startX - panelWidth - 8;

        // Check inventory slots (equipment + backpack)
        for (int i = 0; i < this.inventory.length; i++) {
            Slots s = this.inventory[i];
            if (s != null && s.getButton() != null && s.getItem() != null) {
                if (s.getButton().getBounds().inside(mx, my)) {
                    this.activeTooltip = new ItemTooltip(s.getItem(),
                            new Vector2f(tooltipX, 100), panelWidth, 0);
                    return;
                }
            }
        }

        // Check ground loot slots
        for (int i = 0; i < this.groundLoot.length; i++) {
            Slots s = this.groundLoot[i];
            if (s != null && s.getButton() != null && s.getItem() != null) {
                if (s.getButton().getBounds().inside(mx, my)) {
                    this.activeTooltip = new ItemTooltip(s.getItem(),
                            new Vector2f(tooltipX, 100), panelWidth, 0);
                    return;
                }
            }
        }

        // Mouse not over any item slot
        this.activeTooltip = null;
    }

    public boolean canSwap() {
        return (Instant.now().toEpochMilli() - this.lastAction) > 1000;
    }

    public void setActionTime() {
        this.lastAction = Instant.now().toEpochMilli();
    }

    public boolean isEquipmentEmpty() {
        for (int i = 0; i < this.inventory.length; i++) {
            Slots curr = this.inventory[i];
            if (curr == null) continue;
            if (curr.getItem() != null)
                return false;
        }
        return true;
    }

    public boolean isGroundLootEmpty() {
        for (int i = 0; i < this.groundLoot.length; i++) {
            Slots curr = this.groundLoot[i];
            if (curr == null) continue;
            if (curr.getItem() != null)
                return false;
        }
        return true;
    }

    public boolean isHoveringInventory(float posX) {
        int panelWidth = (OpenRealmGame.width / 5);
        int startX = OpenRealmGame.width - panelWidth;
        return posX >= startX;
    }

    private void sendTradeCommand(String command) {
        try {
            ServerCommandMessage serverCommand = ServerCommandMessage.parseFromInput("/" + command);
            CommandPacket packet = CommandPacket.create(this.playState.getPlayer(), CommandType.SERVER_COMMAND,
                    serverCommand);
            this.playState.getRealmManager().getClient().sendRemote(packet);
        } catch (Exception e) {
            log.error("Failed to send trade command. Reason: {}", e);
        }
    }

    private void ensureTradeButtons() {
        if (this.confirmTradeButton != null && this.cancelTradeButton != null) return;

        int panelWidth = (OpenRealmGame.width / 5);
        int startX = OpenRealmGame.width - panelWidth;
        int buttonWidth = (panelWidth / 2) - 8;
        int buttonY = 790;

        this.confirmTradeButton = new Button("CONFIRM", new Vector2f(startX + 4, buttonY), buttonWidth, 32);
        this.confirmTradeButton.onMouseUp(event -> {
            this.sendTradeCommand("confirm true");
        });

        this.cancelTradeButton = new Button("CANCEL", new Vector2f(startX + buttonWidth + 12, buttonY), buttonWidth, 32);
        this.cancelTradeButton.onMouseUp(event -> {
            this.sendTradeCommand("decline");
        });
    }

    // Reusable position vectors for slot rendering to avoid per-frame allocations
    private final Vector2f[] slotPositions = new Vector2f[20];
    private final Vector2f[] groundLootPositions = new Vector2f[8];
    {
        for (int i = 0; i < 20; i++) slotPositions[i] = new Vector2f();
        for (int i = 0; i < 8; i++) groundLootPositions[i] = new Vector2f();
    }

    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        this.recomputeLayout();
        int panelWidth = (OpenRealmGame.width / 5);
        int startX = OpenRealmGame.width - panelWidth;

        // Reposition HP/MP/XP bars + size to match new layout.
        // Bar width = panel width - 2 * inset; height fixed at 22 px each.
        final int barX = startX + PANEL_INSET;
        final int barW = panelWidth - 2 * PANEL_INSET;
        final int barH = 22;
        if (this.hp != null) { this.hp.getPos().x = barX;  this.hp.getPos().y = this.layoutBarsY;          this.hp.setBarWidth(barW); this.hp.setBarHeight(barH); }
        if (this.mp != null) { this.mp.getPos().x = barX;  this.mp.getPos().y = this.layoutBarsY + barH;   this.mp.setBarWidth(barW); this.mp.setBarHeight(barH); }
        if (this.xp != null) { this.xp.getPos().x = barX;  this.xp.getPos().y = this.layoutBarsY + barH*2; this.xp.setBarWidth(barW); this.xp.setBarHeight(barH); }

        // Web-parity inventory: 4 equipment + 8 BAG 1 + 8 BAG 2 = 20 slots.
        // Only the currently-active bag's 8 slots are rendered, the other
        // bag is hidden behind its tab.
        Slots[] equips = this.getSlots(0, 4);
        final int bagBase = (this.activeBag == 0) ? 4 : 12;
        Slots[] inv1 = this.getSlots(bagBase,     bagBase + 4);
        Slots[] inv2 = this.getSlots(bagBase + 4, bagBase + 8);

        // Sprite-HUD migration: when the atlas is loaded, draw the new
        // sprite-based HUD here and skip the legacy sidebar block below.
        // The new method also repositions slot Buttons so click/drag still
        // works at the new locations.
        final boolean useSpriteHud = UiAtlas.isReady();
        if (useSpriteHud) {
            this.renderSpriteHud(batch, shapes, font);
        }

        // Color palette — mirrors webclient style.css (#1a1218cc panels,
        // #3a2a38 borders, #c8a86e tan accent). Pulled here so any tweak
        // touches one spot.
        final Color cPanel  = new Color(0.10f, 0.07f, 0.09f, 0.95f);
        final Color cBorder = new Color(0.23f, 0.16f, 0.22f, 1f);
        final Color cAccent = new Color(0.78f, 0.66f, 0.43f, 1f);
        final Color cMuted  = new Color(0.53f, 0.47f, 0.41f, 1f);

        if (!useSpriteHud) {
        // ====== SHAPES PASS: all backgrounds in one ShapeRenderer batch ======
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA,
                GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        // Sidebar background — dark plum, not the legacy grey, to match the
        // webclient's almost-black HUD column.
        shapes.setColor(0.07f, 0.05f, 0.06f, 0.96f);
        shapes.rect(startX, 0, panelWidth, OpenRealmGame.height);

        // Fame badge background (tan, will hold "+N FAME" text in sprite pass)
        shapes.setColor(0.20f, 0.15f, 0.10f, 0.95f);
        shapes.rect(startX + PANEL_INSET, FAME_Y, panelWidth - 2 * PANEL_INSET, FAME_H);

        // Stats panel background (between bars and equipment)
        shapes.setColor(cPanel);
        final int statsH = 22 * 3 + 8;
        shapes.rect(startX + PANEL_INSET, this.layoutStatsY - 4,
                panelWidth - 2 * PANEL_INSET, statsH);

        // Equipment slot backgrounds — uniform grey for ALL 4 slots
        // regardless of whether they hold an item, so empty slots don't
        // show the dark panel underneath. Slots are spread across the
        // full HUD column width via slotX(i) which now divides the
        // usable width into 4 equal cells.
        final Color cSlotBg     = new Color(0.18f, 0.16f, 0.18f, 1f);
        final Color cSlotBorder = new Color(0.30f, 0.24f, 0.28f, 1f);
        final Color cSlotSelected = new Color(0.55f, 0.45f, 0.18f, 1f);
        for (int i = 0; i < 4; i++) {
            final Slots curr = equips[i];
            float sx, sy;
            if (curr != null && curr.getDragPos() != null) {
                sx = curr.getDragPos().x;
                sy = curr.getDragPos().y;
            } else {
                sx = this.slotX(i);
                sy = this.layoutEquipY;
            }
            // Track the slot's screen pos so renderItem / hit-test
            // downstream still see the real layout.
            final Vector2f pos = slotPositions[i];
            pos.x = sx; pos.y = sy;
            shapes.setColor(curr != null && curr.isSelected() ? cSlotSelected : cSlotBg);
            shapes.rect(sx, sy, SLOT_SIZE, SLOT_SIZE);
        }

        // BAG 1 / BAG 2 tab strip — split-tab look (active = tan, inactive = muted)
        final int tabY = this.layoutBagTabY;
        final int tabH = 24;
        final int tabW = (panelWidth - 2 * PANEL_INSET) / 2;
        final int tab1X = startX + PANEL_INSET;
        final int tab2X = startX + PANEL_INSET + tabW;
        // BAG 1 tab
        shapes.setColor(this.activeBag == 0 ? 0.20f : 0.10f,
                        this.activeBag == 0 ? 0.15f : 0.08f,
                        this.activeBag == 0 ? 0.10f : 0.10f, 0.95f);
        shapes.rect(tab1X, tabY, tabW, tabH);
        // BAG 2 tab
        shapes.setColor(this.activeBag == 1 ? 0.20f : 0.10f,
                        this.activeBag == 1 ? 0.15f : 0.08f,
                        this.activeBag == 1 ? 0.10f : 0.10f, 0.95f);
        shapes.rect(tab2X, tabY, tabW, tabH);
        // Tab underline for the active tab
        shapes.setColor(cAccent);
        shapes.rect(this.activeBag == 0 ? tab1X : tab2X, tabY + tabH - 2, tabW, 2);

        // Inventory rows — same uniform grey bg pattern as equipment so the
        // grid reads as one coherent surface regardless of which slots hold
        // items. Webclient #inventory-panel does the same: every slot has
        // the same .item-slot background, hover/selection adds a tint.
        for (int i = 0; i < 4; i++) {
            final Slots curr = inv1[i];
            float sx, sy;
            if (curr != null && curr.getDragPos() != null) {
                sx = curr.getDragPos().x;
                sy = curr.getDragPos().y;
            } else {
                sx = this.slotX(i);
                sy = this.layoutBag1Y;
            }
            final Vector2f pos = slotPositions[4 + i];
            pos.x = sx; pos.y = sy;
            shapes.setColor(curr != null && curr.isSelected() ? cSlotSelected : cSlotBg);
            shapes.rect(sx, sy, SLOT_SIZE, SLOT_SIZE);
        }
        for (int i = 0; i < 4; i++) {
            final Slots curr = inv2[i];
            float sx, sy;
            if (curr != null && curr.getDragPos() != null) {
                sx = curr.getDragPos().x;
                sy = curr.getDragPos().y;
            } else {
                sx = this.slotX(i);
                sy = this.layoutBag2Y;
            }
            final Vector2f pos = slotPositions[8 + i];
            pos.x = sx; pos.y = sy;
            shapes.setColor(curr != null && curr.isSelected() ? cSlotSelected : cSlotBg);
            shapes.rect(sx, sy, SLOT_SIZE, SLOT_SIZE);
        }

        // Ground loot slot backgrounds — persistent 4x2 grid that always
        // shows the same slot layout while a loot container is open, so
        // the panel reads as a uniform grid (matching equipment / bag
        // rows) instead of a sparse cluster of grey blobs.
        if (!this.isTrading && !this.isGroundLootEmpty()) {
            for (int i = 0; i < this.groundLoot.length; i++) {
                Slots curr = this.groundLoot[i];
                int row = i > 3 ? 1 : 0;
                int col = i > 3 ? i - 4 : i;
                Vector2f pos = groundLootPositions[i];
                if (curr != null && curr.getDragPos() != null) {
                    pos.x = curr.getDragPos().x;
                    pos.y = curr.getDragPos().y;
                } else {
                    pos.x = this.slotX(col);
                    pos.y = this.groundLootRowY(row);
                }
                shapes.setColor(curr != null && curr.isSelected() ? cSlotSelected : cSlotBg);
                shapes.rect(pos.x, pos.y, SLOT_SIZE, SLOT_SIZE);
            }
        }

        // HP/MP/XP bar shapes
        this.hp.renderShapes(shapes);
        this.mp.renderShapes(shapes);
        this.xp.renderShapes(shapes);

        // HP / MP potion quick-slot backgrounds, side by side under the bags.
        final int potionY = this.layoutPotionY;
        final int potionSize = SLOT_SIZE;
        final int potionGapX = 12;
        final int potionTotalW = potionSize * 2 + potionGapX;
        final int potionStartX = startX + (panelWidth - potionTotalW) / 2;
        // HP potion (Z) — red, blue stripe like webclient #hp-potion-slot
        shapes.setColor(0.55f, 0.10f, 0.10f, 0.95f);
        shapes.rect(potionStartX, potionY, potionSize, potionSize);
        // MP potion (X) — blue
        shapes.setColor(0.10f, 0.18f, 0.55f, 0.95f);
        shapes.rect(potionStartX + potionSize + potionGapX, potionY, potionSize, potionSize);

        shapes.end();

        // Light pass for borders / dividers — gives the panels a webclient-y
        // outline without forcing every rect to be a stroked rect above.
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(cBorder);
        shapes.rect(startX + PANEL_INSET, FAME_Y, panelWidth - 2 * PANEL_INSET, FAME_H);
        shapes.rect(startX + PANEL_INSET, this.layoutStatsY - 4,
                panelWidth - 2 * PANEL_INSET, statsH);
        // Equipment row underline (separates from bag rows below)
        shapes.line(startX + PANEL_INSET, this.layoutEquipY + SLOT_SIZE + 4,
                    startX + panelWidth - PANEL_INSET, this.layoutEquipY + SLOT_SIZE + 4);
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);

        // ====== SPRITE PASS: all item sprites + text in one SpriteBatch ======
        batch.begin();

        // BAG tab labels — centered horizontally AND vertically. Vertical
        // formula matches TextField's centering: text baseline at
        // `tabY + (tabH + gl.height)/2`, which puts the glyph visually centered
        // in the tab rect rather than sitting on its bottom edge.
        GlyphLayout gl = new GlyphLayout();
        gl.setText(font, "BAG 1");
        float bagY = tabY + (tabH + gl.height) / 2f - 10f;
        font.setColor(this.activeBag == 0 ? cAccent : cMuted);
        float bag1X = tab1X + (tabW - gl.width) / 2f;
        font.draw(batch, "BAG 1", bag1X, bagY);
        font.setColor(this.activeBag == 1 ? cAccent : cMuted);
        gl.setText(font, "BAG 2");
        float bag2X = tab2X + (tabW - gl.width) / 2f;
        font.draw(batch, "BAG 2", bag2X, bagY);
        font.setColor(Color.WHITE);

        // HP / MP potion labels + counts + hotkey hints (Z drinks HP, X drinks MP).
        int hpCount = 0, mpCount = 0;
        try {
            if (this.playState.getPlayer() != null) {
                hpCount = this.playState.getPlayer().getHpPotions();
                mpCount = this.playState.getPlayer().getMpPotions();
            }
        } catch (Exception ignored) { }
        font.setColor(Color.WHITE);
        font.draw(batch, "HP",        potionStartX + 18, potionY + 22);
        font.draw(batch, "x" + hpCount,  potionStartX + 14, potionY + 42);
        font.setColor(cMuted);
        font.draw(batch, "[Z]",       potionStartX + 18, potionY + 56);
        font.setColor(Color.WHITE);
        final int potMpX = potionStartX + potionSize + potionGapX;
        font.draw(batch, "MP",        potMpX + 18, potionY + 22);
        font.draw(batch, "x" + mpCount,  potMpX + 14, potionY + 42);
        font.setColor(cMuted);
        font.draw(batch, "[X]",       potMpX + 18, potionY + 56);
        font.setColor(Color.WHITE);

        // Equipment items
        for (int i = 0; i < equips.length; i++) {
            if (equips[i] != null) equips[i].renderItem(batch, slotPositions[i]);
        }

        // Inventory items
        for (int i = 0; i < inv1.length; i++) {
            if (inv1[i] != null) inv1[i].renderItem(batch, slotPositions[4 + i]);
        }
        for (int i = 0; i < inv2.length; i++) {
            if (inv2[i] != null) inv2[i].renderItem(batch, slotPositions[8 + i]);
        }

        // Ground loot items
        if (!this.isTrading) {
            for (int i = 0; i < this.groundLoot.length; i++) {
                if (this.groundLoot[i] != null) this.groundLoot[i].renderItem(batch, groundLootPositions[i]);
            }
        }

        // Stack-count overlays — drawn AFTER all sprites so the "xN" text
        // sits on top of the slot icon. Web parity: main.js' updateInventoryUI
        // draws the .item-stack span over the item face for stackable items
        // with count > 1. Only inventory + ground-loot stacks make sense
        // (equipment is non-stackable in the current item set).
        for (int i = 0; i < inv1.length; i++) {
            if (inv1[i] != null) inv1[i].renderStackCount(batch, font, slotPositions[4 + i]);
        }
        for (int i = 0; i < inv2.length; i++) {
            if (inv2[i] != null) inv2[i].renderStackCount(batch, font, slotPositions[8 + i]);
        }
        if (!this.isTrading) {
            for (int i = 0; i < this.groundLoot.length; i++) {
                if (this.groundLoot[i] != null) this.groundLoot[i].renderStackCount(batch, font, groundLootPositions[i]);
            }
        }

        // HP/MP/XP bar text
        this.hp.renderText(batch, font);
        this.mp.renderText(batch, font);
        this.xp.renderText(batch, font);

        // (Difficulty / fame badge text removed alongside the shapes pass
        // above — see note there.)
        } // end if (!useSpriteHud) — sprite HUD draws bars/items in renderSpriteHud

        // Trade UI (still uses old rendering for now - it's conditional/rare)
        if (this.isTrading) {
            this.renderTradeUI(batch, shapes, font, startX, panelWidth);
        }

        // Render nearby players list
        // Sprite HUD reroutes the nearby-players list into its own bottom-
        // left panel; the legacy path renders it in the right sidebar.
        if (useSpriteHud && this.spriteHudNearbyEnabled) {
            // renderNearbyPlayers reads layoutNearbyY for the header Y —
            // override it to the sprite-HUD nearby panel's screen y, restore
            // after so the legacy path is unaffected.
            final int prevNearbyY = this.layoutNearbyY;
            this.layoutNearbyY = this.spriteHudNearbyY;
            this.renderNearbyPlayers(batch, shapes, font,
                    this.spriteHudNearbyX, this.spriteHudNearbyW);
            this.layoutNearbyY = prevNearbyY;
        } else {
            this.renderNearbyPlayers(batch, shapes, font, startX, panelWidth);
        }

        if (this.activeTooltip != null) {
            this.activeTooltip.render(batch, shapes, font);
        }

        this.renderPlayerTooltip(batch, shapes, font);
        this.renderPlayerContextMenu(batch, shapes, font);
        if (!useSpriteHud) this.renderStats(batch, font); // sprite HUD draws stats in renderSpriteHud
        this.renderPortalPrompt(batch, shapes, font);
        this.renderInteractPrompt(batch, shapes, font);
        this.playerChat.render(batch, shapes, font);

        if (this.minimap.isInitialized()) {
            // Position: sprite-HUD owns minimap layout (set inside renderSpriteHud
            // → panel.container.large at top-left). Legacy path falls back to
            // the right-sidebar position.
            if (!useSpriteHud) {
                final int hudPanelW = OpenRealmGame.width / 5;
                final int hudPanelX = OpenRealmGame.width - hudPanelW;
                final int size = Math.max(96, Math.min(hudPanelW - 2 * PANEL_INSET,
                        OpenRealmGame.height / 4));
                this.minimap.setLayout(hudPanelX + PANEL_INSET, this.layoutMinimapY, size);
            }
            // Wrap update + render in a try/catch so a transient failure
            // inside the minimap (e.g. mid-realm-transition state where the
            // Pixmap rebuild touches still-clearing tile data) doesn't
            // propagate into the LWJGL render loop and kill the whole game.
            // The portal-enter crash was a NullPointerException / Pixmap
            // size assertion fired here; without this guard the JVM exits
            // before the user can see anything.
            try {
                this.minimap.update();
                this.minimap.render(batch, shapes);
            } catch (Throwable t) {
                log.warn("Minimap render failed (recovering): {}", t.toString());
            }
        }

        // (Sprite-HUD already drawn earlier in render() when atlas is ready.)

        // Web-parity overlays render last so they sit on top of the HUD.
        // Each one is a no-op when its `visible` / `active` flag is false.
        this.realmTransition.update();
        this.realmTransition.render(batch, shapes, font);
        this.forgeWindow.update();
        this.forgeWindow.render(batch, shapes, font);
        this.fameStoreWindow.update();
        this.fameStoreWindow.render(batch, shapes, font);
        this.optionsWindow.update();
        this.optionsWindow.render(batch, shapes, font);

        // Dev-stats overlay removed — the top-left corner now hosts the
        // minimap panel. PerfMetrics still ticks for any other consumers
        // (debug logs, future overlay re-enable) but doesn't render.
        PerfMetrics.get().onFrame();
    }

    private void renderTradeUI(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font, int startX, int panelWidth) {
        this.ensureTradeButtons();

        // Update ground loot with ALL of other player's items, marking selected ones
        if (this.currentTradeSelection != null) {
            NetInventorySelection otherSelection = this.getOtherPlayerSelection();
            this.groundLoot = new Slots[8];
            if (otherSelection != null && otherSelection.getItemRefs() != null) {
                GameItem[] allItems = otherSelection.getGameItems();
                Boolean[] selection = otherSelection.getSelection();
                for (int i = 0; i < allItems.length && i < 8; i++) {
                    GameItem item = allItems[i];
                    if (item == null || item.getItemId() == -1) continue;
                    this.buildGroundLootSlotButton(i, item);
                    // Mark selected items so they render with yellow highlight
                    if (this.groundLoot[i] != null && selection != null && i < selection.length
                            && selection[i] != null && selection[i]) {
                        this.groundLoot[i].setSelected(true);
                    }
                }
            }
        }

        // Draw trade header
        String header = "Trading with " + (this.tradePartnerName != null ? this.tradePartnerName : "...");
        font.setColor(Color.GREEN);
        font.draw(batch, header, startX + 4, 230);

        // Draw "YOUR OFFER" label
        font.setColor(Color.YELLOW);
        font.draw(batch, "YOUR OFFER (right-click to select)", startX + 4, 440);

        // Draw "THEIR OFFER" label - show partner's full inventory
        String theirLabel = this.tradePartnerName != null
                ? this.tradePartnerName + "'s ITEMS (selected = offered)"
                : "THEIR ITEMS";
        font.setColor(Color.CYAN);
        font.draw(batch, theirLabel, startX + 4, 640);

        // Render the other player's items in the ground loot area
        Slots[] gl1 = Arrays.copyOfRange(this.groundLoot, 0, 4);
        Slots[] gl2 = Arrays.copyOfRange(this.groundLoot, 4, 8);

        for (int i = 0; i < gl1.length; i++) {
            Slots curr = gl1[i];
            if (curr != null) {
                curr.render(batch, shapes, new Vector2f(startX + (i * 64), 650));
            }
        }

        for (int i = 0; i < gl2.length; i++) {
            Slots curr = gl2[i];
            if (curr != null) {
                curr.render(batch, shapes, new Vector2f(startX + (i * 64), 714));
            }
        }

        // Draw Confirm and Cancel buttons
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(Color.FOREST);
        shapes.rect(this.confirmTradeButton.getPos().x, this.confirmTradeButton.getPos().y,
                this.confirmTradeButton.getWidth(), this.confirmTradeButton.getHeight());
        shapes.setColor(Color.FIREBRICK);
        shapes.rect(this.cancelTradeButton.getPos().x, this.cancelTradeButton.getPos().y,
                this.cancelTradeButton.getWidth(), this.cancelTradeButton.getHeight());
        shapes.end();
        batch.begin();
        font.setColor(Color.WHITE);
        font.draw(batch, "CONFIRM", this.confirmTradeButton.getPos().x + 8, this.confirmTradeButton.getPos().y + 22);
        font.draw(batch, "CANCEL", this.cancelTradeButton.getPos().x + 12, this.cancelTradeButton.getPos().y + 22);
    }

    private TextureRegion getClassIcon(int classId) {
        TextureRegion cached = this.classIconCache.get(classId);
        if (cached != null) return cached;
        CharacterClass cls = CharacterClass.valueOf(classId);
        if (cls == null) return null;
        try {
            TextureRegion icon = GameSpriteManager.loadClassSprites(cls).getSubSprite(0, 0).getRegion();
            this.classIconCache.put(classId, icon);
            return icon;
        } catch (Exception e) {
            return null;
        }
    }

    private void refreshNearbyPlayerButtons(int startX, int panelWidth) {
        long now = Instant.now().toEpochMilli();
        if ((now - this.lastNearbyRefresh) < 500 && !this.nearbyPlayerButtons.isEmpty()) return;
        this.lastNearbyRefresh = now;

        Set<Player> nearby = null;
        try {
            nearby = this.playState.getRealmManager().getRealm().getPlayersExcept(this.playState.getPlayerId());
        } catch (Exception e) {
            return;
        }
        if (nearby == null || nearby.isEmpty()) {
            this.nearbyPlayerButtons.clear();
            this.nearbyPlayerList.clear();
            this.hoveredPlayer = null;
            return;
        }

        int headerY = this.layoutNearbyY;
        int iconSize = 26;
        int entryHeight = 30;
        int colWidth = (panelWidth - 8) / 2;
        int startY = headerY + 16;

        List<Player> playerList = new ArrayList<>(nearby);
        List<Button> newButtons = new ArrayList<>();

        for (int i = 0; i < playerList.size() && i < 16; i++) {
            Player p = playerList.get(i);
            int col = i % 2;
            int row = i / 2;

            int x = startX + (col * colWidth);
            int y = startY + (row * entryHeight);

            Button btn = new Button(new Vector2f(x, y), iconSize);
            btn.getBounds().setWidth(colWidth);
            btn.getBounds().setHeight(entryHeight);
            final Player hoverTarget = p;
            final int btnX = x;
            final int btnY = y;
            final int btnW = colWidth;
            btn.onHoverIn(event -> {
                this.hoveredPlayer = hoverTarget;
            });
            btn.onHoverOut(event -> {
                if (this.hoveredPlayer == hoverTarget) {
                    this.hoveredPlayer = null;
                }
            });
            // Open the trade/tp context menu on left-click. Button has no
            // onClick callback, so use onMouseDown (mirrors webclient
            // trade.js click handler — show menu at click coords).
            btn.onMouseDown(event -> {
                this.contextMenuPlayer = hoverTarget;
                this.contextMenuX = btnX + btnW + 4;
                this.contextMenuY = btnY;
            });
            newButtons.add(btn);
        }

        this.nearbyPlayerList = playerList;
        this.nearbyPlayerButtons = newButtons;
    }

    private void renderNearbyPlayers(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font, int startX, int panelWidth) {
        this.refreshNearbyPlayerButtons(startX, panelWidth);

        int headerY = this.layoutNearbyY;
        font.setColor(0.78f, 0.66f, 0.43f, 1f); // tan accent (matches name + level)
        font.draw(batch, "Nearby players", startX, headerY);
        font.setColor(Color.WHITE);

        if (this.nearbyPlayerList.isEmpty()) return;

        int iconSize = 26;
        int entryHeight = 30;
        int colWidth = (panelWidth - 8) / 2;
        int startY = headerY + 16;

        for (int i = 0; i < this.nearbyPlayerList.size() && i < 16; i++) {
            Player p = this.nearbyPlayerList.get(i);
            int col = i % 2;
            int row = i / 2;

            int x = startX + (col * colWidth);
            int y = startY + (row * entryHeight);

            // Class icon — sized to the row so the avatar reads cleanly
            // alongside the name. Sprite is centered vertically against
            // the text baseline.
            TextureRegion icon = this.getClassIcon(p.getClassId());
            if (icon != null) {
                batch.draw(icon, x, y + (entryHeight - iconSize) / 2f, iconSize, iconSize);
            }

            // Hovered row: keep the row color the same as the role color
            // (so the user still sees the role) but brighten with a yellow
            // tint via blend. Simplest: just override to YELLOW when hovered,
            // which matches the prior behavior the user expects.
            final Color nameColor = (this.hoveredPlayer == p)
                    ? Color.YELLOW
                    : roleColorFor(p.getChatRole());
            font.setColor(nameColor);
            // Full name, no clip — the panel is wide enough now and the
            // tooltip-on-hover already shows the canonical name anyway.
            font.draw(batch, p.getName(),
                    x + iconSize + 6,
                    y + (entryHeight + 10) / 2);
        }
        font.setColor(Color.WHITE);
    }

    /** Cached chat-role nameplate colors used by the hover tooltip, mirrors
     *  webclient renderer.js GameRenderer.getNameColorHex. Kept here as
     *  well as PlayState (where the in-world nameplate uses them) so
     *  PlayerUI doesn't need a static cross-class import dance. */
    private static final Color UI_ROLE_SYSADMIN = new Color(1.00f, 0.25f, 0.25f, 1f);
    private static final Color UI_ROLE_ADMIN    = new Color(0.25f, 0.50f, 0.88f, 1f);
    private static final Color UI_ROLE_MOD      = new Color(0.25f, 0.75f, 0.25f, 1f);
    private static final Color UI_ROLE_EDITOR   = new Color(0.63f, 0.25f, 0.75f, 1f);
    private static final Color UI_ROLE_DEMO     = new Color(0.80f, 0.80f, 0.80f, 1f);
    private static final Color UI_ROLE_DEFAULT  = new Color(0.93f, 0.93f, 0.93f, 1f);

    private static Color roleColorFor(String role) {
        if (role == null) return UI_ROLE_DEFAULT;
        switch (role) {
            case "sysadmin": return UI_ROLE_SYSADMIN;
            case "admin":    return UI_ROLE_ADMIN;
            case "mod":      return UI_ROLE_MOD;
            case "editor":   return UI_ROLE_EDITOR;
            case "demo":     return UI_ROLE_DEMO;
            default:         return UI_ROLE_DEFAULT;
        }
    }

    /** Width / height of the trade-tp context menu. Two rows of options
     *  plus a name header. Kept small so it fits next to a 2-col nearby
     *  list entry on a typical screen. */
    private static final int CTX_MENU_W = 130;
    private static final int CTX_MENU_HEADER_H = 22;
    private static final int CTX_MENU_OPTION_H = 22;

    /**
     * Render + input for the player context menu. Mirrors webclient
     * trade.js {@code showPlayerContextMenu}: header with the player's
     * name, then "Trade" / "Teleport" rows that send the same SERVER_COMMAND
     * payloads chat would (/trade {@literal <name>}, /tp {@literal <name>}).
     * Click anywhere outside dismisses, matching the web behavior.
     */
    private void renderPlayerContextMenu(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        if (this.contextMenuPlayer == null) return;
        final Player p = this.contextMenuPlayer;
        final int x = this.contextMenuX;
        final int yHeader = this.contextMenuY;
        final int yTrade = yHeader + CTX_MENU_HEADER_H;
        final int yTp = yTrade + CTX_MENU_OPTION_H;
        final int totalH = CTX_MENU_HEADER_H + 2 * CTX_MENU_OPTION_H;

        // Background + border
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0x1a / 255f, 0x12 / 255f, 0x18 / 255f, 0.96f);
        shapes.rect(x, yHeader, CTX_MENU_W, totalH);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0x3a / 255f, 0x2a / 255f, 0x38 / 255f, 1f);
        shapes.rect(x, yHeader, CTX_MENU_W, totalH);
        // Row separators
        shapes.line(x, yTrade, x + CTX_MENU_W, yTrade);
        shapes.line(x, yTp, x + CTX_MENU_W, yTp);
        shapes.end();
        batch.begin();

        font.setColor(0xc8 / 255f, 0xa8 / 255f, 0x6e / 255f, 1f);
        font.draw(batch, p.getName() == null ? "Player" : p.getName(),
                x + 6, yHeader + 14);

        font.setColor(0xe0 / 255f, 0xd8 / 255f, 0xc8 / 255f, 1f);
        font.draw(batch, "Trade",    x + 6, yTrade + 14);
        font.draw(batch, "Teleport", x + 6, yTp + 14);
        font.setColor(Color.WHITE);
    }

    /**
     * Mouse-driven hit-test for the context menu. Called from input()
     * after the rest of the input pipeline so a click on a menu row
     * doesn't get swallowed by anything below. Edge-triggered on
     * mouse-down via prevContextMenuMouseDown to mirror the chat-tab
     * click pattern; holding the button doesn't keep firing.
     */
    private void handleContextMenuInput(MouseHandler mouse) {
        final boolean down = mouse.isPressed(1);
        final boolean justClicked = down && !this.prevContextMenuMouseDown;
        this.prevContextMenuMouseDown = down;
        if (this.contextMenuPlayer == null) return;
        if (!justClicked) return;

        final int mx = mouse.getX();
        final int my = mouse.getY();
        final int x = this.contextMenuX;
        final int yHeader = this.contextMenuY;
        final int yTrade = yHeader + CTX_MENU_HEADER_H;
        final int yTp = yTrade + CTX_MENU_OPTION_H;
        final int yEnd = yTp + CTX_MENU_OPTION_H;
        final boolean inX = mx >= x && mx <= x + CTX_MENU_W;
        final boolean inMenuY = my >= yHeader && my <= yEnd;
        if (!inX || !inMenuY) {
            // Click-elsewhere — dismiss without action (matches web).
            this.contextMenuPlayer = null;
            return;
        }
        final Player target = this.contextMenuPlayer;
        if (my >= yTrade && my < yTp) {
            this.sendServerCommand("trade", target.getName());
            this.enqueueChat(TextPacket.create("SYSTEM", target.getName(),
                    "Trade request sent to " + target.getName()));
        } else if (my >= yTp && my < yEnd) {
            this.sendServerCommand("tp", target.getName());
            this.enqueueChat(TextPacket.create("SYSTEM", target.getName(),
                    "Teleporting to " + target.getName()));
        }
        this.contextMenuPlayer = null;
    }

    /**
     * Send a SERVER_COMMAND CommandPacket — same path
     * {@link PlayerChat#input} uses for typed slash commands. Delegates
     * the parsing to {@link ServerCommandMessage#parseFromInput} so
     * server-side handling stays uniform.
     */
    private void sendServerCommand(String command, String arg) {
        try {
            final String full = "/" + command + " " + (arg == null ? "" : arg);
            final ServerCommandMessage msg = ServerCommandMessage.parseFromInput(full);
            final CommandPacket packet = CommandPacket.create(this.playState.getPlayer(),
                    CommandType.SERVER_COMMAND, msg);
            this.playState.getRealmManager().getClient().sendRemote(packet);
        } catch (Exception ex) {
            log.error("Failed to send server command /{} {}: {}", command, arg, ex.getMessage());
        }
    }

    private void renderPlayerTooltip(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        if (this.hoveredPlayer == null) return;

        final Player p = this.hoveredPlayer;
        final int panelW = OpenRealmGame.width / 5;
        // Anchor the tooltip to the LEFT edge of the right HUD column so it
        // doesn't fall over the player sprite or escape the screen on
        // narrow windows. Mirrors the webclient #player-tooltip placement
        // (just inside the HUD, above the nearby-player list it pops from).
        final int tooltipW = panelW;
        final int tooltipX = OpenRealmGame.width - panelW - tooltipW - 8;
        final int tooltipY = this.layoutNearbyY - 168;
        final int padX = 8;

        // Pull stats — guard nulls so a freshly-added remote player whose
        // UpdatePacket hasn't landed yet doesn't NPE.
        final int hp = p.getHealth();
        final int mp = p.getMana();
        final int maxHp = (p.getStats() != null) ? p.getStats().getHp() : 0;
        final int maxMp = (p.getStats() != null) ? p.getStats().getMp() : 0;
        final CharacterClass cls = CharacterClass.valueOf(p.getClassId());
        final String className = (cls != null) ? cls.name() : "Unknown";

        // Equipment slots 0-3. Server's stripped UpdatePacket
        // (UpdatePacket.fromPlayerWithoutInventory) ships these for
        // remote players; null until the first broadcast lands.
        final GameItem[] equips = p.getSlots(0, 4);

        // Layout sizes — equipment slots are 36px to give a little
        // breathing room (was 32 touching). Compute panel height
        // dynamically so we don't paint unused chrome.
        final int rowH = 16;
        final int equipSlot = 36;
        final int equipGap = 6;
        final int equipRowH = equipSlot + 8;
        final int tooltipH = (rowH * 4) + equipRowH + 16;

        // Background panel — match the webclient's tooltip palette.
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0x1a / 255f, 0x12 / 255f, 0x18 / 255f, 0.94f);
        shapes.rect(tooltipX, tooltipY, tooltipW, tooltipH);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0x4a / 255f, 0x3a / 255f, 0x58 / 255f, 1f);
        shapes.rect(tooltipX, tooltipY, tooltipW, tooltipH);
        shapes.end();
        batch.begin();

        int y = tooltipY + rowH;
        // Name (role-colored)
        font.setColor(roleColorFor(p.getChatRole()));
        font.draw(batch, p.getName() == null ? "Player" : p.getName(), tooltipX + padX, y);
        // Class
        y += rowH;
        font.setColor(0x88 / 255f, 0x78 / 255f, 0x68 / 255f, 1f);
        font.draw(batch, className, tooltipX + padX, y);
        // HP (cur/max) — webclient parity. setColor mutated so reset to
        // white for the divider character.
        y += rowH;
        font.setColor(0xe0 / 255f, 0x55 / 255f, 0x55 / 255f, 1f);
        font.draw(batch, "HP: " + hp + "/" + maxHp, tooltipX + padX, y);
        // MP (cur/max)
        y += rowH;
        font.setColor(0x55 / 255f, 0x77 / 255f, 0xe0 / 255f, 1f);
        font.draw(batch, "MP: " + mp + "/" + maxMp, tooltipX + padX, y);
        // Equipment row — slots 0-3 spaced with a real gap so they're
        // not touching, no black background under empty slots.
        y += 6;
        final int totalEquipsW = 4 * equipSlot + 3 * equipGap;
        int equipStartX = tooltipX + (tooltipW - totalEquipsW) / 2;
        for (int i = 0; i < equips.length; i++) {
            final int sx = equipStartX + i * (equipSlot + equipGap);
            // Slot bg — uniform muted purple regardless of contents,
            // mirrors webclient .tooltip-equip-slot styling and matches
            // the inventory slot color so the surface reads as the same
            // material across the whole HUD.
            batch.end();
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0x2a / 255f, 0x20 / 255f, 0x30 / 255f, 1f);
            shapes.rect(sx, y, equipSlot, equipSlot);
            shapes.end();
            shapes.begin(ShapeRenderer.ShapeType.Line);
            shapes.setColor(0x3a / 255f, 0x2a / 255f, 0x38 / 255f, 1f);
            shapes.rect(sx, y, equipSlot, equipSlot);
            shapes.end();
            batch.begin();
            if (equips[i] != null && equips[i].getItemId() != -1) {
                final TextureRegion itemRegion = GameSpriteManager.ITEM_SPRITES.get(equips[i].getItemId());
                if (itemRegion != null) {
                    batch.draw(itemRegion, sx + 2, y + 2, equipSlot - 4, equipSlot - 4);
                }
            }
        }
        Gdx.gl.glDisable(GL20.GL_BLEND);
        font.setColor(Color.WHITE);
    }

    /**
     * Detect a click on the BAG 1 / BAG 2 tab strip and toggle the active
     * bag. Edge-triggered on mouse press (using prevMouseDown) so holding
     * the button doesn't flip back and forth every frame.
     */
    private boolean prevTabMouseDown = false;
    private void handleBagTabClick(MouseHandler mouse) {
        boolean down = mouse.isPressed(1);
        boolean justClicked = down && !this.prevTabMouseDown;
        this.prevTabMouseDown = down;
        if (!justClicked) return;

        this.recomputeLayout();
        int panelWidth = (OpenRealmGame.width / 5);
        int startX = OpenRealmGame.width - panelWidth;
        int tabY = this.layoutBagTabY;
        int tabH = 24;
        int tabW = (panelWidth - 2 * PANEL_INSET) / 2;
        int tab1X = startX + PANEL_INSET;
        int tab2X = startX + PANEL_INSET + tabW;
        int mx = (int) mouse.getX();
        int my = (int) mouse.getY();
        if (my < tabY || my > tabY + tabH) return;
        if (mx >= tab1X && mx <= tab1X + tabW) {
            this.activeBag = 0;
        } else if (mx >= tab2X && mx <= tab2X + tabW) {
            this.activeBag = 1;
        }
    }

    public void handleDragAndDrop(MouseHandler mouse) {
        if (this.isTrading) {
            for (Slots slot : this.inventory) {
                if (slot != null) slot.setDragPos(null);
            }
            for (Slots slot : this.groundLoot) {
                if (slot != null) slot.setDragPos(null);
            }
            this.isDragging = false;
            this.dragSourceIndex = -1;
            this.dragStartPos = null;
            return;
        }

        int mouseX = (int) mouse.getX();
        int mouseY = (int) mouse.getY();

        if (mouse.isPressed(1) && !this.isDragging && this.dragSourceIndex == -1) {
            // Detect drag start: find slot with non-null dragPos
            for (int i = 0; i < this.inventory.length; i++) {
                Slots slot = this.inventory[i];
                if (slot != null && slot.getDragPos() != null && slot.getItem() != null) {
                    this.dragSourceIndex = i;
                    this.dragStartPos = new Vector2f(mouseX, mouseY);
                    break;
                }
            }
            if (this.dragSourceIndex == -1) {
                for (int i = 0; i < this.groundLoot.length; i++) {
                    Slots slot = this.groundLoot[i];
                    if (slot != null && slot.getDragPos() != null && slot.getItem() != null) {
                        this.dragSourceIndex = i + 20;
                        this.dragStartPos = new Vector2f(mouseX, mouseY);
                        break;
                    }
                }
            }
        }

        // Only set isDragging after mouse moves past threshold
        if (this.dragSourceIndex != -1 && !this.isDragging && this.dragStartPos != null) {
            float dist = new Vector2f(mouseX, mouseY).distanceTo(this.dragStartPos);
            if (dist > DRAG_THRESHOLD) {
                this.isDragging = true;
            }
        }

        // On mouse release while dragging
        if (!mouse.isPressed(1) && this.isDragging) {
            int targetIndex = this.findSlotAtPositionByLayout(mouseX, mouseY);
            this.executeDrop(this.dragSourceIndex, targetIndex);
            this.isDragging = false;
            this.dragSourceIndex = -1;
            this.dragStartPos = null;
        } else if (!mouse.isPressed(1)) {
            // Reset if released without dragging
            this.dragSourceIndex = -1;
            this.dragStartPos = null;
        }
    }

    private int findSlotAtPositionByLayout(int mouseX, int mouseY) {
        this.recomputeLayout();
        int panelWidth = (OpenRealmGame.width / 5);
        int startX = OpenRealmGame.width - panelWidth;
        if (mouseX < startX || mouseX > OpenRealmGame.width) return -1;

        // Find which slot column the cursor is over by hit-testing each
        // possible column rect (slotX is non-uniform vs startX since we
        // center the row inside the panel).
        int col = -1;
        for (int c = 0; c < 4; c++) {
            int sx = this.slotX(c);
            if (mouseX >= sx && mouseX < sx + SLOT_SIZE) { col = c; break; }
        }
        if (col < 0) return -1;

        if (mouseY >= this.layoutEquipY && mouseY < this.layoutEquipY + SLOT_SIZE) return col;
        if (mouseY >= this.layoutBag1Y  && mouseY < this.layoutBag1Y  + SLOT_SIZE) return 4 + col;
        if (mouseY >= this.layoutBag2Y  && mouseY < this.layoutBag2Y  + SLOT_SIZE) return 8 + col;
        int gl0 = this.groundLootRowY(0);
        int gl1 = this.groundLootRowY(1);
        if (mouseY >= gl0 && mouseY < gl0 + SLOT_SIZE) return 20 + col;
        if (mouseY >= gl1 && mouseY < gl1 + SLOT_SIZE) return 24 + col;
        return -1;
    }

    private void executeDrop(int fromIndex, int targetIndex) {
        if (!this.canSwap()) return;
        if (fromIndex == targetIndex) return;

        this.setActionTime();

        // Forge drop-zones take priority when the forge window is up:
        // dragging an inventory item onto the Target / Crystal / Essence
        // slot binds it to that forge slot (web parity, forge.js
        // dropzones). Without this, drag-drop into the forge silently
        // fell through to executeDrop's normal swap path and the forge
        // slots stayed empty no matter what the player tried.
        if (this.forgeWindow.isVisible() && fromIndex >= 0 && fromIndex <= 19) {
            final int mx = com.badlogic.gdx.Gdx.input.getX();
            final int my = com.badlogic.gdx.Gdx.input.getY();
            final Slots srcSlot = this.inventory[fromIndex];
            final GameItem srcItem = srcSlot != null ? srcSlot.getItem() : null;
            int crystalItemId = -1;
            int crystalStatId = -1;
            if (srcItem != null) {
                crystalItemId = srcItem.getItemId();
                // The crystal's stat id is encoded as itemId - 808
                // (see ServerFameStoreHelper.CRYSTAL_ITEM_MIN). For non-
                // crystal drops the value is ignored by the forge slot.
                if (crystalItemId >= 808 && crystalItemId <= 815) {
                    crystalStatId = crystalItemId - 808;
                }
            }
            if (this.forgeWindow.tryAcceptDrop(mx, my, fromIndex, crystalItemId, crystalStatId)) {
                return;
            }
        }

        boolean fromIsGround = fromIndex >= 20 && fromIndex <= 27;
        boolean targetIsGround = targetIndex >= 20 && targetIndex <= 27;
        boolean fromIsEquip = fromIndex >= 0 && fromIndex <= 3;
        boolean targetIsEquip = targetIndex >= 0 && targetIndex <= 3;

        if (targetIndex == -1) {
            // Dropped outside any slot: drop item
            this.playState.getRealmManager().moveItem(-1, fromIndex, true, false);
        } else if (fromIsGround && !targetIsGround) {
            // Ground -> inventory/equip: pickup. HP/MP potions route to the
            // potion counters on the server; pinning targetSlot to a fixed
            // inventory index avoids the equip-validation path on slots 0-3.
            final Slots srcSlot = this.groundLoot[fromIndex - 20];
            final GameItem srcItem = srcSlot != null ? srcSlot.getItem() : null;
            if (srcItem != null
                    && (srcItem.getItemId() == com.openrealm.game.entity.Player.HP_POTION_ITEM_ID
                     || srcItem.getItemId() == com.openrealm.game.entity.Player.MP_POTION_ITEM_ID)) {
                this.playState.getRealmManager().moveItem(4, fromIndex, false, false);
            } else {
                this.playState.getRealmManager().moveItem(targetIndex, fromIndex, false, false);
            }
        } else if (!fromIsGround && targetIsGround) {
            // Inventory/equip -> ground area: drop
            this.playState.getRealmManager().moveItem(-1, fromIndex, true, false);
        } else {
            // inv->equip, equip->inv, inv->inv: swap/equip/unequip
            this.playState.getRealmManager().moveItem(targetIndex, fromIndex, false, false);
        }
    }

    /**
     * Bottom-right interaction prompt — pinned to the lower-left corner of
     * the right HUD column inside a black box so the text is legible against
     * any tile background. Format: "PRESS SPACE TO ENTER {NAME}".
     */
    private void renderPortalPrompt(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        if (this.playState == null || this.playState.getPlayer() == null) return;
        try {
            final Vector2f pPos = this.playState.getPlayer().getPos();
            if (pPos == null) return;
            final Portal nearest = this.playState.getClosestPortal(pPos, 32f);
            if (nearest == null) return;
            String name = "PORTAL";
            try {
                final PortalModel pm = GameDataManager.PORTALS != null
                        ? GameDataManager.PORTALS.get((int) nearest.getPortalId()) : null;
                if (pm != null && pm.getPortalName() != null && !pm.getPortalName().isEmpty()) {
                    name = pm.getPortalName();
                } else if (pm != null && pm.getLabel() != null && !pm.getLabel().isEmpty()) {
                    name = pm.getLabel();
                }
            } catch (Exception ignored) { /* fall back to generic label */ }
            this.renderHintBox(batch, shapes, font, "PRESS SPACE TO ENTER " + name.toUpperCase(), 0);
        } catch (Exception ignored) { /* never block render on a UI hint */ }
    }

    /**
     * Forge / fame-store interaction prompt — same visual style as the
     * portal prompt but stacked above it. Surfaced because the F-key
     * interaction is invisible without a UI hint.
     */
    private void renderInteractPrompt(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        if (this.playState == null || this.playState.getPlayer() == null) return;
        try {
            final String type = this.playState.getNearbyInteractionType();
            if (type == null) return;
            final String label;
            if ("forge".equalsIgnoreCase(type)) label = "PRESS F TO USE FORGE";
            else if ("fame_store".equalsIgnoreCase(type)) label = "PRESS F TO OPEN FAME SHOP";
            else label = "PRESS F TO INTERACT";
            this.renderHintBox(batch, shapes, font, label, 1);
        } catch (Exception ignored) { /* never block render on a UI hint */ }
    }

    /**
     * Shared hint-box renderer for bottom-right interaction prompts. Stack
     * index lifts the box upward so multiple hints can show at once.
     */
    private void renderHintBox(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font, String text, int stackIndex) {
        final GlyphLayout gl = new GlyphLayout(font, text);
        final int padX = 14, padY = 8;
        final int boxW = (int) gl.width + padX * 2;
        final int boxH = (int) gl.height + padY * 2;
        final int panelW = OpenRealmGame.width / 5;
        // Pin to the bottom of the screen, just left of the right HUD column.
        final int boxX = OpenRealmGame.width - panelW - boxW - 16;
        final int boxY = OpenRealmGame.height - 24 - boxH - stackIndex * (boxH + 6);

        batch.end();
        com.badlogic.gdx.Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, 0.85f);
        shapes.rect(boxX, boxY, boxW, boxH);
        shapes.setColor(0.95f, 0.85f, 0.45f, 1f);
        shapes.rect(boxX, boxY, boxW, 2);
        shapes.rect(boxX, boxY + boxH - 2, boxW, 2);
        shapes.rect(boxX, boxY, 2, boxH);
        shapes.rect(boxX + boxW - 2, boxY, 2, boxH);
        shapes.end();
        com.badlogic.gdx.Gdx.gl.glDisable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
        batch.begin();

        font.setColor(0.95f, 0.85f, 0.45f, 1f);
        font.draw(batch, text, boxX + padX, boxY + padY + gl.height);
        font.setColor(Color.WHITE);
    }

    private void renderStats(SpriteBatch batch, BitmapFont font) {
        if (this.playState.getPlayer() == null) return;
        this.recomputeLayout();
        final int panelWidth = OpenRealmGame.width / 5;
        final int panelStartX = OpenRealmGame.width - panelWidth;
        final int textX = panelStartX + PANEL_INSET;
        // Two columns inside the panel; right column starts halfway across
        final int colGap = (panelWidth - 2 * PANEL_INSET) / 2;
        final int yOffset = 22;
        final int startY = this.layoutStatsY + 14;

        // Player name + level header (top of HUD column)
        font.setColor(new Color(0.78f, 0.66f, 0.43f, 1f));
        long fame = GameDataManager.EXPERIENCE_LVLS.getBaseFame(this.playState.getPlayer().getExperience());
        final String header;
        if (fame == 0L) {
            header = this.playState.getPlayer().getName() + "   Lv. "
                    + GameDataManager.EXPERIENCE_LVLS.getLevel(this.playState.getPlayer().getExperience());
        } else {
            header = this.playState.getPlayer().getName() + "   Lv. 20";
        }
        font.draw(batch, header, textX, HEADER_Y);

        // Fame badge text — centered inside the gold pill drawn earlier
        font.setColor(new Color(1.00f, 0.85f, 0.42f, 1f));
        final String fameStr = (fame > 0L) ? ("+ " + fame + " FAME") : "+ 0 FAME";
        GlyphLayout fameLayout = new GlyphLayout(font, fameStr);
        float fameTextX = panelStartX + (panelWidth - fameLayout.width) / 2f;
        font.draw(batch, fameStr, fameTextX, FAME_Y + FAME_H - 7);
        font.setColor(Color.WHITE);

        Stats stats = this.playState.getPlayer().getComputedStats();
        font.setColor(this.playState.getPlayer().isStatMaxed(3) ? Color.YELLOW : Color.WHITE);
        font.draw(batch, "ATT " + stats.getAtt(), textX, startY);
        font.setColor(this.playState.getPlayer().isStatMaxed(4) ? Color.YELLOW : Color.WHITE);
        font.draw(batch, "SPD " + stats.getSpd(), textX, startY + (1 * yOffset));
        font.setColor(this.playState.getPlayer().isStatMaxed(6) ? Color.YELLOW : Color.WHITE);
        font.draw(batch, "VIT " + stats.getVit(), textX, startY + (2 * yOffset));
        font.setColor(this.playState.getPlayer().isStatMaxed(2) ? Color.YELLOW : Color.WHITE);
        font.draw(batch, "DEF " + stats.getDef(), textX + colGap, startY);
        font.setColor(this.playState.getPlayer().isStatMaxed(5) ? Color.YELLOW : Color.WHITE);
        font.draw(batch, "DEX " + stats.getDex(), textX + colGap, startY + (1 * yOffset));
        font.setColor(this.playState.getPlayer().isStatMaxed(7) ? Color.YELLOW : Color.WHITE);
        font.draw(batch, "WIS " + stats.getWis(), textX + colGap, startY + (2 * yOffset));
        font.setColor(Color.WHITE);
    }

    // ===================================================================
    // Sprite HUD prototype (panel.hud.main only, for visual validation).
    // ===================================================================

    /**
     * Comprehensive sprite-HUD renderer — webclient parity layout. Draws all
     * 8 panels at their final-target screen positions, populates them with
     * live game data, and repositions the existing slot {@link Button}
     * instances so click/drag hit-tests land at the new screen coordinates.
     *
     * Layout:
     *   top-left      panel.container.large  →  minimap viewport
     *   top-right     panel.container.small  →  name / fame / HP-MP-XP bars
     *   below ↑       panel.container.small  →  8-stat grid
     *   below ↑       panel.hud.main         →  player view + equip ring + 4×4 inv
     *   below ↑       panel.hud.inv_ext      →  ground loot (when present)
     *   bottom-center panel.hud.equipment    →  4-slot equipment hotbar (mirror inv 0..3)
     *   flank ↑       panel.hud.potion ×2    →  HP / MP potions
     *   bottom-left   panel.container.small  →  chat
     *
     * Coordinate system: PlayerUI uses Y-down (camera setToOrtho yDown=true),
     * so child sheet-Y offsets translate directly to screen-Y.
     */
    private void renderSpriteHud(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        if (!UiAtlas.isReady()) return;
        final int s = UiAtlas.getDisplayScale();
        final int W = OpenRealmGame.width;
        final int H = OpenRealmGame.height;
        final int margin = 16;

        // Minimap + chat both use the LARGE container. Single lookup so the
        // two can't drift out of sync.
        final UiComponent cLarge   = UiAtlas.componentOf("panel.container.large");
        final UiComponent cSmall   = UiAtlas.componentOf("panel.container.small");
        final UiComponent cMain    = UiAtlas.componentOf("panel.hud.main");
        final UiComponent cInvExt  = UiAtlas.componentOf("panel.hud.inv_ext");
        final UiComponent cHotbar  = UiAtlas.componentOf("panel.hud.equipment");
        final UiComponent cPotion  = UiAtlas.componentOf("panel.hud.potion");
        if (cMain == null || cSmall == null || cLarge == null) return;
        final UiComponent cMinimap = cLarge;

        // ---- Compute panel screen positions ----
        // New layout:
        //   LEFT  : [preview+bars] - [nearby players] - [chat] (top-down)
        //   RIGHT : [minimap]      - [main inventory] - [loot ext] (top-down)
        // The player sprite preview lives in the LEFT preview panel; the
        // stats list lives inside main.player (the viewport rect that
        // previously held the sprite). Minimap moved from top-left to
        // top-right above the inventory.
        final float smallW = cSmall.getW() * s;
        final float smallH = cSmall.getH() * s;

        final float mainW = cMain.getW() * s;
        final float mainH = cMain.getH() * s;
        final float mainX = W - margin - mainW;

        // RIGHT column: minimap pinned to the top-right; main inventory below.
        // cMinimap == cLarge here so it's always non-null after the early
        // return above; left as direct field accesses (no null-fallback) so
        // mainY can't accidentally collapse to the top of the screen.
        final float minimapW = cMinimap.getW() * s;
        final float minimapH = cMinimap.getH() * s;
        final float minimapX = W - margin - minimapW;
        final float minimapY = margin;
        final float mainY = minimapY + minimapH + 8;

        // LEFT column: preview chrome at top, nearby panel below. Each uses
        // panel.container.small for chrome — same width as the right-column
        // mini-panels so the layout reads as paired columns.
        final float previewX = margin;
        final float previewY = margin;
        final float nearbyX  = margin;
        final float nearbyY  = previewY + smallH + 8;

        final boolean lootVisible = !this.isGroundLootEmpty();
        final float invExtW = cInvExt != null ? cInvExt.getW() * s : 0;
        final float invExtH = cInvExt != null ? cInvExt.getH() * s : 0;
        final float invExtX = W - margin - invExtW;
        final float invExtY = mainY + mainH + 4;

        final float hotbarW = cHotbar != null ? cHotbar.getW() * s : 0;
        final float hotbarH = cHotbar != null ? cHotbar.getH() * s : 0;
        final float hotbarX = (W - hotbarW) / 2f;
        final float hotbarY = H - margin - hotbarH;

        final float potionW = cPotion != null ? cPotion.getW() * s : 0;
        final float potionH = cPotion != null ? cPotion.getH() * s : 0;
        final float hpPotX = hotbarX - 8 - potionW;
        final float hpPotY = hotbarY + (hotbarH - potionH) / 2f;
        final float mpPotX = hotbarX + hotbarW + 8;
        final float mpPotY = hpPotY;

        // Chat uses the LARGE container, anchored bottom-left underneath the
        // nearby panel.
        final float largeW = cLarge.getW() * s;
        final float largeH = cLarge.getH() * s;
        final float chatX = margin;
        final float chatY = H - margin - largeH;

        // ---- Pass 1: panel chrome (atlas blits) ----
        final TextureRegion rMinimap = UiAtlas.region("panel.container.large");
        final TextureRegion rSmall   = UiAtlas.region("panel.container.small");
        final TextureRegion rMain    = UiAtlas.region("panel.hud.main");
        final TextureRegion rInvExt  = UiAtlas.region("panel.hud.inv_ext");
        final TextureRegion rHotbar  = UiAtlas.region("panel.hud.equipment");
        final TextureRegion rPotion  = UiAtlas.region("panel.hud.potion");

        if (rMinimap != null) batch.draw(rMinimap, minimapX, minimapY, minimapW, minimapH);
        if (rSmall   != null) {
            batch.draw(rSmall, previewX, previewY, smallW, smallH); // preview + bars
            batch.draw(rSmall, nearbyX,  nearbyY,  smallW, smallH); // nearby players
        }
        // Chat uses the LARGE container chrome — but only when chat is
        // expanded. Collapsed → the entire panel disappears (PlayerChat
        // keeps just the toggle button visible so the player can reopen).
        final boolean chatExpanded = (this.playerChat != null) && !this.playerChat.isCollapsed();
        if (rMinimap != null && chatExpanded) batch.draw(rMinimap, chatX, chatY, largeW, largeH);
        if (rMain != null) batch.draw(rMain, mainX, mainY, mainW, mainH);
        if (lootVisible && rInvExt != null) batch.draw(rInvExt, invExtX, invExtY, invExtW, invExtH);
        if (rHotbar != null) batch.draw(rHotbar, hotbarX, hotbarY, hotbarW, hotbarH);
        if (rPotion != null) {
            batch.draw(rPotion, hpPotX, hpPotY, potionW, potionH);
            batch.draw(rPotion, mpPotX, mpPotY, potionW, potionH);
        }

        // ---- Pass 2: player class sprite (LEFT preview panel) ----
        // Sprite hugs the left edge of the preview chrome; name + bars stack
        // to its right. Layout mirrors the example image: small character
        // portrait, label+bars column to the right.
        final int previewPad = 10;
        final int spriteCell = (int) Math.min(smallH - previewPad * 2, 64);
        final float spriteX = previewX + previewPad;
        final float spriteY = previewY + (smallH - spriteCell) / 2f;
        if (this.playState != null && this.playState.getPlayer() != null) {
            final Color prev = batch.getColor();
            batch.setColor(Color.WHITE);
            try {
                final Player p = this.playState.getPlayer();
                // Use the class's STATIC idle_front frame from animations data
                // — NOT the live spritesheet's current frame, which would
                // mirror the player's in-world walk/attack animation in
                // the HUD. Falls back to getCurrentFrame() if anim data
                // hasn't loaded yet.
                final TextureRegion frame = this.getHudIdleFrame(p);
                if (frame != null && frame.getRegionWidth() > 0) {
                    batch.draw(frame, spriteX, spriteY, spriteCell, spriteCell);
                }
            } catch (Exception ignore) { /* sprite sheet not ready yet */ }
            batch.setColor(prev);
        }

        // ---- Pass 3: inventory + equipment slots — items rendered AND
        //              backing Buttons repositioned so click/drag hits land here.
        // Equipment ring (panel.hud.main.equip.0..3) — inventory[0..3].
        // No flips, no shifts — children render at their JSON-defined
        // positions relative to the panel chrome.
        for (int i = 0; i < 4; i++) {
            final UiComponent eq = UiAtlas.componentOf("panel.hud.main.equip." + i);
            if (eq == null) continue;
            final float ex = mainX + (eq.getX() - cMain.getX()) * s;
            final float ey = mainY + (eq.getY() - cMain.getY()) * s;
            this.repositionSlotButton(this.inventory, i, ex, ey);
            this.drawHudItemIcon(batch, this.getInventoryItem(i),
                    ex, ey, eq.getW() * s, eq.getH() * s);
        }

        // 4×4 main inventory grid — inventory[4..19].
        final int[][] cells = UiAtlas.gridCells("panel.hud.main.grid");
        for (int i = 0; i < cells.length; i++) {
            final int[] cell = cells[i];
            final float cx = mainX + (cell[0] - cMain.getX()) * s;
            final float cy = mainY + (cell[1] - cMain.getY()) * s;
            this.repositionSlotButton(this.inventory, 4 + i, cx, cy);
            this.drawHudItemIcon(batch, this.getInventoryItem(4 + i),
                    cx, cy, cell[2] * s, cell[3] * s);
        }

        // Equipment hotbar (bottom-center) — 1×4 mirroring inventory[0..3].
        // Same backing Slots so hit-testing on either spot edits the same item;
        // we don't re-position the Button here (the equip-ring above already
        // owns each slot's pos). We just draw the icon a second time.
        final int[][] hotbarCells = UiAtlas.gridCells("panel.hud.equipment.grid");
        for (int i = 0; i < hotbarCells.length && i < 4; i++) {
            final int[] cell = hotbarCells[i];
            final float cx = hotbarX + (cell[0] - cHotbar.getX()) * s;
            final float cy = hotbarY + (cell[1] - cHotbar.getY()) * s;
            this.drawHudItemIcon(batch, this.getInventoryItem(i),
                    cx, cy, cell[2] * s, cell[3] * s);
        }

        // Loot extension (panel.hud.inv_ext) — 8 ground-loot slots when present.
        if (lootVisible && cInvExt != null) {
            final int[][] lootCells = UiAtlas.gridCells("panel.hud.inv_ext.grid");
            for (int i = 0; i < lootCells.length && i < this.groundLoot.length; i++) {
                final int[] cell = lootCells[i];
                final float cx = invExtX + (cell[0] - cInvExt.getX()) * s;
                final float cy = invExtY + (cell[1] - cInvExt.getY()) * s;
                this.repositionSlotButton(this.groundLoot, i, cx, cy);
                final GameItem gi = (this.groundLoot[i] != null) ? this.groundLoot[i].getItem() : null;
                this.drawHudItemIcon(batch, gi, cx, cy, cell[2] * s, cell[3] * s);
            }
        }

        // ---- Pass 4: HP / MP / XP bars in the LEFT preview panel ----
        // Bars stack to the right of the player sprite. Name occupies the
        // top ~18 px of that column, then bars start at previewY+36 with
        // a clear gap so the name's descender doesn't kiss the HP bar.
        final int barX = (int)(spriteX + spriteCell + 10);
        final int barW = (int)(previewX + smallW - barX - previewPad);
        final int barH = 22;            // thicker so HP/MP/Fame text has padding
        final int barStride = barH + 4; // 4-px gap between bars
        final int barTop = (int)(previewY + 38);  // pushed down so name+level have a clear gap above the HP bar
        if (this.hp != null) { this.hp.getPos().x = barX; this.hp.getPos().y = barTop;                 this.hp.setBarWidth(barW); this.hp.setBarHeight(barH); }
        if (this.mp != null) { this.mp.getPos().x = barX; this.mp.getPos().y = barTop + barStride;     this.mp.setBarWidth(barW); this.mp.setBarHeight(barH); }
        if (this.xp != null) { this.xp.getPos().x = barX; this.xp.getPos().y = barTop + barStride * 2; this.xp.setBarWidth(barW); this.xp.setBarHeight(barH); }
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        if (this.hp != null) this.hp.renderShapes(shapes);
        if (this.mp != null) this.mp.renderShapes(shapes);
        if (this.xp != null) this.xp.renderShapes(shapes);
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
        batch.begin();
        if (this.hp != null) this.hp.renderText(batch, font);
        if (this.mp != null) this.mp.renderText(batch, font);
        if (this.xp != null) this.xp.renderText(batch, font);

        // ---- Pass 5: name + level (top of LEFT preview panel),
        //              8-stat grid inside main.player viewport (RIGHT) ----
        if (this.playState != null && this.playState.getPlayer() != null) {
            font.setColor(0.78f, 0.66f, 0.43f, 1f); // tan accent
            String nameLine = this.playState.getPlayer().getName() != null
                    ? this.playState.getPlayer().getName()
                    : "Player";
            int lvl = 1;
            try { lvl = GameDataManager.EXPERIENCE_LVLS.getLevel(this.playState.getPlayer().getExperience()); }
            catch (Exception ignore) { /* xp data not yet loaded */ }
            // Anchor name to the TOP of the preview panel (well above
            // barTop=previewY+36) so the text's descender doesn't overlap
            // the HP bar. Smaller scale so a long name + level fits in the
            // ~140-px column to the right of the sprite.
            final float origNameScale = font.getData().scaleX;
            font.getData().setScale(0.85f);
            // Name baseline at previewY+14 so the descender clears the
            // HP bar (top at previewY+38) by ~10 px instead of touching it.
            font.draw(batch, nameLine + "  Lv " + lvl, barX, previewY + 14);
            font.getData().setScale(origNameScale);
            font.setColor(Color.WHITE);
        }
        // Stats grid lives in the band between the LEFT and RIGHT equip-ring
        // columns at the top of panel.hud.main — wider than the cramped
        // 96-px player viewport, so 2-column "ATT 60 / DEF 56" text actually
        // fits without overflow.
        final UiComponent eqNW = UiAtlas.componentOf("panel.hud.main.equip.0");
        final UiComponent eqNE = UiAtlas.componentOf("panel.hud.main.equip.1");
        final UiComponent eqSW = UiAtlas.componentOf("panel.hud.main.equip.2");
        if (eqNW != null && eqNE != null && eqSW != null) {
            final float statsX = mainX + (eqNW.getX() + eqNW.getW() - cMain.getX()) * s + 4;
            final float statsRight = mainX + (eqNE.getX() - cMain.getX()) * s - 4;
            final float statsY = mainY + (eqNW.getY() - cMain.getY()) * s;
            this.renderStatsAt(batch, font, (int) statsX,
                    (int) (statsRight - statsX), (int) statsY);
        }

        // ---- Pass 5b: difficulty + account fame badges (pill style,
        //               webclient parity) to the RIGHT of the preview. ----
        this.renderBadgesNextToPreview(batch, shapes, font,
                previewX + smallW, previewY);

        // ---- Pass 6: potion icons + counts inside the oval panels ----
        this.drawPotionWidget(batch, font, hpPotX, hpPotY, potionW, potionH, true);
        this.drawPotionWidget(batch, font, mpPotX, mpPotY, potionW, potionH, false);

        // ---- Pass 7: minimap into panel.container.large (top-RIGHT) ----
        if (this.minimap != null) {
            final int mmInset = 12;
            final int mmSize = (int) Math.min(minimapW - mmInset * 2, minimapH - mmInset * 2);
            this.minimap.setLayout((int)(minimapX + mmInset), (int)(minimapY + mmInset), Math.max(32, mmSize));
        }

        // ---- Pass 8: chat into the bottom-left large panel ----
        // Pass the FULL chrome rect (no inset) so chat content fills the
        // container edge-to-edge — the panel.container.large sprite has
        // its own visible border, no need for an extra padding ring.
        if (this.playerChat != null) {
            this.playerChat.setLayout(
                (int) chatX, (int) chatY,
                (int) largeW, (int) largeH);
        }

        // ---- Pass 9: nearby players panel coords (rendered later in
        //              the main render path so its hover tooltip can layer
        //              over earlier passes). ----
        this.spriteHudNearbyX = (int)(nearbyX + 8);
        this.spriteHudNearbyW = (int)(smallW - 16);
        this.spriteHudNearbyY = (int)(nearbyY + 8);
        this.spriteHudNearbyEnabled = true;
    }

    // Sprite HUD — nearby panel position passed to renderNearbyPlayers().
    private int spriteHudNearbyX = 0;
    private int spriteHudNearbyY = 0;
    private int spriteHudNearbyW = 0;
    private boolean spriteHudNearbyEnabled = false;

    /** Move the inventory or groundLoot slot's {@link Button} to a new screen
     *  position so existing click/right-click handlers fire at the sprite-HUD
     *  location instead of the legacy sidebar position. The Button's bounds
     *  Rectangle holds pos by reference, so just mutating pos updates hits. */
    private void repositionSlotButton(Slots[] arr, int idx, float x, float y) {
        if (arr == null || idx < 0 || idx >= arr.length) return;
        final Slots slot = arr[idx];
        if (slot == null || slot.getButton() == null) return;
        // If the slot is currently being dragged, the button pos should follow
        // the cursor; let the existing drag logic handle that and skip here.
        if (slot.getDragPos() != null) return;
        slot.getButton().getPos().x = x;
        slot.getButton().getPos().y = y;
    }

    /** itemId of the bundled HP/MP potion entries in game-items.json.
     *  Used by the bottom-bar potion oval renderer to look up the actual
     *  potion sprite (so the side panels show a small bottle, not text). */
    private static final int HP_POTION_ITEM_ID = 296;
    private static final int MP_POTION_ITEM_ID = 297;

    /** Render a potion oval's contents — small potion bottle sprite drawn
     *  on the LEFT half of the oval, count text drawn on the RIGHT half.
     *  Mirrors the webclient {@code #hp-potion-slot} / {@code #mp-potion-slot}. */
    private void drawPotionWidget(SpriteBatch batch, BitmapFont font,
                                   float x, float y, float w, float h, boolean hp) {
        if (this.playState == null || this.playState.getPlayer() == null) return;
        final Player p = this.playState.getPlayer();
        final int count = hp ? p.getHpPotions() : p.getMpPotions();
        final int itemId = hp ? HP_POTION_ITEM_ID : MP_POTION_ITEM_ID;

        // Potion sprite — 8 px source × 2 (icon at HUD scale) = 16px display.
        // Anchored to the left side of the oval with a small inset.
        final float iconSize = Math.min(h - 4, 18f);
        TextureRegion icon = GameSpriteManager.ITEM_SPRITES != null
                ? GameSpriteManager.ITEM_SPRITES.get(itemId) : null;
        if (icon == null) {
            // Lazy-load if the item def hasn't been processed yet.
            final GameItem item = GameDataManager.GAME_ITEMS != null
                    ? GameDataManager.GAME_ITEMS.get(itemId) : null;
            if (item != null) {
                GameDataManager.loadSpriteModel(item);
                icon = GameSpriteManager.ITEM_SPRITES.get(itemId);
            }
        }
        if (icon != null) {
            batch.draw(icon, x + 4, y + (h - iconSize) / 2f, iconSize, iconSize);
        }

        // Count text on the right half of the oval.
        font.setColor(Color.WHITE);
        final String label = String.valueOf(count);
        final GlyphLayout gl = new GlyphLayout(font, label);
        font.draw(batch, label, x + w - gl.width - 6, y + (h + gl.height) / 2f);
    }

    /** Variant of {@link #renderStats(SpriteBatch, BitmapFont)} that takes
     *  explicit panel bounds so the 2-column grid renders inside the
     *  sprite-HUD's statsBot panel. */
    private void renderStatsAt(SpriteBatch batch, BitmapFont font,
                                int startX, int panelWidth, int statsY) {
        if (this.playState == null || this.playState.getPlayer() == null) return;
        final Player p = this.playState.getPlayer();
        final Stats computed = p.getComputedStats();
        final Stats base     = p.getStats();
        if (computed == null) return;
        // Six non-HP/MP stats stacked in a SINGLE column, each rendered as
        // "LBL VAL  +BONUS" inline. White label+value, green bonus when
        // positive, red when negative. Mirrors webclient.
        // Smaller font (0.6) + 13-px line height — the 0.85 scale was
        // bleeding past the equip-slot columns and looked overly large
        // for the cramped band between them.
        final float origScale = font.getData().scaleX;
        font.getData().setScale(0.6f);
        final int yOffset = 13;
        final int textX   = startX;
        final int startY  = statsY + 12;

        // Stat order: ATT, DEF, SPD, DEX, VIT, WIS — webclient parity.
        final int[]    statMaxedIdx = {  3,    6,    4,    5,    2,    7  };
        final String[] statLabels   = { "ATT", "DEF", "SPD", "DEX", "VIT", "WIS" };
        final int[] computedVals = { computed.getAtt(), computed.getDef(),
                computed.getSpd(), computed.getDex(), computed.getVit(),
                computed.getWis() };
        final int[] baseVals = (base != null)
                ? new int[] { base.getAtt(), base.getDef(), base.getSpd(),
                              base.getDex(), base.getVit(), base.getWis() }
                : computedVals; // fall back: no bonus shown if base missing

        final GlyphLayout gl = new GlyphLayout();
        for (int i = 0; i < statLabels.length; i++) {
            final int y = startY + yOffset * i;
            final int compV = computedVals[i];
            final int baseV = baseVals[i];
            final int bonus = compV - baseV;
            // Label + value (white, or gold if maxed).
            font.setColor(p.isStatMaxed(statMaxedIdx[i]) ? Color.YELLOW : Color.WHITE);
            final String main = statLabels[i] + " " + compV;
            font.draw(batch, main, textX, y);
            // Bonus inline, color-coded.
            if (bonus != 0) {
                gl.setText(font, main);
                final float bonusX = textX + gl.width + 4;
                if (bonus > 0) {
                    font.setColor(0.25f, 0.78f, 0.25f, 1f); // green
                    font.draw(batch, "+" + bonus, bonusX, y);
                } else {
                    font.setColor(0.91f, 0.31f, 0.31f, 1f); // red
                    font.draw(batch, String.valueOf(bonus), bonusX, y);
                }
            }
        }
        font.setColor(Color.WHITE);
        font.getData().setScale(origScale);
    }

    /** Render difficulty + account fame badges to the RIGHT of the preview
     *  panel, mirroring the webclient pill style:
     *    GREEN PILL: "☠ 1.0"        difficulty multiplier
     *    GOLD  PILL: "+ 99,022 FAME" account fame total
     *  Each is a small rounded-corner-ish rect with text centered. */
    private void renderBadgesNextToPreview(SpriteBatch batch, ShapeRenderer shapes,
                                            BitmapFont font,
                                            float previewRightX, float previewTopY) {
        if (this.playState == null || this.playState.getPlayer() == null) return;
        final Player p = this.playState.getPlayer();
        // Account fame source-of-truth = PlayState.account, populated from
        // GET /data/account/{uuid}. Mirrors the webclient (account.accountFame
        // from main.js's REST account payload). Fall back to other caches
        // if the REST account hasn't loaded yet.
        long accountFame = 0L;
        if (this.playState.getAccount() != null
                && this.playState.getAccount().getAccountFame() != null) {
            accountFame = this.playState.getAccount().getAccountFame();
        }
        if (accountFame == 0L && this.fameStoreWindow != null
                && this.fameStoreWindow.getAccountFame() > 0L) {
            accountFame = this.fameStoreWindow.getAccountFame();
        }
        if (accountFame == 0L) {
            accountFame = p.getCachedAccountFame();
        }
        float difficulty = 1.0f;
        try {
            if (this.playState.getRealmManager() != null
                    && this.playState.getRealmManager().getRealm() != null) {
                difficulty = this.playState.getRealmManager().getRealm().getDifficulty();
            }
        } catch (Exception ignored) {}

        // Pre-measure text so pill widths actually FIT the content (FAME
        // values can hit 6-7 digits, e.g. "99,022 FAME" overflowed the
        // previous 130-px pill leaving the leading digit outside the chrome).
        final float origScale = font.getData().scaleX;
        font.getData().setScale(1.0f);
        final GlyphLayout glDiff = new GlyphLayout();
        final GlyphLayout glFame = new GlyphLayout();
        final String diffStr = "DIFF " + String.format("%.1f", difficulty);
        final String fameStr = String.format("%,d FAME", accountFame);
        glDiff.setText(font, diffStr);
        glFame.setText(font, fameStr);

        // Per-pill width = text + 32 px horizontal padding (16 each side).
        // Stack uses the WIDER of the two so both pills line up flush-right.
        final float padX = 16f;
        final float pillH = 30;
        final float pillGap = 6;
        final float pillW = Math.max(glDiff.width, glFame.width) + padX * 2;
        final float pillStackH = pillH * 2 + pillGap;
        final float previewH = 148; // panel.container.small at displayScale 2
        final float pillX = previewRightX + 8;
        final float diffY = previewTopY + (previewH - pillStackH) / 2f;
        final float fameY = diffY + pillH + pillGap;

        // ShapeRenderer pass — solid backgrounds matching the webclient
        // #difficulty-icon / #account-fame-display CSS rgba values.
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(180/255f, 60/255f, 60/255f, 1.0f);
        shapes.rect(pillX, diffY, pillW, pillH);
        shapes.setColor(50/255f, 38/255f, 24/255f, 1.0f);
        shapes.rect(pillX, fameY, pillW, pillH);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(160/255f, 64/255f, 64/255f, 1f);
        shapes.rect(pillX, diffY, pillW, pillH);
        shapes.setColor(200/255f, 168/255f, 110/255f, 1f);
        shapes.rect(pillX, fameY, pillW, pillH);
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
        batch.begin();

        // Manual horizontal centering using the GlyphLayout we already
        // computed — Align.center wasn't reliably centering for some font
        // configurations. Vertical baseline = pill center + half-cap-height.
        final float capH = font.getCapHeight();
        final float diffTextX = pillX + (pillW - glDiff.width) / 2f;
        final float fameTextX = pillX + (pillW - glFame.width) / 2f;
        final float baselineDiff = diffY + (pillH + capH) / 2f;
        final float baselineFame = fameY + (pillH + capH) / 2f;

        font.setColor(1f, 1f, 1f, 1f);
        font.draw(batch, diffStr, diffTextX, baselineDiff);

        font.setColor(255/255f, 216/255f, 107/255f, 1f);
        font.draw(batch, fameStr, fameTextX, baselineFame);

        font.setColor(Color.WHITE);
        font.getData().setScale(origScale);
    }

    /** Static idle-front frame for the HUD preview canvas. Pulls the row/col
     *  for {@code idle_front} (or {@code idle_side} as fallback) from the
     *  class's animation data and builds a one-off TextureRegion off the
     *  class sheet — independent of the in-world Player.spriteSheet, so
     *  walking / attacking doesn't animate the HUD avatar. */
    private final java.util.Map<String, com.badlogic.gdx.graphics.g2d.TextureRegion> _hudIdleCache
            = new java.util.HashMap<>();
    private com.badlogic.gdx.graphics.g2d.TextureRegion getHudIdleFrame(Player p) {
        if (p == null) return null;
        final int classId = p.getClassId();
        final com.openrealm.game.model.AnimationModel anim =
                (com.openrealm.game.data.GameDataManager.ANIMATIONS != null)
                ? com.openrealm.game.data.GameDataManager.ANIMATIONS.get(classId) : null;
        if (anim == null || anim.getAnimations() == null) {
            // Fallback to the live current frame so SOMETHING shows up
            // until anim data lands.
            return (p.getSpriteSheet() != null) ? p.getSpriteSheet().getCurrentFrame() : null;
        }
        com.openrealm.game.model.AnimationSetModel set = anim.getAnimations().get("idle_front");
        if (set == null || set.getFrames() == null || set.getFrames().isEmpty()) {
            set = anim.getAnimations().get("idle_side");
        }
        if (set == null || set.getFrames() == null || set.getFrames().isEmpty()) return null;
        final com.openrealm.game.model.AnimationFrameModel f = set.getFrames().get(0);
        final String key = classId + ":" + f.getRow() + ":" + f.getCol();
        com.badlogic.gdx.graphics.g2d.TextureRegion cached = _hudIdleCache.get(key);
        if (cached != null) return cached;
        final com.badlogic.gdx.graphics.Texture tex = (com.openrealm.game.data.GameSpriteManager.TEXTURE_CACHE != null)
                ? com.openrealm.game.data.GameSpriteManager.TEXTURE_CACHE.get(anim.getSpriteKey()) : null;
        if (tex == null) return null;
        final int spW = anim.getSpriteSize();
        final int spH = anim.getEffectiveSpriteHeight();
        final com.badlogic.gdx.graphics.g2d.TextureRegion region =
                new com.badlogic.gdx.graphics.g2d.TextureRegion(
                        tex, f.getCol() * spW, f.getRow() * spH, spW, spH);
        // Match GameSpriteManager's flip convention so the avatar isn't
        // upside-down on the y-down camera.
        region.flip(false, true);
        _hudIdleCache.put(key, region);
        return region;
    }

    /** Inventory accessor that handles both slot-list and direct backing
     *  array shapes. Returns null for empty / out-of-range. */
    private GameItem getInventoryItem(int idx) {
        if (this.inventory == null || idx < 0 || idx >= this.inventory.length) return null;
        final Slots slot = this.inventory[idx];
        if (slot == null) return null;
        return slot.getItem();
    }

    /** Centered item icon at iconScale × source-size (32×32 for 8px sprites).
     *  Mirrors {@link Slots#renderItem} so per-item enchant overlays still
     *  show up. No-op when the cell is empty. */
    private void drawHudItemIcon(SpriteBatch batch, GameItem item,
                                  float x, float y, float w, float h) {
        if (item == null || item.getItemId() == -1) return;
        if (item.getSpriteKey() == null) {
            GameDataManager.loadSpriteModel(item);
        }
        TextureRegion icon = SpriteRecolorCache.getEnchantedItemRegion(item);
        if (icon == null) icon = GameSpriteManager.ITEM_SPRITES.get(item.getItemId());
        if (icon == null) return;
        // 8px source × iconScale(2) × displayScale(2) = 32px on screen.
        final float iconSize = 8f * UiAtlas.getIconScale() * UiAtlas.getDisplayScale();
        batch.draw(icon, x + (w - iconSize) / 2f, y + (h - iconSize) / 2f, iconSize, iconSize);
    }
}
