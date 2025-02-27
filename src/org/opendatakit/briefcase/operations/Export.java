/*
 * Copyright (C) 2018 Nafundi
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
package org.opendatakit.briefcase.operations;

import static java.time.format.DateTimeFormatter.ISO_DATE_TIME;
import static org.opendatakit.briefcase.export.ExportForms.buildExportDateTimePrefix;
import static org.opendatakit.briefcase.operations.Common.FORM_ID;
import static org.opendatakit.briefcase.operations.Common.STORAGE_DIR;
import static org.opendatakit.briefcase.reused.UncheckedFiles.createDirectories;
import static org.opendatakit.briefcase.reused.http.RequestBuilder.url;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;
import org.opendatakit.briefcase.export.DateRange;
import org.opendatakit.briefcase.export.ExportConfiguration;
import org.opendatakit.briefcase.export.ExportToCsv;
import org.opendatakit.briefcase.export.ExportToGeoJson;
import org.opendatakit.briefcase.export.FormDefinition;
import org.opendatakit.briefcase.model.BriefcaseFormDefinition;
import org.opendatakit.briefcase.model.BriefcasePreferences;
import org.opendatakit.briefcase.model.FormStatus;
import org.opendatakit.briefcase.model.FormStatusEvent;
import org.opendatakit.briefcase.pull.PullForm;
import org.opendatakit.briefcase.pull.PullResult;
import org.opendatakit.briefcase.reused.BriefcaseException;
import org.opendatakit.briefcase.reused.OptionalProduct;
import org.opendatakit.briefcase.reused.RemoteServer;
import org.opendatakit.briefcase.reused.http.CommonsHttp;
import org.opendatakit.briefcase.reused.http.Credentials;
import org.opendatakit.briefcase.reused.job.Job;
import org.opendatakit.briefcase.reused.job.JobsRunner;
import org.opendatakit.briefcase.ui.export.ExportPanel;
import org.opendatakit.briefcase.util.FormCache;
import org.opendatakit.common.cli.Operation;
import org.opendatakit.common.cli.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class Export {
  private static final Logger log = LoggerFactory.getLogger(Export.class);
  private static final Param<Void> EXPORT = Param.flag("e", "export", "Export a form");
  private static final Param<Path> EXPORT_DIR = Param.arg("ed", "export_directory", "Export directory", Paths::get);
  private static final Param<String> FILE = Param.arg("f", "export_filename", "Filename for export operation");
  private static final Param<LocalDate> START = Param.localDate("start", "export_start_date", "Export start date (inclusive)");
  private static final Param<LocalDate> END = Param.localDate("end", "export_end_date", "Export end date (inclusive)");
  private static final Param<Void> EXCLUDE_MEDIA = Param.flag("em", "exclude_media_export", "Exclude media in export");
  private static final Param<Void> OVERWRITE = Param.flag("oc", "overwrite_csv_export", "Overwrite files during export");
  private static final Param<Path> PEM_FILE = Param.arg("pf", "pem_file", "PEM file for form decryption", Paths::get);
  private static final Param<Void> PULL_BEFORE = Param.flag("pb", "pull_before", "Pull before export");
  private static final Param<Void> SPLIT_SELECT_MULTIPLES = Param.flag("ssm", "split_select_multiples", "Split select multiple fields");
  private static final Param<Void> INCLUDE_GEOJSON_EXPORT = Param.flag("ig", "include_geojson", "Include a GeoJSON file with spatial data");
  private static final Param<Void> REMOVE_GROUP_NAMES = Param.flag("rgn", "remove_group_names", "Remove group names from column names");

  public static Operation EXPORT_FORM = Operation.of(
      EXPORT,
      args -> export(
          args.get(STORAGE_DIR),
          args.get(FORM_ID),
          args.get(EXPORT_DIR),
          args.get(FILE),
          !args.has(EXCLUDE_MEDIA),
          args.has(OVERWRITE),
          args.has(PULL_BEFORE),
          args.getOptional(START),
          args.getOptional(END),
          args.getOptional(PEM_FILE),
          args.has(SPLIT_SELECT_MULTIPLES),
          args.has(INCLUDE_GEOJSON_EXPORT),
          args.has(REMOVE_GROUP_NAMES)
      ),
      Arrays.asList(STORAGE_DIR, FORM_ID, FILE, EXPORT_DIR),
      Arrays.asList(PEM_FILE, EXCLUDE_MEDIA, OVERWRITE, START, END, PULL_BEFORE, SPLIT_SELECT_MULTIPLES, INCLUDE_GEOJSON_EXPORT, REMOVE_GROUP_NAMES)
  );

  public static void export(String storageDir, String formid, Path exportDir, String baseFilename, boolean exportMedia, boolean overwriteFiles, boolean pullBefore, Optional<LocalDate> startDate, Optional<LocalDate> endDate, Optional<Path> maybePemFile, boolean splitSelectMultiples, boolean includeGeoJsonExport, boolean removeGroupNames) {
    CliEventsCompanion.attach(log);
    Path briefcaseDir = Common.getOrCreateBriefcaseDir(storageDir);
    FormCache formCache = FormCache.from(briefcaseDir);
    formCache.update();
    Optional<BriefcaseFormDefinition> maybeFormDefinition = formCache.getForms().stream()
        .filter(form -> form.getFormId().equals(formid))
        .findFirst();

    createDirectories(exportDir);

    BriefcaseFormDefinition formDefinition = maybeFormDefinition.orElseThrow(() -> new FormNotFoundException(formid));

    System.out.println("Exporting form " + formDefinition.getFormName() + " (" + formDefinition.getFormId() + ") to: " + exportDir);
    DateRange dateRange = new DateRange(startDate, endDate);
    ExportConfiguration configuration = ExportConfiguration.Builder.empty()
        .setExportFilename(baseFilename)
        .setExportDir(exportDir)
        .setPemFile(maybePemFile)
        .setDateRange(dateRange)
        .setPullBefore(pullBefore)
        .setOverwriteFiles(overwriteFiles)
        .setExportMedia(exportMedia)
        .setSplitSelectMultiples(splitSelectMultiples)
        .setRemoveGroupNames(removeGroupNames)
        .build();

    Job<PullResult> pullJob = Job.noOpSupplier();
    if (configuration.resolvePullBefore()) {
      BriefcasePreferences appPreferences = BriefcasePreferences.appScoped();
      FormStatus formStatus = new FormStatus(formDefinition);

      String urlKey = String.format("%s_pull_settings_url", formid);
      String usernameKey = String.format("%s_pull_settings_username", formid);
      String passwordKey = String.format("%s_pull_settings_password", formid);

      if (appPreferences.hasKey(urlKey)) {
        RemoteServer server = new RemoteServer(
            url(appPreferences.nullSafeGet(urlKey).orElseThrow(BriefcaseException::new)),
            OptionalProduct.all(
                appPreferences.nullSafeGet(usernameKey),
                appPreferences.nullSafeGet(passwordKey)
            ).map(Credentials::from)
        );

        pullJob = PullForm.pull(CommonsHttp.reusing(), server, briefcaseDir, false, Export::onEvent, formStatus);
      }
    }
    FormDefinition formDef = FormDefinition.from(formDefinition);
    Job<Void> exportJob = Job.run(runnerStatus -> ExportToCsv.export(formDef, configuration));

    Job<Void> exportGeoJsonJob = configuration.resolveIncludeGeoJsonExport()
        ? Job.run(runnerStatus -> ExportToGeoJson.export(formDef, configuration))
        : Job.noOp;

    Job<Void> job = pullJob
        .thenRun(exportJob)
        .thenRun(exportGeoJsonJob)
        .thenRun(__ -> BriefcasePreferences.forClass(ExportPanel.class).put(
            buildExportDateTimePrefix(formDefinition.getFormId()),
            LocalDateTime.now().format(ISO_DATE_TIME)
        ));

    new JobsRunner<Void>()
        .onError(Export::onError)
        .onSuccess(__ -> {
          System.out.println();
          System.out.println("Successfully exported all forms");
          log.info("Successfully exported all forms");
        })
        .launchSync(Stream.of(job));
  }

  private static void onError(Throwable e) {
    System.err.println("Error pulling form");
    log.error("Error pulling form", e);
  }

  private static void onEvent(FormStatusEvent formStatusEvent) {
    System.out.println(formStatusEvent.getStatusString());
  }
}
