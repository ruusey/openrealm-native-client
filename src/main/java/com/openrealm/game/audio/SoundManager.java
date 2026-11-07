package com.openrealm.game.audio;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.files.FileHandle;
import com.fasterxml.jackson.databind.JsonNode;
import com.openrealm.game.Settings;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.model.ability.Ability;
import com.openrealm.net.client.ClientGameLogic;

/**
 * Data-driven sound effects for the native client, mirroring the Godot client's
 * Sfx autoload: sounds.json (served alongside the other game data) maps weapon
 * archetypes, ability names/tags and events to ogg files, and this preloads every
 * referenced file. The client owns only the trigger points and the volume gate.
 */
public final class SoundManager {

    private static final Logger log = LoggerFactory.getLogger(SoundManager.class);
    private static final String LOG_NS = "[SOUND]";

    /** WeaponArchetype id -> the `attack` key it sounds as. Fixed by the enum, not data (mirrors Godot). */
    private static final Map<Integer, String> ARCHETYPE_NAMES = new HashMap<>();
    static {
        ARCHETYPE_NAMES.put(1, "sword");  ARCHETYPE_NAMES.put(2, "axe");    ARCHETYPE_NAMES.put(3, "hammer");
        ARCHETYPE_NAMES.put(10, "dagger"); ARCHETYPE_NAMES.put(11, "bow");  ARCHETYPE_NAMES.put(12, "throwing_knife");
        ARCHETYPE_NAMES.put(20, "tome");   ARCHETYPE_NAMES.put(21, "staff"); ARCHETYPE_NAMES.put(22, "wand");
    }

    private static final Map<String, Sound> SOUNDS = new HashMap<>();
    private static Map<String, String> attackMap = new HashMap<>();
    private static Map<String, String> abilityByName = new HashMap<>();
    private static Map<String, String> abilityByTag = new HashMap<>();
    private static Map<String, String> eventMap = new HashMap<>();
    private static String abilityDefault = "";
    private static boolean enabled = false;

    private SoundManager() {
    }

    /** Fetches sounds.json and preloads every ogg it references. Must run on the render
     *  thread (Gdx.audio ready) -- call it beside the sprite load in create(). */
    public static void load(final boolean remote) {
        try {
            final String text = fetchText("sounds.json", remote);
            if (text == null || text.isBlank()) {
                log.warn("{} no sounds.json found, SFX disabled", LOG_NS);
                return;
            }
            final JsonNode root = GameDataManager.JSON_MAPPER.readTree(text);
            enabled = !root.has("enabled") || root.get("enabled").asBoolean(true);
            attackMap = readStringMap(root.get("attack"));
            abilityByName = readStringMap(root.get("abilityByName"));
            abilityByTag = readStringMap(root.get("abilityByTag"));
            eventMap = readStringMap(root.get("event"));
            abilityDefault = root.hasNonNull("abilityDefault") ? root.get("abilityDefault").asText() : "";

            final Set<String> files = new HashSet<>();
            files.addAll(attackMap.values());
            files.addAll(abilityByName.values());
            files.addAll(abilityByTag.values());
            files.addAll(eventMap.values());
            if (!abilityDefault.isEmpty()) {
                files.add(abilityDefault);
            }
            for (final String file : files) {
                preload(file, remote);
            }
            log.info("{} loaded {} sounds (enabled={})", LOG_NS, SOUNDS.size(), enabled);
        } catch (final Exception e) {
            log.error("{} failed to load sounds: {}", LOG_NS, e.getMessage());
        }
    }

    /** The local player's basic attack, by the archetype of the weapon in hand. */
    public static void playAttack(final int archetypeId) {
        play(attackMap.getOrDefault(ARCHETYPE_NAMES.getOrDefault(archetypeId, "default"), attackMap.get("default")));
    }

    /** An ability cast, resolved from the ability's name, then its tags, then the default. */
    public static void playAbility(final Ability ability) {
        if (ability == null) {
            play(abilityDefault);
            return;
        }
        String file = abilityByName.get(ability.getName());
        if (file == null && ability.getTags() != null) {
            for (final String tag : ability.getTags()) {
                file = abilityByTag.get(tag);
                if (file != null) {
                    break;
                }
            }
        }
        play(file == null ? abilityDefault : file);
    }

    /** A named event: "enemy_hit", "player_hurt", "item_use", ... */
    public static void playEvent(final String key) {
        play(eventMap.get(key));
    }

    public static void dispose() {
        for (final Sound sound : SOUNDS.values()) {
            try {
                sound.dispose();
            } catch (final Exception ignored) {
            }
        }
        SOUNDS.clear();
    }

    private static void play(final String filename) {
        if (!enabled || filename == null || filename.isEmpty() || Gdx.audio == null) {
            return;
        }
        final Sound sound = SOUNDS.get(filename);
        if (sound == null) {
            return;
        }
        final float volume = volume();
        if (volume <= 0f) {
            return;
        }
        try {
            sound.play(volume);
        } catch (final Exception ignored) {
        }
    }

    private static float volume() {
        final Settings settings = Settings.get();
        if (settings == null) {
            return 0f;
        }
        final float volume = settings.getMasterVolume() * settings.getSfxVolume();
        return Math.max(0f, Math.min(1f, volume));
    }

    // LibGDX only builds a Sound from a FileHandle, so each ogg is cached to local storage first.
    private static void preload(final String filename, final boolean remote) {
        if (filename == null || filename.isEmpty() || SOUNDS.containsKey(filename)) {
            return;
        }
        try {
            final byte[] bytes = fetchBytes(filename, remote);
            if (bytes == null || bytes.length == 0) {
                return;
            }
            final FileHandle cache = Gdx.files.local("sfx-cache/" + filename);
            cache.parent().mkdirs();
            cache.writeBytes(bytes, false);
            SOUNDS.put(filename, Gdx.audio.newSound(cache));
        } catch (final Exception e) {
            log.warn("{} could not load sound {}: {}", LOG_NS, filename, e.getMessage());
        }
    }

    private static Map<String, String> readStringMap(final JsonNode node) {
        final Map<String, String> out = new HashMap<>();
        if (node != null && node.isObject()) {
            final Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                final Map.Entry<String, JsonNode> entry = fields.next();
                out.put(entry.getKey(), entry.getValue().asText());
            }
        }
        return out;
    }

    private static String fetchText(final String file, final boolean remote) throws Exception {
        if (remote) {
            return ClientGameLogic.DATA_SERVICE.executeGet("game-data/" + file, null);
        }
        final InputStream is = SoundManager.class.getClassLoader().getResourceAsStream("data/" + file);
        return is == null ? null : new String(readAllBytes(is), StandardCharsets.UTF_8);
    }

    private static byte[] fetchBytes(final String file, final boolean remote) throws Exception {
        if (remote) {
            final URL url = new URL(ClientGameLogic.DATA_SERVICE.getBaseUrl() + "game-data/" + file);
            try (InputStream is = url.openStream()) {
                return readAllBytes(is);
            }
        }
        final InputStream is = SoundManager.class.getClassLoader().getResourceAsStream("audio/" + file);
        return is == null ? null : readAllBytes(is);
    }

    private static byte[] readAllBytes(final InputStream is) throws Exception {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int read;
        while ((read = is.read(buffer)) != -1) {
            bos.write(buffer, 0, read);
        }
        is.close();
        return bos.toByteArray();
    }
}
