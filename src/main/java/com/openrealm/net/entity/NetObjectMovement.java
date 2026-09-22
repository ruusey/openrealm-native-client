package com.openrealm.net.entity;

import java.io.DataInputStream;
import java.io.DataOutputStream;

import com.openrealm.game.contants.EntityType;
import com.openrealm.game.entity.Bullet;
import com.openrealm.game.entity.Enemy;
import com.openrealm.game.entity.Entity;
import com.openrealm.game.entity.GameObject;
import com.openrealm.game.entity.Player;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.SerializableFieldType;
import com.openrealm.net.core.codec.StreamCodec;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Streamable
public class NetObjectMovement extends SerializableFieldType<NetObjectMovement> {
    private long entityId;
    private byte entityType;
    private float posX;
    private float posY;
    private float velX;
    private float velY;
    private byte flags;

    /** Flag bit: entity is currently in an attack/shoot animation. */
    private static final byte FLAG_ATTACKING = 0x01;

    public static final StreamCodec<NetObjectMovement> CODEC = StreamCodec.builder(NetObjectMovement::new)
            .int64(NetObjectMovement::getEntityId, NetObjectMovement::setEntityId)
            .int8(NetObjectMovement::getEntityType, NetObjectMovement::setEntityType)
            .float32(NetObjectMovement::getPosX, NetObjectMovement::setPosX)
            .float32(NetObjectMovement::getPosY, NetObjectMovement::setPosY)
            .float32(NetObjectMovement::getVelX, NetObjectMovement::setVelX)
            .float32(NetObjectMovement::getVelY, NetObjectMovement::setVelY)
            .int8(NetObjectMovement::getFlags, NetObjectMovement::setFlags)
            .build();

    public NetObjectMovement(float posX, float posY) {
        this.posX = posX;
        this.posY = posY;
    }

    public NetObjectMovement(GameObject obj) {
        this.entityId = obj.getId();
        if (obj instanceof Enemy) {
            this.entityType = EntityType.ENEMY.getEntityTypeId();
        } else if (obj instanceof Player) {
            this.entityType = EntityType.PLAYER.getEntityTypeId();
        } else if (obj instanceof Bullet) {
            this.entityType = EntityType.BULLET.getEntityTypeId();
        }
        this.posX = Math.round(obj.getPos().x * 2f) / 2f;
        this.posY = Math.round(obj.getPos().y * 2f) / 2f;
        this.velX = Math.round(obj.getDx() * 8f) / 8f;
        this.velY = Math.round(obj.getDy() * 8f) / 8f;
        this.flags = 0;
        if (obj instanceof Entity && ((Entity) obj).isAttacking()) this.flags |= FLAG_ATTACKING;
    }

    public boolean isAttackingFlag() {
        return (this.flags & FLAG_ATTACKING) != 0;
    }

    public EntityType getTargetEntityType() {
    	final EntityType type = EntityType.valueOf(entityType);
        return type;
    }

    public boolean equals(NetObjectMovement other) {
        return this.entityId == other.getEntityId() && this.entityType == other.getEntityType()
                && this.posX == other.getPosX() && this.posY == other.getPosY() && this.velX == other.getVelX()
                && this.getVelY() == other.getVelY() && this.flags == other.getFlags();
    }

    @Override
    public int write(NetObjectMovement value, DataOutputStream stream) throws Exception {
        return CODEC.write(value, stream);
    }

    @Override
    public NetObjectMovement read(DataInputStream stream) throws Exception {
        return CODEC.read(stream);
    }
}
