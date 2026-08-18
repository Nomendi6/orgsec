package com.nomendi6.orgsec.helper;

import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds inclusive root-to-self lineage lists from {@link OrganizationDef#parentId}.
 *
 * <p>Evaluators never call this. Loaders run it once over the full party set after every
 * refresh. A hole, cycle or depth overflow leaves that node's lineage {@code null}.
 */
public final class LineageBuilder {

    public static final int MAX_DEPTH = 20;

    private static final Logger log = LoggerFactory.getLogger(LineageBuilder.class);

    private LineageBuilder() {}

    /**
     * True when {@code lineage} is a usable inclusive chain ending at {@code selfId}.
     */
    public static boolean isUsable(List<Long> lineage, Long selfId) {
        if (lineage == null || selfId == null || selfId <= 0 || lineage.isEmpty() || lineage.size() > MAX_DEPTH) {
            return false;
        }
        Set<Long> seen = new HashSet<>();
        for (Long id : lineage) {
            if (id == null || id <= 0 || !seen.add(id)) {
                return false;
            }
        }
        return selfId.equals(lineage.get(lineage.size() - 1));
    }

    /**
     * Walks every organization in {@code organizations} and writes both lineage lists.
     */
    public static void assignLineages(Map<Long, OrganizationDef> organizations) {
        if (organizations == null || organizations.isEmpty()) {
            return;
        }
        Map<Long, List<Long>> cache = new HashMap<>();
        int rejected = 0;
        for (OrganizationDef organization : organizations.values()) {
            if (organization == null || organization.organizationId == null) {
                continue;
            }
            List<Long> lineage = walk(organization.organizationId, organizations, cache, new HashSet<>());
            organization.orgLineageIds = lineage;
            if (lineage == null) {
                rejected++;
            }
        }
        for (OrganizationDef organization : organizations.values()) {
            if (organization == null) {
                continue;
            }
            if (organization.companyId == null) {
                organization.companyLineageIds = null;
                continue;
            }
            OrganizationDef company = organizations.get(organization.companyId);
            organization.companyLineageIds = company == null ? null : company.orgLineageIds;
        }
        if (rejected > 0) {
            log.warn("Rejected org lineage for {} organization(s) (missing parent, cycle or depth)", rejected);
        }
    }

    /**
     * Copies {@code parentId} and both lineages from the party store onto each person membership.
     */
    public static void copyOntoMemberships(
        Map<Long, PersonDef> persons,
        Map<Long, OrganizationDef> organizations
    ) {
        if (persons == null || organizations == null) {
            return;
        }
        for (PersonDef person : persons.values()) {
            if (person == null || person.organizationsMap == null) {
                continue;
            }
            for (OrganizationDef membership : person.organizationsMap.values()) {
                if (membership == null || membership.organizationId == null) {
                    continue;
                }
                OrganizationDef party = organizations.get(membership.organizationId);
                if (party == null) {
                    membership.orgLineageIds = null;
                    membership.companyLineageIds = null;
                    continue;
                }
                membership.parentId = party.parentId;
                membership.orgLineageIds = copyLineage(party.orgLineageIds);
                membership.companyLineageIds = copyLineage(party.companyLineageIds);
            }
        }
    }

    public static List<Long> copyLineage(List<Long> lineage) {
        return lineage == null ? null : List.copyOf(lineage);
    }

    private static List<Long> walk(
        Long id,
        Map<Long, OrganizationDef> organizations,
        Map<Long, List<Long>> cache,
        Set<Long> visiting
    ) {
        if (cache.containsKey(id)) {
            return cache.get(id);
        }
        if (!visiting.add(id)) {
            cache.put(id, null);
            return null;
        }
        OrganizationDef organization = organizations.get(id);
        if (organization == null
            || organization.organizationId == null
            || organization.organizationId <= 0
            || !organization.organizationId.equals(id)) {
            visiting.remove(id);
            cache.put(id, null);
            return null;
        }
        if (organization.parentId == null) {
            List<Long> root = List.of(organization.organizationId);
            cache.put(id, root);
            visiting.remove(id);
            return root;
        }
        if (organization.parentId <= 0) {
            visiting.remove(id);
            cache.put(id, null);
            return null;
        }
        List<Long> parentLineage = walk(organization.parentId, organizations, cache, visiting);
        visiting.remove(id);
        if (parentLineage == null || parentLineage.size() >= MAX_DEPTH) {
            cache.put(id, null);
            return null;
        }
        List<Long> lineage = new ArrayList<>(parentLineage.size() + 1);
        lineage.addAll(parentLineage);
        lineage.add(organization.organizationId);
        List<Long> frozen = List.copyOf(lineage);
        cache.put(id, frozen);
        return frozen;
    }
}
