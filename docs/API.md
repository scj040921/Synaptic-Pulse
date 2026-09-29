# API 接口概要

基础地址 `http://localhost:8088/api`。除注册、登录与健康检查外，请求头需携带 `Authorization: Bearer <token>`。错误响应形如 `{ "error": "说明" }`。

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/health` | 健康检查 |
| POST | `/auth/register` | 注册 `{username,password,displayName}` |
| POST | `/auth/login` | 登录 `{username,password}`；返回令牌及用户资料 |
| POST | `/auth/logout` | 退出当前会话 |
| GET / PUT | `/me` | 获取 / 更新本人资料；更新体含 `{displayName,bio,interests}` |
| GET | `/users/{id}` | 获取用户公开资料 |
| GET | `/matches` | 同校体验账号的共同兴趣排序，最多 20 人 |
| GET / POST | `/posts` | 最近 50 条动态 / 发布 `{content}` |
| GET | `/chats` | 会话列表 |
| GET / POST | `/chats/{peerId}/messages` | 最近 50 条消息 / 发送 `{content}` |
| POST / DELETE | `/blocks/{peerId}` | 屏蔽 / 取消屏蔽 |
| POST | `/training/reply` | `{scenario,message,consent:true}`；调用已配置模型 |

目前聊天采用客户端每 5 秒拉取，不是 WebSocket。API 没有图像上传、分页历史或已读回执。
