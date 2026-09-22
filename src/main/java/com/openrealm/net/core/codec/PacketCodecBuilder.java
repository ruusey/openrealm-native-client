package com.openrealm.net.core.codec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.openrealm.net.NetConstants;

/**
 * Declares the wire layout of a {@code T} field-by-field, in declaration order. Every
 * primitive maps to a getter/setter pair (method references, not reflection), so the
 * builder chain reads top-to-bottom exactly like the bytes on the wire. Build once into
 * a reusable {@link StreamCodec} and hold it in a {@code static final} on the packet type.
 */
public final class PacketCodecBuilder<T> {
	private final Supplier<T> factory;
	final List<FieldWriter<T>> writers = new ArrayList<>();
	final List<FieldReader<T>> readers = new ArrayList<>();

	PacketCodecBuilder(Supplier<T> factory) {
		this.factory = factory;
	}

	public PacketCodecBuilder<T> int8(ByteGetter<T> getter, ByteSetter<T> setter) {
		this.writers.add((source, stream) -> {
			stream.writeByte(getter.get(source));
			return NetConstants.BYTE_LENGTH;
		});
		this.readers.add((stream, target) -> setter.set(target, stream.readByte()));
		return this;
	}

	public PacketCodecBuilder<T> bool(BoolGetter<T> getter, BoolSetter<T> setter) {
		this.writers.add((source, stream) -> {
			stream.writeBoolean(getter.get(source));
			return NetConstants.BOOLEAN_LENGTH;
		});
		this.readers.add((stream, target) -> setter.set(target, stream.readBoolean()));
		return this;
	}

	public PacketCodecBuilder<T> int16(ShortGetter<T> getter, ShortSetter<T> setter) {
		this.writers.add((source, stream) -> {
			stream.writeShort(getter.get(source));
			return NetConstants.INT16_LENGTH;
		});
		this.readers.add((stream, target) -> setter.set(target, stream.readShort()));
		return this;
	}

	public PacketCodecBuilder<T> int32(IntGetter<T> getter, IntSetter<T> setter) {
		this.writers.add((source, stream) -> {
			stream.writeInt(getter.get(source));
			return NetConstants.INT32_LENGTH;
		});
		this.readers.add((stream, target) -> setter.set(target, stream.readInt()));
		return this;
	}

	public PacketCodecBuilder<T> int64(LongGetter<T> getter, LongSetter<T> setter) {
		this.writers.add((source, stream) -> {
			stream.writeLong(getter.get(source));
			return NetConstants.INT64_LENGTH;
		});
		this.readers.add((stream, target) -> setter.set(target, stream.readLong()));
		return this;
	}

	public PacketCodecBuilder<T> float32(FloatGetter<T> getter, FloatSetter<T> setter) {
		this.writers.add((source, stream) -> {
			stream.writeFloat(getter.get(source));
			return NetConstants.FLOAT_LENGTH;
		});
		this.readers.add((stream, target) -> setter.set(target, stream.readFloat()));
		return this;
	}

	/** UTF-8 string prefixed with its int32 byte length. */
	public PacketCodecBuilder<T> utf(Function<T, String> getter, BiConsumer<T, String> setter) {
		this.writers.add((source, stream) -> StreamCodec.writeUtf(getter.apply(source), stream));
		this.readers.add((stream, target) -> setter.accept(target, StreamCodec.readUtf(stream)));
		return this;
	}

	/** A single nested value encoded by its own codec. */
	public <V> PacketCodecBuilder<T> nested(StreamCodec<V> codec, Function<T, V> getter, BiConsumer<T, V> setter) {
		this.writers.add((source, stream) -> codec.write(getter.apply(source), stream));
		this.readers.add((stream, target) -> setter.accept(target, codec.read(stream)));
		return this;
	}

	/** A list of nested values prefixed with an int32 element count. */
	public <V> PacketCodecBuilder<T> list(StreamCodec<V> codec, Function<T, List<V>> getter, BiConsumer<T, List<V>> setter) {
		this.writers.add((source, stream) -> {
			final List<V> items = getter.apply(source);
			final int count = items == null ? 0 : items.size();
			stream.writeInt(count);
			int bytes = NetConstants.INT32_LENGTH;
			for (int i = 0; i < count; i++) {
				bytes += codec.write(items.get(i), stream);
			}
			return bytes;
		});
		this.readers.add((stream, target) -> {
			final int count = stream.readInt();
			final List<V> items = new ArrayList<>(Math.max(0, count));
			for (int i = 0; i < count; i++) {
				items.add(codec.read(stream));
			}
			setter.accept(target, items);
		});
		return this;
	}

	/** A boxed short array prefixed with an int32 element count; null entries write as 0. */
	public PacketCodecBuilder<T> shortArray(Function<T, Short[]> getter, BiConsumer<T, Short[]> setter) {
		this.writers.add((source, stream) -> {
			final Short[] items = getter.apply(source);
			final int count = items == null ? 0 : items.length;
			stream.writeInt(count);
			int bytes = NetConstants.INT32_LENGTH;
			for (int i = 0; i < count; i++) {
				stream.writeShort(items[i] == null ? 0 : items[i]);
				bytes += NetConstants.INT16_LENGTH;
			}
			return bytes;
		});
		this.readers.add((stream, target) -> {
			final int count = stream.readInt();
			final Short[] items = new Short[Math.max(0, count)];
			for (int i = 0; i < count; i++) {
				items[i] = stream.readShort();
			}
			setter.accept(target, items);
		});
		return this;
	}

	/**
	 * A block of optional field groups gated by a single mask byte. The byte is written
	 * after the preceding fields; each present section's fields follow in declaration order.
	 * Straight-line packets that trigger no section pay only the one mask byte.
	 */
	public PacketCodecBuilder<T> maskedSections(Consumer<MaskedSectionSet<T>> configurer) {
		final MaskedSectionSet<T> set = new MaskedSectionSet<>();
		configurer.accept(set);
		final List<SectionSpec<T>> sections = set.sections;
		this.writers.add((source, stream) -> {
			int mask = 0;
			for (final SectionSpec<T> section : sections) {
				if (section.present.test(source)) mask |= section.bit;
			}
			stream.writeByte(mask);
			int bytes = NetConstants.BYTE_LENGTH;
			for (final SectionSpec<T> section : sections) {
				if (section.present.test(source)) {
					for (final FieldWriter<T> writer : section.writers) {
						bytes += writer.write(source, stream);
					}
				}
			}
			return bytes;
		});
		this.readers.add((stream, target) -> {
			final int mask = stream.readByte() & 0xFF;
			for (final SectionSpec<T> section : sections) {
				if ((mask & section.bit) != 0) {
					for (final FieldReader<T> reader : section.readers) {
						reader.read(stream, target);
					}
				}
			}
		});
		return this;
	}

	public StreamCodec<T> build() {
		return new StreamCodec<>(this.factory, this.writers, this.readers);
	}
}
