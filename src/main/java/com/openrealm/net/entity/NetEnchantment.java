package com.openrealm.net.entity;

import java.io.DataInputStream;
import java.io.DataOutputStream;

import com.openrealm.net.Streamable;
import com.openrealm.net.core.SerializableFieldType;
import com.openrealm.net.core.codec.StreamCodec;

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

    public static final StreamCodec<NetEnchantment> CODEC = StreamCodec.builder(NetEnchantment::new)
            .int8(NetEnchantment::getStatId, NetEnchantment::setStatId)
            .int8(NetEnchantment::getDeltaValue, NetEnchantment::setDeltaValue)
            .int8(NetEnchantment::getPixelX, NetEnchantment::setPixelX)
            .int8(NetEnchantment::getPixelY, NetEnchantment::setPixelY)
            .int32(NetEnchantment::getPixelColor, NetEnchantment::setPixelColor)
            .build();

    @Override
    public int write(NetEnchantment value, DataOutputStream stream) throws Exception {
        return CODEC.write(value, stream);
    }

    @Override
    public NetEnchantment read(DataInputStream stream) throws Exception {
        return CODEC.read(stream);
    }
}
