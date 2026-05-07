package com.openrealm.net.client;

import com.openrealm.account.service.OpenRealmClientDataService;
import com.openrealm.game.GameLauncher;
import com.openrealm.game.contants.CharacterClass;
import com.openrealm.game.contants.EntityType;
import com.openrealm.game.contants.GlobalConstants;
import com.openrealm.game.contants.TextEffect;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.data.GameSpriteManager;
import com.openrealm.game.model.MapModel;
import com.openrealm.game.contants.ProjectileFlag;
import com.openrealm.game.entity.Bullet;
import com.openrealm.game.entity.Enemy;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.Portal;
import com.openrealm.game.entity.item.LootContainer;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.state.GameStateManager;
import com.openrealm.game.ui.EffectText;
import com.openrealm.net.Packet;
import com.openrealm.net.client.packet.AcceptTradeRequestPacket;
import com.openrealm.game.ui.ActiveVisualEffect;
import com.openrealm.net.client.packet.CreateEffectPacket;
import com.openrealm.net.client.packet.LoadMapPacket;
import com.openrealm.net.client.packet.LoadPacket;
import com.openrealm.net.client.packet.CompactMovePacket;
import com.openrealm.net.client.packet.ObjectMovePacket;
import com.openrealm.net.entity.NetCompactMovement;
import com.openrealm.net.client.packet.PlayerDeathPacket;
import com.openrealm.net.client.packet.RequestTradePacket;
import com.openrealm.net.client.packet.TextEffectPacket;
import com.openrealm.net.client.packet.UnloadPacket;
import com.openrealm.net.client.packet.UpdatePacket;
import com.openrealm.net.client.packet.UpdatePlayerTradeSelectionPacket;
import com.openrealm.net.client.packet.UpdateTradePacket;
import com.openrealm.net.client.packet.PlayerPosAckPacket;
import com.openrealm.net.client.packet.GlobalPlayerPositionPacket;
import com.openrealm.net.entity.NetPlayerPosition;
import com.openrealm.net.core.IOService;
import com.openrealm.net.entity.NetBullet;
import com.openrealm.net.entity.NetEnemy;
import com.openrealm.net.entity.NetInventorySelection;
import com.openrealm.net.entity.NetLootContainer;
import com.openrealm.net.entity.NetPlayer;
import com.openrealm.net.entity.NetPortal;
import com.openrealm.net.entity.NetTradeSelection;
import com.openrealm.net.entity.NetObjectMovement;
import com.openrealm.net.messaging.CommandType;
import com.openrealm.net.messaging.LoginResponseMessage;
import com.openrealm.net.messaging.PlayerAccountMessage;
import com.openrealm.net.messaging.ServerErrorMessage;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerClient;
import com.openrealm.net.server.packet.CommandPacket;
import com.openrealm.net.server.packet.DeathAckPacket;
import com.openrealm.net.server.packet.LoginAckPacket;
import com.openrealm.net.server.packet.TextPacket;
import com.openrealm.util.PacketHandlerClient;

