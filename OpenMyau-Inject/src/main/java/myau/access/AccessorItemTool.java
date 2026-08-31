package myau.access;

import myau.inject.MappingBridge;
import net.minecraft.item.Item;
import net.minecraft.item.ItemTool;

import java.lang.reflect.Field;

public final class AccessorItemTool {
    private static final String OWNER = "net.minecraft.item.ItemTool";
    private static final Field F_TOOL_MATERIAL =
            MappingBridge.field(OWNER, "toolMaterial", Item.ToolMaterial.class);
    private AccessorItemTool() {
    }
    public static Item.ToolMaterial getToolMaterial(ItemTool owner) {
        try {
            return (Item.ToolMaterial) F_TOOL_MATERIAL.get(owner);
        } catch (Throwable t) {
            Access.report(OWNER, "toolMaterial", t);
            return null;
        }
    }
}
