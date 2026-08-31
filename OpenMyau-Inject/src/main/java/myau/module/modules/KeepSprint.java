package myau.module.modules;

import myau.Myau;
import myau.access.AccessorKeyBinding;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.MoveInputEvent;
import myau.events.SprintEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.util.MovementTicks;
import net.minecraft.client.Minecraft;

public class KeepSprint extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int MODE_STANDARD = 0;
    private static final int MODE_PREDICTION = 1;
    private static final int KNOCKBACK_GUARD_TICKS = 8;
    public final ModeProperty mode = new ModeProperty("mode", MODE_STANDARD, new String[]{"STANDARD", "PREDICTION"});
    public final PercentProperty slowdown = new PercentProperty("slowdown", 0,
            () -> this.mode.getValue() == MODE_STANDARD);
    public final BooleanProperty groundOnly = new BooleanProperty("ground-only", false,
            () -> this.mode.getValue() == MODE_STANDARD);
    public final BooleanProperty reachOnly = new BooleanProperty("reach-only", false,
            () -> this.mode.getValue() == MODE_STANDARD);
    private boolean sprintCancelled = false;
    private int stopTick = Integer.MIN_VALUE;
    public KeepSprint() {
        super("Keep Sprint", false);
    }
    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getValue() == MODE_PREDICTION ? "Prediction" : "Standard"};
    }

    @Override
    public void onDisabled() {
        this.sprintCancelled = false;
        this.stopTick = Integer.MIN_VALUE;
    }

    public boolean shouldKeepSprint() {
        if (this.mode.getValue() != MODE_STANDARD) {
            return false;
        }
        if (this.groundOnly.getValue() && !mc.thePlayer.onGround) {
            return false;
        }
        if (!this.reachOnly.getValue()) {
            return true;
        }
        return mc.objectMouseOver != null
                && mc.objectMouseOver.hitVec != null
                && mc.objectMouseOver.hitVec.distanceTo(mc.getRenderViewEntity().getPositionEyes(1.0F)) > 3.0;
    }
    public boolean shouldDeferAttack() {
        if (!this.isEnabled() || this.mode.getValue() != MODE_PREDICTION || mc.thePlayer == null) {
            return false;
        }
        if (MovementTicks.sinceVelocity() < KNOCKBACK_GUARD_TICKS) {
            return false;
        }
        if (MovementTicks.ground() == 1) {
            return true;
        }
        if (!mc.thePlayer.isSprinting()) {
            return false;
        }
        mc.thePlayer.setSprinting(false);

        AccessorKeyBinding.setPressed(mc.gameSettings.keyBindSprint, false);
        this.sprintCancelled = true;
        this.stopTick = mc.thePlayer.ticksExisted;
        return true;
    }
    @EventTarget(Priority.LOWEST)
    public void onSprint(SprintEvent event) {
        if (!this.isEnabled() || this.mode.getValue() != MODE_PREDICTION || mc.thePlayer == null) {
            return;
        }
        if (mc.thePlayer.ticksExisted == this.stopTick && mc.thePlayer.isSprinting()) {
            mc.thePlayer.setSprinting(false);
        }
    }
    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (this.isEnabled() && this.sprintCancelled && !mc.thePlayer.isSprinting()) {
            mc.thePlayer.movementInput.jump = false;
        }
    }
    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.thePlayer.isSprinting()
                || this.mode.getValue() != MODE_PREDICTION || !hasTarget()) {
            this.sprintCancelled = false;
        }
    }
    private static boolean hasTarget() {
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        return killAura != null && killAura.isEnabled() && killAura.getTarget() != null;
    }
}
