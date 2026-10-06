package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.EconomyTables;
import com.sao.fakeserver.table.VipGiftCfg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * VIP 礼包（看板 type5「VIP特权礼包」）：C2S 2309 查档位 / 2310 查已购 / 2311 购买。
 *
 * <p>与「VIP 等级奖励」（C2S 2701 → S2C 3101，{@code VipCfg.txt} 35-46 列，见
 * {@link PayService#onVipLevelAward}）和「VIP 邮件 8/9」（{@code vip-mail.json}，见
 * {@link MailService}）是**三套不同的东西**：礼包是花钻石买道具，等级奖励是达标免费领，
 * 邮件是每天/升级自动发。
 *
 * <p>客户端链路（{@code VipBuyItem.cs} / {@code ActivityPropertyMgr.cs} / {@code PlayGameState.cs}）：
 * <ul>
 *   <li>2309（空包）→ 2617 {@code items[]} → {@code UpdateVIPGiftCfg}（{@code :313-332}）填
 *       {@code mVipBuy} → {@code RefreshVipBuyGiftAll()} 重建面板。</li>
 *   <li>2310（空包，{@code MainPlayer.cs:471} **每次登录必发**）→ 2609 {@code curBoughtVIPGifts}
 *       （{@code "|id|id|"} 串）→ {@code ActivityMainUI.RefreshVIPBuyed}（{@code :2787-2789}），
 *       面板用 {@code Contains("|id|")} 判「已购/可买」（{@code :1167}、
 *       {@code ActivityPropertyMgr.HasVipBuyItem :232-242} 还用它点红点）。</li>
 *   <li>2311 {@code vipGiftID} → 2610 {@code buyID} → {@code PlayGameState.cs:247} 注册的
 *       {@code OnBuyVIPGift_Ret}（{@code :7054-7073}）：用 buyID 在 {@code mVipBuy} 里查**配置**
 *       （不是已购），命中冒字 {@code 100151}「获得 X xN」+ {@code RefreshVipTitle()}，
 *       未命中 {@code Debug.LogError}。**2610 是成功提示**，故本服务成功时回 2610 并重推 2609。</li>
 * </ul>
 *
 * <p>档位数据在 {@code tables\vip-gift.json}（APK 无此表，默认空）。拒绝路径一律**不回 2610**
 * （2610 会被客户端当成功提示弹字，回了等于假报成功），改为重推 2609 让面板回到
 * 真实状态 —— 与 LTSJ 的拒绝路径重推 3702 同一思路。
 */
@Service
public class VipGiftService {
    private static final Logger log = LoggerFactory.getLogger(VipGiftService.class);

    private final VipGiftCfg cfg;
    private final EconomyTables tables;
    private final PlayerStore store;
    private final PlayerDumpService dump;
    private final ProgressService progress;

    public VipGiftService(VipGiftCfg cfg, EconomyTables tables, PlayerStore store,
                          PlayerDumpService dump, ProgressService progress) {
        this.cfg = cfg;
        this.tables = tables;
        this.store = store;
        this.dump = dump;
        this.progress = progress;
    }

    /** C2S 2309（空包）查档位 → S2C 2617；空表也要回（客户端靠回包清空 mVipBuy）。 */
    public void onConfigQuery(GameSession session, GamePacket pkt) {
        List<byte[]> items = new ArrayList<>();
        for (VipGiftCfg.Item it : cfg.items()) {
            items.add(dump.vipGiftItem(it.id, it.vipLevel, it.ori, it.count, it.needRmb));
        }
        session.send(MsgIds.S2C_VIP_GIFT_CONFIG_RET, pkt, dump.vipGiftConfigRet(items));
        log.info("{} vip gift config query items={}", session.player() == null ? "-" : session.player().account,
                Integer.valueOf(items.size()));
    }

    /** C2S 2310（空包）查已购 → S2C 2609；登录必发，必须回（否则面板保留上一次的已购串）。 */
    public void onBoughtQuery(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        sendBought(session, pkt, rec);
    }

    /** C2S 2311 购买 → S2C 2610（成功）；拒绝只重推 2609。 */
    public void onBuy(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.ensureCollections();
        int id = Pb.read(pkt.body).getInt(1, 0);
        VipGiftCfg.Item it = cfg.item(id);
        if (it == null) {
            log.info("{} vip gift buy deny: no such item id={}", rec.account, Integer.valueOf(id));
            sendBought(session, pkt, rec);
            return;
        }
        if (rec.economy.vipBuyedGifts.contains("|" + id + "|")) {
            log.info("{} vip gift buy deny: already bought id={}", rec.account, Integer.valueOf(id));
            sendBought(session, pkt, rec);
            return;
        }
        // 与客户端 VipBuyItem.cs:155-161 同口径：GetVIPLevel() 由 mCurBuyZuanShi(= chargedDiamond) 现算
        int vip = tables.vipLevel(rec.economy.chargedDiamond);
        if (vip < it.vipLevel) {
            log.info("{} vip gift buy deny: vip={} need={} id={}", rec.account, Integer.valueOf(vip),
                    Integer.valueOf(it.vipLevel), Integer.valueOf(id));
            sendBought(session, pkt, rec);
            return;
        }
        // 与客户端 VipBuyItem.cs:136 同口径：mCurRMB 就是钻石余额（登录 detail field7 = rec.diamond）
        if (rec.diamond < it.needRmb) {
            log.info("{} vip gift buy deny: diamond={} need={} id={}", rec.account,
                    Integer.valueOf(rec.diamond), Integer.valueOf(it.needRmb), Integer.valueOf(id));
            progress.pushDiamond(session, pkt, rec);
            sendBought(session, pkt, rec);
            return;
        }

        Map<String, Integer> changed = progress.emptyChanged();
        List<PlayerRecord.Equipment> newEq =
                progress.grantReward(rec, it.ori, it.count, changed);
        rec.diamond -= it.needRmb;
        // 买礼包花的是钻石，与其它钻石消耗入口同口径记账当日消耗并推 EAttriType=18
        // （LtsjService.java:210 抽卡、ArenaService.java:331 买次数、ActivityService.java:623 等同样走 addTodayCost）
        progress.addTodayCost(session, pkt, rec, it.needRmb);
        rec.economy.vipBuyedGifts = rec.economy.vipBuyedGifts + "|" + id + "|";
        store.save(rec);
        progress.pushDiamond(session, pkt, rec);
        progress.pushGoods(session, pkt, rec, changed);
        progress.pushEquips(session, pkt, newEq);
        session.send(MsgIds.S2C_VIP_GIFT_BUY_RET, pkt, dump.vipGiftBuyRet(id));
        sendBought(session, pkt, rec);
        log.info("{} vip gift buy ok id={} ori={} count={} cost={} diamond={}", rec.account,
                Integer.valueOf(id), it.ori, Integer.valueOf(it.count), Integer.valueOf(it.needRmb),
                Integer.valueOf(rec.diamond));
    }

    /** S2C 2609：已购串（购买成功后也推一次，让面板的「已购」状态立即生效）。 */
    private void sendBought(GameSession session, GamePacket pkt, PlayerRecord rec) {
        session.send(MsgIds.S2C_VIP_GIFT_BOUGHT_RET, pkt,
                dump.vipGiftBoughtRet(rec.economy.vipBuyedGifts));
    }
}
