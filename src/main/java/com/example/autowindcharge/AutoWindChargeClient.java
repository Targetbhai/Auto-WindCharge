package com.example.autowindcharge;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Singleplayer-only, always on (Minecraft 1.21.11, Yarn mappings).
 * Throw an ender pearl, then throw a wind charge yourself. Right before
 * your wind charge leaves, the mod aims it at where your pearl will be,
 * so the burst hits the pearl and flings it upward.
 */
public class AutoWindChargeClient implements ClientModInitializer {
    private static final double WIND_CHARGE_SPEED = 1.5; // blocks/tick
    private static final int MAX_LOOKAHEAD = 30;         // ticks to predict

    private static boolean restorePending = false;
    private static float oldYaw, oldPitch;

    @Override
    public void onInitializeClient() {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!world.isClient()) return ActionResult.PASS;
            MinecraftClient client = MinecraftClient.getInstance();
            if (!(player instanceof ClientPlayerEntity self) || !client.isInSingleplayer()) {
                return ActionResult.PASS;
            }
            if (!self.getStackInHand(hand).isOf(Items.WIND_CHARGE)) return ActionResult.PASS;

            List<EnderPearlEntity> pearls = client.world.getEntitiesByClass(
                    EnderPearlEntity.class,
                    self.getBoundingBox().expand(48),
                    p -> p.getOwner() == self);
            if (pearls.isEmpty()) return ActionResult.PASS;

            Vec3d eye = self.getEyePos();
            Vec3d target = predictIntercept(eye, pearls.get(0));
            if (target == null) return ActionResult.PASS;

            Vec3d d = target.subtract(eye);
            double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
            float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
            float pitch = (float) -Math.toDegrees(Math.atan2(d.y, horiz));

            oldYaw = self.getYaw();
            oldPitch = self.getPitch();
            restorePending = true;
            self.setYaw(yaw);
            self.setPitch(pitch);
            return ActionResult.PASS; // the normal throw continues, now aimed
        });

        // Give your camera back right after the throw
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (restorePending && client.player != null) {
                client.player.setYaw(oldYaw);
                client.player.setPitch(oldPitch);
            }
            restorePending = false;
        });
    }

    /** Simulate the pearl and find the first point a wind charge can reach in time. */
    private static Vec3d predictIntercept(Vec3d eye, EnderPearlEntity pearl) {
        Vec3d pos = pearl.getEntityPos();
        Vec3d vel = pearl.getVelocity();
        Vec3d prev = pos;
        for (int t = 1; t <= MAX_LOOKAHEAD; t++) {
            prev = pos;
            pos = pos.add(vel);               // pearl follows its curved path
            vel = vel.multiply(0.99).add(0, -0.03, 0);
            double reach = WIND_CHARGE_SPEED * t; // wind charge flies straight
            if (pos.distanceTo(eye) <= reach) {
                // Pick the tick where both arrive closest to the same time
                double now = Math.abs(pos.distanceTo(eye) - reach);
                double before = Math.abs(prev.distanceTo(eye) - WIND_CHARGE_SPEED * (t - 1));
                return before < now ? prev : pos;
            }
        }
        return null;
    }
}
