package in.reconrail.auth.security;

import in.reconrail.auth.constants.Constants;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

@Component
public class TenantSessionConfigurer {
    @PersistenceContext
    private EntityManager entityManager;

    public void applyTenant(Long tenantId){
        if(tenantId == null){
            return;
        }
        entityManager
                .createNativeQuery("SELECT set_config('" + Constants.TENANT_SETTING + "', :tenantId, true)")
                .setParameter("tenantId",String.valueOf(tenantId))
                .getSingleResult();
    }
}
