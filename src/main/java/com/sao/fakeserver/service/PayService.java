package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.CultivateTables;
import com.sao.fakeserver.table.EconomyTables;
import com.sao.fakeserver.util.GameTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class PayService {
    private static final Logger log = LoggerFactory.getLogger(PayService.class);

    private final PlayerStore store;
    private final PlayerDumpService dump;
    private final ProgressService progress;
    private final MailService mail;
    private final EconomyTables tables;
    private final CultivateTables cultivate;

    public PayService(PlayerStore store, PlayerDumpService dump, ProgressService progress,
                      MailService mail, EconomyTables tables, CultivateTables cultivate) {
        this.store = store;
        this.dump = dump;
        this.progress = progress;
        this.mail = mail;
        this.tables = tables;
        this.cultivate = cultivate;
    }

    public void onSdkPayId(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        int goodsId = Pb.read(pkt.body).getInt(1, 0);
        EconomyTables.PayRow row = tables.pay(goodsId);
        if (row == null) {
            // 客户端只会发 UserPayGoods.txt / UserPayGoods_1st.txt 里的 ID（UserPayCfg.GetPayCfg）。
            // 表外 ID 不发奖，回空 orderID → 客户端走 OnSdkPayIdAskRet 的 orderID.Length==0 分支
            // （提示 100840 + 复位 VIPUISystem.mWaitChongZhiServerBack），不静默送钻。
            log.warn("{} sdk pay unknown goods={} -> reject", rec.account, goodsId);
            session.send(MsgIds.S2C_SDK_PAY_ID, pkt, dump.payIdRet("", goodsId));
            return;
        }
        String orderId = rec.account + "-pay-" + System.currentTimeMillis();
        // 先发钻/VIP/限次进度，再回 3401，避免客户端先调渠道 SDK 抛错打断后续包
        grant(session, pkt, rec, row);
        session.send(MsgIds.S2C_SDK_PAY_ID, pkt, dump.payIdRet(orderId, goodsId));
        session.send(MsgIds.S2C_PAY_SUC, pkt, dump.paySuc(goodsId));
        log.info("{} sdk pay goods={} diamond={} charged={} vip={} extMonth={}",
                rec.account, goodsId, rec.diamond, rec.economy.chargedDiamond,
                tables.vipLevel(rec.economy.chargedDiamond), rec.economy.payExtMonthKey);
    }

    /**
     * C2S 3501（月卡）/ 3502（至尊卡）：玩家点「领取」结算当日返钻 → 回 S2C 3901。
     *
     * <p>客户端 TaskSystem.cs:799-853 点击时先弹本地确认框（奖励数字取 TeQuanCard.txt 的每日返钻），
     * 确认后才发 3501/3502；服务端结算完必须回 3901（CMsgTeQuanCardInfo）——
     * 客户端 OnTeQuanCardInfoUpdate（PlayGameState.cs:7322-7338）用它的 todayHasAward* 刷
     * mTodayHasAwardZhiZunCard/mTodayHasAwardTeQuanCard 并发 EN_REFRESH_TEQUANFULI 复位按钮。
     *
     * <p>0 点群发邮件的老做法已撤（MailService.grantCardDailies 删除）：那会在玩家没点之前就把
     * todayZhiZun/todayTeQuan 置位，客户端「领取」按钮整天显示已领取、3501/3502 永远发不出去，
     * 与 MainPlayer.cs:1647-1650 的红点逻辑也冲突。日返改为按「点领」结算。
     */
    public void onTeQuanDaily(GameSession session, GamePacket pkt, boolean zhiZun) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        progress.ensureDaily(rec);
        EconomyTables.TeQuanRow cfg = tables.teQuan(zhiZun ? 4 : 2);
        int daily = cfg == null ? 120 : Math.max(0, cfg.dailyDiamond);
        // 每日返物品（TeQuanCard.txt 列 8/9）。APK 自带表为 0 → 当前只有返钻；填表即按表发。
        String dailyOri = cfg == null ? "" : cfg.dailyGoodsOri;
        int dailyCnt = cfg == null ? 0 : Math.max(0, cfg.dailyGoodsCount);
        boolean hasItems = !dailyOri.isEmpty() && dailyCnt > 0;
        boolean owned = zhiZun ? rec.cards.zhiZun : progress.monthlyCardActive(rec);
        boolean claimed = zhiZun ? rec.cards.todayZhiZun : rec.cards.todayTeQuan;
        if (!owned || claimed || (daily <= 0 && !hasItems)) {
            log.info("{} tequan daily skip zhiZun={} owned={} claimed={} daily={} goods={}",
                    rec.account, zhiZun, owned, claimed, daily, hasItems);
            session.send(MsgIds.S2C_TEQUAN_INFO, pkt, dump.teQuanInfo(rec));
            return;
        }
        rec.diamond += daily;
        if (zhiZun) {
            rec.cards.todayZhiZun = true;
        } else {
            rec.cards.todayTeQuan = true;
        }
        Map<String, Integer> changed = progress.emptyChanged();
        List<PlayerRecord.Equipment> newEq = hasItems
                ? progress.grantReward(rec, dailyOri, dailyCnt, changed)
                : java.util.Collections.<PlayerRecord.Equipment>emptyList();
        store.save(rec);
        progress.pushDiamond(session, pkt, rec);
        progress.pushGoods(session, pkt, rec, changed);
        progress.pushEquips(session, pkt, newEq);
        session.send(MsgIds.S2C_TEQUAN_INFO, pkt, dump.teQuanInfo(rec));
        log.info("{} tequan daily claimed zhiZun={} diamond={} goods={}x{}", rec.account, zhiZun,
                daily, dailyOri, dailyCnt);
    }

    public void onSdkPayCheck(GameSession session, GamePacket pkt) {
        String orderId = Pb.read(pkt.body).getString(1);
        session.send(MsgIds.S2C_SDK_PAY_CHECK, pkt, dump.payCheckRet(orderId));
    }

    public void onQqPay(GameSession session, GamePacket pkt) {
        /* 客户端只上报 QQ 支付票据，假服不回 3701 */
    }

    /**
     * C2S 3401：本 Android 包客户端没有任何 CCMsgApplePay 发送点（全 client-src grep "apple" 0 命中），
     * 能走到这里只可能是伪造包 —— 原来在此白送 6 元档（grant(8) + S2C 3403），等于可被刷钻，已撤。
     * 另外客户端 3801 是 C2S（BaoShiHeChengUI.cs:385-387 CCMsgTimeStoneChangeColour），
     * 假服把 3801 当 Apple 回包本就方向反了；保留 handler 只为协议占位。
     */
    public void onApplePay(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        log.info("{} apple pay ignored (no client sender)", rec == null ? "?" : rec.account);
    }

    /** C2S 2701：领取 VipCfg 对应等级礼包 → S2C 3101。 */
    public void onVipLevelAward(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        if (rec.economy.vipAwardGetInfo == null) {
            rec.economy.vipAwardGetInfo = "";
        }
        int level = Pb.read(pkt.body).getInt(1, 0);
        int vip = tables.vipLevel(rec.economy.chargedDiamond);
        // 三个拒绝分支都必须回 3101：客户端 VIPUISystem.cs:431 点「领取」就进 WaitSever()，
        // 唯一复位入口是 3101 触发的 EN_REFRESH_VIP_TEQUAN_UI（VIPUISystem.cs:319-321）；
        // 新号 VIP0 打开礼包页必发 level=1（VIPUISystem.cs:123-135），不回包按钮会永久停在「等待服务器」。
        if (level <= 0 || level > vip) {
            log.info("{} vip award skip level={} vip={}", rec.account, level, vip);
            session.send(MsgIds.S2C_VIP_LEVEL_AWARD_RET, pkt,
                    dump.vipLevelAwardRet(rec.economy.vipAwardGetInfo, java.util.Collections.emptyList()));
            return;
        }
        String mark = "|" + level + "|";
        if (rec.economy.vipAwardGetInfo.contains(mark)) {
            log.info("{} vip award already level={}", rec.account, level);
            session.send(MsgIds.S2C_VIP_LEVEL_AWARD_RET, pkt,
                    dump.vipLevelAwardRet(rec.economy.vipAwardGetInfo, java.util.Collections.emptyList()));
            return;
        }
        EconomyTables.VipRow row = tables.vipByLevel(level);
        if (row == null || row.awards.isEmpty()) {
            log.info("{} vip award empty level={}", rec.account, level);
            session.send(MsgIds.S2C_VIP_LEVEL_AWARD_RET, pkt,
                    dump.vipLevelAwardRet(rec.economy.vipAwardGetInfo, java.util.Collections.emptyList()));
            return;
        }

        Map<String, Integer> changed = progress.emptyChanged();
        List<PlayerRecord.Equipment> newEq = new ArrayList<>();
        List<byte[]> items = new ArrayList<>();
        for (EconomyTables.VipAward a : row.awards) {
            if (a == null || a.ori == null || a.ori.isEmpty() || a.count <= 0) {
                continue;
            }
            if (cultivate.equip(a.ori) != null) {
                for (int i = 0; i < a.count; i++) {
                    PlayerRecord.Equipment eq = progress.grantEquip(rec, a.ori);
                    eq.stars = a.stars;
                    newEq.add(eq);
                }
            } else {
                progress.addGoods(rec, a.ori, a.count);
                progress.markGoods(changed, a.ori);
            }
            items.add(dump.vipAwardItem(a.ori, a.count, a.stars));
        }
        rec.economy.vipAwardGetInfo = rec.economy.vipAwardGetInfo + mark;
        store.save(rec);
        progress.pushGoods(session, pkt, rec, changed);
        for (PlayerRecord.Equipment eq : newEq) {
            session.send(MsgIds.S2C_ADD_EQUIP, pkt, dump.equipment(eq));
        }
        session.send(MsgIds.S2C_VIP_LEVEL_AWARD_RET, pkt, dump.vipLevelAwardRet(rec.economy.vipAwardGetInfo, items));
        log.info("{} vip award level={} items={}", rec.account, level, items.size());
    }

    /**
     * 兼容入口：只做旧档 {@code firstPayIds} → {@code payBuyCounts} 的迁移，**不再跨月清空**。
     *
     * <p>2026-10-04 拍板「首充双倍不按月重置」：客户端 {@code MainPlayer.cs:2540}
     * {@code mHas1stChongZhi = UserPayCfg.GetCur1stChongZhiRMB() > 0U}，而
     * {@code GetCur1stChongZhiRMB()}（{@code UserPayCfg.cs:165-180}）只看 {@code UserPayInfo} 里
     * ID ≤ {@code msMax1stID}(=7) 的档 —— 服务端这份列表就是 {@code payBuyCounts} 的镜像
     * （{@code PlayerDumpService.userPayInfo}）。清空后客户端会把「已首充」判回未充值：
     * 充值页回到 1 次表（{@code GetCurBuyList()}）、{@code is1stCharge=true} 又显示
     * {@code ZuanShiCount*2} 的双倍数字、大厅/VIP 页首充提示复活。
     * 限次另赠（{@code ZuanShiExtAward}/{@code ZuanShiExtAwardTime}）同理按累计购买次数递减，
     * 客户端 {@code GetExtAwardTimeLeft()} 直接拿 {@code buyCount} 比对，协议里没有月份概念。
     */
    public void ensurePayExtMonth(PlayerRecord rec) {
        rec.ensureCollections();
        migrateFirstPayIds(rec);
    }

    /**
     * admin 手工重置充值进度（不清 VIP 累计 chargedDiamond）。
     * 注意：会把 {@code payBuyCounts} 清空 → 客户端首充状态与双倍显示会复活，仅供测试。
     */
    public void resetPayExt(PlayerRecord rec) {
        rec.ensureCollections();
        rec.economy.payExtMonthKey = GameTime.month().toString();
        rec.economy.payBuyCounts.clear();
        if (rec.economy.firstPayIds != null) {
            rec.economy.firstPayIds.clear();
        }
    }

    private void migrateFirstPayIds(PlayerRecord rec) {
        if (rec.economy.firstPayIds == null || rec.economy.firstPayIds.isEmpty()) {
            return;
        }
        for (Integer id : rec.economy.firstPayIds) {
            if (id == null) {
                continue;
            }
            if (!rec.economy.payBuyCounts.containsKey(id)) {
                rec.economy.payBuyCounts.put(id, Integer.valueOf(1));
            }
        }
        rec.economy.firstPayIds.clear();
    }

    private void grant(GameSession session, GamePacket pkt, PlayerRecord rec, EconomyTables.PayRow row) {
        ensurePayExtMonth(rec);
        if (row.type == 2 || row.type == 4) {
            grantCard(session, pkt, rec, row);
            return;
        }
        // vip 等级由 chargedDiamond 算出（EconomyTables.vipLevel），升级只能在充值处判定 → 发 mailType 9
        int vipBefore = tables.vipLevel(rec.economy.chargedDiamond);
        int goodsId = row.id;
        int bought = buyCount(rec, goodsId);
        boolean extOk = row.extTimes > 0 && bought < row.extTimes;
        // 客户端算式（PlayGameState.cs:6851）：
        //   ZuanShiCount + (GetExtAwardTimeLeft() > 0 ? ZuanShiExtAward : ZuanShiAward)
        // 即「常规赠送」与「限次另赠」是二选一，不是相加（VIPChongZhiItem.cs:59-71 的档位文案同样二选一）。
        // 相加会让 30/198/648 元档首购多发 15/200/1500 钻，且与充值弹窗显示的数字不符。
        int add = Math.max(0, row.diamond)
                + (extOk ? Math.max(0, row.extAward) : Math.max(0, row.award));
        rec.diamond += add;
        // VIP 只计「常规钻石」，不含常规赠送/限次另赠
        rec.economy.chargedDiamond += Math.max(0, row.diamond);
        rec.economy.rmbChongZhi += Math.max(0, row.rmb);
        rec.economy.curDayChongZhiRmb += Math.max(0, row.rmb);
        bumpBuyCount(rec, goodsId);
        store.save(rec);
        progress.pushDiamond(session, pkt, rec);
        progress.pushChargedDiamond(session, pkt, rec);
        progress.pushRmbChongZhi(session, pkt, rec);
        progress.pushTodayChongZhi(session, pkt, rec);
        session.send(MsgIds.S2C_USER_PAY_INFO, pkt, dump.userPayInfo(rec));
        int vipAfter = tables.vipLevel(rec.economy.chargedDiamond);
        mailVipLevelAdd(rec, vipBefore, vipAfter);
    }

    /**
     * 跨档充值后补发 mailType 9「vip提升奖励」，按跨过的每一级各发一封。
     * <p>APK 没有「提升奖励」表，只能按正文推：Sys_MailConfig 第 9 行「你现在是vip{0}级玩家，
     * 提升vip带来的每日奖励补充如下：」——每级一份补充。一次买齐跨多级时逐级补发
     * （0→VIP9 共 9 封），单档跳级与旧行为一致（1 封）。
     * 逐级防重键 "vip-add/"+level（MailService.sendVipLevelAdd）互不冲突，Paras 写该级等级，
     * 正文 {0} 替换成该级；vip-mail.json 的 add 本就是「相对上一级的增量」。
     */
    private void mailVipLevelAdd(PlayerRecord rec, int vipBefore, int vipAfter) {
        for (int lv = vipBefore + 1; lv <= vipAfter; lv++) {
            mail.sendVipLevelAdd(rec, lv);
        }
    }

    private static int buyCount(PlayerRecord rec, int goodsId) {
        Integer n = rec.economy.payBuyCounts.get(Integer.valueOf(goodsId));
        return n == null ? 0 : n.intValue();
    }

    private static void bumpBuyCount(PlayerRecord rec, int goodsId) {
        if (goodsId <= 0) {
            return;
        }
        Integer key = Integer.valueOf(goodsId);
        int n = buyCount(rec, goodsId) + 1;
        rec.economy.payBuyCounts.put(key, Integer.valueOf(n));
    }

    private void grantCard(GameSession session, GamePacket pkt, PlayerRecord rec, EconomyTables.PayRow row) {
        rec.ensureCollections();
        int vipBefore = tables.vipLevel(rec.economy.chargedDiamond);
        EconomyTables.TeQuanRow cfg = tables.teQuan(row.type);
        int buy = cfg == null ? row.diamond : cfg.buyDiamond;
        rec.diamond += Math.max(0, buy);
        if (row.type == 4) {
            rec.cards.zhiZun = true;
        } else if (row.type == 2) {
            int days = cfg == null ? 30 : Math.max(1, cfg.days);
            java.time.LocalDateTime base = GameTime.now();
            if (progress.monthlyCardActive(rec)) {
                try {
                    java.time.LocalDateTime end = java.time.LocalDateTime.parse(rec.cards.teQuanEnd, PlayerDumpService.TIME);
                    if (end.isAfter(base)) {
                        base = end;
                    }
                } catch (RuntimeException ignored) {
                }
            }
            rec.cards.teQuanEnd = base.plusDays(days).format(PlayerDumpService.TIME);
        }
        // 买卡当天不自动结算日返：客户端买卡后「领取」按钮应保持可点（todayZhiZun/todayTeQuan 为 false），
        // 由玩家点 3501/3502 结算（见 onTeQuanDaily）。
        // 买卡也是充值：VipCfg「累计钻石数量」按 UserPayGoods 的常规钻石数量累计
        // （1001 月卡 250 / 1003 至尊卡 980），累计充值/当日累充按充值金额累计（25 / 98）。
        // 客户端 mCurBuyZuanShi → VipManager 等级、curDayChongZhiRmb → 壕送大礼档位都读这两个计数。
        // 注意：不计入 payBuyCounts（首充判定 UserPayCfg.GetCur1stChongZhiRMB 只看 ID ≤ 7 的档）。
        rec.economy.chargedDiamond += Math.max(0, row.diamond);
        rec.economy.rmbChongZhi += Math.max(0, row.rmb);
        rec.economy.curDayChongZhiRmb += Math.max(0, row.rmb);
        // 购买立即返物品（TeQuanCard.txt 列 5/6）。APK 自带表两张卡这两列都是 0，故当前恒无物品；
        // 填了表就必须发，否则是静默漏发（客户端 TeQuanCardCfg.cs:87-91 会显示这项）。
        String buyOri = cfg == null ? "" : cfg.buyGoodsOri;
        int buyCnt = cfg == null ? 0 : Math.max(0, cfg.buyGoodsCount);
        Map<String, Integer> changed = progress.emptyChanged();
        List<PlayerRecord.Equipment> newEq = buyOri.isEmpty() || buyCnt <= 0
                ? java.util.Collections.<PlayerRecord.Equipment>emptyList()
                : progress.grantReward(rec, buyOri, buyCnt, changed);
        store.save(rec);
        progress.pushDiamond(session, pkt, rec);
        progress.pushChargedDiamond(session, pkt, rec);
        progress.pushRmbChongZhi(session, pkt, rec);
        progress.pushTodayChongZhi(session, pkt, rec);
        progress.pushGoods(session, pkt, rec, changed);
        progress.pushEquips(session, pkt, newEq);
        session.send(MsgIds.S2C_TEQUAN_INFO, pkt, dump.teQuanInfo(rec));
        session.send(MsgIds.S2C_TEQUAN_BUY, pkt, dump.teQuanAward(row.type, buy, buyOri, buyCnt));
        log.info("{} buy card type={} buy+{} rmb+{} charged+{} end={}",
                rec.account, row.type, buy, row.rmb, row.diamond, rec.cards.teQuanEnd);
        int vipAfter = tables.vipLevel(rec.economy.chargedDiamond);
        mailVipLevelAdd(rec, vipBefore, vipAfter);
    }
}
