# 技术架构

## 从终端到一次工具调用

[PaiCLI](../src/main/java/com/paicli/PaiCLI.java) 解析命令并进入交互运行时。[InteractiveRuntimeAdapter](../src/main/java/com/paicli/interactive/runtime/InteractiveRuntimeAdapter.java) 连接会话、任务及 MCP 服务，[ProductionInteractiveTaskExecutor](../src/main/java/com/paicli/interactive/runtime/ProductionInteractiveTaskExecutor.java) 为任务准备上下文与执行组件。

[AgentLoop](../src/main/java/com/paicli/agent/AgentLoop.java) 驱动模型请求和工具调用。Tool Registry 暴露工具定义；工具参数、路径和审批经过检查后执行，结果写入状态库，再用于后续模型请求。计划确认与工具授权分别处理，文件写入和命令仍按当前策略审批。

## 持久化、恢复和 rewind

[state](../src/main/java/com/paicli/state) 保存 Task、Run、Tool 调用、结果和检查点。执行租约与 fencing 标识用于拒绝过期执行者的状态更新。恢复时先读取已有执行记录，确定继续位置，避免重复执行已完成步骤。

会话绑定、任务恢复、文件回退对应三种操作：

| 操作 | 作用 |
| --- | --- |
| `/resume` | 绑定已保存会话 |
| `/task recover` | 根据持久化状态恢复任务执行 |
| `/rewind` | 恢复受管理的文件快照，并校验当前文件内容 |

命令可能产生数据库、网络等外部副作用；状态不明时采用保守恢复，不能用文件快照保证任意外部操作的 exactly-once 或回滚。

## 上下文编译与压缩

[context](../src/main/java/com/paicli/context) 根据预算构建模型输入，将持久化事实映射为当前轮次需要的上下文。压缩发生在工具结果持久化后、下一轮模型请求前。

[DeterministicCompactionPolicy](../src/main/java/com/paicli/context/DeterministicCompactionPolicy.java) 和 [CompactionCoordinator](../src/main/java/com/paicli/context/CompactionCoordinator.java) 组织确定性账本、工具关联和来源引用，并校验来源完整性。任务事实保存在状态库中，压缩调整的是活动上下文投影，不依赖 LLM 生成摘要。

## MCP 与 Skills

[InteractiveMcpService](../src/main/java/com/paicli/mcp/interactive/InteractiveMcpService.java) 将本地 STDIO MCP 服务接入任务。AgentLoop 先执行 `prepareApproval`，再通过 `executeApproved` 调用工具。配置加载、schema、进程启动、产物身份与输出净化分别有实现。

[SkillRuntime](../src/main/java/com/paicli/skill/SkillRuntime.java) 先发现元数据，再按显式选择或工具调用加载正文和资源；资源列表及正文支持分页。Skill 内容作为任务上下文使用，权限仍由宿主审批策略决定。示例见 [skill-examples](../src/main/resources/skill-examples)。

## Provider 与平台支持

[ModelProvider](../src/main/java/com/paicli/provider/ModelProvider.java) 隔离模型协议，凭证解析位于 [credential](../src/main/java/com/paicli/credential)。

| 接口 | 当前实现 |
| --- | --- |
| OpenAI-compatible Chat Completions | 请求、工具响应与有界重试 |
| DeepSeek | Chat 适配及私有 continuation 字段处理 |
| Ollama | 原生 adapter |
| OpenAI Responses / Codex、Anthropic、Gemini | 尚无完整协议适配 |

模型响应当前一次性返回，终端进度来自执行事件。Windows 可使用 Credential Manager；其他平台可通过环境变量提供凭证，终端与凭证存储的跨平台验证范围见 [验证记录](validation.md)。

Strict Plan 的完整验收、通用多 Agent 与后台任务平台仍属后续工作。

## 离线 Core Demo

[CoreDemoCommandParser](../src/main/java/com/paicli/demo/cli/CoreDemoCommandParser.java) 接受固定命令 `demo core --headless`。[HeadlessCoreDemoRunner](../src/main/java/com/paicli/demo/runtime/HeadlessCoreDemoRunner.java) 创建独立样例工作区，使用 [CoreDemoDeterministicProvider](../src/main/java/com/paicli/demo/provider/CoreDemoDeterministicProvider.java) 驱动固定场景。

[CoreDemoFullWorkflow](../src/main/java/com/paicli/demo/workflow/CoreDemoFullWorkflow.java) 在持久化检查点处受控中断，重新打开状态库后继续执行，再检查 rewind。报告包括 `report.md`、`summary.json` 和测试摘要。

演示用真实文件、状态库和 Maven 测试检查运行时流程，模型侧为确定性脚本，适合观察执行机制。
