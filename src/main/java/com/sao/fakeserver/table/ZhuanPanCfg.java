package com.sao.fakeserver.table;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sao.fakeserver.config.SaoProperties;
import com.sao.fakeserver.util.GameTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 大厅「梦幻转盘」配置：tables/zhuanpan.json，手改后重启假服。
 *
 * <p>假服没有原厂转盘奖池表（原厂奖池在服务端），所以这里手配。字段号权威来自
 * {@code pyfoot\tmp_msgdll\NetProto\CCMsgRequestZhuanPanBaseInfo_Ret.cs}：
 * 1 zuanShiOneCost / 2 zuanShiTenCost / 3 tokenItem / 4 tokenOneCost / 5 tokenTenCost /
 * 6 zuanShiTurnModifier(fixed32) / 7 endTime / 8 topAwards / 9 normalAwardItem / 10 specialAwardItem。</p>
 *
 * <p>三条硬约束（违反了客户端会 NRE 或空白）：</p>
 * <ol>
 *   <li>{@code normalAwards} 必须 8 个槽位、pos = 1..8 —— 客户端
 *       {@code LunPanChouJiangUI.cs:125} 用 {@code string.Format(格式,pos)} 去 FindChild 取槽位物件，
 *       且 {@code :518-526} 的停位角公式按 8 槽分角。</li>
 *   <li>{@code specialAwards} 只需 pos 2 与 6 —— 客户端 {@code :130-137} 只认这两个 pos 的倍率标签。</li>
 *   <li>{@code endTime} 必须能按 {@code yyyy-MM-dd HH:mm:ss} 解析（{@code LunPanChouJiangUI.cs:555}），
 *       否则 ParseExact 抛异常。</li>
 * </ol>
 */
@Component
public class ZhuanPanCfg {
    private static final Logger log = LoggerFactory.getLogger(ZhuanPanCfg.class);
    private static final String FILE = "zhuanpan.json";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 客户端槽位数固定 8（{@code LunPanChouJiangUI.cs:518-526} 的停位角按它分角）。 */
    public static final int SLOTS = 8;

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final SaoProperties props;
    private FileData data = FileData.fallback();

