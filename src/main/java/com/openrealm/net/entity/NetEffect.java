package com.openrealm.net.entity;

import java.io.DataInputStream;
import java.io.DataOutputStream;

import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.entity.item.Effect;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.SerializableFieldType;
import com.openrealm.net.core.codec.StreamCodec;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@Streamable
@AllArgsConstructor
public class NetEffect extends SerializableFieldType<NetEffect> {
	private boolean self;
	private short effectId;
	private long duration;
	private long cooldownDuration;
	private short mpCost;

	public NetEffect() {
		this.self = false;
		this.effectId = -1;
		this.duration = -1l;
		this.cooldownDuration = -1l;
		this.mpCost = -1;
	}

	public static final StreamCodec<NetEffect> CODEC = StreamCodec.builder(NetEffect::new)
			.bool(NetEffect::isSelf, NetEffect::setSelf)
			.int16(NetEffect::getEffectId, NetEffect::setEffectId)
			.int64(NetEffect::getDuration, NetEffect::setDuration)
			.int64(NetEffect::getCooldownDuration, NetEffect::setCooldownDuration)
			.int16(NetEffect::getMpCost, NetEffect::setMpCost)
			.build();

	public Effect asEffect() {
		return Effect.builder().self(this.self).effectId(StatusEffectType.valueOf(this.effectId)).duration(this.duration)
				.cooldownDuration(this.cooldownDuration).mpCost(this.mpCost).build();
	}

	@Override
	public int write(NetEffect value, DataOutputStream stream) throws Exception {
		return CODEC.write(value, stream);
	}

	@Override
	public NetEffect read(DataInputStream stream) throws Exception {
		return CODEC.read(stream);
	}
}
