package in.reconrail.auth.security;

import java.util.Currency;

/**
 * Holds the tenant id for the current request thread.
 *
 * DANGER: Tomcat serves requests from a thread pool. A thread that handled a
 * request for tenant 3 will later handle one for tenant 9. If this is not
 * cleared after every request, the second request inherits the first tenant's
 * context — one seller silently reading another's data. The failure is
 * load-dependent and intermittent, so it will not reproduce in testing.
 * Clearing in a finally block is mandatory, not optional.
 */
public final class TenantContext {
    private static final ThreadLocal<Long> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext(){}

    public static void setTenantId(Long tenantId){
        CURRENT_TENANT.set(tenantId);
    }

    public static Long getTenantId(){
        return CURRENT_TENANT.get();
    }

    public static void clear(){
        // remove() detaches the entry from the thread's map.
        // set(null) would leave a lingering entry — a slow leak in a pool.
        CURRENT_TENANT.remove();
    }
}
