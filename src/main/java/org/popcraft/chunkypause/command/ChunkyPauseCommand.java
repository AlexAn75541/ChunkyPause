package org.popcraft.chunkypause.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.popcraft.chunky.api.ChunkyAPI;
import org.popcraft.chunkypause.ChunkyPause;
import org.popcraft.chunkypause.memory.MemoryInfo;
import org.popcraft.chunkypause.memory.MemoryManager;

import java.util.List;

import static org.popcraft.chunkypause.util.ColorUtil.*;

/**
 * Handles all plugin commands
 */
public class ChunkyPauseCommand implements CommandExecutor, TabCompleter {
    
    private final ChunkyPause plugin;
    private final ChunkyAPI chunky;
    private final MemoryManager memoryManager;
    
    public ChunkyPauseCommand(ChunkyPause plugin, ChunkyAPI chunky, MemoryManager memoryManager) {
        this.plugin = plugin;
        this.chunky = chunky;
        this.memoryManager = memoryManager;
    }
    
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, 
                           @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            displayStatus(sender);
            return true;
        }
        
        String subCommand = args[0].toLowerCase();
        
        switch (subCommand) {
            case "reload":
                return handleReload(sender);
            case "gc":
                return handleGC(sender);
            case "forcepause":
                return handleForcePause(sender);
            default:
                return handleSetMaxPlayers(sender, args[0]);
        }
    }
    
    /**
     * Display plugin status
     */
    private void displayStatus(CommandSender sender) {
        MemoryInfo memInfo = memoryManager.getMemoryInfo();
        int currentPlayers = Bukkit.getOnlinePlayers().size();
        
        sender.sendMessage(colorize("&6═══════════════════════════════════"));
        sender.sendMessage(colorize("&6       ChunkyPause Status"));
        sender.sendMessage(colorize("&6═══════════════════════════════════"));
        sender.sendMessage(colorize("&7Max players: &e" + plugin.getMaxPlayers()));
        sender.sendMessage(colorize("&7Current players: &e" + currentPlayers + 
            (currentPlayers > plugin.getMaxPlayers() ? " &c(OVER LIMIT)" : " &a(OK)")));
        sender.sendMessage("");
        sender.sendMessage(colorize("&7Memory usage: &e" + String.format("%.1f%%", memInfo.getUsagePercent() * 100)));
        sender.sendMessage(colorize("&7Memory: &e" + memInfo.getUsedMB() + "MB &7/ &e" + memInfo.getMaxMB() + "MB"));
        sender.sendMessage(colorize("&7Allocated: &e" + memInfo.getAllocatedMB() + "MB"));
        sender.sendMessage("");
        sender.sendMessage(colorize("&7JVM: &e" + plugin.getJvmName()));
        sender.sendMessage(colorize("&7Version: &e" + plugin.getJvmVersion()));
        sender.sendMessage(colorize("&7GC Type: &e" + plugin.getGcType()));
        
        if (plugin.isFixedHeapSize()) {
            sender.sendMessage(colorize("&7Heap: &eFixed (-Xmx ≈ -Xms)"));
        } else {
            sender.sendMessage(colorize("&7Heap: &aDynamic"));
        }
        
        sender.sendMessage("");
        sender.sendMessage(colorize("&7Paused by memory: &e" + plugin.isPausedByMemory()));
        sender.sendMessage(colorize("&7Paused by players: &e" + plugin.isPausedByPlayers()));
        sender.sendMessage(colorize("&7Force paused: &e" + plugin.isForcePaused()));
        sender.sendMessage(colorize("&7Clean on join: &e" + plugin.isCleanMemoryOnJoin()));
        sender.sendMessage(colorize("&6═══════════════════════════════════"));
        sender.sendMessage(colorize("&7Commands:"));
        sender.sendMessage(colorize("&e  /chunkypause <number> &7- Set max players"));
        sender.sendMessage(colorize("&e  /chunkypause reload &7- Reload config"));
        sender.sendMessage(colorize("&e  /chunkypause gc &7- Force GC"));
        sender.sendMessage(colorize("&e  /chunkypause forcepause &7- Toggle force pause"));
    }
    
    /**
     * Handle reload command
     */
    private boolean handleReload(CommandSender sender) {
        plugin.reloadConfig();
        plugin.loadConfiguration();
        sender.sendMessage(colorize("&aConfiguration reloaded!"));
        sender.sendMessage(colorize("&7Max players: &e" + plugin.getMaxPlayers()));
        return true;
    }
    
    /**
     * Handle GC command
     */
    private boolean handleGC(CommandSender sender) {
        sender.sendMessage(colorize("&6Forcing garbage collection..."));
        plugin.resetGCCooldown();
        memoryManager.performGC("manual command");
        return true;
    }
    
    /**
     * Handle force pause command
     */
    private boolean handleForcePause(CommandSender sender) {
        boolean newState = !plugin.isForcePaused();
        plugin.setForcePaused(newState);
        
        plugin.getConfig().set("force-paused", newState);
        plugin.saveConfig();
        
        if (newState) {
            sender.sendMessage(colorize("&6Force pausing Chunky generation..."));
            Bukkit.getServer().getWorlds().forEach(world -> {
                try {
                    chunky.pauseTask(world.getName());
                } catch (Exception e) {
                    // Task might not be running
                }
            });
            sender.sendMessage(colorize("&aChunky is now force paused. It will not resume automatically."));
            sender.sendMessage(colorize("&7Use &e/chunkypause forcepause &7again to allow automatic resuming."));
            sender.sendMessage(colorize("&7This state will persist across server restarts."));
        } else {
            sender.sendMessage(colorize("&aForce pause disabled. Chunky can now resume automatically."));
            sender.sendMessage(colorize("&7This state has been saved to config."));
            
            int currentPlayers = Bukkit.getOnlinePlayers().size();
            MemoryInfo memInfo = memoryManager.getMemoryInfo();
            
            if (currentPlayers <= plugin.getMaxPlayers() && 
                memInfo.getUsagePercent() < plugin.getMemoryThreshold() &&
                !plugin.isPausedByMemory() &&
                !plugin.isPausedByPlayers()) {
                sender.sendMessage(colorize("&aConditions met - resuming Chunky generation..."));
                Bukkit.getServer().getWorlds().forEach(world -> {
                    try {
                        chunky.continueTask(world.getName());
                    } catch (Exception e) {
                        // Task might not exist
                    }
                });
            } else {
                sender.sendMessage(colorize("&7Chunky will resume when conditions are met:"));
                if (currentPlayers > plugin.getMaxPlayers()) {
                    sender.sendMessage(colorize("&7  - Players: &e" + currentPlayers + "/" + 
                        plugin.getMaxPlayers() + " &c(too many)"));
                }
                if (memInfo.getUsagePercent() >= plugin.getMemoryThreshold()) {
                    sender.sendMessage(colorize("&7  - Memory: &e" + 
                        String.format("%.1f%%", memInfo.getUsagePercent() * 100) + " &c(too high)"));
                }
            }
        }
        
        return true;
    }
    
    /**
     * Handle set max players command
     */
    private boolean handleSetMaxPlayers(CommandSender sender, String arg) {
        try {
            int newMaxPlayers = Integer.parseInt(arg);
            if (newMaxPlayers < 0) {
                sender.sendMessage(colorize("&cError: Player count must be 0 or greater"));
                return false;
            }
            
            plugin.setMaxPlayers(newMaxPlayers);
            plugin.getConfig().set("max-players", newMaxPlayers);
            plugin.saveConfig();
            sender.sendMessage(colorize("&aMax players changed to: &e" + newMaxPlayers));
            
            // Check if immediate action needed
            plugin.checkPlayerThreshold(sender);
            
            return true;
        } catch (NumberFormatException e) {
            sender.sendMessage(colorize("&cError: Please provide a valid number"));
            sender.sendMessage(colorize("&7Use /chunkypause <number> to set max players"));
            return false;
        }
    }
    
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, 
                                     @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("reload", "gc", "forcepause", "0", "1", "2", "5", "10");
        }
        return List.of();
    }
}
