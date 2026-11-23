package ru.fridorin.translator.cache;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.fridorin.translator.TranslatorModClient;
import ru.fridorin.translator.config.TranslatorConfigManager;
import ru.fridorin.translator.service.SkyblockDictionary;
import ru.fridorin.translator.service.TranslationScope;
import ru.fridorin.translator.util.AtomicFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class TranslationCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(TranslationCache.class);
    private static final Gson GSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final TypeToken<Map<String, Entry>> ENTRIES_TYPE = new TypeToken<>() { };
    private static final int MAX_ENTRIES = 10_000;
    private static final int MAX_CHARACTERS = 8192;
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;

    // An escaped JSON character takes at most six bytes. The minimum weight also caps entry count.
    private static final long TTL_MILLIS = TimeUnit.DAYS.toMillis(7);
    private static final int ENTRY_OVERHEAD = 192;

    private record Entry(String text, String provider, TranslationScope scope, long expiresAt, boolean persistable) {
        boolean validAt(long now) {
            return expiresAt > now && expiresAt - now <= TTL_MILLIS;
        }
    }

    private static final Cache<String, Entry> CACHE = CacheBuilder.newBuilder()
        .expireAfterWrite(7, TimeUnit.DAYS)
        .maximumWeight(MAX_FILE_BYTES - 2)
        .weigher((String key, Entry value) -> Math.max(MAX_FILE_BYTES / MAX_ENTRIES + 1,
                (key.length() + value.text().length()) * 6 + ENTRY_OVERHEAD))
        .build();
    private static final Object CACHE_LOCK = new Object();
    private static final Object STORAGE_LOCK = new Object();
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "SkyblockTranslator-CacheSaver");
        thread.setDaemon(true);
        return thread;
    });
    private static Path cacheFile;
    private static String activeLanguage = "ru";
    private static long clearGeneration;
    private static ScheduledFuture<?> pendingSaveTask;
    private static boolean dirty;

    public static void loadForLanguage(String language) {
        String normalized = TranslatorConfigManager.normalizeLanguage(language);
        Path directory = FabricLoader.getInstance().getConfigDir().resolve("skyblock_translator");
        loadForLanguage(directory, normalized);
    }

    static void loadForLanguage(Path directory, String language) {
        synchronized (STORAGE_LOCK) {
            Path file = directory.resolve("cache_v2_" + language + ".json");
            // Block writes to the previous cache while saving its final snapshot.
            long originalClearGeneration;
            Snapshot previous;
            synchronized (CACHE_LOCK) {
                previous = takeSnapshot();
                CACHE.invalidateAll();
                activeLanguage = language;
                cacheFile = file;
                originalClearGeneration = clearGeneration;
                TranslatorModClient.clearComponentCache();
            }
            persist(previous);

            Map<String, Entry> loaded = readCache(file, language);
            synchronized (CACHE_LOCK) {
                if (originalClearGeneration != clearGeneration) return;
                var config = TranslatorConfigManager.getConfig();
                loaded.forEach((key, entry) -> {
                    if (entry.scope().mayPersist(config)) CACHE.asMap().putIfAbsent(key, entry);
                    else dirty = true;
                });
                if (dirty) scheduleSaveLocked();
            }
        }
    }

    public static String get(String text, TranslationScope scope) {
        if (text == null) return null;
        var config = TranslatorConfigManager.getConfig();
        if (!scope.allowsOnline(config)) return null;
        String key = cacheKey(text, config.apiProvider, scope);
        synchronized (CACHE_LOCK) {
            if (!activeLanguage.equals(config.targetLanguage)) return null;
            Entry entry = CACHE.getIfPresent(key);
            if (entry == null) return null;
            if (entry.validAt(System.currentTimeMillis())) return entry.text();
            CACHE.invalidate(key);
            return null;
        }
    }

    public static void put(String original, String translated, String language, long generation,
                           String provider, TranslationScope scope) {
        if (original == null || translated == null || language == null) return;
        String value = translated.trim();
        if (!validEntry(original.strip(), value, language)) return;
        var config = TranslatorConfigManager.getConfig();
        synchronized (CACHE_LOCK) {
            if (!activeLanguage.equals(language) || !language.equals(config.targetLanguage)
                    || !scope.allowsOnline(config)
                    || generation != TranslatorModClient.getCacheGeneration()) return;
            String key = cacheKey(original, provider, scope);
            boolean persistable = scope.mayPersist(config);
            CACHE.put(key, new Entry(value, provider, scope, System.currentTimeMillis() + TTL_MILLIS, persistable));
            if (persistable) {
                dirty = true;
                scheduleSaveLocked();
            }
        }
    }

    private static String cacheKey(String text, String provider, TranslationScope scope) {
        return provider + ":" + scope.name() + ":" + text.strip().toLowerCase(Locale.ROOT);
    }

    public static void refreshPersistence() {
        synchronized (CACHE_LOCK) {
            var config = TranslatorConfigManager.getConfig();
            if (config.persistChatCache || cacheFile == null) return;
            boolean removed = CACHE.asMap().values().removeIf(entry -> entry.persistable() && !entry.scope().mayPersist(config));
            if (removed) {
                dirty = true;
                scheduleSaveLocked();
            }
        }
    }

    private static boolean validEntry(String key, String value, String language) {
        return !key.isEmpty() && !value.isEmpty() && key.length() <= MAX_CHARACTERS
            && value.length() <= MAX_CHARACTERS && !key.equalsIgnoreCase(value)
            && (!"ru".equals(language) || !SkyblockDictionary.isHybrid(value));
    }

    private static Map<String, Entry> readCache(Path file, String language) {
        if (!Files.exists(file)) return Map.of();
        Map<String, Entry> result = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        try {
            Map<String, Entry> stored = GSON.fromJson(AtomicFiles.read(file, MAX_FILE_BYTES), ENTRIES_TYPE.getType());
            if (stored != null) {
                for (Map.Entry<String, Entry> pair : stored.entrySet()) {
                    if (result.size() >= MAX_ENTRIES) break;
                    String key = pair.getKey();
                    Entry entry = pair.getValue();
                    if (key == null || entry == null || entry.scope() == null || entry.provider() == null
                            || entry.text() == null || !entry.persistable() || !entry.validAt(now)) continue;
                    String prefix = entry.provider() + ":" + entry.scope().name() + ":";
                    if (key.startsWith(prefix) && prefix.length() < 64
                            && validEntry(key.substring(prefix.length()), entry.text(), language)) {
                        result.put(key, entry);
                    }
                }
            }
        } catch (IOException | JsonParseException error) {
            LOGGER.warn("Could not load the translation cache");
        }
        return result;
    }

    private record Snapshot(Path file, Map<String, Entry> entries) { }

    private static Snapshot takeSnapshot() {
        if (!dirty || cacheFile == null) {
            dirty = false;
            return null;
        }
        dirty = false;
        Map<String, Entry> entries = new LinkedHashMap<>();
        var config = TranslatorConfigManager.getConfig();
        long now = System.currentTimeMillis();
        CACHE.asMap().forEach((key, entry) -> {
            if (entry.persistable() && entry.scope().mayPersist(config) && entry.validAt(now)) entries.put(key, entry);
        });
        return new Snapshot(cacheFile, entries);
    }

    private static boolean persist(Snapshot snapshot) {
        if (snapshot == null) return true;
        try {
            AtomicFiles.write(snapshot.file(), GSON.toJson(snapshot.entries()), MAX_FILE_BYTES);
            return true;
        } catch (IOException error) {
            LOGGER.warn("Could not save the translation cache");
            return false;
        }
    }

    private static void save() {
        synchronized (STORAGE_LOCK) {
            Snapshot snapshot;
            synchronized (CACHE_LOCK) {
                pendingSaveTask = null;
                snapshot = takeSnapshot();
            }
            if (!persist(snapshot)) {
                synchronized (CACHE_LOCK) {
                    dirty = true;
                }
            }
        }
    }

    private static void scheduleSaveLocked() {
        if (scheduler.isShutdown() || pendingSaveTask != null) return;
        pendingSaveTask = scheduler.schedule(TranslationCache::save, 5, TimeUnit.SECONDS);
    }

    public static void shutdown() {
        synchronized (CACHE_LOCK) {
            if (pendingSaveTask != null) pendingSaveTask.cancel(false);
            scheduler.shutdown();
        }
        save();
    }

    public static void clear() {
        synchronized (CACHE_LOCK) {
            TranslatorModClient.clearComponentCache();
            CACHE.invalidateAll();
            clearGeneration++;
            dirty = true;
            scheduleSaveLocked();
        }
    }
}
