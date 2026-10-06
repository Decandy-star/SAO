package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.AnnounceCfg;
import com.sao.fakeserver.table.GiftCodeCfg;
import com.sao.fakeserver.util.GameTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 礼包码 / 激活码 / 系统公告 —— 对应协议 C2S 20、C2S 30，S2C 20、21、22、30。
 * <p>
 * 客户端行为依据（out2 = {@code decompiled\_cache\verify_base\out2\Client}）：
 * <ul>
 *   <li>礼包码入口只在 detail {@code field 86 EnableLiBaoMa} 为真时显示
 *       （{@code MobileGameDemo\MainPlayerSystem.cs:116-119}）；点击确定后本地校验长度 2..32，
 *       再发 C2S 20（{@code MainPlayerSystem.cs:501-515}）。回包 S2C 20 只发事件
 *       {@code EN_GIFT_PACK_RET}，界面按 {@code ret}(0..3) 取提示文案
 *       （{@code MainPlayerSystem.cs:136-146}）。</li>
 *   <li>激活码面板由服务端 S2C 21 弹（{@code ᜲ.cs:247-255} → {@code UIModulesManager.cs:5642}），
 *       提交后发 C2S 30；客户端只注册失败回包 S2C 22（ret 1/2 → 两种提示，{@code ᜲ.cs:258-275}），
 *       <b>成功没有专用回包</b>，也不自动关面板（全 out2 无 {@code EN_CLOSE_ACTIVATION_UI} 发送点）。</li>
 *   <li>公告 S2C 30 分派见 {@code ᝁ.cs:5253-5278}：type 1(缺省)=走马灯、5=喇叭大字、
 *       2/3/4/5=聊天面板系统行。</li>
 * </ul>
 * 全服使用次数（{@code GIFT_PACK_RET_KeyUsed}=2）假服无跨账号存档，故不下发，见类注释与
 * {@code docs/PROTOCOL_GAP_REPORT.md}。
 */
@Service
public class GiftCodeService {
    private static final Logger log = LoggerFactory.getLogger(GiftCodeService.class);

    /** GIFT_PACK_RET：兑换成功。 */
    public static final int RET_OK = 0;
    /** GIFT_PACK_RET：码不存在 / 已过期。 */
    public static final int RET_INVALID = 1;
    /** GIFT_PACK_RET：全服次数用尽（本假服不会回这个值）。 */
    public static final int RET_KEY_USED = 2;
    /** GIFT_PACK_RET：本账号已兑换过。 */
    public static final int RET_ALREADY = 3;

    private final PlayerStore store;
    private final PlayerDumpService dump;
    private final ProgressService progress;
    private final GiftCodeCfg cfg;
    private final AnnounceCfg announce;

    public GiftCodeService(PlayerStore store, PlayerDumpService dump, ProgressService progress,
                           GiftCodeCfg cfg, AnnounceCfg announce) {
        this.store = store;
        this.dump = dump;
        this.progress = progress;
        this.cfg = cfg;
        this.announce = announce;
    }

    // ---------------------------------------------------------------- C2S 20

    /** C2S 20 {@code CMsgRequestGiftPack{key}} → S2C 20 {@code CMsgGiftPackRet{ret}}。 */
    public void onRequestGiftPack(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        String key = Pb.read(pkt.body).getString(1);
        String code = key == null ? "" : key.trim();
        GiftCodeCfg.Code item = cfg.gift(code);

        int ret;
        if (code.isEmpty() || item == null || expired(item)) {
            ret = RET_INVALID;
        } else if (item.oncePerAccount && rec.usedGiftCodes.contains(code)) {
            ret = RET_ALREADY;
        } else {
            grant(session, pkt, rec, item.reward);
            if (!rec.usedGiftCodes.contains(code)) {
                rec.usedGiftCodes.add(code);
            }
            store.save(rec);
            ret = RET_OK;
        }
        session.send(MsgIds.S2C_GIFT_PACK_RET, pkt, dump.giftPackRet(ret));
        log.info("{} gift code [{}] -> ret={}", rec.account, code, Integer.valueOf(ret));
    }

    // ---------------------------------------------------------------- C2S 30

