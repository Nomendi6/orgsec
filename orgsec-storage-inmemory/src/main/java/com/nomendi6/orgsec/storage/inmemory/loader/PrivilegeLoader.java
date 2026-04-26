package com.nomendi6.orgsec.storage.inmemory.loader;

import java.util.Map;
import org.springframework.stereotype.Service;
import com.nomendi6.orgsec.api.PrivilegeDefinitionProvider;
import com.nomendi6.orgsec.api.PrivilegeRegistry;
import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.model.PrivilegeDef;

/**
 * Service responsible for loading privilege definitions into the registry.
 * Works with string-based privilege identifiers instead of application-specific enums.
 */
@Service
public class PrivilegeLoader {

    private final PrivilegeRegistry privilegeRegistry;

    public PrivilegeLoader(PrivilegeRegistry privilegeRegistry) {
        this.privilegeRegistry = privilegeRegistry;
    }

    /**
     * Initialize privileges from a provider.
     * This method is now called from the application layer, not from within orgsec.
     *
     * @param provider The privilege definition provider
     */
    public void initializePrivileges(PrivilegeDefinitionProvider provider) {
        Map<String, PrivilegeDef> definitions = provider.getPrivilegeDefinitions();
        privilegeRegistry.registerBulk(definitions);
    }

    /**
     * Initialize a single privilege by its identifier.
     *
     * @param identifier The privilege identifier
     * @param definition The privilege definition
     */
    public void initializePrivilege(String identifier, PrivilegeDef definition) {
        privilegeRegistry.registerPrivilege(identifier, definition);
    }

    /**
     * Create privilege definition from a string identifier.
     * This method can be used by applications to create definitions.
     *
     * @param privilegeName String identifier for the privilege
     * @return PrivilegeDef created from the identifier
     */
    public static PrivilegeDef createPrivilegeDefinition(String privilegeName) {
        int nPos = privilegeName.indexOf("_");
        int nPos2 = privilegeName.indexOf("_", nPos + 1);

        if (nPos >= 0 && nPos2 >= 0) {
            String resource = privilegeName.substring(0, nPos);
            String organization = privilegeName.substring(nPos + 1, nPos2);
            String operation = privilegeName.substring(nPos2 + 1);

            PrivilegeDef def = new PrivilegeDef(privilegeName, resource).allowOperation(operationFrom(operation));

            switch (organization) {
                case "ALL":
                    def.allowAll(true);
                    break;
                case "COMP":
                    def.allowOrg(PrivilegeDirection.EXACT, PrivilegeDirection.NONE, false);
                    break;
                case "COMPHD":
                    def.allowOrg(PrivilegeDirection.HIERARCHY_DOWN, PrivilegeDirection.NONE, false);
                    break;
                case "COMPHU":
                    def.allowOrg(PrivilegeDirection.HIERARCHY_UP, PrivilegeDirection.NONE, false);
                    break;
                case "ORG":
                    def.allowOrg(PrivilegeDirection.NONE, PrivilegeDirection.EXACT, false);
                    break;
                case "ORGHD":
                    def.allowOrg(PrivilegeDirection.NONE, PrivilegeDirection.HIERARCHY_DOWN, false);
                    break;
                case "ORGHU":
                    def.allowOrg(PrivilegeDirection.NONE, PrivilegeDirection.HIERARCHY_UP, false);
                    break;
                case "EMP":
                    def.allowOrg(PrivilegeDirection.NONE, PrivilegeDirection.NONE, true);
                    break;
            }

            return def;
        }

        // Fallback for malformed privilege names
        return new PrivilegeDef(privilegeName, "UNKNOWN");
    }

    /**
     * Convert operation string to PrivilegeOperation enum
     *
     * @param operation operation string (R, W, E)
     * @return PrivilegeOperation enum value
     */
    private static PrivilegeOperation operationFrom(String operation) {
        if (operation.equals("R")) {
            return PrivilegeOperation.READ;
        }
        if (operation.equals("W")) {
            return PrivilegeOperation.WRITE;
        }
        if (operation.equals("E")) {
            return PrivilegeOperation.EXECUTE;
        }
        return PrivilegeOperation.NONE;
    }
}
