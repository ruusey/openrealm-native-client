package com.openrealm.game.update;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openrealm.game.Settings;

import lombok.extern.slf4j.Slf4j;

/**
 * Checks the GitHub releases page for a newer client build and, if the user
 * agrees, downloads and launches the Windows installer.
 *
 * Flow:
 *   1. GET /repos/{owner}/{repo}/releases/latest (unauth — 60 req/hr is plenty).
 *   2. Compare tag_name vs current GAME_VERSION, ignoring a leading "v".
 *   3. If newer and not skipped, prompt the user with release notes.
 *   4. On accept: download the .exe asset to %TEMP% with a progress bar,
 *      Runtime.exec() it, then System.exit(0) so the running EXE can be
 *      replaced.
 *
 * Network failures, missing assets, malformed tags, etc. are all swallowed —
 * the launcher must still start the game when GitHub is unreachable.
 */
@Slf4j
public final class UpdateChecker {

    private static final String OWNER = "ruusey";
    private static final String REPO = "openrealm-native-client";
    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest";

    private UpdateChecker() {}

    /**
     * Synchronous check + prompt + (optional) install. Returns only after the
     * user dismisses any dialog; if they accept the update, this method calls
     * {@link System#exit(int)} after launching the installer and never returns.
     *
     * Safe to call before LibGDX init — uses Swing only, no GL context.
     */
    public static void checkAndMaybeUpdate(String currentVersion) {
        if (GraphicsEnvironment.isHeadless()) return;
        if (currentVersion == null || currentVersion.isBlank() || "dev".equals(currentVersion)) {
            log.info("[UPDATE] Skipping update check — running from dev build (version={})", currentVersion);
            return;
        }
        try {
            ReleaseInfo latest = fetchLatestRelease();
            if (latest == null) return;
            if (compareSemver(latest.version, currentVersion) <= 0) {
                log.info("[UPDATE] Already on latest version {} (remote={})", currentVersion, latest.version);
                return;
            }
            String skip = Settings.get().getSkipUpdateVersion();
            if (skip != null && skip.equals(latest.version)) {
                log.info("[UPDATE] User previously chose to skip {}", latest.version);
                return;
            }
            log.info("[UPDATE] New version available: {} (current {})", latest.version, currentVersion);
            promptAndInstall(currentVersion, latest);
        } catch (Throwable t) {
            log.warn("[UPDATE] Update check failed: {}", t.toString());
        }
    }

