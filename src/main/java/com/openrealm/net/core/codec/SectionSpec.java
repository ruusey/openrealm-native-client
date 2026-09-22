package com.openrealm.net.core.codec;

import java.util.List;
import java.util.function.Predicate;

/** One optional field group in a masked-section layout, gated by a single mask bit. */
class SectionSpec<T> {
	final int bit;
	final Predicate<T> present;
	final List<FieldWriter<T>> writers;
	final List<FieldReader<T>> readers;

	SectionSpec(int bit, Predicate<T> present, List<FieldWriter<T>> writers, List<FieldReader<T>> readers) {
		this.bit = bit;
		this.present = present;
		this.writers = writers;
		this.readers = readers;
	}
}
