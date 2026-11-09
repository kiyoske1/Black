package com.kiyoske.blackmarket;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.UUID;

/** Случайные заказы криминальных NPC и аукционы между игроками. */
public final class Deals {
    private Deals() {}

    static void msg(ServerPlayer p, String s) { p.sendSystemMessage(Component.literal(s)); }
    static Component line(String pre, Item i, String post) { return Component.literal(pre).append(Goods.name(i)).append(Component.literal(post)); }

    static ListTag list(CompoundTag t, String k) { if (!t.contains(k, 9)) t.put(k, new ListTag()); return t.getList(k, 10); }

    // vanilla-товары для заказов: id, цена за штуку, мин, макс
    static final Object[][] VANILLA = {
        {"minecraft:diamond", 80, 2, 5}, {"minecraft:gold_ingot", 25, 8, 24}, {"minecraft:ender_pearl", 40, 2, 6},
        {"minecraft:emerald", 30, 6, 16}, {"minecraft:netherite_scrap", 150, 1, 3}
    };

    // ================= ЗАКАЗЫ =================
    public static void tickPlayer(MinecraftServer s, BMData d, ServerPlayer p, CompoundTag t, long now) {
        ListTag orders = list(t, "orders");
        for (int i = orders.size() - 1; i >= 0; i--) {
            if (now > orders.getCompound(i).getLong("exp")) {
                orders.remove(i);
                d.addRep(p.getUUID(), -2);
                msg(p, "§7[Заказ] Срок заказа истёк — заказчик недоволен. §8(Репутация -2)");
            }
        }
        if (BMData.due(t, "nextOrder", now, 4800, 6000) && orders.size() < 3) generate(d, p, orders, now);
    }

