package io.example.timer;

import android.os.Bundle;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import io.github.yuloong07star.luwi.plugin.api.ILwPlugin;
import io.github.yuloong07star.luwi.plugin.api.ILwPluginContext;

/**
 * 样例 inline 插件: 一只番茄钟
 *
 * 三件事各是一个读数:
 *
 * - `timer_start` / `timer_state`: 工具能被调到, 而且它**推得动桌面上那一格** (`ui.widget`)
 * - `timer_boom`: 故意抛异常, 量的是"插件抛异常时 Luwi 不受影响" (那一句会变成工具的错误文本)
 * - `describe()`: 与包里那份 `plugin.json` 逐项对照, 对不上就不启用
 */
public class TimerPlugin extends ILwPlugin {

    private static final String ID = "io.example.timer";
    private static final String NAME = "番茄钟";
    private static final String VERSION = "1.0.0";

    private ILwPluginContext context;

    private String state = "还没开始";

    @Override
    public Bundle describe() {
        Bundle offered = new Bundle();
        offered.putString("id", ID);
        offered.putString("name", NAME);
        offered.putString("version", VERSION);
        offered.putString("api", "1");
        offered.putStringArrayList(
                "tools",
                new ArrayList<>(Arrays.asList("timer_boom", "timer_start", "timer_state")));
        offered.putStringArrayList("capabilities", new ArrayList<>(Collections.singletonList("ui.widget")));
        offered.putStringArrayList("dangerous", new ArrayList<String>());
        return offered;
    }

    @Override
    public Bundle lifecycle(String event, Bundle args) {
        if ("attach".equals(event)) {
            IBinder binder = args == null ? null : args.getBinder("context");
            context = ILwPluginContext.Companion.asInterface(binder);
            return ok("番茄钟到位");
        }
        if ("enable".equals(event)) {
            state = "还没开始";
            push(state);
            return ok("番茄钟上岗");
        }
        if ("disable".equals(event) || "detach".equals(event)) {
            state = "还没开始";
            return ok("番茄钟收工");
        }
        return ok("收到了 " + event);
    }

    @Override
    public Bundle invoke(String tool, Bundle args) {
        if ("timer_start".equals(tool)) {
            state = "25:00 在跑";
            push(state);
            return ok("已经开了一轮 25 分钟");
        }
        if ("timer_state".equals(tool)) {
            return ok("现在: " + state);
        }
        if ("timer_boom".equals(tool)) {
            // 这一下是故意炸的: 用来量"插件抛异常时 Luwi 不受影响"那一条
            throw new IllegalStateException("这一下是故意炸的");
        }
        return fail(NAME + "没有这个工具: " + tool);
    }

    /** 把"这会儿在跑什么"推给桌面上那一格 (能力 `ui.widget`, 声明过才推得动) */
    private void push(String text) {
        if (context == null) {
            return;
        }
        Bundle button = new Bundle();
        button.putString("label", "开一轮");
        button.putString("action", "tool");
        button.putString("tool", "timer_start");
        ArrayList<Bundle> buttons = new ArrayList<>();
        buttons.add(button);
        Bundle args = new Bundle();
        args.putString("title", NAME);
        args.putString("text", text);
        args.putParcelableArrayList("buttons", buttons);
        context.call("ui.widget", args);
    }

    private static Bundle ok(String text) {
        Bundle result = new Bundle();
        result.putBoolean("ok", true);
        result.putString("text", text);
        result.putStringArray("images", new String[0]);
        return result;
    }

    private static Bundle fail(String text) {
        Bundle result = new Bundle();
        result.putBoolean("ok", false);
        result.putString("text", text);
        result.putStringArray("images", new String[0]);
        return result;
    }
}
