# tables/

假服结算用的配置。填法和抽卡说明见本文。新对话总览：[docs/CONTINUE.md](../docs/CONTINUE.md)。

**搬家只带本目录，不必再带 GameText。** 除 `daily-activity.json` 外，改表后都要重启假服。客户端 UI 仍读自己的 `GameData/` 切片，价格 / 机器人外观两边要一致。

缺文件时启动会尝试再从 `sao.game-text` 抽一次并写回这里。`gacha-pool.json`、`daily-activity.json` 不会从 GameText 抽。

重新抽出 GameText 切片：在 `sao-fake-server` 下执行 `python extract_tables.py`。`GameText.txt` 里同名文件会再出现在 `Temp/StringTable`（较短，是文案）。脚本取 **更长** 的那份，才是 `GameData` 配置。

生成抽卡奖池：`python gen_gacha_pool.py`（规则见 [docs/GACHA_PLAN.md](../docs/GACHA_PLAN.md)）。

---

## 目录总表

| 文件 | 假服谁读 | 改完 | 一句话 |
|---|---|---|---|
| `PlayerLevelInfo.ini` | GameTables | 重启 | 账号升级经验、升级加体力 |
| `WuJiangLevelInfo.txt` | GameTables | 重启 | 武将升级经验 |
| `WuJiangBaseAttri.ini` | Cultivate + 生成奖池 | 重启 | 武将定义、碎片名、进阶类型 |
| `WuJiangUpStar.txt` | Cultivate | 重启 | 合成/升星碎片与金币 |
| `WuJiangStarCommonInfo.txt` | Cultivate | 重启 | 武将星级上限（首行） |
| `WuJiangJinJie.txt` | Cultivate | 重启 | 进阶类型：消耗材料与成长 |
| `SkillUpgrade.txt` | Cultivate | 重启 | 技能每级金币 |
| `NewSkillProperty.ini` | Cultivate | 重启 | 主动技能：伤害类型（0普攻/1物理/2法术/3治疗）、基础伤害与成长 |
| `MonsterProperty.ini` | FightConfig | 重启 | 怪 OriName→HP/攻防/技能；`SpawneredMonster` AddSummon 用 |
| `NewSkillBeiDong.ini` | Cultivate | 重启 | 被动技能：作用对象（自身/队友/敌方）与触发几率 |
| `JinJieBookCompose.txt` | Cultivate | 重启 | 进阶书合成配方 |
| `GoodsList.txt` | Economy + Cultivate + 生成奖池 | 重启 | 道具定义（品质、属性、碎片对应武将） |
| `EquipmentList.txt` | Cultivate + 生成奖池 | 重启 | 装备定义（品质、职业槽） |
| `EquipmentCompose.txt` | Cultivate | 重启 | 装备合成配方 |
| `EquipmentUpgrade.txt` | Cultivate | 重启 | 强化金币与暴击倍率 |
| `EquipmentStarUpgrade.txt` | Cultivate | 重启 | 升星系数与固化材料 |
| `EquipmentDeCompose.txt` | Cultivate | 重启 | 拆装备 / 拆图纸返还 |
| `EquipmentCuiLian.txt` | Cultivate | 重启 | 淬炼开启、部件、飞跃消耗 |
| `EquipmentJingLian.txt` | Economy | 重启 | 精炼等级、经验、飞跃属性 |
| `EquipmentReset.txt` | Economy | 重启 | 退星 / 退淬炼钻石与返还 |
| `EquipmentXiLian.txt` | Cultivate `rollXiLianType` | 重启 | 洗练属性种类万分率（GameText 目前仅 1 行） |
| `EquipmentXiLianCommon.txt` | Economy | 重启 | 洗练消耗、正值概率、值域 |
| `EquipTransform.txt` | Economy | 重启 | 指定紫装转橙装 |
| `RegionDropList.txt` | GameTables | 重启 | 关卡金币/经验/必掉/随机掉落 |
| `DrawBaoXiangConfig.txt` | GameTables | 重启 | 抽卡价格、免费次数、CD（**不管出什么**） |
| `gacha-pool.json` | GachaPool / DrawService | 重启 | **正抽出货与权重** |
| `daily-activity.json` | DailyActivityTables | 公共调度按 mtime 重读 | 竞技场日程（JJC 排名邮时刻 / 周重置 / 挑战跨度） |
| `notices/EventNotice.txt` | HTTP | **每次 GET 读盘** | 进大厅活动公告正文 |
| `notices/Notice.txt` | HTTP | **每次 GET 读盘** | 登录更新公告正文 |
| `JJC_Robot.txt` | GameTables | 重启 | 竞技场+BOB 机器人。`targetGuid`=行号。BOB 扩池 5001–5200（lv35–70）；**APK 须 GameText 同步** |
| `JJC_RobotEquips.txt` | GameTables | 重启 | 装备库；扩池含 ID 29–36。改外观/强度两边一起改 |
| `JJC_RankPrize.txt` | GameTables | 重启 | 每日排名邮件：钻 + 竞技积分 |
| `JJC_CDPrice.txt` | GameTables | 重启 | 清 CD 钻价（剩余冷却分钟 0–9） |
| `ShopCommom.txt` | Economy | 重启 | 商店栏位、免费刷新、钻刷新价 |
| `BuyTiLi.txt` | Economy | 重启 | 买体力：第 N 次钻石与兑换量 |
| `BuyJinBi.txt` | Economy | 重启 | 买金币：第 N 次钻石与兑换量 |
| `BuyFBPlayTime.txt` | Economy | 重启 | 买普通副本次数钻价 |
| `BuyJYFBPlayTime.txt` | Economy | 重启 | 买精英副本次数钻价 |
| `UserPayGoods.txt` | Economy | 重启 | 充值档：RMB→钻石（假服不真扣钱） |
| `UserPayGoods_1st.txt` | **假服不读** | — | 客户端首充档展示 |
| `ExchangeShop.txt` | Economy | 重启 | 兑换：万能碎片 + 魔法尘 |
| `ChapterBaoXiang.txt` | Economy | 重启 | 章节星宝箱奖励 |
| `QiangKuang_Common.txt` | Economy | 重启 | 抢矿公共参数 |
| `UnionMaJiuBase.txt` | Economy | 重启 | 运镖公共参数 |
| `VipCfg.txt` | Economy | 重启 | VIP 累计钻石门槛、发镖/劫镖次数 |
| `UnionBuildingLevelUp.txt` | Economy | 重启 | 公会建筑等级、酬劳/小时 |
| `TeQuanCard.txt` | Economy | 重启 | 月卡/至尊：立即钻石、每日钻石、天数 |
| `TimeStoneChangeColour.txt` | Economy | 重启 | 时光石改色：等级 → 金币/钻石 |
| `DailyTaskConfig.txt` | TaskTables | 重启 | 日常任务：类型、次数、奖励 |
| `OnceTaskConfig.txt` | TaskTables | 重启 | 一次性任务：前置、类型、customParam=关卡 ID |
| `Union.txt` | Economy | 重启 | 公会常数；假服读「每日挑战boss次数」 |
| `GlobalSetup_CH.txt` | Cultivate | 重启 | 全局开关与技能点购买 |

