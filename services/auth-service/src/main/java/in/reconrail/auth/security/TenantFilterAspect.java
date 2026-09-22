package in.reconrail.auth.security;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Session;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Enables the Hibernate tenant filter on the current session before any
 * service method runs.
 *
 * WHY AN ASPECT: the filter must be enabled on an OPEN Hibernate session.
 * Sessions are bound to transactions, so the servlet filter (where the tenant
 * context is set) is too early — no session exists yet. Enabling it inside
 * each repository call would be scattered and forgettable, which is the exact
 * problem this layer exists to solve.
 *
 * LIMITATION: Hibernate filters do NOT apply to entityManager.find() by
 * primary key, nor to native queries. That gap is why ADR-0003 mandates a
 * third layer (PostgreSQL row-level security).
 */
@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
@Order(1)
public class TenantFilterAspect {
    @PersistenceContext
    private EntityManager entityManager;

    private final TenantSessionConfigurer tenantSessionConfigurer;

    @Before("execution(* in.reconrail.auth.service.impl.*.*(..))")
    public void enableTenantFilter(){
        Long tenantId = TenantContext.getTenantId();
        // No tenant in context means an unauthenticated flow — login, register,
        // token refresh. Those legitimately need to query across tenants (to
        // find the tenant by slug in the first place), so we do not filter.
        if(tenantId == null){
            return;
        }
        entityManager.unwrap(Session.class)
                .enableFilter("tenantFilter")
                .setParameter("tenantId",tenantId);

        tenantSessionConfigurer.applyTenant(tenantId);
    }
}
