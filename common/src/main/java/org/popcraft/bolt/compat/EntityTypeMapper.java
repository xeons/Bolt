package org.popcraft.bolt.compat;

public class EntityTypeMapper {
    public static String map(final String entityType) {
        switch (entityType) {
            case "DROPPED_ITEM": return "ITEM";
            case "LEASH_HITCH": return "LEASH_KNOT";
            case "ENDER_SIGNAL": return "EYE_OF_ENDER";
            case "SPLASH_POTION": return "POTION";
            case "THROWN_EXP_BOTTLE": return "EXPERIENCE_BOTTLE";
            case "PRIMED_TNT": return "TNT";
            case "FIREWORK": return "FIREWORK_ROCKET";
            case "MINECART_COMMAND": return "COMMAND_BLOCK_MINECART";
            case "MINECART_CHEST": return "CHEST_MINECART";
            case "MINECART_FURNACE": return "FURNACE_MINECART";
            case "MINECART_TNT": return "TNT_MINECART";
            case "MINECART_HOPPER": return "HOPPER_MINECART";
            case "MINECART_MOB_SPAWNER": return "SPAWNER_MINECART";
            case "MUSHROOM_COW": return "MOOSHROOM";
            case "SNOWMAN": return "SNOW_GOLEM";
            case "ENDER_CRYSTAL": return "END_CRYSTAL";
            case "FISHING_HOOK": return "FISHING_BOBBER";
            case "LIGHTNING": return "LIGHTNING_BOLT";
            default: return entityType;
        }
    }
}
