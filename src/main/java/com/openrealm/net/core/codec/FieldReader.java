package com.openrealm.net.core.codec;

import java.io.DataInputStream;

/** Reads one field from the stream into an existing {@code T}. */
@FunctionalInterface
interface FieldReader<T> {
	void read(DataInputStream stream, T target) throws Exception;
}
