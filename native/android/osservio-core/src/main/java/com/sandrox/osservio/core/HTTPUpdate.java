package com.sandrox.osservio.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class HTTPUpdate {
    public final HTTPEvent event;
    public final boolean isNew;
    public final List<HTTPBody> bodies;
    HTTPUpdate(HTTPEvent event, boolean isNew) { this(event, isNew, Collections.emptyList()); }
    HTTPUpdate(HTTPEvent event, boolean isNew, List<HTTPBody> bodies) {
        this.event = event; this.isNew = isNew; this.bodies = Collections.unmodifiableList(new ArrayList<>(bodies));
    }
}