---

## 抽卡两层（先分清）

一次抽卡会发两层东西，不要混：

| 层 | 配哪里 | 干什么 | 有没有概率 |
|---|---|---|---|
| **正抽** | `gacha-pool.json` | 单抽 1 次 / 十连 10 次，每次从金币池或钻石池里按权重抽 1 条 | 有。`weight` 相对权重 |
| **额外碎片** | `DrawBaoXiangConfig.txt` 第 6～10 行 | 钻石抽结束后再额外塞一种碎片，UI 显示「意外获得」 | **没有抽哪种的概率**。开启后必给，数量在 min～max 均匀随机 |

金币抽没有额外碎片。十连也不会免费。

---

## DrawBaoXiangConfig.txt

只管价格、免费次数、CD、钻石额外碎片。**不管正抽出什么**。

假服：`GameTables.parseDraw`。客户端酒馆 UI 读 Unity `GameData/DrawBaoXiangConfig.txt`，两边建议改成一样。

### 格式

有效行必须是 **3 列**：`#` + 说明 + 值，列之间用 Tab 或空格。前两行表头没有 `#`，会被丢掉。

- **按行顺序读**，不要插行、不要换行序
- 说明里不要有空格，否则会拆成 4 列被跳过，后面全部错位
- 碎片 OriName 不能带空格，不能写中文，不能写逗号列表

```
#	系统开启等级	2
#	金币单抽价格	10000
```

### 各行含义

| 行（按顺序） | 含义 | 原厂值 | 怎么填 |
|---|---|---|---|
| 系统开启等级 | 几级才能开酒馆 UI | `2` | 整数。假服不拦，客户端拦 |
| 金币单抽价格 | 花金币单抽 | `10000` | 整数金币 |
| 金币十连抽价格 | 花金币十连 | `100000` | 整数。原厂是 10 倍，可改折扣 |
| 钻石单抽价格 | 花钻石单抽 | `265` | 整数钻石 |
| 钻石十连抽价格 | 花钻石十连原价 | `2650` | 整数。下面三行可另打折 |
| 钻石抽额外碎片物品原始名 | 钻石抽额外再给**一种**碎片 | `0` | 单个道具 OriName。`0` = 关闭 |
| 单抽获取碎片最小数 / 最大数 | 钻石单抽额外碎片数量 | `0` / `0` | 整数。名为 `0` 或抽到数量为 0 就不给 |
| 十连抽获取碎片最小数 / 最大数 | 钻石十连额外碎片数量 | `0` / `0` | 在 min～max **含两端**均匀随机 |
| 金币单抽免费次数 | 每天免费金币单抽次数 | `5` | 整数。用完当天只能花钱 |
| 免费金币单抽冷却 CD（分钟） | 两次免费金币抽间隔 | `5` | **分钟**。`5` = 5 分钟 |
| 免费钻石单抽冷却 CD（分钟） | 两次免费钻石抽间隔 | `2880` | **分钟**。`2880` = 48 小时 |

