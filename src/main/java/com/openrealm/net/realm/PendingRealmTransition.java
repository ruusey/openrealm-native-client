package com.openrealm.net.realm;

import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.Portal;

/**
 * Data class for a deferred realm transition. The heavy realm generation
 * (terrain, enemies, dungeon layout) runs on a worker thread. Once complete,
 * the result is enqueued here and the tick thread integrates it: adds the
 * realm, transfers the player, sends map/load packets.
 */
public class PendingRealmTransition {
	public final Realm generatedRealm;
	public final Player player;
	public final Realm sourceRealm;
	public final Portal usedPortal;
	public PendingRealmTransition(Realm generatedRealm, Player player, Realm sourceRealm, Portal usedPortal) {
		this.generatedRealm = generatedRealm;
		this.player = player;
		this.sourceRealm = sourceRealm;
		this.usedPortal = usedPortal;
	}
}
