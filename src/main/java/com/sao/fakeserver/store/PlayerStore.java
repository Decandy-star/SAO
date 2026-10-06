package com.sao.fakeserver.store;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sao.fakeserver.config.SaoProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@Component
public class PlayerStore {
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Path dir;
    private final Map<String, PlayerRecord> cache = new ConcurrentHashMap<>();
    /** 竞技场把 1–1000000 当机器人，真人 playerId 必须大于这个区间。 */
    private final AtomicInteger idSeq = new AtomicInteger(2000000);

    public PlayerStore(SaoProperties props) throws IOException {
        this.dir = Paths.get(props.getDataDir()).toAbsolutePath();
        Files.createDirectories(this.dir);
        try (Stream<Path> stream = Files.list(this.dir)) {
            stream.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                try {
                    PlayerRecord rec = mapper.readValue(p.toFile(), PlayerRecord.class);
                    rec.ensureCollections();
                    boolean bumped = false;
                    if (rec.playerId > 0 && rec.playerId <= 1000000) {
                        rec.playerId = idSeq.getAndIncrement();
                        bumped = true;
                    }
                    cache.put(rec.account, rec);
                    idSeq.updateAndGet(v -> Math.max(v, rec.playerId + 1));
                    if (bumped) {
                        save(rec);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("load " + p, e);
                }
            });
        }
    }

    public PlayerRecord get(String account) {
        return cache.get(account);
    }

    public Collection<PlayerRecord> all() {
        return cache.values();
    }

    /** 按 playerId 查档；无则 null。 */
    public PlayerRecord findByPlayerId(int playerId) {
        if (playerId <= 0) {
            return null;
        }
        for (PlayerRecord r : cache.values()) {
            if (r != null && r.playerId == playerId) {
                return r;
            }
        }
        return null;
    }

    public PlayerRecord findByRoleName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (PlayerRecord r : cache.values()) {
            if (r != null && name.equals(r.roleName)) {
                return r;
            }
        }
        return null;
    }

    public int nextPlayerId() {
        return idSeq.getAndIncrement();
    }

    public synchronized void save(PlayerRecord rec) {
        rec.ensureCollections();
        rec.note = PlayerRecord.NOTE;
        cache.put(rec.account, rec);
        Path file = dir.resolve(safeName(rec.account) + ".json");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), rec);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** 清空内存缓存与 data/players/*.json，playerId 序列回到 2000001 起。 */
    public synchronized int clearAll() {
        int n = cache.size();
        cache.clear();
        idSeq.set(2000000);
        try {
            if (Files.isDirectory(dir)) {
                try (Stream<Path> stream = Files.list(dir)) {
                    stream.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new RuntimeException("delete " + p, e);
                        }
                    });
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return n;
    }

    /** 删除单个账号（内存 + json）；不存在返回 false。 */
    public synchronized boolean delete(String account) {
        if (account == null || account.isEmpty()) {
            return false;
        }
        PlayerRecord removed = cache.remove(account);
        Path file = dir.resolve(safeName(account) + ".json");
        boolean hadFile = Files.isRegularFile(file);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new RuntimeException("delete " + file, e);
        }
        return removed != null || hadFile;
    }

    private static String safeName(String account) {
        return account.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
