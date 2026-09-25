package com.handwash.service;

import com.handwash.repository.ChangeControlRepository;
import com.handwash.repository.ChangeControlRepository.ModelRow;
import com.handwash.repository.ChangeControlRepository.Report;
import com.handwash.repository.ChangeControlRepository.Settings;
import com.handwash.repository.ProtocolProfileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Model and profile governance: which model releases exist, whether any validation report backs them
 * ("sin evidencia" otherwise) and the second-approver switch. A report is evidence that a measurement
 * exists, never an approval: the clinical decision policy stays off whatever is registered here.
 */
@Service
public class ChangeControlService {
    public static final String SIN_EVIDENCIA = "SIN_EVIDENCIA";
    public static final String CON_INFORME = "CON_INFORME";
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    public record ModelStatus(ModelRow model, String evidencia) {}

    private final ChangeControlRepository repository;
    private final ProtocolProfileRepository profiles;
    private final SessionAuditService audit;
    private final LongSupplier clockMillis;
    private final String configuredModelVersion;
    private final String configuredModelSha;
    private final String configuredDatasetVersion;

    @Autowired
    public ChangeControlService(ChangeControlRepository repository, ProtocolProfileRepository profiles, SessionAuditService audit,
                                @Value("${handwash.audit.model-version:}") String modelVersion,
                                @Value("${handwash.audit.model-sha256:}") String modelSha,
                                @Value("${handwash.audit.dataset-version:}") String datasetVersion) {
        this(repository, profiles, audit, System::currentTimeMillis, modelVersion, modelSha, datasetVersion);
    }

    ChangeControlService(ChangeControlRepository repository, ProtocolProfileRepository profiles, SessionAuditService audit,
                         LongSupplier clockMillis, String modelVersion, String modelSha, String datasetVersion) {
        this.repository = repository;
        this.profiles = profiles;
        this.audit = audit;
        this.clockMillis = clockMillis;
        this.configuredModelVersion = modelVersion;
        this.configuredModelSha = modelSha;
        this.configuredDatasetVersion = datasetVersion;
    }

    public Settings settings() { return repository.settings(); }

    public Settings setSecondApproverRequired(boolean required, String actor) {
        repository.setSecondApproverRequired(required, actor, clockMillis.getAsLong());
        audit.recordStationEvent(null, actor, "CHANGE_CONTROL_SETTINGS_CHANGED", required ? "SECOND_APPROVER_ON" : "SECOND_APPROVER_OFF", null);
        return repository.settings();
    }

    /** Records the release this server declares, once; called at start-up so a new model is visible before any wash. */
    public boolean registerConfiguredRelease() {
        if (!HEX64.matcher(configuredModelSha.toLowerCase()).matches() || configuredModelVersion.isBlank()) return false;
        boolean created = repository.registerRelease(configuredModelSha.toLowerCase(), configuredModelVersion,
            configuredDatasetVersion.isBlank() ? "UNKNOWN" : configuredDatasetVersion, clockMillis.getAsLong());
        if (created) audit.recordStationEvent(null, "SYSTEM", "MODEL_RELEASE_REGISTERED", "REGISTERED", "SHA_" + configuredModelSha.substring(0, 12));
        return created;
    }

    public List<ModelStatus> models() {
        return repository.models().stream()
            .map(row -> new ModelStatus(row, row.reports() > 0 ? CON_INFORME : SIN_EVIDENCIA)).toList();
    }

    public List<Report> reports(int limit) { return repository.reports(limit); }

    public boolean profileHasReport(int version) { return repository.profileHasReport(version); }

    public Report registerReport(String modelSha256, Integer profileVersion, String title, String summary,
                                 String documentSha256, String actor) {
        String model = modelSha256 == null ? "" : modelSha256.toLowerCase();
        String document = documentSha256 == null ? "" : documentSha256.toLowerCase();
        if (!HEX64.matcher(model).matches() || !HEX64.matcher(document).matches()) throw new IllegalArgumentException("HASH_INVALIDO");
        String cleanTitle = title == null ? "" : title.strip();
        String cleanSummary = summary == null ? "" : summary.strip();
        if (cleanTitle.length() < 3 || cleanTitle.length() > 120 || cleanSummary.length() < 10 || cleanSummary.length() > 500
            || (cleanTitle + cleanSummary).chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("INFORME_INVALIDO");
        }
        if (repository.models().stream().noneMatch(row -> row.modelSha256().equals(model))) throw new IllegalArgumentException("MODELO_DESCONOCIDO");
        if (profileVersion != null && profiles.findChange(profileVersion).isEmpty()) throw new IllegalArgumentException("VERSION_INEXISTENTE");
        long id = repository.addReport(model, profileVersion, cleanTitle, cleanSummary, document, actor, clockMillis.getAsLong());
        audit.recordStationEvent(null, actor, "VALIDATION_REPORT_REGISTERED", "REGISTERED", "REPORT_" + id);
        return repository.reports(1).get(0);
    }
}
