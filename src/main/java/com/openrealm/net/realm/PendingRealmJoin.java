package com.openrealm.net.realm;

import com.openrealm.game.entity.Player;
import com.openrealm.net.Packet;
import com.openrealm.net.server.ClientSession;

/**
 * Data class for a deferred realm-join operation. Worker threads create these
 * after async authentication completes; the tick thread drains and executes
 * them before building LoadPackets, guaranteeing no race with the delta logic.
 */
public class PendingRealmJoin {
	public final Realm realm;
	public final Player player;
	public final String srcIp;
	public final ClientSession session;
	public final Packet loginResponse;
	public PendingRealmJoin(Realm realm, Player player, String srcIp, ClientSession session, Packet loginResponse) {
		this.realm = realm;
		this.player = player;
		this.srcIp = srcIp;
		this.session = session;
		this.loginResponse = loginResponse;
	}
}
