package com.openrealm.net.entity;

import java.io.DataInputStream;
import java.io.DataOutputStream;

import com.openrealm.game.entity.item.Stats;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.SerializableFieldType;
import com.openrealm.net.core.codec.StreamCodec;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@Streamable
@AllArgsConstructor
public class NetStats extends SerializableFieldType<NetStats> {
	private int hp;
	private short mp;
	private short def;
	private short str;
	private short spd;
	private short dex;
	private short vit;
	private short wis;

	public static final StreamCodec<NetStats> CODEC = StreamCodec.builder(NetStats::new)
			.int32(NetStats::getHp, NetStats::setHp)
			.int16(NetStats::getMp, NetStats::setMp)
			.int16(NetStats::getDef, NetStats::setDef)
			.int16(NetStats::getStr, NetStats::setStr)
			.int16(NetStats::getSpd, NetStats::setSpd)
			.int16(NetStats::getDex, NetStats::setDex)
			.int16(NetStats::getVit, NetStats::setVit)
			.int16(NetStats::getWis, NetStats::setWis)
			.build();

	public NetStats() {
		this.hp = 0;
		this.mp = 0;
		this.def = 0;
		this.str = 0;
		this.spd = 0;
		this.dex = 0;
		this.vit = 0;
		this.wis = 0;
	}

	@Override
	public int write(NetStats value, DataOutputStream stream) throws Exception {
		return CODEC.write(value, stream);
	}

	@Override
	public NetStats read(DataInputStream stream) throws Exception {
		return CODEC.read(stream);
	}

	public static NetStats fromStats(Stats stats) {
		if (stats == null) return new NetStats();
		return new NetStats(
			stats.getHp(), (short) stats.getMp(),
			(short) stats.getDef(), (short) stats.getStr(),
			(short) stats.getSpd(), (short) stats.getDex(),
			(short) stats.getVit(), (short) stats.getWis()
		);
	}

	public Stats asStats() {
		return Stats.builder().hp(this.hp).mp(this.mp).def(this.def).str(this.str).spd(this.spd).dex(this.dex)
				.vit(this.vit).wis(this.wis).build();
	}

}