    private static ReleaseInfo fetchLatestRelease() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(LATEST_RELEASE_URL))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "OpenRealm-Native-Client")
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.warn("[UPDATE] GitHub releases returned HTTP {}", resp.statusCode());
            return null;
        }
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(resp.body());

        String tag = textOrNull(root, "tag_name");
        if (tag == null) return null;
        String version = stripLeadingV(tag);

        JsonNode assets = root.path("assets");
        String exeUrl = null;
        long exeSize = 0;
        String exeName = null;
        if (assets.isArray()) {
            for (JsonNode a : assets) {
                String name = textOrNull(a, "name");
                if (name == null) continue;
                if (name.toLowerCase(Locale.ROOT).endsWith(".exe")) {
                    exeUrl = textOrNull(a, "browser_download_url");
                    exeSize = a.path("size").asLong(0);
                    exeName = name;
                    break;
                }
            }
        }
        if (exeUrl == null) {
            log.info("[UPDATE] Release {} has no .exe asset — nothing to install", tag);
            return null;
        }
        ReleaseInfo info = new ReleaseInfo();
        info.tagName = tag;
        info.version = version;
        info.notes = textOrNull(root, "body");
        info.htmlUrl = textOrNull(root, "html_url");
        info.assetName = exeName;
        info.assetUrl = exeUrl;
        info.assetSize = exeSize;
        return info;
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static void promptAndInstall(String currentVersion, ReleaseInfo latest) {
        final int[] choice = {1};
        try {
            SwingUtilities.invokeAndWait(() -> choice[0] = showPromptDialog(currentVersion, latest));
        } catch (Exception e) {
            log.warn("[UPDATE] Prompt dialog failed: {}", e.toString());
            return;
        }
        switch (choice[0]) {
            case 0: // Update now
                downloadAndLaunch(latest);
                break;
            case 1: // Remind later
                log.info("[UPDATE] User chose to be reminded later");
                break;
            case 2: // Skip this version
                Settings s = Settings.get();
                s.setSkipUpdateVersion(latest.version);
                s.save();
                log.info("[UPDATE] User chose to skip version {}", latest.version);
                break;
            default:
                break;
        }
    }

    /** @return 0 update, 1 remind later, 2 skip. */
    private static int showPromptDialog(String currentVersion, ReleaseInfo latest) {
        JTextArea notes = new JTextArea(latest.notes == null ? "(no release notes)" : latest.notes);
        notes.setEditable(false);
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        notes.setFont(new Font("SansSerif", Font.PLAIN, 12));
        notes.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(notes);
        scroll.setPreferredSize(new Dimension(560, 260));

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel("OpenRealm " + latest.version + " is available. You're on " + currentVersion + "."), BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);

        Object[] options = { "Update now", "Remind me later", "Skip this version" };
        return JOptionPane.showOptionDialog(null, panel, "OpenRealm Update Available",
                JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null,
                options, options[0]);
    }

    private static void downloadAndLaunch(ReleaseInfo latest) {
        File tmpDir = new File(System.getProperty("java.io.tmpdir"));
        File out = new File(tmpDir, "OpenRealm-" + latest.version + "-installer.exe");

        ProgressDialog dlg = new ProgressDialog("Downloading OpenRealm " + latest.version);
        DownloadWorker worker = new DownloadWorker(latest, out, dlg);
        worker.execute();
        dlg.setVisible(true); // modal, blocks until worker.done() disposes

        if (worker.failure != null) {
            log.error("[UPDATE] Download failed: {}", worker.failure.toString());
            JOptionPane.showMessageDialog(null,
                    "Update download failed:\n" + worker.failure.getMessage()
                            + "\n\nYou can download manually from:\n" + latest.htmlUrl,
                    "Update failed", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!out.isFile() || out.length() == 0) {
            log.error("[UPDATE] Download produced empty file at {}", out);
            return;
        }

        try {
            log.info("[UPDATE] Launching installer at {}", out.getAbsolutePath());
            new ProcessBuilder(out.getAbsolutePath())
                    .inheritIO()
                    .start();
            // Quit so the installer can replace the running EXE. The installer
            // process is detached and survives this exit.
            System.exit(0);
        } catch (Exception e) {
            log.error("[UPDATE] Failed to launch installer: {}", e.toString());
            JOptionPane.showMessageDialog(null,
                    "Could not launch the installer:\n" + e.getMessage()
                            + "\n\nThe download is at:\n" + out.getAbsolutePath(),
                    "Update failed", JOptionPane.WARNING_MESSAGE);
        }
    }

    private static int compareSemver(String a, String b) {
        int[] av = parseVersion(a);
        int[] bv = parseVersion(b);
        int n = Math.max(av.length, bv.length);
        for (int i = 0; i < n; i++) {
            int x = i < av.length ? av[i] : 0;
            int y = i < bv.length ? bv[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int[] parseVersion(String v) {
        String s = stripLeadingV(v);
        int dash = s.indexOf('-');
        if (dash >= 0) s = s.substring(0, dash);
        String[] parts = s.split("\\.");
        List<Integer> nums = new ArrayList<>();
        for (String p : parts) {
            try { nums.add(Integer.parseInt(p)); } catch (NumberFormatException nfe) { nums.add(0); }
        }
        int[] out = new int[nums.size()];
        for (int i = 0; i < out.length; i++) out[i] = nums.get(i);
        return out;
    }

    private static String stripLeadingV(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("v") || s.startsWith("V")) return s.substring(1);
        return s;
    }

    private static class ReleaseInfo {
        String tagName;
        String version;
        String notes;
        String htmlUrl;
        String assetName;
        String assetUrl;
        long assetSize;
    }

    /** Modal dialog that shows download progress. Disposed by the worker on done(). */
    private static class ProgressDialog extends JDialog {
        final JProgressBar bar = new JProgressBar(0, 100);
        final JLabel status = new JLabel("Connecting...");

        ProgressDialog(String title) {
            super((java.awt.Frame) null, title, true);
            setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            JPanel p = new JPanel(new BorderLayout(8, 8));
            p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            bar.setStringPainted(true);
            bar.setPreferredSize(new Dimension(420, 24));
            p.add(status, BorderLayout.NORTH);
            p.add(bar, BorderLayout.CENTER);
            JButton cancel = new JButton("Cancel");
            cancel.addActionListener(e -> { cancelled = true; dispose(); });
            p.add(cancel, BorderLayout.SOUTH);
            setContentPane(p);
            pack();
            setLocationRelativeTo(null);
            setAlwaysOnTop(true);
        }

        volatile boolean cancelled = false;
    }

    private static class DownloadWorker extends SwingWorker<Void, Integer> {
        final ReleaseInfo release;
        final File out;
        final ProgressDialog dlg;
        Throwable failure;

        DownloadWorker(ReleaseInfo release, File out, ProgressDialog dlg) {
            this.release = release;
            this.out = out;
            this.dlg = dlg;
        }

        @Override
        protected Void doInBackground() {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.ALWAYS)
                        .build();
                HttpRequest req = HttpRequest.newBuilder(URI.create(release.assetUrl))
                        .header("User-Agent", "OpenRealm-Native-Client")
                        .GET()
                        .build();
                HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() != 200) {
                    throw new RuntimeException("HTTP " + resp.statusCode());
                }
                long total = release.assetSize > 0 ? release.assetSize
                        : resp.headers().firstValueAsLong("Content-Length").orElse(-1);

                if (out.exists()) Files.delete(out.toPath());
                long done = 0;
                byte[] buf = new byte[64 * 1024];
                try (InputStream in = resp.body();
                     FileOutputStream fos = new FileOutputStream(out)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (dlg.cancelled) {
                            log.info("[UPDATE] Download cancelled by user");
                            return null;
                        }
                        fos.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) Math.min(100, (done * 100L) / total);
                            publish(pct);
                        }
                    }
                }
            } catch (Throwable t) {
                this.failure = t;
            }
            return null;
        }

        @Override
        protected void process(List<Integer> chunks) {
            int last = chunks.get(chunks.size() - 1);
            dlg.bar.setValue(last);
            dlg.status.setText("Downloading... " + last + "%");
        }

        @Override
        protected void done() {
            dlg.dispose();
        }
    }
}
