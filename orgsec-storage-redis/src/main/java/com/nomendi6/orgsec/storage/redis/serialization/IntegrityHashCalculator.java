package com.nomendi6.orgsec.storage.redis.serialization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Calculator for integrity hash using SHA-256 algorithm.
 * <p>
 * Used to detect cache poisoning and data corruption by calculating
 * a cryptographic hash of cached JSON data.
 * </p>
 */
public class IntegrityHashCalculator {

    private static final Logger log = LoggerFactory.getLogger(IntegrityHashCalculator.class);
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String METADATA_FIELD = "_metadata";

    private final ObjectMapper objectMapper;

    /**
     * Constructs a new integrity hash calculator with provided ObjectMapper.
     *
     * @param objectMapper the ObjectMapper for JSON processing
     */
    public IntegrityHashCalculator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Constructs a new integrity hash calculator.
     *
     * @deprecated Use {@link #IntegrityHashCalculator(ObjectMapper)} instead
     */
    @Deprecated
    public IntegrityHashCalculator() {
        this(new ObjectMapper());
    }

    /**
     * Calculates SHA-256 hash of the given JSON string.
     * <p>
     * The JSON is canonicalized before hashing (sorted keys, no whitespace)
     * to ensure consistent hash values.
     * </p>
     *
     * @param json the JSON string to hash
     * @return hexadecimal string representation of the hash
     * @throws IllegalArgumentException if JSON is invalid or hashing fails
     */
    public String calculateHash(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("JSON cannot be null or empty");
        }

        try {
            // Parse and canonicalize JSON
            String canonicalJson = canonicalizeJson(json);

            // Calculate SHA-256 hash
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashBytes = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));

            // Convert to hexadecimal string
            return Hex.encodeHexString(hashBytes);

        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 algorithm not available", e);
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        } catch (Exception e) {
            log.error("Failed to calculate hash for JSON", e);
            throw new IllegalArgumentException("Failed to calculate hash", e);
        }
    }

    /**
     * Verifies that the given JSON matches the expected hash.
     *
     * @param json         the JSON string to verify
     * @param expectedHash the expected hash value
     * @return true if hash matches, false otherwise
     */
    public boolean verifyHash(String json, String expectedHash) {
        if (json == null || expectedHash == null) {
            return false;
        }

        try {
            String actualHash = calculateHash(json);
            return actualHash.equalsIgnoreCase(expectedHash);
        } catch (Exception e) {
            log.warn("Failed to verify hash: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Canonicalizes JSON by:
     * 1. Parsing to tree structure
     * 2. Removing _metadata field (if present)
     * 3. Sorting keys alphabetically (recursive)
     * 4. Compact formatting (no whitespace)
     *
     * @param json the JSON string to canonicalize
     * @return canonicalized JSON string
     * @throws Exception if JSON parsing fails
     */
    private String canonicalizeJson(String json) throws Exception {
        // Parse JSON
        JsonNode rootNode = objectMapper.readTree(json);

        // Remove _metadata field if present
        if (rootNode.isObject()) {
            ObjectNode objectNode = (ObjectNode) rootNode;
            objectNode.remove(METADATA_FIELD);
        }

        // Sort keys recursively
        JsonNode sortedNode = sortKeys(rootNode);

        // Convert to compact string
        return objectMapper.writeValueAsString(sortedNode);
    }

    /**
     * Recursively sorts JSON object keys alphabetically.
     *
     * @param node the JSON node to sort
     * @return sorted JSON node
     */
    private JsonNode sortKeys(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            // Collect field names and sort them alphabetically
            List<String> fieldNames = new ArrayList<>();
            node.fieldNames().forEachRemaining(fieldNames::add);
            Collections.sort(fieldNames);
            // Add fields in sorted order
            for (String fieldName : fieldNames) {
                JsonNode childNode = node.get(fieldName);
                sorted.set(fieldName, sortKeys(childNode));
            }
            return sorted;
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                // Note: Arrays are not sorted, only objects within arrays
                sortKeys(node.get(i));
            }
        }
        return node;
    }
}
