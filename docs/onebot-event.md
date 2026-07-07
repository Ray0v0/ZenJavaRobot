# OneBotEvent 参考文档

`OneBotEvent` 是 ZenJavaRobot 中所有 QQ 事件的统一数据模型。每当收到一条 QQ 消息、通知或请求时，框架会将原始 JSON 解析为 `OneBotEvent` 对象，传入插件的 `onEvent()` 方法。

## 事件分类

OneBot v11 协议定义了四类事件，通过 `postType` 字段区分：

| `postType` | 含义 | `detailType` 示例 |
|------------|------|-------------------|
| `message` | 消息事件 | `private`（私聊）、`group`（群聊） |
| `notice` | 通知事件 | `group_upload`（群文件上传）、`group_increase`（加群） |
| `request` | 请求事件 | `friend`（好友申请）、`group`（加群邀请） |
| `meta_event` | 元事件 | `heartbeat`（心跳）、`lifecycle`（生命周期） |

## 字段速查

### 通用字段（所有事件都有）

| 字段 | 类型 | getter | 说明 |
|------|------|--------|------|
| `postType` | `String` | `getPostType()` | 事件大类 |
| `detailType` | `String` | `getDetailType()` | 事件子类 |
| `subType` | `String` | `getSubType()` | 进一步细分 |
| `selfId` | `long` | `getSelfId()` | Bot 自己的 QQ 号 |
| `userId` | `long` | `getUserId()` | 事件来源用户的 QQ 号 |
| `time` | `long` | `getTime()` | 事件发生时间（Unix 时间戳） |

### 消息事件专用

| 字段 | 类型 | getter | 说明 |
|------|------|--------|------|
| `message` | `List<MessageSegment>` | `getMessage()` | 消息内容（片段列表） |
| `rawMessage` | `String` | `getRawMessage()` | 原始文本（CQ 码格式） |
| `sender` | `Sender` | `getSender()` | 发送者信息 |
| `messageId` | `int` | `getMessageId()` | 消息 ID |
| `groupId` | `Long` | `getGroupId()` | 群号（仅群消息，私聊为 `null`） |
| `font` | `int` | `getFont()` | 字体 |

### 通知/请求事件专用

| 字段 | 类型 | getter | 说明 |
|------|------|--------|------|
| `operatorId` | `Long` | `getOperatorId()` | 操作者 QQ（如踢人管理员） |
| `targetId` | `Long` | `getTargetId()` | 操作目标 QQ（如被踢的人） |
| `duration` | `int` | `getDuration()` | 时长（禁言秒数） |
| `comment` | `String` | `getComment()` | 附加信息（好友申请验证消息） |
| `flag` | `String` | `getFlag()` | 请求标志（用于同意/拒绝请求） |
| `noticeData` | `Map<String,Object>` | `getNoticeData()` | 通知附加数据（文件上传信息等） |

## 便捷方法

### 事件类型判断

```java
event.isMessage()     // true = 消息事件
event.isNotice()      // true = 通知事件
event.isGroup()       // true = 群聊/群相关
event.isPrivate()     // true = 私聊
event.isGroupUpload() // true = 群文件上传通知
```

### 消息内容提取

```java
event.getPlainText()       // 拼接所有文本段，返回纯文本字符串
event.getImageSegments()   // 返回所有图片段（List<MessageSegment>）
event.hasImages()          // 消息是否包含图片
event.getSessionKey()      // 返回 "group_123456" 或 "private_789012"
```

## 关联类型

### Sender（发送者）

| 字段 | 类型 | 说明 |
|------|------|------|
| `userId` | `long` | QQ 号 |
| `nickname` | `String` | QQ 昵称 |
| `card` | `String` | 群名片（群内显示名） |
| `role` | `String` | 群角色：`owner` / `admin` / `member` |
| `title` | `String` | 群头衔 |

```java
Sender sender = event.getSender();
sender.getDisplayName()  // 智能取：群名片 > 昵称 > QQ号
sender.getUserId()       // QQ 号
sender.getRole()         // "owner" / "admin" / "member"
```

