package com.openrealm.game.entity;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.openrealm.game.contants.ProjectileFlag;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.ProjectileGroup;

import lombok.Data;
import lombok.EqualsAndHashCode;
import com.openrealm.game.model.ProjectileEffect;

@Data
@EqualsAndHashCode(callSuper = false)
public class Bullet extends GameObject  {
	private long srcEntityId;
    private int projectileId;
    private float angle;
    private float magnitude;
    private float range;
    private short damage;
    private boolean isEnemy;
    private boolean playerHit;
    private boolean enemyHit;
    private float tfAngle = (float) (Math.PI / 2);

    /**
     * Projectile behavior flags (ProjectileFlag IDs): PLAYER_PROJECTILE(10),
     * PARAMETRIC(12), INVERTED_PARAMETRIC(13), ORBITAL(20).
     * Controls movement — NOT on-hit effects.
     */
    private List<Short> flags;
    /**
     * On-hit status effects (StatusEffect IDs + durations).
     * Applied to the target entity when this bullet hits.
     * NOT behavior flags — those go in {@link #flags}.
     */
    private List<ProjectileEffect> effects;

    private boolean invert = false;

    private long timeStep = 0;
    private short amplitude = 4;
    private short frequency = 25;

    // Orbital projectile: orbits around a fixed center point
    private float orbitCenterX;
    private float orbitCenterY;
    private float orbitRadius;
    private float orbitPhase; // starting angle in radians for this projectile

    // LINE_SEGMENT wall: length (px) of the segment, which extends perpendicular
    // to the facing angle; size is its thickness. lifetimeTicks forces expiry so
    // a static (magnitude 0) wall whose range never decrements still despawns.
    private short length;
    private int lifetimeTicks;
    // ANCHORED follow: offset of this bullet's top-left from the source entity's
    // top-left, derived once at first sight so the wall tracks the moving enemy.
    private boolean anchorReady;
    private float anchorOffsetX;
    private float anchorOffsetY;

    private long createdTime;
    /**
     * Server-tick at which this bullet was spawned. Used for O(1) tick-counter
     * based expiry instead of wall-clock comparison via Instant.now() — saves
     * ~12 K syscalls/sec when many bullets are in flight.
     */
    private long createdTick;
    /**
     * Cached sin/cos of {@link #angle}. Angle is invariant for straight and
     * parametric projectiles, so caching at construction (and on direction-
     * change) eliminates two trig calls per bullet per tick. Orbital bullets
     * use a different code path and don't read these.
     */
    private float sinAngle;
    private float cosAngle;
    private long lastUpdateNanos = System.nanoTime();

    public Bullet() {
    	super(0l,null,0);
    }
    public Bullet(long id, int bulletId, Vector2f origin, int size) {
        super(id, origin, size);
        this.flags = new ArrayList<>();
        this.createdTime = Instant.now().toEpochMilli();
        cacheAngle();
    }

    public Bullet(long id, int projectileId, Vector2f origin, int size, float angle, float magnitude, float range,
            short damage, boolean isEnemy, boolean playerHit, boolean enemyHit, List<Short> flags, boolean invert,
            long timeStep, short amplitude, short frequency) {
        super(id, origin, size);
        this.projectileId = projectileId;
        this.angle = angle;
        this.magnitude = magnitude;
        this.range = range;
        this.damage = damage;
        this.isEnemy = isEnemy;
        this.playerHit = playerHit;
        this.enemyHit = enemyHit;
        this.flags = flags;
        this.invert = invert;
        this.timeStep = timeStep;
        this.amplitude = amplitude;
        this.frequency = frequency;
        this.createdTime = Instant.now().toEpochMilli();
        cacheAngle();
    }

    public Bullet(long id, int projectileId, Vector2f origin, Vector2f dest, short size, float magnitude, float range,
            short damage, boolean isEnemy) {
        super(id, origin, size);
        this.projectileId = projectileId;
        this.magnitude = magnitude;
        this.range = range;
        this.damage = damage;
        this.angle = -Bullet.getAngle(origin, dest);
        this.isEnemy = isEnemy;
        this.flags = new ArrayList<>();
        this.createdTime = Instant.now().toEpochMilli();
        cacheAngle();
    }

    public Bullet(long id, int projectileId, Vector2f origin, Vector2f dest, short size, float magnitude, float range,
            short damage, short amplitude, short frequency, boolean isEnemy) {
        super(id, origin, size);
        this.projectileId = projectileId;
        this.magnitude = magnitude;
        this.range = range;
        this.damage = damage;
        this.angle = -Bullet.getAngle(origin, dest);
        this.amplitude = amplitude;
        this.frequency = frequency;
        this.isEnemy = isEnemy;
        this.flags = new ArrayList<>();
        this.createdTime = Instant.now().toEpochMilli();
        cacheAngle();
    }

