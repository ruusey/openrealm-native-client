package com.openrealm.net.core.codec;

@FunctionalInterface
public interface ShortGetter<T> {
	short get(T source);
}
