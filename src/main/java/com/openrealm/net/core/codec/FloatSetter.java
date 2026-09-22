package com.openrealm.net.core.codec;

@FunctionalInterface
public interface FloatSetter<T> {
	void set(T target, float value);
}
