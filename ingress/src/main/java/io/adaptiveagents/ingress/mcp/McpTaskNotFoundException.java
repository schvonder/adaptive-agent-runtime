package io.adaptiveagents.ingress.mcp;

/** Raised for MCP lookups of unknown task ids (or not-yet-terminal results) — client-side error. */
class McpTaskNotFoundException extends RuntimeException {

    McpTaskNotFoundException(String detail) {
        super(detail);
    }
}
