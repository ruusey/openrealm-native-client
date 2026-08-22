package com.openrealm.net.client.packet;

import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.nettypes.SerializableLong;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Server-to-client sync of a player's 9 account-wide skill XP totals. The client
 * derives level 0-99 from the same quadratic curve the server uses.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Streamable
@NoArgsConstructor
@PacketId(packetId = (byte) 43)
public class SkillsPacket extends Packet {
    @SerializableField(order = 0, type = SerializableLong.class)
    private long playerId;
    @SerializableField(order = 1, type = SerializableLong.class)
    private long xp0;
    @SerializableField(order = 2, type = SerializableLong.class)
    private long xp1;
    @SerializableField(order = 3, type = SerializableLong.class)
    private long xp2;
    @SerializableField(order = 4, type = SerializableLong.class)
    private long xp3;
    @SerializableField(order = 5, type = SerializableLong.class)
    private long xp4;
    @SerializableField(order = 6, type = SerializableLong.class)
    private long xp5;
    @SerializableField(order = 7, type = SerializableLong.class)
    private long xp6;
    @SerializableField(order = 8, type = SerializableLong.class)
    private long xp7;
    @SerializableField(order = 9, type = SerializableLong.class)
    private long xp8;

    public long[] asArray() {
        return new long[] { this.xp0, this.xp1, this.xp2, this.xp3, this.xp4,
                this.xp5, this.xp6, this.xp7, this.xp8 };
    }
}