### MessageSegment（消息片段）

QQ 消息不是纯文本 — 一条消息可以是文字、图片、@、表情等的**混合体**。每个独立的部分称为一个 `MessageSegment`：

```
"看看这个 [图片] 怎么样 @小明"
   ↑       ↑     ↑     ↑
 text    image  text   at
```

#### 字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | `String` | 片段类型，决定 `data` 里有什么 |
| `data` | `Map<String,Object>` | 片段数据，内容随 `type` 不同 |

#### 方法

```java
seg.getType()                   // 返回 "text" / "image" / "at" / "reply" / "face"
seg.getData()                   // 返回整个 data Map
seg.getDataString("key")        // data 中取一个字段，转成 String
```

#### 各类型详解

**text — 文本**

```json
{ "type": "text", "data": { "text": "你好世界" } }
```

```java
seg.getDataString("text")  // → "你好世界"
```

**image — 图片**

```json
{
  "type": "image",
  "data": {
    "file": "f74f8e1a3c2b...",
    "url": "https://gchat.qpic.cn/...",
    "sub_type": "0",
    "file_size": "245760"
  }
}
```

| data 字段 | 说明 |
|-----------|------|
| `file` | OneBot 文件标识符，用于 `ctx.api().getImage(file)` 获取图片本体 |
| `url` | 腾讯服务器直链，有时效性 |
| `sub_type` | 图片类型：`0` 普通图，`1` 表情 |
| `file_size` | 文件大小（字节，字符串格式） |

```java
seg.getDataString("file")      // → "f74f8e1a3c2b..."
seg.getDataString("url")       // → "https://gchat.qpic.cn/..."
seg.getDataString("file_size") // → "245760"

// 获取图片本体
ImageData img = ctx.api().getImage(seg);
```

**at — @某人**

```json
{ "type": "at", "data": { "qq": "123456789", "name": "小明" } }
```

```java
seg.getDataString("qq")    // → "123456789"（被 @ 的 QQ 号）
seg.getDataString("name")  // → "@小明" 或空
```

注意：`qq` 为 `"all"` 时表示 @全体成员。

**reply — 回复**

```json
{ "type": "reply", "data": { "id": "1314" } }
```

```java
seg.getDataString("id")  // → "1314"（被回复的消息 ID）
```

**face — QQ 表情**

```json
{ "type": "face", "data": { "id": "178" } }
```

```java
seg.getDataString("id")  // → "178"（QQ 表情编号）
```

#### 遍历消息的所有片段

```java
for (MessageSegment seg : event.getMessage()) {
    switch (seg.getType()) {
        case "text":
            System.out.println("文本: " + seg.getDataString("text"));
            break;
        case "image":
            System.out.println("图片: " + seg.getDataString("url"));
            break;
        case "at":
            System.out.println("@了: " + seg.getDataString("qq"));
            break;
        case "reply":
            System.out.println("回复消息: " + seg.getDataString("id"));
            break;
        case "face":
            System.out.println("表情: " + seg.getDataString("id"));
            break;
        default:
            System.out.println("未知类型: " + seg.getType());
    }
}
```

#### 与 OneBotAPI.textMessage() 的关系

`OneBotAPI.textMessage("你好")` **构造**一个只含一个 text 段的列表，用于发送纯文本消息：

```java
// 等价于
List<Map<String, Object>> msg = List.of(
    Map.of("type", "text", "data", Map.of("text", "你好"))
);
ctx.api().sendPrivateMsg(userId, msg);

// 简化写法
ctx.api().sendPrivateMsg(userId, OneBotAPI.textMessage("你好"));
```

如果需要发送混合消息（文字+图片），可以自己构建列表：

```java
List<Map<String, Object>> msg = List.of(
    Map.of("type", "text", "data", Map.of("text", "给你看张图")),
    Map.of("type", "image", "data", Map.of("file", img.getFile()))
);
ctx.api().sendPrivateMsg(userId, msg);
```

## 完整示例

### 基础消息处理

