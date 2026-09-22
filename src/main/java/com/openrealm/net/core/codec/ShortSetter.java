package com.openrealm.net.core.codec;

@FunctionalInterface
public interface ShortSetter<T> {
	void set(T target, short value);
}
