package com.kiyoske.blackmarket;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Random;
import java.util.UUID;

/** Мировое хранилище: кредиты, репутация, розыск, долги, заказы, аукционы (работает и для офлайн-игроков). */
public class BMData extends SavedData {
    public static final Random RNG = new Random();
    public CompoundTag root = new CompoundTag();

    public static BMData get(MinecraftServer s) {
        return s.overworld().getDataStorage().computeIfAbsent(tag -> { BMData d = new BMData(); d.root = tag; return d; }, BMData::new, "blackmarket");
    }

    @Override public CompoundTag save(CompoundTag tag) { tag.merge(root); return tag; }

    public static CompoundTag sub(CompoundTag parent, String key) {
        if (!parent.contains(key, 10)) parent.put(key, new CompoundTag());
        return parent.getCompound(key);
    }

    /** Данные игрока (изменяемый тег). */
    public CompoundTag p(UUID id) {
        CompoundTag all = sub(root, "players");
        String k = id.toString();
        if (!all.contains(k, 10)) all.put(k, new CompoundTag());
        setDirty();
        return all.getCompound(k);
    }

    public int credits(UUID id) { return p(id).getInt("credits"); }
    public void setCredits(UUID id, int v) { p(id).putInt("credits", Math.max(0, v)); }
    public void addCredits(UUID id, int v) { setCredits(id, credits(id) + v); }
    public int rep(UUID id) { return p(id).getInt("rep"); }
    public void addRep(UUID id, int v) { CompoundTag t = p(id); t.putInt("rep", Math.max(-50, Math.min(5000, t.getInt("rep") + v))); }
    public int heat(UUID id) { return p(id).getInt("heat"); }
    public void addHeat(UUID id, int v) { CompoundTag t = p(id); t.putInt("heat", Math.max(0, Math.min(100, t.getInt("heat") + v))); }
    public int nextSeq(String key) { int v = root.getInt(key) + 1; root.putInt(key, v); setDirty(); return v; }

    /** Таймер: при первом вызове планирует, далее срабатывает раз в min..min+rnd тиков. */
    public static boolean due(CompoundTag t, String k, long now, int min, int rnd) {
        long v = t.getLong(k);
        long next = now + min + RNG.nextInt(rnd + 1);
        if (v == 0) { t.putLong(k, next); return false; }
        if (now < v) return false;
        t.putLong(k, next);
        return true;
    }

    public static String rank(int rep) {
        if (rep >= 800) return "Легенда";
        if (rep >= 400) return "Авторитет";
        if (rep >= 150) return "Свой";
        if (rep >= 50) return "Знакомый";
        if (rep >= 0) return "Новичок";
        return "Чужак";
    }
}
