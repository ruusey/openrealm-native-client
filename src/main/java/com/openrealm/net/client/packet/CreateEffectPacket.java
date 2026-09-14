package com.openrealm.net.client.packet;

import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.nettypes.SerializableByte;
import com.openrealm.net.core.nettypes.SerializableFloat;
import com.openrealm.net.core.nettypes.SerializableLong;
import com.openrealm.net.core.nettypes.SerializableShort;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Data
@EqualsAndHashCode(callSuper = true)
@Slf4j
@Streamable
@Builder
@AllArgsConstructor
@NoArgsConstructor
@PacketId(packetId = (byte)21)
public class CreateEffectPacket extends Packet {
	@SerializableField(order = 0, type = SerializableShort.class)
	private short effectType;
	@SerializableField(order = 1, type = SerializableFloat.class)
	private float posX;
	@SerializableField(order = 2, type = SerializableFloat.class)
	private float posY;
	@SerializableField(order = 3, type = SerializableFloat.class)
	private float radius;
	@SerializableField(order = 4, type = SerializableShort.class)
	private short duration;
	@SerializableField(order = 5, type = SerializableFloat.class)
	private float targetPosX;
	@SerializableField(order = 6, type = SerializableFloat.class)
	private float targetPosY;
	// Item tier 0-6 (0 = non-tiered); client recolors the effect by tier.
	@SerializableField(order = 7, type = SerializableByte.class)
	private byte tier;
	// Caster entity id (0 = enemy/environmental); drives the hide-ally-effects option.
	@SerializableField(order = 8, type = SerializableLong.class)
	private long ownerId;

	public static final short EFFECT_HEAL_RADIUS = 0;
	public static final short EFFECT_VAMPIRISM = 1;
	public static final short EFFECT_STASIS_FIELD = 2;
	public static final short EFFECT_CHAIN_LIGHTNING = 3;
	public static final short EFFECT_CURSE_RADIUS = 4;
	public static final short EFFECT_POISON_SPLASH = 5;
	public static final short EFFECT_TRAP_THROW = 6;
	public static final short EFFECT_TRAP_PLACED = 7;
	public static final short EFFECT_TRAP_TRIGGER = 8;
	public static final short EFFECT_SMOKE_POOF = 9;
	public static final short EFFECT_WIZARD_BURST = 10;
	public static final short EFFECT_KNIGHT_SHOCKWAVE = 11;
	public static final short EFFECT_WARRIOR_BUFF = 12;
	public static final short EFFECT_NINJA_DASH = 13;
	public static final short EFFECT_PALADIN_SEAL = 14;
	public static final short EFFECT_WATER_FOUNTAIN = 15;
	public static final short EFFECT_SHIELD_DOME = 16;
	public static final short EFFECT_TAUNT_ROAR  = 17;
	public static final short EFFECT_BRACE_STANCE = 18;
	public static final short EFFECT_FROST_NOVA   = 19;
	public static final short EFFECT_BLINK_GLYPH  = 20;
	// id 21 retired (was EFFECT_HUNTERS_RETICLE)
	public static final short EFFECT_POISON_CLOUD    = 22;
	public static final short EFFECT_LIFE_DRAIN      = 23;
	public static final short EFFECT_BONE_SPIKES     = 24;
	public static final short EFFECT_LIGHTNING_STRIKE = 25;
	public static final short EFFECT_MANA_BOLT       = 26;
	public static final short EFFECT_TIME_STOP       = 27;
	public static final short EFFECT_BEAST_CLAWS     = 28;
	public static final short EFFECT_SMITE_FLASH     = 29;
	public static final short EFFECT_DEATH_BLOSSOM   = 30;
	public static final short EFFECT_INSPIRE_BLOOM   = 31;
	public static final short EFFECT_RECKLESS_SLASH  = 32;
	public static final short EFFECT_STAR_SHURIKEN   = 33;
	public static final short EFFECT_SNARE_GEAR      = 34;
	public static final short EFFECT_COMBUSTION_TRAP = 35;
	public static final short EFFECT_WAR_CRY_WAVE    = 36;
	public static final short EFFECT_CALTROPS        = 37;
	public static final short EFFECT_ARCANE_AURA     = 38;
	public static final short EFFECT_HASTE_WIND      = 39;
	public static final short EFFECT_BANNER_RAISE    = 40;
	public static final short EFFECT_RAMPAGE_AURA    = 41;
	public static final short EFFECT_STORM_AURA      = 42;
	public static final short EFFECT_DEATH_PACT_AURA = 43;
	public static final short EFFECT_BLADE_STORM     = 44;
	public static final short EFFECT_SOUL_VORTEX     = 45;
	// BLADE_ORBIT/BLADE_BLENDER: tier byte selects shuriken sprite (0..5 -> item 298..303).
	public static final short EFFECT_BLADE_ORBIT     = 46;
	public static final short EFFECT_BLADE_BLENDER   = 47;
	// ids 48-50 retired (were REALITY_TEAR / PHANTOM_STRIKE / STASIS_LOCK)
	public static final short EFFECT_SANCTUARY_DOME  = 51;
	public static final short EFFECT_VAMPIRIC_LATCH  = 52;
	public static final short EFFECT_RAPIER_STAB     = 53;
	public static final short EFFECT_LOW_SWING       = 54;
	public static final short EFFECT_DISARM_FLOURISH = 55;
	public static final short EFFECT_DIVINE_BEAM     = 56;
	public static final short EFFECT_FORTIFY_AURA    = 57;
	public static final short EFFECT_GROUND_POUND    = 58;
	public static final short EFFECT_DRUID_ROOTS     = 59;
	public static final short EFFECT_DRUID_MOONLIGHT = 60;
	public static final short EFFECT_DRUID_WILD_SURGE = 61;
	public static final short EFFECT_MELEE_SWING = 62;
	public static final short EFFECT_PURIFY_CIRCLE = 63;
	public static final short EFFECT_BEAM_WARNING = 64;

	public static CreateEffectPacket aoeEffect(short type, float x, float y, float radius, short duration) {
		return aoeEffect(type, x, y, radius, duration, (byte) 0);
	}

	public static CreateEffectPacket aoeEffect(short type, float x, float y, float radius, short duration, byte tier) {
		return CreateEffectPacket.builder()
			.effectType(type).posX(x).posY(y).radius(radius)
			.duration(duration).targetPosX(0).targetPosY(0).tier(tier).build();
	}

	public static CreateEffectPacket lineEffect(short type, float fromX, float fromY, float toX, float toY, short duration) {
		return lineEffect(type, fromX, fromY, toX, toY, duration, (byte) 0);
	}

	public static CreateEffectPacket lineEffect(short type, float fromX, float fromY, float toX, float toY, short duration, byte tier) {
		return CreateEffectPacket.builder()
			.effectType(type).posX(fromX).posY(fromY).radius(0)
			.duration(duration).targetPosX(toX).targetPosY(toY).tier(tier).build();
	}
}
