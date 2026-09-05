package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.util.KeyBindUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import org.lwjgl.input.Keyboard;

import java.util.Objects;

public class Freelook extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    public static boolean perspectiveToggled;
    public static float cameraYaw;
    public static float cameraPitch;

    private boolean prevKeyState;
    private int previousPerspective;
    private float lastFov;
    
    public final IntProperty freelookKey = new IntProperty("key", 56);
    public final BooleanProperty hold = new BooleanProperty("hold", true);
    public final BooleanProperty invertPitch = new BooleanProperty("invert-pitch", false);
    public final BooleanProperty lockPitch = new BooleanProperty("lock-pitch", true);
    public final BooleanProperty customFov = new BooleanProperty("custom-fov", false);
    public final IntProperty fov = new IntProperty("fov", 90, 10, 150);

    public Freelook() {
        super("Freelook", false);
    }

    @EventTarget(Priority.LOWEST)
    public void onTick(TickEvent event) {
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            if (mc.currentScreen != null) {
                return;
            }
            
            boolean down = Keyboard.isKeyDown(freelookKey.getValue());
            if (down != prevKeyState) {
                onPressed(down);
                prevKeyState = down;
            }
        }
    }

    private void onPressed(boolean state) {
        if (!isEnabled()) {
            if (perspectiveToggled) {
                resetPerspective();
            }
            return;
        }
        if (state) {
            cameraYaw = mc.thePlayer.rotationYaw;
            cameraPitch = mc.thePlayer.rotationPitch;
            if (perspectiveToggled) {
                resetPerspective();
            } else {
                enterPerspective();
            }
        } else if (hold.getValue()) {
            resetPerspective();
        }
    }

    private void enterPerspective() {
        perspectiveToggled = true;
        previousPerspective = mc.gameSettings.thirdPersonView;
        applyThirdPersonView(1);
        lastFov = mc.gameSettings.fovSetting;
    }

    public void resetPerspective() {
        perspectiveToggled = false;
        applyThirdPersonView(previousPerspective);
        if (mc.currentScreen == null && mc.inGameHasFocus) {
            mc.mouseHelper.grabMouseCursor();
        }
        if (hold.getValue() || mc.gameSettings.fovSetting == lastFov || customFov.getValue()) {
            mc.gameSettings.fovSetting = lastFov;
        }
    }

    public static boolean overrideMouse(Minecraft mc) {
        if (!mc.inGameHasFocus) {
            return false;
        }
        if (!perspectiveToggled) {
            return true;
        }
        mc.mouseHelper.mouseXYChange();
        float sens = mc.gameSettings.mouseSensitivity * 0.6f + 0.2f;
        float mult = sens * sens * sens * 8.0f;
        
        int dx = Mouse.getDX();
        int dy = Mouse.getDY();
        float fdx = dx * mult;
        float fdy = dy * mult;
        cameraYaw += fdx * 0.15f;
        if (getCameraModule() != null && getCameraModule().invertPitch.getValue()) {
            fdy = -fdy;
        }
        cameraPitch += fdy * 0.15f;
        if (getCameraModule() != null && getCameraModule().lockPitch.getValue()) {
            cameraPitch = Math.max(-90f, Math.min(90f, cameraPitch));
        }
        if (getCameraModule() != null && getCameraModule().customFov.getValue()) {
            mc.gameSettings.fovSetting = getCameraModule().fov.getValue();
        }
        return false;
    }

    private static Freelook getCameraModule() {
        return (Freelook) myau.Myau.moduleManager.getModule(Freelook.class);
    }

    @Override
    public void onDisabled() {
        if (perspectiveToggled) {
            perspectiveToggled = false;
            applyThirdPersonView(0);
            if (mc.currentScreen == null && mc.inGameHasFocus) {
                mc.mouseHelper.grabMouseCursor();
            }
            mc.gameSettings.fovSetting = lastFov;
        }
    }

    private void applyThirdPersonView(int view) {
        if (view < 0) {
            view = 0;
        } else if (view > 2) {
            view = 2;
        }

        mc.gameSettings.thirdPersonView = view;
        if (mc.entityRenderer != null) {
            if (view == 0) {
                mc.entityRenderer.loadEntityShader(mc.getRenderViewEntity());
            } else if (view == 1) {
                mc.entityRenderer.loadEntityShader((Entity) null);
            }
        }
        if (mc.renderGlobal != null) {
            mc.renderGlobal.setDisplayListEntitiesDirty();
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{freelookKey.getValue() == 0 ? "Unbound" : KeyBindUtil.getKeyName(freelookKey.getValue())};
    }
}
