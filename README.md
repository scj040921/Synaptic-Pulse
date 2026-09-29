# Synaptic Pulse 隙湃

基于原有 uni-app x demo 继续开发的广州大学校园社交项目。当前完成第一阶段源码：Java API、持久化账号、兴趣推荐、社区动态、私聊、屏蔽和可配置的 AI 社交练习。后续研究功能与验收顺序见 [开发路线](docs/ROADMAP.md)。

## 目录

| 路径 | 用途 |
| --- | --- |
| `backend/` | Spring Boot 3.5 + Java 17 API；H2 本地数据库 |
| `DC/` | 在原 demo 上改造的 uni-app x 前端；Android 与 Web 共用页面 |
| `DC/common/api.js` | API 地址、登录令牌与请求封装 |
| `DC/legacy/` | 原 demo 中未接入实际二维码能力的页面备份，不参与页面注册 |
| `docs/` | 阶段计划、接口与验收说明 |

## 在 Windows 上运行

1. 安装 JDK 17。进入 `backend`，执行 `./mvnw.cmd spring-boot:run`。首次运行会下载 Maven 及依赖。健康检查为 `http://localhost:8088/api/health`。
2. 使用 **HBuilderX 5.21 或更新版本**打开 `DC` 目录，选择“运行到浏览器”。Windows 端使用 Chrome 或 Edge 打开 Web 版。后端默认端口为 8088，Web 开发服务器常用 8080，二者不会占用同一端口。
3. Android 模拟器运行同一 `DC` 工程时，API 默认地址为 `http://10.0.2.2:8088`。真机需在登录页填写电脑的局域网地址，例如 `http://192.168.1.8:8088`；手机与电脑需要能相互访问。正式部署应改为 HTTPS 域名。
4. 在登录页创建两个体验账号，分别设置兴趣标签，即可检验推荐、动态与双向聊天。

项目启用了 [uni-app x 蒸汽模式](https://doc.dcloud.net.cn/uni-app-x/app-vapor.html)，以便 Android 端运行现有 JavaScript 页面；此模式的 Android 构建需要 HBuilderX 5.21+。本机现有的 HBuilderX 4.55 无法完成该模式的 Web/Android 编译，命令行发行也未生成 Web 产物。前端已通过 Vue 单文件组件语法检查，但 Web 与 Android 真机编译仍需在新版 HBuilderX 验收。Android 正式打包还需要项目自己的 DCloud AppID 与签名。**Windows 支持方式目前是浏览器 Web 版，没有原生 Windows 安装包。**

### 配置

后端通过环境变量配置。默认 H2 文件位于 `backend/data/`，适合本地开发。

| 变量 | 用途 |
| --- | --- |
| `PORT` | API 端口，默认 8088 |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | 数据库连接 |
| `CORS_ORIGINS` | Web 允许来源，逗号分隔；默认允许本机 8080、8081、5173 |
| `AI_BASE_URL` | OpenAI 兼容接口前缀，默认 `https://api.openai.com/v1` |
| `AI_MODEL` / `AI_API_KEY` | 社交练习使用的模型和密钥；为空时接口明确返回 503 |

不要把 API 密钥写进前端或提交到仓库。练习页要求用户逐次同意，服务端只向模型发送当前场景和本次输入，不读取站内聊天与个人资料。

## 验证

在 `backend` 执行 `./mvnw.cmd test`。集成测试覆盖：未登录拦截、注册、兴趣匹配、发布、私聊、屏蔽、AI 同意与未配置状态。接口概要见 [API 文档](docs/API.md)。

## 当前边界

- 推荐采用共同兴趣的 Jaccard 分数，可解释、可运行；文档中的 Transformer/GNN、联邦学习和实时推理尚处研究阶段。
- 体验账号未进行广州大学身份核验，不能用于真实校园用户开放注册。H2、前端本地令牌存储和 HTTP 开发地址也仅适合本地原型。
- 250 米位置波纹、匿名层、AR、协作任务、脉冲积分与成就尚未实现。上线前还需要内容审核、举报、限流、数据保留与隐私合规设计。
- 本阶段未复制第三方 GitHub 仓库代码，保持原 demo 的 uni-app x 结构与 Java 后端清晰可控。