    public Bullet(long id, int projectileId, Vector2f origin, float angle, short size, float magnitude, float range,
            short damage, boolean isEnemy) {
        super(id, origin, size);
        this.projectileId = projectileId;
        this.magnitude = magnitude;
        this.range = range;
        this.damage = damage;
        this.angle = -angle;
        this.isEnemy = isEnemy;
        this.flags = new ArrayList<>();
        this.createdTime = Instant.now().toEpochMilli();
        cacheAngle();
    }

    public static float getAngle(Vector2f source, Vector2f target) {
        double angle = (Math.atan2(target.y - source.y, target.x - source.x));
        angle -= Math.PI / 2;
        return (float) angle;
    }

    /** Recompute the cached sin/cos after {@link #angle} is set. Avoids per-
     *  tick trig calls in {@link #update(float)} and {@link #updateParametric(float)}. */
    public void cacheAngle() {
        this.sinAngle = (float) Math.sin(this.angle);
        this.cosAngle = (float) Math.cos(this.angle);
    }

    public void setAngle(float angle) {
        this.angle = angle;
        cacheAngle();
    }

    /** Client-side hit prediction marker. PlayState's per-frame circle hit
     *  test sets this when a player bullet visually contacts an enemy.
     *  Renderer skips consumed bullets so the sprite vanishes on hit, but
     *  the entry stays in the realm map — removing on hit was fighting the
     *  server's LoadPacket diff, which kept re-adding the bullet at its
     *  server-side (slightly stale) position and produced visibly "frozen"
     *  projectiles until the next UnloadPacket. The eventual UnloadPacket
     *  cleans up for real. */
    private transient boolean consumedClient = false;

    public boolean isConsumedClient() { return this.consumedClient; }
    public void setConsumedClient(boolean v) { this.consumedClient = v; }

    // WHY: Locally-spawned (client-predicted) player bullets bypass the
    // server LoadPacket round-trip so the firing player sees their stream
    // continuously. The server's eventual broadcast is dedup'd against
    // these in handleLoadClient instead of being inserted alongside.
    private transient boolean predicted = false;
    public boolean isPredicted() { return this.predicted; }
    public void setPredicted(boolean v) { this.predicted = v; }

    public boolean hasFlag(short flag) {
        return (this.flags != null) && (this.flags.contains(flag));
    }

    public boolean hasFlag(ProjectileFlag flag) {
        return (this.flags != null) && (this.flags.contains(flag.flagId));
    }

    public boolean hasFlag(StatusEffectType flag) {
        return (this.flags != null) && (this.flags.contains(flag.effectId));
    }

    public boolean isEnemy() {
        return this.isEnemy;
    }

    public float getAngle() {
        return this.angle;
    }

    public float getMagnitude() {
        return this.magnitude;
    }

    // 10-second lifetime ceiling (640 ticks @ 64Hz). Tick-counter based to
    // avoid the wall-clock syscall that used to run per-bullet per-tick.
    private static final long MAX_LIFETIME_TICKS = 640L;

    public boolean remove() {
        return this.range <= 0.0;
    }

    /** Tick-counter aware expiry — pass the realm's current tickCounter. */
    public boolean remove(long currentTick) {
        // Explicit lifetime governs static walls whose range never decrements.
        // Client bullets carry no createdTick (currentTick is passed as 0), so
        // fall back to the server-stamped createdTime at 64 ticks/sec.
        if (this.lifetimeTicks > 0) {
            if (this.createdTick != 0L) {
                return (currentTick - this.createdTick) > this.lifetimeTicks;
            }
            return (Instant.now().toEpochMilli() - this.createdTime) > (this.lifetimeTicks * 1000L / 64L);
        }
        if (this.range <= 0.0) return true;
        // createdTick == 0 indicates a legacy bullet not initialized via the
        // tick-aware spawn path; fall back to the wall-clock check so the
        // 10-second cap still works for those.
        if (this.createdTick != 0L) {
            return (currentTick - this.createdTick) > MAX_LIFETIME_TICKS;
        }
        return ((Instant.now().toEpochMilli()) - this.createdTime) > 10000L;
    }

    public short getDamage() {
        return this.damage;
    }

