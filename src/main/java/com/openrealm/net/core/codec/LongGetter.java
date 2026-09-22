package com.openrealm.net.core.codec;

@FunctionalInterface
public interface LongGetter<T> {
	long get(T source);
}
