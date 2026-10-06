package com.sao.fakeserver.table;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sao.fakeserver.config.SaoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 抽卡奖池：tables/gacha-pool.json，手改后重启假服。
 * 加载时剔除怪物/NPC 武将（index≥1000 或无合成碎片）；金币池不允许 type=hero。
 */
@Component
@DependsOn("cultivateTables")
public class GachaPool {
    private static final Logger log = LoggerFactory.getLogger(GachaPool.class);
    private static final String FILE = "gacha-pool.json";

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final SaoProperties props;
    private final CultivateTables cultivate;
    private FileData data = FileData.fallback();

    public GachaPool(SaoProperties props, CultivateTables cultivate) {
        this.props = props;
        this.cultivate = cultivate;
    }

    @PostConstruct
    public void init() {
        Path file = Paths.get(props.getTablesDir()).resolve(FILE);
        if (!Files.isRegularFile(file)) {
            log.warn("missing {}, using built-in pool", file);
            sanitizeLoaded();
            return;
        }
        try {
            FileData loaded = mapper.readValue(file.toFile(), FileData.class);
            if (loaded != null) {
                loaded.ensure();
                data = loaded;
            }
        } catch (IOException e) {
            log.warn("read {} failed: {}", file, e.toString());
        }
        sanitizeLoaded();
        log.info("gacha pool gold={} diamond={}", data.gold.size(), data.diamond.size());
    }

    /** 剔除非法英雄条目；金币池按规划只出碎片，去掉误配的 hero。 */
    private void sanitizeLoaded() {
        if (data == null) {
            data = FileData.fallback();
        }
        int stripped = 0;
        if (data.gold != null) {
            List<Entry> keep = new ArrayList<Entry>();
            for (Entry e : data.gold) {
                if (e != null && e.isHero()) {
                    stripped++;
                    continue;
                }
                if (e != null) {
                    keep.add(e);
                }
            }
            data.gold = keep;
        }
        if (data.diamond != null) {
            List<Entry> keep = new ArrayList<Entry>();
            for (Entry e : data.diamond) {
                if (e != null && e.isHero() && !cultivate.isPlayableHero(e.heroIndex)) {
                    log.warn("gacha strip non-playable hero index={}", e.heroIndex);
                    stripped++;
                    continue;
                }
                if (e != null) {
                    keep.add(e);
                }
            }
            data.diamond = keep;
        }
        data.ensure();
        if (stripped > 0) {
            log.info("gacha sanitized stripped={} gold={} diamond={}", stripped, data.gold.size(), data.diamond.size());
        }
    }

    public Entry roll(boolean diamond, Random rng) {
        List<Entry> pool = diamond ? data.diamond : data.gold;
        int total = 0;
        for (Entry e : pool) {
            total += Math.max(0, e.weight);
        }
        if (total <= 0) {
            return Entry.item("SP048", 1);
        }
        int tick = rng.nextInt(total);
        int acc = 0;
        for (Entry e : pool) {
            acc += Math.max(0, e.weight);
            if (tick < acc) {
                return e;
            }
        }
        return pool.get(pool.size() - 1);
    }

    public int goldSize() {
        return data.gold == null ? 0 : data.gold.size();
    }

    public int diamondSize() {
        return data.diamond == null ? 0 : data.diamond.size();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FileData {
        public List<Entry> gold = new ArrayList<Entry>();
        public List<Entry> diamond = new ArrayList<Entry>();

        static FileData fallback() {
            FileData d = new FileData();
            // 金币池：仅碎片；钻石池：可玩英雄示例（正式以 gacha-pool.json 为准）
            d.gold.add(Entry.item("SP048", 1));
            d.diamond.add(Entry.equip("EQ0023"));
            d.diamond.add(Entry.hero(28, "GOODS110"));
            return d;
        }

        void ensure() {
            if (gold == null || gold.isEmpty()) {
                gold = fallback().gold;
            }
            if (diamond == null || diamond.isEmpty()) {
                diamond = fallback().diamond;
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {
        /** item / hero / equip */
        public String type = "item";
        public String ori = "";
        public int count = 1;
        public int heroIndex;
        /** 已拥有时转碎片的道具名；数量由 CultivateTables 按合成初始星级折算，不写在池里。 */
        public String fragmentOri = "SP048";
        public int weight = 1;

        public boolean isHero() {
            return "hero".equals(type) && heroIndex > 0;
        }

        public boolean isEquip() {
            return "equip".equals(type) && ori != null && !ori.isEmpty();
        }

        static Entry item(String ori, int count) {
            Entry e = new Entry();
            e.type = "item";
            e.ori = ori;
            e.count = count;
            e.weight = 50;
            return e;
        }

        static Entry hero(int index, String frag) {
            Entry e = new Entry();
            e.type = "hero";
            e.heroIndex = index;
            e.fragmentOri = frag;
            e.weight = 5;
            return e;
        }

        static Entry equip(String ori) {
            Entry e = new Entry();
            e.type = "equip";
            e.ori = ori;
            e.count = 1;
            e.weight = 5;
            return e;
        }
    }
}