    @Override
    // Legacy entry point — kept so other callers that still pass no arg keep
    // working. The realm tick now precomputes bulletScale once per tick and
    // calls update(float) below, avoiding ~12 K nanoTime syscalls/sec when
    // many bullets are in flight.
    public void update() {
        final long now = System.nanoTime();
        final float dt = Math.min((now - this.lastUpdateNanos) / 1_000_000_000.0f, 0.1f);
        this.lastUpdateNanos = now;
        update(dt * 64.0f);
    }

    /**
     * Hot path — bulletScale is computed ONCE per tick at the realm level
     * and passed to every bullet. Removes the per-bullet System.nanoTime()
     * + float division that used to run 12 800 times/sec at 200 bullets.
     * Also reads cached {@link #sinAngle}/{@link #cosAngle} instead of
     * calling Math.sin/cos every tick — the angle of straight + parametric
     * projectiles is invariant for the bullet's whole lifetime.
     */
    public void update(float bulletScale) {
        if (this.hasFlag(ProjectileFlag.ORBITAL)) {
            this.updateOrbital(bulletScale);
        } else if (this.hasFlag(ProjectileFlag.PARAMETRIC)
                || this.hasFlag(ProjectileFlag.INVERTED_PARAMETRIC)) {
            this.updateParametric(bulletScale);
        } else {
            // LINE_SEGMENT walls spin in place when given a frequency (deg/tick) —
            // matches the server so the rendered wall tracks the server hitbox.
            if (this.frequency != 0 && this.hasFlag(ProjectileFlag.LINE_SEGMENT)) {
                this.angle += (float) Math.toRadians(this.frequency * bulletScale);
                cacheAngle();
            }
            // Straight-line projectile — uses cached sin/cos.
            float speed = this.magnitude;
            if (this.hasFlag(ProjectileFlag.SPEED_DECAY) || this.hasFlag(ProjectileFlag.SPEED_RAMP)) {
                speed *= speedCurveMult();
            }
            final float step = speed * bulletScale;
            final float velX = this.sinAngle * step;
            final float velY = this.cosAngle * step;
            // dist == magnitude * bulletScale because (sinA² + cosA²) == 1.
            this.range -= step;
            this.pos.addX(velX);
            this.pos.addY(velY);
            this.dx = velX;
            this.dy = velY;
        }
    }

    /**
     * Parametric projectile update - applies sinusoidal oscillation perpendicular
     * to the direction of travel, creating wavy projectile patterns (e.g. RotMG staff shots).
     *
     * The oscillation is computed as a position offset along the perpendicular axis,
     * so each tick we apply the CHANGE in offset (delta) rather than a raw velocity.
     * Negative amplitude naturally inverts the wave (no special flag needed).
     *
     * Perpendicular axis to forward (sin(a), cos(a)) is (cos(a), -sin(a)).
     */
    public void updateParametric(float bulletScale) {
        // Compute perpendicular offset BEFORE advancing timeStep
        float prevOffset = (float) (this.amplitude * Math.sin(Math.toRadians(this.timeStep)));

        this.timeStep = (long) ((this.timeStep + this.frequency * bulletScale) % 360);

        float currOffset = (float) (this.amplitude * Math.sin(Math.toRadians(this.timeStep)));
        float perpDelta = (currOffset - prevOffset) * (this.invert ? -1 : 1);

        // Forward velocity along the travel direction (cached sin/cos —
        // angle is invariant for the lifetime of a parametric projectile).
        float forwardX = this.sinAngle * this.magnitude * bulletScale;
        float forwardY = this.cosAngle * this.magnitude * bulletScale;

        // Perpendicular direction (90 degrees from forward).
        float perpX = this.cosAngle;
        float perpY = -this.sinAngle;

        // Combine forward motion + perpendicular oscillation
        float velX = forwardX + perpX * perpDelta;
        float velY = forwardY + perpY * perpDelta;

        // Decrease range by forward distance only (not oscillation)
        this.range -= this.magnitude * bulletScale;

        this.pos.addX(velX);
        this.pos.addY(velY);
        this.dx = velX;
        this.dy = velY;
    }

    /**
     * Orbital projectile update — positions the bullet on a circle around orbitCenter.
     * Uses frequency as angular speed (degrees/tick) and amplitude as orbit radius.
     * The initial angle for each projectile in the ring is set via orbitPhase.
     */
    public void updateOrbital(float bulletScale) {
        this.orbitPhase += (float) Math.toRadians(this.frequency * bulletScale);
        float newX = this.orbitCenterX + this.orbitRadius * (float) Math.cos(this.orbitPhase);
        float newY = this.orbitCenterY + this.orbitRadius * (float) Math.sin(this.orbitPhase);
        this.dx = newX - this.pos.x;
        this.dy = newY - this.pos.y;
        this.pos.x = newX;
        this.pos.y = newY;
        // Decrease range by arc length traveled per tick
        this.range -= this.orbitRadius * Math.abs(Math.toRadians(this.frequency * bulletScale));
    }

