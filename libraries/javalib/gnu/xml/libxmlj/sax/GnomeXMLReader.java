/*
 * GnomeXmlReader.java
 * Copyright (C) 2004 The Free Software Foundation
 * 
 * This file is part of GNU JAXP, a library.
 * 
 * GNU JAXP is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * 
 * GNU JAXP is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 * As a special exception, if you link this library with other files to
 * produce an executable, this library does not by itself cause the
 * resulting executable to be covered by the GNU General Public License.
 * This exception does not however invalidate any other reasons why the
 * executable file might be covered by the GNU General Public License.
 */
package gnu.xml.libxmlj.sax;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.PushbackInputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.DTDHandler;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.DeclHandler;
import org.xml.sax.ext.LexicalHandler;

import gnu.xml.libxmlj.util.StandaloneLocator;

/**
 * A SAX2 parser that uses libxml2.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GnomeXMLReader
implements XMLReader
{

  static
    {
      System.loadLibrary("xmlj");
    }

  private static final String FEATURES_PREFIX = "http://xml.org/sax/features/";
  private static final List RECOGNIZED_FEATURES = Arrays.asList(new String[] {
    "external-general-entities", "external-parameter-entities",
          "is-standalone", "lexical-handler/parameter-entities", "namespaces",
          "namespace-prefixes", "resolve-dtd-uris", "string-interning",
          "use-attributes2", "use-locator2", "use-entity-resolver2", "validation"
  });
  private static final String PROPERTIES_PREFIX = "http://xml.org/sax/properties/";
  private static final List RECOGNIZED_PROPERTIES = Arrays.asList(new String[] {
    "declaration-handler", "dom-node", "lexical-handler", "xml-string"
  });

  // Features

  private transient boolean standalone;
  private boolean namespaces;
  private boolean namespacePrefixes;
  private boolean validation;

  // Callback handlers

  private ContentHandler contentHandler;

  private DTDHandler dtdHandler;

  private EntityResolver entityResolver;

  private ErrorHandler errorHandler;

  private DeclHandler declarationHandler;

  private LexicalHandler lexicalHandler;

  private GnomeLocator locator;

  // Namespace helper for handling callbacks
  private transient Namespaces ns;

  // If true, do not invoke callback methods except endDocument
  private transient boolean seenFatalError;

  private transient boolean seenStartDocument;

  private transient URL base;

  public GnomeXMLReader()
    {
      this(true, true);
    }

  public GnomeXMLReader(boolean namespaces, boolean validation)
    {
      this.namespaces = namespaces;
      this.validation = validation;
      ns = new Namespaces();
    }

  public ContentHandler getContentHandler()
    {
      return contentHandler;
    }

  public void setContentHandler(ContentHandler handler)
    {
      contentHandler = handler;
    }

  public DTDHandler getDTDHandler()
    {
      return dtdHandler;
    }

  public void setDTDHandler(DTDHandler handler)
    {
      dtdHandler = handler;
    }

  public EntityResolver getEntityResolver()
    {
      return entityResolver;
    }

  public void setEntityResolver(EntityResolver resolver)
    {
      entityResolver = resolver;
    }

  public ErrorHandler getErrorHandler()
    {
      return errorHandler;
    }

  public void setErrorHandler(ErrorHandler handler)
    {
      errorHandler = handler;
    }

  // Features

  public boolean getFeature(String name) throws SAXNotRecognizedException, SAXNotSupportedException
    {
      checkFeatureName(name);
      String key = name.substring(FEATURES_PREFIX.length());
      if ("external-general-entities".equals(key))
        return validation; // TODO check this
      else if ("external-parameter-entities".equals(key))
        return validation; // TODO check this
      else if ("standalone".equals(key))
        return standalone;
      else if ("namespaces".equals(key))
        return namespaces;
      else if ("namespace-prefixes".equals(key))
        return namespacePrefixes;
      else if ("resolve-dtd-uris".equals(key))
        return true; // TODO check this
      else if ("validation".equals(key))
        return validation;
      else
        return false;
    }

  public void setFeature(String name, boolean value) throws SAXNotRecognizedException, SAXNotSupportedException
    {
      checkFeatureName(name);
      String key = name.substring(FEATURES_PREFIX.length());
      if ("namespaces".equals(key))
        namespaces = value;
      else if ("namespace-prefixes".equals(key))
        namespacePrefixes = value;
      else if ("validation".equals(key))
        validation = value;
    }

  /**
   * Check that the specified feature name is recognized.
   */
  static void checkFeatureName(String name) throws SAXNotRecognizedException
    {
      if (name == null || !name.startsWith(FEATURES_PREFIX))
        throw new SAXNotRecognizedException(name);
      String key = name.substring(FEATURES_PREFIX.length());
      if (!RECOGNIZED_FEATURES.contains(key))
        throw new SAXNotRecognizedException(name);
    }

  // Properties

  public Object getProperty(String name) throws SAXNotRecognizedException, SAXNotSupportedException
    {
      checkPropertyName(name);
      String key = name.substring(PROPERTIES_PREFIX.length());
      if ("declaration-handler".equals(key))
        return declarationHandler;
      else if ("lexical-handler".equals(key))
        return lexicalHandler;
      else
        throw new SAXNotSupportedException(name);
    }

  public void setProperty(String name, Object value) throws SAXNotRecognizedException, SAXNotSupportedException
    {
      checkPropertyName(name);
      String key = name.substring(PROPERTIES_PREFIX.length());
      if ("declaration-handler".equals(key))
        declarationHandler = (DeclHandler)value;
      else if ("lexical-handler".equals(key))
        lexicalHandler = (LexicalHandler)value;
    }

  /**
   * Check that the specified property name is recognized.
   */
  static void checkPropertyName(String name) throws SAXNotRecognizedException
    {
      if (!name.startsWith(PROPERTIES_PREFIX))
        throw new SAXNotRecognizedException(name);
      String key = name.substring(PROPERTIES_PREFIX.length());
      if (!RECOGNIZED_PROPERTIES.contains(key))
        throw new SAXNotRecognizedException(name);
    }

  // Parse

  public void parse(String filename) throws IOException, SAXException
    {
      File file = new File(filename);
      String url  = file.getAbsolutePath();
      if (File.separatorChar != '/')
        url = url.replace (File.separatorChar, '/');
      if (!url.startsWith ("/"))
        url = "/" + url;
      if (!url.endsWith ("/") && file.isDirectory ())
        url = url + "/";
      url = "file:" + url;
      InputSource source = new InputSource(url);
      source.setByteStream(new FileInputStream(file));
      parse(source);
    }

  public synchronized void parse(InputSource input) throws IOException, SAXException
    {
      InputStream in = getInputStream(input);
      String publicId = input.getPublicId();
      String systemId = input.getSystemId();
      // Reset state
      standalone = false;
      seenFatalError = false;
      seenStartDocument = false;
      base = (systemId != null) ? new URL(systemId) : null;
      // Parse
      try
        {
          parseStream(in,
                      publicId,
                      systemId,
                      validation,
                      contentHandler != null,
                      dtdHandler != null,
                      entityResolver != null,
                      errorHandler != null,
                      declarationHandler != null,
                      lexicalHandler != null);
        }
      catch (IOException e)
        {
          String message = e.getMessage();
          if ("document is empty".equals(message))
            {
              startDocument(false);
              fatalError(message, -1, -1, publicId, systemId);
              endDocument();
            }
          else
            throw e;
        }
      in.close();
    }

  InputStream getInputStream(InputSource input) throws IOException
    {
      InputStream in = input.getByteStream();
      if (in == null)
        {
          String systemId = input.getSystemId();
          if (systemId != null)
            in = new URL(systemId).openStream();
          else
            throw new IOException("Unable to locate input source");
        }
      return new PushbackInputStream(in, 50);
    }

  native void parseStream(InputStream in,
                          String publicId,
                          String systemId,
                          boolean validate,
                          boolean contentHandler,
                          boolean dtdHandler,
                          boolean entityResolver,
                          boolean errorHandler,
                          boolean declarationHandler,
                          boolean lexicalHandler)
    throws IOException, SAXException;

  String getURI(String prefix)
    {
      if (!namespaces)
        return null;
      return ns.getURI(prefix);
    }

  /*
   * Expands the given URI if necessary.
   */
  String expand(String uri)
    {
      if (uri != null && (uri.length() > 0) && (uri.indexOf(':') == -1) &&
          base != null)
        {
          if (uri.charAt(0) != '/')
            {
              // relative
              String path = base.getFile();
              int lsi = path.lastIndexOf('/');
              if (lsi != -1)
                uri = path.substring(0, lsi + 1) + uri;
            }
          try
            {
              uri = new URL(base.getProtocol(),
                            base.getHost(),
                            base.getPort(),
                            uri).toString();
            }
          catch (MalformedURLException e)
            {
            }
        }
      return uri;
    }

  // Callbacks from libxmlj

  private void startDTD(String name,
                        String publicId,
                        String systemId) throws SAXException
    {
      if (!seenFatalError && lexicalHandler != null)
        lexicalHandler.startDTD(name, publicId, expand(systemId));
    }

  private void externalEntityDecl(String name,
                                  String publicId,
                                  String systemId) throws SAXException
    {
      if (!seenFatalError && declarationHandler != null)
        declarationHandler.externalEntityDecl(name, publicId, expand(systemId));
    }

  private void internalEntityDecl(String name,
                                  String value) throws SAXException
    {
      if (!seenFatalError && declarationHandler != null)
        declarationHandler.internalEntityDecl(name, value);
    }

  private InputStream resolveEntity(String publicId,
                                    String systemId) throws SAXException, IOException
    {
      if (entityResolver != null)
        return getInputStream(entityResolver.resolveEntity(publicId,
                                                           expand(systemId)));
      else
        return null;
    }

  private void notationDecl(String name,
                            String publicId,
                            String systemId) throws SAXException
    {
      if (!seenFatalError && dtdHandler != null)
        dtdHandler.notationDecl(name, publicId, expand(systemId));
    }

  private void attributeDecl(String eName,
                             String aName,
                             String type,
                             String mode,
                             String value) throws SAXException
    {
      if (!seenFatalError && declarationHandler != null)
        declarationHandler.attributeDecl(eName, aName, type, mode, value);
    }

  private void elementDecl(String name,
                           String model) throws SAXException
    {
      if (!seenFatalError && declarationHandler != null)
        declarationHandler.elementDecl(name, model);
    }

  private void unparsedEntityDecl(String name,
                                  String publicId,
                                  String systemId,
                                  String notationName) throws SAXException
    {
      if (!seenFatalError && dtdHandler != null)
        dtdHandler.unparsedEntityDecl(name, publicId, expand(systemId),
                                      notationName);
    }

  private void setDocumentLocator(int ctx, int loc)
    {
      locator = new GnomeLocator(ctx, loc);
      if (!seenFatalError && contentHandler != null)
        contentHandler.setDocumentLocator(locator);
    }

  private void startDocument(boolean standalone) throws SAXException
    {
      if (contentHandler != null)
        contentHandler.startDocument();
      this.standalone = standalone;
      seenStartDocument = true;
    }

  private void endDocument() throws SAXException
    {
      if (contentHandler != null)
        contentHandler.endDocument();
    }

  private void startElement(String name, String[] attrs) throws SAXException
    {
      if (!seenFatalError && contentHandler != null)
        {
          XMLName xName = new XMLName(this, name);
          if (namespaces)
            {
              // Handle defined namespaces
              ns.push();
              int len = attrs.length;
              ArrayList filtered = new ArrayList(len);
              for (int i = 0; i < len; i += 2)
                {
                  String attName = attrs[i];
                  String attValue = attrs[i + 1];
                  if (attName.equals("xmlns"))
                    startPrefixMapping("", attValue);
                  else if (attName.startsWith("xmlns:"))
                    startPrefixMapping(attName.substring(6), attValue);
                  else
                    {
                      filtered.add(attName);
                      filtered.add(attValue);
                    }
                }
              // Remove xmlns attributes
              attrs = new String[filtered.size()];
              filtered.toArray(attrs);
            }
          // Construct attributes
          Attributes atts = new StringArrayAttributes(this, attrs);
          contentHandler.startElement(xName.uri, xName.localName, xName.qName,
                                      atts);
        }
    }

  private void endElement(String name) throws SAXException
    {
      if (!seenFatalError && contentHandler != null)
        {
          XMLName xName = new XMLName(this, name);
          String uri = (xName.uri == null) ? "" : xName.uri;
          contentHandler.endElement(uri, xName.localName, xName.qName);
          // Handle undefining namespaces
          if (namespaces)
            {
              for (Iterator i = ns.currentPrefixes(); i.hasNext(); )
                endPrefixMapping((String)i.next());
              ns.pop(); // releases current depth
            }
        }
    }

  private void startPrefixMapping(String prefix, String uri) throws SAXException
    {
      if (!seenFatalError && contentHandler != null)
        {
          ns.define(prefix, uri);
          contentHandler.startPrefixMapping(prefix, uri);
        }
    }

  private void endPrefixMapping(String prefix) throws SAXException
    {
      if (!seenFatalError && contentHandler != null)
        contentHandler.endPrefixMapping(prefix);
    }

  private void characters(String text) throws SAXException
    {
      if (!seenFatalError && contentHandler != null && text != null)
        {
          char[] ch = text.toCharArray();
          contentHandler.characters(ch, 0, ch.length);
        }
    }

  private void ignorableWhitespace(String text) throws SAXException
    {
      if (!seenFatalError && contentHandler != null && text != null)
        {
          char[] ch = text.toCharArray();
          contentHandler.ignorableWhitespace(ch, 0, ch.length);
        }
    }

  private void processingInstruction(String target,
                                     String data) throws SAXException
    {
      if (!seenFatalError && contentHandler != null)
        {
          if (data == null)
            data = "";
          contentHandler.processingInstruction(target, data);
        }
    }

  private void comment(String text) throws SAXException
    {
      if (!seenFatalError && lexicalHandler != null && text != null)
        {
          char[] ch = text.toCharArray();
          lexicalHandler.comment(ch, 0, ch.length);
        }
    }

  private void cdataBlock(String text) throws SAXException
    {
      if (!seenFatalError && text != null)
        {
          if (lexicalHandler != null)
            lexicalHandler.startCDATA();
          characters(text);
          if (lexicalHandler != null)
            lexicalHandler.endCDATA();
        }
    }

  private void warning(String message,
                       int lineNumber,
                       int columnNumber,
                       String publicId,
                       String systemId) throws SAXException
    {
      if (!seenFatalError && errorHandler != null)
        {
          Locator l = new StandaloneLocator(lineNumber,
                                            columnNumber,
                                            publicId,
                                            systemId);
          errorHandler.warning(new SAXParseException(message, l));
        }
    }

  private void error(String message,
                     int lineNumber,
                     int columnNumber,
                     String publicId,
                     String systemId) throws SAXException
    {
      if (!seenFatalError && errorHandler != null)
        {
          Locator l = new StandaloneLocator(lineNumber,
                                            columnNumber,
                                            publicId,
                                            systemId);
          errorHandler.error(new SAXParseException(message, l));
        }
    }

  private void fatalError(String message,
                          int lineNumber,
                          int columnNumber,
                          String publicId,
                          String systemId) throws SAXException
    {
      if (!seenFatalError && errorHandler != null)
        {
          if (!seenStartDocument)
            startDocument(false);
          seenFatalError = true;
          Locator l = new StandaloneLocator(lineNumber,
                                            columnNumber,
                                            publicId,
                                            systemId);
          errorHandler.fatalError(new SAXParseException(message, l));
        }
    }

}