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
        PendingInput(int seq, float vx, float vy, float pxPerTick) {
            this.seq = seq; this.vx = vx; this.vy = vy; this.pxPerTick = pxPerTick;
        }
    }

    /** Visual-only smoothing offset applied to the local player's render
     *  position when reconciliation finds a small mismatch (collision /
     *  slow-tile divergence). The logical pos is snapped to the replay
     *  result for accurate next-tick collisions, while the visual diff
     *  decays toward zero each frame so the user doesn't see a hop. */
    private float smoothingOffsetX = 0f;
    private float smoothingOffsetY = 0f;

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
        if (p.hasEffect(StatusEffectType.PARALYZED)) {
            p.setDx(0);
            p.setDy(0);
        }
        // Reuse a single scratch vector for the center-offset queries
        // instead of pos.clone(...) — those clone calls were the largest
        // single allocation source on the per-frame other-player movement
        // path (~3K Vector2f / sec on a populated realm).
        final Vector2f scratch = this.movePlayerScratch;
        final float halfSize = p.getSize() / 2f;
        scratch.x = p.getPos().x + halfSize;
        scratch.y = p.getPos().y + halfSize;
        if (!this.getRealmManager().getRealm().getTileManager().collisionTile(p, p.getDx(), 0)
                && !this.getRealmManager().getRealm().getTileManager().collidesXLimit(p, p.getDx())
                && !this.getRealmManager().getRealm().getTileManager().isVoidTile(scratch, p.getDx(), 0)) {
            p.xCol = false;
            if (p.getDx() != 0.0f) {
                if (this.getRealmManager().getRealm().getTileManager().collidesSlowTile(p)) {
                    p.getPos().x += p.getDx() / 3.0f;
                } else {
                    p.getPos().x += p.getDx();
                }
            }
        } else {
            p.xCol = true;
        }

        // Refresh the scratch vector after the X-axis update — pos may have
        // moved.
        scratch.x = p.getPos().x + halfSize;
        scratch.y = p.getPos().y + halfSize;
        if (!this.getRealmManager().getRealm().getTileManager().collisionTile(p, 0, p.getDy())
                && !this.getRealmManager().getRealm().getTileManager().collidesYLimit(p, p.getDy())
                && !this.getRealmManager().getRealm().getTileManager().isVoidTile(scratch, 0, p.getDy())) {
            p.yCol = false;
            if (p.getDy() != 0.0f) {
                if (this.getRealmManager().getRealm().getTileManager().collidesSlowTile(p)) {
                    p.getPos().y += p.getDy() / 3.0f;
                } else {
                    p.getPos().y += p.getDy();
                }
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

        // Step 4: replay remaining unacked inputs.
        synchronized (this.pendingInputs) {
            for (final PendingInput input : this.pendingInputs) {
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
            this.smoothingOffsetX += savedX - replayX;
            this.smoothingOffsetY += savedY - replayY;
            // Cap the smoothing offset so a long burst of corrections
            // can't accumulate into a visible rubber-band.
            final float magSq = this.smoothingOffsetX * this.smoothingOffsetX
                    + this.smoothingOffsetY * this.smoothingOffsetY;
            if (magSq > 36f /* 6 px cap */) {
                final float mag = (float) Math.sqrt(magSq);
                final float scale = 6f / mag;
                this.smoothingOffsetX *= scale;
                this.smoothingOffsetY *= scale;
            }
        } else {
            // Agreement — revert to the saved pos so there's zero visible
            // change. This is the common case when ping is stable and
            // physics lines up; without it the render would briefly show
            // the saved pos shifted by sub-pixel rounding noise.
            local.getPos().x = savedX;
            local.getPos().y = savedY;
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
            damage = (short) (damage + player.getStats().getAtt());
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
            damage = (short) (damage + player.getStats().getAtt());
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
        final short atkBonus = (short) player.getStats().getAtt();
        final Realm realm = this.realmManager.getRealm();

        // MultiShot / extra-projectile gems: count PROJECTILE_COUNT (effectType=2)
        // enchantments on the equipped weapon. Server applies a centered fan in
        // ServerGameLogic; mirror that here so the predicted shot lands at the
        // SAME angle as the server-side bullet — otherwise findMatchingPredictedBullet
        // pairs the cursor-aligned predicted bullet with one of the off-center
        // server bullets, leaving the player visibly out of the spread center.
        int extraProjectiles = 0;
        if (weapon.getEnchantments() != null) {
            for (com.openrealm.game.entity.item.Enchantment e : weapon.getEnchantments()) {
                // PROJECTILE_COUNT == 2; also accept legacy enchants where
                // effectType wasn't persisted (defaulted to 0) but param1
                // happened to encode the project-count flag — defensive in
                // case some MongoDB rows pre-date the effectType column.
                if (e.getEffectType() == 2) {
                    extraProjectiles += e.getMagnitude();
                }
            }
        }
        final int totalBullets = 1 + extraProjectiles;
        final float SPREAD = 0.12f;
        log.info("[shoot-predict] weapon='{}' projGroupId={} extraProjectiles={} totalBullets={} enchants={}",
                weapon.getName(), projGroupId, extraProjectiles, totalBullets,
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
                final Bullet b = new Bullet(Realm.RANDOM.nextLong(), projGroupId, spawnPos,
                        shootAngle + deltaA, proj.getSize(), proj.getMagnitude(), proj.getRange(),
                        rolledDamage, false);
                b.setSrcEntityId(player.getId());
                b.setAmplitude(proj.getAmplitude());
                b.setFrequency(proj.getFrequency());
                // Carry the projectile's behavior flags so dedup + hit
                // prediction (PLAYER_PROJECTILE / PARAMETRIC / ORBITAL etc.)
                // see the same trajectory as the server-side bullet.
                if (proj.getFlags() != null) {
                    b.setFlags(new ArrayList<>(proj.getFlags()));
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
                float tilesPerSec = 4.0f + 5.6f * (player.getComputedStats().getSpd() / 75.0f);
                if (player.hasEffect(StatusEffectType.SPEEDY)) tilesPerSec *= 1.5f;
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
                    // by exactly one tick worth.
                    player.setDx(vx * pxPerTick);
                    player.setDy(vy * pxPerTick);
                    this.movePlayer(player);

                    // Buffer this input for reconciliation replay. Capture
                    // pxPerTick so the replay uses the EXACT step magnitude
                    // that was applied originally — spd stat or SPEEDY effect
                    // can change between now and ack arrival, and we want
                    // the replay to reproduce what the simulation actually
                    // did, not what it would do today.
                    synchronized (this.pendingInputs) {
                        this.pendingInputs.addLast(new PendingInput(seq, vx, vy, pxPerTick));
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
                                    this.realmManager.getRealm().getRealmId(), this.getPlayerId());
                            this.realmManager.getClient().sendRemote(usePortal);
                            this.realmManager.getRealm().loadMap(1);
                        } else {
                            UsePortalPacket usePortal = UsePortalPacket.from(closestPortal.getId(),
                                    this.realmManager.getRealm().getRealmId(), this.getPlayerId());
                            this.realmManager.getClient().sendRemote(usePortal);
                            this.realmManager.getRealm().loadMap(portalModel.getMapId());
                        }
                        // Flag that we're transitioning realms - next ObjectMovePacket should snap position
                        this.realmManager.setAwaitingRealmTransition(true);
                        // Tell server we're ready for tiles after map rebuild
                        this.realmManager.getClient().sendRemote(LoginAckPacket.from(this.getPlayerId()));
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
                            this.realmManager.getRealm().getRealmId(), this.getPlayerId());
                    this.realmManager.getClient().sendRemote(usePortal);
                    this.realmManager.getRealm().loadMap(29);
                    this.realmManager.setAwaitingRealmTransition(true);
                    this.realmManager.getClient().sendRemote(LoginAckPacket.from(this.getPlayerId()));
                    this.lastPortalTick = System.currentTimeMillis();
                } catch (Exception e) {
                    PlayState.log.error("Failed to send Nexus UsePortalPacket", e.getMessage());
                }
            }
            if (key.f1.clicked && canUsePortal) {
                try {
                    if (this.realmManager.getRealm().getMapId() != 1) {
                        UsePortalPacket usePortal = UsePortalPacket.toVault(this.realmManager.getRealm().getRealmId(), this.getPlayerId());
                        this.realmManager.getClient().sendRemote(usePortal);
                        this.realmManager.getRealm().loadMap(1);
                        this.realmManager.setAwaitingRealmTransition(true);
                        this.realmManager.getClient().sendRemote(LoginAckPacket.from(this.getPlayerId()));
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
                        pkt.setPlayerId(this.getPlayerId());
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
		if (player.hasEffect(StatusEffectType.SPEEDY)) {
			dex = dex * 1.5;
		}
        boolean canShoot = (System.currentTimeMillis() - this.lastShotTick) > (1000 / dex + 10);
        boolean canUseAbility = (System.currentTimeMillis() - this.lastAbilityTick) > 1000;
        boolean clickingWorld = mouse.isPressed(1) && (this.pui == null || !this.pui.isHoveringInventory(mouse.getX()));
        // WHY: do NOT call player.setAttacking(clickingWorld) here. That clobbers
        // the timer-driven attack flag and cuts the attack animation the instant
        // the mouse button releases — the webclient instead refreshes a 0.3s
        // shootingAnim timer on every shot fire (main.js ~2205). We do the
        // equivalent below at the actual firing site via triggerAttackAnimation.
        // WHY: store aim in WORLD coordinates (not screen pixels). updateAnimation
        // compares aim against the player's world center to pick attack direction;
        // mixing screen-pixel aim with world-pixel center under WORLD_SCALE=2
        // made the comparison pivot off the wrong origin (cursor at the player's
        // ACTUAL on-screen position registered as offset by half a screen).
        final float aimInvScale = 1f / OpenRealmGame.WORLD_SCALE;
        player.setAimX(mouse.getX() * aimInvScale + PlayState.map.x);
        player.setAimY(mouse.getY() * aimInvScale + PlayState.map.y);
        // Mouse -> world conversion: the world camera is zoomed 2× via
        // OpenRealmGame.WORLD_SCALE, so 1 screen pixel == 1/WORLD_SCALE world
        // pixels. Without dividing here, every shot/ability targets a world
        // point twice as far from the player as the cursor visually points
        // to — the aim drifts further off-target the further from screen
        // center the cursor is.
        final float invScale = 1f / OpenRealmGame.WORLD_SCALE;
        if (clickingWorld && canShoot) {
            this.lastShotTick = System.currentTimeMillis();
            Vector2f dest = new Vector2f(mouse.getX() * invScale, mouse.getY() * invScale);
            dest.addX(PlayState.map.x);
            dest.addY(PlayState.map.y);
            this.shotDestQueue.add(dest);
            // Webclient parity: each shot refreshes the attack animation hold
            // so the local player keeps cycling attack frames between rapid
            // shots and for ~350ms after the last one.
            player.triggerAttackAnimation();
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
                    Vector2f pos = new Vector2f(mouse.getX() * invScale, mouse.getY() * invScale);
                    pos.addX(PlayState.map.x);
                    pos.addY(PlayState.map.y);
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
            game.getWorldCamera().update();
            batch.setProjectionMatrix(game.getWorldCamera().combined);
            shapes.setProjectionMatrix(game.getWorldCamera().combined);
        }
        this.realmManager.getRealm().getTileManager().render(player, batch, shapes);

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

        for (Player p : this.realmManager.getRealm().getPlayers().values()) {
            visibleEntities.add(p);
            p.updateAnimation();
        }

        for (int i = 0; i < gameObject.length; i++) {
            if (gameObject[i] instanceof Enemy) {
                Enemy e = (Enemy) gameObject[i];
                visibleEntities.add(e);
                visibleEnemies.add(e);
            } else if (gameObject[i] instanceof Bullet) {
                final Bullet b = (Bullet) gameObject[i];
                // Skip locally-consumed bullets — set by the player-bullet
                // hit prediction in update(). Sprite vanishes but the
                // entry stays in the realm so the server's eventual
                // UnloadPacket cleanly removes it.
                if (b.isConsumedClient()) continue;
                visibleBullets.add(b);
            }
            // Players already added above, skip to avoid double-render
        }

        // Update visual effect state for all entities before rendering
        for (int i = 0; i < visibleEntities.size(); i++) {
            visibleEntities.get(i).updateEffectState();
        }

        // Pass 1.5: Ground shadows under each visible entity. Drawn BEFORE
        // entity bodies so the sprite stands on top of its own shadow,
        // mirroring webclient renderer.js (drawEllipse(0, size/2 + size*0.08,
        // size*0.4, size*0.12) at alpha 0.3). Adds visual weight + a hint
        // of grounded perspective.
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, 0.3f);
        for (int i = 0; i < visibleEntities.size(); i++) {
            final Entity ent = visibleEntities.get(i);
            final int s = ent.getSize() > 0 ? ent.getSize() : 32;
            final float wx = ent.getPos().getWorldVar().x + s * 0.5f;
            final float wy = ent.getPos().getWorldVar().y + s * 0.92f;
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

        // Status effect icons stacked above each player's HP/MP bars +
        // nameplate. Colored chips mirror webclient _drawStatusIcons
        // (renderer.js ~3066): bottommost chip just above the name,
        // additional effects stack upward. Color choices match the
        // webclient palette so a player can identify the effect at a
        // glance from either client.
        for (Player rp : this.realmManager.getRealm().getPlayers().values()) {
            final Short[] effs = rp.getEffectIds();
            if (effs == null) continue;
            final int s = rp.getSize() > 0 ? rp.getSize() : 32;
            // Same render-anchor as the HP/MP bars and nameplate above
            // so status icons don't oscillate against the moving sprite.
            final float wx = rp.getEffectiveRenderX() - Vector2f.worldX;
            final float wy = rp.getEffectiveRenderY() - Vector2f.worldY;
            // ~16 px below the HP/MP/name stack: HP at wy-10, MP at
            // wy-6, name centered around wy-15. Iconize from wy-22 down
            // (smaller y = visually higher in this projection).
            float iconY = wy - 22;
            final float iconW = s * 0.9f;
            final float iconH = 4f;
            final float iconGap = 1f;
            final float iconX = wx + (s - iconW) * 0.5f;
            for (StatusEffectIconDef def : STATUS_ICON_DEFS) {
                if (!hasEffectId(effs, def.effectId)) continue;
                shapes.setColor(0f, 0f, 0f, 0.85f);
                shapes.rect(iconX - 1, iconY - 1, iconW + 2, iconH + 2);
                shapes.setColor(def.r, def.g, def.b, 0.95f);
                shapes.rect(iconX, iconY, iconW, iconH);
                iconY -= (iconH + iconGap);
            }
        }
        shapes.end();

        // Pass 5: Visual ability effects (rings, arcs, particles)
        this.renderVisualEffects(shapes);

        Gdx.gl.glDisable(GL20.GL_BLEND);
        batch.begin();

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

        // Switch to the UI camera (1:1 screen pixels) so PlayerUI's HUD
        // layout uses window-pixel coords. Stays on UI camera for the
        // rest of the frame.
        if (game.getUiCamera() != null) {
            game.getUiCamera().update();
            batch.setProjectionMatrix(game.getUiCamera().combined);
            shapes.setProjectionMatrix(game.getUiCamera().combined);
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
            MoveItemPacket moveItem = MoveItemPacket.from(this.getPlayer().getId(), from.getTargetSlot(), (byte) slotIndex, false, consume);
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
        final float r, g, b;
        StatusEffectIconDef(short effectId, int rgb) {
            this.effectId = effectId;
            this.r = ((rgb >> 16) & 0xFF) / 255f;
            this.g = ((rgb >>  8) & 0xFF) / 255f;
            this.b = ( rgb        & 0xFF) / 255f;
        }
    }

    private static final StatusEffectIconDef[] STATUS_ICON_DEFS = new StatusEffectIconDef[] {
        new StatusEffectIconDef(StatusEffectType.HEALING.effectId,      0xFF4444),
        new StatusEffectIconDef(StatusEffectType.SPEEDY.effectId,       0x44FF44),
        new StatusEffectIconDef(StatusEffectType.BERSERK.effectId,      0xFF6644),
        new StatusEffectIconDef(StatusEffectType.DAMAGING.effectId,     0xFFAA44),
        new StatusEffectIconDef(StatusEffectType.ARMORED.effectId,      0x6688CC),
        new StatusEffectIconDef(StatusEffectType.INVINCIBLE.effectId,   0x44AAFF),
        new StatusEffectIconDef(StatusEffectType.INVISIBLE.effectId,    0xCCBB88),
        new StatusEffectIconDef(StatusEffectType.SLOWED.effectId,       0x6688FF),
        new StatusEffectIconDef(StatusEffectType.PARALYZED.effectId,    0x888888),
        new StatusEffectIconDef(StatusEffectType.STUNNED.effectId,      0x88CCFF),
        new StatusEffectIconDef(StatusEffectType.STASIS.effectId,       0x444448),
        new StatusEffectIconDef(StatusEffectType.DAZED.effectId,        0x9988AA),
        new StatusEffectIconDef(StatusEffectType.POISONED.effectId,     0x40CC40),
        new StatusEffectIconDef(StatusEffectType.CURSED.effectId,       0xAA2255),
        new StatusEffectIconDef(StatusEffectType.ARMOR_BROKEN.effectId, 0x7060CC),
    };

    private static boolean hasEffectId(Short[] effs, short eid) {
        if (effs == null) return false;
        for (Short s : effs) {
            if (s != null && s == eid) return true;
        }
        return false;
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

        float r, g, b;
        switch (type) {
        case CreateEffectPacket.EFFECT_HEAL_RADIUS:
            r = 0.1f; g = 1.0f; b = 0.2f;
            break;
        case CreateEffectPacket.EFFECT_VAMPIRISM:
            r = 0.9f; g = 0.0f; b = 1.0f;
            break;
        case CreateEffectPacket.EFFECT_STASIS_FIELD:
            r = 0.3f; g = 0.6f; b = 1.0f;
            break;
        case CreateEffectPacket.EFFECT_CURSE_RADIUS:
            r = 0.8f; g = 0.0f; b = 0.15f;
            break;
        case CreateEffectPacket.EFFECT_POISON_SPLASH:
            r = 0.2f; g = 0.8f; b = 0.2f;
            break;
        default:
            r = 1.0f; g = 1.0f; b = 1.0f;
            break;
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
