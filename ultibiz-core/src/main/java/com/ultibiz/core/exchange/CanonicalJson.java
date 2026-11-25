package com.ultibiz.core.exchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The canonical JSON representation of an exchanged document — the wire format
 * of the REST/JSON adapter and the stored form of every mapped message.
 */
public final class CanonicalJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CanonicalJson() {
    }

    public static CanonicalDocument fromJson(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            return fromNode(root);
        } catch (ExchangeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ExchangeException("The payload is not valid JSON: " + ex.getMessage(), ex);
        }
    }

    static CanonicalDocument fromNode(JsonNode root) {
        List<ExchangeLine> lines = new ArrayList<>();
        JsonNode lineNodes = root.path("lines");
        if (lineNodes.isArray()) {
            for (JsonNode lineNode : lineNodes) {
                lines.add(new ExchangeLine(
                        text(lineNode, "productCode"),
                        text(lineNode, "description"),
                        decimal(lineNode, "quantity"),
                        decimal(lineNode, "unitPrice"),
                        text(lineNode, "taxCode"),
                        decimal(lineNode, "taxPercent")));
            }
        }
        List<String> references = new ArrayList<>();
        JsonNode referenceNodes = root.path("references");
        if (referenceNodes.isArray()) {
            for (JsonNode referenceNode : referenceNodes) {
                references.add(referenceNode.isTextual() ? referenceNode.asText()
                        : text(referenceNode, "type") + ":" + text(referenceNode, "value"));
            }
        }
        return new CanonicalDocument(
                text(root, "type"),
                text(root, "documentNumber"),
                date(root, "issueDate"),
                date(root, "dueDate"),
                text(root, "currency"),
                party(root.path("seller")),
                party(root.path("buyer")),
                lines,
                text(root, "paymentTerms"),
                references);
    }

    public static String toJson(CanonicalDocument document) {
        ObjectNode root = MAPPER.createObjectNode();
        put(root, "type", document.type());
        put(root, "documentNumber", document.documentNumber());
        put(root, "issueDate", document.issueDate() == null ? null : document.issueDate().toString());
        put(root, "dueDate", document.dueDate() == null ? null : document.dueDate().toString());
        put(root, "currency", document.currency());
        root.set("seller", partyNode(document.seller()));
        root.set("buyer", partyNode(document.buyer()));
        ArrayNode lines = root.putArray("lines");
        for (ExchangeLine line : document.lines()) {
            ObjectNode node = MAPPER.createObjectNode();
            put(node, "productCode", line.productCode());
            put(node, "description", line.description());
            node.put("quantity", line.quantity());
            node.put("unitPrice", line.unitPrice());
            put(node, "taxCode", line.taxCode());
            node.put("taxPercent", line.taxPercent());
            lines.add(node);
        }
        put(root, "paymentTerms", document.paymentTerms());
        ArrayNode references = root.putArray("references");
        document.references().forEach(references::add);
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception ex) {
            throw new ExchangeException("Cannot render the canonical JSON", ex);
        }
    }

    private static ObjectNode partyNode(ExchangeParty party) {
        ObjectNode node = MAPPER.createObjectNode();
        if (party == null) {
            return node;
        }
        put(node, "partyCode", party.partyCode());
        put(node, "name", party.name());
        put(node, "electronicAddress", party.electronicAddress());
        put(node, "vatNumber", party.vatNumber());
        put(node, "street", party.street());
        put(node, "city", party.city());
        put(node, "postalCode", party.postalCode());
        put(node, "countryCode", party.countryCode());
        return node;
    }

    private static ExchangeParty party(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return new ExchangeParty(
                text(node, "partyCode"),
                text(node, "name"),
                text(node, "electronicAddress"),
                text(node, "vatNumber"),
                text(node, "street"),
                text(node, "city"),
                text(node, "postalCode"),
                text(node, "countryCode"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return BigDecimal.ZERO;
        }
        return value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText().trim());
    }

    private static LocalDate date(JsonNode node, String field) {
        String value = text(node, field);
        return value == null || value.isBlank() ? null : LocalDate.parse(value.trim());
    }

    private static void put(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }
}
