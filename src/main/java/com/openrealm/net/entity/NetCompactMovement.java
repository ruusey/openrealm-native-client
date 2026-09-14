package com.openrealm.net.entity;

import java.io.DataInputStream;
import java.io.DataOutputStream;

import com.openrealm.net.Streamable;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.SerializableFieldType;
import com.openrealm.net.core.nettypes.SerializableByte;
import com.openrealm.net.core.nettypes.SerializableFloat;
import com.openrealm.net.core.nettypes.SerializableShort;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// Wire: 15 bytes = shortEntityId(2)+posX(4)+posY(4)+velXFixed(2)+velYFixed(2)+flags(1). Velocity is fixed-point value*128.
@Data
@AllArgsConstructor
@NoArgsConstructor
@Streamable
public class NetCompactMovement extends SerializableFieldType<NetCompactMovement> {
    @SerializableField(order = 0, type = SerializableShort.class)
    private short shortEntityId;
    @SerializableField(order = 1, type = SerializableFloat.class)
    private float posX;
    @SerializableField(order = 2, type = SerializableFloat.class)
    private float posY;
    @SerializableField(order = 3, type = SerializableShort.class)
    private short velXFixed;
    @SerializableField(order = 4, type = SerializableShort.class)
    private short velYFixed;
    @SerializableField(order = 5, type = SerializableByte.class)
    private byte flags;

    private static final float VEL_SCALE = 128f;

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
        final NetCompactMovement v = (value == null) ? new NetCompactMovement() : value;
        stream.writeShort(v.shortEntityId);
        stream.writeFloat(v.posX);
        stream.writeFloat(v.posY);
        stream.writeShort(v.velXFixed);
        stream.writeShort(v.velYFixed);
        stream.writeByte(v.flags);
        return 15;
    }

    @Override
    public NetCompactMovement read(DataInputStream stream) throws Exception {
        final NetCompactMovement m = new NetCompactMovement();
        m.shortEntityId = stream.readShort();
        m.posX = stream.readFloat();
        m.posY = stream.readFloat();
        m.velXFixed = stream.readShort();
        m.velYFixed = stream.readShort();
        m.flags = stream.readByte();
        return m;
    }
}
