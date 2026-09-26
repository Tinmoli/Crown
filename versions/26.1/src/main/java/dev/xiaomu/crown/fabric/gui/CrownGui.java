package dev.xiaomu.crown.fabric.gui;

import dev.xiaomu.crown.fabric.CrownServerContext;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import org.slf4j.LoggerFactory;

/** 统一虚拟菜单的点击边界和异常反馈。 */
abstract class CrownGui extends SimpleGui {
    private final CrownServerContext guiContext;

    protected CrownGui(CrownServerContext context, MenuType<?> type, ServerPlayer player) {
        super(type, player, false);
        guiContext = context;
        setLockPlayerInventory(true);
    }

    @Override
    public boolean click(int slot, ClickType click, ContainerInput input) {
        // isLeft/isRight 也包含拖拽，不能把它们全部视为按钮操作。
        if (click != ClickType.MOUSE_LEFT && click != ClickType.MOUSE_RIGHT
                && click != ClickType.MOUSE_LEFT_SHIFT && click != ClickType.MOUSE_RIGHT_SHIFT) {
            return false;
        }
        return super.click(slot, click, input);
    }

    @Override
    public void handleException(Throwable failure) {
        LoggerFactory.getLogger("Crown").error(
                guiContext.runtime().snapshot().languages().text("log.gui.failed"), failure);
        getPlayer().sendSystemMessage(guiContext.messages().render("gui.action.failed"));
        close();
    }
}
