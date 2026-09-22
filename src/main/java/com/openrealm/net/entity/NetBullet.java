package com.openrealm.net.entity;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Arrays;

import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.data.GameSpriteManager;
import com.openrealm.game.entity.Bullet;
import com.openrealm.game.graphics.SpriteSheet;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.ProjectileGroup;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.SerializableFieldType;
import com.openrealm.net.core.codec.StreamCodec;
import com.openrealm.net.core.nettypes.*;


import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import java.util.List;

@Getter
@Setter
@Slf4j
@EqualsAndHashCode(callSuper=false)
@AllArgsConstructor
@NoArgsConstructor
@Streamable
public class NetBullet extends SerializableFieldType<NetBullet> {
	
	@SerializableField(order = 0, type = SerializableLong.class)
	private long id;
	@SerializableField(order = 1, type = SerializableInt.class)
	private int projectileId;
	@SerializableField(order = 2, type = SerializableShort.class)
	private short size;
	@SerializableField(order = 3, type = Vector2f.class)
	private Vector2f pos;
	@SerializableField(order = 4, type = SerializableFloat.class)
	private float dX;
	@SerializableField(order = 5, type = SerializableFloat.class)
	private float dY;
	@SerializableField(order = 6, type = SerializableFloat.class)
	private float angle;
	@SerializableField(order = 7, type = SerializableFloat.class)
	private float magnitude;
	@SerializableField(order = 8, type = SerializableFloat.class)
	private float range;
	@SerializableField(order = 9, type = SerializableShort.class)
	private short damage;
	@SerializableField(order = 10, type = SerializableShort.class, isCollection=true)
	private Short[] flags;
	@SerializableField(order = 11, type = SerializableBoolean.class)
	private boolean invert;
	@SerializableField(order = 12, type = SerializableLong.class)
	private long timeStep;
	@SerializableField(order = 13, type = SerializableShort.class)
	private short amplitude;
	@SerializableField(order = 14, type = SerializableShort.class)
	private short frequency;
	@SerializableField(order = 15, type = SerializableLong.class)
	private long createdTime;
	@SerializableField(order = 16, type = SerializableFloat.class)
	private float orbitCenterX;
	@SerializableField(order = 17, type = SerializableFloat.class)
	private float orbitCenterY;
	@SerializableField(order = 18, type = SerializableFloat.class)
	private float orbitRadius;
	@SerializableField(order = 19, type = SerializableFloat.class)
	private float orbitPhase;
	@SerializableField(order = 20, type = SerializableLong.class)
	private long srcEntityId;
	@SerializableField(order = 21, type = SerializableInt.class)
	private int lifetimeTicks;
	@SerializableField(order = 22, type = SerializableShort.class)
	private short length;
	@SerializableField(order = 23, type = SerializableLong.class)
	private long targetEntityId;
	@SerializableField(order = 24, type = SerializableString.class)
	private String overrideSpriteKey;
	@SerializableField(order = 25, type = SerializableInt.class)
	private int overrideSpriteRow;
	@SerializableField(order = 26, type = SerializableInt.class)
	private int overrideSpriteCol;
	@SerializableField(order = 27, type = SerializableInt.class)
	private int overrideSpriteSize;
	@SerializableField(order = 28, type = SerializableInt.class)
	private int overrideSpriteHeight;

	// Section-presence bit mask; bit values MUST match server + webclient NetBullet serializers.
	private static final byte SECT_WAVY   = 0x01;
	private static final byte SECT_ORBIT  = 0x02;
	private static final byte SECT_HOMING = 0x04;
	private static final byte SECT_SPRITE = 0x08;

	// Authoritative wire layout (the @SerializableField annotations above are inert once
	// write/read delegate here). Base fields always ride; wavy/orbit/homing/sprite groups
	// ride only when non-default, gated by a section-mask byte. MUST byte-match server + webclient.
	public static final StreamCodec<NetBullet> CODEC = StreamCodec.builder(NetBullet::new)
			.int64(NetBullet::getId, NetBullet::setId)
			.int32(NetBullet::getProjectileId, NetBullet::setProjectileId)
			.int16(NetBullet::getSize, NetBullet::setSize)
			.nested(Vector2f.CODEC, NetBullet::getPos, NetBullet::setPos)
			.float32(NetBullet::getDX, NetBullet::setDX)
			.float32(NetBullet::getDY, NetBullet::setDY)
			.float32(NetBullet::getAngle, NetBullet::setAngle)
			.float32(NetBullet::getMagnitude, NetBullet::setMagnitude)
			.float32(NetBullet::getRange, NetBullet::setRange)
			.int16(NetBullet::getDamage, NetBullet::setDamage)
			.int16(NetBullet::getLength, NetBullet::setLength)
			.int32(NetBullet::getLifetimeTicks, NetBullet::setLifetimeTicks)
			.int64(NetBullet::getSrcEntityId, NetBullet::setSrcEntityId)
			.int64(NetBullet::getCreatedTime, NetBullet::setCreatedTime)
			.shortArray(NetBullet::getFlags, NetBullet::setFlags)
			.maskedSections(maskedData -> maskedData
					.section(SECT_WAVY,
							p -> (p.amplitude != 0 || p.frequency != 0 || p.invert || p.timeStep != 0L),
							wavyData -> wavyData
									.bool(NetBullet::isInvert, NetBullet::setInvert)
									.int64(NetBullet::getTimeStep, NetBullet::setTimeStep)
									.int16(NetBullet::getAmplitude, NetBullet::setAmplitude)
									.int16(NetBullet::getFrequency, NetBullet::setFrequency))
					.section(SECT_ORBIT,
							p -> (p.orbitRadius != 0f),
							orbitData -> orbitData
									.float32(NetBullet::getOrbitCenterX, NetBullet::setOrbitCenterX)
									.float32(NetBullet::getOrbitCenterY, NetBullet::setOrbitCenterY)
									.float32(NetBullet::getOrbitRadius, NetBullet::setOrbitRadius)
									.float32(NetBullet::getOrbitPhase, NetBullet::setOrbitPhase))
					.section(SECT_HOMING,
							p -> (p.targetEntityId != 0L),
							homing -> homing
									.int64(NetBullet::getTargetEntityId, NetBullet::setTargetEntityId))
					.section(SECT_SPRITE,
							p -> ((p.overrideSpriteKey != null && !p.overrideSpriteKey.isEmpty())
									|| p.overrideSpriteRow != 0 || p.overrideSpriteCol != 0
									|| p.overrideSpriteSize != 0 || p.overrideSpriteHeight != 0),
							spriteOverride -> spriteOverride
									.utf(NetBullet::getOverrideSpriteKey, NetBullet::setOverrideSpriteKey)
									.int32(NetBullet::getOverrideSpriteRow, NetBullet::setOverrideSpriteRow)
									.int32(NetBullet::getOverrideSpriteCol, NetBullet::setOverrideSpriteCol)
									.int32(NetBullet::getOverrideSpriteSize, NetBullet::setOverrideSpriteSize)
									.int32(NetBullet::getOverrideSpriteHeight, NetBullet::setOverrideSpriteHeight)))
			.build();

