package com.openrealm.game.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// placement reference to a SetPieceModel by id, with count/zone rules
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SetPiece {
    private int setPieceId;
    private int minCount;
    private int maxCount;
    private List<String> allowedZones;
}
