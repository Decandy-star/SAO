package com.sao.fakeserver.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sao.fakeserver.config.SaoProperties;
import com.sao.fakeserver.table.CultivateTables;
import com.sao.fakeserver.util.GameTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 签到当月英雄碎片：月初在三星及以上武将中随机一枚，全服当月共用。
 * <p>
 * 定档状态落盘 data/world/signin-month.json；一旦跨月 roll 出新碎片，
 * 立即把 tables/QianDao.txt 的 SP* 列刷成当月碎片（供客户端格子图标）。
 * 到账发奖（SignInService）读的是同一 json 的 fragmentOri，两处同源不脱节。
 */
@Component
public class SignInMonthStore {
    private static final Logger log = LoggerFactory.getLogger(SignInMonthStore.class);
    private static final int MIN_COMPOSE_STAR = 3;
    /**
     * 上游 `tables/QianDao.txt` 里碎片列的原样占位值（圣咏剑姬碎片）。
     * 刷新时若发现列里是别的碎片（≠ 当月），说明这张表被上个月刷过而没刷回来 → 打 warn。
     */
    private static final String DEFAULT_QIANDAO_FRAGMENT = "SP047";

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Path file;
    private final Path tablesDir;
    private final CultivateTables cultivate;
    /** 启动/每日调度是否自动 patch tables/QianDao.txt（测试里关掉，见 SaoProperties#signinPatchTable）。 */
    private final boolean autoPatchTable;
    private MonthPick pick = new MonthPick();

    public SignInMonthStore(SaoProperties props, CultivateTables cultivate) {
        this.file = Paths.get(props.getWorldDir()).toAbsolutePath().resolve("signin-month.json");
        this.tablesDir = Paths.get(props.getTablesDir()).toAbsolutePath();
        this.cultivate = cultivate;
        this.autoPatchTable = props.isSigninPatchTable();
    }

    @PostConstruct
    public void load() throws Exception {
        Files.createDirectories(file.getParent());
        if (Files.isRegularFile(file)) {
            pick = mapper.readValue(file.toFile(), MonthPick.class);
            if (pick == null) {
                pick = new MonthPick();
            }
        }
        // 启动即检查当月定档（跨月重启时在这里 roll，不等玩家访问），roll 完顺带刷表
        ensureCurrentMonth(autoPatchTable);
    }

    /**
     * 检查当月签到英雄碎片定档：当月已定则 no-op；跨月则 roll 全服共用碎片并刷 QianDao 表。
     * 启动 {@link javax.annotation.PostConstruct} 与公共调度每日 0 点调用。
     */
    public synchronized void ensureMonthlyPickForScheduler() {
        ensureCurrentMonth(autoPatchTable);
    }

    /** 当前月碎片 Ori（纯读取；定档由启动/公共调度保证）。 */
    public synchronized String fragmentOri() {
        return pick.fragmentOri == null || pick.fragmentOri.isEmpty() ? "SP048" : pick.fragmentOri;
    }

    public synchronized MonthPick current() {
        return pick;
    }

    /**
     * 一步定档并同步表：决定当月碎片并写 data/world/signin-month.json，
     * 再 patch tables/QianDao.txt 的 SP* 列（供客户端合并显示）。
     * <p>
     * 优先级：ori 指定 > force 重抽随机 > 沿用已定档。调用方随后可再触发 GameText 重建。
     *
     * @return 定档结果
     */
    public synchronized MonthPick resolveAndPatch(boolean force, String ori) {
        if (ori != null && !ori.trim().isEmpty()) {
            setPickOri(ori.trim());
        } else if (force) {
            pick.year = GameTime.today().getYear() - 1;
            pick.month = 1;
            // 末尾统一 patch，这里只走 roll
            ensureCurrentMonth(false);
        } else {
            // 沿用已定档（当月已定则 no-op）
            ensureCurrentMonth(false);
        }
        patchQianDaoSpColumns(fragmentOri());
        return pick;
    }

    /**
     * 强制重抽当月英雄碎片（全服共用）。
     * 落盘 data/world/signin-month.json；{@code patchQianDaoTable}=true 时
     * 同步改 tables/QianDao.txt 里 SP* 列（给合并 GameText 用）。
     */
    public synchronized MonthPick reroll(boolean patchQianDaoTable) {
        LocalDate today = GameTime.today();
        // 强制走进 ensure 的 roll 分支（随机重抽）
        pick.year = today.getYear() - 1;
        pick.month = 1;
        ensureCurrentMonth(patchQianDaoTable);
        return pick;
    }

    /** 直接指定当月碎片（写档）；与当前已定档相同则不动。 */
    private void setPickOri(String ori) {
        LocalDate today = GameTime.today();
        boolean monthSame = pick.year == today.getYear() && pick.month == today.getMonthValue();
        if (monthSame && ori.equals(pick.fragmentOri)) {
            return;
        }
        CultivateTables.HeroCfg h = cultivate.heroByFragment(ori);
        pick.year = today.getYear();
        pick.month = today.getMonthValue();
        pick.fragmentOri = ori;
        pick.heroIndex = h != null ? h.index : 0;
        save();
        log.info("signin-month set {}-{} frag={} heroIndex={}", pick.year, pick.month, ori, pick.heroIndex);
    }

