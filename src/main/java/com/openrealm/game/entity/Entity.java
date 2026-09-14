package com.openrealm.game.entity;

import java.time.Instant;
import java.util.Set;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.graphics.Sprite;
import com.openrealm.game.graphics.SpriteOutline;
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
    protected String lastMovementDirection = "side"; // "side" | "front" | "back", for hysteresis
    private static final float DIRECTION_SWITCH_THRESHOLD = 0.15f;
    // Sprite stroke: 8 dark copies (4 cardinal + 4 diagonal) behind the body. Offset in world units.
    private static final float STROKE_OFFSET = 1f;
    private static final float STROKE_ALPHA = 0.85f;

    public boolean xCol = false;
    public boolean yCol = false;

    protected int attackSpeed = 1050;
    protected int attackDuration = 650;
    protected double attacktime;
    protected boolean canAttack = true;
    protected boolean attacking = false;
    /** Epoch millis until which the entity is considered "attacking" for animation. */
    protected long attackingUntil = 0;
    private static final long ATTACK_ANIM_DURATION_MS = 350;
    protected float aimX = 0;
    protected float aimY = 0;
    /** aimX/aimY: cursor for the local player, last shot's firing angle for remotes. */
    protected boolean aimControlled = false;
    /** createdTime of the last projectile that drove the firing pose, so a
     *  multishot volley restarts the attack clip once, not per pellet. */
    protected long lastShotCreatedTime = 0;
    protected boolean wading = false;

    public int health = 100;
    public int mana = 100;
    public float healthpercent = 1;
    public float manapercent = 1;

    protected Rectangle hitBounds;

    private Short[] effectIds;
    private Long[] effectTimes;
    /** Per-effect stack count, parallel to effectIds (server-authoritative). >1
     *  only for stacked DOTs (poison/bleed); drives the "xN" badge on the
     *  overhead status icon. */
    private Short[] effectStacks;

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
            if (this.effectIds[i] != -1 && this.effectTimes[i] != -1) {
                // Movement-gating effects (paralyze, slow) clear ONLY on the
                // server's authoritative update — expiring them on the local
                // clock (skewed vs the absolute server timestamps) rubberbanded
                // the player. The reliable connection always delivers the clear.
                final short id = this.effectIds[i];
                if (id == StatusEffectType.PARALYZED.effectId
                        || id == StatusEffectType.SLOWED.effectId) continue;
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
        this.effectStacks = new Short[] { 1, 1, 1, 1, 1, 1, 1, 1 };
    }

    /** Stack count for an active effect (1 if not present / not stacked). Used
     *  by the overhead status-icon renderer to show a "xN" badge. */
    public int getEffectStackCount(StatusEffectType effect) {
        if (this.effectIds == null || this.effectStacks == null) return 1;
        for (int i = 0; i < this.effectIds.length; i++) {
            if (this.effectIds[i] == effect.effectId) {
                return Math.max(1, this.effectStacks[i]);
            }
        }
        return 1;
    }

    /** Status effect ids treated as debuffs by the WARDED / VULNERABLE gates in
     *  addEffect. Beneficial statuses are absent so they apply on warded targets. */
    private static final Set<Short> DEBUFF_IDS = Set.of(
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

    /** Mark attacking for ATTACK_ANIM_DURATION_MS. */
    public void triggerAttackAnimation() {
        this.attackingUntil = System.currentTimeMillis() + ATTACK_ANIM_DURATION_MS;
        this.attacking = true;
        // Restart the clip from frame 0 on every shot (webclient parity).
        this.attackFrame = 0;
        this.attackFrameTimer = 0f;
    }

    /** Remote-player variant: also point the attack pose along the firing angle. */
    public void triggerAttackAnimation(float fireAngle) {
        this.aimControlled = true;
        this.aimX = this.pos.x + this.size / 2f + (float) Math.sin(fireAngle) * 100f;
        this.aimY = this.pos.y + this.size / 2f + (float) Math.cos(fireAngle) * 100f;
        this.triggerAttackAnimation();
    }

    /** Overrides Lombok isAttacking() to also honor the timer-based flag. */
    public boolean isAttacking() {
        if (this.attackingUntil > 0 && System.currentTimeMillis() > this.attackingUntil) {
            this.attacking = false;
            this.attackingUntil = 0;
        }
        return this.attacking;
    }

    // Walk cycle is distance-based (a frame every WALK_PX_PER_FRAME px) so gait
    // scales with speed. MUST match the webclient's WALK_PX_PER_FRAME.
    private static final float WALK_PX_PER_FRAME = 28f;
    private static final float ATTACK_FRAME_SECONDS = 0.08f;
    private float animDistance = 0f;
    private int animFrame = 0;
    private float attackFrameTimer = 0f;
    private int attackFrame = 0;

    public void update(double time) {
        final SpriteSheet sheet = this.getSpriteSheet();
        if (sheet == null) return;

        // Frame-rate independent dt, capped to avoid jumps after a paused window.
        final float dt = Gdx.graphics != null
                ? Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f)
                : 1f / 60f;

        // Attack frames advance on their own wall-clock timer, NOT movement pace
        // (webclient parity); routing them through the pace path stuck
        // standing-still attacks on frame 0.
        if (this.isAttacking()) {
            this.attackFrameTimer += dt;
            int frameCount = sheet.getFrameCount();
            if (frameCount < 1) frameCount = 1;
            while (this.attackFrameTimer >= ATTACK_FRAME_SECONDS) {
                this.attackFrameTimer -= ATTACK_FRAME_SECONDS;
                // Clamp (not modulo): play the clip ONCE and hold the last frame
                // for the rest of the window; each shot resets attackFrame to 0.
                this.attackFrame = Math.min(this.attackFrame + 1, frameCount - 1);
            }
            sheet.setAnimationFrame(this.attackFrame);
            // Keep the walk accumulator advancing so resuming motion doesn't snap a stale frame.
            final float pace = (float) Math.sqrt(this.dx * this.dx + this.dy * this.dy);
            if (pace > 0.1f) {
                this.animDistance += pace * 64f * dt;
                int wfc = sheet.getFrameCount();
                if (wfc < 2) wfc = 2;
                while (this.animDistance >= WALK_PX_PER_FRAME) {
                    this.animDistance -= WALK_PX_PER_FRAME;
                    this.animFrame = (this.animFrame + 1) % wfc;
                }
            }
            return;
        }
        // Attack just ended — reset so the next one starts on frame 0.
        this.attackFrame = 0;
        this.attackFrameTimer = 0f;

        final float pace = (float) Math.sqrt(this.dx * this.dx + this.dy * this.dy);
        if (pace > 0.1f) {
            this.animDistance += pace * 64f * dt;
            // 'while' so a single big-dt frame advances the right count after a hitch.
            int frameCount = sheet.getFrameCount();
            if (frameCount < 2) frameCount = 2;
            while (this.animDistance >= WALK_PX_PER_FRAME) {
                this.animDistance -= WALK_PX_PER_FRAME;
                this.animFrame = (this.animFrame + 1) % frameCount;
            }
        } else {
            this.animDistance = 0f;
            this.animFrame = 0;
        }

        // This method owns the frame index; SpriteSheet.animate() time-stepping isn't used for entities.
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
                // Compare aim and player center in the SAME (world) space —
                // mixing screen-pixel aim with a world center inverted relX/relY.
                // Remote players broadcast no aim, so fall back to velocity.
                float relX, relY;
                if (this.aimControlled) {
                    float worldCenterX = this.pos.x + this.size / 2f;
                    float worldCenterY = this.pos.y + this.size / 2f;
                    relX = this.aimX - worldCenterX;
                    relY = this.aimY - worldCenterY;
                } else {
                    relX = this.dx;
                    relY = this.dy;
                }
                if (Math.abs(relX) > Math.abs(relY)) {
                    targetAnim = "attack_side";
                } else if (relY > 0) {
                    targetAnim = "attack_down";
                } else {
                    targetAnim = "attack_up";
                }
                if (relX < 0) {
                    this.left = true;
                    this.right = false;
                } else {
                    this.right = true;
                    this.left = false;
                }
            } else if ((this.left || this.right) && (this.up || this.down)) {
                // Diagonal: hysteresis prevents rapid animation switching.
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

    /** Update the sprite sheet's visual effect from active statuses; subclasses override. */
    public void updateEffectState() {
    }

    /**
     * Shader-based 1px outline pass. Caller sets/clears the outline shader
     * around a batched run. Quad enlarged by ~1 source-pixel so the shader's
     * neighbor sampling fills the new edge fragments.
     */
    public void renderOutline(SpriteBatch batch) {
        if (this.getSpriteSheet() == null) return;
        final TextureRegion frame = this.getSpriteSheet().getCurrentFrame();
        if (frame == null) return;
        // Match the body draw rect (see renderBody); pad 1 source-pixel for the shader sample.
        final int refW = this.getSpriteSheet().getSpriteImageWidth();
        final int refH = this.getSpriteSheet().getSpriteImageHeight();
        final int rw = frame.getRegionWidth();
        final int rh = frame.getRegionHeight();
        if (rw <= 0 || rh <= 0 || refW <= 0 || refH <= 0) return;
        final float unitX = (float) this.size / refW;
        final float unitY = (float) this.size / refH;
        final float drawW = rw * unitX;
        final float drawH = rh * unitY;
        final float padX = unitX;
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

    /** Current visible texture region (or null) for the outline shader's UV bounds. */
    public TextureRegion getCurrentFrame() {
        if (this.getSpriteSheet() == null) return null;
        return this.getSpriteSheet().getCurrentFrame();
    }

    /**
     * Dark silhouette stroke: offset copies behind the body. Sets its own dark
     * tint, so the caller must run this with the default (non-effect) shader.
     */
    public void renderStroke(SpriteBatch batch) {
        if (this.getSpriteSheet() == null) return;
        final TextureRegion frame = this.getSpriteSheet().getCurrentFrame();
        if (frame == null) return;
        final float wx = this.pos.getWorldVar().x;
        final float wy = this.pos.getWorldVar().y;
        final int refW = this.getSpriteSheet().getSpriteImageWidth();
        final int refH = this.getSpriteSheet().getSpriteImageHeight();
        final int rw = frame.getRegionWidth();
        final int rh = frame.getRegionHeight();
        final float drawW, drawH, drawX, drawY;
        if (refW <= 0 || refH <= 0 || rw <= 0 || rh <= 0) {
            drawW = this.size; drawH = this.size; drawX = wx; drawY = wy;
        } else {
            final float unitX = (float) this.size / refW;
            final float unitY = (float) this.size / refH;
            drawW = rw * unitX; drawH = rh * unitY;
            drawX = this.left ? (wx + this.size - drawW) : wx;
            drawY = wy + this.size - drawH;
        }
        final float scaleX = this.left ? -1f : 1f;
        SpriteOutline.drawOutline(batch, frame, drawX, drawY, drawW * 0.5f, drawH * 0.5f,
                drawW, drawH, scaleX, 1f, 0f, STROKE_OFFSET, STROKE_ALPHA);
    }

    /** Draw the main sprite body with its current effect (caller manages shader). */
    public void renderBody(SpriteBatch batch) {
        if (this.getSpriteSheet() == null) return;
        TextureRegion frame = this.getSpriteSheet().getCurrentFrame();
        if (frame == null) return;
        float wx = this.pos.getWorldVar().x;
        float wy = this.pos.getWorldVar().y;
        // Scale draw rect by frame region vs reference cell: body anchored at
        // bottom-left, so wider frames extend right (mirrored left) and taller up.
        final int refW = this.getSpriteSheet().getSpriteImageWidth();
        final int refH = this.getSpriteSheet().getSpriteImageHeight();
        final int rw = frame.getRegionWidth();
        final int rh = frame.getRegionHeight();
        if (refW <= 0 || refH <= 0 || rw <= 0 || rh <= 0) {
            // Sheet mid-load: fall back to a square draw.
            if (this.left) batch.draw(frame, wx, wy, this.size * 0.5f, this.size * 0.5f, this.size, this.size, -1f, 1f, 0f);
            else           batch.draw(frame, wx, wy, this.size * 0.5f, this.size * 0.5f, this.size, this.size, 1f, 1f, 0f);
            return;
        }
        final float unitX = (float) this.size / refW;
        final float unitY = (float) this.size / refH;
        final float drawW = rw * unitX;
        final float drawH = rh * unitY;
        final float drawY = wy + this.size - drawH;
        if (this.left) {
            batch.draw(frame, wx + this.size - drawW, drawY, drawW * 0.5f, drawH * 0.5f, drawW, drawH, -1f, 1f, 0f);
        } else {
            batch.draw(frame, wx, drawY, drawW * 0.5f, drawH * 0.5f, drawW, drawH, 1f, 1f, 0f);
        }
    }

    public Sprite.EffectEnum getCurrentEffect() {
        if (this.getSpriteSheet() == null) return Sprite.EffectEnum.NORMAL;
        return this.getSpriteSheet().getCurrentEffect();
    }

    @Override
    public abstract void render(SpriteBatch batch);

    /**
     * Removal hook (death, despawn, viewport unload): drops the per-instance
     * SpriteSheet wrapper for GC. The shared Texture is owned by
     * GameSpriteManager and never disposed here.
     */
    public void onRemoved() {
        this.setSpriteSheet(null);
    }
}
