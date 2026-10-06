package com.sao.fakeserver.service;

import com.sao.fakeserver.netty.GamePacket;
import com.sao.fakeserver.proto.MsgIds;
import com.sao.fakeserver.proto.Pb;
import com.sao.fakeserver.session.GameSession;
import com.sao.fakeserver.store.PlayerRecord;
import com.sao.fakeserver.store.PlayerStore;
import com.sao.fakeserver.table.CultivateTables;
import com.sao.fakeserver.table.TaskTables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TaskService {
    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    public static final int DAILY_NORMAL_FB = 1;
    public static final int DAILY_HARD_FB = 2;
    /** DailyTaskConfig type3：命运之镰 / 致命 / 圣诞。 */
    public static final int DAILY_MATERIAL_FB = 3;
    public static final int DAILY_RESOURCE_FB = 4;
    public static final int DAILY_JJC = 5;
    public static final int DAILY_SKILL_UP = 6;
    public static final int DAILY_DRAW = 7;
    /** DailyTaskConfig type8：挑战赛战胜一场。 */
    public static final int DAILY_BOB = 8;
    public static final int DAILY_BUY_GOLD = 9;
    public static final int DAILY_MINE = 10;

    public static final int ONCE_NORMAL_STAGE = 1;
    public static final int ONCE_HARD_STAGE = 2;
    public static final int ONCE_COLLECT_HERO = 3;
    public static final int ONCE_PLAYER_LEVEL = 4;
    public static final int ONCE_JINJIE = 5;
    public static final int ONCE_TIME_STONE = 6;
    public static final int ONCE_EQUIP_STAR = 7;

    private final PlayerStore store;
    private final PlayerDumpService dump;
    private final ProgressService progress;
    private final TaskTables tables;
    private final CultivateTables cultivate;
    private final MailService mail;

    public TaskService(PlayerStore store, PlayerDumpService dump, ProgressService progress, TaskTables tables,
                       CultivateTables cultivate, @Lazy MailService mail) {
        this.store = store;
        this.dump = dump;
        this.progress = progress;
        this.tables = tables;
        this.cultivate = cultivate;
        this.mail = mail;
    }

    public void ensure(PlayerRecord rec) {
        rec.ensureCollections();
        for (TaskTables.DailyCfg cfg : tables.dailyAll()) {
            rec.tasks.daily(cfg.id);
        }
        if (unlockOnce(rec)) {
            mail.notifyNewMail(rec.account);
        }
    }

    public void onDailyList(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        ensure(rec);
        store.save(rec);
        session.send(MsgIds.S2C_DAILY_TASK_RET, pkt, dump.dailyTaskList(dailyEntries(rec)));
    }

    /**
     * 跨日日常已清：走 S2C 1102 增量把整份日常重播一遍。
     * APK 侧 {@code OnUpdateDailyTask} 无 {@code mAllDailyTaskInfoNeedSyncServer} 门控
     * （该标志只在冷启为 true，收到 1101 后置 false 且全工程无处置回 true），
     * 而 1101 全量会被丢弃；不发这一包，在线跨日的号日常面板会一直停在昨天。
     */
    public void pushDailyReset(GameSession session, GamePacket pkt, PlayerRecord rec) {
        if (session == null || rec == null) {
            return;
        }
        ensure(rec);
        session.send(MsgIds.S2C_DAILY_TASK_UPDATE, pkt == null ? 0 : pkt.serial,
                dump.dailyTaskList(dailyEntries(rec)));
    }

    /** 1101（全量）与跨日重播共用的日常条目：逐条按当前 finishTimes/prized 序列化。 */
    private List<byte[]> dailyEntries(PlayerRecord rec) {
        List<byte[]> items = new ArrayList<>();
        for (TaskTables.DailyCfg cfg : tables.dailyAll()) {
            PlayerRecord.DailyTask t = rec.tasks.daily(cfg.id);
            items.add(dump.dailyTaskInfo(cfg.id, t.finishTimes, t.prized));
        }
        return items;
    }

    public void onOnceList(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        ensure(rec);
        store.save(rec);
        List<byte[]> items = new ArrayList<>();
        for (TaskTables.OnceCfg cfg : tables.onceAll()) {
            if (!unlocked(rec, cfg)) {
                continue;
            }
            PlayerRecord.OnceTask t = rec.tasks.once.get(Integer.valueOf(cfg.id));
            if (t == null || t.deleted) {
                continue;
            }
            items.add(dump.onceTaskInfo(cfg.id, t.finishTimes, false));
        }
        session.send(MsgIds.S2C_ONCE_TASK_RET, pkt, dump.onceTaskList(items));
    }

    public void onPrizeDaily(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        ensure(rec);
        int taskId = Pb.read(pkt.body).getInt(1, 0);
        TaskTables.DailyCfg cfg = tables.daily(taskId);
        PlayerRecord.DailyTask t = rec.tasks.daily.get(Integer.valueOf(taskId));
        if (cfg == null || t == null || t.prized || t.finishTimes < cfg.finishCount) {
            return;
        }
        t.prized = true;
        grant(session, pkt, rec, cfg.exp, cfg.gold, cfg.rmb, cfg.goods1, cfg.cnt1, cfg.goods2, cfg.cnt2);
        store.save(rec);
        session.send(MsgIds.S2C_DAILY_TASK_UPDATE, pkt,
                dump.dailyTaskList(java.util.Collections.singletonList(
                        dump.dailyTaskInfo(taskId, t.finishTimes, true))));
        log.info("{} prize daily {}", rec.account, taskId);
    }

    public void onPrizeOnce(GameSession session, GamePacket pkt) {
        PlayerRecord rec = session.player();
        if (rec == null) {
            return;
        }
        ensure(rec);
        int taskId = Pb.read(pkt.body).getInt(1, 0);
        TaskTables.OnceCfg cfg = tables.once(taskId);
        PlayerRecord.OnceTask t = rec.tasks.once.get(Integer.valueOf(taskId));
        if (cfg == null || t == null || t.deleted || t.finishTimes < cfg.finishCount) {
            return;
        }
        Set<Integer> existed = new HashSet<Integer>(rec.tasks.once.keySet());
        t.deleted = true;
        grant(session, pkt, rec, cfg.exp, cfg.gold, cfg.rmb, cfg.goods1, cfg.cnt1, cfg.goods2, cfg.cnt2);
        if (unlockOnce(rec)) {
            mail.notifyNewMail(rec.account);
        }
        List<byte[]> items = new ArrayList<>();
        items.add(dump.onceTaskInfo(taskId, t.finishTimes, true));
        for (Map.Entry<Integer, PlayerRecord.OnceTask> e : rec.tasks.once.entrySet()) {
            if (e.getKey().intValue() == taskId || existed.contains(e.getKey())) {
                continue;
            }
            if (e.getValue().deleted) {
                continue;
            }
            items.add(dump.onceTaskInfo(e.getKey().intValue(), e.getValue().finishTimes, false));
        }
        store.save(rec);
        session.send(MsgIds.S2C_ONCE_TASK_UPDATE, pkt, dump.onceTaskList(items));
        log.info("{} prize once {}", rec.account, taskId);
    }

    public void onChapterWin(GameSession session, GamePacket pkt, PlayerRecord rec, int region, int diff, int times) {
        if (rec == null || times <= 0) {
            return;
        }
        ensure(rec);
        List<byte[]> dailyDirty = new ArrayList<>();
        if (diff == 1) {
            addDaily(rec, DAILY_NORMAL_FB, times, dailyDirty);
        } else if (diff == 2) {
            addDaily(rec, DAILY_HARD_FB, times, dailyDirty);
        }
        List<byte[]> onceDirty = new ArrayList<>();
        int wantType = diff == 2 ? ONCE_HARD_STAGE : ONCE_NORMAL_STAGE;
        for (TaskTables.OnceCfg cfg : tables.onceAll()) {
            if (cfg.type != wantType || cfg.customParam != region) {
                continue;
            }
            PlayerRecord.OnceTask t = rec.tasks.once.get(Integer.valueOf(cfg.id));
            if (t == null || t.deleted) {
                continue;
            }
            int next = Math.min(cfg.finishCount, t.finishTimes + times);
            if (next != t.finishTimes) {
                t.finishTimes = next;
                onceDirty.add(dump.onceTaskInfo(cfg.id, t.finishTimes, false));
            }
        }
        if (dailyDirty.isEmpty() && onceDirty.isEmpty()) {
            return;
        }
        store.save(rec);
        if (!dailyDirty.isEmpty()) {
            session.send(MsgIds.S2C_DAILY_TASK_UPDATE, pkt, dump.dailyTaskList(dailyDirty));
        }
        if (!onceDirty.isEmpty()) {
            session.send(MsgIds.S2C_ONCE_TASK_UPDATE, pkt, dump.onceTaskList(onceDirty));
        }
    }

    public void onDailyAction(GameSession session, GamePacket pkt, PlayerRecord rec, int type, int times) {
        if (rec == null || times <= 0) {
            return;
        }
        ensure(rec);
        List<byte[]> dirty = new ArrayList<>();
        addDaily(rec, type, times, dirty);
        if (dirty.isEmpty()) {
            return;
        }
        store.save(rec);
        session.send(MsgIds.S2C_DAILY_TASK_UPDATE, pkt, dump.dailyTaskList(dirty));
    }

    /** 账号升级：反填主线 type4（攻略组等级）并推 1104。 */
    public void onPlayerLeveled(GameSession session, GamePacket pkt, PlayerRecord rec, boolean leveled) {
        if (!leveled) {
            return;
        }
        syncOnce(session, pkt, rec);
    }

    /** 养成/抽卡/升级后按当前账号状态反填主线 type3–7，有变化才推 1104。 */
    public void syncOnce(GameSession session, GamePacket pkt, PlayerRecord rec) {
        if (rec == null) {
            return;
        }
        Map<Integer, Integer> before = snapshotOnce(rec);
        ensure(rec);
        List<byte[]> dirty = new ArrayList<>();
        for (TaskTables.OnceCfg cfg : tables.onceAll()) {
            if (!unlocked(rec, cfg)) {
                continue;
            }
            PlayerRecord.OnceTask t = rec.tasks.once.get(Integer.valueOf(cfg.id));
            if (t == null || t.deleted) {
                continue;
            }
            Integer prev = before.get(Integer.valueOf(cfg.id));
            if (prev == null || prev.intValue() != t.finishTimes) {
                dirty.add(dump.onceTaskInfo(cfg.id, t.finishTimes, false));
            }
        }
        if (dirty.isEmpty()) {
            return;
        }
        store.save(rec);
        session.send(MsgIds.S2C_ONCE_TASK_UPDATE, pkt, dump.onceTaskList(dirty));
    }

    private void addDaily(PlayerRecord rec, int type, int times, List<byte[]> dirty) {
        for (TaskTables.DailyCfg cfg : tables.dailyAll()) {
            if (cfg.type != type) {
                continue;
            }
            PlayerRecord.DailyTask t = rec.tasks.daily(cfg.id);
            if (t.prized) {
                continue;
            }
            int next = Math.min(cfg.finishCount, t.finishTimes + times);
            if (next != t.finishTimes) {
                t.finishTimes = next;
                dirty.add(dump.dailyTaskInfo(cfg.id, t.finishTimes, t.prized));
            }
        }
    }

    private boolean unlockOnce(PlayerRecord rec) {
        rec.ensureCollections();
        boolean changed = true;
        boolean mailed = false;
        while (changed) {
            changed = false;
            for (TaskTables.OnceCfg cfg : tables.onceAll()) {
                if (!preDone(rec, cfg.preTaskId)) {
                    continue;
                }
                PlayerRecord.OnceTask t = rec.tasks.once.get(Integer.valueOf(cfg.id));
                if (t == null) {
                    t = rec.tasks.once(cfg.id);
                    backfillOnce(rec, cfg, t);
                    mailed |= sendAcceptMail(rec, cfg);
                    changed = true;
                } else if (!t.deleted) {
                    int before = t.finishTimes;
                    backfillOnce(rec, cfg, t);
                    if (t.finishTimes != before) {
                        changed = true;
                    }
                }
            }
        }
        return mailed;
    }

    /** 表列非 0：解锁时 mailType=18。grantKey 防重。 */
    private boolean sendAcceptMail(PlayerRecord rec, TaskTables.OnceCfg cfg) {
        if (cfg == null || cfg.notifyMail == 0) {
            return false;
        }
        String key = "once-accept/" + cfg.id;
        if (rec.economy.mailGrantKeys.contains(key)) {
            return false;
        }
        String title = (cfg.name == null || cfg.name.isEmpty()) ? ("任务" + cfg.id) : cfg.name;
        mail.appendSystemMail(rec, MailService.MAIL_TYPE_TASK,
                Collections.singletonList(title),
                java.util.Arrays.asList(title, rewardText(cfg)),
                key,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                null);
        log.info("{} once-accept mail task={} type=18", rec.account, Integer.valueOf(cfg.id));
        return true;
    }

    private static String rewardText(TaskTables.OnceCfg cfg) {
        StringBuilder sb = new StringBuilder();
        if (cfg.gold > 0) {
            sb.append("金币").append(cfg.gold);
        }
        if (cfg.rmb > 0) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append("钻石").append(cfg.rmb);
        }
        if (cfg.exp > 0) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append("经验").append(cfg.exp);
        }
        appendGoods(sb, cfg.goods1, cfg.cnt1);
        appendGoods(sb, cfg.goods2, cfg.cnt2);
        return sb.length() == 0 ? "见任务奖励" : sb.toString();
    }

    private static void appendGoods(StringBuilder sb, String ori, int n) {
        if (ori == null || ori.isEmpty() || "0".equals(ori) || n <= 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(ori).append('x').append(n);
    }

    private boolean unlocked(PlayerRecord rec, TaskTables.OnceCfg cfg) {
        return preDone(rec, cfg.preTaskId);
    }

    private boolean preDone(PlayerRecord rec, int preId) {
        if (preId <= 0) {
            return true;
        }
        PlayerRecord.OnceTask pre = rec.tasks.once.get(Integer.valueOf(preId));
        return pre != null && pre.deleted;
    }

    private static Map<Integer, Integer> snapshotOnce(PlayerRecord rec) {
        Map<Integer, Integer> m = new HashMap<>();
        if (rec.tasks == null || rec.tasks.once == null) {
            return m;
        }
        for (Map.Entry<Integer, PlayerRecord.OnceTask> e : rec.tasks.once.entrySet()) {
            if (e.getValue() == null || e.getValue().deleted) {
                continue;
            }
            m.put(e.getKey(), Integer.valueOf(e.getValue().finishTimes));
        }
        return m;
    }

    private void backfillOnce(PlayerRecord rec, TaskTables.OnceCfg cfg, PlayerRecord.OnceTask t) {
        if (t.deleted) {
            return;
        }
        if (cfg.type == ONCE_NORMAL_STAGE || cfg.type == ONCE_HARD_STAGE) {
            int diff = cfg.type == ONCE_HARD_STAGE ? 2 : 1;
            String key = PlayerRecord.stageKey(cfg.customParam, diff);
            if (rec.progress.stageStars.getOrDefault(key, Integer.valueOf(0)).intValue() > 0) {
                t.finishTimes = Math.max(t.finishTimes, cfg.finishCount);
            }
            return;
        }
        int have = onceHave(rec, cfg);
        t.finishTimes = Math.max(t.finishTimes, Math.min(cfg.finishCount, have));
    }

    /** 表：type3 武将数 / 4 账号等级 / 5 最高进阶 / 6 时光石等级 / 7 紫装星。 */
    private int onceHave(PlayerRecord rec, TaskTables.OnceCfg cfg) {
        if (cfg.type == ONCE_COLLECT_HERO) {
            return rec.heroes == null ? 0 : rec.heroes.size();
        }
        if (cfg.type == ONCE_PLAYER_LEVEL) {
            return rec.level >= cfg.customParam ? cfg.finishCount : 0;
        }
        if (cfg.type == ONCE_JINJIE) {
            int max = 0;
            if (rec.heroes != null) {
                for (PlayerRecord.Hero wj : rec.heroes) {
                    if (wj != null) {
                        max = Math.max(max, wj.stage);
                    }
                }
            }
            return max >= cfg.customParam ? cfg.finishCount : 0;
        }
        if (cfg.type == ONCE_TIME_STONE) {
            return maxTimeStoneLevel(rec) >= cfg.customParam ? cfg.finishCount : 0;
        }
        if (cfg.type == ONCE_EQUIP_STAR) {
            int max = 0;
            if (rec.equipments != null) {
                for (PlayerRecord.Equipment eq : rec.equipments) {
                    if (eq == null) {
                        continue;
                    }
                    CultivateTables.EquipCfg ec = cultivate.equip(eq.ori);
                    if (ec != null && ec.quality >= 3) {
                        max = Math.max(max, eq.stars);
                    }
                }
            }
            return max >= cfg.customParam ? cfg.finishCount : 0;
        }
        return 0;
    }

    private static int maxTimeStoneLevel(PlayerRecord rec) {
        int max = 0;
        if (rec.bag != null) {
            for (String ori : rec.bag.keySet()) {
                max = Math.max(max, timeStoneLevel(ori));
            }
        }
        if (rec.heroes != null) {
            for (PlayerRecord.Hero wj : rec.heroes) {
                if (wj == null || wj.timeStones == null) {
                    continue;
                }
                for (String ori : wj.timeStones) {
                    max = Math.max(max, timeStoneLevel(ori));
                }
            }
        }
        return max;
    }

    /** TS101 / TS208 → 末位等级。 */
    static int timeStoneLevel(String ori) {
        if (ori == null || !ori.startsWith("TS") || ori.length() < 4) {
            return 0;
        }
        try {
            return Integer.parseInt(ori.replaceAll("[^0-9]", "")) % 10;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void grant(GameSession session, GamePacket pkt, PlayerRecord rec,
                       int exp, int gold, int rmb, String g1, int c1, String g2, int c2) {
        boolean leveled = progress.addPlayerExp(rec, exp);
        rec.gold += Math.max(0, gold);
        rec.diamond += Math.max(0, rmb);
        Map<String, Integer> changed = progress.emptyChanged();
        progress.addGoods(rec, g1, c1);
        progress.markGoods(changed, g1);
        progress.addGoods(rec, g2, c2);
        progress.markGoods(changed, g2);
        progress.pushGoods(session, pkt, rec, changed);
        progress.pushPlayerProgress(session, pkt, rec, exp > 0, gold > 0, leveled, leveled, rmb > 0);
        onPlayerLeveled(session, pkt, rec, leveled);
    }
}