    /** C2S 30 {@code CCMsg_Account_Check_JiHuoMa{account,jihuoma,deviceid}}；失败发 S2C 22。 */
    public void onActivationCode(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        String account = f.getString(1);
        String raw = f.getString(2);
        String device = f.getString(3);
        String code = raw == null ? "" : raw.trim();
        GiftCodeCfg.Code item = cfg.activation(code);

        // 客户端本地已挡「长度必须 12」，这里再挡一次，保证长度非法时也有明确回包
        if (code.length() != 12 || item == null || expired(item)) {
            session.send(MsgIds.S2C_ACTIVATION_FAIL, pkt, dump.activationFail(1));
            log.info("{} activation account={} device={} code=[{}] -> invalid",
                    rec.account, account, device, code);
            return;
        }
        if (item.oncePerAccount && rec.usedActivationCodes.contains(code)) {
            session.send(MsgIds.S2C_ACTIVATION_FAIL, pkt, dump.activationFail(2));
            log.info("{} activation code=[{}] -> already used", rec.account, code);
            return;
        }
        grant(session, pkt, rec, item.reward);
        if (!rec.usedActivationCodes.contains(code)) {
            rec.usedActivationCodes.add(code);
        }
        store.save(rec);
        // 成功无专用回包：客户端不关面板，靠玩家自己返回
        log.info("{} activation code=[{}] -> granted", rec.account, code);
    }

    // ------------------------------------------------------------- 登录推送

    /** 登录时推系统公告，并按配置弹一次激活码输入 UI。 */
    public void onLogin(GameSession session, GamePacket pkt, PlayerRecord rec) {
        if (rec == null) {
            return;
        }
        pushAnnouncements(session, pkt);
        if (cfg.isPromptActivationOnLogin() && !rec.activationPrompted) {
            rec.activationPrompted = true;
            store.save(rec);
            session.send(MsgIds.S2C_ACTIVATION_NOTIFY, pkt, dump.activationNotify(rec.account));
            log.info("{} activation prompt pushed", rec.account);
        }
    }

    /** 推 {@code tables/announcements.json} 里配的公告（登录时调）。 */
    public void pushAnnouncements(GameSession session, GamePacket pkt) {
        for (AnnounceCfg.Item it : announce.loginItems()) {
            session.send(MsgIds.S2C_SYS_ANNOUNCEMENT, pkt, dump.sysAnnouncement(it));
        }
    }

    // ------------------------------------------------------------------ 内部

    private boolean expired(GiftCodeCfg.Code item) {
        String s = item.expiresAt;
        if (s == null || s.trim().isEmpty()) {
            return false;
        }
        try {
            // 到期当天仍可用
            return GameTime.today().isAfter(LocalDate.parse(s.trim()));
        } catch (DateTimeParseException e) {
            log.warn("bad expiresAt [{}] in gift-code.json — treated as never expires", s);
            return false;
        }
    }

    /** 发奖：货币直加字段，道具走背包；逐项推包。 */
    private void grant(GameSession session, GamePacket pkt, PlayerRecord rec, GiftCodeCfg.Reward r) {
        if (r == null) {
            return;
        }
        Map<String, Integer> changed = new LinkedHashMap<>();
        if (r.gold > 0) {
            rec.gold += r.gold;
        }
        if (r.diamond > 0) {
            rec.diamond += r.diamond;
        }
        if (r.stamina > 0) {
            rec.stamina += r.stamina;
        }
        if (r.wannengFragments > 0) {
            rec.wannengFragments += r.wannengFragments;
        }
        if (r.goods != null) {
            for (GiftCodeCfg.Goods g : r.goods) {
                if (g == null || g.ori == null || g.ori.trim().isEmpty() || g.count <= 0) {
                    continue;
                }
                progress.addGoods(rec, g.ori.trim(), g.count);
                progress.markGoods(changed, g.ori.trim());
            }
        }
        if (!changed.isEmpty()) {
            progress.pushGoods(session, pkt, rec, changed);
        }
        if (r.gold > 0) {
            progress.pushGold(session, pkt, rec);
        }
        if (r.diamond > 0) {
            progress.pushDiamond(session, pkt, rec);
        }
        if (r.stamina > 0) {
            progress.pushStamina(session, pkt, rec);
        }
        if (r.wannengFragments > 0) {
            progress.pushWnsp(session, pkt, rec);
        }
    }
}
