package com.openrealm.net.entity;

import com.openrealm.game.contants.CharacterClass;
import com.openrealm.game.data.GameSpriteManager;
import com.openrealm.game.entity.Player;
import com.openrealm.game.math.Vector2f;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.SerializableFieldType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.openrealm.net.core.nettypes.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Streamable
public class NetPlayer extends SerializableFieldType<NetPlayer>{
	@SerializableField(order = 0, type = SerializableLong.class)
	private long id;
	@SerializableField(order = 1, type = SerializableString.class)
	private String name;
	@SerializableField(order = 2, type = SerializableString.class)
	private String accountUuid;
	@SerializableField(order = 3, type = SerializableString.class)
	private String characterUuid;
	@SerializableField(order = 4, type = SerializableInt.class)
	private int classId;
	@SerializableField(order = 5, type = SerializableShort.class)
	private short size;
	@SerializableField(order = 6, type = Vector2f.class)
	private Vector2f pos;
	@SerializableField(order = 7, type = SerializableFloat.class)
	private float dX;
	@SerializableField(order = 8, type = SerializableFloat.class)
	private float dY;
	@SerializableField(order = 9, type = SerializableShort.class)
	private short shortId;
	@SerializableField(order = 10, type = SerializableString.class)
	private String chatRole;
	// Cosmetic dye id (0 = no dye), resolved client-side via dye-assets.json.
	@SerializableField(order = 11, type = SerializableInt.class)
	private int dyeId;

	public static NetPlayer fromPlayer(Player p) {
		final NetPlayer n = new NetPlayer();
		n.id = p.getId();
		n.name = p.getName();
		n.accountUuid = p.getAccountUuid();
		n.characterUuid = p.getCharacterUuid();
		n.classId = p.getClassId();
		n.size = (short) p.getSize();
		n.pos = p.getPos();
		n.dX = p.getDx();
		n.dY = p.getDy();
		n.chatRole = p.getChatRole();
		n.dyeId = p.getDyeId();
		return n;
	}

	public Player toPlayer() {
		Player p = new Player();
		p.setId(this.id);
		p.setName(this.name);
		p.setAccountUuid(this.accountUuid);
		p.setCharacterUuid(this.characterUuid);
		p.setClassId(this.classId);
		p.setSize(this.size);
		p.setPos(this.pos);
		p.setDx(this.dX);
		p.setDy(this.dY);
		p.setChatRole(this.chatRole);
		p.setDyeId(this.dyeId);
		// Load the class sprite sheet or Entity.renderBody bails and the player is invisible.
		try {
			final CharacterClass cls = CharacterClass.valueOf(this.classId);
			if (cls != null) {
				p.setSpriteSheet(GameSpriteManager.loadClassSprites(cls));
			}
		} catch (Exception ignored) { /* fall back to invisible-but-tracked */ }
		return p;
	}
}
