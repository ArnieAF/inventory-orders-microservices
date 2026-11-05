package com.microinv.auth.common;

/** Error de negocio con codigo del contrato; se traduce al formato comun en {@link GlobalExceptionHandler}. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code) {
        // Sin stack trace: son errores esperados, no fallos del programa.
        super(code.defaultMessage(), null, false, false);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
