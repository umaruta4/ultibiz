package com.ultibiz.core.application;

import com.ultibiz.core.infrastructure.SiteRepository;
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
}
