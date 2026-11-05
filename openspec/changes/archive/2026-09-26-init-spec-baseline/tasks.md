# Tasks

## 1. 恢复项目上下文（config.yaml）

- [x] 1.1 将 v1.x 旧 `config.yaml` 的 context 与 rules（取自历史提交 `5ab509f`）按 v2.0.0 现状校正后写回 `openspec/config.yaml`：`SINGBOX_VERSION` 相关表述改为 `THRONE_CORE_REF` 与 `VERSION_NAME`，其余规矩（快速滚动验证、uv run 工具流、外部仓库路径规则）保留。验证：`openspec context --json` 正常返回且 `config.yaml` 中不再出现 sing-box 版本来源的旧表述
- [x] 1.2 提交批次改动（由用户自行执行 git 提交；CI/真机验证不适用：本批次仅改 OpenSpec 配置，无代码行为影响；本地以 1.1 的命令输出为证据）

## 2. 治理基线规格（repository-governance）

- [x] 2.1 依据旧 gov spec（`5ab509f:openspec/specs/repository-governance/spec.md`）与当前实测（`nb4a.properties`、`.github/actions/throne-core/action.yml`、`workflows/release.yml`、`workflows/preview.yml`）编写 `specs/repository-governance/spec.md` delta。验证：文件存在，每条 requirement 至少一个 WHEN/THEN 场景，不包含 sing-box/libcore 旧链路表述
- [x] 2.2 提交批次改动（由用户自行执行 git 提交；CI/真机验证不适用：纯规格文档）

## 3. 能力域基线规格（10 份 delta）

- [x] 3.1 核对并定稿 10 份能力域 delta（`proxy-connection`、`profile-management`、`outbound-protocols`、`routing-rules`、`auto-selector`、`connectivity-testing`、`settings-storage`、`backup-restore`、`app-widgets`、`diagnostics-logging`）：确认场景表述均为外部可观察行为、与既有单测（`ThrBackupTest.kt`、`TestSettingsContractTest.kt`）不冲突。验证：每份文件存在且含 `## ADDED Requirements`
- [x] 3.2 提交批次改动（由用户自行执行 git 提交；CI/真机验证不适用：纯规格文档）

## 4. 全量校验与评审

- [x] 4.1 运行 `openspec validate init-spec-baseline --strict` 并修复全部报错。验证：命令零退出
- [x] 4.2 运行 `openspec status --change init-spec-baseline` 确认全部产物 done。验证：`isPlanningComplete: true`
- [x] 4.3 用户批准文档类变更直接推进归档；已从 `5ab509f` 恢复 `openspec/history/official-sing-box-migration.md` 作为历史资料（不作为现行 spec，可随时删除）
- [x] 4.4 评审通过后执行 archive，将 11 份 delta 合入 `openspec/specs/`。验证：`openspec list --specs` 列出全部 11 个能力
