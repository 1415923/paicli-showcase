# 测试与验证记录

记录日期：2026-10-11。环境：JDK 21.0.11、Maven 3.9.9。

## 已执行

| 项目 | 结果 |
| --- | --- |
| 主代码与测试编译 | `mvn -o -DskipTests test-compile` 成功；565 个主 Java 文件、423 个测试 Java 文件 |
| 关键测试 | 85 项通过，0 失败、0 错误、0 跳过 |
| 文档复核 | 本轮核对源码入口、命令、相对链接及 Mermaid 结构；仅修改文档 |

关键测试命令：

```powershell
mvn -o "-Dtest=PersistenceSanitizerTest,ToolRegistryTest,SkillRuntimeTest,McpConfigManifestTest,R3CompactionProductContractTest" test
```

覆盖持久化净化、工具权限、Skills、MCP 配置和上下文压缩。编译和测试结果沿用此前候选检查，本轮没有重复运行这些测试。

## Core Demo

`demo core --headless` 已与 CLI 帮助、[命令解析器](../src/main/java/com/paicli/demo/cli/CoreDemoCommandParser.java)、[运行器](../src/main/java/com/paicli/demo/runtime/HeadlessCoreDemoRunner.java) 和 [分发包说明](../src/dist/README.md) 逐项核对。

**本轮未实际执行 Core Demo，也未重新打包 JAR。** README 给出的是实现支持的命令，没有引用新的演示成功记录。演示内部使用离线 Maven 测试，需要预先准备依赖缓存。

## 其他测试的运行条件

- `mvn test`：日常单元测试。
- `mvn verify -Pintegration`：集成测试；部分需要本地进程、Python、浏览器或显式参数。
- 历史 campaign / release 测试还依赖外部清单和评测数据，这些资产不随本仓库提供。

本次未执行全量测试、分发包真实终端验收、真实 Provider 调用、外部 benchmark 或跨平台验证。POM 沿用 1.2.0 版本号，当前代码包含后续开发改动，上述结果仅对应本候选。

## 发布文件检查

此前已对公开文件做本地敏感信息扫描和人工复核，未发现真实凭证。本轮只复查变更文档与暂存范围；原始会话、凭证、构建产物和历史归档未纳入 Git。测试中的合成凭证与环境变量引用保留用于验证安全行为。
