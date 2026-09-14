package com.openrealm.game.model;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// data layers keyed "0"=base, "1"=collision/decoration; tile id 0 = don't overwrite terrain
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SetPieceModel {
    private int setPieceId;
    private String name;
    private int width;
    private int height;
    private Map<String, int[][]> data = new LinkedHashMap<>();

    @JsonIgnore
    public int[][] getLayer(String key) {
        return this.data == null ? null : this.data.get(key);
    }

    @JsonIgnore
    public int[][] getBaseLayer() {
        return getLayer("0");
    }

    @JsonIgnore
    public int[][] getCollisionLayer() {
        return getLayer("1");
    }
}
