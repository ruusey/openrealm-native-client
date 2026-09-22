package com.openrealm.net.core.codec;

@FunctionalInterface
public interface BoolSetter<T> {
	void set(T target, boolean value);
}
