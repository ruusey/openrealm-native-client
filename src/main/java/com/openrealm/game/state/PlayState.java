package com.openrealm.game.state;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.Matrix4;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.openrealm.account.dto.PlayerAccountDto;
import com.openrealm.game.OpenRealmGame;
import com.openrealm.game.Settings;
import com.badlogic.gdx.graphics.Color;
import com.openrealm.game.contants.CharacterClass;
import com.openrealm.game.contants.GlobalConstants;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.model.TileModel;
import com.openrealm.game.tile.Tile;
import com.openrealm.game.tile.TileManager;
import com.openrealm.game.tile.TileData;
import com.openrealm.game.tile.TileMap;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.data.GameSpriteManager;
import com.openrealm.game.entity.Bullet;
import com.openrealm.game.entity.Enemy;
import com.openrealm.game.entity.Entity;
import com.openrealm.game.entity.GameObject;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.Portal;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.LootContainer;
import com.openrealm.game.math.Rectangle;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.graphics.SpriteSheet;
import com.openrealm.game.model.PortalModel;
import com.openrealm.game.model.Projectile;
import com.openrealm.game.model.ProjectileGroup;
import com.openrealm.game.model.WeaponArchetypeModel;
import com.openrealm.game.model.AnimationModel;
import com.openrealm.game.model.AnimationSetModel;
import com.openrealm.game.model.AnimationFrameModel;
import com.openrealm.game.ui.ActiveVisualEffect;
import com.openrealm.game.ui.ChatBubble;
import com.openrealm.game.ui.Slots;
import com.openrealm.game.ui.EffectText;
import com.openrealm.game.ui.Minimap;
import com.openrealm.game.ui.PerfMetrics;
import com.openrealm.game.ui.PlayerUI;
import com.openrealm.net.client.packet.CreateEffectPacket;
import com.openrealm.net.client.ClientGameLogic;
import com.openrealm.net.client.SocketClient;
import com.openrealm.net.messaging.CommandType;
import com.openrealm.net.messaging.LoginRequestMessage;
import com.openrealm.net.entity.NetPlayerPosition;
import com.openrealm.net.entity.NetPartyMember;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerClient;
import com.openrealm.net.server.packet.CommandPacket;
import com.openrealm.net.server.packet.MoveItemPacket;
import com.openrealm.net.server.packet.PlayerMovePacket;
import com.openrealm.net.server.packet.PlayerShootPacket;
import com.openrealm.net.server.packet.UseAbilityPacket;
import com.openrealm.net.server.packet.InvestSkillPointPacket;
import com.openrealm.game.model.ability.Ability;
import com.openrealm.net.server.packet.LoginAckPacket;
import com.openrealm.game.contants.ProjectileFlag;
import com.openrealm.net.server.packet.InteractTilePacket;
import com.openrealm.net.server.packet.UsePortalPacket;
import com.openrealm.game.model.TileModel;
import com.openrealm.util.Camera;
import com.openrealm.util.Cardinality;
import com.openrealm.util.KeyHandler;
import com.openrealm.util.MouseHandler;
import com.openrealm.util.WorkerThread;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import com.badlogic.gdx.Input;
import com.openrealm.game.graphics.ShaderManager;
import com.openrealm.game.graphics.ProjectileFxManager;
import com.openrealm.game.graphics.Sprite;
import com.openrealm.game.graphics.AbilityEffectRenderer;
import static com.openrealm.game.graphics.AbilityEffectRenderer.drawCircle;
import static com.openrealm.game.graphics.AbilityEffectRenderer.drawCircleOutline;

@Data
@EqualsAndHashCode(callSuper = false)
@Slf4j
public class PlayState extends GameState {
    private static final String LOG_NS = "[CLIENT](play-state)";

    private static final long QUICK_USE_COOLDOWN_MS = 250;
    private static final long PORTAL_COOLDOWN_MS = 1000;
    private static final long CAST_RING_DURATION_MS = 700L;
    // De-render a remote peer once it passes this range from the local player —
    // the server's player load radius (viewport 10 + 5 tiles). Past it the
    // server stops sending its updates, so keeping it would freeze/extrapolate a
    // ghost that then teleports when a lagged update lands.
    private static final float REMOTE_DERENDER_PX = 15 * 32;
    private static final float REMOTE_DERENDER_PX_SQ = REMOTE_DERENDER_PX * REMOTE_DERENDER_PX;
    // Enemy name labels: when a realm holds more than this many enemies (a horde),
    // only enemies within NAME_HORDE_RADIUS of the local player get a name so we
    // don't pay hundreds of text draws. Mirrors webclient renderer.js.
    private static final int NAME_HORDE_THRESHOLD = 50;
    private static final float NAME_HORDE_RADIUS_SQ = (32f * 5f) * (32f * 5f);

    /** Cached chat-role nameplate colors. Mirrors webclient renderer.js
     *  GameRenderer.getNameColorHex. Static so we don't allocate a Color
     *  per name draw. */
    private static final Color ROLE_SYSADMIN = new Color(1.00f, 0.25f, 0.25f, 1f);
    private static final Color ROLE_ADMIN    = new Color(0.25f, 0.50f, 0.88f, 1f);
    private static final Color ROLE_MOD      = new Color(0.25f, 0.75f, 0.25f, 1f);
    private static final Color ROLE_EDITOR   = new Color(0.63f, 0.25f, 0.75f, 1f);
    private static final Color ROLE_DEMO     = new Color(0.80f, 0.80f, 0.80f, 1f);
    private static final Color ROLE_DEFAULT  = new Color(0.93f, 0.93f, 0.93f, 1f);
    // Enemy nameplate colour — webclient renderEnemy uses 0xff8080 (light red).
    private static final Color ENEMY_NAME_COLOR = new Color(1f, 0.5f, 0.5f, 1f);

    private RealmManagerClient realmManager;
    /** Server-wide players surfaced by GlobalPlayerPositionPacket — used
     *  ONLY by the minimap to plot dots for players who aren't in our
     *  local realm map. Webclient parity (game.minimapPlayers).
     *  Previously the global-pos handler was overwriting our local
     *  players' coords with these positions, which dragged the in-realm
     *  dots around as players in OTHER realms moved. */
    private NetPlayerPosition[] minimapPlayers = new NetPlayerPosition[0];
    private Queue<EffectText> damageText;
    private Queue<ActiveVisualEffect> activeEffects;
    // Lazily-built swing frames per weapon archetype (animations "effect:62",
    // set "swing_sword"/"_axe"/"_hammer"/"_dagger" or generic "swing"). Only
    // successful builds are cached so a not-yet-loaded sheet retries next frame.
    private final Map<String, TextureRegion[]> swingFrameCache = new HashMap<>();
    // Phase 4 — party state mirror of webclient game.partyId / partyMembers.
    // Latest snapshot from PartyUpdatePacket. partyId == 0 means "not in
    // a party"; the UI hides the panel in that case.
    private long partyId = 0L;
    private NetPartyMember[] partyMembers = new NetPartyMember[0];
    /** Active cast bars by playerId — set by AbilityCastStartPacket handler,
     *  rendered as a bottom→top fill overlay on each casting player's
     *  sprite. Auto-cleared by the renderer when the cast completes. */
    private final Map<Long, long[]> activeCasts = new ConcurrentHashMap<>();
    private List<Vector2f> shotDestQueue;
    private PlayerAccountDto account;
    private Camera cam;
    private PlayerUI pui;
    public static Vector2f map;
    public long lastShotTick = 0;
    public long lastAbilityTick = 0;
    private long lastQuickUseTick = 0;
    private long lastPortalTick = 0;
    /** Data-driven projectile FX particles (trails, muzzle/impact bursts). */
    private final ProjectileFxManager projectileFx = new ProjectileFxManager();
    public long playerId = -1l;
    /** Account-wide skill XP (PlayerSkill ordinal -> XP), synced by SkillsPacket. */
    private long[] skillXp = new long[9];
    /** Local player's privilege role (sysadmin/admin/mod/editor/demo), captured
     *  at login. STATIC so it survives a PlayState re-created on a realm
     *  transition, then re-applied to the local Player each frame so the name
     *  color holds — mirrors the webclient's module-level localChatRole. */
    private static String localChatRole = null;
    public void setLocalChatRole(String role) { PlayState.localChatRole = role; }

    private long lastSampleTime;
    private long frames;
    private long lastFrames;

    private Map<Cardinality, Boolean> lastDirectionMap;
    private boolean sentChat = false;
    private boolean debugMode = false;

    // Reusable per-frame visibility buffers. Previously these were
    // allocated fresh in render() (3 ArrayLists per frame, ~150-300
    // entries each on busy realms = 9-18K allocs/sec at 60 FPS). Held
    // as fields and cleared at start of render so the underlying
    // backing arrays are reused frame-to-frame. Initial capacity sized
    // for a typical viewport.
    private final List<Entity> visibleEntities = new ArrayList<>(256);
    private final List<Bullet> visibleBullets = new ArrayList<>(128);
    private final List<Enemy> visibleEnemies = new ArrayList<>(128);
    private final HashSet<Long> lockOnSeen = new HashSet<>();

    // Scratch Vector2f reused for collision-check center-offset queries in
    // movePlayer. Previously each call did p.getPos().clone(halfSize,
    // halfSize) — 4 fresh Vector2f per moved player per frame, ~3K/sec on
    // a busy realm. PlayState input/render run on the GL thread so a
    // single field is safe.
    private final Vector2f movePlayerScratch = new Vector2f();

    // Scratch GlyphLayout reused for nameplate measurement / centering in
    // the world-camera render pass. Without this each name draw allocated
    // a fresh GlyphLayout (one per visible player per frame).
    private final GlyphLayout nameLayoutScratch = new GlyphLayout();

    // Scratch GlyphLayout for chat-bubble measurement/centering, kept separate
    // from nameLayoutScratch so the nameplate layout stays intact while a
    // bubble is positioned relative to it.
    private final GlyphLayout chatBubbleLayoutScratch = new GlyphLayout();

    // Chat bubbles keyed by sender name, floated briefly above the player's
    // head. Written from the network thread (handleTextClient), read on the
    // render thread — hence the concurrent map.
    private final Map<String, ChatBubble> chatBubbles = new ConcurrentHashMap<>();

    /**
     * Server-reconciliation input buffer. Mirrors the webclient's
     * {@code _pendingInputs} array (game.js#handlePosAck): every client
     * sim-tick we predict the next pos locally AND push a {@link PendingInput}
     * record here, then on PlayerPosAckPacket we drop confirmed inputs,
     * snap to the server pos, and replay the rest. Bounded to 128 entries
     * (~2 s of inputs at 64 Hz) so a stuck connection can't grow it.
     *
     * Thread-safety: pushed from the GL/input thread, read+mutated under
     * {@link #reconcileLocalPlayerPos} which is {@code synchronized} on
     * PlayState so it can't race the input-loop drain.
     */
    private final ArrayDeque<PendingInput> pendingInputs = new ArrayDeque<>(128);

    /** Visual-only smoothing offset applied to the local player's render
     *  position when reconciliation finds a small mismatch (collision /
     *  slow-tile divergence). The logical pos is snapped to the replay
     *  result for accurate next-tick collisions, while the visual diff
     *  decays toward zero each frame so the user doesn't see a hop. */
    private float smoothingOffsetX = 0f;
    private float smoothingOffsetY = 0f;


    private long castRingExpiresAt = 0L;
    private float castRingCx, castRingCy, castRingRadius;

    /**
     * Set when the initial login send fails. The state's first update() tick
     * detects this and pops PlayState back to CharacterSelectState with the
     * message, so the user can edit the server host and retry.
     */
    private String connectError = null;

    /** Frame counter for periodic debug logging. */
    private long frameCounter = 0;
    /**
     * Accumulator (seconds) for the fixed-tick movement loop. Render frames
     * deposit dt here; whole 1/64-s ticks are drained off and applied at
     * the server's authoritative rate, so 144 FPS rendering doesn't cause
     * the client to predict 2.4× faster than the server simulates.
     */
    private float moveAccumulator = 0f;
    /**
     * Sub-tick interpolation state. Mirrors the web client's
     * {@code _interpFromX/_interpToX/_renderX} system in main.js. Visual
     * position lerps from the pre-tick to post-tick simulation positions
     * over each 1/64 s tick window, so 144 FPS rendering stays smooth even
     * though simulation is fixed at 64 Hz.
     */
    private float interpFromX, interpFromY;
    /**
     * Smoothed camera position. Mirrors the web client's
     * {@code game.cameraX/cameraY} (game.js ~line 1580). The camera
     * exponentially eases toward the player's lerped render position
     * each frame instead of being hard-locked to it. Hard-locking made
     * the camera feel sticky / laggy under fast input changes — every
     * direction flip jolted the world rather than letting the player
     * drift inside a small dead zone before the camera caught up.
     *
     * NaN sentinel = "no anchor yet, snap on first frame".
     */
    private float cameraX = Float.NaN;
    private float cameraY = Float.NaN;
    private float interpToX, interpToY;
    private boolean hasInterpAnchor = false;

    private final Matrix4 worldTransformIdt = new Matrix4();

    /** Labels MUST match webclient renderer.js STATUS_ICON_DEFS so a
     *  player can read the same chip text on either client. Suffix
     *  convention: '+' = buff modifier up, '-' = debuff modifier down. */
    private static final StatusEffectIconDef[] STATUS_ICON_DEFS = new StatusEffectIconDef[] {
        new StatusEffectIconDef(StatusEffectType.HEALING.effectId,      "Heal",   0xFF4444),
        new StatusEffectIconDef(StatusEffectType.SPEEDY.effectId,       "Spd+",   0x44FF44),
        new StatusEffectIconDef(StatusEffectType.BERSERK.effectId,      "Aspd+",  0xFF6644),
        new StatusEffectIconDef(StatusEffectType.DAMAGING.effectId,     "Atk+",   0xFFAA44),
        new StatusEffectIconDef(StatusEffectType.ARMORED.effectId,      "Armr+",  0x6688CC),
        new StatusEffectIconDef(StatusEffectType.INVINCIBLE.effectId,   "Invuln", 0x44AAFF),
        new StatusEffectIconDef(StatusEffectType.HIDDEN.effectId,    "Hidden",   0xCCBB88),
        new StatusEffectIconDef(StatusEffectType.SLOWED.effectId,       "Slow",   0x6688FF),
        new StatusEffectIconDef(StatusEffectType.PARALYZED.effectId,    "Para",   0x888888),
        new StatusEffectIconDef(StatusEffectType.STUNNED.effectId,      "Stun",   0x88CCFF),
        new StatusEffectIconDef(StatusEffectType.STASIS.effectId,       "Stasis", 0x444448),
        new StatusEffectIconDef(StatusEffectType.DAZED.effectId,        "Daze",   0x9988AA),
        new StatusEffectIconDef(StatusEffectType.POISONED.effectId,     "Pois",   0x40CC40),
        new StatusEffectIconDef(StatusEffectType.CURSED.effectId,       "Curse",  0xAA2255),
        new StatusEffectIconDef(StatusEffectType.ARMOR_BROKEN.effectId, "Armr-",  0x7060CC),
        // Phase 3 — class kit statuses added during the combat rework.
        new StatusEffectIconDef(StatusEffectType.TAUNT_TARGET.effectId, "Taunt",  0xC8201F),
        new StatusEffectIconDef(StatusEffectType.BRACED.effectId,       "Def+",   0x88AACC),
        new StatusEffectIconDef(StatusEffectType.PROTECTED.effectId,    "Vit+",   0xFFE070),
        new StatusEffectIconDef(StatusEffectType.PHALANX_DOME.effectId, "Dome",   0x6CCCFF),
        // Phase 3 (post-rework) expanded debuff palette.
        new StatusEffectIconDef(StatusEffectType.WEAKEN.effectId,       "Atk-",   0x8A5A30),
        new StatusEffectIconDef(StatusEffectType.BLIND.effectId,        "Blind",  0x1A1A1A),
        new StatusEffectIconDef(StatusEffectType.WARDED.effectId,       "Ward",   0xC8C0FF),
        new StatusEffectIconDef(StatusEffectType.MANA_FOUNT.effectId,   "MP+",    0x4080FF),
        new StatusEffectIconDef(StatusEffectType.VULNERABLE.effectId,   "Vuln",   0xCC4080),
        new StatusEffectIconDef(StatusEffectType.GROUNDED.effectId,     "Grnd",   0x806040),
        new StatusEffectIconDef(StatusEffectType.MARKED_FOR_LOOT.effectId, "Mark", 0xFFD840),
        // Heavy Buffer "Guiding Light" aura — split into two icons so STR
        // and DEX each show as their own pip above the player's head.
        new StatusEffectIconDef(StatusEffectType.EMPOWERED_STR.effectId, "Atk+",  0xFFAA44),
        new StatusEffectIconDef(StatusEffectType.EMPOWERED_DEX.effectId, "Dex+",  0xFFD060),
    };

    /**
     * Cached shuriken texture regions, one per tier 0..5. Indexed by tier
     * (col = 10 + tier on row 16 of openrealm-items.png). Lazily filled the
     * first time a blade-orbit/blender effect renders. Frames are flipped
     * once at load to match LibGDX's bottom-left origin convention; the
     * SpriteBatch.draw calls below pass the un-flipped TextureRegion and
     * rely on this baked-in orientation.
     */
    private TextureRegion[] _shurikenRegions;

    public PlayState(GameStateManager gsm, Camera cam) {
        super(gsm);
        PlayState.map = new Vector2f();
        Vector2f.setWorldVar(PlayState.map.x, PlayState.map.y);
        this.cam = cam;
        this.realmManager = new RealmManagerClient(this, new Realm(false, 2));
        this.shotDestQueue = new ArrayList<>();
        this.damageText = new ConcurrentLinkedQueue<>();
        this.activeEffects = new ConcurrentLinkedQueue<>();
        try {
            this.doLogin();
        } catch (Exception e) {
            // Connection refused / unreachable host / etc. — don't kill the
            // whole client; log it and bounce back to character-select so the
            // user can change the game-server host and try again.
            log.error("{} failed to send initial LoginRequest, returning to character select: {}",
                    LOG_NS, e.getMessage());
            this.connectError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return;
        }
        WorkerThread.submitAndForkRun(this.realmManager);
    }

    public String getConnectError() { return this.connectError; }

    public void loadClass(Player player, CharacterClass cls, boolean setEquipment) {
        if (setEquipment || (this.playerId == -1l)) {
            player.equipSlots(GameDataManager.getStartingEquipment(cls));
        } else {
            final GameItem[] existing = this.getPlayer().getInventory();
            player.setInventory(existing);
        }
        this.cam.target(player);

        if ((this.playerId != -1) || (this.realmManager.getRealm().getPlayer(this.playerId) != null)) {
            this.realmManager.getRealm().removePlayer(this.playerId);
        }
        this.playerId = this.realmManager.getRealm().addPlayer(player);
        this.realmManager.setCurrentPlayerId(this.playerId);
        this.pui = new PlayerUI(this);

        this.getPui().setEquipment(player.getInventory());
    }

    public long getPlayerId() {
        return this.playerId;
    }

    public Vector2f getPlayerPos() {
        return this.realmManager.getRealm().getPlayers().get(this.playerId).getPos();
    }
    
