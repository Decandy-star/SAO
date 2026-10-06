package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.store.WorldStore;
import com.sao.fakeserver.table.KfHappyCfg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 开服狂欢：7 日狂欢（C2S 5401 → S2C 6201）与半月庆典（C2S 5402 → S2C 6202）、
 * 领取（C2S 5403 → S2C 6203）。
 * <p>
 * 修复依据：{@code docs/PROTOCOL_GAP_REPORT.md} §2.1 簇 8 与 §6 第 2 条 ——
 * 这两条消息原本在 {@code MessageDispatcher} 里是<b>空 case</b>（与 {@code C2S_LEVEL_LOAD_FINISH}
 * 共用 fallthrough），客户端进大厅/进区域就发 5401+5402，不回包导致
 * {@code ActivityPropertyMgr.cs:1159/1384} 永不执行 ⇒ {@code KFHappyMainUI.cs:2047}
 * {@code Count == 0 → return} ⇒ <b>面板整块空白</b>。
 * <p>
 * ⚠️ 只补 6201/6202 而不补 5403 会形成「一级缺口掩盖二级缺口」：列表修好后玩家点领取仍然
 * 无反应、无奖励、不复位（{@code KFHappy_LingQuItem.cs:405/:452}）。三者必须一起补。
 * <p>
 * 三条装配硬约束见 {@link KfHappyCfg} 的类注释（7 条 / day∈[8,14] / YeQian 四条且首条 ID=1）。
 */
@Service
public class KfHappyService {
    private static final Logger log = LoggerFactory.getLogger(KfHappyService.class);
    private static final String TIME = "yyyy-MM-dd HH:mm:ss";

    private final PlayerStore store;
    private final PlayerDumpService dump;
    private final ProgressService progress;
    private final KfHappyCfg cfg;
    private final WorldStore world;

    public KfHappyService(PlayerStore store, PlayerDumpService dump, ProgressService progress, KfHappyCfg cfg,
                          WorldStore world) {
        this.store = store;
        this.dump = dump;
        this.progress = progress;
        this.cfg = cfg;
        this.world = world;
    }

