package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.store.SignInMonthStore;
import com.sao.fakeserver.table.EconomyTables;
import com.sao.fakeserver.table.SignInTables;
import com.sao.fakeserver.util.GameTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 日历签到 C2S 1401/1402 → S2C 1801/1803。
 * 模板读 tables/QianDao.txt；英雄碎片日由 SignInMonthStore 月初随机一枚替换（当月固定）。
 * <p>
 * 每日状态（下发客户端 mPrizeStatus）：1=未领取、2=已领取、3=已领取(VIP 双倍)。
 * 1801 只下发 1..今天（长度=日号，对齐 APK Count=今天）。
 * 补签：{@link #ALLOW_MAKEUP} 已定**不接**（VipCfg 无补签列，客户端也没有补签 UI/文案）。
 * 因此过去未领的格子在 {@code PlayerDumpService.qianDaoInfo} 下发时翻成 2，否则 APK 会把它
 * 画成可领并真发 1402（死点击）。
 * 服务端存档仍按整月；不下发 0 —— APK 把 0 画成已领取图章。
 */
@Service
public class SignInService {
    private static final Logger log = LoggerFactory.getLogger(SignInService.class);

    /** 兼容旧档：旧版用 0 表「过期/未到」，APK 客户端会渲染成已领取章，故不再下发。 */
    private static final int NOCAN = 0;
    /** 未领取：客户端显示可领取底板（不盖章）。 */
    static final int NOPRIZED = 1;
    /** 已领取（普通）：客户端盖章。 */
    static final int NORMAL = 2;
    /** 已领取（VIP 双倍）：客户端盖章。 */
    private static final int DOUBLE = 3;
    /** 补签（漏天未领再点 1402）。已定不接：VipCfg 无此特权列、客户端无补签 UI/文案；过去未领格在下发时翻成已领。 */
    private static final boolean ALLOW_MAKEUP = false;

    private final PlayerStore store;
    private final PlayerDumpService dump;
    private final ProgressService progress;
    private final SignInTables tables;
    private final EconomyTables economy;
    private final SignInMonthStore monthStore;

    public SignInService(PlayerStore store, PlayerDumpService dump, ProgressService progress,
                         SignInTables tables, EconomyTables economy, SignInMonthStore monthStore) {
        this.store = store;
        this.dump = dump;
        this.progress = progress;
        this.tables = tables;
        this.economy = economy;
        this.monthStore = monthStore;
    }

    public void onInfo(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        refreshMonth(rec);
        store.save(rec);
        session.send(MsgIds.S2C_QIANDAO_INFO, pkt, dump.qianDaoInfo(rec));
        // 这里**不能**再 pushTipsIfNeed：
        // APK 的 SignInSystem.OnEvent（MobileGameDemo\SignInSystem.cs:258-263）在**面板打开时**
        // 收到 1802（EN_SIGNIN_NOTIFY）会立刻重发 1401；而 1401 又回 1801+1802 →
        // 只要今天有可领奖励就变成无上限的 1401/1801/1802 请求循环。
        // 1802 只该在「面板基本关着」的时机推：LoginService.java:147 账号进场、
        // LoginService.java:69-78 跨日心跳、LoginService.java:200 创角延迟。
        log.info("{} qiandao info {}-{} total={}", rec.account, rec.signIn.year, rec.signIn.month, rec.signIn.totalDays);
    }

    public void onPrize(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        refreshMonth(rec);
        int day = Pb.read(pkt.body).getInt(1, 0);
        int todayDay = GameTime.today().getDayOfMonth();
        if (day < 1 || day > todayDay || day > rec.signIn.prizeStatus.size()) {
            log.info("{} qiandao prize reject day={} today={}", rec.account, day, todayDay);
            return;
        }
        if (!ALLOW_MAKEUP && day != todayDay) {
            // APK 侧过去未领的格子被画成「可领」且点击真发 1402（SignInSystem.cs:189-197 / :396-407，
            // 无 VIP/花费判断），服务端这里只能静默拒；1402 没有失败回包，玩家侧表现为「点了没反应」。
            // 记 warn 便于排查是不是这条路径。
            log.warn("{} qiandao makeup off day={} today={}（补签已定不接；过去未领格下发时已翻成 2，正常点不到这里）",
                    rec.account, Integer.valueOf(day), Integer.valueOf(todayDay));
            return;
        }
        int st = rec.signIn.prizeStatus.get(day - 1).intValue();
        SignInTables.DayRow row = tables.day(day);
        if (row == null) {
            return;
        }
        int vip = economy.vipLevel(rec.economy.chargedDiamond);
        boolean vipOk = row.vipDouble > 0 && vip >= row.vipDouble;
        // 已领普通的 VIP 补双倍只给「今天」（APK 仅最后一格走该分支）
        if (st == NORMAL && day != todayDay) {
            log.info("{} qiandao prize skip vip-double makeup day={}", rec.account, day);
            return;
        }

        Map<String, Integer> changed = new LinkedHashMap<>();
        if (st == NOPRIZED) {
            grant(rec, row, changed);
            rec.signIn.totalDays++;
            if (vipOk) {
                grant(rec, row, changed);
                rec.signIn.prizeStatus.set(day - 1, Integer.valueOf(DOUBLE));
            } else {
                rec.signIn.prizeStatus.set(day - 1, Integer.valueOf(NORMAL));
            }
        } else if (st == NORMAL && vipOk) {
            grant(rec, row, changed);
            rec.signIn.prizeStatus.set(day - 1, Integer.valueOf(DOUBLE));
        } else {
            return;
        }

        rec.signIn.lastSignDate = GameTime.today().toString();
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        progress.pushGold(session, pkt, rec);
        if (row.rmb > 0) {
            progress.pushDiamond(session, pkt, rec);
        }
        session.send(MsgIds.S2C_QIANDAO_PRIZE, pkt, dump.qianDaoPrize(day));
        log.info("{} qiandao prize day={} ->{}", rec.account, day, rec.signIn.prizeStatus.get(day - 1));
    }

    /** 1..今天有未领（含今天 VIP 补双倍）则 1802。 */
    public void pushTipsIfNeed(GameSession session, GamePacket pkt, PlayerRecord rec) {
        if (session == null || rec == null) {
            return;
        }
        refreshMonth(rec);
        int today = GameTime.today().getDayOfMonth();
        if (today <= 0 || rec.signIn.prizeStatus == null || rec.signIn.prizeStatus.isEmpty()) {
            return;
        }
        int vip = economy.vipLevel(rec.economy.chargedDiamond);
        int last = Math.min(today, rec.signIn.prizeStatus.size());
        int from = ALLOW_MAKEUP ? 1 : today;
        if (from > last) {
            return;
        }
        for (int d = from; d <= last; d++) {
            int st = rec.signIn.prizeStatus.get(d - 1).intValue();
            SignInTables.DayRow row = tables.day(d);
            boolean vipOk = row != null && row.vipDouble > 0 && vip >= row.vipDouble;
            if (st == NOPRIZED || (d == today && st == NORMAL && vipOk)) {
                session.send(MsgIds.S2C_QIANDAO_TIPS, pkt == null ? 0 : pkt.serial, new byte[0]);
                return;
            }
        }
    }

    private void grant(PlayerRecord rec, SignInTables.DayRow row, Map<String, Integer> changed) {
        String ori = resolveOri(row);
        if (row.goodsCount > 0 && ori != null && !"0".equals(ori) && !ori.isEmpty()) {
            progress.addGoods(rec, ori, row.goodsCount);
            progress.markGoods(changed, ori);
        }
        if (row.rmb > 0) {
            rec.diamond += row.rmb;
        }
        if (row.gold > 0) {
            rec.gold += row.gold;
        }
    }

    /** 英雄碎片日（表内 SP*）换成当月固定碎片；金币/钻/铸铁等原样。 */
    private String resolveOri(SignInTables.DayRow row) {
        if (row == null || row.ori == null) {
            return "0";
        }
        String ori = row.ori.trim();
        if (ori.isEmpty() || "0".equals(ori)) {
            return ori;
        }
        if (ori.startsWith("SP")) {
            return monthStore.fragmentOri();
        }
        return ori;
    }

    private void refreshMonth(PlayerRecord rec) {
        if (rec.signIn == null) {
            rec.signIn = new PlayerRecord.SignIn();
        }
        LocalDate today = GameTime.today();
        int year = today.getYear();
        int month = today.getMonthValue();
        int daysInMonth = today.lengthOfMonth();

        if (rec.signIn.year != year || rec.signIn.month != month
                || rec.signIn.prizeStatus == null
                || rec.signIn.prizeStatus.size() != daysInMonth) {
            // 新月重建：每天=「未领取(1)」。是否今天可领由 onPrize 点击时判，这里不再区分过期/未来。
            rec.signIn.year = year;
            rec.signIn.month = month;
            rec.signIn.totalDays = 0;
            rec.signIn.prizeStatus = new ArrayList<>();
            for (int i = 0; i < daysInMonth; i++) {
                rec.signIn.prizeStatus.add(Integer.valueOf(NOPRIZED));
            }
            rec.signIn.lastSignDate = "";
            return;
        }

        // 同月旧档迁移：旧版本把「过期/未到」记成 0(NOCAN)，APK 客户端会把 0 画成「已领取」图章；
        // 一律转成 1(未领取)，客户端才显示不盖章。已领 2/3 原样保留。
        for (int d = 1; d <= daysInMonth; d++) {
            if (rec.signIn.prizeStatus.get(d - 1).intValue() == NOCAN) {
                rec.signIn.prizeStatus.set(d - 1, Integer.valueOf(NOPRIZED));
            }
        }
    }
}
