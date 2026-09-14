package com.openrealm.game.entity;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.openrealm.game.graphics.SpriteSheet;
import com.openrealm.game.math.Rectangle;
import com.openrealm.game.math.Vector2f;
import com.openrealm.net.entity.NetObjectMovement;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import com.badlogic.gdx.Gdx;

@Data
@Slf4j
public abstract class GameObject {
    protected long id;
    protected Rectangle bounds;
    protected Vector2f pos;
    protected int size;
    protected int spriteX;
    protected int spriteY;

    protected float dx;
    protected float dy;

    protected boolean teleported = false;
    protected String name = "";

    public boolean discovered;
    private SpriteSheet spriteSheet;

    public GameObject(long id, Vector2f origin, int spriteX, int spriteY, int size) {
        this(id, origin, size);
    }

    public void setSpriteSheet(final SpriteSheet spriteSheet) {
        this.spriteSheet = spriteSheet;
    }

    /**
     * Refresh {@link #bounds} after a pos/size change without allocating. bounds
     * shares the same mutable {@link Vector2f} as {@link #pos}, so in-place pos
     * mutation is already reflected; this only touches w/h on size changes.
     */
    protected void refreshBounds() {
        if (this.bounds == null) {
            this.bounds = new Rectangle(this.pos, this.size, this.size);
            return;
        }
        if (this.bounds.getPos() != this.pos) {
            this.bounds.setBox(this.pos, this.size, this.size);
        } else if ((int) this.bounds.getWidth() != this.size
                || (int) this.bounds.getHeight() != this.size) {
            this.bounds.setWidth(this.size);
            this.bounds.setHeight(this.size);
        }
    }

    public GameObject(long id, Vector2f origin, int size) {
        this.id = id;
        this.bounds = new Rectangle(origin, size, size);
        this.pos = origin;
        this.size = size;
    }

    public synchronized void setPos(Vector2f pos) {
        // A Player setPos at (0,0) is almost always a bug (invisible remote
        // player); log once per id with a stack trace to surface the call site.
        if (pos != null && pos.x == 0f && pos.y == 0f
                && this instanceof Player) {
            log.warn("[POS-DEBUG] setPos(0,0) on Player id={} - likely the cause of invisible-remote-player bug",
                    this.id, new Throwable("setPos(0,0)"));
        }
        this.pos = pos;
        this.bounds = new Rectangle(pos, this.size, this.size);
        this.teleported = true;
        // Entry point for teleports / realm transitions / spawn: re-prime the
        // dead-reckoning state so extrapolate() doesn't yank pos to a stale target.
        this.targetX = pos.x;
        this.targetY = pos.y;
        this.serverPosX = pos.x;
        this.serverPosY = pos.y;
        this.lastVelUpdateMs = System.currentTimeMillis();
    }

    public boolean getTeleported() {
        return this.teleported;
    }

    public void setTeleported(final boolean teleported) {
        this.teleported = teleported;
    }

    public void addForce(float a, boolean vertical) {
        if (!vertical) {
            this.dx -= a;
        } else {
            this.dy -= a;
        }
    }

    public void update() {

    }

    public void applyMovementLerp(float velX, float velY, float pct) {
        final float lerpX = this.lerp(this.pos.x, this.pos.x + velX, pct);
        final float lerpY = this.lerp(this.pos.y, this.pos.y + velY, pct);

        this.pos = new Vector2f(lerpX, lerpY);
    }

    // Beyond 3 tiles, snap instead of lerp (teleports/portals would slow-slide).
    private static final float SNAP_DISTANCE_SQ = (3 * 32) * (3 * 32);

