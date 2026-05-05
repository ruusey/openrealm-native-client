package com.openrealm.game;

import java.net.http.HttpClient;

import javax.swing.JOptionPane;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openrealm.account.dto.PingResponseDto;
import com.openrealm.account.dto.PlayerAccountDto;
import com.openrealm.account.service.OpenRealmClientDataService;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.net.client.ClientGameLogic;
import com.openrealm.net.client.SocketClient;

import lombok.extern.slf4j.Slf4j;

/**
 * Entry point for the OpenRealm native Java desktop client.
 *
 * Usage:
 *   java -jar openrealm-native-client.jar <data-service-host>
 *   java -jar openrealm-native-client.jar <data-service-host> <email> <password> <characterUuid>
 *
 * The first form opens a Swing login dialog and a character picker. The
 * second form skips both (used by automated test launches and dev shortcuts).
 */
@Slf4j
public class GameLauncher {
    public static final String GAME_VERSION = "1.0.0";
    public static final Boolean DEBUG_MODE = true;

    public static void main(String[] args) {
        GameLauncher.log.info("Starting OpenRealm Native Client v{}", GAME_VERSION);

        if (args.length == 0) {
            log.info("No data-service host provided. Defaulting to 127.0.0.1");
            args = new String[] { "127.0.0.1" };
        }

        // Allow legacy "-client <host>" invocations to keep working — strip the
        // mode flag so the rest of the argument layout matches the new form.
        if ("-client".equals(args[0])) {
            String[] tail = new String[args.length - 1];
            System.arraycopy(args, 1, tail, 0, tail.length);
            args = tail;
        }
        // -server / -embedded are no longer supported in the native client; the
        // game server lives in the openrealm/ repo. Reject those modes loudly so
        // a stale shell script doesn't silently start nothing.
        if (args.length > 0 && ("-server".equals(args[0]) || "-embedded".equals(args[0]))) {
            log.error("'{}' mode is not supported by the native client. Run the game server from the openrealm/ repo instead.", args[0]);
            System.exit(-1);
        }

        final String addr = args[0];
        final String dataServiceUrl;
        if (addr.startsWith("http://") || addr.startsWith("https://")) {
            dataServiceUrl = addr.endsWith("/") ? addr : addr + "/";
        } else {
            dataServiceUrl = "http://" + addr + "/";
        }

        ClientGameLogic.DATA_SERVICE = new OpenRealmClientDataService(HttpClient.newHttpClient(),
                dataServiceUrl, null);
        pingClient();
        GameDataManager.loadGameData(true);
        startClient(args);
    }

    private static void pingClient() {
        try {
            PingResponseDto dataServerOnline = ClientGameLogic.DATA_SERVICE.executeGet("ping", null,
                    PingResponseDto.class);
            GameLauncher.log.info("Data server online. Response: {}", dataServerOnline);
        } catch (Exception e) {
            GameLauncher.log.error("FATAL. Unable to reach data server at {}. Reason: {}",
                    ClientGameLogic.DATA_SERVICE.getBaseUrl(), e.getMessage());
            System.exit(-1);
        }
    }

    private static void startClient(String[] args) {
        SocketClient.SERVER_ADDR = args[0];
        boolean skipLogin = false;
        if (args.length > 3) {
            SocketClient.PLAYER_EMAIL = args[1];
            SocketClient.PLAYER_PASSWORD = args[2];
            SocketClient.CHARACTER_UUID = args[3];
            skipLogin = true;
        }

        // Simple Swing login dialog before LibGDX takes over the main thread
        if (!skipLogin) {
            try {
                String email = JOptionPane.showInputDialog(null, "Email:", "OpenRealm Login", JOptionPane.PLAIN_MESSAGE);
                if (email == null || email.isBlank()) {
                    log.error("[CLIENT] Login cancelled");
                    System.exit(0);
                }
                String password = JOptionPane.showInputDialog(null, "Password:", "OpenRealm Login", JOptionPane.PLAIN_MESSAGE);
                if (password == null || password.isBlank()) {
                    log.error("[CLIENT] Login cancelled");
                    System.exit(0);
                }
                SocketClient.PLAYER_EMAIL = email;
                SocketClient.PLAYER_PASSWORD = password;

                final ObjectNode loginRequest = new ObjectNode(JsonNodeFactory.instance);
                loginRequest.put("email", email);
                loginRequest.put("password", password);

                final ObjectNode response = ClientGameLogic.DATA_SERVICE.executePost("admin/account/login",
                        loginRequest, ObjectNode.class);
                ClientGameLogic.DATA_SERVICE.setSessionToken(response.get("token").asText());
                final PlayerAccountDto account = ClientGameLogic.DATA_SERVICE.executeGet(
                        "/data/account/" + response.get("accountGuid").asText(), null, PlayerAccountDto.class);

                // Build character selection list
                String[] charOptions = account.getCharacters().stream()
                        .map(c -> c.getCharacterClass() + " [" + c.getCharacterUuid() + "]")
                        .toArray(String[]::new);

                if (charOptions.length == 0) {
                    JOptionPane.showMessageDialog(null, "No characters found on this account.");
                    System.exit(0);
                }

                String selected = (String) JOptionPane.showInputDialog(null, "Select Character:", "OpenRealm",
                        JOptionPane.PLAIN_MESSAGE, null, charOptions, charOptions[0]);
                if (selected == null) {
                    System.exit(0);
                }
                int idx = selected.indexOf("[");
                SocketClient.CHARACTER_UUID = selected.substring(idx + 1, selected.lastIndexOf("]"));
                log.info("[CLIENT] Chose characterUuid={}", SocketClient.CHARACTER_UUID);
            } catch (Exception e) {
                log.error("[CLIENT] Failed to perform login. Reason: {}", e.getMessage());
                JOptionPane.showMessageDialog(null, e.getMessage());
                System.exit(-1);
            }
        }

        log.info("[CLIENT] Starting LibGDX game client...");

        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("OpenRealm " + GAME_VERSION);
        config.setWindowedMode(1920, 1080);
        config.setResizable(true);
        config.useVsync(true);
        config.setForegroundFPS(144);

        new Lwjgl3Application(new OpenRealmGame(), config);
    }
}
