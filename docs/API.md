# API 接口概要

基础地址 `http://localhost:8088/api`。除注册、登录与健康检查外，请求头需携带 `Authorization: Bearer <token>`。错误响应形如 `{ "error": "说明" }`。

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/health` | 健康检查 |
| POST | `/auth/register` | 注册 `{username,password,displayName}` |
| POST | `/auth/login` | 登录 `{username,password}`；返回令牌及用户资料 |
| POST | `/auth/logout` | 退出当前会话 |
| GET / PUT | `/me` | 获取 / 更新本人资料；更新体含 `{displayName,bio,interests,portrait?}`，响应包含 `avatarUrl` 和 `portrait` |
| PUT | `/me/avatar` | 设置头像 `{imageUrl}`；图片须由本人上传 |
| GET | `/users/{id}` | 获取用户公开资料 |
| GET | `/profile/options` | 返回画像维度 `{groups:[{key,title,max,options}],interests:[...]}`，需登录；前端和后端校验使用同一选项目录 |
| GET | `/matches` | 同校体验账号的共同兴趣排序，最多 20 人 |
| GET / POST | `/posts` | `?q=` 搜索最近 50 条动态 / 发布 `{content,imageUrls,topics,locationName,latitude?,longitude?}`（图片最多 9 张，话题最多 5 个） |
| GET | `/posts/{id}` | 获取单条可见动态，包括图片、评论数、点赞和可选坐标；双向屏蔽规则同列表 |
| PUT / DELETE | `/posts/{id}/like` | 点赞 / 取消点赞，重复点赞不会增加计数 |
| DELETE | `/posts/{id}` | 删除本人动态，同时删除评论和点赞关联 |
| POST | `/posts/assist` | `{prompt,mode:"offline"或"ai",consent}`；返回 `{draft,topics,source}` |
| POST | `/uploads/images` | 上传一张图片（multipart 字段 `file`；返回 `{url}`），用于帖子、头像或聊天 |
| GET | `/posts/{postId}/comments` | 获取动态评论 |
| POST | `/posts/{postId}/comments` | 发表评论 `{content}`，最多 500 字 |
| GET | `/chats` | 会话列表，包含最近消息、时间和 `unreadCount` |
| GET / POST | `/chats/{peerId}/messages` | 最近 50 条消息；`?before=id` 查看更早消息，`?q=` 搜索文字 / 发送文字 `{content}`、图片 `{type:"image",imageUrl}`、内置表情 `{type:"sticker",content}` 或本地表情包 `{type:"sticker",imageUrl}`；可携带 `clientId` 实现重试去重 |
| POST | `/chats/{peerId}/read` | `{lastReadId}` 更新已读位置，旧位置不会覆盖新位置 |
| GET | `/blocks` | 本人屏蔽列表 |
| POST / DELETE | `/blocks/{peerId}` | 屏蔽 / 取消屏蔽 |
| POST | `/training/reply` | `{scenario,message,consent:true}`；调用已配置模型 |
| GET | `/capabilities` | 当前服务能力 `{ai:boolean}` |
| POST | `/practice/respond` | `{scenario,message,level,mode,consent,practiceId,history}`；分级多轮练习，返回 `{reply,tips,source,practiceId}` |
| GET | `/me/stats` | 发帖、练习、双向对话计数，以及脉冲、成就、各难度练习次数 |
| GET / POST | `/events` | `?q=` 搜索同校活动 / 创建 `{title,content,locationName,startsAt,capacity,placeId?,latitude?,longitude?}`；关联地点后服务端使用目录中的名称，响应包含 `placeId`、`latitude/longitude`；任意位置可不传 placeId，传成对 WGS84 坐标 |
| GET | `/events/{id}` | 获取单条同校、未相互屏蔽的活动，包括标记坐标 |
| PUT / DELETE | `/events/{id}/signup` | 报名 / 取消报名；容量检查与插入在同一事务中完成 |
| DELETE | `/events/{id}` | 发起者删除活动 |
| GET | `/map/places` | 同校地点；支持 `q`、`category` 和可选 `lat/lng/radius`；响应含 `favorite`、`distanceMeters` |
| GET | `/map/overview` | 同校地点与可见的未开始活动；支持 `lat/lng/radius`，返回 `{campus,coordinateSystem,places,events,posts}`；posts 为同校、双向屏蔽过滤后、符合附近范围的最新 100 条位置动态 |
| PUT / DELETE | `/map/places/{id}/favorite` | 收藏 / 取消收藏地图地点，重复操作幂等 |

图片由服务器本地文件目录保存，通过 `/media/{文件名}` 公开读取；上传要求登录，支持 JPG、PNG、WebP、GIF，单张最大 5 MB。设置头像、发送图片及创建图文动态时会校验上传者。评论列表最多返回最近 100 条，并过滤双方屏蔽的用户。聊天默认每 5 秒拉取，可在设置中关闭自动刷新；当前未使用 WebSocket，也没有向发送者展示对方已读状态。

新练习接口的 `level` 为 `新手`、`进阶`、`专家`；`mode` 为 `offline`（默认）或 `ai`。AI 模式需要明确同意且服务端已配置模型。练习历史最多 10 条，只允许 user/assistant，单条最多 2000 字；服务端只保存场景、难度、来源和练习标识，不保存练习文本。同一个用户与 `practiceId` 只计一次练习。

活动名额为 1～300，开始时间采用服务器本地时间的 ISO 格式，例如 `2026-10-10T14:30:00`。到达开始时间后停止新报名，允许取消已有报名。服务器当前按本机时区运行，跨时区部署需统一时间策略。

地图经纬度使用 WGS84；`lat/lng` 须同时提供。`radius` 单位为米，允许 50～5000，且需要经纬度；不传 `radius` 返回全部目录地点。距离为 Haversine 直线距离，查询坐标不写入业务数据库。收藏与目录关联活动校验同校目录；任意位置活动校验 WGS84 坐标，查看仍限同校；活动波纹遵循已有双向屏蔽规则。地图说明见 [校园地图](CAMPUS_MAP.md)。

外观、刷新开关和服务地址保存在用户端本地。发帖与聊天草稿按服务器和账号隔离；图片草稿先上传再保存 URL，不依赖临时文件路径。更换服务器会清除登录令牌，401 会返回登录页面。

帖子 `latitude/longitude` 必须成对提供有效 WGS84 坐标，也可同时为 null。坐标与图片在同一个发帖事务中保存，旧帖和无标记帖继续可用。地图只显示明确标记坐标的帖子，不从地点名称推断位置。主动发表的地图标记持久保存并随帖子公开；地图定位查询仍不保存用户轨迹。

个人画像 `portrait` 格式：`{college,major,choices:{grade:[],personality:[],goals:[],communication:[],pace:[],groupSize:[],availability:[]}}`。院系 / 专业各最多 80 字；choices 的合法值与上限由 `/profile/options` 返回，未知键、非法值或超出选择数量会返回 400。兴趣最多 10 个，每个最多 30 字。旧客户端省略 portrait 时保留原画像；传空对象可清空画像。所有画像选填，保存后作为公开资料返回并供推荐卡片查看，不是心理测评；推荐排序仍采用共同兴趣 Jaccard。

活动选择目录地点时，名称和坐标以服务器目录为准；任意坐标需成对提供，不从名称推断坐标。旧活动关联目录时可读取目录坐标作为兼容回退；没有坐标与目录关联的旧活动不会出现在地图上。
