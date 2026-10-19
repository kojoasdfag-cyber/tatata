package com.example.sniper;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SniperMod implements ClientModInitializer {
    // ---- config (change with /sniper ... commands or edit here) ----
    static String streamer = "streamername";
    static long maxPay = 50000, increment = 500, auctionMs = 30000, fireBeforeEndMs = 1300, streamDelayMs = 3500;
    static boolean dryRun = true, requireStreamerInLine = true;
    // Group 1 must capture the amount. ADJUST to the real chat format!
    static Pattern payRegex = Pattern.compile("paid .*?\\$([\\d,.]+\\s*[kmb]?)", Pattern.CASE_INSENSITIVE);

    // ---- state ----
    static long top = 0, startedAt = 0;
    static volatile boolean running = false, paid = false, showChat = false;
    static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor();
    static ScheduledFuture<?> timer;

    static void say(String s) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> { if (mc.player != null) mc.player.sendMessage(Text.literal("[Sniper] " + s), false); });
    }

    static long parseAmount(String s) {
        Matcher m = Pattern.compile("^([\\d.]+)\\s*([kmb])?$", Pattern.CASE_INSENSITIVE)
                .matcher(s.replace(",", "").trim());
        if (!m.matches()) return -1;
        double mult = 1;
        if (m.group(2) != null) {
            switch (m.group(2).toLowerCase()) { case "k" -> mult = 1e3; case "m" -> mult = 1e6; case "b" -> mult = 1e9; }
        }
        return Math.round(Double.parseDouble(m.group(1)) * mult);
    }

    static synchronized void start(long delayMs) {
        if (timer != null) timer.cancel(false);
        top = 0; paid = false; running = true;
        startedAt = System.currentTimeMillis() - delayMs;
        long wait = Math.max(0, auctionMs - delayMs - fireBeforeEndMs);
        timer = EXEC.schedule(SniperMod::fire, wait, TimeUnit.MILLISECONDS);
        say("Auction started (delay " + delayMs + "ms), firing in " + wait + "ms");
    }

    static synchronized void stop() {
        if (timer != null) timer.cancel(false);
        running = false;
        say("Auction stopped");
    }

    static synchronized void fire() {
        if (!running || paid) return;
        running = false;
        long amount = top + increment;
        if (amount > maxPay) { say("SKIP: needed " + amount + " > cap " + maxPay); return; }
        paid = true; // set before sending so it can never fire twice
        String cmd = "pay " + streamer + " " + amount;
        if (dryRun) { say("[DRY RUN] would send: /" + cmd); return; }
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> { if (mc.getNetworkHandler() != null) mc.getNetworkHandler().sendChatCommand(cmd); });
        say("SENT: /" + cmd);
    }

        static void onChat(String msg) {
        if (msg.startsWith("[Sniper]")) return;
        if (showChat) say("CHAT: " + msg);
        if (!running) return;
        if (requireStreamerInLine && !msg.toLowerCase().contains(streamer.toLowerCase())) return;
        Matcher m = payRegex.matcher(msg);
        if (!m.find()) return;
        long amount = parseAmount(m.group(1));
        if (amount > top) { top = amount; say("New top payment: " + top + " (t+" + (System.currentTimeMillis() - startedAt) + "ms)"); }
    }

    @Override
    public void onInitializeClient() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> { if (!overlay) onChat(message.getString()); });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var root = ClientCommandManager.literal("sniper");
            root.then(ClientCommandManager.literal("start").executes(c -> { start(streamDelayMs); return 1; }));
            root.then(ClientCommandManager.literal("startnow").executes(c -> { start(0); return 1; }));
            root.then(ClientCommandManager.literal("stop").executes(c -> { stop(); return 1; }));
            root.then(ClientCommandManager.literal("live").executes(c -> { dryRun = false; say("LIVE: real payments ON"); return 1; }));
            root.then(ClientCommandManager.literal("dry").executes(c -> { dryRun = true; say("Dry run: payments OFF"); return 1; }));
            root.then(ClientCommandManager.literal("chat").executes(c -> { showChat = !showChat; say("show chat = " + showChat); return 1; }));
            root.then(ClientCommandManager.literal("status").executes(c -> {
                say("top=" + top + " running=" + running + " paid=" + paid + " max=" + maxPay + " dry=" + dryRun + " streamer=" + streamer);
                return 1; }));
            root.then(ClientCommandManager.literal("delay").then(ClientCommandManager.argument("ms", LongArgumentType.longArg(0))
                    .executes(c -> { streamDelayMs = LongArgumentType.getLong(c, "ms"); say("streamDelayMs = " + streamDelayMs); return 1; })));
            root.then(ClientCommandManager.literal("top").then(ClientCommandManager.argument("n", StringArgumentType.word())
                    .executes(c -> { top = Math.max(0, parseAmount(StringArgumentType.getString(c, "n"))); say("top = " + top); return 1; })));
            root.then(ClientCommandManager.literal("max").then(ClientCommandManager.argument("n", StringArgumentType.word())
                    .executes(c -> { maxPay = Math.max(0, parseAmount(StringArgumentType.getString(c, "n"))); say("max = " + maxPay); return 1; })));
            root.then(ClientCommandManager.literal("streamer").then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .executes(c -> { streamer = StringArgumentType.getString(c, "name"); say("streamer = " + streamer); return 1; })));
            dispatcher.register(root);
        });
    }
}
