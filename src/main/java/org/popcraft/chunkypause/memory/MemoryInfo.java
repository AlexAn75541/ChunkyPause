package org.popcraft.chunkypause.memory;

/**
 * Immutable container for memory information
 */
public class MemoryInfo {
    private final long usedMB;
    private final long maxMB;
    private final long allocatedMB;
    private final double usagePercent;

    public MemoryInfo(long usedMB, long maxMB, long allocatedMB, double usagePercent) {
        this.usedMB = usedMB;
        this.maxMB = maxMB;
        this.allocatedMB = allocatedMB;
        this.usagePercent = usagePercent;
    }

    public long getUsedMB() {
        return usedMB;
    }

    public long getMaxMB() {
        return maxMB;
    }

    public long getAllocatedMB() {
        return allocatedMB;
    }

    public double getUsagePercent() {
        return usagePercent;
    }
    
    @Override
    public String toString() {
        return String.format("Memory[Used=%dMB, Allocated=%dMB, Max=%dMB, Usage=%.1f%%]", 
            usedMB, allocatedMB, maxMB, usagePercent * 100);
    }
}
