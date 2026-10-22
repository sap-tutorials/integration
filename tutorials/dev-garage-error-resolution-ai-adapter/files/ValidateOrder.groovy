/*
 * ValidateOrder.groovy
 * Main integration process - replaces the standard XML Validator step.
 * Validates the SalesOrder payload against the embedded XSD and throws an
 * exception whose message contains the full validation detail (line + reason),
 * so the exception subprocess can pass it to the AI adapter.
 */
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.transform.Field
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException

@Field String XSD = '''<?xml version="1.0" encoding="UTF-8"?>
<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" elementFormDefault="qualified">
  <xs:element name="SalesOrder">
    <xs:complexType>
      <xs:sequence>
        <xs:element name="OrderID" type="xs:string"/>
        <xs:element name="OrderDate" type="xs:date"/>
        <xs:element name="Customer">
          <xs:complexType>
            <xs:sequence>
              <xs:element name="CustomerID" type="xs:string"/>
              <xs:element name="Email" type="xs:string"/>
              <xs:element name="Phone" type="xs:string" minOccurs="0"/>
            </xs:sequence>
          </xs:complexType>
        </xs:element>
        <xs:element name="Items">
          <xs:complexType>
            <xs:sequence>
              <xs:element name="Item" maxOccurs="unbounded">
                <xs:complexType>
                  <xs:sequence>
                    <xs:element name="MaterialID" type="xs:string"/>
                    <xs:element name="Quantity" type="xs:positiveInteger"/>
                    <xs:element name="UnitPrice" type="xs:decimal"/>
                    <xs:element name="Currency">
                      <xs:simpleType>
                        <xs:restriction base="xs:string">
                          <xs:pattern value="[A-Z]{3}"/>
                        </xs:restriction>
                      </xs:simpleType>
                    </xs:element>
                  </xs:sequence>
                </xs:complexType>
              </xs:element>
            </xs:sequence>
          </xs:complexType>
        </xs:element>
      </xs:sequence>
    </xs:complexType>
  </xs:element>
</xs:schema>'''

def Message processData(Message message) {
    String xml = message.getBody(String)

    def schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                              .newSchema(new StreamSource(new StringReader(XSD)))
    def validator = schema.newValidator()

    List<String> errors = []
    validator.setErrorHandler([
        warning   : { SAXParseException e -> },
        error     : { SAXParseException e -> errors << "Line ${e.lineNumber}: ${e.message}" },
        fatalError: { SAXParseException e -> errors << "Line ${e.lineNumber}: ${e.message}" }
    ] as ErrorHandler)

    try {
        validator.validate(new StreamSource(new StringReader(xml)))
    } catch (SAXParseException e) {
        // Fatal errors (e.g. malformed XML) stop validation; the handler usually recorded them already
        if (!errors) errors << "Line ${e.lineNumber}: ${e.message}"
    }

    if (errors) {
        throw new IllegalStateException("SalesOrder schema validation failed: " + errors.join(" | "))
    }
    return message
}