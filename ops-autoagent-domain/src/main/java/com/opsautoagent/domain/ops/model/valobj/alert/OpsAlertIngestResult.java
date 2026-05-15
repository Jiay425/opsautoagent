package com.opsautoagent.domain.ops.model.valobj.alert;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsAlertIngestResult {

    private int totalAlerts;

    private int acceptedCount;

    private int skippedCount;

}

