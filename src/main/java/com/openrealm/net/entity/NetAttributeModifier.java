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
public class NetAttributeModifier extends SerializableFieldType<NetAttributeModifier> {
    private byte statId;
    private byte deltaValue;

    public static final StreamCodec<NetAttributeModifier> CODEC = StreamCodec.builder(NetAttributeModifier::new)
            .int8(NetAttributeModifier::getStatId, NetAttributeModifier::setStatId)
            .int8(NetAttributeModifier::getDeltaValue, NetAttributeModifier::setDeltaValue)
            .build();

    @Override
    public int write(NetAttributeModifier value, DataOutputStream stream) throws Exception {
        return CODEC.write(value, stream);
    }

    @Override
    public NetAttributeModifier read(DataInputStream stream) throws Exception {
        return CODEC.read(stream);
    }
}
