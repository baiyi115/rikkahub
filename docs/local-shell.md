# Android 本机 Shell

在助手的本地工具中启用“本机 Shell”，即可让聊天 Agent 使用 `local_shell` 和 `local_shell_job`，不需要新建工作区或打开终端页面。

## 身份与审批

- 默认使用应用 UID。“以 root 运行”只改变默认身份，模型仍可通过 `identity` 显式选择 `user` 或 `root`。
- root 通过设备的 `su` 执行，需要超级用户管理器授权。“发现 su”不等于“已获授权”；`check_root` 通过 `id -u` 检查本次授权，不维护全局授权状态。
- “执行前询问”默认开启；关闭后，不再要求应用内逐次批准执行及启动后台任务。系统超级用户授权不受此开关控制。
- 需要 Android 自带的 `sh` 和 `setsid`。缺少进程隔离能力时直接报错，不静默降级为无法可靠回收的进程。

## 前台执行与持久会话

- 不传 `session_id` 时，每次在新 Shell 中执行；结束时清理残留进程组。
- `open` 创建持久会话，`exec` 复用目录、变量等 Shell 状态，`close` 关闭，`list` 只列当前聊天的会话。
- 会话按聊天归属校验；每个聊天最多 4 个，全应用最多 16 个。删除聊天或助手会清理对应持久会话。
- 超时、取消执行会关闭整个会话，而不是尝试保留可能已经损坏的 Shell 状态。停止回复不会停止之前显式启动的后台任务。
- stdin 为 `/dev/null`，可使用管道、重定向及 heredoc；不提供 PTY、交互式输入或密码提示处理。
- 描述符 **3** 保留给完成协议。可以执行 `exec >文件` 等 stdout/stderr 重定向，但不能关闭或重定向描述符 3。不要修改 Shell 的输入解析模式；`exit`、替换 Shell 的 `exec`、`set -e` 导致的退出可能结束会话。
- 一次性执行分别返回 stdout/stderr，持久会话合并二者。每个结果流最多保留 32768 个字符，不是 32768 字节；超出部分丢弃并标记 `truncated`。需要完整大输出时，命令应主动重定向到文件。

## 后台任务

- `local_shell_job` 复用现有后台任务面板、前台服务、日志和完成通知。
- 任务在回复完成后继续运行；停止任务或删除聊天会终止任务进程。
- 任务以命令 Shell 的生命周期为准：父 Shell 结束后，先清理其残留进程组，再报告任务结束。长任务应直接运行，或自行 `wait` 等待子任务，不要用裸 `&` 启动后立即退出父 Shell。
- 不支持通过 `setsid` 等方式主动脱管的 daemon，也不保证管理应用重启前的进程。系统强杀、设备关机、root 授权被撤销时，不能保证完成清理。
- root 进程回收使用 root 身份读取 `/proc`，但不同超级用户实现和 SELinux 策略仍需真机验证。
- 使用 Android 自带的工具；不会自动安装 bash、Python、Node 或完整 Linux 用户空间。

## 回归测试

平台无关测试覆盖增量完成协议、有界输出、审批开关和日志读取。`LocalShellRunnerTest` 另外在 Linux 的真实 `sh` / `setsid` 上验证会话隔离、状态保留、重定向、取消、超时和子进程回收；Windows 没有这些程序时跳过该组进程测试。

```bash
./gradlew :app:testGithubDebugUnitTest --tests '*ShellOutputCollectorTest' --tests '*LocalShellRunnerTest' --tests '*LocalShellToolTest' :workspace:testDebugUnitTest --tests '*BackgroundCommandLogTest'
```

进程测试中的 `su` 是转发参数的测试脚本，不会申请宿主机 root。这不能替代 Android mksh、Magisk、KernelSU 或 APatch 的真机测试。
