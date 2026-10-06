package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.CultivateTables;
import com.sao.fakeserver.util.GameTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 次日登录奖：C2S 2601 → S2C 3001（CCMsgPrizeSecondLoginDayWJ_Ret.suiPianCount）。
 * 奖池与客户端 GameData/SecondLoginDayPrize.txt 一致：左右创角均为武将 26、3 星。
 * <p>
 * 「次日」判定：账号 createdAt 的次日（自然日）起可领，当天不可领。
 * 登录包 field36（leftTime）由 {@link #leftTimeSec} 计算，未到次日时给倒计时让客户端禁点；
 * 单位必须是「秒」：客户端按秒倒计时（减 deltaTime）并以 /3600 显示时:分:秒。
 * 领奖入口 {@link #onGetPrize} 再校验一次，防客户端绕过 UI 直接发包。
 */
@Service
public class SecondLoginDayService {
    private static final Logger log = LoggerFactory.getLogger(SecondLoginDayService.class);

    /** SecondLoginDayPrize.txt：左右奖池 uResId。 */
    private static final int PRIZE_HERO_INDEX = 26;
    /** SecondLoginDayPrize.txt：uStar。 */
    private static final int PRIZE_STAR = 3;

    private final PlayerStore store;
    private final PlayerDumpService dump;
    private final ProgressService progress;
    private final CultivateTables cultivate;

    public SecondLoginDayService(PlayerStore store, PlayerDumpService dump,
                                 ProgressService progress, CultivateTables cultivate) {
        this.store = store;
        this.dump = dump;
        this.progress = progress;
        this.cultivate = cultivate;
    }

    public void onGetPrize(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        // 当天创号不算「次日」：未达次日一律拒领（客户端可点的入口已由登录包 leftTime 禁用，这里双保险）
        if (!canClaim(rec)) {
            log.info("{} second-login deny: created={} not next day yet", rec.account, rec.createdAt);
            return;
        }
        if (rec.isDrawSecondDayLoginPrized) {
            log.info("{} second-login already drawn", rec.account);
            session.send(MsgIds.S2C_SECOND_LOGIN_DAY_PRIZE_RET, pkt, dump.secondLoginDayPrizeRet(0));
            return;
        }

        int suiPianCount = 0;
        Map<String, Integer> changed = progress.emptyChanged();
        PlayerRecord.Hero exist = rec.findHeroByIndex(PRIZE_HERO_INDEX);
        if (exist != null) {
            CultivateTables.HeroCfg cfg = cultivate.heroByIndex(PRIZE_HERO_INDEX);
            String frag = cfg != null && cfg.fragmentOri != null && !cfg.fragmentOri.isEmpty()
                    ? cfg.fragmentOri : "GOODS119";
            suiPianCount = cultivate.chaiJieFragByStar(PRIZE_STAR);
            progress.addGoods(rec, frag, suiPianCount);
            progress.markGoods(changed, frag);
        } else {
            CultivateTables.HeroCfg cfg = cultivate.heroByIndex(PRIZE_HERO_INDEX);
            if (cfg == null) {
                log.warn("{} second-login hero {} missing in table", rec.account, PRIZE_HERO_INDEX);
                return;
            }
            PlayerRecord.Hero wj = newHero(rec, cfg);
            rec.heroes.add(wj);
            store.save(rec);
            session.send(MsgIds.S2C_ADD_WUJIANG, pkt, dump.addWuJiang(wj, true));
        }

        rec.isDrawSecondDayLoginPrized = true;
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        session.send(MsgIds.S2C_SECOND_LOGIN_DAY_PRIZE_RET, pkt, dump.secondLoginDayPrizeRet(suiPianCount));
        log.info("{} second-login prize hero={} fragments={}", rec.account, PRIZE_HERO_INDEX, suiPianCount);
    }

    private PlayerRecord.Hero newHero(PlayerRecord rec, CultivateTables.HeroCfg cfg) {
        PlayerRecord.Hero wj = new PlayerRecord.Hero();
        wj.heroIndex = cfg.index;
        wj.id = PlayerDumpService.guidOf(rec.account, cfg.index);
        wj.level = 1;
        wj.stars = Math.max(1, PRIZE_STAR);
        wj.stage = 5;
        wj.fightPower = cultivate.computeFightPower(cfg.index, wj.level, wj.stars);
        return wj;
    }

    /** 是否已到可领日：账号 createdAt 次日（自然日）起。createdAt 为空/解析失败按旧行为放行（兼容手改档）。 */
    public static boolean canClaim(PlayerRecord rec) {
        LocalDate created = createdDate(rec);
        return created == null || created.isBefore(GameTime.today());
    }

    /**
     * 距「次日 0 点」（可领时刻）的剩余秒数；已领 / 已可领 / 旧档返回 0。
     * 供登录包 field36 使用。勿下发毫秒：客户端会把数值当秒显示，毫秒会被放大约 1000 倍
     * （例如还剩约 18 分钟会显示成约 300 小时）。
     */
    public static long leftTimeSec(PlayerRecord rec) {
        if (rec == null || rec.isDrawSecondDayLoginPrized || canClaim(rec)) {
            return 0L;
        }
        LocalDate created = createdDate(rec);
        LocalDateTime nextDay = created.plusDays(1).atStartOfDay();
        long sec = Duration.between(GameTime.now(), nextDay).getSeconds();
        return Math.max(0L, sec);
    }

    /** 解析 createdAt 的日期部分（yyyy-MM-dd HH:mm:ss）；空/坏档返回 null（放行，保持旧语义）。 */
    private static LocalDate createdDate(PlayerRecord rec) {
        if (rec == null || rec.createdAt == null) {
            return null;
        }
        String s = rec.createdAt.trim();
        if (s.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }
}
