package ru.fridorin.translator.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TranslationCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(TranslationCache.class);
    private static final Gson GSON = new GsonBuilder().create();
    private static File cacheFile;
    private static final int MAX_SIZE = 10000;

    private static final com.google.common.cache.Cache<String, String> lruCache = com.google.common.cache.CacheBuilder.newBuilder()
        .maximumSize(MAX_SIZE)
        .build();

    private static final Object SCHEDULER_LOCK = new Object();
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SkyblockTranslator-CacheSaver");
        t.setDaemon(true);
        return t;
    });
    private static ScheduledFuture<?> pendingSaveTask = null;
    private static volatile boolean isDirty = false;

    public static void loadForLanguage(String lang) {
        synchronized (SCHEDULER_LOCK) {
            if (isDirty) {
                saveLocked();
            }
            lruCache.invalidateAll();
            
            String filename = "cache_" + lang + ".json";
            cacheFile = FabricLoader.getInstance().getConfigDir().resolve("skyblock_translator/" + filename).toFile();
            
            if ("ru".equals(lang)) {
                File legacyFile = FabricLoader.getInstance().getConfigDir().resolve("skyblock_translator/cache.json").toFile();
                if (legacyFile.exists() && !cacheFile.exists()) {
                    legacyFile.renameTo(cacheFile);
                }
            }
            
            load();
        }
    }

    public static void init() {
        String lang = ru.fridorin.translator.config.TranslatorConfigManager.getConfig().targetLanguage;
        loadForLanguage(lang);
    }

    public static String get(String text) {
        if (text == null) return null;
        String lower = text.trim().toLowerCase();
        return lruCache.getIfPresent(lower);
    }

    public static void put(String original, String translated) {
        if (original == null || translated == null) return;
        String trimmedOrig = original.trim();
        String trimmedTrans = translated.trim();
        if (trimmedOrig.isEmpty() || trimmedTrans.isEmpty() || trimmedOrig.equalsIgnoreCase(trimmedTrans)) return;
        
        String targetLang = ru.fridorin.translator.config.TranslatorConfigManager.getConfig().targetLanguage;
        if (!"en".equalsIgnoreCase(targetLang) && ru.fridorin.translator.service.SkyblockDictionary.isHybrid(trimmedTrans)) {
            return;
        }

        synchronized (SCHEDULER_LOCK) {
            lruCache.put(trimmedOrig.toLowerCase(), trimmedTrans);
            isDirty = true;
        }
        scheduleSave();
    }

    public static void load() {
        if (cacheFile == null || !cacheFile.exists()) {
            return;
        }
        try (java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(cacheFile.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
            Type type = new TypeToken<Map<String, String>>(){}.getType();
            Map<String, String> loaded = GSON.fromJson(reader, type);
            if (loaded != null) {
                String targetLang = ru.fridorin.translator.config.TranslatorConfigManager.getConfig().targetLanguage;
                boolean checkHybrid = !"en".equalsIgnoreCase(targetLang);
                int count = 0;
                for (Map.Entry<String, String> entry : loaded.entrySet()) {
                    if (count >= MAX_SIZE) {
                        isDirty = true;
                        break;
                    }
                    String key = entry.getKey();
                    String val = entry.getValue();
                    if (key == null || val == null) continue;
                    if (checkHybrid && ru.fridorin.translator.service.SkyblockDictionary.isHybrid(val)) {
                        isDirty = true;
                        continue;
                    }
                    lruCache.put(key.toLowerCase(), val);
                    count++;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load translation cache", e);
        }
    }

    private static void save() {
        synchronized (SCHEDULER_LOCK) {
            saveLocked();
        }
    }

    private static void saveLocked() {
        if (!isDirty || cacheFile == null) return;
        Map<String, String> snapshot = new java.util.LinkedHashMap<>(lruCache.asMap());
        isDirty = false;
        try {
            File parent = cacheFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            File tempFile = new File(cacheFile.getParentFile(), cacheFile.getName() + ".tmp");
            try (java.io.BufferedWriter writer = java.nio.file.Files.newBufferedWriter(tempFile.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                GSON.toJson(snapshot, writer);
            }
            java.nio.file.Files.move(tempFile.toPath(), cacheFile.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.error("Failed to save translation cache", e);
        }
    }

    public static void scheduleSave() {
        if (!isDirty || scheduler.isShutdown()) return;
        synchronized (SCHEDULER_LOCK) {
            if (pendingSaveTask != null && !pendingSaveTask.isDone()) {
                pendingSaveTask.cancel(false);
            }
            pendingSaveTask = scheduler.schedule(TranslationCache::save, 5, TimeUnit.SECONDS);
        }
    }

    public static void shutdown() {
        synchronized (SCHEDULER_LOCK) {
            if (pendingSaveTask != null) {
                pendingSaveTask.cancel(false);
            }
        }
        save();
        scheduler.shutdown();
    }

    public static void clear() {
        lruCache.invalidateAll();
        isDirty = true;
        save();
    }
}

