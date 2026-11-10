package com.aerosync.model;

/**
 * What a user may do inside the airports assigned to them. The airport
 * assignment (user_airports) decides <em>where</em>; the role decides
 * <em>what</em>.
 */
public enum UserRole {

    /** Views operations and acts on them: disruptions, resolutions, flight status. */
    OPERATIONS_CONTROLLER(true),

    /** Views operations only. */
    OPERATIONS_VIEWER(false);

    private final boolean canOperate;

    UserRole(boolean canOperate) {
        this.canOperate = canOperate;
    }

    public boolean canOperate() {
        return canOperate;
    }
}
