package com.openrealm.net.client.packet;

import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.nettypes.SerializableLong;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Server -> client acknowledgement that the player can open the Exchange Market
 * UI. Sent in response to InteractTilePacket on a tile whose interactionType ==
 * "exchange_market". The client builds the exchange UI from its local inventory
 * and game-items data; the ExchangeItemsPacket it sends back is authoritative.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Streamable
@NoArgsConstructor
@AllArgsConstructor
@PacketId(packetId = (byte) 44)
public class OpenExchangeMarketPacket extends Packet {
    @SerializableField(order = 0, type = SerializableLong.class)
    private long playerId;
}
