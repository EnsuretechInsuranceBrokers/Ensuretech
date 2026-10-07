package com.broker.billing.security;

import com.broker.billing.api.ForbiddenTenantException;

public final class TenantContextHolder {
    private static final ThreadLocal<TenantContext> CURRENT = new ThreadLocal<>();

    private TenantContextHolder() {}

    public static void set(TenantContext ctx) { CURRENT.set(ctx); }

    public static void clear() { CURRENT.remove(); }

    public static TenantContext require() {
        TenantContext ctx = CURRENT.get();
        if (ctx == null) throw new ForbiddenTenantException("No tenant context");
        return ctx;
    }

    /** broker_id for broker-scoped callers; anything else is rejected. */
    public static long requireBrokerId() {
        TenantContext ctx = require();
        if (ctx.brokerId() == null) throw new ForbiddenTenantException("Caller is not broker-scoped");
        return ctx.brokerId();
    }
}
