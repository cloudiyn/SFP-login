velocity / 主插件 (SFP-login 核心)
===================================

目的
----
`velocity` 是 SFP-login 的主插件/核心模块，负责：
- 档案（Profile）管理（注册、查找与绑定）；
- 提供主运行时的 API（例如 `SFPLoginMain`、数据库管理、命令/事件集成点）给其他子模块使用；
- 提供基础数据库接口与部分工具（如离线 UUID 识别与 profile 表定义）。

集成与模块注册
----------------
- 子模块应通过主插件暴露的 API 注册自己：`HyperZoneApiProvider.get().registerModule(module)`（`module` 为 `HyperSubModule` 实例）。
  2.0.0 起不再有 `SFPLoginMain.getInstance()` / `SFPLoginMain.getCoreConfig()` 这类伴生静态访问器，运行时上下文改为 `cn.starfallplain.sfplogin.runtimeContext()` / `coreConfig()` / `sfpLoginMain()`；外部模块必须用新包名 `cn.starfallplain.sfplogin.api` 重新编译。
- 主插件在启动时会注册若干内置子模块（如 `OfflineSubModule`、`YggdrasilSubModule`、`MergeSubModule` 等）；外部模块也可以在运行时检测到主插件并主动调用 `registerModule` 完成集成。

主要技术细节
-------------
- API & Provider：
  - 核心提供 `HyperZoneDatabaseManager`、`HyperZonePlayerAccessorProvider`、`HyperChatCommandManagerProvider` 等接口供子模块使用；
  - 提供 `ProfileTable` 等数据库表定义，子模块可借此创建或查询与 profile 相关的数据。
- 命令：
  - 内置命令实现 `HyperZoneLoginCommand`，支持如下用例（示例）：
	- `reload`：重载配置/状态（输出 `Reloaded!`）。
  - `re`：对玩家触发重新认证（仅玩家可执行，会调用当前等待区适配器的 `reJoin(...)`）。
	- `uuid`：显示代理 Player 与 HyperZonePlayer 的信息（包括 profile、uuid 等）。
    - 管理命令通过 Brigadier 子命令节点上的权限检查进行限制：`reload` 与 `uuid` 需要 `hyperzonelogin.admin`，`re` 允许普通玩家使用。
- 事件与表管理：
  - 主插件负责触发表结构事件（`TableSchemaEvent`）以便子模块统一创建/删除表结构；
  - 子模块可以监听并响应这些事件进行表的创建或清理。
- 运行库加载：
  - 主插件入口已切换为 Java bootstrap，以便在没有 `MCKotlin-Velocity` 的情况下先下载 Kotlin 运行时；
  - 主插件产物现为普通 `jar`，仅额外并入 `api` 模块输出；第三方运行库不再随主插件一起打包；
  - 主插件会在 `onEnable` 前动态下载并注入 Configurate、Exposed、JDBC 驱动、连接池等运行库；
  - 对需要隔离包名的运行库，会先按原始 Maven 坐标下载到本地缓存，再在注入类路径前重写为 relocated jar；当前主用例是 `bStats`，用于避免与其他插件的 `org.bstats` 包冲突；
  - 同时也会下载 `mckotlin-velocity` 原先提供的 Kotlin StdLib / Kotlin Reflect / KotlinX Coroutines；
  - 运行库缓存目录为 `plugins/sfplogin/libs/`；
  - `auth-offline`、`auth-yggd`、`profile-skin`、`data-merge` 也会在注册自身子模块前按需加载各自运行库，并复用该缓存目录；
  - 该机制参考并改编自 LuckPerms，详细署名见仓库根目录的 `THIRD_PARTY_NOTICES.md`。

