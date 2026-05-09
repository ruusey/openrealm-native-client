package com.openrealm.net.entity;

import java.io.DataInputStream;
import java.io.DataOutputStream;

import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.IOService;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.SerializableFieldType;
import com.openrealm.net.core.nettypes.SerializableBoolean;
import com.openrealm.net.core.nettypes.SerializableLong;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Streamable
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NetInventorySelection extends SerializableFieldType<NetInventorySelection> {

	@SerializableField(order = 0, type = SerializableLong.class)
	private long playerId;
	@SerializableField(order = 1, type = SerializableBoolean.class, isCollection = true)
	private Boolean[] selection;
	@SerializableField(order = 2, type = NetGameItemRef.class, isCollection = true)
	private NetGameItemRef[] itemRefs;
	/** Whether this player has hit Confirm on the trade. Set by the server
	 *  on /confirm true and broadcast in UpdateTradePacket so the partner's
	 *  trade overlay can render "X has confirmed". */
	@SerializableField(order = 3, type = SerializableBoolean.class)
	private boolean confirmed;

	public static NetInventorySelection fromPlayer(Player player, Boolean[] selectedSlots) {
		return NetInventorySelection.builder().playerId(player.getId()).selection(selectedSlots).build();
	}

	public GameItem[] getGameItems() {
		final GameItem[] items = new GameItem[itemRefs.length];
		for (int i = 0; i < this.itemRefs.length; i++) {
			items[i] = GameItem.fromGameItemRef(this.itemRefs[i]);
		}
		return items;
	}

}
