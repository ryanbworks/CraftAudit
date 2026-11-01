package cn.alini.craftaudit.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * 聊天栏通用组件：分页行、可点击按钮。
 */
public final class ChatUi {

    private ChatUi() {}

    // "第 2/5 页 ◀ (1|2|3|4|5) ▶"，数字和箭头可点击执行 cmdPrefix + 页码
    public static MutableComponent pageLine(String lang, String cmdPrefix, int page, int totalPages) {
        MutableComponent line = Component.literal(Lang.tr(lang, "craftaudit.page.line", page, totalPages)).withStyle(ChatFormatting.GRAY);
        if (page > 1) {
            line.append(pageButton("◀ ", cmdPrefix + (page - 1), Lang.tr(lang, "craftaudit.page.prev")));
        }
        line.append(Component.literal("("));
        for (int i = 1; i <= totalPages; i++) {
            if (i == page) {
                line.append(Component.literal(String.valueOf(i)).withStyle(ChatFormatting.YELLOW));
            } else {
                line.append(pageButton(String.valueOf(i), cmdPrefix + i, Lang.tr(lang, "craftaudit.page.goto", i)));
            }
            if (i < totalPages) line.append(Component.literal("|").withStyle(ChatFormatting.GRAY));
        }
        line.append(Component.literal(") "));
        if (page < totalPages) {
            line.append(pageButton("▶", cmdPrefix + (page + 1), Lang.tr(lang, "craftaudit.page.next")));
        }
        return line;
    }

    private static MutableComponent pageButton(String label, String command, String hover) {
        return button(label, command, true, hover);
    }

    // run=true 点击直接执行，false 填入聊天框
    public static MutableComponent button(String label, String command, boolean run, String hover) {
        return Component.literal(label).withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(run ? ClickEvent.Action.RUN_COMMAND : ClickEvent.Action.SUGGEST_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }
}
