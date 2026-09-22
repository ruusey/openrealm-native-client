package com.openrealm.net.core.codec;

import java.io.DataOutputStream;

/** Writes one field of {@code T} to the stream and returns the byte count written. */
@FunctionalInterface
interface FieldWriter<T> {
	int write(T source, DataOutputStream stream) throws Exception;
}
