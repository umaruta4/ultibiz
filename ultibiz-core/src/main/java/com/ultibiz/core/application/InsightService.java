package com.ultibiz.core.application;

import com.ultibiz.core.ai.AiInsight;
import com.ultibiz.core.ai.AiInsightProvider;
import com.ultibiz.core.infrastructure.AiRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The insight engine: it runs the anomaly rules of the modules and turns the
 * findings into alerts with the evidence, the confidence and a suggested
 * action.
 */
@Service
public class InsightService {

    private final List<AiInsightProvider> providers;
    private final AiRepository ai;

    public InsightService(List<AiInsightProvider> providers, AiRepository ai) {
        this.providers = providers;
        this.ai = ai;
    }

    /** Runs every rule and stores the findings as alerts. */
    public int run(SiteScope scope) {
        int found = 0;
        for (AiInsightProvider provider : providers) {
            for (AiInsight insight : provider.insights(scope)) {
                ai.upsertAlert(scope.organizationId(), insight.ruleCode(), insight.severity(),
                        insight.title(), insight.evidence(), insight.confidence(),
                        insight.suggestedAction(), insight.entity(), insight.entityId());
                found++;
            }
        }
        return found;
    }

    public List<AiRepository.Alert> alerts(SiteScope scope, String status, int limit) {
        return ai.alerts(scope.organizationId(), status, limit);
    }

    public void acknowledge(SiteScope scope, String actor, long id, String status) {
        ai.acknowledgeAlert(scope.organizationId(), id, status, actor);
    }
}
