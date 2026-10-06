package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.session.SessionHub;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.store.WorldStore;
import com.sao.fakeserver.table.CultivateTables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 好友整链 —— 对应 C2S 1403-1418，S2C 1303-1320。
 *
 * <p>之前假服对好友协议<b>零实现</b>（无常量、无 case、无存档字段），后果：
 * <ul>
 *   <li>{@code FriendSystem.cs:1036 Open()} 的 4 个 tab 全空 ——
 *       {@code :1100/:1117/:1141/:1229} 发 1403/1409/1412/1415 都没有回包，
 *       唯一能填充列表的路径（{@code :69-95}）永不执行；</li>
 *   <li>大厅社交入口红点永不出现：{@code FriendSystemServerData.isCanGetRewards}
 *       与 {@code applyCount} 的<b>唯一写点</b>是 1320 的 handler
 *       （{@code FriendSystem.cs:998-999}），入口 {@code :1591 isHasRedPoint()} 读它们；</li>
 *   <li>加好友 / 同意申请 / 领邀请奖 / 查看详情全部静默无反应。</li>
 * </ul>
 *
 * <p>两条会让客户端崩的硬约束（都已处理）：
 * <ol>
 *   <li>{@code CFriendBase.8 OfflineTime} 必须是非空且合法的 {@code yyyy-MM-dd HH:mm:ss}
 *       —— {@code FriendItem.cs:108 DateTime.ParseExact} 不判空。离线好友用
 *       {@link PlayerRecord#lastLogoutAtMs}（无记录则回退建号时间）。</li>
 *   <li>{@code playerId} 必须 &gt; 1000000（{@code PlayerStore} 的 idSeq 从 2000000 起）。</li>
 * </ol>
 *
 * <p>号段说明：S2C 1303 与 C2S 1303（{@code C2S_SHOP_REFRESH}，商店手动刷新）<b>同号反向</b>，
 * 出站必须用 {@code S2C_FRIEND_LIST_RET}。S2C 1316 的包体与 S2C 2902
 * （{@code CCMsgRemotePlayerBreifInfo}）完全相同，直接复用
 * {@link PlayerDumpService#remotePlayerBrief}。
 *
 * <p>数值来源：{@code GameText/GameData/FriendsParams.txt}（单次赠送 2 点体力、
 * 最大好友 30、最大申请 50、最大领取 20、邀请开放 20、每页推荐 5）与
 * {@code FriendsInvite.txt}（档位 3/10/20/30/40 的金币与钻石）。
 * 其余（每日上限、冷却、{@code flushtime} 语义、{@code errorcode} 全集等）属需抓真服包
 * 才能定论的项，见 docs/PROTOCOL_GAP_REPORT.md §7。
 */
@Service
public class FriendService {
    private static final Logger log = LoggerFactory.getLogger(FriendService.class);

    /** FriendsParams.txt：单次赠送/领取体力值。 */
    private static final int POWER_PER_GIFT = 2;
    /** FriendsParams.txt：好友上限。 */
    private static final int MAX_FRIENDS = 30;
    /** FriendsParams.txt：申请列表上限。 */
    private static final int MAX_APPLIES = 50;
    /** FriendsParams.txt：每日最多领取次数（客户端 {@code FriendSystem.cs:251} 也拿 20 判错）。 */
    private static final int MAX_GET_POWER_PER_DAY = 20;
    /** FriendsParams.txt：每页推荐人数。 */
    private static final int PAGE_SIZE = 5;

    /** FriendsInvite.txt 的档位人数。 */
    private static final int[] INVITE_TIERS = {3, 10, 20, 30, 40};
    /** FriendsInvite.txt 档位对应金币。 */
    private static final int[] INVITE_GOLD = {50000, 100000, 200000, 300000, 500000};
    /** FriendsInvite.txt 档位对应钻石。 */
    private static final int[] INVITE_DIAMOND = {100, 200, 500, 500, 1000};

    /** 加好友页刷新倒计时秒数（{@code CFriendRedPoint.flushtime}）。 */
    private static final int FLUSH_SECONDS = 8;

    private final PlayerStore store;
    private final PlayerDumpService dump;
    private final ProgressService progress;
    private final SessionHub hub;
    private final CultivateTables cultivateTables;
    private final ArenaService arena;

    public FriendService(PlayerStore store, PlayerDumpService dump, ProgressService progress,
                         SessionHub hub, CultivateTables cultivateTables, ArenaService arena) {
        this.store = store;
        this.dump = dump;
        this.progress = progress;
        this.hub = hub;
        this.cultivateTables = cultivateTables;
        this.arena = arena;
    }

    // ------------------------------------------------------ C2S 1403 好友列表

    /** C2S 1403 {@code NET_CCMsgRequestFriendsList}（无 body）→ S2C 1303。 */
    public void onFriendList(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        daily(rec);
        List<byte[]> infos = new ArrayList<byte[]>();
        for (Integer guid : rec.friendIds) {
            if (guid == null) {
                continue;
            }
            PlayerRecord f = store.findByPlayerId(guid.intValue());
            if (f == null) {
                continue;
            }
            infos.add(dump.friendInfo(baseOf(f), rec.friendGaveToday.contains(guid),
                    receiveState(rec, guid.intValue())));
        }
        session.send(MsgIds.S2C_FRIEND_LIST_RET, pkt, dump.friendListRet(infos));
        log.info("{} friend list -> {} friends", rec.account, Integer.valueOf(infos.size()));
    }

    // ------------------------------------------------------ C2S 1404 删好友

    /** C2S 1404 {@code CFriendGuid{1 Guid}} → S2C 1304 {@code CFriendGuid}。 */
    public void onDeleteFriend(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        int guid = Pb.read(pkt.body).getInt(1, 0);
        rec.friendIds.remove(Integer.valueOf(guid));
        rec.friendGaveToday.remove(Integer.valueOf(guid));
        rec.friendReceiveState.remove(String.valueOf(guid));
        PlayerRecord other = store.findByPlayerId(guid);
        if (other != null) {
            other.friendIds.remove(Integer.valueOf(rec.playerId));
            other.friendGaveToday.remove(Integer.valueOf(rec.playerId));
            other.friendReceiveState.remove(String.valueOf(rec.playerId));
            store.save(other);
        }
        store.save(rec);
        session.send(MsgIds.S2C_FRIEND_DELETE_RET, pkt, dump.friendGuid(guid));
        log.info("{} deleted friend guid={}", rec.account, Integer.valueOf(guid));
    }

    // -------------------------------------------------- C2S 1405 赠送体力

    /** C2S 1405 {@code CFriendGuid{1 Guid}} → S2C 1305 + 纯推送 1306 给对方。 */
    public void onGiveFriendPower(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        daily(rec);
        int guid = Pb.read(pkt.body).getInt(1, 0);
        PlayerRecord other = store.findByPlayerId(guid);
        if (other == null || !rec.friendIds.contains(Integer.valueOf(guid))) {
            session.send(MsgIds.S2C_FRIEND_GIVE_RET, pkt, dump.friendGuid(guid));
            log.info("{} give power to guid={} refused: not a friend", rec.account,
                    Integer.valueOf(guid));
            return;
        }
        if (!rec.friendGaveToday.contains(Integer.valueOf(guid))) {
            rec.friendGaveToday.add(Integer.valueOf(guid));
            rec.friendGaveCount++;
            // 对方今天可以从我这里领 2 点体力（IsReceive = 1 可领）
            other.stamina += POWER_PER_GIFT;
            other.friendReceiveState.put(String.valueOf(rec.playerId), Integer.valueOf(1));
            store.save(other);
            store.save(rec);
            GameSession os = hub.get(other.account);
            if (os != null) {
                progress.pushStamina(os, pkt, other);
                os.send(MsgIds.S2C_FRIEND_GIVE_PUSH, 0, dump.friendGuid(rec.playerId));
            }
        }
        session.send(MsgIds.S2C_FRIEND_GIVE_RET, pkt, dump.friendGuid(guid));
        log.info("{} gave {} power to guid={}", rec.account, Integer.valueOf(POWER_PER_GIFT),
                Integer.valueOf(guid));
    }

    // -------------------------------------------------- C2S 1406 领取体力

    /** C2S 1406 {@code CFriendGuid{1 Guid}} → S2C 1307 {@code CFriendAccPowerRet}。 */
    public void onAcceptFriendPower(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        daily(rec);
        int guid = Pb.read(pkt.body).getInt(1, 0);
        // count 字段是「今日已领取次数」：客户端 FriendSystem.cs:251 拿它和 20 比。
        int count = rec.friendReceivedToday;
        if (receiveState(rec, guid) != 1) {
            session.send(MsgIds.S2C_FRIEND_ACCEPT_POWER_RET, pkt, dump.friendAccPowerRet(guid, count));
            log.info("{} accept power from guid={} refused: nothing to claim", rec.account,
                    Integer.valueOf(guid));
            return;
        }
        if (count >= MAX_GET_POWER_PER_DAY) {
            session.send(MsgIds.S2C_FRIEND_ACCEPT_POWER_RET, pkt, dump.friendAccPowerRet(guid, count));
            log.info("{} accept power from guid={} refused: daily limit {}", rec.account,
                    Integer.valueOf(guid), Integer.valueOf(MAX_GET_POWER_PER_DAY));
            return;
        }
        rec.friendReceivedToday++;
        rec.friendReceiveState.put(String.valueOf(guid), Integer.valueOf(2));
        rec.stamina += POWER_PER_GIFT;
        store.save(rec);
        progress.pushStamina(session, pkt, rec);
        session.send(MsgIds.S2C_FRIEND_ACCEPT_POWER_RET, pkt,
                dump.friendAccPowerRet(guid, rec.friendReceivedToday));
        log.info("{} accepted {} power from guid={} (today={})", rec.account,
                Integer.valueOf(POWER_PER_GIFT), Integer.valueOf(guid),
                Integer.valueOf(rec.friendReceivedToday));
    }

    // -------------------------------------------- C2S 1407/1408 加好友

    /** C2S 1407 {@code CFriendGuid{1 Guid}} → S2C 1308。 */
    public void onAddFriendByGuid(GameSession session, GamePacket pkt) {
        addFriend(session, pkt, Pb.read(pkt.body).getInt(1, 0));
    }

    /** C2S 1408 {@code CFriendName{1 name}} → S2C 1308。 */
    public void onAddFriendByName(GameSession session, GamePacket pkt) {
        String name = Pb.read(pkt.body).getString(1);
        PlayerRecord target = name == null ? null : store.findByRoleName(name.trim());
        addFriend(session, pkt, target == null ? 0 : target.playerId);
    }

    private void addFriend(GameSession session, GamePacket pkt, int guid) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        // CAddFriendRet.errorcode：1 已经是好友 / 2 玩家不存在 / 3 对方申请数已满 / 0 成功
        if (guid <= 0 || guid == rec.playerId) {
            session.send(MsgIds.S2C_FRIEND_ADD_RET, pkt, dump.addFriendRet(false, 0, guid, 2));
            log.info("{} add friend guid={} -> errorcode=2 (not found)", rec.account,
                    Integer.valueOf(guid));
            return;
        }
        PlayerRecord target = store.findByPlayerId(guid);
        if (target == null) {
            session.send(MsgIds.S2C_FRIEND_ADD_RET, pkt, dump.addFriendRet(false, 0, guid, 2));
            log.info("{} add friend guid={} -> errorcode=2 (not found)", rec.account,
                    Integer.valueOf(guid));
            return;
        }
        if (rec.friendIds.contains(Integer.valueOf(guid))) {
            session.send(MsgIds.S2C_FRIEND_ADD_RET, pkt, dump.addFriendRet(false, 0, guid, 1));
            log.info("{} add friend guid={} -> errorcode=1 (already friend)", rec.account,
                    Integer.valueOf(guid));
            return;
        }
        if (target.friendApplies.size() >= MAX_APPLIES) {
            session.send(MsgIds.S2C_FRIEND_ADD_RET, pkt, dump.addFriendRet(false, 0, guid, 3));
            log.info("{} add friend guid={} -> errorcode=3 (target applies full)", rec.account,
                    Integer.valueOf(guid));
            return;
        }
        if (!target.friendApplies.contains(Integer.valueOf(rec.playerId))) {
            target.friendApplies.add(Integer.valueOf(rec.playerId));
            store.save(target);
            GameSession ts = hub.get(target.account);
            if (ts != null) {
                // 1309 客户端完全不解析包体，空体即可；它唯一动作是回发 1418 刷新红点。
                ts.send(MsgIds.S2C_FRIEND_ADD_PUSH, 0, new byte[0]);
            }
        }
        session.send(MsgIds.S2C_FRIEND_ADD_RET, pkt, dump.addFriendRet(true, 0, guid, 0));
        log.info("{} add friend guid={} -> applied", rec.account, Integer.valueOf(guid));
    }

    // -------------------------------------------- C2S 1409/1410 推荐列表

    /** C2S 1409 {@code NET_CCMsgRequestPushApplyList} → S2C 1310（首页）。 */
    public void onPushApplyList(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.friendPushPage = 0;
        store.save(rec);
        pushRecommend(session, pkt, rec);
    }

    /** C2S 1410 {@code NET_CCMsgRequestPushApplyListNextPage} → S2C 1310（下一页）。 */
    public void onPushApplyListNext(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        rec.friendPushPage++;
        store.save(rec);
        pushRecommend(session, pkt, rec);
    }

    private void pushRecommend(GameSession session, GamePacket pkt, PlayerRecord rec) {
        List<PlayerRecord> pool = recommendPool(rec);
        int pages = (pool.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        if (pages <= 0) {
            pages = 1;
        }
        if (rec.friendPushPage >= pages) {
            rec.friendPushPage = 0;
        }
        int from = rec.friendPushPage * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, pool.size());
        List<byte[]> bases = new ArrayList<byte[]>();
        for (int i = from; i < to; i++) {
            bases.add(baseOf(pool.get(i)));
        }
        session.send(MsgIds.S2C_FRIEND_PUSH_APPLY_RET, pkt, dump.friendApplyListRet(bases));
        log.info("{} recommend page={}/{} -> {} candidates", rec.account,
                Integer.valueOf(rec.friendPushPage + 1), Integer.valueOf(pages),
                Integer.valueOf(bases.size()));
    }

    /** 推荐池：排除自己、已有好友、已在我申请列表里的人；按等级降序。 */
    private List<PlayerRecord> recommendPool(PlayerRecord rec) {
        List<PlayerRecord> pool = new ArrayList<PlayerRecord>();
        for (PlayerRecord r : store.all()) {
            if (r == null || r.playerId == rec.playerId) {
                continue;
            }
            if (rec.friendIds.contains(Integer.valueOf(r.playerId))) {
                continue;
            }
            if (rec.friendApplies.contains(Integer.valueOf(r.playerId))) {
                continue;
            }
            pool.add(r);
        }
        Collections.sort(pool, new Comparator<PlayerRecord>() {
            @Override
            public int compare(PlayerRecord a, PlayerRecord b) {
                if (a.level != b.level) {
                    return b.level - a.level;
                }
                return a.playerId - b.playerId;
            }
        });
        return pool;
    }

    // ------------------------------------------------------ C2S 1411 邀请码

    /** C2S 1411（{@code CFriendGuid} 里装的是邀请码）→ S2C 1311（复用 {@code CAddFriendRet} 体）。 */
    public void onInviteMe(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        int code = Pb.read(pkt.body).getInt(1, 0);
        PlayerRecord inviter = store.findByPlayerId(code);
        if (inviter == null || inviter.playerId == rec.playerId) {
            session.send(MsgIds.S2C_FRIEND_INVITE_ME_RET, pkt, dump.addFriendRet(false, 0, 0, 0));
            log.info("{} invite code {} invalid", rec.account, Integer.valueOf(code));
            return;
        }
        rec.inviterGuid = inviter.playerId;
        store.save(rec);
        session.send(MsgIds.S2C_FRIEND_INVITE_ME_RET, pkt,
                dump.addFriendRet(true, 0, inviter.playerId, 0));
        log.info("{} invited by guid={}", rec.account, Integer.valueOf(inviter.playerId));
    }

    // ---------------------------------------------------- C2S 1412 邀请人数

    /** C2S 1412 {@code NET_CCMsgRequestInviteCount} → S2C 1312（客户端只读 realcount）。 */
    public void onInviteCount(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        int real = invitedCount(rec.playerId);
        session.send(MsgIds.S2C_FRIEND_INVITE_COUNT_RET, pkt, dump.inviteCount(real, real));
        log.info("{} invite count = {}", rec.account, Integer.valueOf(real));
    }

    private int invitedCount(int playerId) {
        int n = 0;
        for (PlayerRecord r : store.all()) {
            if (r != null && r.inviterGuid == playerId) {
                n++;
            }
        }
        return n;
    }

    // -------------------------------------------------- C2S 1413 领档位奖

    /** C2S 1413（{@code CFriendGuid.Guid} = 档位人数 3/10/20/30/40）→ S2C 1313。 */
    public void onInviteReward(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        int tier = Pb.read(pkt.body).getInt(1, 0);
        int index = -1;
        for (int i = 0; i < INVITE_TIERS.length; i++) {
            if (INVITE_TIERS[i] == tier) {
                index = i;
                break;
            }
        }
        int real = invitedCount(rec.playerId);
        if (index < 0 || real < tier || rec.inviteClaimed.contains(Integer.valueOf(tier))) {
            session.send(MsgIds.S2C_FRIEND_INVITE_REWARD_RET, pkt, dump.inviteReward(1, false, index));
            log.info("{} invite reward tier={} refused (real={} claimed={})", rec.account,
                    Integer.valueOf(tier), Integer.valueOf(real),
                    Boolean.valueOf(rec.inviteClaimed.contains(Integer.valueOf(tier))));
            return;
        }
        rec.gold += INVITE_GOLD[index];
        rec.diamond += INVITE_DIAMOND[index];
        rec.inviteClaimed.add(Integer.valueOf(tier));
        store.save(rec);
        progress.pushGold(session, pkt, rec);
        progress.pushDiamond(session, pkt, rec);
        session.send(MsgIds.S2C_FRIEND_INVITE_REWARD_RET, pkt, dump.inviteReward(1, true, index));
        log.info("{} invite reward tier={} granted gold={} diamond={}", rec.account,
                Integer.valueOf(tier), Integer.valueOf(INVITE_GOLD[index]),
                Integer.valueOf(INVITE_DIAMOND[index]));
    }

    // ------------------------------------------------ C2S 1414 我的邀请

    /** C2S 1414 {@code NET_CCMsgRequestInviteMyReward} → S2C 1318 + 纯推送 1317。 */
    public void onInviteMyReward(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        session.send(MsgIds.S2C_FRIEND_INVITE_MY_INFO, pkt,
                dump.inviteMyInfo(rec.inviterGuid, rec.inviteClaimed.size()));
        List<Integer> ids = new ArrayList<Integer>(rec.inviteClaimed);
        session.send(MsgIds.S2C_FRIEND_INVITE_REWARDS, pkt, dump.inviteRewards(ids));
        log.info("{} invite my info inviter={} claimed={}", rec.account,
                Integer.valueOf(rec.inviterGuid), Integer.valueOf(rec.inviteClaimed.size()));
    }

    // ---------------------------------------------------- C2S 1415 申请列表

    /** C2S 1415 {@code NET_CCMsgRequestFriendApplyList} → S2C 1314。 */
    public void onApplyList(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        daily(rec);
        List<byte[]> bases = new ArrayList<byte[]>();
        for (Integer guid : rec.friendApplies) {
            if (guid == null) {
                continue;
            }
            PlayerRecord f = store.findByPlayerId(guid.intValue());
            if (f != null) {
                bases.add(baseOf(f));
            }
        }
        session.send(MsgIds.S2C_FRIEND_APPLY_LIST_RET, pkt, dump.friendApplyListRet(bases));
        log.info("{} apply list -> {}", rec.account, Integer.valueOf(bases.size()));
    }

    // ------------------------------------------------ C2S 1416 同意/拒绝

    /** C2S 1416 {@code CApplyFriendDeal{1 guid,2 agree}} → S2C 1315 + 纯推送 1319 给申请人。 */
    public void onApplyDeal(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        Pb.Fields f = Pb.read(pkt.body);
        int guid = f.getInt(1, 0);
        boolean agree = f.getBool(2);
        if (!rec.friendApplies.contains(Integer.valueOf(guid))) {
            session.send(MsgIds.S2C_FRIEND_APPLY_DEAL_RET, pkt, dump.agreeFriendRet(false, 0, guid, 0));
            log.info("{} apply deal guid={} refused: no such apply", rec.account,
                    Integer.valueOf(guid));
            return;
        }
        PlayerRecord other = store.findByPlayerId(guid);
        rec.friendApplies.remove(Integer.valueOf(guid));
        if (agree) {
            if (rec.friendIds.size() >= MAX_FRIENDS) {
                store.save(rec);
                // errorcode 1 = 好友数量已达上限（FriendSystem.cs:941）
                session.send(MsgIds.S2C_FRIEND_APPLY_DEAL_RET, pkt,
                        dump.agreeFriendRet(false, 0, guid, 1));
                log.info("{} apply deal guid={} refused: friend list full", rec.account,
                        Integer.valueOf(guid));
                return;
            }
            if (!rec.friendIds.contains(Integer.valueOf(guid))) {
                rec.friendIds.add(Integer.valueOf(guid));
            }
            if (other != null && !other.friendIds.contains(Integer.valueOf(rec.playerId))) {
                other.friendIds.add(Integer.valueOf(rec.playerId));
                store.save(other);
                GameSession os = hub.get(other.account);
                if (os != null) {
                    // 1319 客户端解析后丢弃包体，只用它的数量更新计数。
                    List<byte[]> one = new ArrayList<byte[]>();
                    one.add(baseOf(rec));
                    os.send(MsgIds.S2C_FRIEND_AGREE_PUSH, 0, dump.friendApplyListRet(one));
                }
            }
        }
        store.save(rec);
        session.send(MsgIds.S2C_FRIEND_APPLY_DEAL_RET, pkt, dump.agreeFriendRet(true, 0, guid, 0));
        log.info("{} apply deal guid={} agree={}", rec.account, Integer.valueOf(guid),
                Boolean.valueOf(agree));
    }

    // -------------------------------------------------- C2S 1417 好友详情

    /** C2S 1417 {@code CFriendGuid{1 Guid}} → S2C 1316（体同 2902）。 */
    public void onFriendBrief(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        int guid = Pb.read(pkt.body).getInt(1, 0);
        PlayerRecord f = store.findByPlayerId(guid);
        if (f == null) {
            log.info("{} friend brief guid={} not found", rec.account, Integer.valueOf(guid));
            return;
        }
        session.send(MsgIds.S2C_FRIEND_BRIEF_RET, pkt,
                dump.friendBrief(slotOf(f), arena.unionNameFor(f.playerId), wjsOf(f)));
        log.info("{} friend brief guid={} name={}", rec.account, Integer.valueOf(guid), f.roleName);
    }

    // ---------------------------------------------------- C2S 1418 红点

    /** C2S 1418 {@code NET_CCMsgRequestRedPoint}（无 body）→ S2C 1320。 */
    public void onRedPoint(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        daily(rec);
        boolean rewards = hasClaimable(rec);
        int applies = rec.friendApplies.size();
        session.send(MsgIds.S2C_FRIEND_RED_POINT, pkt,
                dump.friendRedPoint(rewards, applies, FLUSH_SECONDS));
        log.info("{} friend red point rewards={} applies={}", rec.account, Boolean.valueOf(rewards),
                Integer.valueOf(applies));
    }

    /** 「有可领的邀请档位奖」= rewards 为真的条件（原厂条件属抓包项，取此自洽定义）。 */
    private boolean hasClaimable(PlayerRecord rec) {
        int real = invitedCount(rec.playerId);
        for (int tier : INVITE_TIERS) {
            if (real >= tier && !rec.inviteClaimed.contains(Integer.valueOf(tier))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 内部

    private void daily(PlayerRecord rec) {
        progress.ensureDaily(rec);
    }

    /** IsReceive：0 无、1 可领、2 已领。 */
    private int receiveState(PlayerRecord rec, int guid) {
        Integer v = rec.friendReceiveState.get(String.valueOf(guid));
        return v == null ? 0 : v.intValue();
    }

    private byte[] baseOf(PlayerRecord r) {
        boolean online = isOnline(r);
        String offline = null;
        if (!online) {
            long ms = r.lastLogoutAtMs > 0 ? r.lastLogoutAtMs : createdMs(r);
            offline = PlayerDumpService.timeAt(ms);
        }
        return dump.friendBase(r.playerId, r.mainHeroIndex, r.level, r.roleName,
                arena.unionNameFor(r.playerId), totalFightPower(r), online, offline);
    }

    private long createdMs(PlayerRecord r) {
        try {
            return java.time.LocalDateTime.parse(r.createdAt, PlayerDumpService.TIME)
                    .atZone(com.sao.fakeserver.util.GameTime.ZONE).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return System.currentTimeMillis();
        }
    }

    private boolean isOnline(PlayerRecord r) {
        GameSession s = hub.get(r.account);
        return s != null && s.channel() != null && s.channel().isActive();
    }

    private int totalFightPower(PlayerRecord r) {
        int sum = 0;
        for (PlayerRecord.Hero wj : r.heroes) {
            if (wj == null) {
                continue;
            }
            int fp = cultivateTables.computeFightPower(r, wj);
            sum += fp;
        }
        return Math.max(1000, sum);
    }

    /** 好友详情用的假 JjcSlot（只借它的字段布局，不写进世界存档）。 */
    private WorldStore.JjcSlot slotOf(PlayerRecord r) {
        WorldStore.JjcSlot slot = new WorldStore.JjcSlot();
        slot.kind = "player";
        slot.targetGuid = r.playerId;
        slot.account = r.account;
        slot.heroIndex = r.mainHeroIndex;
        slot.level = r.level;
        slot.name = r.roleName;
        slot.fightPower = totalFightPower(r);
        return slot;
    }

    /** {@code CCMsgRemoteWuJiangBreifInfo}：每项 {index, jieduan, level, stars}。 */
    private List<int[]> wjsOf(PlayerRecord r) {
        List<int[]> out = new ArrayList<int[]>();
        for (PlayerRecord.Hero wj : r.heroes) {
            if (wj == null) {
                continue;
            }
            out.add(new int[]{wj.heroIndex, wj.stage, wj.level, wj.stars});
        }
        return out;
    }
}
