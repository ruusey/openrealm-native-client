package com.openrealm.net.entity;

import com.openrealm.net.Streamable;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.SerializableFieldType;
import com.openrealm.net.core.nettypes.SerializableInt;
import com.openrealm.net.core.nettypes.SerializableLong;
import com.openrealm.net.core.nettypes.SerializableShort;
import com.openrealm.net.core.nettypes.SerializableString;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Streamable
public class NetPartyMember extends SerializableFieldType<NetPartyMember> {
    @SerializableField(order = 0, type = SerializableLong.class)
    private long playerId;
    @SerializableField(order = 1, type = SerializableString.class)
    private String name;
    @SerializableField(order = 2, type = SerializableInt.class)
    private int classId;
    @SerializableField(order = 3, type = SerializableInt.class)
    private int health;
    @SerializableField(order = 4, type = SerializableInt.class)
    private int maxHealth;
    @SerializableField(order = 5, type = SerializableInt.class)
    private int mana;
    @SerializableField(order = 6, type = SerializableInt.class)
    private int maxMana;
    @SerializableField(order = 7, type = SerializableInt.class)
    private int level;
    @SerializableField(order = 8, type = SerializableLong.class)
    private long realmId;
    @SerializableField(order = 9, type = SerializableShort.class, isCollection = true)
    private Short[] effectIds;
    /** Ability ids bound to hotbar slots 0..3. */
    @SerializableField(order = 10, type = SerializableInt.class, isCollection = true)
    private Integer[] hotbarBindings;
    /** End-of-cooldown epoch-millis per hotbar slot. */
    @SerializableField(order = 11, type = SerializableLong.class, isCollection = true)
    private Long[] abilityCooldownEnds;
    /** Skill points invested into each of the 4 hotbar abilities. */
    @SerializableField(order = 12, type = SerializableInt.class, isCollection = true)
    private Integer[] hotbarInvested;
    @SerializableField(order = 13, type = NetStats.class)
    private NetStats stats;
    /** Equipped items (slots 0..4: weapon/armor/gauntlets/boots/ring); null = empty. */
    @SerializableField(order = 14, type = NetGameItem.class, isCollection = true)
    private NetGameItem[] equipment;
}
