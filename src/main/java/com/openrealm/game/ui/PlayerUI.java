package com.openrealm.game.ui;

import java.time.Instant;
import java.util.ArrayList;
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
import com.openrealm.net.realm.Realm;
import com.openrealm.game.model.ItemTooltip;
import com.openrealm.game.model.ability.Ability;
import com.openrealm.game.model.ability.PassiveAbility;
import com.openrealm.game.model.AbilityTooltip;
import com.openrealm.game.model.PassiveTooltip;
import com.openrealm.game.model.AnimationModel;
import com.openrealm.game.model.AnimationSetModel;
import com.openrealm.game.model.AnimationFrameModel;
import com.openrealm.net.entity.NetPartyMember;
import com.openrealm.net.entity.NetGameItem;
import com.openrealm.game.model.CharacterClassModel;
import com.badlogic.gdx.graphics.Texture;
import com.openrealm.game.state.PlayState;
import com.openrealm.game.state.RealmTransitionState;
import com.openrealm.net.client.packet.UpdatePlayerTradeSelectionPacket;
import com.openrealm.net.entity.NetInventorySelection;
import com.openrealm.net.entity.NetStats;
import com.openrealm.net.entity.NetTradeSelection;
import com.openrealm.net.messaging.CommandType;
import com.openrealm.net.messaging.ServerCommandMessage;
import com.openrealm.net.server.packet.MoveItemPacket;
import com.openrealm.net.server.packet.ItemStoreMovePacket;
import com.openrealm.net.server.packet.SplitStackPacket;
import com.openrealm.net.server.packet.CommandPacket;
import com.openrealm.net.server.packet.TextPacket;
import com.openrealm.util.KeyHandler;
import com.openrealm.util.MouseHandler;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
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
    private static final String LOG_NS = "[CLIENT](player-ui)";

    private static final Color UI_ROLE_SYSADMIN = new Color(1.00f, 0.25f, 0.25f, 1f);
    private static final Color UI_ROLE_ADMIN    = new Color(0.25f, 0.50f, 0.88f, 1f);
    private static final Color UI_ROLE_MOD      = new Color(0.25f, 0.75f, 0.25f, 1f);
    private static final Color UI_ROLE_EDITOR   = new Color(0.63f, 0.25f, 0.75f, 1f);
    private static final Color UI_ROLE_DEMO     = new Color(0.80f, 0.80f, 0.80f, 1f);
    private static final Color UI_ROLE_DEFAULT  = new Color(0.93f, 0.93f, 0.93f, 1f);

    private static final int CTX_MENU_W = 180;
    private static final int CTX_MENU_HEADER_H = 22;
    private static final int CTX_MENU_OPTION_H = 22;

    private static final int HP_POTION_ITEM_ID = 296;
    private static final int MP_POTION_ITEM_ID = 297;

    /** cell 0 = class passive label, cells 1..3 = active abilities bound via hotbarBindings[0..3]. */
    private static final int HOTBAR_SLOT_COUNT = 4;

    private static final float DRAG_THRESHOLD = 8.0f;
    private static final long DOUBLE_CLICK_MS = 400L;

    private static final int INV_PAGE_BASE = Player.EQUIPMENT_SLOT_COUNT;
    private static final int INV_PAGE_SIZE = 20;

    /** Off-screen parking coordinate for hidden slot buttons: far enough out that Button.bounds.inside() never matches. */
    private static final float OFFSCREEN_PARK = -100000f;

    private static final int PANEL_INSET     = 8;
    private static final int SLOT_SIZE       = 56;
    private static final int SLOT_GAP        = 4;
    private static final int HEADER_Y        = 16;
    private static final int FAME_Y          = 28;
    private static final int FAME_H          = 24;

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
    @lombok.Getter @lombok.Setter
    private String pendingTradeRequestFrom = null;
    private String pendingPartyInviteFrom = null;
    private long   pendingPartyInviteExpiresAt = 0L;
    private Button partyInviteAcceptBtn = null;
    private Button partyInviteDeclineBtn = null;
    @lombok.Getter @lombok.Setter
    private long pendingTradeRequestStartMs = 0L;
    private Button tradeRequestAcceptBtn = null;
    private Button tradeRequestDeclineBtn = null;
    /** 0 means no scheduled close. */
    private long tradeOverlayCloseAtMs = 0L;
    /** Snapshot of the partner's full inventory at trade-accept; on-wire selection packets carry only Boolean[] flags. */
    @lombok.Getter @lombok.Setter
    private GameItem[] partnerInventory = null;
    @lombok.Getter @lombok.Setter
    private int partnerClassId = 0;
    @lombok.Getter @lombok.Setter
    private int partnerDyeId = 0;
    private Button confirmTradeButton = null;
    private Button cancelTradeButton = null;
    private Button[] tradeMyButtons = null;
    /** Local-only confirm flag; the network types carry no per-side confirmed bit. Cleared on any selection change. */
    private boolean myTradeConfirmed = false;

    private Map<Integer, TextureRegion> classIconCache = new HashMap<>();
    private List<Button> nearbyPlayerButtons = new ArrayList<>();
    private List<Player> nearbyPlayerList = new ArrayList<>();
    private Player hoveredPlayer = null;
    private int hoveredBtnX = 0;
    private int hoveredBtnY = 0;
    private int hoveredBtnW = 0;
    private int hoveredBtnH = 0;
    private long lastNearbyRefresh = 0;

    private Player contextMenuPlayer = null;
    private int contextMenuX = 0;
    private int contextMenuY = 0;
    private boolean prevContextMenuMouseDown = false;
    private boolean prevPartyKickMouseDown = false;
    private boolean prevLootDebugMouseDown = false;

    private int dragSourceIndex = -1;
    private boolean isDragging = false;
    private Vector2f dragStartPos = null;
    private long lastSlotClickTime = 0L;
    private int lastSlotClickIdx = -1;
    private ItemTooltip activeTooltip = null;
    /** Mutually exclusive with activeTooltip; reset every updateTooltip pass. */
    private AbilityTooltip activeAbilityTooltip = null;
    /** Distinct from activeAbilityTooltip: passives have no MP/cooldown/SP. */
    private PassiveTooltip activePassiveTooltip = null;
    /** Cd-strip cell rects captured by renderPartyMembers for hover hit-testing;
     *  each entry is [x, y, w, h, abilityId, investedSp, vit, wis, hp, mp, str, def, spd, dex]. */
    private final List<float[]> _lastPartyAbilityCells = new ArrayList<>();
    /** Passive-cell rects for the party strip; each entry is [x, y, w, h, classId]. */
    private final List<float[]> _lastPartyPassiveCells = new ArrayList<>();
    /** Party member row rects + parallel refs, captured by renderPartyMembers. */
    private final List<float[]> _lastPartyMemberRows = new ArrayList<>();
    private final List<NetPartyMember> _lastPartyMemberRefs = new ArrayList<>();
    /** Member whose inspect panel (full stats + equipment) is open. */
    private NetPartyMember hoveredPartyMember = null;
    /** Inspect-panel rect so the hover persists moving from row into panel. */
    private float[] _partyInspectPanelRect = null;
    /** Equipment icon cells in the inspect panel, paired for tooltip hover. */
    private final List<GameItem> _lastPartyEquipItems = new ArrayList<>();
    private final List<float[]> _lastPartyEquipRects = new ArrayList<>();
    /** Row rect of the currently-hovered party member (inspect anchor). */
    private float[] _hoveredPartyRowRect = null;
    /** 0 = MAIN (slots 5..24), 1 = BACKPACK (slots 25..44); one 20-slot page shown at a time. */
    private int activeBag = 0;

    private boolean prevInvTabMouseDown = false;
    private boolean prevTabMouseDown = false;

    private int spriteHudTabMainX = 0, spriteHudTabMainY = 0, spriteHudTabMainW = 0, spriteHudTabMainH = 0;
    private int spriteHudTabBackX = 0, spriteHudTabBackY = 0, spriteHudTabBackW = 0, spriteHudTabBackH = 0;
    private boolean spriteHudTabsEnabled = false;

    private final ForgeWindow forgeWindow = new ForgeWindow();
    private final FameStoreWindow fameStoreWindow = new FameStoreWindow();
    private final ExchangeMarketWindow exchangeMarketWindow = new ExchangeMarketWindow();
    private final OptionsWindow optionsWindow = new OptionsWindow();
    private final RealmTransitionState realmTransition = new RealmTransitionState();
    private final PotionStorageWindow potionStorageWindow = new PotionStorageWindow();
    private final SkillsWindow skillsWindow = new SkillsWindow();
    private final MetricsWindow metricsWindow = new MetricsWindow();

    /** Reused per-frame to avoid slot-render allocations: 5 equipment + 20 page cells. */
    private final Vector2f[] slotPositions = new Vector2f[Player.EQUIPMENT_SLOT_COUNT + INV_PAGE_SIZE];
    private final Vector2f[] groundLootPositions = new Vector2f[10];
    {
        for (int i = 0; i < slotPositions.length; i++) slotPositions[i] = new Vector2f();
        for (int i = 0; i < 10; i++) groundLootPositions[i] = new Vector2f();
    }

    private final Map<String, TextureRegion> _hudIdleCache = new HashMap<>();

    private int layoutMinimapY  = 60;
    private int layoutMinimapBot = 260;
    private int layoutBarsY     = 264;
    private int layoutStatsY    = 348;
    private int layoutEquipY    = 408;
    private int layoutBagTabY   = 478;
    private int layoutBag1Y     = 506;
    private int layoutPotionY   = 506 + (SLOT_SIZE + SLOT_GAP) * 4 + 10;
    private int layoutNearbyY   = 506 + (SLOT_SIZE + SLOT_GAP) * 4 + 10 + SLOT_SIZE + 16;

    // Sprite HUD panel positions cached each frame for hit-testing empty slots
    // (which have no Button to interrogate).
    private int spriteHudNearbyX = 0;
    private int spriteHudNearbyY = 0;
    private int spriteHudNearbyW = 0;
    private int spriteHudInvExtX = 0;
    private int spriteHudInvExtY = 0;
    private int spriteHudInvOnlyX = 0;
    private int spriteHudInvOnlyY = 0;
    private int spriteHudEquipStatsX = 0;
    private int spriteHudEquipStatsY = 0;
    /** Outer chrome right edge - the player tooltip anchors here. */
    private int spriteHudNearbyPanelRight = 0;
    private int spriteHudNearbyPanelTop = 0;
    private int spriteHudNearbyPanelBottom = 0;
    private boolean spriteHudNearbyEnabled = false;
    private int spriteHudPartyX = 0;
    private int spriteHudPartyY = 0;
    private int spriteHudPartyW = 0;
    private boolean spriteHudPartyEnabled = false;
    /** When true, renderNearbyPlayers skips its internal party section so the caller draws it in its own chrome. */
    private boolean suppressInternalParty = false;

    /** Cached pixel rects for the 4 hotbar slots, set during the sprite pass and consumed by renderAbilityHotbarOverlays. */
    private float[][] _lastHotbarCellPx;

    /** Local player's classId, or -1 if not in a game yet. */
    private int viewerClassId() {
        if (this.playState == null) return -1;
        final Player p = this.playState.getPlayer();
        return (p == null) ? -1 : p.getClassId();
    }

    public void showPartyInvitePrompt(String inviterName) {
        this.pendingPartyInviteFrom = inviterName;
        this.pendingPartyInviteExpiresAt = System.currentTimeMillis() + 60_000L;
        // Rebind click handlers to the current inviter on next render.
        this.partyInviteAcceptBtn = null;
        this.partyInviteDeclineBtn = null;
    }

    /** Defer the trade overlay close by 1s so the dual-CONFIRMED status stays visible after a successful trade. */
    public void scheduleTradeOverlayClose() {
        this.tradeOverlayCloseAtMs = System.currentTimeMillis() + 1000L;
    }

    /** Real inventory index for visible page cell {@code cell} (0..19). */
    private int pageSlotIndex(int cell) {
        return INV_PAGE_BASE + this.activeBag * INV_PAGE_SIZE + cell;
    }

    /** Move every backpack slot button NOT on the active page off-screen so
     *  only the visible page (plus the always-visible equipment row) hit-tests
     *  clicks and drags. Equipment slots 0..EQUIPMENT_SLOT_COUNT-1 are left in
     *  place; they are always on screen. */
    private void parkInactivePageButtons() {
        if (this.inventory == null) return;
        final int activeBase = INV_PAGE_BASE + this.activeBag * INV_PAGE_SIZE;
        final int activeEnd = activeBase + INV_PAGE_SIZE;
        for (int i = INV_PAGE_BASE; i < this.inventory.length; i++) {
            if (i >= activeBase && i < activeEnd) continue;
            final Slots slot = this.inventory[i];
            if (slot == null || slot.getButton() == null) continue;
            if (slot.getDragPos() != null) continue;
            slot.getButton().getPos().x = OFFSCREEN_PARK;
            slot.getButton().getPos().y = OFFSCREEN_PARK;
        }
    }

    /** First empty backpack slot on the given page, or -1 if the page is full. */
    private int firstEmptyOnPage(int page) {
        final int base = INV_PAGE_BASE + page * INV_PAGE_SIZE;
        for (int i = base; i < base + INV_PAGE_SIZE && i < this.inventory.length; i++) {
            final Slots s = this.inventory[i];
            if (s == null || s.getItem() == null || s.getItem().getItemId() == -1) {
                return i;
            }
        }
        return -1;
    }

    /** Screen rects [x,y,w,h] for the inv_only page in MAIN/BACKPACK tab order
     *  (0..19). When the atlas grid carries the full 20 cells (V3 sheet) those
     *  exact cells are used; otherwise a 5x4 grid is synthesized across the
     *  panel so the offline V1 atlas still renders all 20 page slots. */
    private float[][] invPageCellRects(UiComponent cInvOnly, float invOnlyX, float invOnlyY, int s) {
        final int[][] cells = UiAtlas.gridCells("panel.hud.inv_only.grid");
        final float[][] out = new float[INV_PAGE_SIZE][4];
        if (cells != null && cells.length >= INV_PAGE_SIZE) {
            for (int i = 0; i < INV_PAGE_SIZE; i++) {
                out[i][0] = invOnlyX + (cells[i][0] - cInvOnly.getX()) * s;
                out[i][1] = invOnlyY + (cells[i][1] - cInvOnly.getY()) * s;
                out[i][2] = cells[i][2] * s;
                out[i][3] = cells[i][3] * s;
            }
            return out;
        }
        // Synthesize a 5x4 grid inside the panel (V1 atlas only has 16 cells).
        // Reserve a header band at the top for the MAIN / BACKPACK tab strip.
        final int cols = 5, rows = 4;
        final float pad = 4f * s;
        final float gap = 1f * s;
        final float header = 16f * s;
        final float gridX = invOnlyX + pad;
        final float gridY = invOnlyY + header;
        final float cw = (cInvOnly.getW() * s - 2 * pad - (cols - 1) * gap) / cols;
        final float ch = (cInvOnly.getH() * s - header - pad - (rows - 1) * gap) / rows;
        int i = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                out[i][0] = gridX + c * (cw + gap);
                out[i][1] = gridY + r * (ch + gap);
                out[i][2] = cw;
                out[i][3] = ch;
                i++;
            }
        }
        return out;
    }

    /** Draw the two MAIN / BACKPACK page tabs above the inv grid and cache their screen rects. */
    private void renderInvPageTabs(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font,
            UiComponent cInvOnly, float invOnlyX, float invOnlyY, float[][] invRects, int s) {
        // Tab strip spans the grid width, sitting in the gap between the panel
        // top and the first grid row.
        final float gridTop = (invRects.length > 0) ? invRects[0][1] : invOnlyY;
        final float gridLeft = (invRects.length > 0) ? invRects[0][0] : invOnlyX;
        final float lastRight = (invRects.length > 0)
                ? invRects[invRects.length - 1][0] + invRects[invRects.length - 1][2]
                : invOnlyX + cInvOnly.getW() * s;
        final float stripW = lastRight - gridLeft;
        float stripH = gridTop - invOnlyY - 2;
        if (stripH < 14) stripH = 14;
        final float stripY = Math.max(invOnlyY + 2, gridTop - stripH - 2);
        final float tabW = stripW / 2f;

        this.spriteHudTabMainX = (int) gridLeft;
        this.spriteHudTabMainY = (int) stripY;
        this.spriteHudTabMainW = (int) tabW;
        this.spriteHudTabMainH = (int) stripH;
        this.spriteHudTabBackX = (int) (gridLeft + tabW);
        this.spriteHudTabBackY = (int) stripY;
        this.spriteHudTabBackW = (int) tabW;
        this.spriteHudTabBackH = (int) stripH;
        this.spriteHudTabsEnabled = true;

        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(this.activeBag == 0 ? 0.20f : 0.08f,
                        this.activeBag == 0 ? 0.15f : 0.06f,
                        0.10f, 0.92f);
        shapes.rect(gridLeft, stripY, tabW, stripH);
        shapes.setColor(this.activeBag == 1 ? 0.20f : 0.08f,
                        this.activeBag == 1 ? 0.15f : 0.06f,
                        0.10f, 0.92f);
        shapes.rect(gridLeft + tabW, stripY, tabW, stripH);
        shapes.setColor(0.78f, 0.66f, 0.43f, 1f);
        shapes.rect(this.activeBag == 0 ? gridLeft : gridLeft + tabW,
                stripY + stripH - 2, tabW, 2);
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
        batch.begin();

        final Color tan = new Color(0.78f, 0.66f, 0.43f, 1f);
        final Color muted = new Color(0.53f, 0.47f, 0.41f, 1f);
        final float origScale = font.getData().scaleX;
        font.getData().setScale(0.7f);
        this.drawCenteredTabLabel(batch, font, "MAIN", gridLeft, stripY, tabW, stripH,
                this.activeBag == 0 ? tan : muted);
        this.drawCenteredTabLabel(batch, font, "BACKPACK", gridLeft + tabW, stripY, tabW, stripH,
                this.activeBag == 1 ? tan : muted);
        font.getData().setScale(origScale);
        font.setColor(Color.WHITE);
    }

    private void drawCenteredTabLabel(SpriteBatch batch, BitmapFont font, String text,
            float x, float y, float w, float h, Color color) {
        final GlyphLayout gl = new GlyphLayout(font, text);
        font.setColor(color);
        font.draw(batch, text, x + (w - gl.width) / 2f, y + (h + gl.height) / 2f);
    }

    /** Edge-triggered sprite-HUD MAIN / BACKPACK tab click; switches the active page. */
    private void handleInvPageTabClick(MouseHandler mouse) {
        if (!this.spriteHudTabsEnabled) return;
        boolean down = mouse.isPressed(1);
        boolean justClicked = down && !this.prevInvTabMouseDown;
        this.prevInvTabMouseDown = down;
        if (!justClicked) return;
        final int page = this.tabHitPage(mouse.getX(), mouse.getY());
        if (page >= 0) this.activeBag = page;
    }

    /** Page index (0 = MAIN, 1 = BACKPACK) whose tab rect contains the point,
     *  or -1 if the point is over neither tab. */
    private int tabHitPage(int mouseX, int mouseY) {
        if (!this.spriteHudTabsEnabled) return -1;
        if (mouseX >= this.spriteHudTabMainX && mouseX < this.spriteHudTabMainX + this.spriteHudTabMainW
                && mouseY >= this.spriteHudTabMainY && mouseY < this.spriteHudTabMainY + this.spriteHudTabMainH) {
            return 0;
        }
        if (mouseX >= this.spriteHudTabBackX && mouseX < this.spriteHudTabBackX + this.spriteHudTabBackW
                && mouseY >= this.spriteHudTabBackY && mouseY < this.spriteHudTabBackY + this.spriteHudTabBackH) {
            return 1;
        }
        return -1;
    }

    /** If a drag is released over a page tab, return that page's first-empty
     *  real slot index (or -1 if no tab hit / page full). Mirrors the
     *  webclient drag-onto-tab relocate behavior. */
    private int tabDropTargetSlot(int mouseX, int mouseY) {
        final int page = this.tabHitPage(mouseX, mouseY);
        if (page < 0) return -1;
        return this.firstEmptyOnPage(page);
    }

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
        this.groundLoot = new Slots[10];
        this.inventory = new Slots[Player.INVENTORY_SIZE];
        this.playerChat = new PlayerChat(p);
        this.minimap = new Minimap(p);
    }

    /** Recompute the right-sidebar Y anchors from the current window size. */
    private void recomputeLayout() {
        final int panelW = OpenRealmGame.width / 5;
        final int minimapSize = Math.max(96, Math.min(panelW - 2 * PANEL_INSET,
                OpenRealmGame.height / 4));
        this.layoutMinimapY = PANEL_INSET;
        this.layoutMinimapBot = this.layoutMinimapY + minimapSize;
        this.layoutBarsY    = this.layoutMinimapBot + 6;
        this.layoutStatsY   = this.layoutBarsY + 22 * 3 + 8;
        this.layoutEquipY   = this.layoutStatsY + 22 * 3 + 14;
        this.layoutBagTabY  = this.layoutEquipY + SLOT_SIZE + 18;
        this.layoutBag1Y    = this.layoutBagTabY + 24 + 4;
        final int pageBottomY = this.layoutBag1Y + 4 * (SLOT_SIZE + SLOT_GAP);
        this.layoutPotionY  = pageBottomY + 10;
        this.layoutNearbyY  = this.layoutPotionY + 56 + 14;
    }

    /** Screen X for equipment slot column, centered in an equal-width cell. */
    private int slotX(int col) {
        final int slots = Player.EQUIPMENT_SLOT_COUNT;
        final int panelW = OpenRealmGame.width / 5;
        final int startX = OpenRealmGame.width - panelW;
        final int rowW = slots * SLOT_SIZE + (slots - 1) * SLOT_GAP;
        final int rowStart = startX + (panelW - rowW) / 2;
        return rowStart + col * (SLOT_SIZE + SLOT_GAP);
    }

    private int legacyPageColX(int cell) {
        return this.slotX(cell % 5);
    }
    private int legacyPageRowY(int cell) {
        return this.layoutBag1Y + (cell / 5) * (SLOT_SIZE + SLOT_GAP);
    }

    private int groundLootRowY(int row) {
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
        // = new Slots[...]` blew away every Button's bounds mid-click. Swap the
        // item field on existing slots; only build/drop a Button on empty<->populated.
        if (this.inventory == null || this.inventory.length != Player.INVENTORY_SIZE) {
            this.inventory = new Slots[Player.INVENTORY_SIZE];
        }
        final int eq = Player.EQUIPMENT_SLOT_COUNT;
        final int total = Math.min(Player.INVENTORY_SIZE, loot != null ? loot.length : 0);
        for (int i = 0; i < this.inventory.length; i++) {
            final GameItem next = (i < total) ? loot[i] : null;
            final boolean nextEmpty = (next == null || next.getItemId() == -1);
            final Slots existing = this.inventory[i];
            if (nextEmpty) {
                this.inventory[i] = null;
                continue;
            }
            if (existing == null) {
                if (i < eq) {
                    this.buildEquipmentSlotButton(i, next);
                } else {
                    this.buildInventorySlotsButton(i - eq, next);
                }
            } else {
                existing.setItem(next);
            }
        }
    }

    public void setGroundLoot(GameItem[] loot) {
        if (this.isTrading && this.currentTradeSelection != null) {
            loot = this.getOtherPlayerSelectedItems();
        }
        // Only build/drop a Button on empty<->populated; swap the item field otherwise.
        // Rebuilding wipes the sprite-HUD position back to off-screen legacy coords.
        if (this.groundLoot == null || this.groundLoot.length != 10) {
            this.groundLoot = new Slots[10];
        }
        for (int i = 0; i < this.groundLoot.length; i++) {
            final GameItem item = (i < loot.length) ? loot[i] : null;
            final boolean isEmpty = (item == null || item.getItemId() == -1);
            final Slots existing = this.groundLoot[i];
            if (isEmpty) {
                this.groundLoot[i] = null;
            } else if (existing == null) {
                this.buildGroundLootSlotButton(i, item);
            } else {
                existing.setItem(item);
            }
        }
    }

    /** Local player's NetInventorySelection (the side keyed to our id). */
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

    /** Items the OTHER player has selected for trade. */
    private GameItem[] getOtherPlayerSelectedItems() {
        NetInventorySelection otherSelection = this.getOtherPlayerSelection();
        if (otherSelection == null || otherSelection.getItemRefs() == null) {
            return new GameItem[Player.TRADE_SLOT_COUNT];
        }

        GameItem[] allItems = otherSelection.getGameItems();
        Boolean[] selection = otherSelection.getSelection();
        if (selection == null) return new GameItem[Player.TRADE_SLOT_COUNT];

        GameItem[] selectedItems = new GameItem[Player.TRADE_SLOT_COUNT];
        int idx = 0;
        for (int i = 0; i < selection.length && i < allItems.length; i++) {
            if (selection[i] != null && selection[i] && allItems[i] != null) {
                if (idx < selectedItems.length) {
                    selectedItems[idx++] = allItems[i];
                }
            }
        }
        return selectedItems;
    }

    public void clearTradeSelections() {
        Slots[] invSlots = this.getSlots(Player.EQUIPMENT_SLOT_COUNT, Player.EQUIPMENT_SLOT_COUNT + Player.TRADE_SLOT_COUNT);
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
            // Seed the Button at its sprite-HUD grid cell so the first click after
            // a fresh loot bag spawn (input runs before render) lands. Legacy
            // coords are the fallback until the atlas loads.
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
            log.info("{} loot-build slot={} itemId={} pos=({}, {}) size={}",
                    LOG_NS, actualIdx, item.getItemId(), x, y, SLOT_SIZE);

            b.onMouseUp(event -> {
                log.info("{} loot-click FIRED slot={} itemId={} trading={} dragging={} canSwap={}",
                        LOG_NS, actualIdx, item != null ? item.getItemId() : -1,
                        this.isTrading, this.isDragging, this.canSwap());
                if (this.isTrading) return;
                if (this.isDragging) return;
                this.activeTooltip = null;
                // No canSwap() gate: it drops (not queues) rapid picks. Each click
                // sends its own moveItem; the server no-ops an already-taken slot.
                // target = first backpack slot; the server ground-loot branch routes
                // via firstEmptyInvSlot (covers both pages, potions, stack merge).
                final int wireFromIdx = actualIdx + MoveItemPacket.groundLootBase();
                final byte wireTargetSlot = (byte) Player.EQUIPMENT_SLOT_COUNT;
                try {
                    this.playState.getRealmManager().moveItem(wireTargetSlot, wireFromIdx, false, false);
                    log.info("{} loot-click moveItem sent (target={} from={})", LOG_NS, wireTargetSlot, wireFromIdx);
                } catch (Exception e) {
                    log.warn("{} loot-click moveItem failed for slot {} item {}: {}",
                            LOG_NS, actualIdx, item != null ? item.getItemId() : -1, e.getMessage());
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

            // Right-click drops the equipped item to ground.
            final int dropEquipIdx = actualIdx;
            b.onRightClick(event -> {
                if (this.isTrading) return;
                if (!this.canSwap()) return;
                this.setActionTime();
                log.info("{} equip-rclick-drop slot={} itemId={}",
                        LOG_NS, dropEquipIdx, item != null ? item.getItemId() : -1);
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
        final int inventoryOffset = Player.EQUIPMENT_SLOT_COUNT;

        if (item != null) {
            final int actualIdx = index + inventoryOffset;
            final int cell = index % INV_PAGE_SIZE;
            final int x = this.legacyPageColX(cell);
            final int y = this.legacyPageRowY(cell);
            Button b = new Button(new Vector2f(x, y), SLOT_SIZE);

            b.onRightClick(event -> {
                // Right-click drops to ground, or toggles trade selection during a trade.
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
                            log.warn("{} trade-toggle update failed: {}", LOG_NS, e.getMessage());
                        }
                    }
                } else {
                    if (!this.canSwap()) return;
                    this.setActionTime();
                    // Shift+right-click a stack of >1: split it (checked before quick-store).
                    final boolean shiftHeld = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)
                            || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);
                    if (shiftHeld && item != null && item.isStackable() && item.getStackCount() > 1) {
                        try {
                            final SplitStackPacket pkt = new SplitStackPacket(actualIdx);
                            this.playState.getRealmManager().getClient().getOutboundPacketQueue().add(pkt);
                            log.info("{} inv-rclick-split slot={} itemId={} stack={}",
                                    LOG_NS, actualIdx, item.getItemId(), item.getStackCount());
                        } catch (Exception e) {
                            log.warn("{} inv-rclick-split failed: {}", LOG_NS, e.getMessage());
                        }
                        return;
                    }
                    // Quick-store to the potion-storage modal when open + item eligible.
                    if (this.potionStorageWindow != null
                            && this.potionStorageWindow.isVisible()
                            && item != null
                            && (item.isStackable() || "gem".equals(item.getCategory()))) {
                        try {
                            // toIdx=-1 = auto-place sentinel (server picks the destination).
                            final ItemStoreMovePacket pkt = new ItemStoreMovePacket(
                                    this.potionStorageWindow.getStoreKind(),
                                    ItemStoreMovePacket.SIDE_INV, actualIdx,
                                    ItemStoreMovePacket.SIDE_STORAGE, -1);
                            this.playState.getRealmManager().getClient().getOutboundPacketQueue().add(pkt);
                            log.info("{} inv-rclick-quickstore slot={} itemId={}",
                                    LOG_NS, actualIdx, item.getItemId());
                        } catch (Exception e) {
                            log.warn("{} inv-rclick-quickstore failed: {}", LOG_NS, e.getMessage());
                        }
                        return;
                    }
                    log.info("{} inv-rclick-drop slot={} itemId={}",
                            LOG_NS, actualIdx, item != null ? item.getItemId() : -1);
                    this.playState.getRealmManager().moveItem(-1, actualIdx, true, false);
                }
            });

            b.onMouseDown(btn -> {
                // Shift-click or double-click smart-equips/uses the item. A plain
                // left-click only arms a drag (move = drag, drop = right-click).
                if (this.isTrading || item == null) return;
                final boolean shiftHeld = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)
                        || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);
                final long now = System.currentTimeMillis();
                final boolean doubleClick = this.lastSlotClickIdx == actualIdx
                        && (now - this.lastSlotClickTime) < DOUBLE_CLICK_MS;
                this.lastSlotClickTime = now;
                this.lastSlotClickIdx = actualIdx;
                if (shiftHeld || doubleClick) {
                    if (!this.canSwap()) return;
                    this.setActionTime();
                    this.playState.handleQuickUseKey(actualIdx);
                }
            });

            this.inventory[actualIdx] = new Slots(b, item);
        }
    }

    public void update(double time) {
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

        if (this.isTrading) {
            if (this.confirmTradeButton != null) {
                this.confirmTradeButton.update(time);
            }
            if (this.cancelTradeButton != null) {
                this.cancelTradeButton.update(time);
            }
        }

        for (Button btn : this.nearbyPlayerButtons) {
            btn.update(time);
        }
    }

    public void input(MouseHandler mouse, KeyHandler key) {
        // Tab clicks must run BEFORE drag-drop / slot input so a slot underneath
        // doesn't swallow them.
        if (UiAtlas.isReady()) {
            this.handleInvPageTabClick(mouse);
        } else {
            this.handleBagTabClick(mouse);
        }

        // Minimap runs before drag-drop so a minimap click isn't consumed by a slot.
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

        final boolean lootDebugLogThisFrame = mouse.isPressed(1) && !this.prevLootDebugMouseDown;
        this.prevLootDebugMouseDown = mouse.isPressed(1);
        for (int i = 0; i < this.groundLoot.length; i++) {
            Slots curr = this.groundLoot[i];
            if (curr != null) {
                if (lootDebugLogThisFrame && curr.getButton() != null) {
                    final var bnd = curr.getButton().getBounds();
                    log.info("{} loot-input slot={} mouse=({}, {}) bounds=({}, {}, {}x{}) inside={}",
                            LOG_NS, i, mouse.getX(), mouse.getY(),
                            (int) bnd.getPos().x, (int) bnd.getPos().y,
                            (int) bnd.getWidth(), (int) bnd.getHeight(),
                            bnd.inside(mouse.getX(), mouse.getY()));
                }
                curr.input(mouse, key);
            }
        }

        this.updateTooltip(mouse);

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

        if (this.pendingTradeRequestFrom != null) {
            if (this.tradeRequestAcceptBtn != null) this.tradeRequestAcceptBtn.input(mouse, key);
            if (this.tradeRequestDeclineBtn != null) this.tradeRequestDeclineBtn.input(mouse, key);
        }
        if (this.pendingPartyInviteFrom != null) {
            if (this.partyInviteAcceptBtn != null)  this.partyInviteAcceptBtn.input(mouse, key);
            if (this.partyInviteDeclineBtn != null) this.partyInviteDeclineBtn.input(mouse, key);
        }

        for (Button btn : this.nearbyPlayerButtons) {
            btn.input(mouse, key);
        }

        // Runs LAST so a menu-row click isn't consumed by the nearby-player button under it.
        this.handleContextMenuInput(mouse);
        this.handlePartyKickClick(mouse);

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

        final Player viewer = (this.playState != null) ? this.playState.getPlayer() : null;
        final Stats viewerStats = (viewer != null) ? viewer.getComputedStats() : null;

        this.activeAbilityTooltip = null;
        this.activePassiveTooltip = null;

        for (int i = 0; i < this.inventory.length; i++) {
            Slots s = this.inventory[i];
            if (s != null && s.getButton() != null && s.getItem() != null) {
                if (s.getButton().getBounds().inside(mx, my)) {
                    this.activeTooltip = new ItemTooltip(s.getItem(),
                            new Vector2f(tooltipX, 100), panelWidth, 0,
                            this.viewerClassId());
                    this.activeTooltip.setViewerStats(viewerStats);
                    return;
                }
            }
        }

        for (int i = 0; i < this.groundLoot.length; i++) {
            Slots s = this.groundLoot[i];
            if (s != null && s.getButton() != null && s.getItem() != null) {
                if (s.getButton().getBounds().inside(mx, my)) {
                    this.activeTooltip = new ItemTooltip(s.getItem(),
                            new Vector2f(tooltipX, 100), panelWidth, 0,
                            this.viewerClassId());
                    this.activeTooltip.setViewerStats(viewerStats);
                    return;
                }
            }
        }

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
                        this.activeTooltip.setViewerStats(viewerStats);
                        return;
                    }
                }
            }
        }

        // Own hotbar: cell 0 = passive (PassiveTooltip), cells 1..4 = actives (bindingIdx = cell - 1).
        final Player local = (this.playState != null) ? this.playState.getPlayer() : null;
        if (local != null && this._lastHotbarCellPx != null && this._lastHotbarCellPx.length > 0) {
            final float[] cell0 = this._lastHotbarCellPx[0];
            if (cell0 != null
                    && mx >= cell0[0] && mx < cell0[0] + cell0[2]
                    && my >= cell0[1] && my < cell0[1] + cell0[3]) {
                final PassiveAbility pa = local.getClassPassive();
                if (pa != null) {
                    this.activePassiveTooltip = new PassiveTooltip(
                            pa, local.getStats(),
                            new Vector2f(cell0[0], cell0[1]), panelWidth).anchorAbove();
                    this.activeTooltip = null;
                    return;
                }
            }
        }
        if (local != null && this._lastHotbarCellPx != null) {
            for (int slot = 1; slot < this._lastHotbarCellPx.length; slot++) {
                final float[] cell = this._lastHotbarCellPx[slot];
                if (cell == null) continue;
                if (mx >= cell[0] && mx < cell[0] + cell[2]
                        && my >= cell[1] && my < cell[1] + cell[3]) {
                    final int bindingIdx = slot - 1;
                    final Ability ab = local.getActiveAbility(bindingIdx);
                    if (ab == null) continue;
                    final int invested = AbilityTooltip.investedFor(local, ab);
                    final Stats stats = local.getStats();
                    this.activeAbilityTooltip = new AbilityTooltip(
                            ab, slot, invested, stats,
                            new Vector2f(cell[0], cell[1]), panelWidth).anchorAbove();
                    this.activeTooltip = null;
                    return;
                }
            }
        }

        // Party member cd-strip cells (cached by renderPartyMembers) carry the
        // member's computed stats so tooltip damage uses THEIR numbers.
        if (!this._lastPartyAbilityCells.isEmpty()) {
            for (float[] cell : this._lastPartyAbilityCells) {
                if (mx >= cell[0] && mx < cell[0] + cell[2]
                        && my >= cell[1] && my < cell[1] + cell[3]) {
                    final int aid = (int) cell[4];
                    final int invested = (int) cell[5];
                    final Ability ab = (GameDataManager.ABILITIES != null)
                            ? GameDataManager.ABILITIES.get(aid) : null;
                    if (ab == null) continue;
                    // hp is int, the other 7 are short - match or @Builder rejects the call.
                    final Stats memberStats = (cell.length >= 14)
                            ? Stats.builder()
                                    .vit((short) cell[6])
                                    .wis((short) cell[7])
                                    .hp ((int)   cell[8])
                                    .mp ((short) cell[9])
                                    .str((short) cell[10])
                                    .def((short) cell[11])
                                    .spd((short) cell[12])
                                    .dex((short) cell[13])
                                    .build()
                            : null;
                    this.activeAbilityTooltip = new AbilityTooltip(
                            ab, 0, invested, memberStats,
                            new Vector2f(cell[0], cell[1]), panelWidth).anchorAbove();
                    this.activeTooltip = null;
                    return;
                }
            }
        }

        // Party passive cell -> class-passive tooltip.
        for (float[] pcell : this._lastPartyPassiveCells) {
            if (mx >= pcell[0] && mx < pcell[0] + pcell[2]
                    && my >= pcell[1] && my < pcell[1] + pcell[3]) {
                final PassiveAbility pa = this.classPassive((int) pcell[4]);
                if (pa != null) {
                    this.activePassiveTooltip = new PassiveTooltip(pa, viewerStats,
                            new Vector2f(pcell[0], pcell[1]), panelWidth).anchorAbove();
                    this.activeTooltip = null;
                    return;
                }
            }
        }

        // Party member equipment icon -> full item tooltip (inventory parity).
        for (int i = 0; i < this._lastPartyEquipRects.size(); i++) {
            final float[] r = this._lastPartyEquipRects.get(i);
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                final GameItem it = this._lastPartyEquipItems.get(i);
                if (it != null) {
                    final int vcId = (this.hoveredPartyMember != null)
                            ? this.hoveredPartyMember.getClassId() : this.viewerClassId();
                    final Stats ms = (this.hoveredPartyMember != null
                            && this.hoveredPartyMember.getStats() != null)
                            ? this.hoveredPartyMember.getStats().asStats() : viewerStats;
                    this.activeTooltip = new ItemTooltip(it,
                            new Vector2f(r[0], r[1]), panelWidth, 0, vcId);
                    this.activeTooltip.setViewerStats(ms);
                    this.activeAbilityTooltip = null;
                    this.activePassiveTooltip = null;
                    return;
                }
            }
        }

        // Which member is being inspected: row hover opens it; the hover
        // persists while the cursor is inside the open inspect panel.
        NetPartyMember hoveredMember = null;
        for (int i = 0; i < this._lastPartyMemberRows.size(); i++) {
            final float[] r = this._lastPartyMemberRows.get(i);
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                hoveredMember = this._lastPartyMemberRefs.get(i);
                this._hoveredPartyRowRect = r;
                break;
            }
        }
        if (hoveredMember == null && this.hoveredPartyMember != null
                && this._partyInspectPanelRect != null) {
            final float[] r = this._partyInspectPanelRect;
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                hoveredMember = this.hoveredPartyMember;
            }
        }
        this.hoveredPartyMember = hoveredMember;

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

    /** True iff the cursor sits over any hotbar cell; used to suppress the basic-attack shot. */
    public boolean isHoveringHotbarCell(int mx, int my) {
        if (this._lastHotbarCellPx == null) return false;
        for (float[] cell : this._lastHotbarCellPx) {
            if (cell == null) continue;
            if (mx >= cell[0] && mx < cell[0] + cell[2]
                    && my >= cell[1] && my < cell[1] + cell[3]) {
                return true;
            }
        }
        return false;
    }

    /** hotbarBindings index (0..3) for an active-ability cell under the cursor, else -1 (passive/none). */
    public int getHotbarBindingAtScreen(int mx, int my) {
        if (this._lastHotbarCellPx == null) return -1;
        // Start at cell 1 - cell 0 is the passive and is not a fire target.
        for (int slot = 1; slot < this._lastHotbarCellPx.length; slot++) {
            final float[] cell = this._lastHotbarCellPx[slot];
            if (cell == null) continue;
            if (mx >= cell[0] && mx < cell[0] + cell[2]
                    && my >= cell[1] && my < cell[1] + cell[3]) {
                return slot - 1;
            }
        }
        return -1;
    }

    private void sendTradeCommand(String command) {
        try {
            ServerCommandMessage serverCommand = ServerCommandMessage.parseFromInput("/" + command);
            CommandPacket packet = CommandPacket.create(this.playState.getPlayer(), CommandType.SERVER_COMMAND,
                    serverCommand);
            this.playState.getRealmManager().getClient().sendRemote(packet);
        } catch (Exception e) {
            log.error("{} Failed to send trade command. Reason: {}", LOG_NS, e);
        }
    }

    /** Lazy-construct the Confirm/Cancel buttons, then re-anchor them to the atlas rects each frame. */
    private void ensureTradeButtons(float[] confirmRect, float[] cancelRect) {
        if (this.confirmTradeButton == null) {
            this.confirmTradeButton = new Button("Confirm",
                    new Vector2f(confirmRect[0], confirmRect[1]), (int) confirmRect[2], (int) confirmRect[3]);
            this.confirmTradeButton.onMouseUp(event -> {
                this.sendTradeCommand("confirm true");
                this.myTradeConfirmed = true;
            });
        }
        if (this.cancelTradeButton == null) {
            this.cancelTradeButton = new Button("Cancel",
                    new Vector2f(cancelRect[0], cancelRect[1]), (int) cancelRect[2], (int) cancelRect[3]);
            this.cancelTradeButton.onMouseUp(event -> {
                this.sendTradeCommand("decline");
                this.myTradeConfirmed = false;
            });
        }
        this.confirmTradeButton.getPos().x = confirmRect[0];
        this.confirmTradeButton.getPos().y = confirmRect[1];
        this.cancelTradeButton.getPos().x = cancelRect[0];
        this.cancelTradeButton.getPos().y = cancelRect[1];
    }

    /** Lazy-construct the my-side trade-cell click targets; repositioned each frame by renderTradeUI. */
    private void ensureTradeMyButtons() {
        if (this.tradeMyButtons != null && this.tradeMyButtons.length == Player.TRADE_SLOT_COUNT) return;
        this.tradeMyButtons = new Button[Player.TRADE_SLOT_COUNT];
        final int s = UiAtlas.getDisplayScale();
        final int[][] cells = UiAtlas.gridCells("panel.hud.trade.player0.inv");
        final int cellW = (cells != null && cells.length > 0) ? cells[0][2] * s : SLOT_SIZE;
        for (int i = 0; i < Player.TRADE_SLOT_COUNT; i++) {
            final int slotIdx = i + Player.EQUIPMENT_SLOT_COUNT;
            final Button b = new Button(new Vector2f(0, 0), cellW);
            // onMouseDown fires once per click; onMouseUp fires twice and would toggle back off.
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
                    log.warn("{} trade-overlay selection update failed: {}", LOG_NS, e.getMessage());
                }
            });
            this.tradeMyButtons[i] = b;
        }
    }

    /** Centered horizontal purification bar at the top of the screen; self-contained shapes/batch cycle. */
    private void renderPurificationBar(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        final Realm realm = (this.playState.getRealmManager() != null)
                ? this.playState.getRealmManager().getRealm() : null;
        if (realm == null || realm.getPurificationGoal() <= 0L) return;
        final int pct = (int) Math.max(0, Math.min(100,
                realm.getPurificationProgress() * 100L / realm.getPurificationGoal()));
        final int barW = Math.min(440, OpenRealmGame.width / 3);
        final int barH = 20;
        final int barX = (OpenRealmGame.width - barW) / 2;
        final int barY = 10;

        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.04f, 0.08f, 0.06f, 0.85f);
        shapes.rect(barX, barY, barW, barH);
        shapes.setColor(0.18f, 0.68f, 0.48f, 1f);
        shapes.rect(barX, barY, barW * (pct / 100f), barH);
        shapes.end();
        batch.begin();

        String label = (realm.getPurificationTier() > 1)
                ? "Tier " + realm.getPurificationTier() + " - Purification " + pct + "%"
                : "Realm Purification - " + pct + "%";
        final String mods = realm.getPurificationModifiers();
        if (mods != null && !mods.isEmpty()) label += "  -  " + mods;
        font.setColor(Color.WHITE);
        UiRender.drawCenteredIn(batch, font, label, barX, barY, barW, barH);
    }

    /** Difficulty tint matching the webclient minimap badge (green to red as difficulty rises). */
    private float[] difficultyColor(float diff) {
        final float r = diff <= 2 ? 0.24f : diff <= 4 ? 0.71f : diff <= 6 ? 0.86f : 1.0f;
        final float g = diff <= 2 ? 0.71f : diff <= 4 ? 0.63f : diff <= 6 ? 0.31f : 0.16f;
        final float b = diff <= 2 ? 0.24f : 0.16f;
        return new float[] { r, g, b };
    }

    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        this.recomputeLayout();
        int panelWidth = (OpenRealmGame.width / 5);
        int startX = OpenRealmGame.width - panelWidth;

        final int barX = startX + PANEL_INSET;
        final int barW = panelWidth - 2 * PANEL_INSET;
        final int barH = 22;
        if (this.hp != null) { this.hp.getPos().x = barX;  this.hp.getPos().y = this.layoutBarsY;          this.hp.setBarWidth(barW); this.hp.setBarHeight(barH); }
        if (this.mp != null) { this.mp.getPos().x = barX;  this.mp.getPos().y = this.layoutBarsY + barH;   this.mp.setBarWidth(barW); this.mp.setBarHeight(barH); }
        if (this.xp != null) { this.xp.getPos().x = barX;  this.xp.getPos().y = this.layoutBarsY + barH*2; this.xp.setBarWidth(barW); this.xp.setBarHeight(barH); }

        Slots[] equips = this.getSlots(0, Player.EQUIPMENT_SLOT_COUNT);
        final int bagBase = INV_PAGE_BASE + this.activeBag * INV_PAGE_SIZE;
        final Slots[] page = this.getSlots(bagBase,
                Math.min(bagBase + INV_PAGE_SIZE, this.inventory.length));

        // Atlas loaded: draw the sprite HUD (also repositions slot Buttons) and skip the legacy sidebar.
        final boolean useSpriteHud = UiAtlas.isReady();
        if (useSpriteHud) {
            this.renderSpriteHud(batch, shapes, font);
        }

        this.renderPurificationBar(batch, shapes, font);

        final Color cPanel  = new Color(0.10f, 0.07f, 0.09f, 0.95f);
        final Color cBorder = new Color(0.23f, 0.16f, 0.22f, 1f);
        final Color cAccent = new Color(0.78f, 0.66f, 0.43f, 1f);
        final Color cMuted  = new Color(0.53f, 0.47f, 0.41f, 1f);

        if (!useSpriteHud) {
        // One ShapeRenderer batch for all sidebar backgrounds; do not insert
        // batch flushes into this rect loop.
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA,
                GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        shapes.setColor(0.07f, 0.05f, 0.06f, 0.96f);
        shapes.rect(startX, 0, panelWidth, OpenRealmGame.height);

        shapes.setColor(0.20f, 0.15f, 0.10f, 0.95f);
        shapes.rect(startX + PANEL_INSET, FAME_Y, panelWidth - 2 * PANEL_INSET, FAME_H);

        shapes.setColor(cPanel);
        final int statsH = 22 * 3 + 8;
        shapes.rect(startX + PANEL_INSET, this.layoutStatsY - 4,
                panelWidth - 2 * PANEL_INSET, statsH);

        final Color cSlotBg     = new Color(0.18f, 0.16f, 0.18f, 1f);
        final Color cSlotBorder = new Color(0.30f, 0.24f, 0.28f, 1f);
        final Color cSlotSelected = new Color(0.55f, 0.45f, 0.18f, 1f);
        for (int i = 0; i < Player.EQUIPMENT_SLOT_COUNT; i++) {
            final Slots curr = equips[i];
            float sx, sy;
            if (curr != null && curr.getDragPos() != null) {
                sx = curr.getDragPos().x;
                sy = curr.getDragPos().y;
            } else {
                sx = this.slotX(i);
                sy = this.layoutEquipY;
            }
            final Vector2f pos = slotPositions[i];
            pos.x = sx; pos.y = sy;
            shapes.setColor(curr != null && curr.isSelected() ? cSlotSelected : cSlotBg);
            shapes.rect(sx, sy, SLOT_SIZE, SLOT_SIZE);
        }

        final int tabY = this.layoutBagTabY;
        final int tabH = 24;
        final int tabW = (panelWidth - 2 * PANEL_INSET) / 2;
        final int tab1X = startX + PANEL_INSET;
        final int tab2X = startX + PANEL_INSET + tabW;
        shapes.setColor(this.activeBag == 0 ? 0.20f : 0.10f,
                        this.activeBag == 0 ? 0.15f : 0.08f,
                        this.activeBag == 0 ? 0.10f : 0.10f, 0.95f);
        shapes.rect(tab1X, tabY, tabW, tabH);
        shapes.setColor(this.activeBag == 1 ? 0.20f : 0.10f,
                        this.activeBag == 1 ? 0.15f : 0.08f,
                        this.activeBag == 1 ? 0.10f : 0.10f, 0.95f);
        shapes.rect(tab2X, tabY, tabW, tabH);
        shapes.setColor(cAccent);
        shapes.rect(this.activeBag == 0 ? tab1X : tab2X, tabY + tabH - 2, tabW, 2);

        for (int i = 0; i < page.length; i++) {
            final Slots curr = page[i];
            float sx, sy;
            if (curr != null && curr.getDragPos() != null) {
                sx = curr.getDragPos().x;
                sy = curr.getDragPos().y;
            } else {
                sx = this.legacyPageColX(i);
                sy = this.legacyPageRowY(i);
            }
            final Vector2f pos = slotPositions[Player.EQUIPMENT_SLOT_COUNT + i];
            pos.x = sx; pos.y = sy;
            shapes.setColor(curr != null && curr.isSelected() ? cSlotSelected : cSlotBg);
            shapes.rect(sx, sy, SLOT_SIZE, SLOT_SIZE);
        }

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

        this.hp.renderShapes(shapes);
        this.mp.renderShapes(shapes);
        this.xp.renderShapes(shapes);

        final int potionY = this.layoutPotionY;
        final int potionSize = SLOT_SIZE;
        final int potionGapX = 12;
        final int potionTotalW = potionSize * 2 + potionGapX;
        final int potionStartX = startX + (panelWidth - potionTotalW) / 2;
        shapes.setColor(0.55f, 0.10f, 0.10f, 0.95f);
        shapes.rect(potionStartX, potionY, potionSize, potionSize);
        shapes.setColor(0.10f, 0.18f, 0.55f, 0.95f);
        shapes.rect(potionStartX + potionSize + potionGapX, potionY, potionSize, potionSize);

        shapes.end();

        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(cBorder);
        shapes.rect(startX + PANEL_INSET, FAME_Y, panelWidth - 2 * PANEL_INSET, FAME_H);
        shapes.rect(startX + PANEL_INSET, this.layoutStatsY - 4,
                panelWidth - 2 * PANEL_INSET, statsH);
        shapes.line(startX + PANEL_INSET, this.layoutEquipY + SLOT_SIZE + 4,
                    startX + panelWidth - PANEL_INSET, this.layoutEquipY + SLOT_SIZE + 4);
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);

        batch.begin();

        this.drawCenteredTabLabel(batch, font, "MAIN", tab1X, tabY, tabW, tabH,
                this.activeBag == 0 ? cAccent : cMuted);
        this.drawCenteredTabLabel(batch, font, "BACKPACK", tab2X, tabY, tabW, tabH,
                this.activeBag == 1 ? cAccent : cMuted);
        font.setColor(Color.WHITE);

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

        for (int i = 0; i < equips.length; i++) {
            if (equips[i] != null) equips[i].renderItem(batch, slotPositions[i]);
        }

        for (int i = 0; i < page.length; i++) {
            if (page[i] != null) page[i].renderItem(batch, slotPositions[Player.EQUIPMENT_SLOT_COUNT + i]);
        }

        if (!this.isTrading) {
            for (int i = 0; i < this.groundLoot.length; i++) {
                if (this.groundLoot[i] != null) this.groundLoot[i].renderItem(batch, groundLootPositions[i]);
            }
        }

        // Stack counts drawn AFTER all sprites so "xN" sits on top of the icon.
        for (int i = 0; i < page.length; i++) {
            if (page[i] != null) page[i].renderStackCount(batch, font, slotPositions[Player.EQUIPMENT_SLOT_COUNT + i]);
        }
        if (!this.isTrading) {
            for (int i = 0; i < this.groundLoot.length; i++) {
                if (this.groundLoot[i] != null) this.groundLoot[i].renderStackCount(batch, font, groundLootPositions[i]);
            }
        }

        this.hp.renderText(batch, font);
        this.mp.renderText(batch, font);
        this.xp.renderText(batch, font);
        }

        if (this.isTrading) {
            this.renderTradeUI(batch, shapes, font, startX, panelWidth);
        }

        // Sprite HUD reroutes nearby + party into dedicated bottom-left panels;
        // the legacy path keeps the single right-sidebar layout.
        if (useSpriteHud && this.spriteHudNearbyEnabled) {
            if (this.spriteHudPartyEnabled) {
                final int prevNearbyY = this.layoutNearbyY;
                this.layoutNearbyY = this.spriteHudPartyY;
                this.renderPartyMembers(batch, shapes, font,
                        this.spriteHudPartyX, this.spriteHudPartyW);
                this.layoutNearbyY = prevNearbyY;
            }
            final int prevNearbyY = this.layoutNearbyY;
            this.layoutNearbyY = this.spriteHudNearbyY;
            this.suppressInternalParty = true;
            this.renderNearbyPlayers(batch, shapes, font,
                    this.spriteHudNearbyX, this.spriteHudNearbyW);
            this.suppressInternalParty = false;
            this.layoutNearbyY = prevNearbyY;
        } else {
            this.renderNearbyPlayers(batch, shapes, font, startX, panelWidth);
        }

        // Inspect panel draws under the tooltips so a hovered equipment tooltip sits on top.
        this.renderPartyMemberInspect(batch, shapes, font);
        if (this.activeTooltip != null) {
            this.activeTooltip.render(batch, shapes, font);
        }
        if (this.activeAbilityTooltip != null) {
            this.activeAbilityTooltip.render(batch, shapes, font);
        }
        if (this.activePassiveTooltip != null) {
            this.activePassiveTooltip.render(batch, shapes, font);
        }

        this.renderPlayerTooltip(batch, shapes, font);
        this.renderPlayerContextMenu(batch, shapes, font);
        this.renderTradeRequestPopup(batch, shapes, font);
        this.renderPartyInvitePrompt(batch, shapes, font);
        if (!useSpriteHud) this.renderStats(batch, font);
        this.renderPortalPrompt(batch, shapes, font);
        this.renderInteractPrompt(batch, shapes, font);
        this.playerChat.render(batch, shapes, font);

        if (this.minimap.isInitialized()) {
            if (!useSpriteHud) {
                final int hudPanelW = OpenRealmGame.width / 5;
                final int hudPanelX = OpenRealmGame.width - hudPanelW;
                final int size = Math.max(96, Math.min(hudPanelW - 2 * PANEL_INSET,
                        OpenRealmGame.height / 4));
                this.minimap.setLayout(hudPanelX + PANEL_INSET, this.layoutMinimapY, size);
            }
            // Guard the minimap: a transient failure (e.g. Pixmap rebuild mid
            // realm-transition) must not propagate into the render loop and kill the game.
            try {
                this.minimap.update();
                this.minimap.render(batch, shapes);
            } catch (Throwable t) {
                log.warn("{} Minimap render failed (recovering): {}", LOG_NS, t.toString());
            }
        }

        // Overlays render last so they sit on top of the HUD; each is a no-op when hidden.
        this.realmTransition.update();
        this.realmTransition.render(batch, shapes, font);
        this.forgeWindow.update();
        this.forgeWindow.render(batch, shapes, font);
        this.fameStoreWindow.update();
        this.fameStoreWindow.render(batch, shapes, font);
        this.exchangeMarketWindow.update();
        this.exchangeMarketWindow.render(batch, shapes, font);
        this.optionsWindow.update();
        this.optionsWindow.render(batch, shapes, font);
        this.potionStorageWindow.update();
        this.potionStorageWindow.render(batch, shapes, font);
        this.skillsWindow.update();
        this.skillsWindow.render(batch, shapes, font,
                this.playState != null ? this.playState.getSkillXp() : null);
        this.metricsWindow.update();
        this.metricsWindow.render(batch, shapes, font);

        PerfMetrics.get().onFrame();
    }

    /**
     * Centered trade overlay: blit the panel.hud.trade chrome, then overlay live
     * text, item icons, selection borders and click targets. Left grid = MY main
     * page (inventory[5..24], clickable); right grid = partner's page (read-only).
     */
    private void renderTradeUI(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font,
                                int startX, int panelWidth) {
        if (!UiAtlas.isReady()) return;
        final int s = UiAtlas.getDisplayScale();
        final UiComponent cTrade = UiAtlas.componentOf("panel.hud.trade");
        if (cTrade == null) return;

        final int panelW = cTrade.getW() * s;
        final int panelH = cTrade.getH() * s;
        final int ox = (OpenRealmGame.width  - panelW) / 2;
        final int oy = (OpenRealmGame.height - panelH) / 2;

        final float[] confirmRect = this.tradeSubRect("panel.hud.trade.confirm", cTrade, ox, oy, s);
        final float[] cancelRect  = this.tradeSubRect("panel.hud.trade.cancel",  cTrade, ox, oy, s);
        this.ensureTradeButtons(confirmRect, cancelRect);
        this.ensureTradeMyButtons();

        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0, 0, 0, 0.55f);
        shapes.rect(0, 0, OpenRealmGame.width, OpenRealmGame.height);
        shapes.end();
        batch.begin();

        final TextureRegion rTrade = UiAtlas.region("panel.hud.trade");
        if (rTrade != null) {
            batch.draw(rTrade, ox, oy, panelW, panelH);
        }

        final Player me = (this.playState != null) ? this.playState.getPlayer() : null;
        final String myName    = (me != null && me.getName() != null) ? me.getName() : "YOU";
        final String partner   = (this.tradePartnerName != null) ? this.tradePartnerName : "...";
        // myTradeConfirmed mirrors the local flag in case the server broadcast lags a frame.
        final NetInventorySelection mySel    = this.getMyTradeSelection();
        final NetInventorySelection theirSel = this.getOtherPlayerSelection();
        final boolean iConfirmed    = this.myTradeConfirmed || (mySel != null && mySel.isConfirmed());
        final boolean theyConfirmed = theirSel != null && theirSel.isConfirmed();

        final String status;
        if (iConfirmed && theyConfirmed)  status = "Trade confirmed!";
        else if (iConfirmed)              status = "Waiting for " + partner + " to confirm";
        else if (theyConfirmed)           status = partner + " confirmed - confirm to trade";
        else                              status = "Selecting items";
        font.setColor(0xd8 / 255f, 0xc8 / 255f, 0xa8 / 255f, 1f);
        this.drawCenteredText(batch, font, status, this.tradeSubRect("panel.hud.trade-title", cTrade, ox, oy, s));

        font.setColor(roleColorFor(me != null ? me.getChatRole() : null));
        this.drawCenteredText(batch, font, myName, this.tradeSubRect("panel.hud.trade.player0", cTrade, ox, oy, s));
        font.setColor(0.40f, 0.78f, 0.88f, 1f);
        this.drawCenteredText(batch, font, partner, this.tradeSubRect("panel.hud.trade.player1", cTrade, ox, oy, s));
        font.setColor(Color.WHITE);

        final int[][] myCells   = UiAtlas.gridCells("panel.hud.trade.player0.inv");
        final int[][] theirCells = UiAtlas.gridCells("panel.hud.trade.player1.inv");
        final int count = Player.TRADE_SLOT_COUNT;

        if (myCells != null) {
            for (int i = 0; i < count && i < myCells.length; i++) {
                final float[] r = this.tradeCellRect(myCells[i], cTrade, ox, oy, s);
                final int slotIdx = i + Player.EQUIPMENT_SLOT_COUNT;
                final Slots slot = (slotIdx < this.inventory.length) ? this.inventory[slotIdx] : null;
                final GameItem item = (slot != null) ? slot.getItem() : null;
                final boolean selected = slot != null && slot.isSelected();
                this.drawTradeSlot(batch, shapes, r[0], r[1], r[2], r[3], item, selected);
                if (this.tradeMyButtons != null && this.tradeMyButtons[i] != null) {
                    this.tradeMyButtons[i].getPos().x = r[0];
                    this.tradeMyButtons[i].getPos().y = r[1];
                }
            }
        }
        // Partner items come from the trade-accept snapshot; the on-wire selection carries no items.
        final Boolean[] theirFlags = (theirSel != null) ? theirSel.getSelection() : null;
        final GameItem[] theirItems = this.partnerInventory;
        if (theirCells != null) {
            for (int i = 0; i < count && i < theirCells.length; i++) {
                final float[] r = this.tradeCellRect(theirCells[i], cTrade, ox, oy, s);
                final int partnerSlotIdx = i + Player.EQUIPMENT_SLOT_COUNT;
                final GameItem item = (theirItems != null && partnerSlotIdx < theirItems.length)
                        ? theirItems[partnerSlotIdx] : null;
                final boolean sel = (theirFlags != null && i < theirFlags.length
                                      && theirFlags[i] != null) ? theirFlags[i] : false;
                this.drawTradeSlot(batch, shapes, r[0], r[1], r[2], r[3], item, sel);
            }
        }

        // Button art is baked into the container sprite; only overlay the centered label.
        font.setColor(Color.WHITE);
        this.drawCenteredText(batch, font, iConfirmed ? "Confirmed" : "Confirm", confirmRect);
        this.drawCenteredText(batch, font, "Cancel", cancelRect);
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Screen rect [x,y,w,h] of a trade sub-panel, offset from the
     *  centered container origin. */
    private float[] tradeSubRect(String id, UiComponent container, int ox, int oy, int s) {
        final UiComponent c = UiAtlas.componentOf(id);
        if (c == null) return new float[] { ox, oy, 0, 0 };
        return new float[] {
                ox + (c.getX() - container.getX()) * s,
                oy + (c.getY() - container.getY()) * s,
                c.getW() * s,
                c.getH() * s,
        };
    }

    /** Screen rect [x,y,w,h] of one grid cell (absolute sheet coords from
     *  UiAtlas.gridCells), offset from the centered container origin. */
    private float[] tradeCellRect(int[] cell, UiComponent container, int ox, int oy, int s) {
        return new float[] {
                ox + (cell[0] - container.getX()) * s,
                oy + (cell[1] - container.getY()) * s,
                cell[2] * s,
                cell[3] * s,
        };
    }

    private void drawCenteredText(SpriteBatch batch, BitmapFont font, String text, float[] rect) {
        if (text == null || rect == null) return;
        final GlyphLayout gl = new GlyphLayout(font, text);
        font.draw(batch, text,
                rect[0] + (rect[2] - gl.width)  / 2f,
                rect[1] + (rect[3] + gl.height) / 2f - gl.height * 0.15f);
    }

    /** Party-invite Accept/Decline prompt on the left; auto-dismisses after the 60s server TTL. */
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
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        UiRender.panel(batch, shapes, boxX, boxY, boxW, boxH,
                new Color(0x1a / 255f, 0x12 / 255f, 0x18 / 255f, 0.94f),
                new Color(0xc8 / 255f, 0xa8 / 255f, 0x6e / 255f, 1f));
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

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        UiRender.panel(batch, shapes, boxX, boxY, boxW, boxH,
                new Color(0x1a / 255f, 0x12 / 255f, 0x18 / 255f, 0.94f),
                new Color(0xc8 / 255f, 0xa8 / 255f, 0x6e / 255f, 1f));
        font.setColor(0xc8 / 255f, 0xa8 / 255f, 0x6e / 255f, 1f);
        font.draw(batch, this.pendingTradeRequestFrom + " wants to trade", boxX + 10, boxY + 22);
        font.setColor(Color.WHITE);

        this.drawTradeButton(batch, shapes, font, this.tradeRequestAcceptBtn,
                "ACCEPT", new Color(0.25f, 0.78f, 0.35f, 1f));
        this.drawTradeButton(batch, shapes, font, this.tradeRequestDeclineBtn,
                "DECLINE", new Color(0.78f, 0.27f, 0.27f, 1f));
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** One trade-overlay slot: selection border + item icon (the cell recess is baked into the sprite). */
    private void drawTradeSlot(SpriteBatch batch, ShapeRenderer shapes,
                                float x, float y, float w, float h,
                                GameItem item, boolean selected) {
        if (selected) {
            batch.end();
            shapes.begin(ShapeRenderer.ShapeType.Line);
            shapes.setColor(0xc8 / 255f, 0xa8 / 255f, 0x6e / 255f, 1f);
            shapes.rect(x, y, w, h);
            shapes.rect(x + 1, y + 1, w - 2, h - 2);
            shapes.end();
            batch.begin();
        }
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
        UiRender.panel(batch, shapes, b.getPos().x, b.getPos().y, b.getWidth(), b.getHeight(),
                bg, new Color(0, 0, 0, 0.8f));
        final GlyphLayout gl = new GlyphLayout(font, label);
        font.setColor(Color.WHITE);
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
            // Open the menu on RELEASE so handleContextMenuInput's mouse-down
            // edge-trigger doesn't dismiss it the same frame.
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

        // Party section sits above nearby (inline in the legacy layout); the
        // sprite HUD draws it in its own chrome and sets suppressInternalParty.
        final int partyConsumed = this.suppressInternalParty
                ? 0
                : this.renderPartyMembers(batch, shapes, font, startX, panelWidth);

        int headerY = this.layoutNearbyY + partyConsumed;
        font.setColor(0.78f, 0.66f, 0.43f, 1f);
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

            TextureRegion icon = this.getClassIcon(p.getClassId());
            if (icon != null) {
                batch.draw(icon, x, y + (entryHeight - iconSize) / 2f, iconSize, iconSize);
            }

            final Color nameColor = (this.hoveredPlayer == p)
                    ? Color.YELLOW
                    : roleColorFor(p.getChatRole());
            font.setColor(nameColor);
            // Vertically center the name against the entry (same as the icon),
            // measuring the glyph height instead of a fixed offset that dropped
            // the text below the icon.
            final GlyphLayout nameGl = new GlyphLayout(font, p.getName());
            font.draw(batch, nameGl,
                    x + iconSize + 6,
                    y + (entryHeight - nameGl.height) / 2f);
        }
        font.setColor(Color.WHITE);
    }

    /** Party-members section above nearby: class icon, name, HP/MP mini-bars, cd strip.
     *  Returns the pixel height consumed, or 0 when not in a party.
     */
    private int renderPartyMembers(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font,
                                    int startX, int panelWidth) {
        if (this.playState == null) return 0;
        final long partyId = this.playState.getPartyId();
        final NetPartyMember[] members = this.playState.getPartyMembers();
        if (partyId == 0L || members == null || members.length == 0) return 0;

        final long localId = this.playState.getPlayer() != null
                ? this.playState.getPlayer().getId() : 0L;
        final long leaderId = this.playState.getPartyLeaderId();
        final boolean localIsLeader = leaderId == localId;
        List<NetPartyMember> toDraw = new ArrayList<>();
        for (NetPartyMember m : members) {
            if (m != null && m.getPlayerId() != localId) toDraw.add(m);
        }

        // Cards enlarged for an at-a-glance party snapshot; the 5 ability cells
        // auto-size to span the bar width so cooldowns read clearly.
        final int rowH = 54;
        final int iconSize = 30;
        final int rowGap = 3;
        final int cdCellGap = 3;
        this._lastPartyAbilityCells.clear();
        this._lastPartyPassiveCells.clear();
        this._lastPartyMemberRows.clear();
        this._lastPartyMemberRefs.clear();
        int headerY = this.layoutNearbyY;
        font.setColor(1.00f, 0.85f, 0.36f, 1f);
        font.draw(batch, "Players In Party  " + members.length + "/4", startX, headerY);
        font.setColor(Color.WHITE);
        int y = headerY + 12;
        final long nowMs = System.currentTimeMillis();
        for (NetPartyMember m : toDraw) {
            this._lastPartyMemberRows.add(new float[] { startX, y, panelWidth, rowH });
            this._lastPartyMemberRefs.add(m);
            TextureRegion icon = this.getClassIcon(m.getClassId());
            if (icon != null) {
                batch.draw(icon, startX, y + (rowH - iconSize) / 2f, iconSize, iconSize);
            }
            String name = m.getName() != null ? m.getName() : "?";
            if (name.length() > 12) name = name.substring(0, 12);
            if (m.getPlayerId() == leaderId) name = "* " + name;
            font.setColor(1.00f, 0.94f, 0.62f, 1f);
            font.draw(batch, name, startX + iconSize + 6, y + 11);
            font.setColor(Color.WHITE);
            if (localIsLeader) {
                font.setColor(0xe0 / 255f, 0x40 / 255f, 0x40 / 255f, 1f);
                font.draw(batch, "x", startX + panelWidth - 12, y + 11);
                font.setColor(Color.WHITE);
            }
            final float hpPct = m.getMaxHealth() > 0
                    ? Math.max(0f, Math.min(1f, m.getHealth() / (float) m.getMaxHealth())) : 0f;
            final float mpPct = m.getMaxMana() > 0
                    ? Math.max(0f, Math.min(1f, m.getMana() / (float) m.getMaxMana())) : 0f;
            final int barX = startX + iconSize + 6;
            final int barW = panelWidth - (iconSize + 6) - 4;
            final int hpY = y + 16;
            final int mpY = hpY + 6;
            final int cdY = mpY + 6;
            final int cdStripX = barX;
            final int cdCellSize = Math.max(12, Math.min(24, (barW - 4 * cdCellGap) / 5));
            final Integer[] bindings = m.getHotbarBindings();
            final Long[] cdEnds = m.getAbilityCooldownEnds();
            batch.end();
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0.10f, 0.06f, 0.06f, 0.88f);
            shapes.rect(barX, hpY, barW, 5);
            shapes.setColor(0.78f, 0.06f, 0.19f, 0.95f);
            shapes.rect(barX, hpY, barW * hpPct, 5);
            shapes.setColor(0.06f, 0.06f, 0.12f, 0.88f);
            shapes.rect(barX, mpY, barW, 4);
            shapes.setColor(0.31f, 0.44f, 1.00f, 0.95f);
            shapes.rect(barX, mpY, barW * mpPct, 4);
            for (int i = 0; i < 5; i++) {
                final float cx = cdStripX + i * (cdCellSize + cdCellGap);
                shapes.setColor(0.10f, 0.07f, 0.03f, 0.92f);
                shapes.rect(cx, cdY, cdCellSize, cdCellSize);
            }
            shapes.end();
            batch.begin();
            // Passive cell (index 0): class icon marks the passive; cache for hover.
            if (icon != null) {
                batch.draw(icon, cdStripX + 1, cdY + 1, cdCellSize - 2, cdCellSize - 2);
            }
            this._lastPartyPassiveCells.add(new float[] {
                    cdStripX, cdY, cdCellSize, cdCellSize, m.getClassId() });
            final Integer[] invested = m.getHotbarInvested();
            final NetStats netStats = m.getStats();
            final int sVit = netStats != null ? netStats.getVit() : 0;
            final int sWis = netStats != null ? netStats.getWis() : 0;
            final int sHp  = netStats != null ? netStats.getHp()  : 0;
            final int sMp  = netStats != null ? netStats.getMp()  : 0;
            final int sStr = netStats != null ? netStats.getStr() : 0;
            final int sDef = netStats != null ? netStats.getDef() : 0;
            final int sSpd = netStats != null ? netStats.getSpd() : 0;
            final int sDex = netStats != null ? netStats.getDex() : 0;
            if (bindings != null) {
                for (int i = 0; i < 4 && i < bindings.length; i++) {
                    final int aid = bindings[i] != null ? bindings[i] : 0;
                    if (aid <= 0) continue;
                    final Ability ab = GameDataManager.ABILITIES == null ? null
                            : GameDataManager.ABILITIES.get(aid);
                    if (ab == null) continue;
                    final float cx = cdStripX + (i + 1) * (cdCellSize + cdCellGap);
                    final int inv = (invested != null && i < invested.length && invested[i] != null)
                            ? invested[i] : 0;
                    this._lastPartyAbilityCells.add(new float[] {
                            cx, cdY, cdCellSize, cdCellSize, (float) aid, (float) inv,
                            sVit, sWis, sHp, sMp, sStr, sDef, sSpd, sDex
                    });
                    if (ab.getSpriteKey() != null && !ab.getSpriteKey().isEmpty()) {
                        final int spriteSize = ab.getSpriteSize() > 0 ? ab.getSpriteSize() : 8;
                        final Sprite spr = GameSpriteManager.loadSprite(ab.getCol(), ab.getRow(),
                                ab.getSpriteKey(), spriteSize);
                        if (spr != null && spr.getRegion() != null) {
                            batch.draw(spr.getRegion(), cx + 1, cdY + 1, cdCellSize - 2, cdCellSize - 2);
                        }
                    }
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

    /** Class passive for a given classId (party members carry only classId). */
    private PassiveAbility classPassive(int classId) {
        if (GameDataManager.CHARACTER_CLASSES == null || GameDataManager.PASSIVES == null) return null;
        final CharacterClassModel cls = GameDataManager.CHARACTER_CLASSES.get(classId);
        if (cls == null || cls.getAbilityTree() == null) return null;
        final int id = cls.getAbilityTree().getPassive();
        return id > 0 ? GameDataManager.PASSIVES.get(id) : null;
    }

    /**
     * Inspect panel for the hovered party member: full stats + equipped items.
     * Stays open while the cursor is over the row or the panel, so the player
     * can move onto an equipment icon to see its full item tooltip like an
     * inventory hover. Populates the equip-cell caches for that hover pass.
     */
    private void renderPartyMemberInspect(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        this._lastPartyEquipRects.clear();
        this._lastPartyEquipItems.clear();
        final NetPartyMember m = this.hoveredPartyMember;
        if (m == null || this._hoveredPartyRowRect == null) {
            this._partyInspectPanelRect = null;
            return;
        }
        final NetStats st = m.getStats();
        final NetGameItem[] equip = m.getEquipment();
        final int padX = 10;
        final int padY = 10;
        final int lineH = 15;
        final int equipSlot = 30;
        final int equipGap = 4;
        final int panelW = 5 * equipSlot + 4 * equipGap + padX * 2;
        final int panelH = padY * 2 + 7 * lineH + 8 + equipSlot;

        final float[] row = this._hoveredPartyRowRect;
        float px = row[0] + row[2] + 8;
        if (px + panelW > OpenRealmGame.width - 4) px = row[0] - panelW - 8;
        if (px < 4) px = 4;
        float py = row[1] - 4;
        if (py + panelH > OpenRealmGame.height - 4) py = OpenRealmGame.height - 4 - panelH;
        if (py < 4) py = 4;
        this._partyInspectPanelRect = new float[] { px, py, panelW, panelH };

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        UiRender.panel(batch, shapes, px, py, panelW, panelH,
                new Color(0x1a / 255f, 0x12 / 255f, 0x18 / 255f, 0.96f),
                new Color(0x4a / 255f, 0x3a / 255f, 0x58 / 255f, 1f));

        final CharacterClass cls = CharacterClass.valueOf(m.getClassId());
        final String className = (cls != null) ? cls.name() : "Unknown";
        int ty = (int) py + padY + 12;
        font.setColor(0xff / 255f, 0xf0 / 255f, 0xa0 / 255f, 1f);
        font.draw(batch, m.getName() != null ? m.getName() : "Player", px + padX, ty);
        ty += lineH;
        font.setColor(0x88 / 255f, 0x78 / 255f, 0x68 / 255f, 1f);
        font.draw(batch, "Lv " + m.getLevel() + " " + className, px + padX, ty);
        ty += lineH;
        font.setColor(0xe0 / 255f, 0x55 / 255f, 0x55 / 255f, 1f);
        font.draw(batch, "HP: " + m.getHealth() + "/" + m.getMaxHealth(), px + padX, ty);
        ty += lineH;
        font.setColor(0x55 / 255f, 0x77 / 255f, 0xe0 / 255f, 1f);
        font.draw(batch, "MP: " + m.getMana() + "/" + m.getMaxMana(), px + padX, ty);
        ty += lineH;
        font.setColor(0xc8 / 255f, 0xa8 / 255f, 0x6e / 255f, 1f);
        if (st != null) {
            final int colX2 = (int) px + panelW / 2;
            font.draw(batch, "STR " + st.getStr(), px + padX, ty);
            font.draw(batch, "VIT " + st.getVit(), colX2, ty);
            ty += lineH;
            font.draw(batch, "DEF " + st.getDef(), px + padX, ty);
            font.draw(batch, "WIS " + st.getWis(), colX2, ty);
            ty += lineH;
            font.draw(batch, "SPD " + st.getSpd(), px + padX, ty);
            font.draw(batch, "DEX " + st.getDex(), colX2, ty);
            ty += lineH;
        } else {
            ty += lineH * 3;
        }
        ty += 8;
        final int equipY = ty - 4;
        for (int i = 0; i < 5; i++) {
            final float sx = px + padX + i * (equipSlot + equipGap);
            batch.end();
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0x2a / 255f, 0x20 / 255f, 0x30 / 255f, 1f);
            shapes.rect(sx, equipY, equipSlot, equipSlot);
            shapes.end();
            shapes.begin(ShapeRenderer.ShapeType.Line);
            shapes.setColor(0x3a / 255f, 0x2a / 255f, 0x38 / 255f, 1f);
            shapes.rect(sx, equipY, equipSlot, equipSlot);
            shapes.end();
            batch.begin();
            final NetGameItem ng = (equip != null && i < equip.length) ? equip[i] : null;
            if (ng != null && ng.getItemId() != -1) {
                final GameItem gi = ng.asGameItem();
                final TextureRegion region = GameSpriteManager.ITEM_SPRITES.get(gi.getItemId());
                if (region != null) {
                    batch.draw(region, sx + 2, equipY + 2, equipSlot - 4, equipSlot - 4);
                }
                this._lastPartyEquipItems.add(gi);
                this._lastPartyEquipRects.add(new float[] { sx, equipY, equipSlot, equipSlot });
            }
        }
        Gdx.gl.glDisable(GL20.GL_BLEND);
        font.setColor(Color.WHITE);
    }

    /** Leader-only party kick: edge-triggered click on the row's "x" sends /party kick {name}. */
    private void handlePartyKickClick(MouseHandler mouse) {
        final boolean down = mouse.isPressed(1);
        final boolean justClicked = down && !this.prevPartyKickMouseDown;
        this.prevPartyKickMouseDown = down;
        if (!justClicked || this.playState == null) return;
        final long localId = this.playState.getPlayer() != null ? this.playState.getPlayer().getId() : 0L;
        if (this.playState.getPartyLeaderId() != localId) return;
        final int mx = mouse.getX();
        final int my = mouse.getY();
        for (int i = 0; i < this._lastPartyMemberRows.size(); i++) {
            final float[] r = this._lastPartyMemberRows.get(i);
            final float kx = r[0] + r[2] - 18;
            if (mx >= kx && mx < r[0] + r[2] && my >= r[1] && my < r[1] + 16) {
                final NetPartyMember target = this._lastPartyMemberRefs.get(i);
                if (target != null && target.getName() != null) {
                    this.sendServerCommand("party", "kick " + target.getName());
                }
                return;
            }
        }
    }

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

    /** Player context menu: name header + Trade / Teleport rows (send the same /trade, /tp commands). */
    private void renderPlayerContextMenu(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        if (this.contextMenuPlayer == null) return;
        final Player p = this.contextMenuPlayer;
        final int x = this.contextMenuX;
        final int yHeader = this.contextMenuY;
        final int yTrade = yHeader + CTX_MENU_HEADER_H;
        final int yTp = yTrade + CTX_MENU_OPTION_H;
        final int totalH = CTX_MENU_HEADER_H + 2 * CTX_MENU_OPTION_H;

        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0x1a / 255f, 0x12 / 255f, 0x18 / 255f, 0.96f);
        shapes.rect(x, yHeader, CTX_MENU_W, totalH);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0x3a / 255f, 0x2a / 255f, 0x38 / 255f, 1f);
        shapes.rect(x, yHeader, CTX_MENU_W, totalH);
        shapes.line(x, yTrade, x + CTX_MENU_W, yTrade);
        shapes.line(x, yTp, x + CTX_MENU_W, yTp);
        shapes.end();
        batch.begin();

        font.setColor(0xc8 / 255f, 0xa8 / 255f, 0x6e / 255f, 1f);
        font.draw(batch, p.getName() == null ? "Player" : p.getName(),
                x + 6, yHeader + 14);

        font.setColor(0xe0 / 255f, 0xd8 / 255f, 0xc8 / 255f, 1f);
        final String pname = (p.getName() != null) ? p.getName() : "Player";
        final String shortName = pname.length() > 10 ? pname.substring(0, 10) : pname;
        font.draw(batch, "Trade with "    + shortName, x + 6, yTrade + 14);
        font.draw(batch, "Teleport to "   + shortName, x + 6, yTp + 14);
        font.setColor(Color.WHITE);
    }

    /** Edge-triggered context-menu hit-test; called last in input() so no row click is swallowed. */
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

    /** Send a SERVER_COMMAND packet, same path PlayerChat uses for typed slash commands. */
    private void sendServerCommand(String command, String arg) {
        try {
            final String full = "/" + command + " " + (arg == null ? "" : arg);
            final ServerCommandMessage msg = ServerCommandMessage.parseFromInput(full);
            final CommandPacket packet = CommandPacket.create(this.playState.getPlayer(),
                    CommandType.SERVER_COMMAND, msg);
            this.playState.getRealmManager().getClient().sendRemote(packet);
        } catch (Exception ex) {
            log.error("{} Failed to send server command /{} {}: {}", LOG_NS, command, arg, ex.getMessage());
        }
    }

    private void renderPlayerTooltip(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        if (this.hoveredPlayer == null) return;

        final Player p = this.hoveredPlayer;
        final int padX = 10;
        final int padY = 8;
        final int tooltipW = 240;

        final int hp = p.getHealth();
        final int mp = p.getMana();
        final int maxHp = (p.getStats() != null) ? p.getStats().getHp() : 0;
        final int maxMp = (p.getStats() != null) ? p.getStats().getMp() : 0;
        final CharacterClass cls = CharacterClass.valueOf(p.getClassId());
        final String className = (cls != null) ? cls.name() : "Unknown";
        int level = -1;
        try {
            if (GameDataManager.EXPERIENCE_LVLS != null) {
                level = GameDataManager.EXPERIENCE_LVLS.getLevel(p.getExperience());
            }
        } catch (Exception ignored) { }
        final String levelStr = (level > 0) ? ("Lv " + level + " ") : "";

        final GameItem[] equips = p.getSlots(0, Player.EQUIPMENT_SLOT_COUNT);

        final int nameRowH  = 16;
        final int classRowH = 15;
        final int hpRowH    = 15;
        final int mpRowH    = 15;
        final int gapBeforeEquip = 10;
        final int equipSlot = 36;
        final int equipGap  = 4;
        final int equipRowH = equipSlot + 4;
        final int tooltipH = padY + nameRowH + classRowH + hpRowH + mpRowH
                            + gapBeforeEquip + equipRowH + padY;

        int tooltipX = (this.spriteHudNearbyPanelRight > 0)
                ? this.spriteHudNearbyPanelRight + 8
                : this.hoveredBtnX + this.hoveredBtnW + 6;
        if (tooltipX + tooltipW > OpenRealmGame.width - 4) {
            tooltipX = Math.max(4, OpenRealmGame.width - tooltipW - 4);
        }
        int tooltipY = this.hoveredBtnY - 4;
        if (tooltipY < 4) tooltipY = 4;
        if (tooltipY + tooltipH > OpenRealmGame.height - 4) {
            tooltipY = Math.max(4, OpenRealmGame.height - tooltipH - 4);
        }

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        UiRender.panel(batch, shapes, tooltipX, tooltipY, tooltipW, tooltipH,
                new Color(0x1a / 255f, 0x12 / 255f, 0x18 / 255f, 0.94f),
                new Color(0x4a / 255f, 0x3a / 255f, 0x58 / 255f, 1f));

        int y = tooltipY + padY + nameRowH;
        font.setColor(roleColorFor(p.getChatRole()));
        final String nameLine = p.getName() == null ? "Player" : p.getName();
        font.draw(batch, nameLine, tooltipX + padX, y);
        if (p.getChatRole() != null && !p.getChatRole().isEmpty()) {
            final GlyphLayout nameGl = new GlyphLayout(font, nameLine);
            font.setColor(0x88 / 255f, 0x78 / 255f, 0x68 / 255f, 1f);
            font.draw(batch, "[" + p.getChatRole() + "]",
                    tooltipX + padX + nameGl.width + 6, y);
        }
        y += classRowH;
        font.setColor(0x88 / 255f, 0x78 / 255f, 0x68 / 255f, 1f);
        font.draw(batch, levelStr + className, tooltipX + padX, y);
        y += hpRowH;
        font.setColor(0xe0 / 255f, 0x55 / 255f, 0x55 / 255f, 1f);
        font.draw(batch, "HP: " + hp + "/" + maxHp, tooltipX + padX, y);
        y += mpRowH;
        font.setColor(0x55 / 255f, 0x77 / 255f, 0xe0 / 255f, 1f);
        font.draw(batch, "MP: " + mp + "/" + maxMp, tooltipX + padX, y);
        y += gapBeforeEquip;
        // Thin divider between the stat lines and the equipment row.
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0x3a / 255f, 0x2a / 255f, 0x38 / 255f, 1f);
        shapes.rect(tooltipX + padX, y - 5, tooltipW - padX * 2, 1);
        shapes.end();
        batch.begin();
        // Center the 5 equipment slots within the card width.
        final int equipRowW = equips.length * equipSlot + (equips.length - 1) * equipGap;
        int equipStartX = tooltipX + (tooltipW - equipRowW) / 2;
        for (int i = 0; i < equips.length; i++) {
            final int sx = equipStartX + i * (equipSlot + equipGap);
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

    /** Edge-triggered bag-tab click toggling the active page (legacy sidebar fallback). */
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
                        this.dragSourceIndex = i + MoveItemPacket.groundLootBase();
                        this.dragStartPos = new Vector2f(mouseX, mouseY);
                        break;
                    }
                }
            }
        }

        if (this.dragSourceIndex != -1 && !this.isDragging && this.dragStartPos != null) {
            float dist = new Vector2f(mouseX, mouseY).distanceTo(this.dragStartPos);
            if (dist > DRAG_THRESHOLD) {
                this.isDragging = true;
            }
        }

        // Fire on release even if the drag threshold was never crossed: dragStartPos
        // is sampled a frame late, so gating on isDragging drops quick drags.
        if (!mouse.isPressed(1) && this.dragSourceIndex != -1) {
            final int dropPage = this.tabHitPage(mouseX, mouseY);
            int targetIndex = this.tabDropTargetSlot(mouseX, mouseY);
            if (targetIndex < 0) {
                targetIndex = this.findSlotAtPositionByLayout(mouseX, mouseY);
            }
            if (targetIndex != this.dragSourceIndex) {
                this.executeDrop(this.dragSourceIndex, targetIndex);
                if (dropPage >= 0 && targetIndex >= 0) {
                    this.activeBag = dropPage;
                }
            }
            this.isDragging = false;
            this.dragSourceIndex = -1;
            this.dragStartPos = null;
        }
    }

    /** Hit-test a screen point against every slot rect (incl. empty) and return the
     *  wire index (equipment 0..4, backpack 5..24, ground loot 25..34), or -1. */
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
            // Backpack page grid (20 cells). Each visible cell maps to the
            // real slot pageSlotIndex(cell) so the Backpack tab targets 25..44.
            final UiComponent cInvOnly = UiAtlas.componentOf("panel.hud.inv_only");
            if (cInvOnly != null) {
                final float[][] invRects = this.invPageCellRects(cInvOnly,
                        this.spriteHudInvOnlyX, this.spriteHudInvOnlyY, s);
                for (int i = 0; i < invRects.length; i++) {
                    final int cx = (int) invRects[i][0];
                    final int cy = (int) invRects[i][1];
                    final int cw = (int) invRects[i][2];
                    final int ch = (int) invRects[i][3];
                    if (mouseX >= cx && mouseX < cx + cw && mouseY >= cy && mouseY < cy + ch) {
                        return this.pageSlotIndex(i);
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

        // Legacy sidebar fallback (atlas not ready).
        int panelWidth = (OpenRealmGame.width / 5);
        int startX = OpenRealmGame.width - panelWidth;
        if (mouseX < startX || mouseX > OpenRealmGame.width) return -1;
        int col = -1;
        for (int c = 0; c < Player.EQUIPMENT_SLOT_COUNT; c++) {
            int sx = this.slotX(c);
            if (mouseX >= sx && mouseX < sx + SLOT_SIZE) { col = c; break; }
        }
        if (col >= 0 && mouseY >= this.layoutEquipY && mouseY < this.layoutEquipY + SLOT_SIZE) {
            return col;
        }
        if (col >= 0 && col < 5) {
            for (int row = 0; row < 4; row++) {
                final int ry = this.layoutBag1Y + row * (SLOT_SIZE + SLOT_GAP);
                if (mouseY >= ry && mouseY < ry + SLOT_SIZE) {
                    return this.pageSlotIndex(row * 5 + col);
                }
            }
        }
        if (col >= 0 && col < 4) {
            int gl0 = this.groundLootRowY(0);
            int gl1 = this.groundLootRowY(1);
            final int groundBase = MoveItemPacket.groundLootBase();
            if (mouseY >= gl0 && mouseY < gl0 + SLOT_SIZE) return groundBase + col;
            if (mouseY >= gl1 && mouseY < gl1 + SLOT_SIZE) return groundBase + 4 + col;
        }
        return -1;
    }

    private void executeDrop(int fromIndex, int targetIndex) {
        if (!this.canSwap()) return;
        if (fromIndex == targetIndex) return;

        this.setActionTime();

        // Forge drop-zones take priority when the forge window is up.
        if (this.forgeWindow.isVisible() && fromIndex >= 0 && fromIndex < this.inventory.length) {
            final int mx = Gdx.input.getX();
            final int my = Gdx.input.getY();
            final Slots srcSlot = this.inventory[fromIndex];
            final GameItem srcItem = srcSlot != null ? srcSlot.getItem() : null;
            int crystalItemId = -1;
            int crystalStatId = -1;
            if (srcItem != null) {
                crystalItemId = srcItem.getItemId();
                // Crystal stat id is encoded as itemId - 808 (ServerFameStoreHelper.CRYSTAL_ITEM_MIN).
                if (crystalItemId >= 808 && crystalItemId <= 815) {
                    crystalStatId = crystalItemId - 808;
                }
            }
            if (this.forgeWindow.tryAcceptDrop(mx, my, fromIndex, crystalItemId, crystalStatId)) {
                return;
            }
        }

        // Potion-storage moves go through ItemStoreMovePacket (storage lives off-inventory).
        if (this.potionStorageWindow.isVisible() && fromIndex >= 0 && fromIndex < this.inventory.length) {
            final int mx = Gdx.input.getX();
            final int my = Gdx.input.getY();
            if (this.potionStorageWindow.tryAcceptDrop(mx, my, fromIndex)) {
                return;
            }
        }

        // Wire protocol: equipment 0..4, backpack 5..24, ground loot 25..34.
        final int groundEnd = MoveItemPacket.groundLootBase() + this.groundLoot.length;
        boolean fromIsGround = fromIndex >= MoveItemPacket.groundLootBase() && fromIndex < groundEnd;
        boolean targetIsGround = targetIndex >= MoveItemPacket.groundLootBase() && targetIndex < groundEnd;
        boolean fromIsEquip = fromIndex >= 0 && fromIndex < Player.EQUIPMENT_SLOT_COUNT;
        boolean targetIsEquip = targetIndex >= 0 && targetIndex < Player.EQUIPMENT_SLOT_COUNT;

        if (targetIndex == -1) {
            this.playState.getRealmManager().moveItem(-1, fromIndex, true, false);
        } else if (fromIsGround && !targetIsGround) {
            // Ground -> inventory/equip pickup. HP/MP potions pin to a fixed slot
            // to skip the equip-validation path on slots 0-3.
            final Slots srcSlot = this.groundLoot[fromIndex - MoveItemPacket.groundLootBase()];
            final GameItem srcItem = srcSlot != null ? srcSlot.getItem() : null;
            if (srcItem != null
                    && (srcItem.getItemId() == Player.HP_POTION_ITEM_ID
                     || srcItem.getItemId() == Player.MP_POTION_ITEM_ID)) {
                this.playState.getRealmManager().moveItem(Player.EQUIPMENT_SLOT_COUNT, fromIndex, false, false);
            } else {
                this.playState.getRealmManager().moveItem(targetIndex, fromIndex, false, false);
            }
        } else if (!fromIsGround && targetIsGround) {
            this.playState.getRealmManager().moveItem(-1, fromIndex, true, false);
        } else {
            this.playState.getRealmManager().moveItem(targetIndex, fromIndex, false, false);
        }
    }

    /** Bottom-center "PRESS SPACE TO ENTER {NAME}" portal prompt. */
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
            } catch (Exception ignored) { }
            this.renderHintBox(batch, shapes, font, "PRESS SPACE TO ENTER " + name.toUpperCase(), 0);
        } catch (Exception ignored) { }
    }

    /** F-key interaction prompt (forge / fame store / etc.), stacked above the portal prompt. */
    private void renderInteractPrompt(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        if (this.playState == null || this.playState.getPlayer() == null) return;
        if (this.forgeWindow != null && this.forgeWindow.isVisible()) return;
        if (this.fameStoreWindow != null && this.fameStoreWindow.isVisible()) return;
        if (this.exchangeMarketWindow != null && this.exchangeMarketWindow.isVisible()) return;
        if (this.potionStorageWindow != null && this.potionStorageWindow.isVisible()) return;
        try {
            final String type = this.playState.getNearbyInteractionType();
            if (type == null) return;
            final String label;
            if ("forge".equalsIgnoreCase(type)) label = "PRESS F TO USE FORGE";
            else if ("fame_store".equalsIgnoreCase(type)) label = "PRESS F TO OPEN FAME SHOP";
            else if ("potion_storage".equalsIgnoreCase(type)) label = "PRESS F TO OPEN POTION STORAGE";
            else if ("exchange_market".equalsIgnoreCase(type)) label = "PRESS F TO OPEN EXCHANGE MARKET";
            else label = "PRESS F TO INTERACT";
            this.renderHintBox(batch, shapes, font, label, 1);
        } catch (Exception ignored) { }
    }

    /** Shared bottom-center hint box; stackIndex lifts it above the previous hint. */
    private void renderHintBox(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font, String text, int stackIndex) {
        final GlyphLayout gl = new GlyphLayout(font, text);
        final int padX = 14, padY = 10;
        final int boxW = (int) gl.width + padX * 2;
        final int boxH = (int) gl.height + padY * 2;
        final int hotbarReserve = 72 + 16;
        final int boxX = (OpenRealmGame.width  - boxW) / 2;
        final int boxY = OpenRealmGame.height - hotbarReserve - 12 - boxH - stackIndex * (boxH + 6);

        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, 0.85f);
        shapes.rect(boxX, boxY, boxW, boxH);
        shapes.setColor(0.95f, 0.85f, 0.45f, 1f);
        shapes.rect(boxX, boxY, boxW, 2);
        shapes.rect(boxX, boxY + boxH - 2, boxW, 2);
        shapes.rect(boxX, boxY, 2, boxH);
        shapes.rect(boxX + boxW - 2, boxY, 2, boxH);
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
        batch.begin();

        font.setColor(0.95f, 0.85f, 0.45f, 1f);
        UiRender.drawCenteredIn(batch, font, text, boxX, boxY, boxW, boxH);
        font.setColor(Color.WHITE);
    }

    private void renderStats(SpriteBatch batch, BitmapFont font) {
        if (this.playState.getPlayer() == null) return;
        this.recomputeLayout();
        final int panelWidth = OpenRealmGame.width / 5;
        final int panelStartX = OpenRealmGame.width - panelWidth;
        final int textX = panelStartX + PANEL_INSET;
        final int colGap = (panelWidth - 2 * PANEL_INSET) / 2;
        final int yOffset = 22;
        final int startY = this.layoutStatsY + 14;

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

        font.setColor(new Color(1.00f, 0.85f, 0.42f, 1f));
        final String fameStr = (fame > 0L) ? ("+ " + fame + " FAME") : "+ 0 FAME";
        GlyphLayout fameLayout = new GlyphLayout(font, fameStr);
        float fameTextX = panelStartX + (panelWidth - fameLayout.width) / 2f;
        font.draw(batch, fameStr, fameTextX, FAME_Y + FAME_H - 7);
        font.setColor(Color.WHITE);

        Stats stats = this.playState.getPlayer().getComputedStats();
        font.setColor(this.playState.getPlayer().isStatMaxed(3) ? Color.YELLOW : Color.WHITE);
        font.draw(batch, "STR " + stats.getStr(), textX, startY);
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

    /**
     * Sprite-HUD renderer: draws all panels at their screen positions, populates them
     * with live data, and repositions slot Buttons so click/drag hit-tests follow.
     * Y-down camera, so child sheet-Y offsets map directly to screen-Y.
     */
    private void renderSpriteHud(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        if (!UiAtlas.isReady()) return;
        final int s = UiAtlas.getDisplayScale();
        final int W = OpenRealmGame.width;
        final int H = OpenRealmGame.height;
        final int margin = 16;

        final UiComponent cPlayerInfo = UiAtlas.componentOf("panel.hud.player_info");
        final UiComponent cChat       = UiAtlas.componentOf("panel.hud.chat");
        final UiComponent cEquipStats = UiAtlas.componentOf("panel.hud.equipment_with_stats");
        final UiComponent cInvOnly    = UiAtlas.componentOf("panel.hud.inv_only");
        final UiComponent cMinimap    = UiAtlas.componentOf("panel.container.small");
        final UiComponent cNearby     = UiAtlas.componentOf("panel.container.small");
        final UiComponent cInvExt     = UiAtlas.componentOf("panel.hud.inv_ext");
        final UiComponent cHotbar     = UiAtlas.componentOf("panel.hud.ability_bar");
        final UiComponent cHpPot      = UiAtlas.componentOf("panel.hud.ability_bar.hp_potion");
        final UiComponent cMpPot      = UiAtlas.componentOf("panel.hud.ability_bar.mp_potion");
        if (cPlayerInfo == null || cChat == null || cEquipStats == null
                || cInvOnly == null || cMinimap == null) return;

        final float playerInfoW = cPlayerInfo.getW() * s;
        final float playerInfoH = cPlayerInfo.getH() * s;
        final float playerInfoX = margin;
        final float playerInfoY = margin;

        // Nearby panel sits between playerInfo and chat on the left column. In a party,
        // a second chrome is stamped above it and the vertical space is split in half.
        final float nearbyW = cNearby != null ? cNearby.getW() * s : 0;
        final float nearbyFullH = cNearby != null ? cNearby.getH() * s : 0;
        final float nearbyX = margin;
        final boolean partyVisible = (this.playState != null && this.playState.getPartyId() != 0L);
        final float panelGap = 8f;
        final float partyH  = partyVisible ? (nearbyFullH - panelGap) / 2f : 0f;
        final float nearbyH = partyVisible ? (nearbyFullH - panelGap) / 2f : nearbyFullH;
        final float partyY  = playerInfoY + playerInfoH + 8;
        final float nearbyY = partyVisible ? (partyY + partyH + panelGap)
                                            : (playerInfoY + playerInfoH + 8);

        final float chatW = cChat.getW() * s;
        final float chatH = cChat.getH() * s;
        final float chatX = margin;
        final float chatY = H - margin - chatH;

        // Chrome-less square minimap, sized to the equip/stats panel width.
        final float minimapSide = cEquipStats.getW() * s;
        final float minimapW = minimapSide;
        final float minimapH = minimapSide;
        final float minimapX = W - margin - minimapW;
        final float minimapY = margin;

        final float equipStatsW = cEquipStats.getW() * s;
        final float equipStatsH = cEquipStats.getH() * s;
        final float equipStatsX = W - margin - equipStatsW;
        final float equipStatsY = minimapY + minimapH; // flush under the square minimap

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

        // Potion slots live inside the bar art; position from their atlas sub-rects.
        final float potionW = cHpPot != null ? cHpPot.getW() * s : 0;
        final float potionH = cHpPot != null ? cHpPot.getH() * s : 0;
        final float hpPotX = cHpPot != null ? hotbarX + (cHpPot.getX() - cHotbar.getX()) * s : 0;
        final float hpPotY = cHpPot != null ? hotbarY + (cHpPot.getY() - cHotbar.getY()) * s : 0;
        final float mpPotX = cMpPot != null ? hotbarX + (cMpPot.getX() - cHotbar.getX()) * s : 0;
        final float mpPotY = cMpPot != null ? hotbarY + (cMpPot.getY() - cHotbar.getY()) * s : 0;

        // Panel chrome blits.
        final TextureRegion rPlayerInfo = UiAtlas.region("panel.hud.player_info");
        final TextureRegion rChat       = UiAtlas.region("panel.hud.chat");
        final TextureRegion rEquipStats = UiAtlas.region("panel.hud.equipment_with_stats");
        final TextureRegion rInvOnly    = UiAtlas.region("panel.hud.inv_only");
        final TextureRegion rInvExt     = UiAtlas.region("panel.hud.inv_ext");
        final TextureRegion rHotbar     = UiAtlas.region("panel.hud.ability_bar");

        if (rPlayerInfo != null) batch.draw(rPlayerInfo, playerInfoX, playerInfoY, playerInfoW, playerInfoH);
        final TextureRegion rNearby = UiAtlas.region("panel.container.small");
        if (partyVisible && rNearby != null && cNearby != null) {
            batch.draw(rNearby, nearbyX, partyY, nearbyW, partyH);
        }
        if (rNearby != null && cNearby != null) batch.draw(rNearby, nearbyX, nearbyY, nearbyW, nearbyH);
        if (rEquipStats != null) batch.draw(rEquipStats, equipStatsX, equipStatsY, equipStatsW, equipStatsH);
        if (rInvOnly    != null) batch.draw(rInvOnly,    invOnlyX,    invOnlyY,    invOnlyW,    invOnlyH);
        if (lootVisible && rInvExt != null) batch.draw(rInvExt, invExtX, invExtY, invExtW, invExtH);
        if (rHotbar     != null) batch.draw(rHotbar,     hotbarX,     hotbarY,     hotbarW,     hotbarH);
        final boolean chatExpanded = (this.playerChat != null) && !this.playerChat.isCollapsed();
        if (rChat != null && chatExpanded) batch.draw(rChat, chatX, chatY, chatW, chatH);

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
            } catch (Exception ignore) { }
            batch.setColor(prev);
        }

        // Cache panel origins so findSlotAtPositionByLayout can rect empty slots (no Button).
        this.spriteHudEquipStatsX = (int) equipStatsX;
        this.spriteHudEquipStatsY = (int) equipStatsY;
        this.spriteHudInvOnlyX = (int) invOnlyX;
        this.spriteHudInvOnlyY = (int) invOnlyY;
        // Draw a dark backdrop + border under every equipment slot so all 5 look uniform
        // (the chrome sprite only bakes 4 frames).
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < Player.EQUIPMENT_SLOT_COUNT; i++) {
            final UiComponent eq = UiAtlas.componentOf("panel.hud.equipment_with_stats." + i);
            if (eq == null) continue;
            final float ex = equipStatsX + (eq.getX() - cEquipStats.getX()) * s;
            final float ey = equipStatsY + (eq.getY() - cEquipStats.getY()) * s;
            final float ew = eq.getW() * s;
            final float eh = eq.getH() * s;
            shapes.setColor(0.05f, 0.05f, 0.08f, 0.85f);
            shapes.rect(ex, ey, ew, eh);
        }
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < Player.EQUIPMENT_SLOT_COUNT; i++) {
            final UiComponent eq = UiAtlas.componentOf("panel.hud.equipment_with_stats." + i);
            if (eq == null) continue;
            final float ex = equipStatsX + (eq.getX() - cEquipStats.getX()) * s;
            final float ey = equipStatsY + (eq.getY() - cEquipStats.getY()) * s;
            final float ew = eq.getW() * s;
            final float eh = eq.getH() * s;
            shapes.setColor(0.40f, 0.40f, 0.45f, 0.9f);
            shapes.rect(ex, ey, ew, eh);
        }
        shapes.end();
        batch.begin();
        for (int i = 0; i < Player.EQUIPMENT_SLOT_COUNT; i++) {
            final UiComponent eq = UiAtlas.componentOf("panel.hud.equipment_with_stats." + i);
            if (eq == null) continue;
            final float ex = equipStatsX + (eq.getX() - cEquipStats.getX()) * s;
            final float ey = equipStatsY + (eq.getY() - cEquipStats.getY()) * s;
            this.repositionSlotButton(this.inventory, i, ex, ey);
            this.drawHudItemIcon(batch, this.getInventoryItem(i),
                    ex, ey, eq.getW() * s, eq.getH() * s);
        }
        final float[][] invRects = this.invPageCellRects(cInvOnly, invOnlyX, invOnlyY, s);
        // Park the hidden page's buttons off-screen; otherwise both pages' buttons
        // overlap the same cells and a click resolves to the wrong (hidden) slot.
        this.parkInactivePageButtons();
        for (int i = 0; i < invRects.length; i++) {
            final float cx = invRects[i][0];
            final float cy = invRects[i][1];
            final float cw = invRects[i][2];
            final float ch = invRects[i][3];
            final int slotIdx = this.pageSlotIndex(i);
            this.repositionSlotButton(this.inventory, slotIdx, cx, cy);
            this.drawHudItemIcon(batch, this.getInventoryItem(slotIdx), cx, cy, cw, ch);
        }
        this.renderInvPageTabs(batch, shapes, font, cInvOnly, invOnlyX, invOnlyY,
                invRects, s);

        // Bottom-center hotbar: cell 0 = passive (name label), cells 1..4 = active
        // abilities via hotbarBindings[0..3]. The overlay pass below reads binding N-1.
        final UiComponent[] hotbarCells = {
                UiAtlas.componentOf("panel.hud.ability_bar.passive"),
                UiAtlas.componentOf("panel.hud.ability_bar.0"),
                UiAtlas.componentOf("panel.hud.ability_bar.1"),
                UiAtlas.componentOf("panel.hud.ability_bar.2"),
        };
        final Player localPlayer = (this.playState != null) ? this.playState.getPlayer() : null;
        final float[][] hotbarCellPx = new float[HOTBAR_SLOT_COUNT][4]; // [slot] = {x, y, w, h}
        for (int i = 0; i < hotbarCells.length && i < HOTBAR_SLOT_COUNT; i++) {
            final UiComponent cell = hotbarCells[i];
            if (cell == null) continue;
            final float cx = hotbarX + (cell.getX() - cHotbar.getX()) * s;
            final float cy = hotbarY + (cell.getY() - cHotbar.getY()) * s;
            final float cw = cell.getW() * s;
            final float ch = cell.getH() * s;
            hotbarCellPx[i][0] = cx;
            hotbarCellPx[i][1] = cy;
            hotbarCellPx[i][2] = cw;
            hotbarCellPx[i][3] = ch;
            if (localPlayer == null) continue;
            if (i == 0) {
                final PassiveAbility pa = localPlayer.getClassPassive();
                if (pa != null) {
                    final String name = pa.getName() != null ? pa.getName() : "Passive";
                    final float origScale = font.getData().scaleX;
                    font.getData().setScale(0.7f);
                    final GlyphLayout gl = new GlyphLayout(font, name,
                            Color.valueOf("e8d8b8"), cw - 2, 1, true);
                    final float tx = cx + (cw - gl.width) * 0.5f;
                    // gl wraps to multiple lines, so center the whole block.
                    final float ty = cy + (ch - gl.height) * 0.5f;
                    font.draw(batch, gl, tx, ty);
                    font.getData().setScale(origScale);
                }
            } else {
                final int bindingIdx = i - 1;
                final Ability ab = localPlayer.getActiveAbility(bindingIdx);
                if (ab != null) {
                    this.drawAbilityHudIcon(batch, font, ab, bindingIdx + 1, cx, cy, cw, ch);
                }
            }
        }
        this._lastHotbarCellPx = hotbarCellPx;

        if (cInvExt != null) {
            this.spriteHudInvExtX = (int) invExtX;
            this.spriteHudInvExtY = (int) invExtY;
        }

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
        // Overlay drawn in this shapes pass so the dark cooldown fade sits on top of
        // the ability icons painted earlier in the batch pass.
        this.renderAbilityHotbarOverlays(shapes, localPlayer);
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
        batch.begin();
        if (this.hp != null) this.hp.renderText(batch, font);
        if (this.mp != null) this.mp.renderText(batch, font);
        if (this.xp != null) this.xp.renderText(batch, font);

        final UiComponent rNameRect = UiAtlas.componentOf("panel.hud.player_info.player_name");
        if (rNameRect != null && this.playState != null && this.playState.getPlayer() != null) {
            final float nx = playerInfoX + (rNameRect.getX() - cPlayerInfo.getX()) * s;
            final float ny = playerInfoY + (rNameRect.getY() - cPlayerInfo.getY()) * s;
            String nameLine = this.playState.getPlayer().getName() != null
                    ? this.playState.getPlayer().getName() : "Player";
            int lvl = 1;
            try { lvl = GameDataManager.EXPERIENCE_LVLS.getLevel(this.playState.getPlayer().getExperience()); }
            catch (Exception ignore) { }
            final float origNameScale = font.getData().scaleX;
            font.getData().setScale(0.95f);
            font.setColor(0.78f, 0.66f, 0.43f, 1f);
            font.draw(batch, nameLine + "  Lv " + lvl, nx + 4, ny + 4);
            font.setColor(Color.WHITE);
            font.getData().setScale(origNameScale);
        }
        final UiComponent rStatsRect = UiAtlas.componentOf("panel.hud.equipment_with_stats.stats");
        if (rStatsRect != null) {
            final float statsX = equipStatsX + (rStatsRect.getX() - cEquipStats.getX()) * s;
            final float statsY = equipStatsY + (rStatsRect.getY() - cEquipStats.getY()) * s;
            final float statsW = rStatsRect.getW() * s;
            final float statsH = rStatsRect.getH() * s;
            this.renderStatsAt(batch, font, (int) statsX, (int) statsW, (int) statsY, (int) statsH);
        }

        this.drawPotionWidget(batch, font, hpPotX, hpPotY, potionW, potionH, true);
        this.drawPotionWidget(batch, font, mpPotX, mpPotY, potionW, potionH, false);

        if (this.minimap != null) {
            this.minimap.setLayout((int) minimapX, (int) minimapY, Math.max(32, (int) minimapSide));
        }

        if (this.playerChat != null) {
            this.playerChat.setLayout((int) chatX, (int) chatY, (int) chatW, (int) chatH);
        }

        // renderNearbyPlayers reads these + layoutNearbyY to land the list inside the panel.
        this.spriteHudNearbyX = (int)(nearbyX + 8);
        this.spriteHudNearbyW = (int)(nearbyW - 16);
        this.spriteHudNearbyY = (int)(nearbyY + 8);
        this.spriteHudNearbyPanelRight  = (int)(nearbyX + nearbyW);
        this.spriteHudNearbyPanelTop    = (int)(nearbyY);
        this.spriteHudNearbyPanelBottom = (int)(nearbyY + nearbyH);
        this.spriteHudNearbyEnabled = (cNearby != null);
        this.spriteHudPartyEnabled = partyVisible && cNearby != null;
        this.spriteHudPartyX = (int)(nearbyX + 8);
        this.spriteHudPartyW = (int)(nearbyW - 16);
        this.spriteHudPartyY = (int)(partyY + 8);
    }

    /** Reposition a slot's Button to a sprite-HUD cell; bounds hold pos by reference so hits track it. */
    private void repositionSlotButton(Slots[] arr, int idx, float x, float y) {
        if (arr == null || idx < 0 || idx >= arr.length) return;
        final Slots slot = arr[idx];
        if (slot == null || slot.getButton() == null) return;
        // While dragging, the drag logic owns the button pos.
        if (slot.getDragPos() != null) return;
        slot.getButton().getPos().x = x;
        slot.getButton().getPos().y = y;
    }

    /** Potion oval: bottle sprite on the left, count on the right, keybind hint below. */
    private void drawPotionWidget(SpriteBatch batch, BitmapFont font,
                                   float x, float y, float w, float h, boolean hp) {
        if (this.playState == null || this.playState.getPlayer() == null) return;
        final Player p = this.playState.getPlayer();
        final int count = hp ? p.getHpPotions() : p.getMpPotions();
        final int itemId = hp ? HP_POTION_ITEM_ID : MP_POTION_ITEM_ID;

        final float iconSize = Math.min(h - 4, 18f);
        TextureRegion icon = GameSpriteManager.ITEM_SPRITES != null
                ? GameSpriteManager.ITEM_SPRITES.get(itemId) : null;
        if (icon == null) {
            final GameItem item = GameDataManager.GAME_ITEMS != null
                    ? GameDataManager.GAME_ITEMS.get(itemId) : null;
            if (item != null) {
                GameDataManager.loadSpriteModel(item);
                icon = GameSpriteManager.ITEM_SPRITES.get(itemId);
            }
        }
        if (icon != null) {
            drawIconOutlined(batch, icon, x + 4, y + (h - iconSize) / 2f, iconSize, iconSize);
        }

        font.setColor(Color.WHITE);
        final String label = String.valueOf(count);
        final GlyphLayout gl = new GlyphLayout(font, label);
        font.draw(batch, label, x + w - gl.width - 6, y + (h + gl.height) / 2f);

        final float origScale = font.getData().scaleX;
        font.getData().setScale(0.6f);
        final String keyHint = hp ? "(Z)" : "(X)";
        final GlyphLayout kgl = new GlyphLayout(font, keyHint);
        font.setColor(0.78f, 0.66f, 0.43f, 1f);
        font.draw(batch, keyHint, x + (w - kgl.width) / 2f, y + h + kgl.height + 1f);
        font.setColor(Color.WHITE);
        font.getData().setScale(origScale);
    }

    /** renderStats variant with explicit panel bounds, for the sprite-HUD stats rect. */
    private void renderStatsAt(SpriteBatch batch, BitmapFont font,
                                int startX, int panelWidth, int statsY, int panelHeight) {
        if (this.playState == null || this.playState.getPlayer() == null) return;
        final Player p = this.playState.getPlayer();
        final Stats computed = p.getComputedStats();
        final Stats base     = p.getStats();
        if (computed == null) return;
        final float origScale = font.getData().scaleX;
        font.getData().setScale(0.6f);
        final int rowH = 11;
        final int gridH = rowH * 3;
        final int colTextW = (int) (panelWidth * 0.44f);
        final int blockLeft = startX + Math.max(0, (panelWidth - colTextW * 2) / 2);
        final int textXcol0 = blockLeft;
        final int textXcol1 = blockLeft + colTextW;
        final int startY  = statsY + Math.max(2, (panelHeight - gridH) / 2) + 10;

        // Column-major: left col STR/DEF/SPD, right col DEX/VIT/WIS.
        final int[]    statMaxedIdx = {  3,    6,    4,    5,    2,    7  };
        final String[] statLabels   = { "STR", "DEF", "SPD", "DEX", "VIT", "WIS" };
        final int[] computedVals = { computed.getStr(), computed.getDef(),
                computed.getSpd(), computed.getDex(), computed.getVit(),
                computed.getWis() };
        final int[] baseVals = (base != null)
                ? new int[] { base.getStr(), base.getDef(), base.getSpd(),
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
                    font.setColor(0.25f, 0.78f, 0.25f, 1f);
                    font.draw(batch, "+" + bonus, bonusX, y);
                } else {
                    font.setColor(0.91f, 0.31f, 0.31f, 1f);
                    font.draw(batch, String.valueOf(bonus), bonusX, y);
                }
            }
        }
        font.setColor(Color.WHITE);
        font.getData().setScale(origScale);
    }

    /** One-off idle-front frame for the HUD avatar, built off the class sheet so
     *  in-world walk/attack animation doesn't move it. */
    private TextureRegion getHudIdleFrame(Player p) {
        if (p == null) return null;
        final int classId = p.getClassId();
        final AnimationModel anim =
                GameDataManager.getAnimation("player", classId);
        if (anim == null || anim.getAnimations() == null) {
            return (p.getSpriteSheet() != null) ? p.getSpriteSheet().getCurrentFrame() : null;
        }
        AnimationSetModel set = anim.getAnimations().get("idle_front");
        if (set == null || set.getFrames() == null || set.getFrames().isEmpty()) {
            set = anim.getAnimations().get("idle_side");
        }
        if (set == null || set.getFrames() == null || set.getFrames().isEmpty()) return null;
        final AnimationFrameModel f = set.getFrames().get(0);
        final int dyeId = p.getDyeId();
        // dyeId is in the cache key so a re-dye refreshes the avatar.
        final String key = classId + ":" + f.getRow() + ":" + f.getCol() + ":" + dyeId;
        TextureRegion cached = _hudIdleCache.get(key);
        if (cached != null) return cached;
        final int spW = anim.getSpriteSize();
        final int spH = anim.getEffectiveSpriteHeight();
        if (dyeId > 0) {
            final TextureRegion dyed = SpriteRecolorCache.getDyedRegion(
                    anim.getSpriteKey(), classId, f.getRow(), f.getCol(), spW, spH, spW, spH, dyeId);
            if (dyed != null) {
                _hudIdleCache.put(key, dyed);
                return dyed;
            }
        }
        final Texture tex = (GameSpriteManager.TEXTURE_CACHE != null)
                ? GameSpriteManager.TEXTURE_CACHE.get(anim.getSpriteKey()) : null;
        if (tex == null) return null;
        final TextureRegion region =
                new TextureRegion(
                        tex, f.getCol() * spW, f.getRow() * spH, spW, spH);
        // Match GameSpriteManager's flip convention for the y-down camera.
        region.flip(false, true);
        _hudIdleCache.put(key, region);
        return region;
    }

    /** Item at inventory index, or null for empty / out-of-range. */
    private GameItem getInventoryItem(int idx) {
        if (this.inventory == null || idx < 0 || idx >= this.inventory.length) return null;
        final Slots slot = this.inventory[idx];
        if (slot == null) return null;
        return slot.getItem();
    }

    /** Centered item icon; no-op when the cell is empty. 16px+ sprites scale to the cell, 8px stay small. */
    private void drawHudItemIcon(SpriteBatch batch, GameItem item,
                                  float x, float y, float w, float h) {
        if (item == null || item.getItemId() == -1) return;
        if (item.getSpriteKey() == null) {
            GameDataManager.loadSpriteModel(item);
        }
        TextureRegion icon = SpriteRecolorCache.getEnchantedItemRegion(item);
        if (icon == null) icon = GameSpriteManager.ITEM_SPRITES.get(item.getItemId());
        if (icon == null) return;
        final float iconSize = (icon.getRegionWidth() >= 16)
                ? Math.min(22f * UiAtlas.getDisplayScale(), Math.min(w, h) - 4f)
                : 8f * UiAtlas.getIconScale() * UiAtlas.getDisplayScale();
        batch.draw(icon, x + (w - iconSize) / 2f, y + (h - iconSize) / 2f, iconSize, iconSize);
    }

    /** Draw a HUD icon with a 1px black silhouette outline (8 offset copies). Leaves batch color WHITE. */
    private void drawIconOutlined(SpriteBatch batch, TextureRegion region, float x, float y, float w, float h) {
        final float o = 1f;
        batch.setColor(0f, 0f, 0f, 0.85f);
        batch.draw(region, x + o, y,     w, h);
        batch.draw(region, x - o, y,     w, h);
        batch.draw(region, x,     y + o, w, h);
        batch.draw(region, x,     y - o, w, h);
        batch.draw(region, x + o, y + o, w, h);
        batch.draw(region, x + o, y - o, w, h);
        batch.draw(region, x - o, y + o, w, h);
        batch.draw(region, x - o, y - o, w, h);
        batch.setColor(Color.WHITE);
        batch.draw(region, x, y, w, h);
    }

    /** Draw an ability icon via spriteKey/row/col, falling back to the slot number. */
    private void drawAbilityHudIcon(SpriteBatch batch, BitmapFont font, Ability ab,
                                     int number, float x, float y, float w, float h) {
        if (ab == null) return;
        final TextureRegion icon = GameSpriteManager.getAbilityIconRegion(ab);
        if (icon != null) {
            drawIconOutlined(batch, icon, x, y, w, h);
            return;
        }
        final String str = number > 0 ? String.valueOf(number) : "?";
        final float origScale = font.getData().scaleX;
        font.getData().setScale(1f);
        final GlyphLayout probe = new GlyphLayout(font, str);
        final float scale = probe.height > 0 ? (h * 0.72f / probe.height) : 1f;
        font.getData().setScale(scale);
        font.setColor(Color.valueOf("e8d8b8"));
        final GlyphLayout gl = new GlyphLayout(font, str);
        font.draw(batch, gl, x + (w - gl.width) * 0.5f, y + (h - gl.height) * 0.5f);
        font.getData().setScale(origScale);
        font.setColor(Color.WHITE);
    }

    /** Paint cooldown fill + SP pip column on top of each ability icon. */
    private void renderAbilityHotbarOverlays(ShapeRenderer shapes, Player localPlayer) {
        if (localPlayer == null || _lastHotbarCellPx == null) return;
        final long now = System.currentTimeMillis();
        final long[] cds = localPlayer.getAbilityCooldowns();
        // Start at cell 1 (cell 0 is the passive); bindingIdx = slot - 1.
        for (int slot = 1; slot < HOTBAR_SLOT_COUNT; slot++) {
            final float[] cell = _lastHotbarCellPx[slot];
            if (cell == null) continue;
            final float cx = cell[0], cy = cell[1], cw = cell[2], ch = cell[3];
            final int bindingIdx = slot - 1;
            final Ability ab = localPlayer.getActiveAbility(bindingIdx);
            if (ab == null) continue;
            if (cds != null && bindingIdx < cds.length && cds[bindingIdx] > now) {
                final long base = ab.getBaseCooldownMs();
                if (base > 0) {
                    final long remain = Math.min(cds[bindingIdx] - now, base);
                    final float frac = Math.max(0f, Math.min(1f, remain / (float) base));
                    shapes.setColor(0f, 0f, 0f, 0.62f);
                    shapes.rect(cx, cy + ch * (1f - frac), cw, ch * frac);
                }
            }
            // SP pip column along the cell's right edge: one pip per maxSkillPoints, filled to invested.
            final int maxSp = ab.getMaxSkillPoints() <= 0 ? 5 : ab.getMaxSkillPoints();
            final int invested = localPlayer.getSkillLevel(ab.getId());
            if (maxSp > 0) {
                final float pipW = 3.5f;
                final float pipH = Math.max(2.5f, ch / (maxSp + 1.5f));
                final float pipX = cx + cw - pipW - 2f;
                for (int p = 0; p < maxSp; p++) {
                    final float py = cy + 2f + p * (pipH + 1f);
                    if (p < invested) {
                        shapes.setColor(1.0f, 0.65f, 0.18f, 0.95f);
                    } else {
                        shapes.setColor(0.18f, 0.16f, 0.13f, 0.75f);
                    }
                    shapes.rect(pipX, py, pipW, pipH);
                }
            }
        }
    }
}
