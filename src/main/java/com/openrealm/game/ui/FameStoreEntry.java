package com.openrealm.game.ui;

/** A single purchasable entry rendered in the grid. */
public class FameStoreEntry {
    public int itemId;
    public String name;
    public long cost;
    public String description;
    public FameStoreEntry(int itemId, String name, long cost, String description) {
        this.itemId = itemId;
        this.name = name;
        this.cost = cost;
        this.description = description;
    }
}