钻石免费是单独 1 次，不走「金币免费次数」那一行。

### 钻石十连原价 / 折扣（假服扩展行）

客户端 Unity `GameData/DrawBaoXiangConfig.txt` 只读前 14 行。假服在同表**末尾**再读 3 行，改完重启。说明里不要空格。

| 行 | 含义 | 默认 | 怎么填 |
|---|---|---|---|
| 钻石十连折扣倍率 | 常驻十连实扣 = 原价 × 倍率 | `1` | `1` = 原价。`0.8` = 八折。≤0 当 1 |
| 首次钻石十连折扣倍率 | 前 N 次十连用这个倍率 | `1` | 与上一行相同则没有「首次优惠」 |
| 首次钻石十连次数 | 前几次走首次倍率 | `0` | `0` = 不用首次档。`1` = 第一次十连打折，之后回常驻 |

进游戏 `CMsgDetailPlayerInfo` field **66** 带 `ZSDrawBaoXiangPriceInfo`（原价 / 现价 / 倍率）。钻石十连成功后再推 **S2C 2403**（首次折扣用完后 UI 会去掉打折标）。扣费按现价，和客户端展示一致。

原价（默认，现在表就是这样）：

```
#	钻石十连折扣倍率	1
#	首次钻石十连折扣倍率	1
#	首次钻石十连次数	0
```

第一次十连半价，之后原价：

```
#	钻石十连折扣倍率	1
#	首次钻石十连折扣倍率	0.5
#	首次钻石十连次数	1
```

一直八折：

```
#	钻石十连折扣倍率	0.8
#	首次钻石十连折扣倍率	0.8
#	首次钻石十连次数	0
```

想清掉「已经用过首次折扣」：把存档 `gacha.diamondTenCount` 改回 `0`。

### 钻石抽额外碎片物品原始名（`SP048` 从哪来）

这不是协议字段名，是 **道具表 OriName**。去 `GoodsList.txt` 里找，常见武将碎片：

| OriName | 显示名 | 对应武将 index |
|---|---|---|
| `GOODS3` | 桐人碎片 | 18 |
| `GOODS110` | ALO亚丝娜碎片 | 28 |
| `SP048` | 鼠妹碎片 | 48 |

文档和旧示例奖池常拿 `SP048` 当例子，原厂这行填的是 **`0`（关闭）**。客户端会读进 `mZSAddSuiPianGoods`，但展示「意外获得」用的是服务器回包 `addSuiPian[0]`，真正发不发、发哪种以假服本表为准。

**只能填一个 OriName。** 不要写 `SP048,GOODS3`、不要空格分隔多个。整段会当成一个不存在的道具名，图标/名字都会空。

想抽多种东西：改 `gacha-pool.json` 的 `gold` / `diamond` 列表，用 `weight` 分配概率。额外碎片这行永远是「钻石抽再白送一种」。规划奖池要求这行保持 `0`。

### 额外碎片数量 / 概率

开启条件（同时满足才发）：

1. 这次是 **钻石抽**（C2S 2202）
2. 原始名不是 `0`、不是空
3. 抽到的数量 `> 0`

数量公式（假服 `DrawService`）：

```text
count = min + random(0 .. max-min)     // max>=min 时含两端，每个整数等概率
count = min                            // max<=min 时固定给 min
```

例：min=1、max=3 → 1 / 2 / 3 各 1/3。min=max=2 → 必给 2。min=max=0 → 不给。

没有「额外碎片出现率」。只要上面 3 条成立，**100% 再给一次**，和正抽结果无关（正抽已抽到同名碎片也会再加）。

十连也只额外给 **一次**（按十连 min～max），不是抽 10 次各给一次。

示例（开启鼠妹碎片）：

```
#	钻石抽额外碎片物品原始名	SP048
#	单抽获取碎片最小数	1
#	单抽获取碎片最大数	3
#	十连抽获取碎片最小数	8
#	十连抽获取碎片最大数	12
```

### 免费次数与 CD（假服怎么走）

表里填的是 **分钟**。假服读入后 `×60` 变成秒再比。客户端 UI 倒计时同样是表值 `×60`。

存档：`data/players/{账号}.json` 的 `gacha`：

