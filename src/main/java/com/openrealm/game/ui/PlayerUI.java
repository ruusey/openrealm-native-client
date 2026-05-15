package com.openrealm.game.ui;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.openrealm.game.OpenRealmGame;
import com.openrealm.game.contants.CharacterClass;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.data.GameSpriteManager;
import com.openrealm.game.graphics.Sprite;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.Stats;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.ItemTooltip;
import com.openrealm.game.model.ability.Ability;
import com.openrealm.game.state.PlayState;
import com.openrealm.game.state.RealmTransitionState;
import com.openrealm.net.client.packet.UpdatePlayerTradeSelectionPacket;
import com.openrealm.net.entity.NetInventorySelection;
import com.openrealm.net.entity.NetTradeSelection;
import com.openrealm.net.messaging.CommandType;
import com.openrealm.net.messaging.ServerCommandMessage;
import com.openrealm.net.server.packet.MoveItemPacket;
import com.openrealm.net.server.packet.PotionStorageMovePacket;
import com.openrealm.net.server.packet.SplitStackPacket;
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

    /** Local player's classId, or -1 if not in a game yet. Used by the
     *  ItemTooltip ctor to render the "Compatible / Cannot equip" row. */
    private int viewerClassId() {
        if (this.playState == null) return -1;
        final Player p = this.playState.getPlayer();
        return (p == null) ? -1 : p.getClassId();
    }
    private PlayerChat playerChat;
    private Minimap minimap;
    private long lastAction = Instant.now().toEpochMilli();
    private Button menuButton = null;

    private NetTradeSelection currentTradeSelection = null;
    private String tradePartnerName = null;
    /** Pending incoming trade request — name of the player who sent
     *  the /trade. Triggers an Accept / Decline popup (rendered under
     *  the right HUD column). Cleared on accept, decline, or 15s
     *  timeout (matches the server-side TTL). Webclient parity
     *  (trade.js showTradeRequestPopup). */
    @lombok.Getter @lombok.Setter
    private String pendingTradeRequestFrom = null;
    /** Phase 4 — party invite prompt state. Set by showPartyInvitePrompt
     *  when SYSTEM chat reports "X invited you to a party". Cleared on
     *  Accept/Decline click or 60s TTL (matches server invite eviction). */
    private String pendingPartyInviteFrom = null;
    private long   pendingPartyInviteExpiresAt = 0L;
    private Button partyInviteAcceptBtn = null;
    private Button partyInviteDeclineBtn = null;
    public void showPartyInvitePrompt(String inviterName) {
        this.pendingPartyInviteFrom = inviterName;
        this.pendingPartyInviteExpiresAt = System.currentTimeMillis() + 60_000L;
        // Force re-create on next render so the button click handlers bind
        // to the current inviter (not whoever was in the slot before).
        this.partyInviteAcceptBtn = null;
        this.partyInviteDeclineBtn = null;
    }
    @lombok.Getter @lombok.Setter
    private long pendingTradeRequestStartMs = 0L;
    /** Buttons for the pending-trade-request popup — Accept fires
     *  /accept, Decline fires /decline. Lazy built when the popup
     *  first renders so we know the panel position. */
    private Button tradeRequestAcceptBtn = null;
    private Button tradeRequestDeclineBtn = null;
    /** Wall-clock timestamp at which the trade overlay should close
     *  after a trade completes. Set to System.currentTimeMillis()+1000
     *  by {@link #scheduleTradeOverlayClose} so both players can see
     *  the dual-CONFIRMED state for a beat before the UI dismisses. 0
     *  means no scheduled close. */
    private long tradeOverlayCloseAtMs = 0L;

    /** Defer the trade overlay close by 1 second so the dual-CONFIRMED
     *  status is visible after a successful trade completes. Called
     *  from ClientGameLogic.handleAcceptTrade when the server sends an
     *  AcceptTradeRequestPacket(false) closing the trade — but we want
     *  to keep the overlay up briefly first. */
    public void scheduleTradeOverlayClose() {
        this.tradeOverlayCloseAtMs = System.currentTimeMillis() + 1000L;
    }
    /** Snapshot of the partner's inventory at trade-accept time. The
     *  on-wire trade-selection packets carry only Boolean[] flags (no
     *  items), so we cache the partner's full 20-slot inventory from
     *  AcceptTradeRequestPacket and use Boolean[] flags from
     *  {@link #currentTradeSelection} purely as overlay highlights.
     *  Webclient parity (game.tradePartnerInv).
     */
    @lombok.Getter @lombok.Setter
    private com.openrealm.game.entity.item.GameItem[] partnerInventory = null;
    /** Class id of the trade partner, used by the trade overlay header
     *  portrait + the in-tooltip class label. Captured at trade accept. */
    @lombok.Getter @lombok.Setter
    private int partnerClassId = 0;
    /** Dye id of the trade partner. Captured at trade accept. */
    @lombok.Getter @lombok.Setter
    private int partnerDyeId = 0;
    private Button confirmTradeButton = null;
    private Button cancelTradeButton = null;
    /** 8 click-targets that overlay the left (mine) grid in the trade
     *  panel. Click toggles selection on inventory[4..11] — same path
     *  the legacy right-click on the sidebar took, just routed through
     *  the centered overlay so the trade UI is self-contained. */
    private Button[] tradeMyButtons = null;
    /** Local-only "I have hit Confirm" flag — the network types
     *  (NetInventorySelection / NetTradeSelection) don't carry a
     *  per-side confirmed bit, so we mirror the webclient pattern of
     *  tracking it client-side and clearing on selection change. */
    private boolean myTradeConfirmed = false;

    private Map<Integer, TextureRegion> classIconCache = new HashMap<>();
    private List<Button> nearbyPlayerButtons = new ArrayList<>();
    private List<Player> nearbyPlayerList = new ArrayList<>();
    private Player hoveredPlayer = null;
    // Screen rect of the nearby-list button the pointer is currently on.
    // Stored at hover-in so the tooltip lands flush next to that entry
    // rather than at a fixed corner of the screen.
    private int hoveredBtnX = 0;
    private int hoveredBtnY = 0;
    private int hoveredBtnW = 0;
    private int hoveredBtnH = 0;
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
    /** Edge-trigger flag for the loot-pickup click diagnostic log so we
     *  print bounds + mouse pos exactly once per click cycle (on the
     *  press transition), not every frame the button is held. */
    private boolean prevLootDebugMouseDown = false;

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
    // Potion-storage UI: 32-slot dialog opened by F-key on tile 328 in the
    // vault. See PotionStorageWindow for layout + drag-drop wiring.
    private final PotionStorageWindow potionStorageWindow = new PotionStorageWindow();

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

    /** Screen X for slot column 0..{@link Player#EQUIPMENT_SLOT_COUNT}-1.
     *  Divides the full usable HUD width into equal cells per equipment slot
     *  and centers the slot inside each cell. Phase 1B bumped EQUIPMENT_SLOT_COUNT
     *  from 4 to 5; this layout adapts automatically. */
    private int slotX(int col) {
        final int slots = Player.EQUIPMENT_SLOT_COUNT;
        final int panelW = OpenRealmGame.width / 5;
        final int startX = OpenRealmGame.width - panelW;
        final int rowW = slots * SLOT_SIZE + (slots - 1) * SLOT_GAP;
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
        // CRITICAL: do NOT recreate the inventory array on every UpdatePacket.
        // UpdatePackets fire at 5 Hz from the server; the old `this.inventory
        // = new Slots[...]` blew away every Button's bounds + clicked state
        // mid-click, so press→release transitions for click/drag/right-click
        // were silently lost (user reported "can't pick up loot / drag items
        // / shift-drop is broken" on the native client). Same defect was
        // already fixed for ground loot in setGroundLoot — we mirror that
        // pattern here:
        //   - empty → empty:    keep null
        //   - empty → populated: build a fresh Slots + Button
        //   - populated → empty: drop the slot (button + handlers go away)
        //   - populated → populated: KEEP existing Button (preserves bounds
        //     reposition done by renderSpriteHud + the click state machine),
        //     just swap the item field. This is the equivalent of the
        //     webclient updating the slot's <img>/dataset without ripping
        //     down the <div>.
        if (this.inventory == null || this.inventory.length != Player.INVENTORY_SIZE) {
            this.inventory = new Slots[Player.INVENTORY_SIZE];
        }
        final int eq = Player.EQUIPMENT_SLOT_COUNT;
        final int total = Math.min(Player.INVENTORY_SIZE, loot != null ? loot.length : 0);
        // For each slot, decide rebuild vs swap.
        for (int i = 0; i < this.inventory.length; i++) {
            final GameItem next = (i < total) ? loot[i] : null;
            final boolean nextEmpty = (next == null || next.getItemId() == -1);
            final Slots existing = this.inventory[i];
            if (nextEmpty) {
                // Item left this slot — drop the Slots so its Button stops
                // hit-testing. Without this an empty slot would still pick
                // up clicks from the ghost button of the previous item.
                this.inventory[i] = null;
                continue;
            }
            if (existing == null) {
                // Empty → populated: build the appropriate button. Equipment
                // (0..eq-1) vs backpack (eq..INVENTORY_SIZE-1) take different
                // handlers — buildEquipmentSlotButton vs buildInventorySlotsButton.
                if (i < eq) {
                    this.buildEquipmentSlotButton(i, next);
                } else {
                    this.buildInventorySlotsButton(i - eq, next);
                }
            } else {
                // Populated → populated: swap the item, keep the Button so
                // in-progress click/drag state survives the UpdatePacket.
                existing.setItem(next);
            }
        }
    }

    public void setGroundLoot(GameItem[] loot) {
        if (this.isTrading && this.currentTradeSelection != null) {
            loot = this.getOtherPlayerSelectedItems();
        }
        // CRITICAL: only REBUILD a slot's Button if that slot transitioned
        // from empty→item or item→empty. For an item-id change in an
        // existing slot, swap the Slots' item field but keep the same
        // Button instance (which has its sprite-HUD position from the
        // previous render's repositionSlotButton). Rebuilding every
        // call wiped the sprite-HUD positions back to legacy slotX()/
        // groundLootRowY() — those legacy coords sit OFF-SCREEN on the
        // new HUD layout, so Button.bounds.inside() never matched the
        // user's click on the visible bag and NO loot click ever fired.
        // Webclient parity: createSlot rebuilds DOM nodes, but the DOM
        // is positioned by CSS so layout is never lost on rebuild.
        if (this.groundLoot == null || this.groundLoot.length != 8) {
            this.groundLoot = new Slots[8];
        }
        for (int i = 0; i < this.groundLoot.length; i++) {
            final GameItem item = (i < loot.length) ? loot[i] : null;
            final boolean isEmpty = (item == null || item.getItemId() == -1);
            final Slots existing = this.groundLoot[i];
            if (isEmpty) {
                this.groundLoot[i] = null;
            } else if (existing == null) {
                // Empty -> populated: build a fresh Button (will be
                // repositioned by renderSpriteHud on the next render
                // tick). The first click on the FIRST frame after
                // building may still miss because input runs before
                // render — this is unavoidable for a freshly-spawned
                // bag and only affects the very first click.
                this.buildGroundLootSlotButton(i, item);
            } else {
                // Populated -> populated (same slot, possibly different
                // item or stack count): swap the item field and keep
                // the existing Button + handler + position.
                existing.setItem(item);
            }
        }
    }

    /**
     * Get the other player's NetInventorySelection (not filtered).
     */
    /** Local player's NetInventorySelection (the side keyed to our id).
     *  Used by the trade overlay to read the server-broadcast confirmed
     *  flag (the only authoritative signal that the server has accepted
     *  my /confirm true). */
    private NetInventorySelection getMyTradeSelection() {
        if (this.currentTradeSelection == null || this.playState == null) return null;
        final long myId = this.playState.getPlayerId();
        if (this.currentTradeSelection.getPlayer0Selection() != null
                && this.currentTradeSelection.getPlayer0Selection().getPlayerId() == myId) {
            return this.currentTradeSelection.getPlayer0Selection();
        }
        return this.currentTradeSelection.getPlayer1Selection();
    }

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
        Slots[] invSlots = this.getSlots(Player.EQUIPMENT_SLOT_COUNT, Player.EQUIPMENT_SLOT_COUNT + 8);
        for (Slots slot : invSlots) {
            if (slot != null) {
                slot.setSelected(false);
            }
        }
        this.confirmTradeButton = null;
        this.cancelTradeButton = null;
        this.tradeMyButtons = null;
        this.myTradeConfirmed = false;
        this.tradeOverlayCloseAtMs = 0L;
        this.tradeRequestAcceptBtn = null;
        this.tradeRequestDeclineBtn = null;
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
            // Try to position the new Button directly at its sprite-HUD
            // grid cell so the FIRST click after a fresh loot bag spawn
            // doesn't miss (input runs before render — the legacy
            // slotX()/groundLootRowY() seed put the bounds off-screen
            // until renderSpriteHud could reposition). Falls back to
            // the legacy coords if the atlas hasn't loaded yet.
            int x = this.slotX((index > 3) ? index - 4 : index);
            int y = this.groundLootRowY((index > 3) ? 1 : 0);
            try {
                if (UiAtlas.isReady()) {
                    final UiComponent cInvExt = UiAtlas.componentOf("panel.hud.inv_ext");
                    final int[][] cells = UiAtlas.gridCells("panel.hud.inv_ext.grid");
                    if (cInvExt != null && cells != null && index < cells.length
                            && this.spriteHudInvExtX > 0 && this.spriteHudInvExtY > 0) {
                        final int s = UiAtlas.getDisplayScale();
                        final int[] cell = cells[index];
                        x = (int)(this.spriteHudInvExtX + (cell[0] - cInvExt.getX()) * s);
                        y = (int)(this.spriteHudInvExtY + (cell[1] - cInvExt.getY()) * s);
                    }
                }
            } catch (Exception ignored) { /* fall through to legacy coords */ }
            Button b = new Button(new Vector2f(x, y), SLOT_SIZE);
            log.info("[loot-build] slot={} itemId={} pos=({}, {}) size={}",
                    actualIdx, item.getItemId(), x, y, SLOT_SIZE);

            b.onMouseUp(event -> {
                // Always log entry so a missing log makes it obvious the
                // click never reached the handler (button bounds wrong /
                // input order issue) vs reached but blocked by a guard.
                log.info("[loot-click] FIRED slot={} itemId={} trading={} dragging={} canSwap={}",
                        actualIdx, item != null ? item.getItemId() : -1,
                        this.isTrading, this.isDragging, this.canSwap());
                if (this.isTrading) return;
                if (this.isDragging) return;
                this.activeTooltip = null;
                if (!this.canSwap()) return;
                this.setActionTime();
                // Mirror webclient onSlotClick: just send moveItem with
                // target=first-backpack-slot and let the server's
                // ground-loot branch route it via firstEmptyInvSlot() —
                // covers BAG 1 + BAG 2, potions, and stack merging.
                // Wire protocol (Phase 1B): ground loot is indices
                // [21..28] (MoveItemPacket.GROUND_LOOT_IDX). The stale
                // `+ 20` offset sent fromIdx=20 (last backpack) for the
                // first loot item, which the server rejected as
                // "invalid from slot" → every loot click was a no-op.
                final int wireFromIdx = actualIdx + MoveItemPacket.groundLootBase();
                final byte wireTargetSlot = (byte) Player.EQUIPMENT_SLOT_COUNT;
                try {
                    this.playState.getRealmManager().moveItem(wireTargetSlot, wireFromIdx, false, false);
                    log.info("[loot-click] moveItem sent (target={} from={})", wireTargetSlot, wireFromIdx);
                } catch (Exception e) {
                    log.warn("[loot-click] moveItem failed for slot {} item {}: {}",
                            actualIdx, item != null ? item.getItemId() : -1, e.getMessage());
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

            // Equipment-slot right-click = drop the equipped item to
            // ground. Using closure-captured actualIdx directly (was
            // routing through getOverlapping which scanned only 0..11).
            final int dropEquipIdx = actualIdx;
            b.onRightClick(event -> {
                if (this.isTrading) return;
                if (!this.canSwap()) return;
                this.setActionTime();
                log.info("[equip-rclick-drop] slot={} itemId={}",
                        dropEquipIdx, item != null ? item.getItemId() : -1);
                this.playState.getRealmManager().moveItem(-1, dropEquipIdx, true, false);
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
        // Phase 1B (combat rework) grew equipment from 4 → 5 slots. This
        // offset MUST match Player.EQUIPMENT_SLOT_COUNT and the wire
        // protocol in MoveItemPacket (backpack starts at index 5). The
        // stale `= 4` here was the root cause behind every backpack click
        // mapping to the wrong slot — right-click drop in the first
        // backpack cell was actually dropping the ring, etc.
        final int inventoryOffset = Player.EQUIPMENT_SLOT_COUNT;

        if (item != null) {
            final int actualIdx = index + inventoryOffset;
            final int col = (index > 3) ? index - 4 : index;
            final int y = (index > 3) ? this.layoutBag2Y : this.layoutBag1Y;
            Button b = new Button(new Vector2f(this.slotX(col), y), SLOT_SIZE);

            b.onRightClick(event -> {
                // Right-click on inventory slot = drop the item to ground
                // (or toggle trade selection while in a trade). The
                // previous version routed through getOverlapping(event) /
                // getOverlapIdx(event), both of which scanned ONLY slots
                // 0..11 — so right-click in BAG 2 (slots 12..19)
                // returned null and dropped silently. Using the
                // closure-captured actualIdx directly is both simpler
                // AND covers the full 20-slot inventory.
                if (this.isTrading) {
                    final Slots slot = (actualIdx < this.inventory.length) ? this.inventory[actualIdx] : null;
                    if (slot != null && slot.getItem() != null) {
                        slot.setSelected(!slot.isSelected());
                        try {
                            final UpdatePlayerTradeSelectionPacket updatedTrade =
                                    UpdatePlayerTradeSelectionPacket.fromSelection(
                                            this.getPlayState().getPlayer(), this);
                            this.playState.getRealmManager().getClient().sendRemote(updatedTrade);
                        } catch (Exception e) {
                            log.warn("[trade-toggle] update failed: {}", e.getMessage());
                        }
                    }
                } else {
                    if (!this.canSwap()) return;
                    this.setActionTime();
                    // Shift+right-click on a stackable item with stackCount>1:
                    // ask the server to split the stack. Source keeps ceil(N/2),
                    // floor(N/2) lands in the first empty backpack slot. Server
                    // rejects if the inventory is full so we never silently lose
                    // half a stack. Checked BEFORE quick-store so a stackable
                    // gem (none today, but defensively) can still be split
                    // when held and otherwise auto-stored.
                    final boolean shiftHeld = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)
                            || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);
                    if (shiftHeld && item != null && item.isStackable() && item.getStackCount() > 1) {
                        try {
                            final long pid = this.playState.getPlayer().getId();
                            final SplitStackPacket pkt = new SplitStackPacket(pid, actualIdx);
                            this.playState.getRealmManager().getClient().getOutboundPacketQueue().add(pkt);
                            log.info("[inv-rclick-split] slot={} itemId={} stack={}",
                                    actualIdx, item.getItemId(), item.getStackCount());
                        } catch (Exception e) {
                            log.warn("[inv-rclick-split] failed: {}", e.getMessage());
                        }
                        return;
                    }
                    // Quick-store: if the potion-storage modal is open AND the
                    // item is storage-eligible (stackable || category=='gem'),
                    // route right-click to the auto-place flow instead of
                    // drop-to-ground. Server figures out the destination
                    // (first mergeable stack, else first empty slot) so the
                    // user can mass-stash with a flurry of right-clicks
                    // without dragging. Mirrors the webclient onSlotRightClick
                    // branch.
                    if (this.potionStorageWindow != null
                            && this.potionStorageWindow.isVisible()
                            && item != null
                            && (item.isStackable() || "gem".equals(item.getCategory()))) {
                        try {
                            final long pid = this.playState.getPlayer().getId();
                            // toIdx=-1 = auto-place sentinel; server picks
                            // first mergeable stack, else first empty slot.
                            final PotionStorageMovePacket pkt = new PotionStorageMovePacket(
                                    pid, PotionStorageMovePacket.SIDE_INV, actualIdx,
                                    PotionStorageMovePacket.SIDE_STORAGE, -1);
                            this.playState.getRealmManager().getClient().getOutboundPacketQueue().add(pkt);
                            log.info("[inv-rclick-quickstore] slot={} itemId={}",
                                    actualIdx, item.getItemId());
                        } catch (Exception e) {
                            log.warn("[inv-rclick-quickstore] failed: {}", e.getMessage());
                        }
                        return;
                    }
                    log.info("[inv-rclick-drop] slot={} itemId={}",
                            actualIdx, item != null ? item.getItemId() : -1);
                    this.playState.getRealmManager().moveItem(-1, actualIdx, true, false);
                }
            });

            this.inventory[actualIdx] = new Slots(b, item);
        }
    }

    private int getOverlapIdx(Vector2f pos) {
        // Hit-test the primary backpack page (8 slots starting after equipment).
        final int base = Player.EQUIPMENT_SLOT_COUNT;
        Slots[] equipSlots = this.getSlots(base, base + 8);
        int returnIdx = -1;
        for (int i = 0; i < equipSlots.length; i++) {
            Slots s = equipSlots[i];
            if ((s == null) || (s.getButton() == null)) continue;
            if (s.getButton().getBounds().inside((int) pos.x, (int) pos.y)) {
                returnIdx = i;
            }
        }
        return returnIdx + base;
    }

    private Slots getOverlapping(Vector2f pos) {
        Slots[] equipSlots = this.getSlots(0, Player.EQUIPMENT_SLOT_COUNT + 8);
        for (Slots s : equipSlots) {
            if ((s == null) || (s.getButton() == null)) continue;
            if (s.getButton().getBounds().inside((int) pos.x, (int) pos.y))
                return s;
        }
        return null;
    }

    public void update(double time) {
        // Tick the deferred trade-overlay close timer. When the server
        // closes a completed trade we keep the overlay rendered for ~1s
        // showing both 'CONFIRMED' badges, then run the actual close
        // here so the player can see that both sides accepted.
        if (this.tradeOverlayCloseAtMs > 0L
                && System.currentTimeMillis() >= this.tradeOverlayCloseAtMs) {
            this.tradeOverlayCloseAtMs = 0L;
            this.setTrading(false);
            this.setCurrentTradeSelection(null);
            this.setTradePartnerName(null);
            this.setPartnerInventory(null);
            this.setPartnerClassId(0);
            this.setPartnerDyeId(0);
            this.clearTradeSelections();
        }
        // Auto-dismiss pending trade-request popup after 15s (matches
        // server-side TTL).
        if (this.pendingTradeRequestFrom != null
                && System.currentTimeMillis() - this.pendingTradeRequestStartMs > 15000L) {
            this.pendingTradeRequestFrom = null;
            this.tradeRequestAcceptBtn = null;
            this.tradeRequestDeclineBtn = null;
        }
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

        // Debug-log loot slot bounds whenever the LEFT mouse JUST went
        // down (not held) — surfaces 'click went here, button is at
        // these coords' so we can see why a click on a visible loot
        // bag isn't reaching the handler.
        final boolean lootDebugLogThisFrame = mouse.isPressed(1) && !this.prevLootDebugMouseDown;
        this.prevLootDebugMouseDown = mouse.isPressed(1);
        for (int i = 0; i < this.groundLoot.length; i++) {
            Slots curr = this.groundLoot[i];
            if (curr != null) {
                if (lootDebugLogThisFrame && curr.getButton() != null) {
                    final var bnd = curr.getButton().getBounds();
                    log.info("[loot-input] slot={} mouse=({}, {}) bounds=({}, {}, {}x{}) inside={}",
                            i, mouse.getX(), mouse.getY(),
                            (int) bnd.getPos().x, (int) bnd.getPos().y,
                            (int) bnd.getWidth(), (int) bnd.getHeight(),
                            bnd.inside(mouse.getX(), mouse.getY()));
                }
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
            if (this.tradeMyButtons != null) {
                for (Button b : this.tradeMyButtons) {
                    if (b != null) b.input(mouse, key);
                }
            }
        }

        // Trade-request popup input — Accept / Decline buttons.
        if (this.pendingTradeRequestFrom != null) {
            if (this.tradeRequestAcceptBtn != null) this.tradeRequestAcceptBtn.input(mouse, key);
            if (this.tradeRequestDeclineBtn != null) this.tradeRequestDeclineBtn.input(mouse, key);
        }
        // Party-invite popup input — Accept / Decline buttons. Sits left,
        // routed identically to the trade prompt.
        if (this.pendingPartyInviteFrom != null) {
            if (this.partyInviteAcceptBtn != null)  this.partyInviteAcceptBtn.input(mouse, key);
            if (this.partyInviteDeclineBtn != null) this.partyInviteDeclineBtn.input(mouse, key);
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
                            new Vector2f(tooltipX, 100), panelWidth, 0,
                            this.viewerClassId());
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
                            new Vector2f(tooltipX, 100), panelWidth, 0,
                            this.viewerClassId());
                    return;
                }
            }
        }

        // Check potion-storage cells when the modal is open. getCellRect
        // returns flipped-ortho screen coords matching the cells' rendered
        // position so hover land-on works the same as inventory slots.
        if (this.potionStorageWindow != null && this.potionStorageWindow.isVisible()) {
            final GameItem[] storageItems = this.potionStorageWindow.getItems();
            if (storageItems != null) {
                for (int i = 0; i < storageItems.length; i++) {
                    final GameItem stored = storageItems[i];
                    if (stored == null) continue;
                    final int[] r = this.potionStorageWindow.getCellRect(i);
                    if (r != null && mx >= r[0] && mx < r[0] + r[2]
                            && my >= r[1] && my < r[1] + r[3]) {
                        this.activeTooltip = new ItemTooltip(stored,
                                new Vector2f(tooltipX, 100), panelWidth, 0,
                                this.viewerClassId());
                        return;
                    }
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

    /** Lazy-construct the Confirm/Cancel buttons, then re-anchor them
     *  to the supplied row coords each frame. Called from
     *  {@link #renderTradeUI} so the buttons follow the centered overlay
     *  on every resize. */
    private void ensureTradeButtons(int overlayX, int overlayW, int rowY) {
        final int btnW = 150;
        final int btnH = 36;
        final int gap  = 16;
        final int totalBtnsW = btnW * 2 + gap;
        final int btnX0 = overlayX + (overlayW - totalBtnsW) / 2;
        final int btnX1 = btnX0 + btnW + gap;
        if (this.confirmTradeButton == null) {
            this.confirmTradeButton = new Button("CONFIRM", new Vector2f(btnX0, rowY), btnW, btnH);
            this.confirmTradeButton.onMouseUp(event -> {
                this.sendTradeCommand("confirm true");
                this.myTradeConfirmed = true;
            });
        }
        if (this.cancelTradeButton == null) {
            this.cancelTradeButton = new Button("CANCEL", new Vector2f(btnX1, rowY), btnW, btnH);
            this.cancelTradeButton.onMouseUp(event -> {
                this.sendTradeCommand("decline");
                this.myTradeConfirmed = false;
            });
        }
        this.confirmTradeButton.getPos().x = btnX0;
        this.confirmTradeButton.getPos().y = rowY;
        this.cancelTradeButton.getPos().x = btnX1;
        this.cancelTradeButton.getPos().y = rowY;
    }

    /** Lazy-construct the 8 click-targets that overlay my-side trade
     *  cells. Click toggles selection on inventory[i + EQUIPMENT_SLOT_COUNT]
     *  (= the first 8 backpack slots — BAG 1) and fires
     *  UpdatePlayerTradeSelectionPacket. Position is updated each frame
     *  by {@link #renderTradeUI}. */
    private void ensureTradeMyButtons(int leftX, int bodyY, UiComponent cInv) {
        if (this.tradeMyButtons != null && this.tradeMyButtons.length == 8) return;
        this.tradeMyButtons = new Button[8];
        final int s = UiAtlas.getDisplayScale();
        final int[][] cells = UiAtlas.gridCells("panel.hud.inv_only.grid");
        final int cellW = (cells != null && cells.length > 0) ? cells[0][2] * s : SLOT_SIZE;
        for (int i = 0; i < 8; i++) {
            final int slotIdx = i + Player.EQUIPMENT_SLOT_COUNT;
            final Button b = new Button(new Vector2f(0, 0), cellW);
            // onMouseDown so the toggle fires ONCE per click cycle —
            // onMouseUp fires twice (press + release), which toggled
            // selection on→off in a single click and looked like the
            // selection wasn't sticking.
            b.onMouseDown(event -> {
                if (!this.isTrading) return;
                if (slotIdx >= this.inventory.length) return;
                final Slots slot = this.inventory[slotIdx];
                if (slot == null || slot.getItem() == null) return;
                slot.setSelected(!slot.isSelected());
                this.myTradeConfirmed = false; // any change voids prior confirm
                final UpdatePlayerTradeSelectionPacket pkt =
                        UpdatePlayerTradeSelectionPacket.fromSelection(this.playState.getPlayer(), this);
                try {
                    this.playState.getRealmManager().getClient().sendRemote(pkt);
                } catch (Exception e) {
                    log.warn("[trade-overlay] selection update failed: {}", e.getMessage());
                }
            });
            this.tradeMyButtons[i] = b;
        }
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
        Slots[] equips = this.getSlots(0, Player.EQUIPMENT_SLOT_COUNT);
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

        // Render nearby players list — and, when the player is in a party,
        // a SEPARATE party-members list in its own chrome above nearby.
        // Sprite HUD reroutes both into dedicated bottom-left panels; the
        // legacy path keeps the old single-panel right-sidebar layout.
        if (useSpriteHud && this.spriteHudNearbyEnabled) {
            // Party panel (top) — only when in a party. Uses its own anchor
            // so it lives in the upper chrome, not nested inside nearby.
            if (this.spriteHudPartyEnabled) {
                final int prevNearbyY = this.layoutNearbyY;
                this.layoutNearbyY = this.spriteHudPartyY;
                this.renderPartyMembers(batch, shapes, font,
                        this.spriteHudPartyX, this.spriteHudPartyW);
                this.layoutNearbyY = prevNearbyY;
            }
            // Nearby panel (bottom). renderNearbyPlayers will NOT also draw
            // the party section because we pass useExternalParty=true via
            // the suppressInternalParty flag set above.
            final int prevNearbyY = this.layoutNearbyY;
            this.layoutNearbyY = this.spriteHudNearbyY;
            this.suppressInternalParty = true;
            this.renderNearbyPlayers(batch, shapes, font,
                    this.spriteHudNearbyX, this.spriteHudNearbyW);
            this.suppressInternalParty = false;
            this.layoutNearbyY = prevNearbyY;
        } else {
            // Legacy single-panel layout — party still renders inside nearby.
            this.renderNearbyPlayers(batch, shapes, font, startX, panelWidth);
        }

        if (this.activeTooltip != null) {
            this.activeTooltip.render(batch, shapes, font);
        }

        this.renderPlayerTooltip(batch, shapes, font);
        this.renderPlayerContextMenu(batch, shapes, font);
        this.renderTradeRequestPopup(batch, shapes, font);
        this.renderPartyInvitePrompt(batch, shapes, font);
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
        this.potionStorageWindow.update();
        this.potionStorageWindow.render(batch, shapes, font);

        // Dev-stats overlay removed — the top-left corner now hosts the
        // minimap panel. PerfMetrics still ticks for any other consumers
        // (debug logs, future overlay re-enable) but doesn't render.
        PerfMetrics.get().onFrame();
    }

    /**
     * Centered trade overlay: two panel.hud.inv_only chromes stuck side
     * by side with panel.container.inventory headers above each. Confirm
     * and Cancel buttons sit underneath. Webclient parity with the
     * #trade-overlay DOM block in trade.js.
     *
     * Left side  — MY items (inventory[4..11]); slots 0..7 of BAG 1 are
     *              clickable to toggle trade selection.
     * Right side — PARTNER items (currentTradeSelection.other); read-only.
     */
    private void renderTradeUI(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font,
                                int startX, int panelWidth) {
        if (!UiAtlas.isReady()) return;
        final int s = UiAtlas.getDisplayScale();
        final UiComponent cInv    = UiAtlas.componentOf("panel.hud.inv_only");
        final UiComponent cInvHdr = UiAtlas.componentOf("panel.container.inventory");
        if (cInv == null) return; // atlas not loaded — bail to avoid NPE

        // ---- Layout — center the entire overlay on the screen. ----
        final int panelW = cInv.getW() * s;                    // 240
        final int panelH = cInv.getH() * s;                    // 254
        final int hdrH   = cInvHdr != null ? cInvHdr.getH() * s : 36 * s;
        final int gap    = 16;
        final int btnH   = 36;
        final int totalW = panelW * 2 + gap;
        final int totalH = hdrH + 4 + panelH + gap + btnH;
        final int ox = (OpenRealmGame.width  - totalW) / 2;
        final int oy = (OpenRealmGame.height - totalH) / 2;

        final int leftX  = ox;
        final int rightX = ox + panelW + gap;
        final int hdrY   = oy;
        final int bodyY  = hdrY + hdrH + 4;
        final int btnY   = bodyY + panelH + gap;

        this.ensureTradeButtons(ox, totalW, btnY);
        this.ensureTradeMyButtons(leftX, bodyY, cInv);

        // ---- Dimmed backdrop. ----
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0, 0, 0, 0.55f);
        shapes.rect(0, 0, OpenRealmGame.width, OpenRealmGame.height);
        shapes.end();
        batch.begin();

        // ---- Sprite chrome blits — headers + bodies. ----
        final TextureRegion rHdr  = (cInvHdr != null) ? UiAtlas.region("panel.container.inventory") : null;
        final TextureRegion rBody = UiAtlas.region("panel.hud.inv_only");
        if (rHdr != null) {
            batch.draw(rHdr,  leftX,  hdrY, panelW, hdrH);
            batch.draw(rHdr,  rightX, hdrY, panelW, hdrH);
        } else {
            // Fallback flat fill if the chrome region resolves but is empty.
            batch.end();
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0x1a / 255f, 0x12 / 255f, 0x18 / 255f, 0.95f);
            shapes.rect(leftX,  hdrY, panelW, hdrH);
            shapes.rect(rightX, hdrY, panelW, hdrH);
            shapes.end();
            batch.begin();
        }
        if (rBody != null) {
            batch.draw(rBody, leftX,  bodyY, panelW, panelH);
            batch.draw(rBody, rightX, bodyY, panelW, panelH);
        }

        // ---- Header text — names + status (role-colored own name,
        //       cyan partner name, green when each side has confirmed). ----
        final Player me = (this.playState != null) ? this.playState.getPlayer() : null;
        final String myName    = (me != null && me.getName() != null) ? me.getName() : "YOU";
        final String partner   = (this.tradePartnerName != null) ? this.tradePartnerName : "...";
        // Read confirmed flags from the live UpdateTradePacket — these
        // are now broadcast by the server (NetInventorySelection.confirmed
        // field) so each side sees real-time confirmation status of the
        // other. My own confirmed mirrors the local `myTradeConfirmed`
        // flag too in case the server's broadcast lags by a frame.
        final NetInventorySelection mySel    = this.getMyTradeSelection();
        final NetInventorySelection theirSel2 = this.getOtherPlayerSelection();
        final boolean iConfirmed     = this.myTradeConfirmed
                || (mySel != null && mySel.isConfirmed());
        final boolean theyConfirmed  = theirSel2 != null && theirSel2.isConfirmed();

        font.setColor(roleColorFor(me != null ? me.getChatRole() : null));
        font.draw(batch, myName, leftX + 12, hdrY + 22);
        if (iConfirmed) {
            font.setColor(0.25f, 0.78f, 0.35f, 1f);
            font.draw(batch, "CONFIRMED", leftX + 12, hdrY + 40);
        } else {
            font.setColor(0x88 / 255f, 0x78 / 255f, 0x68 / 255f, 1f);
            font.draw(batch, "Picking...", leftX + 12, hdrY + 40);
        }

        font.setColor(0.40f, 0.78f, 0.88f, 1f);
        font.draw(batch, partner, rightX + 12, hdrY + 22);
        if (theyConfirmed) {
            font.setColor(0.25f, 0.78f, 0.35f, 1f);
            font.draw(batch, "CONFIRMED", rightX + 12, hdrY + 40);
        } else {
            font.setColor(0x88 / 255f, 0x78 / 255f, 0x68 / 255f, 1f);
            font.draw(batch, "Picking...", rightX + 12, hdrY + 40);
        }
        font.setColor(Color.WHITE);

        // ---- Slot grids — only the top 8 cells (BAG 1) are tradable. ----
        final int[][] cells = UiAtlas.gridCells("panel.hud.inv_only.grid");
        if (cells != null && cells.length >= 8) {
            // My side
            for (int i = 0; i < 8; i++) {
                final int[] c = cells[i];
                final float cx = leftX + (c[0] - cInv.getX()) * s;
                final float cy = bodyY + (c[1] - cInv.getY()) * s;
                final float cw = c[2] * s;
                final float ch = c[3] * s;
                final int slotIdx = i + Player.EQUIPMENT_SLOT_COUNT;
                final Slots slot = (slotIdx < this.inventory.length) ? this.inventory[slotIdx] : null;
                final GameItem item = (slot != null) ? slot.getItem() : null;
                final boolean selected = slot != null && slot.isSelected();
                this.drawTradeSlot(batch, shapes, cx, cy, cw, ch, item, selected);
                // Re-anchor click target to overlay cell so a click on the
                // overlay routes to the same toggle handler the sidebar
                // right-click used.
                if (this.tradeMyButtons != null && this.tradeMyButtons[i] != null) {
                    this.tradeMyButtons[i].getPos().x = cx;
                    this.tradeMyButtons[i].getPos().y = cy;
                }
            }
            // Partner side — items come from the snapshot we captured at
            // trade-accept (partnerInventory), selection flags come from
            // the live UpdateTradePacket. The on-wire selection packet
            // does NOT carry items; .getGameItems() on it would NPE on
            // the null itemRefs.
            final NetInventorySelection theirSel = this.getOtherPlayerSelection();
            final Boolean[] theirFlags = (theirSel != null) ? theirSel.getSelection() : null;
            final GameItem[] theirItems = this.partnerInventory;
            for (int i = 0; i < 8; i++) {
                final int[] c = cells[i];
                final float cx = rightX + (c[0] - cInv.getX()) * s;
                final float cy = bodyY  + (c[1] - cInv.getY()) * s;
                final float cw = c[2] * s;
                final float ch = c[3] * s;
                final int partnerSlotIdx = i + Player.EQUIPMENT_SLOT_COUNT;
                final GameItem item = (theirItems != null && partnerSlotIdx < theirItems.length)
                        ? theirItems[partnerSlotIdx] : null;
                // Partner's selection is keyed 0..7 (server uses bag-1
                // relative indices); our flags array matches that.
                final boolean sel = (theirFlags != null && i < theirFlags.length
                                      && theirFlags[i] != null) ? theirFlags[i] : false;
                this.drawTradeSlot(batch, shapes, cx, cy, cw, ch, item, sel);
            }
        }

        // ---- Confirm + Cancel buttons. ----
        this.drawTradeButton(batch, shapes, font, this.confirmTradeButton,
                iConfirmed ? "CONFIRMED ✓" : "CONFIRM",
                iConfirmed ? new Color(0.18f, 0.55f, 0.23f, 1f)
                            : new Color(0.25f, 0.78f, 0.35f, 1f));
        this.drawTradeButton(batch, shapes, font, this.cancelTradeButton,
                "CANCEL", new Color(0.78f, 0.27f, 0.27f, 1f));
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Pop-up "X wants to trade — Accept / Decline" surfaced under the
     *  right HUD column. Lazy-builds the two buttons on first render
     *  so they sit underneath whatever the right column currently
     *  resolves to. Buttons clear themselves on click and feed
     *  /accept or /decline through the existing chat command path
     *  (no need to actually type the command). Webclient parity. */
    /**
     * Phase 4 — party invite prompt with Accept/Decline buttons. Sits on
     * the LEFT side of the screen (matches the webclient placement) so
     * the player doesn't have to type /party accept. Auto-dismisses after
     * 60s to match the server-side invite TTL.
     */
    private void renderPartyInvitePrompt(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        if (this.pendingPartyInviteFrom == null) return;
        if (System.currentTimeMillis() > this.pendingPartyInviteExpiresAt) {
            this.pendingPartyInviteFrom = null;
            this.partyInviteAcceptBtn = null;
            this.partyInviteDeclineBtn = null;
            return;
        }
        final int boxW = 220;
        final int boxH = 80;
        final int boxX = 16;
        // Center vertically — 28% from top matches the web placement.
        final int boxY = OpenRealmGame.height - (OpenRealmGame.height * 28 / 100) - boxH;
        if (this.partyInviteAcceptBtn == null || this.partyInviteDeclineBtn == null) {
            final int btnW = (boxW - 24) / 2;
            final int btnH = 26;
            final int btnY = boxY + 8;
            this.partyInviteAcceptBtn = new Button("ACCEPT", new Vector2f(boxX + 8, btnY), btnW, btnH);
            this.partyInviteAcceptBtn.onMouseDown(event -> {
                this.sendServerCommand("party", "accept");
                this.pendingPartyInviteFrom = null;
                this.partyInviteAcceptBtn = null;
                this.partyInviteDeclineBtn = null;
            });
            this.partyInviteDeclineBtn = new Button("DECLINE", new Vector2f(boxX + 16 + btnW, btnY), btnW, btnH);
            this.partyInviteDeclineBtn.onMouseDown(event -> {
                this.sendServerCommand("party", "decline");
                this.pendingPartyInviteFrom = null;
                this.partyInviteAcceptBtn = null;
                this.partyInviteDeclineBtn = null;
            });
        }
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0x1a / 255f, 0x12 / 255f, 0x18 / 255f, 0.94f);
        shapes.rect(boxX, boxY, boxW, boxH);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0xc8 / 255f, 0xa8 / 255f, 0x6e / 255f, 1f);
        shapes.rect(boxX, boxY, boxW, boxH);
        shapes.end();
        batch.begin();
        font.setColor(0xc8 / 255f, 0xa8 / 255f, 0x6e / 255f, 1f);
        font.draw(batch, "PARTY INVITE", boxX + 10, boxY + boxH - 8);
        font.setColor(1f, 0.94f, 0.62f, 1f);
        font.draw(batch, this.pendingPartyInviteFrom + " wants you in their party",
                boxX + 10, boxY + boxH - 26);
        font.setColor(Color.WHITE);
        this.drawTradeButton(batch, shapes, font, this.partyInviteAcceptBtn,
                "ACCEPT", new Color(0.25f, 0.78f, 0.35f, 1f));
        this.drawTradeButton(batch, shapes, font, this.partyInviteDeclineBtn,
                "DECLINE", new Color(0.78f, 0.27f, 0.27f, 1f));
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void renderTradeRequestPopup(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        if (this.pendingTradeRequestFrom == null) return;
        final int panelW = OpenRealmGame.width / 5;
        final int boxW = panelW;
        final int boxH = 80;
        final int boxX = OpenRealmGame.width - panelW - 16;
        // Sit underneath the inventory bag (~700 px) so it's clearly
        // visible without overlapping minimap/equip-stats above it.
        final int boxY = OpenRealmGame.height - 16 - 36 - 8 - boxH - 16;
        if (this.tradeRequestAcceptBtn == null || this.tradeRequestDeclineBtn == null) {
            final int btnW = (boxW - 24) / 2;
            final int btnH = 28;
            final int btnY = boxY + boxH - btnH - 8;
            this.tradeRequestAcceptBtn = new Button("ACCEPT", new Vector2f(boxX + 8, btnY), btnW, btnH);
            this.tradeRequestAcceptBtn.onMouseDown(event -> {
                this.sendServerCommand("accept", "");
                this.pendingTradeRequestFrom = null;
                this.tradeRequestAcceptBtn = null;
                this.tradeRequestDeclineBtn = null;
            });
            this.tradeRequestDeclineBtn = new Button("DECLINE", new Vector2f(boxX + 16 + btnW, btnY), btnW, btnH);
            this.tradeRequestDeclineBtn.onMouseDown(event -> {
                this.sendServerCommand("decline", "");
                this.pendingTradeRequestFrom = null;
                this.tradeRequestAcceptBtn = null;
                this.tradeRequestDeclineBtn = null;
            });
        }

        // Background panel — same palette as the trade overlay header.
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0x1a / 255f, 0x12 / 255f, 0x18 / 255f, 0.94f);
        shapes.rect(boxX, boxY, boxW, boxH);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0xc8 / 255f, 0xa8 / 255f, 0x6e / 255f, 1f);
        shapes.rect(boxX, boxY, boxW, boxH);
        shapes.end();
        batch.begin();
        font.setColor(0xc8 / 255f, 0xa8 / 255f, 0x6e / 255f, 1f);
        font.draw(batch, this.pendingTradeRequestFrom + " wants to trade", boxX + 10, boxY + 22);
        font.setColor(Color.WHITE);

        // Accept (green) / Decline (red) buttons.
        this.drawTradeButton(batch, shapes, font, this.tradeRequestAcceptBtn,
                "ACCEPT", new Color(0.25f, 0.78f, 0.35f, 1f));
        this.drawTradeButton(batch, shapes, font, this.tradeRequestDeclineBtn,
                "DECLINE", new Color(0.78f, 0.27f, 0.27f, 1f));
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Render a single trade-overlay slot: optional yellow selection
     *  border + recessed background + item icon. Centralized so my-side
     *  and partner-side render identically. */
    private void drawTradeSlot(SpriteBatch batch, ShapeRenderer shapes,
                                float x, float y, float w, float h,
                                GameItem item, boolean selected) {
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0x0e / 255f, 0x0a / 255f, 0x0c / 255f, 0.85f);
        shapes.rect(x, y, w, h);
        shapes.end();
        if (selected) {
            shapes.begin(ShapeRenderer.ShapeType.Line);
            shapes.setColor(0xc8 / 255f, 0xa8 / 255f, 0x6e / 255f, 1f);
            shapes.rect(x, y, w, h);
            shapes.rect(x + 1, y + 1, w - 2, h - 2);
            shapes.end();
        }
        batch.begin();
        if (item != null && item.getItemId() != -1) {
            final TextureRegion icon = GameSpriteManager.ITEM_SPRITES.get(item.getItemId());
            if (icon != null) {
                batch.draw(icon, x + 4, y + 4, w - 8, h - 8);
            }
        }
    }

    private void drawTradeButton(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font,
                                  Button b, String label, Color bg) {
        if (b == null) return;
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(bg);
        shapes.rect(b.getPos().x, b.getPos().y, b.getWidth(), b.getHeight());
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0, 0, 0, 0.8f);
        shapes.rect(b.getPos().x, b.getPos().y, b.getWidth(), b.getHeight());
        shapes.end();
        batch.begin();
        final GlyphLayout gl = new GlyphLayout(font, label);
        font.setColor(Color.WHITE);
        // font.draw y is the TOP of the text bbox in y-down ortho (NOT
        // the baseline). To vertically center: text_top = box_top +
        // (box_h - text_h)/2 + text_h ≈ box_top + (box_h + text_h)/2 ?
        // No — for TOP convention, text_top = box_top + (box_h-text_h)/2
        // centers the text. The previous +text_h pushed it bottom-aligned.
        font.draw(batch, label,
                b.getPos().x + (b.getWidth()  - gl.width)  / 2f,
                b.getPos().y + (b.getHeight() + gl.height) / 2f - gl.height * 0.15f);
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
            final int btnH2 = entryHeight;
            btn.onHoverIn(event -> {
                this.hoveredPlayer = hoverTarget;
                this.hoveredBtnX = btnX;
                this.hoveredBtnY = btnY;
                this.hoveredBtnW = btnW;
                this.hoveredBtnH = btnH2;
            });
            btn.onHoverOut(event -> {
                if (this.hoveredPlayer == hoverTarget) {
                    this.hoveredPlayer = null;
                }
            });
            // Open the trade/tp context menu on left-click RELEASE
            // (onMouseUp). Releasing rather than pressing means the
            // same-frame `handleContextMenuInput` doesn't see a fresh
            // mouse-down and dismiss the menu we just opened —
            // justClicked there requires a transition from up→down,
            // which only happens on the NEXT click (which IS a menu
            // option click, the path we want).
            btn.onMouseUp(event -> {
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

        // Phase 4 — Party section sits ABOVE the nearby list when the
        // player is in a party. In the legacy single-panel layout it
        // renders here (inline above the nearby header). The sprite-HUD
        // two-panel layout calls renderPartyMembers separately into its
        // own chrome and sets suppressInternalParty so we don't double-
        // render it here.
        final int partyConsumed = this.suppressInternalParty
                ? 0
                : this.renderPartyMembers(batch, shapes, font, startX, panelWidth);

        int headerY = this.layoutNearbyY + partyConsumed;
        font.setColor(0.78f, 0.66f, 0.43f, 1f); // tan accent (matches name + level)
        // Matches webclient #nearby-section label ("Players Nearby").
        font.draw(batch, "Players Nearby", startX, headerY);
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

    /**
     * Render the party-members section above the nearby-players list.
     * Mirrors webclient trade.js _renderPartySection: gold-tinted member
     * rows with class icon, name, and stacked HP/MP mini-bars. Returns the
     * pixel height consumed so the caller can offset the nearby section
     * below us. Returns 0 when the player isn't in a party.
     */
    private int renderPartyMembers(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font,
                                    int startX, int panelWidth) {
        if (this.playState == null) return 0;
        final long partyId = this.playState.getPartyId();
        final com.openrealm.net.entity.NetPartyMember[] members = this.playState.getPartyMembers();
        if (partyId == 0L || members == null || members.length == 0) return 0;

        final long localId = this.playState.getPlayer() != null
                ? this.playState.getPlayer().getId() : 0L;
        // Build display list — skip self so the panel matches webclient.
        java.util.List<com.openrealm.net.entity.NetPartyMember> toDraw = new java.util.ArrayList<>();
        for (com.openrealm.net.entity.NetPartyMember m : members) {
            if (m != null && m.getPlayerId() != localId) toDraw.add(m);
        }

        // Slightly taller rows so the cooldown strip below HP/MP has room.
        final int rowH = 44;
        final int iconSize = 24;
        final int rowGap = 2;
        final int cdCellSize = 14;
        final int cdCellGap = 2;
        int headerY = this.layoutNearbyY;
        font.setColor(1.00f, 0.85f, 0.36f, 1f); // gold accent
        // Header text mirrors webclient #party-section: "Players In Party N/4"
        // (count includes self so the user sees their own membership).
        font.draw(batch, "Players In Party  " + members.length + "/4", startX, headerY);
        font.setColor(Color.WHITE);
        int y = headerY + 12;
        final long nowMs = System.currentTimeMillis();
        for (com.openrealm.net.entity.NetPartyMember m : toDraw) {
            TextureRegion icon = this.getClassIcon(m.getClassId());
            if (icon != null) {
                batch.draw(icon, startX, y + (rowH - iconSize) / 2f, iconSize, iconSize);
            }
            String name = m.getName() != null ? m.getName() : "?";
            if (name.length() > 14) name = name.substring(0, 14);
            font.setColor(1.00f, 0.94f, 0.62f, 1f);
            font.draw(batch, name, startX + iconSize + 6, y + 11);
            font.setColor(Color.WHITE);
            final float hpPct = m.getMaxHealth() > 0
                    ? Math.max(0f, Math.min(1f, m.getHealth() / (float) m.getMaxHealth())) : 0f;
            final float mpPct = m.getMaxMana() > 0
                    ? Math.max(0f, Math.min(1f, m.getMana() / (float) m.getMaxMana())) : 0f;
            final int barX = startX + iconSize + 6;
            final int barW = panelWidth - (iconSize + 6) - 4;
            final int hpY = y + 14;
            final int mpY = hpY + 5;
            // Cooldown strip — 4 mini ability icons under the HP/MP bars.
            // Each cell is a small sprite of the bound ability with a dark
            // overlay that drains from the top as the cooldown ticks down.
            // Mirrors the webclient party-cd-strip behavior.
            final Integer[] bindings = m.getHotbarBindings();
            final Long[]    cdEnds   = m.getAbilityCooldownEnds();
            final int cdY = mpY + 5;
            final int cdStripX = barX;
            batch.end();
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0.10f, 0.06f, 0.06f, 0.88f);
            shapes.rect(barX, hpY, barW, 4);
            shapes.setColor(0.78f, 0.06f, 0.19f, 0.95f);
            shapes.rect(barX, hpY, barW * hpPct, 4);
            shapes.setColor(0.06f, 0.06f, 0.12f, 0.88f);
            shapes.rect(barX, mpY, barW, 3);
            shapes.setColor(0.31f, 0.44f, 1.00f, 0.95f);
            shapes.rect(barX, mpY, barW * mpPct, 3);
            // Cell backplates so missing-icon cells still register visually.
            for (int i = 0; i < 4; i++) {
                final float cx = cdStripX + i * (cdCellSize + cdCellGap);
                shapes.setColor(0.10f, 0.07f, 0.03f, 0.92f);
                shapes.rect(cx, cdY, cdCellSize, cdCellSize);
            }
            shapes.end();
            batch.begin();
            // Ability icons + cooldown overlay (drain-from-top dark rect).
            if (bindings != null) {
                for (int i = 0; i < 4 && i < bindings.length; i++) {
                    final int aid = bindings[i] != null ? bindings[i] : 0;
                    if (aid <= 0) continue;
                    final com.openrealm.game.model.ability.Ability ab =
                            com.openrealm.game.data.GameDataManager.ABILITIES == null ? null
                                    : com.openrealm.game.data.GameDataManager.ABILITIES.get(aid);
                    if (ab == null) continue;
                    final float cx = cdStripX + i * (cdCellSize + cdCellGap);
                    if (ab.getSpriteKey() != null && !ab.getSpriteKey().isEmpty()) {
                        final int spriteSize = ab.getSpriteSize() > 0 ? ab.getSpriteSize() : 8;
                        final Sprite spr = GameSpriteManager.loadSprite(ab.getCol(), ab.getRow(),
                                ab.getSpriteKey(), spriteSize);
                        if (spr != null && spr.getRegion() != null) {
                            batch.draw(spr.getRegion(), cx + 1, cdY + 1, cdCellSize - 2, cdCellSize - 2);
                        }
                    }
                    // Cooldown overlay (dark rect from top) — height tracks
                    // remaining/total. Drawn via shapes pass.
                    final long cdEnd = cdEnds != null && i < cdEnds.length && cdEnds[i] != null ? cdEnds[i] : 0L;
                    final long baseCd = ab.getBaseCooldownMs();
                    if (cdEnd > nowMs && baseCd > 0) {
                        final float remaining = Math.min(cdEnd - nowMs, baseCd);
                        final float frac = Math.max(0f, Math.min(1f, remaining / (float) baseCd));
                        batch.end();
                        shapes.begin(ShapeRenderer.ShapeType.Filled);
                        shapes.setColor(0f, 0f, 0f, 0.65f);
                        shapes.rect(cx, cdY + cdCellSize * (1f - frac), cdCellSize, cdCellSize * frac);
                        shapes.end();
                        batch.begin();
                    }
                }
            }
            // Dim if member is in a different realm — applied to whole row
            // after sprites are drawn by drawing a translucent grey overlay.
            final boolean sameRealm = m.getRealmId() == 0L
                    || this.playState.getRealmManager().getRealm() == null
                    || this.playState.getRealmManager().getRealm().getRealmId() == m.getRealmId();
            if (!sameRealm) {
                batch.end();
                shapes.begin(ShapeRenderer.ShapeType.Filled);
                shapes.setColor(0f, 0f, 0f, 0.45f);
                shapes.rect(startX, y, panelWidth, rowH);
                shapes.end();
                batch.begin();
            }
            y += rowH + rowGap;
        }
        return Math.max(0, y - headerY + 8);
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
     *  plus a name header. Wide enough for "Teleport to <NAME>" with
     *  a 10-char trimmed name (longest expected label). */
    private static final int CTX_MENU_W = 180;
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
        final String pname = (p.getName() != null) ? p.getName() : "Player";
        // Fit the name into the 130-px menu width by trimming if needed.
        final String shortName = pname.length() > 10 ? pname.substring(0, 10) : pname;
        font.draw(batch, "Trade with "    + shortName, x + 6, yTrade + 14);
        font.draw(batch, "Teleport to "   + shortName, x + 6, yTp + 14);
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
        final int padX = 12;
        final int padY = 12;
        // Tooltip anchors flush against the right edge of the entire
        // nearby-players panel chrome — never overlaps the player list
        // entries. Webclient parity (#player-tooltip lands to the right
        // of the entry; we anchor to the panel for cleaner alignment).
        final int tooltipW = 240;

        // Pull stats — guard nulls so a freshly-added remote player whose
        // UpdatePacket hasn't landed yet doesn't NPE.
        final int hp = p.getHealth();
        final int mp = p.getMana();
        final int maxHp = (p.getStats() != null) ? p.getStats().getHp() : 0;
        final int maxMp = (p.getStats() != null) ? p.getStats().getMp() : 0;
        final CharacterClass cls = CharacterClass.valueOf(p.getClassId());
        final String className = (cls != null) ? cls.name() : "Unknown";
        // Level: derive from broadcast experience via the same
        // ExperienceModel the local player level uses. UpdatePacket
        // ships experience for every player so this resolves nearby
        // players, not just self. Falls back to "?" if the experience
        // model hasn't loaded yet (cold-boot only).
        int level = -1;
        try {
            if (com.openrealm.game.data.GameDataManager.EXPERIENCE_LVLS != null) {
                level = com.openrealm.game.data.GameDataManager.EXPERIENCE_LVLS.getLevel(p.getExperience());
            }
        } catch (Exception ignored) { /* leave as -1 */ }
        final String levelStr = (level > 0) ? ("Lv " + level + " ") : "";

        // Equipment slots 0-3. Server's stripped UpdatePacket
        // (UpdatePacket.fromPlayerWithoutInventory) ships these for
        // remote players; null until the first broadcast lands.
        final GameItem[] equips = p.getSlots(0, Player.EQUIPMENT_SLOT_COUNT);

        // Vertical layout (top→down inside the tooltip):
        //   nameRow   16   "Name [role]"
        //   classRow  14   "Lv N Class"
        //   hpRow     14   "HP: cur/max"
        //   mpRow     14   "MP: cur/max"
        //   gap        8
        //   equipRow  40   4 slot icons (36 + 4 padding)
        // Plus padY top + padY bottom.
        final int nameRowH  = 18;
        final int classRowH = 16;
        final int hpRowH    = 16;
        final int mpRowH    = 16;
        final int gapBeforeEquip = 8;
        final int equipSlot = 36;
        final int equipGap  = 4;
        final int equipRowH = equipSlot + 4;
        final int tooltipH = padY + nameRowH + classRowH + hpRowH + mpRowH
                            + gapBeforeEquip + equipRowH + padY;

        // X anchor: flush right of the nearby-players panel chrome.
        // Falls back to next-to-button if the panel rect isn't tracked
        // (e.g. legacy non-sprite-HUD render path).
        int tooltipX = (this.spriteHudNearbyPanelRight > 0)
                ? this.spriteHudNearbyPanelRight + 8
                : this.hoveredBtnX + this.hoveredBtnW + 6;
        if (tooltipX + tooltipW > OpenRealmGame.width - 4) {
            tooltipX = Math.max(4, OpenRealmGame.width - tooltipW - 4);
        }
        // Y anchor: vertically center against the hovered entry row,
        // clamped to stay inside the window.
        int tooltipY = this.hoveredBtnY - 4;
        if (tooltipY < 4) tooltipY = 4;
        if (tooltipY + tooltipH > OpenRealmGame.height - 4) {
            tooltipY = Math.max(4, OpenRealmGame.height - tooltipH - 4);
        }

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

        int y = tooltipY + padY + nameRowH;
        // Name (role-colored). Append "[role]" badge if non-empty.
        font.setColor(roleColorFor(p.getChatRole()));
        final String nameLine = p.getName() == null ? "Player" : p.getName();
        font.draw(batch, nameLine, tooltipX + padX, y);
        if (p.getChatRole() != null && !p.getChatRole().isEmpty()) {
            final GlyphLayout nameGl = new GlyphLayout(font, nameLine);
            font.setColor(0x88 / 255f, 0x78 / 255f, 0x68 / 255f, 1f);
            font.draw(batch, "[" + p.getChatRole() + "]",
                    tooltipX + padX + nameGl.width + 6, y);
        }
        // Level + class — webclient parity ("Lv 12 Wizard").
        y += classRowH;
        font.setColor(0x88 / 255f, 0x78 / 255f, 0x68 / 255f, 1f);
        font.draw(batch, levelStr + className, tooltipX + padX, y);
        // HP (cur/max) — red.
        y += hpRowH;
        font.setColor(0xe0 / 255f, 0x55 / 255f, 0x55 / 255f, 1f);
        font.draw(batch, "HP: " + hp + "/" + maxHp, tooltipX + padX, y);
        // MP (cur/max) — blue.
        y += mpRowH;
        font.setColor(0x55 / 255f, 0x77 / 255f, 0xe0 / 255f, 1f);
        font.draw(batch, "MP: " + mp + "/" + maxMp, tooltipX + padX, y);
        // Equipment row — left-justified beneath the stat lines so the
        // 4 slot icons line up with the left edge of the text block.
        y += gapBeforeEquip;
        // Left-justified beneath the stat lines (was centered, which
        // made it visually disconnect from the text block above).
        int equipStartX = tooltipX + padX;
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
                        // Wire protocol: ground loot is GROUND_LOOT_IDX
                        // = [21..28]. Was `i + 20` which collided with
                        // backpack[15] (slot 20) AND produced server-
                        // rejected fromIdx values for all loot picks.
                        this.dragSourceIndex = i + MoveItemPacket.groundLootBase();
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

    /**
     * Hit-test a screen point against every slot rectangle (including
     * empty slots) and return the slot's WIRE PROTOCOL index, or -1 for
     * nothing-hit. Wire layout (per MoveItemPacket): equipment 0..4,
     * backpack 5..20, ground loot 21..28.
     *
     * Sprite-HUD uses the atlas grid cells; each panel anchor is cached
     * by renderSpriteHud so we can compute the cell rect for empty slots
     * (which have no Button to interrogate). Falls back to the legacy
     * sidebar layout when the atlas isn't ready yet — that path uses the
     * pre-Phase-1B 4-equip/8-backpack/8-loot sidebar.
     */
    private int findSlotAtPositionByLayout(int mouseX, int mouseY) {
        this.recomputeLayout();

        if (UiAtlas.isReady() && this.spriteHudInvOnlyX > 0 && this.spriteHudEquipStatsX > 0) {
            final int s = UiAtlas.getDisplayScale();
            final UiComponent cEquipStats = UiAtlas.componentOf("panel.hud.equipment_with_stats");
            // Equipment row (5 slots, indices 0..4 on the wire).
            if (cEquipStats != null) {
                for (int i = 0; i < Player.EQUIPMENT_SLOT_COUNT; i++) {
                    final UiComponent eq = UiAtlas.componentOf("panel.hud.equipment_with_stats." + i);
                    if (eq == null) continue;
                    final int ex = (int)(this.spriteHudEquipStatsX + (eq.getX() - cEquipStats.getX()) * s);
                    final int ey = (int)(this.spriteHudEquipStatsY + (eq.getY() - cEquipStats.getY()) * s);
                    final int ew = (int)(eq.getW() * s);
                    final int eh = (int)(eq.getH() * s);
                    if (mouseX >= ex && mouseX < ex + ew && mouseY >= ey && mouseY < ey + eh) {
                        return i;
                    }
                }
            }
            // Backpack grid (16 cells, indices 5..20 on the wire).
            final UiComponent cInvOnly = UiAtlas.componentOf("panel.hud.inv_only");
            final int[][] invCells = UiAtlas.gridCells("panel.hud.inv_only.grid");
            if (cInvOnly != null && invCells != null) {
                final int backpackBase = Player.EQUIPMENT_SLOT_COUNT;
                for (int i = 0; i < invCells.length; i++) {
                    final int[] cell = invCells[i];
                    final int cx = (int)(this.spriteHudInvOnlyX + (cell[0] - cInvOnly.getX()) * s);
                    final int cy = (int)(this.spriteHudInvOnlyY + (cell[1] - cInvOnly.getY()) * s);
                    final int cw = (int)(cell[2] * s);
                    final int ch = (int)(cell[3] * s);
                    if (mouseX >= cx && mouseX < cx + cw && mouseY >= cy && mouseY < cy + ch) {
                        return backpackBase + i;
                    }
                }
            }
            // Ground-loot grid (up to 8 cells, indices 21..28 on the wire).
            final UiComponent cInvExt = UiAtlas.componentOf("panel.hud.inv_ext");
            final int[][] lootCells = UiAtlas.gridCells("panel.hud.inv_ext.grid");
            if (cInvExt != null && lootCells != null
                    && this.spriteHudInvExtX > 0 && this.spriteHudInvExtY > 0) {
                final int groundBase = MoveItemPacket.groundLootBase();
                for (int i = 0; i < lootCells.length; i++) {
                    final int[] cell = lootCells[i];
                    final int cx = (int)(this.spriteHudInvExtX + (cell[0] - cInvExt.getX()) * s);
                    final int cy = (int)(this.spriteHudInvExtY + (cell[1] - cInvExt.getY()) * s);
                    final int cw = (int)(cell[2] * s);
                    final int ch = (int)(cell[3] * s);
                    if (mouseX >= cx && mouseX < cx + cw && mouseY >= cy && mouseY < cy + ch) {
                        return groundBase + i;
                    }
                }
            }
            return -1;
        }

        // Legacy sidebar fallback. Pre-Phase-1B layout (4 equipment, 2 bag
        // rows of 4, 2 loot rows of 4). Kept so the game still renders if
        // the atlas fails to load. Indices match the wire protocol where
        // possible — equipment 0..4 (only 4 hit-tested), backpack 5..12
        // (only 8 hit-tested via i+5), ground loot 21..28.
        int panelWidth = (OpenRealmGame.width / 5);
        int startX = OpenRealmGame.width - panelWidth;
        if (mouseX < startX || mouseX > OpenRealmGame.width) return -1;
        int col = -1;
        for (int c = 0; c < 4; c++) {
            int sx = this.slotX(c);
            if (mouseX >= sx && mouseX < sx + SLOT_SIZE) { col = c; break; }
        }
        if (col < 0) return -1;
        if (mouseY >= this.layoutEquipY && mouseY < this.layoutEquipY + SLOT_SIZE) return col;
        if (mouseY >= this.layoutBag1Y  && mouseY < this.layoutBag1Y  + SLOT_SIZE)
            return Player.EQUIPMENT_SLOT_COUNT + col;
        if (mouseY >= this.layoutBag2Y  && mouseY < this.layoutBag2Y  + SLOT_SIZE)
            return Player.EQUIPMENT_SLOT_COUNT + 4 + col;
        int gl0 = this.groundLootRowY(0);
        int gl1 = this.groundLootRowY(1);
        final int groundBase = MoveItemPacket.groundLootBase();
        if (mouseY >= gl0 && mouseY < gl0 + SLOT_SIZE) return groundBase + col;
        if (mouseY >= gl1 && mouseY < gl1 + SLOT_SIZE) return groundBase + 4 + col;
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
        if (this.forgeWindow.isVisible() && fromIndex >= 0 && fromIndex <= 20) {
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

        // Potion-storage drop-zone takes priority when its window is up.
        // Inventory→storage moves go through PotionStorageMovePacket, not
        // the normal MoveItemPacket pipeline, because the storage state
        // lives off-inventory on the server.
        if (this.potionStorageWindow.isVisible() && fromIndex >= 0 && fromIndex <= 20) {
            final int mx = com.badlogic.gdx.Gdx.input.getX();
            final int my = com.badlogic.gdx.Gdx.input.getY();
            if (this.potionStorageWindow.tryAcceptDrop(mx, my, fromIndex)) {
                return;
            }
        }

        // Wire protocol (Phase 1B): equipment 0..4 (5 slots), backpack
        // 5..20, ground loot 21..28. Was stuck on the pre-Phase-1B 4-slot
        // equipment layout — backpack[15] (slot 20) was classified as
        // ground loot, and equipment[4] (ring) was classified as
        // backpack. Both broke drag-drop on the affected slots.
        boolean fromIsGround = fromIndex >= MoveItemPacket.groundLootBase()
                && fromIndex < MoveItemPacket.groundLootBase() + 8;
        boolean targetIsGround = targetIndex >= MoveItemPacket.groundLootBase()
                && targetIndex < MoveItemPacket.groundLootBase() + 8;
        boolean fromIsEquip = fromIndex >= 0 && fromIndex < Player.EQUIPMENT_SLOT_COUNT;
        boolean targetIsEquip = targetIndex >= 0 && targetIndex < Player.EQUIPMENT_SLOT_COUNT;

        if (targetIndex == -1) {
            // Dropped outside any slot: drop item
            this.playState.getRealmManager().moveItem(-1, fromIndex, true, false);
        } else if (fromIsGround && !targetIsGround) {
            // Ground -> inventory/equip: pickup. HP/MP potions route to the
            // potion counters on the server; pinning targetSlot to a fixed
            // inventory index avoids the equip-validation path on slots 0-3.
            final Slots srcSlot = this.groundLoot[fromIndex - MoveItemPacket.groundLootBase()];
            final GameItem srcItem = srcSlot != null ? srcSlot.getItem() : null;
            if (srcItem != null
                    && (srcItem.getItemId() == com.openrealm.game.entity.Player.HP_POTION_ITEM_ID
                     || srcItem.getItemId() == com.openrealm.game.entity.Player.MP_POTION_ITEM_ID)) {
                this.playState.getRealmManager().moveItem(Player.EQUIPMENT_SLOT_COUNT, fromIndex, false, false);
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
     * interaction is invisible without a UI hint. Suppressed while any
     * interactive modal (forge, fame store, potion storage) is open,
     * since the player is already inside the UI they would otherwise
     * be prompted to enter.
     */
    private void renderInteractPrompt(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        if (this.playState == null || this.playState.getPlayer() == null) return;
        // Hide prompt while the player is already in the relevant modal —
        // "press F" is meaningless once they're inside.
        if (this.forgeWindow != null && this.forgeWindow.isVisible()) return;
        if (this.fameStoreWindow != null && this.fameStoreWindow.isVisible()) return;
        if (this.potionStorageWindow != null && this.potionStorageWindow.isVisible()) return;
        try {
            final String type = this.playState.getNearbyInteractionType();
            if (type == null) return;
            final String label;
            if ("forge".equalsIgnoreCase(type)) label = "PRESS F TO USE FORGE";
            else if ("fame_store".equalsIgnoreCase(type)) label = "PRESS F TO OPEN FAME SHOP";
            else if ("potion_storage".equalsIgnoreCase(type)) label = "PRESS F TO OPEN POTION STORAGE";
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
        final int padX = 14, padY = 10;
        final int boxW = (int) gl.width + padX * 2;
        final int boxH = (int) gl.height + padY * 2;
        // Center horizontally on the screen, anchor ABOVE the bottom-
        // center hotbar (panel.hud.equipment is 36 px tall at 1x =
        // 72 px at 2x display scale, plus the 16-px margin above it).
        // Stack additional hints (e.g. forge prompt) ABOVE this one.
        final int hotbarReserve = 72 + 16;
        final int boxX = (OpenRealmGame.width  - boxW) / 2;
        final int boxY = OpenRealmGame.height - hotbarReserve - 12 - boxH - stackIndex * (boxH + 6);

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
        // True vertical center: glyph baseline at boxY + boxH/2 + gl.height/2
        // (cap height included), which puts the visual center of the
        // capital glyphs at the exact box center. The previous formula
        // with - 2f fudge had the text riding the bottom of the box.
        font.draw(batch, text,
                boxX + (boxW - gl.width) / 2f,
                boxY + boxH / 2f + gl.height / 2f);
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

        // Component lookups for the new dedicated panels. Names mirror the
        // atlas component IDs exactly:
        //   panel.hud.player_info        — class sprite + name + HP/MP/Fame bars
        //   panel.hud.chat               — chat with dedicated text_area + input_box rects
        //   panel.hud.equipment_with_stats — 4 equip slots + 8-stat grid
        //   panel.hud.inv_only           — 4×4 inventory grid
        //   panel.hud.inv_ext            — 4×2 ground-loot grid (conditional)
        //   panel.hud.equipment          — bottom-center equipment hotbar
        //   panel.hud.potion ×2          — HP / MP potion ovals
        //   panel.container.small        — minimap chrome
        final UiComponent cPlayerInfo = UiAtlas.componentOf("panel.hud.player_info");
        final UiComponent cChat       = UiAtlas.componentOf("panel.hud.chat");
        final UiComponent cEquipStats = UiAtlas.componentOf("panel.hud.equipment_with_stats");
        final UiComponent cInvOnly    = UiAtlas.componentOf("panel.hud.inv_only");
        final UiComponent cMinimap    = UiAtlas.componentOf("panel.container.small");
        final UiComponent cNearby     = UiAtlas.componentOf("panel.container.small");
        final UiComponent cInvExt     = UiAtlas.componentOf("panel.hud.inv_ext");
        final UiComponent cHotbar     = UiAtlas.componentOf("panel.hud.equipment");
        final UiComponent cPotion     = UiAtlas.componentOf("panel.hud.potion");
        if (cPlayerInfo == null || cChat == null || cEquipStats == null
                || cInvOnly == null || cMinimap == null) return;

        // ---- Compute panel screen positions ----
        // LEFT  : playerInfo (top) → chat (bottom-anchored)
        // RIGHT : minimap → equipStats → invOnly → invExt (top-down)
        // BOT   : hotbar with potion ovals flanking
        final float playerInfoW = cPlayerInfo.getW() * s;
        final float playerInfoH = cPlayerInfo.getH() * s;
        final float playerInfoX = margin;
        final float playerInfoY = margin;

        // Nearby-players panel sits BETWEEN playerInfo (top) and chat (bottom)
        // on the left column. Uses panel.container.small chrome. When the
        // player is in a party, a SECOND identical panel is stamped above
        // nearby to hold the party-members list — same chrome twice, one
        // labelled "PARTY", one "PLAYERS NEARBY", stacked with an 8px gap.
        // Available vertical space is split between them.
        final float nearbyW = cNearby != null ? cNearby.getW() * s : 0;
        final float nearbyFullH = cNearby != null ? cNearby.getH() * s : 0;
        final float nearbyX = margin;
        final boolean partyVisible = (this.playState != null && this.playState.getPartyId() != 0L);
        final float panelGap = 8f;
        // When in a party: split available height in half so two equal-sized
        // chromes stack within the original nearby slot. Otherwise nearby
        // gets the full slot to itself.
        final float partyH  = partyVisible ? (nearbyFullH - panelGap) / 2f : 0f;
        final float nearbyH = partyVisible ? (nearbyFullH - panelGap) / 2f : nearbyFullH;
        final float partyY  = playerInfoY + playerInfoH + 8;
        final float nearbyY = partyVisible ? (partyY + partyH + panelGap)
                                            : (playerInfoY + playerInfoH + 8);

        final float chatW = cChat.getW() * s;
        final float chatH = cChat.getH() * s;
        final float chatX = margin;
        final float chatY = H - margin - chatH;

        final float minimapW = cMinimap.getW() * s;
        final float minimapH = cMinimap.getH() * s;
        final float minimapX = W - margin - minimapW;
        final float minimapY = margin;

        final float equipStatsW = cEquipStats.getW() * s;
        final float equipStatsH = cEquipStats.getH() * s;
        final float equipStatsX = W - margin - equipStatsW;
        final float equipStatsY = minimapY + minimapH + 8;

        final float invOnlyW = cInvOnly.getW() * s;
        final float invOnlyH = cInvOnly.getH() * s;
        final float invOnlyX = W - margin - invOnlyW;
        final float invOnlyY = equipStatsY + equipStatsH + 8;

        final boolean lootVisible = !this.isGroundLootEmpty();
        final float invExtW = cInvExt != null ? cInvExt.getW() * s : 0;
        final float invExtH = cInvExt != null ? cInvExt.getH() * s : 0;
        final float invExtX = W - margin - invExtW;
        final float invExtY = invOnlyY + invOnlyH + 4;

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

        // ---- Pass 1: panel chrome (atlas blits) ----
        final TextureRegion rPlayerInfo = UiAtlas.region("panel.hud.player_info");
        final TextureRegion rChat       = UiAtlas.region("panel.hud.chat");
        final TextureRegion rMinimap    = UiAtlas.region("panel.container.small");
        final TextureRegion rEquipStats = UiAtlas.region("panel.hud.equipment_with_stats");
        final TextureRegion rInvOnly    = UiAtlas.region("panel.hud.inv_only");
        final TextureRegion rInvExt     = UiAtlas.region("panel.hud.inv_ext");
        final TextureRegion rHotbar     = UiAtlas.region("panel.hud.equipment");
        final TextureRegion rPotion     = UiAtlas.region("panel.hud.potion");

        if (rPlayerInfo != null) batch.draw(rPlayerInfo, playerInfoX, playerInfoY, playerInfoW, playerInfoH);
        // Nearby-players panel chrome (small container, between playerInfo and chat).
        // When in a party, also stamp a SECOND identical chrome ABOVE nearby
        // for the party-members list — two distinct panels, same look.
        final TextureRegion rNearby = UiAtlas.region("panel.container.small");
        if (partyVisible && rNearby != null && cNearby != null) {
            batch.draw(rNearby, nearbyX, partyY, nearbyW, partyH);
        }
        if (rNearby != null && cNearby != null) batch.draw(rNearby, nearbyX, nearbyY, nearbyW, nearbyH);
        if (rMinimap    != null) batch.draw(rMinimap,    minimapX,    minimapY,    minimapW,    minimapH);
        if (rEquipStats != null) batch.draw(rEquipStats, equipStatsX, equipStatsY, equipStatsW, equipStatsH);
        if (rInvOnly    != null) batch.draw(rInvOnly,    invOnlyX,    invOnlyY,    invOnlyW,    invOnlyH);
        if (lootVisible && rInvExt != null) batch.draw(rInvExt, invExtX, invExtY, invExtW, invExtH);
        if (rHotbar     != null) batch.draw(rHotbar,     hotbarX,     hotbarY,     hotbarW,     hotbarH);
        if (rPotion != null) {
            batch.draw(rPotion, hpPotX, hpPotY, potionW, potionH);
            batch.draw(rPotion, mpPotX, mpPotY, potionW, potionH);
        }
        // Chat chrome — only when chat is expanded.
        final boolean chatExpanded = (this.playerChat != null) && !this.playerChat.isCollapsed();
        if (rChat != null && chatExpanded) batch.draw(rChat, chatX, chatY, chatW, chatH);

        // ---- Pass 2: player class sprite into player_info.player rect ----
        final UiComponent rPlayerSprite = UiAtlas.componentOf("panel.hud.player_info.player");
        if (rPlayerSprite != null && this.playState != null && this.playState.getPlayer() != null) {
            final float vx = playerInfoX + (rPlayerSprite.getX() - cPlayerInfo.getX()) * s;
            final float vy = playerInfoY + (rPlayerSprite.getY() - cPlayerInfo.getY()) * s;
            final float vw = rPlayerSprite.getW() * s;
            final float vh = rPlayerSprite.getH() * s;
            final Color prev = batch.getColor();
            batch.setColor(Color.WHITE);
            try {
                final TextureRegion frame = this.getHudIdleFrame(this.playState.getPlayer());
                if (frame != null && frame.getRegionWidth() > 0) {
                    final float spriteSize = Math.min(vw, vh) * 0.85f;
                    batch.draw(frame, vx + (vw - spriteSize) / 2f, vy + (vh - spriteSize) / 2f,
                            spriteSize, spriteSize);
                }
            } catch (Exception ignore) { /* sprite sheet not ready yet */ }
            batch.setColor(prev);
        }

        // ---- Pass 3: equipment slots (panel.hud.equipment_with_stats.0..4)
        //              + inventory grid (panel.hud.inv_only.grid). ----
        // Phase 1B: 5 equipment slots (weapon, armor, gauntlets, boots, ring).
        // Cache panel origins so findSlotAtPositionByLayout (drop hit-test)
        // can compute each slot's rectangle even when the slot is empty
        // and has no Button to interrogate.
        this.spriteHudEquipStatsX = (int) equipStatsX;
        this.spriteHudEquipStatsY = (int) equipStatsY;
        this.spriteHudInvOnlyX = (int) invOnlyX;
        this.spriteHudInvOnlyY = (int) invOnlyY;
        for (int i = 0; i < Player.EQUIPMENT_SLOT_COUNT; i++) {
            final UiComponent eq = UiAtlas.componentOf("panel.hud.equipment_with_stats." + i);
            if (eq == null) continue;
            final float ex = equipStatsX + (eq.getX() - cEquipStats.getX()) * s;
            final float ey = equipStatsY + (eq.getY() - cEquipStats.getY()) * s;
            this.repositionSlotButton(this.inventory, i, ex, ey);
            this.drawHudItemIcon(batch, this.getInventoryItem(i),
                    ex, ey, eq.getW() * s, eq.getH() * s);
        }
        final int[][] cells = UiAtlas.gridCells("panel.hud.inv_only.grid");
        // Backpack starts at EQUIPMENT_SLOT_COUNT (5) after Phase 1B — used to
        // be 4. Without this shift, the first backpack cell overwrote slot 4
        // (ring) and the ring slot button hung at its initial slotX()/
        // layoutEquipY position, ending up floating mid-screen.
        final int backpackBase = Player.EQUIPMENT_SLOT_COUNT;
        for (int i = 0; i < cells.length; i++) {
            final int[] cell = cells[i];
            final float cx = invOnlyX + (cell[0] - cInvOnly.getX()) * s;
            final float cy = invOnlyY + (cell[1] - cInvOnly.getY()) * s;
            this.repositionSlotButton(this.inventory, backpackBase + i, cx, cy);
            this.drawHudItemIcon(batch, this.getInventoryItem(backpackBase + i),
                    cx, cy, cell[2] * s, cell[3] * s);
        }

        // Bottom-center hotbar (panel.hud.equipment) renders ABILITY icons
        // from the ability's own sprite fields (spriteKey/row/col), matching
        // every other data type. Cooldown overlay + SP pips drawn in a
        // post-pass below (shape renderer needs to switch out of batch).
        final int[][] hotbarCells = UiAtlas.gridCells("panel.hud.equipment.grid");
        final Player localPlayer = (this.playState != null) ? this.playState.getPlayer() : null;
        // Per-slot cell coords captured so the post-pass can paint cooldown
        // overlays + SP pips without re-computing the layout.
        final float[][] hotbarCellPx = new float[4][4]; // [slot] = {x, y, w, h}
        for (int i = 0; i < hotbarCells.length && i < 4; i++) {
            final int[] cell = hotbarCells[i];
            final float cx = hotbarX + (cell[0] - cHotbar.getX()) * s;
            final float cy = hotbarY + (cell[1] - cHotbar.getY()) * s;
            final float cw = cell[2] * s;
            final float ch = cell[3] * s;
            hotbarCellPx[i][0] = cx;
            hotbarCellPx[i][1] = cy;
            hotbarCellPx[i][2] = cw;
            hotbarCellPx[i][3] = ch;
            final Ability ab = (localPlayer != null) ? localPlayer.getActiveAbility(i) : null;
            this.drawAbilityHudIcon(batch, ab, cx, cy, cw, ch);
        }
        // Stash for the cooldown/SP overlay pass below (drawn after batch.end()).
        this._lastHotbarCellPx = hotbarCellPx;

        // Cache loot panel coords so buildGroundLootSlotButton can
        // spawn freshly-built Buttons directly at the sprite-HUD
        // grid position (instead of the off-screen legacy coords
        // that dropped every first-click).
        if (cInvExt != null) {
            this.spriteHudInvExtX = (int) invExtX;
            this.spriteHudInvExtY = (int) invExtY;
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

        // ---- Pass 4: HP / MP / Fame bars positioned at their dedicated
        //              atlas rects inside panel.hud.player_info. ----
        final UiComponent rHpRect   = UiAtlas.componentOf("panel.hud.player_info.hp");
        final UiComponent rMpRect   = UiAtlas.componentOf("panel.hud.player_info.mp");
        final UiComponent rFameRect = UiAtlas.componentOf("panel.hud.player_info.fame");
        if (this.hp != null && rHpRect != null) {
            this.hp.getPos().x   = playerInfoX + (rHpRect.getX() - cPlayerInfo.getX()) * s;
            this.hp.getPos().y   = playerInfoY + (rHpRect.getY() - cPlayerInfo.getY()) * s;
            this.hp.setBarWidth ((int)(rHpRect.getW() * s));
            this.hp.setBarHeight((int)(rHpRect.getH() * s));
        }
        if (this.mp != null && rMpRect != null) {
            this.mp.getPos().x   = playerInfoX + (rMpRect.getX() - cPlayerInfo.getX()) * s;
            this.mp.getPos().y   = playerInfoY + (rMpRect.getY() - cPlayerInfo.getY()) * s;
            this.mp.setBarWidth ((int)(rMpRect.getW() * s));
            this.mp.setBarHeight((int)(rMpRect.getH() * s));
        }
        if (this.xp != null && rFameRect != null) {
            this.xp.getPos().x   = playerInfoX + (rFameRect.getX() - cPlayerInfo.getX()) * s;
            this.xp.getPos().y   = playerInfoY + (rFameRect.getY() - cPlayerInfo.getY()) * s;
            this.xp.setBarWidth ((int)(rFameRect.getW() * s));
            this.xp.setBarHeight((int)(rFameRect.getH() * s));
        }
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        if (this.hp != null) this.hp.renderShapes(shapes);
        if (this.mp != null) this.mp.renderShapes(shapes);
        if (this.xp != null) this.xp.renderShapes(shapes);
        // Hotbar cooldown overlay + SP pip column — drawn after the HP/MP
        // bars but before batch.begin() so the dark fade sits ON TOP of
        // the ability icons painted earlier in the batch pass. Mirrors
        // ui-widgets.updateAbilityBar in the webclient.
        this.renderAbilityHotbarOverlays(shapes, localPlayer);
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
        batch.begin();
        if (this.hp != null) this.hp.renderText(batch, font);
        if (this.mp != null) this.mp.renderText(batch, font);
        if (this.xp != null) this.xp.renderText(batch, font);

        // ---- Pass 5: name + level into player_info.player_name rect,
        //              8-stat grid into equipment_with_stats.stats rect. ----
        final UiComponent rNameRect = UiAtlas.componentOf("panel.hud.player_info.player_name");
        if (rNameRect != null && this.playState != null && this.playState.getPlayer() != null) {
            final float nx = playerInfoX + (rNameRect.getX() - cPlayerInfo.getX()) * s;
            final float ny = playerInfoY + (rNameRect.getY() - cPlayerInfo.getY()) * s;
            String nameLine = this.playState.getPlayer().getName() != null
                    ? this.playState.getPlayer().getName() : "Player";
            int lvl = 1;
            try { lvl = GameDataManager.EXPERIENCE_LVLS.getLevel(this.playState.getPlayer().getExperience()); }
            catch (Exception ignore) { /* xp data not yet loaded */ }
            final float origNameScale = font.getData().scaleX;
            font.getData().setScale(0.95f);
            font.setColor(0.78f, 0.66f, 0.43f, 1f); // tan accent
            font.draw(batch, nameLine + "  Lv " + lvl, nx + 4, ny + 4);
            font.setColor(Color.WHITE);
            font.getData().setScale(origNameScale);
        }
        final UiComponent rStatsRect = UiAtlas.componentOf("panel.hud.equipment_with_stats.stats");
        if (rStatsRect != null) {
            final float statsX = equipStatsX + (rStatsRect.getX() - cEquipStats.getX()) * s;
            final float statsY = equipStatsY + (rStatsRect.getY() - cEquipStats.getY()) * s;
            final float statsW = rStatsRect.getW() * s;
            this.renderStatsAt(batch, font, (int) statsX, (int) statsW, (int) statsY);
        }

        // ---- Pass 6: potion icons + counts inside the oval panels ----
        this.drawPotionWidget(batch, font, hpPotX, hpPotY, potionW, potionH, true);
        this.drawPotionWidget(batch, font, mpPotX, mpPotY, potionW, potionH, false);

        // ---- Pass 7: minimap into panel.container.small (top-RIGHT) ----
        if (this.minimap != null) {
            final int mmInset = 8;
            final int mmSize = (int) Math.min(minimapW - mmInset * 2, minimapH - mmInset * 2);
            this.minimap.setLayout((int)(minimapX + mmInset), (int)(minimapY + mmInset), Math.max(32, mmSize));
        }

        // ---- Pass 8: chat layout — full panel.hud.chat rect; PlayerChat's
        //              internal layout splits into messages + input. ----
        if (this.playerChat != null) {
            this.playerChat.setLayout((int) chatX, (int) chatY, (int) chatW, (int) chatH);
        }

        // Nearby-players list re-routed into its dedicated bottom-left
        // panel.container.small. renderNearbyPlayers reads layoutNearbyY
        // for the section header — set those fields so it lands inside
        // the right rect.
        this.spriteHudNearbyX = (int)(nearbyX + 8);
        this.spriteHudNearbyW = (int)(nearbyW - 16);
        this.spriteHudNearbyY = (int)(nearbyY + 8);
        this.spriteHudNearbyPanelRight  = (int)(nearbyX + nearbyW);
        this.spriteHudNearbyPanelTop    = (int)(nearbyY);
        this.spriteHudNearbyPanelBottom = (int)(nearbyY + nearbyH);
        this.spriteHudNearbyEnabled = (cNearby != null);
        // Party panel content rect — mirrors nearby. Only consulted when
        // partyVisible is true; renderPartyMembers anchors its header at
        // spriteHudPartyY so the gold "PARTY N/4" sits inside the upper
        // chrome stamped above nearby.
        this.spriteHudPartyEnabled = partyVisible && cNearby != null;
        this.spriteHudPartyX = (int)(nearbyX + 8);
        this.spriteHudPartyW = (int)(nearbyW - 16);
        this.spriteHudPartyY = (int)(partyY + 8);
    }

    // Sprite HUD — nearby panel position passed to renderNearbyPlayers().
    private int spriteHudNearbyX = 0;
    private int spriteHudNearbyY = 0;
    private int spriteHudNearbyW = 0;
    /** Cached sprite-HUD inv_ext (loot bag) panel coords — used by
     *  buildGroundLootSlotButton so the new Button's bounds are
     *  spawned at the correct sprite-HUD position from frame 1
     *  instead of the legacy off-screen slotX/groundLootRowY. */
    private int spriteHudInvExtX = 0;
    private int spriteHudInvExtY = 0;
    /** Cached sprite-HUD invOnly (backpack) panel coords — drop-zone
     *  hit-testing in findSlotAtPositionByLayout consults the atlas
     *  grid cells relative to these to compute each backpack slot
     *  rectangle, including empty slots that have no Button to
     *  hit-test against. */
    private int spriteHudInvOnlyX = 0;
    private int spriteHudInvOnlyY = 0;
    /** Cached sprite-HUD equipment-with-stats panel coords — same
     *  reason as spriteHudInvOnly, but for the 5 equipment slots. */
    private int spriteHudEquipStatsX = 0;
    private int spriteHudEquipStatsY = 0;
    private int spriteHudNearbyPanelRight = 0; // outer chrome right edge — tooltip anchors here
    private int spriteHudNearbyPanelTop = 0;
    private int spriteHudNearbyPanelBottom = 0;
    private boolean spriteHudNearbyEnabled = false;
    // Sprite HUD — second panel stamped above nearby for the party list.
    private int spriteHudPartyX = 0;
    private int spriteHudPartyY = 0;
    private int spriteHudPartyW = 0;
    private boolean spriteHudPartyEnabled = false;
    /** When true, renderNearbyPlayers skips its internal renderPartyMembers
     *  call so the caller can draw the party section in its own panel
     *  chrome instead (sprite-HUD two-panel layout). */
    private boolean suppressInternalParty = false;

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
        // Six non-HP/MP stats laid out as 2 columns × 3 rows so each
        // entry has horizontal room for "LBL VAL +BONUS" without
        // bleeding past the panel edge. Yellow when maxed for class,
        // green positive bonus / red negative — webclient parity.
        final float origScale = font.getData().scaleX;
        font.getData().setScale(0.6f);
        final int rowH = 13;
        final int colW = panelWidth / 2;
        final int textXcol0 = startX;
        final int textXcol1 = startX + colW;
        final int startY  = statsY + 12;

        // Stat order: ATT, DEF, SPD, DEX, VIT, WIS — same as before.
        // Layout (column-major so left col = first 3, right col = last 3):
        //   ATT   DEX
        //   DEF   VIT
        //   SPD   WIS
        final int[]    statMaxedIdx = {  3,    6,    4,    5,    2,    7  };
        final String[] statLabels   = { "ATT", "DEF", "SPD", "DEX", "VIT", "WIS" };
        final int[] computedVals = { computed.getAtt(), computed.getDef(),
                computed.getSpd(), computed.getDex(), computed.getVit(),
                computed.getWis() };
        final int[] baseVals = (base != null)
                ? new int[] { base.getAtt(), base.getDef(), base.getSpd(),
                              base.getDex(), base.getVit(), base.getWis() }
                : computedVals;

        final GlyphLayout gl = new GlyphLayout();
        for (int i = 0; i < statLabels.length; i++) {
            final int col = i / 3;
            final int row = i % 3;
            final int textX = (col == 0) ? textXcol0 : textXcol1;
            final int y = startY + rowH * row;
            final int compV = computedVals[i];
            final int baseV = baseVals[i];
            final int bonus = compV - baseV;
            font.setColor(p.isStatMaxed(statMaxedIdx[i]) ? Color.YELLOW : Color.WHITE);
            final String main = statLabels[i] + " " + compV;
            font.draw(batch, main, textX, y);
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

        // Manual centering. font.draw with the project's y-DOWN ortho camera
        // takes (x, y) as the TOP-LEFT of the text bounding box — NOT the
        // baseline. Previous attempts treated y as the baseline, which
        // shoved both pill labels to the bottom edge of their pills.
        // Vertical center = pillY + (pillH - textHeight) / 2.
        final float diffTextX = pillX + (pillW - glDiff.width) / 2f;
        final float fameTextX = pillX + (pillW - glFame.width) / 2f;
        final float diffTextY = diffY + (pillH - glDiff.height) / 2f;
        final float fameTextY = fameY + (pillH - glFame.height) / 2f;

        font.setColor(1f, 1f, 1f, 1f);
        font.draw(batch, diffStr, diffTextX, diffTextY);

        font.setColor(255/255f, 216/255f, 107/255f, 1f);
        font.draw(batch, fameStr, fameTextX, fameTextY);

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

    /**
     * Draw an ability icon using the standard {@code spriteKey} / {@code row} /
     * {@code col} fields on {@link Ability} — same convention as every other
     * data type (items/enemies/tiles).
     */
    private void drawAbilityHudIcon(SpriteBatch batch, Ability ab,
                                     float x, float y, float w, float h) {
        if (ab == null || ab.getSpriteKey() == null || ab.getSpriteKey().isEmpty()) return;
        final int spriteSize = ab.getSpriteSize() > 0 ? ab.getSpriteSize() : 8;
        final Sprite spr = GameSpriteManager.loadSprite(ab.getCol(), ab.getRow(),
                ab.getSpriteKey(), spriteSize);
        if (spr == null || spr.getRegion() == null) return;
        final float iconSize = spriteSize * UiAtlas.getIconScale() * UiAtlas.getDisplayScale();
        batch.draw(spr.getRegion(),
                x + (w - iconSize) / 2f, y + (h - iconSize) / 2f,
                iconSize, iconSize);
    }

    /** Cached pixel rects for the 4 hotbar slots, set during the sprite pass
     *  and consumed by {@link #renderAbilityHotbarOverlays}. Avoids re-running
     *  the atlas math twice per frame. */
    private float[][] _lastHotbarCellPx;

    /**
     * Paint cooldown fill + SP pip column on top of each ability icon.
     * Mirror of webclient ui-widgets.updateAbilityBar. Driven by the
     * player's {@code abilityCooldowns} and {@code abilitySkillPoints}
     * — both are local mirrors that match the server.
     */
    private void renderAbilityHotbarOverlays(ShapeRenderer shapes, Player localPlayer) {
        if (localPlayer == null || _lastHotbarCellPx == null) return;
        final long now = System.currentTimeMillis();
        final long[] cds = localPlayer.getAbilityCooldowns();
        for (int slot = 0; slot < 4; slot++) {
            final float[] cell = _lastHotbarCellPx[slot];
            if (cell == null) continue;
            final float cx = cell[0], cy = cell[1], cw = cell[2], ch = cell[3];
            final Ability ab = localPlayer.getActiveAbility(slot);
            if (ab == null) continue;
            // Cooldown overlay — dark fill from the TOP of the cell as the
            // CD ticks down. Webclient uses the same "drain from top" idiom
            // (height-based fill that shrinks over time).
            if (cds != null && slot < cds.length && cds[slot] > now) {
                final long base = ab.getBaseCooldownMs();
                if (base > 0) {
                    final long remain = Math.min(cds[slot] - now, base);
                    final float frac = Math.max(0f, Math.min(1f, remain / (float) base));
                    shapes.setColor(0f, 0f, 0f, 0.62f);
                    shapes.rect(cx, cy + ch * (1f - frac), cw, ch * frac);
                }
            }
            // SP pip column — vertical row of orange-or-grey 4px squares
            // along the right edge of the cell. One pip per maxSkillPoints,
            // filled up to invested level. Mirrors web "ability-sp-pipcol".
            final int maxSp = ab.getMaxSkillPoints() <= 0 ? 5 : ab.getMaxSkillPoints();
            final int invested = localPlayer.getSkillLevel(ab.getId());
            if (maxSp > 0) {
                final float pipW = 3.5f;
                final float pipH = Math.max(2.5f, ch / (maxSp + 1.5f));
                final float pipX = cx + cw - pipW - 2f;
                for (int p = 0; p < maxSp; p++) {
                    final float py = cy + 2f + p * (pipH + 1f);
                    if (p < invested) {
                        shapes.setColor(1.0f, 0.65f, 0.18f, 0.95f); // amber filled
                    } else {
                        shapes.setColor(0.18f, 0.16f, 0.13f, 0.75f); // dim empty
                    }
                    shapes.rect(pipX, py, pipW, pipH);
                }
            }
        }
    }
}
