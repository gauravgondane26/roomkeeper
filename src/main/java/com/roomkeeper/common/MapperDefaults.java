package com.roomkeeper.common;

import org.mapstruct.MapperConfig;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * Shared settings for every MapStruct mapper: mappers are Spring beans, and a response
 * field that nothing maps to fails the build instead of silently coming back null.
 * <p>
 * Mappers are how entities become API responses. Services return the responses, so
 * entities never leave the service layer.
 */
@MapperConfig(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface MapperDefaults {
}
