package org.popcraft.chunkypause.util;

import org.bukkit.ChatColor;

/**
 * Utility class for handling colored messages
 * Uses Bukkit ChatColor API for cross-version compatibility
 */
public class ColorUtil {
    
    // Color codes
    public static final String GOLD = ChatColor.GOLD.toString();
    public static final String YELLOW = ChatColor.YELLOW.toString();
    public static final String GREEN = ChatColor.GREEN.toString();
    public static final String RED = ChatColor.RED.toString();
    public static final String GRAY = ChatColor.GRAY.toString();
    public static final String DARK_GRAY = ChatColor.DARK_GRAY.toString();
    public static final String AQUA = ChatColor.AQUA.toString();
    public static final String WHITE = ChatColor.WHITE.toString();
    public static final String RESET = ChatColor.RESET.toString();
    
    /**
     * Colorize a string with color codes
     * @param text Text with & color codes
     * @return Colored text
     */
    public static String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
    
    /**
     * Strip all color codes from a string
     * @param text Colored text
     * @return Plain text
     */
    public static String strip(String text) {
        return ChatColor.stripColor(text);
    }
    
    // Pre-formatted message types
    public static String success(String message) {
        return GREEN + message;
    }
    
    public static String warning(String message) {
        return YELLOW + message;
    }
    
    public static String error(String message) {
        return RED + message;
    }
    
    public static String info(String message) {
        return GRAY + message;
    }
    
    public static String highlight(String message) {
        return GOLD + message;
    }
}
