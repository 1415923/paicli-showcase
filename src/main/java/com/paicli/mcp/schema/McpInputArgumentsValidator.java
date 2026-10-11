package com.paicli.mcp.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class McpInputArgumentsValidator {
    private final ObjectMapper json = new ObjectMapper();
    private final int maxBytes, maxDepth, maxNodes, maxArrayItems, maxStringChars;
    public McpInputArgumentsValidator(int maxBytes) { this(maxBytes, 32, 10000, 4096, 65536); }
    public McpInputArgumentsValidator(int maxBytes, int maxDepth, int maxNodes, int maxArrayItems, int maxStringChars) {
        this.maxBytes=maxBytes; this.maxDepth=maxDepth; this.maxNodes=maxNodes; this.maxArrayItems=maxArrayItems; this.maxStringChars=maxStringChars;
    }
    public void validate(JsonNode arguments, String normalizedSchemaJson) throws McpArgumentValidationException {
        if (arguments == null || !arguments.isObject()) fail("ARGUMENTS_NOT_OBJECT", "arguments must be an object");
        try { if (json.writeValueAsBytes(arguments).length > maxBytes) fail("ARGUMENTS_TOO_LARGE", "arguments exceed byte budget"); }
        catch (McpArgumentValidationException e) { throw e; } catch (Exception e) { fail("ARGUMENTS_INVALID", "arguments cannot be serialized"); }
        try { validateNode(arguments, json.readTree(normalizedSchemaJson), 1, new Counter()); }
        catch (McpArgumentValidationException e) { throw e; } catch (Exception e) { fail("SCHEMA_INVALID", "frozen schema cannot be read"); }
    }
    private void validateNode(JsonNode value, JsonNode schema, int depth, Counter count) throws McpArgumentValidationException {
        if (depth > maxDepth || ++count.nodes > maxNodes) fail("ARGUMENT_BUDGET", "arguments exceed structural budget");
        if (value.isTextual() && value.textValue().length() > maxStringChars) fail("ARGUMENT_BUDGET", "string exceeds budget");
        if (value.isArray() && value.size() > maxArrayItems) fail("ARGUMENT_BUDGET", "array exceeds budget");
        if (!matchesType(value, schema.get("type"))) fail("TYPE_MISMATCH", "argument type does not match schema");
        if (schema.has("enum")) { boolean found=false; for(JsonNode x:schema.get("enum")) found |= x.equals(value); if(!found) fail("ENUM_MISMATCH","value is not in enum"); }
        if (schema.has("const") && !schema.get("const").equals(value)) fail("CONST_MISMATCH","value does not match const");
        if (value.isObject()) validateObject(value, schema, depth, count);
        if (value.isArray()) validateArray(value, schema, depth, count);
        if (value.isTextual()) { bound(value.textValue().length(),schema,"minLength","maxLength"); }
        if (value.isNumber()) validateNumber(value.decimalValue(), schema, value.isIntegralNumber());
    }
    private void validateObject(JsonNode value, JsonNode schema,int depth,Counter count)throws McpArgumentValidationException{
        bound(value.size(),schema,"minProperties","maxProperties");
        Set<String> required=new HashSet<>(); if(schema.has("required")) for(JsonNode x:schema.get("required")) required.add(x.asText());
        for(String name:required) if(!value.has(name)) fail("REQUIRED_MISSING","required property is missing");
        JsonNode props=schema.get("properties"), additional=schema.get("additionalProperties");
        var it=value.fields(); while(it.hasNext()){var e=it.next(); JsonNode child=props==null?null:props.get(e.getKey());
            if(child!=null) validateNode(e.getValue(),child,depth+1,count);
            else if(additional!=null&&additional.isBoolean()&&!additional.booleanValue()) fail("ADDITIONAL_PROPERTY","unknown property is not allowed");
            else if(additional!=null&&additional.isObject()) validateNode(e.getValue(),additional,depth+1,count);
            else inspectBudget(e.getValue(),depth+1,count);
        }
    }
    private void inspectBudget(JsonNode value,int depth,Counter count)throws McpArgumentValidationException{
        if(depth>maxDepth||++count.nodes>maxNodes)fail("ARGUMENT_BUDGET","arguments exceed structural budget");
        if(value.isTextual()&&value.textValue().length()>maxStringChars)fail("ARGUMENT_BUDGET","string exceeds budget");
        if(value.isArray()){if(value.size()>maxArrayItems)fail("ARGUMENT_BUDGET","array exceeds budget");for(JsonNode x:value)inspectBudget(x,depth+1,count);}
        else if(value.isObject()){var it=value.elements();while(it.hasNext())inspectBudget(it.next(),depth+1,count);}
    }
    private void validateArray(JsonNode value,JsonNode schema,int depth,Counter count)throws McpArgumentValidationException{
        bound(value.size(),schema,"minItems","maxItems");
        if(schema.path("uniqueItems").asBoolean(false)){Set<String> seen=new HashSet<>();for(JsonNode x:value)if(!seen.add(x.toString()))fail("UNIQUE_ITEMS","array items must be unique");}
        if(schema.has("items"))for(JsonNode x:value)validateNode(x,schema.get("items"),depth+1,count);
    }
    private void validateNumber(BigDecimal n,JsonNode s,boolean integral)throws McpArgumentValidationException{
        if(typeContains(s.get("type"),"integer")&&!integral)fail("TYPE_MISMATCH","integer required");
        if(s.has("minimum")&&n.compareTo(s.get("minimum").decimalValue())<0)fail("NUMBER_RANGE","below minimum");
        if(s.has("maximum")&&n.compareTo(s.get("maximum").decimalValue())>0)fail("NUMBER_RANGE","above maximum");
        if(s.has("exclusiveMinimum")&&n.compareTo(s.get("exclusiveMinimum").decimalValue())<=0)fail("NUMBER_RANGE","below exclusive minimum");
        if(s.has("exclusiveMaximum")&&n.compareTo(s.get("exclusiveMaximum").decimalValue())>=0)fail("NUMBER_RANGE","above exclusive maximum");
        if(s.has("multipleOf")&&n.remainder(s.get("multipleOf").decimalValue()).compareTo(BigDecimal.ZERO)!=0)fail("MULTIPLE_OF","not a multiple");
    }
    private static boolean matchesType(JsonNode v,JsonNode t){if(t==null)return true;if(t.isArray()){for(JsonNode x:t)if(matches(v,x.asText()))return true;return false;}return matches(v,t.asText());}
    private static boolean matches(JsonNode v,String t){return switch(t){case"null"->v.isNull();case"boolean"->v.isBoolean();case"object"->v.isObject();case"array"->v.isArray();case"number"->v.isNumber();case"integer"->v.isIntegralNumber();case"string"->v.isTextual();default->false;};}
    private static boolean typeContains(JsonNode t,String x){if(t==null)return false;if(t.isTextual())return x.equals(t.asText());for(JsonNode n:t)if(x.equals(n.asText()))return true;return false;}
    private static void bound(int n,JsonNode s,String min,String max)throws McpArgumentValidationException{if(s.has(min)&&n<s.get(min).asInt())fail("SIZE_CONSTRAINT","below "+min);if(s.has(max)&&n>s.get(max).asInt())fail("SIZE_CONSTRAINT","above "+max);}
    private static void fail(String c,String m)throws McpArgumentValidationException{throw new McpArgumentValidationException(c,m);}
    private static final class Counter{int nodes;}
}
