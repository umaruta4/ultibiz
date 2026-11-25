package com.ultibiz.core.application;

import com.ultibiz.core.domain.Site;
import com.ultibiz.core.infrastructure.SiteRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Use cases around sites. Counts respect the caller's scope.
 */
@Service
public class SiteService {

    private final SiteRepository sites;

    public SiteService(SiteRepository sites) {
        this.sites = sites;
    }

    public long countVisibleTo(SiteScope scope) {
        if (scope.isDenyAll()) {
            return 0;
        }
        return sites.countInScope(scope);
    }

    /** Sites the caller may use as an owner of master data. */
    public List<Site> listVisibleTo(SiteScope scope) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        return sites.listVisible(scope);
    }
}
