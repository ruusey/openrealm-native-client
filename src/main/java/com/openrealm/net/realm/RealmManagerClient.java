package com.openrealm.net.realm;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import org.reflections.Reflections;
import org.reflections.scanners.Scanners;

import com.openrealm.game.contants.PacketType;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.LootContainer;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.state.PlayState;
import com.openrealm.net.Packet;
import com.openrealm.net.client.ClientGameLogic;
import com.openrealm.net.client.SocketClient;
import com.openrealm.net.client.packet.AcceptTradeRequestPacket;
import com.openrealm.net.client.packet.LoadMapPacket;
import com.openrealm.net.client.packet.LoadPacket;
import com.openrealm.net.client.packet.RealmPurificationPacket;
import com.openrealm.net.client.packet.ObjectMovePacket;
import com.openrealm.net.client.packet.PlayerDeathPacket;
import com.openrealm.net.client.packet.RequestTradePacket;
import com.openrealm.net.client.packet.TextEffectPacket;
import com.openrealm.net.client.packet.CompactMovePacket;
import com.openrealm.net.client.packet.PlayerStatePacket;
import com.openrealm.net.client.packet.UnloadPacket;
import com.openrealm.net.client.packet.UpdatePacket;
import com.openrealm.net.server.ServerGameLogic;
import com.openrealm.net.server.ServerTradeManager;
import com.openrealm.net.server.packet.CommandPacket;
import com.openrealm.net.server.packet.HeartbeatPacket;
import com.openrealm.net.server.packet.MoveItemPacket;
import com.openrealm.net.server.packet.TextPacket;
import com.openrealm.util.PacketHandlerClient;
import com.openrealm.util.PacketHandlerServer;
import com.openrealm.util.TimedWorkerThread;
import com.openrealm.util.WorkerThread;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
@EqualsAndHashCode(callSuper = false)
public class RealmManagerClient implements Runnable {
	private Reflections classPathScanner = new Reflections("com.openrealm", Scanners.SubTypes, Scanners.MethodsAnnotated);
	private MethodHandles.Lookup publicLookup = MethodHandles.publicLookup();
	private final Map<Byte, List<MethodHandle>> userPacketCallbacksClient = new HashMap<>();

    private SocketClient client;
    private PlayState state;
    private Realm realm;
    private boolean shutdown = false;
    private final Map<Class<? extends Packet>, BiConsumer<RealmManagerClient, Packet>> packetCallbacksClient = new HashMap<>();
    private long currentPlayerId;
    private volatile boolean awaitingRealmTransition = false;
    private TimedWorkerThread workerThread;
    // Short ID -> Long ID mapping for compact movement packets.
    // Populated from LoadPacket (NetPlayer.shortId / NetEnemy.shortId).
    private final Map<Short, Long> shortIdToLongId = new ConcurrentHashMap<>();

    public RealmManagerClient(PlayState state, Realm realm) {
        this.registerPacketCallbacks();
        this.registerPacketCallbacksReflection();
        this.realm = realm;
        this.client = new SocketClient(SocketClient.SERVER_ADDR, 2222);
        this.state = state;
        WorkerThread.submitAndForkRun(this.client);
    }

    @Override
    public void run() {
        RealmManagerClient.log.info("[CLIENT] Starting OpenRealm Client");

        // Network-side tick: drain inbound packets only. DO NOT call
        // state.update() here — the LibGDX render loop
        // (OpenRealmGame.render -> gsm.update -> PlayState.update) is the
        // single source of simulation ticks. Calling state.update from
        // both paths makes Enemy.extrapolate() run at FPS+64Hz, each using
        // the SAME stale Gdx frame delta, so velocity-based extrapolation
        // advances ~2x as fast as the server's actual simulation.
        // Symptom: enemies lurch forward, then snap back when the next
        // ObjectMovePacket lands at the true server position.
        final Runnable tick = this::processClientPackets;

        this.workerThread = new TimedWorkerThread(tick, 64);
        WorkerThread.submitAndForkRun(this.workerThread);

        RealmManagerClient.log.info("[CLIENT] RealmManagerClient exiting run().");
    }

