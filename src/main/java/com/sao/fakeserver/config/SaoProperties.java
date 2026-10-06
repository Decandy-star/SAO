package com.sao.fakeserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sao")
public class SaoProperties {
    private int gamePort = 12345;
    private String publicIp = "127.0.0.1";
    private String serverId = "1";
    private String serverName = "本地假服";
    private String dataDir = "./data/players";
    private String worldDir = "./data/world";
    /** 假服自带表目录（从 GameText 抽出的切片）。迁移时整夹带走。 */
    private String tablesDir = "./tables";
    /** 仅当 tables 缺文件时，尝试从 GameText 再抽一份。 */
    private String gameText = "../Assets/Resources/GameText.txt";
    /** GameText 切分/合并/打 AB 工作区（含 scripts、work、out）。 */
    private String gametextDir = "./gametext";
    /** 打 AB 用的 Unity 编辑器；空则用脚本默认路径。 */
    private String unityExe = "F:\\Unity 4.6.6f2\\Editor\\Unity.exe";
    /** Unity 工程根（含 Assets）；相对假服 cwd 或绝对路径。 */
    private String unityProject = "..";
    private String pythonExe = "python";
    /** false=无档通知创角；true=自动建号跳过选角命名。 */
    private boolean autoCreateRole = false;
    /**
     * 启动/每日调度时是否把当月签到碎片刷进 {@code tables/QianDao.txt}。
     * 关掉只影响「自动 patch」，admin 的 publish/reroll 显式 patch 不受影响。
     * 测试要关：测试各自带独立 world 目录，但 tables 目录是共用的，
     * 启动 patch 会把 live 表刷成测试档的碎片（曾把 SP040 刷成 SP046）。
     */
    private boolean signinPatchTable = true;
    private int initRmb = 5000;
    private int jjcDailyTimes = 5;
    /** JJC_Common「每日竞技场排行榜切磋次数」 */
    private int jjcQieCuoDailyTimes = 10;
    private int defaultWujiangIndex = 18;
    /** 右选(selectRight)初始武将：须与 CreateRoleSetup.txt 右侧(闪光骑士=19)及 NewUserFB_WjAttriCfg 表一致。 */
    private int altWujiangIndex = 19;
    private int mainCityRegionId = 1;
    private float bornX;
    private float bornY;
    private float bornZ;
    private int initSerial = 15;
    private int dungeonPlayerExp = 80;
    private int dungeonWujiangExp = 60;
    private int dungeonGold = 500;
    private int dungeonVpCost = 6;
    private String dropOriName = "SP048";
    private int dropCount = 1;
    /** true 时校验 {@code loginPassword}，并按 {@code loginAccount} 白名单放行账号。 */
    private boolean enforceLogin = true;
    /**
     * 允许登录的账号白名单：逗号分隔可写多个（例 {@code alice,bob}）；
     * {@code *} 或留空 = <b>不限账号</b>（多人联调默认，只校验密码）。
     *
     * <p>改前这里被当成「唯一允许的账号」（默认 admin），第二个账号会被直接断线。
     */
    private String loginAccount = "*";
    private String loginPassword = "1";
    /**
     * HTTP {@code /admin/*} 鉴权 token（与游戏 TCP 无关）。
     * 空字符串 = 拒绝全部 /admin。Header {@code X-Admin-Token} / Bearer / query {@code token}。
     */
    private String adminToken = "";
    /**
     * KFZ 捏造类真人号数量（{@code npc_kfz_0001..}）。启动补齐；已存在不覆盖积分。
     * 建议 200–1000；单号约数十 KB（15 武将+少量装备）。
     */
    private int kfzNpcCount = 800;

    /**
     * 全服开服时间（{@code yyyy-MM-dd HH:mm:ss}，上海时区）。
     *
     * <p>留空 = 由 {@code WorldStore} 在首次启动时写入 {@code data/world/server.json} 并长期沿用；
     * 非空时优先于该文件。7 日狂欢 / 半月庆典等「开服第 N 天」玩法一律以它为准，
     * <b>不能</b>再按单个玩家的 {@code createdAt} 计算。
     */
    private String openServerTime = "";

    public int getGamePort() {
        return gamePort;
    }

    public void setGamePort(int gamePort) {
        this.gamePort = gamePort;
    }

    public String getPublicIp() {
        return publicIp;
    }

