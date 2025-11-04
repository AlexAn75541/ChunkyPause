package org.popcraft.chunkypause.memory;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.management.ManagementFactory;
import java.util.List;

import static org.popcraft.chunkypause.util.ColorUtil.*;

/**
 * Handles garbage collection and memory management
 */
public class MemoryManager {
    
    private final JavaPlugin plugin;
    private final String gcType;
    private final boolean isFixedHeapSize;
    private long lastGCTime = 0;
    private static final long GC_COOLDOWN = 10000; // 10 seconds
    
    public MemoryManager(JavaPlugin plugin, String gcType, boolean isFixedHeapSize) {
        this.plugin = plugin;
        this.gcType = gcType;
        this.isFixedHeapSize = isFixedHeapSize;
    }
    
    /**
     * Get current memory information
     */
    public MemoryInfo getMemoryInfo() {
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
    
    /**
     * Perform optimized garbage collection
     */
    public void performGC(String reason) {
        long currentTime = System.currentTimeMillis();
        
        // Check GC cooldown
        if (currentTime - lastGCTime < GC_COOLDOWN) {
            plugin.getLogger().info(info("Skipping GC (cooldown active, last GC: " + 
                ((currentTime - lastGCTime) / 1000) + "s ago)"));
            return;
        }
        
        lastGCTime = currentTime;
        MemoryInfo beforeGC = getMemoryInfo();
        
        plugin.getLogger().info(highlight("=== Starting Memory Cleanup (" + reason + ") ==="));
        plugin.getLogger().info(info("Before: Used=" + beforeGC.getUsedMB() + "MB, Allocated=" + 
            beforeGC.getAllocatedMB() + "MB, Max=" + beforeGC.getMaxMB() + "MB (" + 
            String.format("%.1f%%", beforeGC.getUsagePercent() * 100) + ")"));
        
        // Execute GC strategy
        executeGCStrategy();
        
        // Try to release memory if not fixed heap
        if (!isFixedHeapSize) {
            attemptMemoryRelease();
        }
        
        // Report results after delay
        new BukkitRunnable() {
            @Override
            public void run() {
                reportResults(beforeGC);
            }
        }.runTaskLater(plugin, 40L); // Wait 2 seconds
    }
    
    /**
     * Execute GC strategy based on configuration
     */
    private void executeGCStrategy() {
        try {
            if (isFixedHeapSize) {
                plugin.getLogger().info(info("Strategy: Fixed heap - Single full GC pass"));
                System.gc();
                Thread.sleep(200);
            } else if (gcType.contains("ZGC") || gcType.contains("Shenandoah")) {
                plugin.getLogger().info(info("Strategy: Low-pause GC - Minimal intervention"));
                System.gc();
                Thread.sleep(100);
            } else if (gcType.contains("G1")) {
                plugin.getLogger().info(info("Strategy: G1GC - Dual-pass cleanup"));
                System.gc();
                Thread.sleep(250);
                System.gc();
            } else {
                plugin.getLogger().info(info("Strategy: Standard GC - Multi-pass aggressive"));
                for (int i = 0; i < 3; i++) {
                    System.gc();
                    Thread.sleep(200);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning(warning("GC strategy interrupted"));
        }
    }
    
    /**
     * Attempt to release memory back to OS
     */
    private void attemptMemoryRelease() {
        plugin.getLogger().info(info("Attempting to release memory back to OS..."));
        
        try {
            Runtime runtime = Runtime.getRuntime();
            long before = runtime.totalMemory();
            
            System.gc();
            Thread.sleep(500);
            
            long after = runtime.totalMemory();
            long released = (before - after) / (1024 * 1024);
            
            if (released > 0) {
                plugin.getLogger().info(success("Successfully released " + released + "MB back to OS"));
            } else {
                plugin.getLogger().info(info("JVM holding allocated memory (expected with -Xms near -Xmx)"));
            }
        } catch (Exception e) {
            // Ignore
        }
    }
    
    /**
     * Report GC results
     */
    private void reportResults(MemoryInfo beforeGC) {
        MemoryInfo afterGC = getMemoryInfo();
        long freedUsedMB = beforeGC.getUsedMB() - afterGC.getUsedMB();
        long freedAllocatedMB = beforeGC.getAllocatedMB() - afterGC.getAllocatedMB();
        
        plugin.getLogger().info(highlight("=== Memory Cleanup Complete ==="));
        plugin.getLogger().info(info("After: Used=" + afterGC.getUsedMB() + "MB, Allocated=" + 
            afterGC.getAllocatedMB() + "MB, Max=" + afterGC.getMaxMB() + "MB (" + 
            String.format("%.1f%%", afterGC.getUsagePercent() * 100) + ")"));
        
        if (freedUsedMB > 0) {
            plugin.getLogger().info(success("Freed: " + freedUsedMB + "MB used memory"));
            plugin.getLogger().info(success("Usage: " + String.format("%.1f%%", beforeGC.getUsagePercent() * 100) + 
                " -> " + String.format("%.1f%%", afterGC.getUsagePercent() * 100)));
        }
        
        if (freedAllocatedMB > 0) {
            plugin.getLogger().info(success("Released: " + freedAllocatedMB + "MB allocated memory"));
        } else if (!isFixedHeapSize && afterGC.getAllocatedMB() > afterGC.getMaxMB() * 0.90) {
            plugin.getLogger().info(info("Allocated memory remains high (JVM memory management)"));
        }
        
        if (freedUsedMB <= 0 && freedAllocatedMB <= 0) {
            plugin.getLogger().info(info("No significant memory freed (memory may already be optimal)"));
        }
    }
    
    /**
     * Detect garbage collector type
     */
    public static String detectGarbageCollector() {
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
    
    /**
     * Detect if heap size is fixed (-Xmx = -Xms)
     */
    public static boolean detectFixedHeapSize() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        
        // Within 5% means fixed heap
        double heapRatio = (double) totalMemory / maxMemory;
        return heapRatio > 0.95;
    }
}
