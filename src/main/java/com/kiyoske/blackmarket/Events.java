package com.kiyoske.blackmarket;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BlackMarketMod.MODID)
public final class Events {
    private Events() {}

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent e) { Cmds.register(e.getDispatcher()); }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        var s = e.getServer();
        int t = s.getTickCount();
        long now = s.overworld().getGameTime();
        if (t % 10 == 0) Npcs.tickNpcs(s, now);
        if (t % 20 == 0) { Npcs.tickPlayers(s, now); Deals.settle(s, now); }
    }

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        BMData d = BMData.get(p.getServer());
        CompoundTag old = p.getPersistentData();
        CompoundTag t = d.p(p.getUUID());
        // миграция баланса из старой версии мода (1.0.0)
        if (old.contains("blackmarket_credits") && !t.getBoolean("migrated")) {
            d.addCredits(p.getUUID(), old.getInt("blackmarket_credits"));
            t.putBoolean("migrated", true);
        }
        Deals.claim(p);
    }

    @SubscribeEvent
    public static void join(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide()) return;
        if (e.getEntity().getPersistentData().getBoolean("bm_npc") && !Npcs.LIVE.contains(e.getEntity().getUUID())) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void hurt(LivingHurtEvent e) {
        if (e.getSource().getEntity() instanceof ServerPlayer p)
            Npcs.onHurt(p, e.getEntity(), p.serverLevel().getGameTime(), p.getServer());
    }

    @SubscribeEvent
    public static void death(LivingDeathEvent e) {
        if (e.getEntity().level().isClientSide()) return;
        ServerPlayer killer = e.getSource().getEntity() instanceof ServerPlayer p ? p : null;
        var server = e.getEntity().getServer();
        if (server != null) Npcs.onDeath(killer, e.getEntity(), server);
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent e) { Npcs.dismissAll(e.getServer()); }
}
