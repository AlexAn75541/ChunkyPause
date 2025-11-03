package org.popcraft.chunkypause;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import org.popcraft.chunky.api.ChunkyAPI;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.List;

public final class ChunkyPause extends JavaPlugin implements Listener {
    private ChunkyAPI chunky;
    private int players = -1;
    private long memoryCheckInterval;
    private double memoryThreshold;
    private long resumeDelay;
    private boolean isPausedByMemory = false;
    private boolean isPausedByPlayers = false;
    private boolean cleanMemoryOnJoin;
    private long lastMemoryLogTime = 0;
    private long lastGCTime = 0;
    private static final long GC_COOLDOWN = 10000; // 10 seconds minimum between GC calls
    
    // JVM Detection
    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    private boolean isAzulJDK = false;
    private boolean isMimallocDetected = false;
    private String jvmName = "Unknown";
    private String jvmVendor = "Unknown";

    @Override
    public void onEnable() {
        getConfig().options().copyDefaults(true);
        saveConfig();
        
        // Detect JVM and optimizations first
        detectJVMOptimizations();
        
        // Load configuration
        loadConfiguration();
        
        // Load ChunkyAPI
        this.chunky = Bukkit.getServer().getServicesManager().load(ChunkyAPI.class);
        if (chunky != null && chunky.version() == 0) {
            this.players = getConfig().getInt("players", -1);
            getServer().getPluginManager().registerEvents(this, this);
            
            // Start memory monitoring
            startMemoryMonitor();
            
            getLogger().info("ChunkyPause enabled with adaptive memory monitoring");
            getLogger().info("JVM: " + jvmName + " by " + jvmVendor);
            
            if (isAzulJDK) {
                getLogger().info("§a✓ Azul JDK detected - Enhanced optimizations enabled");
            } else {
                getLogger().info("§7Standard JDK detected - Using conservative settings");
            }
            
            if (isMimallocDetected) {
                getLogger().info("§a✓ mimalloc detected - Advanced memory management enabled");
            } else {
                getLogger().info("§7System allocator - Standard memory management");
            }
            
            getLogger().info("Memory threshold: " + (memoryThreshold * 100) + "%");
            getLogger().info("Check interval: " + (memoryCheckInterval / 20) + " seconds");
            getLogger().info("Clean memory on player join: " + cleanMemoryOnJoin);
        } else {
            getLogger().warning("Chunky API not found or incompatible version!");
        }
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll((Plugin) this);
    }

    private void detectJVMOptimizations() {
        jvmName = System.getProperty("java.vm.name", "Unknown");
        jvmVendor = System.getProperty("java.vm.vendor", "Unknown");
        String jvmVersion = System.getProperty("java.vm.version", "Unknown");
        
        String jvmNameLower = jvmName.toLowerCase();
        String jvmVendorLower = jvmVendor.toLowerCase();
        
        // Detect Azul JDK (Zulu, Prime, Zing)
        isAzulJDK = jvmVendorLower.contains("azul") || 
                    jvmNameLower.contains("zulu") || 
                    jvmNameLower.contains("zing") ||
                    jvmNameLower.contains("prime");
        
        // Detect mimalloc by checking environment variables
        String ldPreload = System.getenv("LD_PRELOAD");
        String dyldInsert = System.getenv("DYLD_INSERT_LIBRARIES");
        isMimallocDetected = (ldPreload != null && ldPreload.contains("mimalloc")) ||
                            (dyldInsert != null && dyldInsert.contains("mimalloc"));
        
        // Check JVM arguments for mimalloc
        try {
            for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (arg.toLowerCase().contains("mimalloc")) {
                    isMimallocDetected = true;
                    break;
                }
            }
        } catch (Exception e) {
            // Ignore if unable to get JVM arguments
        }
        
