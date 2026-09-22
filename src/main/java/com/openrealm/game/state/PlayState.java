package com.openrealm.game.state;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.utils.Align;
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
    // Matches the server's player load radius (viewport 10 + 5 tiles); past it
    // the server stops sending peer updates, so a retained peer would ghost.
    private static final float REMOTE_DERENDER_PX = 15 * 32;
    private static final float REMOTE_DERENDER_PX_SQ = REMOTE_DERENDER_PX * REMOTE_DERENDER_PX;
    // Above this enemy count only enemies within NAME_HORDE_RADIUS get a label.
    private static final int NAME_HORDE_THRESHOLD = 50;
    private static final float NAME_HORDE_RADIUS_SQ = (32f * 5f) * (32f * 5f);

    private static final Color ROLE_SYSADMIN = new Color(1.00f, 0.25f, 0.25f, 1f);
    private static final Color ROLE_ADMIN    = new Color(0.25f, 0.50f, 0.88f, 1f);
    private static final Color ROLE_MOD      = new Color(0.25f, 0.75f, 0.25f, 1f);
    private static final Color ROLE_EDITOR   = new Color(0.63f, 0.25f, 0.75f, 1f);
    private static final Color ROLE_DEMO     = new Color(0.80f, 0.80f, 0.80f, 1f);
    private static final Color ROLE_DEFAULT  = new Color(0.93f, 0.93f, 0.93f, 1f);
    private static final Color ENEMY_NAME_COLOR = new Color(1f, 0.5f, 0.5f, 1f);
    private static final int IDLE_KEEPALIVE_TICKS = 16;

    /** Local player's privilege role, captured at login. STATIC so it survives a
     *  PlayState re-created on a realm transition, then re-applied to the local
     *  Player each frame so the name color holds. */
    private static String localChatRole = null;

    private RealmManagerClient realmManager;
    /** Server-wide players from GlobalPlayerPositionPacket, used ONLY by the
     *  minimap to plot players outside our local realm map. Must NOT overwrite
     *  local players' coords or in-realm dots drag around. */
    private NetPlayerPosition[] minimapPlayers = new NetPlayerPosition[0];
    private Queue<EffectText> damageText;
    private Queue<ActiveVisualEffect> activeEffects;
    // Only successful builds are cached so a not-yet-loaded sheet retries next frame.
    private final Map<String, TextureRegion[]> swingFrameCache = new HashMap<>();
    private long partyId = 0L;
    private long partyLeaderId = 0L;
    private NetPartyMember[] partyMembers = new NetPartyMember[0];
    /** Active cast bars by playerId; auto-cleared by the renderer on completion. */
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
    private final ProjectileFxManager projectileFx = new ProjectileFxManager();
    public long playerId = -1l;
    /** Account-wide skill XP (PlayerSkill ordinal -> XP), synced by SkillsPacket. */
    private long[] skillXp = new long[9];

    private long lastSampleTime;
    private long frames;
    private long lastFrames;

    private Map<Cardinality, Boolean> lastDirectionMap;
    private boolean sentChat = false;
    private boolean debugMode = false;

    // Reusable per-frame visibility buffers, cleared at start of render so the
    // backing arrays are reused frame-to-frame instead of reallocated.
    private final List<Entity> visibleEntities = new ArrayList<>(256);
    private final List<Bullet> visibleBullets = new ArrayList<>(128);
    private final List<Enemy> visibleEnemies = new ArrayList<>(128);
    private final HashSet<Long> lockOnSeen = new HashSet<>();

    // Scratch reused for collision center-offset queries in movePlayer; safe as
    // a field because input/render run on the GL thread.
    private final Vector2f movePlayerScratch = new Vector2f();

    private final GlyphLayout nameLayoutScratch = new GlyphLayout();
    // Kept separate from nameLayoutScratch so a bubble can be positioned relative
    // to the still-intact nameplate layout.
    private final GlyphLayout chatBubbleLayoutScratch = new GlyphLayout();

    // Keyed by sender name. Written from the network thread, read on the render
    // thread — hence the concurrent map.
    private final Map<String, ChatBubble> chatBubbles = new ConcurrentHashMap<>();

    /**
     * Server-reconciliation input buffer: every sim-tick we predict pos locally
     * and push a {@link PendingInput}; on PlayerPosAckPacket we drop confirmed
     * inputs, snap to the server pos, and replay the rest. Bounded to 128.
     * Pushed from the GL/input thread, read+mutated under the synchronized
     * {@link #reconcileLocalPlayerPos} so it can't race the input-loop drain.
     */
    private final ArrayDeque<PendingInput> pendingInputs = new ArrayDeque<>(128);

    /** Visual-only smoothing offset on a small reconciliation mismatch: logical
     *  pos snaps to the replay result (accurate next-tick collisions) while the
     *  visual diff decays toward zero each frame so there's no hop. */
    private float smoothingOffsetX = 0f;
    private float smoothingOffsetY = 0f;

    private long castRingExpiresAt = 0L;
    private float castRingCx, castRingCy, castRingRadius;

    /** Set when the initial login send fails; update() pops PlayState back to
     *  CharacterSelectState so the user can edit the host and retry. */
    private String connectError = null;

    private long frameCounter = 0;
    /** Accumulator (seconds) for the fixed 1/64-s movement tick, so high-FPS
     *  rendering doesn't predict faster than the server simulates. */
    private float moveAccumulator = 0f;
    // Send-gating: ship a PlayerMovePacket only on a non-zero input, the stop-edge
    // (one final 0,0), or a ~4Hz idle keepalive. lastSentVx/Vy = last SENT vector.
    private float lastSentVx = 0f;
    private float lastSentVy = 0f;
    private int idleSendCounter = 0;
    // Visual pos lerps from pre-tick to post-tick sim positions over each tick.
    private float interpFromX, interpFromY;
    /** Smoothed camera position; eases toward the lerped render pos each frame.
     *  NaN sentinel = "no anchor yet, snap on first frame". */
    private float cameraX = Float.NaN;
    private float cameraY = Float.NaN;
    private float interpToX, interpToY;
    private boolean hasInterpAnchor = false;

    private final Matrix4 worldTransformIdt = new Matrix4();

    /** Labels MUST match webclient STATUS_ICON_DEFS. Suffix convention:
     *  '+' = buff modifier up, '-' = debuff modifier down. */
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
        new StatusEffectIconDef(StatusEffectType.TAUNT_TARGET.effectId, "Taunt",  0xC8201F),
        new StatusEffectIconDef(StatusEffectType.BRACED.effectId,       "Def+",   0x88AACC),
        new StatusEffectIconDef(StatusEffectType.PROTECTED.effectId,    "Vit+",   0xFFE070),
        new StatusEffectIconDef(StatusEffectType.PHALANX_DOME.effectId, "Dome",   0x6CCCFF),
        new StatusEffectIconDef(StatusEffectType.WEAKEN.effectId,       "Atk-",   0x8A5A30),
        new StatusEffectIconDef(StatusEffectType.BLIND.effectId,        "Blind",  0x1A1A1A),
        new StatusEffectIconDef(StatusEffectType.WARDED.effectId,       "Ward",   0xC8C0FF),
        new StatusEffectIconDef(StatusEffectType.MANA_FOUNT.effectId,   "MP+",    0x4080FF),
        new StatusEffectIconDef(StatusEffectType.INSTAKILL.effectId,    "DEATH",  0xFF0000),
        new StatusEffectIconDef(StatusEffectType.VULNERABLE.effectId,   "Vuln",   0xCC4080),
        new StatusEffectIconDef(StatusEffectType.GROUNDED.effectId,     "Grnd",   0x806040),
        new StatusEffectIconDef(StatusEffectType.MARKED_FOR_LOOT.effectId, "Mark", 0xFFD840),
        // Guiding Light aura split so STR and DEX each show their own pip.
        new StatusEffectIconDef(StatusEffectType.EMPOWERED_STR.effectId, "Atk+",  0xFFAA44),
        new StatusEffectIconDef(StatusEffectType.EMPOWERED_DEX.effectId, "Dex+",  0xFFD060),
        new StatusEffectIconDef(StatusEffectType.SACRIFICE.effectId,    "Sac",    0xB03060),
    };

    /** Cached shuriken regions per tier 0..5 (col = 10 + tier, row 16 of
     *  openrealm-items.png). Flipped once at load; draw calls rely on that. */
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
            // Bounce back to character-select instead of killing the client so
            // the user can change the game-server host and retry.
            log.error("{} failed to send initial LoginRequest, returning to character select: {}",
                    LOG_NS, e.getMessage());
            this.connectError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return;
        }
        WorkerThread.submitAndForkRun(this.realmManager);
    }

    public void setLocalChatRole(String role) { PlayState.localChatRole = role; }

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
        this.pui.getRealmTransition().begin(null);

        this.getPui().setEquipment(player.getInventory());
    }

    public long getPlayerId() {
        return this.playerId;
    }

    public Vector2f getPlayerPos() {
        return this.realmManager.getRealm().getPlayers().get(this.playerId).getPos();
    }
    
    public void doLogin() throws Exception {
        // Prefer an existing session token (auto-login path); fall back to
        // email/password. Sending both null makes the server reject with 400.
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
        shapes.rect(x + r, y, w - 2 * r, h);
        shapes.rect(x, y + r, r, h - 2 * r);
        shapes.rect(x + w - r, y + r, r, h - 2 * r);
        shapes.circle(x + r, y + r, r);
        shapes.circle(x + w - r, y + r, r);
        shapes.circle(x + r, y + h - r, r);
        shapes.circle(x + w - r, y + h - r, r);
    }

    @Override
    public void update(double time) {
        // CharacterSelectState watches for this and pops PlayState back to
        // CHARSELECT so a failed connect doesn't strand the user.
        if (this.connectError != null) return;

        final Player player = this.realmManager.getRealm().getPlayer(this.realmManager.getCurrentPlayerId());

        if (player == null)
            return;
        if (!this.gsm.isStateActive(GameStateManager.PAUSE)) {
            final Realm clientRealm = this.realmManager.getRealm();
            final GameObject[] gameObject = clientRealm.getAllGameObjects();
            // Precompute bulletScale once per frame; dt clamped to 1/30 like
            // the rest of the sim's frame-skip cap.
            final float bulletDt = Gdx.graphics != null
                    ? Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f)
                    : 1f / 60f;
            final float bulletScale = bulletDt * 64f;
            // Local bullet culling (range, 10s cap, terrain). Without it,
            // predicted bullets whose client IDs don't match the server's
            // UnloadPacket accumulate forever.
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
                        // Advance the seeker in fixed 1/64s ticks (steer then move) to
                        // match the server's discretization, else the path drifts.
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
                    // Terrain collision (skip for pass-through). OOB counts as a
                    // hit, which is fine — off-map bullets should die anyway.
                    if (!expired
                            && !bul.hasFlag(ProjectileFlag.PASS_THROUGH_TERRAIN)
                            && bul.getPos() != null
                            && tm != null) {
                        final float half = bul.getSize() * 0.5f;
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
                    // Center-to-center (match the server). De-render on leave so a
                    // stale peer doesn't ghost then teleport on a lagged update.
                    final float otherHalf = (playerOther.getSize() > 0 ? playerOther.getSize() : 32) * 0.5f;
                    final float ddx = (playerOther.getPos().x + otherHalf) - refX;
                    final float ddy = (playerOther.getPos().y + otherHalf) - refY;
                    if (ddx * ddx + ddy * ddy > REMOTE_DERENDER_PX_SQ) {
                        if (playersToDerender == null) playersToDerender = new ArrayList<>(4);
                        playersToDerender.add(playerOther.getId());
                        continue;
                    }
                    playerOther.update(time);
                    // Extrapolate remote peers toward the server-reported target;
                    // without it they'd sit at the spawn pos forever.
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

            // Client-side player-bullet hit prediction (visual cull only; server
            // stays authoritative for damage). Mark-consumed rather than remove:
            // removing fights the server's LoadPacket diff, which re-adds the
            // bullet at its stale pos until the kill packet lands, freezing it.
            // Circle-vs-circle with HIT_RADIUS_FACTOR to match server circleHit().
            final Map<Long, Bullet> bullets = clientRealm.getBullets();
            final Map<Long, Enemy>  enemies = clientRealm.getEnemies();
            final long localId = this.playerId;
            if (!bullets.isEmpty() && !enemies.isEmpty()) {
                for (final Bullet b : bullets.values()) {
                    if (b == null || b.getPos() == null) continue;
                    if (b.isConsumedClient()) continue;
                    // Accept flagged player projectiles AND any bullet sourced by
                    // the local player, so unflagged weapons (daggers, flags: [])
                    // still hit-cull.
                    final boolean isOwnBullet = (localId != -1L && b.getSrcEntityId() == localId);
                    if (!b.hasFlag(ProjectileFlag.PLAYER_PROJECTILE) && !isOwnBullet) continue;
                    if (b.hasFlag(ProjectileFlag.PASS_THROUGH_ENEMIES)) continue;
                    // Homing bullets are server-driven; a local cull would delete
                    // our copy before the server's, which then re-sends and orbits.
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

            // Local player's sim / position / camera happens in input(); this
            // only animates the sprite. Splitting the two produces visible lurch.
            player.update(time);

            if (this.pui != null) {
                this.pui.update(time);
            }

            // Iterator-remove: the queue's iterator is weakly consistent, and the
            // render thread is the only mutator, so concurrent producers are safe.
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
        // PARALYZED is filtered by the caller (live tick) and by the reconcile
        // replay via PendingInput.paralyzed, so it isn't re-checked here.
        final Vector2f scratch = this.movePlayerScratch;
        final float halfSize = p.getSize() / 2f;
        scratch.x = p.getPos().x + halfSize;
        scratch.y = p.getPos().y + halfSize;
        final TileManager tm = this.getRealmManager().getRealm().getTileManager();
        // The first movement input can arrive before the initial LoadMapPacket
        // populates the tile layers; skip the frame so collision queries don't
        // index an empty collision layer.
        if (!tm.isMapLoaded()) return;
        // Apply the slow-tile divisor to the delta FIRST, then query and commit
        // against that same reduced delta (else prediction desyncs from the
        // server near water/lava-bordering walls).
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
     * Server-reconciliation entry point, called from the network thread on
     * PlayerPosAckPacket. Drops acked inputs, snaps to the server pos, replays
     * remaining inputs through {@link #movePlayer}, then classifies the error:
     * over 64 px teleports, over 2 px keeps the replay pos and smooths the visual
     * diff, at/under 2 px adopts the replay pos too (see the branch note below).
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

        // Step 4: replay remaining unacked inputs. OR the per-input paralyzed/
        // slowed snapshot with the CURRENT effect state so an effect that landed
        // mid-flight applies the same way the server will process it.
        synchronized (this.pendingInputs) {
            for (final PendingInput input : this.pendingInputs) {
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
            // Genuine mismatch. Logical pos stays at the replay result; the
            // visible diff is absorbed into a decaying smoothing offset.
            // SET (don't accumulate) or 30+ acks/sec ride the 6 px cap and jitter.
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
            // Under 2 px: adopt the REPLAY result, not saved. Clinging to saved
            // lets tiny per-ack diffs compound into a slow drift that eventually
            // snaps; trusting the replay stays anchored with no visible jerk.
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

    // Local shot prediction so a delayed LoadPacket doesn't gap the firing
    // player's own projectile stream. The predicted sprite renders until the
    // server bullet dedups against it.
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

        // Mirror the server's multishot fan (archetype.projectileCount +1 for a
        // MultishotGem) and its spread/range/piercing exactly, or the predicted
        // bullets dedup poorly and the player sees ghosts.
        final WeaponArchetypeModel _archShot =
                (weapon == null || weapon.getArchetypeId() <= 0 || GameDataManager.WEAPON_ARCHETYPES == null)
                        ? null
                        : GameDataManager.WEAPON_ARCHETYPES.get(weapon.getArchetypeId());
        // Melee swings are server-side AoEs; no travelling projectile to predict.
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

        // Homing prediction target: nearest enemy to the cursor within ~6 tiles,
        // mirroring the server, so the predicted seeker curves right.
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
                // Bullet's ctor keeps the origin Vector2f by reference (no copy),
                // so each bullet MUST get its own clone or they share one pos and
                // advance together.
                final Vector2f spawnPos = source.clone(-offset, -offset);
                final float deltaA = (i - (totalBullets - 1) / 2f) * SPREAD;
                // Predicted Bullet.projectileId MUST be the GROUP id, not
                // proj.getProjectileId(): the server broadcasts the group id, and
                // findMatchingPredictedBullet's equality check dedups on it.
                final float predictedRange = proj.getRange() * rangeMul;
                final Bullet b = new Bullet(Realm.RANDOM.nextLong(), projGroupId, spawnPos,
                        shootAngle + deltaA, proj.getSize(), proj.getMagnitude(), predictedRange,
                        rolledDamage, false);
                b.setSrcEntityId(player.getId());
                b.setAmplitude(proj.getAmplitude());
                b.setFrequency(proj.getFrequency());
                // Carry the projectile's behavior flags so dedup + hit prediction
                // see the same trajectory; add PASS_THROUGH_ENEMIES (25) for a
                // piercing archetype that lacks it.
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

                // The whole movement + visual-position pipeline runs INLINE here
                // (not split across update()) or a 1-frame gap between simulating
                // and computing renderX produces per-tick lurch.
                // TICK_RATE MUST equal the server's 64 Hz or replayed inputs after
                // a PlayerPosAck diverge from the server's positions.
                final float TICK_RATE = 64f;
                final float TICK_DT = 1f / TICK_RATE;
                float frameDt = Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f);
                this.moveAccumulator += frameDt;
                if (this.moveAccumulator > 0.25f) this.moveAccumulator = 0.25f;

                float vx = (player.getIsRight() ? 1f : 0f) - (player.getIsLeft() ? 1f : 0f);
                float vy = (player.getIsDown()  ? 1f : 0f) - (player.getIsUp()   ? 1f : 0f);
                final float mag = (float) Math.sqrt(vx * vx + vy * vy);
                if (mag > 0f) { vx /= mag; vy /= mag; }

                // basePxPerTick EXCLUDES SLOWED so the replay can re-derive the
                // 0.5 factor without double-counting; SPEEDY stays baked in.
                float baseTilesPerSec = 4.0f + 5.6f * (player.getComputedStats().getSpd() / 75.0f);
                if (player.hasEffect(StatusEffectType.SPEEDY)) baseTilesPerSec *= 1.5f;
                final float basePxPerTick = baseTilesPerSec * 32.0f / TICK_RATE;
                final boolean slowedNow = player.hasEffect(StatusEffectType.SLOWED);
                final float pxPerTick = basePxPerTick * (slowedNow ? 0.5f : 1.0f);

                int ticks = 0;
                while (this.moveAccumulator >= TICK_DT) {
                    this.moveAccumulator -= TICK_DT;
                    // Anchor the render lerp at the pos ENTERING the final tick so
                    // render() interpolates across only that tick (no backward snap
                    // on 2-tick frames, no overshoot on direction changes).
                    this.interpFromX = player.getPos().x;
                    this.interpFromY = player.getPos().y;
                    this.hasInterpAnchor = true;
                    // Unique per-tick seq so a PlayerPosAck acks exactly one input.
                    player.setLastInputSeq(player.getLastInputSeq() + 1);
                    final int seq = player.getLastInputSeq();

                    // PARALYZED is short-circuited here (not in movePlayer) so the
                    // reconcile replay can still move non-paralyzed snapshot inputs
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

                    // Buffer this input for reconciliation replay, capturing the
                    // step magnitude actually applied (spd/SPEEDY may change before
                    // the ack, and the replay must reproduce what happened).
                    synchronized (this.pendingInputs) {
                        final boolean paralyzedAtSend = player.hasEffect(StatusEffectType.PARALYZED);
                        this.pendingInputs.addLast(new PendingInput(seq, vx, vy, basePxPerTick, slowedNow, paralyzedAtSend));
                        while (this.pendingInputs.size() > 128) {
                            this.pendingInputs.pollFirst();
                        }
                    }

                    // Send-gate: any non-zero vector, the stop-edge (one final 0,0),
                    // or a ~4Hz idle keepalive. Every tick still buffers a seq above.
                    final boolean moving = (vx != 0f || vy != 0f);
                    final boolean wasMoving = (this.lastSentVx != 0f || this.lastSentVy != 0f);
                    boolean shouldSend = false;
                    if (moving || wasMoving) {
                        shouldSend = true;
                        this.idleSendCounter = 0;
                    } else if (++this.idleSendCounter >= IDLE_KEEPALIVE_TICKS) {
                        shouldSend = true;
                        this.idleSendCounter = 0;
                    }
                    if (shouldSend) {
                        try {
                            PlayerMovePacket packet = PlayerMovePacket.from(player, seq, vx, vy);
                            this.realmManager.getClient().sendRemote(packet);
                        } catch (Exception e) {
                            PlayState.log.error("{} failed to create player move packet", LOG_NS, e);
                        }
                        this.lastSentVx = vx;
                        this.lastSentVy = vy;
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

                if (this.lastDirectionMap == null) {
                    this.lastDirectionMap = lastDirectionTempMap;
                } else if (!this.lastDirectionMap.equals(lastDirectionTempMap)) {
                    this.lastDirectionMap = lastDirectionTempMap;
                }

                // Render pos = lerp between the start and end of the most-recent
                // tick, by the accumulator's leftover fraction.
                final float interpFrac = Math.max(0f, Math.min(1f, this.moveAccumulator / TICK_DT));
                float renderX = this.interpFromX + (player.getPos().x - this.interpFromX) * interpFrac;
                float renderY = this.interpFromY + (player.getPos().y - this.interpFromY) * interpFrac;

                // Decay the reconciliation smoothing offset each frame, then apply
                // it to the render pos (the logical pos already snapped to replay).
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

                // Camera eases toward the lerped player pos (frame-rate independent
                // exponential smoothing, 0.03s half-life).
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
            // Space (bound to key.attack) also triggers nearest-portal use.
            key.attack.tick();
            boolean portalKeyClicked = key.f2.clicked || key.attack.clicked;
            if (portalKeyClicked && canUsePortal) {
                try {
                    Portal closestPortal = this.realmManager.getState().getClosestPortal(this.getPlayerPos(), 32);
                    if (closestPortal != null) {
                        PortalModel portalModel = GameDataManager.PORTALS.get((int) closestPortal.getPortalId());
                        // A Vault portal (id 2) MUST use the toVault variant: it's
                        // the only path that reaches the server's setupChests
                        // branch. UsePortalPacket.from would route by toRealmId and
                        // spawn no chests / land the user elsewhere.
                        final boolean isVaultPortal = closestPortal.getPortalId() == 2;
                        if (isVaultPortal) {
                            if (this.realmManager.getRealm().getMapId() == 1) {
                                return; // already in vault
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
                        if (this.pui != null) this.pui.getRealmTransition().begin(null);
                        // Tell server we're ready for tiles after map rebuild
                        this.realmManager.getClient().sendRemote(LoginAckPacket.from());
                        this.lastPortalTick = System.currentTimeMillis();
                    }
                } catch (Exception e) {
                    PlayState.log.error("{} failed to send UsePortalPacket: {}", LOG_NS, e.getMessage());
                }

            }
            // R = teleport to Nexus (map 29); suppressed while chat is capturing.
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
                    if (this.pui != null) this.pui.getRealmTransition().begin(null);
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
                        if (this.pui != null) this.pui.getRealmTransition().begin(null);
                        this.realmManager.getClient().sendRemote(LoginAckPacket.from());
                        this.lastPortalTick = System.currentTimeMillis();
                    }
                } catch (Exception e) {
                    PlayState.log.error("{} failed to send Vault UsePortalPacket: {}", LOG_NS, e.getMessage());
                }

            }

            // F = interact with the closest nearby tile (5x5 scan) that has an
            // interactionType (forge / fame store / etc), else pick up ground loot.
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
                    } else if (this.pui != null) {
                        // No interact tile in reach: F picks up the first ground-loot
                        // item (server re-checks proximity and routes potions).
                        final Slots[] gl = this.pui.getGroundLoot();
                        if (gl != null) {
                            for (int i = 0; i < gl.length; i++) {
                                final Slots s = gl[i];
                                final GameItem it = (s != null) ? s.getItem() : null;
                                if (it != null && it.getItemId() > 0) {
                                    this.realmManager.moveItem(Player.EQUIPMENT_SLOT_COUNT,
                                            MoveItemPacket.groundLootBase() + i, false, false);
                                    break;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    PlayState.log.error("{} failed to send InteractTilePacket: {}", LOG_NS, e.getMessage());
                }
            }
            if (this.pui != null) {
                this.pui.input(mouse, key);
            }
            boolean canQuickUse = (System.currentTimeMillis() - this.lastQuickUseTick) > QUICK_USE_COOLDOWN_MS;
            // Shift + 1..8 hot-swaps/consumes backpack slots 5..12. REQUIRES shift
            // because plain 1..4 are ability casts (handled below).
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

            // Suppressed while chat is capturing so typing doesn't toggle menus.
            if (this.pui != null && !key.captureMode) {
                if (Gdx.input.isKeyJustPressed(Settings.get().getKeybind("skillsMenu")))
                    this.pui.getSkillsWindow().toggle();
                if (Gdx.input.isKeyJustPressed(Settings.get().getKeybind("metricsMenu")))
                    this.pui.getMetricsWindow().toggleFor(SocketClient.CHARACTER_UUID);
                if (Gdx.input.isKeyJustPressed(Input.Keys.N)) this.pui.getMinimap().toggle();
            }
        }

        // O toggles the options window; suppressed while chat is capturing.
        if (this.pui != null && !key.captureMode
                && Gdx.input.isKeyJustPressed(Input.Keys.O)) {
            this.pui.getOptionsWindow().toggle();
        }

        // isKeyJustPressed (rising edge), NOT key.escape.clicked: holding ESC
        // makes clicked fire on consecutive frames, which reopens the menu it
        // just closed.
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            // Modals consume ESC first (they self-close in update()); suppress
            // the pause toggle so one press doesn't close a modal AND open pause.
            boolean anyModal = (this.pui != null) && (
                    this.pui.getForgeWindow().isVisible()
                 || this.pui.getFameStoreWindow().isVisible()
                 || this.pui.getExchangeMarketWindow().isVisible()
                 || this.pui.getOptionsWindow().isVisible()
                 || this.pui.getPotionStorageWindow().isVisible()
                 || this.pui.getSkillsWindow().isVisible()
                 || this.pui.getMetricsWindow().isVisible());
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
					e.printStackTrace();
				}

            }
        }

        double dex = (int) ((6.5 * (this.getPlayer().getComputedStats().getDex() + 17.3)) / 75);
		// Archetype attack-speed multiplier applied BEFORE the BERSERK +50% so
		// the two stack the same way the server does.
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
		if (player.hasEffect(StatusEffectType.BERSERK)) {
			dex = dex * 1.5;
		}
        boolean canShoot = (System.currentTimeMillis() - this.lastShotTick) > (1000 / dex + 10);
        boolean canUseAbility = (System.currentTimeMillis() - this.lastAbilityTick) > 1000;
        // Suppress the basic-attack shot when the cursor sits over a hotbar cell,
        // else the same click both casts and fires.
        final boolean hoveringHotbar = (this.pui != null)
                && this.pui.isHoveringHotbarCell(mouse.getX(), mouse.getY());
        boolean clickingWorld = mouse.isPressed(1)
                && (this.pui == null || !this.pui.isHoveringInventory(mouse.getX()))
                && !hoveringHotbar;
        // Do NOT setAttacking(clickingWorld) here; it would cut the attack anim on
        // button release. triggerAttackAnimation() at the firing site handles it.
        // Screen to world: WORLD_SCALE=2 => 1 screen px = 1/2 world px.
        final float invScale = 1f / OpenRealmGame.WORLD_SCALE;
        final float pivotWx = player.getPos().x + player.getSize() * 0.5f;
        final float pivotWy = player.getPos().y + player.getSize() * 0.5f;
        // Pivot aim about the player's ACTUAL on-screen pos: the world view is
        // shifted left for the HUD panel, so pivoting about width/2 skews the aim.
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
            player.triggerAttackAnimation();
        }
        // Left-click a hotbar cell fires the bound ability at the cursor.
        // Edge-triggered so a held click doesn't spam.
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
                    player.triggerAttackAnimation();
                } catch (Exception e) {
                    PlayState.log.error("{} failed to send UseAbility packet from hotbar click for slot {}",
                            LOG_NS, bindingIdx, e);
                }
            }
        }
        // Right-click a hotbar ability cell to invest a skill point into it.
        // Edge-triggered so a held right-click can't drain the pool; the global
        // right-click ability-fire below is suppressed over the hotbar.
        if (Gdx.input.isButtonJustPressed(Input.Buttons.RIGHT) && this.pui != null) {
            final int investBinding = this.pui.getHotbarBindingAtScreen(mouse.getX(), mouse.getY());
            if (investBinding >= 0) {
                try {
                    final InvestSkillPointPacket pkt = new InvestSkillPointPacket((byte) investBinding);
                    this.realmManager.getClient().sendRemote(pkt);
                    // Optimistic local mirror; server state lands on the next sync.
                    final Ability ab = this.getPlayer().getActiveAbility(investBinding);
                    if (ab != null) this.getPlayer().investSkillPoint(ab.getId());
                } catch (Exception e) {
                    PlayState.log.error("{} failed to send InvestSkillPoint from hotbar right-click for slot {}",
                            LOG_NS, investBinding, e);
                }
            }
        }

        // Plain keys 1..4 fire the four hotbar slots at the cursor; shift+number
        // is inventory hot-swap (above), so skip the cast when shift is held.
        // Gated on !captureMode so typing a digit while the chat box is open
        // doesn't fire an ability.
        if (!key.captureMode) {
            final boolean shiftHeldDigit = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)
                    || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);
            final int[] digitKeys = { Input.Keys.NUM_1, Input.Keys.NUM_2, Input.Keys.NUM_3, Input.Keys.NUM_4 };
            final long[] cds = player.getAbilityCooldowns();
            for (int slot = 0; slot < 4; slot++) {
                if (!Gdx.input.isKeyJustPressed(digitKeys[slot])) continue;
                if (this.pui != null && this.pui.isHoveringInventory(mouse.getX())) continue;
                if (shiftHeldDigit) continue;
                if (!canUseAbility) continue;
                final long now = System.currentTimeMillis();
                // Skip the send AND the cast pose while this slot's CD drains, or
                // the pose plays on every press the server silently rejects.
                if (cds != null && slot < cds.length && cds[slot] > now) continue;
                try {
                    Vector2f pos = clampCastPos(player, slot, aimWx, aimWy);
                    UseAbilityPacket useAbility = UseAbilityPacket.from(this.getPlayer(), pos, slot);
                    this.realmManager.getClient().sendRemote(useAbility);
                    this.lastAbilityTick = now;
                    final long cd = this.effectiveAbilityCooldownMs(player, slot);
                    if (cds != null && slot < cds.length && cd > 0) cds[slot] = now + cd;
                    player.triggerAttackAnimation();
                } catch (Exception e) {
                    PlayState.log.error("{} failed to send UseAbility packet for slot {}", LOG_NS, slot, e);
                }
            }
        }

        if ((mouse.isPressed(3)) && canUseAbility && !hoveringHotbar
                && (this.pui == null || !this.pui.isHoveringInventory(mouse.getX()))) {
            // Client-side mana gate (server still authoritative): the optimistic
            // decrement keeps the mana bar from snapping back within a round-trip.
            int abilityCost = 0;
            try {
                final GameItem ability = player.getAbility();
                if (ability != null && ability.getEffect() != null) {
                    abilityCost = ability.getEffect().getMpCost();
                }
            } catch (Exception ignored) { /* zero-cost fallback */ }
            final long[] rcCds = player.getAbilityCooldowns();
            final long rcNow = System.currentTimeMillis();
            final boolean rcOnCooldown = rcCds != null && rcCds.length > 0 && rcCds[0] > rcNow;
            if (rcOnCooldown) {
                // Slot-0 ability still cooling down.
            } else if (abilityCost > 0 && player.getMana() < abilityCost) {
                // Out of mana.
            } else {
                try {
                    Vector2f pos = new Vector2f(aimWx, aimWy);
                    UseAbilityPacket useAbility = UseAbilityPacket.from(this.getPlayer(), pos);
                    this.realmManager.getClient().sendRemote(useAbility);
                    this.lastAbilityTick = rcNow;
                    final long cd = this.effectiveAbilityCooldownMs(player, 0);
                    if (rcCds != null && rcCds.length > 0 && cd > 0) rcCds[0] = rcNow + cd;
                    player.triggerAttackAnimation();
                    if (abilityCost > 0) {
                        player.setMana(Math.max(0, player.getMana() - abilityCost));
                    }
                } catch (Exception e) {
                    PlayState.log.error("{} failed to send UseAbility packet", LOG_NS, e);
                }
            }
        }
    }

    /** SP-reduced cooldown for a hotbar slot, matching the server + tooltip:
     *  max(500, base - invested * cdReductionPerPointMs). 0 when the slot has
     *  no ability or no base cooldown. */
    private long effectiveAbilityCooldownMs(Player p, int slot) {
        final Ability ab = p.getActiveAbility(slot);
        if (ab == null) return 0L;
        final long base = ab.getBaseCooldownMs();
        if (base <= 0L) return 0L;
        final long red = (long) p.getSkillLevel(ab.getId()) * ab.getCdReductionPerPointMs();
        return Math.max(500L, base - red);
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

    /** Read-only version of the F-key scan: the closest nearby tile's
     *  interactionType (forge / fame_store / etc), or null, for the HUD hint. */
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

    /** Reset the interpolation anchor on an authoritative pos snap, else the
     *  next render frame lerps from the stale pre-snap pos (a per-tick hop). */
    public void resetInterpAnchor(float x, float y) {
        this.interpFromX = x;
        this.interpFromY = y;
        this.hasInterpAnchor = true;
        // Snap the camera too, or the smoother slides it across the world.
        this.cameraX = x;
        this.cameraY = y;
    }

    @Override
    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        Player player = this.realmManager.getRealm().getPlayer(this.playerId);
        if (player == null)
            return;
        // Switch to the zoomed world camera for tiles + entities; the HUD pass
        // below switches back to the UI camera.
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

        final List<Entity> visibleEntities = this.visibleEntities;
        final List<Bullet> visibleBullets = this.visibleBullets;
        final List<Enemy> visibleEnemies = this.visibleEnemies;
        visibleEntities.clear();
        visibleBullets.clear();
        visibleEnemies.clear();

        // Diagnostic entity/player census every ~300 frames.
        this.frameCounter++;
        if (this.frameCounter % 300 == 0) {
            int realmEnemies = this.realmManager.getRealm().getEnemies() != null
                    ? this.realmManager.getRealm().getEnemies().size() : 0;
            int realmBullets = this.realmManager.getRealm().getBullets() != null
                    ? this.realmManager.getRealm().getBullets().size() : 0;
            int realmPortals = this.realmManager.getRealm().getPortals() != null
                    ? this.realmManager.getRealm().getPortals().size() : 0;
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

        // BLIND: render-only cull of entities more than ~3 tiles from the local
        // player. Reach uses each body's half-size so a large enemy the player
        // stands on isn't culled by corner math.
        final Player localBlindPlayer = this.realmManager.getRealm().getPlayer(
                this.realmManager.getCurrentPlayerId());
        final boolean isBlind = localBlindPlayer != null
                && localBlindPlayer.hasEffect(StatusEffectType.BLIND);
        final float BLIND_RADIUS = 32f * 3f;
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
            // Keep the local player's role sticky, restoring it onto a re-created
            // local entry so the name color holds.
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
                if (b.isConsumedClient()) continue;
                // Hide OTHER players' projectiles (own + enemy shots still show).
                if (hideOtherBullets && b.getSrcEntityId() != localPlayerId
                        && this.realmManager.getRealm().getPlayers().containsKey(b.getSrcEntityId())) continue;
                // Local player's OWN bullets are BLIND-exempt so they can still aim.
                if (isBlind && b.getSrcEntityId() != localBlindId) {
                    final float half = b.getSize() / 2f;
                    final float dx = (b.getPos().x + half) - blindPx, dy = (b.getPos().y + half) - blindPy;
                    final float reach = BLIND_RADIUS + half;
                    if (dx * dx + dy * dy > reach * reach) continue;
                }
                visibleBullets.add(b);
            }
        }

        for (int i = 0; i < visibleEntities.size(); i++) {
            visibleEntities.get(i).updateEffectState();
        }

        // Ground shadows, BEFORE entity bodies so each sprite stands on its own
        // shadow. Entities, portals, and loot only (collision-object shadows are
        // drawn by TileManager under each sprite; redrawing here double-stacked).
        // One shapes pass batches all the ellipses (a tight per-frame loop).
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, 0.30f);
        for (int i = 0; i < visibleEntities.size(); i++) {
            final Entity ent = visibleEntities.get(i);
            final int s = ent.getSize() > 0 ? ent.getSize() : 32;
            final float wx = ent.getPos().getWorldVar().x + s * 0.5f;
            final float wy = ent.getPos().getWorldVar().y + s * 0.92f;
            shapes.ellipse(wx - s * 0.4f, wy - s * 0.06f, s * 0.8f, s * 0.24f);
        }
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

        // Entity sprite strokes (dark silhouette behind each body).
        if (gfx.isSpriteStroke()) {
            ShaderManager.clearEffect(batch);
            for (int i = 0; i < visibleEntities.size(); i++) {
                visibleEntities.get(i).renderStroke(batch);
            }
        }

        // Entity bodies grouped by effect to minimize shader switches.
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

        // Bullet outlines (behind), then FX particles, then bullet bodies.
        if (gfx.isSpriteStroke()) {
            for (int i = 0; i < visibleBullets.size(); i++) {
                visibleBullets.get(i).renderOutline(batch);
            }
        }
        this.projectileFx.emitAndUpdate(visibleBullets,
                this.realmManager.getRealm().getBullets(), Gdx.graphics.getDeltaTime());
        this.projectileFx.render(batch);
        final Realm poisonRealm = this.realmManager.getRealm();
        for (int i = 0; i < visibleBullets.size(); i++) {
            final Bullet b = visibleBullets.get(i);
            // Venom-coat a player's shots while they carry Imbue Poison.
            if (!b.isEnemy() && poisonRealm != null) {
                final Player owner = poisonRealm.getPlayer(b.getSrcEntityId());
                b.setPoisonTrail(owner != null && owner.hasEffect(StatusEffectType.IMBUED_POISON));
            }
            b.render(batch);
        }

        // Overhead bars (enemy HP, then player HP/MP, then cast overlays). One
        // shapes pass batches all the rects (a tight per-frame loop).
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
            // +Y is screen-down, so wy + size is the sprite's bottom edge.
            float barY = wy + enemy.getSize() + 2;
            shapes.setColor(0.2f, 0.2f, 0.2f, 0.8f);
            shapes.rect(wx, barY, barWidth, barHeight);
            shapes.setColor(1f, 0f, 0f, 0.9f);
            final float hpFrac = Math.max(0f, Math.min(1f, enemy.getHealthpercent()));
            shapes.rect(wx, barY, barWidth * hpFrac, barHeight);
        }
        // Cast overlay: translucent fill rising up the casting player's sprite.
        // Auto-clears when the duration elapses (no cast-finish packet).
        // Everything overhead anchors on getEffectiveRenderX/Y (the smoothly
        // interpolated pos the sprite uses), NOT raw pos, or the bar oscillates
        // against the moving sprite.
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
            // Below the sprite; +16 leaves room for the name (drawn at wy + s + 2).
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

        // Status-effect chips above each head. The chip BACKGROUNDS draw here in
        // the shapes pass; the labels draw in a later batch pass, so per-chip
        // layout coords are cached in these lists. Chips are sized 1/WORLD_SCALE
        // so their on-screen size matches the webclient's 40x14 screen pixels.
        final List<float[]> _statusChipLayout = new ArrayList<>();
        final List<String>  _statusChipLabels = new ArrayList<>();
        final float chipWS = OpenRealmGame.WORLD_SCALE;
        final float chipW = 40f / chipWS;
        final float chipH = 14f / chipWS;
        for (Player rp : gfx.isShowStatusBubbles()
                ? this.realmManager.getRealm().getPlayers().values()
                : Collections.<Player>emptyList()) {
            final Short[] effs = rp.getEffectIds();
            if (effs == null) continue;
            final int sSize = rp.getSize() > 0 ? rp.getSize() : 32;
            final float wx = rp.getEffectiveRenderX() - Vector2f.worldX;
            final float wy = rp.getEffectiveRenderY() - Vector2f.worldY;
            final float iconX = wx + (sSize * 0.5f) - (chipW * 0.5f);
            // Extra lift clears the nameplate, or the bottom chip sits behind the
            // name glyphs and the later batch.draw paints text over the icon.
            final float bottomY = wy - 22f / chipWS - 11f;
            this.emitStatusChips(shapes, effs, rp.getEffectStacks(), iconX, bottomY, chipW, chipH,
                    _statusChipLayout, _statusChipLabels);
        }

        // Enemy chips (lifted one row when a name label shows so they clear it).
        for (Enemy en : gfx.isShowStatusBubbles() ? visibleEnemies
                : Collections.<Enemy>emptyList()) {
            final Short[] effs = en.getEffectIds();
            if (effs == null) continue;
            final int sSize = en.getSize() > 0 ? en.getSize() : 32;
            final float wx = en.getPos().getWorldVar().x;
            final float wy = en.getPos().getWorldVar().y;
            final float iconX = wx + (sSize * 0.5f) - (chipW * 0.5f);
            final boolean named = this.shouldLabelEnemy(en);
            final float bottomY = wy - 4f - (named ? 16f / chipWS : 0f);
            this.emitStatusChips(shapes, effs, en.getEffectStacks(), iconX, bottomY, chipW, chipH,
                    _statusChipLayout, _statusChipLabels);
        }

        // Chat bubble BACKGROUNDS behind the bubble text (drawn in the nameplate
        // pass). Geometry mirrors that bubble-text formula exactly.
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
                this.chatBubbleLayoutScratch.setText(font, bubble.getMessage(), Color.BLACK, 180f / ws, Align.center, true);
                final float chatW = this.chatBubbleLayoutScratch.width;
                final float chatH = this.chatBubbleLayoutScratch.height;
                final float textTopY = wy - 12 - nameH - 4 - chatH;
                final float bgW = chatW + 2 * padX;
                final float bgH = chatH + 2 * padY;
                final float bgX = wx + (sSize * 0.5f) - (bgW * 0.5f);
                final float bgY = textTopY - padY;
                shapes.setColor(1f, 1f, 1f, 0.95f * bubble.alpha(now));
                this.drawRoundedRect(shapes, bgX, bgY, bgW, bgH, radius);
            }
            font.getData().setScale(prevScale);
        }
        shapes.end();

        // Status-chip labels, centered inside each chip painted above.
        if (!_statusChipLayout.isEmpty()) {
            batch.begin();
            final float prevScale = font.getData().scaleX;
            font.getData().setScale(0.45f / OpenRealmGame.WORLD_SCALE);
            for (int idx = 0; idx < _statusChipLayout.size(); idx++) {
                final float[] r = _statusChipLayout.get(idx);
                final String label = _statusChipLabels.get(idx);
                this.nameLayoutScratch.setText(font, label);
                font.setColor(Color.WHITE);
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

        // Player nameplates (world-camera batch, 0.5x font). Color follows
        // chatRole. Anchored on the lerped render pos so the name doesn't jitter.
        final float origScale = font.getData().scaleX;
        font.getData().setScale(0.5f);
        final long bubbleNowMs = System.currentTimeMillis();
        this.chatBubbles.values().removeIf(b -> b.isExpired(bubbleNowMs));
        for (Player rp : this.realmManager.getRealm().getPlayers().values()) {
            final String nm = rp.getName();
            if (nm == null || nm.isEmpty()) continue;
            final int s = rp.getSize() > 0 ? rp.getSize() : 32;
            final float wx = rp.getEffectiveRenderX() - Vector2f.worldX;
            final float wy = rp.getEffectiveRenderY() - Vector2f.worldY;
            this.nameLayoutScratch.setText(font, nm);
            font.setColor(roleColorFor(rp.getChatRole()));
            if (gfx.isShowPlayerNames()) {
                font.draw(batch, this.nameLayoutScratch,
                        wx + (s * 0.5f) - (this.nameLayoutScratch.width * 0.5f),
                        wy + s + 2);
            }
            // Chat bubble floats just above the nameplate, fading out at end of life.
            final ChatBubble bubble = gfx.isShowChatBubbles() ? this.chatBubbles.get(nm) : null;
            if (bubble != null && !bubble.isExpired(bubbleNowMs)) {
                this.chatBubbleLayoutScratch.setText(font, bubble.getMessage(),
                        new Color(0f, 0f, 0f, bubble.alpha(bubbleNowMs)),
                        180f / OpenRealmGame.WORLD_SCALE, Align.center, true);
                font.draw(batch, this.chatBubbleLayoutScratch,
                        wx + (s * 0.5f) - (this.chatBubbleLayoutScratch.width * 0.5f),
                        wy - 12 - this.nameLayoutScratch.height - 4 - this.chatBubbleLayoutScratch.height);
            }
        }
        // Enemy names intentionally not drawn (overhead health bar identifies them).
        font.getData().setScale(origScale);
        font.setColor(Color.WHITE);

        Collection<Portal> portals = this.realmManager.getRealm().getPortals().values();
        final float prevPortalScale = font.getData().scaleX;
        final Player me = this.getPlayer();
        for (Portal portal : portals) {
            portal.render(batch);
            final String portalLabel = portal.getTargetLabel();
            if (portalLabel == null || portalLabel.isEmpty()) continue;
            // Minimal name + badge by default; the portal the player stands on
            // ("focused") expands into a full card with purification + modifiers.
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

        // Loot bags MUST render here while the world projection is active
        // (LootContainer.render maps via pos.getWorldVar()), not after the UI
        // camera switch, or they draw at the wrong scale/position.
        for (LootContainer lc : this.realmManager.getRealm().getLoot().values()) {
            lc.render(batch);
        }

        if (gfx.isLootBagPreview()) {
            this.renderLootBagPreviews(batch, shapes);
        }

        if (this.pui == null)
            return;

        // Damage text uses WORLD coords, so it MUST render before the UI-camera
        // switch or the numbers land at the wrong screen position.
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
        // Vignette before the HUD so the panel/minimap stay fully lit.
        if (isBlind) this.renderBlindVignette(batch, shapes);
        this.pui.render(batch, shapes, font);

        this.renderCloseLoot(batch);

        // /dev overlay pinned above the minimap.
        if (PerfMetrics.get().isDevVisible()) {
            final Minimap devMinimap = this.pui.getMinimap();
            PerfMetrics.get().renderDevBar(batch, font,
                    devMinimap.getDrawX(), devMinimap.getDrawY(), devMinimap.getSizePx());
        }

        if (this.debugMode) {
            this.renderDebugTileOverlay(batch, shapes, font, player);
        }
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

        Tile baseTile = baseLayer.getBlocks()[tileRow][tileCol];
        Tile collTile = collisionLayer.getBlocks()[tileRow][tileCol];

        float drawX = (tileCol * tileSize) - PlayState.map.x;
        float drawY = (tileRow * tileSize) - PlayState.map.y;

        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 1f, 0f, 0.15f);
        shapes.rect(drawX, drawY, tileSize, tileSize);
        shapes.end();

        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0f, 1f, 0f, 1f);
        shapes.rect(drawX, drawY, tileSize, tileSize);
        shapes.end();

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

        if (tooltipX + tooltipWidth > OpenRealmGame.width) {
            tooltipX = mx - tooltipWidth - 8;
        }
        if (tooltipY + tooltipHeight > OpenRealmGame.height) {
            tooltipY = my - tooltipHeight - 8;
        }

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

        // Only the HUD ground-loot panel sync; bag sprites render in render().
        // The trade UI manages the ground-loot area while trading.
        if (this.getPui().isTrading()) {
            return;
        }

        // Match the server's ground-loot pickup radius so a surfaced bag is one
        // the server will accept clicks for.
        final int lootSearchRadius = player.getSize() + 24;
        final LootContainer closeLoot = this.getClosestLootContainer(player.getPos(), lootSearchRadius);

        if ((closeLoot != null && this.getPui().isGroundLootEmpty()) || (closeLoot != null && closeLoot.getContentsChanged())) {
            this.getPui().setGroundLoot(closeLoot.getItems());
        } else if ((closeLoot == null) && !this.getPui().isGroundLootEmpty()) {
            this.getPui().setGroundLoot(new GameItem[10]);
        }

        if (closeLoot != null && !this.getPui().isGroundLootEmpty()) {
            // Diff by itemId + stackCount, not just slot count, or a partial
            // stack pickup leaves the bag showing the pre-pickup stack.
            if (this.lootDiffersFromUI(closeLoot)) {
                this.getPui().setGroundLoot(closeLoot.getItems());
            }
        }
    }

    /** True if the container's items differ from the cached groundLoot UI
     *  snapshot. MUST treat null Slot and itemId==-1 as equivalent (setGroundLoot
     *  skips both), or it returns true every frame and rebuilds the loot Buttons
     *  each render, leaving their click bounds stale so every pickup click misses. */
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

    /** Emit one active-effect chip per set effect, stacking upward from bottomY,
     *  and record each chip's rect + label for the later label pass. Runs inside
     *  the caller's open Filled shapes pass. */
    private void emitStatusChips(ShapeRenderer shapes, Short[] effs, Short[] stacks,
            float iconX, float bottomY,
            float iconW, float iconH, List<float[]> outLayout, List<String> outLabels) {
        final float iconGap = 2f / OpenRealmGame.WORLD_SCALE;
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
            outLayout.add(new float[] { iconX, chipY, iconW, iconH });
            // Append "xN" for stacked DOTs so the player reads the intensity.
            final int stack = stackFor(effs, stacks, def.effectId);
            outLabels.add(stack > 1 ? (def.label + " x" + stack) : def.label);
            activeIdx++;
        }
    }

    private static int stackFor(Short[] effs, Short[] stacks, short eid) {
        if (effs == null || stacks == null) return 1;
        for (int i = 0; i < effs.length && i < stacks.length; i++) {
            if (effs[i] != null && effs[i] == eid) {
                return (stacks[i] != null) ? Math.max(1, stacks[i]) : 1;
            }
        }
        return 1;
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

    /** Read-only item grid under each ground-loot bag (world-camera space):
     *  dark backgrounds first, then icons. No pickup interaction. */
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

    /** Blind vignette: a clear tunnel around the centered player fading to dark.
     *  UI-camera space (1:1 screen pixels). */
    private void renderBlindVignette(SpriteBatch batch, ShapeRenderer shapes) {
        final float w = OpenRealmGame.width, h = OpenRealmGame.height;
        final float cx = w / 2f, cy = h / 2f;
        final float innerR = 32f * 3f * OpenRealmGame.WORLD_SCALE;
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

    /** Shuriken visuals for BLADE_ORBIT / BLADE_BLENDER (shared shuriken sprite,
     *  phase driven by wall-clock). Drawn inside an open SpriteBatch. */
    private void renderShurikenEffects(SpriteBatch batch) {
        if (this.activeEffects == null || this.activeEffects.isEmpty()) return;
        final long now = System.currentTimeMillis();
        final float wx = Vector2f.worldX;
        final float wy = Vector2f.worldY;
        // Only the newest packet per effect type renders (lowest elapsed), or
        // overlapping refresh packets draw at different phases and jitter.
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

    /** Per-archetype swing frames; null until a sheet is authored + loaded. */
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

    private boolean hasSwingSprite(short tier) {
        return swingFramesFor(tier) != null;
    }

    /** Sprite-override melee swing, drawn only when a sheet is authored (else the
     *  procedural drawMeleeSwing path handles it). */
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
            // Imbue Poison tints the swing venom-green while the swinger holds it.
            final Realm swingRealm = this.realmManager.getRealm();
            final Player swingOwner = (swingRealm == null || vfx.getOwnerId() == 0L)
                    ? null : swingRealm.getPlayer(vfx.getOwnerId());
            if (swingOwner != null && swingOwner.hasEffect(StatusEffectType.IMBUED_POISON)) {
                batch.setColor(0.45f, 1f, 0.35f, swingAlpha);
            } else {
                batch.setColor(1f, 1f, 1f, swingAlpha);
            }
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

    /** Rotating bracket reticle over each HOMING-projectile target. Red = the
     *  local player is targeted, amber = a lock on an enemy. */
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

    /** Melee aim indicator: expanding/fading rings at the cursor, clamped to the
     *  weapon's max melee range. */
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

        // MUST re-enable GL_BLEND: the preceding SpriteBatch.end() disabled it,
        // and without it every AoE disc renders fully opaque.
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        final float wx = Vector2f.worldX;
        final float wy = Vector2f.worldY;

        for (ActiveVisualEffect vfx : this.activeEffects) {
            final float t = vfx.getProgress();
            final short type = vfx.getEffectType();

            if (vfx.isAoe()) {
                boolean meleePoison = false;
                if (type == CreateEffectPacket.EFFECT_MELEE_SWING && vfx.getOwnerId() != 0L) {
                    final Realm r = this.realmManager.getRealm();
                    final Player owner = r == null ? null : r.getPlayer(vfx.getOwnerId());
                    meleePoison = owner != null && owner.hasEffect(StatusEffectType.IMBUED_POISON);
                }
                AbilityEffectRenderer.renderAoeEffect(shapes, vfx, type, t, wx, wy,
                        hasSwingSprite(vfx.getTier()), meleePoison);
            } else {
                AbilityEffectRenderer.renderLineEffect(shapes, vfx, t, wx, wy);
            }
        }
    }

}
