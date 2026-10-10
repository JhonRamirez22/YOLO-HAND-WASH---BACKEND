package com.handwash.api.v1.dto;

import java.util.List;

public record ProtocolProfileOverviewResponse(ProtocolProfileResponse vigente,
                                              List<ProtocolProfileResponse> historial) {}
