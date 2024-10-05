package cart.adapter;

public record ReadOnlyRequest(long requestID, byte[] requestData) {}
