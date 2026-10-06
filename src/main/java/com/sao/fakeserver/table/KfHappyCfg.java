package com.sao.fakeserver.table;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sao.fakeserver.config.SaoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 开服狂欢（7 日狂欢 C2S 5401→S2C 6201 / 半月庆典 5402→6202 / 领取 5403→6203）
 * 的兜底数值：{@code tables/act-kfhappy.json}。
 * <p>
 * <b>为什么是兜底而不是读表</b>：APK 与假服都<b>没有</b>这个活动的数值表 ——
 * {@code tables\} 下原本无任何 kfhappy 配置，客户端只有纯文案
 * {@code gametext\...\HalfMonthCelebrationDesc.txt}（5 行 5500001..5500005），
 * {@code activities.json} 的 type24/25 与本功能也无关。
 * 详见 {@code docs/PROTOCOL_GAP_REPORT.md} §2.1 簇 8 与 §6 第 2 条。
 * <p>
 * 协议结构（逐字段读自 {@code pyfoot\tmp_msgdll\NetProto\}）：
 * <ul>
 *   <li>S2C 6201 {@code CCMsgHappySomeDayActivity}：唯一字段 tag1 repeated {@code CCMsgKFHappyOneDayActivity}。</li>
 *   <li>S2C 6202 {@code CCMsgKFHappyHalfMonthActivity_ret}：直接就是一个 {@code CCMsgKFHappyOneDayActivity}。</li>
 *   <li>S2C 6203 {@code CCMsgKFHappyGetOneAwardRet}：1 day / 2 type / 3 ID / 4 GetState。</li>
 * </ul>
 * <b>type 号 ≠ tag 号</b>（客户端 {@code ActivityPropertyMgr.cs:1409-1500} 的 switch 按 type 找列表）：
 * type1→tag5 每日福利、type2→tag8 关卡挑战、type3→tag6 等级突破、type4→tag11 星级突破、
 * type5→tag9 玩法挑战、type6→tag7 半价折扣、type7→tag12 战力突破、type8→tag10 物品售卖。
 * 这一层映射写死在 {@code PlayerDumpService.kfHappyOneDay}，本类只负责数值。
 * <p>
 * 三条装配硬约束（客户端会崩/白屏，务必守住）：
 * <ol>
 *   <li>6201 必须发满 7 条（day=1..7）—— {@code KFHappyMainUI.cs:160-167} 用 {@code Count} 当「当前天」，
 *       {@code :222-228}、{@code :629-644} 直接按索引取。</li>
 *   <li>6202 的 day 必须 ∈ [8,14] —— {@code :231-270} 只建 8..14 的键。</li>
 *   <li>YeQian 必须 4 条且第一条 ID==1 —— {@code :453-568} 用它填 4 个 SubTab 名称并注册点击字典，
 *       空则 {@code :585-593 TryGetValue} 全 miss ⇒ 正文面板永不构建。</li>
 * </ol>
 * 另 {@code ActivityEndTime}/{@code LingQuEndTime} 必须是合法 {@code yyyy-MM-dd HH:mm:ss}：
 * 两个属性都有 {@code = ""} 初始化器 + {@code [DefaultValue("")]}，所以客户端 {@code == null} 判假，
 * 空串会直接送进 {@code ParseExact} 抛 {@code FormatException}。
 */
@Component
public class KfHappyCfg {
    private static final Logger log = LoggerFactory.getLogger(KfHappyCfg.class);
    private static final String FILE = "act-kfhappy.json";

    /** 7 日狂欢的天数（day=1..7）。 */
    public static final int SEVEN_DAY_COUNT = 7;
    /** 半月庆典的第一天 / 最后一天（客户端只建 8..14 的键）。 */
    public static final int HALF_MONTH_FROM = 8;
    public static final int HALF_MONTH_TO = 14;

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final SaoProperties props;

    private boolean enabled = true;
    /** 每天的奖励是否 = 基础值 × day（让后一天比前一天厚一点，纯观感）。 */
    private boolean dayMultiplier = true;
    private final List<YeQian> yeQian = new ArrayList<>();
    private final List<TypeCfg> types = new ArrayList<>();

