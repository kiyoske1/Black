package com.kiyoske.blackmarket;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

public final class Cmds {
    private Cmds() {}

    static void msg(ServerPlayer p, String s) { p.sendSystemMessage(Component.literal(s)); }
    static BMData data(ServerPlayer p) { return BMData.get(p.getServer()); }
    static long now(ServerPlayer p) { return p.getServer().overworld().getGameTime(); }

    public static void register(CommandDispatcher<CommandSourceStack> dp) {
        // --- кредиты ---
        dp.register(Commands.literal("credits")
            .executes(c -> { ServerPlayer p = c.getSource().getPlayerOrException(); msg(p, "§6[BlackMarket] §fБаланс: §e" + data(p).credits(p.getUUID()) + " кредитов"); return 1; })
            .then(Commands.literal("pay").then(Commands.argument("player", EntityArgument.player()).then(Commands.argument("amount", IntegerArgumentType.integer(1)).executes(c -> {
                ServerPlayer from = c.getSource().getPlayerOrException();
                ServerPlayer to = EntityArgument.getPlayer(c, "player");
                int amount = IntegerArgumentType.getInteger(c, "amount");
                BMData d = data(from);
                if (from == to || d.credits(from.getUUID()) < amount) { msg(from, "§cНедостаточно кредитов или неверный получатель."); return 0; }
                d.addCredits(from.getUUID(), -amount); d.addCredits(to.getUUID(), amount);
                msg(from, "§aПереведено " + amount + " кредитов игроку " + to.getName().getString());
                msg(to, "§aПолучено " + amount + " кредитов от " + from.getName().getString());
                return 1;
            })))));

        // --- рынок: прайс, покупка редких товаров ---
        dp.register(Commands.literal("blackmarket")
            .executes(c -> { market(c.getSource().getPlayerOrException()); return 1; })
            .then(Commands.literal("buy").then(Commands.argument("good", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(Goods.ALL.keySet(), b))
                .executes(c -> buy(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "good"), 1))
                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                    .executes(c -> buy(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "good"), IntegerArgumentType.getInteger(c, "amount")))))));

        // --- продажа ---
        dp.register(Commands.literal("sellpart")
            .executes(c -> sell(c.getSource().getPlayerOrException(), false))
            .then(Commands.literal("all").executes(c -> sell(c.getSource().getPlayerOrException(), true))));

        // --- репутация ---
        dp.register(Commands.literal("rep").executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            BMData d = data(p); CompoundTag t = d.p(p.getUUID());
            int rep = t.getInt("rep");
            msg(p, "§8§m------------------------------");
            msg(p, "§6Репутация: §e" + rep + " §7(" + BMData.rank(rep) + ")");
            msg(p, "§6Розыск: §c" + t.getInt("heat") + "§7/100");
            msg(p, "§6Долг: §e" + t.getInt("debt") + " §7| Лимит займа: §e" + (500 + Math.max(0, rep) * 5));
            msg(p, "§8§m------------------------------");
            return 1;
        }));

        // --- займы ---
        dp.register(Commands.literal("loan")
            .executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                CompoundTag t = data(p).p(p.getUUID());
                if (t.getInt("debt") <= 0) msg(p, "§7Долгов нет. Взять: §e/loan take <сумма> §7(возврат +25%, срок 10 мин). Лимит: §e" + (500 + Math.max(0, t.getInt("rep")) * 5));
                else msg(p, "§6Долг: §e" + t.getInt("debt") + " §7| осталось: " + Math.max(0, (t.getLong("due") - now(p)) / 20) + " сек. §e/loan pay <сумма>");
                return 1;
            })
            .then(Commands.literal("take").then(Commands.argument("amount", IntegerArgumentType.integer(10)).executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                BMData d = data(p); CompoundTag t = d.p(p.getUUID());
                int amount = IntegerArgumentType.getInteger(c, "amount");
                int limit = 500 + Math.max(0, t.getInt("rep")) * 5;
                if (t.getInt("debt") > 0 && now(p) >= t.getLong("due")) { msg(p, "§cСначала закрой просроченный долг."); return 0; }
                if (t.getInt("debt") + amount * 125 / 100 > limit) { msg(p, "§cПревышен лимит займа (" + limit + ")."); return 0; }
                d.addCredits(p.getUUID(), amount);
                t.putInt("debt", t.getInt("debt") + amount * 125 / 100);
                t.putLong("due", now(p) + 12000);
                msg(p, "§6[Займ] §fПолучено §e" + amount + "§f. Вернуть: §e" + t.getInt("debt") + " §fза 10 минут. Не вернёшь — придут коллекторы.");
                return 1;
            })))
            .then(Commands.literal("pay").then(Commands.argument("amount", IntegerArgumentType.integer(1)).executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                BMData d = data(p); CompoundTag t = d.p(p.getUUID());
                int pay = Math.min(IntegerArgumentType.getInteger(c, "amount"), t.getInt("debt"));
                if (pay <= 0) { msg(p, "§7Долгов нет."); return 0; }
                if (d.credits(p.getUUID()) < pay) { msg(p, "§cНедостаточно кредитов."); return 0; }
                d.addCredits(p.getUUID(), -pay);
                t.putInt("debt", t.getInt("debt") - pay);
                if (t.getInt("debt") == 0) { t.putInt("dlevel", 0); d.addRep(p.getUUID(), 1); Npcs.dismiss(p.getServer(), p.getUUID()); msg(p, "§aДолг полностью погашен."); }
                else msg(p, "§aВыплачено §e" + pay + "§a. Остаток: §e" + t.getInt("debt"));
                return 1;
            }))));

        // --- NPC ---
        dp.register(Commands.literal("collector")
            .then(Commands.literal("pay").executes(c -> Npcs.collector(c.getSource().getPlayerOrException(), "pay")))
            .then(Commands.literal("talk").executes(c -> Npcs.collector(c.getSource().getPlayerOrException(), "talk")))
            .then(Commands.literal("refuse").executes(c -> Npcs.collector(c.getSource().getPlayerOrException(), "refuse"))));
        dp.register(Commands.literal("police")
            .then(Commands.literal("comply").executes(c -> Npcs.police(c.getSource().getPlayerOrException(), "comply")))
            .then(Commands.literal("bribe").executes(c -> Npcs.police(c.getSource().getPlayerOrException(), "bribe")))
            .then(Commands.literal("resist").executes(c -> Npcs.police(c.getSource().getPlayerOrException(), "resist"))));

        // --- заказы ---
        dp.register(Commands.literal("orders")
            .executes(c -> Deals.orders(c.getSource().getPlayerOrException()))
            .then(Commands.literal("deliver").then(Commands.argument("id", IntegerArgumentType.integer(1))
                .executes(c -> Deals.deliver(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "id"))))));

        // --- аукцион ---
        dp.register(Commands.literal("auction")
            .executes(c -> Deals.list(c.getSource().getPlayerOrException()))
            .then(Commands.literal("list").executes(c -> Deals.list(c.getSource().getPlayerOrException())))
            .then(Commands.literal("sell").then(Commands.argument("price", IntegerArgumentType.integer(1, 10000000))
                .executes(c -> Deals.sell(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "price"), 5))
                .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 60))
                    .executes(c -> Deals.sell(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "price"), IntegerArgumentType.getInteger(c, "minutes"))))))
            .then(Commands.literal("bid").then(Commands.argument("id", IntegerArgumentType.integer(1)).then(Commands.argument("amount", IntegerArgumentType.integer(1))
                .executes(c -> Deals.bid(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "id"), IntegerArgumentType.getInteger(c, "amount"))))))
            .then(Commands.literal("cancel").then(Commands.argument("id", IntegerArgumentType.integer(1))
                .executes(c -> Deals.cancel(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "id")))))
            .then(Commands.literal("claim").executes(c -> { Deals.claim(c.getSource().getPlayerOrException()); return 1; })));

        // --- казино ---
        dp.register(Commands.literal("casino").then(Commands.argument("bet", IntegerArgumentType.integer(1, 1000000)).executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            BMData d = data(p); UUID id = p.getUUID();
            int bet = IntegerArgumentType.getInteger(c, "bet");
            if (d.credits(id) < bet) { msg(p, "§cНедостаточно кредитов для ставки."); return 0; }
            d.addCredits(id, -bet);
            int roll = BMData.RNG.nextInt(100);
            if (roll < 10) { d.addCredits(id, bet * 5); msg(p, "§d✦ ДЖЕКПОТ! §fВыпало 5x. Выигрыш: §e+" + bet * 5 + " кредитов"); }
            else if (roll < 45) { d.addCredits(id, bet * 2); msg(p, "§aУдача! §fВыпало 2x. Выигрыш: §e+" + bet * 2 + " кредитов"); }
            else msg(p, "§cНе повезло. §7Ставка сгорела: " + bet + " кредитов.");
            msg(p, "§7Баланс: §e" + d.credits(id) + " кредитов");
            return 1;
        })));
    }

    static void market(ServerPlayer p) {
        BMData d = data(p);
        CompoundTag m = Goods.market(d, now(p));
        int rep = d.rep(p.getUUID());
        msg(p, "§8§m--------------------------------");
        msg(p, "§6§lЧЁРНЫЙ РЫНОК §7| §f" + BMData.rank(rep) + " §7(реп " + rep + ")");
        for (Goods.Good g : Goods.ALL.values()) {
            int mult = Goods.mult(m, g);
            String trend = mult >= 120 ? " §a▲" : mult <= 80 ? " §c▼" : "";
            String buy = g.buyRep() < 0 ? "" : (rep >= g.buyRep() ? " §7| купить §e" + Goods.buyPrice(m, g) + " §7(склад " + Goods.stock(m, g) + ")" : " §7| §8нужна реп. " + g.buyRep());
            p.sendSystemMessage(Component.literal("§f").append(Goods.name(g.item().get())).append(Component.literal(" §7- продать §e" + Goods.sellPrice(m, g, rep) + trend + buy)));
        }
        msg(p, "§7Продать: §e/sellpart [all]§7 | Купить: §e/blackmarket buy <товар> [кол-во]");
        msg(p, "§7Ещё: §e/rep /loan /orders /auction /casino");
        msg(p, "§8§m--------------------------------");
    }

    static int buy(ServerPlayer p, String id, int amount) {
        Goods.Good g = Goods.ALL.get(id);
        if (g == null || g.buyRep() < 0) { msg(p, "§cТакого товара нет в продаже."); return 0; }
        BMData d = data(p);
        CompoundTag m = Goods.market(d, now(p));
        if (d.rep(p.getUUID()) < g.buyRep()) { msg(p, "§cТебе не доверяют: нужна репутация " + g.buyRep() + "."); return 0; }
        if (Goods.stock(m, g) < amount) { msg(p, "§cНа складе только " + Goods.stock(m, g) + ". Ассортимент обновляется каждые 10 минут."); return 0; }
        int cost = Goods.buyPrice(m, g) * amount;
        if (d.credits(p.getUUID()) < cost) { msg(p, "§cНужно §e" + cost + " §cкредитов."); return 0; }
        d.addCredits(p.getUUID(), -cost);
        m.putInt("s_" + g.id(), Goods.stock(m, g) - amount);
        d.addHeat(p.getUUID(), 2 * amount);
        for (int i = 0; i < amount; i++) p.getInventory().placeItemBackInInventory(new ItemStack(g.item().get()));
        msg(p, "§aКуплено: §f" + amount + "× " + g.id() + " §7| §e-" + cost + " кр. §8(Розыск +" + 2 * amount + ")");
        return 1;
    }

    static int sell(ServerPlayer p, boolean all) {
        ItemStack held = p.getMainHandItem();
        Goods.Good g = Goods.of(held);
        if (g == null) { msg(p, "§cВозьми в руку товар чёрного рынка (/blackmarket)."); return 0; }
        BMData d = data(p);
        CompoundTag m = Goods.market(d, now(p));
        int n = all ? held.getCount() : 1;
        int total = Goods.sellPrice(m, g, d.rep(p.getUUID())) * n;
        held.shrink(n);
        d.addCredits(p.getUUID(), total);
        d.addRep(p.getUUID(), Math.max(1, total / 400));
        d.addHeat(p.getUUID(), (g.organ() ? 8 : 5) * n);
        msg(p, "§aПродано: §f" + n + "× " + g.id() + " §7| §e+" + total + " кредитов §8(Розыск растёт)");
        return 1;
    }
}
