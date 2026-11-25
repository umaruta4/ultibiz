package com.ultibiz.core.exchange.peppol;

import com.ultibiz.core.exchange.CanonicalDocument;
import com.ultibiz.core.exchange.ExchangeAdapter;
import com.ultibiz.core.exchange.ExchangeException;
import com.ultibiz.core.exchange.ExchangeLine;
import com.ultibiz.core.exchange.ExchangeParty;
import com.ultibiz.core.exchange.ValidationIssue;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** The Peppol UBL adapter: UBL 2.1 out, UBL 2.1 in, EN 16931 validation. */
@Component
public class PeppolAdapter implements ExchangeAdapter {

    private static final String CBC = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";
    private static final String CAC = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2";

    @Override
    public String protocol() {
        return "PEPPOL";
    }

    @Override
    public CanonicalDocument parse(String payload) throws ExchangeException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var builder = factory.newDocumentBuilder();
            Element root = builder.parse(new InputSource(new StringReader(payload)))
                    .getDocumentElement();

            String rootTag = root.getLocalName();
            String type = "CreditNote".equals(rootTag)
                    ? CanonicalDocument.CREDIT_NOTE : CanonicalDocument.INVOICE;
            String number = text(root, "ID");
            String issueDate = text(root, "IssueDate");
            String dueDate = text(root, "DueDate");
            String currency = text(root, "DocumentCurrencyCode");

            ExchangeParty seller = party(root, "AccountingSupplierParty");
            ExchangeParty buyer = party(root, "AccountingCustomerParty");

            List<ExchangeLine> lines = new ArrayList<>();
            NodeList lineNodes = root.getElementsByTagNameNS(CAC,
                    "CreditNote".equals(rootTag) ? "CreditNoteLine" : "InvoiceLine");
            for (int i = 0; i < lineNodes.getLength(); i++) {
                Element lineEl = (Element) lineNodes.item(i);
                String qty = text(lineEl, "InvoicedQuantity");
                if (qty == null) {
                    qty = text(lineEl, "CreditedQuantity");
                }
                String price = text(lineEl, "PriceAmount");
                String productCode = null;
                NodeList sellerIds = lineEl.getElementsByTagNameNS(CAC, "SellersItemIdentification");
                if (sellerIds.getLength() > 0) {
                    productCode = text((Element) sellerIds.item(0), "ID");
                }
                String name = null;
                NodeList items = lineEl.getElementsByTagNameNS(CAC, "Item");
                if (items.getLength() > 0) {
                    name = text((Element) items.item(0), "Name");
                }
                String percent = "0";
                NodeList taxCats = lineEl.getElementsByTagNameNS(CAC, "ClassifiedTaxCategory");
                if (taxCats.getLength() > 0) {
                    String p = text((Element) taxCats.item(0), "Percent");
                    if (p != null) {
                        percent = p;
                    }
                }
                lines.add(new ExchangeLine(productCode, name,
                        new BigDecimal(qty == null ? "0" : qty),
                        new BigDecimal(price == null ? "0" : price),
                        "S", new BigDecimal(percent)));
            }
            return new CanonicalDocument(type, number,
                    issueDate == null ? null : LocalDate.parse(issueDate),
                    dueDate == null ? null : LocalDate.parse(dueDate),
                    currency, seller, buyer, lines, null, List.of());
        } catch (ExchangeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ExchangeException("The payload is not valid UBL XML: " + ex.getMessage(), ex);
        }
    }

    @Override
    public List<ValidationIssue> validate(CanonicalDocument document) {
        return PeppolRules.validate(document);
    }

    @Override
    public String write(CanonicalDocument document) throws ExchangeException {
        return UblWriter.write(document);
    }

    private static ExchangeParty party(Element root, String tag) {
        NodeList parties = root.getElementsByTagNameNS(CAC, tag);
        if (parties.getLength() == 0) {
            return null;
        }
        Element wrapper = (Element) parties.item(0);
        NodeList partyNodes = wrapper.getElementsByTagNameNS(CAC, "Party");
        if (partyNodes.getLength() == 0) {
            return null;
        }
        Element partyEl = (Element) partyNodes.item(0);
        String endpoint = null;
        Element endpointEl = firstCbc(partyEl, "EndpointID");
        if (endpointEl != null) {
            endpoint = endpointEl.getAttribute("schemeID") + ":" + endpointEl.getTextContent();
        }
        String name = null;
        NodeList names = partyEl.getElementsByTagNameNS(CAC, "PartyName");
        if (names.getLength() > 0) {
            name = text((Element) names.item(0), "Name");
        }
        String vat = null;
        NodeList taxSchemes = partyEl.getElementsByTagNameNS(CAC, "PartyTaxScheme");
        if (taxSchemes.getLength() > 0) {
            vat = text((Element) taxSchemes.item(0), "CompanyID");
        }
        NodeList addresses = partyEl.getElementsByTagNameNS(CAC, "PostalAddress");
        String country = countryHelper(addresses);
        return new ExchangeParty(null, name, endpoint, vat, null, null, null, country);
    }

    private static String countryHelper(NodeList addresses) {
        if (addresses.getLength() > 0) {
            NodeList countries = ((Element) addresses.item(0)).getElementsByTagNameNS(CAC, "Country");
            if (countries.getLength() > 0) {
                return text((Element) countries.item(0), "IdentificationCode");
            }
        }
        return null;
    }

    private static Element firstCbc(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagNameNS(CBC, tag);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static String text(Element parent, String tag) {
        Element el = firstCbc(parent, tag);
        if (el == null) {
            return null;
        }
        String value = el.getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }
}
