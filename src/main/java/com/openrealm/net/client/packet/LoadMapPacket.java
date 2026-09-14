package com.openrealm.net.client.packet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.nettypes.SerializableLong;
import com.openrealm.net.core.nettypes.SerializableShort;
import com.openrealm.net.entity.NetTile;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Getter
@Setter
@Slf4j
@Streamable
@AllArgsConstructor
@PacketId(packetId = (byte)8)
public class LoadMapPacket extends Packet {
	@SerializableField(order = 0, type = SerializableLong.class)
    private long realmId;
	@SerializableField(order = 1, type = SerializableShort.class)
    private short mapId;
	// -1 for static/terrain maps; else the dungeon id (client resolves grid dims from DUNGEONS).
	@SerializableField(order = 2, type = SerializableShort.class)
    private short dungeonId;
	@SerializableField(order = 3, type = SerializableShort.class)
    private short mapWidth;
	@SerializableField(order = 4, type = SerializableShort.class)
    private short mapHeight;
	@SerializableField(order = 5, type = NetTile.class, isCollection=true)
    private NetTile[] tiles;

    public LoadMapPacket() {

    }

    public static LoadMapPacket from(long realmId, short mapId, short dungeonId, short mapWidth, short mapHeight, List<NetTile> tiles) throws Exception {
    	return from(realmId, mapId, dungeonId, mapWidth, mapHeight, tiles.toArray(new NetTile[0]));
    }

    public static LoadMapPacket from(long realmId, short mapId, short dungeonId, short mapWidth, short mapHeight,  NetTile[] tiles) throws Exception {
    	return new LoadMapPacket(realmId, mapId, dungeonId, mapWidth, mapHeight, tiles);
    }

    // Bit layout: [tileId 16 | layer 8 | x 20 | y 20] = 64 bits; sign-extension safe.
    private static long packTileKey(NetTile t) {
        return ((long) (t.getTileId() & 0xFFFF) << 48)
             | ((long) (t.getLayer() & 0xFF) << 40)
             | ((long) (t.getXIndex() & 0xFFFFF) << 20)
             |  ((long) (t.getYIndex() & 0xFFFFF));
    }

    public LoadMapPacket difference(LoadMapPacket other) throws Exception {
        // If the player is changing realms, force the new tiles to be sent
        if (this.realmId != other.getRealmId())
            return other;
        final NetTile[] myTiles = this.getTiles();
        final Set<Long> myKeys = new HashSet<>(myTiles.length * 2);
        for (final NetTile t : myTiles) myKeys.add(packTileKey(t));

        final List<NetTile> diff = new ArrayList<>();
        for (final NetTile tileOther : other.getTiles()) {
            if (!myKeys.contains(packTileKey(tileOther))) {
                diff.add(tileOther);
            }
        }
        if (diff.isEmpty())
            return null;
        return LoadMapPacket.from(other.getRealmId(), other.getMapId(), other.getDungeonId(),
                other.getMapWidth(), other.getMapHeight(), diff);
    }

    public boolean equals(LoadMapPacket other) {
        if (other == null) return false;
        if (this.realmId != other.getRealmId()) return false;
        if (this.mapId != other.getMapId()) return false;
        if (this.mapHeight != other.getMapHeight() || this.mapWidth != other.getMapWidth()) return false;

        final NetTile[] myTiles = this.getTiles();
        final NetTile[] otherTiles = other.getTiles();
        if (myTiles.length != otherTiles.length) return false;

        // Set-based, order-independent: the viewport can emit the same tiles in any order.
        final Set<Long> myKeys = new HashSet<>(myTiles.length * 2);
        for (final NetTile t : myTiles) myKeys.add(packTileKey(t));
        for (final NetTile t : otherTiles) {
            if (!myKeys.contains(packTileKey(t))) return false;
        }
        return true;
    }

    public static boolean tilesContains(NetTile tile, NetTile[] array) {
        for (NetTile netTile : array) {
            if (tile.equals(netTile))
                return true;
        }
        return false;
    }
}
