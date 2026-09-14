package com.openrealm.net.server.packet;

import com.openrealm.game.entity.Player;
import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.nettypes.SerializableFloat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.openrealm.net.core.nettypes.SerializableInt;

// (vx, vy) is a world-space direction unit-vector; (0, 0) = not moving.
@Data
@EqualsAndHashCode(callSuper = true)
@Slf4j
@Streamable
@AllArgsConstructor
@NoArgsConstructor
@PacketId(packetId = (byte)1)
public class PlayerMovePacket extends Packet {
	@SerializableField(order = 0, type = SerializableInt.class)
    private int seq;
	@SerializableField(order = 1, type = SerializableFloat.class)
    private float vx;
	@SerializableField(order = 2, type = SerializableFloat.class)
    private float vy;

    public static PlayerMovePacket from(Player player, int seq, float vx, float vy) throws Exception {
    	final PlayerMovePacket read = new PlayerMovePacket(seq, vx, vy);
        return read;
    }
}
