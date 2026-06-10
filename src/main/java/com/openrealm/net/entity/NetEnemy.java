package com.openrealm.net.entity;

import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.data.GameSpriteManager;
import com.openrealm.game.entity.Enemy;
import com.openrealm.game.graphics.SpriteSheet;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.EnemyModel;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.SerializableFieldType;
import com.openrealm.net.core.nettypes.SerializableFloat;
import com.openrealm.net.core.nettypes.SerializableInt;
import com.openrealm.net.core.nettypes.SerializableLong;
import com.openrealm.net.core.nettypes.SerializableShort;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
@Streamable
@AllArgsConstructor
@NoArgsConstructor
public class NetEnemy extends SerializableFieldType<NetEnemy> {
	
	@SerializableField(order = 0, type = SerializableLong.class)
	private long id;
	@SerializableField(order = 1, type = SerializableInt.class)
	private int enemyId;
	@SerializableField(order = 2, type = SerializableInt.class)
	private int weaponId;
	@SerializableField(order = 3, type = SerializableShort.class)
	private short size;
	@SerializableField(order = 4, type = Vector2f.class)
	private Vector2f pos;
	@SerializableField(order = 5, type = SerializableFloat.class)
	private float dX;
	@SerializableField(order = 6, type = SerializableFloat.class)
	private float dY;
	@SerializableField(order = 7, type = SerializableFloat.class)
	private float difficulty;
	@SerializableField(order = 8, type = SerializableInt.class)
	private int health;
	@SerializableField(order = 9, type = SerializableInt.class)
	private int maxHealth;
	// Compact short ID for bandwidth-efficient movement packets.
	// Assigned by ShortIdAllocator when entity enters a realm.
	@SerializableField(order = 10, type = SerializableShort.class)
	private short shortId;

	/** Hand-rolled construction from Enemy — bypasses ModelMapper reflection. */
	public static NetEnemy fromEnemy(Enemy e) {
		final NetEnemy n = new NetEnemy();
		n.id = e.getId();
		n.enemyId = e.getEnemyId();
		n.weaponId = e.getWeaponId();
		n.size = (short) e.getSize();
		n.pos = e.getPos();
		n.dX = e.getDx();
		n.dY = e.getDy();
		n.difficulty = e.getDifficulty();
		n.health = e.getHealth();
		final EnemyModel enemyModel = GameDataManager.ENEMIES.get(e.getEnemyId());
		n.maxHealth = (int) (enemyModel != null
				? enemyModel.getHealth() * e.getDifficulty()
				: e.getHealth());
		// shortId is set by the LoadPacket.from(...allocator) overload.
		return n;
	}

	public Enemy asEnemy() {
		final Enemy e = new Enemy();
		e.setId(this.getId());
		e.setEnemyId(this.getEnemyId());
		e.setWeaponId(this.getWeaponId());
		e.setSize(this.getSize());
		e.setPos(this.getPos());
		e.setDx(this.getDX());
		e.setDy(this.getDY());
		e.setDifficulty(this.getDifficulty());
		e.setHealth(this.getHealth());
		if (this.maxHealth > 0) {
			e.setHealthpercent((float) this.health / (float) this.maxHealth);
		}
		// Wire the sprite sheet here so Enemy.render() doesn't bail at its
		// null-check. Without this, every enemy is invisible because the
		// renderer's first line is `if (getSpriteSheet() == null) return;`.
		// Web-client equivalent: gameState.enemyData[enemyId] -> spriteKey lookup.
		final EnemyModel model = GameDataManager.ENEMIES != null
				? GameDataManager.ENEMIES.get(this.getEnemyId())
				: null;
		if (model != null) {
			// Prefer an animated sheet (idle/walk/attack sets) when the enemy has an
			// animations.json entry; otherwise fall back to the static single-frame sheet.
			SpriteSheet sheet = GameSpriteManager.loadEnemySprites(this.getEnemyId());
			if (sheet == null) {
				sheet = GameSpriteManager.getSpriteSheet(model);
			}
			if (sheet != null) {
				e.setSpriteSheet(sheet);
			} else {
				log.warn("[ENEMY] no sprite sheet for enemyId={} spriteKey={}",
						this.getEnemyId(), model.getSpriteKey());
			}
		} else {
			log.warn("[ENEMY] no model found for enemyId={}", this.getEnemyId());
		}
		return e;
	}
}