import lombok.extern.slf4j.Slf4j;
import com.openrealm.game.ui.FameStoreWindow;
import com.openrealm.game.ui.ForgeWindow;
import com.openrealm.net.client.packet.OpenFameStorePacket;
import com.openrealm.net.client.packet.OpenForgePacket;
import com.openrealm.net.server.ServerFameStoreHelper;
import com.openrealm.net.client.packet.PlayerStatePacket;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class ClientGameLogic {
	public static OpenRealmClientDataService DATA_SERVICE = null;
	public static boolean GAME_OVER = false;
	
	
	@PacketHandlerClient(RequestTradePacket.class)
	public static void handleTradeRequestClient(RealmManagerClient cli, Packet packet) {
		final RequestTradePacket tradeRequest = (RequestTradePacket) packet;
		cli.getState().getPui().getPlayerChat().addChatMessage(TextPacket.create(tradeRequest.getRequestingPlayerName(),
				cli.getState().getPlayer().getName(),
				tradeRequest.getRequestingPlayerName() + " has proposed a trade, type /accept to initiate the trade"));

	}
	
	@PacketHandlerClient(AcceptTradeRequestPacket.class)
	public static void handleAcceptTrade(RealmManagerClient cli, Packet packet) {
		final AcceptTradeRequestPacket tradeRequest = (AcceptTradeRequestPacket) packet;
		log.info("[CLIENT] Recieved trade packet. Accepted = {}", tradeRequest.isAccepted());

		if (tradeRequest.isAccepted()) {
			log.info("[CLIENT] Trade accepted between {} and {}", tradeRequest.getPlayer0().getName(), tradeRequest.getPlayer1().getName());
			cli.getState().getPui().setTrading(true);
			cli.getState().getPui().setTradePartnerName(tradeRequest.getPlayer1().getName());
		} else {
			log.info("[CLIENT] Trade closed");
			cli.getState().getPui().setTrading(false);
			cli.getState().getPui().setCurrentTradeSelection(null);
			cli.getState().getPui().setTradePartnerName(null);
			cli.getState().getPui().clearTradeSelections();
		}
	}

	@PacketHandlerClient(UpdatePlayerTradeSelectionPacket.class)
	public static void handleUpdateTradeSelection(RealmManagerClient mgr, Packet packet) {
		final UpdatePlayerTradeSelectionPacket updateTrade = (UpdatePlayerTradeSelectionPacket) packet;
		final NetInventorySelection selection = updateTrade.getSelection();

		NetTradeSelection currSelection = mgr.getState().getPui().getCurrentTradeSelection();
		if (currSelection != null) {
			currSelection.applyUpdate(selection);
		}
	}

	@PacketHandlerClient(UpdateTradePacket.class)
	public static void handleUpdateTrade(RealmManagerClient mgr, Packet packet) {
		final UpdateTradePacket updateTrade = (UpdateTradePacket) packet;
		final NetTradeSelection selection = updateTrade.getSelections();

		NetTradeSelection currSelection = mgr.getState().getPui().getCurrentTradeSelection();
		if(currSelection==null) {
			currSelection = selection;
			mgr.getState().getPui().setCurrentTradeSelection(selection);
		}else {
			currSelection.applyUpdate(selection);
		}

	}
	
	@PacketHandlerClient(CreateEffectPacket.class)
	public static void handleCreateEffectClient(RealmManagerClient cli, Packet packet) {
		final CreateEffectPacket effectPacket = (CreateEffectPacket) packet;
		try {
			if (cli.getState() == null) return;
			cli.getState().getActiveEffects().add(ActiveVisualEffect.from(effectPacket));
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to handle CreateEffect Packet. Reason: {}", e);
		}
	}

	public static void handlePlayerDeathClient(RealmManagerClient cli, Packet packet) {
		@SuppressWarnings("unused")
		final PlayerDeathPacket playerDeath = (PlayerDeathPacket) packet;
		if(GAME_OVER) {
			log.info("Already recieved death packet. Ignoring {}", playerDeath);
			return;
		}
		GAME_OVER=true;
		
		try {
			cli.getClient().sendRemote(new DeathAckPacket(cli.getState().getPlayer().getId()));
			cli.getState().getRealmManager().shutdownClient();
			cli.getState().gsm.add(GameStateManager.GAMEOVER);
			cli.getState().gsm.pop(GameStateManager.PLAY);
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to handle PlayerDeath Packet. Reason: {}", e);
		}
	}

	public static void handleTextEffectClient(RealmManagerClient cli, Packet packet) {
		final TextEffectPacket textEffect = (TextEffectPacket) packet;
		try {
			final Realm clientRealm = cli.getState().getRealmManager().getRealm();
			Vector2f targetPos = null;
			try {
				switch (EntityType.valueOf(textEffect.getEntityType())) {
				case BULLET:
					final Bullet b = clientRealm.getBullet(textEffect.getTargetEntityId());
					if (b == null) {
						ClientGameLogic.log.warn("[CLIENT] Bullet with id {} was not found for targeted TextEffect",
								textEffect.getTargetEntityId());
						return;
					}
					targetPos = b.getPos();

					break;
				case ENEMY:
					final Enemy e = clientRealm.getEnemy(textEffect.getTargetEntityId());
					if (e == null) {
						ClientGameLogic.log.warn("[CLIENT] Enemy with id {} was not found for targeted TextEffect",
								textEffect.getTargetEntityId());
						return;
					}
					targetPos = e.getPos();
					break;
				case PLAYER:
					final Player p = clientRealm.getPlayer(textEffect.getTargetEntityId());
					if (p == null) {
						ClientGameLogic.log.warn("[CLIENT] Player with id {} was not found for targeted TextEffect",
								textEffect.getTargetEntityId());
						return;
					}
					targetPos = p.getPos();
					break;
				default:
					break;
				}

				final EffectText hitText = EffectText.builder().damage(textEffect.getText())
						.effect(TextEffect.from(textEffect.getTextEffectId())).sourcePos(targetPos).build();
				cli.getState().getDamageText().add(hitText);
			} catch (Exception e) {
				ClientGameLogic.log.error("[CLIENT] Failed to create client TextEffect. Reason: {}", e);
			}

		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to handle TextEffect Packet. Reason: {}", e);
		}
	}

	public static void handleLoadMapClient(RealmManagerClient cli, Packet packet) {
		final LoadMapPacket loadPacket = (LoadMapPacket) packet;
		try {
			// Skip if client hasn't finished login setup yet (UI not ready)
			if (cli.getState() == null || cli.getState().getPui() == null) {
				log.debug("[CLIENT] LoadMap received before login complete, skipping");
				return;
			}
			// LoadMap packets fire on EVERY tile-stream chunk while moving
			// around — they're not a transition signal. Only show the
			// "OPENREALM <zone>" overlay when the realm or map id actually
			// changes from what we previously recorded; otherwise treat the
			// packet as a normal tile merge and skip the splash.
			final long prevRealmId = cli.getRealm().getRealmId();
			final long prevMapId   = cli.getRealm().getMapId();
			final boolean realmChanged = (prevRealmId != loadPacket.getRealmId())
					|| (prevMapId != loadPacket.getMapId());

			cli.getState().getPui().getMinimap().initializeMap((int) loadPacket.getMapId());
			cli.getRealm().setRealmId(loadPacket.getRealmId());
			cli.getRealm().setMapId(loadPacket.getMapId());
			cli.getRealm().getTileManager().mergeMap(loadPacket);

			if (realmChanged) {
				String zoneName = "Map " + loadPacket.getMapId();
				float diff = 0f;
				try { diff = cli.getRealm().getDifficulty(); } catch (Exception ignored) {}
				cli.getState().getPui().getRealmTransition().trigger(zoneName, diff);
				// Clear chat on realm change so the log doesn't carry over
				// across maps / instances. Mirrors the web client.
				try { cli.getState().getPui().getPlayerChat().clearChat(); } catch (Exception ignored) {}

				// Snap local player to the new map's spawn so we don't render
				// at the previous realm's coordinates inside the new tile
				// mesh. The server has already done setPos for the destination
				// (mapModel.getCenter for vault, getRandomSpawnPoint for
				// nexus, etc.) — but ObjectMovePacket / PlayerPosAck for the
				// local player don't fire until the next input. Without this
				// the sprite renders inside walls / outside playable area
				// until the player presses a movement key. The server's
				// authoritative pos arrives next tick and corrects any
				// drift; getCenter is a safe visual default in the meantime.
				try {
					final Player local = cli.getRealm().getPlayer(cli.getCurrentPlayerId());
					final MapModel mapModel = GameDataManager.MAPS.get((int) loadPacket.getMapId());
					if (local != null && local.getPos() != null && mapModel != null) {
						final Vector2f spawn = mapModel.getCenter();
						local.getPos().x = spawn.x;
						local.getPos().y = spawn.y;
						cli.getState().resetInterpAnchor(spawn.x, spawn.y);
					}
				} catch (Exception ignored) { /* best effort — server pos will follow */ }
			}
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to handle LoadMap Packet. Reason: {}", e);
		}
	}

	/**
	 * Server's authoritative position for the LOCAL player. The server sends
	 * this every tick when moving (and periodically when idle) so we can
	 * pull predicted client position back if it has drifted ahead. Without
	 * this handler the predicted position runs forever past where the
	 * server thinks we are — at higher render frame rates the visual sprite
	 * outruns the tile stream and bullets spawn from the server's last
	 * known position (visibly behind the player).
	 *
	 * Strategy: pure snap. Cheap, robust, can't drift. Web client does a
	 * smoother lerp + input-replay reconciliation but for a desync this
	 * severe a snap is the right floor — no jitter once frame rate matches
	 * the server's tick rate, and even at 144 FPS the snaps are a few px
	 * each so they aren't visually disruptive.
	 */
	@PacketHandlerClient(PlayerPosAckPacket.class)
	public static void handlePlayerPosAckClient(RealmManagerClient cli, Packet packet) {
		try {
			final PlayerPosAckPacket ack = (PlayerPosAckPacket) packet;
			final Player local = cli.getRealm().getPlayer(cli.getCurrentPlayerId());
			if (local == null || local.getPos() == null) return;
			local.getPos().x = ack.getPosX();
			local.getPos().y = ack.getPosY();
			local.setLastProcessedInputSeq(ack.getSeq());
			// Re-anchor the sub-tick interpolation so the next render
			// frame's lerp starts from the SNAPPED position. Without this,
			// a PosAck arriving mid-tick leaves interpFromX pointing at
			// the pre-snap position; the camera lerps from old → new and
			// shows a 1-2 px hop every server tick.
			if (cli.getState() != null) {
				cli.getState().resetInterpAnchor(ack.getPosX(), ack.getPosY());
			}
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed PlayerPosAck handler. Reason: {}", e.getMessage());
		}
	}

	/**
	 * Server's authoritative positions for OTHER players (broadcast every
	 * few ticks). Snap each remote player to the server-reported position
	 * so we don't render them at stale interpolated positions when their
	 * own input/server simulation has them somewhere else.
	 */
	@PacketHandlerClient(GlobalPlayerPositionPacket.class)
	public static void handleGlobalPlayerPositionClient(RealmManagerClient cli, Packet packet) {
		try {
			final GlobalPlayerPositionPacket gp = (GlobalPlayerPositionPacket) packet;
			if (gp.getPlayers() == null) return;
			final long localId = cli.getCurrentPlayerId();
			for (NetPlayerPosition p : gp.getPlayers()) {
				if (p == null) continue;
				final Player target = cli.getRealm().getPlayer(p.getPlayerId());
				if (target == null || target.getPos() == null) continue;
				target.getPos().x = p.getX();
				target.getPos().y = p.getY();
			}
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed GlobalPlayerPosition handler. Reason: {}", e.getMessage());
		}
	}

	@PacketHandlerClient(OpenForgePacket.class)
	public static void handleOpenForgeClient(RealmManagerClient cli, Packet packet) {
		try {
			if (cli.getState() == null || cli.getState().getPui() == null) return;
			final ForgeWindow forge = cli.getState().getPui().getForgeWindow();
			forge.setRealmManager(cli);
			// PlayState ref so the forge UI can resolve a forge-slot
			// inventory index back to the actual GameItem (used to draw
			// the icon inside the slot square + the target weapon as
			// the paint canvas background).
			forge.setPlayState(cli.getState());
			forge.show();
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to handle OpenForge Packet. Reason: {}", e);
		}
	}

	@PacketHandlerClient(OpenFameStorePacket.class)
	public static void handleOpenFameStoreClient(RealmManagerClient cli, Packet packet) {
		try {
			if (cli.getState() == null || cli.getState().getPui() == null) return;
			final OpenFameStorePacket open = (OpenFameStorePacket) packet;
			final FameStoreWindow store = cli.getState().getPui().getFameStoreWindow();
			store.setRealmManager(cli);
			store.setAccountFame(open.getAccountFame());
			// The native client doesn't yet receive the catalog over the wire;
			// for now show a small hard-coded list mirroring the web client's
			// initial fame-store stock so the UI is interactive end-to-end.
			// When the server emits a catalog payload, replace this with the
			// served list.
			List<FameStoreWindow.Entry> entries = new ArrayList<>();
			// Catalog mirrors ServerFameStoreHelper's accepted itemId
			// ranges and per-tier costs:
			//   821-828  → 8 dyes      (500 fame each)
			//   808-815  → 8 crystals  (1000 fame each)
			//   830-836  → 7 gems      (5000 fame each — endgame power tier)
			final long DYE_COST     = ServerFameStoreHelper.DYE_FAME_COST;
			final long CRYSTAL_COST = ServerFameStoreHelper.CRYSTAL_FAME_COST;
			final long GEM_COST     = ServerFameStoreHelper.GEM_FAME_COST;
			entries.add(new FameStoreWindow.Entry(821, "Green Dye",  DYE_COST));
			entries.add(new FameStoreWindow.Entry(822, "Yellow Dye", DYE_COST));
			entries.add(new FameStoreWindow.Entry(823, "Red Dye",    DYE_COST));
			entries.add(new FameStoreWindow.Entry(824, "Blue Dye",   DYE_COST));
			entries.add(new FameStoreWindow.Entry(825, "Purple Dye", DYE_COST));
			entries.add(new FameStoreWindow.Entry(826, "Orange Dye", DYE_COST));
			entries.add(new FameStoreWindow.Entry(827, "White Dye",  DYE_COST));
			entries.add(new FameStoreWindow.Entry(828, "Black Dye",  DYE_COST));
			entries.add(new FameStoreWindow.Entry(808, "Vit Crystal", CRYSTAL_COST));
			entries.add(new FameStoreWindow.Entry(809, "Wis Crystal", CRYSTAL_COST));
			entries.add(new FameStoreWindow.Entry(810, "HP Crystal",  CRYSTAL_COST));
			entries.add(new FameStoreWindow.Entry(811, "MP Crystal",  CRYSTAL_COST));
			entries.add(new FameStoreWindow.Entry(812, "Att Crystal", CRYSTAL_COST));
			entries.add(new FameStoreWindow.Entry(813, "Def Crystal", CRYSTAL_COST));
			entries.add(new FameStoreWindow.Entry(814, "Spd Crystal", CRYSTAL_COST));
			entries.add(new FameStoreWindow.Entry(815, "Dex Crystal", CRYSTAL_COST));
			entries.add(new FameStoreWindow.Entry(830, "Wisdom Scaling Gem", GEM_COST));
			entries.add(new FameStoreWindow.Entry(831, "Swift Scaling Gem",  GEM_COST));
			entries.add(new FameStoreWindow.Entry(832, "Multishot Gem",      GEM_COST));
			entries.add(new FameStoreWindow.Entry(833, "Crushing Gem",       GEM_COST));
			entries.add(new FameStoreWindow.Entry(834, "Slowing Gem",        GEM_COST));
			entries.add(new FameStoreWindow.Entry(835, "Vampiric Gem",       GEM_COST));
			entries.add(new FameStoreWindow.Entry(836, "Brutal Gem",         GEM_COST));
			store.setEntries(entries);
			store.show();
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to handle OpenFameStore Packet. Reason: {}", e);
		}
	}

	// Angle window for matching server-echoed player bullets back to a
	// locally-predicted bullet. ~5° matches the webclient (game.js ~770).
	private static final float PREDICTED_ANGLE_TOLERANCE = 0.09f;

	private static Bullet findMatchingPredictedBullet(RealmManagerClient cli, Bullet server) {
		final long localId = cli.getCurrentPlayerId();
		if (localId == 0L) return null;
		for (final Bullet pb : cli.getRealm().getBullets().values()) {
			if (!pb.isPredicted()) continue;
			if (pb.getSrcEntityId() != localId) continue;
			if (pb.getProjectileId() != server.getProjectileId()) continue;
			final float diff = Math.abs(pb.getAngle() - server.getAngle());
			if (diff < PREDICTED_ANGLE_TOLERANCE
					|| diff > (float) (Math.PI * 2) - PREDICTED_ANGLE_TOLERANCE) {
				return pb;
			}
		}
		return null;
	}

	public static void handleLoadClient(RealmManagerClient cli, Packet packet) {
		final LoadPacket loadPacket = (LoadPacket) packet;
		try {
			for (final NetPlayer player : loadPacket.getPlayers()) {
				Player p = player.toPlayer();

				if (p.getId() == cli.getCurrentPlayerId()) {
					// LOCAL player is added via doLoginResponse and never via
					// addPlayerIfNotExists, but the LoadPacket is still the
					// only place chatRole arrives — UpdatePacket doesn't
					// carry it. Without copying it here the local player's
					// nameplate renders in the default off-white instead of
					// the role-colored red/blue, even though chatRole is
					// resolved server-side.
					try {
						final Player localExisting = cli.getRealm().getPlayer(p.getId());
						if (localExisting != null) {
							if (p.getChatRole() != null && !p.getChatRole().isEmpty()) {
								localExisting.setChatRole(p.getChatRole());
							}
							// Refresh server-authoritative size so /size and
							// any future server-driven resize takes effect on
							// the local renderer (same handling as webclient
							// game.js handleLoad). Bounds is rebuilt off the
							// new size so collision matches the visual.
							if (p.getSize() > 0 && localExisting.getSize() != p.getSize()) {
								localExisting.setSize(p.getSize());
								if (localExisting.getBounds() != null) {
									localExisting.getBounds().setWidth(p.getSize());
									localExisting.getBounds().setHeight(p.getSize());
								}
							}
							if (p.getDyeId() != localExisting.getDyeId()) {
								localExisting.setDyeId(p.getDyeId());
							}
						}
					} catch (Exception ignored) { /* best-effort */ }
					continue;
				}
				// Defensive: skip remote players whose LoadPacket pos is
				// exactly (0, 0). That's the server's
				// uninitialized-Vector2f sentinel — typically observed when
				// a remote join races our LoadPacket assembly. The next
				// LoadPacket (server re-broadcasts every couple seconds)
				// will deliver the real spawn pos and add them properly.
				// Without this gate the player gets stuck at (0, 0) for
				// the whole session because addPlayerIfNotExists is a
				// no-op once the entry is present.
				if (p.getPos() != null
						&& p.getPos().x == 0f && p.getPos().y == 0f) {
					continue;
				}
				final boolean wasNew = cli.getRealm().getPlayer(p.getId()) == null;
				cli.getRealm().addPlayerIfNotExists(p);
				// Refresh authoritative remote-player fields in place when
				// the entry already exists. addPlayerIfNotExists is a no-op
				// for known IDs, so without this the server-broadcast size /
				// dye / chatRole updates would never reach already-tracked
				// players on the native client (mirrors webclient game.js
				// handleLoad lines 657-662).
				if (!wasNew) {
					try {
						final Player remoteExisting = cli.getRealm().getPlayer(p.getId());
						if (remoteExisting != null) {
							if (p.getSize() > 0 && remoteExisting.getSize() != p.getSize()) {
								remoteExisting.setSize(p.getSize());
								if (remoteExisting.getBounds() != null) {
									remoteExisting.getBounds().setWidth(p.getSize());
									remoteExisting.getBounds().setHeight(p.getSize());
								}
							}
							if (p.getDyeId() != remoteExisting.getDyeId()) {
								remoteExisting.setDyeId(p.getDyeId());
							}
							if (p.getChatRole() != null && !p.getChatRole().isEmpty()) {
								remoteExisting.setChatRole(p.getChatRole());
							}
						}
					} catch (Exception ignored) { /* best-effort */ }
				}
				// Register short ID mapping for compact movement packets
				if (player.getShortId() != 0) {
					cli.getShortIdToLongId().put(player.getShortId(), player.getId());
				}
				// One-shot diagnostic — log the pos used when a remote player
				// is FIRST inserted into the realm. If that pos is already
				// (0, 0), the bug is in the LoadPacket payload (server or
				// wire). If it's correct here but we still see (0, 0) on
				// screen later, the corruption is downstream
				// (applyServerCorrection / movePlayer / clobbered Vector2f).
				if (wasNew) {
					final float lpx = p.getPos() == null ? Float.NaN : p.getPos().x;
					final float lpy = p.getPos() == null ? Float.NaN : p.getPos().y;
					log.info("[LOADPACKET] Added remote player id={} name={} loadPos=({}, {}) classId={} size={}",
							p.getId(), p.getName(), lpx, lpy, p.getClassId(), p.getSize());
				}
				// Existing-player sprite recovery: if the prior add happened
				// before ANIMATIONS data loaded, addPlayerIfNotExists kept
				// the spriteSheet=null entry. Patch it up in place.
				try {
					Player existing = cli.getRealm().getPlayer(p.getId());
					if (existing != null && existing.getSpriteSheet() == null
							&& p.getSpriteSheet() != null) {
						existing.setSpriteSheet(p.getSpriteSheet());
						existing.setClassId(p.getClassId());
					}
				} catch (Exception ignored) { /* best-effort recovery */ }
			}
			for (final NetLootContainer loot : loadPacket.getContainers()) {
				final LootContainer lc = loot.asLootContainer();
				if (lc.getContentsChanged()) {
					LootContainer current = cli.getRealm().getLoot().get(lc.getLootContainerId());
					if (current == null) {
						cli.getRealm().addLootContainerIfNotExists(lc);
						// current = cli.getRealm().getLoot().get(lc.getLootContainerId());
					} else {
						current.setContentsChanged(true);
						current.setItems(lc.getItems());
					}

				} else {
					cli.getRealm().addLootContainerIfNotExists(lc);
				}
			}

			for (final NetBullet bullet : loadPacket.getBullets()) {
				final Bullet b = bullet.asBullet();
				// WHY: Mirrors webclient game.js handleLoad (~line 770).
				// If a predicted local bullet matches this server bullet's
				// projectileId + angle (within ~5°), keep the prediction
				// rendering and skip inserting the duplicate. Otherwise
				// the player sees their predicted shot AND the server
				// echo as two side-by-side bullets, and worse, the
				// server-confirmed copy without an existing match would
				// render at a stale (one-RTT-old) position.
				if (b.hasFlag(ProjectileFlag.PLAYER_PROJECTILE)) {
					final Bullet match = findMatchingPredictedBullet(cli, b);
					if (match != null) {
						// Adopt the server's authoritative ID so the eventual
						// UnloadPacket (keyed by server ID) can actually find
						// and remove this bullet. Without this, predicted
						// bullets are stored under a client-random ID and
						// outlive every cleanup signal — they fly forever
						// past walls until the wall-clock cap kicks in (and
						// only if something culls expired bullets at all).
						final long oldId = match.getId();
						final long newId = b.getId();
						if (oldId != newId) {
							cli.getRealm().getBullets().remove(oldId);
							match.setId(newId);
							cli.getRealm().getBullets().put(newId, match);
						}
						match.setPredicted(false);
						continue;
					}
				}
				cli.getRealm().addBulletIfNotExists(b);
			}

			for (final NetEnemy enemy : loadPacket.getEnemies()) {
				// Web parity (game.js LoadPacket handler): the server now
				// re-broadcasts the full enemy set every LoadPacket. For an
				// already-tracked enemy, refresh authoritative dx/dy/health
				// + serverPos/lastVelUpdate WITHOUT touching pos — the
				// per-frame extrapolator walks pos toward targetX/Y. Re-
				// instantiating via asEnemy() and overwriting pos every
				// LoadPacket is what produced the visible teleport-back-
				// to-spawn rubber-band on the native client. targetX/Y is
				// only refreshed when the server's reported position has
				// genuinely diverged from our last snapshot — sub-pixel
				// jitter is suppressed so we don't reset the close-step
				// every tick.
				final Enemy existing = cli.getRealm().getEnemy(enemy.getId());
				if (existing != null) {
					existing.setEnemyId(enemy.getEnemyId());
					existing.setWeaponId(enemy.getWeaponId());
					if (enemy.getSize() > 0) existing.setSize(enemy.getSize());
					existing.setDifficulty(enemy.getDifficulty());
					if (enemy.getHealth() > 0) {
						existing.setHealth(enemy.getHealth());
						if (enemy.getMaxHealth() > 0) {
							existing.setHealthpercent(
									(float) enemy.getHealth() / (float) enemy.getMaxHealth());
						}
					}
					// Re-prime dead-reckoning state without snapping pos.
					existing.refreshFromLoadPacket(
							enemy.getPos().x, enemy.getPos().y,
							enemy.getDX(), enemy.getDY());
				} else {
					final Enemy e = enemy.asEnemy();
					cli.getRealm().addEnemyIfNotExists(e);
				}
				// Register short ID mapping for compact movement packets
				if (enemy.getShortId() != 0) {
					cli.getShortIdToLongId().put(enemy.getShortId(), enemy.getId());
				}
			}

			for (final NetPortal portal : loadPacket.getPortals()) {
				// MUST go through asPortal() — IOService.mapModel() copies fields
				// via reflection and silently skips the sprite-loading step,
				// leaving every portal with sprite=null and rendering blank.
				final Portal p = portal.asPortal();
				cli.getRealm().addPortalIfNotExists(p);
			}
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to handle Load Packet. Reason: {}", e);
		}
	}

	public static void handleUnloadClient(RealmManagerClient cli, Packet packet) {
		final UnloadPacket unloadPacket = (UnloadPacket) packet;
		try {
			final Realm realm = cli.getRealm();
			for (final Long p : unloadPacket.getPlayers()) {
				if (p == cli.getCurrentPlayerId()) {
					continue;
				}
				final Player existing = realm.getPlayers().get(p);
				if (existing == null) {
					ClientGameLogic.log.error("[CLIENT] Player {} does not exist", p);
					continue;
				}
				// WHY: route through removePlayer so spatialGrid /
				// shortIdAllocator entries are released too. Bypassing it
				// (which the previous code did) leaked one grid + one
				// allocator entry per despawn.
				final short shortId = realm.getShortIdAllocator().toShort(p);
				realm.removePlayer(existing);
				if (shortId != 0) {
					cli.getShortIdToLongId().remove(shortId);
				}
			}
			for (final Long lc : unloadPacket.getContainers()) {
				final LootContainer removed = cli.getRealm().getLoot().remove(lc);
				if (removed == null) {
					ClientGameLogic.log.error("[CLIENT] LootContainer {} does not exist", lc);
				}
			}
			for (final Long b : unloadPacket.getBullets()) {
				final Bullet removed = cli.getRealm().getBullets().remove(b);
				if (removed == null) {
					ClientGameLogic.log.error("[CLIENT] Bullet {} does not exist", b);
				}
			}
			for (final Long e : unloadPacket.getEnemies()) {
				final Enemy existing = realm.getEnemies().get(e);
				if (existing == null) {
					ClientGameLogic.log.error("[CLIENT] Enemy {} does not exist", e);
					continue;
				}
				// Same reasoning as the player branch above — go through
				// removeEnemy so spatialGrid + short-id state are cleaned
				// and the entity's onRemoved() drops its SpriteSheet wrapper
				// (TextureRegion[][] arrays + animSets HashMaps), which is
				// the bulk of per-enemy heap retention.
				final short shortId = realm.getShortIdAllocator().toShort(e);
				realm.removeEnemy(existing);
				if (shortId != 0) {
					cli.getShortIdToLongId().remove(shortId);
				}
			}
			for (final Long p : unloadPacket.getPortals()) {
				final Portal removed = cli.getRealm().getPortals().remove(p);
				if (removed == null) {
					ClientGameLogic.log.error("[CLIENT] Portal {} does not exist", p);
				}
			}

		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to handle Unload Packet. Reason: {}", e);
		}
	}

	public static void handleTextClient(RealmManagerClient cli, Packet packet) {
		final TextPacket textPacket = (TextPacket) packet;
		ClientGameLogic.log.info("[CLIENT] Recieved Text Packet \nTO: {}\nFROM: {}\nMESSAGE: {}", textPacket.getTo(),
				textPacket.getFrom(), textPacket.getMessage());
		try {
			cli.getState().getPui().enqueueChat(textPacket.clone());
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to handle text packet. Reason: {}", e.getMessage());
		}
	}
	
	// Client command codes for readability
	private static final byte LOGIN_RESPONSE_MSG_CODE = 2;
	private static final byte SERVER_ERROR_MSG_CODE = 4;
	private static final byte PLAYER_ACCOUNT_MSG_CODE = 5;
	// Switched command packet message type handler
	public static void handleCommandClient(RealmManagerClient cli, Packet packet) {
		final CommandPacket commandPacket = (CommandPacket) packet;
		ClientGameLogic.log.info("[CLIENT] Recieved Command Packet for Player {} Command={}",
				commandPacket.getPlayerId(), commandPacket.getCommand());
		try {
			switch (commandPacket.getCommandId()) {
			case LOGIN_RESPONSE_MSG_CODE:
				final LoginResponseMessage loginResponse = CommandType.fromPacket(commandPacket);
				ClientGameLogic.doLoginResponse(cli, loginResponse);
				break;
			case SERVER_ERROR_MSG_CODE:
				final ServerErrorMessage serverError = CommandType.fromPacket(commandPacket);
				ClientGameLogic.handleServerError(cli, serverError);
				break;
			case PLAYER_ACCOUNT_MSG_CODE:
				final PlayerAccountMessage playerAccount = CommandType.fromPacket(commandPacket);
				cli.getState().setAccount(playerAccount.getAccount());
				break;
			}
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to handle client command packet. Reason: {}", e.getMessage());
		}
	}

	public static void handleObjectMoveClient(RealmManagerClient cli, Packet packet) {
		final ObjectMovePacket objectMovePacket = (ObjectMovePacket) packet;
		for (NetObjectMovement movement : objectMovePacket.getMovements()) {
			final EntityType type = movement.getTargetEntityType();
			if (type == null) {
				continue;
			}
			switch (type) {
			case PLAYER:
				final Player playerToUpdate = cli.getRealm().getPlayer(movement.getEntityId());
				if (playerToUpdate == null) {
					break;
				}
				if (cli.getCurrentPlayerId() == movement.getEntityId()) {
					if (cli.isAwaitingRealmTransition()) {
						// Snap to server position after portal transition.
						// Re-anchor sub-tick interp so renderX picks up from
						// the snapped pos instead of pre-snap.
						playerToUpdate.applyMovement(movement);
						if (cli.getState() != null) {
							cli.getState().resetInterpAnchor(movement.getPosX(), movement.getPosY());
						}
						cli.setAwaitingRealmTransition(false);
					}
					// Otherwise: ignore. Local player runs client-side
					// prediction in PlayState.input(); server reconciliation
					// for self goes through PlayerPosAckPacket, which is the
					// only path that resets the sub-tick interp anchor. The
					// previous applyMovementLerp(0.8f) here yanked pos 80%
					// toward server every server tick without anchor reset,
					// producing a visible stutter as the renderX lerp's
					// interpFromX kept pointing at pre-yank positions.
				} else {
					// Other players: use dead reckoning correction (smooth blend)
					// Their positions are extrapolated in PlayState.movePlayer()
					playerToUpdate.applyServerCorrection(movement);
				}
				break;
			case ENEMY:
				final Enemy enemyToUpdate = cli.getRealm().getEnemy(movement.getEntityId());
				if (enemyToUpdate == null) {
					break;
				}
				// Dead reckoning correction: blend toward server position over several
				// frames instead of snapping. The client extrapolates using velocity
				// between corrections, so movement stays smooth at lower server send rates.
				enemyToUpdate.applyServerCorrection(movement);
				break;
			case BULLET:
				final Bullet bulletToUpdate = cli.getRealm().getBullet(movement.getEntityId());
				if (bulletToUpdate == null) {
					break;
				}
				bulletToUpdate.applyMovementLerp(movement);
				break;
			default:
				break;
			}
		}
	}

	/**
	 * Handles CompactMovePacket — bandwidth-efficient movement corrections using
	 * 2-byte short entity IDs. Resolves short IDs via the mapping established
	 * in LoadPacket, then applies dead reckoning corrections.
	 */
	public static void handleCompactMoveClient(RealmManagerClient cli, Packet packet) {
		final CompactMovePacket compactPacket = (CompactMovePacket) packet;
		for (NetCompactMovement cm : compactPacket.getMovements()) {
			final Long longId = cli.getShortIdToLongId().get(cm.getShortEntityId());
			if (longId == null) {
				continue; // Unknown short ID — entity not yet loaded
			}
			// Build a lightweight movement for the correction
			final NetObjectMovement movement = new NetObjectMovement();
			movement.setEntityId(longId);
			movement.setPosX(cm.getPosX());
			movement.setPosY(cm.getPosY());
			movement.setVelX(cm.getVelX());
			movement.setVelY(cm.getVelY());

			// Try enemy first (most common in combat), then player
			final Enemy enemyToUpdate = cli.getRealm().getEnemy(longId);
			if (enemyToUpdate != null) {
				enemyToUpdate.applyServerCorrection(movement);
				continue;
			}
			final Player playerToUpdate = cli.getRealm().getPlayer(longId);
			if (playerToUpdate != null) {
				if (longId == cli.getCurrentPlayerId()) {
					playerToUpdate.applyMovementLerp(movement, 0.8f);
				} else {
					playerToUpdate.applyServerCorrection(movement);
				}
			}
		}
	}

	public static void handleUpdateClient(RealmManagerClient cli, Packet packet) {
		final UpdatePacket updatePacket = (UpdatePacket) packet;
		final Player toUpdate = cli.getRealm().getPlayer((updatePacket.getPlayerId()));
		if (toUpdate != null) {
			toUpdate.applyUpdate(updatePacket, cli.getState());
		} else {
			final Enemy enemyToUpdate = cli.getRealm().getEnemy((updatePacket.getPlayerId()));
			if (enemyToUpdate != null) {
				enemyToUpdate.applyUpdate(updatePacket, cli.getState());
				log.debug("[CLIENT] Recieved update for enemy {}", updatePacket);
			}
		}
	}

	public static void handlePlayerStateClient(RealmManagerClient cli, Packet packet) {
		final PlayerStatePacket statePacket =
			(PlayerStatePacket) packet;
		final Player toUpdate = cli.getRealm().getPlayer(statePacket.getPlayerId());
		if (toUpdate != null) {
			toUpdate.applyState(statePacket);
		} else {
			final Enemy enemyToUpdate = cli.getRealm().getEnemy(statePacket.getPlayerId());
			if (enemyToUpdate != null) {
				enemyToUpdate.applyState(statePacket);
			}
		}
	}

	private static void handleServerError(RealmManagerClient cli, ServerErrorMessage message) {
		ClientGameLogic.log.error("[CLIENT] Recieved Server Error ***{}", message);
		cli.getState().getPui().enqueueChat(TextPacket.create("SYSTEM", "", message.toString()));
	}

	private static void doLoginResponse(RealmManagerClient cli, LoginResponseMessage loginResponse) {
		try {
			if (loginResponse.isSuccess()) {
				final CharacterClass cls = CharacterClass.valueOf(loginResponse.getClassId());
				final Player player = new Player(loginResponse.getPlayerId(),
						new Vector2f(loginResponse.getSpawnX(), loginResponse.getSpawnY()), GlobalConstants.PLAYER_SIZE,
						cls);
				ClientGameLogic.log.info("[CLIENT] Login succesful, added Player ID {}", player.getId());
				// Carry the server-resolved chatRole (sysadmin / admin /
				// mod / demo) onto the local Player up front. Without
				// this, the local nameplate stayed off-white because
				// LoadPacket only ships chatRole for REMOTE players —
				// the local entry is filtered out at line 409 — so
				// nothing else writes the field for us.
				if (loginResponse.getChatRole() != null && !loginResponse.getChatRole().isEmpty()) {
					player.setChatRole(loginResponse.getChatRole());
				}
				player.setSpriteSheet(GameSpriteManager.loadClassSprites(cls));
				ClientGameLogic.DATA_SERVICE.setSessionToken(loginResponse.getToken());
				cli.getState().setAccount(loginResponse.getAccount());
				cli.getState().loadClass(player, cls, true);
				cli.setCurrentPlayerId(player.getId());
				cli.getState().setPlayerId(player.getId());
				cli.startHeartbeatThread();
				// Tell the server we're ready to receive tiles
				try {
					cli.getClient().sendRemote(LoginAckPacket.from(player.getId()));
				} catch (Exception ex) {
					log.error("[CLIENT] Failed to send LoginAck. Reason: {}", ex.getMessage());
				}
				final TextPacket packet = TextPacket.create("SYSTEM", "Player",
						"Welcome to OpenRealm Server " + GameLauncher.GAME_VERSION);
				cli.getState().getPui().enqueueChat(packet);
			}
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to respond to login response. Reason: {}", e.getMessage());
		}
	}
}
