# SFP-login 重构任务清单

> 项目：HyperZoneLogin → **SFP-login**
> 包名：`cn.starfallplain.sfplogin`，插件 id：`sfplogin`，名称：`SFP-login`
> 版本：语义化 `x.y.z`，本次大重构 → **2.0.0**（不带 `-SNAPSHOT`）
> 顺序：**M0 → R1 → R3 → R2 → A → B → C**（每完成一项即勾选 + 单独提交，不推送）
> 图例：`[ ]` 未开始　`[~]` 进行中　`[x]` 已完成　`[!]` 阻塞/待确认

---

## 0. 已完成 / 既有决策

- [x] 删除绑定功能：`/bindcode` 命令、`binding_code` 表与仓库、`PendingOfflineRegistrationManager` 待绑定链路、
      `HyperZoneCredential.validateBind/bind/onConsumed`、`HyperZoneProfileService.bindSubmittedCredentials`（commit `16fd6d6`）。
      保留：渠道身份绑定（entry/floodgate/offline 的 profileId 关联）、`bindExistingProfile`、qq-whitelist 的 QQ 绑定。
- [x] 工作区先做 wip 提交（commit `9cc6fc9`，提交说明已写清）。
- [x] 关键决策（用户已确认）：

| # | 决策 |
|---|------|
| D1 | 数据兼容：**原地换 jar**，保留现有数据库/profile UUID/`remap.prefix`/转发链；不做一次性的 Profile 重写；`binding_code` 旧表留在库里不删 |
| D2 | 删除 backend 实现与 `backend-nanolimbo` 模块（含 NanoLimbo 子模块）；只保留 outpre；outpre 仍连外部 limbo（测试服 `127.0.0.1:30066`） |
| D3 | 配置自动迁移：旧 `vServer.mode=backend` 自动落到 `outpre` 并 WARN；删除 `vServer.backend.*`、`modules.backendNanolimbo` |
| D4 | Dialog：进入等待区自动弹出；用自定义包提交密码（不走聊天命令）；注册用两个输入框；Java 客户端 protocol ≥ 771（1.21.6+）用 dialog；Floodgate 基岩端用 Cumulus 表单；仅 offline 密码渠道；不支持 ViaVersion |
| D5 | `syncProfileName` 维持现状（外部改名允许同步）；AGENT.MD 规则改为「外部认证源改名可同步，必须记日志」，不再禁止 |
| D6 | 改名：包 `cn.starfallplain.sfplogin`、id `sfplogin`、名称 `SFP-login`；**玩家可见命令/权限节点保持原样**（如 `/login`、`hyperzonelogin.*`），外部模块 id（`hzl-*`）不变 |
| D7 | 版本：语义化 `2.0.0`，去掉日历版本与 `-SNAPSHOT` 后缀；后续 bug fix `2.0.1`、小更新 `2.1.0` |
| D8 | 提交粒度：一个任务项一次提交；中文提交说明写清范围；**不 push** |
| D9 | 迁移方式：复制旧工作区 → 改 `workspace.json` → 用户重启 DSH → 新路径验证后再删旧目录 |

**待确认的次要假设（如不同意请在会话里说，我改 todo）**

- [ ] H1 类名：只把插件入口类改名（`HyperZoneLoginBootstrap`→`SFPLoginBootstrap`、`HyperZoneLoginMain`→`SFPLoginMain`），
      `HyperZone*` 公共 API 类型名先保留（包名已变，破坏性已存在）；不做全量类名前缀替换。
- [ ] H2 API 版本：`apiVersion` 提到 `2.0.0`（RELEASE，不带 SNAPSHOT）。
- [ ] H3 `todo.md` 本身提交进仓库，每完成一项更新并随该提交一起提交。

---

## M0 工作区迁移与改名

- [x] **M0-1 工作区迁移**
  - [x] 复制 `E:\Dev\MC\Plugins\HyperzoneLogin` → `E:\Dev\MC\Plugins\SFP-plugins\SFP-login`（含 `.git`）
  - [x] 改 `C:\Users\ran\.dsh\storages\workspace.json`：workspace id `7074c266-566c-42e6-81a6-5d2161998a1d` 的 path/title
  - [x] 用户重启 DSH，在新路径确认 git 状态与 HEAD 一致
  - [x] 旧目录 `E:\Dev\MC\Plugins\HyperzoneLogin` 暂留；验收通过后按删除流程处理（先列清单再删）

