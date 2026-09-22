package com.openrealm.net.core.codec;

@FunctionalInterface
public interface FloatGetter<T> {
	float get(T source);
}
