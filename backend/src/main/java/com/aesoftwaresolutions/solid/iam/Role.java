package com.aesoftwaresolutions.solid.iam;

/** Organization roles, from most to least privileged. */
public enum Role {
    owner, admin, accountant, bookkeeper, viewer;

    public boolean canWrite() {
        return this != viewer;
    }

    public boolean canManageMembers() {
        return this == owner || this == admin;
    }
}
