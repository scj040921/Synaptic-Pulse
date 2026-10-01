# Synaptic Pulse · 隙湃

面向校园的社交应用，围绕兴趣交友、社区动态、校园地图和线下活动展开。用户端使用 uni-app x，服务器端使用 Java 和 Spring Boot。目前支持桌面浏览器和 Android，桌面安装包暂未提供。

## 功能

- **社区动态**：纯文字或图文发帖，最多九张图片，九宫格展示；支持话题、搜索、点赞、评论和草稿。电脑端可直接拖入图片。
- **校园地图**：搜索地点、收藏、查看附近活动；在地图任意位置标记后发帖或发起活动，支持点击选点和拖动调整。
- **校园活动**：填写时间、地点和人数上限，报名、取消报名，查看附近的活动。
- **个人资料与推荐**：设置头像、院系专业、分类兴趣和自定义标签；补充性格、交友目的、交流方式、活动人数与空闲时间偏好。推荐展示共同兴趣和对方资料。
- **私聊**：发送文字、图片、表情包和 GIF，查看历史消息、未读数，搜索聊天内容，管理屏蔽名单。
- **社交练习**：按场景和难度练习对话，查看表达建议与成长记录；可选接入模型服务，辅助练习和发布文案。
- **设置与外观**：切换经典或 Liquid Glass 风格，调整聊天自动刷新、服务器地址，清理本机草稿。背景模糊效果取决于平台支持。

## 项目结构

```text
backend/                 服务器端
  src/main/java/         接口与业务逻辑
  src/main/resources/    数据库结构、配置与地图地点数据
  src/test/java/         接口与功能测试
client/                  用户端
  pages/                 页面
  components/            公共组件
  common/                请求封装、设置和地图通信
  static/campus/         地图页面、矢量底图与依赖
  legacy/                未注册的旧示例页面
docs/                    接口、地图与开发文档
scripts/                 地图数据导入工具
start-dev.ps1            Windows 开发启动脚本
```

数据库文件和上传图片保存在 `backend/data/`，不随源代码提交。

## 本地运行

准备 JDK 17 或更新版本、HBuilderX 5.21 或更新版本。项目启用了 uni-app x 蒸汽模式，Android 端需要对应版本的 HBuilderX。后端自带 Maven Wrapper，无需另装 Maven。

### 服务器端

在项目根目录打开 PowerShell：

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

首次启动会下载依赖。默认接口地址为 `http://localhost:8088/api`，健康检查地址为 `http://localhost:8088/api/health`。

### 用户端

在 HBuilderX 中导入 `client` 目录，选择“运行到浏览器”。Web 地址以运行窗口输出为准；登录页和设置页可以修改服务器地址。

Android 开发需配置 Android SDK，然后选择“运行到 Android App 基座”：

- Android 模拟器连接电脑后端使用 `http://10.0.2.2:8088`。
- 真机使用电脑的局域网地址，例如 `http://192.168.1.8:8088`，并确保两端可以互相访问。
- 正式打包需配置自己的 DCloud AppID 与应用签名。

Windows 下也可以使用启动脚本，同时启动后端并打开用户端工程：

```powershell
.\start-dev.ps1 -HBuilderX 'D:\HBuilderX\HBuilderX.exe'
```

路径替换为实际安装位置，或通过 `HBUILDERX_PATH` 环境变量指定。脚本的 `-Android` 选项会启动名为 `SynapticPulse_API35` 的模拟器，使用前需先创建该 AVD 并设置 `ANDROID_HOME`。

## 配置

服务器端从环境变量读取配置，默认使用 H2 文件数据库。

| 变量 | 说明 |
| --- | --- |
| `PORT` | 接口端口，默认 `8088` |
| `DB_URL` | 数据库地址，默认 `jdbc:h2:file:./data/synaptic;DB_CLOSE_ON_EXIT=FALSE` |
| `DB_USER` / `DB_PASSWORD` | 数据库账号与密码，默认账号 `sa`、空密码 |
| `UPLOAD_DIR` | 图片目录，默认 `./data/uploads`，相对于后端工作目录 |
| `CORS_ORIGINS` | 允许访问接口的 Web 来源，逗号分隔；默认允许 localhost 的 8080、8081、5173、5174 端口 |
| `AI_BASE_URL` | 兼容 OpenAI 的接口地址，默认 `https://api.openai.com/v1` |
| `AI_MODEL` / `AI_API_KEY` | 模型名称和密钥，可选 |

未配置模型时，练习与文案辅助提供本地规则建议。调用模型前需用户确认，发送内容仅限当前练习对话或待发布文案。密钥只配置在服务器端。

## 开发文档

- [接口说明](docs/API.md)
- [校园地图与数据来源](docs/CAMPUS_MAP.md)
- [功能对照与验证记录](docs/FEATURE_ALIGNMENT.md)
- [开发计划](docs/ROADMAP.md)
- [第三方组件与地图数据许可](client/static/campus/THIRD_PARTY.md)

运行已有后端测试：

```powershell
cd backend
.\mvnw.cmd test
```

## 开发状态

项目处于开发阶段。兴趣推荐采用共同标签的 Jaccard 分数，校园身份认证、群聊和路径导航尚未接入。地图初始数据覆盖广州大学大学城校区，来自 OpenStreetMap；附近距离为直线距离。

图片目前保存在服务器本地磁盘。正式部署还需完善身份认证、内容审核、存储备份和 HTTPS 配置。Android 真机定位与跨端外观仍需继续验收。
