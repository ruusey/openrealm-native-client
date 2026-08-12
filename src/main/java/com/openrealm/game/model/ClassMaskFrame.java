package com.openrealm.game.model;

import java.util.List;

import lombok.Data;

@Data
public class ClassMaskFrame {
    private int row;
    private int col;
    private List<String> animKeys;
    private int[][] mask;
}
