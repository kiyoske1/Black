package com.kiyoske.blackmarket;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(BlackMarketMod.MODID)
public class BlackMarketMod {
    public static final String MODID = "blackmarket";

    public BlackMarketMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        Goods.ITEMS.register(bus);
        bus.addListener((BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey() == CreativeModeTabs.INGREDIENTS) Goods.ALL.values().forEach(g -> e.accept(g.item().get()));
        });
    }
}
