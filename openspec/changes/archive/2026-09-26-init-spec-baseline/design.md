# Design

## Context

`openspec/specs/` 因 v2.0.0 throne 核心移植后的 `git reset --hard`（重置到 `102afdf`）而清空；v1.x 旧 spec 仍可从历史提交 `5ab509f` 读取（`android-application`、`libcore-integration`、`repository-governance`、`theme-system` 四个主规范与 `openspec/history/`、旧 `config.yaml`）。本变更只写规格与 OpenSpec 配置，不涉及任何代码改动。规格来源是对当前代码的只读考察，关键锚点：

- 连接服务：`app/src/main/java/io/nekohasekai/sagernet/bg/BaseService.kt`（State 枚举、广播 Action）、`VpnService.kt`、`SagerConnection.kt`、`TileService.kt`。
- 数据模型：`database/ProxyEntity.kt`、`ProxyGroup.kt`（`isSubscription = url 非空`，字段镜像桌面端 `GroupsRepo.cpp`）、`RouteRuleEntity.kt`、`RouteProfileEntity.kt`、`SagerDatabase.kt`（Room，schema 版本化）。
- 协议：`outbound/types/` 下 24 个协议实现 + `moe/matsuri/nb4a/Protocols.kt`。
- 备份：`database/backup/ThrBackup.kt`（"THRN" 魔数、Qt_6_0 little-endian 流、format version 2，与桌面 `.thrbackup` 互通；有 `ThrBackupTest.kt`）。
- 订阅：`group/RequestIdentity.kt`、`group/SubscriptionFetch.kt`（v2.0.0 的 UA/HWID 请求身份机制，取代 v1.x 的 UA 候选链）。
- 构建/发布：`nb4a.properties`（`PACKAGE_NAME`/`VERSION_NAME`/`VERSION_CODE`/`THRONE_CORE_REF`）、`.github/actions/throne-core/action.yml`、`workflows/release.yml`（手动触发）、`workflows/preview.yml`（主分支推送，`v$VERSION_NAME-pre.N`）。
- 既有测试：`app/src/test/`（`ThrBackupTest.kt`、`TestSettingsContractTest.kt`、`WrappedHostResolverTest.kt`）——场景措辞尽量与这些可测行为对齐。

## Goals / Non-Goals

**Goals:**
- 11 个能力域（含治理）的基线 spec 全部落档，每条 requirement 至少一个场景。
- 从旧 gov spec 恢复仍有效的仓库规矩（config.yaml context/rules + `repository-governance`），并按 v2.0.0 校正。
- 场景用外部可观察行为表述，不绑定内部类名。

**Non-Goals:**
- 不恢复旧 `libcore-integration` spec（`libcore/` 已移除，核心改为 CI 按 `THRONE_CORE_REF` 构建，其构建协作约束并入 `repository-governance`）。
- 不整体恢复旧 `android-application` spec：其多数要求所依赖的机制已被 v2.0.0 取代（如 UA 候选链 → `RequestIdentity`、sing-box schema 校验 → throne 核心），新基线以当前代码为准。
- 不在本变更内恢复旧 `theme-system` spec：主题行为量大且 v2.0.0 UI 改动多，留待后续独立变更逐条核对恢复。
- 不为每个协议字段逐一建档；`outbound-protocols` 只定契约层级。

## Decisions

1. **按能力域拆 11 份 spec，替代旧的 4 份粗粒度主规范。**
   备选：照搬旧结构。拒绝原因：旧 `android-application` 一个 spec 混合了服务、订阅、备份、UI 等多域，delta 归档时冲突面大；新划分与 `app/src/main/java` 包结构（bg/database/outbound/route/ui/utils/appwidget）对应，治理独立成域。

2. **基线 spec 用 `ADDED Requirements` 写成 delta，经 archive 落入主 spec。**
   遵循 OpenSpec 流程：即使描述的是"已存在"的行为，在 `openspec/specs/` 视角下都是新增能力，走标准 delta → archive 路径。

3. **治理规矩分两处落地：协作/构建约束进 `repository-governance` spec，AI 协作上下文进 `config.yaml`。**
   旧版正是这一分工：spec 管"仓库必须是什么样"，config 管"AI 做规划时按什么约束工作"。恢复时同步校正过时项：`SINGBOX_VERSION` → `THRONE_CORE_REF`；删除依赖已丢失脚本的"布局可静态校验"要求（旧 `roo_check_repo_governance.py` 未随 reset 存活，如需可由后续 change 重建）；场景格式从 GIVEN/WHEN/THEN 放宽为 WHEN/THEN（可补 GIVEN），与当前 schema 模板一致。

4. **发布/预览要求按当前工作流实测行为书写。**
   `release.yml`：手动 dispatch、tag `v$VERSION_NAME`、远端已存在同 tag 时失败并提示提升版本、`publish=false` 仅上传 artifact。`preview.yml`：主分支推送自动触发、`v${VERSION_NAME}-pre.N`（N≤998）、HEAD 为正式 tag 时跳过、pre-release。均与旧 gov spec 有实质差异，按现状书写。

5. **订阅相关硬性要求暂不写入基线。**
   v1.x 的 UA 链回退、逐条容错、diff 熔断等行为在 v2.0.0 是否完整保留尚未逐一验证；未经核实的旧要求不冒充基线。候选后续变更：订阅解析与更新行为的专项基线核对。

## Risks / Trade-offs

- [spec 与代码漂移] 代码演进后基线可能过时 → 归档后以 delta 流程维护；行为变更必须先改 spec 再改代码。
- [粒度争议] 11 个域的边界是人为划分 → 若后续变更跨域，允许 delta 同时命中多个 spec。
- [旧经验丢失] 旧 `android-application`/`libcore-integration` 中的事故教训（如 URL 测速共享 cache.db、protect 回环）未随 spec 恢复 → `openspec/history/official-sing-box-migration.md` 可从 `5ab509f` 恢复为历史资料（不作为现行 spec），建议随本变更归档一并处理。
- [主题基线缺位] `theme-system` 延后 → 在恢复前，主题相关变更暂时只受代码评审约束。
