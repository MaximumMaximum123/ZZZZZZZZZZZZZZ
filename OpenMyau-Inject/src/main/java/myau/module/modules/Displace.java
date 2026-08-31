package myau.module.modules;

import myau.Myau;
import myau.enums.BlinkModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.MoveInputEvent;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import myau.util.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class Displace extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int DISPLACE_WINDOW_TICKS = 10;
    private static final int VOID_SCAN_DIRECTIONS = 32;
    private static final int VOID_SCAN_RINGS = 12;
    private static final int VOID_SCAN_DEPTH = 10;
    private static final double VOID_SCAN_STEP = 0.5;
    private static final double[] VOID_SCAN_X = new double[VOID_SCAN_DIRECTIONS];
    private static final double[] VOID_SCAN_Z = new double[VOID_SCAN_DIRECTIONS];
    static {
        for (int i = 0; i < VOID_SCAN_DIRECTIONS; i++) {
            double angle = Math.PI * 2.0 * i / VOID_SCAN_DIRECTIONS;
            VOID_SCAN_X[i] = Math.cos(angle);
            VOID_SCAN_Z[i] = Math.sin(angle);
        }
    }
    private static final int ROTATION_PRIORITY = 2;
    public final FloatProperty yawOffset = new FloatProperty("yaw-offset", 90.0F, 0.0F, 180.0F);
    public final FloatProperty delay = new FloatProperty("delay", 0.0F, 0.0F, 500.0F);
    public final ModeProperty direction = new ModeProperty("direction", 0, new String[]{"LEFT", "RIGHT"});
    public final BooleanProperty findVoid = new BooleanProperty("find-void", false);
    public final BooleanProperty blink = new BooleanProperty("blink", false);
    public final BooleanProperty hasKnockback = new BooleanProperty("has-knockback", false);
    private boolean displaceThisTick = false;
    private boolean active = false;
    private boolean hasKB = false;
    private boolean compensateNextTick = false;
    private boolean displaceLeft = false;
    private boolean wasDisplacingLastTick = false;
    private boolean releaseBlinkNextGameTick = false;
    private int tickCounter;
    private final Map<Integer, Integer> targetWindowStartTicks = new HashMap<>();
    public Displace() {
        super("Displace", false);
    }
    @Override
    public void onEnabled() {
        this.displaceThisTick = false;
        this.active = false;
        this.hasKB = false;
        this.compensateNextTick = false;
        this.wasDisplacingLastTick = false;
        this.releaseBlinkNextGameTick = false;
        this.tickCounter = 0;
        this.targetWindowStartTicks.clear();
        this.releaseBlink();
    }
    @Override
    public void onDisabled() {
        this.active = false;
        this.compensateNextTick = false;
        this.wasDisplacingLastTick = false;
        this.releaseBlinkNextGameTick = false;
        this.targetWindowStartTicks.clear();
        this.releaseBlink();
    }
    @Override
    public String[] getSuffix() {
        return new String[]{this.findVoid.getValue() ? "Void" : (this.direction.getValue() == 0 ? "Left" : "Right")};
    }

    private static int msToTicks(double ms) {
        return ms <= 0.0 ? 0 : (int) Math.ceil(ms / 50.0);
    }

    private boolean anyMovementKey() {
        return mc.gameSettings.keyBindForward.isKeyDown()
                || mc.gameSettings.keyBindBack.isKeyDown()
                || mc.gameSettings.keyBindLeft.isKeyDown()
                || mc.gameSettings.keyBindRight.isKeyDown();
    }
    private Float findVoidYaw(EntityPlayer target) {
        if (target == null || mc.thePlayer == null || mc.theWorld == null) {
            return null;
        }
        double bestX = 0.0;
        double bestZ = 0.0;
        double bestScore = Double.MAX_VALUE;
        for (int ring = 1; ring <= VOID_SCAN_RINGS; ring++) {
            double radius = ring * VOID_SCAN_STEP;
            boolean foundInRing = false;
            for (int i = 0; i < VOID_SCAN_DIRECTIONS; i++) {
                double x = target.posX + VOID_SCAN_X[i] * radius;
                double z = target.posZ + VOID_SCAN_Z[i] * radius;
                if (!this.isVoidColumn(x, target.posY, z)) {
                    continue;
                }
                double playerDx = x - mc.thePlayer.posX;
                double playerDz = z - mc.thePlayer.posZ;

                double score = radius * radius * 1000.0 + playerDx * playerDx + playerDz * playerDz;
                if (score < bestScore) {
                    bestScore = score;
                    bestX = x;
                    bestZ = z;
                    foundInRing = true;
                }
            }
            if (foundInRing) {
                break;
            }
        }
        if (bestScore == Double.MAX_VALUE) {
            return null;
        }
        this.updateDisplaceSide(target, bestX, bestZ);
        double dx = bestX - target.posX;
        double dz = bestZ - target.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.001) {
            return null;
        }
        double aimRadius = Math.min(dist, Math.max(0.35, target.width * 0.5 + 0.15));
        double aimX = target.posX + dx / dist * aimRadius;
        double aimZ = target.posZ + dz / dist * aimRadius;
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        return RotationUtil.getRotationsTo(aimX, target.posY + target.getEyeHeight() * 0.5,
                aimZ, mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch)[0];
    }
    private boolean isVoidColumn(double x, double y, double z) {
        int blockX = MathHelper.floor_double(x);
        int blockZ = MathHelper.floor_double(z);
        int startY = MathHelper.floor_double(y) - 1;
        int endY = Math.max(0, startY - VOID_SCAN_DEPTH);
        for (int blockY = startY; blockY >= endY; blockY--) {
            if (!mc.theWorld.isAirBlock(new BlockPos(blockX, blockY, blockZ))) {
                return false;
            }
        }
        return true;
    }
    private void updateDisplaceSide(EntityPlayer target, double voidX, double voidZ) {
        double targetDx = target.posX - mc.thePlayer.posX;
        double targetDz = target.posZ - mc.thePlayer.posZ;
        double voidDx = voidX - mc.thePlayer.posX;
        double voidDz = voidZ - mc.thePlayer.posZ;
        this.displaceLeft = targetDx * voidDz - targetDz * voidDx < 0.0;
    }
    private void pruneTargetDelayStates() {
        if (mc.theWorld == null) {
            this.targetWindowStartTicks.clear();
            return;
        }
        Iterator<Map.Entry<Integer, Integer>> iterator = this.targetWindowStartTicks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Integer> entry = iterator.next();
            Entity entity = mc.theWorld.getEntityByID(entry.getKey());
            if (!(entity instanceof EntityPlayer) || entity.isDead || ((EntityPlayer) entity).deathTime != 0) {
                iterator.remove();
            }
        }
    }
    private boolean shouldDisplaceInCurrentWindow(EntityPlayer target, int currentTick) {
        if (target == null) {
            return true;
        }
        int targetId = target.getEntityId();
        Integer windowStartTick = this.targetWindowStartTicks.get(targetId);
        if (windowStartTick == null || currentTick - windowStartTick >= DISPLACE_WINDOW_TICKS) {
            this.targetWindowStartTicks.put(targetId, currentTick);
            return true;
        }
        int delayTicks = msToTicks(this.delay.getValue());
        if (delayTicks <= 0) {
            return true;
        }
        return currentTick - windowStartTick >= delayTicks;
    }
    private void releaseBlink() {
        Myau.blinkManager.setBlinkState(false, BlinkModules.DISPLACE);
    }
    @EventTarget(Priority.HIGHEST)
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        if (this.releaseBlinkNextGameTick) {
            this.releaseBlink();
            this.releaseBlinkNextGameTick = false;
        }
    }
    @EventTarget(Priority.LOWEST)
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled() || !this.active) {
            this.compensateNextTick = false;
            return;
        }
        if (this.compensateNextTick && !this.displaceThisTick) {
            this.compensateNextTick = false;
            mc.thePlayer.movementInput.moveStrafe = this.displaceLeft ? -1 : 1;
            return;
        }
        if (!this.displaceThisTick || this.hasKB) {
            return;
        }
        if (!this.anyMovementKey()) {
            return;
        }
        mc.thePlayer.movementInput.moveForward = 1;
        this.compensateNextTick = true;
    }
    @EventTarget(Priority.HIGH)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.POST) {
            return;
        }
        if (!this.blink.getValue() || !this.active || !this.displaceThisTick || this.releaseBlinkNextGameTick) {
            return;
        }
        if (!(event.getPacket() instanceof C03PacketPlayer)) {
            return;
        }
        if (Myau.blinkManager.getBlinkingModule() == BlinkModules.DISPLACE) {
            return;
        }
        Myau.blinkManager.setBlinkState(true, BlinkModules.DISPLACE);
        this.releaseBlinkNextGameTick = true;
    }

    @EventTarget(Priority.LOWEST)
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE || !this.isEnabled()) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            this.active = false;
            this.compensateNextTick = false;
            this.wasDisplacingLastTick = false;
            return;
        }
        this.tickCounter++;
        int currentTick = this.tickCounter;
        this.pruneTargetDelayStates();
        if (this.hasKnockback.getValue() && EnchantmentHelper.getKnockbackModifier(mc.thePlayer) <= 0) {
            this.active = false;
            this.displaceThisTick = false;
            this.compensateNextTick = false;
            this.wasDisplacingLastTick = false;
            return;
        }
        if (mc.currentScreen != null) {
            this.active = false;
            this.displaceThisTick = false;
            this.compensateNextTick = false;
            this.wasDisplacingLastTick = false;
            return;
        }
        EntityPlayer target = null;

        boolean attacking = mc.gameSettings.keyBindAttack.isKeyDown() || this.isKillAuraActive();
        if (attacking) {
            target = this.findClosestTarget(9.0);
        }

        boolean hasKBEnchant = EnchantmentHelper.getKnockbackModifier(mc.thePlayer) > 0;
        this.active = target != null && (hasKBEnchant || this.anyMovementKey());
        if (!this.active) {
            this.displaceThisTick = false;
            this.compensateNextTick = false;
            this.wasDisplacingLastTick = false;
            return;
        }

        Float voidYaw = this.findVoid.getValue() ? this.findVoidYaw(target) : null;
        if (voidYaw == null) {
            this.displaceLeft = this.direction.getValue() == 0;
        }
        this.hasKB = hasKBEnchant;
        this.displaceThisTick = !this.displaceThisTick;
        if (this.displaceThisTick && !this.shouldDisplaceInCurrentWindow(target, currentTick)) {
            this.displaceThisTick = false;
            this.compensateNextTick = false;
            this.wasDisplacingLastTick = false;
            return;
        }
        if (!this.displaceThisTick && this.wasDisplacingLastTick) {
            int key = mc.gameSettings.keyBindAttack.getKeyCode();
            if (key != 0) {
                KeyBinding.onTick(key);
            }
        }
        this.wasDisplacingLastTick = this.displaceThisTick;
        if (!this.displaceThisTick) {
            return;
        }

        float baseYaw;
        if (voidYaw != null) {
            baseYaw = voidYaw;
        } else {
            baseYaw = event.getYaw();
            float offset = this.yawOffset.getValue();
            baseYaw = this.displaceLeft ? baseYaw - offset : baseYaw + offset;
        }

        event.setRotation(baseYaw, event.getNewPitch(), ROTATION_PRIORITY);
        event.setPervRotation(baseYaw, ROTATION_PRIORITY);
    }
    private boolean isKillAuraActive() {
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        return killAura != null
                && killAura.isEnabled()
                && killAura.isAttackAllowed()
                && killAura.getTarget() != null;
    }
    private EntityPlayer findClosestTarget(double maxRange) {
        EntityPlayer closest = null;
        double closestDist = maxRange;
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityPlayer) || entity == mc.thePlayer) {
                continue;
            }
            if (entity.isDead || ((EntityPlayer) entity).deathTime != 0) {
                continue;
            }
            double dist = mc.thePlayer.getDistanceToEntity(entity);
            if (dist < closestDist) {
                closest = (EntityPlayer) entity;
                closestDist = dist;
            }
        }
        return closest;
    }
}
