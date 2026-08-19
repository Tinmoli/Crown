package dev.xiaomu.crown.runtime.wardrobe;

/** 佩戴/卸下操作的归一化结果，供命令与 GUI 稳定映射到语言键。 */
public enum EquipResult {
    EQUIPPED,
    EQUIPPED_DEFAULT,
    UNEQUIPPED,
    NOT_OWNED,
    EXPIRED,
    ALREADY_EQUIPPED,
    STORAGE_FAILED
}