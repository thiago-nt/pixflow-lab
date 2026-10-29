package dev.pixflow.lab.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public OutboxRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public List<ClaimedOutboxEvent> claim(String owner, int batchSize, Duration ttl) {
        return transactions.execute(status -> jdbc.query("""
                WITH candidates AS (
                    SELECT event_id FROM outbox_events
                    WHERE published_at IS NULL
                      AND next_attempt_at <= clock_timestamp()
                      AND (claimed_until IS NULL OR claimed_until < clock_timestamp())
                    ORDER BY created_at, event_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE outbox_events event
                   SET claimed_by = ?, claim_token = gen_random_uuid(),
                       claimed_until = clock_timestamp() + (? * interval '1 millisecond'),
                       attempt_count = attempt_count + 1
                  FROM candidates
                 WHERE event.event_id = candidates.event_id
                RETURNING event.*
                """, this::map, batchSize, owner, ttl.toMillis()));
    }

    public boolean markPublished(ClaimedOutboxEvent event) {
        return jdbc.update("""
                UPDATE outbox_events
                   SET published_at=clock_timestamp(), claimed_by=NULL, claim_token=NULL,
                       claimed_until=NULL, last_error=NULL
                 WHERE event_id=? AND claimed_by=? AND claim_token=?
                   AND published_at IS NULL AND claimed_until > clock_timestamp()
                """, event.eventId(), event.claimedBy(), event.claimToken()) == 1;
    }

    public boolean markFailed(ClaimedOutboxEvent event, String error, Duration backoff) {
        return jdbc.update("""
                UPDATE outbox_events
                   SET claimed_by=NULL, claim_token=NULL, claimed_until=NULL,
                       last_error=?, next_attempt_at=clock_timestamp() + (? * interval '1 millisecond')
                 WHERE event_id=? AND claimed_by=? AND claim_token=? AND published_at IS NULL
                """, sanitize(error), backoff.toMillis(), event.eventId(), event.claimedBy(), event.claimToken()) == 1;
    }

    public long pendingCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Long.class);
        return count == null ? 0 : count;
    }

    private ClaimedOutboxEvent map(ResultSet rs, int row) throws SQLException {
        return new ClaimedOutboxEvent(rs.getObject("event_id", java.util.UUID.class),
                rs.getObject("aggregate_id", java.util.UUID.class), rs.getLong("aggregate_version"),
                rs.getString("topic"), rs.getString("event_type"), rs.getString("payload_json"),
                rs.getString("partition_key"), rs.getTimestamp("created_at").toInstant(),
                rs.getString("claimed_by"), rs.getObject("claim_token", java.util.UUID.class),
                rs.getTimestamp("claimed_until").toInstant(), rs.getInt("attempt_count"));
    }

    private static String sanitize(String error) {
        if (error == null) return "unknown publish failure";
        var singleLine = error.replaceAll("[\\r\\n\\t]+", " ");
        return singleLine.substring(0, Math.min(singleLine.length(), 1000));
    }
}
