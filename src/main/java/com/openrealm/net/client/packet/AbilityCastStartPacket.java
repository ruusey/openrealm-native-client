package com.openrealm.net.client.packet;

import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.nettypes.SerializableByte;
import com.openrealm.net.core.nettypes.SerializableFloat;
import com.openrealm.net.core.nettypes.SerializableInt;
import com.openrealm.net.core.nettypes.SerializableLong;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = true)
@Streamable
@AllArgsConstructor
@NoArgsConstructor
@PacketId(packetId = (byte) 38)
public class AbilityCastStartPacket extends Packet {
    @SerializableField(order = 0, type = SerializableLong.class)
    private long playerId;
    @SerializableField(order = 1, type = SerializableInt.class)
    private int abilityId;
    @SerializableField(order = 2, type = SerializableByte.class)
    private byte slot;
    @SerializableField(order = 3, type = SerializableInt.class)
    private int durationMs;
    @SerializableField(order = 4, type = SerializableFloat.class)
    private float worldTargetX;
    @SerializableField(order = 5, type = SerializableFloat.class)
    private float worldTargetY;
}