    /**
     * Configure this bullet as an orbital projectile.
     * @param centerX orbit center X
     * @param centerY orbit center Y
     * @param radius orbit radius in pixels
     * @param startPhase starting angle in radians (evenly spaced for ring patterns)
     */
    public void setupOrbital(float centerX, float centerY, float radius, float startPhase) {
        this.orbitCenterX = centerX;
        this.orbitCenterY = centerY;
        this.orbitRadius = radius;
        this.orbitPhase = startPhase;
        // Set initial position on the orbit
        this.pos.x = centerX + radius * (float) Math.cos(startPhase);
        this.pos.y = centerY + radius * (float) Math.sin(startPhase);
    }

    /**
     * ANCHORED follow: derive the spawn-time offset from the source entity's
     * top-left on first sight, then snap to it each tick so the wall tracks the
     * moving enemy. Matches the server's anchorTo geometry.
     */
    public void anchorFollow(float sourceTopLeftX, float sourceTopLeftY) {
        if (!this.anchorReady) {
            this.anchorOffsetX = this.pos.x - sourceTopLeftX;
            this.anchorOffsetY = this.pos.y - sourceTopLeftY;
            this.anchorReady = true;
            return;
        }
        this.pos.x = sourceTopLeftX + this.anchorOffsetX;
        this.pos.y = sourceTopLeftY + this.anchorOffsetY;
    }

    /** Exponential speed multiplier over lifetime — must match the server. */
    public float speedCurveMult() {
        final float lifeMs = (this.lifetimeTicks > 0 ? this.lifetimeTicks : 192) * 1000f / 64f;
        float p = (Instant.now().toEpochMilli() - this.createdTime) / lifeMs;
        if (p < 0f) p = 0f; else if (p > 1f) p = 1f;
        final float k = this.frequency > 0 ? this.frequency : 4f;
        if (this.hasFlag(ProjectileFlag.SPEED_RAMP)) {
            return (float) ((Math.exp(k * p) - 1.0) / (Math.exp(k) - 1.0));
        }
        return (float) ((Math.exp(-k * p) - Math.exp(-k)) / (1.0 - Math.exp(-k)));
    }

    @Override
    public void render(SpriteBatch batch) {
        if (this.getSpriteSheet() == null) return;
        TextureRegion frame = this.getSpriteSheet().getCurrentFrame();
        if (frame == null) return;

        final ProjectileGroup group = GameDataManager.PROJECTILE_GROUPS.get(this.getProjectileId());
        final float angleOffset = Float.parseFloat(group.getAngleOffset());

        // Convert angle to degrees for LibGDX (counter-clockwise positive).
        // Spinning projectiles (shurikens) ignore the flight-angle and rotate
        // by a continuous wall-clock-driven phase so they read as spinning
        // blades regardless of flight direction.
        float rotationDeg;
        if (group.isSpinning()) {
            // ~6 rad/s — matches webclient renderer (Date.now() * 0.006).
            final double phase = (System.currentTimeMillis() * 0.006) % (Math.PI * 2);
            rotationDeg = (float) Math.toDegrees(phase);
        } else if (angleOffset > 0.0f) {
            rotationDeg = (float) Math.toDegrees(-this.getAngle() + (this.tfAngle + angleOffset));
        } else {
            rotationDeg = (float) Math.toDegrees(-this.getAngle() + this.tfAngle);
        }

        float wx = this.pos.getWorldVar().x;
        float wy = this.pos.getWorldVar().y;
        float halfSize = this.size / 2f;

        // LINE_SEGMENT wall: tile the sprite along the axis perpendicular to the
        // facing angle (matching the server's lineHit geometry) instead of
        // drawing one centered sprite. size is the line thickness; length the
        // span. Drawn here and returned — no trail/outline for walls.
        if (this.hasFlag(ProjectileFlag.LINE_SEGMENT) && this.length > 0) {
            final float a = this.getAngle();
            final float perpX = (float) Math.cos(a);
            final float perpY = (float) -Math.sin(a);
            final float half = this.length * 0.5f;
            final int tiles = Math.max(1, Math.round(this.length / (float) this.size));
            // Align tiles to the wall axis so they form a straight line (and stay
            // aligned as a spinning wall rotates). The group angleOffset is NOT
            // applied — for a wall it just tilts the line off-axis.
            final float lineRotDeg = (float) Math.toDegrees(-a + this.tfAngle);
            for (int i = 0; i <= tiles; i++) {
                final float off = -half + ((float) i / tiles) * this.length;
                batch.draw(frame, wx + perpX * off, wy + perpY * off, halfSize, halfSize,
                        this.size, this.size, 1f, 1f, lineRotDeg);
            }
            return;
        }

        // Sticky afterimage trail (e.g. Trapper Tar Shot): fading tinted copies
        // of the sprite trailing back along the flight line. Drawn before the
        // body so the projectile stays on top. Only non-spinning straight
        // projectiles request a trail, so backward = -(sin,cos)*step is exact.
        final String trailColor = group.getTrailColor();
        if (trailColor != null) {
            final float[] rgb = parseTrailColor(trailColor);
            final float prev = batch.getPackedColor();
            final float spacing = this.size * 0.34f;
            for (int i = TRAIL_SEGMENTS; i >= 1; i--) {
                final float f = i / (float) TRAIL_SEGMENTS;
                final float a = 0.55f * (1f - f);
                final float seg = this.size * (1f - 0.12f * i);
                final float off = (this.size - seg) * 0.5f;
                final float tx = wx - this.sinAngle * spacing * i;
                final float ty = wy - this.cosAngle * spacing * i;
                batch.setColor(rgb[0], rgb[1], rgb[2], a);
                batch.draw(frame, tx + off, ty + off, seg / 2f, seg / 2f,
                        seg, seg, 1f, 1f, rotationDeg);
            }
            batch.setPackedColor(prev);
        }

        // draw with rotation around center
        batch.draw(frame, wx, wy, halfSize, halfSize, this.size, this.size, 1f, 1f, rotationDeg);
    }

