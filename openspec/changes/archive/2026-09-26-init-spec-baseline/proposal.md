# Proposal

## Why

项目 `ThroneForAndroid`（v2.0.0，`com.nb4a.throne`）在 Nova 完成 throne 核心移植后因改动面过大执行了 `git reset --hard`（重置到 `102afdf`），v1.x 时代的 `openspec/specs/`（`android-application`、`libcore-integration`、`repository-governance`、`theme-system`）随之丢失。缺少行为规格意味着后续改动只能依赖代码考古。需要基于 v2.0.0 现状重建规格基线，并从旧 gov spec 恢复仍然有效的仓库规矩。

## What Changes

- 初始化 v2.0.0 规格基线：按当前代码观察到的行为编写 `ADDED` 要求与场景，落到 `openspec/specs/<capability>/spec.md`。
- 恢复 `openspec/config.yaml` 的项目上下文与规则（依据旧版 config，按 v2.0.0 调整：throne 核心替代 sing-box 官方 libcore，`THRONE_CORE_REF` 替代 `SINGBOX_VERSION`）。
- 恢复仓库治理规格 `repository-governance`（源自旧 gov spec，按 v2.0.0 调整：throne-core CI 构建链、release/preview 工作流现状）。
- 本次变更**只新增规格与 OpenSpec 配置文档，不修改任何项目代码**；规格内容以当前代码观察到的行为为准，不引入新行为。
- 按能力域划分基线（每个能力一份 spec）：
  - `proxy-connection`：VPN/代理服务生命周期、前台通知、SagerConnection 进程绑定、快速设置磁贴。
  - `profile-management`：节点与分组的增删改查、排序、置顶、订阅更新与导入分享。
  - `outbound-protocols`：各出站协议（约 24 种）的配置契约与生成行为。
  - `routing-rules`：路由规则与路由配置档的管理及路由决策。
  - `auto-selector`：按测速结果自动切换节点。
  - `connectivity-testing`：URL 测速与 TCP ping。
  - `settings-storage`：DataStore 设置契约与多进程一致性。
  - `backup-restore`：与桌面互通的 `.thrbackup` 备份/恢复。
  - `app-widgets`：桌面小部件状态与开关。
  - `diagnostics-logging`：日志查看、导出与脱敏。
  - `repository-governance`：仓库边界、构建协作与维护工具布局（恢复自旧 gov spec）。

## Capabilities

### New Capabilities

- `repository-governance`: 仓库目录职责、OpenSpec 规范入口、本地/CI 构建协作、版本与核心来源、发布与预览工作流、Python 工具布局。
- `proxy-connection`: VPN/代理连接服务的生命周期、状态广播、通知与系统集成入口。
- `profile-management`: 节点与分组数据管理、订阅、导入/分享。
- `outbound-protocols`: 各代理协议出站的配置字段与行为契约。
- `routing-rules`: 路由规则、路由配置档与分流行为。
- `auto-selector`: 按测速自动切换节点的选择器行为。
- `connectivity-testing`: 连通性测速（URL test / TCP ping）行为。
- `settings-storage`: 应用设置的定义、存储与读写契约。
- `backup-restore`: 配置备份与恢复行为。
- `app-widgets`: 桌面小部件的状态展示与快捷开关。
- `diagnostics-logging`: 日志采集、展示、导出与脱敏。

### Modified Capabilities

（无 —— `openspec/specs/` 当前为空，全部为新建基线。）

## Impact

- 影响 `openspec/specs/`（新增基线规格）、`openspec/config.yaml`（恢复项目上下文）与 `openspec/changes/init-spec-baseline/`。
- 不触碰 `app/`、`buildScript/`、`.github/` 等项目代码与构建配置；不改变运行时行为。
- 旧 `libcore-integration` spec 不恢复：v2.0.0 已移除 `libcore/`，核心改为 CI 从 `THRONE_CORE_REF` 构建 `ThroneCore.aar`，其约束并入 `repository-governance`。
- 旧 `android-application` spec 不整体恢复：其多数要求（UA 链回退、sing-box schema 校验等）在 v2.0.0 已被 `RequestIdentity`、throne 核心等新机制取代；仍有效的主题行为（旧 `theme-system`）建议作为后续独立基线变更单独核对恢复。
- 后续变更将以这些基线 spec 为参照编写 delta 规格。
