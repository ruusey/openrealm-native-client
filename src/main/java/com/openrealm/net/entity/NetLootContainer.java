package com.openrealm.net.entity;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.stream.Stream;

import com.openrealm.game.contants.LootTier;
import com.openrealm.game.entity.item.Chest;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.LootContainer;
import com.openrealm.game.math.Vector2f;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.IOService;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.SerializableFieldType;
import com.openrealm.net.core.nettypes.SerializableBoolean;
import com.openrealm.net.core.nettypes.SerializableByte;
import com.openrealm.net.core.nettypes.SerializableLong;
import com.openrealm.net.core.nettypes.SerializableString;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
@Data
@Streamable
@AllArgsConstructor
@NoArgsConstructor
public class NetLootContainer extends SerializableFieldType<NetLootContainer>{
	@SerializableField(order = 0, type = SerializableLong.class)
    private long lootContainerId;
	@SerializableField(order = 1, type = SerializableString.class)
    private String uid;
	@SerializableField(order = 2, type = SerializableBoolean.class)
    private boolean isChest;
	@SerializableField(order = 3, type = SerializableByte.class)
    private byte tier;
	@SerializableField(order = 4, type = NetGameItem.class, isCollection = true)
    private NetGameItem[] items;
	@SerializableField(order = 5, type = Vector2f.class)
    private Vector2f pos;
	@SerializableField(order = 6, type = SerializableLong.class)
    private long spawnedTime;
	@SerializableField(order = 7, type = SerializableBoolean.class)
    private boolean contentsChanged;
	// soulboundPlayerId: -1 = public, otherwise the playerId this loot is bound to.
	@SerializableField(order = 8, type = SerializableLong.class)
    private long soulboundPlayerId;
	
	public LootContainer asLootContainer() {
		LootContainer container = new LootContainer();
		container.setLootContainerId(this.lootContainerId);
		container.setUid(this.uid);
		final LootTier resolvedTier = LootTier.valueOf(this.tier);
		container.setTier(resolvedTier);
		// Set sprite explicitly or non-chest bags render invisible (chests re-wrapped below).
		container.setSprite(LootTier.getLootSprite(this.tier));
		GameItem[] itemsMapped = new GameItem[items.length];
		for(int i = 0 ; i < items.length ; i++) {

			itemsMapped[i] = this.items[i] == null ? null : this.items[i].asGameItem();
		}
		container.setItems(itemsMapped);
		container.setPos(this.pos);
		container.setSpawnedTime(this.spawnedTime);
		container.setContentsChanged(this.contentsChanged);
		container.setSoulboundPlayerId(this.soulboundPlayerId);

		if(this.isChest) {
			container = new Chest(container);
		}
		return container;
	}
	
    public boolean equals(NetLootContainer other) {
        boolean basic = (this.lootContainerId == other.getLootContainerId()) && this.pos.equals(other.getPos());
        boolean tierMatch = this.getTier() == other.getTier();
        boolean loot = true;
        for (int i = 0; i < this.items.length && i < other.getItems().length; i++) {
            NetGameItem a = this.items[i];
            NetGameItem b = other.getItems()[i];
            if (a == null && b == null) continue;
            if (a == null || b == null || !a.equals(b)) {
                loot = false;
                break;
            }
        }
        return basic && loot && tierMatch;
    }

}
