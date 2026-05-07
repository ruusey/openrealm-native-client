package com.openrealm.game;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import com.badlogic.gdx.Input;

/**
 * Persisted user settings for the native client (graphics, controls, audio).
 *
 * Mirrors the web client's `or_settings` localStorage structure so options
 * roughly translate between platforms — though the native client only has
 * keyboard/mouse, so mobile-only fields are absent.
 *
 * Stored at `~/.openrealm/settings.json` and read once at startup. The options
 * menu writes through this on close. Defaults are populated whenever a field
 * is missing from the on-disk file (so adding new options is non-breaking).
 */
@Data
@Slf4j
public class Settings {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.ALWAYS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static Settings INSTANCE;

    public static Settings get() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    /** Graphics settings. Field names mirror the web client where possible. */
    private boolean hideOtherPlayerBullets = false;
    private boolean showDamageNumbers = true;
    private boolean showPlayerNames = true;
    private boolean showRealmTransition = true;
    /** "low", "med", "high" — currently advisory only. */
    private String renderQuality = "high";
    /** -1 = unlimited, otherwise a cap on rendered enemy bullets. */
    private int maxBulletsOnScreen = -1;

    /** Version the user clicked "Skip this version" on in the update prompt.
     *  Null/empty means show the prompt for any newer release. */
    private String skipUpdateVersion = null;

    /** Audio sliders, 0.0–1.0. No audio engine is wired yet, but the values persist. */
    private float masterVolume = 0.7f;
    private float sfxVolume = 0.8f;
    private float musicVolume = 0.5f;

    /**
     * Keyboard bindings. Values are LibGDX `Input.Keys.*` integer codes.
     * Stored as a map so future bindings can be added without renaming fields.
     */
    private Map<String, Integer> keybinds = defaultKeybinds();

    private static Map<String, Integer> defaultKeybinds() {
        Map<String, Integer> m = new HashMap<>();
        m.put("moveUp", Input.Keys.W);
        m.put("moveDown", Input.Keys.S);
        m.put("moveLeft", Input.Keys.A);
        m.put("moveRight", Input.Keys.D);
        m.put("autofire", Input.Keys.I);
        m.put("inventory", Input.Keys.R);
        m.put("hpPotion", Input.Keys.Z);
        m.put("mpPotion", Input.Keys.X);
        m.put("rotateLeft", Input.Keys.Q);
        m.put("rotateRight", Input.Keys.E);
        m.put("resetCamera", Input.Keys.C);
        m.put("lootPickup", Input.Keys.F);
        m.put("chat", Input.Keys.ENTER);
        m.put("menu", Input.Keys.ESCAPE);
        return m;
    }

    public int getKeybind(String action) {
        Integer code = this.keybinds.get(action);
        if (code != null) return code;
        // Fall back to the default for newly-introduced actions so settings
        // saved before the action existed don't strand the user without a key.
        Integer def = defaultKeybinds().get(action);
        return def != null ? def : -1;
    }

    public void setKeybind(String action, int keyCode) {
        this.keybinds.put(action, keyCode);
    }

    private static File settingsFile() {
        String home = System.getProperty("user.home", ".");
        File dir = new File(home, ".openrealm");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "settings.json");
    }

    public static Settings load() {
        File f = settingsFile();
        if (!f.exists()) {
            log.info("No settings file at {} — using defaults", f.getAbsolutePath());
            return new Settings();
        }
        try {
            Settings loaded = MAPPER.readValue(f, Settings.class);
            // Merge any default keybinds the user's file is missing.
            for (Map.Entry<String, Integer> def : defaultKeybinds().entrySet()) {
                loaded.keybinds.putIfAbsent(def.getKey(), def.getValue());
            }
            return loaded;
        } catch (IOException e) {
            log.warn("Failed to read settings from {}: {} — using defaults", f.getAbsolutePath(), e.getMessage());
            return new Settings();
        }
    }

    public void save() {
        File f = settingsFile();
        try {
            MAPPER.writeValue(f, this);
            log.debug("Settings saved to {}", f.getAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to save settings to {}: {}", f.getAbsolutePath(), e.getMessage());
        }
    }
}
