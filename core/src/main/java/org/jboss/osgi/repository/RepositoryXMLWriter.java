/*
 * #%L
 * JBossOSGi Repository: API
 * %%
 * Copyright (C) 2011 - 2012 JBoss by Red Hat
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package org.jboss.osgi.repository;

import static org.jboss.osgi.repository.Namespace100.REPOSITORY_NAMESPACE;
import static org.jboss.osgi.repository.Namespace100.Attribute.NAME;
import static org.jboss.osgi.repository.Namespace100.Attribute.NAMESPACE;
import static org.jboss.osgi.repository.Namespace100.Attribute.TYPE;
import static org.jboss.osgi.repository.Namespace100.Attribute.VALUE;
import static org.jboss.osgi.repository.Namespace100.Element.ATTRIBUTE;
import static org.jboss.osgi.repository.Namespace100.Element.CAPABILITY;
import static org.jboss.osgi.repository.Namespace100.Element.DIRECTIVE;
import static org.jboss.osgi.repository.Namespace100.Element.REPOSITORY;
import static org.jboss.osgi.repository.Namespace100.Element.REQUIREMENT;
import static org.jboss.osgi.repository.Namespace100.Element.RESOURCE;
import static org.jboss.osgi.repository.RepositoryMessages.MESSAGES;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Map.Entry;

import org.jboss.osgi.repository.AttributeValueHandler.AttributeValue;
import org.jboss.osgi.repository.Namespace100.Type;
import org.jboss.osgi.resolver.XResource;
import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;

/**
 * Write repository contnet to XML.
 *
 * @author thomas.diesler@jboss.com
 * @since 21-May-2012
 */
public class RepositoryXMLWriter implements RepositoryWriter {

    private final XMLWriter writer;

    public static RepositoryWriter create(OutputStream output) {
        return new RepositoryXMLWriter(output);
    }

    private RepositoryXMLWriter(OutputStream output) {
        if (output == null)
            throw MESSAGES.illegalArgumentNull("output");
        writer = new XMLWriter(output);
    }

    @Override
    public void writeRepositoryElement(Map<String, String> attributes) {
        try {
            writer.writeStartDocument();
            writer.writeStartElement(REPOSITORY.getLocalName());
            writer.writeAttribute("xmlns", REPOSITORY_NAMESPACE);
            for (Entry<String, String> entry : attributes.entrySet()) {
                writer.writeAttribute(entry.getKey(), entry.getValue());
            }
        } catch (IOException ex) {
            throw MESSAGES.cannotWriteRepositoryElement(ex);
        }
    }

    @Override
    public void writeResource(XResource resource) {
        try {
            writer.writeStartElement(RESOURCE.getLocalName());
            for (Entry<String, Object> entry : resource.getAttributes().entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                writer.writeAttribute(key, "" + value);
            }
            for (Capability cap : resource.getCapabilities(null)) {
                writer.writeStartElement(CAPABILITY.getLocalName());
                writer.writeAttribute(NAMESPACE.getLocalName(), cap.getNamespace());
                writeAttributes(cap.getAttributes());
                writeDirectives(cap.getDirectives());
                writer.writeEndElement();
            }
            for (Requirement req : resource.getRequirements(null)) {
                writer.writeStartElement(REQUIREMENT.getLocalName());
                writer.writeAttribute(NAMESPACE.getLocalName(), req.getNamespace());
                writeAttributes(req.getAttributes());
                writeDirectives(req.getDirectives());
                writer.writeEndElement();
            }
            writer.writeEndElement();
        } catch (IOException ex) {
            throw MESSAGES.cannotWriteRepositoryElement(ex);
        }
    }

    @Override
    public void close() {
        try {
            writer.writeEndElement();
            writer.writeEndDocument();
            writer.close();
        } catch (IOException ex) {
            throw MESSAGES.cannotWriteRepositoryElement(ex);
        }
    }

    private void writeAttributes(Map<String, Object> attributes) throws IOException {
        for (Entry<String, Object> entry : attributes.entrySet()) {
            AttributeValue attval = AttributeValue.create(entry.getValue());
            writer.writeStartElement(ATTRIBUTE.getLocalName());
            writer.writeAttribute(NAME.getLocalName(), entry.getKey());
            if (attval.isListType()) {
                writer.writeAttribute(VALUE.getLocalName(), attval.getValueString());
                writer.writeAttribute(TYPE.getLocalName(), "List<" + attval.getType() + ">");
            } else {
                writer.writeAttribute(VALUE.getLocalName(), attval.getValueString());
                if (attval.getType() != Type.String) {
                    writer.writeAttribute(TYPE.getLocalName(), attval.getType().toString());
                }
            }
            writer.writeEndElement();
        }
    }

    private void writeDirectives(Map<String, String> directives) throws IOException {
        for (Entry<String, String> entry : directives.entrySet()) {
            writer.writeStartElement(DIRECTIVE.getLocalName());
            writer.writeAttribute(NAME.getLocalName(), entry.getKey());
            writer.writeAttribute(VALUE.getLocalName(), entry.getValue());
            writer.writeEndElement();
        }
    }

    /**
     * A minimal XML writer. Unlike XMLStreamWriter it writes tab, newline and carriage return in attribute values as
     * character references, which attribute-value normalization would otherwise turn into spaces. A character that
     * XML 1.0 cannot represent at all is rejected, the written XML could not be read again.
     */
    private static final class XMLWriter {

        private final Writer output;
        private final Deque<String> elements = new ArrayDeque<String>();
        private boolean startTagOpen;

        XMLWriter(OutputStream output) {
            this.output = new OutputStreamWriter(output, StandardCharsets.UTF_8);
        }

        void writeStartDocument() throws IOException {
            output.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        }

        void writeStartElement(String name) throws IOException {
            closeStartTag();
            output.write("<" + name);
            elements.push(name);
            startTagOpen = true;
        }

        void writeAttribute(String name, String value) throws IOException {
            output.write(" " + name + "=\"" + escape(value) + "\"");
        }

        void writeEndElement() throws IOException {
            String name = elements.pop();
            if (startTagOpen) {
                output.write("/>");
                startTagOpen = false;
            } else {
                output.write("</" + name + ">");
            }
        }

        void writeEndDocument() throws IOException {
            while (!elements.isEmpty()) {
                writeEndElement();
            }
        }

        // Like XMLStreamWriter, do not close the underlying output stream
        void close() throws IOException {
            output.flush();
        }

        private void closeStartTag() throws IOException {
            if (startTagOpen) {
                output.write(">");
                startTagOpen = false;
            }
        }

        private static String escape(String value) {
            StringBuilder result = new StringBuilder();
            int i = 0;
            while (i < value.length()) {
                int ch = value.codePointAt(i);
                i += Character.charCount(ch);
                switch (ch) {
                    case '&':
                        result.append("&amp;");
                        break;
                    case '<':
                        result.append("&lt;");
                        break;
                    case '>':
                        result.append("&gt;");
                        break;
                    case '"':
                        result.append("&quot;");
                        break;
                    case '\t':
                        result.append("&#9;");
                        break;
                    case '\n':
                        result.append("&#10;");
                        break;
                    case '\r':
                        result.append("&#13;");
                        break;
                    default:
                        // Char ::= #x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]
                        if (ch < 0x20 || (ch >= 0xD800 && ch <= 0xDFFF) || ch == 0xFFFE || ch == 0xFFFF)
                            throw MESSAGES.invalidXMLCharacter(ch, value);
                        result.appendCodePoint(ch);
                        break;
                }
            }
            return result.toString();
        }
    }
}
