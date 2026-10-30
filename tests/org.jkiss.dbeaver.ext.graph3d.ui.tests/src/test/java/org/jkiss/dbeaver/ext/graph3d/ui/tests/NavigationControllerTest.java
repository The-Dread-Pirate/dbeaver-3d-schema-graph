/*
 * DBeaver 3D Schema Graph
 * Licensed under EPL-2.0.
 */
package org.jkiss.dbeaver.ext.graph3d.ui.tests;

import org.jkiss.dbeaver.ext.graph3d.ui.model.NavigationController;
import org.jkiss.dbeaver.ext.graph3d.ui.model.NavigationController.Outcome;
import org.jkiss.dbeaver.ext.graph3d.ui.model.NavigationController.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure {@link NavigationController} flow. The DBeaver
 * resolver/opener are faked, so every branch (blank id, not found, resolve
 * throws, open throws, success) is exercised headless.
 */
class NavigationControllerTest {

    @Test
    @DisplayName("blank or null id short-circuits to INVALID_ID and never resolves")
    void blankId() {
        AtomicReference<Boolean> resolved = new AtomicReference<>(false);
        NavigationController c = new NavigationController(
            id -> { resolved.set(true); return new Object(); },
            r -> { });

        assertEquals(Status.INVALID_ID, c.navigate(null).status());
        assertEquals(Status.INVALID_ID, c.navigate("").status());
        assertEquals(Status.INVALID_ID, c.navigate("   ").status());
        assertFalse(resolved.get(), "resolver must not be called for a blank id");
    }

    @Test
    @DisplayName("unresolved id yields NOT_FOUND and never opens")
    void notFound() {
        AtomicReference<Boolean> opened = new AtomicReference<>(false);
        NavigationController c = new NavigationController(
            id -> null,
            r -> opened.set(true));

        Outcome o = c.navigate("db.schema.ghost");
        assertEquals(Status.NOT_FOUND, o.status());
        assertFalse(o.opened());
        assertFalse(opened.get());
        assertTrue(o.message().contains("db.schema.ghost"));
    }

    @Test
    @DisplayName("resolver exception is captured as ERROR, not propagated")
    void resolveThrows() {
        NavigationController c = new NavigationController(
            id -> { throw new IllegalStateException("db offline"); },
            r -> { });

        Outcome o = c.navigate("db.schema.orders");
        assertEquals(Status.ERROR, o.status());
        assertTrue(o.message().contains("db offline"));
    }

    @Test
    @DisplayName("opener exception is captured as ERROR, not propagated")
    void openThrows() {
        NavigationController c = new NavigationController(
            id -> new Object(),
            r -> { throw new RuntimeException("no editor"); });

        Outcome o = c.navigate("db.schema.orders");
        assertEquals(Status.ERROR, o.status());
        assertTrue(o.message().contains("no editor"));
    }

    @Test
    @DisplayName("resolved object is handed to the opener and reported OPENED")
    void success() {
        Object handle = new Object();
        AtomicReference<Object> openedWith = new AtomicReference<>();
        NavigationController c = new NavigationController(
            id -> handle,
            openedWith::set);

        Outcome o = c.navigate("db.schema.orders");
        assertEquals(Status.OPENED, o.status());
        assertTrue(o.opened());
        assertSame(handle, openedWith.get(), "the resolved handle must be passed through to the opener");
    }

    @Test
    @DisplayName("checked exceptions from resolve/open are caught as ERROR")
    void checkedExceptionCaught() {
        // A real resolver throws DBException (a checked Exception); confirm the
        // controller catches Exception and reports ERROR rather than letting it
        // escape the BrowserFunction callback.
        NavigationController c = new NavigationController(
            id -> { throw new Exception("checked failure"); },
            r -> { });
        Outcome o = c.navigate("x");
        assertEquals(Status.ERROR, o.status());
        assertTrue(o.message().contains("checked failure"));
    }

    @Test
    @DisplayName("Error (not Exception) is intentionally propagated, not swallowed")
    void errorPropagates() {
        NavigationController c = new NavigationController(
            id -> { throw new Error("hard"); },
            r -> { });
        Error thrown = assertThrows(Error.class, () -> c.navigate("x"));
        assertEquals("hard", thrown.getMessage());
    }
}
