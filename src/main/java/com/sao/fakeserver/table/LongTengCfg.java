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

/**
 * 大厅「龙腾 / 限时兑换」配置：tables/longteng.json，手改后重启假服。
 *
 * <p>假服与原厂客户端都<b>没有</b>这个活动的数值表（原厂配置在服务端），所以这里手配。
 * 字段号权威来自 {@code pyfoot\tmp_msgdll\NetProto\CMsgUpdateLTExchangeInfo.cs} 与
 * 同目录 {@code CMsgUpdateLTExchangePrizeRet.cs} / {@code CCMsgLTExchangeRankListInfo.cs} /
 * {@code CMsgLTExchangeGoodsRet.cs}。</p>
 *
 * <p>两条硬约束：</p>
 * <ol>
 *   <li>{@code endTime} 必须能按 {@code yyyy-MM-dd HH:mm:ss} 严格解析
 *       （{@code ExchangeInATime.cs:278 DateTime.ParseExact}），否则客户端抛异常。</li>
 *   <li>{@code medalNames} 必须非空，且 {@code prizes[].medalLevel} 只能 1..5
 *       —— {@code ExchangeGoods.cs:176} 直接取 {@code xunzhangName.Count} 不判空；
 *       {@code PublicIconPropertyCfg.cs:588 mXunZhangIconName = new string[5,2]} 是 5 档定长。</li>
 * </ol>
 *
 * <p>道具 ori 全部取自 {@code tables/GoodsList.txt}：{@code ZSDH01..ZSDH05} 是
 * 兑换勋章 1..5 星（{@code :512-516}）、{@code ZSDH06} 荣誉（{@code :517}）、
 * {@code ZSDH07} 兑换币（{@code :518}）。</p>
 */
@Component
public class LongTengCfg {
    private static final Logger log = LoggerFactory.getLogger(LongTengCfg.class);
    private static final String FILE = "longteng.json";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final SaoProperties props;
    private Root data = Root.fallback();

    public LongTengCfg(SaoProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        Path file = Paths.get(props.getTablesDir()).resolve(FILE);
        if (!Files.isRegularFile(file)) {
            log.warn("missing {}, using built-in longteng config", file);
            return;
        }
        try {
            Root loaded = mapper.readValue(file.toFile(), Root.class);
            if (loaded != null) {
                loaded.ensure();
                data = loaded;
            }
        } catch (IOException e) {
            log.warn("read {} failed: {}", file, e.toString());
        }
        data.ensure();
        log.info("longteng enabled={} prizes={} goods={} rankPrizes={} token={}", Boolean.valueOf(data.enabled),
                Integer.valueOf(data.prizes.size()), Integer.valueOf(data.goods.size()),
                Integer.valueOf(data.rankPrizes.size()), data.tokenName);
    }

    public boolean isOpen() {
        return data.enabled && data.activeOnOff == 1;
    }

    // ------------------------------------------------------------ 对 4101 的投影

    public int activeOnOff() {
        return isOpen() ? 1 : 0;
    }

    public int onceRmbCost() {
        return Math.max(0, data.onceRmbCost);
    }

    public int tenRmbCost() {
        return Math.max(0, data.tenRmbCost);
    }

    public int onceTokenCost() {
        return Math.max(0, data.onceTokenCost);
    }

    public int tenTokenCost() {
        return Math.max(0, data.tenTokenCost);
    }

    public String tokenName() {
        return data.tokenName;
    }

    public String honourName() {
        return data.honourName;
    }

    public List<String> medalNames() {
        return data.medalNames;
    }

    public int rankId() {
        return data.rankId;
    }

    /** 结束时间：配了就用配的，没配就按「今天 + {@code keepDays} 天 23:59:59」算。 */
    public String endTime() {
        if (data.endTime != null && !data.endTime.trim().isEmpty()) {
            return data.endTime.trim();
        }
        LocalDateTime end = GameTime.today().plusDays(Math.max(1, data.keepDays)).atTime(23, 59, 59);
        return end.format(TIME);
    }

    // ------------------------------------------------------------------ 数据

    public List<Prize> prizes() {
        return data.prizes;
    }

    public Prize prizeByOri(String ori) {
        if (ori == null) {
            return null;
        }
        for (Prize p : data.prizes) {
            if (p != null && ori.equalsIgnoreCase(p.moriName)) {
                return p;
            }
        }
        return null;
    }

    public List<Goods> goods() {
        return data.goods;
    }

    public Goods goodsByOri(String ori) {
        if (ori == null) {
            return null;
        }
        for (Goods g : data.goods) {
            if (g != null && ori.equalsIgnoreCase(g.oriName)) {
                return g;
            }
        }
        return null;
    }

    public List<RankPrize> rankPrizes() {
        return data.rankPrizes;
    }

    /** 抽奖一次要扣的兑换币数（不足时按 {@code rmb} 钻石兜底）。 */
    public int tokenCost(int times) {
        return times >= 10 ? tenTokenCost() : onceTokenCost();
    }

    /** 兑换币不足时抽一次要扣的钻石数。 */
    public int rmbCost(int times) {
        return times >= 10 ? tenRmbCost() : onceRmbCost();
    }

