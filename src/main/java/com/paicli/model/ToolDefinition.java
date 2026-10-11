package com.paicli.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Describes a tool that can be offered to the model.
 *
 * @param name             tool name, e.g. "list_files"
 * @param description      human-readable description of what the tool does
 * @param parameterSchema  JSON Schema describing the tool's parameters
 */
public record ToolDefinition(String name, String description, JsonNode parameterSchema) {
}
