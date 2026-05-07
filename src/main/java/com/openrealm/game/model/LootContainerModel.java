package com.openrealm.game.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class LootContainerModel extends SpriteModel {
    private int tierId;
    private String name;
    private boolean fullSize;
}
