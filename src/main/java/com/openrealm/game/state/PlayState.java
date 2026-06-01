package com.openrealm.game.state;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.openrealm.account.dto.PlayerAccountDto;
import com.openrealm.game.OpenRealmGame;
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
import com.openrealm.game.model.ProjectileGroup;
import com.openrealm.game.ui.ActiveVisualEffect;
import com.openrealm.game.ui.EffectText;
import com.openrealm.game.ui.PlayerUI;
import com.openrealm.net.client.packet.CreateEffectPacket;
import com.openrealm.net.client.ClientGameLogic;
import com.openrealm.net.client.SocketClient;
import com.openrealm.net.messaging.CommandType;
import com.openrealm.net.messaging.LoginRequestMessage;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerClient;
import com.openrealm.net.server.packet.CommandPacket;
import com.openrealm.net.server.packet.MoveItemPacket;
import com.openrealm.net.server.packet.PlayerMovePacket;
import com.openrealm.net.server.packet.PlayerShootPacket;
import com.openrealm.net.server.packet.UseAbilityPacket;
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
import com.openrealm.game.graphics.Sprite;

@Data
@EqualsAndHashCode(callSuper = false)
@Slf4j
public class PlayState extends GameState {
    private RealmManagerClient realmManager;
    /** Server-wide players surfaced by GlobalPlayerPositionPacket — used
     *  ONLY by the minimap to plot dots for players who aren't in our
     *  local realm map. Webclient parity (game.minimapPlayers).
     *  Previously the global-pos handler was overwriting our local
     *  players' coords with these positions, which dragged the in-realm
     *  dots around as players in OTHER realms moved. */
    @lombok.Getter @lombok.Setter
    private com.openrealm.net.entity.NetPlayerPosition[] minimapPlayers =
            new com.openrealm.net.entity.NetPlayerPosition[0];
    private Queue<EffectText> damageText;
    private Queue<ActiveVisualEffect> activeEffects;
    // Phase 4 — party state mirror of webclient game.partyId / partyMembers.
    // Latest snapshot from PartyUpdatePacket. partyId == 0 means "not in
    // a party"; the UI hides the panel in that case.
    @lombok.Getter @lombok.Setter
    private long partyId = 0L;
    @lombok.Getter @lombok.Setter
    private com.openrealm.net.entity.NetPartyMember[] partyMembers =
            new com.openrealm.net.entity.NetPartyMember[0];
    /** Active cast bars by playerId — set by AbilityCastStartPacket handler,
     *  rendered as a bottom→top fill overlay on each casting player's
     *  sprite. Auto-cleared by the renderer when the cast completes. */
    @lombok.Getter
    private final java.util.Map<Long, long[]> activeCasts = new java.util.concurrent.ConcurrentHashMap<>();
    private List<Vector2f> shotDestQueue;
    private PlayerAccountDto account;
    private Camera cam;
    private PlayerUI pui;
    public static Vector2f map;
    public long lastShotTick = 0;
    public long lastAbilityTick = 0;
    private long lastQuickUseTick = 0;
    private long lastPortalTick = 0;
    private static final long QUICK_USE_COOLDOWN_MS = 250;
    private static final long PORTAL_COOLDOWN_MS = 1000;
    public long playerId = -1l;
    
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
    private final java.util.ArrayDeque<PendingInput> pendingInputs = new java.util.ArrayDeque<>(128);

    /** One sim-tick worth of input + the per-tick step magnitude that
     *  was active when the input was sent. Captured at send-time so the
     *  reconciler replay is exactly what the client originally simulated
     *  (spd stat / SPEEDY effect could otherwise change between send and
     *  ack). */
    private static final class PendingInput {
        final int seq;
        final float vx, vy, pxPerTick;
        /** Snapshot of PARALYZED at send time. Captured per-input so replay
         *  uses the exact effect state the server processed each input with —
         *  without this, a paralyze landing between send and ack would make
         *  the reconciler zero ALL queued inputs (movePlayer reads current
         *  state), diverging from the server which advanced the inputs that
         *  were sent BEFORE paralyze landed. SPEEDY / SLOWED are already
         *  captured implicitly via pxPerTick (computed at send time). */
        final boolean paralyzed;
        PendingInput(int seq, float vx, float vy, float pxPerTick, boolean paralyzed) {
            this.seq = seq; this.vx = vx; this.vy = vy; this.pxPerTick = pxPerTick;
            this.paralyzed = paralyzed;
        }
    }

    /** Visual-only smoothing offset applied to the local player's render
     *  position when reconciliation finds a small mismatch (collision /
     *  slow-tile divergence). The logical pos is snapped to the replay
     *  result for accurate next-tick collisions, while the visual diff
     *  decays toward zero each frame so the user doesn't see a hop. */
    private float smoothingOffsetX = 0f;
    private float smoothingOffsetY = 0f;

    /** Camera rotation around the player, radians. 0 = north-up. Held
     *  continuously while Q (left, +) or E (right, -) is down; C snaps
     *  back to 0. Mirrors webclient game.cameraAngle so muscle memory
     *  carries between clients. The rotation is applied to the world
     *  OrthographicCamera before tile/entity render; the input vx/vy is
     *  pre-rotated by -cameraAngle so W still walks toward screen-north
     *  regardless of how the world is turned. */
    private float cameraAngle = 0f;
    private static final float CAM_ROTATE_SPEED = 2.4f; // rad/sec while held

