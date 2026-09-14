package com.openrealm.net.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import java.util.Set;

import org.modelmapper.AbstractConverter;
import org.modelmapper.ModelMapper;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;

import com.openrealm.game.contants.PacketType;
import com.openrealm.net.NetConstants;
import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.client.packet.LoadMapPacket;
import com.openrealm.net.client.packet.UnloadPacket;
import com.openrealm.net.core.converters.*;
import com.openrealm.net.core.nettypes.SerializableLong;
import com.openrealm.net.entity.NetTile;
import lombok.extern.slf4j.Slf4j;
/**
 * Reflective serialization for {@link Packet} / {@link SerializableFieldType} POJOs.
 * @SerializableField(order,...) defines the on-wire field order; collections are
 * prefixed with a 4-byte int32 length. Field order is load-bearing (wire desync if changed).
 */
@Slf4j
@SuppressWarnings({ "unused", "rawtypes", "unchecked" })
public class IOService {
	private static final ModelMapper MAPPER = new ModelMapper();
	private static final Lookup METHOD_LOOKUP = MethodHandles.lookup();
	private static final Map<Class<?>, PacketMappingInformation[]> MAPPING_DATA = new HashMap<>();
	public static final Reflections CLASSPATH_SCANNER = new Reflections("com.openrealm", Scanners.SubTypes);

	static {
		try {
			registerModelConverter(new ShortToEffectTypeConverter());
			registerModelConverter(new EffectTypeToShortConverter());
			registerModelConverter(new ByteToLootTierConverter());
			registerModelConverter(new LootTierToByteConverter());
		}catch(Exception e) {
			log.error("[IOService] Failed to register custom mapper. Reason: {}", e.getMessage());
		}
	}
	
	public static void registerModelConverter(AbstractConverter converter) throws Exception {
		MAPPER.addConverter(converter);
	}

	public static <T> T readPacket(Class<? extends Packet> clazz, byte[] data) throws Exception {
		final ByteArrayInputStream bis = new ByteArrayInputStream(data);
		final DataInputStream dis = new DataInputStream(bis);
		final byte packetIdRead = removeHeader(dis);
		final Packet read = ((Packet)readStream(clazz, dis));
		read.setId(packetIdRead);
		return (T) read;
	}

	public static <T> T readPacket(Class<? extends Packet> clazz, DataInputStream stream) throws Exception {
		final byte packetIdRead = removeHeader(stream);
		return readStream(clazz, stream);
	}

	public static byte[] writePacket(Packet packet, DataOutputStream stream) throws Exception {
		final ByteArrayOutputStream payloadStream = new ByteArrayOutputStream();
		final DataOutputStream payloadOut = new DataOutputStream(payloadStream);
		writeStream(packet, payloadOut);
		final byte[] payload = payloadStream.toByteArray();

		final Byte packetId = PacketType.getPacketId(packet.getClass());
		if (packetId == null) {
			log.error("[IOService] NO PACKET MAPPING FOR PACKET {}", packet);
			return new byte[0];
		}

		// Frame: header (1-byte id + 4-byte length) + payload
		final int frameSize = NetConstants.PACKET_HEADER_SIZE + payload.length;
		final ByteArrayOutputStream frameStream = new ByteArrayOutputStream(frameSize);
		final DataOutputStream frameOut = new DataOutputStream(frameStream);
		frameOut.writeByte(packetId);
		frameOut.writeInt(payload.length + NetConstants.PACKET_HEADER_SIZE);
		frameOut.write(payload);
		final byte[] frame = frameStream.toByteArray();

		stream.write(frame);
		return frame;
	}

	public static int writeStream(Object model, DataOutputStream stream0) throws Exception {
		final PacketMappingInformation[] mappingInfo = MAPPING_DATA.get(model.getClass());
		if (log.isDebugEnabled())
			log.info("[IOService::WRITE] class {} begin. data = {}", model.getClass(), model);
		if (mappingInfo == null) {
			log.error("[IOService::WRITE] **CRITICAL** No mapping for class {}", model.getClass());
			return 0;
		}
		int bytesWritten = 0;
		for (int idx = 0; idx < mappingInfo.length; idx++) {
			final PacketMappingInformation info = mappingInfo[idx];
			final SerializableFieldType serializer = info.getSerializer();
			if (info.isCollection()) {
				final Object[] collection = (Object[]) info.getPropertyHandle().get(model);
				final int collectionLength = collection != null ? collection.length : 0;
				stream0.writeInt(collectionLength);
				bytesWritten += NetConstants.INT32_LENGTH;
				for (int i = 0; i < collectionLength; i++) {
					bytesWritten += serializer.write(collection[i], stream0);
				}
			} else {
				final Object obj = info.getPropertyHandle().get(model);
				bytesWritten += serializer.write(obj, stream0);
			}
		}
		return bytesWritten;
	}

	public static <T> T mapModel(Object model, Class<T> target) {
		return MAPPER.map(model, target);
	}

	public static <T> T readStream(Class<?> clazz, DataInputStream stream, Object result) throws Exception {
		final PacketMappingInformation[] mappingInfo = MAPPING_DATA.get(clazz);
		if(mappingInfo==null) {
			log.error("[IOService::READ] **CRITICAL** No mapping for class {}", clazz);
			throw new Exception("No mapping for class "+clazz.getSimpleName());
		}
		if (log.isDebugEnabled())
			log.info("[IOService::READ] class {} begin. CurrentRessults = {}", clazz, result);
		if (result == null) {
			final Object packet = clazz.getDeclaredConstructor().newInstance();
			result = packet;
		}

		for (int idx = 0; idx < mappingInfo.length; idx++) {
			final PacketMappingInformation info = mappingInfo[idx];
			final SerializableFieldType<?> serializer = info.getSerializer();
			if (info.isCollection()) {
				final int collectionLength = stream.readInt();
				final Object[] collection = (Object[]) Array
						.newInstance(info.getPropertyHandle().varType().getComponentType(), collectionLength);
				for (int i = 0; i < collectionLength; i++) {
					collection[i] = serializer.read(stream);
				}
				info.getPropertyHandle().set(result, collection);
			} else {
				info.getPropertyHandle().set(result, serializer.read(stream));
			}
		}
		return (T) result;
	}