    public void setPublicIp(String publicIp) {
        this.publicIp = publicIp;
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getDataDir() {
        return SaoDirs.resolve(dataDir).toString();
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public String getWorldDir() {
        return SaoDirs.resolve(worldDir).toString();
    }

    public void setWorldDir(String worldDir) {
        this.worldDir = worldDir;
    }

    public String getTablesDir() {
        return SaoDirs.resolve(tablesDir).toString();
    }

    public void setTablesDir(String tablesDir) {
        this.tablesDir = tablesDir;
    }

    public boolean isSigninPatchTable() {
        return signinPatchTable;
    }

    public void setSigninPatchTable(boolean signinPatchTable) {
        this.signinPatchTable = signinPatchTable;
    }

    public String getGameText() {
        return gameText;
    }

    public void setGameText(String gameText) {
        this.gameText = gameText;
    }

    public String getGametextDir() {
        return SaoDirs.resolve(gametextDir).toString();
    }

    public void setGametextDir(String gametextDir) {
        this.gametextDir = gametextDir;
    }

    public String getUnityExe() {
        return unityExe;
    }

    public void setUnityExe(String unityExe) {
        this.unityExe = unityExe;
    }

    public String getUnityProject() {
        return SaoDirs.resolve(unityProject).toString();
    }

    public void setUnityProject(String unityProject) {
        this.unityProject = unityProject;
    }

    public String getPythonExe() {
        return pythonExe;
    }

    public void setPythonExe(String pythonExe) {
        this.pythonExe = pythonExe;
    }

    public boolean isAutoCreateRole() {
        return autoCreateRole;
    }

    public int getInitRmb() {
        return initRmb;
    }

    public void setInitRmb(int initRmb) {
        this.initRmb = initRmb;
    }

    public int getJjcDailyTimes() {
        return jjcDailyTimes;
    }

    public void setJjcDailyTimes(int jjcDailyTimes) {
        this.jjcDailyTimes = jjcDailyTimes;
    }

    public int getJjcQieCuoDailyTimes() {
        return jjcQieCuoDailyTimes;
    }

    public void setJjcQieCuoDailyTimes(int jjcQieCuoDailyTimes) {
        this.jjcQieCuoDailyTimes = jjcQieCuoDailyTimes;
    }

    public void setAutoCreateRole(boolean autoCreateRole) {
        this.autoCreateRole = autoCreateRole;
    }

    public int getDefaultWujiangIndex() {
        return defaultWujiangIndex;
    }

    public void setDefaultWujiangIndex(int defaultWujiangIndex) {
        this.defaultWujiangIndex = defaultWujiangIndex;
    }

    public int getAltWujiangIndex() {
        return altWujiangIndex;
    }

    public void setAltWujiangIndex(int altWujiangIndex) {
        this.altWujiangIndex = altWujiangIndex;
    }

    public int getMainCityRegionId() {
        return mainCityRegionId;
    }

    public void setMainCityRegionId(int mainCityRegionId) {
        this.mainCityRegionId = mainCityRegionId;
    }

    public float getBornX() {
        return bornX;
    }

    public void setBornX(float bornX) {
        this.bornX = bornX;
    }

    public float getBornY() {
        return bornY;
    }

    public void setBornY(float bornY) {
        this.bornY = bornY;
    }

    public float getBornZ() {
        return bornZ;
    }

    public void setBornZ(float bornZ) {
        this.bornZ = bornZ;
    }

    public int getInitSerial() {
        return initSerial;
    }

    public void setInitSerial(int initSerial) {
        this.initSerial = initSerial;
    }

    public int getDungeonPlayerExp() {
        return dungeonPlayerExp;
    }

    public void setDungeonPlayerExp(int dungeonPlayerExp) {
        this.dungeonPlayerExp = dungeonPlayerExp;
    }

    public int getDungeonWujiangExp() {
        return dungeonWujiangExp;
    }

    public void setDungeonWujiangExp(int dungeonWujiangExp) {
        this.dungeonWujiangExp = dungeonWujiangExp;
    }

    public int getDungeonGold() {
        return dungeonGold;
    }

    public void setDungeonGold(int dungeonGold) {
        this.dungeonGold = dungeonGold;
    }

    public int getDungeonVpCost() {
        return dungeonVpCost;
    }

    public void setDungeonVpCost(int dungeonVpCost) {
        this.dungeonVpCost = dungeonVpCost;
    }

    public String getDropOriName() {
        return dropOriName;
    }

    public void setDropOriName(String dropOriName) {
        this.dropOriName = dropOriName;
    }

    public int getDropCount() {
        return dropCount;
    }

    public void setDropCount(int dropCount) {
        this.dropCount = dropCount;
    }

    public boolean isEnforceLogin() {
        return enforceLogin;
    }

    public void setEnforceLogin(boolean enforceLogin) {
        this.enforceLogin = enforceLogin;
    }

    public String getLoginAccount() {
        return loginAccount;
    }

    public void setLoginAccount(String loginAccount) {
        this.loginAccount = loginAccount;
    }

    public String getLoginPassword() {
        return loginPassword;
    }

    public void setLoginPassword(String loginPassword) {
        this.loginPassword = loginPassword;
    }

    public String getOpenServerTime() {
        return openServerTime;
    }

    public void setOpenServerTime(String openServerTime) {
        this.openServerTime = openServerTime;
    }

    public String getAdminToken() {
        return adminToken;
    }

    public void setAdminToken(String adminToken) {
        this.adminToken = adminToken;
    }

    public int getKfzNpcCount() {
        return kfzNpcCount;
    }

    public void setKfzNpcCount(int kfzNpcCount) {
        this.kfzNpcCount = kfzNpcCount;
    }
}