| 字段 | 含义 |
|---|---|
| `lastGoldFreeAt` | 上次免费金币单抽时间 `yyyy-MM-dd HH:mm:ss`。空 = 还没用过，立刻可免 |
| `lastDiamondFreeAt` | 上次免费钻石单抽时间。空 = 立刻可免 |
| `goldFreeLeft` | 当天还剩几次免费金币单抽 |
| `diamondTenCount` | 钻石十连次数（折扣计数，与免费无关） |
| `diamondOnceCount` | 钻石（黄金宝箱）单抽次数。`0` = 下一次单抽必出猫妖诗乃（index 31） |

**金币免费（只对单抽）：**

1. **登录 / `ensureDaily` / 上海 5:00 cron**：按 `lastGoldFreeAt` 的**游戏日**（时钟−5 小时）刷新 `goldFreeLeft`。对齐 APK 次数用尽倒计到次日 **05:00**。客户端只认下发的 `freeJinBiDrawTimeLeft` / S2C 2402
2. 每次金币抽前再兜底一次（同一套 `refreshGachaGoldFreeDaily`）
3. 单抽 且 `goldFreeLeft > 0` 且距 `lastGoldFreeAt` 已满 CD → 免金币，`goldFreeLeft--`，记下当前时间
4. 否则扣「金币单抽/十连价格」。次数用完或 CD 没到，仍可花钱抽

**钻石免费（只对单抽，没有次数行）：**

1. 单抽 且距 `lastDiamondFreeAt` 已满 CD（或时间为空）→ 免钻石，记下当前时间
2. 否则扣钻石。十连按 field 66 现价扣费，并 `diamondTenCount++`，再推 S2C 2403

账号**第一次钻石单抽**（免费或付费都算）必出猫妖诗乃，然后 `diamondOnceCount++`。十连不走这条。想立刻再测首次必得：把 `diamondOnceCount` 改回 `0`。

CD 没到时付费抽 **不会**刷新免费时间戳。想立刻再免：把对应时间戳改成空串，或把时间改到足够早。

客户端倒计时读的是 Unity 自己那份表。假服 CD 已过、客户端表还是 2880 分钟时，UI 仍可能显示冷却，但点下去假服会按自己的表结算。

---

## gacha-pool.json

正抽出货。**不要手填**，改 `GoodsList` / `EquipmentList` / `WuJiangBaseAttri` 后在 `sao-fake-server/` 跑 `python gen_gacha_pool.py`，规则见 [docs/GACHA_PLAN.md](../docs/GACHA_PLAN.md)。改完重启假服。

当前已生成（等概率，`weight=1`）：

| 池 | C2S | 出什么 | 条数 |
|---|---|---|---|
| `gold` | 2201 | 品质≤4 装备碎片 + 全部魂石碎片 | 60 |
| `diamond` | 2202 | 品质≥3 完整装备（跳过不显示）+ 全部英雄 | 94 |

```json
{ "type": "item", "ori": "ZBSP1", "count": 1, "weight": 1 }
{ "type": "equip", "ori": "EQ0023", "weight": 1 }
{ "type": "hero", "heroIndex": 18, "fragmentOri": "GOODS3", "weight": 1 }
```

| 字段 | 含义 |
|---|---|
| `gold` / `diamond` | 金币池 / 钻石池 |
| `type` | `item` 道具，`equip` 完整装备，`hero` 武将 |
| `ori` / `count` | 道具原始名和数量（`item`）；装备原始名（`equip`） |
| `heroIndex` | 武将表 ID。18 桐人，28 ALO亚丝娜 |
| `fragmentOri` | 已拥有该武将时转成碎片的道具名（须是该武将自己的碎片）。**数量不在池里写**，由 `WuJiangUpStar` 按该武将合成初始星级折算 |
| `weight` | 相对权重。规划池全是 1 |

### 正抽怎么抽

单抽 `roll` 1 次，十连 `roll` 10 次，每次独立：

1. 取对应池，`total = Σ max(0, weight)`
2. 在 `[0, total)` 里均匀随机一个数，落到哪条就出哪条
3. 概率 = `该条 weight / total`。规划池全是 1，所以金币每条 1/60，钻石每条 1/94

命中后（都会写入账号 JSON）：

- `item`：进背包 `ori × count`，S2C 301
- `equip`：发一件装备（S2C 1406），结果 `type=1` 带 GUID
- `hero` 且号上还没有：给整卡（S2C 1204），星级=合成初始星级
- `hero` 且已拥有：改发 `fragmentOri × 折算数`（`WuJiangUpStar` 第三段，按合成初始星级），进背包

钻石抽「额外碎片」保持表里 `0`，不要再开。

---

## daily-activity.json

假服自有配置，**不是** GameText 切片。**只配竞技场日程**，不含月卡返钻 / 通用活动邮。

改完不必重启：公共调度启动补跑 / 每日 0 点 / 发邮钟点闸门会按 mtime 重读。

