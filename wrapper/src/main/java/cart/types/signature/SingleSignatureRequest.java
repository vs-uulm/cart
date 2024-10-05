package cart.types.signature;

import cart.RequestType;

public record SingleSignatureRequest(long requestID, RequestType requestType, byte[] requestData, byte[] responseData) implements SignatureRequest {}
