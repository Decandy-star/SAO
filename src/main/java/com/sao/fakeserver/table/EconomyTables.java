package com.sao.fakeserver.table;

import com.sao.fakeserver.config.SaoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 商店 / 买体金 / 充值档 / 精炼 / 重置 / 转换 / 章节宝箱 / 抢矿常数。
 */
@Component
public class EconomyTables {
    private static final Logger log = LoggerFactory.getLogger(EconomyTables.class);
    /** 与客户端 ShopPropertyCfg.GetNextGoodsRefreshTime 同一时区。 */
    public static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String[] TABLE_FILES = {
            "GoodsList.txt",
            "BuyTiLi.txt",
            "BuyJinBi.txt",
            "BuyFBPlayTime.txt",
            "BuyJYFBPlayTime.txt",
            "UserPayGoods.txt",
            "ShopCommom.txt",
            "ExchangeShop.txt",
            "EquipmentJingLian.txt",
            "EquipmentReset.txt",
            "EquipTransform.txt",
            "EquipmentXiLianCommon.txt",
            "ChapterBaoXiang.txt",
            "QiangKuang_Common.txt",
            "QiangKuang.txt",
            "GlobalSetup_CH.txt",
            "UserPayGoods_1st.txt",
            "UnionMaJiuBase.txt",
            "VipCfg.txt",
            "UnionBuildingLevelUp.txt",
            "TeQuanCard.txt",
            "TimeStoneChangeColour.txt",
            "TimeStoneCompose.txt",
            "Union.txt",
        };

    private final SaoProperties props;
    private final Map<String, GoodsCfg> goods = new HashMap<>();
    private final List<BuyRow> buyTiLi = new ArrayList<>();
    private final List<BuyRow> buyJinBi = new ArrayList<>();
    private final List<BuyRow> buyFb = new ArrayList<>();
    private final List<BuyRow> buyJyFb = new ArrayList<>();
    private final Map<Integer, PayRow> payById = new HashMap<>();
    private final Map<Integer, ShopCfg> shops = new HashMap<>();
    private final Map<String, ExchangeRow> exchange = new HashMap<>();
    private final Map<String, LeapRow> leaps = new HashMap<>();
    private final Map<String, RefineRow> refines = new HashMap<>();
    private final Map<String, TransformRow> transforms = new HashMap<>();
    private final Map<String, ResetRow> resetStars = new HashMap<>();
    private final Map<String, ResetRow> resetJingLian = new HashMap<>();
    private int resetBaseCost = 50;
    private String resetGiveGoods = "JLBS04";
    private int resetGiveGoodsRate = 9500;
    private final Map<Integer, ChestRow> chests = new HashMap<>();
    private final List<String> shopPool = new ArrayList<>();
    /** 随机开箱（属性10）产品池。 */
    private final List<String> randomBoxPool = new ArrayList<>();
    /** 自选箱 GoodsList param1 → cells。 */
    private final Map<Integer, List<ChoiceCell>> choiceSchemes = new HashMap<>();
    /** type → 货池（GoodsList 按玩法语义拆分，见 buildShopCatalogs）。 */
    private final Map<Integer, List<ShopOffer>> shopOffersByType = new HashMap<>();
    private XiLianCommon xiLian = new XiLianCommon();
    private MineCommon mine = new MineCommon();
    /** QiangKuang.txt：矿ID → 行。 */
    private final Map<Integer, MineRow> minesById = new HashMap<>();
    /** 矿类型 → 该类型矿 ID 升序（分页 8 个/页）。 */
    private final Map<Integer, List<Integer>> mineIdsByType = new HashMap<>();
    private int escortRaidTimes = 3;
    private int escortSendXdb = 100;
    private final Map<Integer, VipRow> vipByLevel = new HashMap<>();
    private final Map<String, Integer> buildingChoulao = new HashMap<>();
    private final Map<Integer, TeQuanRow> teQuanByType = new HashMap<>();
    private final Map<Integer, int[]> timeStoneColorCost = new HashMap<>();
    /** {@code TimeStoneCompose.txt}：时光石等级 → 合成消耗金币。 */
    private final Map<Integer, Integer> timeStoneComposeGold = new HashMap<>();
    /**
     * {@code TimeStoneCompose.txt} 第 4 列「暴击概率」：等级 → 概率。
     * <p>单位（30% 还是万分率 0.3/10000）无法从 APK 判定（客户端 {@code TimeStoneComposeCfg} 根本不读这 4 列），
     * 本表按字面 0.3=30% 解释；若真服抓包证明是万分率，须改成 {@code toFloat(cols,3)/10000f}。
     */
    private final Map<Integer, Float> timeStoneCrit = new HashMap<>();
    /**
     * {@code TimeStoneCompose.txt} 第 5/6/7 列「第一/二/三次合成暴击概率」→ 位掩码（bit0/bit1/bit2）。
     * <p>这三列的 0/1 语义在 APK 里无解释（L1-L5=(1,0,1)、L6-L7=(0,1,0) 也不是合理权重序列），
     * 因此只做解析与暴露、暂不参与判定；等真服抓包定论后再用。
     */
    private final Map<Integer, Integer> timeStoneCritRoll = new HashMap<>();
    private int buildingProfitCap = 3000000;
    private int unionBossMaxTimes = 2;

