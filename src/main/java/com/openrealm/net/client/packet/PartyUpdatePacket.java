package com.openrealm.net.client.packet;

import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.nettypes.SerializableLong;
import com.openrealm.net.entity.NetPartyMember;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = true)
@Streamable
@NoArgsConstructor
@PacketId(packetId = (byte) 41)
public class PartyUpdatePacket extends Packet {
    @SerializableField(order = 0, type = SerializableLong.class)
    private long partyId;
    @SerializableField(order = 1, type = NetPartyMember.class, isCollection = true)
    private NetPartyMember[] members;
}
