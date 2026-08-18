package com.nomendi6.orgsec.constants;

/**
 * How {@code HIERARCHY_UP} is decided on GET and LIST.
 *
 * <p>{@link #PATH} is the default and the 1.0 contract: the record path must be a prefix of the
 * principal path. {@link #IDS} uses the principal's inclusive lineage and the record's owner id.
 */
public enum HierarchyUpStrategy {
    PATH,
    IDS
}
