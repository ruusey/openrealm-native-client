package com.openrealm.net.client.packet;

import java.util.ArrayList;
import java.util.List;

import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.entity.NetCompactMovement;
import com.openrealm.net.entity.NetObjectMovement;
import com.openrealm.net.realm.ShortIdAllocator;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

// Short (2-byte) entity IDs resolve to long IDs via the mapping in LoadPacket.
@Data
@EqualsAndHashCode(callSuper = true)
@Streamable
@NoArgsConstructor
@PacketId(packetId = (byte) 25)
public class CompactMovePacket extends Packet {

    @SerializableField(order = 0, type = NetCompactMovement.class, isCollection = true)
    private NetCompactMovement[] movements;

    public static CompactMovePacket from(List<NetObjectMovement> corrections, ShortIdAllocator allocator) throws Exception {
        final List<NetCompactMovement> compact = new ArrayList<>();
        for (final NetObjectMovement m : corrections) {
            short shortId = allocator.toShort(m.getEntityId());
            if (shortId != 0) {
                compact.add(new NetCompactMovement(shortId, m));
            }
        }
        final CompactMovePacket packet = new CompactMovePacket();
        packet.setMovements(compact.toArray(new NetCompactMovement[0]));
        return packet;
    }
}
