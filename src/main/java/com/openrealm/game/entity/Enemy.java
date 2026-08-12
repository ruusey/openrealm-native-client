package com.openrealm.game.entity;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.item.Stats;
import com.openrealm.game.graphics.Sprite;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.EnemyModel;
import com.openrealm.game.state.PlayState;
import com.openrealm.net.client.packet.UpdatePacket;
import com.openrealm.net.realm.RealmManagerClient;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import com.openrealm.game.graphics.ShaderManager;
import com.openrealm.net.client.packet.PlayerStatePacket;

@Data
@EqualsAndHashCode(callSuper = false)
@Slf4j
public class Enemy extends Entity {
    protected EnemyModel model;
    protected int chaseRange;
    protected int attackRange;
    protected int xOffset;
    protected int yOffset;

    private int enemyId;
    private int weaponId = -1;
    private float difficulty = 1.0f;
    private Stats stats;
    private Vector2f spawnPos = null;

    public Enemy() {
        super(0, null, 0);
    }

    public Enemy(long id, int enemyId, Vector2f origin, int size, int weaponId) {
        super(id, origin, size);
        this.model = GameDataManager.ENEMIES.get(enemyId);
        this.enemyId = enemyId;
        this.weaponId = weaponId;
        this.stats = this.model.getStats().clone();
        this.health = stats.getHp();
        this.mana = stats.getMp();
        if (origin != null) {
            this.spawnPos = origin.clone();
        }
    }

    public void applyStats(Stats stats) {
        this.health = stats.getHp();
        this.mana = stats.getMp();
        this.stats.setHp(stats.getHp());
        this.stats.setMp(stats.getMp());
        this.stats.setDef(stats.getDef());
        this.stats.setStr(stats.getStr());
        this.stats.setSpd(stats.getSpd());
        this.stats.setDex(stats.getDex());
        this.stats.setVit(stats.getVit());
        this.stats.setWis(stats.getWis());
    }

    @Override
    public int getHealth() {
        return this.health;
    }

    @Override
    public int getMana() {
        return this.mana;
    }

    public int getMaxHealth() {
        return (this.stats != null) ? this.stats.getHp() : this.health;
    }

    public void applyUpdate(UpdatePacket packet, PlayState state) {
        this.name = packet.getPlayerName();
        this.stats = packet.getStats().asStats();
        this.health = packet.getHealth();
        this.mana = packet.getMana();
        if (this.stats != null && this.stats.getHp() > 0) {
            this.healthpercent = (float) this.health / (float) this.stats.getHp();
        }
    }

    public void applyState(PlayerStatePacket packet) {
        this.health = packet.getHealth();
        this.mana = packet.getMana();
        this.setEffectIds(packet.getEffectIds());
        this.setEffectTimes(packet.getEffectTimes());
        if (this.stats != null && this.stats.getHp() > 0) {
            this.healthpercent = (float) this.health / (float) this.stats.getHp();
        }
    }

    // ========== UPDATE LOOP ==========

    public void update(RealmManagerClient mgr, double time) {
        super.update(time);
        // Select the active animation set (idle/walk/attack) from velocity +
        // attack state. No-op for static enemies (hasAnimSets() == false).
        this.updateAnimation();
        if (this.stats != null && this.stats.getHp() > 0) {
            this.healthpercent = (float) this.getHealth() / (float) this.stats.getHp();
        }
        if (this.stats != null && this.stats.getMp() > 0) {
            this.manapercent = (float) this.getMana() / (float) this.stats.getMp();
        }
        // Dead reckoning extrapolation, viewport-gated so off-screen
        // enemies don't drift while the server has stopped updating them.
        // Pass the local player center as the gate reference.
        float refX = 0f, refY = 0f;
        try {
            final Player local = mgr != null && mgr.getState() != null
                    ? mgr.getState().getPlayer() : null;
            if (local != null && local.getPos() != null) {
                final float halfSize = (local.getSize() > 0 ? local.getSize() : 32) * 0.5f;
                refX = local.getPos().x + halfSize;
                refY = local.getPos().y + halfSize;
                this.extrapolate(refX, refY, true);
                return;
            }
        } catch (Exception ignored) { /* fall through to ungated extrapolation */ }
        this.extrapolate();
    }

