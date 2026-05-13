package com.openrealm.game.entity;

import java.time.Instant;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.graphics.Sprite;
import com.openrealm.game.graphics.SpriteSheet;
import com.openrealm.game.math.Rectangle;
import com.openrealm.game.math.Vector2f;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public abstract class Entity extends GameObject {
    protected boolean up = false;
    protected boolean down = false;
    protected boolean right = false;
    protected boolean left = false;
    protected boolean attack = false;
    protected String lastAnimSet = "idle_side";
    protected String lastMovementDirection = "side"; // "side", "front", or "back" - used for hysteresis
    private static final float DIRECTION_SWITCH_THRESHOLD = 0.15f;

    public boolean xCol = false;
    public boolean yCol = false;

    protected int attackSpeed = 1050;
    protected int attackDuration = 650;
    protected double attacktime;
    protected boolean canAttack = true;
    protected boolean attacking = false;
    /** Epoch millis until which the entity is considered "attacking" for animation. */
    protected long attackingUntil = 0;
    /** Duration in ms that the attacking flag stays true after a shot. */
    private static final long ATTACK_ANIM_DURATION_MS = 350;
    protected float aimX = 0;
    protected float aimY = 0;

    public int health = 100;
    public int mana = 100;
    public float healthpercent = 1;
    public float manapercent = 1;

    protected Rectangle hitBounds;

    private Short[] effectIds;
    private Long[] effectTimes;

    public Entity(long id, Vector2f origin, int size) {
        super(id, origin, size);
        this.hitBounds = new Rectangle(origin, size, size);
        this.resetEffects();
    }

    public void removeEffect(short effectId) {
        for (int i = 0; i < this.effectIds.length; i++) {
            if (this.effectIds[i] == effectId) {
                this.effectIds[i] = (short) -1;
                this.effectTimes[i] = (long) -1;
            }
        }
    }

    public void removeExpiredEffects() {
        for (int i = 0; i < this.effectIds.length; i++) {
            if (this.effectIds[i] != -1) {
                if (Instant.now().toEpochMilli() > this.effectTimes[i]) {
                    this.effectIds[i] = (short) -1;
                    this.effectTimes[i] = (long) -1;
                }
            }
        }
    }

    public boolean hasEffect(StatusEffectType effect) {
        if (this.effectIds == null)
            return false;
        for (int i = 0; i < this.effectIds.length; i++) {
            if (this.effectIds[i] == effect.effectId)
                return true;
        }
        return false;
    }

    public boolean hasNoEffects() {
        for (int i = 0; i < this.effectIds.length; i++) {
            if (this.effectIds[i] > -1)
                return false;
        }
        return true;
    }

    public void resetEffects() {
        this.effectIds = new Short[] { -1, -1, -1, -1, -1, -1, -1, -1 };
        this.effectTimes = new Long[] { -1l, -1l, -1l, -1l, -1l, -1l, -1l, -1l };
    }

    /** Set of status effect ids that are considered debuffs — used by the
     *  WARDED / VULNERABLE gates in addEffect. Beneficial statuses (HEALING,
     *  SPEEDY, INVINCIBLE, PROTECTED, BRACED, ARMORED, MANA_FOUNT, etc.) are
     *  NOT in this set so they apply normally even on warded targets. */
    private static final java.util.Set<Short> DEBUFF_IDS = java.util.Set.of(
            StatusEffectType.PARALYZED.effectId,
            StatusEffectType.STUNNED.effectId,
            StatusEffectType.DAZED.effectId,
            StatusEffectType.CURSED.effectId,
            StatusEffectType.POISONED.effectId,
            StatusEffectType.SLOWED.effectId,
            StatusEffectType.ARMOR_BROKEN.effectId,
            StatusEffectType.WEAKEN.effectId,
            StatusEffectType.BLIND.effectId,
            StatusEffectType.VULNERABLE.effectId,
            StatusEffectType.GROUNDED.effectId
    );

    private boolean hasEffectId(short id) {
        if (this.effectIds == null) return false;
        for (int i = 0; i < this.effectIds.length; i++) {
            if (this.effectIds[i] == id) {
                final long end = this.effectTimes[i];
                if (end == Long.MAX_VALUE || end > Instant.now().toEpochMilli()) return true;
            }
        }
        return false;
    }

    public void addEffect(StatusEffectType effect, long duration) {
        // WARDED — silently drop any incoming debuff. Beneficial statuses
        // (heals, speed buffs) still apply because they're not in DEBUFF_IDS.
        if (DEBUFF_IDS.contains(effect.effectId)
                && hasEffectId(StatusEffectType.WARDED.effectId)) {
            return;
        }
        // GROUNDED auto-applies SLOWED for the same duration — the debuff's
        // "movement lock + can't dash" semantics need both flags.
        if (effect == StatusEffectType.GROUNDED) {
            addEffect(StatusEffectType.SLOWED, duration);
        }
        // VULNERABLE — incoming debuffs get DOUBLE duration.
        long effDuration = duration;
        if (DEBUFF_IDS.contains(effect.effectId)
                && hasEffectId(StatusEffectType.VULNERABLE.effectId)
                && duration != Long.MAX_VALUE) {
            effDuration = duration * 2L;
        }
        // Sentinel: Long.MAX_VALUE duration = permanent effect (never expires).
        // Computing now + Long.MAX_VALUE would overflow into a negative value
        // and get removed on the next tick, so store it directly.
        final long expireTime = (effDuration == Long.MAX_VALUE)
                ? Long.MAX_VALUE
                : Instant.now().toEpochMilli() + effDuration;

        // POISONED stacks — always add a new slot (multiple poisons tick independently)
        if (effect == StatusEffectType.POISONED) {
            for (int i = 0; i < this.effectIds.length; i++) {
                if (this.effectIds[i] == -1) {
                    this.effectIds[i] = effect.effectId;
                    this.effectTimes[i] = expireTime;
                    return;
                }
            }
            return;
        }

        // All other effects: refresh duration if already present, otherwise add to empty slot
        for (int i = 0; i < this.effectIds.length; i++) {
            if (this.effectIds[i] == effect.effectId) {
                // Refresh: extend to whichever expires later
                if (expireTime > this.effectTimes[i]) {
                    this.effectTimes[i] = expireTime;
                }
                return;
            }
        }
        // Not found — add to first empty slot
        for (int i = 0; i < this.effectIds.length; i++) {
            if (this.effectIds[i] == -1) {
                this.effectIds[i] = effect.effectId;
                this.effectTimes[i] = expireTime;
                return;
            }
        }
    }

    public boolean getDeath() {
        return this.health <= 0;
    }

    public int getDirection() {
        if ((this.isUp()) || (this.isLeft()))
            return 1;
        return -1;
    }

    /**
     * Mark this entity as attacking for ATTACK_ANIM_DURATION_MS.
     * Used by the server when a player shoots to broadcast the attack
     * animation state to other clients via ObjectMovePacket.
     */
    public void triggerAttackAnimation() {
        this.attackingUntil = System.currentTimeMillis() + ATTACK_ANIM_DURATION_MS;
        this.attacking = true;
    }

    /**
     * Override Lombok's isAttacking() — also checks the timer-based flag
     * set by triggerAttackAnimation() for network-broadcast attack state.
     */
    public boolean isAttacking() {
        if (this.attackingUntil > 0 && System.currentTimeMillis() > this.attackingUntil) {
            this.attacking = false;
            this.attackingUntil = 0;
        }
        return this.attacking;
    }

    /**
     * Walk cycle driven by pixels traveled, NOT elapsed time. Direct port
     * of the web client's loop in game.js around line 1290:
     * <pre>
     *   pace = sqrt(dx*dx + dy*dy);                 // px / tick (64Hz)
     *   if (pace &gt; 0.1) {
     *     animDistance += pace * 64 * dt;            // px / sec
     *     while (animDistance &gt; PX_PER_FRAME) {
     *       animDistance -= PX_PER_FRAME;
     *       animFrame = (animFrame + 1) % 2;
     *     }
     *   } else {
     *     animDistance = 0;                          // reset on stop
     *   }
     * </pre>
     * One full 2-frame leg-swap cycle covers 1.5 tiles regardless of
     * actual speed, which is why the web client looks natural at every
     * SPD value. The previous time-based SpriteSheet.animate() (which
     * just incremented a counter every render frame) cycled WAY too
     * fast at 144 FPS because it had no relation to actual locomotion.
     */
    /**
     * Pixels of motion required per walk-frame swap. Web client uses 24
     * with a 2-frame cycle (one stride per 48 px ≈ 1.5 tiles). Native
     * spritesheets often have 4-frame walk cycles, so 24 px would swap
     * 4 sprites per 1.5 tiles — visibly twice as fast as the web's gait.
     * Bumped to 48 so a single full stride covers 4*48 = 192 px ≈ 6
     * tiles, matching the perceived pace of the web client at any FPS.
     */
    private static final float PX_PER_FRAME = 48f;
    /** Webclient main.js ~2160: 80ms per attack frame. */
    private static final float ATTACK_FRAME_SECONDS = 0.08f;
    private float animDistance = 0f;
    private int animFrame = 0;
    private float attackFrameTimer = 0f;
    private int attackFrame = 0;

    public void update(double time) {
        final SpriteSheet sheet = this.getSpriteSheet();
        if (sheet == null) return;

        // Frame-rate independent dt from libgdx, capped to avoid massive
        // jumps after a paused window.
        final float dt = Gdx.graphics != null
                ? Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f)
                : 1f / 60f;

        // WHY: attack frames advance on their OWN wall-clock timer, NOT on
        // movement pace. Webclient (main.js ~2158) cycles attackFrame every
        // 80ms while shootingAnim is set — independent of dx/dy. The native
        // previously routed every animation through the pace-driven path
        // below, so standing-still attacks stayed stuck on frame 0.
        // isAttacking() consults the timer-based attackingUntil flag.
        if (this.isAttacking()) {
            this.attackFrameTimer += dt;
            int frameCount = sheet.getFrameCount();
            if (frameCount < 1) frameCount = 1;
            while (this.attackFrameTimer >= ATTACK_FRAME_SECONDS) {
                this.attackFrameTimer -= ATTACK_FRAME_SECONDS;
                this.attackFrame = (this.attackFrame + 1) % frameCount;
            }
            sheet.setAnimationFrame(this.attackFrame);
            // Keep the walk accumulator fresh while attacking so motion that
            // resumes after the attack ends doesn't snap a stale frame.
            final float pace = (float) Math.sqrt(this.dx * this.dx + this.dy * this.dy);
            if (pace > 0.1f) {
                this.animDistance += pace * 64f * dt;
                int wfc = sheet.getFrameCount();
                if (wfc < 2) wfc = 2;
                while (this.animDistance > PX_PER_FRAME) {
                    this.animDistance -= PX_PER_FRAME;
                    this.animFrame = (this.animFrame + 1) % wfc;
                }
            }
            return;
        }
        // Attack just ended — reset attack accumulators so next attack
        // starts on frame 0.
        this.attackFrame = 0;
        this.attackFrameTimer = 0f;

        final float pace = (float) Math.sqrt(this.dx * this.dx + this.dy * this.dy);
        if (pace > 0.1f) {
            this.animDistance += pace * 64f * dt;
            // 'while' rather than 'if' so a single big-dt frame still
            // advances the right number of frames (matches web behaviour
            // where multiple PX_PER_FRAME steps can fire in one rAF if
            // the entity moved especially fast).
            int frameCount = sheet.getFrameCount();
            if (frameCount < 2) frameCount = 2;
            while (this.animDistance > PX_PER_FRAME) {
                this.animDistance -= PX_PER_FRAME;
                this.animFrame = (this.animFrame + 1) % frameCount;
            }
        } else {
            // Reset accumulator + park on idle frame 0 when stationary
            // so the next step doesn't fire instantly from leftover
            // travel and idle pose stays consistent.
            this.animDistance = 0f;
            this.animFrame = 0;
        }

        // Drive the visual frame directly. SpriteSheet.animate()'s old
        // time-based stepping is no longer called for entities — this
        // method now owns the frame index.
        sheet.setAnimationFrame(this.animFrame);
    }

    public void updateAnimation() {
        if (this.dx > 0) {
            this.right = true;
        } else if (this.dx < 0) {
            this.left = true;
        } else {
            this.right = false;
            this.left = false;
        }

        if (this.dy > 0) {
            this.down = true;
        } else if (this.dy < 0) {
            this.up = true;
        } else {
            this.down = false;
            this.up = false;
        }

        // Select animation set based on movement state with direction hysteresis
        if (this.getSpriteSheet() != null && this.getSpriteSheet().hasAnimSets()) {
            String targetAnim;
            if (this.isAttacking()) {
                // WHY: compare aim and player center in the SAME (world) space.
                // PlayState now stores aimX/Y in world coords; player center is
                // pos + size/2 (world coords). Previous code mixed screen-pixel
                // aim with world-relative center which inverted the sign of
                // relX/relY when the cursor sat near the player's on-screen
                // position, picking the wrong attack direction.
                float worldCenterX = this.pos.x + this.size / 2f;
                float worldCenterY = this.pos.y + this.size / 2f;
                float relX = this.aimX - worldCenterX;
                float relY = this.aimY - worldCenterY;
                // Determine if aim is more horizontal or vertical
                if (Math.abs(relX) > Math.abs(relY)) {
                    targetAnim = "attack_side";
                } else if (relY > 0) {
                    targetAnim = "attack_down";
                } else {
                    targetAnim = "attack_up";
                }
                // Flip sprite based on horizontal aim direction
                if (relX < 0) {
                    this.left = true;
                    this.right = false;
                } else {
                    this.right = true;
                    this.left = false;
                }
            } else if ((this.left || this.right) && (this.up || this.down)) {
                // Diagonal movement: use hysteresis to prevent rapid animation switching.
                float absDx = Math.abs(this.dx);
                float absDy = Math.abs(this.dy);
                if ("side".equals(this.lastMovementDirection)) {
                    if (absDy > absDx * (1.0f + DIRECTION_SWITCH_THRESHOLD)) {
                        this.lastMovementDirection = this.dy < 0 ? "back" : "front";
                    }
                } else {
                    if (absDx > absDy * (1.0f + DIRECTION_SWITCH_THRESHOLD)) {
                        this.lastMovementDirection = "side";
                    } else {
                        // Vertical still dominates — update up/front based on current dy
                        this.lastMovementDirection = this.dy < 0 ? "back" : "front";
                    }
                }
                targetAnim = getWalkAnim(this.lastMovementDirection);
            } else if (this.left || this.right) {
                this.lastMovementDirection = "side";
                targetAnim = "walk_side";
            } else if (this.up || this.down) {
                this.lastMovementDirection = this.dy < 0 ? "back" : "front";
                targetAnim = getWalkAnim(this.lastMovementDirection);
            } else {
                // Idle: keep facing the same direction as last movement
                targetAnim = getIdleAnim(this.lastMovementDirection);
            }
            this.lastAnimSet = targetAnim;
            this.getSpriteSheet().setAnimSet(targetAnim);
        }
    }

    private static String getWalkAnim(String direction) {
        switch (direction) {
            case "back": return "walk_back";
            case "front": return "walk_front";
            default: return "walk_side";
        }
    }

    private static String getIdleAnim(String direction) {
        switch (direction) {
            case "back": return "idle_back";
            case "front": return "idle_front";
            default: return "idle_side";
        }
    }

    /**
     * Update the sprite sheet's visual effect based on active status effects.
     * Override in subclasses for class-specific effect mappings.
     */
    public void updateEffectState() {
        // Default: no effect mapping. Subclasses override.
    }

    /**
     * Outline pass: shader-based 1px black outline (PixiJS OutlineFilter
     * equivalent). Caller is responsible for setting/clearing the outline
     * shader around a batched run of these calls.
     *
     * The quad is enlarged by ~1 sprite-pixel on each side so the new
     * fragments outside the original geometry edge get filled by the
     * shader's neighbor-sampling logic.
     */
    public void renderOutline(SpriteBatch batch) {
        if (this.getSpriteSheet() == null) return;
        final TextureRegion frame = this.getSpriteSheet().getCurrentFrame();
        if (frame == null) return;
        // Match the body draw rect (see renderBody for anchor convention) so
        // the outline stays aligned with wide / tall attack frames; expand by
        // 1 source-pixel of padding for the outline shader's neighbor sample.
        final int refW = this.getSpriteSheet().getSpriteImageWidth();
        final int refH = this.getSpriteSheet().getSpriteImageHeight();
        final int rw = frame.getRegionWidth();
        final int rh = frame.getRegionHeight();
        if (rw <= 0 || rh <= 0 || refW <= 0 || refH <= 0) return;
        final float unitX = (float) this.size / refW;
        final float unitY = (float) this.size / refH;
        final float drawW = rw * unitX;
        final float drawH = rh * unitY;
        final float padX = unitX;       // 1 source-pixel
        final float padY = unitY;
        final float wx = this.pos.getWorldVar().x;
        final float wy = this.pos.getWorldVar().y;
        final float drawY = wy + this.size - drawH;
        if (this.left) {
            batch.draw(frame, wx + this.size + padX, drawY - padY,
                    -(drawW + 2 * padX), drawH + 2 * padY);
        } else {
            batch.draw(frame, wx - padX, drawY - padY,
                    drawW + 2 * padX, drawH + 2 * padY);
        }
    }

    /** Returns the current visible texture region (or null), used by the
     *  outline pass to set per-region UV bounds on the outline shader. */
    public TextureRegion getCurrentFrame() {
        if (this.getSpriteSheet() == null) return null;
        return this.getSpriteSheet().getCurrentFrame();
    }

    /**
     * Draw only the main sprite body with its current effect.
     * Called during the batched body pass (caller manages shader).
     */
    public void renderBody(SpriteBatch batch) {
        if (this.getSpriteSheet() == null) return;
        TextureRegion frame = this.getSpriteSheet().getCurrentFrame();
        if (frame == null) return;
        float wx = this.pos.getWorldVar().x;
        float wy = this.pos.getWorldVar().y;
        // Scale the draw rect by the FRAME's region size relative to the
        // sheet's reference cell, so a "wide attack" frame extends past the
        // body's right edge instead of being squished into a square. Anchor
        // convention: body occupies the bottom-left of the frame, so wider
        // frames extend RIGHT (mirrored when facing left) and taller frames
        // extend UP. Matches the RotMG-style sheet authoring assumption.
        final int refW = this.getSpriteSheet().getSpriteImageWidth();
        final int refH = this.getSpriteSheet().getSpriteImageHeight();
        final int rw = frame.getRegionWidth();
        final int rh = frame.getRegionHeight();
        if (refW <= 0 || refH <= 0 || rw <= 0 || rh <= 0) {
            // Defensive: if the sheet is mid-load, fall back to the legacy
            // square draw rather than zero-sizing.
            if (this.left) batch.draw(frame, wx + this.size, wy, -this.size, this.size);
            else           batch.draw(frame, wx, wy, this.size, this.size);
            return;
        }
        final float unitX = (float) this.size / refW;
        final float unitY = (float) this.size / refH;
        final float drawW = rw * unitX;
        final float drawH = rh * unitY;
        // Body bottom stays at wy + size regardless of frame height.
        final float drawY = wy + this.size - drawH;
        if (this.left) {
            // Mirror: right edge of body stays at wx + size.
            batch.draw(frame, wx + this.size, drawY, -drawW, drawH);
        } else {
            batch.draw(frame, wx, drawY, drawW, drawH);
        }
    }

    /**
     * Returns the current visual effect for this entity's sprite.
     */
    public Sprite.EffectEnum getCurrentEffect() {
        if (this.getSpriteSheet() == null) return Sprite.EffectEnum.NORMAL;
        return this.getSpriteSheet().getCurrentEffect();
    }

    @Override
    public abstract void render(SpriteBatch batch);

    /**
     * Lifecycle hook invoked when the entity is removed from a Realm
     * (death, despawn, viewport unload). Drops references to per-instance
     * heap state so the GC can reclaim it even if some short-lived closure
     * (e.g. an in-flight async attack callback, a packet handler iterator)
     * still pins the Entity for a few more ticks.
     *
     * <p>The shared {@link com.badlogic.gdx.graphics.Texture} the SpriteSheet
     * points at is owned by GameSpriteManager — never disposed here.
     * What we ARE freeing is the per-instance SpriteSheet wrapper itself
     * (TextureRegion[][] arrays, animSets HashMaps, Sprite lists), which
     * for a fully-loaded enemy adds up to a few KB of heap each.
     */
    public void onRemoved() {
        this.setSpriteSheet(null);
    }
}
