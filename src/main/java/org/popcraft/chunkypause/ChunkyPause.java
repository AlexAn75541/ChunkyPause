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
import java.util.List;

public final class ChunkyPause extends JavaPlugin implements Listener {
    private ChunkyAPI chunky;
    private int maxPlayers = 0;
    private long memoryCheckInterval;
    private double memoryThreshold;
    private long resumeDelay;
    private boolean isPausedByMemory = false;
    private boolean isPausedByPlayers = false;
    private boolean isForcePaused = false;
    private boolean cleanMemoryOnJoin;
    private boolean memoryMonitoringEnabled = true; // Toggle for memory monitoring
    private long lastMemoryLogTime = 0;
    private long lastGCTime = 0;
    private static final long GC_COOLDOWN = 10000; // 10 seconds minimum between GC calls
    
    // JVM Detection
    private boolean isFixedHeapSize = false; // true when -Xmx = -Xms
    private String jvmName = "Unknown";
    private String jvmVersion = "Unknown";
    private String gcType = "Unknown";

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
            getServer().getPluginManager().registerEvents(this, this);
            
            // Start memory monitoring
            startMemoryMonitor();
            
            // Apply force pause if enabled in config
            if (isForcePaused) {
                getLogger().info("§eApplying force pause from config...");
                Bukkit.getServer().getWorlds().forEach(world -> {
                    try {
                        chunky.pauseTask(world.getName());
                    } catch (Exception e) {
                        // Task might not be running, ignore
                    }
                });
                getLogger().info("§eChunky is force paused - use /chunkypause forcepause to resume");
            }
            
            getLogger().info("ChunkyPause enabled with adaptive memory monitoring");
            getLogger().info("Max players allowed during generation: " + maxPlayers);
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
        jvmVersion = System.getProperty("java.vm.version", "Unknown");
        
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        
        // Detect if heap size is fixed (-Xmx = -Xms)
        // When fixed, totalMemory will be very close to maxMemory at startup
        double heapRatio = (double) totalMemory / maxMemory;
        isFixedHeapSize = heapRatio > 0.95; // Within 5% means fixed heap
        
        // Detect GC type from JVM arguments
        gcType = detectGarbageCollector();
        
        getLogger().info("===========================================");
        getLogger().info("JVM Configuration:");
        getLogger().info("  Name: " + jvmName);
        getLogger().info("  Version: " + jvmVersion);
        getLogger().info("  Max Memory: " + (maxMemory / (1024 * 1024)) + "MB");
        getLogger().info("  Initial Memory: " + (totalMemory / (1024 * 1024)) + "MB");
        
        if (isFixedHeapSize) {
            getLogger().info("  §eHeap Mode: FIXED (-Xmx ≈ -Xms)");
            getLogger().info("  §eNote: Allocated memory will stay at 100%");
            getLogger().info("  §ePlugin will focus on USED memory only");
        } else {
            getLogger().info("  §aHeap Mode: DYNAMIC");
            getLogger().info("  §aJVM can release memory back to OS");
        }
        
        getLogger().info("  Garbage Collector: " + gcType);
        
        if (gcType.contains("ZGC") || gcType.contains("Shenandoah")) {
            getLogger().info("  §aLow-pause GC detected - Optimized strategy");
        } else if (gcType.contains("G1")) {
            getLogger().info("  §aG1GC detected - Balanced strategy");
        } else {
            getLogger().info("  §7Standard GC - Conservative strategy");
        }
        
