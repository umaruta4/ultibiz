package com.ultibiz.core.application;

import com.ultibiz.core.infrastructure.OrganizationRepository;
import org.springframework.stereotype.Service;

/**
 * Use cases around the organization (tenant) root.
 */
@Service
public class OrganizationService {

    private final OrganizationRepository organizations;

    public OrganizationService(OrganizationRepository organizations) {
        this.organizations = organizations;
    }

    public long count() {
        return organizations.count();
    }
}
