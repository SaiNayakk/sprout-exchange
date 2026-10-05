package app.sprout.exchange.domain;

import org.springframework.http.HttpStatus;

/** The stable error codes of the exchange contract. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Invalid request"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Not a member"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "No such order"),
    DUPLICATE_ORDER_ID(HttpStatus.CONFLICT, "Order id already used"),
    MARKET_CLOSED(HttpStatus.UNPROCESSABLE_ENTITY, "Market closed"),
    UNKNOWN_INSTRUMENT(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown instrument"),
    PRICE_OUT_OF_BAND(HttpStatus.UNPROCESSABLE_ENTITY, "Price outside the day's band"),
    INVALID_TICK(HttpStatus.UNPROCESSABLE_ENTITY, "Price not a multiple of the tick size"),
    ORDER_NOT_OPEN(HttpStatus.CONFLICT, "Order not open"),
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Prices unavailable");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
