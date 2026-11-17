package com.wan.downloadmanager.exception;

/** Application error with a stable code and an HTTP status for the local API. */
public class WdmException extends RuntimeException {
    private final String code;
    private final int status;

    public WdmException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public String code() { return code; }
    public int status() { return status; }

    public static WdmException badRequest(String code, String message) { return new WdmException(400, code, message); }
    public static WdmException forbidden(String code, String message) { return new WdmException(403, code, message); }
    public static WdmException notFound(String what) { return new WdmException(404, "not_found", what + " not found"); }
    public static WdmException conflict(String code, String message) { return new WdmException(409, code, message); }
    /** A premium feature the current entitlement does not include. */
    public static WdmException entitlement(String feature) {
        return new WdmException(402, "entitlement_required", "This feature needs a plan that includes " + feature);
    }
    public static WdmException unavailable(String code, String message) { return new WdmException(503, code, message); }
}
