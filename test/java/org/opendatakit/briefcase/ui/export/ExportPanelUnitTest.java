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
package org.opendatakit.briefcase.ui.export;

import static com.github.npathai.hamcrestopt.OptionalMatchers.isEmpty;
import static com.github.npathai.hamcrestopt.OptionalMatchers.isPresent;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.opendatakit.briefcase.export.ExportConfiguration.Builder.empty;
import static org.opendatakit.briefcase.export.ExportConfiguration.Builder.load;
import static org.opendatakit.briefcase.export.ExportForms.buildCustomConfPrefix;
import static org.opendatakit.briefcase.export.ExportForms.buildExportDateTimePrefix;
import static org.opendatakit.briefcase.export.ExportForms.load;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.opendatakit.briefcase.export.ExportConfiguration;
import org.opendatakit.briefcase.export.ExportEvent;
import org.opendatakit.briefcase.export.ExportForms;
import org.opendatakit.briefcase.export.FormDefinition;
import org.opendatakit.briefcase.model.BriefcaseFormDefinition;
import org.opendatakit.briefcase.model.BriefcasePreferences;
import org.opendatakit.briefcase.model.FormStatus;
import org.opendatakit.briefcase.model.FormStatusBuilder;
import org.opendatakit.briefcase.model.InMemoryPreferences;
import org.opendatakit.briefcase.reused.http.FakeHttp;
import org.opendatakit.briefcase.ui.reused.NoOpAnalytics;
import org.opendatakit.briefcase.util.FormCache;

public class ExportPanelUnitTest {

  private ExportConfiguration initialDefaultConf;

  @Test
  public void saves_to_user_preferences_changes_on_the_default_configuration() throws IOException {
    initialDefaultConf = empty().build();
    BriefcasePreferences exportPreferences = new BriefcasePreferences(InMemoryPreferences.empty());
    BriefcasePreferences appPreferences = new BriefcasePreferences(InMemoryPreferences.empty());
    ExportForms forms = load(initialDefaultConf, new ArrayList<>(), exportPreferences, appPreferences);
    ExportPanelForm exportPanelForm = ExportPanelForm.from(forms, appPreferences, initialDefaultConf);
    new ExportPanel(
        forms,
        exportPanelForm,
        appPreferences,
        exportPreferences,
        new NoOpAnalytics(),
        FormCache.empty(),
        new FakeHttp()
    );

    exportPanelForm.setDefaultConf(empty().setExportDir(Paths.get(Files.createTempDirectory("briefcase_test").toUri())).build());

    assertThat(load(exportPreferences).getExportDir(), notNullValue());
  }

  @Test
  public void saves_to_user_preferences_changes_on_a_custom_configuration() throws IOException {
    BriefcasePreferences exportPreferences = new BriefcasePreferences(InMemoryPreferences.empty());
    BriefcasePreferences appPreferences = new BriefcasePreferences(InMemoryPreferences.empty());
    List<FormStatus> formsList = FormStatusBuilder.buildFormStatusList(10);
    initialDefaultConf = empty().build();
    ExportForms forms = load(initialDefaultConf, formsList, exportPreferences, appPreferences);
    ExportPanelForm exportPanelForm = ExportPanelForm.from(forms, appPreferences, initialDefaultConf);
    new ExportPanel(
        forms,
        exportPanelForm,
        appPreferences,
        exportPreferences,
        new NoOpAnalytics(),
        FormCache.empty(),
        new FakeHttp()
    );

    FormStatus form = formsList.get(0);
    String formId = form.getFormDefinition().getFormId();

    ExportConfiguration conf = empty()
        .setExportDir(Paths.get(Files.createTempDirectory("briefcase_test").toUri()))
        .build();

    forms.putConfiguration(form, conf);
    exportPanelForm.getFormsTable().getViewModel().triggerChange();

    assertThat(load(exportPreferences, buildCustomConfPrefix(formId)).getExportDir(), notNullValue());
  }

  @Test
  public void saves_to_user_preferences_the_last_successful_export_date_for_a_form() {
    BriefcasePreferences exportPreferences = new BriefcasePreferences(InMemoryPreferences.empty());
    BriefcasePreferences appPreferences = new BriefcasePreferences(InMemoryPreferences.empty());
    List<FormStatus> formsList = FormStatusBuilder.buildFormStatusList(10);
    initialDefaultConf = empty().build();
    ExportForms forms = load(initialDefaultConf, formsList, exportPreferences, appPreferences);
    new ExportPanel(
        forms,
        ExportPanelForm.from(forms, appPreferences, initialDefaultConf),
        appPreferences,
        exportPreferences,
        new NoOpAnalytics(),
        FormCache.empty(),
        new FakeHttp()
    );

    FormStatus form = formsList.get(0);
    String formId = form.getFormDefinition().getFormId();

    assertThat(exportPreferences.nullSafeGet(buildExportDateTimePrefix(formId)), isEmpty());

    FormDefinition formDef = FormDefinition.from((BriefcaseFormDefinition) form.getFormDefinition());
    ExportEvent event = ExportEvent.successForm(formDef, 10);
    forms.appendStatus(event);

    assertThat(exportPreferences.nullSafeGet(buildExportDateTimePrefix(formId)), isPresent());
  }
}
