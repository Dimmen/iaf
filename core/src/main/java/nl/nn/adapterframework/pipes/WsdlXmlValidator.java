/*
   Copyright 2013 Nationale-Nederlanden

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package nl.nn.adapterframework.pipes;

import nl.nn.adapterframework.configuration.ConfigurationException;
import nl.nn.adapterframework.core.IPipeLineSession;
import nl.nn.adapterframework.core.PipeForward;
import nl.nn.adapterframework.core.PipeRunException;
import nl.nn.adapterframework.core.PipeRunResult;
import nl.nn.adapterframework.soap.SoapValidator;
import nl.nn.adapterframework.soap.Wsdl;
import nl.nn.adapterframework.util.ClassUtils;
import nl.nn.adapterframework.util.LogUtil;
import nl.nn.com.ibm.wsdl.extensions.schema.SchemaSerializer;
import nl.nn.javax.wsdl.Definition;
import nl.nn.javax.wsdl.WSDLException;
import nl.nn.javax.wsdl.extensions.ExtensibilityElement;
import nl.nn.javax.wsdl.extensions.schema.Schema;
import nl.nn.javax.wsdl.factory.WSDLFactory;
import nl.nn.javax.wsdl.xml.WSDLReader;
import nl.nn.javax.xml.namespace.QName;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Wsdl based input validator. Given an WSDL, it validates input.
 *
 * @author Michiel Meeuwissen
 * @author Jaco de Groot
 */
public class WsdlXmlValidator extends SoapValidator {

	private static final Logger LOG = LogUtil.getLogger(WsdlXmlValidator.class);

	private static final QName SCHEMA = new QName(XMLConstants.W3C_XML_SCHEMA_NS_URI, "schema", "");

	private static final WSDLFactory FACTORY;
	static {
		WSDLFactory f;
		try {
			f = WSDLFactory.newInstance();
		} catch (WSDLException e) {
			f = null;
			LOG.error(e.getMessage(), e);
		}
		FACTORY = f;
	}

	private String wsdl;
	private Definition definition;

	private SoapValidator.SoapVersion validateSoapEnvelope = SoapValidator.SoapVersion.VERSION_1_1;

	public void setWsdl(String wsdl) throws ConfigurationException {
		this.wsdl = wsdl;
		URL url = ClassUtils.getResourceURL(wsdl);
		if (url == null) {
			throw new ConfigurationException("Could not find WSDL: " + wsdl);
		}
		try {
			definition = getDefinition(url);
		} catch (WSDLException e) {
			throw new ConfigurationException("WSDLException reading WSDL from '" + url + "'", e);
		} catch (IOException e) {
			throw new ConfigurationException("IOException reading WSDL from '" + url + "'", e);
		}
	}


    public boolean isValidateSoapEnvelope() {
        return validateSoapEnvelope != null;
    }
    /**
     * You can disable validating the SOAP envelope. If for some reason that is possible and desirable.
     * @param validateSoapEnvelope false, true, 1.1 or 1.2
     */
    public void setValidateSoapEnvelope(String validateSoapEnvelope) {
        if (validateSoapEnvelope == null || "false".equals(validateSoapEnvelope)) {
            this.validateSoapEnvelope = null;
        } else if ("true".equals(validateSoapEnvelope)) {
            this.validateSoapEnvelope = SoapValidator.SoapVersion.VERSION_1_2;
        } else {
            this.validateSoapEnvelope = SoapValidator.SoapVersion.fromAttribute(validateSoapEnvelope);
        }
    }



	@Override
	public void configure() throws ConfigurationException {
		// Prevent super.configure() from throwing an exception because
		// schemaLocation is empty.
		if (schemaLocation == null) {
			schemaLocation = "dummy";
			super.configure();
			schemaLocation = null;
		} else {
			super.configure();
		}
	}

	@Override
	public PipeRunResult doPipe(Object input, IPipeLineSession session) throws PipeRunException {
		try {
			PipeForward forward = validate(input.toString(), session);
			return new PipeRunResult(forward, input);
		} catch (Exception e) {
			throw new PipeRunException(this, getLogPrefix(session), e);
		}
	}

