# ZenJavaRobot

基于 [OneBot v11](https://github.com/botuniverse/onebot-11) 反向 WebSocket 协议的 QQ 机器人框架。

使用 [NapCat](https://napneko.github.io/) 实现QQ消息的收发，安装和配置方法可参考 [napcat-install](docs/napcat-install.md)。

**零外部包依赖**，纯 JDK 即可编译运行。

## 快速开始

```bash
./run.sh          # 增量编译 + 打包插件 + 启动
./run.sh -v       # verbose 模式
./run.sh -h       # 查看帮助
```

WebSocket 服务端监听 `ws://0.0.0.0:6198`，等待 QQ 客户端连接。

## 项目结构

```
src/
├── app/App.java                  # 入口，组装启动
├── api/                          # 插件接口
│   ├── Plugin.java               # 插件生命周期
│   ├── PluginContext.java        # 插件上下文
│   └── MessageHandler.java       # 事件处理接口
├── config/                       # 配置加载
│   ├── ConfigLoader.java
│   ├── AppConfig.java
│   └── OneBotConfig.java
├── json/                         # 轻量 JSON 解析器
│   ├── Json.java
│   └── JsonValue.java
├── onebot/                       # OneBot v11 协议
│   ├── OneBotServer.java         # WebSocket 服务端
│   ├── OneBotAPI.java            # 消息发送 API
│   ├── model/                    # 事件数据模型
│   │   ├── OneBotEvent.java
│   │   ├── MessageSegment.java
│   │   ├── Sender.java
│   │   └── ImageData.java
│   └── ws/                       # WebSocket 协议实现
│       ├── WsServer.java
│       ├── WsConnection.java
│       └── WsHandshake.java
└── plugin/
    └── PluginManager.java        # 插件加载器

plugins/
└── echo-plugin/                  # 模板插件（示范）
    └── src/
        ├── EchoPlugin.java
        └── META-INF/services/api.Plugin
```

## 配置

编辑 `config.properties`：

```properties
# OneBot v11 反向 WebSocket 服务端
onebot.host=0.0.0.0
onebot.port=6198
onebot.access_token=

# true=发送消息到QQ  false=仅控制台 [DRY-RUN]
send_enabled=false

# 管理员 QQ 号（逗号分隔）
admins=

# 加载的插件（逗号分隔，顺序决定事件分发优先级）
plugins=echo-plugin.jar
```

## 制作插件

参考 `plugins/echo-plugin/` 模板：

### 1. 目录结构

```
plugins/my-plugin/
└── src/
    ├── MyPlugin.java
    └── META-INF/services/
        └── api.Plugin          ← SPI 声明文件
```

### 2. 实现 Plugin 接口

```java
package myplugin;

import api.Plugin;
import api.PluginContext;
import onebot.OneBotAPI;
import onebot.model.OneBotEvent;

public class MyPlugin implements Plugin {

    private PluginContext ctx;

    @Override public String name() { return "my-plugin"; }
    @Override public String version() { return "1.0.0"; }

    @Override public void onEnable(PluginContext ctx) { this.ctx = ctx; }
    @Override public void onDisable() { this.ctx = null; }

    @Override
    public boolean onEvent(OneBotEvent event) {
        if (!event.isMessage()) return false;
        String text = event.getPlainText();

        if (event.isPrivate() && !text.isEmpty()) {
            ctx.api().sendPrivateMsg(event.getUserId(),
                OneBotAPI.textMessage("你说了: " + text));
            return true;   // 事件已处理，不再传递给后续插件
        }
        return false;      // 未处理，继续传递
    }
}
```

### 3. SPI 文件

`src/META-INF/services/api.Plugin`：

```
myplugin.MyPlugin
```

### 4. 注册插件

在 `config.properties` 中添加：

```properties
plugins=echo-plugin.jar,my-plugin.jar
```

打包用 `./run.sh`，它会自动检测改动并增量编译。

### 插件上下文

`PluginContext` 提供：

| 方法 | 说明 |
|------|------|
| `ctx.api()` | 发送消息、调用 QQ API |
| `ctx.dataDir()` | 插件专用数据目录 `plugins/<name>/data/` |

### 事件分发

`config.properties` 中 `plugins` 列表的顺序即事件分发顺序。排在前面的插件先收到事件，返回 `true` 后停止传播。

## API 参考

详见 [`docs/onebot-event.md`](docs/onebot-event.md)。

## License

MIT