    // ------------------------------------------------------------- JSON 结构

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Root {
        public boolean enabled = true;
        public int activeOnOff = 1;
        /** 留空 = 今天 + keepDays 天 23:59:59。 */
        public String endTime = "";
        public int keepDays = 30;
        public int onceRmbCost = 60;
        public int tenRmbCost = 540;
        public String tokenName = "ZSDH07";
        public int onceTokenCost = 2;
        public int tenTokenCost = 18;
        public String honourName = "ZSDH06";
        public int rankId = 1;
        public List<String> medalNames = new ArrayList<String>();
        public List<Prize> prizes = new ArrayList<Prize>();
        public List<Goods> goods = new ArrayList<Goods>();
        public List<RankPrize> rankPrizes = new ArrayList<RankPrize>();

        static Root fallback() {
            Root r = new Root();
            r.medalNames.add("ZSDH01");
            r.medalNames.add("ZSDH02");
            r.medalNames.add("ZSDH03");
            r.medalNames.add("ZSDH04");
            r.medalNames.add("ZSDH05");
            r.prizes.add(prize("PY001", 3, 10, 1, 5, 1, 40));
            r.prizes.add(prize("BX107", 4, 5, 2, 3, 2, 25));
            r.prizes.add(prize("ZBSX01", 4, 3, 3, 2, 5, 15));
            r.prizes.add(prize("JLBS01", 5, 1, 4, 2, 10, 10));
            r.prizes.add(prize("TS101", 5, 1, 5, 1, 20, 5));
            r.goods.add(goods("BX238", 1, 4, 1, 10, "", 0, 0));
            r.goods.add(goods("BX230", 1, 5, 1, 30, "", 0, 0));
            r.goods.add(goods("ZBSX01", 2, 4, 10, 20, "ZBSX01", 0, 0));
            r.goods.add(goods("GOODS117", 3, 5, 1, 50, "", 0, 0));
            r.rankPrizes.add(rankPrize(500000, 1000, 500, "PY001", 20, "BX107", 10, 1));
            r.rankPrizes.add(rankPrize(300000, 600, 300, "PY001", 10, "BX107", 5, 3));
            r.rankPrizes.add(rankPrize(200000, 400, 200, "PY001", 5, "BX107", 3, 10));
            r.rankPrizes.add(rankPrize(100000, 200, 100, "PY001", 3, "BX107", 2, 50));
            r.rankPrizes.add(rankPrize(50000, 100, 50, "PY001", 1, "BX107", 1, 100));
            return r;
        }

        void ensure() {
            if (tokenName == null) {
                tokenName = "ZSDH07";
            }
            if (honourName == null) {
                honourName = "ZSDH06";
            }
            if (medalNames == null) {
                medalNames = new ArrayList<String>();
            }
            if (medalNames.isEmpty()) {
                // 空列表会让 ExchangeGoods.cs:176 直接 NRE，兜底补齐 1..5 档。
                medalNames.add("ZSDH01");
                medalNames.add("ZSDH02");
                medalNames.add("ZSDH03");
                medalNames.add("ZSDH04");
                medalNames.add("ZSDH05");
            }
            if (prizes == null || prizes.isEmpty()) {
                prizes = Root.fallback().prizes;
            }
            if (goods == null) {
                goods = new ArrayList<Goods>();
            }
            if (rankPrizes == null) {
                rankPrizes = new ArrayList<RankPrize>();
            }
            for (Prize p : prizes) {
                if (p != null) {
                    // 客户端 mXunZhangIconName 只有 5 档，越界会取到空/异常。
                    p.medalLevel = Math.max(1, Math.min(5, p.medalLevel));
                    p.weight = Math.max(1, p.weight);
                }
            }
        }

        private static Prize prize(String ori, int star, int num, int medalLevel, int medalNum,
                                   int honourNum, int weight) {
            Prize p = new Prize();
            p.moriName = ori;
            p.goodsStar = star;
            p.goodsNum = num;
            p.medalLevel = medalLevel;
            p.medalNum = medalNum;
            p.honourNum = honourNum;
            p.weight = weight;
            return p;
        }

        private static Goods goods(String ori, int type, int star, int num, int tokenCost,
                                   String suipianName, int suipianNum, int rmbCost) {
            Goods g = new Goods();
            g.oriName = ori;
            g.goodsType = type;
            g.goodsStar = star;
            g.goodsNum = num;
            g.tokenCost = tokenCost;
            g.suipianName = suipianName;
            g.suipianNum = suipianNum;
            g.rmbCost = rmbCost;
            return g;
        }

        private static RankPrize rankPrize(int jinBi, int rmb, int yingPo, String g1, int n1,
                                           String g2, int n2, int limit) {
            RankPrize r = new RankPrize();
            r.jinBi = jinBi;
            r.rmb = rmb;
            r.yingPo = yingPo;
            r.goods1Name = g1;
            r.goods1Num = n1;
            r.goods2Name = g2;
            r.goods2Num = n2;
            r.rankLimit = limit;
            return r;
        }
    }

    /** {@code CCMsgExchangePrize}（4101 tag7）。{@code weight} 是假服内部权重，不上包。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Prize {
        public String moriName = "";
        public int goodsStar;
        public int goodsNum;
        public int medalLevel = 1;
        public int medalNum;
        public int honourNum;
        public int weight = 1;
    }

    /** {@code CMsgLTExchangeGoodsRet} 对应的可兑换商品。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Goods {
        public String oriName = "";
        /** 1 普通道具 2 碎片/整卡 3 武将（客户端 ᝁ.cs:4727-4764 三分支）。 */
        public int goodsType = 1;
        public int goodsStar;
        public int goodsNum = 1;
        public int tokenCost;
        public int rmbCost;
        public String suipianName = "";
        public int suipianNum;
    }

    /** {@code CCMsgExchangeRankPrize}（4101 tag11）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RankPrize {
        public int jinBi;
        public int rmb;
        public int yingPo;
        public String goods1Name = "";
        public int goods1Num;
        public String goods2Name = "";
        public int goods2Num;
        public int rankLimit;
    }
}
