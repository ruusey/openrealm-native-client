package com.openrealm.game.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class PortalModel extends SpriteModel {
    private int portalId;
    private String portalName;
    // A portal routes to EITHER a map (mapId) OR a dungeon (dungeonId); unset is -1.
    private int mapId = -1;
    private int dungeonId = -1;
    private String targetNodeId;
    // Static portal placement: world position (pixels) when placed on a map
    private float x;
    private float y;
    private String label;
}
