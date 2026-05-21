package com.openrealm.game.entity.item;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum ItemClass {
    NONE((byte) 0),
    HEAVY_WEAPON((byte) 1),
    LIGHT_WEAPON((byte) 2),
    MAGIC_WEAPON((byte) 3),
    HEAVY_ARMOR((byte) 10),
    LIGHT_ARMOR((byte) 11),
    ROBE_ARMOR((byte) 12),
    UNIVERSAL((byte) 99);

    public final byte id;

    ItemClass(byte id) {
        this.id = id;
    }

    public boolean isWeapon() {
        return this == HEAVY_WEAPON || this == LIGHT_WEAPON || this == MAGIC_WEAPON;
    }

    public boolean isArmor() {
        return this == HEAVY_ARMOR || this == LIGHT_ARMOR || this == ROBE_ARMOR;
    }

    @JsonCreator
    public static ItemClass fromId(byte id) {
        for (ItemClass c : ItemClass.values()) {
            if (c.id == id) return c;
        }
        return NONE;
    }
}
