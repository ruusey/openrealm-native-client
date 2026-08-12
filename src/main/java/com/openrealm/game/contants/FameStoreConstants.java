package com.openrealm.game.contants;

/**
 * Client-safe fame-store pricing constants. The authoritative copy lives on
 * the server; the client only needs the tier costs to render the fame-store
 * catalog.
 */
public final class FameStoreConstants {
    public static final long DYE_FAME_COST     = 500L;
    public static final long CRYSTAL_FAME_COST = 1000L;
    public static final long GEM_FAME_COST     = 5000L;

    private FameStoreConstants() {
    }
}