| 段 | 作用 |
|---|---|
| `jjcRankMail` | 每天 `hour:minute` 按当前竞技场名次查 `JJC_RankPrize.txt`，发系统邮件 mailType=1（钻石 + 竞技积分） |
| `jjcWeeklyReset` | 每周把玩家名次打回机器人队列之后。默认周一 0 点。`weekday` 1=周一 … 7=周日 |
| `jjcChallengeMaxAbove` | 挑战列表最多比自己高多少名。默认 50 |

防重复键写在账号 `economy.mailGrantKeys`（如 `2026-09-27/jjc-rank`）。

---

## PlayerLevelInfo.ini

账号等级表。假服：`GameTables.parsePlayerLevel`。

有效行 `#` 开头。列：角色等级、到下一级经验、精力常规上限、到下一级的精力增加值。

假服用到的：

- 列 1～2：当前级升下一级要多少经验（通关、吃经验等结算）
- 列 4：刚升到这一级时加多少体力

列 3「精力常规上限」客户端 UI 用；假服体力上限另走 `GlobalSetup_CH.txt` 的「玩家体力上限值」一类逻辑，本表这一列当前不读。

少动。改经验曲线会影响所有号升级节奏。

---

## WuJiangLevelInfo.txt

武将等级经验表。假服：`GameTables.parseWjLevel`。

列：等级、到下级经验。吃经验食、关卡给的武将经验，都按这张表扣到下一级。

和 `PlayerLevelInfo.ini` 是两套等级，不要混。

---

## WuJiangBaseAttri.ini

武将主表。假服：`CultivateTables.parseHeroes`；`gen_gacha_pool.py` 用它填钻石池英雄。

表头很长。假服用到的关键列：

| 列（从 0 计，`#` 为 0） | 含义 |
|---|---|
| 1 | 武将 ID（`heroIndex`） |
| 8 | 武将碎片原始名（桐人是 `GOODS3`，不是 `SP048`） |
| 9 | 合成初始星级 |
| 47～50 | 进阶 1～4 类型，对应 `WuJiangJinJie.txt` 的类型 ID |

说明：ID≥1000 的角色不在角色列表显示。客户端还用本表做模型、技能、站位、品质等；假服合成/升星只认 ID 和碎片名。

钻石奖池「全部英雄」= 本表所有可解析行。改碎片名后必须重跑 `gen_gacha_pool.py`，否则已拥有转碎片会对不上。

---

## WuJiangUpStar.txt

武将合成与升星消耗。假服：`CultivateTables.parseUpStar`。

前半：合成 1 星、1→2 …… 9→10 所需**碎片数量**。  
后半：对应金币。  
再往后：各星英雄折算成碎片数（重复抽到已有英雄时用）。客户端 `GetChaiJieStarBySuiPianCount` 只认表内精确值。假服按该武将 **合成初始星级**（`WuJiangBaseAttri` 第 10 列）取对应折算数，例如 1→8、2→15、3→30、4→50。

改这里会直接影响合成/升星扣材料。

---

## WuJiangStarCommonInfo.txt

客户端为主：升星特效、星图标图集。假服只读**第一行第一个正整数**当武将星级上限（当前 `10`）。其余行假服忽略。

不要把特效行改成数字，否则上限会读错。

---

## WuJiangJinJie.txt

武将进阶类型表。假服：`CultivateTables.parseJinJie`。

一行一种进阶类型。前面是属性加成（攻击、双防、暴击等），假服当前按客户端表结算消耗；材料从「【白】所需物品」起，每档：原始名、数量、单次成长比、进阶成长比，共白 / 绿 / 绿+1 / 蓝 / 蓝+1 / 紫 / 紫+1～紫+4。

某个武将走哪几条类型，看 `WuJiangBaseAttri.ini` 的进阶 1～4 类型列。不需要的材料填 `0`。

---

## SkillUpgrade.txt

技能升级金币。假服：`CultivateTables.parseSkillUpgrade`。

列：等级、名将和无双、技能 A、技能 B、被动技能。  
升到该级时按技能种类扣对应列的金币。

---

## JinJieBookCompose.txt

进阶书合成。假服：`CultivateTables.parseBookCompose`。

列：合成产物原始名、金币、材料 1～4 的原始名与数量。不需要的材料填 `0`。

例：3 个 `GOODS184` 合成 1 个 `GOODS66`。产物必须能在 `GoodsList.txt` 里找到。

---

## GoodsList.txt

全道具表。假服：`EconomyTables.parseGoods`、`CultivateTables.parseGoods`；金币奖池也从这里筛。

关键列：

