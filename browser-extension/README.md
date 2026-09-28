# ABDM Server Chrome 扩展

## 安装与配置

1. 更新并运行包含本仓库 Cookie 转发修复的 ABDM Server。
2. 从 GitHub Release 下载 `abdm-server-chrome-extension-<版本>.zip` 并解压；在 Chrome 打开 `chrome://extensions`，启用“开发者模式”，选择“加载已解压的扩展程序”，选择解压目录。源码开发时可直接选择本目录 `browser-extension`。
3. 点击扩展图标，进入“设置”；填写协议、服务器 IP/主机名、端口（默认 `6868`）和 API Token，保存并测试连接。
4. 配置完成后，默认接管所有普通 HTTP(S) 下载；弹出窗口的开关可以随时停用或启用接管。在设置页可改为“仅接管列表中的扩展名”：已预置 100 多种常见扩展名（含 `sig`、`iso`、`zip`、`exe`、`mp4`、`pdf` 等），可继续添加。模式和列表也随配置同步。
5. 在网页链接上点击右键，选择“使用 ABDM Server 下载链接”，可直接把链接交给服务端；即使自动接管关闭也可以使用。

扩展使用 Manifest V3。配置保存在 `chrome.storage.sync`；Chrome 账号启用扩展设置同步后，Token 也随配置同步。Chrome 扩展存储本身不加密，请只在信任的 Chrome 账号和设备上使用。下载站点的 Cookie 只在下载交接时读取并发给已配置的服务器，不保存在同步配置中。使用 HTTP 连接服务器时，Token 和 Cookie 在传输途中没有 TLS 加密；可用时建议启用 HTTPS。

## 检查 Chrome 账号同步

1. 两台设备都要在 Chrome 登录同一账号，并在同步设置中启用“扩展程序”。**解压加载的扩展本体仍需在另一台设备手动安装。**
2. 在两台设备的扩展设置页检查“扩展 ID”一致，本项目固定 ID 为 `cbmonlpfkenpjmbampjcachcieebjopn`。先前未带固定 `manifest.key` 的开发版可能是另一个 ID；安装新版前请记下旧 IP、端口和 Token，必要时重新填写。
3. 设备 A 在扩展设置页点击“生成测试码”；设备 B 打开相同扩展的设置页，点击“读取测试码”。两端代码与时间一致，证明 `chrome.storage.sync` 中的测试项已跨设备到达。单机读到测试码只证明本地写入成功。
4. 在设备 B 比较服务器地址、接管模式和扩展名列表，并点击“测试连接”。它也成功时，Token 与服务器设置已可用。
5. 如果测试码未到达，打开 `chrome://sync-internals`，看同步状态与扩展设置相关错误；再检查两边的账号、同步开关和扩展 ID。

为了默认覆盖所有网站，扩展请求 HTTP(S) 网站访问权限、Cookie 权限、下载权限和存储权限。服务端自身的下载链接会跳过，避免循环接管。

## 接管行为与边界

- 扩展同时监听下载创建与确定文件名事件，并按下载 ID 去重。在可用时暂缓完成并暂停浏览器任务，再把 URL、文件名、Referer、User-Agent 和匹配的登录 Cookie 交给 ABDM Server。服务端接受后，扩展取消并清除 Chrome 下载记录。失败时恢复 Chrome 下载；如果服务端已接受但 Chrome 取消失败，扩展尝试删除服务端任务后恢复 Chrome。
- 支持 Cookie 登录态的 HTTP(S) GET 文件下载（包括 HttpOnly Cookie）。如需接管隐身窗口下载，需在 Chrome 扩展详情中单独允许；扩展在隐身窗口运行独立进程，读取该窗口独立的 Cookie 存储区。
- Chrome 的文件名确定事件会暂缓下载完成，但网络接收可能已经开始。`blob:`、`data:`、`file:`、POST 表单下载、网页内生成文件，以及依赖浏览器私有请求头或特殊浏览器上下文的下载无法仅凭 URL 和 Cookie 重放，仍由 Chrome 处理。
- 服务器在接受任务后如果网络连接中断，客户端可能无法确认是否创建成功；弹出窗口会提示检查重复任务。此时 Chrome 会继续下载。

## 开发验证

发布打包只包含运行所需文件，ZIP 内根目录就是 `manifest.json`。在仓库根目录运行：

```powershell
python scripts/package-browser-extension.py 1.0.1 dist
```

```powershell
cd browser-extension
node --test tests/*.test.js
```

服务端 Cookie 转发的兼容测试：

```powershell
./gradlew.bat :server:engine-abdm:test --tests '*AbdmCompatibilityTest.session cookies reach the pinned upstream engine'
```

独立 Chrome 配置的浏览器交接和设置页诊断可运行：

```powershell
node tests/chrome-smoke.mjs
$env:SMOKE_TINY='1'; node tests/chrome-smoke.mjs; Remove-Item Env:SMOKE_TINY
```

还需在用户的 Chrome 与实际 ABDM Server 上验收登录站点，以及在两台登录同一账号的设备上验收账号同步。
