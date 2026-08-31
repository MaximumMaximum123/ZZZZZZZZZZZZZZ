package myau.module.modules;

import myau.Myau;
import myau.bot.BotCheck;
import myau.bot.checks.MiddleClickCheck;
import myau.bot.checks.MojangProfileCheck;
import myau.bot.checks.SimpleChecks;
import myau.bot.checks.TabSnapshotCheck;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;

import java.util.ArrayList;
import java.util.List;

public class AntiBot extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final BooleanProperty tab = new BooleanProperty("tab", true);
    public final BooleanProperty hypixel = new BooleanProperty("hypixel", true);
    public final BooleanProperty noPing = new BooleanProperty("no-ping", false);
    public final BooleanProperty npcUuid = new BooleanProperty("npc-uuid", false);
    public final BooleanProperty duplicateName = new BooleanProperty("duplicate-name", false);
    public final BooleanProperty duplicateUuid = new BooleanProperty("duplicate-uuid", false);
    public final BooleanProperty colour = new BooleanProperty("colour", false);
    public final BooleanProperty funcraft = new BooleanProperty("funcraft", false);
    public final BooleanProperty cubecraftBedrock = new BooleanProperty("cubecraft-bedrock", false);
    public final BooleanProperty timeVisible = new BooleanProperty("time-visible", false);
    public final BooleanProperty middleClick = new BooleanProperty("middle-click", false);
    public final BooleanProperty tabSnapshot = new BooleanProperty("tab-snapshot", false);
    public final BooleanProperty mojangProfile = new BooleanProperty("mojang-profile", false);

    private static final class Slot {
        final BooleanProperty setting;
        final BotCheck check;
        boolean wasOn;
        Slot(BooleanProperty setting, BotCheck check) {
            this.setting = setting;
            this.check = check;
        }
    }
    private final List<Slot> slots = new ArrayList<Slot>();
    public AntiBot() {
        super("Anti Bot", true);
        this.add(this.tab, new SimpleChecks.TabCheck());
        this.add(this.hypixel, new SimpleChecks.HypixelCheck());
        this.add(this.noPing, new SimpleChecks.NoPingCheck());
        this.add(this.npcUuid, new SimpleChecks.NpcUuidCheck());
        this.add(this.duplicateName, new SimpleChecks.DuplicateNameCheck());
        this.add(this.duplicateUuid, new SimpleChecks.DuplicateUuidCheck());
        this.add(this.colour, new SimpleChecks.ColourCheck());
        this.add(this.funcraft, new SimpleChecks.FuncraftCheck());
        this.add(this.cubecraftBedrock, new SimpleChecks.CubecraftBedrockCheck());
        this.add(this.timeVisible, new SimpleChecks.TimeVisibleCheck());
        this.add(this.middleClick, new MiddleClickCheck());
        this.add(this.tabSnapshot, new TabSnapshotCheck());
        this.add(this.mojangProfile, new MojangProfileCheck());
    }

    private void add(BooleanProperty setting, BotCheck check) {
        this.slots.add(new Slot(setting, check));
    }
    public static boolean isBot(Entity entity) {
        AntiBot module = (AntiBot) Myau.moduleManager.modules.get(AntiBot.class);
        if (module == null || !module.isEnabled()) {
            return false;
        }
        return Myau.botManager.isBot(entity);
    }
    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE || !this.isEnabled()) {
            return;
        }
        if (mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        for (Slot slot : this.slots) {
            boolean on = slot.setting.getValue();
            if (on) {
                try {
                    slot.check.update();
                } catch (Throwable failed) {

                }
            } else if (slot.wasOn) {
                slot.check.onDisabled();
            }
            slot.wasOn = on;
        }
    }

    @EventTarget
    public void onWorldLoad(LoadWorldEvent event) {
        this.forgetEverything();
    }
    @Override
    public void onDisabled() {
        this.forgetEverything();
    }
    private void forgetEverything() {
        for (Slot slot : this.slots) {
            try {
                slot.check.onDisabled();
            } catch (Throwable ignored) {
            }
            slot.wasOn = false;
        }
        Myau.botManager.clear();
    }
}
