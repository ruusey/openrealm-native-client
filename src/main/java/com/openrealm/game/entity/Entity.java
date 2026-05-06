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

    public void addEffect(StatusEffectType effect, long duration) {
        // Sentinel: Long.MAX_VALUE duration = permanent effect (never expires).
        // Computing now + Long.MAX_VALUE would overflow into a negative value
        // and get removed on the next tick, so store it directly.
        final long expireTime = (duration == Long.MAX_VALUE)
                ? Long.MAX_VALUE
                : Instant.now().toEpochMilli() + duration;

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
    private float animDistance = 0f;
    private int animFrame = 0;

    public void update(double time) {
        final SpriteSheet sheet = this.getSpriteSheet();
        if (sheet == null) return;

        // Frame-rate independent dt from libgdx, capped to avoid massive
        // jumps after a paused window.
        final float dt = Gdx.graphics != null
                ? Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f)
                : 1f / 60f;

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
            if (this.attacking) {
                // Attack animation based on mouse/aim direction, not movement
                float screenCenterX = this.pos.getWorldVar().x + this.size / 2f;
                float screenCenterY = this.pos.getWorldVar().y + this.size / 2f;
                float relX = this.aimX - screenCenterX;
                float relY = this.aimY - screenCenterY;
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
        // 1 sprite-pixel of padding == size / regionWidth world units per side.
        final int rw = frame.getRegionWidth();
        final int rh = frame.getRegionHeight();
        if (rw <= 0 || rh <= 0) return;
        final float padX = (float) this.size / rw;
        final float padY = (float) this.size / rh;
        final float wx = this.pos.getWorldVar().x;
        final float wy = this.pos.getWorldVar().y;
        if (this.left) {
            batch.draw(frame, wx + this.size + padX, wy - padY,
                    -(this.size + 2 * padX), this.size + 2 * padY);
        } else {
            batch.draw(frame, wx - padX, wy - padY,
                    this.size + 2 * padX, this.size + 2 * padY);
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
        if (this.left) {
            batch.draw(frame, wx + this.size, wy, -this.size, this.size);
        } else {
            batch.draw(frame, wx, wy, this.size, this.size);
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
}
