package com.kiyoske.blackmarket;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

/** NPC: коллекторы, полиция, банды. Они физически приходят к игроку. */
public final class Npcs {
    private Npcs() {}

    static final Random RNG = BMData.RNG;
    public static final Set<UUID> LIVE = new HashSet<>();
    public static final Map<UUID, Enc> ENC = new HashMap<>();

    public static class Enc {
        String kind; // collector | police | gang
        List<UUID> mobs = new ArrayList<>();
        long arrived = -1, hostileAt;
        boolean hostile, talked;
    }

    static void msg(ServerPlayer p, String s) { p.sendSystemMessage(Component.literal(s)); }

    // ---------- спавн ----------
    static Mob spawnOne(ServerPlayer p, String kind) {
        ServerLevel lv = p.serverLevel();
        Mob m;
        if (kind.equals("police")) m = EntityType.PILLAGER.create(lv); else m = EntityType.VINDICATOR.create(lv);
        if (m == null) return null;
        boolean open = !lv.dimensionType().hasCeiling() && lv.canSeeSky(p.blockPosition());
        boolean placed = false;
        for (int i = 0; i < 15 && !placed; i++) {
            double dist = (kind.equals("gang") ? 14 : 22) + RNG.nextInt(8) - i;
            double ang = RNG.nextDouble() * Math.PI * 2;
            int x = (int) Math.floor(p.getX() + Math.cos(ang) * dist);
            int z = (int) Math.floor(p.getZ() + Math.sin(ang) * dist);
            int y = open ? lv.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) : p.getBlockY();
            m.moveTo(x + 0.5, y, z + 0.5, RNG.nextFloat() * 360f, 0f);
            placed = lv.noCollision(m);
        }
        if (!placed) m.moveTo(p.getX() + 2, p.getY(), p.getZ() + 2, 0f, 0f);
        m.finalizeSpawn(lv, lv.getCurrentDifficultyAt(m.blockPosition()), MobSpawnType.EVENT, null, null);
        switch (kind) {
            case "police" -> { m.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET)); m.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE)); m.setCustomName(Component.literal("§9Полицейский")); }
            case "collector" -> { m.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE)); m.setCustomName(Component.literal("§cКоллектор")); }
            default -> { m.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET)); m.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE)); m.setCustomName(Component.literal("§4Бандит")); }
        }
        for (EquipmentSlot sl : EquipmentSlot.values()) m.setDropChance(sl, 0f);
        m.setCustomNameVisible(true);
        m.setPersistenceRequired();
        m.getPersistentData().putBoolean("bm_npc", true);
        m.getPersistentData().putString("bm_kind", kind);
        LIVE.add(m.getUUID());
        lv.addFreshEntity(m);
        return m;
    }

    static Enc start(ServerPlayer p, String kind, int count, long now) {
        Enc e = new Enc();
        e.kind = kind;
        for (int i = 0; i < count; i++) { Mob m = spawnOne(p, kind); if (m != null) e.mobs.add(m.getUUID()); }
        if (e.mobs.isEmpty()) return null;
        if (kind.equals("gang")) { e.hostile = true; e.hostileAt = now; }
        ENC.put(p.getUUID(), e);
        return e;
    }

    static Mob find(MinecraftServer s, UUID id) {
        for (ServerLevel l : s.getAllLevels()) { Entity e = l.getEntity(id); if (e instanceof Mob m && m.isAlive()) return m; }
        return null;
    }

    static void dismiss(MinecraftServer s, UUID player) {
        Enc e = ENC.remove(player);
        if (e == null) return;
        for (UUID id : e.mobs) { Mob m = find(s, id); if (m != null) m.discard(); LIVE.remove(id); }
    }

    public static void dismissAll(MinecraftServer s) { for (UUID u : new ArrayList<>(ENC.keySet())) dismiss(s, u); }

    static void hostile(MinecraftServer s, ServerPlayer p, Enc e, long now, String why) {
        if (e.hostile) return;
        e.hostile = true; e.hostileAt = now;
        msg(p, why);
    }

    // ---------- тик NPC (каждые 10 тиков) ----------
    public static void tickNpcs(MinecraftServer s, long now) {
        for (UUID pid : new ArrayList<>(ENC.keySet())) {
            Enc e = ENC.get(pid);
            ServerPlayer p = s.getPlayerList().getPlayer(pid);
            if (p == null || !p.isAlive()) { dismiss(s, pid); continue; }
            List<Mob> alive = new ArrayList<>();
            for (int i = 0; i < e.mobs.size(); i++) {
                Mob m = find(s, e.mobs.get(i));
                if (m == null) continue;
                if (m.level() != p.level() || m.distanceTo(p) > 70) {
                    LIVE.remove(m.getUUID()); m.discard();
                    m = spawnOne(p, e.kind);
                    if (m == null) continue;
                    e.mobs.set(i, m.getUUID());
                }
                alive.add(m);
            }
            if (alive.isEmpty()) { ENC.remove(pid); continue; }
            if (!e.hostile) {
                for (Mob m : alive) {
                    m.setTarget(null);
                    if (m.distanceTo(p) > 3.5f) { m.setNoAi(false); m.getNavigation().moveTo(p, 1.15); }
                    else {
                        if (!m.isNoAi()) { m.getNavigation().stop(); m.setNoAi(true); }
                        m.lookAt(p, 30f, 30f);
                        if (e.arrived < 0) { e.arrived = now; prompt(p, e); }
                    }
                }
                if (e.arrived >= 0 && now - e.arrived > 1200) hostile(s, p, e, now, "§cВремя вышло — они переходят к силовым методам!");
            } else {
                for (Mob m : alive) { m.setNoAi(false); if (m.getTarget() != p) m.setTarget(p); }
                if (now - e.hostileAt > 3600) { dismiss(s, pid); msg(p, "§7Они потеряли к тебе интерес и ушли. §8(Проблема не решена.)"); }
            }
        }
    }

    static void prompt(ServerPlayer p, Enc e) {
        if (e.kind.equals("collector")) msg(p, "§c[Коллектор] §fДолг пора возвращать. §e/collector pay §7— заплатить, §e/collector talk §7— договориться, §e/collector refuse §7— отказаться. §8(60 сек)");
        else msg(p, "§9[Полиция] §fОстановитесь для досмотра! §e/police comply §7— подчиниться, §e/police bribe §7— взятка, §e/police resist §7— сопротивляться. §8(60 сек)");
    }

    // ---------- тик игроков (каждые 20 тиков) ----------
    public static void tickPlayers(MinecraftServer s, long now) {
        BMData d = BMData.get(s);
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (p.isSpectator() || !p.isAlive()) continue;
            UUID id = p.getUUID();
            CompoundTag t = d.p(id);
            boolean free = !ENC.containsKey(id);

            if (now >= t.getLong("heatAt")) { t.putLong("heatAt", now + 600); d.addHeat(id, -1); }
            if (BMData.due(t, "smuggleAt", now, 1200, 0)) {
                Set<String> held = new HashSet<>();
                for (ItemStack st : p.getInventory().items) { Goods.Good g = Goods.of(st); if (g != null) held.add(g.id()); }
                if (!held.isEmpty()) d.addHeat(id, held.size() * 2);
            }
            int debt = t.getInt("debt");
            long due = t.getLong("due");
            if (debt > 0) {
                if (now < due && due - now < 2400 && BMData.due(t, "remindAt", now, 1200, 0))
                    msg(p, "§6[Долг] §fДо срока возврата §e" + (due - now) / 20 + " сек§f. Долг: §e" + debt + " §f(/loan pay)");
                if (now >= due && free && now >= t.getLong("nextCollect")) {
                    int lvl = t.getInt("dlevel");
                    t.putInt("dlevel", lvl + 1);
                    t.putInt("debt", debt + debt / 10);
                    t.putLong("nextCollect", now + 6000);
                    if (start(p, "collector", Math.min(3, 1 + lvl), now) != null)
                        msg(p, "§c[Коллекторы] §fК тебе идут люди за долгом §e" + t.getInt("debt") + " §f(включая штраф 10%).");
                    free = false;
                }
            }
            if (free && BMData.due(t, "policeCheck", now, 400, 0) && t.getInt("heat") >= 25 && now >= t.getLong("nextPolice")
                    && RNG.nextInt(100) < t.getInt("heat") - 15) {
                if (start(p, "police", 1 + t.getInt("heat") / 40, now) != null) { msg(p, "§9[Полиция] §7Кто-то настучал... к тебе направляются патрульные!"); free = false; }
            }
            if (BMData.due(t, "nextGang", now, 4800, 6000) && free && (t.getInt("rep") >= 10 || t.getInt("credits") >= 500 || hasGoods(p))) {
                int n = 2 + Math.max(0, t.getInt("rep")) / 300;
                if (start(p, "gang", Math.min(4, n), now) != null) msg(p, "§4[Банда] §cКонкуренты выследили тебя и нападают! Отбивайся — за них платят.");
            }
            Deals.tickPlayer(s, d, p, t, now);
        }
    }

    static boolean hasGoods(ServerPlayer p) { for (ItemStack st : p.getInventory().items) if (Goods.of(st) != null) return true; return false; }

    // ---------- действия игрока: коллектор ----------
    static Enc cur(ServerPlayer p, String kind) {
        Enc e = ENC.get(p.getUUID());
        if (e == null || !e.kind.equals(kind) || e.hostile) { msg(p, "§7Сейчас тебя никто не ждёт для разговора."); return null; }
        return e;
    }

    public static int collector(ServerPlayer p, String action) {
        Enc e = cur(p, "collector");
        if (e == null) return 0;
        MinecraftServer s = p.getServer();
        BMData d = BMData.get(s);
        CompoundTag t = d.p(p.getUUID());
        long now = s.overworld().getGameTime();
        int debt = t.getInt("debt");
        switch (action) {
            case "pay" -> {
                if (d.credits(p.getUUID()) < debt) { msg(p, "§cНе хватает кредитов. Нужно: §e" + debt + "§c, у тебя: §e" + d.credits(p.getUUID())); return 0; }
                d.addCredits(p.getUUID(), -debt);
                t.putInt("debt", 0); t.putInt("dlevel", 0);
                d.addRep(p.getUUID(), 1);
                dismiss(s, p.getUUID());
                msg(p, "§a[Коллектор] §fРасчёт получен. Приятно иметь дело.");
            }
            case "talk" -> {
                if (e.talked) { msg(p, "§cВторой раз они разговаривать не станут."); return 0; }
                e.talked = true;
                int chance = Math.min(80, 35 + Math.max(0, d.rep(p.getUUID())) / 10);
                if (RNG.nextInt(100) < chance) {
                    t.putLong("due", now + 6000);
                    t.putInt("debt", debt * 115 / 100);
                    dismiss(s, p.getUUID());
                    msg(p, "§a[Коллектор] §fЛадно, ещё 5 минут. Но долг теперь §e" + t.getInt("debt") + " §f(+15%).");
                } else hostile(s, p, e, now, "§c[Коллектор] §fРазговор окончен. Берите его!");
            }
            default -> { d.addRep(p.getUUID(), -5); hostile(s, p, e, now, "§c[Коллектор] §fТы сделал свой выбор. §7(Репутация -5)"); }
        }
        return 1;
    }

    // ---------- действия игрока: полиция ----------
    public static int police(ServerPlayer p, String action) {
        Enc e = cur(p, "police");
        if (e == null) return 0;
        MinecraftServer s = p.getServer();
        BMData d = BMData.get(s);
        UUID id = p.getUUID();
        long now = s.overworld().getGameTime();
        switch (action) {
            case "comply" -> search(p, s, d, now);
            case "bribe" -> {
                int cost = 150 + d.heat(id) * 8;
                if (d.credits(id) < cost) { msg(p, "§cВзятка стоит §e" + cost + "§c, у тебя не хватает."); return 0; }
                d.addCredits(id, -cost);
                if (RNG.nextInt(100) < 75) {
                    d.addHeat(id, -15); d.addRep(id, -1);
                    d.p(id).putLong("nextPolice", now + 4800);
                    dismiss(s, id);
                    msg(p, "§a[Полиция] §fМы вас не видели. §7(-" + cost + " кредитов)");
                } else { msg(p, "§c[Полиция] §fДеньги заберём, а вас обыщем."); search(p, s, d, now); }
            }
            default -> {
                d.addRep(id, -3); d.addHeat(id, 20);
                hostile(s, p, e, now, "§9[Полиция] §fОн сопротивляется! Огонь на поражение!");
            }
        }
        return 1;
    }

    static void search(ServerPlayer p, MinecraftServer s, BMData d, long now) {
        UUID id = p.getUUID();
        d.p(id).putLong("nextPolice", now + 7200);
        for (ItemStack st : p.getInventory().items) {
            if (st.is(Goods.ALL.get("fake_passport").item().get())) {
                st.shrink(1);
                d.addHeat(id, -10);
                dismiss(s, id);
                msg(p, "§a[Полиция] §fДокументы в порядке, проходите. §7(Фальшивый паспорт израсходован)");
                return;
            }
        }
        int items = 0, value = 0;
        List<ItemStack> all = new ArrayList<>();
        all.addAll(p.getInventory().items); all.addAll(p.getInventory().offhand); all.addAll(p.getInventory().armor);
        for (ItemStack st : all) {
            Goods.Good g = Goods.of(st);
            if (g != null) { items += st.getCount(); value += st.getCount() * g.price(); st.shrink(st.getCount()); }
        }
        p.getInventory().setChanged();
        p.containerMenu.broadcastChanges();
        if (items == 0) {
            d.addHeat(id, -20);
            msg(p, "§a[Полиция] §fЧисто. Извините за беспокойство.");
        } else {
            int fine = Math.min(d.credits(id), 50 + value / 5 + d.heat(id) * 3);
            d.addCredits(id, -fine);
            d.addRep(id, -5);
            d.p(id).putInt("heat", 15);
            msg(p, "§c[Полиция] §fКонфисковано нелегальных предметов: §e" + items + " §f(на ~" + value + " кр.). Штраф: §e" + fine + " §fкр. §7(Репутация -5)");
        }
        dismiss(s, id);
    }

    // ---------- события ----------
    public static void onHurt(ServerPlayer attacker, Entity victim, long now, MinecraftServer s) {
        if (!victim.getPersistentData().getBoolean("bm_npc")) return;
        Enc e = ENC.get(attacker.getUUID());
        if (e != null && !e.hostile) {
            if (e.kind.equals("police")) { BMData d = BMData.get(s); d.addHeat(attacker.getUUID(), 20); d.addRep(attacker.getUUID(), -3); }
            hostile(s, attacker, e, now, "§cТы напал первым — они отвечают!");
        }
    }

    public static void onDeath(ServerPlayer killer, Entity victim, MinecraftServer s) {
        CompoundTag vt = victim.getPersistentData();
        if (!vt.getBoolean("bm_npc")) return;
        LIVE.remove(victim.getUUID());
        if (killer == null) return;
        BMData d = BMData.get(s);
        UUID id = killer.getUUID();
        switch (vt.getString("bm_kind")) {
            case "gang" -> {
                int reward = 40 + RNG.nextInt(80);
                d.addCredits(id, reward); d.addRep(id, 4);
                msg(killer, "§a[Банда] §fОтбился! Трофеи: §e+" + reward + " кр.§f, репутация +4");
                if (RNG.nextInt(100) < 15) { killer.getInventory().placeItemBackInInventory(new ItemStack(Goods.ALL.get("contraband_crate").item().get())); msg(killer, "§7У бандита нашёлся ящик контрабанды."); }
                if (RNG.nextInt(100) < 3) { killer.getInventory().placeItemBackInInventory(new ItemStack(Goods.ALL.get("black_diamond").item().get())); msg(killer, "§d✦ Из кармана бандита выпал Чёрный алмаз!"); }
            }
            case "collector" -> { d.addRep(id, 2); d.addHeat(id, 10); msg(killer, "§7Коллектор убит, но долг остаётся. §8(Розыск +10)"); }
            case "police" -> { d.addHeat(id, 25); d.addRep(id, -3); msg(killer, "§9Убийство полицейского! §cРозыск +25"); }
            default -> {}
        }
    }
}
