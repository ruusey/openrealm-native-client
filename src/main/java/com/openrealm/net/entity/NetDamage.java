package com.openrealm.net.entity;

import java.io.DataInputStream;
import java.io.DataOutputStream;

import com.openrealm.net.Streamable;
import com.openrealm.net.core.SerializableFieldType;
import com.openrealm.net.core.codec.StreamCodec;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@Streamable
@AllArgsConstructor
public class NetDamage extends SerializableFieldType<NetDamage> {
	private int projectileGroupId;
	private short min;
	private short max;

	public NetDamage() {
		this.projectileGroupId = -1;
		this.min = -1;
		this.max =-1;
	}

	public static final StreamCodec<NetDamage> CODEC = StreamCodec.builder(NetDamage::new)
			.int32(NetDamage::getProjectileGroupId, NetDamage::setProjectileGroupId)
			.int16(NetDamage::getMin, NetDamage::setMin)
			.int16(NetDamage::getMax, NetDamage::setMax)
			.build();

	@Override
	public int write(NetDamage value, DataOutputStream stream) throws Exception {
		return CODEC.write(value, stream);
	}

	@Override
	public NetDamage read(DataInputStream stream) throws Exception {
		return CODEC.read(stream);
	}
}
