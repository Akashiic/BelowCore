package com.akashiic.bellow;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.block.Block;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import com.akashiic.bellow.api.BellowAPI;
import com.akashiic.bellow.api.IDeepChunk;

/**
 * {@code /bellow info} - floor and deep state of the current chunk.<br>
 * {@code /bellow stats [radius]} - histogram of the blocks below Y 0 (air and bedrock aside) in loaded chunks around
 * the sender.<br>
 * {@code /bellow selftest} - runs {@link SelfTest} and prints the report. It writes blocks, chests and (on Crucible)
 * plugin regions and claims in the world, so it only runs on servers started with
 * {@code -Dakashicbellow.selftest=true}.
 */
public class BellowCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "bellow";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/bellow <info|stats [radius]|selftest>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        return args.length == 1 ? getListOfStringsMatchingLastWord(args, "info", "stats", "selftest") : null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0) throw new WrongUsageException(this.getCommandUsage(sender));
        World world = sender.getEntityWorld();
        int x = sender.getPlayerCoordinates().posX;
        int z = sender.getPlayerCoordinates().posZ;
        switch (args[0]) {
            case "info": {
                Chunk chunk = world.getChunkFromBlockCoords(x, z);
                IDeepChunk deep = (IDeepChunk) chunk;
                int sections = 0;
                ExtendedBlockStorage[] arr = deep.bellow$peekDeepSections();
                if (arr != null) for (ExtendedBlockStorage s : arr) if (s != null) sections++;
                say(
                    sender,
                    String.format(
                        "dim %d minY %d%s | chunk [%d, %d] gen=%d deepSections=%d fromDisk=%s",
                        world.provider.dimensionId,
                        BellowAPI.getMinY(world),
                        BellowAPI.getDimensionOwner(world.provider.dimensionId) == null ? ""
                            : " claimed by " + BellowAPI.getDimensionOwner(world.provider.dimensionId),
                        chunk.xPosition,
                        chunk.zPosition,
                        deep.bellow$getGenState(),
                        sections,
                        deep.bellow$isFromDisk()));
                return;
            }
            case "stats": {
                // the scan runs on the main thread: about 20 million blocks at most (radius 16 at -64, 11 at -128, 5 at
                // -512)
                int perChunk = Math.max(1, -BellowAPI.getMinY(world) << 8);
                int maxRadius = Math.max(0, (int) ((Math.sqrt(20_000_000.0 / perChunk) - 1) / 2));
                int radius = args.length > 1
                    ? MathHelper.clamp_int(parseInt(sender, args[1]), 0, Math.min(16, maxRadius))
                    : Math.min(4, maxRadius);
                Map<String, long[]> counts = SelfTest.histogram(world, x >> 4, z >> 4, radius);
                say(
                    sender,
                    "Blocks below Y 0 in " + (2 * radius + 1) * (2 * radius + 1)
                        + " chunks (per 16-block band, bottom first):");
                for (Map.Entry<String, long[]> e : new TreeMap<>(counts).entrySet()) {
                    StringBuilder b = new StringBuilder(e.getKey()).append(':');
                    for (long v : e.getValue()) b.append(' ')
                        .append(v);
                    say(sender, b.toString());
                }
                return;
            }
            case "selftest": {
                if (!Boolean.getBoolean("akashicbellow.selftest")) {
                    say(
                        sender,
                        "The self test writes test blocks and chests into this world; it only runs on a test server"
                            + " started with -Dakashicbellow.selftest=true.");
                    return;
                }
                for (String line : new SelfTest(world).run()) say(sender, line);
                return;
            }
            default:
                throw new WrongUsageException(this.getCommandUsage(sender));
        }
    }

    static String name(Block block, int meta) {
        Object id = Block.blockRegistry.getNameForObject(block);
        return id + (meta != 0 ? ":" + meta : "");
    }

    private static void say(ICommandSender sender, String text) {
        sender.addChatMessage(new ChatComponentText(text));
    }
}
