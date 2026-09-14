package com.openrealm.game.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Corridor attach strip on a room edge: side "N"/"S"/"E"/"W", offset+length along it. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DungeonRoomConnection {
    private int id;
    private String side;
    private int offset;
    private int length;
}