    public synchronized void applyMovementLerp(NetObjectMovement packet, float pct) {
        float dx = packet.getPosX() - this.pos.x;
        float dy = packet.getPosY() - this.pos.y;
        if (dx * dx + dy * dy > SNAP_DISTANCE_SQ) {
            // Large jump — snap directly (teleport, portal, etc.)
            this.pos.x = packet.getPosX();
            this.pos.y = packet.getPosY();
        } else {
            this.pos.x = this.lerp(this.pos.x, packet.getPosX(), pct);
            this.pos.y = this.lerp(this.pos.y, packet.getPosY(), pct);
        }
        this.bounds = new Rectangle(this.pos, this.size, this.size);
        this.dx = packet.getVelX();
        this.dy = packet.getVelY();
        this.serverPosX = packet.getPosX();
        this.serverPosY = packet.getPosY();
        this.lastVelUpdateMs = System.currentTimeMillis();
    }

    public synchronized void applyMovementLerp(NetObjectMovement packet) {
        final float lerpX = this.lerp(this.pos.x, packet.getPosX(), 0.65f);
        final float lerpY = this.lerp(this.pos.y, packet.getPosY(), 0.65f);

        this.pos = new Vector2f(lerpX, lerpY);
        this.bounds = new Rectangle(this.pos, this.size, this.size);
        this.dx = packet.getVelX();
        this.dy = packet.getVelY();
        this.serverPosX = packet.getPosX();
        this.serverPosY = packet.getPosY();
        this.lastVelUpdateMs = System.currentTimeMillis();
    }

    public synchronized void applyMovement(NetObjectMovement packet) {
        this.pos = new Vector2f(packet.getPosX(), packet.getPosY());
        this.bounds = new Rectangle(this.pos, this.size, this.size);
        this.dx = packet.getVelX();
        this.dy = packet.getVelY();
        this.targetX = packet.getPosX();
        this.targetY = packet.getPosY();
        this.serverPosX = packet.getPosX();
        this.serverPosY = packet.getPosY();
        this.lastVelUpdateMs = System.currentTimeMillis();
    }

    // Server reconciliation (web client game.js parity). pos = rendered
    // position; target = server's authoritative pos projected along velocity.
    // Both advance by velocity each frame; pos is nudged toward target at a
    // bounded constant speed so motion never jumps on packet boundaries.
    protected volatile float targetX = Float.NaN;
    protected volatile float targetY = Float.NaN;

    // Last server-known authoritative position; pos snaps back to this on
    // staleness (>1.2s) or viewport exit so it doesn't drift off-map.
    protected volatile float serverPosX = Float.NaN;
    protected volatile float serverPosY = Float.NaN;
    /** Wall-clock millis of last applyServerCorrection; 0 = never. */
    protected volatile long lastVelUpdateMs = 0L;

    // Kept for legacy paths; new reconciliation uses constant-speed close.
    protected float correctionOffsetX = 0f;
    protected float correctionOffsetY = 0f;
    private static final float CORRECTION_BLEND_RATE = 0.15f;
    /** Floor close time; adaptive time scales up for larger gaps. */
    private static final float CORRECTION_CLOSE_TIME_SEC = 0.05f;
    /** Close-speed cap (8 tiles/s): small gaps close in 50ms, large gaps glide instead of teleport. */
    private static final float MAX_CATCHUP_SPEED_PX_PER_SEC = 256f;
    /** Beyond this pos/target gap, hard-snap. 5 tiles: routine jitter glides, real teleports snap. */
    private static final float CORRECTION_SNAP_THRESHOLD_SQ = (5 * 32) * (5 * 32);
    /** No velocity update for this long => extrapolating into thin air; snap back + zero velocity. */
    private static final long EXTRAP_STALENESS_CAP_MS = 1200L;

