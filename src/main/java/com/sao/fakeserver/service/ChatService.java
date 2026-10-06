package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * 聊天（世界/公会/私聊/DEBUG）与黑名单 —— 对应 C2S 501-504、S2C 801/802。
 *
 * <p>之前假服对这四个 C2S <b>零实现</b>（无常量、无 case），后果：
 * <ul>
 *   <li>{@code ChatSystem.cs:236} 开面板发 501 无回包 ⇒ {@code :898} 对空列表直接 return，
 *       历史区永远空白；</li>
 *   <li>世界/跨服/公会频道发言（{@code :343/:394/:438}）发出后本地只清输入框
 *       （{@code :344}）<b>不本地插入</b>，服务端不回 801 就等于发言石沉大海；</li>
 *   <li>拉黑（{@code :1092} 发 503）时客户端 {@code :1083-1085} <b>先本地弹「已加入黑名单」</b>，
 *       802 不回则 {@code BlackList.cs:151 mBlackList.Add} 永不执行 = 提示成功但没生效。</li>
 * </ul>
 *
 * <p>号段说明：S2C 801 {@code S2C_CHAT_TO_CLI} 假服原本就有（{@code DungeonService.java:1138}、
 * {@code MineService.java:994} 两处系统播报在用），只是 {@code chatToCli} 漏写了 tag8/9
 * （SrcServerID / TarServerID），被客户端 {@code ChatSystem.cs:645} 判成跨服后丢弃，
 * 已在 {@link PlayerDumpService#chatToCli} 修掉。S2C 802 与 C2S 802（{@code C2S_HANDLE_MAIL}）
 * 是<b>同号反向</b>占用，不冲突。
 *
 * <p>历史记录（501 的回包）是进程内环形缓冲、不落档：聊天记录本身是会话性数据，
 * 重启即清空属预期。黑名单落 {@link PlayerRecord#blackList}。
 */
@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 单条发言最大长度（客户端不校验，服务端兜底防刷屏）。 */
    private static final int MAX_TEXT = 400;
    /** 历史环形缓冲上限（客户端自身裁剪上限是 120，见 {@code ChatSystem.cs:1458}）。 */
    private static final int HISTORY_KEEP = 120;

    /** EChatType（{@code CCMsgChatToSvr.type}）。 */
    public static final int CHAT_WORLD = 1;
    public static final int CHAT_UNION = 2;
    public static final int CHAT_SINGLE = 3;
    public static final int CHAT_DEBUG = 4;

    /** 进程内世界频道历史，最新在尾。 */
    private final Deque<byte[]> worldHistory = new ArrayDeque<byte[]>();

    private final PlayerStore store;
    private final PlayerDumpService dump;
    private final SessionHub hub;

    public ChatService(PlayerStore store, PlayerDumpService dump, SessionHub hub) {
        this.store = store;
        this.dump = dump;
        this.hub = hub;
    }

    // ---------------------------------------------------------------- C2S 501

    /**
     * C2S 501 {@code NET_CCMsgRequestChatInfo}（无 body）→ S2C 801 历史回包。
     * <p>只回世界频道历史：原厂的私聊/公会历史范围属「需抓真服包才能定论」
     * （见 docs/PROTOCOL_GAP_REPORT.md §7），不猜。
     */
    public void onChatInfo(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        List<byte[]> history;
        synchronized (worldHistory) {
            history = new ArrayList<byte[]>(worldHistory.size());
            for (byte[] r : worldHistory) {
                if (!isBlocked(rec, r)) {
                    history.add(r);
                }
            }
        }
        session.send(MsgIds.S2C_CHAT_TO_CLI, pkt, dump.chatHistory(history));
        log.info("{} chat history -> {} records", rec.account, Integer.valueOf(history.size()));
    }

    // ---------------------------------------------------------------- C2S 502

    /**
     * C2S 502 {@code NET_CCMsgChatToSvr}。
     * <p>1 type、2 TargetName、3 Text、4 TarServerID、5 TargetGuid、6 SoundData、7 SoundLen。
     * <p>回包一律是 S2C 801；世界频道广播给所有在线会话，公会频道广播给同会成员，
     * 私聊只发给目标（发送方 {@code ChatSystem.cs:534} 已本地插过一条，不再回声）。
     */
    public void onChatToSvr(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        int type = f.getInt(1, 0);
        String targetName = f.getString(2);
        String text = f.getString(3);
        int tarServerId = f.getInt(4, 0);
        int targetGuid = f.getInt(5, 0);
        if (text == null) {
            text = "";
        }
        text = text.trim();
        if (text.isEmpty()) {
            return;
        }
        if (text.length() > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT);
        }

        String time = PlayerDumpService.timeAt(System.currentTimeMillis());
        // tag8 SrcServerID 必须填本服号，否则客户端 ChatSystem.cs:645 判跨服后丢弃。
        int srcServerId = dump.localServerId();
        byte[] record = dump.chatRecordToCli(type, rec.roleName, rec.mainHeroIndex, rec.level, text,
                time, unionName(rec), rec.playerId, targetGuid, targetName == null ? "" : targetName,
                srcServerId, tarServerId);

        switch (type) {
            case CHAT_WORLD:
                pushWorld(record);
                broadcastAll(record);
                log.info("{} world chat: {}", rec.account, text);
                break;
            case CHAT_UNION:
                if (!hasUnion(rec)) {
                    log.info("{} union chat ignored: no union", rec.account);
                    return;
                }
                broadcastUnion(rec, record);
                log.info("{} union chat: {}", rec.account, text);
                break;
            case CHAT_SINGLE:
                if (sendPrivate(session, rec, record, targetGuid, targetName)) {
                    log.info("{} private chat to guid={} name={}: {}", rec.account,
                            Integer.valueOf(targetGuid), targetName, text);
                } else {
                    log.info("{} private chat target not found (guid={} name={})", rec.account,
                            Integer.valueOf(targetGuid), targetName);
                }
                break;
            case CHAT_DEBUG:
                // 客户端自己 default: return 丢弃 type4（ChatSystem.cs:682），不用回包
                break;
            default:
                log.info("{} chat type={} ignored", rec.account, Integer.valueOf(type));
                break;
        }
    }

    // ------------------------------------------------------------ C2S 503/504

    /**
     * C2S 503 {@code NET_CCMsgAddIntoBlackList}：包体直接是
     * {@code CPlayerGuidAndNameAndResIDAndLevel}（1 Guid、2 Name、3 ResID、4 Level、5 serverID）。
     */
    public void onAddBlackList(GameSession session, GamePacket pkt) {
        blackListOp(session, pkt, true);
    }

    /** C2S 504 移出黑名单，包体与 503 同构。 */
    public void onDelBlackList(GameSession session, GamePacket pkt) {
        blackListOp(session, pkt, false);
    }

    private void blackListOp(GameSession session, GamePacket pkt, boolean add) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        PlayerRecord.Black b = new PlayerRecord.Black();
        b.guid = f.getInt(1, 0);
        b.name = nullToEmpty(f.getString(2));
        b.resId = f.getInt(3, 0);
        b.level = f.getInt(4, 1);
        b.serverId = f.getInt(5, 0);

        if (add) {
            if (findBlack(rec, b.guid, b.serverId) == null) {
                rec.blackList.add(b);
            }
        } else {
            for (Iterator<PlayerRecord.Black> it = rec.blackList.iterator(); it.hasNext(); ) {
                PlayerRecord.Black old = it.next();
                if (old != null && old.guid == b.guid && old.serverId == b.serverId) {
                    it.remove();
                }
            }
        }
        store.save(rec);
        // 必须原样回显 guid + serverId：客户端 BlackList.cs:199-208 用这两者做判等键。
        session.send(MsgIds.S2C_UPDATE_BLACK_LIST, pkt,
                dump.updateBlackList(add, b.guid, b.name, b.resId, b.level, b.serverId));
        log.info("{} blacklist {} guid={} serverId={} name={}", rec.account,
                add ? "add" : "remove", Integer.valueOf(b.guid), Integer.valueOf(b.serverId), b.name);
    }

    // ------------------------------------------------------------------ 内部

    private void pushWorld(byte[] record) {
        synchronized (worldHistory) {
            worldHistory.addLast(record);
            while (worldHistory.size() > HISTORY_KEEP) {
                worldHistory.pollFirst();
            }
        }
    }

    private void broadcastAll(byte[] record) {
        for (GameSession s : hub.onlineSnapshot()) {
            PlayerRecord r = s.player();
            if (r == null || isBlocked(r, record)) {
                continue;
            }
            s.send(MsgIds.S2C_CHAT_TO_CLI, 0, wrap(record));
        }
    }

    private void broadcastUnion(PlayerRecord sender, byte[] record) {
        String unionId = sender.guild == null ? null : sender.guild.id;
        if (unionId == null || unionId.isEmpty()) {
            return;
        }
        for (GameSession s : hub.onlineSnapshot()) {
            PlayerRecord r = s.player();
            if (r == null || r.guild == null || !unionId.equals(r.guild.id)) {
                continue;
            }
            s.send(MsgIds.S2C_CHAT_TO_CLI, 0, wrap(record));
        }
    }

    /** 私聊：guid 优先，guid 为 0 时按角色名找。返回是否找到目标。 */
    private boolean sendPrivate(GameSession session, PlayerRecord self, byte[] record,
                               int targetGuid, String targetName) {
        PlayerRecord target = null;
        if (targetGuid > 0) {
            target = store.findByPlayerId(targetGuid);
        }
        if (target == null && targetName != null && !targetName.trim().isEmpty()) {
            target = store.findByRoleName(targetName.trim());
        }
        if (target == null || target == self) {
            return false;
        }
        GameSession ts = hub.get(target.account);
        if (ts == null) {
            // 离线私聊假服不存信箱（原厂是否存属需抓包项），只记日志
            return false;
        }
        ts.send(MsgIds.S2C_CHAT_TO_CLI, 0, wrap(record));
        return true;
    }

    private byte[] wrap(byte[] record) {
        List<byte[]> one = new ArrayList<byte[]>(1);
        one.add(record);
        return dump.chatHistory(one);
    }

    private boolean hasUnion(PlayerRecord rec) {
        return rec.guild != null && rec.guild.id != null && !rec.guild.id.isEmpty();
    }

    private String unionName(PlayerRecord rec) {
        return rec.guild == null || rec.guild.name == null ? "" : rec.guild.name;
    }

    private PlayerRecord.Black findBlack(PlayerRecord rec, int guid, int serverId) {
        for (PlayerRecord.Black b : rec.blackList) {
            if (b != null && b.guid == guid && b.serverId == serverId) {
                return b;
            }
        }
        return null;
    }

    /**
     * 世界历史里存的记录是原始 {@code CChatRecordToCli} 字节，这里直接按 tag 号取发送者，
     * 避免为了过滤再解一次结构（客户端 {@code ChatSystem.cs:916} 自己也会按黑名单过滤）。
     */
    private boolean isBlocked(PlayerRecord rec, byte[] record) {
        if (rec.blackList == null || rec.blackList.isEmpty()) {
            return false;
        }
        Pb.Fields f = Pb.read(record);
        int guid = f.getInt(11, 0);
        int serverId = f.getInt(8, 0);
        return findBlack(rec, guid, serverId) != null;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
