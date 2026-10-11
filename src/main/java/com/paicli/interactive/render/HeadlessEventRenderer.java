package com.paicli.interactive.render;

import java.io.PrintWriter;

/** Contract renderer for future non-interactive event output; not wired into existing CLI paths in R0. */
public final class HeadlessEventRenderer extends PlainEventRenderer {
    public HeadlessEventRenderer(PrintWriter output){super(output);}
}