    private long castRingExpiresAt = 0L;
    private float castRingCx, castRingCy, castRingRadius;
    private static final long CAST_RING_DURATION_MS = 700L;

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
            log.error("Failed to send initial LoginRequest, returning to character select. Reason: {}",
                    e.getMessage());
            this.connectError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return;
        }
        WorkerThread.submitAndForkRun(this.realmManager);
    }

    /**
     * Set when the initial login send fails. The state's first update() tick
     * detects this and pops PlayState back to CharacterSelectState with the
     * message, so the user can edit the server host and retry.
     */
    private String connectError = null;
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
            final com.openrealm.game.tile.TileManager tm =
                    this.realmManager.getRealm().getTileManager();
            for (int i = 0; i < gameObject.length; i++) {
                if (gameObject[i] instanceof Enemy) {
                    ((Enemy) gameObject[i]).update(this.getRealmManager(), time);
                } else if (gameObject[i] instanceof Bullet) {
                    final Bullet bul = (Bullet) gameObject[i];
                    bul.update(bulletScale);
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
                    playerOther.update(time);
                    // Mirror webclient's per-frame extrapolation for remote
                    // entities: lerp pos toward targetX/Y at the velocity
                    // the server reported. Without this we'd just sit at
                    // the LoadPacket spawn pos forever (movePlayer would
                    // burn CPU on dx/dy without smoothing toward the
                    // authoritative target). refX/refY is the local
                    // player center for the viewport gate so a
                    // far-away remote freezes instead of drifting off-map
                    // in the few seconds after we leave them behind.
                    final float localHalf = (player.getSize() > 0 ? player.getSize() : 32) * 0.5f;
                    final float refX = player.getPos().x + localHalf;
                    final float refY = player.getPos().y + localHalf;
                    playerOther.extrapolate(refX, refY, true);
                }
            }

            if (bulletsToCull != null) {
                final Map<Long, Bullet> bulletMap = clientRealm.getBullets();
                for (final Long bid : bulletsToCull) {
                    bulletMap.remove(bid);
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
                    PlayState.log.error("Failed to build player shoot packet. Reason: {}", e.getMessage());
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
            for (java.util.Iterator<EffectText> it = this.damageText.iterator(); it.hasNext(); ) {
                final EffectText text = it.next();
                text.update();
                if (text.getRemove()) {
                    it.remove();
                }
            }

            final float deltaMs = (float) (time * 1000.0);
            for (java.util.Iterator<ActiveVisualEffect> it = this.activeEffects.iterator(); it.hasNext(); ) {
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
        final float dx = p.getDx();
        final float dy = p.getDy();

        // Resolve axis-blocked state UPFRONT (no pos mutation yet) so the
        // diagonal corner-cut prevention below can also see the
        // both-axes-free case. Webclient parity: game.js _checkCollision
        // path runs all three queries (x-only, y-only, diagonal) before
        // committing any motion.
        boolean xBlocked = tm.collisionTile(p, dx, 0)
                || tm.collidesXLimit(p, dx)
                || tm.isVoidTile(scratch, dx, 0);
        boolean yBlocked = tm.collisionTile(p, 0, dy)
                || tm.collidesYLimit(p, dy)
                || tm.isVoidTile(scratch, 0, dy);

        // Diagonal corner-cutting prevention (mirrors webclient game.js
        // simulateTick lines 522-528). When neither axis is blocked but
        // the diagonal IS blocked, the player would otherwise clip through
        // a wall corner. Block the axis with the smaller |delta| so the
        // player slides along the larger axis instead — same heuristic
        // the server uses.
        if (!xBlocked && !yBlocked && dx != 0f && dy != 0f) {
            if (tm.collisionTile(p, dx, dy) || tm.isVoidTile(scratch, dx, dy)) {
                if (Math.abs(dx) >= Math.abs(dy)) yBlocked = true;
                else xBlocked = true;
            }
        }

        if (!xBlocked) {
            p.xCol = false;
            if (dx != 0f) {
                if (tm.collidesSlowTile(p)) p.getPos().x += dx / 3.0f;
                else                         p.getPos().x += dx;
            }
        } else {
            p.xCol = true;
        }

        // Refresh scratch after the X-axis update — pos may have moved.
        scratch.x = p.getPos().x + halfSize;
        scratch.y = p.getPos().y + halfSize;
        if (!yBlocked) {
            p.yCol = false;
            if (dy != 0f) {
                if (tm.collidesSlowTile(p)) p.getPos().y += dy / 3.0f;
                else                         p.getPos().y += dy;
            }
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
                if (input.paralyzed) continue;
                local.setDx(input.vx * input.pxPerTick);
                local.setDy(input.vy * input.pxPerTick);
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
        //   MultishotGem (gemstoneType=3) → +2 extras
        // Spread / range / piercing also pulled from the archetype so a
        // pierce-archetype bow's predicted bullet carries PASS_THROUGH_ENEMIES
        // and matches the server bullet's flag set.
        final com.openrealm.game.model.WeaponArchetypeModel _archShot =
                (weapon == null || weapon.getArchetypeId() <= 0 || com.openrealm.game.data.GameDataManager.WEAPON_ARCHETYPES == null)
                        ? null
                        : com.openrealm.game.data.GameDataManager.WEAPON_ARCHETYPES.get(weapon.getArchetypeId());
        final int archCount  = (_archShot != null && _archShot.getProjectileCount() > 0)
                ? _archShot.getProjectileCount() : 1;
        final int gemMulti   = (weapon != null && weapon.getGemstoneType() == 3 /* MultishotGem */) ? 2 : 0;
        final float SPREAD   = (_archShot != null && _archShot.getSpreadRad() > 0f)
                ? _archShot.getSpreadRad() : 0.12f;
        final float rangeMul = (_archShot != null && _archShot.getRangeMul() > 0f)
                ? _archShot.getRangeMul() : 1.0f;
        final boolean archPierces = _archShot != null && _archShot.isPiercing();
        final int totalBullets = archCount + gemMulti;
        log.info("[shoot-predict] weapon='{}' projGroupId={} archCount={} gemMulti={} totalBullets={} enchants={}",
                weapon.getName(), projGroupId, archCount, gemMulti, totalBullets,
                weapon.getEnchantments() == null ? 0 : weapon.getEnchantments().size());

        for (final com.openrealm.game.model.Projectile proj : group.getProjectiles()) {
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

        // Camera rotation — viewport-relative, mirrors the webclient bindings
        // exactly (game.js controls.bindings.rotateLeft = KeyQ, rotateRight =
        // KeyE, resetCamera = KeyC). Held-key continuous rotation rather than
        // per-press snap so the player can tune the angle to taste.
        //
        // Q rotates the VIEWPORT left (camera tilts left from the player's
        // POV) which makes the WORLD appear to spin RIGHT on screen. E does
        // the opposite. Concretely:
        //   Q  →  cameraAngle += delta  →  world spins CW visually
        //   E  →  cameraAngle -= delta  →  world spins CCW visually
        // This sign convention matches the webclient (worldLayer.rotation =
        // +cameraAngle, PIXI CW for positive in Y-down), and the camera apply
        // below uses worldCam.rotate(-degrees(cameraAngle), 0,0,1) which
        // produces the same visual under LibGDX setToOrtho(true). Keeping
        // the convention identical means the movement rotation in the
        // simulate loop (cos(-cameraAngle)) and the screen→world aim rotation
        // (also cos(-cameraAngle)) all stay self-consistent — pressing W
        // walks the player toward screen-north and the mouse cursor maps to
        // the world tile it visually overlaps under any rotation.
        final float camDt = Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f);
        if (key.q.down) this.cameraAngle += CAM_ROTATE_SPEED * camDt;
        if (key.e.down) this.cameraAngle -= CAM_ROTATE_SPEED * camDt;
        if (key.c.down) this.cameraAngle = 0f;

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

                // Rotate screen-space input into world-space by -cameraAngle
                // so W (screen-north) walks toward whatever the camera shows
                // as north, even when the world has been rotated by Q/E.
                // Matches webclient screenDirFlagsToWorldVector in main.js.
                if (this.cameraAngle != 0f && (vx != 0f || vy != 0f)) {
                    final float cs = (float) Math.cos(-this.cameraAngle);
                    final float sn = (float) Math.sin(-this.cameraAngle);
                    final float rvx = vx * cs - vy * sn;
                    final float rvy = vx * sn + vy * cs;
                    vx = rvx;
                    vy = rvy;
                }

                // Per-tick pixel step. RotMG: tiles/sec = 4 + 5.6 * (spd/75).
                // Status modifiers MUST match webclient game.js simulateTick
                // exactly so client + server (and replay) all agree on the
                // step magnitude per tick.
                float tilesPerSec = 4.0f + 5.6f * (player.getComputedStats().getSpd() / 75.0f);
                if (player.hasEffect(StatusEffectType.SPEEDY)) tilesPerSec *= 1.5f;
                if (player.hasEffect(StatusEffectType.SLOWED)) tilesPerSec *= 0.5f;
                final float pxPerTick = tilesPerSec * 32.0f / TICK_RATE;

                // Save preTick BEFORE we drain — this is the lerp's "from".
                final float preTickX = player.getPos().x;
                final float preTickY = player.getPos().y;

                int ticks = 0;
                while (this.moveAccumulator >= TICK_DT) {
                    this.moveAccumulator -= TICK_DT;
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
                        this.pendingInputs.addLast(new PendingInput(seq, vx, vy, pxPerTick, paralyzedAtSend));
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
                        PlayState.log.error("Failed to create player move packet. Reason: {}", e);
                    }

                    ticks++;
                }

                if (ticks > 0) {
                    this.interpFromX = preTickX;
                    this.interpFromY = preTickY;
                    this.hasInterpAnchor = true;
                }

                // EXPERIMENT A: capture the per-tick step so render() can
                // EXTRAPOLATE forward from the latest tick rather than
                // INTERPOLATE backward from the previous tick. Lerp from
                // the OLD position always renders 1 tick behind the
                // simulation (frac stays small at 60fps + 64Hz tick =>
                // renderX ~= preTickX); extrapolating from the NEW
                // position renders where the physics is heading this
                // frame, so the sprite is always at-or-ahead of the
                // sim, never behind. Direction-change overshoot is at
                // most 1 tick worth (~3-5 px) and gets corrected on
                // the next tick.
                this.lastTickStepX = vx * pxPerTick;
                this.lastTickStepY = vy * pxPerTick;

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

                // Render position via EXTRAPOLATION (Experiment A). The
                // accumulator's leftover fraction projects forward by
                // up to one tick worth of the most-recent velocity:
                //
                //   renderX = pos.x + (acc / TICK_DT) * lastTickStep
                //
                // pos is the LATEST simulated state; we extrapolate
                // toward where the next tick will land. As the next
                // tick fires, pos advances and accumulator resets,
                // so renderX walks continuously without snap-back
                // (the old interp formula could snap backward on
                // multi-tick frames because it lerped from preTick
                // through 2 ticks of advance, briefly putting render
                // behind the visible position).
                final float interpFrac = Math.max(0f, Math.min(1f, this.moveAccumulator / TICK_DT));
                float renderX = player.getPos().x + this.lastTickStepX * interpFrac;
                float renderY = player.getPos().y + this.lastTickStepY * interpFrac;

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
                    PlayState.log.error("Failed to send test UsePortalPacket", e.getMessage());
                }

            }
            // R = teleport to Nexus (map 29). Mirrors the web client's
            // hotkey. Suppressed while the chat input is capturing keys
            // so the user can type 'r' in messages without TPing out.
            if (!key.captureMode
                    && Gdx.input.isKeyJustPressed(Input.Keys.R)
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
                    PlayState.log.error("Failed to send Nexus UsePortalPacket", e.getMessage());
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
                    PlayState.log.error("Failed to send test UsePortalPacket", e.getMessage());
                }

            }

            // F = interact with nearby tile (forge / fame store / etc).
            // Mirrors web client's updateInteractPrompt + triggerNearbyInteract:
            // scan a 5x5 window around the player, pick the closest tile whose
            // TileModel has a non-empty interactionType, send InteractTilePacket.
            // Server replies with OpenForgePacket / OpenFameStorePacket.
            if (!key.captureMode && Gdx.input.isKeyJustPressed(Input.Keys.F)) {
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
                    PlayState.log.error("Failed to send InteractTilePacket. Reason: {}", e.getMessage());
                }
            }
            if (this.pui != null) {
                this.pui.input(mouse, key);
            }
            boolean canQuickUse = (System.currentTimeMillis() - this.lastQuickUseTick) > QUICK_USE_COOLDOWN_MS;
            if (canQuickUse) {
                // Shift held → address slots 13..20 (second half of the 16-slot
                // grid). Plain 1-8 → slots 5..12 (first half). Phase 1B grew
                // equipment from 4 → 5 slots, so backpack starts at 5 now
                // (not 4). Without this update, plain-1 read inv[4] (the ring
                // EQUIP slot) and nothing in the bag was reachable via hotkey,
                // which is what broke shift+# for gauntlets/boots specifically.
                final boolean shiftHeld = com.badlogic.gdx.Gdx.input.isKeyPressed(com.badlogic.gdx.Input.Keys.SHIFT_LEFT)
                        || com.badlogic.gdx.Gdx.input.isKeyPressed(com.badlogic.gdx.Input.Keys.SHIFT_RIGHT);
                final int base = shiftHeld ? 13 : 5;
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
                if (key.m.clicked) this.pui.getMinimap().toggle();
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
			final com.openrealm.game.entity.item.GameItem _w = player.getInventory()[0];
			final com.openrealm.game.model.WeaponArchetypeModel _archFR =
					(_w == null || _w.getArchetypeId() <= 0 || com.openrealm.game.data.GameDataManager.WEAPON_ARCHETYPES == null)
							? null
							: com.openrealm.game.data.GameDataManager.WEAPON_ARCHETYPES.get(_w.getArchetypeId());
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
        // Screen → world conversion that accounts for cameraAngle. Mirrors
        // webclient renderer.js getWorldCoords: subtract screen center, rotate
        // by -cameraAngle, divide by WORLD_SCALE, add back the world-space
        // pivot (player center). The previous formula
        //   aim = mouse * invScale + PlayState.map
        // was a pure translation that assumed an axis-aligned camera; under
        // any non-zero cameraAngle it landed aim/shots on a world point that
        // doesn't match where the cursor visually points, so basic attacks
        // and abilities fired toward the wrong tile whenever Q/E had been
        // pressed. WORLD_SCALE=2 ⇒ 1 screen px = 1/2 world px.
        final float invScale = 1f / OpenRealmGame.WORLD_SCALE;
        final float screenCx = OpenRealmGame.width  * 0.5f;
        final float screenCy = OpenRealmGame.height * 0.5f;
        final float pivotWx = player.getPos().x + player.getSize() * 0.5f;
        final float pivotWy = player.getPos().y + player.getSize() * 0.5f;
        final float sdx = mouse.getX() - screenCx;
        final float sdy = mouse.getY() - screenCy;
        final float aimCs = (float) Math.cos(-this.cameraAngle);
        final float aimSn = (float) Math.sin(-this.cameraAngle);
        final float aimWx = pivotWx + (sdx * aimCs - sdy * aimSn) * invScale;
        final float aimWy = pivotWy + (sdx * aimSn + sdy * aimCs) * invScale;
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
                    PlayState.log.error("Failed to send UseAbility packet from hotbar click for slot {}. Reason: {}",
                            bindingIdx, e);
                }
            }
        }

        // Phase 2C/2D — number-key hotbar mapping. Keys 1..4 fire the four
        // hotbar slots at the cursor; Shift+1..4 invests a skill point
        // into the bound ability (server enforces cap + pool). Mirrors
        // webclient main.js Digit1..Digit4 handlers. We send the packet
        // BEFORE the right-click branch below so the dedicated slot key
        // wins over the legacy "right-click fires slot 0" path.
        {
            final boolean shiftSp = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)
                    || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);
            final int[] digitKeys = { Input.Keys.NUM_1, Input.Keys.NUM_2, Input.Keys.NUM_3, Input.Keys.NUM_4 };
            for (int slot = 0; slot < 4; slot++) {
                if (!Gdx.input.isKeyJustPressed(digitKeys[slot])) continue;
                if (this.pui != null && this.pui.isHoveringInventory(mouse.getX())) continue;
                if (shiftSp) {
                    try {
                        com.openrealm.net.server.packet.InvestSkillPointPacket pkt =
                                new com.openrealm.net.server.packet.InvestSkillPointPacket((byte) slot);
                        this.realmManager.getClient().sendRemote(pkt);
                        // Optimistic local mirror so the SP pip column updates
                        // immediately — server-authoritative state lands on
                        // the next sync.
                        final com.openrealm.game.model.ability.Ability ab = this.getPlayer().getActiveAbility(slot);
                        if (ab != null) this.getPlayer().investSkillPoint(ab.getId());
                    } catch (Exception e) {
                        PlayState.log.error("Failed to send InvestSkillPoint packet. Reason: {}", e);
                    }
                } else if (canUseAbility) {
                    try {
                        Vector2f pos = clampCastPos(player, slot, aimWx, aimWy);
                        UseAbilityPacket useAbility = UseAbilityPacket.from(this.getPlayer(), pos, slot);
                        this.realmManager.getClient().sendRemote(useAbility);
                        this.lastAbilityTick = System.currentTimeMillis();
                    } catch (Exception e) {
                        PlayState.log.error("Failed to send UseAbility packet for slot {}. Reason: {}", slot, e);
                    }
                }
            }
        }

        if ((mouse.isPressed(3)) && canUseAbility && (this.pui == null || !this.pui.isHoveringInventory(mouse.getX()))) {
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
                    PlayState.log.error("Failed to send UseAbility packet. Reason: {}", e);
                }
            }
        }
    }

    @SuppressWarnings("unused")
    private CharacterClass currentPlayerCharacterClass() {
        return CharacterClass.valueOf(this.getPlayer().getClassId());
    }

    private Vector2f clampCastPos(Player p, int bindingIdx, float rawX, float rawY) {
        final com.openrealm.game.model.ability.Ability ab = p.getActiveAbility(bindingIdx);
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
    /** Per-tick step (px) the player JUST moved on the latest sim tick.
     *  Used by the render-extrapolation formula: renderX = pos.x +
     *  (acc / TICK_DT) * lastTickStepX. Captured inside the tick loop
     *  so direction changes between frames don't smear the prediction
     *  with a stale velocity. */
    private float lastTickStepX = 0f, lastTickStepY = 0f;
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

    private final com.badlogic.gdx.math.Matrix4 worldRotMatrix = new com.badlogic.gdx.math.Matrix4();
    private final com.badlogic.gdx.math.Matrix4 worldRotMatrixIdt = new com.badlogic.gdx.math.Matrix4();

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
            final com.badlogic.gdx.graphics.OrthographicCamera worldCam = game.getWorldCamera();
            worldCam.up.set(0f, -1f, 0f);
            worldCam.direction.set(0f, 0f, 1f);
            worldCam.update();
            batch.setProjectionMatrix(worldCam.combined);
            shapes.setProjectionMatrix(worldCam.combined);

            if (this.cameraAngle != 0f) {
                final float halfSize = player.getSize() / 2f;
                final float pivotX = player.getEffectiveRenderX() + halfSize - Vector2f.worldX;
                final float pivotY = player.getEffectiveRenderY() + halfSize - Vector2f.worldY;
                this.worldRotMatrix.idt();
                this.worldRotMatrix.translate(pivotX, pivotY, 0f);
                this.worldRotMatrix.rotate(0f, 0f, 1f, (float) Math.toDegrees(this.cameraAngle));
                this.worldRotMatrix.translate(-pivotX, -pivotY, 0f);
                batch.setTransformMatrix(this.worldRotMatrix);
                shapes.setTransformMatrix(this.worldRotMatrix);
            } else {
                batch.setTransformMatrix(this.worldRotMatrixIdt);
                shapes.setTransformMatrix(this.worldRotMatrixIdt);
            }
        }
        this.realmManager.getRealm().getTileManager().render(player, batch, shapes, this.cameraAngle);

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
                sb.append("[RENDER] players(").append(ps.size()).append(")=");
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
                log.info(sb.toString());
            } catch (Exception ignored) {}
            log.info("[RENDER] realm[enemies={} bullets={} portals={}] viewport[objs={}]",
                    realmEnemies, realmBullets, realmPortals, gameObject.length);
        }

        // BLIND status — clamp visible radius around the local player. Same
        // semantics as the webclient: enemies, bullets, and other players
        // outside ~3 tiles vanish from the local view. Server stays
        // authoritative on positions; this is pure render-side cull so the
        // player can't see what's about to hit them.
        final Player localBlindPlayer = this.realmManager.getRealm().getPlayer(
                this.realmManager.getCurrentPlayerId());
        final boolean isBlind = localBlindPlayer != null
                && localBlindPlayer.hasEffect(com.openrealm.game.contants.StatusEffectType.BLIND);
        final float BLIND_RADIUS = 32f * 3f;
        final float BLIND_RADIUS_SQ = BLIND_RADIUS * BLIND_RADIUS;
        final float blindPx = isBlind ? localBlindPlayer.getPos().x : 0f;
        final float blindPy = isBlind ? localBlindPlayer.getPos().y : 0f;
        final long localBlindId = isBlind ? localBlindPlayer.getId() : 0L;

        for (Player p : this.realmManager.getRealm().getPlayers().values()) {
            if (isBlind && p.getId() != localBlindId) {
                final float dx = p.getPos().x - blindPx, dy = p.getPos().y - blindPy;
                if (dx * dx + dy * dy > BLIND_RADIUS_SQ) continue;
            }
            visibleEntities.add(p);
            p.updateAnimation();
        }

        for (int i = 0; i < gameObject.length; i++) {
            if (gameObject[i] instanceof Enemy) {
                Enemy e = (Enemy) gameObject[i];
                if (isBlind) {
                    final float dx = e.getPos().x - blindPx, dy = e.getPos().y - blindPy;
                    if (dx * dx + dy * dy > BLIND_RADIUS_SQ) continue;
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
                // BLIND cull — bullets outside the tunnel radius vanish.
                // Local player's OWN bullets are exempt so they can still aim.
                if (isBlind && b.getSrcEntityId() != localBlindId) {
                    final float dx = b.getPos().x - blindPx, dy = b.getPos().y - blindPy;
                    if (dx * dx + dy * dy > BLIND_RADIUS_SQ) continue;
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
        // Decoration collision objects (trees, rocks, statues). The
        // TileManager populates these buffers during its render() above;
        // they're still valid here because we haven't yet entered the
        // next frame. Smaller / lower-alpha shadow than entities so a
        // forest of trees doesn't look like a forest of dark blobs.
        //
        // We intentionally SKIP overWaterTiles (collision tiles whose
        // base is a slowing liquid — water, lava). A shadow doesn't
        // belong on a fluid surface. Matches the webclient PASS 2
        // base-slows check.
        final TileManager tm = this.realmManager.getRealm().getTileManager();
        if (tm != null) {
            shapes.setColor(0f, 0f, 0f, 0.25f);
            final List<Tile> objTiles = tm.getObjectTilesView();
            for (int i = 0; i < objTiles.size(); i++) {
                final Tile t = objTiles.get(i);
                final int s = t.getWidth() > 0 ? t.getWidth() : 32;
                final float wx = t.getPos().getWorldVar().x + s * 0.5f;
                final float wy = t.getPos().getWorldVar().y + s * 0.95f;
                shapes.ellipse(wx - s * 0.32f, wy - s * 0.045f, s * 0.64f, s * 0.18f);
            }
            // overWaterTiles: shadows deliberately omitted (see comment above).
        }
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

        // Pass 3: Bullet bodies (no shader needed)
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
            float barY = wy - 6;
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
            final float hpY = wy - 10;
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
        final java.util.List<float[]> _statusChipLayout = new java.util.ArrayList<>();
        final java.util.List<String>  _statusChipLabels = new java.util.ArrayList<>();
        for (Player rp : this.realmManager.getRealm().getPlayers().values()) {
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
                // libGDX uses a flipped ortho, so font.draw y argument is
                // the top of the glyph baseline — center text vertically
                // by aligning baseline to chip middle + half text height.
                final float tx = r[0] + (r[2] - this.nameLayoutScratch.width) * 0.5f;
                final float ty = r[1] + (r[3] + this.nameLayoutScratch.height) * 0.5f;
                font.draw(batch, this.nameLayoutScratch, tx, ty);
            }
            font.getData().setScale(prevScale);
            font.setColor(Color.WHITE);
            batch.end();
            // Leave shapes ENDED to match the original flow that the
            // following renderVisualEffects pass expects (it manages its
            // own begin/end pairs).
        }

        // Pass 5: Visual ability effects (rings, arcs, particles)
        this.renderVisualEffects(shapes);

        Gdx.gl.glDisable(GL20.GL_BLEND);
        batch.begin();
        // Pass 5b: Ninja shuriken visuals — BLADE_ORBIT + BLADE_BLENDER both
        // need REAL shuriken sprites (not shape primitives) to match the
        // item icons. Drawn inside the open batch so they Z-sort with
        // entities + nameplate text below.
        this.renderShurikenEffects(batch);

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
            // y = top of HP bar minus 2px gap. HP bar sits at wy - 10, so
            // the name's bottom baseline is at wy - 12. Add the layout
            // height since GlyphLayout uses a top-anchor in flipped ortho.
            font.draw(batch, this.nameLayoutScratch,
                    wx + (s * 0.5f) - (this.nameLayoutScratch.width * 0.5f),
                    wy - 12 - this.nameLayoutScratch.height);
        }
        font.getData().setScale(origScale);
        font.setColor(Color.WHITE);

        Collection<Portal> portals = this.realmManager.getRealm().getPortals().values();
        for (Portal portal : portals) {
            portal.render(batch);
        }

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

        if (this.pui == null)
            return;

        // Damage text uses WORLD coords (sourcePos - Vector2f.worldX/Y),
        // so render it BEFORE flipping to the UI camera. Otherwise the
        // numbers paint at world-pixel positions through the UI projection
        // — which puts a hit that occurred at world (300, 200) at screen
        // (300, 200) instead of at the actual sprite location. Flush the
        // batch first so any prior world-space draws complete before the
        // next pass starts.
        for (EffectText text : this.getDamageText()) {
            text.render(batch, font);
        }

        if (game.getUiCamera() != null) {
            game.getUiCamera().update();
            batch.setProjectionMatrix(game.getUiCamera().combined);
            shapes.setProjectionMatrix(game.getUiCamera().combined);
            batch.setTransformMatrix(this.worldRotMatrixIdt);
            shapes.setTransformMatrix(this.worldRotMatrixIdt);
        }
        this.pui.render(batch, shapes, font);

        this.renderCloseLoot(batch);

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
            this.getPui().setGroundLoot(new GameItem[8]);
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
        final com.openrealm.game.ui.Slots[] uiSlots = this.getPui().getGroundLoot();
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

    private void handleQuickUseKey(int slotIndex) {
        try {
            GameItem from = this.getPlayer().getInventory()[slotIndex];
            if (from == null) return;
            boolean consume = from.isConsumable();
            MoveItemPacket moveItem = MoveItemPacket.from(from.getTargetSlot(), (byte) slotIndex, false, consume);
            this.realmManager.getClient().sendRemote(moveItem);
        } catch (Exception e) {
            PlayState.log.error("Failed to send move item packet: {}", "No Item in slot");
        }
    }

    public Player getPlayer() {
        return this.realmManager.getRealm().getPlayer(this.playerId);
    }

    /**
     * Render all active visual effects using ShapeRenderer.
     * Called between batch.end() and batch.begin() while blending is enabled.
     */
    /** Cached chat-role nameplate colors. Mirrors webclient renderer.js
     *  GameRenderer.getNameColorHex. Static so we don't allocate a Color
     *  per name draw. */
    private static final Color ROLE_SYSADMIN = new Color(1.00f, 0.25f, 0.25f, 1f);
    private static final Color ROLE_ADMIN    = new Color(0.25f, 0.50f, 0.88f, 1f);
    private static final Color ROLE_MOD      = new Color(0.25f, 0.75f, 0.25f, 1f);
    private static final Color ROLE_EDITOR   = new Color(0.63f, 0.25f, 0.75f, 1f);
    private static final Color ROLE_DEMO     = new Color(0.80f, 0.80f, 0.80f, 1f);
    private static final Color ROLE_DEFAULT  = new Color(0.93f, 0.93f, 0.93f, 1f);

    private static Color roleColorFor(String role) {
        if (role == null) return ROLE_DEFAULT;
        switch (role) {
            case "sysadmin": return ROLE_SYSADMIN;
            case "admin":    return ROLE_ADMIN;
            case "mod":      return ROLE_MOD;
            case "editor":   return ROLE_EDITOR;
            case "demo":     return ROLE_DEMO;
            default:         return ROLE_DEFAULT;
        }
    }

    /**
     * Status icon palette mirrors webclient renderer.js STATUS_ICON_DEFS so
     * the same effect renders the same color on both clients (player can
     * identify "green DoT pip = poison" from any client they're using).
     */
    private static final class StatusEffectIconDef {
        final short effectId;
        final String label;
        final float r, g, b;
        StatusEffectIconDef(short effectId, String label, int rgb) {
            this.effectId = effectId;
            this.label = label;
            this.r = ((rgb >> 16) & 0xFF) / 255f;
            this.g = ((rgb >>  8) & 0xFF) / 255f;
            this.b = ( rgb        & 0xFF) / 255f;
        }
    }

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
        new StatusEffectIconDef(StatusEffectType.INVISIBLE.effectId,    "Hide",   0xCCBB88),
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

    private static boolean hasEffectId(Short[] effs, short eid) {
        if (effs == null) return false;
        for (Short s : effs) {
            if (s != null && s == eid) return true;
        }
        return false;
    }

    /**
     * Cached shuriken texture regions, one per tier 0..5. Indexed by tier
     * (col = 10 + tier on row 16 of openrealm-items.png). Lazily filled the
     * first time a blade-orbit/blender effect renders. Frames are flipped
     * once at load to match LibGDX's bottom-left origin convention; the
     * SpriteBatch.draw calls below pass the un-flipped TextureRegion and
     * rely on this baked-in orientation.
     */
    private TextureRegion[] _shurikenRegions;
    private TextureRegion getShurikenRegion(int tier) {
        if (_shurikenRegions == null) _shurikenRegions = new TextureRegion[6];
        final int t = Math.max(0, Math.min(5, tier));
        if (_shurikenRegions[t] != null) return _shurikenRegions[t];
        try {
            com.badlogic.gdx.graphics.Texture tex =
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

    private void renderVisualEffects(ShapeRenderer shapes) {
        if (this.activeEffects.isEmpty()) return;

        final float wx = Vector2f.worldX;
        final float wy = Vector2f.worldY;

        for (ActiveVisualEffect vfx : this.activeEffects) {
            final float t = vfx.getProgress();
            final short type = vfx.getEffectType();

            if (vfx.isAoe()) {
                renderAoeEffect(shapes, vfx, type, t, wx, wy);
            } else {
                renderLineEffect(shapes, vfx, t, wx, wy);
            }
        }
    }

    private void renderAoeEffect(ShapeRenderer shapes, ActiveVisualEffect vfx, short type, float t, float wx, float wy) {
        final float cx = vfx.getPosX() - wx;
        final float cy = vfx.getPosY() - wy;
        final float maxRadius = vfx.getRadius();

        // Water fountain has its own procedural renderer (parabolic-arc
        // droplets + splash ripples), not the standard ring/particle setup.
        if (type == CreateEffectPacket.EFFECT_WATER_FOUNTAIN) {
            renderWaterFountain(shapes, vfx, cx, cy, maxRadius);
            return;
        }

        // Boss-grenade warning / impact (Enemy 26). Tier >= 10 is the
        // sentinel the boss script uses to ask for a *much* more visible
        // ring than the default CURSE_RADIUS — the standard renderer's
        // 35% fill reads as faint over the spiral arms + the ground tiles.
        // This path snaps to full radius, paints a 55%-opacity red disc,
        // and pulses the outline so the player can read the danger zone
        // through bullet clutter.
        if (vfx.getTier() >= 10) {
            final float bossAlpha = t < 0.7f ? 1.0f : 1.0f - (t - 0.7f) * 3.33f;

            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(1.0f, 0.05f, 0.05f, bossAlpha * 0.95f);
            drawCircle(shapes, cx, cy, maxRadius, 36);
            shapes.end();

            shapes.begin(ShapeRenderer.ShapeType.Line);
            Gdx.gl.glLineWidth(8f);
            final float urgency = 0.85f + 0.15f * (float) Math.sin(t * Math.PI * 12);
            shapes.setColor(1.0f, 0.2f, 0.2f, bossAlpha * urgency);
            drawCircleOutline(shapes, cx, cy, maxRadius, 48);
            drawCircleOutline(shapes, cx, cy, maxRadius * 0.97f, 48);
            drawCircleOutline(shapes, cx, cy, maxRadius * 1.03f, 48);
            shapes.setColor(1.0f, 0.45f, 0.35f, bossAlpha * 0.85f);
            drawCircleOutline(shapes, cx, cy, maxRadius * 0.9f, 48);
            drawCircleOutline(shapes, cx, cy, maxRadius * 1.1f, 48);
            shapes.end();
            return;
        }

        // Ring expands fast then holds
        final float currentRadius = maxRadius * Math.min(t * 3.0f, 1.0f);
        // Stay fully visible for 70% of duration, then fade
        final float alpha = t < 0.7f ? 1.0f : 1.0f - (t - 0.7f) * 3.33f;

        // SOUL_VORTEX (45) is a persistent vortex with bespoke art — render
        // it specially so it doesn't get drawn as a generic ring on top of
        // its actual visual. Falls through to the dedicated branch below.
        // Phase 4 bespoke effects — each dispatches to a self-contained
        // renderer that manages its own shape begin/end. Mirrors the
        // procedural rendering done in the webclient renderer.js.
        if (type == CreateEffectPacket.EFFECT_SANCTUARY_DOME) {
            renderSanctuaryDome(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_VAMPIRIC_LATCH) {
            renderVampiricLatch(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_RAPIER_STAB) {
            renderRapierStab(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_LOW_SWING) {
            renderLowSwing(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_DISARM_FLOURISH) {
            renderDisarmFlourish(shapes, vfx, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_DIVINE_BEAM) {
            renderDivineBeam(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_FORTIFY_AURA) {
            renderFortifyAura(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_GROUND_POUND) {
            renderGroundPound(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_SOUL_VORTEX) {
            renderSoulVortex(shapes, vfx, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_SMOKE_POOF) {
            renderSmokePoof(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_FROST_NOVA) {
            renderFrostNova(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_POISON_CLOUD) {
            renderPoisonCloud(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_LIGHTNING_STRIKE) {
            renderLightningStrike(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_SMITE_FLASH) {
            renderSmiteFlash(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_BONE_SPIKES) {
            renderBoneSpikes(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_MANA_BOLT) {
            renderManaBolt(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_TIME_STOP) {
            renderTimeStop(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_BEAST_CLAWS) {
            renderBeastClaws(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_DEATH_BLOSSOM) {
            renderDeathBlossom(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_INSPIRE_BLOOM) {
            renderInspireBloom(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_RECKLESS_SLASH) {
            renderRecklessSlash(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_STAR_SHURIKEN) {
            renderStarShuriken(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_BLINK_GLYPH) {
            renderBlinkGlyph(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_LIFE_DRAIN) {
            renderLifeDrain(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_SNARE_GEAR) {
            renderSnareGear(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_COMBUSTION_TRAP) {
            renderCombustionTrap(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_WAR_CRY_WAVE) {
            renderWarCryWave(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_CALTROPS) {
            renderCaltrops(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_ARCANE_AURA) {
            renderArcaneAura(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_HASTE_WIND) {
            renderHasteWind(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_BANNER_RAISE) {
            renderBannerRaise(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_RAMPAGE_AURA) {
            renderRampageAura(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_STORM_AURA) {
            renderStormAura(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_DEATH_PACT_AURA) {
            renderDeathPactAura(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_BLADE_STORM) {
            renderBladeStorm(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_TAUNT_ROAR) {
            renderTauntRoar(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_BRACE_STANCE) {
            renderBraceStance(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_SHIELD_DOME) {
            renderShieldDome(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_WIZARD_BURST) {
            renderWizardBurst(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_PALADIN_SEAL) {
            renderPaladinSeal(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_WARRIOR_BUFF) {
            renderWarriorBuff(shapes, cx, cy, maxRadius, t);
            return;
        }
        // BLADE_ORBIT (46) and BLADE_BLENDER (47) are drawn separately in
        // renderShurikenEffects() using real shuriken sprites + SpriteBatch.
        // We early-return so the procedural ring path doesn't paint a
        // generic disc behind them. BLADE_BLENDER still gets a faint ground
        // halo though, drawn here for hazard-zone readability.
        if (type == CreateEffectPacket.EFFECT_BLADE_ORBIT) return;
        if (type == CreateEffectPacket.EFFECT_BLADE_BLENDER) {
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0.06f, 0.03f, 0.03f, alpha * 0.45f);
            drawCircle(shapes, cx, cy, maxRadius, 48);
            shapes.end();
            shapes.begin(ShapeRenderer.ShapeType.Line);
            Gdx.gl.glLineWidth(2f);
            shapes.setColor(0.75f, 0.12f, 0.18f, alpha * 0.7f);
            drawCircleOutline(shapes, cx, cy, maxRadius, 64);
            shapes.end();
            Gdx.gl.glLineWidth(1f);
            return;
        }
        // Per-effect color palette. Mirrors the webclient renderer.js cases
        // for parity at-a-glance — same hue as the webclient even if the
        // shape detail is simplified to ring+particles here.
        float r, g, b;
        switch (type) {
        case CreateEffectPacket.EFFECT_HEAL_RADIUS:       r = 0.10f; g = 1.00f; b = 0.20f; break;
        case CreateEffectPacket.EFFECT_VAMPIRISM:         r = 0.90f; g = 0.00f; b = 1.00f; break;
        case CreateEffectPacket.EFFECT_STASIS_FIELD:      r = 0.30f; g = 0.60f; b = 1.00f; break;
        case CreateEffectPacket.EFFECT_CURSE_RADIUS:      r = 0.80f; g = 0.00f; b = 0.15f; break;
        case CreateEffectPacket.EFFECT_POISON_SPLASH:     r = 0.20f; g = 0.80f; b = 0.20f; break;
        case CreateEffectPacket.EFFECT_TRAP_PLACED:       r = 0.85f; g = 0.55f; b = 0.10f; break;
        case CreateEffectPacket.EFFECT_TRAP_TRIGGER:      r = 1.00f; g = 0.45f; b = 0.10f; break;
        case CreateEffectPacket.EFFECT_SMOKE_POOF:        r = 0.55f; g = 0.55f; b = 0.60f; break;
        case CreateEffectPacket.EFFECT_WIZARD_BURST:      r = 1.00f; g = 0.55f; b = 0.10f; break;
        case CreateEffectPacket.EFFECT_KNIGHT_SHOCKWAVE:  r = 0.95f; g = 0.85f; b = 0.30f; break;
        case CreateEffectPacket.EFFECT_WARRIOR_BUFF:      r = 1.00f; g = 0.65f; b = 0.20f; break;
        case CreateEffectPacket.EFFECT_NINJA_DASH:        r = 0.40f; g = 0.85f; b = 1.00f; break;
        case CreateEffectPacket.EFFECT_PALADIN_SEAL:      r = 1.00f; g = 0.85f; b = 0.35f; break;
        case CreateEffectPacket.EFFECT_SHIELD_DOME:       r = 0.50f; g = 0.80f; b = 1.00f; break;
        case CreateEffectPacket.EFFECT_TAUNT_ROAR:        r = 1.00f; g = 0.20f; b = 0.20f; break;
        case CreateEffectPacket.EFFECT_BRACE_STANCE:      r = 0.70f; g = 0.85f; b = 0.95f; break;
        case CreateEffectPacket.EFFECT_FROST_NOVA:        r = 0.60f; g = 0.90f; b = 1.00f; break;
        case CreateEffectPacket.EFFECT_BLINK_GLYPH:       r = 0.75f; g = 0.45f; b = 1.00f; break;
        case CreateEffectPacket.EFFECT_POISON_CLOUD:      r = 0.38f; g = 0.78f; b = 0.20f; break;
        case CreateEffectPacket.EFFECT_LIFE_DRAIN:        r = 0.85f; g = 0.10f; b = 0.30f; break;
        case CreateEffectPacket.EFFECT_BONE_SPIKES:       r = 0.92f; g = 0.90f; b = 0.78f; break;
        case CreateEffectPacket.EFFECT_LIGHTNING_STRIKE:  r = 1.00f; g = 0.95f; b = 0.30f; break;
        case CreateEffectPacket.EFFECT_MANA_BOLT:         r = 0.55f; g = 0.30f; b = 1.00f; break;
        case CreateEffectPacket.EFFECT_TIME_STOP:         r = 0.70f; g = 0.80f; b = 0.95f; break;
        case CreateEffectPacket.EFFECT_BEAST_CLAWS:       r = 0.85f; g = 0.45f; b = 0.20f; break;
        case CreateEffectPacket.EFFECT_SMITE_FLASH:       r = 1.00f; g = 0.90f; b = 0.40f; break;
        case CreateEffectPacket.EFFECT_DEATH_BLOSSOM:     r = 0.60f; g = 0.10f; b = 0.70f; break;
        case CreateEffectPacket.EFFECT_INSPIRE_BLOOM:     r = 1.00f; g = 0.80f; b = 0.30f; break;
        case CreateEffectPacket.EFFECT_RECKLESS_SLASH:    r = 0.95f; g = 0.20f; b = 0.20f; break;
        case CreateEffectPacket.EFFECT_STAR_SHURIKEN:     r = 0.85f; g = 0.85f; b = 0.90f; break;
        case CreateEffectPacket.EFFECT_SNARE_GEAR:        r = 0.75f; g = 0.65f; b = 0.20f; break;
        case CreateEffectPacket.EFFECT_COMBUSTION_TRAP:   r = 1.00f; g = 0.45f; b = 0.10f; break;
        case CreateEffectPacket.EFFECT_WAR_CRY_WAVE:      r = 0.95f; g = 0.30f; b = 0.20f; break;
        case CreateEffectPacket.EFFECT_CALTROPS:          r = 0.70f; g = 0.70f; b = 0.75f; break;
        case CreateEffectPacket.EFFECT_ARCANE_AURA:       r = 0.65f; g = 0.40f; b = 1.00f; break;
        case CreateEffectPacket.EFFECT_HASTE_WIND:        r = 0.60f; g = 0.95f; b = 0.80f; break;
        case CreateEffectPacket.EFFECT_BANNER_RAISE:      r = 0.90f; g = 0.50f; b = 0.20f; break;
        case CreateEffectPacket.EFFECT_RAMPAGE_AURA:      r = 1.00f; g = 0.25f; b = 0.10f; break;
        case CreateEffectPacket.EFFECT_STORM_AURA:        r = 0.40f; g = 0.65f; b = 1.00f; break;
        case CreateEffectPacket.EFFECT_DEATH_PACT_AURA:   r = 0.55f; g = 0.10f; b = 0.50f; break;
        case CreateEffectPacket.EFFECT_BLADE_STORM:       r = 0.90f; g = 0.85f; b = 0.85f; break;
        // Phase 3 (post-rework) bespoke effects — until the native renderer
        // ports the procedural shape for each, paint a distinctive ring.
        case CreateEffectPacket.EFFECT_SANCTUARY_DOME:    r = 1.00f; g = 0.85f; b = 0.35f; break;
        case CreateEffectPacket.EFFECT_VAMPIRIC_LATCH:    r = 0.85f; g = 0.10f; b = 0.30f; break;
        // Heavy class kit FX — Debuffer (silver/red), Buffer (gold), DPS (dust).
        case CreateEffectPacket.EFFECT_RAPIER_STAB:       r = 0.88f; g = 0.90f; b = 0.93f; break;
        case CreateEffectPacket.EFFECT_LOW_SWING:         r = 0.75f; g = 0.16f; b = 0.19f; break;
        case CreateEffectPacket.EFFECT_DISARM_FLOURISH:   r = 1.00f; g = 0.82f; b = 0.30f; break;
        case CreateEffectPacket.EFFECT_DIVINE_BEAM:       r = 1.00f; g = 0.83f; b = 0.30f; break;
        case CreateEffectPacket.EFFECT_FORTIFY_AURA:      r = 0.25f; g = 0.66f; b = 1.00f; break;
        case CreateEffectPacket.EFFECT_GROUND_POUND:      r = 0.72f; g = 0.56f; b = 0.38f; break;
        default:                                          r = 1.00f; g = 1.00f; b = 1.00f; break;
        }

        // Filled translucent disc - much more visible
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(r, g, b, alpha * 0.35f);
        drawCircle(shapes, cx, cy, currentRadius, 48);
        shapes.end();

        // Thick bright outer ring (draw multiple concentric rings for thickness)
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(4f);
        shapes.setColor(r, g, b, alpha);
        drawCircleOutline(shapes, cx, cy, currentRadius, 64);
        shapes.setColor(r, g, b, alpha * 0.7f);
        drawCircleOutline(shapes, cx, cy, currentRadius * 0.97f, 64);
        drawCircleOutline(shapes, cx, cy, currentRadius * 1.03f, 64);
        shapes.end();

        // Second inner ring, pulsing
        float pulse = 0.7f + 0.3f * (float) Math.sin(t * Math.PI * 8);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(r, g, b, alpha * 0.8f * pulse);
        drawCircleOutline(shapes, cx, cy, currentRadius * 0.6f, 48);
        shapes.end();

        // Large orbiting particles on the ring edge
        int particleCount = 16;
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < particleCount; i++) {
            float angle = (float) (i * Math.PI * 2 / particleCount) + t * (float) Math.PI * 4;
            float px = cx + (float) Math.cos(angle) * currentRadius;
            float py = cy + (float) Math.sin(angle) * currentRadius;
            float pAlpha = alpha * (0.6f + 0.4f * (float) Math.sin(angle * 3 + t * Math.PI * 10));
            shapes.setColor(Math.min(r + 0.3f, 1f), Math.min(g + 0.3f, 1f), Math.min(b + 0.3f, 1f), pAlpha);
            shapes.rect(px - 3, py - 3, 6, 6);
        }

        // Inner scattered particles (moving outward or inward)
        int innerParticles = 12;
        for (int i = 0; i < innerParticles; i++) {
            float angle = (float) (i * Math.PI * 2 / innerParticles) - t * (float) Math.PI * 3;
            float dist;
            if (type == CreateEffectPacket.EFFECT_VAMPIRISM) {
                dist = currentRadius * (1.0f - t);
            } else {
                dist = currentRadius * 0.2f + currentRadius * 0.6f * t;
            }
            float px = cx + (float) Math.cos(angle) * dist;
            float py = cy + (float) Math.sin(angle) * dist;
            float pAlpha = alpha * 0.9f;
            shapes.setColor(Math.min(r + 0.2f, 1f), Math.min(g + 0.2f, 1f), Math.min(b + 0.2f, 1f), pAlpha);
            shapes.rect(px - 2.5f, py - 2.5f, 5, 5);
        }

        // Bright center flash at start
        if (t < 0.3f) {
            float flashAlpha = (0.3f - t) * 3.0f;
            shapes.setColor(1f, 1f, 1f, flashAlpha * 0.5f);
            drawCircle(shapes, cx, cy, currentRadius * 0.3f * (1.0f - t * 2), 24);
        }
        shapes.end();

        Gdx.gl.glLineWidth(1f);
    }

    private void renderLineEffect(ShapeRenderer shapes, ActiveVisualEffect vfx, float t, float wx, float wy) {
        if (vfx.getEffectType() == CreateEffectPacket.EFFECT_POISON_SPLASH) {
            renderPoisonThrow(shapes, vfx, t, wx, wy);
            return;
        }
        if (vfx.getEffectType() == CreateEffectPacket.EFFECT_KNIGHT_SHOCKWAVE) {
            renderKnightShockwave(shapes, vfx, t, wx, wy);
            return;
        }
        if (vfx.getEffectType() == CreateEffectPacket.EFFECT_NINJA_DASH) {
            renderNinjaDash(shapes, vfx, t, wx, wy);
            return;
        }
        final float x1 = vfx.getPosX() - wx;
        final float y1 = vfx.getPosY() - wy;
        final float x2 = vfx.getTargetPosX() - wx;
        final float y2 = vfx.getTargetPosY() - wy;
        // Stay fully visible for 80% of duration, then fade
        final float alpha = t < 0.8f ? 1.0f : 1.0f - (t - 0.8f) * 5.0f;

        final float dx = x2 - x1;
        final float dy = y2 - y1;
        final float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 1f) return;

        int segments = Math.max(8, (int) (length / 10));
        float perpX = -dy / length;
        float perpY = dx / length;

        // Pre-compute jitter offsets for main bolt (reused by glow)
        float[] jitters = new float[segments + 1];
        jitters[0] = 0;
        jitters[segments] = 0;
        for (int i = 1; i < segments; i++) {
            float frac = (float) i / segments;
            jitters[i] = (float) (Math.sin(frac * Math.PI * 5 + t * Math.PI * 14) * 12.0f
                    + Math.cos(frac * Math.PI * 9 + t * Math.PI * 8) * 5.0f);
        }

        // Outer glow (thick, dim blue-purple)
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < segments; i++) {
            float frac0 = (float) i / segments;
            float frac1 = (float) (i + 1) / segments;
            float px0 = x1 + dx * frac0 + perpX * jitters[i];
            float py0 = y1 + dy * frac0 + perpY * jitters[i];
            float px1 = x1 + dx * frac1 + perpX * jitters[i + 1];
            float py1 = y1 + dy * frac1 + perpY * jitters[i + 1];
            // Draw thick quads along the bolt as glow
            float glowSize = 6f;
            shapes.setColor(0.3f, 0.4f, 1.0f, alpha * 0.3f);
            shapes.rectLine(px0, py0, px1, py1, glowSize);
        }
        shapes.end();

        // Main bright bolt - thick electric blue
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < segments; i++) {
            float frac0 = (float) i / segments;
            float frac1 = (float) (i + 1) / segments;
            float px0 = x1 + dx * frac0 + perpX * jitters[i];
            float py0 = y1 + dy * frac0 + perpY * jitters[i];
            float px1 = x1 + dx * frac1 + perpX * jitters[i + 1];
            float py1 = y1 + dy * frac1 + perpY * jitters[i + 1];
            shapes.setColor(0.4f, 0.7f, 1.0f, alpha);
            shapes.rectLine(px0, py0, px1, py1, 3f);
        }
        shapes.end();

        // Inner white-hot core
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < segments; i++) {
            float frac0 = (float) i / segments;
            float frac1 = (float) (i + 1) / segments;
            float px0 = x1 + dx * frac0 + perpX * jitters[i];
            float py0 = y1 + dy * frac0 + perpY * jitters[i];
            float px1 = x1 + dx * frac1 + perpX * jitters[i + 1];
            float py1 = y1 + dy * frac1 + perpY * jitters[i + 1];
            shapes.setColor(0.8f, 0.9f, 1.0f, alpha * 0.9f);
            shapes.rectLine(px0, py0, px1, py1, 1.5f);
        }
        shapes.end();

        // Secondary fork bolt (different jitter pattern)
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < segments; i++) {
            float frac0 = (float) i / segments;
            float frac1 = (float) (i + 1) / segments;
            float j0 = (float) (Math.cos(frac0 * Math.PI * 7 + t * Math.PI * 18) * 8.0f);
            float j1 = (float) (Math.cos(frac1 * Math.PI * 7 + t * Math.PI * 18) * 8.0f);
            if (i == 0) j0 = 0;
            if (i == segments - 1) j1 = 0;
            float px0 = x1 + dx * frac0 + perpX * j0;
            float py0 = y1 + dy * frac0 + perpY * j0;
            float px1 = x1 + dx * frac1 + perpX * j1;
            float py1 = y1 + dy * frac1 + perpY * j1;
            shapes.setColor(0.5f, 0.6f, 1.0f, alpha * 0.5f);
            shapes.rectLine(px0, py0, px1, py1, 2f);
        }
        shapes.end();

        // Bright glow particles along the bolt
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        int particleCount = Math.max(6, segments / 2);
        for (int i = 0; i < particleCount; i++) {
            float frac = (float) i / particleCount;
            int segIdx = Math.min((int) (frac * segments), segments - 1);
            float px = x1 + dx * frac + perpX * jitters[segIdx];
            float py = y1 + dy * frac + perpY * jitters[segIdx];
            float pAlpha = alpha * (0.5f + 0.5f * (float) Math.sin(frac * Math.PI));
            shapes.setColor(0.6f, 0.8f, 1.0f, pAlpha);
            shapes.rect(px - 3, py - 3, 6, 6);
        }

        // Bright impact circles at endpoints
        float endSize = 8f + 4f * (float) Math.sin(t * Math.PI * 10);
        shapes.setColor(0.5f, 0.7f, 1.0f, alpha * 0.8f);
        drawCircle(shapes, x1, y1, endSize, 12);
        drawCircle(shapes, x2, y2, endSize, 12);
        shapes.setColor(1.0f, 1.0f, 1.0f, alpha);
        drawCircle(shapes, x1, y1, endSize * 0.4f, 8);
        drawCircle(shapes, x2, y2, endSize * 0.4f, 8);
        shapes.end();
    }

    /**
     * Knight Phalanx Shockwave (shield-bash thrust) — directional shield
     * bash with windup/thrust/slam phases. Ground-shadow streak along the
     * dash axis, 6 force chevrons sweeping forward, slam burst at the
     * forward endpoint, two staggered aftermath shockwaves, flash, debris
     * particles, and forward-radiating ground cracks. Procedural port of
     * renderer.js case 11 — directional via vfx.posX/Y → targetPosX/Y.
     */
    private void renderKnightShockwave(ShapeRenderer shapes, ActiveVisualEffect vfx, float t, float wx, float wy) {
        final float sx = vfx.getPosX() - wx;
        final float sy = vfx.getPosY() - wy;
        final float tx = vfx.getTargetPosX() - wx;
        final float ty = vfx.getTargetPosY() - wy;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;

        float kdx = tx - sx, kdy = ty - sy;
        final float kdist = (float) Math.sqrt(kdx * kdx + kdy * kdy);
        final float dirX = kdist > 0.5f ? kdx / kdist : 1f;
        final float dirY = kdist > 0.5f ? kdy / kdist : 0f;
        final float perpX = -dirY, perpY = dirX;

        final float REACH = Math.max(60f, Math.min(280f, kdist));
        final float WINDUP_END = 0.12f;
        final float THRUST_END = 0.50f;
        final float SLAM_END = 0.70f;

        // Use the gold palette from EFFECT_KNIGHT_SHOCKWAVE
        final float tcR = 0.95f, tcG = 0.85f, tcB = 0.30f;

        // ── Ground-shadow streak along the thrust axis ───────────────
        final float streakStart = -40f;
        final float streakEnd = REACH * Math.min(1.2f, t * 1.4f);
        final float startX = sx + dirX * streakStart, startY = sy + dirY * streakStart;
        final float endX = sx + dirX * streakEnd, endY = sy + dirY * streakEnd;
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(tcR, tcG, tcB, alpha * 0.10f);
        shapes.rectLine(startX, startY, endX, endY, 28f);
        shapes.setColor(tcR, tcG, tcB, alpha * 0.22f);
        shapes.rectLine(startX, startY, endX, endY, 16f);
        shapes.setColor(0f, 0f, 0f, alpha * 0.45f);
        shapes.rectLine(startX, startY, endX, endY, 6f);
        shapes.end();

        // ── Force chevrons sweeping forward ──────────────────────────
        final int chevCount = 6;
        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < chevCount; i++) {
            final float phaseOff = i * 0.045f;
            float lt;
            if (t < WINDUP_END) {
                final float tt = t / WINDUP_END;
                lt = -0.55f - 0.10f * tt - i * 0.08f;
            } else if (t < THRUST_END) {
                final float tt = Math.max(0f, Math.min(1f,
                        (t - WINDUP_END - phaseOff) / (THRUST_END - WINDUP_END - phaseOff)));
                final float eased = tt * tt * (3f - 2f * tt);
                final float startLT = -0.55f - 0.10f - i * 0.08f;
                final float endLT = 1.20f - i * 0.05f;
                lt = startLT + (endLT - startLT) * eased;
            } else {
                lt = 1.20f - i * 0.05f;
            }
            final float cx = sx + dirX * REACH * lt;
            final float cy = sy + dirY * REACH * lt;
            final float ltClamped = Math.max(-0.7f, Math.min(1.3f, lt));
            final float distFromCore = Math.max(0f, ltClamped - 1.0f);
            final float aheadFade = 1f - distFromCore * 1.8f;
            final float fade = alpha * Math.max(0.2f, aheadFade) * (1f - i * 0.06f);

            final float arm = 22f - i * 2.2f;
            final float tipFwd = arm * 0.55f;
            final float tipX = cx + dirX * tipFwd;
            final float tipY = cy + dirY * tipFwd;
            final float back1X = cx + perpX * arm - dirX * arm * 0.4f;
            final float back1Y = cy + perpY * arm - dirY * arm * 0.4f;
            final float back2X = cx - perpX * arm - dirX * arm * 0.4f;
            final float back2Y = cy - perpY * arm - dirY * arm * 0.4f;

            Gdx.gl.glLineWidth(7f);
            shapes.setColor(tcR, tcG, tcB, fade * 0.85f);
            shapes.line(back1X, back1Y, tipX, tipY);
            shapes.line(tipX, tipY, back2X, back2Y);
            Gdx.gl.glLineWidth(3f);
            shapes.setColor(0f, 0f, 0f, fade * 0.6f);
            shapes.line(back1X, back1Y, tipX, tipY);
            shapes.line(tipX, tipY, back2X, back2Y);
            Gdx.gl.glLineWidth(2f);
            shapes.setColor(1f, 1f, 1f, fade * 0.95f);
            shapes.line(back1X, back1Y, tipX, tipY);
            shapes.line(tipX, tipY, back2X, back2Y);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();

        // ── Brace flash behind knight during wind-up ─────────────────
        if (t < WINDUP_END) {
            final float tt = t / WINDUP_END;
            final float brakeA = alpha * (1f - tt) * 0.7f;
            final float braceX = sx - dirX * 18f;
            final float braceY = sy - dirY * 18f;
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0.33f, 0.20f, 0.13f, brakeA * 0.5f);
            drawCircle(shapes, braceX, braceY, 14f + tt * 8f, 18);
            shapes.setColor(tcR, tcG, tcB, brakeA * 0.4f);
            drawCircle(shapes, braceX, braceY, 10f + tt * 6f, 16);
            shapes.end();
        }

        // ── Slam impact + radial spokes + forward crack lines ────────
        if (t >= WINDUP_END) {
            final float slamProg = Math.max(0f, Math.min(1f, (t - WINDUP_END) / (SLAM_END - WINDUP_END)));
            final float slamPeak = (THRUST_END - WINDUP_END) / (SLAM_END - WINDUP_END);
            float slamA = slamProg <= slamPeak
                    ? slamProg / slamPeak
                    : Math.max(0f, 1f - (slamProg - slamPeak) / (1f - slamPeak));
            slamA *= alpha;
            if (slamA > 0.02f) {
                final float slamX = sx + dirX * REACH;
                final float slamY = sy + dirY * REACH;
                shapes.begin(ShapeRenderer.ShapeType.Filled);
                shapes.setColor(tcR, tcG, tcB, slamA * 0.55f);
                drawCircle(shapes, slamX, slamY, 38f + slamA * 18f, 32);
                shapes.setColor(1f, 1f, 1f, slamA * 0.95f);
                drawCircle(shapes, slamX, slamY, 18f + slamA * 10f, 24);
                shapes.setColor(tcR, tcG, tcB, slamA);
                drawCircle(shapes, slamX, slamY, 8f, 14);
                shapes.end();
                // 12 radial spokes around the slam
                shapes.begin(ShapeRenderer.ShapeType.Line);
                Gdx.gl.glLineWidth(3f);
                shapes.setColor(1f, 1f, 1f, slamA * 0.9f);
                final int spokes = 12;
                for (int i = 0; i < spokes; i++) {
                    final float a = (i / (float) spokes) * (float) Math.PI * 2f;
                    final float inner = 12f;
                    final float outer = 28f + slamA * 22f;
                    shapes.line(slamX + (float) Math.cos(a) * inner, slamY + (float) Math.sin(a) * inner,
                                slamX + (float) Math.cos(a) * outer, slamY + (float) Math.sin(a) * outer);
                }
                // Forward-only crack lines
                Gdx.gl.glLineWidth(4f);
                shapes.setColor(tcR, tcG, tcB, slamA * 0.85f);
                for (int i = -1; i <= 1; i++) {
                    final float tilt = i * 0.45f;
                    final float cTilt = (float) Math.cos(tilt), sTilt = (float) Math.sin(tilt);
                    final float fX = dirX * cTilt - dirY * sTilt;
                    final float fY = dirY * cTilt + dirX * sTilt;
                    shapes.line(slamX, slamY,
                                slamX + fX * (40f + slamA * 30f),
                                slamY + fY * (40f + slamA * 30f));
                }
                Gdx.gl.glLineWidth(1f);
                shapes.end();
            }
        }

        // ── Aftermath shockwaves (two staggered rings) ───────────────
        if (t >= THRUST_END) {
            final float aftT = (t - THRUST_END) / (1.0f - THRUST_END);
            final float slamX = sx + dirX * REACH;
            final float slamY = sy + dirY * REACH;
            shapes.begin(ShapeRenderer.ShapeType.Line);
            final float r1 = 30f + aftT * 100f;
            final float r1A = alpha * (1.0f - aftT) * 0.95f;
            Gdx.gl.glLineWidth(7f);
            shapes.setColor(tcR, tcG, tcB, r1A);
            drawCircleOutline(shapes, slamX, slamY, r1, 48);
            Gdx.gl.glLineWidth(3f);
            shapes.setColor(1f, 1f, 1f, r1A);
            drawCircleOutline(shapes, slamX, slamY, r1 * 0.93f, 48);
            if (aftT > 0.30f) {
                final float aft2 = (aftT - 0.30f) / 0.70f;
                final float r2 = 24f + aft2 * 78f;
                final float r2A = alpha * (1.0f - aft2) * 0.70f;
                Gdx.gl.glLineWidth(4f);
                shapes.setColor(tcR, tcG, tcB, r2A);
                drawCircleOutline(shapes, slamX, slamY, r2, 48);
            }
            Gdx.gl.glLineWidth(1f);
            shapes.end();
        }

        // ── Slam-moment flash ────────────────────────────────────────
        final float flashWindow = 0.20f;
        final float flashCenter = THRUST_END;
        final float fdist = Math.abs(t - flashCenter);
        if (fdist < flashWindow) {
            final float flashA = (1f - fdist / flashWindow) * alpha * 0.75f;
            final float flashX = sx + dirX * REACH;
            final float flashY = sy + dirY * REACH;
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(1f, 1f, 1f, flashA);
            drawCircle(shapes, flashX, flashY, 56f + (1f - fdist / flashWindow) * 24f, 36);
            shapes.setColor(tcR, tcG, tcB, flashA * 0.55f);
            drawCircle(shapes, flashX, flashY, 92f, 36);
            shapes.end();
        }

        // ── Debris particles ─────────────────────────────────────────
        if (t >= THRUST_END) {
            final float debT = (t - THRUST_END) / (1.0f - THRUST_END);
            final float slamX = sx + dirX * REACH;
            final float slamY = sy + dirY * REACH;
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            final int PARTICLES = 14;
            for (int i = 0; i < PARTICLES; i++) {
                final float baseAng = (float) Math.atan2(dirY, dirX);
                final float spread = (i / (float) PARTICLES - 0.5f) * (float) Math.PI * 1.5f;
                final float ang = baseAng + spread + (i * 1.3f) * 0.02f;
                final float vScale = 0.7f + ((i * 0.193f) % 1f) * 0.6f;
                final float reach = 70f + vScale * 60f;
                final float tt = Math.min(1f, debT * 1.3f);
                final float eased = 1f - (float) Math.pow(1f - tt, 3);
                final float pdx = (float) Math.cos(ang) * reach * eased;
                final float pdy = (float) Math.sin(ang) * reach * eased + eased * eased * 14f;
                final float px = slamX + pdx;
                final float py = slamY + pdy;
                final float partA = alpha * (1f - eased) * 0.9f;
                final float partR = 2.5f + (i % 3) * 1.5f;
                if ((i & 1) == 0) {
                    shapes.setColor(tcR, tcG, tcB, partA);
                } else {
                    shapes.setColor(0.42f, 0.27f, 0.14f, partA);
                }
                drawCircle(shapes, px, py, partR, 10);
                if ((i & 1) == 0) {
                    shapes.setColor(1f, 1f, 1f, partA * 0.6f);
                    drawCircle(shapes, px - partR * 0.3f, py - partR * 0.3f, partR * 0.4f, 8);
                }
            }
            shapes.end();
        }
    }

    /**
     * Ninja Dash — directional vortex of slicing blades along the dash path:
     * dash spine (tier aura + black outline + white core), orbiting blade
     * diamonds at varying perpendicular offsets, vanish puff at start,
     * arrival flash + radial spokes at endpoint. Procedural port of
     * renderer.js case 13. Directional via vfx.posX/Y → targetPosX/Y.
     */
    private void renderNinjaDash(ShapeRenderer shapes, ActiveVisualEffect vfx, float t, float wx, float wy) {
        final float sx = vfx.getPosX() - wx;
        final float sy = vfx.getPosY() - wy;
        final float tx = vfx.getTargetPosX() - wx;
        final float ty = vfx.getTargetPosY() - wy;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();

        final float dx = tx - sx, dy = ty - sy;
        final float dist = Math.max(1f, (float) Math.sqrt(dx * dx + dy * dy));
        final float dirX = dx / dist, dirY = dy / dist;
        final float perpX = -dirY, perpY = dirX;

        // Cyan tier color from EFFECT_NINJA_DASH palette
        final float tcR = 0.40f, tcG = 0.85f, tcB = 1.00f;

        // ── 1. Dash spine ────────────────────────────────────────────
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(tcR, tcG, tcB, alpha * 0.10f);
        shapes.rectLine(sx, sy, tx, ty, 20f);
        shapes.setColor(tcR, tcG, tcB, alpha * 0.25f);
        shapes.rectLine(sx, sy, tx, ty, 10f);
        shapes.setColor(0f, 0f, 0f, alpha * 0.55f);
        shapes.rectLine(sx, sy, tx, ty, 5f);
        shapes.setColor(1f, 1f, 1f, alpha * 0.75f);
        shapes.rectLine(sx, sy, tx, ty, 3f);
        shapes.end();

        // ── 2. Vortex of orbiting blades ─────────────────────────────
        final int bladeCount = Math.max(14, (int) (dist / 14f));
        final float ORBIT_AMP = 44f;
        final float ORBIT_SPEED = 0.011f;
        final float SPIN_SPEED = 0.016f;
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < bladeCount; i++) {
            final float frac = (i + 0.5f) / (float) bladeCount;
            final float appear = frac * 0.45f;
            if (t < appear) continue;
            final float local = (t - appear) / Math.max(0.001f, 1f - appear);
            float bScale = 1.0f;
            if (local < 0.15f) bScale = local / 0.15f;
            else if (local > 0.75f) bScale = Math.max(0f, (1f - local) / 0.25f);
            if (bScale <= 0f) continue;

            final float cx = sx + dx * frac;
            final float cy = sy + dy * frac;
            final float sign = (i & 1) != 0 ? 1f : -1f;
            final float orbitPhase = sign * (now * ORBIT_SPEED + i * 0.55f);
            final float orbit = (float) Math.sin(orbitPhase) * ORBIT_AMP * bScale;
            final float bx = cx + perpX * orbit;
            final float by = cy + perpY * orbit;

            final float spin = now * SPIN_SPEED + i * 0.4f;
            final float cs = (float) Math.cos(spin), sn = (float) Math.sin(spin);

            // Outer tier-coloured glow blade (diamond as two triangles)
            final float gLen = 22f * bScale, gWid = 7f * bScale;
            final float gx0 = bx + gLen * cs, gy0 = by + gLen * sn;
            final float gx1 = bx - gWid * sn, gy1 = by + gWid * cs;
            final float gx2 = bx - gLen * cs, gy2 = by - gLen * sn;
            final float gx3 = bx + gWid * sn, gy3 = by - gWid * cs;
            shapes.setColor(tcR, tcG, tcB, alpha * 0.32f * bScale);
            shapes.triangle(gx0, gy0, gx1, gy1, gx2, gy2);
            shapes.triangle(gx0, gy0, gx2, gy2, gx3, gy3);
            // Steel core
            final float cLen = 16f * bScale, cWid = 4f * bScale;
            final float cx0 = bx + cLen * cs, cy0 = by + cLen * sn;
            final float cx1 = bx - cWid * sn, cy1 = by + cWid * cs;
            final float cx2 = bx - cLen * cs, cy2 = by - cLen * sn;
            final float cx3 = bx + cWid * sn, cy3 = by - cWid * cs;
            shapes.setColor(1f, 1f, 1f, alpha * 0.85f * bScale);
            shapes.triangle(cx0, cy0, cx1, cy1, cx2, cy2);
            shapes.triangle(cx0, cy0, cx2, cy2, cx3, cy3);
        }
        shapes.end();
        // Motion trail per blade
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(2f);
        for (int i = 0; i < bladeCount; i++) {
            final float frac = (i + 0.5f) / (float) bladeCount;
            final float appear = frac * 0.45f;
            if (t < appear) continue;
            final float local = (t - appear) / Math.max(0.001f, 1f - appear);
            float bScale = 1.0f;
            if (local < 0.15f) bScale = local / 0.15f;
            else if (local > 0.75f) bScale = Math.max(0f, (1f - local) / 0.25f);
            if (bScale <= 0f) continue;
            final float cx = sx + dx * frac;
            final float cy = sy + dy * frac;
            final float sign = (i & 1) != 0 ? 1f : -1f;
            final float orbitPhase = sign * (now * ORBIT_SPEED + i * 0.55f);
            final float orbit = (float) Math.sin(orbitPhase) * ORBIT_AMP * bScale;
            final float bx = cx + perpX * orbit;
            final float by = cy + perpY * orbit;
            final float spin = now * SPIN_SPEED + i * 0.4f;
            final float cs = (float) Math.cos(spin), sn = (float) Math.sin(spin);
            final float cLen = 16f * bScale;
            final float trailLen = 14f * bScale;
            shapes.setColor(tcR, tcG, tcB, alpha * 0.45f * bScale);
            shapes.line(bx + cLen * cs, by + cLen * sn,
                        bx + cLen * cs - dirX * trailLen,
                        by + cLen * sn - dirY * trailLen);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();

        // ── 3. Vanish puff at start ──────────────────────────────────
        final float startPuffA = Math.max(0f, 1.0f - t * 1.6f);
        if (startPuffA > 0f) {
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0.5f, 0.5f, 0.5f, startPuffA * 0.6f);
            drawCircle(shapes, sx, sy, 16f, 20);
            shapes.setColor(tcR, tcG, tcB, startPuffA * 0.4f);
            drawCircle(shapes, sx, sy, 26f, 24);
            shapes.end();
        }

        // ── 4. Arrival flash + radial sparks at endpoint ─────────────
        final float arriveA = t < 0.5f ? (1.0f - t / 0.5f) : 0f;
        if (arriveA > 0f) {
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(1f, 1f, 1f, arriveA * 0.9f);
            drawCircle(shapes, tx, ty, 12f + arriveA * 10f, 22);
            shapes.setColor(tcR, tcG, tcB, arriveA * 0.6f);
            drawCircle(shapes, tx, ty, 26f + arriveA * 14f, 28);
            shapes.end();
            shapes.begin(ShapeRenderer.ShapeType.Line);
            Gdx.gl.glLineWidth(2f);
            shapes.setColor(1f, 1f, 1f, arriveA * 0.9f);
            final int spokes = 10;
            for (int i = 0; i < spokes; i++) {
                final float a = (i / (float) spokes) * (float) Math.PI * 2f + now * 0.005f;
                final float inner = 8f;
                final float outer = 22f + arriveA * 18f;
                shapes.line(tx + (float) Math.cos(a) * inner, ty + (float) Math.sin(a) * inner,
                            tx + (float) Math.cos(a) * outer, ty + (float) Math.sin(a) * outer);
            }
            Gdx.gl.glLineWidth(1f);
            shapes.end();
        }
    }

    /** Render a chunky vial/grenade arc from caster to target position.
     *  Default palette is green (assassin poison vial, tiers 0-6). When the
     *  packet's tier is >= 10 we draw red — used by the Inferno Demon grenade
     *  so we can re-use the same parabolic-lob renderer without inventing a
     *  parallel effect type. */
    private void renderPoisonThrow(ShapeRenderer shapes, ActiveVisualEffect vfx, float t, float wx, float wy) {
        final float x1 = vfx.getPosX() - wx;
        final float y1 = vfx.getPosY() - wy;
        final float x2 = vfx.getTargetPosX() - wx;
        final float y2 = vfx.getTargetPosY() - wy;

        final float dx = x2 - x1;
        final float dy = y2 - y1;
        final float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 1f) return;

        // Red-grenade palette gated on a tier sentinel — keeps assassin tiers
        // 0-6 (and the untiered Soulrot Vial at tier 0) on the green look.
        final boolean redGrenade = vfx.getTier() >= 10;

        final float trailR = redGrenade ? 0.95f : 0.20f;
        final float trailG = redGrenade ? 0.15f : 0.65f;
        final float trailB = redGrenade ? 0.10f : 0.15f;
        final float dripR  = redGrenade ? 0.85f : 0.15f;
        final float dripG  = redGrenade ? 0.10f : 0.60f;
        final float dripB  = redGrenade ? 0.05f : 0.10f;
        final float glowR  = redGrenade ? 1.00f : 0.20f;
        final float glowG  = redGrenade ? 0.20f : 0.70f;
        final float glowB  = redGrenade ? 0.10f : 0.10f;
        final float bodyR  = redGrenade ? 1.00f : 0.30f;
        final float bodyG  = redGrenade ? 0.30f : 0.90f;
        final float bodyB  = redGrenade ? 0.05f : 0.20f;
        final float coreR  = redGrenade ? 1.00f : 0.70f;
        final float coreG  = redGrenade ? 0.85f : 1.00f;
        final float coreB  = redGrenade ? 0.30f : 0.50f;

        // Tall parabolic arc — 50% of throw distance as peak height
        int steps = 24;
        float arcHeight = dist * 0.5f;

        // Vial position along arc (t goes 0->1 over the duration)
        float vialFrac = Math.min(t, 1.0f);

        // Compute arc positions
        float[] arcX = new float[steps + 1];
        float[] arcY = new float[steps + 1];
        for (int i = 0; i <= steps; i++) {
            float f = (float) i / steps;
            arcX[i] = x1 + dx * f;
            arcY[i] = y1 + dy * f - 4.0f * arcHeight * f * (1.0f - f);
        }

        shapes.begin(ShapeRenderer.ShapeType.Filled);

        // Thick trail behind the vial / grenade
        for (int i = 0; i < steps; i++) {
            float f = (float) (i + 1) / steps;
            if (f > vialFrac) break;
            // Trail fades from thin at start to thick near vial
            float thickness = 3.0f + 5.0f * (f / Math.max(vialFrac, 0.01f));
            float trailAlpha = 0.15f + 0.4f * (f / Math.max(vialFrac, 0.01f));
            shapes.setColor(trailR, trailG, trailB, trailAlpha);
            shapes.rectLine(arcX[i], arcY[i], arcX[i + 1], arcY[i + 1], thickness);
        }

        // Dripping / sparking particles along the trail
        for (int i = 0; i < 6; i++) {
            float pf = vialFrac * (0.3f + 0.7f * i / 6.0f);
            int idx = Math.min((int) (pf * steps), steps);
            float dripY = arcY[idx] + (t * 30.0f * (i + 1) / 6.0f);  // drip downward over time
            float dripAlpha = Math.max(0, 0.5f - t * 0.6f);
            if (dripAlpha > 0) {
                shapes.setColor(dripR, dripG, dripB, dripAlpha);
                shapes.rect(arcX[idx] - 2, dripY - 1, 4, 3 + i);
            }
        }

        // Fat vial / grenade blob
        if (vialFrac < 1.0f) {
            int vialIdx = Math.min((int) (vialFrac * steps), steps);
            float vx = arcX[vialIdx];
            float vy = arcY[vialIdx];

            // Outer glow
            shapes.setColor(glowR, glowG, glowB, 0.4f);
            drawCircle(shapes, vx, vy, 12f, 10);
            // Main body
            shapes.setColor(bodyR, bodyG, bodyB, 0.9f);
            drawCircle(shapes, vx, vy, 8f, 10);
            // Bright core / highlight
            shapes.setColor(coreR, coreG, coreB, 0.8f);
            drawCircle(shapes, vx - 2, vy - 2, 3.5f, 8);
        }

        shapes.end();
    }

    /**
     * Sorcerer Reality Tear — pitch-black void disc, violet inner glow,
     * 6 jagged radial cracks rotating outward, 10 orbiting void shards.
     * Procedural port of renderer.js case 48 for native LibGDX.
     */
    private void renderSanctuaryDome(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.90f ? 1.0f : 1.0f - (t - 0.90f) * 10f;
        final long now = System.currentTimeMillis();
        // Translucent dome
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1.00f, 0.82f, 0.38f, alpha * 0.18f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(4f);
        shapes.setColor(1.00f, 0.82f, 0.38f, alpha * 0.95f);
        drawCircleOutline(shapes, cx, cy, radius, 64);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1.00f, 0.94f, 0.63f, alpha * 0.8f);
        drawCircleOutline(shapes, cx, cy, radius * 0.97f, 64);
        drawCircleOutline(shapes, cx, cy, radius * 1.03f, 64);
        shapes.end();
        // 8 rising light pillars
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int pillars = 8;
        for (int i = 0; i < pillars; i++) {
            final float a = (i / (float) pillars) * (float) Math.PI * 2f + now * 0.0008f;
            final float cosA = (float) Math.cos(a), sinA = (float) Math.sin(a);
            final float baseR = radius * 0.92f;
            final float px = cx + cosA * baseR;
            final float py = cy + sinA * baseR;
            final float pHeight = radius * 0.42f * (0.7f + 0.3f * (float) Math.sin(now * 0.005f + i));
            shapes.setColor(1.00f, 0.94f, 0.63f, alpha * 0.55f);
            drawCircle(shapes, px, py - pHeight * 0.5f, 5f, 12);
        }
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(3f);
        for (int i = 0; i < pillars; i++) {
            final float a = (i / (float) pillars) * (float) Math.PI * 2f + now * 0.0008f;
            final float cosA = (float) Math.cos(a), sinA = (float) Math.sin(a);
            final float baseR = radius * 0.92f;
            final float px = cx + cosA * baseR;
            final float py = cy + sinA * baseR;
            final float pHeight = radius * 0.42f * (0.7f + 0.3f * (float) Math.sin(now * 0.005f + i));
            shapes.setColor(1.00f, 0.82f, 0.38f, alpha * 0.75f);
            shapes.line(px, py, px, py - pHeight);
        }
        // Holy cross center
        Gdx.gl.glLineWidth(5f);
        shapes.setColor(1.00f, 0.94f, 0.63f, alpha);
        final float crossLen = radius * 0.35f;
        shapes.line(cx - crossLen, cy, cx + crossLen, cy);
        shapes.line(cx, cy - crossLen, cx, cy + crossLen);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1.00f, 0.82f, 0.38f, alpha * 0.85f);
        shapes.line(cx - crossLen * 0.7f, cy - crossLen * 0.7f,
                    cx + crossLen * 0.7f, cy + crossLen * 0.7f);
        shapes.line(cx + crossLen * 0.7f, cy - crossLen * 0.7f,
                    cx - crossLen * 0.7f, cy + crossLen * 0.7f);
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // Bright center pulse
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final float pulse = 0.55f + 0.35f * (float) Math.sin(now * 0.008f);
        shapes.setColor(1f, 1f, 1f, alpha * pulse);
        drawCircle(shapes, cx, cy, 6f, 18);
        shapes.setColor(1.00f, 0.94f, 0.63f, alpha);
        drawCircle(shapes, cx, cy, 3f, 12);
        shapes.end();
    }

    /**
     * Necromancer Vampiric Latch — dark blood-red ground halo, 8 snaking
     * tendrils that oscillate perpendicular to outward axis with pulsing
     * mouth caps at the rim, central heart pulsing.
     */
    private void renderVampiricLatch(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        // Ground halo
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.19f, 0.03f, 0.06f, alpha * 0.5f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(0.75f, 0.13f, 0.25f, alpha * 0.9f);
        drawCircleOutline(shapes, cx, cy, radius, 48);
        Gdx.gl.glLineWidth(1f);
        // 8 snaking tendrils
        final int tendrils = 8;
        final int segs = 6;
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(0.75f, 0.13f, 0.25f, alpha * 0.95f);
        for (int i = 0; i < tendrils; i++) {
            final float a = (i / (float) tendrils) * (float) Math.PI * 2f;
            final float cosA = (float) Math.cos(a), sinA = (float) Math.sin(a);
            final float perpX = (float) Math.cos(a + (float) Math.PI / 2f);
            final float perpY = (float) Math.sin(a + (float) Math.PI / 2f);
            final float phase = now * 0.004f + i;
            float prevX = cx, prevY = cy;
            for (int s = 1; s <= segs; s++) {
                final float sT = s / (float) segs;
                final float sR = radius * sT;
                final float wave = (float) Math.sin(phase + sT * (float) Math.PI * 3f) * 6f * sT;
                final float px = cx + cosA * sR + perpX * wave;
                final float py = cy + sinA * sR + perpY * wave;
                shapes.line(prevX, prevY, px, py);
                prevX = px; prevY = py;
            }
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // Mouth caps + center heart
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < tendrils; i++) {
            final float a = (i / (float) tendrils) * (float) Math.PI * 2f;
            final float cosA = (float) Math.cos(a), sinA = (float) Math.sin(a);
            final float perpX = (float) Math.cos(a + (float) Math.PI / 2f);
            final float perpY = (float) Math.sin(a + (float) Math.PI / 2f);
            final float phase = now * 0.004f + i;
            // End of last segment (s = segs)
            final float wave = (float) Math.sin(phase + (float) Math.PI * 3f) * 6f;
            final float ex = cx + cosA * radius + perpX * wave;
            final float ey = cy + sinA * radius + perpY * wave;
            shapes.setColor(1f, 0.5f, 0.63f, alpha);
            drawCircle(shapes, ex, ey, 3.5f, 12);
        }
        // Central heart pulse
        final float pulse = 0.7f + 0.3f * (float) Math.sin(now * 0.009f);
        shapes.setColor(0.75f, 0.13f, 0.25f, alpha * pulse);
        drawCircle(shapes, cx, cy, 8f, 18);
        shapes.setColor(1f, 0.5f, 0.63f, alpha);
        drawCircle(shapes, cx, cy, 4f, 12);
        shapes.end();
    }

    /**
     * Heavy Debuffer Sidearm — quick silver rapier stab. 4 cardinal sparkle
     * arms shoot outward, white core flash, sparkle stars at the tips.
     * Procedural port of renderer.js case 53.
     */
    private void renderRapierStab(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final float corePulse = 1.0f - t;
        final float armReach = radius * (0.4f + 0.7f * t);

        // 4-axis sparkle lines (N/S/E/W) — outward dashes
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(4f);
        shapes.setColor(0.54f, 0.60f, 0.66f, alpha * 0.85f);
        for (int i = 0; i < 4; i++) {
            final float a = (i / 4f) * (float) Math.PI * 2f;
            final float cosA = (float) Math.cos(a), sinA = (float) Math.sin(a);
            final float fx = cx + cosA * (armReach * 0.35f);
            final float fy = cy + sinA * (armReach * 0.35f);
            final float tx = cx + cosA * armReach;
            final float ty = cy + sinA * armReach;
            shapes.line(fx, fy, tx, ty);
        }
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1f, 1f, 1f, alpha);
        for (int i = 0; i < 4; i++) {
            final float a = (i / 4f) * (float) Math.PI * 2f;
            final float cosA = (float) Math.cos(a), sinA = (float) Math.sin(a);
            final float fx = cx + cosA * (armReach * 0.35f);
            final float fy = cy + sinA * (armReach * 0.35f);
            final float tx = cx + cosA * armReach;
            final float ty = cy + sinA * armReach;
            shapes.line(fx, fy, tx, ty);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();

        // Sparkle stars at tips + bright core flash
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < 4; i++) {
            final float a = (i / 4f) * (float) Math.PI * 2f;
            final float tx = cx + (float) Math.cos(a) * armReach;
            final float ty = cy + (float) Math.sin(a) * armReach;
            shapes.setColor(1f, 1f, 1f, alpha * (1.0f - t * 0.6f));
            drawCircle(shapes, tx, ty, 3f + 2f * (1f - t), 12);
        }
        shapes.setColor(1f, 1f, 1f, alpha * corePulse);
        drawCircle(shapes, cx, cy, 6f + 3f * corePulse, 18);
        shapes.setColor(0.88f, 0.90f, 0.93f, alpha * corePulse * 0.7f);
        drawCircle(shapes, cx, cy, 10f + 4f * corePulse, 18);
        shapes.end();
    }

    /**
     * Heavy Debuffer Ankle Strike — bottom-half horizontal arc sweep. Steel
     * underlay, red core, white highlight. Quick ankle-level glint at center.
     * Procedural port of renderer.js case 54.
     */
    private void renderLowSwing(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final float reach = radius * 1.05f;
        final int segs = 10;
        // Sweep across the lower half of the ring. LibGDX is Y-up; PIXI Y is
        // down. Negate sin so the arc reads "lower" on screen (below caster).
        final float a0 = (float) (Math.PI * 0.15);
        final float a1 = (float) (Math.PI * 0.85);

        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(8f);
        shapes.setColor(0.25f, 0.03f, 0.06f, alpha * 0.8f);
        for (int s = 0; s < segs; s++) {
            final float p0 = a0 + ((a1 - a0) * s) / segs;
            final float p1 = a0 + ((a1 - a0) * (s + 1)) / segs;
            shapes.line(cx + (float) Math.cos(p0) * reach, cy - (float) Math.sin(p0) * reach,
                        cx + (float) Math.cos(p1) * reach, cy - (float) Math.sin(p1) * reach);
        }
        Gdx.gl.glLineWidth(5f);
        shapes.setColor(0.75f, 0.16f, 0.19f, alpha);
        for (int s = 0; s < segs; s++) {
            final float p0 = a0 + ((a1 - a0) * s) / segs;
            final float p1 = a0 + ((a1 - a0) * (s + 1)) / segs;
            shapes.line(cx + (float) Math.cos(p0) * reach, cy - (float) Math.sin(p0) * reach,
                        cx + (float) Math.cos(p1) * reach, cy - (float) Math.sin(p1) * reach);
        }
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1f, 1f, 1f, alpha * 0.9f);
        for (int s = 0; s < segs; s++) {
            final float p0 = a0 + ((a1 - a0) * s) / segs;
            final float p1 = a0 + ((a1 - a0) * (s + 1)) / segs;
            shapes.line(cx + (float) Math.cos(p0) * reach, cy - (float) Math.sin(p0) * reach,
                        cx + (float) Math.cos(p1) * reach, cy - (float) Math.sin(p1) * reach);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();

        // Small ankle-level steel glint just below caster.
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.63f, 0.66f, 0.69f, alpha * (1f - t));
        drawCircle(shapes, cx, cy - 4f, 4f, 12);
        shapes.end();
    }

    /**
     * Heavy Debuffer Disarm — ultimate flourish. Triple-ring expanding
     * outward, 8 sparkle stars at cardinal/diagonal points, central impact
     * burst. Procedural port of renderer.js case 55.
     */
    private void renderDisarmFlourish(ShapeRenderer shapes, ActiveVisualEffect vfx,
                                       float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final float elapsed = vfx != null ? vfx.getElapsed() : 0f;

        // Three pulsing rings, time-offset for a cascade outward.
        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < 3; i++) {
            final float ringP = (t + i * 0.18f) % 1.0f;
            if (ringP > 0.85f) continue;
            final float ringR = radius * (0.2f + ringP * 1.0f);
            final float ringA = alpha * (1.0f - ringP) * 0.9f;
            Gdx.gl.glLineWidth(6f);
            shapes.setColor(0.50f, 0.28f, 0.03f, ringA * 0.6f);
            drawCircleOutline(shapes, cx, cy, ringR, 48);
            Gdx.gl.glLineWidth(3f);
            shapes.setColor(1.00f, 0.82f, 0.30f, ringA);
            drawCircleOutline(shapes, cx, cy, ringR, 48);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();

        // 8 sparkle stars at cardinal+diagonal points.
        final float starR = radius * (0.7f + 0.25f * t);
        final float starPulse = 0.5f + 0.5f * (float) Math.sin(elapsed * 0.024);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < 8; i++) {
            final float a = (i / 8f) * (float) Math.PI * 2f + elapsed * 0.001f;
            final float tx = cx + (float) Math.cos(a) * starR;
            final float ty = cy + (float) Math.sin(a) * starR;
            shapes.setColor(1.00f, 0.82f, 0.30f, alpha);
            drawCircle(shapes, tx, ty, 5f + 2f * starPulse, 8);
            shapes.setColor(1f, 1f, 1f, alpha);
            drawCircle(shapes, tx, ty, 2f, 8);
        }
        // Central impact burst — bright early, fades out.
        final float earlyA = Math.max(0f, 1f - t * 2.5f);
        if (earlyA > 0f) {
            shapes.setColor(1f, 1f, 1f, alpha * earlyA);
            drawCircle(shapes, cx, cy, 10f + 8f * earlyA, 24);
            shapes.setColor(1.00f, 0.82f, 0.30f, alpha * earlyA * 0.85f);
            drawCircle(shapes, cx, cy, 18f + 10f * earlyA, 24);
        }
        shapes.end();
    }

    /**
     * Heavy Buffer Divine Beam — vertical column of golden light rising from
     * the caster, ground halo, and rising heal sparkles. Procedural port of
     * renderer.js case 56. LibGDX is Y-up so the column rises +y.
     */
    private void renderDivineBeam(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final float beamH = radius * 2.2f;
        final float beamW = Math.max(12f, radius * 0.35f);
        final float colA = alpha * (1.0f - t * 0.4f);

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        // Outer column glow
        shapes.setColor(1.00f, 0.94f, 0.63f, colA * 0.35f);
        shapes.rect(cx - beamW, cy, beamW * 2f, beamH);
        // Inner column — bright core
        shapes.setColor(1.00f, 0.83f, 0.30f, colA * 0.65f);
        shapes.rect(cx - beamW * 0.5f, cy, beamW, beamH);
        // Hot white spine
        shapes.setColor(1f, 1f, 1f, colA);
        shapes.rect(cx - 3f, cy, 6f, beamH);
        // Ground halo fill
        shapes.setColor(1.00f, 0.94f, 0.63f, alpha * 0.45f);
        drawCircle(shapes, cx, cy, radius * 0.7f, 36);
        shapes.end();

        // Ground halo rings
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(5f);
        shapes.setColor(1.00f, 0.83f, 0.30f, alpha * 0.9f);
        drawCircleOutline(shapes, cx, cy, radius, 48);
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(1f, 1f, 1f, alpha);
        drawCircleOutline(shapes, cx, cy, radius * 0.85f, 48);
        Gdx.gl.glLineWidth(1f);
        shapes.end();

        // Rising sparkle particles (heal feel) — Y-up: rise in +y.
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < 8; i++) {
            final float seed = i * 0.713f;
            final float st = (t + seed) % 1.0f;
            final float angle = seed * (float) Math.PI * 2f;
            final float dist = radius * (0.25f + 0.6f * ((seed * 11f) % 1f));
            final float px = cx + (float) Math.cos(angle) * dist;
            final float py = cy + (float) Math.sin(angle) * dist + st * radius * 0.7f;
            shapes.setColor(1f, 1f, 1f, alpha * (1f - st));
            drawCircle(shapes, px, py, 3f, 10);
            shapes.setColor(1.00f, 0.83f, 0.30f, alpha * (1f - st) * 0.8f);
            drawCircle(shapes, px, py, 5f, 10);
        }
        shapes.end();
    }

    /**
     * Heavy Buffer Fortify Aura — persistent regen sigil. Outer ring,
     * hexagram (two interlocking triangles), pulsing rising sparkles.
     * Procedural port of renderer.js case 57.
     */
    private void renderFortifyAura(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.90f ? 1.0f : 1.0f - (t - 0.90f) * 10f;
        final long now = System.currentTimeMillis();
        final float pulse = 0.65f + 0.35f * (float) Math.sin(now * 0.005);

        // Outer ring (dark base + blue overlay).
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(4f);
        shapes.setColor(0.06f, 0.22f, 0.28f, alpha * 0.85f);
        drawCircleOutline(shapes, cx, cy, radius, 48);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(0.25f, 0.66f, 1.00f, alpha * 0.9f * pulse);
        drawCircleOutline(shapes, cx, cy, radius, 48);

        // Hexagram — two interlocking equilateral triangles.
        final float innerR = radius * 0.62f;
        Gdx.gl.glLineWidth(3f);
        // Triangle A — green, pointing up (rotation = -PI/2 in math convention).
        shapes.setColor(0.38f, 1.00f, 0.53f, alpha * pulse);
        {
            float[] tx = new float[3], ty = new float[3];
            for (int i = 0; i < 3; i++) {
                final float a = (-(float) Math.PI / 2f) + (i / 3f) * (float) Math.PI * 2f;
                tx[i] = cx + (float) Math.cos(a) * innerR;
                ty[i] = cy + (float) Math.sin(a) * innerR;
            }
            shapes.line(tx[0], ty[0], tx[1], ty[1]);
            shapes.line(tx[1], ty[1], tx[2], ty[2]);
            shapes.line(tx[2], ty[2], tx[0], ty[0]);
        }
        // Triangle B — blue, pointing down (rotation = PI/2).
        shapes.setColor(0.25f, 0.66f, 1.00f, alpha * pulse);
        {
            float[] tx = new float[3], ty = new float[3];
            for (int i = 0; i < 3; i++) {
                final float a = ((float) Math.PI / 2f) + (i / 3f) * (float) Math.PI * 2f;
                tx[i] = cx + (float) Math.cos(a) * innerR;
                ty[i] = cy + (float) Math.sin(a) * innerR;
            }
            shapes.line(tx[0], ty[0], tx[1], ty[1]);
            shapes.line(tx[1], ty[1], tx[2], ty[2]);
            shapes.line(tx[2], ty[2], tx[0], ty[0]);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();

        // Rising sparkles — alternating green/blue. Y-up: rise +y.
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int sparkles = 14;
        for (int i = 0; i < sparkles; i++) {
            final float seed = i * 0.451f;
            final float cycle = ((now * 0.0006f) + seed) % 1.0f;
            final float angle = (i / (float) sparkles) * (float) Math.PI * 2f + now * 0.0005f;
            final float dist = radius * (0.3f + 0.55f * ((seed * 23f) % 1f));
            final float px = cx + (float) Math.cos(angle) * dist;
            final float py = cy + (float) Math.sin(angle) * dist + cycle * radius * 0.65f;
            final float sA = (1f - cycle) * 0.9f;
            if ((i & 1) == 0) shapes.setColor(0.25f, 0.66f, 1.00f, alpha * sA);
            else              shapes.setColor(0.38f, 1.00f, 0.53f, alpha * sA);
            drawCircle(shapes, px, py, 2.5f, 8);
            shapes.setColor(1f, 1f, 1f, alpha * sA * 0.7f);
            drawCircle(shapes, px, py, 1.2f, 6);
        }
        // Central glint.
        shapes.setColor(1f, 1f, 1f, alpha * pulse * 0.7f);
        drawCircle(shapes, cx, cy, 4f, 12);
        shapes.end();
    }

    /**
     * Heavy DPS Ground Pound — expanding dust ring, 6 radial ground cracks,
     * lingering dust puffs, central impact flash. Procedural port of
     * renderer.js case 58.
     */
    private void renderGroundPound(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;

        // 1. Expanding dust ring — fast outward in first 40% of life.
        final float ringP = Math.min(1f, t / 0.4f);
        final float ringR = radius * (0.2f + 0.8f * ringP);
        final float ringA = alpha * (1f - ringP * 0.5f);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(8f);
        shapes.setColor(0.25f, 0.16f, 0.06f, ringA * 0.85f);
        drawCircleOutline(shapes, cx, cy, ringR, 48);
        Gdx.gl.glLineWidth(5f);
        shapes.setColor(0.72f, 0.56f, 0.38f, ringA);
        drawCircleOutline(shapes, cx, cy, ringR, 48);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(0.88f, 0.78f, 0.56f, ringA * 0.9f);
        drawCircleOutline(shapes, cx, cy, ringR, 48);

        // 2. 6 radial crack lines with a midpoint kink for texture.
        final float crackR = radius * (0.5f + 0.55f * t);
        Gdx.gl.glLineWidth(4f);
        shapes.setColor(0.25f, 0.16f, 0.06f, alpha * (1f - t * 0.4f));
        for (int i = 0; i < 6; i++) {
            final float a = (i / 6f) * (float) Math.PI * 2f + 0.3f;
            final float midR = crackR * 0.55f;
            final float midX = cx + (float) Math.cos(a) * midR;
            final float midY = cy + (float) Math.sin(a) * midR;
            final float jitterA = a + ((i % 2 == 0) ? 0.15f : -0.15f);
            final float endX = cx + (float) Math.cos(jitterA) * crackR;
            final float endY = cy + (float) Math.sin(jitterA) * crackR;
            shapes.line(cx, cy, midX, midY);
            shapes.line(midX, midY, endX, endY);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();

        // 3. Lingering dust puffs — slowly drift up (Y-up: +y) in second half.
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int puffs = 8;
        for (int i = 0; i < puffs; i++) {
            final float seed = i * 0.617f;
            final float angle = (i / (float) puffs) * (float) Math.PI * 2f + seed * 0.4f;
            final float dist = radius * (0.3f + 0.5f * ((seed * 13f) % 1f));
            final float px = cx + (float) Math.cos(angle) * dist;
            final float py = cy + (float) Math.sin(angle) * dist + t * 8f;
            final float puffR = 4f + 4f * t;
            shapes.setColor(0.72f, 0.56f, 0.38f, alpha * (1f - t) * 0.75f);
            drawCircle(shapes, px, py, puffR, 12);
            shapes.setColor(0.88f, 0.78f, 0.56f, alpha * (1f - t) * 0.55f);
            drawCircle(shapes, px, py, puffR * 0.5f, 10);
        }
        // 4. Central impact flash — first beat only.
        final float earlyA = Math.max(0f, 1f - t * 4f);
        if (earlyA > 0f) {
            shapes.setColor(1f, 1f, 1f, alpha * earlyA);
            drawCircle(shapes, cx, cy, 12f * earlyA + 6f, 18);
            shapes.setColor(0.88f, 0.78f, 0.56f, alpha * earlyA * 0.8f);
            drawCircle(shapes, cx, cy, 18f * earlyA + 8f, 18);
        }
        shapes.end();
    }

    /**
     * Rogue Smoke Poof — billowy three-tone puff cluster + brief dagger
     * silhouettes during the first 35% of life + tier-tinted POP flash for
     * the first 30% + warm ember flecks drifting outward and upward.
     * Procedural port of renderer.js case 9.
     */
    private void renderSmokePoof(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        final float puffR = radius * (0.6f + t * 1.4f);
        // 12 overlapping puff circles, rotating slowly
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < 12; i++) {
            final float a = (i / 12f) * (float) Math.PI * 2f + now * 0.002f;
            final float dist = puffR * (0.30f + 0.20f * (i % 2));
            final float px = cx + (float) Math.cos(a) * dist;
            final float py = cy + (float) Math.sin(a) * dist;
            final float pr = puffR * (0.55f + 0.12f * (float) Math.sin(now * 0.01f + i));
            shapes.setColor(0.55f, 0.55f, 0.60f, alpha * 0.18f);
            drawCircle(shapes, px, py, pr, 18);
            shapes.setColor(0.50f, 0.50f, 0.50f, alpha * 0.32f);
            drawCircle(shapes, px, py, pr * 0.78f, 16);
            shapes.setColor(0.25f, 0.25f, 0.25f, alpha * 0.4f);
            drawCircle(shapes, px, py, pr * 0.45f, 14);
        }
        shapes.end();
        // Dagger silhouettes (first 35%)
        if (t < 0.35f) {
            final float dagA = (1f - t / 0.35f) * alpha;
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            final int dCount = 4;
            for (int i = 0; i < dCount; i++) {
                final float a = (i / (float) dCount) * (float) Math.PI * 2f + (float) Math.PI / 4f;
                final float reach = puffR * (0.55f + t * 0.6f);
                final float dx = cx + (float) Math.cos(a) * reach;
                final float dy = cy + (float) Math.sin(a) * reach;
                final float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
                shapes.setColor(0.69f, 0.69f, 0.75f, dagA * 0.85f);
                shapes.triangle(dx + cs * 8f, dy + sn * 8f,
                                dx + sn * 2.5f, dy - cs * 2.5f,
                                dx - cs * 4f,  dy - sn * 4f);
                shapes.triangle(dx + cs * 8f, dy + sn * 8f,
                                dx - cs * 4f, dy - sn * 4f,
                                dx - sn * 2.5f, dy + cs * 2.5f);
                shapes.setColor(1f, 1f, 1f, dagA * 0.9f);
                shapes.triangle(dx + cs * 7f, dy + sn * 7f,
                                dx + sn * 1f, dy - cs * 1f,
                                dx - cs * 2f, dy - sn * 2f);
                shapes.triangle(dx + cs * 7f, dy + sn * 7f,
                                dx - cs * 2f, dy - sn * 2f,
                                dx - sn * 1f, dy + cs * 1f);
            }
            shapes.end();
        }
        // POP flash (first 30%)
        if (t < 0.30f) {
            final float flashA = 1f - t / 0.30f;
            shapes.begin(ShapeRenderer.ShapeType.Line);
            Gdx.gl.glLineWidth(4f);
            shapes.setColor(0.55f, 0.55f, 0.60f, flashA * 0.75f);
            drawCircleOutline(shapes, cx, cy, puffR * 0.7f * (1f + t * 1.2f), 48);
            Gdx.gl.glLineWidth(1f);
            shapes.end();
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0.55f, 0.55f, 0.60f, flashA * 0.55f);
            drawCircle(shapes, cx, cy, puffR * 0.55f * (1f + t), 32);
            shapes.setColor(1f, 1f, 1f, flashA * 0.85f);
            drawCircle(shapes, cx, cy, puffR * 0.35f * (1f + t), 24);
            shapes.end();
        }
        // Ember flecks
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < 10; i++) {
            final float seed = i * 0.439f;
            final float phase = (t * 1.6f + seed) % 1.0f;
            final float a = (seed * (float) Math.PI * 2f + now * 0.001f) % ((float) Math.PI * 2f);
            final float dist = puffR * 0.4f + phase * puffR * 0.7f;
            final float lift = phase * 28f;
            final float ex = cx + (float) Math.cos(a) * dist;
            final float ey = cy + (float) Math.sin(a) * dist + lift;  // +lift: native Y-up flips relative to web Y-down
            final float eA = alpha * (1f - phase) * 0.95f;
            if (eA <= 0.05f) continue;
            shapes.setColor(1.00f, 0.55f, 0.15f, eA * 0.4f);
            drawCircle(shapes, ex, ey, 3f, 10);
            shapes.setColor(1.00f, 0.78f, 0.30f, eA);
            drawCircle(shapes, ex, ey, 1.5f, 8);
        }
        shapes.end();
    }

    /**
     * Wizard / Mystic Frost Nova — 12 diamond ice spikes radiating outward
     * from a cold halo, with a tiny white central frost burst.
     * Procedural port of renderer.js case 19.
     */
    private void renderFrostNova(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        // Halo
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.50f, 0.82f, 1.00f, alpha * 0.18f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(0.50f, 0.82f, 1.00f, alpha * 0.85f);
        drawCircleOutline(shapes, cx, cy, radius, 48);
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // Crystal diamond spikes
        final int spikes = 12;
        final float spikeReach = radius * (0.55f + 0.55f * t);
        final float baseW = 9f;
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < spikes; i++) {
            final float a = (i / (float) spikes) * (float) Math.PI * 2f;
            final float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            final float tipX = cx + ca * spikeReach;
            final float tipY = cy + sa * spikeReach;
            final float perpX = -sa * baseW, perpY = ca * baseW;
            final float innerX = cx + ca * (spikeReach * 0.35f);
            final float innerY = cy + sa * (spikeReach * 0.35f);
            final float tailX = cx + ca * (spikeReach * 0.05f);
            final float tailY = cy + sa * (spikeReach * 0.05f);
            // Diamond as two triangles
            shapes.setColor(0.50f, 0.82f, 1.00f, alpha * 0.6f);
            shapes.triangle(tipX, tipY, innerX + perpX, innerY + perpY, tailX, tailY);
            shapes.triangle(tipX, tipY, tailX, tailY, innerX - perpX, innerY - perpY);
        }
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(2f);
        for (int i = 0; i < spikes; i++) {
            final float a = (i / (float) spikes) * (float) Math.PI * 2f;
            final float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            final float tipX = cx + ca * spikeReach;
            final float tipY = cy + sa * spikeReach;
            final float perpX = -sa * baseW, perpY = ca * baseW;
            final float innerX = cx + ca * (spikeReach * 0.35f);
            final float innerY = cy + sa * (spikeReach * 0.35f);
            final float tailX = cx + ca * (spikeReach * 0.05f);
            final float tailY = cy + sa * (spikeReach * 0.05f);
            shapes.setColor(0.19f, 0.44f, 0.82f, alpha * 0.95f);
            shapes.line(tipX, tipY, innerX + perpX, innerY + perpY);
            shapes.line(innerX + perpX, innerY + perpY, tailX, tailY);
            shapes.line(tailX, tailY, innerX - perpX, innerY - perpY);
            shapes.line(innerX - perpX, innerY - perpY, tipX, tipY);
            // Inner core line
            shapes.setColor(1f, 1f, 1f, alpha);
            shapes.line(cx + ca * (spikeReach * 0.08f), cy + sa * (spikeReach * 0.08f), tipX, tipY);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // Central frost burst
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1f, 1f, 1f, alpha * 0.55f);
        drawCircle(shapes, cx, cy, radius * 0.12f, 16);
        shapes.end();
    }

    /**
     * Hunter Reticle — red 4-corner crosshair sweeping inward toward the
     * target, with center cross-tick lock indicator.
     * Procedural port of renderer.js case 21.
     */
    private void renderPoisonCloud(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        // Cloud body
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.19f, 0.31f, 0.06f, alpha * 0.35f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.setColor(0.38f, 0.75f, 0.13f, alpha * 0.40f);
        drawCircle(shapes, cx, cy, radius * 0.85f, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(0.67f, 1.00f, 0.50f, alpha * 0.85f);
        drawCircleOutline(shapes, cx, cy, radius, 48);
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // Bubbles
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int bubbles = 9;
        for (int i = 0; i < bubbles; i++) {
            final float seed = i * 0.591f;
            final float a = seed * (float) Math.PI * 2f + now * 0.001f;
            final float dist = radius * (0.2f + 0.55f * ((seed * 17f) % 1f));
            final float bx = cx + (float) Math.cos(a) * dist;
            final float by = cy + (float) Math.sin(a) * dist;
            final float br = 4f + 2f * (float) Math.sin(now * 0.008f + seed * 7f);
            shapes.setColor(0.38f, 0.75f, 0.13f, alpha * 0.75f);
            drawCircle(shapes, bx, by, br + 1f, 14);
            shapes.setColor(0.67f, 1.00f, 0.50f, alpha * 0.9f);
            drawCircle(shapes, bx, by, br * 0.55f, 12);
        }
        shapes.end();
    }

    /**
     * Wizard / Storm Lightning Strike — vertical zigzag bolt crashing down
     * with bright white core, ground impact ring expanding outward, and
     * yellow burst at impact point.
     * Procedural port of renderer.js case 25. Native Y-up means the bolt
     * descends from cy+r*2.2 to cy (web: from cy-r*2.2 downward to cy).
     */
    private void renderLightningStrike(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        final int segs = 6;
        // Outer dark zigzag
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(6f);
        shapes.setColor(0.50f, 0.38f, 0.06f, alpha * 0.7f);
        float px = cx + (float) Math.sin(now * 0.05f) * 8f;
        float py = cy + radius * 2.2f;
        for (int s = 1; s <= segs; s++) {
            final float frac = s / (float) segs;
            final float wob = ((float) Math.sin(now * 0.04f + s * 1.7f) * 14f) * (1f - frac);
            final float nx = cx + wob;
            final float ny = cy + radius * 2.2f * (1f - frac);
            shapes.line(px, py, nx, ny);
            px = nx; py = ny;
        }
        // Bright yellow bolt
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(1.00f, 0.94f, 0.38f, alpha);
        px = cx + (float) Math.sin(now * 0.05f) * 8f;
        py = cy + radius * 2.2f;
        for (int s = 1; s <= segs; s++) {
            final float frac = s / (float) segs;
            final float wob = ((float) Math.sin(now * 0.04f + s * 1.7f) * 14f) * (1f - frac);
            final float nx = cx + wob;
            final float ny = cy + radius * 2.2f * (1f - frac);
            shapes.line(px, py, nx, ny);
            px = nx; py = ny;
        }
        // White core line straight down
        Gdx.gl.glLineWidth(1f);
        shapes.setColor(1f, 1f, 1f, alpha);
        shapes.line(cx, cy + radius * 2.2f, cx, cy);
        // Ground impact ring
        final float ringR = radius * (0.4f + t * 0.8f);
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(1.00f, 0.94f, 0.38f, (1f - t) * alpha);
        drawCircleOutline(shapes, cx, cy, ringR, 48);
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // Burst at impact
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1.00f, 0.94f, 0.38f, alpha * 0.6f);
        drawCircle(shapes, cx, cy, 14f, 20);
        shapes.setColor(1f, 1f, 1f, alpha);
        drawCircle(shapes, cx, cy, 6f, 14);
        shapes.end();
    }

    /**
     * Priest / Paladin Smite Flash — golden cross of light + central white
     * burst + 4 diagonal ground cracks radiating outward.
     * Procedural port of renderer.js case 29.
     */
    private void renderSmiteFlash(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        // Gold cross
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(8f);
        shapes.setColor(1.00f, 0.82f, 0.38f, alpha);
        shapes.line(cx, cy - radius * 0.6f, cx, cy + radius * 0.6f);
        shapes.line(cx - radius * 0.45f, cy - radius * 0.1f,
                    cx + radius * 0.45f, cy - radius * 0.1f);
        // White inner highlight
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(1f, 1f, 1f, alpha);
        shapes.line(cx, cy - radius * 0.6f, cx, cy + radius * 0.6f);
        shapes.line(cx - radius * 0.45f, cy - radius * 0.1f,
                    cx + radius * 0.45f, cy - radius * 0.1f);
        // Ground cracks (4 diagonals)
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(0.50f, 0.38f, 0.13f, alpha * 0.85f);
        for (int i = 0; i < 4; i++) {
            final float a = (i / 4f) * (float) Math.PI * 2f + (float) Math.PI / 4f;
            final float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            shapes.line(cx + ca * radius * 0.6f, cy + sa * radius * 0.6f,
                        cx + ca * radius * 1.1f, cy + sa * radius * 1.1f);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // Center burst
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1.00f, 0.82f, 0.38f, alpha * 0.55f);
        drawCircle(shapes, cx, cy, radius * 0.55f, 32);
        shapes.setColor(1f, 1f, 1f, alpha * 0.75f);
        drawCircle(shapes, cx, cy, radius * 0.35f, 24);
        shapes.end();
    }

    /**
     * Necromancer Bone Spikes — 9 jagged white shards erupting from the
     * ground, each with a darker shadow base. Spikes grow in the first 45%
     * of life. Procedural port of renderer.js case 24.
     */
    private void renderBoneSpikes(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, alpha * 0.15f);
        drawCircle(shapes, cx, cy, radius, 48);
        final int spikes = 9;
        final float grow = Math.min(t * 2.2f, 1f);
        for (int i = 0; i < spikes; i++) {
            final float seed = i * 0.683f;
            final float a = (i / (float) spikes) * (float) Math.PI * 2f + seed;
            final float dist = radius * (0.20f + 0.7f * ((seed * 13f) % 1f));
            final float bx = cx + (float) Math.cos(a) * dist;
            final float by = cy + (float) Math.sin(a) * dist;
            final float h = (14f + 10f * ((seed * 7f) % 1f)) * grow;
            final float w = 6f;
            // Shadow base triangle (point up in native Y-up: tip = by + h)
            shapes.setColor(0.31f, 0.28f, 0.19f, alpha * 0.7f);
            shapes.triangle(bx - w, by, bx + w, by, bx, by + h);
            // Bone face
            shapes.setColor(0.92f, 0.88f, 0.75f, alpha);
            shapes.triangle(bx - w * 0.7f, by + 1f, bx + w * 0.7f, by + 1f, bx, by + h * 0.92f);
        }
        shapes.end();
    }

    /**
     * Wizard Mana Bolt — 6 rotating arcane star arms with violet halo and
     * bright white core. Procedural port of renderer.js case 26.
     */
    private void renderManaBolt(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.56f, 0.25f, 1.00f, alpha * 0.25f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.end();
        final int arms = 6;
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(4f);
        shapes.setColor(0.75f, 0.50f, 1.00f, alpha);
        for (int i = 0; i < arms; i++) {
            final float a = (i / (float) arms) * (float) Math.PI * 2f + now * 0.003f;
            shapes.line(cx, cy, cx + (float) Math.cos(a) * radius, cy + (float) Math.sin(a) * radius);
        }
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1f, 1f, 1f, alpha);
        for (int i = 0; i < arms; i++) {
            final float a = (i / (float) arms) * (float) Math.PI * 2f + now * 0.003f;
            shapes.line(cx, cy,
                        cx + (float) Math.cos(a) * radius * 0.95f,
                        cy + (float) Math.sin(a) * radius * 0.95f);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.75f, 0.50f, 1.00f, alpha * 0.7f);
        drawCircle(shapes, cx, cy, 14f, 18);
        shapes.setColor(1f, 1f, 1f, alpha);
        drawCircle(shapes, cx, cy, 8f, 14);
        shapes.end();
    }

    /**
     * Mystic Time Stop — silver chronometer ring with 12 tick marks and
     * frozen hour/minute hands (no animation: time is stopped).
     * Procedural port of renderer.js case 27.
     */
    private void renderTimeStop(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.25f, 0.28f, 0.35f, alpha * 0.20f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(5f);
        shapes.setColor(0.75f, 0.82f, 0.88f, alpha * 0.95f);
        drawCircleOutline(shapes, cx, cy, radius, 64);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1f, 1f, 1f, alpha);
        drawCircleOutline(shapes, cx, cy, radius - 3f, 64);
        // Tick marks
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(0.75f, 0.82f, 0.88f, alpha);
        for (int i = 0; i < 12; i++) {
            final float a = (i / 12f) * (float) Math.PI * 2f;
            final float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            shapes.line(cx + ca * (radius - 6f), cy + sa * (radius - 6f),
                        cx + ca * (radius - 14f), cy + sa * (radius - 14f));
        }
        // Frozen hands (hour pointing up = +y native, minute toward upper-right)
        Gdx.gl.glLineWidth(4f);
        shapes.setColor(1f, 1f, 1f, alpha);
        shapes.line(cx, cy, cx, cy + radius * 0.55f);
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(0.75f, 0.82f, 0.88f, alpha);
        shapes.line(cx, cy, cx + radius * 0.7f, cy - radius * 0.1f);
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1f, 1f, 1f, alpha);
        drawCircle(shapes, cx, cy, 5f, 14);
        shapes.end();
    }

    /**
     * Druid Beast Claws — 3 angled claw-slash arcs at the caster, each
     * with shadow + sharp claw + bright white highlight, rotating slowly.
     * Procedural port of renderer.js case 28.
     */
    private void renderBeastClaws(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        final float reach = radius * 1.4f;
        final float sweep = 0.55f;
        final int slashes = 3;
        final int segs = 8;
        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < slashes; i++) {
            final float baseA = (i / (float) slashes) * (float) Math.PI * 2f + now * 0.001f;
            // Shadow
            Gdx.gl.glLineWidth(7f);
            shapes.setColor(0.25f, 0.13f, 0.06f, alpha * 0.85f);
            for (int s = 0; s < segs; s++) {
                final float a0 = baseA - sweep / 2f + (s / (float) segs) * sweep;
                final float a1 = baseA - sweep / 2f + ((s + 1) / (float) segs) * sweep;
                shapes.line(cx + (float) Math.cos(a0) * reach, cy + (float) Math.sin(a0) * reach,
                            cx + (float) Math.cos(a1) * reach, cy + (float) Math.sin(a1) * reach);
            }
            // Sharp claw
            Gdx.gl.glLineWidth(4f);
            shapes.setColor(0.82f, 0.63f, 0.38f, alpha);
            for (int s = 0; s < segs; s++) {
                final float a0 = baseA - sweep / 2f + (s / (float) segs) * sweep;
                final float a1 = baseA - sweep / 2f + ((s + 1) / (float) segs) * sweep;
                shapes.line(cx + (float) Math.cos(a0) * reach, cy + (float) Math.sin(a0) * reach,
                            cx + (float) Math.cos(a1) * reach, cy + (float) Math.sin(a1) * reach);
            }
            // Bright highlight
            Gdx.gl.glLineWidth(2f);
            shapes.setColor(1f, 1f, 1f, alpha * 0.9f);
            for (int s = 0; s < segs; s++) {
                final float a0 = baseA - sweep / 2f + (s / (float) segs) * sweep;
                final float a1 = baseA - sweep / 2f + ((s + 1) / (float) segs) * sweep;
                shapes.line(cx + (float) Math.cos(a0) * reach, cy + (float) Math.sin(a0) * reach,
                            cx + (float) Math.cos(a1) * reach, cy + (float) Math.sin(a1) * reach);
            }
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
    }

    /**
     * Ninja Death Blossom ult — 8 radial slash arcs with dark outer trace
     * and bright blade core, rotating with progress + red center pip.
     * Procedural port of renderer.js case 30.
     */
    private void renderDeathBlossom(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final int slashes = 8;
        final float reach = radius * 1.1f;
        final float sweep = 0.42f;
        final int segs = 6;
        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < slashes; i++) {
            final float baseA = (i / (float) slashes) * (float) Math.PI * 2f + t * (float) Math.PI * 0.5f;
            // Outer dark
            Gdx.gl.glLineWidth(6f);
            shapes.setColor(0.16f, 0.13f, 0.19f, alpha * 0.85f);
            for (int s = 0; s < segs; s++) {
                final float a0 = baseA - sweep / 2f + (s / (float) segs) * sweep;
                final float a1 = baseA - sweep / 2f + ((s + 1) / (float) segs) * sweep;
                shapes.line(cx + (float) Math.cos(a0) * reach, cy + (float) Math.sin(a0) * reach,
                            cx + (float) Math.cos(a1) * reach, cy + (float) Math.sin(a1) * reach);
            }
            // Bright blade
            Gdx.gl.glLineWidth(3f);
            shapes.setColor(0.88f, 0.88f, 0.94f, alpha);
            for (int s = 0; s < segs; s++) {
                final float a0 = baseA - sweep / 2f + (s / (float) segs) * sweep;
                final float a1 = baseA - sweep / 2f + ((s + 1) / (float) segs) * sweep;
                shapes.line(cx + (float) Math.cos(a0) * reach, cy + (float) Math.sin(a0) * reach,
                            cx + (float) Math.cos(a1) * reach, cy + (float) Math.sin(a1) * reach);
            }
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1.00f, 0.25f, 0.38f, alpha);
        drawCircle(shapes, cx, cy, 6f, 14);
        shapes.end();
    }

    /**
     * Bard Inspire Bloom — 6 golden flower petals expanding outward from
     * the center, with deep-gold base, gold body, and white core.
     * Procedural port of renderer.js case 31.
     */
    private void renderInspireBloom(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final int petals = 6;
        final float reach = radius * (0.55f + 0.55f * t);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < petals; i++) {
            final float a = (i / (float) petals) * (float) Math.PI * 2f + t * (float) Math.PI * 0.25f;
            final float px = cx + (float) Math.cos(a) * reach * 0.5f;
            final float py = cy + (float) Math.sin(a) * reach * 0.5f;
            shapes.setColor(0.63f, 0.44f, 0.13f, alpha * 0.75f);
            drawCircle(shapes, px, py, reach * 0.32f, 18);
            shapes.setColor(1.00f, 0.82f, 0.38f, alpha * 0.95f);
            drawCircle(shapes, px, py, reach * 0.26f, 16);
            shapes.setColor(1f, 1f, 1f, alpha * 0.6f);
            drawCircle(shapes, px, py, reach * 0.12f, 12);
        }
        shapes.setColor(1f, 1f, 1f, alpha);
        drawCircle(shapes, cx, cy, 6f, 14);
        shapes.setColor(1.00f, 0.82f, 0.38f, alpha);
        drawCircle(shapes, cx, cy, 11f, 18);
        shapes.end();
    }

    /**
     * Berserker Reckless Slash — wide sweeping red arc with dark outer
     * trace + bright red blade + white highlight along the sweep.
     * Procedural port of renderer.js case 32. (Web uses no rotation —
     * always sweeps right; we keep that for consistency.)
     */
    private void renderRecklessSlash(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final float reach = radius * 1.05f;
        final float sweep = 1.4f;
        final int segs = 14;
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(10f);
        shapes.setColor(0.38f, 0.00f, 0.06f, alpha * 0.85f);
        for (int s = 0; s < segs; s++) {
            final float a0 = -sweep / 2f + (s / (float) segs) * sweep;
            final float a1 = -sweep / 2f + ((s + 1) / (float) segs) * sweep;
            shapes.line(cx + (float) Math.cos(a0) * reach, cy + (float) Math.sin(a0) * reach,
                        cx + (float) Math.cos(a1) * reach, cy + (float) Math.sin(a1) * reach);
        }
        Gdx.gl.glLineWidth(6f);
        shapes.setColor(1.00f, 0.13f, 0.19f, alpha);
        for (int s = 0; s < segs; s++) {
            final float a0 = -sweep / 2f + (s / (float) segs) * sweep;
            final float a1 = -sweep / 2f + ((s + 1) / (float) segs) * sweep;
            shapes.line(cx + (float) Math.cos(a0) * reach, cy + (float) Math.sin(a0) * reach,
                        cx + (float) Math.cos(a1) * reach, cy + (float) Math.sin(a1) * reach);
        }
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(1f, 1f, 1f, alpha * 0.9f);
        for (int s = 0; s < segs; s++) {
            final float a0 = -sweep / 2f + (s / (float) segs) * sweep;
            final float a1 = -sweep / 2f + ((s + 1) / (float) segs) * sweep;
            shapes.line(cx + (float) Math.cos(a0) * reach, cy + (float) Math.sin(a0) * reach,
                        cx + (float) Math.cos(a1) * reach, cy + (float) Math.sin(a1) * reach);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
    }

    /**
     * Ninja Star Shuriken — rotating 4-point throwing star drawn as two
     * crossed triangles + bright cross highlight + dark center stud.
     * Procedural port of renderer.js case 33.
     */
    private void renderStarShuriken(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        final float rot = now * 0.018f;
        final float armR = radius * (0.6f + 0.4f * t);
        final float[] px = new float[4];
        final float[] py = new float[4];
        for (int i = 0; i < 4; i++) {
            final float a = rot + (i / 4f) * (float) Math.PI * 2f;
            px[i] = cx + (float) Math.cos(a) * armR;
            py[i] = cy + (float) Math.sin(a) * armR;
        }
        // Steel body as two triangles forming the diamond
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.75f, 0.78f, 0.82f, alpha * 0.85f);
        shapes.triangle(px[0], py[0], px[1], py[1], px[2], py[2]);
        shapes.triangle(px[0], py[0], px[2], py[2], px[3], py[3]);
        shapes.end();
        // Outer dark frame
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(4f);
        shapes.setColor(0.25f, 0.28f, 0.31f, alpha);
        shapes.line(px[0], py[0], px[1], py[1]);
        shapes.line(px[1], py[1], px[2], py[2]);
        shapes.line(px[2], py[2], px[3], py[3]);
        shapes.line(px[3], py[3], px[0], py[0]);
        // Bright cross highlight
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(1f, 1f, 1f, alpha);
        shapes.line(px[0], py[0], px[2], py[2]);
        shapes.line(px[1], py[1], px[3], py[3]);
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.25f, 0.28f, 0.31f, alpha);
        drawCircle(shapes, cx, cy, 5f, 12);
        shapes.end();
    }

    /**
     * Sorcerer Blink Glyph — violet runic portal: outer rune ring,
     * translucent void interior, 6 runic tick-runes orbiting the rim, and
     * a central vertical rift line. Procedural port of renderer.js case 20.
     */
    private void renderBlinkGlyph(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        final float phase = Math.min(t * 2f, 1f);
        final float ringR = radius * (0.4f + phase * 0.7f);
        // Void interior
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.10f, 0.04f, 0.19f, alpha * 0.55f);
        drawCircle(shapes, cx, cy, ringR, 48);
        shapes.end();
        // Outer rune ring
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(4f);
        shapes.setColor(0.78f, 0.50f, 1.00f, alpha);
        drawCircleOutline(shapes, cx, cy, ringR, 64);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(0.56f, 0.25f, 1.00f, alpha * 0.85f);
        drawCircleOutline(shapes, cx, cy, ringR - 4f, 64);
        // Vertical rift line
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(0.78f, 0.50f, 1.00f, alpha * 0.95f);
        shapes.line(cx, cy - ringR * 0.9f, cx, cy + ringR * 0.9f);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1f, 1f, 1f, alpha * 0.8f);
        shapes.line(cx, cy - ringR * 0.85f, cx, cy + ringR * 0.85f);
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // 6 rune ticks orbiting
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < 6; i++) {
            final float a = (i / 6f) * (float) Math.PI * 2f + now * 0.004f;
            final float rx = cx + (float) Math.cos(a) * ringR;
            final float ry = cy + (float) Math.sin(a) * ringR;
            shapes.setColor(0.78f, 0.50f, 1.00f, alpha);
            drawCircle(shapes, rx, ry, 4f, 12);
            shapes.setColor(1f, 1f, 1f, alpha * 0.8f);
            drawCircle(shapes, rx, ry, 1.6f, 8);
        }
        shapes.end();
    }

    /**
     * Necromancer Life Drain — 3 spiraling red ribbon streams pulling
     * INWARD from the rim to the caster, with bright pulsing center.
     * Procedural port of renderer.js case 23.
     */
    private void renderLifeDrain(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        // Halo
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.75f, 0.00f, 0.13f, alpha * 0.20f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(0.31f, 0.00f, 0.06f, alpha * 0.95f);
        drawCircleOutline(shapes, cx, cy, radius, 48);
        // 3 inward-spiraling streams
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(0.75f, 0.00f, 0.13f, alpha);
        final int arms = 3, segs = 20;
        for (int arm = 0; arm < arms; arm++) {
            final float armOff = (arm / (float) arms) * (float) Math.PI * 2f;
            for (int s = 0; s < segs - 1; s++) {
                final float t0 = s / (float) segs, t1 = (s + 1) / (float) segs;
                final float rr0 = radius * (1f - t0) + 4f;
                final float rr1 = radius * (1f - t1) + 4f;
                final float a0 = armOff + t0 * 4f + now * 0.004f;
                final float a1 = armOff + t1 * 4f + now * 0.004f;
                shapes.line(cx + (float) Math.cos(a0) * rr0, cy + (float) Math.sin(a0) * rr0,
                            cx + (float) Math.cos(a1) * rr1, cy + (float) Math.sin(a1) * rr1);
            }
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.75f, 0.00f, 0.13f, alpha);
        drawCircle(shapes, cx, cy, 8f, 16);
        shapes.setColor(1f, 1f, 1f, alpha);
        drawCircle(shapes, cx, cy, 4f, 12);
        shapes.end();
    }

    /**
     * Engineer Snare Gear — tightening iron gear ring with 12 rectangular
     * teeth around the rim. Procedural port of renderer.js case 34.
     */
    private void renderSnareGear(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        final float gearR = radius * (1f - t * 0.30f);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(5f);
        shapes.setColor(0.19f, 0.22f, 0.25f, alpha * 0.95f);
        drawCircleOutline(shapes, cx, cy, gearR, 48);
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(0.50f, 0.53f, 0.56f, alpha);
        drawCircleOutline(shapes, cx, cy, gearR - 3f, 48);
        // 12 teeth
        final int teeth = 12;
        for (int i = 0; i < teeth; i++) {
            final float a = (i / (float) teeth) * (float) Math.PI * 2f + now * 0.001f;
            final float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            Gdx.gl.glLineWidth(5f);
            shapes.setColor(0.50f, 0.53f, 0.56f, alpha);
            shapes.line(cx + ca * gearR, cy + sa * gearR,
                        cx + ca * (gearR + 8f), cy + sa * (gearR + 8f));
            Gdx.gl.glLineWidth(2f);
            shapes.setColor(1f, 1f, 1f, alpha);
            shapes.line(cx + ca * gearR, cy + sa * gearR,
                        cx + ca * (gearR + 8f), cy + sa * (gearR + 8f));
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
    }

    /**
     * Pyromancer Combustion Trap — orange explosion ring with hot inner
     * core, ember sparks, and central flash. Ring expands with progress.
     * Procedural port of renderer.js case 35.
     */
    private void renderCombustionTrap(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        final float ringR = radius * (0.4f + 0.7f * t);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1.00f, 0.38f, 0.13f, alpha * 0.45f);
        drawCircle(shapes, cx, cy, ringR, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(8f);
        shapes.setColor(0.50f, 0.25f, 0.13f, alpha * 0.9f);
        drawCircleOutline(shapes, cx, cy, ringR, 48);
        Gdx.gl.glLineWidth(5f);
        shapes.setColor(1.00f, 0.38f, 0.13f, alpha);
        drawCircleOutline(shapes, cx, cy, ringR - 4f, 48);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1.00f, 0.88f, 0.25f, alpha);
        drawCircleOutline(shapes, cx, cy, ringR - 9f, 48);
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // Embers
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int embers = 12;
        for (int i = 0; i < embers; i++) {
            final float seed = i * 0.491f;
            final float a = seed * (float) Math.PI * 2f + now * 0.002f;
            final float d = ringR * ((seed * 13f) % 1f);
            shapes.setColor(1.00f, 0.88f, 0.25f, alpha);
            drawCircle(shapes, cx + (float) Math.cos(a) * d, cy + (float) Math.sin(a) * d, 3f, 10);
        }
        // Central flash
        shapes.setColor(1.00f, 0.88f, 0.25f, alpha * 0.85f);
        drawCircle(shapes, cx, cy, ringR * 0.2f, 18);
        shapes.end();
    }

    /**
     * Warrior War Cry Wave — 4 concentric red wave-rings at staggered
     * progress offsets to evoke a roaring shockwave. Procedural port of
     * renderer.js case 36.
     */
    private void renderWarCryWave(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < 4; i++) {
            final float phase = (t * 1.5f + i * 0.18f) % 1.0f;
            final float ringR = radius * (0.2f + phase * 1.0f);
            final float ringA = (1f - phase) * alpha;
            if (ringA <= 0.02f) continue;
            Gdx.gl.glLineWidth(4f);
            shapes.setColor(0.50f, 0.00f, 0.13f, ringA * 0.85f);
            drawCircleOutline(shapes, cx, cy, ringR, 48);
            Gdx.gl.glLineWidth(2f);
            shapes.setColor(1.00f, 0.19f, 0.31f, ringA);
            drawCircleOutline(shapes, cx, cy, ringR - 3f, 48);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1.00f, 0.19f, 0.31f, alpha * 0.45f);
        drawCircle(shapes, cx, cy, radius * 0.18f, 16);
        shapes.setColor(1f, 1f, 1f, alpha);
        drawCircle(shapes, cx, cy, radius * 0.08f, 12);
        shapes.end();
    }

    /**
     * Trapper Caltrops — 10 scattered tiny 4-point metal spikes inside the
     * radius, each with a steel center stud. Procedural port of renderer.js
     * case 37.
     */
    private void renderCaltrops(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.25f, 0.28f, 0.31f, alpha * 0.18f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(0.69f, 0.72f, 0.75f, alpha * 0.5f);
        drawCircleOutline(shapes, cx, cy, radius, 48);
        final int caltrops = 10;
        for (int i = 0; i < caltrops; i++) {
            final float seed = i * 0.421f;
            final float a = seed * (float) Math.PI * 2f;
            final float d = radius * ((seed * 11f) % 0.85f);
            final float kx = cx + (float) Math.cos(a) * d;
            final float ky = cy + (float) Math.sin(a) * d;
            final float arm = 6f;
            Gdx.gl.glLineWidth(3f);
            shapes.setColor(0.25f, 0.28f, 0.31f, alpha);
            shapes.line(kx - arm, ky, kx + arm, ky);
            shapes.line(kx, ky - arm, kx, ky + arm);
            shapes.line(kx - arm * 0.7f, ky - arm * 0.7f, kx + arm * 0.7f, ky + arm * 0.7f);
            shapes.line(kx - arm * 0.7f, ky + arm * 0.7f, kx + arm * 0.7f, ky - arm * 0.7f);
            Gdx.gl.glLineWidth(1f);
            shapes.setColor(1f, 1f, 1f, alpha);
            shapes.line(kx - arm, ky, kx + arm, ky);
            shapes.line(kx, ky - arm, kx, ky + arm);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < caltrops; i++) {
            final float seed = i * 0.421f;
            final float a = seed * (float) Math.PI * 2f;
            final float d = radius * ((seed * 11f) % 0.85f);
            shapes.setColor(0.69f, 0.72f, 0.75f, alpha);
            drawCircle(shapes, cx + (float) Math.cos(a) * d, cy + (float) Math.sin(a) * d, 2f, 8);
        }
        shapes.end();
    }

    /**
     * Wizard Arcane Aura — purple swirling self-aura with 8 orbiting
     * sparks at varying radii. Procedural port of renderer.js case 38.
     */
    private void renderArcaneAura(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.56f, 0.25f, 1.00f, alpha * 0.20f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(0.75f, 0.50f, 1.00f, alpha * 0.9f);
        drawCircleOutline(shapes, cx, cy, radius, 48);
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // Orbiting sparks
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int sparks = 8;
        for (int i = 0; i < sparks; i++) {
            final float a = (i / (float) sparks) * (float) Math.PI * 2f + now * 0.005f;
            final float wob = (float) Math.sin(now * 0.01f + i) * 0.15f;
            final float orbR = radius * (0.85f + wob);
            final float ex = cx + (float) Math.cos(a) * orbR;
            final float ey = cy + (float) Math.sin(a) * orbR;
            shapes.setColor(0.75f, 0.50f, 1.00f, alpha);
            drawCircle(shapes, ex, ey, 4f, 12);
            shapes.setColor(1f, 1f, 1f, alpha);
            drawCircle(shapes, ex, ey, 1.8f, 8);
        }
        shapes.end();
    }

    /**
     * Ninja Haste Wind — 5 vertical cyan streamers at the caster's feet
     * sliding upward as progress advances. Procedural port of renderer.js
     * case 39. (Native Y-up: streamers travel up the screen with phase.)
     */
    private void renderHasteWind(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final int streamers = 5;
        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < streamers; i++) {
            final float seed = i * 0.523f;
            final float phase = (t + seed) % 1.0f;
            final float xOff = (seed * 2f - 1f) * radius * 0.6f;
            // Web: yStart = sy + r*0.4 - phase * r*1.2; yEnd = yStart + 18 (downward in web Y-down).
            // In native Y-up, mirror: streamer rises up the screen.
            final float yStart = cy - radius * 0.4f + phase * radius * 1.2f;
            final float yEnd   = yStart - 18f;
            final float a = (1f - phase) * alpha;
            Gdx.gl.glLineWidth(4f);
            shapes.setColor(0.25f, 0.88f, 1.00f, a);
            shapes.line(cx + xOff, yStart, cx + xOff, yEnd);
            Gdx.gl.glLineWidth(2f);
            shapes.setColor(1f, 1f, 1f, a);
            shapes.line(cx + xOff, yStart, cx + xOff, yEnd);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
    }

    /**
     * Standard-bearer Banner Raise — vertical red banner with gold pole
     * above the caster + ground stomp shockwave. Procedural port of
     * renderer.js case 40. (Native Y-up: banner extends upward = +y.)
     */
    private void renderBannerRaise(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final float h = radius * 1.6f * Math.min(t * 1.6f, 1f);
        // Pole (gold) — extends upward
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1.00f, 0.82f, 0.38f, alpha);
        shapes.rect(cx - 2f, cy, 4f, radius * 1.5f);
        // Banner cloth — dark backing
        shapes.setColor(0.31f, 0.00f, 0.06f, alpha * 0.95f);
        shapes.rect(cx + 2f, cy + radius * 1.4f - h, 30f, h);
        // Banner cloth — red face
        shapes.setColor(0.75f, 0.06f, 0.19f, alpha);
        shapes.rect(cx + 4f, cy + radius * 1.4f - 2f - (h - 4f), 26f, h - 4f);
        shapes.end();
        // Banner emblem (X) — drawn at the top of banner
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1f, 1f, 1f, alpha);
        final float ey0 = cy + radius * 1.3f;
        shapes.line(cx + 8f,  ey0, cx + 26f, ey0 - 14f);
        shapes.line(cx + 26f, ey0, cx + 8f,  ey0 - 14f);
        // Stomp shockwave at feet
        final float ringR = radius * (0.3f + t * 0.8f);
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(0.75f, 0.06f, 0.19f, (1f - t) * alpha);
        drawCircleOutline(shapes, cx, cy - 8f, ringR, 48);
        Gdx.gl.glLineWidth(1f);
        shapes.end();
    }

    /**
     * Berserker Rampage Aura — dark-red ground halo with 10 outer flame
     * tongues drawn as triangles + hot gold inner highlight triangles.
     * Procedural port of renderer.js case 41.
     */
    private void renderRampageAura(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.38f, 0.00f, 0.06f, alpha * 0.35f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.setColor(1.00f, 0.25f, 0.13f, alpha * 0.45f);
        drawCircle(shapes, cx, cy, radius * 0.85f, 48);
        final int tongues = 10;
        for (int i = 0; i < tongues; i++) {
            final float a = (i / (float) tongues) * (float) Math.PI * 2f + now * 0.0035f;
            final float wob = 0.85f + 0.15f * (float) Math.sin(now * 0.015f + i);
            final float baseX = cx + (float) Math.cos(a) * radius * 0.85f;
            final float baseY = cy + (float) Math.sin(a) * radius * 0.85f;
            final float tipX = cx + (float) Math.cos(a) * radius * 1.15f * wob;
            final float tipY = cy + (float) Math.sin(a) * radius * 1.15f * wob;
            final float perpX = -(float) Math.sin(a) * 6f;
            final float perpY =  (float) Math.cos(a) * 6f;
            shapes.setColor(1.00f, 0.25f, 0.13f, alpha * 0.95f);
            shapes.triangle(baseX + perpX, baseY + perpY,
                            tipX, tipY,
                            baseX - perpX, baseY - perpY);
            shapes.setColor(1.00f, 0.82f, 0.25f, alpha);
            shapes.triangle(baseX + perpX * 0.6f, baseY + perpY * 0.6f,
                            tipX, tipY,
                            baseX - perpX * 0.6f, baseY - perpY * 0.6f);
        }
        shapes.end();
    }

    /**
     * Storm Druid Storm Aura — 5 zigzag yellow bolts emanating outward
     * with a deep-blue ground halo + bright white center pip.
     * Procedural port of renderer.js case 42.
     */
    private void renderStormAura(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.13f, 0.16f, 0.28f, alpha * 0.25f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1.00f, 0.94f, 0.38f, alpha * 0.75f);
        drawCircleOutline(shapes, cx, cy, radius, 48);
        // 5 bolts
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(1.00f, 0.94f, 0.38f, alpha);
        final int bolts = 5;
        final int segs = 5;
        for (int i = 0; i < bolts; i++) {
            final float baseA = (i / (float) bolts) * (float) Math.PI * 2f + now * 0.003f + (float) Math.sin(now * 0.01f + i);
            float px = cx, py = cy;
            for (int s = 1; s <= segs; s++) {
                final float tt = s / (float) segs;
                final float wob = (float) Math.sin(now * 0.03f + s + i) * 8f;
                final float tA = baseA + wob * 0.02f;
                final float nx = cx + (float) Math.cos(tA) * radius * tt;
                final float ny = cy + (float) Math.sin(tA) * radius * tt;
                shapes.line(px, py, nx, ny);
                px = nx; py = ny;
            }
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1f, 1f, 1f, alpha);
        drawCircle(shapes, cx, cy, 5f, 14);
        shapes.end();
    }

    /**
     * Necromancer Death Pact Aura — 10 dark-red mist wisps spiraling at
     * varying radii with deep ground halo. Procedural port of renderer.js
     * case 43.
     */
    private void renderDeathPactAura(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.19f, 0.00f, 0.06f, alpha * 0.40f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(0.63f, 0.00f, 0.13f, alpha * 0.85f);
        drawCircleOutline(shapes, cx, cy, radius, 48);
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // Spiral wisps
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int wisps = 10;
        for (int i = 0; i < wisps; i++) {
            final float seed = i * 0.671f;
            final float a = seed * (float) Math.PI * 2f + now * 0.004f;
            final float orbR = radius * (0.4f + ((seed * 7f) % 0.6f));
            final float wx = cx + (float) Math.cos(a) * orbR;
            final float wy = cy + (float) Math.sin(a) * orbR;
            shapes.setColor(0.38f, 0.13f, 0.19f, alpha * 0.85f);
            drawCircle(shapes, wx, wy, 5f, 12);
            shapes.setColor(0.63f, 0.00f, 0.13f, alpha);
            drawCircle(shapes, wx, wy, 2.5f, 10);
        }
        shapes.end();
    }

    /**
     * Berserker Blade Storm — 2 dual rotating blades through the player,
     * drawn as long line segments crossing the center. Procedural port of
     * renderer.js case 44.
     */
    private void renderBladeStorm(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        final float rot = now * 0.025f;
        final float orbR = radius * 0.85f;
        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < 2; i++) {
            final float a = rot + i * (float) Math.PI;
            final float x0 = cx + (float) Math.cos(a) * orbR;
            final float y0 = cy + (float) Math.sin(a) * orbR;
            final float x1 = cx + (float) Math.cos(a + (float) Math.PI) * orbR;
            final float y1 = cy + (float) Math.sin(a + (float) Math.PI) * orbR;
            Gdx.gl.glLineWidth(7f);
            shapes.setColor(0.13f, 0.13f, 0.16f, alpha * 0.85f);
            shapes.line(x0, y0, x1, y1);
            Gdx.gl.glLineWidth(4f);
            shapes.setColor(0.88f, 0.88f, 0.94f, alpha);
            shapes.line(x0, y0, x1, y1);
            Gdx.gl.glLineWidth(2f);
            shapes.setColor(1f, 1f, 1f, alpha);
            shapes.line(x0, y0, x1, y1);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1f, 1f, 1f, alpha);
        drawCircle(shapes, cx, cy, 5f, 12);
        shapes.end();
    }

    /**
     * Knight Taunt Roar — translucent red disc + bright outline ring +
     * small bright center dot. Tightens slightly as it fades.
     * Procedural port of renderer.js case 17.
     */
    private void renderTauntRoar(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final float r2 = radius * (1f - 0.25f * t);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1.00f, 0.13f, 0.19f, alpha * 0.55f);
        drawCircle(shapes, cx, cy, r2, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(4f);
        shapes.setColor(1.00f, 0.31f, 0.38f, alpha);
        drawCircleOutline(shapes, cx, cy, r2, 48);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1f, 1f, 1f, alpha * 0.7f);
        drawCircleOutline(shapes, cx, cy, r2 - 3f, 48);
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1f, 1f, 1f, alpha * 0.6f);
        drawCircle(shapes, cx, cy, r2 * 0.18f, 14);
        shapes.end();
    }

    /**
     * Knight Brace Stance — black core with bright accent rim, 8 spoke
     * decorations, and 4 cardinal bright dots. Expands outward with
     * progress. Procedural port of renderer.js case 18.
     */
    private void renderBraceStance(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final float ringR = radius * (0.35f + 0.95f * t);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.06f, 0.06f, 0.07f, alpha * 0.70f);
        drawCircle(shapes, cx, cy, ringR, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(5f);
        shapes.setColor(0.78f, 0.78f, 0.82f, alpha);
        drawCircleOutline(shapes, cx, cy, ringR, 64);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1f, 1f, 1f, alpha * 0.8f);
        drawCircleOutline(shapes, cx, cy, ringR - 4f, 64);
        // 8 spoke decorations
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(0.78f, 0.78f, 0.82f, alpha * 0.95f);
        final int spokes = 8;
        for (int i = 0; i < spokes; i++) {
            final float a = (i / (float) spokes) * (float) Math.PI * 2f + t * (float) Math.PI * 0.5f;
            final float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            shapes.line(cx + ca * (ringR - 8f), cy + sa * (ringR - 8f),
                        cx + ca * (ringR + 6f), cy + sa * (ringR + 6f));
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // 4 cardinal dots
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < 4; i++) {
            final float a = (i / 4f) * (float) Math.PI * 2f + t * (float) Math.PI * 0.5f;
            final float dx = (float) Math.cos(a) * ringR;
            final float dy = (float) Math.sin(a) * ringR;
            shapes.setColor(1f, 1f, 1f, alpha);
            drawCircle(shapes, cx + dx, cy + dy, 3f, 10);
        }
        shapes.end();
    }

    /**
     * Knight Phalanx Shield Dome — HARDCODED BLUE protective bubble: dense
     * translucent blue interior, heavy multi-layer rim, 4 rotating energy
     * ripples, edge sparks, plus a bright cast-moment flash on the first
     * 15% of life. Procedural port of renderer.js case 16.
     */
    private void renderShieldDome(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        // 1. Translucent BLUE interior
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.06f, 0.50f, 1.00f, alpha * 0.42f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.setColor(0.63f, 0.86f, 1.00f, alpha * 0.22f);
        drawCircle(shapes, cx, cy, radius * 0.85f, 48);
        shapes.setColor(1f, 1f, 1f, alpha * 0.10f);
        drawCircle(shapes, cx, cy, radius * 0.55f, 48);
        shapes.end();
        // 2. Heavy multi-layer rim
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(10f);
        shapes.setColor(0.23f, 0.66f, 1.00f, alpha);
        drawCircleOutline(shapes, cx, cy, radius, 64);
        Gdx.gl.glLineWidth(5f);
        shapes.setColor(0.63f, 0.86f, 1.00f, alpha);
        drawCircleOutline(shapes, cx, cy, radius - 7f, 64);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1f, 1f, 1f, alpha * 0.85f);
        drawCircleOutline(shapes, cx, cy, radius - 12f, 64);
        // 3. Rotating energy ripples — 4 short white arcs
        final int ripples = 4;
        final float ripPhase = now * 0.003f;
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(1f, 1f, 1f, alpha * 0.85f);
        for (int i = 0; i < ripples; i++) {
            final float a0 = ripPhase + (i / (float) ripples) * (float) Math.PI * 2f;
            final float a1 = a0 + 0.32f;
            final int seg = 8;
            for (int s = 0; s < seg; s++) {
                final float aa = a0 + (a1 - a0) * (s / (float) seg);
                final float ab = a0 + (a1 - a0) * ((s + 1) / (float) seg);
                shapes.line(cx + (float) Math.cos(aa) * radius, cy + (float) Math.sin(aa) * radius,
                            cx + (float) Math.cos(ab) * radius, cy + (float) Math.sin(ab) * radius);
            }
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // 4. Edge sparks — fixed seeded positions, flicker independently
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int sparks = 10;
        for (int i = 0; i < sparks; i++) {
            final float seed = i * 0.371f;
            final float angle = seed * (float) Math.PI * 2f + now * 0.0008f;
            final float flicker = 0.5f + 0.5f * (float) Math.sin(now * 0.012f + seed * 11f);
            if (flicker < 0.55f) continue;
            final float ex = cx + (float) Math.cos(angle) * radius;
            final float ey = cy + (float) Math.sin(angle) * radius;
            shapes.setColor(0.23f, 0.66f, 1.00f, alpha * 0.85f * flicker);
            drawCircle(shapes, ex, ey, 5f, 12);
            shapes.setColor(1f, 1f, 1f, alpha * flicker);
            drawCircle(shapes, ex, ey, 2f, 8);
        }
        shapes.end();
        // 5. Cast-moment punch
        if (t < 0.15f) {
            final float flashA = 1.0f - t / 0.15f;
            shapes.begin(ShapeRenderer.ShapeType.Line);
            Gdx.gl.glLineWidth(8f);
            shapes.setColor(1f, 1f, 1f, flashA * 0.9f);
            drawCircleOutline(shapes, cx, cy, radius, 64);
            Gdx.gl.glLineWidth(1f);
            shapes.end();
        }
    }

    /**
     * Wizard Burst — arcane release: filled magic-circle floor, two
     * expanding wave-rings beyond the burst, two main runic rings, glyph
     * hexagram (rotating Star of David), 6 orbiting rune diamonds, radial
     * spokes that fade, sparkle convergence (first 30%), bright cast
     * flash. Procedural port of renderer.js case 10.
     */
    private void renderWizardBurst(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        final float burstR = radius * (0.5f + t * 0.6f);
        // Floor
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1.00f, 0.55f, 0.10f, alpha * 0.18f);
        drawCircle(shapes, cx, cy, burstR, 48);
        shapes.end();
        // Expanding wave rings
        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (int w = 0; w < 2; w++) {
            final float waveDelay = w * 0.20f;
            final float wt = Math.max(0f, (t - waveDelay)) / Math.max(0.001f, 1f - waveDelay);
            if (wt <= 0f || wt >= 1f) continue;
            final float wR = burstR * (1.0f + wt * 0.9f);
            final float wA = alpha * (1f - wt) * 0.75f;
            Gdx.gl.glLineWidth(2.5f);
            shapes.setColor(1.00f, 0.55f, 0.10f, wA);
            drawCircleOutline(shapes, cx, cy, wR, 64);
            Gdx.gl.glLineWidth(1.5f);
            shapes.setColor(1f, 1f, 1f, wA * 0.6f);
            drawCircleOutline(shapes, cx, cy, wR * 0.97f, 64);
        }
        // Two main runic rings
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(1.00f, 0.55f, 0.10f, alpha * 0.85f);
        drawCircleOutline(shapes, cx, cy, burstR, 64);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1f, 1f, 1f, alpha * 0.85f);
        drawCircleOutline(shapes, cx, cy, burstR * 0.78f, 64);
        // Glyph hexagram — two interlocking triangles
        final float glyphR = burstR * 0.55f;
        final float rot = now * 0.003f;
        shapes.setColor(1.00f, 0.55f, 0.10f, alpha * 0.75f);
        Gdx.gl.glLineWidth(2f);
        // Upright triangle
        final float u0x = cx + (float) Math.cos(rot - (float) Math.PI / 2f) * glyphR;
        final float u0y = cy + (float) Math.sin(rot - (float) Math.PI / 2f) * glyphR;
        final float u1x = cx + (float) Math.cos(rot + (float) Math.PI / 6f) * glyphR;
        final float u1y = cy + (float) Math.sin(rot + (float) Math.PI / 6f) * glyphR;
        final float u2x = cx + (float) Math.cos(rot + 5f * (float) Math.PI / 6f) * glyphR;
        final float u2y = cy + (float) Math.sin(rot + 5f * (float) Math.PI / 6f) * glyphR;
        shapes.line(u0x, u0y, u1x, u1y);
        shapes.line(u1x, u1y, u2x, u2y);
        shapes.line(u2x, u2y, u0x, u0y);
        // Inverted triangle
        final float i0x = cx + (float) Math.cos(rot + (float) Math.PI / 2f) * glyphR;
        final float i0y = cy + (float) Math.sin(rot + (float) Math.PI / 2f) * glyphR;
        final float i1x = cx + (float) Math.cos(rot - (float) Math.PI / 6f) * glyphR;
        final float i1y = cy + (float) Math.sin(rot - (float) Math.PI / 6f) * glyphR;
        final float i2x = cx + (float) Math.cos(rot + 7f * (float) Math.PI / 6f) * glyphR;
        final float i2y = cy + (float) Math.sin(rot + 7f * (float) Math.PI / 6f) * glyphR;
        shapes.line(i0x, i0y, i1x, i1y);
        shapes.line(i1x, i1y, i2x, i2y);
        shapes.line(i2x, i2y, i0x, i0y);
        // Radial spokes that fade
        final float spokeA = alpha * (1.0f - t * 0.7f);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1f, 1f, 1f, spokeA);
        for (int i = 0; i < 8; i++) {
            final float a = (i / 8f) * (float) Math.PI * 2f;
            final float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            shapes.line(cx + ca * burstR * 0.2f, cy + sa * burstR * 0.2f,
                        cx + ca * burstR * 0.95f, cy + sa * burstR * 0.95f);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // Six rune-points orbiting (filled diamonds)
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int runes = 6;
        for (int i = 0; i < runes; i++) {
            final float a = (i / (float) runes) * (float) Math.PI * 2f + now * 0.006f;
            final float px = cx + (float) Math.cos(a) * burstR;
            final float py = cy + (float) Math.sin(a) * burstR;
            shapes.setColor(1.00f, 0.55f, 0.10f, alpha * 0.55f);
            drawCircle(shapes, px, py, 8f, 14);
            shapes.setColor(1f, 1f, 1f, alpha * 0.95f);
            // Diamond as two triangles
            shapes.triangle(px, py - 5f, px + 4f, py, px, py + 5f);
            shapes.triangle(px, py - 5f, px, py + 5f, px - 4f, py);
        }
        // Sparkle convergence (first 30%)
        if (t < 0.30f) {
            final float gt = t / 0.30f;
            final float eased = 1f - (float) Math.pow(1f - gt, 2);
            for (int i = 0; i < 8; i++) {
                final float a = (i / 8f) * (float) Math.PI * 2f;
                final float dist = burstR * 1.4f * (1f - eased);
                final float px = cx + (float) Math.cos(a) * dist;
                final float py = cy + (float) Math.sin(a) * dist;
                shapes.setColor(1f, 1f, 1f, alpha * 0.9f);
                drawCircle(shapes, px, py, 2f + (1f - eased) * 2f, 10);
                shapes.setColor(1.00f, 0.55f, 0.10f, alpha * 0.55f);
                drawCircle(shapes, px, py, 5f + (1f - eased) * 3f, 12);
            }
        }
        // Initial flash
        if (t < 0.25f) {
            final float flashA = 1.0f - t / 0.25f;
            shapes.setColor(1f, 1f, 1f, flashA * 0.85f);
            drawCircle(shapes, cx, cy, burstR * 0.5f, 32);
            shapes.setColor(1.00f, 0.55f, 0.10f, flashA * 0.65f);
            drawCircle(shapes, cx, cy, burstR * 0.75f, 32);
        }
        // Pulsing core
        shapes.setColor(1.00f, 0.55f, 0.10f, alpha * 0.75f);
        drawCircle(shapes, cx, cy, burstR * 0.18f, 14);
        shapes.end();
    }

    /**
     * Paladin Seal — vertical pillar of light + radiant gold cross at the
     * caster + rotating halo with 12 sun-rays + ascending divine motes +
     * cast-moment consecration flash. Procedural port of renderer.js
     * case 14. (Native Y-up: pillar extends +y above caster.)
     */
    private void renderPaladinSeal(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        final float baseR = radius * (0.55f + 0.45f * t);
        final float pillarH = baseR * 2.4f;
        final float pillarW = baseR * 0.55f;
        // Pillar (Y-up: pillar rises upward = positive Y above caster)
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1.00f, 0.85f, 0.35f, alpha * 0.18f);
        shapes.rect(cx - pillarW, cy, pillarW * 2f, pillarH);
        shapes.setColor(1.00f, 0.94f, 0.63f, alpha * 0.30f);
        shapes.rect(cx - pillarW * 0.55f, cy, pillarW * 1.1f, pillarH * 0.95f);
        shapes.setColor(1f, 1f, 1f, alpha * 0.45f);
        shapes.rect(cx - pillarW * 0.20f, cy, pillarW * 0.4f, pillarH * 0.92f);
        // Halo behind the cross (above caster)
        final float haloR = baseR * 0.78f;
        final float crossCy = cy + baseR * 0.15f;
        shapes.setColor(1.00f, 0.85f, 0.35f, alpha * 0.22f);
        drawCircle(shapes, cx, crossCy, haloR, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(1.00f, 0.88f, 0.44f, alpha * 0.85f);
        drawCircleOutline(shapes, cx, crossCy, haloR, 48);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1f, 1f, 1f, alpha * 0.6f);
        drawCircleOutline(shapes, cx, crossCy, haloR * 0.92f, 48);
        // 12 sun-rays
        final int spokes = 12;
        final float spokePulse = 0.8f + 0.2f * (float) Math.sin(now * 0.012f);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1.00f, 0.94f, 0.63f, alpha * 0.7f * spokePulse);
        for (int i = 0; i < spokes; i++) {
            final float a = (i / (float) spokes) * (float) Math.PI * 2f + now * 0.0015f;
            final float inner = haloR * 0.95f;
            final float outer = haloR * (1.15f + 0.08f * (float) Math.sin(now * 0.008f + i));
            shapes.line(cx + (float) Math.cos(a) * inner, crossCy + (float) Math.sin(a) * inner,
                        cx + (float) Math.cos(a) * outer, crossCy + (float) Math.sin(a) * outer);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // The cross (vertical + horizontal beams as rects)
        final float vH = haloR * 1.55f;
        final float vW = haloR * 0.18f;
        final float hH = haloR * 0.18f;
        final float hW = haloR * 1.05f;
        final float hOff = vH * 0.12f;  // horizontal sits slightly above center (Y-up = +)
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        // Outer glow
        shapes.setColor(1.00f, 0.85f, 0.35f, alpha * 0.55f);
        shapes.rect(cx - vW * 1.5f, crossCy - vH * 0.55f, vW * 3f, vH * 1.1f);
        shapes.rect(cx - hW, crossCy + hOff - hH * 1.5f, hW * 2f, hH * 3f);
        // Warm gold
        shapes.setColor(1.00f, 0.94f, 0.63f, alpha * 0.85f);
        shapes.rect(cx - vW, crossCy - vH * 0.5f, vW * 2f, vH);
        shapes.rect(cx - hW * 0.95f, crossCy + hOff - hH, hW * 1.9f, hH * 2f);
        // White core
        shapes.setColor(1f, 1f, 1f, Math.min(1f, alpha));
        shapes.rect(cx - vW * 0.45f, crossCy - vH * 0.5f, vW * 0.9f, vH);
        shapes.rect(cx - hW * 0.92f, crossCy + hOff - hH * 0.45f, hW * 1.84f, hH * 0.9f);
        // Cross-arm endpoint flares
        final float flareR = 4f + 2f * spokePulse;
        shapes.setColor(1f, 1f, 1f, alpha * 0.9f);
        drawCircle(shapes, cx, crossCy - vH * 0.5f, flareR, 12);
        drawCircle(shapes, cx, crossCy + vH * 0.5f, flareR, 12);
        drawCircle(shapes, cx - hW * 0.95f, crossCy + hOff, flareR, 12);
        drawCircle(shapes, cx + hW * 0.95f, crossCy + hOff, flareR, 12);
        shapes.setColor(1.00f, 0.85f, 0.35f, alpha * 0.5f);
        drawCircle(shapes, cx, crossCy - vH * 0.5f, flareR * 1.8f, 14);
        drawCircle(shapes, cx, crossCy + vH * 0.5f, flareR * 1.8f, 14);
        drawCircle(shapes, cx - hW * 0.95f, crossCy + hOff, flareR * 1.8f, 14);
        drawCircle(shapes, cx + hW * 0.95f, crossCy + hOff, flareR * 1.8f, 14);
        // Ascending motes (web: rises from ground upward; Y-up: same direction)
        final int motes = 14;
        for (int i = 0; i < motes; i++) {
            final float seed = i * 0.61f;
            final float phase = (t + seed) % 1.0f;
            final float moteA = (float) Math.sin(phase * (float) Math.PI) * alpha;
            if (moteA <= 0.05f) continue;
            final float dx = (float) Math.sin(seed * 7f + now * 0.001f) * baseR * 0.5f;
            // Y-up: motes rise upward as phase increases
            final float my = cy - baseR * 0.6f + phase * pillarH * 1.05f;
            final float mx = cx + dx;
            shapes.setColor(1.00f, 0.94f, 0.63f, moteA * 0.5f);
            drawCircle(shapes, mx, my, 5f, 12);
            shapes.setColor(1f, 1f, 1f, Math.min(1f, moteA));
            drawCircle(shapes, mx, my, 2.5f, 10);
        }
        // Initial consecration flash
        if (t < 0.18f) {
            final float flashA = 1.0f - t / 0.18f;
            shapes.setColor(1f, 1f, 1f, flashA * 0.95f);
            drawCircle(shapes, cx, cy, baseR * 0.55f, 32);
            shapes.setColor(1.00f, 0.94f, 0.63f, flashA * 0.7f);
            drawCircle(shapes, cx, cy, baseR * 0.85f, 32);
        }
        shapes.end();
    }

    /**
     * Warrior Buff — gritty battle rally: smoke haze + jagged 16-segment
     * shockwave ring + crossed war-blades raised high + 8 outward chevrons
     * + 18 ember motes + cast-moment roar flash + pulsing core.
     * Procedural port of renderer.js case 12.
     */
    private void renderWarriorBuff(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final float earlyA = t < 0.35f ? alpha : alpha * (1.0f - (t - 0.35f) / 0.65f);
        final long now = System.currentTimeMillis();
        final float buffR = radius * (0.5f + t * 0.55f);
        // 1. Smoke haze
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.33f, 0.20f, 0.13f, alpha * 0.22f);
        drawCircle(shapes, cx, cy, buffR * 1.1f, 48);
        shapes.end();
        // 2. Jagged 16-segment shockwave ring
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(5f);
        shapes.setColor(1.00f, 0.65f, 0.20f, alpha * 0.85f);
        final int jagSegs = 16;
        for (int i = 0; i < jagSegs; i++) {
            final float a0 = (i / (float) jagSegs) * (float) Math.PI * 2f;
            final float a1 = ((i + 1) / (float) jagSegs) * (float) Math.PI * 2f;
            final float r0 = buffR * (0.92f + 0.08f * (float) Math.sin(i * 5.7f + now * 0.005f));
            final float r1 = buffR * (0.92f + 0.08f * (float) Math.sin((i + 1) * 5.7f + now * 0.005f));
            shapes.line(cx + (float) Math.cos(a0) * r0, cy + (float) Math.sin(a0) * r0,
                        cx + (float) Math.cos(a1) * r1, cy + (float) Math.sin(a1) * r1);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // 3. Crossed war-blades — two diagonal stretched diamonds
        final float bladeAngle1 = -(float) Math.PI / 4f + (float) Math.sin(now * 0.004f) * 0.06f;
        final float bladeAngle2 = -(float) Math.PI * 3f / 4f - (float) Math.sin(now * 0.004f) * 0.06f;
        final float bladeLen = buffR * 0.55f;
        final float bladeWid = 6f;
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int blade = 0; blade < 2; blade++) {
            final float ang = blade == 0 ? bladeAngle1 : bladeAngle2;
            final float cs = (float) Math.cos(ang), sn = (float) Math.sin(ang);
            // Outer warm glow (orange-red)
            shapes.setColor(1.00f, 0.50f, 0.19f, alpha * 0.55f);
            final float bw = bladeWid + 3f;
            // Diamond split into two triangles: (tip, side1, tail) + (tip, tail, side2)
            shapes.triangle(cx + bladeLen * 1.1f * cs, cy + bladeLen * 1.1f * sn,
                            cx - bw * sn,              cy + bw * cs,
                            cx - bladeLen * 0.55f * cs, cy - bladeLen * 0.55f * sn);
            shapes.triangle(cx + bladeLen * 1.1f * cs, cy + bladeLen * 1.1f * sn,
                            cx - bladeLen * 0.55f * cs, cy - bladeLen * 0.55f * sn,
                            cx + bw * sn,              cy - bw * cs);
            // Steel-bright core
            shapes.setColor(1f, 1f, 1f, alpha * 0.95f);
            shapes.triangle(cx + bladeLen * cs,        cy + bladeLen * sn,
                            cx - bladeWid * sn,        cy + bladeWid * cs,
                            cx - bladeLen * 0.5f * cs, cy - bladeLen * 0.5f * sn);
            shapes.triangle(cx + bladeLen * cs,        cy + bladeLen * sn,
                            cx - bladeLen * 0.5f * cs, cy - bladeLen * 0.5f * sn,
                            cx + bladeWid * sn,        cy - bladeWid * cs);
        }
        shapes.end();
        // 4. Outward war-cry chevrons
        shapes.begin(ShapeRenderer.ShapeType.Line);
        final int chevs = 8;
        for (int i = 0; i < chevs; i++) {
            final float a = (i / (float) chevs) * (float) Math.PI * 2f + now * 0.005f;
            final float kx = cx + (float) Math.cos(a) * buffR * 0.78f;
            final float ky = cy + (float) Math.sin(a) * buffR * 0.78f;
            final float ox = (float) Math.cos(a), oy = (float) Math.sin(a);
            final float tx = -oy, ty = ox;
            Gdx.gl.glLineWidth(4f);
            shapes.setColor(1.00f, 0.65f, 0.20f, alpha * 0.85f);
            shapes.line(kx - tx * 8f - ox * 4f, ky - ty * 8f - oy * 4f, kx + ox * 9f, ky + oy * 9f);
            shapes.line(kx + ox * 9f, ky + oy * 9f, kx + tx * 8f - ox * 4f, ky + ty * 8f - oy * 4f);
            Gdx.gl.glLineWidth(2f);
            shapes.setColor(1.00f, 0.88f, 0.75f, alpha * 0.95f);
            shapes.line(kx - tx * 8f - ox * 4f, ky - ty * 8f - oy * 4f, kx + ox * 9f, ky + oy * 9f);
            shapes.line(kx + ox * 9f, ky + oy * 9f, kx + tx * 8f - ox * 4f, ky + ty * 8f - oy * 4f);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // 5. Ember motes (18 little square dust particles)
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int embers = 18;
        for (int i = 0; i < embers; i++) {
            final float seed = i * 0.91f;
            final float a = (seed * 6.28f) + now * 0.004f;
            final float dist = buffR * (0.85f + 0.20f * (float) Math.sin(now * 0.008f + seed));
            final float ex = cx + (float) Math.cos(a) * dist;
            final float ey = cy + (float) Math.sin(a) * dist;
            final float sz = (i & 1) != 0 ? 3f : 2f;
            shapes.setColor(1.00f, 0.38f, 0.13f, alpha * 0.85f);
            shapes.rect(ex - sz, ey - sz, sz * 2f, sz * 2f);
            shapes.setColor(0.53f, 0.27f, 0.00f, alpha * 0.55f);
            shapes.rect(ex - sz - 1f, ey - sz - 1f, sz * 2f + 2f, sz * 2f + 2f);
        }
        // 6. Initial roar flash
        if (t < 0.18f) {
            final float flashA = 1.0f - t / 0.18f;
            shapes.setColor(1.00f, 0.88f, 0.75f, flashA * 0.95f);
            drawCircle(shapes, cx, cy, buffR * 0.28f, 28);
            shapes.setColor(1.00f, 0.50f, 0.19f, flashA * 0.7f);
            drawCircle(shapes, cx, cy, buffR * 0.5f, 32);
        }
        // 7. Throbbing core
        final float corePulse = 0.6f + 0.4f * (float) Math.sin(now * 0.022f);
        shapes.setColor(1.00f, 0.65f, 0.20f, earlyA * 0.65f * corePulse);
        drawCircle(shapes, cx, cy, buffR * 0.22f, 24);
        shapes.end();
    }

    /**
     * Necromancer Soul Harvest visual — persistent crimson/violet vortex
     * with three inward-spiraling arms + drifting motes + bright core.
     * Driven by wall-clock so consecutive refresh packets stay phase-
     * continuous (no resetting on each server pulse).
     */
    private void renderSoulVortex(ShapeRenderer shapes, ActiveVisualEffect vfx,
                                   float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        // Ground halo + outer boundary ring
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.13f, 0.03f, 0.10f, alpha * 0.55f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.setColor(0.50f, 0.19f, 0.75f, alpha * 0.25f);
        drawCircle(shapes, cx, cy, radius * 0.92f, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(0.75f, 0.06f, 0.25f, alpha * 0.9f);
        drawCircleOutline(shapes, cx, cy, radius, 64);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(0.50f, 0.19f, 0.75f, alpha * 0.85f);
        drawCircleOutline(shapes, cx, cy, radius * 0.78f, 64);
        // Three inward spiraling arms — chained line segments rotated by
        // wall-clock so the whole vortex churns.
        final int arms = 3;
        final int segs = 24;
        final float rotSpeed = 0.006f;
        for (int arm = 0; arm < arms; arm++) {
            final float armOff = (arm / (float) arms) * (float) Math.PI * 2f;
            shapes.setColor(1.0f, 0.5f, 1.0f, alpha * 0.95f);
            float prevX = cx, prevY = cy;
            for (int s = 0; s <= segs; s++) {
                final float tt = s / (float) segs;
                final float rr = radius * (1f - tt * 0.95f) + 2f;
                final float a = armOff + tt * (float) Math.PI * 2.8f + now * rotSpeed;
                final float px = cx + (float) Math.cos(a) * rr;
                final float py = cy + (float) Math.sin(a) * rr;
                if (s > 0) shapes.line(prevX, prevY, px, py);
                prevX = px; prevY = py;
            }
        }
        shapes.end();
        // Drifting soul motes — orbiting wisps
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int motes = 14;
        for (int i = 0; i < motes; i++) {
            final float seed = i * 0.421f + 0.137f;
            final float phase = ((now * 0.0012f + seed) % 1.0f);
            final float moteA = (float) Math.sin(phase * Math.PI) * alpha;
            if (moteA <= 0.05f) continue;
            final float orbA = seed * (float) Math.PI * 2f + now * 0.004f + phase * 2f;
            final float orbR = radius * (0.25f + 0.65f * phase);
            final float mx = cx + (float) Math.cos(orbA) * orbR;
            final float my = cy + (float) Math.sin(orbA) * orbR;
            shapes.setColor(1.0f, 0.5f, 1.0f, moteA * 0.9f);
            shapes.rect(mx - 2f, my - 2f, 4f, 4f);
        }
        // Bright core — the sink everything spirals into
        shapes.setColor(0.75f, 0.06f, 0.25f, alpha * 0.8f);
        drawCircle(shapes, cx, cy, 9f, 18);
        shapes.setColor(1.0f, 0.5f, 1.0f, alpha);
        drawCircle(shapes, cx, cy, 4f, 16);
        shapes.end();
        Gdx.gl.glLineWidth(1f);
    }

    /**
     * Procedural water fountain — ring of streams continuously launching
     * droplets up and out from (cx, cy), each following the same parabolic
     * arc the assassin's poison throw uses, landing inside `radius` and
     * splashing on impact. Uses the effect's own elapsed-ms clock so the
     * animation stays smooth across heal-tick packet boundaries.
     */
    private void renderWaterFountain(ShapeRenderer shapes, ActiveVisualEffect vfx,
                                     float cx, float cy, float radius) {
        if (radius <= 0) return;

        // Continuous timeline (seconds). Looping the fountain off elapsed —
        // not the normalized t — keeps adjacent packets phase-continuous so
        // overlapping heal-tick packets read as one stream rather than
        // resetting on each tick.
        final float elapsedSec = vfx.getElapsed() / 1000f;
        final float dropPeriod = 0.85f;     // seconds per droplet (launch -> land)
        final int streams = 14;             // number of staggered launchers around the ring

        // Overall fade so the visual eases out at the end of the packet's
        // lifetime instead of popping. Because consecutive heal ticks send
        // overlapping packets, the visible stream stays continuous.
        final float t = vfx.getProgress();
        final float globalAlpha = t < 0.85f ? 1.0f : Math.max(0f, 1.0f - (t - 0.85f) * 6.7f);

        shapes.begin(ShapeRenderer.ShapeType.Filled);

        // Soft pool reflection at the base (gives the fountain a "wet" anchor
        // even if the mapper hasn't placed water tiles yet).
        shapes.setColor(0.20f, 0.45f, 0.75f, 0.18f * globalAlpha);
        drawCircle(shapes, cx, cy, radius, 32);
        shapes.setColor(0.35f, 0.65f, 0.95f, 0.10f * globalAlpha);
        drawCircle(shapes, cx, cy, radius * 0.82f, 28);

        for (int s = 0; s < streams; s++) {
            // Per-stream deterministic randomness so each launcher has its
            // own angle / landing distance / phase but the look stays stable.
            float r1 = pseudoRand(s * 73 + 11);
            float r2 = pseudoRand(s * 131 + 29);
            float r3 = pseudoRand(s * 197 + 53);

            // Each stream slowly orbits so the fountain doesn't read as
            // 14 fixed jets — gives a subtle organic motion.
            float baseAngle = (float) (s * Math.PI * 2 / streams)
                    + elapsedSec * 0.35f
                    + r1 * (float) Math.PI * 2;
            // Landing distance: 55–100% of radius so droplets fill the pool
            // without all bunching at the rim.
            float landDist = radius * (0.55f + 0.45f * r2);

            float landX = cx + (float) Math.cos(baseAngle) * landDist;
            float landY = cy + (float) Math.sin(baseAngle) * landDist;

            // Phase in [0,1) — stream s lags by s/streams of the period plus
            // its own random jitter so launches don't all line up.
            float phase = ((elapsedSec / dropPeriod) + (s + r3) / streams) % 1.0f;
            if (phase < 0) phase += 1.0f;

            if (phase < 0.78f) {
                // Droplet in flight: parabola from (cx,cy) to (landX,landY)
                // with peak height ≈ 60% of the ground distance, matching the
                // poison throw's lob feel.
                float f = phase / 0.78f;
                float arcHeight = landDist * 0.65f + radius * 0.10f;
                float dx = landX - cx;
                float dy = landY - cy;
                float px = cx + dx * f;
                float py = cy + dy * f - 4.0f * arcHeight * f * (1.0f - f);

                // Short trailing tail (3 segments behind the head)
                int tailSegs = 3;
                for (int k = 1; k <= tailSegs; k++) {
                    float fk = Math.max(0f, f - 0.06f * k);
                    float tx = cx + dx * fk;
                    float ty = cy + dy * fk - 4.0f * arcHeight * fk * (1.0f - fk);
                    float tailA = globalAlpha * 0.32f * (1.0f - (float) k / tailSegs);
                    shapes.setColor(0.55f, 0.78f, 1.0f, tailA);
                    shapes.rect(tx - 1.5f, ty - 1.5f, 3f, 3f);
                }

                // Droplet head — outer halo + bright core.
                shapes.setColor(0.40f, 0.70f, 1.0f, globalAlpha * 0.55f);
                drawCircle(shapes, px, py, 3.2f, 10);
                shapes.setColor(0.85f, 0.95f, 1.0f, globalAlpha * 0.95f);
                drawCircle(shapes, px, py, 1.6f, 8);
            } else {
                // Splash ripple at the landing point. Phase 0.78–1.0 covers
                // ~22% of the period (~190 ms), enough to read as an impact
                // without lingering past the next launch.
                float sf = (phase - 0.78f) / 0.22f;            // 0..1 splash progress
                float splashR = 2.0f + 7.5f * sf;
                float splashA = globalAlpha * (1.0f - sf) * 0.85f;
                // Soft outer halo (filled, low alpha) — staying in Filled
                // mode for the whole fountain pass keeps batches simple and
                // avoids per-droplet begin/end churn.
                shapes.setColor(0.40f, 0.70f, 1.0f, splashA * 0.40f);
                drawCircle(shapes, landX, landY, splashR, 14);
                // Bright center splat fading fast
                shapes.setColor(0.85f, 0.95f, 1.0f, splashA);
                drawCircle(shapes, landX, landY, Math.max(0.5f, 2.2f * (1.0f - sf)), 8);
                // Two small side flecks kicked outward by the impact
                float flAng = baseAngle + (r1 - 0.5f) * 1.2f;
                float flDist = splashR * 0.9f;
                float fx = landX + (float) Math.cos(flAng) * flDist;
                float fy = landY + (float) Math.sin(flAng) * flDist;
                shapes.setColor(0.70f, 0.88f, 1.0f, splashA * 0.7f);
                shapes.rect(fx - 1f, fy - 1f, 2f, 2f);
            }
        }

        // Bright core at the statue base — the "spout" the fountain emerges
        // from. Subtly pulses so the source itself looks alive.
        float pulse = 0.85f + 0.15f * (float) Math.sin(elapsedSec * Math.PI * 4);
        shapes.setColor(0.85f, 0.95f, 1.0f, globalAlpha * 0.55f * pulse);
        drawCircle(shapes, cx, cy, 4.0f * pulse, 12);
        shapes.setColor(1.0f, 1.0f, 1.0f, globalAlpha * 0.85f * pulse);
        drawCircle(shapes, cx, cy, 1.8f, 8);

        shapes.end();
    }

    /** Cheap deterministic [0,1) hash — no allocations, suitable per-frame. */
    private static float pseudoRand(int seed) {
        int x = seed;
        x = (x ^ 61) ^ (x >>> 16);
        x = x + (x << 3);
        x = x ^ (x >>> 4);
        x = x * 0x27d4eb2d;
        x = x ^ (x >>> 15);
        // Map to [0,1)
        return ((x & 0x7fffffff) % 1000003) / 1000003f;
    }

    /** Draw a filled circle using triangles (ShapeRenderer.Filled mode must be active) */
    private static void drawCircle(ShapeRenderer shapes, float cx, float cy, float radius, int segments) {
        for (int i = 0; i < segments; i++) {
            float a1 = (float) (i * Math.PI * 2 / segments);
            float a2 = (float) ((i + 1) * Math.PI * 2 / segments);
            shapes.triangle(cx, cy,
                    cx + (float) Math.cos(a1) * radius, cy + (float) Math.sin(a1) * radius,
                    cx + (float) Math.cos(a2) * radius, cy + (float) Math.sin(a2) * radius);
        }
    }

    /** Draw a circle outline (ShapeRenderer.Line mode must be active) */
    private static void drawCircleOutline(ShapeRenderer shapes, float cx, float cy, float radius, int segments) {
        for (int i = 0; i < segments; i++) {
            float a1 = (float) (i * Math.PI * 2 / segments);
            float a2 = (float) ((i + 1) * Math.PI * 2 / segments);
            shapes.line(
                    cx + (float) Math.cos(a1) * radius, cy + (float) Math.sin(a1) * radius,
                    cx + (float) Math.cos(a2) * radius, cy + (float) Math.sin(a2) * radius);
        }
    }

}
