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
import com.openrealm.game.entity.Entity;
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
import com.openrealm.game.ui.PotionStorageWindow;
import com.openrealm.net.client.packet.OpenFameStorePacket;
import com.openrealm.net.client.packet.OpenForgePacket;
import com.openrealm.net.client.packet.OpenPotionStoragePacket;
import com.openrealm.net.client.packet.PotionStorageUpdatePacket;
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
		final String fromName = tradeRequest.getRequestingPlayerName();
		// Surface a UI popup with Accept/Decline buttons (PlayerUI renders
		// when pendingTradeRequestFrom is set). Webclient parity with
		// trade.js showTradeRequestPopup. Also keep the chat-line so
		// players who minimize the popup can still see who asked.
		cli.getState().getPui().setPendingTradeRequestFrom(fromName);
		cli.getState().getPui().setPendingTradeRequestStartMs(System.currentTimeMillis());
		cli.getState().getPui().getPlayerChat().addChatMessage(TextPacket.create(fromName,
				cli.getState().getPlayer().getName(),
				fromName + " wants to trade — Accept / Decline"));
	}
	
	@PacketHandlerClient(AcceptTradeRequestPacket.class)
	public static void handleAcceptTrade(RealmManagerClient cli, Packet packet) {
		final AcceptTradeRequestPacket tradeRequest = (AcceptTradeRequestPacket) packet;
		log.info("[CLIENT] Recieved trade packet. Accepted = {}", tradeRequest.isAccepted());

		if (tradeRequest.isAccepted()) {
			log.info("[CLIENT] Trade accepted between {} and {}", tradeRequest.getPlayer0().getName(), tradeRequest.getPlayer1().getName());
			final var pui = cli.getState().getPui();
			pui.setPendingTradeRequestFrom(null); // close popup if open
			pui.setTrading(true);
			// Server constructs the packet with player0=self, player1=partner
			// for each recipient (see ServerTradeManager line 131-132). So
			// player1Inv is always the partner's inventory regardless of
			// who sent /trade. Capture it now — selection-update packets
			// carry only Boolean[] flags, no items, so this snapshot is
			// the only source of truth for "partner inventory contents"
			// during the trade.
			pui.setTradePartnerName(tradeRequest.getPlayer1().getName());
			pui.setPartnerClassId(tradeRequest.getPlayer1().getClassId());
			pui.setPartnerDyeId(tradeRequest.getPlayer1().getDyeId());
			pui.setPartnerInventory(buildPartnerInventory(tradeRequest));
		} else {
			log.info("[CLIENT] Trade closed");
			final var pui = cli.getState().getPui();
			pui.setPendingTradeRequestFrom(null); // close popup if open
			// Trigger the post-trade 1s "trade complete" delay rather
			// than closing the overlay instantly. closeTradeOverlayDeferred
			// keeps the overlay rendered with both 'CONFIRMED' badges
			// visible for 1000ms, then clears the trade state. Mirrors
			// the webclient pattern. If we weren't actually trading
			// (e.g. /trade refused before accept), close immediately.
			if (pui.isTrading()) {
				pui.scheduleTradeOverlayClose();
			} else {
				pui.setTrading(false);
				pui.setCurrentTradeSelection(null);
				pui.setTradePartnerName(null);
				pui.setPartnerInventory(null);
				pui.setPartnerClassId(0);
				pui.setPartnerDyeId(0);
				pui.clearTradeSelections();
			}
		}
	}

	/** Convert AcceptTradeRequestPacket.player1Inv (NetGameItem[]) into a
	 *  GameItem[] the trade overlay can read directly. Empty / itemId<=0
	 *  slots stay null so the overlay's null-check renders an empty cell. */
	private static com.openrealm.game.entity.item.GameItem[] buildPartnerInventory(
			AcceptTradeRequestPacket pkt) {
		final com.openrealm.net.entity.NetGameItem[] src = pkt.getPlayer1Inv();
		if (src == null) return new com.openrealm.game.entity.item.GameItem[0];
		final com.openrealm.game.entity.item.GameItem[] out =
				new com.openrealm.game.entity.item.GameItem[src.length];
		for (int i = 0; i < src.length; i++) {
			if (src[i] == null) continue;
			if (src[i].getItemId() <= 0) continue;
			out[i] = src[i].asGameItem();
		}
		return out;
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

	@PacketHandlerClient(com.openrealm.net.client.packet.AbilityCastStartPacket.class)
	public static void handleAbilityCastStartClient(RealmManagerClient cli, Packet packet) {
		final com.openrealm.net.client.packet.AbilityCastStartPacket cast =
				(com.openrealm.net.client.packet.AbilityCastStartPacket) packet;
		try {
			if (cli.getState() == null) return;
			// Store as [startEpochMs, durationMs] so PlayState's renderer can
			// compute progress and auto-clear when the cast completes.
			cli.getState().getActiveCasts().put(cast.getPlayerId(),
					new long[] { System.currentTimeMillis(), cast.getDurationMs() });
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to handle AbilityCastStart Packet. Reason: {}", e);
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
			cli.getClient().sendRemote(new DeathAckPacket());
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
			// Initial session connect: prevRealmId is the default 0L and
			// no client-initiated portal use has occurred. The cross-realm
			// entity wipe below must NOT fire on this transition, or it
			// would race against the server's initial LoadPacket (which
			// arrives before LoadMap in some session boots) and erase the
			// portals/enemies the player just received → 1–2s "empty map"
			// gap until the next server broadcast loop refilled them.
			// Webclient parity: game.handleLoadMap never touches entity
			// state; renderer.prepareForNewRealm only clears the PIXI pool.
			final boolean isInitialConnect = (prevRealmId == 0L);
			// Reset the tile grid (and thus the minimap, which rebuilds from it)
			// on ANY new map: a realm/map id change, OR a client-initiated
			// transition that rebuilt the TileManager via Realm.loadMap. The latter
			// catches a nested dungeon that reuses the parent's realm/map id, where
			// realmChanged stays false. Mirrors the web mapGridReset signal.
			final boolean mapGridReset = realmChanged || cli.getRealm().isTileGridRebuilt();
			cli.getRealm().setTileGridRebuilt(false);

			// Set core realm state BEFORE the minimap init: a UI-side throw must
			// never leave the realm id stale (which broke dungeon exit/nexus).
			cli.getRealm().setRealmId(loadPacket.getRealmId());
			cli.getRealm().setMapId(loadPacket.getMapId());
			cli.getRealm().setDungeonId((int) loadPacket.getDungeonId());
			cli.getState().getPui().getMinimap().initializeMap((int) loadPacket.getMapId(),
					(int) loadPacket.getDungeonId());
			// Zero the tile layers + fog-of-war on any transition so a nested
			// dungeon doesn't inherit the prior realm's tiles bleeding through
			// on the minimap.
			if (mapGridReset) {
				cli.getRealm().getTileManager().resetTiles((int) loadPacket.getMapId(), (int) loadPacket.getDungeonId());
			}
			cli.getRealm().getTileManager().mergeMap(loadPacket);

			if (realmChanged) {
				String zoneName = "Map " + loadPacket.getMapId();
				float diff = 0f;
				try { diff = cli.getRealm().getDifficulty(); } catch (Exception ignored) {}
				cli.getState().getPui().getRealmTransition().trigger(zoneName, diff);
				// Clear chat on realm change so the log doesn't carry over
				// across maps / instances. Mirrors the web client.
				try { cli.getState().getPui().getPlayerChat().clearChat(); } catch (Exception ignored) {}
				// Cross-realm carry-over wipe — gated on !isInitialConnect.
				// User-initiated portal use already calls Realm.loadMap()
				// BEFORE the new LoadPacket arrives, so the new realm's
				// entities populate into a clean map. On the FIRST LoadMap
				// of a session there's no prior realm to leak from, and
				// the LoadPacket race makes wiping here harmful (see
				// isInitialConnect comment above).
				if (!isInitialConnect) {
					try {
						final long localId = cli.getCurrentPlayerId();
						final java.util.Map<Long, com.openrealm.game.entity.Player> players = cli.getRealm().getPlayers();
						if (players != null) {
							players.entrySet().removeIf(e -> e.getKey() != localId);
						}
						final java.util.Map<Long, com.openrealm.game.entity.Enemy> enemies = cli.getRealm().getEnemies();
						if (enemies != null) enemies.clear();
						final java.util.Map<Long, com.openrealm.game.entity.Bullet> bullets = cli.getRealm().getBullets();
						if (bullets != null) bullets.clear();
						final java.util.Map<Long, com.openrealm.game.entity.item.LootContainer> loot = cli.getRealm().getLoot();
						if (loot != null) loot.clear();
						final java.util.Map<Long, com.openrealm.game.entity.Portal> portals = cli.getRealm().getPortals();
						if (portals != null) portals.clear();
						// Buffered UpdatePackets for the previous realm's
						// players are stale — drop them so a same-id player
						// in the new realm doesn't replay an old packet.
						PENDING_UPDATES.clear();
					} catch (Exception ignored) { /* defensive — never block transition */ }
				}

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
						// CRITICAL: also reset the LERPED render position.
						// The minimap (and any other code that reads
						// getEffectiveRenderX) keys off renderX, not pos.x.
						// Without this reset, the dot stayed at the previous
						// realm's tile coords after a portal transition —
						// e.g. nexus center (~tile 8) lingered as the
						// overworld dot's position, putting it in the
						// top-left corner of the overworld minimap until
						// the player issued a movement input that ran the
						// PlayState lerp pipeline. Setting renderX = NaN
						// makes getEffectiveRenderX fall back to pos.x for
						// the next render frame, which IS the freshly-set
						// spawn pos.
						local.setRenderPos(Float.NaN, Float.NaN);
						cli.getState().resetInterpAnchor(spawn.x, spawn.y);
						// Drop the rollback-prediction input buffer + any
						// pending visual smoothing offset. A fresh map's
						// physics doesn't share state with the previous
						// realm, so replaying stale inputs would teleport
						// the player off the spawn point on the first
						// PlayerPosAck after the transition.
						cli.getState().clearPendingInputs();
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
			// Route through PlayState's rollback-prediction reconciler
			// instead of hard-snapping pos here. The reconciler drops
			// confirmed inputs from the buffer, replays the remaining
			// unacked ones from the server pos, and absorbs any small
			// divergence into a decaying visual smoothing offset —
			// matching the webclient's Game.handlePosAck (game.js#840).
			// The previous implementation snapped pos to the ack pos
			// directly, which under high latency rubber-banded the
			// player back by (latency × speed) pixels every server
			// tick because predictions made between the player's
			// local input and the ack's arrival were thrown away.
			if (cli.getState() != null) {
				cli.getState().reconcileLocalPlayerPos(ack.getSeq(), ack.getPosX(), ack.getPosY());
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
			// Store the server-wide snapshot for the minimap to render
			// — this is the SAME path the webclient uses
			// (game.minimapPlayers). Do NOT overwrite cli.getRealm().getPlayer
			// positions: those are LOCAL realm players whose positions are
			// authoritatively driven by ObjectMovePacket / PlayerPosAckPacket;
			// the global packet carries cross-realm positions that would
			// otherwise drag in-realm dots around as players in OTHER
			// realms moved.
			cli.getState().setMinimapPlayers(gp.getPlayers());
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

	@PacketHandlerClient(OpenPotionStoragePacket.class)
	public static void handleOpenPotionStorageClient(RealmManagerClient cli, Packet packet) {
		try {
			if (cli.getState() == null || cli.getState().getPui() == null) return;
			final OpenPotionStoragePacket open = (OpenPotionStoragePacket) packet;
			final PotionStorageWindow win = cli.getState().getPui().getPotionStorageWindow();
			win.setRealmManager(cli);
			win.setPlayState(cli.getState());
			win.open(open.getItems());
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to handle OpenPotionStorage Packet. Reason: {}", e);
		}
	}

	@PacketHandlerClient(com.openrealm.net.client.packet.PartyUpdatePacket.class)
	public static void handlePartyUpdateClient(RealmManagerClient cli, Packet packet) {
		try {
			if (cli.getState() == null) return;
			final com.openrealm.net.client.packet.PartyUpdatePacket upd =
					(com.openrealm.net.client.packet.PartyUpdatePacket) packet;
			cli.getState().setPartyId(upd.getPartyId());
			cli.getState().setPartyMembers(upd.getMembers() == null
					? new com.openrealm.net.entity.NetPartyMember[0] : upd.getMembers());
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to handle PartyUpdate Packet. Reason: {}", e);
		}
	}

	@PacketHandlerClient(PotionStorageUpdatePacket.class)
	public static void handlePotionStorageUpdateClient(RealmManagerClient cli, Packet packet) {
		try {
			if (cli.getState() == null || cli.getState().getPui() == null) return;
			final PotionStorageUpdatePacket upd = (PotionStorageUpdatePacket) packet;
			cli.getState().getPui().getPotionStorageWindow().refresh(upd.getItems());
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to handle PotionStorageUpdate Packet. Reason: {}", e);
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
			//   821-828  -> 8 dyes      (500 fame each)
			//   808-815  -> 8 crystals  (1000 fame each)
			//   830-836  -> 7 gems      (5000 fame each — endgame power tier)
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
			entries.add(new FameStoreWindow.Entry(854, "Attack Scaling Gem",    GEM_COST));
			entries.add(new FameStoreWindow.Entry(855, "Defense Scaling Gem",   GEM_COST));
			entries.add(new FameStoreWindow.Entry(856, "Dexterity Scaling Gem", GEM_COST));
			entries.add(new FameStoreWindow.Entry(857, "Vitality Scaling Gem",  GEM_COST));
			entries.add(new FameStoreWindow.Entry(858, "Health Scaling Gem",    GEM_COST));
			entries.add(new FameStoreWindow.Entry(859, "Mana Scaling Gem",      GEM_COST));
			store.setEntries(entries);
			store.show();
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to handle OpenFameStore Packet. Reason: {}", e);
		}
	}

	// Angle window for matching server-echoed player bullets back to a
	// locally-predicted bullet. Bumped from 0.09 (5°) to 0.12 (~6.9°) to
	// match the SPREAD constant — without this, the CENTER bullet of a
	// MultiShot fan could be the lone predicted bullet that DIDN'T pair
	// up (float-precision drift in (i - (totalBullets-1)/2f)*SPREAD
	// pushed the absolute angle diff just past the old 0.09 threshold for
	// totalBullets=3 i=1), producing a phantom predicted center shot
	// that drifted forever between the two server-echoed flank shots.
	private static final float PREDICTED_ANGLE_TOLERANCE = 0.12f;
	// Position-proximity window (px²) used as a fallback when the
	// server-echoed bullet has no PLAYER_PROJECTILE flag (many ability
	// projectile groups in projectile-groups.json ship with flags: [],
	// so the flag-gated dedup misses them entirely and the player sees
	// both their predicted shot and the server's authoritative copy).
	// Webclient parity (game.js handleLoad ~line 770).
	private static final float PREDICTED_POS_TOLERANCE_SQ = 96f * 96f;

	/** Find a locally-predicted bullet that corresponds to the server's
	 *  authoritative {@code server} bullet. Match is by projectileId +
	 *  angle (within {@link #PREDICTED_ANGLE_TOLERANCE}). When the
	 *  server bullet carries the PLAYER_PROJECTILE flag the angle
	 *  match alone is enough; for unflagged ability projectiles we
	 *  also require the predicted bullet to be within
	 *  {@link #PREDICTED_POS_TOLERANCE_SQ} so an unrelated enemy bullet
	 *  with a coincidental angle can't be mistakenly deduped against
	 *  the player's predicted shot. */
	private static Bullet findMatchingPredictedBullet(RealmManagerClient cli, Bullet server) {
		final long localId = cli.getCurrentPlayerId();
		if (localId == 0L) return null;
		final boolean serverIsFlagged = server.hasFlag(ProjectileFlag.PLAYER_PROJECTILE);
		for (final Bullet pb : cli.getRealm().getBullets().values()) {
			if (!pb.isPredicted()) continue;
			if (pb.getSrcEntityId() != localId) continue;
			if (pb.getProjectileId() != server.getProjectileId()) continue;
			final float angleDiff = Math.abs(pb.getAngle() - server.getAngle());
			final boolean angleNear = angleDiff < PREDICTED_ANGLE_TOLERANCE
					|| angleDiff > (float) (Math.PI * 2) - PREDICTED_ANGLE_TOLERANCE;
			if (!angleNear) continue;
			if (serverIsFlagged) return pb;
			// Unflagged: also gate on position so we don't misalign with a
			// far-away predicted bullet that happens to share an angle.
			if (pb.getPos() == null || server.getPos() == null) return pb;
			final float dx = pb.getPos().x - server.getPos().x;
			final float dy = pb.getPos().y - server.getPos().y;
			if (dx * dx + dy * dy < PREDICTED_POS_TOLERANCE_SQ) return pb;
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
				if (wasNew) {
					// If we'd already received an UpdatePacket for this
					// player before LoadPacket added them (race between
					// LoadPacket assembly and server's broadcast loop),
					// replay it now so HP/MP/exp/inventory aren't blank
					// until the next state change. Webclient parity: it
					// suffers the same race but typically loads tiles
					// faster so it doesn't manifest.
					replayPendingUpdate(cli, p.getId());
				}
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

			// Approximate one-way latency (ms) — used to fast-forward a
			// freshly-arrived bullet by RTT/2 along its angle so it
			// doesn't visually appear to "spawn at the firing player's
			// feet and slowly catch up". Webclient parity (game.js
			// handleLoad ~line 808). Falls back to 0 (no catchup) when
			// PerfMetrics hasn't accumulated samples yet.
			int oneWayMsForCatchup = 0;
			try {
				oneWayMsForCatchup = com.openrealm.game.ui.PerfMetrics.get().getPing();
			} catch (Exception ignored) { /* leave as 0 */ }
			final float catchupSec = Math.min(oneWayMsForCatchup / 1000f, 0.25f);
			final float catchupScale = catchupSec * 64f;

			for (final NetBullet bullet : loadPacket.getBullets()) {
				final Bullet b = bullet.asBullet();
				// WHY: Mirrors webclient game.js handleLoad (~line 770).
				// If a predicted local bullet matches this server bullet's
				// projectileId + angle, keep the prediction rendering and
				// skip the duplicate. The match check now runs for ALL
				// player bullets (not just PLAYER_PROJECTILE-flagged ones)
				// — many ability projectile groups in projectile-groups.json
				// ship with flags: [], so the old flag gate let those
				// bullets through unmatched and the player saw both their
				// predicted shot AND the server's echo as two ghosts.
				final Bullet match = findMatchingPredictedBullet(cli, b);
				if (match != null) {
					// Adopt the server's authoritative ID so the eventual
					// UnloadPacket (keyed by server ID) can actually find
					// and remove this bullet. Without this, predicted
					// bullets are stored under a client-random ID and
					// outlive every cleanup signal — they fly forever
					// past walls until the wall-clock cap kicks in.
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
				// Homing: server-authoritative position. Deterministic replay
				// doesn't hold for target-seeking paths, so snap an already-tracked
				// homing bullet to the server pos/heading/target on every snapshot,
				// and add a new one at the server state with NO straight-line
				// catchup (the catchup below assumes straight motion and flings
				// homing bullets onto random orbits when they cross the viewport
				// boundary or get re-loaded).
				if (b.hasFlag(ProjectileFlag.HOMING)) {
					final Bullet tracked = cli.getRealm().getBullets().get(b.getId());
					if (tracked != null && tracked.getPos() != null && b.getPos() != null) {
						tracked.getPos().setX(b.getPos().x);
						tracked.getPos().setY(b.getPos().y);
						tracked.setAngle(b.getAngle());
						tracked.setTargetEntityId(b.getTargetEntityId());
					} else {
						// Hand off the predicted seeker to the server's authoritative
						// copy: drop our predicted homing bullet (its steered angle
						// won't match the dedup above) and add the server bullet so
						// snap + skip-consume + unload-removal all apply to it.
						cli.getRealm().getBullets().values().removeIf(pb -> pb != null && pb.isPredicted()
								&& pb.getProjectileId() == b.getProjectileId()
								&& pb.hasFlag(ProjectileFlag.HOMING));
						cli.getRealm().addBulletIfNotExists(b);
					}
					continue;
				}
				// Non-matched (NOT our own predicted) — typically another
				// player's bullet. Fast-forward by RTT/2 along its angle
				// so it visually appears where the server has it NOW
				// rather than at the snapshot pos one RTT ago. Skip for
				// orbital + parametric (their position function isn't a
				// straight line) and for zero-magnitude (stationary
				// effect bullets).
				if (catchupScale > 0.5f && b.getMagnitude() > 0
						&& !b.hasFlag(ProjectileFlag.ORBITAL)
						&& !b.hasFlag(ProjectileFlag.PARAMETRIC)
						&& !b.hasFlag(ProjectileFlag.INVERTED_PARAMETRIC)
						&& b.getPos() != null) {
					final float advance = b.getMagnitude() * catchupScale;
					final float velX = b.getSinAngle() * advance;
					final float velY = b.getCosAngle() * advance;
					b.getPos().addX(velX);
					b.getPos().addY(velY);
					b.setRange(b.getRange() - advance);
				}
				cli.getRealm().addBulletIfNotExists(b);

				// Drive the firing pose for the player who fired this bullet,
				// the same way the local player's pose is driven by its own
				// shots. Grouped per volley by createdTime so a multishot burst
				// restarts the clip once, not once per pellet. Direction comes
				// from the bullet angle, so a stationary shooter still faces the
				// way they fired.
				final long shooterId = b.getSrcEntityId();
				if (shooterId != cli.getCurrentPlayerId()) {
					Entity shooter = cli.getRealm().getPlayer(shooterId);
					if (shooter == null) shooter = cli.getRealm().getEnemy(shooterId);
					if (shooter != null && shooter.getLastShotCreatedTime() != b.getCreatedTime()) {
						shooter.setLastShotCreatedTime(b.getCreatedTime());
						shooter.triggerAttackAnimation(b.getAngle());
					}
				}
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
				// A null here is normal: PlayState already de-renders peers the
				// instant they leave render range, so the server's UnloadPacket
				// often arrives for a peer we dropped locally already.
				cli.derenderRemotePlayer(p);
			}
			for (final Long lc : unloadPacket.getContainers()) {
				final LootContainer removed = cli.getRealm().getLoot().remove(lc);
				if (removed == null) {
					ClientGameLogic.log.error("[CLIENT] LootContainer {} does not exist", lc);
				}
			}
			for (final Long b : unloadPacket.getBullets()) {
				// Client-side culling (range/lifetime/terrain in PlayState
				// update) often removes bullets BEFORE the server's
				// UnloadPacket round-trips back, so a null here is the
				// normal case for player-fired projectiles, not an error.
				// Demoted from ERROR to TRACE to keep the log readable.
				final Bullet removed = cli.getRealm().getBullets().remove(b);
				if (removed == null && ClientGameLogic.log.isTraceEnabled()) {
					ClientGameLogic.log.trace("[CLIENT] Bullet {} already culled locally", b);
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
			// Party invite hook — same regex match the webclient uses to
			// pop up the Accept/Decline panel. PlayerUI owns the actual
			// prompt overlay; this just dispatches inviter name to it.
			if ("SYSTEM".equalsIgnoreCase(textPacket.getFrom())) {
				final String msg = textPacket.getMessage() == null ? "" : textPacket.getMessage();
				final int idx = msg.indexOf(" invited you to a party");
				if (idx > 0) {
					final String inviter = msg.substring(0, idx).trim();
					cli.getState().getPui().showPartyInvitePrompt(inviter);
				}
			}
			cli.getState().getPui().enqueueChat(textPacket.clone());
			// Float the line over the sender's head too. Skip SYSTEM / event
			// broadcasts — those have no on-screen player to anchor to.
			final String from = textPacket.getFrom();
			if (from != null && !"SYSTEM".equalsIgnoreCase(from) && !"EVENT_MARKER".equalsIgnoreCase(from)) {
				cli.getState().addChatBubble(from, textPacket.getMessage());
			}
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
					// Attack pose is driven by observed projectiles (see the
					// LoadPacket bullet loop), not this movement flag, so it
					// works for a stationary shooter and cycles per shot.
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

	/** UpdatePackets that arrive for an unknown player id (the server sent
	 *  the broadcast but the LoadPacket adding this player either hadn't
	 *  arrived yet or was skipped by the pos==(0,0) sentinel guard).
	 *  Buffered here and applied when {@link #handleLoadClient} actually
	 *  adds the player. Without this, the joining player's HP/MP/exp/
	 *  inventory stayed empty because the server's delta-check
	 *  (`oldOtherUpdate.equals(stripped, false)`) considered the
	 *  one-shot first-send already done and never resent the UpdatePacket
	 *  even though the receiving client never actually applied it. */
	private static final java.util.concurrent.ConcurrentHashMap<Long, UpdatePacket> PENDING_UPDATES =
			new java.util.concurrent.ConcurrentHashMap<>();

	public static void handleUpdateClient(RealmManagerClient cli, Packet packet) {
		final UpdatePacket updatePacket = (UpdatePacket) packet;
		final Player toUpdate = cli.getRealm().getPlayer((updatePacket.getPlayerId()));
		if (toUpdate != null) {
			toUpdate.applyUpdate(updatePacket, cli.getState());
			PENDING_UPDATES.remove(updatePacket.getPlayerId());
		} else {
			final Enemy enemyToUpdate = cli.getRealm().getEnemy((updatePacket.getPlayerId()));
			if (enemyToUpdate != null) {
				enemyToUpdate.applyUpdate(updatePacket, cli.getState());
				log.debug("[CLIENT] Recieved update for enemy {}", updatePacket);
			} else {
				// Unknown id — buffer the packet so we can replay it the
				// moment LoadPacket adds the player. Cap at the most
				// recent packet per player so a stale entry can't grow
				// unbounded if a packet arrives for a player we'll
				// never see (e.g. they leave before LoadPacket adds them).
				PENDING_UPDATES.put(updatePacket.getPlayerId(), updatePacket);
			}
		}
	}

	/** Drain any buffered UpdatePacket for {@code playerId} now that the
	 *  player has been added to the realm. Called from
	 *  {@link #handleLoadClient} immediately after addPlayerIfNotExists. */
	private static void replayPendingUpdate(RealmManagerClient cli, long playerId) {
		final UpdatePacket pending = PENDING_UPDATES.remove(playerId);
		if (pending == null) return;
		final Player target = cli.getRealm().getPlayer(playerId);
		if (target == null) return;
		try {
			target.applyUpdate(pending, cli.getState());
			log.info("[CLIENT] Replayed buffered UpdatePacket for newly-loaded player {}", playerId);
		} catch (Exception e) {
			log.warn("[CLIENT] Buffered UpdatePacket replay failed for player {}: {}", playerId, e.getMessage());
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
				log.info("[CLIENT] login chatRole = '{}'", loginResponse.getChatRole());
				if (loginResponse.getChatRole() != null && !loginResponse.getChatRole().isEmpty()) {
					player.setChatRole(loginResponse.getChatRole());
					// Persist it so a local Player re-created on a realm
					// transition (which doesn't carry chatRole) keeps the
					// role-colored nameplate instead of reverting to off-white.
					cli.getState().setLocalChatRole(loginResponse.getChatRole());
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
					cli.getClient().sendRemote(LoginAckPacket.from());
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
