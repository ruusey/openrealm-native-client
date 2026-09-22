package com.openrealm.net.core.codec;

@FunctionalInterface
public interface ByteGetter<T> {
	byte get(T source);
}
