package com.openrealm.net.client;

import com.openrealm.account.service.OpenRealmClientDataService;
import com.openrealm.game.GameLauncher;
import com.openrealm.game.Settings;
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
import com.openrealm.game.ui.FameStoreEntry;
import com.openrealm.game.ui.ExchangeMarketWindow;
import com.openrealm.game.ui.FameStoreWindow;
import com.openrealm.game.ui.ForgeWindow;
import com.openrealm.game.ui.PotionStorageWindow;
import com.openrealm.net.client.packet.OpenExchangeMarketPacket;
import com.openrealm.net.client.packet.OpenFameStorePacket;
import com.openrealm.net.client.packet.OpenForgePacket;
import com.openrealm.net.client.packet.OpenItemStorePacket;
import com.openrealm.net.client.packet.ItemStoreUpdatePacket;
import com.openrealm.net.client.packet.PlayerStatePacket;
import com.openrealm.net.client.packet.AbilityCastStartPacket;
import com.openrealm.net.client.packet.PartyUpdatePacket;
import com.openrealm.net.client.packet.SkillsPacket;
import com.openrealm.net.client.packet.QuestStatePacket;
import com.openrealm.game.model.QuestSnapshot;
import com.openrealm.net.entity.NetGameItem;
import com.openrealm.net.entity.NetPartyMember;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.ui.PerfMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class ClientGameLogic {
	public static OpenRealmClientDataService DATA_SERVICE = null;
	public static boolean GAME_OVER = false;
	// Status labels (PLAYER_INFO) render one lane above damage numbers; same-lane bursts stack.
	private static final float TEXT_INFO_LANE_OFFSET = 20.0f;
	private static final float TEXT_STACK_STEP = 14.0f;
	private static final int TEXT_MAX_STACK = 4;
	private static final float TEXT_STACK_FRESH_DISTANCE = 25.0f;
	
	
	@PacketHandlerClient(RequestTradePacket.class)
	public static void handleTradeRequestClient(RealmManagerClient cli, Packet packet) {
		final RequestTradePacket tradeRequest = (RequestTradePacket) packet;
		final String fromName = tradeRequest.getRequestingPlayerName();
		cli.getState().getPui().setPendingTradeRequestFrom(fromName);
		cli.getState().getPui().setPendingTradeRequestStartMs(System.currentTimeMillis());
		cli.getState().getPui().getPlayerChat().addChatMessage(TextPacket.create(fromName,
				cli.getState().getPlayer().getName(),
				fromName + " wants to trade - Accept / Decline"));
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
			// Server builds the packet as player0=self, player1=partner per recipient;
			// selection-update packets carry only flags, so this inventory snapshot is
			// the sole source of partner contents during the trade.
			pui.setTradePartnerName(tradeRequest.getPlayer1().getName());
			pui.setPartnerClassId(tradeRequest.getPlayer1().getClassId());
			pui.setPartnerDyeId(tradeRequest.getPlayer1().getDyeId());
			pui.setPartnerInventory(buildPartnerInventory(tradeRequest));
		} else {
			log.info("[CLIENT] Trade closed");
			final var pui = cli.getState().getPui();
			pui.setPendingTradeRequestFrom(null); // close popup if open
			// Defer overlay close 1s so both CONFIRMED badges stay visible; if we
			// weren't actually trading (refused before accept), close immediately.
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

	// Empty / itemId<=0 slots stay null so the overlay renders an empty cell.
	private static GameItem[] buildPartnerInventory(
			AcceptTradeRequestPacket pkt) {
		final NetGameItem[] src = pkt.getPlayer1Inv();
		if (src == null) return new GameItem[0];
		final GameItem[] out =
				new GameItem[src.length];
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
			// Hide ally effects: drop OTHER players' casts; own casts + enemy telegraphs (ownerId 0) stay.
			if (Settings.get().isHideAllyEffects() && effectPacket.getOwnerId() != 0
					&& effectPacket.getOwnerId() != cli.getCurrentPlayerId()) {
				return;
			}
			cli.getState().getActiveEffects().add(ActiveVisualEffect.from(effectPacket));
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to handle CreateEffect Packet. Reason: {}", e);
		}
	}

	@PacketHandlerClient(AbilityCastStartPacket.class)
	public static void handleAbilityCastStartClient(RealmManagerClient cli, Packet packet) {
		final AbilityCastStartPacket cast =
				(AbilityCastStartPacket) packet;
		try {
			if (cli.getState() == null) return;
			// [startEpochMs, durationMs] so the renderer can compute progress and auto-clear.
			cli.getState().getActiveCasts().put(cast.getPlayerId(),
					new long[] { System.currentTimeMillis(), cast.getDurationMs() });
			// Local caster already poses in PlayState; only drive remotes here.
			if (cast.getPlayerId() != cli.getCurrentPlayerId()) {
				final Player caster = cli.getRealm().getPlayer(cast.getPlayerId());
				if (caster != null) {
					final float dx = cast.getWorldTargetX() - (caster.getPos().x + caster.getSize() / 2f);
					final float dy = cast.getWorldTargetY() - (caster.getPos().y + caster.getSize() / 2f);
					if ((cast.getWorldTargetX() != 0f || cast.getWorldTargetY() != 0f)
							&& (Math.abs(dx) > 0.001f || Math.abs(dy) > 0.001f)) {
						caster.triggerAttackAnimation((float) Math.atan2(dx, dy));
					} else {
						caster.triggerAttackAnimation(0f);
					}
				}
			}
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

				final TextEffect fx = TextEffect.from(textEffect.getTextEffectId());
				final boolean infoLane = fx == TextEffect.PLAYER_INFO;
				// Same entity == same Vector2f instance (getPos()), so compare by reference.
				int stack = 0;
				for (final EffectText t : cli.getState().getDamageText()) {
					if (t.getSourcePos() == targetPos
							&& ((t.getEffect() == TextEffect.PLAYER_INFO) == infoLane)
							&& t.getAnimationDistance() > TEXT_STACK_FRESH_DISTANCE) {
						stack++;
					}
				}
				final float laneOffset = (infoLane ? TEXT_INFO_LANE_OFFSET : 0f)
						+ Math.min(stack, TEXT_MAX_STACK) * TEXT_STACK_STEP;
				final EffectText hitText = EffectText.builder().damage(textEffect.getText())
						.effect(fx).sourcePos(targetPos).laneOffset(laneOffset).build();
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
			// LoadMap fires on every tile-stream chunk, not just transitions; only
			// treat it as a transition when the realm or map id actually changes.
			final long prevRealmId = cli.getRealm().getRealmId();
			final long prevMapId   = cli.getRealm().getMapId();
			final boolean realmChanged = (prevRealmId != loadPacket.getRealmId())
					|| (prevMapId != loadPacket.getMapId());
			// prevRealmId 0L == initial session connect. The cross-realm entity wipe
			// below must NOT fire here: it races the server's initial LoadPacket and
			// erases just-received entities -> 1-2s empty map.
			final boolean isInitialConnect = (prevRealmId == 0L);
			// Reset tiles on any realm/map id change OR a client-initiated transition
			// that rebuilt TileManager (nested dungeon reusing parent's ids).
			final boolean mapGridReset = realmChanged || cli.getRealm().isTileGridRebuilt();
			cli.getRealm().setTileGridRebuilt(false);

			// Set realm state BEFORE minimap init: a UI throw must not leave the id stale.
			cli.getRealm().setRealmId(loadPacket.getRealmId());
			cli.getRealm().setMapId(loadPacket.getMapId());
			cli.getRealm().setDungeonId((int) loadPacket.getDungeonId());
			cli.getState().getPui().getMinimap().initializeMap((int) loadPacket.getMapId(),
					(int) loadPacket.getDungeonId());
			// Zero tile layers + fog on transition so a nested dungeon doesn't inherit prior tiles.
			if (mapGridReset) {
				cli.getRealm().getTileManager().resetTiles((int) loadPacket.getMapId(), (int) loadPacket.getDungeonId());
			}
			cli.getRealm().getTileManager().mergeMap(loadPacket);

			if (realmChanged) {
				String zoneName = "Map " + loadPacket.getMapId();
				float diff = 0f;
				try { diff = cli.getRealm().getDifficulty(); } catch (Exception ignored) {}
				cli.getState().getPui().getRealmTransition().trigger(zoneName, diff);
				try { cli.getState().getPui().getPlayerChat().clearChat(); } catch (Exception ignored) {}
				// Cross-realm carry-over wipe. Skipped on initial connect (see above).
				if (!isInitialConnect) {
					try {
						final long localId = cli.getCurrentPlayerId();
						final Map<Long, Player> players = cli.getRealm().getPlayers();
						if (players != null) {
							players.entrySet().removeIf(e -> e.getKey() != localId);
						}
						final Map<Long, Enemy> enemies = cli.getRealm().getEnemies();
						if (enemies != null) enemies.clear();
						final Map<Long, Bullet> bullets = cli.getRealm().getBullets();
						if (bullets != null) bullets.clear();
						final Map<Long, LootContainer> loot = cli.getRealm().getLoot();
						if (loot != null) loot.clear();
						final Map<Long, Portal> portals = cli.getRealm().getPortals();
						if (portals != null) portals.clear();
						// Drop buffered UpdatePackets so a same-id player doesn't replay a stale one.
						PENDING_UPDATES.clear();
					} catch (Exception ignored) { /* defensive - never block transition */ }
				}

				// Visual placeholder until the server's authoritative pos arrives, so we
				// don't render at the prior realm's coords inside the new tile mesh.
				try {
					final Player local = cli.getRealm().getPlayer(cli.getCurrentPlayerId());
					final MapModel mapModel = GameDataManager.MAPS.get((int) loadPacket.getMapId());
					if (local != null && local.getPos() != null && mapModel != null) {
						// getRandomSpawnPoint (not center - center is void on edge-spawn maps).
						final Vector2f spawn = mapModel.getRandomSpawnPoint();
						local.getPos().x = spawn.x;
						local.getPos().y = spawn.y;
						// Must also reset the lerped render pos: minimap reads renderX, not pos.x.
						// NaN makes getEffectiveRenderX fall back to the freshly-set spawn pos.
						local.setRenderPos(Float.NaN, Float.NaN);
						cli.getState().resetInterpAnchor(spawn.x, spawn.y);
						// Drop stale prediction inputs; a fresh map doesn't share prior physics.
						cli.getState().clearPendingInputs();
					}
				} catch (Exception ignored) { /* best effort - server pos will follow */ }
			}
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to handle LoadMap Packet. Reason: {}", e);
		}
	}

	// Server's authoritative position for the LOCAL player. Reconciles predicted
	// client position that has drifted ahead of the server.
	@PacketHandlerClient(PlayerPosAckPacket.class)
	public static void handlePlayerPosAckClient(RealmManagerClient cli, Packet packet) {
		try {
			final PlayerPosAckPacket ack = (PlayerPosAckPacket) packet;
			// Route through the rollback-prediction reconciler, not a hard snap: it
			// drops confirmed inputs, replays unacked ones, and absorbs small drift.
			// A direct snap rubber-banded the player back by (latency x speed) per tick.
			if (cli.getState() != null) {
				cli.getState().reconcileLocalPlayerPos(ack.getSeq(), ack.getPosX(), ack.getPosY());
			}
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed PlayerPosAck handler. Reason: {}", e.getMessage());
		}
	}

	// Server-wide player positions for the minimap only.
	@PacketHandlerClient(GlobalPlayerPositionPacket.class)
	public static void handleGlobalPlayerPositionClient(RealmManagerClient cli, Packet packet) {
		try {
			final GlobalPlayerPositionPacket gp = (GlobalPlayerPositionPacket) packet;
			if (gp.getPlayers() == null) return;
			// Minimap-only. Do NOT overwrite realm player positions - those are driven
			// by ObjectMovePacket / PlayerPosAckPacket; this carries cross-realm coords.
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
			forge.setPlayState(cli.getState());
			forge.show();
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to handle OpenForge Packet. Reason: {}", e);
		}
	}

	@PacketHandlerClient(OpenItemStorePacket.class)
	public static void handleOpenItemStoreClient(RealmManagerClient cli, Packet packet) {
		try {
			if (cli.getState() == null || cli.getState().getPui() == null) return;
			final OpenItemStorePacket open = (OpenItemStorePacket) packet;
			final PotionStorageWindow win = cli.getState().getPui().getPotionStorageWindow();
			win.setRealmManager(cli);
			win.setPlayState(cli.getState());
			win.open(open.getStoreKind(), open.getItems());
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to handle OpenItemStore Packet. Reason: {}", e);
		}
	}

	@PacketHandlerClient(SkillsPacket.class)
	public static void handleSkillsClient(RealmManagerClient cli, Packet packet) {
		try {
			if (cli.getState() == null) return;
			final SkillsPacket skills = (SkillsPacket) packet;
			cli.getState().setSkillXp(skills.asArray());
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to handle Skills Packet. Reason: {}", e);
		}
	}

	@PacketHandlerClient(QuestStatePacket.class)
	public static void handleQuestStateClient(RealmManagerClient cli, Packet packet) {
		try {
			if (cli.getState() == null) return;
			final QuestStatePacket quests = (QuestStatePacket) packet;
			final QuestSnapshot snapshot = GameDataManager.JSON_MAPPER.readValue(
					quests.getJson() == null ? "{}" : quests.getJson(), QuestSnapshot.class);
			cli.getState().setQuestStars(quests.getStars());
			cli.getState().setQuests(snapshot.getQuests());
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to handle QuestState Packet. Reason: {}", e);
		}
	}

	@PacketHandlerClient(PartyUpdatePacket.class)
	public static void handlePartyUpdateClient(RealmManagerClient cli, Packet packet) {
		try {
			if (cli.getState() == null) return;
			final PartyUpdatePacket upd =
					(PartyUpdatePacket) packet;
			cli.getState().setPartyId(upd.getPartyId());
			cli.getState().setPartyLeaderId(upd.getLeaderId());
			cli.getState().setPartyMembers(upd.getMembers() == null
					? new NetPartyMember[0] : upd.getMembers());
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to handle PartyUpdate Packet. Reason: {}", e);
		}
	}

	@PacketHandlerClient(ItemStoreUpdatePacket.class)
	public static void handleItemStoreUpdateClient(RealmManagerClient cli, Packet packet) {
		try {
			if (cli.getState() == null || cli.getState().getPui() == null) return;
			final ItemStoreUpdatePacket upd = (ItemStoreUpdatePacket) packet;
			cli.getState().getPui().getPotionStorageWindow().refresh(upd.getItems());
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to handle ItemStoreUpdate Packet. Reason: {}", e);
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
			// Catalog + prices from fame-store.json (itemId -> cost); names from GAME_ITEMS. Cheapest first.
			List<FameStoreEntry> entries = new ArrayList<>();
			final Map<Integer, Long> catalog = GameDataManager.FAME_STORE;
			if (catalog != null) {
				final List<Map.Entry<Integer, Long>> sorted = new ArrayList<>(catalog.entrySet());
				sorted.sort((a, b) -> {
					final int c = Long.compare(a.getValue(), b.getValue());
					return c != 0 ? c : Integer.compare(a.getKey(), b.getKey());
				});
				for (Map.Entry<Integer, Long> ce : sorted) {
					final GameItem item = GameDataManager.GAME_ITEMS != null
							? GameDataManager.GAME_ITEMS.get(ce.getKey()) : null;
					final String name = (item != null && item.getName() != null)
							? item.getName() : ("Item " + ce.getKey());
					final String desc = (item != null) ? item.getDescription() : null;
					entries.add(new FameStoreEntry(ce.getKey(), name, ce.getValue(), desc));
				}
			}
			store.setEntries(entries);
			store.show();
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to handle OpenFameStore Packet. Reason: {}", e);
		}
	}

	@PacketHandlerClient(OpenExchangeMarketPacket.class)
	public static void handleOpenExchangeMarketClient(RealmManagerClient cli, Packet packet) {
		try {
			if (cli.getState() == null || cli.getState().getPui() == null) return;
			final ExchangeMarketWindow win = cli.getState().getPui().getExchangeMarketWindow();
			win.setRealmManager(cli);
			win.setPlayerUi(cli.getState().getPui());
			win.show();
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to handle OpenExchangeMarket Packet. Reason: {}", e);
		}
	}

	// Angle window (~6.9 deg) for pairing a server-echoed bullet to a predicted one;
	// must be >= the MultiShot SPREAD or the center pellet fails to dedup.
	private static final float PREDICTED_ANGLE_TOLERANCE = 0.12f;
	// Position fallback (px^2) for unflagged ability projectiles (flags: []) that the
	// PLAYER_PROJECTILE-gated dedup misses, so an unrelated bullet can't dedup by angle alone.
	private static final float PREDICTED_POS_TOLERANCE_SQ = 96f * 96f;

	// Match by projectileId + angle; unflagged projectiles also require position proximity.
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
					// LoadPacket is the only place chatRole arrives (UpdatePacket omits it);
					// without copying it the local nameplate stays off-white.
					try {
						final Player localExisting = cli.getRealm().getPlayer(p.getId());
						if (localExisting != null) {
							if (p.getChatRole() != null && !p.getChatRole().isEmpty()) {
								localExisting.setChatRole(p.getChatRole());
							}
							// Refresh authoritative size (/size); rebuild bounds so collision matches.
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
				// Skip remote players at exactly (0,0): the server's uninitialized-Vector2f
				// sentinel from a join racing LoadPacket assembly. addPlayerIfNotExists would
				// otherwise pin them there for the session; the next LoadPacket has the real pos.
				if (p.getPos() != null
						&& p.getPos().x == 0f && p.getPos().y == 0f) {
					continue;
				}
				final boolean wasNew = cli.getRealm().getPlayer(p.getId()) == null;
				cli.getRealm().addPlayerIfNotExists(p);
				if (wasNew) {
					// Replay an UpdatePacket that arrived before LoadPacket added this player.
					replayPendingUpdate(cli, p.getId());
				}
				// addPlayerIfNotExists is a no-op for known ids, so refresh size/dye/chatRole here.
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
				// Short ID -> long ID mapping for CompactMovePacket.
				if (player.getShortId() != 0) {
					cli.getShortIdToLongId().put(player.getShortId(), player.getId());
				}
				if (wasNew) {
					final float lpx = p.getPos() == null ? Float.NaN : p.getPos().x;
					final float lpy = p.getPos() == null ? Float.NaN : p.getPos().y;
					log.info("[LOADPACKET] Added remote player id={} name={} loadPos=({}, {}) classId={} size={}",
							p.getId(), p.getName(), lpx, lpy, p.getClassId(), p.getSize());
				}
				// Sprite recovery: a prior add before ANIMATIONS loaded left spriteSheet=null.
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
					} else {
						current.setContentsChanged(true);
						current.setItems(lc.getItems());
					}

				} else {
					cli.getRealm().addLootContainerIfNotExists(lc);
				}
			}

			// Fast-forward a freshly-arrived bullet by ~RTT/2 along its angle so it
			// doesn't appear to spawn behind the shooter and catch up. 0 = no catchup.
			int oneWayMsForCatchup = 0;
			try {
				oneWayMsForCatchup = PerfMetrics.get().getPing();
			} catch (Exception ignored) { /* leave as 0 */ }
			final float catchupSec = Math.min(oneWayMsForCatchup / 1000f, 0.25f);
			final float catchupScale = catchupSec * 64f;

			for (final NetBullet bullet : loadPacket.getBullets()) {
				final Bullet b = bullet.asBullet();
				// If a predicted local bullet matches this server bullet, keep the
				// prediction and skip the duplicate echo.
				final Bullet match = findMatchingPredictedBullet(cli, b);
				if (match != null) {
					// Adopt the server's id so the UnloadPacket (keyed by server id) can
					// remove it; otherwise the predicted bullet outlives every cleanup.
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
				// Homing paths aren't deterministic: snap a tracked homing bullet to the
				// server pos/heading/target each snapshot, with NO straight-line catchup
				// (catchup below assumes straight motion and flings seekers onto random orbits).
				if (b.hasFlag(ProjectileFlag.HOMING)) {
					final Bullet tracked = cli.getRealm().getBullets().get(b.getId());
					if (tracked != null && tracked.getPos() != null && b.getPos() != null) {
						tracked.getPos().setX(b.getPos().x);
						tracked.getPos().setY(b.getPos().y);
						tracked.setAngle(b.getAngle());
						tracked.setTargetEntityId(b.getTargetEntityId());
					} else {
						// Hand the predicted seeker off to the server copy (its steered
						// angle won't match the dedup above).
						cli.getRealm().getBullets().values().removeIf(pb -> pb != null && pb.isPredicted()
								&& pb.getProjectileId() == b.getProjectileId()
								&& pb.hasFlag(ProjectileFlag.HOMING));
						cli.getRealm().addBulletIfNotExists(b);
					}
					continue;
				}
				// Another player's bullet: fast-forward by ~RTT/2. Skip orbital/parametric
				// (non-straight paths) and zero-magnitude (stationary effect) bullets.
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

				// Drive the shooter's firing pose. Grouped per volley by createdTime so a
				// multishot burst restarts the clip once; direction from the bullet angle.
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
				// For an already-tracked enemy, refresh dx/dy/health WITHOUT touching pos - 
				// the per-frame extrapolator walks pos toward target. Overwriting pos each
				// LoadPacket rubber-banded enemies back to spawn.
				final Enemy existing = cli.getRealm().getEnemy(enemy.getId());
				if (existing != null) {
					existing.setEnemyId(enemy.getEnemyId());
					existing.setWeaponId(enemy.getWeaponId());
					if (enemy.getSize() > 0) existing.setSize(enemy.getSize());
					existing.setDifficulty(enemy.getDifficulty());
					if (enemy.getMaxHealth() > 0) existing.setMaxHealth(enemy.getMaxHealth());
					if (enemy.getHealth() > 0) {
						existing.setHealth(enemy.getHealth());
						if (enemy.getMaxHealth() > 0) {
							existing.setHealthpercent(
									(float) enemy.getHealth() / (float) enemy.getMaxHealth());
						}
					}
					existing.refreshFromLoadPacket(
							enemy.getPos().x, enemy.getPos().y,
							enemy.getDX(), enemy.getDY());
				} else {
					final Enemy e = enemy.asEnemy();
					cli.getRealm().addEnemyIfNotExists(e);
				}
				// Short ID -> long ID mapping for CompactMovePacket.
				if (enemy.getShortId() != 0) {
					cli.getShortIdToLongId().put(enemy.getShortId(), enemy.getId());
				}
			}

			for (final NetPortal portal : loadPacket.getPortals()) {
				// MUST go through asPortal(): reflection field-copy skips sprite loading,
				// leaving sprite=null (blank portal).
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
				// Often a no-op: PlayState already de-renders peers leaving render range.
				cli.derenderRemotePlayer(p);
			}
			for (final Long lc : unloadPacket.getContainers()) {
				final LootContainer removed = cli.getRealm().getLoot().remove(lc);
				if (removed == null) {
					ClientGameLogic.log.error("[CLIENT] LootContainer {} does not exist", lc);
				}
			}
			for (final Long b : unloadPacket.getBullets()) {
				// Client-side culling usually removes bullets first, so a null here is normal.
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
				// Go through removeEnemy so spatialGrid + short-id state are cleaned and
				// onRemoved() drops the SpriteSheet (bulk of per-enemy heap retention).
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
			// Party invite hook - dispatch inviter name to PlayerUI's prompt overlay.
			if ("SYSTEM".equalsIgnoreCase(textPacket.getFrom())) {
				final String msg = textPacket.getMessage() == null ? "" : textPacket.getMessage();
				final int idx = msg.indexOf(" invited you to a party");
				if (idx > 0) {
					final String inviter = msg.substring(0, idx).trim();
					cli.getState().getPui().showPartyInvitePrompt(inviter);
				}
				// Admin /hop toggle result - flip local minimap click behavior.
				if (msg.startsWith("Hop mode: ")) {
					cli.getState().getPui().getMinimap().setHopMode(msg.endsWith("ON"));
				}
			}
			cli.getState().getPui().enqueueChat(textPacket.clone());
			// Float the line over the sender; skip SYSTEM / event broadcasts (no anchor).
			final String from = textPacket.getFrom();
			if (from != null && !"SYSTEM".equalsIgnoreCase(from) && !"EVENT_MARKER".equalsIgnoreCase(from)) {
				cli.getState().addChatBubble(from, textPacket.getMessage());
			}
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to handle text packet. Reason: {}", e.getMessage());
		}
	}
	
	// CommandPacket message type codes.
	private static final byte LOGIN_RESPONSE_MSG_CODE = 2;
	private static final byte SERVER_ERROR_MSG_CODE = 4;
	private static final byte PLAYER_ACCOUNT_MSG_CODE = 5;
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
						// Snap + re-anchor sub-tick interp after a portal transition.
						playerToUpdate.applyMovement(movement);
						if (cli.getState() != null) {
							cli.getState().resetInterpAnchor(movement.getPosX(), movement.getPosY());
						}
						cli.setAwaitingRealmTransition(false);
					}
					// Otherwise ignore: local player reconciles via PlayerPosAckPacket, the
					// only path that resets the interp anchor.
				} else {
					playerToUpdate.applyServerCorrection(movement);
				}
				break;
			case ENEMY:
				final Enemy enemyToUpdate = cli.getRealm().getEnemy(movement.getEntityId());
				if (enemyToUpdate == null) {
					break;
				}
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

	// CompactMovePacket: 2-byte short entity ids resolved via the LoadPacket mapping.
	public static void handleCompactMoveClient(RealmManagerClient cli, Packet packet) {
		final CompactMovePacket compactPacket = (CompactMovePacket) packet;
		for (NetCompactMovement cm : compactPacket.getMovements()) {
			final Long longId = cli.getShortIdToLongId().get(cm.getShortEntityId());
			if (longId == null) {
				continue; // Unknown short id - entity not yet loaded
			}
			final NetObjectMovement movement = new NetObjectMovement();
			movement.setEntityId(longId);
			movement.setPosX(cm.getPosX());
			movement.setPosY(cm.getPosY());
			movement.setVelX(cm.getVelX());
			movement.setVelY(cm.getVelY());

			// Enemy first (most common in combat), then player.
			final Enemy enemyToUpdate = cli.getRealm().getEnemy(longId);
			if (enemyToUpdate != null) {
				enemyToUpdate.applyServerCorrection(movement);
				continue;
			}
			final Player playerToUpdate = cli.getRealm().getPlayer(longId);
			if (playerToUpdate != null) {
				if (longId == cli.getCurrentPlayerId()) {
					// Self only arrives here on teleport (normal self-movement reconciles
					// via PlayerPosAckPacket); snap + re-anchor on realm transition.
					if (cli.isAwaitingRealmTransition()) {
						playerToUpdate.applyMovement(movement);
						if (cli.getState() != null) {
							cli.getState().resetInterpAnchor(movement.getPosX(), movement.getPosY());
						}
						cli.setAwaitingRealmTransition(false);
					}
				} else {
					playerToUpdate.applyServerCorrection(movement);
				}
			}
		}
	}

	// UpdatePackets for a player not yet added by LoadPacket; replayed on add.
	// The server's delta-check won't resend, so without this the player stays blank.
	private static final ConcurrentHashMap<Long, UpdatePacket> PENDING_UPDATES =
			new ConcurrentHashMap<>();

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
				// Unknown id - buffer (most-recent-per-player) for replay when LoadPacket adds them.
				PENDING_UPDATES.put(updatePacket.getPlayerId(), updatePacket);
			}
		}
	}

	// Drain a buffered UpdatePacket now that the player has been added.
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
				// LoadPacket only ships chatRole for REMOTE players, so set the local one here.
				log.info("[CLIENT] login chatRole = '{}'", loginResponse.getChatRole());
				if (loginResponse.getChatRole() != null && !loginResponse.getChatRole().isEmpty()) {
					player.setChatRole(loginResponse.getChatRole());
					// Persist so a Player re-created on realm transition keeps the role color.
					cli.getState().setLocalChatRole(loginResponse.getChatRole());
				}
				player.setSpriteSheet(GameSpriteManager.loadClassSprites(cls));
				ClientGameLogic.DATA_SERVICE.setSessionToken(loginResponse.getToken());
				cli.getState().setAccount(loginResponse.getAccount());
				cli.getState().loadClass(player, cls, true);
				cli.setCurrentPlayerId(player.getId());
				cli.getState().setPlayerId(player.getId());
				cli.startHeartbeatThread();
				// Tell the server we're ready to receive tiles.
				try {
					cli.getClient().sendRemote(LoginAckPacket.from());
				} catch (Exception ex) {
					log.error("[CLIENT] Failed to send LoginAck. Reason: {}", ex.getMessage());
				}
				final String serverVersion = loginResponse.getVersion() != null
						? loginResponse.getVersion() : GameLauncher.GAME_VERSION;
				final TextPacket packet = TextPacket.create("SYSTEM", "Player",
						"Welcome to OpenRealm Server " + serverVersion);
				cli.getState().getPui().enqueueChat(packet);
			}
		} catch (Exception e) {
			ClientGameLogic.log.error("[CLIENT] Failed to respond to login response. Reason: {}", e.getMessage());
		}
	}
}
