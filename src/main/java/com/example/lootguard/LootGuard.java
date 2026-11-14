package com.example.lootguard;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Aesir Loot Guard - Gaks_v310
 *
 * Yakinda bir oyuncu olunce kilit suresi boyunca:
 *  - ekranda "X OLDU" yazar ve eline kilic alir
 *  - kristal / glowstone / anchor eline gelirse surekli guvenli slota gecer
 *  - kristal / glowstone / anchor kullanimini ve kristale vurmayi engeller
 *
 * Olum 3 yolla algilanir: oyuncunun olu olmasi, yakindan birden kaybolmasi, olum mesaji.
 */
public class LootGuard implements ClientModInitializer {
    public static volatile boolean enabled = true;
    public static volatile int lockSeconds = 5;   // kilit suresi (saniye), GUI'den ayarlanir

    private static final double RANGE = 30.0;     // olum algilama mesafesi (blok)
    private static final double NEAR = 20.0;      // "kayboldu" algilamasi icin mesafe
    private static final long DEDUPE_MS = 4000;   // ayni oyuncu icin tekrar algilama araligi

    private static KeyBinding openKey;
    private static volatile long lockUntil = 0;
    private static String lastName = "";

    private static final Map<String, Long> lastTrigger = new HashMap<>();
    private static Map<UUID, String> prevNear = new HashMap<>();
    private static final Set<String> nearNames = new HashSet<>();
    private static Object lastWorld = null;

    public static boolean locked() {
        return enabled && System.currentTimeMillis() < lockUntil;
    }

    private static boolean bad(ItemStack s) {
        return s.isOf(Items.END_CRYSTAL) || s.isOf(Items.GLOWSTONE) || s.isOf(Items.RESPAWN_ANCHOR);
    }

    private static void trigger(MinecraftClient client, String name) {
        long now = System.currentTimeMillis();
        Long last = lastTrigger.get(name);
        if (last != null && now - last < DEDUPE_MS) return;
        lastTrigger.put(name, now);
        lockUntil = now + lockSeconds * 1000L;
        lastName = name;
        switchToSword(client);
    }

    private static void switchToSword(MinecraftClient client) {
        var inv = client.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isIn(ItemTags.SWORDS)) {
                inv.setSelectedSlot(i);
                return;
            }
        }
    }

    /** Oncelik: kilic -> bos slot -> pearl -> totem. Bulamazsa bir sey yapmaz. */
    private static void switchToSafe(MinecraftClient client) {
        var inv = client.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isIn(ItemTags.SWORDS)) { inv.setSelectedSlot(i); return; }
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isEmpty()) { inv.setSelectedSlot(i); return; }
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(Items.ENDER_PEARL)) { inv.setSelectedSlot(i); return; }
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) { inv.setSelectedSlot(i); return; }
        }
    }

    @Override
    public void onInitializeClient() {
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("lootguard", "main"));
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.lootguard.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, category));

        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof GameMenuScreen) {
                Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("Aesir Loot Guard"),
                        b -> MinecraftClient.getInstance().setScreen(new LootGuardScreen()))
                        .dimensions(5, 5, 110, 20).build());
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                client.setScreen(new LootGuardScreen());
            }
            if (client.player == null || client.world == null) {
                prevNear.clear();
                nearNames.clear();
                lastWorld = null;
                return;
            }
            if (!enabled) {
                prevNear.clear();
                nearNames.clear();
                return;
            }
            if (client.world != lastWorld) {
                lastWorld = client.world;
                prevNear.clear();
            }

            Map<UUID, String> curNear = new HashMap<>();
            Set<UUID> present = new HashSet<>();
            nearNames.clear();
            for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
                if (p == client.player) continue;
                present.add(p.getUuid());
                String name = p.getName().getString();
                double d2 = client.player.squaredDistanceTo(p);
                if (d2 <= RANGE * RANGE) nearNames.add(name);
                if (p.isDead()) {                       // 1) olu olarak goruldu
                    if (d2 <= RANGE * RANGE) trigger(client, name);
                    continue;
                }
                if (d2 <= NEAR * NEAR) curNear.put(p.getUuid(), name);
            }
            // 2) yakindayken bir anda kayboldu (olu hali hic gorulmediyse)
            for (Map.Entry<UUID, String> e : prevNear.entrySet()) {
                if (!present.contains(e.getKey())) trigger(client, e.getValue());
            }
            prevNear = curNear;

            if (locked() && bad(client.player.getMainHandStack())) {
                switchToSafe(client);   // kilit boyunca surekli kontrol, her tick
            }
            if (locked() && client.player.age % 5 == 0) {
                long left = Math.max(0, lockUntil - System.currentTimeMillis());
                client.inGameHud.setOverlayMessage(
                        Text.literal("Aesir Loot Guard: " + lastName + " OLDU - loot korunuyor ("
                                + (left / 1000 + 1) + " sn)"), false);
            }
        });

        // 3) sohbetteki olum mesaji
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!enabled || overlay) return;
            if (!(message.getContent() instanceof TranslatableTextContent t)) return;
            if (!t.getKey().startsWith("death.")) return;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;
            for (Object a : t.getArgs()) {
                String s = (a instanceof Text tx) ? tx.getString() : String.valueOf(a);
                if (nearNames.contains(s)) trigger(client, s);
            }
        });

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClient() || !locked()) return ActionResult.PASS;
            if (bad(player.getStackInHand(hand))
                    || world.getBlockState(hit.getBlockPos()).isOf(Blocks.RESPAWN_ANCHOR)) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient() && locked() && entity instanceof EndCrystalEntity) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
    }
}
