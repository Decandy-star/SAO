package com.sao.fakeserver.service;

import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.store.SignInMonthStore;
import com.sao.fakeserver.table.DailyActivityTables;
import com.sao.fakeserver.util.GameTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 假服公共级定时入口：全服定档、系统发邮、JJC 周重置、争霸到点推阶段、KFZ 入围快照、商店整点 1701。
 * 用户级日刷仍走登录 / 业务包里的 {@link ProgressService#ensureDaily}（跨日次数）；商店货架整点另走本类 cron。
 */
@Component
public class GlobalServerScheduler {
    private static final Logger log = LoggerFactory.getLogger(GlobalServerScheduler.class);

    private final DailyActivityTables activity;
    private final MailService mail;
    private final ArenaService arena;
    private final ActivityService activityService;
    private final SignInMonthStore signInMonth;
    private final SessionHub sessions;
    private final ZbzService zbz;
    private final KfzService kfz;
    private final ShopService shop;
    private final ProgressService progress;
    private final LtsjService ltsj;
    private final UnionService union;

    public GlobalServerScheduler(DailyActivityTables activity, MailService mail, ArenaService arena,
                                 ActivityService activityService, SignInMonthStore signInMonth,
                                 SessionHub sessions, ZbzService zbz, KfzService kfz, ShopService shop,
                                 ProgressService progress, LtsjService ltsj, UnionService union) {
        this.activity = activity;
        this.mail = mail;
        this.arena = arena;
        this.activityService = activityService;
        this.signInMonth = signInMonth;
        this.sessions = sessions;
        this.zbz = zbz;
        this.kfz = kfz;
        this.shop = shop;
        this.progress = progress;
        this.ltsj = ltsj;
        this.union = union;
    }

    /**
     * 服务器启动钩子：重读活动表，把「每日定档 / 系统发邮补发 / JJC 周重置 / KFZ 入围补跑」各跑一遍，
     * 避免假服中途重启后要等到下一个整点才结算。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onServerReady() {
        activity.reload(true);
        activityService.rollHeroFragmentPlanForToday();
        signInMonth.ensureMonthlyPickForScheduler();
        mail.catchUpAllPlayers("startup");
        arena.resetWeeklyIfDue();
        kfz.runEligibilitySnapshotIfDue();
        union.handoverInactiveOwners();
        // 公会战跨日：把每个公会的 pvpDay 滚到当天（客户端进场景先发 1560/1561/1562/1568，
        // 回包 1957/1958/1959 直接渲染棋盘与排行，不滚天就会显示昨天的数据）。
        union.rolloverPvpDays();
        // 押镖统一结算补跑：启动时刻若已过当天窗口结束点（相位 3），把漏结算的镖车收益补发。
        union.settleMajiuAwards("startup");
        // 公会拍卖 / 公会战结算补跑：整夜停机时昨天的拍卖与公会战不能丢。
        union.settleAuctions("startup");
        union.settlePvpBattles("startup");
        log.info("公共调度启动补跑完成");
    }

    /**
     * 每日 0 点：壕送大礼当日 heroFragment 定档、签到月碎片跨月检查、
     * vip每日礼包补发、在线号用户级日刷（含 attri 17/18 归零、2601 活动状态重推）、
     * JJC 周重置若配置落在此刻。
     *
     * <p>注意：月卡/至尊卡每日返钻不在这里发——客户端是「点领」结算
     * （TaskSystem.cs:799-853 → C2S 3501/3502），0 点置位会让领取按钮整天不可点。
     */
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Shanghai")
    public void dailyMidnightJobs() {
        activity.reload(false);
        activityService.rollHeroFragmentPlanForToday();
        signInMonth.ensureMonthlyPickForScheduler();
        arena.resetWeeklyIfDue();
        // 会长连续一周未登录 → 自动让位（StrTable 101282「由于你一周未登录游戏,会长职务由{0}接任」）。
        // 只有会长一人的公会跳过：没有可接任的人。
        union.handoverInactiveOwners();
        // 公会战跨日：客户端进公会战场景先发 1560/1561/1562/1568，回包 1957/1958/1959 直接渲染
        // 棋盘与排行，而跨日刷新原先只挂在 1549/1550/1567 上 ⇒ 0 点统一把所有公会滚到当天。
        union.rolloverPvpDays();
        // 训练场到点：训练完成的 1935 原先只有玩家再发一次 1539 才产生（tickTrain 唯一调用点），
        // 关掉面板后训练到点不结算。0 点扫一遍（分钟钩子另挂 unionTrainClock）。
        union.trainClock();
        // vip每日礼包（mailType 8，Sys_MailConfig 第8行）：发邮钟点闸门只在 daily-activity.json
        // 配的钟点扫号，跨夜在线的号拿不到新一天那封 → 0 点单独扫一遍 vip 邮件。
        mail.grantVipDailyAllPlayers("daily-0");
        // 限时神将按 LocalDate 跨日重开一期（轮换神将 + 免费次数 + 四字/宝箱状态），
        // 客户端没有查询包 → 必须主动推 3702，否则在线挂机的号 0 点后面板还是上一期。
        ltsj.pushToOnline();
        // 用户级日刷（次数/标记）本走登录与业务包懒结算，但 attri 17/18（今日累充/耗钻）客户端
        // 没有本地跨日重置：不主动推，在线挂机的号 0 点后仍显示昨天的数、面板把昨天已达标的档位
        // 显示成「可领」，点领取被服端按归零后的值静默拒。0 点对在线号补跑一遍。
        progress.resetDailyChargeToOnline();
        // 活动看板状态（2601：7 日签到态 / 今日体力档 / 各活动已领串）同样没有本地跨日重置，
        // 客户端只在打开活动页时发 2318 → 在线跨夜的号大厅活动红点停在前一天。0 点补推一遍。
        activityService.pushStatusToOnline();
        log.info("公共调度每日 0 点任务完成");
    }

    /**
     * 商店整点换货：9/12/18/21 上海，对在线号推 S2C 1701（店内会跟 1301 拉新货架）。
     */
    @Scheduled(cron = "0 0 9,12,18,21 * * *", zone = "Asia/Shanghai")
    public void shopSysRefreshClock() {
        shop.pushClockRefreshToOnline();
    }

    /**
     * 金币免费抽次数 + 商店每日免费刷新次数：上海 5:00 游戏日界
     * （金币抽对齐 APK 次数用尽倒计到次日 05:00；商店刷新对齐 {@code ShopCommom}
     * 公会商店行第 16 列「重置每日免费刷新次数时间」= 5）。
     */
    @Scheduled(cron = "0 0 5 * * *", zone = "Asia/Shanghai")
    public void gachaGoldFreeClock() {
        progress.pushGoldFreeGameDayResetToOnline();
        progress.pushShopFreeRefreshGameDayResetToOnline();
    }

    /**
     * 发邮钟点闸门：每分钟只比对 daily-activity.json 里 JJC 排名邮 / 周重置的钟点。
     * 当前分钟未命中任何配置则立刻返回，不扫号；命中才补发 JJC 排名邮并检查周重置。
     */
    @Scheduled(cron = "0 * * * * *")
    public void mailClockGate() {
        activity.reload(false);
        LocalDateTime now = GameTime.now();
        if (!isConfiguredMailOrWeeklyMinute(now)) {
            return;
        }
        mail.catchUpAllPlayers("clock-" + now.toLocalTime().withSecond(0).withNano(0));
        arena.resetWeeklyIfDue();
    }

    /**
     * 押镖统一结算：马厩时间窗（UnionMaJiuTime.txt 18:00 起 + 发镖 7200s + 劫镖 7200s = 22:00 结束）
     * 一结束，就把当天发过车、还没点 1548 领奖的运镖收益统一发掉：在线推 1943（客户端收到即开
     * 结算弹窗），离线按 mailType 7「运镖收益」（Sys_MailConfig 第7行）邮寄。
     *
     * <p>分钟级钩子 + 服务端自己判相位与当日去重（{@link UnionService#settleMajiuAwards}），
     * 结算时刻不写死在 cron 里，改表即生效。
     */
    @Scheduled(cron = "0 * * * * *")
    public void majiuSettleClock() {
        union.settleMajiuAwards("clock");
    }

    /**
     * 公会域两个「到点结算」都挂在分钟钩子上（服务端自己判窗口与当日去重，改表即生效）：
     * <ul>
     *   <li>作战室战利品拍卖：{@code Union.txt}「作战室拍卖结算时间（24小时制小时）」= 20 点后，
     *       把当天最高出价者拍中的战利品按系统邮件 4「战利品拍卖胜出」寄出、清空货架
     *       （{@link UnionService#settleAuctions}）。</li>
     *   <li>公会战 PvP：{@code UnionPvPTime.txt} 19:00 + 3600s = 20:00 结束后，判定邮件模板
     *       10–16 并结算公会成长值/晶石与个人奖励（{@link UnionService#settlePvpBattles}）。</li>
     * </ul>
     */
    @Scheduled(cron = "0 * * * * *")
    public void unionSettleClock() {
        union.settleAuctions("clock");
        union.settlePvpBattles("clock");
    }

    /**
     * 训练场到点：每分钟扫在线号推进训练倒计时（{@link UnionService#trainClock}），到点的坑结算经验
     * 并推 1935。客户端只在打开面板与面板内倒计时归零时发 1539，关掉面板就再也不问。
     */
    @Scheduled(cron = "0 * * * * *")
    public void unionTrainClock() {
        union.trainClock();
    }

    /**
     * 争霸阶段到点推：每分钟对在线号跑一次 {@link ZbzService#pushPhaseIfChanged}。
     * 无变化静默；跨表时段边界则主动 S2C 4605（客户端再发 4101 刷树），对齐真服挂机也能切阶段。
     */
    @Scheduled(cron = "0 * * * * *")
    public void zbzPhaseClock() {
        int n = 0;
        for (GameSession s : sessions.onlineSnapshot()) {
            int before = s.player() != null ? s.player().zbz.curState : -1;
            zbz.pushPhaseIfChanged(s);
            int after = s.player() != null ? s.player().zbz.curState : -1;
            if (before != after) {
                n++;
            }
        }
        if (n > 0) {
            log.info("zbz phase clock pushed {} online", n);
        }
    }

    /**
     * KFZ 入围：周日 21:00 按 JJC 前十快照下周资格；其它时刻 catch-up。
     */
    @Scheduled(cron = "0 * * * * *")
    public void kfzEligibilityClock() {
        kfz.runEligibilitySnapshotIfDue();
    }

    /** 当前时分是否命中 JJC 排名邮或 JJC 周重置配置时刻。 */
    private boolean isConfiguredMailOrWeeklyMinute(LocalDateTime now) {
        DailyActivityTables.FileConfig cfg = activity.current();
        LocalTime t = now.toLocalTime().withSecond(0).withNano(0);
        if (cfg.jjcRankMail != null && cfg.jjcRankMail.enabled
                && sameMinute(t, cfg.jjcRankMail.hour, cfg.jjcRankMail.minute)) {
            return true;
        }
        if (cfg.zbzRankMail != null && cfg.zbzRankMail.enabled
                && sameMinute(t, cfg.zbzRankMail.hour, cfg.zbzRankMail.minute)) {
            return true;
        }
        DailyActivityTables.JjcWeeklyReset weekly = cfg.jjcWeeklyReset;
        if (weekly != null && weekly.enabled
                && sameMinute(t, weekly.hour, weekly.minute)
                && now.getDayOfWeek().getValue() == Math.max(1, Math.min(7, weekly.weekday))) {
            return true;
        }
        return false;
    }

    private static boolean sameMinute(LocalTime t, int hour, int minute) {
        return t.getHour() == Math.max(0, Math.min(23, hour))
                && t.getMinute() == Math.max(0, Math.min(59, minute));
    }
}
