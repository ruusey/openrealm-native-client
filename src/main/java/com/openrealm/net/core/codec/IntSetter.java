package com.openrealm.net.core.codec;

@FunctionalInterface
public interface IntSetter<T> {
	void set(T target, int value);
}
