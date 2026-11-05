package com.sandrox.osservio.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RetainedBreadcrumbs {
    public final RetainedRun run;
    public final List<BreadcrumbEvent> events;
    public RetainedBreadcrumbs(RetainedRun run, List<BreadcrumbEvent> events) {
        this.run = run; this.events = Collections.unmodifiableList(new ArrayList<>(events));
    }
}
