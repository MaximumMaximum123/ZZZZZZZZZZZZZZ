package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.MoveInputEvent;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.util.BlockUtil;
import myau.util.KeyBindUtil;
import myau.util.MoveUtil;
import myau.util.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;

import java.util.ArrayList;
import java.util.List;

public class BridgeAssist extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final EnumFacing[] SIDES = {
            EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.EAST, EnumFacing.WEST
    };
    private static final double MS_PER_TICK = 50.0;
    private static final int ROTATION_PRIORITY = 4;
    private static final float MAX_PITCH_STEP = 15.0F;
    public final BooleanProperty prePlace = new BooleanProperty("pre-place", false);
    public final FloatProperty edgeOffset = new FloatProperty("edge-offset", 0.0F, 0.0F, 0.3F);
    public final IntProperty unsneakDelay = new IntProperty("unsneak-delay", 50, 50, 300);
    public final IntProperty sneakOnJump = new IntProperty("sneak-on-jump", 0, 0, 500);
    public final BooleanProperty sneakKeyPressed = new BooleanProperty("sneak-key-pressed", false);
    public final BooleanProperty holdingBlocks = new BooleanProperty("holding-blocks", false);
    public final BooleanProperty lookingDown = new BooleanProperty("looking-down", false);
    public final BooleanProperty notMovingForward = new BooleanProperty("not-moving-forward", false);
    private boolean sneakingFromModule;
    private boolean placed;
    private boolean forceRelease;
    private int sneakJumpDelayTicks = -1;
    private int sneakJumpStartTick = -1;
    private int unsneakDelayTicks = -1;
    private int unsneakStartTick = -1;
    public BridgeAssist() {
        super("BridgeAssist", false);
    }
    @Override
    public String[] getSuffix() {
        float offset = this.edgeOffset.getValue();
        return new String[]{offset == Math.rint(offset)
                ? String.valueOf((int) offset)
                : String.format("%.2f", offset)};
    }
    @Override
    public void onDisabled() {
        this.sneakingFromModule = false;
        this.resetUnsneak();
    }
    @EventTarget(Priority.LOWEST)
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (mc.currentScreen != null || mc.thePlayer.capabilities.isFlying) {
            return;
        }
        boolean manualSneak = this.isManualSneak();
        boolean requireSneak = this.sneakKeyPressed.getValue();
        double forward = MoveUtil.getForwardValue();
        double strafe = MoveUtil.getLeftValue();
        boolean jumping = mc.gameSettings.keyBindJump.isKeyDown();
        if (manualSneak && !requireSneak) {
            this.resetUnsneak();
            return;
        }
        if (requireSneak && (!manualSneak || (forward == 0.0 && strafe == 0.0))) {
            if (!manualSneak) {
                this.resetUnsneak();
            }
            this.repressSneak();
            return;
        }
        if (this.notMovingForward.getValue() && forward > 0.0) {
            this.clearSneak();
            return;
        }
        if (this.lookingDown.getValue() && mc.thePlayer.rotationPitch < 70.0F) {
            this.clearSneak();
            return;
        }
        if (this.holdingBlocks.getValue() && !this.isHoldingBlock()) {
            this.clearSneak();
            return;
        }
        if (jumping && mc.thePlayer.onGround && (forward != 0.0 || strafe != 0.0)
                && this.sneakOnJump.getValue() > 0
                && (!requireSneak || this.forceRelease)) {
            this.sneakJumpStartTick = mc.thePlayer.ticksExisted;
            this.sneakJumpDelayTicks = randomizedTicks(this.sneakOnJump.getValue());
            this.pressSneak(true);
            return;
        }
        double offset = this.computeEdgeOffset();
        if (Double.isNaN(offset)) {
            if (jumping && (this.sneakOnJump.getValue() <= 0 || (forward == 0.0 && strafe == 0.0))) {
                if (this.sneakingFromModule) {
                    this.tryReleaseSneak(true);
                }
            } else if (mc.thePlayer.onGround) {
                this.pressSneak(true);
            } else if (this.sneakingFromModule) {
                this.tryReleaseSneak(true);
            }
            return;
        }
        if (offset > this.edgeOffset.getValue()) {
            this.pressSneak(true);
        } else if (this.sneakingFromModule) {
            this.tryReleaseSneak(true);
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND) {
            return;
        }
        if (!(event.getPacket() instanceof C08PacketPlayerBlockPlacement)) {
            return;
        }
        C08PacketPlayerBlockPlacement placement = (C08PacketPlayerBlockPlacement) event.getPacket();
        if (placement.getPlacedBlockDirection() != 255
                && this.sneakingFromModule && this.sneakKeyPressed.getValue()) {
            this.placed = true;
        }
    }
    private void pressSneak(boolean resetDelay) {
        this.setSneak(true);
        this.sneakingFromModule = true;
        if (resetDelay) {
            this.unsneakStartTick = -1;
        }
        this.repressSneak();
    }
    private void tryReleaseSneak(boolean resetDelay) {
        int existed = mc.thePlayer.ticksExisted;
        if (this.unsneakStartTick == -1 && this.sneakJumpStartTick == -1) {
            this.unsneakStartTick = existed;
            this.unsneakDelayTicks = randomizedTicks(this.unsneakDelay.getValue() - 50);
        }
        if (this.sneakJumpStartTick != -1 && existed - this.sneakJumpStartTick < this.sneakJumpDelayTicks) {
            this.pressSneak(false);
            return;
        }
        if (this.unsneakStartTick != -1 && existed - this.unsneakStartTick < this.unsneakDelayTicks) {
            this.pressSneak(false);
            return;
        }
        this.releaseSneak(resetDelay);
    }
    private void releaseSneak(boolean resetDelay) {
        if (!this.sneakKeyPressed.getValue()) {
            this.setSneak(false);
        } else if (this.sneakingFromModule && this.isManualSneak()
                && (this.placed || !mc.thePlayer.onGround)) {
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
            this.setSneak(false);
            this.forceRelease = true;
        } else if (this.forceRelease) {
            this.setSneak(false);
        }
        this.sneakingFromModule = false;
        this.placed = false;
        if (resetDelay) {
            this.resetUnsneak();
        }
    }
    private void repressSneak() {
        if (this.forceRelease && this.isManualSneak()) {
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), true);
            this.setSneak(true);
        }
        this.forceRelease = false;
    }
    private void clearSneak() {
        this.sneakingFromModule = false;
        this.resetUnsneak();
        if (this.sneakKeyPressed.getValue()) {
            this.repressSneak();
        }
    }
    private void resetUnsneak() {
        this.unsneakStartTick = -1;
        this.sneakJumpStartTick = -1;
        this.sneakJumpDelayTicks = -1;
        this.unsneakDelayTicks = -1;
    }
    private void setSneak(boolean sneak) {
        if (mc.thePlayer.movementInput.sneak == sneak) {
            return;
        }
        mc.thePlayer.movementInput.sneak = sneak;
        float factor = sneak ? 0.3F : 1.0F / 0.3F;
        mc.thePlayer.movementInput.moveForward *= factor;
        mc.thePlayer.movementInput.moveStrafe *= factor;
    }
    private boolean isManualSneak() {
        return KeyBindUtil.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode());
    }
    private boolean isHoldingBlock() {
        ItemStack held = mc.thePlayer.getHeldItem();
        return held != null && held.getItem() instanceof ItemBlock;
    }

    private static int randomizedTicks(double milliseconds) {
        double raw = Math.max(0.0, milliseconds) / MS_PER_TICK;
        int whole = (int) raw;
        return whole + (Math.random() < raw - whole ? 1 : 0);
    }

    private double computeEdgeOffset() {
        double[] predicted = MoveUtil.predictMovement();
        AxisAlignedBB box = mc.thePlayer.getEntityBoundingBox()
                .offset(mc.thePlayer.motionX + predicted[0], 0.0, mc.thePlayer.motionZ + predicted[1]);
        AxisAlignedBB groundCheck = new AxisAlignedBB(
                box.minX, box.minY - 0.01, box.minZ,
                box.maxX, box.minY, box.maxZ
        );
        List<AxisAlignedBB> ground = mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, groundCheck);
        if (ground.isEmpty()) {
            return Double.NaN;
        }
        double feetX = (box.minX + box.maxX) / 2.0;
        double feetZ = (box.minZ + box.maxZ) / 2.0;
        double nearest = Double.MAX_VALUE;
        for (AxisAlignedBB solid : ground) {
            double closestX = Math.max(solid.minX, Math.min(feetX, solid.maxX));
            double closestZ = Math.max(solid.minZ, Math.min(feetZ, solid.maxZ));
            nearest = Math.min(nearest, Math.max(Math.abs(feetX - closestX), Math.abs(feetZ - closestZ)));
        }
        return nearest;
    }
    @EventTarget(Priority.LOW)
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || !this.prePlace.getValue() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null
                || mc.currentScreen != null || mc.thePlayer.capabilities.isFlying) {
            return;
        }
        if (!this.isHoldingBlock()) {
            return;
        }
        if (this.lookingDown.getValue() && mc.thePlayer.rotationPitch < 70.0F) {
            return;
        }
        if (this.notMovingForward.getValue() && mc.thePlayer.movementInput.moveForward > 0.0F) {
            return;
        }

        float basePitch = event.getNewPitch();
        float baseYaw = event.getNewYaw();
        float target = this.findPlacementPitch(basePitch, mc.playerController.getBlockReachDistance());
        if (Float.isNaN(target)) {
            return;
        }
        float step = RotationUtil.clampAngle(
                RotationUtil.wrapAngleDiff(target, basePitch) - basePitch, MAX_PITCH_STEP);
        event.setRotation(baseYaw, basePitch + step, ROTATION_PRIORITY);
    }
    private float findPlacementPitch(float currentPitch, double reach) {
        float yaw = mc.thePlayer.rotationYaw;
        List<FaceTarget> targets = this.collectTargets();
        if (targets.isEmpty()) {
            return Float.NaN;
        }
        float bestDelta = Float.MAX_VALUE;
        float bestPitch = Float.NaN;
        for (float pitch = 60.0F; pitch < 90.0F; ) {
            float step = 1.0F + (float) (Math.random() * 2.0 - 1.0) * 0.46F;
            pitch += Math.max(0.4F, Math.min(1.8F, step));
            float sample = Math.min(pitch, 90.0F);
            MovingObjectPosition hit = RotationUtil.rayTrace(yaw, sample, reach, 1.0F);
            if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
                continue;
            }
            EnumFacing face = hit.sideHit;
            if (face == EnumFacing.UP || face == EnumFacing.DOWN) {
                continue;
            }
            BlockPos block = hit.getBlockPos();
            for (FaceTarget target : targets) {
                if (block.equals(target.block) && face == target.face) {
                    float delta = Math.abs(sample - currentPitch);
                    if (delta < bestDelta) {
                        bestDelta = delta;
                        bestPitch = sample;
                    }
                    break;
                }
            }
        }
        return bestPitch;
    }
    private List<FaceTarget> collectTargets() {
        AxisAlignedBB box = mc.thePlayer.getEntityBoundingBox();
        int standY = MathHelper.floor_double(box.minY) - 1;
        int minX = MathHelper.floor_double(box.minX);
        int maxX = MathHelper.floor_double(box.maxX);
        int minZ = MathHelper.floor_double(box.minZ);
        int maxZ = MathHelper.floor_double(box.maxZ);
        List<FaceTarget> targets = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos stand = new BlockPos(x, standY, z);
                if (BlockUtil.isReplaceable(stand)) {
                    continue;
                }
                for (EnumFacing face : SIDES) {
                    if (BlockUtil.isReplaceable(stand.offset(face))) {
                        targets.add(new FaceTarget(stand, face));
                    }
                }
            }
        }
        return targets;
    }
    private static final class FaceTarget {
        private final BlockPos block;
        private final EnumFacing face;
        private FaceTarget(BlockPos block, EnumFacing face) {
            this.block = block;
            this.face = face;
        }
    }
}
