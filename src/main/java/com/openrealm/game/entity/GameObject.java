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

    public GameObject(long id, Vector2f origin, int size) {
        this.id = id;
        this.bounds = new Rectangle(origin, size, size);
        this.pos = origin;
        this.size = size;
    }

    public synchronized void setPos(Vector2f pos) {
        this.pos = pos;
        this.bounds = new Rectangle(pos, this.size, this.size);
        this.teleported = true;
        // setPos is the entry point for teleports / realm transitions /
        // initial spawn. Re-prime the dead-reckoning state so the next
        // extrapolate() doesn't yank pos back to a stale target/serverPos
        // that referred to a previous map.
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

    // Snap threshold: if server position is more than 3 tiles away, snap instead of lerp.
    // This handles teleports (planewalker cloak, portals) where lerp would cause a slow slide.
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

    // --- Server reconciliation, ported from web client game.js ---
    // Web client maintains TWO positions per entity:
    //   pos     — the visually-rendered position (what the player sees)
    //   target  — the server's authoritative position, projected forward
    //             along the last-known velocity each tick
    // Each frame, both pos and target advance by dx*tickStep (extrapolation),
    // and pos is then nudged toward target at a constant linear speed
    // (closes any gap in ~50 ms). When a server packet arrives,
    // target snaps to packet.posX/posY but pos is unchanged — so the
    // visible motion is always smooth, never jumping on packet boundaries.
    //
    // The previous "correctionOffset blended at 15% per frame on top of
    // dx*tickStep extrapolation" had two problems:
    //   1. Errors REPLACED across rapid packets caused pos to oscillate
    //      forward/backward (visible rubberband).
    //   2. The blend ran ON TOP of extrapolation, inflating effective
    //      speed by ~15% whenever the client was a tick behind the server.
    protected volatile float targetX = Float.NaN;
    protected volatile float targetY = Float.NaN;

    // Last server-known authoritative position. When extrapolation goes
    // stale (no updates >1.2 s) or the entity leaves the viewport, pos is
    // snapped back to this so it doesn't drift through walls / off-map.
    // Mirrors web client's e._serverPosX/_serverPosY.
    protected volatile float serverPosX = Float.NaN;
    protected volatile float serverPosY = Float.NaN;
    /** Wall-clock millis when applyServerCorrection last ran. Used by the
     *  staleness cap in extrapolate(). 0 = never updated. */
    protected volatile long lastVelUpdateMs = 0L;

    // Correction blend rate kept for legacy paths but new reconciliation
    // uses constant-speed close (CORRECTION_CLOSE_TIME_SEC).
    protected float correctionOffsetX = 0f;
    protected float correctionOffsetY = 0f;
    private static final float CORRECTION_BLEND_RATE = 0.15f;
    /** Time (sec) over which pos is lerped to target after a server packet
     *  diverges from extrapolation. Mirrors web's `dist / 0.05` formula. */
    private static final float CORRECTION_CLOSE_TIME_SEC = 0.05f;
    // If target diverges by more than this from pos, hard-snap pos.
    private static final float CORRECTION_SNAP_THRESHOLD_SQ = (3 * 32) * (3 * 32);
    /** Web parity: if no server velocity update arrives for this long, the
     *  client is extrapolating into thin air. Snap pos back to the last
     *  known server position and zero velocity. */
    private static final long EXTRAP_STALENESS_CAP_MS = 1200L;

    /**
     * Apply a dead reckoning server correction. Instead of snapping the entity,
     * we compute the error between our local position and the server's corrected
     * position, and store it as an offset to be blended out over subsequent frames.
     * Velocity is always updated immediately since it affects future extrapolation.
     */
    /**
     * Web-parity refresh from a LoadPacket for an entity that ALREADY
     * exists on the client. The server now resends the full enemy/player
     * set every LoadPacket so a dropped ObjectMovePacket self-heals next
     * tick — but we must not overwrite pos (rubber-bands the entity to a
     * stale snapshot). Only refresh velocity, server-pos / lastVelUpdate,
     * and (when divergent) targetX/Y.
     *
     * Equivalent to the existing-enemy branch in game.js's LoadPacket
     * handler.
     */
    public synchronized void refreshFromLoadPacket(float posX, float posY,
                                                   float velX, float velY) {
        this.dx = velX;
        this.dy = velY;
        this.serverPosX = posX;
        this.serverPosY = posY;
        this.lastVelUpdateMs = System.currentTimeMillis();
        // Refresh target only on meaningful divergence (>0.5 px) so the
        // close-step doesn't re-aim every tick at sub-pixel noise.
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
        // Velocity drives future extrapolation — always update.
        this.dx = packet.getVelX();
        this.dy = packet.getVelY();

        // Track the server-reported authoritative position + freshness so
        // extrapolate() can snap back to it on staleness / viewport exit.
        this.serverPosX = packet.getPosX();
        this.serverPosY = packet.getPosY();
        this.lastVelUpdateMs = System.currentTimeMillis();

        // First-ever correction: prime both target AND pos so we don't
        // start from zero.
        if (Float.isNaN(this.targetX)) {
            this.pos.x = packet.getPosX();
            this.pos.y = packet.getPosY();
            this.targetX = packet.getPosX();
            this.targetY = packet.getPosY();
            this.bounds = new Rectangle(this.pos, this.size, this.size);
            return;
        }

        // Move target to the server's reported position. pos is left alone
        // — extrapolate() will smoothly nudge it toward target each frame.
        this.targetX = packet.getPosX();
        this.targetY = packet.getPosY();

        // Hard snap if the target jumped a long way (teleport, realm tx).
        final float gapX = this.targetX - this.pos.x;
        final float gapY = this.targetY - this.pos.y;
        if (gapX * gapX + gapY * gapY > CORRECTION_SNAP_THRESHOLD_SQ) {
            this.pos.x = this.targetX;
            this.pos.y = this.targetY;
        }
        this.bounds = new Rectangle(this.pos, this.size, this.size);
    }

    /**
     * Advance position by velocity (dead reckoning extrapolation) and blend
     * any pending correction offset. Call this once per client tick for entities
     * that use dead reckoning (enemies). For players, use blendCorrectionOffset()
     * instead since movePlayer() handles velocity advancement with collision checks.
     */
    public void extrapolate() {
        this.extrapolate(0f, 0f, true);
    }

    /**
     * Extrapolate position using server-supplied velocity, with the same
     * viewport gating the web client uses in game.js updateInterpolation().
     *
     * dx/dy from the server are in pixels-per-TICK at the server's 64 Hz
     * simulation rate. Per-frame motion = dx * dt * 64 → per-second motion
     * = dx * 64, matching the server regardless of render FPS.
     *
     * Viewport gate: server only sends ObjectMovePackets for entities
     * within ~10 tiles of any player. The instant the entity crosses
     * outside that radius, server updates dry up — extrapolating further
     * is pure client fiction and produces a "drift then snap-back" jitter
     * the moment an update lands. Pass the local player position so this
     * method can freeze velocity outside the viewport.
     *
     * @param refX center X to gate against (e.g. local player center)
     * @param refY center Y to gate against
     * @param applyViewportGate true to freeze when outside ~10 tile radius
     */
    public synchronized void extrapolate(float refX, float refY, boolean applyViewportGate) {
        // Snapshot velocity + target locally so a concurrent
        // applyServerCorrection() on the network thread can't tear the
        // computation between the pos-advance and the gap-close steps.
        // Even with `synchronized` on both methods this keeps the inner
        // math working off a single consistent set of values.
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
                // Outside viewport: server has stopped sending updates for
                // this entity, so further extrapolation is pure fiction.
                // Park it at the last server-known position so it doesn't
                // drift off-map and snap back when it re-enters the view.
                this.dx = 0f;
                this.dy = 0f;
                if (!Float.isNaN(this.serverPosX)) {
                    this.pos.x = this.serverPosX;
                    this.pos.y = this.serverPosY;
                    this.targetX = this.serverPosX;
                    this.targetY = this.serverPosY;
                    this.bounds = new Rectangle(this.pos, this.size, this.size);
                }
                return;
            }
        }

        // Staleness cap: if the server hasn't acked velocity for a while,
        // freeze extrapolation in place. Critical: ObjectMovePacket sends
        // only the diff — a constant-velocity enemy isn't re-broadcast,
        // so lastVelUpdateMs lags the actual server state. Snapping pos
        // back to serverPosX (which is also stale) produces a visible
        // backward rubberband. Freezing velocity lets the next real
        // packet close any gap smoothly via the target mechanism.
        if (this.lastVelUpdateMs != 0L) {
            if (System.currentTimeMillis() - this.lastVelUpdateMs > EXTRAP_STALENESS_CAP_MS) {
                this.dx = 0f;
                this.dy = 0f;
                this.bounds = new Rectangle(this.pos, this.size, this.size);
                return;
            }
        }

        final float TICK_RATE = 64f;
        final float dt = Gdx.graphics != null
                ? Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f)
                : 1f / 60f;
        final float scale = dt * TICK_RATE;

        // Web parity: skip the extrapolation step entirely when velocity
        // is zero. Avoids touching pos/target with a 0-magnitude vector
        // every frame for the thousands of idle enemies in a realm — both
        // a tiny perf win and a guarantee that idle enemies render at the
        // server-reported position byte-for-byte.
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
            // Constant-speed close from pos toward target. Mirrors the web
            // client's `speed = dist / 0.05; step = speed * dt` formula:
            // any divergence collapses to zero in CORRECTION_CLOSE_TIME_SEC,
            // regardless of frame rate.
            final float gapX = tx - this.pos.x;
            final float gapY = ty - this.pos.y;
            final float distSq = gapX * gapX + gapY * gapY;
            if (distSq > CORRECTION_SNAP_THRESHOLD_SQ) {
                // Hard snap on huge gaps (teleport / realm transition that
                // didn't go through applyServerCorrection's normal path).
                this.pos.x = tx;
                this.pos.y = ty;
            } else if (distSq > 0.09f /* 0.3px */) {
                final float dist = (float) Math.sqrt(distSq);
                final float step = (dist / CORRECTION_CLOSE_TIME_SEC) * dt;
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
        this.bounds = new Rectangle(this.pos, this.size, this.size);
    }

    /**
     * Blend pending correction offset toward zero without advancing by velocity.
     * Use this for entities where velocity advancement is handled elsewhere
     * (e.g., players with collision-checked movement in PlayState.movePlayer).
     */
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
        this.bounds = new Rectangle(this.pos, this.size, this.size);
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
