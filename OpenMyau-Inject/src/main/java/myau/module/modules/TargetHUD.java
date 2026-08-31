package myau.module.modules;

import myau.Myau;
import myau.enums.ChatColors;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.Render2DPostEvent;
import myau.module.Module;
import myau.util.ColorUtil;
import myau.util.shader.BlurUtils;
import myau.util.shader.RoundedShader;
import myau.ui.clickgui.GuiRender;
import myau.util.RenderUtil;
import myau.util.TeamUtil;
import myau.util.TimerUtil;
import myau.property.properties.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C02PacketUseEntity.Action;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public class TargetHUD extends Module {
    private static final int MODE_RAVEN = 1;

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final DecimalFormat healthFormat = new DecimalFormat("0.0", new DecimalFormatSymbols(Locale.US));
    private static final DecimalFormat diffFormat = new DecimalFormat("+0.0;-0.0", new DecimalFormatSymbols(Locale.US));
    private final TimerUtil lastAttackTimer = new TimerUtil();
    private final TimerUtil animTimer = new TimerUtil();
    private EntityLivingBase lastTarget = null;
    private EntityLivingBase target = null;
    private ResourceLocation headTexture = null;
    private float oldHealth = 0.0F;
    private float newHealth = 0.0F;
    private float maxHealth = 0.0F;

    private float ghostBar = Float.NaN;
    private double lastHealth = -1.0;
    private final BarTimer healthBarTimer = new BarTimer();
    private static final float GHOST_MS = 500.0F;
    private static final float GHOST_MIN_WIDTH = 3.0F;

    private static final float RAVEN_PADDING = 8.0F;

    private static final float RAVEN_RADIUS = 10.0F;
    private static final float RAVEN_MODERN_RADIUS = 8.0F;
    private static final int RAVEN_BLOOM_PASSES = 3;
    private static final float RAVEN_BLOOM_RADIUS = 2.0F;
    private static final int RAVEN_BLUR_PASSES = 2;
    private static final float RAVEN_BLUR_RADIUS = 3.0F;
    private static final int RAVEN_MAX_BACKGROUND_ALPHA = 210;
    private static final int RAVEN_MAX_OUTLINE_ALPHA = 255;
    private static final float RAVEN_BAR_RADIUS = 4.0F;
    private static final float RAVEN_BAR_HEIGHT = 5.0F;
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"MYAU", "RAVEN"});
    public final ModeProperty ravenStyle = new ModeProperty("raven-style", 0, new String[]{"MODERN", "LEGACY"},
            () -> this.mode.getValue() == MODE_RAVEN);
    public final ModeProperty color = new ModeProperty("color", 0, new String[]{"DEFAULT", "HUD"});
    public final ModeProperty posX = new ModeProperty("position-x", 1, new String[]{"LEFT", "MIDDLE", "RIGHT"});
    public final ModeProperty posY = new ModeProperty("position-y", 1, new String[]{"TOP", "MIDDLE", "BOTTOM"});
    public final FloatProperty scale = new FloatProperty("scale", 1.0F, 0.5F, 1.5F);
    public final IntProperty offX = new IntProperty("offset-x", 0, -255, 255);
    public final IntProperty offY = new IntProperty("offset-y", 40, -255, 255);
    public final PercentProperty background = new PercentProperty("background", 25);
    public final BooleanProperty head = new BooleanProperty("head", true);
    public final BooleanProperty indicator = new BooleanProperty("indicator", true);
    public final BooleanProperty outline = new BooleanProperty("outline", false);
    public final BooleanProperty animations = new BooleanProperty("animations", true);
    public final BooleanProperty shadow = new BooleanProperty("shadow", true);
    public final BooleanProperty kaOnly = new BooleanProperty("ka-only", true);
    public final BooleanProperty chatPreview = new BooleanProperty("chat-preview", false);
    private EntityLivingBase resolveTarget() {
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        if (killAura.isEnabled() && killAura.isAttackAllowed() && TeamUtil.isEntityLoaded(killAura.getTarget())) {
            return killAura.getTarget();
        } else if (!(Boolean) this.kaOnly.getValue()
                && !this.lastAttackTimer.hasTimeElapsed(1500L)
                && TeamUtil.isEntityLoaded(this.lastTarget)) {
            return this.lastTarget;
        } else {
            return this.chatPreview.getValue() && mc.currentScreen instanceof GuiChat ? mc.thePlayer : null;
        }
    }
    private ResourceLocation getSkin(EntityLivingBase entityLivingBase) {
        if (entityLivingBase instanceof EntityPlayer) {
            NetworkPlayerInfo playerInfo = mc.getNetHandler().getPlayerInfo(entityLivingBase.getName());
            if (playerInfo != null) {
                return playerInfo.getLocationSkin();
            }
        }
        return null;
    }
    private Color getTargetColor(EntityLivingBase entityLivingBase) {
        if (entityLivingBase instanceof EntityPlayer) {
            if (TeamUtil.isFriend((EntityPlayer) entityLivingBase)) {
                return Myau.friendManager.getColor();
            }
            if (TeamUtil.isTarget((EntityPlayer) entityLivingBase)) {
                return Myau.targetManager.getColor();
            }
        }
        switch (this.color.getValue()) {
            case 0:
                if (!(entityLivingBase instanceof EntityPlayer)) {
                    return new Color(-1);
                }
                return TeamUtil.getTeamColor((EntityPlayer) entityLivingBase, 1.0F);
            case 1:
                int rgb = ((HUD) Myau.moduleManager.modules.get(HUD.class)).getColor(System.currentTimeMillis()).getRGB();
                return new Color(rgb);
            default:
                return new Color(-1);
        }
    }
    public TargetHUD() {
        super("Target HUD", false, true);
    }
    @EventTarget
    public void onRender(Render2DPostEvent event) {
        if (this.isEnabled() && mc.thePlayer != null) {
            EntityLivingBase entityLivingBase = this.target;
            this.target = this.resolveTarget();
            if (this.target != null) {
                float health = (mc.thePlayer.getHealth() + mc.thePlayer.getAbsorptionAmount()) / 2.0F;
                float abs = this.target.getAbsorptionAmount() / 2.0F;
                float heal = this.target.getHealth() / 2.0F + abs;
                if (this.target != entityLivingBase) {
                    this.headTexture = null;
                    this.animTimer.setTime();
                    this.oldHealth = heal;
                    this.newHealth = heal;
                }
                if (!this.animations.getValue() || this.animTimer.hasTimeElapsed(150L)) {
                    this.oldHealth = this.newHealth;
                    this.newHealth = heal;
                    this.maxHealth = this.target.getMaxHealth() / 2.0F;
                    if (this.oldHealth != this.newHealth) {
                        this.animTimer.reset();
                    }
                }
                ResourceLocation resourceLocation = this.getSkin(this.target);
                if (resourceLocation != null) {
                    this.headTexture = resourceLocation;
                }
                float elapsedTime = (float) Math.min(Math.max(this.animTimer.getElapsedTime(), 0L), 150L);
                float healthRatio = Math.min(Math.max(RenderUtil.lerpFloat(this.newHealth, this.oldHealth, elapsedTime / 150.0F) / this.maxHealth, 0.0F), 1.0F);
                Color targetColor = this.getTargetColor(this.target);
                Color healthBarColor = this.color.getValue() == 0 ? ColorUtil.getHealthBlend(healthRatio) : targetColor;
                float healthDeltaRatio = Math.min(Math.max((health - heal + 1.0F) / 2.0F, 0.0F), 1.0F);
                Color healthDeltaColor = ColorUtil.getHealthBlend(healthDeltaRatio);
                ScaledResolution scaledResolution = new ScaledResolution(mc);
                String targetNameText = ChatColors.formatColor(String.format("&r%s&r", TeamUtil.stripName(this.target)));
                int targetNameWidth = mc.fontRendererObj.getStringWidth(targetNameText);
                String healthText = ChatColors.formatColor(
                        String.format("&r&f%s%s❤&r", healthFormat.format(heal), abs > 0.0F ? "&6" : "&c")
                );
                int healthTextWidth = mc.fontRendererObj.getStringWidth(healthText);
                String statusText = ChatColors.formatColor(String.format("&r&l%s&r", heal == health ? "D" : (heal < health ? "W" : "L")));
                int statusTextWidth = mc.fontRendererObj.getStringWidth(statusText);
                String healthDiffText = ChatColors.formatColor(
                        String.format("&r%s&r", heal == health ? "0.0" : diffFormat.format(health - heal))
                );
                int healthDiffWidth = mc.fontRendererObj.getStringWidth(healthDiffText);
                float barContentWidth = Math.max(
                        (float) targetNameWidth + (this.indicator.getValue() ? 2.0F + (float) statusTextWidth + 2.0F : 0.0F),
                        (float) healthTextWidth + (this.indicator.getValue() ? 2.0F + (float) healthDiffWidth + 2.0F : 0.0F)
                );
                float headIconOffset = this.head.getValue() && this.headTexture != null ? 25.0F : 0.0F;
                float barTotalWidth = Math.max(headIconOffset + 70.0F, headIconOffset + 2.0F + barContentWidth + 2.0F);
                float posX = this.offX.getValue().floatValue() / this.scale.getValue();
                switch (this.posX.getValue()) {
                    case 1:
                        posX += (float) scaledResolution.getScaledWidth() / this.scale.getValue() / 2.0F - barTotalWidth / 2.0F;
                        break;
                    case 2:
                        posX *= -1.0F;
                        posX += (float) scaledResolution.getScaledWidth() / this.scale.getValue() - barTotalWidth;
                }
                float posY = this.offY.getValue().floatValue() / this.scale.getValue();
                switch (this.posY.getValue()) {
                    case 1:
                        posY += (float) scaledResolution.getScaledHeight() / this.scale.getValue() / 2.0F - 13.5F;
                        break;
                    case 2:
                        posY *= -1.0F;
                        posY += (float) scaledResolution.getScaledHeight() / this.scale.getValue() - 27.0F;
                }
                if (this.mode.getValue() == MODE_RAVEN) {
                    this.renderRaven(scaledResolution, targetNameText, healthRatio,
                            heal == health ? 0 : (heal < health ? 1 : -1), healthBarColor);
                    return;
                }
                GlStateManager.pushMatrix();
                GlStateManager.scale(this.scale.getValue(), this.scale.getValue(), 0.0F);
                GlStateManager.translate(posX, posY, -450.0F);
                RenderUtil.enableRenderState();
                int backgroundColor = new Color(0.0F, 0.0F, 0.0F, (float) this.background.getValue() / 100.0F).getRGB();
                int outlineColor = this.outline.getValue() ? targetColor.getRGB() : new Color(0, 0, 0, 0).getRGB();
                RenderUtil.drawOutlineRect(0.0F, 0.0F, barTotalWidth, 27.0F, 1.5F, backgroundColor, outlineColor);
                RenderUtil.drawRect(headIconOffset + 2.0F, 22.0F, barTotalWidth - 2.0F, 25.0F, ColorUtil.darker(healthBarColor, 0.2F).getRGB());
                RenderUtil.drawRect(headIconOffset + 2.0F, 22.0F, headIconOffset + 2.0F + healthRatio * (barTotalWidth - 2.0F - headIconOffset - 2.0F), 25.0F, healthBarColor.getRGB());
                RenderUtil.disableRenderState();
                GlStateManager.disableDepth();
                GlStateManager.enableBlend();
                GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                mc.fontRendererObj.drawString(targetNameText, headIconOffset + 2.0F, 2.0F, -1, this.shadow.getValue());
                mc.fontRendererObj.drawString(healthText, headIconOffset + 2.0F, 12.0F, -1, this.shadow.getValue());
                if (this.indicator.getValue()) {
                    mc.fontRendererObj.drawString(statusText, barTotalWidth - 2.0F - (float) statusTextWidth, 2.0F, healthDeltaColor.getRGB(), this.shadow.getValue());
                    mc.fontRendererObj.drawString(healthDiffText, barTotalWidth - 2.0F - (float) healthDiffWidth, 12.0F, ColorUtil.darker(healthDeltaColor, 0.8F).getRGB(), this.shadow.getValue());
                }
                if (this.head.getValue() && this.headTexture != null) {
                    GlStateManager.color(1.0F, 1.0F, 1.0F);
                    mc.getTextureManager().bindTexture(this.headTexture);
                    Gui.drawScaledCustomSizeModalRect(2, 2, 8.0F, 8.0F, 8, 8, 23, 23, 64.0F, 64.0F);
                    Gui.drawScaledCustomSizeModalRect(2, 2, 40.0F, 8.0F, 8, 8, 23, 23, 64.0F, 64.0F);
                    GlStateManager.color(1.0F, 1.0F, 1.0F);
                }
                GlStateManager.disableBlend();
                GlStateManager.enableDepth();
                GlStateManager.popMatrix();
            }
        }
    }
    private void renderRaven(ScaledResolution resolution, String name, float healthRatio,
                             int status, Color barColor) {
        String text = name;
        if (this.indicator.getValue()) {
            text = text + " " + ChatColors.formatColor(status >= 0 ? "&aW" : "&cL");
        }
        double health = Math.min(Math.max(healthRatio, 0.0F), 1.0F);
        if (health != this.lastHealth) {
            this.healthBarTimer.start();
            this.lastHealth = health;
        }
        float scale = this.scale.getValue();
        float screenWidth = resolution.getScaledWidth() / scale;
        float screenHeight = resolution.getScaledHeight() / scale;
        float textWidth = mc.fontRendererObj.getStringWidth(text) + RAVEN_PADDING;
        float x = screenWidth / 2.0F - textWidth / 2.0F + this.offX.getValue() / scale;
        float y = screenHeight / 2.0F + 15.0F + this.offY.getValue() / scale;
        float left = x - RAVEN_PADDING;
        float top = y - RAVEN_PADDING;
        float right = x + textWidth;
        float barTop = y + mc.fontRendererObj.FONT_HEIGHT - 1.0F + RAVEN_PADDING;
        float bottom = barTop + 13.0F;
        int background = new Color(0, 0, 0, Math.round(210.0F * this.background.getValue() / 25.0F) > 255
                ? 255 : Math.round(210.0F * this.background.getValue() / 25.0F)).getRGB();
        int[] gradient = this.ravenGradient(barColor);
        boolean modern = this.ravenStyle.getValue() == 0 && BlurUtils.isReady() && RoundedShader.isReady();
        if (modern) {
            GlStateManager.disableDepth();
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            this.drawRavenModernPanel(left * scale, top * scale, right * scale, bottom * scale,
                    RAVEN_MODERN_RADIUS * scale);
        }
        GlStateManager.pushMatrix();
        GlStateManager.scale(scale, scale, 1.0F);
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        if (!modern) {
            if (this.outline.getValue()) {
                GuiRender.drawRoundedGradientOutlinedRect(left, top, right, bottom, RAVEN_RADIUS,
                        background, gradient[0], gradient[1]);
            } else {
                GuiRender.drawRoundedRect(left, top, right, bottom, RAVEN_RADIUS, background);
            }
        }
        float barLeft = left + 6.0F;
        float barRight = right - 6.0F;
        float barBottom = barTop + RAVEN_BAR_HEIGHT;
        GuiRender.drawRoundedRect(barLeft, barTop, barRight, barBottom, RAVEN_BAR_RADIUS,
                new Color(0, 0, 0, 110).getRGB());
        float healthBar = (int) (barRight + (barLeft - barRight) * (1.0F - health));
        boolean healing = false;
        if (!this.animations.getValue() || Float.isNaN(this.ghostBar)) {
            this.ghostBar = healthBar;
        } else if (healthBar != this.ghostBar && this.ghostBar - barLeft >= GHOST_MIN_WIDTH) {
            float difference = this.ghostBar - healthBar;
            if (difference > 0.0F) {
                this.ghostBar -= this.healthBarTimer.value(0.0F, difference);
            } else {
                healing = true;
                this.ghostBar = this.healthBarTimer.value(this.ghostBar, healthBar);
            }
        } else {
            this.ghostBar = healthBar;
        }
        if (this.ghostBar > barRight) {
            this.ghostBar = barRight;
        }

        GuiRender.drawRoundedRect(barLeft, barTop, this.ghostBar, barBottom, RAVEN_BAR_RADIUS,
                darken(gradient[1], 25));
        GuiRender.drawRoundedGradientRect(barLeft, barTop, healing ? this.ghostBar : healthBar,
                barBottom, RAVEN_BAR_RADIUS, gradient[0], gradient[1]);

        mc.fontRendererObj.drawString(text, x, y,
                new Color(220, 220, 220).getRGB(), this.shadow.getValue());
        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }
    private void drawRavenModernPanel(float left, float top, float right, float bottom, float radius) {
        int scaled = Math.round(RAVEN_MAX_BACKGROUND_ALPHA * this.background.getValue() / 25.0F);
        int fillAlpha = Math.min(RAVEN_MAX_BACKGROUND_ALPHA, Math.max(0, scaled));
        float width = right - left;
        float height = bottom - top;
        BlurUtils.prepareBloom();
        RoundedShader.drawRound(left, top, width, height, radius, new Color(0, 0, 0, fillAlpha).getRGB());
        BlurUtils.bloomEnd(RAVEN_BLOOM_PASSES, RAVEN_BLOOM_RADIUS);
        BlurUtils.prepareBlur();
        RoundedShader.drawRound(left, top, width, height, radius,
                new Color(0, 0, 0, RAVEN_MAX_OUTLINE_ALPHA).getRGB());
        BlurUtils.blurEnd(RAVEN_BLUR_PASSES, RAVEN_BLUR_RADIUS);
    }
    private int[] ravenGradient(Color barColor) {
        if (this.color.getValue() != 1) {
            int flat = barColor.getRGB();
            return new int[]{flat, flat};
        }
        HUD hud = (HUD) Myau.moduleManager.modules.get(HUD.class);
        long now = System.currentTimeMillis();
        return new int[]{hud.getColor(now, 0L).getRGB(), hud.getColor(now, 4L).getRGB()};
    }
    private static int darken(int color, int percent) {
        double factor = (100 - percent) / 100.0;
        int alpha = (color >> 24) & 0xFF;
        int red = (int) (((color >> 16) & 0xFF) * factor);
        int green = (int) (((color >> 8) & 0xFF) * factor);
        int blue = (int) ((color & 0xFF) * factor);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }
    private static final class BarTimer {
        private long started;
        private float cached = Float.NaN;
        private void start() {
            this.cached = Float.NaN;
            this.started = System.currentTimeMillis();
        }
        private float value(float from, float to) {
            if (!Float.isNaN(this.cached) && this.cached == to) {
                return this.cached;
            }
            float t = (System.currentTimeMillis() - this.started) / GHOST_MS;
            t = t < 0.5F ? 2.0F * t * t : -1.0F + (4.0F - 2.0F * t) * t;
            float value = from + t * (to - from);
            if ((to > from && value > to) || (to < from && value < to)) {
                value = to;
            }
            if (value == to) {
                this.cached = value;
            }
            return value;
        }
    }
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (event.getType() == EventType.SEND && event.getPacket() instanceof C02PacketUseEntity) {
            C02PacketUseEntity packet = (C02PacketUseEntity) event.getPacket();
            if (packet.getAction() != Action.ATTACK) {
                return;
            }
            Entity entity = packet.getEntityFromWorld(mc.theWorld);
            if (entity instanceof EntityLivingBase) {
                if (entity instanceof EntityArmorStand) {
                    return;
                }
                this.lastAttackTimer.reset();
                this.lastTarget = (EntityLivingBase) entity;
            }
        }
    }
}