- [x] **M0-2 包名重写**（机械替换 + 目录搬迁）
  - [x] `icu.h2l.login` → `cn.starfallplain.sfplogin`（main/test 源码目录、package、import、group、`buildSrc` 的 `icu.h2l.gradle`）
  - [x] 资源与反射字符串：`EmbeddedModuleRegistry` 的 `implementationClassName`、`paper-plugin.yml` main、`velocity-plugin.json` main、日志/诊断里的类名
  - [x] `grep -R "icu\.h2l\|HyperZoneLogin"` 清零（历史文档/生成物除外）

- [x] **M0-3 插件描述符**
  - [ ] `velocity-plugin.json`：`id=sfplogin`、`name=SFP-login`、`main=cn.starfallplain.sfplogin.SFPLoginBootstrap`；删除不再需要的 `limboapi` 依赖（R3 后）
  - [x] 各内置模块的 `paper-plugin.yml`：main 类改包名；模块 id 保持 `hzl-*`
  - [x] `rootProject.name = "SFP-login"`、jar baseName、发布产物名

- [x] **M0-4 插件入口类改名**（按 H1）
  - [x] `HyperZoneLoginBootstrap` → `SFPLoginBootstrap`
  - [x] `HyperZoneLoginMain` → `SFPLoginMain`（含 `getInstance()` 调用点）
  - [x] 配置/日志文案里的旧插件名替换（`HyperZoneLogin`/`HyperzoneLogin`）

- [x] **M0-5 旧数据目录自动迁移**
  - [x] 启动早期（读配置前）：若新数据目录为空且旧 `plugins/hyperzonelogin` 存在 → 递归复制（config/DB/日志目录），记录 INFO；**不删除旧目录**
  - [x] 若新旧都存在且非空 → 只用新目录并 WARN，避免覆盖
  - [x] 测试：临时目录模拟旧数据 → 启动 → 数据可用

- [x] **M0-6 文档改名**
  - [x] `AGENT.MD`：项目名、路径、命令示例；保留既有规则并补 D5 的改名同步规则
  - [x] `README.md` / `README.en.md`：名称、坐标、下载名
  - [x] `HyperZoneLogin-architecture.prg` 及导出 html/mmd：改名为 `SFP-login-architecture.*`（内容后续随架构更新）

- [x] **M0-7 构建验证 + 提交**：`./gradlew build` 绿；提交 `refactor!: 重命名为 SFP-login（cn.starfallplain.sfplogin / sfplogin）`

---

## R1 兼容基线（先锁死，再动结构）

- [x] **R1-1 UUID/转发不变式**（只加护栏，不改行为）
  - [x] `RemapUtils.genProfile/genUUID`、`NettyLoginSessionHandler` 的 `actualUuid` 选择、`LoginProfilePacketReplacer` 的 modern forwarding 解析：加测试/断言
  - [x] 明确并写入文档：初始未认证 UUID 由 `remap.prefix` 决定；认证后 Profile UUID 来自数据库，转发使用该 UUID
  - [x] `remap.prefix` 默认值与既有配置保持不变

- [x] **R1-2 数据库兼容回归**
  - [ ] 用一份旧版 SQLite（含 profile/credential/binding_code）启动新 jar：能读、能登录、UUID 不变（真实 jar 版在 R1-4 验收）
  - [x] 确认没有 destructive migration；新增列/表只能增量
  - [x] 增加自动化测试：旧 schema fixture → `DatabaseManager` 打开 → 关键查询通过

- [x] **R1-3 配置自动迁移**
  - [x] `core.conf`：`vServer.mode: backend` → 写回 `outpre`，日志 WARN「backend 已移除，已切换 outpre」
  - [ ] 删除 `vServer.backend.*` 整节；删除 `modules.backendNanolimbo`（移到 R3-2：字段仍被后端代码引用，删代码时随数据类一起消失）
  - [x] 迁移前备份为 `core.conf.bak-<时间戳>`；失败不阻塞启动（沿用默认值）

- [ ] **R1-4 手动验收清单（真实服务端）**
  - [ ] 旧 jar 跑出的玩家 UUID = 新 jar 同一玩家 UUID（正版/离线/floodgate 各一）
  - [ ] 登录后进入 lobby，背包/位置/经济等按 UUID 存储的数据仍在
  - [ ] 等待区 → 认证 → 放行全链路可走通

- [ ] **R1-5 提交**：`test(compat): 锁定 UUID 转发与旧数据兼容基线`

---

## R3 删除 backend 实现与 NanoLimbo

