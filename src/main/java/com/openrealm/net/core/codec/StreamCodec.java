package com.openrealm.net.core.codec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

import com.openrealm.net.NetConstants;

/**
 * A reflection-free, declarative codec for a "hot packet" or net entity. Field order is
 * fixed at build time by the {@link PacketCodecBuilder} chain (declaration order == wire
 * order), so byte layout lives in one place instead of being spread across hand-written
 * {@code read}/{@code write} pairs with manual byte tallies.
 * <p>
 * Byte order is big-endian throughout (matching {@link DataOutputStream}), so the layout
 * stays compatible with the webclient and native client readers.
 * <p>
 * Usage — build once, reuse per instance:
 * <pre>
 * private static final StreamCodec&lt;NetStats&gt; CODEC = StreamCodec.builder(NetStats::new)
 *     .int32(NetStats::getHp,  NetStats::setHp)
 *     .int16(NetStats::getMp,  NetStats::setMp)
 *     .build();
 *
 * public int write(NetStats value, DataOutputStream stream) throws Exception { return CODEC.write(value, stream); }
 * public NetStats read(DataInputStream stream) throws Exception { return CODEC.read(stream); }
 * </pre>
 */
public final class StreamCodec<T> {
	private final Supplier<T> factory;
	private final FieldWriter<T>[] writers;
	private final FieldReader<T>[] readers;

	@SuppressWarnings("unchecked")
	StreamCodec(Supplier<T> factory, List<FieldWriter<T>> writers, List<FieldReader<T>> readers) {
		this.factory = factory;
		this.writers = writers.toArray(new FieldWriter[0]);
		this.readers = readers.toArray(new FieldReader[0]);
	}

	public static <T> PacketCodecBuilder<T> builder(Supplier<T> factory) {
		return new PacketCodecBuilder<>(factory);
	}

	/** A null value serializes as a fresh default instance, matching the old hand-coded guards. */
	public int write(T value, DataOutputStream stream) throws Exception {
		final T source = value != null ? value : this.factory.get();
		int bytes = 0;
		for (int i = 0; i < this.writers.length; i++) {
			bytes += this.writers[i].write(source, stream);
		}
		return bytes;
	}

	public T read(DataInputStream stream) throws Exception {
		final T target = this.factory.get();
		for (int i = 0; i < this.readers.length; i++) {
			this.readers[i].read(stream, target);
		}
		return target;
	}

	/** Prefixes the UTF-8 BYTE length (not char count), so multibyte strings stay aligned. */
	public static int writeUtf(String value, DataOutputStream stream) throws Exception {
		final byte[] encoded = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
		stream.writeInt(encoded.length);
		stream.write(encoded);
		return NetConstants.INT32_LENGTH + encoded.length;
	}

	public static String readUtf(DataInputStream stream) throws Exception {
		final int length = stream.readInt();
		if (length <= 0) return "";
		final byte[] buffer = new byte[length];
		stream.readFully(buffer);
		return new String(buffer, StandardCharsets.UTF_8);
	}
}