| 列 | 含义 |
|---|---|
| 原始名 | `GOODS*` / `SP*` / `ZBSP*` / `ZBSX*` 等，协议和奖池都用这个 |
| 显示名 / 图标 | 客户端 UI |
| 品质 | 1 白～5 金。金币奖池装备碎片要求品质≤4 |
| 道具显示位置 | 1 其他 / 2 魂石 / 3 装备 / 4 进阶 / 5 时光 / 6 宝箱 |
| 道具属性 | 见下表 |
| 属性自定义参数 1 | 武将碎片 = 武将 Index；装备图纸 = 装备原始名；经验食 = 经验值 |

道具属性：

| 值 | 类型 | 假服/奖池 |
|---|---|---|
| 1 | 武将经验食 | 养成吃经验 |
| 2 | 武将碎片（魂石） | 合成升星；**金币奖池全部收入** |
| 3 | 装备碎片（图纸） | 合成装备；**金币奖池品质≤4** |
| 4 | 装备材料（升星石等） | 升星/淬炼消耗 |
| 6 | 可出售包装金币 | 商店自动售卖 |
| 9 | 进阶书碎片 | 进阶书合成 |
| 10 | 宝箱 | 打开走客户端规则 |
| 13 | 精炼经验道具 | 精炼 |

改品质或属性后，若影响奖池筛选，重跑 `gen_gacha_pool.py`。

---

## EquipmentList.txt

装备定义。假服：`CultivateTables.parseEquipList`；钻石奖池筛品质≥3 且「是否不显示在装备查找中」≠1。

关键列：原始名（`EQ*`）、显示名、品质、金币价格、装备类型（1 武器 / 2 配件 / 3 饰品 / 4 翅膀 / 5 勋章）、装备职业类型（1～8 对应单手～防护水晶）、基础属性与成长、是否不显示。

完整装备**不是**道具，不能写进背包 `ori`。抽中走 `type=equip`，发一件 0 级 0 星未穿的 `CEquipmentInfo`。

白/绿装备原厂直接给整件，没有对应图纸，所以金币池没有白绿碎片。

---

## EquipmentCompose.txt

装备合成配方。假服：`CultivateTables.parseEquipCompose`。抽卡 `type=equip` 发装备时不扣这张表；玩家点合成才扣。

列：装备原始名、金币、最多 4 组材料（原始名+数量）。蓝装一般 20 个对应 `ZBSP*`，紫装 50 个。不需要的填 `0`。

---

## EquipmentUpgrade.txt

装备强化。假服：`CultivateTables.parseEquipUpgrade`。

上半：金币基础值、白～金品质系数、强化暴击万分率（七档加起来应为 10000）。  
下半 `#` 行：强化等级 → 该级金币增量。

假服按品质系数 × 等级行扣金币，并按暴击万分率决定一次升几级。

---

## EquipmentStarUpgrade.txt

装备升星。假服：`CultivateTables.parseEquipStar`。

两段：

1. 0 星～10 星成长系数（客户端算战力；假服认星级上限与消耗）
2. 固化消耗：品质、当前星级、金币、通用材料名与数量（紫/金升星吃 `ZBSX01` 等）

---

## EquipmentDeCompose.txt

拆解返还。假服：`CultivateTables.parseDecompose`。

- `#` 行：按品质+星级拆**整件装备**，返还金币、万能碎片、最多 5 种物品
- `*` 行：按品质拆**装备碎片/图纸**，主要返还万能碎片

不需要的列填 `0`。

---

## EquipmentCuiLian.txt

淬炼。假服：`CultivateTables.parseCuiLian`。按 `#` 行顺序读，不要插段。

1. 开启淬炼属性提升系数
2. 开启淬炼消耗（金币、需求装备数量、材料）
3. 淬炼消耗（星级 0～4：金币 + 材料）
4. 飞跃消耗（飞跃星级 1～5）

材料一般是 `ZBSX01`～`ZBSX03`。

---

## EquipmentJingLian.txt

精炼 / 飞跃。假服：`EconomyTables.parseJingLian`。

- 前段 `#`：装备类型 → 提升哪两条属性，直到标记 `JingLianAttrEnd`
- 后段：淬炼等级、品质、经验、各部位飞跃属性串、等级限制、金币、最多 4 组材料

飞跃属性串形如 `1_1_17.28`（属性 ID_类型_数值）。假服扣费与加经验按后段；具体属性展示仍以客户端为准。

---

## EquipmentReset.txt

退星 / 退淬炼。假服：`EconomyTables.parseReset`。

- 退星：品质+星级 → 钻石消耗、装备返还万分率、材料返还万分率
- `ResetStarEnd` 之后是退淬炼配置

`基础消耗` 行是保底钻石。返还百分比按万分率（`10000` = 100%）。

---

## EquipmentXiLian.txt

洗练**可随到哪些属性、各属性万分率、阶段系数**。这是客户端表。

假服洗练消耗和随机值域走下一张 `EquipmentXiLianCommon.txt`；属性种类万分率本文件由 `CultivateTables.parseEquipXiLian` 读取。

