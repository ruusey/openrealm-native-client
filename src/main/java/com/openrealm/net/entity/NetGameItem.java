package com.openrealm.net.entity;

import java.io.DataInputStream;
import java.io.DataOutputStream;

import java.util.ArrayList;
import java.util.List;

import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.item.AttributeModifier;
import com.openrealm.game.entity.item.Damage;
import com.openrealm.game.entity.item.Effect;
import com.openrealm.game.entity.item.Enchantment;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.Stats;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.IOService;
import com.openrealm.net.core.SerializableFieldType;
import com.openrealm.net.core.codec.StreamCodec;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@Streamable
public class NetGameItem extends SerializableFieldType<NetGameItem> {
	private int itemId;
	private String uid;
	private String name;
	private String description;
	private NetStats stats;
	private NetDamage damage;
	private NetEffect effect;
	private boolean consumable;
	private byte tier;
	private byte targetSlot;
	private byte targetClass;
	private byte fameBonus;
	private boolean stackable;
	private int maxStack;
	private int stackCount;
	private String category;
	private byte forgeStatId;
	private byte forgeSlotId;
	private List<NetEnchantment> enchantments;
	private byte rarity;
	private List<NetAttributeModifier> attributeModifiers;
	private byte gemstoneType;
	private byte gemPixelX;
	private byte gemPixelY;
	private int gemPixelColor;

	// Wire layout MUST match server NetGameItem.write byte-for-byte or inventory packets desync.
	public static final StreamCodec<NetGameItem> CODEC = StreamCodec.builder(NetGameItem::new)
			.int32(NetGameItem::getItemId, NetGameItem::setItemId)
			.utf(NetGameItem::getUid, NetGameItem::setUid)
			.utf(NetGameItem::getName, NetGameItem::setName)
			.utf(NetGameItem::getDescription, NetGameItem::setDescription)
			.nested(NetStats.CODEC, NetGameItem::getStats, NetGameItem::setStats)
			.nested(NetDamage.CODEC, NetGameItem::getDamage, NetGameItem::setDamage)
			.nested(NetEffect.CODEC, NetGameItem::getEffect, NetGameItem::setEffect)
			.bool(NetGameItem::isConsumable, NetGameItem::setConsumable)
			.int8(NetGameItem::getTier, NetGameItem::setTier)
			.int8(NetGameItem::getTargetSlot, NetGameItem::setTargetSlot)
			.int8(NetGameItem::getTargetClass, NetGameItem::setTargetClass)
			.int8(NetGameItem::getFameBonus, NetGameItem::setFameBonus)
			.bool(NetGameItem::isStackable, NetGameItem::setStackable)
			.int32(NetGameItem::getMaxStack, NetGameItem::setMaxStack)
			.int32(NetGameItem::getStackCount, NetGameItem::setStackCount)
			.utf(NetGameItem::getCategory, NetGameItem::setCategory)
			.int8(NetGameItem::getForgeStatId, NetGameItem::setForgeStatId)
			.int8(NetGameItem::getForgeSlotId, NetGameItem::setForgeSlotId)
			.list(NetEnchantment.CODEC, NetGameItem::getEnchantments, NetGameItem::setEnchantments)
			.int8(NetGameItem::getRarity, NetGameItem::setRarity)
			.list(NetAttributeModifier.CODEC, NetGameItem::getAttributeModifiers, NetGameItem::setAttributeModifiers)
			.int8(NetGameItem::getGemstoneType, NetGameItem::setGemstoneType)
			.int8(NetGameItem::getGemPixelX, NetGameItem::setGemPixelX)
			.int8(NetGameItem::getGemPixelY, NetGameItem::setGemPixelY)
			.int32(NetGameItem::getGemPixelColor, NetGameItem::setGemPixelColor)
			.build();

	@Override
	public int write(NetGameItem value, DataOutputStream stream) throws Exception {
		return CODEC.write(value, stream);
	}

