package com.pulse.control;

import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.util.concurrent.Callable;

/**
 * ThreadLocal-based SecurityContext propagation mechanism enabling seamless context transfer
 * across Project Loom virtual threads and asynchronous worker pools.
 */
public final class SecurityContextHolder {

    private static final ThreadLocal<SecurityContext> CURRENT_CONTEXT = new ThreadLocal<>();

    private SecurityContextHolder() {
    }

    public static void setContext(SecurityContext context) {
        if (context != null) {
            CURRENT_CONTEXT.set(context);
        } else {
            CURRENT_CONTEXT.remove();
        }
    }

    public static SecurityContext getContext() {
        return CURRENT_CONTEXT.get();
    }

    public static void clearContext() {
        CURRENT_CONTEXT.remove();
    }

    public static String getPrincipalName() {
        SecurityContext ctx = CURRENT_CONTEXT.get();
        if (ctx != null && ctx.getUserPrincipal() != null) {
            return ctx.getUserPrincipal().getName();
        }
        return "anonymous";
    }

    public static boolean isUserInRole(String role) {
        SecurityContext ctx = CURRENT_CONTEXT.get();
        return ctx != null && ctx.isUserInRole(role);
    }

    public static <T> Callable<T> wrap(Callable<T> task, SecurityContext contextToPropagate) {
        return () -> {
            SecurityContext previous = CURRENT_CONTEXT.get();
            try {
                setContext(contextToPropagate);
                return task.call();
            } finally {
                setContext(previous);
            }
        };
    }

    public static Runnable wrap(Runnable task, SecurityContext contextToPropagate) {
        return () -> {
            SecurityContext previous = CURRENT_CONTEXT.get();
            try {
                setContext(contextToPropagate);
                task.run();
            } finally {
                setContext(previous);
            }
        };
    }
}