    public EconomyTables(SaoProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        Path dir = Paths.get(props.getTablesDir());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("cannot create tables dir {}", dir);
        }
        ensureTables(dir);
        parseGoods(readTable(dir, "GoodsList.txt"));
        parseBuy(readTable(dir, "BuyTiLi.txt"), buyTiLi);
        parseBuy(readTable(dir, "BuyJinBi.txt"), buyJinBi);
        parseBuy(readTable(dir, "BuyFBPlayTime.txt"), buyFb);
        parseBuy(readTable(dir, "BuyJYFBPlayTime.txt"), buyJyFb);
        parsePay(readTable(dir, "UserPayGoods.txt"), false);
        // 首充档 ID 1–7；与常规 8+ 分表，合并进 payById 供发钻与 GetCur1stChongZhiRMB。
        parsePay(readTable(dir, "UserPayGoods_1st.txt"), true);
        parseShop(readTable(dir, "ShopCommom.txt"));
        parseExchange(readTable(dir, "ExchangeShop.txt"));
        parseJingLian(readTable(dir, "EquipmentJingLian.txt"));
        parseReset(readTable(dir, "EquipmentReset.txt"));
        parseTransform(readTable(dir, "EquipTransform.txt"));
        parseXiLian(readTable(dir, "EquipmentXiLianCommon.txt"));
        parseChest(readTable(dir, "ChapterBaoXiang.txt"));
        parseMine(readTable(dir, "QiangKuang_Common.txt"));
        parseMineList(readTable(dir, "QiangKuang.txt"));
        parseZzFromGlobal(readTable(dir, "GlobalSetup_CH.txt"));
        parseChangeNameFromGlobal(readTable(dir, "GlobalSetup_CH.txt"));
        parseMajiu(readTable(dir, "UnionMaJiuBase.txt"));
        parseVip(readTable(dir, "VipCfg.txt"));
        parseBuilding(readTable(dir, "UnionBuildingLevelUp.txt"));
        parseTeQuan(readTable(dir, "TeQuanCard.txt"));
        parseTimeStoneColor(readTable(dir, "TimeStoneChangeColour.txt"));
        parseTimeStoneCompose(readTable(dir, "TimeStoneCompose.txt"));
        fillBoxCurrency();
        parseUnion(readTable(dir, "Union.txt"));
        log.info("economy goods={} shops={} pay={} exchange={} leaps={} chests={} vip={} building={} tequan={} unionBossMax={} mines={}",
                goods.size(), shops.size(), payById.size(), exchange.size(), leaps.size(), chests.size(),
                vipByLevel.size(), buildingChoulao.size(), teQuanByType.size(), unionBossMaxTimes, minesById.size());
    }

    public GoodsCfg goods(String ori) {
        return goods.get(ori);
    }

    /**
     * 公会拍卖基础价格（勇气币）= GoodsList col8。作战室拍卖的**起拍价**用它，
     * 公会商店的兄弟币基价也用它（APK 只有这一列公会货币价，见 {@code buildShopCatalogs}）。
     * 0 = 该表没填基价（如 EQ* 装备在 {@code EquipmentList.txt}，由 {@code CultivateTables} 提供）。
     */
    public int auctionPrice(String ori) {
        GoodsCfg g = goods.get(ori);
        return g == null ? 0 : g.auctionPrice;
    }

    /**
     * 产出位置 = GoodsList col10：1 关卡 / 2 竞技场兑换 / 3 神秘商店兑换 /
     * <b>4 公会商店产出</b> / 5 特殊活动 / 6 宝物转换。0 = 未标。
     */
    public int goodsSource(String ori) {
        GoodsCfg g = goods.get(ori);
        return g == null ? 0 : g.source;
    }

    /**
     * 器魂吞噬经验值 = GoodsList 第 15 字段（cols[14]）：C2S 3702 每件材料折算的器魂经验。
     * 0 = 不是器魂材料（客户端吞噬列表只列 &gt;0 的，见 {@code QiHunSys.cs:418-430}）。
     */
    public int soulExp(String ori) {
        GoodsCfg g = goods.get(ori);
        return g == null ? 0 : g.soulExp;
    }

    /** 显示名（GoodsList col2）；查不到返回空串，调用方自己退回 ori。 */
    public String goodsDisplayName(String ori) {
        GoodsCfg g = goods.get(ori);
        return g == null || g.displayName == null ? "" : g.displayName;
    }

    /** 全部物品（魔盒/商店随机池等需要按品质扫描）。 */
    public java.util.Collection<GoodsCfg> allGoods() {
        return goods.values();
    }

    /**
     * 道具是否是可用的喇叭（GoodsList col12 attrType == 12）。
     * <p>客户端 {@code BagUISystem.cs:870} 就是按这个属性把「使用」按钮接到喇叭输入弹窗的。
     */
    public boolean isLaBa(String ori) {
        GoodsCfg g = goods.get(ori);
        return g != null && g.attrType == 12;
    }

    /**
     * 喇叭道具 col13「属性自定义参数2」按 {@code |} 切开的 4 段
     * （雪花类型 / 场景特效 / 头顶特效 / Notifycfg id）。
     * <p>不是喇叭或没配就返回长度为 4 的空串数组，调用方可安全取下标。
     */
    public String[] laBaParams(String ori) {
        String raw = "";
        GoodsCfg g = goods.get(ori);
        if (g != null && g.attrP2Raw != null) {
            raw = g.attrP2Raw.trim();
        }
        String[] parts = raw.split("\\|", -1);
        String[] out = new String[]{"", "", "", ""};
        for (int i = 0; i < out.length && i < parts.length; i++) {
            out[i] = parts[i] == null ? "" : parts[i].trim();
        }
        return out;
    }

    public int sellGold(String ori, int count) {
        GoodsCfg g = goods.get(ori);
        if (g == null || count <= 0) {
            return 0;
        }
        return Math.max(0, g.goldPrice) * count;
    }

    /**
     * 时光石卸下（C2S 2901）消耗钻石：GoodsList col12 第三段（见 {@link GoodsCfg#removeCost}）。
     * 表值：Ⅰ–Ⅳ = 0，Ⅴ = 10，Ⅵ = 50，Ⅶ = 100，Ⅷ = 150。
     */
    public int timeStoneRemoveCost(String ori) {
        GoodsCfg g = goods.get(ori);
        return g == null ? 0 : Math.max(0, g.removeCost);
    }

    /** 取 {@code _} 分隔串的第 idx 段（0 基）为整数；越界/非法返回 0。 */
    private static int subInt(String s, int idx) {
        if (s == null || s.isEmpty() || idx < 0) {
            return 0;
        }
        String[] parts = s.split("_");
        if (idx >= parts.length) {
            return 0;
        }
        return toInt(parts[idx], 0);
    }

    public int jingLianStoneExp(String ori) {
        GoodsCfg g = goods.get(ori);
        if (g == null || g.attrType != 13) {
            return 0;
        }
        return Math.max(1, g.attrP1);
    }

    public boolean isBaoXiang(String ori) {
        GoodsCfg g = goods.get(ori);
        return g != null && g.attrType == 10;
    }

    public boolean isChoiceBaoXiang(String ori) {
        GoodsCfg g = goods.get(ori);
        return g != null && g.attrType == 15;
    }

    public List<ChoiceCell> choiceCells(String ori) {
        GoodsCfg g = goods.get(ori);
        if (g == null || g.attrType != 15) {
            return Collections.emptyList();
        }
        List<ChoiceCell> cells = choiceSchemes.get(Integer.valueOf(g.attrP1));
        return cells == null ? Collections.emptyList() : cells;
    }

    public ChoiceCell choiceCell(String ori, int choiceId) {
        for (ChoiceCell c : choiceCells(ori)) {
            if (c.id == choiceId) {
                return c;
            }
        }
        return null;
    }

    public int randomBoxPoolSize() {
        return randomBoxPool.size();
    }

    public String rollRandomBox(Random rng) {
        if (randomBoxPool.isEmpty()) {
            return "GOODS104";
        }
        return randomBoxPool.get(rng.nextInt(randomBoxPool.size()));
    }

    public BuyRow buyTiLi(int todayTimesPlusOne) {
        return pickBuy(buyTiLi, todayTimesPlusOne);
    }

    public BuyRow buyJinBi(int todayTimesPlusOne) {
        return pickBuy(buyJinBi, todayTimesPlusOne);
    }

    public int buyFbDiamond(boolean elite, int todayTimesPlusOne) {
        BuyRow row = pickBuy(elite ? buyJyFb : buyFb, todayTimesPlusOne);
        return row == null ? 50 : row.cost;
    }

    public PayRow pay(int id) {
        return payById.get(id);
    }

    public ShopCfg shop(int type) {
        return shops.get(type);
    }

    /** VipCfg col5 体力购买次数。缺档 VIP0=1。 */
    public int buyTiLiMaxTimes(int chargedDiamond) {
        VipRow row = vip(chargedDiamond);
        return row == null ? 1 : Math.max(0, row.buyVpMaxTimesEveryday);
    }

    /** VipCfg col10 金币购买次数。缺档 VIP0=4。 */
    public int buyJinBiMaxTimes(int chargedDiamond) {
        VipRow row = vip(chargedDiamond);
        return row == null ? 4 : Math.max(0, row.buyJinBiMaxTimesEveryday);
    }

    /** VipCfg col11 公会捐赠次数（当日上限）。缺档 VIP0=3。 */
    public int unionDonateMaxTimes(int chargedDiamond) {
        VipRow row = vip(chargedDiamond);
        return row == null ? 3 : Math.max(0, row.unionDonateMaxTimes);
    }

    /**
     * VipCfg col26「公会佣兵数量」：单个建筑的佣兵格位数（VIP0-4=2、VIP5-13=3、VIP14-15=4）。
     * 客户端 {@code MyBuildingWithYongBingItem_InYongBingMianBan.cs:208-215} 按
     * {@code VipManager.UnionMercenaryCount} 逐格上锁（{@code k >= count} 即锁），
     * 面板一共 4 格，所以这是「每个建筑能雇佣几个武将」的上限。缺档按 VIP0 = 2。
     */
    public int unionMercenaryCount(int chargedDiamond) {
        VipRow row = vip(chargedDiamond);
        return row == null ? 2 : Math.max(0, row.unionMercenaryCount);
    }

    /** 上海本地 yyyyMMddHH，给整点换货比较用（不用 float unix，秒级会丢精度）。 */
    public static int shopSlotKey(LocalDateTime t) {
        return t.getYear() * 1000000 + t.getMonthValue() * 10000 + t.getDayOfMonth() * 100 + t.getHour();
    }

    /** 不晚于 {@code t} 的最近一次表整点；当天都未到则用前一日最后一档。 */
    public static int latestPassedShopSlot(int[] hours, LocalDateTime t) {
        if (hours == null || hours.length == 0) {
            return shopSlotKey(t);
        }
        LocalDate d = t.toLocalDate();
        int nowKey = shopSlotKey(t);
        for (int back = 0; back < 3; back++) {
            int best = 0;
            for (int h : hours) {
                if (h < 0 || h > 23) {
                    continue;
                }
                int key = d.getYear() * 1000000 + d.getMonthValue() * 10000 + d.getDayOfMonth() * 100 + h;
                if (key <= nowKey && key > best) {
                    best = key;
                }
            }
            if (best > 0) {
                return best;
            }
            d = d.minusDays(1);
        }
        return nowKey;
    }

    /**
     * 自上次整点档 {@code lastSlot}（yyyyMMddHH）之后是否又到了表刷新整点（含该整点）。
     * lastSlot 未建档则 false（新店由 ensureShop 首次 force）。
     */
    public static boolean shopDueSysRefresh(int[] hours, int lastSlot, long nowMs) {
        if (hours == null || hours.length == 0 || lastSlot <= 0) {
            return false;
        }
        LocalDateTime now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), SHOP_ZONE);
        int nowKey = shopSlotKey(now);
        LocalDate d = LocalDate.of(lastSlot / 1000000, (lastSlot / 10000) % 100, (lastSlot / 100) % 100);
        LocalDate end = now.toLocalDate();
        if (d.isAfter(end)) {
            return false;
        }
        while (!d.isAfter(end)) {
            for (int h : hours) {
                if (h < 0 || h > 23) {
                    continue;
                }
                int key = d.getYear() * 1000000 + d.getMonthValue() * 10000 + d.getDayOfMonth() * 100 + h;
                if (key > lastSlot && key <= nowKey) {
                    return true;
                }
            }
            d = d.plusDays(1);
        }
        return false;
    }

    public List<String> shopPool() {
        return shopPool;
    }

    /** 某商店货池；无配置时退回空列表（ShopService 再兜底）。 */
    public List<ShopOffer> shopOffers(int type) {
        List<ShopOffer> list = shopOffersByType.get(Integer.valueOf(type));
        return list == null ? java.util.Collections.<ShopOffer>emptyList() : list;
    }

    /**
     * 某商店「免费/付费刷新次数」的重置小时（{@code ShopCommom} 第 16 列，五行均为 5）。
     *
     * <p>缺行/缺列时回落 {@link #DEFAULT_FREE_REFRESH_RESET_HOUR}。该列客户端零消费
     * （{@code ShopPropertyCfg.cs:26} 只赋值）⇒ 纯服务端日界口径。
     */
    public int shopFreeRefreshResetHour(int type) {
        ShopCfg cfg = shops.get(Integer.valueOf(type));
        return cfg == null ? DEFAULT_FREE_REFRESH_RESET_HOUR : cfg.freeRefreshResetHour;
    }

    /** 当前等级能进货架加权池的条数（minLevel ≤ lv）。 */
    public int shopOfferCountAtLevel(int type, int lv) {
        int n = 0;
        for (ShopOffer o : shopOffers(type)) {
            if (o != null && o.minLevel <= lv) {
                n++;
            }
        }
        return n;
    }

    public ExchangeRow exchange(String ori) {
        return exchange.get(ori);
    }

    public LeapRow leap(int quality, int nextLevel) {
        return leaps.get(quality + "/" + nextLevel);
    }

    public RefineRow refine(int quality, int nextLevel) {
        return refines.get(quality + "/" + nextLevel);
    }

    public TransformRow transform(String ori) {
        return transforms.get(ori);
    }

    public ResetRow resetStar(int quality, int star) {
        return resetStars.get(quality + "/" + star);
    }

    public ResetRow resetJingLian(int quality, int jingLianLevel) {
        return resetJingLian.get(quality + "/" + jingLianLevel);
    }

    public String resetGiveGoods() {
        return resetGiveGoods == null || resetGiveGoods.isEmpty() ? "JLBS04" : resetGiveGoods;
    }

    public int resetGiveGoodsRate() {
        return resetGiveGoodsRate;
    }

    public int resetBaseCost() {
        return resetBaseCost;
    }

    public ChestRow chest(int chapterId, int baoxiangId) {
        return chests.get(chapterId * 1000 + baoxiangId);
    }

    public XiLianCommon xiLian() {
        return xiLian;
    }

    public MineCommon mine() {
        return mine;
    }

    public MineRow mineRow(int kuangId) {
        return minesById.get(Integer.valueOf(kuangId));
    }

    /** 解析矿类型：优先表；兼容旧假 ID type*10000+…。 */
    public int mineType(int kuangId) {
        MineRow row = mineRow(kuangId);
        if (row != null) {
            return row.type;
        }
        if (kuangId >= 10000) {
            return Math.max(1, kuangId / 10000);
        }
        return 1;
    }

    /** 某类型第 page 页的 8 个矿 ID（1-based page；不足则短列表）。 */
    public List<Integer> mineIdsOnPage(int type, int page) {
        List<Integer> all = mineIdsByType.get(Integer.valueOf(Math.max(1, type)));
        if (all == null || all.isEmpty() || page < 1) {
            return Collections.emptyList();
        }
        int from = (page - 1) * 8;
        if (from >= all.size()) {
            return Collections.emptyList();
        }
        int to = Math.min(all.size(), from + 8);
        return all.subList(from, to);
    }

    public int minePageCount(int type) {
        List<Integer> all = mineIdsByType.get(Integer.valueOf(Math.max(1, type)));
        if (all == null || all.isEmpty()) {
            return 0;
        }
        return (all.size() - 1) / 8 + 1;
    }

    /** 矿 ID 所在页（1-based）；表外/找不到 → 0。 */
    public int minePageOf(int kuangId) {
        List<Integer> all = mineIdsByType.get(Integer.valueOf(mineType(kuangId)));
        if (all == null || all.isEmpty()) {
            return 0;
        }
        int idx = all.indexOf(Integer.valueOf(kuangId));
        if (idx < 0) {
            return 0;
        }
        return idx / 8 + 1;
    }

    /** 无表行时按类型取代表速度（兼容旧档假 ID）。 */
    public MineRow mineRowOrTypeFallback(int kuangId) {
        MineRow row = mineRow(kuangId);
        if (row != null) {
            return row;
        }
        List<Integer> all = mineIdsByType.get(Integer.valueOf(mineType(kuangId)));
        if (all == null || all.isEmpty()) {
            return null;
        }
        return mineRow(all.get(0).intValue());
    }

    public int escortRaidTimes() {
        return escortRaidTimes;
    }

    public int escortSendXdb() {
        return escortSendXdb;
    }

    /** 与客户端 VipManager.ReCalVIPLevel 相同：累计充值钻石达到哪档就哪级。 */
    public int vipLevel(int chargedDiamond) {
        int level = 0;
        for (VipRow row : vipByLevel.values()) {
            if (row != null && row.zuanShiNeeds <= chargedDiamond && row.level > level) {
                level = row.level;
            }
        }
        return level;
    }

    public VipRow vip(int chargedDiamond) {
        return vipByLevel.get(Integer.valueOf(vipLevel(chargedDiamond)));
    }

    public int faBiaoCount(int chargedDiamond) {
        VipRow row = vip(chargedDiamond);
        return row == null ? 1 : Math.max(1, row.faBiaoCount);
    }

    public int jieBiaoCount(int chargedDiamond) {
        VipRow row = vip(chargedDiamond);
        return row == null ? 3 : Math.max(0, row.jieBiaoCount);
    }

    /** VipCfg「竞技场重置次数」：每日可买 JJC 次数上限。 */
    public int jjcResetMaxCount(int chargedDiamond) {
        VipRow row = vip(chargedDiamond);
        return row == null ? 0 : Math.max(0, row.jjcResetMaxCount);
    }

    /** VipCfg「BOB重置次数」：每日可重置挑战赛次数上限。 */
    public int bobResetMaxCount(int chargedDiamond) {
        VipRow row = vip(chargedDiamond);
        return row == null ? 1 : Math.max(0, row.bobResetMaxCount);
    }

    /** VipCfg 资源本每日次数：12 不可触 / 13 镜像 / 14 致命 / 15 镰刀 / 16 圣诞。缺档按 2。 */
    public int resourceFbMaxPlay(int chargedDiamond, int regionType) {
        VipRow row = vip(chargedDiamond);
        int n = 2;
        if (row != null) {
            if (regionType == 5) {
                n = row.mirrorMax;
            } else if (regionType == 6) {
                n = row.untouchableMax;
            } else if (regionType == 8) {
                n = row.deadlyMax;
            } else if (regionType == 9) {
                n = row.santaMax;
            } else if (regionType == 10) {
                n = row.scytheMax;
            } else {
                n = 0;
            }
        }
        return Math.max(0, n);
    }

    /** VipCfg 资源本倍率；双倍日额外份 = floor(count*rate)−count。 */
    public float resourceFbRate(int chargedDiamond, int regionType) {
        VipRow row = vip(chargedDiamond);
        float r = 1f;
        if (row != null) {
            if (regionType == 5) {
                r = row.mirrorRate;
            } else if (regionType == 6) {
                r = row.untouchableRate;
            } else if (regionType == 8) {
                r = row.deadlyRate;
            } else if (regionType == 9) {
                r = row.santaRate;
            } else if (regionType == 10) {
                r = row.scytheRate;
            }
        }
        return r <= 0f ? 1f : r;
    }

    /** VipCfg 末列「日常活动扫荡」；登录 18 未写=0 仅看此项。 */
    public boolean resourceFbSaoDangOpen(int chargedDiamond) {
        VipRow row = vip(chargedDiamond);
        return row != null && row.riChangHuoDongSaoDang;
    }

    public boolean bctSingleSaoDang(int chargedDiamond) {
        VipRow row = vip(chargedDiamond);
        return row != null && row.bctSingleSaoDang;
    }

    public boolean bctAllSaoDang(int chargedDiamond) {
        VipRow row = vip(chargedDiamond);
        return row != null && row.bctAllSaoDang;
    }

    public int buildingChoulao(int type, int level) {
        Integer n = buildingChoulao.get(type + "/" + level);
        return n == null ? 0 : n.intValue();
    }

    public int buildingProfitCap() {
        return buildingProfitCap;
    }

    public int unionBossMaxTimes() {
        return Math.max(1, unionBossMaxTimes);
    }

    public TeQuanRow teQuan(int type) {
        return teQuanByType.get(Integer.valueOf(type));
    }

    public int[] timeStoneColorCost(int level) {
        int[] c = timeStoneColorCost.get(Integer.valueOf(level));
        return c != null ? c : new int[]{1000, 0};
    }

    /**
     * 时光石合成（C2S 3004）消耗金币：{@code TimeStoneCompose.txt}「等级/消耗金币」两列。
     * 行 = 输入石的等级（1..6，第 7 行首列无 {@code #} 被 hashRows 过滤 ⇒ 7 级不可再合成）。
     * 客户端 {@code TimeStoneComposeCfg} 只读这两列，{@code BaoShiHeChengUI.cs:1124} 用它查金币是否够。
     *
     * @return 该等级合成所需金币；没有该等级配置（含 7 级及以上）返回 -1 表示不可合成
     */
    public int timeStoneComposeGold(int level) {
        Integer n = timeStoneComposeGold.get(Integer.valueOf(level));
        return n == null ? -1 : n.intValue();
    }

    /** 可合成的最高输入等级（{@code TimeStoneCompose.txt} 里带 {@code #} 的最大行）。 */
    public int timeStoneComposeMaxInputLevel() {
        int max = 0;
        for (Integer k : timeStoneComposeGold.keySet()) {
            if (k != null && k.intValue() > max) {
                max = k.intValue();
            }
        }
        return max;
    }

    /**
     * 时光石合成暴击概率（{@code TimeStoneCompose.txt} 第 4 列，表值 0.3/0.2/0.1）。
     * <p><b>单位 = 万分率</b>（用户裁决，m01963）：表值除以 10000 ⇒ 0.3 = 0.003%，
     * 写法与 {@code CultivateTables} 的 {@code upgradeCritWan}（万分率整数）一致。
     * 未配置返回 0（= 不暴击）。
     * <p>客户端语义：{@code BaoShiHeChengUI.cs:102} 判 {@code TimeStoneCount > 1} 就播暴击粒子
     * {@code "eff_ui_baoshi_hecheng_baoji"} 并延迟 1 秒弹窗，{@code BaoShiHeChengRet.cs:77} 也只支持两组石头位
     * ⇒ <b>暴击产出上限就是 2 颗</b>。
     */
    public float timeStoneCrit(int level) {
        Float f = timeStoneCrit.get(Integer.valueOf(level));
        return f == null ? 0f : f.floatValue() / 10000f;
    }

    /** {@code TimeStoneCompose.txt} 第 5/6/7 列打包成的位掩码（bit0=第一次、bit1=第二次、bit2=第三次）。 */
    public int timeStoneCritRollMask(int level) {
        Integer v = timeStoneCritRoll.get(Integer.valueOf(level));
        return v == null ? 0 : v.intValue();
    }

    private BuyRow pickBuy(List<BuyRow> list, int times) {
        if (list.isEmpty()) {
            return null;
        }
        BuyRow last = list.get(list.size() - 1);
        for (BuyRow r : list) {
            if (r.times == times) {
                return r;
            }
            last = r;
        }
        return last;
    }

    private void ensureTables(Path dir) {
        boolean missing = false;
        for (String name : TABLE_FILES) {
            if (!Files.isRegularFile(dir.resolve(name))) {
                missing = true;
                break;
            }
        }
        if (!missing) {
            return;
        }
        GameTextLoader loader = GameTextLoader.load(props.getGameText());
        if (loader.isEmpty()) {
            return;
        }
        for (String name : TABLE_FILES) {
            String text = loader.get(name);
            if (text == null) {
                continue;
            }
            try {
                Files.write(dir.resolve(name), text.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                log.warn("write {} failed: {}", name, e.toString());
            }
        }
    }

    private static String readTable(Path dir, String name) {
        Path file = dir.resolve(name);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private void parseGoods(String text) {
        goods.clear();
        shopPool.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            if (cols.length < 12) {
                continue;
            }
            GoodsCfg g = new GoodsCfg();
            g.ori = cols[1];
            g.displayName = cols.length > 2 ? cols[2] : "";
            g.quality = toInt(cols, 6);
            g.goldPrice = toInt(cols, 7);
            g.auctionPrice = toInt(cols, 8);
            g.slot = toInt(cols, 9);
            g.source = toInt(cols, 10);
            g.attrType = toInt(cols, 11);
            g.attrP1 = toInt(cols, 12);
            g.attrP1Raw = cols.length > 12 ? cols[12] : "";
            g.removeCost = subInt(g.attrP1Raw, 2);
            // col13「属性自定义参数2」：喇叭道具的分段参数（见 GoodsCfg#attrP2Raw）。
            g.attrP2Raw = cols.length > 13 ? cols[13] : "";
            // 第 15 字段「器魂吞噬经验值」（cols[14]，含首列 #）。
            g.soulExp = toInt(cols, 14);
            goods.put(g.ori, g);
            if ((g.attrType == 1 || g.attrType == 3 || g.attrType == 4 || g.attrType == 13)
                    && g.goldPrice > 0 && shopPool.size() < 80) {
                shopPool.add(g.ori);
            }
        }
        buildShopCatalogs();
        buildBoxPools();
    }

    /**
     * 随机箱（属性 10）产品池：可进背包的养成道具/碎片，不含箱/喇叭/双倍卡/自选箱，
     * 武将碎片只要 param1 为可玩 index（&lt;1000）。每开 1 次抽 1 件。
     */
    private void buildBoxPools() {
        randomBoxPool.clear();
        choiceSchemes.clear();
        fillChoiceSchemes();
        for (GoodsCfg g : goods.values()) {
            if (g == null || g.ori == null || g.ori.isEmpty()) {
                continue;
            }
            int a = g.attrType;
            if (a == 10 || a == 12 || a == 14 || a == 15 || a == 6) {
                continue;
            }
            if (g.ori.startsWith("BX") || g.ori.startsWith("WSJL") || g.ori.startsWith("HTSP")
                    || g.ori.startsWith("HTJB") || g.ori.startsWith("FBSGS")) {
                continue;
            }
            if (a == 2 && (g.attrP1 <= 0 || g.attrP1 >= 1000)) {
                continue;
            }
            if (a <= 0) {
                continue;
            }
            randomBoxPool.add(g.ori);
        }
        log.info("baoXiang randomPool={} choiceSchemes={}", Integer.valueOf(randomBoxPool.size()),
                Integer.valueOf(choiceSchemes.size()));
    }

    /** 自选箱方案：属性 15 的 param1。无官方 cells 表，选项按 GoodsList 描述规划。UI 最多 4 格。 */
    private void fillChoiceSchemes() {
        choiceSchemes.put(Integer.valueOf(1), choicePairItem("GOODS145", 30, "SP044", 30));
        choiceSchemes.put(Integer.valueOf(2), choicePairEquip("EQ0034", "EQ0037"));
        List<ChoiceCell> s3 = new ArrayList<ChoiceCell>();
        s3.add(choiceHero(1, 34, 3));
        s3.add(choiceHero(2, 38, 3));
        s3.add(choiceHero(3, 40, 3));
        s3.add(choiceHero(4, 48, 3));
        choiceSchemes.put(Integer.valueOf(3), s3);
        choiceSchemes.put(Integer.valueOf(4), choicePairItem("GOODS115", 10, "GOODS144", 10));
        choiceSchemes.put(Integer.valueOf(5), choicePairItem("GOODS118", 10, "SP035", 10));
        choiceSchemes.put(Integer.valueOf(6), choicePairItem("QH002", 50, "QH003", 50));
        List<ChoiceCell> s7 = new ArrayList<ChoiceCell>();
        s7.add(choiceItem(1, "ZBSP33", 10));
        s7.add(choiceItem(2, "ZBSP34", 10));
        s7.add(choiceItem(3, "ZBSP35", 10));
        s7.add(choiceItem(4, "ZBSP36", 10));
        choiceSchemes.put(Integer.valueOf(7), s7);
        List<ChoiceCell> s8 = new ArrayList<ChoiceCell>();
        s8.add(choiceItem(1, "GOODS245", 80));
        s8.add(choiceItem(2, "SP048", 80));
        s8.add(choiceItem(3, "SP040", 80));
        s8.add(choiceItem(4, "GOODS146", 80));
        choiceSchemes.put(Integer.valueOf(8), s8);
        List<ChoiceCell> s9 = new ArrayList<ChoiceCell>();
        s9.add(choiceEquip(1, "EQ0080"));
        s9.add(choiceEquip(2, "EQ0081"));
        s9.add(choiceEquip(3, "EQ0082"));
        s9.add(choiceEquip(4, "EQ0083"));
        choiceSchemes.put(Integer.valueOf(9), s9);
    }

    /**
     * 宝箱（{@code GoodsList.txt} 属性 10）的<b>货币</b>产出。
     * <p>为什么必须硬编码：{@code GoodsList} 承载产出的唯一列是 {@code col12}，内容形如 {@code 99999:NN}
     * 的「抽取库引用」，而 <b>99999 解库表在假服与客户端都不存在</b>（{@code tables\ltsj.json:14} 亦自述）。
     * 客户端对属性 10 的行 {@code col12} 原样保留、零消费；{@code CCMsgOpenBaoXiang_Ret} 的 8 个货币
     * 字段（1 jinBi / 2 zuanShi / 3 tiLi / 4 wnsp / 5 yingPo / 8 mofachen / 9 jjcJiFen / 10 xiongDiBi）
     * <b>完全由服务端下发值决定，客户端不做任何本地换算</b>（{@code BagUISystem.cs:1375-1443} 单开、
     * {@code :1569-1636} 十连，逐项 {@code >0} 才冒字入列）。
     * <p>本表数值取自 {@code GoodsList.txt} 的名字/说明列（如「万能碎片×500」「必定获得500个万能碎片」），
     * 因此客户端显示与假服入账自洽；只是可能与真服真实值不同 —— 要逐值一致必须抓到 99999 解库表。
     * <p>另有 7 个箱（{@code HTJB26 / TWBX003 / TWBX004 / BX158 / BX237 / HTSP26 / BX109}）文本只写
     * 「大量/超量/随机」，**文本本身给不出数字**，故其值由产品按<b>养成曲线</b>规划（用户 m02388 指令），
     * 依据见 {@link #fillBoxCurrency()} 的锚点注释；同属「与真服可能不同」的估算值。
     * <p>纯道具混合箱（{@code BX240} 精炼催化剂宝箱 / {@code BX241} 精纯结晶宝箱）相反 ——
     * 说明列自带「4~5 个」「1~2 个」的区间，取<b>区间下限</b>填 {@code alsoItemOri/alsoItemCount}（见 {@link #fillBoxCurrency()}）。
     * <p><b>钻石恒为 0</b>：全表属性 10 行无一含「钻石/RMB」。
     */
    public static final class BoxCurrency {
        public final int gold;
        public final int diamond;
        public final int stamina;
        public final int wnsp;
        public final int yingPo;
        public final int moFaChen;
        public final int jjc;
        public final int xdb;
        /**
         * 混合箱：说明列除货币外还写了道具时填该道具 ori，{@code null} = 纯货币箱。
         * <p>只有 {@code OMBX001}（GoodsList.txt:544「打开后获得酱味三明治x5，体力x30」）命中，
         * 其 {@code alsoItemOri = "GOODS104"}（:24 酱味三明治）、{@code alsoItemCount = 5}。
         * 纯货币箱开箱时不再白送随机物品，混合箱仍要发这一件。
         */
        public String alsoItemOri;
        /** 混合箱道具数量（见 {@link #alsoItemOri}）。 */
        public int alsoItemCount;

        BoxCurrency(int gold, int diamond, int stamina, int wnsp, int yingPo, int moFaChen, int jjc, int xdb) {
            this.gold = gold;
            this.diamond = diamond;
            this.stamina = stamina;
            this.wnsp = wnsp;
            this.yingPo = yingPo;
            this.moFaChen = moFaChen;
            this.jjc = jjc;
            this.xdb = xdb;
        }

        public boolean any() {
            return alsoItemOri != null || gold > 0 || diamond > 0 || stamina > 0 || wnsp > 0
                    || yingPo > 0 || moFaChen > 0 || jjc > 0 || xdb > 0;
        }

        /**
         * 开箱 {@code n} 次要发的<b>货币</b>总量（十连 = 单次 × count）。
         * <p><b>故意不乘 {@link #alsoItemCount}</b>：混合箱的道具是在开箱循环里<b>逐次</b>发的
         * （{@code DungeonService.onOpenBaoXiang} 的 {@code for (i < count)} 每次发 alsoItemCount 件），
         * 这里若再乘一次就是 count² —— BX109 十连会发 200 片结衣碎片而不是 20 片。
         */
        public BoxCurrency mul(int n) {
            if (n <= 1) {
                return this;
            }
            BoxCurrency r = new BoxCurrency(gold * n, diamond * n, stamina * n, wnsp * n,
                    yingPo * n, moFaChen * n, jjc * n, xdb * n);
            r.alsoItemOri = alsoItemOri;
            r.alsoItemCount = alsoItemCount;
            return r;
        }
    }

    private final Map<String, BoxCurrency> boxCurrency = new HashMap<>();

    /** 该宝箱 ori 的货币产出；{@code null} = 不是货币箱（走随机物品池）。 */
    public BoxCurrency boxCurrency(String ori) {
        return ori == null ? null : boxCurrency.get(ori);
    }

    private static BoxCurrency cur(int gold, int diamond, int stamina, int wnsp, int yingPo, int moFaChen, int jjc, int xdb) {
        return new BoxCurrency(gold, diamond, stamina, wnsp, yingPo, moFaChen, jjc, xdb);
    }

    private void fillBoxCurrency() {
        boxCurrency.clear();
        // 金币箱 HTJB1..25 = 260000 + 20000×(n-1)（GoodsList.txt:316-340）
        // 万能碎片箱 HTSP1..25 = 600 + 100×(n-1)（:290-314）
        for (int n = 1; n <= 25; n++) {
            boxCurrency.put("HTJB" + n, cur(260000 + 20000 * (n - 1), 0, 0, 0, 0, 0, 0, 0));
            boxCurrency.put("HTSP" + n, cur(0, 0, 0, 600 + 100 * (n - 1), 0, 0, 0, 0));
        }
        // 体力
        boxCurrency.put("BX127", cur(0, 0, 150, 0, 0, 0, 0, 0));   // :364
        boxCurrency.put("BX152", cur(0, 0, 100, 0, 0, 0, 0, 0));   // :388
        boxCurrency.put("BX136", cur(0, 0, 80, 0, 0, 0, 0, 0));    // :373
        // 混合箱：GoodsList.txt:544「打开后获得酱味三明治x5，体力x30」——货币 + 道具都发，
        // 因此 DungeonService 不能对它跳过道具分支（alsoItemOri 非空即判为混合箱）。
        BoxCurrency ombx = cur(0, 0, 30, 0, 0, 0, 0, 0);
        ombx.alsoItemOri = "GOODS104";  // :24 酱味三明治（可提升英雄 400 点经验）
        ombx.alsoItemCount = 5;
        boxCurrency.put("OMBX001", ombx);
        // 金币
        boxCurrency.put("BX146", cur(500000, 0, 0, 0, 0, 0, 0, 0));    // :383
        boxCurrency.put("BX148", cur(1000000, 0, 0, 0, 0, 0, 0, 0));   // :385
        boxCurrency.put("BX169", cur(400000, 0, 0, 0, 0, 0, 0, 0));    // :406
        boxCurrency.put("BX196", cur(888888, 0, 0, 0, 0, 0, 0, 0));    // :433
        boxCurrency.put("BX121", cur(500000, 0, 0, 0, 0, 0, 0, 0));    // :358
        boxCurrency.put("BX122", cur(800000, 0, 0, 0, 0, 0, 0, 0));    // :359
        boxCurrency.put("BX123", cur(800000, 0, 0, 0, 0, 0, 0, 0));    // :360
        boxCurrency.put("BX124", cur(1000000, 0, 0, 0, 0, 0, 0, 0));   // :361
        boxCurrency.put("BX125", cur(1500000, 0, 0, 0, 0, 0, 0, 0));   // :362
        boxCurrency.put("BX126", cur(2500000, 0, 0, 0, 0, 0, 0, 0));   // :363
        // 万能碎片
        boxCurrency.put("BX107", cur(0, 0, 0, 500, 0, 0, 0, 0));       // :344
        boxCurrency.put("BX108", cur(0, 0, 0, 2000, 0, 0, 0, 0));      // :345
        boxCurrency.put("BX132", cur(0, 0, 0, 2000, 0, 0, 0, 0));      // :369
        boxCurrency.put("BX138", cur(0, 0, 0, 1000, 0, 0, 0, 0));      // :375
        boxCurrency.put("BX141", cur(0, 0, 0, 3000, 0, 0, 0, 0));      // :378
        boxCurrency.put("BX150", cur(0, 0, 0, 1500, 0, 0, 0, 0));      // :387
        boxCurrency.put("BX239", cur(0, 0, 0, 300, 0, 0, 0, 0));       // :476
        boxCurrency.put("TWBX001", cur(0, 0, 0, 200, 0, 0, 0, 0));     // :519
        boxCurrency.put("TWBX002", cur(0, 0, 0, 300, 0, 0, 0, 0));     // :520
        // 英魄
        boxCurrency.put("BX105", cur(0, 0, 0, 0, 500, 0, 0, 0));       // :342
        boxCurrency.put("BX106", cur(0, 0, 0, 0, 2000, 0, 0, 0));      // :343
        boxCurrency.put("BX129", cur(0, 0, 0, 0, 1000, 0, 0, 0));      // :366
        boxCurrency.put("BX140", cur(0, 0, 0, 0, 3000, 0, 0, 0));      // :377
        boxCurrency.put("BX144", cur(0, 0, 0, 0, 200, 0, 0, 0));       // :381
        boxCurrency.put("BX149", cur(0, 0, 0, 0, 800, 0, 0, 0));       // :386
        boxCurrency.put("BX263", cur(0, 0, 0, 0, 50, 0, 0, 0));        // :563
        // 魔法尘
        boxCurrency.put("BX242", cur(0, 0, 0, 0, 0, 10, 0, 0));        // :479
        boxCurrency.put("BX244", cur(0, 0, 0, 0, 0, 1, 0, 0));         // :481
        // JJC 积分
        boxCurrency.put("BX260", cur(0, 0, 0, 0, 0, 0, 50, 0));        // :560
        boxCurrency.put("BX261", cur(0, 0, 0, 0, 0, 0, 100, 0));       // :561
        // 兄弟币
        boxCurrency.put("BX264", cur(0, 0, 0, 0, 0, 0, 0, 50));        // :564
        // ================= 「大量 / 超量 / 随机」型 7 箱：说明列给不出数字，按养成曲线规划（用户 m02388） =================
        // 规划所用锚点（全部为本仓库实据，不是外部猜测）：
        //  ① 系列顺延：金币箱 HTJB1..25 = 260000 + 20000×(n−1)（GoodsList.txt:316-340）
        //     ⇒ n=26 = 760000；万能碎片箱 HTSP1..25 = 600 + 100×(n−1)（:290-314）⇒ n=26 = 3100。
        //  ② 钻金比：BuyJinBi.txt:2「2 钻石 → 20000 金币」⇒ 1 钻石 = 10000 金币（下述金币值即 20~76 钻）。
        //  ③ 金币的真实去处是装备升级，EquipmentUpgrade.txt 单次消耗：Lv30=6.0万 / Lv40=11.6万 / Lv50=18.8万 /
        //     Lv60=27.6万 / Lv70=38.0万 / Lv79=48.7万 ⇒ 一个「大额金币箱」应约等于该等级段 1 次升级（最多 1.5 次）。
        //  ④ 已注册同品质金币箱量级：BX169=40万、BX146=50万、BX196=88.9万、BX148=100万（本方法上方）。
        //  ⑤ 英雄碎片阶梯（VIP 箱说明）：碎片×10(VIP4) / ×20(VIP8) / ×50(VIP10)，另有 BX145「结衣碎片×10」；
        //     英魄箱已注册值 200(BX144) / 500(BX105) / 800(BX149) / 2000(BX106) / 3000(BX140)。
        boxCurrency.put("HTJB26", cur(760000, 0, 0, 0, 0, 0, 0, 0));   // :341 百战金币宝箱「大量」= HTJB 系列第 26 号顺延（≈Lv79 一次升级）
        boxCurrency.put("HTSP26", cur(0, 0, 0, 3100, 0, 0, 0, 0));     // :315 百战碎片宝箱「大量」= HTSP 系列第 26 号顺延
        boxCurrency.put("TWBX003", cur(200000, 0, 0, 0, 0, 0, 0, 0));  // :521 百战金币宝箱1「大量」品质3 = 百层塔常规层（Lv30→75）中段 ≈Lv52 一次升级
        boxCurrency.put("TWBX004", cur(400000, 0, 0, 0, 0, 0, 0, 0));  // :522 百战金币宝箱2「超量」= 上一层 2 倍（百层塔每 5 层给 2 倍：ZBSX01 10→20 + ZBSX02×5 + 两箱）
        boxCurrency.put("BX158", cur(600000, 0, 0, 0, 0, 0, 0, 0));    // :394 珍稀金币宝箱 品质4「珍稀」夹在 BX169 40万 与 BX148 100万 之间（≈Lv77 一次升级）
        // 红包是「新年兑换」页最高档（act-exchange.json:52，耗「新春大吉」四字各 5 = 次档「四字各 3」的 1.7 倍，也是该页唯一二十字档）
        // ⇒ 定为该页最大奖。⚠「随机开出」不做随机化：BoxCurrency 无区间字段，直接给期望值（8 结尾贴合红包语义）。
        boxCurrency.put("BX237", cur(288888, 0, 0, 0, 0, 0, 0, 0));    // :474 红包
        // 结衣魂石宝箱：魂石箱主产出是英雄碎片（GoodsList.txt:52 SP039「结衣」属性2），说明里的「或英魄」是兜底。
        // ⚠ 混合箱机制（alsoItemOri）只能「碎片和英魄都给」、做不到「二选一」；按主产出给碎片 + 少量英魄。
        BoxCurrency jieYi = cur(0, 0, 0, 0, 200, 0, 0, 0);
        jieYi.alsoItemOri = "SP039";
        jieYi.alsoItemCount = 2;
        boxCurrency.put("BX109", jieYi);                               // :346 结衣魂石宝箱
        // ============ 百层塔层 31+ 的两个材料箱：说明列自带数量区间，取**区间下限** ============
        // 与上面 7 箱不同，这两个箱的文字给了数字，不需要产品规划：
        //   GoodsList.txt:477 BX240 精炼催化剂宝箱「打开后获得4~5个精炼催化剂」⇒ JLFY02 ×4
        //   GoodsList.txt:478 BX241 精纯结晶宝箱  「打开后获得1~2个精纯结晶」  ⇒ JLFY03 ×1
        // 取区间下限的理由：假服发的是定值（BoxCurrency 无区间字段），取小值保证不过量、
        // 且可被测试钉住；客户端只显示服务端下发的数字，看不到这个取舍。
        // 取值的养成锚点（EquipmentJingLian.txt:16-22 品质4）：单次淬炼 Lv4 要 JLFY02×5、
        // Lv7 起叠加 JLFY03×5、Lv10 要 JLFY02×150+JLFY03×30；品质5（:26-32）Lv10 要 JLFY02×300+JLFY03×60。
        // ⇒ 一个箱＝「一次淬炼所需的一小部分」，与既有小额来源同量级（CloneService.java:116 克隆掉落 JLFY02×2、
        // OnceTaskConfig.txt「实力突破」JLFY02×2）。这两个箱全仓唯一产出点＝HundredTowerList.txt 层 31+（见报告 §8.8）。
        BoxCurrency catalyst = cur(0, 0, 0, 0, 0, 0, 0, 0);
        catalyst.alsoItemOri = "JLFY02";
        catalyst.alsoItemCount = 4;
        boxCurrency.put("BX240", catalyst);                            // :477 精炼催化剂宝箱
        BoxCurrency crystal = cur(0, 0, 0, 0, 0, 0, 0, 0);
        crystal.alsoItemOri = "JLFY03";
        crystal.alsoItemCount = 1;
        boxCurrency.put("BX241", crystal);                             // :478 精纯结晶宝箱
    }

    private static List<ChoiceCell> choicePairItem(String a, int na, String b, int nb) {
        List<ChoiceCell> list = new ArrayList<ChoiceCell>();
        list.add(choiceItem(1, a, na));
        list.add(choiceItem(2, b, nb));
        return list;
    }

    private static List<ChoiceCell> choicePairEquip(String a, String b) {
        List<ChoiceCell> list = new ArrayList<ChoiceCell>();
        list.add(choiceEquip(1, a));
        list.add(choiceEquip(2, b));
        return list;
    }

    private static ChoiceCell choiceItem(int id, String ori, int n) {
        ChoiceCell c = new ChoiceCell();
        c.id = id;
        c.type = 3;
        c.goodId = ori;
        c.amount = n;
        return c;
    }

    private static ChoiceCell choiceEquip(int id, String ori) {
        ChoiceCell c = new ChoiceCell();
        c.id = id;
        c.type = 1;
        c.goodId = ori;
        c.amount = 1;
        return c;
    }

    private static ChoiceCell choiceHero(int id, int index, int star) {
        ChoiceCell c = new ChoiceCell();
        c.id = id;
        c.type = 2;
        c.goodId = String.valueOf(index);
        c.amount = 1;
        c.star = star;
        return c;
    }

    /**
     * 五店货池规划（自主规划，对齐货币语义 + 独立性 + 等级渐进）：
     * <ul>
     *   <li>1 百货：经验食物 / 装备材料 / 低阶印记 / 钱袋 — 日常养成</li>
     *   <li>2 万能碎片店：装备碎片 ZBSP + 精炼石。ShopCommom 行名「作废」，APK NormalShop DuiHuan 页仍走 type2（开店等级 15）</li>
     *   <li>3 竞技：中高阶印记符文 + 刷新球 + 低阶时光石 — 战力/挑战向</li>
     *   <li>4 公会：<b>货单按 GoodsList 列重建</b>（见下方 {@code guild} 三段），售价 = col8 公会货币基价</li>
     *   <li>5 英魄：武将碎片专营 — 英雄收集向</li>
     * </ul>
     * 各池互不交叉核心品类；minLevel 随品质抬高。
     *
     * <p><b>公会店（type 4，兄弟币）货单依据</b>（用户 m11582「你自己规划具体的」授权自主规划）：
     * <ol>
     *   <li>APK {@code GoodsList.txt} col10「产出位置 = 4 公会商店产出」共 4 件：雷肯 / 死枪 武将碎片 +
     *       稀有翅膀碎片 / 精灵之翅碎片 —— 与真服玩家描述「限定英雄碎片 + 精灵翅膀碎片」完全吻合；</li>
     *   <li>APK col9「道具显示位置 = 4 进阶」96 件：印记/符文/晶石 Ⅰ–Ⅸ + 其碎片 Ⅲ–Ⅸ —— 对应真服描述的
     *       「英雄进阶材料 / 进阶石」；</li>
     *   <li>真服玩家描述点名、APK 把来源标在别处的养成/消耗品：铸铁/星陨石/锻魂石、时光石 Ⅰ–Ⅲ、
     *       经验药水、金币包。</li>
     * </ol>
     * 售价用 {@code GoodsList} col8「公会拍卖基础价格（勇气币）」：APK 只有这一列公会货币基价，
     * 且兄弟币与勇气币收入同量级（单场 15/25 + 名次奖 320/600），故同列同值。
     */
    private void buildShopCatalogs() {
        shopOffersByType.clear();
        List<ShopOffer> baiHuo = new ArrayList<>();
        List<ShopOffer> wnsp = new ArrayList<>();
        List<ShopOffer> jjc = new ArrayList<>();
        List<ShopOffer> guild = new ArrayList<>();
        List<ShopOffer> yingPo = new ArrayList<>();
        for (GoodsCfg g : goods.values()) {
            if (g == null || g.ori == null || g.ori.isEmpty()) {
                continue;
            }
            int q = Math.max(1, g.quality);
            int minLv = minLevelForQuality(q);
            int w = Math.max(1, 8 - q);
            // ---- 公会店货单（三段，见方法 javadoc）；addGuild 去重，不会与下面原有链重复计权
            if (g.source == 4) {
                addGuild(guild, offer(g.ori, q >= 4 ? 40 : 25, 1, 1, 1, false, g.auctionPrice));
            } else if (g.slot == 4 && g.auctionPrice > 0) {
                addGuild(guild, offer(g.ori, guildLevelFor(g.auctionPrice),
                        guildWeightFor(g.auctionPrice), 1, 2, false, g.auctionPrice));
            } else if (g.ori.startsWith("ZBSX")) {
                addGuild(guild, offer(g.ori, 15 + 12 * Math.max(0, zbsxGrade(g.ori) - 1), 2, 1, 2, false,
                        g.auctionPrice));
            } else if (g.attrType == 7 && timeStoneGrade(g.ori) >= 1 && timeStoneGrade(g.ori) <= 3) {
                int grade = timeStoneGrade(g.ori);
                addGuild(guild, offer(g.ori, 10 + 10 * grade, grade == 1 ? 3 : 2, 1, 1, false,
                        g.auctionPrice));
            } else if (g.attrType == 1 && g.goldPrice >= 40) {
                addGuild(guild, offer(g.ori, 10 + 10 * Math.max(0, q - 1), 2, 1, 2, false, g.auctionPrice));
            } else if (g.attrType == 6 && g.goldPrice > 0) {
                addGuild(guild, offer(g.ori, 10 + 5 * Math.max(0, q - 1), 2, 1, 1, false, g.auctionPrice));
            } else if (g.attrType == 8) {
                addGuild(guild, offer(g.ori, Math.max(minLv, 25), w, 1, 1, false, g.auctionPrice));
            }
            if ("PY001".equals(g.ori)) {
                jjc.add(offer(g.ori, 5, 6, 1, 2, false));
                baiHuo.add(offer(g.ori, 20, 1, 1, 1, true));
            } else if (g.attrType == 1) {
                // 经验食物：百货，低等级多给几份
                baiHuo.add(offer(g.ori, minLv, w + 4, q <= 2 ? 3 : 1, q <= 2 ? 8 : 3, false));
            } else if (g.attrType == 4 && g.goldPrice > 0) {
                baiHuo.add(offer(g.ori, minLv, w, 1, 2, false));
                if (q >= 3) {
                    guild.add(offer(g.ori, Math.max(minLv, 20), w, 1, 1, false));
                }
            } else if (g.attrType == 5 && q <= 2) {
                baiHuo.add(offer(g.ori, minLv, w, 1, 1, false));
            } else if (g.attrType == 6 && g.goldPrice > 0) {
                baiHuo.add(offer(g.ori, minLv, 2, 1, 1, q >= 3));
            } else if (g.attrType == 3 && g.ori.startsWith("ZBSP")) {
                // 蓝/紫碎片 minLevelForQuality 20/35，开店 15 会滤空
                wnsp.add(offer(g.ori, 15, w, 1, 1, false));
            } else if (g.attrType == 13) {
                wnsp.add(offer(g.ori, Math.max(minLv, 25), w, 1, 1, false));
            } else if (g.attrType == 5 && q >= 3) {
                jjc.add(offer(g.ori, Math.max(minLv, 15), w, 1, 1, false));
            } else if (g.attrType == 7) {
                // 时光石：竞技卖Ⅰ–Ⅱ；公会卖Ⅲ（高阶仍稀有）
                int grade = timeStoneGrade(g.ori);
                if (grade >= 1 && grade <= 2) {
                    jjc.add(offer(g.ori, Math.max(minLv, 12), w, 1, grade == 1 ? 2 : 1, false));
                } else if (grade == 3) {
                    guild.add(offer(g.ori, Math.max(minLv, 30), 2, 1, 1, false));
                }
            } else if (g.attrType == 9) {
                // 进阶书碎片品质偏高时 minLevelForQuality 会让开店 10 滤空
                guild.add(offer(g.ori, 10, w, 1, 2, false));
            } else if (g.attrType == 8) {
                guild.add(offer(g.ori, Math.max(minLv, 25), w, 1, 1, false));
            } else if (g.attrType == 2) {
                // 英雄碎片品质几乎全紫，minLevelForQuality=35 会让 12 级英魄店过滤为空。
                // 开店等级 12；高品质只降权重。
                yingPo.add(offer(g.ori, 12, Math.max(1, 6 - q), 1, 1, false));
            }
        }
        // 原有链补进公会店的行（如 a4 装备材料，真服描述也点名了「装备制作材料」）同样补上 col8 基价，
        // 保证同一家店里价格口径一致：有 col8 就用 col8，没有才让 ShopService 按品质/金币价推算。
        for (ShopOffer o : guild) {
            if (o.price <= 0) {
                GoodsCfg g = goods.get(o.ori);
                if (g != null && g.auctionPrice > 0) {
                    o.price = g.auctionPrice;
                }
            }
        }
        shopOffersByType.put(Integer.valueOf(1), baiHuo);
        shopOffersByType.put(Integer.valueOf(2), wnsp);
        shopOffersByType.put(Integer.valueOf(3), jjc);
        shopOffersByType.put(Integer.valueOf(4), guild);
        shopOffersByType.put(Integer.valueOf(5), yingPo);
        log.info("shop catalogs sizes baiHuo={} wnsp={} jjc={} guild={} yingPo={}",
                baiHuo.size(), wnsp.size(), jjc.size(), guild.size(), yingPo.size());
    }

    private static int minLevelForQuality(int quality) {
        if (quality <= 1) {
            return 1;
        }
        if (quality == 2) {
            return 10;
        }
        if (quality == 3) {
            return 20;
        }
        if (quality == 4) {
            return 35;
        }
        return 50;
    }

    /** TS101 → 1，TS204 → 4；非时光石 0。 */
    private static int timeStoneGrade(String ori) {
        if (ori == null || ori.length() < 5 || !ori.startsWith("TS")) {
            return 0;
        }
        char g = ori.charAt(4);
        if (g >= '1' && g <= '7') {
            return g - '0';
        }
        return 0;
    }

    /** ZBSX01 铸铁 / ZBSX02 星陨石 / ZBSX03 锻魂石 → 1/2/3；其它 0。 */
    private static int zbsxGrade(String ori) {
        if (ori == null || ori.length() < 6 || !ori.startsWith("ZBSX")) {
            return 0;
        }
        char g = ori.charAt(5);
        return (g >= '1' && g <= '9') ? g - '0' : 0;
    }

    /**
     * 公会店开启等级：按 GoodsList col8 基价分档。印记/符文/晶石 Ⅰ–Ⅸ 的基价阶梯是
     * 14 / 18 / 54 / 118 / 164 / 212 / 236 / 282 / 354，正好九档。
     */
    private static int guildLevelFor(int auctionPrice) {
        if (auctionPrice <= 18) {
            return 10;
        }
        if (auctionPrice <= 54) {
            return 15;
        }
        if (auctionPrice <= 118) {
            return 20;
        }
        if (auctionPrice <= 164) {
            return 25;
        }
        if (auctionPrice <= 212) {
            return 30;
        }
        if (auctionPrice <= 236) {
            return 35;
        }
        if (auctionPrice <= 282) {
            return 40;
        }
        if (auctionPrice <= 354) {
            return 45;
        }
        return 50;
    }

    /** 公会店权重：低阶材料多出、高阶稀有（与 {@link #guildLevelFor} 同档）。 */
    private static int guildWeightFor(int auctionPrice) {
        if (auctionPrice <= 18) {
            return 6;
        }
        if (auctionPrice <= 54) {
            return 5;
        }
        if (auctionPrice <= 118) {
            return 4;
        }
        if (auctionPrice <= 212) {
            return 3;
        }
        if (auctionPrice <= 282) {
            return 2;
        }
        return 1;
    }

    /** 公会店去重加入：同一个 ori 只保留首次（新货单块在原有链之前跑，故以新规则为准）。 */
    private static void addGuild(List<ShopOffer> guild, ShopOffer o) {
        for (ShopOffer e : guild) {
            if (e.ori.equals(o.ori)) {
                return;
            }
        }
        guild.add(o);
    }

    private static ShopOffer offer(String ori, int minLevel, int weight, int countMin, int countMax, boolean preferRmb) {
        return offer(ori, minLevel, weight, countMin, countMax, preferRmb, 0);
    }

    private static ShopOffer offer(String ori, int minLevel, int weight, int countMin, int countMax,
                                   boolean preferRmb, int price) {
        ShopOffer o = new ShopOffer();
        o.ori = ori;
        o.minLevel = Math.max(1, minLevel);
        o.weight = Math.max(1, weight);
        o.countMin = Math.max(1, countMin);
        o.countMax = Math.max(o.countMin, countMax);
        o.preferRmb = preferRmb;
        o.price = Math.max(0, price);
        return o;
    }

    private void parseBuy(String text, List<BuyRow> out) {
        out.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            BuyRow r = new BuyRow();
            r.times = toInt(cols, 1);
            r.cost = toInt(cols, 2);
            r.gain = toInt(cols, 3);
            if (r.times > 0) {
                out.add(r);
            }
        }
    }

    private int max1stPayGoodsId;

    private void parsePay(String text, boolean firstChargeTable) {
        if (!firstChargeTable) {
            payById.clear();
            max1stPayGoodsId = 0;
        }
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            PayRow r = new PayRow();
            r.id = toInt(cols, 1);
            r.type = toInt(cols, 2);
            r.rmb = toInt(cols, 4);
            r.diamond = toInt(cols, 6);
            r.award = toInt(cols, 7);
            r.extAward = toInt(cols, 8);
            r.extTimes = toInt(cols, 9);
            payById.put(Integer.valueOf(r.id), r);
            if (firstChargeTable && r.id > 0 && r.id < 1000 && r.id > max1stPayGoodsId) {
                max1stPayGoodsId = r.id;
            }
        }
    }

    /** UserPayGoods_1st 里钻石档最大 ID（客户端 msMax1stID）。 */
    public int max1stPayGoodsId() {
        return max1stPayGoodsId > 0 ? max1stPayGoodsId : 7;
    }

    private void parseShop(String text) {
        shops.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            ShopCfg s = new ShopCfg();
            s.type = toInt(cols, 1);
            s.openLevel = toInt(cols, 3);
            List<Integer> hours = new ArrayList<Integer>();
            for (int c = 9; c <= 13; c++) {
                int h = toInt(cols, c);
                if (h >= 0 && h <= 23) {
                    hours.add(Integer.valueOf(h));
                }
            }
            s.refreshHours = new int[hours.size()];
            for (int i = 0; i < hours.size(); i++) {
                s.refreshHours[i] = hours.get(i).intValue();
            }
            s.freeRefresh = toInt(cols, 14);
            if (cols.length > 15) {
                int resetHour = toInt(cols, 15);
                s.freeRefreshResetHour = (resetHour >= 0 && resetHour <= 23)
                        ? resetHour : DEFAULT_FREE_REFRESH_RESET_HOUR;
            }
            s.fieldCount = Math.max(1, toInt(cols, 16));
            s.refreshDiamond = Math.max(1, toInt(cols, 17));
            shops.put(Integer.valueOf(s.type), s);
        }
    }

    private void parseExchange(String text) {
        exchange.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            ExchangeRow r = new ExchangeRow();
            r.ori = cols[1];
            r.count = Math.max(1, toInt(cols, 2));
            r.wnsp = toInt(cols, 4);
            r.moFaChen = toInt(cols, 5);
            r.level = toInt(cols, 6);
            exchange.put(r.ori, r);
        }
    }

    private void parseJingLian(String text) {
        leaps.clear();
        refines.clear();
        if (text == null) {
            return;
        }
        int section = 0;
        for (String[] cols : hashRows(text)) {
            if ("JingLianAttrEnd".equals(cols[1]) || "JingLianEnd".equals(cols[1])) {
                section++;
                continue;
            }
            if (section == 1 && cols.length >= 12) {
                LeapRow r = new LeapRow();
                r.level = toInt(cols, 1);
                r.quality = toInt(cols, 2);
                r.exp = toInt(cols, 3);
                r.levelLimit = toInt(cols, 9);
                r.gold = toInt(cols, 10);
                for (int i = 0; i < 4; i++) {
                    int base = 11 + i * 2;
                    if (base + 1 < cols.length) {
                        String ori = cols[base];
                        int n = toInt(cols, base + 1);
                        if (ori != null && !"0".equals(ori) && n > 0) {
                            r.mats.add(new Mat(ori, n));
                        }
                    }
                }
                leaps.put(r.quality + "/" + r.level, r);
            } else if (section == 2 && cols.length >= 4) {
                RefineRow r = new RefineRow();
                r.quality = toInt(cols, 1);
                r.level = toInt(cols, 2);
                r.exp = toInt(cols, 3);
                refines.put(r.quality + "/" + r.level, r);
            }
        }
    }

    private void parseReset(String text) {
        resetStars.clear();
        resetJingLian.clear();
        resetBaseCost = 50;
        resetGiveGoods = "JLBS04";
        resetGiveGoodsRate = 9500;
        if (text == null) {
            return;
        }
        String[] first = firstTokens(text);
        if (first.length >= 2) {
            resetBaseCost = toInt(first, 1);
        }
        int section = 0;
        for (String line : text.split("\r?\n")) {
            String[] cols = split(line);
            if (cols.length < 2) {
                continue;
            }
            if ("#".equals(cols[0]) && "ResetStarEnd".equals(cols[1])) {
                section++;
                continue;
            }
            if ("@".equals(cols[0]) && cols.length >= 5) {
                resetGiveGoods = cols[2];
                resetGiveGoodsRate = toInt(cols, 4);
                section = 2;
                continue;
            }
            if (!"#".equals(cols[0]) || cols.length < 4) {
                continue;
            }
            ResetRow r = parseResetRow(cols);
            if (section == 0) {
                resetStars.put(r.quality + "/" + r.star, r);
            } else if (section >= 2) {
                resetJingLian.put(r.quality + "/" + r.star, r);
            }
        }
    }

    private static ResetRow parseResetRow(String[] cols) {
        ResetRow r = new ResetRow();
        r.quality = toInt(cols, 1);
        r.star = toInt(cols, 2);
        r.diamond = toInt(cols, 3);
        r.equipReturnRate = toInt(cols, 4);
        for (int i = 0; i < 4; i++) {
            int base = 5 + i * 2;
            String ori = base < cols.length ? cols[base] : "0";
            int rate = toInt(cols, base + 1);
            if (ori != null && !ori.isEmpty() && !"0".equals(ori) && rate != 0) {
                r.goodsRates.add(new Mat(ori, rate));
            }
        }
        return r;
    }

    private void parseTransform(String text) {
        transforms.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            TransformRow r = new TransformRow();
            r.from = cols[1];
            r.gold = toInt(cols, 2);
            r.to = cols[cols.length - 1];
            for (int i = 0; i < 4; i++) {
                int base = 3 + i * 2;
                if (base + 1 < cols.length - 1) {
                    String ori = cols[base];
                    int n = toInt(cols, base + 1);
                    if (ori != null && !"0".equals(ori) && n > 0) {
                        r.mats.add(new Mat(ori, n));
                    }
                }
            }
            transforms.put(r.from, r);
        }
    }

    private void parseXiLian(String text) {
        xiLian = new XiLianCommon();
        if (text == null) {
            return;
        }
        Map<String, String> kv = kvLines(text);
        xiLian.stoneOri = kv.getOrDefault("洗练石原始名", "XLS");
        xiLian.normalStone = toInt(kv.get("普通洗练消耗洗练石数量"), 2);
        xiLian.goldStone = toInt(kv.get("金币洗练消耗洗练石数量"), 1);
        xiLian.goldCost = toInt(kv.get("金币洗练消耗金币数量"), 1000);
        xiLian.rmbStone = toInt(kv.get("RMB洗练消耗洗练石数量"), 1);
        xiLian.rmbCost = toInt(kv.get("RMB洗练消耗RMB数量"), 20);
        xiLian.minAbs = toInt(kv.get("随机值值域绝对值下限"), 5);
        xiLian.maxAbs = toInt(kv.get("随机值值域绝对值上限"), 15);
        xiLian.normalPosWan = toInt(kv.get("普通洗练出现正值概率(万分率)"), 3000);
        xiLian.goldPosWan = toInt(kv.get("金币洗练出现正值概率(万分率)"), 5000);
        xiLian.rmbPosWan = toInt(kv.get("RMB洗练出现正值概率(万分率)"), 8000);
        xiLian.levelRange = toInt(kv.get("装备等级区分范围(对应的洗练值域提高)"), 20);
    }

    private void parseChest(String text) {
        chests.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            ChestRow r = new ChestRow();
            r.chapterId = toInt(cols, 1);
            r.baoxiangId = toInt(cols, 2);
            r.diamond = toInt(cols, 3);
            r.gold = toInt(cols, 4);
            for (int i = 0; i < 5; i++) {
                int base = 5 + i * 2;
                if (base + 1 >= cols.length) {
                    break;
                }
                String ori = cols[base];
                int n = toInt(cols, base + 1);
                if (ori != null && !"0".equals(ori) && n > 0) {
                    r.goods.add(new Mat(ori, n));
                }
            }
            chests.put(Integer.valueOf(r.chapterId * 1000 + r.baoxiangId), r);
        }
    }

    private void parseMine(String text) {
        mine = new MineCommon();
        if (text == null) {
            return;
        }
        Map<String, String> kv = kvLines(text);
        mine.buyZzDiamond = toInt(kv.get("一次性购买征战水晶消耗的钻石"), 100);
        mine.buyZzCount = toInt(kv.get("一次性购买征战水晶数量"), 20);
        mine.reliveDiamond = toInt(kv.get("英雄买活需要花费的钻石"), 120);
        mine.reliveSec = Math.max(0, toInt(kv.get("抢矿死亡复活时间(秒)"), 14400));
        mine.goldCap = toInt(kv.get("金矿单次掠夺金币上限"), 78300);
        mine.diamondCap = toInt(kv.get("金矿单次掠夺钻石上限"), 6);
        mine.silverGoldCap = toInt(kv.get("银矿单次掠夺金币上限"), 52200);
        mine.silverDiamondCap = toInt(kv.get("银矿单次掠夺钻石上限"), 4);
        mine.copperGoldCap = toInt(kv.get("铜矿单次掠夺金币上限"), 26100);
        mine.copperDiamondCap = toInt(kv.get("铜矿单次掠夺钻石上限"), 2);
        mine.occupyZz = toInt(kv.get("占领消耗的征战水晶"), 4);
        mine.robZz = toInt(kv.get("掠夺消耗的征战水晶"), 2);
        mine.openLevel = Math.max(0, toInt(kv.get("开放等级"), 28));
        mine.openAfterMainFb = Math.max(0, toInt(kv.get("开放通关ID"), 0));
        mine.robRatio = toDouble(kv.get("掠夺系数"), 0.5);
        mine.keepRatio = toDouble(kv.get("保留系数"), 0.5);
        mine.leaveKeepRatio = toDouble(kv.get("撤离矿时资源保留系数"), 0.4);
        mine.protectRobSec = Math.max(0, toInt(kv.get("成功掠夺保护时间(秒)"), 7200));
        mine.rebuildSec = Math.max(0, toInt(kv.get("重建保护时间(秒)"), 60));
        mine.recordKeepN = Math.max(1, toInt(kv.get("保留最近N场矿战记录"), 10));
    }

    /** QiangKuang.txt：矿ID / 类型 / 钻速(时) / 金速(时) / 可开采时长(时)。 */
    private void parseMineList(String text) {
        minesById.clear();
        mineIdsByType.clear();
        if (text == null) {
            return;
        }
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("#")) {
                continue;
            }
            String[] cols = t.split("[\t ]+");
            if (cols.length < 6) {
                continue;
            }
            try {
                MineRow r = new MineRow();
                r.id = Integer.parseInt(cols[1]);
                r.type = Integer.parseInt(cols[2]);
                r.diamondPerHour = Integer.parseInt(cols[3]);
                r.goldPerHour = Integer.parseInt(cols[4]);
                r.hours = Math.max(1, Integer.parseInt(cols[5]));
                minesById.put(Integer.valueOf(r.id), r);
                List<Integer> list = mineIdsByType.get(Integer.valueOf(r.type));
                if (list == null) {
                    list = new ArrayList<>();
                    mineIdsByType.put(Integer.valueOf(r.type), list);
                }
                list.add(Integer.valueOf(r.id));
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        for (List<Integer> list : mineIdsByType.values()) {
            Collections.sort(list);
        }
    }

    /** GlobalSetup_CH：征战水晶上限 / 回复秒数（登录 field41 倒计时权威）。 */
    private void parseZzFromGlobal(String text) {
        if (text == null) {
            return;
        }
        Map<String, String> kv = kvLines(text);
        mine.zzMax = Math.max(1, toInt(kv.get("征战水晶上限"), 20));
        mine.zzRestoreSec = Math.max(1, toInt(kv.get("回复1点征战水晶需要的时间(秒)"), 1800));
    }

    /**
     * 改名花费（钻）：GlobalSetup_CH「攻略组改名钻石花费」= 客户端 {@code GlobalSetup.mMainPlayerChangeNameCostRMB}
     * （{@code GlobalSetup.cs:120} 按 GlobalSetup 字段顺序读到该行；表值 100）。C2S 2101 结算用。
     */
    public int changeNameCostRmb = 100;

    private void parseChangeNameFromGlobal(String text) {
        if (text == null) {
            return;
        }
        changeNameCostRmb = Math.max(0, toInt(kvLines(text).get("攻略组改名钻石花费"), 100));
    }

    private void parseMajiu(String text) {
        if (text == null) {
            return;
        }
        Map<String, String> kv = kvLines(text);
        escortRaidTimes = Math.max(1, toInt(kv.get("单次活动有效掠夺场次（不管成功还是失败）"), 3));
        escortSendXdb = toInt(kv.get("发镖参与的兄弟币奖励（发镖参与奖）"), 100);
    }

    private void parseVip(String text) {
        vipByLevel.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            VipRow r = new VipRow();
            r.level = toInt(cols, 1);
            r.zuanShiNeeds = toInt(cols, 2);
            r.buyVpMaxTimesEveryday = toInt(cols, 5);
            r.jjcResetMaxCount = toInt(cols, 8);
            r.qkBuyReliveMax = toInt(cols, 9);
            r.buyJinBiMaxTimesEveryday = toInt(cols, 10);
            r.unionDonateMaxTimes = toInt(cols, 11);
            r.unionMercenaryCount = toInt(cols, 26);
            r.faBiaoCount = toInt(cols, 27);
            r.jieBiaoCount = toInt(cols, 28);
            r.bobResetMaxCount = toInt(cols, 25);
            r.occupyMineMax = toInt(cols, 22);
            r.inviteCoDefMax = toInt(cols, 23);
            r.joinCoDefMax = toInt(cols, 24);
            r.untouchableMax = toInt(cols, 12);
            r.mirrorMax = toInt(cols, 13);
            r.deadlyMax = toInt(cols, 14);
            r.scytheMax = toInt(cols, 15);
            r.santaMax = toInt(cols, 16);
            r.untouchableRate = toFloat(cols, 17);
            r.mirrorRate = toFloat(cols, 18);
            r.deadlyRate = toFloat(cols, 19);
            r.scytheRate = toFloat(cols, 20);
            r.santaRate = toFloat(cols, 21);
            r.bctSingleSaoDang = toInt(cols, 32) == 1;
            r.bctAllSaoDang = toInt(cols, 33) == 1;
            r.riChangHuoDongSaoDang = toInt(cols, 46) == 1;
            if (r.faBiaoCount <= 0) {
                r.faBiaoCount = 1;
            }
            // VipCfg：奖励道具1/2/3/4 = cols 34/37/40/43，星级+1、数量+2
            for (int i = 0; i < 4; i++) {
                int base = 34 + i * 3;
                String ori = col(cols, base);
                int count = toInt(cols, base + 2);
                if (ori == null || ori.isEmpty() || "0".equals(ori) || count <= 0) {
                    continue;
                }
                VipAward a = new VipAward();
                a.ori = ori;
                a.stars = toInt(cols, base + 1);
                a.count = count;
                r.awards.add(a);
            }
            vipByLevel.put(Integer.valueOf(r.level), r);
        }
    }

    private static String col(String[] cols, int i) {
        if (i >= cols.length || cols[i] == null) {
            return "";
        }
        return cols[i].trim();
    }

    public VipRow vipByLevel(int level) {
        return vipByLevel.get(Integer.valueOf(level));
    }

    private void parseBuilding(String text) {
        buildingChoulao.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            int type = toInt(cols, 1);
            int level = toInt(cols, 2);
            int choulao = toInt(cols, 8);
            buildingChoulao.put(type + "/" + level, Integer.valueOf(choulao));
        }
    }

    private void parseTeQuan(String text) {
        teQuanByType.clear();
        if (text == null) {
            return;
        }
        // 列（0 基）：0 输出符 / 1 类型 / 2 名称 / 3 有效时间(日) / 4 购买立即返钻 /
        // 5 购买立即返物品 / 6 数量 / 7 每日返钻 / 8 每日返物品 / 9 数量。
        // 客户端 TeQuanCardCfg.cs:87-91 同样按这个顺序读 LiJiFanWuPin/LiJiFanWuPinCnt/
        // PerDayFanWuPin/PerDayFanWuPinCnt；APK 自带表（gametext-tables\TeQuanCard.txt）两张卡
        // 的 5/6/8/9 全是 0，故解析出「无物品」是当前数据的正确结果。
        for (String[] cols : hashRows(text)) {
            TeQuanRow r = new TeQuanRow();
            r.type = toInt(cols, 1);
            r.days = toInt(cols, 3);
            r.buyDiamond = toInt(cols, 4);
            r.buyGoodsOri = toOri(cols, 5);
            r.buyGoodsCount = toInt(cols, 6);
            r.dailyDiamond = toInt(cols, 7);
            r.dailyGoodsOri = toOri(cols, 8);
            r.dailyGoodsCount = toInt(cols, 9);
            teQuanByType.put(Integer.valueOf(r.type), r);
        }
    }

    private void parseTimeStoneColor(String text) {
        timeStoneColorCost.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            int level = toInt(cols, 1);
            timeStoneColorCost.put(Integer.valueOf(level), new int[]{toInt(cols, 2), toInt(cols, 3)});
        }
    }

    /**
     * {@code TimeStoneCompose.txt}：表头「等级 消耗金币 暴击概率 第一次 第二次 第三次」。
     * <p>客户端 {@code TimeStoneComposeCfg.cs} 只读前两列（{@code num = 1; level; jinBiCost;}），
     * 后 4 列只有服务端用得上（客户端不显示任何概率）。
     * <p>第 7 行（等级 7）首列无 {@code #}，{@code hashRows} 会跳过 ⇒ 与客户端 {@code GetTimeStoneMaxLevel()=7}
     * 「满级不可再合」口径一致。
     */
    private void parseTimeStoneCompose(String text) {
        timeStoneComposeGold.clear();
        timeStoneCrit.clear();
        timeStoneCritRoll.clear();
        if (text == null) {
            return;
        }
        for (String[] cols : hashRows(text)) {
            int level = toInt(cols, 1);
            if (level > 0) {
                timeStoneComposeGold.put(Integer.valueOf(level), Integer.valueOf(toInt(cols, 2)));
                timeStoneCrit.put(Integer.valueOf(level), Float.valueOf(toFloat(cols, 3)));
                timeStoneCritRoll.put(Integer.valueOf(level),
                        Integer.valueOf((toInt(cols, 4) & 1) | ((toInt(cols, 5) & 1) << 1) | ((toInt(cols, 6) & 1) << 2)));
            }
        }
    }

    private void parseUnion(String text) {
        if (text == null) {
            return;
        }
        java.util.List<String> vals = new ArrayList<String>();
        for (String line : text.split("\r?\n")) {
            String[] cols = split(line);
            if (cols.length < 2) {
                continue;
            }
            for (int i = 1; i < cols.length; i++) {
                if (cols[i] != null && !cols[i].isEmpty()) {
                    vals.add(cols[i]);
                    break;
                }
            }
        }
        if (vals.size() > 12) {
            unionBossMaxTimes = Math.max(1, toInt(new String[]{vals.get(12)}, 0));
        }
    }

    private static Map<String, String> kvLines(String text) {
        Map<String, String> map = new HashMap<>();
        for (String line : text.split("\r?\n")) {
            String[] cols = split(line);
            if (cols.length < 2 || "#".equals(cols[0])) {
                continue;
            }
            String key = cols[0];
            String val = cols[cols.length - 1];
            map.put(key, val);
        }
        return map;
    }

    private static String[] firstTokens(String text) {
        for (String line : text.split("\r?\n")) {
            String[] cols = split(line);
            if (cols.length >= 2) {
                return cols;
            }
        }
        return new String[0];
    }

    private static List<String[]> hashRows(String text) {
        List<String[]> rows = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            String[] cols = split(line);
            if (cols.length > 1 && "#".equals(cols[0])) {
                rows.add(cols);
            }
        }
        return rows;
    }

    private static String[] split(String line) {
        return line.trim().split("[ \\t]+");
    }

    private static int toInt(String[] cols, int i) {
        if (i >= cols.length) {
            return 0;
        }
        return toInt(cols[i], 0);
    }

    /** 取 ori 列：缺列 / 空 / `"0"` 一律返回空串（表示「无此物品」）。 */
    private static String toOri(String[] cols, int i) {
        if (i >= cols.length || cols[i] == null) {
            return "";
        }
        String s = cols[i].trim();
        return "0".equals(s) ? "" : s;
    }

    private static float toFloat(String[] cols, int i) {
        if (i >= cols.length) {
            return 0f;
        }
        return (float) toDouble(cols[i], 0d);
    }

    private static int toInt(String s, int def) {
        if (s == null || s.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            try {
                return (int) Float.parseFloat(s.trim());
            } catch (NumberFormatException e2) {
                return def;
            }
        }
    }

    private static double toDouble(String s, double def) {
        if (s == null || s.isEmpty()) {
            return def;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static final class GoodsCfg {
        public String ori = "";
        /** GoodsList col2 显示名（邮件正文/战利品名用）。 */
        public String displayName = "";
        public int quality;
        public int goldPrice;
        /** GoodsList col8「公会拍卖基础价格（勇气币）」：作战室拍卖起拍价 + 公会商店兄弟币基价。 */
        public int auctionPrice;
        /** GoodsList col9「道具显示位置」：1 其他 / 2 魂石 / 3 装备 / 4 进阶 / 5 时光 / 6 宝箱。 */
        public int slot;
        /** GoodsList col10「产出位置」：1 关卡 / 2 竞技场兑换 / 3 神秘商店兑换 / 4 公会商店产出 / 5 特殊活动 / 6 宝物转换。 */
        public int source;
        public int attrType;
        public int attrP1;
        /**
         * GoodsList col12「属性自定义参数1」原文（如时光石 {@code 1_1_0} = 颜色_等级_卸下消耗钻石）。
         * {@link #attrP1} 走 {@code toInt} 只拿得到纯数字，拿不到子段。
         */
        public String attrP1Raw = "";
        /**
         * GoodsList col13「属性自定义参数2」原文（喇叭道具用）。
         * <p>表头 {@code GoodsList.txt:5} 原文：{@code 12.喇叭（1|0|0|36：雪花类型|场景特效|头顶特效|Notifycfg对应ID）}。
         * <p>客户端 {@code BagUISystem.cs:468} 把它按 {@code |} 切开：第 1 段决定发 4701 还是 4702，
         * 第 2 段是场景天气特效名（S2C 5202 handler 用它调 {@code EnableWeather}），
         * 第 3 段是头顶特效名（{@code EnableHeadIcon}），第 4 段是 Notifycfg id（客户端不消费）。
         * <p>出厂表里只有 {@code GOODS250}（大雪花，{@code 1|0|0|36}）与
         * {@code GOODS251}（六角雪花，{@code 2|eff_sc_sd_xiaxue|eff_buff_xuehua|37}）。
         */
        public String attrP2Raw = "";
        /**
         * 时光石卸下消耗钻石 = col12 第三段：客户端 {@code GoodsPropertyCfg.cs:29-36}
         * 把 customParam1 按 {@code _} 切开取第三段当 {@code mRemoveCost}，
         * {@code RemoveTimeRock.cs:67-76} 按它扣钻石后才发 2901。非时光石恒 0。
         */
        public int removeCost;
        /**
         * GoodsList 第 15 字段「器魂吞噬经验值」= cols[14]：这件道具作为器魂吞噬材料被吃掉时
         * 提供多少器魂经验。0 = 不是器魂材料（客户端 {@code QiHunSys.cs:422} 只把 &gt;0 的列进吞噬列表）。
         */
        public int soulExp;
    }

    /** {@code CCMsgChoiceBaoXiangCell}：type 1 整装 / 2 武将 index / 3 道具。 */
    public static final class ChoiceCell {
        public int id;
        public int type;
        public String goodId = "";
        public int amount = 1;
        public int star;
    }

    /** 商店货架候选（按店拆池，带开启等级）。 */
    public static final class ShopOffer {
        public String ori = "";
        public int minLevel = 1;
        public int weight = 1;
        public int countMin = 1;
        public int countMax = 1;
        public boolean preferRmb;
        /** 固定售价；0 = 由 {@code ShopService} 按品质/金币价推算。公会店用 GoodsList col8 兄弟币基价。 */
        public int price;
    }

    public static final class BuyRow {
        public int times;
        public int cost;
        public int gain;
    }

    public static final class PayRow {
        public int id;
        public int type;
        public int rmb;
        public int diamond;
        public int award;
        public int extAward;
        public int extTimes;

        public int grant(boolean first) {
            int n = diamond + award;
            if (first && extTimes > 0) {
                n += extAward;
            }
            return Math.max(0, n);
        }
    }

    public static final class VipRow {
        public int level;
        public int zuanShiNeeds;
        /** VipCfg col5 体力购买次数。 */
        public int buyVpMaxTimesEveryday;
        /** VipCfg col10 金币购买次数。 */
        public int buyJinBiMaxTimesEveryday;
        /** VipCfg col11 公会捐赠次数（当日上限，客户端 UnionManagerSystem 显示「剩余 = 上限 − 已捐」）。 */
        public int unionDonateMaxTimes = 3;
        /** VipCfg col26 公会佣兵数量（单建筑佣兵格位上限；客户端 VipManager.UnionMercenaryCount）。 */
        public int unionMercenaryCount = 2;
        /** VipCfg col8 竞技场重置次数。 */
        public int jjcResetMaxCount;
        /** VipCfg col25 BOB 重置次数。 */
        public int bobResetMaxCount;
        /** VipCfg col9 矿战买活次数。 */
        public int qkBuyReliveMax = 2;
        /** VipCfg col22 占领矿数量。 */
        public int occupyMineMax = 2;
        /** VipCfg col23 邀请协防数量。 */
        public int inviteCoDefMax;
        /** VipCfg col24 参与协防数量。 */
        public int joinCoDefMax = 1;
        /** VipCfg col12–16 资源本每日次数。 */
        public int untouchableMax = 2;
        public int mirrorMax = 2;
        public int deadlyMax = 2;
        public int scytheMax = 2;
        public int santaMax = 2;
        public float untouchableRate = 1f;
        public float mirrorRate = 1f;
        public float deadlyRate = 1f;
        public float scytheRate = 1f;
        public float santaRate = 1f;
        /** VipCfg col46 日常活动扫荡。 */
        public boolean riChangHuoDongSaoDang;
        /** VipCfg col32/33 百层塔单扫 / 一键扫荡。 */
        public boolean bctSingleSaoDang;
        public boolean bctAllSaoDang;
        public int faBiaoCount = 1;
        public int jieBiaoCount = 3;
        public final List<VipAward> awards = new ArrayList<>();
    }

    public static final class VipAward {
        public String ori = "";
        public int stars;
        public int count;
    }

    public static final class TeQuanRow {
        public int type;
        public int days;
        public int buyDiamond;
        public int dailyDiamond = 120;
        /** 购买立即返物品的 ori（列 5），空/`"0"` = 无物品。 */
        public String buyGoodsOri = "";
        /** 购买立即返物品数量（列 6）。 */
        public int buyGoodsCount;
        /** 每日返物品的 ori（列 8），空/`"0"` = 无物品。 */
        public String dailyGoodsOri = "";
        /** 每日返物品数量（列 9）。 */
        public int dailyGoodsCount;
    }

    public static final class ShopCfg {
        public int type;
        public int openLevel;
        /** ShopCommom 货物刷新整点（0–23；-1 占位已丢掉）。 */
        public int[] refreshHours = new int[0];
        public int freeRefresh;
        /**
         * ShopCommom 第 16 列「重置每日免费刷新次数时间」（小时，五行均为 5）。
         *
         * <p>客户端 {@code ShopPropertyCfg.cs:26 mResetDailyFreeRefreshTime} 解析了该列但**无消费方**
         * ⇒ 重置时刻纯服务端口径；改前服务端把它写死成 0 点日历日，现按本列取「商店游戏日」界
         * （见 {@code ProgressService.resetShopFreeRefreshDaily}）。列缺失/越界时回落到 5。
         */
        public int freeRefreshResetHour = DEFAULT_FREE_REFRESH_RESET_HOUR;
        public int fieldCount;
        public int refreshDiamond;
    }

    /** {@code ShopCommom} 第 16 列缺失时的回落值（表内五行均为 5）。 */
    public static final int DEFAULT_FREE_REFRESH_RESET_HOUR = 5;

    public static final class ExchangeRow {
        public String ori = "";
        public int count;
        public int wnsp;
        public int moFaChen;
        public int level;
    }

    public static final class LeapRow {
        public int level;
        public int quality;
        public int exp;
        public int levelLimit;
        public int gold;
        public final List<Mat> mats = new ArrayList<>();
    }

    public static final class RefineRow {
        public int quality;
        public int level;
        public int exp;
    }

    public static final class TransformRow {
        public String from = "";
        public String to = "";
        public int gold;
        public final List<Mat> mats = new ArrayList<>();
    }

    public static final class ResetRow {
        public int quality;
        public int star;
        public int diamond;
        /** 万分率：同名装备返还。 */
        public int equipReturnRate;
        /** ori + count 存万分率。 */
        public final List<Mat> goodsRates = new ArrayList<>();
    }

    public static final class ChestRow {
        public int chapterId;
        public int baoxiangId;
        public int diamond;
        public int gold;
        public final List<Mat> goods = new ArrayList<>();
    }

    public static final class XiLianCommon {
        public String stoneOri = "XLS";
        public int normalStone = 2;
        public int goldStone = 1;
        public int goldCost = 1000;
        public int rmbStone = 1;
        public int rmbCost = 20;
        public int minAbs = 5;
        public int maxAbs = 15;
        public int normalPosWan = 3000;
        public int goldPosWan = 5000;
        public int rmbPosWan = 8000;
        /** 表有「装备等级区分范围」，工程/APK C# 无公式；未用于掷值。 */
        public int levelRange = 20;
    }

    public static final class MineCommon {
        public int buyZzDiamond = 100;
        public int buyZzCount = 20;
        public int reliveDiamond = 120;
        /** QiangKuang_Common「抢矿死亡复活时间(秒)」默认 14400。 */
        public int reliveSec = 14400;
        public int goldCap = 78300;
        public int diamondCap = 6;
        public int silverGoldCap = 52200;
        public int silverDiamondCap = 4;
        public int copperGoldCap = 26100;
        public int copperDiamondCap = 2;
        public int occupyZz = 4;
        public int robZz = 2;
        /** QiangKuang_Common「开放等级」。 */
        public int openLevel = 28;
        /** QiangKuang_Common「开放通关ID」（0=只看等级）。 */
        public int openAfterMainFb;
        /** QiangKuang_Common「掠夺系数」默认 0.5。 */
        public double robRatio = 0.5;
        /** 「保留系数」默认 0.5。 */
        public double keepRatio = 0.5;
        /** 「撤离矿时资源保留系数」默认 0.4。 */
        public double leaveKeepRatio = 0.4;
        /** 「成功掠夺保护时间(秒)」默认 7200。 */
        public int protectRobSec = 7200;
        /** 「重建保护时间(秒)」默认 60。 */
        public int rebuildSec = 60;
        /** 「保留最近N场矿战记录」默认 10。 */
        public int recordKeepN = 10;
        /** GlobalSetup「征战水晶上限」默认 20。 */
        public int zzMax = 20;
        /** GlobalSetup「回复1点征战水晶需要的时间(秒)」默认 1800。 */
        public int zzRestoreSec = 1800;

        public int occupyGoldCap(int type) {
            if (type == 2) {
                return silverGoldCap;
            }
            if (type == 3) {
                return copperGoldCap;
            }
            return goldCap;
        }

        public int occupyDiamondCap(int type) {
            if (type == 2) {
                return silverDiamondCap;
            }
            if (type == 3) {
                return copperDiamondCap;
            }
            return diamondCap;
        }

        /** 单次掠夺上限（QiangKuang_Common），非占矿产出。 */
        public int robGoldCap(int type) {
            return occupyGoldCap(type);
        }

        public int robDiamondCap(int type) {
            return occupyDiamondCap(type);
        }
    }

    /** QiangKuang.txt 一行。 */
    public static final class MineRow {
        public int id;
        public int type;
        public int diamondPerHour;
        public int goldPerHour;
        /** 可开采时长（小时）。 */
        public int hours = 12;

        public long fillMs() {
            return hours * 3600L * 1000L;
        }
    }

    public static final class Mat {
        public final String ori;
        public final int count;

        public Mat(String ori, int count) {
            this.ori = ori;
            this.count = count;
        }
    }
}
