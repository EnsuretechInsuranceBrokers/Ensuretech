package com.broker.billing.grpc;

import com.broker.billing.grpc.gen.*;
import com.broker.billing.service.CdLedgerService;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CdBalanceGrpcService extends CdBalanceGrpc.CdBalanceImplBase {
    private final CdLedgerService ledger;

    CdBalanceGrpcService(CdLedgerService ledger) {
        this.ledger = ledger;
    }

    @Override
    public void checkCdBalance(CdBalanceRequest req, StreamObserver<CdBalanceResponse> out) {
        try {
            if (req.getBrokerId() <= 0) throw bad("broker_id is required");
            if (req.getPolicyId() <= 0) throw bad("policy_id is required");
            UUID job;
            BigDecimal required;
            try {
                job = UUID.fromString(req.getJobId());
            } catch (IllegalArgumentException e) {
                throw bad("job_id must be a UUID");
            }
            try {
                required = new BigDecimal(req.getRequiredAmount());
            } catch (NumberFormatException e) {
                throw bad("required_amount is not a decimal");
            }
            if (required.abs().compareTo(new BigDecimal("1e13")) >= 0) throw bad("required_amount out of range");
            var r = ledger.check(req.getBrokerId(), req.getPolicyId(), job, required);
            out.onNext(CdBalanceResponse.newBuilder().setStatus(r.status()).setAvailableBalance(r.available().toPlainString())
                    .setRequiredAmount(r.required().toPlainString()).setDeficitAmount(r.deficit().toPlainString())
                    .setReservedAmount(r.reserved().toPlainString()).build());
            out.onCompleted();
        } catch (StatusRuntimeException e) {
            out.onError(e);
        } catch (RuntimeException e) {
            out.onError(Status.INTERNAL.withDescription("internal error").asRuntimeException());
        }
    }

    private static StatusRuntimeException bad(String m) {
        return Status.INVALID_ARGUMENT.withDescription(m).asRuntimeException();
    }
}
