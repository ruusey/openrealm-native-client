package com.openrealm.net.core.nettypes;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;

import com.openrealm.net.NetConstants;
import com.openrealm.net.core.SerializableFieldType;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SerializableString extends SerializableFieldType<String> {

	@Override
	public String read(DataInputStream stream) throws Exception {
		try {
			int te = stream.readInt();
			byte[] test = new byte[te];
			stream.read(test);
			return new String(test, StandardCharsets.UTF_8);
		} catch (Exception e) {
			e.printStackTrace();
			if(log.isDebugEnabled()) {
				log.debug("SerializableString failed to read stream. Reason: {}", e);
			}
			return "";
		}
	}

	@Override
	public int write(String value, DataOutputStream stream) throws Exception {
		if(stream==null)throw new Exception("SerializableString Error: target stream cannot be null");
		final String toUse = value == null ? "" : value;
		// Prefix the UTF-8 BYTE length, not String.length(): a char-count prefix desyncs the stream on any multibyte char.
		final byte[] encoded = toUse.getBytes(StandardCharsets.UTF_8);
		stream.writeInt(encoded.length);
		stream.write(encoded);
		return NetConstants.INT16_LENGTH + encoded.length;
	}
}
