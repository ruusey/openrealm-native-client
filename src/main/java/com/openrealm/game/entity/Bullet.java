package com.openrealm.game.entity;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.openrealm.game.contants.ProjectileFlag;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.graphics.SpriteOutline;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.Projectile;
import com.openrealm.game.model.ProjectileGroup;
import com.openrealm.game.model.ProjectileFx;

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

    /** Movement/behavior flags (ProjectileFlag IDs) — NOT on-hit effects. */
    private List<Short> flags;
    /** On-hit status effects — NOT behavior flags (those go in {@link #flags}). */
    private List<ProjectileEffect> effects;

    private boolean invert = false;

    private long timeStep = 0;
    private short amplitude = 4;
    private short frequency = 25;

    private float orbitCenterX;
    private float orbitCenterY;
    private float orbitRadius;
    private float orbitPhase;

    // LINE_SEGMENT wall: length (px) extends perpendicular to the facing angle,
    // size is thickness. lifetimeTicks forces expiry of a static (magnitude 0)
    // wall whose range never decrements.
    private short length;
    private int lifetimeTicks;
    private long targetEntityId;
    private float homingAccum;
    // ANCHORED follow: bullet top-left offset from the source entity top-left,
    // derived once at first sight so the wall tracks the moving enemy.
    private boolean anchorReady;
    private float anchorOffsetX;
    private float anchorOffsetY;

    private long createdTime;
    private long createdTick;
    /** Cached sin/cos of {@link #angle}; orbital bullets don't read these. */
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

    /** Recompute cached sin/cos after {@link #angle} is set. */
    public void cacheAngle() {
        this.sinAngle = (float) Math.sin(this.angle);
        this.cosAngle = (float) Math.cos(this.angle);
    }

    public void setAngle(float angle) {
        this.angle = angle;
        cacheAngle();
    }

    /** Client hit-prediction marker: renderer skips consumed bullets so the
     *  sprite vanishes on hit, but the entry stays in the realm map until the
     *  server's UnloadPacket — removing it locally fought the LoadPacket diff,
     *  which re-added the bullet at a stale pos ("frozen" projectiles). */
    private transient boolean consumedClient = false;
    private transient float fxTrailAcc = 0f;
    /** Owner-status driven (IMBUED_POISON), not a group property, so it toggles with the buff. */
    private transient boolean poisonTrail = false;

    public boolean isConsumedClient() { return this.consumedClient; }
    public void setConsumedClient(boolean v) { this.consumedClient = v; }
    public boolean isPoisonTrail() { return this.poisonTrail; }
    public void setPoisonTrail(boolean v) { this.poisonTrail = v; }

    /** Client-predicted player bullet; the server's broadcast is dedup'd
     *  against these in handleLoadClient instead of inserted alongside. */
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

    // 10-second lifetime ceiling (640 ticks @ 64Hz).
    private static final long MAX_LIFETIME_TICKS = 640L;

    public boolean remove() {
        return this.range <= 0.0;
    }

    /** Tick-counter aware expiry — pass the realm's current tickCounter. */
    public boolean remove(long currentTick) {
        // lifetimeTicks and range both apply (whichever hits first). Client
        // bullets carry no createdTick (currentTick 0), so fall back to
        // createdTime at 64/s.
        if (this.lifetimeTicks > 0) {
            final boolean lifeUp = (this.createdTick != 0L)
                    ? (currentTick - this.createdTick) > this.lifetimeTicks
                    : (Instant.now().toEpochMilli() - this.createdTime) > (this.lifetimeTicks * 1000L / 64L);
            if (lifeUp) return true;
        }
        if (this.range <= 0.0) return true;
        // createdTick == 0: legacy bullet not spawned via the tick-aware path.
        if (this.createdTick != 0L) {
            return (currentTick - this.createdTick) > MAX_LIFETIME_TICKS;
        }
        return ((Instant.now().toEpochMilli()) - this.createdTime) > 10000L;
    }

    public short getDamage() {
        return this.damage;
    }

    @Override
    // Legacy no-arg entry point; the realm tick precomputes bulletScale and calls update(float).
    public void update() {
        final long now = System.nanoTime();
        final float dt = Math.min((now - this.lastUpdateNanos) / 1_000_000_000.0f, 0.1f);
        this.lastUpdateNanos = now;
        update(dt * 64.0f);
    }

    /** Hot path — bulletScale is computed once per tick at the realm level. */
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
     * Sinusoidal oscillation perpendicular to travel (wavy staff shots). Applies
     * the CHANGE in offset per tick, not a raw velocity; negative amplitude
     * inverts the wave. Perpendicular of forward (sin,cos) is (cos,-sin).
     */
    public void updateParametric(float bulletScale) {
        // Perpendicular offset computed BEFORE advancing timeStep.
        float prevOffset = (float) (this.amplitude * Math.sin(Math.toRadians(this.timeStep)));

        this.timeStep = (long) ((this.timeStep + this.frequency * bulletScale) % 360);

        float currOffset = (float) (this.amplitude * Math.sin(Math.toRadians(this.timeStep)));
        float perpDelta = (currOffset - prevOffset) * (this.invert ? -1 : 1);

        float forwardX = this.sinAngle * this.magnitude * bulletScale;
        float forwardY = this.cosAngle * this.magnitude * bulletScale;

        float perpX = this.cosAngle;
        float perpY = -this.sinAngle;

        float velX = forwardX + perpX * perpDelta;
        float velY = forwardY + perpY * perpDelta;

        // Range decreases by forward distance only, not oscillation.
        this.range -= this.magnitude * bulletScale;

        this.pos.addX(velX);
        this.pos.addY(velY);
        this.dx = velX;
        this.dy = velY;
    }

    /** Orbital: frequency is angular speed (deg/tick), amplitude is orbit radius. */
    public void updateOrbital(float bulletScale) {
        this.orbitPhase += (float) Math.toRadians(this.frequency * bulletScale);
        float newX = this.orbitCenterX + this.orbitRadius * (float) Math.cos(this.orbitPhase);
        float newY = this.orbitCenterY + this.orbitRadius * (float) Math.sin(this.orbitPhase);
        this.dx = newX - this.pos.x;
        this.dy = newY - this.pos.y;
        this.pos.x = newX;
        this.pos.y = newY;
        this.range -= this.orbitRadius * Math.abs(Math.toRadians(this.frequency * bulletScale));
    }

    public void setupOrbital(float centerX, float centerY, float radius, float startPhase) {
        this.orbitCenterX = centerX;
        this.orbitCenterY = centerY;
        this.orbitRadius = radius;
        this.orbitPhase = startPhase;
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

    /** HOMING: rotate facing toward target center by at most maxTurnRad — matches the server. */
    public void steerToward(float targetCenterX, float targetCenterY, float maxTurnRad) {
        final float bcx = this.pos.x + this.getSize() * 0.5f;
        final float bcy = this.pos.y + this.getSize() * 0.5f;
        final float desired = (float) Math.atan2(targetCenterX - bcx, targetCenterY - bcy);
        float diff = desired - this.angle;
        while (diff > Math.PI) diff -= (float) (2 * Math.PI);
        while (diff < -Math.PI) diff += (float) (2 * Math.PI);
        if (diff > maxTurnRad) diff = maxTurnRad;
        else if (diff < -maxTurnRad) diff = -maxTurnRad;
        this.angle += diff;
        cacheAngle();
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
        // Melee swings are invisible AoEs — the swing animation stands in for them.
        if (this.hasFlag(ProjectileFlag.MELEE_SWING)) return;
        if (this.getSpriteSheet() == null) return;
        TextureRegion frame = this.getSpriteSheet().getCurrentFrame();
        if (frame == null) return;

        // group is null for sprite-override bullets with no group (or on data
        // load failure); rotation/spin/trail fall back to neutral defaults.
        final ProjectileGroup group = GameDataManager.PROJECTILE_GROUPS != null
                ? GameDataManager.PROJECTILE_GROUPS.get(this.getProjectileId()) : null;
        final float angleOffset = (group != null && group.getAngleOffset() != null)
                ? Float.parseFloat(group.getAngleOffset()) : 0f;

        // angleOffset undoes a diagonal sprite; LibGDX rotation is CCW-positive degrees.
        final float baseDeg = (angleOffset > 0.0f)
                ? (float) Math.toDegrees(-this.getAngle() + (this.tfAngle + angleOffset))
                : (float) Math.toDegrees(-this.getAngle() + this.tfAngle);
        // Projectile FX spin: continuous overrides the flight angle (shurikens),
        // additive spins on top. dir "CW/CCW" is visual; LibGDX CCW-positive so CW = negative.
        float rotationDeg = baseDeg;
        float rotateSpinDeg = 0f;
        if (group != null && group.getFx() != null) {
            for (final ProjectileFx fx : group.getFx()) {
                if (fx == null || !"spin".equals(fx.getType())) continue;
                final float rate = (fx.getRate() != null) ? fx.getRate() : 6f;
                final float dir = "CCW".equalsIgnoreCase(fx.getDir()) ? 1f : -1f;
                final float spinDeg = dir * (float) Math.toDegrees((System.currentTimeMillis() * 0.001) * rate);
                if ("continuous".equals(fx.getMode())) {
                    rotationDeg = spinDeg;
                } else {
                    rotateSpinDeg += spinDeg;
                    rotationDeg = baseDeg + rotateSpinDeg;
                }
            }
        }

        float wx = this.pos.getWorldVar().x;
        float wy = this.pos.getWorldVar().y;
        float halfSize = this.size / 2f;

        // LINE_SEGMENT wall: tile the sprite along the axis perpendicular to
        // facing (matches server lineHit). size = thickness, length = span. No
        // trail/outline for walls.
        if (this.hasFlag(ProjectileFlag.LINE_SEGMENT) && this.length > 0) {
            final float a = this.getAngle();
            final float perpX = (float) Math.cos(a);
            final float perpY = (float) -Math.sin(a);
            final float half = this.length * 0.5f;
            final int tiles = Math.max(1, Math.round(this.length / (float) this.size));
            // Tiles point ALONG the wall axis (no tfAngle — that aligns to travel).
            final float lineRotDeg = (float) Math.toDegrees(-a + angleOffset) + rotateSpinDeg;
            for (int i = 0; i <= tiles; i++) {
                final float off = -half + ((float) i / tiles) * this.length;
                batch.draw(frame, wx + perpX * off, wy + perpY * off, halfSize, halfSize,
                        this.size, this.size, 1f, 1f, lineRotDeg);
            }
            return;
        }

        // Sticky afterimage trail (e.g. Trapper Tar Shot): fading tinted copies
        // trailing back along the flight line, drawn before the body.
        final String trailColor = (group != null) ? group.getTrailColor() : null;
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

        // Venom trail (Assassin Imbue Poison): group-trail geometry keyed off the owner's buff.
        if (this.poisonTrail) {
            final float prev = batch.getPackedColor();
            final float spacing = this.size * 0.34f;
            for (int i = TRAIL_SEGMENTS; i >= 1; i--) {
                final float f = i / (float) TRAIL_SEGMENTS;
                final float a = 0.5f * (1f - f);
                final float seg = this.size * (1f - 0.12f * i);
                final float off = (this.size - seg) * 0.5f;
                final float tx = wx - this.sinAngle * spacing * i;
                final float ty = wy - this.cosAngle * spacing * i;
                batch.setColor(0.30f, 0.85f, 0.20f, a);
                batch.draw(frame, tx + off, ty + off, seg / 2f, seg / 2f,
                        seg, seg, 1f, 1f, rotationDeg);
            }
            batch.setPackedColor(prev);
        }

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

    /** Dark silhouette outline: 8 tinted copies (4 cardinal + 4 diagonal)
     *  behind the bullet; caller draws the real sprite on top. */
    public void renderOutline(SpriteBatch batch) {
        if (this.getSpriteSheet() == null) return;
        if (this.hasFlag(ProjectileFlag.MELEE_SWING)) return;
        // Walls draw their own tiled sprites in render() with no outline.
        if (this.hasFlag(ProjectileFlag.LINE_SEGMENT)) return;
        TextureRegion frame = this.getSpriteSheet().getCurrentFrame();
        if (frame == null) return;

        final ProjectileGroup group = GameDataManager.PROJECTILE_GROUPS != null
                ? GameDataManager.PROJECTILE_GROUPS.get(this.getProjectileId()) : null;
        final float angleOffset = (group != null && group.getAngleOffset() != null)
                ? Float.parseFloat(group.getAngleOffset()) : 0f;
        float rotationDeg;
        if (group != null && group.isSpinning()) {
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
        SpriteOutline.drawOutline(batch, frame, wx, wy, halfSize, halfSize, this.size, this.size,
                1f, 1f, rotationDeg, OUTLINE_OFFSET, OUTLINE_ALPHA);
    }
}
