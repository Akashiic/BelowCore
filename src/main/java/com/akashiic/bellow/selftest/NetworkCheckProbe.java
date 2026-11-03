package com.akashiic.bellow.selftest;

import java.util.HashMap;
import java.util.Map;

import com.akashiic.bellow.AkashicBellow;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.internal.FMLNetworkHandler;
import cpw.mods.fml.common.network.internal.NetworkModHolder;
import cpw.mods.fml.relauncher.Side;

/**
 * The FML handshake decision about the other side's mod list, through FML's own code: {@code NetworkModHolder.check}
 * for Bellow and {@code FMLNetworkHandler.checkModList}, which both handshake states call. The side is the side of
 * the list: {@code SERVER} is a client looking at a server's mods.
 */
public final class NetworkCheckProbe {

    private NetworkCheckProbe() {}

    public static void run(BukkitProbe.Check check) {
        ModContainer bellow = Loader.instance()
            .getIndexedModList()
            .get(AkashicBellow.MODID);
        NetworkModHolder holder = NetworkRegistry.INSTANCE.registry()
            .get(bellow);
        if (holder == null) {
            check.check("network check of the mod list", false, "Bellow has no network holder");
            return;
        }
        String version = bellow.getVersion();
        Map<String, String> same = new HashMap<>(), without = new HashMap<>(), other = new HashMap<>();
        for (ModContainer mod : Loader.instance()
            .getModList()) {
            if (mod == bellow) continue;
            same.put(mod.getModId(), mod.getVersion());
            without.put(mod.getModId(), mod.getVersion());
            other.put(mod.getModId(), mod.getVersion());
        }
        same.put(AkashicBellow.MODID, version);
        other.put(AkashicBellow.MODID, version + "-other");
        boolean sameOk = holder.check(same, Side.SERVER) && holder.check(same, Side.CLIENT);
        boolean serverWithout = holder.check(without, Side.SERVER);
        boolean clientWithout = holder.check(without, Side.CLIENT);
        boolean otherVersion = holder.check(other, Side.SERVER) || holder.check(other, Side.CLIENT);
        boolean vanilla = holder.acceptsVanilla(Side.SERVER) || holder.acceptsVanilla(Side.CLIENT);
        String sameResult = FMLNetworkHandler.checkModList(same, Side.SERVER);
        String withoutResult = FMLNetworkHandler.checkModList(without, Side.SERVER);
        check.check(
            "joining a server without Bellow is refused in the handshake",
            !serverWithout && withoutResult != null && withoutResult.contains(AkashicBellow.MODID),
            "server without Bellow: " + (serverWithout ? "accepted" : "refused") + ", FML: " + withoutResult);
        // other mods of the pack may refuse this list with their own checks: only Bellow's verdict counts here
        boolean sameAccepted = sameResult == null || !sameResult.contains(AkashicBellow.MODID + "{");
        check.check(
            "the handshake still accepts the same build and refuses the rest",
            sameOk && sameAccepted && !clientWithout && !otherVersion && !vanilla,
            "same build " + (sameOk ? "accepted" : "refused")
                + " (FML: "
                + sameResult
                + "), client without Bellow "
                + (clientWithout ? "accepted" : "refused")
                + ", other version "
                + (otherVersion ? "accepted" : "refused")
                + ", vanilla "
                + (vanilla ? "accepted" : "refused"));
    }
}
