package com.wan.downloadmanager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.repository.JpaRepository;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Writes and reads back one fully-populated row of every entity: proves the Flyway schema and the
 * JPA mappings agree (column names, types, CHECK constraints for enums) on real SQLite.
 */
class RepositoryRoundTripTest extends BackendTestBase {
    @Autowired ApplicationContext ctx;

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void everyEntityRoundTrips() throws Exception {
        String[] order = {"DownloadCategory", "DownloadQueue", "Download", "DownloadPart", "DownloadHeader", "QueueItem",
                "MediaSource", "MediaFormat", "BrowserSession", "HistoryEntry", "Schedule", "SpeedStatistic", "ConversionJob",
                "EntitlementCacheEntry", "AccountCacheEntry", "DeviceRegistration", "AppEvent"};
        String downloadId = null, queueId = null, mediaId = null;
        for (String name : order) {
            Class<?> type = Class.forName("com.wan.downloadmanager.entity." + name);
            Object entity = type.getDeclaredConstructor().newInstance();
            String id = UUID.randomUUID().toString();
            for (Field f : type.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Class<?> t = f.getType();
                Object v;
                if (t == String.class) v = switch (f.getName()) {
                    case "id", "sub", "installationId" -> id;
                    case "downloadId" -> downloadId;
                    case "queueId" -> queueId;
                    case "mediaSourceId" -> mediaId;
                    case "categoryId" -> "cat-video";
                    case "browser" -> name.equals("BrowserSession") ? "CHROME" : "x";
                    case "startTime", "endTime" -> "09:30";
                    default -> f.getName() + "-" + id.substring(0, 6);
                };
                else if (t == long.class || t == Long.class) v = 42L + (f.getName().equals("bucketAt") ? System.nanoTime() : 0);
                else if (t == int.class || t == Integer.class) v = f.getName().equals("maxConcurrent") || f.getName().equals("connections") ? 2 : 7;
                else if (t == double.class || t == Double.class) v = 0.5;
                else if (t == boolean.class || t == Boolean.class) v = true;
                else if (t.isEnum()) v = t.getEnumConstants()[0];
                else throw new IllegalStateException("unhandled " + t);
                f.set(entity, v);
            }
            String repoBean = Character.toLowerCase(name.charAt(0)) + name.substring(1) + "Repository";
            JpaRepository repo = (JpaRepository) ctx.getBean(repoBean);
            repo.saveAndFlush(entity);
            assertThat(((java.util.Optional<?>) repo.findById(id)).isPresent()).as(name).isTrue();
            if (name.equals("Download")) downloadId = id;
            if (name.equals("DownloadQueue")) queueId = id;
            if (name.equals("MediaSource")) mediaId = id;
        }
    }
}
