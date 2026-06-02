package com.openrealm.net.server.packet;

import com.openrealm.game.entity.Player;
import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.nettypes.SerializableBoolean;
import com.openrealm.net.core.nettypes.SerializableByte;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Data
@EqualsAndHashCode(callSuper = true)
@Slf4j
@Streamable
@NoArgsConstructor
@AllArgsConstructor
@PacketId(packetId = (byte)12)
public class MoveItemPacket extends Packet {
	// Slot-index regions — must match the server's MoveItemPacket EXACTLY (raw
	// bytes over the wire). Derived from the inventory + loot sizes:
	//   equipment 0..4, backpack 5..24, ground loot 25..34, potion slots 35/36.
	private static final int BACKPACK_START = Player.EQUIPMENT_SLOT_COUNT;
	private static final int GROUND_LOOT_START = Player.INVENTORY_SIZE;
	private static final int GROUND_LOOT_SIZE = 10;
	public static final int HP_POTION_SLOT = GROUND_LOOT_START + GROUND_LOOT_SIZE;
	public static final int MP_POTION_SLOT = HP_POTION_SLOT + 1;

	@SerializableField(order = 0, type = SerializableByte.class)
	private byte targetSlotIndex;
	@SerializableField(order = 1, type = SerializableByte.class)
	private byte fromSlotIndex;
	@SerializableField(order = 2, type = SerializableBoolean.class)
	private boolean drop;
	@SerializableField(order = 3, type = SerializableBoolean.class)
	private boolean consume;

	public static MoveItemPacket from(byte targetSlot, byte fromSlot, boolean drop, boolean consume)
			throws Exception {
		MoveItemPacket packet = new MoveItemPacket(targetSlot, fromSlot, drop, consume);
		return packet;
	}

	public static boolean isInventory(int index) {
		return index >= BACKPACK_START && index < Player.INVENTORY_SIZE;
	}

	public static boolean isEquipment(int index) {
		return index >= 0 && index < Player.EQUIPMENT_SLOT_COUNT;
	}

	public static boolean isGroundLoot(int index) {
		return index >= GROUND_LOOT_START && index < GROUND_LOOT_START + GROUND_LOOT_SIZE;
	}

	public static int groundLootBase() {
		return GROUND_LOOT_START;
	}
}