        getLogger().info("===========================================");
    }

    private String detectGarbageCollector() {
        try {
            List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
            
            for (String arg : jvmArgs) {
                if (arg.contains("UseZGC")) return "ZGC";
                if (arg.contains("UseShenandoahGC")) return "Shenandoah";
                if (arg.contains("UseG1GC")) return "G1GC";
                if (arg.contains("UseParallelGC")) return "Parallel GC";
                if (arg.contains("UseConcMarkSweepGC")) return "CMS";
                if (arg.contains("UseSerialGC")) return "Serial GC";
            }
            
            // Default GC detection via MXBean
            for (java.lang.management.GarbageCollectorMXBean gc : 
                 java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
                String name = gc.getName().toLowerCase();
                if (name.contains("zgc")) return "ZGC";
                if (name.contains("shenandoah")) return "Shenandoah";
                if (name.contains("g1")) return "G1GC";
                if (name.contains("parallel")) return "Parallel GC";
            }
            
            return "Default (Platform)";
        } catch (Exception e) {
            return "Unknown";
        }
    }

    public void loadConfiguration() {
        maxPlayers = getConfig().getInt("max-players", 0);
        memoryThreshold = getConfig().getDouble("memory-threshold", 0.85);
        memoryCheckInterval = getConfig().getLong("check-interval", 100L);
        resumeDelay = getConfig().getLong("resume-delay", 100L);
        cleanMemoryOnJoin = getConfig().getBoolean("clean-memory-on-join", true);
        isForcePaused = getConfig().getBoolean("force-paused", false);
        memoryMonitoringEnabled = getConfig().getBoolean("memory-monitoring-enabled", true);
        
        // Log force pause state on startup if enabled
        if (isForcePaused) {
            getLogger().info("§eForce pause is ENABLED - Chunky will remain paused until disabled");
        }
        
        // Log memory monitoring state
        getLogger().info("Memory monitoring: " + (memoryMonitoringEnabled ? "§aENABLED" : "§cDISABLED"));
    }

    private void startMemoryMonitor() {
        new BukkitRunnable() {
            @Override
            public void run() {
                // Skip monitoring if disabled
                if (!memoryMonitoringEnabled) {
                    return;
                }
                
                MemoryInfo memInfo = getDetailedMemoryInfo();
                
                // Log memory usage periodically (every 60 seconds)
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastMemoryLogTime >= 60000) {
                    double allocatedPercent = ((double) memInfo.allocatedMB / memInfo.maxMB) * 100;
                    getLogger().info(String.format("Memory: %.1f%% used (%dMB / %dMB allocated / %dMB max) | Allocated: %.1f%%", 
                        memInfo.usagePercent * 100, 
                        memInfo.usedMB, 
                        memInfo.allocatedMB,
                        memInfo.maxMB,
                        allocatedPercent));
                    
                    // Warn if allocated memory is getting too high
                    if (allocatedPercent > 90) {
                        getLogger().warning("§eAllocated memory very high (" + String.format("%.1f%%", allocatedPercent) + 
                                          ") - May cause lag and high MSPT");
                    }
                    
                    lastMemoryLogTime = currentTime;
                }
                
                // Check if memory threshold exceeded (only check USED memory, not allocated)
                if (memInfo.usagePercent > memoryThreshold && !isPausedByMemory) {
                    handleHighMemory(memInfo);
                }
            }
        }.runTaskTimer(this, 20L, memoryCheckInterval);
    }

    private void handleHighMemory(MemoryInfo memInfo) {
        if (chunky == null || isPausedByMemory) return;
        
        isPausedByMemory = true;
        getLogger().warning(String.format(
            "Memory usage critical (%.1f%%)! Pausing Chunky generation and cleaning memory...", 
            memInfo.usagePercent * 100));
        
        // Pause all Chunky tasks
        int pausedCount = 0;
        for (org.bukkit.World world : Bukkit.getServer().getWorlds()) {
            try {
                chunky.pauseTask(world.getName());
                pausedCount++;
            } catch (Exception e) {
                // Task might not be running, ignore
            }
        }
        
        if (pausedCount == 0) {
            // No tasks were actually running, reset the flag
            getLogger().info("No active Chunky tasks found - nothing to pause");
            isPausedByMemory = false;
            return;
        }
        
        getLogger().info("Paused " + pausedCount + " Chunky task(s)");
        
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
        
        getLogger().info("§6=== Starting Memory Cleanup (" + reason + ") ===");
        getLogger().info("§7Before: Used=" + beforeGC.usedMB + "MB, Allocated=" + beforeGC.allocatedMB + 
                        "MB, Max=" + beforeGC.maxMB + "MB (" + String.format("%.1f%%", beforeGC.usagePercent * 100) + ")");
        
        // Determine GC strategy based on detected GC type and heap configuration
        if (isFixedHeapSize) {
            getLogger().info("§7Strategy: Fixed heap - Single full GC pass");
            // For fixed heap, single GC is sufficient since memory won't be released anyway
            System.gc();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else if (gcType.contains("ZGC") || gcType.contains("Shenandoah")) {
            getLogger().info("§7Strategy: Low-pause GC - Minimal intervention");
            // These GCs handle memory automatically, just hint
            System.gc();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else if (gcType.contains("G1")) {
            getLogger().info("§7Strategy: G1GC - Dual-pass cleanup");
            // G1GC benefits from two passes
            System.gc();
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.gc();
        } else {
            getLogger().info("§7Strategy: Standard GC - Multi-pass aggressive");
            // Standard GC: thorough multi-pass cleanup
            for (int i = 0; i < 3; i++) {
                System.gc();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        // Try to release memory back to OS (only works if not fixed heap)
        if (!isFixedHeapSize) {
            getLogger().info("§7Attempting to release memory back to OS...");
            
            try {
                Runtime runtime = Runtime.getRuntime();
                long before = runtime.totalMemory();
                
                System.gc();
                Thread.sleep(500);
                
                long after = runtime.totalMemory();
                long released = (before - after) / (1024 * 1024);
                
                if (released > 0) {
                    getLogger().info("§aSuccessfully released " + released + "MB back to OS");
                } else {
                    getLogger().info("§7JVM holding allocated memory (expected with -Xms near -Xmx)");
                }
            } catch (Exception e) {
                // Ignore
            }
        }
        
        // Phase 3: Wait and report results
        new BukkitRunnable() {
            @Override
            public void run() {
                MemoryInfo afterGC = getDetailedMemoryInfo();
                long freedUsedMB = beforeGC.usedMB - afterGC.usedMB;
                long freedAllocatedMB = beforeGC.allocatedMB - afterGC.allocatedMB;
                
                getLogger().info("§6=== Memory Cleanup Complete ===");
                getLogger().info("§7After: Used=" + afterGC.usedMB + "MB, Allocated=" + afterGC.allocatedMB + 
                               "MB, Max=" + afterGC.maxMB + "MB (" + String.format("%.1f%%", afterGC.usagePercent * 100) + ")");
                
                if (freedUsedMB > 0) {
                    getLogger().info("§aFreed: " + freedUsedMB + "MB used memory");
                    getLogger().info("§aUsage: " + String.format("%.1f%%", beforeGC.usagePercent * 100) + 
                                   " -> " + String.format("%.1f%%", afterGC.usagePercent * 100));
                }
                
                if (freedAllocatedMB > 0) {
                    getLogger().info("§aReleased: " + freedAllocatedMB + "MB allocated memory");
                } else if (!isFixedHeapSize && afterGC.allocatedMB > afterGC.maxMB * 0.90) {
                    getLogger().info("§7Allocated memory remains high (JVM memory management)");
                }
                
                if (freedUsedMB <= 0 && freedAllocatedMB <= 0) {
                    getLogger().info("§7No significant memory freed (memory may already be optimal)");
                }
            }
        }.runTaskLater(this, 40L); // Wait 2 seconds for full GC cycle
    }

    private void scheduleMemoryRecoveryCheck() {
        new BukkitRunnable() {
            int attempts = 0;
            final int maxAttempts = 6; // Try for up to 30 seconds (6 attempts * 5 seconds)
            
            @Override
            public void run() {
                MemoryInfo memInfo = getDetailedMemoryInfo();
                attempts++;
                
                // Add 5% buffer below threshold to prevent rapid pause/resume cycles
                if (memInfo.usagePercent < memoryThreshold - 0.05) {
                    isPausedByMemory = false;
                    
                    // Check if we can resume based on player count and force pause
                    int currentPlayers = Bukkit.getOnlinePlayers().size();
                    if (currentPlayers <= maxPlayers && !isPausedByPlayers && !isForcePaused) {
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
                    } else {
                        // Build reason string
                        StringBuilder reason = new StringBuilder();
                        if (currentPlayers > maxPlayers) {
                            reason.append("players online (").append(currentPlayers).append("/").append(maxPlayers).append(")");
                        }
                        if (isForcePaused) {
                            if (reason.length() > 0) reason.append(" and ");
                            reason.append("force paused");
                        }
                        if (isPausedByPlayers) {
                            if (reason.length() > 0) reason.append(" and ");
                            reason.append("paused by player count");
                        }
                        
                        if (reason.length() > 0) {
                            getLogger().info(String.format(
                                "Memory recovered (%.1f%%), but %s. Chunky remains paused.", 
                                memInfo.usagePercent * 100, reason.toString()));
                        } else {
                            // No blocking reason - just resume
                            getLogger().info(String.format(
                                "Memory recovered (%.1f%%). Resuming Chunky generation...", 
                                memInfo.usagePercent * 100));
                            
                            Bukkit.getServer().getWorlds().forEach(world -> {
                                try {
                                    chunky.continueTask(world.getName());
                                } catch (Exception e) {
                                    // Task might not exist, ignore
                                }
                            });
                        }
                    }
                    
                    cancel();
                } else if (attempts >= maxAttempts) {
                    getLogger().warning(String.format(
                        "Memory still high after %d seconds (%.1f%%). Chunky remains paused.", 
                        (maxAttempts * resumeDelay / 20),
                        memInfo.usagePercent * 100));
                    getLogger().warning("Will retry next time Chunky attempts to run or memory drops naturally.");
                    cancel();
                } else {
                    // Only log every other attempt to reduce spam
                    if (attempts % 2 == 0) {
                        getLogger().info(String.format(
                            "Waiting for memory to recover... (%.1f%% used, attempt %d/%d)", 
                            memInfo.usagePercent * 100, attempts, maxAttempts));
                    }
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
            int currentPlayers = Bukkit.getOnlinePlayers().size();
            sender.sendMessage("§6═══════════════════════════════════");
            sender.sendMessage("§6       ChunkyPause Status");
            sender.sendMessage("§6═══════════════════════════════════");
            sender.sendMessage("§7Max players: §e" + maxPlayers);
            sender.sendMessage("§7Current players: §e" + currentPlayers + 
                (currentPlayers > maxPlayers ? " §c(OVER LIMIT)" : " §a(OK)"));
            sender.sendMessage("");
            sender.sendMessage("§7Memory usage: §e" + String.format("%.1f%%", memInfo.usagePercent * 100));
            sender.sendMessage("§7Memory: §e" + memInfo.usedMB + "MB §7/ §e" + memInfo.maxMB + "MB");
            sender.sendMessage("§7Allocated: §e" + memInfo.allocatedMB + "MB");
            sender.sendMessage("");
            sender.sendMessage("§7JVM: §e" + jvmName);
            sender.sendMessage("§7Version: §e" + jvmVersion);
            sender.sendMessage("§7GC Type: §e" + gcType);
            
            if (isFixedHeapSize) {
                sender.sendMessage("§7Heap: §eFixed (-Xmx ≈ -Xms)");
            } else {
                sender.sendMessage("§7Heap: §aDynamic");
            }
            
            sender.sendMessage("");
            sender.sendMessage("§7Paused by memory: §e" + isPausedByMemory);
            sender.sendMessage("§7Paused by players: §e" + isPausedByPlayers);
            sender.sendMessage("§7Force paused: §e" + isForcePaused);
            sender.sendMessage("§7Clean on join: §e" + cleanMemoryOnJoin);
            sender.sendMessage("§7Memory monitoring: " + (memoryMonitoringEnabled ? "§aENABLED" : "§cDISABLED"));
            sender.sendMessage("§6═══════════════════════════════════");
            sender.sendMessage("§7Commands:");
            sender.sendMessage("§e  /chunkypause <number> §7- Set max players");
            sender.sendMessage("§e  /chunkypause reload §7- Reload config");
            sender.sendMessage("§e  /chunkypause gc §7- Force GC");
            sender.sendMessage("§e  /chunkypause forcepause §7- Toggle force pause");
            sender.sendMessage("§e  /chunkypause togglememory §7- Toggle memory monitoring");
            return true;
        }
        
        // Handle reload command
        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadConfiguration();
            sender.sendMessage("§aConfiguration reloaded!");
            sender.sendMessage("§7Max players: §e" + maxPlayers);
            return true;
        }
        
        // Handle gc command
        if (args[0].equalsIgnoreCase("gc")) {
            sender.sendMessage("§6Forcing garbage collection...");
            lastGCTime = 0; // Reset cooldown
            performOptimizedGC("manual command");
            return true;
        }
        
        // Handle forcepause command
        if (args[0].equalsIgnoreCase("forcepause")) {
            isForcePaused = !isForcePaused;
            
            // Save the force pause state to config
            getConfig().set("force-paused", isForcePaused);
            saveConfig();
            
            if (isForcePaused) {
                // Pause Chunky
                sender.sendMessage("§6Force pausing Chunky generation...");
                Bukkit.getServer().getWorlds().forEach(world -> {
                    try {
                        chunky.pauseTask(world.getName());
                    } catch (Exception e) {
                        // Task might not be running, ignore
                    }
                });
                sender.sendMessage("§aChunky is now force paused. It will not resume automatically.");
                sender.sendMessage("§7Use §e/chunkypause forcepause §7again to allow automatic resuming.");
                sender.sendMessage("§7This state will persist across server restarts.");
            } else {
                // Allow Chunky to resume if conditions allow
                sender.sendMessage("§aForce pause disabled. Chunky can now resume automatically.");
                sender.sendMessage("§7This state has been saved to config.");
                
                // Check if we should resume immediately
                int currentPlayers = Bukkit.getOnlinePlayers().size();
                MemoryInfo memInfo = getDetailedMemoryInfo();
                
                if (currentPlayers <= maxPlayers && memInfo.usagePercent < memoryThreshold) {
                    sender.sendMessage("§aConditions met - resuming Chunky generation...");
                    Bukkit.getServer().getWorlds().forEach(world -> {
                        try {
                            chunky.continueTask(world.getName());
                        } catch (Exception e) {
                            // Task might not exist, ignore
                        }
                    });
                } else {
                    sender.sendMessage("§7Chunky will resume when conditions are met:");
                    if (currentPlayers > maxPlayers) {
                        sender.sendMessage("§7  - Players: §e" + currentPlayers + "/" + maxPlayers + " §c(too many)");
                    }
                    if (memInfo.usagePercent >= memoryThreshold) {
                        sender.sendMessage("§7  - Memory: §e" + String.format("%.1f%%", memInfo.usagePercent * 100) + " §c(too high)");
                    }
                }
            }
            
            return true;
        }
        
        // Handle togglememory command
        if (args[0].equalsIgnoreCase("togglememory")) {
            memoryMonitoringEnabled = !memoryMonitoringEnabled;
            
            // Save the state to config
            getConfig().set("memory-monitoring-enabled", memoryMonitoringEnabled);
            saveConfig();
            
            if (memoryMonitoringEnabled) {
                sender.sendMessage("§aMemory monitoring §aENABLED");
                sender.sendMessage("§7The plugin will now:");
                sender.sendMessage("§7  - Monitor memory usage continuously");
                sender.sendMessage("§7  - Auto-pause Chunky when memory threshold is exceeded");
                sender.sendMessage("§7  - Perform GC when needed");
                sender.sendMessage("§7  - Clean memory on player join (if clean-memory-on-join is enabled)");
                sender.sendMessage("§7  - Use adaptive strategies for your GC type (§e" + gcType + "§7)");
                
                if (isFixedHeapSize) {
                    sender.sendMessage("§7  - Monitor §eUSED§7 memory only (fixed heap detected)");
                }
                
                // If re-enabling and memory was paused, check if we should resume
                if (isPausedByMemory) {
                    MemoryInfo memInfo = getDetailedMemoryInfo();
                    if (memInfo.usagePercent < (memoryThreshold - 0.05)) { // 5% buffer
                        isPausedByMemory = false;
                        sender.sendMessage("§aMemory is acceptable - resuming tasks");
                        if (!isPausedByPlayers && !isForcePaused) {
                            Bukkit.getServer().getWorlds().forEach(world -> {
                                try {
                                    chunky.continueTask(world.getName());
                                } catch (Exception e) {
                                    // Ignore
                                }
                            });
                        }
                    }
                }
            } else {
                sender.sendMessage("§cMemory monitoring §cDISABLED");
                sender.sendMessage("§7The plugin will now:");
                sender.sendMessage("§7  - Only manage pause/resume based on player count");
                sender.sendMessage("§7  - Not automatically pause for high memory");
                sender.sendMessage("§7  - Not clean memory on player join");
                sender.sendMessage("§7  - Manual GC via §e/chunkypause gc §7still available");
                
                // If disabling and only paused by memory (not by players or force), resume
                if (isPausedByMemory && !isPausedByPlayers && !isForcePaused) {
                    isPausedByMemory = false;
                    sender.sendMessage("§eMemory pause cleared - resuming tasks");
                    Bukkit.getServer().getWorlds().forEach(world -> {
                        try {
                            chunky.continueTask(world.getName());
                        } catch (Exception e) {
                            // Ignore
                        }
                    });
                }
            }
            
            sender.sendMessage("§7This setting has been saved to config.yml");
            
            return true;
        }
        
        // Handle setting max players
        try {
            final int newMaxPlayers = Integer.parseInt(args[0]);
            if (newMaxPlayers < 0) {
                sender.sendMessage("§cError: Player count must be 0 or greater");
                return false;
            }
            
            this.maxPlayers = newMaxPlayers;
            getConfig().set("max-players", newMaxPlayers);
            saveConfig();
            sender.sendMessage("§aMax players changed to: §e" + newMaxPlayers);
            
            // Check if we need to pause/resume immediately
            int currentPlayers = Bukkit.getOnlinePlayers().size();
            if (currentPlayers > maxPlayers && !isPausedByPlayers) {
                isPausedByPlayers = true;
                Bukkit.getServer().getWorlds().forEach(world -> {
                    try {
                        chunky.pauseTask(world.getName());
                    } catch (Exception e) {
                        // Ignore
                    }
                });
                sender.sendMessage("§6Chunky paused (current players: " + currentPlayers + ")");
            } else if (currentPlayers <= maxPlayers && isPausedByPlayers && !isPausedByMemory && !isForcePaused) {
                isPausedByPlayers = false;
                Bukkit.getServer().getWorlds().forEach(world -> {
                    try {
                        chunky.continueTask(world.getName());
                    } catch (Exception e) {
                        // Ignore
                    }
                });
                sender.sendMessage("§aChunky resumed (current players: " + currentPlayers + ")");
            } else if (isForcePaused) {
                sender.sendMessage("§7Chunky is force paused. Use §e/chunkypause forcepause §7to allow resuming.");
            }
            
            return true;
        } catch (NumberFormatException e) {
            sender.sendMessage("§cError: Please provide a valid number");
            sender.sendMessage("§7Use /chunkypause <number> to set max players");
            return false;
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("reload", "gc", "forcepause", "0", "1", "2", "5", "10");
        }
        return List.of();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(final PlayerJoinEvent event) {
        final Server server = event.getPlayer().getServer();
        final int playerCount = server.getOnlinePlayers().size();
        
        // Check if we should pause Chunky based on player count
        if (playerCount > maxPlayers && chunky != null && !isPausedByPlayers) {
            isPausedByPlayers = true;
            getLogger().info("Player " + event.getPlayer().getName() + " joined. Player count (" + 
                           playerCount + ") exceeded limit (" + maxPlayers + "). Pausing Chunky...");
            server.getWorlds().forEach(world -> {
                try {
                    chunky.pauseTask(world.getName());
                } catch (Exception e) {
                    // Task might not be running, ignore
                }
            });
        }
        
        // Clean memory when player joins (if enabled and memory monitoring is enabled)
        if (cleanMemoryOnJoin && memoryMonitoringEnabled) {
            getLogger().info("Player joined. Cleaning memory...");
            // Delay slightly to not block the join process
            new BukkitRunnable() {
                @Override
                public void run() {
                    cleanMemory();
                }
            }.runTaskLater(this, 20L); // Wait 1 second after join
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
                
                // Resume Chunky if player count is at or below threshold and not force paused
                if (playerCount <= maxPlayers && chunky != null && isPausedByPlayers && !isPausedByMemory && !isForcePaused) {
                    isPausedByPlayers = false;
                    getLogger().info("Player count (" + playerCount + ") at/below limit (" + maxPlayers + 
                                   "). Resuming Chunky generation...");
                    server.getWorlds().forEach(world -> {
                        try {
                            chunky.continueTask(world.getName());
                        } catch (Exception e) {
                            // Task might not exist, ignore
                        }
                    });
                } else if (playerCount > maxPlayers) {
                    getLogger().info("Players still online (" + playerCount + "). Keeping Chunky paused.");
                } else if (isForcePaused) {
                    getLogger().info("Player count acceptable, but Chunky is force paused. Use /chunkypause forcepause to resume.");
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
    
    // Public getters for command handler
    public int getMaxPlayers() {
        return maxPlayers;
    }
    
    public void setMaxPlayers(int maxPlayers) {
        this.maxPlayers = maxPlayers;
    }
    
    public double getMemoryThreshold() {
        return memoryThreshold;
    }
    
    public boolean isPausedByMemory() {
        return isPausedByMemory;
    }
    
    public boolean isPausedByPlayers() {
        return isPausedByPlayers;
    }
    
    public boolean isForcePaused() {
        return isForcePaused;
    }
    
    public void setForcePaused(boolean forcePaused) {
        this.isForcePaused = forcePaused;
    }
    
    public boolean isCleanMemoryOnJoin() {
        return cleanMemoryOnJoin;
    }
    
    public boolean isFixedHeapSize() {
        return isFixedHeapSize;
    }
    
    public String getJvmName() {
        return jvmName;
    }
    
    public String getJvmVersion() {
        return jvmVersion;
    }
    
    public String getGcType() {
        return gcType;
    }
    
    public void resetGCCooldown() {
        lastGCTime = 0;
    }
    
    public boolean isMemoryMonitoringEnabled() {
        return memoryMonitoringEnabled;
    }
    
    public void setMemoryMonitoringEnabled(boolean enabled) {
        this.memoryMonitoringEnabled = enabled;
        getConfig().set("memory-monitoring-enabled", enabled);
        saveConfig();
        
        // If re-enabling and memory was paused, check if we should resume
        if (enabled && isPausedByMemory) {
            MemoryInfo memInfo = getDetailedMemoryInfo();
            if (memInfo.usagePercent < (memoryThreshold - 0.05)) { // 5% buffer
                isPausedByMemory = false;
                getLogger().info("§aMemory monitoring re-enabled and memory is acceptable - resuming tasks");
                if (!isPausedByPlayers && !isForcePaused) {
                    Bukkit.getServer().getWorlds().forEach(world -> {
                        try {
                            chunky.continueTask(world.getName());
                        } catch (Exception e) {
                            // Ignore
                        }
                    });
                }
            }
        }
        
        // If disabling and only paused by memory (not by players or force), resume
        if (!enabled && isPausedByMemory && !isPausedByPlayers && !isForcePaused) {
            isPausedByMemory = false;
            getLogger().info("§eMemory monitoring disabled - resuming tasks");
            Bukkit.getServer().getWorlds().forEach(world -> {
                try {
                    chunky.continueTask(world.getName());
                } catch (Exception e) {
                    // Ignore
                }
            });
        }
    }
    
    public void checkPlayerThreshold(CommandSender sender) {
        int currentPlayers = Bukkit.getOnlinePlayers().size();
        
        if (currentPlayers > maxPlayers && !isPausedByPlayers) {
            isPausedByPlayers = true;
            Bukkit.getServer().getWorlds().forEach(world -> {
                try {
                    chunky.pauseTask(world.getName());
                } catch (Exception e) {
                    // Ignore
                }
            });
            if (sender != null) {
                sender.sendMessage(org.bukkit.ChatColor.GOLD + "Chunky paused (current players: " + currentPlayers + ")");
            }
        } else if (currentPlayers <= maxPlayers && isPausedByPlayers && !isPausedByMemory && !isForcePaused) {
            isPausedByPlayers = false;
            Bukkit.getServer().getWorlds().forEach(world -> {
                try {
                    chunky.continueTask(world.getName());
                } catch (Exception e) {
                    // Ignore
                }
            });
            if (sender != null) {
                sender.sendMessage(org.bukkit.ChatColor.GREEN + "Chunky resumed (current players: " + currentPlayers + ")");
            }
        } else if (isForcePaused && sender != null) {
            sender.sendMessage(org.bukkit.ChatColor.GRAY + "Chunky is force paused. Use " + org.bukkit.ChatColor.YELLOW + "/chunkypause forcepause " + org.bukkit.ChatColor.GRAY + "to allow resuming.");
        }
    }
}