GameText 切片目前只有 **1** 条 `#` 行（类型 1 / 品质 3）。无匹配行时先同类型再首行，再 0–8 均匀。APK 无装备洗练入口。

阶段系数、属性 baseVal、Common「装备等级区分范围」：客户端只读进字段，**工程内无公式**，假服不用于掷值。

---

## EquipmentXiLianCommon.txt

洗练公共参数。假服：`EconomyTables.parseXiLian`。键值表，不是 `#` 行。

假服当前读取：洗练石、三种消耗、值域绝对值、三种「出现正值概率」万分率。

「装备等级区分范围」已读入 `levelRange`，**无 APK 公式**，不参与掷值。

---

## EquipTransform.txt

指定紫装转橙装。假服：`EconomyTables.parseTransform`。

列：待转换紫装原始名、金币、最多 4 组材料、生成橙色装备原始名。  
例：`EQ0043` + `GOODS259`×10 + 10 万金 → `EQ0098`。

不是任意紫转橙，只认表里列出的几对。

---

## RegionDropList.txt

关卡掉落。假服：`GameTables.parseDrop`。键 = `场景ID × 10 + 难度`。难度除精英副本为 2 外都配 1。

假服结算会用到：金币、主玩家经验、武将经验、首次通关必掉物品×3、首次万能碎片/英魄、10 个随机物品（原始名+数量+万分率）、特殊掉落、英魄/万能碎片的数量与概率。

表头还说明：20+ 镜像挑战、30+ 死亡躲避等活动关。主产出类型列给客户端「掉落显示」用。

改某关掉落只影响假服发奖；客户端关卡 UI 仍读自己的切片。

---

## JJC_Robot.txt

竞技场机器人名单。假服：`GameTables.parseRobots`。

`Index` 从 1 开始，既是表行号，也是该机器人在排行榜上的**初始名次**，也是协议里的 `targetGuid`。客户端 `GetRobotData(guid-1)` 用这一行画模型和打战斗。

假服匹配：名次窗口内有谁挑谁（机器人或玩家）。`guid>1e6` 开战暂拒（PVP 只差防守阵序列化进包，战斗公式相同）。新号排在机器人行数 + 1（末位）。

假服结算只读：名字、角色等级、资源 ID、胜场。阵容、技能等级、装备库 ID 给客户端战斗用。

想重新从末位爬：删 `data/world/jjc.json` 里自己那条。本表须与客户端同份，否则模型和结算对不上。

---

## JJC_RobotEquips.txt

机器人装备库。列：库 ID、装备等级/星级/固化、各职业槽装备原始名（单手、双手、轻型、重型、盾、甲、离子盾、水晶、翅膀、饰品、荣耀）。

`JJC_Robot.txt` 每行的「装备库 ID」指向这里。假服抽出但不解析；战斗在客户端按这张表穿装备。改外观两边一起改。

---

## JJC_RankPrize.txt

竞技场每日排名邮件。假服：`GameTables.parseJjcRankPrize`，由 `daily-activity.json` 的 `jjcRankMail` 在指定时刻触发。

列：排名低位、排名高位、RMB（钻石）、金币、积分、物品 1/2。当前表金币和物品都是 0，只发钻石 + 竞技积分。

25 档，例如第 1 名 350 钻 + 1200 积分，4001～5000 名 40 钻 + 200 积分。表外更差名次用最后一档。

积分不是每场挑战加上去的，只在这封邮件领取时入账。

---

## JJC_CDPrice.txt

清挑战 CD 的钻石价格。假服：`GameTables.jjcClearCdPrice`。

每行一档，行序 = 剩余冷却**整分钟** 0～9（与客户端 `GetClearPrice` 一致：`(int)(10 - 已过分钟)`，≥10 钳到 9）。例如刚进 CD≈40 钻，剩不足 1 分钟≈4 钻。

---

## ShopCommom.txt

商店公共配置。文件名原厂就少一个 `n`（不是 Common）。假服：`EconomyTables.parseShop`。

假服用：商店类型 ID、开启等级、每日免费刷新次数、栏位数量、RMB 刷新参数（钻价）。

刷新时刻、气泡文案给客户端。类型：1 百货、3 竞技、4 公会、5 英魄；类型 2 已作废。

具体货架商品不在这张表（另有商店货物表，假服若未抽则用代码/存档逻辑）。

---

## BuyTiLi.txt

钻石买体力。假服：`EconomyTables.parseBuy`。

列：今日第几次、钻石消耗、体力兑换量。次数越多越贵，每次兑换量当前都是 150。超出表行则按最后一行或拒绝（以代码为准）。

按自然日计数，存在账号当日购买次数里。

---

## BuyJinBi.txt

钻石买金币。列：第几次、钻石消耗、金币兑换量。结构同买体力，行数更多、单价更低。

---

## BuyFBPlayTime.txt

钻石买**普通副本**挑战次数。列：今日第几次、钻石消耗。没有「兑换量」列，一次就是 +1 次挑战。

