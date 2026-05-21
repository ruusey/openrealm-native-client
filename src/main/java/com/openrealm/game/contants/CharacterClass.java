package com.openrealm.game.contants;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.openrealm.game.entity.Player;

public enum CharacterClass {
    BARBARIAN(0),
    ASSASSIN(1),
    WIZARD(2),
    DUELIST(3),
    TRAPPER(4),
    NECROMANCER(5),
    PALADIN(6),
    DRUID(7),
    PRIEST(8),
    KNIGHT(9),
    NINJA(10),
    CULTIST(11);

    public static final Map<Integer, CharacterClass> map = new HashMap<>();
    static {
        for (CharacterClass cc : CharacterClass.values()) {
            CharacterClass.map.put(cc.classId, cc);
        }
    }

    public final int classId;

    CharacterClass(int classId) {
        this.classId = classId;
    }

    public static List<CharacterClass> getCharacterClasses() {
        return Arrays.asList(CharacterClass.values()).stream()
                .filter(c -> c.classId >= 0)
                .collect(Collectors.toList());
    }

    public static CharacterClass getPlayerCharacterClass(Player p) {
        return CharacterClass.valueOf(p.getClassId());
    }

    public static CharacterClass valueOf(int classId) {
        return CharacterClass.map.get(classId);
    }
}