    public ZhuanPanCfg(SaoProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        Path file = Paths.get(props.getTablesDir()).resolve(FILE);
        if (!Files.isRegularFile(file)) {
            log.warn("missing {}, using built-in zhuanpan config", file);
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
        data.ensure();
        log.info("zhuanpan enabled={} slots={} special={} token={}", Boolean.valueOf(data.enabled),
                Integer.valueOf(data.normalAwards.size()), Integer.valueOf(data.specialAwards.size()),
                data.tokenItem);
    }

    public boolean isOpen() {
        return data.enabled;
    }

    public int zuanShiOneCost() {
        return Math.max(0, data.zuanShiOneCost);
    }

    public int zuanShiTenCost() {
        return Math.max(0, data.zuanShiTenCost);
    }

    public String tokenItem() {
        return data.tokenItem == null ? "" : data.tokenItem;
    }

    public int tokenOneCost() {
        return Math.max(0, data.tokenOneCost);
    }

    public int tokenTenCost() {
        return Math.max(0, data.tokenTenCost);
    }

    public float turnModifier() {
        return data.zuanShiTurnModifier <= 0f ? 1f : data.zuanShiTurnModifier;
    }

    public long curAwardPool() {
        return Math.max(0L, data.curAwardPool);
    }

    /** 榜单跑马灯只保留最近 10 条（客户端 {@code ᝁ.cs:8373-8376} 也是 >10 截断）。 */
    public int bigAwardKeep() {
        return 10;
    }

    /**
     * 活动结束时刻。配了 {@code endTime} 就用它；否则按 {@code durationDays} 从今天起算到当天 23:59:59。
     * 两种都必须是 {@code yyyy-MM-dd HH:mm:ss}。
     */
    public String endTime() {
        if (data.endTime != null && !data.endTime.trim().isEmpty()) {
            return data.endTime.trim();
        }
        int days = Math.max(1, data.durationDays);
        return GameTime.today().plusDays(days - 1L).atTime(23, 59, 59).format(TIME);
    }

    /** 距结束还剩多少秒；已结束（或解析失败）返回 0。 */
    public long endTimeLeftSec() {
        try {
            LocalDateTime end = LocalDateTime.parse(endTime(), TIME);
            long left = java.time.Duration.between(GameTime.now(), end).getSeconds();
            return Math.max(0L, left);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    public List<Slot> slots() {
        return data.normalAwards;
    }

    public List<Special> specials() {
        return data.specialAwards;
    }

    public List<TopAward> topAwards() {
        return data.topAwards;
    }

    /** 该 pos 若在 specialAwards 里则返回倍率，否则 1。 */
    public float backRate(int pos) {
        for (Special s : data.specialAwards) {
            if (s != null && s.pos == pos) {
                return s.backRate <= 0f ? 1f : s.backRate;
            }
        }
        return 1f;
    }

    /** 按 weight 抽一个槽位。 */
    public Slot roll(Random rng) {
        List<Slot> pool = data.normalAwards;
        int total = 0;
        for (Slot s : pool) {
            total += Math.max(0, s.weight);
        }
        if (total <= 0) {
            return pool.get(rng.nextInt(pool.size()));
        }
        int tick = rng.nextInt(total);
        int acc = 0;
        for (Slot s : pool) {
            acc += Math.max(0, s.weight);
            if (tick < acc) {
                return s;
            }
        }
        return pool.get(pool.size() - 1);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FileData {
        public boolean enabled = true;
        public int zuanShiOneCost = 50;
        public int zuanShiTenCost = 450;
        public String tokenItem = "MHZP01";
        public int tokenOneCost = 1;
        public int tokenTenCost = 10;
        public float zuanShiTurnModifier = 1f;
        public String endTime = "";
        public int durationDays = 7;
        public long curAwardPool;
        public List<TopAward> topAwards = new ArrayList<TopAward>();
        public List<Slot> normalAwards = new ArrayList<Slot>();
        public List<Special> specialAwards = new ArrayList<Special>();

        static FileData fallback() {
            FileData d = new FileData();
            TopAward top = new TopAward();
            TopAward.Goods g = new TopAward.Goods();
            g.oriName = "GOODS104";
            g.count = 1;
            g.star = 4;
            top.goodsItems.add(g);
            d.topAwards.add(top);
            for (int pos = 1; pos <= SLOTS; pos++) {
                Slot s = new Slot();
                s.pos = pos;
                s.jinBi = 20000 * pos;
                s.weight = Math.max(1, 30 - pos * 3);
                d.normalAwards.add(s);
            }
            d.specialAwards.add(Special.of(2, 2f));
            d.specialAwards.add(Special.of(6, 1.5f));
            return d;
        }

        void ensure() {
            if (normalAwards == null) {
                normalAwards = new ArrayList<Slot>();
            }
            // 收紧成 8 槽、pos 归一到 1..8：多出来的丢掉、缺的用兜底补，避免客户端 FindChild 拿不到物件。
            List<Slot> fixed = new ArrayList<Slot>();
            for (int pos = 1; pos <= SLOTS; pos++) {
                Slot hit = null;
                for (Slot s : normalAwards) {
                    if (s != null && s.pos == pos) {
                        hit = s;
                        break;
                    }
                }
                if (hit == null) {
                    hit = new Slot();
                    hit.pos = pos;
                    hit.jinBi = 10000 * pos;
                    hit.weight = 10;
                }
                if (hit.weight <= 0) {
                    hit.weight = 1;
                }
                fixed.add(hit);
            }
            if (normalAwards.size() != SLOTS) {
                log.warn("zhuanpan normalAwards {} entries, normalized to {} slots",
                        Integer.valueOf(normalAwards.size()), Integer.valueOf(SLOTS));
            }
            normalAwards = fixed;
            if (specialAwards == null || specialAwards.isEmpty()) {
                specialAwards = new ArrayList<Special>();
                specialAwards.add(Special.of(2, 2f));
                specialAwards.add(Special.of(6, 1.5f));
            }
            if (topAwards == null) {
                topAwards = new ArrayList<TopAward>();
            }
            if (tokenItem == null) {
                tokenItem = "";
            }
            if (endTime == null) {
                endTime = "";
            }
            if (durationDays < 1) {
                durationDays = 7;
            }
        }
    }

    /** 一个槽位的展示奖励 + 抽奖权重（对应 CCMsgZhuanPanNormalAwardsItem）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Slot {
        /** 1..8；客户端按它 FindChild 取槽位物件。 */
        public int pos;
        /** 非空则槽位显示该道具图标（优先级最高）。 */
        public String oriName = "";
        public int count;
        public int star;
        public int zuanShi;
        public int jinBi;
        /** 只影响服务端抽奖概率，客户端不读。 */
        public int weight = 1;
    }

    /** 特殊槽倍率（对应 CCMsgZhuanPanSpecialAwardsItem）：客户端只认 pos 2 与 6。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Special {
        public int pos;
        public float backRate = 1f;

        static Special of(int pos, float rate) {
            Special s = new Special();
            s.pos = pos;
            s.backRate = rate;
            return s;
        }
    }

    /** 顶部大奖展示（对应 CCMsgZhuanPanTopAwardItem）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TopAward {
        public int jinBi;
        public int zuanShi;
        public int timeReq;
        public List<Goods> goodsItems = new ArrayList<Goods>();

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Goods {
            public String oriName = "";
            public int count = 1;
            public int star;
        }
    }
}