- [ ] **R3-0 删除清单（已盘点，执行前再确认一次）**
  | 目标 | 体积/数量 | 说明 |
  |---|---|---|
  | `velocity/src/main/kotlin/.../vServer/backend/`（5 文件） | 约 42 KB | `BackendAuthHoldListener` 20.7K、`BackendRuntimeProfileCompensator` 11.4K、`BackendWaitingAreaPlayerInfoFilter` 5.0K、`BackendProfileLayerCompatListener` 2.7K、`BackendLoginProfileReplaceListener` 2.5K |
  | `velocity/src/test/.../BackendRuntimeProfileCompensatorTest.kt` | 1 文件 | 随实现删除 |
  | `backend-nanolimbo/`（含 `NanoLimbo/` 子模块） | 3.4 MB（子模块 1.1 MB） | 整个 Gradle 模块 + submodule（commit `169bcc4`）+ `.gitmodules` 条目 |
  - [ ] 用户确认此清单后执行 `git rm -r`（删除流程：遍历→列清单→确认→删）

- [ ] **R3-1 删除 backend 包与测试**；清理 `HyperZoneLoginMain`、`VelocityNetworkInjectorImpl`、`LoginVerifyListener`、`EmbeddedModuleRegistry` 中的注入/监听注册
- [ ] **R3-2 配置与模块清理**：`VServerConfig.BackendConfig` 整块、`modules.backendNanolimbo`、`ModulesConfig` 注释、`settings.gradle.kts` include、`gradle/libs.versions.toml` 中仅 NanoLimbo 使用的依赖
- [ ] **R3-3 outpre 外部 limbo 收口**：校验 `vServer.outpre.authServerAddress` 可达性（启动 fail-fast 或首次登录 WARN）；文档写明测试服 `127.0.0.1:30066`
- [ ] **R3-4 消息键收敛**：`MessagesBackendAuth` 中只被 backend 使用的键迁移到 outpre 或删除；`config-comments` 同步
- [ ] **R3-5 测试/构建**：`./gradlew build`；`vc-runtest` 确认不引用 backend；提交 `refactor!: 移除 backend 实现与 backend-nanolimbo`

---

## R2 Dialog / 基岩表单登录

- [ ] **R2-1 API 契约**：`auth/api` 增加 `AuthPromptPresenter`（player、mode LOGIN/REGISTER、title/body、提交回调、close 回调）
- [ ] **R2-2 Java dialog 渲染**：`Dialog` NBT 构建（`minecraft:multi_action`，`inputs` 用 `text`）+ `DialogShowPacket`；`protocol >= 771` 判定用 `ProtocolVersionCompat`
- [ ] **R2-3 自定义包提交**：发送 `dialog/run_command` 不行，改为 `dynamic/custom` → `ServerboundCustomClickActionPacket`；代理侧解析 payload NBT（`hzl:auth`），在 `OutPreClientSessionHandler` 拦截，不转发后端
- [ ] **R2-4 offline 渠道接入**：登录/注册直接走 `OfflineAuthService`；错误回显在同一 dialog（重开/标题+错误行）；关闭 dialog = 继续等待区/回退聊天命令
- [ ] **R2-5 Floodgate 基岩表单**：`CustomForm` 两个输入（密码、确认），`FloodgateApi.sendForm`；仅 floodgate 玩家；回退到聊天
- [ ] **R2-6 路由与能力检测**：按 `ConnectedPlayer` 协议/Floodgate 标记选 presenter；Java `<771`、ViaVersion、未知客户端回退聊天；登录成功/失败后关闭 dialog
- [ ] **R2-7 配置与 i18n**：`auth.prompt.dialog.enabled`、超时、最大长度（默认 32 不够则配 128）；`dialog.*` 三语文案；等待区进入时自动弹出
- [ ] **R2-8 测试**：NBT 构建快照、presenter 选择矩阵、custom packet 解码、基岩表单构建；真机 1.21.6+/1.21.5/基岩各一轮
- [ ] **R2-9 提交**：`feat(auth): 支持 dialog 与基岩表单登录注册`

---

## A 组：生命周期 / 等待区状态 / 连接交接