    /**
     * Web-parity refresh from a LoadPacket for an entity that ALREADY exists.
     * Must NOT overwrite pos (would rubber-band to a stale snapshot); only
     * refresh velocity, server-pos, lastVelUpdate, and divergent target.
     */
    public synchronized void refreshFromLoadPacket(float posX, float posY,
                                                   float velX, float velY) {
        this.dx = velX;
        this.dy = velY;
        this.serverPosX = posX;
        this.serverPosY = posY;
        this.lastVelUpdateMs = System.currentTimeMillis();
        // Refresh target only on >0.5px divergence so the close-step doesn't re-aim on noise.
        if (Float.isNaN(this.targetX)) {
            this.targetX = posX;
            this.targetY = posY;
        } else {
            final float ddx = posX - this.targetX;
            final float ddy = posY - this.targetY;
            if (ddx * ddx + ddy * ddy > 0.25f) {
                this.targetX = posX;
                this.targetY = posY;
            }
        }
    }

    public synchronized void applyServerCorrection(NetObjectMovement packet) {
        // Ignore exactly (0,0): the server's uninitialized-Vector2f sentinel,
        // not a real position. A real spawn re-broadcasts a non-zero pos next tick.
        if (packet.getPosX() == 0f && packet.getPosY() == 0f) {
            return;
        }
        // Velocity drives future extrapolation — always update.
        this.dx = packet.getVelX();
        this.dy = packet.getVelY();

        this.serverPosX = packet.getPosX();
        this.serverPosY = packet.getPosY();
        this.lastVelUpdateMs = System.currentTimeMillis();

        // First-ever correction: prime both target and pos.
        if (Float.isNaN(this.targetX)) {
            this.pos.x = packet.getPosX();
            this.pos.y = packet.getPosY();
            this.targetX = packet.getPosX();
            this.targetY = packet.getPosY();
            this.bounds = new Rectangle(this.pos, this.size, this.size);
            return;
        }

        // Move target only; pos is left for extrapolate() to nudge. A former
        // hard-snap-on-large-gap here caused the "remote player at (0,0)" bug
        // on first-after-realm-transition frames.
        this.targetX = packet.getPosX();
        this.targetY = packet.getPosY();
        this.refreshBounds();
    }

    /** Dead-reckoning extrapolation for enemies; players use blendCorrectionOffset(). */
    public void extrapolate() {
        this.extrapolate(0f, 0f, true);
    }

    /**
     * Extrapolate using server velocity (px/tick at 64Hz; per-second = ×64).
     * Viewport gate: the server only sends moves within ~10 tiles of a player,
     * so freeze velocity outside that radius to avoid drift-then-snap jitter.
     *
     * @param refX center X to gate against (local player center)
     * @param refY center Y to gate against
     * @param applyViewportGate freeze when outside ~10 tile radius
     */
    public synchronized void extrapolate(float refX, float refY, boolean applyViewportGate) {
        // Snapshot velocity + target so a concurrent applyServerCorrection()
        // can't tear the computation between pos-advance and gap-close.
        float vx = this.dx;
        float vy = this.dy;
        float tx = this.targetX;
        float ty = this.targetY;
        final boolean hasTarget = !Float.isNaN(tx);

        if (applyViewportGate) {
            final float halfSize = (this.size > 0) ? (this.size * 0.5f) : 16f;
            final float ex = this.pos.x + halfSize;
            final float ey = this.pos.y + halfSize;
            final float ddx = ex - refX;
            final float ddy = ey - refY;
            // 10 tiles + 1/2 tile margin = matches web client.
            final float VIEWPORT_FREEZE_PX = 10 * 32 + 16;
            if (ddx * ddx + ddy * ddy > VIEWPORT_FREEZE_PX * VIEWPORT_FREEZE_PX) {
                // Outside viewport: park at the last server-known position.
                this.dx = 0f;
                this.dy = 0f;
                if (!Float.isNaN(this.serverPosX)) {
                    this.pos.x = this.serverPosX;
                    this.pos.y = this.serverPosY;
                    this.targetX = this.serverPosX;
                    this.targetY = this.serverPosY;
                    this.refreshBounds();
                }
                return;
            }
        }

        // Staleness cap: freeze velocity (don't snap to the also-stale
        // serverPosX, which rubberbands) — ObjectMovePacket sends only diffs,
        // so a constant-velocity enemy's lastVelUpdateMs lags the server.
        if (this.lastVelUpdateMs != 0L) {
            if (System.currentTimeMillis() - this.lastVelUpdateMs > EXTRAP_STALENESS_CAP_MS) {
                this.dx = 0f;
                this.dy = 0f;
                this.refreshBounds();
                return;
            }
        }

        final float TICK_RATE = 64f;
        final float dt = Gdx.graphics != null
                ? Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f)
                : 1f / 60f;
        final float scale = dt * TICK_RATE;

