package com.sandrox.osservio.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public final class InspectorGlassBudgetTest {
    @Test public void admissionIncludesMarginsAndReplacesExistingReservation() {
        InspectorGlassBudget budget = new InspectorGlassBudget(); Object bar = new Object();
        assertTrue(budget.reserve(bar, 1080, 180)); assertEquals(1144L * 244, budget.pixels());
        assertTrue(budget.reserve(bar, 1080, 180)); assertEquals(1144L * 244, budget.pixels());
        assertTrue(budget.reserve(bar, 720, 160)); assertEquals(784L * 224, budget.pixels());
        budget.release(bar); budget.release(bar); assertEquals(0, budget.pixels());
    }
    @Test public void rejectionDoesNotDisplaceOtherSurfacesOrOverflow() {
        InspectorGlassBudget budget = new InspectorGlassBudget(); Object first = new Object(), second = new Object();
        assertTrue(budget.reserve(first, 700_000, InspectorGlassBudget.MAX_SESSION_PIXELS - 100_000, 1024, 768)); long previous = budget.pixels();
        assertFalse(budget.reserve(second, 1024, 512)); assertEquals(previous, budget.pixels());
        assertFalse(budget.reserve(first, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertFalse(budget.reserve(first, 0, 100)); assertFalse(budget.reserve(first, -1, 100));
        assertFalse(budget.reserve(first, 4096, 1)); assertEquals(previous, budget.pixels());
        budget.release(first); assertTrue(budget.reserve(second, 1024, 512));
    }
    @Test public void exactSurfaceLimitIsAdmittedButOnePixelBeyondIsNot() {
        InspectorGlassBudget budget = new InspectorGlassBudget(); Object surface = new Object();
        assertTrue(budget.reserve(surface, 1024 - 64, 768 - 64));
        assertEquals(InspectorGlassBudget.MAX_SURFACE_PIXELS, budget.pixels());
        assertFalse(budget.reserve(surface, 1024 - 64, 769 - 64));
    }
    @Test public void layeredNavigationAccountsForEveryNodeAndRejectsLargeGeometry() {
        InspectorGlassBudget budget = new InspectorGlassBudget(); Object navigation = new Object();
        assertTrue(InspectorTabGeometry.reserve(budget, navigation, 992, 171, 2.75f, 4));
        assertTrue(budget.pixels() > 2_900_000); assertTrue(budget.pixels() <= InspectorGlassBudget.MAX_SESSION_PIXELS);
        long previous = budget.pixels();
        assertFalse(InspectorTabGeometry.reserve(budget, new Object(), 992, 171, 2.75f, 4));
        assertFalse(InspectorTabGeometry.reserve(budget, navigation, 1360, 250, 4, 4));
        assertEquals(previous, budget.pixels());
        budget.release(navigation);
        assertTrue(InspectorTabGeometry.reserve(budget, navigation, 700, 150, 2, 4));
        assertTrue(budget.pixels() < previous);
        assertFalse(InspectorTabGeometry.reserve(budget, new Object(), Integer.MAX_VALUE, 200, 3, 4));
        assertFalse(InspectorTabGeometry.reserve(budget, new Object(), 1000, 1, 3, 4));
        assertFalse(InspectorTabGeometry.reserve(budget, new Object(), 1000, 180, Float.NaN, 4));
    }
    @Test public void navigationAndInlineChoicesShareOneBoundedBudget() {
        InspectorGlassBudget budget = new InspectorGlassBudget(); Object navigation = new Object(), choices = new Object();
        assertTrue(InspectorTabGeometry.reserve(budget, navigation, 992, 171, 2.75f, 4));
        assertTrue(InspectorTabGeometry.reserve(budget, choices, 992, 143, 2.75f, 3, true));
        for (int i = 0; i < 4; i++) assertTrue(budget.reserve(new Object(), 132, 132));
        assertTrue(budget.pixels() < InspectorGlassBudget.MAX_SESSION_PIXELS);
        long previous = budget.pixels();
        assertTrue(InspectorTabGeometry.reserve(budget, choices, 992, 143, 2.75f, 3, true, 44, 21));
        assertEquals(previous, budget.pixels());
        assertFalse(InspectorTabGeometry.reserve(budget, choices, 992, 143, 2.75f, 3, true, Integer.MAX_VALUE, 21));
        assertEquals(previous, budget.pixels());
        assertFalse(InspectorTabGeometry.reserve(budget, new Object(), 992, 143, 2.75f, 3, true));
        assertEquals(previous, budget.pixels());
        budget.release(navigation); budget.release(choices);
        assertEquals(4L * 196 * 196, budget.pixels());
    }
}
