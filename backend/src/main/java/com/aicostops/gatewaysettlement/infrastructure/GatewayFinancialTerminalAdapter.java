package com.aicostops.gatewaysettlement.infrastructure;

import com.aicostops.gatewaysettlement.application.GatewayFinancialTerminalPort;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Component;

/** Bounded read of M15 gateway_financial_resolution without reconciliation types. */
@Component
public class GatewayFinancialTerminalAdapter implements GatewayFinancialTerminalPort {

    private final TerminalResolutionMapper mapper;

    public GatewayFinancialTerminalAdapter(TerminalResolutionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean hasTerminalResolution(long organizationId, long requestId) {
        return mapper.countResolution(organizationId, requestId) > 0;
    }

    @Mapper
    public interface TerminalResolutionMapper {

        @Select("""
                SELECT COUNT(*)
                FROM gateway_financial_resolution
                WHERE org_id=#{organizationId} AND request_id=#{requestId}
                """)
        long countResolution(
                @Param("organizationId") long organizationId,
                @Param("requestId") long requestId);
    }
}
