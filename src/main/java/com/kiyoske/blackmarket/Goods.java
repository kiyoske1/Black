package com.kiyoske.blackmarket;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraft.nbt.CompoundTag;

import java.util.LinkedHashMap;
import java.util.Map;

/** Каталог товаров, динамические цены, склад редких товаров. */
public final class Goods {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BlackMarketMod.MODID);
    public static final Map<String, Good> ALL = new LinkedHashMap<>();

    /** buyRep < 0 — купить нельзя (органы). */
    public record Good(String id, RegistryObject<Item> item, int price, boolean organ, int buyRep, int stockMax) {}

    private static void def(String id, int price, boolean organ, int buyRep, int stock, Rarity r) {
        RegistryObject<Item> o = ITEMS.register(id, () -> new Item(new Item.Properties().stacksTo(organ ? 64 : 16).rarity(r)));
        ALL.put(id, new Good(id, o, price, organ, buyRep, stock));
    }

    static {
        def("heart", 250, true, -1, 0, Rarity.COMMON);
        def("liver", 180, true, -1, 0, Rarity.COMMON);
        def("kidney", 220, true, -1, 0, Rarity.COMMON);
        def("lung", 160, true, -1, 0, Rarity.COMMON);
        def("eye", 300, true, -1, 0, Rarity.COMMON);
        def("contraband_crate", 120, false, 0, 8, Rarity.UNCOMMON);
        def("fake_passport", 400, false, 40, 3, Rarity.RARE);
        def("stolen_artifact", 900, false, 150, 2, Rarity.EPIC);
        def("black_diamond", 1500, false, 300, 1, Rarity.EPIC);
    }

    private Goods() {}

    public static Good of(ItemStack st) {
        if (st.isEmpty()) return null;
        for (Good g : ALL.values()) if (st.is(g.item().get())) return g;
        return null;
    }

    public static Component name(Item i) { return i.getDescription(); }

    /** Раз в 10 минут пересчитываем рыночные множители и склад. */
    public static CompoundTag market(BMData d, long now) {
        CompoundTag m = BMData.sub(d.root, "market");
        if (now >= m.getLong("rollAt")) {
            m.putLong("rollAt", now + 12000);
            for (Good g : ALL.values()) {
                m.putInt("m_" + g.id(), 60 + BMData.RNG.nextInt(121));
                m.putInt("s_" + g.id(), BMData.RNG.nextInt(g.stockMax() + 1));
            }
            d.setDirty();
        }
        return m;
    }

    public static int mult(CompoundTag m, Good g) { int v = m.getInt("m_" + g.id()); return v == 0 ? 100 : v; }
    public static int stock(CompoundTag m, Good g) { return m.getInt("s_" + g.id()); }
    public static int marketPrice(CompoundTag m, Good g) { return g.price() * mult(m, g) / 100; }
    public static int sellPrice(CompoundTag m, Good g, int rep) { return marketPrice(m, g) * (100 + Math.min(25, Math.max(0, rep) / 20)) / 100; }
    public static int buyPrice(CompoundTag m, Good g) { return marketPrice(m, g) * 16 / 10; }
}