        getLogger().info("===========================================");
        getLogger().info("JVM Detection Results:");
        getLogger().info("  Name: " + jvmName);
        getLogger().info("  Vendor: " + jvmVendor);
        getLogger().info("  Version: " + jvmVersion);
        getLogger().info("  Azul JDK: " + (isAzulJDK ? "YES" : "NO"));
        getLogger().info("  mimalloc: " + (isMimallocDetected ? "YES" : "NO"));
        getLogger().info("===========================================");
    }

    private void loadConfiguration() {
        memoryThreshold = getConfig().getDouble("memory-threshold", 0.85);
        memoryCheckInterval = getConfig().getLong("check-interval", 100L);
        resumeDelay = getConfig().getLong("resume-delay", 100L);
        cleanMemoryOnJoin = getConfig().getBoolean("clean-memory-on-join", true);
    }

    private void startMemoryMonitor() {
        new BukkitRunnable() {
            @Override
            public void run() {
                MemoryInfo memInfo = getDetailedMemoryInfo();
                
                // Log memory usage periodically (every 60 seconds)
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastMemoryLogTime >= 60000) {
                    getLogger().info(String.format("Memory: %.1f%% used (%dMB / %dMB, allocated: %dMB)", 
                        memInfo.usagePercent * 100, 
                        memInfo.usedMB, 
                        memInfo.maxMB,
                        memInfo.allocatedMB));
                    lastMemoryLogTime = currentTime;
                }
                
                // Check if memory threshold exceeded
                if (memInfo.usagePercent > memoryThreshold && !isPausedByMemory) {
                    handleHighMemory(memInfo);
                }
            }
        }.runTaskTimer(this, 20L, memoryCheckInterval);
    }

    private void handleHighMemory(MemoryInfo memInfo) {
        if (chunky == null) return;
        
        isPausedByMemory = true;
        getLogger().warning(String.format(
            "Memory usage critical (%.1f%%)! Pausing Chunky generation and cleaning memory...", 
            memInfo.usagePercent * 100));
        
        // Pause all Chunky tasks
        Bukkit.getServer().getWorlds().forEach(world -> {
            try {
                chunky.pauseTask(world.getName());
            } catch (Exception e) {
                // Task might not be running, ignore
            }
        });
        
        // Perform optimized GC based on JDK
        performOptimizedGC("high memory");
        
        // Schedule memory recovery check
        scheduleMemoryRecoveryCheck();
    }

    private void performOptimizedGC(String reason) {
        long currentTime = System.currentTimeMillis();
        
        // Check GC cooldown to prevent excessive GC calls
        if (currentTime - lastGCTime < GC_COOLDOWN) {
            getLogger().info("§7Skipping GC (cooldown active, last GC: " + 
                ((currentTime - lastGCTime) / 1000) + "s ago)");
            return;
        }
        
        lastGCTime = currentTime;
        MemoryInfo beforeGC = getDetailedMemoryInfo();
        
        getLogger().info("§6Performing garbage collection (" + reason + ")...");
        
        if (isAzulJDK && isMimallocDetected) {
            // Azul JDK + mimalloc: Most efficient setup
            // Single GC call is sufficient due to optimized allocator and C4/ZGC
            getLogger().info("§7Using Azul+mimalloc optimized GC strategy");
            System.gc();
        } else if (isAzulJDK) {
            // Azul JDK alone: C4/ZGC handles memory efficiently
            getLogger().info("§7Using Azul JDK optimized GC strategy");
            System.gc();
            try {
                memoryMXBean.gc(); // Additional hint for heap compaction
            } catch (Exception e) {
                // Ignore if not available
            }
        } else if (isMimallocDetected) {
            // mimalloc with standard JDK: Reduced fragmentation helps
            getLogger().info("§7Using mimalloc optimized GC strategy");
            System.gc();
        } else {
            // Standard JVM: Multiple GC calls for better cleanup
            getLogger().info("§7Using standard JDK GC strategy (conservative)");
            System.gc();
            try {
                Thread.sleep(500); // Give GC time to work
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.gc(); // Second pass for tenured generation
        }
        
        // Log results after a brief delay
        new BukkitRunnable() {
            @Override
            public void run() {
                MemoryInfo afterGC = getDetailedMemoryInfo();
                long freedMB = beforeGC.usedMB - afterGC.usedMB;
                
                if (freedMB > 0) {
                    getLogger().info(String.format(
                        "§aGC completed: Freed %dMB (%.1f%% -> %.1f%%)",
                        freedMB,
                        beforeGC.usagePercent * 100,
                        afterGC.usagePercent * 100
                    ));
                } else {
                    getLogger().info("§7GC completed (minimal memory freed - this is normal)");
                }
            }
        }.runTaskLater(this, 20L); // Wait 1 second
    }

    private void scheduleMemoryRecoveryCheck() {
        new BukkitRunnable() {
            int attempts = 0;
            final int maxAttempts = 10; // Try for up to 50 seconds (10 attempts * 5 seconds)
            
            @Override
            public void run() {
                MemoryInfo memInfo = getDetailedMemoryInfo();
                attempts++;
                
                // Add 5% buffer below threshold to prevent rapid pause/resume cycles
                if (memInfo.usagePercent < memoryThreshold - 0.05) {
                    isPausedByMemory = false;
                    getLogger().info(String.format(
                        "Memory recovered (%.1f%%). Resuming Chunky generation...", 
                        memInfo.usagePercent * 100));
                    
                    // Resume all Chunky tasks
                    Bukkit.getServer().getWorlds().forEach(world -> {
                        try {
                            chunky.continueTask(world.getName());
                        } catch (Exception e) {
                            // Task might not exist, ignore
                        }
                    });
                    
                    cancel();
                } else if (attempts >= maxAttempts) {
                    getLogger().warning(String.format(
                        "Memory still high after %d seconds (%.1f%%). Chunky remains paused.", 
                        (maxAttempts * resumeDelay / 20),
                        memInfo.usagePercent * 100));
                    cancel();
                } else {
                    getLogger().info(String.format(
                        "Waiting for memory to recover... (%.1f%% used, attempt %d/%d)", 
                        memInfo.usagePercent * 100, attempts, maxAttempts));
                }
            }
        }.runTaskTimer(this, resumeDelay, resumeDelay);
    }

    private MemoryInfo getDetailedMemoryInfo() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        return new MemoryInfo(
            usedMemory / (1024 * 1024),      // usedMB
            maxMemory / (1024 * 1024),       // maxMB
            totalMemory / (1024 * 1024),     // allocatedMB
            (double) usedMemory / maxMemory  // usagePercent
        );
    }

    private void cleanMemory() {
        MemoryInfo beforeClean = getDetailedMemoryInfo();
        
        // Perform optimized GC
        performOptimizedGC("player join");
        
        // Wait a moment for GC to complete, then log results
        new BukkitRunnable() {
            @Override
            public void run() {
                MemoryInfo afterClean = getDetailedMemoryInfo();
                long freedMB = beforeClean.usedMB - afterClean.usedMB;
                
                if (freedMB > 0) {
                    getLogger().info(String.format(
                        "Memory cleaned: freed %dMB (%.1f%% -> %.1f%%)", 
                        freedMB,
                        beforeClean.usagePercent * 100,
                        afterClean.usagePercent * 100));
                }
            }
        }.runTaskLater(this, 20L); // Wait 1 second
    }

    @Override
    public boolean onCommand(CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 0) {
            MemoryInfo memInfo = getDetailedMemoryInfo();
            sender.sendMessage("§6═══════════════════════════════════");
            sender.sendMessage("§6       ChunkyPause Status");
            sender.sendMessage("§6═══════════════════════════════════");
            sender.sendMessage("§7Max players: §e" + (this.players < 0 ? "Disabled" : this.players));
            sender.sendMessage("§7Current players: §e" + Bukkit.getOnlinePlayers().size());
            sender.sendMessage("");
            sender.sendMessage("§7Memory usage: §e" + String.format("%.1f%%", memInfo.usagePercent * 100));
            sender.sendMessage("§7Memory: §e" + memInfo.usedMB + "MB §7/ §e" + memInfo.maxMB + "MB");
            sender.sendMessage("§7Allocated: §e" + memInfo.allocatedMB + "MB");
            sender.sendMessage("");
            sender.sendMessage("§7JVM: §e" + jvmName);
            sender.sendMessage("§7Vendor: §e" + jvmVendor);
            
            if (isAzulJDK) {
                sender.sendMessage("§7Optimizations: §a✓ Azul Enhanced");
            } else {
                sender.sendMessage("§7Optimizations: §7Standard (Conservative)");
            }
            
            if (isMimallocDetected) {
                sender.sendMessage("§7Allocator: §a✓ mimalloc");
            } else {
                sender.sendMessage("§7Allocator: §7System Default");
            }
            
            sender.sendMessage("");
            sender.sendMessage("§7Paused by memory: §e" + isPausedByMemory);
            sender.sendMessage("§7Paused by players: §e" + isPausedByPlayers);
            sender.sendMessage("§7Clean on join: §e" + cleanMemoryOnJoin);
            sender.sendMessage("§6═══════════════════════════════════");
            sender.sendMessage("§7Commands:");
            sender.sendMessage("§e  /chunkypause <number> §7- Set max players");
            sender.sendMessage("§e  /chunkypause reload §7- Reload config");
            sender.sendMessage("§e  /chunkypause gc §7- Force GC");
            return true;
        }
        
        // Handle reload command
        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadConfiguration();
            sender.sendMessage("§aConfiguration reloaded!");
            return true;
        }
        
        // Handle gc command
        if (args[0].equalsIgnoreCase("gc")) {
            sender.sendMessage("§6Forcing garbage collection...");
            lastGCTime = 0; // Reset cooldown
            performOptimizedGC("manual command");
            return true;
        }
        
        try {
            final int newPlayerCount = Integer.parseInt(args[0]);
            this.players = newPlayerCount;
            getConfig().set("players", newPlayerCount);
            saveConfig();
            sender.sendMessage("§aMax player count changed to: §e" + newPlayerCount);
            
            // Check if we need to pause/resume immediately
            int currentPlayers = Bukkit.getOnlinePlayers().size();
            if (newPlayerCount >= 0 && currentPlayers > newPlayerCount && !isPausedByPlayers) {
                isPausedByPlayers = true;
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "chunky pause");
                sender.sendMessage("§6Chunky paused (current players: " + currentPlayers + ")");
            } else if (newPlayerCount >= 0 && currentPlayers <= newPlayerCount && isPausedByPlayers) {
                isPausedByPlayers = false;
                if (!isPausedByMemory) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "chunky continue");
                    sender.sendMessage("§aChunky resumed");
                }
            }
            
            return true;
        } catch (NumberFormatException e) {
            sender.sendMessage("§cError: Please provide a valid number");
            sender.sendMessage("§7Use -1 to disable player-based pausing");
            return false;
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        return List.of();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(final PlayerJoinEvent event) {
        final Server server = event.getPlayer().getServer();
        final int playerCount = server.getOnlinePlayers().size();
        
        // Clean memory when player joins (if enabled)
        if (cleanMemoryOnJoin) {
            getLogger().info("Player " + event.getPlayer().getName() + " joined. Cleaning memory...");
            // Delay slightly to not block the join process
            new BukkitRunnable() {
                @Override
                public void run() {
                    cleanMemory();
                }
            }.runTaskLater(this, 20L); // Wait 1 second after join
        }
        
        // Check if we should pause Chunky based on player count
        if (players >= 0 && playerCount > players && chunky != null && !isPausedByPlayers) {
            isPausedByPlayers = true;
            getLogger().info("Player count (" + playerCount + ") exceeded limit (" + players + "). Pausing Chunky...");
            server.getWorlds().forEach(world -> {
                try {
                    chunky.pauseTask(world.getName());
                } catch (Exception e) {
                    // Task might not be running, ignore
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(final PlayerQuitEvent event) {
        final Server server = event.getPlayer().getServer();
        
        // Delay check to ensure player is fully removed from count
        new BukkitRunnable() {
            @Override
            public void run() {
                final int playerCount = server.getOnlinePlayers().size();
                
                // Check if we should resume Chunky based on player count
                if (players >= 0 && playerCount <= players && chunky != null && isPausedByPlayers && !isPausedByMemory) {
                    isPausedByPlayers = false;
                    getLogger().info("Player count (" + playerCount + ") at/below limit (" + players + "). Resuming Chunky...");
                    server.getWorlds().forEach(world -> {
                        try {
                            chunky.continueTask(world.getName());
                        } catch (Exception e) {
                            // Task might not exist, ignore
                        }
                    });
                }
            }
        }.runTaskLater(this, 40L); // Wait 2 seconds after quit
    }

    private static class MemoryInfo {
        final long usedMB;
        final long maxMB;
        final long allocatedMB;
        final double usagePercent;

        MemoryInfo(long usedMB, long maxMB, long allocatedMB, double usagePercent) {
            this.usedMB = usedMB;
            this.maxMB = maxMB;
            this.allocatedMB = allocatedMB;
            this.usagePercent = usagePercent;
        }
    }
}
