package echo;

import api.Plugin;
import api.PluginContext;
import onebot.OneBotAPI;
import onebot.model.OneBotEvent;

/**
 * Demo plugin — echoes private messages back.
 * This single file is the entire plugin.
 */
public class EchoPlugin implements Plugin {

    private PluginContext ctx;

    @Override public String name() { return "echo-plugin"; }
    @Override public String version() { return "1.0.0"; }

    @Override
    public void onEnable(PluginContext ctx) {
        this.ctx = ctx;
        System.out.println("[EchoPlugin] enabled");
    }

    @Override public void onDisable() { this.ctx = null; }

    @Override
    public boolean onEvent(OneBotEvent event) {
        if (!event.isMessage()) return false;
        String text = event.getPlainText();

        if (event.isPrivate() && !text.isEmpty()) {
            System.out.println("[EchoPlugin] " + text);
            ctx.api().sendPrivateMsg(event.getUserId(),
                    OneBotAPI.textMessage("[Echo] " + text));
            return true;
        }
        return false;
    }

    public static void main(String[] args) {
        System.out.println("EchoPlugin — template plugin, ready.");
    }
}