    /** C2S 5401 7 日狂欢：发满 7 条（day=1..7），客户端拿 {@code Count} 当「当前天」。 */
    public void onSevenDays(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null || !cfg.isEnabled()) {
            return;
        }
        rec.ensureCollections();
        int cur = world.openDay();
        List<byte[]> days = new ArrayList<>();
        for (int day = 1; day <= KfHappyCfg.SEVEN_DAY_COUNT; day++) {
            days.add(dump.kfHappyOneDay(oneDay(rec, day, cur)));
        }
        session.send(MsgIds.S2C_KF_HAPPY_7_RET, pkt, dump.kfHappySomeDayRet(days));
    }

    /** C2S 5402 半月庆典：包体就是一个 day 包，day 必须落在 [8,14]。 */
    public void onHalfMonth(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null || !cfg.isEnabled()) {
            return;
        }
        rec.ensureCollections();
        int cur = world.openDay();
        int day = Math.max(KfHappyCfg.HALF_MONTH_FROM, Math.min(KfHappyCfg.HALF_MONTH_TO, cur));
        session.send(MsgIds.S2C_KF_HAPPY_15_RET, pkt, dump.kfHappyOneDay(oneDay(rec, day, cur)));
    }

    /**
     * C2S 5403 领取一档。
     * <p>{@code CCMsgKFHappyGetOneAward}：1 day / 2 type / 3 ID（都是 uint）。
     * <p>GetState 回填口径（与下发时同一套判定，客户端 {@code ActivityPropertyMgr.cs:1403-1408}
     * 把结果写回列表项）：
     * <ul>
     *   <li>参数不合法 / 该档不存在 ⇒ 1（条件未达成）</li>
     *   <li>已经领过 ⇒ 3（已领取）</li>
     *   <li>开服天数还没到那一天 ⇒ 1</li>
     *   <li>否则发奖 + 记档 ⇒ 3</li>
     * </ul>
     * 注意<b>不发 2</b>：2 是「可领取」的展示态，只有下发 6201/6202 时才用。
     */
    public void onGetAward(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null || !cfg.isEnabled()) {
            return;
        }
        rec.ensureCollections();
        Pb.Fields f = Pb.read(pkt.body);
        int day = f.getInt(1, 0);
        int type = f.getInt(2, 0);
        int id = f.getInt(3, 0);
        KfHappyCfg.TypeCfg tc = cfg.typeOf(type);
        boolean dayOk = tc != null
                && ((day >= 1 && day <= KfHappyCfg.SEVEN_DAY_COUNT)
                    || (day >= KfHappyCfg.HALF_MONTH_FROM && day <= KfHappyCfg.HALF_MONTH_TO));
        if (!dayOk || id <= 0) {
            session.send(MsgIds.S2C_KF_HAPPY_GET_RET, pkt, dump.kfHappyGetAwardRet(day, type, id, 1));
            return;
        }
        if (isClaimed(rec, day, type, id)) {
            session.send(MsgIds.S2C_KF_HAPPY_GET_RET, pkt, dump.kfHappyGetAwardRet(day, type, id, 3));
            return;
        }
        if (day > world.openDay()) {
            // 还没到那一天 —— 客户端 KFHappy_LingQuItem.cs:399-416 也不会在这时发 5403，
            // 这里是改包/时序兜底。
            session.send(MsgIds.S2C_KF_HAPPY_GET_RET, pkt, dump.kfHappyGetAwardRet(day, type, id, 1));
            return;
        }
        Map<String, Integer> changed = progress.emptyChanged();
        int mul = cfg.isDayMultiplier() ? day : 1;
        if (tc.jinbi > 0) {
            rec.gold += tc.jinbi * mul;
        }
        if (tc.zuanshi > 0) {
            rec.diamond += tc.zuanshi * mul;
        }
        for (KfHappyCfg.Goods g : tc.goods) {
            if (g == null || g.oriName == null || g.oriName.isEmpty()) {
                continue;
            }
            int n = Math.max(1, g.count) * mul;
            progress.grantReward(rec, g.oriName, n, changed);
        }
        rec.activity.kfHappyClaimed = markClaimed(rec, day, type, id);
        store.save(rec);
        if (tc.jinbi > 0) {
            progress.pushGold(session, pkt, rec);
        }
        if (tc.zuanshi > 0) {
            progress.pushDiamond(session, pkt, rec);
        }
        progress.pushGoods(session, pkt, rec, changed);
        session.send(MsgIds.S2C_KF_HAPPY_GET_RET, pkt, dump.kfHappyGetAwardRet(day, type, id, 3));
        log.info("{} kf happy claim day={} type={} id={} gold={} diamond={}",
                rec.account, Integer.valueOf(day), Integer.valueOf(type), Integer.valueOf(id),
                Integer.valueOf(tc.jinbi * mul), Integer.valueOf(tc.zuanshi * mul));
    }

    /** 组一个 day 包：4 个 YeQian + 每种 type 一条（state 按「已领 / 到天可领 / 未到天」判定）。 */
    private PlayerDumpService.KfHappyDay oneDay(PlayerRecord rec, int day, int cur) {
        PlayerDumpService.KfHappyDay d = new PlayerDumpService.KfHappyDay();
        d.day = day;
        String end = activityEnd();
        d.activityEndTime = end;
        d.lingQuEndTime = end;
        int yi = 1;
        for (KfHappyCfg.YeQian y : cfg.yeQian()) {
            PlayerDumpService.KfHappyYeQian e = new PlayerDumpService.KfHappyYeQian();
            // 第一条必须是 ID=1：客户端 KFHappyMainUI.cs:453-568 用它填 4 个 SubTab 名称<b>并</b>
            // 注册点击字典，第一条 ID 不为 1 会让 :585-593 的 TryGetValue 全部 miss，
            // switch 不命中 ⇒ 正文面板永不构建（这才是「面板空白」的第二层原因）。
            e.id = yi == 1 ? 1 : Math.max(1, y.id);
            e.name = y.name == null ? "" : y.name;
            d.yeQian.add(e);
            yi++;
        }
        for (KfHappyCfg.TypeCfg tc : cfg.types()) {
            PlayerDumpService.KfHappyEntry e = new PlayerDumpService.KfHappyEntry();
            e.type = tc.type;
            e.id = 1;
            e.text = tc.text == null ? "" : tc.text;
            int mul = cfg.isDayMultiplier() ? day : 1;
            // 半价折扣 / 物品售卖是「花钻买」，不是「免费领」：这里不把 xianjia 当奖励下发，
            // 只把标价带上让客户端自己显示（客户端 newPrice 分支在协议里根本没有字段，
            // 报价走 yuanjia/xianjia 两列，见 KFHappy_LingQuItem.cs:420-452）。
            e.jinbi = tc.jinbi > 0 ? tc.jinbi * mul : 0;
            e.zuanshi = tc.zuanshi > 0 ? tc.zuanshi * mul : 0;
            e.yuanjia = tc.yuanjia;
            e.xianjia = tc.xianjia;
            int state;
            if (isClaimed(rec, day, tc.type, 1)) {
                state = 3;
            } else if (day <= cur) {
                state = 2;
            } else {
                state = 1;
            }
            e.state = state;
            if (KfHappyCfg.hasFen(tc.type)) {
                // 没有真实进度来源（等级/星级/战力/玩法次数都需要跨系统取数），
                // 与 state 保持自洽：可领/已领 = 满进度，未到天 = 0/N。
                e.fenzi = day <= cur ? 1 : 0;
                e.fenmu = 1;
            }
            for (KfHappyCfg.Goods g : tc.goods) {
                if (g == null || g.oriName == null || g.oriName.isEmpty()) {
                    continue;
                }
                PlayerDumpService.KfHappyAward a = new PlayerDumpService.KfHappyAward();
                a.ori = g.oriName;
                a.count = Math.max(1, g.count) * mul;
                a.stars = g.stars;
                e.goods.add(a);
            }
            d.entries.add(e);
        }
        return d;
    }

    /**
     * 活动结束时间：<b>开服日</b> + 14 天 23:59:59（半月档的尾巴），
     * 格式必须是客户端 {@code DateTime.ParseExact} 认的那种。
     */
    private String activityEnd() {
        return world.openServerDate().plusDays(KfHappyCfg.HALF_MONTH_TO - 1).atTime(23, 59, 59)
                .format(java.time.format.DateTimeFormatter.ofPattern(TIME));
    }

    private boolean isClaimed(PlayerRecord rec, int day, int type, int id) {
        return rec.activity.kfHappyClaimed != null
                && rec.activity.kfHappyClaimed.contains(claimKey(day, type, id));
    }

    private String markClaimed(PlayerRecord rec, int day, int type, int id) {
        String s = rec.activity.kfHappyClaimed == null ? "" : rec.activity.kfHappyClaimed;
        return s + claimKey(day, type, id);
    }

    private static String claimKey(int day, int type, int id) {
        return day + "|" + type + "|" + id + ";";
    }
}
