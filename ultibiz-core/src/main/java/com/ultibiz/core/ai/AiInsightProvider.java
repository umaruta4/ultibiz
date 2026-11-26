package com.ultibiz.core.ai;

import com.ultibiz.core.application.SiteScope;
import java.util.List;

/**
 * The anomaly rules of a module. The insight engine runs them on a schedule
 * and turns the findings into alerts with evidence, confidence and a
 * suggested action.
 */
public interface AiInsightProvider {

    /** The module the rules belong to. */
    String module();

    /** Runs the rules and returns the findings. */
    List<AiInsight> insights(SiteScope scope);
}
