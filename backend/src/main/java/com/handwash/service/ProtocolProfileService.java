package com.handwash.service;

import com.handwash.model.WashStep;
import com.handwash.model.ProfileChange;
import com.handwash.model.ProtocolProfile;
import com.handwash.repository.ChangeControlRepository;
import com.handwash.repository.ProtocolProfileRepository;
import com.handwash.strategy.ProtocolProfileProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * Owns the profile version in force. A single arbiter task caches it so session
 * creation never waits on the database; edits append a version and swap the cache.
 */
@Service
public class ProtocolProfileService implements ProtocolProfileProvider {
    /** Raised when the creator tries to approve their own change while a second approver is required. */
    public static final class SameApproverException extends RuntimeException {
        public SameApproverException() { super("MISMO_ADMIN"); }
    }

    private final ProtocolProfileRepository repository;
    private final ChangeControlRepository settings;
    private final SessionAuditService audit;
    private final TransactionTemplate transaction;
    private volatile ProtocolProfile vigente;

    public ProtocolProfileService(ProtocolProfileRepository repository, ChangeControlRepository settings,
                                  SessionAuditService audit, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.settings = settings;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public ProtocolProfile perfilVigente() {
        ProtocolProfile actual = vigente;
        if (actual != null) return actual;
        synchronized (this) {
            if (vigente == null) {
                vigente = repository.findLatest().orElse(ProtocolProfile.PREDETERMINADO);
            }
            return vigente;
        }
    }

    public List<ProtocolProfile> historial(int limit) {
        return repository.findRecent(limit);
    }

    public List<ProfileChange> cambios(int limit) {
        return repository.findRecentChanges(limit);
    }

    /**
     * Appends a version with its reason. With a second approver required the version waits as PENDING and
     * the profile in force does not change until another ADMIN approves it.
     */
    public synchronized ProfileChange crearVersion(Map<WashStep, Long> tiemposPasoMs, long umbralLentoMs,
                                                  String autor, String motivo) {
        return append(tiemposPasoMs, umbralLentoMs, autor, ChangeReason.normalize(motivo), null);
    }

    /** One-click rollback: a NEW version that copies an earlier one that was in force. */
    public synchronized ProfileChange revertir(int version, String autor, String motivo) {
        String reason = ChangeReason.normalize(motivo);
        ProfileChange origen = repository.findChange(version).orElseThrow(() -> new IllegalArgumentException("VERSION_INEXISTENTE"));
        if (!ProfileChange.ACTIVA.equals(origen.estado())) throw new IllegalArgumentException("VERSION_NO_VIGENTE_ALGUNA_VEZ");
        return append(origen.perfil().tiemposPasoMs(), origen.perfil().umbralLentoMs(), autor, reason, version);
    }

    public synchronized ProfileChange aprobar(int version, String aprobador) {
        ProfileChange cambio = repository.findChange(version).orElseThrow(() -> new IllegalArgumentException("VERSION_INEXISTENTE"));
        if (!ProfileChange.PENDIENTE.equals(cambio.estado())) throw new IllegalArgumentException("VERSION_NO_PENDIENTE");
        if (settings.settings().secondApproverRequired() && aprobador.equals(cambio.perfil().creadoPor())) {
            throw new SameApproverException();
        }
        long now = System.currentTimeMillis();
        ProfileChange aprobado = transaction.execute(status -> {
            if (!repository.decide(version, ProfileChange.ACTIVA, aprobador, now)) throw new IllegalArgumentException("VERSION_NO_PENDIENTE");
            audit.recordStationEvent(null, aprobador, "PROTOCOL_PROFILE_APPROVED", "APPROVED", "VERSION_" + version);
            return repository.findChange(version).orElseThrow();
        });
        if (vigente == null || aprobado.perfil().version() > vigente.version()) vigente = aprobado.perfil();
        return aprobado;
    }

    public synchronized ProfileChange rechazar(int version, String admin) {
        ProfileChange cambio = repository.findChange(version).orElseThrow(() -> new IllegalArgumentException("VERSION_INEXISTENTE"));
        if (!ProfileChange.PENDIENTE.equals(cambio.estado())) throw new IllegalArgumentException("VERSION_NO_PENDIENTE");
        return transaction.execute(status -> {
            if (!repository.decide(version, ProfileChange.RECHAZADA, admin, System.currentTimeMillis())) {
                throw new IllegalArgumentException("VERSION_NO_PENDIENTE");
            }
            audit.recordStationEvent(null, admin, "PROTOCOL_PROFILE_REJECTED", "REJECTED", "VERSION_" + version);
            return repository.findChange(version).orElseThrow();
        });
    }

    /** Version and audit commit together; running sessions keep their own strategy. */
    private ProfileChange append(Map<WashStep, Long> tiempos, long umbral, String autor, String motivo, Integer revertidoDe) {
        boolean needsApproval = settings.settings().secondApproverRequired();
        ProfileChange creado = transaction.execute(status -> {
            ProtocolProfile base = repository.findLatest().orElse(ProtocolProfile.PREDETERMINADO);
            ProtocolProfile siguiente = new ProtocolProfile(Math.addExact(repository.highestVersion(), 1), tiempos, umbral,
                autor, System.currentTimeMillis());
            // Validates ranges exactly like a normal edit would.
            base.siguienteVersion(tiempos, umbral, autor, siguiente.creadoEnEpochMs());
            String estado = needsApproval ? ProfileChange.PENDIENTE : ProfileChange.ACTIVA;
            repository.insert(siguiente, motivo, revertidoDe, estado);
            audit.recordStationEvent(null, autor, needsApproval ? "PROTOCOL_PROFILE_VERSION_PENDING"
                : revertidoDe == null ? "PROTOCOL_PROFILE_VERSION_CREATED" : "PROTOCOL_PROFILE_REVERTED",
                needsApproval ? "PENDING" : "CREATED", "VERSION_" + siguiente.version());
            return repository.findChange(siguiente.version()).orElseThrow();
        });
        if (ProfileChange.ACTIVA.equals(creado.estado())) vigente = creado.perfil();
        return creado;
    }
}