    static void generate(BMData d, ServerPlayer p, ListTag orders, long now) {
        int rep = d.rep(p.getUUID());
        String itemId; int count, unit;
        if (BMData.RNG.nextInt(100) < 55) {
            Goods.Good[] pool = Goods.ALL.values().stream().filter(g -> g.organ() || g.buyRep() <= rep).toArray(Goods.Good[]::new);
            Goods.Good g = pool[BMData.RNG.nextInt(pool.length)];
            itemId = BlackMarketMod.MODID + ":" + g.id();
            count = g.organ() ? 1 + BMData.RNG.nextInt(2) : (g.price() < 200 ? 1 + BMData.RNG.nextInt(4) : 1);
            unit = g.price();
        } else {
            Object[] v = VANILLA[BMData.RNG.nextInt(VANILLA.length)];
            itemId = (String) v[0]; unit = (Integer) v[1];
            int min = (Integer) v[2], max = (Integer) v[3];
            count = min + BMData.RNG.nextInt(max - min + 1);
        }
        int reward = count * unit * (130 + BMData.RNG.nextInt(41)) / 100;
        CompoundTag o = new CompoundTag();
        o.putInt("id", d.nextSeq("orderSeq"));
        o.putString("item", itemId); o.putInt("count", count); o.putInt("reward", reward);
        o.putInt("rep", Math.max(1, reward / 150)); o.putLong("exp", now + 18000);
        orders.add(o);
        Item it = ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemId));
        p.sendSystemMessage(line("§8[Аноним] §7Есть работа: §f" + count + "× ", it, " §7за §e" + reward + " кр.§7 (15 мин). §8/orders"));
    }

    public static int orders(ServerPlayer p) {
        BMData d = BMData.get(p.getServer());
        long now = p.getServer().overworld().getGameTime();
        ListTag l = list(d.p(p.getUUID()), "orders");
        if (l.isEmpty()) { msg(p, "§7Активных заказов нет. Предложения приходят сами."); return 1; }
        msg(p, "§6§lЗаказы §7(сдать: /orders deliver <№>)");
        for (int i = 0; i < l.size(); i++) {
            CompoundTag o = l.getCompound(i);
            Item it = ForgeRegistries.ITEMS.getValue(new ResourceLocation(o.getString("item")));
            p.sendSystemMessage(line("§e#" + o.getInt("id") + " §f" + o.getInt("count") + "× ", it,
                " §7→ §e" + o.getInt("reward") + " кр.§7, реп +" + o.getInt("rep") + ", осталось " + Math.max(0, (o.getLong("exp") - now) / 20) + " сек"));
        }
        return 1;
    }

    public static int deliver(ServerPlayer p, int id) {
        BMData d = BMData.get(p.getServer());
        CompoundTag t = d.p(p.getUUID());
        ListTag l = list(t, "orders");
        for (int i = 0; i < l.size(); i++) {
            CompoundTag o = l.getCompound(i);
            if (o.getInt("id") != id) continue;
            Item it = ForgeRegistries.ITEMS.getValue(new ResourceLocation(o.getString("item")));
            int need = o.getInt("count"), have = 0;
            for (ItemStack st : p.getInventory().items) if (st.is(it)) have += st.getCount();
            if (have < need) { p.sendSystemMessage(line("§cНе хватает: нужно ", it, "×" + need + ", у тебя " + have)); return 0; }
            int left = need;
            for (ItemStack st : p.getInventory().items) if (left > 0 && st.is(it)) { int n = Math.min(left, st.getCount()); st.shrink(n); left -= n; }
            p.getInventory().setChanged();
            d.addCredits(p.getUUID(), o.getInt("reward"));
            d.addRep(p.getUUID(), o.getInt("rep"));
            if (o.getString("item").startsWith(BlackMarketMod.MODID)) d.addHeat(p.getUUID(), 4);
            l.remove(i);
            msg(p, "§a[Заказ] Сделка закрыта: §e+" + o.getInt("reward") + " кр.§a, репутация +" + o.getInt("rep"));
            return 1;
        }
        msg(p, "§cЗаказа с таким номером нет.");
        return 0;
    }

    // ================= АУКЦИОНЫ =================
    static ListTag auctions(BMData d) { if (!d.root.contains("auctions", 9)) d.root.put("auctions", new ListTag()); d.setDirty(); return d.root.getList("auctions", 10); }

    public static int sell(ServerPlayer p, int price, int minutes) {
        BMData d = BMData.get(p.getServer());
        ItemStack held = p.getMainHandItem();
        if (held.isEmpty()) { msg(p, "§cВозьми предмет в основную руку."); return 0; }
        ListTag a = auctions(d);
        int mine = 0;
        for (int i = 0; i < a.size(); i++) if (a.getCompound(i).getUUID("seller").equals(p.getUUID())) mine++;
        if (mine >= 3) { msg(p, "§cНе больше 3 активных лотов."); return 0; }
        CompoundTag lot = new CompoundTag();
        int id = d.nextSeq("auctionSeq");
        lot.putInt("id", id);
        lot.putUUID("seller", p.getUUID());
        lot.putString("sname", p.getName().getString());
        lot.put("item", held.save(new CompoundTag()));
        lot.putInt("start", price); lot.putInt("bid", 0);
        lot.putLong("end", p.getServer().overworld().getGameTime() + minutes * 1200L);
        a.add(lot);
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.getServer().getPlayerList().broadcastSystemMessage(
            Component.literal("§6[Аукцион] §f" + p.getName().getString() + " выставил лот §e#" + id + "§f: ").append(held.getHoverName())
                .append(Component.literal(" ×" + held.getCount() + " §7от §e" + price + " кр. §7(" + minutes + " мин). §8/auction bid " + id + " <сумма>")), false);
        return 1;
    }

    public static int list(ServerPlayer p) {
        BMData d = BMData.get(p.getServer());
        long now = p.getServer().overworld().getGameTime();
        ListTag a = auctions(d);
        if (a.isEmpty()) { msg(p, "§7Лотов нет. Выставить: §e/auction sell <цена> [минут]"); return 1; }
        msg(p, "§6§lАукцион");
        for (int i = 0; i < a.size(); i++) {
            CompoundTag l = a.getCompound(i);
            ItemStack st = ItemStack.of(l.getCompound("item"));
            int bid = l.getInt("bid");
            p.sendSystemMessage(Component.literal("§e#" + l.getInt("id") + " §f").append(st.getHoverName()).append(Component.literal(
                " ×" + st.getCount() + " §7| " + (bid > 0 ? "ставка §e" + bid + " §7(" + l.getString("bname") + ")" : "старт §e" + l.getInt("start"))
                + " §7| " + Math.max(0, (l.getLong("end") - now) / 20) + " сек | " + l.getString("sname"))));
        }
        return 1;
    }

    public static int bid(ServerPlayer p, int id, int amount) {
        MinecraftServer s = p.getServer();
        BMData d = BMData.get(s);
        ListTag a = auctions(d);
        for (int i = 0; i < a.size(); i++) {
            CompoundTag l = a.getCompound(i);
            if (l.getInt("id") != id) continue;
            if (l.getUUID("seller").equals(p.getUUID())) { msg(p, "§cНельзя ставить на свой лот."); return 0; }
            int cur = l.getInt("bid");
            if (amount < l.getInt("start") || amount <= cur) { msg(p, "§cСтавка должна быть выше текущей (" + Math.max(cur, l.getInt("start") - 1) + ")."); return 0; }
            if (l.contains("bidder") && l.getUUID("bidder").equals(p.getUUID())) { msg(p, "§cТвоя ставка уже лидирует."); return 0; }
            if (d.credits(p.getUUID()) < amount) { msg(p, "§cНедостаточно кредитов."); return 0; }
            if (l.contains("bidder")) {
                UUID old = l.getUUID("bidder");
                d.addCredits(old, cur);
                ServerPlayer op = s.getPlayerList().getPlayer(old);
                if (op != null) msg(op, "§e[Аукцион] §fТвою ставку на лот #" + id + " перебили. Кредиты возвращены (§e" + cur + "§f).");
            }
            d.addCredits(p.getUUID(), -amount);
            l.putInt("bid", amount); l.putUUID("bidder", p.getUUID()); l.putString("bname", p.getName().getString());
            msg(p, "§a[Аукцион] Ставка §e" + amount + " §aпринята.");
            return 1;
        }
        msg(p, "§cЛота с таким номером нет.");
        return 0;
    }

    public static int cancel(ServerPlayer p, int id) {
        MinecraftServer s = p.getServer();
        BMData d = BMData.get(s);
        ListTag a = auctions(d);
        for (int i = 0; i < a.size(); i++) {
            CompoundTag l = a.getCompound(i);
            if (l.getInt("id") != id) continue;
            if (!l.getUUID("seller").equals(p.getUUID())) { msg(p, "§cЭто не твой лот."); return 0; }
            if (l.getInt("bid") > 0) { msg(p, "§cНа лот уже есть ставки."); return 0; }
            giveOrPend(s, d, p.getUUID(), ItemStack.of(l.getCompound("item")));
            a.remove(i);
            msg(p, "§7Лот снят.");
            return 1;
        }
        msg(p, "§cЛота с таким номером нет.");
        return 0;
    }

    public static void settle(MinecraftServer s, long now) {
        BMData d = BMData.get(s);
        ListTag a = auctions(d);
        for (int i = a.size() - 1; i >= 0; i--) {
            CompoundTag l = a.getCompound(i);
            if (now < l.getLong("end")) continue;
            a.remove(i);
            UUID seller = l.getUUID("seller");
            ItemStack st = ItemStack.of(l.getCompound("item"));
            int bid = l.getInt("bid");
            if (l.contains("bidder") && bid > 0) {
                UUID buyer = l.getUUID("bidder");
                int net = bid * 95 / 100;
                d.addCredits(seller, net);
                d.addRep(seller, Math.max(1, bid / 200)); d.addRep(buyer, 1);
                giveOrPend(s, d, buyer, st);
                tell(s, seller, "§a[Аукцион] Лот #" + l.getInt("id") + " продан за §e" + bid + "§a. Получено §e" + net + " §a(комиссия 5%).");
                tell(s, buyer, "§a[Аукцион] Ты выиграл лот #" + l.getInt("id") + "! Предмет выдан.");
            } else {
                giveOrPend(s, d, seller, st);
                tell(s, seller, "§7[Аукцион] Лот #" + l.getInt("id") + " не продан — предмет возвращён.");
            }
        }
    }

    static void tell(MinecraftServer s, UUID id, String m) { ServerPlayer p = s.getPlayerList().getPlayer(id); if (p != null) msg(p, m); }

    static void giveOrPend(MinecraftServer s, BMData d, UUID id, ItemStack st) {
        ServerPlayer p = s.getPlayerList().getPlayer(id);
        if (p != null) p.getInventory().placeItemBackInInventory(st);
        else list(d.p(id), "pending").add(st.save(new CompoundTag()));
    }

    /** Выдать предметы, накопленные пока игрок был офлайн. */
    public static void claim(ServerPlayer p) {
        BMData d = BMData.get(p.getServer());
        ListTag l = list(d.p(p.getUUID()), "pending");
        if (l.isEmpty()) return;
        for (int i = 0; i < l.size(); i++) p.getInventory().placeItemBackInInventory(ItemStack.of(l.getCompound(i)));
        l.clear();
        msg(p, "§6[Аукцион] Тебе выданы предметы, пока тебя не было.");
    }
}
