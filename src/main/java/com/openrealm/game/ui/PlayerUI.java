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
     * The webclient layout (name → fame → minimap → bars → stats → equip →
     * bags → potions → nearby) collapses gracefully on shorter windows; we
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
        this.layoutBagTabY  = this.layoutEquipY + SLOT_SIZE + 10;
        this.layoutBag1Y    = this.layoutBagTabY + 24 + 4;
        this.layoutBag2Y    = this.layoutBag1Y + SLOT_SIZE + SLOT_GAP;
        this.layoutPotionY  = this.layoutBag2Y + SLOT_SIZE + 10;
        this.layoutNearbyY  = this.layoutPotionY + 56 + 14;
    }

    /** Screen X for the given slot column 0..3, evenly distributed across
     *  the right HUD column width with a small inset. */
    private int slotX(int col) {
        final int panelW = OpenRealmGame.width / 5;
        final int startX = OpenRealmGame.width - panelW;
        final int usable = panelW - 2 * PANEL_INSET;
        final int totalSlots = 4 * SLOT_SIZE + 3 * SLOT_GAP;
        final int leftPad = (usable - totalSlots) / 2;
        return startX + PANEL_INSET + leftPad + col * (SLOT_SIZE + SLOT_GAP);
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

        GameItem[] equipmentArr = Arrays.copyOfRange(loot, 0, 4);
        GameItem[] inventoryArr = Arrays.copyOfRange(loot, 4, 12);

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

        // Color palette — mirrors webclient style.css (#1a1218cc panels,
        // #3a2a38 borders, #c8a86e tan accent). Pulled here so any tweak
        // touches one spot.
        final Color cPanel  = new Color(0.10f, 0.07f, 0.09f, 0.95f);
        final Color cBorder = new Color(0.23f, 0.16f, 0.22f, 1f);
        final Color cAccent = new Color(0.78f, 0.66f, 0.43f, 1f);
        final Color cMuted  = new Color(0.53f, 0.47f, 0.41f, 1f);

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

        // Equipment slot backgrounds
        for (int i = 0; i < equips.length; i++) {
            Slots curr = equips[i];
            if (curr != null) {
                Vector2f pos = slotPositions[i];
                if (curr.getDragPos() != null) { pos.x = curr.getDragPos().x; pos.y = curr.getDragPos().y; }
                else { pos.x = this.slotX(i); pos.y = this.layoutEquipY; }
                curr.renderBackground(shapes, pos);
            }
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

        // Inventory row 1 backgrounds (top row of the active bag)
        for (int i = 0; i < inv1.length; i++) {
            Slots curr = inv1[i];
            if (curr != null) {
                Vector2f pos = slotPositions[4 + i];
                if (curr.getDragPos() != null) { pos.x = curr.getDragPos().x; pos.y = curr.getDragPos().y; }
                else { pos.x = this.slotX(i); pos.y = this.layoutBag1Y; }
                curr.renderBackground(shapes, pos);
            }
        }

        // Inventory row 2 backgrounds
        for (int i = 0; i < inv2.length; i++) {
            Slots curr = inv2[i];
            if (curr != null) {
                Vector2f pos = slotPositions[8 + i];
                if (curr.getDragPos() != null) { pos.x = curr.getDragPos().x; pos.y = curr.getDragPos().y; }
                else { pos.x = this.slotX(i); pos.y = this.layoutBag2Y; }
                curr.renderBackground(shapes, pos);
            }
        }

        // Ground loot slot backgrounds (anchored to bottom of screen)
        if (!this.isTrading) {
            for (int i = 0; i < this.groundLoot.length; i++) {
                Slots curr = this.groundLoot[i];
                if (curr != null) {
                    Vector2f pos = groundLootPositions[i];
                    int row = i > 3 ? 1 : 0;
                    int col = i > 3 ? i - 4 : i;
                    if (curr.getDragPos() != null) { pos.x = curr.getDragPos().x; pos.y = curr.getDragPos().y; }
                    else { pos.x = this.slotX(col); pos.y = this.groundLootRowY(row); }
                    curr.renderBackground(shapes, pos);
                }
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

        // EQUIPMENT label (small caps, muted)
        font.setColor(cMuted);
        font.draw(batch, "EQUIPMENT", startX + PANEL_INSET, this.layoutEquipY - 6);
        font.setColor(Color.WHITE);

        // BAG tab labels — centered with GlyphLayout
        GlyphLayout gl = new GlyphLayout();
        float bagY = tabY + 16;
        font.setColor(this.activeBag == 0 ? cAccent : cMuted);
        gl.setText(font, "BAG 1");
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

        // Trade UI (still uses old rendering for now - it's conditional/rare)
        if (this.isTrading) {
            this.renderTradeUI(batch, shapes, font, startX, panelWidth);
        }

        // Render nearby players list
        this.renderNearbyPlayers(batch, shapes, font, startX, panelWidth);

        if (this.activeTooltip != null) {
            this.activeTooltip.render(batch, shapes, font);
        }

        this.renderPlayerTooltip(batch, shapes, font);
        this.renderStats(batch, font);
        this.renderPortalPrompt(batch, font);
        this.playerChat.render(batch, shapes, font);

        if (this.minimap.isInitialized()) {
            // Square minimap at the top of the right HUD column (under the
            // name + fame badge), mirroring the webclient #minimap-container.
            final int hudPanelW = OpenRealmGame.width / 5;
            final int hudPanelX = OpenRealmGame.width - hudPanelW;
            final int size = Math.max(96, Math.min(hudPanelW - 2 * PANEL_INSET,
                    OpenRealmGame.height / 4));
            this.minimap.setLayout(hudPanelX + PANEL_INSET, this.layoutMinimapY, size);
            this.minimap.update();
            this.minimap.render(batch, shapes);
        }

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

        // Dev-stats overlay (FPS / MEM / PING / JITTER) — mirrors web client
        // _perfEl. Anchored to the top-LEFT corner since the minimap moved
        // into the right HUD column; left corner is now free real-estate
        // and putting stats over the HUD would fight with the minimap.
        // Cheap — four font.draw calls per frame.
        PerfMetrics.get().onFrame();
        PerfMetrics.get().render(batch, font, 80f, 8f);
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
        int iconSize = 20;
        int entryHeight = 26;
        int colWidth = (panelWidth - 12) / 2;
        int startY = headerY + 16;

        List<Player> playerList = new ArrayList<>(nearby);
        List<Button> newButtons = new ArrayList<>();

        for (int i = 0; i < playerList.size() && i < 16; i++) {
            Player p = playerList.get(i);
            int col = i % 2;
            int row = i / 2;

            int x = startX + 4 + (col * (colWidth + 4));
            int y = startY + (row * entryHeight);

            Button btn = new Button(new Vector2f(x, y), iconSize);
            btn.getBounds().setWidth(colWidth);
            btn.getBounds().setHeight(entryHeight);
            final Player hoverTarget = p;
            btn.onHoverIn(event -> {
                this.hoveredPlayer = hoverTarget;
            });
            btn.onHoverOut(event -> {
                if (this.hoveredPlayer == hoverTarget) {
                    this.hoveredPlayer = null;
                }
            });
            newButtons.add(btn);
        }

        this.nearbyPlayerList = playerList;
        this.nearbyPlayerButtons = newButtons;
    }

    private void renderNearbyPlayers(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font, int startX, int panelWidth) {
        this.refreshNearbyPlayerButtons(startX, panelWidth);

        if (this.nearbyPlayerList.isEmpty()) return;

        int headerY = this.layoutNearbyY;
        font.setColor(Color.WHITE);
        font.draw(batch, "Nearby Players", startX + 4, headerY);

        int iconSize = 20;
        int entryHeight = 26;
        int colWidth = (panelWidth - 12) / 2;
        int startY = headerY + 16;
        int maxNameChars = 10;

        for (int i = 0; i < this.nearbyPlayerList.size() && i < 16; i++) {
            Player p = this.nearbyPlayerList.get(i);
            int col = i % 2;
            int row = i / 2;

            int x = startX + 4 + (col * (colWidth + 4));
            int y = startY + (row * entryHeight);

            // Draw class icon
            TextureRegion icon = this.getClassIcon(p.getClassId());
            if (icon != null) {
                batch.draw(icon, x, y, iconSize, iconSize);
            }

            // Draw clipped player name
            String displayName = p.getName();
            if (displayName.length() > maxNameChars) {
                displayName = displayName.substring(0, maxNameChars) + "..";
            }

            // Highlight hovered player
            if (this.hoveredPlayer == p) {
                font.setColor(Color.YELLOW);
            } else {
                font.setColor(Color.WHITE);
            }
            font.draw(batch, displayName, x + iconSize + 4, y + 14);
        }
    }

    private void renderPlayerTooltip(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        if (this.hoveredPlayer == null) return;

        Player p = this.hoveredPlayer;
        int tooltipX = (OpenRealmGame.width / 2) + 75;
        int tooltipY = 100;
        int tooltipWidth = OpenRealmGame.width / 5;
        int tooltipHeight = 120;
        int spacing = 16;

        // Background
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(new Color(0.2f, 0.2f, 0.2f, 0.9f));
        shapes.rect(tooltipX, tooltipY, tooltipWidth, tooltipHeight);
        shapes.end();
        batch.begin();

        // Player name and class
        CharacterClass cls = CharacterClass.valueOf(p.getClassId());
        String className = cls != null ? cls.name() : "Unknown";
        font.setColor(Color.YELLOW);
        font.draw(batch, p.getName() + " (" + className + ")", tooltipX + 4, tooltipY + spacing);

        // HP and MP
        font.setColor(Color.RED);
        font.draw(batch, "HP: " + p.getHealth(), tooltipX + 4, tooltipY + (2 * spacing));
        font.setColor(Color.BLUE);
        font.draw(batch, "MP: " + p.getMana(), tooltipX + 100, tooltipY + (2 * spacing));

        // Equipped items label
        font.setColor(Color.WHITE);
        font.draw(batch, "Equipment:", tooltipX + 4, tooltipY + (3 * spacing));

        // Draw equipped item sprites (slots 0-3)
        GameItem[] equips = p.getSlots(0, 4);
        int itemX = tooltipX + 4;
        int itemY = tooltipY + (3 * spacing) + 4;
        int itemSize = 32;
        for (int i = 0; i < equips.length; i++) {
            if (equips[i] != null && equips[i].getItemId() != -1) {
                TextureRegion itemRegion = GameSpriteManager.ITEM_SPRITES.get(equips[i].getItemId());
                if (itemRegion != null) {
                    batch.draw(itemRegion, itemX + (i * (itemSize + 4)), itemY, itemSize, itemSize);
                }
            } else {
                // Draw empty slot placeholder
                batch.end();
                shapes.begin(ShapeRenderer.ShapeType.Line);
                shapes.setColor(Color.DARK_GRAY);
                shapes.rect(itemX + (i * (itemSize + 4)), itemY, itemSize, itemSize);
                shapes.end();
                batch.begin();
            }
        }
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

        boolean fromIsGround = fromIndex >= 20 && fromIndex <= 27;
        boolean targetIsGround = targetIndex >= 20 && targetIndex <= 27;
        boolean fromIsEquip = fromIndex >= 0 && fromIndex <= 3;
        boolean targetIsEquip = targetIndex >= 0 && targetIndex <= 3;

        if (targetIndex == -1) {
            // Dropped outside any slot: drop item
            this.playState.getRealmManager().moveItem(-1, fromIndex, true, false);
        } else if (fromIsGround && !targetIsGround) {
            // Ground -> inventory/equip: pickup
            this.playState.getRealmManager().moveItem(targetIndex, fromIndex, false, false);
        } else if (!fromIsGround && targetIsGround) {
            // Inventory/equip -> ground area: drop
            this.playState.getRealmManager().moveItem(-1, fromIndex, true, false);
        } else {
            // inv->equip, equip->inv, inv->inv: swap/equip/unequip
            this.playState.getRealmManager().moveItem(targetIndex, fromIndex, false, false);
        }
    }

    /**
     * Bottom-center "[Space] Use [Portal Name]" prompt — appears whenever
     * the local player is within ~32 px (1 tile) of a portal. Mirrors the
     * web client's nearby-portal interaction prompt. Press Space (or F2)
     * to enter the portal.
     */
    private void renderPortalPrompt(SpriteBatch batch, BitmapFont font) {
        if (this.playState == null || this.playState.getPlayer() == null) return;
        try {
            final Vector2f pPos = this.playState.getPlayer().getPos();
            if (pPos == null) return;
            final Portal nearest = this.playState.getClosestPortal(pPos, 32f);
            if (nearest == null) return;
            String name = "Portal";
            try {
                final PortalModel pm = GameDataManager.PORTALS != null
                        ? GameDataManager.PORTALS.get((int) nearest.getPortalId()) : null;
                if (pm != null && pm.getPortalName() != null && !pm.getPortalName().isEmpty()) {
                    name = pm.getPortalName();
                } else if (pm != null && pm.getLabel() != null && !pm.getLabel().isEmpty()) {
                    name = pm.getLabel();
                }
            } catch (Exception ignored) { /* fall back to generic label */ }
            final String prompt = "[Space] Use " + name;
            // Centered horizontally, ~80 px above the bottom of the screen.
            final int promptY = OpenRealmGame.height - 96;
            final int approxWidth = prompt.length() * 7;
            final int promptX = Math.max(0, OpenRealmGame.width / 2 - approxWidth / 2);
            font.setColor(0.95f, 0.85f, 0.45f, 1f);
            font.draw(batch, prompt, promptX, promptY);
            font.setColor(Color.WHITE);
        } catch (Exception ignored) { /* never block render on a UI hint */ }
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
}
