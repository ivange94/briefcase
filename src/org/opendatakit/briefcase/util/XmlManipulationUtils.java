/*
 * Copyright (C) 2011 University of Washington.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.opendatakit.briefcase.util;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.opendatakit.common.utils.WebUtils.parseDate;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.apache.commons.io.FileUtils;
import org.javarosa.xform.parse.XFormParser;
import org.kxml2.io.KXmlParser;
import org.kxml2.io.KXmlSerializer;
import org.kxml2.kdom.Document;
import org.kxml2.kdom.Element;
import org.kxml2.kdom.Node;
import org.opendatakit.aggregate.form.XFormParameters;
import org.opendatakit.briefcase.model.CannotFixXMLException;
import org.opendatakit.briefcase.model.FileSystemException;
import org.opendatakit.briefcase.model.MetadataUpdateException;
import org.opendatakit.briefcase.model.ParsingException;
import org.opendatakit.briefcase.util.ServerFetcher.SubmissionChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

@SuppressWarnings("checkstyle:MissingSwitchDefault")
class XmlManipulationUtils {

  private static final Logger log = LoggerFactory.getLogger(XmlManipulationUtils.class);

  private static final String NAMESPACE_OPENDATAKIT_ORG_SUBMISSIONS = "http://opendatakit.org/submissions";

  private static final String NAMESPACE_ODK = "http://www.opendatakit.org/xforms";

  // NOTE: the only transfered metadata is the instanceID and the submissionDate

  private static final String INSTANCE_ID_ATTRIBUTE_NAME = "instanceID";
  private static final String SUBMISSION_DATE_ATTRIBUTE_NAME = "submissionDate";

  private static final String OPEN_ROSA_NAMESPACE_PRELIM = "http://openrosa.org/xforms/metadata";
  private static final String OPEN_ROSA_NAMESPACE = "http://openrosa.org/xforms";
  private static final String OPEN_ROSA_NAMESPACE_SLASH = "http://openrosa.org/xforms/";
  private static final String OPEN_ROSA_METADATA_TAG = "meta";
  private static final String OPEN_ROSA_INSTANCE_ID = "instanceID";
  private static final String BASE64_ENCRYPTED_FIELD_KEY = "base64EncryptedFieldKey";


  /**
   * Traverse submission looking for OpenRosa metadata tag (with or without
   * namespace).
   */
  private static Element findMetaTag(Element parent, String rootUri) {
    for (int i = 0; i < parent.getChildCount(); ++i) {
      if (parent.getType(i) == Node.ELEMENT) {
        Element child = parent.getElement(i);
        String cnUri = child.getNamespace();
        String cnName = child.getName();
        if (cnName.equals(OPEN_ROSA_METADATA_TAG)
            && (cnUri == null ||
            cnUri.equals(EMPTY_STRING) ||
            cnUri.equals(rootUri) ||
            cnUri.equalsIgnoreCase(OPEN_ROSA_NAMESPACE) ||
            cnUri.equalsIgnoreCase(OPEN_ROSA_NAMESPACE_SLASH) ||
            cnUri.equalsIgnoreCase(OPEN_ROSA_NAMESPACE_PRELIM))) {
          return child;
        } else {
          Element descendent = findMetaTag(child, rootUri);
          if (descendent != null)
            return descendent;
        }
      }
    }
    return null;
  }

  /**
   * Find the OpenRosa instanceID defined for this record, if any.
   */
  private static String getOpenRosaInstanceId(Element root) {
    String rootUri = root.getNamespace();
    Element meta = findMetaTag(root, rootUri);
    if (meta != null) {
      for (int i = 0; i < meta.getChildCount(); ++i) {
        if (meta.getType(i) == Node.ELEMENT) {
          Element child = meta.getElement(i);
          String cnUri = child.getNamespace();
          String cnName = child.getName();
          if (cnName.equals(OPEN_ROSA_INSTANCE_ID)
              && (cnUri == null ||
              cnUri.equals(EMPTY_STRING) ||
              cnUri.equals(rootUri) ||
              cnUri.equalsIgnoreCase(OPEN_ROSA_NAMESPACE) ||
              cnUri.equalsIgnoreCase(OPEN_ROSA_NAMESPACE_SLASH) ||
              cnUri.equalsIgnoreCase(OPEN_ROSA_NAMESPACE_PRELIM))) {
            return XFormParser.getXMLText(child, true);
          }
        }
      }
    }
    return null;
  }

  /**
   * Encrypted field-level encryption key.
   */
  private static String getBase64EncryptedFieldKey(Element root) {
    String rootUri = root.getNamespace();
    Element meta = findMetaTag(root, rootUri);
    if (meta != null) {
      for (int i = 0; i < meta.getChildCount(); ++i) {
        if (meta.getType(i) == Node.ELEMENT) {
          Element child = meta.getElement(i);
          String cnUri = child.getNamespace();
          String cnName = child.getName();
          if (cnName.equals(BASE64_ENCRYPTED_FIELD_KEY)
              && (cnUri == null ||
              cnUri.equals(EMPTY_STRING) ||
              cnUri.equals(rootUri) ||
              cnUri.equalsIgnoreCase(OPEN_ROSA_NAMESPACE) ||
              cnUri.equalsIgnoreCase(OPEN_ROSA_NAMESPACE_SLASH))) {
            return XFormParser.getXMLText(child, true);
          }
        }
      }
    }
    return null;
  }

  public static class FormInstanceMetadata {
    final XFormParameters xparam;
    public final String instanceId; // this may be null
    final String base64EncryptedFieldKey; // this may be null

    FormInstanceMetadata(XFormParameters xparam, String instanceId, String base64EncryptedFieldKey) {
      this.xparam = xparam;
      this.instanceId = instanceId;
      this.base64EncryptedFieldKey = base64EncryptedFieldKey;
    }
  }

  private static final String FORM_ID_ATTRIBUTE_NAME = "id";
  private static final String EMPTY_STRING = "";
  private static final String NAMESPACE_ATTRIBUTE = "xmlns";
  private static final String MODEL_VERSION_ATTRIBUTE_NAME = "version";

  static FormInstanceMetadata getFormInstanceMetadata(Element root) throws ParsingException {
    // check for odk id
    String formId = root.getAttributeValue(null, FORM_ID_ATTRIBUTE_NAME);

    // if odk id is not present use namespace
    if (formId == null || formId.equalsIgnoreCase(EMPTY_STRING)) {
      String schema = root.getAttributeValue(null, NAMESPACE_ATTRIBUTE);

      // TODO: move this into FormDefinition?
      if (schema == null) {
        throw new ParsingException("Unable to extract form id");
      }

      formId = schema;
    }

    String modelVersionString = root.getAttributeValue(null, MODEL_VERSION_ATTRIBUTE_NAME);

    String instanceId = getOpenRosaInstanceId(root);
    if (instanceId == null) {
      instanceId = root.getAttributeValue(null, INSTANCE_ID_ATTRIBUTE_NAME);
    }
    String base64EncryptedFieldKey = getBase64EncryptedFieldKey(root);
    return new FormInstanceMetadata(new XFormParameters(formId, modelVersionString), instanceId, base64EncryptedFieldKey);
  }

  static Document parseXml(File submission) throws ParsingException, FileSystemException {
    // parse the xml document...
    Document doc;
    try (InputStream is = new FileInputStream(submission); InputStreamReader isr = new InputStreamReader(is, UTF_8)) {
      Document tempDoc = new Document();
      KXmlParser parser = new KXmlParser();
      parser.setInput(isr);
      parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
      tempDoc.parse(parser);
      doc = tempDoc;
    } catch (XmlPullParserException e) {
      try {
        return BadXMLFixer.fixBadXML(submission);
      } catch (CannotFixXMLException e1) {
        // We just place the debug file in the same folder as the submission we're processing
        File debugFileLocation = submission.toPath().resolveSibling(submission.toPath().getFileName().toString() + ".debug").toFile();
        try {
          if (!debugFileLocation.exists()) {
            FileUtils.forceMkdir(debugFileLocation);
          }
          long checksum = FileUtils.checksumCRC32(submission);
          File debugFile = new File(debugFileLocation, "submission-" + checksum + ".xml");
          FileUtils.copyFile(submission, debugFile);
        } catch (IOException e2) {
          throw new RuntimeException(e2);
        }
        throw new ParsingException("Failed during parsing of submission Xml: "
            + e.toString());
      }
    } catch (IOException e) {
      throw new FileSystemException("Failed while reading submission xml: "
          + e.toString());
    }
    return doc;
  }

  static SubmissionChunk parseSubmissionDownloadListResponse(Document doc)
      throws ParsingException {
    List<String> uriList = new ArrayList<>();
    String websafeCursorString = "";

    // Attempt parsing
    Element idChunkElement = doc.getRootElement();
    if (!idChunkElement.getName().equals("idChunk")) {
      String msg = "Parsing submissionList reply -- root element is not <idChunk> :"
          + idChunkElement.getName();
      log.error(msg);
      throw new ParsingException(msg);
    }
    String namespace = idChunkElement.getNamespace();
    if (!namespace.equalsIgnoreCase(NAMESPACE_OPENDATAKIT_ORG_SUBMISSIONS)) {
      String msg = "Parsing submissionList reply -- root element namespace is incorrect:"
          + namespace;
      log.error(msg);
      throw new ParsingException(msg);
    }
    int nElements = idChunkElement.getChildCount();
    for (int i = 0; i < nElements; ++i) {
      if (idChunkElement.getType(i) != Element.ELEMENT) {
        // e.g., whitespace (text)
        continue;
      }
      Element subElement = idChunkElement.getElement(i);
      namespace = subElement.getNamespace();
      if (!namespace.equalsIgnoreCase(NAMESPACE_OPENDATAKIT_ORG_SUBMISSIONS)) {
        // someone else's extension?
        continue;
      }
      String name = subElement.getName();
      if (name.equalsIgnoreCase("idList")) {
        // parse the idList
        int nIdElements = subElement.getChildCount();
        for (int j = 0; j < nIdElements; ++j) {
          if (subElement.getType(j) != Element.ELEMENT) {
            // e.g., whitespace (text)
            continue;
          }
          Element idElement = subElement.getElement(j);
          namespace = idElement.getNamespace();
          if (!namespace.equalsIgnoreCase(NAMESPACE_OPENDATAKIT_ORG_SUBMISSIONS)) {
            // someone else's extension?
            continue;
          }
          name = idElement.getName();
          if (name.equalsIgnoreCase("id")) {
            // gather the uri
            String uri = XFormParser.getXMLText(idElement, true);
            if (uri != null) {
              uriList.add(uri);
            }
          } else {
            log.warn("Unrecognized tag inside idList: " + name);
          }
        }
      } else if (name.equalsIgnoreCase("resumptionCursor")) {
        // gather the resumptionCursor
        websafeCursorString = XFormParser.getXMLText(subElement, true);
        if (websafeCursorString == null) {
          websafeCursorString = "";
        }
      } else {
        log.warn("Unrecognized tag inside idChunk: " + name);
      }
    }

    return new SubmissionChunk(uriList, websafeCursorString);
  }

  static String updateSubmissionMetadata(File submissionFile, Document doc)
      throws MetadataUpdateException {

    Element root = doc.getRootElement();
    Element metadata = root.getElement(NAMESPACE_ODK, "submissionMetadata");

    // and get the instanceID and submissionDate from the metadata.
    // we need to put that back into the instance file if not already present
    String instanceID = metadata.getAttributeValue("", INSTANCE_ID_ATTRIBUTE_NAME);
    String submissionDate = metadata.getAttributeValue("", SUBMISSION_DATE_ATTRIBUTE_NAME);

    // read the original document...
    Document originalDoc;
    try {
      FileInputStream fs = new FileInputStream(submissionFile);
      InputStreamReader fr = new InputStreamReader(fs, UTF_8);
      originalDoc = new Document();
      KXmlParser parser = new KXmlParser();
      parser.setInput(fr);
      parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
      originalDoc.parse(parser);
      fr.close();
      fs.close();
    } catch (IOException e) {
      String msg = "Original submission file could not be opened";
      log.error(msg, e);
      throw new MetadataUpdateException(msg);
    } catch (XmlPullParserException e) {
      String msg = "Original submission file could not be parsed as XML file";
      log.error(msg, e);
      throw new MetadataUpdateException(msg);
    }

    // determine whether it has the attributes already added.
    // if they are already there, they better match the values returned by
    // Aggregate 1.0
    boolean hasInstanceID = false;
    boolean hasSubmissionDate = false;
    root = originalDoc.getRootElement();
    for (int i = 0; i < root.getAttributeCount(); ++i) {
      String name = root.getAttributeName(i);
      if (name.equals(INSTANCE_ID_ATTRIBUTE_NAME)) {
        if (!root.getAttributeValue(i).equals(instanceID)) {
          String msg = "Original submission file's instanceID does not match that on server!";
          log.error(msg);
          throw new MetadataUpdateException(msg);
        } else {
          hasInstanceID = true;
        }
      }

      if (name.equals(SUBMISSION_DATE_ATTRIBUTE_NAME)) {
        Date oldDate = parseDate(submissionDate);
        String returnDate = root.getAttributeValue(i);
        Date newDate = parseDate(returnDate);
        // cross-platform datetime resolution is 1 second.
        if (Math.abs(newDate.getTime() - oldDate.getTime()) > 1000L) {
          String msg = "Original submission file's submissionDate does not match that on server!";
          log.error(msg);
          throw new MetadataUpdateException(msg);
        } else {
          hasSubmissionDate = true;
        }
      }
    }

    if (hasInstanceID && hasSubmissionDate) {
      log.info("submission already has instanceID and submissionDate attributes: "
          + submissionFile.getAbsolutePath());
      return instanceID;
    }

    if (!hasInstanceID) {
      root.setAttribute("", INSTANCE_ID_ATTRIBUTE_NAME, instanceID);
    }
    if (!hasSubmissionDate) {
      root.setAttribute("", SUBMISSION_DATE_ATTRIBUTE_NAME, submissionDate);
    }

    // and write out the changes...

    // write the file out...
    File revisedFile = new File(submissionFile.getParentFile(), "." + submissionFile.getName());
    try {
      FileOutputStream fos = new FileOutputStream(revisedFile, false);

      KXmlSerializer serializer = new KXmlSerializer();
      serializer.setOutput(fos, UTF_8.name());
      originalDoc.write(serializer);
      serializer.flush();
      fos.close();

      // and swap files...
      boolean restoreTemp = false;
      File temp = new File(submissionFile.getParentFile(), ".back." + submissionFile.getName());

      try {
        if (temp.exists()) {
          if (!temp.delete()) {
            String msg = "Unable to remove temporary submission backup file";
            log.error(msg);
            throw new MetadataUpdateException(msg);
          }
        }
        if (!submissionFile.renameTo(temp)) {
          String msg = "Unable to rename submission to temporary submission backup file";
          log.error(msg);
          throw new MetadataUpdateException(msg);
        }

        // recovery is possible...
        restoreTemp = true;

        if (!revisedFile.renameTo(submissionFile)) {
          String msg = "Original submission file could not be updated";
          log.error(msg);
          throw new MetadataUpdateException(msg);
        }

        // we're successful...
        restoreTemp = false;
      } finally {
        if (restoreTemp) {
          if (!temp.renameTo(submissionFile)) {
            String msg = "Unable to restore submission from temporary submission backup file";
            log.error(msg);
            throw new MetadataUpdateException(msg);
          }
        }
      }
    } catch (FileNotFoundException e) {
      String msg = "Temporary submission file could not be opened";
      log.error(msg, e);
      throw new MetadataUpdateException(msg);
    } catch (IOException e) {
      String msg = "Temporary submission file could not be written";
      log.error(msg, e);
      throw new MetadataUpdateException(msg);
    }
    return instanceID;
  }
}