- 认证等待区（`core.conf` 的 `vServer.outpre`）：
  - 2.0.0 起只保留 `outpre` 模式：先挂起正常 Velocity 注册，把登录阶段连接桥接到真实认证服，认证完成后再继续正常初始服流程；
  - 旧配置里的 `vServer.mode: backend` 与内置 NanoLimbo 后端已移除；启动时会自动把 `vServer.mode` 迁移为 `outpre`，并把原配置备份为 `core.conf.bak-<时间戳>`；
  - `authHost` / `authPort`：outpre 要直连桥接到的真实认证服（测试服 `127.0.0.1:30066`）；
  - `authLabel`：仅用于日志/状态标识的逻辑名，不需要在 Velocity 中注册；
  - `rememberRequestedServerDuringAuth`：是否记住玩家在认证阶段切换出来的目标服；
  - `presentedHost` / `presentedPort` / `presentedPlayerIp`：转接到认证服时，对后端暴露的连接信息；
  - 认证完成后的目标服回落遵循 Velocity 原生的 `forced-hosts` / `try`（attempt-connection-order）顺序；
  - 认证服无需在 Velocity 中注册；只要配置好 `authHost/authPort` 即可进行直连桥接；
  - 认证完成前，玩家不能进入其他后端；
  - 若 `rememberRequestedServerDuringAuth=true`，则会记住玩家原本想去的服务器，并在认证成功后自动连接过去。

- i18n / 玩家提示国际化：
  - 主插件会在数据目录生成 `messages.conf` 与 `messages/` 子目录；
  - `messages.conf` 用于控制 `defaultLocale`、`fallbackLocale` 与是否优先读取客户端语言；
  - 默认提供 `messages/en_us.conf`、`messages/zh_cn.conf`、`messages/ru_ru.conf` 三套文案；
  - 服主可直接编辑这些语言文件，自定义核心模块面向玩家的提示内容；
  - 修改后可使用 `/hzl reload` 重新加载消息配置。

从 1.x 升级（破坏性变更）
--------------------------
- **坐标全改**：插件 id 从 `hyperzonelogin` 改为 `sfplogin`，数据目录从 `plugins/hyperzonelogin` 变为 `plugins/sfplogin`，
  API 包名从 `icu.h2l.*` 变为 `cn.starfallplain.sfplogin.*`；生产环境**原地替换 jar 即可**，首次启动会自动复制旧数据目录（旧目录不会被删除，确认无误后自行清理）。
- **外部/独立模块 jar 必须重编译**：依赖旧包名 `icu.h2l.api` 的模块在新版本上会编译/加载失败；内置模块已随主插件更新。
- **命令与权限不变**：玩家侧命令（`/login`、`/register`、`/hzl ...`）与权限节点（`hyperzonelogin.admin`、`hyperzonelogin.qq.whitelist.bypass`）保持原样，模块 id 仍是 `hzl-*`。
- **等待区只剩 outpre**：内置 NanoLimbo 后端移除，`vServer.mode: backend` 启动时自动迁移为 `outpre` 并备份 `core.conf.bak-<时间戳>`；需要 `vServer.outpre.authHost/authPort` 指向真实认证服。
- **dialog 登录要求 1.21.6+（协议 771~775）**：Java 客户端在等待区会弹出带输入框的原生登录/注册框；低版本与未收录协议的 ViaVersion 组合自动回退聊天命令。基岩玩家走 Floodgate 表单，未安装 Floodgate 时同样回退聊天。
- **认证服回传校验**：outpre 桥接时，认证服在 `ServerLoginSuccess` 回传的档案必须与本次登录声明一致，否则断开并回退等待区（提示见 `backend-auth.claim-mismatch`）。

权限（Permission）
-------------------
| 命令 | 说明 | 所需权限 |
|------|------|----------|
| /hzl reload | 重载插件配置/状态 | hyperzonelogin.admin |
| /hzl re | 触发重新认证（玩家专用） | 无（命令仅允许玩家） |
| /hzl uuid | 显示 player/profile 信息 | hyperzonelogin.admin |

开发者/扩展提示
----------------
- 若实现子模块，请在 `register(...)` 中要求合适的 provider（例如 `HyperChatCommandManagerProvider`、`HyperZonePlayerAccessorProvider`），并在缺失时抛出明确异常以便快速定位集成问题。
- 主插件尽量暴露必要的轻量 API，避免子模块通过反射或 classloader hack 进行耦合调用。

