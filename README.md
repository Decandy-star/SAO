# SAO Fake Server

Unity 4.6 还原工程用的 **本地假服**（不是原厂服务器，也不接 360 渠道）。

本目录可以整夹挪走，不依赖 Unity 工程编译。对照源码仍在 Unity 仓库里。

**新对话先读 [docs/CONTINUE.md](docs/CONTINUE.md)**（当前进度、怎么跑、手改哪几个文件）。

## 这个假服做什么

- HTTP：下发选服列表 `QF.xml`、活动/更新公告 `EventNotice.txt` / `Notice.txt`
- TCP：游戏 protobuf 包（12 字节小端包头 + body）
- 内存 + 本地 JSON：账号在 `data/players/{account}.json`，公会/竞技场排行在 `data/world/`（**权威在假服**，不在客户端 `user.cfg`）

## 环境

- JDK 8+（本机已有 `F:\Java\jdk1.8.0_152` 即可）
- Maven 3.8+（本机 `F:\maven\apache-maven-3.9.4`）

```bash
cd sao-fake-server
set JAVA_HOME=F:\Java\jdk1.8.0_152
F:\maven\apache-maven-3.9.4\bin\mvn.cmd test
F:\maven\apache-maven-3.9.4\bin\mvn.cmd package -DskipTests
```

开发：`mvn spring-boot:run`

打成 jar 后，在 **sao-fake-server 目录**（旁边要有 `tables/`，`data/` 会自动建）执行：

```bash
java -jar target\sao-fake-server.jar
```

把 `sao-fake-server.jar` 和 `tables/` 拷到别的目录也可以，`java -jar` 会先看当前目录，再看 jar 旁边。抽卡奖池手改 `tables/gacha-pool.json`，改完重启。

启动后：

| 端口 | 用途 |
|---|---|
| 8080 | HTTP：`QF.xml`、公告、[`/admin/*`](docs/ADMIN.md)（清档 / 发邮件 / grant） |
| 12345 | 游戏 TCP |

选服 XML 示例：`http://127.0.0.1:8080/Other/Android_90/QF.xml`

## 客户端怎么指过来

编辑器里 `LoginSystem` 会把 `ServerListUrl` 指到 `http://127.0.0.1:8080/Other/Android_90/QF.xml`。`GameData/game.cfg` 切片里的默认选服/公告地址也已改成同一主机（后面补空格保持长度）。

进大厅活动公告：`http://127.0.0.1:8080/Other/Android_90/EventNotice.txt`，文案在 [`tables/notices/EventNotice.txt`](tables/notices/EventNotice.txt)。登录更新公告：`Notice.txt`。客户端弹窗暂时关着，打开时改 `PlayGameState` 里 `EN_OPEN_INGAME_NOTICE_UI`。

真机联调把 `application.yml` 的 `sao.public-ip` 改成电脑局域网 IP，手机和电脑同一网段。

抽卡奖池：手改 [`tables/gacha-pool.json`](tables/gacha-pool.json)。价格/免费次数/CD 手改 [`tables/DrawBaoXiangConfig.txt`](tables/DrawBaoXiangConfig.txt)。怎么填见 [tables/README.md](tables/README.md)。改完重启假服。

管理接口（清档 / 发邮件 / 塞背包）详见 [docs/ADMIN.md](docs/ADMIN.md)。

登录默认账密见 `application.yml`（`admin` / `1`）。默认 `sao.auto-create-role=false`：无存档时发 **S2C 1301** 进创角（选左右角色+命名）；设为 `true` 才会首次直接发号跳过创角。

## 文档

| 文件 | 内容 |
|---|---|
| [docs/CONTINUE.md](docs/CONTINUE.md) | **新对话入口**：进度、怎么跑、下一步 |
| [docs/MVP.md](docs/MVP.md) | 已实现 / 明确不做 |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | 包头、消息号、登录与副本时序 |
| [docs/DUNGEON_SIMPLIFY.md](docs/DUNGEON_SIMPLIFY.md) | 主线本/扫荡/宝箱简化点 |
| [docs/ITEMS_AND_TIMERS.md](docs/ITEMS_AND_TIMERS.md) | 物品交互 & 服务器计时核对 |
| [docs/ADMIN.md](docs/ADMIN.md) | **HTTP 管理：清档 / 发邮件 / grant / GameText rebuild** |
| [发布.md](发布.md) | **发布总流程：GameText 切分后日常怎么发、真机热更缺口** |
| [docs/CLOUD_RELEASE.md](docs/CLOUD_RELEASE.md) | **云假服（阿里云+Gitee）发布步骤；非本流程禁止动服务器** |
| [gametext/README.md](gametext/README.md) | GameText 脚本调用说明 |
| [../apk-pipeline/README.md](../apk-pipeline/README.md) | **APK 解包/打包重签工作区（A/B 脚本）** |
| [docs/HERO_REPLACE.md](docs/HERO_REPLACE.md) | 英雄换模/换名/技能排查 |
| [docs/SAVE_FORMAT.md](docs/SAVE_FORMAT.md) | 本地 JSON 字段，方便手改 |
| [tables/README.md](tables/README.md) | 结算表、抽卡价格/奖池怎么填 |

## 配置

见 `src/main/resources/application.yml`。结算表在 **`tables/`**，怎么填见 [tables/README.md](tables/README.md)。搬家带上整个 `sao-fake-server` 即可，不必再带 Unity 工程。手改存档见 [docs/SAVE_FORMAT.md](docs/SAVE_FORMAT.md)。

单机化：带走 `tables/` + `data/players` + `data/world`。

## 挪走之后

把整个 `sao-fake-server` 文件夹复制到任意位置即可。不必改包名。对照文档里的绝对路径指向原来的 Unity 仓库和 `F:\workspace\daojian\tools\build_probe\msg_decomp`。
