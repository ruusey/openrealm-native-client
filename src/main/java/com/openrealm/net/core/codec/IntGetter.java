package com.openrealm.net.core.codec;

@FunctionalInterface
public interface IntGetter<T> {
	int get(T source);
}
