package com.openrealm.net.core.codec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Collects the optional field groups of a {@link PacketCodecBuilder#maskedSections} block.
 * Each section rides on the wire only when its {@code present} predicate holds; a single
 * mask byte written before the groups records which are present. Section bodies read and
 * write against the same target object as the surrounding codec.
 */
public final class MaskedSectionSet<T> {
	final List<SectionSpec<T>> sections = new ArrayList<>();

	public MaskedSectionSet<T> section(int bit, Predicate<T> present, Consumer<PacketCodecBuilder<T>> body) {
		final PacketCodecBuilder<T> collector = new PacketCodecBuilder<>(null);
		body.accept(collector);
		this.sections.add(new SectionSpec<>(bit, present, collector.writers, collector.readers));
		return this;
	}
}
