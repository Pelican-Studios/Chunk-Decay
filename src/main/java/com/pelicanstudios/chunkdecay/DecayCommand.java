package com.pelicanstudios.chunkdecay;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * /chunkdecay start | stop | status | delay <seconds> | speed <layers> | particles on|off | resetstats
 *
 * No permission requirement, so it works in singleplayer worlds without cheats enabled.
 */
public final class DecayCommand {
    private DecayCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("chunkdecay")
                .executes(DecayCommand::status)
                .then(CommandManager.literal("status").executes(DecayCommand::status))
                .then(CommandManager.literal("start").executes(DecayCommand::start))
                .then(CommandManager.literal("stop").executes(DecayCommand::stop))
                .then(CommandManager.literal("delay")
                        .then(CommandManager.argument("seconds", IntegerArgumentType.integer(0, 600))
                                .executes(ctx -> {
                                    DecayManager m = manager(ctx);
                                    if (m == null) return 0;
                                    int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
                                    m.setDelaySeconds(seconds);
                                    feedback(ctx, prefix().append(Text.literal("Chunks now decay ")
                                            .append(Text.literal(seconds + "s").formatted(Formatting.YELLOW))
                                            .append(" after you leave them.").formatted(Formatting.GRAY)));
                                    return 1;
                                })))
                .then(CommandManager.literal("speed")
                        .then(CommandManager.argument("layers", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> {
                                    DecayManager m = manager(ctx);
                                    if (m == null) return 0;
                                    int layers = IntegerArgumentType.getInteger(ctx, "layers");
                                    m.setLayersPerTick(layers);
                                    feedback(ctx, prefix().append(Text.literal("Collapse speed set to ")
                                            .append(Text.literal(layers + " layers/tick").formatted(Formatting.YELLOW))
                                            .append(" (default 4).").formatted(Formatting.GRAY)));
                                    return 1;
                                })))
                .then(CommandManager.literal("particles")
                        .then(CommandManager.literal("on").executes(ctx -> particles(ctx, true)))
                        .then(CommandManager.literal("off").executes(ctx -> particles(ctx, false))))
                .then(CommandManager.literal("resetstats").executes(ctx -> {
                    DecayManager m = manager(ctx);
                    if (m == null) return 0;
                    m.resetStats();
                    feedback(ctx, prefix().append(Text.literal("Decayed-chunk counter reset.").formatted(Formatting.GRAY)));
                    return 1;
                })));
    }

    private static int start(CommandContext<ServerCommandSource> ctx) {
        DecayManager m = manager(ctx);
        if (m == null) return 0;
        if (m.isEnabled()) {
            ctx.getSource().sendError(Text.literal("Chunk Decay is already running. Use /chunkdecay stop first."));
            return 0;
        }
        m.start();

        Text title = Text.literal("☠ CHUNK DECAY STARTED ☠").formatted(Formatting.DARK_RED, Formatting.BOLD);
        Text info = Text.literal("Every chunk you leave crumbles into the void ").formatted(Formatting.RED)
                .append(Text.literal(m.getDelaySeconds() + "s").formatted(Formatting.YELLOW))
                .append(Text.literal(" later. Don't look back.").formatted(Formatting.RED));
        for (ServerWorld world : ctx.getSource().getServer().getWorlds()) {
            for (ServerPlayerEntity player : world.getPlayers()) {
                player.sendMessage(title, false);
                player.sendMessage(info, false);
                world.playSound(null, player.getX(), player.getY(), player.getZ(),
                        DecayManager.startSound(), SoundCategory.MASTER, 0.7f, 1.0f);
            }
        }
        return 1;
    }

    private static int stop(CommandContext<ServerCommandSource> ctx) {
        DecayManager m = manager(ctx);
        if (m == null) return 0;
        if (!m.isEnabled()) {
            ctx.getSource().sendError(Text.literal("Chunk Decay isn't running."));
            return 0;
        }
        m.stop();
        feedback(ctx, prefix().append(Text.literal("Stopped. Queued chunks were spared - nothing else will decay.").formatted(Formatting.GREEN)));
        return 1;
    }

    private static int status(CommandContext<ServerCommandSource> ctx) {
        DecayManager m = manager(ctx);
        if (m == null) return 0;
        MutableText msg = prefix()
                .append(m.isEnabled()
                        ? Text.literal("RUNNING").formatted(Formatting.RED, Formatting.BOLD)
                        : Text.literal("OFF").formatted(Formatting.GREEN, Formatting.BOLD))
                .append(line("Delay", m.getDelaySeconds() + "s"))
                .append(line("Speed", m.getLayersPerTick() + " layers/tick"))
                .append(line("Particles", m.hasParticles() ? "on" : "off"))
                .append(line("Queued", String.valueOf(m.getQueuedCount())))
                .append(line("Collapsing now", String.valueOf(m.getActiveCount())))
                .append(line("Chunks decayed", String.valueOf(m.getChunksDecayed())));
        if (!m.isEnabled()) {
            msg.append(Text.literal("\nType /chunkdecay start to begin.").formatted(Formatting.GRAY, Formatting.ITALIC));
        }
        feedback(ctx, msg);
        return 1;
    }

    private static int particles(CommandContext<ServerCommandSource> ctx, boolean on) {
        DecayManager m = manager(ctx);
        if (m == null) return 0;
        m.setParticles(on);
        feedback(ctx, prefix().append(Text.literal("Collapse particles " + (on ? "on." : "off.")).formatted(Formatting.GRAY)));
        return 1;
    }

    // ------------------------------------------------------------------ helpers

    private static DecayManager manager(CommandContext<ServerCommandSource> ctx) {
        DecayManager m = ChunkDecay.manager();
        if (m == null) {
            ctx.getSource().sendError(Text.literal("Chunk Decay isn't ready yet."));
        }
        return m;
    }

    private static MutableText prefix() {
        return Text.literal("[Chunk Decay] ").formatted(Formatting.DARK_PURPLE);
    }

    private static MutableText line(String label, String value) {
        return Text.literal("\n  " + label + ": ").formatted(Formatting.GRAY)
                .append(Text.literal(value).formatted(Formatting.WHITE));
    }

    private static void feedback(CommandContext<ServerCommandSource> ctx, Text text) {
        ctx.getSource().sendFeedback(() -> text, false);
    }
}
