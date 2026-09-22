package com.openrealm.net.entity;

import java.io.DataInputStream;
import java.io.DataOutputStream;

import com.openrealm.net.Streamable;
import com.openrealm.net.core.SerializableFieldType;
import com.openrealm.net.core.codec.StreamCodec;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Compact movement entry using a 2-byte short entity ID instead of 8-byte long.
 * Used in ObjectMovePacket when short IDs have been assigned via ShortIdAllocator.
 * <p>
 * Wire format: 15 bytes per entity (was 26 with NetObjectMovement):
 *   shortEntityId (2) + posX (4) + posY (4) + velX (2, quantized) + velY (2, quantized) + flags (1)
 * Velocity is encoded as fixed-point: value * 128, giving ~0.008 precision per unit.
 * This is more than sufficient for entity speeds in the range 0–3.5.
 * <p>
 * Compared to NetObjectMovement (26 bytes): 42% size reduction per entity.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Streamable
public class NetCompactMovement extends SerializableFieldType<NetCompactMovement> {
    private short shortEntityId;
    private float posX;
    private float posY;
    // Velocity encoded as fixed-point short: value * 128
    private short velXFixed;
    private short velYFixed;
    private byte flags;

    private static final float VEL_SCALE = 128f;

    public static final StreamCodec<NetCompactMovement> CODEC = StreamCodec.builder(NetCompactMovement::new)
            .int16(NetCompactMovement::getShortEntityId, NetCompactMovement::setShortEntityId)
            .float32(NetCompactMovement::getPosX, NetCompactMovement::setPosX)
            .float32(NetCompactMovement::getPosY, NetCompactMovement::setPosY)
            .int16(NetCompactMovement::getVelXFixed, NetCompactMovement::setVelXFixed)
            .int16(NetCompactMovement::getVelYFixed, NetCompactMovement::setVelYFixed)
            .int8(NetCompactMovement::getFlags, NetCompactMovement::setFlags)
            .build();

    /**
     * Create from a full NetObjectMovement with a pre-assigned short ID.
     */
    public NetCompactMovement(short shortId, NetObjectMovement full) {
        this.shortEntityId = shortId;
        this.posX = full.getPosX();
        this.posY = full.getPosY();
        this.velXFixed = (short) Math.round(full.getVelX() * VEL_SCALE);
        this.velYFixed = (short) Math.round(full.getVelY() * VEL_SCALE);
        this.flags = full.getFlags();
    }

    public float getVelX() {
        return velXFixed / VEL_SCALE;
    }

    public float getVelY() {
        return velYFixed / VEL_SCALE;
    }

    @Override
    public int write(NetCompactMovement value, DataOutputStream stream) throws Exception {
        return CODEC.write(value, stream);
    }

    @Override
    public NetCompactMovement read(DataInputStream stream) throws Exception {
        return CODEC.read(stream);
    }
}
