package com.microinv.auth.config;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Limita el tamano de los JSON de entrada ANTES de validar campos: los cuerpos legitimos de este
 * servicio miden unos pocos cientos de bytes, asi que un documento enorme se rechaza (400) sin
 * llegar a cargarse entero en memoria.
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    static final int MAX_DOCUMENT_CHARS = 8_192;

    @Bean
    Jackson2ObjectMapperBuilderCustomizer boundedJsonInput() {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxDocumentLength(MAX_DOCUMENT_CHARS)
                .maxStringLength(MAX_DOCUMENT_CHARS)
                .maxNestingDepth(16)
                .build();
        return builder -> builder.factory(JsonFactory.builder().streamReadConstraints(constraints).build());
    }
}
