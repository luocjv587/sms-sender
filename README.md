# SMS Sender 邮件转发服务

这是配套 Android 客户端使用的轻量 Vercel API。客户端把 SMTP 配置和邮件内容发送到
`POST /api/forward`，函数通过 Nodemailer 直接连接指定 SMTP 服务器。服务不需要数据库。

## 一键部署到 Vercel

先把本仓库推送到你自己的公开 GitHub 仓库，再将下面链接里的
`YOUR_GITHUB_NAME` 替换为你的 GitHub 用户名：

[![Deploy with Vercel](https://vercel.com/button)](https://vercel.com/new/clone?repository-url=https%3A%2F%2Fgithub.com%2FYOUR_GITHUB_NAME%2Fsms-sender&env=FORWARDER_API_KEY&envDescription=%E7%94%A8%E4%BA%8E%E4%BF%9D%E6%8A%A4%E8%BD%AC%E5%8F%91%20API%20%E7%9A%84%E9%95%BF%E9%9A%8F%E6%9C%BA%E5%AF%86%E9%92%A5)

也可以在 Vercel 控制台导入仓库。无需额外 Build Command；Vercel 会自动识别
`api/forward.ts` 为 Node.js Function。

## 环境变量

在 Vercel 项目的 Settings → Environment Variables 中配置：

- `FORWARDER_API_KEY`：保护接口的长随机密钥，建议至少 32 个随机字节；不要使用 SMTP
  密码，也不要提交到 Git。

修改环境变量后需要重新部署。客户端请求必须携带：

```text
Authorization: Bearer <FORWARDER_API_KEY>
Content-Type: application/json
```

## Android 客户端配置

在 Android 客户端中填写：

- API 地址：`https://你的项目.vercel.app/api/forward`
- API Key：与 Vercel 中 `FORWARDER_API_KEY` 完全一致
- SMTP 主机、端口、加密方式、邮箱账号及 SMTP 专用密码
- 发件人与收件人地址

客户端会把原号码写在邮件正文开头，不放入普通邮件标题。短信包含“验证码、校验码、OTP、
verification code”等关键词时，会选择距离关键词最近的 4–8 位数字或字母数字组合写入标题；
未识别到验证码时标题为“短信转发”。

接口只接受以下严格 JSON schema，不允许额外字段：

```json
{
  "smtp": {
    "host": "smtp.example.com",
    "port": 587,
    "secure": false,
    "user": "sender@example.com",
    "pass": "smtp-app-password"
  },
  "mail": {
    "from": "sender@example.com",
    "fromName": "+86 13800138000",
    "to": "receiver@example.com",
    "subject": "短信转发",
    "text": "消息正文"
  }
}
```

`fromName` 为原短信号码，它会显示为邮件发件人名称；真实 `From` 地址仍必须是 SMTP
邮箱，邮件协议不允许用手机号码作为邮箱地址。`mail.text` 与 `mail.html` 至少提供一个。
`secure: true` 通常用于隐式 TLS（465）；
`secure: false` 会强制 STARTTLS（通常为 587）。出于滥用与安全风险，端口 25 被禁止。

成功响应：

```json
{ "ok": true, "messageId": "..." }
```

失败响应始终使用统一结构：

```json
{ "error": { "code": "INVALID_REQUEST", "message": "..." } }
```

## 常见 SMTP 配置

- Gmail：`smtp.gmail.com`，465/`secure: true` 或 587/`secure: false`；开启两步验证后使用
  应用专用密码。
- Outlook / Microsoft 365：`smtp.office365.com`，587/`secure: false`；租户可能需要管理员
  开启 SMTP AUTH。
- QQ 邮箱：`smtp.qq.com`，465/`secure: true` 或 587/`secure: false`；使用邮箱设置中生成的
  授权码。
- 163 邮箱：`smtp.163.com`，465/`secure: true`；使用客户端授权密码。

供应商策略会变化，请以邮箱供应商最新文档为准。部分服务会拒绝 Vercel 数据中心出口 IP，
或要求预先验证发件地址。

## 安全、隐私与限制

- 请求体最大 16 KiB；邮件头拒绝 CR/LF 注入；SMTP 主机经 DNS 解析后必须全部为公网地址。
  实际连接使用已校验 IP，并保留原主机名做 TLS 证书校验，以降低 SSRF 与 DNS rebinding 风险。
- SMTP 连接、问候和 socket 均设置超时；所有非 465 连接强制升级为 TLS。
- 函数不写数据库、不持久化请求、邮件内容或 SMTP 凭据，代码也不会记录这些数据。数据仍会在
  一次请求的内存中短暂存在，并经 Vercel 与目标 SMTP 服务处理；请同时查看两者的隐私政策。
- 当前限流为每个函数实例、每个来源 IP 每分钟 10 次的内存 best-effort 限流。Serverless
  多实例、冷启动或实例回收会重置/分散计数，它不是全局配额，也不能替代 Vercel WAF、
  Upstash Redis 等集中式限流。
- 本服务只支持单个收件人纯邮箱地址，不支持显示名或收件人列表。函数最长执行时间为 30 秒，
  SMTP 服务响应过慢时会失败。
- API Key 是共享凭据。若将它内置到公开发布的 APK，攻击者可能提取它；面向不受信任用户时，
  应改用用户登录、短期令牌和服务端集中限流。

## 本地开发与验证

要求 Node.js 20 或更高版本：

```bash
npm install
npm test
npm run typecheck
npm run build
```

测试会 mock DNS 与 Nodemailer，不会发送真实邮件，也不会连接真实 SMTP 服务。

## Android CI

`.github/workflows/android-apk.yml` 会在 Android 或工作流相关文件变化时运行单元测试、
Lint 和 `assembleDebug`，并上传名为 `sms-sender-debug-apk` 的 APK artifact。

## 构建与安装 Android App

本地需要 JDK 17 和 Android SDK 35：

```bash
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
```

生成的可安装文件位于 `android/app/build/outputs/apk/debug/app-debug.apk`。将 APK 传到安卓
手机后，允许文件管理器“安装未知应用”即可侧载；系统首次启动时会逐项说明并申请短信接收和
通知权限。部分厂商会限制后台任务，可从首页进入电池优化设置，将本 App 设为允许后台运行。

Debug APK 使用开发调试签名，只适合自用测试。长期使用或分发前，应在
`android/app/build.gradle.kts` 中配置自己的 release keystore，并妥善备份签名文件与密码。

`RECEIVE_SMS` 属于 Google Play 严格限制的敏感权限。本项目定位为私人侧载工具；如果改为
上架 Google Play，通常需要实现完整默认短信应用能力，并通过 Play Console 权限用途审核。
# sms-sender
