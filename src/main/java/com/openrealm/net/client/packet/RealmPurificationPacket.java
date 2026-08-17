package com.openrealm.net.client.packet;

import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.nettypes.SerializableFloat;
import com.openrealm.net.core.nettypes.SerializableInt;
import com.openrealm.net.core.nettypes.SerializableLong;
import com.openrealm.net.core.nettypes.SerializableString;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Realm purification progress (progress/goal) for the realm the player is in,
 * rendered as the centered overworld purification bar.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Streamable
@NoArgsConstructor
@PacketId(packetId = (byte) 42)
public class RealmPurificationPacket extends Packet {
	@SerializableField(order = 0, type = SerializableLong.class)
	private long realmId;
	@SerializableField(order = 1, type = SerializableLong.class)
	private long progress;
	@SerializableField(order = 2, type = SerializableLong.class)
	private long goal;
	@SerializableField(order = 3, type = SerializableFloat.class)
	private float difficulty;
	@SerializableField(order = 4, type = SerializableInt.class)
	private int tier;
	@SerializableField(order = 5, type = SerializableString.class)
	private String modifiers = "";
}
