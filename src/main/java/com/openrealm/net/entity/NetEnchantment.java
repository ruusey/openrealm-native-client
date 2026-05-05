package com.openrealm.net.entity;

import java.io.DataInputStream;
import java.io.DataOutputStream;

import com.openrealm.net.Streamable;
import com.openrealm.net.core.SerializableFieldType;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Streamable
public class NetEnchantment extends SerializableFieldType<NetEnchantment> {
    private byte statId;
    private byte deltaValue;
    private byte pixelX;
    private byte pixelY;
    private int pixelColor;
    private byte effectType;
    private byte param1;
    private short magnitude;
    private int durationMs;

    public NetEnchantment(byte statId, byte deltaValue, byte pixelX, byte pixelY, int pixelColor) {
        this.statId = statId;
        this.deltaValue = deltaValue;
        this.pixelX = pixelX;
        this.pixelY = pixelY;
        this.pixelColor = pixelColor;
        this.effectType = 0;
        this.param1 = statId;
        this.magnitude = deltaValue;
        this.durationMs = 0;
    }

    @Override
    public int write(NetEnchantment value, DataOutputStream stream) throws Exception {
        final NetEnchantment v = value == null ? new NetEnchantment() : value;
        stream.writeByte(v.statId);
        stream.writeByte(v.deltaValue);
        stream.writeByte(v.pixelX);
        stream.writeByte(v.pixelY);
        stream.writeInt(v.pixelColor);
        stream.writeByte(v.effectType);
        stream.writeByte(v.param1);
        stream.writeShort(v.magnitude);
        stream.writeInt(v.durationMs);
        return 16;
    }

    @Override
    public NetEnchantment read(DataInputStream stream) throws Exception {
        final NetEnchantment v = new NetEnchantment();
        v.statId = stream.readByte();
        v.deltaValue = stream.readByte();
        v.pixelX = stream.readByte();
        v.pixelY = stream.readByte();
        v.pixelColor = stream.readInt();
        v.effectType = stream.readByte();
        v.param1 = stream.readByte();
        v.magnitude = stream.readShort();
        v.durationMs = stream.readInt();
        return v;
    }
}
