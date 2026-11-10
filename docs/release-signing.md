# Release 签名与出包

## 本地出包

构建前需要准备两样不入库的内容：

1. `app/google-services.json`：来自自己的 Firebase 项目（README 也要求放在这里），缺少时 `google-services` 插件会直接失败。
2. `web-ui` 的依赖：首次构建前在 `web-ui` 里执行 `pnpm install --frozen-lockfile`，Gradle 的 `buildWebUi` 只会执行 `pnpm run build`。

签名配置读取仓库根目录的 `local.properties`（已被 git 忽略）：

```properties
sdk.dir=C:/Users/<user>/AppData/Local/Android/Sdk
storeFile=app.key
storePassword=<password>
keyAlias=rikkahub
keyPassword=<password>
```

`storeFile` 以 `app/` 为基准解析，因此 `app.key` 对应 `app/app.key`（`app/.gitignore` 已忽略该文件）。

```bash
pnpm --dir web-ui install --frozen-lockfile
./gradlew assembleGithubRelease
```

产物：`app/build/outputs/apk/github/release/*.apk`。

> 这个 keystore 是应用更新的身份凭证。丢失后无法覆盖安装已发布的版本，务必把 `app/app.key` 和密码备份到仓库之外。

## GitHub Actions（手动触发）

`.github/workflows/release.yml` 不监听 push，也没有任何输入项：在 Actions 页面点 “Run workflow” 即可。
构建完成后在运行记录的 Artifacts 里下载 `rikkahub-github-release`，不需要创建或推送 tag。
`.github/workflows/daily-build.yml` 仍按计划每晚构建。两个工作流都需要以下仓库 Secret：

| Secret | 内容 |
| --- | --- |
| `KEY_BASE64` | `base64 -w0 app/app.key` 的输出（Windows 可用 `certutil -encode` 后去掉首尾行） |
| `SIGNING_CONFIG` | 上面 `local.properties` 的完整内容，不含 `sdk.dir` |
| `GOOGLE_SERVICES_JSON` | `app/google-services.json` 的完整内容 |

`versionCode` / `versionName` 仍取自 `app/build.gradle.kts`，发版前先更新再手动运行。
