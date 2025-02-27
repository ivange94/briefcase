/*
 * Copyright (C) 2019 Nafundi
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

package org.opendatakit.briefcase.pull;

import static org.opendatakit.briefcase.reused.http.RequestBuilder.url;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.xml.bind.DatatypeConverter;
import org.opendatakit.briefcase.reused.BriefcaseException;
import org.opendatakit.briefcase.reused.UncheckedFiles;

public class MediaFile {
  private final String filename;
  private final String hash;
  private final URL downloadUrl;

  private MediaFile(String filename, String hash, URL downloadUrl) {
    this.filename = filename;
    this.hash = hash;
    this.downloadUrl = downloadUrl;
  }

  public static MediaFile of(String filename, String hash, String downloadUrl) {
    return new MediaFile(filename, hash, url(downloadUrl));
  }

  private static String md5(Path file) {
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      md.update(UncheckedFiles.readAllBytes(file));
      byte[] digest = md.digest();
      return DatatypeConverter.printHexBinary(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new BriefcaseException(e);
    }
  }

  boolean needsUpdate(Path mediaDir) {
    Path targetPath = mediaDir.resolve(filename);
    return !hash.startsWith("md5:") || // we didn't get an MD5 hash
        !Files.exists(targetPath) || // There's no local file at the target path
        !hash.equalsIgnoreCase("md5:" + md5(targetPath)); // The local file's hash doesn't match what we expected
  }

  Path getTargetPath(Path mediaDir) {
    return mediaDir.resolve(filename);
  }

  URL getDownloadUrl() {
    return downloadUrl;
  }
}
