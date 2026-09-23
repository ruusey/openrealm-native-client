package com.openrealm.net.client.packet;

import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.nettypes.SerializableLong;
import com.openrealm.net.core.nettypes.SerializableString;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Full quest-log snapshot for one player: public star total plus a JSON payload of every
 * visible quest's status + objective progress. Must exist on the native client (even before
 * the quest window is built) so PacketType can resolve id 46 - an unknown id NPEs the read
 * loop and empties the realm.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Streamable
@NoArgsConstructor
@PacketId(packetId = (byte) 46)
public class QuestStatePacket extends Packet {
    @SerializableField(order = 0, type = SerializableLong.class)
    private long playerId;
    @SerializableField(order = 1, type = SerializableLong.class)
    private long stars;
    @SerializableField(order = 2, type = SerializableString.class)
    private String json;
}
