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

package org.opendatakit.briefcase.push;

import java.util.Optional;
import org.opendatakit.briefcase.model.ServerConnectionInfo;
import org.opendatakit.briefcase.transfer.TransferForms;

public class PushEvent {

  public static class Failure extends PushEvent {
    public Failure() {
    }
  }

  public static class Success extends PushEvent {
    public TransferForms forms;
    public final Optional<ServerConnectionInfo> transferSettings;

    public Success(TransferForms forms, ServerConnectionInfo transferSettings) {
      this.forms = forms;
      this.transferSettings = Optional.ofNullable(transferSettings);
    }
  }

  public static class Cancel extends PushEvent {
    public final String cause;

    public Cancel(String cause) {
      this.cause = cause;
    }
  }
}
