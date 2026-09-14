package com.openrealm.account.dto;

import java.util.List;

public enum AccountProvision {
    OPENREALM_PLAYER,
    OPENREALM_MODERATOR,
    OPENREALM_EDITOR,
    OPENREALM_ADMIN,
    OPENREALM_SYS_ADMIN,
    OPENREALM_DEMO;

    public boolean satisfies(AccountProvision required) {
        if (this == required) return true;
        // DEMO is a guest flag, not a tier: ADMIN/SYS_ADMIN must NOT satisfy it
        // or admins inherit the guest 1-character/1-chest cap. DEMO matches only DEMO.
        if (required == OPENREALM_DEMO) return false;
        if (this == OPENREALM_SYS_ADMIN) return true;
        if (this == OPENREALM_ADMIN) return required != OPENREALM_SYS_ADMIN;
        if (required == OPENREALM_PLAYER) return true;
        return false;
    }

    /** Passes if ANY held provision satisfies ANY required provision. */
    public static boolean checkAccess(List<AccountProvision> accountProvisions, AccountProvision[] required) {
        if (accountProvisions == null || accountProvisions.isEmpty()) return false;
        for (AccountProvision held : accountProvisions) {
            for (AccountProvision req : required) {
                if (held.satisfies(req)) return true;
            }
        }
        return false;
    }
}
