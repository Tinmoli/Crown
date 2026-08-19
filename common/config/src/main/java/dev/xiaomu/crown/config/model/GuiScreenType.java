package dev.xiaomu.crown.config.model;

/** Crown 支持的服务端虚拟箱子尺寸。 */
public enum GuiScreenType {
    GENERIC_9X1(9),
    GENERIC_9X2(18),
    GENERIC_9X3(27),
    GENERIC_9X4(36),
    GENERIC_9X5(45),
    GENERIC_9X6(54);

    private final int slotCount;

    GuiScreenType(int slotCount) {
        this.slotCount = slotCount;
    }

    public int slotCount() {
        return slotCount;
    }
}