- [ ] **A1 HyperZoneContext + 可关闭生命周期**（`HyperZoneLoginMain.kt:93-139`）：`start()` 返回已注册资源，`close()` 逆序释放；`onDisable` 全量关闭；删除 `companion` 静态 `instance/coreConfig/startConfig`
- [ ] **A2 统一等待区状态**（`OutPreState` vs `BackendHoldState` 合并）：`WaitingAreaSessionState`（authTarget、returnTarget、initialFlowPending、verifiedExitPending）+ `WaitingAreaGate`；先接 outpre，backend 删除后不保留 backend 状态
- [ ] **A3 ConnectionHandoff + 隔离 proxy join**：`join/held/verified/released/failed` 状态机；`eventLoop().join()` 从事件回调移除（`ProfileSkinApplySupport`、`ServerLoginSuccessPacketReplacer`）
- [ ] **A4 CredentialFlow 显式 LinkSubmit + 模块可重注册**：`submit/consume/link` 三态；`ChannelPasswordGateRegistry` / `CredentialChannelRegistry` 提供 unregister；模块注册失败回滚
- [ ] **A5 outpre 认证会话校验**（原 A6 绑定收敛已随绑定删除取消）：外部认证服返回的档案必须与 claim 的一致；失败回滚等待区
- [ ] **A6 提交**：`refactor(core): 生命周期与等待区状态收敛`

## B 组：LoginManager / 配置 / 转发重写

- [ ] **B1 放行顺序事务化**：先 `markWaitingAreaLeavePending`/`onVerified`，成功后再 `notifiedReadyPlayers.add`；失败回滚并允许重试（`LoginManager.kt:256-262`）
- [ ] **B2 拆分 LoginManager**（642 行 → `CredentialClaimService` / `AwaitingLoginSessions` / `TransitionSequencer` / `PlayerSessionView`）
- [ ] **B3 ConfigStore + 可重载快照**：auth/vServer 配置 reload 后重建不可变快照；`messages()` 失败显式上报；解锁 `ProfileBindingCodeService` 已删除后的 reload 路径
- [ ] **B4 LoginPacketRewriteService + off-thread**：统一 4 处登录包改写；`eventLoop().join()` 移到线程池/api 线程；超时降级
- [ ] **B5 i18n 收口**：移除 `/bindcode` 回退文案；所有失败路径有明确 key；中英俄三语同步
- [ ] **B6 模块描述符化**：`EmbeddedModuleRegistry` 改 `ModuleDescriptor` 注册（可先文档化 + 最小实现）
- [ ] **B7 提交**：`refactor(core): 放行顺序、配置快照与登录包重写`

## C 组：构建 / 反射 / 测试边界

- [ ] **C1 反射隔离**：`VelocityInternalAccess` 只保留必要入口 + 启动自检（握手失败明确日志）；删除 `FuzzyLookup` 死代码
- [ ] **C2 buildSrc 约定插件**：8 个模块的 42-44 行模板收敛为一个 convention plugin
- [ ] **C3 CTD 子构建**：移除非必要的独立 `--no-daemon` gradle 调用（Kotlin `FilePageCache` 冲突用构建脚本规避），减少全量构建
- [ ] **C4 测试边界**：vServer/eventLoop 并发测试、outpre 包处理测试；新增 dialog/custom packet 测试；避免 `LoginManagerTest` 级别的重反射
- [ ] **C5 spotless 拆分**：12 条路径规则收敛为按模块配置
- [ ] **C6 版本与发布**：`version=2.0.0`（无 `-SNAPSHOT`），删除日历版本计算/`releaseChannel` 后缀逻辑；README 更新
- [ ] **C7 CI**：增加 build+test workflow（当前只有 weekly-release）
- [ ] **C8 提交**：`build: 收敛构建配置与版本号`

---

## 验证与提交规范

- 每个任务项：实现 → `./gradlew build`（必要时 `test`）→ 勾选本文件 → 提交（中文说明，写清范围与影响）→ 不 push
- 涉及删除的项：先列「路径 + 体积 + 内容 + 可重建性」→ 用户确认 → 再删
- 涉及线上数据的项（R1/M0-5）：先在测试服验证，再谈部署
- 真机验收：`bukkit-offline-verify` 思路 + 测试服（`\\WIN-QCLLAKI0B0G\d\xlpy\xlpy_vanilla\server`）冒烟

## 风险 / 待确认

- [ ] 旧目录删除时机（M0-1 验收后）
- [ ] 类名前缀 H1、API 版本 H2、todo 提交 H3
- [ ] 外部/独立模块 jar 需随包名变更重新构建（它们依赖 `api` 的旧包名会编译失败）
- [ ] dialog 自定义包需与 ViaVersion/其他聊天插件共存验证（不支持 ViaVersion 客户端）
- [ ] Floodgate 运行时若未安装，表单路径自动回退聊天
