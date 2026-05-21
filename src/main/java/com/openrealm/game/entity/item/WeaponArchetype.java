package com.openrealm.game.entity.item;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum WeaponArchetype {
    NONE((byte) 0),
    SWORD((byte) 1),
    AXE((byte) 2),
    HAMMER((byte) 3),
    DAGGER((byte) 10),
    BOW((byte) 11),
    CHAKRAM((byte) 12),
    TOME((byte) 20),
    STAFF((byte) 21),
    WAND((byte) 22);

    public final byte id;

    WeaponArchetype(byte id) {
        this.id = id;
    }

    public ItemClass itemClass() {
        switch (this) {
            case SWORD: case AXE: case HAMMER:    return ItemClass.HEAVY_WEAPON;
            case DAGGER: case BOW: case CHAKRAM:  return ItemClass.LIGHT_WEAPON;
            case TOME: case STAFF: case WAND:     return ItemClass.MAGIC_WEAPON;
            default: return ItemClass.NONE;
        }
    }

    @JsonCreator
    public static WeaponArchetype fromId(byte id) {
        for (WeaponArchetype a : WeaponArchetype.values()) {
            if (a.id == id) return a;
        }
        return NONE;
    }
}