	public static <T> T readStream(Class<?> clazz, DataInputStream stream) throws Exception {
		return readStream(clazz, stream, null);
	}

	public static <T> T readStream(Class<?> clazz, byte[] stream) throws Exception {
		final ByteArrayInputStream bis = new ByteArrayInputStream(stream);
		final DataInputStream dis = new DataInputStream(bis);
		return readStream(clazz, dis, null);
	}

	public static void addHeader(Packet packet, int dataSize, DataOutputStream stream) throws Exception {
		final Byte packetId = PacketType.getPacketId(packet.getClass());
		if(packetId==null) {
			log.error("[IOService] NO PACKET MAPPING FOR PACKET {}", packet);
			return;
		}
		stream.writeByte(packetId);
		stream.writeInt(dataSize + NetConstants.PACKET_HEADER_SIZE);
	}

	public static byte removeHeader(DataInputStream stream) throws Exception {
		final byte packetId = stream.readByte();
		final int len = stream.readInt();
		return packetId;
	}

	public static long[] convertLongArray(Long[] in) {
		final long[] intArr = new long[in.length];
		for (int i = 0; i < in.length; i++) {
			intArr[i] = in[i];
		}
		return intArr;
	}

	public static int[] convertIntArray(Integer[] in) {
		final int[] intArr = new int[in.length];
		for (int i = 0; i < in.length; i++) {
			intArr[i] = in[i];
		}
		return intArr;
	}

	public static short[] convertShortArray(Short[] in) {
		final short[] shortArr = new short[in.length];
		for (int i = 0; i < in.length; i++) {
			shortArr[i] = in[i];
		}
		return shortArr;
	}

	public static Long[] convertLongArray(long[] in) {
		final Long[] intArr = new Long[in.length];
		for (int i = 0; i < in.length; i++) {
			intArr[i] = in[i];
		}
		return intArr;
	}

	public static Integer[] convertIntArray(int[] in) {
		final Integer[] intArr = new Integer[in.length];
		for (int i = 0; i < in.length; i++) {
			intArr[i] = in[i];
		}
		return intArr;
	}

	public static Short[] convertShortArray(short[] in) {
		final Short[] shortArr = new Short[in.length];
		for (int i = 0; i < in.length; i++) {
			shortArr[i] = in[i];
		}
		return shortArr;
	}

	// Build the reflective field-order map for every @Streamable Packet/field type.
	public static void mapSerializableData() throws Exception {
		log.info("[IOService::INIT] Loading classes to map packet data");
		final Set<Class<? extends Packet>> packetsToMap = CLASSPATH_SCANNER.getSubTypesOf(Packet.class);
		final Set<Class<? extends SerializableFieldType>> netEntitiesToMap = CLASSPATH_SCANNER.getSubTypesOf(SerializableFieldType.class);
		final Set<Class> allClasses = new HashSet<>();
		allClasses.addAll(packetsToMap);
		allClasses.addAll(netEntitiesToMap);

		for (Class<?> clazz : allClasses) {
			if (!isStreamableClass(clazz))
				continue;
			final List<PacketMappingInformation> mappingForClass = new LinkedList<>();
			final Field[] fieldsToWrite = clazz.getDeclaredFields();
			for (Field objField : fieldsToWrite) {
				objField.setAccessible(true);
				final Annotation[] annots = objField.getAnnotations();
				for (Annotation annot : annots) {
					if (annot instanceof SerializableField) {
						final SerializableField serdesAnnotation = (SerializableField) annot;
						final int order = serdesAnnotation.order();
						SerializableFieldType<?> serializer = null;
						try {
							final Lookup tempLookup = MethodHandles.privateLookupIn(clazz, METHOD_LOOKUP);
							final Class<? extends SerializableFieldType<?>> serializerType = serdesAnnotation.type();
							final boolean isCollection = serdesAnnotation.isCollection();

							serializer = serializerType.getDeclaredConstructor().newInstance();
							final VarHandle fieldHandle = tempLookup.findVarHandle(clazz, objField.getName(),
									objField.getType());

							final PacketMappingInformation mappingInfo = PacketMappingInformation.builder()
									.propertyHandle(fieldHandle).order(order).serializer(serializer)
									.isCollection(isCollection).build();
							mappingForClass.add(mappingInfo);
						} catch (Exception e) {
							log.error("[IOService::INIT] **CRITICAL** Failed parsing serializable types in packets. Reason: {}", e);
						}
					}
				}
			}
			
			if (mappingForClass.size() > 0) {
				// Sort by @SerializableField order: defines the on-wire sequence.
				Collections.sort(mappingForClass, new Comparator<PacketMappingInformation>() {
					@Override
					public int compare(PacketMappingInformation info0, PacketMappingInformation info1) {
						return info0.getOrder() - info1.getOrder();
					}
				});
				MAPPING_DATA.put(clazz, mappingForClass.toArray(new PacketMappingInformation[0]));
			}
		}
		log.info("[IOService::INIT] Mapping completed");
	}

	public static boolean isStreamableClass(Class<?> clazz) {
		if(clazz==null) return false;
		boolean result = false;
		for (Annotation annot : clazz.getDeclaredAnnotations()) {
			if (annot instanceof Streamable) {
				result = true;
				break;
			}
		}
		return result;
	}
}
