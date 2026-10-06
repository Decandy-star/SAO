package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.AnnounceCfg;
import com.sao.fakeserver.table.EconomyTables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 喇叭（雪花道具）—— 对应 C2S 4701/4702、S2C 5201/5202（+ 走马灯 S2C 30 type=5）。
 *
 * <p>之前假服零实现。客户端侧：
 * <ul>
 *   <li>{@code BagUISystem.cs:870} 用 {@code mAttribute == 12} 把「使用」接到喇叭输入弹窗；
 *       {@code :468} 把道具的「属性自定义参数2」按 {@code |} 切开，{@code :474-480} 用第一段
 *       决定发 4701 还是 4702。</li>
 *   <li>发包后 {@code :484-485} <b>立即关面板、不扣道具、不等回包、不本地插条目</b>
 *       ⇒ 扣道具与广播都必须服务端做。</li>
 *   <li>5202 handler {@code ᝁ.cs:8492-8506} 用 tag2 {@code oriName} 查
 *       {@code GetPropertyCfg}：查不到就在 {@code :8494} 解引用 null 崩
 *       ⇒ 广播里的 oriName 必须是 {@code GoodsList} 里真实存在的喇叭道具。</li>
 *   <li>走马灯 / 世界行走道不需要另发 801：S2C 30 的 {@code type == 5}
 *       （{@code ESysAnnouncement_Type_Chat_Laba}）在 {@code ᝁ.cs:5263-5265} 会同时触发
 *       {@code EN_ADD_SYSTEM_ANNOUNCEMENT_FOR_LABA} 与 {@code EN_SYS_CHAT_ANNOUNCEMENT}
 *       （{@code ChatSystem.cs:604-613} 当成系统聊天行）。</li>
 * </ul>
 *
 * <p>号段陷阱：S2C 4701/4702 已被跨服战 KFZ 占用（{@code S2C_KFZ_ZHAN_KUANG}／
 * {@code S2C_KFZ_XIANGXI}），喇叭的回包是 5201/5202，不是 4701/4702。
 *
 * <p>大小喇叭的对应关系：出厂表只有两个 attrType=12 的道具
 * （{@code GoodsList.txt:219 GOODS250} 参数 {@code 1|0|0|36}、
 * {@code :220 GOODS251} 参数 {@code 2|eff_sc_sd_xiaxue|eff_buff_xuehua|37}），
 * 第一段 {@code "1"} 走 4701、{@code "2"} 走 4702。
 * <p>「扣几个 / 每日上限 / CD / 大喇叭与小喇叭的实质差异」属需抓真服包才能定论的项，
 * 见 docs/PROTOCOL_GAP_REPORT.md §7；本实现取「每次扣 1」。
 */
@Service
public class LaBaService {
    private static final Logger log = LoggerFactory.getLogger(LaBaService.class);

    /** 兜底：客户端没上送 oriName 时按 msgId 反推（4701 小、4702 大）。 */
    private static final String FALLBACK_SMALL = "GOODS250";
    private static final String FALLBACK_BIG = "GOODS251";

    private final PlayerStore store;
    private final PlayerDumpService dump;
    private final ProgressService progress;
    private final SessionHub hub;
    private final EconomyTables economy;

    public LaBaService(PlayerStore store, PlayerDumpService dump, ProgressService progress,
                       SessionHub hub, EconomyTables economy) {
        this.store = store;
        this.dump = dump;
        this.progress = progress;
        this.hub = hub;
        this.economy = economy;
    }

    /** C2S 4701 {@code NET_CCMsgRequestOpenSmallLaBa}。 */
    public void onOpenSmall(GameSession session, GamePacket pkt) {
        open(session, pkt, FALLBACK_SMALL);
    }

    /** C2S 4702 {@code NET_CCMsgRequestOpenBigLaBa}。 */
    public void onOpenBig(GameSession session, GamePacket pkt) {
        open(session, pkt, FALLBACK_BIG);
    }

    // ------------------------------------------------------------------ 内部

    private void open(GameSession session, GamePacket pkt, String fallbackOri) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        // CCMsgLaBaOpenMessage：1 words、2 oriName、3 dynID、4 playerName、5 playerGuid、6 serverID
        Pb.Fields f = Pb.read(pkt.body);
        String words = f.getString(1);
        String ori = f.getString(2);
        if (words == null) {
            words = "";
        }
        words = words.trim();
        if (ori == null || ori.trim().isEmpty() || !economy.isLaBa(ori.trim())) {
            ori = fallbackOri;
        } else {
            ori = ori.trim();
        }

        // 客户端已经关了面板且没有任何失败提示通道，所以 5201 无论如何都回，避免界面卡死。
        session.send(MsgIds.S2C_LABA_OPEN_RET, pkt, dump.laBaOpenRet());

        if (words.isEmpty()) {
            log.info("{} laba [{}] empty words, ignored", rec.account, ori);
            return;
        }
        if (!progress.hasGoods(rec, ori, 1)) {
            log.info("{} laba [{}] no such item in bag, ignored", rec.account, ori);
            return;
        }

        Map<String, Integer> changed = new LinkedHashMap<String, Integer>();
        if (progress.consumeGoods(rec, ori, 1)) {
            progress.markGoods(changed, ori);
        }
        store.save(rec);
        if (!changed.isEmpty()) {
            progress.pushGoods(session, pkt, rec, changed);
        }

        byte[] broadcast = dump.laBaBroadcast(words, ori, rec.playerId, rec.roleName, rec.playerId,
                dump.localServerId());
        for (GameSession s : hub.onlineSnapshot()) {
            s.send(MsgIds.S2C_LABA_BROADCAST, 0, broadcast);
        }

        // 走马灯/世界行走道：S2C 30 type=5（Chat_Laba）。regionType 0=ALL。
        AnnounceCfg.Item ann = new AnnounceCfg.Item();
        ann.type = 5;
        ann.regionType = 0;
        ann.content = words;
        for (GameSession s : hub.onlineSnapshot()) {
            s.send(MsgIds.S2C_SYS_ANNOUNCEMENT, 0, dump.sysAnnouncement(ann));
        }

        log.info("{} laba [{}] broadcast: {}", rec.account, ori, words);
    }
}