	public static NetBullet fromBullet(Bullet b) {
		final NetBullet n = new NetBullet();
		n.id = b.getId();
		n.projectileId = b.getProjectileId();
		n.size = (short) b.getSize();
		n.pos = b.getPos();
		n.dX = b.getDx();
		n.dY = b.getDy();
		n.angle = b.getAngle();
		n.magnitude = b.getMagnitude();
		n.range = b.getRange();
		n.damage = b.getDamage();
		final List<Short> bf = b.getFlags();
		if (bf != null && !bf.isEmpty()) {
			n.flags = bf.toArray(new Short[0]);
		} else {
			n.flags = new Short[0];
		}
		n.invert = b.isInvert();
		n.timeStep = b.getTimeStep();
		n.amplitude = b.getAmplitude();
		n.frequency = b.getFrequency();
		n.createdTime = b.getCreatedTime();
		n.orbitCenterX = b.getOrbitCenterX();
		n.orbitCenterY = b.getOrbitCenterY();
		n.orbitRadius = b.getOrbitRadius();
		n.orbitPhase = b.getOrbitPhase();
		n.srcEntityId = b.getSrcEntityId();
		n.lifetimeTicks = b.getLifetimeTicks();
		n.length = b.getLength();
		n.targetEntityId = b.getTargetEntityId();
		return n;
	}

	public Bullet asBullet() {
		final Bullet bullet = new Bullet();
		bullet.setId(this.id);
		bullet.setProjectileId(this.projectileId);
		bullet.setSize(this.size);
		bullet.setPos(this.pos);
		bullet.setDx(this.dX);
		bullet.setDy(this.dY);
		bullet.setAngle(this.angle);
		bullet.setMagnitude(this.magnitude);
		bullet.setRange(this.range);
		bullet.setDamage(this.damage);
		bullet.setFlags(Arrays.asList(this.flags));
		bullet.setInvert(this.invert);
		bullet.setTimeStep(this.timeStep);
		bullet.setAmplitude(this.amplitude);
		bullet.setFrequency(this.frequency);
		bullet.setCreatedTime(this.createdTime);
		bullet.setSrcEntityId(this.srcEntityId);
		bullet.setLifetimeTicks(this.lifetimeTicks);
		bullet.setLength(this.length);
		bullet.setTargetEntityId(this.targetEntityId);
		// Per-ability sprite override: motion/rotation still come from the projectile group.
		if (this.overrideSpriteKey != null && !this.overrideSpriteKey.isBlank()) {
			final ProjectileGroup override = new ProjectileGroup();
			override.setSpriteKey(this.overrideSpriteKey);
			override.setRow(this.overrideSpriteRow);
			override.setCol(this.overrideSpriteCol);
			override.setSpriteSize(this.overrideSpriteSize);
			override.setSpriteHeight(this.overrideSpriteHeight);
			override.setAngleOffset("0");
			final SpriteSheet sheet = GameSpriteManager.getSpriteSheet(override);
			if (sheet != null) {
				bullet.setSpriteSheet(sheet);
				return bullet;
			}
			log.warn("[BULLET] override sprite failed for projectileId={} spriteKey={}",
					this.projectileId, this.overrideSpriteKey);
		}
		// Resolve projectileId -> ProjectileGroup -> spriteKey; without a sprite Bullet.render() bails.
		final ProjectileGroup group = GameDataManager.PROJECTILE_GROUPS != null
				? GameDataManager.PROJECTILE_GROUPS.get(this.projectileId)
				: null;
		if (group != null) {
			final SpriteSheet sheet = GameSpriteManager.getSpriteSheet(group);
			if (sheet != null) {
				bullet.setSpriteSheet(sheet);
			} else {
				log.warn("[BULLET] no sprite sheet for projectileId={} spriteKey={}",
						this.projectileId, group.getSpriteKey());
			}
		} else {
			log.warn("[BULLET] no projectile group for projectileId={}", this.projectileId);
		}
		return bullet;
	}

	@Override
	public int write(NetBullet value, DataOutputStream stream) throws Exception {
		return CODEC.write(value, stream);
	}

	@Override
	public NetBullet read(DataInputStream stream) throws Exception {
		return CODEC.read(stream);
	}
}