---

## BuyJYFBPlayTime.txt

钻石买**精英副本**挑战次数。列与 `BuyFBPlayTime.txt` 相同，价格曲线当前也相同，但是两张独立表、两套当日计数，改一边不会影响另一边。

---

## UserPayGoods.txt

充值商品。假服：`EconomyTables.parsePay`。假服不接支付渠道，点充值按本表直接加钻石（含赠送）。

列：ID、类型、描述、充值金额、图标、常规钻石、常规赠送、限次赠送、限次次数、推荐。

钱包加钻 = 常规钻石 + 常规赠送 +（首次且限次次数>0 时的限次赠送）。  
**VIP 累计**只加「常规钻石」列，不加赠送。登录 dump field 15 / 充值后 S2C 501 type=10。

类型：1 买钻石、2 月卡、3 季卡、4 至尊卡。假服点档直接加钻。月卡/至尊**不计入** `chargedDiamond`；立即钻石与每日钻石读 `TeQuanCard.txt`。每天登录（或点 3501/3502）补当日钻。

---

## VipCfg.txt

客户端 `VipManager` 也读这份。假服：`EconomyTables.parseVip`。

VIP 等级**不单独存**。用账号 `economy.chargedDiamond`（累计充值钻石，不含赠送）从高到低对「累计钻石数量」列，第一档 `<= 当前累计` 就是等级。和客户端 `ReCalVIPLevel` 同一套。

| VIP | 累计钻石 |
|---|---|
| 0 | 0 |
| 1 | 60 |
| 2 | 300 |
| 3 | 600 |
| 15 | 200000 |

手改存档把 `chargedDiamond` 改成对应值，重新登录即可。充值当下也会推 type=10，大厅 VIP 数字不用重登。

假服目前按表读出发镖数量、劫镖次数，给马厩每日次数。其它特权（买体上限、十连扫荡等）客户端自己按等级读表。

---

## UserPayGoods_1st.txt

客户端**首充**档位展示（描述里带「首冲再送」）。假服 **不解析**，只抽出对照。假服实际加钻读 `UserPayGoods.txt`。

若要首充双倍，应在假服充值逻辑或 `UserPayGoods.txt` 的限次赠送列处理，不要以为改本文件会生效。

---

## ExchangeShop.txt

兑换商店。假服：`EconomyTables.parseExchange`。

列：物品原始名、数量、所属页签、万能碎片价、魔法尘价、需求等级。

页签 1/2 主要是蓝/紫装备碎片（只花万能碎片）；页签 3 起含金色碎片和 `GOODS259` 等，会同时要魔法尘。等级不够假服会拒兑。

---

## ChapterBaoXiang.txt

章节星数宝箱。假服：`EconomyTables.parseChest`。键 = `章节×1000 + 宝箱等级`。

宝箱等级位标志（可组合）：

| 值 | 含义 |
|---|---|
| 1 | 普通 10 星 |
| 2 | 普通 20 星 |
| 4 | 普通 30 星 |
| 8 | 精英 10 星 |
| 16 | 精英 20 星 |
| 32 | 精英 30 星 |

奖励：钻石、金币、最多 5 组物品。领过的箱子记在账号章节进度里，不能重复领。

---

## QiangKuang_Common.txt

抢矿公共常数。假服：`EconomyTables.parseMine`。键值表。

假服读取：买征战水晶的钻石与数量、英雄买活钻石、金/银/铜矿单次掠夺金币与钻石上限、占领/掠夺消耗的征战水晶。

表里还有开放等级、保护时间、复活时间等，客户端或未接线的玩法会用；改上限会影响一次能抢多少。

---

## UnionMaJiuBase.txt

公会运镖公共常数。假服：`EconomyTables.parseMajiu`。键值表。

假服当前读取：

- `单次活动有效掠夺场次（不管成功还是失败）`（默认 3，每天重置，劫镖用）
- `发镖参与的兄弟币奖励（发镖参与奖）`（默认 100，**领奖时**才给）

押镖时长假服写死 **2 小时**（`UnionService.ESCORT_MS`），用服务器本机时间。每天发车次数按 VIP0「发镖数量」= **1**。在途只能有一车。

刷新次数、各级公会晶石、劫镖比例、排行奖励串等仍以客户端 / 未接线逻辑为准。

---

## GlobalSetup_CH.txt

客户端全局开关与常数（引导、出生点、体力回复秒数、副本失败扣体等）。假服：`CultivateTables.parseGlobalSetup`，按**非空 token 顺序**取值，不是按中文键名。

假服用来买技能点：名将/技能 A/B/被动每次消耗的技能点、技能点上限、买技能点的钻石与一次买几个。

不要在表中间插行或删空列，token 下标会错位，技能点价格会读成别的数字。体力回复等仍以客户端为准。
