package com.openrealm.game;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.http.HttpClient;
import java.time.Instant;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.openrealm.account.dto.PingResponseDto;
import com.openrealm.account.service.OpenRealmClientDataService;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.net.client.ClientGameLogic;
import com.openrealm.net.client.SocketClient;

import lombok.extern.slf4j.Slf4j;

/**
 * Entry point for the OpenRealm native Java desktop client.
 *
 * Usage:
 *   java -jar openrealm-native-client.jar
 *   java -jar openrealm-native-client.jar &lt;data-service-host&gt;
 *   java -jar openrealm-native-client.jar &lt;data-service-host&gt; &lt;email&gt; &lt;password&gt; &lt;characterUuid&gt;
 *
 * The two-arg form opens the new in-game login screen (matching the web
 * client's flow). The four-arg form skips both login and char-select — used
 * by automated test launches and dev shortcuts.
 */
@Slf4j
public class GameLauncher {
    public static final String GAME_VERSION = "1.0.0";
    public static final Boolean DEBUG_MODE = true;

    public static void main(String[] args) {
        // Catch ANY startup error and dump it to ~/.openrealm/crash.log
        // (plus stderr if a console is attached). jpackage-built EXEs run
        // with no console by default, so an unhandled exception during
        // LWJGL native loading, font init, etc. would otherwise produce
        // a silent process exit and leave the user with nothing to debug.
        // We also pop a Swing dialog so the trace stays visible after the
        // jpackage console window auto-closes — that window dies the
        // moment the JVM exits, taking any stderr printout with it.
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            writeCrashLog("Uncaught in thread " + t.getName(), e);
            showCrashDialog("Uncaught in thread " + t.getName(), e);
        });
        try {
            launch(args);
        } catch (Throwable t) {
            writeCrashLog("Fatal in main()", t);
            showCrashDialog("Fatal in main()", t);
            System.exit(-1);
        }
    }

    private static void writeCrashLog(String header, Throwable t) {
        try {
            File dir = new File(System.getProperty("user.home", "."), ".openrealm");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "crash.log");
            try (PrintWriter pw = new PrintWriter(new FileWriter(f, true))) {
                pw.println("==== " + Instant.now() + " ====");
                pw.println(header);
                t.printStackTrace(pw);
                pw.println();
            }
        } catch (Exception ignored) { /* nothing else we can do */ }
        try { t.printStackTrace(System.err); } catch (Exception ignored) {}
        try { GameLauncher.log.error("Fatal: {}", t.toString(), t); } catch (Exception ignored) {}
    }

    /**
     * Pop a modal Swing dialog showing the stack trace + a "Copy" button.
     * Survives the jpackage console window auto-closing on exit — without
     * this, a crash inside the LibGDX render loop closes the console
     * before the user can read or copy anything. Synchronous so the JVM
     * doesn't exit until the user dismisses it.
     */
    private static void showCrashDialog(String header, Throwable t) {
        try {
            // No-op in headless / scripted launches.
            if (GraphicsEnvironment.isHeadless()) return;
            final StringWriter sw = new StringWriter();
            sw.append("OpenRealm Native Client crashed.\n\n");
            sw.append(header).append("\n\n");
            t.printStackTrace(new PrintWriter(sw));
            sw.append("\nFull crash log: ")
              .append(System.getProperty("user.home", "."))
              .append("/.openrealm/crash.log");
            final String msg = sw.toString();

            final JTextArea area = new JTextArea(msg, 20, 100);
            area.setEditable(false);
            area.setFont(new Font("Monospaced", Font.PLAIN, 11));
            area.setCaretPosition(0);
            final JScrollPane scroll = new JScrollPane(area);

            // Modal on the AWT event thread. invokeAndWait so the JVM
            // doesn't exit while the dialog is up.
            SwingUtilities.invokeAndWait(() -> {
                final JOptionPane pane = new JOptionPane(
                        scroll, JOptionPane.ERROR_MESSAGE,
                        JOptionPane.DEFAULT_OPTION, null,
                        new Object[] { "Copy to clipboard", "Close" });
                final JDialog dlg = pane.createDialog(null, "OpenRealm crash");
                dlg.setAlwaysOnTop(true);
                dlg.setVisible(true);
                final Object choice = pane.getValue();
                if ("Copy to clipboard".equals(choice)) {
                    try {
                        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                                new StringSelection(msg), null);
                    } catch (Exception ignored) {}
                }
                dlg.dispose();
            });
        } catch (Exception ignored) { /* dialog is best-effort */ }
    }

    private static void launch(String[] args) {
        GameLauncher.log.info("Starting OpenRealm Native Client v{}", GAME_VERSION);

        if (args.length == 0) {
            log.info("No data-service host provided. Defaulting to http://98.95.5.4");
            args = new String[] { "http://98.95.5.4" };
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

        // SocketClient.SERVER_ADDR is the GAME-server host (TCP target on
        // port 2222), NOT the data-service host. They're different
        // deployments. Leave SERVER_ADDR alone here — CharacterSelectState
        // owns it and writes it from the user's typed/preset game-server
        // value at Play time.
        // Headless / scripted launch path. Stash credentials on SocketClient
        // and let LoginState detect them and skip straight to PlayState.
        if (args.length > 3) {
            SocketClient.PLAYER_EMAIL = args[1];
            SocketClient.PLAYER_PASSWORD = args[2];
            SocketClient.CHARACTER_UUID = args[3];
            log.info("[CLIENT] CLI-supplied credentials detected — skipping login UI");
        }

        log.info("[CLIENT] Starting LibGDX game client...");
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("OpenRealm " + GAME_VERSION);
        config.setWindowedMode(1920, 1080);
        config.setResizable(true);
        config.useVsync(true);
        config.setForegroundFPS(144);
        // OS window / taskbar icon. setWindowIcon takes a list of sizes; LibGDX
        // picks the closest match per platform (Windows wants 16/32, macOS
        // dock prefers larger, Linux varies). One 300×300 PNG is enough — the
        // backend downscales — and ships at the jar root after Maven shade.
        try {
            config.setWindowIcon(Files.FileType.Classpath, "icon_min.png");
        } catch (Exception e) {
            log.warn("[CLIENT] Could not set window icon: {}", e.getMessage());
        }

        new Lwjgl3Application(new OpenRealmGame(), config);
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
}
