package com.openrealm.net.entity;

import java.io.DataInputStream;
import java.io.DataOutputStream;

import com.openrealm.game.entity.Portal;
import com.openrealm.game.math.Vector2f;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.IOService;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.SerializableFieldType;
import com.openrealm.net.core.nettypes.SerializableFloat;
import com.openrealm.net.core.nettypes.SerializableInt;
import com.openrealm.net.core.nettypes.SerializableLong;
import com.openrealm.net.core.nettypes.SerializableShort;
import com.openrealm.net.core.nettypes.SerializableString;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.data.GameSpriteManager;
import com.openrealm.game.model.PortalModel;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Streamable
public class NetPortal extends SerializableFieldType<NetPortal> {
	@SerializableField(order = 0, type = SerializableLong.class)
	private long id;
	@SerializableField(order = 1, type = SerializableShort.class)
	private short portalId;
	@SerializableField(order = 2, type = SerializableLong.class)
	private long fromRealmId;
	@SerializableField(order = 3, type = SerializableLong.class)
	private long toRealmId;
	@SerializableField(order = 4, type = SerializableLong.class)
	private long expires;
	@SerializableField(order = 5, type = Vector2f.class)
	private Vector2f pos;
	@SerializableField(order = 6, type = SerializableString.class)
	private String targetLabel;
	@SerializableField(order = 7, type = SerializableFloat.class)
	private float targetDifficulty;
	@SerializableField(order = 8, type = SerializableInt.class)
	private int targetPlayerCount;
	@SerializableField(order = 9, type = SerializableLong.class)
	private long targetPurificationProgress;
	@SerializableField(order = 10, type = SerializableLong.class)
	private long targetPurificationGoal;
	@SerializableField(order = 11, type = SerializableInt.class)
	private int targetTier;
	@SerializableField(order = 12, type = SerializableString.class)
	private String targetModifiers;

	/** Hand-rolled construction from Portal — bypasses ModelMapper reflection. */
	public static NetPortal fromPortal(Portal p) {
		final NetPortal n = new NetPortal();
		n.id = p.getId();
		n.portalId = (short) p.getPortalId();
		n.fromRealmId = p.getFromRealmId();
		n.toRealmId = p.getToRealmId();
		n.expires = p.getExpires();
		n.pos = p.getPos();
		n.targetLabel = p.getTargetLabel() != null ? p.getTargetLabel() : "";
		n.targetDifficulty = p.getTargetDifficulty();
		n.targetPlayerCount = p.getTargetPlayerCount();
		n.targetPurificationProgress = p.getTargetPurificationProgress();
		n.targetPurificationGoal = p.getTargetPurificationGoal();
		n.targetTier = p.getTargetTier();
		n.targetModifiers = p.getTargetModifiers() != null ? p.getTargetModifiers() : "";
		return n;
	}

	public Portal asPortal() {
		Portal p = new Portal();
		p.setId(this.getId());
		p.setPortalId(this.getPortalId());
		p.setFromRealmId(this.getFromRealmId());
		p.setToRealmId(this.getToRealmId());
		p.setExpires(this.getExpires());
		p.setPos(this.getPos());
		p.setTargetLabel(this.getTargetLabel());
		p.setTargetDifficulty(this.getTargetDifficulty());
		p.setTargetPlayerCount(this.getTargetPlayerCount());
		p.setTargetPurificationProgress(this.getTargetPurificationProgress());
		p.setTargetPurificationGoal(this.getTargetPurificationGoal());
		p.setTargetTier(this.getTargetTier());
		p.setTargetModifiers(this.getTargetModifiers());
		// Load sprite — Portal.render() short-circuits when sprite is null,
		// so without this every portal stays invisible despite being in the
		// realm. Mirrors NetEnemy / NetBullet sprite resolution.
		try {
			PortalModel model =
					GameDataManager.PORTALS != null
							? GameDataManager.PORTALS.get((int) this.getPortalId())
							: null;
			if (model != null) {
				p.setSprite(GameSpriteManager.loadSprite(model));
			}
		} catch (Exception ignored) { /* portal renders blank rather than crashing */ }
		return p;
	}

}