	@Override
	public NetGameItem read(DataInputStream stream) throws Exception {
		return CODEC.read(stream);
	}

	public GameItem asGameItem() {
		GameItem item = new GameItem();
		item.setItemId(this.itemId);
		item.setUid(this.uid);
		item.setName(this.name);
		item.setDescription(this.description);
		item.setStats(IOService.mapModel(this.stats, Stats.class));
		item.setDamage(IOService.mapModel(this.damage, Damage.class));
		item.setEffect(IOService.mapModel(this.effect, Effect.class));
		item.setConsumable(this.consumable);
		item.setTier(this.tier);
		item.setTargetSlot(this.targetSlot);
		item.setTargetClass(this.targetClass);
		item.setFameBonus(this.fameBonus);
		item.setStackable(this.stackable);
		item.setMaxStack(this.maxStack);
		item.setStackCount(this.stackCount);
		item.setCategory(this.category);
		item.setForgeStatId(this.forgeStatId);
		item.setForgeSlotId(this.forgeSlotId);
		item.setRarity(this.rarity);
		item.setGemstoneType(this.gemstoneType);
		item.setGemPixelX(this.gemPixelX);
		item.setGemPixelY(this.gemPixelY);
		item.setGemPixelColor(this.gemPixelColor);
		if (this.enchantments != null && !this.enchantments.isEmpty()) {
			final List<Enchantment> out = new ArrayList<>(this.enchantments.size());
			for (NetEnchantment ne : this.enchantments) {
				out.add(new Enchantment(ne.getStatId(), ne.getDeltaValue(), ne.getPixelX(), ne.getPixelY(),
						ne.getPixelColor()));
			}
			item.setEnchantments(out);
		} else {
			item.setEnchantments(new ArrayList<>());
		}
		if (this.attributeModifiers != null && !this.attributeModifiers.isEmpty()) {
			final List<AttributeModifier> out = new ArrayList<>(this.attributeModifiers.size());
			for (NetAttributeModifier nm : this.attributeModifiers) {
				out.add(new AttributeModifier(nm.getStatId(), nm.getDeltaValue()));
			}
			item.setAttributeModifiers(out);
		} else {
			item.setAttributeModifiers(new ArrayList<>());
		}
		// archetypeId isn't carried on the wire — resolve it from the item
		// definition so melee weapons predict correctly (the client skips
		// projectile spawning for melee) and the melee cone reticle renders.
		// Without this every equipped weapon reads archetypeId=0, so melee
		// swings fired a ghost basic-attack projectile (doubled by a multishot
		// gem) instead of the AoE cone.
		if (GameDataManager.GAME_ITEMS != null) {
			final GameItem def = GameDataManager.GAME_ITEMS.get(this.itemId);
			if (def != null) item.setArchetypeId(def.getArchetypeId());
		}
		return item;
	}

	public boolean equals(NetGameItem other) {
		if (other == null) return false;
		if (this.itemId != other.itemId) return false;
		if (this.uid == null && other.uid == null) return true;
		if (this.uid == null || other.uid == null) return false;
		return this.uid.equals(other.uid);
	}

	public NetGameItem() {
		this.itemId = -1;
		this.uid = "";
		this.name = "";
		this.description = "";
		this.stats = new NetStats();
		this.damage = new NetDamage();
		this.effect = new NetEffect();
		this.consumable = false;
		this.tier = -2;
		this.targetSlot = -1;
		this.targetClass = -1;
		this.fameBonus = -1;
		this.stackable = false;
		this.maxStack = 1;
		this.stackCount = 1;
		this.category = "generic";
		this.forgeStatId = -1;
		this.forgeSlotId = -1;
		this.enchantments = new ArrayList<>();
		this.rarity = 0;
		this.attributeModifiers = new ArrayList<>();
		this.gemstoneType = 0;
		this.gemPixelX = 0;
		this.gemPixelY = 0;
		this.gemPixelColor = 0;
	}
}
