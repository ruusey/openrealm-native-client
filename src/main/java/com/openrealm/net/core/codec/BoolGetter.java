package com.openrealm.net.core.codec;

@FunctionalInterface
public interface BoolGetter<T> {
	boolean get(T source);
}
