package com.openrealm.net.core.codec;

@FunctionalInterface
public interface LongSetter<T> {
	void set(T target, long value);
}