    @Override
    public void updateEffectState() {
        if (this.getSpriteSheet() == null) return;
        if (this.hasEffect(StatusEffectType.INVINCIBLE)) {
            if (!this.getSpriteSheet().hasEffect(Sprite.EffectEnum.INVINCIBLE)) {
                this.getSpriteSheet().setEffect(Sprite.EffectEnum.INVINCIBLE);
            }
        } else if (this.hasEffect(StatusEffectType.STASIS)) {
            if (!this.getSpriteSheet().hasEffect(Sprite.EffectEnum.STASIS)) {
                this.getSpriteSheet().setEffect(Sprite.EffectEnum.STASIS);
            }
        } else if (this.hasEffect(StatusEffectType.PARALYZED)) {
            if (!this.getSpriteSheet().hasEffect(Sprite.EffectEnum.GRAYSCALE)) {
                this.getSpriteSheet().setEffect(Sprite.EffectEnum.GRAYSCALE);
            }
        } else if (this.hasEffect(StatusEffectType.STUNNED)) {
            if (!this.getSpriteSheet().hasEffect(Sprite.EffectEnum.DECAY)) {
                this.getSpriteSheet().setEffect(Sprite.EffectEnum.DECAY);
            }
        } else if (this.hasEffect(StatusEffectType.ARMOR_BROKEN)) {
            if (!this.getSpriteSheet().hasEffect(Sprite.EffectEnum.ARMOR_BROKEN)) {
                this.getSpriteSheet().setEffect(Sprite.EffectEnum.ARMOR_BROKEN);
            }
        } else if (this.hasEffect(StatusEffectType.CURSED)) {
            if (!this.getSpriteSheet().hasEffect(Sprite.EffectEnum.CURSED)) {
                this.getSpriteSheet().setEffect(Sprite.EffectEnum.CURSED);
            }
        } else if (this.hasEffect(StatusEffectType.POISONED)) {
            if (!this.getSpriteSheet().hasEffect(Sprite.EffectEnum.POISONED)) {
                this.getSpriteSheet().setEffect(Sprite.EffectEnum.POISONED);
            }
        } else if (this.hasNoEffects()) {
            if (!this.getSpriteSheet().hasEffect(Sprite.EffectEnum.NORMAL)) {
                this.getSpriteSheet().setEffect(Sprite.EffectEnum.NORMAL);
            }
        }
    }

    @Override
    public void onRemoved() {
        super.onRemoved();
        this.spawnPos = null;
        this.stats = null;
        // model is a SHARED pointer into GameDataManager.ENEMIES — DO NOT null
        // its registry entry, just drop our reference so the Enemy instance
        // doesn't keep that EnemyModel reachable through us specifically.
        this.model = null;
    }

    @Override
    public void render(SpriteBatch batch) {
        if (this.getSpriteSheet() == null) return;
        this.updateEffectState();
        TextureRegion frame = this.getSpriteSheet().getCurrentFrame();
        if (frame != null) {
            float wx = this.pos.getWorldVar().x;
            float wy = this.pos.getWorldVar().y;

            // No outline pass, no SILHOUETTE-shader drop shadow. The user
            // repeatedly asked for the chunky black halo to be gone; the
            // web client renders enemies as just the body sprite, so do
            // the same here.
            Sprite.EffectEnum currentEffect = this.getSpriteSheet().getCurrentEffect();
            ShaderManager.applyEffect(batch, currentEffect);
            // Scale draw rect by the frame's region size relative to the
            // sheet's reference cell so wide/tall attack frames extend past
            // the body instead of being squished — see Entity.renderBody for
            // the anchor convention.
            final int refW = this.getSpriteSheet().getSpriteImageWidth();
            final int refH = this.getSpriteSheet().getSpriteImageHeight();
            final int rw = frame.getRegionWidth();
            final int rh = frame.getRegionHeight();
            if (refW > 0 && refH > 0 && rw > 0 && rh > 0) {
                final float unitX = (float) this.size / refW;
                final float unitY = (float) this.size / refH;
                final float drawW = rw * unitX;
                final float drawH = rh * unitY;
                final float drawY = wy + this.size - drawH;
                if (this.left) {
                    batch.draw(frame, wx + this.size, drawY, -drawW, drawH);
                } else {
                    batch.draw(frame, wx, drawY, drawW, drawH);
                }
            } else {
                if (this.left) batch.draw(frame, wx + this.size, wy, -this.size, this.size);
                else            batch.draw(frame, wx, wy, this.size, this.size);
            }
            ShaderManager.clearEffect(batch);
        }
    }
}