    public KfHappyCfg(SaoProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        fallback();
        Path file = Paths.get(props.getTablesDir()).resolve(FILE);
        if (!Files.isRegularFile(file)) {
            log.warn("missing {} — 开服狂欢使用内置兜底数值", file);
            return;
        }
        try {
            Root r = mapper.readValue(file.toFile(), Root.class);
            if (r == null) {
                return;
            }
            enabled = r.enabled;
            dayMultiplier = r.dayMultiplier;
            if (r.yeQian != null && !r.yeQian.isEmpty()) {
                yeQian.clear();
                for (YeQian y : r.yeQian) {
                    if (y != null) {
                        yeQian.add(y);
                    }
                }
            }
            if (r.types != null && !r.types.isEmpty()) {
                types.clear();
                for (TypeCfg t : r.types) {
                    if (t != null && tagOf(t.type) != 0) {
                        types.add(t);
                    }
                }
            }
            log.info("act-kfhappy enabled={} yeQian={} types={}", Boolean.valueOf(enabled),
                    Integer.valueOf(yeQian.size()), Integer.valueOf(types.size()));
        } catch (IOException e) {
            log.warn("read {} failed: {} — 使用内置兜底数值", file, e.toString());
        }
    }

    private void fallback() {
        yeQian.clear();
        yeQian.add(new YeQian(1, "每日福利"));
        yeQian.add(new YeQian(2, "关卡挑战"));
        yeQian.add(new YeQian(3, "等级突破"));
        yeQian.add(new YeQian(4, "战力突破"));
        types.clear();
        types.add(type(1, 10000, 0, 0, 0, "每日登录即可领取", "PY001", 2));
        types.add(type(2, 20000, 20, 0, 0, "通关指定关卡即可领取", null, 0));
        types.add(type(3, 20000, 20, 0, 0, "主角达到指定等级", null, 0));
        types.add(type(4, 20000, 20, 0, 0, "武将升至指定星级", null, 0));
        types.add(type(5, 20000, 20, 0, 0, "参与指定玩法", null, 0));
        types.add(type(6, 0, 0, 100, 50, "限时半价购买", null, 0));
        types.add(type(7, 20000, 20, 0, 0, "战力达到指定值", null, 0));
        types.add(type(8, 0, 0, 200, 100, "超值礼包限量售卖", null, 0));
    }

    private static TypeCfg type(int type, int jinbi, int zuanshi, int yuanjia, int xianjia,
                               String text, String ori, int count) {
        TypeCfg t = new TypeCfg();
        t.type = type;
        t.jinbi = jinbi;
        t.zuanshi = zuanshi;
        t.yuanjia = yuanjia;
        t.xianjia = xianjia;
        t.text = text;
        if (ori != null && !ori.isEmpty()) {
            Goods g = new Goods();
            g.oriName = ori;
            g.count = count;
            t.goods.add(g);
        }
        return t;
    }

    /**
     * 语义 type → 协议 tag 号（客户端列表字段）。<b>这张表不能凭猜测改</b>，
     * 依据是 {@code ActivityPropertyMgr.cs:1409-1500} 的 switch 与 {@code :1127-1158} 的赋值。
     *
     * @return 0 = 未知 type
     */
    public static int tagOf(int type) {
        switch (type) {
            case 1: return 5;   // MeiRiFuli 每日福利
            case 2: return 8;   // LevelChallenge 关卡挑战
            case 3: return 6;   // DengjiTuPo 等级突破
            case 4: return 11;  // StarTuPo 星级突破
            case 5: return 9;   // PlayChallenge 玩法挑战
            case 6: return 7;   // HalfDiscount 半价折扣
            case 7: return 12;  // FightPowerTuPo 战力突破
            case 8: return 10;  // GoodsSale 物品售卖
            default: return 0;
        }
    }

    /** 该 type 是否带 fenzi/fenmu 两列。type2(关卡挑战) 与 type6(半价) 没有这两列。 */
    public static boolean hasFen(int type) {
        return type != 2 && type != 6;
    }

    /** 该 type 是否带 yuanjia/xianjia 两列（半价折扣 6 / 物品售卖 8）。 */
    public static boolean hasPrice(int type) {
        return type == 6 || type == 8;
    }

    public boolean isEnabled() {
        return enabled && !types.isEmpty();
    }

    public List<YeQian> yeQian() {
        return yeQian;
    }

    public List<TypeCfg> types() {
        return types;
    }

    public TypeCfg typeOf(int type) {
        for (TypeCfg t : types) {
            if (t.type == type) {
                return t;
            }
        }
        return null;
    }

    public boolean isDayMultiplier() {
        return dayMultiplier;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Root {
        public boolean enabled = true;
        public boolean dayMultiplier = true;
        public int sevenDayEndDays = SEVEN_DAY_COUNT;
        public List<YeQian> yeQian = new ArrayList<>();
        public List<TypeCfg> types = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class YeQian {
        public int id;
        public String name = "";

        public YeQian() {
        }

        public YeQian(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TypeCfg {
        public int type;
        public String name = "";
        public String text = "";
        public int jinbi;
        public int zuanshi;
        public int yuanjia;
        public int xianjia;
        public List<Goods> goods = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Goods {
        public String oriName = "";
        public int count = 1;
        public int stars;
    }
}
