package com.openrealm.game.state;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
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
import com.openrealm.game.model.PortalModel;
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
        final LoginRequestMessage login = LoginRequestMessage.builder().characterUuid(SocketClient.CHARACTER_UUID)
                .email(SocketClient.PLAYER_EMAIL).password(SocketClient.PLAYER_PASSWORD).build();
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
            for (int i = 0; i < gameObject.length; i++) {
                if (gameObject[i] instanceof Enemy) {
                    ((Enemy) gameObject[i]).update(this.getRealmManager(), time);
                } else if (gameObject[i] instanceof Bullet) {
                    ((Bullet) gameObject[i]).update(bulletScale);
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
            if (!bullets.isEmpty() && !enemies.isEmpty()) {
                for (final Bullet b : bullets.values()) {
                    if (b == null || b.getPos() == null) continue;
                    if (b.isConsumedClient()) continue;
                    if (!b.hasFlag(ProjectileFlag.PLAYER_PROJECTILE)) continue;
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

            for (int i = 0; i < this.shotDestQueue.size(); i++) {
                final Vector2f dest = this.shotDestQueue.remove(i);
                final Vector2f source = this.getPlayer().getCenteredPosition();
                if (this.realmManager.getRealm().getTileManager().isCollisionTile(source)) {
                    continue;
                }
                try {
                    PlayerShootPacket packet = PlayerShootPacket.from(Realm.RANDOM.nextLong(), player, dest);
                    this.realmManager.getClient().sendRemote(packet);
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

            final List<EffectText> toRemove = new ArrayList<>();
            for (EffectText text : this.getDamageText()) {
                text.update();
                if (text.getRemove()) {
                    toRemove.add(text);
                }
            }
            this.damageText.removeAll(toRemove);

            final float deltaMs = (float) (time * 1000.0);
            final List<ActiveVisualEffect> effectsToRemove = new ArrayList<>();
            for (ActiveVisualEffect vfx : this.activeEffects) {
                vfx.update(deltaMs);
                if (vfx.getRemove()) {
                    effectsToRemove.add(vfx);
                }
            }
            this.activeEffects.removeAll(effectsToRemove);

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
                // drain ticks → simulateTick(perTick) → set interpFrom/To →
                // compute renderX = lerp(from, to, frac) → set HUD positions.
                //
                // This BLOCK is the entire player movement & visual position
                // pipeline. Doing it INLINE here (not split between update()
                // and input()) is critical: any 1-frame split between
                // simulating and computing renderX produces visible per-tick
                // lurch even when both endpoints are correct, because dt has
                // moved on before the lerp catches up.
                // ============================================================
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
                    // Apply one tick of movement with collision check. We
                    // set dx/dy on the player and let movePlayer (the
                    // shared collision-aware integrator) advance pos.x/y
                    // by exactly one tick worth.
                    player.setDx(vx * pxPerTick);
                    player.setDy(vy * pxPerTick);
                    this.movePlayer(player);
                    ticks++;
                }

                if (ticks > 0) {
                    this.interpFromX = preTickX;
                    this.interpFromY = preTickY;
                    this.hasInterpAnchor = true;
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

                // Send PlayerMovePacket only when direction changes (server
                // already tracks the last-known direction and reuses it
                // tick-to-tick).
                if (this.lastDirectionMap == null) {
                    this.lastDirectionMap = lastDirectionTempMap;
                }
                if (!this.lastDirectionMap.equals(lastDirectionTempMap)) {
                    try {
                        player.setLastInputSeq(player.getLastInputSeq() + 1);
                        PlayerMovePacket packet = PlayerMovePacket.from(player, player.getLastInputSeq(), vx, vy);
                        this.realmManager.getClient().sendRemote(packet);
                    } catch (Exception e) {
                        PlayState.log.error("Failed to create player move packet. Reason: {}", e);
                    }
                    this.lastDirectionMap = lastDirectionTempMap;
                }

                // Compute render position INLINE — must happen this same
                // call so render() sees up-to-date values. Web client does
                // this in the same processInput frame.
                final float interpFrac = Math.max(0f, Math.min(1f, this.moveAccumulator / TICK_DT));
                float renderX = this.hasInterpAnchor
                        ? this.interpFromX + (player.getPos().x - this.interpFromX) * interpFrac
                        : player.getPos().x;
                float renderY = this.hasInterpAnchor
                        ? this.interpFromY + (player.getPos().y - this.interpFromY) * interpFrac
                        : player.getPos().y;
                player.setRenderPos(renderX, renderY);

                // Camera follows the lerped player position with
                // exponential smoothing (web parity, game.js ~1580):
                //   cameraX += (target - cameraX) * (1 - exp(-dt/halflife))
                // Halflife 0.03s → ~97% of any gap closes within 150ms,
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
            // pipeline above (KeyHandler binds Space → key.attack), so
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
                boolean used = false;
                if (key.one.clicked) { this.handleQuickUseKey(4); used = true; }
                else if (key.two.clicked) { this.handleQuickUseKey(5); used = true; }
                else if (key.three.clicked) { this.handleQuickUseKey(6); used = true; }
                else if (key.four.clicked) { this.handleQuickUseKey(7); used = true; }
                else if (key.five.clicked) { this.handleQuickUseKey(8); used = true; }
                else if (key.six.clicked) { this.handleQuickUseKey(9); used = true; }
                else if (key.seven.clicked) { this.handleQuickUseKey(10); used = true; }
                else if (key.eight.clicked) { this.handleQuickUseKey(11); used = true; }
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
                 || this.pui.getOptionsWindow().isVisible());
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
        player.setAttacking(clickingWorld);
        // Pass mouse screen position for aim-based attack animation direction
        player.setAimX(mouse.getX());
        player.setAimY(mouse.getY());
        // Mouse → world conversion: the world camera is zoomed 2× via
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
        }
        if ((mouse.isPressed(3)) && canUseAbility && (this.pui == null || !this.pui.isHoveringInventory(mouse.getX()))) {
            // Client-side mana gate. Server enforces this too, but without
            // a local check the player can spam-click and watch predicted
            // projectiles spawn before the server reply unloads them, then
            // the mana bar snaps back when UpdatePacket arrives. Mirrors
            // the webclient tryUseAbility cost gate. Optimistic decrement
            // keeps the gate honest within a round-trip.
            int abilityCost = 0;
            try {
                final GameItem ability = player.getSlot(1);
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
     * → new, showing a visible hop every server tick.
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

        // Pass 4: Enemy health bars
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
        shapes.end();

        // Pass 5: Visual ability effects (rings, arcs, particles)
        this.renderVisualEffects(shapes);

        Gdx.gl.glDisable(GL20.GL_BLEND);
        batch.begin();

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

        font.setColor(Color.WHITE);
        String fps = this.lastFrames + " FPS";
        font.draw(batch, fps, 6 * 32, 32);
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

        final LootContainer closeLoot = this.getClosestLootContainer(player.getPos(), player.getSize() / 2);

        if ((closeLoot != null && this.getPui().isGroundLootEmpty()) || (closeLoot != null && closeLoot.getContentsChanged())) {
            this.getPui().setGroundLoot(closeLoot.getItems());

        } else if ((closeLoot == null) && !this.getPui().isGroundLootEmpty()) {
            this.getPui().setGroundLoot(new GameItem[8]);
        }

        if (closeLoot != null && !this.getPui().isGroundLootEmpty()) {
            final boolean contentsChanged = this.getPui().getNonEmptySlotCount() != closeLoot.getNonEmptySlotCount();
            if (contentsChanged) {
                this.getPui().setGroundLoot(closeLoot.getItems());
            }
        }
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

    /** Render a chunky poison vial arc from player to target position (800ms flight) */
    private void renderPoisonThrow(ShapeRenderer shapes, ActiveVisualEffect vfx, float t, float wx, float wy) {
        final float x1 = vfx.getPosX() - wx;
        final float y1 = vfx.getPosY() - wy;
        final float x2 = vfx.getTargetPosX() - wx;
        final float y2 = vfx.getTargetPosY() - wy;

        final float dx = x2 - x1;
        final float dy = y2 - y1;
        final float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 1f) return;

        // Tall parabolic arc — 50% of throw distance as peak height
        int steps = 24;
        float arcHeight = dist * 0.5f;

        // Vial position along arc (t goes 0→1 over the 800ms duration)
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

        // Thick green trail behind the vial
        for (int i = 0; i < steps; i++) {
            float f = (float) (i + 1) / steps;
            if (f > vialFrac) break;
            // Trail fades from thin at start to thick near vial
            float thickness = 3.0f + 5.0f * (f / Math.max(vialFrac, 0.01f));
            float trailAlpha = 0.15f + 0.4f * (f / Math.max(vialFrac, 0.01f));
            shapes.setColor(0.2f, 0.65f, 0.15f, trailAlpha);
            shapes.rectLine(arcX[i], arcY[i], arcX[i + 1], arcY[i + 1], thickness);
        }

        // Dripping particles along the trail
        for (int i = 0; i < 6; i++) {
            float pf = vialFrac * (0.3f + 0.7f * i / 6.0f);
            int idx = Math.min((int) (pf * steps), steps);
            float dripY = arcY[idx] + (t * 30.0f * (i + 1) / 6.0f);  // drip downward over time
            float dripAlpha = Math.max(0, 0.5f - t * 0.6f);
            if (dripAlpha > 0) {
                shapes.setColor(0.15f, 0.6f, 0.1f, dripAlpha);
                shapes.rect(arcX[idx] - 2, dripY - 1, 4, 3 + i);
            }
        }

        // Fat vial blob
        if (vialFrac < 1.0f) {
            int vialIdx = Math.min((int) (vialFrac * steps), steps);
            float vx = arcX[vialIdx];
            float vy = arcY[vialIdx];

            // Outer glow
            shapes.setColor(0.2f, 0.7f, 0.1f, 0.4f);
            drawCircle(shapes, vx, vy, 12f, 10);
            // Main vial body
            shapes.setColor(0.3f, 0.9f, 0.2f, 0.9f);
            drawCircle(shapes, vx, vy, 8f, 10);
            // Bright core / highlight
            shapes.setColor(0.7f, 1.0f, 0.5f, 0.8f);
            drawCircle(shapes, vx - 2, vy - 2, 3.5f, 8);
        }

        shapes.end();
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