        // Skip extrapolation when velocity is zero so idle enemies render at
        // the server-reported position exactly (and to avoid needless work).
        if (vx != 0f || vy != 0f) {
            this.pos.x += vx * scale;
            this.pos.y += vy * scale;
            if (hasTarget) {
                tx += vx * scale;
                ty += vy * scale;
                this.targetX = tx;
                this.targetY = ty;
            }
        }

        if (hasTarget) {
            // Adaptive constant-speed close: small gaps collapse in ~50ms,
            // large gaps glide at MAX_CATCHUP_SPEED_PX_PER_SEC instead of teleporting.
            final float gapX = tx - this.pos.x;
            final float gapY = ty - this.pos.y;
            final float distSq = gapX * gapX + gapY * gapY;
            if (distSq > CORRECTION_SNAP_THRESHOLD_SQ) {
                // Hard snap on huge gaps (teleport / realm transition).
                this.pos.x = tx;
                this.pos.y = ty;
            } else if (distSq > 0.09f /* 0.3px */) {
                final float dist = (float) Math.sqrt(distSq);
                final float adaptiveCloseTime = Math.max(
                        CORRECTION_CLOSE_TIME_SEC,
                        dist / MAX_CATCHUP_SPEED_PX_PER_SEC);
                final float step = (dist / adaptiveCloseTime) * dt;
                if (step >= dist) {
                    this.pos.x = tx;
                    this.pos.y = ty;
                } else {
                    final float ratio = step / dist;
                    this.pos.x += gapX * ratio;
                    this.pos.y += gapY * ratio;
                }
            }
        }
        this.refreshBounds();
    }

    /** Blend correction offset toward zero without advancing by velocity
     *  (players advance velocity via collision-checked PlayState.movePlayer). */
    public synchronized void blendCorrectionOffset() {
        if (this.correctionOffsetX != 0f || this.correctionOffsetY != 0f) {
            float blendX = this.correctionOffsetX * CORRECTION_BLEND_RATE;
            float blendY = this.correctionOffsetY * CORRECTION_BLEND_RATE;
            this.pos.x += blendX;
            this.pos.y += blendY;
            this.correctionOffsetX -= blendX;
            this.correctionOffsetY -= blendY;

            // Zero out tiny residuals to avoid perpetual micro-corrections
            if (this.correctionOffsetX * this.correctionOffsetX +
                this.correctionOffsetY * this.correctionOffsetY < 0.01f) {
                this.correctionOffsetX = 0f;
                this.correctionOffsetY = 0f;
            }
        }
        this.refreshBounds();
    }

    private float lerp(float start, float end, float pct) {
        return (start + ((end - start) * pct));
    }

    public Vector2f getCenteredPosition() {
        return this.pos.clone((this.getSize() / 2), this.getSize() / 2);
    }

    @Override
    public Vector2f clone() {
        Vector2f newVector = new Vector2f(this.pos.x, this.pos.y);
        return newVector;
    }

    public void render(SpriteBatch batch) {
        if (this.spriteSheet == null) {
            GameObject.log.warn("GameObject {} does not have a sprite sheet!");
            return;
        }
        TextureRegion frame = this.spriteSheet.getCurrentFrame();
        if (frame != null) {
            batch.draw(frame, this.pos.getWorldVar().x, this.pos.getWorldVar().y, this.size, this.size);
        }
    }

    @Override
    public String toString() {
        return "$" + this.name;
    }
}
