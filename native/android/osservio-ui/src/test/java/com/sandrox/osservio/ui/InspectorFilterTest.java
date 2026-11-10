package com.sandrox.osservio.ui;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class InspectorFilterTest {
    @Test public void optionsFreezeAtOpenAndKeepEvictedSelectionsRemovable() {
        InspectorFilterSheet.Group empty = new InspectorFilterSheet.Group(InspectorQuery.Field.STATE, "State");
        InspectorFilterSheet.Group hosts = new InspectorFilterSheet.Group(InspectorQuery.Field.HOST, "Host");
        InspectorQuery query = new InspectorQuery(); query.values.put(InspectorQuery.Field.HOST, Collections.singleton("expired.test"));
        List<InspectorFilterSheet.Group> groups = InspectorFilterSheet.available(Arrays.asList(empty, hosts), query);
        assertEquals(1, groups.size()); assertTrue(groups.get(0).options.containsKey("expired.test"));
        assertTrue(hosts.options.isEmpty()); hosts.option("new.test", "new.test");
        assertFalse(groups.get(0).options.containsKey("new.test"));
        assertTrue(InspectorFilterSheet.available(Collections.singletonList(empty), query).isEmpty());
    }
}
