# SFP-login Agent Rules

本文件记录 SFP-login 登录链路的强制工程规范。

## 1. 登录流程必须使用明确语义

SFP-login 的登录链路属于**严格状态机**，禁止使用会混淆状态边界的模糊命名或混合流程。

### 强制规则

- `resolve` 只允许表示：**按既有 `profileId` 解析已有 Profile**。
- `create` 只允许表示：**以指定注册名尝试新建 Profile**。
- `canResolve` 只允许表示：**既有 `profileId` 是否可解析**。
- `canCreate` 只允许表示：**给定 `(userName, uuid?)` 是否允许建档**。

### 明确禁止

以下语义在本项目中视为不合规，禁止重新引入：

- `resolveOrCreate`
- `canResolveOrCreate`
- 任何把 `resolve / create / 回退匹配 / 自动改名` 混在一个入口中的 API

原因：登录流程必须可审计、可推导、可断言，不能依赖“也许解析、也许创建、也许顺便改名”的模糊副作用。

## 2. `clientOriginalName` 与 `registrationName` 必须严格区分

- `clientOriginalName`：客户端初始上报的**不可信原始名**，创建后不得改写。
- `registrationName`：当前等待区会话在“若要新建 Profile”时应使用的**待建档注册名**，完全由认证渠道提交的凭证决定；等待区**不提供**任何玩家可自行改名的命令入口。

禁止把 `clientOriginalName` 当成：

- 可信认证结果
- 正式游戏身份
- 当前可变注册名

## 3. 等待区不提供改名 / 重算 UUID 入口

`/rename` 与 `/reUUID` 已从项目中**整体移除**，禁止以任何形式重新引入：

- 不允许注册 `rename` / `reUUID` 命令（含任何别名）；
- 不允许提供"玩家在等待区自行修改待建档注册名"的入口；
- 不允许提供"玩家在等待区自行重算/重分配建档 UUID"的入口；
- `HyperZoneCredential` 不再提供 `withRegistrationName(...)` / `withReUuidRequest()` 这类派生凭证接口。

原因：等待区的名称、UUID、凭证组合都属于**不可信且高变化的临时态**，把它暴露成玩家可自助触发的写操作，会让登录状态机变得不可审计、不可推导。

## 4. 已落库绑定关系不得被静默改写

建档一旦完成，正式 Profile 的 `name` / `UUID` 与已落库的认证绑定都视为**正式数据**。

禁止：

- 在等待区或游戏区直接修改已存在 Profile 的正式名称；
- 直接改写已落库的正式认证绑定；
- 把任何建档流程充当 merge / remap / rebind 工具；
- 重新引入任何形式的改名 / 重算 UUID 玩家入口（见第 3 节）。

## 4.1 外部认证源改名允许同步，但必须留痕

已落库的正式 Profile 名称**不因等待区操作而改变**；但以下情况例外，且必须显式留痕：

- 外部认证源（如 Yggdrasil / Mojang 平台名、Floodgate 渠道名）一侧发生了改名，
  且核心层通过 `syncProfileName` 之类的受控机制同步到本地正式 Profile 时，**允许同步**；
- 同步动作必须记录日志（旧名 → 新名、来源渠道、触发时机），便于事后审计；
- 同步只能由核心层内部受控机制发起，不得由等待区玩家自助入口触发（见第 3 节）；
- 同步失败不得回滚认证结果，也不得把玩家退回等待区，除非同步失败意味着身份不一致（此时按第 6 节处理）。

## 5. 认证凭证规范

认证模块提交到核心层的 `HyperZoneCredential` 必须遵守：

- `getBoundProfileId()` 只表示当前是否已绑定；
- 未绑定凭证如需参与严格建档，可通过 `getSuggestedProfileCreateUuid()` 显式提供建议 UUID；
- `getRegistrationName()` 返回渠道自身的可信注册名，凭证一旦提交即视为不可变更；
- 不得以任何方式在凭证提交之后隐式篡改正式落库绑定。

## 5.1 `attachProfile` 后的游戏区流程边界

`attachProfile` 之后，玩家身份已进入正式 Profile 语义。

此阶段若存在 GameProfile 显示优化、正式 name/UUID 补偿同步、切服后身份一致性修复等需求，必须遵守：

- 只允许由核心层**内部受控机制**处理；
- 必须与等待区建档流程彻底隔离；
- 不得复用等待区 credential 回调等不可信入口直接驱动；
- 设计与实现时必须明确标注“这是正式游戏区流程，不是等待区流程”。

