package com.sao.fakeserver.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sao.fakeserver.config.SaoProperties;
import com.sao.fakeserver.util.GameTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.scheduling.annotation.Scheduled;

import javax.annotation.PreDestroy;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class WorldStore {
    private static final Logger log = LoggerFactory.getLogger(WorldStore.class);
    /** 存档里的时间格式，与 {@code PlayerDumpService.TIME} 同口径。 */
    private static final String SERVER_TIME = "yyyy-MM-dd HH:mm:ss";

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Path unionsFile;
    private final Path jjcFile;
    private final Path minesFile;
    private final Path kfzPodiumFile;
    private final Path serverFile;
    /** 仅用于取 {@code sao.open-server-time}（开服时间的显式覆盖）。 */
    private final SaoProperties props;
    private final List<UnionRecord> unions = new CopyOnWriteArrayList<>();
    private JjcWorld jjc = new JjcWorld();
    private KfzPodium kfzPodium = new KfzPodium();
    private ServerInfo serverInfo = new ServerInfo();
    private final Map<Integer, MineSlot> mines = new ConcurrentHashMap<>();
    private final AtomicBoolean minesDirty = new AtomicBoolean(false);

    public WorldStore(SaoProperties props) throws IOException {
        this.props = props;
        Path dir = Paths.get(props.getWorldDir()).toAbsolutePath();
        Files.createDirectories(dir);
        this.unionsFile = dir.resolve("unions.json");
        this.jjcFile = dir.resolve("jjc.json");
        this.minesFile = dir.resolve("mines.json");
        this.kfzPodiumFile = dir.resolve("kfz-podium.json");
        this.serverFile = dir.resolve("server.json");
        loadUnions();
        loadJjc();
        loadMines();
        loadKfzPodium();
        loadServer();
    }

    // ---------------------------------------------------------------- 开服时间

    /**
     * 服务器开服时间（{@code yyyy-MM-dd HH:mm:ss}）。
     *
     * <p>取值优先级：{@code sao.open-server-time} 配置 → {@code data/world/server.json} →
     * <b>首次启动时刻</b>（并立刻落盘）。这是<b>全服唯一</b>的开服锚点：
     * 开服狂欢（7 日狂欢 / 半月庆典）等「开服第 N 天」玩法一律用它，
     * 不能再用单个玩家的 {@code createdAt}（那样每个账号各算一套，多人环境下活动进度会分裂）。
     */
    public synchronized String openServerTime() {
        String configured = props.getOpenServerTime();
        if (configured != null && !configured.trim().isEmpty()) {
            String v = configured.trim();
            if (!v.equals(serverInfo.openServerTime)) {
                serverInfo.openServerTime = v;
                saveServer();
            }
            return v;
        }
        if (serverInfo.openServerTime != null && !serverInfo.openServerTime.trim().isEmpty()) {
            return serverInfo.openServerTime.trim();
        }
        serverInfo.openServerTime = GameTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern(SERVER_TIME));
        saveServer();
        log.info("server open time initialized: {} (data/world/server.json)", serverInfo.openServerTime);
        return serverInfo.openServerTime;
    }

    /** 开服日期；解析失败时退化为今天（不让坏档卡死登录）。 */
    public synchronized LocalDate openServerDate() {
        String s = openServerTime();
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (RuntimeException e) {
            log.warn("bad server open time '{}', fallback to today", s);
            return GameTime.today();
        }
    }

    /** 开服第几天，开服当天 = 1。 */
    public synchronized int openDay() {
        long d = ChronoUnit.DAYS.between(openServerDate(), GameTime.today()) + 1;
        return (int) Math.max(1L, d);
    }

    public synchronized void saveServer() {
        if (serverInfo.note == null || serverInfo.note.isEmpty()) {
            serverInfo.note = ServerInfo.NOTE;
        }
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(serverFile.toFile(), serverInfo);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void loadServer() throws IOException {
        if (!Files.isRegularFile(serverFile)) {
            return;
        }
        ServerInfo loaded = mapper.readValue(serverFile.toFile(), ServerInfo.class);
        if (loaded != null) {
            serverInfo = loaded;
        }
        if (serverInfo.note == null || serverInfo.note.isEmpty()) {
            serverInfo.note = ServerInfo.NOTE;
        }
        log.info("loaded server info openTime={}", serverInfo.openServerTime);
    }

    public synchronized List<UnionRecord> unions() {
        return unions;
    }

    public synchronized UnionRecord findUnion(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (UnionRecord u : unions) {
            if (id.equals(u.id)) {
                return u;
            }
        }
        return null;
    }

    public synchronized UnionRecord findUnionByName(String name) {
        for (UnionRecord u : unions) {
            if (name != null && name.equals(u.name)) {
                return u;
            }
        }
        return null;
    }

    public synchronized void saveUnions() {
        UnionsFile file = new UnionsFile();
        file.unions = new ArrayList<>(unions);
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(unionsFile.toFile(), file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized JjcWorld jjc() {
        return jjc;
    }

    /** 清空公会 / 竞技场 / 抢矿 / KFZ 领奖台并写回空 JSON。 */
    public synchronized void clearAll() {
        unions.clear();
        saveUnions();
        jjc = new JjcWorld();
        saveJjc();
        kfzPodium = new KfzPodium();
        saveKfzPodium();
        mines.clear();
        minesDirty.set(true);
        flushMines(true);
    }

    public synchronized void saveJjc() {
        if (jjc.note == null || jjc.note.isEmpty()) {
            jjc.note = JjcWorld.NOTE;
        }
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(jjcFile.toFile(), jjc);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized KfzPodium kfzPodium() {
        if (kfzPodium.slots == null) {
            kfzPodium.slots = new ArrayList<>();
        }
        return kfzPodium;
    }

    public synchronized void saveKfzPodium() {
        if (kfzPodium.slots == null) {
            kfzPodium.slots = new ArrayList<>();
        }
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(kfzPodiumFile.toFile(), kfzPodium);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 写入/覆盖领奖台某一展示位（displayRank 1..3）。
     * star/score 供 4706 RankItem；resId 须 &gt;0 否则主城雕像模型解析失败。
     */
    public synchronized void setKfzPodiumSlot(int displayRank, int guid, String name, int resId,
                                             int level, int serverId, int star, int score) {
        if (displayRank < 1 || displayRank > 3) {
            return;
        }
        KfzPodium podium = kfzPodium();
        KfzPodiumSlot slot = null;
        for (KfzPodiumSlot s : podium.slots) {
            if (s != null && s.displayRank == displayRank) {
                slot = s;
                break;
            }
        }
        if (slot == null) {
            slot = new KfzPodiumSlot();
            slot.displayRank = displayRank;
            podium.slots.add(slot);
        }
        slot.guid = guid;
        slot.name = name == null ? "" : name;
        slot.resId = resId > 0 ? resId : 18;
        slot.level = level > 0 ? level : 1;
        slot.serverId = serverId > 0 ? serverId : 1;
        slot.star = star;
        slot.score = score;
        saveKfzPodium();
    }

    private void loadUnions() throws IOException {
        if (!Files.isRegularFile(unionsFile)) {
            return;
        }
        byte[] raw = Files.readAllBytes(unionsFile);
        String text = new String(raw, java.nio.charset.StandardCharsets.UTF_8).trim();
        if (text.startsWith("[")) {
            UnionRecord[] arr = mapper.readValue(raw, UnionRecord[].class);
            unions.clear();
            if (arr != null) {
                for (UnionRecord u : arr) {
                    u.ensure();
                    unions.add(u);
                }
            }
            saveUnions();
            return;
        }
        UnionsFile file = mapper.readValue(raw, UnionsFile.class);
        unions.clear();
        if (file != null && file.unions != null) {
            for (UnionRecord u : file.unions) {
                u.ensure();
                unions.add(u);
            }
        }
        log.info("loaded {} unions", unions.size());
    }

    public Map<Integer, MineSlot> mines() {
        return mines;
    }

    public void markMinesDirty() {
        minesDirty.set(true);
    }

    /**
     * 抢矿世界数据落盘：约每 60 秒若 dirty 则写 data/world/mines.json。
     * 与跨日/发奖无关；关服时 {@link #flushMinesOnExit} 还会强制再刷一次。
     */
    @Scheduled(fixedDelay = 60000)
    public void flushMinesScheduled() {
        flushMines(false);
    }

    @PreDestroy
    public void flushMinesOnExit() {
        flushMines(true);
    }

    public synchronized void saveMines() {
        minesDirty.set(true);
        flushMines(true);
    }

    private synchronized void flushMines(boolean force) {
        if (!force && !minesDirty.get()) {
            return;
        }
        MinesFile file = new MinesFile();
        file.mines.addAll(mines.values());
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(minesFile.toFile(), file);
            minesDirty.set(false);
        } catch (IOException e) {
            log.warn("save mines failed: {}", e.toString());
        }
    }

    private void loadMines() throws IOException {
        if (!Files.isRegularFile(minesFile)) {
            return;
        }
        MinesFile file = mapper.readValue(minesFile.toFile(), MinesFile.class);
        mines.clear();
        if (file != null && file.mines != null) {
            for (MineSlot s : file.mines) {
                if (s != null && s.id > 0) {
                    mines.put(Integer.valueOf(s.id), s);
                }
            }
        }
        log.info("loaded {} mines", mines.size());
    }

    private void loadJjc() throws IOException {
        if (!Files.isRegularFile(jjcFile)) {
            return;
        }
        jjc = mapper.readValue(jjcFile.toFile(), JjcWorld.class);
        if (jjc.ranks == null) {
            jjc.ranks = new ArrayList<>();
        }
        if (jjc.note == null || jjc.note.isEmpty()) {
            jjc.note = JjcWorld.NOTE;
        }
    }

    private void loadKfzPodium() throws IOException {
        if (!Files.isRegularFile(kfzPodiumFile)) {
            return;
        }
        kfzPodium = mapper.readValue(kfzPodiumFile.toFile(), KfzPodium.class);
        if (kfzPodium == null) {
            kfzPodium = new KfzPodium();
        }
        if (kfzPodium.slots == null) {
            kfzPodium.slots = new ArrayList<>();
        }
        log.info("loaded kfz podium slots={}", kfzPodium.slots.size());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UnionsFile {
        @JsonProperty("_说明")
        public String note = "手改说明: unions[].id 公会ID. joinType=verify审核/direct直接进/deny拒绝. "
                + "members[].job=member/elder/owner. members[].playerId 对账号 JSON 的 playerId. "
                + "bossHp 是全会共享剩余血量，会长 C2S 1533 重置为 bossMaxHp.";
        public List<UnionRecord> unions = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"id", "name", "icon", "notice", "level", "joinType", "joinLevel",
            "crystal", "growth", "bossHp", "bossMaxHp", "bossChapter", "bossOri", "members"})
    public static class UnionRecord {
        public String id = "";
        public String name = "";
        public String icon = "";
        public String notice = "欢迎加入";
        public int level = 1;
        /** verify / direct / deny */
        public String joinType = "direct";
        public int joinLevel = 1;
        public int crystal;
        public int growth;
        public int bossHp = 1000000;
        public int bossMaxHp = 1000000;
        public int bossChapter = 1;
        public String bossOri = "UnionBoss1";
        /** 建筑类型 1–7 → 等级，缺省按 1 */
        public java.util.Map<Integer, Integer> buildings = new java.util.LinkedHashMap<Integer, Integer>();
        /** 建筑升级中：类型 → 升级完成时刻(ms)。缺省/0 = 不在升级中。对齐 UnionBuildingLevelUp.txt 的「到下一级消耗的时间」。 */
        public java.util.Map<Integer, Long> buildingUpgradeEnd = new java.util.LinkedHashMap<Integer, Long>();
        /** 各建筑已雇佣武将：类型 → 佣兵列表。对齐 1917 CCMsgRequestOneUnionBuildingAllEmployers_Ret。 */
        public java.util.Map<Integer, java.util.List<Employer>> employers =
                new java.util.LinkedHashMap<Integer, java.util.List<Employer>>();
        /** 各章节 Boss 剩余血量：章节 → 血量。缺省 = 满血（按 MonsterProperty.ini 的 UnionBossN.maxHp）。 */
        public java.util.Map<Integer, Integer> bossHpMap = new java.util.LinkedHashMap<Integer, Integer>();
        /** 各章节累计伤害：章节 → (account → 伤害)。驱动 1923 bossInfo 字段 4 hurtRankList。 */
        public java.util.Map<Integer, java.util.List<DamageEntry>> bossDamage =
                new java.util.LinkedHashMap<Integer, java.util.List<DamageEntry>>();
        /** 入会申请列表（C2S 1503 且 joinType=EUJT_Verify 时登记），1907 下发、1506 审批。 */
        public List<Member> requesters = new ArrayList<>();
        /** 作战室拍卖货架（C2S 1530/1532 结算后由 Boss 掉落生成）。 */
        public List<AuctionLot> auctionLots = new ArrayList<>();
        /** 拍卖货架日期 yyyy-MM-dd，跨日重刷。 */
        public String auctionDay = "";
        /**
         * 拍卖结算所属游戏日 yyyy-MM-dd。**必须落档**（去重键不能只在进程内存里）：
         * 20 点后重启会让当天 20 点之后新入架的掉落被立刻结算
         * （21:00 击杀 → 21:10 重启 → 21:11 就发奖，货架没机会被别人出价）。
         * 每天 20 点后无论货架是否为空都要写这个键 —— 空货架的公会若不标记，
         * 重启后当天新入架的掉落照样会被提前结算。
         */
        public String auctionSettledDay = "";
        /**
         * 待告知「会长被自动让位」的前会长 playerId（0 = 无）。该玩家下次登录时补推 1968
         * {@code CCMsgUnionNotifyOwnerChangeOnce}，客户端弹 StrTable 101282
         * 「由于你一周未登录游戏,会长职务由{0}接任」（PlayGameState.cs OnNET_CCMsgUnionPresidentChange_Ret）。
         */
        public int pendingOwnerChangeFor;
        /** 1968 里填的新会长名（{0}）。 */
        public String pendingOwnerChangeName = "";
        /**
         * 公会战 PvP 报名（C2S 1549 → 1946）。报名消耗 UnionPvP.txt「报名消耗的公会晶石」。
         * 窗口由 {@code UnionService.inPvpEnrollWindow} 按 {@code UnionPvPTime.txt} 判定，
         * 口径与客户端相位机（APK {@code Client\᝴.cs:34} 的 {@code ᜀ()}）完全一致：
         * 战斗日（表里首列为 {@code #} 的行，出厂 周一/三/六）的 19:00–21:00 关闭
         * （19:00–20:00 InProgress、20:00–21:00 JoinLimit，客户端 100774/100773 两种提示都拒绝），
         * **非战斗日全天开放**（客户端相位恒为 Preparing，报名面板照常可点）。
         * 结算后本字段立刻置回 false，让客户端 21:00 进入 Idle 相位后能为下一场再报名。
         */
        public boolean pvpEnrolled;
        /** 当前统计/结算所属游戏日 yyyy-MM-dd（跨日滚天）。 */
        public String pvpDay = "";
        /**
         * 本次报名**冲着哪一场战斗日**去的 yyyy-MM-dd（报名目标日，跨日延续到那一场）。
         *
         * <p>客户端 100727「报名成功，公会战将于[FFFF00]{0}{1}:{2}[-]开启」里的 {0} 取
         * {@code ᜴.ᜀ}（{@code UnionPvPTimeInfoMgr.GetNearestUpperInfo} 最近的一场战斗日），
         * 所以非战斗日、以及战斗日 21:00 后的报名都是**为下一场战斗日**报的名：
         * 周二 21:30 报名 → 周三；周三 21:30 报名 → 周六。跨日时若本字段还在未来，
         * 报名**不清**（否则客户端承诺的「周三19:00 开启」会落空），
         * 结算也只在 {@code pvpDay == pvpEnrollDay} 时发生。
         */
        public String pvpEnrollDay = "";
        /** 据点 ID → 该据点的防守阵容（C2S 1551/1552 → 1948/1950/1957/1959/1960）。 */
        public java.util.Map<Integer, java.util.List<PvpFormation>> pvpDefFormations =
                new java.util.LinkedHashMap<Integer, java.util.List<PvpFormation>>();
        /** 我方已攻占的据点 ID（1567 结算时按目标据点推进，用于 1958 的 isCanAttack）。 */
        public java.util.List<Integer> pvpCapturedPoints = new ArrayList<>();
        /** 公会战战报（1567 → 1956/1965）。 */
        public java.util.List<PvpFightRecord> pvpRecords = new ArrayList<>();
        public int pvpWinTimes;
        public int pvpFailTimes;
        /**
         * 1958/1959 f1/f2 与 1965 f4/f5 的「被攻占据点数」**不再用独立计数器**：
         * 改前这里的两个 int 全库只有清零没有自增（积分牌恒 0），现在由
         * {@code UnionService.selfAttackedPoints/capturedCount} 从双方 {@link #pvpCapturedPoints} 推导。
         * 字段保留仅为兼容旧存档 JSON。
         */
        public int pvpSelfAttackedPoints;
        public int pvpTargetAttackedPoints;
        /** 己方单个据点被攻击的次数（1963 CCMsgUpdateUnionPvPPointFormationAttacker）。 */
        public int pvpAttackerCnt;
        /**
         * 当天配对到的对手公会 id。真服按赛程配对（无表可依，见 docs 待拍板项④），
         * 假服在 1549 报名时把当时选中的对手**钉住**，当天不再漂移；
         * 结算时该公会已解散 → mailType 15「对方公会解散」。
         */
        public String pvpRivalId = "";
        /** 公会战结算所属游戏日 yyyy-MM-dd（分钟级钩子只结算一次）。 */
        public String pvpSettledDay = "";
        public List<Member> members = new ArrayList<>();

        public void ensure() {
            if (id == null) {
                id = "";
            }
            if (joinType == null || joinType.isEmpty()) {
                joinType = "direct";
            }
            if (members == null) {
                members = new ArrayList<>();
            }
            if (requesters == null) {
                requesters = new ArrayList<>();
            }
            if (auctionLots == null) {
                auctionLots = new ArrayList<>();
            }
            if (auctionDay == null) {
                auctionDay = "";
            }
            if (auctionSettledDay == null) {
                auctionSettledDay = "";
            }
            if (pendingOwnerChangeName == null) {
                pendingOwnerChangeName = "";
            }
            if (pvpDay == null) {
                pvpDay = "";
            }
            if (pvpEnrollDay == null) {
                pvpEnrollDay = "";
            }
            if (pvpRivalId == null) {
                pvpRivalId = "";
            }
            if (pvpSettledDay == null) {
                pvpSettledDay = "";
            }
            if (pvpDefFormations == null) {
                pvpDefFormations = new java.util.LinkedHashMap<Integer, java.util.List<PvpFormation>>();
            }
            if (pvpCapturedPoints == null) {
                pvpCapturedPoints = new ArrayList<>();
            }
            if (pvpRecords == null) {
                pvpRecords = new ArrayList<>();
            }
            if (buildingUpgradeEnd == null) {
                buildingUpgradeEnd = new java.util.LinkedHashMap<Integer, Long>();
            }
            if (employers == null) {
                employers = new java.util.LinkedHashMap<Integer, java.util.List<Employer>>();
            }
            // 旧存档迁移：Employer 早先没有 hiredBy/slotIndex，语义是「整张公会表 + 列表下标即格位」。
            // 迁移成 hiredBy=原 playerId、slotIndex=列表下标，否则老档的佣兵在 1915 里会全部消失。
            for (java.util.List<Employer> list : employers.values()) {
                if (list == null) {
                    continue;
                }
                for (int i = 0; i < list.size(); i++) {
                    Employer e = list.get(i);
                    if (e != null && e.hiredBy == 0) {
                        e.hiredBy = e.playerId;
                        e.slotIndex = i;
                    }
                }
            }
            if (bossHpMap == null) {
                bossHpMap = new java.util.LinkedHashMap<Integer, Integer>();
            }
            if (bossDamage == null) {
                bossDamage = new java.util.LinkedHashMap<Integer, java.util.List<DamageEntry>>();
            }
            if (buildings == null) {
                buildings = new java.util.LinkedHashMap<Integer, Integer>();
            }
            if (bossOri == null || bossOri.isEmpty()) {
                bossOri = "UnionBoss1";
            }
            if (bossMaxHp <= 0) {
                bossMaxHp = 1000000;
            }
            if (bossChapter <= 0) {
                bossChapter = 1;
            }
            for (int t = 1; t <= 7; t++) {
                if (!buildings.containsKey(Integer.valueOf(t))) {
                    buildings.put(Integer.valueOf(t), Integer.valueOf(1));
                }
            }
        }

        public int joinTypeCode() {
            if ("deny".equals(joinType)) {
                return 2;
            }
            if ("direct".equals(joinType)) {
                return 1;
            }
            return 0;
        }

        public Member findMember(int playerId) {
            for (Member m : members) {
                if (m.playerId == playerId) {
                    return m;
                }
            }
            return null;
        }

        /** 建筑 {@code type} 的佣兵列表（不返回 null）。 */
        public java.util.List<Employer> employersOf(int type) {
            ensure();
            java.util.List<Employer> list = employers.get(Integer.valueOf(type));
            if (list == null) {
                list = new ArrayList<>();
                employers.put(Integer.valueOf(type), list);
            }
            return list;
        }

        /** 建筑 {@code type} 的升级完成时刻；0 = 未在升级中。 */
        public long upgradeEndOf(int type) {
            ensure();
            Long end = buildingUpgradeEnd.get(Integer.valueOf(type));
            return end == null ? 0L : end.longValue();
        }

        /** 建筑 {@code type} 是否正在升级中。 */
        public boolean isUpgrading(int type, long nowMs) {
            return upgradeEndOf(type) > nowMs;
        }

        /** 章节 Boss 当前血量；未记录则按 {@code maxHp} 满血。 */
        public int bossHpOf(int chapter, int maxHp) {
            ensure();
            Integer hp = bossHpMap.get(Integer.valueOf(chapter));
            return hp == null ? maxHp : Math.max(0, hp.intValue());
        }

        public void setBossHp(int chapter, int hp) {
            ensure();
            bossHpMap.put(Integer.valueOf(chapter), Integer.valueOf(Math.max(0, hp)));
        }

        /** 章节各玩家累计伤害（按伤害降序，不返回 null）。 */
        public java.util.List<DamageEntry> damageList(int chapter) {
            ensure();
            java.util.List<DamageEntry> list = bossDamage.get(Integer.valueOf(chapter));
            if (list == null) {
                list = new ArrayList<>();
                bossDamage.put(Integer.valueOf(chapter), list);
            }
            list.sort(new java.util.Comparator<DamageEntry>() {
                @Override
                public int compare(DamageEntry a, DamageEntry b) {
                    return Integer.compare(b.damage, a.damage);
                }
            });
            return list;
        }

        /** 累加一次 Boss 伤害；同一玩家按 playerId 合并。 */
        public void addDamage(int chapter, int playerId, String account, String name, int heroIndex,
                              int level, int damage) {
            if (damage <= 0) {
                return;
            }
            for (DamageEntry e : damageList(chapter)) {
                if (e.playerId == playerId && playerId > 0) {
                    e.damage += damage;
                    return;
                }
                if (playerId <= 0 && e.account.equals(account == null ? "" : account)) {
                    e.damage += damage;
                    return;
                }
            }
            DamageEntry e = new DamageEntry();
            e.playerId = playerId;
            e.account = account == null ? "" : account;
            e.name = name == null ? "" : name;
            e.heroIndex = heroIndex;
            e.level = level;
            e.damage = damage;
            damageList(chapter).add(e);
        }

        public int damageOf(int chapter, int playerId) {
            for (DamageEntry e : damageList(chapter)) {
                if (e.playerId == playerId && playerId > 0) {
                    return e.damage;
                }
            }
            return 0;
        }

        /** 据点 {@code point} 的防守阵容列表（不返回 null）。 */
        public java.util.List<PvpFormation> pvpFormationsOf(int point) {
            ensure();
            java.util.List<PvpFormation> list = pvpDefFormations.get(Integer.valueOf(point));
            if (list == null) {
                list = new ArrayList<>();
                pvpDefFormations.put(Integer.valueOf(point), list);
            }
            return list;
        }

        /** 据点 {@code point} 上指定序号的阵容；找不到返回 null。 */
        public PvpFormation pvpFindFormation(int point, int formationIndex) {
            for (PvpFormation f : pvpFormationsOf(point)) {
                if (f.formationIndex == formationIndex) {
                    return f;
                }
            }
            return null;
        }
    }

    /** 公会战据点防守阵容：对齐 1948 {@code CMsgDefPointDefFormation} 与 1950 {@code CMsgOneUnionPvPDefFormation}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PvpFormation {
        /** 据点 ID（{@code CMsgOneUnionPvPDefFormation.7 DefPointsIndex}）。 */
        public int point;
        /**
         * 阵容序号（**0 起**，客户端 CCMsgUpdateOnePointDefFormation.2 DefFormationIndex）。
         * 客户端从 {0,1,2} 里取第一个空位（{@code GongHuiZhanJuDianBuFangUI.cs:236-255 GetAEmptyFormationIndex}），
         * 行名就是该序号（{@code SetItem} 里 {@code trans.name = info.FormationIndex}）。
         */
        public int formationIndex;
        public int playerId;
        public String account = "";
        public String playerName = "";
        public int playerLevel = 1;
        /** 主角形象 ID（{@code CMsgOneUnionPvPDefFormation.5 PlayerResID}）。 */
        public int playerResId;
        public int fightPower;
        /**
         * {@code eFormationType}：24..28 = {@code FORMATION_TYPE_UNION_PVP_DEFENSE_1..5}，即该成员在
         * 「我的队伍」面板里保存的第 N 套公会战防守预设（201/202 通用阵容接口按 type 存读）。
         * 下阵由 1552 的 {@code DefPlayerGuid = 0} 标记，**与 type 无关**
         * （{@code GongHuiZhanJuDianBuFangUI.cs:207} 下阵时写 type 24 + guid 0）。
         */
        public int formationType;
        /**
         * 击杀者名字（1960 f7 {@code KillerName}）。空串 = 该阵容仍存活；非空 = 已被打掉。
         * 客户端据此把整行变暗 200 深度、显示骷髅并写击杀者名
         * （{@code GongHuiZhanJuDianAttackUI.cs:120/133/148-157}），故**打掉后不能从列表里删**。
         */
        public String killerName = "";
        /** 是否正在被攻击（1960 f8 {@code IsFighting}）：1564 开打时置位、1567 结算时清除。 */
        public boolean isFighting;
        /** 上阵武将（含 {1..5} 号位 job 与简要信息）。 */
        public java.util.List<PvpWjBrief> wjs = new ArrayList<>();
    }

    /** 阵容里一个武将位：对齐 {@code CMsgWuJiangJobAndBriefInfo{1 job,2 wjBriefInfo}}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PvpWjBrief {
        /** {@code eFormationJob}，1..5 号位。 */
        public int job;
        public int index;
        public int level = 1;
        public int stage;
        public int stars;
    }

    /** 公会战战报一行：对齐 1956 {@code CCMsgRequestUnionPvPFightRank_OneFightRecord}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PvpFightRecord {
        public String myName = "";
        public String myIcon = "";
        public int myLevel = 1;
        public String targetName = "";
        public String targetIcon = "";
        public int targetLevel;
        public boolean win;
    }

    /** Boss 伤害榜一行：对齐 1923/1926 的 CCMsgBossFightPlayerHurtInfo{name,resID,level,damageContri}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DamageEntry {
        public int playerId;
        public String account = "";
        public String name = "";
        public int heroIndex;
        public int level = 1;
        public int damage;
    }

    /** 建筑佣兵：对齐 1917 CCMsgWJInfoForOneUnionBuildingAllEmployers。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Employer {
        public int playerId;
        public String account = "";
        public String name = "";
        public int wjIndex;
        public int wjLevel;
        public int wjStage;
        public int wjStars;
        public int fightPower;
        /** 雇佣价（金币）。 */
        public int price;
        /** 雇佣冷却结束时刻(ms)。 */
        public long cdEnd;
        /**
         * 雇佣者（建筑所有者）playerId。
         *
         * <p>1915 的类名是 {@code CCMsgOnePlayeAllBuildingEmployerInfo}（**一个玩家**所有建筑的佣兵），
         * 客户端 {@code UnionManagerSystem.cs:298-327} 把 wjIndex[] 直接填进「我的建筑」格位 ⇒ 格位是
         * **每个玩家自己的**，不是公会共享的一张大表。原实现把 u.employersOf(type) 整表发给所有人，
         * 于是别人的佣兵会出现在我的建筑格位里。</p>
         */
        public int hiredBy;
        /** 该佣兵所在的格位下标（0 基，与 1915/1521 的 geziIndex 对齐）。 */
        public int slotIndex;
    }

    /** 作战室拍卖货架一行：对齐 1928 CCMsgZuoZhanShiPaiMaiItemInfo。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuctionLot {
        public int dropId;
        public String ori = "";
        public int count = 1;
        /** 当前最高价（勇气币）。 */
        public int price;
        public String topBidder = "";
        public int topBidderId;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Member {
        public int playerId;
        public String account = "";
        public int heroIndex;
        public int level;
        public String name = "";
        /** member / elder / owner */
        public String job = "member";
        public int contribution;
        /** 战力；1907 入会申请列表（CMsgUnionRequester.4 FightPower）用。 */
        public int fightPower;
        /** 最近在线时刻(ms)；0 = 未知（下发时按在线/空串处理）。 */
        public long lastOnlineAt;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MinesFile {
        @JsonProperty("_说明")
        public String note = "手改说明: mines[].id=矿点ID（type*10000+page*8+格）. type 1金/2银/3铜. "
                + "holderId=占领者 playerId. occupiedAt/lastCollectAt 毫秒时间戳. "
                + "protectRobUntil/rebuildUntil=保护截止毫秒. npcHolder=假号占位. "
                + "假服每分钟写回，占/撤/领也会立刻写；NPC 一轮(按表可开采时长，多为 12h)结束会换号补位。";
        public List<MineSlot> mines = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"id", "holderId", "account", "name", "level", "occupiedAt", "lastCollectAt",
            "protectRobUntil", "rebuildUntil", "fighting", "npcHolder", "defWj", "coDefs"})
    public static class MineSlot {
        public int id;
        public int holderId;
        public String account = "";
        public String name = "";
        public int level;
        public long occupiedAt;
        public long lastCollectAt;
        /** 成功掠夺保护截止（毫秒）；0=无。 */
        public long protectRobUntil;
        /** 重建保护截止（毫秒）；0=无。 */
        public long rebuildUntil;
        /** 正在被攻打。 */
        public boolean fighting;
        /** 假号占位矿主（一轮结束后可换号）。 */
        public boolean npcHolder;
        /** 本矿防阵 5 槽 GUID（按 KuangID 独立，对齐客户端 mAllQiangKuangDefWuJiang）。 */
        public List<String> defWj = new ArrayList<>();
        /** 协防挂矿（最多 VIP 邀请上限，常见 0–2）。 */
        public List<CoDefender> coDefs = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CoDefender {
        public int playerId;
        public String account = "";
        public String name = "";
        /** 5 槽武将 GUID。 */
        public List<String> wj = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JjcWorld {
        public static final String NOTE =
                "手改说明: 这里只存玩家，以及被打过/换过名次的机器人。5000 个默认机器人在 tables/JJC_Robot.txt，不要往 JSON 抄。"
                        + " 新号从最后一名开始（机器人数量+1），挑战列表只出比你名次高的机器人。"
                        + " kind=robot|player. robot 的 targetGuid=表第 N 行（从 1 计，客户端 GetRobotData(guid-1)），"
                        + "外观和战斗全看客户端那一行，不能按 fightPower 现算。"
                        + " 每周重置后玩家排到机器人后面。lastWeeklyReset 是上次周重置的日期。";

        @JsonProperty("_说明")
        public String note = NOTE;
        public List<JjcSlot> ranks = new ArrayList<>();
        /** 上次周重置对应的那个重置日 yyyy-MM-dd */
        public String lastWeeklyReset = "";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"kind", "targetGuid", "robotTableIndex", "account", "rank", "wins",
            "heroIndex", "level", "name", "fightPower"})
    public static class JjcSlot {
        /** robot 或 player */
        public String kind = "robot";
        public int targetGuid;
        /** 仅 robot：JJC_Robot.txt 0 起下标，targetGuid 应等于 tableIndex+1 */
        public int robotTableIndex;
        public String account = "";
        public int rank;
        public int wins;
        public int heroIndex = 18;
        public int level = 10;
        public String name = "";
        public int fightPower = 1000;
    }

    /** 主城 KFZ 雕像领奖台（S2C 4706）；落盘 data/world/kfz-podium.json。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KfzPodium {
        public List<KfzPodiumSlot> slots = new ArrayList<>();
    }

    /** 全服信息；落盘 data/world/server.json。目前只有开服时间。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ServerInfo {
        public static final String NOTE =
                "手改说明: openServerTime 是全服开服时间（yyyy-MM-dd HH:mm:ss），开服当天算第 1 天。"
                        + " 7 日狂欢/半月庆典等「开服第 N 天」玩法都以它为准，与单个账号的 createdAt 无关。"
                        + " 留空 = 下次启动自动写入当前时间；也可以在 application.yml 用 sao.open-server-time 覆盖（配置优先）。";

        @JsonProperty("_说明")
        public String note = NOTE;
        /** 开服时间 yyyy-MM-dd HH:mm:ss；空 = 首次启动自动写入。 */
        public String openServerTime = "";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"displayRank", "guid", "name", "resId", "level", "serverId", "star", "score"})
    public static class KfzPodiumSlot {
        /** 展示名次 1..3（非 DFZ 淘汰名次）。 */
        public int displayRank;
        public int guid;
        public String name = "";
        public int resId;
        public int level;
        public int serverId = 1;
        public int star;
        public int score;
    }
}
