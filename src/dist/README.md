# PaiCLI 1.2.0 distribution

PaiCLI is a Java 21 terminal Coding Agent. This extracted distribution is self-contained except for the Java runtime and the Provider you choose; Maven is not required.

## 1. Verify Java and the launcher

```powershell
# Windows PowerShell
java -version
.\bin\paicli.ps1 --version
.\bin\paicli.ps1 doctor
```

```bat
rem Windows cmd
java -version
bin\paicli.cmd --help
```

```sh
# macOS / Linux
java -version
./bin/paicli --version
./bin/paicli doctor
```

`doctor` is read-only and does not send a Provider request or print a secret.

## 2. Choose the target workspace

PaiCLI treats the current working directory as the project it may inspect or modify. To work on another project, change to that project and invoke this distribution by absolute path:

```powershell
cd D:\path\to\target-project
D:\tools\paicli\bin\paicli.ps1 --help
```

Configuration and durable state then stay under `D:\path\to\target-project\.paicli\`, not beside the PaiCLI binary.

## 3. Configure a Provider

Create `<target-workspace>/.paicli/config.json`. This DeepSeek example contains references only, never the secret value:

```json
{
  "provider": "openai",
  "provider_preset": "deepseek",
  "base_url": "https://api.deepseek.com",
  "model": "deepseek-v4-flash",
  "api_key_env": "DEEPSEEK_API_KEY",
  "credential_ref": "PaiCLI/provider/deepseek/default",
  "thinking": "enabled",
  "reasoning_effort": "max",
  "approval_mode": "on-request"
}
```

`provider: openai` means the Generic OpenAI-compatible Chat Completions adapter; `provider_preset: deepseek` adds the DeepSeek continuation contract. Neither setting implements OpenAI Responses/Codex.

For a temporary PowerShell credential override:

```powershell
$env:DEEPSEEK_API_KEY = '<temporary-value>'
```

Do not commit the value. In the interactive shell, `/provider login` uses masked input and Windows Credential Manager; `/provider credential status` reports the source without printing the credential. Unsupported persistent credential stores fail closed.

Ollama can be selected with `"provider": "ollama"`; its default endpoint/model are `http://localhost:11434` and `qwen3:14b`. The Ollama adapter is retained for compatibility/regression and is not the v1.2 final live acceptance target. The `mock` Provider is development-only and public tasks reject it.

## 4. Start PaiCLI

Run the launcher with no arguments:

```powershell
.\bin\paicli.ps1
```

Then verify readiness and submit a task:

```text
/provider status
/provider credential status
/provider test
Read README.md and summarize this project.
```

Common commands are `/help`, `/provider`, `/model`, `/mode`, `/plan`, `/diff`, `/tasks`, `/resume`, `/continue`, `/rewind`, `/verify`, `/report`, `/compact`, `/doctor`, and `/exit`.

`/plan` shows the bounded summary for the bound durable Plan revision. `/plan --details` verifies and displays its full canonical detail. Plan confirmation never grants Tool authority; writes and high-risk commands still pass workspace, safety, and approval checks.

The fixed offline deterministic demo is available without a real Provider:

```powershell
.\bin\paicli.ps1 demo core --headless
```
