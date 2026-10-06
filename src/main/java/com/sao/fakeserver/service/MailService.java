package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.CultivateTables;
import com.sao.fakeserver.table.DailyActivityTables;
import com.sao.fakeserver.table.EconomyTables;
import com.sao.fakeserver.table.GameTables;
import com.sao.fakeserver.table.VipMailCfg;
import com.sao.fakeserver.util.GameTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 邮箱：系统邮一律走 {@link #appendSystemMail}，强制 Sys_MailConfig 模板（mailType&gt;0）。
 */
@Service
public class MailService {
    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    /** Sys_MailConfig：竞技场排名奖励 */
    public static final int MAIL_TYPE_JJC_RANK = 1;
    /** Sys_MailConfig：礼包管理员（标题/正文均可 {0}）——月卡/至尊返钻、自定义补发 */
    public static final int MAIL_TYPE_GIFT_ADMIN = 2;
    /**
     * Sys_MailConfig 第3行：公会 boss 名次奖。对应 ESysMailTypeID.EMTID_UNION_BOSS_XDB=3，
     * 正文「你在[FF0000]boss{0}[-]的攻略战中贡献了[00FF00]{1}%[-]的伤害，夺得[00FF00]{2}[-]名，
     * 额外获得了以下奖励：」——{0}=boss 名、{1}=伤害占比（整数百分数）、{2}=名次。
     * <p>
     * 「额外获得了以下奖励」= 名次奖走这封邮的附件（兄弟币/勇气币），
     * 与 JJC 排名邮（{@link #MAIL_TYPE_JJC_RANK}）、争霸排名邮（{@link #MAIL_TYPE_ZBZ}）同一范式，
     * 故击杀结算时**不再内联发放**，否则同一份名次奖会被发两次。
     */
    public static final int MAIL_TYPE_UNION_BOSS = 3;
    /**
     * Sys_MailConfig 第7行：运镖收益（无占位符）。
     * 对应 ESysMailTypeID.EMTID_UNION_MJ_AWARD=7，正文
     * 「你在公会运镖活动中有收益没有及时领取，邮寄给你啦：」——
     * 马厩活动结算时玩家不在线（或当天没点 1548 领奖）时，未领取的运镖收益走这封邮。
     */
    public static final int MAIL_TYPE_UNION_MJ_AWARD = 7;
    /** Sys_MailConfig：任务管理员（接任务通知） */
    public static final int MAIL_TYPE_TASK = 18;
    /** Sys_MailConfig：争霸战管理员 */
    public static final int MAIL_TYPE_ZBZ = 19;
    /** Sys_MailConfig：跨服战管理员 */
    public static final int MAIL_TYPE_KFZ = 20;
    /** Sys_MailConfig：变体纷争奖励（精炼等） */
    public static final int MAIL_TYPE_CLONE_REWARD = 22;
    /** Sys_MailConfig：变体纷争碎片奖励 */
    public static final int MAIL_TYPE_CLONE_FRAG = 23;
    /**
     * Sys_MailConfig 第8行：vip每日礼包（正文 {0}=vip等级）。
     * 对应 ESysMailTypeID.EMTID_VIP_AWARD_EVERY_DAY=8；内容来自 tables\vip-mail.json。
     */
    public static final int MAIL_TYPE_VIP_DAILY = 8;
    /**
     * Sys_MailConfig 第9行：vip提升奖励（正文 {0}=vip等级）。
     * 对应 ESysMailTypeID.EMTID_VIP_AWARD_ADD=9；vip 等级提升时发，内容来自 tables\vip-mail.json。
     */
    public static final int MAIL_TYPE_VIP_ADD = 9;
    /**
     * Sys_MailConfig 第4行：作战室战利品拍卖**胜出**。正文
     * 「你公会作战室战利品拍卖中胜出，获得了如下战利品。」——附件 = 拍下的战利品。
     * 拍卖在 {@code Union.txt}「作战室拍卖结算时间」到点结算，物品走这封邮（邮箱是唯一交付口）。
     */
    public static final int MAIL_TYPE_UNION_AUCTION_WIN = 4;
    /**
     * Sys_MailConfig 第5行：战利品拍卖**竞标失败退款**。正文
     * 「你公会作战室战利品"{0}"的拍卖中竞标失败，退还如下款项。」——{0}=战利品名，
     * 附件 = 退还的勇气币（拍卖货币）。
     */
    public static final int MAIL_TYPE_UNION_AUCTION_REFUND = 5;
    /**
     * Sys_MailConfig 第6行：公会拍卖通用退款。正文
     * 「你在公会拍卖中竞拍的款项退还如下。」——无占位符，用于「带着最高出价离开公会」
     * 这类无法按单件战利品命名的退款。
     */
    public static final int MAIL_TYPE_UNION_REFUND = 6;
    /**
     * Sys_MailConfig 第10–16行：公会战（PvP）结算。7 行同一邮件名「公会战奖励」，
     * 只按战果选行号，正文都带 {0}=对手公会名（第16行「幸运轮空」无占位符）：
     * 10 攻陷对方基地胜 / 11 己方基地失守败 / 12 据点更多胜 / 13 据点更少败 /
     * 14 据点相同平 / 15 对方公会解散胜 / 16 幸运轮空胜。附件 = 该档成长/晶石/兄弟币/物品。
     */
    public static final int MAIL_TYPE_UNION_PVP_BASE_WIN = 10;
    public static final int MAIL_TYPE_UNION_PVP_BASE_LOSE = 11;
    public static final int MAIL_TYPE_UNION_PVP_POINT_WIN = 12;
    public static final int MAIL_TYPE_UNION_PVP_POINT_LOSE = 13;
    public static final int MAIL_TYPE_UNION_PVP_DRAW = 14;
    public static final int MAIL_TYPE_UNION_PVP_RIVAL_DISMISS = 15;
    public static final int MAIL_TYPE_UNION_PVP_BYE = 16;
    /**
     * Sys_MailConfig 第24行：公会队长（会长）转让通知。正文
     * 「由于{0}一周未登陆游戏，会长职务由{1}接任。」——{0}=旧会长名、{1}=新会长名。
     * 正文没有「你」，发件人是公会战管理员 ⇒ 面向**全公会成员**的通报（不是只发给当事人）。
     */
    public static final int MAIL_TYPE_UNION_OWNER_CHANGE = 24;

    private final PlayerStore players;
    private final PlayerDumpService dump;
    private final ProgressService progress;
    private final ArenaService arena;
    private final ZbzService zbz;
    private final GameTables tables;
    private final DailyActivityTables activity;
    private final CultivateTables cultivate;
    private final EconomyTables economy;
    private final SessionHub sessions;
    private final VipMailCfg vipMail;

    public MailService(PlayerStore players, PlayerDumpService dump, ProgressService progress,
                       ArenaService arena, ZbzService zbz, GameTables tables, DailyActivityTables activity,
                       CultivateTables cultivate, EconomyTables economy, SessionHub sessions,
                       VipMailCfg vipMail) {
        this.players = players;
        this.dump = dump;
        this.progress = progress;
        this.arena = arena;
        this.zbz = zbz;
        this.tables = tables;
        this.activity = activity;
        this.cultivate = cultivate;
        this.economy = economy;
        this.sessions = sessions;
        this.vipMail = vipMail;
    }

    /**
     * 公共调度扫全号：补发已到点的 JJC 排名邮、月卡/至尊返钻邮。
     */
    public void catchUpAllPlayers(String why) {
        int n = 0;
        for (PlayerRecord rec : players.all()) {
            if (grantDue(rec)) {
                players.save(rec);
                notifyNewMail(rec.account);
                n++;
            }
        }
        if (n > 0) {
            log.info("mail catch-up {} players={}", why, n);
        }
    }

    /**
     * vip每日礼包（mailType 8）每日 0 点补发：只扫 vip 邮件，不碰排名邮的到点判定。
     *
     * <p>{@link #catchUpAllPlayers} 由「发邮钟点闸门」调用，只在 {@code tables\daily-activity.json}
     * 配的钟点（当前 21:00 / 21:35 / 23:30，周一 0 点随周重置）才扫号；跨夜在线的号因此拿不到
     * 新一天的那封 8，要等玩家点开邮箱（{@link #onRequest}）或重登才补上。真服是每天到点发，
     * 所以 0 点单独扫一遍：在线号会收到 {@code S2C_MAIL_NOTIFY}（点邮箱图标）。
     */
    public void grantVipDailyAllPlayers(String why) {
        LocalDate today = GameTime.today();
        int n = 0;
        for (PlayerRecord rec : players.all()) {
            if (rec == null || rec.npcPassive) {
                continue;
            }
            rec.ensureCollections();
            if (grantVipDaily(rec, today)) {
                players.save(rec);
                notifyNewMail(rec.account);
                n++;
            }
        }
        if (n > 0) {
            log.info("vip daily mail {} players={}", why, n);
        }
    }

    /**
     * 对单号补发已到点的系统邮（登录、开邮箱、公共扫号；mailGrantKeys 防重）。
     */
    public boolean grantDue(PlayerRecord rec) {
        if (rec == null || rec.npcPassive) {
            return false;
        }
        rec.ensureCollections();
        arena.ensureSlot(rec);
        DailyActivityTables.FileConfig cfg = activity.current();
        LocalDateTime now = GameTime.now();
        LocalDate today = now.toLocalDate();
        boolean added = false;
        if (existedBefore(rec, today) && cfg.jjcRankMail != null && cfg.jjcRankMail.enabled) {
            added |= grantJjcRank(rec, today.minusDays(1));
        }
        if (cfg.jjcRankMail != null && cfg.jjcRankMail.enabled
                && reached(now, cfg.jjcRankMail.hour, cfg.jjcRankMail.minute)) {
            added |= grantJjcRank(rec, today);
        }
        if (existedBefore(rec, today) && cfg.zbzRankMail != null && cfg.zbzRankMail.enabled) {
            added |= grantZbzRank(rec, today.minusDays(1));
        }
        if (cfg.zbzRankMail != null && cfg.zbzRankMail.enabled
                && reached(now, cfg.zbzRankMail.hour, cfg.zbzRankMail.minute)) {
            added |= grantZbzRank(rec, today);
        }
        if (existedBefore(rec, today) && cfg.kfzPaiWeiRankMail != null && cfg.kfzPaiWeiRankMail.enabled) {
            added |= grantKfzPaiWeiRank(rec, today.minusDays(1));
        }
        if (cfg.kfzPaiWeiRankMail != null && cfg.kfzPaiWeiRankMail.enabled
                && reached(now, cfg.kfzPaiWeiRankMail.hour, cfg.kfzPaiWeiRankMail.minute)) {
            added |= grantKfzPaiWeiRank(rec, today);
        }
        if (existedBefore(rec, today) && cfg.kfzDfzRankMail != null && cfg.kfzDfzRankMail.enabled) {
            added |= grantKfzDfzRank(rec, today.minusDays(1));
        }
        if (cfg.kfzDfzRankMail != null && cfg.kfzDfzRankMail.enabled
                && reached(now, cfg.kfzDfzRankMail.hour, cfg.kfzDfzRankMail.minute)) {
            added |= grantKfzDfzRank(rec, today);
        }
        if (vipMail.enabled()) {
            added |= grantVipDaily(rec, today);
        }
        return added;
    }

    private static boolean existedBefore(PlayerRecord rec, LocalDate day) {
        if (rec.createdAt == null || rec.createdAt.isEmpty()) {
            return true;
        }
        try {
            return LocalDateTime.parse(rec.createdAt, PlayerDumpService.TIME).toLocalDate().isBefore(day);
        } catch (Exception e) {
            return true;
        }
    }

    // ---------- vip 邮件（mailType 8 / 9，内容见 tables\vip-mail.json） ----------

    /**
     * vip每日礼包（mailType 8）：按当前 vip 等级每天一封，mailGrantKeys 防重。
     *
     * <p>占位符在**正文**（Sys_MailConfig 第8行「你的是vip{0}级玩家，每日可领取如下vip专属奖励：」）：
     * 客户端 SysMailCfg.GetTitle 用 TitleParm 替换标题、GetContent 用 Paras 替换正文
     * （SysMailCfg.cs:50-84），所以等级必须写进 paras。
     *
     * <p>vip 等级是算出来的（EconomyTables.vipLevel(chargedDiamond)，表 VipCfg「累计钻石数量」），
     * 没有单独存字段，所以升级判定只能在充值处做（见 sendVipLevelAdd / PayService.grant）。
     */
    private boolean grantVipDaily(PlayerRecord rec, LocalDate day) {
        int vip = economy.vipLevel(rec.economy.chargedDiamond);
        VipMailCfg.Level cfg = vipMail.level(vip);
        if (vip <= 0 || cfg == null || cfg.daily == null || cfg.daily.isEmpty()) {
            return false;
        }
        String key = day + "/vip-daily";
        if (rec.economy.mailGrantKeys.contains(key)) {
            return false;
        }
        appendVipMail(rec, MAIL_TYPE_VIP_DAILY, vip, key, cfg.daily);
        return true;
    }

    /**
     * vip提升奖励（mailType 9）：vip 等级被充值顶上去之后由 {@link PayService} 调用，每级一封。
     *
     * <p>第9行文案是「提升vip带来的每日奖励补充」，故 vip-mail.json 的 {@code levels.<n>.add}
     * 应填该等级带来的**增量**附件；没配 = 不发。
     */
    public void sendVipLevelAdd(PlayerRecord rec, int vip) {
        if (rec == null || vip <= 0) {
            return;
        }
        rec.ensureCollections();
        VipMailCfg.Level cfg = vipMail.level(vip);
        if (!vipMail.enabled() || cfg == null || cfg.add == null || cfg.add.isEmpty()) {
            return;
        }
        String key = "vip-add/" + vip;
        if (rec.economy.mailGrantKeys.contains(key)) {
            return;
        }
        PlayerRecord.Mail mail = appendVipMail(rec, MAIL_TYPE_VIP_ADD, vip, key, cfg.add);
        players.save(rec);
        notifyNewMail(rec.account);
        GameSession session = sessions.get(rec.account);
        if (session != null && session.channel() != null && session.channel().isActive()) {
            session.send(MsgIds.S2C_MAIL_UPDATE, 0, dump.oneMail(mail));
        }
        log.info("{} vip add mail vip={} id={} items={}", rec.account, vip, mail.mailDynId,
                mail.items == null ? 0 : mail.items.size());
    }

    private PlayerRecord.Mail appendVipMail(PlayerRecord rec, int mailType, int vip, String key,
                                            VipMailCfg.Reward reward) {
        List<PlayerRecord.MailItem> items = null;
        if (reward.items != null) {
            items = new ArrayList<>();
            for (VipMailCfg.Item it : reward.items) {
                if (it == null) {
                    continue;
                }
                addItemToList(items, it.ori, it.count, it.stars);
            }
        }
        return appendSystemMail(rec, mailType, null,
                Collections.singletonList(String.valueOf(vip)), key,
                reward.gold, reward.diamond, reward.stamina, reward.exp, reward.jjcScore,
                reward.wannengFragments, reward.yingPo, reward.moFaChen,
                reward.brotherCoin, reward.courageCoin, items);
    }

    public void onRequest(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        if (grantDue(rec)) {
            players.save(rec);
        }
        session.send(MsgIds.S2C_MAIL_LIST, pkt, dump.mailList(rec));
    }

    public void onHandle(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        int dynId = Pb.read(pkt.body).getInt(1, 0);
        PlayerRecord.Mail mail = find(rec, dynId);
        if (mail == null) {
            return;
        }
        if (!mail.handled) {
            claim(session, pkt, rec, mail);
            mail.handled = true;
            players.save(rec);
        }
        rec.mails.remove(mail);
        players.save(rec);
        session.send(MsgIds.S2C_MAIL_DEL, pkt, dump.mailDynId(dynId));
    }

    public boolean hasOpen(PlayerRecord rec) {
        if (rec == null || rec.mails == null) {
            return false;
        }
        for (PlayerRecord.Mail mail : rec.mails) {
            if (!mail.handled) {
                return true;
            }
        }
        return false;
    }

    public void notifyNewMail(String account) {
        GameSession session = sessions.get(account);
        if (session != null && session.channel() != null && session.channel().isActive()) {
            session.send(MsgIds.S2C_MAIL_NOTIFY, 0, new byte[0]);
        }
    }

    /**
     * 对外发系统邮并落盘；在线则推通知 + 单封更新。
     * 底层强制 mailType&gt;0，见 {@link #appendSystemMail}。
     */
    public PlayerRecord.Mail sendSystemMail(String account, int mailType,
                                            List<String> titleParms, List<String> paras,
                                            int gold, int diamond, int stamina, int exp, int jjcScore,
                                            int wannengFragments, int yingPo, int moFaChen,
                                            int brotherCoin, int courageCoin,
                                            List<PlayerRecord.MailItem> items) {
        PlayerRecord rec = players.get(account);
        if (rec == null) {
            throw new IllegalArgumentException("no player " + account);
        }
        rec.ensureCollections();
        PlayerRecord.Mail mail = appendSystemMail(rec, mailType, titleParms, paras, null,
                gold, diamond, stamina, exp, jjcScore,
                wannengFragments, yingPo, moFaChen, brotherCoin, courageCoin, items);
        players.save(rec);
        GameSession session = sessions.get(account);
        if (session != null && session.channel() != null && session.channel().isActive()) {
            session.send(MsgIds.S2C_MAIL_NOTIFY, 0, new byte[0]);
            session.send(MsgIds.S2C_MAIL_UPDATE, 0, dump.oneMail(mail));
        }
        log.info("{} sys mail id={} type={} items={}", account, mail.mailDynId, mailType,
                mail.items == null ? 0 : mail.items.size());
        return mail;
    }

    /**
     * 运镖收益补发（mailType 7，Sys_MailConfig 第7行「你在公会运镖活动中有收益没有及时领取，
     * 邮寄给你啦：」）——模板无占位符，故 titleParms / paras 传 null。
     *
     * <p>金币走 mail.gold、兄弟币走 mail.brotherCoin；1943 的
     * {@code CCMsgYunBiaoAwardItem.jinShi}（公会晶石）与目的地/被劫次数这类展示字段
     * 邮箱模型里没有对应列（{@link #appendSystemMail} 无晶石参数），只能丢弃——
     * 真服这封邮的附件也只有金币/兄弟币。
     */
    public PlayerRecord.Mail sendMajiuAwardMail(String account, int gold, int brotherCoin) {
        return sendSystemMail(account, MAIL_TYPE_UNION_MJ_AWARD, null, null,
                gold, 0, 0, 0, 0, 0, 0, 0, brotherCoin, 0, null);
    }

    /**
     * 公会 boss 击杀后的名次奖邮（mailType 3，Sys_MailConfig 第3行）。
     *
     * <p>正文占位符 {0}=boss 名、{1}=伤害占比百分数、{2}=名次；附件 = 名次兄弟币/勇气币
     * （`UnionBoss.txt` 第 11/12 列「结算」两列，按 {@code UnionCfg.rankAward} 取值）。
     * {@code grantKey} 非空时按账号去重（一章一次），重复调用直接返回 null 不重发。
     *
     * @return 新建的邮件；账号不存在或 grantKey 已存在时返回 null
     */
    public PlayerRecord.Mail sendUnionBossRankMail(String account, String bossName, int damagePercent,
                                                   int rank, int brotherCoin, int courageCoin,
                                                   String grantKey) {
        PlayerRecord rec = players.get(account);
        if (rec == null) {
            return null;
        }
        rec.ensureCollections();
        if (grantKey != null && rec.economy.mailGrantKeys.contains(grantKey)) {
            return null;
        }
        PlayerRecord.Mail mail = appendSystemMail(rec, MAIL_TYPE_UNION_BOSS, null,
                Arrays.asList(bossName, String.valueOf(damagePercent), String.valueOf(rank)),
                grantKey, 0, 0, 0, 0, 0, 0, 0, 0, brotherCoin, courageCoin, null);
        players.save(rec);
        GameSession session = sessions.get(account);
        if (session != null && session.channel() != null && session.channel().isActive()) {
            session.send(MsgIds.S2C_MAIL_NOTIFY, 0, new byte[0]);
            session.send(MsgIds.S2C_MAIL_UPDATE, 0, dump.oneMail(mail));
        }
        log.info("{} union boss mail boss={} pct={} rank={} xdb={} yqb={}", account, bossName,
                damagePercent, rank, brotherCoin, courageCoin);
        return mail;
    }

    /**
     * 作战室拍卖胜出（mailType 4）：附件 = 战利品。
     *
     * @param lootName 战利品名（存档可读副本用；模板第4行正文无占位符）
     * @param grantKey 非空时按账号去重
     * @return 新建邮件；账号不存在或 grantKey 已存在返回 null
     */
    public PlayerRecord.Mail sendAuctionWinMail(String account, String lootName, String ori, int count,
                                               String grantKey) {
        List<PlayerRecord.MailItem> items = new ArrayList<>();
        addItemToList(items, ori, count, 0);
        PlayerRecord.Mail mail = sendUnionMail(account, MAIL_TYPE_UNION_AUCTION_WIN, null, 0, 0,
                items, grantKey);
        if (mail != null) {
            log.info("{} union auction win loot={} ori={} x{}", account, lootName, ori,
                    Integer.valueOf(count));
        }
        return mail;
    }

    /**
     * 战利品竞标失败退款（mailType 5）：{0}=战利品名，附件 = 退还的勇气币。
     */
    public PlayerRecord.Mail sendAuctionRefundMail(String account, String lootName, int courageCoin) {
        return sendUnionMail(account, MAIL_TYPE_UNION_AUCTION_REFUND,
                Collections.singletonList(lootName), 0, courageCoin, null, null);
    }

    /**
     * 公会拍卖通用退款（mailType 6）：无占位符，附件 = 退还的勇气币。
     */
    public PlayerRecord.Mail sendUnionRefundMail(String account, int courageCoin) {
        return sendUnionMail(account, MAIL_TYPE_UNION_REFUND, null, 0, courageCoin, null, null);
    }

    /**
     * 公会战结算（mailType 10–16）：{0}=对手公会名（第16行「幸运轮空」传 null），
     * 附件 = 兄弟币 + 该档物品。
     *
     * @param mailType 10–16，见 {@link #MAIL_TYPE_UNION_PVP_BASE_WIN} 等常量
     */
    public PlayerRecord.Mail sendUnionPvpMail(String account, int mailType, String rivalUnionName,
                                             int brotherCoin, String goodsOri, int goodsCount,
                                             String grantKey) {
        if (mailType < MAIL_TYPE_UNION_PVP_BASE_WIN || mailType > MAIL_TYPE_UNION_PVP_BYE) {
            throw new IllegalArgumentException("公会战邮件 mailType 必须是 10–16，收到 " + mailType);
        }
        // 第16行「公会战幸运轮空，不战而胜，你获得奖励如下:」没有 {0}
        List<String> paras = mailType == MAIL_TYPE_UNION_PVP_BYE
                ? null : Collections.singletonList(rivalUnionName);
        List<PlayerRecord.MailItem> items = new ArrayList<>();
        addItemToList(items, goodsOri, goodsCount, 0);
        PlayerRecord.Mail mail = sendUnionMail(account, mailType, paras, brotherCoin, 0, items, grantKey);
        if (mail != null) {
            log.info("{} union pvp mail type={} rival={} xdb={} goods={}", account,
                    Integer.valueOf(mailType), rivalUnionName, Integer.valueOf(brotherCoin), goodsOri);
        }
        return mail;
    }

    /**
     * 会长转让通报（mailType 24）：{0}=旧会长名、{1}=新会长名；无附件。
     * 由 {@code UnionService#handoverInactiveOwners} 发给**全公会成员**。
     */
    public PlayerRecord.Mail sendUnionOwnerChangeMail(String account, String oldOwnerName,
                                                     String newOwnerName, String grantKey) {
        return sendUnionMail(account, MAIL_TYPE_UNION_OWNER_CHANGE,
                Arrays.asList(oldOwnerName, newOwnerName), 0, 0, null, grantKey);
    }

    /**
     * 公会域系统邮的公共出口：查账号 → 查 grantKey → {@link #appendSystemMail} → 落盘 →
     * 在线推通知 + 单封更新。账号不存在或 grantKey 命中返回 null。
     */
    private PlayerRecord.Mail sendUnionMail(String account, int mailType, List<String> paras,
                                           int brotherCoin, int courageCoin,
                                           List<PlayerRecord.MailItem> items, String grantKey) {
        PlayerRecord rec = players.get(account);
        if (rec == null) {
            return null;
        }
        rec.ensureCollections();
        if (grantKey != null && !grantKey.isEmpty() && rec.economy.mailGrantKeys.contains(grantKey)) {
            return null;
        }
        PlayerRecord.Mail mail = appendSystemMail(rec, mailType, null, paras, grantKey,
                0, 0, 0, 0, 0, 0, 0, 0, brotherCoin, courageCoin, items);
        players.save(rec);
        GameSession session = sessions.get(account);
        if (session != null && session.channel() != null && session.channel().isActive()) {
            session.send(MsgIds.S2C_MAIL_NOTIFY, 0, new byte[0]);
            session.send(MsgIds.S2C_MAIL_UPDATE, 0, dump.oneMail(mail));
        }
        return mail;
    }

    /**
     * 底层唯一追加入口：往账号邮箱加一封系统邮。
     * <p>
     * mailType 必须 &gt;0（客户端按 Sys_MailConfig 渲染标题/图标；0=空标题，禁止下发）。
     * 不落盘、不推送；调用方负责 save / notify。
     * grantKey 非空时写入 mailGrantKeys（调用方应先查重）。
     */
    public PlayerRecord.Mail appendSystemMail(PlayerRecord rec, int mailType,
                                              List<String> titleParms, List<String> paras,
                                              String grantKey,
                                              int gold, int diamond, int stamina, int exp, int jjcScore,
                                              int wannengFragments, int yingPo, int moFaChen,
                                              int brotherCoin, int courageCoin,
                                              List<PlayerRecord.MailItem> items) {
        if (rec == null) {
            throw new IllegalArgumentException("player is null");
        }
        if (mailType <= 0) {
            throw new IllegalArgumentException(
                    "mailType 必须 >0（Sys_MailConfig 模板 ID），禁止 mailType=0 空标题下发");
        }
        rec.ensureCollections();
        PlayerRecord.Mail mail = newMail(rec);
        mail.mailType = mailType;
        mail.sender = "系统";
        if (grantKey != null && !grantKey.isEmpty()) {
            mail.grantKey = grantKey;
            rec.economy.mailGrantKeys.add(grantKey);
        }
        if (titleParms != null) {
            for (String p : titleParms) {
                if (p != null && !p.isEmpty()) {
                    mail.titleParm.add(p);
                }
            }
        }
        if (paras != null) {
            for (String p : paras) {
                if (p != null && !p.isEmpty()) {
                    mail.paras.add(p);
                }
            }
        }
        mail.gold = Math.max(0, gold);
        mail.diamond = Math.max(0, diamond);
        mail.stamina = Math.max(0, stamina);
        mail.exp = Math.max(0, exp);
        mail.jjcScore = Math.max(0, jjcScore);
        mail.wannengFragments = Math.max(0, wannengFragments);
        mail.yingPo = Math.max(0, yingPo);
        mail.moFaChen = Math.max(0, moFaChen);
        mail.brotherCoin = Math.max(0, brotherCoin);
        mail.courageCoin = Math.max(0, courageCoin);
        if (items != null) {
            for (PlayerRecord.MailItem it : items) {
                if (it == null) {
                    continue;
                }
                addItem(mail, it.ori, it.count, it.stars);
            }
        }
        // 存档可读副本（客户端展示仍只看 mailType + 占位）
        if (!mail.titleParm.isEmpty()) {
            mail.title = mail.titleParm.get(0);
        }
        if (!mail.paras.isEmpty()) {
            mail.text = mail.paras.get(0);
        }
        rec.mails.add(mail);
        return mail;
    }

    private boolean grantJjcRank(PlayerRecord rec, LocalDate day) {
        String key = day + "/jjc-rank";
        if (rec.economy.mailGrantKeys.contains(key)) {
            return false;
        }
        GameTables.RankPrizeRow prize = tables.prizeForRank(rec.arena.rank);
        int gold = prize == null ? 0 : prize.gold;
        int diamond = prize == null ? 0 : prize.diamond;
        int jjcScore = prize == null ? 0 : prize.jjcScore;
        List<PlayerRecord.MailItem> items = new ArrayList<>();
        if (prize != null) {
            addItemToList(items, prize.goods1, prize.goods1Num, 0);
            addItemToList(items, prize.goods2, prize.goods2Num, 0);
        }
        PlayerRecord.Mail mail = appendSystemMail(rec, MAIL_TYPE_JJC_RANK,
                null,
                Collections.singletonList(String.valueOf(rec.arena.rank)),
                key,
                gold, diamond, 0, 0, jjcScore, 0, 0, 0, 0, 0,
                items);
        log.info("{} jjc rank mail rank={} jf={} diamond={}", rec.account, rec.arena.rank,
                mail.jjcScore, mail.diamond);
        return true;
    }

    /** 争霸排位日排名邮：mailType=19；表 ZhengBaZhan 排位赛排名奖励；仅 jiFen>0。 */
    private boolean grantZbzRank(PlayerRecord rec, LocalDate day) {
        String key = day + "/zbz-rank";
        if (rec.economy.mailGrantKeys.contains(key)) {
            return false;
        }
        rec.ensureCollections();
        if (rec.zbz.jiFen <= 0) {
            return false;
        }
        int rank = zbz.resolveMyRank(rec);
        GameTables.ZbzRankPrizeRow prize = tables.zbzPrizeForRank(rank);
        if (prize == null) {
            return false;
        }
        PlayerRecord.Mail mail = appendSystemMail(rec, MAIL_TYPE_ZBZ,
                Collections.singletonList("排位赛排名奖励"),
                Collections.singletonList("第" + rank + "名"),
                key,
                prize.gold, prize.diamond, 0, 0, 0,
                prize.wnsp, prize.yingPo, 0, 0, 0,
                null);
        log.info("{} zbz rank mail rank={} gold={} yingPo={} wnsp={}",
                rec.account, rank, mail.gold, mail.yingPo, mail.wannengFragments);
        return true;
    }

    /** 跨服排位日排名邮：mailType=20；表 KuaFuZhanPrize 排位赛排名奖励。 */
    private boolean grantKfzPaiWeiRank(PlayerRecord rec, LocalDate day) {
        String key = day + "/kfz-paiwei-rank";
        if (rec.economy.mailGrantKeys.contains(key)) {
            return false;
        }
        rec.ensureCollections();
        int rank = Math.max(1, rec.kfz.rank);
        GameTables.KfzRankPrizeRow prize = tables.kfzPaiWeiPrizeForRank(rank);
        if (prize == null) {
            return false;
        }
        List<PlayerRecord.MailItem> items = new ArrayList<>();
        addItemToList(items, prize.goods1, prize.goods1Count, 0);
        addItemToList(items, prize.goods2, prize.goods2Count, 0);
        PlayerRecord.Mail mail = appendSystemMail(rec, MAIL_TYPE_KFZ,
                Collections.singletonList("排位赛排名奖励"),
                Collections.singletonList("第" + rank + "名"),
                key,
                prize.gold, prize.diamond, 0, 0, prize.jjc, 0, 0, 0, 0, 0,
                items);
        rec.kfz.paiWeiRankMailDay = day.toString();
        log.info("{} kfz paiwei rank mail rank={} gold={} jjc={}",
                rec.account, rank, mail.gold, mail.jjcScore);
        return true;
    }

    /** 跨服巅峰排名邮：mailType=20；表巅峰对决排名奖励；有淘汰名次才发。 */
    private boolean grantKfzDfzRank(PlayerRecord rec, LocalDate day) {
        String key = day + "/kfz-dfz-rank";
        if (rec.economy.mailGrantKeys.contains(key)) {
            return false;
        }
        rec.ensureCollections();
        int rank = 0;
        if (rec.kfz.dfzBracket != null) {
            for (PlayerRecord.KfzDfzSlot s : rec.kfz.dfzBracket) {
                if (s != null && s.guid == rec.playerId && s.rank > 0) {
                    rank = s.rank;
                    break;
                }
            }
        }
        if (rank <= 0) {
            return false;
        }
        GameTables.KfzRankPrizeRow prize = tables.kfzDfzPrizeForRank(rank);
        if (prize == null) {
            return false;
        }
        List<PlayerRecord.MailItem> items = new ArrayList<>();
        addItemToList(items, prize.goods1, prize.goods1Count, 0);
        addItemToList(items, prize.goods2, prize.goods2Count, 0);
        PlayerRecord.Mail mail = appendSystemMail(rec, MAIL_TYPE_KFZ,
                Collections.singletonList("巅峰对决排名奖励"),
                Collections.singletonList("第" + rank + "名"),
                key,
                prize.gold, prize.diamond, 0, 0, prize.jjc, 0, 0, 0, 0, 0,
                items);
        rec.kfz.dfzRankMailDay = day.toString();
        log.info("{} kfz dfz rank mail rank={} gold={}", rec.account, rank, mail.gold);
        return true;
    }

    private void claim(GameSession session, GamePacket pkt, PlayerRecord rec, PlayerRecord.Mail mail) {
        rec.gold += mail.gold;
        rec.diamond += mail.diamond;
        rec.stamina += mail.stamina;
        rec.exp += mail.exp;
        rec.jjcScore += mail.jjcScore;
        rec.wannengFragments += mail.wannengFragments;
        rec.yingPo += mail.yingPo;
        rec.moFaChen += mail.moFaChen;
        rec.guild.brotherCoin += mail.brotherCoin;
        rec.guild.courageCoin += mail.courageCoin;
        Map<String, Integer> changed = progress.emptyChanged();
        List<PlayerRecord.Equipment> newEq = new ArrayList<>();
        if (mail.items != null) {
            for (PlayerRecord.MailItem it : mail.items) {
                if (it == null || it.ori == null || it.ori.isEmpty() || "0".equals(it.ori) || it.count <= 0) {
                    continue;
                }
                if (cultivate.equip(it.ori) != null) {
                    for (int i = 0; i < it.count; i++) {
                        PlayerRecord.Equipment eq = progress.grantEquip(rec, it.ori);
                        eq.stars = it.stars;
                        newEq.add(eq);
                    }
                } else {
                    progress.addGoods(rec, it.ori, it.count);
                    progress.markGoods(changed, it.ori);
                }
            }
        }
        if (mail.gold > 0) {
            progress.pushGold(session, pkt, rec);
        }
        if (mail.diamond > 0) {
            progress.pushDiamond(session, pkt, rec);
        }
        if (mail.stamina > 0) {
            progress.pushPlayerProgress(session, pkt, rec, false, false, true, false, false);
        }
        if (mail.exp > 0) {
            progress.pushPlayerProgress(session, pkt, rec, true, false, false, false, false);
        }
        if (mail.jjcScore > 0) {
            progress.pushJjcScore(session, pkt, rec);
        }
        if (mail.wannengFragments > 0) {
            progress.pushWnsp(session, pkt, rec);
        }
        if (mail.yingPo > 0) {
            progress.pushYingPo(session, pkt, rec);
        }
        if (mail.moFaChen > 0) {
            progress.pushMoFaChen(session, pkt, rec);
        }
        if (mail.brotherCoin > 0 || mail.courageCoin > 0) {
            session.send(MsgIds.S2C_UNION_PLAYER_RES, pkt, dump.unionPlayerRes(rec));
        }
        progress.pushGoods(session, pkt, rec, changed);
        for (PlayerRecord.Equipment eq : newEq) {
            session.send(MsgIds.S2C_ADD_EQUIP, pkt, dump.equipment(eq));
        }
    }

    private static PlayerRecord.Mail newMail(PlayerRecord rec) {
        PlayerRecord.Mail mail = new PlayerRecord.Mail();
        mail.kind = "sys";
        mail.mailDynId = rec.nextMailSeq++;
        mail.sendTime = PlayerDumpService.now();
        mail.items = new ArrayList<>();
        mail.paras = new ArrayList<>();
        mail.titleParm = new ArrayList<>();
        return mail;
    }

    private static void addItem(PlayerRecord.Mail mail, String ori, int count, int stars) {
        if (ori == null || ori.isEmpty() || "0".equals(ori) || count <= 0) {
            return;
        }
        PlayerRecord.MailItem it = new PlayerRecord.MailItem();
        it.ori = ori;
        it.count = count;
        it.stars = stars;
        mail.items.add(it);
    }

    private static void addItemToList(List<PlayerRecord.MailItem> items, String ori, int count, int stars) {
        if (ori == null || ori.isEmpty() || "0".equals(ori) || count <= 0) {
            return;
        }
        PlayerRecord.MailItem it = new PlayerRecord.MailItem();
        it.ori = ori;
        it.count = count;
        it.stars = stars;
        items.add(it);
    }

    private static PlayerRecord.Mail find(PlayerRecord rec, int dynId) {
        for (PlayerRecord.Mail m : rec.mails) {
            if (m.mailDynId == dynId) {
                return m;
            }
        }
        return null;
    }

    private static boolean reached(LocalDateTime now, int hour, int minute) {
        LocalTime t = LocalTime.of(Math.max(0, Math.min(23, hour)), Math.max(0, Math.min(59, minute)));
        return !now.toLocalTime().isBefore(t);
    }
}