```java
@Override
public boolean onEvent(OneBotEvent event) {
    // 只处理消息，忽略通知/请求/心跳
    if (!event.isMessage()) {
        return false;
    }

    // 获取文本和发送者
    String text = event.getPlainText();
    Sender sender = event.getSender();
    String name = sender != null ? sender.getDisplayName() : "未知";

    // 私聊：直接回复
    if (event.isPrivate()) {
        ctx.api().sendPrivateMsg(event.getUserId(),
            OneBotAPI.textMessage("你好，" + name + "！你说了：" + text));
        return true;
    }

    // 群聊：只有被 @ 才回复
    if (event.isGroup()) {
        long selfId = event.getSelfId();  // bot 自己的 QQ
        for (MessageSegment seg : event.getMessage()) {
            if ("at".equals(seg.getType())
                    && String.valueOf(selfId).equals(seg.getDataString("qq"))) {
                ctx.api().sendGroupMsg(event.getGroupId(),
                    OneBotAPI.textMessage("收到 @" + name));
                return true;
            }
        }
    }

    return false;
}
```

### ImageData — 解析后的图片数据

通过 `ctx.api().getImage(seg)` 获取图片，返回 `ImageData` 对象：

| 方法 | 返回 | 说明 |
|------|------|------|
| `getFile()` | `String` | OneBot 文件标识符 |
| `getLocalPath()` | `String` | Napcat 本地临时路径（可能不可访问） |
| `getBytes()` | `byte[]` | 原始图片字节 |
| `getSize()` | `long` | 字节数 |
| `getMimeType()` | `String` | `image/png`、`image/jpeg` 等 |
| `getBase64()` | `String` | 纯 base64 编码字符串 |
| `getDataUri()` | `String` | `data:image/png;base64,xxxx...` — 可直接喂给视觉 LLM |
| `saveTo(File dir)` | `File` | 保存到指定目录，自动追加扩展名 |

### 处理图片消息

```java
@Override
public boolean onEvent(OneBotEvent event) {
    if (!event.hasImages()) return false;

    for (MessageSegment seg : event.getImageSegments()) {
        try {
            // 通过 API 获取图片本体
            ImageData img = ctx.api().getImage(seg);

            System.out.println(img);  // ImageData{file='...', mime='image/png', size=245760}

            // 获取 data URI 喂给视觉 LLM
            String dataUri = img.getDataUri();

            // 保存到插件数据目录
            File saved = img.saveTo(ctx.dataDir());
            System.out.println("图片保存在: " + saved.getAbsolutePath());

        } catch (RuntimeException e) {
            System.err.println("获取图片失败: " + e.getMessage());
        }
    }

    ctx.api().reply(event, "收到了 " + event.getImageSegments().size() + " 张图片");
    return true;
}
```

也可以直接用 file 标识符获取（不需要 MessageSegment）：

```java
ImageData img = ctx.api().getImage("f74f8e1a3c2b9d5e6a7b8c9d0e1f2a3b");
```

### 处理群文件上传

```java
@Override
public boolean onEvent(OneBotEvent event) {
    if (!event.isGroupUpload()) return false;

    Map<String, Object> file = event.getNoticeData();
    String fileName = String.valueOf(file.getOrDefault("name", "未知文件"));
    long fileSize = ((Number) file.getOrDefault("size", 0)).longValue();
    long uploader = event.getUserId();

    System.out.printf("群 %d 收到文件: %s (%d bytes) from %d%n",
            event.getGroupId(), fileName, fileSize, uploader);
    return false;  // 不拦截，其他插件继续处理
}
```

### 处理好友申请

```java
@Override
public boolean onEvent(OneBotEvent event) {
    if (!"request".equals(event.getPostType())) return false;
    if (!"friend".equals(event.getDetailType())) return false;

    long fromUser = event.getUserId();
    String message = event.getComment();
    String flag = event.getFlag();  // 需要传给同意/拒绝 API

    System.out.printf("好友申请: QQ=%d, 验证消息=%s, flag=%s%n", fromUser, message, flag);
    // 自动同意：
    // ctx.api().call("set_friend_add_request", Map.of("flag", flag, "approve", true));
    return true;
}
```
