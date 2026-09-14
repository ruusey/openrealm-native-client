package com.openrealm.net.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;

import com.openrealm.net.Streamable;

@Streamable
// Reads as null, writes nothing (no-op placeholder field type).
public class EmptyField extends SerializableFieldType<EmptyField>{

	@Override
	public EmptyField read(DataInputStream stream) throws Exception {
		return null;
	}

	@Override
	public int write(EmptyField value, DataOutputStream stream) throws Exception {
		return 0;
	}

}