	@Override
	public void setSchemaLocation(String schemaLocation) {
		throw new IllegalArgumentException("The schemaLocation attribute isn't supported");
	}

	@Override
	public String getSchemaLocation() {
		try {
			StringBuilder build = new StringBuilder();
			for (nl.nn.adapterframework.validation.Schema schema : getSchemas()) {
				if (build.length() > 0) {
					build.append("\n");
				}
				if (! getSoapNamespace().equals(schema.getTargetNamespace())) {
					build.append(schema.toString());
				}
			}
			return build.toString();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

	}

	protected static Definition getDefinition(URL url) throws WSDLException, IOException {
		InputSource source = new InputSource(url.openStream());
		source.setSystemId(url.toString());
		WSDLReader reader  = FACTORY.newWSDLReader();
		reader.setFeature("javax.wsdl.verbose",         true);
		reader.setFeature("javax.wsdl.importDocuments", true);
		return reader.readWSDL(url.toString(), source);
	}

	protected static void addNamespaces(Schema schema, Map<String, String> namespaces) {
		for (Map.Entry<String,String> e : namespaces.entrySet()) {
			String key = e.getKey().length() == 0 ? "xmlns" : ("xmlns:" + e.getKey());
			if (schema.getElement().getAttribute(key).length() == 0) {
				schema.getElement().setAttribute(key, e.getValue());
			}
		}
	}

	private InputStream toInputStream(Schema wsdlSchema) throws WSDLException, UnsupportedEncodingException {
		return new ByteArrayInputStream(toBytes(wsdlSchema));
	}

	private byte[] toBytes(Schema wsdlSchema) throws WSDLException, UnsupportedEncodingException {
		SchemaSerializer schemaSerializer = new SchemaSerializer();
		StringWriter w = new StringWriter();
		PrintWriter res = new PrintWriter(w);
		schemaSerializer.marshall(Object.class, SCHEMA, wsdlSchema, res,
				definition, definition.getExtensionRegistry());
		return w.toString().trim().getBytes("UTF-8");
	}

	@Override
	public String getSchemasId() {
		return wsdl;
	}

	@Override
	public List<nl.nn.adapterframework.validation.Schema> getSchemas() throws ConfigurationException {
		List<nl.nn.adapterframework.validation.Schema> result = new ArrayList<nl.nn.adapterframework.validation.Schema>();
        if (validateSoapEnvelope != null) {
            result.add(
                    new nl.nn.adapterframework.validation.Schema() {
                        public InputStream getInputStream() throws IOException {
                            if (validateSoapEnvelope.xsd == null) {
                                throw new IOException(validateSoapEnvelope + " has  no xsd");
                            }
                            return ClassUtils.getResourceURL(validateSoapEnvelope.xsd).openStream();
                        }

						public String getTargetNamespace() {
							return validateSoapEnvelope.schema;
						}
                        public String getSystemId() {
                            return ClassUtils.getResourceURL(validateSoapEnvelope.xsd).toExternalForm();
                        }

						@Override
						public String toString() {
							return validateSoapEnvelope.schema + " " + getSystemId();
						}

                    }
            );
        }
		List types = definition.getTypes().getExtensibilityElements();
		for (Iterator i = types.iterator(); i.hasNext();) {
			ExtensibilityElement type = (ExtensibilityElement) i.next();
			QName qn = type.getElementType();
			if (SCHEMA.equals(qn)) {
				final Schema schema = (Schema) type;
				addNamespaces(schema, definition.getNamespaces());
				result.add(
						new nl.nn.adapterframework.validation.Schema() {

							public InputStream getInputStream() {
								try {
									return toInputStream(schema);
								} catch (WSDLException e) {
									throw new RuntimeException("WSDLException while reading schema " + e.getMessage(), e);
								} catch (UnsupportedEncodingException e) {
									// can not happen
									throw new RuntimeException(e);
								}
							}
							public String getTargetNamespace() {
								return schema.getElement().getAttribute("targetNamespace");
							}

							public String getSystemId() {
								return ClassUtils.getResourceURL(wsdl).toExternalForm() + "#" + getTargetNamespace();
							}

							@Override
							public String toString() {
								return getTargetNamespace() + " " + getSystemId();
							}
						});
			}
		}

		return result;
	}

}