    public void doLogin() throws Exception {
        // Mirror webclient main.js (~line 1548): when an existing session
        // token is available, send THAT and let the game server resolve it
        // via /admin/account/token/resolve — same as the webclient's
        // network.sendLogin(uuid, '', '', token) path. The previous code
        // always sent email/password from SocketClient.PLAYER_EMAIL/PASSWORD
        // which are null when the user came in via the auto-login token
        // path (form login was skipped). Server then logged
        //   LoginRequestMessage(email=null, password=null, token=null)
        // and /admin/account/login returned 400 ("originalPassword is null").
        final String sessionToken = ClientGameLogic.DATA_SERVICE.getSessionToken();
        final LoginRequestMessage.LoginRequestMessageBuilder builder =
                LoginRequestMessage.builder().characterUuid(SocketClient.CHARACTER_UUID);
        if (sessionToken != null && !sessionToken.isEmpty()) {
            builder.token(sessionToken);
        } else {
            builder.email(SocketClient.PLAYER_EMAIL).password(SocketClient.PLAYER_PASSWORD);
        }
        final LoginRequestMessage login = builder.build();
        final CommandPacket loginPacket = CommandPacket.from(CommandType.LOGIN_REQUEST, login);
        this.realmManager.getClient().sendRemote(loginPacket);
    }

    /** Register a chat line to float briefly above the sender's head. Keyed by
     *  name so a new message replaces the previous bubble. Cosmetic — the same
     *  text is also shown in the chat bar via PlayerChat. */
    public void addChatBubble(String name, String message) {
        if (name == null || name.isEmpty() || message == null || message.isEmpty()) return;
        final String clipped = message.length() > 80 ? message.substring(0, 79) + "..." : message;
        final long now = System.currentTimeMillis();
        final long life = 3500L + Math.min(2500L, clipped.length() * 40L);
        this.chatBubbles.put(name, new ChatBubble(clipped, now, life));
    }

    /** Filled rounded rectangle — libGDX's ShapeRenderer has no rounded-rect
     *  primitive, so compose one from a cross of two rects plus four corner
     *  discs. Caller sets the color and must be inside a Filled shapes pass. */
    private void drawRoundedRect(ShapeRenderer shapes, float x, float y, float w, float h, float r) {
        r = Math.min(r, Math.min(w, h) * 0.5f);
        shapes.rect(x + r, y, w - 2 * r, h);          // center column (full height)
        shapes.rect(x, y + r, r, h - 2 * r);          // left edge
        shapes.rect(x + w - r, y + r, r, h - 2 * r);  // right edge
        shapes.circle(x + r, y + r, r);               // bottom-left corner
        shapes.circle(x + w - r, y + r, r);           // bottom-right corner
        shapes.circle(x + r, y + h - r, r);           // top-left corner
        shapes.circle(x + w - r, y + h - r, r);       // top-right corner
    }