    public void processClientPackets() {
        while (!this.getClient().getInboundPacketQueue().isEmpty()) {
            Packet toProcess = this.getClient().getInboundPacketQueue().remove();
            try {
				Packet created = toProcess;
				//log.info("[CLIENT] Processing client packet {} ", created);
                created.setSrcIp(toProcess.getSrcIp());
                created.setId(toProcess.getId());
                BiConsumer<RealmManagerClient, Packet> consumer = this.packetCallbacksClient.get(created.getClass());
                if(consumer!=null) {
                	consumer.accept(this, created);
                }
                final List<MethodHandle> packetHandles = this.userPacketCallbacksClient.get(created.getId());
				long start = System.nanoTime();
				if (packetHandles != null) {

					for (MethodHandle handler : packetHandles) {
						try {
							handler.invokeExact(this, created);
						} catch (Throwable e) {
							log.error("Failed to invoke packet callback. Reason: {}", e);
						}
					}
					log.debug("[CLIENT] Invoked {} packet callbacks for PacketType {} using reflection in {} nanos",
							packetHandles.size(), PacketType.valueOf(created.getId()),
							(System.nanoTime() - start));
				}
            } catch (Exception e) {
                RealmManagerClient.log.error("[CLIENT] Failed to process client packets {}", e);
            }
        }
    }

    private void registerPacketCallbacks() {
        this.registerPacketCallback(UpdatePacket.class, ClientGameLogic::handleUpdateClient);
        this.registerPacketCallback(ObjectMovePacket.class, ClientGameLogic::handleObjectMoveClient);
        this.registerPacketCallback(TextPacket.class, ClientGameLogic::handleTextClient);
        this.registerPacketCallback(CommandPacket.class, ClientGameLogic::handleCommandClient);
        this.registerPacketCallback(LoadPacket.class, ClientGameLogic::handleLoadClient);
        this.registerPacketCallback(LoadMapPacket.class, ClientGameLogic::handleLoadMapClient);
        this.registerPacketCallback(UnloadPacket.class, ClientGameLogic::handleUnloadClient);
        this.registerPacketCallback(TextEffectPacket.class, ClientGameLogic::handleTextEffectClient);
        this.registerPacketCallback(PlayerDeathPacket.class, ClientGameLogic::handlePlayerDeathClient);
        this.registerPacketCallback(PlayerStatePacket.class, ClientGameLogic::handlePlayerStateClient);
        this.registerPacketCallback(CompactMovePacket.class, ClientGameLogic::handleCompactMoveClient);
        // Server echoes HeartbeatPacket with the original client timestamp
        // so we can compute the true RTT and feed the dev-stats overlay.
        this.registerPacketCallback(HeartbeatPacket.class, (cli, pkt) -> {
            try {
                final HeartbeatPacket hb = (HeartbeatPacket) pkt;
                com.openrealm.game.ui.PerfMetrics.get().recordHeartbeatRtt(hb.getTimestamp());
            } catch (Exception ignored) { /* metrics never crash gameplay */ }
        });
        this.registerPacketCallback(RealmPurificationPacket.class, (cli, pkt) -> {
            try {
                final RealmPurificationPacket p = (RealmPurificationPacket) pkt;
                final Realm realm = cli.getRealm();
                if (realm != null) {
                    realm.setPurificationProgress(p.getProgress());
                    realm.setPurificationGoal(p.getGoal());
                    realm.setPurificationDifficulty(p.getDifficulty());
                }
            } catch (Exception ignored) { /* never crash gameplay */ }
        });
//        this.registerPacketCallback(RequestTradePacket.class, ClientGameLogic::handleTradeRequestClient);
//        this.registerPacketCallback(AcceptTradeRequestPacket.class, ClientGameLogic::handleAcceptTrade);

    }
    
