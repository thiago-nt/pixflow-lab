package dev.pixflow.lab.payment.infrastructure.persistence;

import dev.pixflow.lab.payment.application.*;
import dev.pixflow.lab.payment.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcPaymentStore implements PaymentStore {
    private final JdbcTemplate jdbc;
    public JdbcPaymentStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<StoredIdempotency> findIdempotency(String accountId,String digest){return jdbc.query("select request_hash,payment_id from idempotency_keys where tenant_id=? and key_digest=?",(rs,n)->new StoredIdempotency(rs.getString(1),rs.getObject(2,UUID.class)),accountId,digest).stream().findFirst();}
    public void insertPayment(Payment p,String hash){jdbc.update("insert into payments(payment_id,tenant_id,request_hash,amount_minor,currency,status,external_reference,merchant_reference,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?)",p.id(),p.accountId(),hash,p.money().minorUnits(),p.money().currency(),p.status().name(),"pxf-"+p.id(),p.merchantReference(),Timestamp.from(p.createdAt()),Timestamp.from(p.updatedAt()));}
    public void insertIdempotency(String accountId,String digest,String hash,UUID paymentId){jdbc.update("insert into idempotency_keys(tenant_id,key_digest,request_hash,payment_id,state,expires_at) values (?,?,?,?,?,?)",accountId,digest,hash,paymentId,"COMPLETED",Timestamp.from(Instant.now().plus(30,ChronoUnit.DAYS)));}
    public void insertOutbox(UUID eventId,Payment p,String correlationId,String payload){jdbc.update("insert into outbox_events(event_id,aggregate_id,aggregate_version,topic,event_type,payload_json,partition_key) values (?,?,1,'payment.requested.v1','payment.requested',?::jsonb,?)",eventId,p.id(),payload,p.id().toString());}
    public void insertAudit(UUID paymentId,UUID eventId,String correlationId){jdbc.update("insert into payment_audit(payment_id,previous_status,new_status,fencing_token,event_id,correlation_id,reason) values (?,null,'PENDING',0,?,?,'PAYMENT_CREATED')",paymentId,eventId,correlationId);}
    public Optional<Payment> findPayment(UUID id){return jdbc.query("select * from payments where payment_id=?",this::payment,id).stream().findFirst();}
    public PaymentPage findPayments(int page,int size){var items=jdbc.query("select * from payments order by created_at desc,payment_id desc limit ? offset ?",this::payment,size,(long)page*size);Long total=jdbc.queryForObject("select count(*) from payments",Long.class);return new PaymentPage(items,page,size,total==null?0:total);}
    public LabPaymentView findLabView(UUID id){
        var payment=findPayment(id).orElseThrow(()->new PaymentNotFoundException(id));
        var key=jdbc.query("select key_digest from idempotency_keys where payment_id=?",(rs,n)->rs.getString(1),id).stream().findFirst().orElse("");
        var outbox=jdbc.query("select event_id,topic,event_type,created_at,published_at,claimed_by,claimed_until,attempt_count,last_error from outbox_events where aggregate_id=? order by created_at",(rs,n)->new LabPaymentView.OutboxView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),instant(rs.getTimestamp(4)),instant(rs.getTimestamp(5)),rs.getString(6),instant(rs.getTimestamp(7)),rs.getInt(8),rs.getString(9)),id);
        var audit=jdbc.query("select audit_id,previous_status,new_status,event_id,occurred_at,correlation_id,reason from payment_audit where payment_id=? order by occurred_at",(rs,n)->new LabPaymentView.AuditView(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getObject(4,UUID.class),instant(rs.getTimestamp(5)),rs.getString(6),rs.getString(7)),id);
        return new LabPaymentView(payment,key.isEmpty()?"":key.substring(0,8)+"…",outbox,audit);
    }
    private Payment payment(ResultSet rs,int row) throws SQLException{return new Payment(rs.getObject("payment_id",UUID.class),rs.getString("tenant_id"),new Money(java.math.BigDecimal.valueOf(rs.getLong("amount_minor"),2),rs.getString("currency").trim()),rs.getString("merchant_reference"),PaymentStatus.valueOf(rs.getString("status")),instant(rs.getTimestamp("created_at")),instant(rs.getTimestamp("updated_at")));}
    private static Instant instant(Timestamp timestamp){return timestamp==null?null:timestamp.toInstant();}
}
