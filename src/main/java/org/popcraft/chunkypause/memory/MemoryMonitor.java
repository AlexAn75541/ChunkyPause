package org.popcraft.chunkypause.memory;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.popcraft.chunky.api.ChunkyAPI;

import static org.popcraft.chunkypause.util.ColorUtil.*;

/**
 * Monitors memory usage and triggers actions when thresholds are exceeded
 */
public class MemoryMonitor {
    
    private final JavaPlugin plugin;
    private final ChunkyAPI chunky;
    private final MemoryManager memoryManager;
    private final double memoryThreshold;
    private final long checkInterval;
    private final long resumeDelay;
    
    private boolean isPausedByMemory = false;
    private long lastMemoryLogTime = 0;
    private static final long MEMORY_LOG_INTERVAL = 60000; // 60 seconds
    
    public MemoryMonitor(JavaPlugin plugin, ChunkyAPI chunky, MemoryManager memoryManager,
                        double memoryThreshold, long checkInterval, long resumeDelay) {
        this.plugin = plugin;
        this.chunky = chunky;
        this.memoryManager = memoryManager;
        this.memoryThreshold = memoryThreshold;
        this.checkInterval = checkInterval;
        this.resumeDelay = resumeDelay;
    }
    
    /**
     * Start monitoring memory
     */
    public void start() {
        new BukkitRunnable() {
            @Override
            public void run() {
                checkMemory();
            }
        }.runTaskTimer(plugin, 20L, checkInterval);
    }
    
    /**
     * Check current memory status
     */
    private void checkMemory() {
        try {
            MemoryInfo memInfo = memoryManager.getMemoryInfo();
            
            // Periodic logging
            if (shouldLogMemory()) {
                logMemoryStatus(memInfo);
            }
            
            // Check for critical memory conditions
            if (memInfo.getUsagePercent() > memoryThreshold && !isPausedByMemory) {
                handleHighMemory(memInfo);
            }
        } catch (Exception e) {
            plugin.getLogger().warning(warning("Error in memory monitor: " + e.getMessage()));
        }
    }
    
    /**
     * Should we log memory status?
     */
    private boolean shouldLogMemory() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastMemoryLogTime >= MEMORY_LOG_INTERVAL) {
            lastMemoryLogTime = currentTime;
            return true;
        }
        return false;
    }
    
    /**
     * Log current memory status
     */
    private void logMemoryStatus(MemoryInfo memInfo) {
        double allocatedPercent = ((double) memInfo.getAllocatedMB() / memInfo.getMaxMB()) * 100;
        
        plugin.getLogger().info(String.format(
            "Memory: %.1f%% used (%dMB / %dMB allocated / %dMB max) | Allocated: %.1f%%",
            memInfo.getUsagePercent() * 100,
            memInfo.getUsedMB(),
            memInfo.getAllocatedMB(),
            memInfo.getMaxMB(),
            allocatedPercent
        ));
    }
    
    /**
     * Handle high memory situation
     */
    private void handleHighMemory(MemoryInfo memInfo) {
        if (chunky == null || isPausedByMemory) return;
        
        isPausedByMemory = true;
        plugin.getLogger().warning(warning(String.format(
            "Memory usage critical (%.1f%%)! Pausing Chunky generation and cleaning memory...", 
            memInfo.getUsagePercent() * 100)));
        
        // Pause all Chunky tasks
        int pausedCount = 0;
        for (org.bukkit.World world : Bukkit.getServer().getWorlds()) {
            try {
                chunky.pauseTask(world.getName());
                pausedCount++;
            } catch (Exception e) {
                // Task might not be running
            }
        }
        
        if (pausedCount == 0) {
            plugin.getLogger().info(info("No active Chunky tasks found - nothing to pause"));
            isPausedByMemory = false;
            return;
        }
        
        plugin.getLogger().info(info("Paused " + pausedCount + " Chunky task(s)"));
        
        // Perform GC
        memoryManager.performGC("high memory");
        
        // Schedule recovery check
        scheduleRecoveryCheck();
    }
    
    /**
     * Schedule memory recovery check
     */
    private void scheduleRecoveryCheck() {
        new BukkitRunnable() {
            int attempts = 0;
            final int maxAttempts = 6;
            
            @Override
            public void run() {
                MemoryInfo memInfo = memoryManager.getMemoryInfo();
                attempts++;
                
                if (memInfo.getUsagePercent() < memoryThreshold - 0.05) {
                    isPausedByMemory = false;
                    plugin.getLogger().info(success(String.format(
                        "Memory recovered (%.1f%%). Memory monitor cleared.", 
                        memInfo.getUsagePercent() * 100)));
                    cancel();
                } else if (attempts >= maxAttempts) {
                    plugin.getLogger().warning(warning(String.format(
                        "Memory still high after %d seconds (%.1f%%).", 
                        (maxAttempts * resumeDelay / 20),
                        memInfo.getUsagePercent() * 100)));
                    plugin.getLogger().warning(warning("Will retry when memory drops naturally."));
                    cancel();
                } else if (attempts % 2 == 0) {
                    plugin.getLogger().info(info(String.format(
                        "Waiting for memory to recover... (%.1f%% used, attempt %d/%d)", 
                        memInfo.getUsagePercent() * 100, attempts, maxAttempts)));
                }
            }
        }.runTaskTimer(plugin, resumeDelay, resumeDelay);
    }
    
    /**
     * Check if paused by memory
     */
    public boolean isPausedByMemory() {
        return isPausedByMemory;
    }
    
    /**
     * Reset pause state
     */
    public void resetPauseState() {
        isPausedByMemory = false;
    }
}
