package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.util.ItemUtil;
import myau.util.KeyBindUtil;
import myau.util.RotationUtil;
import myau.util.TeamUtil;
import myau.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityBat;
import net.minecraft.entity.passive.EntitySquid;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;

import java.util.WeakHashMap;

public class Autoblock extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final long TICK_MS = 50L;
    private static final int MODE_LEGIT = 0;
    private static final int MODE_BLATANT = 1;
    public final ModeProperty mode = new ModeProperty("mode", MODE_LEGIT,
            new String[]{"LEGIT", "BLATANT"});
    public final FloatProperty range = new FloatProperty("range", 3.5F, 1.0F, 6.0F);
    public final IntProperty maxHurtTime = new IntProperty("max-hurt-time", 200, 50, 500, 10);
    public final IntProperty maxHoldDuration = new IntProperty("max-hold-duration", 150, 0, 500, 10);
    public final BooleanProperty forceBlockAnimation =
            new BooleanProperty("force-block-animation", false);
    public final BooleanProperty forceBlockOnlyInRange =
            new BooleanProperty("force-block-only-in-range", true,
                    () -> this.forceBlockAnimation.getValue());
    public final PercentProperty lagChance = new PercentProperty("lag-chance", 0,
            () -> this.mode.getValue() == MODE_BLATANT);
    public final IntProperty lagMaxDuration = new IntProperty("lag-max-duration", 200, 0, 1000,
            () -> this.mode.getValue() == MODE_BLATANT && this.lagChance.getValue() > 0);
    public final BooleanProperty endLagOnAttack = new BooleanProperty("end-lag-on-attack", true,
            () -> this.mode.getValue() == MODE_BLATANT && this.lagChance.getValue() > 0);
    public final BooleanProperty blockAgainAfterLag =
            new BooleanProperty("block-again-after-lag", true,
                    () -> this.mode.getValue() == MODE_BLATANT && this.lagChance.getValue() > 0);
    public final BooleanProperty requireLeftClick = new BooleanProperty("require-left-click", false);
    public final BooleanProperty requireRightClick = new BooleanProperty("require-right-click", false);
    public final BooleanProperty requireDamaged = new BooleanProperty("require-damaged", false);

    private final TimerUtil blockTimer = new TimerUtil();
    private final TimerUtil lagTimer = new TimerUtil();

    private final WeakHashMap<EntityLivingBase, Boolean> firstSwingUsed =
            new WeakHashMap<EntityLivingBase, Boolean>();
    private EntityLivingBase target;
    private boolean blocking;
    private boolean lagging;
    private boolean wasAttackKeyDown;

    private int lastHurtTime;

    private boolean heldThisHurt;

    private boolean hurtTriggered;

    public Autoblock() {
        super("Auto Block", false);
    }
    public boolean isForcingAnimation() {
        return this.isEnabled() && this.forceBlockAnimation.getValue() && this.animationAllowed;
    }
    private boolean animationAllowed;
    public boolean isActive() {
        return this.isEnabled() && (this.blocking || this.lagging);
    }
    @Override
    public void onDisabled() {
        this.stopBlocking();

        KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
        this.stopLag();
        this.target = null;
        this.wasAttackKeyDown = false;
        this.lastHurtTime = 0;
        this.heldThisHurt = false;
        this.hurtTriggered = false;
        this.animationAllowed = false;
        this.firstSwingUsed.clear();
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || this.deferToKillAura()) {
            return;
        }
        if (!(event.getTarget() instanceof EntityLivingBase)) {
            return;
        }
        EntityLivingBase attacked = (EntityLivingBase) event.getTarget();
        if (this.firstSwingUsed.put(attacked, Boolean.TRUE) != null) {
            return;
        }
        if (!this.blocking && !this.lagging) {
            this.hurtTriggered = false;
            this.startBlocking();
        }
    }
    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE
                || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (this.deferToKillAura() || !ItemUtil.isHoldingSword()) {
            this.stopBlocking();
            this.stopLag();
            this.animationAllowed = false;
            return;
        }

        this.manageLag();

        int hurtTime = mc.thePlayer.hurtTime;
        if (hurtTime > this.lastHurtTime) {
            this.heldThisHurt = false;
        }
        this.lastHurtTime = hurtTime;

        this.target = this.findNearbyTarget();
        boolean attackKeyDown = KeyBindUtil.isKeyDown(mc.gameSettings.keyBindAttack.getKeyCode());
        if (!attackKeyDown && this.wasAttackKeyDown) {
            this.firstSwingUsed.clear();
        }
        this.wasAttackKeyDown = attackKeyDown;
        boolean inRange = this.target != null
                && mc.thePlayer.getDistanceToEntity(this.target) <= this.range.getValue();
        boolean rangedTarget = this.target != null && isRanged(this.target.getHeldItem());
        if (!this.conditionsMet() || !inRange || rangedTarget) {
            this.stopBlocking();
        } else if (!this.blocking && !this.lagging && !this.heldThisHurt
                && this.shouldStart(hurtTime)) {
            this.hurtTriggered = true;
            this.startBlocking();
        }

        if (this.blocking && this.blockTimer.hasTimeElapsed(this.maxHoldDuration.getValue())) {
            if (this.hurtTriggered) {
                this.heldThisHurt = true;
            }
            this.stopBlocking();
            this.maybeStartLag();
        }
        this.animationAllowed = !this.forceBlockOnlyInRange.getValue() || inRange;
    }

    private boolean shouldStart(int hurtTime) {
        return hurtTime > 0 && hurtTime * TICK_MS <= this.maxHurtTime.getValue();
    }

    private EntityLivingBase findNearbyTarget() {
        EntityLivingBase closest = null;
        double closestDistance = this.range.getValue();
        for (Object raw : mc.theWorld.loadedEntityList) {
            if (!(raw instanceof EntityLivingBase) || raw == mc.thePlayer) {
                continue;
            }
            Entity entity = (Entity) raw;
            if (entity instanceof EntityAnimal || entity instanceof EntityBat
                    || entity instanceof EntitySquid || entity instanceof EntityVillager) {
                continue;
            }
            EntityLivingBase living = (EntityLivingBase) entity;
            if (living.isDead || living.deathTime > 0 || !this.isHostile(living)) {
                continue;
            }
            double distance = RotationUtil.distanceToEntity(living);
            if (distance <= closestDistance) {
                closestDistance = distance;
                closest = living;
            }
        }
        return closest;
    }

    private boolean isHostile(EntityLivingBase living) {
        if (!(living instanceof EntityPlayer)) {
            return true;
        }
        EntityPlayer player = (EntityPlayer) living;
        return !TeamUtil.isFriend(player) && !TeamUtil.isSameTeam(player) && !TeamUtil.isBot(player);
    }

    private static boolean isRanged(ItemStack held) {
        return held != null && held.getItem() instanceof ItemBow;
    }
    private boolean conditionsMet() {
        if (this.requireLeftClick.getValue() && !this.attackIntended()) {
            return false;
        }
        if (this.requireRightClick.getValue()
                && !KeyBindUtil.isKeyDown(mc.gameSettings.keyBindUseItem.getKeyCode())) {
            return false;
        }

        return true;
    }
    private boolean attackIntended() {
        if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindAttack.getKeyCode())) {
            return true;
        }
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        return killAura != null && killAura.isEnabled()
                && !killAura.requirePress.getValue() && killAura.getTarget() != null;
    }
    private void startBlocking() {
        this.blocking = true;
        this.blockTimer.reset();
        int keyCode = mc.gameSettings.keyBindUseItem.getKeyCode();
        KeyBindUtil.setKeyBindState(keyCode, true);

        KeyBindUtil.pressKeyOnce(keyCode);
    }

    private void stopBlocking() {
        if (!this.blocking) {
            return;
        }
        this.blocking = false;
        KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
    }
    private boolean deferToKillAura() {
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        return killAura != null && killAura.isEnabled() && killAura.autoBlock.getValue() != 0;
    }
    private void maybeStartLag() {
        if (this.mode.getValue() != MODE_BLATANT || this.lagChance.getValue() <= 0
                || Myau.lagManager == null) {
            return;
        }
        if (Math.random() * 100.0 >= this.lagChance.getValue()) {
            return;
        }
        Myau.lagManager.setDelay(Math.max(1,
                (int) Math.round(this.lagMaxDuration.getValue() / (double) TICK_MS)));
        this.lagging = true;
        this.lagTimer.reset();
    }
    private void manageLag() {
        if (!this.lagging) {
            return;
        }
        if (Myau.lagManager == null) {
            this.lagging = false;
            return;
        }
        boolean elapsed = this.lagTimer.hasTimeElapsed(this.lagMaxDuration.getValue());
        boolean attacking = this.endLagOnAttack.getValue() && this.attackPending();
        if (!elapsed && !attacking) {
            return;
        }
        this.stopLag();
        if (this.blockAgainAfterLag.getValue()) {
            this.startBlocking();
        }
    }

    private void stopLag() {
        if (!this.lagging) {
            return;
        }
        this.lagging = false;
        if (Myau.lagManager != null) {
            Myau.lagManager.setDelay(0);
        }
    }
    private boolean attackPending() {
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        return killAura != null && killAura.isEnabled() && killAura.getTarget() != null;
    }
}