    private static final int TRAIL_SEGMENTS = 5;

    /** Parse a "#RRGGBB" trail colour to normalized RGB; black on bad input. */
    private static float[] parseTrailColor(String hex) {
        try {
            final int rgb = Integer.parseInt(hex.startsWith("#") ? hex.substring(1) : hex, 16);
            return new float[] {
                ((rgb >> 16) & 0xFF) / 255f,
                ((rgb >> 8) & 0xFF) / 255f,
                (rgb & 0xFF) / 255f
            };
        } catch (NumberFormatException e) {
            return new float[] { 0f, 0f, 0f };
        }
    }

    private static final float OUTLINE_OFFSET = 1f;
    private static final float OUTLINE_ALPHA = 0.85f;

    /** Dark silhouette outline: four cardinal-offset tinted copies behind the
     *  bullet (web parity with the tile/entity outline). Caller draws the real
     *  sprite on top afterwards. */
    public void renderOutline(SpriteBatch batch) {
        if (this.getSpriteSheet() == null) return;
        // Walls draw their own tiled sprites in render() with no outline.
        if (this.hasFlag(ProjectileFlag.LINE_SEGMENT)) return;
        TextureRegion frame = this.getSpriteSheet().getCurrentFrame();
        if (frame == null) return;

        final ProjectileGroup group = GameDataManager.PROJECTILE_GROUPS.get(this.getProjectileId());
        final float angleOffset = Float.parseFloat(group.getAngleOffset());
        float rotationDeg;
        if (group.isSpinning()) {
            final double phase = (System.currentTimeMillis() * 0.006) % (Math.PI * 2);
            rotationDeg = (float) Math.toDegrees(phase);
        } else if (angleOffset > 0.0f) {
            rotationDeg = (float) Math.toDegrees(-this.getAngle() + (this.tfAngle + angleOffset));
        } else {
            rotationDeg = (float) Math.toDegrees(-this.getAngle() + this.tfAngle);
        }
        final float wx = this.pos.getWorldVar().x;
        final float wy = this.pos.getWorldVar().y;
        final float halfSize = this.size / 2f;
        final float prev = batch.getPackedColor();
        batch.setColor(0f, 0f, 0f, OUTLINE_ALPHA);
        batch.draw(frame, wx + OUTLINE_OFFSET, wy, halfSize, halfSize, this.size, this.size, 1f, 1f, rotationDeg);
        batch.draw(frame, wx - OUTLINE_OFFSET, wy, halfSize, halfSize, this.size, this.size, 1f, 1f, rotationDeg);
        batch.draw(frame, wx, wy + OUTLINE_OFFSET, halfSize, halfSize, this.size, this.size, 1f, 1f, rotationDeg);
        batch.draw(frame, wx, wy - OUTLINE_OFFSET, halfSize, halfSize, this.size, this.size, 1f, 1f, rotationDeg);
        batch.setPackedColor(prev);
    }
}