    /**
     * 把 tables/QianDao.txt 里「签到碎片格」换成当月碎片 Ori（数量/钻/金币/VIP 列不动）。
     * <p>
     * ⚠️ 必须**幂等**：这张表的碎片列第一次被刷成具体碎片（如 GOODS102）后就不再带 `SP` 前缀，
     * 早期实现只认 `ori.startsWith("SP")` → 当月重刷（publish/reroll/跨月启动）什么都不做，
     * 表里留着**上个月**的碎片，客户端日历图标与当月实际到账脱节（2026-10 就踩了这条：
     * 表停在 9 月的 GOODS102，而 signin-month.json 已是 SP040/SP047）。
     * 现在按「商品是武将碎片（GoodsList 第 12 列 == 2）」识别，并在覆盖非默认值时打 warn。
     */
    public void patchQianDaoSpColumns(String fragOri) {
        if (fragOri == null || fragOri.isEmpty()) {
            return;
        }
        Path q = tablesDir.resolve("QianDao.txt");
        if (!Files.isRegularFile(q)) {
            log.warn("QianDao.txt not found for patch: {}", q);
            return;
        }
        try {
            java.util.List<String> lines = Files.readAllLines(q, java.nio.charset.StandardCharsets.UTF_8);
            StringBuilder out = new StringBuilder();
            int changed = 0;
            for (String line : lines) {
                String outLine = line;
                String trim = line.trim();
                if (trim.startsWith("#")) {
                    String[] cols = line.split("\t", -1);
                    if (cols.length >= 3) {
                        String ori = cols[2] == null ? "" : cols[2].trim();
                        // 占位行：保留上游原样（大多是 0）；碎片列才刷
                        if (!ori.isEmpty() && !"0".equals(ori) && isFragmentOri(ori)) {
                            if (!ori.equals(fragOri)) {
                                if (!DEFAULT_QIANDAO_FRAGMENT.equals(ori)) {
                                    log.warn("QianDao.txt 碎片列 {} -> {}（原值不是默认占位 {}，翻月未刷或人手改过表）",
                                            ori, fragOri, DEFAULT_QIANDAO_FRAGMENT);
                                }
                                cols[2] = fragOri;
                                outLine = String.join("\t", cols);
                                changed++;
                            }
                        }
                    }
                }
                out.append(outLine).append('\n');
            }
            if (changed == 0) {
                return;
            }
            Files.write(q, out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            log.info("patched {} fragment columns -> {} ({} rows)", q.toAbsolutePath(), fragOri, Integer.valueOf(changed));
        } catch (Exception e) {
            log.warn("patch QianDao fail: {}", e.toString());
        }
    }

    /** 该 Ori 是否是武将碎片（GoodsList 里第 12 列类型 == 2）。 */
    private boolean isFragmentOri(String ori) {
        CultivateTables.HeroCfg h = cultivate.heroByFragment(ori);
        return h != null && ori.equals(h.fragmentOri);
    }

    /**
     * 确保当月已定档：未定档（跨月/首启/强抽）时 roll 出新碎片并写 json。
     *
     * @param patchTable 真正 roll 出碎片后，是否立即把 tables/QianDao.txt 的 SP* 列刷成当月碎片。
     *                   每日定时任务 / 启动跨月传 true（roll 完直接改表，不再等人手 publish）；
     *                   手工重抽预览可传 false（只改 json）。
     */
    private void ensureCurrentMonth(boolean patchTable) {
        LocalDate today = GameTime.today();
        int y = today.getYear();
        int m = today.getMonthValue();
        if (pick.year == y && pick.month == m
                && pick.fragmentOri != null && !pick.fragmentOri.isEmpty()) {
            return;
        }
        List<CultivateTables.HeroCfg> pool = heroesStarAtLeast(MIN_COMPOSE_STAR);
        if (pool.isEmpty()) {
            pick.year = y;
            pick.month = m;
            pick.heroIndex = 48;
            pick.fragmentOri = "SP048";
            log.warn("signin-month empty pool, fallback SP048");
        } else {
            Random rnd = ThreadLocalRandom.current();
            CultivateTables.HeroCfg h = pool.get(rnd.nextInt(pool.size()));
            pick.year = y;
            pick.month = m;
            pick.heroIndex = h.index;
            pick.fragmentOri = h.fragmentOri;
            log.info("signin-month roll {}-{} hero={} frag={} pool={}",
                    y, m, h.index, h.fragmentOri, pool.size());
        }
        save();
        if (patchTable) {
            // roll 完成直接改 qiandao 表，保证客户端格子图标与到账同源当月碎片
            patchQianDaoSpColumns(fragmentOri());
        }
    }

    private List<CultivateTables.HeroCfg> heroesStarAtLeast(int minStar) {
        List<CultivateTables.HeroCfg> out = new ArrayList<>();
        for (CultivateTables.HeroCfg h : cultivate.allHeroes()) {
            if (h == null || !cultivate.isPlayableHero(h.index)) {
                continue;
            }
            if (h.composeStar < minStar) {
                continue;
            }
            out.add(h);
        }
        return out;
    }

    private void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), pick);
        } catch (Exception e) {
            log.warn("signin-month save fail: {}", e.toString());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MonthPick {
        public int year;
        public int month;
        public int heroIndex;
        public String fragmentOri = "";
    }
}
