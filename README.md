# abdm-server

**简体中文** | [English](README_en.md)

自托管的下载管理器：一个 JVM 进程同时提供 REST/WebSocket API 与打包好的 Web 界面，
面向局域网（LAN-first）部署，默认使用内置的分段下载引擎，也可以切换到
AB Download Manager 作为后端引擎。

- 版本：`0.1.0`
- 仓库：<https://github.com/dixtdf/abdm-server>
- 许可证：[Apache-2.0](LICENSE)
- 上游引擎：[AB Download Manager](https://github.com/amir1376/ab-download-manager)
  `v1.10.4`（子模块，固定 commit，见 [docs/upstream.md](docs/upstream.md)）

---

## 功能特性 Features

- **多连接分段下载**：单任务 1–256 连接，range 规划 + 已完成区间检查点（interval set），
  边下边改连接数、断点续传、重启恢复走同一条路径。
- **任务队列与并发控制**：`maxConcurrentDownloads` 控制同时下载数，其余任务排队并报告
  `queuePosition`。
- **实时进度**：进度通过 WebSocket 推送（`download.progress` / `download.state` / …），
  按 `progressIntervalMs` 节流；前端不再轮询 REST。
- **每个连接的分片视图**：与 ABDM 桌面端一致的“连接 / 分片”表（`#` / 状态 / 已下载 / 总大小 / 速度），
  通过 `download.parts` 帧推送；下载中改连接数会重划分片，已下载字节保留、数字不归零。
- **限速**：全局 `globalSpeedLimit` 与单任务 `speedLimit`。
- **HLS、校验和、代理、自定义请求头 / Cookie / Referer / User-Agent**：按任务粒度配置。
- **持久化**：SQLite（WAL）保存任务、进度检查点、历史记录与设置，
  进程重启后自动恢复（可配置）。
- **引擎可插拔**：内置 `engine-native`，或编译期切换到 `engine-abdm` 适配层
  （`-Pabdm.enabled=true`）。API、数据库与前端都不感知引擎差异。
- **多语言界面**：`en-US` / `zh-CN`，错误码与文案分离（见 [docs/i18n.md](docs/i18n.md)）。
- **多架构镜像**：`linux/amd64` + `linux/arm64`。

## 界面 Interface

仓库有意**不附带截图**：界面由 `web/`（Vue 3 + Vite）构建，布局为任务列表 + 实时进度条 +
历史记录 + 设置页，所有文案来自 `web/src/locales/*.json`，可直接在本地
`npm run dev` 预览，无需依赖文档里的图片。

---

## 快速开始 Quick start

### 方式一：Docker Compose（推荐）

1. 在仓库根目录创建 `.env`：

   ```dotenv
   IMAGE_OWNER=dixtdf        # 或你自己的 fork
   IMAGE_TAG=latest          # 或 edge / 0.1.0
   DOWNLOAD_HOST_PATH=/mnt/downloads
   AUTH_MODE=none            # 或 token
   AUTH_TOKEN=               # AUTH_MODE=token 时填写
   TZ=Asia/Shanghai
   PORT=6868
   ```

2. 首次运行准备目录（镜像以 uid 1000 的非 root 用户运行，**不需要 privileged**；
   宿主机目录必须让 uid 1000 可写）：

   ```bash
   mkdir -p config
   sudo chown -R 1000:1000 config       # /downloads 同理
   docker compose up -d
   docker compose logs -f downloader
   ```

   目录权限不对时服务会直接拒绝启动，并打印上面这条命令。

3. 验证（容器内置同样的健康检查）：

   ```bash
   curl -fsS http://127.0.0.1:6868/api/v1/health
   # {"status":"ok","uptimeSeconds":3,"version":"0.1.0","engine":"native"}
   ```

4. 打开 `http://<局域网 IP>:6868`。

使用本地构建的镜像：`docker build -t abdm-server:local .`，然后按
`docker-compose.yml` 顶部注释里的 override 方式把 `image` 指向 `abdm-server:local`。

### 方式二：本地开发（前后端分开）

前置条件：JDK 17、Node 22+。默认（`native` 引擎）不需要别的东西；如果要编译
`engine-abdm` 适配层，还需要 JDK 25（上游固定 `jvm.toolchain=25`）和 Android SDK，
并先用 `bash scripts/build-abdm-bridge.sh` 导出上游运行时（见下文）。

后端（默认 `native` 引擎，端口 6868）：

```bash
# Linux / macOS
./gradlew :server:app:run

# Windows
.\gradlew.bat :server:app:run
```

前端开发服务器（Vite，默认 5173，代理到 6868）：

```bash
cd web
npm ci
npm run dev
```

打成单个 fat jar 后直接运行：

```bash
./gradlew :server:app:shadowJar
java -jar server/app/build/libs/abdm-server-*-all.jar
```

本地跑的时候可用环境变量覆盖目录，例如：

```bash
ABDM_CONFIG_DIR=./.local/config ABDM_DOWNLOAD_ROOT=./.local/downloads ./gradlew :server:app:run
```

---

## 配置 Configuration

### 环境变量 Environment variables

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PORT` | `6868` | HTTP/WebSocket 监听端口 |
| `ABDM_CONFIG_DIR` | `/config` | SQLite 数据库与运行期状态目录（必须可写） |
| `ABDM_DOWNLOAD_ROOT` | `/downloads` | 下载根目录，同时是路径安全边界（必须可写） |
| `ABDM_AUTH_MODE` | `none` | `none` 或 `token`；`none` 时启动会打印醒目告警 |
| `ABDM_AUTH_TOKEN` | 空 | `AUTH_MODE=token` 时必填；除 `/api/v1/health` 外所有路由要求 `Authorization: Bearer <token>`，WebSocket 额外接受 `?token=<token>` |
| `ABDM_WEB_DIR` | `/app/web` | 前端构建产物目录；镜像内固定为 `/app/web` |
| `ABDM_ENGINE` | `native` | `native`（内置引擎）或 `abdm`（需以 `-Pabdm.enabled=true` 构建） |
| `ABDM_LOG_LEVEL` | `info` | 日志级别（`debug` / `info` / `warn` / `error`） |
| `TZ` | `Asia/Shanghai` | 容器时区，仅影响日志与展示 |

### 服务端设置 Server settings

这些设置通过 `GET/PUT /api/v1/settings` 读写，持久化在 SQLite 中：

| 设置 | 默认值 | 说明 |
| --- | --- | --- |
| `downloadRoot` | `/downloads` | 唯一可写根目录，越界路径返回 `PATH_OUTSIDE_DOWNLOAD_ROOT` |
| `defaultFolder` | `/downloads` | 新建任务未指定 `folder` 时的目标目录 |
| `defaultConnections` | `8` | 新建任务未指定 `connections` 时的连接数（1–256） |
| `maxConcurrentDownloads` | `3` | 同时下载的任务数上限，超出部分排队 |
| `globalSpeedLimit` | `0` | 全局限速，字节/秒，`0` 表示不限速 |
| `resumeOnStartup` | `true` | 启动时是否自动恢复未完成任务（否则停在 `PAUSED`） |
| `progressIntervalMs` | `500` | WebSocket 进度推送节流间隔 |
| `authMode` | `none` | 与 `ABDM_AUTH_MODE` 对应 |
| `maxConnections` | `256` | 单任务连接数硬上限 |

界面语言与主题是浏览器偏好（`localStorage` 的 `ui.locale`、`ui.theme`），不是服务端设置。

---

## API 摘要 API summary

完整契约（端点、请求体、状态机、错误码、WebSocket 帧）以
**[docs/api.md](docs/api.md)** 为唯一事实来源，前端与 `server:web-api` 都遵循它。
基址 `/api/v1`，错误统一为 `{"error":{"code":"...","params":{...}}}`，后端不返回本地化句子。

| 分组 | 端点 |
| --- | --- |
| 健康与版本 | `GET /health`、`GET /version` |
| 设置 | `GET /settings`、`PUT /settings` |
| 目录浏览 | `GET /directories?path=/downloads` |
| 任务 | `GET /downloads`、`POST /downloads`、`GET /downloads/{id}`、`DELETE /downloads/{id}?deleteFile=` |
| 任务控制 | `POST /downloads/{id}/start`、`/pause`、`/resume`、`PATCH /downloads/{id}/connections` |
| 历史 | `GET /history?limit=100`、`DELETE /history` |
| 实时事件 | `WS /events`（首帧 `hello` 携带全量任务，之后为增量事件） |

---

## 安全模型 Security model

本项目定位是**局域网自托管**，不是公网服务。

- **默认 `AUTH_MODE=none`**：任何能访问 6868 端口的人都能操作下载任务，启动时会打印告警。
  只要不是完全可信的网段，就设置 `AUTH_MODE=token` + 足够长的随机 `ABDM_AUTH_TOKEN`。
- **不要直接暴露到公网**。需要远程访问时，使用以下任一方式：
  - Tailscale / WireGuard 等私有网络（推荐，无需开放端口）；
  - 反向代理（Caddy / Nginx）挂 TLS + HTTP Basic 或 `AUTH_MODE=token`，并且只监听内网地址。
- **路径安全**：所有可写路径都经 `PathGuard` 归一化并校验必须落在 `downloadRoot` 之内，
  `../` 穿越与绝对路径逃逸一律返回 `PATH_OUTSIDE_DOWNLOAD_ROOT`；`/config` 不可通过 API 访问。
- **容器最小权限**：镜像以非 root 用户 `abdm`（uid 1000）运行，只暴露 6868，
  运行期不含 Node/npm/Gradle/源码。
- **单写者**：SQLite 采用 WAL，`/config` 目录同时只能被一个服务实例使用。
- **令牌不进日志**：`ABDM_AUTH_TOKEN` 由环境注入，不要写进 `docker-compose.yml` 提交到仓库。

---

## 目录结构 Repository layout

```
server/app          Ktor 启动、依赖装配、静态资源
server/web-api      REST + WebSocket 路由与 DTO 映射（契约见 docs/api.md）
server/scheduler    队列、并发、状态机、重启恢复
server/engine-api   引擎端口：DownloadEngine / EngineEvent / PathGuard / 存储接口
server/engine-native 内置分段下载引擎（默认，始终参与构建）
server/engine-abdm   AB Download Manager 适配层（可选，abdm.enabled=true）
server/persistence  SQLite（WAL）存储
web/                Vue 3 + Vite 前端
third_party/ab-download-manager   上游子模块（只读，从不修改）
scripts/            update-abdm.sh / check-abdm.sh / build-release.sh
docs/               api.md / architecture.md / i18n.md / upstream.md
```

架构分层、线程与协程模型、重启恢复流程见 [docs/architecture.md](docs/architecture.md)。

---

## 上游引擎与子模块 Upstream

- 固定 `AB Download Manager v1.10.4`，commit `afc57634b3c121c6415213242b2b600cccc6fd6e`。
- 默认构建 `-Pabdm.enabled=false`：适配层编译为 stub，服务只用内置引擎，**不需要 Android SDK**。
- `-Pabdm.enabled=true` 才会真正编译适配层，并且它编译的是 `scripts/build-abdm-bridge.sh`
  导出到 `third_party/abdm-dist/` 的上游运行时 jar（导出那一步需要 JDK 25 + Android SDK）。
- `third_party/ab-download-manager` **永远不修改**；所有适配代码都在 `server/engine-abdm/`。
- 升级流程、兼容性检查清单见 [docs/upstream.md](docs/upstream.md)。

脚本（Bash + `set -euo pipefail`，首次使用请赋予执行权限；Windows 请通过
Git Bash 或 WSL 运行）：

```bash
chmod +x scripts/*.sh          # Linux / macOS / Git Bash

scripts/check-abdm.sh          # 校验子模块干净且在固定 commit 上
scripts/update-abdm.sh v1.10.5 # 移动 pin，打印 old -> new commit
scripts/build-abdm-bridge.sh   # 从上游导出运行时 jar 到 third_party/abdm-dist
scripts/build-release.sh       # 构建前端 + fat jar，生成 dist/SHA256SUMS
```

---

## 构建与验证 Commands used by CI

后端：

```bash
./gradlew build -Pabdm.enabled=false                                     # 编译 + 单元测试
bash scripts/build-abdm-bridge.sh                                        # 导出上游运行时（JDK 25 + Android SDK）
./gradlew -Pabdm.enabled=true :server:engine-abdm:test                   # AbdmCompatibilityTest
./gradlew --no-daemon -Pabdm.enabled=false :server:app:shadowJar         # 生成 fat jar
```

前端（在 `web/` 下）：

```bash
npm ci
npm run i18n:check     # 文案与错误码完整性门禁
npm run lint
npm run typecheck
npm run test
npm run build          # 产出 web/dist
```

容器：

```bash
docker build -t abdm-server:local .
docker run --rm -p 6868:6868 -v "$PWD/config:/config" -v /mnt/downloads:/downloads abdm-server:local
curl -fsS http://127.0.0.1:6868/api/v1/health
```

CI 工作流：`.github/workflows/ci.yml`（backend / frontend / docker / abdm-compat）、
`docker.yml`（每次推送 main 发布 `:edge`，tag 发布正式镜像）、
`release-manual.yml`（手动输入版本号发版，见「发布」章节）、
`release.yml`（推送 tag 时发版）、`upstream-check.yml`（每周检查上游并开 PR）、`codeql.yml`。

### 本地验收（端到端）

仓库自带一套可重复的真实验收脚本，覆盖需求书 V1 验收清单里的核心项：
分段下载、**下载过程中动态修改连接数**（8 → 64 → 256 → 16）、
暂停 → 进程重启 → 续传、1/64/256 连接结果一致，全部以 SHA-256 比对收尾。

```bash
# 1) 起一个支持 Range 的本地文件服务器（可加 --throttle 限速，便于观察/暂停）
node scripts/test-http-server.mjs ./big.bin 9100 --throttle 262144

# 2) 起服务（默认端口 6868，或按需指定）
./gradlew :server:app:fatJar
java -jar server/app/build/libs/abdm-server-*-all.jar

# 3) 一键跑完整验收（Windows PowerShell；脚本自己起文件服务器与 jar）
powershell -ExecutionPolicy Bypass -File scripts/acceptance-test.ps1 -FileSizeMb 256
```

`scripts/acceptance-test.ps1` 会依次验证：1/8/64/256 连接下载、下载中改连接数、
暂停 → 重启 → 续传、校验和一致，并打印每一步的实测结果。

单元测试侧，`server/engine-native` 里的 `NativeDownloadEngineTest` 用同一个思路在进程内跑
（自带 Range 服务器），其中 `a restart resumes from the sqlite checkpoint instead of starting over`
就是“重启后续传”的回归测试；`AbdmCompatibilityTest` 则用真实 ABDM 引擎跑同一组场景。

---

## 发布 Releasing

发布走 GitHub Actions 的**手动流程**（`.github/workflows/release-manual.yml`）：
输入版本号即可，默认对 `main` 打 tag，并同时推镜像到 GHCR、把二进制传到 Release。

1. Actions → **Release (manual)** → Run workflow
2. 填写输入：

| 输入 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `version` | 是 | – | 语义化版本，如 `0.2.0`（允许写成 `v0.2.0`） |
| `ref` | 是 | `main` | 要构建并打 tag 的分支 / tag / commit |
| `prerelease` | 否 | `false` | 标记为预发布 |
| `push_latest` | 否 | `true` | 是否同时移动 `:latest` 镜像标签 |
| `dry_run` | 否 | `false` | 只验证与构建，不推镜像、不打 tag、不发 Release |

3. 流程依次执行：校验版本与重名 tag → checkout `ref`（含子模块）→ 后端 `build`、
   前端 `i18n:check / lint / typecheck / test` → 构建带版本号的 fat jar（`-Pproject.version=`，
   并用 `java -jar server.jar --print-version` 复核）→ 生成 `SHA256SUMS` →
   推送多架构镜像（`linux/amd64` + `linux/arm64`）→ 打 tag → 创建 Release
   （附件 `server.jar`、`docker-compose.yml`、`SHA256SUMS`）。

产物：

```text
ghcr.io/dixtdf/abdm-server:0.2.0     # 版本
ghcr.io/dixtdf/abdm-server:0.2       # major.minor
ghcr.io/dixtdf/abdm-server:latest    # push_latest=true 时
ghcr.io/dixtdf/abdm-server:edge      # 每次推送 main 自动发布（docker.yml）
tag: v0.2.0（打在 ref 指向的提交上）
```

两点说明：

1. tag 是**最后一步**创建的——验证或镜像构建失败不会留下半成品 tag。
2. 由 `GITHUB_TOKEN` 推送的 tag 不会触发其他 workflow，所以 Release 由本流程自己创建。
   如果你更习惯手动打 tag，效果等价：

   ```bash
   git tag v0.2.0 && git push origin v0.2.0   # 触发 release.yml
   ```

本地自检可用与环境无关的方式复核版本号确实进了产物：

```bash
./gradlew -Pabdm.enabled=false -Pproject.version=0.2.0 :server:app:fatJar
java -jar server/app/build/libs/abdm-server-*-all.jar --print-version   # -> 0.2.0
```

改动版本号（一次改完 Gradle 版本目录、前端 `package.json`/`package-lock.json`、
服务端与引擎的回退版本、两个 README；其余地方都从这些地方派生）：

```powershell
# 先看要改什么，不写文件
powershell -ExecutionPolicy Bypass -File scripts/set-version.ps1 0.2.0 -DryRun

# 真改，并可选提交/打 tag/推送
powershell -ExecutionPolicy Bypass -File scripts/set-version.ps1 v0.2.0 -Commit -Tag
```

---

## 致谢 Acknowledgements

特别鸣谢 **AB Download Manager（ABDM）及其全部贡献者**：本项目的下载核心、分片模型，以及
“连接 / 分片”视图的交互设计，都源自他们的工作；`engine-abdm` 直接复用其运行时，
没有 ABDM 就不会有这个项目。也感谢 Kotlin、Ktor、SQLite JDBC、Vue、Vite 等开源社区。

---

## 许可证 License

[Apache License 2.0](LICENSE)。第三方组件与上游引擎的归属见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

**This project is not affiliated with or endorsed by AB Download Manager.**
本项目与 AB Download Manager 项目方无隶属关系，也未获得其背书或认证。
