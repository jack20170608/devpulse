# Gatherer 架构迁移 / Gatherer architecture migration

[中文](#中文) | [English](#english)

## 中文

gatherer 已不再是独立 Maven 模块。环境事实采集并入 `devpulse-core` 的 `environment` 包，Gather 和 Pulse 直接依赖 core。

正文已迁移到 [Core 架构设计](../../devpulse-core/architecture.md)；采集契约及示例见 [Core 使用说明](../../../devpulse-core/README.md#环境事实采集)。本页仅保留迁移链接。

## English

gatherer is no longer a standalone Maven module. Environment fact collection is part of `devpulse-core`'s `environment` package; Gather and Pulse depend directly on core.

The architecture now lives in [Core architecture](../../devpulse-core/architecture.md); collection contracts and examples are in the [Core usage guide](../../../devpulse-core/README.md#environment-fact-collection). This page retains migration links only.
