package com.bbenshab.vocalist;

import com.bbenshab.vocalist.brain.Minds;
import com.bbenshab.vocalist.cmd.VocalistCmd;
import com.bbenshab.vocalist.settings.Settings;
import com.bbenshab.vocalist.talk.TalkHub;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Vocalist - talk to any creature on the server.
 *
 * Author: Boaz Ben Shabat
 */
@Mod(Vocalist.ID)
public final class Vocalist {

    public static final String ID = "vocalist";
    public static final Logger LOG = LogUtils.getLogger();

    private static Settings settings;

    public Vocalist() {
        settings = Settings.load();
        MinecraftForge.EVENT_BUS.addListener(this::onCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarting);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGH, this::onTouch);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::onChat);
    }

    public static Settings settings() {
        return settings;
    }

    private void onCommands(RegisterCommandsEvent event) {
        VocalistCmd.register(event.getDispatcher());
    }

    private void onServerStarting(ServerStartingEvent event) {
        TalkHub.attach(event.getServer());
        Minds.rebuild();
        LOG.info("Vocalist ready. Minds: {}", Minds.describe());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        settings.save();
        TalkHub.detach();
    }

    /** Sneak + right-click a creature with an empty hand to start talking. */
    private void onTouch(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) {
            return;
        }
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer speaker)) {
            return;
        }
        if (!speaker.isShiftKeyDown() || !speaker.getMainHandItem().isEmpty()) {
            return;
        }
        if (!(event.getTarget() instanceof net.minecraft.world.entity.LivingEntity creature)
                || event.getTarget() instanceof net.minecraft.world.entity.player.Player) {
            return;
        }
        event.setCanceled(true);
        TalkHub.open(speaker, creature);
    }

    /** While talking, chat messages go to the creature instead of global chat. */
    private void onChat(ServerChatEvent event) {
        net.minecraft.server.level.ServerPlayer speaker = event.getPlayer();
        if (!TalkHub.isTalking(speaker)) {
            return;
        }
        event.setCanceled(true);
        TalkHub.say(speaker, event.getMessage().getString());
    }
}