## 5.2 `VelocityHyperZoneProfileService` / `DatabaseHelper` 的禁止越界项

`VelocityHyperZoneProfileService` 与相关 `DatabaseHelper` 默认只承担：

- 既有 Profile 读取；
- 新 Profile 创建；
- attach / bind 所需业务逻辑；
- 围绕创建/读取的必要校验与查询辅助。

除非需求被**明确、单独、强语义**地说明，否则禁止：

- 在这些类中新增“修改现有 Profile 数据”的接口；
- 在这些类中直接改写既有 Profile 的正式 `name` / `UUID`；
- 把运行时显示修正、Velocity 内存补偿同步、等待区临时态修正，错误地下沉为正式 Profile 数据更新。

## 6. 等待区放行条件

玩家离开等待区必须同时满足：

- 认证已通过；
- 正式 Profile 已 attach。

任何仅完成其中一半的状态，都必须继续停留在等待区。

## 7. 模块实现建议

- Offline / Yggdrasil / Floodgate 等认证模块，如遇“可信认证完成但当前名称无法建档”，应保留玩家在等待区并提供明确提示；
- 后续恢复流程应通过显式 `create(...)` + 绑定逻辑推进，不得重新拼回模糊入口；
- 新模块接入时，优先复用核心事件与明确接口，不得私自引入“自动 resolveOrCreate”捷径。

## 8. 模块对齐原则

所有子模块都必须与现有模块保持一致的基础能力，不允许出现“功能已做但外围体验缺失”的半成品接入。

### 8.1 i18n 为强制项

凡是面向玩家展示的模块消息，必须接入统一 i18n：

- 提供模块级消息 helper（如 `YggdrasilMessages`、`FloodgateMessages`）；
- 提供 `messages/<namespace>/en_us.conf`、`zh_cn.conf`、`ru_ru.conf`；
- 在模块注册阶段复制 bundled locale 到数据目录；
- 禁止在监听器 / service 中长期保留玩家可见硬编码字符串。

### 8.2 模块行为与核心提示必须对齐

- 若模块会把玩家留在等待区，就必须给出下一步操作提示；
- 不允许某个模块具备功能，但提示链路缺失，导致玩家只能“猜命令”。

### 8.3 新模块接入检查项

新增或重构模块时，至少检查以下项目是否齐全：

1. 明确接口语义是否符合核心规则；
2. 玩家可见消息是否全部接入 i18n；
3. 是否提供中/英/俄三语资源；
4. 是否在失败/等待区状态下提供明确下一步引导；
5. 是否与现有模块的参数格式、提示风格、资源落盘方式保持一致。

## 9. 源码获取限制

- 寻找源码时不能去 cache 中找，如果需要源码且 `ref/` 目录下没有相关源码，请停止编码，并向用户请求对应的源码。

## 10. 文档生成规范

- **禁止生成总结文档**：完成功能后，不要生成总结性的 Markdown 文件（如 IMPROVEMENT_SUMMARY.md）
- **禁止生成快速参考卡**：不要生成 QUICKSTART.md、CHEATSHEET 类的文件
- 功能完成时直接向用户报告结果，无需额外的独立总结文件
- 只在用户明确要求时才生成辅助文档

## 11. 版本号与提交规范

- 版本号格式为 `x.y.z`：
  - Bug 修复：`z + 1`；
  - 小内容更新：`y + 1`；
  - 没有大更新 / 没有重构：不要改动 `x`。
- 版本号不带 `-SNAPSHOT` 后缀，正式版本直接写 `x.y.z`。
- 每次修改完成后提交到本地 Git 仓库，但不要推送（push）。
- 提交说明必须写清楚改了什么、为什么改。

## 12. 项目坐标与命名约定

- 仓库 / 项目名：`SFP-login`
- 根包名：`cn.starfallplain.sfplogin`（API 层为 `cn.starfallplain.sfplogin.api`）
- Velocity 插件 id：`sfplogin`；插件显示名：`SFP-login`
- 数据目录：`plugins/sfplogin`（旧 `plugins/hyperzonelogin` 会在启动时自动迁移一次，旧目录保留）
- 发布产物名：`SFP-login-<version>.jar`、`SFP-login-<version>-all.jar`、`SFP-login-<version>-ctd.jar`
- 内置模块 id 保持 `hzl-*` 不变；玩家可见命令与权限节点保持原样（如 `/login`、`hyperzonelogin.admin`）

