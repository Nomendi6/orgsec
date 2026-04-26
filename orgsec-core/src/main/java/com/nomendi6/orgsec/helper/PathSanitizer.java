package com.nomendi6.orgsec.helper;

import java.util.regex.Pattern;
import com.nomendi6.orgsec.exceptions.OrgsecSecurityException;

/**
 * Utility class for sanitizing and validating organization paths.
 * Paths must follow the format: |pathId1|pathId2|pathId3|
 * where each pathId contains only alphanumeric characters and underscore.
 */
public class PathSanitizer {

    // Pattern for valid path format: starts and ends with |, contains alphanumeric and underscore segments separated by |
    private static final Pattern VALID_PATH_PATTERN = Pattern.compile("^\\|([A-Za-z0-9_]+\\|)*$");

    // Pattern for valid path ID (alphanumeric and underscore for hierarchy)
    private static final Pattern VALID_PATH_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_]+$");

    // Maximum path depth to prevent DoS attacks
    private static final int MAX_PATH_DEPTH = 20;

    // Maximum path ID length (increased to handle longer encoded IDs)
    private static final int MAX_PATH_ID_LENGTH = 30;

    /**
     * Validates and sanitizes an organization path.
     *
     * @param path The path to validate
     * @return The validated path
     * @throws OrgsecSecurityException if the path is invalid
     */
    public static String validatePath(String path) {
        if (path == null || path.isEmpty()) {
            throw new OrgsecSecurityException("Path cannot be null or empty");
        }

        // Check if path matches the expected format
        if (!VALID_PATH_PATTERN.matcher(path).matches()) {
            throw new OrgsecSecurityException("Invalid path format for path: " + path + ". Path must follow pattern: |pathId1|pathId2|");
        }

        // Check path depth
        String[] segments = path.split("\\|");
        if (segments.length - 2 > MAX_PATH_DEPTH) { // -2 because split includes empty strings at start and end
            throw new OrgsecSecurityException("Path depth exceeds maximum allowed depth of " + MAX_PATH_DEPTH);
        }

        // Validate each segment (segments will be: ["", "pathId1", "pathId2", ...])
        for (int i = 1; i < segments.length; i++) {
            String segment = segments[i];
            if (!segment.isEmpty()) { // Skip empty segments
                if (segment.length() > MAX_PATH_ID_LENGTH) {
                    throw new OrgsecSecurityException("Path ID exceeds maximum length of " + MAX_PATH_ID_LENGTH);
                }
                if (!VALID_PATH_ID_PATTERN.matcher(segment).matches()) {
                    throw new OrgsecSecurityException(
                        "Invalid path ID: " + segment + ". Path IDs must contain only alphanumeric characters and underscore."
                    );
                }
            }
        }

        return path;
    }

    /**
     * Validates a single path ID.
     *
     * @param pathId The path ID to validate
     * @return The validated path ID
     * @throws OrgsecSecurityException if the path ID is invalid
     */
    public static String validatePathId(String pathId) {
        if (pathId == null || pathId.isEmpty()) {
            throw new OrgsecSecurityException("Path ID cannot be null or empty");
        }

        if (pathId.length() > MAX_PATH_ID_LENGTH) {
            throw new OrgsecSecurityException("Path ID exceeds maximum length of " + MAX_PATH_ID_LENGTH);
        }

        if (!VALID_PATH_ID_PATTERN.matcher(pathId).matches()) {
            throw new OrgsecSecurityException("Invalid path ID. Path IDs must contain only alphanumeric characters and underscore.");
        }

        return pathId;
    }

    /**
     * Sanitizes a path value, returning null for null/empty inputs.
     * This is a null-safe version of validatePath() for use in DTOs.
     *
     * @param path The path to sanitize
     * @return The sanitized path or null if input was null/empty
     */
    public static String sanitizePath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return path; // Preserve null/empty values
        }
        return validatePath(path);
    }

    /**
     * Sanitizes a path ID value, returning null for null/empty inputs.
     * This is a null-safe version of validatePathId() for use in DTOs.
     *
     * @param pathId The path ID to sanitize
     * @return The sanitized path ID or null if input was null/empty
     */
    public static String sanitizePathId(String pathId) {
        if (pathId == null || pathId.trim().isEmpty()) {
            return pathId; // Preserve null/empty values
        }
        return validatePathId(pathId);
    }

    /**
     * Escapes special characters in a path for safe use in RSQL queries.
     *
     * @param path The path to escape
     * @return The escaped path
     */
    public static String escapeForRsql(String path) {
        if (path == null) {
            return null;
        }

        // First validate the path
        validatePath(path);

        // Escape any RSQL special characters (even though validated paths shouldn't have them)
        // This is defense in depth
        return path
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("=", "\\=")
            .replace("!", "\\!")
            .replace("~", "\\~")
            .replace("<", "\\<")
            .replace(">", "\\>");
    }

    /**
     * Checks if a path contains another path as a parent.
     *
     * @param childPath The child path
     * @param parentPath The parent path to check
     * @return true if childPath contains parentPath as a parent
     */
    public static boolean containsParentPath(String childPath, String parentPath) {
        if (childPath == null || parentPath == null) {
            return false;
        }

        // Validate both paths
        validatePath(childPath);
        validatePath(parentPath);

        return childPath.contains(parentPath);
    }

    /**
     * Builds a path from parent path and path ID.
     *
     * @param parentPath The parent path (can be null for root)
     * @param pathId The path ID to append
     * @return The constructed path
     */
    public static String buildPath(String parentPath, String pathId) {
        // Validate path ID
        validatePathId(pathId);

        if (parentPath == null || parentPath.isEmpty()) {
            return "|" + pathId + "|";
        }

        // Validate parent path
        validatePath(parentPath);

        // Parent path already ends with |, just append pathId and closing |
        if (parentPath.endsWith("|")) {
            return parentPath + pathId + "|";
        } else {
            // This shouldn't happen with valid paths, but handle it anyway
            return parentPath + "|" + pathId + "|";
        }
    }
}