    @Override
    public void update(double time) {
        // Bail out cleanly if the constructor couldn't reach the game server.
        // CharacterSelectState's transition handler watches for this and pops
        // PlayState back to CHARSELECT so the user isn't stranded on a black
        // screen with a broken realm.
        if (this.connectError != null) return;

        final Player player = this.realmManager.getRealm().getPlayer(this.realmManager.getCurrentPlayerId());

        if (player == null)
            return;
        if (!this.gsm.isStateActive(GameStateManager.PAUSE)) {
            // Process all client-side updates inline — these are fast and
            // pool dispatch overhead exceeds the work itself
            final Realm clientRealm = this.realmManager.getRealm();
            final GameObject[] gameObject = clientRealm.getAllGameObjects();
            // Precompute bulletScale once per frame so we don't pay a
            // System.nanoTime() syscall + division per bullet (was up to
            // ~12K syscalls/sec at 200 in-flight bullets). Bullet.update()
            // no-arg still works for legacy callers but the parametric form
            // is the hot path. dt clamped to 1/30 to match the rest of the
            // simulation's frame-skip cap.
            final float bulletDt = Gdx.graphics != null
                    ? Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f)
                    : 1f / 60f;
            final float bulletScale = bulletDt * 64f;
            // Bullets that need to be evicted from the realm map this tick.
            // We evict for: range exhaustion, the 10s wall-clock safety cap,
            // and terrain collision (walls / non-void tiles, mirrors the
            // server's processTerrainHit). Without local culling, predicted
            // bullets — whose client-random IDs don't match the server's
            // UnloadPacket — accumulate forever; non-predicted bullets also
            // visibly fly through walls until the server's UnloadPacket
            // round-trip lands.
            List<Long> bulletsToCull = null;
            List<Long> playersToDerender = null;
            final TileManager tm =
                    this.realmManager.getRealm().getTileManager();
            for (int i = 0; i < gameObject.length; i++) {
                if (gameObject[i] instanceof Enemy) {
                    ((Enemy) gameObject[i]).update(this.getRealmManager(), time);
                } else if (gameObject[i] instanceof Bullet) {
                    final Bullet bul = (Bullet) gameObject[i];
                    if (bul.hasFlag(ProjectileFlag.HOMING)) {
                        GameObject tgt = clientRealm.getPlayer(bul.getTargetEntityId());
                        if (tgt == null && player != null && player.getId() == bul.getTargetEntityId()) tgt = player;
                        if (tgt == null) tgt = clientRealm.getEnemies().get(bul.getTargetEntityId());
                        // Advance the seeker in fixed 1/64s ticks (steer then move,
                        // once per tick) to match the server's discretization exactly.
                        // Per-frame variable-bulletScale stepping drew a slightly
                        // different pursuit curve, so the path drifted between snapshots
                        // and the server's position sync visibly snapped/flipped.
                        final float maxTurn = (float) Math.toRadians(bul.getFrequency());
                        float accum = bul.getHomingAccum() + bulletScale;
                        int guard = 0;
                        while (accum >= 1f && guard++ < 16) {
                            accum -= 1f;
                            if (tgt != null) {
                                bul.steerToward(tgt.getPos().x + tgt.getSize() * 0.5f,
                                        tgt.getPos().y + tgt.getSize() * 0.5f, maxTurn);
                            }
                            bul.update(1f);
                        }
                        bul.setHomingAccum(accum);
                    } else {
                        bul.update(bulletScale);
                    }
                    // ANCHORED walls follow their source entity as it moves.
                    if (bul.hasFlag(ProjectileFlag.ANCHORED)) {
                        final Enemy src = clientRealm.getEnemies().get(bul.getSrcEntityId());
                        if (src != null) bul.anchorFollow(src.getPos().x, src.getPos().y);
                    }
                    boolean expired = bul.remove(0L);
                    // Terrain collision: skip for pass-through projectiles.
                    // Use bullet center; isCollisionTile returns true for OOB
                    // which is fine — bullets off the map should die anyway.
                    if (!expired
                            && !bul.hasFlag(ProjectileFlag.PASS_THROUGH_TERRAIN)
                            && bul.getPos() != null
                            && tm != null) {
                        final float half = bul.getSize() * 0.5f;
                        // Thread-local reuse — was `pos.clone(half, half)`
                        // which allocated per-bullet per-frame.
                        final Vector2f center = bul.getPos().centerOffset(half, half);
                        if (tm.isCollisionTile(center)) {
                            expired = true;
                        }
                    }
                    if (expired) {
                        if (bulletsToCull == null) bulletsToCull = new ArrayList<>(8);
                        bulletsToCull.add(bul.getId());
                    }
                } else if (gameObject[i] instanceof Player && gameObject[i].getId() != player.getId()) {
                    final Player playerOther = (Player) gameObject[i];
                    final float localHalf = (player.getSize() > 0 ? player.getSize() : 32) * 0.5f;
                    final float refX = player.getPos().x + localHalf;
                    final float refY = player.getPos().y + localHalf;
                    // De-render a peer the instant it leaves render range rather
                    // than freezing/extrapolating a ghost that teleports when a
                    // lagged update lands. Center-to-center to match the server.
                    final float otherHalf = (playerOther.getSize() > 0 ? playerOther.getSize() : 32) * 0.5f;
                    final float ddx = (playerOther.getPos().x + otherHalf) - refX;
                    final float ddy = (playerOther.getPos().y + otherHalf) - refY;
                    if (ddx * ddx + ddy * ddy > REMOTE_DERENDER_PX_SQ) {
                        if (playersToDerender == null) playersToDerender = new ArrayList<>(4);
                        playersToDerender.add(playerOther.getId());
                        continue;
                    }
                    playerOther.update(time);
                    // Mirror webclient's per-frame extrapolation for remote
                    // entities: lerp pos toward targetX/Y at the velocity
                    // the server reported. Without this we'd just sit at
                    // the LoadPacket spawn pos forever (movePlayer would
                    // burn CPU on dx/dy without smoothing toward the
                    // authoritative target).
                    playerOther.extrapolate(refX, refY, true);
                }
            }

            if (bulletsToCull != null) {
                final Map<Long, Bullet> bulletMap = clientRealm.getBullets();
                for (final Long bid : bulletsToCull) {
                    bulletMap.remove(bid);
                }
            }

            if (playersToDerender != null) {
                for (final Long pid : playersToDerender) {
                    this.realmManager.derenderRemotePlayer(pid);
                }
            }

            // Client-side player-bullet hit prediction — mirrors webclient
            // game.js around line 1499. Without this the bullet sprite
            // visually flies through the enemy until the server's UnloadPacket
            // arrives a frame or two later, which reads as "projectiles pass
            // through enemies after a hit". Server stays authoritative for
            // damage; this is purely a visual cull.
            //
            // Mark-consumed pattern (NOT remove): set Bullet.consumedClient
            // and let the render path skip the sprite. Removing the bullet
            // from the realm map was fighting the server's LoadPacket diff,
            // which keeps re-adding the bullet at its slightly-stale
            // server-side position until the kill packet lands — that
            // produced visibly "frozen" projectiles after a hit.
            //
            // Circle-vs-circle test using GlobalConstants.HIT_RADIUS_FACTOR
            // so hit radius matches the server's circleHit() exactly.
            // Pass-through-enemies projectiles skip the cull and keep flying.
            final Map<Long, Bullet> bullets = clientRealm.getBullets();
            final Map<Long, Enemy>  enemies = clientRealm.getEnemies();
            final long localId = this.playerId;
            if (!bullets.isEmpty() && !enemies.isEmpty()) {
                for (final Bullet b : bullets.values()) {
                    if (b == null || b.getPos() == null) continue;
                    if (b.isConsumedClient()) continue;
                    // Was filtering only PLAYER_PROJECTILE-flagged bullets,
                    // which dropped many weapon projectiles whose data ships
                    // with flags: [] (daggers etc). Those weren't culled
                    // visually on enemy contact and pierced through until
                    // the server's UnloadPacket landed a tick later. Now
                    // also accept any bullet whose srcEntityId is the
                    // local player — predicted bullets carry that, and
                    // findMatchingPredictedBullet preserves it through the
                    // dedup ID adoption — so a non-flagged dagger from
                    // the local player still hit-culls correctly.
                    final boolean isOwnBullet = (localId != -1L && b.getSrcEntityId() == localId);
                    if (!b.hasFlag(ProjectileFlag.PLAYER_PROJECTILE) && !isOwnBullet) continue;
                    if (b.hasFlag(ProjectileFlag.PASS_THROUGH_ENEMIES)) continue;
                    // Homing bullets are server-driven — never client-consume them.
                    // The server removes a homing bullet on its authoritative hit
                    // (UnloadPacket); a predicted local cull deleted our copy before
                    // the server killed its copy, which got re-sent and orbited.
                    if (b.hasFlag(ProjectileFlag.HOMING)) continue;
                    final float bSize = b.getSize() > 0 ? b.getSize() : 4f;
                    final float br = bSize * GlobalConstants.HIT_RADIUS_FACTOR;
                    final float bcx = b.getPos().x + bSize * 0.5f;
                    final float bcy = b.getPos().y + bSize * 0.5f;
                    for (final Enemy e : enemies.values()) {
                        if (e == null || e.getPos() == null) continue;
                        final float eSize = e.getSize() > 0 ? e.getSize() : 32f;
                        final float er = eSize * GlobalConstants.HIT_RADIUS_FACTOR;
                        final float ecx = e.getPos().x + eSize * 0.5f;
                        final float ecy = e.getPos().y + eSize * 0.5f;
                        final float dx = bcx - ecx;
                        final float dy = bcy - ecy;
                        final float rsum = br + er;
                        if (dx > rsum || dx < -rsum || dy > rsum || dy < -rsum) continue;
                        if (dx * dx + dy * dy < rsum * rsum) {
                            b.setConsumedClient(true);
                            break;
                        }
                    }
                }
            }

            while (!this.shotDestQueue.isEmpty()) {
                final Vector2f dest = this.shotDestQueue.remove(0);
                final Vector2f source = this.getPlayer().getCenteredPosition();
                if (this.realmManager.getRealm().getTileManager().isCollisionTile(source)) {
                    continue;
                }
                try {
                    PlayerShootPacket packet = PlayerShootPacket.from(Realm.RANDOM.nextLong(), player, dest);
                    this.realmManager.getClient().sendRemote(packet);
                    this.spawnPredictedBullets(player, source, dest);
                } catch (Exception e) {
                    PlayState.log.error("{} failed to build player shoot packet: {}", LOG_NS, e.getMessage());
                }
            }

            // Animate the local player's sprite (walk/idle frames). All
            // simulation, position update, and renderX/camera computation
            // for the local player happens in input() now — the web client
            // pattern of doing the entire processInput pipeline in one
            // function. Splitting it produces visible lurch.
            player.update(time);

            if (this.pui != null) {
                this.pui.update(time);
            }

            // Iterator-remove avoids the per-frame `new ArrayList<>` for
            // toRemove AND the O(n*m) cost of ConcurrentLinkedQueue.removeAll
            // (n=queue size, m=ArrayList.contains lookup per element). The
            // queue's iterator is weakly consistent and supports remove(),
            // which is what we want here — we're the only mutator on the
            // render thread, and concurrent producers (network thread
            // adding damage text) don't conflict with the iterator.
            for (Iterator<EffectText> it = this.damageText.iterator(); it.hasNext(); ) {
                final EffectText text = it.next();
                text.update();
                if (text.getRemove()) {
                    it.remove();
                }
            }

            final float deltaMs = (float) (time * 1000.0);
            for (Iterator<ActiveVisualEffect> it = this.activeEffects.iterator(); it.hasNext(); ) {
                final ActiveVisualEffect vfx = it.next();
                vfx.update(deltaMs);
                if (vfx.getRemove()) {
                    it.remove();
                }
            }

            this.cam.target(player);
            this.cam.update();
            for (final LootContainer lc : this.realmManager.getRealm().getLoot().values()) {
                lc.setContentsChanged(false);
            }
        }
        this.frames++;
        if((Instant.now().toEpochMilli()-lastSampleTime)>=1000) {
        	this.lastFrames = frames;
            this.lastSampleTime = Instant.now().toEpochMilli();
            this.frames=0;
        }
    }

    private void movePlayer(Player p) {
        // PARALYZED check moved out — live-tick caller now applies it
        // before invoking this method, and the reconcile-replay loop
        // filters paralyzed-at-send-time inputs via PendingInput.paralyzed.
        // Reuse a single scratch vector for the center-offset queries
        // instead of pos.clone(...) — those clone calls were the largest
        // single allocation source on the per-frame other-player movement
        // path (~3K Vector2f / sec on a populated realm).
        final Vector2f scratch = this.movePlayerScratch;
        final float halfSize = p.getSize() / 2f;
        scratch.x = p.getPos().x + halfSize;
        scratch.y = p.getPos().y + halfSize;
        final TileManager tm = this.getRealmManager().getRealm().getTileManager();
        // The first movement input can arrive before the initial LoadMapPacket
        // populates the tile layers; skip the frame so collision queries don't
        // index an empty collision layer.
        if (!tm.isMapLoaded()) return;
        // Webclient parity (game.js simulateTick): apply the slow-tile divisor
        // to the delta FIRST, then run every collision query and commit against
        // that same reduced delta. Checking the full delta but committing a
        // divided one made the client refuse moves the server allowed, which
        // desynced prediction from reconciliation and bounced the player off
        // walls bordering water/lava.
        final float slow = tm.collidesSlowTile(p) ? 3.0f : 1.0f;
        final float dx = p.getDx() / slow;
        final float dy = p.getDy() / slow;

        // On disconnect the server stops streaming tiles, so confine the player
        // to the already-loaded area by treating never-streamed (null) tiles as
        // solid. While connected this stays off so normal streaming is unchanged.
        final boolean disconnected = this.realmManager.isDisconnected();

        boolean xBlocked = tm.collisionTile(p, dx, 0)
                || tm.collidesXLimit(p, dx)
                || tm.isVoidTile(scratch, dx, 0)
                || (disconnected && tm.isUnloadedTile(scratch, dx, 0));
        boolean yBlocked = tm.collisionTile(p, 0, dy)
                || tm.collidesYLimit(p, dy)
                || tm.isVoidTile(scratch, 0, dy)
                || (disconnected && tm.isUnloadedTile(scratch, 0, dy));

        // Diagonal corner-cutting prevention: when neither axis is blocked but
        // the diagonal IS, block the smaller-|delta| axis so the player slides
        // along the larger one instead of clipping the corner.
        if (!xBlocked && !yBlocked && dx != 0f && dy != 0f) {
            if (tm.collisionTile(p, dx, dy) || tm.isVoidTile(scratch, dx, dy)
                    || (disconnected && tm.isUnloadedTile(scratch, dx, dy))) {
                if (Math.abs(dx) >= Math.abs(dy)) yBlocked = true;
                else xBlocked = true;
            }
        }

        if (!xBlocked) {
            p.xCol = false;
            if (dx != 0f) p.getPos().x += dx;
        } else {
            p.xCol = true;
        }

        // Refresh scratch after the X-axis update — pos may have moved.
        scratch.x = p.getPos().x + halfSize;
        scratch.y = p.getPos().y + halfSize;
        if (!yBlocked) {
            p.yCol = false;
            if (dy != 0f) p.getPos().y += dy;
        } else {
            p.yCol = true;
        }
    }

    /**
     * Server-reconciliation entry point — call from the network thread on
     * PlayerPosAckPacket arrival. Mirrors the webclient's
     * {@code Game.handlePosAck} (game.js#840):
     *
     * <ol>
     *   <li>Drop all pending inputs whose seq ≤ the acked seq (the server
     *       has confirmed those).</li>
     *   <li>Save the current locally-predicted pos.</li>
     *   <li>Snap pos to the server's authoritative pos at acked seq.</li>
     *   <li>Replay the remaining pending inputs through {@link #movePlayer}
     *       so we reproduce the same collision-aware physics the original
     *       prediction did.</li>
     *   <li>Compare the replayed pos to the saved pos:
     *     <ul>
     *       <li>err > 64 px : hard teleport (realm transition / kick)</li>
     *       <li>err > 2 px  : keep the replayed pos as logical (correct
     *           collisions next tick), absorb the visual diff into a
     *           smoothing offset that decays over ~50 ms</li>
     *       <li>err ≤ 2 px  : agree — revert to the saved pos so there's
     *           zero visible change (the common case at any ping when
     *           client + server physics line up)</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * Without this, every PlayerPosAck hard-snapped pos to a position
     * that was {@code (latency × speed)} pixels behind the predicted
     * state, producing the visible rubber-banding the user reported on
     * high-latency clients.
     *
     * Synchronized so it can't race the input loop's pending-input drain.
     */
    public synchronized void reconcileLocalPlayerPos(int ackSeq, float ackPosX, float ackPosY) {
        final Player local = this.realmManager.getRealm().getPlayer(this.playerId);
        if (local == null || local.getPos() == null) return;

        // Step 1: drop confirmed inputs.
        synchronized (this.pendingInputs) {
            while (!this.pendingInputs.isEmpty() && this.pendingInputs.peekFirst().seq <= ackSeq) {
                this.pendingInputs.pollFirst();
            }
        }

        // Step 2: save the predicted pos.
        final float savedX = local.getPos().x;
        final float savedY = local.getPos().y;

        // Step 3: snap to server-authoritative pos.
        local.getPos().x = ackPosX;
        local.getPos().y = ackPosY;

        // Step 4: replay remaining unacked inputs. Per-input paralyzed
        // snapshot wins over the player's CURRENT paralyzed state — an
        // input sent BEFORE paralyze landed still moves; one sent DURING
        // paralyze stays frozen. Without this snapshot the replay reads
        // current state for every iteration, mis-zeroing pre-paralyze
        // inputs (or mis-moving during-paralyze inputs) and diverging
        // from the server's actual per-tick decisions.
        synchronized (this.pendingInputs) {
            for (final PendingInput input : this.pendingInputs) {
                // OR the per-input snapshot with the CURRENT effect state so an
                // effect that landed mid-flight (between send and ack) is applied
                // to the still-unacked inputs the server is about to process the
                // same way. Matches webclient game.js simulateTick.
                if (local.hasEffect(StatusEffectType.PARALYZED) || input.paralyzed) continue;
                final boolean slowed = local.hasEffect(StatusEffectType.SLOWED) || input.slowed;
                final float step = input.basePxPerTick * (slowed ? 0.5f : 1.0f);
                local.setDx(input.vx * step);
                local.setDy(input.vy * step);
                this.movePlayer(local);
            }
        }

        // Step 5: classify the prediction error.
        final float replayX = local.getPos().x;
        final float replayY = local.getPos().y;
        final float errX = replayX - savedX;
        final float errY = replayY - savedY;
        final float errSq = errX * errX + errY * errY;

        if (errSq > 64f * 64f) {
            // Hard teleport — keep replayed pos, drop any pending smoothing
            // offset so the visual jumps with the logical pos.
            this.smoothingOffsetX = 0f;
            this.smoothingOffsetY = 0f;
        } else if (errSq > 4f /* 2 px */) {
            // Genuine mismatch (collision / slow-tile divergence). Logical
            // pos stays at the replayed result so the next tick's collision
            // checks are correct, but the visible diff is absorbed into a
            // smoothing offset that the render path decays out over ~50 ms.
            //
            // SET, don't ACCUMULATE: previously this was
            //     smoothingOffsetX += (saved - replay)
            // clamped at 6 px. At 30+ acks/sec each contributing ~0.5-2 px,
            // the offset rode the 6 px cap continuously, producing a
            // constant sticky-jitter feel during rapid input. Webclient
            // parity: setting it to the current divergence lets the
            // existing decay actually win between acks instead of fighting
            // an accumulating new addition.
            final float dx = savedX - replayX;
            final float dy = savedY - replayY;
            final float dmagSq = dx * dx + dy * dy;
            final float CAP = 6f;
            if (dmagSq > CAP * CAP) {
                final float scale = CAP / (float) Math.sqrt(dmagSq);
                this.smoothingOffsetX = dx * scale;
                this.smoothingOffsetY = dy * scale;
            } else {
                this.smoothingOffsetX = dx;
                this.smoothingOffsetY = dy;
            }
        } else {
            // Under 2 px: still adopt the REPLAY result, not the saved
            // prediction. Even tiny per-ack diffs (0.5-2 px from float
            // rounding, slow-tile edges, status-effect timing) compound
            // across every ack; clinging to savedX/Y let the client drift
            // ~5-10 px per minute until it eventually crossed the 2 px
            // threshold mid-game and visibly snapped, then resumed
            // drifting. Always trusting the replay keeps the client
            // anchored to the server's authoritative position — under
            // identical physics on both sides replay essentially equals
            // saved within float noise, so there's no visible jerk.
            local.getPos().x = replayX;
            local.getPos().y = replayY;
        }

        local.setLastProcessedInputSeq(ackSeq);
    }

    /** Drop any pending inputs queued for reconciliation. Called on realm
     *  transitions / character swap so a stale buffer can't replay through
     *  a fresh map. */
    public synchronized void clearPendingInputs() {
        synchronized (this.pendingInputs) {
            this.pendingInputs.clear();
        }
        this.smoothingOffsetX = 0f;
        this.smoothingOffsetY = 0f;
    }

    public synchronized void addProjectile(int projectileGroupId, int projectileId, Vector2f src, Vector2f dest, short size, float magnitude,
            float range, short damage, boolean isEnemy, List<Short> flags) {
        Player player = this.realmManager.getRealm().getPlayer(this.playerId);
        if (player == null)
            return;

        if (!isEnemy) {
            damage = (short) (damage + player.getStats().getStr());
        }
        Bullet b = new Bullet(Realm.RANDOM.nextLong(), projectileId, src, dest, size, magnitude, range, damage, isEnemy);
        b.setFlags(flags);
        this.realmManager.getRealm().addBullet(b);
    }

    public synchronized long addProjectile(int projectileGroupId, int projectileId, Vector2f src, float angle, short size, float magnitude,
            float range, short damage, boolean isEnemy, List<Short> flags, short amplitude, short frequency) {
        Player player = this.realmManager.getRealm().getPlayer(this.playerId);
        if (player == null)
            return -1;

        if (!isEnemy) {
            damage = (short) (damage + player.getStats().getStr());
        }
        Bullet b = new Bullet(Realm.RANDOM.nextLong(), projectileId, src, angle, size, magnitude, range, damage, isEnemy);
        b.setAmplitude(amplitude);
        b.setFrequency(frequency);
        b.setFlags(flags);
        return this.realmManager.getRealm().addBullet(b);
    }

    // WHY: Without local prediction the firing player sees their own
    // projectile stream gap whenever a LoadPacket is delayed (jitter, GC
    // hitch, packet loss) — other observers stay smooth because the
    // server's continuous broadcast is unaffected. Mirrors webclient
    // main.js ~2215 (negative-id predicted bullets) + game.js ~770
    // (server-bullet dedup) so the predicted sprite is the one that
    // renders end-to-end with zero perceived latency.
    private void spawnPredictedBullets(Player player, Vector2f source, Vector2f dest) {
        if (player == null || player.getInventory() == null) return;
        final GameItem weapon = player.getSlot(0);
        if (weapon == null || weapon.getDamage() == null) return;
        final int projGroupId = weapon.getDamage().getProjectileGroupId();
        if (GameDataManager.PROJECTILE_GROUPS == null) return;
        final ProjectileGroup group = GameDataManager.PROJECTILE_GROUPS.get(projGroupId);
        if (group == null || group.getProjectiles() == null) return;

        final float baseAngle = Bullet.getAngle(source, dest);
        final SpriteSheet sheet = GameSpriteManager.getSpriteSheet(group);
        final short atkBonus = (short) player.getStats().getStr();
        final Realm realm = this.realmManager.getRealm();

        // Server's symmetric multishot fan — mirror exactly or predicted
        // bullets dedup poorly against the authoritative spawn and the player
        // sees ghosts. Sources of bullets per shot:
        //   archetype.projectileCount (built-in fan)
        //   MultishotGem (gemstoneType=3) → +1 extra (1 base + 1 = 2 fanned, no center)
        // Spread / range / piercing also pulled from the archetype so a
        // pierce-archetype bow's predicted bullet carries PASS_THROUGH_ENEMIES
        // and matches the server bullet's flag set.
        final WeaponArchetypeModel _archShot =
                (weapon == null || weapon.getArchetypeId() <= 0 || GameDataManager.WEAPON_ARCHETYPES == null)
                        ? null
                        : GameDataManager.WEAPON_ARCHETYPES.get(weapon.getArchetypeId());
        // Melee swings are invisible server-side AoEs — no travelling projectile to
        // predict. The swing animation is triggered at the firing site regardless.
        if (_archShot != null && _archShot.isMelee()) return;
        final int archCount  = (_archShot != null && _archShot.getProjectileCount() > 0)
                ? _archShot.getProjectileCount() : 1;
        final int gemMulti   = (weapon != null && weapon.getGemstoneType() == 3 /* MultishotGem */) ? 1 : 0;
        final float SPREAD   = (_archShot != null && _archShot.getSpreadRad() > 0f)
                ? _archShot.getSpreadRad() : 0.12f;
        final float rangeMul = (_archShot != null && _archShot.getRangeMul() > 0f)
                ? _archShot.getRangeMul() : 1.0f;
        final boolean archPierces = _archShot != null && _archShot.isPiercing();
        final int totalBullets = archCount + gemMulti;
        log.info("{} shoot-predict weapon='{}' projGroupId={} archCount={} gemMulti={} totalBullets={} enchants={}",
                LOG_NS, weapon.getName(), projGroupId, archCount, gemMulti, totalBullets,
                weapon.getEnchantments() == null ? 0 : weapon.getEnchantments().size());

        // Homing prediction target: nearest enemy to the cursor within ~6 tiles
        // (mirrors the server) so the predicted seeker curves like the real one
        // until the server's authoritative copy takes over.
        long predictedHomingTarget = 0L;
        boolean groupHasHoming = false;
        for (final Projectile pr : group.getProjectiles()) {
            if (pr.getFlags() != null && pr.getFlags().contains(ProjectileFlag.HOMING.flagId)) { groupHasHoming = true; break; }
        }
        if (groupHasHoming && realm.getEnemies() != null) {
            float bestSq = 192f * 192f;
            for (final Enemy en : realm.getEnemies().values()) {
                if (en == null || en.getPos() == null) continue;
                final float ecx = en.getPos().x + en.getSize() * 0.5f;
                final float ecy = en.getPos().y + en.getSize() * 0.5f;
                final float dx = ecx - dest.x, dy = ecy - dest.y;
                final float d = dx * dx + dy * dy;
                if (d < bestSq) { bestSq = d; predictedHomingTarget = en.getId(); }
            }
        }
        final long lockedHomingTarget = predictedHomingTarget;

        for (final Projectile proj : group.getProjectiles()) {
            float projAngleOffset = 0f;
            try { projAngleOffset = Float.parseFloat(proj.getAngle()); } catch (Exception ignored) {}
            final float shootAngle = baseAngle + projAngleOffset;
            final short rolledDamage = (short) (proj.getDamage() + atkBonus);
            final short offset = (short) (player.getSize() / 2);
            for (int i = 0; i < totalBullets; i++) {
                // CLONE spawnPos per-bullet — Bullet's GameObject ctor
                // does `this.pos = origin;` (no defensive copy), so all
                // bullets sharing one source Vector2f advance THE SAME
                // pos every tick. With multishot+1, both bullets shared
                // one pos and each tick's update() ran twice on it,
                // making the visible bullet appear to move at 2x speed
                // (and overlap in screen space, so the player saw one
                // 'phantom' shot rather than the expected pair).
                final Vector2f spawnPos = source.clone(-offset, -offset);
                final float deltaA = (i - (totalBullets - 1) / 2f) * SPREAD;
                // CRITICAL: predicted Bullet.projectileId MUST be the GROUP
                // id (projGroupId) — not proj.getProjectileId() — to match
                // the server's bullet broadcast. RealmManagerServer.addProjectile
                // sets bullet.projectileId = the GROUP id passed in (its
                // first projectileId param), not the individual projectile's
                // id. With the wrong field, findMatchingPredictedBullet's
                // projectileId equality check failed and dedup silently
                // missed every shot — so the predicted bullets accumulated
                // alongside the server-confirmed copies (or got culled
                // later, leaving only the "phantom" central shot the user
                // reported). Webclient parity: main.js spawnPredictedBullets
                // passes projGroupId here too.
                // Archetype range multiplier — staves outshoot daggers.
                final float predictedRange = proj.getRange() * rangeMul;
                final Bullet b = new Bullet(Realm.RANDOM.nextLong(), projGroupId, spawnPos,
                        shootAngle + deltaA, proj.getSize(), proj.getMagnitude(), predictedRange,
                        rolledDamage, false);
                b.setSrcEntityId(player.getId());
                b.setAmplitude(proj.getAmplitude());
                b.setFrequency(proj.getFrequency());
                // Carry the projectile's behavior flags so dedup + hit
                // prediction (PLAYER_PROJECTILE / PARAMETRIC / ORBITAL etc.)
                // see the same trajectory as the server-side bullet. If the
                // archetype declares piercing, add PASS_THROUGH_ENEMIES (25)
                // when the projectile def doesn't already carry it.
                final List<Short> baseFlags = proj.getFlags() != null
                        ? new ArrayList<>(proj.getFlags()) : new ArrayList<>();
                if (archPierces && !baseFlags.contains((short) 25)) {
                    baseFlags.add((short) 25);
                }
                if (!baseFlags.isEmpty()) {
                    b.setFlags(baseFlags);
                }
                if (proj.getEffects() != null) {
                    b.setEffects(proj.getEffects());
                }
                if (sheet != null) b.setSpriteSheet(sheet);
                b.setPredicted(true);
                // Honor the authored lifetime so the predicted bullet expires when
                // the server's does (player path used to ignore these).
                b.setLifetimeTicks(proj.getLifetimeTicks());
                b.setLength(proj.getLength());
                if (proj.getFlags() != null && proj.getFlags().contains(ProjectileFlag.HOMING.flagId)) {
                    b.setTargetEntityId(lockedHomingTarget);
                }
                realm.addBullet(b);
            }
        }
    }

    @SuppressWarnings("unused")
    private List<Bullet> getBullets() {
        final GameObject[] gameObject = this.realmManager.getRealm()
                .getGameObjectsInBounds(this.realmManager.getRealm().getTileManager().getRenderViewPort(this.getPlayer()));

        final List<Bullet> results = new ArrayList<>();
        for (int i = 0; i < gameObject.length; i++) {
            if (gameObject[i] instanceof Bullet) {
                results.add((Bullet) gameObject[i]);
            }
        }
        return results;
    }

    @Override
    public void input(MouseHandler mouse, KeyHandler key) {
        key.escape.tick();
        key.f1.tick();
        key.f2.tick();
        key.shift.tick();
        key.t.tick();
        key.enter.tick();
        key.one.tick();
        key.two.tick();
        key.three.tick();
        key.four.tick();
        key.five.tick();
        key.six.tick();
        key.seven.tick();
        key.eight.tick();
        key.m.tick();
        key.plus.tick();
        key.minus.tick();

        Player player = this.realmManager.getRealm().getPlayer(this.playerId);
        if (player == null)
            return;

        this.cam.input(mouse, key);

        if (!this.gsm.isStateActive(GameStateManager.PAUSE)) {
            if ((this.cam.getTarget() == player) && !player.hasEffect(StatusEffectType.PARALYZED)) {
                final Map<Cardinality, Boolean> lastDirectionTempMap = new HashMap<>();
                player.input(mouse, key);

                // ============================================================
                // PORTED FROM web client main.js processInput(dt) (~line 2030):
                // drain ticks -> simulateTick(perTick) -> set interpFrom/To ->
                // compute renderX = lerp(from, to, frac) -> set HUD positions.
                //
                // This BLOCK is the entire player movement & visual position
                // pipeline. Doing it INLINE here (not split between update()
                // and input()) is critical: any 1-frame split between
                // simulating and computing renderX produces visible per-tick
                // lurch even when both endpoints are correct, because dt has
                // moved on before the lerp catches up.
                // ============================================================
                // 64 Hz client tick — exact server parity. v1.0.48 had this
                // at 120 Hz to get a steady 2-ticks-per-frame at 60 fps
                // vsync, but that broke server reconciliation: each client
                // tick simulated 1/120 s of motion while the server applied
                // 1/64 s ticks, so replaying buffered inputs after a
                // PlayerPosAck produced positions that diverged from the
                // server's. The webclient runs at 64 Hz and absorbs the
                // ~1.067 ticks-per-frame jitter via input replay; doing the
                // same here keeps the rollback math exact. Visual smoothness
                // still comes from the existing extrapolated render formula
                // (renderX = pos + frac × lastTickStep).
                final float TICK_RATE = 64f;
                final float TICK_DT = 1f / TICK_RATE;
                float frameDt = Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f);
                this.moveAccumulator += frameDt;
                if (this.moveAccumulator > 0.25f) this.moveAccumulator = 0.25f;

                // Direction unit vector from key state. Continuous angle
                // (no 22.5° snapping) — matches web client's
                // screenDirFlagsToWorldVector.
                float vx = (player.getIsRight() ? 1f : 0f) - (player.getIsLeft() ? 1f : 0f);
                float vy = (player.getIsDown()  ? 1f : 0f) - (player.getIsUp()   ? 1f : 0f);
                final float mag = (float) Math.sqrt(vx * vx + vy * vy);
                if (mag > 0f) { vx /= mag; vy /= mag; }

                // Per-tick pixel step. RotMG: tiles/sec = 4 + 5.6 * (spd/75).
                // Status modifiers MUST match webclient game.js simulateTick
                // exactly so client + server (and replay) all agree on the
                // step magnitude per tick.
                // basePxPerTick excludes SLOWED so the replay can re-derive the
                // 0.5 factor from (current OR snapshot) SLOWED without double-
                // counting; SPEEDY stays baked in (snapshot semantics, matches web).
                float baseTilesPerSec = 4.0f + 5.6f * (player.getComputedStats().getSpd() / 75.0f);
                if (player.hasEffect(StatusEffectType.SPEEDY)) baseTilesPerSec *= 1.5f;
                final float basePxPerTick = baseTilesPerSec * 32.0f / TICK_RATE;
                final boolean slowedNow = player.hasEffect(StatusEffectType.SLOWED);
                final float pxPerTick = basePxPerTick * (slowedNow ? 0.5f : 1.0f);

                int ticks = 0;
                while (this.moveAccumulator >= TICK_DT) {
                    this.moveAccumulator -= TICK_DT;
                    // Anchor the render lerp at the position ENTERING this tick.
                    // After the drain it holds the start of the FINAL tick, so
                    // render() interpolates across only the most-recent tick —
                    // not across every tick drained this frame (which snapped
                    // backward on 2-tick frames) and not by extrapolating past
                    // the sim (which overshot on direction changes, the jagged
                    // feel vs the webclient).
                    this.interpFromX = player.getPos().x;
                    this.interpFromY = player.getPos().y;
                    this.hasInterpAnchor = true;
                    // Allocate a fresh input seq for this tick. Mirrors the
                    // webclient's per-tick seq increment (main.js#handleInput
                    // game._inputSeq++). Each tick gets a unique seq so the
                    // server's PlayerPosAck can ack exactly one of them and
                    // the client knows precisely how many remaining inputs
                    // to replay.
                    player.setLastInputSeq(player.getLastInputSeq() + 1);
                    final int seq = player.getLastInputSeq();

                    // Apply one tick of movement with collision check. We
                    // set dx/dy on the player and let movePlayer (the
                    // shared collision-aware integrator) advance pos.x/y
                    // by exactly one tick worth. PARALYZED short-circuits
                    // movement entirely — handled here rather than inside
                    // movePlayer so the reconcile-replay loop can call
                    // movePlayer for non-paralyzed snapshot inputs even
                    // while the player is currently paralyzed.
                    final boolean paralyzedNow = player.hasEffect(StatusEffectType.PARALYZED);
                    if (paralyzedNow) {
                        player.setDx(0);
                        player.setDy(0);
                    } else {
                        player.setDx(vx * pxPerTick);
                        player.setDy(vy * pxPerTick);
                        this.movePlayer(player);
                    }

                    // Buffer this input for reconciliation replay. Capture
                    // pxPerTick so the replay uses the EXACT step magnitude
                    // that was applied originally — spd stat or SPEEDY effect
                    // can change between now and ack arrival, and we want
                    // the replay to reproduce what the simulation actually
                    // did, not what it would do today.
                    synchronized (this.pendingInputs) {
                        final boolean paralyzedAtSend = player.hasEffect(StatusEffectType.PARALYZED);
                        this.pendingInputs.addLast(new PendingInput(seq, vx, vy, basePxPerTick, slowedNow, paralyzedAtSend));
                        while (this.pendingInputs.size() > 128) {
                            this.pendingInputs.pollFirst();
                        }
                    }

                    // Send the input to the server every tick (was: only on
                    // direction change). Per-tick send + per-tick seq is
                    // what makes rollback prediction work — the server's
                    // ack carries the seq it last processed, and the client
                    // matches that to its buffer to replay only the inputs
                    // the server hasn't seen yet. ~64 packets/sec × 21 bytes
                    // = ~1.3 KB/s per player, same as the webclient.
                    try {
                        PlayerMovePacket packet = PlayerMovePacket.from(player, seq, vx, vy);
                        this.realmManager.getClient().sendRemote(packet);
                    } catch (Exception e) {
                        PlayState.log.error("{} failed to create player move packet", LOG_NS, e);
                    }

                    ticks++;
                }

                // Set facing flags for animation/aim regardless of ticks.
                if (player.getIsUp())    lastDirectionTempMap.put(Cardinality.NORTH, true); else lastDirectionTempMap.put(Cardinality.NORTH, false);
                if (player.getIsDown())  lastDirectionTempMap.put(Cardinality.SOUTH, true); else lastDirectionTempMap.put(Cardinality.SOUTH, false);
                if (player.getIsLeft())  lastDirectionTempMap.put(Cardinality.WEST,  true); else lastDirectionTempMap.put(Cardinality.WEST,  false);
                if (player.getIsRight()) lastDirectionTempMap.put(Cardinality.EAST,  true); else lastDirectionTempMap.put(Cardinality.EAST,  false);
                if (vx == 0f && vy == 0f) {
                    player.setDx(0); player.setDy(0);
                    lastDirectionTempMap.put(Cardinality.NONE, true);
                }

                // (PlayerMovePacket is now sent inside the tick-drain loop
                // above, once per simulated tick — required for proper
                // server reconciliation. lastDirectionMap is no longer
                // load-bearing for network purposes; left in place for any
                // local consumers that still read it.)
                if (this.lastDirectionMap == null) {
                    this.lastDirectionMap = lastDirectionTempMap;
                } else if (!this.lastDirectionMap.equals(lastDirectionTempMap)) {
                    this.lastDirectionMap = lastDirectionTempMap;
                }

                // Render position via INTERPOLATION between the start and end
                // of the most-recent tick (web parity, main.js _renderX). The
                // accumulator's leftover fraction walks renderX from the tick's
                // start toward its end:
                //
                //   renderX = interpFrom + (pos - interpFrom) * (acc / TICK_DT)
                //
                // interpFrom is the position entering the final tick (or the
                // snapped pos after a reconcile). Interpolating between two
                // adjacent tick states is smooth at any frame/tick beat — no
                // overshoot on direction changes, no backward snap on 2-tick
                // frames.
                final float interpFrac = Math.max(0f, Math.min(1f, this.moveAccumulator / TICK_DT));
                float renderX = this.interpFromX + (player.getPos().x - this.interpFromX) * interpFrac;
                float renderY = this.interpFromY + (player.getPos().y - this.interpFromY) * interpFrac;

                // Decay any reconciliation smoothing offset toward zero each
                // frame, then apply it to the rendered position. The logical
                // pos was snapped to the server's authoritative replay result
                // (accurate collisions next tick), but the visual lag of the
                // diff is decayed out over a few frames — mirrors the
                // webclient's _smoothX/_smoothY in handlePosAck.
                if (this.smoothingOffsetX != 0f || this.smoothingOffsetY != 0f) {
                    final float decay = (float) Math.exp(-frameDt / 0.07f); // ~50ms half-life
                    this.smoothingOffsetX *= decay;
                    this.smoothingOffsetY *= decay;
                    if (Math.abs(this.smoothingOffsetX) < 0.05f) this.smoothingOffsetX = 0f;
                    if (Math.abs(this.smoothingOffsetY) < 0.05f) this.smoothingOffsetY = 0f;
                    renderX += this.smoothingOffsetX;
                    renderY += this.smoothingOffsetY;
                }

                player.setRenderPos(renderX, renderY);

                // Camera follows the lerped player position with
                // exponential smoothing (web parity, game.js ~1580):
                //   cameraX += (target - cameraX) * (1 - exp(-dt/halflife))
                // Halflife 0.03s -> ~97% of any gap closes within 150ms,
                // frame-rate independent. The hard lock that was here
                // before made the camera feel sluggish on direction
                // changes — the player would visibly drift off-center
                // for a tick or two before snapping back; with eased
                // smoothing the camera glides naturally.
                if (Float.isNaN(this.cameraX)) {
                    this.cameraX = renderX;
                    this.cameraY = renderY;
                } else {
                    final float halfLife = 0.03f;
                    final float camSmooth = 1f - (float) Math.exp(-frameDt / halfLife);
                    this.cameraX += (renderX - this.cameraX) * camSmooth;
                    this.cameraY += (renderY - this.cameraY) * camSmooth;
                }

                final float worldViewW = OpenRealmGame.width / OpenRealmGame.WORLD_SCALE;
                final float worldViewH = OpenRealmGame.height / OpenRealmGame.WORLD_SCALE;
                final float hudPanelWorldW = (OpenRealmGame.width / 5f) / OpenRealmGame.WORLD_SCALE;
                PlayState.map.x = this.cameraX - (worldViewW - hudPanelWorldW) / 2f;
                PlayState.map.y = this.cameraY - (worldViewH * 0.5f);
                Vector2f.setWorldVar(PlayState.map.x, PlayState.map.y);
            }
            boolean canUsePortal = (System.currentTimeMillis() - this.lastPortalTick) > PORTAL_COOLDOWN_MS;
            // Space also triggers nearest-portal use, mirroring web client
            // hotkey behaviour. attack.tick() runs in the key.attack
            // pipeline above (KeyHandler binds Space -> key.attack), so
            // attack.clicked is edge-triggered just like f2.clicked.
            key.attack.tick();
            boolean portalKeyClicked = key.f2.clicked || key.attack.clicked;
            if (portalKeyClicked && canUsePortal) {
                try {
                    Portal closestPortal = this.realmManager.getState().getClosestPortal(this.getPlayerPos(), 32);
                    if (closestPortal != null) {
                        PortalModel portalModel = GameDataManager.PORTALS.get((int) closestPortal.getPortalId());
                        // Web parity (main.js doRealmTransition): if the portal
                        // entity is the Vault portal (portalId == 2), send the
                        // toVault variant of UsePortalPacket — that's the only
                        // way the server reaches its setupChests / exit-portal
                        // branch (ServerGameLogic.handleUsePortalServer line 148).
                        // Sending UsePortalPacket.from for a vault-portal entity
                        // hits the generic portal branch instead, which routes
                        // by Portal.toRealmId — for a freshly-spawned exit
                        // portal that link points into the wrong realm so the
                        // chest spawn never happens and the user lands somewhere
                        // unexpected.
                        final boolean isVaultPortal = closestPortal.getPortalId() == 2;
                        if (isVaultPortal) {
                            if (this.realmManager.getRealm().getMapId() == 1) {
                                // Already in vault — ignore, mirror web client
                                // re-entry guard.
                                return;
                            }
                            UsePortalPacket usePortal = UsePortalPacket.toVault(
                                    this.realmManager.getRealm().getRealmId());
                            this.realmManager.getClient().sendRemote(usePortal);
                            this.realmManager.getRealm().loadMap(1);
                        } else {
                            UsePortalPacket usePortal = UsePortalPacket.from(closestPortal.getId(),
                                    this.realmManager.getRealm().getRealmId());
                            this.realmManager.getClient().sendRemote(usePortal);
                            this.realmManager.getRealm().loadMap(portalModel.getMapId());
                        }
                        // Flag that we're transitioning realms - next ObjectMovePacket should snap position
                        this.realmManager.setAwaitingRealmTransition(true);
                        // Tell server we're ready for tiles after map rebuild
                        this.realmManager.getClient().sendRemote(LoginAckPacket.from());
                        this.lastPortalTick = System.currentTimeMillis();
                    }
                } catch (Exception e) {
                    PlayState.log.error("{} failed to send UsePortalPacket: {}", LOG_NS, e.getMessage());
                }

            }
            // R = teleport to Nexus (map 29). Mirrors the web client's
            // hotkey. Suppressed while the chat input is capturing keys
            // so the user can type 'r' in messages without TPing out.
            if (!key.captureMode
                    && Gdx.input.isKeyJustPressed(Settings.get().getKeybind("goNexus"))
                    && canUsePortal
                    && this.realmManager.getRealm().getMapId() != 29) {
                try {
                    UsePortalPacket usePortal = UsePortalPacket.toNexus(
                            this.realmManager.getRealm().getRealmId());
                    this.realmManager.getClient().sendRemote(usePortal);
                    this.realmManager.getRealm().loadMap(29);
                    this.realmManager.setAwaitingRealmTransition(true);
                    this.realmManager.getClient().sendRemote(LoginAckPacket.from());
                    this.lastPortalTick = System.currentTimeMillis();
                } catch (Exception e) {
                    PlayState.log.error("{} failed to send Nexus UsePortalPacket: {}", LOG_NS, e.getMessage());
                }
            }
            if (key.f1.clicked && canUsePortal) {
                try {
                    if (this.realmManager.getRealm().getMapId() != 1) {
                        UsePortalPacket usePortal = UsePortalPacket.toVault(this.realmManager.getRealm().getRealmId());
                        this.realmManager.getClient().sendRemote(usePortal);
                        this.realmManager.getRealm().loadMap(1);
                        this.realmManager.setAwaitingRealmTransition(true);
                        this.realmManager.getClient().sendRemote(LoginAckPacket.from());
                        this.lastPortalTick = System.currentTimeMillis();
                    }
                } catch (Exception e) {
                    PlayState.log.error("{} failed to send Vault UsePortalPacket: {}", LOG_NS, e.getMessage());
                }

            }

            // F = interact with nearby tile (forge / fame store / etc).
            // Mirrors web client's updateInteractPrompt + triggerNearbyInteract:
            // scan a 5x5 window around the player, pick the closest tile whose
            // TileModel has a non-empty interactionType, send InteractTilePacket.
            // Server replies with OpenForgePacket / OpenFameStorePacket.
            if (!key.captureMode && Gdx.input.isKeyJustPressed(Settings.get().getKeybind("lootPickup"))) {
                try {
                    final TileMap baseLayer = this.realmManager.getRealm().getTileManager().getBaseLayer();
                    final TileMap collisionLayer = this.realmManager.getRealm().getTileManager().getCollisionLayer();
                    final int ts = baseLayer.getTileSize();
                    final int px = (int) (player.getPos().x / ts);
                    final int py = (int) (player.getPos().y / ts);
                    int bestTx = -1, bestTy = -1;
                    float bestD2 = Float.MAX_VALUE;
                    for (int dy = -2; dy <= 2; dy++) {
                        for (int dx = -2; dx <= 2; dx++) {
                            int tx = px + dx, ty = py + dy;
                            if (tx < 0 || ty < 0 || tx >= baseLayer.getWidth() || ty >= baseLayer.getHeight()) continue;
                            final Tile[] candidates = new Tile[]{
                                    collisionLayer.getBlocks()[ty][tx],
                                    baseLayer.getBlocks()[ty][tx]
                            };
                            for (Tile t : candidates) {
                                if (t == null) continue;
                                final TileModel def = GameDataManager.TILES.get((int) t.getTileId());
                                if (def == null || def.getInteractionType() == null
                                        || def.getInteractionType().isEmpty()) continue;
                                final float cx = (tx + 0.5f) * ts;
                                final float cy = (ty + 0.5f) * ts;
                                final float ddx = cx - player.getPos().x;
                                final float ddy = cy - player.getPos().y;
                                final float d2 = ddx * ddx + ddy * ddy;
                                if (d2 < bestD2 && d2 <= (3 * ts) * (3 * ts)) {
                                    bestD2 = d2;
                                    bestTx = tx;
                                    bestTy = ty;
                                }
                            }
                        }
                    }
                    if (bestTx >= 0) {
                        InteractTilePacket pkt = new InteractTilePacket();
                        pkt.setTileX(bestTx);
                        pkt.setTileY(bestTy);
                        this.realmManager.getClient().sendRemote(pkt);
                    }
                } catch (Exception e) {
                    PlayState.log.error("{} failed to send InteractTilePacket: {}", LOG_NS, e.getMessage());
                }
            }
            if (this.pui != null) {
                this.pui.input(mouse, key);
            }
            boolean canQuickUse = (System.currentTimeMillis() - this.lastQuickUseTick) > QUICK_USE_COOLDOWN_MS;
            // Shift + 1..8 hot-swaps the first eight backpack slots (5..12, the
            // visible main row) into their target equipment slot, or consumes a
            // consumable. Matches webclient main.js shift+Digit1-8. Hot-swap
            // REQUIRES shift because plain 1..4 are ability casts (handled below);
            // without the gate, pressing 1 to cast would also equip slot 5.
            final boolean shiftHotswap = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)
                    || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);
            if (canQuickUse && shiftHotswap) {
                final int base = 5;
                boolean used = false;
                if (key.one.clicked) { this.handleQuickUseKey(base + 0); used = true; }
                else if (key.two.clicked)   { this.handleQuickUseKey(base + 1); used = true; }
                else if (key.three.clicked) { this.handleQuickUseKey(base + 2); used = true; }
                else if (key.four.clicked)  { this.handleQuickUseKey(base + 3); used = true; }
                else if (key.five.clicked)  { this.handleQuickUseKey(base + 4); used = true; }
                else if (key.six.clicked)   { this.handleQuickUseKey(base + 5); used = true; }
                else if (key.seven.clicked) { this.handleQuickUseKey(base + 6); used = true; }
                else if (key.eight.clicked) { this.handleQuickUseKey(base + 7); used = true; }
                if (used) this.lastQuickUseTick = System.currentTimeMillis();
            }

            if (this.pui != null) {
                // Rebindable (Options > Controls): skillsMenu (default M), metricsMenu (default .).
                if (Gdx.input.isKeyJustPressed(Settings.get().getKeybind("skillsMenu")))
                    this.pui.getSkillsWindow().toggle();
                if (Gdx.input.isKeyJustPressed(Settings.get().getKeybind("metricsMenu")))
                    this.pui.getMetricsWindow().toggleFor(SocketClient.CHARACTER_UUID);
                // Minimap moved off M to N.
                if (Gdx.input.isKeyJustPressed(Input.Keys.N)) this.pui.getMinimap().toggle();
                // Zoom is driven by the minimap's own mouse-wheel handler now
                // (see Minimap input pass) — the +/- keyboard fallback was
                // removed alongside the textured-quad rewrite. Keep this
                // input branch in case future layouts re-add keyboard zoom.
            }
        }

        // Toggle the in-game options window with O — mirrors the web client's
        // gear-icon shortcut. Doesn't conflict with PauseState (ESC).
        // Suppressed while chat input is capturing keys so the user can type
        // 'o' in messages without flickering the options window.
        if (this.pui != null && !key.captureMode
                && Gdx.input.isKeyJustPressed(Input.Keys.O)) {
            this.pui.getOptionsWindow().toggle();
        }

        // Use isKeyJustPressed (rising-edge only) instead of key.escape.clicked.
        // Key.toggle increments the press counter every frame the key is HELD,
        // and Key.tick consumes one press per call — so holding ESC for 2+
        // frames in a row makes clicked fire on consecutive frames. With the
        // pop/add toggle below, that meant ESC closed the menu then immediately
        // re-opened it ("pressing ESC just takes you back to the escape menu").
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            // Web-parity modals consume ESC first. They close themselves in
            // their own update(), but we also need to suppress the pause
            // toggle so a single keypress doesn't simultaneously close a
            // modal AND open the pause menu.
            boolean anyModal = (this.pui != null) && (
                    this.pui.getForgeWindow().isVisible()
                 || this.pui.getFameStoreWindow().isVisible()
                 || this.pui.getOptionsWindow().isVisible()
                 || this.pui.getPotionStorageWindow().isVisible());
            if (anyModal) {
                // Each modal already closes itself on ESC in its update().
                // Just don't toggle pause this frame.
            } else if (this.gsm.isStateActive(GameStateManager.PAUSE)) {
                this.gsm.pop(GameStateManager.PAUSE);
            } else {
                try {
					final PlayerAccountDto account = ClientGameLogic.DATA_SERVICE
					        .executeGet("/data/account/" + this.getAccount().getAccountUuid(), null, PlayerAccountDto.class);
					this.setAccount(account);
	                PauseState pause = new PauseState(this.gsm, this.getAccount());
	                this.gsm.add(GameStateManager.PAUSE, pause);
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}

            }
        }

        double dex = (int) ((6.5 * (this.getPlayer().getComputedStats().getDex() + 17.3)) / 75);
		// Weapon-archetype attack-speed multiplier (hammers swing slow,
		// daggers fast). Mirrors ServerGameLogic.handlePlayerShoot. Applied
		// BEFORE BERSERK so the +50% buff stacks consistently with archetype.
		{
			final GameItem _w = player.getInventory()[0];
			final WeaponArchetypeModel _archFR =
					(_w == null || _w.getArchetypeId() <= 0 || GameDataManager.WEAPON_ARCHETYPES == null)
							? null
							: GameDataManager.WEAPON_ARCHETYPES.get(_w.getArchetypeId());
			if (_archFR != null && _archFR.getAttackSpeedMul() > 0f) {
				dex = dex * _archFR.getAttackSpeedMul();
			}
		}
		// Client-side fire-rate prediction. BERSERK boosts attack speed by 50%
		// (was SPEEDY pre-split). SPEEDY is movement-only now.
		if (player.hasEffect(StatusEffectType.BERSERK)) {
			dex = dex * 1.5;
		}
        boolean canShoot = (System.currentTimeMillis() - this.lastShotTick) > (1000 / dex + 10);
        boolean canUseAbility = (System.currentTimeMillis() - this.lastAbilityTick) > 1000;
        // Hotbar-cell hits steal the click — mirror the webclient where
        // clicking an ability cell fires the bound ability at the cursor
        // (cells 1..4) or is a no-op on the passive cell (cell 0). Without
        // this suppression the basic-attack shot below would also fire on
        // the same click, double-tapping the projectile pipeline.
        final boolean hoveringHotbar = (this.pui != null)
                && this.pui.isHoveringHotbarCell(mouse.getX(), mouse.getY());
        boolean clickingWorld = mouse.isPressed(1)
                && (this.pui == null || !this.pui.isHoveringInventory(mouse.getX()))
                && !hoveringHotbar;
        // WHY: do NOT call player.setAttacking(clickingWorld) here. That clobbers
        // the timer-driven attack flag and cuts the attack animation the instant
        // the mouse button releases — the webclient instead refreshes a 0.3s
        // shootingAnim timer on every shot fire (main.js ~2205). We do the
        // equivalent below at the actual firing site via triggerAttackAnimation.
        // Screen → world conversion. WORLD_SCALE=2 ⇒ 1 screen px = 1/2 world px.
        final float invScale = 1f / OpenRealmGame.WORLD_SCALE;
        final float pivotWx = player.getPos().x + player.getSize() * 0.5f;
        final float pivotWy = player.getPos().y + player.getSize() * 0.5f;
        // Pivot the aim about the player's ACTUAL on-screen position, derived
        // from the live render origin (PlayState.map). The world view is shifted
        // left to clear the right-side HUD panel, so the player renders at
        // ~0.4*width, not screen center — pivoting about width/2 skewed the aim
        // angle by up to ~30 degrees near vertical.
        final float screenCx = (pivotWx - PlayState.map.x) * OpenRealmGame.WORLD_SCALE;
        final float screenCy = (pivotWy - PlayState.map.y) * OpenRealmGame.WORLD_SCALE;
        final float sdx = mouse.getX() - screenCx;
        final float sdy = mouse.getY() - screenCy;
        final float aimWx = pivotWx + sdx * invScale;
        final float aimWy = pivotWy + sdy * invScale;
        player.setAimX(aimWx);
        player.setAimY(aimWy);
        player.setAimControlled(true);
        if (clickingWorld && canShoot) {
            this.lastShotTick = System.currentTimeMillis();
            Vector2f dest = new Vector2f(aimWx, aimWy);
            this.shotDestQueue.add(dest);
            // Webclient parity: each shot refreshes the attack animation hold
            // so the local player keeps cycling attack frames between rapid
            // shots and for ~350ms after the last one.
            player.triggerAttackAnimation();
        }
        // Mouse-click-on-hotbar-cell fires the bound ability at the cursor.
        // Mirrors webclient ui-widgets.updateAbilityBar's click handler ->
        // __webclientFireAbilityFromUI(s) -> castWithPrediction at the
        // current cursor world coords. Edge-triggered (justPressed) so
        // holding the click doesn't spam fires; cooldown still gates via
        // canUseAbility. clickingWorld was already cleared above when the
        // cursor sits over the hotbar, so the basic-attack path below
        // won't double-fire on this same click.
        if (Gdx.input.isButtonJustPressed(Input.Buttons.LEFT)
                && this.pui != null
                && canUseAbility) {
            final int bindingIdx = this.pui.getHotbarBindingAtScreen(
                    mouse.getX(), mouse.getY());
            if (bindingIdx >= 0) {
                try {
                    final Vector2f pos = clampCastPos(player, bindingIdx, aimWx, aimWy);
                    final UseAbilityPacket useAbility = UseAbilityPacket.from(
                            this.getPlayer(), pos, bindingIdx);
                    this.realmManager.getClient().sendRemote(useAbility);
                    this.lastAbilityTick = System.currentTimeMillis();
                } catch (Exception e) {
                    PlayState.log.error("{} failed to send UseAbility packet from hotbar click for slot {}",
                            LOG_NS, bindingIdx, e);
                }
            }
        }
        // Right-click a hotbar ability cell → invest a skill point into the bound
        // ability. Webclient parity (ui-widgets contextmenu → __webclientInvest-
        // SkillPoint). Edge-triggered so a held right-click can't drain the pool;
        // the global right-click ability-fire below is suppressed over the hotbar
        // (hoveringHotbar) so the two don't both act on the same click.
        if (Gdx.input.isButtonJustPressed(Input.Buttons.RIGHT) && this.pui != null) {
            final int investBinding = this.pui.getHotbarBindingAtScreen(mouse.getX(), mouse.getY());
            if (investBinding >= 0) {
                try {
                    final InvestSkillPointPacket pkt = new InvestSkillPointPacket((byte) investBinding);
                    this.realmManager.getClient().sendRemote(pkt);
                    // Optimistic local mirror so the SP pip column updates immediately —
                    // server-authoritative state lands on the next sync.
                    final Ability ab = this.getPlayer().getActiveAbility(investBinding);
                    if (ab != null) this.getPlayer().investSkillPoint(ab.getId());
                } catch (Exception e) {
                    PlayState.log.error("{} failed to send InvestSkillPoint from hotbar right-click for slot {}",
                            LOG_NS, investBinding, e);
                }
            }
        }

        // Phase 2C/2D — number-key hotbar mapping. Plain keys 1..4 fire the four
        // hotbar slots at the cursor. Shift + number is reserved for inventory
        // hot-swap (handled above), so we skip the cast when shift is held.
        // Skill-point investment moved to right-clicking the hotbar ability cell
        // (webclient parity) — see the hotbar right-click branch above.
        {
            final boolean shiftHeldDigit = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)
                    || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);
            final int[] digitKeys = { Input.Keys.NUM_1, Input.Keys.NUM_2, Input.Keys.NUM_3, Input.Keys.NUM_4 };
            for (int slot = 0; slot < 4; slot++) {
                if (!Gdx.input.isKeyJustPressed(digitKeys[slot])) continue;
                if (this.pui != null && this.pui.isHoveringInventory(mouse.getX())) continue;
                if (shiftHeldDigit) continue;
                if (canUseAbility) {
                    try {
                        Vector2f pos = clampCastPos(player, slot, aimWx, aimWy);
                        UseAbilityPacket useAbility = UseAbilityPacket.from(this.getPlayer(), pos, slot);
                        this.realmManager.getClient().sendRemote(useAbility);
                        this.lastAbilityTick = System.currentTimeMillis();
                    } catch (Exception e) {
                        PlayState.log.error("{} failed to send UseAbility packet for slot {}", LOG_NS, slot, e);
                    }
                }
            }
        }

        if ((mouse.isPressed(3)) && canUseAbility && !hoveringHotbar
                && (this.pui == null || !this.pui.isHoveringInventory(mouse.getX()))) {
            // Client-side mana gate. Server enforces this too, but without
            // a local check the player can spam-click and watch predicted
            // projectiles spawn before the server reply unloads them, then
            // the mana bar snaps back when UpdatePacket arrives. Mirrors
            // the webclient tryUseAbility cost gate. Optimistic decrement
            // keeps the gate honest within a round-trip.
            // Phase 1B: ability is now class-bound (no longer slot 1).
            int abilityCost = 0;
            try {
                final GameItem ability = player.getAbility();
                if (ability != null && ability.getEffect() != null) {
                    abilityCost = ability.getEffect().getMpCost();
                }
            } catch (Exception ignored) { /* zero-cost fallback */ }
            if (abilityCost > 0 && player.getMana() < abilityCost) {
                // Out of mana — skip both the send and the cooldown bump.
            } else {
                try {
                    Vector2f pos = new Vector2f(aimWx, aimWy);
                    UseAbilityPacket useAbility = UseAbilityPacket.from(this.getPlayer(), pos);
                    this.realmManager.getClient().sendRemote(useAbility);
                    this.lastAbilityTick = System.currentTimeMillis();
                    if (abilityCost > 0) {
                        player.setMana(Math.max(0, player.getMana() - abilityCost));
                    }
                } catch (Exception e) {
                    PlayState.log.error("{} failed to send UseAbility packet", LOG_NS, e);
                }
            }
        }
    }

    private Vector2f clampCastPos(Player p, int bindingIdx, float rawX, float rawY) {
        final Ability ab = p.getActiveAbility(bindingIdx);
        if (ab == null) return new Vector2f(rawX, rawY);
        final int max = ab.getMaxCastRange();
        final float cx = p.getPos().x + p.getSize() * 0.5f;
        final float cy = p.getPos().y + p.getSize() * 0.5f;
        if (max > 0) {
            this.castRingCx = cx;
            this.castRingCy = cy;
            this.castRingRadius = max;
            this.castRingExpiresAt = System.currentTimeMillis() + CAST_RING_DURATION_MS;
        }
        if (max < 0) return new Vector2f(rawX, rawY);
        if (max == 0) return new Vector2f(cx, cy);
        final float dx = rawX - cx;
        final float dy = rawY - cy;
        final float distSq = dx * dx + dy * dy;
        if (distSq <= (float) max * max) return new Vector2f(rawX, rawY);
        final float scale = max / (float) Math.sqrt(distSq);
        return new Vector2f(cx + dx * scale, cy + dy * scale);
    }

    public GameItem getLootContainerItemByUid(String uid) {
        for (LootContainer lc : this.realmManager.getRealm().getLoot().values()) {
            for (GameItem item : lc.getItems()) {
                if (item.getUid().equals(uid))
                    return item;
            }
        }
        return null;
    }

    public void removeLootContainerItemByUid(String uid) {
        this.replaceLootContainerItemByUid(uid, null);
    }

    public void replaceLootContainerItemByUid(String uid, GameItem replacement) {
        for (LootContainer lc : this.realmManager.getRealm().getLoot().values()) {
            int foundIdx = -1;
            for (int i = 0; i < lc.getItems().length; i++) {
                GameItem item = lc.getItems()[i];
                if (item == null) {
                    continue;
                }
                if (item.getUid().equals(uid)) {
                    foundIdx = i;
                }
            }
            if (foundIdx > -1) {
                lc.setItem(foundIdx, replacement);
            }
        }
    }

    public LootContainer getClosestLootContainer(final Vector2f pos, final float limit) {
        float best = Float.MAX_VALUE;
        LootContainer bestLoot = null;
        for (final LootContainer lootContainer : this.realmManager.getRealm().getLoot().values()) {
            float dist = lootContainer.getPos().distanceTo(pos);
            if ((dist < best) && (dist <= limit)) {
                best = dist;
                bestLoot = lootContainer;
            }
        }
        return bestLoot;
    }

    /**
     * Scan the player's neighborhood for the closest tile that exposes a
     * non-empty interactionType (forge / fame_store / etc) and return its
     * type, or null if none is in range. Mirrors the F-key tile scan above
     * but as a read-only lookup so the HUD can render an interaction hint.
     */
    public String getNearbyInteractionType() {
        try {
            if (this.realmManager == null) return null;
            final Player player = this.getPlayer();
            if (player == null) return null;
            final TileMap baseLayer = this.realmManager.getRealm().getTileManager().getBaseLayer();
            final TileMap collisionLayer = this.realmManager.getRealm().getTileManager().getCollisionLayer();
            final int ts = baseLayer.getTileSize();
            final int px = (int) (player.getPos().x / ts);
            final int py = (int) (player.getPos().y / ts);
            String bestType = null;
            float bestD2 = Float.MAX_VALUE;
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    final int tx = px + dx, ty = py + dy;
                    if (tx < 0 || ty < 0 || tx >= baseLayer.getWidth() || ty >= baseLayer.getHeight()) continue;
                    final Tile[] candidates = new Tile[]{
                            collisionLayer.getBlocks()[ty][tx],
                            baseLayer.getBlocks()[ty][tx]
                    };
                    for (Tile t : candidates) {
                        if (t == null) continue;
                        final TileModel def = GameDataManager.TILES.get((int) t.getTileId());
                        if (def == null || def.getInteractionType() == null
                                || def.getInteractionType().isEmpty()) continue;
                        final float cx = (tx + 0.5f) * ts;
                        final float cy = (ty + 0.5f) * ts;
                        final float ddx = cx - player.getPos().x;
                        final float ddy = cy - player.getPos().y;
                        final float d2 = ddx * ddx + ddy * ddy;
                        if (d2 < bestD2 && d2 <= (3 * ts) * (3 * ts)) {
                            bestD2 = d2;
                            bestType = def.getInteractionType();
                        }
                    }
                }
            }
            return bestType;
        } catch (Exception ignored) {
            return null;
        }
    }

    public Portal getClosestPortal(final Vector2f pos, final float limit) {
        float best = Float.MAX_VALUE;
        Portal bestPortal = null;
        for (final Portal portal : this.realmManager.getRealm().getPortals().values()) {
            float dist = portal.getPos().distanceTo(pos);
            if ((dist < best) && (dist <= limit)) {
                best = dist;
                bestPortal = portal;
            }
        }
        return bestPortal;
    }

    /**
     * Reset the sub-tick interpolation anchor to the given position.
     * Called from {@code ClientGameLogic.handlePlayerPosAckClient} when
     * the server's authoritative position snaps the local player — the
     * old interpFromX/Y would otherwise still point at the pre-snap
     * position and the next render frame would lerp the camera from old
     * -> new, showing a visible hop every server tick.
     */
    public void resetInterpAnchor(float x, float y) {
        this.interpFromX = x;
        this.interpFromY = y;
        this.hasInterpAnchor = true;
        // Force the camera to snap to the new anchor too — otherwise the
        // exponential smoother would slide the camera across the world
        // following a portal teleport.
        this.cameraX = x;
        this.cameraY = y;
    }

    @Override
    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        Player player = this.realmManager.getRealm().getPlayer(this.playerId);
        if (player == null)
            return;
        // Switch from the default UI camera (set by OpenRealmGame.render)
        // to the zoomed world camera for tile + entity rendering. The HUD
        // pass below will switch back. Without this, world tiles/entities
        // would be drawn 1:1 and look way out of scale relative to the
        // web client's 2x desktop zoom.
        OpenRealmGame game = (OpenRealmGame) Gdx.app.getApplicationListener();
        if (game.getWorldCamera() != null) {
            final OrthographicCamera worldCam = game.getWorldCamera();
            worldCam.up.set(0f, -1f, 0f);
            worldCam.direction.set(0f, 0f, 1f);
            worldCam.update();
            batch.setProjectionMatrix(worldCam.combined);
            shapes.setProjectionMatrix(worldCam.combined);
            batch.setTransformMatrix(this.worldTransformIdt);
            shapes.setTransformMatrix(this.worldTransformIdt);
        }
        this.realmManager.getRealm().getTileManager().render(player, batch, shapes);

        final long nowMs = System.currentTimeMillis();
        if (this.castRingExpiresAt > nowMs && this.castRingRadius > 0f) {
            final float remain = (this.castRingExpiresAt - nowMs) / (float) CAST_RING_DURATION_MS;
            final float alpha = Math.max(0f, Math.min(1f, remain)) * 0.7f;
            final float rx = this.castRingCx - Vector2f.worldX;
            final float ry = this.castRingCy - Vector2f.worldY;
            batch.end();
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            shapes.begin(ShapeRenderer.ShapeType.Line);
            shapes.setColor(0.4f, 0.8f, 1.0f, alpha);
            shapes.circle(rx, ry, this.castRingRadius, 48);
            shapes.end();
            Gdx.gl.glDisable(GL20.GL_BLEND);
            batch.begin();
        }

        GameObject[] gameObject = this.realmManager.getRealm()
                .getGameObjectsInBounds(this.realmManager.getRealm().getTileManager().getRenderViewPort(player));

        // Reuse the per-frame buffers (declared as fields above). Clearing
        // keeps the backing arrays so we don't pay an allocation per frame.
        final List<Entity> visibleEntities = this.visibleEntities;
        final List<Bullet> visibleBullets = this.visibleBullets;
        final List<Enemy> visibleEnemies = this.visibleEnemies;
        visibleEntities.clear();
        visibleBullets.clear();
        visibleEnemies.clear();

        // Diagnostic: dump entity counts every ~5 seconds (60fps × 5 = 300
        // frames). Helps debug "why aren't enemies/bullets rendering" — if
        // realmTotal > 0 but visibleTotal == 0 the bounds query is culling
        // them; if realmTotal == 0 they aren't being added to the realm.
        this.frameCounter++;
        if (this.frameCounter % 300 == 0) {
            int realmEnemies = this.realmManager.getRealm().getEnemies() != null
                    ? this.realmManager.getRealm().getEnemies().size() : 0;
            int realmBullets = this.realmManager.getRealm().getBullets() != null
                    ? this.realmManager.getRealm().getBullets().size() : 0;
            int realmPortals = this.realmManager.getRealm().getPortals() != null
                    ? this.realmManager.getRealm().getPortals().size() : 0;
            // Player census: every 5 seconds, dump the realm's player table
            // with id/name/pos/sprite-loaded for each. Lets us see whether
            // remote players have actually been added by handleLoadClient
            // and whether they have valid sprite sheets — when remote
            // players aren't visible, this tells us if the bug is in the
            // network path (no entries) or the render path (entries exist
            // but spriteSheet is null / pos is off-map / etc.).
            try {
                final long localId = this.realmManager.getCurrentPlayerId();
                final Collection<Player> ps =
                        this.realmManager.getRealm().getPlayers().values();
                final StringBuilder sb = new StringBuilder();
                sb.append("players(").append(ps.size()).append(")=");
                for (Player rp : ps) {
                    String spriteState = "noSprite";
                    if (rp.getSpriteSheet() != null) {
                        try {
                            // Distinguish "sheet exists but no frames" (setAnimSet
                            // failed to find idle_side) from a fully usable sheet.
                            // Without this, both states reported "ok" and the
                            // invisible-player bug looked like a render-path issue.
                            int frameCount = rp.getSpriteSheet().getFrameCount();
                            spriteState = (rp.getSpriteSheet().getCurrentFrame() != null
                                    && frameCount > 0)
                                    ? "ok(" + frameCount + "f)"
                                    : "noFrames(classId=" + rp.getClassId() + ")";
                        } catch (Exception ex) {
                            spriteState = "spriteErr:" + ex.getClass().getSimpleName();
                        }
                    }
                    sb.append('[')
                      .append(rp.getId())
                      .append('|').append(rp.getName())
                      .append('|').append(rp.getId() == localId ? "self" : "remote")
                      .append('|').append(rp.getPos() == null ? "null"
                              : (int) rp.getPos().x + "," + (int) rp.getPos().y)
                      .append('|').append("size=").append(rp.getSize())
                      .append('|').append(spriteState)
                      .append("] ");
                }
                log.info("{} render {}", LOG_NS, sb.toString());
            } catch (Exception ignored) {}
            log.info("{} render realm[enemies={} bullets={} portals={}] viewport[objs={}]",
                    LOG_NS, realmEnemies, realmBullets, realmPortals, gameObject.length);
        }

        // BLIND status — clamp visible radius around the local player. Same
        // semantics as the webclient: enemies, bullets, and other players
        // outside ~3 tiles vanish from the local view. Server stays
        // authoritative on positions; this is pure render-side cull so the
        // player can't see what's about to hit them.
        final Player localBlindPlayer = this.realmManager.getRealm().getPlayer(
                this.realmManager.getCurrentPlayerId());
        final boolean isBlind = localBlindPlayer != null
                && localBlindPlayer.hasEffect(StatusEffectType.BLIND);
        final float BLIND_RADIUS = 32f * 3f;
        // Player CENTER + body-based reach (mirrors the webclient): an entity is
        // culled only when its whole body sits outside the tunnel. Corner math
        // left large enemies invisible even when the player stood on them.
        final float blindHalf = isBlind ? localBlindPlayer.getSize() / 2f : 0f;
        final float blindPx = isBlind ? localBlindPlayer.getPos().x + blindHalf : 0f;
        final float blindPy = isBlind ? localBlindPlayer.getPos().y + blindHalf : 0f;
        final long localBlindId = isBlind ? localBlindPlayer.getId() : 0L;

        // Graphics toggles, read live from Settings each frame.
        final Settings gfx = Settings.get();
        final boolean renderOtherPlayers = gfx.isRenderOtherPlayers();
        final boolean hideOtherBullets = gfx.isHideOtherPlayerBullets();
        final long localPlayerId = this.realmManager.getCurrentPlayerId();

        for (Player p : this.realmManager.getRealm().getPlayers().values()) {
            if (!renderOtherPlayers && p.getId() != localPlayerId) continue;
            if (isBlind && p.getId() != localBlindId) {
                final float half = p.getSize() / 2f;
                final float dx = (p.getPos().x + half) - blindPx, dy = (p.getPos().y + half) - blindPy;
                final float reach = BLIND_RADIUS + half;
                if (dx * dx + dy * dy > reach * reach) continue;
            }
            visibleEntities.add(p);
            p.updateAnimation();
            p.setWading(this.realmManager.getRealm().getTileManager().collidesSlowTile(p));
            // Keep the local player's privilege role sticky: capture it from
            // whatever source supplied it (login or any packet) and restore it
            // if a re-created local entry lost it, so the name color holds.
            if (p.getId() == this.realmManager.getCurrentPlayerId()) {
                final String role = p.getChatRole();
                if (role != null && !role.isEmpty()) {
                    PlayState.localChatRole = role;
                } else if (PlayState.localChatRole != null) {
                    p.setChatRole(PlayState.localChatRole);
                }
            }
        }

        for (int i = 0; i < gameObject.length; i++) {
            if (gameObject[i] instanceof Enemy) {
                Enemy e = (Enemy) gameObject[i];
                if (isBlind) {
                    final float half = e.getSize() / 2f;
                    final float dx = (e.getPos().x + half) - blindPx, dy = (e.getPos().y + half) - blindPy;
                    final float reach = BLIND_RADIUS + half;
                    if (dx * dx + dy * dy > reach * reach) continue;
                }
                visibleEntities.add(e);
                visibleEnemies.add(e);
            } else if (gameObject[i] instanceof Bullet) {
                final Bullet b = (Bullet) gameObject[i];
                // Skip locally-consumed bullets — set by the player-bullet
                // hit prediction in update(). Sprite vanishes but the
                // entry stays in the realm so the server's eventual
                // UnloadPacket cleanly removes it.
                if (b.isConsumedClient()) continue;
                // Hide OTHER players' projectiles (own + enemy shots still show).
                if (hideOtherBullets && b.getSrcEntityId() != localPlayerId
                        && this.realmManager.getRealm().getPlayers().containsKey(b.getSrcEntityId())) continue;
                // BLIND cull — bullets outside the tunnel radius vanish.
                // Local player's OWN bullets are exempt so they can still aim.
                if (isBlind && b.getSrcEntityId() != localBlindId) {
                    final float half = b.getSize() / 2f;
                    final float dx = (b.getPos().x + half) - blindPx, dy = (b.getPos().y + half) - blindPy;
                    final float reach = BLIND_RADIUS + half;
                    if (dx * dx + dy * dy > reach * reach) continue;
                }
                visibleBullets.add(b);
            }
            // Players already added above, skip to avoid double-render
        }

        // Update visual effect state for all entities before rendering
        for (int i = 0; i < visibleEntities.size(); i++) {
            visibleEntities.get(i).updateEffectState();
        }

        // Pass 1.5: Ground shadows. Drawn BEFORE entity bodies so the
        // sprite stands on top of its own shadow, mirroring webclient
        // renderer.js. Three categories share the pass:
        //   - players + enemies (visibleEntities) at alpha 0.30
        //   - decoration collision objects (trees, rocks, statues, river
        //     stones) at alpha 0.25 — matches webclient decoration shadow
        //   - portals + loot containers at alpha 0.35 — matches webclient
        //     billboarded-object shadow
        // ShapeRenderer state swap is paid once and amortized across all
        // three loops, so adding the extra categories is essentially free
        // vs the entities-only baseline.
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        // Entities (players + enemies)
        shapes.setColor(0f, 0f, 0f, 0.30f);
        for (int i = 0; i < visibleEntities.size(); i++) {
            final Entity ent = visibleEntities.get(i);
            final int s = ent.getSize() > 0 ? ent.getSize() : 32;
            final float wx = ent.getPos().getWorldVar().x + s * 0.5f;
            final float wy = ent.getPos().getWorldVar().y + s * 0.92f;
            shapes.ellipse(wx - s * 0.4f, wy - s * 0.06f, s * 0.8f, s * 0.24f);
        }
        // Collision-object shadows (trees, rocks, cacti, statues) are drawn by
        // TileManager Pass 3, UNDER each object sprite. Don't redraw them here —
        // doing so stacked a second oval on top of every object (the cactus/palm
        // double-shadow). Entities, portals, and loot still shadow here because
        // their sprites are drawn after this pass.
        // Portals + loot containers — drawn LATER in the frame after this
        // pass, but the ground shadow needs to render before everything
        // else for the "sprite stands on shadow" stack. Pull the same
        // collection the portal-render loop uses below.
        shapes.setColor(0f, 0f, 0f, 0.35f);
        for (Portal portal : this.realmManager.getRealm().getPortals().values()) {
            if (portal.getPos() == null) continue;
            final int s = 32;
            final float wx = portal.getPos().getWorldVar().x + s * 0.5f;
            final float wy = portal.getPos().getWorldVar().y + s * 0.92f;
            shapes.ellipse(wx - s * 0.4f, wy - s * 0.06f, s * 0.8f, s * 0.24f);
        }
        for (LootContainer lc : this.realmManager.getRealm().getLoot().values()) {
            if (lc.getPos() == null) continue;
            final int s = 16;
            final float wx = lc.getPos().getWorldVar().x + s * 0.5f;
            final float wy = lc.getPos().getWorldVar().y + s * 0.92f;
            shapes.ellipse(wx - s * 0.4f, wy - s * 0.06f, s * 0.8f, s * 0.24f);
        }
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
        batch.begin();

        // Pass 2a: entity sprite strokes (dark silhouette behind every body),
        // drawn with the default shader so the dark tint applies. Gated by the
        // global sprite-stroke toggle. Matches the webclient's per-entity outline.
        if (gfx.isSpriteStroke()) {
            ShaderManager.clearEffect(batch);
            for (int i = 0; i < visibleEntities.size(); i++) {
                visibleEntities.get(i).renderStroke(batch);
            }
        }

        // Pass 2: All entity bodies grouped by effect (minimize shader switches)
        Sprite.EffectEnum currentEffect = null;
        for (int i = 0; i < visibleEntities.size(); i++) {
            Entity e = visibleEntities.get(i);
            Sprite.EffectEnum effect = e.getCurrentEffect();
            if (effect != currentEffect) {
                ShaderManager.applyEffect(batch, effect);
                currentEffect = effect;
            }
            e.renderBody(batch);
        }
        ShaderManager.clearEffect(batch);

        // Tall-wall occlusion: redraw tall walls above the entity bodies so a
        // player/enemy overlapping a wall's footprint is partially covered by
        // it (2.5D depth). Runs before bullets + overhead HP bars so those
        // still draw on top.
        this.realmManager.getRealm().getTileManager().renderTallWallOcclusion(batch);

        // Pass 3: Bullet outlines first (all behind), then bodies on top.
        // Outlines are skipped when the global sprite-stroke toggle is off.
        if (gfx.isSpriteStroke()) {
            for (int i = 0; i < visibleBullets.size(); i++) {
                visibleBullets.get(i).renderOutline(batch);
            }
        }
        // Projectile FX particles (data-driven trails + muzzle/impact bursts),
        // drawn behind the bullet bodies. World-space, same batch as bullets.
        this.projectileFx.emitAndUpdate(visibleBullets,
                this.realmManager.getRealm().getBullets(), Gdx.graphics.getDeltaTime());
        this.projectileFx.render(batch);
        for (int i = 0; i < visibleBullets.size(); i++) {
            visibleBullets.get(i).render(batch);
        }

        // Pass 4: Enemy health bars + Player HP/MP bars (overhead).
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < visibleEnemies.size(); i++) {
            Enemy enemy = visibleEnemies.get(i);
            float wx = enemy.getPos().getWorldVar().x;
            float wy = enemy.getPos().getWorldVar().y;
            int barWidth = enemy.getSize();
            int barHeight = 4;
            // Below the sprite (webclient parity): +Y is screen-down, so wy + size
            // is the sprite's bottom edge. The enemy name sits ABOVE the head, so
            // nothing competes for the space directly under the sprite.
            float barY = wy + enemy.getSize() + 2;
            shapes.setColor(0.2f, 0.2f, 0.2f, 0.8f);
            shapes.rect(wx, barY, barWidth, barHeight);
            shapes.setColor(1f, 0f, 0f, 0.9f);
            shapes.rect(wx, barY, barWidth * enemy.getHealthpercent(), barHeight);
        }
        // Player HP + MP nameplate bars. Mirrors webclient renderer.js
        // _drawPlayerHpMp (~line 1280): two stacked 4px bars below the
        // sprite — green HP, blue MP — with a darker background. Drawn
        // before the name text so the bars sit cleanly underneath.
        //
        // Anchor on getEffectiveRenderX/Y (same source the sprite +
        // nameplate use) NOT the raw pos.getWorldVar(). The sprite
        // extrapolates between ticks (renderX = pos + frac × lastTickStep)
        // while the simulated pos snaps in tick-sized increments, so a
        // bar tracking pos visibly oscillates against the smoothly-
        // moving sprite. The same fix was applied to the nameplate
        // text below; HP/MP bars and status icons were missed.
        // Cast overlay — opaque grey rectangle filling the casting player's
        // sprite bottom→top as the cast advances. Visible on every player
        // in the realm (including party members) so the caster has a clear
        // commitment cue and observers can read who's mid-cast. Auto-clears
        // when the cast duration elapses (no explicit cast-finish packet).
        if (this.activeCasts != null && !this.activeCasts.isEmpty()) {
            final long now = System.currentTimeMillis();
            for (Player rp : this.realmManager.getRealm().getPlayers().values()) {
                final long[] cast = this.activeCasts.get(rp.getId());
                if (cast == null || cast.length < 2) continue;
                final long elapsedMs = now - cast[0];
                final long durMs = cast[1];
                if (durMs <= 0 || elapsedMs >= durMs) {
                    this.activeCasts.remove(rp.getId());
                    continue;
                }
                final float pct = Math.max(0f, Math.min(1f, elapsedMs / (float) durMs));
                final int s = rp.getSize() > 0 ? rp.getSize() : 32;
                final float wx = rp.getEffectiveRenderX() - Vector2f.worldX;
                final float wy = rp.getEffectiveRenderY() - Vector2f.worldY;
                // Sprite sits in roughly [wx, wx+s] × [wy, wy+s]. Fill from
                // the bottom (wy+s, larger Y in libGDX Y-down screen coords)
                // upward as pct increases — same direction as a tank UI
                // cast bar. Translucent so the sprite is still readable.
                final float fillH = s * pct;
                shapes.setColor(0f, 0f, 0f, 0.55f);
                shapes.rect(wx, wy + s - fillH, s, fillH);
            }
        }
        for (Player rp : this.realmManager.getRealm().getPlayers().values()) {
            final int s = rp.getSize() > 0 ? rp.getSize() : 32;
            final float wx = rp.getEffectiveRenderX() - Vector2f.worldX;
            final float wy = rp.getEffectiveRenderY() - Vector2f.worldY;
            final int barW = s;
            final int barH = 3;
            final int barGap = 1;
            // Below the sprite, top->bottom: NAME, then HP bar, then MP bar. Y is
            // screen-down, so wy + s is the sprite's bottom edge; leave room above
            // the bars for the name (drawn in the nameplate pass at wy + s + 2).
            final float hpY = wy + s + 16;
            final float mpY = hpY + barH + barGap;
            float hpPct = 0f;
            float mpPct = 0f;
            try {
                int maxHp = rp.getStats() != null ? rp.getStats().getHp() : 0;
                int maxMp = rp.getStats() != null ? rp.getStats().getMp() : 0;
                if (maxHp > 0) hpPct = Math.max(0f, Math.min(1f, rp.getHealth() / (float) maxHp));
                if (maxMp > 0) mpPct = Math.max(0f, Math.min(1f, rp.getMana() / (float) maxMp));
            } catch (Exception ignored) {}
            shapes.setColor(0.13f, 0.13f, 0.13f, 0.78f);
            shapes.rect(wx, hpY, barW, barH);
            shapes.setColor(0.25f, 0.78f, 0.25f, 0.92f);
            shapes.rect(wx, hpY, barW * hpPct, barH);
            shapes.setColor(0.13f, 0.13f, 0.13f, 0.78f);
            shapes.rect(wx, mpY, barW, barH);
            shapes.setColor(0.25f, 0.50f, 0.88f, 0.92f);
            shapes.rect(wx, mpY, barW * mpPct, barH);
        }

        // Status-effect chips stacked above each player's nameplate.
        // Port of webclient _drawStatusIcons (renderer.js ~5512): 40x14
        // pill-shaped chips, bottommost just above the head, additional
        // effects stack upward. Chip BACKGROUND is drawn here (shapes
        // pass); the abbreviation TEXT is drawn in a follow-up batch pass
        // below so font.draw can lay glyphs over the colored body. Cache
        // per-chip layout coords during this loop so the text pass
        // doesn't have to recompute them.
        final List<float[]> _statusChipLayout = new ArrayList<>();
        final List<String>  _statusChipLabels = new ArrayList<>();
        for (Player rp : gfx.isShowStatusBubbles()
                ? this.realmManager.getRealm().getPlayers().values()
                : Collections.<Player>emptyList()) {
            final Short[] effs = rp.getEffectIds();
            if (effs == null) continue;
            final int sSize = rp.getSize() > 0 ? rp.getSize() : 32;
            // Same render-anchor as the HP/MP bars and nameplate above
            // so status icons don't oscillate against the moving sprite.
            final float wx = rp.getEffectiveRenderX() - Vector2f.worldX;
            final float wy = rp.getEffectiveRenderY() - Vector2f.worldY;
            // Webclient chips are 40x14 SCREEN pixels. Native renders here
            // through the world camera which has WORLD_SCALE=2× zoom — so
            // 40 world units = 80 actual pixels. Divide by WORLD_SCALE to
            // match the webclient's on-screen size. Same for the vertical
            // gap and the bottom-anchor offset.
            final float WS = OpenRealmGame.WORLD_SCALE;
            final float iconW = 40f / WS;
            final float iconH = 14f / WS;
            final float iconGap = 2f / WS;
            final float iconX = wx + (sSize * 0.5f) - (iconW * 0.5f);
            // Stack the chips ABOVE the nameplate (not just above the HP bar).
            // Nameplate is rendered later with the world batch at y =
            // wy - 12 - layoutHeight, where layoutHeight ≈ 8 world units at
            // the 0.5× font scale. Without this extra ~11 unit lift the
            // bottommost chip sat directly behind the name glyphs and the
            // later batch.draw painted the text on top of the icon — the
            // exact "icons hidden behind name" symptom the user reported.
            final float bottomY = wy - 22f / WS - 11f;
            int activeIdx = 0;
            for (StatusEffectIconDef def : STATUS_ICON_DEFS) {
                if (!hasEffectId(effs, def.effectId)) continue;
                // bottommost chip is idx 0, additional effects stack upward
                // (Y-up in libGDX: -Y in our flipped world cam → "upward").
                final float chipY = bottomY - (activeIdx + 1) * (iconH + iconGap);
                // Black border + drop shadow for legibility
                shapes.setColor(0f, 0f, 0f, 0.85f);
                shapes.rect(iconX - 1, chipY - 1, iconW + 2, iconH + 2);
                // Coloured body (effect identity)
                shapes.setColor(def.r, def.g, def.b, 0.92f);
                shapes.rect(iconX, chipY, iconW, iconH);
                // Highlight strip along the top edge for polish
                shapes.setColor(1f, 1f, 1f, 0.18f);
                shapes.rect(iconX + 1, chipY + iconH - 4f, iconW - 2, 3f);
                _statusChipLayout.add(new float[] { iconX, chipY, iconW, iconH });
                _statusChipLabels.add(def.label);
                activeIdx++;
            }
        }

        // Enemy status-effect chips — same pooled shapes/labels pass as players,
        // anchored above the enemy head (lifted one row when a name label shows so
        // the chips clear it). Mirrors webclient renderEnemy status-icon block.
        for (Enemy en : gfx.isShowStatusBubbles() ? visibleEnemies
                : Collections.<Enemy>emptyList()) {
            final Short[] effs = en.getEffectIds();
            if (effs == null) continue;
            final int sSize = en.getSize() > 0 ? en.getSize() : 32;
            final float wx = en.getPos().getWorldVar().x;
            final float wy = en.getPos().getWorldVar().y;
            final float WS = OpenRealmGame.WORLD_SCALE;
            final float iconW = 40f / WS;
            final float iconH = 14f / WS;
            final float iconGap = 2f / WS;
            final float iconX = wx + (sSize * 0.5f) - (iconW * 0.5f);
            final boolean named = this.shouldLabelEnemy(en);
            final float bottomY = wy - 4f - (named ? 16f / WS : 0f);
            int activeIdx = 0;
            for (StatusEffectIconDef def : STATUS_ICON_DEFS) {
                if (!hasEffectId(effs, def.effectId)) continue;
                final float chipY = bottomY - (activeIdx + 1) * (iconH + iconGap);
                shapes.setColor(0f, 0f, 0f, 0.85f);
                shapes.rect(iconX - 1, chipY - 1, iconW + 2, iconH + 2);
                shapes.setColor(def.r, def.g, def.b, 0.92f);
                shapes.rect(iconX, chipY, iconW, iconH);
                shapes.setColor(1f, 1f, 1f, 0.18f);
                shapes.rect(iconX + 1, chipY + iconH - 4f, iconW - 2, 3f);
                _statusChipLayout.add(new float[] { iconX, chipY, iconW, iconH });
                _statusChipLabels.add(def.label);
                activeIdx++;
            }
        }

        // Chat bubble BACKGROUNDS — white rounded boxes drawn behind the bubble
        // text (the text itself is drawn in the nameplate batch pass below).
        // Uses the same shapes-then-batch split as the status chips so the
        // ShapeRenderer is never interleaved with the SpriteBatch. Geometry
        // mirrors the bubble text formula in the nameplate loop exactly.
        if (gfx.isShowChatBubbles()) {
            final long now = System.currentTimeMillis();
            final float ws = OpenRealmGame.WORLD_SCALE;
            final float padX = 8f / ws;
            final float padY = 5f / ws;
            final float radius = 8f / ws;
            final float prevScale = font.getData().scaleX;
            font.getData().setScale(0.5f);
            for (Player rp : this.realmManager.getRealm().getPlayers().values()) {
                final String nm = rp.getName();
                if (nm == null || nm.isEmpty()) continue;
                final ChatBubble bubble = this.chatBubbles.get(nm);
                if (bubble == null || bubble.isExpired(now)) continue;
                final int sSize = rp.getSize() > 0 ? rp.getSize() : 32;
                final float wx = rp.getEffectiveRenderX() - Vector2f.worldX;
                final float wy = rp.getEffectiveRenderY() - Vector2f.worldY;
                this.nameLayoutScratch.setText(font, nm);
                final float nameH = this.nameLayoutScratch.height;
                this.chatBubbleLayoutScratch.setText(font, bubble.getMessage());
                final float chatW = this.chatBubbleLayoutScratch.width;
                final float chatH = this.chatBubbleLayoutScratch.height;
                final float textTopY = wy - 12 - nameH - 4 - chatH;
                final float bgW = chatW + 2 * padX;
                final float bgH = chatH + 2 * padY;
                final float bgX = wx + (sSize * 0.5f) - (bgW * 0.5f);
                final float bgY = textTopY - chatH - padY;
                shapes.setColor(1f, 1f, 1f, 0.95f * bubble.alpha(now));
                this.drawRoundedRect(shapes, bgX, bgY, bgW, bgH, radius);
            }
            font.getData().setScale(prevScale);
        }
        shapes.end();

        // Status-chip label pass — draw abbreviations centered inside
        // each chip we just painted. Smaller-than-default scale so the
        // 4-char labels fit inside a 40-wide chip.
        if (!_statusChipLayout.isEmpty()) {
            batch.begin();
            final float prevScale = font.getData().scaleX;
            // Font scale also halved (chips are now 1/WORLD_SCALE size so
            // text-to-chip ratio stays the same as the previous tuning).
            font.getData().setScale(0.45f / OpenRealmGame.WORLD_SCALE);
            for (int idx = 0; idx < _statusChipLayout.size(); idx++) {
                final float[] r = _statusChipLayout.get(idx);
                final String label = _statusChipLabels.get(idx);
                this.nameLayoutScratch.setText(font, label);
                font.setColor(Color.WHITE);
                // Flipped world ortho: font.draw y is the TOP of the text and
                // glyphs extend downward (+y). Center vertically by insetting
                // the top edge half the leftover height inside the chip.
                final float tx = r[0] + (r[2] - this.nameLayoutScratch.width) * 0.5f;
                final float ty = r[1] + (r[3] - this.nameLayoutScratch.height) * 0.5f;
                font.draw(batch, this.nameLayoutScratch, tx, ty);
            }
            font.getData().setScale(prevScale);
            font.setColor(Color.WHITE);
            batch.end();
            // Leave shapes ENDED to match the original flow that the
            // following renderVisualEffects pass expects (it manages its
            // own begin/end pairs).
        }

        // Pass 5: Visual ability effects (rings, arcs, particles). Gated by the
        // non-projectile ability-animation toggle.
        if (gfx.isPlayAbilityAnimations()) {
            this.renderVisualEffects(shapes);
        }
        // Lock-on reticles over entities targeted by live HOMING projectiles.
        this.renderLockOnReticles(shapes);
        // Melee aim indicator (raindrop ripple) at the cursor, clamped to range.
        this.renderMeleeAimReticle(shapes);

        Gdx.gl.glDisable(GL20.GL_BLEND);
        batch.begin();
        // Pass 5b: Ninja shuriken visuals — BLADE_ORBIT + BLADE_BLENDER both
        // need REAL shuriken sprites (not shape primitives) to match the
        // item icons. Drawn inside the open batch so they Z-sort with
        // entities + nameplate text below.
        if (gfx.isPlayAbilityAnimations()) {
            this.renderShurikenEffects(batch);
            this.renderMeleeSwings(batch);
        }

        // Player nameplates — rendered with the world-camera batch so the
        // text anchors to the entity. Font is dropped to 0.5x so the
        // nameplate matches the webclient's small overhead label
        // (~10-12px) instead of the default 16-20px which covered the
        // whole sprite. Color follows chatRole exactly like webclient
        // renderer.js getNameColorHex (sysadmin/red, admin/blue,
        // mod/green, editor/purple, demo/gray, default/off-white).
        // Use GlyphLayout to center the name horizontally on the sprite.
        final float origScale = font.getData().scaleX;
        font.getData().setScale(0.5f);
        final long bubbleNowMs = System.currentTimeMillis();
        this.chatBubbles.values().removeIf(b -> b.isExpired(bubbleNowMs));
        for (Player rp : this.realmManager.getRealm().getPlayers().values()) {
            final String nm = rp.getName();
            if (nm == null || nm.isEmpty()) continue;
            final int s = rp.getSize() > 0 ? rp.getSize() : 32;
            // Use the LERPED render position (same source as the sprite
            // and HP bar use) instead of raw pos. Was reading
            // rp.getPos().getWorldVar() which is the post-tick sim
            // position — that snaps in tick-sized increments while the
            // sprite (which uses getEffectiveRenderX) walks smoothly,
            // so the nameplate visibly jittered above the moving
            // sprite. With this change the nameplate locks frame-for-
            // frame to the same coords the sprite renders at.
            final float wx = rp.getEffectiveRenderX() - Vector2f.worldX;
            final float wy = rp.getEffectiveRenderY() - Vector2f.worldY;
            this.nameLayoutScratch.setText(font, nm);
            font.setColor(roleColorFor(rp.getChatRole()));
            // Name sits just BELOW the sprite and ABOVE the HP/MP bars (top-anchored
            // in flipped ortho, so it extends downward into the reserved gap).
            if (gfx.isShowPlayerNames()) {
                font.draw(batch, this.nameLayoutScratch,
                        wx + (s * 0.5f) - (this.nameLayoutScratch.width * 0.5f),
                        wy + s + 2);
            }
            // Chat bubble floats just above the nameplate, fading out at end of life.
            final ChatBubble bubble = gfx.isShowChatBubbles() ? this.chatBubbles.get(nm) : null;
            if (bubble != null && !bubble.isExpired(bubbleNowMs)) {
                this.chatBubbleLayoutScratch.setText(font, bubble.getMessage());
                // Dark text for contrast on the white bubble background (drawn
                // in the earlier shapes pass).
                font.setColor(0.10f, 0.10f, 0.10f, bubble.alpha(bubbleNowMs));
                font.draw(batch, this.chatBubbleLayoutScratch,
                        wx + (s * 0.5f) - (this.chatBubbleLayoutScratch.width * 0.5f),
                        wy - 12 - this.nameLayoutScratch.height - 4 - this.chatBubbleLayoutScratch.height);
            }
        }
        // Enemy names intentionally not drawn — enemies are identified by their
        // overhead health bar only (Pass 4). Status chips still render below.
        font.getData().setScale(origScale);
        font.setColor(Color.WHITE);

        Collection<Portal> portals = this.realmManager.getRealm().getPortals().values();
        final float prevPortalScale = font.getData().scaleX;
        final Player me = this.getPlayer();
        for (Portal portal : portals) {
            portal.render(batch);
            final String portalLabel = portal.getTargetLabel();
            if (portalLabel == null || portalLabel.isEmpty()) continue;
            // Under-portal info: minimal (name + tier/difficulty badge) by default; the portal the
            // player stands on expands into a full card with purification + modifiers.
            font.getData().setScale(0.5f);
            final float diff = portal.getTargetDifficulty();
            final int tier = portal.getTargetTier();
            final String diffStr = (diff == Math.floor(diff)) ? Integer.toString((int) diff) : String.format("%.1f", diff);
            final String badge = tier > 1 ? "T" + tier : (diff > 0f ? "D" + diffStr : "");

            boolean focused = false;
            if (me != null && me.getPos() != null) {
                final float dx = (portal.getPos().x + 16f) - (me.getPos().x + 16f);
                final float dy = (portal.getPos().y + 16f) - (me.getPos().y + 16f);
                focused = (dx * dx + dy * dy) <= (42f * 42f);
            }

            final StringBuilder info = new StringBuilder();
            if (focused) {
                info.append(badge.isEmpty() ? portalLabel : (portalLabel + "   [" + badge + "]"));
                if (diff > 0f) info.append("\nDifficulty ").append(diffStr);
                if (portal.getTargetPlayerCount() >= 0) {
                    info.append("  -  ").append(portal.getTargetPlayerCount()).append(" in realm");
                }
                if (portal.getTargetPurificationGoal() > 0L) {
                    final int pct = (int) Math.max(0, Math.min(100,
                            portal.getTargetPurificationProgress() * 100L / portal.getTargetPurificationGoal()));
                    info.append("\nPurified ").append(pct).append('%');
                }
                final String mods = portal.getTargetModifiers();
                if (mods != null && !mods.isEmpty()) {
                    info.append('\n');
                    final String[] parts = mods.split(",");
                    for (int i = 0; i < parts.length; i++) {
                        if (i > 0) info.append(' ');
                        info.append('[').append(parts[i].trim()).append(']');
                    }
                } else if (tier > 1) {
                    info.append("\nModifiers revealed on entry");
                }
            } else {
                info.append(badge.isEmpty() ? portalLabel : (portalLabel + "  -  " + badge));
            }

            this.nameLayoutScratch.setText(font, info.toString());
            if (tier > 1) font.setColor(1f, 0.60f, 0.42f, 1f);
            else font.setColor(0.62f, 0.90f, 0.75f, 1f);
            final float bx = portal.getPos().getWorldVar().x;
            final float by = portal.getPos().getWorldVar().y;
            font.draw(batch, this.nameLayoutScratch,
                    bx + 16f - this.nameLayoutScratch.width * 0.5f, by + 36f);
        }
        font.getData().setScale(prevPortalScale);
        font.setColor(Color.WHITE);

        // Loot bags must render with the WORLD camera projection active —
        // LootContainer.render uses pos.getWorldVar() to manually transform
        // to camera-relative coordinates, then relies on the world projection
        // for the screen mapping. Previously the lc.render() loop lived in
        // renderCloseLoot which the caller invokes AFTER switching the batch
        // to the UI camera (1:1 screen pixels), so bags drew at half-scale,
        // un-scaled screen coordinates that read as "random spots" relative
        // to the actual map tiles.
        for (LootContainer lc : this.realmManager.getRealm().getLoot().values()) {
            lc.render(batch);
        }

        // Read-only loot bag preview — small item grid under each bag. Never
        // interacts with pickup; strictly a display aid, off by default.
        if (gfx.isLootBagPreview()) {
            this.renderLootBagPreviews(batch, shapes);
        }

        if (this.pui == null)
            return;

        // Damage text uses WORLD coords (sourcePos - Vector2f.worldX/Y),
        // so render it BEFORE flipping to the UI camera. Otherwise the
        // numbers paint at world-pixel positions through the UI projection
        // — which puts a hit that occurred at world (300, 200) at screen
        // (300, 200) instead of at the actual sprite location. Flush the
        // batch first so any prior world-space draws complete before the
        // next pass starts.
        if (gfx.isShowDamageNumbers()) {
            for (EffectText text : this.getDamageText()) {
                text.render(batch, font);
            }
        }

        if (game.getUiCamera() != null) {
            game.getUiCamera().update();
            batch.setProjectionMatrix(game.getUiCamera().combined);
            shapes.setProjectionMatrix(game.getUiCamera().combined);
            batch.setTransformMatrix(this.worldTransformIdt);
            shapes.setTransformMatrix(this.worldTransformIdt);
        }
        // Blind vignette darkens the periphery down to the choked render range.
        // Drawn before the HUD so the panel/minimap stay fully lit.
        if (isBlind) this.renderBlindVignette(batch, shapes);
        this.pui.render(batch, shapes, font);

        this.renderCloseLoot(batch);

        // Client-side /dev overlay: FPS / ping / jitter / resolution bar pinned
        // above the minimap (toggled by the /dev chat command). batch is active here.
        if (PerfMetrics.get().isDevVisible()) {
            final Minimap devMinimap = this.pui.getMinimap();
            PerfMetrics.get().renderDevBar(batch, font,
                    devMinimap.getDrawX(), devMinimap.getDrawY(), devMinimap.getSizePx());
        }

        if (this.debugMode) {
            this.renderDebugTileOverlay(batch, shapes, font, player);
        }

        // FPS overlay removed — was overlapping with the new sprite-HUD's
        // top-left preview panel (player name + bars). Re-enable behind a
        // debug flag if needed.
    }

    private void renderDebugTileOverlay(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font, Player player) {
        int mx = Gdx.input.getX();
        int my = Gdx.input.getY();

        // Convert screen coords to world coords
        float worldX = mx + PlayState.map.x;
        float worldY = my + PlayState.map.y;

        int tileSize = GlobalConstants.BASE_TILE_SIZE;
        int tileCol = (int) (worldX / tileSize);
        int tileRow = (int) (worldY / tileSize);

        TileMap baseLayer = this.realmManager.getRealm().getTileManager().getBaseLayer();
        TileMap collisionLayer = this.realmManager.getRealm().getTileManager().getCollisionLayer();

        if (tileCol < 0 || tileCol >= baseLayer.getWidth() || tileRow < 0 || tileRow >= baseLayer.getHeight()) {
            return;
        }

        // Get tiles at hovered position
        Tile baseTile = baseLayer.getBlocks()[tileRow][tileCol];
        Tile collTile = collisionLayer.getBlocks()[tileRow][tileCol];

        // Draw green outline around hovered tile in world space
        float drawX = (tileCol * tileSize) - PlayState.map.x;
        float drawY = (tileRow * tileSize) - PlayState.map.y;

        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // Filled green tint
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 1f, 0f, 0.15f);
        shapes.rect(drawX, drawY, tileSize, tileSize);
        shapes.end();

        // Green border
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0f, 1f, 0f, 1f);
        shapes.rect(drawX, drawY, tileSize, tileSize);
        shapes.end();

        // Build tooltip text
        int tooltipX = mx + 16;
        int tooltipY = my + 16;
        int lineHeight = 16;
        int padding = 6;

        List<String> lines = new ArrayList<>();
        lines.add("Tile [" + tileCol + ", " + tileRow + "]");

        if (baseTile != null && !baseTile.isVoid()) {
            String baseName = "ID " + baseTile.getTileId();
            TileModel baseModel = GameDataManager.TILES.get((int) baseTile.getTileId());
            if (baseModel != null && baseModel.getName() != null) {
                baseName = baseModel.getName() + " (" + baseTile.getTileId() + ")";
            }
            lines.add("Base: " + baseName);
        } else {
            lines.add("Base: void");
        }

        if (collTile != null && !collTile.isVoid()) {
            String collName = "ID " + collTile.getTileId();
            TileModel collModel = GameDataManager.TILES.get((int) collTile.getTileId());
            if (collModel != null && collModel.getName() != null) {
                collName = collModel.getName() + " (" + collTile.getTileId() + ")";
            }
            lines.add("Collision: " + collName);
        }

        // Show tile data flags
        TileData data = null;
        if (collTile != null && collTile.getData() != null && collTile.getData().hasCollision()) {
            data = collTile.getData();
        } else if (baseTile != null && baseTile.getData() != null) {
            data = baseTile.getData();
        }

        if (data != null) {
            List<String> flags = new ArrayList<>();
            if (data.hasCollision()) flags.add("COLLISION");
            if (data.slows()) flags.add("SLOWS");
            if (data.damaging()) flags.add("DAMAGING");
            if (!flags.isEmpty()) {
                lines.add("Flags: " + String.join(", ", flags));
            }
        }

        int tooltipWidth = 0;
        for (String line : lines) {
            tooltipWidth = Math.max(tooltipWidth, line.length() * 7 + padding * 2);
        }
        int tooltipHeight = padding * 2 + lines.size() * lineHeight;

        // Clamp tooltip to screen
        if (tooltipX + tooltipWidth > OpenRealmGame.width) {
            tooltipX = mx - tooltipWidth - 8;
        }
        if (tooltipY + tooltipHeight > OpenRealmGame.height) {
            tooltipY = my - tooltipHeight - 8;
        }

        // Draw tooltip background
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.1f, 0.1f, 0.12f, 0.92f);
        shapes.rect(tooltipX, tooltipY, tooltipWidth, tooltipHeight);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0f, 0.8f, 0f, 1f);
        shapes.rect(tooltipX, tooltipY, tooltipWidth, tooltipHeight);
        shapes.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
        batch.begin();

        // Draw tooltip text
        font.setColor(Color.GREEN);
        for (int i = 0; i < lines.size(); i++) {
            font.draw(batch, lines.get(i), tooltipX + padding, tooltipY + padding + lineHeight + (i * lineHeight));
        }
        font.setColor(Color.WHITE);
    }

    public void renderCloseLoot(SpriteBatch batch) {
        Player player = this.realmManager.getRealm().getPlayer(this.playerId);
        if (player == null)
            return;

        // Note: bag sprite rendering moved to the world-camera section of
        // render() (next to portals). This method now only handles the HUD
        // ground-loot panel sync — the closest bag's contents pump into the
        // bottom-right inventory bag overlay.

        // Skip normal loot container logic while trading - trade UI manages ground loot area
        if (this.getPui().isTrading()) {
            return;
        }

        // Match the server's ground-loot pickup radius (player.getSize() +
        // 24) so the loot panel only surfaces a bag whose items the
        // server will accept clicks for. Used to be size/2 (~14px), so
        // a bag could appear in the UI but be just outside server
        // pickup range — clicks looked like no-ops.
        final int lootSearchRadius = player.getSize() + 24;
        final LootContainer closeLoot = this.getClosestLootContainer(player.getPos(), lootSearchRadius);

        if ((closeLoot != null && this.getPui().isGroundLootEmpty()) || (closeLoot != null && closeLoot.getContentsChanged())) {
            this.getPui().setGroundLoot(closeLoot.getItems());
        } else if ((closeLoot == null) && !this.getPui().isGroundLootEmpty()) {
            this.getPui().setGroundLoot(new GameItem[10]);
        }

        if (closeLoot != null && !this.getPui().isGroundLootEmpty()) {
            // Diff by itemId + stackCount, NOT just count — partial
            // pickups of a stack don't change the slot count but do
            // change stackCount, and the previous count-only check
            // missed those (so the bag visually still showed the full
            // stack even after the player took 5 of 10).
            if (this.lootDiffersFromUI(closeLoot)) {
                this.getPui().setGroundLoot(closeLoot.getItems());
            }
        }
    }

    /** True if the loot container's current items differ in count, item
     *  id, or stack count from the cached groundLoot UI snapshot.
     *  IMPORTANT: setGroundLoot skips items where item==null OR itemId==-1
     *  (the empty-slot sentinel), so the UI snapshot has null Slots in
     *  those positions. The diff MUST treat both representations as
     *  equivalent — otherwise it returns true every frame, setGroundLoot
     *  is called every render, and the freshly-built Buttons reset to
     *  legacy positions BEFORE the next input tick reads their bounds.
     *  Result: the user clicks the visible (sprite-HUD-positioned) bag
     *  but the bounds are still at the off-screen legacy coords from
     *  the rebuild — every click misses, no handler ever fires.
     *  This was the actual cause of 'loot pickup never works'. */
    private boolean lootDiffersFromUI(LootContainer closeLoot) {
        final Slots[] uiSlots = this.getPui().getGroundLoot();
        final GameItem[] lcItems = closeLoot.getItems();
        if (uiSlots == null || lcItems == null) return true;
        final int n = Math.min(uiSlots.length, lcItems.length);
        for (int i = 0; i < n; i++) {
            final GameItem ui  = (uiSlots[i] != null) ? uiSlots[i].getItem() : null;
            final GameItem srcRaw = lcItems[i];
            // Treat itemId==-1 as the empty sentinel so it lines up with
            // setGroundLoot's empty-slot skip.
            final GameItem src = (srcRaw == null || srcRaw.getItemId() == -1)
                    ? null : srcRaw;
            if (ui == null && src == null) continue;
            if (ui == null || src == null) return true;
            if (ui.getItemId() != src.getItemId()) return true;
            if (ui.getStackCount() != src.getStackCount()) return true;
        }
        return false;
    }

    public void handleQuickUseKey(int slotIndex) {
        try {
            GameItem from = this.getPlayer().getInventory()[slotIndex];
            if (from == null) return;
            boolean consume = from.isConsumable();
            MoveItemPacket moveItem = MoveItemPacket.from(from.getTargetSlot(), (byte) slotIndex, false, consume);
            this.realmManager.getClient().sendRemote(moveItem);
        } catch (Exception e) {
            PlayState.log.error("{} failed to send move item packet: {}", LOG_NS, "No Item in slot");
        }
    }

    public Player getPlayer() {
        return this.realmManager.getRealm().getPlayer(this.playerId);
    }

    public long[] getSkillXp() {
        return this.skillXp;
    }

    public void setSkillXp(final long[] skillXp) {
        if (skillXp != null && skillXp.length == this.skillXp.length) {
            this.skillXp = skillXp;
        }
    }

    /** Horde name-cull: label all enemies below the threshold, else only those
     *  near the local player. Mirrors webclient renderer.js _shouldLabelEnemy. */
    private boolean shouldLabelEnemy(Enemy enemy) {
        final var enemies = this.realmManager.getRealm().getEnemies();
        if (enemies == null || enemies.size() <= NAME_HORDE_THRESHOLD) return true;
        final Player lp = this.getPlayer();
        if (lp == null || lp.getPos() == null) return false;
        final float dx = enemy.getPos().getWorldVar().x - lp.getPos().getWorldVar().x;
        final float dy = enemy.getPos().getWorldVar().y - lp.getPos().getWorldVar().y;
        return dx * dx + dy * dy <= NAME_HORDE_RADIUS_SQ;
    }

    private static Color roleColorFor(String role) {
        if (role == null) return ROLE_DEFAULT;
        switch (role.trim().toLowerCase()) {
            case "sysadmin": return ROLE_SYSADMIN;
            case "admin":    return ROLE_ADMIN;
            case "mod":      return ROLE_MOD;
            case "editor":   return ROLE_EDITOR;
            case "demo":     return ROLE_DEMO;
            default:         return ROLE_DEFAULT;
        }
    }

    private static boolean hasEffectId(Short[] effs, short eid) {
        if (effs == null) return false;
        for (Short s : effs) {
            if (s != null && s == eid) return true;
        }
        return false;
    }

    private TextureRegion getShurikenRegion(int tier) {
        if (_shurikenRegions == null) _shurikenRegions = new TextureRegion[6];
        final int t = Math.max(0, Math.min(5, tier));
        if (_shurikenRegions[t] != null) return _shurikenRegions[t];
        try {
            Texture tex =
                    GameSpriteManager.TEXTURE_CACHE.get("openrealm-items.png");
            if (tex == null) return null;
            final int sw = GlobalConstants.BASE_SPRITE_SIZE;
            TextureRegion reg = new TextureRegion(tex, (10 + t) * sw, 16 * sw, sw, sw);
            reg.flip(false, true);
            _shurikenRegions[t] = reg;
            return reg;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Read-only loot-bag preview: a small item grid under each ground loot
     * container (world-camera space). Dark backgrounds first (ShapeRenderer),
     * then item icons (SpriteBatch). Purely visual — no pickup interaction.
     * Mirrors the webclient renderer.js renderLootPreviews.
     */
    private void renderLootBagPreviews(SpriteBatch batch, ShapeRenderer shapes) {
        final float WS = OpenRealmGame.WORLD_SCALE;
        final float CELL = 13f / WS, ICON = 10f / WS, PAD = 2f / WS;
        final int COLS = 5, MAXN = COLS * 2;

        // Backgrounds.
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (LootContainer lc : this.realmManager.getRealm().getLoot().values()) {
            if (lc.getPos() == null || lc.getItems() == null) continue;
            final int n = Math.min(countLootItems(lc), MAXN);
            if (n == 0) continue;
            final int cols = Math.min(n, COLS);
            final int rows = (n + COLS - 1) / COLS;
            final float gridW = cols * CELL, gridH = rows * CELL;
            final float bx = lc.getPos().getWorldVar().x, by = lc.getPos().getWorldVar().y;
            final float gx = bx + 8f - gridW / 2f, gy = by + 20f;
            shapes.setColor(0.04f, 0.04f, 0.05f, 0.72f);
            shapes.rect(gx - PAD, gy - PAD, gridW + 2 * PAD, gridH + 2 * PAD);
        }
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
        batch.begin();

        // Item icons.
        for (LootContainer lc : this.realmManager.getRealm().getLoot().values()) {
            if (lc.getPos() == null || lc.getItems() == null) continue;
            final int n = Math.min(countLootItems(lc), MAXN);
            if (n == 0) continue;
            final int cols = Math.min(n, COLS);
            final float gridW = cols * CELL;
            final float bx = lc.getPos().getWorldVar().x, by = lc.getPos().getWorldVar().y;
            final float gx = bx + 8f - gridW / 2f, gy = by + 20f;
            int drawn = 0;
            for (GameItem it : lc.getItems()) {
                if (it == null || it.getItemId() < 0) continue;
                if (drawn >= MAXN) break;
                final TextureRegion region = (GameSpriteManager.ITEM_SPRITES != null)
                        ? GameSpriteManager.ITEM_SPRITES.get(it.getItemId()) : null;
                final int col = drawn % COLS, row = drawn / COLS;
                if (region != null) {
                    final float ix = gx + col * CELL + (CELL - ICON) / 2f;
                    final float iy = gy + row * CELL + (CELL - ICON) / 2f;
                    batch.draw(region, ix, iy, ICON, ICON);
                }
                drawn++;
            }
        }
    }

    private static int countLootItems(LootContainer lc) {
        int n = 0;
        for (GameItem it : lc.getItems()) {
            if (it != null && it.getItemId() >= 0) n++;
        }
        return n;
    }

    /**
     * Screen-space blind vignette: a clear circular tunnel of radius
     * BLIND_RADIUS × WORLD_SCALE around the (centered) player, fading to dark
     * toward the edges. Reflects the choked bullet/enemy render range. Drawn
     * with the UI camera active so it maps 1:1 to screen pixels.
     */
    private void renderBlindVignette(SpriteBatch batch, ShapeRenderer shapes) {
        final float w = OpenRealmGame.width, h = OpenRealmGame.height;
        final float cx = w / 2f, cy = h / 2f; // player is centered in the world viewport
        final float innerR = 32f * 3f * OpenRealmGame.WORLD_SCALE; // choked render range
        final float fadeR = innerR + 180f;
        final float cornerR = (float) Math.hypot(Math.max(cx, w - cx), Math.max(cy, h - cy)) + 4f;
        final Color clear = new Color(0f, 0f, 0f, 0f);
        final Color dark = new Color(0f, 0f, 0f, 0.94f);

        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int SEG = 64;
        for (int i = 0; i < SEG; i++) {
            final double a0 = i * 2.0 * Math.PI / SEG;
            final double a1 = (i + 1) * 2.0 * Math.PI / SEG;
            final float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
            final float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            final float ix0 = cx + innerR * c0, iy0 = cy + innerR * s0;
            final float ix1 = cx + innerR * c1, iy1 = cy + innerR * s1;
            final float fx0 = cx + fadeR * c0, fy0 = cy + fadeR * s0;
            final float fx1 = cx + fadeR * c1, fy1 = cy + fadeR * s1;
            final float gx0 = cx + cornerR * c0, gy0 = cy + cornerR * s0;
            final float gx1 = cx + cornerR * c1, gy1 = cy + cornerR * s1;
            // Gradient band: clear at the tunnel edge -> dark at fadeR.
            shapes.triangle(ix0, iy0, fx0, fy0, fx1, fy1, clear, dark, dark);
            shapes.triangle(ix0, iy0, fx1, fy1, ix1, iy1, clear, dark, clear);
            // Solid band out to the corner so screen edges are fully dark.
            shapes.triangle(fx0, fy0, gx0, gy0, gx1, gy1, dark, dark, dark);
            shapes.triangle(fx0, fy0, gx1, gy1, fx1, fy1, dark, dark, dark);
        }
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
        batch.begin();
    }

    /**
     * Ninja kit shuriken visuals — both effects use the same real shuriken
     * sprite (tier 0..5 picks col 10..15 on row 16 of openrealm-items.png).
     * Drawn inside an open SpriteBatch so we can use TextureRegion. Phase
     * driven by wall-clock so consecutive refresh packets stay smooth.
     */
    private void renderShurikenEffects(SpriteBatch batch) {
        if (this.activeEffects == null || this.activeEffects.isEmpty()) return;
        final long now = System.currentTimeMillis();
        final float wx = Vector2f.worldX;
        final float wy = Vector2f.worldY;
        // Persistent-refresh dedupe (matches webclient): only the newest
        // packet per effect type actually renders. Newer = lower elapsed.
        // Without this, multiple overlapping refresh packets paint blade
        // groups at different rotation phases simultaneously and jitter.
        ActiveVisualEffect newestOrbit = null, newestBlender = null;
        for (ActiveVisualEffect vfx : this.activeEffects) {
            final short type = vfx.getEffectType();
            if (type == CreateEffectPacket.EFFECT_BLADE_ORBIT) {
                if (newestOrbit == null || vfx.getElapsed() < newestOrbit.getElapsed()) newestOrbit = vfx;
            } else if (type == CreateEffectPacket.EFFECT_BLADE_BLENDER) {
                if (newestBlender == null || vfx.getElapsed() < newestBlender.getElapsed()) newestBlender = vfx;
            }
        }
        if (newestOrbit != null) drawBladeOrbit(batch, newestOrbit, now, wx, wy);
        if (newestBlender != null) drawBladeBlender(batch, newestBlender, now, wx, wy);
    }

    private static String swingSetName(short tier) {
        switch (tier) {
            case 2:  return "swing_axe";
            case 3:  return "swing_hammer";
            case 10: return "swing_dagger";
            default: return "swing_sword";
        }
    }

    /** Per-archetype swing frames from animations "effect:62". Null until a sheet
     *  is authored + loaded — then the procedural drawMeleeSwing() draws instead. */
    private TextureRegion[] swingFramesFor(short tier) {
        final String setName = swingSetName(tier);
        final TextureRegion[] cached = this.swingFrameCache.get(setName);
        if (cached != null) return cached;
        final AnimationModel anim = GameDataManager.getAnimation("effect",
                CreateEffectPacket.EFFECT_MELEE_SWING);
        if (anim == null || anim.getAnimations() == null) return null;
        AnimationSetModel set = anim.getAnimations().get(setName);
        if (set == null) set = anim.getAnimations().get("swing");
        if (set == null || set.getFrames() == null || set.getFrames().isEmpty()) return null;
        if (GameSpriteManager.TEXTURE_CACHE == null) return null;
        final Texture tex = GameSpriteManager.TEXTURE_CACHE.get(anim.getSpriteKey());
        if (tex == null) return null;
        final int cell = anim.getSpriteSize() > 0 ? anim.getSpriteSize() : 16;
        final List<AnimationFrameModel> frames = set.getFrames();
        final TextureRegion[] regions = new TextureRegion[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            final AnimationFrameModel f = frames.get(i);
            final TextureRegion reg = new TextureRegion(tex,
                    f.getCol() * cell, f.getRow() * cell, cell, cell);
            reg.flip(false, true);
            regions[i] = reg;
        }
        this.swingFrameCache.put(setName, regions);
        return regions;
    }

    /** True when a sprite swing sheet is authored for this archetype — the
     *  procedural path then skips so renderMeleeSwings() draws the frames. */
    private boolean hasSwingSprite(short tier) {
        return swingFramesFor(tier) != null;
    }

    /** Sprite-override swing (only when a sheet is authored). Directional slash
     *  emanating from the player toward the aim, frame picked by effect progress. */
    private void renderMeleeSwings(SpriteBatch batch) {
        if (this.activeEffects == null || this.activeEffects.isEmpty()) return;
        final float wx = Vector2f.worldX;
        final float wy = Vector2f.worldY;
        for (ActiveVisualEffect vfx : this.activeEffects) {
            if (vfx.getEffectType() != CreateEffectPacket.EFFECT_MELEE_SWING) continue;
            final TextureRegion[] frames = swingFramesFor(vfx.getTier());
            if (frames == null) continue;   // no art — procedural drawMeleeSwing() drew it
            final float progress = vfx.getProgress();
            final int idx = Math.min(frames.length - 1, (int) (progress * frames.length));
            final TextureRegion region = frames[idx];
            if (region == null) continue;
            final float ox = vfx.getTargetPosX() - wx;   // swing origin (player)
            final float oy = vfx.getTargetPosY() - wy;
            final float ang = (float) Math.atan2(vfx.getPosY() - vfx.getTargetPosY(),
                    vfx.getPosX() - vfx.getTargetPosX());   // origin -> center
            final float sprSize = Math.max(vfx.getRadius() * 2.4f, 28f);
            final float off = sprSize * 0.42f;
            final float dcx = ox + (float) Math.cos(ang) * off;
            final float dcy = oy + (float) Math.sin(ang) * off;
            final float swingAlpha = progress < 0.8f ? 1f : Math.max(0f, (1f - progress) * 5f);
            batch.setColor(1f, 1f, 1f, swingAlpha);
            batch.draw(region, dcx - sprSize / 2f, dcy - sprSize / 2f,
                    sprSize / 2f, sprSize / 2f, sprSize, sprSize, 1f, 1f,
                    (float) Math.toDegrees(ang));
        }
        batch.setColor(1f, 1f, 1f, 1f);
    }

    private void drawBladeOrbit(SpriteBatch batch, ActiveVisualEffect vfx,
                                 long now, float worldX, float worldY) {
        final TextureRegion tex = getShurikenRegion(vfx.getTier());
        if (tex == null) return;
        final float cx = vfx.getPosX() - worldX;
        final float cy = vfx.getPosY() - worldY;
        final float orbitR = Math.max(36f, vfx.getRadius());
        final float sprSize = 22f;
        final float orbitSpeed = 0.0028f;  // ~1 rev / 2.3s
        final float spinSpeed  = 0.012f;   // ~2 rev/s self-spin
        for (int i = 0; i < 4; i++) {
            final float orbA = (i / 4f) * (float) Math.PI * 2f + now * orbitSpeed;
            final float bx = cx + (float) Math.cos(orbA) * orbitR;
            final float by = cy + (float) Math.sin(orbA) * orbitR;
            final float rotDeg = (float) Math.toDegrees(now * spinSpeed + i * 0.7f);
            batch.draw(tex, bx - sprSize / 2f, by - sprSize / 2f,
                    sprSize / 2f, sprSize / 2f, sprSize, sprSize, 1f, 1f, rotDeg);
        }
    }

    private void drawBladeBlender(SpriteBatch batch, ActiveVisualEffect vfx,
                                   long now, float worldX, float worldY) {
        final TextureRegion tex = getShurikenRegion(vfx.getTier());
        if (tex == null) return;
        final float cx = vfx.getPosX() - worldX;
        final float cy = vfx.getPosY() - worldY;
        final float radius = vfx.getRadius();
        if (radius <= 0) return;
        final float sprSize = 20f;
        final int blades = 9;
        final float spiralTurns = 1.4f;
        final float rotPhase  = now * 0.0018f;
        final float spinPhase = now * 0.010f;
        for (int i = 0; i < blades; i++) {
            final float tt = (i + 1) / (float)(blades + 1);
            final float rr = radius * (0.15f + 0.85f * tt);
            final float a  = rotPhase + tt * (float) Math.PI * 2f * spiralTurns;
            final float bx = cx + (float) Math.cos(a) * rr;
            final float by = cy + (float) Math.sin(a) * rr;
            final float rotDeg = (float) Math.toDegrees(spinPhase + i * 0.9f);
            batch.draw(tex, bx - sprSize / 2f, by - sprSize / 2f,
                    sprSize / 2f, sprSize / 2f, sprSize, sprSize, 1f, 1f, rotDeg);
        }
    }

    /** Rotating bracket reticle over every entity targeted by a live HOMING
     *  projectile. Red = the local player is the target, amber = a lock on an
     *  enemy. Mirrors the webclient lock-on hint. */
    private void renderLockOnReticles(ShapeRenderer shapes) {
        if (this.visibleBullets.isEmpty()) return;
        final Realm realm = this.realmManager.getRealm();
        if (realm == null) return;
        final Player local = realm.getPlayer(this.playerId);
        final float t = System.currentTimeMillis() * 0.001f;
        final float spin = t * 2.2f;
        final float pulse = Math.max(0.25f, 0.55f + 0.45f * (float) Math.sin(t * 5.0f));
        final float wx = Vector2f.worldX, wy = Vector2f.worldY;
        this.lockOnSeen.clear();
        boolean began = false;
        for (int i = 0; i < this.visibleBullets.size(); i++) {
            final Bullet bul = this.visibleBullets.get(i);
            if (bul == null || !bul.hasFlag(ProjectileFlag.HOMING)) continue;
            final long tid = bul.getTargetEntityId();
            if (tid == 0L || !this.lockOnSeen.add(tid)) continue;
            GameObject tgt = realm.getPlayer(tid);
            if (tgt == null) tgt = realm.getEnemies().get(tid);
            if (tgt == null) continue;
            final boolean isLocal = (local != null && tgt == local);
            final float cx = tgt.getPos().x + tgt.getSize() * 0.5f - wx;
            final float cy = tgt.getPos().y + tgt.getSize() * 0.5f - wy;
            final float rad = tgt.getSize() * 0.7f;
            if (!began) {
                Gdx.gl.glEnable(GL20.GL_BLEND);
                Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
                shapes.begin(ShapeRenderer.ShapeType.Line);
                began = true;
            }
            if (isLocal) shapes.setColor(1f, 0.23f, 0.23f, pulse);
            else shapes.setColor(1f, 0.82f, 0.23f, pulse);
            this.drawReticleBrackets(shapes, cx, cy, rad, spin);
            shapes.circle(cx, cy, rad * 0.5f, 24);
        }
        if (began) {
            shapes.end();
            Gdx.gl.glDisable(GL20.GL_BLEND);
        }
    }

    private void drawReticleBrackets(ShapeRenderer shapes, float cx, float cy, float rad, float spin) {
        final float cos = (float) Math.cos(spin), sin = (float) Math.sin(spin);
        final float arm = rad * 0.45f;
        final int[][] corners = { {-1, -1}, {1, -1}, {1, 1}, {-1, 1} };
        for (int c = 0; c < 4; c++) {
            final float sx = corners[c][0], sy = corners[c][1];
            final float lx = sx * rad, ly = sy * rad;
            final float px = cx + lx * cos - ly * sin, py = cy + lx * sin + ly * cos;
            final float ax = lx - sx * arm, ay = ly;
            final float pax = cx + ax * cos - ay * sin, pay = cy + ax * sin + ay * cos;
            final float bx = lx, by = ly - sy * arm;
            final float pbx = cx + bx * cos - by * sin, pby = cy + bx * sin + by * cos;
            shapes.line(pax, pay, px, py);
            shapes.line(px, py, pbx, pby);
        }
    }

    /** Melee aim indicator: a grey raindrop-ripple pattern (expanding, fading
     *  rings) at the cursor, clamped to the weapon's max melee range. Mirrors the
     *  webclient meleeReticle. */
    private void renderMeleeAimReticle(ShapeRenderer shapes) {
        final Player player = this.getPlayer();
        if (player == null || player.getInventory() == null) return;
        final GameItem weapon = player.getInventory()[0];
        final WeaponArchetypeModel arch = (weapon == null || weapon.getArchetypeId() <= 0
                || GameDataManager.WEAPON_ARCHETYPES == null)
                ? null : GameDataManager.WEAPON_ARCHETYPES.get(weapon.getArchetypeId());
        if (arch == null || !arch.isMelee()) return;
        final float cx = player.getPos().x + player.getSize() * 0.5f;
        final float cy = player.getPos().y + player.getSize() * 0.5f;
        float dx = player.getAimX() - cx, dy = player.getAimY() - cy;
        final float d = (float) Math.hypot(dx, dy);
        final float maxRange = meleeMaxRange(weapon, arch);
        if (maxRange > 0 && d > maxRange) { dx = dx / d * maxRange; dy = dy / d * maxRange; }
        final float rx = (cx + dx) - Vector2f.worldX;
        final float ry = (cy + dy) - Vector2f.worldY;
        final long now = System.currentTimeMillis();
        final float phBase = (now % 900L) / 900f; // keep float precision (large ms would lose it)
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(2f);
        for (int i = 0; i < 3; i++) {
            final float ph = (phBase + i / 3f) % 1f;
            shapes.setColor(0.72f, 0.76f, 0.80f, (1f - ph) * 0.6f);
            drawCircleOutline(shapes, rx, ry, 11f * (0.35f + ph * 0.9f), 28);
        }
        shapes.end();
        Gdx.gl.glLineWidth(1f);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.85f, 0.88f, 0.91f, 0.7f);
        drawCircle(shapes, rx, ry, 1.6f, 10);
        shapes.end();
    }

    /** Max melee reach in world px: base projectile range * archetype range mul. */
    private float meleeMaxRange(GameItem weapon, WeaponArchetypeModel arch) {
        if (weapon == null || weapon.getDamage() == null || GameDataManager.PROJECTILE_GROUPS == null) return 0f;
        final ProjectileGroup pg = GameDataManager.PROJECTILE_GROUPS.get(weapon.getDamage().getProjectileGroupId());
        if (pg == null || pg.getProjectiles() == null || pg.getProjectiles().isEmpty()) return 0f;
        final float base = pg.getProjectiles().get(0).getRange();
        return base * (arch.getRangeMul() != 0f ? arch.getRangeMul() : 1f);
    }

    private void renderVisualEffects(ShapeRenderer shapes) {
        if (this.activeEffects.isEmpty()) return;

        // The preceding nameplate/status-chip pass ends its SpriteBatch, and
        // SpriteBatch.end() disables GL_BLEND. Without re-enabling it here the
        // effects' alpha is ignored and every AoE disc renders fully opaque,
        // washing out the whole screen (the caller disables blend again after).
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        final float wx = Vector2f.worldX;
        final float wy = Vector2f.worldY;

        for (ActiveVisualEffect vfx : this.activeEffects) {
            final float t = vfx.getProgress();
            final short type = vfx.getEffectType();

            if (vfx.isAoe()) {
                AbilityEffectRenderer.renderAoeEffect(shapes, vfx, type, t, wx, wy, hasSwingSprite(vfx.getTier()));
            } else {
                AbilityEffectRenderer.renderLineEffect(shapes, vfx, t, wx, wy);
            }
        }
    }

}