## 13. 构建与验证

- 构建：`./gradlew build`（Windows 下 `gradlew.bat build`），提交前必须为绿；
- 单独跑某模块测试：`./gradlew :velocity:test --tests "cn.starfallplain.sfplogin.*"`；
- 代码风格：`./gradlew spotlessApply`，提交前保持无 diff。

## 14. UUID / 转发不变式（兼容基线）

- **初始未认证 UUID**：由 `remap.prefix` 决定，算法是 `UUID.nameUUIDFromBytes("<prefix>:<username>")`（见 `RemapUtils.genUUID`），
  用户名保持客户端登录时的原样、不做小写化。`remap.prefix` 的默认值固定为 `HyperZone`，**不得修改**；
  改为 `OfflinePlayer` 属于用户侧切换离线标准生成法，属于行为变更，需要单独评估。
- **已确定 UUID 优先**：正版校验或 Floodgate 已经给出 UUID 时直接沿用，不再重算
  （`RemapUtils.resolveActualUuid(holderUuid, username, prefix)`）。
- **认证后 Profile UUID**：来自数据库中的正式档案（`genProfileUUID` 的 `h2l:<小写用户名>` 仅用于没有正式档案的兜底场景）；
  转发给后端时使用该 UUID，而不是临时 UUID。
- **modern forwarding**：后端声明的版本号位于 `LoginPluginRequest` payload 的 32 字节签名之后，
  解析失败一律回退 `PlayerDataForwarding.MODERN_DEFAULT`，不得因此中断登录（`decodeModernForwardingVersion`）。
- 上述行为的守护测试：`api/src/test/kotlin/cn/starfallplain/sfplogin/api/util/RemapUtilsTest.kt`、
  `velocity/src/test/kotlin/cn/starfallplain/sfplogin/config/RemapConfigCompatTest.kt`、
  `velocity/src/test/kotlin/cn/starfallplain/sfplogin/inject/network/netty/replacer/LoginProfilePacketReplacerTest.kt`。

## 15. 认证提示框（dialog / 基岩表单）

- **统一契约**：展示层实现 `api/.../api/auth/AuthPromptPresenter`，由 `AuthPromptPresenterRegistry` 按
  `priority` 选一个可用实现；能力检测抛异常、或没有实现可用时，等待区回退到聊天命令提示（`/login`、`/register`）。
- **Java dialog**（`velocity/.../dialog/`）：
  - 只用 `minecraft:multi_action` 对话框，提交按钮动作是 `dynamic/custom`，命名空间 ID 固定为 `hzl:auth`；
  - PLAY 阶段的包 ID 表在 `DialogPacketIdsTable`，**只收录已核对的协议版本**（771/772/773/774/775）；
    未收录的版本（含 776 及以后）一律视为不支持，禁止猜 ID —— 发错包会让客户端直接断开；
  - Velocity 4.1.0 只在 CONFIG 阶段注册 dialog 包，PLAY 阶段靠 `SfpDialogPackets` 手拼
    `[varint packetId][varint holderId=0][unnamed NBT]` 写入连接的 `Channel`；
  - 客户端回传的 `custom_click_action` 由 `OutPreClientSessionHandler` 在转发后端之前拦下，
    经 `DialogCustomActionCodec` 判定；**过期响应也要消费掉**，否则会被后端当成未知包。
- **基岩表单**（`auth-floodgate/.../prompt/FloodgateAuthPromptPresenter`）：走 Floodgate 内置的 Cumulus 旧版 API
  （`org.geysermc.cumulus.CustomForm`），优先级高于 dialog；基岩回传的数组按“所有控件”给出，
  标签位是 `null`，取值必须按控件类型计算下标，不能直接按输入序号读。
- **ViaVersion**：客户端上报的是翻译后的协议号，是否支持只看 `DialogPacketIdsTable` 是否收录，不做额外探测。
- 守护测试：`velocity/src/test/kotlin/cn/starfallplain/sfplogin/dialog/`、
  `api/src/test/kotlin/cn/starfallplain/sfplogin/api/auth/AuthPromptPresenterRegistryTest.kt`、
  `auth-floodgate/src/test/kotlin/cn/starfallplain/sfplogin/auth/floodgate/prompt/FloodgateAuthPromptPresenterTest.kt`。
