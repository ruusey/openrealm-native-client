package com.openrealm.net.core.codec;

@FunctionalInterface
public interface ByteSetter<T> {
	void set(T target, byte value);
}