    private void registerPacketCallbacksReflection() {
		log.info("[CLIENT] Registering packet handlers using reflection");
		final MethodType mt = MethodType.methodType(void.class, RealmManagerClient.class, Packet.class);

		final Set<Method> subclasses = this.classPathScanner.getMethodsAnnotatedWith(PacketHandlerClient.class);
		for (final Method method : subclasses) {
			try {
				final PacketHandlerClient packetToHandle = method.getDeclaredAnnotation(PacketHandlerClient.class);
				MethodHandle handlerMethod = null;
				
					handlerMethod = this.publicLookup.findStatic(ClientGameLogic.class, method.getName(), mt);
				

				if (handlerMethod != null) {
					final Entry<Byte, Class<? extends Packet>> targetPacketType = PacketType.valueOf(packetToHandle.value());
					List<MethodHandle> existing = this.userPacketCallbacksClient.get(targetPacketType.getKey());
					if (existing == null) {
						existing = new ArrayList<>();
					}
					existing.add(handlerMethod);
					log.info("[CLIENT] Added new packet handler for packet {}. Handler method: {}", targetPacketType.getKey(),
							handlerMethod.toString());
					this.userPacketCallbacksClient.put(targetPacketType.getKey(), existing);
				}
			} catch (Exception e) {
				log.error("[CLIENT] Failed to get MethodHandle to method {}. Reason: {}", method.getName(), e);
			}
		}
	}

    private void registerPacketCallback(Class<? extends Packet> packetId, BiConsumer<RealmManagerClient, Packet> callback) {
        this.packetCallbacksClient.put(packetId, callback);
    }

    public void update(double time) {
        this.state.update(time);
    }

    public void startHeartbeatThread() {
        Runnable sendHeartbeat = () -> {
            while (!this.shutdown) {
                try {
                    long currentTime = Instant.now().toEpochMilli();
                    HeartbeatPacket pack = HeartbeatPacket.from(currentTime);
                    // Record before send so PerfMetrics can match the
                    // returned echo to the original send timestamp.
                    com.openrealm.game.ui.PerfMetrics.get().recordHeartbeatSend(currentTime);
                    this.client.sendRemote(pack);
                    Thread.sleep(1000);
                } catch (Exception e) {
                    RealmManagerClient.log.error("Failed to send Heartbeat packet. Reason: {}", e);
                }
            }
        };
        WorkerThread.submitAndForkRun(sendHeartbeat);
    }

    public boolean isDisconnected() {
        return this.shutdown || (this.client != null && this.client.isDisconnected());
    }

    public void shutdownClient() {
        this.shutdown = true;
        if (this.workerThread != null) {
            this.workerThread.setShutdown(true);
        }
        if (this.client != null) {
            this.client.close();
        }
        RealmManagerClient.log.info("[CLIENT] Client shutdown complete.");
    }

    public void moveItem(int toSlotIndex, int fromSlotIndex, boolean drop, boolean consume) {
        try {
            MoveItemPacket moveItem = MoveItemPacket.from((byte) toSlotIndex,
                    (byte) fromSlotIndex, drop, consume);
            this.getClient().sendRemote(moveItem);
        } catch (Exception e) {
            RealmManagerClient.log.error("[CLIENT] Failed to send MoveItem packet. Reason: {}", e);
        }
    }

    public Player getClosestPlayer(final Vector2f pos, final float limit) {
        float best = Float.MAX_VALUE;
        Player bestPlayer = null;
        final Realm targetRealm = this.realm;
        for (final Player player : targetRealm.getPlayers().values()) {
            final float dist = player.getPos().distanceTo(pos);
            if ((dist < best) && (dist <= limit)) {
                best = dist;
                bestPlayer = player;
            }
        }
        return bestPlayer;
    }

    /**
     * Remove a remote peer client-side — used both when it leaves render range
     * and on the server's UnloadPacket. Routes through removePlayer so the
     * spatialGrid + shortIdAllocator entries are released, then drops the
     * short-id reverse mapping. No-op for the local player or an unknown id.
     */
    public boolean derenderRemotePlayer(final long playerId) {
        if (playerId == this.currentPlayerId) {
            return false;
        }
        final Player existing = this.realm.getPlayers().get(playerId);
        if (existing == null) {
            return false;
        }
        final short shortId = this.realm.getShortIdAllocator().toShort(playerId);
        this.realm.removePlayer(existing);
        if (shortId != 0) {
            this.shortIdToLongId.remove(shortId);
        }
        return true;
    }

    public LootContainer getClosestLootContainer(final Vector2f pos, final float limit) {
        float best = Float.MAX_VALUE;
        LootContainer bestLoot = null;
        final Realm targetRealm = this.realm;
        for (final LootContainer lootContainer : targetRealm.getLoot().values()) {
            float dist = lootContainer.getPos().distanceTo(pos);
            if ((dist < best) && (dist <= limit)) {
                best = dist;
                bestLoot = lootContainer;
            }
        }
        return bestLoot;
    }
}
