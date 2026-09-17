package com.aesoftwaresolutions.solid.audit;

import com.aesoftwaresolutions.solid.common.Ids;
import com.aesoftwaresolutions.solid.platform.RequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes audit events. Each event's hash covers its content and the previous event's hash.
 *
 * <p>Never put passwords, MFA codes, session tokens, secrets or full tax IDs in {@code details}.
 * Keys that look sensitive are rejected to make that mistake loud.
 */
@Service
public class AuditLog {

    private static final Set<String> FORBIDDEN_KEYS = Set.of("password", "code", "token", "secret", "ssn", "tin",
            "recoverycode", "otp");
    private static final String GENESIS = "0".repeat(64);
    /** Postgres advisory lock id used to serialise appends so the chain never forks. */
    private static final long CHAIN_LOCK = 0x50_4C_49_44_41_55_44L;

    private final JdbcClient db;
    private final ObjectMapper json;

    AuditLog(JdbcClient db, ObjectMapper mapper) {
        this.db = db;
        this.json = mapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public record Actor(UUID userId, String ip) {
        public static final Actor SYSTEM = new Actor(null, null);

        /** The user making the current HTTP request, or SYSTEM outside a request. */
        public static Actor current() {
            return RequestContext.current().map(c -> new Actor(c.userId(), c.ip())).orElse(SYSTEM);
        }
    }

    /**
     * Records an event. Runs in its own transaction so failed actions (e.g. wrong password) are still logged
     * even when the caller's transaction rolls back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Actor actor, UUID orgId, String action, String objectType, UUID objectId,
                       Map<String, ?> details) {
        Map<String, Object> safe = new TreeMap<>();
        if (details != null) {
            details.forEach((k, v) -> {
                if (FORBIDDEN_KEYS.contains(k.toLowerCase().replace("_", ""))) {
                    throw new IllegalArgumentException("Audit details must not contain sensitive key: " + k);
                }
                safe.put(k, v);
            });
        }
        String detailsJson = toJson(safe);

        db.sql("select pg_advisory_xact_lock(?)").param(CHAIN_LOCK).query().singleRow();
        String prevHash = db.sql("select hash from audit.event order by seq desc limit 1")
                .query(String.class).optional().orElse(GENESIS);

        UUID id = Ids.newId();
        String hash = sha256(String.join("|", prevHash, id.toString(), String.valueOf(orgId),
                String.valueOf(actor.userId()), String.valueOf(actor.ip()), action, String.valueOf(objectType),
                String.valueOf(objectId), detailsJson));
        db.sql("""
                insert into audit.event (id, org_id, actor_user_id, actor_ip, action, object_type, object_id, details, prev_hash, hash)
                values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?)""")
                .params(id, orgId, actor.userId(), actor.ip(), action, objectType, objectId, detailsJson, prevHash, hash)
                .update();
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> forOrg(UUID orgId, int limit) {
        return db.sql("""
                select seq, id, occurred_at, org_id, actor_user_id, actor_ip, action, object_type, object_id, details::text as details
                from audit.event where org_id = ? order by seq desc limit ?""")
                .params(orgId, Math.min(Math.max(limit, 1), 500))
                .query((rs, n) -> new AuditEvent(rs.getLong("seq"), rs.getObject("id", UUID.class),
                        rs.getObject("occurred_at", java.time.OffsetDateTime.class), rs.getObject("org_id", UUID.class),
                        rs.getObject("actor_user_id", UUID.class), rs.getString("actor_ip"), rs.getString("action"),
                        rs.getString("object_type"), rs.getObject("object_id", UUID.class), fromJson(rs.getString("details"))))
                .list();
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> forUser(UUID userId, int limit) {
        return db.sql("""
                select seq, id, occurred_at, org_id, actor_user_id, actor_ip, action, object_type, object_id, details::text as details
                from audit.event where actor_user_id = ? or (object_type = 'user' and object_id = ?) order by seq desc limit ?""")
                .params(userId, userId, Math.min(Math.max(limit, 1), 500))
                .query((rs, n) -> new AuditEvent(rs.getLong("seq"), rs.getObject("id", UUID.class),
                        rs.getObject("occurred_at", java.time.OffsetDateTime.class), rs.getObject("org_id", UUID.class),
                        rs.getObject("actor_user_id", UUID.class), rs.getString("actor_ip"), rs.getString("action"),
                        rs.getString("object_type"), rs.getObject("object_id", UUID.class), fromJson(rs.getString("details"))))
                .list();
    }

    /** Recomputes the whole chain. Returns the first broken sequence number, or null if intact. */
    @Transactional(readOnly = true)
    public Long verifyChain() {
        String prev = GENESIS;
        List<Map<String, Object>> rows = db.sql("""
                select seq, id, org_id, actor_user_id, actor_ip, action, object_type, object_id, details::text as details,
                       prev_hash, hash from audit.event order by seq""").query().listOfRows();
        for (Map<String, Object> r : rows) {
            String detailsJson = toJson(new TreeMap<>(fromJson((String) r.get("details"))));
            String expected = sha256(String.join("|", prev, r.get("id").toString(), String.valueOf(r.get("org_id")),
                    String.valueOf(r.get("actor_user_id")), String.valueOf(r.get("actor_ip")), (String) r.get("action"),
                    String.valueOf(r.get("object_type")), String.valueOf(r.get("object_id")), detailsJson));
            if (!prev.equals(r.get("prev_hash")) || !expected.equals(r.get("hash"))) {
                return ((Number) r.get("seq")).longValue();
            }
            prev = (String) r.get("hash");
        }
        return null;
    }

    private String toJson(Map<String, Object> map) {
        try {
            return json.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Audit details must be JSON-serialisable", e);
        }
    }

    private Map<String, Object> fromJson(String s) {
        try {
            return json.readValue(s, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
